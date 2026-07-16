package com.chipprbots.fukuii.storage

import org.scalatest.funsuite.AnyFunSuite

class FlatStorageSpec extends AnyFunSuite:

  // -- FlatAccountStorage -------------------------------------------------------------------------------------

  test("FlatAccountStorage get/put/remove round-trip over the dedicated Namespace.FlatAccount CF"):
    val ds = EphemDataSource()
    val flat = new FlatAccountStorage(ds)
    val key = IndexedSeq(1.toByte, 2.toByte)
    val value = IndexedSeq(9.toByte)

    val _ = assert(flat.get(key).isEmpty)
    flat.put(key, value)
    val _ = assert(
      flat.get(key).contains(value) &&
        // The primitive is a real dedicated CF, not a prefix convention over an existing one.
        ds.get(Namespace.FlatAccount, key).contains(value),
      "after put, both the storage's own get and a direct read of Namespace.FlatAccount must return the value"
    )

    flat.remove(key)
    assert(flat.get(key).isEmpty)

  test("FlatAccountStorage.seekFrom is a bounded forward range-serve primitive (RX-L2-24)"):
    val ds = EphemDataSource()
    val flat = new FlatAccountStorage(ds)
    flat.put(IndexedSeq(1.toByte), IndexedSeq(10.toByte))
    flat.put(IndexedSeq(2.toByte), IndexedSeq(20.toByte))
    flat.put(IndexedSeq(3.toByte), IndexedSeq(30.toByte))

    val served = flat.seekFrom(Array(1.toByte), Array(3.toByte)).toSeq
    assert(
      served.map { case (k, v) => (k.toIndexedSeq, v.toIndexedSeq) } ==
        Seq(IndexedSeq(1.toByte) -> IndexedSeq(10.toByte), IndexedSeq(2.toByte) -> IndexedSeq(20.toByte))
    )

  // -- FlatSlotStorage -----------------------------------------------------------------------------------------

  test("FlatSlotStorage get/put/remove are account-scoped: same slot key under different owners does not collide"):
    val ds = EphemDataSource()
    val flat = new FlatSlotStorage(ds)
    val ownerA = IndexedSeq.fill(31)(0.toByte) :+ 0xaa.toByte
    val ownerB = IndexedSeq.fill(31)(0.toByte) :+ 0xbb.toByte
    val slot = IndexedSeq(1.toByte)

    flat.put(ownerA, slot, IndexedSeq(111.toByte))
    flat.put(ownerB, slot, IndexedSeq(222.toByte))

    val _ = assert(
      flat.get(ownerA, slot).contains(IndexedSeq(111.toByte)) &&
        flat.get(ownerB, slot).contains(IndexedSeq(222.toByte)),
      "each owner's slot value must be independently retrievable after both puts"
    )

    flat.remove(ownerA, slot)
    assert(
      flat.get(ownerA, slot).isEmpty &&
        flat.get(ownerB, slot).contains(IndexedSeq(222.toByte)), // untouched
      "removing owner A's slot must clear only that owner's entry, leaving owner B's slot untouched"
    )

  test("FlatSlotStorage.seekStorageRange bounds the scan to one owner and strips the owner prefix back off"):
    val ds = EphemDataSource()
    val flat = new FlatSlotStorage(ds)
    val ownerA = IndexedSeq.fill(31)(0.toByte) :+ 0xaa.toByte
    val ownerB = IndexedSeq.fill(31)(0.toByte) :+ 0xbb.toByte

    flat.put(ownerA, IndexedSeq(1.toByte), IndexedSeq(10.toByte))
    flat.put(ownerA, IndexedSeq(2.toByte), IndexedSeq(20.toByte))
    flat.put(ownerB, IndexedSeq(1.toByte), IndexedSeq(99.toByte))

    val served = flat.seekStorageRange(ownerA, Array(0.toByte), Array(3.toByte)).toSeq
    assert(
      served.map { case (k, v) => (k.toIndexedSeq, v.toIndexedSeq) } ==
        Seq(IndexedSeq(1.toByte) -> IndexedSeq(10.toByte), IndexedSeq(2.toByte) -> IndexedSeq(20.toByte))
    )
