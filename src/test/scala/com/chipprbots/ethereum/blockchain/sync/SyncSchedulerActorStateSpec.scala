package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import org.scalatest.flatspec.AnyFlatSpecLike

import com.chipprbots.ethereum.blockchain.sync.fast.DownloaderState
import com.chipprbots.ethereum.blockchain.sync.fast.SyncSchedulerActorState
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.ProcessingStatistics
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.SchedulerState
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.StateNode
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.StateNodeRequest
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.RequestFailed
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StateSyncStats
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.SyncStateSchedulerActorResponse
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.*

/** Coverage for [[SyncSchedulerActorState]] (RS08-REMAINDER-01 P2) — a 124-line pure case class with zero prior direct
  * coverage (only reached indirectly via `StateSyncSpec`'s property-based E2E fixture). Mirrors
  * [[SyncStateDownloaderStateSpec]]'s plain `AnyFlatSpecLike` style.
  */
class SyncSchedulerActorStateSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike:

  "SyncSchedulerActorState" should "report no remaining pending requests when the scheduler state is empty" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    assert(!initialState.hasRemainingPendingRequests)

  it should "report remaining pending requests once the scheduler state has an active request" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val schedulerState: SchedulerState = SchedulerState().schedule(request(hash1))
    val state: SyncSchedulerActorState = initialState.copy(currentSchedulerState = schedulerState)
    assert(state.hasRemainingPendingRequests)

  it should "toggle isProcessing via initProcessing/finishProcessing" taggedAs (UnitTest, SyncTest) in new TestSetup:
    assert(!initialState.isProcessing)
    val processing: SyncSchedulerActorState = initialState.initProcessing
    assert(processing.isProcessing)
    assert(!processing.finishProcessing.isProcessing)

  it should "have no restart requested initially, and report it once withRestartRequested is applied" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    assert(!initialState.restartHasBeenRequested)
    assert(initialState.restartRequested.isEmpty)
    val withRestart: SyncSchedulerActorState = initialState.withRestartRequested(syncInitiator.ref)
    assert(withRestart.restartHasBeenRequested)
    assert(withRestart.restartRequested.contains(syncInitiator.ref))

  it should "enqueue and dequeue request results in FIFO order" taggedAs (UnitTest, SyncTest) in new TestSetup:
    val result1: RequestFailed = RequestFailed(peer1, "reason1")
    val result2: RequestFailed = RequestFailed(peer2, "reason2")
    val withBoth: SyncSchedulerActorState =
      initialState.withNewRequestResult(result1).withNewRequestResult(result2)
    assert(withBoth.numberOfRemainingRequests == 2)

    val (dequeued1, afterFirst) = withBoth.getRequestToProcess.get
    assert(dequeued1 == result1)
    assert(afterFirst.numberOfRemainingRequests == 1)

    val (dequeued2, afterSecond) = afterFirst.getRequestToProcess.get
    assert(dequeued2 == result2)
    assert(afterSecond.numberOfRemainingRequests == 0)
    assert(afterSecond.getRequestToProcess.isEmpty)

  it should "replace scheduler/downloader state and stats via withNewProcessingResults" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val newSchedulerState: SchedulerState = SchedulerState().schedule(request(hash1))
    val newDownloaderState: DownloaderState = DownloaderState().scheduleNewNodesForRetrieval(Seq(hash1))
    val newStats: ProcessingStatistics = ProcessingStatistics().addSaved(5)

    val updated: SyncSchedulerActorState =
      initialState.withNewProcessingResults(newSchedulerState, newDownloaderState, newStats)

    assert(updated.currentSchedulerState == newSchedulerState)
    assert(updated.currentDownloaderState == newDownloaderState)
    assert(updated.currentStats == newStats)

  it should "replace only the downloader state via withNewDownloaderState" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val originalSchedulerState: SchedulerState = initialState.currentSchedulerState
    val newDownloaderState: DownloaderState = DownloaderState().scheduleNewNodesForRetrieval(Seq(hash1))

    val updated: SyncSchedulerActorState = initialState.withNewDownloaderState(newDownloaderState)

    assert(updated.currentDownloaderState == newDownloaderState)
    assert(updated.currentSchedulerState == originalSchedulerState)

  it should "enrich assigned peer requests with nibble-path info from the scheduler state" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val nibblePath: Seq[Byte] = Seq(3.toByte, 5.toByte)
    val accountHash: ByteString = ByteString("account-hash")
    val enrichedRequest: StateNodeRequest =
      StateNodeRequest(hash1, None, StateNode, Seq(), 0, 0, nibblePath = nibblePath, accountHash = Some(accountHash))
    val schedulerState: SchedulerState = SchedulerState().schedule(enrichedRequest)
    val state: SyncSchedulerActorState = initialState.copy(currentSchedulerState = schedulerState)

    val (requests, _) = state.assignTasksToPeers(NonEmptyList.one(peer1), nodesPerPeer = 10)

    assert(requests.size == 1)
    val peerRequest = requests.head
    assert(peerRequest.nodes.toList.contains(hash1))
    assert(peerRequest.pathInfo(hash1) == ((nibblePath, Some(accountHash))))

  trait TestSetup:
    val syncInitiator: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SyncStateSchedulerActorResponse] =
      testKit.createTestProbe[SyncStateSchedulerActorResponse]()
    val statsInitiator: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[StateSyncStats] =
      testKit.createTestProbe[StateSyncStats]()

    val ref1: TypedActorRef[PeerActor.Command] = testKit.createTestProbe[PeerActor.Command]().ref
    val ref2: TypedActorRef[PeerActor.Command] = testKit.createTestProbe[PeerActor.Command]().ref

    val peer1: Peer = Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 1), ref1, incomingConnection = false)
    val peer2: Peer = Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.1", 2), ref2, incomingConnection = false)

    val hash1: ByteString = ByteString("hash-1")

    def request(hash: ByteString): StateNodeRequest =
      StateNodeRequest(hash, None, StateNode, Seq(), 0, 0)

    val initialState: SyncSchedulerActorState =
      SyncSchedulerActorState.initial(
        SchedulerState(),
        ProcessingStatistics(),
        1,
        syncInitiator.ref,
        statsInitiator.ref
      )
