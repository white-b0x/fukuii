# besu — block-production
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

## Architecture summary

Besu factors block production into three orthogonal layers, and this separation is
the whole lesson for fukuii:

1. **`MiningCoordinator`** — the pluggable *lifecycle/orchestration* interface. One
   implementation per consensus family decides *when* and *how* a block gets
   produced and imported. On the `upstream` (post-ETC-removal) mirror the live
   implementations are `MergeCoordinator` (PoS payload building via the Engine API)
   and `BftMiningCoordinator` (IBFT2/QBFT proposer), plus `NoopMiningCoordinator`
   for non-mining nodes. PoW production (`PoWMiningCoordinator`, `PoWBlockCreator`)
   has been **removed** — confirmed absent from the tree.

2. **`BlockCreator` / `AbstractBlockCreator`** — the *consensus-agnostic assembly
   engine*. Given a parent header + timestamp it duplicates the parent world state,
   runs pre-execution system calls, drives transaction selection, processes
   withdrawals and EIP-7685 EL requests, pays the coinbase, computes all the roots,
   and builds a `SealableBlockHeader`. The single abstract hook
   `createFinalBlockHeader(SealableBlockHeader)` is what each consensus subclass
   overrides to "seal" — PoW nonce (historic), PoS zero-difficulty finalization, or
   BFT proposer-seal extraData. **All the byte-level block-assembly logic lives once
   in the abstract base**; consensus variance is a single method.

3. **`BlockTransactionSelector`** (+ `txselection/selectors/*`) — the production-side
   *tx selection* engine: a pipeline of composable selectors run against each
   candidate, with a hard wall-clock time budget, world-state commit/rollback per
   tx, and a plugin extension point (`PluginTransactionSelector`) so enterprise
   deployments can inject custom inclusion policy (MEV/censorship/compliance).

The flow for PoW/BFT single-shot mining: `BlockMiner` (a `Runnable`, one per block)
→ `AbstractBlockCreator.createBlock` → `BlockTransactionSelector` → import via
`BlockImporter`. For PoS: `MergeCoordinator.preparePayload` → async
`tryToBuildBetterBlock` loop → `MergeBlockCreator.createBlock` → block stashed by
`PayloadIdentifier` for later `engine_getPayload` retrieval.

## Key types / interfaces / files

- `ethereum/blockcreation/.../MiningCoordinator.java:25` — the pluggable per-consensus
  block-production interface: `start/stop/enable/disable/isMining`, `createBlock(...)`,
  `changeTargetGasLimit`, and `isCompatibleWithEngineApi()` (default false; the Merge
  coordinator overrides it). This is besu's central abstraction — every consensus
  family provides one implementation.
- `ethereum/blockcreation/.../BlockCreator.java:27` — the assembly contract; nested
  `BlockCreationResult` bundles the `Block` + `TransactionSelectionResults` + timings
  + optional `BlockAccessList` (EIP-7928) + optional EIP-7685 `Request`s.
- `ethereum/blockcreation/.../AbstractBlockCreator.java:77` — the consensus-agnostic
  assembly engine. `createBlock(...)` at `:188` is the ~185-line canonical pipeline;
  `createFinalBlockHeader` at `:526` is the sole abstract sealing hook;
  `rewardBeneficiary` at `:480`; `duplicateWorldStateAtParent` at `:444` (mines on a
  disposable clone, never the head). `cancel()`/`isCancelled()` thread the
  cancellation flag down into the active selector.
- `ethereum/blockcreation/.../BlockMiner.java:47` — `Runnable` that produces AND
  imports exactly one block (`mineBlock()` at `:131`); designed to be constructed
  fresh per block and cancelled safely. Notifies `MinedBlockObserver`s. The javadoc
  explicitly notes IBFT reuses the created block "as part of a proposal round."
- `ethereum/blockcreation/.../txselection/BlockTransactionSelector.java:108` — the
  production-side selector. `buildTransactionListForBlock()` at `:210` pulls from the
  pool; `evaluateTransactions(List)` at `:517` evaluates a fixed list (BFT/engine
  path). Two-phase time-boxed selection (`pluginTimeLimitedSelection` then
  `internalTimeLimitedSelection`) with per-tx `commit()`/`rollback()` of layered
  `WorldUpdater`s (`:586`/`:614`) and a deferred `PendingAction` queue so selector
  side effects only fire on commit.
