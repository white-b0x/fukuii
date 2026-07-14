# Topic — L2 / sidechain / rollup consensus & family support
_Documented 2026-07-13. Cross-client survey. Cross-refs consensus-engines/multi-network + erigon block-execution (Bor)._

Scope: how each reference client models **non-L1-Ethereum EVM families** — Polygon/Bor
sidechain, OP-Stack / Optimism rollup, Taiko based-rollup, XDC/XDPoS, Scroll zkRollup, and
Gnosis Chain (AuRa→PoS sidechain). For each: *where* it lives in the tree, *how* the family
is structured, and the *gating mechanism* (compile-feature vs runtime plugin vs in-tree
module). All findings are against the vendored `upstream` refs (reth `3d76b93c2`,
nethermind `0d09a09ed`, erigon `f1d79d699e`, besu `3fd233a4f9`). Cited as `path:line`.

This topic feeds initial-assessment §1a (family-abstraction spectrum) and NET-01 (Polygon)
/ B7.0.5 (`NetworkFamily` `given`-based registry). It does **not** re-derive the general
family-selection mechanism — see per-client `consensus-engines.md` / `multi-network.md` and
`erigon/block-execution.md` (Bor block-execution hooks already documented there).

---

## Bor / Polygon PoS (sidechain)

**Home: erigon `polygon/` — a first-class in-tree subsystem, the NET-01 reference.** This is
the only reference client with a *full production* sidechain family in-tree (nethermind's L2
families are rollups; besu's is a network-config pointer). The whole `polygon/` tree —
`bor`, `bridge`, `heimdall`, `chain`, `db`, `sync`, `p2p`, `tests`, `tracer` — is a subsystem,
not just config (`erigon/consensus-engines.md:33-35`).

Mechanics (already documented in `erigon/block-execution.md:61-67` and
`erigon/consensus-engines.md:101-123` — summarized, not duplicated):
- **Engine as one of four `rules.Engine` implementations** (ethash / aura / bor / merge),
  selected by `chainConfig.Bor != nil` presence + a double guard
  (`chainConfig.Bor != nil && consensusCfg.ValidatorContract != ""`,
  `erigon/consensus-engines.md:75-77`). Bor's config is an **interface** (`Bor BorConfig`,
  `json:"-"`), not a pointer — a divergence from the ethash/aura pointer-presence pattern
  (`erigon/consensus-engines.md:86-88`), i.e. the "config-is-interface breaks the pointer
  convention" leak flagged in initial-assessment §1a.
- **`Bor.Finalize`**: no block reward, no uncles, no withdrawals (validators paid out-of-band)
  (`erigon/block-execution.md:62`). At **sprint start** it does two sidechain-specific things
  via syscall:
  - **`checkAndCommitSpan`** — rotates the validator set (span) by calling the Bor validator-set
    system contract (`polygon/bor/bor.go:1092`, per `erigon/block-execution.md:63`).
  - **`CommitStates`** — replays **state-sync events** fetched from the Heimdall **bridge**
    (`c.bridgeReader.Events`) into the `StateReceiver` contract; this is how L1→sidechain
    deposits enter the chain (`polygon/bor/bor.go:1217`, per `erigon/block-execution.md:64`).
- **External out-of-band infrastructure injection**: `bor.New(chainConfig, blockReader, spanner,
  stateReceiver, logger, polygonBridge, heimdallService)` — the `*bridge.Service` and
  `heimdallService` are injected, exactly like AuRa needs a dedicated consensus KV DB
  (`erigon/consensus-engines.md:105-112`, `node/rulesconfig/config.go:71-95`). Bor and AuRa are
  the two "engine needs external infra beyond the header" precedents.
- **Synthetic state-sync receipts**: state-sync logs (in `ibs.Logs()` but not in any tx receipt)
  are gathered into a synthetic `StateSyncReceipt` via `DeriveFieldsForBorReceipt`
  (`execution/protocol/block_exec.go:203-223`, per `erigon/block-execution.md:67,120-123`).
- **System-call author divergence**: `header.Coinbase` for Bor vs `params.SystemAddress`
  otherwise (`execution/protocol/block_exec.go:230`, `erigon/block-execution.md:57`).

