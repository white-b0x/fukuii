# Peer Disconnect / Blacklist / Ban Policy — Cross-Client Survey

Full survey of how reference clients handle peer disconnection, re-dial suppression,
banning, and peer reputation/scoring — every wire `DisconnectReason`, not just
`BreachOfProtocol`. This supersedes the narrower NETWORK-03 scoping note
(`.local/docs/research-july/network-03-self-detected-breach-blacklist.md`, which cited
only go-ethereum/core-geth) as the evidence base for
`docs/development/coding-standards/networking/peer-disconnect-blacklist.md`. Governance
rule and evidence-table format follow `reference-client-crosscheck.md` (the EVM/consensus
analog of this doc); this is the P2P/`herald` domain instance of the same discipline —
every claim below is `file:line`-cited against the vendored copy under
`.claude/repo-references/clients/`.

---

## 1. Client coverage map for this domain

| Client | Mechanism family | Weight for this survey |
|---|---|---|
| go-ethereum | Flat, reason-agnostic redial cooldown; **no** reason-keyed ban | **Canonical baseline** — what "doing nothing extra" looks like |
| core-geth | Identical to go-ethereum (fork, `p2p/` untouched at this layer) | Confirms geth's model is also ETC's upstream baseline |
| nethermind | Direction-keyed (local/remote) reconnect-delay + reputation score | **Direction-axis precedent #1** — closest structural analog to fukuii's existing split |
| besu | Direction-keyed node-ID denylist + separate numeric reputation score | **Direction-axis precedent #2 — sharpest, most directly on-point for NETWORK-03** |
| erigon | "Kick" only, via sentry `PenalizePeer`; inherits geth's p2p layer beneath it | Weakest mechanism surveyed — confirms geth's flat model is the floor, not an aberration |
| reth | Graduated reputation + 3-tier backoff + time-bound dual (peer-ID + IP) ban | Bonus corroboration (vendored, not in the original ask) — **sharpest self/received asymmetry of all five** |

None of the five implement anything resembling fukuii's `Permanent` tier (365-day duration,
`PeerManagerActor.scala:1084`) as a *time-based* concept. Section 7.3 makes this the
headline synthesis finding.

---

## 2. go-ethereum / core-geth — flat, reason-agnostic redial cooldown, no ban

**Disconnect reasons** (`p2p/peer_error.go:57-92`, go-ethereum): `DiscRequested`,
`DiscNetworkError`, `DiscProtocolError`, `DiscUselessPeer`, `DiscTooManyPeers`,
`DiscAlreadyConnected`, `DiscIncompatibleVersion`, `DiscInvalidIdentity`, `DiscQuitting`,
`DiscUnexpectedIdentity`, `DiscSelf`, `DiscReadTimeout`, `DiscSubprotocolError` — a superset
of fukuii's 13-reason wire enum (fukuii's `NullNodeIdentityReceived`/`IdentityTheSame` map
to geth's single `DiscInvalidIdentity`/`DiscSelf`).

**Redial suppression — the only mechanism, and it ignores the reason entirely:**
- `p2p/dial.go:39-43`: `dialHistoryExpiration = inboundThrottleTime + 5*time.Second`
- `p2p/server.go:53`: `inboundThrottleTime = 30 * time.Second` → **35s total**
- `p2p/dial.go:538-542` (`startDial`): `d.history.add(hkey, d.clock.Now().Add(dialHistoryExpiration))`
  — added unconditionally on every dial attempt, **not conditioned on disconnect reason at
  all**. A peer disconnected for `DiscProtocolError` and a peer disconnected for
  `DiscRequested` get the identical 35s cooldown.

**No reason-keyed ban, no self/received distinction, no reputation/scoring system exists
in the core `p2p` package.** Confirmed by exhaustive grep of `p2p/server.go` and
`p2p/peer.go` for any additional handling keyed off `DiscReason` beyond logging — none
found.

**core-geth is byte-identical at this layer.** Diff of `p2p/peer_error.go` and
`p2p/dial.go` between `.claude/repo-references/clients/core-geth` and
`.claude/repo-references/clients/go-ethereum` shows only geth-side additions (an extra
`DiscInvalid` sentinel, DNS-lookup refactoring) — core-geth's `dialHistoryExpiration`
(`p2p/dial.go:39-41`) and `inboundThrottleTime` (`p2p/server.go:58`) are the same 30s+5s=35s
values. **This means the ETC reference implementation itself has no permanent-ban concept
at the p2p layer** — any tiering fukuii does beyond the flat 35s cooldown is a fukuii-
original design choice, not something inherited from the ETC upstream.

