# Private / Permissioned Networks — PoA / BFT capability spec for fukuii

_Read-only research, 2026-07-10 (web-verified where dated). Batch 5, Part C of the network-landscape
survey. Pairs with `reference-client-tree-structure.md` (the package-layout precedent) and
`01-*`/Part A (fukuii current-state audit) / Part D (synthesis + target tree) — this file owns the
**PoA/BFT consensus-engine seam** analysis and the **gap list**, and defers current-state inventory and
final target-tree shape to those. Besu paths verified on disk under
`/media/dev/2tb/dev/reference-clients-evm/besu/`._

## Why this arm is the sharpest test of fukuii's abstraction

A **Clique/PoA devnet is the concrete "third network fukuii could add next"** with the smallest external
dependency — no consensus-layer client (unlike PoS), no external miner fleet (unlike PoW mainnet). It is
therefore the load-bearing stress test of the `consensus/engine/ConsensusEngine` seam
(`EngineId.{Ethash,EngineApi,Clique,Qbft,Bor}` — `Clique`/`Qbft` already exist as reserved seams that
`throw NotImplementedError`). Everything below is framed as additions to the **four-seam model**
(**selection · scheduling · finality · fork-choice**), the vocabulary Part D synthesizes against.

The headline finding: **fukuii's biggest missing piece is not a consensus algorithm — it is a *sealing
path*.** fukuii today only *verifies* PoW seals and *validates* externally-supplied PoS payloads; it has
no code that *produces and signs* a block itself. PoA is the first family that makes fukuii the block
author, and that gap dominates the effort estimate.

## 1. Per-mechanism table (the four seams + header fields + validator source + finality)

| | **Clique (PoA)** | **IBFT 2.0 (BFT)** | **QBFT (BFT)** | **Aura (PoA)** |
|---|---|---|---|---|
| **Selection** (who may produce block N) | In-turn signer = `blockNumber mod N` of the sorted signer set (difficulty 2); others out-of-turn (difficulty 1), rate-limited to 1 block per `⌊N/2⌋+1` | Proposer = deterministic f(round, validator set), round-robin; block then *voted* on by validators | Same as IBFT2 (round-robin proposer) + formally-verified round-change | Primary = `step mod N`, `step = unixTime / stepDuration` (wall-clock round-robin) |
| **Scheduling** (when) | `blockperiodseconds` block time; in-turn seals immediately, out-of-turn adds random wiggle | `blockperiodseconds` + `requesttimeoutseconds`; timeout → round-change | Same + tunable `xemptyblockperiodseconds` | Fixed `stepDuration`; empty-step messages instead of empty blocks |
| **Finality** | **Probabilistic** — longest-chain / total-difficulty (in-turn blocks weigh 2×) | **Instant** — a block is final once committed; ≥ `2f+1` COMMIT seals (N ≥ `3f+1`, min 4 validators) | **Instant** — same BFT guarantee, stronger liveness | Probabilistic + a finality gadget (final when > 2/3 validators sealed above) |
| **Fork-choice** | Existing GHOST / highest-total-difficulty (reused unchanged) | **None** — immediate finality means no forks to choose between | **None** — same | "Most-sealed" chain by authorized steps |
| **Header fields** | `extraData` = 32-byte vanity + Nx20-byte signer list (epoch blocks only) + 65-byte proposer seal; `nonce` = vote (`0xff…ff` ADD / `0x0` DROP); `coinbase` = vote candidate; `difficulty` ∈ {1,2}; `mixHash`=0 | `extraData` (RLP codec) = vanity + validators + optional Vote + round + Collection of committer seals; `mixHash` = fixed IBFT constant; `difficulty` = 1 | Same shape; **round number carried in extraData**; QBFT RLP layout differs from IBFT2 | Seal fields = step number + validator signature (Parity-style header seal list) |
| **Validator-set source** | Genesis signer list in `extraData`, mutated by in-header **votes** (`clique_propose`) | Genesis `extraData` **or** validator **smart contract** (`getValidators()`) | Same two modes, switchable at a `startBlock` transition (`ForkingValidatorProvider`) | Genesis list, static-contract, or reporting/voting **contract** (`getValidators()`) |
| **Status (2026)** | **Deprecated for block production from Besu 25.12.0** — sync-only; dev-only, never production | Supported for existing nets; superseded by QBFT | **Recommended enterprise default** (EEA-compliant) | OpenEthereum/Parity legacy; no maintained JVM impl — reference only |

