// §8a-retro batch 5: MIGRATED to ScalaTestWithActorTestKit — RLPxConnectionHandler.apply is Typed
// (Behavior[Command]). `connection`/`tcpActorProbe` stay Classic TestProbes — genuine Pekko-TCP
// boundary (they feed InboundTcpBridge/OutboundTcpBridge's Tcp.Register), same documented exception
// as PeerManagerSpec's connection/inboundTcp probes. `rlpxConnectionParent` is a Typed TestProbe
// (PeerActor.Command is already Typed) per the pattern in PeerActorHandshakingSpec.
package com.chipprbots.ethereum.network.rlpx

import java.net.InetSocketAddress
import java.net.URI

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.io.Tcp
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.network.PeerActor

import scala.concurrent.duration.FiniteDuration

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageDecoder
import com.chipprbots.ethereum.network.p2p.MessageDecoder.DecodingError
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Ping
import com.chipprbots.ethereum.network.rlpx.MessageCodec.CompressionPolicy
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.HelloCodec
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.InitialHelloReceived
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*

// §8a-E6: computeCapabilityOffsets tests extracted to RLPxCapabilityOffsetsSpec (pure unit, no actor deps)
// SCALA 3 MIGRATION: Fixed by creating manual stub implementation for AuthHandshaker
class RLPxConnectionHandlerSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockFactory:

  // Classic ActorSystem for the Classic-boundary TestProbes below (`connection`, `tcpActorProbe`).
  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  it should "write messages send to TCP connection" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    mockMessageCodec.encodeMessageHandler = Some(_ => ByteString("ping encoded"))

    setupIncomingRLPxConnection()

    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))

  it should "write messages to TCP connection once all previous ACK were received" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    mockMessageCodec.encodeMessageHandler = Some(_ => ByteString("ping encoded"))

    setupIncomingRLPxConnection()

    // Send first message
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
    connection.reply(RLPxConnectionHandler.Ack)
    connection.expectNoMessage()

    // Send second message
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
    connection.reply(RLPxConnectionHandler.Ack)
    connection.expectNoMessage()

  it should "accummulate messages and write them when receiving ACKs" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    mockMessageCodec.encodeMessageHandler = Some(_ => ByteString("ping encoded"))

    setupIncomingRLPxConnection()

    // Send several messages
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())

    // Only first message is sent
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
    connection.expectNoMessage()

    // Send Ack, second message should now be sent through TCP connection
    connection.reply(RLPxConnectionHandler.Ack)
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
    connection.expectNoMessage()

    // Send Ack, third message should now be sent through TCP connection
    connection.reply(RLPxConnectionHandler.Ack)
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
    connection.expectNoMessage()

  it should "close the connection when Ack timeout happens" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    mockMessageCodec.encodeMessageHandler = Some(_ => ByteString("ping encoded"))

    setupIncomingRLPxConnection()

    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))

    val expectedHello: InitialHelloReceived = rlpxConnectionParent.expectMessageType[InitialHelloReceived]
    expectedHello.message shouldBe a[Hello]

    // The rlpx connection is closed after a timeout happens (after rlpxConfiguration.waitForTcpAckTimeout) and it is processed
    rlpxConnectionParent.expectTerminated(
      rlpxConnection,
      max = rlpxConfiguration.waitForTcpAckTimeout + Timeouts.normalTimeout
    )

  it should "ignore timeout of old messages" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    mockMessageCodec.encodeMessageHandler = Some(_ => ByteString("ping encoded"))

    setupIncomingRLPxConnection()

    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping()) // With SEQ number 0
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping()) // With SEQ number 1

    // Only first Ping is sent
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))

    // Upon Ack, the next message is sent
    connection.reply(RLPxConnectionHandler.Ack)
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))

    // AckTimeout for the first Ping is received
    rlpxConnection ! RLPxConnectionHandler.AckTimeout(0) // AckTimeout for first Ping message

    // Connection should continue to work perfectly
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.reply(RLPxConnectionHandler.Ack)
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))

  it should "close the connection if the AuthHandshake init message's MAC is invalid" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    // Incomming connection arrives
    rlpxConnection ! RLPxConnectionHandler.HandleConnection(connection.ref)
    connection.expectMsgClass(classOf[Tcp.Register])
    val bridge = connection.lastSender

    // AuthHandshaker throws exception on initial message
    mockHandshaker.handleInitialMessageHandler = Some(_ => throw new Exception("MAC invalid"))
    mockHandshaker.handleInitialMessageV4Handler = Some(_ => throw new Exception("MAC invalid"))

    val data: ByteString = ByteString((0 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)
    bridge ! Tcp.Received(data)
    rlpxConnectionParent.expectMessage(RLPxConnectionHandler.ConnectionFailed)
    rlpxConnectionParent.expectTerminated(rlpxConnection)

  it should "close the connection if the AuthHandshake init message decodes to an AuthHandshakeError result (not a throw)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    // NETWORK-02-TESTGAP: the 2 sibling MAC-invalid tests only exercise the `Failure(ex)` branch of
    // waitingForAuthHandshakeInit (both handleInitialMessage and handleInitialMessageV4 throw). This
    // test exercises the other branch that reaches the same outcome: handleInitialMessage throws (so
    // pre-EIP8 decode fails, forcing fallback to the V4/EIP-8 path) but handleInitialMessageV4 RETURNS
    // AuthHandshakeError instead of throwing — the `case Success((_, AuthHandshakeError, _))` arm.
    rlpxConnection ! RLPxConnectionHandler.HandleConnection(connection.ref)
    connection.expectMsgClass(classOf[Tcp.Register])
    val bridge = connection.lastSender

    mockHandshaker.handleInitialMessageHandler = Some(_ => throw new Exception("pre-EIP8 decode fails"))
    mockHandshaker.handleInitialMessageV4Handler = Some(_ => (ByteString.empty, AuthHandshakeError))

    val data: ByteString = ByteString((0 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)
    bridge ! Tcp.Received(data)
    rlpxConnectionParent.expectMessage(RLPxConnectionHandler.ConnectionFailed)
    // Discriminator: the `Success((_, AuthHandshakeError, _))` special-case arm short-circuits BEFORE
    // bridgeWrite — unlike the generic `Success((responsePacket, result, remainingData))` fallback arm,
    // which calls bridgeWrite(bridge, responsePacket) before processHandshakeResult. If the special-case
    // arm were removed, this mocked (ByteString.empty, AuthHandshakeError) tuple would fall through to
    // the generic arm and bridgeWrite would forward an empty Tcp.Write to `connection` (InboundTcpBridge
    // relays every Tcp.Write it receives straight to connectionRef). Asserting no such write arrives is
    // what makes this test a genuine regression guard rather than one that passes on both code paths.
    connection.expectNoMessage(Timeouts.shortTimeout)
    rlpxConnectionParent.expectTerminated(rlpxConnection)

  it should "close the connection if the AuthHandshake response message's MAC is invalid" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    // Outgoing connection request arrives
    rlpxConnection ! RLPxConnectionHandler.ConnectTo(uri)
    tcpActorProbe.expectMsg(Tcp.Connect(inetAddress))

    // The TCP connection results are handled
    val initPacket: ByteString = ByteString("Init packet")
    mockHandshaker.initiateHandler = Some(_ => initPacket -> mockHandshaker)

    tcpActorProbe.reply(Tcp.Connected(inetAddress, inetAddress))
    tcpActorProbe.expectMsgClass(classOf[Tcp.Register]) // bridge registers (not typed actor directly)
    val outboundBridge = tcpActorProbe.lastSender
    tcpActorProbe.expectMsg(Tcp.Write(initPacket, RLPxConnectionHandler.Ack))

    // AuthHandshaker handles the response message (that throws an invalid MAC)
    mockHandshaker.handleResponseMessageHandler = Some(_ => throw new Exception("MAC invalid"))
    mockHandshaker.handleResponseMessageV4Handler = Some(_ => throw new Exception("MAC invalid"))

    val data: ByteString = ByteString((0 until AuthHandshaker.ResponsePacketLength).map(_.toByte).toArray)
    outboundBridge ! Tcp.Received(data)
    rlpxConnectionParent.expectMessage(RLPxConnectionHandler.ConnectionFailed)
    rlpxConnectionParent.expectTerminated(rlpxConnection)

  it should "handle SendMessage gracefully during shutdown without dead letters" in new TestSetup:
    // Start setting up connection
    rlpxConnection ! RLPxConnectionHandler.HandleConnection(connection.ref)
    connection.expectMsgClass(classOf[Tcp.Register])
    val bridge = connection.lastSender

    // AuthHandshaker handles initial message and fails (simulating auth failure scenario)
    val data: ByteString = ByteString((0 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)

    // Configure the test double to fail authentication
    mockHandshaker.handleInitialMessageHandler = Some(_ => throw new Exception("Auth failed"))
    mockHandshaker.handleInitialMessageV4Handler = Some(_ => throw new Exception("Auth failed"))

    // Send the auth data which will trigger shutdown
    bridge ! Tcp.Received(data)

    // Immediately send a SendMessage during the shutdown window
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())

    // The actor should gracefully handle the message and terminate without dead letters
    rlpxConnectionParent.expectMessage(RLPxConnectionHandler.ConnectionFailed)
    rlpxConnectionParent.expectTerminated(rlpxConnection, max = Timeouts.normalTimeout)

  it should "handle late Hello message after handshake without compression" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    // Setup a mock that will capture what gets encoded
    var encodedMessages: List[ByteString] = Nil
    mockMessageCodec.encodeMessageHandler = Some { msg =>
      val encoded = ByteString(s"encoded:${msg.underlyingMsg.getClass.getSimpleName}")
      encodedMessages = encodedMessages :+ encoded
      encoded
    }

    setupIncomingRLPxConnection()

    // Clear any messages from setup
    encodedMessages = Nil

    // Send a late Hello message - this should NOT go through MessageCodec.encodeMessage
    // Instead, it should be written directly using frameCodec to avoid compression
    val lateHello: Hello = Hello(
      p2pVersion = 5,
      clientId = "test-client",
      capabilities = Seq(Capability.ETH63),
      listenPort = 30303,
      nodeId = ByteString(Array.fill[Byte](64)(0))
    )

    rlpxConnection ! RLPxConnectionHandler.SendMessage(lateHello)

    // The connection should write the Hello without going through MessageCodec.encodeMessage
    // (which would compress it). Instead, it should use frameCodec directly.
    // We can verify this by checking that encodeMessage was NOT called
    connection.expectMsgClass(classOf[Tcp.Write])

    // The encodeMessage handler should NOT have been called for Hello
    encodedMessages should be(empty)

    // Now send a regular message (non-Hello) and verify it goes through MessageCodec
    connection.reply(RLPxConnectionHandler.Ack)
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsgClass(classOf[Tcp.Write])

    // This time encodeMessage should have been called
    encodedMessages should not be empty
    encodedMessages.head.utf8String should include("Ping")

  // SCALA 3 MIGRATION: Cannot use self-type constraint with `new TestSetup` in Scala 3.
  // Using lazy val for mocks ensures they're created when accessed within MockFactory context.
  trait TestSetup extends SecureRandomBuilder:

    // Mock parameters for RLPxConnectionHandler
    val mockMessageDecoder: MessageDecoder = new MessageDecoder:
      override def fromBytes(`type`: Int, payload: Array[Byte]): Either[DecodingError, Message] =
        throw new Exception("Mock message decoder fails to decode all messages")
    val protocolVersion = Capability.ETH63

    // SCALA 3 MIGRATION: Using configurable test double instead of mock because
    // AuthHandshaker with Selectable cannot be properly mocked in Scala 3
    lazy val mockHandshaker: ConfigurableAuthHandshaker = new ConfigurableAuthHandshaker()
    lazy val connection: TestProbe = TestProbe()

    // SCALA 3 MIGRATION: Cannot mock MessageCodec with constructor parameters in Scala 3
    // Using configurable test double pattern similar to ConfigurableAuthHandshaker
    private lazy val stubFrameCodec: FrameCodec = stub[FrameCodec]
    private val defaultCompressionPolicy = CompressionPolicy(
      compressOutbound = false,
      expectInboundCompressed = false
    )

    class ConfigurableMessageCodec
        extends MessageCodec(
          stubFrameCodec,
          mockMessageDecoder,
          5L, // remotePeer2PeerVersion
          "test-client",
          defaultCompressionPolicy
        ):
      var encodeMessageHandler: Option[MessageSerializable => ByteString] = None
      var readMessagesHandler: Option[
        ByteString => Seq[Either[MessageDecoder.DecodingError, com.chipprbots.ethereum.network.p2p.Message]]
      ] = None

      override def encodeMessage(message: MessageSerializable): ByteString =
        encodeMessageHandler.getOrElse(super.encodeMessage)(message)

      override def readMessages(
          data: ByteString
      ): Seq[Either[MessageDecoder.DecodingError, com.chipprbots.ethereum.network.p2p.Message]] =
        readMessagesHandler.getOrElse(super.readMessages)(data)

    lazy val mockMessageCodec: ConfigurableMessageCodec = new ConfigurableMessageCodec()

    lazy val mockHelloExtractor: HelloCodec = mock[HelloCodec]

    // Configurable test double for AuthHandshaker that can be set up for different test scenarios
    class ConfigurableAuthHandshaker
        extends AuthHandshaker(
          nodeKey = ConfigurableAuthHandshaker.generateKeyPairHelper(),
          nonce = ByteString.empty,
          ephemeralKey = ConfigurableAuthHandshaker.generateKeyPairHelper(),
          secureRandom = new java.security.SecureRandom(),
          isInitiator = false,
          initiatePacketOpt = None,
          responsePacketOpt = None,
          remotePubKeyOpt = None
        ):
      var initiateHandler: Option[URI => (ByteString, AuthHandshaker)] = None
      var handleInitialMessageHandler: Option[ByteString => (ByteString, AuthHandshakeResult)] = None
      var handleInitialMessageV4Handler: Option[ByteString => (ByteString, AuthHandshakeResult)] = None
      var handleResponseMessageHandler: Option[ByteString => AuthHandshakeResult] = None
      var handleResponseMessageV4Handler: Option[ByteString => AuthHandshakeResult] = None

      override def initiate(uri: URI): (ByteString, AuthHandshaker) =
        initiateHandler.map(_(uri)).getOrElse(super.initiate(uri))

      override def handleInitialMessage(data: ByteString): (ByteString, AuthHandshakeResult) =
        handleInitialMessageHandler.map(_(data)).getOrElse(super.handleInitialMessage(data))

      override def handleInitialMessageV4(
          data: ByteString,
          peerLabel: => String = "unknown"
      ): (ByteString, AuthHandshakeResult) =
        handleInitialMessageV4Handler
          .map(_(data))
          .getOrElse(super.handleInitialMessageV4(data, peerLabel))

      override def handleResponseMessage(data: ByteString): AuthHandshakeResult =
        handleResponseMessageHandler.map(_(data)).getOrElse(super.handleResponseMessage(data))

      override def handleResponseMessageV4(
          data: ByteString,
          peerLabel: => String = "unknown"
      ): AuthHandshakeResult =
        handleResponseMessageV4Handler
          .map(_(data))
          .getOrElse(super.handleResponseMessageV4(data, peerLabel))

    object ConfigurableAuthHandshaker:
      private def generateKeyPairHelper(): AsymmetricCipherKeyPair =
        import java.security.SecureRandom
        import com.chipprbots.ethereum.crypto.generateKeyPair
        generateKeyPair(new SecureRandom())

    val uri = new URI(
      "enode://18a551bee469c2e02de660ab01dede06503c986f6b8520cb5a65ad122df88b17b285e3fef09a40a0d44f99e014f8616cf1ebc2e094f96c6e09e2f390f5d34857@47.90.36.129:30303"
    )
    val inetAddress = new InetSocketAddress(uri.getHost, uri.getPort)

    val rlpxConfiguration: RLPxConfiguration = new RLPxConfiguration:
      override val waitForTcpAckTimeout: FiniteDuration = Timeouts.normalTimeout

      // unused
      override val waitForHandshakeTimeout: FiniteDuration = Timeouts.veryLongTimeout

    lazy val tcpActorProbe: TestProbe = TestProbe()
    lazy val rlpxConnectionParent: TypedTestProbe[PeerActor.Command] = testKit.createTestProbe[PeerActor.Command]()
    lazy val rlpxConnection: TypedActorRef[RLPxConnectionHandler.Command] = testKit.spawn(
      RLPxConnectionHandler.apply(
        protocolVersion :: Nil,
        mockHandshaker,
        (_, _, _, _, _, _) => mockMessageCodec,
        rlpxConfiguration,
        _ => mockHelloExtractor,
        rlpxConnectionParent.ref,
        Some(tcpActorProbe.ref)
      )
    )

    // Setup for RLPxConnection, after it the RLPxConnectionHandler is in a handshaked state
    def setupIncomingRLPxConnection(): Unit =
      // Start setting up connection
      rlpxConnection ! RLPxConnectionHandler.HandleConnection(connection.ref)
      connection.expectMsgClass(classOf[Tcp.Register])
      // Bridge child registers with the connection; capture its ref to inject TCP events
      val bridge: ActorRef = connection.lastSender

      // Configure stubFrameCodec to return empty Seq instead of null
      stubFrameCodec.readFrames.when(*).returns(Seq.empty)
      stubFrameCodec.writeFrames.when(*).returns(ByteString.empty)

      // AuthHandshaker handles initial message
      val data = ByteString((0 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)
      val hello = ByteString((1 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)
      val response = ByteString("response data")

      // Configure the test double to return specific responses
      mockHandshaker.handleInitialMessageHandler = Some { _ =>
        (
          response,
          AuthHandshakeSuccess(
            new Secrets(
              Array.empty[Byte],
              Array.empty[Byte],
              Array.empty[Byte],
              new org.bouncycastle.crypto.digests.KeccakDigest(256),
              new org.bouncycastle.crypto.digests.KeccakDigest(256)
            ),
            ByteString()
          )
        )
      }

      mockHelloExtractor.readHello
        .expects(ByteString.empty)
        .returning(Some((Hello(5, "", Capability.ETH63 :: Nil, 30303, ByteString("abc")), Seq.empty)))
      mockMessageCodec.readMessagesHandler = Some(_ => Nil) // For processing of messages after handshaking finishes

      // Inject TCP data via the bridge (bridge forwards to typed actor as TcpReceived)
      bridge ! Tcp.Received(data)
      connection.expectMsg(Tcp.Write(response, RLPxConnectionHandler.Ack))

      bridge ! Tcp.Received(hello)

      // Connection fully established
      rlpxConnectionParent.expectMessageType[RLPxConnectionHandler.ConnectionEstablished]
