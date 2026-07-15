package com.chipprbots.fukuii.storage

/** One leaf of a shard's own accumulator: a block's hash paired with its RAW (caller-encoded, opaque) total difficulty
  * bytes — deliberately NOT [[CheckpointEntry]]'s `BigInt` (decoding [[ColdBlockRecord.totalDifficulty]] into a
  * `BigInt` would require `storage` to assume an encoding it never chose; concatenating the raw bytes into the leaf
  * preimage needs no such assumption).
  */
final case class ShardAccumulatorEntry(blockHash: IndexedSeq[Byte], totalDifficulty: IndexedSeq[Byte])

/** A single shard's own accumulator commitment over its `(blockHash, TD)` records (RX-L2-21/22's per-shard analogue of
  * [[CheckpointAccumulator]] — same [[MerkleFold]], different leaf-preimage convention) — so a shard fetched from an
  * untrusted torrent/WebSeed peer self-verifies against a known root BEFORE trust, exactly as [[CheckpointAccumulator]]
  * does for the state-pivot archive.
  */
final case class ShardAccumulator(root: IndexedSeq[Byte], entries: IndexedSeq[ShardAccumulatorEntry])

object ShardAccumulator:
  private def leafPreimage(e: ShardAccumulatorEntry): IndexedSeq[Byte] = e.blockHash ++ e.totalDifficulty

  def build(entries: IndexedSeq[ShardAccumulatorEntry], hash: IndexedSeq[Byte] => IndexedSeq[Byte]): ShardAccumulator =
    val leaves = entries.map(e => hash(leafPreimage(e)))
    ShardAccumulator(MerkleFold(leaves, hash), entries)

  def verify(
      accumulator: ShardAccumulator,
      hash: IndexedSeq[Byte] => IndexedSeq[Byte],
      trustedRoot: IndexedSeq[Byte]
  ): Boolean =
    build(accumulator.entries, hash).root == trustedRoot

/** One era1-history-shard entry of a [[ShardManifest]]: the listing L7 turns into torrent metainfo / WebSeed URLs.
  * `storage` owns only the FORMAT — L7 populates entries (as shards are exported) and distributes the manifest;
  * `storage` never talks to a torrent client, a WebSeed server, or the filesystem.
  */
final case class ShardManifestEntry(
    epochIndex: BigInt,
    rangeStart: BigInt,
    rangeEndExclusive: BigInt,
    accumulatorRoot: IndexedSeq[Byte]
)

/** One checkpoint-artifact entry of a [[ShardManifest]] (`CheckpointArchive.scala`'s state-pivot analogue of
  * [[ShardManifestEntry]]) — `(pivot-block -> checkpoint-id -> accumulator-root)`. `checkpointId` is opaque to
  * `storage`: L7 assigns it (and looks artifacts up by it) — this seam never interprets it.
  */
final case class CheckpointManifestEntry(
    pivotBlockNumber: BigInt,
    checkpointId: IndexedSeq[Byte],
    accumulatorRoot: IndexedSeq[Byte]
)

/** An ordered listing of history-shard and checkpoint-artifact entries — the single manifest L7's torrent/HTTP layer
  * serves BOTH kinds of distributable content from. Encoding is deterministic (fixed-width epoch/range fields,
  * length-prefixed ids/roots) for the same reason shard-file and checkpoint-archive encoding are: a manifest is itself
  * content two independent nodes must be able to produce/verify identically.
  */
final case class ShardManifest(
    shardEntries: IndexedSeq[ShardManifestEntry],
    checkpointEntries: IndexedSeq[CheckpointManifestEntry] = IndexedSeq.empty
)

object ShardManifest:
  import Era1Shard.Codec.*

  def encode(manifest: ShardManifest): IndexedSeq[Byte] =
    val shardBody = manifest.shardEntries.flatMap { e =>
      putBlockNumber(e.epochIndex) ++ putBlockNumber(e.rangeStart) ++ putBlockNumber(e.rangeEndExclusive) ++
        putLengthPrefixed(e.accumulatorRoot.toArray)
    }
    val checkpointBody = manifest.checkpointEntries.flatMap { e =>
      putBlockNumber(e.pivotBlockNumber) ++ putLengthPrefixed(e.checkpointId.toArray) ++
        putLengthPrefixed(e.accumulatorRoot.toArray)
    }
    (putInt(manifest.shardEntries.size) ++ shardBody ++ putInt(manifest.checkpointEntries.size) ++
      checkpointBody).toIndexedSeq

  def decode(bytes: IndexedSeq[Byte]): ShardManifest =
    val cursor = Cursor(bytes)
    val shardCount = cursor.readInt()
    val shardEntries = (0 until shardCount).map { _ =>
      val epochIndex = cursor.readBlockNumber()
      val rangeStart = cursor.readBlockNumber()
      val rangeEndExclusive = cursor.readBlockNumber()
      val root = cursor.readLengthPrefixed()
      ShardManifestEntry(epochIndex, rangeStart, rangeEndExclusive, root)
    }
    val checkpointCount = cursor.readInt()
    val checkpointEntries = (0 until checkpointCount).map { _ =>
      val pivotBlockNumber = cursor.readBlockNumber()
      val checkpointId = cursor.readLengthPrefixed()
      val root = cursor.readLengthPrefixed()
      CheckpointManifestEntry(pivotBlockNumber, checkpointId, root)
    }
    ShardManifest(shardEntries, checkpointEntries)

