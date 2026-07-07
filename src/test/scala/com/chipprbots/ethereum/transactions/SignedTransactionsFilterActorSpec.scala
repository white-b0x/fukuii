package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.Nonce
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.Wei
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.AnnounceTransactions
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.ProperSignedTransactions

/** Actor-layer coverage for [[SignedTransactionsFilterActor]] (RS-08 P1) — the Typed actor was previously exercised
  * only indirectly through [[PendingTransactionsManagerSpec]]'s downstream collaborators. This spec drives the actor
  * directly via its captured `peerEventBus` message-adapter subscriber (see `RegularSyncFixtures.waitForSubscription`
  * for the precedent this mirrors), covering both the small-batch (< 256 txs) and chunked large-batch (>= 256 txs)
  * recovery paths.
  *
  * Coverage gap (documented, not a production change): the `RecoveryFailed` command is a self-send reached only when
  * `getSignedTransactionsSequential` (or the config resolution it depends on) *throws* inside `recoverLargeBatch`'s `IO
  * { ... }.handleErrorWith`. `recoverSenders` swallows individual ECDSA-recovery failures via `Option` (see
  * `SignedTransaction.getSender`/`calculateSender`'s `Try(...).toOption.flatten`), so a malformed signature alone never
  * reaches this path — it would require an actual exception from config resolution or the stateless filter, which is
  * not reachable through the actor's public API without a test-only production seam. Left uncovered per the
  * STOP-and-report scope boundary rather than instrumenting production.
  */
class SignedTransactionsFilterActorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  trait TestSetup extends SecureRandomBuilder:
    val pendingTxProbe: TypedTestProbe[PendingTransactionsManager.Command] = testKit.createTestProbe()
    val peerEventBusProbe: TypedTestProbe[PeerEventBusActor.Command] = testKit.createTestProbe()

    val filterActor: TypedActorRef[SignedTransactionsFilterActor.Command] =
      testKit.spawn(SignedTransactionsFilterActor(pendingTxProbe.ref, peerEventBusProbe.ref))

    // Capture the message-adapter subscriber the actor registers with peerEventBus on startup, then drive every
    // case directly through it — the recipe RegularSyncFixtures.waitForSubscription uses.
    val subscriber: TypedActorRef[PeerEvent] = peerEventBusProbe
      .fishForMessage(max = 5.seconds) {
        case SubscribeCmd(_: MessageClassifier, _) => FishingOutcomes.complete
        case _                                     => FishingOutcomes.continueAndIgnore
      }
      .head
      .asInstanceOf[SubscribeCmd]
      .subscriber

    val peerId: PeerId = PeerId("peer1")

    /** A real, independently-signed legacy transfer — sender recovery must succeed deterministically so chunk
      * arrival/ordering assertions in the large-batch test don't depend on the ~50% chance a dummy R/S recovers to
      * *some* curve point.
      */
    def signedTx(
        nonce: BigInt,
        keyPair: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
    ): (SignedTransaction, Address) =
      val tx = LegacyTransaction(
        nonce = Nonce(nonce),
        gasPrice = GasPrice(BigInt("1000000000")),
        gasLimit = GasAmount(21000),
        receivingAddress = Some(Address(42)),
        value = Wei(0),
        payload = ByteString.empty
      )
      (SignedTransaction.sign(tx, keyPair, Some(ChainId(BigInt(0x3d)))), Address(keyPair))

  "SignedTransactionsFilterActor" should "recover senders for a small batch (< 256 txs) and forward ProperSignedTransactions" taggedAs UnitTest in new TestSetup:
    val (tx1, addr1) = signedTx(0)
    val (tx2, addr2) = signedTx(1)
    val (tx3, addr3) = signedTx(2)

    subscriber ! MessageFromPeer(SignedTransactions(Seq(tx1, tx2, tx3)), peerId)

    val received: ProperSignedTransactions = pendingTxProbe.expectMessageType[ProperSignedTransactions]
    received.peerId shouldBe peerId
    received.signedTransactions shouldBe Set(
      SignedTransactionWithSender(tx1, addr1),
      SignedTransactionWithSender(tx2, addr2),
      SignedTransactionWithSender(tx3, addr3)
    )
    pendingTxProbe.expectNoMessage(Timeouts.shortTimeout)

  it should "handle a large batch (>= 256 txs): announce immediately, then flush recovered chunks in strict index order" taggedAs UnitTest in new TestSetup:
    val totalTxs = 260
    val chunkSize = 50
    val expectedChunkCount = 6 // ceil(260 / 50)

    val keyPair: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
    val signed: Vector[(SignedTransaction, Address)] = (0 until totalTxs).toVector.map(n => signedTx(n, keyPair))
    val txs: Vector[SignedTransaction] = signed.map(_._1)

    subscriber ! MessageFromPeer(SignedTransactions(txs), peerId)

    // (a) The stateless-valid set is announced once, immediately — before any chunk recovery completes.
    val announce: AnnounceTransactions = pendingTxProbe.expectMessageType[AnnounceTransactions]
    announce.peerId shouldBe peerId
    announce.signedTransactions should contain theSameElementsAs txs

    // (b) + (c) ProperSignedTransactions arrives once per 50-tx chunk. flushRecoveredChunks buffers by index and
    // only emits when the NEXT expected index is available, so the messages arrive in strict ascending chunk order
    // regardless of parTraverseN's real (non-deterministic) completion order. We verify this by tagging each
    // received message with its chunk index — recovered from the nonce range, since chunk i covers nonces
    // [i*50, i*50+49] — and asserting the observed sequence is exactly 0..5.
    val received: IndexedSeq[ProperSignedTransactions] =
      (1 to expectedChunkCount).map(_ =>
        pendingTxProbe.expectMessageType[ProperSignedTransactions](Timeouts.normalTimeout)
      )
    received.foreach(_.peerId shouldBe peerId)

    val chunkIndices: IndexedSeq[Int] = received.map { msg =>
      val minNonce = msg.signedTransactions.map(_.tx.tx.nonce.value).min
      (minNonce / chunkSize).toInt
    }
    chunkIndices shouldBe (0 until expectedChunkCount)

    // Aggregate multiset check: every recovered sender across all chunks matches the original input, no drops/dupes.
    val recovered: Set[(SignedTransaction, Address)] =
      received.flatMap(_.signedTransactions).map(s => s.tx -> s.senderAddress).toSet
    recovered shouldBe signed.toSet

    pendingTxProbe.expectNoMessage(Timeouts.shortTimeout)

  it should "default to an empty tx list (and not throw) when the peer event carries an unexpected message type" taggedAs UnitTest in new TestSetup:
    // The message adapter's fallback branch: any MessageFromPeer whose payload isn't SignedTransactions becomes
    // PeerSignedTransactions(SignedTransactions(Nil), peerId) rather than throwing.
    subscriber ! MessageFromPeer(WireProtocol.Pong(), peerId)

    // recoverSmallBatch(Nil, peerId) recovers zero transactions — nothing is forwarded.
    pendingTxProbe.expectNoMessage(Timeouts.shortTimeout)

    // The actor must still be alive and process subsequent traffic normally.
    val (tx, addr) = signedTx(0)
    subscriber ! MessageFromPeer(SignedTransactions(Seq(tx)), peerId)
    val received: ProperSignedTransactions = pendingTxProbe.expectMessageType[ProperSignedTransactions]
    received.signedTransactions shouldBe Set(SignedTransactionWithSender(tx, addr))
