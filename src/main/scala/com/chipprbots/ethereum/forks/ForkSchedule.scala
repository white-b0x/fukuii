package com.chipprbots.ethereum.forks

import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TotalDifficulty

/** L3 fork schedule — the per-chain map of proposal → (activation, params) (Batch 5 framework §1.4).
  *
  * This is a DERIVED view (Stage 5.3a): `BlockchainConfig.forkSchedule` is derived from the existing
  * `ForkBlockNumbers`/`ForkTimestamps`/`MonetaryPolicyConfig`/`minTip`/gas-target/`treasuryAddress`/`terminalTotalDifficulty`
  * fields, which remain the permanent L2 HOCON representation (F9). Nothing in production dispatch reads this yet — the
  * `EvmConfig.forBlock` switch onto the schedule is Stage 5.3b. `ForkScheduleDerivationSpec` proves this view
  * reproduces, for every registered proposal, the exact activation decision the underlying fields already make.
  */
final case class ForkSchedule(entries: Map[ProposalId, ScheduledProposal]):

  /** The activation axis for a proposal — `Never` if the proposal is not scheduled on this chain. */
  def activationOf(id: ProposalId): ForkActivation =
    entries.get(id).map(_.activation).getOrElse(ForkActivation.Never)

  /** The per-chain params for a proposal — empty if the proposal is not scheduled on this chain. */
  def paramsOf(id: ProposalId): ProposalParams =
    entries.get(id).map(_.params).getOrElse(ProposalParams.empty)

  /** Whether a proposal is active at the given dispatch point (block / timestamp / total-difficulty). */
  def isActive(id: ProposalId, block: BlockNumber, timestamp: Timestamp, td: TotalDifficulty): Boolean =
    activationOf(id).isActiveAt(block, timestamp, td)