---

## 3. Nethermind — direction-keyed reconnect-delay + reputation score, no permanent ban

**Wire-level reasons** (`EthDisconnectReason.cs:9-24`) are a 1:1 match to fukuii's 13-code
enum by numeric value (`DisconnectRequested=0x00` … `Other=0x10`).

**A richer internal taxonomy folds onto the wire codes**
(`DisconnectReason.cs:12-75`, `DisconnectReasonExtension.cs:79-111`): ~35 Nethermind-
specific reasons (`InvalidForkId`, `MissingForkId`, `TxFlooding`, `HeaderResponseTooLong`,
`InconsistentHeaderBatch`, `GossipingInPoS`, etc.) each map onto one of the 13 wire codes
via `ToEthDisconnectReason` — e.g. `InvalidGenesis`/`MissingForkId`/`InvalidForkId` all
collapse to wire `BreachOfProtocol` (`DisconnectReasonExtension.cs:86`). This is the same
"our own richer detection, wire only carries the coarse code" shape fukuii's
`Blacklist.BlacklistReason` sealed hierarchy already implements
(`blockchain/sync/Blacklist.scala:47-246` — ~30 fukuii-internal reasons folding onto the
13-code `P2PBlacklistGroup` subset).

**The self/received (`Local`/`Remote`) direction axis, explicit:**
- `NodeStatsLight.cs:130-159` (`AddNodeStatsDisconnectEvent`): takes a `DisconnectType`
  parameter (`Local` or `Remote`) and looks up a *different* parameter table depending on
  direction — `LocalDisconnectParams` vs `RemoteDisconnectParams`.
- `StatsParameters.cs:24-40` (`LocalDisconnectParams`): reconnect-delay + reputation-score
  pairs for self-triggered disconnects — the "very bad" set (`UnexpectedIdentity`,
  `IncompatibleP2PVersion`, `UselessPeer`, `BreachOfProtocol`, `MessageLimitsBreached`) all
  get **15-minute** reconnect delay + **-10000** reputation.
- `StatsParameters.cs:42-55` (`RemoteDisconnectParams`): a *different* set for
  peer-initiated disconnects. Notably: `AlreadyConnected` here is "very bad" (**-10000**
  reputation, but **zero** delay) — a reason not present in `LocalDisconnectParams` at all.
  **Nethermind's "very bad" set includes a received `BreachOfProtocol`** (same 15 min/-10000
  as the local case) — i.e., Nethermind does *not* discount a peer's own accusation the way
  Besu (§4) and fukuii's current `PeerDisconnectPolicy` do. This is a genuine cross-client
  divergence — see §7.2.

**No blacklist/ban set of any kind exists in Nethermind.** The reconnect delay
(`IsConnectionDelayed`, `NodeStatsLight.cs:228-247`) is a soft, decaying signal: it randomizes
delays under 500ms for private-network friendliness (`NodeStatsLight.cs:258-266`), and the
harshest reconnect delay found anywhere in `StatsParameters.cs` is **15 minutes**
(`ReceiveMessageTimeout`/very-bad set). Peer *removal* happens only via reputation-sorted
candidate-pool eviction when the pool exceeds `MaxCandidatePeerCount`
(`PeerManager.cs:696-739`, `PeerStatsComparer.Compare` sorts ascending by
`CurrentReputation`, `PeerManager.cs:759-769` — worst-reputation candidates are dropped
first) — a capacity-pressure eviction, not a standing ban list. A peer with terrible
reputation but no candidate-pool pressure is simply dialed last (`PeerManager.cs:600-609`,
`CollectionsMarshal.AsSpan(_currentSelection.Candidates).Sort(default(PeerComparer))`).

---

## 4. Besu — direction-keyed node-ID denylist (sharpest precedent for NETWORK-03)

