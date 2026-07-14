# Topic — Consensus-methods & network-types MASTER CATALOG
_Documented 2026-07-13. Synthesis/index of the consensus survey — see the linked detail docs for evidence._

This is the **navigable index** over the consensus-methods + network-types survey: one master
matrix, one network inventory, the family-abstraction gating spectrum, and fukuii's omni-client
scope statement. It **does not re-derive** anything — every claim below is cited in a linked
detail doc (git archaeology, `path:line` evidence, per-client dispatch mechanics live there).
Use-case lens per `README.md`: every method is characterized by **what it's GOOD FOR**
(`DEFAULT` / `OPTIONAL(role)` / `OBSOLETE`), and only the genuinely obsolete is discarded.

Reference refs surveyed (upstream/clean, per SR convention): go-ethereum `59e89e81e`,
core-geth `4185df450`/`b28aa0a0b`, besu `3fd233a4f9`, erigon `f1d79d699e`,
nethermind `0d09a09ed`, reth `3d76b93c2`.

---

## Master consensus-method matrix (method × client × authority × fukuii verdict)

**16 consensus methods** catalogued (the 15 survey-list methods + Scroll as a no-authority,
out-of-scope entry). Cell legend: **✓** current/present at surveyed ref · **⌫YYYY** carried
then removed (year) · **—** never carried. "Present" = the client can *validate+produce* under
that method unless the cell says otherwise (validation-only / seal-removed noted inline).

| # | Method | go-ethereum | core-geth | besu | erigon | nethermind | reth | Authority | fukuii verdict |
|---|--------|:-----------:|:---------:|:----:|:------:|:----------:|:----:|-----------|----------------|
| 1 | **PoW-Ethash** (generic/ETH; block-number dispatch) | ⌫2023 mining (validate+faker shim kept) | ✓ | ⌫2026 | ⌫2021 mining (getWork-serve+validate kept) | ✓ opt-in (vanilla) | — | go-ethereum (ancestral structure); nethermind (vanilla cross-check) | **OPTIONAL(generic-Ethash cross-check)** — subsumed by ETChash for ETC production |
| 2 | **PoW-ETChash / ECIP-1099** (ETC epoch-doubling) | — | **✓ sole living authority** | ⌫2026 (JVM ref; ECIP-1099 diverged) | — | — | — | **core-geth (byte-authority)**; besu-history (JVM structure only) | **DEFAULT (ETC/Mordor production — forge)** |
| 3 | **PoS / Engine-API** (timestamp dispatch) | **✓ authority** | ✓ (inert on ETC) | ✓ | ✓ | ✓ | ✓ native | **go-ethereum** | **DEFAULT (ETH/Sepolia — beacon)** |
| 4 | **Clique** (EIP-225 PoA) | ✓ validate+seal | ✓ validate+**seal** | ✓ validate only (**seal removed** → `NoopMiningCoordinator`) | — | ✓ validate+seal | — | **core-geth (sealing)** + **besu (seam structure)** | **OPTIONAL(private-testnet / consortium)** — B7.1 / NET-02 planned |
| 5 | **IBFT 2.0** (besu BFT) | — | — | ✓ validate only (**mining=hard error**) | — | — | — | **besu** | **OPTIONAL(enterprise interop only)** — QBFT preferred |
| 6 | **QBFT** (besu BFT, instant finality) | — | — | ✓ **validate+seal** | — | — | — | **besu** | **OPTIONAL(enterprise / consortium / custody)** — B7.2; highest-value BFT |
| 7 | **AuRa** (Authority Round, step-based) | — | — | — | ✓ (Gnosis) | ✓ (richest impl) | — | nethermind / erigon | **OBSOLETE for fukuii** — Parity/Gnosis-specific, no ETC demand |
| 8 | **Bor / Polygon PoS** (sidechain) | — | — | — | **✓ in-tree `polygon/` module** | — | — | **erigon** | **DEFAULT-reference / PLANNED (NET-01)** — the promoted L2 family |
| 9 | **OP-Stack / Optimism** (rollup) | — | — | Linea net-config only (no OP) | — | **✓ plugin `Nethermind.Optimism`** | downstream crate (absent; `is_optimism()` leak) | **nethermind** | **OPTIONAL(multi-network / L2)** |
| 10 | **Taiko** (based rollup) | — | — | — | — | **✓ plugin `Nethermind.Taiko`** | — | **nethermind** | **OPTIONAL(multi-network / L2), lower priority** |
| 11 | **XDPoS / Xdc** (HotStuff DPoS-BFT) | — | — | — | — | **✓ plugin `Nethermind.Xdc`** | — | **nethermind** | **OPTIONAL(enterprise), niche** — family-abstraction stress case |
| 12 | **Gnosis** (AuRa→PoS sidechain) | — | — | — | ✓ embedded net (AuRa engine) | ✓ AuRa engine + spec overlay (`AuRaMerge`) | — | nethermind / erigon | **OPTIONAL(multi-network)** — thinnest family (engine reuse) |
| 13 | **Scroll** (zkRollup) | — | — | — | — | — | — (UI false-positive only) | **none (no vendored authority)** | **OBSOLETE / out-of-scope** |
| 14 | **dev / instant-seal** (local + CI) | ✓ sim-beacon / Clique-hist | ✓ (same geth machinery) | ✓ Ethash `fixeddifficulty:100` | (staged) | ✓ `NethDev` / AuRa | ✓ `--dev` auto-seal (`MiningMode`) | besu (PoW-shaped) / nethermind (`NethDev`) / reth (`MiningMode::Instant`) | **OPTIONAL(local / CI)** |
| 15 | **Internal CPU-Ethash sealing** (private-PoW-testnet) | ⌫2023 | **✓ real (ETC-correct)** | ⌫2026 | ⌫2021 | ✓ opt-in (vanilla; clean gate model) | — | **core-geth (target)** + nethermind (`Mining.Enabled=false` gate model) | **OPTIONAL(private-PoW-testnet / conformance)** |
| 16 | **Faker / test-engine PoW** (unit/consensus tests) | stubs only (hollow on HEAD) | **✓ full family + real PoW body** | fixed-diff schedule | ✓ `FakeEthash` | real `Validate` + `NethDev` | — | **core-geth** (only geth-lineage faker next to a working PoW body) | **OPTIONAL(test)** — port faker family; add `NewFakeFailer(n)` analogue |

