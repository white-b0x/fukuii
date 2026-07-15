package com.chipprbots.fukuii.storage

import cats.effect.IO

/** One frozen block's cold-shard payload — raw pre-encoded bytes, `storage` byte-pure (no header/body/receipt domain
  * types; the caller already RLP-encoded them via the layers above — DoD grep, `storage` imports nothing from
  * `com.chipprbots.fukuii.trie.*` and no domain block/account/receipt types). [[totalDifficulty]] is retained in every
  * frozen record — the load-bearing ETC PoW fork-choice invariant the living ETC freezer authority confirms: core-geth
  * `core/rawdb/ancient_scheme.go:35-36` `ChainFreezerDifficultyTable = "diffs"`, retained `:46`, appended
  * `chain_freezer.go:293`. The post-merge ETH freezer DROPS total difficulty — that is the wrong template for a PoW
  * successor; fukuii always retains it (harmless for a PoS network, required for a PoW one).
  */
final case class ColdBlockRecord(
    header: IndexedSeq[Byte],
    body: IndexedSeq[Byte],
    receipts: IndexedSeq[Byte],
    totalDifficulty: IndexedSeq[Byte]
)

/** The result of one [[ColdStore.freeze]] call. */
final case class FreezeReport(frozenCount: Int, lowestFrozen: Option[BigInt], highestFrozen: Option[BigInt])

/** The hot -> cold freezer seam (RX-L2-21, `historical-distribution.md` gap #11): a sealed, number-addressed
  * append-only store for block ranges that have fallen below the reorg-safe boundary. `RocksDb` is the sole `KvEngine`
  * inhabitant (`StorageProfile.engine`, RX-L2-27 OBSOLETE for MDBX/a second engine) — [[ColdStore]] is therefore an
  * IN-ENGINE analogue of a reference-client freezer (besu's BlobDB-per-static-segment shape, `L2.md` §4e), not a second
  * on-disk store: cold records live in their own dedicated, static-data-tagged column families
  * ([[Namespace.ColdHeader]]/[[Namespace.ColdBody]]/[[Namespace.ColdReceipts]]/[[Namespace.ColdChainWeight]]), keyed by
  * a fixed-width big-endian block number so ascending key order matches ascending block-number order — required for
  * [[shardBounds]]'s range to be a valid [[DataSource.deleteRange]] window.
  *
  * ==One fixed-block-range sharded format underneath==
  * `shardSize` blocks share one contiguous key range per cold namespace. This is the SAME sharding a later era1 export
  * and EIP-4444 expiry compose with (RX-L2-22/25, both deferred): a shard's entire key range is a single
  * [[DataSource.deleteRange]] away from being dropped whole — "a shard is a whole file" in a real flat-file freezer
  * becomes "a shard is one deleteRange window" in this in-engine analogue. [[ColdStore.expireShard]] exposes exactly
  * that mechanism (not a policy: no scheduling, no config key, no CLI verb — those are the deferred EIP-4444 occupancy,
  * L7/product).
  *
  * ==Write-time-boundary freeze, not a freeze pass (reth shape)==
  * [[freeze]] is called incrementally, at the moment a caller (L4/L5) determines a contiguous run of blocks has crossed
  * the reorg-safe boundary — never as a later sweep re-reading already-written hot data end to end. This avoids the
  * geth freeze-pass bug class (a separate freeze pass racing a concurrent hot-store compaction/delete). [[freeze]] is
  * idempotent per block number: re-freezing an already-frozen number overwrites it (a plain RocksDB upsert), never
  * errors — a caller that retries a partially-acknowledged freeze call cannot corrupt state.
  */
