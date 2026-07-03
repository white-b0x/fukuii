package com.chipprbots.ethereum.consensus.pow.difficulty

import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.utils.BlockchainConfig

object EthashDifficultyCalculator extends DifficultyCalculator:
  import DifficultyCalculator.*
  private val ExpDifficultyPeriod: Int = 100_000
  private val ByzantiumRelaxDifficulty: BigInt = 3_000_000
  private val ConstantinopleRelaxDifficulty: BigInt = 5_000_000
  private val MuirGlacierRelaxDifficulty: BigInt = 9_000_000

  def calculateDifficulty(blockNumber: BigInt, blockTimestamp: Timestamp, parentHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Difficulty =
    // This function's own `blockNumber` param is deliberately kept raw BigInt (fake-block-number
    // ice-age-delay arithmetic, out of IP-CL-I's/IP-CL-G's scope — same precedent as
    // calculateBombExponent below) — unwrap the now-BlockNumber-typed fork fields at this one
    // boundary rather than retyping the param.
    val forkBlockNumbers = blockchainConfig.forkBlockNumbers
    val homesteadBlockNumber = forkBlockNumbers.homesteadBlockNumber.value
    val byzantiumBlockNumber = forkBlockNumbers.byzantiumBlockNumber.value
    val atlantisBlockNumber = forkBlockNumbers.atlantisBlockNumber.value
    val difficultyBombRemovalBlockNumber = forkBlockNumbers.difficultyBombRemovalBlockNumber.value
    val muirGlacierBlockNumber = forkBlockNumbers.muirGlacierBlockNumber.value
    val constantinopleBlockNumber = forkBlockNumbers.constantinopleBlockNumber.value

    lazy val timestampDiff: Long = blockTimestamp - parentHeader.unixTimestamp

    val parentDiff: Difficulty = parentHeader.difficulty
    val x: BigInt = parentDiff.value / DifficultyBoundDivision
    val c: BigInt =
      if blockNumber < homesteadBlockNumber then if blockTimestamp < parentHeader.unixTimestamp + 13 then 1 else -1
      else if blockNumber >= byzantiumBlockNumber || blockNumber >= atlantisBlockNumber then
        val parentUncleFactor = if parentHeader.ommersHash.value == BlockHeader.EmptyOmmers then 1 else 2
        math.max(parentUncleFactor - (timestampDiff / 9), FrontierTimestampDiffLimit)
      else math.max(1 - (timestampDiff / 10), FrontierTimestampDiffLimit)

    val extraDifficulty: BigInt =
      if blockNumber < difficultyBombRemovalBlockNumber then
        // calculate a fake block number for the ice-age delay
        val fakeBlockNumber: BigInt =
          // https://eips.ethereum.org/EIPS/eip-2384
          if blockNumber >= muirGlacierBlockNumber then (blockNumber - MuirGlacierRelaxDifficulty).max(0)
          // https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1234.md
          else if blockNumber >= constantinopleBlockNumber then (blockNumber - ConstantinopleRelaxDifficulty).max(0)
          // https://github.com/ethereum/EIPs/blob/master/EIPS/eip-649.md
          else if blockNumber >= byzantiumBlockNumber then (blockNumber - ByzantiumRelaxDifficulty).max(0)
          else blockNumber

        val difficultyBombExponent = calculateBombExponent(fakeBlockNumber)
        if difficultyBombExponent >= 0 then BigInt(2).pow(difficultyBombExponent)
        else 0
      else 0

    val difficultyWithoutBomb: BigInt = MinimumDifficulty.value.max(parentDiff.value + x * c)
    Difficulty(difficultyWithoutBomb + extraDifficulty)

  private def calculateBombExponent(blockNumber: BigInt)(implicit
      blockchainConfig: BlockchainConfig
  ): Int =
    val difficultyBombPauseBlockNumber = blockchainConfig.forkBlockNumbers.difficultyBombPauseBlockNumber.value
    val difficultyBombContinueBlockNumber = blockchainConfig.forkBlockNumbers.difficultyBombContinueBlockNumber.value
    if blockNumber < difficultyBombPauseBlockNumber then (blockNumber / ExpDifficultyPeriod - 2).toInt
    else if blockNumber < difficultyBombContinueBlockNumber then
      ((difficultyBombPauseBlockNumber / ExpDifficultyPeriod) - 2).toInt
    else
      val delay = (difficultyBombContinueBlockNumber - difficultyBombPauseBlockNumber) / ExpDifficultyPeriod
      ((blockNumber / ExpDifficultyPeriod) - delay - 2).toInt
