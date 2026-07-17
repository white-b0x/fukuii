package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString

/** Scala 3 `extension`-based helpers over Pekko `ByteString`, plus its canonical unsigned ordering.
  *
  * `import ByteStringOps.given` brings the ordering into scope; `import ByteStringOps.*` brings the extension methods
  * in.
  */
object ByteStringOps:

  extension (bytes: ByteString)
    /** Lower-case unprefixed hex. */
    def toHex: String = Hex.toHexString(bytes.toArray)

    /** `0x`-prefixed lower-case hex. */
    def toPrefixedHex: String = "0x" + Hex.toHexString(bytes.toArray)

    /** Right-pad (append) with `byte` up to `length`; a no-op if already at least that long. */
    def padRight(length: Int, byte: Byte): ByteString =
      if length <= bytes.length then bytes
      else bytes ++ ByteString(Array.fill[Byte](length - bytes.length)(byte))

    /** Left-pad (prepend) with `byte` up to `length`; a no-op if already at least that long. */
    def padLeft(length: Int, byte: Byte): ByteString =
      if length <= bytes.length then bytes
      else ByteString(Array.fill[Byte](length - bytes.length)(byte)) ++ bytes

  /** Lexicographic ordering treating each byte as **unsigned** (0..255).
    *
    * Ethereum orders byte material unsigned (address sorting in access lists, trie key nibbles, withdrawal ordering),
    * so `0xff` sorts after `0x01`. Scala's default `Ordering[Seq[Byte]]` compares bytes *signed* (`0xff` = -1 sorts
    * first) — a latent consensus footgun this avoids.
    */
  given byteStringOrdering: Ordering[ByteString] with
    def compare(a: ByteString, b: ByteString): Int =
      val min = math.min(a.length, b.length)
      var i = 0
      var result = 0
      while result == 0 && i < min do
        result = (a(i) & 0xff) - (b(i) & 0xff)
        i += 1
      if result != 0 then result else a.length - b.length
