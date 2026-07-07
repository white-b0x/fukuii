package com.chipprbots.ethereum.network

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.PeerDisconnectPolicy.BlacklistTier
import com.chipprbots.ethereum.network.PeerDisconnectPolicy.ReceivedDisconnectAction
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect.Reasons

/** Table-driven regression guard for the NETWORK-01 disconnect→blacklist policy. Every row is the truth table from
  * `.local/docs/research-july/disconnect-policy-refactor-design.md` §1e — the byte-for-byte behavior of the
  * pre-refactor split (PeerActor's `handleDisconnect` reason match + PeerManagerActor's
  * `blacklistDurationForDisconnect`). If a future change to `PeerDisconnectPolicy` alters any cell here, this spec
  * fails immediately instead of requiring another multi-pass archaeology exercise like the one that produced this
  * refactor.
  */
class PeerDisconnectPolicySpec extends AnyFlatSpecLike with Matchers:

  // reason -> (tier, receivedDisconnectAction)
  private val truthTable: Seq[(Long, BlacklistTier, ReceivedDisconnectAction)] = Seq(
    (Reasons.DisconnectRequested, BlacklistTier.Short, ReceivedDisconnectAction.NotifyOnly),
    (Reasons.TcpSubsystemError, BlacklistTier.Short, ReceivedDisconnectAction.NotifyOnly),
    (Reasons.BreachOfProtocol, BlacklistTier.Permanent, ReceivedDisconnectAction.Suppress),
    (Reasons.UselessPeer, BlacklistTier.Short, ReceivedDisconnectAction.NotifyAndRemove),
    (Reasons.TooManyPeers, BlacklistTier.Short, ReceivedDisconnectAction.NotifyOnly),
    (Reasons.AlreadyConnected, BlacklistTier.Short, ReceivedDisconnectAction.NotifyOnly),
    (Reasons.IncompatibleP2pProtocolVersion, BlacklistTier.Permanent, ReceivedDisconnectAction.NotifyAndRemove),
    (Reasons.NullNodeIdentityReceived, BlacklistTier.Permanent, ReceivedDisconnectAction.NotifyAndRemove),
    (Reasons.ClientQuitting, BlacklistTier.Short, ReceivedDisconnectAction.NotifyOnly),
    (Reasons.UnexpectedIdentity, BlacklistTier.Long, ReceivedDisconnectAction.NotifyAndRemove),
    (Reasons.IdentityTheSame, BlacklistTier.Long, ReceivedDisconnectAction.NotifyAndRemove),
    (Reasons.TimeoutOnReceivingAMessage, BlacklistTier.Short, ReceivedDisconnectAction.NotifyOnly),
    (Reasons.Other, BlacklistTier.Short, ReceivedDisconnectAction.NotifyAndRemove)
  )

  private def reasonName(reason: Long): String =
    Seq(
      "DisconnectRequested" -> Reasons.DisconnectRequested,
      "TcpSubsystemError" -> Reasons.TcpSubsystemError,
      "BreachOfProtocol" -> Reasons.BreachOfProtocol,
      "UselessPeer" -> Reasons.UselessPeer,
      "TooManyPeers" -> Reasons.TooManyPeers,
      "AlreadyConnected" -> Reasons.AlreadyConnected,
      "IncompatibleP2pProtocolVersion" -> Reasons.IncompatibleP2pProtocolVersion,
      "NullNodeIdentityReceived" -> Reasons.NullNodeIdentityReceived,
      "ClientQuitting" -> Reasons.ClientQuitting,
      "UnexpectedIdentity" -> Reasons.UnexpectedIdentity,
      "IdentityTheSame" -> Reasons.IdentityTheSame,
      "TimeoutOnReceivingAMessage" -> Reasons.TimeoutOnReceivingAMessage,
      "Other" -> Reasons.Other
    ).collectFirst { case (name, r) if r == reason => name }.getOrElse(s"0x${reason.toHexString}")

  "PeerDisconnectPolicy" should "cover all 13 Disconnect.Reasons values exactly once" in {
    truthTable.map(_._1).distinct should have size 13
  }

  it should "match the pre-refactor truth table for every reason (tier)" in {
    truthTable.foreach { case (reason, expectedTier, _) =>
      withClue(s"reason=${reasonName(reason)}: ") {
        PeerDisconnectPolicy.tier(reason) shouldBe expectedTier
      }
    }
  }

  it should "match the pre-refactor truth table for every reason (receivedDisconnectAction)" in {
    truthTable.foreach { case (reason, _, expectedAction) =>
      withClue(s"reason=${reasonName(reason)}: ") {
        PeerDisconnectPolicy.receivedDisconnectAction(reason) shouldBe expectedAction
      }
    }
  }

  it should "suppress notification only for BreachOfProtocol among the 13 defined reasons" in {
    truthTable.foreach { case (reason, _, expectedAction) =>
      withClue(s"reason=${reasonName(reason)}: ") {
        val action = PeerDisconnectPolicy.receivedDisconnectAction(reason)
        if reason == Reasons.BreachOfProtocol then action.shouldNotify shouldBe false
        else action.shouldNotify shouldBe true
        action shouldBe expectedAction
      }
    }
  }

  it should "fall back to Long tier and Suppress (byte-for-byte parity with the pre-refactor " +
    "case _ => // nothing) for an unknown/future/malformed reason code" in {
      val unknownReason = 0xffL
      PeerDisconnectPolicy.tier(unknownReason) shouldBe BlacklistTier.Long
      PeerDisconnectPolicy.receivedDisconnectAction(unknownReason) shouldBe ReceivedDisconnectAction.Suppress
    }
