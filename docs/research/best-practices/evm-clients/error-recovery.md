# Best Practices: Error Recovery Patterns

Synthesized from go-ethereum, reth, Besu, erigon, and Fukuii.

---

## 1. Peer Disconnect → Immediate Request Cancellation

**This is the single most important error recovery pattern.**

**Pattern:**
```
On peer disconnect:
  for each in-flight request from that peer:
    cancel timeout timer
    re-enqueue task (to front of queue)
    reset consecutive-timeout counter for that coordinator
```

**Why:** If disconnect is not handled, the timeout timer fires, increments
`consecutiveTimeouts`. At threshold, the coordinator declares "all peers stalled"
and stops dispatching — even though other peers are healthy. This is BUG-W8.

**go-ethereum:** `revertRequests()` called synchronously in `peerDrop()` handler.
**reth:** Future drop semantics — all `oneshot::Receiver` handles resolve to `ChannelClosed`.
**Fukuii:** `CancelRequests(peerId)` message to all 4 coordinators.

---

## 2. Adaptive Request Timeout

**Algorithm (go-ethereum p2p/msgrate port):**
```
medianRTT = median of last N response times (EMA-weighted)
timeout = 3 × medianRTT / confidence
min: ~2s (cold start)
max: 60s (hard cap)
```

**Why 3× medianRTT:** Accounts for variance in network conditions. Too low → excessive
false timeouts. Too high → slow detection of genuinely dead peers.

**Per-peer tracking:** Each peer has its own RTT history. A slow peer shouldn't inflate
timeouts for fast peers.

**Fukuii implementation:** `SNAPRequestTracker.scala` (307 lines), `PeerRateTracker` for EMA.

---

## 3. Stagnation Watchdogs (Multiple Levels)

A well-designed client has watchdogs at every level of the download stack:

| Level | Interval | Condition | Action |
|-------|----------|-----------|--------|
| Per-phase (controller) | 30s | No progress for 10 min | Force-complete storage |
| Healing (coordinator) | 2 min | 3 cycles with 0 nodes healed | Request pivot restart |
| Healing (idle) | 5 min | No nodes healed at all | Declare healing complete |
| Pivot refresh (coordinator) | 15 min | pivotRefreshRequested stuck | Force-reset flag |

**Why multiple levels:** A single top-level watchdog misses stalls inside coordinators
(e.g., all peers cooling down simultaneously). Inner watchdogs detect inner stalls before
they propagate upward.

---

## 4. Exponential Backoff for Transient Failures

**Pattern (Fukuii ByteCodeCoordinator):**
```
failures per peer: Map[PeerId, Int]
backoff = min(base × 2^(failures-1), maxBackoff)
reset failures on successful response
```

**When to use exponential vs. flat cooldown:**
- **Exponential:** Content-addressed requests (bytecodes) — same peer can recover after a few
  failures. Backoff grows to give peer time to recover.
- **Flat cooldown:** Root-specific requests (account/storage ranges) — use binary stateless
  detection instead, cleared on pivot refresh.

---

## 5. Force-Complete With Recovery

**Pattern:**
```
Threshold: maxConsecutiveTaskFailures = 100
On threshold:
  1. Report ForceComplete* to controller
  2. Controller continues: marks phase as done
  3. Missing data recovered by BytecodeRecoveryActor during chain import
```

**Why not retry indefinitely:** Some state gaps cannot be resolved by re-requesting from
the same peers. Hanging indefinitely blocks chain progress.

**Recovery guarantee:** The EVM executes blocks sequentially. Any missing bytecode or storage
slot is fetched on-demand when the block that first uses it is executed. The execution engine
logs a "missing code" error and fetches from peers — the same as a light client fallback.

---

## 6. Crash Recovery: Atomic Commits

**Pattern (erigon, Fukuii BUG-W7 fix):**

Rule: block data and chain weight are written in the same atomic DB batch.

**Why:** On crash between writing block data and updating chain weight, the DB is inconsistent:
- Weight recorded but block missing → chain appears heavier than it is
- Block stored but weight stale → chain appears lighter than it is

Both cause incorrect fork choice decisions on restart.

**Erigon pattern:** Stage boundary commits — progress is written atomically with stage data
in the same MDBX transaction. Guarantees either the full stage result OR no stage result.

**Safe fallback on read failure:** Rather than crashing, return genesis weight. The sync
engine will re-download from genesis if needed.

---

## 7. Progress Checkpointing

**Minimum acceptable:** Checkpoint account range progress at each range completion.
**Better:** Checkpoint every N accounts (reduces restart overhead).
**Best (Besu):** Checkpoint on every account dequeue.

**What to checkpoint:**
1. Current pivot block number
2. Per-range `(startHash, lastReceivedHash)` map

**Staleness check on restore:**
```
if |currentPivot - savedPivot| > MaxPreservedPivotDistance (256):
    discard saved progress — state may have changed significantly
```

---

## 8. Task Re-Enqueue on Failure

**Rule:** On any request failure (timeout, empty response, verify fail):
1. Cancel the timeout timer
2. Return tasks to the queue **at the head** (not tail)
3. Decrement in-flight count for that peer
4. Apply appropriate cooldown to the peer

**Why head re-enqueue:** Tasks that just failed are likely to succeed quickly with a different
peer. Putting them at the tail means other tasks run first, extending the overall time.
Head re-enqueue gives highest priority to tasks that need retry.

---

## 9. Proof Verification Before Storage Write

**Rule (go-ethereum, Fukuii):**
```
receive AccountRange response
↓
VerifyRangeProof(root, start, hashes, accounts, proof)
↓
if ok: write to MptStorage + FlatSlotStorage
if fail: discard all accounts from this response, cooldown peer
```

**Why:** An adversarial or buggy peer could send plausible-looking account data with an
invalid proof. Writing unverified data corrupts the trie — silently, leading to validation
failures at the end of sync that are hard to diagnose.

---

## Cross-References
- BUG-W8 (cancellation failure): `../fukuii-state/snap-sync/known-bugs.md`
- BUG-W7 (atomic commit): `../fukuii-state/snap-sync/known-bugs.md`
- reth cancellation pattern: `../ref-clients/reth/snap-protocol/async-concurrency.md`
- erigon crash recovery: `../ref-clients/erigon/snap-protocol/recovery-design.md`
- Fukuii error handling: `../fukuii-state/error-handling.md`
