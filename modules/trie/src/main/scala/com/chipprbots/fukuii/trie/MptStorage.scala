package com.chipprbots.fukuii.trie

import scala.collection.concurrent.TrieMap

import org.apache.pekko.util.ByteString

/** A 32-byte keccak reference to a stored node. Opaque over `ByteString` (structural equality ⇒ a valid map key). */
opaque type NodeHash = ByteString
object NodeHash:
  def apply(bytes: ByteString): NodeHash = bytes
  extension (h: NodeHash)
    def bytes: ByteString = h
    def toArray: Array[Byte] = h.toArray

/** The RLP-encoded bytes of a node. Opaque over `Array[Byte]`. */
opaque type NodeEncoded = Array[Byte]
object NodeEncoded:
  def apply(bytes: Array[Byte]): NodeEncoded = bytes
  extension (e: NodeEncoded) def toArray: Array[Byte] = e

/** A node's location in the trie — the nibble path from the trie root.
  *
  * besu's `NodeLoader`/`NodeUpdater` carry both a `location` and a `hash`; this is the abstraction that lets a store
  * key nodes by *either* hash (archival) *or* path. For T1 the store is hash-keyed and `location` is not load-bearing.
  * The path-keyed profile and the **account-scoped** `Location` (a nibble path from the *account* trie root, so
  * per-account storage-subtrie nodes don't collide in the shared node store) is the S2 storage decision (vault) — the
  * seam shape is landed here so S2 does not have to retrofit it.
  */
opaque type Location = ByteString
object Location:
  val Root: Location = ByteString.empty
  def apply(path: ByteString): Location = path
  extension (l: Location) def bytes: ByteString = l

/** The read half of the byte-pure storage seam (besu `NodeLoader`): resolve a node's RLP bytes by `(location, hash)`.
  */
trait NodeLoader:
  def loadNode(location: Location, hash: NodeHash): Option[NodeEncoded]

/** The write half of the byte-pure storage seam (besu `NodeUpdater`): store a node's RLP bytes by `(location, hash)`.
  */
trait NodeUpdater:
  def storeNode(location: Location, hash: NodeHash, value: NodeEncoded): Unit

/** The typed node contract the trie runs against — resolves a hash reference to a decoded [[MptNode]].
  *
  * The module boundary is besu's `NodeLoader`/`NodeUpdater` realized as the L2 edge: `storage` (below) moves bytes
  * keyed by `(location, hash)` with zero node-shape awareness; `trie` owns the decode ([[NodeLoader]]→[[MptNode]]) and
  * the collapse ([[MptNode]]→[[NodeUpdater]]). `trie → storage` is down-only; `storage` never imports node types.
  */
trait MptStorage extends NodeLoader with NodeUpdater:

  /** Resolve a hash reference to its decoded node. Fails loud ([[MptNodeDecodeException]]) on a missing or malformed
    * node — a dangling reference is a corrupt trie, never a silent empty.
    */
  def get(nodeHash: ByteString): MptNode =
    loadNode(Location.Root, NodeHash(nodeHash)) match
      case Some(encoded) => MptNode.decode(encoded.toArray)
      case None =>
        throw new MptNodeDecodeException(
          s"Missing trie node ${com.chipprbots.fukuii.bytes.Hex.toHexString(nodeHash.toArray)} — trie is inconsistent"
        )

/** An in-memory, hash-keyed [[MptStorage]] — the DoD/test backing and the reference for a `DataSource`-backed impl
  * (vault, S2). Thread-safe via [[TrieMap]]; `location` is ignored (hash-keyed).
  */
final class InMemoryMptStorage extends MptStorage:
  private val store = TrieMap.empty[ByteString, Array[Byte]]

  override def loadNode(location: Location, hash: NodeHash): Option[NodeEncoded] =
    store.get(hash.bytes).map(NodeEncoded.apply)

  override def storeNode(location: Location, hash: NodeHash, value: NodeEncoded): Unit =
    store.update(hash.bytes, value.toArray)

  def size: Int = store.size
