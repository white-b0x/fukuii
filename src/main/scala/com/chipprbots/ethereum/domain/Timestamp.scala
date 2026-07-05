package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.longEncDec

opaque type Timestamp = Long

object Timestamp:
  val Zero: Timestamp = 0L
  val MaxValue: Timestamp = Long.MaxValue

  def apply(v: Long): Timestamp = v
  def apply(v: Int): Timestamp = v.toLong

  extension (t: Timestamp)
    def toLong: Long = t
    def underlying: Long = t
    def -(other: Timestamp): Long = t - other
    def +(delta: Long): Timestamp = t + delta
    def +(delta: Int): Timestamp = t + delta.toLong
    def >(other: Timestamp): Boolean = t > other
    def >=(other: Timestamp): Boolean = t >= other
    def <(other: Timestamp): Boolean = t < other
    def <=(other: Timestamp): Boolean = t <= other
    def min(other: Timestamp): Timestamp = if t < other then t else other
    def max(other: Timestamp): Timestamp = if t > other then t else other
    // Must call the static java.lang.Long.toHexString, NOT `t.toHexString`:
    // `t: Timestamp` is opaque (no `.toHexString` member on the underlying Long),
    // so `t.toHexString` re-binds to THIS extension -> infinite recursion / runtime
    // hang on every caller (eth_subscribe newHeads, Engine API block JSON).
    def toHexString: String = java.lang.Long.toHexString(t)

  given rlpCodec: RLPCodec[Timestamp] = longEncDec.xmap((v: Long) => Timestamp(v), _.toLong)
  // Pin the Long ordering explicitly — same opaque-type-in-scope footgun as the BigInt-backed
  // domain types (Nonce/Wei/TotalDifficulty/ChainId/etc.): inside this object `Timestamp =:=
  // Long`, so the implicit `Ordering` argument of a bare `Ordering.by(_.toLong)` can resolve to
  // the given being defined here → self-referential lazy-val init deadlock the moment it's
  // summoned. See OpaqueOrderingResolutionSpec.
  given Ordering[Timestamp] = Ordering.by[Timestamp, Long](_.toLong)(using scala.math.Ordering.Long)
