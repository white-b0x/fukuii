package com.chipprbots.fukuii.crypto

import java.io.ByteArrayInputStream

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.Mac
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.parsers.ECIESPublicKeyParser
import org.bouncycastle.util.Arrays
import org.bouncycastle.util.BigIntegers

/** Integrated Encryption Scheme (IES) over an EC key-agreement cipher, following IEEE Std 1363a with the two
  * Ethereum-specific changes: the MAC key is hashed before use, and the encryption IV is folded into the MAC. This is
  * the primitive behind the RLPx encrypted handshake.
  *
  * @param kdf
  *   key-derivation function (ECIES uses [[ConcatKDFBytesGenerator]]; [[MGF1BytesGeneratorExt]] selectable via the
  *   `Right`).
  * @param mac
  *   MAC generator for the ciphertext.
  * @param hash
  *   hash used to derive the MAC key when `hashMacKey` is set.
  * @param cipher
  *   the block cipher; `None` runs stream mode (KDF stream XOR-ed with the plaintext).
  * @param IV
  *   optional IV, folded into the MAC.
  * @param prvSrc
  *   private key, or a generator producing an ephemeral pair.
  * @param pubSrc
  *   peer public key, or a parser reading the ephemeral key from the input stream.
  * @param hashMacKey
  *   when true (Ethereum), hash the KDF-derived MAC key before use.
  */
