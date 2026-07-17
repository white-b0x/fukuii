package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.bytes.Address

/** The immutable per-call simulation bundle threaded through the [[TransactionProcessor]], avoiding any process-global
  * mutable simulation flags (L4 plan §5/§6, RX-L4-23).
  *
  * **R2 — no process-global execution state.** Process-global `@volatile` flags would be exactly the shared mutable
  * state R2 bans: two `ChainInstance`s in one binary threading a simulation into `runVM` would race on them. Here every
  * simulation toggle rides on this per-call value, so nothing is instance- or process-level mutable. Both go-ethereum
  * (`vm.Config` threaded as a *parameter* into `Process`/`NewEVM`, `core/state_processor.go:66-90`) and besu
  * (`MainnetTransactionProcessor` receives `TransactionValidationParams.processingBlock()` **per call**, not a mutable
  * processor field) confirm the per-call, never-per-instance shape.
  *
  * **`precompileRelocations` needs NO L3 change.** [[com.chipprbots.fukuii.evm.CallContext.precompileRelocations]] is
  * already a plain field (default empty) on the L3 seam — the processor simply *populates* it from these options when
  * it builds the top-level `CallContext`. The actual relocation *behavior* (remapping a precompile address for a
  * simulated call) is P5's precompile work; P2 only threads the map through, so the L3 seam is untouched (confirmed by
  * reading `CallContext.scala:59` + `EvmInterpreter.call`'s "Relocation remap … not wired here (default empty)").
  *
  * @param precompileRelocations
  *   simulation-only remap of precompile addresses (empty in [[none]] / all consensus execution); the disposition of
  *   the remap is P5.
  */
final case class SimulationOptions(
    precompileRelocations: Map[Address, Address] = Map.empty
):

  /** Whether this is a real (non-simulation) execution — the default fast path. */
  def isConsensus: Boolean = precompileRelocations.isEmpty

object SimulationOptions:

  /** The default no-simulation options — empty relocations, the consensus execution path. */
  val none: SimulationOptions = SimulationOptions()
