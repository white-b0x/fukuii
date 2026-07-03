package com.chipprbots.ethereum.ledger

import com.chipprbots.ethereum.domain.BlockNumber

object BlockRewardCalculatorOps:

  implicit class BlockRewardCalculatorWithMinerReward(calculator: BlockRewardCalculator):
    def calculateMiningReward(blockNumber: BlockNumber, numberOfOmmers: Int): BigInt =
      val rewardForBlock = calculator.calculateMiningRewardForBlock(blockNumber)
      val rewardForOmmers = calculator.calculateMiningRewardForOmmers(blockNumber, numberOfOmmers)
      rewardForBlock.value + rewardForOmmers.value
