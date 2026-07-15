package com.chipprbots.fukuii.crypto

import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigInteger
import java.security.SecureRandom

import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.*
import org.bouncycastle.math.ec.ECPoint

/** Ethereum ECIES: `AES-128-CTR` + `HMAC-SHA-256` + `Concat-KDF(SHA-256)` over secp256k1 ECDH — the envelope scheme for
  * the RLPx encrypted handshake. The wire layout is `ephemeralPubKey(65) || IV(16) || ciphertext || MAC(32)`.
  */
object ECIESCoder:

  val KeySize = 128
  val PublicKeyOverheadSize = 65
  val MacOverheadSize = 32
  val OverheadSize: Int = PublicKeyOverheadSize + KeySize / 8 + MacOverheadSize

  @throws[IOException]
  @throws[InvalidCipherTextException]
  def decrypt(privKey: BigInteger, cipher: Array[Byte], macData: Option[Array[Byte]] = None): Array[Byte] =
    // CVE-2026-22862: reject truncated ciphertexts (< ephemeral key + IV + MAC + 1 byte) before parsing.
    if cipher.length < OverheadSize + 1 then
      throw new InvalidCipherTextException(s"Ciphertext too short: ${cipher.length} < ${OverheadSize + 1}")
    val is = new ByteArrayInputStream(cipher)
    val ephemBytes = new Array[Byte](2 * ((curve.getCurve.getFieldSize + 7) / 8) + 1)
    is.read(ephemBytes)
    val ephem = decodeAndValidatePoint(ephemBytes)
    val iv = new Array[Byte](KeySize / 8)
    is.read(iv)
    val cipherBody = new Array[Byte](is.available)
    is.read(cipherBody)
    decrypt(ephem, privKey, Some(iv), cipherBody, macData)

  @throws[InvalidCipherTextException]
  def decrypt(
      ephem: ECPoint,
      prv: BigInteger,
      iv: Option[Array[Byte]],
      cipher: Array[Byte],
      macData: Option[Array[Byte]]
  ): Array[Byte] =
    val iesEngine = makeIESEngine(ephem, prv, iv)
    iesEngine.processBlock(cipher, 0, cipher.length, forEncryption = false, macData)

  def encrypt(
      toPub: ECPoint,
      secureRandom: SecureRandom,
      plaintext: Array[Byte],
      macData: Option[Array[Byte]] = None
  ): Array[Byte] =
    val iv = secureRandomByteArray(secureRandom, KeySize / 8)
    val eGen = new ECKeyPairGenerator
    eGen.init(new ECKeyGenerationParameters(curve, secureRandom))
    val ephemPair = eGen.generateKeyPair
    val prv = ephemPair.getPrivate.asInstanceOf[ECPrivateKeyParameters].getD
    val pub = ephemPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ

    val iesEngine = makeIESEngine(toPub, prv, Some(iv))
    pub.getEncoded(false) ++ iv ++
      iesEngine.processBlock(plaintext, 0, plaintext.length, forEncryption = true, macData)

  private def makeIESEngine(pub: ECPoint, prv: BigInteger, iv: Option[Array[Byte]]): EthereumIESEngine =
    val aesEngine = AESEngine.newInstance()
    new EthereumIESEngine(
      kdf = Left(new ConcatKDFBytesGenerator(new SHA256Digest)),
      mac = new HMac(new SHA256Digest),
      hash = new SHA256Digest,
      cipher = Some(new BufferedBlockCipher(SICBlockCipher.newInstance(aesEngine))): @annotation.nowarn(
        "cat=deprecation"
      ),
      IV = iv,
      prvSrc = Left(new ECPrivateKeyParameters(prv, curve)),
      pubSrc = Left(new ECPublicKeyParameters(pub, curve))
    )
