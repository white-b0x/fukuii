package com.chipprbots.ethereum.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.io.Tcp
import org.apache.pekko.testkit.TestProbe

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

class ServerActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  private val keyPair = com.chipprbots.ethereum.crypto.generateKeyPair(new java.security.SecureRandom)

  private def freshHolder() =
    new AtomicReference(NodeStatus(keyPair, ServerStatus.NotListening, ServerStatus.NotListening))

  private def blacklist: Blacklist = CacheBasedBlacklist.empty(100)

  "ServerActor" should "transition to listening immediately when an explicit advertised-address is set" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val holder = freshHolder()
    val pm = testKit.createTestProbe[PeerManagerActor.Command]()
    // TCP probe absorbs the Bind request so no real socket binding happens.
    val tcpProbe = TestProbe()
    val actor = testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref), "server-test-1")

    val explicit = InetAddress.getByName("1.2.3.4")
    val localAddr = new InetSocketAddress("0.0.0.0", 30303)
    actor ! ServerActor.StartServer(localAddr, Some(explicit))
    // The Bind carries the bridge handler ref that receives the Bound/CommandFailed/Connected events.
    val bindHandler = tcpProbe.expectMsgType[Tcp.Bind].handler
    bindHandler ! Tcp.Bound(localAddr)

    eventually(timeout(1.second), interval(50.millis)) {
      assert(
        holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
        "ServerStatus should have transitioned to Listening"
      )
    }
    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe explicit
      case other                           => fail(s"Expected Listening, got $other")
  }

  it should "finalise advertisement via DetectedIP when bound to a wildcard address" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val holder = freshHolder()
    val pm = testKit.createTestProbe[PeerManagerActor.Command]()
    val tcpProbe = TestProbe()
    val actor = testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref), "server-test-2")

    val localAddr = new InetSocketAddress("0.0.0.0", 30304)
    val detectedIp = InetAddress.getByName("5.6.7.8")
    actor ! ServerActor.StartServer(localAddr, None)
    // Confirm StartServer was processed, then inject TcpBound directly to preserve
    // same-sender ordering with the DetectedIP message that follows immediately.
    // (Using bindHandler would route through TcpEventBridge — a different sender —
    // breaking FIFO ordering guarantees with the subsequent DetectedIP send.)
    tcpProbe.expectMsgType[Tcp.Bind]
    actor ! ServerActor.TcpBound(localAddr)

    // Simulate the Future result returning from the async IP detection
    actor ! ServerActor.DetectedIP(Some(detectedIp))

    eventually(timeout(2.seconds), interval(50.millis)) {
      assert(
        holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
        "ServerStatus should reach Listening after DetectedIP"
      )
    }
    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe detectedIp
      case other                           => fail(s"Expected Listening, got $other")
  }

  it should "reach Listening via the real IO(Tcp) bind path (ServerActor.apply, not testApply)" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Regression test for the W2 Classic->Typed migration bug where `tcpManager ! Bind(tcpBridge, address)`
    // (no explicit sender) let the Tcp manager's `Bound` reply — which Pekko always sends to the *sender*
    // of `Bind`, not the `handler` — fall to `Actor.noSender`/deadLetters instead of reaching `tcpBridge`.
    // ServerActorSpec's other cases all use `testApply` with a TestProbe standing in for the Tcp manager,
    // which never exercised this real send-with-implicit-sender path — this is the only test in the suite
    // that spawns the production `ServerActor.apply` and drives it through a real IO(Tcp) round trip.
    val holder = freshHolder()
    val pm = testKit.createTestProbe[PeerManagerActor.Command]()
    val actor = testKit.spawn(ServerActor(holder, pm.ref, blacklist), "server-real-bind")

    val bindAddress = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
    actor ! ServerActor.StartServer(bindAddress)

    eventually(timeout(5.seconds), interval(100.millis)) {
      assert(
        holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
        "ServerStatus should reach Listening via the real Tcp manager Bind/Bound round trip"
      )
    }

    val boundAddress = holder.get().serverStatus match
      case ServerStatus.Listening(address) => address
      case other                           => fail(s"Expected Listening, got $other")

    boundAddress.getPort should not be 0

    // Confirm the same tcpBridge also correctly routes the `Connected` half of the real path
    // (handler-addressed, unaffected by this bug, but worth covering end-to-end in the one real-bind test).
    val socket = new java.net.Socket()
    try
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress, boundAddress.getPort), 2000)
      pm.expectMessageType[PeerManagerActor.HandlePeerConnectionCmd](3.seconds)
    finally socket.close()
  }

  it should "fall back to loopback when DetectedIP carries None" taggedAs (UnitTest, NetworkTest) in {
    val holder = freshHolder()
    val pm = testKit.createTestProbe[PeerManagerActor.Command]()
    val tcpProbe = TestProbe()
    val actor = testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref), "server-test-3")

    val localAddr = new InetSocketAddress("0.0.0.0", 30305)
    actor ! ServerActor.StartServer(localAddr, None)
    // Inject TcpBound directly (same-sender ordering guarantee — see test 2 comment).
    tcpProbe.expectMsgType[Tcp.Bind]
    actor ! ServerActor.TcpBound(localAddr)
    actor ! ServerActor.DetectedIP(None)

    eventually(timeout(2.seconds), interval(50.millis)) {
      assert(
        holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
        "ServerStatus should reach Listening (loopback) after DetectedIP(None)"
      )
    }
    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe InetAddress.getLoopbackAddress
      case other                           => fail(s"Expected Listening, got $other")
  }