**Fine-grained self-detected breach taxonomy** (`DisconnectMessage.java:113-126`): 9 distinct
`BREACH_OF_PROTOCOL_*` sub-reasons all share wire code `0x02` but carry different messages —
`_MALFORMED_MESSAGE_RECEIVED`, `_UNSOLICITED_MESSAGE_RECEIVED`, `_INVALID_BLOCK`,
`_NON_SEQUENTIAL_HEADERS`, `_MESSAGE_RECEIVED_BEFORE_HELLO_EXCHANGE`, etc. — matching wire
code `0x02` via `getCode()` (`DisconnectMessage.java:196-198`) but distinguishable in-process
via `isBreachOfProtocol()` (`DisconnectMessage.java:204-206`, matches on the `BREACH_OF_PROTOCOL`
name prefix). This is the same granularity fukuii's two NETWORK-03 self-detected sites
(invalid `BlockRangeUpdate` invariant vs. generic RLPx decode failure) represent informally —
Besu's precedent is that these are legitimately *different* reasons even though they share a
wire code, which bears on NETWORK-03 Q3 (differentiate the two sites or not).

**`PeerDenylistManager` — the direction axis, explicit and asymmetric by design**
(`PeerDenylistManager.java:29-72`):
```java
private static final Set<DisconnectReason> locallyTriggeredDisconnectReasons =
    ImmutableSet.of(
        DisconnectReason.BREACH_OF_PROTOCOL, DisconnectReason.INCOMPATIBLE_P2P_PROTOCOL_VERSION);

private static final Set<DisconnectReason> remotelyTriggeredDisconnectReasons =
    ImmutableSet.of(DisconnectReason.INCOMPATIBLE_P2P_PROTOCOL_VERSION);
```
(`PeerDenylistManager.java:31-36`). `shouldBlock` (`PeerDenylistManager.java:68-71`):
```java
return (!initiatedByPeer && locallyTriggeredDisconnectReasons.contains(reason))
    || (initiatedByPeer && remotelyTriggeredDisconnectReasons.contains(reason));
```
**A self-detected (`!initiatedByPeer`) `BREACH_OF_PROTOCOL` denylists the peer.** **A
received (`initiatedByPeer`) `BREACH_OF_PROTOCOL` does NOT** — it is conspicuously absent
from `remotelyTriggeredDisconnectReasons`, while `INCOMPATIBLE_P2P_PROTOCOL_VERSION`
denylists in *both* directions. This is the exact inversion fukuii's
`PeerDisconnectPolicy.receivedDisconnectAction` already implements for `BreachOfProtocol`
(`Suppress` — `network/PeerDisconnectPolicy.scala:64`) and directly answers NETWORK-03's
Q1 ("should a self-detected `BreachOfProtocol` blacklist at all?") — **yes, per Besu's
precedent**, and it's the closest reference-client match to fukuii's `IncompatibleP2pProtocolVersion`
handling too, which fukuii already treats as `NotifyAndRemove` (blacklist) on receipt
(`PeerDisconnectPolicy.scala:58-60`) — consistent with Besu's both-directions denylisting of
that specific reason.

**Denylist mechanics** (`PeerPermissionsDenylist.java:31-96`):
- Keyed by **peer node ID**, not IP address (`add(Peer)`/`isPermitted` take a `Peer`/`PeerId`,
  `PeerPermissionsDenylist.java:57-72`) — structurally different from fukuii's IP-keyed
  `PeerAddress` blacklist (`PeerManagerActor.scala:1125`).
- Bounded to **500 entries**, `LimitedSet.create(..., Mode.DROP_LEAST_RECENTLY_ACCESSED)`
  (`DefaultP2PNetwork.java:574`: `PeerPermissionsDenylist.create(500)`) — LRU eviction once
  full, **no explicit time-based expiry at all**.
- Purely **in-memory** (`ConcurrentHashSet`/`LimitedSet`, `PeerPermissionsDenylist.java:34-43`)
  — not persisted to disk; cleared on process restart (`close()`,
  `PeerPermissionsDenylist.java:92-95`, called at shutdown). So despite having no TTL, this
  is **not** a "forever" ban in the way the word suggests — it is bounded by *both* process
  lifetime and LRU capacity pressure.
- Maintained peers are explicitly exempted (`PeerDenylistManager.java:56-60`) — same shape as
  fukuii's `maintainedPeersByNodeId` exemption on the blacklist-add path
  (`PeerManagerActor.scala:453-454`, `!isMaintainedPeer`).

