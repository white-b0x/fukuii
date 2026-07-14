# EVM Multi-Network Landscape Taxonomy — the four-seam decomposition

_Read-only research, 2026-07-10. Part B of the Batch 5 network-landscape survey. Feeds
Row 5.5-design (fukuii's `consensus/` file-tree and abstraction-seam evolution). Paired with
`reference-client-tree-structure.md` (how real clients lay out consensus code). Part C
(private/permissioned networks) and Part D (fukuii synthesis) are separate deliverables —
this file describes the outside world and stops at the handoff points noted inline._

_Durable, reusable knowledge about networks fukuii does not (yet) run. The operator's framing:
"knowing what exists is helpful; nothing locks us into implementation, but building so
implementation is a POSSIBILITY is important." The goal below is that fukuii's abstraction
seams don't **preclude** these networks — not that we implement any of them._

---

## Headline: which seam actually differs

**For the overwhelming majority of EVM networks, the EVM execution layer is identical (or a
config-selected Ethereum hardfork level) and ONLY the Sybil-resistance + finality seams
differ.** An execution client like fukuii can host them by swapping a consensus *seam*, not by
writing a new execution engine.

The exceptions — networks that diverge at the **execution layer itself**, where fukuii would
need genuinely new EVM/state code rather than a seam swap — are a short, identifiable list:

1. **zkEVMs that are not bytecode-equivalent** (zkSync Era Type-4; Polygon zkEVM's Type-3 edges) — the "EVM" is a different bytecode/IR, so the execution client is different software.
2. **Fee-model divergences that touch the state transition** — EIP-1559 base-fee burn vs. ETC's no-burn gas model, blob/DA gas accounting, L2 L1-data-fee charging. These are execution-layer, not just header-field, differences.
3. **Non-EVM-host execution environments** — Neon EVM (EVM as a Solana BPF program). Not an EVM L1 at all; an execution client cannot "add" it as a network.

Everything else — BNB Smart Chain, Avalanche C-Chain, Polygon PoS, Gnosis, Berachain, Ronin,
the OP-Stack and Arbitrum L2 execution clients, the bytecode-equivalent zkEVMs (Scroll,
Linea) — is **"same EVM execution, different selection/finality layer."** This is the load-bearing
finding for fukuii's tree (see the payload analysis below).

---

## The four seams (the taxonomy — do NOT collapse to PoW-vs-PoS)

From an execution client's viewpoint a consensus mechanism is not one thing; it decomposes into
four largely independent seams. Two networks can share three of the four and differ in one.

| # | Seam | Question it answers | Example values |
|---|------|---------------------|----------------|
| 1 | **Sybil resistance / validator selection** | Who may produce blocks, and how are they chosen? | hashrate (PoW), stake weight (PoS), NPoS election, PoSA stake-election over an authority set, PoA authority list, permissioned allowlist |
| 2 | **Block-production scheduling** | Who proposes, and when? | open mining race, stake-weighted proposer windows, round-robin sealing, PBS/ePBS builder auction, leader rotation, validator-elected block producer (VEBloP) |
| 3 | **Finality gadget** | How is irreversibility achieved? | heaviest/longest chain (probabilistic), Casper FFG checkpoints, CometBFT/Tendermint single-slot BFT, Snowman/Snowball repeated subsampling, BFT round finality |
| 4 | **Fork-choice rule** | How is the canonical head selected? | GHOST / LMD-GHOST, heaviest-chain, MESS-weighted (ECIP-1100), BFT (no fork choice — instant commit), Snowman preference |

**Economics is NOT a fifth seam.** Emission schedules (ECIP-1017 fixed supply, EIP-1559 burn),
Proof of Liquidity (Berachain), restaking (EigenLayer-style), and staking-reward routing are an
**incentive/economics layer** orthogonal to the four consensus seams. A source that calls
"Proof of Liquidity" a *consensus mechanism* is conflating economics with seam #1 — flagged
each time it occurs below. Berachain is the sharpest example: its actual consensus is
CometBFT BFT (seams 1–4); "PoL" is purely who-gets-rewarded (economics).

---

## Dimension table — L1s (sovereign consensus)

Fixed dimension set so results map onto fukuii's config surface. Seams abbreviated
**Sel** (selection) · **Sched** (scheduling) · **Fin** (finality) · **FC** (fork-choice).

| Network | Chain ID | Sel | Sched | Fin | FC | Fork activation | EVM target | Block time | Fee model |
|---------|----------|-----|-------|-----|----|-----------------|-----------|-----------|-----------|
| **Ethereum** | 1 | PoS stake weight | stake-weighted proposer (PBS via MEV-Boost today; **ePBS enshrined in Glamsterdam, H2 2026**) | Casper FFG (2-epoch, ~13 min econ finality) | LMD-GHOST | **timestamp** | Fusaka (live 2025-12-03); Glamsterdam next | 12 s | EIP-1559, base-fee **burned** |
| **Ethereum Classic** | 61 (63 Mordor) | PoW / Ethash (ETChash post-ECIP-1099) | open mining race | heaviest-chain, probabilistic | heaviest-chain **+ MESS (ECIP-1100)** | **block-number** | Mystique; **Olympia** planned (ECIP-1111/1112/1121/1122) | ~13 s | traditional gas, **no burn**; ECIP-1111 base-fee floor + Treasury routing (planned) |
| **BNB Smart Chain** | 56 | **PoSA** — 45-validator authority set, stake-elected | round-robin sealing among active set | fast-finality BFT vote (~1.1 s post-Fermi) | heaviest-chain + fast-finality | **block-number** (BEP hardforks) | tracks ~Cancun-era + BSC BEPs | **0.45 s** (Fermi, 2026-01-14) | EIP-1559-style; gas token BNB |
| **Avalanche C-Chain** | 43114 | PoS stake weight (Primary Network validators) | validator proposes per Snowman round | **Snowman** (repeated random subsampling, ~0.8–1.5 s absolute finality) | Snowman preference (not GHOST) | **block-number** (network upgrades: Etna, etc.) | tracks recent Ethereum EVM | ~1–2 s | EIP-1559-variant, ACP-125 min base-fee 1 nAVAX; gas token AVAX |
| **Polygon PoS** | 137 | PoS stake weight (Heimdall/CometBFT-based) | **VEBloP** — validators elect a small block-producer set (Rio, Oct 2025) | Heimdall v2 checkpoint BFT (~5 s finality) | reorg-free by construction (VEBloP) | **block-number** | Bhilai added EIP-7702 (~Prague-era) | ~2 s | EIP-1559; gas token POL |
| **Gnosis Chain** | 100 | PoS stake weight (1 GNO/validator, 145k+ validators) | stake-weighted proposer (mirrors Ethereum EL+CL split) | Casper FFG (Gnosis Beacon Chain / GBC) | LMD-GHOST | **timestamp** (mirrors Ethereum forks) | tracks Ethereum (London+, EIP-1559 live) | ~5 s | EIP-1559; gas token **xDAI** (stable) |
| **Berachain** | 80094 | PoS stake weight (BERA); **PoL = economics, not selection** | CometBFT proposer rotation (BeaconKit) | **CometBFT single-slot BFT** (2/3+ commit, instant) | BFT — no fork choice, committed = final | **timestamp** (EL mirrors Ethereum) | tracks recent Ethereum EVM | ~2 s | EIP-1559; gas token BERA; BGT soulbound reward token |

**Edge case — Neon EVM** (chain ID 245022934): **not an EVM L1.** It is an EVM *interpreter*
deployed as a Solana on-chain program (Rust → BPF bytecode). Sybil/scheduling/finality/fork-choice
are all **Solana's** (Tower-BFT / Alpenglow-Votor-Rotor in flight, none of it EVM-shaped). An
execution client like fukuii has no seam to swap here — the EVM runs *inside another chain's
runtime*. Included only to mark the outer boundary of "EVM network."

---

## Dimension table — L2s / rollups (inherit settlement from an L1)

The critical reframing for fukuii: **an L2's rollup-node concerns (sequencing, DA posting,
proof generation, L1 settlement) are NOT the execution client's job.** The execution client
(op-geth/op-reth, Arbitrum's Geth-core, a zkEVM's executor) runs a **near-standard EVM state
transition** plus a handful of L2 deltas. What follows separates the two.

| L2 | Type | Settlement L1 | DA layer | Sequencer model | Proof system | EVM target | What the *execution client* must add |
|----|------|--------------|----------|-----------------|-------------|-----------|--------------------------------------|
| **Optimism / Base (OP Stack)** | Optimistic | Ethereum | Ethereum blobs (EIP-4844) | single sequencer (Superchain shared roadmap) | Cannon fault-proof VM (MIPS); permissionless since late 2024 | tracks Ethereum forks closely (EVM-equivalent) | L1-data-fee (`L1Block` predeploy), deposit-tx type, `op-geth`/`op-reth` predeploys; **not** the fault proof |
| **Arbitrum One (Nitro)** | Optimistic | Ethereum | Ethereum blobs | single sequencer; BoLD permissionless validation (2024→2025) | WASM fraud proof (compiled STF), interactive bisection | Geth-core EVM + **Stylus** (WASM: Rust/C/C++ alongside Solidity) | ArbOS, retryable tickets, L1-pricing, Stylus host; **not** the fraud proof |
| **Scroll** | ZK (Type-1 leaning) | Ethereum | Ethereum blobs | single sequencer | Halo2 + KZG | **bytecode-level EVM equivalence** — contracts deploy unmodified | near-vanilla EVM; trace export for prover |
| **Linea** | ZK (Type-2) | Ethereum | Ethereum blobs | single sequencer (ConsenSys) | gnark-based | EVM-equivalent at execution | near-vanilla EVM + trace export |
| **zkSync Era** | ZK (Type-4) | Ethereum | Ethereum blobs / validium option | single sequencer | custom (Boojum) | **zkEVM IR — different bytecode**; Solidity recompiled | **entirely different execution engine** — not a seam swap |
| **Polygon zkEVM** | ZK (Type-2→3) | Ethereum | Ethereum blobs | single sequencer | STARK→SNARK | EVM-equivalent with minor opcode edges | mostly-vanilla EVM; some opcode deltas |

**The L2 insight for an execution client:** for the optimistic and Type-1/2 ZK rollups, the
execution client is *Ethereum's EVM plus a small L2-delta set* (deposit/system tx types, an
L1-data-fee charge, a few predeploys). The rollup's *identity* (optimistic vs ZK, proof system,
DA) lives in the **rollup node / prover**, which fukuii-as-execution-client would not own.
Only **Type-3/4 zkEVMs** (zkSync Era, Polygon zkEVM's edges) push divergence *into* the
execution layer.

---

## Dimension table — sidechains (independent consensus + bridge)

| Sidechain | Chain ID | Consensus (seams 1–4) | Bridge / validator-set model | EVM target | Fork activation | Note |
|-----------|----------|-----------------------|------------------------------|-----------|-----------------|------|
| **Polygon PoS** | 137 | (see L1 table — historically a "commit-chain"; now Heimdall-BFT L1-like) | Ethereum-anchored checkpoint bridge (Heimdall posts to L1) | Bhilai / ~Prague | block-number | often classed L1 today; bridge to Ethereum makes it sidechain-lineage |
| **Ronin** | 2020 | **DPoS**, ~22 validators, 3 s slots, ~1 min finality | Sky-Mavis-operated bridge (MainchainGatewayManager); multisig validator gateway | recent Ethereum EVM | block-number | **In flight: migrating DPoS sidechain → zkEVM L2 (2026)** — reclassifies |
| **Gnosis Chain** | 100 | (covered as L1 above — full PoS EL+CL, not a thin sidechain) | historic xDAI bridge; now a sovereign PoS chain | Ethereum forks | timestamp | listed here only to disambiguate its xDAI-bridge past |

---

## The payload: which seams differ, grouped (the point of this doc)

Grouping every network above by **which of the four seams actually diverges from Ethereum's**
answers fukuii's structural question directly: should `consensus/` organize by *mechanism
bucket* or by *seam*?

### Group A — same EVM execution, differ ONLY in Sybil-resistance + finality (the majority)

BNB Smart Chain, Avalanche C-Chain, Polygon PoS, Gnosis, Berachain, Ronin, and the
EVM-equivalent L2 execution clients (OP Stack, Arbitrum, Scroll, Linea).

For all of these the EVM state transition is Ethereum's (at some hardfork level) and the
differences are confined to **seam #1 (selection)** and **seam #3 (finality)** — with
occasional **seam #2 (scheduling)** flavor (VEBloP, round-robin sealing, ePBS). Concretely:

- **Selection** ranges over the full menu: hashrate (ETC), unrestricted stake (ETH/Gnosis/Avalanche/Berachain), a *bounded authority set* stake-elected (BSC PoSA 45, Ronin DPoS ~22, Polygon VEBloP-elected).
- **Finality** ranges over: probabilistic heaviest-chain (ETC), Casper FFG checkpoints (ETH/Gnosis), single-slot BFT (Berachain CometBFT, BSC fast-finality vote), Snowman subsampling (Avalanche), checkpoint-BFT (Polygon Heimdall v2).
- **Fork-choice** collapses to two shapes: GHOST-family for the probabilistic/FFG chains, and *no meaningful fork choice* for the BFT-final chains (committed == canonical).

**These networks do not need new execution code — they need a pluggable consensus seam.** A
client that can express "this network's block-validity check + finality signal come from module
X" hosts all of Group A without touching the EVM.

### Group B — diverge at the EXECUTION layer (fukuii would need real new code)

1. **Fork-activation dispatch is itself a seam that already splits fukuii's world.** ETC dispatches forks by **block number**; ETH/Gnosis/Berachain by **timestamp**; BSC/Avalanche/Polygon/Ronin by block number. This is the split fukuii *already* encodes (`EvmConfig.forBlock(n, cfg)` vs the `forBlock(n, ts, cfg)` timestamp overload). It is an execution-layer concern, not a pure-consensus one — and it is the one Group-B-ish divergence fukuii is already built for.
2. **Fee-model divergences that alter the state transition:** EIP-1559 base-fee **burn** (ETH/most) vs. ETC's **no-burn** traditional gas vs. ECIP-1111's base-fee-floor-to-Treasury routing (planned) vs. L2 L1-data-fee charging (OP/Arbitrum). These touch gas accounting inside execution, not just header fields — real code, not a config toggle.
3. **Non-bytecode-equivalent zkEVMs (zkSync Era Type-4, Polygon zkEVM's Type-3 edges):** the "EVM" is a *different bytecode/IR*. This is a different execution engine, full stop — outside the reach of any Ethereum-EVM execution client.
4. **Neon EVM:** EVM-as-a-Solana-program. Not hostable by an EVM execution client at all.

### Verdict for fukuii's `consensus/` tree

**Organize by seam at the shared tier, with mechanism buckets as the coarse leaves — not a
package-per-network and not a rigid PoW/PoS binary.** The evidence:

- Group A proves the dominant axis of variation is **selection + finality**, which are seams — so the neutral spine should expose those as pluggable points (this is exactly what `reference-client-tree-structure.md` found Besu/geth doing: a neutral `ProtocolSchedule`/`Engine` spine with `{pow,pos,poa}` mechanism leaves, transition as a *composite* not a subclass).
- The PoW/PoS binary is too coarse: BSC PoSA, Ronin DPoS, and Polygon VEBloP are all "PoS-ish selection" but differ sharply in *finality* (BFT vs checkpoint) and *scheduling* (round-robin vs elected-producer). A `pos/` leaf that internally varies its finality gadget matches reality better than three sibling packages.
- The genuine execution-layer divergences (Group B) map onto fukuii's **existing** seams — fork-activation dispatch (already split) and fee model (already network-configured). fukuii should keep those as first-class configuration axes, *not* fold them into the consensus-mechanism package.
- Per-network specifics (chain ID, alloc, fork schedule, bridge params) stay **data/config**, never source packages — unanimous across all four reference clients (core-geth `params/config_<network>.go` is the precedent whose networks map 1:1 onto fukuii's).

Net: a neutral consensus spine exposing **selection** and **finality** as the pluggable seams,
`{pow, pos, poa}` mechanism leaves (ETC as a *config variant inside* `pow/`, per the ETC
authority core-geth — not a `consensus/etc/` sibling), a composite transition wrapper, and
per-network data in `config/`. This structure does not *preclude* any Group-A network and
cleanly quarantines the Group-B divergences fukuii already handles.

**Handoffs:** private/permissioned networks (PoA authority lists, IBFT/QBFT, permissioned
allowlists — the `poa/` leaf) are **Part C**, not duplicated here. fukuii's concrete target
tree and the seam-interface design are **Part D**. This document supplies the outside-world
taxonomy both build on.

---

## Uncertainty flags (verify before relying on any of these)

- **Ethereum Glamsterdam** (ePBS via EIP-7732, Block-Level Access Lists via EIP-7928): **in flight**, final devnet mid-2026, mainnet targeted **H2 2026 (Sep–Dec)** — *not yet live*. Fusaka (PeerDAS) **is** live (2025-12-03). Do not state ePBS as current mainnet behavior.
- **BNB Smart Chain Fermi** (0.45 s blocks, ~1.1 s finality) activated **2026-01-14** — recent; validator count (45) and BEP set may drift. 2026 roadmap "1 Gigagas/s" is a target, not shipped.
- **Berachain PoL v2** deprecated the original tri-token BGT model (token decoupling, BERA-staking yield). "PoL" is **economics, not consensus** — CometBFT/BeaconKit is the actual consensus. Any source calling PoL a consensus mechanism conflates the two.
- **Polygon Rio / VEBloP** live since Oct 2025; Heimdall v2 (~5 s finality) live since mid-2025. "Reorg-free" is a design claim of VEBloP; confirm against production behavior before treating as a hard finality guarantee.
- **Avalanche** fee/finality figures are Etna/Avalanche9000-era (ACP-77/-125/-125). Snowman is *probabilistic* absolute finality (~0.8–1.5 s), not BFT-instant — sources sometimes blur this.
- **Ronin** is **mid-migration from DPoS sidechain to zkEVM L2 (2026)** — its classification (sidechain vs L2) is changing under us; verify current state before citing.
- **OP Stack execution client:** op-geth is being superseded by **op-reth**; "op-geth" statements may be stale. Fault-proof permissionlessness (Stage 1) landed late 2024.
- **zkEVM type classifications** (Vitalik's Type 1–4) are the projects' *own* claims and move as they upgrade toward equivalence — Scroll (Type-1-leaning), Linea (Type-2), Polygon zkEVM (Type-2→3), zkSync Era (Type-4). Re-verify a project's current type before depending on it.
- **Chain IDs** are stated from memory/common knowledge for several networks; verify against a chain-ID registry for any consensus-critical use.
- Consensus-critical lens: header-field, fork-activation, base-fee/burn, difficulty/sealing, and state-root/RLP claims above are landscape-level orientation — **not** validated byte-for-byte against reference clients. Any actual fukuii implementation of a Group-A/B network must re-derive these from the authoritative client per `consensus-change-protocol.md`.

---

## Sources

- Ethereum Glamsterdam: [ethereum.org/roadmap/glamsterdam](https://ethereum.org/roadmap/glamsterdam/) · [The Defiant — final devnet, 200M gas](https://thedefiant.io/news/blockchains/ethereum-glamsterdam-final-devnet-200m-gas-limit-target) · [Everstake overview](https://everstake.one/resources/blog/ethereum-glamsterdam-upgrade-explained)
- Ethereum Fusaka (live): [Ethereum Foundation — Fusaka mainnet announcement](https://blog.ethereum.org/2025/11/06/fusaka-mainnet-announcement) · [ethereum.org/roadmap/fusaka](https://ethereum.org/roadmap/fusaka/) · [CoinDesk activation](https://www.coindesk.com/tech/2025/12/03/ethereum-activates-fusaka-upgrade-aiming-to-cut-node-costs-speed-layer-2-settlements)
- BNB Smart Chain Fermi/Maxwell: [BNB Chain blog — Fermi 0.45s](https://www.bnbchain.org/en/blog/fermi-hard-fork-accelerates-bsc-to-0-45-second-block-times) · [Phemex — Maxwell](https://phemex.com/blogs/bnb-maxwell-upgrade-faster-block-times) · [BSC docs](https://docs.bnbchain.org/bnb-smart-chain/introduction/)
- Avalanche: [Avalanche Builder Hub — Etna/Avalanche9000](https://build.avax.network/academy/avalanche-l1/avalanche-fundamentals/03-multi-chain-architecture-intro/03a-etna-upgrade) · [CryptoAdventure — finality/fees 2026](https://cryptoadventure.com/avalanche-review-2026-near-instant-finality-l1-flexibility-and-the-real-fee-economics/)
- Polygon PoS Rio / Heimdall v2 / Bhilai: [Polygon — 5s finality Heimdall v2](https://polygon.technology/blog/polygon-5-second-fast-finality-upgrade) · [AlexaBlockchain — Rio hardfork live](https://alexablockchain.com/polygon-pos-rio-hardfork-live-on-mainnet/) · [Stakin — Bhilai/Heimdall](https://stakin.com/blog/understanding-polygons-bhilai-and-heimdall-upgrades-finality-1000-tps-and-gasless-ux)
- Berachain PoL v2 / BeaconKit: [Berachain V2 blog](https://blog.berachain.com/blog/berachain-v2-an-explanation-of-the-changes-how-it-affects-pol-dynamics-and-why-its-an-important-next-step) · [beacon-kit (GitHub)](https://github.com/berachain/beacon-kit) · [OKX — PoL v2](https://www.okx.com/en-us/learn/berachain-proof-of-liquidity-v2)
- Gnosis Chain: [gnosis.io/chain](https://www.gnosis.io/chain) · [Gnosis docs — node architecture](https://docs.gnosischain.com/node/architecture) · [Nethermind Sedge — Gnosis](https://docs.sedge.nethermind.io/docs/networks/gnosis)
- OP Stack / Arbitrum: [OP Stack fault-proof spec](https://specs.optimism.io/fault-proof/index.html) · [Chainstack — Arbitrum vs Optimism RPC 2026](https://chainstack.com/arbitrum-vs-optimism-rpc-infrastructure/) · [Eco — OP Stack architecture](https://eco.com/support/en/articles/11779236-what-is-the-op-stack-architecture-and-superchain-explained)
- ZK rollups (Scroll/Linea/zkSync/Polygon zkEVM): [Eco — Scroll native zkEVM](https://eco.com/support/en/articles/15183713-what-is-scroll-native-zkevm-l2-explained) · [Eco — zkSync vs Linea vs Scroll](https://eco.com/support/en/articles/14798705-zksync-vs-linea-vs-scroll-zk-rollups-compared) · [thirdweb — zkEVM comparison](https://blog.thirdweb.com/polygon-zkevm-vs-zksync-era-vs-linea-vs-scroll-vs-taiko/)
- Neon EVM: [Consensys — Neon EVM on Solana](https://consensys.net/blog/cryptoeconomic-research/neon-an-ethereum-virtual-machine-on-solana/) · [neonevm.org](https://www.neonevm.org/) · [Solana — EVM to SVM](https://solana.com/developers/evm-to-svm/smart-contracts)
- Ronin: [Axie whitepaper — Ronin sidechain](https://whitepaper.axieinfinity.com/technology/ronin-ethereum-sidechain) · [ChainCatcher — Ronin sidechain → L2](https://www.chaincatcher.com/en/article/2197969) · [Eco — Ronin](https://eco.com/support/en/articles/13003863-what-is-ronin-the-sky-mavis-gaming-chain-behind-axie-infinity)
- Reference-client tree precedent: `docs/research/network-landscape/reference-client-tree-structure.md` (companion, on disk)
