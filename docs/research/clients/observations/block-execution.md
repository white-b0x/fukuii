# Observations — block-execution
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/block-execution.md + consensus-engines observation._

This is the Phase-2 cross-client comparison for the **block-execution** subsystem: how each
reference client structures the single-block execution pipeline, splits verify from produce,
seams the reward/finalize economics, models fork-varying collaborators, processes EIP-7685
system calls, and (erigon) parallelizes execution. It builds on the already-consolidated
`observations/consensus-engines.md` — which owns the *engine-selection / merge-decorator /
fork-dispatch* axis — and does **not** re-litigate it here: consensus-engines covers *how a
family is selected and how the merge is modelled*; this doc covers *what the executor does per
block once a family is in hand, and where the reward lands*. Every per-client claim is cited to
that client's `block-execution.md`; the reward-seam ↔ merge-decorator link is cross-referenced,
not duplicated.

**Authority model (per Phase-0):** go-ethereum = canonical ETH execution pipeline + the
`consensus.Engine.Finalize` reward/withdrawal seam + EIP-1559 burn/tip semantics; core-geth =
ETC ECIP-1017 fixed-supply emission byte-authority (era math, uncle/nephew reductions, DAO
absence); besu = the `ProtocolSpec`-per-fork bundle + `MilestoneType` dispatch (fukuii's closest
JVM structural mirror); erigon = staged/parallel execution (exec3 Block-STM) + Bor sidechain
finalize; nethermind = queue-driven async processing + `IRewardCalculator` DI swap + the
`MergeRewardCalculator` per-block decorator; reth = trait-abstracted pre/post/tx executor
(`apply_pre_execution_changes` / `execute_transaction` / `apply_post_execution_changes`).

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | fukuii | Authoritative |
|---|---|---|---|---|---|---|---|---|
| **Execution pipeline structure** | 3-role interface pipeline: `Processor.Process` → `Validator.ValidateState` → `consensus.Engine.Finalize`, coordinated by `BlockChain.processBlock` | inherits geth's pipeline verbatim; the only structural delta is *where the reward comes from* (see below), not the loop shape | fork-agnostic `AbstractBlockProcessor.processBlock()` reads all behavior off a per-fork `ProtocolSpec` bundle (~30 collaborators); loop never names a fork | execution is **one stage** of an 8-stage sync pipeline; `SpawnExecuteBlocksStage`→`ExecV3`; core `ExecuteBlockEphemerally` is the geth-`Process` equivalent | **three-layer** split: `BlockchainProcessor` (async queue/reorg) → `BranchProcessor` (world-state scope/prewarm) → `BlockProcessor` (single-block EVM pipeline) | **three-layer trait stack**: `EvmFactory` → `BlockExecutor` → `BlockAssembler`, driven by `Executor<DB>`; loop = `BasicBlockExecutor::execute_one` | orchestrator→preparator→reward-seam: `BlockExecution.executeBlock` (`ledger/BlockExecution.scala:100`) → `BlockPreparator.executeTransactions` (`:520`) → `ConsensusEngine.finalizeBlock`; **no `ProtocolSpec` bundle** — forks re-derived via `EvmConfig.forBlock(...)` per site | **go-ethereum** (canonical role split); besu/reth = cleanest bundled/trait forms |
| **Validate-vs-produce split** | one executor, two consumers: verify = `Process`+`ValidateState`; produce = `Process`+`AssembleBlock` (writes `header.Root` instead of comparing) | same as geth; `FinalizeAndAssemble` still present (older shape) — rejects any block carrying withdrawals | same `ProtocolSpec` bundle backs both `MainnetBlockValidator` (import) and the block-creation path (produce); `getForNextBlockHeader` gives producer the next spec | `Finalize` (verify) vs `FinalizeAndAssemble` (mining) both off one `engine` interface; Unwind/Prune are first-class per-stage ops | same `IBlockProcessor` reused by production, RPC `Proof`, stateless env — differ only by injected reward source + `ProcessingOptions` (`ProducingBlock`) | block-building (`BasicBlockBuilder`) and import (`BasicBlockExecutor`) call the **same** `BlockExecutor` methods → production and validation cannot diverge | one `BlockPreparator` backs verify (`executeAndValidateBlock` `:39`), miner produce (`prepareBlock` `:624`), and proposer build (`executeForProposer` `:94`) → production/validation cannot diverge | **reth / besu** (shared executor both directions); geth = canonical two-consumer form |
| **Reward/finalize seam** | `consensus.Engine.Finalize` — the **sole** reward/withdrawal hook; `Process` knows nothing about rewards | reward relocated to `params/mutations.AccumulateRewards`, dispatched on `GetEthashECIP1017Transition`; still called from `Ethash.Finalize` | `AbstractBlockProcessor.rewardCoinbase` — the **one abstract method** of an otherwise concrete loop; PoS sets `blockReward=Wei.ZERO, skipZeroBlockRewards=true` | `engine.Finalize` / `FinalizeAndAssemble` applies rewards + post-block system calls, then `ibs.CommitBlock`; Bor `Finalize` = no reward, span rotation + state-sync instead | `BlockProcessor.ApplyMinerRewards` calls `IRewardCalculator.CalculateRewards(block)` and credits whatever it returns — consensus-agnostic | `apply_post_execution_changes()` — where ETH puts withdrawals and a PoW client would put the block+ommer reward | `ConsensusEngine.finalizeBlock` (`consensus/ConsensusEngine.scala:96`) delegates verbatim to ledger `BlockPreparator.payBlockReward` (`:51`); PoW/PoS split = one `if block.header.isPoS` early return (`:58`); withdrawals applied *outside* the seam, inline in `BlockExecution` (`:115`) | **go-ethereum** `Finalize` (canonical single seam); nethermind/besu/reth = same seam, different language binding |
| **System-calls (EIP-2935/4788/7002/7251)** | two-phase: `PreExecution` (4788 beacon-root, 2935 parent-hash) before tx loop; `PostExecution` (7002/7251/6110 request extraction) after; modelled as pseudo-txs w/ `SystemAddress` sender, fixed 30M budget | inherits geth's phases; PoW ETC exercises none of the post-Merge request calls (withdrawals hard-rejected) | `RequestProcessorCoordinator` = `RequestType`→processor map; `SystemCallRequestProcessor` is the reusable "call a system contract, wrap output as `Request`" primitive; `noOp()` for pre-Prague/PoA | `engine.Initialize` (pre-block, 2935/4788) + `engine.Finalize` (post-block requests); `SysCallContract` harness — **author = `header.Coinbase` for Bor, `SystemAddress` otherwise** | composed behind one `ISystemContractHandler` (beacon-root + blockhash + withdrawals + requests), invoked at fixed pipeline points | `apply_pre_execution_changes()` (2935/4788) + `apply_post_execution_changes()` (7002/7251); the per-call body lives out-of-tree in `alloy-evm` | two-phase: pre-tx 4788/2935 (`applyEip4788`/`applyEip2935`), post-tx 7002/7251 (`processPragueSystemCalls` `:366`, `SystemAddress`, 30M gas) + 6110 deposit log-scrape (`collectDepositRequests` `:421`); **no `RequestType` registry** (hard-coded loop); EIP-2935 dual-gated ETH-Prague-ts OR ETC-Olympia-block (`:245-249`) | **go-ethereum** (canonical two-phase model); besu = cleanest data-driven `RequestType` map |
| **Receipts** | `MakeReceipt` sets status/gas/logs/bloom; persists minimally, `Receipts.DeriveFields` reconstructs display fields (effective gas price, log index) on the RPC read path | inherits geth | receipt built inside `processBlock`; receipt/bloom/receipts-root checks in the loop | receipts/bloom/gas-used checked in `ExecuteBlockEphemerally` (`:153-179`); **Bor state-sync receipts are synthetic** (logs sliced from `ibs.Logs()`, no backing tx) | `IBlockTransactionsExecutor` collects receipts per-tx; receipts root + blooms computed after tx loop | `RethReceiptBuilder` = EIP-658 status receipt (tx_type, success, cumulative gas, logs) | EIP-658 typed receipts per tx type (Legacy/Type01–04, `BlockPreparator.scala:579-584`), bloom via `BloomFilter`; receipts-root + logsBloom checked in the post-exec validator (`StdBlockValidator.scala:71`/`:88`), not lazily rebuilt on the RPC read path | **go-ethereum** (`DeriveFields` lazy-reconstruction is the canonical storage-minimal pattern) |
| **Parallel execution** | none (serial `Process`) | none | serial or **parallel `MainnetBlockProcessor`** choice wired per fork (`MainnetProtocolSpecs:211`), but the tx loop itself is serial | **exec3 Block-STM** optimistic-concurrency engine: speculative out-of-order execution against an in-RAM `VersionMap`, read-set conflict detection, bounded re-execution; canonical result assembled in valid serial order → deterministic root; `experimentalBAL`-gated, not yet default | prewarming + parallel bloom/receipts/state-root after the tx loop; tx loop serial; sender-recovery parallelized off the hot path | serial `execute_one`; `OnStateHook` lets witness/trace consumers observe without a second pass | none — strictly serial tail-recursive `executeTransactions` (`BlockPreparator.scala:519-520`); no optimistic/parallel path | **erigon** (sole parallel-execution / Block-STM authority) |
| **Per-fork bundle** | fork-gating lives inside `PreExecution`/`PostExecution`; `Process` itself is fork-clean; forks chosen via named-fork `chainRules` | **per-EIP transition block numbers** (`IsEnabled(GetEIPxTransition, num)`), not geth's monolithic `chainRules` — lets ETC assemble bespoke fork groupings | **`ProtocolSpec` immutable value object per fork**, built once at startup, looked up by header; forks form an inheritance chain of deltas (`shanghaiDefinition` calls `parisDefinition`, mutates delta) | `rules.Engine` per network; fork behavior via `chainRules` like geth | per-fork `spec` carries `BlockReward`; `AddTransitions(blockNumbers, timestamps)` (see consensus-engines) | `ConfigureEvm` umbrella trait yields per-block `BlockExecutor` bound to spec/tx/receipt types via associated types; monomorphized, no `dyn` | **no bundle** — `EvmConfig.forBlock(...)` re-derived per use-site + inline `forkBlockNumbers.*` / `isXTimestamp(...)` predicates; dual dispatch clock via the 2-arg (ETC block-number) vs 3-arg (ETH +timestamp) overload | **besu** (`ProtocolSpec` bundle is the transferable JVM form) |
| **The ECIP-1017 ETC reward** | **NOT authoritative** — geth's ethash `accumulateRewards` is ETH's halving-by-hard-fork (5→3→2 ETH), not ETC's rule | **AUTHORITATIVE**: `ecip1017BlockReward` era math — 5 ETC base, ×4/5 per 5M-block era; era index `(blockNum-1) mod eraLength` (byte-critical off-by-one); Era-0 vs Era-≥1 uncle/nephew formulas genuinely differ; DAO state change absent (expressed as `nil` config) | **removed from upstream** (Feb 2026) — structural reference only, never behavioral; see `history-pow-etc.md` | not authoritative — ethash constants (`rules/ethash/rules.go:51-53`) are vanilla ETH, carry no ECIP-1017 | not authoritative — `RewardCalculator` is vanilla `spec.BlockReward` + uncle math (Ethash cross-check only) | **not authoritative** — no PoW/Ethash reward path exists anywhere in reth's execution crates | **consumes core-geth authority** — `BlockRewardCalculator.scala:11` era fn `(blockNumber-1)/eraDuration` (`:110`), era-0-vs-≥1 ommer switch (`:91-97`), Byzantium/Constantinople base step-downs (`:117`); + ECIP-1111 treasury base-fee credit (`BlockPreparator.scala:89`, fukuii Olympia — no ref-client equivalent) | **core-geth** (sole living ETC/ECIP-1017 byte-authority) |

