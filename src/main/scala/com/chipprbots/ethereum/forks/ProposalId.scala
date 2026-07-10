package com.chipprbots.ethereum.forks

/** L1 identity for a protocol proposal (Batch 5 framework §1.2).
  *
  * A `ProposalId` names a single protocol change by the ecosystem's own registry number — an EIP for Ethereum-lineage
  * changes, an ECIP for Ethereum-Classic-lineage changes, and an open `Custom(family, number)` slot so future L2 /
  * appchain families slot in without touching the ETC/ETH sums. This is the L1 (implementation) key: it is
  * **family-agnostic** — chain id keys the *config* layer (L2), never the implementation. `Eip(1559)` is one shared
  * impl referenced by every family that composes it, not a per-chain copy (§1.1 "share the implementation, never the
  * bundle").
  */
enum ProposalId:
  case Eip(number: Int) // EIP-1559  -> Eip(1559)
  case Ecip(number: Int) // ECIP-1017 -> Ecip(1017)
  case Custom(family: String, number: Int) // e.g. Custom("bor", 1) for a Polygon-family proposal

  /** Human-facing label for logs, docs and test messages — derived, never a storage key (§1.5). */
  def label: String = this match
    case Eip(n)       => s"EIP-$n"
    case Ecip(n)      => s"ECIP-$n"
    case Custom(f, n) => s"$f-$n"
