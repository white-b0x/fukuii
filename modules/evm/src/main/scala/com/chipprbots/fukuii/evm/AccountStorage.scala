package com.chipprbots.fukuii.evm

import com.chipprbots.fukuii.bytes.UInt256

/** Account's storage representation. Implementations should be immutable and only keep track of changes to the storage.
  *
  * The slot key is the 256-bit EVM word [[UInt256]]. The AS-IS `july-fourth` seam typed the offset as the L1
  * `domain.StorageKey` value class, which the L1 rebuild did not carry forward — the natural EVM-native slot type is
  * the word itself, and the concrete `trie`/`storage` (L2) implementation lives behind this seam at L4 (the VM is
  * parameterized over `S`, never importing storage).
  */
trait AccountStorage[S <: AccountStorage[S]]:
  def store(offset: UInt256, value: BigInt): S
  def load(offset: UInt256): BigInt
