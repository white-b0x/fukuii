# Topic — SNAP-sync porting reference (nethermind base + besu persistence, per-concern)

_Workstream-A deep-dive, 2026-07-14. READ-ONLY byte-level extraction from the vendored trees under
`.claude/repo-references/clients/{nethermind,go-ethereum,besu}` (four parallel agents: heal-path +
concurrency, wire sizes, crash-resume, entry/exit transitions). This is the **build spec** for fukuii
L7's SNAP port — the detailed companion to the scarcity verdict in
[`snap-heal-low-peers.md`](snap-heal-low-peers.md) and the pivot-policy design in
[`plan/L7.md`](../../../architecture/fukuii-rebuild/plan/L7.md) §6.8.1. Line numbers are the vendored
trees on the date read; re-verify before porting._

## Per-concern authority map (the headline)

The operator's direction — "use nethermind as the base framework, then see if fukuii practices are
relevant" — refines on inspection into a **per-concern** split (consistent with `reference-client-authority`:
authority is per-concern, not per-client). nethermind is the base for the *control machinery*; besu is
the authority for *persistence/resume*; the *completeness invariants* are nethermind's and are already
best-in-class.

| Concern | Authority | Why |
|---|---|---|
| Selector / mode state-machine, feed/dispatcher, pivot-policy (`StaticSnapPivot`) | **nethermind** | cleanest actor-mappable decomposition; the pin knob is the heal-hold precedent |
| Heal engine (dependency-counted trie-node DAG, priority frontier, boundary recovery) | **nethermind** | `TreeSync` reuse, structural completeness proof |
| Heal **completeness invariants** (bottom-up save, root-last+flush, corruption tripwire) | **nethermind** | crash-safe *and* simpler than geth's snapshot generator — **keep unchanged** |
| **Crash-resume / frontier persistence** | **besu** | continuously-committed transactional frontier journal; nethermind persists *nothing* here |
| snap/1 **wire bytes** (message layout, proof placement, serve caps) | **go-ethereum** | the byte authority (unchanged from L7 §3) |
| Serve DoS caps (per-message size/count/wall-clock) | **go-ethereum** + **besu** | geth per-message constants; besu's 4 s wall-clock is the cleaner all-range timer |

**fukuii's own AS-IS already beats nethermind on one axis** (crash-resume — see §5), so the port is
*bidirectional*: adopt nethermind's machinery, keep/upgrade fukuii's frontier persistence to besu's model,
and do **not** inherit nethermind's binary resume marker.

---

## 1. Phase model — one selector state, two internal sub-phases (corrects an assumption)

The vendored nethermind has **no `SnapRanges` sync-mode and no `ShouldBeInSnapRangesPhase`**. Snap range
download **and** trie-node heal both run under the single `SyncMode.StateNodes` flag; the ranges→heal
progression is sequential *inside* `StateSyncRunner.Run`, invisible to the mode selector
(`FastSync/StateSyncRunner.cs:49-56`):

```
Run: if FindBestFullState()!=0 return;  await StateSyncPrecursorWait();
     if SnapSync: await snapSyncRunner.Run()   // Phase A: SNAP ranges, fully drains
     await RunStateSyncRounds()                // Phase B: TreeSync heal
     if StaticSnapPivot: log "node is idle"
```

- **Phase A (SNAP ranges)** — account/storage/bytecode ranges overlap freely within one dispatcher,
  prioritized each 50 ms poll by `ProgressTracker.IsFinished` (`SnapSync/ProgressTracker.cs:145-203`).
  Done ⟺ `IsSnapGetRangesFinished` (all 5 queues empty + all 4 active counters 0, `:454-462`).
- **Phase B (heal)** — `TreeSync` downloads the trie nodes that repair the boundaries Phase A couldn't
  stitch, plus the `UpdatedStorages` heal set. Only heal can finalize the root.
- **Two other corrections:** long-range catch-up was **removed** (`MultiSyncModeSelector.cs:314-316`;
  `FastSyncCatchUpHeightDelta` is dead — don't port a catch-up edge); `StateMaxDistanceFromHead=128` is
  **not** in the selector's entry predicates (it governs *pivot advance* in `StateSyncPivot`, not
  mode-entry — entry uses `StateMinDistanceFromHead=32` + `StickyStateNodesDelta=32` ⇒ 64).

---

## 2. Healing path (build spec)

**Heal == the fast-sync trie-node downloader `TreeSync`, driven by a dependency-counting DAG. No separate
heal engine.**

