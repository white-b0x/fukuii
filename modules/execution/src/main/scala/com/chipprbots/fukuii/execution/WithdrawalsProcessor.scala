package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Wei
import com.chipprbots.fukuii.domain.Withdrawal

/** Applies EIP-4895 validator withdrawals **after** the tx loop and **outside** the [[RewardScheme]] seam — withdrawals
  * credit validator withdrawal addresses (disjoint from the coinbase), so they are never double-credited with issuance
  * (L4 plan §9, RX-L4-13). The bundle binds [[Eip4895WithdrawalsProcessor]] on the ETH post-Shapella path and
  * **`None`** on the ETC/PoW path (besu `AbstractBlockProcessor.getWithdrawalsProcessor()` empty on PoW); a withdrawal
  * present on the ETC path is a hard reject ([[BlockExecutionError.WithdrawalsNotAllowed]]), not a silent skip.
  */
trait WithdrawalsProcessor:

  /** Credit each withdrawal's `amount` (Gwei) to its `address`, returning the mutated world. Called once per block,
    * post-tx-loop, before the request phase and the reward (besu order, RX-L4-11).
    */
  def processWithdrawals(withdrawals: List[Withdrawal], world: InMemoryWorldState): InMemoryWorldState

object WithdrawalsProcessor:

  /** EIP-4895 — credit each validator withdrawal, converting the `amount` from **Gwei to Wei** (`× 10^9`; go-ethereum
    * `Engine.Finalize` `state.AddBalance(w.Address, w.Amount × params.GWei)`; besu `WithdrawalsProcessor`
    * `account.incrementBalance(withdrawal.getAmount().getAsWei())`). Each credit is an additive `addBalance` on the
    * withdrawal address, then the touched-account set is swept for empties (besu `clearAccountsThatAreEmpty`; a
    * zero-amount withdrawal to a fresh account nets to nothing, exactly as geth's block-boundary `Finalise(true)`).
    */
  object Eip4895WithdrawalsProcessor extends WithdrawalsProcessor:

    /** `1 Gwei = 10^9 Wei` (go-ethereum `params.GWei`). */
    val GweiInWei: BigInt = BigInt(10).pow(9)

    def processWithdrawals(withdrawals: List[Withdrawal], world: InMemoryWorldState): InMemoryWorldState =
      val credited = withdrawals.foldLeft(world) { (w, withdrawal) =>
        addBalance(w, withdrawal.address, BigInt(withdrawal.amount) * GweiInWei)
      }
      credited.deleteEmptyTouchedAccounts

    /** Add `amount` Wei to `address`, creating the account if absent, and **touch** it (so the EIP-161 empty-sweep can
      * reclaim a zero-amount credit) — mirrors go-ethereum `AddBalance` + block-boundary finalise / besu `getOrCreate`
      * + `incrementBalance` + `clearAccountsThatAreEmpty`.
      */
    private def addBalance(world: InMemoryWorldState, address: Address, amount: BigInt): InMemoryWorldState =
      val account = world.getAccount(address).getOrElse(world.getEmptyAccount)
      world
        .saveAccount(address, account.copy(balance = Wei(account.balance.toUInt256 + UInt256(amount))))
        .touchAccounts(address)
