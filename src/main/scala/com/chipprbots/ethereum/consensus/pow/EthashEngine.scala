package com.chipprbots.ethereum.consensus.pow

import com.chipprbots.ethereum.consensus.ConsensusEngine
import com.chipprbots.ethereum.consensus.EngineId
import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig

/** PoW (Ethash / ETChash) engine. Wraps an existing [[Mining]] instance (a `PoWMining` in practice) and exposes its
  * already-constructed validators, block generator, miner plumbing, and block finalization unchanged.
  */
final class EthashEngine(val mining: Mining) extends ConsensusEngine:
  val id: EngineId = EngineId.Ethash
  def headerValidator: BlockHeaderValidator = mining.validators.blockHeaderValidator
  def sealer: Option[Mining] = Some(mining)
  def blockGenerator: BlockGenerator = mining.blockGenerator
  def finalizeBlock(block: Block, world: InMemoryWorldStateProxy)(implicit
      blockchainConfig: BlockchainConfig
  ): InMemoryWorldStateProxy =
    mining.blockPreparator.payBlockReward(block, world)
