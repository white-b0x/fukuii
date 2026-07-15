package com.chipprbots.fukuii.storage

import cats.effect.unsafe.implicits.global

import org.scalatest.funsuite.AnyFunSuite

class TrieLogStoreSpec extends AnyFunSuite:

  private def serializedFor(tag: Byte): IndexedSeq[Byte] = IndexedSeq(tag, 1, 2, 3)

  test("put then get round-trips the identical serialized bytes"):
    val store = new PersistedTrieLogStore(EphemDataSource())
    val serialized = serializedFor(9)

    store.put(42, serialized).unsafeRunSync()

    assert(store.get(42).unsafeRunSync().contains(serialized))

  test("get on a block number never put returns None"):
    val store = new PersistedTrieLogStore(EphemDataSource())
    store.put(42, serializedFor(1)).unsafeRunSync()

    assert(store.get(43).unsafeRunSync().isEmpty)

  test("put is idempotent per block number: re-putting overwrites rather than erroring"):
    val store = new PersistedTrieLogStore(EphemDataSource())
    store.put(10, serializedFor(1)).unsafeRunSync()
    store.put(10, serializedFor(2)).unsafeRunSync()

    assert(store.get(10).unsafeRunSync().contains(serializedFor(2)))

  test("the underlying CF is a real dedicated column family (Namespace.TrieLog), not a prefix within another CF"):
    val ds = EphemDataSource()
    val store = new PersistedTrieLogStore(ds)
    val serialized = serializedFor(5)
    store.put(7, serialized).unsafeRunSync()

    assert(ds.get(Namespace.TrieLog, ColdStore.encodeBlockNumber(7).toIndexedSeq).contains(serialized))

  test("prune(belowBlock) removes every log strictly below the horizon and keeps the horizon and everything above it"):
    val store = new PersistedTrieLogStore(EphemDataSource())
    (0 until 10).foreach(n => store.put(n, serializedFor(n.toByte)).unsafeRunSync())

    store.prune(5).unsafeRunSync()

    (0 until 5).foreach(n => assert(store.get(n).unsafeRunSync().isEmpty, s"block $n should have been pruned"))
    (5 until 10).foreach(n =>
      assert(store.get(n).unsafeRunSync().contains(serializedFor(n.toByte)), s"block $n should be retained")
    )

  test("prune is a no-op when belowBlock is 0 (nothing below the start of the keyspace)"):
    val store = new PersistedTrieLogStore(EphemDataSource())
    (0 until 3).foreach(n => store.put(n, serializedFor(n.toByte)).unsafeRunSync())

    store.prune(0).unsafeRunSync()

    (0 until 3).foreach(n => assert(store.get(n).unsafeRunSync().contains(serializedFor(n.toByte))))

  test(
    "prune uses an ordered range delete: keys are big-endian fixed-width so ascending byte order == ascending block order"
  ):
    // A regression guard on the key-encoding property prune()'s ordered deleteRange window depends on: without
    // fixed-width big-endian keys, a lexicographic deleteRange over block-number bytes would not align with
    // ascending block-number order (e.g. block 9's key would sort AFTER block 10's under naive variable-width
    // encoding), silently pruning the wrong range.
    val a = ColdStore.encodeBlockNumber(9)
    val b = ColdStore.encodeBlockNumber(10)
    def unsignedCompare(x: Array[Byte], y: Array[Byte]): Int =
      x.zip(y).map { case (xb, yb) => (xb & 0xff) - (yb & 0xff) }.find(_ != 0).getOrElse(0)
    assert(unsignedCompare(a, b) < 0)
