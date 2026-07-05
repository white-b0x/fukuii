# Hardfork Implementation Checklist

Non-exhaustive extension-point map for a new EIP/ECIP hard fork — names objects/classes to
open, not files-by-line-number (goes stale) or process steps
(`consensus-change-protocol.md` owns *when* to stop and consult `forge`/`beacon`; this is
only *where the code lives*). Shape ported from Reth's `HARDFORK-CHECKLIST.md`. See also
`src/main/scala/com/chipprbots/ethereum/consensus/AGENTS.md` for the full directory map.

**Read `PARITY-02` (`.claude/sprints/QUEUE.md` Chase & Deferred Items) before touching any
`vm/OpCode.scala`/`vm/EvmConfig.scala` opcode or fee-schedule object named after a fork.**
The unprefixed `OlympiaOpCodes`/`OlympiaFeeSchedule` names are misleadingly ETC-sounding but
are actually what ETH's timestamp path uses for Cancun/Osaka; ETC's real block-number path
uses the differently-named `EtcOlympiaOpCodes`. `OsakaOpCodes` is currently a bare alias
(`= OlympiaOpCodes`), not an independent definition. Gated on forge+beacon joint review before
any restructure — do not assume today's naming reflects which network a given object actually
drives.

## PoW-only (ETC — block-number dispatch)

- New opcodes at a block number → `vm/OpCode.scala` list object (**`EtcOlympiaOpCodes`** —
  NOT the unprefixed `OlympiaOpCodes`, which ETH's timestamp path actually uses, see `PARITY-02`
  above), dispatched via `EvmConfig.forBlock(blockNumber, blockchainConfig)` (`vm/EvmConfig.scala`)
- New/changed fork activation block → `utils/BlockchainConfig.scala`
- Fixed-supply emission changes (ECIP-1017-style) → `ledger/BlockRewardCalculator.scala`
- PoW header validation → `consensus/pow/validators/{EthashBlockHeaderValidator,PoWBlockHeaderValidator}.scala`

## PoS-only (ETH — timestamp dispatch)

- New opcodes at a timestamp → `vm/OpCode.scala` list object (Cancun uses the unprefixed
  `OlympiaOpCodes`; `OsakaOpCodes` is currently `= OlympiaOpCodes`, a bare alias — see
  `PARITY-02` above before adding to or splitting either), dispatched via the timestamp
  overload `EvmConfig.forBlock(blockNumber, timestamp, blockchainConfig)`
- New/changed fork activation timestamp → `utils/BlockchainConfig.scala`'s `isXTimestamp`
  predicates (`isShanghaiTimestamp`, `isCancunTimestamp`, `isPragueTimestamp`, `isOsakaTimestamp`)
- Post-merge header rules (withdrawals root, blob gas) → `consensus/engine/PoSBlockHeaderValidator.scala`
- PoW→PoS transition boundary → `consensus/engine/TransitionBlockHeaderValidator.scala`
- New `engine_newPayloadVX`/`engine_getPayloadVX` pair → `consensus/engine/` (`EngineApiController`/`EngineApiService`)

## Shared

- Gas cost table per fork → `vm/FeeSchedule.scala` (**`OlympiaFeeSchedule`** — despite the
  name, this is ETH's Cancun fee schedule, not ECIP-1111's; see `PARITY-02` —, `PragueFeeSchedule`,
  `OsakaFeeSchedule`)
- Genesis/chain-config schema → `blockchain/data/genesis.scala` + `blockchain/data/GenesisDataLoader.scala`
- EIP-1559 base-fee changes → `consensus/eip1559/BaseFeeCalculator.scala` — read open
  `PARITY-01` (`.claude/sprints/QUEUE.md`) first; `EngineApiService.scala` has a second,
  possibly-diverging inline base-fee calc

## Recommended, not blocking

`forge`/`beacon` should jointly spot-check this checklist within one sprint — don't block
merge on scheduling that review.
