package com.chipprbots.ethereum.blockchain.sync.fast

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.LoggingTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Random

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason.InvalidStateResponse
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.CannotDecodeMptNode
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.Command
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.Critical
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.DownloaderError
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.PrintInfo
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.ProcessingResult
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.RestartRequested
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StartSyncingTo
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StateSyncFinished
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StateSyncStats
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.SyncStateSchedulerActorResponse
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.WaitingForNewTargetBlock
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** Discrete state-transition coverage for [[SyncStateSchedulerActor]] (RS08-REMAINDER-01 P2) — a 703-line actor
  * previously only reached indirectly through [[StateSyncSpec]]'s property-based end-to-end fixture. Uses a real
  * `ScalaTestWithActorTestKit` dispatcher (not `BehaviorTestKit`): `behavior()`'s `Behaviors.setup` launches an async
  * IO fiber (`loadFilterFromBlockchain...unsafeRunSync`, self-sending `BloomFilterResult`) that `BehaviorTestKit`
  * cannot drive.
  *
  * `ProcessingResult` (with its `Critical`/`DownloaderError` payloads) is one of the few `Command` subtypes NOT marked
  * `private` inside the `SyncStateSchedulerActor` object, so it is this spec's lever for reaching the actor's internal
  * `consecutiveUselessResponses` counter and its critical-error termination path without a full simulated peer/network
  * round trip (mirrored from `StateSyncSpec`'s heavier `AutoPilot`-based fixture).
  */
class SyncStateSchedulerActorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  "SyncStateSchedulerActor" should "reply WaitingForNewTargetBlock to RestartRequested while idle" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    actor ! RestartRequested
    syncInitResponse.expectMessage(5.seconds, WaitingForNewTargetBlock)

  it should "reply StateSyncFinished immediately for an empty state root, without entering syncing" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
    actor ! StartSyncingTo(TrieRoot(emptyRoot), 1)
    syncInitResponse.expectMessage(5.seconds, StateSyncFinished)

  it should "stay idle after PrintInfo, still answering RestartRequested normally" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    actor ! PrintInfo
    syncInitResponse.expectNoMessage(200.millis)
    actor ! RestartRequested
    syncInitResponse.expectMessage(5.seconds, WaitingForNewTargetBlock)

  it should "self-restart after 20 consecutive useless responses" taggedAs (UnitTest, SyncTest) in new TestSetup:
    val root: ByteString = genRandomByteString()
    actor ! StartSyncingTo(TrieRoot(root), 1)
    syncInitResponse.expectNoMessage(500.millis)

    val peer: Peer = newPeer("useless-peer")
    def uselessResponse: ProcessingResult =
      ProcessingResult(Left(DownloaderError(DownloaderState(), peer, Some(InvalidStateResponse("no useful data")))))

    (1 until 20).foreach(_ => actor ! uselessResponse)
    syncInitResponse.expectNoMessage(200.millis)

    actor ! uselessResponse
    syncInitResponse.expectMessage(5.seconds, WaitingForNewTargetBlock)

  it should "terminate when processing yields a Critical error" taggedAs (UnitTest, SyncTest) in new TestSetup:
    val root: ByteString = genRandomByteString()
    actor ! StartSyncingTo(TrieRoot(root), 1)
    syncInitResponse.expectNoMessage(500.millis)

    val deathProbe: TypedTestProbe[Nothing] = testKit.createTestProbe[Nothing]()
    // The test logback config's root level is ERROR, so — unlike DEBUG/INFO — this log assertion is reliable
    // regardless of the actor's effective logger name.
    LoggingTestKit.error("Critical error while state syncing").expect {
      actor ! ProcessingResult(Left(Critical(CannotDecodeMptNode)))
    }
    deathProbe.expectTerminated(actor)

  trait TestSetup extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: ActorSystem = SyncStateSchedulerActorSpec.this.system.classicSystem

    override lazy val syncConfig: Config.SyncConfig = defaultSyncConfig.copy(
      peersScanInterval = 1.hour,
      syncRetryInterval = 30.seconds
    )

    val networkPeerManager: TestProbe = TestProbe()
    val syncInitResponse: TypedTestProbe[SyncStateSchedulerActorResponse] =
      testKit.createTestProbe[SyncStateSchedulerActorResponse]()
    val syncInitStats: TypedTestProbe[StateSyncStats] = testKit.createTestProbe[StateSyncStats]()
    val peerEventBus: TypedActorRef[PeerEventBusActor.Command] = testKit.spawn(PeerEventBusActor.behavior())
    val blacklist: Blacklist = CacheBasedBlacklist.empty(100)

    val storages = getNewStorages.storages
    override lazy val blockchainReader: BlockchainReader = BlockchainReader(storages)
    val sync: SyncStateScheduler = SyncStateScheduler(
      blockchainReader,
      storages.evmCodeStorage,
      storages.stateStorage,
      storages.nodeStorage,
      syncConfig.stateSyncBloomFilterSize
    )

    val actor: TypedActorRef[Command] = testKit.spawn(
      SyncStateSchedulerActor.behavior(
        sync,
        syncConfig,
        networkPeerManager.ref,
        peerEventBus,
        blacklist,
        syncInitResponse.ref,
        syncInitStats.ref
      )
    )

    def newPeer(name: String): Peer =
      Peer(
        PeerId(name),
        new InetSocketAddress("127.0.0.1", 0),
        testKit.createTestProbe[PeerActor.Command]().ref,
        incomingConnection = false
      )

    def genRandomByteString(): ByteString =
      val arr = new Array[Byte](32)
      Random.nextBytes(arr)
      ByteString.fromArrayUnsafe(arr)