trait ColdStore:

  /** Freezes `records` (each a block number paired with its cold-shard payload) into the cold store. Order within the
    * `Seq` does not matter for correctness (each record is an independent upsert); callers SHOULD submit ascending
    * contiguous ranges (the write-time-boundary shape) so [[FreezeReport.lowestFrozen]] /
    * [[FreezeReport.highestFrozen]] track a gap-free frontier, but a non-contiguous or out-of-order `records` is not
    * itself an error here — gap detection across freeze calls is the caller's (L4/L5's) concern.
    */
  def freeze(records: Seq[(BigInt, ColdBlockRecord)]): IO[FreezeReport]

  /** Number-addressed read: the frozen record for `blockNumber`, if one has been [[freeze]]d. */
  def get(blockNumber: BigInt): Option[ColdBlockRecord]

  /** The lowest block number ever frozen, if any. */
  def lowestFrozen: Option[BigInt]

  /** The highest block number ever frozen, if any. */
  def highestFrozen: Option[BigInt]

  /** The fixed key-range boundary `[shardStart, shardEnd)` (in block numbers) that contains `blockNumber` — the unit
    * [[expireShard]] operates on.
    */
  def shardBounds(blockNumber: BigInt): (BigInt, BigInt)

  /** Drops every frozen record whose block number falls in `shardBounds(anyBlockInShard)` — a single
    * [[DataSource.deleteRange]] per cold namespace, never a point-delete loop (Iron Rule #1/DataSource contract). The
    * MECHANISM only: nothing here decides WHEN a shard becomes eligible for expiry (EIP-4444 policy, RX-L2-25, deferred
    * to L7/product).
    */
  def expireShard(anyBlockInShard: BigInt): IO[Unit]

  /** Encodes the full ERA1 epoch `epochIndex` (`Era1Shard.EpochSize` = 8192 blocks, ALWAYS — this is independent of
    * this instance's local expiry `shardSize`) as a byte-canonical shard FILE ([[Era1Shard.encodeShard]]) — the
    * distributable representation a later L7 torrent/WebSeed transport seeds verbatim. Requires every block in the
    * epoch to already be [[freeze]]d; raises [[Era1Shard.ShardIncompleteException]] otherwise (an incomplete epoch is
    * not a valid distributable shard). NOTE: `epochIndex` is an EPOCH INDEX (`blockNumber / Era1Shard.EpochSize`), not
    * a block number — use [[Era1Shard.epochIndexOf]] to convert.
    */
  def exportShard(epochIndex: BigInt, hash: IndexedSeq[Byte] => IndexedSeq[Byte]): IndexedSeq[Byte]

  /** Decodes a shard file ([[exportShard]]'s output, or one fetched from an untrusted peer) and [[freeze]]s its records
    * into this store — but ONLY after every check below passes. Raises before calling [[freeze]] on any failure — no
    * partial import of an unverified or mislabeled shard.
    *
    *   1. **Epoch-label check (F-S3b-2 — a real attack, not a hardening nicety).** `expectedEpochIndex` is the epoch
    *      the CALLER intends this shard to occupy (e.g. from the manifest slot it was fetched to fill); if the shard's
    *      OWN claimed epoch (decoded from its Version record) differs, [[Era1Shard.ShardEpochMismatchException]] is
    *      raised — even though a shard genuinely containing epoch N's blocks self-verifies (its accumulator commits to
    *      `(blockHash, TD)` pairs, which say nothing about WHICH epoch slot those blocks are meant to occupy) and can
    *      even match a caller-supplied `trustedRoot` for epoch N, a malicious peer could serve it mislabeled as epoch M
    *      to make genuine blocks land at the wrong block-number range, corrupting the number->hash index. The freeze
    *      keys are derived from `expectedEpochIndex` — the caller's own intent — never from the shard's self-declared
    *      epoch, even after this check passes (defense in depth: the decoded epoch is never the source of truth for
    *      where these bytes are written). 2. **Self-consistency.** The accumulator recomputed from the parsed records
    *      must match the root EMBEDDED in the file (tamper/corruption detection with no external input needed). 3.
    *      **Trust anchor (optional).** If the caller supplies `trustedRoot` (e.g. from a [[ShardManifest]] it already
    *      trusts), the embedded root must additionally match it.
    *
    * ==Caller contract (F-S3b-1) — what this DOES and does NOT verify==
    * This verifies header-chain identity (`blockHash = hash(header)`) and total difficulty against the trusted root
    * (and now the epoch label) — nothing more. It does NOT verify that the imported body/receipts bytes are the real
    * payload for that header: that is only transitively guaranteed via the header's OWN embedded roots (transactions
    * root, receipts root) and MUST be checked at the domain/importing layer (L4/`trie`/L7) before those bytes are
    * trusted for execution or serving. Do not treat a successful [[importShard]] from an untrusted transport as
    * sufficient on its own — body/receipts-vs-header-root verification is a separate, scheduled downstream check.
    *
    * ==`trustedRoot = None` is a trust-BOOTSTRAP path, not a safe default for untrusted sources==
    * `None` detects only INTERNAL corruption (the file's own accumulator vs. its own embedded root) — it does nothing
    * to defend against a peer serving a self-consistent but entirely fabricated shard. **L7 MUST supply `trustedRoot`
    * (from an already-trusted manifest/checkpoint chain) for any shard sourced from an untrusted peer** — `None` is
    * only appropriate when the caller has no independent root yet (e.g. the very first shard of a fresh bootstrap,
    * before any manifest has been validated) and accepts that narrower guarantee.
    */
  def importShard(
      bytes: IndexedSeq[Byte],
      expectedEpochIndex: BigInt,
      hash: IndexedSeq[Byte] => IndexedSeq[Byte],
      trustedRoot: Option[IndexedSeq[Byte]] = None
  ): IO[FreezeReport]

  /** The [[ShardAccumulator]] root for ERA1 epoch `epochIndex`, WITHOUT serializing the whole shard file — the cheap
    * primitive [[manifestEntry]] (and an L7 manifest-builder walking many epochs) uses. Same completeness requirement
    * and exception as [[exportShard]].
    */
  def shardAccumulatorRoot(epochIndex: BigInt, hash: IndexedSeq[Byte] => IndexedSeq[Byte]): IndexedSeq[Byte]

  /** Builds the [[ShardManifestEntry]] for ERA1 epoch `epochIndex` — L2 owns this FORMAT primitive; L7 assembles many
    * entries into a [[ShardManifest]] and turns it into torrent metainfo / WebSeed URLs.
    */
  def manifestEntry(epochIndex: BigInt, hash: IndexedSeq[Byte] => IndexedSeq[Byte]): ShardManifestEntry =
    val (start, endExclusive) = Era1Shard.epochBounds(epochIndex)
    ShardManifestEntry(epochIndex, start, endExclusive, shardAccumulatorRoot(epochIndex, hash))

