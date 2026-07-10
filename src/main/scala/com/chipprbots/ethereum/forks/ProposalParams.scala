package com.chipprbots.ethereum.forks

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.utils.MonetaryPolicyConfig

/** A single per-proposal, per-chain parameter value (Batch 5 framework §1.4).
  *
  * The "same proposal, different parameters per network" axis (e.g. ECIP-1017's `era-duration` = 5M on ETC vs 2M on
  * Mordor) is carried here rather than duplicating the implementation. An open sum kept deliberately small — extend
  * only when a proposal needs a genuinely new value shape.
  */
enum ParamValue:
  case MonetaryPolicy(config: MonetaryPolicyConfig)
  case Number(value: BigInt)
  case Addr(address: Address)

/** Per-proposal, per-chain parameters — a name→value map with typed views (framework §1.4).
  *
  * Additive carrier only in Stage 5.3a: nothing in production reads these params yet. The typed views project the open
  * map into the concrete param type a given proposal expects (e.g. `monetaryPolicy` for ECIP-1017).
  */
final case class ProposalParams(values: Map[String, ParamValue]):

  /** Typed view for ECIP-1017 — the disinflationary emission schedule parameters. */
  def monetaryPolicy: Option[MonetaryPolicyConfig] =
    values.get(ProposalParams.MonetaryPolicyKey).collect { case ParamValue.MonetaryPolicy(c) => c }

  /** Generic numeric-param accessor (gas targets, tip/base-fee floors, blob target/max). */
  def number(key: String): Option[BigInt] =
    values.get(key).collect { case ParamValue.Number(v) => v }

  /** Generic address-param accessor (e.g. the ECIP-1111/1112 Treasury address). */
  def address(key: String): Option[Address] =
    values.get(key).collect { case ParamValue.Addr(a) => a }

object ProposalParams:
  val empty: ProposalParams = ProposalParams(Map.empty)

  // Canonical param-name keys (framework §5.1–5.6).
  val MonetaryPolicyKey: String = "monetary-policy" // ECIP-1017
  val TreasuryAddressKey: String = "treasury-address" // ECIP-1111 / ECIP-1112
  val BaseFeeFloorKey: String = "base-fee-floor" // ECIP-1111 (MIN_BASE_FEE)
  val MinTipKey: String = "min-tip" // ECIP-1122 (MIN_MINER_TIP)
  val SpiralGasTargetKey: String = "spiral-gas-target" // ECIP-1122 gas-target schedule
  val OlympiaGasTargetKey: String = "olympia-gas-target"
  val BlobTargetKey: String = "blob-target" // EIP-7892 BPO
  val BlobMaxKey: String = "blob-max"

/** A proposal's schedule entry: its activation axis plus its per-chain params (framework §1.4). */
final case class ScheduledProposal(activation: ForkActivation, params: ProposalParams = ProposalParams.empty)
