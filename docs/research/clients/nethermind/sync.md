# nethermind — sync

_Commit/branch documented: `0d09a09edd0a861d21c647ceaa7f9f5ea1c74255` (branch `upstream`,
`origin/upstream`). Vendored at `.claude/repo-references/clients/nethermind`. Documented 2026-07-13._

## Architecture summary

nethermind decomposes sync into **independent per-data-type feeds**, each a small state machine
implementing `ISyncFeed<T>` (`Nethermind.Synchronization/ParallelSync/ISyncFeed.cs:11`), paired with
a **`SyncDispatcher<T>`** (`ParallelSync/SyncDispatcher.cs:17`) that pulls one batch at a time from
the feed (`PrepareRequest`), allocates a peer from the shared `ISyncPeerPool`, dispatches the request,
and hands the response back (`HandleResponse`). A feed declares `IsMultiFeed` — when true the
dispatcher keeps issuing requests **concurrently across many peers** (bounded by a processing
semaphore) rather than one-at-a-time. The full set of feeds is: `HeadersSyncFeed`, `BodiesSyncFeed`,
`ReceiptsSyncFeed`, `BlockAccessListsSyncFeed` (the four backward "FastBlocks" downloaders),
`FastSyncFeed`/`FullSyncFeed` (forward block download/execution), plus `StateSyncFeed` and
`SnapSyncFeed` (state acquisition, driven by a lighter `SimpleDispatcher`). This
**feed + dispatcher parallelism is nethermind's signature** — versus geth's single monolithic
`Downloader` with internal fetcher goroutines, besu's single `DefaultSynchronizer` composing one
initial-sync downloader + full-sync downloader, and erigon's strictly serial staged pipeline.

Sitting above every feed is the **`MultiSyncModeSelector`** (`ParallelSync/MultiSyncModeSelector.cs:34`),
a 1-second-tick loop that computes a **flags enum** `SyncMode` (`ParallelSync/SyncMode.cs:9`) — a
*combination* of modes, not one mode — from a `Snapshot` of local progress (processed/state/block/
header block numbers) versus best-peer height and pivot. Each feed subscribes to the selector's
`Changed` event and activates/sleeps itself when its activation flags are (un)set
(`ParallelSync/ActivatedSyncFeed.cs:22-42`). This is how nethermind runs **several feeds
concurrently** — e.g. backward `FastHeaders` in parallel with forward `Full` sync, or `FastSync`
forward-download alongside `StateNodes` healing — and dynamically re-picks the blend as chain state
and peer availability change. Contrast besu, where FULL-vs-SNAP is one static config axis chosen at
startup.

State is acquired by **snap sync first, then trie-node state-sync healing**: `StateSyncRunner`
(`FastSync/StateSyncRunner.cs:35-70`) runs `SnapSyncRunner` to completion (account/storage range
download + bytecode), then runs `TreeSync` rounds to fill/heal the remaining trie nodes, writing both
into the same `INodeStorage` (the Hash/HalfPath world-state store documented in
`storage-persistence.md`). Post-merge, an injected `IBeaconSyncStrategy` feeds the selector a
CL-dictated target block and enables the exclusive `BeaconHeaders` (reverse header) mode; the default
`No.BeaconSync` (`Synchronizer.cs:332`) makes a PoW node peer-driven instead.

## Key types / interfaces / files

### The feed/dispatcher core (`ParallelSync/`)
- `ParallelSync/ISyncFeed.cs:11-36` — **`ISyncFeed<T>`**: `PrepareRequest(token) → Task<T>`,
  `HandleResponse(T, peer) → SyncResponseHandlingResult`, `CurrentState`
  (`Dormant`/`Active`/`Finished`), a `StateChanged` event, `bool IsMultiFeed`, `AllocationContexts
  Contexts` (which peer-pool context to allocate from), `Activate()`/`Finish()`, and
  `SyncModeSelectorOnChanged(SyncMode)`. This is the load-bearing seam — every data type is one
  implementation.