**A separate, orthogonal numeric reputation score exists** (`PeerReputation.java:35-141`):
starts at 100 (`DEFAULT_INITIAL_SCORE`), capped at 150 (`DEFAULT_MAX_SCORE`), decremented by
timeouts/useless-responses (small -1 per event below threshold, -10 at threshold —
`recordRequestTimeout`/`recordUselessResponse`, lines 66-114) and incremented by useful
responses (+1, capped at max — `recordUsefulResponse`, lines 116-120). This score drives
*disconnection* decisions (`USELESS_PEER_BY_REPUTATION`, `DisconnectMessage.java:135`) — it
is a **pre-disconnect signal**, not a post-disconnect ban mechanism; the denylist above is
what actually suppresses re-dial.

---

## 5. Erigon — kick only, no additional ban layer

**`PeerPenalizer.Penalize`** (`execution/p2p/peer_penalizer.go:35-42`) sends a
`PenalizePeerRequest{Penalty: sentryproto.PenaltyKind_Kick}` over gRPC to the sentry
process. The sentry's handler
(`p2p/sentry/sentry_grpc_server.go:1434-1442`, `GrpcServer.PenalizePeer`):
```go
ss.removePeer(peerID, p2p.NewPeerError(p2p.PeerErrorDiscReason, p2p.DiscRequested, nil, "penalized peer"))
```
**This literally rewrites the disconnect reason to `DiscRequested`** regardless of why the
peer was penalized, and does nothing beyond disconnecting — no ban list, no reconnect
delay beyond what the underlying geth-derived `p2p` package's flat 35s dial-history cooldown
(§2) already provides. `sentry_multi_client.go:791` carries an explicit
`// TODO: Extend penalty kinds` comment confirming there is currently no richer mechanism.
Static/trusted peers are explicitly exempted from penalization
(`sentry_grpc_server.go:1438`: `!peerInfo.peer.Info().Network.Static && !peerInfo.peer.Info().Network.Trusted`).
**Erigon is the weakest mechanism surveyed** — it confirms geth's flat model is the
practical floor across the Go-client family, not a gap unique to geth.

---

## 6. reth — graduated reputation + 3-tier backoff + time-bound dual ban (bonus corroboration)

Not in the original ask (task named go-ethereum/core-geth + nethermind/erigon/besu) but
vendored under `.claude/repo-references/clients/reth` and directly on-point — included as
corroborating evidence, weighted per `reference-client-crosscheck.md`'s coverage map (reth
is **not** EVM/consensus-authoritative, but the coverage map's exclusion is specifically
about the un-vendored `revm` interpreter — the P2P/`network-types` crate is fully vendored
and Rust-native, unrelated to that exclusion).

**Reputation constants** (`crates/net/network-types/src/peers/reputation.rs:4-43`):
```rust
pub const DEFAULT_REPUTATION: Reputation = 0;
const REPUTATION_UNIT: i32 = -1024;
pub const BANNED_REPUTATION: i32 = 50 * REPUTATION_UNIT;              // -51200
const REMOTE_DISCONNECT_REPUTATION_CHANGE: i32 = 4 * REPUTATION_UNIT;  // -4096, received disconnect (any reason)
const TIMEOUT_REPUTATION_CHANGE: i32 = 4 * REPUTATION_UNIT;            // -4096
const BAD_MESSAGE_REPUTATION_CHANGE: i32 = 16 * REPUTATION_UNIT;       // -16384
const BAD_PROTOCOL_REPUTATION_CHANGE: i32 = i32::MIN;                  // instant ban
```

**The sharpest self/received asymmetry of all five clients surveyed:**
`REMOTE_DISCONNECT_REPUTATION_CHANGE` (`reputation.rs:13`) — the penalty applied when *the
peer* disconnects us — is a **flat -4096 regardless of the reason the peer gave**. reth does
not reason-key received disconnects at all; a peer claiming `BreachOfProtocol` and a peer
claiming `TooManyPeers` receive the identical mild penalty (12.5 such events needed to reach
`BANNED_REPUTATION`). Only *self-detected* signals — `bad_protocol` (instant `i32::MIN`
ban), `bad_message` (-16384), `timeout` (-4096) — can drive a peer to the ban threshold
quickly. This is a stronger, blanket version of Besu's "don't trust the peer's own breach
accusation" precedent (§4): reth doesn't trust *any* of the peer's stated reasons, full stop.

