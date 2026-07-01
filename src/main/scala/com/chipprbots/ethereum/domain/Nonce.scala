package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type Nonce = BigInt

object Nonce:
  val Zero: Nonce = BigInt(0)
  def apply(v: BigInt): Nonce = v
  def apply(v: Long): Nonce = BigInt(v)
  def apply(v: Int): Nonce = BigInt(v)

  extension (n: Nonce)
    def value: BigInt = n
    def increment: Nonce = n + BigInt(1)
    def >(other: Nonce): Boolean = n > other
    def >=(other: Nonce): Boolean = n >= other
    def <(other: Nonce): Boolean = n < other
    def <=(other: Nonce): Boolean = n <= other

  given rlpCodec: RLPCodec[Nonce] = bigIntEncDec.xmap((v: BigInt) => Nonce(v), _.value)
  // Pin the BigInt ordering explicitly — avoids self-referential lazy-val init deadlock
  // that occurs when Ordering.by(_.value) resolves the implicit to the given being defined.
  given Ordering[Nonce] = Ordering.by[Nonce, BigInt](_.value)(using scala.math.Ordering.BigInt)
