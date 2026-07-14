# Reference-Client File-Tree Structure Survey — precedent for fukuii's consensus/package realignment

_Read-only research, 2026-07-10. Feeds Batch 5 Row 5.5 (whole-tree alignment). Paired with
`fukuii-tree-classification.md` (scout's fukuii-side map). Every path verified on disk under
`/media/dev/2tb/dev/reference-clients-evm/` (copies also under `.claude/repo-references/clients/`).
Grounds fukuii's target tree in real precedent per the reference-client-authority-PLUS-architectural-mirror
model (`systemic-review-protocol.md` "Authority vs. architectural mirror")._

## Headline

Two archetypes exist; four clients (Besu/geth/core-geth/Nethermind) **agree** on the load-bearing
patterns and diverge on only two points (→ fukuii judgment calls):

**Strong agreements — adopt with confidence:**
1. **Mechanism as sibling package, PoA sharing a `common/` sub-tree.** Besu `consensus/{clique,ibft,qbft,merge,common}`; geth/core-geth `consensus/{ethash,clique,beacon,(lyra2)}`; Nethermind `Nethermind.Consensus.{Ethash,Classic,AuRa,Clique}` + base `Consensus`. fukuii `consensus/{pow,pos,poa}` + `consensus/common/` is directly precedented.
2. **Fork schedule separated from mechanism code — unanimous.** Never put fork defs inside mechanism packages. Best-in-class: Nethermind `Specs/Forks/NN_<Fork>.cs` (one ordered file/fork); Besu `MainnetProtocolSpecs` (fork→spec factory); geth `params/ChainConfig`.
3. **Transition = a composite wrapping two schedules, NOT a subclass.** Besu `TransitionProtocolSchedule` (holds pre+post schedules + `MergeContext`); geth `Beacon{ ethone Engine }` dispatching on `IsPoSHeader` (difficulty==0). Composition over inheritance.
4. **Per-network specifics = data/config, not source packages.** No client makes a package per network. core-geth `params/config_<network>.go` file-sets are the precedent whose networks (classic/mordor/olympia) map 1:1 onto fukuii's.

**Divergences — fukuii judgment calls:**
- **A. Neutral `ConsensusEngine` interface placement.** geth/core-geth: INSIDE `consensus/` (`consensus.go`, top of tree). Besu: OUTSIDE, in `ethereum/core/…/mainnet/` (`ProtocolSchedule`/`ProtocolSpec`). **Rec: geth placement** (neutral trait at top of `consensus/`, `pow`/`pos`/`poa` as leaves) — simpler for a Scala module; only replicate Besu's split if fukuii's spec bundle is EVM-coupled the way Besu's `ProtocolSpec` is.
- **B. ETC = full mechanism sibling vs config-variant of shared PoW.** Nethermind: full sibling `Nethermind.Consensus.Classic` (`Etchash.cs`, `Ecip1017Calculator.cs`). core-geth: config-variant INSIDE `consensus/ethash/consensus_classic.go`. **Rec: follow core-geth (the ETC authority)** — ETC/ETChash is a **config-selected variant inside `consensus/pow/`, NOT a parallel `consensus/etc/` tree.** This keeps network names (Classic/Mordor/Olympia) as leaves/config and the neutral mechanism term (`pow`/Ethash) at the shared tier — exactly `nomenclature.md`. Nethermind's full sibling risks duplicating shared PoW code AND elevating a network name to a mechanism-tier package (a `nomenclature.md` violation).
- **C. Umbrella naming** (Erigon `execution/protocol/rules/{ethash,merge,…}`): cleaner but cosmetic; not worth diverging from the `consensus/` convention every client + fukuii already use.

## Net recommendation

fukuii mirrors **Besu's four-layer object structure** as the JVM skeleton, with placement/semantics borrowed per-concern from the authority whose concern it is:
- **Besu = shape:** (A) pluggable mechanism modules `consensus/{pow,pos,poa}` + `consensus/common/` shared plumbing; (B) neutral `ProtocolSchedule`/`ProtocolSpec` spine = fukuii's `ConsensusEngine`/`ValidatorsExecutor` neutral tier; (C) composite transition (`TransitionProtocolSchedule` ≙ fukuii `TransitionBlockHeaderValidator`/dispatch); (D) abstract controller-builder selector (`BesuControllerBuilder` subclasses ≙ fukuii family/mechanism selection, `TransitionBesuControllerBuilder` ≙ TTD-keyed `ValidatorsExecutor`).
- **go-ethereum = neutral-interface placement** (top of `consensus/`) + the transition-wrapper pattern.
- **core-geth = ETC-as-config-variant + per-network file-sets** (the authority whose networks fukuii ships).
- **Nethermind = fork-schedule-as-numbered-data ergonomics** (`Specs/Forks/NN_<Fork>`).

## Per-client maps (path-cited)

### Besu (JVM — PRIMARY mirror), 4 layers
- **A. Pluggable engines** — sibling Gradle modules `consensus/{clique,ibft,ibftlegacy,qbft,qbft-core,merge,common}`; PoA family shares `consensus/common/…/bft/`. **No `consensus/ethash` module — mainnet PoW validation lives in the neutral spec layer, and there is NO ETC/Classic support** (`ClassicProtocolSpecs.java` exists only as a stale `build/spotless-clean/` artifact).
- **B. Neutral spine OUTSIDE `consensus/`** — `ethereum/core/…/mainnet/{ProtocolSchedule,ProtocolSpec,ProtocolScheduleBuilder,ProtocolSpecBuilder,MainnetProtocolSchedule,MainnetProtocolSpecs}.java`.
- **C. Transition composite** — `consensus/merge/…/{TransitionProtocolSchedule,MergeProtocolSchedule,PostMergeContext,TransitionContext,TransitionCoordinator}`; `TransitionProtocolSchedule implements ProtocolSchedule` composing two schedules via `TransitionUtils`.
- **D. Selector** — `app/…/controller/BesuControllerBuilder` (abstract) → `{Mainnet,Clique,Ibft,IbftLegacy,Qbft,Merge,Transition,ConsensusSchedule}BesuControllerBuilder`. `TransitionBesuControllerBuilder` builds the transition when `getTerminalTotalDifficulty().isPresent()`.
- Config: top-level `config/` module (`GenesisConfig`, `GenesisConfigOptions`, `TransitionsConfigOptions`, `Fork`/`CliqueFork`/`QbftFork`/`BftFork`).

### go-ethereum (Go — ETH authority)
- `consensus/consensus.go` = `Engine` interface (neutral, top of tree). Mechanisms: `consensus/{ethash,clique,beacon}`. `consensus/beacon/consensus.go` `Beacon{ ethone consensus.Engine }` wraps legacy, dispatches per-header on `IsPoSHeader`. `consensus/misc/{eip1559,eip4844}` cross-fork fee helpers. Config: `params/config.go` (`ChainConfig` fork numbers+timestamps) + `params/protocol_params.go`.

### core-geth (Go — ETC/PoW authority + multi-network)
- Same skeleton + `consensus/merger.go` (transition-state manager) + `consensus/lyra2` (extra PoW variant — sibling model scales). **ETChash is NOT a package** — `consensus/ethash/consensus_classic.go` (`ecip1010Explosion`, ECIP difficulty), config-selected. **Richest per-network split:** `params/config_{classic,mordor,mintme}.go` + matching `genesis_*.go`/`bootnodes_*.go`; Olympia = `params/olympia_treasury.go` (network-policy data beside config, not in mechanism code).

### Nethermind (C# — OO cross-check)
- Assembly-per-mechanism: `Nethermind.Consensus` (neutral core: Processing/Producers/Validators/Rewards/Withdrawals/Scheduler), `.Consensus.Ethash`, **`.Consensus.Classic` (ETC full sibling: `Etchash.cs`, `Ecip1017Calculator.cs`, `DifficultyBombCalculator.cs`)**, `.Consensus.AuRa`, `.Consensus.Clique`, `.Merge.Plugin` (PoS as a PLUGIN, outside `Consensus.*`), `.Merge.AuRa`. Forks: `Specs/Forks/NN_<Fork>.cs` numerically ordered (`00_Olympic`…`25_Amsterdam`); `Specs/ChainSpecStyle/` JSON-chainspec driven; `GnosisForks/` subdir when a network diverges.

### Erigon / Reth (secondary — mostly non-transferable)
- Erigon (Go): `execution/protocol/rules/{ethash,clique,merge,aura}` + `execution/protocol/misc` — same sibling model under an `execution/protocol/` umbrella (naming idea only).
- Reth (Rust): trait+crate, PoS/engine-centric, no PoW tree (`crates/consensus/{consensus,common}`, `crates/ethereum/{consensus,engine-primitives}`, `crates/engine/`). Rust idioms don't transfer.

## Patterns that do NOT transfer
- Go build-tags/file-suffix dispatch (`consensus_classic.go`): the IDEA (ETC as config variant) transfers; the mechanism (Go suffixes) → fukuii uses `ChainConfig`/`EvmConfig` branching (already does).
- Reth Rust traits/crate-per-concern + PoS-only: no PoW/PoS-coexistence precedent; ignore for structure.
- Nethermind assembly-per-project + Besu Gradle-module-per-mechanism: .NET assemblies / Gradle modules are lighter than sbt modules — fukuii should separate by **package under one `consensus/` source root**, NOT an sbt submodule per mechanism, unless a build-isolation reason emerges. Take the namespace/package LAYOUT, not the module granularity.
