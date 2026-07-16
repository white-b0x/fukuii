package com.chipprbots.fukuii.evm

/** The identity of a single protocol proposal, keyed by the ecosystem's own registry number.
  *
  * An EIP for Ethereum-lineage changes, an ECIP for Ethereum-Classic-lineage changes, and an open `Custom(family,
  * number)` slot so future families slot in without touching the ETC/ETH sums. This is the **family-agnostic**
  * implementation key: chain id keys the *config* layer, never the implementation. `Eip(1559)` is one shared impl
  * referenced by every family that composes it, not a per-chain copy.
  *
  * It is the fold key for the additive per-EIP/ECIP EVM feature registry (the DEFAULT(ETC path) — core-geth's
  * `enableNNNN` shape realized family-agnostically): the effective opcode/gas/precompile set at a given height is
  * *derived* by folding the active proposals, not hand-maintained as a per-fork mega-table.
  */
enum ProposalId:
  case Eip(number: Int)
  case Ecip(number: Int)
  case Custom(family: String, number: Int)

  /** Human-facing label for logs, docs and test messages — derived, never a storage key. */
  def label: String = this match
    case Eip(n)       => s"EIP-$n"
    case Ecip(n)      => s"ECIP-$n"
    case Custom(f, n) => s"$f-$n"
