# reth — block-production
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth is **PoS-native — there is no PoW sealing/mining production path**. Block
production is driven entirely by the Engine API: the CL calls
`forkchoiceUpdated` with `PayloadAttributes`, which spawns a *payload job* that
builds a block and keeps improving it until the CL calls `getPayload`.

The production side is layered into three concerns, each a trait so a network
family can swap its own implementation at compile time (the same
`NodeTypes`-style compile-time-generic pluggability reth uses everywhere):

1. **`PayloadBuilder`** (`crates/payload/basic/src/lib.rs:956`) — the pure,
   stateless "given a parent + attributes + best-payload-so-far, build one
   block" function. `EthereumPayloadBuilder` is the concrete Eth impl; an
   Optimism node supplies `OpPayloadBuilder`, etc. This is the trait a new
   family implements.
2. **`PayloadJob` / `PayloadJobGenerator`** (`crates/payload/builder/src/traits.rs`)
   — the *lifecycle* abstraction. A job is a `Future` that repeatedly invokes
   the builder on an interval, holding the best payload built so far and
   returning it to the CL on demand. `BasicPayloadJob` /
   `BasicPayloadJobGenerator` are the reusable default impls, generic over any
   `PayloadBuilder`.
3. **`PayloadServiceBuilder` / `PayloadBuilderBuilder`** (node-builder wiring,
   `crates/node/builder/src/components/payload.rs`) — the node-assembly seam
   that constructs a family's builder and plugs it into the generic
   `BasicPayloadJobGenerator` + `PayloadBuilderService`.

The key division of labor: **the generic machinery (job lifecycle, deadline,
empty-block fallback, best-payload racing, semaphore-bounded task spawning)
lives once in `basic`/`builder`; only the per-block "fill from pool" logic is
network-specific** and lives in the family's `PayloadBuilder`.

## Key types / interfaces / files

- `crates/payload/builder/src/traits.rs:23` — `trait PayloadJob: Future` — the
  in-flight build. Must always be ready to hand back the best payload (or an
  empty block) so the validator never misses a slot; must be cancel-safe.
  `best_payload()` (never called by CL), `resolve_kind(PayloadKind)` (called on
  `getPayload`, must resolve in <1s).
- `crates/payload/builder/src/traits.rs:105` — `trait PayloadJobGenerator` —
  `new_payload_job(input, id)` creates a job on `forkchoiceUpdated`; the doc
  comment mandates it "initially build a new (empty) payload without
  transactions, so it can be returned directly." `on_new_state` feeds canonical
  chain updates for pre-caching.
- `crates/payload/basic/src/lib.rs:956` — `trait PayloadBuilder` — `try_build`
  (build a better block), `build_empty_payload` (transactionless fallback),
  `on_missing_payload` (what to do if CL asks before anything is built:
  `MissingPayloadBehaviour::{AwaitInProgress, RaceEmptyPayload, RacePayload}`).
- `crates/payload/basic/src/lib.rs:365` — `struct BasicPayloadJob` — the
  reusable job impl. Its `Future::poll` (line 451) is the **empty-first +
  background-improve** loop: on each interval tick it `spawn_build_job()`s a new
  attempt on a blocking thread; `BuildOutcome::Better` replaces the best,
  `Aborted` (worse fees) is discarded, `Freeze` stops further building.
- `crates/payload/basic/src/lib.rs:56` — `struct BasicPayloadJobGenerator` —
  generic over `<Client, Builder>`; `new_payload_job` (line 162) spawns the
  first build immediately (`job.spawn_build_job()` at line 208).
- `crates/payload/basic/src/lib.rs:1026` — `fn is_better_payload` — the
  selection criterion between attempts: strictly higher total fees wins.
- `crates/payload/basic/src/lib.rs:800` — `enum BuildOutcome`
  (`Better`/`Aborted`/`Cancelled`/`Freeze`) and `:619` `enum PayloadState`
  (`Missing`/`Best`/`Frozen`).
