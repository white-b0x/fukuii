package com.chipprbots.fukuii.storage

import cats.effect.unsafe.implicits.global

import org.scalatest.funsuite.AnyFunSuite

class PruningStoreSpec extends AnyFunSuite:

  private def hash(tag: Byte): IndexedSeq[Byte] = IndexedSeq.fill(31)(0.toByte) :+ tag
  private val loc: NodeLocation = NodeLocation.Root

  /** A tiny two-level "trie": root -> leaf, one per block. Each block's root/leaf pair is content-addressed by a fresh
    * synthetic hash (as a real MPT would produce on a value change) so refcount bookkeeping — not real node
    * bytes/encoding — is what's under test (the trie-side child-hash extraction is explicitly out of `storage`'s scope;
    * this seam takes child-hash lists as a caller-supplied input, per the class docs).
    */
  private def writeBlock(
      nodeStorage: INodeStorage,
      rootTag: Byte,
      leafTag: Byte,
      value: IndexedSeq[Byte]
  ): BlockCommit =
    val root = hash(rootTag)
    val leaf = hash(leafTag)
    nodeStorage.put(loc, leaf, value)
    nodeStorage.put(loc, root, value)
    val leafCommit = NodeCommit(loc, leaf, Nil)
    val rootCommit = NodeCommit(loc, root, Seq(leaf))
    BlockCommit(0, Seq(leafCommit, rootCommit), root) // blockNumber overwritten by caller via .copy

  // -- Archive: never prunes -------------------------------------------------------------------------------------

  test("Archive mode never removes a node regardless of prune(safeHeight)"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val engine = PruningStore.forMode(PruningMode.Archive, nodeStorage, ds)

    val commit0 = writeBlock(nodeStorage, 1, 2, IndexedSeq(9)).copy(blockNumber = 0)
    engine.commitBlock(commit0)
    val commit1 = writeBlock(nodeStorage, 3, 4, IndexedSeq(9)).copy(blockNumber = 1)
    engine.commitBlock(commit1)

    val report = engine.prune(1000).unsafeRunSync()
    assert(report == PruneReport(0, 0))
    assert(nodeStorage.get(loc, hash(1)).isDefined)
    assert(nodeStorage.get(loc, hash(2)).isDefined)
    assert(nodeStorage.get(loc, hash(3)).isDefined)
    assert(nodeStorage.get(loc, hash(4)).isDefined)

  // -- Basic: refcount GC, horizon, barrier, rollback ------------------------------------------------------------

  test("Basic mode removes only 0-ref nodes past the history horizon, preserving the live (retained) root"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val engine = PruningStore.forMode(PruningMode.Basic, nodeStorage, ds, historyBlocks = 2)

    engine.commitBlock(writeBlock(nodeStorage, 1, 2, IndexedSeq(9)).copy(blockNumber = 0)) // R0(root=1)->L0(leaf=2)
    engine.commitBlock(writeBlock(nodeStorage, 3, 4, IndexedSeq(9)).copy(blockNumber = 1)) // R1(root=3)->L1(leaf=4)
    engine.commitBlock(writeBlock(nodeStorage, 5, 6, IndexedSeq(9)).copy(blockNumber = 2)) // R2(root=5)->L2(leaf=6)
    // Committing block 2 releases block 0's retained root (horizonBlock = 2 - 2 = 0): R0/L0 fall to 0-ref and are
    // filed on death row at block 2.

    // Barrier: a consumer registered at height 1 must keep everything orphaned in (1, head] alive.
    val barrierReport = engine.prune(1).unsafeRunSync()
    assert(barrierReport == PruneReport(0, 2)) // R0, L0 on death row but not yet removable
    assert(nodeStorage.get(loc, hash(1)).isDefined)
    assert(nodeStorage.get(loc, hash(2)).isDefined)

    // Once the barrier passes the orphaning block, the orphaned subtree is physically removed...
    val report = engine.prune(2).unsafeRunSync()
    assert(report == PruneReport(2, 0))
    assert(nodeStorage.get(loc, hash(1)).isEmpty) // R0
    assert(nodeStorage.get(loc, hash(2)).isEmpty) // L0
    // ...but the still-retained roots (R1/L1, R2/L2) are untouched — state-root preserved for every live root.
    assert(nodeStorage.get(loc, hash(3)).contains(IndexedSeq(9)))
    assert(nodeStorage.get(loc, hash(4)).contains(IndexedSeq(9)))
    assert(nodeStorage.get(loc, hash(5)).contains(IndexedSeq(9)))
    assert(nodeStorage.get(loc, hash(6)).contains(IndexedSeq(9)))

  test("Basic mode rollback replays the undo-log: a reorg resurrects the released root, state-root preserved"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val engine = PruningStore.forMode(PruningMode.Basic, nodeStorage, ds, historyBlocks = 2)

    engine.commitBlock(writeBlock(nodeStorage, 1, 2, IndexedSeq(9)).copy(blockNumber = 0)) // R0->L0
    engine.commitBlock(writeBlock(nodeStorage, 3, 4, IndexedSeq(9)).copy(blockNumber = 1)) // R1->L1
    engine.commitBlock(writeBlock(nodeStorage, 5, 6, IndexedSeq(9)).copy(blockNumber = 2)) // R2->L2, releases R0->L0

    // Reorg: block 2 never happened. Rolling it back must resurrect R0/L0 (their release is undone) and orphan
    // R2/L2 (this block's own contribution is retracted).
    engine.rollback(2)

    // R0/L0 must survive ANY future prune — they were never truly orphaned once block 2 is undone.
    val afterRollback = engine.prune(10).unsafeRunSync()
    assert(nodeStorage.get(loc, hash(1)).contains(IndexedSeq(9))) // R0 resurrected
    assert(nodeStorage.get(loc, hash(2)).contains(IndexedSeq(9))) // L0 resurrected (cascade, not just the root)
    // R1/L1 (never touched by block 2 or its rollback) remain live regardless.
    assert(nodeStorage.get(loc, hash(3)).contains(IndexedSeq(9)))
    assert(nodeStorage.get(loc, hash(4)).contains(IndexedSeq(9)))
    // R2/L2 (block 2's own retracted contribution) are now orphaned and were swept by the prune(10) above.
    assert(nodeStorage.get(loc, hash(5)).isEmpty)
    assert(nodeStorage.get(loc, hash(6)).isEmpty)
    assert(afterRollback.removed == 2)

  test("prune(safeHeight) never removes a node whose orphaning block is above safeHeight"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val engine = PruningStore.forMode(PruningMode.Basic, nodeStorage, ds, historyBlocks = 1)

    engine.commitBlock(writeBlock(nodeStorage, 1, 2, IndexedSeq(9)).copy(blockNumber = 0))
    engine.commitBlock(writeBlock(nodeStorage, 3, 4, IndexedSeq(9)).copy(blockNumber = 1)) // releases R0/L0 at block 1

    // A registered consumer at height H = 0: everything orphaned in (0, head] must survive.
    val guarded = engine.prune(0).unsafeRunSync()
    assert(guarded.removed == 0)
    assert(nodeStorage.get(loc, hash(1)).isDefined)
    assert(nodeStorage.get(loc, hash(2)).isDefined)

    // Advancing the barrier past the orphaning block allows the sweep.
    val released = engine.prune(1).unsafeRunSync()
    assert(released.removed == 2)
    assert(nodeStorage.get(loc, hash(1)).isEmpty)
    assert(nodeStorage.get(loc, hash(2)).isEmpty)

  // -- InMemory: same algebra, process-resident bookkeeping ------------------------------------------------------

  test("InMemory mode changelog rollback preserves the state root exactly as Basic mode does"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val engine = PruningStore.forMode(PruningMode.InMemory, nodeStorage, ds, historyBlocks = 2)

    engine.commitBlock(writeBlock(nodeStorage, 1, 2, IndexedSeq(9)).copy(blockNumber = 0))
    engine.commitBlock(writeBlock(nodeStorage, 3, 4, IndexedSeq(9)).copy(blockNumber = 1))
    engine.commitBlock(writeBlock(nodeStorage, 5, 6, IndexedSeq(9)).copy(blockNumber = 2))

    engine.rollback(2)
    val report = engine.prune(10).unsafeRunSync()

    assert(nodeStorage.get(loc, hash(1)).contains(IndexedSeq(9)))
    assert(nodeStorage.get(loc, hash(2)).contains(IndexedSeq(9)))
    assert(nodeStorage.get(loc, hash(3)).contains(IndexedSeq(9)))
    assert(nodeStorage.get(loc, hash(4)).contains(IndexedSeq(9)))
    assert(nodeStorage.get(loc, hash(5)).isEmpty)
    assert(nodeStorage.get(loc, hash(6)).isEmpty)
    assert(report.removed == 2)

  // -- Composability: an eviction × persistence pair genuinely governs behavior ------------------------------------

  test("the composed Basic-mode (default eviction x persistence) passes the same GC + rollback shape as above"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val engine = PruningStore.forMode(
      PruningMode.Basic,
      nodeStorage,
      ds,
      historyBlocks = 2,
      evictionStrategy = EvictionStrategy.always,
      persistenceStrategy = PersistenceStrategy.always
    )

    engine.commitBlock(writeBlock(nodeStorage, 1, 2, IndexedSeq(9)).copy(blockNumber = 0))
    engine.commitBlock(writeBlock(nodeStorage, 3, 4, IndexedSeq(9)).copy(blockNumber = 1))
    engine.commitBlock(writeBlock(nodeStorage, 5, 6, IndexedSeq(9)).copy(blockNumber = 2))

    val report = engine.prune(2).unsafeRunSync()
    assert(report.removed == 2)
    assert(nodeStorage.get(loc, hash(1)).isEmpty)
    assert(nodeStorage.get(loc, hash(2)).isEmpty)

  test("a genuinely different eviction policy produces a genuinely different outcome (real composition, not cosmetic)"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val engine = PruningStore.forMode(
      PruningMode.Basic,
      nodeStorage,
      ds,
      historyBlocks = 2,
      evictionStrategy = EvictionStrategy.never
    )

    engine.commitBlock(writeBlock(nodeStorage, 1, 2, IndexedSeq(9)).copy(blockNumber = 0))
    engine.commitBlock(writeBlock(nodeStorage, 3, 4, IndexedSeq(9)).copy(blockNumber = 1))
    engine.commitBlock(writeBlock(nodeStorage, 5, 6, IndexedSeq(9)).copy(blockNumber = 2)) // releases R0/L0

    // With eviction disabled, nothing is ever filed on death row — prune has nothing to remove, ever.
    val report = engine.prune(1000).unsafeRunSync()
    assert(report == PruneReport(0, 0))
    assert(nodeStorage.get(loc, hash(1)).isDefined)
    assert(nodeStorage.get(loc, hash(2)).isDefined)

  // -- Coverage hardening: shared subtrees, cascade-guard isolation, new-parent resurrection, depth ----------------
  //
  // The tests above all use fresh, unique hashes per block — they never exercise the real-MPT case of a node shared
  // across consecutive roots (an unmodified subtree, content-addressed the same in both). The four tests below close
  // that gap. Fail-then-pass evidence for each is recorded in the S3a coverage-hardening report (not committed here);
  // each was confirmed to fail under a targeted, reverted mutation of the exact mechanism it guards before being
  // accepted as a genuine regression test.

  test("releasing an old root does not orphan a child still held by a newer root (shared-subtree preservation)"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val bookkeeping = new PersistedBookkeeping(ds)
    val engine = new RefCountedNodeStore(nodeStorage, bookkeeping, historyBlocks = 1)
    val value: IndexedSeq[Byte] = IndexedSeq(9)

    val c = hash(10) // shared child, referenced by both R0 and R1
    val r0 = hash(11)
    val r1 = hash(12)
    nodeStorage.put(loc, c, value)
    nodeStorage.put(loc, r0, value)
    nodeStorage.put(loc, r1, value)

    // Block 0: R0 -> C (C is new this block).
    engine.commitBlock(BlockCommit(0, Seq(NodeCommit(loc, c, Nil), NodeCommit(loc, r0, Seq(c))), r0))
    // Block 1: R1 -> C. C is NOT re-emitted in newNodes — it already exists in storage (the real-MPT shape: an
    // unmodified subtree is never rewritten); only R1 itself is new this block.
    engine.commitBlock(BlockCommit(1, Seq(NodeCommit(loc, r1, Seq(c))), r1))
    // historyBlocks = 1 releases R0 (retained at block 0) when block 1 commits.

    assert(bookkeeping.getEntry(r0).map(_.refCount).contains(0)) // R0 correctly orphaned
    assert(bookkeeping.deathRowBlockOf(c).isEmpty) // C must NOT be filed — R1 still holds it
    assert(bookkeeping.getEntry(c).map(_.refCount).contains(1)) // refcount reflects R1's sole remaining hold

    val report = engine.prune(1).unsafeRunSync()
    assert(report.removed == 1) // only R0
    assert(nodeStorage.get(loc, r0).isEmpty)
    assert(nodeStorage.get(loc, c).contains(value)) // C survives and still resolves to its value

  test("a brand-new node's anchor-hold increment must not double-cascade into a child already reference-counted"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val bookkeeping = new PersistedBookkeeping(ds)
    // historyBlocks large enough that this single commit triggers no release — isolates the increment-cascade
    // mechanism from any decrement/horizon logic.
    val engine = new RefCountedNodeStore(nodeStorage, bookkeeping, historyBlocks = 100)
    val value: IndexedSeq[Byte] = IndexedSeq(9)

    val leaf = hash(21)
    val root = hash(20)
    nodeStorage.put(loc, leaf, value)
    nodeStorage.put(loc, root, value)

    engine.commitBlock(BlockCommit(1, Seq(NodeCommit(loc, leaf, Nil), NodeCommit(loc, root, Seq(leaf))), root))

    // leaf is referenced exactly once (root's direct child-hash edge). If root's own anchor-hold increment also
    // cascaded into its children (the bug the `wasOnDeathRow` gate prevents), leaf would be over-counted to 2 and —
    // needing two decrements instead of one to ever reach zero — would never be garbage collected by a single
    // legitimate release.
    assert(bookkeeping.getEntry(leaf).map(_.refCount).contains(1))
    assert(bookkeeping.getEntry(root).map(_.refCount).contains(1))

  test("a death-row node re-referenced by a NEW block's parent (not a rollback) resurrects with its own subtree"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val bookkeeping = new PersistedBookkeeping(ds)
    val engine = new RefCountedNodeStore(nodeStorage, bookkeeping, historyBlocks = 1)
    val value: IndexedSeq[Byte] = IndexedSeq(9)

    val orphanRoot = hash(30) // released and never re-referenced — stays orphaned
    val orphanChild = hash(31) // released, then re-referenced by a NEW parent below
    val orphanGrandchild = hash(34) // orphanChild's own child — must resurrect too, transitively
    val r1 = hash(32)
    val newParent = hash(33)
    Seq(orphanGrandchild, orphanChild, orphanRoot, r1, newParent).foreach(h => nodeStorage.put(loc, h, value))

    engine.commitBlock(
      BlockCommit(
        0,
        Seq(
          NodeCommit(loc, orphanGrandchild, Nil),
          NodeCommit(loc, orphanChild, Seq(orphanGrandchild)),
          NodeCommit(loc, orphanRoot, Seq(orphanChild))
        ),
        orphanRoot
      )
    )
    engine.commitBlock(BlockCommit(1, Seq(NodeCommit(loc, r1, Nil)), r1))
    // historyBlocks = 1 releases orphanRoot at block 1, cascading through orphanChild and orphanGrandchild — all
    // three filed on death row at block 1.
    assert(bookkeeping.deathRowBlockOf(orphanChild).contains(BigInt(1)))
    assert(bookkeeping.deathRowBlockOf(orphanGrandchild).contains(BigInt(1)))

    // Block 2: a brand-new parent references orphanChild directly — NOT a rollback, a fresh forward reference.
    engine.commitBlock(BlockCommit(2, Seq(NodeCommit(loc, newParent, Seq(orphanChild))), newParent))

    // orphanChild AND its own child (orphanGrandchild) must resurrect off death row with correct refcounts...
    assert(bookkeeping.deathRowBlockOf(orphanChild).isEmpty)
    assert(bookkeeping.getEntry(orphanChild).map(_.refCount).contains(1))
    assert(bookkeeping.deathRowBlockOf(orphanGrandchild).isEmpty)
    assert(bookkeeping.getEntry(orphanGrandchild).map(_.refCount).contains(1))
    // ...while orphanRoot — never re-referenced by anyone — correctly remains orphaned and is swept by prune. (A
    // size-1 retention window also releases r1's own anchor the moment block 2 commits — its natural, unrelated
    // removal alongside orphanRoot, not a second orphaning of the subtree under test.)
    val report = engine.prune(2).unsafeRunSync()
    assert(report.removed == 2)
    assert(nodeStorage.get(loc, orphanRoot).isEmpty)
    assert(nodeStorage.get(loc, r1).isEmpty)
    assert(nodeStorage.get(loc, orphanChild).contains(value))
    assert(nodeStorage.get(loc, orphanGrandchild).contains(value))

  test("the refcount cascade propagates correctly through 3+ levels on both release and resurrection"):
    val ds = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(ds)
    val bookkeeping = new PersistedBookkeeping(ds)
    val engine = new RefCountedNodeStore(nodeStorage, bookkeeping, historyBlocks = 1)
    val value: IndexedSeq[Byte] = IndexedSeq(9)

    val root = hash(40)
    val mid = hash(41)
    val leaf = hash(42)
    val r1 = hash(43)
    Seq(root, mid, leaf, r1).foreach(h => nodeStorage.put(loc, h, value))

    // A 3-level chain: root -> mid -> leaf.
    engine.commitBlock(
      BlockCommit(
        0,
        Seq(NodeCommit(loc, leaf, Nil), NodeCommit(loc, mid, Seq(leaf)), NodeCommit(loc, root, Seq(mid))),
        root
      )
    )
    engine.commitBlock(BlockCommit(1, Seq(NodeCommit(loc, r1, Nil)), r1)) // releases root at block 1

    // Decrement cascade must propagate through all 3 levels on release: root, mid, AND leaf all reach zero.
    assert(bookkeeping.getEntry(root).map(_.refCount).contains(0))
    assert(bookkeeping.getEntry(mid).map(_.refCount).contains(0))
    assert(bookkeeping.getEntry(leaf).map(_.refCount).contains(0))
    assert(bookkeeping.deathRowBlockOf(root).contains(BigInt(1)))
    assert(bookkeeping.deathRowBlockOf(mid).contains(BigInt(1)))
    assert(bookkeeping.deathRowBlockOf(leaf).contains(BigInt(1)))

    // Reorg: undo block 1. The increment cascade must equally propagate through all 3 levels on resurrection.
    engine.rollback(1)
    assert(bookkeeping.getEntry(root).map(_.refCount).contains(1))
    assert(bookkeeping.getEntry(mid).map(_.refCount).contains(1))
    assert(bookkeeping.getEntry(leaf).map(_.refCount).contains(1))
    assert(bookkeeping.deathRowBlockOf(root).isEmpty)
    assert(bookkeeping.deathRowBlockOf(mid).isEmpty)
    assert(bookkeeping.deathRowBlockOf(leaf).isEmpty)
    assert(nodeStorage.get(loc, root).contains(value))
    assert(nodeStorage.get(loc, mid).contains(value))
    assert(nodeStorage.get(loc, leaf).contains(value))
