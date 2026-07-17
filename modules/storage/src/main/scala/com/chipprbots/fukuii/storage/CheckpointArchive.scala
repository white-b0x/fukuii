package com.chipprbots.fukuii.storage

/** One accumulator leaf: a block's hash paired with its total difficulty — the era1-shaped `(hash, TD)` pair
  * go-ethereum's `internal/era/accumulator.go` commits to (RX-L2-23).
  */
final case class CheckpointEntry(blockHash: IndexedSeq[Byte], totalDifficulty: BigInt)

/** An accumulator-committed set of [[CheckpointEntry]] values, giving a content commitment stronger than a mere
  * transport-integrity check: a CRC32 trailer catches bit-flips in transit but says nothing about whether the content
  * is the content a peer/operator actually trusts. [[CheckpointAccumulator.build]]/[[CheckpointAccumulator.verify]]
  * fold `entries` into a single binary-Merkle [[root]] a caller can check against a known-good value BEFORE importing
  * anything.
  *
  * ==`storage` stays crypto-free (DAG Iron Rule)==
  * `storage` depends on `domain, common` only — no `crypto`. The fold needs a hash function; rather than adding a
  * `crypto` dependency to compute one, the hash is INJECTED by the caller, exactly as [[NodeCommit]]/ [[PruningStore]]
  * above are handed already-computed child-hash lists rather than deriving them by parsing a node. The caller (a future
  * L4/`trie`-adjacent export routine, which DOES depend on `crypto`) supplies `keccak256` (or whatever the network's
  * hash function is) as a plain `IndexedSeq[Byte] => IndexedSeq[Byte]`.
  */
final case class CheckpointAccumulator(root: IndexedSeq[Byte], entries: IndexedSeq[CheckpointEntry])

/** The pairwise binary fold both [[CheckpointAccumulator]] and [[ShardAccumulator]] (S3b's per-shard analogue,
  * `Era1Shard.scala`) build over (go-ethereum `internal/era/accumulator.go` shape): each interior node is `hash(left ++
  * right)` over the level below; an odd trailing node at any level is carried up to the next level UNCHANGED (not
  * duplicated) — duplicating the last node would make a genuinely-doubled entry indistinguishable from an odd-length
  * list, silently weakening the commitment. Shared here rather than duplicated per accumulator type: the fold itself is
  * identical, only each type's leaf-preimage convention differs.
  *
  * ==Scope note: whole-list-equality commitment only, no leaf/internal domain separation==
  * This fold does NOT tag leaf vs. interior nodes before hashing (no `0x00`/`0x01` domain-separation prefix, unlike
  * e.g. RFC 6962 certificate transparency logs). That is safe TODAY because every current caller
  * ([[CheckpointAccumulator.verify]], [[ShardAccumulator.verify]]) only ever recomputes the ENTIRE fold from the full
  * leaf list and compares the resulting root — a whole-list-equality check, never a Merkle INCLUSION proof (a short
  * authentication path proving one leaf belongs to a root without the verifier holding every other leaf). If an
  * inclusion-proof feature is ever added (e.g. an L7 light-client proof of "this one header is in this checkpoint"
  * without shipping the whole entry list), leaf/internal domain separation MUST be added first — without it, an
  * attacker who controls leaf content could craft a leaf whose bytes equal a valid interior-node preimage
  * (second-preimage / CVE-2012-2459-class Merkle-tree forgery), letting a single crafted leaf masquerade as an internal
  * subtree. Not exploitable via any code path in this module today; a required precondition before any future
  * partial/inclusion-proof consumer of this fold.
  */
private[storage] object MerkleFold:
  def apply(level: IndexedSeq[IndexedSeq[Byte]], hash: IndexedSeq[Byte] => IndexedSeq[Byte]): IndexedSeq[Byte] =
    if level.isEmpty then hash(IndexedSeq.empty)
    else
      var current = level
      while current.size > 1 do
        current = current
          .grouped(2)
          .map {
            case Seq(l, r) => hash(l ++ r)
            case Seq(l)    => l
          }
          .toIndexedSeq
      current.head

