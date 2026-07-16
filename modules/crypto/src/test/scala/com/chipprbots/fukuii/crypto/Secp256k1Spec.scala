package com.chipprbots.fukuii.crypto

import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address

/** secp256k1 key generation, public-key derivation and address derivation.
  *
  * The private/address KAT is go-ethereum's `crypto_test.go` test key (`testPrivHex`/`testAddrHex`): private
  * `289c2857…232032` → address `970e8128…8cf791`, proving [[pubKeyToAddress]] is byte-exact to
  * `crypto.PubkeyToAddress`.
  */
class Secp256k1Spec extends AnyFunSuite:

  private val secureRandom = new SecureRandom()

  test("pubKeyToAddress matches the go-ethereum known-answer key/address vector"):
    val prv = ByteString(Hex.decode("289c2857d4598e37fb9647507e47a309d6133539bf21a8b9cb6df88fd5232032"))
    val pub = pubKeyFromPrvKey(prv)
    val addr = pubKeyToAddress(pub)
    assert(addr.toHex == "970e8128ab834e8eac17ab8e3812f010678cf791")

  test("pubKeyToAddress equals the low 20 bytes of keccak256(pubKey)"):
    val prv = ByteString(Hex.decode("289c2857d4598e37fb9647507e47a309d6133539bf21a8b9cb6df88fd5232032"))
    val pub = pubKeyFromPrvKey(prv)
    val expected = Address(ByteString(kec256(pub.toArray).takeRight(20)))
    assert(pubKeyToAddress(pub) == expected)

  test("derived public key is 64 bytes (uncompressed, prefix dropped)"):
    val (_, pub) = keyPairToByteArrays(generateKeyPair(secureRandom))
    assert(pub.length == 64)

  test("pubKeyFromPrvKey agrees with the key pair's public key"):
    val keyPair = generateKeyPair(secureRandom)
    val (prv, pub) = keyPairToByteArrays(keyPair)
    assert(pubKeyFromPrvKey(prv).sameElements(pub))

  test("keyPairFromPrvKey round-trips a generated private key"):
    val keyPair = generateKeyPair(secureRandom)
    val (prv, pub) = keyPairToByteArrays(keyPair)
    val rebuilt = keyPairFromPrvKey(prv)
    assert(pubKeyFromKeyPair(rebuilt).sameElements(pub))

  test("decodeAndValidatePoint accepts the generator"):
    val g = curve.getG.getEncoded(false)
    assert(decodeAndValidatePoint(g).equals(curve.getG))

  test("decodeAndValidatePoint rejects the point at infinity"):
    intercept[IllegalArgumentException](decodeAndValidatePoint(Array[Byte](0x00))) // encoded infinity

  test("secureRandomByteArray returns the requested length"):
    assert(
      secureRandomByteArray(secureRandom, 32).length == 32 &&
        secureRandomByteString(secureRandom, 16).length == 16,
      "both secureRandomByteArray and secureRandomByteString must return the requested length"
    )
