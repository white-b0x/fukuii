package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString
import org.scalatest.funsuite.AnyFunSuite

class HashSpec extends AnyFunSuite:

  private val thirtyTwoBytes = ByteString(Array.tabulate[Byte](32)(i => (i + 1).toByte))

  test("apply wraps exactly 32 bytes"):
    assert(Hash(thirtyTwoBytes).bytes == thirtyTwoBytes)

  test("apply is strict — wrong-length input fails loud"):
    intercept[IllegalArgumentException](Hash(ByteString(0xff)))
    intercept[IllegalArgumentException](Hash(ByteString(Array.fill[Byte](33)(1))))
    intercept[IllegalArgumentException](Hash(ByteString.empty))

  test("fromBytesTruncating left-pads a short input (geth SetBytes)"):
    val h = Hash.fromBytesTruncating(ByteString(0xff))
    assert(h.bytes.length == 32)
    assert(h.toHex == ("0" * 62) + "ff")

  test("fromBytesTruncating keeps the rightmost 32 bytes of a long input (geth SetBytes)"):
    val raw = ByteString(Array.tabulate[Byte](40)(i => i.toByte))
    assert(Hash.fromBytesTruncating(raw).bytes.toArray.sameElements(raw.takeRight(32).toArray))

  test("Zero is 32 zero bytes"):
    assert(Hash.Zero.bytes.length == 32)
    assert(Hash.Zero.toHex == "0" * 64)

  test("fromHex tolerates 0x, left-pads short, rejects oversized"):
    assert(Hash.fromHex("0x" + ("00" * 31) + "ff").toHex.takeRight(2) == "ff")
    assert(Hash.fromHex("0xff").toHex == ("0" * 62) + "ff")
    intercept[IllegalArgumentException](Hash.fromHex("0x" + ("ab" * 33)))

  test("equality is structural and ordering is unsigned"):
    assert(Hash.fromBytesTruncating(ByteString(0x01)) == Hash.fromBytesTruncating(ByteString(0x01)))
    val ord = summon[Ordering[Hash]]
    assert(ord.compare(Hash.fromBytesTruncating(ByteString(0xff)), Hash.fromBytesTruncating(ByteString(0x01))) > 0)

  test("Bytes32 is the same type as Hash"):
    val b: Bytes32 = Hash(thirtyTwoBytes)
    val h: Hash = b
    assert(h == b)
