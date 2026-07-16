package com.chipprbots.fukuii.bytes

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class HexSpec extends AnyFunSuite with ScalaCheckPropertyChecks:

  private val byteArrayGen: Gen[Array[Byte]] =
    Gen.listOf(Gen.choose(Byte.MinValue, Byte.MaxValue)).map(_.toArray)

  test("encode then decode round-trips for arbitrary bytes"):
    forAll(byteArrayGen): bytes =>
      assert(Hex.decode(Hex.toHexString(bytes)).sameElements(bytes))

  test("toHexString emits lower-case, two chars per byte"):
    assert(Hex.toHexString(Array(0x00, 0x0f, 0xff, 0xa5).map(_.toByte)) == "000fffa5")

  test("empty input encodes to empty string and back"):
    assert(
      Hex.toHexString(Array.emptyByteArray) == "" && Hex.decode("").isEmpty,
      "empty input must encode to the empty string and decode back to empty"
    )

  test("decode accepts an optional 0x / 0X prefix"):
    assert(
      Hex.decode("0xdeadbeef").sameElements(Hex.decode("deadbeef")) &&
        Hex.decode("0XDEADBEEF").sameElements(Hex.decode("deadbeef")),
      "decode must accept an optional 0x / 0X prefix"
    )

  test("decode is case-insensitive"):
    assert(Hex.decode("ABCDEF").sameElements(Hex.decode("abcdef")))

  test("decode rejects an odd number of nibbles"):
    val _ = intercept[IllegalArgumentException](Hex.decode("abc"))
    intercept[IllegalArgumentException](Hex.decode("0xabc"))

  test("decode rejects a non-hex character"):
    val _ = intercept[IllegalArgumentException](Hex.decode("zz"))
    intercept[IllegalArgumentException](Hex.decode("00gg"))
