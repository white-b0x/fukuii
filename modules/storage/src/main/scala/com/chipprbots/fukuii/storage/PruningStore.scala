package com.chipprbots.fukuii.storage

import cats.effect.IO

/** One newly-written node's identity for refcount bookkeeping. [[location]] and [[childHashes]] are supplied by the
  * caller (a future trie-commit consumer — deliberately deferred here, T2-adjacent) rather than derived by parsing a
  * node: `storage` stays byte-pure (DoD grep — no `com.chipprbots.fukuii.trie.*` import). [[childHashes]] must already
  * be committed (via an earlier [[NodeCommit]] in the same or a prior block) before this one references them — batch
  * write order matters for MPT correctness (Iron Rule #6): children before parents.
  */
final case class NodeCommit(location: NodeLocation, nodeHash: IndexedSeq[Byte], childHashes: Seq[IndexedSeq[Byte]])

/** One block's contribution to the refcount graph: the node writes it introduces, plus the resulting root hash (which
  * may itself be one of [[newNodes]], or an already-known hash if the block introduced no trie changes).
  */
final case class BlockCommit(blockNumber: BigInt, newNodes: Seq[NodeCommit], rootHash: IndexedSeq[Byte])

/** The result of one [[PruningStore.prune]] call. */
final case class PruneReport(removed: Int, remainingDeathRow: Int)

/** The composable pruning-mode contract (`PruningMode.{Archive,Basic,InMemory}` realized) — [[PruningStore.forMode]] is
  * the factory that composes a named [[PruningMode]] with an [[EvictionStrategy]] × [[PersistenceStrategy]] pair
  * (RX-L2-15/16). [[prune]] takes an external safe-height, never reasoning purely on local reorg depth (R7, RX-L2-18):
  * `safeHeight = min(local reorg horizon, min(consumer FinishedHeights))`; a caller with no external consumer passes
  * the local reorg horizon, reproducing today's (additive, zero-regression) behavior.
  */
trait PruningStore:

  /** Registers a block's new nodes and root in the refcount graph, releasing (and cascading a decrement through) any
    * older retained root that falls out of the retention window as a result. Synchronous, mirroring
    * [[DataSource.update]] — no unbounded scan or iterator lifetime is involved.
    */
  def commitBlock(commit: BlockCommit): Unit

  /** Undoes exactly what [[commitBlock]] did for `blockNumber` (a reorg unwind): re-increments (and cascades a
    * resurrection through) any root that commit had released, decrements this block's own root and new-node
    * contributions, and discards the block's undo-log. A no-op if no snapshot is on file for `blockNumber` (already
    * pruned, or never committed).
    */
  def rollback(blockNumber: BigInt): Unit

  /** Physically removes every death-row node whose orphaning block is at or before `safeHeight` — see the class-level
    * R7 note. Never removes a node whose orphaning block is above `safeHeight`, regardless of local reorg depth.
    */
  def prune(safeHeight: BigInt): IO[PruneReport]

/** `PruningMode.Archive`: never files anything to death row, never prunes — every node committed via [[commitBlock]]
  * (written to the backing [[INodeStorage]] by the caller, outside this seam) is retained forever. [[rollback]] is
  * correctly a no-op: nothing was ever removed, so there is nothing to undo.
  */
object ArchivePruningStore extends PruningStore:
  override def commitBlock(commit: BlockCommit): Unit = ()
  override def rollback(blockNumber: BigInt): Unit = ()
  override def prune(safeHeight: BigInt): IO[PruneReport] = IO.pure(PruneReport(0, 0))

