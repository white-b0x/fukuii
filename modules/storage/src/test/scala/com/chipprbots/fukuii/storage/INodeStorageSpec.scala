package com.chipprbots.fukuii.storage

import org.scalatest.funsuite.AnyFunSuite

class INodeStorageSpec extends AnyFunSuite:

  private def nodeHash(tag: Byte): IndexedSeq[Byte] = IndexedSeq.fill(31)(0.toByte) :+ tag
  private val someOwner: IndexedSeq[Byte] = IndexedSeq.fill(32)(7.toByte)
  private val someValue: IndexedSeq[Byte] = IndexedSeq(1, 2, 3, 4)

  // -- empty-node short-circuit ------------------------------------------------------------------------------------

  test("HashKeyedNodeStorage returns the known empty-node encoding without ever persisting it"):
    val ds = EphemDataSource()
    val store = new HashKeyedNodeStorage(ds)
    store.put(NodeLocation.Root, EmptyNode.hash, someValue)
    assert(
      store.get(NodeLocation.Root, EmptyNode.hash).contains(EmptyNode.encoded) &&
        ds.get(Namespace.Node, EmptyNode.hash).isEmpty,
      "the empty-node encoding must be returned without ever being persisted"
    )

  test("PathKeyedNodeStorage returns the known empty-node encoding without ever persisting it"):
    val ds = EphemDataSource()
    val store = new PathKeyedNodeStorage(ds)
    store.put(NodeLocation.Root, EmptyNode.hash, someValue)
    assert(
      store.get(NodeLocation.Root, EmptyNode.hash).contains(EmptyNode.encoded) &&
        ds.get(Namespace.StateTriePath, EmptyNode.hash).isEmpty,
      "the empty-node encoding must be returned without ever being persisted"
    )

  // -- physical key layout ------------------------------------------------------------------------------------------

  test("PathKeyedNodeStorage routes owner=None to StateTriePath and owner=Some(_) to StorageTriePath"):
    val ds = EphemDataSource()
    val store = new PathKeyedNodeStorage(ds)
    val stateLoc = NodeLocation(None, IndexedSeq(1, 2))
    val storageLoc = NodeLocation(Some(someOwner), IndexedSeq(1, 2))
    store.put(stateLoc, nodeHash(1), someValue)
    store.put(storageLoc, nodeHash(2), someValue)
    assert(
      store.get(stateLoc, nodeHash(1)).contains(someValue) &&
        store.get(storageLoc, nodeHash(2)).contains(someValue) &&
        ds.get(Namespace.StateTriePath, stateLoc.path ++ nodeHash(1)).contains(someValue) &&
        ds.get(Namespace.StorageTriePath, someOwner ++ storageLoc.path ++ nodeHash(2)).contains(someValue),
      "a None-owner location must route to StateTriePath and a Some-owner location must route to StorageTriePath, keyed as expected"
    )

  // -- directional dual-read + RequirePath carve-out ------------------------------------------------------------------

  /** An [[INodeStorage]] that fails any call — used to prove a scheme is never even probed when `migrationInProgress`
    * is false (the `RequirePath` carve-out).
    */
  private object PoisonNodeStorage extends INodeStorage:
    override def get(location: NodeLocation, nodeHash: IndexedSeq[Byte]): Option[IndexedSeq[Byte]] =
      fail("the inactive scheme must not be probed when migrationInProgress = false")
    override def put(location: NodeLocation, nodeHash: IndexedSeq[Byte], value: IndexedSeq[Byte]): Unit =
      fail("the inactive scheme must never be written to")
    override def remove(location: NodeLocation, nodeHash: IndexedSeq[Byte]): Unit =
      fail("the inactive scheme must never be deleted from")

  test("without migrationInProgress, the inactive scheme is never probed on a miss (RequirePath carve-out)"):
    val hashStore = new HashKeyedNodeStorage(EphemDataSource())
    val seam = new SchemeIndirectedNodeStorage(hashStore, PoisonNodeStorage, Scheme.Hash, migrationInProgress = false)
    assert(seam.get(NodeLocation.Root, nodeHash(9)).isEmpty)

  test("with migrationInProgress, a miss on the active scheme falls back to a node written under the other scheme"):
    val ds = EphemDataSource()
    val hashStore = new HashKeyedNodeStorage(ds)
    val pathStore = new PathKeyedNodeStorage(ds)
    // Write directly to the hash-keyed store, bypassing the seam.
    hashStore.put(NodeLocation.Root, nodeHash(5), someValue)
    val seam = new SchemeIndirectedNodeStorage(hashStore, pathStore, Scheme.Path, migrationInProgress = true)
    assert(
      seam.currentScheme == Scheme.Path && seam.get(NodeLocation.Root, nodeHash(5)).contains(someValue),
      "the active scheme must be Path and a miss must fall back to the node written under Hash"
    )

  test("directional dual-read is directional: Hash-active probes hash first, falls back to path"):
    val ds = EphemDataSource()
    val hashStore = new HashKeyedNodeStorage(ds)
    val pathStore = new PathKeyedNodeStorage(ds)
    pathStore.put(NodeLocation.Root, nodeHash(6), someValue)
    val seam = new SchemeIndirectedNodeStorage(hashStore, pathStore, Scheme.Hash, migrationInProgress = true)
    assert(seam.get(NodeLocation.Root, nodeHash(6)).contains(someValue))

  test("writes go only to the active scheme"):
    val ds = EphemDataSource()
    val hashStore = new HashKeyedNodeStorage(ds)
    val pathStore = new PathKeyedNodeStorage(ds)
    val seam = new SchemeIndirectedNodeStorage(hashStore, pathStore, Scheme.Path, migrationInProgress = true)
    seam.put(NodeLocation.Root, nodeHash(7), someValue)
    assert(
      pathStore.get(NodeLocation.Root, nodeHash(7)).contains(someValue) &&
        hashStore.get(NodeLocation.Root, nodeHash(7)).isEmpty,
      "a write must land only in the active (Path) scheme, never the inactive (Hash) scheme"
    )

  // -- D3 delete asymmetry ---------------------------------------------------------------------------------------

  test("removing under an active Path scheme deletes only the path-keyed copy, leaving a hash-keyed copy intact"):
    val ds = EphemDataSource()
    val hashStore = new HashKeyedNodeStorage(ds)
    val pathStore = new PathKeyedNodeStorage(ds)
    // Simulate a post-migration state: the same node persisted under both physical schemes.
    hashStore.put(NodeLocation.Root, nodeHash(8), someValue)
    pathStore.put(NodeLocation.Root, nodeHash(8), someValue)
    val seam = new SchemeIndirectedNodeStorage(hashStore, pathStore, Scheme.Path, migrationInProgress = false)
    seam.remove(NodeLocation.Root, nodeHash(8))
    assert(
      pathStore.get(NodeLocation.Root, nodeHash(8)).isEmpty &&
        hashStore.get(NodeLocation.Root, nodeHash(8)).contains(someValue),
      "removing under the active Path scheme must delete only the path-keyed copy, leaving the hash-keyed copy intact"
    )
