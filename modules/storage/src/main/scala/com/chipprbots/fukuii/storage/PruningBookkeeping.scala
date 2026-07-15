package com.chipprbots.fukuii.storage

import cats.effect.IO

import scala.collection.concurrent.TrieMap

/** One node's refcount-GC bookkeeping record — the besu/old-Mantis-lineage `StoredNode(rlp, references,
  * lastUsedByBlock)` shape, minus the RLP bytes ([[INodeStorage]] already holds those) plus [[location]] (needed to
  * physically [[INodeStorage.remove]] the node later regardless of hash- or path-keying — a bare hash cannot address a
  * path-keyed physical key).
  *
  * [[childHashes]] is recorded once, at commit time, by the caller (T2-adjacent — [[RefCountedNodeStore]] never parses
  * a node to discover them) so a later cascade (a chain of zero-ref decrements, or a rollback re-increment) never needs
  * the caller to re-supply it.
  */
final case class RefEntry(
    refCount: Int,
    location: NodeLocation,
    childHashes: Seq[IndexedSeq[Byte]],
    lastUsedByBlock: BigInt
)

/** The undo-log [[RefCountedNodeStore.rollback]] replays for one block: exactly what that block's
  * [[RefCountedNodeStore.commitBlock]] call did to the refcount graph — which older retained root (if any) it released,
  * its own new root, and the new nodes it introduced (each with its recorded child-hash list, so the reverse cascade
  * needs no external input).
  */
final case class BlockSnapshot(
    releasedRoot: Option[IndexedSeq[Byte]],
    rootHash: IndexedSeq[Byte],
    newNodes: Seq[NodeCommit]
)

/** The bookkeeping [[RefCountedNodeStore]] runs its refcount graph, death-row filing, retained-root ring, and rollback
  * snapshots against. [[PersistedBookkeeping]] (`PruningMode.Basic`, RocksDB-backed via dedicated column families) and
  * [[InMemoryBookkeeping]] (`PruningMode.InMemory`, process-resident) are the two composed realizations — the graph
  * algebra in [[RefCountedNodeStore]] is identical over either, which is precisely what kills the AS-IS gap of two
  * independently-maintained refcount implementations (RX-L2-15/16).
  */
trait PruningBookkeeping:
  def getEntry(nodeHash: IndexedSeq[Byte]): Option[RefEntry]
  def putEntry(nodeHash: IndexedSeq[Byte], entry: RefEntry): Unit
  def removeEntry(nodeHash: IndexedSeq[Byte]): Unit
  def putDeathRow(nodeHash: IndexedSeq[Byte], blockNumber: BigInt): Unit
  def removeDeathRow(nodeHash: IndexedSeq[Byte]): Unit
  def deathRowBlockOf(nodeHash: IndexedSeq[Byte]): Option[BigInt]
  def deathRowEntries(): IO[Seq[(IndexedSeq[Byte], BigInt)]]
  def putRetainedRoot(blockNumber: BigInt, rootHash: IndexedSeq[Byte]): Unit
  def getRetainedRoot(blockNumber: BigInt): Option[IndexedSeq[Byte]]
  def removeRetainedRoot(blockNumber: BigInt): Unit
  def putSnapshot(blockNumber: BigInt, snapshot: BlockSnapshot): Unit
  def getSnapshot(blockNumber: BigInt): Option[BlockSnapshot]
  def removeSnapshot(blockNumber: BigInt): Unit
  def snapshotBlockNumbers(): IO[Seq[BigInt]]

/** Small hand-rolled binary codec for the bookkeeping records above — `storage` has no RLP/circe dependency (only
  * `domain`/`common`), and these records never need to interop with anything outside this module, so a minimal
  * length-prefixed encoding is the appropriately-sized tool.
  */
