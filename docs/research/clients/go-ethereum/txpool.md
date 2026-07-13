# go-ethereum — txpool
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary

geth's transaction pool is a **thin aggregator over a set of pluggable `SubPool`
implementations**, not a monolith. `core/txpool/txpool.go` defines `TxPool`,
which owns `subpools []SubPool` plus the chain-head subscription and the shared
state snapshot; every public operation (`Add`, `Pending`, `Get`, `Stats`,
`Content`, `Nonce`, …) is a fan-out/merge over the subpools. Two concrete
subpools ship today:

- **`legacypool/`** — the classic in-memory EVM-execution pool for
  Legacy / AccessList / DynamicFee (1559) / SetCode (7702) transactions.
  Pending (executable) + queued (future/non-executable) split, priced eviction,
  10% price-bump replacement, async reorg loop. Family-neutral in structure.
- **`blobpool/`** — the EIP-4844 blob-transaction pool. **Disk-backed**
  (`holiman/billy` shelf store), a completely different design: no
  pending/queued split, fee-"jump" eviction priority, blobs live on disk and are
  *pulled* not pushed. PoS/ETH-only.

Routing between subpools is by `SubPool.Filter(tx)` — `TxPool.Add`
(`txpool.go:318`) walks subpools in order and gives the tx to the first that
accepts its type; an unclaimed type returns `ErrTxTypeNotSupported`
(`txpool.go:351`). Because a blob tx and a normal tx from the same account would
otherwise collide, a shared **`Reserver`** (`reserver.go`) grants one subpool
exclusive ownership of an account's in-flight nonces at a time.

Admission validation is centralized in `validation.go`, shared by both subpools:
a **stateless** `ValidateTransaction` (fork-rule/type/size/tip/intrinsic-gas
gates) and a **stateful** `ValidateTransactionWithState` (nonce ordering,
balance, overdraft, slot limits). This is the layer that maps to fukuii's
**banksy** client-layer policy tier.

The main-thread event loop (`txpool.go:154 loop`) consumes chain-head events and
drives a single-flight `Reset` across all subpools; legacypool additionally runs
its own async `scheduleReorgLoop`/`runReorg` (`legacypool.go:1139`, `:1209`) so
that promotion/demotion/truncation never block the add path.

## Key types / interfaces / files

- `core/txpool/subpool.go:98` — `SubPool` interface: the seam. `Filter` /
  `FilterType` (routing), `Init(gasTip, head, reserver)` / `Reset` / `Close`
  (lifecycle in lockstep), `Add` / `Pending(filter)` (admission + mining view),
  `Get`/`GetRLP`/`GetMetadata`/`Has`/`Status`/`Content` (queries),
  `ValidateTxBasics`, `SetGasTip`, `Nonce`, `Stats`, `Clear`.
- `core/txpool/subpool.go:33` — `LazyTransaction`: a small handle (hash + fee
  caps + gas) the miner batches over, with `Resolve()` pulling the full tx only
  when needed. Avoids materializing every pooled tx for block building.
- `core/txpool/subpool.go:75` — `PendingFilter`: cheap pre-filters (`MinTip`,
  `BaseFee`, `BlobFee`, `GasLimitCap`, `BlobTxs`, `BlobVersion`) the miner passes
  into `Pending` so the pool caps lists before allocating.
- `core/txpool/txpool.go:68` — `TxPool` aggregator; `New` (`:87`) inits subpools
  in order and hands each a `reserver.NewHandle(i)`.
- `core/txpool/txpool.go:46` — `BlockChain` interface (Config / CurrentBlock /
  Genesis / SubscribeChainHeadEvent / StateAt) — the mockable chain backing.
- `core/txpool/validation.go:63` — `ValidateTransaction` (stateless admission).
- `core/txpool/validation.go:245` — `ValidateTransactionWithState` (nonce /
  balance / overdraft / per-account slot gate).
- `core/txpool/validation.go:43,217` — `ValidationOptions` /
  `ValidationOptionsWithState`: how each subpool parameterizes the shared checks
  (accepted-type bitmap, `MaxSize`, `MaxBlobCount`, `MinTip`; callbacks
  `FirstNonceGap`, `UsedAndLeftSlots`, `ExistingExpenditure`, `ExistingCost`).
- `core/txpool/reserver.go:41,61,79` — `ReservationTracker` / `Reserver` /
  `ReservationHandle`: cross-subpool account exclusivity (`Hold`/`Release`/`Has`,
  per-subpool `id` ownership).
- `core/txpool/legacypool/legacypool.go:232` — `LegacyPool`: `pending
  map[addr]*list`, `queue *queue`, `all *lookup`, `priced *pricedList`,
  `pendingNonces *noncer`.
- `core/txpool/legacypool/legacypool.go:160` — `DefaultConfig`: `PriceLimit 1`,
  `PriceBump 10`, `AccountSlots 16`, `GlobalSlots 4096+1024`, `AccountQueue 64`,
  `GlobalQueue 1024`, `Lifetime 3h`.
