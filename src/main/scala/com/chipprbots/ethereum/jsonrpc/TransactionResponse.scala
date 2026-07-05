package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

trait BaseTransactionResponse:
  def hash: ByteString
  def nonce: Nonce
  def blockHash: Option[ByteString]
  def blockNumber: Option[BlockNumber]
  def transactionIndex: Option[BigInt]
  def from: Option[ByteString]
  def to: Option[ByteString]
  def value: Wei
  def gasPrice: GasPrice
  def gas: GasAmount
  def input: ByteString

final case class TransactionResponse(
    hash: ByteString,
    nonce: Nonce,
    blockHash: Option[ByteString],
    blockNumber: Option[BlockNumber],
    transactionIndex: Option[BigInt],
    from: Option[ByteString],
    to: Option[ByteString],
    value: Wei,
    gasPrice: GasPrice,
    gas: GasAmount,
    input: ByteString,
    `type`: Option[BigInt],
    chainId: Option[BigInt],
    maxFeePerGas: Option[BigInt],
    maxPriorityFeePerGas: Option[BigInt],
    accessList: Option[Seq[Map[String, Any]]],
    maxFeePerBlobGas: Option[BigInt],
    blobVersionedHashes: Option[Seq[ByteString]],
    authorizationList: Option[Seq[Map[String, Any]]],
    yParity: Option[BigInt],
    v: Option[BigInt],
    r: Option[BigInt],
    s: Option[BigInt],
    blockTimestamp: Option[BigInt]
) extends BaseTransactionResponse

final case class TransactionData(
    stx: SignedTransaction,
    blockHeader: Option[BlockHeader] = None,
    transactionIndex: Option[Int] = None
)

object TransactionResponse:

  // NOTE: this given is NOT dead code despite having no textual reference in this file — it is
  // picked up implicitly by `SignedTransaction.getSender(stx)` below, which takes a `using
  // BlockchainConfig` parameter invisible to a plain-text/grep-based "unused given" check.
  // (A prior pass of this batch incorrectly deleted this as confirmed-dead; restored after
  // `sbt compile-all` surfaced the missing-given error.)
  given blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  /** Per-transaction-type fields extracted from `stx.tx` in [[apply]] below. Named fields instead of a positional tuple
    * — a transposed pair (e.g. `maxFeePerGas`/`maxPriorityFeePerGas`) previously would have compiled silently.
    */
  private case class TxTypeFields(
      txType: BigInt,
      chainId: Option[BigInt],
      maxFeePerGas: Option[BigInt],
      maxPriorityFeePerGas: Option[BigInt],
      accessList: Option[Seq[Map[String, Any]]],
      maxFeePerBlobGas: Option[BigInt],
      blobVersionedHashes: Option[Seq[ByteString]],
      authorizationList: Option[Seq[Map[String, Any]]]
  )

  def apply(tx: TransactionData): TransactionResponse =
    TransactionResponse(tx.stx, tx.blockHeader, tx.transactionIndex)

  def apply(
      stx: SignedTransaction,
      blockHeader: Option[BlockHeader] = None,
      transactionIndex: Option[Int] = None
  ): TransactionResponse =
    val txFields =
      stx.tx match
        case _: LegacyTransaction =>
          // EIP-155: extract chainId from v value for replay-protected legacy txs
          val legacyChainId = if stx.signature.v > 35 then Some((stx.signature.v - 35) / 2) else None
          TxTypeFields(BigInt(0), legacyChainId, None, None, None, None, None, None)
        case tx: TransactionWithAccessList =>
          TxTypeFields(
            BigInt(1),
            Some(tx.chainId.value),
            None,
            None,
            Some(encodeAccessList(tx.accessList)),
            None,
            None,
            None
          )
        case tx: TransactionWithDynamicFee =>
          TxTypeFields(
            BigInt(2),
            Some(tx.chainId.value),
            Some(tx.maxFeePerGas.value),
            Some(tx.maxPriorityFeePerGas.value),
            Some(encodeAccessList(tx.accessList)),
            None,
            None,
            None
          )
        case tx: BlobTransaction =>
          TxTypeFields(
            BigInt(3),
            Some(tx.chainId.value),
            Some(tx.maxFeePerGas.value),
            Some(tx.maxPriorityFeePerGas.value),
            Some(encodeAccessList(tx.accessList)),
            Some(tx.maxFeePerBlobGas),
            Some(tx.blobVersionedHashes.map(_.value)),
            None
          )
        case tx: SetCodeTransaction =>
          TxTypeFields(
            BigInt(4),
            Some(tx.chainId.value),
            Some(tx.maxFeePerGas.value),
            Some(tx.maxPriorityFeePerGas.value),
            Some(encodeAccessList(tx.accessList)),
            None,
            None,
            Some(encodeAuthorizationList(tx.authorizationList))
          )

    val effectiveGasPrice = Transaction.effectiveGasPrice(stx.tx, blockHeader.flatMap(_.baseFee))

    TransactionResponse(
      hash = stx.hash.value,
      nonce = stx.tx.nonce,
      blockHash = blockHeader.map(_.hash.value),
      blockNumber = blockHeader.map(_.number),
      transactionIndex = transactionIndex.map(txIndex => BigInt(txIndex)),
      from = SignedTransaction.getSender(stx).map(_.bytes),
      to = stx.tx.receivingAddress.map(_.bytes),
      value = stx.tx.value,
      gasPrice = GasPrice(effectiveGasPrice),
      gas = stx.tx.gasLimit,
      input = stx.tx.payload,
      `type` = Some(txFields.txType),
      chainId = txFields.chainId,
      maxFeePerGas = txFields.maxFeePerGas,
      maxPriorityFeePerGas = txFields.maxPriorityFeePerGas,
      accessList = txFields.accessList,
      maxFeePerBlobGas = txFields.maxFeePerBlobGas,
      blobVersionedHashes = txFields.blobVersionedHashes,
      authorizationList = txFields.authorizationList,
      // yParity only for typed transactions (type >= 1), not legacy
      yParity = if txFields.txType > 0 then Some(stx.signature.v) else None,
      v = Some(stx.signature.v),
      r = Some(stx.signature.r),
      s = Some(stx.signature.s),
      blockTimestamp = blockHeader.map(h => BigInt(h.unixTimestamp.toLong))
    )

  private def encodeAccessList(accessList: List[AccessListItem]): Seq[Map[String, Any]] =
    accessList.map { item =>
      Map(
        "address" -> item.address,
        "storageKeys" -> item.storageKeys
      )
    }

  private def encodeAuthorizationList(authList: List[SetCodeAuthorization]): Seq[Map[String, Any]] =
    authList.map { auth =>
      Map(
        "chainId" -> auth.chainId.value,
        "address" -> auth.address,
        "nonce" -> auth.nonce.value,
        "yParity" -> auth.v,
        "r" -> auth.r,
        "s" -> auth.s
      )
    }