private[storage] object PruningCodec:

  private def putInt(n: Int): Array[Byte] =
    Array((n >>> 24).toByte, (n >>> 16).toByte, (n >>> 8).toByte, n.toByte)

  private def getInt(bytes: IndexedSeq[Byte], offset: Int): Int =
    ((bytes(offset).toInt & 0xff) << 24) | ((bytes(offset + 1).toInt & 0xff) << 16) |
      ((bytes(offset + 2).toInt & 0xff) << 8) | (bytes(offset + 3).toInt & 0xff)

  private def putBytes(bs: IndexedSeq[Byte]): Array[Byte] = putInt(bs.length) ++ bs.toArray

  private def putBytesList(xs: Seq[IndexedSeq[Byte]]): Array[Byte] =
    putInt(xs.size) ++ xs.toArray.flatMap(putBytes)

  private def putBigInt(n: BigInt): Array[Byte] = putBytes(n.toByteArray.toIndexedSeq)

  private def putOptionBytes(opt: Option[IndexedSeq[Byte]]): Array[Byte] = opt match
    case Some(bs) => Array(1.toByte) ++ putBytes(bs)
    case None     => Array(0.toByte)

  private def putLocation(loc: NodeLocation): Array[Byte] = putOptionBytes(loc.owner) ++ putBytes(loc.path)

  private def putNodeCommit(nc: NodeCommit): Array[Byte] =
    putLocation(nc.location) ++ putBytes(nc.nodeHash) ++ putBytesList(nc.childHashes)

  final private class Cursor(bytes: IndexedSeq[Byte]):
    private var pos: Int = 0

    def readInt(): Int =
      val v = getInt(bytes, pos)
      pos += 4
      v

    def readBytes(): IndexedSeq[Byte] =
      val len = readInt()
      val v = bytes.slice(pos, pos + len)
      pos += len
      v

    def readBigInt(): BigInt = BigInt(readBytes().toArray)

    def readOptionBytes(): Option[IndexedSeq[Byte]] =
      val flag = bytes(pos)
      pos += 1
      if flag == 1.toByte then Some(readBytes()) else None

    def readBytesList(): Seq[IndexedSeq[Byte]] =
      val n = readInt()
      (0 until n).map(_ => readBytes())

    def readLocation(): NodeLocation = NodeLocation(readOptionBytes(), readBytes())

    def readNodeCommit(): NodeCommit =
      val location = readLocation()
      val nodeHash = readBytes()
      val children = readBytesList()
      NodeCommit(location, nodeHash, children)

  def encodeBigInt(n: BigInt): Array[Byte] = putBigInt(n)
  def decodeBigInt(bytes: IndexedSeq[Byte]): BigInt = Cursor(bytes).readBigInt()

  def encodeRefEntry(e: RefEntry): IndexedSeq[Byte] =
    (putInt(e.refCount) ++ putLocation(e.location) ++ putBytesList(e.childHashes) ++ putBigInt(
      e.lastUsedByBlock
    )).toIndexedSeq

  def decodeRefEntry(bytes: IndexedSeq[Byte]): RefEntry =
    val c = Cursor(bytes)
    val refCount = c.readInt()
    val location = c.readLocation()
    val children = c.readBytesList()
    val lastUsed = c.readBigInt()
    RefEntry(refCount, location, children, lastUsed)

  def encodeSnapshot(s: BlockSnapshot): IndexedSeq[Byte] =
    (putOptionBytes(s.releasedRoot) ++ putBytes(s.rootHash) ++ putInt(s.newNodes.size) ++
      s.newNodes.toArray.flatMap(putNodeCommit)).toIndexedSeq

  def decodeSnapshot(bytes: IndexedSeq[Byte]): BlockSnapshot =
    val c = Cursor(bytes)
    val released = c.readOptionBytes()
    val rootHash = c.readBytes()
    val count = c.readInt()
    val nodes = (0 until count).map(_ => c.readNodeCommit())
    BlockSnapshot(released, rootHash, nodes)

/** [[PruningBookkeeping]] realized over dedicated column families (`Namespace.RefCount` / `Namespace.DeathRow` /
  * `Namespace.RetainedRoot` / `Namespace.PruneSnapshot`) — dedicated CFs rather than prefixing keys within the hot
  * `Namespace.Node`/path-scheme CFs, the AS-IS anti-pattern this replaces (L2 improvement #15).
  */
