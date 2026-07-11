package com.chipprbots.ethereum.consensus.pos

import com.chipprbots.ethereum.consensus.ConsensusEngine
import com.chipprbots.ethereum.consensus.EngineId
import com.chipprbots.ethereum.consensus.TransitionBlockHeaderValidator
import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Engine-API (PoS) engine. Header validation routes through the existing, transition-aware
  * [[TransitionBlockHeaderValidator]] unchanged (it already dispatches PoS vs PoW per header, so a from-genesis regular
  * sync validates both pre- and post-Merge seals). No sealer — blocks come from the consensus layer. Finalization
  * delegates to the same `payBlockReward`, whose `isPoS` early return yields "no block reward, base fee burned".
  */
final class EngineApiEngine(val mining: Mining) extends ConsensusEngine:
  val id: EngineId = EngineId.EngineApi
  def headerValidator: BlockHeaderValidator = TransitionBlockHeaderValidator
  def sealer: Option[Mining] = None
  def blockGenerator: BlockGenerator = mining.blockGenerator
  def finalizeBlock(block: Block, world: InMemoryWorldStateProxy)(implicit
      blockchainConfig: BlockchainConfig
  ): InMemoryWorldStateProxy =
    mining.blockPreparator.payBlockReward(block, world)
