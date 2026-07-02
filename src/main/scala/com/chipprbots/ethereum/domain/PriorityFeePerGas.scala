package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type PriorityFeePerGas = BigInt

object PriorityFeePerGas:
  val Zero: PriorityFeePerGas = PriorityFeePerGas(BigInt(0))
  def apply(v: BigInt): PriorityFeePerGas = v
  def apply(v: Long): PriorityFeePerGas = BigInt(v)
  def apply(v: Int): PriorityFeePerGas = BigInt(v)

  extension (p: PriorityFeePerGas)
    def value: BigInt = p
    def toLong: Long = p.toLong
    def +(other: PriorityFeePerGas): PriorityFeePerGas = p + other
    def *(n: Long): PriorityFeePerGas = p * n
    def *(n: BigInt): PriorityFeePerGas = p * n
    def /(n: Long): PriorityFeePerGas = p / n
    def compare(other: PriorityFeePerGas): Int = p.compare(other)
    def >(other: PriorityFeePerGas): Boolean = p > other
    def >=(other: PriorityFeePerGas): Boolean = p >= other
    def <(other: PriorityFeePerGas): Boolean = p < other
    def <=(other: PriorityFeePerGas): Boolean = p <= other
    def min(other: PriorityFeePerGas): PriorityFeePerGas = if p < other then p else other
    def max(other: PriorityFeePerGas): PriorityFeePerGas = if p > other then p else other

  given rlpCodec: RLPCodec[PriorityFeePerGas] = bigIntEncDec.xmap((v: BigInt) => PriorityFeePerGas(v), _.value)
  given Ordering[PriorityFeePerGas] = Ordering.by[PriorityFeePerGas, BigInt](_.value)(using scala.math.Ordering.BigInt)
