package com.chipprbots.fukuii.crypto

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.Digest

/** MGF1 mask-generation function (from BouncyCastle) with an added `counterStart` to match Crypto++. An alternate KDF
  * for [[EthereumIESEngine]]; the Ethereum ECIES profile uses [[ConcatKDFBytesGenerator]] instead, but the engine keeps
  * both as a selectable option.
  */
class MGF1BytesGeneratorExt(digest: Digest):
  val digestSize: Int = digest.getDigestSize

  private def itoOSP(i: Int, sp: Array[Byte]): Unit =
    sp(0) = (i >>> 24).toByte
    sp(1) = (i >>> 16).toByte
    sp(2) = (i >>> 8).toByte
    sp(3) = i.toByte

  def generateBytes(outputLength: Int, seed: Array[Byte]): ByteString =
    val counterStart = 1
    val hashBuf = new Array[Byte](digestSize)
    val counterValue = new Array[Byte](Integer.BYTES)

    digest.reset()

    (0 until (outputLength / digestSize + 1))
      .map { i =>
        itoOSP(counterStart + i, counterValue)
        digest.update(seed, 0, seed.length)
        digest.update(counterValue, 0, counterValue.length)
        digest.doFinal(hashBuf, 0)

        val spaceLeft = outputLength - (i * digestSize)
        if spaceLeft > digestSize then ByteString(hashBuf)
        else ByteString(hashBuf).dropRight(digestSize - spaceLeft)
      }
      .foldLeft(ByteString.empty)(_ ++ _)