- **Dependency model** (`FastSync/TreeSync.cs`, `DependentItem.cs:9-16`): a parent node carries a `Counter`
  = number of not-yet-saved children (branch=16 `:817`, extension=1 `:868`, leaf=2 code+storage `:906`).
  `AddNodeToPending` (`:476-575`) checks LRU (`_alreadySavedNode` `:485-499`) → DB (`_store.NodeExists`
  `:501-535`) → in-flight (`_dependencies` `:542-560`); an already-present child decrements the parent.
  `SaveNode` (`:617-700`) writes under lock then `PossiblySaveDependentNodes` (`:577-615`) decrements
  waiting parents and recursively saves any that hit 0 — the cascade that walks completed subtrees upward.
- **Frontier walk = priority-bucketed depth-first, left-biased** (`FastSync/PendingSyncItems.cs`): 7
  `ConcurrentStack`s (code + state/storage × prio 0/1/2, `:28`); `CalculatePriority` `:123-131` = depth
  bonus + left-bias + rightward shift as sync progresses; code drained first; re-bucket every 60 s.
- **Snap→heal stitching:** a failed storage-range boundary proof marks the account for heal —
  `SnapProvider.cs:208-214` → `ProgressTracker.TrackAccountToHeal` → `_pivot.UpdatedStorages.Add`
  (`ProgressTracker.cs:495-499`; `UpdatedStorages` is a `ConcurrentHashSet` on the shared pivot,
  `StateSyncPivot.cs:66`). `VerifyStorageUpdated` (`TreeSync.cs:702-744`) blocks the root save until every
  such account's storage root is present; `EnsureStorageEmpty` clears stale flat slots for emptied accounts.
- **Boundary / on-demand recovery** (`Trie/SnapRangeRecovery.cs`) — fired when a trie node is *missing at
  read time* (an `IPathRecovery`, distinct from normal heal): `ConcurrentAttempt=3` (`:39`) parallel
  **latency-ranked** (`SnapPeerStrategy`, `:32-35`) single-path `GetAccountRange`/`GetStorageRange`
  queries; `AssembleResponse` (`:168-243`) rebuilds the node from returned proofs.
- **Completeness proof (structural, un-fakeable):** `IsRootComplete` = root saved or
  `_store.NodeExists(null, TreePath.Empty, _rootNode)` (`:60-61`); each node verified `Keccak(response)==Hash`
  (`:230-239`); root saves last, so *root present ⇒ whole trie present*. `VerifyPostSyncCleanUp` (`:746-764`)
  errors `"POSSIBLE FAST SYNC CORRUPTION"` if any dependency/pending item survives finalize; optional full
  `VerifyTrieOnStateSyncFinished` re-walk. Node identity is key-scheme-pinned `(Address, Path, Hash)`.

---

## 3. Concurrency (build spec)

- **Dispatcher** (`ParallelSync/SimpleDispatcher.cs`): up to `MaxProcessingThreads` (or `ProcessorCount`)
  requests in flight per feed (`:39-42`); `Allocate` timeout 1000 ms (`SyncConfig.cs:81`); on null peer,
  hand the batch back and continue; feeds poll every 50 ms when idle.
- **Range partitioning** (`ProgressTracker.cs`): account keyspace split into
  `SnapSyncAccountRangePartitionCount=8` equal partitions (`:90-126`; `SyncConfig.cs:58`) → up to 8
  concurrent `GetAccountRange`; `STORAGE_BATCH_SIZE=1200`, `CODES_BATCH_SIZE=1000` (`:27-30`); storage-range
  splitting default-OFF (`EnableSnapSyncStorageRangeSplit=false`, `SyncConfig.cs:96` — splitting causes
  more heal). Account requests throttle to yield to storage/code backlog (`ShouldRequestAccountRequests`
  `:287-290`).
- **Peer allocation** (`AllocationContexts.Snap=16`): snap feed = throughput-ranked `BySpeedStrategy`
  (`SnapSyncAllocationStrategyFactory.cs:14-15`); recovery = latency-ranked; **per-peer concurrent slots =
  2** (`SyncPeerPool.cs:101`) — anti-starvation spreads load; sole-peer-protecting weak-peer backoff 1 s.
- **Sequencing:** ranges overlap internally; **heal does not overlap ranges** — Phase A fully drains
  before Phase B; only heal finalizes the root.

Constants quick-reference: account-partitions 8 · storage-batch 1200 · codes-batch 1000 · state-node
request 384 · dep counters 16/1/2 · heal-reset `_hintsToResetRoot≥32`+2min · snap invalid-window 5/50 ·
recovery attempts 3 · per-peer snap slots 2 · feed poll 50 ms · allocate timeout 1000 ms.

---

## 4. Wire sizes — client-request vs server-serve (settles "3 MB vs 512 KB")