object CheckpointAccumulator:

  private def leafPreimage(e: CheckpointEntry): IndexedSeq[Byte] =
    e.blockHash ++ e.totalDifficulty.toByteArray.toIndexedSeq

  /** Builds the accumulator over `entries`, in the given order (order is part of what the root commits to — a
    * reordering of the same entries produces a different root, matching a Merkle accumulator's usual semantics).
    */
  def build(entries: IndexedSeq[CheckpointEntry], hash: IndexedSeq[Byte] => IndexedSeq[Byte]): CheckpointAccumulator =
    val leaves = entries.map(e => hash(leafPreimage(e)))
    CheckpointAccumulator(MerkleFold(leaves, hash), entries)

  /** Recomputes the fold over `accumulator.entries` and compares against `trustedRoot` — the pre-import content check
    * [[CheckpointArchive.importInto]] performs before touching any storage.
    */
  def verify(
      accumulator: CheckpointAccumulator,
      hash: IndexedSeq[Byte] => IndexedSeq[Byte],
      trustedRoot: IndexedSeq[Byte]
  ): Boolean =
    build(accumulator.entries, hash).root == trustedRoot

/** The state-pivot checkpoint archive (RX-L2-23, `historical-distribution.md` DEFAULT(state-pivot)): a pivot block
  * number, the accumulator commitment over the `(blockHash, TD)` chain of trust up to that pivot, and the opaque state
  * records needed to bootstrap a fresh datadir there. `storage` never inspects a record's payload shape — byte-pure,
  * DoD grep — a record is an opaque `(Namespace, key, value)` triple; the caller (a future `trie`/L4 export routine) is
  * the one that knows whether it holds a trie node or contract bytecode.
  *
  * ==Format now; sync-time engagement is L7==
  * [[CheckpointArchive.exportFrom]]/[[CheckpointArchive.importInto]] are the FORMAT and the atomic
  * write-through-the-storage-seam — NOT the sync-time driver (import-only-on-fresh-DB gating, config keys, the
  * resumable HTTP download). Those stay L7 (`plan/L2.md` §"Layer boundaries").
  */
final case class CheckpointArchive(
    pivotBlockNumber: BigInt,
    accumulator: CheckpointAccumulator,
    records: IndexedSeq[(Namespace, IndexedSeq[Byte], IndexedSeq[Byte])]
)

