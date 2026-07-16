package com.chipprbots.fukuii.rlp

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.rlp.RLPCodecs.given

/** RLP codecs for the `bytes` value types. The consensus-critical distinction: [[UInt256]] is a minimal-length
  * big-endian *scalar* (no leading zeros), while [[Address]]/[[Hash]] are fixed-width *byte strings* (leading zeros
  * preserved).
  */
class RLPValueTypesSpec extends AnyFunSuite:

  test("UInt256 encodes as a minimal-length scalar — no leading zeros"):
    assert(
      Hex.toHexString(encode(UInt256.Zero)) == "80" && // 0 ⇒ empty string
        Hex.toHexString(encode(UInt256(1))) == "01" &&
        Hex.toHexString(encode(UInt256(127))) == "7f" &&
        Hex.toHexString(encode(UInt256(128))) == "8180" &&
        Hex.toHexString(encode(UInt256(255))) == "81ff" &&
        Hex.toHexString(encode(UInt256(256))) == "820100",
      "UInt256 must encode as a minimal-length scalar with no leading zeros"
    )

  test("UInt256 max value is a 32-byte scalar"):
    assert(Hex.toHexString(encode(UInt256.MaxValue)) == "a0" + ("ff" * 32))

  test("UInt256 round-trips, including values that decode from < 32 bytes"):
    for n <- Seq(UInt256.Zero, UInt256(1), UInt256(255), UInt256(256), UInt256(1000000), UInt256.MaxValue) do
      assert(decode[UInt256](encode(n)) == n)

  test("Address encodes as the full 20-byte string — leading zeros preserved (not a scalar)"):
    val addr = Address(ByteString(Array.tabulate[Byte](20)(i => (i + 1).toByte)))
    assert(
      Hex.toHexString(encode(Address.Zero)) == "94" + ("00" * 20) &&
        decode[Address](encode(addr)) == addr,
      "Address must encode as the full 20-byte string and round-trip"
    )

  test("Address with leading zero bytes keeps its full width through a round-trip"):
    val addr = Address.fromHex("0x0000000000000000000000000000000000abcdef")
    val bytes = encode(addr)
    assert(
      bytes.length == 21 && // 0x94 header + 20 bytes
        decode[Address](bytes) == addr,
      "an Address with leading zero bytes must keep its full width through a round-trip"
    )

  test("Hash encodes as the full 32-byte string; round-trips"):
    val h = Hash(ByteString(Array.tabulate[Byte](32)(i => (i * 7).toByte)))
    assert(
      Hex.toHexString(encode(Hash.Zero)) == "a0" + ("00" * 32) &&
        decode[Hash](encode(h)) == h,
      "Hash must encode as the full 32-byte string and round-trip"
    )
