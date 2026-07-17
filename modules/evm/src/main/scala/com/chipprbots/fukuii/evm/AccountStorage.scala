package com.chipprbots.fukuii.evm

import com.chipprbots.fukuii.bytes.UInt256

/** Account's storage representation. Implementations should be immutable and only keep track of changes to the storage.
  *
  * The slot key is the 256-bit EVM word [[UInt256]], not a `domain.StorageKey` value class — L1 does not carry that
  * type forward, and the natural EVM-native slot type is the word itself. The concrete `trie`/`storage` (L2)
  * implementation lives behind this seam at L4 (the VM is parameterized over `S`, never importing storage).
  */
trait AccountStorage[S <: AccountStorage[S]]:
  def store(offset: UInt256, value: BigInt): S
  def load(offset: UInt256): BigInt
