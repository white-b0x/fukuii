package com.chipprbots.fukuii.storage

import cats.effect.IO

import fs2.Stream

/** The single byte-pure key-value contract every storage backend (`RocksDbDataSource` in production, `EphemDataSource`
  * in tests / the fast-sync staging area) implements identically. `storage` moves `(Namespace, Array[Byte]) ->
  * Array[Byte]` with zero awareness of what is stored above it — no `MptNode`, no block/account/receipt types (DoD
  * grep: `storage` imports nothing from `com.chipprbots.fukuii.trie.*`).
  *
  * ==Atomicity (L2-F4)==
  * [[update]] (and its fsync-backed sibling [[updateSync]]) commit the ENTIRE `Seq[DataUpdate]` as a single atomic
  * write — regardless of how many distinct [[Namespace]] values the batch spans. `RocksDbDataSource` backs this with
  * one native `WriteBatch`: a crash mid-batch leaves NONE of the batch applied (all-or-nothing), never a partial
  * subset. This is the named substrate for cross-namespace atomicity (e.g. BUG-W7: writing a block body and its
  * chain-weight/total-difficulty update in the same batch so a crash can never observe one without the other) — callers
  * get this guarantee by constructing a single `Seq[DataUpdate]` spanning both namespaces and passing it to one
  * `update`/`updateSync` call, never by calling `update` twice.
  *
  * ==WAL durability (L2-F2)==
  * This contract intentionally exposes NO unqualified WAL-off bulk-write path. [[update]] writes with the RocksDB WAL
  * enabled (survives process crash; not power loss). [[updateSync]] additionally fsyncs before returning (survives
  * power loss too, at higher latency) — use it for rare, durability-critical one-time writes (e.g. SNAP finalization).
  * A future bulk-tuning seam (`Tune`-style WAL-off range commits, as some reference clients expose for
  * whole-phase-replayable bulk loads) is explicitly OUT of S1's scope: with a persisted SNAP-resume frontier journal
  * (see [[Namespace.Profile.Snap]]), a lost memtable after an unqualified WAL-off write would mark already-lost trie
  * nodes "done" — silent state corruption, not a replay-safe no-op. If a future layer adds such a seam, it MUST qualify
  * every WAL-off variant (flush at each frontier checkpoint, or scope it to phases that fully re-run from scratch on
  * crash) rather than exposing a bare "disable WAL" knob on this contract.
  *
  * ==Iterator lifetime (R5)==
  * The unbounded scans ([[iterate]]) return `fs2.Stream[IO, ...]`, not a raw `Iterator` — the fs2/cats-effect shape
  * enforces that a native RocksDB iterator's lifetime stays bounded: opened, drained in bounded batches, and closed
  * before any suspension point / cancellation can observe it, so no native handle survives a concurrent `close()`. This
  * differs from any specific reference client's API (geth uses `Release()`, besu `.onClose`). Point access ([[get]])
  * and bounded scans ([[scanRange]]) stay synchronous — `RocksDbDataSource` still opens/drains/closes their native
  * iterator within a single call, so there is no suspension point across which they could leak.
  */
trait DataSource:
  import DataSource.*

  /** Obtains the value associated with a key, throwing if absent. Prefer [[get]] unless absence is truly exceptional at
    * the call site.
    */
  def apply(namespace: Namespace, key: Key): Value =
    get(namespace, key).getOrElse(
      throw new NoSuchElementException(s"Key not found in namespace $namespace")
    )

  /** Obtains the value associated with a key, if one exists, in `namespace`. */
  def get(namespace: Namespace, key: Key): Option[Value]

  /** As [[get]], but assumes the caller already serialized `key` — avoids the generic `IndexedSeq[Byte]` wrapping when
    * the caller knows a cheaper representation.
    */
  def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]]

  /** Batch point-lookup for multiple keys in the same namespace. Returns one `Option` per key, `None` for a miss, in
    * the same order as `keys`. The default sequentially calls [[getOptimized]]; `RocksDbDataSource` overrides with a
    * single native multi-get call, amortising per-call overhead and bloom-filter evaluation across the batch.
    */
  def multiGetOptimized(namespace: Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] =
    keys.map(k => getOptimized(namespace, k))

  /** Forward range scan over `[fromKey, toKeyExclusive)` in ascending unsigned-lexicographic key order, within
    * `namespace`. Synchronous; the returned `Iterator` is materialized from the bounded window before this call returns
    * — no storage-native iterator/resource outlives the call, so it is abort-safe even if the caller stops consuming
    * partway through. Keys with high bytes (`>= 0x80`) MUST order correctly (unsigned compare) — implementations must
    * not rely on Java's signed `Array[Byte]`/`String` ordering.
    */
  def scanRange(
      namespace: Namespace,
      fromKey: Array[Byte],
      toKeyExclusive: Array[Byte]
  ): Iterator[(Array[Byte], Array[Byte])]

  /** Delete every key in `[fromKey, toKeyExclusive)` (unsigned lexicographic order) within `namespace`. Implementations
    * should prefer a storage-native range delete (one tombstone, reclaimed by compaction) over a point-delete loop:
    * deleting N keys one at a time writes N tombstones, which at scale (observed at ~140M keys) pins the CPU for tens
    * of minutes and drives memory to the edge of the container's cgroup before a dependent walk (e.g. BFS healing) can
    * even start.
    */
  def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit

  /** Atomically deletes, updates, and inserts key-value pairs across one or more [[DataUpdate]] entries — see the
    * class-level "Atomicity (L2-F4)" note.
    */
  def update(dataSourceUpdates: Seq[DataUpdate]): Unit

  /** Fsync-backed variant of [[update]]: flushes to disk before returning. See the class-level "WAL durability (L2-F2)"
    * note. Default implementation falls back to [[update]] for backends with no separate durability tier (e.g.
    * `EphemDataSource`).
    */
  def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit = update(dataSourceUpdates)

  /** Deletes every key-value pair in the DataSource. Test / staging-area use only. */
  def clear(): Unit

  /** Closes the DataSource without deleting the files/data it holds. */
  def close(): Unit

  /** Closes the DataSource (if not already closed) and deletes all data it holds. Test / staging-area use only. */
  def destroy(): Unit

  /** Streams every key-value pair across all namespaces, until the first error or full exhaustion. */
  def iterate(): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]]

  /** Streams every key-value pair within `namespace`, until the first error or full exhaustion. */
  def iterate(namespace: Namespace): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]]

object DataSource:
  type Key = IndexedSeq[Byte]
  type Value = IndexedSeq[Byte]

  /** Wraps an exception surfaced mid-iteration (including a concurrent `close()` racing a live scan) as a single
    * trailing stream element, rather than raising it and losing everything already yielded.
    */
  final case class IterationError(ex: Throwable)