**Backoff tiers** (`crates/net/network-types/src/peers/config.rs:29-90`,
`PeerBackoffDurations`): explicit `low`/`medium`/`high`/`max` — the same 3-tier shape as
fukuii's `Short`/`Long`/`Permanent`, plus a fourth `max` cap:
```rust
low: 30s, medium: 3min ("3min"), high: 15min ("15min"), max: 1h ("1h")
```
(`config.rs:81-88`, defaults). `backoff_until` (`config.rs:59-64`) *scales* the tier duration
by a per-peer repeat-offense counter (`backoff_time + backoff_time * backoff_counter`),
capped at `max` — so even the harshest backoff tier tops out at **1 hour**, not indefinitely.
A peer that exhausts `max_backoff_count` attempts is dropped from the peer table entirely
(`config.rs:140-149`) rather than banned forever.

**Full ban is separate from backoff, dual-keyed, and time-bound:**
`ban_duration` defaults to **12 hours** (`config.rs:199-200`: `// Ban peers for 12h`), applied
via `ban_peer_until`/`ban_ip_until` (`crates/net/network/src/peers.rs:478,484`) — bans **both**
the peer ID and the IP, triggered when reputation crosses `BANNED_REPUTATION`
(`peers.rs:409,869,982`: `self.ban_list.is_banned_peer`/`is_banned_ip`/`is_banned`). **No
reputation-banned peer is ever banned longer than 12 hours** — `BanList` entries expire.

---

## 7. Cross-client synthesis

### 7.1 The self-detected vs. received trust-direction axis

| Client | Has the axis? | Self-detected (local) | Received (remote) |
|---|---|---|---|
| go-ethereum / core-geth | **No** — flat model | n/a | n/a |
| nethermind | **Yes** — separate `Local`/`Remote` param tables | 15 min/-10000 for the "very bad" set incl. `BreachOfProtocol` | Same 15 min/-10000 set, **including received `BreachOfProtocol`** |
| besu | **Yes** — sharpest, most explicit | `BreachOfProtocol` + `IncompatibleP2PVersion` → denylist | Only `IncompatibleP2PVersion` → denylist; **received `BreachOfProtocol` does NOT** |
| erigon | **No** — kick-only, no ban | n/a | n/a |
| reth | **Yes** — sharpest of all | `bad_protocol`/`bad_message`/`timeout` (self-computed) drive the ban | Flat -4096 **regardless of stated reason** — reth trusts none of the peer's own claims |
| **fukuii (current)** | **Yes** | `BlacklistTier` (reason-only, direction-agnostic — same duration whether self- or peer-triggered) | `receivedDisconnectAction` — `Suppress` for `BreachOfProtocol` only |

