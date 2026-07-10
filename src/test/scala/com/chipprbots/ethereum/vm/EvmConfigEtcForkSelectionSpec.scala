package com.chipprbots.ethereum.vm

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.testing.Tags.*

import FeeScheduleFields.fields

/** Row 5.3b: `forBlock` now DERIVES the config by folding active proposals, so assertions are on VALUES (opcode
  * `.toSet`, fee 41-field tuple, boolean flags) rather than the old `isInstanceOf`/`OpCodeList ==` representation. The
  * ladders are monotonic (each pre-fork reached) — the old degenerate configs that dated a fork while parking a lower
  * one at a sentinel relied on the removed unconditional-builder-chain behaviour and are no longer meaningful.
  */
class EvmConfigEtcForkSelectionSpec extends AnyFunSuite:

  val allMaxCfg: BlockchainConfigForEvm = BlockchainConfigForEvm(
    frontierBlockNumber = BlockNumber(Long.MaxValue),
    homesteadBlockNumber = BlockNumber(Long.MaxValue),
    eip150BlockNumber = BlockNumber(Long.MaxValue),
    eip160BlockNumber = BlockNumber(Long.MaxValue),
    eip161BlockNumber = BlockNumber(Long.MaxValue),
    byzantiumBlockNumber = BlockNumber(Long.MaxValue),
    constantinopleBlockNumber = BlockNumber(Long.MaxValue),
    istanbulBlockNumber = BlockNumber(Long.MaxValue),
    maxCodeSize = None,
    accountStartNonce = 0,
    atlantisBlockNumber = BlockNumber(Long.MaxValue),
    aghartaBlockNumber = BlockNumber(Long.MaxValue),
    petersburgBlockNumber = BlockNumber(Long.MaxValue),
    phoenixBlockNumber = BlockNumber(Long.MaxValue),
    magnetoBlockNumber = BlockNumber(Long.MaxValue),
    berlinBlockNumber = BlockNumber(Long.MaxValue),
    mystiqueBlockNumber = BlockNumber(Long.MaxValue),
    spiralBlockNumber = BlockNumber(Long.MaxValue),
    olympiaBlockNumber = BlockNumber(Long.MaxValue),
    chainId = ChainId(0x3f)
  )

  // A monotonic ETC ladder with the pre-Atlantis forks reached (so DELEGATECALL/G_txcreate etc. are present) and every
  // post-Atlantis fork co-activated at block 0 up to `olympiaBlockNumber`. byzantium stays at the sentinel — on the ETC
  // (non-Ethereum) path the fold never reads it, so "Atlantis over Byzantium" is structural.
  private def etcLadderTo(olympiaBlock: BlockNumber): BlockchainConfigForEvm = allMaxCfg.copy(
    frontierBlockNumber = BlockNumber(0),
    homesteadBlockNumber = BlockNumber(0),
    eip150BlockNumber = BlockNumber(0),
    eip160BlockNumber = BlockNumber(0),
    atlantisBlockNumber = BlockNumber(0),
    aghartaBlockNumber = BlockNumber(0),
    phoenixBlockNumber = BlockNumber(0),
    magnetoBlockNumber = BlockNumber(0),
    mystiqueBlockNumber = BlockNumber(0),
    spiralBlockNumber = BlockNumber(0),
    olympiaBlockNumber = olympiaBlock
  )

  test("EvmConfig.forBlock applies the Atlantis config (and ignores byzantium on the ETC path)", UnitTest, VMTest) {
    val cfg = allMaxCfg.copy(
      frontierBlockNumber = BlockNumber(0),
      homesteadBlockNumber = BlockNumber(0),
      eip150BlockNumber = BlockNumber(0),
      eip160BlockNumber = BlockNumber(0),
      byzantiumBlockNumber = BlockNumber(0),
      atlantisBlockNumber = BlockNumber(0)
    )

    val evmConfig = EvmConfig.forBlock(BlockNumber(0), cfg)

    assert(fields(evmConfig.feeSchedule) == fields(new FeeSchedule.AtlantisFeeSchedule))
    assert(evmConfig.opCodeList.opCodes.toSet == EvmConfig.AtlantisOpCodes.opCodes.toSet)
    assert(evmConfig.noEmptyAccounts) // EIP-161, via Atlantis on ETC
  }

  test("EvmConfig derives the ETC Olympia config at the olympia block", UnitTest, VMTest) {
    val olympiaBlock = BlockNumber(100L)
    val evmConfig = EvmConfig.forBlock(olympiaBlock, etcLadderTo(olympiaBlock))

    assert(fields(evmConfig.feeSchedule) == fields(new FeeSchedule.EtcOlympiaFeeSchedule))
    // spec-009: ETC Olympia uses EtcOlympiaOpCodes (no BLOBHASH/BLOBBASEFEE per ECIP-1121)
    assert(evmConfig.opCodeList.opCodes.toSet == EvmConfig.EtcOlympiaOpCodes.opCodes.toSet)
    assert(evmConfig.eip6780Enabled) // EIP-6780, Olympia-only
  }

  test("EvmConfig derives the Spiral config just before the olympia block", UnitTest, VMTest) {
    val olympiaBlock = BlockNumber(100L)
    val evmConfig = EvmConfig.forBlock(olympiaBlock - 1, etcLadderTo(olympiaBlock))

    // Spiral keeps the Mystique fee schedule (field-identical to EtcOlympia's), so the meaningful distinction from
    // Olympia is the opcode set (no BASEFEE/transient/MCOPY) and the eip6780 flag.
    assert(fields(evmConfig.feeSchedule) == fields(new FeeSchedule.MystiqueFeeSchedule))
    assert(evmConfig.opCodeList.opCodes.toSet == EvmConfig.SpiralOpCodes.opCodes.toSet)
    assert(!evmConfig.eip6780Enabled)
    assert(evmConfig.byteToOpCode.get(BASEFEE.code).isEmpty) // BASEFEE (EIP-3198) is Olympia-only on ETC
  }

  test("EtcForks.Olympia is highest enum value", UnitTest, VMTest) {
    assert(BlockchainConfigForEvm.EtcForks.Olympia > BlockchainConfigForEvm.EtcForks.Spiral)
  }
