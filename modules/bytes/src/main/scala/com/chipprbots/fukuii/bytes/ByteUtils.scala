package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString

/** Big-endian ↔ `BigInt` conversion, fixed-width padding, and bitwise byte-array plumbing.
  *
  * These are the genuinely-generic byte operations the layers above depend on (the fixed-width value types here, plus
  * `crypto`/`rlp`/`trie`). Ethereum treats all multi-byte integer material as unsigned big-endian, so every conversion
  * below is unsigned — matching go-ethereum's `common.BigToBytes`/`common.BytesToBig` and `math/big` usage. Hot paths
  * keep `while`/`var` for allocation and bounds-check economy; that is a performance idiom, not a Scala-2 idiom.
  */
object ByteUtils:

  /** Number of leading bytes shared by both arrays (used by the trie for key prefixes). */
  def matchingLength(a: Array[Byte], b: Array[Byte]): Int =
    var i = 0
    while i < a.length && i < b.length && a(i) == b(i) do i += 1
    i

  /** Unsigned big-endian `BigInt` from a byte-string. Empty ⇒ `0`. */
  def toBigInt(bytes: ByteString): BigInt =
    bytesToBigInt(bytes.toArray)

  /** Unsigned big-endian `BigInt` from a byte-array. Empty ⇒ `0`. */
  def bytesToBigInt(bytes: Array[Byte]): BigInt =
    if bytes.isEmpty then BigInt(0) else BigInt(1, bytes)

  /** Fixed-width, right-aligned big-endian bytes of a non-negative `BigInt`.
    *
    * The value is placed in the low-order end of a `numBytes`-length array (zero-padded on the left); a value wider
    * than `numBytes` keeps its least-significant `numBytes` bytes. Mirrors go-ethereum `math.PaddedBigBytes` /
    * `common.LeftPadBytes`.
    */
  def bigIntToBytes(b: BigInt, numBytes: Int): Array[Byte] =
    require(b >= 0, s"Cannot big-endian-encode a negative BigInt: $b")
    val out = new Array[Byte](numBytes)
    // BigInt.toByteArray is signed two's-complement big-endian; a positive value with its top bit
    // set carries a leading 0x00 sign byte. takeRight(numBytes) drops it and any excess high bytes.
    val src = b.toByteArray
    val len = math.min(src.length, numBytes)
    System.arraycopy(src, src.length - len, out, numBytes - len, len)
    out

  /** Minimal-length unsigned big-endian bytes (no leading zero / sign byte). `0` ⇒ empty array — the canonical RLP
    * scalar form. Mirrors go-ethereum `math/big.Int.Bytes`.
    */
  def bigIntToUnsignedBytes(b: BigInt): Array[Byte] =
    require(b >= 0, s"Cannot big-endian-encode a negative BigInt: $b")
    if b == 0 then Array.emptyByteArray
    else
      val src = b.toByteArray
      if src(0) == 0 then src.tail else src

  /** Left-pad (prepend) `bytes` with `byte` up to `length`; a no-op if already at least that long. */
  def padLeft(bytes: ByteString, length: Int, byte: Byte = 0): ByteString =
    val fill = math.max(0, length - bytes.length)
    if fill == 0 then bytes
    else ByteString(Array.fill[Byte](fill)(byte)) ++ bytes

  /** Element-wise XOR of two equal-length arrays. */
  def xor(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    require(a.length == b.length, s"xor operands differ in length: ${a.length} vs ${b.length}")
    val out = new Array[Byte](a.length)
    var i = 0
    while i < a.length do
      out(i) = (a(i) ^ b(i)).toByte
      i += 1
    out

  /** Element-wise OR of one or more equal-length arrays. */
  def or(arrays: Array[Byte]*): Array[Byte] =
    reduceBitwise(arrays, 0.toByte)((x, y) => (x | y).toByte)

  /** Element-wise AND of one or more equal-length arrays. */
  def and(arrays: Array[Byte]*): Array[Byte] =
    reduceBitwise(arrays, 0xff.toByte)((x, y) => (x & y).toByte)

  private def reduceBitwise(arrays: Seq[Array[Byte]], identity: Byte)(op: (Byte, Byte) => Byte): Array[Byte] =
    require(arrays.nonEmpty, "There should be one or more arrays")
    val len = arrays.head.length
    require(arrays.forall(_.length == len), "All the arrays should have the same length")
    val out = Array.fill[Byte](len)(identity)
    arrays.foreach { arr =>
      var i = 0
      while i < len do
        out(i) = op(out(i), arr(i))
        i += 1
    }
    out
