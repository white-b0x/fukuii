package com.chipprbots.fukuii.crypto

import java.security.SecureRandom

import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.fukuii.bytes.ByteUtils
import com.chipprbots.fukuii.crypto.zksnark.BN128.BN128G1
import com.chipprbots.fukuii.crypto.zksnark.BN128.BN128G2
import com.chipprbots.fukuii.crypto.zksnark.Fp
import com.chipprbots.fukuii.crypto.zksnark.PairingCheck.G1G2Pair

/** The `CryptoBackend` seam must be byte-identical to calling the wrapped pure-BouncyCastle primitives directly — it is
  * a summonable interface, not an alternate implementation. This is the differential KAT `plan/L0.md` §7 requires
  * before a native backend is ever added: today it proves pure == pure (a wrapper is transparent); once a native
  * backend lands, the same shape proves native \== pure across sign/recover/keccak/pair.
  */
class CryptoBackendSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private val secureRandom = new SecureRandom()
  private val backend = CryptoBackend.pureBouncyCastle

  "CryptoBackend.pureBouncyCastle.keccak256" should "be byte-identical to kec256" in {
    forAll(arbitrary[Array[Byte]]) { input =>
      backend.keccak256(input) shouldBe kec256(input)
    }
  }

  "CryptoBackend.pureBouncyCastle.sign" should "be byte-identical to ECDSASignature.sign" in {
    forAll(arbitrary[Array[Byte]]) { message =>
      val keys = generateKeyPair(secureRandom)
      val messageHash = kec256(message)
      backend.sign(messageHash, keys) shouldBe ECDSASignature.sign(messageHash, keys)
    }
  }

  "CryptoBackend.pureBouncyCastle.recoverPublicKey" should "be byte-identical to ECDSASignature.recoverPubBytes" in {
    forAll(arbitrary[Array[Byte]]) { message =>
      val keys = generateKeyPair(secureRandom)
      val messageHash = kec256(message)
      val sig = ECDSASignature.sign(messageHash, keys)
      val recId = sig.v.toByte
      backend.recoverPublicKey(sig.r, sig.s, recId, messageHash).map(_.toSeq) shouldBe
        ECDSASignature.recoverPubBytes(sig.r, sig.s, recId, messageHash).map(_.toSeq)
    }
  }

  "CryptoBackend.pureBouncyCastle.pairingCheck" should "be byte-identical to PairingCheck.pairingCheck" in {
    val fpP = Fp.P
    def bs(n: BigInt) = org.apache.pekko.util.ByteString(ByteUtils.bigIntToBytes(n, 32))

    val g1 = BN128G1(bs(1), bs(2)).get
    val g1Neg = BN128G1(bs(1), bs(fpP - 2)).get
    val g2x0 = BigInt("10857046999023057135944570762232829481370756359578518086990519993285655852781")
    val g2x1 = BigInt("11559732032986387107991004021392285783925812861821192530917403151452391805634")
    val g2y0 = BigInt("8495653923123431417604973247489272438418190587263600148770280649306958101930")
    val g2y1 = BigInt("4082367875863433681332203403145435568316851327593401208105741076214120093531")
    val g2 = BN128G2(bs(g2x0), bs(g2x1), bs(g2y0), bs(g2y1)).get

    val pairs = Seq(G1G2Pair(g1, g2), G1G2Pair(g1Neg, g2))
    backend.pairingCheck(pairs) shouldBe com.chipprbots.fukuii.crypto.zksnark.PairingCheck.pairingCheck(pairs)
  }
