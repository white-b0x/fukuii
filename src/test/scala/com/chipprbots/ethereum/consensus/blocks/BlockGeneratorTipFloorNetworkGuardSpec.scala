package com.chipprbots.ethereum.consensus.blocks

import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.blocks.Ommers
import com.chipprbots.ethereum.consensus.pow.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.Nonce
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.Wei
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NetworkType

/** EIP1559-DEALIAS-01 W1 regression guard for the ECIP-1122 MIN_MINER_TIP network guard on the block-production side
  * (BlockGeneratorSkeleton.prepareTransactions).
  *
  * `eip1559BlockNumber` is populated on every network (Olympia on ETC; London's block on ETH; 0 on Sepolia), so an
  * un-gated tip filter silently enforced the ETC-only 1-gwei floor on ETH/Sepolia. The floor must apply ONLY on
  * ETC-family networks. This spec pins that boundary: same sub-1-gwei-tip tx, same post-Olympia block number — dropped
  * on ETC, kept on ETH.
  */
// scalastyle:off magic.number
class BlockGeneratorTipFloorNetworkGuardSpec
    extends AnyWordSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider
    with SecureRandomBuilder:

  private val OneGwei: BigInt = BigInt(1_000_000_000)
  private val BigGasLimit: GasAmount = GasAmount(30_000_000)

  // Olympia active from block 0 so the block-number half of the guard is always satisfied; minTip pinned to 1 gwei so
  // the assertions do not depend on the test chain conf's default.
  private def configFor(networkType: NetworkType): BlockchainConfig =
    blockchainConfig
      .withUpdatedForkBlocks(_.copy(eip1559BlockNumber = BlockNumber(0)))
      .copy(networkType = networkType, minTip = OneGwei)

  private val etcConfig: BlockchainConfig = configFor(NetworkType.ETC)
  private val ethConfig: BlockchainConfig = configFor(NetworkType.ETH)

  private def signedLegacyTx(gasPrice: BigInt): SignedTransaction =
    val tx = LegacyTransaction(
      nonce = Nonce(0),
      gasPrice = GasPrice(gasPrice),
      gasLimit = GasAmount(21_000),
      receivingAddress = Some(Address(42)),
      value = Wei(BigInt(0)),
      payload = ByteString.empty
    )
    SignedTransaction.sign(tx, crypto.generateKeyPair(secureRandom), Some(ChainId(BigInt(0x3d))))

  // effectiveTip for a legacy tx with blockBaseFee=0 is just gasPrice (baseFee is ignored for legacy).
  private val subFloorTx: SignedTransaction = signedLegacyTx(gasPrice = 1) // 1 wei « 1 gwei
  private val atFloorTx: SignedTransaction = signedLegacyTx(gasPrice = OneGwei) // exactly 1 gwei

  private class TestableGen
      extends BlockGeneratorSkeleton(
        MiningConfig(
          protocol = Protocol.PoW,
          coinbase = Address(42),
          headerExtraData = ByteString.empty,
          blockCacheSize = 1,
          miningEnabled = false,
          gasLimitTarget = BigInt(30_000_000),
          notifyUrls = Seq.empty,
          staleThreshold = 7,
          recommitInterval = 0.seconds
        ),
        new DifficultyCalculator:
          def calculateDifficulty(blockNumber: BigInt, blockTimestamp: Timestamp, parent: BlockHeader)(implicit
              blockchainConfig: BlockchainConfig
          ): Difficulty = Difficulty(BigInt(1))
      ):
    type X = Ommers
    def emptyX: Ommers = Nil
    override protected def newBlockBody(transactions: Seq[SignedTransaction], x: Ommers): BlockBody =
      BlockBody(transactions, Nil)
    override protected def prepareHeader(
        blockNumber: BlockNumber,
        parent: Block,
        beneficiary: Address,
        blockTimestamp: Timestamp,
        x: Ommers
    )(implicit blockchainConfig: BlockchainConfig): BlockHeader =
      defaultPrepareHeader(blockNumber, parent, beneficiary, blockTimestamp, x)
    def generateBlock(
        parent: Block,
        transactions: Seq[SignedTransaction],
        beneficiary: Address,
        x: Ommers,
        initialWorldStateBeforeExecution: Option[com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy]
    )(implicit blockchainConfig: BlockchainConfig): PendingBlockAndState =
      throw new UnsupportedOperationException
    def withBlockTimestampProvider(btp: BlockTimestampProvider): TestBlockGenerator =
      throw new UnsupportedOperationException

    // Public accessor onto the protected, ECIP-1122-gated production-side tip filter.
    def selectTxs(txs: Seq[SignedTransaction])(implicit bc: BlockchainConfig): Seq[SignedTransaction] =
      prepareTransactions(txs, BigGasLimit, blockBaseFee = BigInt(0), blockNumber = BlockNumber(0))

  private val miner = new TestableGen

  "BlockGeneratorSkeleton.prepareTransactions ECIP-1122 tip floor (network guard)" when {

    "network is ETH (eip1559BlockNumber holds London's block)" should {
      "select a sub-1-gwei-tip tx — no ECIP-1122 floor applies" taggedAs (UnitTest, OlympiaTest) in {
        miner.selectTxs(Seq(subFloorTx))(ethConfig).map(_.hash) shouldBe Seq(subFloorTx.hash)
      }
    }

    "network is ETC and the block is at/after Olympia" should {
      "drop a sub-1-gwei-tip tx — the ECIP-1122 floor still applies" taggedAs (UnitTest, OlympiaTest) in {
        miner.selectTxs(Seq(subFloorTx))(etcConfig) shouldBe empty
      }
      "select a tx that meets the 1-gwei floor exactly" taggedAs (UnitTest, OlympiaTest) in {
        miner.selectTxs(Seq(atFloorTx))(etcConfig).map(_.hash) shouldBe Seq(atFloorTx.hash)
      }
    }
  }
