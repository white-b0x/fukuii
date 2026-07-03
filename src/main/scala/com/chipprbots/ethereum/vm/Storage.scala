package com.chipprbots.ethereum.vm

import com.chipprbots.ethereum.domain.StorageKey

/** Account's storage representation. Implementation should be immutable and only keep track of changes to the storage
  */
trait Storage[S <: Storage[S]]:
  def store(offset: StorageKey, value: BigInt): S
  def load(offset: StorageKey): BigInt