Sources: [Besu PoA concepts](https://besu.hyperledger.org/private-networks/concepts/poa),
[Besu Clique](https://besu.hyperledger.org/private-networks/how-to/configure/consensus/clique),
[Besu QBFT](https://besu.hyperledger.org/private-networks/how-to/configure/consensus/qbft),
[Besu IBFT 2.0](https://besu.hyperledger.org/private-networks/how-to/configure/consensus/ibft),
[OpenEthereum Aura](https://openethereum.github.io/Aura),
[Web3 Labs PBFT/IBFT/QBFT](https://medium.com/web3labs/exploring-pbft-ibft-and-qbft-flavours-of-byzantine-fault-tolerance-consensus-algorithms-1ca847d8b523).

**Two archetypes, one clean split:** Clique + Aura are *probabilistic PoA* — they reuse the existing
fork-choice and finality; the only genuinely new machinery is **selection** (authorized signer) +
**sealing**. IBFT2 + QBFT are *instant-finality BFT* — they add a whole **round-based voting state
machine** (proposal → prepare → commit messages, round timers, commit-seal aggregation) and *collapse*
the fork-choice seam to nothing. This split is the crux of the deferral recommendation in §4.

## 2. How Besu (and GoQuorum) run public PoW/PoS **and** private PoA/BFT in one binary

This is the transferable lesson — the direct precedent for fukuii's `ConsensusEngine`/`engineFor`. Besu's
abstraction is a **four-layer object structure**; fukuii already mirrors three of the four.

**Layer A — pluggable mechanism packages, PoA sharing a `common/` subtree.**
`consensus/{clique, ibft, ibftlegacy, qbft, qbft-core, merge, common}`. All BFT variants share
`consensus/common/bft/…` (~60 files: `BftExtraData`, `BftBlockHashing`, `BftProtocolSchedule`, the
`statemachine/` round engine, `validator/` provider interfaces). Clique is a standalone sibling because
it shares only the *neutral* `common/` seams (`BlockInterface`, `ValidatorProvider`), not the BFT round
machinery.

**Layer B — neutral spine outside `consensus/`.** `ethereum/core/…/mainnet/{ProtocolSchedule,
ProtocolSpec, ProtocolScheduleBuilder}`. A `ProtocolSpec` bundles per-fork {header validator, block
processor, block importer, …}; a mechanism package supplies its *own* `ProtocolSchedule` factory
(`CliqueProtocolSchedule.create(...)`, `QbftProtocolScheduleBuilder`) that injects consensus-specific
header-validation rulesets into that neutral spine. **fukuii equivalent already exists**: the
`ConsensusEngine` trait's `headerValidator` / `blockGenerator` / `finalizeBlock` methods *are* the
per-engine slice of a `ProtocolSpec`.

**Layer C — transition = composite, not subclass.** `consensus/merge/TransitionProtocolSchedule
implements ProtocolSchedule` holds a pre- and post-Merge schedule and dispatches per header via
`TransitionUtils`. **fukuii equivalent already exists**: `TransitionBlockHeaderValidator` (routes on
`difficulty == 0`) wrapped inside the single family-stable `EngineApiEngine`.

**Layer D — abstract selector.** `app/…/controller/BesuControllerBuilder` (abstract) →
`{Mainnet, Clique, Ibft, Qbft, Merge, Transition}BesuControllerBuilder` subclasses, each overriding
`createProtocolSchedule()`, `createMiningCoordinator()`, `createConsensusContext()`. This is the pattern
fukuii's `ConsensusEngine.engineFor(mining, blockchainConfig)` collapses into a single dispatch —
appropriate because fukuii selects at **network-family granularity**, not Besu's per-genesis-option
granularity.

**The two neutral seams that make it work — and that fukuii lacks:**
- **`ValidatorProvider`** (`consensus/common/validator/ValidatorProvider.java`): the *entire* abstraction
  over "where does the validator set live?" — `getValidatorsForBlock(header)`,
  `getValidatorsAfterBlock(header)`, `getVoteProviderAtHead()`. Two implementations plug in behind it:
  `BlockValidatorProvider` (walks headers/votes — Clique + BFT block-header mode) and a
  `TransactionValidatorProvider` backed by `ValidatorContractController` (BFT contract mode, which just
  ABI-calls `getValidators()` at a block via the `TransactionSimulator`). `ForkingValidatorProvider`
  composes the two so a network can *switch modes* at a `startBlock`. **This one interface is the single
  most important thing to port.**
- **`BlockInterface`** (`consensus/common/BlockInterface.java`): extracts consensus meaning from an
  otherwise-opaque header — `getProposerOfBlock(header)`, `validatorsInBlock(header)`,
  `extractVoteFromHeader(header)`. `CliqueBlockInterface` decodes the vote from `nonce`+`coinbase`;
  `BftBlockInterface` decodes it from the RLP `extraData`. fukuii's `extraData` is currently opaque bytes.

**GoQuorum** reaches the same end differently: it is a go-ethereum fork that *adds* IBFT/QBFT/Raft
`consensus.Engine` implementations alongside geth's `ethash`/`clique`, i.e. it leans on geth's existing
`consensus.Engine` interface (fukuii's `ConsensusEngine` analogue) rather than inventing a new spine.
Both Besu and GoQuorum implement the **EEA client spec**, which is why their genesis/permissioning
surfaces converge.

## 3. Permissioning — a node-layer concern, orthogonal to consensus

Permissioning is **not** a consensus seam and should not be modeled as one. Two independent allowlists,
each at two possible layers:

| Allowlist | What it gates | Layer | Owner in an EVM client |
|---|---|---|---|
| **Node allowlist** | Which enodes may peer/connect | Local (config file) **or** onchain (contract) | P2P/networking layer (fukuii: `herald`'s domain), *not* consensus |
| **Account allowlist** | Which senders' txs may be included / which blocks import | Local **or** onchain (contract, checked at block import) | Txpool-admission + block-import layer (fukuii: `banksy`'s domain) |

Onchain permissioning stores both lists in smart contracts so all nodes read one source of truth; a node
"only imports blocks in which all transactions are from authorized senders"
([Besu onchain permissioning](https://besu.hyperledger.org/stable/private-networks/concepts/permissioning/onchain)).
**Takeaway for fukuii:** permissioning is a *later, separable* feature routed to `herald` (node) and
`banksy` (account) — it is **not** a prerequisite for standing up a private network and must not bloat the
`ConsensusEngine` seam. Defer entirely; a Clique devnet needs none of it.

## 4. The gap list — what fukuii LACKS for a PoA/BFT private net

Each gap is tagged against a specific seam and given a minimal-viable-abstraction verdict
(**NOW** = needed for a Clique devnet; **DEFER** = only one foreseeable implementation today, or BFT-only).

| # | Gap | Seam | Verdict | Rationale |
|---|---|---|---|---|
| G1 | **Block-sealing path** — a signer that produces a block, ECDSA-signs its hash, and writes the 65-byte seal into `extraData`. fukuii only *verifies* PoW / *validates* PoS payloads. | selection (production side) — fukuii's `ConsensusEngine.sealer: Option[Mining]` is PoW-miner-only | **NOW** | The single biggest missing component. No PoA network exists without it. A new `Sealer`/`BlockSigner` beside the existing PoW `Mining`. |
| G2 | **`ValidatorProvider` seam** — `getValidatorsForBlock(header)` / `getValidatorsAfterBlock`. No fukuii equivalent. | selection (who is authorized) — a NEW seam beside `ConsensusEngine` | **NOW** | This *is* PoA — "who may sign." Start with the block-header/vote-walking impl; leave the contract-mode impl behind the same trait for later. |
| G3 | **`BlockInterface` seam** — decode proposer/validators/vote from a header; `extraData` is currently opaque. | selection | **NOW** | Needed by G2 and by header validation. Small — one Clique impl (`nonce`+`coinbase` vote, signer list slice). |
| G4 | **`extraData` codec** — Clique's 32-byte-vanity + signer-list + 65-byte-seal encode/decode; genesis `extraData` builder. | selection | **NOW** | ~1 file; ported near-verbatim from `CliqueExtraData`. |
| G5 | **PoA header-validation ruleset** — signer authorization, in-turn/out-of-turn difficulty (1 vs 2), signing rate-limit, vote validity, `mixHash`=0. | selection — plugs into `ConsensusEngine.headerValidator` (seam EXISTS) | **NOW** | Reuses the existing `BlockHeaderValidator` seam; only the rules are new. |
| G6 | **Block-production scheduler** — block-period timer; in-turn immediate vs out-of-turn delayed sealing. | **scheduling** | **NOW (thin)** | Lightweight: a periodic timer + the difficulty rule. Not a new subsystem — a small scheduler beside the sealer. |
| G7 | **Config surface** — genesis `clique`/`poa` stanza (`blockperiodseconds`, `epochlength`, `createemptyblocks`); a `NetworkType.PoA` (or reuse per-genesis marker); wire `EngineId.Clique` in `engineIdFor`. | (cross-cutting) | **NOW** | `engineIdFor` currently dispatches only on `NetworkType.{ETC,ETH}`; PoA needs a third family marker. |
| G8 | **Fork-choice seam** — a pluggable rule. | **fork-choice** | **DEFER** | Clique/Aura reuse the *existing* total-difficulty/GHOST rule unchanged; MESS is already an ETC-only concern. Only BFT would change it (to "none"), and BFT is deferred. One foreseeable impl → do not abstract. |
| G9 | **Finality seam** — an explicit finalized-block signal. | **finality** | **DEFER** | Clique/Aura are probabilistic → reuse existing longest-chain. Only instant-finality BFT needs this. Defer *with* BFT (G10). |
| G10 | **BFT round state machine** — proposal/prepare/commit message types, round timers, round-change, commit-seal aggregation, instant finality, the `ValidatorContractController` mode. | selection + finality (both) | **DEFER** | This is IBFT2/QBFT's bulk — Besu's `common/bft` + `qbft-core` ≈ 150 files. No concrete consortium requirement yet. Build only when a BFT network is actually on the roadmap; the `EngineId.Qbft` reserved seam already marks the slot. |

## 5. Recommended first private network — a **Clique static-signer devnet**

**Stand up Clique before anything BFT.** Rationale, in minimal-viable-abstraction terms:

- **Reuses two of the four seams unchanged.** Fork-choice (G8) and finality (G9) are the existing
  probabilistic longest-chain path — *zero* new abstraction. Only **selection** and **scheduling** get new
  code. This is exactly the "cleanly covers ETC+ETH today, extends to private-PoA next without a rewrite"
  bar — no seam is added that has only one implementation.
- **Config-only + a bounded code delta.** The **config-only** part: a genesis with a `clique` stanza and a
  signer list baked into `extraData`. The **minimal-code** part is G1–G7: an `extraData` codec, a
  `BlockInterface`, a block-header `ValidatorProvider`, a Clique `BlockHeaderValidator` ruleset, a
  block-signer (`sealer`), a period scheduler, and the `EngineId.Clique` wiring in `engineIdFor`.
- **Smallest first step = single/static-signer, no voting.** Ship G2 as a *static* validator set read from
  genesis first (skip `clique_propose` in-header voting initially) — it exercises the whole
  selection+sealing spine end-to-end with the least surface, then voting is an additive follow-up behind
  the same `ValidatorProvider` trait.
- **Explicitly do NOT build QBFT/IBFT for the first private net.** Their instant-finality round machinery
  (G10) is ~150 files of message/state-machine code and needs a ≥4-validator BFT deployment to even be
  meaningful. Defer until a consortium requirement is concrete; `EngineId.Qbft` already reserves the seam.

**Net:** the first private network is a Clique devnet, and the work it forces fukuii to grow — a sealing
path (G1) and a `ValidatorProvider`/`BlockInterface` selection seam (G2–G3) — is precisely the generally
useful capability fukuii lacks, not throwaway PoA-specific scaffolding. Aura is reference-only (no
maintained JVM impl, legacy). QBFT is the eventual enterprise target but is a separate, later,
BFT-shaped project.

## Handoffs (not duplicated here)

- **Part A (current-state audit):** the full inventory of fukuii's existing `consensus/` tree — this file
  reads only `ConsensusEngine.scala` to frame the seam, and defers the rest.
- **Part D (synthesis / target tree):** where G1–G10 land in the final package layout, and how the
  `pow`/`pos`/`poa` sibling structure from `reference-client-tree-structure.md` absorbs them.
- **Permissioning implementation** routes to `herald` (node allowlist) and `banksy` (account allowlist),
  not the consensus specialists — see §3.
