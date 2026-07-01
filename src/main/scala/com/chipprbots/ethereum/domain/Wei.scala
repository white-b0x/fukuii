package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type Wei = BigInt

object Wei:
  val Zero: Wei = BigInt(0)

  def apply(v: BigInt): Wei = v
  def apply(v: Long): Wei = BigInt(v)
  def apply(v: Int): Wei = BigInt(v)

  extension (w: Wei)
    def value: BigInt = w
    def +(other: Wei): Wei = w + other
    def -(other: Wei): Wei = w - other
    def *(n: BigInt): Wei = w * n
    def *(n: Long): Wei = w * n
    def /(n: BigInt): Wei = w / n
    def /(n: Long): Wei = w / n
    def compare(other: Wei): Int = w.compare(other)
    def >(other: Wei): Boolean = w > other
    def >=(other: Wei): Boolean = w >= other
    def <(other: Wei): Boolean = w < other
    def <=(other: Wei): Boolean = w <= other
    def min(other: Wei): Wei = if w < other then w else other
    def max(other: Wei): Wei = if w > other then w else other
    def toEther: BigDecimal = BigDecimal(w) / BigDecimal("1000000000000000000")

  given rlpCodec: RLPCodec[Wei] = bigIntEncDec.xmap((v: BigInt) => Wei(v), _.value)
  // Pin the BigInt ordering explicitly — inside this object Wei =:= BigInt, so the
  // implicit Ordering parameter of bare Ordering.by(_.value) resolves to the given
  // being defined → self-referential lazy-val init deadlock.
  given Ordering[Wei] = Ordering.by[Wei, BigInt](_.value)(using scala.math.Ordering.BigInt)
