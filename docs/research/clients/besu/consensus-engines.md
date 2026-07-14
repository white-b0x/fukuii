# besu — consensus-engines

_Commit/branch documented: `3fd233a4f93556e932f734d8feecbad4a047ff67` (branch `upstream`, `origin/upstream`).
Documented 2026-07-13. Read-only research; no fukuii source touched._

_Folds in the earlier B7.0 engine-axis research (`.local/docs/research-july/b7.0-engine-axis-decision.md`),
which already banked besu's `BesuController` / `ProtocolSchedule` / `TransitionProtocolSchedule` /
`CliqueProtocolSchedule` findings — cited and expanded here rather than re-derived._

## Architecture summary

besu is a fresh (non-geth-fork) JVM implementation and is fukuii's closest structural mirror. It cleanly
separates **two orthogonal axes** that fukuii conflates in one place:

1. **The fork/EVM ruleset** — `ProtocolSchedule` → one `ProtocolSpec` per hardfork milestone. This is the
   generic, mechanism-agnostic Ethereum ruleset (EVM, gas, header/body validators, difficulty, fee market,
   block processor). Built by `ProtocolScheduleBuilder` from `MilestoneDefinitions`.
2. **The consensus mechanism** — Clique / IBFT2 / QBFT / Ethash-PoW / merge-PoS. A mechanism does **not**
   subclass the schedule; it **decorates** it. `CliqueProtocolSchedule.create(...)` internally news up a
   `ProtocolScheduleBuilder` and, per fork, swaps in mechanism-specific pieces (block-header validator,
   difficulty calculator, `blockReward=0`, mining-beneficiary calculator). This
   **mechanism-decorates-fork-schedule** split is the single most important architectural lesson for
   fukuii's Batch 7.

Mechanism **selection** is driven off **positive genesis-config markers** (`isClique()` / `isIbft2()` /
`isQbft()` / `getPowAlgorithm()`), dispatched in `BesuController.Builder.fromGenesisFile` to a
per-mechanism `BesuControllerBuilder`. Each builder carries its own **consensus context**
(`CliqueContext` / `BftContext` / `PostMergeContext`) holding the mechanism's live parallel state
(validator set, epoch manager, block interface). Two independent **transition machineries** exist: TTD-gated
merge (`TransitionProtocolSchedule`, pre→post PoS) and block-number-gated PoA→PoA
(`ConsensusScheduleBesuControllerBuilder`, IBFT→QBFT).

## Key types / interfaces / files

### The fork-schedule layer (mechanism-agnostic)
- `ethereum/core/.../mainnet/ProtocolSchedule.java:31-83` — the schedule interface. Core lookup is
  `getByBlockHeader(ProcessableBlockHeader)` (`:33`) — **header-content-derived**, not a stored transition
  block. Holds both block-number and timestamp milestones (`putBlockNumberMilestone` `:57`,
  `putTimestampMilestone` `:59`).
- `ethereum/core/.../mainnet/ProtocolSpec.java:42-78` — one immutable fork ruleset: `EVM evm`,
  `GasCalculator`, `TransactionValidatorFactory`, `BlockHeaderValidator` (+ `ommerHeaderValidator`),
  `BlockProcessor`, `BlockImporter`, `DifficultyCalculator`, `Wei blockReward`,
  `MiningBeneficiaryCalculator`, `PrecompileContractRegistry`, `FeeMarket`. **These are exactly the fields a
  consensus mechanism overrides.**
- `ethereum/core/.../mainnet/ProtocolScheduleBuilder.java:42,100-169,204-218` — builds the ordered milestone
  list from `MilestoneDefinitions.createMilestoneDefinitions(specFactory, config)` and inserts each
  `ProtocolSpec`.