**Embedded chainspecs (in-tree, module ships its own networks):** `polygon/chain/chainspecs/`
holds `bor-mainnet.json`, `amoy.json`, `mumbai.json`, `bor-devnet.json`, with matching
`allocs/` and Heimdall checkpoint testdata (`polygon/chain/genesis.go:33-36`). This is the
"polygon ships its own chainspecs" observation from §1a — the module is self-contained down
to the network definitions and Heimdall test fixtures.

**Verdict: DEFAULT reference for fukuii's promoted NET-01 Polygon family.** Bor is the single
best structural authority for "a sidechain is a MODULE with injected out-of-band oracles,"
which is exactly what B7.0.5's `NetworkFamily` registry must accommodate (a family that is
more than a fork schedule — it carries a bridge/span oracle contract). The leaks (config-as-
interface, `FrozenBorBlocks` in the shared reader, `erigon/consensus-engines.md:57`) are the
*anti-patterns* fukuii's typed `given` registry should avoid — inject the oracle through the
family typeclass, don't leak bor-specifics into shared readers.

---

## Optimism / OP-Stack (rollup)

**Home: nethermind `Nethermind.Optimism` — a runtime plugin assembly (`IConsensusPlugin`).**
This is the deepest OP-Stack implementation across the four clients: nethermind embeds not
just the execution-layer OP fork but a substantial slice of the **rollup consensus/derivation
layer** in-tree.

Execution-layer mechanics:
- **Plugin gating**: `OptimismPlugin(ChainSpec chainSpec) : IConsensusPlugin`, `Enabled =>
  chainSpec.SealEngineType == SealEngineType` where `SealEngineType => Core.SealEngineType.Optimism`
  (`Nethermind.Optimism/OptimismPlugin.cs:46-59`). Self-enabling from the chainspec engine key —
  the nethermind "runtime self-declaring plugin registry" pole (§1a).
- **Deposit transactions**: a new EIP-2718 tx type `DepositTx = 0x7E`
  (`Nethermind.Core/.../TxType.cs:14`), registered by the plugin via
  `api.RegisterTxType<DepositTransactionForRpc>(new OptimismTxDecoder<Transaction>(), Always.Valid)`
  plus an Optimism legacy-tx decoder/validator
  (`OptimismPlugin.cs InitTxTypesAndRlpDecoders`). `tx.IsDeposit() => tx.Type == TxType.DepositTx`
  (`DepositTxExtensions.cs:8-10`). Deposits are always-valid (L1-originated, no signature).
- **L1 data-fee (the OP economic model)**: `OptimismCostHelper` computes the L1 cost charged
  per tx across Bedrock / Ecotone / Fjord fee regimes — Fjord uses FastLZ-compressed size with
  `L1CostFastlzCoef`/`L1CostInterceptNeg`/`FjordDivisor` constants and reads L1 base-fee +
  blob-base-fee scalars from the `l1BlockAddr` system contract
  (`Nethermind.Optimism/OptimismCostHelper.cs:15-72`). This is fork-versioned rollup fee math
  with no L1-Ethereum analogue.
- **Always-post-merge**: OP has no PoW history, so `OptimismPoSSwitcher.TransitionFinished =>
  true`, `HasEverReachedTerminalBlock() => true`, and post-merge is keyed on `bedrockBlockNumber`
  (`Nethermind.Optimism/OptimismPoSSwitcher.cs:9-34`). Full OP surface: dedicated
  `OptimismBlockProcessor`, `OptimismHeaderValidator`, `OptimismBaseFeeCalculator`,
  `OptimismGasLimitCalculator`, `OptimismEthereumEcdsa`, `Create2DeployerContractRewriter`,
  `OptimismEngineRpcCapabilitiesProvider`.
- **Timestamp forks**: OP forks are timestamps (`RegolithTimestamp`…) added to the *identical*
  `AddTransitions(blockNumbers, timestamps)` signature Ethash adds block-numbers to
  (`nethermind/multi-network.md:109-128`) — the §1c fork-dispatch-unification proof.
- **Embedded rollup CL / derivation** (`Nethermind.Optimism/CL/`): `Driver.cs`,
  `OptimismCL.cs`, `L1Bridge/`, `Derivation/`, `Decoding/`, `ExecutionEngineManager.cs`,
  `OptimismSystemConfig.cs`, `L2Api.cs`, `P2P/`. Nethermind embeds the OP **derivation pipeline**
  (batch decoding + L1-block-derived L2 blocks) rather than relying purely on an external
  op-node CL — a notably heavy in-tree footprint for an "L2 plugin."

