# Mutable State in Consensus/EVM Code is Parity-Correct, Not Debt

Two findings, both establishing that mutability the FP-purity lens would flag as "debt" is
in fact the **universal reference-client implementation** of the same code. Ratified by the
cross-check in `reference-client-crosscheck.md`. Cited by the `var`-in-consensus coding
standard.

The governing principle: **on the EVM/consensus hot path and in the Engine-API state
manager, in-place mutation is the correct shape because every reference client — across
Go, Java, and C# — implements it that way, and because they say so in their own comments.**
Rewriting these to persistent/immutable structures is not a modernization; it is a
divergence from the proven design and a measurable performance regression.

---

## Finding 1 — Mutable hot-path buffers + imperative counters are universal

**Invariant:** The EVM interpreter's stack, memory, and gas counter; the Ethash inner loop's
mix buffer; MPT node hashing; and the EIP-4844 `fake_exponential` loop are all implemented
with in-place-mutable buffers and imperative counters in *every* client that vendors them.
This is not one client's shortcut — it is the cross-language consensus on how this code is
shaped.

### Evidence table

| Sub-claim | Client (weight) | Evidence (`file:line`) | Verdict |
|-----------|-----------------|------------------------|---------|
| Ethash inner loop mutates a mix buffer in place | core-geth (**Ethash authority**) | `consensus/ethash/algorithm.go:365-388` — `mix []uint32` mutated across `loopAccesses`, `fnvHash(mix, temp)` in place | SUPPORTED |
| Same, with the buffer explicitly *pooled* for perf | erigon (perf patterns) | `execution/protocol/rules/ethash/algorithm.go` — pooled `mix`/`temp` buffers | SUPPORTED |
| EVM stack is a mutable array + integer `top`/`size` | besu (**JVM analog — weighted**) | `evm/internal/FlexStack.java:55-190` — backing `Object[] entries` + mutable `int top` | SUPPORTED |
| EVM memory is a growable mutable byte buffer | besu (JVM) | `evm/frame/Memory.java:43-145` | SUPPORTED |
| Gas is a mutable `long` decremented per op | besu (JVM) | `evm/frame/MessageFrame.java:207` — mutable `gasRemaining` | SUPPORTED |
| The client *documents* this as intentional | besu (JVM) | `evm/EVM.java:471` — `// Note: like runToHalt, this is performance-critical code. Benchmark before refactoring.` | SUPPORTED |
| Same stack/gas design in Go | go-ethereum | `core/vm/stack.go:78-158` (mutable arena-backed stack), `core/vm/contract.go:131-154` (mutable gas on `Contract`) | SUPPORTED |
| Same again | erigon | `execution/vm/interpreter.go`, `execution/vm/stack.go` | SUPPORTED |
| MPT node hashing mutates lazily + tracks a dirty flag | besu (JVM) | `BranchNode.java` — mutable lazy-hash cache + dirty flag | SUPPORTED |
| MPT in-place child mutation during insert | go-ethereum | `trie/trie.go:208-212` — child pointer mutated in place | SUPPORTED |
| EIP-4844 `fake_exponential` is an imperative accumulator loop | go-ethereum | `consensus/misc/eip4844/eip4844.go:215-227` — `for i := 1; accum.Sign() > 0; i++` mutating `output`/`accum` | SUPPORTED |
| Same loop, JVM | besu (JVM) | `evm/fluent/../BlobFeeMarket.java:60-73` (imperative `fakeExponential`) | SUPPORTED |

**Verdict: SUPPORTED across all vendoring clients and both language families.** No client
implements these paths with persistent/immutable structures.

### Standard

- **Do not "modernize" hot-path mutable buffers or imperative counters to immutable
  structures.** The mutation is the proven, benchmarked design. A `var`/mutable-array on
  the EVM stack, memory, gas counter, Ethash mix, MPT node cache, or a blob-fee loop is
  parity-correct and requires **no** justification comment beyond a pointer to this finding.
- besu's own words are the standard: **"performance-critical code. Benchmark before
  refactoring."** (`evm/EVM.java:471`). Any proposed rewrite of one of these paths must
  carry a benchmark, not a purity argument.
- reth is **not** citable here — its interpreter is the external `revm` crate and is not
  vendored (see coverage map).

---

## Finding 2 — Concurrent cross-request Engine-API mutable state is a sanctioned PoS pattern

**Invariant:** The PoS Engine-API layer maintains **mutable state shared across concurrent
JSON-RPC requests** (payload caches, invalid-block/tipset tracking, finality/sync
snapshots), guarded by explicit locks or concurrent collections. This is the intended
architecture of the reference clients, not a smell to refactor toward message-passing.

### Evidence table

| Sub-claim | Client (weight) | Evidence (`file:line`) | Verdict |
|-----------|-----------------|------------------------|---------|
| Invalid-block/tipset maps guarded by dedicated locks | go-ethereum | `eth/catalyst/api.go:93-127` — `invalidBlocksHits`/`invalidTipsets` maps + `invalidLock`/`forkchoiceLock`/`newPayloadLock` | SUPPORTED |
| Payload cache behind an RWMutex | go-ethereum | `eth/catalyst/queue.go:48-71` — payload cache + `sync.RWMutex` | SUPPORTED |
| Finality/sync state in an `AtomicReference` + guarded queue | besu (**JVM analog — weighted**) | `PostMergeContext.java:42-61` — `AtomicReference` finality/sync state + `synchronized EvictingQueue` | SUPPORTED |
| Bad-block tracking via concurrent caches | besu (JVM) | `BadBlockManager.java:37-46` — Guava concurrent caches | SUPPORTED |

**Verdict: SUPPORTED.** The JVM-weighted witness (besu) uses exactly the JVM concurrency
toolkit — `AtomicReference`, `synchronized`, concurrent-cache — that idiomatic Scala on
Pekko would reach for, confirming this shape translates directly.

### Standard

- **Cross-request mutable state in the Engine-API / fork-choice manager, guarded by explicit
  locks or concurrent/atomic collections, is a sanctioned PoS pattern.** Do not flag it as
  requiring conversion to an actor's private state or a message-passing rewrite purely on
  FP-purity grounds.
- The guard **must** be present and correct (lock, `Atomic*`, or a concurrent collection) —
  the sanction is for *guarded* shared mutable state, not for unsynchronized sharing.
- This is a PoS (`beacon`) finding. The PoW (`forge`) analog is Finding 1's per-execution
  hot-path buffers, which are single-threaded within a block execution and need no guard.
