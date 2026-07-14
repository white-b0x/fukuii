# Observations — consensus-engines
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/consensus-engines.md + initial-assessment §1a-c + consensus-methods-catalog + b7.0-engine-axis-decision._

This is the Phase-2 cross-client comparison for the **consensus-engines** subsystem: how each
reference client abstracts a consensus *family*, keys engine selection, dispatches forks, models
the merge, and where each leaks network identity into an otherwise-neutral seam. It builds on
`initial-assessment.md` §1a (family-abstraction spectrum), §1b (merge/transition), §1c
(fork-dispatch unification), folds in the already-consolidated `b7.0-engine-axis-decision.md`
(engine-selection axis across all 6), and pulls method breadth from
`topics/consensus-methods-catalog.md`. It does not re-research repos — every per-client claim is
cited to that client's `consensus-engines.md`.

**Authority model (per Phase-0, re-stated here so the table reads correctly):** core-geth =
ETC/PoW/ETChash/ECIP byte-authority (sole living, deprecated Sept-2024); go-ethereum = ETH/PoS
baseline + the canonical `consensus.Engine` interface + EIP reference; besu = multi-consensus/PoA
authority (Clique/IBFT2/QBFT) + fukuii's closest JVM structural mirror; erigon = sidechain/Bor +
the EngineReader/Writer read-only-consensus split; nethermind = self-declaring runtime plugin
registry; reth = compile-time-generics SDK + `ForkCondition`-unified fork dispatch.

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | Authoritative |
|---|---|---|---|---|---|---|---|
| **Family-abstraction mechanism** | single-family `consensus.Engine` (11-method iface); 3 concrete engines (ethash/clique/beacon) | inherits geth's `consensus.Engine`; adds full ETC ruleset as config-gated params on the same 3 engines | `ProtocolSchedule` (fork rules) **decorated** by a mechanism; per-mechanism `BesuControllerBuilder` + `ConsensusContext` | `rules.Engine = EngineReader + EngineWriter` split; 4 engines (ethash/aura/bor/merge); sidechain = a `polygon/` module | self-declaring `IConsensusPlugin` per assembly; DI `IModule` wired by framework | compile-time `NodeTypes` + `ConsensusBuilder` associated types; family = a Rust crate | **nethermind** (runtime openness) / **reth** (compile-safety) — fukuii wants both |
| **Engine-selection keying** | `else-means-ethash` **fallthrough** (anti-pattern) | **positive keying** `GetConsensusEngineType` (Ethash/Clique/Lyra2/Unknown); but `CreateConsensusEngine` layer still has the geth fallthrough | **positive genesis markers** `isClique()/isQbft()/getPowAlgorithm()`, no fallthrough; closed hand-maintained if/else dispatch | **positive type-switch** on parsed config type, `panic` on unknown (no fallthrough) | **config-derived** from which `IChainSpecEngineParameters` is present; flat `string SealEngineType` tag (open, not enum) | **type-level** — family fixed by the `NodeTypes` impl the binary is built from | **core-geth / besu / erigon / nethermind** all model positive keying; geth is the negative example |
| **Fork-dispatch (block# vs timestamp)** | split: block-number (`isBlockForked`) pre-merge, timestamp (`isTimestampForked`) post-merge, both in `ChainConfig` | **block-number only** (`IsEnabled(getter, block)`) — ETC never merges, no timestamp axis in the PoW path | **`MilestoneType.{BLOCK_NUMBER,TIMESTAMP}` enum** on one schedule; milestone carries its own axis | split block/timestamp in `ChainConfig`, same as geth | **`AddTransitions(SortedSet blockNumbers, SortedSet timestamps)`** — one method, both axes | **`ForkCondition{Block,Timestamp,TTD,Never}`** — one enum unifies **every** axis; `Hardforks` capability queries | **reth** (`ForkCondition`) — cleanest single-type unification; **nethermind** `AddTransitions` + **besu** `MilestoneType` are the two-encoding runners-up |
| **Merge/transition model** | `beacon.New(ethone)` **decorator**, content-derived (`difficulty==0`); now **mandatory** (PoS-only boot) | same `beacon.New` wrap but **inert** on ETC (TTD unset ⇒ never trips PoS) | `TransitionProtocolSchedule` (TTD-gated, content-derived) + separate `Map<block,builder>` for PoA→PoA (IBFT→QBFT) | `merge.New(eng)` decorator, **conditional** (only when `TTD != nil`) — the shape fukuii wants | `MergePlugin` **composes/co-activates** (decorates base DI regs), TTD-gated via `HasTtd()`; not a wrapper object | validation-only; production entirely behind Engine API; merge = a `ForkCondition::TTD` + `is_paris_active` query | **erigon** (conditional decorator) for fukuii's shape; **go-ethereum** for the canonical content-derived heuristic |
| **PoW support** | **dropped standalone PoW** — `ethash.Seal` panics, no `verifySeal`/hashimoto; historical-header verifier only | **full real PoW**: ethash sealing, difficulty bomb + ECIP-1010/1041, ECIP-1017 emission, ECIP-1099 ETChash | had Ethash mining, **removed 2026**; PoW is the default un-decorated mainnet `ProtocolSpec` | ethash present, **mining removed 2021** (getWork-serve + validate kept); `PoW` sub-iface (`Hashrate()`) | **✓ opt-in vanilla Ethash** (still self-mines, `if miningConfig.Enabled`) | **none** — no ethash/pow crate, no seal method (negative data point) | **core-geth** (sole living ETC/PoW/ETChash byte-authority); nethermind = vanilla-Ethash cross-check |
| **PoA support** | Clique validate+seal (difficulty 1/2 = out/in-turn signer scheduling) | Clique validate+**seal** (ETC-lineage sealing authority) | **Clique validate-only (seal removed → `NoopMiningCoordinator`)**; IBFT2 validate-only; **QBFT validate+seal**; the 3 seams (Sealer/ValidatorProvider/BlockInterface) | **AuRa** (Authority Round, OpenEthereum lineage); no Clique | Clique validate+seal, **AuRa** (richest impl), plus OP/Taiko/XDC peer families | **none** — validation-only, no PoA sealing | **besu** (seam structure, Clique/IBFT2/QBFT config+validation) + **core-geth** (Clique *sealing*, since besu stubbed it) |
| **Family-neutrality / leaks** | `else-means-ethash` fallthrough; no multi-engine guard (silently prefers Clique) | fallthrough survives at `CreateConsensusEngine` layer; unconditional (but inert) beacon wrap = latent footgun | clean two-axis split; `DEFAULT_CHAIN_ID=4` (Rinkeby leftover); two un-unified dispatch encodings | **`FrozenBorBlocks` leaks into shared `ChainHeaderReader`**; bor's config-is-interface breaks pointer convention; `is`-a-plain-ethash double-guard footgun | flat string tag (typo → runtime failure, no compile check); ordering is a hand-maintained string list; two guards for one invariant | **`is_optimism()` in the shared trait**; one-family-per-binary (can't runtime-switch) | **nethermind/reth** both leak one L2-ism; the invariant is "inject knobs *through* the family typeclass, never into shared readers" |

