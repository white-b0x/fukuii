package com.chipprbots.fukuii.crypto

import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ECDSASignatureSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private val secureRandom = new SecureRandom()
  private val halfCurveOrder: BigInt = BigInt(curve.getN) >> 1

  "ECDSASignature" should "recover the public key for a known go-ethereum transaction (point sign 28)" in {
    val bytesToSign = Hex.decode("5a1465f4683bf2c18fc72c0789239c0f52b3ceac666ca9551cf265a11abe912c")
    val r = ByteString(Hex.decode("f3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401"))
    val s = ByteString(Hex.decode("53629a403579f5ce57bcbefba2616b1c6156d308ddcd37372c94943fdabeda97"))
    val sig = ECDSASignature(r, s, 28.toByte)
    sig.publicKey(bytesToSign).isDefined shouldBe true
  }

  it should "return None (not throw) for a bad recovery id" in {
    val bytesToSign = Hex.decode("2bb3925f178aa22c11435c61899e134fb7b1227016274b5f7b9d85c4469130ba")
    val r = ByteString(Hex.decode("fbe3df0cf030655d817a89936850d1cc00c07c35d3b21be73cfe9a730ea8b753"))
    val s = ByteString(Hex.decode("62d73b6a92ac23ff514315fad795bbac6d485481d356329d71467e93c87dfa42"))
    val sig = ECDSASignature(r, s, 0x1f.toByte) // 31 — outside {27,28}
    sig.publicKey(bytesToSign) shouldBe None
  }

  it should "return None (not throw) when the point is invalid (bad compression)" in {
    val sig = ECDSASignature(
      ByteString(Hex.decode("149a2046f51f5d043633664d76eef4f99cdba8e53851dcda57224dfe8770f98a")),
      ByteString(Hex.decode("a8898478e9aae9fadb71c7ab5451d47d2efa4199fc26ecc1da62ce8fb77e06f1")),
      28.toByte
    )
    val messageHash = Hex.decode("a1ede9cdf0b6fe37a384b265dce6b74a7464f11799dcee022f628450a19cf4eb")
    sig.publicKey(messageHash) shouldBe None
  }

  it should "return None (not an empty pubkey) when the recovered point is infinity (besu isInfinity guard)" in {
    // Take R = G (discrete log 1); then Q = r^{-1}(sR - eG) = r^{-1}(s - e)G is the point at infinity
    // exactly when e == s. With s = e = 2 and recId matching G's y-parity, recoverPubBytes reconstructs
    // R = G and must reject the ∞ recovery rather than return getEncoded(∞).tail == empty pubkey.
    val g = curve.getG.normalize()
    val r = BigInt(g.getAffineXCoord.toBigInteger)
    val recId: Byte = if BigInt(g.getAffineYCoord.toBigInteger).testBit(0) then 28.toByte else 27.toByte
    val s = BigInt(2)
    val messageHash = Array.fill[Byte](32)(0.toByte)
    messageHash(31) = 2.toByte // e = 2 = s
    ECDSASignature.recoverPubBytes(r, s, recId, messageHash) shouldBe None
  }

  it should "sign a message and recover the signing public key (round-trip)" in {
    forAll(arbitrary[Array[Byte]]) { message =>
      val keys = generateKeyPair(secureRandom)
      val pubParam = keys.getPublic match
        case p: ECPublicKeyParameters => p
        case other                    => sys.error(s"expected ECPublicKeyParameters, got ${other.getClass.getName}")
      val pubKey = pubParam.getQ
      val msg = kec256(message)

      val signature = ECDSASignature.sign(msg, keys)
      val recovered = signature
        .publicKey(msg)
        .map(a => ECDSASignature.UncompressedIndicator +: a)
        .map(curve.getCurve.decodePoint)
        .map(_.getEncoded(true))
        .map(ByteString(_))
      recovered shouldBe Some(ByteString(pubKey.getEncoded(true)))
    }
  }

  it should "always produce a canonical low-S signature (EIP-2)" in {
    forAll(arbitrary[Array[Byte]]) { message =>
      val keys = generateKeyPair(secureRandom)
      val sig = ECDSASignature.sign(kec256(message), keys)
      sig.s should be <= halfCurveOrder
    }
  }

  it should "be deterministic (RFC-6979): the same key and hash yield the same signature" in {
    val keys = generateKeyPair(secureRandom)
    val msg = kec256("deterministic-k".getBytes("US-ASCII"))
    val a = ECDSASignature.sign(msg, keys)
    val b = ECDSASignature.sign(msg, keys)
    a shouldBe b
  }

  it should "canonicalise a high-S value to N - s and leave a low-S value untouched" in {
    val n = BigInt(curve.getN)
    val lowS = halfCurveOrder - 1
    val highS = halfCurveOrder + 1
    assert(
      ECDSASignature.toCanonicalS(lowS) == lowS &&
        ECDSASignature.toCanonicalS(highS) == (n - highS),
      "canonicalisation must leave low-S untouched and reflect high-S to N - s"
    )
  }

  it should "round-trip through the 65-byte r||s||v encoding" in {
    val keys = generateKeyPair(secureRandom)
    val sig = ECDSASignature.sign(kec256("encode-me".getBytes("US-ASCII")), keys)
    val bytes = sig.toBytes
    assert(
      bytes.length == ECDSASignature.EncodedLength &&
        ECDSASignature.fromBytes(bytes) == Some(sig),
      "encoded length must match EncodedLength and round-trip decode to the original signature"
    )
  }
