package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.implicits.global

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.jsonrpc.Web3Service.ClientVersionRequest
import com.chipprbots.ethereum.jsonrpc.Web3Service.ClientVersionResponse
import com.chipprbots.ethereum.jsonrpc.Web3Service.Sha3Request
import com.chipprbots.ethereum.jsonrpc.Web3Service.Sha3Response
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** Smoke coverage for [[Web3Service]] (RS08-REMAINDER-01 P3) — the `web3_*` namespace has no prior direct spec. */
class Web3ServiceSpec extends AnyFlatSpec with Matchers:

  private val service = new Web3Service()

  "Web3Service.sha3" should "hash the request data with keccak-256" taggedAs UnitTest in:
    val data: ByteString = ByteString("some input data")
    val result: Either[JsonRpcError, Sha3Response] = service.sha3(Sha3Request(data)).unsafeRunSync()
    result shouldBe Right(Sha3Response(crypto.kec256(data)))

  "Web3Service.clientVersion" should "return the configured client version string" taggedAs UnitTest in:
    val result: Either[JsonRpcError, ClientVersionResponse] =
      service.clientVersion(ClientVersionRequest()).unsafeRunSync()
    result shouldBe Right(ClientVersionResponse(Config.clientVersion))
