# besu — block-execution
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

Scope: block validation, the transaction-apply loop, receipt generation, rewards,
withdrawals, and EIP-7685 system-call request processing — as they are bundled and
dispatched per hard fork. besu HEAD (Feb 2026+) has **removed ETC**, so ECIP-1017 block
reward code is gone from upstream; historical ETC block-execution is covered separately in
[`history-pow-etc.md`](history-pow-etc.md) and not repeated here. This file documents besu's
_current_ ETH-PoS + PoA block-execution structure.

## Architecture summary

besu factors "what a block means at height/time X" into one immutable value object,
`ProtocolSpec`, and a lookup service, `ProtocolSchedule`, that returns the right
`ProtocolSpec` for a given block header. Everything block-execution touches — the EVM, gas
calculator, transaction processor, the block/header/body validators, the block processor
(reward + tx loop), difficulty calculator, block reward, fee market, withdrawals processor,
request-processor coordinator, pre-execution processor — is a **field of `ProtocolSpec`**
(`ProtocolSpec.java:38-95`, ~30 collaborators). A fork is therefore not a set of `if
(blockNumber >= X)` branches scattered through the processor; it is a distinct
`ProtocolSpec` instance, built once at startup and looked up by header.

The build pipeline is: `ProtocolScheduleBuilder.createProtocolSchedule()` →
`MilestoneDefinitions.createMilestoneDefinitions()` produces an ordered list of
`(HardforkId, MilestoneType, activationValue, specBuilderFn)` → each is materialized into a
`ProtocolSpec` via `ProtocolSpecBuilder.build()` → inserted into a `DefaultProtocolSchedule`
as either a block-number or timestamp milestone. At runtime the block processor calls
`protocolSchedule.getByBlockHeader(header)` once per block and drives the returned spec's
collaborators.

The tx-apply loop itself lives in `AbstractBlockProcessor.processBlock()` and is
**fork-agnostic** — it never names a fork. All fork-specific behavior arrives through the
`ProtocolSpec` it was handed (which gas-accounting strategy, whether a withdrawals processor
is present, whether a request coordinator is present, what the block reward is). That is the
core structural lesson for fukuii.

## Key types / interfaces / files

- `ethereum/mainnet/ProtocolSpec.java:38` — the per-fork bundle. Immutable; ~30 final
  fields (EVM, GasCalculator, MainnetTransactionProcessor, BlockHeaderValidator,
  BlockBodyValidator, BlockProcessor, BlockValidator, DifficultyCalculator, `Wei blockReward`,
  MiningBeneficiaryCalculator, FeeMarket, WithdrawalsValidator/Processor, RequestsValidator,
  `Optional<RequestProcessorCoordinator>`, PreExecutionProcessor, `boolean isPoS`,
  BlockGasAccountingStrategy, …). Pure getters, no logic.
- `ethereum/mainnet/ProtocolSchedule.java:31` — the lookup interface. `getByBlockHeader(header)`
  is the hot path; also `getForNextBlockHeader(parent, timestamp)` (used by block
  production), `milestoneFor(HardforkId)`, `putBlockNumberMilestone`/`putTimestampMilestone`.
- `ethereum/mainnet/DefaultProtocolSchedule.java:40` — the impl. Holds a `NavigableSet<ScheduledProtocolSpec>`
  sorted **descending** by milestone; `getByBlockHeader` (line 67) walks the set and returns
  the first spec whose boundary the header is on-or-after — a single unified scan that works
  for both block-number and timestamp forks because each `ScheduledProtocolSpec` knows how to
  test its own boundary.
- `ethereum/mainnet/ScheduledProtocolSpec.java:23` — `(ProtocolSpec, activation)` pair with
  two implementations: `BlockNumberProtocolSpec` (`isOnOrAfterMilestoneBoundary` compares
  `header.getNumber()`, line 102) and `TimestampProtocolSpec` (compares `header.getTimestamp()`,
  unsigned, line 65). **This is where PoW/block-number and PoS/timestamp dispatch is unified
  behind one interface.**
