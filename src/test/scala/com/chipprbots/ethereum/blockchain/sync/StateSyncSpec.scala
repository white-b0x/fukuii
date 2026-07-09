package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress
import java.util.concurrent.ThreadLocalRandom

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Random

import org.scalactic.anyvals.PosInt
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.blockchain.sync.StateSyncUtils.MptNodeData
import com.chipprbots.ethereum.blockchain.sync.StateSyncUtils.TrieProvider
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.RestartRequested
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StartSyncingTo
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StateSyncFinished
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StateSyncStats
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.WaitingForNewTargetBlock
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TotalDifficulty
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.*
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData.GetNodeDataEnc
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

class StateSyncSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with ScalaCheckPropertyChecks:

  // those tests are somewhat long running 3 successful evaluation should be fine
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = PosInt(3))

  "StateSync" should "sync state to different tries" taggedAs (UnitTest, SyncTest) in new TestSetup():
    forAll(ObjectGenerators.genMultipleNodeData(1000)) { nodeData =>
      val fixture = newSchedulerFixture()
      try
        val trieProvider = TrieProvider()
        val target = trieProvider.buildWorld(nodeData)
        setAutoPilotWithProvider(fixture.networkPeerManager, fixture.peerEventBus, trieProvider)
        fixture.actor ! StartSyncingTo(TrieRoot(target), 1)
        fixture.syncInitResponse.expectMessage(20.seconds, StateSyncFinished)
      finally testKit.stop(fixture.actor)
    }

  it should "sync state to different tries when peers provide different set of data each time" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup():
    forAll(ObjectGenerators.genMultipleNodeData(1000)) { nodeData =>
      val fixture = newSchedulerFixture()
      try
        val trieProvider1 = TrieProvider()
        val target = trieProvider1.buildWorld(nodeData)
        setAutoPilotWithProvider(fixture.networkPeerManager, fixture.peerEventBus, trieProvider1, partialResponseConfig)
        fixture.actor ! StartSyncingTo(TrieRoot(target), 1)
        fixture.syncInitResponse.expectMessage(20.seconds, StateSyncFinished)
      finally testKit.stop(fixture.actor)
    }

  it should "sync state to different tries when peer provide mixed responses" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup():
    forAll(ObjectGenerators.genMultipleNodeData(1000)) { nodeData =>
      val fixture = newSchedulerFixture()
      try
        val trieProvider1 = TrieProvider()
        val target = trieProvider1.buildWorld(nodeData)
        setAutoPilotWithProvider(fixture.networkPeerManager, fixture.peerEventBus, trieProvider1, mixedResponseConfig)
        fixture.actor ! StartSyncingTo(TrieRoot(target), 1)
        fixture.syncInitResponse.expectMessage(20.seconds, StateSyncFinished)
      finally testKit.stop(fixture.actor)
    }

  it should "restart state sync when requested" taggedAs (UnitTest, SyncTest) in new TestSetup():
    forAll(ObjectGenerators.genMultipleNodeData(1000)) { nodeData =>
      val fixture = newSchedulerFixture()
      try
        val trieProvider1 = TrieProvider()
        val target = trieProvider1.buildWorld(nodeData)
        setAutoPilotWithProvider(fixture.networkPeerManager, fixture.peerEventBus, trieProvider1)
        fixture.actor ! StartSyncingTo(TrieRoot(target), 1)
        fixture.actor ! RestartRequested
        // Stats go to syncInitStats; responses go to syncInitResponse — wait directly for WaitingForNewTargetBlock.
        fixture.syncInitResponse.expectMessage(20.seconds, WaitingForNewTargetBlock)
      finally testKit.stop(fixture.actor)
    }

  it should "start state sync when receiving start signal while bloom filter is loading" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup():
    override def buildBlockChain(): (BlockchainReader, BlockchainImpl) =
      val storages = getNewStorages.storages
      val blockchainReader = BlockchainReader(storages)
      (blockchainReader, BlockchainImpl(storages, blockchainReader))

    val fixture = newSchedulerFixture()
    val nodeData: IndexedSeq[MptNodeData] = (0 until 1000).map(i => MptNodeData(Address(i), None, Seq(), i))
    val trieProvider1: TrieProvider = TrieProvider()
    val target: ByteString = trieProvider1.buildWorld(nodeData)
    setAutoPilotWithProvider(fixture.networkPeerManager, fixture.peerEventBus, trieProvider1)
    fixture.actor ! StartSyncingTo(TrieRoot(target), 1)
    fixture.syncInitResponse.expectMessage(20.seconds, StateSyncFinished)

  class TestSetup extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: ActorSystem = StateSyncSpec.this.system.classicSystem
    type PeerConfig = Map[PeerId, PeerAction]

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(TotalDifficulty(10000)),
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value
    )
    val initialPeerInfo: PeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      chainWeight = peerStatus.chainWeight,
      forkAccepted = true,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number.value,
      bestBlockHash = peerStatus.bestHash
    )

    val trieProvider =
      new TrieProvider(blockchain, blockchainReader, getNewStorages.storages.evmCodeStorage, blockchainConfig)

    val peersMap: Map[Peer, PeerInfo] = (1 to 8).map { i =>
      (
        Peer(
          PeerId(s"peer$i"),
          new InetSocketAddress("127.0.0.1", i),
          testKit.createTestProbe[PeerActor.Command](i.toString).ref,
          incomingConnection = false
        ),
        initialPeerInfo
      )
    }.toMap

    sealed trait PeerAction
    case object FullResponse extends PeerAction
    case object PartialResponse extends PeerAction
    case object NoResponse extends PeerAction

    val defaultPeerConfig: PeerConfig = peersMap.map { case (peer, _) =>
      peer.id -> FullResponse
    }

    val maxMptNodeRequest = 50
    val minMptNodeRequest = 20
    val partialResponseConfig: PeerConfig = peersMap.map { case (peer, _) =>
      peer.id -> PartialResponse
    }

    val mixedResponseConfig: PeerConfig = peersMap.map { case (peer, _) =>
      if peer.remoteAddress.getPort <= 3 then peer.id -> FullResponse
      else if peer.remoteAddress.getPort > 3 && peer.remoteAddress.getPort <= 6 then peer.id -> PartialResponse
      else peer.id -> NoResponse
    }

    // Typed analogue of the Classic AutoPilot: a mock NetworkPeerManagerActor whose per-message side
    // effect is a swappable handler (installed by setAutoPilotWithProvider). The scrutinee is a sealed
    // NetworkPeerManagerActor.Command (not Any), so no E165 unchecked-match warning. The spec never
    // inspects messages sent here, so no observation probe is needed. Created fresh per scheduler fixture
    // (see newSchedulerFixture) so a stale request from a previous forAll iteration cannot reach the
    // current iteration's handler.
    final class NetworkPeerManagerMock:
      private val handlerRef =
        new java.util.concurrent.atomic.AtomicReference[NetworkPeerManagerActor.Command => Unit](_ => ())
      def setHandler(f: NetworkPeerManagerActor.Command => Unit): Unit = handlerRef.set(f)
      val ref: TypedActorRef[NetworkPeerManagerActor.Command] =
        testKit.spawn(Behaviors.receiveMessage[NetworkPeerManagerActor.Command] { msg =>
          handlerRef.get()(msg)
          Behaviors.same
        })

    // Responses are delivered by PUBLISHING MessageFromPeer to a real PeerEventBusActor, not by a direct
    // reply to the request sender. The Typed PeerRequestHandler receives peer responses only through a
    // PeerEventBus subscription (SubscribeCmd + MessageClassifier); a direct reply would be invisible to
    // the handler and the scheduler stalls (StateSyncFinished never arrives). Mirrors PeerRequestHandlerSpec's
    // PublishCmd path.
    def setAutoPilotWithProvider(
        networkPeerManager: NetworkPeerManagerMock,
        peerEventBus: TypedActorRef[PeerEventBusActor.Command],
        trieProvider: TrieProvider,
        peerConfig: PeerConfig = defaultPeerConfig
    ): Unit =
      given scala.concurrent.ExecutionContext = classicSystem.dispatcher
      // The Typed PeerRequestHandler sends SendMessageCmd and *then* subscribes to the PEB. Publishing the
      // response synchronously here can race ahead of that subscription and be dropped (the PEB only delivers
      // to current subscribers). Production is immune because real network latency guarantees the subscription
      // is registered before any response arrives; a tiny scheduler delay reproduces that ordering deterministically.
      def publishResponse(responseMsg: NodeData, peer: PeerId): Unit =
        classicSystem.scheduler.scheduleOnce(20.milliseconds)(
          peerEventBus ! PeerEventBusActor.PublishCmd(MessageFromPeer(responseMsg, peer))
        )
      networkPeerManager.setHandler {
        case SendMessageCmd(msg: GetNodeDataEnc, peer) =>
          peerConfig(peer) match
            case FullResponse =>
              val responseMsg =
                NodeData(trieProvider.getNodes(msg.underlyingMsg.mptElementsHashes.toList).map(_.data))
              publishResponse(responseMsg, peer)
            case PartialResponse =>
              val random: ThreadLocalRandom = ThreadLocalRandom.current()
              val elementsToServe = random.nextInt(minMptNodeRequest, maxMptNodeRequest + 1)
              val toGet = msg.underlyingMsg.mptElementsHashes.toList.take(elementsToServe)
              val responseMsg = NodeData(trieProvider.getNodes(toGet).map(_.data))
              publishResponse(responseMsg, peer)
            case NoResponse => ()

        case GetHandshakedPeersCmd(replyTo) =>
          replyTo ! HandshakedPeers(peersMap)

        case _ => ()
      }

    override lazy val syncConfig: Config.SyncConfig = defaultSyncConfig.copy(
      peersScanInterval = 0.5.second,
      nodesPerRequest = maxMptNodeRequest,
      blacklistDuration = 1.second,
      peerResponseTimeout = 1.second,
      syncRetryInterval = 50.milliseconds
    )

    def buildBlockChain(): (BlockchainReader, BlockchainImpl) =
      val storages = getNewStorages.storages
      (
        BlockchainReader(storages),
        BlockchainImpl(storages, BlockchainReader(storages))
      )

    def genRandomArray(): Array[Byte] =
      val arr = new Array[Byte](32)
      Random.nextBytes(arr)
      arr

    def genRandomByteString(): ByteString =
      ByteString.fromArrayUnsafe(genRandomArray())

    // A fully isolated scheduler + its collaborator probes. The scheduler actor is a stateful,
    // long-lived actor with a ScanPeers timer and PeerRequestHandler children; reusing one instance
    // across ScalaCheck forAll iterations lets an in-flight (or timer-scheduled) GetNodeData request
    // for iteration N-1's random trie reach iteration N's rebound mock handler, whose single-trie
    // TrieProvider lacks those hashes -> "Missing expected data in storage" / a stalled StateSyncFinished.
    // Spawning a fresh fixture per iteration (and stopping it afterwards) removes the cross-talk.
    final case class SchedulerFixture(
        actor: TypedActorRef[SyncStateSchedulerActor.Command],
        networkPeerManager: NetworkPeerManagerMock,
        peerEventBus: TypedActorRef[PeerEventBusActor.Command],
        syncInitResponse: TypedTestProbe[SyncStateSchedulerActor.SyncStateSchedulerActorResponse],
        syncInitStats: TypedTestProbe[SyncStateSchedulerActor.StateSyncStats]
    )

    def newSchedulerFixture(): SchedulerFixture =
      val networkPeerManager = new NetworkPeerManagerMock
      val syncInitResponse = testKit.createTestProbe[SyncStateSchedulerActor.SyncStateSchedulerActorResponse]()
      val syncInitStats = testKit.createTestProbe[SyncStateSchedulerActor.StateSyncStats]()
      // A real PeerEventBusActor: the Typed PeerRequestHandler children the scheduler spawns receive peer
      // responses via a PeerEventBus subscription, so the AutoPilot must publish MessageFromPeer here (see
      // setAutoPilotWithProvider). A bare probe cannot route SubscribeCmd, which is why responses were lost.
      val peerEventBus: TypedActorRef[PeerEventBusActor.Command] = testKit.spawn(PeerEventBusActor.behavior())
      val blacklist: Blacklist = CacheBasedBlacklist.empty(100)
      val (blockchainReader, _) = buildBlockChain()
      val actor = testKit.spawn(
        SyncStateSchedulerActor.behavior(
          SyncStateScheduler(
            blockchainReader,
            getNewStorages.storages.evmCodeStorage,
            getNewStorages.storages.stateStorage,
            getNewStorages.storages.nodeStorage,
            syncConfig.stateSyncBloomFilterSize
          ),
          syncConfig,
          networkPeerManager.ref,
          peerEventBus,
          blacklist,
          syncInitResponse.ref,
          syncInitStats.ref
        )
      )
      SchedulerFixture(actor, networkPeerManager, peerEventBus, syncInitResponse, syncInitStats)
