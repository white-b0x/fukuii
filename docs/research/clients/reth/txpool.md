# reth — txpool
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

Scope: `crates/transaction-pool/` — mempool admission, ordering, replacement,
eviction, and gossip for reth's execution client. The defining trait of this crate
is that it is **generic over three pluggable type parameters** — a `TransactionValidator`
(admission), a `TransactionOrdering` (priority), and a `BlobStore` (sidecar storage) —
so a downstream chain plugs its own policy at compile time without forking the pool.
This is the compile-time-pluggable analog of nethermind's `IIncomingTxFilter` chain and
besu's `TransactionSelector`/plugin API, and it maps almost one-to-one onto fukuii's
banksy client-policy tier.

## Architecture summary

The pool is a single generic struct `PoolInner<V, T, S>` (validator / ordering / blob
store) wrapped in an `Arc` and exposed through the `TransactionPool` trait. Three
orthogonal seams are the whole design:

1. **Admission seam** — `V: TransactionValidator` decides *whether* a transaction may
   enter (`validate_transaction` → `TransactionValidationOutcome::{Valid,Invalid,Error}`).
   The Ethereum implementation is `EthTransactionValidator`, built via a fluent
   `EthTransactionValidatorBuilder`. Validation runs on a dedicated task pool
   (`TransactionValidationTaskExecutor`) so the async admission path never blocks the
   caller.
2. **Ordering seam** — `T: TransactionOrdering` computes a per-transaction `Priority`
   score, given the current `base_fee`. The default is `CoinbaseTipOrdering`, whose
   priority is simply `effective_tip_per_gas(base_fee)`.
3. **Blob storage seam** — `S: BlobStore` holds EIP-4844 sidecars out-of-band (disk,
   memory, or noop), keeping the sidecar (large) separate from the pooled transaction
   record (small).

Internally, once admitted, a transaction lives in `TxPool<T>`, which partitions all
transactions into **four sub-pools** derived from an 8-bit `TxState` bitflag:

- **Pending** (`PendingPool`) — no nonce gaps, funded, fee-cap satisfies the pending
  block's base fee. Ready to mine. This is the sub-pool block production reads from,
  best-first, via the `BestTransactions` iterator (which applies the `TransactionOrdering`).
- **BaseFee** (`ParkedPool<BasefeeOrd>`) — otherwise-ready txs whose `maxFeePerGas` is
  currently below the block base fee; promotable to Pending if base fee drops.
- **Queued** (`ParkedPool<QueuedOrd>`) — parked on a nonce gap, insufficient balance, or
  a parked ancestor.
- **Blob** (`BlobTransactions`) — non-pending EIP-4844 txs (base-fee and/or blob-fee not
  yet satisfied). Blob txs are mutually exclusive with normal txs per sender.

A maintenance task (`maintain.rs`) keeps the pool in sync with the canonical chain:
on a new head it applies state-nonce/balance changes, re-derives each tx's `TxState`,
and promotes/demotes/evicts across sub-pools accordingly. The sub-pool a tx belongs to
is a pure function of its `TxState` bits (`impl From<TxState> for SubPool`), which the
comment explicitly notes mirrors erigon's ephemeral-state-field design.

## Key types / interfaces / files

- `traits.rs:114` — `pub trait TransactionPool` — the general-purpose public abstraction
  (RPC injects unverified txs; block production pulls executable txs). `#[auto_impl(&, Arc)]`
  so `Arc<Pool>` is itself a `TransactionPool`. `type Transaction: EthPoolTransaction`.
- `traits.rs:1318` — `pub trait PoolTransaction` — the pooled-transaction abstraction
  (`Consensus` vs `Pooled` representations, cost/tip/nonce accessors).
- `traits.rs:1459` — `pub trait EthPoolTransaction: PoolTransaction` — Ethereum-family
  extension (blob/7702 accessors). The concrete pool type parameter is bounded on this.
- `traits.rs:976` — `pub enum TransactionOrigin { Local, External, Private }` — drives the
  local-exemption policy in the validator.
