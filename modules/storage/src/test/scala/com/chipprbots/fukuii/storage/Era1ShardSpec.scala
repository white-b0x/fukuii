package com.chipprbots.fukuii.storage

import cats.effect.unsafe.implicits.global

import org.scalatest.funsuite.AnyFunSuite

class Era1ShardSpec extends AnyFunSuite:

  // A tiny synthetic hash (NOT keccak — `storage` has no `crypto` dependency, DAG Iron Rule): additive-fold with a
  // fixed length, sufficient to exercise the accumulator/shard structure without depending on a real digest.
  private def hash(bytes: IndexedSeq[Byte]): IndexedSeq[Byte] =
    val sum = bytes.foldLeft(0)((acc, b) => (acc * 31 + b.toInt) & 0xffff)
    IndexedSeq((sum >>> 8).toByte, sum.toByte)

  private def record(n: Long): ColdBlockRecord =
    val tag = (n % 251).toByte // stays a valid single Byte across the full epoch range
    ColdBlockRecord(
      header = IndexedSeq(tag, 1, (n & 0xff).toByte),
      body = IndexedSeq(tag, 2),
      receipts = IndexedSeq(tag, 3),
      totalDifficulty = IndexedSeq(tag, 4, ((n >>> 8) & 0xff).toByte)
    )

  private def freezeFullEpoch(epochIndex: BigInt): PersistedColdStore =
    val store = new PersistedColdStore(EphemDataSource())
    val (start, endExclusive) = Era1Shard.epochBounds(epochIndex)
    val records = (start.toLong until endExclusive.toLong).map(n => BigInt(n) -> record(n))
    store.freeze(records).unsafeRunSync()
    store

  test("Era1Shard.epochBounds/epochIndexOf are epoch-aligned to the 8192-block ERA1 epoch"):
    assert(Era1Shard.EpochSize == BigInt(8192))
    assert(Era1Shard.epochBounds(0) == (BigInt(0), BigInt(8192)))
    assert(Era1Shard.epochBounds(1) == (BigInt(8192), BigInt(16384)))
    assert(Era1Shard.epochIndexOf(0) == BigInt(0))
    assert(Era1Shard.epochIndexOf(8191) == BigInt(0))
    assert(Era1Shard.epochIndexOf(8192) == BigInt(1))

  test("exportShard requires a COMPLETE epoch — an incomplete range raises ShardIncompleteException"):
    val store = new PersistedColdStore(EphemDataSource())
    store.freeze(Seq(BigInt(0) -> record(0))).unsafeRunSync() // only block 0 of epoch 0's 8192 blocks
    intercept[Era1Shard.ShardIncompleteException] {
      store.exportShard(0, hash)
    }

  test("export -> import round-trips a full epoch byte-identically (via re-export after import)"):
    val store = freezeFullEpoch(0)
    val shardBytes = store.exportShard(0, hash)

    val imported = new PersistedColdStore(EphemDataSource())
    imported.importShard(shardBytes, expectedEpochIndex = 0, hash).unsafeRunSync()

    val reExported = imported.exportShard(0, hash)
    assert(reExported == shardBytes)
    // And every individual record round-trips.
    val (start, endExclusive) = Era1Shard.epochBounds(0)
    (start.toLong until endExclusive.toLong).foreach(n => assert(imported.get(BigInt(n)).contains(record(n))))

  test("two independent ColdStores over the same block range export BYTE-IDENTICAL shard files (canonicity)"):
    // The load-bearing property: a BitTorrent infohash is over the file's exact bytes, so two nodes that froze the
    // same logical range independently must produce identical output, with no shared state between them at all.
    val storeA = freezeFullEpoch(1)
    val storeB = freezeFullEpoch(1)
    assert(storeA.exportShard(1, hash) == storeB.exportShard(1, hash))

  test("the per-shard accumulator verifies a good shard and rejects a tampered one"):
    val store = freezeFullEpoch(2)
    val shardBytes = store.exportShard(2, hash)

    // A good shard imports cleanly.
    val goodImporter = new PersistedColdStore(EphemDataSource())
    goodImporter.importShard(shardBytes, expectedEpochIndex = 2, hash).unsafeRunSync()
    assert(goodImporter.get(Era1Shard.epochBounds(2)._1).isDefined)

    // Flip the very last byte — the tail of the embedded accumulator root itself (tag(1)+length(4)+root(N) is the
    // final record in the container). This corrupts the STORED root while leaving every block record untouched, so
    // the recomputed root (built fresh from the parsed header/TD content) is guaranteed to disagree with it —
    // exactly the "corrupted/malicious peer" scenario importShard must reject.
    val tamperIndex = shardBytes.length - 1
    val tampered = shardBytes.updated(tamperIndex, (shardBytes(tamperIndex) ^ 0xff).toByte)

    val ex = intercept[Era1Shard.ShardTamperedException] {
      new PersistedColdStore(EphemDataSource()).importShard(tampered, expectedEpochIndex = 2, hash).unsafeRunSync()
    }
    assert(ex.getMessage.contains("does not match its own embedded accumulator root"))

  test(
    "importShard also rejects a shard whose (self-consistent) embedded root doesn't match a caller-supplied trusted root"
  ):
    val store = freezeFullEpoch(3)
    val shardBytes = store.exportShard(3, hash)
    val wrongTrustedRoot = IndexedSeq(0.toByte, 0.toByte)

    val ex = intercept[Era1Shard.ShardTamperedException] {
      new PersistedColdStore(EphemDataSource())
        .importShard(shardBytes, expectedEpochIndex = 3, hash, Some(wrongTrustedRoot))
        .unsafeRunSync()
    }
    assert(ex.getMessage.contains("trusted root"))

    // The correct trusted root (the manifest-driven happy path) succeeds.
    val (_, _, embeddedRoot) = Era1Shard.decodeShard(shardBytes)
    new PersistedColdStore(EphemDataSource())
      .importShard(shardBytes, expectedEpochIndex = 3, hash, Some(embeddedRoot))
      .unsafeRunSync()

  test(
    "importShard REJECTS a shard whose content genuinely matches trustedRoot but is labeled a DIFFERENT epoch " +
      "(F-S3b-2 — a real attack, not a hardening nicety)"
  ):
    // Epoch 7's genuine blocks, self-verifying AND matching a trustedRoot for epoch 7 — but a malicious peer could
    // serve these exact bytes while claiming they are epoch 8. Simulate that by re-labeling the encoded bytes to a
    // different epoch index without changing any block content, and confirm the caller's expectation (epoch 7) is
    // what's checked, not merely "does it verify against a root for SOME epoch".
    val store = freezeFullEpoch(7)
    val shardBytes = store.exportShard(7, hash)
    val (_, records, embeddedRoot) = Era1Shard.decodeShard(shardBytes)

    // Re-encode the SAME record content (same blockHash/TD -> same accumulator root) but mislabeled as epoch 8's
    // Version record — exactly what a malicious peer relabeling a genuine shard would send.
    val relabeledAsEpoch8 = Era1Shard.encodeShard(epochIndex = 8, records, hash)
    val (relabeledClaim, _, relabeledRoot) = Era1Shard.decodeShard(relabeledAsEpoch8)
    assert(relabeledClaim == BigInt(8)) // sanity: the mislabel took
    assert(relabeledRoot == embeddedRoot) // sanity: content (and thus the accumulator) is untouched

    // The caller expected epoch 7 (e.g. filling manifest slot 7) and even supplies epoch 7's trustedRoot — both the
    // shard's own self-consistency AND the external trust anchor would pass if the epoch label were not checked.
    val ex = intercept[Era1Shard.ShardEpochMismatchException] {
      new PersistedColdStore(EphemDataSource())
        .importShard(relabeledAsEpoch8, expectedEpochIndex = 7, hash, Some(embeddedRoot))
        .unsafeRunSync()
    }
    assert(ex.getMessage.contains("claims epoch 8"))
    assert(ex.getMessage.contains("expected epoch 7"))

    // The SAME bytes import cleanly when the caller's expectation matches the shard's actual label (epoch 8).
    new PersistedColdStore(EphemDataSource())
      .importShard(relabeledAsEpoch8, expectedEpochIndex = 8, hash, Some(embeddedRoot))
      .unsafeRunSync()

  test("manifestEntry reports the epoch-aligned range and a root matching a directly-exported shard's own root"):
    val store = freezeFullEpoch(4)
    val (_, _, embeddedRoot) = Era1Shard.decodeShard(store.exportShard(4, hash))
    val entry = store.manifestEntry(4, hash)

    assert(entry.epochIndex == BigInt(4))
    assert((entry.rangeStart, entry.rangeEndExclusive) == Era1Shard.epochBounds(4))
    assert(entry.accumulatorRoot == embeddedRoot)

  test("ShardManifest encode/decode round-trips a multi-shard-entry listing"):
    val store1 = freezeFullEpoch(5)
    val store2 = freezeFullEpoch(6)
    val manifest = ShardManifest(IndexedSeq(store1.manifestEntry(5, hash), store2.manifestEntry(6, hash)))
    assert(ShardManifest.decode(ShardManifest.encode(manifest)) == manifest)