**Verdict: the trust-direction axis is validated by 3 of 5 clients (nethermind, besu, reth)
— it is not a fukuii invention, and Besu + reth's treatment of received `BreachOfProtocol`
specifically corroborates fukuii's existing `Suppress` choice.** The one genuine divergence:
nethermind treats received `BreachOfProtocol` as equally severe as self-detected (§3) —
this is the one place the reference clients don't agree with each other, flagged here rather
than resolved silently (see the coding standard's ratification item on this point).

**Structural gap in fukuii vs. nethermind/reth:** fukuii's `BlacklistTier` does not vary
duration by direction — a self-detected and a peer-claimed `BreachOfProtocol` get the same
`Permanent` tier *if* both paths are wired to blacklist at all (today only the received path
is wired; NETWORK-03 is about wiring the self-detected path). Nethermind and reth both
*do* vary duration/severity by direction even when a reason appears in both tables.

### 7.2 Should a self-detected `BreachOfProtocol` blacklist at all?

**Yes — per Besu's `locallyTriggeredDisconnectReasons` precedent (§4), and reth's
`bad_protocol` instant-ban precedent (§6).** Both clients that draw the self/received
distinction agree that self-detected protocol violations are the harshest category, more
severe than the same *label* arriving from the peer. No surveyed client treats a
self-detected breach as ignorable.

### 7.3 Ban-duration synthesis — no reference client implements anything close to a 365-day tier

| Client | Harshest penalty mechanism | Duration | Persisted across restart? |
|---|---|---|---|
| go-ethereum / core-geth | Flat redial cooldown | **35s** (`inboundThrottleTime` 30s + 5s) | No |
| erigon | Same as go-ethereum (inherited) | **35s** | No |
| nethermind | Reconnect delay (decaying signal, not a ban) | **15 min** max (`StatsParameters.cs:27-39`) | No — in-process only |
| besu | Node-ID denylist | **Unbounded time**, but size-capped at 500 (LRU-evict) | **No** — in-memory, cleared on restart |
| reth | Dual peer-ID + IP ban | **12h** (`config.rs:199-200`), backoff separately capped at **1h** | No — in-process `BanList` |
| **fukuii (current)** | IP blacklist, `BlacklistTier.Permanent` | **365 days** (`DefaultPermanentBlacklistDuration`, `PeerManagerActor.scala:1084`) | **No** — `CacheBasedBlacklist` is a Scaffeine in-memory cache (`Blacklist.scala:305-317`), not RocksDB-backed; cleared on restart same as besu/reth |

**fukuii's `Permanent` tier (365 days) is the longest single-entry ban duration surveyed by
roughly 2 orders of magnitude** (next-longest is reth's 12h). It shares besu's and reth's
"not actually persisted to disk, cleared on restart" property and besu's "bounded by cache
capacity pressure" property (`CacheBasedBlacklist.empty(maxSize)`,
`Blacklist.scala:305-317`, Caffeine `maximumSize` + Window-TinyLFU eviction,
`PeerManagerActor.scala:228`: `maxBlacklistedNodes = 32 * 8 * discoveryConfig.kademliaBucketSize`)
— so in practice a fukuii "permanent" ban is bounded by *either* 365 days *or* cache-eviction
pressure, whichever comes first, not truly forever. But the intent and the common case (a
long-uptime node, low blacklist churn) diverge sharply from every reference client's
design, none of which reach for a multi-day, let alone multi-month, single ban duration.
This is the standard's central ratification question — see the coding standard's gap table
and ratification list.

### 7.4 Peer-scarcity safeguards

**No reference client has an explicit peer-scarcity/snap-lenient safeguard analogous to
fukuii's `snapCapableHosts` exemption** (`PeerManagerActor.scala:259-267, 512-529`). This
tracks with the finding in §7.3: because no reference client's ban durations exceed ~15
min–12h, and because nethermind/besu naturally re-admit low-reputation peers under capacity
pressure rather than excluding them outright, the *need* for an explicit scarcity exemption
is itself a symptom of fukuii's outlier-long `Permanent`/`Long` tiers combined with a
genuinely small ETC/Mordor snap-server population (ETC mainnet: 1-3 snap servers, per the
existing code comment) — a network condition none of the reference clients' home networks
(ETH mainnet/Sepolia, or nethermind/besu/erigon's general multi-client ETH deployments) face
at the same severity. This is a **fukuii-original, network-condition-driven mitigation**,
not a reference-client-derived pattern — it should be evaluated on its own merits (does it
correctly bound the specific ETC/Mordor risk) rather than against reference-client parity.

---

## Cross-references

- `docs/development/coding-standards/networking/peer-disconnect-blacklist.md` — the DRAFT
  standard this research grounds.
- `src/main/scala/com/chipprbots/ethereum/network/PeerDisconnectPolicy.scala` — fukuii's
  current tier + received-disconnect-action policy.
- `src/main/scala/com/chipprbots/ethereum/network/PeerManagerActor.scala` — blacklist
  application, snap-lenient exemption, duration constants.
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/Blacklist.scala` — the
  `BlacklistReason` sealed hierarchy and `CacheBasedBlacklist` (Scaffeine, in-memory,
  size-capped) implementation.
- `.local/docs/research-july/network-03-self-detected-breach-blacklist.md` — the original
  NETWORK-03 scoping note (go-ethereum/core-geth only); superseded in scope by this doc but
  still the authoritative site-level file list for the two self-detected `BreachOfProtocol`
  call sites (`PeerActor.scala:578`, `RLPxConnectionHandler.scala:445`).
- `reference-client-crosscheck.md` — the EVM/consensus-domain sibling methodology this doc
  follows for the P2P/networking domain.