- `traits.rs:399` / `traits.rs:407` — `best_transactions` / `best_transactions_with_attributes`
  — the block-production read side; the `_with_attributes` form filters by a target
  `base_fee`/`blob_fee` (`BestTransactionsAttributes`).
- `validate/mod.rs:170` — `pub trait TransactionValidator` — **the admission seam.**
  `validate_transaction(origin, tx) -> impl Future<Output = TransactionValidationOutcome>`;
  `on_new_head_block(&self, block)` lets the validator update fork-activation state.
  An `Either<A, B>` impl (`validate/mod.rs:242`) lets two validators be composed at runtime.
- `validate/mod.rs:28` — `pub enum TransactionValidationOutcome` — `Valid { balance,
  state_nonce, transaction, propagate, authorities, .. }` / `Invalid(tx, err)` / `Error`.
  Note `propagate: bool` — the admission decision *also* decides gossip eligibility.
- `validate/mod.rs:286` — `pub struct ValidPoolTransaction` — the pool's internal record.
  `is_underpriced` (`validate/mod.rs:453`) holds the **replacement** rule: a same-nonce
  replacement must beat the existing fee(s) by a configurable `PriceBumpConfig` percentage
  (separate bump for blob txs), enforced across `max_fee_per_gas`, `max_priority_fee_per_gas`,
  and `max_fee_per_blob_gas`.
- `validate/eth.rs:79` — `pub struct EthTransactionValidator` — the reference admission
  impl. `validate/eth.rs:957` — `impl TransactionValidator for EthTransactionValidator`.
- `validate/eth.rs:997` — `pub struct EthTransactionValidatorBuilder` — fluent construction
  (chain id, fork activations, `minimum_priority_fee`, `tx_fee_cap`, `max_tx_input_bytes`,
  `local_transactions_config`, blob params).
- `validate/eth.rs:429` — `validate_one_with_state_provider` — the actual gate sequence
  (size, init-code, gas cap, tip-above-fee-cap, local fee cap, **minimum priority fee**,
  chain id, 7702/4844 fork gating, intrinsic gas, balance/nonce).
- `validate/eth.rs:563` — **the `minimum_priority_fee` admission floor** (see below).
- `validate/eth.rs:1392` — `pub struct ForkTracker` — atomics (`shanghai/cancun/prague/osaka`,
  `max_blob_count`, `tip_timestamp`, `max_initcode_size`, `tx_gas_limit_cap`) refreshed in
  `on_new_head_block` (`validate/eth.rs:880`). Timestamp-fork state, ETH/PoS-shaped.
- `validate/task.rs` — `TransactionValidationTaskExecutor` / `ValidationTask` — off-thread
  validation execution.
- `ordering.rs:44` — `pub trait TransactionOrdering` — **the ordering seam.**
  `type PriorityValue: Ord`; `fn priority(&self, tx, base_fee) -> Priority<PriorityValue>`.
- `ordering.rs:67` — `pub struct CoinbaseTipOrdering` — default ordering;
  `priority = tx.effective_tip_per_gas(base_fee)` (`ordering.rs:84`), citing go-ethereum's
  legacypool. `ordering.rs:8` — `pub enum Priority<T> { Value(T), None }`, where `None`
  (e.g. missing base fee) always sorts lowest.
- `pool/txpool.rs:93` — `pub struct TxPool<T: TransactionOrdering>` — holds the four
  sub-pools + `AllTransactions`. `add_transaction` (`txpool.rs:736`), `insert_tx`
  (`txpool.rs:1974`), `discard_worst` (eviction, `txpool.rs:1232`),
  `on_canonical_state_change` (`txpool.rs:661`).
- `pool/state.rs:16` — `pub(crate) struct TxState: u8` (bitflags) and
  `pub enum SubPool` (`pool/state.rs:146`) with `From<TxState>` (`pool/state.rs:192`) —
  the sub-pool-derivation core.