/** `PruningMode.Basic` and `PruningMode.InMemory` share this one refcount-GC mechanism — the composable split kills the
  * AS-IS gap of two independently-maintained refcount implementations (RX-L2-15/16); only [[bookkeeping]]'s backing
  * (persisted CFs vs process-resident maps) differs between the two modes, selected by [[PruningStore.forMode]].
  *
  * ==The refcount graph==
  * Every node starts at `refCount = 0`. [[commitBlock]] increments each new node's children by one (a parent now
  * references them) and the block's own root by one (an explicit "this is a live, queryable head" anchor hold). When a
  * root falls `historyBlocks` deep, its anchor hold is released (decremented); a decrement that reaches zero files the
  * node on death row and — governed by [[evictionStrategy]] — cascades the same decrement through its recorded
  * [[NodeCommit.childHashes]], so an entire orphaned subtree becomes GC-eligible in one call. [[rollback]] replays the
  * exact inverse (re-increment before decrement, in reverse order), and increments cascade a resurrection through
  * children on the symmetric zero-to-one transition — so a rolled-back release brings an orphaned subtree back with it,
  * never leaving a resurrected parent pointing at still-orphaned children.
  */
final class RefCountedNodeStore(
    nodeStorage: INodeStorage,
    bookkeeping: PruningBookkeeping,
    historyBlocks: Int,
    evictionStrategy: EvictionStrategy = EvictionStrategy.always,
    persistenceStrategy: PersistenceStrategy = PersistenceStrategy.always
) extends PruningStore:

  override def commitBlock(commit: BlockCommit): Unit =
    import commit.*
    newNodes.foreach { nc =>
      val refCount = bookkeeping.getEntry(nc.nodeHash).map(_.refCount).getOrElse(0)
      bookkeeping.putEntry(nc.nodeHash, RefEntry(refCount, nc.location, nc.childHashes, blockNumber))
    }
    newNodes.foreach(nc => nc.childHashes.foreach(child => incrementRef(child, blockNumber)))
    incrementRef(rootHash, blockNumber)
    bookkeeping.putRetainedRoot(blockNumber, rootHash)

    val horizonBlock = blockNumber - historyBlocks
    val releasedRoot: Option[IndexedSeq[Byte]] =
      if horizonBlock >= 0 then bookkeeping.getRetainedRoot(horizonBlock) else None
    releasedRoot.foreach { oldRoot =>
      decrementRef(oldRoot, blockNumber)
      bookkeeping.removeRetainedRoot(horizonBlock)
    }
    bookkeeping.putSnapshot(blockNumber, BlockSnapshot(releasedRoot, rootHash, newNodes))
    // Advisory flush-timing signal only: the concrete update(...)-vs-updateSync(...) durability choice for this
    // block's bookkeeping writes is the caller's to make with the result, per the DataSource WAL-off discipline.
    val _ = persistenceStrategy.shouldPersist(blockNumber)

  override def rollback(blockNumber: BigInt): Unit =
    bookkeeping.getSnapshot(blockNumber).foreach { snapshot =>
      snapshot.releasedRoot.foreach { oldRoot =>
        incrementRef(oldRoot, blockNumber)
        bookkeeping.putRetainedRoot(blockNumber - historyBlocks, oldRoot)
      }
      decrementRef(snapshot.rootHash, blockNumber)
      bookkeeping.removeRetainedRoot(blockNumber)
      snapshot.newNodes.foreach(nc => nc.childHashes.foreach(child => decrementRef(child, blockNumber)))
      bookkeeping.removeSnapshot(blockNumber)
    }

  override def prune(safeHeight: BigInt): IO[PruneReport] =
    for
      entries <- bookkeeping.deathRowEntries()
      oldSnapshots <- bookkeeping.snapshotBlockNumbers()
    yield
      val state = TrieStoreState(0L, 0L, safeHeight, safeHeight)
      var removed = 0
      entries.foreach { case (hash, deathBlock) =>
        if deathBlock <= safeHeight && evictionStrategy.shouldPrunePersistedNode(state) then
          bookkeeping.getEntry(hash).foreach(e => nodeStorage.remove(e.location, hash))
          bookkeeping.removeEntry(hash)
          bookkeeping.removeDeathRow(hash)
          removed += 1
      }
      // Rollback beyond safeHeight is never a valid operation (that is the entire point of the barrier) — an
      // undo-log at or before it can never be replayed, so it is safe to discard alongside the nodes it referenced.
      oldSnapshots.filter(_ <= safeHeight).foreach(bn => bookkeeping.removeSnapshot(bn))
      PruneReport(removed, entries.size - removed)

  /** Increments `hash`'s refcount. Cascades the same increment through its recorded children ONLY when `hash` was
    * previously filed on death row — i.e. this is a genuine resurrection of a subtree that [[decrementRef]] previously
    * cascaded down to zero (a rollback undoing an earlier release, or a re-reference of a not-yet- physically-pruned
    * orphan). A brand-new node's first-ever increment (a direct trie-edge reference from pass 2 of [[commitBlock]], or
    * that block's own root anchor hold) must NOT cascade: its children already receive their own proper increment via
    * [[commitBlock]]'s direct per-edge pass — cascading here as well would double-count them.
    */
  private def incrementRef(hash: IndexedSeq[Byte], atBlock: BigInt): Unit =
    val wasOnDeathRow = bookkeeping.deathRowBlockOf(hash).isDefined
    bookkeeping.getEntry(hash) match
      case Some(entry) =>
        bookkeeping.putEntry(hash, entry.copy(refCount = entry.refCount + 1))
        bookkeeping.removeDeathRow(hash)
        if wasOnDeathRow then entry.childHashes.foreach(child => incrementRef(child, atBlock))
      case None =>
        bookkeeping.putEntry(hash, RefEntry(1, NodeLocation.Root, Nil, atBlock))

  /** Decrements `hash`'s refcount. A one-to-zero transition — governed by [[evictionStrategy]] — files the node on
    * death row and cascades the same decrement through its recorded children. A no-op on an already-zero (or unknown)
    * entry: nothing to decrement, nothing to cascade.
    */
  private def decrementRef(hash: IndexedSeq[Byte], atBlock: BigInt): Unit =
    bookkeeping.getEntry(hash).foreach { entry =>
      if entry.refCount > 0 then
        val newCount = entry.refCount - 1
        bookkeeping.putEntry(hash, entry.copy(refCount = newCount))
        if newCount == 0 then
          val state = TrieStoreState(0L, 0L, atBlock, atBlock)
          if evictionStrategy.shouldPruneDirtyNode(state) then
            bookkeeping.putDeathRow(hash, atBlock)
            entry.childHashes.foreach(child => decrementRef(child, atBlock))
    }

