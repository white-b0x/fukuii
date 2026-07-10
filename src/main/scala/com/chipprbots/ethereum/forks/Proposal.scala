package com.chipprbots.ethereum.forks

/** Super-trait of every concern-specific proposal descriptor (EVM opcode/fee, reward policy, base-fee routing, …).
  *
  * A proposal declares:
  *   - `id` — its ecosystem registry identity (EIP/ECIP/Custom).
  *   - `requires` — the set of other proposals whose *shared implementations it references rather than reimplements*
  *     (Batch 5 framework §1.2 "Proposal dependencies"). Default empty. In Row 5.2 this is not yet expanded by a
  *     `ForkSchedule` builder (that arrives in 5.3); it is declared so composition is explicit and machine-checkable,
  *     and so ECIP-1121 can name the shared EIP impls it bundles.
  *   - `layer` — `Consensus` vs `ClientPolicy`, the validation-gate/ownership tag (§1.6).
  */
trait Proposal:
  def id: ProposalId
  def requires: Set[ProposalId] = Set.empty
  def layer: ProposalLayer
