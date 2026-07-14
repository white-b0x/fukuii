# Observations — block-production
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/block-production.md + mining-protocol-evm + consensus-pow-cpu topics._

Block production = **assemble a candidate block from parent + txpool, then authorize it**
(seal). The mirror of block-execution: production *creates*, execution *replays*, sharing the
same `ApplyTransaction`/`Finalize`/state primitives driven forward. This slot is HIGH VALUE
for fukuii because block production **is** the mining-pool / validator use case. The
cross-client lesson is unanimous: keep the **assembly pipeline consensus-blind**, route all
family-specific authorization through **one narrow seal/produce seam**.

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | fukuii | Authoritative |
|---|---|---|---|---|---|---|---|---|
| **Production abstraction** | `Miner` + payload factory (tiny `Backend` seam) | geth `miner/worker` + `Ethash` engine | `MiningCoordinator` (per-consensus) over shared `AbstractBlockCreator` + `BlockTransactionSelector` | one engine-agnostic pipeline `createBlock→execBlock→finishBlock`, pluggable `rules.Engine.Seal` | one `IBlockProducer.BuildBlock`; injected `ISealer`/`IDifficultyCalculator`/`ITxSource`; trigger/runner decides *when* | two-trait `PayloadBuilder` (pure fill) + `BasicPayloadJob` (lifecycle), compile-time generic | family `match` in `startMiningProcess` + one shared `generateBlock`; Sealer/ValidatorProvider seams DESIGNED-not-built (`PoWMining.scala:105`) | **besu** (MiningCoordinator) / **erigon** (Seal-seam) / **reth** (PayloadBuilder trait) for the *shape* |
| **PoW sealing (getWork/submitWork)** | pre-merge original; **`Ethash.Seal` panics on HEAD** | ✅ full `remoteSealer` state machine; verify-before-ack; ECIP-1099 seedHash | had `PoWBlockCreator` getWork **+ server-Stratum → both removed** | external-`getWork`-only via gRPC `MiningServer`; local CPU mining gutted 2021 | ❌ **no getWork at all** (opt-in CPU `EthashSealer` only) | ❌ none (no PoW path) | ✅ getWork/submitWork + `WorkNotifier` push + ECIP-1099 seedHash + staleThreshold; verify-before-ack NOT yet wired (`EthMiningService.scala:155`) | **core-geth** — sole miner-adopted ETC authority |
| **Payload-building (empty-first + improve-until-getPayload)** | ✅ `buildPayload`: empty `noTxs` first, background goroutine re-builds richer/higher-fee, `Resolve()` freezes | (pre-merge worker; not the PoS path) | ✅ `MergeCoordinator.preparePayload` → async `tryToBuildBetterBlock` | ✅ async `BlockBuilder` keyed by `payloadId`, interrupt+Cond harvest | ✅ `PayloadPreparationService` empty-first then `ImproveBlock` loop, per-`payloadId` cache | ✅ `BasicPayloadJob` empty-first + interval re-`try_build`, best-fees-win | synchronous single full build keyed by `payloadId`, TTL-stashed; no empty-first/improve loop (`EngineApiService.scala:568`) | **geth/reth** (canonical PoS build) |
| **Tx-selection for inclusion** | `TransactionsByPriceAndNonce` heap (global tip order, per-account nonce), priority senders, interruptible fill | same geth heap + `commitTransactions` | `BlockTransactionSelector`: composable time-boxed selectors, commit/rollback per tx, `PluginTransactionSelector` seam | `TxnProvider` batches of 50, `alreadyYielded` set, tip-priority only | composable `ITxSource` + `.Then(...)` (`CompositeTxSource`) | delegated to pool's `BestTransactions` iterator + `mark_invalid` to prune dependents | `prepareTransactions` per-sender nonce + cross-sender tip + gas cutoff + ECIP-1122 Olympia-gated min-tip; whole-list, not interruptible (`BlockGeneratorSkeleton.scala:125`) | **geth** heap = canonical ordering (banksy territory) |
| **MEV / builder** | priority-sender hook only | none | none in-tree (expected via plugin seam) | `blockValue` returned with payload (MEV-aware at interface) | ✅ **Flashbots** `flashbots_validateBuilderSubmissionV3` + `rbuilder_*`; `MultipleBlockProducer`+`IBestBlockPicker` | none in-tree (Optimism builder not vendored) | none (neither family) | **nethermind** (Flashbots/builder-validation) |
| **Encrypted mempool** | ❌ | ❌ | ❌ | ❌ | ✅ **Shutter** threshold-encrypted mempool (`ShutterTxSource`, decrypt-then-front-inject) | ❌ | ❌ | **nethermind** (Shutter) |
| **Dev / instant-seal** | `--dev` SimulatedBeacon (Clique-instant history) | `--dev` sim-beacon / Clique-instant | built-in `dev` = Ethash `fixeddifficulty: 100` | staged (no standalone) | `NethDev`/AuRa instant-seal | `--dev` auto-seal `MiningMode{Instant,Interval,Trigger}` | `MockedMiner` (no-hash, N-block, 1/sec) + `EthashMiner` CPU grind + `RestrictedPoW` signed variant (`MockedMiner.scala:113`) | all ship one; besu fixed-diff = PoW-shaped model |

