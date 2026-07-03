package com.chipprbots.ethereum.vm

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.testing.Tags.*

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

  test("EvmConfig.forBlock prefers Atlantis over Byzantium when activated at same height", UnitTest, VMTest) {
    val cfg = allMaxCfg.copy(byzantiumBlockNumber = BlockNumber(0), atlantisBlockNumber = BlockNumber(0))

    val evmConfig = EvmConfig.forBlock(BlockNumber(0), cfg)

    assert(evmConfig.feeSchedule.isInstanceOf[FeeSchedule.AtlantisFeeSchedule])
    assert(evmConfig.opCodeList == EvmConfig.AtlantisOpCodes)
  }

  test("EvmConfig selects OlympiaConfigBuilder at olympia block", UnitTest, VMTest) {
    val olympiaBlock = BlockNumber(100L)
    val cfg = allMaxCfg.copy(
      byzantiumBlockNumber = BlockNumber(0),
      atlantisBlockNumber = BlockNumber(0),
      aghartaBlockNumber = BlockNumber(0),
      constantinopleBlockNumber = BlockNumber(0),
      petersburgBlockNumber = BlockNumber(0),
      phoenixBlockNumber = BlockNumber(0),
      istanbulBlockNumber = BlockNumber(0),
      magnetoBlockNumber = BlockNumber(0),
      berlinBlockNumber = BlockNumber(0),
      mystiqueBlockNumber = BlockNumber(0),
      spiralBlockNumber = BlockNumber(0),
      olympiaBlockNumber = olympiaBlock
    )

    val evmConfig = EvmConfig.forBlock(olympiaBlock, cfg)

    assert(evmConfig.feeSchedule.isInstanceOf[FeeSchedule.OlympiaFeeSchedule])
    // spec-009: ETC Olympia uses EtcOlympiaOpCodes (no BLOBHASH/BLOBBASEFEE per ECIP-1121)
    assert(evmConfig.opCodeList == EvmConfig.EtcOlympiaOpCodes)
  }

  test("EvmConfig selects SpiralConfigBuilder just before olympia block", UnitTest, VMTest) {
    val olympiaBlock = BlockNumber(100L)
    val cfg = allMaxCfg.copy(
      byzantiumBlockNumber = BlockNumber(0),
      atlantisBlockNumber = BlockNumber(0),
      aghartaBlockNumber = BlockNumber(0),
      constantinopleBlockNumber = BlockNumber(0),
      petersburgBlockNumber = BlockNumber(0),
      phoenixBlockNumber = BlockNumber(0),
      istanbulBlockNumber = BlockNumber(0),
      magnetoBlockNumber = BlockNumber(0),
      berlinBlockNumber = BlockNumber(0),
      mystiqueBlockNumber = BlockNumber(0),
      spiralBlockNumber = BlockNumber(0),
      olympiaBlockNumber = olympiaBlock
    )

    val evmConfig = EvmConfig.forBlock(olympiaBlock - 1, cfg)

    assert(evmConfig.feeSchedule.isInstanceOf[FeeSchedule.MystiqueFeeSchedule])
    // Should NOT be OlympiaFeeSchedule
    assert(!evmConfig.feeSchedule.isInstanceOf[FeeSchedule.OlympiaFeeSchedule])
  }

  test("EtcForks.Olympia is highest enum value", UnitTest, VMTest) {
    assert(BlockchainConfigForEvm.EtcForks.Olympia > BlockchainConfigForEvm.EtcForks.Spiral)
  }
