package com.chipprbots.fukuii.rlp

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.rlp.RLPCodecs.given

/** Canonical-decode enforcement — the strict-decode rules go-ethereum applies in `rlp/raw.go` and `rlp/decode.go`. Old
  * fukuii inherited Mantis's lenient decoder, which accepted these non-canonical encodings; a lenient decoder is a
  * network-partition / consensus-divergence vector, so each of these must be rejected. Vectors are named after
  * `ethereum/tests/RLPTests/invalidRLPTest.json`.
  */
class RLPCanonicalDecodeSpec extends AnyFunSuite:

  private def bytes(hex: String): Array[Byte] = Hex.decode(hex)

  // --- F-RLP-1: non-canonical size headers (ErrCanonSize, go-ethereum rlp/raw.go:360-363, 410-414) ---

  // A single byte < 0x80 must be its own encoding, never a `0x81 xx` short string.
  private val singleByteViolations = List(
    "bytesShouldBeSingleByte00" -> "8100",
    "bytesShouldBeSingleByte01" -> "8101",
    "bytesShouldBeSingleByte7F" -> "817f"
  )

  // Long-form header used where the payload is < 56 bytes (must use the short form).
  private val nonOptimalLongLength = List(
    "wrongSizeList" -> "f80180",
    "wrongSizeList2" -> "f80100",
    "nonOptimalLongLengthArray1" -> "b81000112233445566778899aabbccddeeff",
    "nonOptimalLongLengthArray2" -> "b801ff",
    "nonOptimalLongLengthList1" -> "f810000102030405060708090a0b0c0d0e0f",
    "nonOptimalLongLengthList2" -> "f803112233"
  )

  // Length-of-length field with a leading zero byte.
  private val leadingZeroLength = List(
    "leadingZerosInLongLengthArray1" ->
      "b90040000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
    "leadingZerosInLongLengthArray2" -> "b800",
    "leadingZerosInLongLengthList1" ->
      "fb00000040000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
    "leadingZerosInLongLengthList2" -> "f800"
  )

  for (name, hex) <- singleByteViolations do
    test(s"F-RLP-1 rejects single-byte-as-string: $name"):
      intercept[RLPException](rawDecode(bytes(hex)))

  for (name, hex) <- nonOptimalLongLength do
    test(s"F-RLP-1 rejects long-form header for payload < 56: $name"):
      intercept[RLPException](rawDecode(bytes(hex)))

  for (name, hex) <- leadingZeroLength do
    test(s"F-RLP-1 rejects leading zero in length-of-length: $name"):
      intercept[RLPException](rawDecode(bytes(hex)))

  // --- F-RLP-3: payload extends beyond the buffer (ErrValueTooLarge, go-ethereum rlp/raw.go:380-383) ---

  private val truncated = List(
    "lessThanShortLengthArray1" -> "81",
    "lessThanShortLengthArray2" -> "a0000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e",
    "lessThanShortLengthList1" -> "c5010203",
    "lessThanShortLengthList2" -> "e201020304050607",
    "lessThanLongLengthArray1" -> "ba010000aabbccddeeff",
    "lessThanLongLengthArray2" -> "b840ffeeddccbbaa99887766554433221100",
    "lessThanLongLengthList1" -> "f90180",
    "incorrectLengthInArray" -> "b9002100dc2b275d0f74e8a53e6f4ec61b27f24278820be3f82ea2110e582081b0565df0"
  )

  for (name, hex) <- truncated do
    test(s"F-RLP-3 rejects truncated payload: $name"):
      intercept[RLPException](rawDecode(bytes(hex)))

  // --- GAP-3: length-of-length overflow (>4 bytes cannot fit fukuii's Int length) rejected by name ---

  test("GAP-3 rejects int32Overflow by name (8-byte length-of-length on a string)"):
    intercept[RLPException](rawDecode(bytes("bf0f000000000000021111")))

  test("GAP-3 rejects int32Overflow2 by name (8-byte length-of-length on a list)"):
    intercept[RLPException](rawDecode(bytes("ff0f000000000000021111")))

  test("GAP-3 rejects lessThanLongLengthList2 by name (8-byte length-of-length list)"):
    intercept[RLPException](rawDecode(bytes("ffffffffffffffffff0001020304050607")))

  // --- J-RLP-1: signed-Int payload-end overflow bypasses the F-RLP-3 bounds guard ---
  //
  // A canonical 4-byte length header whose value lands in the top Int window (~0x7ffffffd) makes
  // `beginPos + length - 1` overflow to a negative Int. The old `end >= data.length` guard then read
  // `negative >= length -> false`, bypassing the bounds check: lenient decode accepted the malformed
  // frame and list decode drove `pos` negative into an ArrayIndexOutOfBoundsException. The length math is
  // now carried in Long (besu Math.toIntExact gating), so both branches reject cleanly with RLPException.

  // Long-string header: prefix 0xbb (length-of-length 4), length 0x7ffffffd, one payload byte.
  private val overflowString = "bb7ffffffd"
  // Long-list analogue: prefix 0xfb (length-of-length 4), same oversize length.
  private val overflowList = "fb7ffffffd"

  test("J-RLP-1 rejects int-overflow long-string length with a clean RLPException (lenient rawDecode)"):
    val ex = intercept[RLPException](rawDecode(bytes(overflowString)))
    assert(!ex.isInstanceOf[ArrayIndexOutOfBoundsException])

  test("J-RLP-1 rejects int-overflow long-list length with a clean RLPException (lenient rawDecode)"):
    val ex = intercept[RLPException](rawDecode(bytes(overflowList)))
    assert(!ex.isInstanceOf[ArrayIndexOutOfBoundsException])

  test("J-RLP-1 does not raise ArrayIndexOutOfBoundsException on the overflow vectors"):
    intercept[RLPException](rawDecode(bytes(overflowString)))
    intercept[RLPException](rawDecode(bytes(overflowList)))

  test("J-RLP-1 rejects the overflow vectors on the strict path too"):
    intercept[RLPException](rawDecodeStrict(bytes(overflowString)))
    intercept[RLPException](rawDecodeStrict(bytes(overflowList)))

  test("J-RLP-1 does not regress ordinary large-but-valid long-form items"):
    // 56-byte payload -> smallest canonical long-form string header (0xb8 0x38 ...).
    val payload = "01" * 56
    val decoded = rawDecode(bytes("b838" + payload))
    assert(decoded.isInstanceOf[RLPValue])
    assert(decoded.asInstanceOf[RLPValue].bytes.sameElements(bytes(payload)))
    // 56-byte long-form list of 56 single-byte items round-trips.
    val listBody = "01" * 56
    val decodedList = rawDecode(bytes("f838" + listBody))
    assert(decodedList.isInstanceOf[RLPList])
    assert(decodedList.asInstanceOf[RLPList].items.size == 56)

  // --- F-RLP-2: scalar decoders reject leading zeros (ErrCanonInt, go-ethereum rlp/decode.go:750) ---

  test("F-RLP-2 Int decoder rejects a single 0x00 byte (zero must be the empty string)"):
    intercept[RLPException](decode[Int](bytes("00")))

  test("F-RLP-2 Int decoder rejects a leading-zero scalar 0x820005"):
    intercept[RLPException](decode[Int](bytes("820005")))

  test("F-RLP-2 BigInt decoder rejects a leading-zero scalar 0x820005"):
    intercept[RLPException](decode[BigInt](bytes("820005")))

  test("F-RLP-2 Long decoder rejects a leading-zero scalar 0x820005"):
    intercept[RLPException](decode[Long](bytes("820005")))

  test("F-RLP-2 UInt256 decoder rejects a leading-zero scalar 0x820005"):
    intercept[RLPException](decode[UInt256](bytes("820005")))

  test("F-RLP-2 canonical scalars still decode (empty ⇒ 0, self-byte ⇒ value)"):
    assert(decode[Int](encode(0)) == 0)
    assert(decode[Int](encode(5)) == 5)
    assert(decode[BigInt](encode(BigInt(0))) == BigInt(0))
    assert(decode[UInt256](encode(UInt256(258))) == UInt256(258))

  // --- B-RLP-N2: Byte/Short decoders carry the same ErrCanonInt guard as the other int kinds ---

  test("B-RLP-N2 Byte decoder rejects a single 0x00 byte (zero must be the empty string)"):
    intercept[RLPException](decode[Byte](bytes("00")))

  test("B-RLP-N2 Short decoder rejects a single 0x00 byte"):
    intercept[RLPException](decode[Short](bytes("00")))

  test("B-RLP-N2 Short decoder rejects a leading-zero scalar 0x820005"):
    intercept[RLPException](decode[Short](bytes("820005")))

  test("B-RLP-N2 canonical Byte/Short scalars still decode (empty ⇒ 0, self-byte ⇒ value)"):
    assert(decode[Byte](encode(0: Byte)) == (0: Byte))
    assert(decode[Byte](encode(5: Byte)) == (5: Byte))
    assert(decode[Short](encode(0: Short)) == (0: Short))
    assert(decode[Short](encode(258: Short)) == (258: Short))
