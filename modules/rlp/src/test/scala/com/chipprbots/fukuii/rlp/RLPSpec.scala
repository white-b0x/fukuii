package com.chipprbots.fukuii.rlp

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.rlp.RLPCodecs.given

/** Canonical RLP-spec byte vectors (from `ethereum/tests/RLPTests/rlptest.json`) plus round-trips for every base and
  * value type. Byte layout is consensus-critical and matched against go-ethereum (`rlp/raw.go`, `rlp/encbuffer.go`).
  */
class RLPSpec extends AnyFunSuite:

  private def hex[T](value: T)(using RLPEncoder[T]): String = Hex.toHexString(encode(value))

  // --- canonical string vectors -------------------------------------------

  test("empty string encodes to 0x80"):
    assert(hex("") == "80")

  test("single low bytes encode as themselves"):
    assert(
      hex(new String(Array[Byte](0))) == "00" &&
        hex(new String(Array[Byte](1))) == "01" &&
        hex(new String(Array[Byte](0x7f))) == "7f",
      "single low bytes must encode as themselves"
    )

  test("short string 'dog' encodes to 0x83646f67"):
    assert(hex("dog") == "83646f67")

  test("55-byte string uses the short-string header (0xb7 boundary)"):
    val s = "Lorem ipsum dolor sit amet, consectetur adipisicing eli"
    assert(
      s.length == 55 &&
        hex(
          s
        ) == "b74c6f72656d20697073756d20646f6c6f722073697420616d65742c20636f6e7365637465747572206164697069736963696e6720656c69",
      "a 55-byte string must use the short-string header (0xb7 boundary)"
    )

  test("56-byte string crosses to the long-string header (0xb838)"):
    val s = "Lorem ipsum dolor sit amet, consectetur adipisicing elit"
    assert(
      s.length == 56 &&
        hex(
          s
        ) == "b8384c6f72656d20697073756d20646f6c6f722073697420616d65742c20636f6e7365637465747572206164697069736963696e6720656c6974",
      "a 56-byte string must cross to the long-string header (0xb838)"
    )

  // --- canonical integer scalar vectors -----------------------------------

  test("integer scalars are minimal-length big-endian"):
    assert(
      hex(0) == "80" && // zero ⇒ empty string
        hex(1) == "01" &&
        hex(16) == "10" &&
        hex(127) == "7f" &&
        hex(128) == "8180" &&
        hex(1000) == "8203e8" &&
        hex(100000) == "830186a0",
      "integer scalars must be minimal-length big-endian"
    )

  // --- canonical list vectors ---------------------------------------------

  test("empty list encodes to 0xc0"):
    assert(Hex.toHexString(encode(RLPList())) == "c0")

  test("string list ['dog','god','cat'] encodes to 0xcc83646f6783676f6483636174"):
    assert(hex(List("dog", "god", "cat")) == "cc83646f6783676f6483636174")

  test("mixed nested list ['zw',[4],1] encodes to 0xc6827a77c10401"):
    val multi = RLPList(RLPEncoder.encode("zw"), RLPList(RLPEncoder.encode(4)), RLPEncoder.encode(1))
    assert(Hex.toHexString(encode(multi)) == "c6827a77c10401")

  test("nested list of lists round-trips through the AST"):
    val inner = List("asdf", "qwer", "zxcv")
    val nested = RLPList(List.fill(4)(RLPEncoder.encode(inner))*)
    val bytes = encode(nested)
    assert(
      Hex.toHexString(bytes) ==
        "f840cf84617364668471776572847a786376cf84617364668471776572847a786376cf84617364668471776572847a786376cf84617364668471776572847a786376" &&
        // round-trip proof is byte-equality: re-encoding the decoded AST reproduces the input exactly
        // (RLPValue is array-backed, so AST `==` is reference equality — bytes are the invariant)
        encode(rawDecode(bytes)).sameElements(bytes),
      "the nested list must match the canonical encoding and round-trip byte-for-byte through the AST"
    )

  // --- base-type round-trips ----------------------------------------------

  test("round-trip: Byte across the full range"):
    for b <- Byte.MinValue to Byte.MaxValue do assert(decode[Byte](encode(b.toByte)) == b.toByte)

  test("round-trip: Short boundary values"):
    for s <- Seq[Short](0, 1, 127, 128, 255, 256, 30303, -1, Short.MinValue, Short.MaxValue) do
      assert(decode[Short](encode(s)) == s)

  test("round-trip: Int / Long / BigInt"):
    for i <- Seq(0, 1, 255, 256, 65535, 100000, Int.MaxValue) do assert(decode[Int](encode(i)) == i)
    for l <- Seq(0L, 1L, 255L, 1L << 40, Long.MaxValue) do assert(decode[Long](encode(l)) == l)
    for n <- Seq(BigInt(0), BigInt(1), BigInt(2).pow(64), BigInt(Long.MaxValue) * 16) do
      assert(decode[BigInt](encode(n)) == n)

  test("round-trip: String / Array[Byte] / ByteString"):
    val arr = Array[Byte](1, 2, 3, 4, 5)
    val bs = ByteString(Array.tabulate[Byte](40)(_.toByte))
    assert(
      decode[String](encode("EthereumJ Client")) == "EthereumJ Client" &&
        decode[Array[Byte]](encode(arr)).sameElements(arr) &&
        decode[ByteString](encode(bs)) == bs,
      "String / Array[Byte] / ByteString must round-trip through RLP"
    )

  test("round-trip: Boolean"):
    assert(
      hex(false) == "80" &&
        hex(true) == "01" &&
        decode[Boolean](encode(true)) &&
        !decode[Boolean](encode(false)),
      "Boolean must encode as 0x80/0x01 and round-trip"
    )

  test("round-trip: Option (empty list vs single-element list)"):
    assert(
      Hex.toHexString(encode(Option.empty[Int])) == "c0" &&
        decode[Option[Int]](encode(Option(42))).contains(42) &&
        decode[Option[Int]](encode(Option.empty[Int])).isEmpty,
      "Option must encode as an empty list vs a single-element list and round-trip"
    )

  test("round-trip: Seq / List"):
    val xs = Seq(1, 2, 3, 100000)
    val ys = List("a", "bb", "ccc")
    assert(
      decode[Seq[Int]](encode(xs)) == xs && decode[List[String]](encode(ys)) == ys,
      "Seq / List must round-trip through RLP"
    )

  test("round-trip: tuples 2..5"):
    assert(
      decode[(Int, String)](encode((1, "x"))) == (1, "x") &&
        decode[(Int, String, Boolean)](encode((1, "x", true))) == (1, "x", true) &&
        decode[(Int, Int, Int, Int)](encode((1, 2, 3, 4))) == (1, 2, 3, 4) &&
        decode[(Int, Int, Int, Int, Int)](encode((1, 2, 3, 4, 5))) == (1, 2, 3, 4, 5),
      "tuples of arity 2 through 5 must round-trip through RLP"
    )

  // --- EIP-2718 typed envelope --------------------------------------------

  test("PrefixedRLPEncodable serializes as `prefix || encode(item)` (EIP-2718)"):
    // a typed transaction/receipt: type byte 0x02 followed by the RLP payload, NOT wrapped in a string
    val payload = RLPList(RLPEncoder.encode(1), RLPEncoder.encode("data"))
    val envelope = PrefixedRLPEncodable(0x02, payload)
    assert(encode(envelope).sameElements(Array[Byte](0x02) ++ encode(payload)))

  test("PrefixedRLPEncodable rejects a prefix outside [0x00, 0x7f]"):
    intercept[IllegalArgumentException](PrefixedRLPEncodable(-1, RLPList()))

  // --- decode failure modes -----------------------------------------------

  test("decoding empty data fails"):
    intercept[RLPException](decode[Array[Byte]](Array.empty[Byte]))

  test("decoding an RLPList where a scalar is expected fails"):
    val listBytes = encode(RLPList(RLPEncoder.encode("cat")))
    val _ = intercept[RLPException](decode[Int](listBytes))
    intercept[RLPException](decode[Byte](listBytes))

  test("decoding an oversized scalar into a fixed-width type fails"):
    val big = encode(BigInt(2).pow(72))
    val _ = intercept[RLPException](decode[Int](big))
    intercept[RLPException](decode[Long](big))
