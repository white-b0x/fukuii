package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ByteUtilsSpec extends AnyFunSuite with ScalaCheckPropertyChecks:

  private val nonNegBigIntGen: Gen[BigInt] =
    Gen.chooseNum(0L, Long.MaxValue).map(BigInt(_))

  test("bytesToBigInt / bigIntToBytes round-trip at fixed width"):
    forAll(nonNegBigIntGen): n =>
      val bytes = ByteUtils.bigIntToBytes(n, 32)
      assert(bytes.length == 32)
      assert(ByteUtils.bytesToBigInt(bytes) == n)

  test("bytesToBigInt treats bytes as unsigned big-endian"):
    assert(ByteUtils.bytesToBigInt(Array(0xff.toByte)) == BigInt(255))
    assert(ByteUtils.bytesToBigInt(Array(0x01, 0x00).map(_.toByte)) == BigInt(256))

  test("bytesToBigInt of empty array is zero"):
    assert(ByteUtils.bytesToBigInt(Array.emptyByteArray) == BigInt(0))

  test("bigIntToBytes left-pads with zeros"):
    assert(ByteUtils.bigIntToBytes(BigInt(1), 4).sameElements(Array[Byte](0, 0, 0, 1)))

  test("bigIntToBytes keeps the least-significant bytes when the value is wider than the field"):
    assert(ByteUtils.bigIntToBytes(BigInt(0x0102), 1).sameElements(Array[Byte](0x02)))

  test("bigIntToBytes handles a value whose top bit is set (no stray sign byte)"):
    val v = BigInt(1) << 255
    val bytes = ByteUtils.bigIntToBytes(v, 32)
    assert(bytes.length == 32)
    assert(bytes(0) == 0x80.toByte)
    assert(ByteUtils.bytesToBigInt(bytes) == v)

  test("bigIntToBytes rejects a negative input"):
    intercept[IllegalArgumentException](ByteUtils.bigIntToBytes(BigInt(-1), 32))

  test("bigIntToUnsignedBytes is minimal-length and empty for zero"):
    assert(ByteUtils.bigIntToUnsignedBytes(BigInt(0)).isEmpty)
    assert(ByteUtils.bigIntToUnsignedBytes(BigInt(255)).sameElements(Array[Byte](0xff.toByte)))
    assert(ByteUtils.bigIntToUnsignedBytes(BigInt(256)).sameElements(Array[Byte](0x01, 0x00)))

  test("padLeft prepends up to length and is a no-op past it"):
    assert(ByteUtils.padLeft(ByteString(1, 2), 4, 0).sameElements(Array[Byte](0, 0, 1, 2)))
    assert(ByteUtils.padLeft(ByteString(1, 2, 3), 2, 0).sameElements(Array[Byte](1, 2, 3)))

  test("matchingLength counts the shared prefix"):
    assert(ByteUtils.matchingLength(Array(1, 2, 3), Array(1, 2, 9)) == 2)
    assert(ByteUtils.matchingLength(Array(1, 2), Array(9, 9)) == 0)
    assert(ByteUtils.matchingLength(Array.emptyByteArray, Array[Byte](1)) == 0)

  test("xor of two arrays"):
    val out = ByteUtils.xor(Array(0x0f, 0xf0).map(_.toByte), Array(0xff, 0x0f).map(_.toByte))
    assert(out.sameElements(Array(0xf0.toByte, 0xff.toByte)))

  test("xor rejects length mismatch"):
    intercept[IllegalArgumentException](ByteUtils.xor(Array[Byte](1), Array[Byte](1, 2)))

  test("or / and combine equal-length arrays"):
    assert(ByteUtils.or(Array(0x01, 0x00).map(_.toByte), Array(0x00, 0x02).map(_.toByte)).sameElements(Array[Byte](0x01, 0x02)))
    assert(ByteUtils.and(Array(0x0f, 0xff).map(_.toByte), Array(0x01, 0x0f).map(_.toByte)).sameElements(Array[Byte](0x01, 0x0f)))

  test("or / and reject a length mismatch and an empty varargs"):
    intercept[IllegalArgumentException](ByteUtils.or(Array[Byte](1), Array[Byte](1, 2)))
    intercept[IllegalArgumentException](ByteUtils.and())