## Approach catalog (use-case-aware)

Verdicts: **DEFAULT** = fukuii's baseline best practice · **OPTIONAL(role)** = offer for a named
use-case (enterprise / custody / validator / light / archival / multi-network) · **OBSOLETE** =
understood-but-discarded. Use-case taxonomy per `README.md`'s omni-client lens.

| Approach | Clients using it | Good for (use-case / node-role) | Verdict | Why |
|---|---|---|---|---|
| **Compile-time-generics registry** | reth (`NodeTypes` + `ConsensusBuilder`) | type-safe SDK builds; downstream L2 crates (op-reth) | **OPTIONAL(compile-safety lens only)** | Full type safety + zero-cost abstraction, but **one crate/binary per family** — fails fukuii's single-JVM-binary, runtime-network-selection premise. Adopt the *type-safety idea* (Scala `given` typeclass), not the one-binary constraint. |
| **Runtime plugin registry** | nethermind (`IConsensusPlugin`, reflection + `EmbeddedPlugins`) | single-binary multi-network; drop-in third-party families; enterprise | **DEFAULT (as the runtime-openness target)** | Strongest "add a family with zero shared-dispatch edits," proven across 8+ mechanisms. But reflection + Autofac DI is un-idiomatic in Scala/Pekko — port the *self-declaration* idea, not the reflection mechanism. |
| **Config-schema pluggability** | core-geth (`ChainConfigurator` iface, `GetConsensusEngineType`) | ETC/PoW production; a small closed set of first-party engines | **OPTIONAL(closed-set families)** | Positive keying with an explicit `Unknown` (no fallthrough) is the clean discriminant shape — but the engine set is a **closed 3-engine enum** (`ConsensusEngineT`), so it does not open for extension the way plugin/typeclass does. |
| **Genesis-positive-selection + mechanism-decorates-schedule** | besu (`isClique/isQbft`, `ProtocolSpecAdapters`) | consortium/enterprise PoA; multi-mechanism (Clique/IBFT/QBFT) on one codebase | **DEFAULT (for the fork-rules ⊥ mechanism split)** | The single most important structural lesson: fork ruleset and mechanism are **orthogonal, composed layers** — a mechanism overrides only the 4-6 spec fields that differ and gets EVM/gas/fork currency free. Its Sealer/ValidatorProvider/BlockInterface seams map 1:1 onto fukuii's G1/G2/G3. Dispatch itself is a closed hand-maintained if/else (the part to improve). |
| **Compile-time module init (blank-import self-registration)** | erigon (`init()` + `polygon/` module) | sidechains needing their own subsystem + injected out-of-band infra (Heimdall/bridge) | **OPTIONAL(heavy sidechain family — NET-01)** | Proves a sidechain is a **module, not a config flag**, and that external infra is threaded through the *constructor*, keeping the base interface clean. The NET-01/Bor reference. Leaks `FrozenBorBlocks` into the shared reader — the cautionary counter-example for family-neutrality. |
| **Single-family fallthrough** | go-ethereum (`else-means-ethash`) | nothing fukuii wants | **OBSOLETE** | The exact anti-pattern B7.0 §A.1 exists to remove; fukuii currently replicates it (`BlockchainConfig.scala:513` defaults to ETC). Every other client uses positive keying. |
| **EngineReader / EngineWriter split** | erigon (`rules.EngineReader` typed across all RPC/exec) | archival / data-serving+RPC / light nodes that never seal | **OPTIONAL(read-only / RPC / archival roles)** | A read-only node types its whole execution/RPC/tracing surface against the reader half and never constructs the verification/sealing/infra-heavy writer engine (`bor.NewRo`, `remoteRulesEngine`). geth's monolithic single interface is the thing this improves on. |
| **Merge as decorator (wrapper object)** | go-ethereum `beacon.New`, core-geth (inert), erigon `merge.New` (conditional) | PoW/PoA → PoS transition, any base engine | **DEFAULT (conditional variant)** | Content-derived (`difficulty==0`), composes over *any* inner engine, single-layer by construction. fukuii wants erigon's **conditional** wrap (skipped for permanently-PoW ETC), **not** geth's now-mandatory wrap. |
| **Merge as transition-schedule** | besu (`TransitionProtocolSchedule` + `Map<block,builder>`) | PoA→PoA mechanism migration (IBFT→QBFT) with dual-wire-protocol gossip | **OPTIONAL(mechanism-upgrade testing / B7.2)** | A first-class ordered schedule of *whole mechanisms* + `MigratingConsensusContext` + both wire protocols advertised across the boundary. The reference for a general `EngineSchedule` beyond the binary PoW→PoS merge. |
| **Merge as composable co-activating wrapper (decorator on DI regs)** | nethermind (`MergePlugin` co-activates, TTD-gated) | single-binary multi-network where merge must layer over Ethash *or* Clique *or* AuRa | **DEFAULT-adjacent (the composition principle)** | `MergePlugin` is a bare `INethermindPlugin` (not `IConsensusPlugin`) so it co-activates without tripping the single-consensus guard; `AuRaMergePlugin` proves merge composes over **any** base. Confirms B7.0-c: merge is orthogonal to the base mechanism. |
| **block#-vs-timestamp: two separate paths** | go-ethereum, core-geth (block-only), erigon | status quo | **OBSOLETE (the split itself)** | fukuii's two `EvmConfig.forBlock` overloads are this. The reference clients that modernized collapsed it. |
| **Unified fork-dispatch: `MilestoneType` / `AddTransitions` / `ForkCondition`** | besu (enum), nethermind (twin-`SortedSet`), reth (single enum) | every node role — a family-neutral single activation seam | **DEFAULT** | Collapse the two overloads into one axis-agnostic activation query. **reth's `ForkCondition{Block,Timestamp,TTD,Never}` is the cleanest single-type form**; nethermind/besu are the two-encoding runners-up. Portable to Scala now, independent of the family-registry design. |

