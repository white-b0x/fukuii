# go-ethereum — block-execution
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary

geth's block-execution subsystem is a three-role pipeline — **Processor → Validator
→ consensus.Engine** — coordinated by `BlockChain.processBlock`
(`core/blockchain.go:2228`). The roles are interface-typed so the same insertion
path serves both consensus families:

1. **Process** (`core/state_processor.go:66`, `StateProcessor.Process`) — executes
   the block against the parent state and returns a `ProcessResult` (receipts,
   requests, logs, gasUsed, block access list). It does **not** compute or compare
   the state root; it only mutates `statedb`.
2. **ValidateState** (`core/block_validator.go:148`) — compares the execution
   output against the header commitments: gasUsed, bloom, receipt root, requests
   hash, block-access-list hash (Amsterdam), and finally the state root via
   `statedb.IntermediateRoot`.
3. **Finalize** (`consensus.Engine.Finalize`, `consensus/consensus.go:88`) —
   consensus-specific end-of-block state mutation (rewards for PoW, withdrawals for
   PoS). Called from inside `Process` at `core/state_processor.go:129`.

The single-block execution order inside `Process` is deliberate and consensus-load-
bearing:

```
PreExecution system calls  (EIP-4788 beacon root, EIP-2935 parent-hash)   :96
  → per-tx apply loop       (TransactionToMessage → ApplyTransactionWithEVM) :99-118
  → PostExecution           (EIP-6110/7002/7251 request extraction, Prague+) :119
  → Engine().Finalize       (block reward OR withdrawals)                     :129
```

Rewards/withdrawals land **after** all transactions and system calls, so the state
root committed later reflects them. Verify and produce share this pipeline: verify
runs Process+ValidateState; produce runs Process then `AssembleBlock`
(`core/state_processor.go:457`) which assigns `header.Root = IntermediateRoot(...)`
and builds the sealed `types.Block`.

## Key types / interfaces / files

- `core/state_processor.go:66` — `StateProcessor.Process`: the canonical per-block
  apply loop. Builds one shared `vm.EVM` (`:87`), reuses it across every tx and
  system call, `defer evm.Release()`.
- `core/state_processor.go:203` — `ApplyTransactionWithEVM`: applies one message,
  finalises tx-local state (`Finalise` post-Byzantium / `IntermediateRoot` for the
  legacy per-tx PostState root at `:222`), then `MakeReceipt`.
- `core/state_processor.go:233` — `MakeReceipt`: sets status (`:240`), gas used,
  contract-creation address, logs, bloom. `CumulativeGasUsed` threaded from the gas
  pool (`:229`).
- `core/state_processor.go:141` / `:163` — `PreExecution` / `PostExecution`: the
  fork-gated system-call phases. `ProcessBeaconBlockRoot` (`:300`, EIP-4788),
  `ProcessParentBlockHash` (`:330`, EIP-2935), and the request-queue calls
  (`:363-383`, EIP-7002/7251/8282) all use `params.SystemAddress` as sender with a
  zero-price `Message` and a fixed 30M gas budget (`systemCallGasBudget`, `:284`).
- `core/state_transition.go:362` — `ApplyMessage` → `stateTransition.execute`
  (`:645`): nonce/fee/EOA precheck (`preCheck`, `:537`) → `buyGas` (`:438`) →
  intrinsic gas → EVM call/create → `settleGas` (`:866`) → coinbase tip payment
  (`:782`). This is the message→gas→refund core.
- `core/state_transition.go:263` — `Message`: the tx-derived execution unit;
  `SkipNonceChecks`/`SkipTransactionChecks` flags carve out the eth_call/prefetch
  simulation paths.
- `core/block_validator.go:51` — `ValidateBody`: uncle/tx/withdrawal roots, blob-gas
  accounting, ancestor presence — pre-execution structural validation.
- `core/block_validator.go:148` — `ValidateState`: post-execution commitment
  checks, state-root last (`:204`).
- `consensus/consensus.go:59` — `Engine` interface: `Finalize` is the sole
  reward/withdrawal hook. **There is no `FinalizeAndAssemble` in the interface** —
  block assembly moved to the free function `AssembleBlock` (`state_processor.go:457`).
- `consensus/ethash/consensus.go:508` — `Ethash.Finalize` → `accumulateRewards`
  (`:558`): static block reward (Frontier 5 / Byzantium 3 / Constantinople 2 ETH,
  `:42-44`) plus uncle rewards.
- `consensus/beacon/consensus.go:346` — `Beacon.Finalize`: delegates to `ethone`
  (ethash) pre-merge; post-merge iterates `body.Withdrawals` crediting gwei→wei
  balances and **applies no block reward** (`:371` "issued by consensus layer instead").
- `core/types/receipt.go:53` — `Receipt`; `:382` `Receipts.DeriveFields` reconstructs
  per-tx display fields (effective gas price, log index, contract address) on the
  RPC read path from stored receipts.

## Design decisions & rationale

- **Interface-typed roles, not conditionals.** `Processor`, `Validator`, and
  `consensus.Engine` are swappable interfaces on `BlockChain`. PoW vs PoS is chosen
  at engine-construction time (`Beacon` wraps an `ethone` fallback), so the
  insertion path in `blockchain.go` never branches on consensus family. This is
  exactly the seam fukuii needs for its PoW/PoS dual-family split.
- **Reward is an engine concern, not a Processor concern.** `Process` knows nothing
  about block rewards; it just calls `Engine().Finalize`. Swapping ethash→beacon
  swaps 5-ETH-block-reward for zero-reward-plus-withdrawals with no change to the
  executor. **This is the exact line fukuii's ETC/ETH split rides on**: ETC keeps
  ECIP-1017 emission in its Finalize equivalent; ETH's beacon Finalize zeroes it.
