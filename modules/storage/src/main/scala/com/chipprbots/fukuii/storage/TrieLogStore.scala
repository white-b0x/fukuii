package com.chipprbots.fukuii.storage

import cats.effect.IO

/** Byte-pure persistence for a per-block `TrieLog` (`trie.TrieLog.serialized`/`TrieLog.deserialize`) — the R7
  * reorg-event substrate for the pruned/flat (Bonsai/Path) [[StorageProfile]] (RX-L2-19). `storage` stores and returns
  * the serialized bytes ONLY; it never imports or parses `com.chipprbots.fukuii.trie.*` types (DoD grep — the
  * `{prior,updated}` leaf-diff shape and its (de)serialization are owned entirely by `trie`, which depends DOWN on
  * `storage`, never the other way).
  *
  * Keyed by a fixed-width 8-byte big-endian block number (reusing [[ColdStore.encodeBlockNumber]]/
  * [[ColdStore.decodeBlockNumber]] — the same encoding [[ColdStore]] uses for its own number-addressed keys) so
  * ascending byte order == ascending block-number order, required for [[prune]]'s [[DataSource.deleteRange]] window to
  * be a contiguous, horizon-bounded range rather than a point-delete loop (Iron Rule #1 / DataSource contract).
  */
final class PersistedTrieLogStore(dataSource: DataSource):

  private def key(blockNumber: BigInt): IndexedSeq[Byte] = ColdStore.encodeBlockNumber(blockNumber).toIndexedSeq

  /** Persists `serialized` (a `TrieLog.serialized` blob) for `blockNumber`. Idempotent: re-`put`ting an already-stored
    * block number overwrites it (a plain upsert) — a caller retrying a partially-acknowledged write can never corrupt
    * state, mirroring [[ColdStore.freeze]]'s idempotence.
    */
  def put(blockNumber: BigInt, serialized: IndexedSeq[Byte]): IO[Unit] =
    IO(dataSource.update(Seq(DataSourceUpdate(Namespace.TrieLog, Nil, Seq(key(blockNumber) -> serialized)))))

  /** Reads back the serialized `TrieLog` bytes for `blockNumber`, if one was ever [[put]]. The caller (`trie`)
    * rehydrates via `TrieLog.deserialize` — this seam never decodes.
    */
  def get(blockNumber: BigInt): IO[Option[IndexedSeq[Byte]]] =
    IO(dataSource.get(Namespace.TrieLog, key(blockNumber)))

  /** Drops every `TrieLog` entry strictly below `belowBlock` — a single [[DataSource.deleteRange]], never a
    * point-delete loop (Iron Rule #1). `belowBlock` itself, and everything at or above it, is retained. A caller passes
    * the local reorg horizon (or an R7 safe-height composed from consumer `FinishedHeight`s, mirroring
    * [[PruningStore.prune]]'s `safeHeight` shape) so a `TrieLog` still needed by an in-flight reorg consumer is never
    * dropped out from under it.
    */
  def prune(belowBlock: BigInt): IO[Unit] = IO {
    val fromKey = ColdStore.encodeBlockNumber(0)
    val toKeyExclusive = ColdStore.encodeBlockNumber(belowBlock)
    dataSource.deleteRange(Namespace.TrieLog, fromKey, toKeyExclusive)
  }
