package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.ConsensusEngine
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.utils.BlockchainConfig

class BlockValidation(
    mining: Mining,
    blockchainReader: BlockchainReader,
    blockQueue: BlockQueue,
    consensusEngine: ConsensusEngine
):

  def validateBlockBeforeExecution(
      block: Block
  )(implicit blockchainConfig: BlockchainConfig): Either[ValidationBeforeExecError, BlockExecutionSuccess] =
    // Source the header (seal) validator through the resolved ConsensusEngine (Stage 5.4c-3), NOT from
    // `mining.validators.blockHeaderValidator`. `consensusEngine.headerValidator eq mining.validators.blockHeaderValidator`
    // for every conf (EthashEngine returns exactly that field; EngineApiEngine and the TTD-aware validators both resolve
    // the singleton TransitionBlockHeaderValidator), so this is a pure wiring redirect — byte-identical — that makes
    // `engineFor` load-bearing for pre-execution header validation.
    mining.validators.validateBlockBeforeExecution(
      block = block,
      getBlockHeaderByHash = getBlockHeaderFromChainOrQueue,
      getNBlocksBack = getNBlocksBackFromChainOrQueue,
      headerValidator = consensusEngine.headerValidator
    )

  private def getBlockHeaderFromChainOrQueue(hash: ByteString): Option[BlockHeader] =
    blockchainReader
      .getBlockHeaderByHash(BlockHash(hash))
      .orElse(blockQueue.getBlockByHash(BlockHash(hash)).map(_.header))

  private def getNBlocksBackFromChainOrQueue(hash: ByteString, n: Int): List[Block] =
    val queuedBlocks = blockQueue.getBranch(BlockHash(hash), dequeue = false).takeRight(n)
    if queuedBlocks.length == n then queuedBlocks
    else
      val chainedBlockHash = queuedBlocks.headOption.map(_.header.parentHash).getOrElse(BlockHash(hash))
      blockchainReader.getBlockByHash(chainedBlockHash) match
        case None =>
          // The in memory blocks aren't connected to the db ones, we don't have n blocks to return so we return none
          Nil

        case Some(highestBlockInStorage) =>
          // We already have |block +: queuedBlocks|
          val remaining = n - queuedBlocks.length - 1
          val remainingBlocks = Iterator
            .iterate(blockchainReader.getBlockByHash(highestBlockInStorage.header.parentHash))(
              _.filter(_.number != BlockNumber.Zero) // avoid trying to fetch parent of genesis
                .flatMap(p => blockchainReader.getBlockByHash(p.header.parentHash))
            )
            .take(remaining)
            .collect { case Some(block) => block }
            .toList
          (remainingBlocks :+ highestBlockInStorage) ::: queuedBlocks

  def validateBlockAfterExecution(
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: GasAmount
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] =
    mining.validators.validateBlockAfterExecution(
      block = block,
      stateRootHash = stateRootHash,
      receipts = receipts,
      gasUsed = gasUsed
    )
