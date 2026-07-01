package com.chipprbots.ethereum.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

import scala.util.Try

import org.bouncycastle.asn1.sec.SECNamedCurves

/** EIP-7951: P-256 (secp256r1) signature verification using JDK's java.security API.
  */
object Secp256r1:

  private lazy val ecParams: ECParameterSpec =
    val params = AlgorithmParameters.getInstance("EC")
    params.init(new ECGenParameterSpec("secp256r1"))
    params.getParameterSpec(classOf[ECParameterSpec])

  // EIP-7951 explicit bounds: curve order n and field modulus p for secp256r1.
  // Mirrors Besu P256VerifyPrecompiledContract (N = R1_PARAMS.getN(), P = field characteristic).
  private lazy val r1Params = SECNamedCurves.getByName("secp256r1")
  private lazy val curveN: BigInteger = r1Params.getN
  private lazy val curveP: BigInteger = r1Params.getCurve.getField.getCharacteristic

  /** Verify a P-256 ECDSA signature.
    *
    * @param hash
    *   32-byte message hash
    * @param r
    *   32-byte r component of signature
    * @param s
    *   32-byte s component of signature
    * @param x
    *   32-byte x coordinate of public key
    * @param y
    *   32-byte y coordinate of public key
    * @return
    *   true if the signature is valid
    */
  def verify(hash: Array[Byte], r: Array[Byte], s: Array[Byte], x: Array[Byte], y: Array[Byte]): Boolean =
    Try {
      val rInt = new BigInteger(1, r)
      val sInt = new BigInteger(1, s)
      val pubX = new BigInteger(1, x)
      val pubY = new BigInteger(1, y)

      // EIP-7951 explicit validation (spec §Validation checks 2, 3, 5); return false on any failure.
      // These make rejection deterministic across JDK providers rather than relying on
      // provider-specific behavior of KeyFactory/Signature (see EIP-7951 Rationale, RIP-7212 fix).
      // Signature component bounds: 0 < r < n and 0 < s < n.
      val rsInRange =
        rInt.signum > 0 && rInt.compareTo(curveN) < 0 && sInt.signum > 0 && sInt.compareTo(curveN) < 0
      // Public-key coordinate bounds: 0 <= qx < p and 0 <= qy < p.
      val qInRange =
        pubX.signum >= 0 && pubX.compareTo(curveP) < 0 && pubY.signum >= 0 && pubY.compareTo(curveP) < 0
      // Point at infinity is encoded as (0, 0) and is not a valid public key.
      val notInfinity = !(pubX.signum == 0 && pubY.signum == 0)

      if !(rsInRange && qInRange && notInfinity) then false
      else
        val ecPoint = new ECPoint(pubX, pubY)
        val pubKeySpec = new ECPublicKeySpec(ecPoint, ecParams)
        val keyFactory = KeyFactory.getInstance("EC")
        val publicKey = keyFactory.generatePublic(pubKeySpec)

        // Convert r, s to DER-encoded signature for JCA
        val derSig = toDerSignature(rInt, sInt)

        val sig = Signature.getInstance("NONEwithECDSA")
        sig.initVerify(publicKey)
        sig.update(hash)
        sig.verify(derSig)
    }.getOrElse(false)

  /** Encode r, s as DER-encoded ECDSA signature */
  private def toDerSignature(r: BigInteger, s: BigInteger): Array[Byte] =
    val rBytes = toUnsignedByteArray(r)
    val sBytes = toUnsignedByteArray(s)

    // DER encoding: 0x30 [total-length] 0x02 [r-length] [r] 0x02 [s-length] [s]
    val totalLength = 2 + rBytes.length + 2 + sBytes.length
    val der = new Array[Byte](2 + totalLength)
    var offset = 0
    der(offset) = 0x30; offset += 1
    der(offset) = totalLength.toByte; offset += 1
    der(offset) = 0x02; offset += 1
    der(offset) = rBytes.length.toByte; offset += 1
    System.arraycopy(rBytes, 0, der, offset, rBytes.length); offset += rBytes.length
    der(offset) = 0x02; offset += 1
    der(offset) = sBytes.length.toByte; offset += 1
    System.arraycopy(sBytes, 0, der, offset, sBytes.length)
    der

  /** Convert BigInteger to minimal unsigned byte array (with leading 0 if high bit set) */
  private def toUnsignedByteArray(value: BigInteger): Array[Byte] =
    val bytes = value.toByteArray
    if bytes.length > 1 && bytes(0) == 0 && (bytes(1) & 0x80) == 0 then
      // Strip unnecessary leading zero
      bytes.drop(1)
    else bytes
