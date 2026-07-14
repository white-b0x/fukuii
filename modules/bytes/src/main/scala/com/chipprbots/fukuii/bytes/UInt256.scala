package com.chipprbots.fukuii.bytes

import org.apache.pekko.util.ByteString

/** A 256-bit unsigned integer — the EVM word and the storage-slot value type.
  *
  * Type-distinct opaque wrapper over `BigInt`, bounded to `[0, 2^256)`. go-ethereum represents this as a 4×`uint64`
  * `uint256.Int`; the consensus-relevant contract is not the internal representation but the **byte-level** one: the
  * canonical form is 32-byte big-endian, zero-padded on the left (what feeds state roots and RLP), which
  * [[UInt256.bytes]] reproduces exactly. `BigInt` gives correct unsigned numeric semantics here.
  *
  * The type also carries the **core 256-bit modular arithmetic** — add/sub/mul/div/mod/pow (all mod `2^256` wrapping),
  * bitwise and/or/xor/not/shl/shr, and comparison — matching the wrapping semantics of go-ethereum's `holiman/uint256`
  * (`Div`/`Mod` by zero yield `0`; `Exp(x, 0) = 1`). The **gas-metered EVM opcode semantics** (EXP gas, SIGNEXTEND,
  * signed SDIV/SMOD/SAR) are deliberately NOT here — those belong to the `evm` layer that meters them.
  */
opaque type UInt256 = BigInt

object UInt256:

  /** Byte width of the canonical representation (32). */
  val Size: Int = 32

  private val Modulus: BigInt = BigInt(2).pow(Size * 8)

  val Zero: UInt256 = BigInt(0)
  val One: UInt256 = BigInt(1)
  val MaxValue: UInt256 = Modulus - 1

  /** Construct from a `BigInt`, requiring it in range `[0, 2^256)`. Out-of-range input is a bug at this layer (there is
    * no wrapping arithmetic here to justify silent reduction), so it is rejected.
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

  /** Number of bits in the word (256). */
  private val Bits: Int = Size * 8

  /** Reduce a `BigInt` into `[0, 2^256)`. `BigInt.mod` is always non-negative, so this also gives the correct
    * two's-complement wrap for a negative intermediate (e.g. an underflowing subtraction).
    */
  private def wrap(n: BigInt): UInt256 = n.mod(Modulus)

  // Ordering[BigInt] IS Ordering[UInt256] inside this file (the alias is transparent here).
  given Ordering[UInt256] = math.Ordering.BigInt

  extension (x: UInt256)
    def toBigInt: BigInt = x

    /** 32-byte big-endian, zero-padded on the left — the state/storage/RLP-fixed-width form. */
    def bytes: ByteString = ByteString(ByteUtils.bigIntToBytes(x, Size))

    /** Lower-case hex of the 32-byte form. */
    def toHex: String = Hex.toHexString(ByteUtils.bigIntToBytes(x, Size))

    def isZero: Boolean = x == Zero

    // --- core 256-bit modular arithmetic (mod 2^256 wrapping) ---------------

    /** `(x + y) mod 2^256`. */
    def +(y: UInt256): UInt256 = wrap((x: BigInt) + (y: BigInt))

    /** `(x - y) mod 2^256` (wrapping; result stays in `[0, 2^256)`). */
    def -(y: UInt256): UInt256 = wrap((x: BigInt) - (y: BigInt))

    /** `(x * y) mod 2^256`. */
    def *(y: UInt256): UInt256 = wrap((x: BigInt) * (y: BigInt))

    /** Unsigned integer division `x / y`, truncating toward zero. `y == 0 ⇒ 0` (EVM `DIV`). */
    def /(y: UInt256): UInt256 = if (y: BigInt) == BigInt(0) then Zero else (x: BigInt) / (y: BigInt)

    /** Unsigned remainder `x mod y`. `y == 0 ⇒ 0` (EVM `MOD`). */
    def mod(y: UInt256): UInt256 = if (y: BigInt) == BigInt(0) then Zero else (x: BigInt).mod(y: BigInt)

    /** `x ** y mod 2^256`. `x ** 0 = 1` (including `0 ** 0 = 1`), matching `holiman/uint256.Exp`. */
    def pow(y: UInt256): UInt256 = (x: BigInt).modPow(y, Modulus)

    /** Bitwise AND. */
    def &(y: UInt256): UInt256 = (x: BigInt) & (y: BigInt)

    /** Bitwise OR. */
    def |(y: UInt256): UInt256 = (x: BigInt) | (y: BigInt)

    /** Bitwise XOR. */
    def ^(y: UInt256): UInt256 = (x: BigInt) ^ (y: BigInt)

    /** Bitwise complement within 256 bits (`2^256 - 1 - x`). */
    def unary_~ : UInt256 = MaxValue ^ x

    /** Logical left shift by `n` bits, wrapping mod `2^256`. `n >= 256 ⇒ 0`; `n <= 0 ⇒ x`. */
    def shiftLeft(n: Int): UInt256 =
      if n <= 0 then x else if n >= Bits then Zero else wrap((x: BigInt) << n)

    /** Logical right shift by `n` bits (unsigned). `n >= 256 ⇒ 0`; `n <= 0 ⇒ x`. */
    def shiftRight(n: Int): UInt256 =
      if n <= 0 then x else if n >= Bits then Zero else (x: BigInt) >> n

    /** Unsigned comparison. */
    def <(y: UInt256): Boolean = (x: BigInt) < (y: BigInt)
    def <=(y: UInt256): Boolean = (x: BigInt) <= (y: BigInt)
    def >(y: UInt256): Boolean = (x: BigInt) > (y: BigInt)
    def >=(y: UInt256): Boolean = (x: BigInt) >= (y: BigInt)