- `ParallelSync/SyncFeed.cs:11-56` — **`SyncFeed<T>`** base: the `Dormant↔Active↔Finished` state
  machine with a `TaskCompletionSource` `FeedTask` that completes on `Finished` (used for shutdown
  join). `Finished` is terminal (`ChangeState` early-returns once finished, `:33-36`).
- `ParallelSync/ActivatedSyncFeed.cs:22-42` — **`ActivatedSyncFeed<T>`**: wires a feed's lifecycle to
  the selector. On `SyncModeSelectorOnChanged`, if `(current & ActivationSyncModes) != None` it
  `InitializeFeed()` + `Activate()`; if the flags clear it `FallAsleep()`. Each concrete feed just
  declares `ActivationSyncModes` (e.g. `FastSyncFeed.cs:15` → `SyncMode.FastSync`).
- `ParallelSync/SyncDispatcher.cs:83-188` — **`SyncDispatcher<T>.DispatchLoop`**: the pull/dispatch
  engine. If the feed is `Dormant` it awaits a dormant `TaskCompletionSource` (no busy-wait,
  `:98-108`); if `Active` it `PrepareRequest` → `Allocate` a peer → `DoDispatch`. **For a single-feed
  it `await`s each dispatch serially; for a `IsMultiFeed` it fires each dispatch on `Task.Run` and
  loops immediately** (`:149-167`), giving per-peer parallelism. `DoDispatch` (`:190-244`) calls
  `Downloader.Dispatch`, then — for multi-feeds — throttles via `_concurrentProcessingSemaphore`
  (default `Environment.ProcessorCount`, `:54-57`) so request issuance can't outrun response
  processing and blow memory (`:216-221`), frees the allocation, and calls `HandleResponse`.
- `ParallelSync/SyncDispatcher.cs:270-297` — **`ReactToHandlingResult`**: maps the feed's
  `SyncResponseHandlingResult` (`OK`/`Emptish`/`LesserQuality`/`NoProgress`/`InternalError`/...) to
  peer-pool reputation calls (`ReportWeakPeer`/`ReportNoSyncProgress`) — the feed judges the response,
  the dispatcher applies the peer consequence.
- `ParallelSync/SimpleDispatcher.cs:24-82` — **`SimpleDispatcher<T>`** + `ISimpleSyncFeed<T>`
  (`ISimpleSyncFeed.cs:14`): a lighter dispatcher for snap+state sync where `PrepareRequest` returns
  `null` to signal completion (breaks the loop, `:51-52`) and blocks internally when waiting for work.
  Still parallel-dispatches under a semaphore (`:64-75`) but drains in-flight tasks with
  `CancellationToken.None` so peer allocations are always freed on shutdown (`:78-81`). Comment: it
  "Replaces `SyncDispatcher` for snap+state sync where sequential execution is required."

### The mode selector (the dynamic blend)
- `ParallelSync/SyncMode.cs:9-68` — **`[Flags] SyncMode`**: `None`, `WaitingForBlock`, `Disconnected`,
  `FastBlocks`(=4) with sub-flags `FastHeaders`/`FastBodies`/`FastReceipts`/`FastBlockAccessLists`
  (each `FastBlocks | bit`), `FastSync`(=8), `StateNodes`(=16), `Full`(=32), `DbLoad`,
  `BeaconHeaders`(=4096), `UpdatingPivot`(=8192). Because it's `[Flags]`, `Current` is a *set*: e.g.
  `FastHeaders | FastReceipts | Full` can all be live at once.
- `ParallelSync/MultiSyncModeSelector.cs:74-96` — **`StartAsync`**: a `PeriodicTimer`
  (`MultiSyncModeSelectorLoopTimerMs`, default ~1s) calling `Update()` each tick.
- `MultiSyncModeSelector.cs:102-197` — **`Update()`**: the decision core. Reads best peer
  (`ReloadDataFromPeers`, `:535-573`, TD-aware and distrusting of peer-reported TD), takes a
  `Snapshot` (`:601-614`), then evaluates each `ShouldBeInXMode(best)` predicate and OR-combines the
  passing flags via `CheckAddFlag` (`:168-178`). When `FastSync` is disabled it collapses to
  `WaitingForBlock`/`BeaconHeaders`/`Full`/`Disconnected` (`:127-151`).
