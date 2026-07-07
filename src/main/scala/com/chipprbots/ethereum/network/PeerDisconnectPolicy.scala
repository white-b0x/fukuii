package com.chipprbots.ethereum.network

import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect

/** Single source of truth for what happens when a peer disconnects, keyed by the wire `Disconnect.Reasons` code. Two
  * independent axes, each a pure function of the reason alone:
  *
  *   - [[BlacklistTier]] — how long to blacklist the peer's address, once we decide to. Identical regardless of which
  *     `PeerActor` call site triggered the blacklist.
  *   - [[ReceivedDisconnectAction]] — whether a *received* wire `Disconnect` (the peer's own claim about why they're
  *     leaving) should be trusted enough to (a) notify `PeerManagerActor` at all and (b) drop the peer from the
  *     known-nodes set. This governs only `PeerActor.handleDisconnect` — the one call site where the reason comes from
  *     the peer itself. The other three notify sites (`ConnectionFailed` retries-exhausted, `HandshakeFailure`,
  *     `handleTerminated` retries-exhausted) are self-classified events — the reason there is our own conclusion, not
  *     an unverified claim from the remote side — and always notify unconditionally; they deliberately do not consult
  *     this table.
  *
  * WHY the split exists (authoritative note — do not restate at call sites): peer-scarce networks made an aggressive,
  * undifferentiated blacklist policy actively harmful (Sepolia 2026-05-13: 100+ peers blacklisted within 5 minutes,
  * pool collapsed to one peer; ETC mainnet has 1-3 snap servers total) — hence the Short/Long/Permanent tiering and the
  * snap-lenient peer-retention exemption in `PeerManagerActor.getBlacklistDuration` (PR #1288). Symmetrically,
  * `BreachOfProtocol` is excluded from [[ReceivedDisconnectAction]] specifically because a *received*
  * `Disconnect(BreachOfProtocol)` is the PEER accusing US of a breach, and [[BlacklistTier]] maps `BreachOfProtocol` to
  * Permanent — trusting an unverified, often transient accusation (fork/version edge cases, decode hiccups mid-sync) at
  * face value for a permanent ban would progressively isolate the node. A *self-detected* breach (we decode-fail their
  * message, or they send an invalid `BlockRangeUpdate`) is a different, currently-unwired signal — see `PeerActor`'s
  * self-initiated `DisconnectPeer(BreachOfProtocol)` sites, out of this policy's scope.
  */
object PeerDisconnectPolicy:

  enum BlacklistTier:
    case Permanent, Short, Long

  final case class ReceivedDisconnectAction(shouldNotify: Boolean, removeKnownNode: Boolean)

  object ReceivedDisconnectAction:
    val NotifyAndRemove: ReceivedDisconnectAction =
      ReceivedDisconnectAction(shouldNotify = true, removeKnownNode = true)
    val NotifyOnly: ReceivedDisconnectAction = ReceivedDisconnectAction(shouldNotify = true, removeKnownNode = false)
    val Suppress: ReceivedDisconnectAction = ReceivedDisconnectAction(shouldNotify = false, removeKnownNode = false)

  import Disconnect.Reasons.*

  def tier(reason: Long): BlacklistTier =
    reason match
      case BreachOfProtocol | IncompatibleP2pProtocolVersion | NullNodeIdentityReceived => BlacklistTier.Permanent
      case UnexpectedIdentity | IdentityTheSame                                         => BlacklistTier.Long
      case TooManyPeers | AlreadyConnected | ClientQuitting | Other | UselessPeer | TcpSubsystemError |
          DisconnectRequested | TimeoutOnReceivingAMessage =>
        BlacklistTier.Short
      // Unknown/future reason code — conservative default, matches today's catch-all in the
      // pre-refactor blacklistDurationForDisconnect (`reason` is a raw wire Long, not a closed
      // enum on our side, so this case is unreachable for every value defined today).
      case _ => BlacklistTier.Long

  def receivedDisconnectAction(reason: Long): ReceivedDisconnectAction =
    reason match
      case IncompatibleP2pProtocolVersion | UselessPeer | NullNodeIdentityReceived | UnexpectedIdentity |
          IdentityTheSame | Other =>
        ReceivedDisconnectAction.NotifyAndRemove
      case TooManyPeers | TcpSubsystemError | DisconnectRequested | ClientQuitting | TimeoutOnReceivingAMessage |
          AlreadyConnected =>
        ReceivedDisconnectAction.NotifyOnly
      case BreachOfProtocol => ReceivedDisconnectAction.Suppress
      // Unknown/future/malformed wire reason code → suppress, matching the pre-refactor
      // `case _ => // nothing` byte-for-byte and the don't-blacklist-on-unrecognized-peer-code
      // default: an unrecognized reason is exactly the ambiguous, unverified, peer-supplied
      // signal we already decline to act on for BreachOfProtocol. Choosing to blacklist unknown
      // reasons is a separate, deliberate policy decision, not an accidental default.
      case _ => ReceivedDisconnectAction.Suppress
