# besu — sync

_Commit/branch documented: `3fd233a4f93556e932f734d8feecbad4a047ff67` (branch `upstream`,
`origin/upstream`). Vendored at `.claude/repo-references/clients/besu`. Documented 2026-07-13._

## Architecture summary

besu structures sync as a **single `Synchronizer` (`DefaultSynchronizer`) that composes at most one
initial-sync downloader in front of a permanent full-sync downloader**, selected by one config axis —
`SyncMode` — that at this HEAD has been **narrowed to exactly two values: `FULL` and `SNAP`**
(`ethereum/eth/.../sync/SyncMode.java:21-25`). Everything else that "used to be a sync mode" —
**FAST** and **CHECKPOINT** — has been **collapsed into SNAP** (see the deprecation notes below).
The multiple-strategies story therefore does **not** live in the `SyncMode` enum anymore; it lives in
**three orthogonal, still-first-class seams** underneath it:

1. **`SyncMode` (FULL vs SNAP)** — the coarse "execute every block" vs "download a pivot's flat state
   then heal" choice, wired in `DefaultSynchronizer`'s constructor (`.../DefaultSynchronizer.java:105,
   125-158`).
2. **`PivotBlockSelector`** — a per-**consensus-family** pluggable interface (`.../PivotBlockSelector.java`)
   that decides *where the sync target comes from*: peers (PoW), the Engine-API FCU **safe block** (PoS),
   or a BFT round (QBFT/IBFT2). This is besu's real "which sync strategy for which node role/network"
   axis, auto-selected in `BesuControllerBuilder.createPivotSelector` (`:1127-1185`).
3. **A genesis-embedded `Checkpoint`** — a trusted `(blockNumber, blockHash, totalDifficulty)` anchor
   baked into the chain-spec (`.../common/checkpoint/Checkpoint.java`) that seeds `SyncState` so a fresh
   node bootstraps *from the checkpoint instead of genesis*. This is what "checkpoint sync" became — not
   a mode, but a data anchor consumed automatically under SNAP.

Alongside those, two auxiliary pipelines exist: the **backward sync** (`.../sync/backwardsync/`), which
fills the header/body gap *backwards* from a CL-announced head after the merge, and an optional **ERA1
import prepipeline** (`.../fullsync/era1prepipeline/`) that pre-loads history from ERA1 files ahead of a
FULL sync. The **snap-serving side** (`.../manager/snap/SnapServer.java`) is a separate, DoS-bounded
responder that serves flat-state ranges to other syncing nodes off the Bonsai flat DB.

## The full sync-mode set (and where FAST/CHECKPOINT went)

| Requested mode | Status at this HEAD | Node role served | Storage format implied |
|---|---|---|---|
| **FULL** | Supported. Execute & validate every block from the chain tip (or from a genesis checkpoint / ERA1 import). | **Archival / full-verifying** node. | Forest **or** Bonsai (Bonsai + trie-log pruning gives a pruned-full node; Forest or `X_BONSAI_ARCHIVE` gives archival). |
| **SNAP** | Supported, and the **default when `--network` is supplied** (`BesuCommand.java:438`). Download flat account/storage ranges at a moving pivot + Merkle proofs, then heal. | **Fast-bootstrap full** node (recent state only). | **Requires Bonsai / path-based flat DB** — the snap server and flat-heal paths read/write the flat account & storage column families (`SnapServer` casts to `BonsaiWorldStateKeyValueStorage`; `--Xsnapsync-synchronizer-flat*` "can only be used when `--Xbonsai-full-flat-db-enabled` is true", `BesuCommand.java:1931`). |
| **FAST** (geth-style trie-node fast sync) | **Removed.** No enum value; the world-state downloader is snap-only. The `fastSync*` field/method names that survive in `DefaultSynchronizer`/`SnapDownloaderFactory`/`SnapSyncDownloader` are **legacy names now wrapping `SnapSyncController`** — not a distinct algorithm. | — | — |
| **CHECKPOINT** (start from a trusted checkpoint block) | **Removed as a mode, folded into SNAP.** `--Xcheckpoint-post-merge-enabled` is a **deprecated no-op** that only logs a warning (`SynchronizerOptions.java:380-393`). Requesting checkpoint behaviour now yields: _"Using SNAP sync mode instead. Your checkpoint configuration will be used automatically."_ (`BesuCommand.java:444`). The checkpoint **data** still drives the bootstrap (below). | **Fast-bootstrap** node (was: skip download below a trusted block). | Bonsai (via SNAP). |
| `X_*` snap variants | **snap/2** exists (`--Xsnap2-enabled`, default **false**, `SnapSyncConfiguration.java:50`) — a BAL-diff-based flat-state syncer (`sync/snapsync/v2/`, `request/v2/`). It is **feature-flag-gated and not yet wired**: `SnapDownloaderFactory.createSnapDownloader` has a `snap2Enabled` branch that is a no-op comment and **falls through to v1** (`SnapDownloaderFactory.java:63-80`). Treat as experimental. | — | Bonsai. |

**The comparative headline for fukuii:** besu's *user-visible* mode surface has actually **contracted**
to `FULL`/`SNAP`, because CHECKPOINT and FAST were subsumed rather than kept as parallel modes. The
"multiple approaches" that matter for node roles are now expressed **below** the mode enum — as the
`PivotBlockSelector` family, the genesis checkpoint anchor, the FULL-vs-SNAP storage coupling, and the
ERA1 prepipeline — each an independently selectable seam. This is the more instructive pattern than a
long mode enum: *make each independent decision its own seam, and auto-bind it to the consensus family /
storage format / chain-spec rather than making the operator hand-pick a monolithic mode.*

## Key types / interfaces / files

### Orchestrator + mode selection
- `.../sync/SyncMode.java:21-25` — the two-value enum `FULL`, `SNAP`. `normalize()` for CLI display.
- `.../sync/DefaultSynchronizer.java:64-173` — the top-level `Synchronizer`. Holds `Optional<FullSyncDownloader>`
  (always present unless the termination condition says stop) **and** `Optional<SnapSyncController>`
  (`fastSyncDownloader`, present only when `SyncMode.SNAP`). The `fastSyncFactory` switch (`:140-158`)
  builds a `SnapDownloaderFactory.createSnapDownloader(...)` for SNAP and `Optional.empty()` otherwise.
- `.../sync/DefaultSynchronizer.java:184-284` — `start()`: if a snap downloader is present, run it, then
  on completion (`handleSyncResult`, `:229-272`) reset the world-state archive to the pivot header and
  hand off to `startFullSync()`; otherwise go straight to full sync. `resyncWorldState()`/`healWorldState()`
  (`:314-368`) tear down and re-create the snap downloader to repair a corrupt/incomplete world state —
  the runtime "re-enter snap to fix state" path.
- `app/.../cli/BesuCommand.java:435-455` — `--sync-mode`: **default SNAP if `--network` is supplied, FULL
  otherwise**; a checkpoint request is coerced to SNAP with a log line; only `FULL`/`SNAP` accepted.
- `.../sync/SynchronizerConfiguration.java:78-174, 316` — the config object; `syncMode` default `FULL`
  in the builder (`:316`), overridden per-network by the CLI. Carries every downloader tunable
  (pivot distance, parallelism, world-state request sizing, checkpoint-retry count, ERA1 settings).

### Pivot-block selection — the per-consensus-family strategy seam
- `.../sync/PivotBlockSelector.java:21-36` — the interface: `selectNewPivotBlock()`, `prepareRetry()`,
  `getBestChainHeight()`, `getMinRequiredBlockNumber()`, `close()`. This is the pluggable "where does the
  sync target come from" contract.
- `app/.../controller/BesuControllerBuilder.java:1127-1185` — `createPivotSelector()`, the binding:
  - **BFT (QBFT/IBFT2)** → `BFTPivotSelectorFromPeers` (`:1134-1145`).
  - **PoS (TTD present)** → `PivotSelectorFromSafeBlock` (`:1146-1176`), subscribed to the merge context
    as a `NewPayloadListener` + `UnverifiedForkchoiceListener`.
  - **PoW (no TTD)** → `PivotSelectorFromPeers` (`:1177-1184`).
- `.../sync/common/PivotSelectorFromPeers.java:35-144` — PoW/peer-driven: pick the best fully-validated
  peer once `syncMinimumPeerCount` height estimates exist (`selectBestPeer`, `:116-137`), set pivot =
  `bestPeerHeight - syncPivotDistance` (`:104`), and **reuse the same pivot while the best peer's head
  stays within `pivotBlockWindowValidity` blocks** (`:92-102`) to avoid churning the pivot.
- `.../sync/common/PivotSelectorFromSafeBlock.java:46-249` — PoS/Engine-API-driven: pivot is anchored to
  the FCU **safe block** (or `head - PIVOT_DISTANCE=64` if no safe block, `:57,202`), reused until the
  head advances past an **`effectiveThreshold` that shrinks by one per estimated missed slot** since the
  last FCU (`:167-200`); if it reaches zero the selector **fails with "consensus client appears offline"**
  (`:172-178`) — a built-in CL-liveness guard. Caches head/payload headers in a Caffeine cache
  (`maximumSize 1000`, `:71-72`) pruned below the finalized block (`:121-128`).

### Genesis checkpoint anchor ("checkpoint sync" today)
- `.../sync/common/checkpoint/Checkpoint.java:22-30` — the immutable value: `blockNumber()`, `blockHash()`,
  `totalDifficulty()`. Constructed as `ImmutableCheckpoint`.
- `app/.../controller/BesuControllerBuilder.java:761-786` — read straight from the chain-spec
  (`genesisConfigOptions.getCheckpointOptions().isValid()`) and passed into `SyncState`. When present,
  the node treats the checkpoint block as its starting head instead of genesis — the fast-bootstrap
  substitute for the deleted CHECKPOINT mode.
- `.../sync/state/SyncState.java:60,78,100,345-346` — stores the `Optional<Checkpoint>`; downstream sync
  components read `getCheckpoint()` to bound how far back they need to fetch.

### Snap downloader wiring + state download / heal
- `.../sync/snapsync/SnapDownloaderFactory.java:50-184` — builds the snap stack: a `ChainSyncState`
  loaded from `syncFolder` for resume (`:102-107`), a `SnapSyncProcessState` (pivot header), an
  `InMemoryTasksPriorityQueues<SnapDataRequest>` task collection, either `SnapWorldStateDownloader` (v1)
  or `SnapV2WorldStateDownloader` (v2, gated), wrapped in a `SnapSyncDownloader` with `PivotSyncActions`.
  If SNAP is requested but the **local chain is non-empty**, it logs and returns `empty()` — snap only
  bootstraps a fresh datadir (`:118-124`).
- `.../sync/snapsync/SnapSyncDownloader.java:47-237` — the pivot-based control loop: `findPivotBlock`
  → `selectPivotBlock` → `downloadPivotBlockHeader` → run chain download **and** world-state download
  concurrently, cancelling one if the other fails (`downloadChainAndWorldState`, `:191-231`).
  `handleFailure` (`:102-133`) **re-pivots to a newer block** on `StalledDownloadException` /
  `MaxRetriesReachedException`, and retries after a 5s delay on `NoAvailablePeersException` — the moving-
  pivot resilience. `deletePivotSyncState` wipes the sync folder (no snap resume yet, `:146-158`).
- `.../sync/snapsync/SnapWorldStateDownloader.java:54-219` — the world-state download entry; can
  **restart at just the heal step** if an accounts-healing list already exists (`:192`), and drives a
  `DynamicPivotBlockSelector` (`:218-219`) that advances the pivot mid-download.
- `.../sync/snapsync/SnapWorldDownloadState.java:78-244` — the heal state machine. `checkCompletion`
  (`:166-...`) sequences **two heal phases**: once all range tasks finish it (1) **starts trie heal**
  (`startTrieHeal`, fetch the trie nodes that stitch the moving-pivot chunk boundaries together), then
  (2) if the flat DB mode is FULL, **starts flat-database heal** (`startFlatDatabaseHeal`, verify/repair
  the flat account & storage entries against the healed trie) before notifying completion (`:178-205`).
  Separate pending queues for trie-node, flat-account and flat-storage heal requests (`:78-85`).

### Snap-serving side + DoS bounds
- `.../manager/snap/SnapServer.java:72-88` — the responder. Constants: `MAX_RESPONSE_SIZE = 2 MiB`,
  `MAX_ENTRIES_PER_REQUEST = 100000`, `MAX_CODE_LOOKUPS_PER_REQUEST = 1024`,
  `MAX_STORAGE_RANGE_ACCOUNTS_PER_REQUEST = 4096`, `PRIME_STATE_ROOT_CACHE_LIMIT = 128`, plus a
  per-request wall-clock cap `maxMillisPerRequest` (`ResponseSizePredicate.DEFAULT_MAX_MILLIS_PER_REQUEST`).
- `.../manager/snap/SnapServer.java:200-296` — serves off the **Bonsai flat DB by state root**: at
  startup it primes a cache of the **latest 128 world states** by root hash (`primeRootToBlockHashCache`,
  `:212`) and requires **code stored by code-hash** (`:218-219`). Each `constructGetXResponse` (account
  range `:298-...`, storage range, bytecodes, trie nodes, plus snap/2 block-access-lists `:254-296`)
  streams entries under an `ExceedingPredicate`/`ResponseSizePredicate` that stops at the byte cap,
  entry cap, or time cap — the same "clamp everything, add proofs only at capped boundaries" shape geth
  uses, but bounded by both size **and** wall-clock time per request.
- `.../manager/snap/SnapProtocolManager.java` — advertises the snap sub-protocol; snap-server capability
  gated by `--snapsync-server-enabled` (default **false**, `SnapSyncConfiguration.java:49`) and by
  `snapServerPeersNeeded` bookkeeping (`BesuControllerBuilder.java:882-887`).

### Full sync + auxiliary pipelines
- `.../sync/fullsync/FullSyncDownloader.java:37-111` — full sync: a `FullSyncChainDownloader` that
  fetches & **executes every block**, optionally preceded by an **ERA1 import prepipeline**
  (`:60-76`) when `--era1-import-prepipeline-enabled` + `--era1-data-uri` are set and mode is FULL —
  history is imported from local/HTTP ERA1 files first, then live full sync continues (`:91-106`). This
  is besu's archival fast-bootstrap: get history cheaply from files instead of re-downloading from peers.
- `.../sync/backwardsync/BackwardSyncContext.java:45-66` — post-merge backward fill: given a CL-announced
  head hash with no local parent, download the header/body chain **backwards** in `BATCH_SIZE = 200`
  segments until it links to the local chain, with retry/bad-chain handling. The PoS "follow the CL's
  head" counterpart to geth's skeleton syncer.

### Config axis surface (CLI)
- `app/.../cli/options/SynchronizerOptions.java` — the full tunable surface. Note `--sync-mode` lives on
  `BesuCommand` itself; `SynchronizerOptions` carries the sub-knobs: pivot distance (`--Xsynchronizer-pivot-distance`),
  world-state request sizing/parallelism, snap per-request counts (`--Xsnapsync-synchronizer-*`), the
  snap pivot window (`--Xsnapsync-synchronizer-pivot-block-window-validity`, default **120**,
  `SnapSyncConfiguration.java:28`), snap-server enable, snap/2 enable, ERA1 settings, and the
  **deprecated no-op** `--Xcheckpoint-post-merge-enabled` (`:380-393`) and deprecated no-op
  `--Xsnapsync-synchronizer-pivot-block-distance-before-caching` (`:305-318`).

## Design decisions & rationale

- **Collapse FAST and CHECKPOINT into SNAP rather than keep parallel modes.** besu deliberately reduced
  the mode surface to `FULL`/`SNAP`. FAST (geth trie-node fast sync) is simply gone; CHECKPOINT is
  reduced to a **genesis-embedded data anchor** that SNAP consumes automatically. The rationale visible
  in code: a checkpoint is *data about where to start*, not a *different algorithm* — so it belongs in
  the chain-spec + `SyncState`, and the algorithm is always SNAP. This avoids the combinatorial mode
  matrix (fast×checkpoint×post-merge) geth accumulated. The legacy `fastSync*` identifiers surviving in
  the snap classes are a naming fossil of that consolidation, not a second code path.
- **`PivotBlockSelector` as the per-consensus-family seam.** Instead of encoding PoW-vs-PoS-vs-BFT sync
  differences in the mode, besu keeps one SNAP algorithm and swaps only *how the pivot/target is chosen*.
  PoW asks peers; PoS asks the Engine-API safe block (with a CL-liveness guard baked into the selector);
  BFT uses a round. The downloader machinery above is written once. This is the cleanest expression of
  "one sync engine, many target-selection strategies" in the reference set.
- **Storage-format coupling is explicit and enforced.** SNAP requires Bonsai flat state (the snap server
  reads/writes flat account & storage column families; the flat-heal knobs error out without
  `bonsai-full-flat-db`). FULL works on Forest (archival) or Bonsai (pruned). besu treats sync mode and
  storage format as a **coupled pair**, not independent — matching `storage-persistence.md`'s finding
  that Forest and Bonsai are fully separate code paths.
- **Two-phase heal (trie heal → flat-DB heal).** Because the pivot moves during a range download, chunk
  boundaries are inconsistent. besu heals the trie nodes first, then — because Bonsai *also* keeps flat
  state — runs a **second flat-database heal** to reconcile the flat leaves with the healed trie. geth's
  hash-scheme snap has only the trie-node heal; besu's Bonsai needs the extra flat pass.
- **Bounded by size AND wall-clock time on the serving side.** Every snap response is clamped by byte
  cap, entry cap, per-kind lookup cap, **and** a per-request millisecond budget — a stricter DoS envelope
  than geth's size+lookup caps, appropriate for a JVM server where a pathological iteration could pin a
  thread.
- **ERA1 prepipeline for archival bootstrap.** Rather than force archival/full nodes to re-download all
  history from peers, besu can import it from ERA1 files (local or HTTP) as a FULL-sync front-end — a
  file-based fast path that keeps full-verification semantics.

## Notable patterns (the reusable ideas)

- **Decompose "which sync strategy" into orthogonal seams instead of one mode enum.** The exportable
  lesson for fukuii: `SyncMode` (FULL/SNAP) × `PivotBlockSelector` (peer/safe-block/BFT) × genesis
  checkpoint anchor × storage format are four *independent, auto-bound* decisions. Adding a strategy
  means adding a `PivotBlockSelector` or a checkpoint source, not branching a monolithic mode.
- **`PivotBlockSelector` interface** — a small, testable seam (`selectNewPivotBlock`/`prepareRetry`/
  `getBestChainHeight`) with one implementation per consensus family, chosen at controller-build time.
  The single most directly portable abstraction for fukuii's "sync tailored to node role" goal.
- **CL-liveness guard inside the pivot selector** (`PivotSelectorFromSafeBlock`'s shrinking
  `effectiveThreshold` → "consensus client appears offline") — the sync layer detecting a stalled CL and
  failing loudly rather than silently pivoting into an unavailable window.
- **Checkpoint-as-chain-spec-data** — a trusted `(number, hash, totalDifficulty)` in the genesis config,
  consumed automatically, replacing a whole sync *mode* with a data anchor.
- **Two-phase heal for flat + trie coexistence** — trie heal then flat-DB heal, the pattern any
  flat-state-plus-thin-trie client (Bonsai, and fukuii's `FlatAccountStorage`/`FlatSlotStorage`) needs.

## Authority note

**besu is the authority for two things in this subsystem: (1) the sync-mode-as-config-axis /
per-consensus-family `PivotBlockSelector` pattern, and (2) checkpoint bootstrap** — even though, at this
HEAD, checkpoint has been *demoted* from a mode to a genesis-embedded anchor consumed by SNAP. It is the
JVM structural mirror closest to fukuii and shows how a multi-consensus client (PoW + PoS + BFT) keeps
**one SNAP engine with a swappable target-selection seam** rather than N modes.

Authority boundaries:
- **go-ethereum remains the authority for snap-protocol wire semantics** — the `GetAccountRange` /
  `GetStorageRanges` / `GetByteCodes` / `GetTrieNodes` message set, range-proof placement, and the
  originating design. Match geth on the wire; look to besu for how to *organize* the sync driver around
  it. besu's serving caps (2 MiB / 100k entries / 1024 code lookups / 4096 storage-range accounts / per-
  request ms budget) are besu's own choices and differ from geth's `softResponseLimit`/`maxTrieNodeTimeSpent`.
- **core-geth remains the authority for PoW-from-genesis sync** for ETC — besu's PoW path
  (`PivotSelectorFromPeers`) is a valid peer-driven-pivot reference, but ETC-specific bootstrap semantics
  (ETChash, ECIP-1017 emission continuity) are core-geth's domain.
- **erigon is the alternative authority for a staged, restartable, flat-state pipeline** (per
  `go-ethereum/sync.md`) — besu is *not* staged; it runs concurrent chain + world-state downloads with a
  two-phase heal.

## Gotchas / anti-patterns / things they later changed

- **The mode surface CONTRACTED, it did not expand.** A reader expecting a rich `SyncMode {FULL, FAST,
  SNAP, CHECKPOINT, X_*}` enum (as the taxonomy slot and the kickoff prompt anticipate) will find only
  `{FULL, SNAP}`. FAST is deleted; CHECKPOINT is a deprecated no-op flag + a genesis anchor. Any fukuii
  design that copies "besu has many sync modes" is copying a **past** besu — the current lesson is the
  opposite: fewer modes, more orthogonal seams.
- **Legacy `fastSync*` naming is a trap.** `DefaultSynchronizer.fastSyncDownloader`,
  `SnapDownloaderFactory.createSnapDownloaderV1`'s `fastSyncDownloader` local, and
  `SnapSyncDownloader.fastSyncActions` are all **SNAP**, not a separate fast sync. Don't infer a FAST
  code path from the names.
- **snap/2 is advertised-but-not-wired.** `--Xsnap2-enabled` (default false) flips a config bit, but
  `SnapDownloaderFactory.createSnapDownloader` has an **empty `if (snap2Enabled) { /* until then v2 uses
  v1 */ }`** branch and always builds the v1 downloader (`:63-80`) — although `createSnapDownloaderV1`
  *does* construct a `SnapV2WorldStateDownloader` when the flag is set (`:134-149`). Treat snap/2 as
  in-progress; do not benchmark against it as if complete.
- **SNAP only bootstraps a fresh datadir.** If the local chain is non-empty when SNAP is requested,
  `SnapDownloaderFactory` logs "cannot be enabled because the local blockchain is not empty" and returns
  empty, silently degrading to full sync (`:118-124`). A restart mid-snap relies on the persisted
  `ChainSyncState`/sync folder, and `deletePivotSyncState` notes snap **resume is not fully implemented**
  ("until fast sync resume functionality is in place", `:152-153`).
- **SNAP ⇒ Bonsai is a hard requirement, enforced late.** There is no `SyncMode`-level guard; the coupling
  surfaces as a snap-server class-cast to Bonsai storage and a CLI validation on the flat-heal flags
  (`BesuCommand.java:1931`). A Forest datadir + SNAP is a misconfiguration caught at wiring/serving time,
  not at mode parse.
- **Snap-server is OFF by default** (`--snapsync-server-enabled` default false) — a fresh besu node
  *consumes* snap but does not *serve* it unless explicitly enabled, unlike its willingness to snap-sync.
