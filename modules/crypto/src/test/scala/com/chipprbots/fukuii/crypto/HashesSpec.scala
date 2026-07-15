package com.chipprbots.fukuii.crypto

import java.nio.charset.StandardCharsets

import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite

/** SHA-256 (FIPS-180) and RIPEMD-160 known-answer vectors — the precompile digests at `0x02`/`0x03`. RIPEMD-160 vectors
  * are from Bosselaers' reference test suite.
  */
class HashesSpec extends AnyFunSuite:

  private def ascii(s: String): Array[Byte] = s.getBytes(StandardCharsets.US_ASCII)
  private def hex(b: Array[Byte]): String = Hex.toHexString(b)

  test("sha256 matches FIPS-180 vectors"):
    assert(hex(sha256(Array.emptyByteArray)) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    assert(hex(sha256(ascii("abc"))) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assert(
      hex(sha256(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))) ==
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    )

  test("ripemd160 matches Bosselaers reference vectors"):
    val examples = Seq(
      "" -> "9c1185a5c5e9fc54612808977ee8f548b2258d31",
      "a" -> "0bdc9d2d256b3ee9daae347be6f4dc835a467ffe",
      "abc" -> "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc",
      "message digest" -> "5d0689ef49d2fae572b881b123a85ffa21595f36",
      "abcdefghijklmnopqrstuvwxyz" -> "f71c27109c692c1b56bbdceb5b9d2865b3708dbc",
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" -> "b0e20b6e3116640286ed3a87a5713079b21f5189",
      ("1234567890" * 8) -> "9b752e45573d4b39f4dbd3323cab82bf63326bfb"
    )
    examples.foreach { case (in, expected) =>
      assert(hex(ripemd160(ascii(in))) == expected)
    }
