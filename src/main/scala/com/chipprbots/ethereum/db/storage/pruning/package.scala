package com.chipprbots.ethereum.db.storage

import com.chipprbots.ethereum.domain.BlockNumber

package object pruning:

  enum PruningMode:
    case ArchivePruning
    case BasicPruning(history: Int)
    case InMemoryPruning(history: Int)

  export PruningMode.{ArchivePruning, BasicPruning, InMemoryPruning}

  trait PruneSupport:

    /** Remove unused data for the given block number
      * @param blockNumber
      *   BlockNumber to prune
      * @param nodeStorage
      *   NodeStorage
      */
    def prune(blockNumber: BlockNumber, nodeStorage: NodesStorage, inMemory: Boolean): Unit

    /** Rollbacks blocknumber changes
      * @param blockNumber
      *   BlockNumber to rollback
      * @param nodeStorage
      *   NodeStorage
      */
    def rollback(blockNumber: BlockNumber, nodeStorage: NodesStorage, inMemory: Boolean): Unit
