package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString

/** A 256-bit unsigned integer — the EVM word and the storage-slot value type.
  *
  * Type-distinct opaque wrapper over `BigInt`, bounded to `[0, 2^256)`. go-ethereum represents this
  * as a 4×`uint64` `uint256.Int`; the consensus-relevant contract is not the internal representation
  * but the **byte-level** one: the canonical form is 32-byte big-endian, zero-padded on the left
  * (what feeds state roots and RLP), which [[UInt256.bytes]] reproduces exactly. `BigInt` gives correct
  * unsigned numeric semantics here; the full wrapping EVM arithmetic (add/mul/etc. mod 2^256) is
  * deferred to the `evm` layer that actually needs it — this module owns only construction, the byte
  * form, equality and ordering.
  */
opaque type UInt256 = BigInt

object UInt256:

  /** Byte width of the canonical representation (32). */
  val Size: Int = 32

  private val Modulus: BigInt = BigInt(2).pow(Size * 8)

  val Zero: UInt256 = BigInt(0)
  val One: UInt256 = BigInt(1)
  val MaxValue: UInt256 = Modulus - 1

  /** Construct from a `BigInt`, requiring it in range `[0, 2^256)`. Out-of-range input is a bug at
    * this layer (there is no wrapping arithmetic here to justify silent reduction), so it is rejected.
    */
  def apply(n: BigInt): UInt256 =
    require(n >= 0 && n < Modulus, s"UInt256 out of range: $n")
    n

  /** Construct from a `Long`; a negative value is rejected (see [[apply(BigInt)]]). */
  def apply(n: Long): UInt256 = apply(BigInt(n))

  /** Unsigned big-endian bytes, at most 32 long. Empty ⇒ [[Zero]]. */
  def fromBytes(bytes: ByteString): UInt256 =
    require(bytes.length <= Size, s"UInt256 input too long: ${bytes.length} > $Size bytes")
    ByteUtils.toBigInt(bytes)

  /** Unsigned big-endian bytes, at most 32 long. Empty ⇒ [[Zero]]. */
  def fromBytes(bytes: Array[Byte]): UInt256 = fromBytes(ByteString(bytes))

  /** Parse from hex (optional `0x` prefix); the decoded value must be at most 32 bytes. */
  def fromHex(hex: String): UInt256 = fromBytes(Hex.decode(hex))

  // Ordering[BigInt] IS Ordering[UInt256] inside this file (the alias is transparent here).
  given Ordering[UInt256] = math.Ordering.BigInt

  extension (x: UInt256)
    def toBigInt: BigInt = x

    /** 32-byte big-endian, zero-padded on the left — the state/storage/RLP-fixed-width form. */
    def bytes: ByteString = ByteString(ByteUtils.bigIntToBytes(x, Size))

    /** Lower-case hex of the 32-byte form. */
    def toHex: String = Hex.toHexString(ByteUtils.bigIntToBytes(x, Size))

    def isZero: Boolean = x == Zero
