package com.chipprbots.ethereum.consensus

import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.pos.EngineApiEngine
import com.chipprbots.ethereum.consensus.pow.EthashEngine
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NetworkType

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

  /** L3 engine-id resolution — Stage 5.4b.
    *
    * Resolves the engine at NETWORK-FAMILY granularity, NOT block/timestamp granularity: a PoW (ETC-family) chain is
    * always [[EngineId.Ethash]]; a PoS-capable (ETH-family) chain is always [[EngineId.EngineApi]]. The engine INSTANCE
    * therefore never changes at the Merge — the [[EngineApiEngine]] handles the pre-/post-Merge split INTERNALLY (its
    * `headerValidator` is [[TransitionBlockHeaderValidator]], which routes per header on `difficulty == 0`, and its
    * `finalizeBlock` delegates to `payBlockReward`, whose `isPoS` early return flips reward/base-fee behaviour at the
    * TTD boundary). Because selection is family-stable, no block number, timestamp, or total difficulty is consulted.
    *
    * The family marker is the EXISTING `BlockchainConfig.networkType` — no new L3 `ForkSchedule` field is added
    * (F9-consistent). `networkType` is the reliable signal here: `mining.protocol` does NOT encode the family (ETH/
    * Sepolia inherit `protocol = pow` from `base/mining.conf` and never override it; Gorgoroth carries a non-standard
    * `protocol = "ethash"`), whereas `networkType` is set explicitly per chain (`network-type = "eth"` for the PoS
    * chains, defaulting to `ETC` for the PoW chains) and is corroborated by `terminalTotalDifficulty` / the derived
    * `Custom("merge", 0)` schedule proposal (`ETH` ⇔ TTD defined ⇔ merge proposal ≠ `Never`).
    *
    * ECIP-1099 (ETChash) is deliberately NOT a distinct engine id. It matches core-geth's structure
    * (`consensus/ethash`: `Config.ECIP1099Block *uint64` + `calcEpochLength(block, ecip1099FBlock)` → 30000/60000
    * within ONE ethash engine) — fukuii carries the same as a DAG-epoch PARAMETER
    * (`BlockchainConfig.ecip1099BlockNumber`, threaded through `EthashUtils`), so [[EngineId.Ethash]] covers both pre-
    * and post-ECIP-1099 ETC without an `EtcHash` case.
    */
  def engineIdFor(blockchainConfig: BlockchainConfig): EngineId =
    blockchainConfig.networkType match
      case NetworkType.ETC => EngineId.Ethash
      case NetworkType.ETH => EngineId.EngineApi

  /** Resolves and constructs the consensus engine for `blockchainConfig`, wrapping the already-built `mining`. This is
    * the single resolution point Stage 5.4c's `MiningBuilder` cutover will call in place of hardwiring an engine. It
    * reuses the same [[EthashEngine]] / [[EngineApiEngine]] wrappers as [[apply]]; it re-authors no consensus logic.
    */
  def engineFor(mining: Mining, blockchainConfig: BlockchainConfig): ConsensusEngine =
    apply(engineIdFor(blockchainConfig), mining)
