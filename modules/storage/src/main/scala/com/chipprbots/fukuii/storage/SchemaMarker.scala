package com.chipprbots.fukuii.storage

/** The on-disk schema format tag (besu `BaseVersionedStorageFormat` analogue). One case for now; a future format change
  * adds a case here, never edits `V1`'s meaning in place.
  */
enum StorageFormat:
  case V1

/** The record [[SchemaMarker]] persists: `(format, version, StorageProfile)`. `version` is a bump counter for
  * within-`V1` schema evolutions that don't change the storage-approach shape (a CF's key layout changing, e.g.) —
  * orthogonal to [[StorageFormat]], which marks a shape change.
  */
final case class SchemaMarker(format: StorageFormat, version: Int, profile: StorageProfile)

/** Explicit, persisted, checked-at-open schema versioning (besu `DATABASE_METADATA.json` +
  * `BaseVersionedStorageFormat`, extended to carry the active [[StorageProfile]] per reth's 3-marker / nethermind
  * auto-detect shape). Written once to the dedicated `Namespace.SchemaMeta` column family on a fresh datadir; on every
  * subsequent open, [[ensureCompatible]] must be the FIRST thing checked, before any other column family is touched — a
  * datadir built under one profile that is reopened under a mismatched profile fails loud with a typed
  * [[SchemaMismatchException]], never RocksDB undefined behavior (e.g. a missing/extra column-family handle).
  *
  * ==Two independent checks, not one scalar equality==
  * Because the resolved profile also gates which column families are open (besu
  * `KeyValueSegmentIdentifier.includeInDatabaseFormat` format-gating — `StorageProfile.namespacesFor`),
  * [[ensureCompatible]] reconciles BOTH: (1) the caller-supplied `openNamespaces` against
  * `StorageProfile.namespacesFor(resolvedProfile)` — a profile whose CF set doesn't match what's actually open fails
  * here, structurally, even if no marker has ever been written; and (2) the persisted marker (once one exists) against
  * `(format, version, resolvedProfile)` — a profile that matches the open CF set but doesn't match a *previously
  * recorded* marker (e.g. reopening a path-keyed datadir under a hash-keyed profile request) fails here too.
  */
object SchemaMarker:

  final case class SchemaMismatchException(message: String) extends RuntimeException(message)

  private val Key: IndexedSeq[Byte] = IndexedSeq(0.toByte)

  private def encodeProfile(p: StorageProfile): Array[Byte] =
    Array(
      p.keying.ordinal.toByte,
      p.pruning.ordinal.toByte,
      p.flat.ordinal.toByte,
      p.freezer.ordinal.toByte,
      p.expiry.ordinal.toByte,
      p.engine.ordinal.toByte
    )

  private def decodeProfile(bytes: IndexedSeq[Byte]): StorageProfile =
    StorageProfile(
      NodeKeying.fromOrdinal(bytes(0).toInt),
      PruningMode.fromOrdinal(bytes(1).toInt),
      FlatAccelerator.fromOrdinal(bytes(2).toInt),
      ColdStoreMode.fromOrdinal(bytes(3).toInt),
      HistoryExpiry.fromOrdinal(bytes(4).toInt),
      KvEngine.fromOrdinal(bytes(5).toInt)
    )

  def encode(marker: SchemaMarker): Array[Byte] =
    val v = marker.version
    val versionBytes = Array((v >>> 24).toByte, (v >>> 16).toByte, (v >>> 8).toByte, v.toByte)
    Array(marker.format.ordinal.toByte) ++ versionBytes ++ encodeProfile(marker.profile)

  def decode(bytes: IndexedSeq[Byte]): SchemaMarker =
    val format = StorageFormat.fromOrdinal(bytes(0).toInt)
    val version =
      ((bytes(1).toInt & 0xff) << 24) | ((bytes(2).toInt & 0xff) << 16) | ((bytes(3).toInt & 0xff) << 8) |
        (bytes(4).toInt & 0xff)
    SchemaMarker(format, version, decodeProfile(bytes.drop(5)))

  /** Checked-at-open entry point. On a fresh datadir (no marker present yet) this writes the marker and returns; on a
    * datadir that already carries one, it is a read-only comparison. See the class-level "Two independent checks" note
    * for what each half of the check catches.
    */
  def ensureCompatible(
      dataSource: DataSource,
      openNamespaces: Set[Namespace],
      resolvedProfile: StorageProfile,
      format: StorageFormat = StorageFormat.V1,
      version: Int = 1
  ): Unit =
    val required = StorageProfile.namespacesFor(resolvedProfile)
    if openNamespaces != required then
      throw SchemaMismatchException(
        s"Opened column-family set does not match profile $resolvedProfile: opened=$openNamespaces, required=$required"
      )
    dataSource.get(Namespace.SchemaMeta, Key) match
      case None =>
        val marker = SchemaMarker(format, version, resolvedProfile)
        dataSource.update(Seq(DataSourceUpdate(Namespace.SchemaMeta, Nil, Seq(Key -> encode(marker).toIndexedSeq))))
      case Some(bytes) =>
        val stored = decode(bytes)
        val requested = SchemaMarker(format, version, resolvedProfile)
        if stored != requested then
          throw SchemaMismatchException(s"Datadir schema marker mismatch: stored=$stored, requested=$requested")
