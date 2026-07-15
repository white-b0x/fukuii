package com.chipprbots.fukuii.crypto

import java.math.BigInteger

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner

/** secp256r1 (P-256) ECDSA signature verification — the primitive behind the EIP-7951 `P256VERIFY` precompile (address
  * `0x0100`).
  *
  * Verification runs entirely on BouncyCastle (no JDK `java.security` EC provider), matching besu `SECP256R1` /
  * `AbstractSECP256`: an unpinned platform provider is a cross-JDK-vendor determinism risk (an attacker-controlled
  * off-curve input rejected by one provider but not another would split the state root). The curve order `N` and field
  * modulus `P` come from BouncyCastle's named-curve table (`secp256r1`), and the EIP-7951 range/point checks are
  * applied explicitly up front.
  *
  * Byte-behaviour is identical to go-ethereum `core/vm/contracts.go` `p256Verify` and besu
  * `P256VerifyPrecompiledContract`: reject `r`/`s` outside `(0, N)`, reject `qx`/`qy` outside `[0, P)`, reject the
  * point at infinity `(0, 0)`, reject a public key not on the curve, and reject a signature that does not verify.
  * Signature malleability is intentionally NOT rejected — high-`s` signatures verify (EIP-7951, matching besu
  * `verifySignature` with the default `ECDSASigner` and geth). This object is primitive-only — gas, input framing (the
  * 160-byte `hash || r
  * \|| s || qx || qy` layout) and the address live in the L3 precompile wrapper.
  */
object Secp256r1:

  // EIP-7951 explicit bounds: curve order N and field modulus P for secp256r1.
  // Mirrors besu P256VerifyPrecompiledContract (N = R1_PARAMS.getN(), P = field characteristic).
  private lazy val r1Params = SECNamedCurves.getByName("secp256r1")
  private lazy val curveN: BigInteger = r1Params.getN
  private lazy val curveP: BigInteger = r1Params.getCurve.getField.getCharacteristic
  private lazy val domain =
    new ECDomainParameters(r1Params.getCurve, r1Params.getG, r1Params.getN, r1Params.getH)

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
    val rInt = new BigInteger(1, r)
    val sInt = new BigInteger(1, s)
    val pubX = new BigInteger(1, x)
    val pubY = new BigInteger(1, y)

    // EIP-7951 explicit validation (spec §Validation checks 2, 3, 5); return false on any failure.
    // These make rejection deterministic across implementations, matching besu P256VerifyPrecompiledContract.
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
      // qx, qy already checked < P; isValid is the pure-BouncyCastle on-curve + not-infinity check
      // (== besu validatePublicPoint).
      val point = r1Params.getCurve.createPoint(pubX, pubY)
      if !point.isValid then false
      else
        // Default ECDSASigner ctor performs NO low-s enforcement, so high-s / malleable signatures
        // verify — matching EIP-7951, besu verifySignature and go-ethereum. r, s passed as BigInteger
        // directly (no DER round-trip).
        val signer = new ECDSASigner()
        signer.init(false, new ECPublicKeyParameters(point, domain))
        signer.verifySignature(hash, rInt, sInt)