- `ethereum/mainnet/milestones/MilestoneType.java:17` — `enum { BLOCK_NUMBER, TIMESTAMP }`.
  Just two values; the entire fork-dispatch dichotomy reduces to this tag.
- `ethereum/mainnet/milestones/MilestoneDefinitions.java:43` — the milestone list. Note the
  hard split: `createMainnetBlockNumberMilestones` (Frontier…Paris, lines 60-113) then
  `createMainnetTimestampMilestones` (Shanghai onward, line 122+). The PoW→PoS boundary
  (Paris) is exactly where the list switches from block-number to timestamp milestones.
- `ethereum/mainnet/ProtocolScheduleBuilder.java:86` — `initSchedule()` builds the flattened
  milestone map, applies `ProtocolSpecAdapters` (see below), then calls `addProtocolSpec`
  (line 244) which `switch`es on `MilestoneType` to route each spec to the right milestone
  map. Also handles the DAO-fork special insertion (lines 142-167).
- `ethereum/mainnet/ProtocolSpecAdapters.java:24` — the **decorator layer**. A
  `Map<Long, Function<ProtocolSpecBuilder, ProtocolSpecBuilder>>`; `getModifierForBlock` uses
  a `TreeSet.floor` to find the modifier in effect at a given block/timestamp. Lets a
  consensus mechanism (BFT/Clique) or a private-network override transform the base mainnet
  spec-builder at a chosen activation point without editing `MainnetProtocolSpecs`.
- `ethereum/mainnet/MainnetProtocolSpecs.java:161` — the milestone catalog: one static
  `xxxDefinition(...)` per fork returning a `ProtocolSpecBuilder`. Each fork **calls the
  previous fork's definition and mutates the delta** (e.g. `shanghaiDefinition` calls
  `parisDefinition` then flips warm-coinbase, swaps the gas calculator, adds the withdrawals
  processor — lines 719-780). `parisDefinition` (line 688) is the PoW→PoS pivot:
  `.blockReward(Wei.ZERO).skipZeroBlockRewards(true).isPoS(true)` +
  `PROOF_OF_STAKE_DIFFICULTY`.
- `ethereum/mainnet/ProtocolSpecBuilder.java:337` — `build()` with `checkNotNull` guards on
  every required collaborator (lines 338-345+), then constructs the immutable `ProtocolSpec`.
  Fluent builder; the `xxxDefinition` methods are just recipes over it.
- `ethereum/mainnet/AbstractBlockProcessor.java:67` — the fork-agnostic execution engine.
  `processBlock()` (line 205) is the tx-apply loop: pre-execution processor → per-tx
  `getTransactionProcessingResult` → commit + receipt + cumulative gas → blob-gas check →
  withdrawals → EIP-7685 requests → `rewardCoinbase` (abstract) → BAL validation →
  `worldState.persist`. Reads all fork behavior off the `ProtocolSpec` it fetches at line 230.
- `ethereum/mainnet/MainnetBlockProcessor.java:30` — the concrete subclass; supplies only
  `rewardCoinbase` (line 72: coinbase + ommer rewards, `MAX_GENERATION=6` ommer check). The
  reward math is the _only_ thing that varies from the abstract loop. Nested
  `MainnetBlockProcessorBuilder` (line 109) is the `ProtocolSpecBuilder.BlockProcessorBuilder`
  factory wired into each fork definition (choice of parallel vs serial at
  `MainnetProtocolSpecs.java:211`).
- `ethereum/MainnetBlockValidator.java:48` (package `org.hyperledger.besu.ethereum`, not
  `mainnet`) — implements `BlockValidator`; composes the spec's `BlockHeaderValidator` +
  `BlockBodyValidator` + `BlockProcessor` + `BlockAccessListValidator`. This is the
  **validation-side** entry (import path); block **production** uses the same `ProtocolSpec`
  collaborators through the block-creation package — the split reuses one spec bundle for
  both directions.
- `ethereum/mainnet/MainnetTransactionProcessor.java` (861 lines) — the per-tx engine
  (intrinsic gas, nonce/balance checks, EVM message frame, refund, fee/tip settlement).
  Built per fork via `.transactionProcessorBuilder(...)` with fork-specific flags
  (`warmCoinbase`, `clearEmptyAccounts`, `CoinbaseFeePriceCalculator.frontier()` vs `.eip1559()`).
