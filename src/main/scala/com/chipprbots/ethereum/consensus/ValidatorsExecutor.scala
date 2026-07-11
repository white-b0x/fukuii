package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.MockedPowBlockHeaderValidator
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator
import com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidator
import com.chipprbots.ethereum.consensus.pow.validators.RestrictedEthashBlockHeaderValidator
import com.chipprbots.ethereum.consensus.pow.validators.StdOmmersValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.consensus.validators.std.StdValidators
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.utils.BlockchainConfig

trait ValidatorsExecutor extends Validators:
  def ommersValidator: OmmersValidator

  def validateBlockBeforeExecution(
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack,
      headerValidator: BlockHeaderValidator
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError.ValidationBeforeExecError, BlockExecutionSuccess] =
    ValidatorsExecutor.validateBlockBeforeExecution(
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
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError, BlockExecutionSuccess] =
    ValidatorsExecutor.validateBlockAfterExecution(
      self = this,
      block = block,
      stateRootHash = stateRootHash,
      receipts = receipts,
      gasUsed = gasUsed
    )

object ValidatorsExecutor:
  def apply(protocol: Protocol)(implicit blockchainConfig: BlockchainConfig): ValidatorsExecutor =
    val blockHeaderValidator: BlockHeaderValidator = protocol match
      case Protocol.MockedPow     => MockedPowBlockHeaderValidator
      case Protocol.RestrictedPoW => RestrictedEthashBlockHeaderValidator
      case Protocol.EngineApi     => TransitionBlockHeaderValidator
      case Protocol.PoW           =>
        // The `protocol` string cannot tell ETC from ETH: both are `protocol=pow`. The merge signal is
        // `terminalTotalDifficulty` (the Merge trigger, mirroring go-ethereum's `consensus/beacon` and Besu's
        // `getTerminalTotalDifficulty().isPresent()` transition-schedule guard). A chain that defines a TTD spans
        // PoW→PoS and needs per-header dispatch (Ethash for difficulty>0, PoS for difficulty==0); a chain without a
        // TTD (ETC/Mordor/gorgoroth) keeps unconditional Ethash sealing.
        if blockchainConfig.terminalTotalDifficulty.isDefined then TransitionBlockHeaderValidator
        else PoWBlockHeaderValidator

    new StdValidatorsExecutor(
      StdBlockValidator,
      blockHeaderValidator,
      StdSignedTransactionValidator,
      new StdOmmersValidator(blockHeaderValidator)
    )

  // Created only for testing purposes, shouldn't be used in production code.
  // Connected with: https://github.com/ethereum/tests/issues/480
  def apply(blockHeaderValidator: BlockHeaderValidator): ValidatorsExecutor =
    new StdValidatorsExecutor(
      StdBlockValidator,
      blockHeaderValidator,
      StdSignedTransactionValidator,
      new StdOmmersValidator(blockHeaderValidator)
    )

  def validateBlockBeforeExecution(
      self: ValidatorsExecutor,
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack,
      headerValidator: BlockHeaderValidator
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError.ValidationBeforeExecError, BlockExecutionSuccess] =

    val header = block.header
    val body = block.body

    // `headerValidator` is the engine-sourced seal validator (Stage 5.4c-3); `eq self.blockHeaderValidator` for every
    // conf, so the direct seal check is byte-identical. Ommers validation keeps using `self.ommersValidator`, which
    // wraps that same instance — nothing else moves.
    val result = for
      _ <- headerValidator.validate(header, getBlockHeaderByHash)
      _ <- self.blockValidator.validateHeaderAndBody(header, body)
      _ <- self.ommersValidator.validate(
        header.parentHash,
        header.number,
        body.uncleNodesList,
        getBlockHeaderByHash,
        getNBlocksBack
      )
    yield BlockExecutionSuccess

    result.left.map(ValidationBeforeExecError.apply)

  def validateBlockAfterExecution(
      self: ValidatorsExecutor,
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: GasAmount
  ): Either[BlockExecutionError, BlockExecutionSuccess] =
    StdValidators.validateBlockAfterExecution(
      self = self,
      block = block,
      stateRootHash = stateRootHash,
      receipts = receipts,
      gasUsed = gasUsed
    )
