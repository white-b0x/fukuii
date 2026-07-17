package com.chipprbots.fukuii.execution

/** Why a state mutation happened — the attribution tag carried by each entry of a per-block [[BlockStateDiff]],
  * modelled on go-ethereum's `BalanceChangeReason` (`core/tracing/hooks.go:339-351`, `BalanceChangeReason byte` with
  * `BalanceIncreaseRewardMineBlock`, transfer, withdrawal, burn reasons). **L4 OWNS this type** because the reason is
  * known only at the point of state transition (which is execution's job); L9's `grpc-seam` imports it *upward* to tag
  * the reorg-notification payload (L4 plan §5, RX-L4-16).
  *
  * ==PROVISIONAL — wire/payload shape OPEN pending the joint L4/L5/L9 WB-R2 review; not a frozen public API.==
  * The variant set is deliberately NOT frozen (L9 holds the payload wire contract OPEN, `coherence-pass-02` WB-R2). Add
  * variants freely until the joint review fixes the payload contract once — do NOT design an RLP/wire codec against
  * this enum yet, and do NOT treat the current cases as a stable ABI.
  *
  * ==Zero-cost when no consumer (RX-L4-16)==
  * The reason-tagging path is genuinely zero-cost when no event consumer is attached because the recording accumulator
  * is **not installed at all** ([[MutationSink.NoTracking]], a branch-free no-op) — mirroring go-ethereum's
  * `state_processor.go:77` gate that installs the hooked `StateDB` only when `Tracer != nil`, NOT a per-mutation `if`.
  * The reason itself is assigned post-hoc by address role in [[BlockProcessor]] over the accumulated touched set, so no
  * reason threading reaches L3's hot path (a coarse per-block-phase attribution — see [[BlockProcessor]]).
  */
enum MutationReason:

  /** Block issuance to the coinbase / ommer beneficiaries — ECIP-1017 era emission (PoW) via [[RewardScheme]]
    * (go-ethereum `BalanceIncreaseRewardMineBlock`/`…MineUncle`). The zero-reward PoS path touches no account, so it
    * contributes no `Reward` entry.
    */
  case Reward

  /** EIP-1559 base-fee disposition — the ETH burn (no state mutation, so it contributes no entry) or the ECIP-1111
    * treasury credit ([[FeeDisposition.RedirectToTreasury]]). The treasury credit surfaces as a `FeeBurn`-tagged entry;
    * a burned base fee mutates nothing and is therefore invisible in the diff (correctly — nothing changed).
    */
  case FeeBurn

  /** A value transfer / general execution mutation from the tx-apply loop — the default attribution for any touched
    * account that is not a reward/treasury/withdrawal/system-call target (go-ethereum `BalanceChangeTransfer`). Covers
    * the sender debit, recipient credit, and coinbase tip. The coinbase, when it also receives issuance, is attributed
    * to the dominant [[Reward]] in this PROVISIONAL net-diff model (geth emits a separate event per balance change; the
    * coarse per-block attribution is a documented simplification, resolved when the payload contract is fixed).
    */
  case Transfer

  /** A system-call mutation — the EIP-4788/2935 pre-execution phase and the EIP-7002/7251/6110 request phase
    * (`SystemAddress`-sender pseudo-txs). `noOp` on the ETC/PoW path, so no `SystemCall` entry appears there.
    */
  case SystemCall

  /** An EIP-4895 validator withdrawal credit — applied post-tx-loop, outside the reward seam (disjoint validator
    * addresses; go-ethereum `BalanceIncreaseWithdrawal`). Never on the ETC/PoW path (withdrawals hard-rejected).
    */
  case Withdrawal
