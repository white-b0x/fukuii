package com.chipprbots.fukuii.storage

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

import cats.effect.IO

import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.util.control.NonFatal

import fs2.Stream
import org.rocksdb.*

import com.chipprbots.fukuii.common.Logger

/** Per-`ChainInstance` RocksDB configuration. Every field is supplied by the instance that owns a given
  * `RocksDbDataSource` (constructor-injected — never a process-global default) so two `ChainInstance`s (e.g. two
  * networks running in the same JVM) never contend on shared tuning.
  */
trait RocksDbConfig:
  val createIfMissing: Boolean
  val paranoidChecks: Boolean
  val path: String
  val maxThreads: Int
  val maxOpenFiles: Int
  val verifyChecksums: Boolean
  val levelCompaction: Boolean
  val blockSize: Long
  val blockCacheSize: Long

  /** Global ceiling (bytes) on the sum of all column-family memtables. Without it, each column family independently
    * holds up to `write_buffer_size * max_write_buffer_number` with no shared cap — unbounded off-heap growth under a
    * write-heavy phase (e.g. SNAP sync) that can starve a memory-constrained host.
    */
  val dbWriteBufferSize: Long = 512L * 1024 * 1024

  /** Ceiling (bytes) on total live WAL across column families; also forces a flush of the column family pinning the
    * oldest WAL segment once the cap is hit, so it cannot accumulate indefinitely.
    */
  val maxTotalWalSize: Long = 512L * 1024 * 1024

  /** When true, attaches a RocksDB `Statistics` object (`StatsLevel.EXCEPT_DETAILED_TIMERS`) so block-cache hit/miss
    * tickers become observable via [[RocksDbDataSource.cacheStats]]. Off by default (adds ~1-2% read overhead); enable
    * only to diagnose cache-bound read amplification.
    */
  val enableStatistics: Boolean = false

/** Production [[DataSource]]: one RocksDB instance per `ChainInstance`, one native column family per [[Namespace]] case
  * (plus RocksDB's own `DEFAULT` CF). No MVCC — atomicity is via a single native `WriteBatch` per
  * [[update]]/[[updateSync]] call (see [[DataSource]]'s "Atomicity (L2-F4)" note).
  *
  * ==Per-instance isolation (R2)==
  * `db`, the column-family handles, the block cache, and the read/write lock below are all fields of THIS instance,
  * built from a constructor-supplied [[RocksDbConfig]] — never held in a companion-object `var`/shared singleton. Two
  * `RocksDbDataSource` instances (e.g. one per network in a multi-instance node) never share a lock, a cache, or a
  * handle map. The one intentional exception is [[RocksDbDataSource.libraryLoaded]]: loading the native RocksDB shared
  * library is a stateless, idempotent, JVM-wide operation (akin to registering a JDBC driver) — it holds no
  * per-instance data, so a single JVM-scoped `lazy val` is the objectively correct scope, not a violation of
  * per-instance isolation.
  *
  * ==Incident guards carried forward==
  *   1. Batched-iterator lifetime (`#1355`): every native `RocksIterator` is opened, drained, and closed within a
  *      single `dbLock.readLock()` + `assureNotClosed()` bracket — see [[unboundedScan]]. No native iterator is ever
  *      created outside a `withNativeIterator`-guarded block. 2. [[deleteRange]] issues one native range tombstone,
  *      never a point-delete loop. 3. [[RocksDbConfig.dbWriteBufferSize]] / [[RocksDbConfig.maxTotalWalSize]] cap total
  *      off-heap memtable/WAL growth across all column families. 4. `DBOptions.setStatsDumpPeriodSec(0)` disables
  *      RocksDB's periodic background stats-dump thread, which can race a concurrent native handle close (JNI
  *      `Statistics`/`DBOptions` teardown) and SIGSEGV the JVM; the thread serves no purpose here since [[cacheStats]]
  *      samples tickers directly rather than parsing a periodic text dump.
  */