object CheckpointArchive:

  /** Raised by [[importInto]] when the archive's accumulator does not verify against the caller's trusted root. Raised
    * BEFORE any write is attempted — a malicious or corrupted checkpoint is rejected without touching `dataSource` at
    * all.
    */
  final case class CheckpointVerificationException(message: String) extends RuntimeException(message)

  /** Builds an archive: computes the accumulator over `entries` (injected `hash`), pairs it with the caller-supplied
    * state `records`. Content only — no CRC32/length-framing here, that stays a transport-layer concern (L7, the
    * resumable-download driver already has its own framing).
    */
  def exportFrom(
      pivotBlockNumber: BigInt,
      entries: IndexedSeq[CheckpointEntry],
      records: IndexedSeq[(Namespace, IndexedSeq[Byte], IndexedSeq[Byte])],
      hash: IndexedSeq[Byte] => IndexedSeq[Byte]
  ): CheckpointArchive =
    CheckpointArchive(pivotBlockNumber, CheckpointAccumulator.build(entries, hash), records)

  /** Verifies `archive.accumulator` against `trustedRoot` FIRST; only if it verifies does it apply every record as ONE
    * atomic [[DataSource.updateSync]] batch (Iron Rule #2 — batches are atomic, partial flushes corrupt state;
    * `updateSync` rather than `update` because a checkpoint import is exactly the "rare, durability-critical one-time
    * write" [[DataSource]]'s class docs call out). A crash mid-import therefore leaves the datadir exactly as it was
    * before the import started — never half-written — and a verification failure never writes a single byte.
    *
    * ==Caller contract (F-S3b-1) — what this DOES and does NOT verify==
    * This verifies header-chain identity (`blockHash`) and total difficulty in `archive.accumulator.entries` against
    * `trustedRoot` — nothing more. It does NOT verify that `archive.records` (the state trie nodes / bytecode this
    * checkpoint carries) is the real payload for that trusted header chain: that is only transitively guaranteed via
    * the pivot header's OWN embedded state root and MUST be checked at the domain/importing layer (L4/`trie`/L7) before
    * the imported state is trusted for execution or serving — e.g. rebuilding the state root from the imported trie
    * nodes and comparing it against the trusted pivot header's `stateRoot` field. Do not treat a successful
    * [[importInto]] from an untrusted transport as sufficient on its own — state-vs-pivot-stateRoot verification is a
    * separate, scheduled downstream check, not something this seam performs.
    */
  def importInto(
      dataSource: DataSource,
      archive: CheckpointArchive,
      trustedRoot: IndexedSeq[Byte],
      hash: IndexedSeq[Byte] => IndexedSeq[Byte]
  ): Unit =
    if !CheckpointAccumulator.verify(archive.accumulator, hash, trustedRoot) then
      throw CheckpointVerificationException(
        s"Checkpoint accumulator root does not match trusted root: computed=${archive.accumulator.root}, trusted=$trustedRoot"
      )
    val updates = archive.records
      .groupBy(_._1)
      .map { case (namespace, entries) =>
        DataSourceUpdate(namespace, Nil, entries.map { case (_, key, value) => key -> value })
      }
      .toSeq
    dataSource.updateSync(updates)

  /** Raised by [[decode]] on a structurally malformed byte stream (wrong tag, truncated record, unknown namespace id) —
    * a PARSE-time check, distinct from [[CheckpointVerificationException]]'s content-trust check.
    */
  final case class CheckpointFormatException(message: String) extends RuntimeException(message)

  /** Raised by [[encode]] when `archive.records` contains two entries for the same `(namespace, key)` pair — a
    * malformed archive (two values for one key) has no single canonical byte representation, so [[encode]] refuses to
    * silently pick one (last-sorted-wins would be an arbitrary, non-obvious resolution rule and would break the
    * byte-canonicity guarantee the moment two producers resolved the ambiguity differently).
    */
  final case class CheckpointDuplicateKeyException(message: String) extends RuntimeException(message)

  private val VersionTag: Byte = 1
  private val EntryTag: Byte = 2
  private val RecordTag: Byte = 3
  private val AccumulatorRootTag: Byte = 4

  private def unsignedCompare(a: IndexedSeq[Byte], b: IndexedSeq[Byte]): Int =
    val n = math.min(a.length, b.length)
    var i = 0
    var d = 0
    while i < n && d == 0 do
      d = (a(i) & 0xff) - (b(i) & 0xff)
      i += 1
    if d != 0 then d else a.length - b.length

  /** Sorts [[CheckpointArchive.records]] by `(namespace id, key)` — both compared unsigned, matching every other
    * key-ordering convention in this module ([[DataSource]]'s contract, [[EphemDataSource]]'s comparator,
    * [[ColdStore]]'s block-number keys). This is what makes [[encode]] byte-canonical regardless of the order `records`
    * happened to be built in (e.g. a differently-ordered `HashMap` traversal on two independent nodes).
    */
  private val canonicalRecordOrdering: Ordering[(Namespace, IndexedSeq[Byte], IndexedSeq[Byte])] =
    Ordering.fromLessThan { (x, y) =>
      val nsCompare = (x._1.id & 0xff) - (y._1.id & 0xff)
      if nsCompare != 0 then nsCompare < 0 else unsignedCompare(x._2, y._2) < 0
    }

  import Era1Shard.Codec.*

  /** Byte-canonical serialization (the operator-committed extension mirroring [[Era1Shard]]'s shard-file canonicity):
    * [[CheckpointArchive.records]] are sorted into [[canonicalRecordOrdering]] BEFORE encoding — so two
    * independently-built [[CheckpointArchive]] values over the same LOGICAL pivot (same accumulator entries, same
    * records, however each node happened to enumerate them) produce byte-identical output, the property a BitTorrent
    * infohash needs. [[CheckpointAccumulator.entries]] are NOT resorted: their order is already the meaningful,
    * deterministic ascending-chain order the accumulator's root commits to (see [[CheckpointAccumulator.build]]'s docs)
    * — reordering them would be a content change, not a canonicalization.
    *
    * Raises [[CheckpointDuplicateKeyException]] if two records share a `(namespace, key)` pair — checked cheaply
    * post-sort, since duplicates are adjacent once [[canonicalRecordOrdering]] is applied.
    */
  def encode(archive: CheckpointArchive): IndexedSeq[Byte] =
    val sortedRecords = archive.records.sorted(canonicalRecordOrdering)
    sortedRecords.sliding(2).foreach {
      case Seq((ns1, key1, _), (ns2, key2, _)) if ns1 == ns2 && key1 == key2 =>
        throw CheckpointDuplicateKeyException(
          s"duplicate checkpoint record for namespace=$ns1, key=$key1 — an archive cannot commit to a single " +
            "canonical byte representation with two values for the same key"
        )
      case _ => ()
    }
    val versionRecord = putRecord(
      VersionTag,
      putBlockNumber(archive.pivotBlockNumber) ++ putInt(archive.accumulator.entries.size) ++ putInt(
        sortedRecords.size
      )
    )
    val entryRecords = archive.accumulator.entries.map { e =>
      putRecord(EntryTag, putLengthPrefixed(e.blockHash.toArray) ++ putLengthPrefixed(e.totalDifficulty.toByteArray))
    }
    val dataRecords = sortedRecords.map { case (namespace, key, value) =>
      putRecord(RecordTag, Array(namespace.id) ++ putLengthPrefixed(key.toArray) ++ putLengthPrefixed(value.toArray))
    }
    val accumulatorRecord = putRecord(AccumulatorRootTag, archive.accumulator.root.toArray)
    versionRecord ++ entryRecords.flatten ++ dataRecords.flatten ++ accumulatorRecord

  /** Decodes [[encode]]'s output back into a [[CheckpointArchive]] — the inverse. Raises [[CheckpointFormatException]]
    * on any structural mismatch; does NOT itself check the embedded accumulator root against anything — that
    * content-trust check is [[importInto]]'s (or, for a standalone check before importing,
    * [[CheckpointAccumulator.verify]] against the decoded `accumulator`).
    */
  def decode(bytes: IndexedSeq[Byte]): CheckpointArchive =
    val cursor = Cursor(bytes)
    val (versionTag, versionPayload) = cursor.readRecord()
    if versionTag != VersionTag then
      throw CheckpointFormatException(s"expected Version record (tag=$VersionTag), got tag=$versionTag")
    val versionCursor = Cursor(versionPayload)
    val pivotBlockNumber = versionCursor.readBlockNumber()
    val entryCount = versionCursor.readInt()
    val recordCount = versionCursor.readInt()

    val entries = (0 until entryCount).map { _ =>
      val (tag, payload) = cursor.readRecord()
      if tag != EntryTag then throw CheckpointFormatException(s"expected Entry record (tag=$EntryTag), got tag=$tag")
      val entryCursor = Cursor(payload)
      val blockHash = entryCursor.readLengthPrefixed()
      val totalDifficulty = BigInt(entryCursor.readLengthPrefixed().toArray)
      CheckpointEntry(blockHash, totalDifficulty)
    }
    val records = (0 until recordCount).map { _ =>
      val (tag, payload) = cursor.readRecord()
      if tag != RecordTag then throw CheckpointFormatException(s"expected Record record (tag=$RecordTag), got tag=$tag")
      val namespaceId = payload(0)
      val namespace = Namespace.byId.getOrElse(
        namespaceId,
        throw CheckpointFormatException(s"unknown namespace id=$namespaceId in checkpoint record")
      )
      val recordCursor = Cursor(payload.drop(1))
      val key = recordCursor.readLengthPrefixed()
      val value = recordCursor.readLengthPrefixed()
      (namespace, key, value)
    }
    val (accTag, accRoot) = cursor.readRecord()
    if accTag != AccumulatorRootTag then
      throw CheckpointFormatException(s"expected AccumulatorRoot record (tag=$AccumulatorRootTag), got tag=$accTag")

    CheckpointArchive(pivotBlockNumber, CheckpointAccumulator(accRoot, entries), records)

  /** Builds the [[CheckpointManifestEntry]] for `archive`, tagged with a caller-assigned `checkpointId` (L2 doesn't
    * interpret it — L7 assigns and looks it up). Pairs with [[ColdStore.manifestEntry]] in the SAME [[ShardManifest]]
    * listing, so L7's torrent/HTTP layer serves era1 history shards and checkpoint state pivots uniformly.
    */
  def manifestEntry(checkpointId: IndexedSeq[Byte], archive: CheckpointArchive): CheckpointManifestEntry =
    CheckpointManifestEntry(archive.pivotBlockNumber, checkpointId, archive.accumulator.root)
