package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.Nonce
import com.chipprbots.ethereum.domain.Wei
import com.chipprbots.ethereum.utils.Config

case class TransactionRequest(
    from: Address,
    to: Option[Address] = None,
    value: Option[Wei] = None,
    gasLimit: Option[GasAmount] = None,
    gasPrice: Option[GasPrice] = None,
    nonce: Option[Nonce] = None,
    data: Option[ByteString] = None
):

  private val defaultGasPrice: GasPrice = GasPrice(2 * BigInt(10).pow(10))
  private val defaultGasLimit: GasAmount = GasAmount(90000)

  // Preferred overload: caller injects an oracle-derived price (e.g. from EthTxService.suggestGasPrice()).
  // The user-supplied gasPrice always wins; the oracle value is only the fallback.
  def toTransaction(defaultNonce: Nonce, suggestedGasPrice: GasPrice): LegacyTransaction =
    LegacyTransaction(
      nonce = nonce.getOrElse(defaultNonce),
      gasPrice = gasPrice.getOrElse(suggestedGasPrice),
      gasLimit = gasLimit.getOrElse(defaultGasLimit),
      receivingAddress = if Config.testmode then to.filter(_ != Address(0)) else to,
      value = value.getOrElse(Wei.Zero),
      payload = data.getOrElse(ByteString.empty)
    )

  // Bridge overload — retained for callers not yet wired to the gas oracle.
  def toTransaction(defaultNonce: Nonce): LegacyTransaction =
    toTransaction(defaultNonce, defaultGasPrice)
