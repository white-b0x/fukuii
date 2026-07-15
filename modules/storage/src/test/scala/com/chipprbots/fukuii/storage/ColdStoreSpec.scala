package com.chipprbots.fukuii.storage

import cats.effect.unsafe.implicits.global

import org.scalatest.funsuite.AnyFunSuite

class ColdStoreSpec extends AnyFunSuite:

  private def record(tag: Byte): ColdBlockRecord =
    ColdBlockRecord(
      header = IndexedSeq(tag, 1),
      body = IndexedSeq(tag, 2),
      receipts = IndexedSeq(tag, 3),
      totalDifficulty = IndexedSeq(tag, 4)
    )

  test("freeze then get round-trips every field, including total difficulty (ETC PoW retention invariant)"):
    val store = new PersistedColdStore(EphemDataSource())
    val report = store.freeze(Seq(BigInt(10) -> record(1))).unsafeRunSync()
    assert(report == FreezeReport(1, Some(BigInt(10)), Some(BigInt(10))))

    val read = store.get(10)
    assert(read.contains(record(1)))
    // The load-bearing invariant: total difficulty is retained in the cold shard, not dropped.
    assert(read.get.totalDifficulty == IndexedSeq(1, 4))

  test("a block never frozen reads as absent"):
    val store = new PersistedColdStore(EphemDataSource())
    store.freeze(Seq(BigInt(10) -> record(1))).unsafeRunSync()
    assert(store.get(11).isEmpty)

  test("write-time-boundary freeze across multiple calls leaves no gap in the frozen range"):
    val store = new PersistedColdStore(EphemDataSource())
    // Simulates a caller freezing contiguous ranges incrementally as blocks cross the reorg-safe boundary,
    // never as one later sweep.
    store.freeze(Seq(BigInt(0) -> record(0), BigInt(1) -> record(1))).unsafeRunSync()
    val report2 = store.freeze(Seq(BigInt(2) -> record(2), BigInt(3) -> record(3))).unsafeRunSync()

    assert(report2 == FreezeReport(2, Some(BigInt(0)), Some(BigInt(3))))
    (0 to 3).foreach(n => assert(store.get(n).contains(record(n.toByte))))
    assert(store.lowestFrozen.contains(BigInt(0)))
    assert(store.highestFrozen.contains(BigInt(3)))

  test("freeze is idempotent per block number: re-freezing overwrites rather than erroring or duplicating"):
    val store = new PersistedColdStore(EphemDataSource())
    store.freeze(Seq(BigInt(5) -> record(1))).unsafeRunSync()
    store.freeze(Seq(BigInt(5) -> record(2))).unsafeRunSync()
    assert(store.get(5).contains(record(2)))
    assert(store.lowestFrozen.contains(BigInt(5)))
    assert(store.highestFrozen.contains(BigInt(5)))

  test("a shard round-trips through the fixed-range format: every block in the shard is retrievable after freeze"):
    val shardSize = BigInt(4)
    val store = new PersistedColdStore(EphemDataSource(), shardSize)
    val records = (0 until 4).map(i => BigInt(i) -> record(i.toByte))
    store.freeze(records).unsafeRunSync()

    assert(store.shardBounds(0) == (BigInt(0), BigInt(4)))
    assert(store.shardBounds(3) == (BigInt(0), BigInt(4)))
    assert(store.shardBounds(4) == (BigInt(4), BigInt(8)))
    (0 until 4).foreach(n => assert(store.get(n).isDefined))

  test("expireShard drops every record in the shard's key range via a single deleteRange per cold namespace"):
    val shardSize = BigInt(4)
    val store = new PersistedColdStore(EphemDataSource(), shardSize)
    val shard0 = (0 until 4).map(i => BigInt(i) -> record(i.toByte))
    val shard1 = (4 until 8).map(i => BigInt(i) -> record(i.toByte))
    store.freeze(shard0 ++ shard1).unsafeRunSync()

    store.expireShard(1).unsafeRunSync() // any block in [0,4) selects that shard
    (0 until 4).foreach(n => assert(store.get(n).isEmpty))
    (4 until 8).foreach(n => assert(store.get(n).isDefined))

  test("ColdStore.encodeBlockNumber round-trips and preserves ascending numeric order as ascending byte order"):
    val a = ColdStore.encodeBlockNumber(BigInt(1))
    val b = ColdStore.encodeBlockNumber(BigInt(256))
    val c = ColdStore.encodeBlockNumber(BigInt(Long.MaxValue))
    assert(ColdStore.decodeBlockNumber(a.toIndexedSeq) == BigInt(1))
    assert(ColdStore.decodeBlockNumber(b.toIndexedSeq) == BigInt(256))
    assert(ColdStore.decodeBlockNumber(c.toIndexedSeq) == BigInt(Long.MaxValue))
    def unsignedCompare(x: Array[Byte], y: Array[Byte]): Int =
      x.zip(y).map { case (xb, yb) => (xb & 0xff) - (yb & 0xff) }.find(_ != 0).getOrElse(0)
    assert(unsignedCompare(a, b) < 0)
    assert(unsignedCompare(b, c) < 0)