- `MultiSyncModeSelector.cs:290-337` — **`ShouldBeInFastSyncMode`**: forward fast-block download runs
  when not in beacon modes, a post-pivot peer exists, `Header < TargetBlock - TotalSyncLag`, and
  **state was never downloaded** (`stateNotDownloadedYet`). Note the inline comment (`:314-316`):
  "Long range catch-up (switching back to fast/state sync when far behind) was **removed**. Replaying
  blocks in full sync is faster… Fast sync now only activates during initial sync."
- `MultiSyncModeSelector.cs:494-528` — **`ShouldBeInStateSyncMode`**/`ShouldBeInStateNodesMode`: state
  sync runs while `!best.StateDownloaded` (`State < PivotNumber`); the `StickyStateNodesDelta = 32`
  (`:45`) lets `StateNodes` keep flipping with `FastSync` to catch the moving head when peers stop
  serving old trie nodes.
- `MultiSyncModeSelector.cs:396-480` — the four **FastBlocks predicates**: `FastHeaders` "can always
  run if there are peers until it is done… in parallel with all other sync modes" (`:406-408`);
  bodies/receipts/BAL additionally require headers done and **state download finished + not in state
  sync** (`:420`, `:443`, `:468`) so they run in parallel with `Full` sync but not while state is
  still downloading.
- `MultiSyncModeSelector.cs:648-731` — the `ref struct Snapshot`: `Processed/State/Block/Header`
  progress pointers, `Peer.(TotalDifficulty,Block)`, `TargetBlock`, `PivotNumber`, and derived
  `StateDownloaded => State >= PivotNumber`, `AnyPostPivotPeerKnown => Peer.Block > PivotNumber`.
  `IsSnapshotInvalid` (`:616-624`) enforces the ordering invariant `Block ≤ Header`, `State ≤ Header`,
  `Processed ≤ Block`; on violation it recalculates progress pointers once, else throws (`:577-599`).

### FastBlocks — parallel backward download to genesis
- `FastBlocks/HeadersSyncFeed.cs:32-95` — **`HeadersSyncFeed`**: an `ActivatedSyncFeed`
  (`ActivationSyncModes` include `FastHeaders`) that downloads headers **backward from the pivot to
  genesis in parallel batches**. `_pending` (re-queued partial/invalid batches), `_sent` (in flight),
  and a `_dependencies` map of out-of-order responses waiting to be stitched in
  (`:56-68`); `AllHeadersDownloaded` when `LowestInsertedHeader ≤ 1` (`:89`). This backward,
  dependency-stitched, memory-budgeted (`_fastHeadersMemoryBudget`, `:48`) parallel download is
  nethermind's distinctive **FastBlocks** capability.
- `FastBlocks/` — `BodiesSyncFeed`, `ReceiptsSyncFeed`, `BlockAccessListsSyncFeed` mirror the same
  backward-fill shape for their data type, each with its own `*SyncBatch`, `*SyncDownloader`/
  `*SyncDispatcher`, and `FastBlocksPeerAllocationStrategyFactory`. `BarrierSyncFeed` is the shared
  base handling the pivot/barrier bookkeeping. All are `IsMultiFeed` so the dispatcher parallelizes
  them across peers.

### Forward block sync (fast-insert vs full-execute)
- `Blocks/FastSyncFeed.cs:12-34` — **`FastSyncFeed`** (`ActivationSyncModes = FastSync`,
  `IsMultiFeed = true`): forward download with `DownloaderOptions.Insert | WithReceipts` — headers/
  bodies/receipts are **inserted without executing** (state comes from snap/state sync), driven by an
  injected `IForwardSyncController` (`MultiBlockDownloader`/`BlockDownloader`).
- `Blocks/FullSyncFeed.cs:11-33` — **`FullSyncFeed`** (`ActivationSyncModes = Full`): same controller
  but `DownloaderOptions.Process` — every block is **executed** against state. Both feeds sleep by
  pruning their download buffer (`PruneDownloadBuffer`, `FastSyncFeed.cs:29-33`).