final class RocksDbDataSource private (
    private var db: RocksDB,
    private val rocksDbConfig: RocksDbConfig,
    private var readOptions: ReadOptions,
    private var dbOptions: DBOptions,
    private var cfOptions: ColumnFamilyOptions,
    private var handles: Map[Namespace, ColumnFamilyHandle],
    private var statistics: Option[Statistics]
) extends DataSource
    with Logger:

  import RocksDbDataSource.*

  private val dbLock = new ReentrantReadWriteLock()

  @volatile
  private var isClosed = false

  /** Count of currently-open native `RocksIterator` handles on this instance — the emission point for a leaked-iterator
    * gauge. The registry (Micrometer, poll interval, alert threshold) is an L8 concern; this is only the raw
    * per-instance counter it would sample.
    */
  private val liveNativeIterators = new AtomicLong(0L)

  def liveIteratorCount: Long = liveNativeIterators.get()

  /** RocksDB block-cache tickers, or `None` when `RocksDbConfig.enableStatistics` is off.
    *
    * @return
    *   `(blockCacheHit, blockCacheMiss, indexFilterHit, indexFilterMiss)`, cumulative since DB open.
    */
  def cacheStats: Option[(Long, Long, Long, Long)] =
    statistics.map { stats =>
      val hit = stats.getTickerCount(TickerType.BLOCK_CACHE_HIT)
      val miss = stats.getTickerCount(TickerType.BLOCK_CACHE_MISS)
      val idxFilterHit =
        stats.getTickerCount(TickerType.BLOCK_CACHE_INDEX_HIT) +
          stats.getTickerCount(TickerType.BLOCK_CACHE_FILTER_HIT)
      val idxFilterMiss =
        stats.getTickerCount(TickerType.BLOCK_CACHE_INDEX_MISS) +
          stats.getTickerCount(TickerType.BLOCK_CACHE_FILTER_MISS)
      (hit, miss, idxFilterHit, idxFilterMiss)
    }

  private def handleOf(namespace: Namespace): ColumnFamilyHandle =
    handles.getOrElse(namespace, throw RocksDbDataSourceException(s"No column family open for namespace $namespace"))

  override def get(namespace: Namespace, key: DataSource.Key): Option[DataSource.Value] =
    dbLock.readLock().lock()
    try
      assureNotClosed()
      Option(db.get(handleOf(namespace), readOptions, key.toArray)).map(ArraySeq.unsafeWrapArray)
    catch
      case error: RocksDbDataSourceClosedException => throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"get failed for namespace $namespace", error)
    finally dbLock.readLock().unlock()

  override def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
    dbLock.readLock().lock()
    try
      assureNotClosed()
      Option(db.get(handleOf(namespace), readOptions, key))
    catch
      case error: RocksDbDataSourceClosedException => throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"getOptimized failed for namespace $namespace", error)
    finally dbLock.readLock().unlock()

  /** Batch point-lookup via a single JNI call, amortising per-call overhead and bloom-filter evaluation across the
    * batch.
    */
  override def multiGetOptimized(namespace: Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] =
    if keys.isEmpty then Seq.empty
    else
      import scala.jdk.CollectionConverters.*
      dbLock.readLock().lock()
      try
        assureNotClosed()
        val handle = handleOf(namespace)
        val cfList = java.util.Collections.nCopies(keys.size, handle)
        db.multiGetAsList(cfList, keys.asJava).asScala.map(Option.apply).toSeq
      catch
        case error: RocksDbDataSourceClosedException => throw error
        case NonFatal(error) =>
          throw RocksDbDataSourceException(s"multiGetOptimized failed for namespace $namespace", error)
      finally dbLock.readLock().unlock()

  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit = doWrite(dataSourceUpdates, sync = false)

  override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit = doWrite(dataSourceUpdates, sync = true)

  /** One native `WriteBatch` per call — see [[DataSource]]'s "Atomicity (L2-F4)" note. */
  private def doWrite(dataSourceUpdates: Seq[DataUpdate], sync: Boolean): Unit =
    dbLock.writeLock().lock()
    try
      assureNotClosed()
      withResources(new WriteOptions().setSync(sync)) { writeOptions =>
        withResources(new WriteBatch()) { batch =>
          dataSourceUpdates.foreach {
            case DataSourceUpdate(namespace, toRemove, toUpsert) =>
              val handle = handleOf(namespace)
              toRemove.foreach(key => batch.delete(handle, key.toArray))
              toUpsert.foreach { case (k, v) => batch.put(handle, k.toArray, v.toArray) }
            case DataSourceUpdateOptimized(namespace, toRemove, toUpsert) =>
              val handle = handleOf(namespace)
              toRemove.foreach(key => batch.delete(handle, key))
              toUpsert.foreach { case (k, v) => batch.put(handle, k, v) }
          }
          db.write(writeOptions, batch)
        }
      }
    catch
      case error: RocksDbDataSourceClosedException => throw error
      case NonFatal(error)                         => throw RocksDbDataSourceException(s"DataSource not updated", error)
    finally dbLock.writeLock().unlock()

  /** One native range tombstone for the whole interval — O(1) write, lazily reclaimed by compaction. Never expand a
    * range into per-key deletes (see [[DataSource.deleteRange]]).
    */
  override def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
    dbLock.writeLock().lock()
    try
      assureNotClosed()
      db.deleteRange(handleOf(namespace), fromKey, toKeyExclusive)
    catch
      case error: RocksDbDataSourceClosedException => throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"deleteRange failed for namespace $namespace", error)
    finally dbLock.writeLock().unlock()

  /** ReadOptions for range scans with `fillCache = false`, so a large sequential scan does not evict hot data from the
    * shared block cache.
    */
  private lazy val scanReadOptions: ReadOptions =
    new ReadOptions().setVerifyChecksums(rocksDbConfig.verifyChecksums).setFillCache(false)

  /** Opens ONE native iterator (tracked via [[liveNativeIterators]]), runs `f`, and closes it in a `finally` — the
    * single choke point every iterator-opening method in this class goes through, so no native `RocksIterator` can be
    * created without also being counted and guaranteed-closed (incident guard #1).
    */
  private def withNativeIterator[A](open: => RocksIterator)(f: RocksIterator => A): A =
    val it = open
    val _ = liveNativeIterators.incrementAndGet()
    try f(it)
    finally
      it.close()
      val _ = liveNativeIterators.decrementAndGet()

  /** Forward range scan via a single seek+next over a bounded `[fromKey, toKeyExclusive)` window. The native iterator
    * is opened, drained into a buffer, and closed before this call returns — no native handle outlives the call, so it
    * is abort-safe even if the caller drops the returned `Iterator` without consuming it.
    */
  override def scanRange(
      namespace: Namespace,
      fromKey: Array[Byte],
      toKeyExclusive: Array[Byte]
  ): Iterator[(Array[Byte], Array[Byte])] =
    dbLock.readLock().lock()
    try
      assureNotClosed()
      withNativeIterator(db.newIterator(handleOf(namespace), scanReadOptions)) { it =>
        val buf = mutable.ArrayBuffer.empty[(Array[Byte], Array[Byte])]
        it.seek(fromKey)
        while it.isValid && java.util.Arrays.compareUnsigned(it.key(), toKeyExclusive) < 0 do
          buf += ((it.key(), it.value()))
          it.next()
        buf.iterator
      }
    catch
      case error: RocksDbDataSourceClosedException => throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"scanRange failed for namespace $namespace", error)
    finally dbLock.readLock().unlock()

  /** Batch size per refill in [[unboundedScan]] — bounds peak memory at O(batch) regardless of total range size. */
  private val unboundedScanBatchSize = 4096

  /** Shared batching engine behind both [[iterate]] overloads (incident guard #1, issue `#1355`).
    *
    * These scans are unbounded (no upper-bound key), so — unlike [[scanRange]], which holds a single
    * `dbLock.readLock()` for its whole bounded window — a single lock cannot be held for the whole call. Instead each
    * batch opens a fresh native iterator via [[withNativeIterator]], drains up to [[unboundedScanBatchSize]] entries,
    * and closes it, ALL within one `dbLock.readLock()` + `assureNotClosed()` bracket. Between batches — exactly where
    * fs2 observes cancellation on the returned `Stream` — no lock and no native iterator are held, so a concurrent
    * `close()` (which takes `dbLock.writeLock()`) can never free native memory under a live iterator, and abandoning
    * the stream mid-scan leaks nothing.
    *
    * The `#1355` race itself (a live scan on one thread vs. a concurrent `close()` on another) is prevented
    * structurally by `dbLock`, not unit-reproduced — a two-thread JNI race is inherently non-deterministic and would
    * violate the constitution's determinism rule as a test. `RocksDbDataSourceSpec` instead pins the deterministic
    * single-threaded sequel: once `close()` has completed, every subsequent access fails cleanly with
    * `RocksDbDataSourceClosedException`, never a segfault.
    */
  private def unboundedScan(
      openIterator: () => RocksIterator,
      initialSeek: RocksIterator => Unit
  ): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]] =
    def refill(resumeAfter: Option[Array[Byte]]): (Vector[(Array[Byte], Array[Byte])], Boolean, Array[Byte]) =
      dbLock.readLock().lock()
      try
        assureNotClosed()
        withNativeIterator(openIterator()) { it =>
          resumeAfter match
            case Some(lastKey) =>
              it.seek(lastKey)
              if it.isValid && java.util.Arrays.equals(it.key(), lastKey) then it.next()
            case None => initialSeek(it)
          val buf = Vector.newBuilder[(Array[Byte], Array[Byte])]
          var taken = 0
          var lastKey: Array[Byte] = resumeAfter.orNull
          while taken < unboundedScanBatchSize && it.isValid do
            val k = it.key()
            buf += ((k, it.value()))
            lastKey = k
            it.next()
            taken += 1
          (buf.result(), taken < unboundedScanBatchSize, lastKey)
        }
      finally dbLock.readLock().unlock()

    def loop(
        resumeAfter: Option[Array[Byte]]
    ): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]] =
      Stream
        .eval(IO(refill(resumeAfter)))
        .flatMap { case (items, exhausted, lastKey) =>
          val batch = Stream.emits(items.map(Right(_)))
          if exhausted then batch else batch ++ loop(Some(lastKey))
        }
        .handleErrorWith(ex => Stream.emit(Left(DataSource.IterationError(ex))))

    loop(None)

  /** Every namespace is its own column family — `db.newIterator()` with no handle would only see RocksDB's own
    * (always-empty, for us) DEFAULT column family, not the union of every [[Namespace]] CF. Concatenating the
    * per-namespace scans is the correct — and only — way to visit every key-value pair across all namespaces.
    */
  override def iterate(): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]] =
    Stream.emits(Namespace.values.toIndexedSeq).flatMap(ns => iterate(ns))

  override def iterate(
      namespace: Namespace
  ): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]] =
    unboundedScan(() => db.newIterator(handleOf(namespace), scanReadOptions), _.seekToFirst())

  /** Test/staging-area only. The whole reopen — `destroy()` followed by reassigning every native handle field — runs
    * under `dbLock.writeLock()`, exactly like [[close]] and [[doWrite]]: without it, a concurrent `close()` (or a
    * second `clear()`) could observe or race the half-reassigned state (e.g. a stale `db` after `destroy()` freed it
    * but before the new `db` is assigned), double-closing or double-opening the same path. `ReentrantReadWriteLock`'s
    * write lock is reentrant, so `destroy()` re-acquiring it (via `close()`) on this same thread below is safe.
    */
  override def clear(): Unit =
    dbLock.writeLock().lock()
    try
      destroy()
      log.debug(s"Recreating RocksDB DataSource at path: ${rocksDbConfig.path}")
      val opened = createDB(rocksDbConfig)
      this.db = opened.db
      this.readOptions = opened.readOptions
      this.handles = opened.handles
      this.dbOptions = opened.dbOptions
      this.cfOptions = opened.cfOptions
      this.statistics = opened.statistics
      this.isClosed = false
    finally dbLock.writeLock().unlock()

  def approximateKeyCount(namespace: Namespace): Long =
    handles
      .get(namespace)
      .flatMap(h => scala.util.Try(db.getLongProperty(h, "rocksdb.estimate-num-keys")).toOption)
      .getOrElse(0L)

  /** Closes the DataSource without deleting its files. No overlay-cache invalidation happens here by design: any
    * LRU/reference-counting cache layered above this class is owned one abstraction tier up, and inverting that (this
    * class reaching up to invalidate a cache it has no reference to) is not this class's responsibility.
    */
  override def close(): Unit =
    log.info(s"Closing RocksDB DataSource at path: ${rocksDbConfig.path}")
    dbLock.writeLock().lock()
    try
      assureNotClosed()
      isClosed = true
      // Close order matters for column-family RocksDB instances: CF handles, then db, then the
      // options/statistics objects that outlive it.
      handles.values.foreach(_.close())
      db.close()
      readOptions.close()
      dbOptions.close()
      cfOptions.close()
      statistics.foreach(_.close())
      statistics = None
    catch
      case error: RocksDbDataSourceClosedException => throw error
      case NonFatal(error) => throw RocksDbDataSourceException(s"Failed to close DataSource", error)
    finally dbLock.writeLock().unlock()

  /** Test/staging-area only. */
  override def destroy(): Unit =
    try if !isClosed then close()
    finally destroyDB(rocksDbConfig)

  private def assureNotClosed(): Unit =
    if isClosed then throw RocksDbDataSourceClosedException(s"This ${getClass.getSimpleName} has been closed")

