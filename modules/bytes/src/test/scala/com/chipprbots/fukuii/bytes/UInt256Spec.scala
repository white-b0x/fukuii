package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class UInt256Spec extends AnyFunSuite with ScalaCheckPropertyChecks:

  private val inRangeGen: Gen[BigInt] =
    Gen.chooseNum(0L, Long.MaxValue).map(BigInt(_))

  test("bytes is always 32 bytes, big-endian, left zero-padded"):
    assert(
      UInt256(1).bytes.toArray.sameElements(Array.fill[Byte](31)(0) :+ 1.toByte) &&
        UInt256.Zero.bytes.length == 32 &&
        UInt256.Zero.bytes.toArray.forall(_ == 0),
      "bytes must be exactly 32, big-endian, left zero-padded"
    )

  test("fromBytes / bytes round-trip preserves the numeric value"):
    forAll(inRangeGen): n =>
      assert(UInt256.fromBytes(UInt256(n).bytes).toBigInt == n)

  test("fromBytes treats bytes as unsigned big-endian; empty is Zero"):
    assert(
      UInt256.fromBytes(ByteString(0xff)).toBigInt == BigInt(255) &&
        UInt256.fromBytes(ByteString.empty).toBigInt == BigInt(0),
      "fromBytes must treat bytes as unsigned big-endian and map empty to Zero"
    )

  test("constants"):
    assert(
      UInt256.Zero.toBigInt == BigInt(0) &&
        UInt256.One.toBigInt == BigInt(1) &&
        UInt256.MaxValue.toBigInt == (BigInt(2).pow(256) - 1) &&
        UInt256.MaxValue.bytes.toArray.forall(_ == 0xff.toByte),
      "Zero/One/MaxValue must hold their expected numeric and byte values"
    )

  test("apply rejects out-of-range values"):
    val _ = intercept[IllegalArgumentException](UInt256(BigInt(-1)))
    intercept[IllegalArgumentException](UInt256(BigInt(2).pow(256)))

  test("apply(Long) rejects negatives"):
    intercept[IllegalArgumentException](UInt256(-1L))

  test("fromBytes rejects more than 32 bytes"):
    intercept[IllegalArgumentException](UInt256.fromBytes(ByteString(Array.fill[Byte](33)(1))))

  test("equality and ordering follow the numeric value"):
    val ord = summon[Ordering[UInt256]]
    assert(
      UInt256(5) == UInt256(5) &&
        ord.compare(UInt256(1), UInt256(2)) < 0 &&
        ord.compare(UInt256.MaxValue, UInt256.Zero) > 0,
      "equality and Ordering must agree with the numeric value"
    )

  test("isZero and toHex"):
    assert(
      UInt256.Zero.isZero &&
        !UInt256(1).isZero &&
        UInt256(1).toHex == ("0" * 63) + "1",
      "isZero and toHex must reflect the numeric value"
    )

  // --- 256-bit modular arithmetic (F-UINT-1) ---

  test("add/sub/mul wrap mod 2^256"):
    val twoTo128 = UInt256(BigInt(2).pow(128))
    assert(
      UInt256.MaxValue + UInt256.One == UInt256.Zero && // (2^256 - 1) + 1 = 2^256 ≡ 0
        UInt256.Zero - UInt256.One == UInt256.MaxValue && // underflow wraps to 2^256 - 1
        (twoTo128 * twoTo128) == UInt256.Zero && // 2^128 · 2^128 = 2^256 ≡ 0
        (UInt256(6) * UInt256(7)) == UInt256(42),
      "add/sub/mul must wrap modulo 2^256"
    )

  test("div and mod truncate; division/modulo by zero yield zero (EVM semantics)"):
    assert(
      UInt256(7) / UInt256(2) == UInt256(3) &&
        UInt256(7).mod(UInt256(3)) == UInt256(1) &&
        UInt256(5) / UInt256.Zero == UInt256.Zero &&
        UInt256(5).mod(UInt256.Zero) == UInt256.Zero,
      "div/mod must truncate and division/modulo by zero must yield zero"
    )

  test("pow is x^y mod 2^256 with x^0 = 1"):
    assert(
      UInt256(2).pow(UInt256(10)) == UInt256(1024) &&
        UInt256(2).pow(UInt256(256)) == UInt256.Zero && // wraps
        UInt256(123).pow(UInt256.Zero) == UInt256.One &&
        UInt256.Zero.pow(UInt256.Zero) == UInt256.One, // 0^0 = 1 (holiman/uint256)
      "pow must wrap modulo 2^256 with x^0 = 1"
    )

  test("bitwise and/or/xor/not"):
    val a = UInt256(0xf0)
    val b = UInt256(0x0f)
    assert(
      (a & b) == UInt256.Zero &&
        (a | b) == UInt256(0xff) &&
        (a ^ UInt256(0xff)) == UInt256(0x0f) &&
        ~UInt256.Zero == UInt256.MaxValue &&
        ~UInt256.MaxValue == UInt256.Zero,
      "bitwise and/or/xor/not must match expected bit patterns"
    )

  test("logical shifts, clamping at the word width"):
    assert(
      UInt256.One.shiftLeft(255).toBigInt == BigInt(2).pow(255) &&
        UInt256.One.shiftLeft(256) == UInt256.Zero && // >= 256 ⇒ 0
        UInt256(BigInt(2).pow(255)) + UInt256(BigInt(2).pow(255)) == UInt256.Zero && // top bit shifts out
        UInt256.MaxValue.shiftRight(255) == UInt256.One &&
        UInt256.MaxValue.shiftRight(256) == UInt256.Zero,
      "logical shifts must clamp at the word width"
    )

  test("unsigned comparison operators"):
    assert(
      UInt256.One < UInt256.MaxValue &&
        UInt256.MaxValue > UInt256.Zero &&
        UInt256(5) <= UInt256(5) &&
        UInt256(5) >= UInt256(5) &&
        !(UInt256.MaxValue < UInt256.Zero),
      "unsigned comparison operators must agree with the numeric value"
    )

  test("add/sub are inverse for in-range operands (property)"):
    forAll(inRangeGen, inRangeGen): (x, y) =>
      val a = UInt256(x)
      val b = UInt256(y)
      assert(((a + b) - b) == a)
