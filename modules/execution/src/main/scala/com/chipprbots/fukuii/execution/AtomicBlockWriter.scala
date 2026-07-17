package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.domain.Block
import com.chipprbots.fukuii.domain.BlockBody
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.rlp.decode
import com.chipprbots.fukuii.rlp.encode
import com.chipprbots.fukuii.storage.DataSource
import com.chipprbots.fukuii.storage.DataSourceBatchUpdate
import com.chipprbots.fukuii.storage.DataSourceUpdate
import com.chipprbots.fukuii.storage.Namespace

/** Writes a block **and its [[ChainWeight]] in the SAME storage batch** — the BUG-W7 / AP-9 durability fix (L4 plan §6
  * "Non-atomic block + weight write" → "Atomic block+weight write", §9; RX-L4-22). This is the one genuinely
  * durability-critical piece of P6: the AS-IS multi-block driver persisted block data and then recorded chain weight in
  * **two separate writes**, so a crash between them left the DB inconsistent (a block visible without its weight, or a
  * weight with no block) → the heaviest-chain fork choice picks wrong on restart, with no recovery path that
  * reconstructs the missing TD.
  *
  * ==One batch = all-or-nothing (the L4→L2 contract)==
  * [[writeBlockWithWeight]] composes the block-data updates (header + body) and the chain-weight update into a
  * **single** [[DataSourceBatchUpdate]] (via [[DataSourceBatchUpdate.and]]) and commits it with **one**
  * [[DataSource.update]] call. The L2 `DataSource` contract guarantees that single `update` is atomic across every
  * [[Namespace]] it spans — one native RocksDB `WriteBatch`, so a crash mid-batch applies NONE of it (`DataSource`
  * scaladoc "Atomicity (L2-F4)", `RocksDbDataSourceSpec`'s close/reopen crash-consistency test; `Namespace.ChainWeight`
  * "Written atomically with its block (BUG-W7)"). The atomicity is obtained by building **one** `Seq[DataUpdate]`
  * spanning both namespaces and passing it to one commit — **never** two `update`/`put` calls.
  *
  * Reference one-batch pattern: besu `AbstractBlockProcessor.java:531` `worldState.persist(...)` is a single atomic
  * committer, not a state-then-metadata two-step; go-ethereum `WriteBlockWithState` writes the block, receipts, and
  * total difficulty under **one** DB batch. No reference client exhibits the AS-IS two-write bug.
  *
  * @param dataSource
  *   the L2 backend (`RocksDbDataSource` in production, `EphemDataSource` in tests) — both honour the single-batch
  *   atomicity contract.
  */
final class AtomicBlockWriter(dataSource: DataSource):

  /** Persist `block` (header + body) and `weight` atomically — one batch, all-or-nothing. Keyed by the block hash
    * (go-ethereum / besu hash-addressed block storage). A crash (or a throwing backend) mid-write leaves NEITHER the
    * block nor the weight, never a partial subset.
    */
  def writeBlockWithWeight(block: Block, weight: ChainWeight): Unit =
    val hashKey: IndexedSeq[Byte] = block.header.hash.bytes.toIndexedSeq
    val headerBytes: IndexedSeq[Byte] = encode(block.header).toIndexedSeq
    val bodyBytes: IndexedSeq[Byte] = encode(block.body).toIndexedSeq
    val weightBytes: IndexedSeq[Byte] = encode(weight).toIndexedSeq

    val blockData = DataSourceBatchUpdate(
      dataSource,
      Array(
        DataSourceUpdate(Namespace.Header, Nil, Seq(hashKey -> headerBytes)),
        DataSourceUpdate(Namespace.Body, Nil, Seq(hashKey -> bodyBytes))
      )
    )
    val chainWeight = DataSourceBatchUpdate(
      dataSource,
      Array(DataSourceUpdate(Namespace.ChainWeight, Nil, Seq(hashKey -> weightBytes)))
    )
    // .and combines into ONE Seq[DataUpdate]; commit() is a single dataSource.update — the atomic all-or-nothing write.
    blockData.and(chainWeight).commit()

  /** Read back a persisted header (test / diagnostic seam — the read path proper is L2/L5's). */
  def readHeader(block: Block): Option[BlockHeader] =
    dataSource
      .get(Namespace.Header, block.header.hash.bytes.toIndexedSeq)
      .map(bytes => decode[BlockHeader](bytes.toArray))

  /** Read back a persisted body (test / diagnostic seam). */
  def readBody(block: Block): Option[BlockBody] =
    dataSource.get(Namespace.Body, block.header.hash.bytes.toIndexedSeq).map(bytes => decode[BlockBody](bytes.toArray))

  /** Read back a persisted chain weight (test / diagnostic seam). */
  def readWeight(block: Block): Option[ChainWeight] =
    dataSource
      .get(Namespace.ChainWeight, block.header.hash.bytes.toIndexedSeq)
      .map(bytes => decode[ChainWeight](bytes.toArray))
