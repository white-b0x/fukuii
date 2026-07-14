# Peer disconnect / blacklist policy

**Domain:** P2P peer-lifecycle policy — what happens after a peer disconnects, for every
wire `Disconnect.Reasons` code, in both directions (we detected it vs. the peer claimed
it). **Owning specialist:** `herald`. **Authority:** cross-client survey in
`docs/research/best-practices/evm-clients/peer-disconnect-blacklist-policy.md` (go-ethereum,
core-geth, nethermind, besu, erigon, reth — every claim below traces to a `file:line`
citation there).

> **Status: DRAFT — not yet operator-ratified.** This is a proposal, not a binding standard.
> No source file may be changed to conform to this doc until the ratification items in
> §5 are resolved by the operator. Citations were checked against the vendored reference
> repos this session (2026-07-08) per the "new synthesis" intake path
> (`../README.md`'s intake → validate → admit → use loop) — **not yet through the
> checker/operator ADMIT step.**

---

## 1. The trust-direction axis (validated — recommend keep as-is)

fukuii already implements the axis this standard would otherwise have to introduce: a
disconnect reason has **two independent policy questions**, not one —

1. **`BlacklistTier`** (`network/PeerDisconnectPolicy.scala:31-32`) — how long to suppress
   re-dial, once the decision to blacklist has been made.
2. **`ReceivedDisconnectAction`** (`PeerDisconnectPolicy.scala:34-40`) — whether a *received*
   wire `Disconnect` (the peer's own unverified claim about why it's leaving) is trusted
   enough to notify `PeerManagerActor`/remove the known-node at all.

This is validated by 3 of 5 surveyed clients (nethermind §3, besu §4, reth §6 of the research
doc) — it is not a fukuii invention, and it is the *majority* pattern among clients that draw
the distinction at all (go-ethereum/core-geth/erigon have no direction axis — they don't
distinguish self vs. received because they have essentially no reason-keyed policy at all).

**Recommendation: keep the two-axis structure.** The specific inputs to each axis are what
the gap table (§4) proposes adjusting.

### The received-`BreachOfProtocol` → `Suppress` choice is directly validated

Besu's `PeerDenylistManager` (`peer-disconnect-blacklist-policy.md` §4) excludes
`BREACH_OF_PROTOCOL` from `remotelyTriggeredDisconnectReasons` while including it in
`locallyTriggeredDisconnectReasons` — the identical asymmetry fukuii's
`receivedDisconnectAction(BreachOfProtocol) → Suppress` already implements
(`PeerDisconnectPolicy.scala:64`). reth's flat, reason-agnostic
`REMOTE_DISCONNECT_REPUTATION_CHANGE` (research doc §6) is an even blunter version of the
same principle: don't trust a peer's stated reason for leaving.

**The one cross-client disagreement on this specific point:** nethermind treats a received
`BreachOfProtocol` as equally severe (15 min / -10000 reputation) as a self-detected one
(`StatsParameters.cs:38,53`, research doc §3) — it does *not* discount the peer's own claim.
This is flagged, not silently resolved — see ratification item R1.

---

## 2. Self-detected protocol breach must be wired (NETWORK-03)

**Current state:** self-detected `BreachOfProtocol` is never wired to notify
`PeerManagerActor` at all — the peer is disconnected but never blacklisted. Two call sites:

- `network/PeerActor.scala:578` — invalid ETH69 `BlockRangeUpdate`
  (`earliestBlock > latestBlock`, or all-zero `latestBlockHash`) →
  `disconnectFromPeer(rlpxConnection, BreachOfProtocol)`, which
  (`PeerActor.scala:444-447`) only sends the wire `Disconnect` and stops — no
  `PeerManagerActor` notification.
- `network/rlpx/RLPxConnectionHandler.scala:445` — undecodable application-message frame →
  `parent ! PeerActor.DisconnectPeer(BreachOfProtocol)`, landing on the same generic,
  non-notifying `DisconnectPeer` handler (`PeerActor.scala:591-592`).

**Recommendation: wire both sites — validated by besu's `locallyTriggeredDisconnectReasons`
(includes `BREACH_OF_PROTOCOL`) and reth's `bad_protocol → i32::MIN` instant-ban (research
doc §4, §6, §7.2). Both direction-aware clients treat a self-verified breach as their
harshest category.** This directly answers NETWORK-03 operator-decision Q1
("should a self-detected `BreachOfProtocol` blacklist at all?") — **yes.**