/** The byte-canonical, era1/E2Store-shaped shard-FILE container (RX-L2-21/22, the operator-committed L7 addition): a
  * fixed-block-range (one ERA1 8192-block epoch, [[Era1Shard.EpochSize]]) sequence of type-length-value records —
  * Version, then per-block Header/Body/Receipts/TotalDifficulty, then a trailing Accumulator record — so two
  * independent [[PersistedColdStore]] instances holding the same underlying block range produce BYTE-IDENTICAL output
  * for [[exportShard]] (the property a BitTorrent infohash needs: the torrent identity is over the file's exact bytes,
  * not a RocksDB SST, whose physical layout differs node-to-node even for identical logical content).
  *
  * ==Structured toward ERA1/E2Store, not yet full spec interop==
  * The TLV shape (tag + length + payload) and the record set (header/body/receipts/TD/accumulator) mirror go-ethereum's
  * `internal/era`/`e2store` container so a later convergence to the exact wire format is additive, not a rewrite. The
  * hard requirement THIS increment satisfies is byte-canonicity across independent producers, not bit-for-bit
  * conformance to the upstream E2Store tag/length encoding. A block-index trailer (ERA1's random-access offset table)
  * is deferred — "where practical" — as a non-load-bearing efficiency nicety; nothing here depends on it.
  */
