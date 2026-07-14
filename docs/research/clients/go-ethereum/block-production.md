# go-ethereum — block-production
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

Scope: the block **assembly / payload-building / sealing** side of the node — how geth
takes a parent header + the txpool and produces a candidate block. This is the mirror image
of block-execution (`block-execution.md`, validate+apply): block-production *creates* a block,
block-execution *replays* one. The two share the same core primitives (`core.ApplyTransaction`,
`state.StateDB`, `core.GasPool`, `consensus.Engine.Finalize`) but drive them in opposite
directions. This slot is HIGH VALUE for fukuii because block production **is** the
mining-pool / validator use case.

## Architecture summary

Post-merge geth no longer "mines" in the classic sense. Instead it is a **payload factory
driven by the consensus layer (CL)** over the Engine API. The flow:

1. **CL calls `engine_forkchoiceUpdated`** with `payloadAttributes` set
   (`eth/catalyst/api.go:380`). This is the *request to build a block on demand*.
2. The catalyst layer packs those attributes into a `miner.BuildPayloadArgs` and calls
   `Miner.BuildPayload` (`eth/catalyst/api.go:381,397`). It stashes the returned `*Payload`
   handle in `localBlocks` keyed by an 8-byte `PayloadID`.
3. `buildPayload` (`miner/payload_building.go:236`) **immediately builds an empty block**
   (`noTxs: true`) so there is always *something* to deliver, then spins up a **background
   goroutine** that repeatedly re-builds a *fuller, higher-fee* block every `Recommit`
   interval (default 2s), replacing the stored block whenever the new one pays more.
4. Later the **CL calls `engine_getPayload`** (`eth/catalyst/api.go:518`), which calls
   `Payload.Resolve()` (`miner/payload_building.go:148`). Resolve **stops the background
   loop** and returns the best block built so far as an `ExecutionPayloadEnvelope`.
5. The CL (not geth) proposes/attests the payload. **geth never seals post-merge** — the
   PoW `Seal` hook is a hard `panic` (`consensus/ethash/ethash.go:76`).

The single-block build itself is `generateWork` (`miner/worker.go:136`): prepare header →
open a state `environment` → **fill transactions from the txpool by descending effective tip**
→ finalize (consensus post-tx changes) → assemble. `Miner.Pending()` reuses the exact same
machinery to answer `eth_getBlockByNumber("pending")` RPC (`miner/miner.go:96`, `getPending`
at `:145`).

## Key types / interfaces / files

- `miner/miner.go:69` — `Miner` struct: holds `config`, `chainConfig`, `engine`
  (`consensus.Engine`), `txpool`, `chain` (`*core.BlockChain`), `prio` (priority senders),
  and a cached `pending`. The whole subsystem is ~1,700 lines across 4 files.
- `miner/miner.go:39` — `Backend` interface: the miner only needs `BlockChain()` + `TxPool()`
  from the node. Deliberately tiny seam.
- `miner/miner.go:44` — `Config`: `GasCeil` (target gas ceiling, default 60M `:57`),
  `GasPrice` (minimum tip for inclusion), `Recommit` (re-build interval, default 2s `:64`),
  `ExtraData`, `MaxBlobsPerBlock`, `PendingFeeRecipient`.
- `miner/miner.go:138` / `miner/payload_building.go:236` — `BuildPayload` / `buildPayload`:
  the on-demand payload entry point. **This is the getWork/payload seam for fukuii.**
- `miner/payload_building.go:42` — `BuildPayloadArgs`: `Parent`, `Timestamp`, `FeeRecipient`,
  `Random` (beacon randomness → `MixDigest`), `Withdrawals`, `BeaconRoot`, `SlotNum`,
  `Version`. `Id()` (`:54`) SHA-256-hashes these into an 8-byte `PayloadID` (first byte =
  version) — the CL's handle for later `getPayload`.
- `miner/payload_building.go:78` — `Payload`: wraps `empty` + `full` blocks behind a mutex
  and a `sync.Cond`. `update` (`:109`) swaps in a new full block **only if it pays higher
  fees** (`r.fees.Cmp(payload.fullFees) > 0`). `Resolve` (`:148`) / `ResolveFull` (`:189`) /
  `ResolveEmpty` (`:175`) close the `stop` channel and hand back an
  `engine.ExecutionPayloadEnvelope`.
- `miner/worker.go:136` — `generateWork`: builds one complete block end-to-end. The heart of
  block assembly.
- `miner/worker.go:246` — `prepareWork`: constructs the sealing header (parent, number,
  `CalcGasLimit`, timestamp, coinbase, extra, `MixDigest`, EIP-1559 base fee `:291`, EIP-4844
  blob-gas fields `:304`, EIP-7843 slot `:314`), calls `engine.Prepare`, runs pre-execution
  system calls, returns an empty `environment` (no txs yet).
