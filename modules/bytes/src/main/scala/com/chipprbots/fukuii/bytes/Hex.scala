package com.chipprbots.fukuii.bytes

/** Hexadecimal encoding/decoding for raw bytes.
  *
  * Decoding mirrors go-ethereum `hexutil` behaviour (`common/hexutil/hex.go`): an optional `0x`/`0X` prefix is
  * accepted, an odd number of nibbles is rejected (go-ethereum returns `ErrOddLength`), and a non-hex character is
  * rejected.
  */
object Hex:

  private val hexChars: Array[Char] = "0123456789abcdef".toCharArray

  /** Lower-case, unprefixed hex; exactly two characters per input byte. */
  def toHexString(bytes: Array[Byte]): String =
    val sb = new java.lang.StringBuilder(bytes.length * 2)
    var i = 0
    while i < bytes.length do
      val b = bytes(i) & 0xff
      sb.append(hexChars(b >>> 4))
      sb.append(hexChars(b & 0x0f))
      i += 1
    sb.toString

  /** Decode a hex string, tolerating an optional `0x`/`0X` prefix.
    *
    * @throws IllegalArgumentException
    *   on an odd number of nibbles or a non-hex character.
    */
  def decode(hex: String): Array[Byte] =
    val s = stripPrefix(hex)
    require((s.length & 1) == 0, s"Invalid hex string, odd length: ${s.length}")
    val out = new Array[Byte](s.length / 2)
    var i = 0
    while i < out.length do
      val hi = nibble(s.charAt(2 * i))
      val lo = nibble(s.charAt(2 * i + 1))
      out(i) = ((hi << 4) | lo).toByte
      i += 1
    out

  private def stripPrefix(hex: String): String =
    if hex.length >= 2 && hex.charAt(0) == '0' && (hex.charAt(1) == 'x' || hex.charAt(1) == 'X') then hex.substring(2)
    else hex

  private def nibble(c: Char): Int =
    if c >= '0' && c <= '9' then c - '0'
    else if c >= 'a' && c <= 'f' then c - 'a' + 10
    else if c >= 'A' && c <= 'F' then c - 'A' + 10
    else throw new IllegalArgumentException(s"Invalid hex character: '$c'")
