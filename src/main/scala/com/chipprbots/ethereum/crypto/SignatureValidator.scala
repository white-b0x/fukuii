package com.chipprbots.ethereum.crypto

import org.apache.pekko.util.ByteString

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.jsonrpc.JsonMethodsImplicits
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Logger

// scalastyle:off regex
object SignatureValidator extends App with SecureRandomBuilder with JsonMethodsImplicits with Logger:

  args match
    case Array(pk, sig, msgHash) =>
      Try {
        val signature = ECDSASignature.fromBytes(ByteStringUtils.string2hash(sig))
        val msg = ByteStringUtils.string2hash(msgHash)

        signature.flatMap(_.publicKey(msg))
      } match
        case Failure(exception) =>
          log.error(
            "Can't recover public key from signature [{}] and msg [{}]: ERROR: {}",
            sig,
            msgHash,
            exception.getMessage,
            exception
          )
          sys.exit(1)
        case Success(recoveredPk) =>
          val publicKey = ByteStringUtils.string2hash(pk)
          recoveredPk match
            case Some(recoveredKey) =>
              if recoveredKey == publicKey then
                log.info(
                  "Recovered public key [{}] is the same as given one",
                  ByteStringUtils.hash2string(recoveredKey)
                )
              else
                log.error(
                  "Recovered public key [{}] is different than given [{}]",
                  ByteStringUtils.hash2string(recoveredKey),
                  ByteStringUtils.hash2string(publicKey)
                )
                sys.exit(1)
            case None =>
              log.error("Can't recover public key from signature [{}] and msg [{}]", sig, msgHash)
              sys.exit(1)
    case _ =>
      val keyPair = crypto.generateKeyPair(secureRandom)
      val pkStr = ByteStringUtils.hash2string(ByteString(crypto.pubKeyFromKeyPair(keyPair)))
      val hash = kec256(Array(1.toByte))
      val hashStr = ByteStringUtils.hash2string(ByteString(hash))
      val signature = ECDSASignature.sign(hash, keyPair)
      val sigStr = ByteStringUtils.hash2string(signature.toBytes)
      log.error(
        "Bad Input. Example usage: [signature-validator publicKey signature message_hash]. Example: [signature-validator {} {} {}]",
        pkStr,
        sigStr,
        hashStr
      )
      sys.exit(1)