object ColdStore:

  /** era1-style epoch size (go-ethereum/erigon precedent range; the exact value is a later tuning knob, not a
    * consensus-fixed constant — no reference-client freezer treats its shard size as chain-critical).
    */
  val DefaultShardSize: BigInt = 8192

  private[storage] val KeyWidth = 8

  /** Fixed-width big-endian encoding so ascending byte order == ascending block-number order (DataSource's
    * unsigned-lexicographic key-ordering contract, [[DataSource.scanRange]]/[[DataSource.deleteRange]]).
    */
  private[storage] def encodeBlockNumber(n: BigInt): Array[Byte] =
    require(n >= 0 && n <= Long.MaxValue, s"ColdStore block number out of encodable range: $n")
    val v = n.toLong
    Array(
      (v >>> 56).toByte,
      (v >>> 48).toByte,
      (v >>> 40).toByte,
      (v >>> 32).toByte,
      (v >>> 24).toByte,
      (v >>> 16).toByte,
      (v >>> 8).toByte,
      v.toByte
    )

  private[storage] def decodeBlockNumber(bytes: IndexedSeq[Byte]): BigInt =
    require(bytes.length == KeyWidth, s"ColdStore key must be $KeyWidth bytes, got ${bytes.length}")
    var v = 0L
    var i = 0
    while i < KeyWidth do
      v = (v << 8) | (bytes(i).toLong & 0xffL)
      i += 1
    BigInt(v)

