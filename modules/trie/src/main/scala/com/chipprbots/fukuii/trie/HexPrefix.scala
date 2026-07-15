package com.chipprbots.fukuii.trie

/** Hex-prefix (compact) nibble encoding — the 2-item-node key packing.
  *
  * Byte-identical to go-ethereum `trie/encoding.go` `hexToCompact`/`compactToHex` and besu `CompactEncoding`. The flag
  * byte occupies the high nibble of `buf[0]`:
  *   - bit 5 (`0x20`) — terminator/leaf flag (set ⇒ leaf, clear ⇒ extension)
  *   - bit 4 (`0x10`) — odd-length flag
  *
  * so the high-nibble states are `0x0` ext-even, `0x1` ext-odd, `0x2` leaf-even, `0x3` leaf-odd. For an odd-length key
  * the first nibble is packed into the low nibble of `buf[0]`; for an even-length key the low nibble of `buf[0]` is a
  * zero pad. Consensus-load-bearing: this is a state-root preimage input.
  */
object HexPrefix:

  /** Pack a nibble array (one nibble per byte, values `0..15`) into compact bytes, tagging leaf vs extension. */
  def encode(nibbles: Array[Byte], isLeaf: Boolean): Array[Byte] =
    val hasOddLength = nibbles.length % 2 == 1
    val firstByteFlag: Byte = (2 * (if isLeaf then 1 else 0) + (if hasOddLength then 1 else 0)).toByte
    val lengthFlag = if hasOddLength then 1 else 2

    val nibblesWithFlag = new Array[Byte](nibbles.length + lengthFlag)
    Array.copy(nibbles, 0, nibblesWithFlag, lengthFlag, nibbles.length)
    nibblesWithFlag(0) = firstByteFlag
    if !hasOddLength then nibblesWithFlag(1) = 0
    nibblesToBytes(nibblesWithFlag)

  /** Unpack compact bytes back to `(nibbles, isLeaf)`. Assumes a well-formed flag byte; the node decoder
    * ([[MptNode.decode]]) validates the flag bits before calling this so adversarial input fails loud upstream.
    */
  def decode(src: Array[Byte]): (Array[Byte], Boolean) =
    val srcNibbles: Array[Byte] = bytesToNibbles(src)
    val t = (srcNibbles(0) & 2) != 0
    val hasOddLength = (srcNibbles(0) & 1) != 0
    val flagLength = if hasOddLength then 1 else 2

    val res = new Array[Byte](srcNibbles.length - flagLength)
    Array.copy(srcNibbles, flagLength, res, 0, srcNibbles.length - flagLength)
    (res, t)

  /** Split each byte into its two 4-bit nibbles (high nibble first). */
  def bytesToNibbles(bytes: Array[Byte]): Array[Byte] =
    val newArray = new Array[Byte](bytes.length * 2)
    var i = 0
    var n = 0
    while i < bytes.length do
      newArray(n) = ((bytes(i) >> 4) & 0xf).toByte
      newArray(n + 1) = (bytes(i) & 0xf).toByte
      n = n + 2
      i = i + 1
    newArray

  /** Combine pairs of nibbles back into bytes. Requires an even nibble count. */
  def nibblesToBytes(nibbles: Array[Byte]): Array[Byte] =
    require(nibbles.length % 2 == 0)
    val newArray = new Array[Byte](nibbles.length / 2)
    var i = 0
    var n = 0
    while i < nibbles.length do
      val newValue = (16 * nibbles(i) + nibbles(i + 1)).toByte
      newArray(n) = newValue
      n = n + 1
      i = i + 2
    newArray
