# nethermind — txpool
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind's mempool lives in `Nethermind.TxPool/` and is built from two orthogonal
mechanisms that compose cleanly:

1. **Admission = a two-phase array of `IIncomingTxFilter`s.** Every rule that can reject an
   incoming transaction is a small, single-responsibility filter object implementing one
   method, `Accept(tx, ref state, options) → AcceptTxResult`. `TxPool` holds two arrays —
   `_preHashFilters` (cheap checks that run *before* ECDSA sender recovery) and
   `_postHashFilters` (checks that need the recovered sender / assigned hash) — and
   `FilterTransactions` simply loops each array, short-circuiting on the first non-accepting
   result (`TxPool.cs:611-634`). A transaction is admitted only if it survives both passes.

2. **Storage/ordering = a bucketed `SortedPool`.** Accepted txs live in a
   `TxDistinctSortedPool`: a `Dictionary<sender, EnhancedSortedSet<Transaction>>` where each
   per-sender bucket is sorted ascending by nonce, plus a global "worst element per bucket"
   sorted set (`_worstSortedValues`) that drives capacity eviction. Blob txs use a separate
   parallel pool (in-memory or persistent).

Flow (`TxPool.SubmitTx` → `AddTransaction`, `TxPool.cs:560-596`): take a read-lock on the
head, run the filter chain, and on acceptance call `AddCore` which computes the tx's
`GasBottleneck` (its effective gas price, capped by the sender bucket's current worst),
inserts into the relevant pool (evicting the global-worst tx if over capacity), and fires
events. A `TxBroadcaster` handles gossip out of band.

## Key types / interfaces / files

- `Filters/IIncomingTxFilter.cs:12` — the whole admission abstraction: one method,
  `AcceptTxResult Accept(Transaction tx, ref TxFilteringState state, TxHandlingOptions opts)`.
- `TxPool.cs:41-42` — `_preHashFilters` / `_postHashFilters`, the two composed filter arrays.
- `TxPool.cs:148-181` — where the chain is *assembled* (the ordered filter list). Order is
  load-bearing and commented inline (e.g. `NullHashTxFilter` "needs to be first as it assigns
  the hash"; `LowNonceFilter` "has to be after `UnknownSenderFilter`").
- `TxPool.cs:174-177` — **the extension seam**: an optional injected `incomingTxFilter` is
  appended to the post-hash filters. Host code can add a custom admission rule without
  touching the pool.
- `TxPool.cs:611-634` — `FilterTransactions`: the pipeline runner (two loops, first-reject wins).
- `Filters/FeeTooLowFilter.cs` — min-fee gate; note `isLocal` (PersistentBroadcast) txs are
  exempted at line 27-31, and full-pool txs are rejected if they can't beat the worst tx's
  `GasBottleneck` (44-55). **This is the closest analogue to a tip-floor filter.**
- `Filters/PriorityFeeTooLowFilter.cs` — blob-tx min priority-fee gate driven by
  `ITxPoolConfig.MinBlobTxPriorityFee`; a clean example of a **config-driven fee floor as a
  filter** (line 23-28).
- `Filters/` (20 filters) — the full rule set: `NotSupportedTxFilter`, `SizeTxFilter`,
  `GasLimitTxFilter`, `MalformedTxFilter`, `AlreadyKnownTxFilter`, `UnknownSenderFilter`,
  `BalanceTooLowFilter`, `LowNonceFilter`, `FutureNonceFilter`, `GapNonceFilter`,
  `DelegatedAccountFilter`, `DeployedCodeFilter`, etc. Each is ~30-60 lines.
- `Collections/SortedPool.cs:22` — the generic `SortedPool<TKey,TValue,TGroupKey>`:
  `_buckets` (per-group sorted sets), `_cacheMap` (key→value), `_worstSortedValues` +
  `_worstValue` (global eviction index), `McsLock` for concurrency. `TryInsert` (400) does
  capacity eviction via `RemoveLast` (436); `GetBest` (223) is the global-best pick.
- `Collections/DistinctValueSortedPool.cs` — adds **replacement semantics**: two txs "compete"
  if same sender+nonce (`CompetingTransactionEqualityComparer`); `CanInsert` (81-104) admits a
  replacement only if it ranks `<=` the incumbent under the replacement comparer.
- `Collections/TxDistinctSortedPool.cs:18` — the concrete tx pool: group = `AddressAsKey`
  (sender), key = tx hash, group order = by-nonce, replacement order = by-fee.
- `Comparison/CompareReplacedTxByFee.cs` — the fee-bump rule for replace-by-fee (must beat
  old fee by a fixed fraction). `CompareReplacedBlobTx.cs` requires a 2x bump for blobs.
- `Collections/BlobTxDistinctSortedPool.cs` / `PersistentBlobTxDistinctSortedPool.cs` +
  `BlobTxStorage.cs` — the separate blob-tx pool, optionally persisted to disk so blob txs
  survive restarts.
- `TxBroadcaster.cs:24` — gossip: keeps a `_persistentTxs` pool (local txs, re-broadcast on a
  timer) plus `_accumulatedTemporaryTxs` (peer txs batched between timer ticks); consults an
  `ITxGossipPolicy`; broadcasts local txs immediately when `MaxFeePerGas ≥ _baseFeeThreshold`.
- `AcceptTxResult.cs:12` — the readonly-struct result type (`Accepted`, `FeeTooLow`,
  `NonceGap`, `OldNonce`, `InsufficientFunds`, `ReplacementNotAllowed`, ...); every filter
  returns one, and `.WithMessage(...)` attaches a reason string.
- `ITxPool.cs` — the public surface: `SubmitTx`, `GetPendingTransactions`,
  `GetPendingTransactionsBySender`, and `NewDiscovered/NewPending/RemovedPending/EvictedPending`
  events.

## Design decisions & rationale

- **Two-phase filter split is a performance decision.** Sender recovery (ECDSA `ecrecover`)
  is the expensive step. Cheap, sender-independent rejections (unsupported type, oversize,
  gas-limit, obviously-too-low fee) run *first* in `_preHashFilters` so junk is dropped before
  paying for recovery/hashing; only survivors hit `_postHashFilters` (`TxPool.cs:613-631`).
- **Ordering is enforced by list position, not priorities.** Filters have implicit
  dependencies (a filter that reads the sender must run after the one that recovers it). This
  is documented with inline comments at the assembly site rather than a declarative ordering
  system — simple, but fragile (see gotchas).
- **`GasBottleneck` = per-tx effective gas price capped by its bucket's worst.** Because a
  sender's txs must execute in nonce order, a later high-fee tx can't really pay more than the
  cheapest earlier tx in its bucket. Capping the sort key at the bucket worst
  (`TxPool.cs:642-645`) makes global fee-ordering / eviction fair across senders.
- **Replacement (RBF) is a comparer, not special-cased code.** `DistinctValueSortedPool`
  treats same-sender+same-nonce as a duplicate and delegates the keep-old/keep-new decision to
  a pluggable replacement comparer requiring a fee bump — RBF policy is data, not control flow.
- **Local txs are privileged.** `PersistentBroadcast` (locally submitted) txs bypass the
  fee-too-low gate and are kept in the broadcaster's persistent pool even if evicted from the
  main pool, so a node always re-gossips its own users' txs.
- **Blob txs are a physically separate pool** with their own capacity and optional disk
  persistence, because they are large and have distinct fee mechanics (blob base fee).

## Notable patterns (the reusable idea)

**Admission as a composable filter chain.** The single most transferable idea: model mempool
admission as an ordered pipeline of tiny, independently-testable `IIncomingTxFilter` objects,
each returning an accept/reject-with-reason, with the pool running them in sequence and
short-circuiting on first reject. Adding, removing, or reordering a rule is a one-line change
to the assembly list; a host/enterprise deployment adds a *custom* rule by injecting one extra
filter (`TxPool.cs:174-177`) with zero changes to the pool core. Rules that are policy floors
(min fee, min priority fee) are just filters parameterized by config
(`PriorityFeeTooLowFilter` reading `ITxPoolConfig.MinBlobTxPriorityFee`).

**Bucketed sorted pool with a global-worst eviction index.** Per-sender buckets sorted by
nonce give correct execution ordering for free; a separate sorted set holding only each
bucket's worst element makes "what do I evict when full?" O(log n) instead of scanning the
whole pool.

### Direct relevance to fukuii's `banksy` admission tier

banksy owns exactly this layer — operator-tunable, non-consensus admission policy that sits
between consensus and networking. This filter-chain shape maps onto it almost 1:1:

- **ECIP-1122 `MIN_MINER_TIP` becomes a filter.** A `TipTooLowFilter` implementing the same
  accept/reject contract, parameterized by the configured tip floor, dropped into the chain —
  structurally identical to `FeeTooLowFilter` / `PriorityFeeTooLowFilter`. The floor is
  config, not a hard fork, which is precisely banksy's "does not change the state root" test.
- **Enterprise custom admission** (allowlists, per-tenant rate limits, compliance gates) is the
  injected-`incomingTxFilter` seam — a customer adds admission rules without forking the pool.
- **Transaction selection / ordering for mining** maps to the `SortedPool` side: bucketed-by-
  sender, nonce-ordered, fee-ranked for block production — the `GetBest`/`GasBottleneck`
  machinery is the validator/mining ordering primitive.

The takeaway for fukuii: keep the *admission policy* (banksy filters) separate from the
*ordering data structure* (SortedPool), so tip floors and enterprise rules are composed in,
not hard-coded.

## Authority note

go-ethereum remains the **canonical reference for txpool behavior** (nonce-gap handling,
replace-by-fee thresholds, pool sizing, eviction semantics) — validate byte/behavior parity
against it, not against Nethermind. Nethermind is cited here as the **composable-admission
reference**: its `IIncomingTxFilter` pipeline is the clearest existing implementation of
admission-as-a-filter-chain, which is the structural pattern fukuii's `banksy` admission tier
should adopt. Use go-ethereum for *what the rules are*, Nethermind for *how to structure the
rule engine*.

## Gotchas / anti-patterns / things they later changed

- **Filter order is implicit and fragile.** Correctness depends on list position
  (`TxPool.cs:158-172`), guarded only by inline comments (`// has to be after
  UnknownSenderFilter`). Reordering the list can silently break a rule that reads state a
  prior filter populates. If fukuii adopts the pattern, prefer making inter-filter
  dependencies explicit (declared prerequisites) rather than relying on array index.
- **Filters run under `TxFilteringState` passed by `ref`** and are expected to be cheap and
  side-effect-light; the pre/post-hash split exists precisely because putting an expensive
  check in the wrong phase wastes recovery cost on doomed txs. A custom enterprise filter
  doing I/O (e.g. a compliance lookup) should be added to the *post*-hash phase and must be
  fast or it becomes a mempool DoS vector.
- **The pool self-heals over capacity.** `SortedPool.EnsureCapacity` / the retry loop in
  `TryInsert` (`TxPool.cs:412-423`, `SortedPool.cs:625-655`) log warnings and force-evict when
  `Count > capacity` — an admission that "auto-recover mitigates bad consequences of such
  bugs," i.e. they treat capacity overshoot as a latent bug class and defensively repair
  rather than trusting invariants.
- **Snapshot cache invalidation is lock-ordering-sensitive.** `_transactionSnapshot` /
  `_blobTransactionSnapshot` must be nulled *inside* the head read-lock (`TxPool.cs:578-587`),
  or a concurrent reader can cache a stale snapshot missing the just-added tx — a subtle
  concurrency footgun called out directly in the code.
- **Concurrency uses a custom `McsLock`** (queue-based spinlock) per pool rather than a plain
  `lock`; visitors passed to `VisitBucket` run while the lock is held and must not re-enter the
  pool (`SortedPool.cs:578-604`).