- **State root computed once, late.** Per-tx work uses `Finalise` (journal flush,
  no trie hashing) since Byzantium; the expensive `IntermediateRoot` is deferred to
  ValidateState/AssembleBlock. Pre-Byzantium the per-tx PostState root is still
  computed (`:222`) because those receipts carried intermediate roots.
- **System calls modelled as pseudo-transactions.** EIP-4788/2935/7002/7251 run
  through the same `evm.Call` machinery with a synthetic `SystemAddress` sender and
  a fixed gas budget outside the block gas pool — no special-cased state pokes.
- **Verify/produce symmetry.** The produce path (`AssembleBlock`) reuses the same
  `Process` output and only differs in that it *writes* `header.Root` rather than
  *comparing* it. One executor, two consumers.

## Notable patterns (the reusable idea)

- **The consensus.Engine.Finalize seam.** A single narrow interface method is the
  entire boundary between "execute EVM transactions" (family-agnostic) and "apply
  consensus economics" (family-specific). Everything above it — the apply loop,
  receipt generation, gas accounting — is shared verbatim between PoW and PoS.
- **Two-phase system calls bracketing the tx loop.** `PreExecution` (state the
  block depends on: beacon root, parent hash) before txs; `PostExecution` (state
  the block produces: withdrawal/consolidation requests derived from logs) after.
  Fork gating (`IsPrague`, `IsAmsterdam`) lives entirely inside these two functions,
  keeping `Process` itself fork-clean.
- **Fake-message simulation flags.** `Message.SkipNonceChecks` /
  `SkipTransactionChecks` / `evm.Config.NoBaseFee` let the identical
  `stateTransition` serve eth_call, estimateGas, and prefetch without a parallel
  code path — the divergence is data on the message, not a forked function.
- **DeriveFields lazy reconstruction.** Receipts persist minimally (status, cumul.
  gas, bloom, logs) and rebuild derived fields on read, avoiding storage bloat.

## Authority note

geth is the **canonical ETH execution-pipeline authority** — the structure above
(Processor/Validator/Engine roles, system-call phases, EIP-1559 burn + tip-to-
coinbase, post-merge zero-reward + withdrawals) is the reference for fukuii's
**PoS/ETH-Sepolia** (`beacon`) path. It is **not** the ETC reward authority:
geth's ethash `accumulateRewards` implements Ethereum's halving-by-hard-fork
schedule (5→3→2 ETH), which is *not* ETC's rule. For ETC's ECIP-1017 fixed-supply
5M-block-era emission (fukuii `BlockRewardCalculator.scala`, `forge` territory),
cross-reference **core-geth** — the ETC reward authority — documented separately;
do not treat this file's `accumulateRewards` as the ETC spec.

## Gotchas / anti-patterns / things they later changed

- **`FinalizeAndAssemble` is gone from the interface.** Older geth (and Mantis-era
  forks like fukuii's ancestor) had `Engine.FinalizeAndAssemble`. Current geth
  removed it; assembly is the free function `AssembleBlock` (`state_processor.go:457`)
  and the interface carries only `Finalize`. A fukuii port modelled on old geth will
  have an extra interface method that no longer maps 1:1.
- **`beacon` wraps `ethash`, it does not replace it.** `Beacon.Finalize` /
  `Seal` / `CalcDifficulty` all delegate to `beacon.ethone` when the header is not
  a PoS header (`consensus/beacon/consensus.go:347,380,399`). The merge is a runtime
  header check (`IsPoSHeader`), not two separate engines. fukuii's cleaner split
  (distinct forge/beacon paths) diverges here intentionally.
- **ethash `SealHash` panics on post-merge fields** (`:535-549`): withdrawalsHash,
  excessBlobGas, blobGasUsed, parentBeaconRoot, slotNumber set on an ethash header
  is a hard panic — the code aggressively asserts that PoW and PoS header shapes
  never mix. Good "fail loudly" precedent; fukuii should keep the equivalent assertions.
- **Rewards run *after* PostExecution system calls**, not before. Any port that
  applies the block reward before withdrawal/request processing will diverge on the
  committed state root even though every tx executed identically.
- **`ProcessParentBlockHash` panics on a failed system call** (`:353`) whereas the
  request-queue calls return an error (`:411`) — inconsistent error handling between
  otherwise-parallel system calls; a byte-for-byte port must preserve which one
  panics vs. returns.
- **Coinbase tip is skipped only when `NoBaseFee` AND both fee fields are zero**
  (`state_transition.go:778`) — a simulation-only guard against negative effective
  tips; on a real block the coinbase is always credited `gasUsed × effectiveTip`.

## Use-case / node-role fitness

- **Validator (block production):** served by the produce split —
  `Process` + `AssembleBlock` + `Engine.Seal`. PoS `Seal` is a no-op
  (`consensus/beacon/consensus.go:379`) because the CL seals; PoW `Seal` runs
  ethash. Withdrawals are CL-driven, so a geth validator applies them but issues no
  EL reward.
- **Enterprise / custody / archival-RPC (verify-only):** served by the verify split
  — `Process` + `ValidateState`, never touching `Seal`/`AssembleBlock`. The
  interface typing means a verify-only deployment carries the full executor with
  zero production surface.
- **Multi-network:** the Processor/Validator/Engine seam is the single point where a
  new network's economics plug in — the reason geth can host ethash-PoW and
  beacon-PoS behind one insertion path, and the template fukuii extends to ETC vs
  ETH within one binary.