## Approach catalog (use-case-aware)

| Approach | Clients using it | Good for (use-case / node-role) | Verdict | Why |
|---|---|---|---|---|
| **getWork/submitWork remote-miner sealing** | **core-geth** (THE miner-adopted ETC path); geth pre-merge (history); erigon (serve-only); besu (removed) | ETC PoW production — external GPU rigs/pools own hashpower+DAG, node serves work & validates seals | **DEFAULT** (ETC production) | The only miner-adopted ETC contract; getWork format + ECIP-1099 seedHash + verify-before-ack + staleThreshold=7 are byte-authoritative in core-geth |
| **Empty-block-first + background-improve-until-getPayload payload handle** | geth, reth, nethermind, besu (Merge), erigon | PoS/validator production — proposer has a whole slot, must never miss it | **DEFAULT** (PoS production) | Guarantees a deliverable block on `getPayload` (1s deadline) while folding in higher-fee txns until slot time; the transferable payload lifecycle |
| **Per-consensus Seal/produce strategy over one shared assembly engine** | besu (`MiningCoordinator` + `AbstractBlockCreator`), nethermind (`IBlockProducer` + `ISealer`), reth (`PayloadBuilder` trait), erigon (`rules.Engine.Seal` seam) | Any multi-consensus client — add a family = one new seal impl, never touch tx-selection/roots | **DEFAULT** (architecture) | Keeps consensus-critical assembly written once; consensus variance is a single hook (`createFinalBlockHeader` / `Seal` / `try_build`) |
| **BFT proposer** | **besu** (IBFT2/QBFT: `BftMiningCoordinator`, `BftProposerSelector`, round-based extraData) | Private/consortium PoA networks (fukuii NET-01/Batch-7) | **OPTIONAL(consortium)** | Proposer identity derived deterministically from on-chain state; the model to mirror for consortium block production |
| **MEV builder / relay validation** | **nethermind** (Flashbots `flashbots_validateBuilderSubmissionV3`, `rbuilder_*`) | Validator/MEV node accepting external builder blocks | **OPTIONAL(validator/MEV)** | Validate-don't-build: re-execute a builder's block, confirm it pays the proposer; heavy assembly stays in external `rbuilder` |
| **Encrypted mempool** | **nethermind** (Shutter) | Anti-frontrunning / fair-ordering validator | **OPTIONAL(anti-frontrunning)** | Threshold-encrypted txs decrypted only after keypers publish the slot key; layered `ITxSource` injects them at the front |
| **Internal CPU sealing** | **core-geth** (ETC-correct), nethermind (vanilla, opt-in) | Private PoW testnet / dev / conformance — node grinds nonces itself, no rig/pool | **OPTIONAL(private-PoW-testnet)** | The PoW analogue of a private-PoA devnet; the vehicle for Olympia (ECIP-1111/1112/1121/1122) fork testing without GPU rigs |
| **Dev instant-seal** | all (geth sim-beacon, besu `fixeddifficulty`, nethermind `NethDev`, reth auto-seal) | Local dev + CI — block per pending tx / per interval, no hashpower | **OPTIONAL(local/CI)** | Fastest inner loop; besu's fixed-difficulty Ethash keeps it PoW-shaped as a conformance harness |
| **In-node server-side Stratum** | besu (built it, removed 2025-06) | (would be) rigs connecting to node without pool software | **OBSOLETE** | Never miner-adopted; Stratum belongs in pool software, not the EVM node — keep it separate |

## Best-practice synthesis