- `miner/worker.go:62` — `environment`: the mutable build state — `state *StateDB`, `gasPool`,
  `header`, growing `txs`/`receipts`/`sidecars`, `tcount`, `size`, `evm`, block-access-list.
  `discard()` (`:87`) stops the prefetcher and releases the EVM.
- `miner/worker.go:543` — `fillTransactions`: pulls pending txs from the pool
  (`txpool.Pending(filter)`), splits into **priority** vs **normal** senders (`prio`),
  builds price-ordered iterators, and commits priority first then normal.
- `miner/worker.go:426` — `commitTransactions`: the **fill loop** — repeatedly peeks the
  best (plain vs blob) tx by tip, checks gas/blob/size budget, and commits until the pool is
  empty, gas runs out, block-size cap is hit, or an **interrupt** fires.
- `miner/worker.go:363` / `:411` — `commitTransaction` / `applyTransaction`: apply one tx via
  `core.ApplyTransaction`, snapshotting state + gas pool so a failed tx reverts cleanly.
- `core/txpool/txorder/ordering.go:89` — `TransactionsByPriceAndNonce`: the profit-maximizing
  tx iterator. A **price heap of per-account head txs**; `Peek`/`Shift`/`Pop` (`:130`,`:138`,
  `:153`) yield the globally-highest-tip next tx while honoring per-account nonce order.
  Ordering key = effective miner tip (`min(GasTipCap, GasFeeCap - baseFee)`, `:39`), ties
  broken by first-seen time (`:62`) for determinism.
- `miner/pending.go:32` — `pending`: TTL-cached (2s, `:29`) pending-block result so repeated
  `eth_getBlockByNumber("pending")` RPCs don't rebuild.
- `consensus/consensus.go:79-98` — the `Engine` production hooks: `Prepare` (init header
  consensus fields), `Finalize` (post-tx state changes, e.g. block reward), `Seal` (PoW
  sealing — push a sealed block into a channel), `SealHash`.
- `consensus/ethash/ethash.go:76` — `Ethash.Seal`: **`panic("ethash (pow) sealing not
  supported any more")`** — mainline geth deleted in-node PoW sealing.
- `eth/catalyst/api.go:380` — `forkchoiceUpdated` → the CL-driven build trigger.

## Design decisions & rationale

- **Build-on-demand, not a continuous mining loop.** Pre-merge geth ran a long-lived `worker`
  goroutine that continuously re-sealed on new heads/txs. Post-merge that is gone: a block is
  built only when the CL asks (`forkchoiceUpdated` w/ attributes). Rationale: the CL owns slot
  timing; the EL is a stateless payload service.
- **Empty-block-first.** `buildPayload` builds a `noTxs` block synchronously before returning
  (`payload_building.go:259`) so that even if tx-filling is slow or fails, the proposer always
  has a valid block to deliver and won't miss its slot (`:246-247` comment).
- **Keep building a better block until asked.** The background goroutine
  (`payload_building.go:268`) re-runs `generateWork` every `Recommit` and only accepts the
  result if it pays **strictly higher fees** (`:121`). Post-merge there are no uncle rewards,
  so total tx fees are the sole revenue metric. It self-terminates after
  `SECONDS_PER_SLOT` (12s, `:286`) or when `Resolve` closes `stop`.
- **Profit-maximizing tx order, nonce-respecting.** `TransactionsByPriceAndNonce` orders by
  effective tip across accounts but never violates per-account nonce ordering — `Shift`
  advances to the same sender's next nonce, `Pop` drops the whole account (used when a tx is
  invalid so subsequent nonces are unreachable).
- **Priority senders.** `SetPrioAddresses` (`miner.go:116`) lets an operator front-load
  specific senders' txs before the normal fee-ordered set (`worker.go:584-610`) — a hook for
  local/MEV/sequencer priority.
- **Interruptible fill.** `commitTransactions` checks an `atomic.Int32` interrupt each
  iteration (`worker.go:433`) so a new head, a resubmit, or a `Recommit`-timeout can abort a
  build mid-flight without corrupting the block (`generateWork` arms a timeout timer at
  `:181`).
- **Snapshot-per-tx.** `applyTransaction` snapshots both the state DB and the gas pool
  (`worker.go:413`) before applying, reverting both on failure — production must tolerate txs
  that fail at execution time (unlike execution of an already-validated block).
- **Fork-schedule-driven header shape.** `prepareWork` layers in EIP-1559 base fee, EIP-4844
  blob-gas fields, EIP-4788 beacon root, EIP-7843 slot number by fork-activation checks — the
  same fork-dispatch table used on the execution side, run forward.

## Notable patterns (the reusable idea)

