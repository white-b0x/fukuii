package com.chipprbots.fukuii.domain

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.decode
import com.chipprbots.fukuii.rlp.encode

class WeiSpec extends AnyFunSuite:

  test("Wei round-trips through RLP as a minimal-length scalar, matching UInt256"):
    val amount = Wei(UInt256(1000000))
    assert(decode[Wei](encode(amount)) == amount)
    assert(encode(amount).sameElements(encode(amount.toUInt256)))

  test("Wei.Zero encodes as the empty string (RLP scalar rule)"):
    assert(encode(Wei.Zero).sameElements(encode(UInt256.Zero)))

  test("Wei is 32-byte-bounded — the full UInt256 range round-trips"):
    val max = Wei(UInt256.MaxValue)
    assert(decode[Wei](encode(max)) == max)
    assert(max.bytes.length == UInt256.Size)

  test("Wei is type-distinct from a raw UInt256"):
    assertDoesNotCompile("val w: Wei = UInt256(1)")
