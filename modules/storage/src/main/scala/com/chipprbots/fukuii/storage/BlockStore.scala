package com.chipprbots.fukuii.storage

import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.domain.Block
import com.chipprbots.fukuii.domain.BlockBody
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.TotalDifficulty
import com.chipprbots.fukuii.rlp.decode
import com.chipprbots.fukuii.rlp.encode

/** The atomic block+weight write primitive — **the L2 placement every reference client uses** (F-L4-6 relocation):
  * besu-etc `BlockchainStorage.Updater` (`putBlockHeader`/`putBlockBody`/`putTotalDifficulty` + one `commit()`,
  * `ethereum/core/.../chain/BlockchainStorage.java:56-102`), core-geth/go-ethereum `rawdb.WriteBlock` + `rawdb.WriteTd`
  * under one DB batch (`core/rawdb/accessors_chain.go`), erigon `rawdb.WriteTd`, nethermind `BlockStore`, reth's db
  * crate — every one of them lives in the storage/db layer, never one layer up in the execution/import driver. This
  * corrects the earlier L4 `execution.AtomicBlockWriter` placement (RX-L4-22 / L4 plan §6/§9): it was the right
  * BATCHING LOGIC in the wrong LAYER — [[putBlock]] ports that logic here.
  *
  * ==BUG-W7 — the durability invariant this exists for==
  * A block's [[BlockHeader]]/[[BlockBody]] and its [[TotalDifficulty]] MUST land in the SAME [[DataSource.update]]
  * batch — never two separate `update` calls — so a crash between them is structurally impossible (Iron Rule #2:
  * batches are atomic). A block visible without its total difficulty (or a total difficulty with no corresponding
  * block) corrupts the heaviest-chain fork-choice decision on restart, with no recovery path that reconstructs a
  * missing TD from partial data. `Namespace.ChainWeight`'s scaladoc ("Written atomically with its block (BUG-W7)") and
  * `RocksDbDataSourceSpec`'s close/reopen crash-consistency test pin this invariant at the primitive level.
  *
  * ==One batch = all-or-nothing==
  * [[putBlock]] composes the header, body, and total-difficulty upserts into a **single** `Array[DataUpdate]` spanning
  * [[Namespace.Header]], [[Namespace.Body]], and [[Namespace.ChainWeight]], and commits it with **one**
  * [[DataSource.update]] call. The `DataSource` contract ("Atomicity (L2-F4)") guarantees that single `update` is
  * atomic across every `Namespace` it spans — one native RocksDB `WriteBatch` in production, so a crash mid-batch
  * applies NONE of it.
  *
  * ==Scope (BUG-W7 core only)==
  * Header + Body + TotalDifficulty, matching the ported `AtomicBlockWriter` shape exactly. Receipts are the plan's
  * stated eventual occupant of the same batch (`03-L2-storage-trie.md` §"Deferrals" — "Typed `putBlock` writes
  * Header/Body/Receipts + `ChainWeight` in one BUG-W7 `WriteBatch`") but are deliberately NOT added here: no L1
  * `Seq[Receipt]` RLP list codec or `Namespace` keying convention has been established yet, and the ported shape is the
  * load-bearing durability fix, not an occasion to expand scope. Adding a receipts upsert to the same `Array` this
  * class already builds is a small, additive follow-up once that codec exists — it does not change this class's
  * atomicity contract.
  *
  * @param dataSource
  *   the L2 backend (`RocksDbDataSource` in production, `EphemDataSource` in tests) — both honour the single-batch
  *   atomicity contract.
  */
final class BlockStore(dataSource: DataSource):

  private def key(blockHash: Hash): IndexedSeq[Byte] = blockHash.toArray.toIndexedSeq

  /** Persist `block` (header + body) and `totalDifficulty` atomically — one batch, all-or-nothing. Keyed by the block
    * hash (go-ethereum / besu hash-addressed block storage). A crash (or a throwing backend) mid-write leaves NEITHER
    * the block nor the weight, never a partial subset.
    */
  def putBlock(block: Block, totalDifficulty: TotalDifficulty): Unit =
    val blockKey = key(block.header.hash)
    val headerBytes: IndexedSeq[Byte] = encode(block.header).toIndexedSeq
    val bodyBytes: IndexedSeq[Byte] = encode(block.body).toIndexedSeq
    val weightBytes: IndexedSeq[Byte] = encode(totalDifficulty).toIndexedSeq
    // One Array[DataUpdate] spanning all three namespaces, committed with one dataSource.update call — the
    // atomic all-or-nothing write (Iron Rule #2 / DataSource "Atomicity (L2-F4)").
    DataSourceBatchUpdate(
      dataSource,
      Array(
        DataSourceUpdate(Namespace.Header, Nil, Seq(blockKey -> headerBytes)),
        DataSourceUpdate(Namespace.Body, Nil, Seq(blockKey -> bodyBytes)),
        DataSourceUpdate(Namespace.ChainWeight, Nil, Seq(blockKey -> weightBytes))
      )
    ).commit()

  /** Reads back a persisted header by block hash — besu `BlockchainStorage.getBlockHeader`. */
  def getBlockHeader(blockHash: Hash): Option[BlockHeader] =
    dataSource.get(Namespace.Header, key(blockHash)).map(bytes => decode[BlockHeader](bytes.toArray))

  /** Reads back a persisted body by block hash — besu `BlockchainStorage.getBlockBody`. */
  def getBlockBody(blockHash: Hash): Option[BlockBody] =
    dataSource.get(Namespace.Body, key(blockHash)).map(bytes => decode[BlockBody](bytes.toArray))

  /** Reads back a persisted block (header + body) by block hash, if both halves are present. */
  def getBlock(blockHash: Hash): Option[Block] =
    for
      header <- getBlockHeader(blockHash)
      body <- getBlockBody(blockHash)
    yield Block(header, body)

  /** Reads back a persisted total difficulty by block hash — besu `BlockchainStorage.getTotalDifficulty`. */
  def getTotalDifficulty(blockHash: Hash): Option[TotalDifficulty] =
    dataSource.get(Namespace.ChainWeight, key(blockHash)).map(bytes => decode[TotalDifficulty](bytes.toArray))