/** [[ColdStore]] realized over dedicated column families of a [[DataSource]] (production: `RocksDbDataSource`;
  * tests/staging: `EphemDataSource` — both implement the same byte-pure contract). The lowest/highest frozen markers
  * ([[Namespace.ColdShardMeta]]) are updated in the SAME atomic batch as every cold-record write, so a crash
  * mid-`freeze` can never observe frozen block bytes without the bounds reflecting them (or vice versa) — Iron Rule #2,
  * batches are atomic.
  */
final class PersistedColdStore(dataSource: DataSource, shardSize: BigInt = ColdStore.DefaultShardSize)
    extends ColdStore:
  import ColdStore.*

  require(shardSize > 0, s"shardSize must be positive, got $shardSize")

  private val boundsKey: IndexedSeq[Byte] = "bounds".getBytes("UTF-8").toIndexedSeq

  private def encodeBounds(lowest: BigInt, highest: BigInt): IndexedSeq[Byte] =
    (encodeBlockNumber(lowest) ++ encodeBlockNumber(highest)).toIndexedSeq

  private def decodeBounds(bytes: IndexedSeq[Byte]): (BigInt, BigInt) =
    (decodeBlockNumber(bytes.slice(0, KeyWidth)), decodeBlockNumber(bytes.slice(KeyWidth, 2 * KeyWidth)))

  private def currentBounds(): Option[(BigInt, BigInt)] =
    dataSource.get(Namespace.ColdShardMeta, boundsKey).map(decodeBounds)

  override def freeze(records: Seq[(BigInt, ColdBlockRecord)]): IO[FreezeReport] = IO {
    val existing = currentBounds()
    if records.isEmpty then FreezeReport(0, existing.map(_._1), existing.map(_._2))
    else
      val headerUpserts = records.map { case (n, r) => encodeBlockNumber(n).toIndexedSeq -> r.header }
      val bodyUpserts = records.map { case (n, r) => encodeBlockNumber(n).toIndexedSeq -> r.body }
      val receiptUpserts = records.map { case (n, r) => encodeBlockNumber(n).toIndexedSeq -> r.receipts }
      val weightUpserts = records.map { case (n, r) => encodeBlockNumber(n).toIndexedSeq -> r.totalDifficulty }

      val numbers = records.map(_._1)
      val newLowest = (numbers ++ existing.map(_._1)).min
      val newHighest = (numbers ++ existing.map(_._2)).max

      dataSource.update(
        Seq(
          DataSourceUpdate(Namespace.ColdHeader, Nil, headerUpserts),
          DataSourceUpdate(Namespace.ColdBody, Nil, bodyUpserts),
          DataSourceUpdate(Namespace.ColdReceipts, Nil, receiptUpserts),
          DataSourceUpdate(Namespace.ColdChainWeight, Nil, weightUpserts),
          DataSourceUpdate(Namespace.ColdShardMeta, Nil, Seq(boundsKey -> encodeBounds(newLowest, newHighest)))
        )
      )
      FreezeReport(records.size, Some(newLowest), Some(newHighest))
  }

  override def get(blockNumber: BigInt): Option[ColdBlockRecord] =
    val key = encodeBlockNumber(blockNumber).toIndexedSeq
    for
      header <- dataSource.get(Namespace.ColdHeader, key)
      body <- dataSource.get(Namespace.ColdBody, key)
      receipts <- dataSource.get(Namespace.ColdReceipts, key)
      weight <- dataSource.get(Namespace.ColdChainWeight, key)
    yield ColdBlockRecord(header, body, receipts, weight)

  override def lowestFrozen: Option[BigInt] = currentBounds().map(_._1)

  override def highestFrozen: Option[BigInt] = currentBounds().map(_._2)

  override def shardBounds(blockNumber: BigInt): (BigInt, BigInt) =
    val shardIndex = blockNumber / shardSize
    (shardIndex * shardSize, (shardIndex + 1) * shardSize)

  override def expireShard(anyBlockInShard: BigInt): IO[Unit] = IO {
    val (start, endExclusive) = shardBounds(anyBlockInShard)
    val fromKey = encodeBlockNumber(start)
    val toKeyExclusive = encodeBlockNumber(endExclusive)
    dataSource.deleteRange(Namespace.ColdHeader, fromKey, toKeyExclusive)
    dataSource.deleteRange(Namespace.ColdBody, fromKey, toKeyExclusive)
    dataSource.deleteRange(Namespace.ColdReceipts, fromKey, toKeyExclusive)
    dataSource.deleteRange(Namespace.ColdChainWeight, fromKey, toKeyExclusive)
  }

  /** Reads every block in ERA1 epoch `epochIndex`, failing loud if any is missing — the shared completeness gate
    * [[exportShard]] and [[shardAccumulatorRoot]] both need before treating a range as a valid distributable epoch.
    */
  private def epochRecordsOrThrow(epochIndex: BigInt): IndexedSeq[(BigInt, ColdBlockRecord)] =
    val (start, endExclusive) = Era1Shard.epochBounds(epochIndex)
    (start.toLong until endExclusive.toLong).map { n =>
      val blockNumber = BigInt(n)
      blockNumber -> get(blockNumber).getOrElse(
        throw Era1Shard.ShardIncompleteException(
          s"block $blockNumber missing from cold store — cannot export incomplete ERA1 epoch $epochIndex " +
            s"[$start, $endExclusive)"
        )
      )
    }

  override def exportShard(epochIndex: BigInt, hash: IndexedSeq[Byte] => IndexedSeq[Byte]): IndexedSeq[Byte] =
    Era1Shard.encodeShard(epochIndex, epochRecordsOrThrow(epochIndex), hash)

  override def importShard(
      bytes: IndexedSeq[Byte],
      expectedEpochIndex: BigInt,
      hash: IndexedSeq[Byte] => IndexedSeq[Byte],
      trustedRoot: Option[IndexedSeq[Byte]]
  ): IO[FreezeReport] =
    val (claimedEpochIndex, records, embeddedRoot) = Era1Shard.decodeShard(bytes)
    if claimedEpochIndex != expectedEpochIndex then
      IO.raiseError(
        Era1Shard.ShardEpochMismatchException(
          s"shard claims epoch $claimedEpochIndex but caller expected epoch $expectedEpochIndex — refusing to freeze " +
            "genuine, self-verifying content under a mislabeled epoch (F-S3b-2: this would silently corrupt the " +
            "number-to-hash index even though both the shard's own accumulator and any supplied trustedRoot verify)"
        )
      )
    else
      val entries = records.map { case (_, r) => ShardAccumulatorEntry(hash(r.header), r.totalDifficulty) }
      val recomputedRoot = ShardAccumulator.build(entries, hash).root
      if recomputedRoot != embeddedRoot then
        IO.raiseError(
          Era1Shard.ShardTamperedException(
            s"shard content does not match its own embedded accumulator root: recomputed=$recomputedRoot, " +
              s"embedded=$embeddedRoot"
          )
        )
      else if trustedRoot.exists(_ != embeddedRoot) then
        IO.raiseError(
          Era1Shard.ShardTamperedException(
            s"shard's embedded accumulator root does not match the caller's trusted root: embedded=$embeddedRoot, " +
              s"trusted=${trustedRoot.get}"
          )
        )
      else
        // Freeze keys are derived from the CALLER's expected epoch, never the shard's own self-declared numbering
        // (defense in depth — even past the equality check above, the decoded epoch is never the source of truth
        // for where these bytes are written; see the class-level F-S3b-2 note).
        val (start, _) = Era1Shard.epochBounds(expectedEpochIndex)
        val remapped = records.zipWithIndex.map { case ((_, record), i) => (start + i) -> record }
        freeze(remapped)

  override def shardAccumulatorRoot(epochIndex: BigInt, hash: IndexedSeq[Byte] => IndexedSeq[Byte]): IndexedSeq[Byte] =
    val entries =
      epochRecordsOrThrow(epochIndex).map { case (_, r) => ShardAccumulatorEntry(hash(r.header), r.totalDifficulty) }
    ShardAccumulator.build(entries, hash).root