- `pool/pending.rs` — `PendingPool` (`best()` builds the ordered iterator);
  `pool/parked.rs` — `ParkedPool` with `BasefeeOrd`/`QueuedOrd` orderings;
  `pool/blob.rs` — `BlobTransactions`; `pool/best.rs:91` — `BestTransactions` iterator
  that yields pending txs highest-priority-first while respecting per-sender nonce order.
- `pool/mod.rs:140` — `pub struct PoolInner<V, T, S>` — the composition point:
  `validator: V`, `pool: RwLock<TxPool<T>>`, `blob_store: S`. `new` at `pool/mod.rs:177`.
  The public `Pool` is the `Arc<PoolInner>` wrapper implementing `TransactionPool`.
- `blobstore/mod.rs` — `pub trait BlobStore`; `blobstore/disk.rs:32` —
  `DiskFileBlobStore` (disk-backed, in-memory LRU cache, **deferred deletion**);
  `blobstore/mem.rs`, `blobstore/noop.rs` — alternative impls.
- `config.rs` — `PoolConfig`, `LocalTransactionConfig`, `PriceBumpConfig`, per-sub-pool
  size limits (`SubPoolLimit`), `TXPOOL_MAX_ACCOUNT_SLOTS_PER_SENDER`.

## Design decisions & rationale

- **Three compile-time seams, not runtime config.** Admission (`TransactionValidator`),
  ordering (`TransactionOrdering`), and blob storage (`BlobStore`) are generic type
  parameters on `PoolInner<V, T, S>`, resolved when the node is built. A different chain
  (or an L2, or an enterprise deployment) supplies its own validator/ordering impls and
  gets a monomorphized, zero-dispatch pool. Contrast besu (runtime plugin registry) and
  nethermind (runtime filter list) — reth trades runtime swappability for compile-time
  guarantees and no vtable cost on the hot path.
- **State-as-bitflags → sub-pool as a pure function.** A transaction's sub-pool is never
  set imperatively; it is *derived* from its `TxState` (`From<TxState> for SubPool`).
  When chain state changes (new base fee, new nonce, new balance), only the bits are
  recomputed and the tx flows to the correct sub-pool. This keeps the four-way partition
  consistent by construction and is explicitly modeled on erigon's design.
- **BaseFee vs Queued split.** Post-EIP-1559 a tx can become executable or not without any
  sender action — purely because the block base fee moved. Separating "parked because of
  the sender" (Queued: nonce gap / balance) from "parked because of the fee market"
  (BaseFee) lets base-fee promotions/demotions be a cheap bulk re-sort rather than a
  per-tx re-validation.
- **Admission decides propagation.** `TransactionValidationOutcome::Valid.propagate` folds
  the gossip-eligibility decision into admission — e.g. local/private-origin txs may be
  admitted but not broadcast (`local_transactions_config.propagate_local_transactions`).
- **Local-origin exemption from the fee floor.** The `minimum_priority_fee` and
  fee-market drops apply only to non-local txs; locally submitted txs bypass the floor
  (but are still subject to a `tx_fee_cap` sanity ceiling). This preserves operator/user
  intent while rate-limiting spam from the network.
- **Off-thread validation.** `TransactionValidationTaskExecutor` runs the (state-touching,
  potentially expensive) validation on a task pool so RPC/P2P ingestion stays responsive.
