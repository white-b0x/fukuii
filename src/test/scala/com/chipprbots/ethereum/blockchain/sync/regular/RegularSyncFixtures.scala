package com.chipprbots.ethereum.blockchain.sync.regular
import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestKitBase
import org.apache.pekko.util.ByteString

import cats.Eq
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits.*

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.math.BigInt
import scala.reflect.ClassTag

import fs2.Stream
import fs2.concurrent.Topic
import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.*
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies as ETHGetBlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders as ETHGetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.consensus.pow.ommers.OmmersPool
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.ActorsTesting.fishForSpecificMessage
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.SyncConfig

// Fixture classes are wrapped in a trait due to problems with making mocks available inside of them
trait RegularSyncFixtures:
  self: Matchers & AsyncMockFactory =>
  class RegularSyncFixture
      extends TestKitBase
      with EphemBlockchainTestSetup
      with TestSyncConfig
      with SecureRandomBuilder:
    // Each fixture owns a per-test ActorTestKit (typed). Its system has a custom user guardian that
    // forbids top-level spawning "from the outside" (system.spawn / system.actorOf), so all actor
    // creation routes through testKit.spawn. The Classic system below is the testKit's underlying
    // adapter — used by Classic TestProbe / AutoPilot in this fixture. The testKit owns the
    // lifecycle (shut down per test via `shutdownFixture()`).
    val testKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit =
      org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit()
    implicit override lazy val system: ActorSystem = testKit.system.classicSystem
    implicit override lazy val ioRuntime: IORuntime = IORuntime.global
    override lazy val syncConfig: SyncConfig =
      defaultSyncConfig.copy(
        blockHeadersPerRequest = 2,
        blockBodiesPerRequest = 2,
        blockFetcherTickInterval = 60.seconds
      )
    val handshakedPeers: Map[Peer, PeerInfo] =
      (0 to 5).toList.map(peerId.andThen(getPeer)).fproduct(getPeerInfo(_)).toMap
    val defaultPeer: Peer = peerByNumber(0)

    // networkPeerManager: a Typed observation probe plus a spawned mock actor. The mock forwards
    // every message to `networkPeerManager` (observation) and applies the swappable reply function
    // installed by `setNetworkPeerManagerAutoPilot` — the Typed analogue of Classic setAutoPilot.
    // Only GetHandshakedPeersCmd is ever replied to (via its own `replyTo`); SendMessageCmd is
    // fire-and-forget and merely observed, so this is a genuine Typed migration, NOT a sender()-reply
    // boundary like NetworkPeerManagerFake (MOD-09).
    val networkPeerManager: TypedTestProbe[NetworkPeerManagerActor.Command] =
      testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    @volatile private var networkPeerManagerAutoPilot: NetworkPeerManagerActor.Command => Unit = _ => ()
    def setNetworkPeerManagerAutoPilot(f: NetworkPeerManagerActor.Command => Unit): Unit =
      networkPeerManagerAutoPilot = f
    val networkPeerManagerRef: TypedActorRef[NetworkPeerManagerActor.Command] =
      testKit.spawn(
        Behaviors.monitor[NetworkPeerManagerActor.Command](
          networkPeerManager.ref,
          Behaviors.receiveMessage[NetworkPeerManagerActor.Command] { msg =>
            networkPeerManagerAutoPilot(msg)
            Behaviors.same
          }
        )
      )

    val peerEventBus: TypedTestProbe[PeerEventBusCommand] = testKit.createTestProbe[PeerEventBusCommand]()

    // ommersPool: a Typed mock actor replying GetOmmers -> empty. Formerly an OnTopFixture-only
    // AutoPilot; now a permanent default (harmless when unused — these tests only ever want an empty
    // ommers list). Never observed, so no probe is needed.
    val ommersPool: TypedActorRef[OmmersPool.Command] =
      testKit.spawn(
        Behaviors.receiveMessage[OmmersPool.Command] {
          case OmmersPool.GetOmmers(_, replyTo) =>
            replyTo ! OmmersPool.Ommers(Seq.empty)
            Behaviors.same
          case _ => Behaviors.same
        }
      )

    // pendingTransactionsManager: a Typed mock actor replying GetPendingTransactionsReq -> empty.
    // Formerly an OnTopFixture-only AutoPilot; now a permanent default. RegularSync/BlockImporter only
    // send fire-and-forget Add/Remove commands here; the empty reply is defensive. Never observed.
    val pendingTransactionsManager: TypedActorRef[PendingTransactionsManager.Command] =
      testKit.spawn(
        Behaviors.receiveMessage[PendingTransactionsManager.Command] {
          case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
            replyTo ! PendingTransactionsManager.PendingTransactionsResponse(Seq.empty)
            Behaviors.same
          case _ => Behaviors.same
        }
      )

    // peersClient: a Typed observation probe plus a spawned mock actor. The mock forwards every
    // message to `peersClient` (observation) and applies the installed PeersClientAutoPilot, which
    // replies via the request's own `replyTo` (Typed AskPattern) and may advance to a next mock (the
    // Typed analogue of Classic setAutoPilot's stateful chaining). Default: no auto-reply — tests
    // drive replies manually via `.replyTo`.
    val peersClient: TypedTestProbe[PeersClient.Command] = testKit.createTestProbe[PeersClient.Command]()
    @volatile private var peersClientAutoPilot: Option[PeersClientAutoPilot] = None
    def setPeersClientAutoPilot(mock: PeersClientAutoPilot): Unit = peersClientAutoPilot = Some(mock)
    val peersClientRef: TypedActorRef[PeersClient.Command] =
      testKit.spawn(
        Behaviors.monitor[PeersClient.Command](
          peersClient.ref,
          Behaviors.receiveMessage[PeersClient.Command] { msg =>
            peersClientAutoPilot = peersClientAutoPilot.map(_.run(msg))
            Behaviors.same
          }
        )
      )

    // Placeholder reply address for building EXPECTED PeersClient.Request values (ignored by Eq).
    val responsePlaceholder: TypedActorRef[PeersClient.ResponseMessage] =
      testKit.createTestProbe[PeersClient.ResponseMessage]().ref
    // Stands in for the SyncController parent: RegularSync relays RegularSyncStuck here (8k-F).
    val supervisor: TypedTestProbe[SyncController.Command] = testKit.createTestProbe[SyncController.Command]()
    val blacklist: CacheBasedBlacklist = CacheBasedBlacklist.empty(100)
    lazy val branchResolution = new BranchResolution(blockchainReader)

    val stateStorage: StateStorage = stub[StateStorage]
    val evmCodeStorage: EvmCodeStorage = stub[EvmCodeStorage]

    // testKit.spawn (anonymous): RegularSyncSpec shares one testKit-owned ActorSystem across all
    // fixtures, so a fixed actor name would collide with InvalidActorNameException on the second
    // fixture. testKit.spawn also satisfies the custom-user-guardian constraint (system.spawn would
    // throw "cannot create top-level actor from the outside"). The Topic's pubsub identifier
    // ("block-imported-topic") is internal, not the actor name.
    lazy val blockTopic: org.apache.pekko.actor.typed.ActorRef[
      org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
    ] = testKit.spawn(
      org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
        "block-imported-topic"
      )
    )

    // testKit.spawn (anonymous): RegularSyncSpec reuses one testKit-owned ActorSystem across many
    // fixtures, so a fixed actor name would collide with InvalidActorNameException on the second test
    // case. testKit.spawn also satisfies the custom-user-guardian constraint. .toClassic because
    // RegularSync's callers in this fixture hold it as a Classic ref.
    lazy val regularSync: ActorRef = testKit
      .spawn(
        RegularSync.apply(
          peersClientRef,
          networkPeerManagerRef,
          peerEventBus.ref,
          consensusAdapter,
          blockchain,
          blockchainReader,
          blockchainWriter,
          stateStorage,
          evmCodeStorage,
          branchResolution,
          validators.blockValidator,
          blacklist,
          syncConfig,
          ommersPool,
          pendingTransactionsManager,
          blockTopic,
          this,
          supervisor.ref
        ),
        org.apache.pekko.actor.typed.Props.empty
          .withDispatcherFromConfig("pekko.actor.default-dispatcher")
      )
      .toClassic

    val defaultTd = 12345

    val testBlocks: List[Block] = BlockHelpers.generateChain(20, BlockHelpers.genesis)
    val testBlocksChunked: List[List[Block]] = testBlocks.grouped(syncConfig.blockHeadersPerRequest).toList

    override lazy val consensusAdapter: ConsensusAdapter =
      val adapter = stub[ConsensusAdapter]
      // Per-block path: mined/broadcast blocks via importBlock
      (adapter
        .evaluateBranchBlock(_: Block)(_: IORuntime, _: BlockchainConfig))
        .when(*, *, *)
        .onCall { case (block: Block, _, _) =>
          importedBlocksSet.add(block)
          results
            .getOrElse(block.header.hash.value, IO.pure(BlockEnqueued))
            .flatTap(_ => importedBlocksSubject.publish1(block).void)
        }
      // Batch path: regular sync blocks via tryImportBlocks
      (adapter
        .evaluateBranch(_: NonEmptyList[Block])(_: IORuntime, _: BlockchainConfig))
        .when(*, *, *)
        // pinned by: the mocked method's own signature above, ConsensusAdapter.evaluateBranch(blocks: NonEmptyList[Block]) —
        // ScalaMock's onCall only ever receives args matching the stubbed method's declared parameter types.
        .onCall { case (nel: (NonEmptyList[Block] @unchecked), _, _) =>
          def go(remaining: List[Block], acc: List[BlockData]): IO[BlockImportResult] =
            remaining match
              case Nil => IO.pure(BlockImportedToTop(acc.reverse))
              case block :: rest =>
                importedBlocksSet.add(block)
                results
                  .getOrElse(block.header.hash.value, IO.pure(BlockImportedToTop(Nil)))
                  .flatTap(_ => importedBlocksSubject.publish1(block).void)
                  .flatMap {
                    case BlockImportedToTop(data)       => go(rest, data.reverse ::: acc)
                    case DuplicateBlock | BlockEnqueued => go(rest, acc)
                    case other                          => IO.pure(other)
                  }
          go(nel.toList, Nil)
        }
      adapter

    blockchainWriter.save(
      block = BlockHelpers.genesis,
      receipts = Nil,
      weight = ChainWeight.totalDifficultyOnly(TotalDifficulty(10000)),
      saveAsBestBlock = true
    )
    // scalastyle:on magic.number

    def done(): Unit =
      regularSync ! PoisonPill

    // Per-test teardown: shut down this fixture's testKit (and its underlying Classic system).
    // Replaces the former TestKit.shutdownActorSystem(testSystem) call in the spec.
    // Named shutdownFixture (not shutdown) to avoid clashing with ShutdownHookBuilder.shutdown.
    def shutdownFixture(): Unit =
      testKit.shutdownTestKit()

    def peerId(number: Int): PeerId = PeerId(s"peer_$number")

    def getPeer(id: PeerId): Peer =
      Peer(
        id,
        new InetSocketAddress("127.0.0.1", 0),
        testKit.createTestProbe[PeerActor.Command](id.value).ref,
        incomingConnection = false
      )

    def getPeerInfo(
        peer: Peer,
        capability: Capability = Capability.ETH68
    ): PeerInfo =
      val status =
        RemoteStatus(
          capability,
          1,
          ChainWeight.totalDifficultyOnly(TotalDifficulty(1)),
          ByteString(s"${peer.id}_bestHash"),
          ByteString("unused")
        )
      PeerInfo(
        status,
        forkAccepted = true,
        chainWeight = status.chainWeight,
        maxBlockNumber = 0,
        bestBlockHash = status.bestHash
      )

    def peerByNumber(number: Int): Peer = handshakedPeers.keys.toList.sortBy(_.id.value).apply(number)

    def blockHeadersChunkRequest(fromChunk: Int): PeersClient.Request[ETHGetBlockHeaders] =
      val block = testBlocksChunked(fromChunk).headNumberUnsafe
      blockHeadersRequest(block)

    // Builds an EXPECTED request for comparison via `expectMsgEq`. The `replyTo` is a placeholder —
    // `eqInstanceForPeersClientRequest` compares only `message` + `peerSelector`, ignoring `replyTo`.
    def blockHeadersRequest(fromBlock: BigInt): PeersClient.Request[ETHGetBlockHeaders] = PeersClient.Request
      .create(
        ETHGetBlockHeaders(
          requestId = 0,
          Left(fromBlock),
          syncConfig.blockHeadersPerRequest,
          skip = 0,
          reverse = false
        ),
        PeersClient.BestPeer
      )
      .apply(responsePlaceholder)
      .asInstanceOf[PeersClient.Request[ETHGetBlockHeaders]]

    // Builds an EXPECTED bodies request for comparison via `expectMsgEq` (replyTo is a placeholder; ignored by Eq).
    def blockBodiesRequest(hashes: Seq[ByteString]): PeersClient.Request[ETHGetBlockBodies] = PeersClient.Request
      .create(ETHGetBlockBodies(BigInt(0), hashes), PeersClient.BestPeer)
      .apply(responsePlaceholder)
      .asInstanceOf[PeersClient.Request[ETHGetBlockBodies]]

    def fishForBlacklistPeer(peer: Peer): PeersClient.BlacklistPeer =
      peersClient.fishForSpecificMessage() {
        case msg @ PeersClient.BlacklistPeer(id, _) if id == peer.id => msg
      }

    val getSyncStatus: IO[SyncProtocol.Status] =
      IO {
        val probe = testKit.createTestProbe[SyncProtocol.Status]()
        regularSync ! SyncProtocol.GetStatus(probe.ref)
        probe.expectMessageType[SyncProtocol.Status]
      }

    def pollForStatus(predicate: SyncProtocol.Status => Boolean): IO[SyncProtocol.Status] = Stream
      .repeatEval(getSyncStatus.delayBy(10.millis))
      .takeThrough(predicate.andThen(!_))
      .compile
      .last
      .flatMap {
        case Some(status) => IO.pure(status)
        case None         => IO.raiseError(new RuntimeException("No status found"))
      }
      .timeout(remainingOrDefault)

    def fishForStatus[B](picker: PartialFunction[SyncProtocol.Status, B]): IO[B] = Stream
      .repeatEval(getSyncStatus.delayBy(10.millis))
      .collect(picker)
      .head
      .compile
      .lastOrError
      .timeout(remainingOrDefault)

    protected val results: mutable.Map[ByteString, IO[BlockImportResult]] =
      mutable.Map[ByteString, IO[BlockImportResult]]()
    protected val importedBlocksSet: mutable.Set[Block] = mutable.Set[Block]()
    private val importedBlocksTopicIO = Topic[IO, Block]
    private lazy val importedBlocksSubject = importedBlocksTopicIO.unsafeRunSync()
    val importedBlocks: Stream[IO, Block] = importedBlocksSubject.subscribe(100)

    def didTryToImportBlock(predicate: Block => Boolean): Boolean =
      importedBlocksSet.exists(predicate)

    def didTryToImportBlock(block: Block): Boolean =
      didTryToImportBlock(_.hash == block.hash)

    def bestBlock: Block = importedBlocksSet.maxBy(_.number)

    def setImportResult(block: Block, result: IO[BlockImportResult]): Unit =
      results(block.header.hash.value) = result

    // Typed reply handler (formerly a Classic AutoPilot). `run` matches a typed PeersClient.Command
    // — not `Any` — so there is no E165 Matchable warning. Each handler replies via the request's own
    // `replyTo` and returns the next handler (`None` = keep this one), reproducing the Classic
    // stateful AutoPilot chaining. Installed via `setPeersClientAutoPilot`.
    class PeersClientAutoPilot(blocks: List[Block] = testBlocks):

      def overrides: PartialFunction[PeersClient.Command, Option[PeersClientAutoPilot]] =
        PartialFunction.empty

      def defaultHandlers: PartialFunction[PeersClient.Command, Option[PeersClientAutoPilot]] = {
        // Typed AskPattern carries its own reply address in `replyTo` (4th field).
        // Handle ETH68/69 GetBlockHeaders (with requestId)
        case PeersClient.Request(ETHGetBlockHeaders(_, Left(minBlock), amount, _, _), _, _, replyTo) =>
          val maxBlock = minBlock + amount
          val matchingHeaders = blocks
            .filter { b =>
              val nr = b.number.value
              minBlock <= nr && nr < maxBlock
            }
            .map(_.header)
            .sortBy(_.number.value)
          replyTo ! PeersClient.Response(defaultPeer, BlockHeaders(BigInt(0), matchingHeaders))
          None
        // Handle ETH68/69 GetBlockBodies (with requestId)
        case PeersClient.Request(ETHGetBlockBodies(_, hashes), _, _, replyTo) =>
          val matchingBodies = hashes.flatMap(hash => blocks.find(_.hash.value == hash)).map(_.body)

          replyTo ! PeersClient.Response(defaultPeer, BlockBodies(BigInt(0), matchingBodies))
          None
        case PeersClient.Request(GetNodeData(hash :: Nil), _, _, replyTo) =>
          replyTo ! PeersClient.Response(
            defaultPeer,
            NodeData(List(ByteString(blocks.byHashUnsafe(hash).header.toBytes: Array[Byte])))
          )
          None
        case _ => None
      }

      final def run(msg: PeersClient.Command): PeersClientAutoPilot =
        overrides.orElse(defaultHandlers).applyOrElse(msg, (_: PeersClient.Command) => None).getOrElse(this)

    implicit class ListOps[T](list: List[T]):

      def get(index: Int): Option[T] =
        if list.isDefinedAt(index) then Some(list(index))
        else None

    implicit class BlocksListOps(blocks: List[Block]):
      def headNumberUnsafe: BigInt = blocks.head.number.value
      def headNumber: Option[BigInt] = blocks.headOption.map(_.number.value)
      def headers: List[BlockHeader] = blocks.map(_.header)
      def hashes: List[ByteString] = headers.map(_.hash.value)
      def bodies: List[BlockBody] = blocks.map(_.body)
      def numbers: List[BigInt] = blocks.map(_.number.value)
      def numberAt(index: Int): Option[BigInt] = blocks.get(index).map(_.number.value)
      def numberAtUnsafe(index: Int): BigInt = numberAt(index).get
      def byHash(hash: ByteString): Option[Block] = blocks.find(_.hash.value == hash)
      def byHashUnsafe(hash: ByteString): Block = byHash(hash).get

    // Typed-probe ports of the Classic TestProbe helpers. Every `case` in the caller now matches a
    // typed message (the probe's element type), not `Any`, so there is no E165 Matchable warning.
    // (`fishForSpecificMessage` / `expectMessagePF` come from ActorsTesting's Typed extensions.)
    implicit class TypedProbeOps[U](probe: TypedTestProbe[U]):

      // One-shot: take the next message and apply `pf` (mirrors Classic expectMsgPF).
      def expectMsgPF[T](max: FiniteDuration = probe.remainingOrDefault)(pf: PartialFunction[U, T]): T =
        val msg = probe.receiveMessage(max)
        if pf.isDefinedAt(msg) then pf(msg)
        else throw new AssertionError(s"unexpected message: $msg")

      // Fish for the first message satisfying `predicate` and return it.
      def fishForSpecificMessageMatching(max: FiniteDuration = probe.remainingOrDefault)(predicate: U => Boolean): U =
        probe
          .fishForMessage(max) {
            case m if predicate(m) => FishingOutcomes.complete
            case _                 => FishingOutcomes.continueAndIgnore
          }
          .last

    // PeersClient-specific Eq-based helpers (their Eq instances are only defined for PeersClient.Request).
    implicit class PeersClientProbeOps(probe: TypedTestProbe[PeersClient.Command]):

      def expectMsgEq[T <: PeersClient.Command: Eq: ClassTag](msg: T): T =
        expectMsgEq(probe.remainingOrDefault, msg)

      def expectMsgEq[T <: PeersClient.Command: Eq: ClassTag](max: FiniteDuration, msg: T): T =
        val received = probe.expectMessageType[T](max)
        assert(Eq[T].eqv(received, msg), s"Expected ${msg}, got ${received}")
        received

      def fishForMsgEq[T <: PeersClient.Command: Eq: ClassTag](
          msg: T,
          max: FiniteDuration = probe.remainingOrDefault
      ): T =
        val ct = implicitly[ClassTag[T]]
        probe
          .fishForMessage(max) {
            case m if ct.runtimeClass.isInstance(m) && Eq[T].eqv(msg, m.asInstanceOf[T]) => FishingOutcomes.complete
            case _ => FishingOutcomes.continueAndIgnore
          }
          .last
          .asInstanceOf[T]

      def expectMsgAllOfEq[T1 <: PeersClient.Command: Eq, T2 <: PeersClient.Command: Eq](
          msg1: T1,
          msg2: T2
      ): (T1, T2) =
        expectMsgAllOfEq(probe.remainingOrDefault, msg1, msg2)

      def expectMsgAllOfEq[T1 <: PeersClient.Command: Eq, T2 <: PeersClient.Command: Eq](
          max: FiniteDuration,
          msg1: T1,
          msg2: T2
      ): (T1, T2) =
        val received = probe.receiveMessages(2, max)
        val found1 = received.find(m => Eq[T1].eqv(msg1, m.asInstanceOf[T1]))
        val found2 = received.find(m => Eq[T2].eqv(msg2, m.asInstanceOf[T2]))

        (found1, found2) match
          case (Some(r1), Some(r2)) => (r1.asInstanceOf[T1], r2.asInstanceOf[T2])
          case (None, _) =>
            fail(s"Expected message $msg1 not found in received messages: $received")
          case (_, None) =>
            fail(s"Expected message $msg2 not found in received messages: $received")

    // Helper to compare ETH66 messages ignoring requestId (which is dynamically generated
    // for core-geth compatibility). Also handles comparison between ETH65 and ETH66 message
    // versions. Only handles GetBlockHeaders and GetBlockBodies as those are the ETH66
    // message types used in these tests. Other ETH66 request types like GetPooledTransactions,
    // GetNodeData, and GetReceipts are not used in RegularSync tests.
    private def messagesEqualIgnoringRequestId(x: Message, y: Message): Boolean = (x, y) match
      // ETH66 to ETH66 comparison
      case (h1: ETHGetBlockHeaders, h2: ETHGetBlockHeaders) =>
        h1.block == h2.block && h1.maxHeaders == h2.maxHeaders && h1.skip == h2.skip && h1.reverse == h2.reverse
      case (b1: ETHGetBlockBodies, b2: ETHGetBlockBodies) =>
        b1.hashes == b2.hashes

      case _ => x == y

    implicit def eqInstanceForPeersClientRequest[T <: Message]: Eq[PeersClient.Request[T]] =
      (x, y) => messagesEqualIgnoringRequestId(x.message, y.message) && x.peerSelector == y.peerSelector

    def fakeEvaluateBlock(
        block: Block
    ): IO[BlockImportResult] =
      val result: BlockImportResult =
        if didTryToImportBlock(block) then DuplicateBlock
        else if importedBlocksSet.isEmpty || bestBlock.isParentOf(block) || importedBlocksSet.exists(
            _.isParentOf(block)
          )
        then
          importedBlocksSet.add(block)
          BlockImportedToTop(
            List(BlockData(block, Nil, ChainWeight.totalDifficultyOnly(TotalDifficulty(block.header.difficulty.value))))
          )
        else if block.number > bestBlock.number then
          importedBlocksSet.add(block)
          BlockEnqueued
        else BlockImportFailed("foo")

      IO.pure(result)

    def fakeEvaluateBatch(nel: NonEmptyList[Block]): IO[BlockImportResult] =
      def go(remaining: List[Block], acc: List[BlockData]): IO[BlockImportResult] =
        remaining match
          case Nil => IO.pure(BlockImportedToTop(acc.reverse))
          case block :: rest =>
            fakeEvaluateBlock(block).flatMap {
              case BlockImportedToTop(data)       => go(rest, data.reverse ::: acc)
              case DuplicateBlock | BlockEnqueued => go(rest, acc)
              case other                          => IO.pure(other)
            }
      go(nel.toList, Nil)

    class FakeBranchResolution extends BranchResolution(stub[BlockchainReader]):
      override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
        val importedHashes = importedBlocksSet.map(_.hash).toSet

        if importedBlocksSet.isEmpty || (importedHashes.contains(
            headers.head.parentHash
          ) && headers.last.number > bestBlock.number)
        then NewBetterBranch(Nil)
        else UnknownBranch

  class OnTopFixture extends RegularSyncFixture:

    // Override blockHeadersPerRequest = 3 so that the last batch of testBlocks (blocks 19-20)
    // has 2 headers < 3 = no cherry-pick. Without this, the cherry-pick in BlockFetcher bumps
    // knownTop to 21 after the last full batch, leaving isOnTop = false permanently.
    override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
      blockHeadersPerRequest = 3,
      blockBodiesPerRequest = 3,
      blockFetcherTickInterval = 60.seconds
    )

    val newBlock: Block = BlockHelpers.generateBlock(testBlocks.last)

    override lazy val consensusAdapter: ConsensusAdapter = stub[ConsensusAdapter]

    var blockFetcher: TypedActorRef[PeerEvent] = uninitialized

    var importedNewBlock = false
    var importedLastTestBlock = false

    override lazy val branchResolution: BranchResolution = stub[BranchResolution]
    branchResolution.resolveBranch.when(*).returns(NewBetterBranch(Nil))

    (consensusAdapter
      .evaluateBranchBlock(_: Block)(_: IORuntime, _: BlockchainConfig))
      .when(*, *, *)
      .onCall { (block, _, _) =>
        if block == newBlock then
          importedNewBlock = true
          IO.pure(
            BlockImportedToTop(
              List(BlockData(newBlock, Nil, ChainWeight.totalDifficultyOnly(TotalDifficulty(newBlock.number.value))))
            )
          )
        else
          if block == testBlocks.last then importedLastTestBlock = true
          IO.pure(BlockImportedToTop(Nil))
      }

    (consensusAdapter
      .evaluateBranch(_: NonEmptyList[Block])(_: IORuntime, _: BlockchainConfig))
      .when(*, *, *)
      // pinned by: the mocked method's own signature above, ConsensusAdapter.evaluateBranch(blocks: NonEmptyList[Block]) —
      // ScalaMock's onCall only ever receives args matching the stubbed method's declared parameter types.
      .onCall { case (nel: (NonEmptyList[Block] @unchecked), _, _) =>
        if nel.toList.contains(testBlocks.last) then importedLastTestBlock = true
        val blockData =
          nel.toList
            .map(b => BlockData(b, Nil, ChainWeight.totalDifficultyOnly(TotalDifficulty(b.header.difficulty.value))))
        IO.pure(BlockImportedToTop(blockData))
      }

    setPeersClientAutoPilot(new PeersClientAutoPilot(testBlocks))
    // ommersPool and pendingTransactionsManager auto-reply is now a permanent default baked into the
    // base fixture's Typed mock actors (empty ommers / empty pending transactions), so no per-fixture
    // install is needed here.

    def waitForSubscription(): Unit =
      blockFetcher = peerEventBus
        .fishForMessage(max = 5.seconds) {
          case SubscribeCmd(_: MessageClassifier, _) => FishingOutcomes.complete
          case _                                     => FishingOutcomes.continueAndIgnore
        }
        .head
        .asInstanceOf[SubscribeCmd]
        .subscriber

    def sendLastTestBlockAsTop(): Unit = sendNewBlock(testBlocks.last)

    def sendNewBlock(block: Block = newBlock, peer: Peer = defaultPeer): Unit =
      blockFetcher ! MessageFromPeer(
        ETHPackets
          .NewBlock(block, ChainWeight.totalDifficultyOnly(TotalDifficulty(block.number.value)).totalDifficulty),
        peer.id
      )

    def goToTop(): Unit =
      regularSync ! SyncProtocol.Start

      waitForSubscription()
      sendLastTestBlockAsTop()

      awaitCond(importedLastTestBlock)