## Best-practice synthesis

**The DEFAULT + OPTIONAL menu that falls out of the six clients:**

1. **Family abstraction — DEFAULT: positive, config-derived engine selection with a uniqueness
   guard.** Every non-geth client keys engine selection *positively* off the parsed chain config
   (which typed engine-config object is present), never off an external network enum with a
   fallthrough. Kill fukuii's `NetworkType`-binary + `else-means-ETC` shape (B7.0 §A.1). Port
   nethermind's `require(sealEngines.size == 1)` uniqueness guard now (B7.0-a, already done),
   independent of any larger refactor.

2. **The B7.0.5 target — a Scala 3 `given`-based typeclass `NetworkFamily` registry.** Neither pole
   is a drop-in: reth's compile-time generics give type-safety but force one-crate-per-family
   (breaks single-binary); nethermind's runtime plugins give single-binary runtime selection but
   pay reflection + DI indirection (un-idiomatic in Scala). The synthesis is **reth's compile-time
   safety inside nethermind's single-binary runtime family-selection**: each family provides a
   `given NetworkFamily` instance (auto-derived, type-checked) discovered through a static registry
   (`ServiceLoader`-style) rather than reflection, mirroring nethermind's two-tier reality
   (auto-derived instances + one explicit `EmbeddedFamilies` list). The registry must span the full
   family-size spectrum: thin (Gnosis: engine reuse + spec overlay) → heavy sidechain (Bor: engine
   + injected Heimdall/bridge oracles) → rollup (OP/Taiko) → alt-BFT (XDC, the stress case).

