package com.chipprbots.fukuii.crypto

import org.apache.pekko.util.ByteString

import scala.util.Try

import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECCurve
import org.bouncycastle.math.ec.ECPoint

import com.chipprbots.fukuii.bytes.ByteUtils

object ECDSASignature:

  val RLength = 32
  val SLength = 32
  val VLength = 1
  val EncodedLength: Int = RLength + SLength + VLength

  /** Leading byte of an uncompressed encoded EC point (BouncyCastle encoding). */
  val UncompressedIndicator: Byte = 0x04
  val CompressedEvenIndicator: Byte = 0x02
  val CompressedOddIndicator: Byte = 0x03

  // Pre-EIP-155 recovery-value convention (the ECDSA "point sign").
  val negativePointSign: Byte = 27
  val positivePointSign: Byte = 28
  // EIP-155 / typed-transaction yParity convention.
  val negativeYParity: Byte = 0
  val positiveYParity: Byte = 1

  val allowedPointSigns: Set[Byte] = Set(negativePointSign, positivePointSign)

  /** Half the curve order — the low-S / high-S boundary (EIP-2). */
  private val halfCurveOrder: BigInt = BigInt(curveParams.getN) >> 1

  def apply(r: ByteString, s: ByteString, v: Byte): ECDSASignature =
    // v must be read as an UNSIGNED byte. For EIP-155 chains v can be >= 128 (e.g. v=157 on ETC
    // mainnet, chainId=61); a signed Byte would turn that into a negative BigInt.
    ECDSASignature(BigInt(1, r.toArray), BigInt(1, s.toArray), BigInt(v & 0xff))

  /** Parse a 65-byte `r || s || v` signature; `None` on any other length. */
  def fromBytes(bytes65: ByteString): Option[ECDSASignature] =
    if bytes65.length == EncodedLength then
      Some(apply(bytes65.take(RLength), bytes65.drop(RLength).take(SLength), bytes65(64)))
    else None

  /** Sign a 32-byte message hash (expected to be a Keccak-256 digest) with a raw private key. */
  def sign(messageHash: ByteString, prvKey: ByteString): ECDSASignature =
    sign(messageHash.toArray, keyPairFromPrvKey(prvKey.toArray))

  /** Sign a 32-byte message hash with a key pair.
    *
    * Deterministic-`k` (RFC-6979) via `HMacDSAKCalculator(SHA256Digest)` — identical `k`, and thus identical `(r, s)`,
    * to go-ethereum's decred/libsecp256k1 signer for the same key and hash. `s` is canonicalised to the low half
    * (EIP-2, [[toCanonicalS]]) to reject signature malleability, and the recovery id `v` is computed as the point sign
    * (27/28) that recovers this key.
    */
  def sign(messageHash: Array[Byte], keyPair: AsymmetricCipherKeyPair): ECDSASignature =
    require(
      messageHash.length == 32,
      s"The message should be a 32-byte hash; got ${messageHash.length} bytes."
    )
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest))
    signer.init(true, keyPair.getPrivate)
    val components = signer.generateSignature(messageHash)
    val r = BigInt(components(0))
    val s = toCanonicalS(BigInt(components(1)))
    val v = BigInt(
      calculateV(r, s, keyPair, messageHash)
        .getOrElse(throw new RuntimeException("Failed to calculate signature recovery id"))
    )
    ECDSASignature(r, s, v)

  /** Canonicalise `s` to the low half of the curve order (EIP-2 malleability rejection). For every valid signature `(r,
    * s)`, `(r, N - s)` is also valid; consensus accepts only the one with `s <= N/2`. Mirrors go-ethereum's
    * `s.IsOverHalfOrder()` reject in `crypto/signature_nocgo.go` and the `ValidateSignatureValues` low-S check in
    * `crypto/crypto.go:246`.
    */
  def toCanonicalS(s: BigInt): BigInt =
    if s > halfCurveOrder then BigInt(curve.getN) - s else s

  private def checkPointSignValidity(pointSign: Byte): Option[Byte] =
    Option(pointSign).filter(allowedPointSigns.contains)

  private def calculateV(
      r: BigInt,
      s: BigInt,
      key: AsymmetricCipherKeyPair,
      messageHash: Array[Byte]
  ): Option[Byte] =
    val pubKeyParam = key.getPublic match
      case p: ECPublicKeyParameters => p
      case other                    => sys.error(s"expected ECPublicKeyParameters, got ${other.getClass.getName}")
    val pubKey = pubKeyParam.getQ.getEncoded(false).tail
    Seq(positivePointSign, negativePointSign).find { i =>
      recoverPubBytes(r, s, i, messageHash).exists(java.util.Arrays.equals(_, pubKey))
    }

  /** Recover the 64-byte (prefix-dropped) public key from a signature and message hash.
    *
    * `recId` is the ECDSA point sign (27 or 28). Returns `None` when the recovery id is out of range, when `r` is not a
    * valid x-coordinate, or when the reconstructed point fails the order check — never throws. This is also the
    * verification path: a signature verifies iff it recovers the expected public key. EIP-155 chain-id encoding of `v`
    * is unwound by the caller (the `domain` transaction layer) back to a 27/28 point sign before this is called.
    */
  def recoverPubBytes(r: BigInt, s: BigInt, recId: Byte, messageHash: Array[Byte]): Option[Array[Byte]] =
    Try {
      val order = curve.getCurve.getOrder
      // x = r (the case x = r + order is negligibly improbable and ignored, matching libsecp256k1).
      val xCoordinate = r
      val curveFp = curve.getCurve match
        case fp: ECCurve.Fp => fp
        case other          => sys.error(s"expected ECCurve.Fp, got ${other.getClass.getName}")
      val prime = curveFp.getQ

      checkPointSignValidity(recId).flatMap { recovery =>
        if xCoordinate.compareTo(prime) < 0 then
          val bigR = constructPoint(xCoordinate, recovery)
          if bigR.multiply(order).isInfinity then
            val e = BigInt(1, messageHash)
            val rInv = r.modInverse(order)
            // Q = r^(-1) (sR - eG)
            val q = bigR
              .multiply(s.bigInteger)
              .subtract(curve.getG.multiply(e.bigInteger))
              .multiply(rInv.bigInteger)
            // Reject a recovery to the point at infinity: `q.getEncoded(false)` of infinity is a single
            // `0x00` byte, so `.tail` would yield an empty (all-zero) pubkey that must not be accepted.
            // Matches besu `AbstractSECP256.java:353` (`if (q.isInfinity()) return null;`).
            if q.isInfinity then None
            else Some(q.getEncoded(false).tail)
          else None
        else None
      }
    }.toOption.flatten

  private def constructPoint(xCoordinate: BigInt, recId: Int): ECPoint =
    val x9 = new X9IntegerConverter
    val compEnc = x9.integerToBytes(xCoordinate.bigInteger, 1 + x9.getByteLength(curve.getCurve))
    compEnc(0) = if recId == positivePointSign then 3.toByte else 2.toByte
    curve.getCurve.decodePoint(compEnc)

