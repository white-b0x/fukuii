package com.chipprbots.fukuii.storage

import org.scalatest.funsuite.AnyFunSuite

class CheckpointArchiveSpec extends AnyFunSuite:

  // A tiny synthetic hash (NOT keccak — `storage` has no `crypto` dependency, DAG Iron Rule): additive-fold with a
  // fixed length, sufficient to exercise the accumulator's structure without depending on a real digest.
  private def hash(bytes: IndexedSeq[Byte]): IndexedSeq[Byte] =
    val sum = bytes.foldLeft(0)((acc, b) => (acc * 31 + b.toInt) & 0xff)
    IndexedSeq(sum.toByte, (sum ^ 0xff).toByte)

  private def entry(tag: Byte, td: Int): CheckpointEntry =
    CheckpointEntry(IndexedSeq.fill(31)(0.toByte) :+ tag, BigInt(td))

  private val entries: IndexedSeq[CheckpointEntry] = IndexedSeq(entry(1, 100), entry(2, 250), entry(3, 400))
  private val stateRoot: IndexedSeq[Byte] = IndexedSeq(0xaa.toByte, 0xbb.toByte)

  private val records: IndexedSeq[(Namespace, IndexedSeq[Byte], IndexedSeq[Byte])] =
    IndexedSeq(
      (Namespace.Node, IndexedSeq(1.toByte), stateRoot),
      (Namespace.Code, IndexedSeq(2.toByte), IndexedSeq(0xc0.toByte))
    )

  test("CheckpointAccumulator.build is deterministic and order-sensitive"):
    val a = CheckpointAccumulator.build(entries, hash)
    val b = CheckpointAccumulator.build(entries, hash)
    assert(a.root == b.root)
    val reordered = CheckpointAccumulator.build(entries.reverse, hash)
    assert(reordered.root != a.root)

  test("CheckpointAccumulator.verify accepts the correct root and rejects a wrong one"):
    val acc = CheckpointAccumulator.build(entries, hash)
    assert(CheckpointAccumulator.verify(acc, hash, acc.root))
    assert(!CheckpointAccumulator.verify(acc, hash, IndexedSeq(0.toByte, 0.toByte)))

  test("export -> import round-trips every state record byte-exact when the trusted root matches"):
    val archive = CheckpointArchive.exportFrom(pivotBlockNumber = 42, entries, records, hash)
    val ds = EphemDataSource()

    CheckpointArchive.importInto(ds, archive, archive.accumulator.root, hash)

    assert(ds.get(Namespace.Node, IndexedSeq(1.toByte)).contains(stateRoot))
    assert(ds.get(Namespace.Code, IndexedSeq(2.toByte)).contains(IndexedSeq(0xc0.toByte)))

  test("import rejects a mismatched trusted root BEFORE writing a single record (fail-closed)"):
    val archive = CheckpointArchive.exportFrom(pivotBlockNumber = 42, entries, records, hash)
    val ds = EphemDataSource()
    val wrongRoot = IndexedSeq(1.toByte, 2.toByte, 3.toByte)

    val ex = intercept[CheckpointArchive.CheckpointVerificationException] {
      CheckpointArchive.importInto(ds, archive, wrongRoot, hash)
    }
    assert(ex.getMessage.contains("does not match trusted root"))
    // Fail-closed: the datadir is untouched, not partially populated.
    assert(ds.get(Namespace.Node, IndexedSeq(1.toByte)).isEmpty)
    assert(ds.get(Namespace.Code, IndexedSeq(2.toByte)).isEmpty)

  test("import applies every record as one atomic batch spanning multiple namespaces"):
    val archive = CheckpointArchive.exportFrom(pivotBlockNumber = 7, entries, records, hash)
    val ds = EphemDataSource()
    CheckpointArchive.importInto(ds, archive, archive.accumulator.root, hash)
    // Both namespaces' records are visible together — never one without the other (the atomicity a crash
    // mid-import must preserve; DataSource.update/updateSync back this with a single native batch).
    assert(ds.get(Namespace.Node, IndexedSeq(1.toByte)).isDefined)
    assert(ds.get(Namespace.Code, IndexedSeq(2.toByte)).isDefined)

  // -- Byte-canonical archive encoding (operator-committed extension: checkpoints distributed via BitTorrent/HTTP,
  // same infrastructure as the era1 history shards) ---------------------------------------------------------------

  test("encode -> decode round-trips a CheckpointArchive exactly"):
    val archive = CheckpointArchive.exportFrom(pivotBlockNumber = 42, entries, records, hash)
    val decoded = CheckpointArchive.decode(CheckpointArchive.encode(archive))
    assert(decoded.pivotBlockNumber == archive.pivotBlockNumber)
    assert(decoded.accumulator.root == archive.accumulator.root)
    assert(decoded.accumulator.entries == archive.accumulator.entries)
    // records are canonically SORTED by encode, so compare as sets, not as the original (unsorted) input order.
    assert(decoded.records.toSet == archive.records.toSet)

  test("two independent exports of the same checkpoint pivot produce BYTE-IDENTICAL archive bytes (canonicity)"):
    // The load-bearing property: a BitTorrent infohash is over the file's exact bytes. Build the "same logical
    // checkpoint" from records supplied in two DIFFERENT orders (simulating two nodes whose staging structures
    // happened to enumerate the same content differently) and confirm encode() still agrees byte-for-byte.
    val recordsForwardOrder = records
    val recordsReverseOrder = records.reverse

    val archiveA = CheckpointArchive.exportFrom(pivotBlockNumber = 99, entries, recordsForwardOrder, hash)
    val archiveB = CheckpointArchive.exportFrom(pivotBlockNumber = 99, entries, recordsReverseOrder, hash)

    assert(CheckpointArchive.encode(archiveA) == CheckpointArchive.encode(archiveB))

  test(
    "the accumulator rejects a tampered checkpoint (a corrupted chain-of-trust entry no longer matches the root the good copy committed to)"
  ):
    val archive = CheckpointArchive.exportFrom(pivotBlockNumber = 42, entries, records, hash)
    val goodBytes = CheckpointArchive.encode(archive)
    // The trusted root is held OUT OF BAND (e.g. from a ShardManifest an operator already trusts) — exactly the
    // untrusted-torrent-peer scenario: a peer hands over bytes, the root to check them against is already known.
    val trustedRoot = archive.accumulator.root

    // A good encoding verifies against the known-good root.
    val decodedGood = CheckpointArchive.decode(goodBytes)
    assert(CheckpointAccumulator.verify(decodedGood.accumulator, hash, trustedRoot))

    // Simulate a peer serving a checkpoint with one corrupted chain-of-trust entry (a flipped blockHash byte) —
    // re-encoded so the "tampered" bytes are a structurally valid, decodable container, exactly what a real
    // corrupted/malicious transfer would look like on the wire.
    val tamperedEntries =
      entries.updated(0, entries.head.copy(blockHash = entries.head.blockHash.updated(0, 0xff.toByte)))
    val tamperedArchive = CheckpointArchive.exportFrom(pivotBlockNumber = 42, tamperedEntries, records, hash)
    val decodedTampered = CheckpointArchive.decode(CheckpointArchive.encode(tamperedArchive))

    assert(!CheckpointAccumulator.verify(decodedTampered.accumulator, hash, trustedRoot))
    val ex = intercept[CheckpointArchive.CheckpointVerificationException] {
      CheckpointArchive.importInto(EphemDataSource(), decodedTampered, trustedRoot, hash)
    }
    assert(ex.getMessage.contains("does not match trusted root"))

  test("encode rejects an archive with two records for the same (namespace, key)"):
    val duplicateKeyRecords = records :+ (Namespace.Node, IndexedSeq(1.toByte), IndexedSeq(0xff.toByte))
    val archive = CheckpointArchive.exportFrom(pivotBlockNumber = 42, entries, duplicateKeyRecords, hash)
    val ex = intercept[CheckpointArchive.CheckpointDuplicateKeyException] {
      CheckpointArchive.encode(archive)
    }
    assert(ex.getMessage.contains("duplicate checkpoint record"))

  // -- Manifest listing (checkpoints alongside era1 history shards) -----------------------------------------------

  test("CheckpointArchive.manifestEntry carries the pivot, caller-supplied id, and the archive's own root"):
    val archive = CheckpointArchive.exportFrom(pivotBlockNumber = 42, entries, records, hash)
    val checkpointId = IndexedSeq(0xc0.toByte, 0xde.toByte)
    val manifestEntry = CheckpointArchive.manifestEntry(checkpointId, archive)

    assert(manifestEntry.pivotBlockNumber == BigInt(42))
    assert(manifestEntry.checkpointId == checkpointId)
    assert(manifestEntry.accumulatorRoot == archive.accumulator.root)

  test("ShardManifest carries checkpoint entries alongside shard entries and round-trips both"):
    val archive = CheckpointArchive.exportFrom(pivotBlockNumber = 42, entries, records, hash)
    val checkpointEntry = CheckpointArchive.manifestEntry(IndexedSeq(1.toByte), archive)
    val shardEntry = ShardManifestEntry(BigInt(0), BigInt(0), BigInt(8192), IndexedSeq(9.toByte))

    val manifest = ShardManifest(IndexedSeq(shardEntry), IndexedSeq(checkpointEntry))
    assert(ShardManifest.decode(ShardManifest.encode(manifest)) == manifest)
