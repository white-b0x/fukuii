package com.chipprbots.fukuii.crypto

import org.apache.pekko.util.ByteString

import scala.util.Try

import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/** AES symmetric cipher used by the keystore (AES-128-CBC) and the RLPx handshake (AES-128-CTR). */
trait SymmetricCipher:
  def encrypt(secret: ByteString, iv: ByteString, message: ByteString): ByteString =
    process(forEncryption = true, secret, iv, message)

  /** Decrypt; `None` on any cipher error (bad padding, wrong length). */
  def decrypt(secret: ByteString, iv: ByteString, encrypted: ByteString): Option[ByteString] =
    Try(process(forEncryption = false, secret, iv, encrypted)).toOption

  protected def getCipher: BufferedBlockCipher

  protected def process(
      forEncryption: Boolean,
      secret: ByteString,
      iv: ByteString,
      data: ByteString
  ): ByteString =
    val cipher = getCipher
    cipher.reset()
    cipher.init(forEncryption, new ParametersWithIV(new KeyParameter(secret.toArray), iv.toArray))
    val output = new Array[Byte](cipher.getOutputSize(data.size))
    val offset = cipher.processBytes(data.toArray, 0, data.size, output, 0)
    val len = cipher.doFinal(output, offset)
    ByteString(output).take(offset + len)

/** AES-128-CBC with PKCS7 padding (keystore). */
object AES_CBC extends SymmetricCipher:
  protected def getCipher: BufferedBlockCipher =
    new PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(AESEngine.newInstance()), new PKCS7Padding)

/** AES-128-CTR (RLPx frame cipher). */
object AES_CTR extends SymmetricCipher:
  protected def getCipher: BufferedBlockCipher =
    (new BufferedBlockCipher(SICBlockCipher.newInstance(AESEngine.newInstance())): @annotation.nowarn(
      "cat=deprecation"
    ))
