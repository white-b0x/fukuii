# Hardfork Implementation Checklist

Non-exhaustive extension-point map for a new EIP/ECIP hard fork — names objects/classes to
open, not files-by-line-number (goes stale) or process steps
(`consensus-change-protocol.md` owns *when* to stop and consult `forge`/`beacon`; this is
only *where the code lives*). Shape ported from Reth's `HARDFORK-CHECKLIST.md`. See also
`src/main/scala/com/chipprbots/ethereum/consensus/AGENTS.md` for the full directory map.

The network-prefixed fork opcode/fee-schedule objects in `vm/OpCode.scala`/`vm/EvmConfig.scala`
are the current naming convention: `EtcOlympiaOpCodes`/`EtcOlympiaFeeSchedule` (ETC,
block-number path) and `EthCancunOpCodes`/`EthOsakaOpCodes`/`EthCancunFeeSchedule`/
`EthOsakaFeeSchedule` (ETH, timestamp path) are independently defined — never a shared
unprefixed name. (`PARITY-02`, `.claude/sprints/QUEUE.md`, landed the de-alias in Batch 5
Row 5.1, `b46e21ea1` — the earlier unprefixed `OlympiaOpCodes`/`OsakaOpCodes`/`OlympiaFeeSchedule`
names no longer exist.) Verify the current object names in `vm/OpCode.scala`/`vm/EvmConfig.scala`
before relying on any literal name here, since a future rename could move the goalposts again.

## PoW-only (ETC — block-number dispatch)

- New opcodes at a block number → `vm/OpCode.scala` list object (**`EtcOlympiaOpCodes`**),
  dispatched via `EvmConfig.forBlock(blockNumber, blockchainConfig)` (`vm/EvmConfig.scala`)
- New/changed fork activation block → `utils/BlockchainConfig.scala`
- Fixed-supply emission changes (ECIP-1017-style) → `ledger/BlockRewardCalculator.scala`
- PoW header validation → `consensus/pow/validators/{EthashBlockHeaderValidator,PoWBlockHeaderValidator}.scala`

## PoS-only (ETH — timestamp dispatch)

- New opcodes at a timestamp → `vm/OpCode.scala` list object (**`EthCancunOpCodes`**,
  **`EthOsakaOpCodes`** — each independently defined), dispatched via the timestamp
  overload `EvmConfig.forBlock(blockNumber, timestamp, blockchainConfig)`
- New/changed fork activation timestamp → `utils/BlockchainConfig.scala`'s `isXTimestamp`
  predicates (`isShanghaiTimestamp`, `isCancunTimestamp`, `isPragueTimestamp`, `isOsakaTimestamp`)
- Post-merge header rules (withdrawals root, blob gas) → `consensus/pos/PoSBlockHeaderValidator.scala`
- PoW→PoS transition boundary → `consensus/TransitionBlockHeaderValidator.scala`
- New `engine_newPayloadVX`/`engine_getPayloadVX` pair → `consensus/pos/` (`EngineApiController`/`EngineApiService`)

## Shared

- Gas cost table per fork → `vm/EvmConfig.scala`'s `FeeSchedule` object (**`EtcOlympiaFeeSchedule`**
  for ECIP-1121, **`EthPragueFeeSchedule`**, **`EthOsakaFeeSchedule`** for ETH — each
  independently defined, never a shared unprefixed name)
- Genesis/chain-config schema → `blockchain/data/genesis.scala` + `blockchain/data/GenesisDataLoader.scala`
- EIP-1559 base-fee changes → `consensus/eip1559/BaseFeeCalculator.scala` — read open
  `PARITY-01` (`.claude/sprints/QUEUE.md`) first; `EngineApiService.scala` has a second,
  possibly-diverging inline base-fee calc

## Recommended, not blocking

`forge`/`beacon` should jointly spot-check this checklist within one sprint — don't block
merge on scheduling that review.
