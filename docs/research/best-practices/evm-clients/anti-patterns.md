# Anti-Patterns: Proven Broken Approaches

Compiled from all 7 reference clients, Hive tests, and Fukuii production attempts.
Each entry has a real failure case. Do not repeat these.

---

## SNAP Protocol Anti-Patterns

### AP-1: Not Cancelling In-Flight Requests on Peer Disconnect
**Failure:** Consecutive-timeout counter spirals → sync engine shuts down.  
**Root cause (BUG-W8):** Peer disconnects but its in-flight requests are left as pending. Each times out, incrementing `consecutiveTimeouts`. At threshold, all peers appear stalled.  
**Fix:** On `PeerDisconnected`, immediately cancel all timers for that peer and re-enqueue tasks.  
**Reference:** `../fukuii-state/snap-sync/known-bugs.md#BUG-W8`

---

### AP-2: Returning 0 Accounts When nBytes = 0
**Failure:** Hive `TestSnapGetAccountRange` case 4 fails. Peers interpret empty response as "root not available."  
**Rule:** `nBytes = 0` means no byte limit — always return at least 1 account (or item).  
**Reference:** `../ref-clients/hive/snap-tests.md`

---

### AP-3: Freezing Pivot Root on Entering Healing
**Failure (BUG-W6):** `snapSyncSnapshotRoot` set once at healing entry, never updated on pivot roll. Healing requests use stale root → all peers return empty → healing stagnates indefinitely.  
**Fix:** Healing coordinator receives `HealingPivotRefreshed(newRoot)` and updates its root.  
**Reference:** `../fukuii-state/snap-sync/known-bugs.md#BUG-W6`

---

### AP-4: Using deferred-merkleization = true for ETC Mainnet
**Failure (ETC Attempt 25):** GC overhead limit exceeded. Process froze at block 24,452,253 after 3 hours. DeferredWriteMptStorage accumulates node batches in memory faster than GC can reclaim them at ETC mainnet state size.  
**Fix:** `deferred-merkleization = false` always for ETC mainnet.  
**Reference:** `../fukuii-state/snap-sync/known-bugs.md#ISSUE-deferred-merkleization`

---

### AP-5: Clearing knownAvailablePeers on Pivot Refresh for Content-Addressed Phases
**Failure (BUG-S1):** `ByteCodeCoordinator` cleared `knownAvailablePeers` on `ByteCodePivotRefreshed`. Bytecodes are hash-keyed — they don't change when the pivot rolls. Clearing peers caused a dispatch stall until the next `PeerAvailable` message.  
**Fix:** Only clear stateless-peer caches (root-specific) on pivot refresh, not `knownAvailablePeers` (session-persistent).  
**Reference:** `../fukuii-state/snap-sync/known-bugs.md#BUG-S1`

---