| Message | Client request cap | Server serve cap |
|---|---|---|
| AccountRange (neth) | **adaptive 50 KB→3,000,000 B** (`NodeStatsLight.cs:88-93`), wire max 3 MiB (`SnapMessageLimits.cs:19`) | **2,000,000 B** (`SnapServer.cs:51,182`) |
| AccountRange (geth) | **524,288 B** (`sync.go:56` `maxRequestSize`) | **2,097,152 B** (`handler.go:32` `softResponseLimit`) |
| AccountRange (besu) | **524,288 B** (`AbstractSnapMessageData.java:32`) | **2,097,152 B** (`SnapServer.java:76`) |
| StorageRange serve | — | neth 2 MB · geth soft 2 MB / **hard 2.3 MB** (`handlers.go:181`, `stateLookupSlack=0.1`) · besu 2 MB, ≤4096 accts |
| ByteCodes | neth 1000/req · geth ≤84 (`sync.go:66`) · besu 84 | 2 MB, ≤1024 codes (geth `handlers.go:348-352`, besu `SnapServer.java:565`) |
| TrieNodes | neth heal 384 · geth ≤1024 (`sync.go:72`) · besu 384 | 2 MB + geth ≤1024 loads + **5 s** (`handlers.go:525,531`); neth ≤100,000 nodes |

**Reconciliation:** "512 KB" = the **client request** size (geth/besu fixed). "3 MB" = **nethermind's
client request** size (adaptive 50 KB→3 MB, latency-ramped). **The server serves ≤2 MB in all three** —
nobody serves 3 MB (nethermind `HardResponseByteLimit=2,000,000` decimal ≈1.907 MiB; geth/besu exactly 2
MiB). Port guidance: keep the **2 MB serve cap**; the client may mirror geth/besu's fixed 512 KB *or* port
nethermind's adaptive sizer (higher request ceiling, capped by the 2 MB a peer will serve anyway).

- **≥1-item progress guarantee:** geth explicit per-message (append-before-check); nethermind by
  construction (`byteLimit` floored to 1, `SnapServer.cs:182`); **besu weaker** (`ResponseSizePredicate`,
  no explicit append-first — **add a ≥1 guard when porting**).
- **Per-request wall-clock cap:** geth 5 s (TrieNodes only, `handler.go:54`); besu **4 s all ranges**
  (`SnapServer.java:741`, `ResponseSizePredicate`); nethermind **none** (CancellationToken + 100k-node
  ceiling only — a gap; **copy besu's 4 s timer**).

---

## 5. Crash-resume / persistence — besu is the authority; keep nethermind's invariants

| | On disk during snap | Lost on hard crash | Resume re-does | Completeness crash-safe? |
|---|---|---|---|---|
| **nethermind** | committed flat/trie nodes (Phase-A **WAL-off**, `PatriciaSnapStateTree.cs:28`) + codes; **one binary Phase-A done-flag** (`ACC_PROGRESS_KEY`, MaxValue\|0, `ProgressTracker.cs:466-492`); heal counters blob; heal nodes (WAL-on) | entire Phase-A frontier (partition cursors, storage/code queues, `UpdatedStorages`); heal dep-graph | **whole Phase-A range download**; heal frontier re-walked from DB (saved subtrees skipped) | **Yes** (root saved last, bottom-up) |
| **go-ethereum** | account/storage bytes; `SnapshotSyncStatus` JSON journal (Next/Last/subtasks); `LastPivotNumber` | the journal **unless gracefully saved** (defer-only, `sync.go:632-635`) | from boundary if clean shutdown; ~whole range phase after `kill -9` | Yes |
| **besu** | flat/trie state; **frontier journal per account-request dequeue** in CF `SNAPSYNC_MISSING_ACCOUNT_RANGE`; heal set in `SNAPSYNC_ACCOUNT_TO_FIX` (`SnapSyncStatePersistenceManager.java:93-123`) | almost nothing — journal continuously committed | from the last-dequeued boundary; heal-only restart if ranges done | Yes |

**nethermind persists *no* frontier** — its own comment: *"we can't actually resume snap sync on
restart"* (`ProgressTracker.cs:466-467`). **besu is the most robust** (transactional per-dequeue journal,
`kill -9` costs only the in-flight request) and is fukuii's JVM authority. **fukuii's AS-IS already beats
nethermind** (`SnapSyncProgressStorage` + preserved account-range cursors up to `MaxPreservedPivotDistance`).

**Port guidance:**
- **Adopt besu's persistence model** — persist the per-partition frontier
  (`NextAccountPath`/`AccountPathLimit`/`MoreAccountsToRight`, the fields nethermind keeps only in-memory in
  `AccountRangePartition`, `ProgressTracker.cs:613-619`) + the `UpdatedStorages` heal set, transactionally
  to a dedicated column family, updated on each partition dequeue; on startup re-enqueue exactly those
  ranges (`SnapWorldStateDownloader.java:178-212` is the template). fukuii's existing cursor persistence
  aligns — keep and upgrade it, do **not** regress to nethermind's binary marker.