## Approach catalog (use-case-aware)

Verdicts: **DEFAULT** = fukuii's baseline best practice · **OPTIONAL(role)** = offer for a named
use-case (enterprise / custody / validator / light / archival / multi-network) · **OBSOLETE** =
understood-but-discarded. Use-case taxonomy per `README.md`'s omni-client lens.

| Approach | Clients using it | Good for (use-case / node-role) | Verdict | Why |
|---|---|---|---|---|
| **`consensus.Engine.Finalize` single seam** | go-ethereum (`Finalize`), core-geth (same shape, reward relocated to `params/mutations`) | every node role — the family-economics boundary line | **DEFAULT** | One narrow interface method is the *entire* boundary between "execute EVM transactions" (family-agnostic) and "apply consensus economics" (family-specific). The apply loop, receipt generation, gas accounting are shared verbatim PoW↔PoS. This is the exact line fukuii's ETC/ETH split rides on: ETC keeps ECIP-1017 emission in Finalize; ETH's beacon Finalize zeroes it. |
| **Immutable per-fork `ProtocolSpec` bundle** | besu (~30 collaborators, `MilestoneType` dispatch) | multi-network + multi-fork (~25 forks, two families) on one codebase; JVM clients | **DEFAULT (for the fork-collaborator bundling)** | A fork is a distinct immutable value object built once, not `if(blockNumber>=X)` branches scattered through the processor. The execution loop is *handed a fully-resolved bundle and just calls methods on it* — replaces "ask `EvmConfig.forBlock(...)` mid-execution and branch." Scales to ~25 forks without the loop rotting. fukuii's closest JVM structural mirror. |
| **`IRewardCalculator` DI + `MergeRewardCalculator` decorator** | nethermind | single-binary multi-network where PoW↔PoS transition must be per-block | **DEFAULT-adjacent (the per-block-transition principle)** | Reward strategy is DI-injected, never branched in the processor: `ApplyMinerRewards` has zero PoW/PoS knowledge. The merge boundary is a **runtime per-block** `IPoSSwitcher.IsPostMerge(header)` decorator — correct across cross-boundary reorgs where a naive "set a boolean once" corrupts rewards. Direct analog of besu's `rewardCoinbase` and geth's `Finalize`, via DI+decorator rather than a subclass hook. Cross-ref `consensus-engines.md` (merge = composable co-activating wrapper). |
| **One execution loop + family pre/post hooks** | reth (`BasicBlockExecutor`) | trait/typeclass-abstracted executors; per-family plug-in | **DEFAULT (the pre/post/tx skeleton)** | `apply_pre_execution_changes` / per-tx `execute_transaction` / `apply_post_execution_changes` as three trait methods. **ETC's block+ommer reward goes in exactly the slot where ETH's withdrawals go** (`apply_post_execution_changes`) — same trait, same driver, different family body; the driver never changes. Caveat: reth's *vocabulary* is post-merge (`parent_beacon_block_root`, `withdrawals`, no PoW-shaped context) — copy the shape, widen the seam for PoW. |
| **Parallel exec3 Block-STM** | erigon | archival re-execution / trace / bulk historical re-org validation | **OPTIONAL(archival / perf role)** | Optimistic out-of-order execution + multi-version read/write conflict detection + bounded re-execution; canonical result assembled in valid serial order → deterministic root. High-throughput historical range re-execution. But: two paths that must be byte-identical (serial vs parallel), genuinely hard conflict-detection edge cases (CREATE2 re-incarnation, self-destruct — their own `#21153` `t.Skip` hid a real bug), `experimentalBAL`-gated, not the default at tip. Not needed for a validating/mining node. |
| **ECIP-1017 era reward** | core-geth (authority) | ETC/PoW production; mining-pool / validator use-case (defines exact issuance a miner is paid) | **DEFAULT (it IS ETC consensus — non-negotiable)** | The ECIP-1017 disinflationary era function (5 ETC, ×4/5 per 5M-block era) is ETC's single largest consensus divergence. Byte-exact reproduction required: era index off-by-one `(blockNum-1) mod eraLength`, separate integer exponentiation of `4^era` and `5^era` before multiply-divide, Era-0-vs-Era-≥1 uncle formula switch. `forge` territory. |
| **Queue-driven async block processing** | nethermind (`BlockchainProcessor` + twin channels) | high-throughput PoS Engine-API latency; overlap ECDSA recovery with execution | **OPTIONAL(perf / Engine-API-latency lens)** | Enqueue-then-drain with a separate sender-recovery queue, an idle fast-path (skip recovery when queue empty → inherit `NewPayload` thread priority), dedicated highest-priority processing thread, GC toggled off in-flight. A clean latency win, but heavy machinery (thread-static `IsBlockProcessingThread` gating is implicit global state); adopt only if Engine-API latency is a named goal. |