object PruningStore:

  /** Composes a named [[PruningMode]] preset with an [[EvictionStrategy]] × [[PersistenceStrategy]] pair (default: the
    * permissive always/always policy, reproducing the historically-monolithic behavior). `historyBlocks` is the local
    * reorg-retention window `Basic`/`InMemory` hold before a root's anchor is released — separate from the mode
    * selector itself, so [[StorageProfile]]'s existing `PruningMode` cases need no change.
    */
  def forMode(
      mode: PruningMode,
      nodeStorage: INodeStorage,
      dataSource: DataSource,
      historyBlocks: Int = 128,
      evictionStrategy: EvictionStrategy = EvictionStrategy.always,
      persistenceStrategy: PersistenceStrategy = PersistenceStrategy.always
  ): PruningStore =
    mode match
      case PruningMode.Archive => ArchivePruningStore
      case PruningMode.Basic =>
        new RefCountedNodeStore(
          nodeStorage,
          new PersistedBookkeeping(dataSource),
          historyBlocks,
          evictionStrategy,
          persistenceStrategy
        )
      case PruningMode.InMemory =>
        new RefCountedNodeStore(
          nodeStorage,
          new InMemoryBookkeeping,
          historyBlocks,
          evictionStrategy,
          persistenceStrategy
        )
