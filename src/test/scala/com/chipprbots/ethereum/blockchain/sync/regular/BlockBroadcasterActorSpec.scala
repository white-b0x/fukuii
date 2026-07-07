package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TotalDifficulty
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.PeerDisconnectedClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.ActorsTesting.expectMessagePF
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Actor-layer coverage for [[BlockBroadcasterActor]] (RS-08 P1) —
  * [[com.chipprbots.ethereum.blockchain.sync.BlockBroadcastSpec]] already covers `BlockBroadcast`'s pure gating logic
  * (ETH68/ETH69/BRU fanout, PoW/PoS) with zero actor involvement; this spec covers the actor-only concerns layered on
  * top of it: the `ScanPeers` timer, the `WrappedHandshakedPeers`/`WrappedPeerDisconnected` adapter wiring into
  * `PeerListHelper`, and — the actor's reason for existing (see the `AnnounceCanonicalHead` scaladoc in the production
  * file) — `latestCanonicalHead` retention across scans so a peer discovered *after* a canonical-head announce still
  * receives it.
  */
class BlockBroadcasterActorSpec extends ScalaTestWithActorTestKit(ManualTime.config) with AnyFlatSpecLike with Matchers:

  val manualTime: ManualTime = ManualTime()

  trait TestSetup extends TestSyncConfig:
    val networkPeerManagerProbe: TypedTestProbe[NetworkPeerManagerActor.Command] = testKit.createTestProbe()
    val peerEventBusProbe: TypedTestProbe[PeerEventBusActor.Command] = testKit.createTestProbe()
    val blacklist: CacheBasedBlacklist = CacheBasedBlacklist.empty(100)
    val broadcast = new BlockBroadcast(networkPeerManagerProbe.ref, isPoWChain = true)

    override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(peersScanInterval = 5.seconds)

    val broadcaster: TypedActorRef[BlockBroadcasterActor.BroadcasterMsg] =
      testKit.spawn(
        BlockBroadcasterActor(broadcast, peerEventBusProbe.ref, networkPeerManagerProbe.ref, blacklist, syncConfig)
      )

    val baseHeader: BlockHeader = Fixtures.Blocks.Block3125369.header

    def peerInfoFor(maxBlockNumber: BigInt): PeerInfo =
      val status = RemoteStatus(
        capability = Capability.ETH68,
        networkId = 1,
        chainWeight = ChainWeight.totalDifficultyOnly(TotalDifficulty(BigInt(10000))),
        bestHash = baseHeader.hash.value,
        genesisHash = Fixtures.Blocks.Genesis.header.hash.value
      )
      PeerInfo(
        remoteStatus = status,
        chainWeight = status.chainWeight,
        forkAccepted = true,
        maxBlockNumber = maxBlockNumber,
        bestBlockHash = status.bestHash
      )

    def newPeer(name: String): Peer =
      Peer(PeerId(name), new InetSocketAddress("127.0.0.1", 0), testKit.createTestProbe[PeerActor.Command]().ref, false)

    /** Reply to the next `GetHandshakedPeersCmd` this test hasn't yet consumed with the given peer map. */
    def replyWithHandshakedPeers(peers: Map[Peer, PeerInfo]): Unit =
      networkPeerManagerProbe.expectMessagePF() { case GetHandshakedPeersCmd(replyTo) =>
        replyTo ! HandshakedPeers(peers)
      }

    /** Capture the `peerDisconnectedAdapter` subscriber PeerListHelper registers for a given peer — mirrors
      * RegularSyncFixtures.waitForSubscription, scoped to PeerDisconnectedClassifier subscriptions only.
      */
    def capturePeerDisconnectedAdapter(): TypedActorRef[PeerEvent] =
      peerEventBusProbe
        .fishForMessage(max = 5.seconds) {
          case SubscribeCmd(_: PeerDisconnectedClassifier, _) => FishingOutcomes.complete
          case _                                              => FishingOutcomes.continueAndIgnore
        }
        .head
        .asInstanceOf[SubscribeCmd]
        .subscriber

  "BlockBroadcasterActor" should "poll for handshaked peers immediately on startup" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    networkPeerManagerProbe.expectMessageType[GetHandshakedPeersCmd]

  it should "re-poll on the ScanPeers fixed-delay timer, without double-firing before the interval elapses" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    networkPeerManagerProbe.expectMessageType[GetHandshakedPeersCmd] // startup poll

    networkPeerManagerProbe.expectNoMessage(200.millis)
    manualTime.timePasses(5.seconds)
    networkPeerManagerProbe.expectMessageType[GetHandshakedPeersCmd]

    networkPeerManagerProbe.expectNoMessage(200.millis)
    manualTime.timePasses(5.seconds)
    networkPeerManagerProbe.expectMessageType[GetHandshakedPeersCmd]

  it should "wire WrappedHandshakedPeers replies into PeerListHelper so a subsequent broadcast reaches the peer" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // replyWithHandshakedPeers consumes the startup GetHandshakedPeersCmd and replies in one step.
    val peer: Peer = newPeer("peer1")
    replyWithHandshakedPeers(Map(peer -> peerInfoFor(maxBlockNumber = 100)))

    val block: Block = Block(baseHeader.copy(number = BlockNumber(101)), BlockBody(Nil, Nil))
    broadcaster ! BlockBroadcasterActor.BroadcastBlock(
      BlockToBroadcast(block, ChainWeight.totalDifficultyOnly(TotalDifficulty(BigInt(20000))))
    )

    networkPeerManagerProbe.expectMessagePF() {
      case NetworkPeerManagerActor.SendMessageCmd(_, id) if id == peer.id => ()
    }

  it should "retain the latest AnnounceCanonicalHead and re-announce it to a peer discovered by a LATER scan, but not to a peer already known at announce time" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // A peer already known BEFORE the canonical-head announce.
    // replyWithHandshakedPeers consumes the startup GetHandshakedPeersCmd and replies in one step.
    val knownPeer: Peer = newPeer("known-peer")
    replyWithHandshakedPeers(Map(knownPeer -> peerInfoFor(maxBlockNumber = 100)))

    val header: BlockHeader = baseHeader.copy(number = BlockNumber(500))
    broadcaster ! BlockBroadcasterActor.AnnounceCanonicalHead(header)

    // Announced immediately to the already-known peer.
    networkPeerManagerProbe.expectMessagePF() {
      case NetworkPeerManagerActor.SendMessageCmd(_, id) if id == knownPeer.id => ()
    }
    networkPeerManagerProbe.expectNoMessage(200.millis)

    // A LATER scan discovers a second peer that was not present at announce time.
    // replyWithHandshakedPeers consumes the scan's GetHandshakedPeersCmd and replies in one step.
    manualTime.timePasses(5.seconds)

    val newlyDiscoveredPeer: Peer = newPeer("late-peer")
    replyWithHandshakedPeers(
      Map(
        knownPeer -> peerInfoFor(maxBlockNumber = 100),
        newlyDiscoveredPeer -> peerInfoFor(maxBlockNumber = 100)
      )
    )

    // Only the newly-discovered peer gets the retained head re-announced — the already-known peer is not re-spammed.
    networkPeerManagerProbe.expectMessagePF() {
      case NetworkPeerManagerActor.SendMessageCmd(_, id) if id == newlyDiscoveredPeer.id => ()
    }
    networkPeerManagerProbe.expectNoMessage(200.millis)

  it should "wire WrappedPeerDisconnected so a disconnected peer no longer receives subsequent broadcasts" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // replyWithHandshakedPeers consumes the startup GetHandshakedPeersCmd and replies in one step.
    val peer: Peer = newPeer("peer1")
    replyWithHandshakedPeers(Map(peer -> peerInfoFor(maxBlockNumber = 100)))

    val disconnectedAdapter: TypedActorRef[PeerEvent] = capturePeerDisconnectedAdapter()
    disconnectedAdapter ! PeerDisconnected(peer.id)

    val block: Block = Block(baseHeader.copy(number = BlockNumber(101)), BlockBody(Nil, Nil))
    broadcaster ! BlockBroadcasterActor.BroadcastBlock(
      BlockToBroadcast(block, ChainWeight.totalDifficultyOnly(TotalDifficulty(BigInt(20000))))
    )

    networkPeerManagerProbe.expectNoMessage(Timeouts.shortTimeout)

  // Thin pass-through checks only — BlockBroadcastSpec owns the gating matrix (ETH68/ETH69/BRU/PoW-PoS).
  it should "delegate BroadcastBlock to broadcast.broadcastBlock with the current handshaked-peer map" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // replyWithHandshakedPeers consumes the startup GetHandshakedPeersCmd and replies in one step.
    val peer: Peer = newPeer("peer1")
    replyWithHandshakedPeers(Map(peer -> peerInfoFor(maxBlockNumber = 100)))

    val block: Block = Block(baseHeader.copy(number = BlockNumber(101)), BlockBody(Nil, Nil))
    broadcaster ! BlockBroadcasterActor.BroadcastBlock(
      BlockToBroadcast(block, ChainWeight.totalDifficultyOnly(TotalDifficulty(BigInt(20000))))
    )

    networkPeerManagerProbe.expectMessagePF() {
      case NetworkPeerManagerActor.SendMessageCmd(_, id) if id == peer.id => ()
    }

  it should "delegate BroadcastBlocks (plural) to broadcast.broadcastBlock for each block" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // replyWithHandshakedPeers consumes the startup GetHandshakedPeersCmd and replies in one step.
    val peer: Peer = newPeer("peer1")
    replyWithHandshakedPeers(Map(peer -> peerInfoFor(maxBlockNumber = 100)))

    val block1: Block = Block(baseHeader.copy(number = BlockNumber(101)), BlockBody(Nil, Nil))
    val block2: Block = Block(baseHeader.copy(number = BlockNumber(102)), BlockBody(Nil, Nil))
    broadcaster ! BlockBroadcasterActor.BroadcastBlocks(
      List(
        BlockToBroadcast(block1, ChainWeight.totalDifficultyOnly(TotalDifficulty(BigInt(20000)))),
        BlockToBroadcast(block2, ChainWeight.totalDifficultyOnly(TotalDifficulty(BigInt(20001))))
      )
    )

    val messages: Seq[AnyRef] = networkPeerManagerProbe.receiveMessages(4, Timeouts.normalTimeout)
    messages.collect { case NetworkPeerManagerActor.SendMessageCmd(_, id) => id }.distinct shouldBe Seq(peer.id)