### State acquisition: snap then heal, into `INodeStorage`
- `FastSync/StateSyncRunner.cs:35-70` — **`StateSyncRunner.Run`**: the state-acquisition orchestrator.
  Short-circuits if `FindBestFullState() != 0` (already have state); waits a precursor gate; **tunes
  the state DB to a bulk-write profile** (`TuneStateDb`, an `ITunableDb` call — the runtime compaction
  profile from `storage-persistence.md`); then, if `SnapSync` enabled, `await snapSyncRunner.Run` to
  completion, then `RunStateSyncRounds` (trie-node healing). Snap fills the bulk of state via ranges;
  the state-sync rounds fill/heal the trie nodes snap couldn't.
- `SnapSync/SnapSyncFeed.cs:14-110` — **`SnapSyncFeed`** (`ISimpleSyncFeed<SnapSyncBatch>`):
  `PrepareRequest` pulls the next account-range / storage-range / bytecode / accounts-to-refresh batch
  from `ISnapProvider` (`:33-44`); `HandleResponse` dispatches by batch kind to
  `AddAccountRange`/`AddStorageRange`/`AddCodes`/`RefreshAccounts` (`:73-102`). `AccountsToRefresh` is
  the **snap→heal bridge**: accounts whose storage/code was incomplete get re-fetched.
- `SnapSync/SnapProviderHelper` + `PatriciaSnapTrieFactory.cs:12-27` — snap ranges are written through
  a `RawScopedTrieStore(nodeStorage, address)` into the same **`INodeStorage`** (Hash or HalfPath
  scheme) as normal block processing; `PatriciaSnapStateTree`/`PatriciaSnapStorageTree` verify range
  proofs and persist the reconstructed nodes. This is where snap sync "writes into the Hash/HalfPath
  NodeStorage" concretely.
- `FastSync/TreeSync.cs` + `PatriciaTreeSyncStore.cs:14-26` — **`TreeSync`** is the classic
  trie-node-by-hash state syncer (the heal phase). `StateSyncFeed.cs:16-46` wraps it as an
  `ISimpleSyncFeed<StateSyncBatch>`: `PrepareRequest` returns `null` when `IsSyncRoundFinished()`,
  else pulls a batch of missing trie-node hashes; responses are persisted via `nodeStorage.Set(...)`
  (`PatriciaTreeSyncStore.cs:20`). `UnknownNodeResolver.cs:32` pins the heal read `Scheme => Hash`.

### Peer pool + allocation
- `Peers/AllocationContexts.cs:8-21` — **`[Flags] AllocationContexts`**: `Headers`(1), `Bodies`(2),
  `Receipts`(4), `Blocks`(6 = `Bodies|Receipts`), `State`(8), `Snap`(16), `ForwardHeader`(32),
  `BlockAccessLists`(64). Each feed allocates peers from its own context so, e.g., a `Snap` allocation and
  a `FastHeaders` allocation don't contend — the mechanism that lets multiple feeds share one peer
  pool without starving each other.
- `Peers/SyncPeerPool.cs` + `SyncPeerAllocation.cs` — `Allocate(strategy, contexts, timeout, token)`
  hands each dispatcher a peer chosen by a per-feed `IPeerAllocationStrategyFactory<T>` (e.g.
  `BlocksSyncPeerAllocationStrategyFactory`, `FastBlocksPeerAllocationStrategyFactory`,
  `SnapSyncAllocationStrategyFactory`); `Free`/`ReportWeakPeer`/`ReportNoSyncProgress` close the loop.

### Wiring (`Synchronizer.cs` / `SynchronizerModule`)
- `Synchronizer.cs:65-100` — **`Start()`**: always starts the full-sync component; if `FastSync`, also
  starts FastBlocks, FastSync, and Snap+State components; then `WireMultiSyncModeSelector()` subscribes
  every feed to the selector and finally launches the selector loop *after* two gates (DB block-load
  finished + sync pivot resolved, `StartModeSelectorAfterGates`, `:102-120`) — the old `DbLoad`
  sync-mode is now this explicit gate rather than a selector tick.
