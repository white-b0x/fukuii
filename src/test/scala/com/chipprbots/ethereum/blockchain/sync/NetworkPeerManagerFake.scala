package com.chipprbots.ethereum.blockchain.sync
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.DurationInt

import fs2.Stream
import fs2.concurrent.Topic

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.SendMessageCmd
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.utils.Config.SyncConfig

class NetworkPeerManagerFake(
    syncConfig: SyncConfig,
    peers: Map[Peer, PeerInfo],
    blocks: List[Block],
    peerEventBus: TypedActorRef[PeerEventBusActor.Command]
)(implicit system: ActorSystem, ioRuntime: IORuntime):
  private val responsesTopicIO: IO[Topic[IO, MessageFromPeer]] = Topic[IO, MessageFromPeer]
  private val requestsTopicIO: IO[Topic[IO, SendMessageCmd]] = Topic[IO, SendMessageCmd]
  private val responsesTopic: Topic[IO, MessageFromPeer] = responsesTopicIO.unsafeRunSync()
  private val requestsTopic: Topic[IO, SendMessageCmd] = requestsTopicIO.unsafeRunSync()
  private val peersConnectedDeferred = Deferred.unsafe[IO, Unit]

  // The AutoPilot below replies via a real PeerEventBusActor, not `sender ! MessageFromPeer`:
  // production's SendMessageCmd is fire-and-forget (the Typed FastSync/PivotBlockSelector sends it
  // through a Classic-adapter ActorRef, so `sender` is deadLetters), and the production
  // PeerRequestHandler reads peer responses only from the PeerEventBus (SubscribeCmd -> PublishCmd).
  // A direct reply to `sender` is silently dropped and production hangs until PeerRequestHandler's
  // own internal timeout — the outer 60s IO.timeout in FastSyncSpec was the observable symptom.
  // Same fix shape as the fa16aeaf9/3b8b07f67 precedent (IP-STATESYNC-01 / SYNCCONTROLLERSPEC-REDO-01).
  // (The GetHandshakedPeersCmd branch already uses a real replyTo and needs no change.)
  val probe: TestProbe = TestProbe("network_peer_manager")
  val autoPilot =
    new NetworkPeerManagerFake.NetworkPeerManagerAutoPilot(
      requestsTopic,
      responsesTopic,
      peersConnectedDeferred,
      peers,
      blocks,
      peerEventBus
    )
  probe.setAutoPilot(autoPilot)

  def ref = probe.ref

  val requests: Stream[IO, SendMessageCmd] = requestsTopic.subscribe(100)
  val responses: Stream[IO, MessageFromPeer] = responsesTopic.subscribe(100)
  val onPeersConnected: IO[Unit] = peersConnectedDeferred.get
  val pivotBlockSelected: Stream[IO, BlockHeader] = responses
    .collect { case MessageFromPeer(BlockHeaders(_, Seq(header)), peer) =>
      (header, peer)
    }
    .chunkN(peers.size)
    .flatMap { headersFromPeersChunk =>
      val headersFromPeers = headersFromPeersChunk.toList
      val (headers, respondedPeers) = headersFromPeers.unzip

      if headers.distinct.size == 1 && respondedPeers.toSet == peers.keySet.map(_.id) then Stream.emit(headers.head)
      else Stream.empty
    }

  val fetchedHeaders: Stream[IO, Seq[BlockHeader]] = responses.collect {
    case MessageFromPeer(BlockHeaders(_, headers), _) if headers.size == syncConfig.blockHeadersPerRequest =>
      headers
  }
  val fetchedBodies: Stream[IO, Seq[BlockBody]] = responses.collect { case MessageFromPeer(BlockBodies(_, bodies), _) =>
    bodies
  }
  val requestedReceipts: Stream[IO, Seq[ByteString]] = requests.collect(
    Function.unlift(msg =>
      msg.message.underlyingMsg match
        case GetReceipts(_, hashes) => Some(hashes)
        case _                      => None
    )
  )
  val fetchedBlocks: Stream[IO, List[Block]] = fetchedBodies
    .scan[(List[Block], List[Block])]((Nil, blocks)) { case ((_, remainingBlocks), bodies) =>
      remainingBlocks.splitAt(bodies.size)
    }
    .map(_._1)
    .zip(requestedReceipts)
    .map { case (blocks, _) => blocks } // a big simplification, but should be sufficient here

  val fetchedState: Stream[IO, Seq[ByteString]] = responses.collect {
    case MessageFromPeer(ETHPackets.NodeData(values), _) => values
  }

