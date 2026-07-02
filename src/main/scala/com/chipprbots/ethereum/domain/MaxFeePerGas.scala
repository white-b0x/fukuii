package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type MaxFeePerGas = BigInt

object MaxFeePerGas:
  val Zero: MaxFeePerGas = MaxFeePerGas(BigInt(0))
  def apply(v: BigInt): MaxFeePerGas = v
  def apply(v: Long): MaxFeePerGas = BigInt(v)
  def apply(v: Int): MaxFeePerGas = BigInt(v)

  extension (p: MaxFeePerGas)
    def value: BigInt = p
    def toLong: Long = p.toLong
    def +(other: MaxFeePerGas): MaxFeePerGas = p + other
    def *(n: Long): MaxFeePerGas = p * n
    def *(n: BigInt): MaxFeePerGas = p * n
    def /(n: Long): MaxFeePerGas = p / n
    def compare(other: MaxFeePerGas): Int = p.compare(other)
    def >(other: MaxFeePerGas): Boolean = p > other
    def >=(other: MaxFeePerGas): Boolean = p >= other
    def <(other: MaxFeePerGas): Boolean = p < other
    def <=(other: MaxFeePerGas): Boolean = p <= other
    def min(other: MaxFeePerGas): MaxFeePerGas = if p < other then p else other
    def max(other: MaxFeePerGas): MaxFeePerGas = if p > other then p else other

  given rlpCodec: RLPCodec[MaxFeePerGas] = bigIntEncDec.xmap((v: BigInt) => MaxFeePerGas(v), _.value)
  given Ordering[MaxFeePerGas] = Ordering.by[MaxFeePerGas, BigInt](_.value)(using scala.math.Ordering.BigInt)
