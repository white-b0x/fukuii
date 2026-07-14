package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.uint256Codec

/** A wei-denominated quantity — an account balance, a transaction value, a gas price/fee.
  *
  * Type-distinct opaque wrapper over [[UInt256]] (32-byte-bounded), so a `Wei` amount cannot be silently mixed with an
  * arbitrary `UInt256` word (an EVM stack value, a storage slot) even though both wrap the same 256-bit representation.
  * Zero-cost at runtime — the JVM structural mirror is besu's semantic `Wei extends BaseUInt256Value<Wei> implements
  * Quantity` (`datatypes/Wei.java`), a wrapper class over the identical 32-byte width; here the wrapper is erased
  * instead of allocated. `ChainId` is opaque *data* (see [[ChainId]]); `Wei` is opaque *quantity data* — neither is a
  * type-level network family.
  */
opaque type Wei = UInt256

object Wei:

  val Zero: Wei = UInt256.Zero

  def apply(u: UInt256): Wei = u

  /** RLP scalar codec, delegating to [[UInt256]]'s minimal-length big-endian scalar encoding — a `Wei` amount is a
    * quantity, not a fixed-width byte string. Built via `xmap` over the named `uint256Codec` (not `summon`) — inside
    * this file `Wei` and `UInt256` are the same type by opaque transparency, so an implicit search for
    * `RLPCodec[UInt256]` here would also match this very given and loop.
    */
  given RLPCodec[Wei] = uint256Codec.xmap(Wei.apply, _.toUInt256)

  // `UInt256.bytes(w)` / `UInt256.isZero(w)` below call the compiler-generated extension methods by
  // qualified name rather than `(w: UInt256).bytes` — inside this file `Wei` and `UInt256` are the same
  // type by opaque transparency, so extension-method syntax here would resolve to *this* `bytes`/`isZero`
  // (self-recursion) instead of the intended delegate.
  extension (w: Wei)
    def toUInt256: UInt256 = w
    def bytes: ByteString = UInt256.bytes(w)
    def isZero: Boolean = UInt256.isZero(w)
