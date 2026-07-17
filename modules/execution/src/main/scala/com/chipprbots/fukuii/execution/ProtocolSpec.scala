package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Wei
import com.chipprbots.fukuii.evm.AccountStorage
import com.chipprbots.fukuii.evm.EvmConfig
import com.chipprbots.fukuii.evm.WorldState

/** How a block's EIP-1559 base fee is disposed — a **family-specific field of the bundle**. The base-fee *computation*
  * is shared ([[CalcBaseFee]]); only the disposition of `block.gasUsed * block.baseFee` diverges by family (L4 plan
  * §3/§7/§9, RX-L4-09/10). A fukuii seam coinage (there is no single besu type for this; besu bakes the burn into
  * `LondonFeeMarket`).
  *
  * **P4b fills the math** ([[dispose]]): the disposition is applied at finalize, alongside the reward, on the
  * *lump-sum* base-fee amount computed **once** per block (`gasUsed * baseFee` — equal to the per-tx base-fee sum only
  * because `baseFee` is block-constant; RX-L4-10 SHARPENS (ii); never per-tx, never double-counted with a per-tx
  * base-fee charge — the base fee is left uncredited by [[TransactionProcessor]], so it is disposed here and nowhere
  * else).
  */
enum FeeDisposition:

  /** No base fee — the pre-EIP-1559 path. */
  case Absent

  /** ETH — the base fee is **burned** (removed from supply, credited nowhere; go-ethereum `consensus/misc/eip1559`).
    * The burn requires **no state mutation**: [[TransactionProcessor]] already leaves `gasUsed * baseFee` uncredited
    * (the sender paid it, the coinbase received only the tip), so "burned" is the correct realization of that
    * uncredited value — a second debit here would double-charge (go-ethereum `core/state_transition.go` credits only
    * `effectiveTip * gasUsed` to the coinbase; the base-fee portion is added nowhere → burned).
    */
  case Burn

  /** ETC Olympia — the base fee is **redirected to the treasury** (ECIP-1111 draft `:49-55`
    * `state.addBalance(treasuryAddress, block.gasUsed * block.baseFee)`), **not** burned. Value-conserving: what leaves
    * senders as base fee is exactly what enters the treasury. forge owns the amount math, banksy is a required consult
    * (the ECIP-1111 security-budget economics ECIP-1122's `MIN_MINER_TIP` is sized against, L4 plan §9).
    */
  case RedirectToTreasury(treasury: Address)

  /** Apply this disposition to `world` given the block-level lump-sum `baseFeeAmount = gasUsed * baseFee`, returning
    * the mutated world (called at finalize by [[BlockProcessor]], alongside the reward). `Absent`/`Burn` mutate
    * **nothing** (the base fee was left uncredited upstream); `RedirectToTreasury` credits the treasury additively.
    *
    * The treasury credit and the miner-reward credit are disjoint additive `addBalance`s on distinct addresses, so
    * their relative order is **state-root-commutative** — the consensus axes are the *amount* (`gasUsed * baseFee`) and
    * the [[CalcBaseFee]] *floor*, not the sequence (ECIP-1111 draft `:57`, RX-L4-10 SHARPENS (i)).
    */
  def dispose[WS <: WorldState[WS, S], S <: AccountStorage[S]](world: WS, baseFeeAmount: BigInt): WS =
    this match
      case Absent | Burn => world
      case RedirectToTreasury(treasury) =>
        val account = world.getAccount(treasury).getOrElse(world.getEmptyAccount)
        world.saveAccount(treasury, account.copy(balance = Wei(account.balance.toUInt256 + UInt256(baseFeeAmount))))

/** The immutable per-fork **bundle** — fukuii's besu-`ProtocolSpec` analog (`mainnet/ProtocolSpec.java`), built once
  * per fork and looked up by header, so the fork is resolved **once** and the loop is *handed* a resolved value it
  * calls methods on, never "ask `EvmConfig.forBlock(...)` mid-execution and branch" (L4 plan §2 v2 / §2.1, RX-L4-02).
  *
  * **Scoped to fukuii's actually-varying collaborators, NOT besu's full ~30-field spec** (RX-L4-02 Q2): besu bundles
  * BAL validators, blob-gas handlers, and EIP-7778 dual-gas — all ETH-future the two current families do not vary. This
  * bundle carries only:
  *   - [[evmConfig]] — the L3 opcode/gas config, **held** from L3's single `EvmConfig.forBlock(header, schedule)`
  *     resolution ([[ProtocolSchedule]]), never re-resolved (§2.1);
  *   - [[preExecution]] — the pre-tx-loop system calls (EIP-4788/2935 on ETH Cancun+/Prague+; `NoPreExecution` on PoW /
  *     pre-Cancun);
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
    preExecution: PreExecutionProcessor,
    rewardScheme: RewardScheme,
    requests: RequestProcessors,
    withdrawals: Option[WithdrawalsProcessor],
    feeDisposition: FeeDisposition
)