3. **Fork ruleset ⊥ mechanism — DEFAULT: besu's decorate-don't-subclass split.** Define the ~20
   Ethereum forks once as a mechanism-agnostic schedule; a mechanism supplies a
   `Map<activation, specModifier>` overriding only the fields that differ (header validator,
   difficulty calc, block reward, mining-beneficiary). fukuii's designed
   Sealer/ValidatorProvider/BlockInterface seams map 1:1 onto besu's G1/G2/G3 and are where B7.1
   (Clique) and B7.2 (IBFT/QBFT) occupy the tree.

4. **Fork-dispatch unification — DEFAULT: collapse the two `forBlock` overloads into one
   axis-agnostic seam.** besu's `MilestoneType.{BLOCK_NUMBER,TIMESTAMP}`, nethermind's
   `AddTransitions(blockNumbers, timestamps)`, and reth's `ForkCondition{Block,Timestamp,TTD,Never}`
   are three encodings of the same idea; **reth's single enum is the cleanest**. A family then need
   not know which axis a fork gates on — it asks a `Hardforks`-style capability predicate. Portable
   to Scala now, independent of the registry.

5. **Merge — DEFAULT: a composable *conditional* wrapper (B7.0-c).** Content-derived
   (`difficulty==0`) routing lifted out of `EngineApiEngine` into a reusable conditional
   `beacon`-style `EngineSchedule` that composes over *any* base engine (Ethash→PoS, Clique→PoS,
   IBFT→QBFT). **Conditional** (skipped for permanently-PoW ETC) — erigon's `merge.New` shape, not
   geth's now-mandatory wrap. This is a *relocation of existing logic* (fukuii already has the
   routing in `TransitionBlockHeaderValidator`), not the per-block config schedule Batch-5 rightly
   rejected (B7.0 §B). besu's `Map<block,builder>` is the reference for the general
   mechanism-migration case (B7.2); nethermind's co-activating `MergePlugin` confirms merge is
   orthogonal to the base.

