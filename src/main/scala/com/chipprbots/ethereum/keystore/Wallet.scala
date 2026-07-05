package com.chipprbots.ethereum.keystore

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair

import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender

case class Wallet(address: Address, prvKey: ByteString):
  lazy val keyPair: AsymmetricCipherKeyPair = keyPairFromPrvKey(prvKey.toArray)

  def signTx(tx: LegacyTransaction, chainId: Option[ChainId]): SignedTransactionWithSender =
    SignedTransactionWithSender(SignedTransaction.sign(tx, keyPair, chainId), Address(keyPair))

  // Redact prvKey — the default case-class toString would leak the private key into logs.
  // Do NOT redact equals/hashCode: KeyStoreImplSpec relies on structural equality (including
  // prvKey) to assert the decrypt path recovered the correct key, not just the correct address.
  override def toString: String = s"Wallet($address, <redacted>)"