object NetworkPeerManagerFake:
  class NetworkPeerManagerAutoPilot(
      requests: Topic[IO, SendMessageCmd],
      responses: Topic[IO, MessageFromPeer],
      peersConnected: Deferred[IO, Unit],
      peers: Map[Peer, PeerInfo],
      blocks: List[Block],
      peerEventBus: TypedActorRef[PeerEventBusActor.Command]
  )(implicit system: ActorSystem, ioRuntime: IORuntime)
      extends AutoPilot:

    // The Typed PeerRequestHandler sends SendMessageCmd and *then* subscribes to the PeerEventBus.
    // Publishing synchronously here can race ahead of that subscription and be dropped (the bus only
    // delivers to current subscribers); a small scheduled delay reproduces the happens-before that
    // real network latency guarantees in production. Mirrors StateSyncSpec/SyncControllerSpec.
    given scala.concurrent.ExecutionContext = system.dispatcher
    private def publishResponse(response: MessageFromPeer): Unit =
      system.scheduler.scheduleOnce(20.milliseconds)(
        peerEventBus ! PeerEventBusActor.PublishCmd(response)
      )

    def run(sender: ActorRef, msg: Any): NetworkPeerManagerAutoPilot =
      msg match
        case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
          replyTo ! NetworkPeerManagerActor.HandshakedPeers(peers)
          peersConnected.complete(()).handleError(_ => ()).unsafeRunSync()
        case sendMsg @ NetworkPeerManagerActor.SendMessageCmd(rawMsg, peerId) =>
          requests.publish1(sendMsg).unsafeRunSync()
          val response = rawMsg.underlyingMsg match
            case GetBlockHeaders(requestId, startingBlock, maxHeaders, skip, reverse) =>
              BlockHeaders(requestId, headersFor(startingBlock, maxHeaders, skip, reverse))

            case GetBlockBodies(requestId, hashes) =>
              BlockBodies(requestId, bodiesFor(hashes))

            case ETHPackets.GetReceipts(requestId, blockHashes) =>
              ETHPackets.Receipts68(requestId, emptyReceiptsRlp(blockHashes.size))

            case ETHPackets.GetNodeData(mptElementsHashes) =>
              ETHPackets.NodeData(Seq.empty)
          val theResponse = MessageFromPeer(response, peerId)
          publishResponse(theResponse)
          responses.publish1(theResponse).unsafeRunSync()
      this

    private def headersFor(
        startingBlock: Either[BigInt, ByteString],
        maxHeaders: BigInt,
        skip: BigInt,
        reverse: Boolean
    ): Seq[BlockHeader] =
      val startIndex = blocks.indexWhere(blockMatchesStart(_, startingBlock))
      if startIndex < 0 then Seq.empty
      else
        val orderedBlocks = if reverse then blocks.take(startIndex + 1).reverse else blocks.drop(startIndex)
        val step = (skip + 1).toInt
        orderedBlocks.zipWithIndex
          .collect { case (block, index) if index % step == 0 => block }
          .take(maxHeaders.toInt)
          .map(_.header)

    private def bodiesFor(hashes: Seq[ByteString]): Seq[BlockBody] =
      hashes.flatMap(hash => blocks.find(_.hash.value == hash)).map(_.body)

    private def emptyReceiptsRlp(count: Int): RLPList =
      RLPList(List.fill(count)(RLPList())*)

    def blockMatchesStart(block: Block, startingBlock: Either[BigInt, ByteString]): Boolean =
      startingBlock.fold(nr => block.number.value == nr, hash => block.hash.value == hash)