final class PersistedBookkeeping(dataSource: DataSource) extends PruningBookkeeping:
  import PruningCodec.*

  override def getEntry(nodeHash: IndexedSeq[Byte]): Option[RefEntry] =
    dataSource.get(Namespace.RefCount, nodeHash).map(v => decodeRefEntry(v))

  override def putEntry(nodeHash: IndexedSeq[Byte], entry: RefEntry): Unit =
    dataSource.update(Seq(DataSourceUpdate(Namespace.RefCount, Nil, Seq(nodeHash -> encodeRefEntry(entry)))))

  override def removeEntry(nodeHash: IndexedSeq[Byte]): Unit =
    dataSource.update(Seq(DataSourceUpdate(Namespace.RefCount, Seq(nodeHash), Nil)))

  override def putDeathRow(nodeHash: IndexedSeq[Byte], blockNumber: BigInt): Unit =
    dataSource.update(
      Seq(DataSourceUpdate(Namespace.DeathRow, Nil, Seq(nodeHash -> encodeBigInt(blockNumber).toIndexedSeq)))
    )

  override def removeDeathRow(nodeHash: IndexedSeq[Byte]): Unit =
    dataSource.update(Seq(DataSourceUpdate(Namespace.DeathRow, Seq(nodeHash), Nil)))

  override def deathRowBlockOf(nodeHash: IndexedSeq[Byte]): Option[BigInt] =
    dataSource.get(Namespace.DeathRow, nodeHash).map(v => decodeBigInt(v))

  override def deathRowEntries(): IO[Seq[(IndexedSeq[Byte], BigInt)]] =
    dataSource
      .iterate(Namespace.DeathRow)
      .collect { case Right((key, value)) => (key.toIndexedSeq, decodeBigInt(value.toIndexedSeq)) }
      .compile
      .toList

  override def putRetainedRoot(blockNumber: BigInt, rootHash: IndexedSeq[Byte]): Unit =
    dataSource.update(
      Seq(DataSourceUpdate(Namespace.RetainedRoot, Nil, Seq(encodeBigInt(blockNumber).toIndexedSeq -> rootHash)))
    )

  override def getRetainedRoot(blockNumber: BigInt): Option[IndexedSeq[Byte]] =
    dataSource.get(Namespace.RetainedRoot, encodeBigInt(blockNumber).toIndexedSeq)

  override def removeRetainedRoot(blockNumber: BigInt): Unit =
    dataSource.update(Seq(DataSourceUpdate(Namespace.RetainedRoot, Seq(encodeBigInt(blockNumber).toIndexedSeq), Nil)))

  override def putSnapshot(blockNumber: BigInt, snapshot: BlockSnapshot): Unit =
    dataSource.update(
      Seq(
        DataSourceUpdate(
          Namespace.PruneSnapshot,
          Nil,
          Seq(encodeBigInt(blockNumber).toIndexedSeq -> encodeSnapshot(snapshot))
        )
      )
    )

  override def getSnapshot(blockNumber: BigInt): Option[BlockSnapshot] =
    dataSource.get(Namespace.PruneSnapshot, encodeBigInt(blockNumber).toIndexedSeq).map(v => decodeSnapshot(v))

  override def removeSnapshot(blockNumber: BigInt): Unit =
    dataSource.update(Seq(DataSourceUpdate(Namespace.PruneSnapshot, Seq(encodeBigInt(blockNumber).toIndexedSeq), Nil)))

  override def snapshotBlockNumbers(): IO[Seq[BigInt]] =
    dataSource
      .iterate(Namespace.PruneSnapshot)
      .collect { case Right((key, _)) => decodeBigInt(key.toIndexedSeq) }
      .compile
      .toList

/** [[PruningBookkeeping]] realized over process-resident maps — `PruningMode.InMemory`'s bookkeeping substrate, never
  * persisted (a restart loses the refcount graph entirely, by design: this mode is for lightweight/ephemeral roles, not
  * restart-surviving GC state).
  */
final class InMemoryBookkeeping extends PruningBookkeeping:
  private val entries = TrieMap.empty[IndexedSeq[Byte], RefEntry]
  private val deathRow = TrieMap.empty[IndexedSeq[Byte], BigInt]
  private val retainedRoots = TrieMap.empty[BigInt, IndexedSeq[Byte]]
  private val snapshots = TrieMap.empty[BigInt, BlockSnapshot]

  override def getEntry(nodeHash: IndexedSeq[Byte]): Option[RefEntry] = entries.get(nodeHash)
  override def putEntry(nodeHash: IndexedSeq[Byte], entry: RefEntry): Unit = entries.update(nodeHash, entry)
  override def removeEntry(nodeHash: IndexedSeq[Byte]): Unit =
    val _ = entries.remove(nodeHash)

  override def putDeathRow(nodeHash: IndexedSeq[Byte], blockNumber: BigInt): Unit =
    deathRow.update(nodeHash, blockNumber)
  override def removeDeathRow(nodeHash: IndexedSeq[Byte]): Unit =
    val _ = deathRow.remove(nodeHash)
  override def deathRowBlockOf(nodeHash: IndexedSeq[Byte]): Option[BigInt] = deathRow.get(nodeHash)
  override def deathRowEntries(): IO[Seq[(IndexedSeq[Byte], BigInt)]] = IO.pure(deathRow.toSeq)

  override def putRetainedRoot(blockNumber: BigInt, rootHash: IndexedSeq[Byte]): Unit =
    retainedRoots.update(blockNumber, rootHash)
  override def getRetainedRoot(blockNumber: BigInt): Option[IndexedSeq[Byte]] = retainedRoots.get(blockNumber)
  override def removeRetainedRoot(blockNumber: BigInt): Unit =
    val _ = retainedRoots.remove(blockNumber)

  override def putSnapshot(blockNumber: BigInt, snapshot: BlockSnapshot): Unit = snapshots.update(blockNumber, snapshot)
  override def getSnapshot(blockNumber: BigInt): Option[BlockSnapshot] = snapshots.get(blockNumber)
  override def removeSnapshot(blockNumber: BigInt): Unit =
    val _ = snapshots.remove(blockNumber)
  override def snapshotBlockNumbers(): IO[Seq[BigInt]] = IO.pure(snapshots.keys.toSeq)