### AP-6: Immutable Seq for Healing Task Queue
**Failure (issue #1167):** O(n) prepend on `Seq` caused quadratic performance at healing scale. Hundreds of thousands of pending tasks × frequent re-enqueue = multi-second stalls per operation.  
**Fix:** `mutable.ArrayDeque[HealingEntry]` — O(1) amortized prepend and dequeue.  
**Reference:** `../fukuii-state/snap-sync/phase4-healing.md`

---

### AP-7: MaxThrottle Too High in Healing
**Failure (issue #1159):** `MaxThrottle = batchSize` locked the healing batch at 2 paths/GetTrieNodes request. At ~6s RTT, this throttled healing to ~6 nodes/sec on Mordor — would take months for ETC mainnet.  
**Fix:** `MaxThrottle = 4` gives floor of `batchSize/4 = 8` paths minimum.  
**Reference:** `../fukuii-state/snap-sync/phase4-healing.md`

---

### AP-8: Serving Accounts for Roots Older Than 127 Blocks
**Failure:** Hive `TestSnapGetAccountRange` case 11 fails. Also causes false serving of pruned data.  
**Rule:** `>127 blocks old` → return empty (not error). `exactly 127 blocks` → serve.  
**Reference:** `../ref-clients/hive/snap-tests.md`

---

### AP-9: Non-Atomic Block+Weight Writes
**Failure (BUG-W7):** Crash between block write and weight write → inconsistent DB on restart → incorrect fork choice → wrong chain selection.  
**Fix:** Write block data and chain weight in the same RocksDB batch.  
**Reference:** `../fukuii-state/snap-sync/known-bugs.md#BUG-W7`

---

### AP-10: Not Building Merkle Proofs on Range Responses
**Failure:** Hive `VerifyRangeProof` call fails for any non-empty range response without proofs.  
**Rule:** Every non-empty `AccountRange` and `StorageRange` response MUST include boundary proofs.  
**Reference:** `../ref-clients/hive/snap-tests.md#proof-verification`

---

## P2P Anti-Patterns

### AP-11: Using networkId = 61 for ETC
**Failure:** ETC uses `networkId = 1` (legacy). Using 61 breaks peer acceptance — peers reject Status with wrong networkId.  
**Reference:** `../ref-clients/core-geth/network-config-comparison.md`

---

### AP-12: Sending ETH69 Status for ETC Mainnet Peers
**Failure:** ETH69 drops TD. ETC peers need TD for PoW chain selection. Connecting with ETH69 status to ETC peers results in rejection or wrong chain selection.  
**Rule:** ETC uses ETH68 only. ETH69 implementation in Fukuii is for future compatibility, not for ETC mainnet peering.  
**Reference:** `../ref-clients/core-geth/eth-protocol-td-versions.md`

---

### AP-13: Sending Unsolicited GetBlockHeaders After Status
**Failure:** Hive `TestStatus` — sending a second Status message causes disconnect. Sending GetBlockHeaders immediately also violates expected message flow in devp2p tests.  
**Rule:** After Status exchange, wait for sync engine to request headers when needed.  
**Reference:** `../ref-clients/hive/eth-tests.md`

---

### AP-14: Per-Peer Subscription Only for Snap Request Serving
**Failure:** Hive devp2p snap tests fire GetAccountRange before ETH-Status exchange completes. Per-peer subscriptions aren't installed yet → requests silently dropped → test timeouts.  
**Fix:** Subscribe globally (`PeerSelector.AllPeers`) to snap request codes at startup.  
**Reference:** `../fukuii-state/p2p/peer-management.md`

---

## Configuration Anti-Patterns

### AP-15: Using Stale ETC DNS Bootnodes
**Failure:** `all.classic.blockd.info` returns 0 enodes silently. Zero peers found at startup.  
**Fix:** Use `all.classic.etcdisco.net` (296 enodes as of 2026-05-04).  
**Reference:** `../ref-clients/core-geth/mordor-config.md`, Fukuii memory `fukuii-etc-discovery.md`

---

### AP-16: core-geth --syncmode full Peers in Snap-Only Slot Budget
**Failure:** core-geth nodes with `--syncmode full` advertise `snap/1` but cannot serve snap requests. They fill peer slots, starving real snap peers.  
**Fix:** Track as "snapless" peers (distinct from "stateless"). Mark permanently and avoid re-selecting for snap requests.  
**Reference:** `../fukuii-state/snap-sync/phase1-implementation.md#stateless-vs-snapless`

---

## Concurrency Anti-Patterns

### AP-17: Actor-per-Request for SNAP Work
**Failure:** Creating one actor per in-flight SNAP request generates thousands of short-lived actors during Phase 1. JVM overhead (GC, mailbox allocation) stalls the ActorSystem.  
**Fix:** One coordinator actor per phase, with in-memory task queue and in-flight map.  
**Reference:** `../best-practices/concurrency-patterns.md`

---

### AP-18: Global Semaphore for Request Concurrency
**Failure:** A global `maxInFlight: Int` shared across all peers causes fast peers to wait for slow peers to free slots.  
**Fix:** Per-peer `maxInFlightPerPeer` budget. Each peer can saturate independently.  
**Reference:** `../best-practices/concurrency-patterns.md`

---

### AP-19: Blocking DB Writes on sync-dispatcher
**Failure:** RocksDB batch writes during healing block the `sync-dispatcher` thread pool. All other sync actors (account range coordinator, storage coordinator) stall waiting for dispatcher threads.  
**Fix:** Dedicated `healing-writer-dispatcher` with a separate thread pool.  
**Reference:** `../fukuii-state/snap-sync/phase4-healing.md`

---

## Cross-References
- Hive tests (correctness specification): `../ref-clients/hive/snap-tests.md`
- Fukuii bug history: `../fukuii-state/snap-sync/known-bugs.md`
- Best practices (positive patterns): [snap-sync-patterns.md](snap-sync-patterns.md)