- `crates/payload/primitives/src/traits.rs:71` — `trait PayloadAttributes` —
  the CL-supplied build inputs (timestamp, withdrawals,
  parent_beacon_block_root, slot_number, target_gas_limit). `:134`
  `PayloadAttributesBuilder` — factory used by `--dev` to synthesize attributes
  locally. `:36` `trait BuiltPayload` — the produced block + fees + requests.
- `crates/ethereum/payload/src/lib.rs:57` — `struct EthereumPayloadBuilder` —
  the concrete Eth builder, generic `<Pool, Client, EvmConfig>`.
- `crates/ethereum/payload/src/lib.rs:147` — `fn default_ethereum_payload` — the
  actual block-fill routine: opens state at the parent, builds the next-block
  EVM env from attributes, then drains `best_txs` from the pool.
- `crates/ethereum/payload/src/lib.rs:100` — tx-selection entry:
  `self.pool.best_transactions_with_attributes(attributes)` producing a
  `BestTransactions` iterator (`:51` `BestTransactionsIter`). The build loop
  (`:258`) calls `best_txs.next()`, and on a tx that doesn't fit calls
  `best_txs.mark_invalid(...)` (lines 282, 306, 328) so the pool iterator drops
  that sender's dependent txs.
- `crates/node/builder/src/components/payload.rs:50` — `trait
  PayloadBuilderBuilder` — the node-assembly hook: `build_payload_builder(ctx,
  pool, evm_config) -> Self::PayloadBuilder`. `:69` `BasicPayloadServiceBuilder`
  wraps it and (line 124) constructs the generic
  `BasicPayloadJobGenerator::with_builder` + `PayloadBuilderService`.
- `crates/engine/local/src/miner.rs:26` — `enum MiningMode` — the `--dev`
  auto-seal driver: `Instant { max_transactions }` (mine when a tx hits the
  pool, or when N accumulate), `Interval(...)` (fixed cadence), `Trigger(...)`
  (on-demand stream). Constructed at `crates/node/core/src/node_config.rs:604`
  from `--dev.block-time` / `--dev.block-max-transactions`.
- `crates/engine/local/src/miner.rs:131` — `struct LocalMiner` — drives the
  local engine: on each `MiningMode` fire, synthesizes attributes via
  `LocalPayloadAttributesBuilder` (`crates/engine/local/src/payload.rs:15`) and
  issues `forkchoiceUpdated` → `getPayload` → `newPayload` itself, standing in
  for the CL. Cross-ref: `consensus-pow-cpu` topic.

## Design decisions & rationale

- **Never miss a slot.** The entire design is shaped by the Engine API's hard
  1s `getPayload` deadline and 12s slot. The generator builds an empty block
  *first and immediately* (`new_payload_job` spawns before returning), so there
  is always a deliverable block even if no full build has finished. `PayloadJob`
  is required to be cancel-safe because the CL may drop the request mid-build.
- **Iteratively improve, keep the best.** A payload job doesn't build once — it
  re-runs `try_build` every `interval` (default 1s) until the slot deadline
  (default `SLOT_DURATION` = 12s), each attempt racing to produce higher total
  fees (`is_better_payload`). This is the MEV-adjacent "keep improving until
  asked" model. `BuildOutcome::Freeze` lets a builder declare a payload final
  and stop the loop.
- **Stateless builder vs stateful job.** `PayloadBuilder::try_build` is pure and
  cheap to call repeatedly; all the messy lifecycle state (best-so-far,
  deadline, cached reads, in-flight task) lives in the reusable `BasicPayloadJob`.
  A new family only writes the pure part.
- **Bounded, off-runtime execution.** Builds run on a dedicated
  `spawn_blocking` thread pool named `payload-builder`, gated by a
  `PayloadTaskGuard` semaphore (`max_payload_tasks`, default 3) so payload
  building can't starve the async runtime.
- **Read caching across attempts.** Because each attempt re-executes the same
  txs, `CachedReads` (and an optional engine-shared `SavedCache` /
  `StateRootHandle`) are threaded through so repeated builds reuse disk reads;
  `on_new_state` pre-caches the tip's changed accounts.