object Era1Shard:

  /** The ERA1 epoch size — shard-FILE boundaries always align to this, independent of a [[PersistedColdStore]]'s own
    * (configurable) local expiry-shard `shardSize` (`ColdStore.shardBounds`/`expireShard`). The two axes are
    * deliberately decoupled: local expiry granularity is a operational tuning knob, era1-epoch alignment is an
    * interop/torrent-friendliness requirement that must not drift with it.
    */
  val EpochSize: BigInt = 8192

  /** The `[start, endExclusive)` block-number range `epochIndex` covers. */
  def epochBounds(epochIndex: BigInt): (BigInt, BigInt) = (epochIndex * EpochSize, (epochIndex + 1) * EpochSize)

  /** The epoch index containing `blockNumber`. */
  def epochIndexOf(blockNumber: BigInt): BigInt = blockNumber / EpochSize

  final case class ShardIncompleteException(message: String) extends RuntimeException(message)
  final case class ShardTamperedException(message: String) extends RuntimeException(message)
  final case class ShardFormatException(message: String) extends RuntimeException(message)

  /** Raised by [[PersistedColdStore.importShard]] when a shard's self-declared epoch (its Version record) differs from
    * the caller's expected epoch (F-S3b-2) — a genuine, self-verifying shard mislabeled onto the wrong block-number
    * range is a real attack, not merely malformed input: the accumulator commits to `(blockHash, TD)` pairs, never to
    * WHICH epoch slot they belong, so neither self-consistency nor a matching `trustedRoot` catches a relabeled shard
    * on their own.
    */
  final case class ShardEpochMismatchException(message: String) extends RuntimeException(message)

  private val VersionTag: Byte = 1
  private val HeaderTag: Byte = 2
  private val BodyTag: Byte = 3
  private val ReceiptsTag: Byte = 4
  private val TotalDifficultyTag: Byte = 5
  private val AccumulatorTag: Byte = 6

  /** Low-level TLV primitives shared with [[ShardManifest]]'s codec — fixed-width integers, big-endian, no
    * container-dependent (e.g. `HashMap` iteration order) encoding anywhere in this object.
    */
  private[storage] object Codec:
    def putInt(n: Int): Array[Byte] =
      Array((n >>> 24).toByte, (n >>> 16).toByte, (n >>> 8).toByte, n.toByte)

    def putBlockNumber(n: BigInt): Array[Byte] = ColdStore.encodeBlockNumber(n)

    def putLengthPrefixed(bytes: Array[Byte]): Array[Byte] = putInt(bytes.length) ++ bytes

    def putRecord(tag: Byte, payload: Array[Byte]): IndexedSeq[Byte] =
      (Array(tag) ++ putInt(payload.length) ++ payload).toIndexedSeq

    final class Cursor(bytes: IndexedSeq[Byte]):
      private var pos: Int = 0

      def readInt(): Int =
        val v =
          ((bytes(pos).toInt & 0xff) << 24) | ((bytes(pos + 1).toInt & 0xff) << 16) |
            ((bytes(pos + 2).toInt & 0xff) << 8) | (bytes(pos + 3).toInt & 0xff)
        pos += 4
        v

      def readBlockNumber(): BigInt =
        val v = ColdStore.decodeBlockNumber(bytes.slice(pos, pos + ColdStore.KeyWidth))
        pos += ColdStore.KeyWidth
        v

      def readLengthPrefixed(): IndexedSeq[Byte] =
        val len = readInt()
        val v = bytes.slice(pos, pos + len)
        pos += len
        v

      def readRecord(): (Byte, IndexedSeq[Byte]) =
        val tag = bytes(pos)
        pos += 1
        (tag, readLengthPrefixed())

  import Codec.*

  private def requireTag(actual: Byte, expected: Byte, what: String): Unit =
    if actual != expected then throw ShardFormatException(s"expected $what record (tag=$expected), got tag=$actual")

  /** Encodes `records` (one full ERA1 epoch's worth, already validated complete by the caller — [[ColdStore]] checks
    * this before calling in) as a byte-canonical shard file, with a trailing per-shard [[ShardAccumulator]] whose
    * entries are `(hash(header), totalDifficulty)` — the block hash is derived by hashing the header bytes (the same
    * relationship every reference client's block hash has to its header), never a separately-supplied field, so
    * [[ColdBlockRecord]] needs no additional field to support this.
    */
  def encodeShard(
      epochIndex: BigInt,
      records: IndexedSeq[(BigInt, ColdBlockRecord)],
      hash: IndexedSeq[Byte] => IndexedSeq[Byte]
  ): IndexedSeq[Byte] =
    val versionRecord = putRecord(VersionTag, putBlockNumber(epochIndex) ++ putInt(records.size))
    val blockRecords = records.flatMap { case (_, r) =>
      Seq(
        putRecord(HeaderTag, r.header.toArray),
        putRecord(BodyTag, r.body.toArray),
        putRecord(ReceiptsTag, r.receipts.toArray),
        putRecord(TotalDifficultyTag, r.totalDifficulty.toArray)
      )
    }
    val entries = records.map { case (_, r) => ShardAccumulatorEntry(hash(r.header), r.totalDifficulty) }
    val accumulatorRecord = putRecord(AccumulatorTag, ShardAccumulator.build(entries, hash).root.toArray)
    versionRecord ++ blockRecords.flatten ++ accumulatorRecord

  /** Decodes a shard file back into `(epochIndex, records, embeddedAccumulatorRoot)` — the inverse of [[encodeShard]].
    * Raises [[ShardFormatException]] on any structural mismatch (wrong tag, truncated record); this is a parse-time
    * check, NOT the content-tampering check ([[ShardAccumulator.verify]] against the returned root is the caller's job
    * — see [[PersistedColdStore.importShard]]).
    */
  def decodeShard(bytes: IndexedSeq[Byte]): (BigInt, IndexedSeq[(BigInt, ColdBlockRecord)], IndexedSeq[Byte]) =
    val cursor = Cursor(bytes)
    val (versionTag, versionPayload) = cursor.readRecord()
    requireTag(versionTag, VersionTag, "Version")
    val versionCursor = Cursor(versionPayload)
    val epochIndex = versionCursor.readBlockNumber()
    val blockCount = versionCursor.readInt()
    val epochStart = epochBounds(epochIndex)._1

    val records = (0 until blockCount).map { i =>
      val (t1, header) = cursor.readRecord(); requireTag(t1, HeaderTag, "Header")
      val (t2, body) = cursor.readRecord(); requireTag(t2, BodyTag, "Body")
      val (t3, receipts) = cursor.readRecord(); requireTag(t3, ReceiptsTag, "Receipts")
      val (t4, td) = cursor.readRecord(); requireTag(t4, TotalDifficultyTag, "TotalDifficulty")
      (epochStart + i) -> ColdBlockRecord(header, body, receipts, td)
    }
    val (accTag, accRoot) = cursor.readRecord()
    requireTag(accTag, AccumulatorTag, "Accumulator")
    (epochIndex, records, accRoot)