**DEFAULT (production architecture).** A **consensus-BLIND assembly pipeline** (state clone →
pre-exec system calls → tx-select → withdrawals/EL-requests → reward → roots → header) with
all family-specific authorization behind **one per-consensus Seal/produce strategy**. This is
the unanimous cross-client verdict — erigon's `rules.Engine.Seal(chain, block, results, stop)`
seam, besu's `MiningCoordinator` + single `createFinalBlockHeader` hook, reth's `PayloadBuilder`
trait, nethermind's injected `ISealer`. Adding a consensus family = one new seal implementation;
tx-selection, execution, and root computation are never touched. **ETC reward-emission
(ECIP-1017) belongs in the post-execution `Finalize`/reward hook where ETH withdrawals go** —
the same slot in the shared pipeline, dispatched per-family.

- **ETC production path = getWork/submitWork** (core-geth authority). External GPU rigs/pools
  own the hashpower and full DAG; the node assembles the candidate, hands out
  `[headerPowHash, ECIP-1099-seedHash, target, blockNumber]`, HTTP-pushes new work, and
  **verifies the submitted seal before acking**. fukuii's `EthMiningService` + `WorkNotifier`
  are already a faithful core-geth port; the one open gap is **SUBMITWORK-VERIFY-SEAL-01**
  (below). Keep getWork-only (`threads<0`, no local CPU grind) a co-equal first-class mode —
  it is the mode a pool operator runs.
- **PoS production path = empty-block-first + background-improve-until-getPayload** (geth/reth
  canonical). Build a valid empty payload synchronously so `getPayload` never returns nothing,
  then re-build richer/higher-fee candidates on an interval until the CL fetches or the slot
  deadline trips; best-fees-win selection between attempts.

**OPTIONAL menu (by node role):**
- **MEV builder / relay validation** (nethermind Flashbots) — validator/MEV nodes; validate
  external builder blocks, don't build monolithically.
- **Shutter encrypted inclusion** (nethermind) — anti-frontrunning validators; a layered
  `ITxSource` that front-injects decrypted txs after keyper key arrival.
- **BFT proposer** (besu IBFT2/QBFT) — private/consortium PoA production.
- **Internal CPU sealing** (core-geth/nethermind) — private PoW testnet / Olympia
  fork-activation testing; gate behind a single opt-in flag (nethermind's `Mining.Enabled=false`
  default), require an explicit coinbase (geth's `StartMining` contract).
- **Dev instant-seal** (all) — local/CI; besu's `fixeddifficulty` Ethash keeps it PoW-shaped.

**OBSOLETE.** **In-node Stratum server** — besu built it and it was *never adopted*; the
miner↔pool Stratum protocol belongs in separate pool software, not the node. Offer at most as
an explicit opt-in mode, never a default.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

fukuii is a **product family**: the node exposes the getWork/IPC production seam; the pool
software lives OUTSIDE the node (Stratum, vardiff, job distribution are pool-layer, not
node-layer). Block production maps onto the **DESIGNED-not-built** production-side seams noted
in the file-tree memo — **Sealer / ValidatorProvider / BlockInterface** — which are the fukuii
names for erigon's `Seal`-seam / besu's `MiningCoordinator` / reth's `PayloadBuilder`. The
target architecture: a consensus-blind assembly pipeline routing PoW (Ethash getWork) and PoS
(Engine-API payload) through one Seal-shaped seam, ECIP-1017 emission in the post-execution
reward hook.

- **SUBMITWORK-VERIFY-SEAL-01 (open, forge-owned).** fukuii's `EthMiningService.scala:155-185`
  returns `SubmitWorkResponse(true)` *before* verifying the PoW seal — a rig submitting an
  invalid nonce is told it succeeded, then the block is silently dropped in import. core-geth
  (`sealer.go:452-490`) runs `verifySeal` inline and returns `false`. A miner-facing getWork-
  **contract** divergence (not consensus — invalid blocks drop either way, no state-root
  effect), and mining pools are a top-priority fukuii use case. Fix: verify the seal before
  returning `true`, matching core-geth. (Verify against `EthashUtils`/`EthashEngine` for the
  existing `verifySeal` primitive before wiring.)
- **Internal CPU sealing = OPTIONAL(private-PoW-testnet) real feature**, not obsolete — fukuii's
  `EthashMiner`/`MockedMiner` + `EthashDAGManager` already provide the body; the work is mode
  wiring + a single opt-in flag, primarily for Olympia upgrade testing without GPU rigs.
- **Faker family gap** — fukuii's `MockedMiner` is the `NewFullFaker` analogue; a
  `NewFakeFailer(n)` ("fail PoW at block n") + `NewFakeDelayer` analogue and a `ModeTest`-style
  tiny-DAG real-PoW path are worth adding for reorg/negative/timing consensus tests.

These are seeds for Phase 3–4, not verdicts to act on here.
