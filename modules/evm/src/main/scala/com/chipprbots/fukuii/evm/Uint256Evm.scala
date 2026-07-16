package com.chipprbots.fukuii.evm

import com.chipprbots.fukuii.bytes.UInt256

/** The **gas-metered EVM opcode word semantics** the L0 [[UInt256]] deliberately does not carry.
  *
  * L0 `bytes/UInt256` holds only the network-neutral core 256-bit modular arithmetic (add/sub/mul/div/mod/pow, bitwise,
  * unsigned compare) — its own scaladoc is explicit that "the gas-metered EVM opcode semantics (EXP gas, SIGNEXTEND,
  * signed SDIV/SMOD/SAR) are deliberately NOT here — those belong to the `evm` layer that meters them." This is that
  * layer: the signed-interpretation opcodes (`SDIV`/`SMOD`/`SLT`/`SGT`/`SAR`), `SIGNEXTEND`, `BYTE`, the modular
  * `ADDMOD`/`MULMOD`, and the EXP-gas `byteSize` — transcribed byte-for-byte from the AS-IS `domain.UInt256` EVM
  * extensions, retyped over the built opaque [[UInt256]].
  *
  * Network-neutral (go-ethereum + besu must agree); no fork gating here.
  */
object Uint256Evm:

  private val Modulus: BigInt = BigInt(2).pow(256)
  private val MaxSignedValue: BigInt = BigInt(2).pow(255) - 1
  private val MaxValueBig: BigInt = Modulus - 1

  /** Two's-complement signed interpretation of a 256-bit word: values above `2^255 - 1` are negative. */
  private def signed(x: UInt256): BigInt =
    val n = x.toBigInt
    if n > MaxSignedValue then n - Modulus else n

  /** Reduce a possibly-negative `BigInt` into `[0, 2^256)` — the signed-result wrap the built `UInt256.apply` (which
    * rejects negatives) does not do for us.
    */
  private def fromSigned(n: BigInt): UInt256 =
    UInt256(((n % Modulus) + Modulus) % Modulus)

  /** `1` if `b`, else `0` — the EVM boolean-result convention (LT/GT/EQ/ISZERO/SLT/SGT push this). */
  def uintOf(b: Boolean): UInt256 = if b then UInt256.One else UInt256.Zero

  extension (x: UInt256)

    /** An `Int` with MSB cleared, thus in `[0, Int.MaxValue]` (AS-IS `UInt256.toInt`). */
    def toInt: Int = x.toBigInt.intValue & Int.MaxValue

    def min(y: UInt256): UInt256 = if x < y then x else y
    def max(y: UInt256): UInt256 = if x > y then x else y

    /** Two's-complement signed value (SAR/SLT/SGT/SDIV/SMOD interpretation). */
    def toSign: BigInt = signed(x)

    /** Signed division, truncating toward zero; `y == 0 ⇒ 0` (EVM `SDIV`). */
    def sdiv(y: UInt256): UInt256 =
      if y.isZero then UInt256.Zero else fromSigned(signed(x) / signed(y))

    /** Signed remainder with the sign of the dividend; `y == 0 ⇒ 0` (EVM `SMOD`). */
    def smod(y: UInt256): UInt256 =
      if y.isZero then UInt256.Zero else fromSigned(signed(x) % signed(y).abs)

    /** `(x + y) mod m`; `m == 0 ⇒ 0` (EVM `ADDMOD`, full-width intermediate). */
    def addmod(y: UInt256, m: UInt256): UInt256 =
      if m.isZero then UInt256.Zero else UInt256((x.toBigInt + y.toBigInt) % m.toBigInt)

    /** `(x * y) mod m`; `m == 0 ⇒ 0` (EVM `MULMOD`, full-width intermediate). */
    def mulmod(y: UInt256, m: UInt256): UInt256 =
      if m.isZero then UInt256.Zero else UInt256((x.toBigInt * y.toBigInt).mod(m.toBigInt))

    /** Signed less-than (EVM `SLT`). */
    def slt(y: UInt256): Boolean = signed(x) < signed(y)

    /** Signed greater-than (EVM `SGT`). */
    def sgt(y: UInt256): Boolean = signed(x) > signed(y)

    /** Arithmetic (sign-extending) right shift by `y` bits — the SAR core (EVM `SAR`), for `y < 256`. */
    def sshift(y: UInt256): UInt256 = fromSigned(signed(x) >> signed(y).toInt)

    /** `SIGNEXTEND` — extend the sign bit of the `x`-th byte of `y`. Here `x` is the byte index, `y` the value. */
    def signExtend(y: UInt256): UInt256 =
      val idx = x.toBigInt
      if idx < 0 || idx > 31 then y
      else
        val i = idx.toInt
        val n = y.toBigInt
        val negative = n.testBit(i * 8 + 7)
        val mask = (BigInt(1) << ((i + 1) * 8)) - 1
        val newN = if negative then n | (MaxValueBig ^ mask) else n & mask
        UInt256(newN)

    /** The `BYTE` opcode — the `x`-th (big-endian, MSB-first) byte of `y` as a word, `0` if `x > 31`. */
    def byteOf(y: UInt256): UInt256 =
      if x.toBigInt > 31 then UInt256.Zero
      else UInt256(y.bytes(x.toBigInt.toInt).toInt & 0xff)

    /** Size in bytes excluding leading zeroes — the EXP gas multiplier (YP Appendix H.1). */
    def byteSize: Int = if x.isZero then 0 else (x.toBigInt.bitLength - 1) / 8 + 1

    /** Saturating add clamped at `2^256 - 1` — the RETURNDATACOPY out-of-bounds guard (AS-IS `fillingAdd`). */
    def fillingAdd(y: UInt256): UInt256 =
      val r = x.toBigInt + y.toBigInt
      if r > MaxValueBig then UInt256.MaxValue else UInt256(r)

/** EIP-7702 delegation-designator (`0xef0100 ‖ address`) parsing — the code-resolution hook `CALL`/`CALLCODE`/
  * `DELEGATECALL`/`STATICCALL` use to warm and charge for the delegation target. Network-neutral EVM behavior; the L1
  * `SetCodeAuthorization` models the *authorization tuple*, this models the *deployed designator* (AS-IS
  * `SetCodeTransaction.parseDelegation`).
  */
object Eip7702:
  import org.apache.pekko.util.ByteString

  import com.chipprbots.fukuii.bytes.Address

  /** EIP-7702 delegation prefix `0xef0100`. */
  val DelegationPrefix: Array[Byte] = Array(0xef.toByte, 0x01.toByte, 0x00.toByte)
  val DelegationCodeLength: Int = 23 // 3 prefix + 20 address

  def isDelegation(code: ByteString): Boolean =
    code.length == DelegationCodeLength && code.startsWith(ByteString(DelegationPrefix))

  def parseDelegation(code: ByteString): Option[Address] =
    if isDelegation(code) then Some(Address(code.drop(3)))
    else None