The *how* (new `Command`, centralize in `PeerDisconnectPolicy`, do not add the notify to the
shared `disconnectFromPeer`/`DisconnectPeer` sink) is already fully scoped in
`.local/docs/research-july/network-03-self-detected-breach-blacklist.md`'s "Recommended
design" and "File list" sections — this standard does not re-derive that, it settles the
duration/tier question those sites still need (ratification item R2 below), and confirms
`Yes` on Q1.

**Unresolved from that scoping note, addressed here:**
- **Q1 (blacklist at all?): YES**, per §2 evidence above.
- **Q2 (Permanent vs. Long tier?): open — see R2.** No surveyed client's harshest tier
  reaches fukuii's 365-day `Permanent`; besu's self-detected-breach denylist has no TTL but
  is bounded by 500-entry LRU capacity, and reth's `bad_protocol` ban is bounded at 12h.
- **Q3 (differentiate the two sites?): open — see R3.** Besu's 9-way
  `BREACH_OF_PROTOCOL_*` sub-reason taxonomy (research doc §4) establishes that reference
  clients *do* distinguish breach subtypes internally even when the wire code is identical —
  this is precedent *for* differentiating, not a settled answer on how.

---

## 3. Peer-scarcity safeguards (validate, don't import)

The existing snap-lenient exemption (`snapCapableHosts`, `PeerManagerActor.scala:259-267`,
`getBlacklistDuration`, `PeerManagerActor.scala:512-529`) has **no reference-client
precedent** — none of the five surveyed clients implement anything analogous (research doc
§7.4). This is not a gap to fill from reference-client evidence; it's a fukuii-original
mitigation for a network condition (ETC mainnet: 1-3 snap servers) none of the reference
clients' primary networks face at comparable severity. **Recommendation: keep it, and
evaluate any future change against the Sepolia 2026-05-13 pool-collapse precedent (PR
#1288) and ETC's specific snap-server scarcity — not against reference-client parity.**

This safeguard is precisely the kind of check that becomes *more* important, not less, if
R2 below results in shortening the `Permanent` tier: a shorter default ban duration reduces
the blast radius of a false-positive blacklist across the board, which is a point in favor
of shortening, but the snap-exemption should stay regardless of what R2 decides, since it
targets a different axis (which *hosts* get leniency, not how long bans generally last).

---

## 4. Gap table — current fukuii behavior vs. recommended

Every reason in fukuii's 13-code wire enum (`WireProtocol.scala:78-90`), plus the
self-detected `BreachOfProtocol` axis NETWORK-03 opens. "Current tier"/"Current received
action" are `PeerDisconnectPolicy.scala:44-70`, verified against the source read this
session. "Recommended" cites the cross-client evidence driving each judgment.

| Reason (wire code) | Current tier | Current received action | Recommended | Delta / rationale |
|---|---|---|---|---|
| `DisconnectRequested` (0x00) | Short | NotifyOnly | **No change** | Universal benign/administrative signal across all 5 clients — none escalate this |
| `TcpSubsystemError` (0x01) | Short | NotifyOnly | **No change** | Transient network-level signal in every client surveyed; no client bans for this |
| `BreachOfProtocol` (0x02), received | Permanent | **Suppress** | **No change** | Directly validated by besu §4 (excluded from `remotelyTriggeredDisconnectReasons`) and reth §6 (flat mild penalty regardless of stated reason). Nethermind disagrees (treats as "very bad" both directions) — flagged as R1, not silently overridden |
| `BreachOfProtocol` (0x02), **self-detected** | **Unwired** (NETWORK-03) | n/a (no received-axis concept for self-detected) | **Wire it; tier open — see R2** | Besu (`locallyTriggeredDisconnectReasons`) and reth (`bad_protocol` instant-ban) both treat self-detected breach as harshest category. Duration is the open question |
| `UselessPeer` (0x03) | Short | NotifyAndRemove | **No change; flag as option** | fukuii/besu treat leniently (besu's `USELESS_PEER_*` spans soft-reputation to hard-protocol failures — a grab-bag, not uniformly severe). Nethermind treats it as "very bad" (15 min, both directions) — a real divergence, presented as R4, not resolved silently |
| `TooManyPeers` (0x04) | Short | NotifyOnly | **No change** | Universal administrative/capacity signal (peer's own connection-management decision). geth: no ban. nethermind: mild penalty (-300 rep, 1 min), not "very bad." reth gives it a dedicated jitter-backoff kind specifically to avoid hot-looping, not a punitive one. This is also the reason class the snap-lenient exemption targets — keep both unchanged together |
| `AlreadyConnected` (0x05) | Short | NotifyOnly | **No change** | Administrative dedup signal, not malicious, in every client. Nethermind's one "very bad" entry for this reason pairs a harsh reputation hit with **zero** reconnect delay — even nethermind doesn't actually block reconnection for it |
| `IncompatibleP2pProtocolVersion` (0x06) | Permanent | NotifyAndRemove | **Direction handling validated; duration open — see R2** | The one reason besu denylists in **both** directions (research doc §4) — universal agreement this deserves harsh, symmetric treatment. Nethermind: "very bad," both directions, 15 min. fukuii's direction handling (both notify+remove) already matches; only the *duration* diverges from every client (see R2) |
| `NullNodeIdentityReceived` (0x07) | Permanent | NotifyAndRemove | **Flag as option — see R5** | No surveyed client treats a null/malformed identity as maximally severe by name; nethermind's implicit default for unlisted reasons is the mild `Other` bucket (-200 rep, zero delay). Plausibly a decode/version-skew bug rather than an attack signal — candidate for downgrade from Permanent to Long |
| `ClientQuitting` (0x08) | Short | NotifyOnly | **No change** | Graceful-shutdown signal, universally treated as benign-to-mild (nethermind: 5 min/-1000, a moderate but non-"very-bad" cooldown — same order of magnitude as fukuii's Short) |
| `UnexpectedIdentity` (0x09) | Long | NotifyAndRemove | **No change** | Nethermind: "very bad," 15 min, both directions — same order of magnitude as fukuii's `Long` tier (60 min default). Besu doesn't denylist for this reason at all (relies on its separate reputation score instead) — no clear signal to escalate further |
| `IdentityTheSame` (0x0a) | Long | NotifyAndRemove | **No change** | Same reasoning as `UnexpectedIdentity` — likely config/discovery edge case, not malice; fukuii's `Long` (not `Permanent`) tier already reflects that, consistent with nethermind's 15 min |
| `TimeoutOnReceivingAMessage` (0x0b) | Short | NotifyOnly | **No change** | Universal agreement this is a mild/transient signal — nethermind explicitly keeps this out of its "very bad" set (5 min/0 rep as a *local* event); reth's `timeout` penalty (-4096) is moderate, not the instant-ban `bad_protocol` treatment |
| `Other` (0x10) | Short | NotifyAndRemove | **No change** | Nethermind explicitly buckets `Other` as mild (-200 rep, zero delay) in **both** direction tables, not "very bad" — direct cross-client agreement with fukuii's Short tier. (Longer-term: fukuii's own debug logging notes `Other` often masks a real ForkId mismatch — a candidate for a future, more specific reason code, out of this standard's scope) |
| Unknown/future reason code | Long (tier default) / Suppress (received default) | | **No change** | Conservative-by-default matches the cross-client posture of not trusting an unrecognized/unverified signal |

**Headline count: 3 of 14 rows carry an open ratification item (self-detected
`BreachOfProtocol`'s wiring+duration, `IncompatibleP2pProtocolVersion`'s duration,
`NullNodeIdentityReceived`'s tier), plus 1 row flagged as an optional escalation candidate
(`UselessPeer`) where a reference client (nethermind) disagrees with fukuii's current
leniency but no other client corroborates escalating it.** The remaining 10 of 14 rows have
**no recommended change** — fukuii's existing per-reason tier/direction choices are already
consistent with the cross-client evidence.

---

## 5. Ratification items (operator decision required before any code change)

**R1 — Received `BreachOfProtocol`: keep `Suppress`, or match nethermind's harsher
treatment?**
Two of three direction-aware clients (besu, reth) support fukuii's current `Suppress`.
Nethermind is the lone dissent (`StatsParameters.cs:38,53`), treating it as equally severe
regardless of direction. **Recommend: keep `Suppress`** (majority + peer-scarcity risk of a
spoofable accusation triggering a scarce-peer ban), but this is presented as a choice, not
a foregone conclusion — nethermind's position is a legitimate, cited alternative.

**R2 — What duration should the harshest tier(s) actually be?**
This is the standard's central, highest-impact question. No reference client implements
anything close to fukuii's current 365-day `Permanent` (research doc §7.3 table). Three
concrete options, none silently preferred:

- **Option A — keep 365 days unchanged.** Rationale: ETC/Mordor's peer population is small
  and low-churn compared to ETH mainnet; a genuinely malicious/protocol-violating peer
  should stay excluded for a long time given how few alternative peers exist to replace lost
  trust. Risk: a peer banned for a transient bug (our own decoder edge case, a version-skew
  false positive) stays locked out for a year with no reference-client precedent for that
  severity, and the Sepolia 2026-05-13 pool-collapse precedent shows a peer-scarce network
  punishes over-aggressive banning specifically.
- **Option B — shrink the harshest tier toward reference-client range (e.g., 12-24h,
  modeled on reth's `ban_duration`).** Rationale: aligns with every surveyed client's
  ceiling within an order of magnitude, meaningfully reduces false-positive blast radius,
  still long enough to deter casual reconnection-flooding. Risk: a peer that is a genuine,
  repeated protocol violator can retry roughly 15-30x more often than under Option A.
- **Option C — reason/direction-specific durations instead of one flat `Permanent`
  value**, modeled on nethermind/reth's per-reason granularity: self-detected
  `BreachOfProtocol` and both-direction `IncompatibleP2pProtocolVersion` (the two reasons
  with the strongest cross-client "treat harshly" signal) keep the longest duration; other
  reasons currently mapped to `Permanent`/`Long` (`NullNodeIdentityReceived`,
  `UnexpectedIdentity`, `IdentityTheSame`) get individually re-evaluated per R5's logic
  rather than inheriting whatever R2 decides for the harshest tier.

**R3 — Should the two self-detected `BreachOfProtocol` sites (invalid `BlockRangeUpdate`
invariant vs. generic RLPx decode failure) share one tier or differentiate?**
Besu's 9-way `BREACH_OF_PROTOCOL_*` sub-reason taxonomy is precedent *for* differentiating
subtypes that share a wire code (research doc §4) — it does not, by itself, tell us these
*specific* two sites should differ. The `BlockRangeUpdate` invariant check is a narrow,
well-defined semantic violation (low false-positive risk); the generic RLPx decode failure
is a broader catch-all (marginally higher false-positive risk from our own decoder bugs,
protocol-version skew, or unsupported future fields) — this asymmetry is the original
scoping note's Q3, restated here as still-open.

**R4 — Should `UselessPeer` escalate from `Short` to `Long`, matching nethermind?**
Low-priority; single-client signal (nethermind only) against fukuii/besu's more lenient
treatment. **Recommend: no change** unless the operator has independent evidence (log data)
that fukuii's `UselessPeer` disconnects are dominated by genuinely low-value/malicious peers
rather than transient causes (e.g., a peer that's simply behind on sync).

**R5 — Should `NullNodeIdentityReceived` downgrade from `Permanent` to `Long`?**
No surveyed client singles this reason out as maximally severe; nethermind's implicit
default (no explicit table entry) is its mildest bucket. Plausibly this reason fires on a
decode/version-skew edge case as often as on an actual attack. **Recommend: downgrade to
`Long`**, contingent on R2's resolution (if R2 shortens `Permanent` significantly, this
item may become moot).

---

## 6. Conformance checks (advisory — no build gate; DRAFT status blocks enforcement)

```bash
# Inventory every disconnect-reason → tier/action mapping site (should resolve to exactly
# PeerDisconnectPolicy.scala as the single source of truth — any other site computing a
# tier or notify decision from a Disconnect.Reasons code directly is drift):
grep -rn "Disconnect.Reasons\." src/main/scala/com/chipprbots/ethereum/network/ --include="*.scala" | grep -v PeerDisconnectPolicy.scala

# Confirm the two NETWORK-03 self-detected sites are still the only unwired BreachOfProtocol
# disconnect calls (re-run after wiring lands — should return 0 once R2/R3 are resolved and
# implemented):
grep -n "BreachOfProtocol" src/main/scala/com/chipprbots/ethereum/network/PeerActor.scala src/main/scala/com/chipprbots/ethereum/network/rlpx/RLPxConnectionHandler.scala
```

These checks are advisory per `../README.md`'s enforcement ladder — they inventory sites
for review, and this standard is not yet even ADMIT-eligible (still DRAFT), so nothing
enforces against it today.

---

## Cross-references

- `docs/research/best-practices/evm-clients/peer-disconnect-blacklist-policy.md` — the full
  cross-client evidence base for every claim in this doc.
- `src/main/scala/com/chipprbots/ethereum/network/PeerDisconnectPolicy.scala` — the current
  implementation this standard proposes changes against.
- `src/main/scala/com/chipprbots/ethereum/network/PeerManagerActor.scala` — blacklist
  application (`getBlacklistDuration`, `PeerClosedConnectionCmd` handling), duration
  constants, snap-lenient exemption.
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/Blacklist.scala` — the underlying
  cache-based blacklist store (Scaffeine, in-memory, size-capped — not RocksDB-persisted).
- `.local/docs/research-july/network-03-self-detected-breach-blacklist.md` — original
  NETWORK-03 scoping note; file list and blast-radius estimate for wiring the two
  self-detected sites remain authoritative once R2/R3 are resolved.
- `../README.md` — the intake → validate → admit → use governance loop this standard is
  currently in the INTAKE stage of.
