package com.chipprbots.fukuii.crypto

import org.scalatest.funsuite.AnyFunSuite

class ConstantTimeSpec extends AnyFunSuite:

  test("equal arrays compare true"):
    assert(constantTimeEquals(Array[Byte](1, 2, 3, 4), Array[Byte](1, 2, 3, 4)))

  test("unequal arrays of the same length compare false"):
    assert(!constantTimeEquals(Array[Byte](1, 2, 3, 4), Array[Byte](1, 2, 3, 5)))

  test("arrays of different length compare false"):
    assert(!constantTimeEquals(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3, 4)))

  test("two empty arrays compare true"):
    assert(constantTimeEquals(Array.emptyByteArray, Array.emptyByteArray))
