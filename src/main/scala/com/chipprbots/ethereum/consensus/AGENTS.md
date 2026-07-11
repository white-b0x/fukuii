# consensus — PoW/PoS Consensus Dispatch

<!-- breadcrumb-currency: directory/file listing verified against source tree 2026-07-10 (ed2498430, after the Batch 5 5.5b mechanism-leaf reorg); re-verify when subpackages are added/removed/renamed, not on every code change inside existing files -->

Fukuii supports two independent consensus families from one codebase: **PoW** (ETC/Mordor,
Ethash, block-number fork dispatch) and **PoS** (ETH/Sepolia, Engine-API-driven, timestamp fork
dispatch). The tree is organized by **consensus mechanism**: `pow/` and `pos/` leaves (a reserved
`poa/` seam, built in Batch 7), with a **neutral spine** at the top level —
`Consensus`/`ConsensusImpl`/`ConsensusAdapter` (family selection) plus the family-spanning dispatch
(`ConsensusEngine`/`engineFor`, `ValidatorsExecutor`, `TransitionBlockHeaderValidator`) that decides
*which* mechanism a block uses. Networks stay config/data — a new network is a new conf, not a new
package.

**Before touching anything here, read root `AGENTS.md`'s "PoW vs PoS" section and
`.agents/protocols/consensus-change-protocol.md` — this is the one subsystem in the repo with a
mandatory pre-implementation gate (forge for PoW, beacon for PoS, both in sequence for shared
code).**

## Directory Structure

| Path | Family | Purpose |
|------|--------|---------|
| `consensus/` (neutral spine) | Shared/neutral | Family dispatch (`Consensus`, `ConsensusImpl`, `ConsensusAdapter`) + the family-spanning seam: `ConsensusEngine` (EngineId enum + trait + `engineFor`/`engineIdFor`), `ValidatorsExecutor`/`StdValidatorsExecutor` (TTD-aware validator resolver), `TransitionBlockHeaderValidator` (per-header PoW↔PoS dispatcher) |
| `consensus/blocks/` | Shared | Block generation skeleton (`BlockGenerator`, `NoOmmersBlockGenerator`) |
| `consensus/eip1559/` | Shared (post-Olympia PoW too) | `BaseFeeCalculator.scala` — see PARITY-01 below before editing |
| `consensus/mining/` | Shared interface | `Mining` interface, `MiningConfig`/`FullMiningConfig`/builders |
| `consensus/validators/` | Shared interface | `BlockHeaderValidator`, `BlockValidator`, `SignedTransactionValidator` |
| `consensus/validators/std/` | Shared, network-agnostic impl | `StdBlockValidator`, `StdSignedTransactionValidator`, `StdValidators`, `MptListValidator` |
| `consensus/pow/` | **PoW mechanism** | Ethash: `EthashConfig/Utils`, `EthashEngine`, `PoWMining`/`PoWMiningCoordinator`, `RestrictedPoWSigner`, `WorkNotifier` |
| `consensus/pow/blocks/` | PoW | `PoWBlockGenerator`, `RestrictedPoWBlockGeneratorImpl` |
| `consensus/pow/difficulty/` | PoW | `DifficultyCalculator` (selector) + `EthashDifficultyCalculator`, `TargetTimeDifficultyCalculator` |
| `consensus/pow/miners/` | PoW | `EthashMiner`, `EthashDAGManager`, `MockedMiner` |
| `consensus/pow/ommers/` | PoW | `OmmersPool` (YP uncle mempool actor) |
| `consensus/pow/mess/` | PoW-family (currently ETC) | `ArtificialFinality`/`MESSConfig` — ECIP-1100 MESS anti-51% subjective fork-choice (banksy-owned policy) |
| `consensus/pow/validators/` | PoW | `EthashBlockHeaderValidator`, `OmmersValidator`/`StdOmmersValidator`, `PoWBlockHeaderValidator` |
| `consensus/pos/` | **PoS mechanism** | Engine-API surface: `EngineApiController/Service/Domain/HttpServer/Metrics`, `ForkChoiceManager/State`, `PoSBlockHeaderValidator`, `JwtAuthenticator`, `EngineApiEngine` |
| `consensus/poa/` | **PoA mechanism (reserved)** | Not yet created — `EngineId.Clique/Qbft/Bor` reserve the seam; built in Batch 7 (Private Network Stack, NET-02 Clique-first) |

**Note**: three validator tiers, not a `validators/{pow,std}` sibling pair — the family-spanning
RESOLVER (`ValidatorsExecutor`) lives in the neutral spine (`consensus/`), the PoW-specific validator
IMPLS in `pow/validators/`, and the network-agnostic std impls in `validators/std/`.

## Improvement proposals (EIPs/ECIPs) — NOT in this subsystem

`consensus/` is the consensus **mechanism** (how blocks are produced/sealed/validated/scored).
Improvement **proposals** ("what execution rules apply, and when") live in a separate,
network-agnostic layer — EIPs, ECIPs, and `Custom` family proposals are handled UNIFORMLY:

- **Registry (what exists + when it activates):** `forks/` (`ProposalId` = `Eip(n)` / `Ecip(n)` /
  `Custom(f, n)`, `ForkSchedule`, `ForkActivation`) · `vm/forks/EvmProposals.scala` (EVM proposals) ·
  `ledger/forks/RewardProposals.scala` (reward/emission). One authoritative list — not scattered.
- **Behavior (impl-by-concern):** opcodes → `vm/OpCode.scala` · gas/fee schedules →
  `vm/EvmConfig.scala` · base fee (EIP-1559) → `consensus/eip1559/BaseFeeCalculator.scala` · rewards
  (ECIP-1017) → `ledger/BlockRewardCalculator.scala`.
- ETC composes adopted **EIPs** (shared with ETH, activated at ETC block heights) + native **ECIPs**
  via its fork schedule — both are just `ProposalId`s. Only PoW-**mechanism** ECIPs (1099 ETChash,
  1010/1041 difficulty, 1100 MESS) have their compute in `pow/`; their fork *definition* still lives
  in the neutral registry above.

## Known live issue — read before touching `eip1559/`

`PARITY-01` (open, `.claude/sprints/QUEUE.md` Chase & Deferred Items): `BaseFeeCalculator.scala`'s
decrease branch may not floor the delta at 1 like go-ethereum/Besu do; `EngineApiService.scala`
has a duplicate inline base-fee calc with a possibly different floor policy. Gate: forge/beacon
review + independent read of go-ethereum/Besu source before changing either.

## Future follow-up (not this batch)

Erigon's `cl/` subsystem has a second, deeper tier of per-fork, function-to-spec-section map
files beyond its flat breadcrumbs — see `erigon/agentic-tooling-pattern.md`. Could be a strong
model for this subsystem's own ETC-vs-ETH fork-by-fork complexity, but it's a substantial
standalone documentation project needing forge+beacon authorship — tracked as a future item,
not started here.

## Cross-references

- Consensus authority: `forge` (PoW), `beacon` (PoS) — both in sequence for shared code
- Gate: `.agents/protocols/consensus-change-protocol.md`
- Deferred parity finding: `PARITY-01` in `.claude/sprints/QUEUE.md`
