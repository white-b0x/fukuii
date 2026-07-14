package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString

/** A 32-byte value — a keccak-256 digest, a block/tx hash, an MPT node hash, or any fixed 32-byte word.
  *
  * Type-distinct opaque wrapper over its backing 32 bytes; a `Hash` cannot be silently mixed with an
  * [[Address]]. The primary constructor [[apply]] is **strict**: it requires exactly 32 bytes and
  * fails loud otherwise. The lenient geth `SetBytes` reshaping is the explicitly-named
  * [[fromBytesTruncating]]. [[Bytes32]] is a transparent alias for the same type where the 32-byte
  * width matters more than the "hash" role (e.g. an EVM/storage word as bytes).
  */
opaque type Hash = ByteString

object Hash:

  /** Byte length of a hash (32). */
  val Length: Int = 32

  /** The all-zero hash. */
  val Zero: Hash = ByteString(Array.fill[Byte](Length)(0))

  /** Wrap **exactly** 32 bytes; throws on any other length. Use [[fromBytesTruncating]] for the
    * parse/derive sites that need geth's lenient reshaping.
    */
  def apply(bytes: ByteString): Hash =
    require(bytes.length == Length, s"Hash must be exactly $Length bytes, got ${bytes.length}")
    bytes

  def apply(bytes: Array[Byte]): Hash = apply(ByteString(bytes))

  /** Right-align raw bytes into exactly 32 bytes, matching go-ethereum `common.Hash.SetBytes`
    * (`common/types.go`) byte-for-byte: a longer input keeps its rightmost 32 bytes, a shorter one is
    * left-padded with zeros — matching `BytesToHash`. For boundary/parse sites only.
    */
  def fromBytesTruncating(bytes: ByteString): Hash =
    val len = bytes.length
    if len == Length then bytes
    else if len > Length then bytes.takeRight(Length)
    else ByteString(Array.fill[Byte](Length - len)(0)) ++ bytes

  def fromBytesTruncating(bytes: Array[Byte]): Hash = fromBytesTruncating(ByteString(bytes))

  /** Parse from hex, tolerating a `0x` prefix; must decode to at most 32 bytes. A shorter value is
    * left-padded (matching `BytesToHash`); an oversized value is rejected fail-loud.
    */
  def fromHex(hex: String): Hash =
    val bytes = Hex.decode(hex)
    require(bytes.length <= Length, s"Hash hex too long: ${bytes.length} > $Length bytes")
    fromBytesTruncating(ByteString(bytes))

  given Ordering[Hash] = ByteStringOps.byteStringOrdering

  extension (hash: Hash)
    def bytes: ByteString = hash
    def toArray: Array[Byte] = hash.toArray

    /** Lower-case unprefixed hex (64 chars). */
    def toHex: String = Hex.toHexString(hash.toArray)

    /** `0x`-prefixed lower-case hex (66 chars). */
    def toPrefixedHex: String = "0x" + Hex.toHexString(hash.toArray)

/** A 32-byte word by width rather than by hash-role. Same underlying type as [[Hash]]. */
type Bytes32 = Hash
