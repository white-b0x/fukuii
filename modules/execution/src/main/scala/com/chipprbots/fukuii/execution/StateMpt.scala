package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.decode
import com.chipprbots.fukuii.rlp.encode
import com.chipprbots.fukuii.trie.ByteArrayEncoder
import com.chipprbots.fukuii.trie.ByteArraySerializable
import com.chipprbots.fukuii.trie.HashByteArraySerializable

/** The trie key/value (de)serializers that turn L4's `execution` domain types into the raw trie bytes the L2
  * [[com.chipprbots.fukuii.trie.MerklePatriciaTrie]] runs against. Homed here (not in `trie`) because they bind the L1
  * `domain` types (`Address`, `Account`) and the EVM-native slot word (`UInt256`) — exactly the composition point the
  * `trie` [[ByteArrayEncoder]] scaladoc names ("the concrete `Address`→hashed-key world-state instance composes at L4
  * `execution`").
  *
  * Byte-authority for the two hashings and the storage-value RLP:
  *   - **Accounts state trie** — `keccak256(address)` key, RLP(Account) value. go-ethereum secure state trie hashes the
  *     20-byte address (`core/state/trie/secure_trie.go` `StateTrie.hashKey`); the leaf value is `RLP(StateAccount)`
  *     (`core/types/state_account.go`). besu `BonsaiWorldStateKeyValueStorage` keys accounts by `Hash.hash(address)`.
  *   - **Per-account storage trie** — `keccak256(pad32(slot))` key, `RLP(trimmed-big-endian value)` value, with a
  *     **zero value stored as a deletion** (an absent slot). go-ethereum `core/state/state_object.go` `updateTrie`:
  *     zero value → `DeleteStorage`; non-zero → `rlp.EncodeToBytes(common.TrimLeftZeroes(value))` under the
  *     `keccak256(key)` secure-trie key. The 32-byte big-endian slot is `UInt256.bytes`.
  */
object StateMpt:

  /** Accounts-state-trie key encoder: `keccak256(address.bytes)` — the geth secure-state-trie key. */
  val addressKeyEncoder: ByteArrayEncoder[Address] =
    HashByteArraySerializable[Address]((address: Address) => address.toArray)

  /** Accounts-state-trie value serializer: the leaf value is `RLP(Account)` (`Account derives RLPCodec`, field order
    * Nonce→Balance→StorageRoot→CodeHash — see [[Account]]).
    */
  val accountSerializer: ByteArraySerializable[Account] = new ByteArraySerializable[Account]:
    override def toBytes(input: Account): Array[Byte] = encode(input)
    override def fromBytes(bytes: Array[Byte]): Account = decode[Account](bytes)

  /** Storage-trie key encoder: `keccak256(pad32(slot))` — the 32-byte big-endian slot ([[UInt256.bytes]]) hashed, the
    * geth secure-storage-trie key.
    */
  val storageKeyEncoder: ByteArrayEncoder[UInt256] =
    HashByteArraySerializable[UInt256]((slot: UInt256) => slot.bytes.toArray)

  /** Storage-trie value serializer: `RLP(trimmed-big-endian value)`.
    * [[com.chipprbots.fukuii.rlp.RLPCodecs.bigIntCodec]] already emits the minimal-length unsigned big-endian (the
    * trimmed form geth RLP-encodes), so this is byte-identical to geth's `rlp.EncodeToBytes(TrimLeftZeroes(value))`.
    * Zero is never encoded here — a zero write is a deletion in [[InMemoryAccountStorage.store]] — matching geth's
    * `DeleteStorage` on a zero value.
    */
  val storageValueSerializer: ByteArraySerializable[BigInt] = new ByteArraySerializable[BigInt]:
    override def toBytes(input: BigInt): Array[Byte] = encode(input)
    override def fromBytes(bytes: Array[Byte]): BigInt = decode[BigInt](bytes)
