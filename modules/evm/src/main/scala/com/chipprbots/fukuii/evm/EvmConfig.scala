package com.chipprbots.fukuii.evm

import com.chipprbots.fukuii.domain.BlockHeader

/** The per-block **resolved** EVM configuration — the value object the interpreter runs against, built once for a fork
  * and cached (fukuii's `Rules`/`IReleaseSpec`/`ProtocolSpec` analog).
  *
  * **P0 shape (this phase — fork-dispatch scaffolding only).** The resolved bundle carries the active proposal set,
  * from which fork state is read. The two headline fields — the dense opcode `IArray` table and the per-fork `given
  * GasCalculator` (unifying fee *values* and gas *computation*, besu's DEFAULT) — are **deferred to P2** (they need the
  * opcode/gas types P2 builds); the intent-named getters (`extension (c: EvmConfig) def shiftOpcodesEnabled: Boolean`,
  * the two-layer model that keeps fork names out of the interpreter loop) sit over [[isActive]] and also land in P2.
  * Those are additive extensions of this shape, not rewrites of it.
  *
  * **R2 (multi-instance isolation):** this value is immutable and freely shareable — two `ChainInstance`s in one binary
  * may share a cached `header → EvmConfig` safely. No `object … { var … }` / process-global EVM state anywhere in
  * `evm`.
  */
final case class EvmConfig(activeProposals: Set[ProposalId]):

  /** Raw proposal membership. The P2 intent-named getters (`shiftOpcodesEnabled`, `hasBaseFeeOpcode`, …) are the
    * fork-name-free layer that sits over this; the interpreter reads intent, never a fork identity.
    */
  def isActive(id: ProposalId): Boolean =
    activeProposals.contains(id)

object EvmConfig:

  /** The single fork-dispatch entry point — resolve the EVM config active at `header` under `schedule`.
    *
    * This is the forward target that replaces the two AS-IS `EvmConfig.forBlock` overloads (2-arg block; 3-arg
    * block+timestamp) with **one** `forBlock(header, schedule)`: the header supplies both number and timestamp, and
    * each proposal's [[ForkActivation]] case decides its own boundary (besu's single `getByBlockHeader`). The AS-IS
    * overloads are not deleted here — they live on branch `july-fourth`; the byte-identity proof that this single
    * method reproduces them at every activation height is P3's fold-identity gate (Chesterton's Fence).
    *
    * P0 resolves the active proposal **set** (the membership half, order-independent). P2 adds the opcode table +
    * `given GasCalculator` to [[EvmConfig]]; **P3** wires the ordered `deriveEvmConfigAt` fold that populates them from
    * this active set (the ordered fold is what proves byte-identity — set membership alone is order-independent).
    */
  def forBlock(header: BlockHeader, schedule: ForkSchedule): EvmConfig =
    EvmConfig(schedule.activeAt(header.number, header.unixTimestamp))
