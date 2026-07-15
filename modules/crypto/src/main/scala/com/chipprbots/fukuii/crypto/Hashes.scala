package com.chipprbots.fukuii.crypto

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA256Digest

/** SHA-256 and RIPEMD-160 — the two non-Keccak digests exposed as EVM precompiles (address `0x02` SHA-256, address
  * `0x03` RIPEMD-160) and used in the RLPx handshake / keystore. Output matches the standard FIPS-180 / RIPEMD-160
  * vectors byte-for-byte.
  *
  * Unlike [[kec256]] these are not on the state-root hot path, so no thread-local reuse — a fresh digest per call keeps
  * them trivially thread-safe.
  */

/** SHA-256 over a whole array. */
def sha256(input: Array[Byte]): Array[Byte] =
  val digest = new SHA256Digest()
  val out = new Array[Byte](digest.getDigestSize)
  digest.update(input, 0, input.length)
  digest.doFinal(out, 0)
  out

/** SHA-256 over a `ByteString`. */
def sha256(input: ByteString): ByteString =
  ByteString(sha256(input.toArray))

/** RIPEMD-160 over a whole array. */
def ripemd160(input: Array[Byte]): Array[Byte] =
  val digest = new RIPEMD160Digest()
  digest.update(input, 0, input.length)
  val out = new Array[Byte](digest.getDigestSize)
  digest.doFinal(out, 0)
  out

/** RIPEMD-160 over a `ByteString`. */
def ripemd160(input: ByteString): ByteString =
  ByteString(ripemd160(input.toArray))