- `ethereum/blockcreation/.../txselection/selectors/` — the composable selector
  pipeline, wired in `createTransactionSelectors` at `BlockTransactionSelector.java:185`:
  `SkipSenderTransactionSelector`, `BlockSizeTransactionSelector` (gas budget),
  `BlobSizeTransactionSelector`, `PriceTransactionSelector`,
  `BlobPriceTransactionSelector`, `MinPriorityFeePerGasTransactionSelector` (the
  min-priority-fee floor — cross-ref fukuii's ECIP-1122 tip floor / banksy), plus
  `BlockRlpSizeTransactionSelector`, `ProcessingResultTransactionSelector`,
  `BlockAccessListItemBudgetTransactionSelector`.
- `consensus/merge/.../blockcreation/MergeCoordinator.java:77` — PoS block production.
  `preparePayload(...)` at `:234` implements the Engine API `forkchoiceUpdated`→build
  contract: builds an **empty block immediately** (so `engine_getPayload` always has
  something), stashes it by `PayloadIdentifier`, then kicks off async
  `tryToBuildBetterBlock` (`:409`) → `retryBlockCreationUntilUseful` (`:475`) that
  keeps repacking a fuller block until the CL fetches it or timeout. `MergeMiningCoordinator`
  interface adds `preparePayload`, `finalizeProposalById`, `updateForkChoice`,
  `validateBlock`, etc.
- `consensus/merge/.../blockcreation/MergeBlockCreator.java` &
  `PayloadIdentifier.java` — the PoS `AbstractBlockCreator` subclass and the
  content-addressed id (hash of parent+timestamp+prevRandao+feeRecipient+withdrawals+…)
  that keys in-flight builds.
- `consensus/common/bft/blockcreation/BftMiningCoordinator.java:41` — the BFT
  `MiningCoordinator`. It does NOT create blocks on demand (`createBlock` returns
  `Optional.empty()` at `:232`/`:240`); instead it runs the `BftProcessor` event loop
  and feeds `NewChainHead` events from a `BlockAddedObserver` into the round state
  machine. Sync-aware: pauses mining while syncing (`subscribe()` at `:156`).
- `consensus/common/bft/blockcreation/BftBlockCreatorFactory.java:52` —
  `create(int round)` at `:112` yields a `BftBlockCreator` whose extraData encodes the
  round number + validator vote (`createExtraData` at `:159`). QBFT extends it
  (`QbftBlockCreatorFactory extends BftBlockCreatorFactory<QbftConfigOptions>`).
- `consensus/common/bft/blockcreation/BftProposerSelector.java:45` — decides *which*
  validator proposes for a given `(sequence, round)`: reads the previous block's
  proposer from the proposer-seal in extraData (via `BlockInterface`), then rotates
  through the sorted validator set by round offset (`changeEachBlock` toggles
  per-block rotation). Pure/static core (`selectProposerForRound` at `:116`).
- Round state machines invoke it: `IbftRound.java:129` /
  `QbftRound.java:144,163` call `blockCreator.createBlock(timestamp, parentHeader)`
  when the local node is the round's proposer; the factory is asked for a
  round-specific creator in `IbftRoundFactory`/`QbftRoundFactory` (`.create(roundNumber)`).

## Design decisions & rationale

- **One abstract assembly engine, one sealing hook.** `AbstractBlockCreator` owns the
  entire deterministic block-assembly sequence (state clone → pre-exec → tx select →
  withdrawals → EL requests → reward → roots → header). The *only* thing a consensus
  family customizes is `createFinalBlockHeader`. This guarantees PoW/PoS/BFT produce
  byte-identical bodies from identical inputs and keeps consensus-critical assembly
  from being copy-pasted per family.
- **Mine on a disposable world-state clone.** `duplicateWorldStateAtParent` produces a
  throwaway mutable state (`...NoUpdateNodeHead`), so a failed/cancelled build never
  corrupts canonical state — "let it crash" is safe here.
- **Time-boxed, cancellable tx selection.** Selection runs on the `EthScheduler` as a
  `FutureTask` with an explicit nanosecond budget (`getBlockTxsSelectionMaxTime`,
  distinct for PoS vs non-PoS) split between a plugin phase and an internal-pool phase;
  on timeout the in-flight tx gets a grace window then a forced interrupt, and late
  txs are penalized/removed from the pool. Rationale: a single pathological tx (or a
  slow plugin) must never blow the slot deadline.
- **Deferred commit via `PendingAction` queue.** A tx is evaluated speculatively
  against a nested `txWorldStateUpdater`; only `commit()` (guarded by the `isTimeout`
  monitor) applies it to the block updater and fires selector `onTransactionSelected`
  callbacks. This makes the "did we beat the clock?" decision atomic against the
  concurrent timeout thread.
- **PoS: empty-block-first, then improve.** `preparePayload` returns a valid empty
  payload synchronously and improves it asynchronously, so `engine_getPayload` is
  never left waiting — the CL can always fetch *something*. Builds are keyed and
  cancellable by `PayloadIdentifier`; a new FCU cancels stale builds.
- **BFT: proposer role is data-driven, not a separate coordinator per algorithm.**
  IBFT2 and QBFT share `BftMiningCoordinator` + `BftBlockCreatorFactory` +
  `BftProposerSelector`; QBFT only subclasses the factory. Proposer identity is
  derived deterministically from on-chain state (previous proposer seal + validator
  set), so every honest node computes the same proposer for a round without
  coordination.

## Notable patterns (the reusable idea)

**The `MiningCoordinator` seam is the transferable pattern.** Besu treats "how blocks
get produced" as a per-consensus strategy behind one narrow interface, layered over a
*shared* `AbstractBlockCreator` assembly engine whose only consensus-variant point is
a single `createFinalBlockHeader` hook, and a *shared* `BlockTransactionSelector` whose
inclusion policy is a composable list of selectors plus a plugin injection point. Adding
a new consensus family = implement `MiningCoordinator` + subclass `AbstractBlockCreator`
(override one method) — you never touch tx-selection or root computation.

Secondary reusable idea: **composable, time-budgeted transaction selectors with
commit/rollback semantics and a plugin seam** (`AbstractTransactionSelector` +
`PluginTransactionSelector`). Each concern (gas budget, blob budget, price floor,
min-priority-fee, RLP size, BAL budget) is an independent selector; enterprise policy
plugs in without forking the engine. This directly maps onto fukuii's banksy-layer
tip-floor / tx-ordering policy and its enterprise deployment story.

## Authority note

**besu is the reference authority for the BFT block-proposer (IBFT2/QBFT)** — the
proposer-selection rotation, round-based extraData encoding, and the
`MiningCoordinator`-per-consensus abstraction are besu's design and the model to mirror
for fukuii's private/consortium PoA block production. Cross-ref
`clients/topics/consensus-poa-and-etc-testnets.md`. besu is **not** the authority for
ETC PoW *sealing*: `PoWMiningCoordinator`/`PoWBlockCreator` were removed from this
mirror, and the canonical PoW/Ethash block-sealing + ECIP-1017 reward reference is
**core-geth** (see `clients/coregeth/`-family docs and fukuii's `forge` charter). For
the production-side tx-selection plugin seam cross-ref `clients/besu/txpool.md`
(`PluginTransactionSelector` / `TransactionSelectionService`).

## Gotchas / anti-patterns / things they later changed

- **`BftMiningCoordinator.createBlock(...)` is a no-op** returning `Optional.empty()`
  (`:232`/`:240`) — "one-off block creation has not been implemented." Do not assume the
  `MiningCoordinator.createBlock` API works for every implementation; BFT drives block
  creation exclusively through its round state machine, not the generic API.
- **`createEmptyWithdrawalsBlock` throws** in `AbstractBlockCreator` (`:167`,
  `UnsupportedOperationException("Only used by BFT block creators")`) — it is
  overridden only on the BFT path. A caller on the mainnet/PoS path hitting it is a bug.
- **`BlockTransactionSelector` is single-use** — it holds per-run mutable state and its
  class javadoc mandates "Once 'used' this class must be discarded and another created."
  `AbstractBlockCreator` re-news it per `createBlock`. Reusing one across blocks would
  cross-contaminate selection state.
- **`@SuppressWarnings("unchecked")` on the whole selector class** (`:107`) — the
  plugin `BlockTransactionSelectionService` generics are erased; a real typing wart in
  the plugin seam, not a modeling improvement to copy verbatim.
- **`rewardBeneficiary` carries a `TODO(tmm)` and a hard-coded `MAX_GENERATION = 6`**
  (`:489`) copied from `BlockProcessor` — reward logic is duplicated between assembly
  and processing rather than shared, a known smell.
- **Timeout handling runs in "best-effort mode" on grace-window expiry** — if the
  in-flight tx thread doesn't yield in time, block completion proceeds anyway and "could
  fail due to concurrency issues" (their words, `waitForCancellationToBeProcessed`).
  The world-state layering is what keeps this from corrupting canonical state.
- **No native MEV/builder market.** Besu has no in-tree MEV-boost/builder block source;
  external block building is expected to arrive via the `PluginTransactionSelector` /
  transaction-selection-service plugin seam, not a first-class builder API.
