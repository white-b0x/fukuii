# nethermind — block-execution
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind splits block execution into three cleanly separated layers, each behind
an interface, with a distinctive **queue-driven async front end**:

1. **`BlockchainProcessor`** (`IBlockchainProcessor`, `IBlockProcessingQueue`) — the
   async orchestrator. Blocks are *enqueued* (not processed inline) and drained by a
   dedicated high-priority processing thread off a bounded channel. It owns branch/reorg
   discovery, "should I process this?" head-comparison checks, invalid-block handling,
   main-chain update, and processing statistics. It does **not** touch the EVM or state.
2. **`BranchProcessor`** (`IBranchProcessor`) — processes one *branch* (an ordered list
   of blocks sharing a base state root). It owns the `IWorldState` scope lifecycle,
   cache prewarming, per-block commit batching for long branches, and the reorg-safe
   "restore at all cost" exception path. It delegates each block to the block processor.
3. **`BlockProcessor`** (`IBlockProcessor`) — the actual single-block execution pipeline:
   system-contract pre-calls → transactions → blooms/receipts root → **miner rewards** →
   withdrawals → execution requests → state root → hash. This is where the
   `IRewardCalculator` is invoked.

The pipeline is *stateless with respect to world-state preparation*: both `IBlockProcessor`
and `ITransactionProcessor` assume the `IWorldState` is already scoped to the parent by the
branch processor (`stateProvider.BeginScope(baseBlock)`).

### The queue-driven async design (distinctive)

`BlockchainProcessor` runs **two** background tasks fed by **two** `System.Threading.Channels`:

- A **recovery queue** (`_recoveryQueue`, *unbounded*, single-reader) — recovers tx sender
  addresses (`IBlockPreprocessorStep.RecoverData`, ECDSA ecrecover) off the hot path.
- A **processing queue** (`_blockQueue`, *bounded* at `MaxProcessingQueueSize = 2048`,
  single-reader, `AllowSynchronousContinuations = true`).

`Enqueue` is driven by `IBlockTree.NewBestSuggestedBlock`. A fast-path optimization: if the
queue is empty (`_queueCount <= 1`) the block skips the recovery queue and goes straight to
the processing queue so processing can continue *synchronously on the NewPayload thread and
inherit its priority* — important for PoS Engine-API latency. When backed up, blocks route
through recovery first. The processing thread sets itself to highest priority, toggles
background GC off while a block is in flight (`GCScheduler.SwitchOffBackgroundGC`), and marks
itself as *the* block-processing thread (`IsBlockProcessingThread`), which downstream code
reads to gate main-thread-only work (e.g. `SetAccountChanges`).

### The IRewardCalculator swap (per-consensus economics)

`BlockProcessor.ApplyMinerRewards` is consensus-agnostic — it just calls
`rewardCalculator.CalculateRewards(block)` and credits each returned `BlockReward` to its
address. **What that calculator *is* is decided entirely at the DI seam**, layered three ways:

- **Base default** (`BlockProcessingModule`): `IRewardCalculatorSource` = `NoBlockRewards.Instance`
  → returns `[]` (no reward). This is the PoS-safe default.
- **PoW plugin** (`EthashPlugin`): registers `IRewardCalculatorSource` = `RewardCalculator`
  → computes the fixed block reward (`spec.BlockReward`) plus uncle/nephew rewards
  (`blockReward >> 5` nephew bonus, `blockReward - (distance * blockReward >> 3)` uncle).
- **Merge plugin co-activation** (`MergePlugin`): *decorates* the underlying source with
  `MergeRewardCalculatorSource` → produces `MergeRewardCalculator`, which at **runtime, per
  block**, checks `_poSSwitcher.IsPostMerge(block.Header)` and dispatches to `NoBlockRewards`
  (post-merge) or the pre-merge Ethash calculator. This is how a single binary transitions
  PoW→PoS rewards across the merge boundary without a code branch in the processor.

## Key types / interfaces / files

- `Nethermind.Consensus/Processing/BlockchainProcessor.cs:33` — the queue-driven async
  processor; owns `_recoveryQueue`/`_blockQueue` channels, branch discovery
  (`PrepareProcessingBranch:695`), reorg walk-back, invalid-block deletion, main-chain update.
- `Nethermind.Consensus/Processing/BlockchainProcessor.cs:137` — `Enqueue`: the fast-path
  (skip recovery queue when idle) vs. recovery-queue routing logic.
- `Nethermind.Consensus/Processing/BlockchainProcessor.cs:333` — `RunProcessingLoop`: the
  drain loop, high-priority thread, background-GC toggling.