object RocksDbDataSource extends Logger:
  final case class RocksDbDataSourceClosedException(message: String) extends IllegalStateException(message)

  final case class RocksDbDataSourceException(message: String, cause: Throwable)
      extends RuntimeException(message, cause)

  object RocksDbDataSourceException:
    def apply(message: String): RocksDbDataSourceException = new RocksDbDataSourceException(message, null)

  /** Loads the RocksDB native library once per JVM. Stateless and idempotent — the one deliberate exception to
    * per-instance isolation (R2); see the class-level doc.
    */
  private lazy val libraryLoaded: Unit =
    try RocksDB.loadLibrary()
    catch
      case NonFatal(error) =>
        throw RocksDbDataSourceException(
          s"Failed to load the RocksDB native library — ensure rocksdbjni is on the classpath",
          error
        )

  final private case class OpenedDb(
      db: RocksDB,
      readOptions: ReadOptions,
      dbOptions: DBOptions,
      cfOptions: ColumnFamilyOptions,
      handles: Map[Namespace, ColumnFamilyHandle],
      statistics: Option[Statistics]
  )

  private def buildCfOptions(config: RocksDbConfig): ColumnFamilyOptions =
    import config.*
    val tableCfg = new BlockBasedTableConfig()
      .setBlockSize(blockSize)
      .setBlockCache(new HyperClockCache(blockCacheSize, 0, -1, false))
      .setCacheIndexAndFilterBlocks(true)
      .setPinL0FilterAndIndexBlocksInCache(true)
      .setFilterPolicy(new BloomFilter(10, false))
    new ColumnFamilyOptions()
      .setCompressionType(CompressionType.LZ4_COMPRESSION)
      .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
      .setLevelCompactionDynamicLevelBytes(levelCompaction)
      .setTableFormatConfig(tableCfg)

  private def createDB(config: RocksDbConfig): OpenedDb =
    import config.*
    import scala.jdk.CollectionConverters.*
    import java.nio.file.Files
    import java.nio.file.Paths

    libraryLoaded

    val dbPath = Paths.get(path)
    val pathExists = Files.exists(dbPath)
    if !pathExists && !createIfMissing then
      throw RocksDbDataSourceException(s"Database path does not exist and createIfMissing is false: $path")
    if !pathExists && createIfMissing then
      try
        val _ = Files.createDirectories(dbPath)
      catch
        case NonFatal(error) =>
          throw RocksDbDataSourceException(s"Failed to create database directory at $path", error)

    val readOptions = new ReadOptions().setVerifyChecksums(verifyChecksums)

    val dbOptions = new DBOptions()
      .setCreateIfMissing(createIfMissing)
      .setParanoidChecks(paranoidChecks)
      .setMaxOpenFiles(maxOpenFiles)
      .setIncreaseParallelism(maxThreads)
      .setCreateMissingColumnFamilies(true)
      .setDbWriteBufferSize(dbWriteBufferSize)
      .setMaxTotalWalSize(maxTotalWalSize)
      // Incident guard #4: disable the periodic background stats-dump thread — it serves no
      // purpose here (cacheStats samples tickers directly) and can race a concurrent native
      // handle close.
      .setStatsDumpPeriodSec(0)

    val statistics: Option[Statistics] =
      if enableStatistics then
        val stats = new Statistics()
        stats.setStatsLevel(StatsLevel.EXCEPT_DETAILED_TIMERS)
        dbOptions.setStatistics(stats)
        Some(stats)
      else None

    val cfOptions = buildCfOptions(config)

    val allNamespaces = Namespace.values.toIndexedSeq
    val cfDescriptors =
      new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions) +:
        allNamespaces.map(ns => new ColumnFamilyDescriptor(Array(ns.id), cfOptions))

    val handleList = mutable.Buffer.empty[ColumnFamilyHandle]
    val db =
      try RocksDB.open(dbOptions, path, cfDescriptors.asJava, handleList.asJava)
      catch
        case error: RocksDBException =>
          throw RocksDbDataSourceException(s"RocksDB failed to open database at path: $path", error)
        case NonFatal(error) =>
          throw RocksDbDataSourceException(s"Unexpected error opening RocksDB at path: $path", error)

    // handleList(0) is the DEFAULT CF (see cfDescriptors above); the remaining handles line up
    // positionally with allNamespaces.
    val handles = allNamespaces.zip(handleList.drop(1)).toMap
    require(
      handles.size == allNamespaces.size,
      "Namespace id collision produced fewer column family handles than namespaces"
    )

    OpenedDb(db, readOptions, dbOptions, cfOptions, handles, statistics)

  private def destroyDB(config: RocksDbConfig): Unit =
    try
      val cfOptions = buildCfOptions(config)
      val options = new Options()
        .setCreateIfMissing(config.createIfMissing)
        .setParanoidChecks(config.paranoidChecks)
      RocksDB.destroyDB(config.path, options)
      options.close()
      cfOptions.close()
    catch case NonFatal(error) => throw RocksDbDataSourceException(s"Failed to destroy DataSource", error)

  /** Opens (or creates) the RocksDB instance at `rocksDbConfig.path`, with one column family per [[Namespace]] case —
    * the full, fixed set (unconditional; profile-gated CF subsetting is S2's job, see [[Namespace]]'s
    * "Profile-membership reservation" note).
    */
  def apply(rocksDbConfig: RocksDbConfig): RocksDbDataSource =
    val opened = createDB(rocksDbConfig)
    new RocksDbDataSource(
      opened.db,
      rocksDbConfig,
      opened.readOptions,
      opened.dbOptions,
      opened.cfOptions,
      opened.handles,
      opened.statistics
    )

  /** try-with-resources for an `AutoCloseable`: runs `f`, then always closes `resource`, adding any close-time
    * exception as suppressed rather than masking the original failure.
    */
  private def withResources[R <: AutoCloseable, T](resource: R)(f: R => T): T =
    var primary: Throwable = null
    try f(resource)
    catch
      case NonFatal(e) =>
        primary = e
        throw e
    finally
      if primary != null then
        try resource.close()
        catch case NonFatal(suppressed) => primary.addSuppressed(suppressed)
      else resource.close()