- **Blob sidecars stored separately, deleted lazily.** `DiskFileBlobStore` uses deferred
  deletion (sidecars aren't removed immediately on inclusion/eviction) because reorgs can
  re-inject a blob tx without its sidecar; keeping it around avoids re-fetching.

## Notable patterns (the reusable idea)

**The single most transferable pattern for fukuii's banksy: a `TransactionOrdering`
trait whose `priority(tx, base_fee)` return value is the one place a network-specific
economic policy plugs in.** reth's `CoinbaseTipOrdering` returns
`effective_tip_per_gas(base_fee)` — but any chain can supply a different impl that, for
example, floors or weights the tip. ECIP-1122's `MIN_MINER_TIP` is exactly this shape:
in reth it would live in *two* cooperating places, and banksy should mirror both:

1. **Admission floor** — `EthTransactionValidator`'s `minimum_priority_fee` gate
   (`validate/eth.rs:563`): non-local dynamic-fee txs whose `max_priority_fee_per_gas`
   is below the configured minimum are rejected at the door with
   `PriorityFeeBelowMinimum`. This is the direct structural analog of a
   `MIN_MINER_TIP` admission gate — an operator-tunable, non-consensus, non-state-root
   knob, precisely banksy's remit.
2. **Selection ordering** — `TransactionOrdering::priority` decides block-production
   ordering among admitted txs. A `MIN_MINER_TIP` that must be *enforced at selection*
   (not just admission) plugs here.

The second reusable idea is the **bitflag `TxState` → derived `SubPool`** partition:
representing "why is this tx not minable right now" as independent boolean bits, then
deriving placement as a pure function, makes the pending/parked/base-fee/blob split
self-consistent under state churn. If fukuii ever grows a first-class parked/pending
split, this is the model to copy.

The third: **the replacement rule as a self-contained predicate** —
`ValidPoolTransaction::is_underpriced(replacement, &PriceBumpConfig)`
(`validate/mod.rs:453`) — a single function that encodes the fee-bump policy for
same-nonce replacement across gas, priority, and blob fees, configurable per tx type.

## Authority note

go-ethereum is the canonical reference for *txpool behavior* (mempool semantics,
replacement rules, sub-pool promotion) — reth even cites go-ethereum's legacypool at
the point where it defines `CoinbaseTipOrdering::priority`. reth is **not** an authority
on what the mempool policy *should be*; it is the reference for **how to structure the
pool as trait-abstracted seams** — the `TransactionValidator` / `TransactionOrdering` /
`BlobStore` decomposition that maps directly onto fukuii's banksy tier (admission gates,
tip/gas floors, selection ordering). For ETC-specific admission economics
(ECIP-1122 `MIN_MINER_TIP`, ECIP-1100/MESS), core-geth remains the behavioral authority;
reth supplies the seam shape into which banksy hangs that behavior.

## Gotchas / anti-patterns / things they later changed

- **ForkTracker is timestamp-fork / ETH-shaped.** `EthTransactionValidator` tracks
  Shanghai/Cancun/Prague/Osaka activation by timestamp via atomics refreshed on each new
  head. This is the PoS/ETH dispatch model; ETC/PoW uses block-number fork dispatch, so a
  fukuii PoW validator would not reuse `ForkTracker` as-is — the *seam* (a validator that
  updates fork state on new heads) transfers, the *timestamp mechanism* does not.
- **`CoinbaseTipOrdering::priority` is documented as incomplete for a missing base fee**
  (`ordering.rs:78` "NOTE: The implementation is incomplete for missing base fee"). The
  `Priority::None` variant exists specifically so a tx with an uncomputable tip sorts
  lowest rather than panicking — a downstream ordering impl must decide this deliberately.
- **Sub-pool membership must never be set directly.** Because `SubPool` is derived from
  `TxState`, any code path that moves a tx between sub-pools without going through the
  bit-recompute (`on_canonical_state_change` / `insert_tx`) will corrupt the partition.
- **Blob deferred deletion means the blob store can hold stale sidecars.** Cleanup is
  driven by a `tracker` (`blobstore/tracker.rs`) tied to finalization, not by pool
  eviction — sizing/monitoring the blob store is a separate concern from pool size limits.
- **Replacement vs new-insert both surface as `InsertErr::Underpriced`** (`txpool.rs:2058`)
  which maps to `PoolErrorKind::ReplacementUnderpriced` (`txpool.rs:822`); a caller can't
  tell "your replacement didn't bump enough" from generic underpricing without inspecting
  the error kind.
- **Eviction (`discard_worst`, `txpool.rs:1232`) drops the globally worst tx across
  sub-pools when limits are exceeded**, using each parked pool's own ordering
  (`BasefeeOrd`/`QueuedOrd`) — so eviction policy is *also* implicitly pluggable via those
  parked-pool orderings, not just the main `TransactionOrdering`.
