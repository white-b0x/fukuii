package com.chipprbots.fukuii.crypto

import java.math.BigInteger
import java.security.SecureRandom

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Ethereum ECIES (AES-128-CTR + HMAC-SHA-256 + Concat-KDF) round-trips and known-answer decrypt.
  * The KAT is the ethereumj `test1` fixed cipher → known plaintext, proving byte-exact envelope
  * behaviour.
  */
class ECIESCoderSpec extends AnyFlatSpec with Matchers:

  private val secureRandom = new SecureRandom()

  "ECIESCoder" should "decrypt a fixed ethereumj cipher to its known plaintext (KAT)" in {
    val privKey = new BigInteger("5e173f6ac3c669587538e7727cf19b782a4f2fda07c1eaa662c593e5e85e3051", 16)
    val cipher = Hex.decode(
      "049934a7b2d7f9af8fd9db941d9da281ac9381b5740e1f64f7092f3588d4f87f5ce55191a6653e5e80c1c5dd538169aa123e70dc6ffc5af1827e546c0e958e42dad355bcc1fcb9cdf2cf47ff524d2ad98cbf275e661bf4cf00960e74b5956b799771334f426df007350b46049adb21a6e78ab1408d5e6ccde6fb5e69f0f4c92bb9c725c02f99fa72b9cdc8dd53cff089e0e73317f61cc5abf6152513cb7d833f09d2851603919bf0fbe44d79a09245c6e8338eb502083dc84b846f2fee1cc310d2cc8b1b9334728f97220bb799376233e113"
    )
    val payload = ECIESCoder.decrypt(privKey, cipher)
    Hex.toHexString(payload) shouldBe
      "802b052f8b066640bba94a4fc39d63815c377fced6fcb84d27f791c9921ddf3e9bf0108e298f490812847109cbd778fae393e80323fd643209841a3b7f110397f37ec61d84cea03dcc5e8385db93248584e8af4b4d1c832d8c7453c0089687a700"
  }

  it should "round-trip encrypt then decrypt" in {
    val privKey = new BigInteger("5e173f6ac3c669587538e7727cf19b782a4f2fda07c1eaa662c593e5e85e3051", 16)
    val payload = Hex.decode("1122334455")
    val pubKeyPoint = curve.getG.multiply(privKey)
    val cipher = ECIESCoder.encrypt(pubKeyPoint, secureRandom, payload)
    ECIESCoder.decrypt(privKey, cipher).sameElements(payload) shouldBe true
  }

  it should "reject a truncated ciphertext (CVE-2026-22862 guard)" in {
    val privKey = new BigInteger("5e173f6ac3c669587538e7727cf19b782a4f2fda07c1eaa662c593e5e85e3051", 16)
    an[Exception] should be thrownBy ECIESCoder.decrypt(privKey, Array.fill[Byte](10)(0))
  }
