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
    assert(Hex.toHexString(encode(UInt256.Zero)) == "80") // 0 ⇒ empty string
    assert(Hex.toHexString(encode(UInt256(1))) == "01")
    assert(Hex.toHexString(encode(UInt256(127))) == "7f")
    assert(Hex.toHexString(encode(UInt256(128))) == "8180")
    assert(Hex.toHexString(encode(UInt256(255))) == "81ff")
    assert(Hex.toHexString(encode(UInt256(256))) == "820100")

  test("UInt256 max value is a 32-byte scalar"):
    assert(Hex.toHexString(encode(UInt256.MaxValue)) == "a0" + ("ff" * 32))

  test("UInt256 round-trips, including values that decode from < 32 bytes"):
    for n <- Seq(UInt256.Zero, UInt256(1), UInt256(255), UInt256(256), UInt256(1000000), UInt256.MaxValue) do
      assert(decode[UInt256](encode(n)) == n)

  test("Address encodes as the full 20-byte string — leading zeros preserved (not a scalar)"):
    assert(Hex.toHexString(encode(Address.Zero)) == "94" + ("00" * 20))
    val addr = Address(ByteString(Array.tabulate[Byte](20)(i => (i + 1).toByte)))
    assert(decode[Address](encode(addr)) == addr)

  test("Address with leading zero bytes keeps its full width through a round-trip"):
    val addr = Address.fromHex("0x0000000000000000000000000000000000abcdef")
    val bytes = encode(addr)
    assert(bytes.length == 21) // 0x94 header + 20 bytes
    assert(decode[Address](bytes) == addr)

  test("Hash encodes as the full 32-byte string; round-trips"):
    assert(Hex.toHexString(encode(Hash.Zero)) == "a0" + ("00" * 32))
    val h = Hash(ByteString(Array.tabulate[Byte](32)(i => (i * 7).toByte)))
    assert(decode[Hash](encode(h)) == h)