- `Synchronizer.cs:317-519` — **`SynchronizerModule`** (Autofac): registers one
  `SyncFeedComponent<TBatch>` (feed+dispatcher+downloader bundle, `SyncFeedComponent.cs:15`) per data
  type, `MultiSyncModeSelector` as the `ISyncModeSelector`, and the snap/state feeds behind
  `SimpleDispatcher`s (`:431-510`). Feeds are conditionally swapped for `NoopSyncFeed<T>` when their
  config toggle is off (`ConfigureFastHeader`, `ConfigureReceiptSyncComponent`, etc.) — a disabled
  data type is a no-op feed, not a branch in the selector.

## Design decisions & rationale

- **One feed per data type, each independently dispatchable.** Rather than a single downloader with
  internal concurrency (geth) or one synchronizer object (besu), nethermind makes each data type
  (`headers`/`bodies`/`receipts`/`BAL`/`blocks`/`state`/`snap`) a self-contained `ISyncFeed<T>` state
  machine plus a `SyncDispatcher<T>`. The dispatcher is generic and written once; adding a data type
  (block access lists, EIP-7928) is a new feed + batch type + allocation strategy, not a new branch in
  a monolith. `IsMultiFeed` is the per-feed switch between "one request at a time" and "fan out across
  every available peer" (`SyncDispatcher.cs:149-167`).
- **`MultiSyncModeSelector` as a dynamic, combinable mode blend.** `SyncMode` is a `[Flags]` set and
  the selector recomputes it every second from a `Snapshot` of local-progress-vs-peer-height. This
  lets fundamentally different activities overlap — backward FastHeaders while forward Full-sync-ing,
  or FastSync-forward alongside StateNodes-healing to chase a moving head — and lets the node fluidly
  re-blend as peers appear/disappear or the CL advances the target. besu's static FULL/SNAP config
  choice cannot express "headers backward + full forward simultaneously"; nethermind's selector is
  built for exactly that. The predicate-per-mode structure (`ShouldBeInXMode`) with per-check trace
  logging (`LogDetailedSyncModeChecks`, `:626-646`) makes the (otherwise opaque) decision auditable.
- **Snapshot-ordering invariant with self-heal.** Every tick validates
  `Processed ≤ Block ≤ Header` and `State ≤ Header` (`IsSnapshotInvalid`, `:616-624`); a transient
  violation (a feed advanced a pointer mid-read) triggers one `RecalculateProgressPointers` retry
  before erroring — cheap protection against acting on a torn read of progress state.