## Best-practice synthesis

**The DEFAULT + OPTIONAL menu that falls out of the six clients:**

1. **DEFAULT — keep the executor FAMILY-AGNOSTIC with the reward/finalize as the SOLE economics
   seam.** Every client converges on this: geth's `consensus.Engine.Finalize`, besu's abstract
   `rewardCoinbase`, nethermind's `IRewardCalculator.CalculateRewards`, reth's
   `apply_post_execution_changes`. Above that one seam — the tx-apply loop, receipt generation,
   gas accounting, system-call phases — is shared verbatim between PoW and PoS. The load-bearing
   consequence for fukuii: **ETC's ECIP-1017 emission goes exactly where ETH's withdrawals go**
   (the post-execution / finalize slot), so the ETC/ETH split needs *no* `if(isPoW)` anywhere in
   the pipeline — only a different implementation bound at the single seam.

2. **DEFAULT — bundle the fork-varying collaborators (besu `ProtocolSpec`).** Replace
   "mid-execution `EvmConfig.forBlock(...)` + branch" with "hand the loop a fully-resolved
   immutable per-fork bundle." Build once at startup, look up by header. Adding a fork = add one
   definition + one milestone entry; the execution loop never changes. This is the JVM-native form
   fukuii should target, and it composes with #1 (the reward is just one field of the bundle).

