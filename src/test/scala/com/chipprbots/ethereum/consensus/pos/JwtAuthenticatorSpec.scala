package com.chipprbots.ethereum.consensus.pos

import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Authorization
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Security coverage for [[JwtAuthenticator]] — the Engine API auth-bypass surface (execution-apis authentication.md).
  * Asserts HS256 verification, the `iat` ±60s window, rejection of tampered signatures, and rejection of `alg:
  * none`-shaped tokens. Exercised through the public `authenticate` directive so the real request path is covered.
  */
class JwtAuthenticatorSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest:

  // A fixed 32-byte (64 hex char) secret. HS256 requires the shared secret to match byte-for-byte.
  private val secretHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  private val secretBytes = secretHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private val authenticator = new JwtAuthenticator(secretHex)

  private val route: Route = authenticator.authenticate { _ =>
    complete("ok")
  }

  private def b64url(bytes: Array[Byte]): String =
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def b64url(s: String): String = b64url(s.getBytes("UTF-8"))

  private def hmac(signingInput: String, key: Array[Byte]): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    b64url(mac.doFinal(signingInput.getBytes("UTF-8")))

  /** Build a signed HS256 token for the given iat, signed with `key` (defaults to the valid secret). */
  private def token(iat: Long, key: Array[Byte] = secretBytes): String =
    val header = b64url("""{"alg":"HS256","typ":"JWT"}""")
    val payload = b64url(s"""{"iat":$iat}""")
    val signingInput = s"$header.$payload"
    s"$signingInput.${hmac(signingInput, key)}"

  private def request(bearer: String) =
    Get("/").withHeaders(Authorization(OAuth2BearerToken(bearer)))

  private def now: Long = Instant.now().getEpochSecond

  "JwtAuthenticator" should "accept a valid HS256 token with a fresh iat" in {
    request(token(now)) ~> route ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldBe "ok"
    }
  }

  it should "accept an iat within +/-60s of now (boundary)" in {
    request(token(now - 59)) ~> route ~> check(status shouldBe StatusCodes.OK)
    request(token(now + 59)) ~> route ~> check(status shouldBe StatusCodes.OK)
  }

  it should "reject an iat older than 60s" in {
    request(token(now - 120)) ~> route ~> check(status shouldBe StatusCodes.Unauthorized)
  }

  it should "reject an iat more than 60s in the future" in {
    request(token(now + 120)) ~> route ~> check(status shouldBe StatusCodes.Unauthorized)
  }

  it should "reject a token signed with the wrong secret" in {
    val wrongKey = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
      .grouped(2)
      .map(Integer.parseInt(_, 16).toByte)
      .toArray
    request(token(now, wrongKey)) ~> route ~> check(status shouldBe StatusCodes.Unauthorized)
  }

  it should "reject a token whose signature has been tampered (same length)" in {
    val valid = token(now)
    val parts = valid.split('.')
    // Flip the first char of the signature segment while preserving its length; constant-time
    // compare must still reject it.
    val sig = parts(2)
    val flipped = (if sig.head == 'A' then 'B' else 'A').toString + sig.tail
    request(s"${parts(0)}.${parts(1)}.$flipped") ~> route ~> check(status shouldBe StatusCodes.Unauthorized)
  }

  it should "reject a token missing the iat claim" in {
    val header = b64url("""{"alg":"HS256","typ":"JWT"}""")
    val payload = b64url("""{"sub":"cl"}""")
    val signingInput = s"$header.$payload"
    val tok = s"$signingInput.${hmac(signingInput, secretBytes)}"
    request(tok) ~> route ~> check(status shouldBe StatusCodes.Unauthorized)
  }

  it should "reject an alg:none token (empty signature)" in {
    val header = b64url("""{"alg":"none","typ":"JWT"}""")
    val payload = b64url(s"""{"iat":$now}""")
    // alg:none carries no signature — three segments with an empty third, which is not a valid HMAC.
    request(s"$header.$payload.") ~> route ~> check(status shouldBe StatusCodes.Unauthorized)
  }

  it should "reject a token that is not three segments" in {
    request("only.two") ~> route ~> check(status shouldBe StatusCodes.Unauthorized)
  }

  it should "reject a request with no Authorization header" in {
    Get("/") ~> route ~> check(status shouldBe StatusCodes.Unauthorized)
  }
