package com.chipprbots.fukuii.domain

import org.scalatest.funsuite.AnyFunSuite

class ChainIdSpec extends AnyFunSuite:

  test("ETC mainnet chainId is 61, Mordor is 63 (core-geth config_classic.go/config_mordor.go)"):
    assert(ChainId(61).toBigInt == BigInt(61))
    assert(ChainId(63).toBigInt == BigInt(63))

  test("ETH mainnet chainId is 1, Sepolia is 11155111"):
    assert(ChainId(1).toBigInt == BigInt(1))
    assert(ChainId(11155111L).toBigInt == BigInt(11155111))

  test("a negative chainId is rejected fail-loud"):
    intercept[IllegalArgumentException](ChainId(-1))

  test("equality is structural over the underlying value"):
    assert(ChainId(61) == ChainId(61))
    assert(ChainId(61) != ChainId(63))

  test("ChainId is type-distinct from a raw BigInt"):
    assertDoesNotCompile("val c: ChainId = BigInt(61)")