3. **DEFAULT — handle a mid-chain PoW→PoS transition with a per-block decorator, not a pipeline
   branch (nethermind `MergeRewardCalculator`).** Where a family switches mid-chain, decide the
   reward per block via a content/`IsPostMerge`-style predicate wrapped around the base calculator
   — correct across cross-boundary reorgs. For ETC this is inert (permanently PoW); the pattern
   matters for the ETH path and any future PoA→PoS. Cross-references `consensus-engines.md`'s
   "merge as composable conditional wrapper" DEFAULT — same principle, reward-seam projection.

4. **DEFAULT — one executor serves both verify and produce.** geth's `Process`+`ValidateState`
   (verify) vs `Process`+`AssembleBlock` (produce), besu's one `ProtocolSpec` behind both
   `MainnetBlockValidator` and block-creation, reth's shared `BlockExecutor` behind builder and
   importer — all guarantee production and validation cannot diverge. A verify-only (enterprise /
   custody / archival-RPC) deployment carries the full executor with zero production surface.

5. **DEFAULT — two-phase system calls bracketing the tx loop.** `PreExecution` (state the block
   *depends on*: 4788 beacon-root, 2935 parent-hash) before txs; `PostExecution` (state the block
   *produces*: 7002/7251/6110 requests) after; modelled as pseudo-txs with a system-address sender.
   besu's data-driven `RequestType`→processor map (with `noOp()` degradation) is the cleanest form.
   Keeps the loop fork-clean and matters for the ETH/Osaka path (ETC exercises none of the
   post-Merge request calls).

