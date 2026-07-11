package com.chipprbots.ethereum.consensus.pow.mess

import com.chipprbots.ethereum.domain.BlockNumber

/** Configuration for MESS (Modified Exponential Subjective Scoring).
  *
  * ECBP-1100 defines block-based activation windows per network:
  *   - Mordor: activate=2,380,000 deactivate=10,400,000
  *   - ETC Mainnet: activate=11,380,000 deactivate=19,250,000
  *
  * Olympia re-activates MESS on both networks. Set `reactivationBlock` to the Olympia block number for each chain once
  * it is finalised; the field is optional so existing configs without the key keep working unchanged.
  *
  * @param enabled
  *   Master switch for MESS scoring. Both enabled=true AND the block must be within an active window for MESS to apply.
  * @param activationBlock
  *   Block at which the first MESS window opens (inclusive). None means open from block 0.
  * @param deactivationBlock
  *   Block at which the first MESS window closes (exclusive). None means never closes.
  * @param reactivationBlock
  *   Block at which Olympia re-opens MESS (inclusive). None means no second window.
  */
case class MESSConfig(
    enabled: Boolean = false,
    activationBlock: Option[BlockNumber] = None,
    deactivationBlock: Option[BlockNumber] = None,
    reactivationBlock: Option[BlockNumber] = None
):

  /** True when MESS is active at `blockNumber`.
    *
    * Active in the first window [activationBlock, deactivationBlock) OR in the Olympia second window
    * [reactivationBlock, ∞).
    */
  def isActiveAtBlock(blockNumber: BlockNumber): Boolean =
    enabled && (firstWindowActive(blockNumber) || reactivationBlock.exists(blockNumber >= _))

  private def firstWindowActive(blockNumber: BlockNumber): Boolean =
    activationBlock.forall(blockNumber >= _) && deactivationBlock.forall(blockNumber < _)
