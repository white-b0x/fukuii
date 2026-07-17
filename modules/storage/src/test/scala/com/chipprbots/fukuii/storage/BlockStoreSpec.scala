package com.chipprbots.fukuii.storage

import org.apache.pekko.util.ByteString

import cats.effect.IO

import fs2.Stream
import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.domain.Block
import com.chipprbots.fukuii.domain.BlockBody
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.domain.TotalDifficulty

/** L2 F-L4-6 relocation — the BUG-W7 durability fix, ported down from the provisional L4 `execution.AtomicBlockWriter`
  * to its reference-client-authoritative placement in `storage`. A block's data and its [[TotalDifficulty]] MUST land
  * in ONE storage batch, so a crash mid-write leaves NEITHER (never a block visible without its weight -> wrong fork
  * choice on restart). This is the regression test for the two-write bug this primitive prevents
  * (`03-L2-storage-trie.md` "Deferrals" note, RX-L4-22).
  */
class BlockStoreSpec extends AnyFunSuite:

  private def addr(b: Byte): Address = Address(ByteString(Array.fill[Byte](Address.Length)(b)))

  private def sampleBlock(number: BigInt): Block =
    val header = BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = addr(0x33),
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 1,
      number = number,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = 0,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty
    )
    Block(header, BlockBody(Nil, Nil, None))

  test("putBlock — block data and total difficulty are both present after a single-batch commit"):
    val ds = EphemDataSource()
    val store = new BlockStore(ds)
    val b = sampleBlock(1)
    val weight = TotalDifficulty.fromBigInt(BigInt(123456))
    store.putBlock(b, weight)
    assert(
      store.getBlockHeader(b.header.hash).contains(b.header) &&
        store.getBlockBody(b.header.hash).contains(b.body) &&
        store.getBlock(b.header.hash).contains(b) &&
        store.getTotalDifficulty(b.header.hash).contains(weight)
    )

  test("putBlock — a backend failure mid-write leaves NEITHER the block nor its weight (all-or-nothing)"):
    val inner = EphemDataSource()
    val failing = new FailingOnUpdateDataSource(inner)
    val store = new BlockStore(failing)
    val b = sampleBlock(1)
    // one commit -> one update call -> it throws -> nothing was written (the batch is atomic; besu/geth one-batch
    // pattern).
    val _ = assertThrows[RuntimeException](store.putBlock(b, TotalDifficulty.fromBigInt(BigInt(999))))
    val key = b.header.hash.toArray.toIndexedSeq
    assert(
      inner.get(Namespace.Header, key).isEmpty &&
        inner.get(Namespace.Body, key).isEmpty &&
        inner.get(Namespace.ChainWeight, key).isEmpty
    )

  test("putBlock — the write is exactly ONE update call spanning header, body, and total-difficulty namespaces"):
    val counting = new CountingDataSource(EphemDataSource())
    val store = new BlockStore(counting)
    store.putBlock(sampleBlock(1), TotalDifficulty.fromBigInt(BigInt(7)))
    // BUG-W7: a single atomic batch, never two (or three) separate writes.
    assert(counting.updateCalls == 1)

  test("total difficulty round-trips byte-exact through the ChainWeight column family"):
    val ds = EphemDataSource()
    val store = new BlockStore(ds)
    val b = sampleBlock(1)
    val weight = TotalDifficulty.fromBigInt(BigInt("123456789012345678901234"))
    store.putBlock(b, weight)
    val readBack = store.getTotalDifficulty(b.header.hash)
    assert(readBack.contains(weight) && readBack.exists(_.toBigInt == weight.toBigInt))

  test("a block never written reads as absent for header, body, block, and total difficulty"):
    val ds = EphemDataSource()
    val store = new BlockStore(ds)
    val neverWritten = sampleBlock(2).header.hash
    assert(
      store.getBlockHeader(neverWritten).isEmpty &&
        store.getBlockBody(neverWritten).isEmpty &&
        store.getBlock(neverWritten).isEmpty &&
        store.getTotalDifficulty(neverWritten).isEmpty
    )

/** A [[DataSource]] that throws on any `update`, delegating reads to `inner` — proves the atomic write is
  * all-or-nothing at the batch boundary (the single `update` throws before `inner` is mutated).
  */
private class FailingOnUpdateDataSource(inner: DataSource) extends DataSource:
  override def get(namespace: Namespace, key: DataSource.Key): Option[DataSource.Value] = inner.get(namespace, key)
  override def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
    inner.getOptimized(namespace, key)
  override def scanRange(
      namespace: Namespace,
      fromKey: Array[Byte],
      toKeyExclusive: Array[Byte]
  ): Iterator[(Array[Byte], Array[Byte])] = inner.scanRange(namespace, fromKey, toKeyExclusive)
  override def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
    inner.deleteRange(namespace, fromKey, toKeyExclusive)
  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
    throw new RuntimeException("simulated backend failure mid-write")
  override def clear(): Unit = inner.clear()
  override def close(): Unit = inner.close()
  override def destroy(): Unit = inner.destroy()
  override def iterate(): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]] = inner.iterate()
  override def iterate(
      namespace: Namespace
  ): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]] =
    inner.iterate(namespace)

/** A [[DataSource]] that counts `update` calls — proves the write issues exactly one batch (never two or three). */
private class CountingDataSource(inner: DataSource) extends DataSource:
  var updateCalls: Int = 0
  override def get(namespace: Namespace, key: DataSource.Key): Option[DataSource.Value] = inner.get(namespace, key)
  override def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
    inner.getOptimized(namespace, key)
  override def scanRange(
      namespace: Namespace,
      fromKey: Array[Byte],
      toKeyExclusive: Array[Byte]
  ): Iterator[(Array[Byte], Array[Byte])] = inner.scanRange(namespace, fromKey, toKeyExclusive)
  override def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
    inner.deleteRange(namespace, fromKey, toKeyExclusive)
  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
    updateCalls += 1
    inner.update(dataSourceUpdates)
  override def clear(): Unit = inner.clear()
  override def close(): Unit = inner.close()
  override def destroy(): Unit = inner.destroy()
  override def iterate(): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]] = inner.iterate()
  override def iterate(
      namespace: Namespace
  ): Stream[IO, Either[DataSource.IterationError, (Array[Byte], Array[Byte])]] =
    inner.iterate(namespace)
