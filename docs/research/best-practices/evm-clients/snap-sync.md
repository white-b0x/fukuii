# Best Practices: SNAP Sync Patterns

Synthesized from go-ethereum, Besu, core-geth, erigon, reth, nethermind, and Fukuii.
These are the patterns that work. All are validated by at least 2 production clients.

---

## 1. Concurrent Coordinator Launch (All Phases Start Together)

**Do this:**
```
startAccountRangeSync:
  1. Create all 4 coordinators simultaneously
  2. Account coordinator starts with budget = configured value
  3. Storage + bytecode coordinators start with maxInFlightPerPeer = 0
  4. Healing coordinator starts with maxInFlightPerPeer = 0
  5. Raise storage/bytecode budgets when AccountRangeSync completes
```

**Why:** Accounts, storage tasks, and bytecode hashes all arrive inline during Phase 1.
Coordinators must exist to receive them. If they're created after Phase 1 completes,
the task queue blocks and the tasks are dropped or buffered in the controller's mailbox.

**All clients:** go-ethereum (goroutines launched upfront), Besu (8 pipelines all active),
Nethermind (dispatch-priority queue, all phases active), Fukuii (actors created upfront).

---

## 2. Pivot Block Selection

**Algorithm (go-ethereum canonical):**
```
pivot = bestBlock - pivotBlockOffset  (default 64)
```

**Rolling (proactive):**
- Track pivot age = `currentHead - pivotBlock`
- When `pivotAge > SnapServeWindowBlocks` (Fukuii: 100; geth: 128): issue pivot probe
- Roll to `bestBlock - pivotBlockOffset` on probe response

**Why rolling matters:** Snap peers prune snapshots outside their serve window (~128-256 blocks
from their head). A stale pivot causes all peers to return empty responses → stateless loop.

**Pivot distance preservation:** Content-addressed data (bytecodes, trie nodes) is valid across
pivot changes. Account ranges with drift ≤ `MaxPreservedPivotDistance` (256) can be resumed
without re-downloading.

---

## 3. nBytes = 0 → Return Minimum 1 Item

**Rule:** When a SNAP request arrives with `responseBytes = 0`, always return at least 1 item.
This applies to `GetAccountRange`, `GetStorageRanges`, `GetByteCodes`, `GetTrieNodes`.

**Why:** `nBytes = 0` is interpreted as "no byte limit" by the spec. Returning empty is a
protocol violation — tested explicitly by Hive (TestSnapGetAccountRange case 4).

**All clients:** go-ethereum, Besu, Nethermind, Fukuii all implement this.

---

## 4. State Root Staleness: 128-Block Window

**Rule:**
- State root exactly 127 blocks old: **serve** (still within snap serve window)
- State root 128+ blocks old: **return empty** (no error, no disconnect)
- Non-existent state root: **return empty**
- Storage root used as account root: **return empty** (no account leaf at that hash)

**Why 128:** The snap serve window is 128 blocks. A root older than the window has been
pruned from peers' snapshots. `127 blocks old = still in window`.

**Source:** go-ethereum `eth/protocols/snap/handler.go`, tested by Hive case 11-13.

---

## 5. GetTrieNodes: Empty PathSet → Disconnect

**Rule:** If a `GetTrieNodes` request contains a path set with zero paths (`Seq.empty`),
the server should disconnect/reject the connection.

**Why:** An empty pathset is a protocol-level malformed request. Serving it would be silent
acceptance of bad protocol behavior.

**Note on implementation:** The server-side behavior is to return empty (TrieNodes with 0 nodes).
The Hive test checks `expReject: true` from the perspective of the test-client that sent the
bad request — it expects the server to close the connection. In practice the test peer expects
a disconnect, not a response.

**All clients:** go-ethereum `handler.go:522-525` returns empty and the connection resets.

---

## 6. Progress Persistence Granularity

| Client | Granularity | Notes |
|--------|-------------|-------|
| Besu | Per account dequeue | `SnapSyncStatePersistenceManager` writes on every dequeue |
| go-ethereum | Phase checkpoint | Writes on phase transitions |
| Nethermind | Binary phase flag | Cannot resume mid-phase |
| Fukuii | Disk progress on range update | Coarser than Besu but better than binary |

