package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

class AddressSpec extends AnyFunSuite:

  private val twentyBytes = ByteString(Array.tabulate[Byte](20)(i => (i + 1).toByte))

  test("apply wraps exactly 20 bytes"):
    assert(Address(twentyBytes).bytes == twentyBytes)

  test("apply is strict — wrong-length input fails loud"):
    val _ = intercept[IllegalArgumentException](Address(ByteString(0xab, 0xcd)))
    val _ = intercept[IllegalArgumentException](Address(ByteString(Array.fill[Byte](21)(1))))
    intercept[IllegalArgumentException](Address(ByteString.empty))

  test("fromBytesTruncating left-pads a short input (geth SetBytes)"):
    val addr = Address.fromBytesTruncating(ByteString(0xab, 0xcd))
    assert(
      addr.bytes.length == 20 && addr.toHex == ("0" * 36) + "abcd",
      "a short input must left-pad to 20 bytes"
    )

  test("fromBytesTruncating keeps the rightmost 20 bytes of a long input (geth SetBytes)"):
    val raw = ByteString(Array.tabulate[Byte](25)(i => i.toByte))
    assert(Address.fromBytesTruncating(raw).bytes.toArray.sameElements(raw.takeRight(20).toArray))

  test("Zero is 20 zero bytes"):
    assert(
      Address.Zero.bytes.length == 20 && Address.Zero.toHex == "0" * 40,
      "Zero must be 20 zero bytes"
    )

  test("fromHex tolerates 0x, left-pads short, rejects oversized"):
    val _ = intercept[IllegalArgumentException](Address.fromHex("0x" + ("ab" * 21)))
    assert(
      Address.fromHex("0x00000000000000000000000000000000000000ff").toHex.takeRight(2) == "ff" &&
        Address.fromHex("0xabcd").toHex == ("0" * 36) + "abcd",
      "fromHex must tolerate a 0x prefix and left-pad a short input"
    )

  test("toPrefixedHex is 0x + 40 hex chars"):
    val s = Address.fromBytesTruncating(ByteString(0x01)).toPrefixedHex
    assert(
      s.startsWith("0x") && s.length == 42,
      "toPrefixedHex must be 0x followed by 40 hex chars"
    )

  test("equality is structural over the bytes"):
    assert(
      Address.fromBytesTruncating(ByteString(0x01)) == Address.fromBytesTruncating(ByteString(0x01)) &&
        Address.fromBytesTruncating(ByteString(0x01)) != Address.fromBytesTruncating(ByteString(0x02)),
      "equality must be structural over the bytes"
    )

  test("round-trips through UInt256 (low 20 bytes)"):
    val addr = Address(twentyBytes)
    assert(Address(addr.toUInt256) == addr)

  test("Address and Hash are type-distinct — a Hash is not assignable to an Address"):
    assertDoesNotCompile("val a: Address = Hash(org.apache.pekko.util.ByteString(1))")