- `core/txpool/legacypool/legacypool.go:666` — `add`: the core admission/insert
  path (dedup → validate → reserve → overflow discard → replace-or-enqueue).
- `core/txpool/legacypool/list.go:303` — `list.Add`: the **10% price-bump
  replacement rule** (must beat both feeCap and tip thresholds).
- `core/txpool/legacypool/list.go:543` — `pricedList`: the **two-heap** price
  index (`urgent` by effective tip, `floating` by gasFeeCap, 4:1 ratio) used for
  underpriced detection and overflow eviction.
- `core/txpool/legacypool/legacypool.go:1437,1517,1541` — `truncatePending` /
  `truncateQueue` / `demoteUnexecutables`: global-limit + reorg eviction.
- `core/txpool/blobpool/blobpool.go:232` (`newBlobTxForPool`), `:125`
  (`blobTxMeta`), `priority.go:39` (`evictionPriority`), `evictheap.go` — the
  blobpool's disk-backed, fee-jump-priority design.
- `core/txpool/locals/tx_tracker.go` — "local" (operator-submitted) tx tracking,
  now a **separate wrapper** (journal + re-inject) rather than a flag inside
  legacypool.
- `eth/handler.go:458` — `BroadcastTransactions`: the eth/68 direct-vs-announce
  propagation split.
- `eth/fetcher/tx_fetcher.go` — announcement-driven retrieval state machine
  (`maxTxRetrievals 256`, `maxTxRetrievalSize 128KB`, `txArriveTimeout 500ms`,
  `maxTxUnderpricedSetSize`).

## Design decisions & rationale

- **SubPool abstraction (the headline).** Rather than special-casing blob txs
  inside the existing pool, geth defined a narrow `SubPool` interface and made
  the top-level pool a coordinator. New tx classes with radically different
  storage/economics (blobs on disk) plug in without touching the legacy path;
  subpools are kept "in lockstep" via a shared `Init`/`Reset` cadence driven by
  one head-event loop (`subpool.go:106-113` explicitly forbids self-starting
  constructors so ordering is centrally controlled).
- **Cross-subpool account reservation.** One account must not have in-flight
  state-changing txs in two subpools at once (e.g. a blob tx and a 7702 SetCode
  from the same address). `Reserver.Hold` grants exclusive ownership on first
  insert (`legacypool.go:690`) and `Release` frees it when the account empties
  (`removeTx` defer, `:1065`). Ownership is tracked per-subpool `id` so a pool
  can't release another's reservation.
- **Stateless / stateful validation split.** `ValidateTxBasics` (stateless) runs
  *before* taking the pool mutex and caches the recovered sender
  (`legacypool.go:929`), so signature recovery and fork-rule checks don't
  serialize behind the lock. Stateful checks (nonce/balance/overdraft) run under
  the lock against the head state snapshot.
- **Pending vs queued (executable vs future).** legacypool separates
  immediately-executable txs (`pending`, contiguous from account nonce) from
  nonce-gapped `queue` txs; `promoteExecutables` moves queued→pending when gaps
  fill, `demoteUnexecutables` moves pending→queue on reorg/nonce-reset
  (`:1405`, `:1541`). Mining only ever sees `pending` via `Pending`.
- **Two-heap priced eviction.** `pricedList` (`list.go:537-555`) keeps an
  `urgent` heap ordered by *effective tip in the next block* and a `floating`
  heap ordered by *gasFeeCap*, at a 4:1 capacity ratio, and always evicts from
  the larger. Rationale in-comment: during congestion effective-tip ordering is
  the right signal; when the base fee later drops, high-feeCap txs (floating)
  become the better keepers. Overflow eviction (`add`, `:706-765`) discards the
  globally cheapest, but **future txs may never churn out a pending tx**
  (`isGapped` + `ErrFutureReplacePending`, `:736`).
- **10% price-bump replacement.** Replacing a same-nonce tx requires beating both
  the fee cap *and* the tip by `PriceBump` percent (`list.go:307-325`) — checked
  against explicit thresholds so tiny Wei-level bumps can't churn the pool.
- **Async reorg, single-flight.** `scheduleReorgLoop` coalesces reset/promote
  requests and runs `runReorg` on its own goroutine; adds only enqueue and
  request a reorg, they never block on promotion. `changesSinceReorg` caps
  churn at 25% of GlobalSlots between reorgs (`:719`) as DoS protection.
- **Reorg tx re-injection with a depth cap.** `reset` (`:1305`) diffs the old
  and new canonical chains and re-injects dropped txs, but bails on reorgs
  deeper than 64 blocks (`:1314`) to avoid pulling unbounded history into memory.
- **Blobpool is deliberately different.** Blobs are large and pulled on demand,
  so the pool keeps only `blobTxMeta` in RAM and blobs on disk in a `billy` shelf
  store; eviction is by an abstract **fee-jump priority** (`priority.go`):
  distance in `log1.125(fee)` space between a tx's fee caps and current
  base/blob fee, approximating "how many fee-halvings until includable" — a
  time-like ordering. `maxTxsPerAccount 16` and short `gappedLifetime` blunt the
  DoS surface of privately-cancellable public blobs.
