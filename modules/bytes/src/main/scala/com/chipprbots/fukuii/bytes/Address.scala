package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString

/** A 20-byte Ethereum account address.
  *
  * Type-distinct opaque wrapper over its backing 20 bytes — an `Address` cannot be silently mixed with a [[Hash]] even
  * though both wrap `ByteString`. The primary constructor [[apply]] is **strict**: it requires exactly 20 bytes and
  * fails loud otherwise. The lenient geth `SetBytes` reshaping (right-align/left-pad/truncate) is the explicitly-named
  * [[fromBytesTruncating]], for the boundary sites that need it.
  */
opaque type Address = ByteString

object Address:

  /** Byte length of an address (20). */
  val Length: Int = 20

  /** The all-zero address. */
  val Zero: Address = ByteString(Array.fill[Byte](Length)(0))

  /** Wrap **exactly** 20 bytes; throws on any other length. Use [[fromBytesTruncating]] for the parse/derive sites that
    * need geth's lenient reshaping.
    */
  def apply(bytes: ByteString): Address =
    require(bytes.length == Length, s"Address must be exactly $Length bytes, got ${bytes.length}")
    bytes

  def apply(bytes: Array[Byte]): Address = apply(ByteString(bytes))

  /** Right-align raw bytes into exactly 20 bytes, matching go-ethereum `common.Address.SetBytes` (`common/types.go`)
    * byte-for-byte: a longer input keeps its rightmost 20 bytes, a shorter one is left-padded with zeros. For boundary
    * sites only — RLP-decoding an address field, or deriving an address from the low 20 bytes of a 32-byte word
    * (CREATE/CREATE2, `ecrecover`, `BytesToAddress`).
    */
  def fromBytesTruncating(bytes: ByteString): Address =
    val len = bytes.length
    if len == Length then bytes
    else if len > Length then bytes.takeRight(Length)
    else ByteString(Array.fill[Byte](Length - len)(0)) ++ bytes

  def fromBytesTruncating(bytes: Array[Byte]): Address = fromBytesTruncating(ByteString(bytes))

  /** Low 20 bytes of a 256-bit word — how the EVM treats an address held in a `UInt256`. */
  def apply(word: UInt256): Address = fromBytesTruncating(word.bytes)

  /** Parse from hex, tolerating a `0x` prefix; must decode to at most 20 bytes. A shorter value is left-padded,
    * matching go-ethereum `HexToAddress`; an oversized value is rejected fail-loud.
    */
  def fromHex(hex: String): Address =
    val bytes = Hex.decode(hex)
    require(bytes.length <= Length, s"Address hex too long: ${bytes.length} > $Length bytes")
    fromBytesTruncating(ByteString(bytes))

  given Ordering[Address] = ByteStringOps.byteStringOrdering

  extension (addr: Address)
    def bytes: ByteString = addr
    def toArray: Array[Byte] = addr.toArray
    def toUInt256: UInt256 = UInt256.fromBytes(addr)

    /** Lower-case unprefixed hex (40 chars). */
    def toHex: String = Hex.toHexString(addr.toArray)

    /** `0x`-prefixed lower-case hex (42 chars) — the canonical string form. */
    def toPrefixedHex: String = "0x" + Hex.toHexString(addr.toArray)
