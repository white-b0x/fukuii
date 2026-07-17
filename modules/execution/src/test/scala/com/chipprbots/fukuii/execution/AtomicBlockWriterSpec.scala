package com.chipprbots.fukuii.execution

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
import com.chipprbots.fukuii.storage.DataSource
import com.chipprbots.fukuii.storage.DataUpdate
import com.chipprbots.fukuii.storage.EphemDataSource
import com.chipprbots.fukuii.storage.Namespace

/** L4 P6 — the BUG-W7 durability fix: a block's data and its [[ChainWeight]] MUST land in ONE storage batch, so a crash
  * mid-write leaves NEITHER (never a block visible without its weight → wrong fork choice on restart). This is the
  * regression test for the AS-IS two-write bug (L4 plan §6/§9, RX-L4-22).
  */
class AtomicBlockWriterSpec extends AnyFunSuite:

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

  test("writeBlockWithWeight — block data and chain weight are both present after a single-batch commit"):
    val ds = EphemDataSource()
    val writer = new AtomicBlockWriter(ds)
    val b = sampleBlock(1)
    val weight = ChainWeight(BigInt(123456))
    writer.writeBlockWithWeight(b, weight)
    assert(
      writer.readHeader(b).contains(b.header) &&
        writer.readBody(b).contains(b.body) &&
        writer.readWeight(b).contains(weight)
    )

  test("writeBlockWithWeight — a backend failure mid-write leaves NEITHER the block nor its weight (all-or-nothing)"):
    val inner = EphemDataSource()
    val failing = new FailingOnUpdateDataSource(inner)
    val writer = new AtomicBlockWriter(failing)
    val b = sampleBlock(1)
    // one commit → one update call → it throws → nothing was written (the batch is atomic; besu/geth one-batch pattern).
    val _ = assertThrows[RuntimeException](writer.writeBlockWithWeight(b, ChainWeight(BigInt(999))))
    val key = b.header.hash.bytes.toIndexedSeq
    assert(
      inner.get(Namespace.Header, key).isEmpty &&
        inner.get(Namespace.Body, key).isEmpty &&
        inner.get(Namespace.ChainWeight, key).isEmpty
    )

  test("writeBlockWithWeight — the write is exactly ONE update call spanning both block and weight namespaces"):
    val counting = new CountingDataSource(EphemDataSource())
    val writer = new AtomicBlockWriter(counting)
    writer.writeBlockWithWeight(sampleBlock(1), ChainWeight(BigInt(7)))
    // BUG-W7: a single atomic batch, never two separate writes.
    assert(counting.updateCalls == 1)

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

/** A [[DataSource]] that counts `update` calls — proves the write issues exactly one batch (never two). */
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
