package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type BaseFeePerGas = BigInt

object BaseFeePerGas:
  val Zero: BaseFeePerGas = BaseFeePerGas(BigInt(0))
  def apply(v: BigInt): BaseFeePerGas = v
  def apply(v: Long): BaseFeePerGas = BigInt(v)
  def apply(v: Int): BaseFeePerGas = BigInt(v)

  extension (p: BaseFeePerGas)
    def value: BigInt = p
    def toLong: Long = p.toLong
    def +(other: BaseFeePerGas): BaseFeePerGas = p + other
    def *(n: Long): BaseFeePerGas = p * n
    def *(n: BigInt): BaseFeePerGas = p * n
    def /(n: Long): BaseFeePerGas = p / n
    def compare(other: BaseFeePerGas): Int = p.compare(other)
    def >(other: BaseFeePerGas): Boolean = p > other
    def >=(other: BaseFeePerGas): Boolean = p >= other
    def <(other: BaseFeePerGas): Boolean = p < other
    def <=(other: BaseFeePerGas): Boolean = p <= other
    def min(other: BaseFeePerGas): BaseFeePerGas = if p < other then p else other
    def max(other: BaseFeePerGas): BaseFeePerGas = if p > other then p else other

  given rlpCodec: RLPCodec[BaseFeePerGas] = bigIntEncDec.xmap((v: BigInt) => BaseFeePerGas(v), _.value)
  given Ordering[BaseFeePerGas] = Ordering.by[BaseFeePerGas, BigInt](_.value)(using scala.math.Ordering.BigInt)
