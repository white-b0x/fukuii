package com.chipprbots.fukuii.crypto

import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite

/** secp256r1 (P-256) ECDSA verification — the primitive behind the EIP-7951 `P256VERIFY` precompile.
  *
  * The valid/tampered known-answer vectors are the Wycheproof ECDSA-P256-SHA256 vectors used by go-ethereum
  * (`core/vm/testdata/precompiles/p256Verify.json`) and besu (`P256VerifyPrecompiledContractTest`); here they drive the
  * primitive [[Secp256r1.verify]] directly (five 32-byte fields), rather than the 160-byte precompile framing which is
  * tested at L3. The remaining cases exercise the EIP-7951 explicit range/point checks: `r`/`s` must be in `(0, N)`,
  * `qx`/`qy` in `[0, P)`, the point at infinity `(0, 0)` is rejected, and an off-curve public key is rejected.
  */
class Secp256r1Spec extends AnyFunSuite:

  private def h(s: String): Array[Byte] = Hex.decode(s)

  // Valid Wycheproof SHA-256 #1 vector.
  private val hash = h("bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023")
  private val r = h("2ba3a8be6b94d5ec80a6d9d1190a436effe50d85a1eee859b8cc6af9bd5c2e18")
  private val s = h("4cd60b855d442f5b3c7b11eb6c4e0ae7525fe710fab9aa7c77a67f79e6fadd76")
  private val qx = h("2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838")
  private val qy = h("c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e")

  private val zero32 = h("0000000000000000000000000000000000000000000000000000000000000000")
  // secp256r1 curve order N and field modulus P (out-of-range boundary values).
  private val curveN = h("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
  private val curveP = h("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")

  test("verify accepts a valid secp256r1 signature (Wycheproof SHA-256 #1)"):
    assert(Secp256r1.verify(hash, r, s, qx, qy))

  test("verify rejects a tampered r/s signature (Wycheproof SHA-256 #3)"):
    val rMod = h("d45c5740946b2a147f59262ee6f5bc90bd01ed280528b62b3aed5fc93f06f739")
    val sMod = h("b329f479a2bbd0a5c384ee1493b1f5186a87139cac5df4087c134b49156847db")
    assert(!Secp256r1.verify(hash, rMod, sMod, qx, qy))

  test("verify rejects all-zero r and s (r, s must be > 0)"):
    assert(!Secp256r1.verify(hash, zero32, zero32, qx, qy))

  test("verify rejects r == N and s == N (r, s must be < curve order)"):
    assert(!Secp256r1.verify(hash, curveN, s, qx, qy) && !Secp256r1.verify(hash, r, curveN, qx, qy))

  test("verify rejects a public-key coordinate == P (qx, qy must be < field modulus)"):
    assert(!Secp256r1.verify(hash, r, s, curveP, qy) && !Secp256r1.verify(hash, r, s, qx, curveP))

  test("verify rejects the point at infinity (qx == 0 && qy == 0)"):
    assert(!Secp256r1.verify(hash, r, s, zero32, zero32))

  test("verify rejects a public key not on the P-256 curve (qx = 0, qy = 1)"):
    val notOnCurveY = h("0000000000000000000000000000000000000000000000000000000000000001")
    assert(!Secp256r1.verify(hash, r, s, zero32, notOnCurveY))
