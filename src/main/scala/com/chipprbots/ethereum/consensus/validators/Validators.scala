package com.chipprbots.ethereum.consensus.validators

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.utils.BlockchainConfig

trait Validators:
  def blockValidator: BlockValidator
  def blockHeaderValidator: BlockHeaderValidator
  def signedTransactionValidator: SignedTransactionValidator

  // Note BlockImport uses this in importBlock
  //
  // `headerValidator` is supplied by the caller rather than read from `this.blockHeaderValidator`, so the header (seal)
  // validator is sourced through the resolved `ConsensusEngine` (Batch 5 Stage 5.4c-3). In production it is
  // `consensusEngine.headerValidator`, which is the SAME instance as `this.blockHeaderValidator` for every conf
  // (proven in EngineResolutionSpec) — a pure wiring redirect, byte-identical. Ommers validation keeps using the
  // engine-agnostic `ommersValidator` (which wraps that same instance), so nothing else moves.
  def validateBlockBeforeExecution(
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack,
      headerValidator: BlockHeaderValidator
  )(implicit blockchainConfig: BlockchainConfig): Either[ValidationBeforeExecError, BlockExecutionSuccess]

  /** This function validates that the various results from execution are consistent with the block. This includes:
    *   - Validating the resulting stateRootHash
    *   - Doing BlockValidator.validateBlockReceipts validations involving the receipts
    *   - Validating the resulting gas used
    *
    * @param block
    *   to validate
    * @param stateRootHash
    *   from the resulting state trie after executing the txs from the block
    * @param receipts
    *   associated with the execution of each of the tx from the block
    * @param gasUsed
    *   accumulated gas used for the execution of the txs from the block
    * @return
    *   None if valid else a message with what went wrong
    */
  def validateBlockAfterExecution(
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: GasAmount
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess]
