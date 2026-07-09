package com.chipprbots.ethereum.transactions.testing
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

// Immutable state holder for the Typed pending-transactions mock (see companion `behavior`).
// (Formerly a Classic `AutoPilot`; the Classic `run` path was removed once the sole consumer
// switched to the Typed `behavior()` factory.)
case class PendingTransactionsManagerAutoPilot(pendingTransactions: Set[PendingTransaction] = Set.empty):

  def addTransactions(signedTransactions: Set[SignedTransactionWithSender]): PendingTransactionsManagerAutoPilot =
    val timestamp = System.currentTimeMillis()
    val stxs = pendingTransactions.map(_.stx)
    val transactionsToAdd = signedTransactions.diff(stxs).map(tx => PendingTransaction(tx, timestamp))

    copy(pendingTransactions ++ transactionsToAdd)

  def removeTransactions(hashes: Set[ByteString]): PendingTransactionsManagerAutoPilot =
    copy(pendingTransactions.filterNot(ptx => hashes.contains(ptx.stx.tx.hash.value)))

object PendingTransactionsManagerAutoPilot:

  /** Typed-actor equivalent of the classic `AutoPilot` above, for specs whose `PendingTransactionsManager` stub is a
    * real `ActorRef[PendingTransactionsManager.Command]` (`testKit.createTestProbe` can't run an `AutoPilot` — Pekko
    * Typed's `TestProbe` has no such hook). `GetPendingTransactions` (the classic, sender-based query) is omitted: it
    * isn't a `PendingTransactionsManager.Command` subtype, so it can never arrive on a typed ref.
    */
  implicit private val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  def behavior(
      state: PendingTransactionsManagerAutoPilot = PendingTransactionsManagerAutoPilot()
  ): Behavior[PendingTransactionsManager.Command] =
    Behaviors.receiveMessage {
      case AddUncheckedTransactions(transactions) =>
        val validTxs = SignedTransactionWithSender.getSignedTransactions(transactions)
        behavior(state.addTransactions(validTxs.toSet))

      case AddTransactions(signedTransactions) =>
        behavior(state.addTransactions(signedTransactions))

      case AddOrOverrideTransaction(newStx, _) =>
        val newStxSender = SignedTransaction.getSender(newStx).get
        val obsoleteTxs = state.pendingTransactions
          .filter(ptx => ptx.stx.senderAddress == newStxSender && ptx.stx.tx.tx.nonce == newStx.tx.nonce)
          .map(_.stx.tx.hash.value)

        behavior(
          state.removeTransactions(obsoleteTxs).addTransactions(Set(SignedTransactionWithSender(newStx, newStxSender)))
        )

      case GetPendingTransactionsReq(replyTo) =>
        replyTo ! PendingTransactionsResponse(state.pendingTransactions.toSeq)
        Behaviors.same

      case RemoveTransactions(signedTransactions) =>
        behavior(state.removeTransactions(signedTransactions.map(_.hash.value).toSet))

      case ProperSignedTransactions(transactions, _) =>
        behavior(state.addTransactions(transactions))

      case ClearPendingTransactions =>
        behavior(state.copy(pendingTransactions = Set.empty))

      case _ => Behaviors.same
    }
