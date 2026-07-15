package com.chipprbots.fukuii.rlp

import com.chipprbots.fukuii.rlp.RLPCodecs.given
import org.scalatest.funsuite.AnyFunSuite

/** Strict decoding: a buffer that by design holds exactly one self-contained item must reject any trailing bytes. The
  * lenient [[decode]]/[[rawDecode]] accept them (a prefix-plus-payload frame legitimately has more bytes after the
  * first item).
  */
class RLPStrictDecodeSpec extends AnyFunSuite:

  test("decodeStrict rejects trailing bytes; decode ignores them"):
    val clean = encode(42)
    val withTrailing = clean ++ Array[Byte](0x01, 0x02)
    // lenient path decodes the first item and ignores the rest
    assert(decode[Int](withTrailing) == 42)
    // strict path fails loud
    intercept[RLPException](decodeStrict[Int](withTrailing))

  test("decodeStrict accepts a buffer consumed exactly"):
    val bytes = encode("dog")
    assert(decodeStrict[String](bytes) == "dog")

  test("rawDecodeStrict rejects trailing bytes after a list"):
    val listBytes = encode(RLPList(RLPEncoder.encode("dog"), RLPEncoder.encode("cat")))
    rawDecode(listBytes ++ Array[Byte](0x7f)) match // lenient: ok
      case _: RLPList => ()
      case other      => fail(s"expected RLPList, got $other")
    intercept[RLPException](rawDecodeStrict(listBytes ++ Array[Byte](0x7f)))

  test("truncated RLP (header promises more bytes than present) fails"):
    // 0x83 promises a 3-byte string but only 2 bytes follow
    intercept[RLPException](rawDecodeStrict(Array[Byte](0x83.toByte, 0x61, 0x62)))
