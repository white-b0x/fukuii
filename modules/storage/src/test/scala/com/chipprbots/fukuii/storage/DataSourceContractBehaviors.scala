package com.chipprbots.fukuii.storage

import cats.effect.unsafe.implicits.global

import scala.collection.immutable.ArraySeq
import scala.util.Random

import org.scalatest.flatspec.AnyFlatSpec

/** Shared [[DataSource]] contract behaviors, run identically against `EphemDataSource` and `RocksDbDataSource` so the
  * two backends are proven to agree on every method — including the key-ordering and empty-read edges the two
  * implementations arrive at through entirely different code paths (a fixed byte-prefix map filter vs. a native RocksDB
  * column-family iterator).
  */
trait DataSourceContractBehaviors:
  this: AnyFlatSpec =>

  private def randomBytes(n: Int): Array[Byte] =
    val arr = new Array[Byte](n)
    Random.nextBytes(arr)
    arr

  private def key(i: Int): Array[Byte] = Array.fill(32)(i.toByte)
  private def value(i: Int): Array[Byte] = Array.fill(8)(i.toByte)

  /** A `Seq[A]` whose `iterator` throws as soon as it is requested — used to make traversal of a `toUpsert`/`toRemove`
    * field fail partway through applying a [[DataUpdate]] batch, so [[dataSourceContract]]'s atomicity test can prove
    * that entries already handed to the implementation before the fault (in an earlier [[DataUpdate]] in the same
    * `Seq`) never become visible. `apply`/`length` are never exercised by [[DataSource.update]] (which only
    * `foreach`/`foldLeft`s over these fields), so they are unimplemented placeholders.
    */
  private def poisonedSeq[A](): Seq[A] =
    new scala.collection.immutable.AbstractSeq[A] with scala.collection.immutable.Seq[A]:
      def apply(i: Int): A = throw new NoSuchElementException("poisonedSeq has no elements")
      def length: Int = 0
      override def iterator: Iterator[A] = throw new RuntimeException("simulated mid-batch failure")

  /** `newDataSource` must return a fresh, empty backend each call; `destroy` tears it down after the test. */
  def dataSourceContract(newDataSource: () => DataSource): Unit =

    it should "return None for a key never written, in any namespace" in {
      val ds = newDataSource()
      try assert(ds.get(Namespace.Node, ArraySeq.unsafeWrapArray(randomBytes(32))).isEmpty)
      finally ds.destroy()
    }

    it should "insert and retrieve a stored key" in {
      val ds = newDataSource()
      try
        val k = randomBytes(32)
        val v = randomBytes(32)
        ds.update(Seq(DataSourceUpdateOptimized(Namespace.Node, Seq(), Seq(k -> v))))
        assert(ds.getOptimized(Namespace.Node, k).map(_.toSeq).contains(v.toSeq))
      finally ds.destroy()
    }

    it should "isolate the same key across different namespaces" in {
      val ds = newDataSource()
      try
        val k = randomBytes(32)
        val vNode = randomBytes(8)
        val vCode = randomBytes(8)
        ds.update(
          Seq(
            DataSourceUpdateOptimized(Namespace.Node, Seq(), Seq(k -> vNode)),
            DataSourceUpdateOptimized(Namespace.Code, Seq(), Seq(k -> vCode))
          )
        )
        assert(ds.getOptimized(Namespace.Node, k).map(_.toSeq).contains(vNode.toSeq))
        assert(ds.getOptimized(Namespace.Code, k).map(_.toSeq).contains(vCode.toSeq))
        ds.update(Seq(DataSourceUpdateOptimized(Namespace.Code, Seq(k), Seq())))
        assert(ds.getOptimized(Namespace.Node, k).map(_.toSeq).contains(vNode.toSeq))
        assert(ds.getOptimized(Namespace.Code, k).isEmpty)
      finally ds.destroy()
    }

    it should "remove keys via update" in {
      val ds = newDataSource()
      try
        val k1 = randomBytes(32)
        val k2 = randomBytes(32)
        ds.update(Seq(DataSourceUpdateOptimized(Namespace.Node, Seq(), Seq(k1 -> k1, k2 -> k2))))
        ds.update(Seq(DataSourceUpdateOptimized(Namespace.Node, Seq(k1), Seq())))
        assert(ds.getOptimized(Namespace.Node, k1).isEmpty)
        assert(ds.getOptimized(Namespace.Node, k2).isDefined)
      finally ds.destroy()
    }

    it should "remove all keys on clear" in {
      val ds = newDataSource()
      try
        val k = randomBytes(32)
        ds.update(Seq(DataSourceUpdateOptimized(Namespace.Node, Seq(), Seq(k -> k))))
        ds.clear()
        assert(ds.getOptimized(Namespace.Node, k).isEmpty)
      finally ds.destroy()
    }

    it should "commit a multi-namespace batch atomically — all entries visible after one update call" in {
      val ds = newDataSource()
      try
        val blockKey = randomBytes(32)
        val blockValue = randomBytes(32)
        val weightValue = randomBytes(8)
        ds.update(
          Seq(
            DataSourceUpdateOptimized(Namespace.Header, Seq(), Seq(blockKey -> blockValue)),
            DataSourceUpdateOptimized(Namespace.ChainWeight, Seq(), Seq(blockKey -> weightValue))
          )
        )
        assert(ds.getOptimized(Namespace.Header, blockKey).map(_.toSeq).contains(blockValue.toSeq))
        assert(ds.getOptimized(Namespace.ChainWeight, blockKey).map(_.toSeq).contains(weightValue.toSeq))
      finally ds.destroy()
    }

    it should "roll back a fully-processed earlier namespace when a later namespace's traversal faults (L2-F4, BUG-W7)" in {
      // Entry 1 (Header) is fully handed to the implementation — for RocksDbDataSource this means
      // `batch.put` already ran against the in-memory WriteBatch, but `db.write` has not. Entry 2's
      // toUpsert then faults on the very first `iterator` call, aborting `update` before any
      // implementation could reach a real commit point. A non-atomic implementation that writes
      // straight through (e.g. calling the real store per DataUpdate as it's consumed) would already
      // have entry 1's key visible; a correctly atomic one (assemble-then-commit-once) has nothing
      // visible from either entry.
      val ds = newDataSource()
      try
        val blockKey = randomBytes(32)
        val blockValue = randomBytes(32)
        assertThrows[RuntimeException](
          ds.update(
            Seq(
              DataSourceUpdateOptimized(Namespace.Header, Seq(), Seq(blockKey -> blockValue)),
              DataSourceUpdateOptimized(Namespace.ChainWeight, Seq(), poisonedSeq[(Array[Byte], Array[Byte])]())
            )
          )
        )
        assert(ds.getOptimized(Namespace.Header, blockKey).isEmpty)
      finally ds.destroy()
    }

    // NOT part of the shared contract: whether use-after-destroy raises is a per-backend safety
    // guard, not a documented DataSource obligation. RocksDbDataSource guards against native
    // use-after-free explicitly (see RocksDbDataSourceSpec); EphemDataSource, holding no native
    // resource, has nothing to guard and legitimately no-ops (see its divergence-edges doc).

    it should "return one Option per key from multiGetOptimized, preserving order, None for misses" in {
      val ds = newDataSource()
      try
        val present = List.tabulate(5)(key)
        val missing = key(999)
        ds.update(Seq(DataSourceUpdateOptimized(Namespace.Node, Seq(), present.map(k => k -> k))))
        val results = ds.multiGetOptimized(Namespace.Node, present :+ missing)
        assert(results.size == 6)
        present.zip(results.init).foreach { case (k, opt) => assert(opt.map(_.toSeq).contains(k.toSeq)) }
        assert(results.last.isEmpty)
      finally ds.destroy()
    }

    it should "scanRange in ascending unsigned-lexicographic key order, half-open [from, to)" in {
      val ds = newDataSource()
      try
        // 0x7f and 0x80+ must interleave correctly under UNSIGNED comparison, not Java's signed
        // Byte ordering (where 0x80.toByte == -128 sorts before 0x00).
        val ks = List(0x00, 0x01, 0x7f, 0x80, 0x81, 0xff).map(i => Array.fill(4)(i.toByte))
        ds.update(Seq(DataSourceUpdateOptimized(Namespace.BfsQueue, Seq(), ks.map(k => k -> k))))
        val scanned = ds.scanRange(Namespace.BfsQueue, ks.head, Array.fill(4)(0xff.toByte)).toList
        assert(scanned.map(_._1.toSeq) == ks.init.map(_.toSeq))
      finally ds.destroy()
    }

    it should "return an empty iterator from scanRange over an empty namespace" in {
      val ds = newDataSource()
      try
        val scanned = ds.scanRange(Namespace.HealingFrontier, Array.emptyByteArray, Array.fill(4)(0xff.toByte))
        assert(scanned.isEmpty)
      finally ds.destroy()
    }

    it should "deleteRange remove only the keys within [from, toExclusive)" in {
      val ds = newDataSource()
      try
        val ks = (0 until 10).map(key).toList
        ds.update(Seq(DataSourceUpdateOptimized(Namespace.Node, Seq(), ks.map(k => k -> k))))
        ds.deleteRange(Namespace.Node, key(2), key(5))
        (0 until 10).foreach { i =>
          val stillThere = ds.getOptimized(Namespace.Node, key(i)).isDefined
          if i >= 2 && i < 5 then assert(!stillThere, s"key $i should have been deleted")
          else assert(stillThere, s"key $i should NOT have been deleted")
        }
      finally ds.destroy()
    }

    it should "iterate every key-value pair within a namespace" in {
      val ds = newDataSource()
      try
        val ks = (0 until 25).map(key).toList
        ds.update(Seq(DataSourceUpdateOptimized(Namespace.Node, Seq(), ks.map(k => k -> value(0)))))
        val all = ds.iterate(Namespace.Node).compile.toList.unsafeRunSync()
        assert(all.forall(_.isRight))
        assert(all.size == ks.size)
      finally ds.destroy()
    }

    it should "return an empty stream from iterate(namespace) over an empty namespace" in {
      val ds = newDataSource()
      try assert(ds.iterate(Namespace.SnapSyncProgress).compile.toList.unsafeRunSync().isEmpty)
      finally ds.destroy()
    }

    it should "iterate() with no namespace yield every key-value pair across ALL namespaces, keys stripped of any namespace tag" in {
      val ds = newDataSource()
      try
        val headerKV = (key(1), value(1))
        val codeKV = (key(2), value(2))
        ds.update(
          Seq(
            DataSourceUpdateOptimized(Namespace.Header, Seq(), Seq(headerKV)),
            DataSourceUpdateOptimized(Namespace.Code, Seq(), Seq(codeKV))
          )
        )
        val all = ds.iterate().compile.toList.unsafeRunSync()
        assert(all.forall(_.isRight))
        val pairs = all.map(_.toOption.get).map { case (k, v) => (k.toSeq, v.toSeq) }.toSet
        assert(pairs == Set((headerKV._1.toSeq, headerKV._2.toSeq), (codeKV._1.toSeq, codeKV._2.toSeq)))
      finally ds.destroy()
    }