- **Snap-first, heal-second, one `INodeStorage`.** State is bulk-loaded by snap ranges (contiguous
  flat leaves + Merkle proofs, geth's protocol) written straight into the Hash/HalfPath NodeStorage,
  then the residual/inconsistent trie nodes are filled by `TreeSync` (fetch-by-hash healing). Both
  write the same store, so the storage layer's dual-key-scheme and tunable-compaction machinery
  (`storage-persistence.md`) serves sync unchanged — `StateSyncRunner` explicitly flips the state DB
  to a bulk-write `ITunableDb` profile for the duration.
- **`SimpleDispatcher` for snap/state, `SyncDispatcher` for the rest.** Snap and state feeds signal
  completion by returning `null` and manage their own internal work queues (`SnapProvider`/`TreeSync`),
  so they use the lighter `SimpleDispatcher` (no Dormant/Active event plumbing) while the block/header
  feeds — which must react to selector mode changes — use the full `SyncDispatcher` + `ActivatedSyncFeed`.
- **Per-context peer allocation.** `AllocationContexts` flags partition the shared `ISyncPeerPool` so
  concurrent feeds request non-conflicting peer slots; a peer can serve `Snap` for one feed while
  another serves `Headers` for another. This is what makes "many feeds, one pool, no starvation"
  work.
- **Post-merge via an injected strategy, not a fork of the engine.** `IBeaconSyncStrategy`
  (`Synchronizer.cs:332`, default `No.BeaconSync`) supplies `GetTargetBlockHeight()` and the
  `ShouldBeInBeaconHeaders/ModeControl` gates. The merge plugin injects a real strategy; a PoW node
  keeps the no-op and stays peer-driven. The selector logic is written once for both.

## Notable patterns (the reusable idea)

1. **Per-data-type feed + generic dispatcher.** `ISyncFeed<T>` (a `PrepareRequest`/`HandleResponse`
   state machine) × `SyncDispatcher<T>` (pull → allocate peer → dispatch → handle → report) is the
   cleanest decomposition of "download N independent data types over a shared peer pool, some in
   parallel" in the reference set. `IsMultiFeed` is the single knob for per-feed parallelism.
2. **Dynamic combinable mode selector.** A periodic loop that emits a `[Flags]` *combination* of sync
   modes from a progress-vs-peer snapshot, letting heterogeneous feeds overlap and re-blend live —
   structurally different from besu's static mode choice and geth's implicit single-cycle mode.
3. **FastBlocks: parallel backward download to genesis.** Headers/bodies/receipts/BAL fetched
   backward from the pivot in concurrent, dependency-stitched, memory-budgeted batches — a distinctive
   nethermind capability that runs in parallel with forward sync.
4. **Snap-then-heal into a single tunable node store**, with the state DB switched to a bulk-write
   compaction profile for the duration and reverted after.
5. **Per-context peer allocation** over one pool as the anti-starvation mechanism for many concurrent
   feeds.
6. **Response-quality → peer-reputation mapping** centralized in the dispatcher
   (`ReactToHandlingResult`), so feeds return a verdict and the dispatcher owns the peer consequence.

## Authority note

**nethermind is THE authority for the parallel feed+dispatcher sync decomposition and the dynamic
`MultiSyncModeSelector`, and for FastBlocks (parallel backward header/body/receipt download).** No
other reference client combines sync modes as a live-recomputed flag set across independent per-type
feeds; geth uses one `Downloader`, besu one `DefaultSynchronizer` with a static mode, erigon a serial
staged pipeline. Where the question is "run several data-type syncs in parallel and dynamically pick
which blend to run," nethermind leads.

Authority boundaries:
- **go-ethereum remains the authority for snap-protocol wire semantics** — `GetAccountRange`/
  `GetStorageRanges`/`GetByteCodes`/`GetTrieNodes`, range-proof placement, and the originating design.
  nethermind *consumes* snap (its `SnapProvider` + range-proof verification) and writes into
  NodeStorage, but the wire protocol a fukuii node must match byte-for-byte is geth's, not
  nethermind's.
- **core-geth remains the authority for PoW/ETC sync semantics** (ETChash, ECIP-1017 emission
  continuity). nethermind's selector is chain-agnostic in shape and not an ETC reference; nothing here
  is ETC-specific.
- **erigon is the alternative authority for a staged, restartable, flat-state batch pipeline** — the
  structurally opposite decomposition (serial stages, one progress integer per stage) to nethermind's
  parallel feeds. nethermind's parallelism and erigon's serial-resumability are the two poles.
- **besu is the authority for the sync-mode-as-config-axis / per-consensus-family `PivotBlockSelector`
  pattern** — nethermind expresses "which strategy" as a *runtime-computed flag blend* rather than a
  config-selected mode + swappable pivot selector.

## The comparative point for fukuii (alternative sync architecture)

fukuii's sync is **actor-based fast/regular/SNAP** (Pekko actors, per `blockchain/sync/AGENTS.md`) —
message-passing, closer in spirit to nethermind's independent-workers model than to erigon's serial
stages, but organized as actors rather than feed+dispatcher pairs behind a mode selector. The
**portable ideas**, decoupled from C#/Autofac specifics:

- **Independent parallel feeds + a dynamic mode selector.** The reusable structure is (a) one
  small `PrepareRequest`/`HandleResponse` state machine per data type, (b) a generic dispatcher that
  turns that into "pull a batch → allocate a peer → dispatch → report peer quality," and (c) a single
  periodic selector that emits a *combination* of active modes from a progress-vs-peer snapshot, which
  each feed self-activates against. fukuii could map each to a supervised actor + a selector actor
  publishing a mode-set, gaining the "backward headers ∥ forward full ∥ state heal" concurrency
  nethermind gets — a **different decomposition than fukuii's current single snap/v1 path** and the
  seed for the Phase-4 "expand fukuii's single snap/v1 into a multi-sync-approach" theme.
- **FastBlocks (parallel backward fill).** A dedicated backward, dependency-stitched, memory-budgeted
  header/body/receipt downloader that runs *in parallel* with forward sync — a capability to evaluate
  against fukuii's current forward-only fast/regular sync.
- **Per-context peer allocation** so many concurrent feeds share one peer pool without starving each
  other — directly relevant if fukuii parallelizes its actors over a shared peer set.
- **`IsMultiFeed` as an explicit per-feed parallelism switch** and **`SimpleDispatcher` vs
  `SyncDispatcher`** as the "self-completing work-queue feed" vs "selector-reactive feed" split — a
  clean way to keep snap/state (which own their queues) distinct from block/header feeds (which react
  to mode changes).

Record for Phase 3–4 as: nethermind = the "independent parallel feeds + dynamic mode selector +
FastBlocks" reference. The takeaway is the *decomposition* (per-type feed/dispatcher and a combinable
mode selector), not the Autofac wiring; geth stays authoritative for the snap wire protocol fukuii
must serve/consume.

