# Hardfork Implementation Checklist

Non-exhaustive extension-point map for a new EIP/ECIP hard fork — names objects/classes to
open, not files-by-line-number (goes stale) or process steps
(`consensus-change-protocol.md` owns *when* to stop and consult `forge`/`beacon`; this is
only *where the code lives*). Shape ported from Reth's `HARDFORK-CHECKLIST.md`. See also
`src/main/scala/com/chipprbots/ethereum/consensus/AGENTS.md` for the full directory map.

## PoW-only (ETC — block-number dispatch)

- New opcodes at a block number → `vm/OpCode.scala` list object (e.g. `OlympiaOpCodes`),
  dispatched via `EvmConfig.forBlock(blockNumber, blockchainConfig)` (`vm/EvmConfig.scala`)
- New/changed fork activation block → `utils/BlockchainConfig.scala`
- Fixed-supply emission changes (ECIP-1017-style) → `ledger/BlockRewardCalculator.scala`
- PoW header validation → `consensus/pow/validators/{EthashBlockHeaderValidator,PoWBlockHeaderValidator}.scala`

## PoS-only (ETH — timestamp dispatch)

- New opcodes at a timestamp → `vm/OpCode.scala` list object (e.g. `OsakaOpCodes` —
  currently aliased to `OlympiaOpCodes`; split it once the two sets diverge), dispatched
  via the timestamp overload `EvmConfig.forBlock(blockNumber, timestamp, blockchainConfig)`
- New/changed fork activation timestamp → `utils/BlockchainConfig.scala`'s `isXTimestamp`
  predicates (`isShanghaiTimestamp`, `isCancunTimestamp`, `isPragueTimestamp`, `isOsakaTimestamp`)
- Post-merge header rules (withdrawals root, blob gas) → `consensus/engine/PoSBlockHeaderValidator.scala`
- PoW→PoS transition boundary → `consensus/engine/TransitionBlockHeaderValidator.scala`
- New `engine_newPayloadVX`/`engine_getPayloadVX` pair → `consensus/engine/` (`EngineApiController`/`EngineApiService`)

## Shared

- Gas cost table per fork → `vm/FeeSchedule.scala` (`OlympiaFeeSchedule`, `PragueFeeSchedule`, `OsakaFeeSchedule`)
- Genesis/chain-config schema → `blockchain/data/genesis.scala` + `blockchain/data/GenesisDataLoader.scala`
- EIP-1559 base-fee changes → `consensus/eip1559/BaseFeeCalculator.scala` — read open
  `PARITY-01` (`.claude/sprints/QUEUE.md`) first; `EngineApiService.scala` has a second,
  possibly-diverging inline base-fee calc

## Recommended, not blocking

`forge`/`beacon` should jointly spot-check this checklist within one sprint — don't block
merge on scheduling that review.