**OPTIONAL menu (offer per named use-case, do not make default):**

- **OPTIONAL(archival/perf) — parallel Block-STM exec3 (erigon).** For archival re-execution /
  trace / bulk re-org validation only. Real complexity cost (two byte-identical paths, hard
  conflict-detection edges, still experimental at tip). Not for a validating/mining node.
- **OPTIONAL(Engine-API-latency) — queue-driven async processing (nethermind).** Adopt only if
  PoS Engine-API `NewPayload` latency is a named goal; heavy thread-static machinery otherwise.

**Authority caveat (non-negotiable):** **core-geth is the sole living ECIP-1017 byte-authority.**
The family-agnostic-executor DEFAULT validates *keeping ETC/ETH reward on one seam* — but the ETC
reward *value* (era math, off-by-one era index, era-dependent uncle/nephew formulas, DAO absence)
must be reproduced byte-for-byte from core-geth's `rewards.go`/`rewards_classic.go`, not from any
prose spec and never from geth/reth/erigon/besu (whose reward code is either vanilla-ETH or ETC-
removed). besu = structural reference for ETC, never behavioral.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

These are **seeds**, not verdicts to implement in this doc. fukuii's block-execution is **SR-08
(ledger)**, sequenced after the vm and db subsystem reviews.

- **fukuii's current shape:** the ledger's finalize path already carries ECIP-1017 emission in
  `BlockRewardCalculator.scala` — the ETC authority target, validated byte-for-byte against
  core-geth's `GetBlockWinnerRewardByEra` / `GetBlockUncleRewardByEra` (note the `-1` in the era
  index and the separate integer exponentiation of `4^era`/`5^era`). This is **consensus-critical,
  `forge` territory**: any change routes through the Consensus-Critical Change Protocol.