- `ethereum/mainnet/requests/RequestProcessorCoordinator.java:31` — EIP-7685 system-request
  fan-out. Holds an `ImmutableSortedMap<RequestType, RequestProcessor>`; `process()` runs each
  processor and collects `List<Request>`. `noOp()` for pre-Prague / PoA-without-system-contracts.
- `ethereum/mainnet/requests/MainnetRequestsProcessor.java:21` — `pragueRequestsProcessors`
  wires the three EL-triggerable requests: WITHDRAWAL (EIP-7002) and CONSOLIDATION (EIP-7251)
  as `SystemCallRequestProcessor` against their predeploy addresses, and DEPOSIT (EIP-6110)
  as a `DepositRequestProcessor` (log-scraping). 
- `ethereum/mainnet/requests/SystemCallRequestProcessor.java:29` — generic "call a system
  contract, wrap output as a `Request`" processor; the reusable primitive behind 7002/7251.
- `PreExecutionProcessor` (referenced `ProtocolSpec.java:84`, wired per fork) — the pre-tx
  system calls (EIP-2935 block-hash history, EIP-4788 beacon-root) run before the tx loop at
  `AbstractBlockProcessor.java:265`.

## Design decisions & rationale

- **Fork = an immutable bundle, not scattered conditionals.** All fork variation is captured
  as _which collaborator instances_ populate a `ProtocolSpec`. The processor code has zero
  fork branches. Adding a fork = add one `xxxDefinition` + one milestone entry; nothing in the
  execution loop changes. This is besu's central abstraction and the reason it scales to
  ~25 forks across two consensus families without the loop rotting.
- **Definitions form an inheritance chain of deltas.** Each `xxxDefinition` starts from the
  prior fork's builder and overrides only what changed. Keeps each fork's diff readable and
  co-located, at the cost of a deep call chain (Osaka → Prague → Cancun → … → Frontier).
- **One schedule, two milestone types, one scan.** Rather than separate block-number and
  timestamp schedules, `DefaultProtocolSchedule` keeps a single descending set and delegates
  the boundary test to each `ScheduledProtocolSpec`. `getByBlockHeader` is polymorphic over
  BLOCK_NUMBER vs TIMESTAMP — the caller never knows which kind of fork it hit.
- **Mechanism decorates the schedule (ProtocolSpecAdapters).** Consensus engines (Clique/BFT)
  and network overrides don't fork `MainnetProtocolSpecs`; they register a
  builder→builder modifier at an activation point. The mainnet catalog stays the single source
  of fork truth; the mechanism layers on top. (§1a called this besu's key pattern.)
- **Reward is the one abstract seam of the processor.** `AbstractBlockProcessor` is concrete
  for the whole loop except `rewardCoinbase`. PoS sets `blockReward = Wei.ZERO,
  skipZeroBlockRewards = true` so the same `MainnetBlockProcessor` produces no issuance
  post-merge — no separate PoS processor needed.
- **System calls / requests are data-driven.** EIP-7685 requests are a `RequestType`→processor
  map, so enabling a request type per fork is a map entry, and PoA-without-system-contracts
  degrades to `noOp()` (`MainnetProtocolSpecs.java:972-975`) instead of crashing.

## Notable patterns (the reusable idea)

**The ProtocolSpec bundle + MilestoneType-tagged schedule is the single most transferable
pattern for fukuii.** Concretely:

1. **Bundle every fork-varying collaborator into one immutable per-fork value object**
   (`ProtocolSpec`), built once, looked up by header. This replaces "the EVM/validator/reward
   asks `EvmConfig.forBlock(...)` mid-execution and branches" with "the execution loop is
   handed a fully-resolved bundle and just calls methods on it."
2. **Unify block-number and timestamp fork dispatch behind one `ScheduledProtocolSpec`
   interface tagged by `MilestoneType.{BLOCK_NUMBER, TIMESTAMP}`**, so one schedule and one
   `getByBlockHeader` scan serve both PoW (block-number) and PoS (timestamp) families. This is
   the direct structural answer to fukuii's two `EvmConfig.forBlock` overloads (2-arg
   block-number for ETC, 3-arg timestamp for ETH): besu does not have two dispatch methods, it
   has one method over two boundary implementations.