- **Keep nethermind's two invariants unchanged** (crash-safe *and* simpler than geth's snapshot generator):
  bottom-up heal, **root saved last + flushed** → "root present ⇒ complete"; the `VerifyPostSyncCleanUp`
  corruption tripwire + optional full trie re-verification.
- **WAL gotcha:** nethermind's `WriteFlags.DisableWAL` on Phase-A range commits is safe *only because* it
  re-runs the whole phase. With a persisted frontier, a lost memtable would let a crash mark "done" nodes
  actually missing — so **keep WAL on for range commits** (or flush on each frontier checkpoint).

---

## 6. Entry / phase / exit transitions (state machine)

Selector tick = 1000 ms (`MultiSyncModeSelector.StartAsync`). States = the `[Flags] SyncMode` set.

| Edge | Predicate (file:line) | Peer-gated? |
|---|---|---|
| →`Disconnected` | `!SynchronizationEnabled`, or peerBlock null/0 & !beaconControl `:106,116` | **yes** (no peers) |
| →`FastSync` | `!Beacon & postPivotPeer(‖StaticSnapPivot) & Header<Target−32 & !StateDownloaded` `:290-337` | **yes** (`Peer.Block>Pivot`) |
| →`StateNodes` (SNAP ranges **then** heal) | `FastSyncEnabled & !Beacon & Header≥Pivot & (AnyPostPivotPeer ‖ StaticSnapPivot·Peer≥Pivot) & (!fast ‖ sticky<64) & !StateDownloaded` `:494-526` | **yes** |
| ranges→heal (internal) | `IsSnapGetRangesFinished` (`ProgressTracker.cs:454-462`) → `RunStateSyncRounds` | no |
| →`Full` (exit) | `!Beacon & desiredPeer & postPivotPeer & Header≥Pivot & !fast & **!state** & !waitHeaders`; needs `State≥Pivot` `:339-375,690` | **yes** |
| finalize | `CanFinalize = IsRootComplete & pivot.CanFinalize` → `VerifyPostSyncCleanUp`+`FinalizeSync` `:100-115` | no |

- `AnyPostPivotPeerKnown ⇒ Peer.Block > PivotNumber` (`:691`) — entry to FastSync/StateNodes and exit to
  Full all require a peer **strictly above** pivot.
- **`StaticSnapPivot` is the only scarcity relaxation** (`:306,499`): downgrades the requirement to a peer
  merely *at* pivot height (`≥`) and skips the head-distance wait (`StateSyncRunner.cs:128`). This is
  fukuii's checkpoint/fixed-pivot lever (cf. `fukuii-checkpoint-service`). SNAP's feed also protects the
  sole peer (`UpdatePivot()` instead of punishing, `SnapSyncFeed.cs:184-207`).
- **ETC/PoW:** `No.BeaconSync` ⇒ all `notInBeaconModes` guards pass, target = peer-max (peer-driven).
  **ETH/PoS:** `Merge.Plugin` BeaconSync supplies the target from FCU/NewPayload; `BeaconHeaders` mode
  runs the reverse header fill.

---

## 7. fukuii L7 port synthesis (maps to plan §6.8.1)

The per-network `pivotPolicy` seam (`RollingPivot` / `StaticPivot` / `AdaptiveHold`, plan §6.8.1) is built
on this per-concern base:

1. **nethermind base** for the selector state-machine (§6), feed/dispatcher + range partitioning (§3),
   the dependency-counted heal DAG + completeness proof (§2), and the `StaticSnapPivot` pin mechanics
   (→ `StaticPivot` for checkpoint bootstrap; the *live-head* freeze-until-heal-drains `AdaptiveHold` is
   fukuii's superset — nethermind's pin never releases and expects a CL/`ExitOnSynced`).
2. **besu base** for crash-resume/frontier persistence (§5) — fukuii's AS-IS cursor persistence already
   aligns; upgrade it to besu's transactional per-dequeue journal, do not adopt nethermind's binary marker.
3. **Keep nethermind's completeness invariants** (§2/§5) and **fukuii's scarcity backstops** (heal-hold,
   dormant-retry, fail-loud force-complete — [`snap-heal-low-peers.md`](snap-heal-low-peers.md)), hardened.
4. **Fill the gaps none of them close:** a besu-style 4 s serve wall-clock (nethermind lacks it, §4);
   an explicit ≥1-item serve guard if following besu's predicate (§4); a heal keep-up/convergence detector
   (no reference has one, §7 of the scarcity doc); snap-peer-scarcity visibility distinct from eth-peer
   count (all three conflate it, §6).
5. **Wire sizes:** 2 MB serve cap (all three); client request either fixed 512 KB or nethermind's adaptive
   50 KB→3 MB sizer; geth per-message DoS constants (L7 §7 SNAP-serving row) remain the byte authority.
