package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.uint256Codec

/** The accumulated proof-of-work total difficulty — Σ `header.difficulty` over every block from genesis to a given
  * block. This is the pure fork-choice weight quantity: PoW fork choice imports the heavier chain, so this is the value
  * two competing branches are compared on (and the value MESS/ECIP-1100 scores against, at L5).
  *
  * Type-distinct opaque wrapper over [[UInt256]] (32-byte-bounded), mirroring [[Wei]]'s pattern. The JVM structural kin
  * is besu-etc's `Difficulty extends BaseUInt256Value<Difficulty> implements Quantity`
  * (`ethereum/core/.../Difficulty.java`) — the ETC JVM authority uses a 256-bit-bounded quantity for both a block's own
  * difficulty and the accumulated total; nethermind (`UInt256`), erigon (`uint256.Int`) and reth (`U256`) all agree on
  * the 256-bit bound (go-ethereum/core-geth carry it as a raw `*big.Int`, the same value with an unbounded
  * representation). Accumulated ETC total difficulty is far below `2^256`, so the bound is never a truncation risk. The
  * consensus-relevant contract is the **byte shape**: RLP-encoded as a minimal-length unsigned big-endian scalar (the
  * same rule go-ethereum's stored `td`, core-geth's `WriteTd`, and besu's `writeUInt256Scalar` all use), which
  * [[uint256Codec]] reproduces exactly — the value round-trips byte-identically for the L2 `chain-weight` CF.
  *
  * This is the pure Σ-difficulty value only. The richer MESS/ECIP-1100 chain-weight wrapper (total difficulty paired
  * with the last-checkpoint number) is a later L5 concept layered on top of this quantity, not modelled here.
  */
opaque type TotalDifficulty = UInt256

object TotalDifficulty:

  /** The genesis-relative starting weight (an empty chain has zero accumulated difficulty). */
  val Zero: TotalDifficulty = UInt256.Zero

  def apply(u: UInt256): TotalDifficulty = u

  /** Convenience constructor from a non-negative `BigInt` (e.g. a `header.difficulty`-typed weight); rejects
    * out-of-range input via [[UInt256.apply]]. Named rather than an `apply` overload because `UInt256` and `BigInt`
    * erase to the same signature (the reason [[Wei]] exposes only its `UInt256` constructor).
    */
  def fromBigInt(n: BigInt): TotalDifficulty = UInt256(n)

  /** Heavier-chain ordering — fork choice selects the greater total difficulty (besu-etc `Difficulty` is `Comparable`).
    * Built via [[Ordering.by]] over the `BigInt` view rather than `summon[Ordering[UInt256]]`: inside this file
    * `TotalDifficulty` and `UInt256` are the same type by opaque transparency, so an implicit search for
    * `Ordering[UInt256]` here would also match this very given and loop (the same trap [[Wei]]'s given avoids).
    */
  given Ordering[TotalDifficulty] = Ordering.by(td => UInt256.toBigInt(td))

  /** RLP scalar codec, delegating to [[UInt256]]'s minimal-length big-endian scalar encoding — a total-difficulty
    * weight is a quantity, not a fixed-width byte string. Built via `xmap` over the named `uint256Codec` (not `summon`)
    * for the same opaque-transparency looping reason as the `Ordering` given above.
    */
  given RLPCodec[TotalDifficulty] = uint256Codec.xmap(u => TotalDifficulty(u), _.toUInt256)

  // `UInt256.bytes(td)` / `UInt256.toBigInt(td)` below call the compiler-generated extension methods by qualified name
  // (they are accessible as regular methods of the `UInt256` object) rather than `(td: UInt256).bytes` — inside this
  // file `TotalDifficulty` and `UInt256` are the same type by opaque transparency, so extension-method syntax on `td`
  // for a name this block also defines (`toUInt256`/`bytes`) would resolve to *this* extension (self-recursion).
  extension (td: TotalDifficulty)
    def toUInt256: UInt256 = td
    def toBigInt: BigInt = UInt256.toBigInt(td)
    def bytes: ByteString = UInt256.bytes(td)

    /** Accumulate one more block's difficulty onto the running weight — `Σ + header.difficulty` (besu-etc
      * `parentTotalDifficulty.add(header.getDifficulty())`). The header's `difficulty` is an always-present,
      * consensus-critical `BigInt` (never a trailing-optional), and is always below `2^256`.
      */
    def increase(header: BlockHeader): TotalDifficulty = td + UInt256(header.difficulty)

    /** Sum two weights (`Difficulty.add`) — used where a range's accumulated difficulty is combined. */
    def add(other: TotalDifficulty): TotalDifficulty = td + other.toUInt256
