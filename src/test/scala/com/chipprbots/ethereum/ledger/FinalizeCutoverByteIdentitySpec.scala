package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.consensus.ConsensusEngine
import com.chipprbots.ethereum.consensus.EngineId
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Stage 5.4c-2 finalize-cutover proof: driving the REWIRED [[BlockExecution]] (whose finalize step now routes through
  * [[ConsensusEngine.finalizeBlock]]) produces a byte-identical `stateRootHash` to a [[BlockExecution]] whose finalize
  * calls the pre-5.4 path (`BlockPreparator.payBlockReward`) directly.
  *
  * The two executors differ ONLY in the injected engine:
  *   - `prodExec` wraps `ConsensusEngine.engineFor(mining, cfg)` — exactly what `NodeBuilder` now resolves.
  *   - `refExec` wraps a minimal engine whose `finalizeBlock` is verbatim `mining.blockPreparator.payBlockReward` — the
  *     pre-5.4 finalize.
  *
  * Everything else in the pipeline (transaction execution, EIP-4895 withdrawals, EIP-7685 system calls, state
  * persistence) is shared, unchanged code. So an identical `stateRootHash` proves the seam is a transparent
  * pass-through AND that the withdrawals step still runs, in order, AFTER finalize.
  */
class FinalizeCutoverByteIdentitySpec extends AnyWordSpec with Matchers:

  "The finalize cutover (BlockExecution routed through ConsensusEngine.finalizeBlock)" should {

    "credit the ETC PoW block reward byte-identically to a direct payBlockReward call, across ECIP-1017 eras" taggedAs (
      UnitTest,
      ConsensusTest,
      StateTest
    ) in new CutoverSetup:
      // Era 0 (< 5M): 5 ETC base reward. Era 1 (5M–10M): 4 ETC. Both flow through the same payBlockReward;
      // the seam must pass the block-number-dependent reward through unchanged.
      for childNumber <- Seq(BigInt(1_000_000), BigInt(6_000_000)) do
        withClue(s"[block $childNumber] ") {
          val block = powChild(childNumber)

          val prodRoot = prodExec.executeBlockNoValidation(block).map(_._3)
          val refRoot = refExec.executeBlockNoValidation(block).map(_._3)

          prodRoot.isRight shouldBe true
          prodRoot shouldEqual refRoot

          // Prove finalize is load-bearing: the miner was actually credited (reward > 0), so the seam is not a no-op.
          val prodWorld = worldAtRoot(prodRoot.toOption.get)
          prodWorld.getGuaranteedAccount(minerAddress).balance.toBigInt should be > initialMinerBalance.toBigInt
        }

    "keep PoS finalize a no-op and still apply the withdrawal after it, byte-identically" taggedAs (
      UnitTest,
      ConsensusTest,
      StateTest
    ) in new CutoverSetup:
      // Post-merge header (difficulty 0 + baseFee ⇒ isPoS): payBlockReward early-returns (no reward, base fee burned),
      // then BlockExecution applies the EIP-4895 withdrawal. The recipient must be credited exactly once, and the
      // engine-routed root must equal the direct-payBlockReward root.
      val recipient = Address(ByteString(Array.fill[Byte](20)(0x42.toByte)))
      val withdrawal =
        Withdrawal(index = BigInt(0), validatorIndex = BigInt(0), address = recipient, amount = BigInt(1))
      val block = posChildWithWithdrawal(Seq(withdrawal))

      val prodRoot = prodExec.executeBlockNoValidation(block).map(_._3)
      val refRoot = refExec.executeBlockNoValidation(block).map(_._3)

      prodRoot.isRight shouldBe true
      prodRoot shouldEqual refRoot

      // finalize → withdrawals order: miner unrewarded (isPoS), recipient credited exactly amount * 1 Gwei once.
      val prodWorld = worldAtRoot(prodRoot.toOption.get)
      prodWorld.getGuaranteedAccount(recipient).balance shouldEqual UInt256(BigInt("1000000000"))
      prodWorld.getAccount(minerAddress).map(_.balance.toBigInt).getOrElse(BigInt(0)) shouldEqual
        initialMinerBalance.toBigInt
  }

  trait CutoverSetup extends BlockchainSetup:
    private val cutoverBlockQueue: BlockQueue =
      BlockQueue(blockchainReader, SyncConfig(com.chipprbots.ethereum.utils.Config.config))
    private val cutoverValidation: BlockValidation =
      new BlockValidation(
        mining,
        blockchainReader,
        cutoverBlockQueue,
        ConsensusEngine.engineFor(mining, blockchainConfig)
      )

    /** Minimal engine whose finalize IS the pre-5.4 path — a direct `payBlockReward` call. */
    private val referenceEngine: ConsensusEngine = new ConsensusEngine:
      def id: EngineId = EngineId.Ethash
      def headerValidator: BlockHeaderValidator = mining.validators.blockHeaderValidator
      def sealer: Option[Mining] = Some(mining)
      def blockGenerator: BlockGenerator = mining.blockGenerator
      def finalizeBlock(block: Block, world: InMemoryWorldStateProxy)(implicit
          blockchainConfig: BlockchainConfig
      ): InMemoryWorldStateProxy =
        mining.blockPreparator.payBlockReward(block, world)

    val prodExec: BlockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      blockchainStorages.evmCodeStorage,
      mining.blockPreparator,
      ConsensusEngine.engineFor(mining, blockchainConfig),
      cutoverValidation
    )

    val refExec: BlockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      blockchainStorages.evmCodeStorage,
      mining.blockPreparator,
      referenceEngine,
      cutoverValidation
    )

    def worldAtRoot(root: ByteString): InMemoryWorldStateProxy =
      InMemoryWorldStateProxy(
        blockchainStorages.evmCodeStorage,
        blockchain.getBackingMptStorage(BlockNumber(-1)),
        (n: BlockNumber) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash),
        blockchainConfig.accountStartNonce,
        root,
        noEmptyAccounts = false,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )

    /** No-tx PoW child at `number`; beneficiary is the miner, parent is the persisted valid parent. */
    def powChild(number: BigInt): Block =
      Block(
        validBlockHeader.copy(number = BlockNumber(number), beneficiary = minerAddress.bytes),
        validBlockBodyWithNoTxs
      )

    /** No-tx post-merge child carrying withdrawals (difficulty 0 + baseFee ⇒ isPoS). */
    def posChildWithWithdrawal(withdrawals: Seq[Withdrawal]): Block =
      Block(
        validBlockHeader.copy(
          number = validBlockParentHeader.number + 1,
          beneficiary = minerAddress.bytes,
          difficulty = Difficulty.Zero,
          extraFields = HefPostShanghai(
            baseFee = BaseFeePerGas(BigInt("1000000000")),
            withdrawalsRoot = BlockHeader.EmptyMpt
          )
        ),
        BlockBody(transactionList = Nil, uncleNodesList = Nil, withdrawals = Some(withdrawals))
      )
