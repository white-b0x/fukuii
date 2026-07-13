package com.chipprbots.ethereum.vm

import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainId

object Fixtures:

  val ConstantinopleBlockNumber = 200
  val PetersburgBlockNumber = 400
  val PhoenixBlockNumber = 600
  val IstanbulBlockNumber = 600
  val MagnetoBlockNumber = 700
  val BerlinBlockNumber = 700
  val MystiqueBlockNumber = 800
  val SpiralBlockNumber = 900
  val OlympiaBlockNumber = 1000

  val blockchainConfig: BlockchainConfigForEvm = BlockchainConfigForEvm(
    // block numbers are irrelevant
    frontierBlockNumber = BlockNumber(0),
    homesteadBlockNumber = BlockNumber(0),
    eip150BlockNumber = BlockNumber(0),
    eip160BlockNumber = BlockNumber(0),
    eip161BlockNumber = BlockNumber(0),
    byzantiumBlockNumber = BlockNumber(0),
    constantinopleBlockNumber = BlockNumber(ConstantinopleBlockNumber),
    istanbulBlockNumber = BlockNumber(IstanbulBlockNumber),
    maxCodeSize = Some(24576),
    accountStartNonce = 0,
    atlantisBlockNumber = BlockNumber(0),
    aghartaBlockNumber = BlockNumber(0),
    petersburgBlockNumber = BlockNumber(PetersburgBlockNumber),
    phoenixBlockNumber = BlockNumber(PhoenixBlockNumber),
    magnetoBlockNumber = BlockNumber(MagnetoBlockNumber),
    berlinBlockNumber = BlockNumber(BerlinBlockNumber),
    mystiqueBlockNumber = BlockNumber(MystiqueBlockNumber),
    spiralBlockNumber = BlockNumber(SpiralBlockNumber),
    eip1559BlockNumber = BlockNumber(OlympiaBlockNumber),
    chainId = ChainId(0x3d)
  )
