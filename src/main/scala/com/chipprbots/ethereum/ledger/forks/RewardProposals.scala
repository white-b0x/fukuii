package com.chipprbots.ethereum.ledger.forks

import com.chipprbots.ethereum.forks.Proposal
import com.chipprbots.ethereum.forks.ProposalId
import com.chipprbots.ethereum.forks.ProposalId.*
import com.chipprbots.ethereum.forks.ProposalLayer

/** L1b — reward / base-fee-routing / client-policy proposal descriptors (Batch 5 framework §1.2, Stage 5.3a).
  *
  * Minimal `Proposal` STUBS only — they declare identity (`id`), dependency edges (`requires`) and the validation-gate
  * tag (`layer`). NO algorithm changes: `BlockRewardCalculator` (ECIP-1017 emission) and
  * `BlockPreparator.creditBaseFeeToTreasury` (ECIP-1111 base-fee → Treasury routing) are UNTOUCHED. Their params live
  * in the chain's `ForkSchedule` (`ProposalParams`); these descriptors exist so composition is explicit and
  * machine-checkable and so later rows can reference the shared implementations by id rather than reimplement them.
  */
object RewardProposals:

  /** ECIP-1017 — fixed-supply disinflationary emission (the `BlockRewardCalculator` algorithm). State-affecting
    * (`Consensus`). Its per-network `era-duration`/reward params are the `MonetaryPolicy` view of its `ProposalParams`.
    */
  val Ecip1017Emission: Proposal = new Proposal:
    val id: ProposalId = Ecip(1017)
    val layer: ProposalLayer = ProposalLayer.Consensus

  /** ECIP-1111 — EIP-1559 fee market + base-fee → Treasury routing + `MIN_BASE_FEE` floor (Olympia). `Consensus`.
    * References the shared EIP-1559/EIP-3198 impls and the ECIP-1112 Treasury via `requires` (framework §5.4).
    */
  val Ecip1111FeeMarket: Proposal = new Proposal:
    val id: ProposalId = Ecip(1111)
    override val requires: Set[ProposalId] = Set(Eip(1559), Eip(3198), Ecip(1112))
    val layer: ProposalLayer = ProposalLayer.Consensus

  /** ECIP-1112 — the Olympia Treasury contract (the base-fee destination ECIP-1111 credits). `Consensus`. */
  val Ecip1112Treasury: Proposal = new Proposal:
    val id: ProposalId = Ecip(1112)
    val layer: ProposalLayer = ProposalLayer.Consensus

  /** ECIP-1122 — ETC network-security client configuration: `MIN_MINER_TIP`, the gas-target schedule, and MESS
    * re-activation. NOT state-affecting — a divergent client is merely weaker, never chain-split — so `ClientPolicy`
    * (banksy-owned; framework §5.5/§1.6). References ECIP-1100/1111/1121 via `requires`.
    */
  val Ecip1122ClientPolicy: Proposal = new Proposal:
    val id: ProposalId = Ecip(1122)
    override val requires: Set[ProposalId] = Set(Ecip(1100), Ecip(1111), Ecip(1121))
    val layer: ProposalLayer = ProposalLayer.ClientPolicy

  val all: List[Proposal] =
    List(Ecip1017Emission, Ecip1111FeeMarket, Ecip1112Treasury, Ecip1122ClientPolicy)

  val byId: Map[ProposalId, Proposal] = all.map(p => p.id -> p).toMap