- **tx-selection is delegated to the pool.** The builder doesn't sort — it asks
  the pool for a `BestTransactions` iterator (priced by base fee + blob gas
  price via `BestTransactionsAttributes`) and just consumes it, using
  `mark_invalid` to prune dependents when a tx can't fit (gas, blob count,
  Osaka RLP size). Ordering policy lives in the pool, not the builder.

## Notable patterns (the reusable idea)

**Split "how to build one block" (family-specific, stateless trait) from "how to
run the build job" (generic, reusable Future).** reth encodes payload production
as a two-trait stack:

- `PayloadBuilder` — the *only* thing a network family implements: a pure
  `try_build(parent, attributes, best_so_far) -> BuildOutcome` plus an
  `build_empty_payload` fallback.
- `BasicPayloadJob` / `BasicPayloadJobGenerator` — written once, generic over
  any `PayloadBuilder`, owning the empty-first-then-background-improve loop, the
  slot deadline, the best-payload race on `getPayload`, cancel-safety, and
  bounded task spawning.

The block-fill loop itself never sorts transactions — it consumes the pool's
`BestTransactions` iterator and calls `mark_invalid` to drop dependents when a
tx won't fit. This is the same "empty-block-first + keep-improving-until-
getPayload" job geth uses, but expressed **trait-generic and compile-time
pluggable** rather than hard-wired: an Eth node plugs in `EthereumPayloadBuilder`,
an OP node plugs in its own builder, all through the `PayloadBuilderBuilder`
node-assembly seam — no runtime dispatch.

## Authority note

reth = the trait-pluggable `PayloadBuilder` + PoS payload-job reference (the
empty-first / background-improve / best-fees-win job lifecycle, cleanly split
from the per-family block-fill). **core-geth = the ETC PoW sealing/mining
authority — reth has no PoW block-production path at all** (its only "local"
production is the `--dev` `MiningMode` auto-seal, which still drives the Engine
API, not Ethash). For fukuii's ETC production side (Ethash sealing, ECIP-1017
reward emission, DAG/mining), core-geth is authoritative; reth is a reference
only for *how to shape the payload-building abstraction*, not for PoW semantics.

## Gotchas / anti-patterns / things they later changed

- **The empty-payload path is not an edge case — it's load-bearing.** If the CL
  requests before a full build finishes, `resolve_kind` races an empty block
  (`RaceEmptyPayload`) against the in-flight job. Getting this wrong = missed
  slots. `EthereumPayloadBuilder::on_missing_payload` (`lib.rs:104`) is
  configurable (`await_payload_on_missing`) precisely because the right choice
  is deployment-dependent.
- **`resolve` is not cancellation-safe** (`traits.rs:69`): dropping the returned
  `ResolvePayloadFuture` cancels payload resolution and can cancel the job.
  Callers must not race/drop it casually.
- **Interval-tick subtlety** (`lib.rs:460`, `:502`): the poll loop deliberately
  waits for an in-flight build to finish before consuming the next interval
  tick, and loops after spawning because `poll_tick` doesn't register a waker on
  `Ready` — a naive rewrite silently stalls building. There's an explicit
  comment warning about this.
- **`build_empty_payload` still runs the full builder** with an empty tx
  iterator (`lib.rs:134`) rather than a separate code path — this keeps
  pre/post-execution changes (withdrawals, beacon-root, requests) correct for
  the empty block instead of duplicating that logic.
- **tx-selection correctness depends on `mark_invalid` semantics.** When a tx is
  skipped for gas/blob/size, you must `mark_invalid` (not just `continue`) or
  the pool iterator will keep yielding that sender's now-invalid dependent txs.
- **Not vendored here:** the Optimism `crates/optimism/payload` builder isn't
  present in this checkout, so the "per-family builder" claim is evidenced by
  the trait shape (`PayloadBuilderBuilder`, generic `BasicPayloadJobGenerator`)
  rather than a second concrete impl in-tree.
