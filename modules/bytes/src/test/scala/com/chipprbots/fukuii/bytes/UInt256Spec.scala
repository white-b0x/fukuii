package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class UInt256Spec extends AnyFunSuite with ScalaCheckPropertyChecks:

  private val inRangeGen: Gen[BigInt] =
    Gen.chooseNum(0L, Long.MaxValue).map(BigInt(_))

  test("bytes is always 32 bytes, big-endian, left zero-padded"):
    assert(UInt256(1).bytes.toArray.sameElements(Array.fill[Byte](31)(0) :+ 1.toByte))
    assert(UInt256.Zero.bytes.length == 32)
    assert(UInt256.Zero.bytes.toArray.forall(_ == 0))

  test("fromBytes / bytes round-trip preserves the numeric value"):
    forAll(inRangeGen): n =>
      assert(UInt256.fromBytes(UInt256(n).bytes).toBigInt == n)

  test("fromBytes treats bytes as unsigned big-endian; empty is Zero"):
    assert(UInt256.fromBytes(ByteString(0xff)).toBigInt == BigInt(255))
    assert(UInt256.fromBytes(ByteString.empty).toBigInt == BigInt(0))

  test("constants"):
    assert(UInt256.Zero.toBigInt == BigInt(0))
    assert(UInt256.One.toBigInt == BigInt(1))
    assert(UInt256.MaxValue.toBigInt == (BigInt(2).pow(256) - 1))
    assert(UInt256.MaxValue.bytes.toArray.forall(_ == 0xff.toByte))

  test("apply rejects out-of-range values"):
    intercept[IllegalArgumentException](UInt256(BigInt(-1)))
    intercept[IllegalArgumentException](UInt256(BigInt(2).pow(256)))

  test("apply(Long) rejects negatives"):
    intercept[IllegalArgumentException](UInt256(-1L))

  test("fromBytes rejects more than 32 bytes"):
    intercept[IllegalArgumentException](UInt256.fromBytes(ByteString(Array.fill[Byte](33)(1))))

  test("equality and ordering follow the numeric value"):
    assert(UInt256(5) == UInt256(5))
    val ord = summon[Ordering[UInt256]]
    assert(ord.compare(UInt256(1), UInt256(2)) < 0)
    assert(ord.compare(UInt256.MaxValue, UInt256.Zero) > 0)

  test("isZero and toHex"):
    assert(UInt256.Zero.isZero)
    assert(!UInt256(1).isZero)
    assert(UInt256(1).toHex == ("0" * 63) + "1")
