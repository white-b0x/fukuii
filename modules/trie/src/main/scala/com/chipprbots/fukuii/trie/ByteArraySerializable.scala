package com.chipprbots.fukuii.trie

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.crypto.kec256

/** How a trie key or value is turned into (and read back from) its raw trie bytes.
  *
  * This is the trie's own key/value-encoding contract — the `MerklePatriciaTrie[K, V]` is generic over it. Homed in the
  * `trie` layer (matching the reference-tree `com.chipprbots.ethereum.mpt` location, and every reference client: geth
  * `StateTrie`, core-geth, nethermind `StateTree` all keep key-hashing in the trie layer; reth/erigon feed the trie
  * pre-hashed keys from the state layer *above*). The concrete `Address`→hashed-key world-state instance composes at L4
  * `execution` (`WorldStateProxy`), which depends on `trie` + `domain` — never the reverse.
  */
trait ByteArrayEncoder[T]:
  def toBytes(input: T): Array[Byte]

trait ByteArrayDecoder[T]:
  def fromBytes(bytes: Array[Byte]): T

trait ByteArraySerializable[T] extends ByteArrayEncoder[T] with ByteArrayDecoder[T]

/** The keccak-key "secure trie" as *serializer composition*, not a trie subclass.
  *
  * Wraps a base key encoder so the key bytes handed to the trie are `keccak256(base.toBytes(key))`. This is exactly
  * geth's `StateTrie`/`secure_trie.go` behaviour (hash the key before insertion) expressed as a key serializer rather
  * than a `SecureTrie` wrapper class — byte-identical hashed-key bytes. The `*_secureTrie` reference fixtures key on
  * raw hex through this wrapper (they do not involve the `Address` type).
  */
final case class HashByteArraySerializable[T](base: ByteArrayEncoder[T]) extends ByteArrayEncoder[T]:
  override def toBytes(input: T): Array[Byte] = kec256(base.toBytes(input))

object ByteArraySerializable:

  /** Identity serializer for raw `Array[Byte]` keys/values — the plain (non-secure) trie's key encoding. */
  given rawByteArraySerializable: ByteArraySerializable[Array[Byte]] with
    override def toBytes(input: Array[Byte]): Array[Byte] = input
    override def fromBytes(bytes: Array[Byte]): Array[Byte] = bytes

  /** Serializer for `ByteString` keys/values. */
  given byteStringSerializable: ByteArraySerializable[ByteString] with
    override def toBytes(input: ByteString): Array[Byte] = input.toArray
    override def fromBytes(bytes: Array[Byte]): ByteString = ByteString(bytes)
