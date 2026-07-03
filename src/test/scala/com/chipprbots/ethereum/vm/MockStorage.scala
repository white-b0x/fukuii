package com.chipprbots.ethereum.vm

import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.UInt256

object MockStorage:
  val Empty: MockStorage = MockStorage()

  def fromSeq(words: Seq[UInt256]): MockStorage =
    val map = words.zipWithIndex.map { case (w, i) => BigInt(i) -> w.toBigInt }.toMap
    MockStorage(map)

case class MockStorage(data: Map[BigInt, BigInt] = Map()) extends Storage[MockStorage]:
  def store(offset: StorageKey, value: BigInt): MockStorage =
    val updated =
      if UInt256(value) == UInt256.Zero then data - offset.value
      else data + (offset.value -> value)

    copy(data = updated)

  def load(addr: StorageKey): BigInt =
    data.getOrElse(addr.value, UInt256.Zero)

  def isEmpty: Boolean =
    data.isEmpty