class EthereumIESEngine(
    kdf: Either[ConcatKDFBytesGenerator, MGF1BytesGeneratorExt],
    mac: Mac,
    hash: Digest,
    cipher: Option[BufferedBlockCipher],
    IV: Option[Array[Byte]],
    prvSrc: Either[ECPrivateKeyParameters, ECKeyPairGenerator],
    pubSrc: Either[ECPublicKeyParameters, ECIESPublicKeyParser],
    hashMacKey: Boolean = true
):

  @throws[InvalidCipherTextException]
  private def encryptBlock(
      plainText: Array[Byte],
      inOff: Int,
      inLen: Int,
      macData: Option[Array[Byte]],
      encodedPublicKey: Array[Byte],
      fillKDFunction: Int => ByteString
  ): Array[Byte] =
    val (derivedKeySecondPart, cryptogram) = cipher match
      case Some(cphr) =>
        // Block-cipher mode: derive a full AES key + a MAC key.
        val derivedKey = fillKDFunction(ECIESCoder.KeySize / 8 + ECIESCoder.KeySize / 8)
        val (firstPart, secondPart) = derivedKey.splitAt(ECIESCoder.KeySize / 8)

        IV match
          case Some(iv) => cphr.init(true, new ParametersWithIV(new KeyParameter(firstPart.toArray), iv))
          case None     => cphr.init(true, new KeyParameter(firstPart.toArray))

        val encrypted = new Array[Byte](cphr.getOutputSize(inLen))
        val len = cphr.processBytes(plainText, inOff, inLen, encrypted, 0)
        cphr.doFinal(encrypted, len)
        (secondPart, ByteString(encrypted))

      case None =>
        // Stream mode: XOR the plaintext with the KDF stream.
        val derivedKey = fillKDFunction(inLen + ECIESCoder.KeySize / 8)
        val (firstPart, secondPart) = derivedKey.splitAt(inLen)
        val encrypted = firstPart.zipWithIndex.map { case (value, idx) =>
          (plainText(inOff + idx) ^ value).toByte
        }
        (secondPart, ByteString(encrypted*))

    mac.init(new KeyParameter(getKdfForMac(derivedKeySecondPart)))
    IV.foreach(iv => mac.update(iv, 0, iv.length))
    mac.update(cryptogram.toArray, 0, cryptogram.length)
    macData.foreach(data => mac.update(data, 0, data.length))
    val messageAuthenticationCode = new Array[Byte](mac.getMacSize)
    mac.doFinal(messageAuthenticationCode, 0)

    encodedPublicKey ++ cryptogram ++ messageAuthenticationCode

  @throws[InvalidCipherTextException]
  private def decryptBlock(
      cryptogram: Array[Byte],
      inOff: Int,
      inLen: Int,
      macData: Option[Array[Byte]],
      encodedPublicKey: Array[Byte],
      fillKDFunction: Int => ByteString
  ): Array[Byte] =
    // CVE-2026-22862: input must carry at least MAC + encoded key + 1 byte of ciphertext.
    if inLen <= mac.getMacSize + encodedPublicKey.length then
      throw new InvalidCipherTextException(
        s"Length of input must be greater than MAC (${mac.getMacSize}) + encoded key (${encodedPublicKey.length}), got $inLen"
      )

    val (derivedKeySecondPart, plainText) = cipher match
      case Some(cphr) =>
        val derivedKey = fillKDFunction(ECIESCoder.KeySize / 8 + ECIESCoder.KeySize / 8)
        val (firstPart, secondPart) = derivedKey.splitAt(ECIESCoder.KeySize / 8)

        IV match
          case Some(iv) => cphr.init(false, new ParametersWithIV(new KeyParameter(firstPart.toArray), iv))
          case None     => cphr.init(false, new KeyParameter(firstPart.toArray))

        val decrypted = new Array[Byte](cphr.getOutputSize(inLen - encodedPublicKey.length - mac.getMacSize))
        val len = cphr.processBytes(
          cryptogram,
          inOff + encodedPublicKey.length,
          inLen - encodedPublicKey.length - mac.getMacSize,
          decrypted,
          0
        )
        cphr.doFinal(decrypted, len)
        (secondPart, ByteString(decrypted))

      case None =>
        val derivedKey = fillKDFunction((inLen - encodedPublicKey.length - mac.getMacSize) + (ECIESCoder.KeySize / 8))
        val (firstPart, secondPart) = derivedKey.splitAt(inLen - encodedPublicKey.length - mac.getMacSize)
        val decrypted = firstPart.zipWithIndex.map { case (value, idx) =>
          (cryptogram(inOff + encodedPublicKey.length + idx) ^ value).toByte
        }
        (secondPart, ByteString(decrypted*))

    val end = inOff + inLen
    val messageAuthenticationCode = Arrays.copyOfRange(cryptogram, end - mac.getMacSize, end)
    val messageAuthenticationCodeCalculated = new Array[Byte](messageAuthenticationCode.length)

    mac.init(new KeyParameter(getKdfForMac(derivedKeySecondPart)))
    IV.foreach(iv => mac.update(iv, 0, iv.length))
    mac.update(
      cryptogram,
      inOff + encodedPublicKey.length,
      inLen - encodedPublicKey.length - messageAuthenticationCodeCalculated.length
    )
    macData.foreach(data => mac.update(data, 0, data.length))
    mac.doFinal(messageAuthenticationCodeCalculated, 0)

    // Constant-time MAC comparison — reject a tampered ciphertext without a timing oracle.
    if !constantTimeEquals(messageAuthenticationCode, messageAuthenticationCodeCalculated) then
      throw new InvalidCipherTextException("Invalid MAC.")

    plainText.toArray

  private def getKdfForMac(derivedKeySecondPart: ByteString): Array[Byte] =
    if hashMacKey then
      val hashBuff = new Array[Byte](hash.getDigestSize)
      hash.reset()
      hash.update(derivedKeySecondPart.toArray, 0, derivedKeySecondPart.length)
      hash.doFinal(hashBuff, 0)
      hashBuff
    else derivedKeySecondPart.toArray

  @throws[InvalidCipherTextException]
  def processBlock(
      in: Array[Byte],
      inOff: Int,
      inLen: Int,
      forEncryption: Boolean,
      macData: Option[Array[Byte]] = None
  ): Array[Byte] =
    val (prv, encodedEphKeyPair) = prvSrc.fold(
      key => (key, None),
      keyPairGenerator =>
        val ephKeyPair = keyPairGenerator.generateKeyPair()
        val prvParam = ephKeyPair.getPrivate.asInstanceOf[ECPrivateKeyParameters]
        val pubEncodedParam = ephKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false)
        (prvParam, Some(pubEncodedParam))
    )

    val (pub, encodedPublicKey) = pubSrc.fold(
      key => (key, None),
      keyParser =>
        val bIn = new ByteArrayInputStream(in, inOff, inLen)
        val result = keyParser.readKey(bIn).asInstanceOf[ECPublicKeyParameters]
        val encLength = inLen - bIn.available
        (result, Some(Arrays.copyOfRange(in, inOff, inOff + encLength)))
    )

    val agree = new ECDHBasicAgreement
    agree.init(prv)
    val sharedSecret = BigIntegers.asUnsignedByteArray(agree.getFieldSize, agree.calculateAgreement(pub))

    val fillKDFunction = (outLen: Int) =>
      kdf.fold(_.generateBytes(outLen, sharedSecret), _.generateBytes(outLen, sharedSecret))

    val encodedKey = encodedPublicKey.orElse(encodedEphKeyPair).getOrElse(new Array[Byte](0))

    if forEncryption then encryptBlock(in, inOff, inLen, macData, encodedKey, fillKDFunction)
    else decryptBlock(in, inOff, inLen, macData, encodedKey, fillKDFunction)
