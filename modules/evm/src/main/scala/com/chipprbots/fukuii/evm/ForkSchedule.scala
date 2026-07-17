package com.chipprbots.fukuii.evm

/** The per-chain fork schedule — the map of proposal → activation axis that drives fork dispatch.
  *
  * This is the family-blind seam the single [[EvmConfig.forBlock]] dispatches over: each entry carries its proposal's
  * own [[ForkActivation]] case, so one header-keyed scan resolves the active set regardless of whether a fork gates on
  * block number (ETC) or timestamp (post-Merge ETH) — besu's `getByBlockHeader` over `MilestoneType.{BLOCK_NUMBER,
  * TIMESTAMP}`, expressed as an axis-tagged enum. *Which* schedule a network is handed is an L5 `NetworkFamily`
  * decision; the seam is L3's.
  *
  * P0 scope: the schedule maps `ProposalId → ForkActivation` directly. A per-chain parameter carrier for economics
  * values (ECIP-1017 monetary policy, ECIP-1111 treasury/base-fee-floor, ECIP-1122 tip/gas-target, EIP-7892 blob
  * target/max) is intentionally **not** modeled here: those are L4/L5 economics params, not consumed by the L3 EVM
  * opcode/gas fold, and their carrier depends on `MonetaryPolicyConfig`, which is outside the `evm` module DAG
  * (`domain`, `crypto`, `rlp` only).
  */
final case class ForkSchedule(entries: Map[ProposalId, ForkActivation]):

  /** The activation axis for a proposal — `Never` if the proposal is not scheduled on this chain. */
  def activationOf(id: ProposalId): ForkActivation =
    entries.getOrElse(id, ForkActivation.Never)

  /** Whether a single proposal is active at the given dispatch point (block / timestamp / total-difficulty). */
  def isActive(id: ProposalId, block: BigInt, timestamp: Long, td: BigInt): Boolean =
    activationOf(id).isActiveAt(block, timestamp, td)

  /** The set of proposals active at the given block/timestamp — the membership half of fork resolution that
    * [[EvmConfig.forBlock]] consumes. No EVM proposal gates on total difficulty (the Merge does not change the derived
    * EVM config), so `ByTotalDifficulty`/`Never` entries are inactive here by construction.
    */
  def activeAt(block: BigInt, timestamp: Long): Set[ProposalId] =
    entries.iterator.collect {
      case (id, ForkActivation.ByBlock(n)) if block >= n         => id
      case (id, ForkActivation.ByTimestamp(t)) if timestamp >= t => id
    }.toSet
