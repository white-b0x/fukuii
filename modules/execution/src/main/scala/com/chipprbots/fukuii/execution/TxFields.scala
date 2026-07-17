package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.domain.AccessListEntry
import com.chipprbots.fukuii.domain.SetCodeAuthorization
import com.chipprbots.fukuii.domain.Transaction

/** Uniform read accessors over the `enum Transaction` variants — the per-variant fields the tx engine needs, projected
  * to one shape so the processor and [[IntrinsicGas]] pattern-match once, here, rather than at every use-site.
  *
  * `to == None` ⇒ contract creation (only Legacy/AccessList/DynamicFee can be creations; Blob/SetCode always carry a
  * `to`). The fee accessors expose the two axes go-ethereum's `TransactionToMessage` reconciles: the *cap* used for the
  * upfront balance check ([[feeCap]] — `gasPrice` for legacy/2930, `maxFeePerGas` for 1559/4844/7702) and the
  * *effective* per-gas price ([[effectiveGasPrice]] — `min(maxFeePerGas, maxPriorityFeePerGas + baseFee)` for the
  * dynamic-fee families; go-ethereum `core/types/transaction.go` `effectiveGasPrice`).
  */
private[execution] object TxFields:

  def nonce(tx: Transaction): BigInt = tx match
    case t: Transaction.Legacy     => t.nonce.toBigInt
    case t: Transaction.AccessList => t.nonce.toBigInt
    case t: Transaction.DynamicFee => t.nonce.toBigInt
    case t: Transaction.Blob       => t.nonce.toBigInt
    case t: Transaction.SetCode    => t.nonce.toBigInt

  def gasLimit(tx: Transaction): BigInt = tx match
    case t: Transaction.Legacy     => t.gasLimit.toBigInt
    case t: Transaction.AccessList => t.gasLimit.toBigInt
    case t: Transaction.DynamicFee => t.gasLimit.toBigInt
    case t: Transaction.Blob       => t.gasLimit.toBigInt
    case t: Transaction.SetCode    => t.gasLimit.toBigInt

  def value(tx: Transaction): BigInt = tx match
    case t: Transaction.Legacy     => t.value.toUInt256.toBigInt
    case t: Transaction.AccessList => t.value.toUInt256.toBigInt
    case t: Transaction.DynamicFee => t.value.toUInt256.toBigInt
    case t: Transaction.Blob       => t.value.toUInt256.toBigInt
    case t: Transaction.SetCode    => t.value.toUInt256.toBigInt

  /** The recipient — `None` is a contract-creation transaction (Blob/SetCode always have a recipient). */
  def to(tx: Transaction): Option[Address] = tx match
    case t: Transaction.Legacy     => t.to
    case t: Transaction.AccessList => t.to
    case t: Transaction.DynamicFee => t.to
    case t: Transaction.Blob       => Some(t.to)
    case t: Transaction.SetCode    => Some(t.to)

  def isContractCreation(tx: Transaction): Boolean = to(tx).isEmpty

  def payload(tx: Transaction): ByteString = tx match
    case t: Transaction.Legacy     => t.payload
    case t: Transaction.AccessList => t.payload
    case t: Transaction.DynamicFee => t.payload
    case t: Transaction.Blob       => t.payload
    case t: Transaction.SetCode    => t.payload

  def accessList(tx: Transaction): List[AccessListEntry] = tx match
    case _: Transaction.Legacy     => Nil
    case t: Transaction.AccessList => t.accessList
    case t: Transaction.DynamicFee => t.accessList
    case t: Transaction.Blob       => t.accessList
    case t: Transaction.SetCode    => t.accessList

  def authorizationList(tx: Transaction): List[SetCodeAuthorization] = tx match
    case t: Transaction.SetCode => t.authorizationList
    case _                      => Nil

  /** The fee *cap* for the upfront balance check — `gasPrice` for legacy/2930, `maxFeePerGas` for the 1559/4844/7702
    * families (go-ethereum `buyGas` uses `GasFeeCap` for the balance check).
    */
  def feeCap(tx: Transaction): BigInt = tx match
    case t: Transaction.Legacy     => t.gasPrice.toUInt256.toBigInt
    case t: Transaction.AccessList => t.gasPrice.toUInt256.toBigInt
    case t: Transaction.DynamicFee => t.maxFeePerGas.toUInt256.toBigInt
    case t: Transaction.Blob       => t.maxFeePerGas.toUInt256.toBigInt
    case t: Transaction.SetCode    => t.maxFeePerGas.toUInt256.toBigInt

  /** The **effective** per-gas price actually charged — `gasPrice` for the legacy families, and `min(maxFeePerGas,
    * maxPriorityFeePerGas + baseFee)` for the dynamic-fee families (go-ethereum `transaction.go` `effectiveGasPrice`;
    * this is `msg.GasPrice` in `state_transition.go`, the value both the upfront gas debit and the sender refund use).
    */
  def effectiveGasPrice(tx: Transaction, baseFee: BigInt): BigInt = tx match
    case t: Transaction.Legacy     => t.gasPrice.toUInt256.toBigInt
    case t: Transaction.AccessList => t.gasPrice.toUInt256.toBigInt
    case t: Transaction.DynamicFee =>
      dynamicEffective(t.maxFeePerGas.toUInt256.toBigInt, t.maxPriorityFeePerGas.toUInt256.toBigInt, baseFee)
    case t: Transaction.Blob =>
      dynamicEffective(t.maxFeePerGas.toUInt256.toBigInt, t.maxPriorityFeePerGas.toUInt256.toBigInt, baseFee)
    case t: Transaction.SetCode =>
      dynamicEffective(t.maxFeePerGas.toUInt256.toBigInt, t.maxPriorityFeePerGas.toUInt256.toBigInt, baseFee)

  private def dynamicEffective(maxFee: BigInt, maxPriority: BigInt, baseFee: BigInt): BigInt =
    (maxPriority + baseFee).min(maxFee)