- `Nethermind.Consensus/Processing/BlockchainProcessor.cs:898` — `ProcessingBranch` ref
  struct: `(BaseBlock, Blocks, BlocksToProcess)` — the branch abstraction.
- `Nethermind.Consensus/Processing/IBlockchainProcessor.cs` — `Start`/`StopAsync`/`Process`
  + `InvalidBlock`/`NewProcessingStatistics` events.
- `Nethermind.Consensus/Processing/BranchProcessor.cs:47` — `Process`: world-state scope
  lifecycle, prewarming, per-block loop, commit batching (`MaxUncommittedBlocks = 64`),
  restore-on-exception.
- `Nethermind.Consensus/Processing/BlockProcessor.cs:72` — `ProcessOne`: the single-block
  entry (DAO transition → prepare header → `ProcessBlock` → validate → store receipts).
- `Nethermind.Consensus/Processing/BlockProcessor.cs:116` — `ProcessBlock`: the ordered
  execution pipeline (system calls → txs → blooms → receipts root → **rewards** →
  withdrawals → requests → state root → hash).
- `Nethermind.Consensus/Processing/BlockProcessor.cs:337` — `ApplyMinerRewards`: the
  consensus-agnostic reward application (loops `rewardCalculator.CalculateRewards`).
- `Nethermind.Consensus/Processing/BlockProcessor.SystemContractHandler.cs:23` —
  `SystemContractHandler` composes beacon-root (EIP-4788), blockhash (EIP-2935),
  withdrawals, and execution-requests handlers behind one `ISystemContractHandler`.
- `Nethermind.Consensus/Processing/BlockProcessor.BlockValidationTransactionsExecutor.cs:21`
  — `IBlockTransactionsExecutor` impl: the per-tx loop calling
  `ITransactionProcessorAdapter.ProcessTransaction`, gas-limit checks, receipt collection.
- `Nethermind.Evm/TransactionProcessing/TransactionProcessor.cs` +
  `SystemTransactionProcessor.cs` — tx-level execution (normal vs. system tx).
- `Nethermind.Consensus/Rewards/IRewardCalculator.cs` — the swap interface:
  `BlockReward[] CalculateRewards(Block block)`.
- `Nethermind.Consensus/Rewards/IRewardCalculatorSource.cs` — factory
  (`Get(ITransactionProcessor)`), the DI registration point per consensus.
- `Nethermind.Consensus/Rewards/RewardCalculator.cs:22` — PoW reward: `spec.BlockReward`
  + uncle/nephew math.
- `Nethermind.Consensus/Rewards/NoBlockRewards.cs:17` — PoS/default: returns `[]`.
- `Nethermind.Merge.Plugin/MergeRewardCalculator.cs:16` — per-block PoW↔PoS reward switch
  via `IPoSSwitcher.IsPostMerge`.
- `Nethermind.Merge.Plugin/MergeRewardCalculatorSource.cs:18` — the decorator source.
- `Nethermind.Consensus.Ethash/EthashPlugin.cs:39` — PoW registers `RewardCalculator`.
- `Nethermind.Init/Modules/BlockProcessingModule.cs:70,101` — base wiring:
  `IRewardCalculator` resolved from source+txProcessor; default source = `NoBlockRewards`.
- `Nethermind.Merge.Plugin/MergePlugin.cs:212` — `AddDecorator<IRewardCalculatorSource,
  MergeRewardCalculatorSource>()` — the merge co-activation seam.
- `Nethermind.Consensus/Processing/ProcessingOptions.cs:11` — flag set
  (`ReadOnlyChain`, `ForceProcessing`, `NoValidation`, `StoreReceipts`, `ProducingBlock`,
  `EthereumMerge`, …) threaded through the whole pipeline.

## Design decisions & rationale

- **Queue + dedicated thread, not inline processing.** Decoupling suggestion (from sync,
  P2P, or Engine-API `NewPayload`) from execution lets Nethermind (a) recover tx senders in
  parallel with execution, (b) pin the processing thread to highest priority and control GC
  around it, and (c) apply backpressure via the bounded channel. Throughput/latency win.
- **Reward strategy is DI-injected, never branched in the processor.** `ApplyMinerRewards`
  has zero knowledge of PoW vs PoS. Consensus economics live entirely in *which*
  `IRewardCalculatorSource` the active plugin registers, plus a runtime decorator for the
  merge transition. Adding a new consensus family = register a new calculator, touch no
  pipeline code.