- **The reward-seam family-agnostic-executor pattern validates keeping ETC/ETH reward on one
  seam.** All six clients converge on it, so fukuii's dual-family split should express PoW-vs-PoS
  economics as *which implementation is bound at the finalize/reward seam*, never as an
  `if(isPoW)` branch in the apply loop — the direct execution-layer continuation of
  consensus-engines' "branch on capabilities, not network identity" invariant. The besu
  `ProtocolSpec` bundle + nethermind per-block merge decorator are the two structural references
  for *how* to bind it.

- **NOTE the `FinalizeAndAssemble`-removed finding.** `go-ethereum/block-execution.md` records
  that current geth removed `Engine.FinalizeAndAssemble` from the interface (assembly is now the
  free function `AssembleBlock`); **core-geth still has it** (Mantis-era shape). A fukuii port
  modelled on the old geth/Mantis lineage will carry an extra interface method that no longer maps
  1:1 to current geth — a dead-code-review candidate to reconcile against the single-`Finalize`
  seam, per `dead-code-review.md` (Wire it / Delete it / Defer).

- **System-call phases seed the ETH/Osaka path.** The two-phase Pre/Post structure and besu's
  `RequestType`→processor map are the reference for fukuii's `beacon`-path 7002/7251/6110 request
  processing; ETC's `forge` path exercises none of it (withdrawals hard-rejected, as in core-geth).

- **exec3 / queue-async are Phase-4 role seeds, not baseline.** Parallel execution and
  queue-driven processing are OPTIONAL(archival/perf, Engine-API-latency) — carry as data-serving /
  latency-role seeds for later, not as SR-08 ledger baseline work.
