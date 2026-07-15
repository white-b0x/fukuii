package com.chipprbots.fukuii.trie

import com.chipprbots.fukuii.storage.INodeStorage
import com.chipprbots.fukuii.storage.NodeLocation

/** An [[MptStorage]] backed by a `storage`-module [[INodeStorage]] (vault, S2) — the `DataSource`-backed counterpart to
  * [[InMemoryMptStorage]]. Adapts `trie`'s opaque `(Location, NodeHash)` pair to `storage`'s byte-pure `(NodeLocation,
  * IndexedSeq[Byte])` shape; `trie` owns node-shape awareness (this class), `storage` never sees it (byte-pure
  * boundary, besu `NodeLoader`/`NodeUpdater` seam realized as the L2 module edge).
  */
final class PersistedMptStorage(nodeStorage: INodeStorage) extends MptStorage:

  private def toNodeLocation(location: Location): NodeLocation =
    NodeLocation(location.owner.map(_.bytes), location.path)

  override def loadNode(location: Location, hash: NodeHash): Option[NodeEncoded] =
    nodeStorage.get(toNodeLocation(location), hash.bytes).map(bytes => NodeEncoded(bytes.toArray))

  override def storeNode(location: Location, hash: NodeHash, value: NodeEncoded): Unit =
    nodeStorage.put(toNodeLocation(location), hash.bytes, value.toArray.toIndexedSeq)