- **Three-tier processor split.** `IBlockchainProcessor` (queue/reorg) vs `IBranchProcessor`
  (state scope / prewarm / commit batching) vs `IBlockProcessor` (single-block EVM pipeline)
  keeps each layer independently testable and reusable (e.g. `OneTimeChainProcessor`,
  stateless env, RPC `Proof` module all reuse `IBlockProcessor` with different reward sources).
- **System calls unified behind `ISystemContractHandler`.** EIP-4788 (beacon root), EIP-2935
  (blockhash), withdrawals, and EIP-7002/7251/6110 execution requests are composed into one
  handler, invoked at fixed pipeline points, so the block pipeline reads linearly.
- **Prewarming + background cancellation.** `BranchProcessor` starts cache prewarming and
  blockhash prefetch early, then cancels them on the `TransactionsExecuted` event to free the
  thread pool for parallel bloom/receipts-root/state-root computation.

## Notable patterns (the reusable idea)

**The consensus-agnostic reward seam.** The single most transferable idea for fukuii:
the block pipeline calls one interface method (`rewardCalculator.CalculateRewards(block) →
BlockReward[]`) and *applies whatever it returns*. All per-family economics — PoW fixed
emission + uncles, PoS zero-reward, and the merge-boundary transition — are expressed as
**which implementation is bound at the DI seam** (plugin registration) plus **a decorator for
the runtime transition**, not as `if (isPoW) … else …` inside the processor. This is the
direct analog of besu's `rewardCoinbase` seam and geth's `Finalize`, but expressed through
DI + a plugin-decorator rather than a subclass hook. For fukuii's PoW(ETC)/PoS(ETH) dual
family — where dispatch today is `EvmConfig.forBlock(blockNumber, …)` (block-number) vs the
timestamp overload — the lesson is: keep block-reward/finalization strategy behind one
injected interface returning a reward list, select it per network family at wiring time, and
handle a mid-chain transition (merge) with a per-block-deciding decorator rather than forking
the execution pipeline.

**Secondary reusable idea:** the *enqueue-then-drain* processing model with a separate
sender-recovery queue and an idle fast-path — a clean way to overlap ECDSA recovery with
execution and to give the block-processing thread deterministic priority/GC behavior.

## Authority note

nethermind = **queue-driven-processing + IRewardCalculator-swap** reference (the design
patterns for async block processing and DI-selected reward economics). go-ethereum / core-geth
= canonical **ETH/ETC execution + reward behavior** (the byte-exact semantics of what a block
reward *is* — ECIP-1017 fixed-supply emission for ETC, EIP-1559 burn / no-reward PoS for ETH).
Consult core-geth for the *values and rules*; consult nethermind for the *structural seam*.

## Gotchas / anti-patterns / things they later changed

- **`NoBlockRewards` is the base default, not a PoW fallback.** The unconfigured
  `IRewardCalculatorSource` returns no reward. A PoW network that fails to register
  `RewardCalculator` (via its plugin) silently produces zero-reward blocks — the seam fails
  *quiet*, not loud. For fukuii, the equivalent misconfiguration (wrong calculator bound for a
  family) would be a consensus break with no exception.
- **`IRewardCalculatorSource.Get(ITransactionProcessor)` exists only for AuRa.** The source
  header explicitly flags this as a wart (`// TODO: this has been introduced to support AuRa
  - find a way to remove it`). Most calculators ignore the tx processor and return `this`.
  Don't over-read the factory indirection as fundamental.
- **The merge reward switch is per-block via `IPoSSwitcher.IsPostMerge(header)`, not a
  one-time flip.** Reorgs across the merge boundary must re-evaluate per block — the decorator
  handles that correctly; a naive "set a boolean once" transition would corrupt rewards on a
  cross-boundary reorg.
- **Reorg branch depth is bounded (`MaxBranchSize = 8192`) but only counts
  blocks-without-state.** A comment (`PrepareProcessingBranch:751`) records a deliberate
  change: the walk-back now collects only blocks lacking state (those needing reprocessing);
  moving deeper blocks onto the main chain is delegated to `BlockTree.TryUpdateMainChain`,
  which walks headers cheaply. Earlier logic bounded the whole reorg depth.
- **Block-processing-thread gating is implicit global state.** `IsBlockProcessingThread` /
  `BlockchainProcessor.IsMainProcessingThread` is a thread-static consulted deep in the
  pipeline (e.g. `SetAccountChanges` only runs on the main thread). Reused processors
  (RPC, simulation) run off-thread and deliberately skip that work — easy to trip over.
- **`AllowSynchronousContinuations = true` on the bounded processing channel** is a
  deliberate latency optimization (let the NewPayload thread run processing inline) but means
  enqueue can execute processing continuations synchronously — not a fire-and-forget queue.