**besu — Linea network configs only, no OP-Stack.** Mainline besu has *no* OP-Stack in-tree
(consensus dirs are clique/ibft/qbft/merge only). Its sole L2 touchpoint is two **network
definitions** — `LINEA_MAINNET`/`LINEA_SEPOLIA` pointing at bundled `linea-mainnet.json` /
`linea-sepolia.json` (`config/.../NetworkDefinition.java:59-72`). Linea is a downstream
besu-based distribution; upstream besu just ships the chain configs, no Linea-specific
consensus/EVM code. So besu's "L2 support" is a genesis pointer, not a family.

**reth — OP-Stack is a downstream crate, absent from this vendored copy.** The vendored reth is
ethereum-only: no `crates/optimism/`, no op-reth, no scroll/taiko crates. What survives in the
shared crates is the **`is_optimism()` leak**: `ChainSpec::is_optimism()` /
`EthChainSpec::is_optimism()` (`crates/chainspec/src/api.rs:53-55, 130`), `is_optimism_mainnet()`
(`crates/chainspec/src/spec.rs:498-501`), and OP-aware dynamic-EIP-1559-params comments
(`spec.rs:396,664`) — ~40 `optimism` references total in shared crates. This is the "small leak:
`is_optimism()` in the shared trait" from §1a; the actual OP family (EVM, deposit tx, L1 cost)
lives in the downstream `op-reth` / `reth-optimism-*` crates that ship a distinct `NodeTypes`
associated-type set — **compile-time, one-crate-per-family** (§1a's reth pole). *Inferred from
the shared-trait hooks; the OP crate itself is not vendored, so its internals are not surveyed
here.*

**Verdict: OPTIONAL(role: multi-network / L2 expansion).** Nethermind's `Nethermind.Optimism`
is the structural authority if fukuii ever adds an OP-Stack family — it demonstrates the full
in-tree footprint (deposit tx type, versioned L1-cost helper, always-post-merge switcher,
embedded derivation). For fukuii's B7.0.5 registry the lesson is scale: an OP family is *large*
(a plugin assembly's worth of processors/validators/fee-calculators + optionally a derivation
pipeline), so the `given NetworkFamily` boundary must be able to swap the whole
block-processor/header-validator/fee-calculator cluster, not just a fork schedule.

---

## Taiko (based rollup)

**Home: nethermind `Nethermind.Taiko` — a runtime plugin assembly (`IConsensusPlugin`),
peer to Optimism.** `TaikoPlugin(ChainSpec chainSpec) : IConsensusPlugin`, `Enabled =>
chainSpec.SealEngineType == SealEngineType` (`SealEngineType => Taiko`)
(`Nethermind.Taiko/TaikoPlugin.cs:48-122`, per `nethermind/consensus-engines.md:159`).

Based-rollup-specific mechanics (this is what distinguishes a *based* rollup from OP):
- **L1-origin binding**: `L1Origin.cs`, `IL1OriginStore` / `L1OriginStore.cs`, `L1OriginDecoder.cs`
  — each L2 block records its L1 origin (the based-rollup L1-block that sequenced it). The L2
  block's canonicity derives from L1 rather than a local sequencer.
- **Beacon-driven head advance / preconfirmation**: `TaikoBeaconHeadAdvancer.cs`,
  `TaikoBeaconSync.cs`, `TaikoSyncProgressResolver.cs` — the head is advanced by an external
  driver (Engine-API/beacon-style), consistent with based sequencing where L1 proposers drive
  L2 progression. `_api.GossipPolicy = ShouldNotGossip.Instance` (`TaikoPlugin.cs Init`) — the
  node does not gossip blocks (they come from L1/the driver, not P2P propagation).
- **Custom VM + zk gas**: `TaikoVirtualMachine.cs`, `ZkGas/`, `Precompiles/`, `Tdx/` (Intel TDX
  attestation for the proving path), `TaikoExecutionRequestsProcessor.cs`. Plus the full
  execution cluster: `TaikoBlockProcessor`, `TaikoBlockValidator`, `TaikoHeaderValidator`,
  `TaikoTransactionProcessor`, `TaikoPayloadPreparationService`, `TaikoGenesisBuilder`.
- Merge-based: adds `MergeProcessingRecoveryStep` in `Init` and resolves an `IPoSSwitcher`
  (`TaikoPlugin.cs Init`) — Taiko is post-merge-only, like OP.

**reth — absent** (0 `taiko` references; no crate in the vendored copy). besu/erigon — no Taiko.

**Verdict: OPTIONAL(role: multi-network / L2 expansion), lower priority than OP/Bor.** Taiko is
the reference for a *based* rollup pattern (L1-origin store + no-gossip + external head driver +
custom VM/zk-gas). Only relevant to fukuii if a based-rollup family is ever promoted; it is not
in current scope. For B7.0.5 it reinforces the same "family swaps the whole VM/processor
cluster" lesson as OP, plus a `GossipPolicy` knob and an L1-origin store — both would need to be
family-injectable seams, not global assumptions.

---

## Xdc / XDPoS

**Home: nethermind `Nethermind.Xdc` — a runtime plugin assembly (`IConsensusPlugin`), the most
complete *alternative-consensus* (non-Ethereum, non-rollup) family in any vendored client.**
`XdcPlugin(ChainSpec chainSpec) : IConsensusPlugin`, `Enabled => chainSpec.SealEngineType ==
SealEngineType`, `SealEngineType => XdcConstants.XDPoS` (`Nethermind.Xdc/XdcPlugin.cs:12-20`).
Crucially the seal-engine tag `XDPoS`/`XDPoSSubnet` is declared in `XdcConstants.cs:12-13`
**outside** the core `SealEngineType` string list — proving the plugin tier is genuinely
open/zero-edit-to-core (`nethermind/consensus-engines.md:80-81`).

XDPoS 2.0 = a **HotStuff-based delegated-PoS BFT** with masternodes. Mechanics are unusually
rich in-tree:
- **HotStuff BFT core**: `XdcHotStuff.cs`, `IQuorumCertificateManager`/`QuorumCertificateManager.cs`,
  `ITimeoutCertificateManager`/`TimeoutCertificateManager.cs`, `IVotesManager`/`VotesManager.cs`,
  `ISyncInfoManager`, `NewRoundEventArgs.cs`, `TimeoutTimer.cs` — QC/TC voting rounds, view-change
  on timeout. This is real BFT consensus, not PoA seal-checking.
- **Masternode / epoch mechanics**: `EpochLength = 900` blocks (`XdcConstants.cs`), voted-in
  masternodes via an on-chain `IMasternodeVotingContract` (`BaseSnapshotManager.cs:29-56,132-162`),
  `IEpochSwitchManager`/`EpochSwitchManager.cs` (epoch-switch-at-round), `IMasternodesCalculator`,
  penalty handling (`IPenaltyHandler`/`PenaltyHandler.cs` — slashing inactive masternodes),
  rewards (`RewardsStore.cs`, `XdcRewardCalculator.cs`). Signer vote nonces mirror Clique
  (`NonceAuthVoteValue = 0xff..ff` / `NonceDropVoteValue = 0`, `XdcConstants.cs`) — XDPoS's PoA
  lineage.
- **Forensics / accountable safety**: `IForensicsProcessor`/`ForensicsProcessor.cs` — BFT
  equivocation detection (the accountability layer HotStuff-family protocols carry).
- **Subnet variant**: a full parallel `Subnet*` family (`XdcSubnetPlugin.cs`,
  `SubnetEpochSwitchManager`, `SubnetMasternodesCalculator`, `SubnetSnapshotManager`,
  `XdcSubnetBlockProducer`) — XDC's app-specific-subnet feature, itself a *second* consensus
  plugin sharing the Xdc core.
- Full node surface: custom `XdcBlockHeader`/`XdcSubnetBlockHeader`, `XdcBlockTree`,
  `XdcBlockStore`, `XdcHeaderStore`, `XdcSealer`, `XdcBlockProducer`, `XdcTransactionProcessor`,
  `XdcBaseFeeCalculator`, dedicated `RLP/`, `RPC/`, `P2P/`, `Discovery/`, `TxPool/`,
  `XdcRocksDbConfigFactory`.

**reth/erigon/besu — no XDC.**

**Verdict: OPTIONAL(role: enterprise / multi-network), niche.** XDC is the reference for "a
whole alternative BFT consensus family (HotStuff + masternode governance + forensics) as a
self-contained plugin, incl. its own header/blocktree/store types." It is the most demanding
test of a family-abstraction: XDC needs its own block header type and block tree, not just its
own engine. For B7.0.5 it is the *stress case* — if fukuii's `given NetworkFamily` registry can
express XDC (custom header type + custom block tree + BFT round managers + subnet sub-family),
it can express anything. Not in current fukuii scope, but the clearest evidence that the
registry's family boundary must reach as deep as the domain block-header/blocktree types.

---

## Scroll (zkRollup)

**Absent from every vendored client.** reth: `scroll` matches are UI false-positives
(`crates/cli/commands/src/db/tui.rs:313-314` mouse-scroll, `sigsegv_handler.rs:112` a comment) —
no scroll crate/feature. Upstream reth's Scroll support (where it exists) is a downstream
`reth-scroll-*` crate set, not vendored here. nethermind: no `Nethermind.Scroll` assembly.
besu: no Scroll (its L2 config surface is Linea only). erigon: no scroll module.

**Verdict: OBSOLETE for reference purposes / OUT OF SCOPE.** No vendored authority exists. If a
zkRollup family were ever promoted for fukuii, the closest in-tree structural analogues are
Taiko (zk gas / proving path, `Nethermind.Taiko/ZkGas/`) and Optimism (deposit tx + L1 data
fee). Do not treat Scroll as a documented reference — there is nothing to cite.

---

## Gnosis Chain (AuRa sidechain)

**Home: nethermind + erigon, as an embedded network riding the AuRa engine (pre-merge) then PoS
(post-merge, Dec 2022).** Gnosis (ex-xDai) is *not* a distinct consensus family — it is a
**network** whose engine key selects AuRa, plus a Gnosis-specific fork/spec provider. See the
PoA topic for the AuRa engine itself; here only the family/network-support wiring:

- **nethermind**: `Nethermind.Consensus.AuRa` provides the engine (`AuRaBlockProcessor`,
  `AuRaBlockProducer`, `AuRaBlockFinalizationManager`, `AuRaContractGasLimitOverride`, …). Gnosis
  rides it via embedded chainspecs `Chains/gnosis.json` + `Chains/chiado.json`, a dedicated
  `GnosisSpecProvider.cs` / `ChiadoSpecProvider.cs`, and a `GnosisForks/` fork-override set
  (`12_LondonGnosis.cs`, `16_ShanghaiGnosis.cs`, …) — i.e. Gnosis is a spec-provider + chainspec
  overlay, engine reused. Case-insensitive engine-key matching is load-bearing: gnosis's
  `"authorityRound"` chainspec key matches the AuRa plugin
  (`nethermind/multi-network.md:80,159,330`). Post-merge Gnosis co-activates AuRa+Merge via
  `Nethermind.Merge.AuRa` (the `AuRaMerge` seal type in the plugin list,
  `nethermind/consensus-engines.md:128`).
- **erigon**: Gnosis is embedded as a native network — `execution/chain/spec/chainspecs/gnosis.json`
  + `chiado.json`, `allocs/gnosis.json`, and full CL support (`cl/clparams/initial_state/
  gnosis.state.ssz`, Deneb testdata). AuRa is one of erigon's four `rules.Engine`s (selected by
  `chainConfig.Aura != nil`) and, like Bor, needs external infra — a dedicated AuRa consensus KV
  DB opened before `aura.NewAuRa(chainConfig.Aura, db)` (`node/rulesconfig/config.go:71-73`, per
  `erigon/consensus-engines.md:71-73`). Erigon even burnt-contract-tests against Gnosis
  (`polygon/chain/config_test.go:36-39`).

