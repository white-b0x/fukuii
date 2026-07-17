package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.evm.EvmConfig

/** Applies EIP-4895 validator withdrawals after the tx loop, **outside** the [[RewardScheme]] seam (withdrawals credit
  * validator addresses, disjoint from the coinbase — never double-credited with issuance; L4 plan §9, RX-L4-13). **P1
  * declares the marker only**; the impl is **P5** (beacon-gated). Absent (`Option.None` in [[ProtocolSpec]]) on the
  * PoW/ETC path (besu `AbstractBlockProcessor.getWithdrawalsProcessor()` empty on PoW).
  */
trait WithdrawalsProcessor

/** How a block's EIP-1559 base fee is disposed — a **family-specific field of the bundle**, no math in P1 (the
  * burn-accounting / treasury-credit amounts are P4). The base-fee *computation* is shared; only the disposition
  * diverges by family (L4 plan §3/§7/§9, RX-L4-09/10). A fukuii seam coinage (there is no single besu type for this;
  * besu bakes the burn into `LondonFeeMarket`).
  */
enum FeeDisposition:

  /** No base fee — the pre-EIP-1559 path. */
  case Absent

  /** ETH — the base fee is **burned** (removed from supply, credited nowhere; go-ethereum `consensus/misc/eip1559`).
    */
  case Burn

  /** ETC Olympia — the base fee is **redirected to the treasury** (ECIP-1111 `block.gasUsed * block.baseFee →
    * treasuryAddress`), not burned. forge owns the amount math (P4), banksy is a required consult (security-budget
    * economics, L4 plan §9).
    */
  case RedirectToTreasury(treasury: Address)

/** The immutable per-fork **bundle** — fukuii's besu-`ProtocolSpec` analog (`mainnet/ProtocolSpec.java`), built once
  * per fork and looked up by header, so the fork is resolved **once** and the loop is *handed* a resolved value it
  * calls methods on, never "ask `EvmConfig.forBlock(...)` mid-execution and branch" (L4 plan §2 v2 / §2.1, RX-L4-02).
  *
  * **Scoped to fukuii's actually-varying collaborators, NOT besu's full ~30-field spec** (RX-L4-02 Q2): besu bundles
  * BAL validators, blob-gas handlers, and EIP-7778 dual-gas — all ETH-future the two current families do not vary. This
  * bundle carries only:
  *   - [[evmConfig]] — the L3 opcode/gas config, **held** from L3's single `EvmConfig.forBlock(header, schedule)`
  *     resolution ([[ProtocolSchedule]]), never re-resolved (§2.1);
  *   - [[rewardScheme]] — the DAG-inverted economics seam (ECIP-1017 for PoW, zero for PoS);
  *   - [[requests]] — the EIP-7685 `RequestType → processor` coordinator (`noOp` on PoW / pre-Prague);
  *   - [[withdrawals]] — the EIP-4895 processor, present by fork (absent on PoW);
  *   - [[feeDisposition]] — the base-fee disposition (burn / treasury / absent) — a field only in P1.
  *
  * **Deliberately left OUT of P1 (declared later):** the block validators (header/body/ommer) — no validator type is
  * built until a later L4 phase; the field lands with that seam rather than being stubbed here. BAL / blob-gas /
  * EIP-7778 collaborators are ETH-future YAGNI and are not planned into this bundle at all (RX-L4-02).
  *
  * **R2:** immutable and per-instance-threaded — two `ChainInstance`s in one binary may share a resolved bundle safely;
  * no `object … { var … }` / process-global execution state anywhere in `execution` (L4 plan §9).
  */
final case class ProtocolSpec(
    evmConfig: EvmConfig,
    rewardScheme: RewardScheme,
    requests: RequestProcessors,
    withdrawals: Option[WithdrawalsProcessor],
    feeDisposition: FeeDisposition
)