Notes threading the matrix:
- **PoW mining is nearly gone from HEAD; only core-geth (ETC) + nethermind (vanilla opt-in) still
  self-mine Ethash.** A naive read of geth/besu/erigon HEAD wildly under-estimates their PoW past —
  validation shims and faker stubs outlive the miner. See detail doc §"Deprecated-PoW synthesis".
- **core-geth is the sole living ETC/ETChash byte-authority** for every ETC *value* (ECIP-1099 epoch,
  ECIP-1017 emission, difficulty, seed). besu-history is a JVM *structural* cross-check only (its
  ECIP-1099 formula diverged; fukuii's own overlay corrects it).
- **besu is the sole BFT (IBFT2/QBFT) authority**; **erigon the sole production sidechain (Bor)
  authority**; **nethermind owns all three "carries-code" alt-families (OP, Taiko, XDC)**.
- **The getWork/Stratum *mining-protocol* surface** (node-side `eth_getWork`/`eth_submitWork` +
  the pool-layer Stratum) is catalogued separately — DEFAULT = core-geth-byte-compatible getWork;
  OBSOLETE = in-node Stratum (besu's un-adopted path). See `mining-protocol-evm.md` §5 /
  `mining-protocol-nonevm.md` §5.

---

## Network-type inventory (PoW / PoS / L2-sidechain — current + deprecated)

Compact index; chain IDs, TTD/deposit-contract/fork-schedule detail and removal-commit archaeology
live in the linked detail docs.

**PoW networks (ETC family)** — → `consensus-poa-and-etc-testnets.md`, `consensus-pow-cpu-dev-and-deprecated.md`
- **ETC mainnet** — chain 61 · **in scope now** (forge). PoW/ETChash, ECIP-1017 emission, block-number forks.
- **Mordor** — chain 63 · **in scope now** (forge). Current, NOT-deprecated ETC PoW testnet.
- **Kotti** — chain 6 · **DEAD** (Clique PoA ETC testnet, dropped core-geth 2023-07 / besu 2023-08). fukuii's *precedent*, not a target.
- **Morden** — chain 2 · **DEAD** (original ETC PoW testnet, doc-comment only in nethermind).
- **Astor** — **NOT FOUND** in any surveyed client (left no trace).

**PoS networks (ETH family)** — → `pos-networks-and-testnets.md`
- **ETH mainnet** — chain 1 · OPTIONAL (PoS-mainnet growth). Merged 2022 (historical TTD).
- **Sepolia** — chain 11155111 · **DEFAULT / in scope now** (beacon). Merged testnet; custom permissioned deposit contract.
- **Hoodi** — chain 560048 · **DEFAULT (add)**. Holesky's PoS-from-genesis successor; mainnet deposit contract.
- **Holesky** — chain 17000 · OPTIONAL (winding down; already removed by nethermind/erigon/besu 2025-Q4→2026-Q2).
- **Ephemery** — chain 39438135 (rotates) · OPTIONAL/niche (besu-only built-in; resetting chain ID → prefer external-genesis path).
- **Deprecated set — curate OUT**: Goerli/5, Ropsten/3, Rinkeby/4, Kiln/1337802, Kovan/42, Morden/2. **No surveyed client still ships any of them** (unanimous 2023–2024 removal).

**L2 / sidechain / rollup** — → `consensus-l2-rollup-sidechain.md`
- **Polygon / Bor** — sidechain · **PLANNED (NET-01)**, erigon `polygon/` = authority. Injected Heimdall/bridge/span oracles.
- **Optimism / OP-Stack** — rollup · OPTIONAL(L2), nethermind = authority. Deposit tx `0x7E`, versioned L1-cost helper, embedded derivation.
- **Taiko** — based rollup · OPTIONAL(L2, lower), nethermind = authority. L1-origin store, no-gossip, TaikoVM/zk-gas.
- **Xdc / XDPoS** — alt-BFT · OPTIONAL(enterprise, niche), nethermind = authority. HotStuff + masternode + custom header/blocktree (the stress case).
- **Gnosis** — AuRa→PoS sidechain · OPTIONAL(multi-network), nethermind/erigon. Thin family: engine reuse + spec overlay.
- **Scroll** — zkRollup · **OBSOLETE/out-of-scope**. No vendored authority.

---

## Family-abstraction gating spectrum (per client) + B7.0.5 synthesis

How each client selects/adds a consensus family, least→most modular (→ `initial-assessment.md` §1a,
`consensus-l2-rollup-sidechain.md` §"Family-gating mechanism per client"):

- **hand-if-else (go-ethereum / besu)** — go-ethereum: single-family, `else-means-ethash` fallthrough (weak). besu: genesis-config *positively* selects the mechanism (`isClique/isQbft/getPowAlgorithm`) but dispatches through a **closed hand-maintained if/else `BesuControllerBuilder`**; key pattern = **mechanism DECORATES the fork-schedule** (`ProtocolSchedule` + `ProtocolSpecAdapters`), and the three seams (Sealer / ValidatorProvider / BlockInterface) map 1:1 onto fukuii's G1/G2/G3.
- **config-schema (core-geth)** — a network is a `ChainConfigurator` *interface* value satisfying one `Configurator` contract; positive keying (no fallthrough) but a **closed 3-engine enum** (`ConsensusEngineT`).
- **in-tree module (erigon)** — **compile-time blank-import `init()` self-registration**; a family ships its own subsystem tree + embedded chainspecs (`polygon/`), engine selected by `chainConfig.X != nil` presence. Broadest *real* multi-family reach, but **leaky** (`FrozenBorBlocks` in the shared reader; Bor's config-is-interface breaks the pointer convention).
- **runtime-plugin (nethermind)** — **self-declaring `IConsensusPlugin` per assembly**; chainspec `SealEngineType` string self-selects (a tag like `XDPoS` can live *outside* core). Genuinely open at the config-param tier; the pole. Honest nuance: a new *subsystem* still uses a hand-maintained `EmbeddedPlugins` list.
- **compile-feature (reth)** — **compile-time-generics SDK** (`NodeTypes` associated types; a family = a downstream crate, e.g. `op-reth`). Type-safe but one-crate-per-family; small leak `is_optimism()` in the shared trait.

**B7.0.5 synthesis** — neither pole is a drop-in for a single-binary JVM node. fukuii's target is a
**Scala 3 `given`-based typeclass `NetworkFamily` registry** = **reth's compile-time type-safety inside
nethermind's single-binary runtime family-selection**, landing on the DESIGNED-not-built
`Sealer`/`ValidatorProvider`/`BlockInterface` seams. It must span a **wide family-size spectrum**:
thin (Gnosis: engine reuse + spec overlay) → heavy sidechain (Bor: engine + injected out-of-band
oracles) → rollup (OP/Taiko: swaps the whole processor/validator/fee-calculator cluster + new tx type)
→ alt-BFT (XDC: custom domain header type + block tree + BFT round managers — the stress case). Mirror
nethermind's two-tier reality (auto-derived `given` family instances + one explicit `EmbeddedFamilies`
list), and **avoid the leaks** (`FrozenBorBlocks`/`is_optimism()`) by injecting oracles/knobs *through*
the family typeclass, never into shared readers. The uniqueness guard (`require(sealEngines.size == 1)`)
is already ported (B7.0-a).

---

## fukuii omni-client scope (in-scope now / planned / obsolete) → Sealer/ValidatorProvider/BlockInterface seams

The whole point is the omni-client **"DEFAULT + OPTIONAL(role) menu"** — a curated, current set,
actively pruned, not a kitchen-sink of every historical method/network. Each in-scope/planned method
slots into fukuii's `{pow,pos,poa}` mechanism leaves behind the three Batch-5-designed seams
(**Sealer** = block production, **ValidatorProvider** = who may seal, **BlockInterface** = extraData/vote
codec). Batch 5 delivered the tree + seam *shape*; **Batch 7 is where the seams get occupied**.

**IN SCOPE NOW**
- **PoW ETC/Mordor (forge)** — method #2 ETChash + #1 Ethash validation. DEFAULT. Sealer = `EthashMiner`/`EthashDAGManager` (already present); getWork production path matches core-geth.
- **PoS ETH/Sepolia (beacon)** — method #3 Engine-API. DEFAULT. Engine-API-driven; the merge modelled as a conditional beacon-style `EngineSchedule` wrapper (B7.0-c), ETC stays permanently unwrapped.

**PLANNED (menu additions, sized per the family spectrum)**
- **Clique (B7.1 / NET-02)** — method #4. Private-testnet / consortium PoA. **Sealer** sourced from **core-geth** (besu deliberately stubbed Clique production — validate against it, don't copy its `NoopMiningCoordinator`); **ValidatorProvider** + **BlockInterface** (extraData codec) from **besu**. Kotti (dead) is the ETC-lineage *precedent*, not a target.
- **Bor / Polygon (NET-01)** — method #8. Promoted sidechain L2. Heavy family: the `NetworkFamily` typeclass must carry **injected out-of-band oracles** (Heimdall/bridge/span). erigon `polygon/` = authority (already partly documented in `erigon/block-execution.md`).
- **QBFT (B7.2, enterprise)** — method #6. Consortium / custody, instant-finality differentiator over Clique. besu = sole authority; its `Map<block, builder>` IBFT→QBFT dual-wire migration is the reference for `EngineSchedule`. (IBFT 2.0 #5 carried for interop only — mining unsupported even in besu.)
- **Internal CPU-Ethash sealing (private-PoW-testnet)** — method #15. Disposable CPU-sealed Ethash/ETC net for Olympia (ECIP-1111/1112/1121/1122) fork-activation testing with no GPU rig. Machinery exists (`EthashMiner`/`MockedMiner`); work = mode wiring + a single opt-in flag (nethermind's `Mining.Enabled=false` gate model + geth's explicit-coinbase contract).
- **dev / instant-seal** — method #14. Local + CI. Two shapes: besu `fixeddifficulty` (PoW-shaped, real seal path) or Clique-instant/`NethDev` (pure bypass; NET-02 covers this). reth `MiningMode::Instant` = the on-demand UX to mirror.
- **Faker / test-engine** — method #16. Port the core-geth faker family (the only geth-lineage faker beside a *working* PoW body); add a `NewFakeFailer(n)` "fail PoW at block n" analogue for reorg/negative consensus tests.

**OBSOLETE (do not add — the genuinely-discarded tail)**
- **AuRa** (#7) — Parity/Gnosis-specific, on-chain validator-contract + step-timing subsystem, no ETC/roadmap demand. Revisit only if a Gnosis-family target appears.
- **Scroll** (#13) — no vendored authority to cite.
- **Kotti / Morden** + the **deprecated PoS testnet set** (Goerli/Ropsten/Rinkeby/Kiln/Kovan/Morden) — dead; the whole ecosystem shed them 2023–2024. Cite Kotti as precedent only.
- **In-node Stratum server** (besu's removed, never-miner-adopted path) — keep Stratum in separate pool software; the node stays on the getWork + typed block-template seam.

**OPTIONAL / growth (not planned, but on the menu if a use-case materializes)**
- OP-Stack (#9), Taiko (#10), Xdc (#11), Gnosis (#12) — nethermind/erigon authorities; expansion order Bor(promoted) → Gnosis(thin) → OP-Stack(heavy, highest-value) → Taiko/XDC(enterprise demand only). ETH mainnet + Holesky/Ephemery = deprioritized PoS growth/sunset targets.

---

## Detail-doc index

| Detail doc | Owns | Feeds catalog rows/sections |
|------------|------|-----------------------------|
| [`consensus-poa-and-etc-testnets.md`](consensus-poa-and-etc-testnets.md) | Clique / IBFT2 / QBFT / AuRa + ETC Kotti PoA testnet (git archaeology) | Methods #4–#7; Kotti/Morden network inventory; B7.1/B7.2 scope |
| [`consensus-l2-rollup-sidechain.md`](consensus-l2-rollup-sidechain.md) | Bor / Optimism / Taiko / Xdc / Gnosis / Scroll + per-client family-gating | Methods #8–#13; L2 network inventory; family-abstraction spectrum; B7.0.5 |
| [`consensus-pow-cpu-dev-and-deprecated.md`](consensus-pow-cpu-dev-and-deprecated.md) | Internal-CPU-Ethash / dev-instant-seal / faker + deprecated-PoW synthesis | Methods #1, #14–#16; PoW mining removal archaeology |
| [`pos-networks-and-testnets.md`](pos-networks-and-testnets.md) | Sepolia/Holesky/Hoodi/Ephemery + deprecated PoS testnets (archaeology) | Method #3; PoS network inventory; TTD/deposit-contract/timestamp-fork structure |
| [`mining-protocol-evm.md`](mining-protocol-evm.md) | Node-side `eth_getWork`/`eth_submitWork` + internal PoW miner (EVM clients) | Mining-protocol surface for methods #2/#15; getWork DEFAULT verdict + §5 alignment gap |
| [`mining-protocol-nonevm.md`](mining-protocol-nonevm.md) | Pool-layer Stratum v1/v2 + non-EVM PoW (Bitcoin/Monero/Zcash `getblocktemplate`) | In-node-Stratum OBSOLETE verdict; typed block-template seam (Phase-4 seed) |
| [`../initial-assessment.md`](../initial-assessment.md) §1a/§2 | Family-abstraction spectrum, authority map, omni-client governing principle | Family-gating spectrum; B7.0.5 synthesis; every fukuii-verdict tag |
| `../*/consensus-engines.md` (per client) | ★ engine-selection/fork-dispatch mechanics per client | Per-client dispatch cells across the master matrix |