**besu — no Gnosis.** (besu's PoA is Clique/IBFT/QBFT for private/consortium chains, not the
public Gnosis network.)

**Verdict: OPTIONAL(role: multi-network), engine already partly in fukuii's roadmap via PoA.**
Gnosis is the cleanest evidence that a "sidechain family" can be *just* an engine-key +
spec-provider + chainspec overlay on an existing PoA engine (AuRa) — **the low-cost end of the
family spectrum**, opposite Bor/XDC. For B7.0.5 it argues the registry should support a *thin*
family (reuse an existing engine, override only the fork spec + genesis) as well as the heavy
Bor/OP/XDC families — the `given NetworkFamily` should compose, not force every family to be a
full plugin. AuRa itself is covered in the PoA topic; do not re-document the engine here.

---

## Family-gating mechanism per client (compile-feature / plugin / in-tree)

| Client | Mechanism | How a new L2/sidechain family is added | Where it sits on §1a spectrum |
|--------|-----------|----------------------------------------|-------------------------------|
| **reth** | **Compile-time generics** (`NodeTypes` associated types) | A downstream crate (`op-reth`, `reth-scroll-*`) with its own type set; shared crates carry only leaks (`is_optimism()`) | The type-safe pole; **one-crate-per-family**, not in this vendored copy |
| **nethermind** | **Runtime self-declaring plugin** (`IConsensusPlugin` per assembly; chainspec `SealEngineType` string self-selects) | Drop an assembly (`Nethermind.Optimism`/`Taiko`/`Xdc`) that self-enables from the engine key; seal-engine tag can live outside core (`XDPoS`) | The runtime pole; **plugin assemblies**, genuinely open at the config-param tier |
| **erigon** | **In-tree module + compile-time blank-import registry** (module ships its own chainspecs) | Add a subsystem tree (`polygon/`) with embedded chainspecs; engine selected by `chainConfig.X != nil` presence | Compile-time module registry; **broadest real multi-family reach** but leaky |
| **besu** | **Genesis-config positive selection** + bundled **network definitions** for L2s | For L2: only a network-config pointer (Linea json); no in-tree rollup family | Closed if/else `BesuControllerBuilder`; **no in-tree L2 family at all** |