**The single most transferable pattern for fukuii's block assembly: the "empty-block-first +
background-improve-until-resolve" payload handle.** A block-build request returns *immediately*
with a guaranteed-valid empty block, then a background worker keeps producing richer candidates
and atomically swaps in any that pays more, gated by a `stop` channel + `sync.Cond`; the
consumer calls `Resolve()` at slot time to freeze and collect the best-so-far. This cleanly
decouples *"give me a block right now"* from *"give me the most profitable block you can by
the deadline"* — exactly the tension a mining pool / proposer faces.

Other reusable ideas:
- **Tiny `Backend` seam** (`BlockChain()` + `TxPool()`): the producer depends on almost
  nothing, making it embeddable/testable.
- **`TransactionsByPriceAndNonce` heap**: the canonical "global tip order without breaking
  per-account nonce order" data structure — directly portable as fukuii's inclusion-ordering
  policy (banksy's territory).
- **Interrupt-token fill loop**: pass an `atomic.Int32` signal into the long-running fill so
  head-changes/timeouts abort cleanly.
- **Production ≠ execution separation**: `generateWork` (assemble) and block-import (execute)
  share `ApplyTransaction`/`Finalize`/`StateDB` but are distinct entry points. Keeping them
  separate — rather than one "process block" path — is what lets the producer snapshot-revert
  failing txs and iterate on fee, while the importer stays strict/deterministic.

## Authority note

geth = **canonical ETH payload-building authority** (Engine-API on-demand build, EIP-1559 tip
ordering, blob/withdrawal/slot header fields, the empty-first/improve-until-resolve pattern).
It is authoritative for fukuii's **PoS/ETH-Sepolia** production path (`beacon` agent).

geth is **NOT** the authority for ETC PoW block production: mainline geth **deleted in-node PoW
sealing** (`Ethash.Seal` panics). For fukuii's ETC path, **core-geth is the block-assembly +
Ethash-seal authority** — it retains the pre-merge `worker`/`Seal` path, the `getWork`/
`submitWork` external-miner RPC seam, ECIP-1017 block-reward `Finalize`, and block-number fork
dispatch. fukuii's ETC production = *assemble a block with this same fill/order machinery, then
seal via Ethash through an external miner* (getWork/submitWork), which is core-geth's shape,
not geth's. Cross-ref: `block-execution.md` (the validate+apply mirror), the mining-protocol
topic `topics/mining-protocol-evm.md` (the external-miner/pool RPC seam), and banksy's
transaction-selection policy (the fill-order half of this slot).

## Gotchas / anti-patterns / things they later changed

- **`Ethash.Seal` panics** (`consensus/ethash/ethash.go:76`) — do not model fukuii's PoW
  sealing on mainline geth; it was removed. Use core-geth (or the beacon-wrapped `ethone` path
  for pre-merge, `consensus/beacon/consensus.go:381`).
- **Empty payload can be delivered.** If the background loop never beats the empty block (slow
  fill, timeout, no profitable txs), `Resolve` returns the *empty* block (`payload_building.go:165`).
  A proposer can legitimately propose an empty block — don't treat "0 txs" as a build failure.
- **`ResolveFull` can block forever.** Its doc warns: don't call `Resolve` concurrently, or the
  `cond.Wait()` for the first full block never wakes (`payload_building.go:187-203`).
- **12s hard cap is mainnet-slot-specific.** The background loop's `endTimer` is a literal
  `12 * time.Second` (`payload_building.go:286`), matching ETH `SECONDS_PER_SLOT`. A non-ETH
  network with a different slot/block time must not inherit this constant blindly.
- **Blob-gas cap is checked in the miner, not in execution.** `commitBlobTransaction` explicitly
  re-checks the per-block blob limit (`worker.go:386-393` "kind of ugly … isn't really a better
  place") because `core.ApplyTransaction` won't reject an over-limit blob tx. Producers carry
  extra validation the executor skips.
- **Timer/stop select race.** `buildPayload` had to add an inner `select` on `payload.stop`
  before running an iteration (`payload_building.go:307`) because when a build takes ~the full
  recommit interval, Go's `select` may pick the timer over `stop` at random and waste a
  `generateWork`. A subtle concurrency footgun worth copying the fix for.
- **`GasCeil` is a *target*, not a hard limit** post-1559 — `CalcGasLimit` (`worker.go:274`)
  eases toward it; and on the London-activation block the parent gas limit is scaled by the
  elasticity multiplier first (`:293`).
- **Pending block is synthetic.** `getPending` (`miner.go:145`) fabricates a slot number and
  uses zero randomness/beacon-root; it exists only to answer RPC and is TTL-cached 2s
  (`pending.go:29`). Don't confuse it with a real proposal payload.