- `ethereum/core/.../mainnet/milestones/MilestoneType.java:17-19` — **the fork-dispatch axis is an enum**:
  `BLOCK_NUMBER` vs `TIMESTAMP`. A single schedule holds both kinds; the milestone itself carries which axis
  gates it. (Contrast fukuii's two `EvmConfig.forBlock` overloads — same idea, different encoding.)
- `ethereum/core/.../mainnet/ProtocolSpecAdapters.java` — the injection point: a
  `Map<Long, Function<ProtocolSpecBuilder, ProtocolSpecBuilder>>` of per-block modifiers that a mechanism
  supplies to override spec fields.

### The mechanism-decorates-schedule seam (THE lesson)
- `consensus/clique/.../CliqueProtocolSchedule.java:71-121` — `create(...)` builds a `specMap` of per-fork
  modifier functions (`:93-107`), wraps them in a `ProtocolSpecAdapters` (`:107`), and hands them to a
  `new ProtocolScheduleBuilder(...)` (`:109-120`). It **does not** re-implement the schedule.
- `CliqueProtocolSchedule.applyCliqueSpecificModifications:123-147` — the actual field swaps on each
  `ProtocolSpecBuilder`: clique `BlockHeaderValidator` (`:131-138`), `CliqueDifficultyCalculator`
  (`:142`), `blockReward(Wei.ZERO)` + `skipZeroBlockRewards(true)` (`:143-144`),
  `miningBeneficiaryCalculator(CliqueHelpers::getProposerOfBlock)` (`:145`), `CliqueBlockHeaderFunctions`
  (`:146`). Everything else (EVM, gas, tx validation, block processing) falls through to mainnet.

### Mechanism selection (positive markers, no fallthrough)
- `config/.../JsonGenesisConfigOptions.java:137,141-162,412-413` — positive predicates:
  `isEthHash()` (`:137`, `ethash`/`fixeddifficulty` key present), `isIbftLegacy()`, `isClique()`,
  `isIbft2()`, `isQbft()`, `isPoa() = qbft||clique||ibft2||ibftLegacy` (`:161-162`), and
  `getPowAlgorithm()` returns `ETHASH` or `UNSUPPORTED` (`:412-413`). **Each mechanism has an affirmative
  key; there is no "else means Ethash" fallthrough** — the anti-pattern go-ethereum's configurator has and
  fukuii replicated.
- `app/.../controller/BesuController.java:344-397` — `fromGenesisFile` dispatch: consensus-migration first
  (`:348`), then `getPowAlgorithm() != UNSUPPORTED` → `MainnetBesuControllerBuilder` (`:355-356`), `isIbft2()`
  → `IbftBesuControllerBuilder` (`:357-358`), `isIbftLegacy()` → **hard error, IBFT1 unsupported** (`:359-361`),
  `isQbft()` → `QbftBesuControllerBuilder` (`:362-363`), `isClique()` → `CliqueBesuControllerBuilder`
  **only if TTD present, else hard error "Clique mining no longer supported"** (`:364-372`). TTD present →
  wrap in `TransitionBesuControllerBuilder(builder, MergeBesuControllerBuilder)` (`:384-395`).
- Consensus contexts (parallel live state, one per mechanism):
  `consensus/clique/.../CliqueContext.java:27-76` (holds `ValidatorProvider` + `EpochManager` +
  `BlockInterface`); `BftContext` (IBFT/QBFT); `PostMergeContext` (merge). All implement a common
  `ConsensusContext` with a `.as(Class)` downcast (`:72-75`).

### The three B7.1 seams (Sealer / ValidatorProvider / BlockInterface)

**1. Sealer / block-proposer analogue.**
- `app/.../controller/CliqueBesuControllerBuilder.java:63-72` — `createMiningCoordinator` now returns a
  `NoopMiningCoordinator` (besu **removed** Clique block production; sync-only). Historically this was a
  `CliqueMinerExecutor`/`CliqueBlockCreator`.
- The live sealer analogue for a *producing* PoA is BFT:
  `consensus/common/.../bft/blockcreation/BftBlockCreatorFactory.java:171-180` — builds the mechanism
  `extraData` (validator list + vote + proposer seal) and encodes it into the header via
  `bftExtraDataCodec.encode(extraData)`. This is the "insert the proposer/validator seal into the header
  before publishing" step — fukuii's designed **G1 `Sealer`** seam.

**2. Validator-set provider (`ValidatorProvider`) — authorized signers at block N.**
- `consensus/common/.../validator/ValidatorProvider.java:26-82` — the interface:
  `getValidatorsAtHead()`, `getValidatorsAfterBlock(header)`, `getValidatorsForBlock(header)`,
  `getVoteProviderAtHead()`, `nodeIsValidator(nodeKey)`. fukuii's designed **G2 `ValidatorProvider`** seam.
- Three implementation modes:
  - **block/vote mode** — `consensus/common/.../validator/blockbased/BlockValidatorProvider.java:31-79`
    (`nonForkingValidatorProvider` `:63` / `forkingValidatorProvider` `:46`). Reads the validator set from
    block extraData + running vote tally (`VoteTallyCache`, `VoteTallyUpdater`). This is Clique's mode and
    IBFT/QBFT genesis-list mode.
  - **contract mode** — `consensus/qbft/.../validator/TransactionValidatorProvider.java:5-24` — reads
    validators from an on-chain smart contract via `ValidatorContractController`.
  - **forking mode** — `consensus/qbft/.../validator/ForkingValidatorProvider.java` — switches between
    block-mode and contract-mode per fork block (QBFT's validator-source migration).
- Clique wires the non-forking block-based provider in
  `CliqueBesuControllerBuilder.createConsensusContext:104-118` (`BlockValidatorProvider.nonForkingValidatorProvider(...)`
  `:111-112`).

**3. extraData encode/decode (`BlockInterface`) — proposer / vote / signer-list.**
- `consensus/common/.../BlockInterface.java:25-58` — the interface:
  `getProposerOfBlock(header)`, `extractVoteFromHeader(header)`, `validatorsInBlock(header)`. fukuii's
  designed **G3 `BlockInterface`** seam.
- `consensus/clique/.../CliqueBlockInterface.java:31-108` — Clique impl: proposer via ecrecover on the
  header seal (`getProposerOfBlock` `:51-59`), vote extraction from `coinbase`+`nonce`
  (`ADD_NONCE`/`DROP_NONCE`, `extractVoteFromHeader` `:61-72`), signer list from decoded extraData
  (`validatorsInBlock` `:95-98`), and `createHeaderBuilderWithVoteHeaders` (`:81-93`) to *write* a vote.
- `consensus/clique/.../CliqueExtraData.java:40-227` — the concrete RLP-ish codec:
  `EXTRA_VANITY_LENGTH=32` (`:44`), `decode(header)` (`:95`), `encode()` (`:165`),
  `encodeUnsealed(vanity, validators)` (`:176`), `createGenesisExtraDataString(validators)` (`:227`).
  Proposer seal recovered lazily via `CliqueBlockHashing.recoverProposerAddress` (`:73-74`).
- BFT's analogue is `consensus/common/.../bft/BftBlockInterface.java` + a pluggable `BftExtraDataCodec`.

### The two transition machineries

**Merge (TTD-gated, PoW/PoA → PoS):**
- `consensus/merge/.../TransitionProtocolSchedule.java:37-146` — holds `preMergeProtocolSchedule` +
  `postMergeProtocolSchedule` + `MergeContext`; `getByBlockHeader` (`:87-92`) dispatches via
  `TransitionUtils.dispatchFunctionAccordingToMergeState`. The reorg-aware variant
  `getByBlockHeaderWithTransitionReorgHandling` (`:100-146`) resolves pre/post by finalized-block state and
  a **content-derived** TTD test (`isTerminalProofOfWorkBlock`, `:131-132`) — **not** a stored transition
  block number.
- Wired in `BesuController.java:384-395`: `new TransitionBesuControllerBuilder(builder, new MergeBesuControllerBuilder())`.
  **Only applied when TTD is present** — a never-merging chain (ETC) is never force-wrapped.

**PoA → PoA (block-number-gated, IBFT → QBFT):**
- `app/.../controller/BesuController.java:399-421` — `createConsensusScheduleBesuControllerBuilder` builds a
  `Map<Long, BesuControllerBuilder>`: block 0 → IBFT2 (or IBFT-legacy) builder (`:404-413`), QBFT start block
  → `QbftBesuControllerBuilder` (`:415-417`), fed to `ConsensusScheduleBesuControllerBuilder` (`:419`).
- `app/.../controller/ConsensusScheduleBesuControllerBuilder.java:83-202` — composes the sub-builders:
  `createProtocolSchedule` (`:161-168`) maps each `(block → builder.createProtocolSchedule())` into
  `ForkSpec`s and combines them (`combinedProtocolScheduleFactory`); `createConsensusContext` (`:185-202`)
  wraps a `ForksSchedule<ConsensusContext>` in a `MigratingConsensusContext`; and it registers **both** wire
  protocols during migration (`:232-238`, IBF/1 pre + istanbul/100 post) so peers can talk across the
  boundary. This `Map<blockNumber, builder>` is the direct reference for B7.0-c's `EngineSchedule` + B7.2.

### Ethash + EIP-1559 (ETH-family ruleset, brief)
- Ethash PoW is selected by `getPowAlgorithm()==ETHASH` → `MainnetBesuControllerBuilder`; the difficulty /
  header-validation live in the mainnet `ProtocolSpec` chain, not a decorator (PoW is besu's *default*
  ruleset, so it needs no override layer). EIP-1559 is a `FeeMarket`/`BaseFeeMarket` field on the
  `ProtocolSpec` (`ProtocolSpec.java:78`), swapped in at the London milestone via the normal milestone
  machinery — the same field a PoA schedule can override.

## Design decisions & rationale

- **Decorator over inheritance.** A mechanism supplies a `Map<block, ProtocolSpecBuilder→ProtocolSpecBuilder>`
  rather than subclassing the schedule. This keeps the ~20 mainnet forks (Frontier→Prague) defined **once**;
  Clique/BFT only override the 4–6 fields that actually differ (header validation, difficulty, reward,
  beneficiary). Trade-off: the mechanism must know which `ProtocolSpecBuilder` setters to touch, but gains
  automatic EVM/gas/fork currency for free.
- **Positive-marker selection, no fallthrough.** Every mechanism has an affirmative genesis key. besu deletes
  the "else = PoW" trap; an unrecognized config with no TTD is an explicit warn→PoS, and Clique/IBFT-legacy
  *mining* are hard errors, not silent degradations.
- **Consensus context as parallel live state.** The validator set / epoch / block-interface don't live in the
  schedule (which is pure fork rules) — they live in a per-mechanism `ConsensusContext` carried alongside the
  blockchain, downcast via `.as(Class)`. Keeps the fork ruleset stateless and the mechanism state explicit.
- **Content-derived transitions.** Both merge and (implicitly) PoA→PoA resolve which ruleset applies from the
  header/chain state, never from a single stored "transition happened here" pointer — which survives reorgs
  across the boundary.
- **Mining removed where the ecosystem moved on.** Clique block production is a `NoopMiningCoordinator`
  (`CliqueBesuControllerBuilder:71`) and Clique/IBFT-legacy mining are hard errors — besu keeps *sync*
  compatibility for legacy chains but refuses to *produce*, steering operators to QBFT/PoS.

## Notable patterns (the reusable idea)

- **Mechanism-decorates-fork-schedule** — the headline pattern. `ConsensusMechanism.create(...)` wraps a
  generic `ProtocolScheduleBuilder` and injects per-fork field overrides via a `ProtocolSpecAdapters`
  map. Fork ruleset and consensus mechanism are orthogonal layers, composed, never merged.
- **Three explicit PoA seams** — `Sealer` (write proposer seal into extraData) / `ValidatorProvider`
  (authorized-signer set at block N, in block/contract/forking modes) / `BlockInterface` (extraData
  codec + proposer/vote extraction). These map **1:1** onto fukuii's Batch-5-deferred G1/G2/G3.
- **`Map<block, builder>` for mechanism transitions** — a first-class, ordered schedule of *whole
  mechanisms* (not just fork rules), combined into one `ProtocolSchedule` + a `MigratingConsensusContext`.
  The reference shape for fukuii's `EngineSchedule`.
- **Dual-protocol registration across a migration boundary** — during IBFT→QBFT both wire protocols are
  advertised so peers on either side can still gossip (`ConsensusScheduleBesuControllerBuilder:232-238`).

## Authority note

**besu is THE multi-consensus / PoA authority** (Clique, IBFT2, QBFT) and the JVM structural mirror closest
to fukuii — this file is the **primary structural reference for Batch 7** (B7.1 Clique, B7.2 IBFT/QBFT) and
for B7.0-c's `EngineSchedule`. Its `CliqueProtocolSchedule`-decorates-`ProtocolScheduleBuilder` shape and its
Sealer/ValidatorProvider/BlockInterface seams are exactly fukuii's designed G1/G2/G3.

besu is **also a strong ETH-family authority** (Ethash PoW, EIP-1559, the full mainnet fork schedule) —
secondary to go-ethereum for canonical ETH baseline, but a valid cross-check for header/fee-market/EVM
behavior. It is **not** the ETC authority: core-geth remains the sole authority for ETChash/ECIP-1017/1099/
1111/1122. besu has no ECIP awareness.

## Gotchas / anti-patterns / things they later changed

- **Clique/IBFT-legacy block production removed.** `CliqueBesuControllerBuilder.createMiningCoordinator`
  returns `NoopMiningCoordinator` (`:71`); Clique mining without TTD (`BesuController:365-371`) and IBFT1
  (`:359-361`) are hard errors. When mining B7.1 Clique in fukuii, do **not** copy besu's current sealer
  path (it's stubbed) — reconstruct from the BFT block-creator / extraData codec pattern instead.
- **`TransitionBesuControllerBuilder` is marked for removal.** `BesuController:390-392` has a TODO to fold it
  into a vanilla `MergeBesuControllerBuilder` once transition is complete (issue #2897). The *composition*
  pattern is durable; the specific Transition* wrapper classes are transitional scaffolding.
- **`DEFAULT_CHAIN_ID = 4`** hardcoded in `CliqueProtocolSchedule:50` (Rinkeby-era leftover) — used only when
  genesis omits chainId; a footgun to avoid replicating.
- **Two dispatch encodings for the same idea.** Fork axis is `MilestoneType.{BLOCK_NUMBER,TIMESTAMP}` on one
  schedule; mechanism-transition axis is a separate `Map<block, builder>` and a separate TTD-gated wrapper.
  besu never unified these into one `ForkCondition` (reth did) — a known structural seam, not a bug.
- **`ConsensusScheduleBesuControllerBuilder` self-describes as "a placeholder class for the QBFT migration
  logic"** (`:80-83`) — it works, but besu treats the whole IBFT→QBFT migration path as provisional. Cite the
  *shape* (Map<block,builder> + MigratingConsensusContext + dual wire protocols), not it as polished
  finished art.
