package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import scala.annotation.unused

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.validators.*
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Implements validators that adhere to the original
  * [[com.chipprbots.ethereum.consensus.validators.Validators Validators]] interface.
  *
  * @see
  *   [[com.chipprbots.ethereum.consensus.StdValidatorsExecutor StdEthashValidators]] for the PoW-specific counterpart.
  */
final class StdValidators(
    val blockValidator: BlockValidator,
    val blockHeaderValidator: BlockHeaderValidator,
    val signedTransactionValidator: SignedTransactionValidator
) extends Validators:

  def validateBlockBeforeExecution(
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack,
      headerValidator: BlockHeaderValidator
  )(implicit blockchainConfig: BlockchainConfig): Either[ValidationBeforeExecError, BlockExecutionSuccess] =
    StdValidators.validateBlockBeforeExecution(
      self = this,
      block = block,
      getBlockHeaderByHash = getBlockHeaderByHash,
      getNBlocksBack = getNBlocksBack,
      headerValidator = headerValidator
    )

  def validateBlockAfterExecution(
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: GasAmount
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] =
    StdValidators.validateBlockAfterExecution(
      self = this,
      block = block,
      stateRootHash = stateRootHash,
      receipts = receipts,
      gasUsed = gasUsed
    )

object StdValidators:
  def validateBlockBeforeExecution(
      self: Validators,
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      @unused getNBlocksBack: GetNBlocksBack,
      headerValidator: BlockHeaderValidator
  )(implicit blockchainConfig: BlockchainConfig): Either[ValidationBeforeExecError, BlockExecutionSuccess] =

    val header = block.header
    val body = block.body

    // `headerValidator` is the engine-sourced seal validator (Stage 5.4c-3); `eq self.blockHeaderValidator` for every
    // conf, so this is byte-identical to reading `self.blockHeaderValidator` directly.
    val result = for
      _ <- headerValidator.validate(header, getBlockHeaderByHash)
      _ <- self.blockValidator.validateHeaderAndBody(header, body)
    yield BlockExecutionSuccess

    result.left.map(ValidationBeforeExecError.apply)

  def validateBlockAfterExecution(
      self: Validators,
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: GasAmount
  ): Either[BlockExecutionError, BlockExecutionSuccess] =

    val header = block.header
    val blockAndReceiptsValidation = self.blockValidator.validateBlockAndReceipts(header, receipts)

    if header.gasUsed != gasUsed then
      Left(ValidationAfterExecError(s"Block has invalid gas used, expected ${header.gasUsed} but got $gasUsed"))
    else if header.stateRoot.value != stateRootHash then
      Left(ValidationAfterExecError(s"Block has invalid state root hash, expected ${Hex
          .toHexString(header.stateRoot.toArray)} but got ${Hex.toHexString(stateRootHash.toArray)}"))
    else
      blockAndReceiptsValidation match
        case Left(err) => Left(ValidationAfterExecError(err.toString))
        case _         => Right(BlockExecutionSuccess)