/** An ECDSA signature over secp256k1, as the tuple `(r, s, v)`.
  *
  * @param r
  *   x-coordinate of the ephemeral public key, mod curve order `N`.
  * @param s
  *   the signature proper; canonicalised low-S for signatures this client produces.
  * @param v
  *   recovery value — either a bare point sign (27/28), a typed-tx yParity (0/1), or an EIP-155 protected value
  *   `chainId*2 + 35 + {0,1}`. The `domain` layer unwinds the EIP-155 form to a 27/28 point sign before recovery.
  */
case class ECDSASignature(r: BigInt, s: BigInt, v: BigInt):

  /** Recover the signer's 64-byte public key (also the verification path). `v` must be a bare point sign (27/28) here —
    * EIP-155 unwinding happens one layer up.
    */
  def publicKey(messageHash: Array[Byte]): Option[Array[Byte]] =
    ECDSASignature.recoverPubBytes(r, s, v.toByte, messageHash)

  /** Recover the signer's 64-byte public key from a `ByteString` hash. */
  def publicKey(messageHash: ByteString): Option[ByteString] =
    ECDSASignature.recoverPubBytes(r, s, v.toByte, messageHash.toArray).map(ByteString(_))

  /** The 65-byte `r || s || v` encoding: each of `r`/`s` as fixed-width 32-byte big-endian, then the low byte of `v`.
    */
  def toBytes: ByteString =
    ByteString(ByteUtils.bigIntToBytes(r, ECDSASignature.RLength)) ++
      ByteString(ByteUtils.bigIntToBytes(s, ECDSASignature.SLength)) ++
      ByteString(v.toByte)