## Gotchas / anti-patterns / things they later changed

- **"Long range catch-up" was removed from the selector.** `ShouldBeInFastSyncMode`'s inline comment
  (`MultiSyncModeSelector.cs:314-316`): fast/state sync no longer re-activates when a synced node falls
  far behind — "Replaying blocks in full sync is faster and doesn't depend on snap endpoints. Fast
  sync now only activates during initial sync when state was never downloaded." A reader assuming
  nethermind re-snaps after a long outage is reading an older design.
- **`DbLoad` is no longer a real selector mode.** The `SyncMode.DbLoad` flag survives in the enum but
  the block-DB-load wait is now an explicit gate before the selector even starts
  (`Synchronizer.cs:102-120`), not a mode the selector emits each tick. Don't infer live `DbLoad`
  behavior from the enum.
- **The mode set can hold several flags at once — treat `Current` as a set, not a scalar.** Code that
  switches on `Current ==` a single mode is wrong; the selector routinely OR-combines (e.g.
  `FastHeaders | FastReceipts | Full`). Use `HasFlag`/the `SyncModeExtensions` helpers
  (`SyncMode.cs:70-108`).
- **`IsMultiFeed` dispatch has a subtle active-task-accounting race.** The comment at
  `SyncDispatcher.cs:144-148` documents a real bug: a non-`async` dispatch lambda would signal task
  completion the moment `DoDispatch` yielded on the semaphore await, dropping the in-flight count
  while the dispatch was still running (the flaky `When_ConcurrentHandleResponseIsRunning_Then_
  BlockDispose` test). The lambda must be `async` so the `finally` runs after the whole task. A footgun
  for anyone porting the dispatcher.
- **`SnapSyncFeed`'s single-peer failure handling deliberately does NOT punish the only peer.**
  `AnalyzeResponsePerPeer` (`SnapSync/SnapSyncFeed.cs:112-221`): after >`AllowedInvalidResponses`(=5)
  failures from a peer, if it's the *only* peer in the window and there were no successes, the failure
  stream is treated as a **stale pivot** (`UpdatePivot()` + clear the log) rather than a bad peer —
  punishing the sole peer would stall sync. Any port must preserve the "scan the whole window first"
  ordering (`:149-159`) or the guard fires prematurely.
- **Snap→state healing reads pin the Hash key scheme.** `UnknownNodeResolver.cs:32` returns
  `KeyScheme.Hash`; the heal path resolves trie nodes content-addressed even when the datadir default
  is HalfPath. Cross-reference `storage-persistence.md`'s dual-scheme NodeStorage — the sync heal path
  is one of the concrete consumers of the fallback-read behavior.
- **State sync is skipped entirely if any full state already exists.** `StateSyncRunner.Run` returns
  early when `FindBestFullState() != 0` (`FastSync/StateSyncRunner.cs:38-42`) — snap/state sync only
  ever bootstraps a fresh datadir; a partially-synced node resumes via `TreeSync`'s own persisted
  progress, not by re-entering the runner.
</content>
</invoke>