6. **Family-neutrality guard — a design invariant.** Every client that generalized leaks one
   network-ism into a shared seam: erigon's `FrozenBorBlocks` in `ChainHeaderReader`, reth's
   `is_optimism()` in the shared trait. The rule for fukuii's `NetworkFamily` typeclass: inject a
   family's oracles/knobs *through* the typeclass, never into shared readers — no `isEtc()` /
   `is_optimism()`-style branch in neutral code (the direct continuation of the EIP1559-DEALIAS
   "branch on capabilities, not network identity" ratchet, B7.0 §A.5).

**Read-only-consensus (OPTIONAL, archival/RPC roles):** erigon's EngineReader/EngineWriter split is
the one factoring geth/besu do not have — worth carrying as a Phase-4 seed for data-serving nodes
that never seal.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

These are **seeds for the B7.0.5 design**, not verdicts to implement in this doc.

- **fukuii's current shape:** block-number fork dispatch on the PoW/`forge` path
  (`EvmConfig.forBlock(block, config)`) + timestamp fork dispatch on the PoS/`beacon` path
  (`EvmConfig.forBlock(block, timestamp, config)`) — the two overloads the reference clients
  collapsed into one seam (reth `ForkCondition`, nethermind `AddTransitions`, besu `MilestoneType`).
  Engine selection currently bijects a hardcoded 2-case `NetworkType{ETC,ETH}` and defaults to ETC —
  fukuii replicates go-ethereum's `else-means-ethash` fallthrough, the anti-pattern core-geth's own
  config type and besu both avoid. B7.0 exists to fix this.

- **The DESIGNED-not-built seams map onto besu.** Batch 5 delivered the `{pow,pos,poa}` mechanism
  tree + seam *shape*; the three production-side seams — **Sealer** (block production),
  **ValidatorProvider** (who may seal), **BlockInterface** (extraData/vote codec) — are designed but
  unoccupied. They map 1:1 onto besu's G1/G2/G3
  (`BftBlockCreatorFactory`/`ValidatorProvider`/`BlockInterface`+`CliqueExtraData`). **Batch 7 is
  where the seams get occupied**: B7.1 Clique (Sealer sourced from **core-geth**, since besu stubbed
  Clique production to `NoopMiningCoordinator`; ValidatorProvider + BlockInterface from **besu**),
  B7.2 IBFT/QBFT (besu, whose `Map<block,builder>` IBFT→QBFT migration is the `EngineSchedule`
  reference).

- **B7.0-a uniqueness guard already ported** (nethermind's `require(sealEngines.size == 1)`) — the
  cheap, dispatch-style-independent port is done.

- **Merge relocation (B7.0-c, recommended Option 2):** lift the content-derived routing fukuii
  already has in `TransitionBlockHeaderValidator` into a reusable *conditional* `EngineSchedule`
  wrapper — erigon's conditional shape, ETC stays permanently unwrapped. Modest refactor, not the
  rejected per-block config schedule.

- **B7.0.5 full `NetworkFamily` layer** (B7.0 §C Option 3) is sequenced for when NET-01 (Polygon/Bor)
  gives a concrete *second* family to design the abstraction against — designing
  `evmProposals`/`precompiles`/`basedOn` + the erigon `bor.New(bridge, heimdall)` external-infra
  injection speculatively against Clique alone risks the wrong abstraction (YAGNI). erigon's
  sidechain-as-module + through-the-constructor infra injection is the NET-01 reference;
  family-neutrality (no `FrozenBorBlocks`-style leak) is the invariant to hold.

- **Do not align ETC to current geth/reth.** Both dropped standalone PoW; core-geth is the sole
  living ETC/PoW/ETChash byte-authority and the merge wrap must stay *conditional* so ETC remains
  permanently PoW-legal. ETChash (ECIP-1099) stays a DAG *parameter* of the one Ethash engine, never
  an `EngineId` case (B7.0 §A.2) — the "same mechanism, different parameterization" axis fukuii
  already models with `ecip1099BlockNumber`.