Key distinctions this survey adds:
- **Sidechain (Bor, Gnosis/AuRa) = in-tree module or engine+overlay**; **rollup (OP, Taiko) =
  plugin assembly** (nethermind) or **downstream crate** (reth). No client makes a rollup a
  mere config overlay — rollups always carry code (deposit tx, L1 cost, derivation, custom VM).
- **nethermind is the only client with rollup families (OP, Taiko) AND an alt-BFT family (XDC)
  in the same tree, all as peer plugins** — the strongest evidence that a single-binary runtime
  plugin registry can host wildly different families side by side. This is the pole B7.0.5
  targets, tempered by reth's compile-time type-safety.
- **The gating tier is two-layered** (per §1a nethermind nuance): config-params
  (`*ChainSpecEngineParameters`) are zero-edit/reflection-discovered, but wiring a new consensus
  *subsystem* still uses a hand-maintained list (`EmbeddedPlugins` / erigon's `init()` blank
  imports / besu's controller if-else). Every client hand-maintains the *subsystem* list; only
  the *param* tier is fully open.

---

## Cross-client L2/rollup matrix (family × client × gating × fukuii verdict)

| Family | reth | nethermind | erigon | besu | fukuii verdict |
|--------|------|-----------|--------|------|----------------|
| **Bor / Polygon PoS** (sidechain) | — | — | **in-tree `polygon/` module** (full: bridge/heimdall/span/state-sync; embedded chainspecs) | — | **DEFAULT** — NET-01 promoted; erigon = the structural authority |
| **Optimism / OP-Stack** (rollup) | downstream crate (absent; `is_optimism()` leak only) | **plugin `Nethermind.Optimism`** (deposit tx `0x7E`, L1-cost helper, embedded CL/derivation) | — | Linea net-config only (no OP) | **OPTIONAL(multi-network)** — nethermind = authority |
| **Taiko** (based rollup) | — | **plugin `Nethermind.Taiko`** (L1-origin store, no-gossip, TaikoVM, zk-gas, TDX) | — | — | **OPTIONAL(multi-network)**, lower priority; nethermind = sole authority |
| **XDC / XDPoS** | — | **plugin `Nethermind.Xdc`** (HotStuff BFT: QC/TC/votes/forensics, masternode epochs, subnet sub-family, custom header/blocktree) | — | — | **OPTIONAL(enterprise)**, niche; the family-abstraction stress case |
| **Scroll** (zkRollup) | — (UI false-positive only) | — | — | — | **OBSOLETE / out-of-scope** — no vendored authority |
| **Gnosis Chain** (AuRa→PoS) | — | **AuRa engine + Gnosis spec-provider/chainspec overlay** (+ `AuRaMerge`) | **embedded network** (chainspec + native CL) on AuRa engine | — | **OPTIONAL(multi-network)** — thin-family reference (engine reuse) |

Reading the matrix: **erigon owns the one production sidechain (Bor)**; **nethermind owns all
three "carries-code" alt-families (OP, Taiko, XDC)**; **Gnosis is the only family two clients
share, and it's the thinnest (engine reuse)**; **reth's L2 lives in absent downstream crates**;
**besu has no in-tree L2 family**. For fukuii's promoted scope (Bor) the authority is erigon;
for any future rollup/alt-consensus the authority is nethermind.

---

## fukuii implications (NET-01, B7.0.5 NetworkFamily registry, multi-layering scope)

1. **NET-01 (Polygon/Bor) → erigon `polygon/` is the DEFAULT reference, already partly
   documented.** The mechanics are in `erigon/block-execution.md` (Finalize hooks, span
   rotation, Heimdall state-sync, synthetic receipts) and `erigon/consensus-engines.md`
   (engine selection, external-infra injection). The *new* facts this topic adds: the module
   **ships its own chainspecs** (`polygon/chain/chainspecs/{bor-mainnet,amoy,mumbai}.json`) and
   its config-is-an-**interface** (not pointer) — both are things B7.0.5 must accommodate (a
   family carries its own network definitions) or avoid (don't leak the family's config shape
   into shared readers, per the `FrozenBorBlocks` anti-pattern).

2. **B7.0.5 `given`-based `NetworkFamily` registry — the L2 evidence sizes the family boundary.**
   Fukuii's target (§1a synthesis) is "reth's compile-time safety inside nethermind's
   single-binary runtime family-selection." This survey shows the registry must span a **wide
   family-size spectrum**:
   - **Thin** (Gnosis): engine reuse + fork-spec/genesis overlay only. The `given NetworkFamily`
     should *compose* over an existing engine, not force a full plugin.
   - **Heavy sidechain** (Bor): engine + injected out-of-band oracles (bridge/span/Heimdall
     service). The typeclass must be able to carry injected oracle contracts, not just a fork
     schedule.
   - **Rollup** (OP/Taiko): swaps the whole block-processor / header-validator / fee-calculator /
     tx-type-decoder cluster, adds a new tx type (`DepositTx`), and may embed a derivation
     pipeline + a `GossipPolicy` knob + an L1-origin store.
   - **Alt-BFT** (XDC): the stress case — a *custom domain block-header type and block tree*,
     plus BFT round managers (QC/TC/votes/forensics) and a nested subnet sub-family.
   If the `given NetworkFamily` boundary can express XDC (reach down to header/blocktree types),
   it can express every family above it. Recommend designing the seam at the depth XDC demands
   but making the *common* case (thin, Gnosis-style) a cheap composition — mirror nethermind's
   two-tier reality (open config-param tier + a small hand-maintained subsystem list), which
   maps cleanly to a Scala 3 `given` registry (auto-derived family instances) + one explicit
   `EmbeddedFamilies` list.

3. **Multi-layering scope (omni-client thesis).** fukuii's thesis explicitly includes
   L2/multi-layering, and NET-01 (Polygon) is promoted. This survey's scope tags:
   **DEFAULT = Bor** (in scope, erigon authority); **OPTIONAL(multi-network) = OP-Stack, Gnosis**
   (nethermind/erigon authorities, plausible next); **OPTIONAL(enterprise, niche) = Taiko, XDC**
   (nethermind authorities, stress cases); **OBSOLETE/out-of-scope = Scroll** (no vendored
   authority). Practical ordering for expansion: Bor (promoted) → Gnosis (thin, cheap, engine
   already arriving via PoA) → OP-Stack (heavy but the highest-value L2) → Taiko/XDC only on
   explicit enterprise demand. The gating-mechanism finding is decisive: because **rollups
   always carry code** (never a config overlay) and **the heavy sidechain needs oracle
   injection**, fukuii cannot ship these as pure HOCON/genesis config — each promoted L2 family
   is a code deliverable behind the `NetworkFamily` seam, sized per the spectrum above.

4. **Gating-mechanism fit for a JVM single binary.** Neither reth's one-crate-per-family
   (compile-time, no single-binary runtime selection) nor besu's no-L2-in-tree is a fit.
   **nethermind's runtime plugin registry is the closest operational model** (all families in
   one binary, self-selected by chainspec engine key), and **erigon's in-tree self-registering
   module with embedded chainspecs is the closest for a heavy sidechain like Bor**. fukuii's
   `given`-based registry should take nethermind's single-binary runtime selection + erigon's
   "family ships its own chainspecs" packaging, expressed with reth-grade type-safety via Scala 3
   `given` derivation — landing on the DESIGNED-not-built `Sealer`/`ValidatorProvider`/
   `BlockInterface` seams (which already map 1:1 onto besu's) rather than any one client's
   mechanism wholesale.
