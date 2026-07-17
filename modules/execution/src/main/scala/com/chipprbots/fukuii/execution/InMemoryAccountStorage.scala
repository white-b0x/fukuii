package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.evm.AccountStorage
import com.chipprbots.fukuii.trie.MerklePatriciaTrie

/** Concrete [[AccountStorage]] over an L2 Merkle Patricia Trie — one contract's storage sub-trie (`UInt256` slot →
  * `BigInt` value), backed by the shared node store the wrapped trie carries.
  *
  * Immutable / functional (the seam contract): every [[store]] returns a **new** instance over a new (immutable) trie;
  * rolling back is discarding the returned instance. The trie holds its mutated nodes resident until [[persist]]
  * commits them to the node store and yields the storage root that feeds `Account.storageRoot`.
  *
  * **Zero is a deletion.** Storing `0` removes the slot (an absent slot, not a slot holding zero) — go-ethereum
  * `core/state/state_object.go` `updateTrie` (`DeleteStorage` on a zero value); the [[StateMpt.storageValueSerializer]]
  * never has to encode zero.
  */
final class InMemoryAccountStorage(val wrapped: MerklePatriciaTrie[UInt256, BigInt])
    extends AccountStorage[InMemoryAccountStorage]:

  override def store(offset: UInt256, value: BigInt): InMemoryAccountStorage =
    val newWrapped = if value == BigInt(0) then wrapped.remove(offset) else wrapped.put(offset, value)
    new InMemoryAccountStorage(newWrapped)

  override def load(offset: UInt256): BigInt = wrapped.get(offset).getOrElse(BigInt(0))

  /** Commit the resident storage nodes to the shared node store, returning a store-backed storage. */
  def persist: InMemoryAccountStorage = new InMemoryAccountStorage(wrapped.commit())

  /** The 32-byte storage-trie root — the value written into `Account.storageRoot`. `EmptyStorageRootHash` for an empty
    * storage trie.
    */
  def storageRoot: Hash = Hash(wrapped.getRootHash)
