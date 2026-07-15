package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

class AddressSpec extends AnyFunSuite:

  private val twentyBytes = ByteString(Array.tabulate[Byte](20)(i => (i + 1).toByte))

  test("apply wraps exactly 20 bytes"):
    assert(Address(twentyBytes).bytes == twentyBytes)

  test("apply is strict — wrong-length input fails loud"):
    intercept[IllegalArgumentException](Address(ByteString(0xab, 0xcd)))
    intercept[IllegalArgumentException](Address(ByteString(Array.fill[Byte](21)(1))))
    intercept[IllegalArgumentException](Address(ByteString.empty))

  test("fromBytesTruncating left-pads a short input (geth SetBytes)"):
    val addr = Address.fromBytesTruncating(ByteString(0xab, 0xcd))
    assert(addr.bytes.length == 20)
    assert(addr.toHex == ("0" * 36) + "abcd")

  test("fromBytesTruncating keeps the rightmost 20 bytes of a long input (geth SetBytes)"):
    val raw = ByteString(Array.tabulate[Byte](25)(i => i.toByte))
    assert(Address.fromBytesTruncating(raw).bytes.toArray.sameElements(raw.takeRight(20).toArray))

  test("Zero is 20 zero bytes"):
    assert(Address.Zero.bytes.length == 20)
    assert(Address.Zero.toHex == "0" * 40)

  test("fromHex tolerates 0x, left-pads short, rejects oversized"):
    assert(Address.fromHex("0x00000000000000000000000000000000000000ff").toHex.takeRight(2) == "ff")
    assert(Address.fromHex("0xabcd").toHex == ("0" * 36) + "abcd")
    intercept[IllegalArgumentException](Address.fromHex("0x" + ("ab" * 21)))

  test("toPrefixedHex is 0x + 40 hex chars"):
    val s = Address.fromBytesTruncating(ByteString(0x01)).toPrefixedHex
    assert(s.startsWith("0x"))
    assert(s.length == 42)

  test("equality is structural over the bytes"):
    assert(Address.fromBytesTruncating(ByteString(0x01)) == Address.fromBytesTruncating(ByteString(0x01)))
    assert(Address.fromBytesTruncating(ByteString(0x01)) != Address.fromBytesTruncating(ByteString(0x02)))

  test("round-trips through UInt256 (low 20 bytes)"):
    val addr = Address(twentyBytes)
    assert(Address(addr.toUInt256) == addr)

  test("Address and Hash are type-distinct — a Hash is not assignable to an Address"):
    assertDoesNotCompile("val a: Address = Hash(org.apache.pekko.util.ByteString(1))")