3. **Layer mechanism/network variation as a `ProtocolSpecAdapters` decorator** over the shared
   fork catalog, keeping per-family fork definitions authoritative and mechanism concerns
   (PoA, private-network tweaks) additive — the multi-network use-case lens.

### Use-case lens
- **Multi-network:** `ProtocolSpec`-per-fork + `MilestoneDefinitions` per network means a new
  network is a new milestone list + optional adapters, not new execution code. A PoW family
  and a PoS family coexist because dispatch is `MilestoneType`-polymorphic, not per-family.
- **Validator (production vs validation split):** the same `ProtocolSpec` bundle backs both
  `MainnetBlockValidator` (import/validate) and the block-creation path (produce). Header
  validation, reward, and system calls are defined once and reused in both directions;
  `getForNextBlockHeader(parent, timestamp)` gives the producer the correct spec for a
  not-yet-existing block.

## Authority note

besu = fukuii's closest JVM block-processing structural mirror; its
`ProtocolSpec`/`ProtocolSchedule`/`ProtocolSpecBuilder` decomposition is the reference for
how to bundle per-fork collaborators in a JVM client. **However, go-ethereum / core-geth are
the behavior authorities**: for ETH/Sepolia PoS semantics use go-ethereum; for ETC/ECIP
(ECIP-1017 emission, ETChash, Olympia opcodes) use core-geth. besu HEAD no longer carries
ETC, so it is a _structural_ reference for ETC work, never a behavioral one.

## Gotchas / anti-patterns / things they later changed

- **ETC removed from upstream.** No ECIP-1017 reward code, no ETC milestone list in besu HEAD.
  `MainnetProtocolSpecs`/`MilestoneDefinitions` are ETH-mainnet-only; PoA (Clique/BFT) is the
  only non-PoW-ETH mechanism still present. Do not mine besu HEAD for ETC block-execution —
  use `history-pow-etc.md`.
- **`MainnetBlockValidator` lives in `org.hyperledger.besu.ethereum`, not `…ethereum.mainnet`.**
  Easy to miss when tracing the validation entry point vs the `mainnet`-packaged collaborators.
- **Deep definition inheritance chain.** Reading what a late fork actually configures means
  walking back through every prior `xxxDefinition`. A single misplaced override (e.g. forgetting
  to re-set a builder field a parent set) silently inherits the wrong value — the `checkNotNull`
  guards in `build()` only catch _missing_ collaborators, not wrong ones.
- **`ProtocolSpecAdapters` insertion is subtle.** `initSchedule` re-parents adapter modifiers at
  `floorEntry(modifierBlock)` (`ProtocolScheduleBuilder.java:108-126`); an adapter registered at
  a block that isn't a real milestone still inserts a new spec entry there. Powerful but easy to
  create an unintended extra milestone.
- **Copy-constructor milestone-map bug (fixed, documented in-code).**
  `DefaultProtocolSchedule` copy constructor now explicitly copies the `milestones` map
  (`DefaultProtocolSchedule.java:54-65`); before the fix, `BftProtocolSchedule` wrapping an
  existing schedule kept an empty milestones map, so `milestoneFor(HardforkId)` returned empty
  and Engine API `forkchoiceUpdated` rejected every payload at the Cancun/Amsterdam boundary
  with `UNSUPPORTED_FORK`. A concrete cautionary tale about the schedule carrying two parallel
  representations (the sorted spec set _and_ the `HardforkId→milestone` map) that must stay in
  sync.
- **`isOnMilestoneBoundary` (==) vs `isOnOrAfterMilestoneBoundary` (>=).** Two different
  predicates on `ScheduledProtocolSpec`; lookup uses on-or-after, exact-boundary is used for
  transition detection. Mixing them up mis-dispatches the activation block itself.