- **eth/68 announcement propagation.** `BroadcastTransactions`
  (`handler.go:458`) sends small non-blob txs *directly* to ~sqrt(peers) chosen
  deterministically per sender, and **announces (hash-only) blob and large txs**,
  letting peers pull them — bandwidth control for heavy payloads.

## Notable patterns (the reusable idea)

1. **The `SubPool` seam.** A single narrow interface + an aggregator that routes
   by `Filter`, coordinates lifecycle in lockstep, and merges query results.
   This is the one pattern most worth copying: it turns "add a new transaction
   class" from surgery on a monolith into "write a new SubPool."
2. **Lazy transaction handles for the mining view.** `Pending` returns
   `LazyTransaction` (hash + fee metadata) grouped by account and nonce-sorted;
   the block builder decides which to `Resolve()`. Keeps the hot block-building
   path allocation-light.
3. **Parameterized shared validation.** One `ValidateTransaction` +
   `ValidationOptions` bitmap/callbacks, reused by every subpool — no duplicated,
   drift-prone admission logic. The admission gate lives in *one* place.
4. **Reservation tracker as a coordination primitive.** A tiny shared
   `map[addr]->ownerID` with `Hold`/`Release`/`Has` cleanly enforces
   "one subpool per account" without either subpool knowing the other's
   internals.
5. **Effective-tip-vs-feeCap dual heap.** A principled two-signal eviction order
   that stays correct as the base fee moves — a better answer than a single
   price key.

## Authority note

geth is the **canonical ETH transaction pool**, and the definitive reference for
the `SubPool` abstraction, the shared `validation.go` admission gates, the
two-heap priced eviction, the 10% price-bump replacement rule, and the entire
**blobpool** (EIP-4844) design — there is no other authoritative implementation
of the disk-backed blob pool. For **ETC/PoW** semantics geth is authoritative
only for the *family-neutral* machinery; the ETC-specific economics are not.

Family-neutral (applies to fukuii's ETC path): the SubPool seam, pending/queued
split, nonce ordering, per-account slot fairness, price-bump replacement, the
reserver, reorg re-injection, and the announcement-based gossip protocol.

**ETH-only, not on fukuii's ETC path:**
- **EIP-1559** effective-tip ordering / `BaseFee` in `PendingFilter` and the
  `urgent` heap — ETC has no 1559 base fee, so the `EffectiveGasTip` degenerates
  to the raw tip/gasprice and there is no base-fee reheap
  (`legacypool.go:1263` London branch).
- **blobpool** entirely (EIP-4844) — serves the PoS validator role only.
- **SetCode / EIP-7702** admission (`validateAuth`, delegation limits,
  `ErrAuthorityReserved`) — Prague/ETH.
- Fork-gate rejections keyed to Berlin/London/Cancun/Prague/Osaka
  (`validation.go:78-98`) and `params.MaxTxGas` — ETH timestamp-fork schedule.

## Gotchas / anti-patterns / things they later changed

- **`Sync`/`Clear` are test-only, DoS-prone in production.** Both call the
  synchronous reset path and are explicitly documented as unsafe for live use
  (`txpool.go:474`, `:491`; `legacypool.go:1814`). Don't wire them into
  operational code.
- **"Local" transactions were refactored out of legacypool.** The old
  `local`/`remote` distinction that pinned operator txs above pricing rules is
  gone from the pool core; it now lives as a separate `locals.TxTracker` wrapper
  (`core/txpool/locals/`) that re-submits journaled txs. Any port that assumes
  legacypool has built-in local-tx privileges is following stale geth.
- **Reservation release is defer-driven and race-sensitive.** `removeTx` takes an
  `unreserve bool` precisely because an add that evicts a previously-scheduled tx
  from the *same* account could otherwise release the lock prematurely
  (`legacypool.go:1046-1052`, `:761`). This is subtle — replicate the flag,
  don't "simplify" it.
- **Double-reservation from the same subpool is swallowed, not fatal.**
  `Hold` logs an error and returns `nil` on same-owner re-reserve
  (`reserver.go:93-96`) "to give the pool a chance to recover while the bug gets
  fixed" — a deliberate soft-fail that can mask accounting bugs.
- **`SubscribeTransactions(reorgs=…)` can't cleanly separate new from
  resurrected txs in legacypool** — the in-comment admission (`legacypool.go:409`)
  is that reorg-resurrected and newly-added txs share the queue path, so the
  `reorgs` flag is best-effort there.
- **Overflow churn is capped at 25% of GlobalSlots between reorgs**
  (`changesSinceReorg`, `:719`); under sustained spam, valid txs can get
  `ErrTxPoolOverflow` even when nominally better, until the next reorg resets the
  counter.
- **Blob eviction priority is float-based** (`log1.125`/`log1.17` fee jumps,
  `priority.go`); EIP-7892 (BPO) already forced a second constant (`log1_17`) for
  blob fees. It's fast (~8ns) but the float space is an approximation of time —
  not an exact ordering, and a moving target as blob-fee mechanics evolve.
