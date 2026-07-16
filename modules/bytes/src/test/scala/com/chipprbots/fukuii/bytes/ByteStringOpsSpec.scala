package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import ByteStringOps.{*, given}

class ByteStringOpsSpec extends AnyFunSuite:

  test("toHex / toPrefixedHex"):
    assert(
      ByteString(0xde, 0xad, 0xbe, 0xef).toHex == "deadbeef" &&
        ByteString(0xde, 0xad, 0xbe, 0xef).toPrefixedHex == "0xdeadbeef",
      "toHex and toPrefixedHex must render the expected hex form"
    )

  test("padLeft prepends, padRight appends, both no-op past length"):
    assert(
      ByteString(1, 2).padLeft(4, 0).sameElements(Array[Byte](0, 0, 1, 2)) &&
        ByteString(1, 2).padRight(4, 0).sameElements(Array[Byte](1, 2, 0, 0)) &&
        ByteString(1, 2, 3).padLeft(2, 0).sameElements(Array[Byte](1, 2, 3)),
      "padLeft/padRight must prepend/append up to the target length and be a no-op past it"
    )

  test("ordering is unsigned lexicographic (0xff sorts after 0x01)"):
    val ord = summon[Ordering[ByteString]]
    assert(
      ord.compare(ByteString(0xff), ByteString(0x01)) > 0 &&
        ord.compare(ByteString(0x01), ByteString(0xff)) < 0,
      "ordering must be unsigned lexicographic"
    )

  test("ordering: shorter prefix sorts before its extension"):
    val ord = summon[Ordering[ByteString]]
    assert(
      ord.compare(ByteString(1, 2), ByteString(1, 2, 3)) < 0 &&
        ord.compare(ByteString(1, 2), ByteString(1, 2)) == 0,
      "a shorter prefix must sort before its extension"
    )

  test("sorting a sequence uses the unsigned order"):
    val sorted = List(ByteString(0xff), ByteString(0x00), ByteString(0x80)).sorted
    assert(sorted.map(_.toHex) == List("00", "80", "ff"))
