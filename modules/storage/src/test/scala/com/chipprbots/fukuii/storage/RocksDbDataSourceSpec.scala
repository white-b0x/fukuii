package com.chipprbots.fukuii.storage

import scala.util.Random

import cats.effect.unsafe.implicits.global

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RocksDbDataSourceSpec extends AnyFlatSpec with Matchers with DataSourceContractBehaviors:

  (it should behave).like(dataSourceContract(() => RocksDbDataSource(RocksDbTestConfig())))

  private def randomKV(): (Array[Byte], Array[Byte]) =
    val k = new Array[Byte](32)
    val v = new Array[Byte](32)
    Random.nextBytes(k)
    Random.nextBytes(v)
    (k, v)

  "RocksDbDataSource" should "issue a single native range tombstone for deleteRange, not a point-delete loop" in {
    val db = RocksDbDataSource(RocksDbTestConfig())
    try
      val ks = (0 until 200).map(_ => randomKV())
      db.update(ks.map { case (k, v) => DataSourceUpdateOptimized(Namespace.Node, Seq(), Seq(k -> v)) })
      db.deleteRange(Namespace.Node, Array.fill(32)(0x00.toByte), Array.fill(32)(0xff.toByte))
      ks.foreach { case (k, _) => db.getOptimized(Namespace.Node, k) shouldBe empty }
    finally db.destroy()
  }

  it should "leak no native iterator across scanRange (bounded, synchronous)" in {
    val db = RocksDbDataSource(RocksDbTestConfig())
    try
      val ks = (0 until 50).map(_ => randomKV())
      db.update(ks.map { case (k, v) => DataSourceUpdateOptimized(Namespace.Node, Seq(), Seq(k -> v)) })
      val _ = db.scanRange(Namespace.Node, Array.fill(32)(0x00.toByte), Array.fill(32)(0xff.toByte)).toList
      db.liveIteratorCount shouldBe 0L
    finally db.destroy()
  }

  it should "leak no native iterator when a live iterate() Stream is cancelled mid-scan (#1355)" in {
    val db = RocksDbDataSource(RocksDbTestConfig())
    try
      val n = 20000
      val batchOfUpdates = (0 until n).map { _ =>
        val k = new Array[Byte](16)
        Random.nextBytes(k)
        DataSourceUpdateOptimized(Namespace.Node, Seq(), Seq(k -> k))
      }
      db.update(batchOfUpdates)

      // Cancel after the first element is observed — forces cancellation to land mid-batch,
      // between two of unboundedScan's internal refills, exercising the exact #1355 window.
      val program =
        db.iterate(Namespace.Node)
          .take(1)
          .compile
          .drain

      program.unsafeRunSync()
      // Give any in-flight native iterator a moment to be released by its `finally` (the
      // cancellation boundary in unboundedScan is between IO.eval calls, not inside one, so this
      // should already be zero synchronously — asserting it directly is the deterministic
      // replacement for the old "does the JVM survive" smoke check).
      db.liveIteratorCount shouldBe 0L
      db.iterate(Namespace.Node).compile.toList.unsafeRunSync().size shouldBe n
    finally db.destroy()
  }

  it should "expose cacheStats only when enableStatistics is set" in {
    val dbOff = RocksDbDataSource(RocksDbTestConfig(statisticsEnabled = false))
    try dbOff.cacheStats shouldBe None
    finally dbOff.destroy()

    val dbOn = RocksDbDataSource(RocksDbTestConfig(statisticsEnabled = true))
    try dbOn.cacheStats shouldBe defined
    finally dbOn.destroy()
  }

  it should "throw RocksDbDataSourceClosedException, not a generic exception, once closed" in {
    val db = RocksDbDataSource(RocksDbTestConfig())
    db.close()
    try
      assertThrows[RocksDbDataSource.RocksDbDataSourceClosedException](
        db.getOptimized(Namespace.Node, Array.emptyByteArray)
      )
    finally db.destroy()
  }

  it should "leak no native iterator when a live iterate() (no namespace) Stream is cancelled mid-scan (#1355)" in {
    val db = RocksDbDataSource(RocksDbTestConfig())
    try
      val n = 20000
      val batchOfUpdates = (0 until n).map { _ =>
        val k = new Array[Byte](16)
        Random.nextBytes(k)
        DataSourceUpdateOptimized(Namespace.Node, Seq(), Seq(k -> k))
      }
      db.update(batchOfUpdates)
      db.iterate().take(1).compile.drain.unsafeRunSync()
      db.liveIteratorCount shouldBe 0L
      db.iterate().compile.toList.unsafeRunSync().size shouldBe n
    finally db.destroy()
  }

  // #1355 was a genuine concurrent close()-vs-live-iterator race: two threads, one draining a
  // Stream while the other calls close(). That race is inherently non-deterministic at the JNI
  // level and is deliberately NOT unit-reproduced here (a flaky two-thread test would violate the
  // constitution's determinism rule) — it is prevented structurally by dbLock (see unboundedScan's
  // doc) rather than proven by a race test. What IS deterministic, and what the two tests below
  // pin, is the single-threaded sequel to that race: once close() has actually completed, every
  // subsequent access — point or streaming, whether or not an iterator was already used — must
  // fail cleanly with RocksDbDataSourceClosedException, never a native segfault.

  it should "throw RocksDbDataSourceClosedException on point access after close(), even once an iterator was already fully materialized" in {
    val db = RocksDbDataSource(RocksDbTestConfig())
    val ks = (0 until 50).map(_ => randomKV())
    db.update(ks.map { case (k, v) => DataSourceUpdateOptimized(Namespace.Node, Seq(), Seq(k -> v)) })
    val _ = db.iterate(Namespace.Node).compile.toList.unsafeRunSync()
    db.close()
    try assertThrows[RocksDbDataSource.RocksDbDataSourceClosedException](db.getOptimized(Namespace.Node, ks.head._1))
    finally db.destroy()
  }

  it should "surface RocksDbDataSourceClosedException as a single IterationError element (not a segfault) when iterate() runs after close()" in {
    val db = RocksDbDataSource(RocksDbTestConfig())
    val ks = (0 until 50).map(_ => randomKV())
    db.update(ks.map { case (k, v) => DataSourceUpdateOptimized(Namespace.Node, Seq(), Seq(k -> v)) })
    val _ = db.iterate(Namespace.Node).compile.toList.unsafeRunSync()
    db.close()
    try
      val result = db.iterate(Namespace.Node).compile.toList.unsafeRunSync()
      result.size shouldBe 1
      result.head match
        case Left(DataSource.IterationError(_: RocksDbDataSource.RocksDbDataSourceClosedException)) => succeed
        case other => fail(s"expected a single IterationError(RocksDbDataSourceClosedException), got: $other")
    finally db.destroy()
  }

  // -- SchemaMarker checked-at-open (S2) --------------------------------------------------------------------------

  /** A second config over the SAME `path` as `base` — simulates a reopen of an existing datadir under a (potentially
    * different) `StorageProfile`.
    */
  private def reopenConfig(base: RocksDbConfig): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = base.createIfMissing
      override val paranoidChecks: Boolean = base.paranoidChecks
      override val path: String = base.path
      override val maxThreads: Int = base.maxThreads
      override val maxOpenFiles: Int = base.maxOpenFiles
      override val verifyChecksums: Boolean = base.verifyChecksums
      override val levelCompaction: Boolean = base.levelCompaction
      override val blockSize: Long = base.blockSize
      override val blockCacheSize: Long = base.blockCacheSize

  it should "reject reopening a datadir under a mismatched StorageProfile with a typed error, before RocksDB ever sees a missing/extra CF" in {
    val config = RocksDbTestConfig()
    val firstOpen = RocksDbDataSource(config, StorageProfile.TipServer) // path-keyed (Bonsai-equivalent)
    firstOpen.close() // close only — the datadir and its CFs remain on disk

    try
      val ex = intercept[SchemaMarker.SchemaMismatchException] {
        RocksDbDataSource(reopenConfig(config), StorageProfile.ArchivalDApp) // hash-keyed (Forest-equivalent)
      }
      ex.getMessage should (include("column-family set").or(include("marker mismatch")))
    finally
      // Manual cleanup: the rejected reopen never produced a live instance to call destroy() on.
      val cleanup = RocksDbDataSource(reopenConfig(config), StorageProfile.TipServer)
      cleanup.destroy()
  }

  it should "reopen a datadir under the SAME StorageProfile without error, marker unchanged" in {
    val config = RocksDbTestConfig()
    val firstOpen = RocksDbDataSource(config, StorageProfile.TipServer)
    firstOpen.update(
      Seq(DataSourceUpdateOptimized(Namespace.StateTriePath, Seq(), Seq(Array[Byte](1) -> Array[Byte](2))))
    )
    firstOpen.close()

    val reopened = RocksDbDataSource(reopenConfig(config), StorageProfile.TipServer)
    try
      reopened.openNamespaces shouldBe StorageProfile.namespacesFor(StorageProfile.TipServer)
      reopened.getOptimized(Namespace.StateTriePath, Array[Byte](1)).map(_.toSeq) shouldBe Some(Seq[Byte](2))
    finally reopened.destroy()
  }

  it should "open with the default profile exactly as before S2 — the full Namespace.values CF set, unconditionally" in {
    val db = RocksDbDataSource(RocksDbTestConfig()) // no profile argument — the pre-S2 call shape
    try db.openNamespaces shouldBe Namespace.values.toSet
    finally db.destroy()
  }
