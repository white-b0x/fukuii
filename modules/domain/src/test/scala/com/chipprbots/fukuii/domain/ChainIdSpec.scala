package com.chipprbots.fukuii.domain

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.rlp.decode
import com.chipprbots.fukuii.rlp.encode

class ChainIdSpec extends AnyFunSuite:

  test("ETC mainnet chainId is 61, Mordor is 63 (core-geth config_classic.go/config_mordor.go)"):
    assert(
      ChainId(61).toBigInt == BigInt(61) &&
        ChainId(63).toBigInt == BigInt(63),
      "ETC mainnet chainId must be 61 and Mordor's must be 63"
    )

  test("ETH mainnet chainId is 1, Sepolia is 11155111"):
    assert(
      ChainId(1).toBigInt == BigInt(1) &&
        ChainId(11155111L).toBigInt == BigInt(11155111),
      "ETH mainnet chainId must be 1 and Sepolia's must be 11155111"
    )

  test("a negative chainId is rejected fail-loud"):
    intercept[IllegalArgumentException](ChainId(-1))

  test("equality is structural over the underlying value"):
    assert(
      ChainId(61) == ChainId(61) &&
        ChainId(61) != ChainId(63),
      "equal underlying values must be equal ChainIds and differing values must not"
    )

  test("ChainId is type-distinct from a raw BigInt"):
    assertDoesNotCompile("val c: ChainId = BigInt(61)")

  test("RLP round-trips ETC mainnet (61) and Mordor (63)"):
    assert(
      decode[ChainId](encode(ChainId(61))) == ChainId(61) &&
        decode[ChainId](encode(ChainId(63))) == ChainId(63),
      "RLP must round-trip both ETC mainnet and Mordor chain IDs"
    )

  test("RLP round-trips ETH mainnet (1) and Sepolia (11155111)"):
    assert(
      decode[ChainId](encode(ChainId(1))) == ChainId(1) &&
        decode[ChainId](encode(ChainId(11155111L))) == ChainId(11155111L),
      "RLP must round-trip both ETH mainnet and Sepolia chain IDs"
    )
