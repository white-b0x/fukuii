# consensus — PoW/PoS Consensus Dispatch

<!-- breadcrumb-currency: directory/file listing verified against source tree 2026-07-05 (a68dbec1f); re-verify when subpackages are added/removed/renamed, not on every code change inside existing files -->

Fukuii supports two independent consensus families from one codebase: **PoW** (ETC/Mordor,
Ethash, block-number fork dispatch) and **PoS** (ETH/Sepolia, Engine-API-driven, timestamp fork
dispatch). `Consensus.scala`/`ConsensusImpl.scala`/`ConsensusAdapter.scala` are the top-level
family-selection layer; almost everything else here is PoW-only, PoS-only, or shared.

**Before touching anything here, read root `AGENTS.md`'s "PoW vs PoS" section and
`.agents/protocols/consensus-change-protocol.md` — this is the one subsystem in the repo with a
mandatory pre-implementation gate (forge for PoW, beacon for PoS, both in sequence for shared
code).**

## Directory Structure

| Path | Family | Purpose |
|------|--------|---------|
| `consensus/` (top-level) | Shared | Family dispatch: `Consensus`, `ConsensusImpl`, `ConsensusAdapter` |
| `consensus/blocks/` | Shared | Block generation skeleton (`BlockGenerator`, `NoOmmersBlockGenerator`) |
| `consensus/difficulty/` | Shared interface | `DifficultyCalculator` — PoW's actual implementations live in `pow/difficulty/` |
| `consensus/eip1559/` | Shared (post-Olympia PoW too) | `BaseFeeCalculator.scala` — see PARITY-01 below before editing |
| `consensus/engine/` | **PoS only** | Engine API surface: `EngineApiController/Service/HttpServer`, `ForkChoiceManager/State`, `PoSBlockHeaderValidator`, `TransitionBlockHeaderValidator`, `JwtAuthenticator` |
| `consensus/mess/` | **PoW only** (ETC) | `ArtificialFinality.scala`/`MESSConfig.scala` — ECIP-1100 MESS anti-51% |
| `consensus/mining/` | Shared interface | `Mining` interface, `MiningConfig`/`FullMiningConfig`/builders |
| `consensus/pow/` | **PoW only** | Ethash: `EthashConfig/Utils`, `PoWMining`/`PoWMiningCoordinator`, `RestrictedPoWSigner`, `WorkNotifier` |
| `consensus/pow/blocks/` | PoW only | `PoWBlockGenerator`, `RestrictedPoWBlockGeneratorImpl` |
| `consensus/pow/difficulty/` | PoW only | `EthashDifficultyCalculator`, `TargetTimeDifficultyCalculator` |
| `consensus/pow/miners/` | PoW only | `EthashMiner`, `EthashDAGManager`, `MockedMiner` |
| `consensus/pow/validators/` | PoW only | `EthashBlockHeaderValidator`, `OmmersValidator`/`StdOmmersValidator`, `PoWBlockHeaderValidator`, `ValidatorsExecutor`/`StdValidatorsExecutor` |
| `consensus/validators/` | Shared interface | `BlockHeaderValidator`, `BlockValidator`, `SignedTransactionValidator` |
| `consensus/validators/std/` | Shared, network-agnostic impl | `StdBlockValidator`, `StdSignedTransactionValidator`, `StdValidators`, `MptListValidator` |

**Note**: the PoW/standard-validator split is `pow/validators/` (PoW-specific) vs.
`validators/std/` (network-agnostic) — different parents, not a `validators/{pow,std}` sibling
pair.

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
