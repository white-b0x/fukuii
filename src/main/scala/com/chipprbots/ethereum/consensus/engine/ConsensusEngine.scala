package com.chipprbots.ethereum.consensus.engine

import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Identity of a consensus engine.
  *
  * `Ethash` and `EngineApi` are the two engines fukuii actually runs today (PoW and PoS). `Clique`, `Qbft` and `Bor`
  * are reserved seams — enumerated so the framework's shape is complete, but with no implementation in this phase
  * (Batch 5 Stage 5.4a). Adding a real one is future work, not a design change.
  */
enum EngineId:
  case Ethash
  case EngineApi
  case Clique
  case Qbft
  case Bor

/** A narrow seam over the pieces of consensus that vary by engine: header (seal) validation, block sealing, block
  * production, and finalization rewards.
  *
  * This interface is purely ADDITIVE — it sits BESIDE the existing [[Mining]] trait, never replaces it, and no
  * production path resolves an engine from it yet (schedule resolution is Stage 5.4b). It wraps already-correct engine
  * logic behind a single seam; it does not re-author any of it. In particular `finalizeBlock` DELEGATES, unchanged, to
  * [[com.chipprbots.ethereum.ledger.BlockPreparator.payBlockReward]], which already implements ECIP-1017 emission, the
  * ECIP-1111 Treasury credit, and the post-merge "no reward / base-fee burned" early return (gated on
  * `block.header.isPoS`).
  */
trait ConsensusEngine:

  /** Which engine this is. */
  def id: EngineId

  /** Seal/header validator: Ethash PoW seal for the PoW engine, the transition-aware PoS validator for the Engine-API
    * engine. Resolved from already-constructed validators; not re-authored here.
    */
  def headerValidator: BlockHeaderValidator

  /** The block-sealing driver, if this engine seals its own blocks. `Some` for PoW (the miner plumbing carried by
    * [[Mining]]); `None` for Engine-API, where the consensus layer supplies blocks.
    */
  def sealer: Option[Mining]

  /** Block producer for this engine. */
  def blockGenerator: BlockGenerator

  /** Finalization hook — applies rewards / base-fee routing. Delegates verbatim to the existing
    * `BlockPreparator.payBlockReward`: PoW pays ECIP-1017 + credits the ECIP-1111 Treasury; PoS (`isPoS`) pays no
    * reward and burns the base fee (the delegate's early return).
    */
  def finalizeBlock(block: Block, world: InMemoryWorldStateProxy)(implicit
      blockchainConfig: BlockchainConfig
  ): InMemoryWorldStateProxy

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

object ConsensusEngine:

  /** Constructs the engine for a given [[EngineId]], wrapping an already-built [[Mining]]. This is a plain constructor
    * dispatch, NOT schedule/`engineFor` resolution (Stage 5.4b) — it never reads a block number, timestamp, or total
    * difficulty. The reserved PoA/sidechain engines are a not-implemented seam.
    */
  def apply(id: EngineId, mining: Mining): ConsensusEngine =
    id match
      case EngineId.Ethash    => new EthashEngine(mining)
      case EngineId.EngineApi => new EngineApiEngine(mining)
      case EngineId.Clique | EngineId.Qbft | EngineId.Bor =>
        throw new NotImplementedError(
          s"ConsensusEngine '$id' is a reserved engine seam with no implementation in this phase (Batch 5 Stage 5.4a)."
        )
