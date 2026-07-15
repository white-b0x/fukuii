package com.chipprbots.fukuii.crypto

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.Digest
import org.bouncycastle.util.Pack

/** NIST SP 800-56A concatenation KDF: derives a key stream by hashing `counter || seed` with an incrementing big-endian
  * counter. Used by [[EthereumIESEngine]] to expand the ECDH shared secret into the AES key and the MAC key.
  *
  * @param digest
  *   the hash driving derivation (SHA-256 for Ethereum ECIES).
  */
class ConcatKDFBytesGenerator(digest: Digest):
  val digestSize: Int = digest.getDigestSize

  /** Produce `outputLength` bytes of key material from `seed`. */
  def generateBytes(outputLength: Int, seed: Array[Byte]): ByteString =
    require(
      outputLength <= (digestSize.toLong * 8) * ((2L << 32) - 1),
      "Output length too large"
    )

    val counterStart: Long = 1
    val hashBuf = new Array[Byte](digestSize)
    val counterValue = new Array[Byte](Integer.BYTES)

    digest.reset()

    (0 until (outputLength / digestSize + 1))
      .map { i =>
        Pack.intToBigEndian(((counterStart + i) % (2L << 32)).toInt, counterValue, 0)
        digest.update(counterValue, 0, counterValue.length)
        digest.update(seed, 0, seed.length)
        digest.doFinal(hashBuf, 0)

        val spaceLeft = outputLength - (i * digestSize)
        if spaceLeft > digestSize then ByteString(hashBuf)
        else ByteString(hashBuf).dropRight(digestSize - spaceLeft)
      }
      .foldLeft(ByteString.empty)(_ ++ _)