**Best practice:** Besu's per-dequeue granularity is optimal — crash and restart loses at most
1 account batch of work. Minimum acceptable: checkpoint at each range completion.

**For Fukuii:** The current per-range-completion checkpoint is adequate for production.
Improving to per-N-accounts would require `AppStateStorage` writes inside the coordinator,
adding latency but reducing restart overhead.

---

## 7. Stateless Peer Detection (Binary)

**Algorithm:**
```
On empty AccountRange/StorageRanges response:
  statelessPeers.add(peerId)

On PivotRefreshed:
  statelessPeers.clear()
  Apply post-pivot cooldown (30-60s before dispatching to re-probed peers)

When all connected snap peers are stateless:
  Request pivot refresh from controller (ONCE — set pivotRefreshRequested = true)
```

**Why binary (not counter-based):** The old approach used a counter (10 empties → 2 min pause).
This was slow to detect the stateless condition and caused unnecessary stalls. Binary detection
is immediate — one empty response marks the peer, all-empty triggers refresh.

**Post-pivot cooldown is critical:** Without a cooldown, new peers immediately return empty
for the fresh root (they haven't synced to it yet), triggering another pivot refresh → loop.

---

## 8. Adaptive Byte Budget

**Pattern (used by go-ethereum, Besu, Nethermind, Fukuii):**
```
initialResponseBytes = 1MB
maxResponseBytes = 2MB (hard cap per SNAP spec)
minResponseBytes = 128KB (floor to prevent micro-requests)

On success (received ≥ 90% of requested budget):
  requestedBytes = min(requestedBytes * 1.25, maxResponseBytes)

On failure (timeout, empty, verify fail):
  requestedBytes = max(requestedBytes * 0.5, minResponseBytes)
```

**Per-peer tracking:** Each peer gets its own `responseBytesTarget`. A slow peer shouldn't
reduce budgets for fast peers.

---

## 9. Proof Generation and Verification

**Server-side proof requirement:**
- Non-empty range response: MUST include Merkle proof for left bound and (if truncated) right bound
- Empty range response: include left-bound proof (proves absence in range)
- Proof nodes are deduplicated before sending

**Client-side verification:**
- Every non-empty `AccountRange` and `StorageRange` response: verify with `VerifyRangeProof`
- Verification failure → discard response, cooldown peer, re-queue tasks
- DO NOT write to storage before verification

**Slim account encoding (geth convention):** `storageRoot` and `codeHash` encoded as empty
bytes when equal to canonical defaults. Saves ~64 bytes per EOA. Round-trip safe.

---

## 10. Force-Completion Thresholds

**Pattern (go-ethereum, Fukuii):**
```
maxConsecutiveTaskFailures = 100
on force-complete:
  report ForceComplete* to controller
  controller: flag storagePhaseForceCompleted = true
  missing data recovered by BytecodeRecoveryActor during chain import
```

**Why force-complete instead of infinite retry:** At scale, some peers may consistently fail
to serve specific accounts/slots (due to their own state gaps or misconfigurations). Hanging
indefinitely is worse than accepting partial state and recovering during execution.

**Besu equivalent:** `markAsStalled()` — currently a no-op TODO in Besu. Fukuii's
force-complete threshold is more robust.

---

## 11. Healing: Per-Node Retry Limit

**Best practice:**
```
maxRetriesPerTask = 20  (Fukuii)
// At ~6s per timeout: 20 retries = ~2 minutes per node
// After threshold: abandon node, log warning
```

Missing trie nodes that can't be healed:
- Are recovered during block execution (on-demand fetch from peers)
- Are extremely rare in practice (nodes are content-addressed, available from any peer)

**Queue implementation:** Use a deque (double-ended queue) for O(1) re-enqueue at head.
**DO NOT** use an immutable `Seq` — O(n) prepend is quadratic at healing scale (issue #1167).

---

## Cross-References
- go-ethereum canonical: `../ref-clients/go-ethereum/snap-protocol/overview-state-machine.md`
- Besu pipeline: `../ref-clients/besu/snap-protocol/overview-state-machine.md`
- Nethermind cross-validation: `../ref-clients/nethermind/cross-validation.md`
- Anti-patterns: [anti-patterns.md](anti-patterns.md)
- Fukuii implementation: `../fukuii-state/snap-sync/current-state-machine.md`

---

## snap/2 Protocol Additions (go-ethereum 17aab1ac9, June 2026)

**Source:** go-ethereum commit 17aab1ac9 "core, eth/protocols/snap, eth/downloader: snap/2 sync logic (#34626)"
**EIP references:** EIP-8189 (snap/2 protocol), EIP-7928 (Block Access Lists)
**Status:** Opt-in behind `--snap.v2` flag. NOT safe to advertise unconditionally on public networks yet.
**Besu status:** Besu upstream branch already has `DownloadAndPersistBlockAccessListsStep.java` — BAL download support is parallel-developed.

---

### 12. snap/2 Is a Separate Protocol Version, Not an Extension of snap/1

**What changed:**
```
snap/1: message codes 0x00-0x07 (8 messages, protocolLengths[SNAP1]=8)
snap/2: message codes 0x00-0x09 (10 messages, protocolLengths[SNAP2]=10)
  New: GetAccessListsMsg = 0x08, AccessListsMsg = 0x09
```

**Protocol negotiation:** snap/2 is only advertised when `--snap.v2` is enabled (`MakeProtocols` in `eth/protocols/snap/handler.go:85-88`). Without the flag, only `ProtocolVersions = []uint{SNAP1}` is advertised. A snap/2 syncer will only pull from peers that have negotiated snap/2 (`syncer.Version() == SNAP2` → downloader skips snap/1-only peers for BAL requests).

**For fukuii:** Fukuii's `Capability.scala` currently only defines `SNAP1`. Adding `SNAP2` requires:
1. New `case object SNAP2 extends Capability(ProtocolFamily.SNAP, 2)` in `Capability.scala`
2. New message codes `GetAccessListsCode` (0x38) and `AccessListsCode` (0x39) in `SNAP.scala`
3. New message types in the SNAP decoder
4. GAP — not implemented.

---

### 13. BAL-Based Healing: No Trie Node Heal Phase

**The fundamental change in snap/2:** The heal phase (patterns 11 above) is eliminated for snap/2 syncers. Instead of fetching `GetTrieNodes` to patch up trie inconsistencies, snap/2 downloads Block Access Lists (BALs) to roll flat state forward.

**snap/1 heal flow:**
```
downloadState (accounts + storage + bytecodes)
  → healState (GetTrieNodes loop until trie is consistent)
  → done
```

**snap/2 flow:**
```
downloadState (accounts + storage + bytecodes)
  → GenerateTrie (rebuild trie from flat state)
  → done
```

**BAL catch-up instead of trie healing (`syncerV2.catchUp` in `syncv2.go:663`):**
When the pivot moves between sync runs, snap/2 fetches BALs for the gap blocks (`previousPivot.Number+1` to `pivot.Number`), verifies each against `header.BlockAccessListHash` (EIP-7928 field), and applies them to flat state. The trie is rebuilt once from flat state at the end — not patched incrementally.

**Why this works:** BALs record the exact state diff per block. Applying them in order to a flat-state snapshot yields the correct post-pivot flat state. `triedb.GenerateTrie` then reconstructs the trie from scratch (parallel across 16 partitions of the account hash space), which avoids the serve-window deadlock that afflicts snap/1's trie-node heal at ~99%.

**For fukuii:** `TrieNodeHealingCoordinator` and `TrieNodeHealingWorker` are snap/1-only actors. snap/2 would replace them with a `BALCatchUpActor` + `GenerateTrie` call. Spec 004 (decouple heal serve root) applies to snap/1 only. GAP — snap/2 not implemented.

---

### 14. BAL Verification: Hash-Committed in Block Header

**Rule (EIP-7928):** The BAL hash is committed in the block header as `BlockAccessListHash *common.Hash` (optional RLP field). A received BAL must be verified before application:
```go
// bal_apply.go:verifyAccessList
func verifyAccessList(b *bal.BlockAccessList, header *types.Header) error {
    if header.BlockAccessListHash == nil {
        return fmt.Errorf("header %d has no access list hash", header.Number)
    }
    have := b.Hash()
    if have != *header.BlockAccessListHash {
        return fmt.Errorf("access list hash mismatch for block %d: have %v, want %v", ...)
    }
    return nil
}
```

**Invalid BAL response handling:** A peer that returns a BAL failing `verifyAccessList` is marked stateless for BAL requests (`s.statelessPeers[res.req.peer]`). The hash is re-added to the pending set for retry from another peer.

**EIP-8189 response limit:** BALs average ~72 KiB compressed. The 2 MiB soft limit implies ≤28 blocks per request (`maxAccessListRequestCount = 28` in `syncv2.go:55`). Size the batch to stay within this regardless of gas limit assumptions.

**For fukuii:** ETC does not have EIP-7928 (BAL hash in header) — snap/2 is therefore not applicable to ETC/Mordor. The ETH/Sepolia chain would require EIP-7928 activation before snap/2 can be used. GAP for ETH side; not applicable to ETC.

---

### 15. Pivot Reorg Detection: isPivotReorged Guard

**New in snap/2:** Before resuming from persisted progress after a pivot change, snap/2 checks whether the previous pivot was reorged out. If yes, all persisted flat state is wiped and sync restarts from scratch (`resetSyncState`). If no, BAL catch-up rolls forward.

**Algorithm (`syncv2.go:637-658`):**
```
isPivotReorged(db, prev, curr):
  if curr.Number <= prev.Number → reorged (new pivot doesn't advance)
  canonical = ReadCanonicalHash(db, prev.Number)
  if canonical == zero hash → reorged (chain rewound below prev)
  return canonical != prev.Hash()
```

**Why it matters:** A chain rewind (e.g., deep uncle acceptance) can erase canonical entries above the rewind point. Without this check, catchUp would try to fetch BALs for blocks that no longer exist in the canonical chain, causing `missing canonical hash` errors.

**snap/1 does not have this guard** — it uses `MaxPreservedPivotDistance` (pattern 2 above) to decide whether to resume or restart. snap/2 uses the explicit reorg check instead.

**For fukuii:** `SNAPSyncController` should implement equivalent reorg detection before attempting any BAL-based catch-up. The snap/1 pivot preservation logic (`MaxPreservedPivotDistance = 256`) remains correct for the current snap/1 implementation. GAP if snap/2 is ever added.

---

### 16. Pivot Progress Persistence: Versioned JSON with Pivot Header

**snap/2 persistence format (`syncProgressV2` in `syncv2.go:264-279`):**
```go
type syncProgressV2 struct {
    Pivot    *types.Header    // Full header (not just hash/number)
    Tasks    []*accountTaskV2 // Suspended account tasks
    Complete bool             // True once sync completed for this pivot
    // ... byte counters
}
```

**Version byte framing:** The blob is persisted as `[syncProgressVersion byte | JSON payload]`. `syncProgressVersion = 2`. On load, a mismatching version byte discards all progress and starts fresh (`syncv2.go:934-937`). This cleanly handles the snap/1 → snap/2 migration case.

**Storing the full pivot header (not just hash):** snap/2 stores the entire `types.Header` as `Pivot`, not just the root hash or block number. This enables `isPivotReorged` (pattern 15) to compare canonical hashes at the exact pivot height without an extra DB lookup for the header.

**Mid-catchUp persistence:** During BAL catch-up, progress is written after each block (`saveSyncStatusWithDB(batch)`) atomically with the state transition. A crash mid-catchUp resumes from the next unapplied block, not from the start.

**For fukuii:** Fukuii's snap/1 persists the pivot root hash. If snap/2 is added, the persisted struct must store the full header and include a version discriminator so snap/1 and snap/2 progress blobs don't cross-load. Pattern 6 (progress granularity) also applies: the mid-catchUp atomic write in go-ethereum is the correct model.

---

### 17. stateCompleted Set: Cross-Cycle Storage Deduplication

**New in snap/2 (absent in snap/1):** `accountTaskV2` carries a `stateCompleted map[common.Hash]struct{}` (in-memory) and its serialized form `StorageCompleted []common.Hash` (persisted). When a storage subtask finishes, the account hash is added to `stateCompleted`. On the next sync cycle — if the same account reappears in the account range response — its storage is skipped without re-downloading.

**Why:** During pivot moves, the same accounts appear again in the re-downloaded account range. Without `stateCompleted`, all their storage would be re-fetched unnecessarily. The set survives a crash/restart via `StorageCompleted` serialization and is cleared for an account only after its range marker (`task.Next`) advances past it (`forwardAccountTask` in `syncv2.go:2063`).

**`activeSubTasks` filter (`syncv2.go:237-251`):** Returns only the subtasks covered by the current account range response. If the response is shorter than the task's full range (e.g., on resume after interrupt), subtasks beyond the response boundary are deferred to the next wave.

**For fukuii:** snap/1's `StorageRangeCoordinator` does not have an equivalent cross-cycle dedup set. For snap/1 this is acceptable because pivot changes trigger a full restart. If snap/2 is added, this pattern must be implemented. Not a gap for the current snap/1 implementation.

---

### 18. GetAccessLists Server-Side: Return Empty String for Missing BALs

**Serving rule (`handlers.go:ServiceGetAccessListsQuery`, line 569-598`):**
```go
for _, hash := range req.Hashes {
    if bal := chain.GetAccessListRLP(hash); len(bal) > 0 {
        response.AppendRaw(bal)          // real BAL
    } else {
        response.AppendRaw(rlp.EmptyString) // unavailable → empty string marker
    }
    if bytes > req.Bytes { break }
}
```

**Each response entry corresponds to the requested hash at the same index.** A missing/unavailable BAL is signaled by `rlp.EmptyString` (not by omitting the entry). The client side checks `bytes.Equal(raw, rlp.EmptyString)` and re-adds that hash to the pending set (`processAccessListResponse` in `syncv2.go:896`).

**Cap: `maxAccessListLookups = 1024` per request** (`handler.go:49`). Ignore hashes beyond this cap.

**For fukuii:** If serving snap/2 peers ever becomes a requirement, `SnapServer.scala` would need a `handleGetAccessLists` branch that returns index-aligned RLP-empty-string entries for blocks without BALs stored locally. Not currently required as ETC does not have EIP-7928. GAP for ETH/snap/2 serving.

---

### 19. BAL Apply: EIP-161 Empty Account Exclusion

**Rule in `applyAccessList` (`bal_apply.go:165-186`):** When applying a BAL diff, an account that becomes empty (balance=0, nonce=0, codeHash=emptyCodeHash) is NOT written to flat state:
- `isEmpty && isNew` → skip (created-and-destroyed in same block, or net-zero change)
- `isEmpty && !isNew` → `DeleteAccountSnapshot` (existing account drained)
- otherwise → `WriteAccountSnapshot` with stale `storageRoot` (intentionally stale — recomputed by `GenerateTrie`)

**Storage root is intentionally stale in flat state during sync.** `applyAccessList` does not recompute the storage root — it only writes slot values. The correct root is computed by `triedb.GenerateTrie` at the end of sync. This is safe because `GenerateTrie` walks flat state and rebuilds the trie from slot values, not from cached roots.

**Slot encoding:** Storage slot values are stored as `rlp.EncodeToBytes(value.Bytes())` — the minimal big-endian RLP encoding with leading zeros trimmed — matching `core/state`'s snapshot writes. Using any other encoding breaks trie consistency.

**For fukuii:** The EIP-161 exclusion rule (not creating empty accounts in flat state) must be honored in any BAL apply implementation. The stale-storageRoot pattern is intentional — do not attempt to keep roots current during BAL application. GAP if snap/2 is ever added.

---

### 20. Unified Syncer Interface: snap/1 and snap/2 Share One Downloader Slot

**Design (`syncer.go:50-63`):** go-ethereum defines a `Syncer` interface with methods including `OnAccessLists`. The snap/1 adapter (`syncerV1Adapter`) provides a no-op `OnAccessLists`. The snap/2 adapter (`syncerV2Adapter`) provides a no-op `OnTrieNodes`. This means:
- snap/2 silently ignores any `TrieNodes` responses (stale snap/1 responses from mixed peers)
- snap/1 silently ignores any `AccessLists` responses
- The `eth/downloader` holds a single `snap.Syncer` slot — either snap/1 or snap/2, selected at startup

**Version reporting:** `syncer.Version()` returns `SNAP1` or `SNAP2`. The downloader passes this to `SnapSyncComplete(hash, isSnapV2 bool)` for post-sync bookkeeping.

**Progress reporting asymmetry:** snap/2's `Progress()` does NOT report `TrienodeHeal*` fields (they are zero). snap/1's `Progress()` reports them. Callers of `ethereum.SyncProgress` must handle both.

**For fukuii:** Fukuii's `SNAPSyncController` currently has a single snap/1 code path. If snap/2 is added, the controller should select between snap/1 and snap/2 at startup (config flag) and route `OnAccessLists` messages to the snap/2 path. The `Syncer` interface pattern is a clean model for this. GAP — not implemented.

---

### 21. GenerateTrie: 16-Partition Parallel Rebuild with Per-Partition Crash Resume

**Source:** `triedb/generate.go:GenerateTrie` (added for snap/2 completion path)

**What it does:** After snap/2 finishes downloading flat state, it calls `triedb.GenerateTrie(db, scheme, root, cancel)` to rebuild the full account + storage MPT from scratch. The account hash space is split into 16 partitions (first nibble of account hash = 0x0–0xF). Each partition runs in its own goroutine via `errgroup`, building the slice of the account trie and all per-account storage tries within its range. Once all 16 partitions complete, a top-level `assembleRoot` constructs the branch node and verifies the hash matches the expected pivot root.

**Crash resume:** Before starting each partition, `GenerateTrie` checks for a `WriteGenerateTriePartitionDone` marker in the DB. If found, the partition is skipped and its subtree blob is recovered directly. Only in-flight partition(s) at the time of crash are re-processed. This means a crash during the trie-generation phase loses at most 1/16 of the work.

**Key constants:**
```go
numPartitions = 16      // aligned with first-nibble MPT branching
partitionRangeSize = uint64(1) << 60  // 2^60 accounts per partition
partitionFinished  = ^uint64(0)       // sentinel in progress tracker
```

**Root verification failure:** If the assembled root doesn't match the expected pivot root, `GenerateTrie` returns an error. The caller (`syncerV2.Sync`) propagates this to the downloader — there is no auto-retry. Manual intervention (wipe and re-sync) is required.

**Not applicable to snap/1:** snap/1 uses incremental `GetTrieNodes` healing. `GenerateTrie` is exclusively the snap/2 completion step. Do not call `GenerateTrie` during or after snap/1 healing.

**For fukuii:** `SnapHashTrie.scala` and `SnapPathTrie.scala` implement the current snap/1 trie-building path. snap/2 would require a `GenerateTrieActor` that walks flat state in 16 parallel partitions using `StackTrie` and assembles the root. The `ShardEnumerator.scala` (16 shards, first-nibble partitioning) already exists and aligns with this pattern — it could serve as the enumeration backbone. GAP — not implemented.

---

### 22. snap/1 API Privatization: External Callers Must Use the Syncer Interface

**Source:** `eth/protocols/snap/sync.go` and `syncer.go` (changed in same commit 17aab1ac9)

**What changed:** In the snap/2 commit, the snap/1 types were privatized:
```
SyncProgress → syncProgress      (unexported)
SyncPending  → syncPending       (unexported)
Syncer       → syncer            (unexported)
NewSyncer()  → newSyncer()       (unexported)
SyncPeer     → SyncPeer          (still exported — needed by handler)
```

The only stable public API surface for the snap syncer is now the `Syncer` interface in `syncer.go` and its two constructors:
```go
func NewV1Syncer(db ethdb.Database, scheme string) Syncer
func NewV2Syncer(db ethdb.Database, scheme string) Syncer
```

**Why this matters for interop:** Any code (e.g., external testing scaffolds, non-go-ethereum callers) that previously constructed a `*snap.Syncer` directly via `snap.NewSyncer()` will fail to compile against go-ethereum post-17aab1ac9. The correct API is `snap.NewV1Syncer()` returning `snap.Syncer` (interface).

**For fukuii:** Fukuii is Scala and never calls go-ethereum APIs directly, so this is not a compile-time breaking change for fukuii itself. However, if fukuii's Hive test harness or any Go-based integration test uses go-ethereum as a library, it must be updated to use `NewV1Syncer`. No action required for the Scala codebase.

---

### 23. Peer Version Gating in Downloader: snap/2 Syncer Silently Drops snap/1 Peers

**Source:** `eth/downloader/downloader.go:RegisterSnapPeer` (line 1115-1119)

**Rule:**
```go
func (d *Downloader) RegisterSnapPeer(p *snap.Peer) error {
    if p.Version() < d.snapSyncer.Version() {
        return nil  // silently skip — peer cannot serve BAL requests
    }
    return d.snapSyncer.Register(p)
}
```

When the downloader is running in snap/2 mode (`snapV2=true`), `d.snapSyncer.Version()` returns `SNAP2=2`. Any peer that negotiated only snap/1 (`p.Version()=1`) is silently dropped at registration — it never enters the `peers` map, never appears in any idle pool, and never receives requests. The same gate applies at `UnregisterSnapPeer`.

**Implication for network bootstrapping:** In a network where most peers are snap/1-only (the current state of ETC mainnet), a snap/2 syncer would see zero eligible peers until enough snap/2 peers are deployed. This is why `--snap.v2` is not safe to advertise unconditionally on public networks yet. The flag must only be enabled on networks where a substantial fraction of snap-serving peers run snap/2.

**`errAccessListPeersExhausted`:** If all registered snap/2 peers become stateless for BAL requests, `fetchAccessLists` returns `errAccessListPeersExhausted`. There is no fallback to snap/1 peers — the catch-up stalls until a new snap/2 peer connects.

**For fukuii:** Fukuii's `NetworkProtocolConfig` advertises `SNAP1` only. Adding `SNAP2` must be guarded by a config flag (analogous to `--snap.v2`) and the peer registration logic in `SNAPSyncController` must gate on the negotiated snap version before enqueuing a peer into its idle pool. GAP — not applicable until EIP-7928 is active on ETH; not applicable to ETC.

---

### 24. resetSyncState: Full Flat-State Wipe, Not Just Progress Metadata

**Source:** `eth/protocols/snap/syncv2.go:resetSyncState` (line 1008-1050)

**What it wipes:**
```go
func (s *syncerV2) resetSyncState() {
    batch := s.db.NewBatch()
    rawdb.DeleteSnapshotSyncStatus(batch)          // progress JSON blob
    deleteRange(batch, rawdb.SnapshotAccountPrefix) // ALL flat account snapshots
    deleteRange(batch, rawdb.SnapshotStoragePrefix) // ALL flat storage snapshots
    batch.Write()
    // ... reset all in-memory counters
}
```

**When it's called:** `resetSyncState` is called when (a) persisted progress cannot be decoded, or (b) `isPivotReorged` returns true (pattern 15). A reorg wipe is unconditional and non-recoverable — the entire flat-state snapshot is deleted, not just the metadata.

**`deleteRange` is RocksDB-aware:** The inner loop calls `batch.DeleteRange(start, limit)`. If the range contains too many keys (`ethdb.ErrTooManyKeys`), it flushes the partial batch and retries until the range is fully deleted. On LevelDB this degrades to key-by-key iteration; on RocksDB the native range-delete is used.

**Contrast with snap/1 restart:** snap/1's `loadSyncStatus` can fall back to a fresh task list (same `resetSyncState` semantics) but does NOT wipe flat-state snapshots, because snap/1's healing phase can re-populate them. snap/2 must wipe flat state on reorg because BAL application is order-dependent — applying diffs on top of a partially-reorged base produces incorrect state.

**For fukuii:** Fukuii's `SnapSyncProgressStorage` currently only deletes the progress marker (`deleteProgress()`), not the flat-state snapshots themselves. If snap/2 is ever added, a pivot-reorg restart must also wipe `FlatAccountStorage` and `FlatSlotStorage` to avoid corrupting the BAL catch-up. For snap/1, the current behavior (marker-only wipe) is correct. GAP only if snap/2 is added.
