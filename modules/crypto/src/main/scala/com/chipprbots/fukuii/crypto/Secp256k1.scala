package com.chipprbots.fukuii.crypto

import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.*
import org.bouncycastle.math.ec.ECPoint

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.ByteUtils

/** secp256k1 domain parameters and key-material helpers.
  *
  * The single curve every Ethereum signature and public key lives on. Values come from
  * BouncyCastle's named-curve table (`secp256k1`), matching go-ethereum `crypto.S256()` — same
  * field prime, generator, order `N` and cofactor `H`.
  */

/** The raw X9 curve parameters for `secp256k1`. */
val curveParams: X9ECParameters = SECNamedCurves.getByName("secp256k1")

/** The secp256k1 domain (curve, generator G, order N, cofactor H). */
val curve: ECDomainParameters =
  new ECDomainParameters(curveParams.getCurve, curveParams.getG, curveParams.getN, curveParams.getH)

/** Generate a fresh secp256k1 key pair from the given source of randomness. */
def generateKeyPair(secureRandom: SecureRandom): AsymmetricCipherKeyPair =
  val generator = new ECKeyPairGenerator
  generator.init(new ECKeyGenerationParameters(curve, secureRandom))
  generator.generateKeyPair()

/** Reconstruct a key pair from a raw private-key scalar. */
def keyPairFromPrvKey(prvKey: BigInt): AsymmetricCipherKeyPair =
  val publicKey = curve.getG.multiply(prvKey.bigInteger).normalize()
  new AsymmetricCipherKeyPair(
    new ECPublicKeyParameters(publicKey, curve),
    new ECPrivateKeyParameters(prvKey.bigInteger, curve)
  )

/** Reconstruct a key pair from raw private-key bytes (unsigned big-endian). */
def keyPairFromPrvKey(prvKeyBytes: Array[Byte]): AsymmetricCipherKeyPair =
  keyPairFromPrvKey(BigInt(1, prvKeyBytes))

/** Reconstruct a key pair from raw private-key bytes (unsigned big-endian). */
def keyPairFromPrvKey(prvKeyBytes: ByteString): AsymmetricCipherKeyPair =
  keyPairFromPrvKey(BigInt(1, prvKeyBytes.toArray))

/** @return
  *   `(privateKey, publicKey)` as raw bytes. The private key is fixed-width 32 bytes; the public
  *   key is uncompressed with its `0x04` prefix byte dropped (the 64-byte `X || Y` form Ethereum
  *   uses everywhere).
  */
def keyPairToByteArrays(keyPair: AsymmetricCipherKeyPair): (Array[Byte], Array[Byte]) =
  val prvKey = ByteUtils.bigIntToBytes(BigInt(keyPair.getPrivate.asInstanceOf[ECPrivateKeyParameters].getD), 32)
  val pubKey = keyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail
  (prvKey, pubKey)

/** As [[keyPairToByteArrays]] but returning `ByteString`s. */
def keyPairToByteStrings(keyPair: AsymmetricCipherKeyPair): (ByteString, ByteString) =
  val (prv, pub) = keyPairToByteArrays(keyPair)
  (ByteString(prv), ByteString(pub))

/** The uncompressed, prefix-dropped 64-byte public key of a key pair. */
def pubKeyFromKeyPair(keyPair: AsymmetricCipherKeyPair): Array[Byte] =
  keyPairToByteArrays(keyPair)._2

/** Derive the 64-byte public key from raw private-key bytes. */
def pubKeyFromPrvKey(prvKey: Array[Byte]): Array[Byte] =
  keyPairToByteArrays(keyPairFromPrvKey(prvKey))._2

/** Derive the 64-byte public key from raw private-key bytes. */
def pubKeyFromPrvKey(prvKey: ByteString): ByteString =
  ByteString(pubKeyFromPrvKey(prvKey.toArray))

/** Derive the 20-byte account address from a 64-byte (prefix-dropped) public key.
  *
  * Byte-exact to go-ethereum `crypto.PubkeyToAddress` (`crypto/crypto.go:253`):
  * `Keccak256(pubBytes[1:])[12:]` — the low 20 bytes of the Keccak-256 of the uncompressed key with
  * its `0x04` prefix stripped. fukuii already carries public keys prefix-dropped, so this hashes the
  * 64 bytes directly and takes the rightmost 20.
  */
def pubKeyToAddress(pubKey: Array[Byte]): Address =
  Address(ByteString(kec256(pubKey).takeRight(Address.Length)))

/** Derive the 20-byte account address from a 64-byte (prefix-dropped) public key. */
def pubKeyToAddress(pubKey: ByteString): Address =
  pubKeyToAddress(pubKey.toArray)

/** Decode an EC point from its encoded form and validate it thoroughly.
  *
  * Defense-in-depth for the invalid-curve / small-subgroup point family (CVE-2025-24883,
  * CVE-2026-26314, CVE-2026-26315). `decodePoint` already checks the curve equation; `isValid()`
  * additionally rejects small-subgroup points (wrong order) and the point at infinity.
  */
def decodeAndValidatePoint(encoded: Array[Byte]): ECPoint =
  val point = curve.getCurve.decodePoint(encoded)
  if !point.isValid || point.isInfinity then
    throw new IllegalArgumentException("Invalid EC point: not on curve or point at infinity")
  point

/** `length` random bytes from the given source. */
def secureRandomByteArray(secureRandom: SecureRandom, length: Int): Array[Byte] =
  val bytes = new Array[Byte](length)
  secureRandom.nextBytes(bytes)
  bytes

/** `length` random bytes from the given source, as a `ByteString`. */
def secureRandomByteString(secureRandom: SecureRandom, length: Int): ByteString =
  ByteString(secureRandomByteArray(secureRandom, length))
