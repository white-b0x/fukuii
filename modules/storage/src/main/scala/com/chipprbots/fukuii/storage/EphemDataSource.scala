package com.chipprbots.fukuii.storage

import java.nio.ByteBuffer

import cats.effect.IO

import fs2.Stream

import DataSource.Key
import DataSource.Value

/** In-memory [[DataSource]] backed by a single `Map`, keyed by `(namespace.id, key)` concatenation. Test-only /
  * fast-sync staging-area use — never a production path (Iron Rule).
  *
  * ==Divergence edges vs. `RocksDbDataSource`==
  *   - Not thread-safe across methods: each method synchronizes on `this` individually, but there is no cross-call
  *     atomicity beyond a single `update`/`scanRange`/`deleteRange` invocation (matching `RocksDbDataSource`'s per-call
  *     `dbLock`, but with a coarser single `synchronized` rather than a read/write lock — reads block writes and vice
  *     versa, there is no concurrent-read fast path).
  *   - No column families: namespace isolation is a key-prefix convention over one `Map`, not a native storage
  *     partition. [[Namespace]] flags (`containsStaticData`, cache/GC hints) are inert here — they only affect
  *     `RocksDbDataSource`'s CF configuration.
  *   - `scanRange`/`deleteRange`/iteration order: emulated via an explicit unsigned-byte comparator over key suffixes
  *     (`cmp`, masking each byte with `0xff` before subtracting) so ordering matches RocksDB's unsigned-lexicographic
  *     column-family ordering — high-byte (`>= 0x80`) keys sort the same way in both backends. An empty-namespace
  *     scan/read returns the same `Iterator.empty`/`None` as an empty RocksDB CF (both backends agree on the empty-read
  *     edge — no special-casing needed on either side).
  *   - O(n) scans: every `scanRange`/`deleteRange`/`iterate(namespace)` call is a full linear pass over the whole map,
  *     filtered by namespace prefix — no index, no bloom filter, no native range delete. Fine for test fixtures and
  *     bounded staging-area use; never appropriate at production scale.
  */
final class EphemDataSource(private var storage: Map[ByteBuffer, Array[Byte]]) extends DataSource:

  private def namespaceKey(namespace: Namespace, key: Array[Byte]): ByteBuffer =
    val buf = ByteBuffer.allocate(1 + key.length)
    buf.put(namespace.id)
    buf.put(key)
    buf.flip()
    buf

  /** Unsigned lexicographic comparator, matching RocksDB's column-family key ordering. */
  private def cmp(a: Array[Byte], b: Array[Byte]): Int =
    val n = math.min(a.length, b.length)
    var i = 0
    var d = 0
    while i < n && d == 0 do
      d = (a(i) & 0xff) - (b(i) & 0xff)
      i += 1
    if d != 0 then d else a.length - b.length

  override def get(namespace: Namespace, key: Key): Option[Value] =
    getOptimized(namespace, key.toArray).map(_.toIndexedSeq)

  override def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] = synchronized {
    storage.get(namespaceKey(namespace, key))
  }

  override def scanRange(
      namespace: Namespace,
      fromKey: Array[Byte],
      toKeyExclusive: Array[Byte]
  ): Iterator[(Array[Byte], Array[Byte])] = synchronized {
    storage.iterator
      .collect {
        case (buf, value) if buf.get(0) == namespace.id =>
          val suffix = java.util.Arrays.copyOfRange(buf.array(), 1, buf.capacity())
          (suffix, value)
      }
      .filter { case (suffix, _) => cmp(suffix, fromKey) >= 0 && cmp(suffix, toKeyExclusive) < 0 }
      .toArray
      .sortWith { case ((a, _), (b, _)) => cmp(a, b) < 0 }
      .iterator
  }

  override def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
    synchronized {
      storage = storage.filter { case (buf, _) =>
        if buf.get(0) != namespace.id then true
        else
          val suffix = java.util.Arrays.copyOfRange(buf.array(), 1, buf.capacity())
          !(cmp(suffix, fromKey) >= 0 && cmp(suffix, toKeyExclusive) < 0)
      }
    }

  /** Threads a single accumulator through the whole `dataSourceUpdates` batch, assigning `storage` only ONCE at the end
    * — matching [[DataSource]]'s "Atomicity (L2-F4)" note (mirroring `RocksDbDataSource`'s assemble-then-commit-once
    * `WriteBatch` shape): if traversal of `dataSourceUpdates` throws partway (e.g. a malformed caller-supplied `Seq`),
    * `storage` is never reassigned, so nothing from the batch — including already-processed entries — becomes visible.
    */
  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit = synchronized {
    storage = dataSourceUpdates.foldLeft(storage) { (acc, dataUpdate) =>
      dataUpdate match
        case DataSourceUpdate(namespace, toRemove, toUpsert) =>
          applyUpdate(acc, namespace, toRemove.map(_.toArray), toUpsert.map { case (k, v) => (k.toArray, v.toArray) })
        case DataSourceUpdateOptimized(namespace, toRemove, toUpsert) =>
          applyUpdate(acc, namespace, toRemove, toUpsert)
    }
  }

  private def applyUpdate(
      base: Map[ByteBuffer, Array[Byte]],
      namespace: Namespace,
      toRemove: Seq[Array[Byte]],
      toUpsert: Seq[(Array[Byte], Array[Byte])]
  ): Map[ByteBuffer, Array[Byte]] =
    val afterRemoval = toRemove.foldLeft(base)((s, key) => s - namespaceKey(namespace, key))
    toUpsert.foldLeft(afterRemoval) { case (s, (key, value)) => s + (namespaceKey(namespace, key) -> value) }

  override def clear(): Unit = synchronized {
    storage = Map()
  }

  override def close(): Unit = ()

  override def destroy(): Unit = clear()

  override def iterate(): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]] = synchronized {
    // Strip the leading namespace-id byte, matching iterate(namespace) below — the key returned
    // here must be the same bare key a caller wrote, not the internal (namespace.id ++ key) form.
    Stream.emits(storage.toList.map { case (buf, value) =>
      Right((java.util.Arrays.copyOfRange(buf.array(), 1, buf.capacity()), value))
    })
  }

  override def iterate(
      namespace: Namespace
  ): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]] = synchronized {
    val namespaceVals = storage.collect {
      case (buf, value) if buf.get(0) == namespace.id =>
        Right((java.util.Arrays.copyOfRange(buf.array(), 1, buf.capacity()), value))
    }
    Stream.emits(namespaceVals.toSeq)
  }

object EphemDataSource:
  def apply(): EphemDataSource = new EphemDataSource(Map())
