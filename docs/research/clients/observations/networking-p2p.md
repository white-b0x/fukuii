# Observations — networking-p2p
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/networking-p2p.md + wire-protocol-evolution topic._

Scope: how each reference client structures its devp2p/RLPx stack, negotiates wire versions,
identifies chain compatibility (ForkID), discovers peers, and where each holds authority — and
what fukuii (Pekko-actor P2P, herald's domain) can borrow. **Verdicts are use-case-aware**: a
shape that is DEFAULT for one node role is OPTIONAL/product-family-only for another. The wire
version matrix is reused verbatim from `topics/wire-protocol-evolution.md` — not re-derived here.

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | Authoritative |
|---|---|---|---|---|---|---|---|
| **Concurrency model** | goroutine-channel CSP: serialized `Server.run()` loop owns the peer set, one goroutine per peer, request-ID dispatcher goroutine | inherited geth (goroutine-channel CSP, unchanged) | DotNetty (JVM Netty port): per-connection channel pipeline (handshake→frame→snappy→session) | goroutine-channel CSP inside the Sentry; core consumes gRPC streams (`Messages`/`PeerEvents`) | DotNetty (managed Netty) pipeline; Autofac-DI'd handler factories | tokio-async poll-driven: one top-level `NetworkManager` future, `Swarm` priority poll-loop, per-peer tokio tasks over bounded channels | go-ethereum (CSP, the actor-mappable model) |
| **Advertised ETH versions (HEAD)** | eth/71, 70, 69 (ETH68 removed `723aae2b4`) | eth/68 only (frozen; constants ETH67/68) | eth/68, 69, 70, 71 simultaneous | eth/68, 69, 70, 71 + `wit/0` | eth/68 default; 69/70/71 post-merge; snap/1 while syncing (66/67 registered, not advertised) | eth/66, 67, 68, 69 (`LATEST=Eth69`); 70/71/72 in codec, not advertised | go-ethereum (wire authority); core-geth (ETC advertised set) |
| **Wire-version handling** | `ProtocolVersions` list + `MakeProtocols` loop + `matchProtocols` highest-common per connection | inherited geth (frozen at one version) | generic `CapabilityMultiplexer` (name→disjoint message-code RangeMap); range lives in the `SubProtocol`, not transport | Sentry advertises version list; one `GrpcServer` per eth version, shared `p2p.Server` | `IP2PCapabilityResolver` **chain** (exists / advertised / why decoupled); delta-inheritance handler chain | version-gated **type-safe codec**: negotiated `EthVersion` threaded through `decode_message(version,buf)`, same ID → different struct per version | besu (`CapabilityMultiplexer`); reth (type-safe codec) |
| **ForkID (EIP-2124/2364)** | canonical: 4-byte rolling CRC32 of genesis+forks + `Next`, 4-rule filter state machine (`core/forkid`) | **config-driven** fork enumeration via `confp.BlockForks`/`TimeForks` — computes ETC's schedule generically; the ETC fork-schedule authority | ENR `ForkId` entry; content-derived Status cross-check | inherited `p2p/forkid`; `Forks{genesis, height_forks, time_forks}` carries **both** block+timestamp forks over the seam | ENR `eth` ForkId entry (`Nethermind.Network.Enr/ForkId.cs`) for pre-handshake filtering | inherited semantics (matches geth) | go-ethereum (CRC32 format); **core-geth (ETC fork schedule)** |
| **Discovery** | discv4 + discv5 + DNS (EIP-1459), merged via `FairMix` iterator; ENR/EIP-778 | inherited stack; ETC bootnodes + `blockd.info` ENR-tree via `SetDNSDiscoveryDefaults2` (URL, not genesis-hash) | discv4 self-impl (+EIP-868 ENR retrofit); discv5 **vendored** (Teku lib); DNS (EIP-1459) | inherited geth discv4+v5+DNS, unchanged | `CompositeDiscoveryApp` runs discv4 ∥ discv5 (flag-selected); discv5 now **native** first-party | discv4 native; discv5 **wraps `sigp/discv5` crate**; DNS-tree | go-ethereum (discv4/v5, ENR, DNS); core-geth (ETC bootnode/DNS config) |
| **Peer mgmt / scoring** | two-phase admission (cheap post-handshake gate → capability gate) re-validated inside serial loop; DialRatio reserves ⅔ inbound; per-IP inbound throttle (LAN-exempt) | inherited geth; sparse bootnode sets (3 ETC / 1 Mordor) | `RlpxAgent` maxPeers + `PeerReputation` numeric score (timeout/useless-response decrements); rich named disconnect reasons | peer mgmt behind Sentry RPCs (`AddPeer`/`PenalizePeer`/`AddTrustedPeer`…) | reputation-ordered dialing (`NodeStatsLight`: persisted/2 + session score); static peers bypass cap | `PeersManager`: `ReputationChangeWeights`, inbound/outbound slots, trusted peers, backoff/ban | go-ethereum (admission model); besu/nethermind/reth (reputation scoring) |
| **P2P-as-service** | embedded library | embedded (inherited) | embedded (module seam, in-process) | **`Sentry` gRPC service** — devp2p lifted out of the node; local (in-process shim) or remote (N standalone binaries) behind one flag; `sentryMultiplexer` fans out | embedded (assembly seam) | embedded (crate seam) | **erigon (Sentry gRPC decomposition)** |

## Approach catalog (use-case-aware)

| Approach | Clients using it | Good for (use-case/node-role) | Verdict | Why |
|---|---|---|---|---|
| **ForkID CRC32 fork-filter (EIP-2124/2364)** | go-ethereum (origin), core-geth, besu, erigon, nethermind, reth (all six) | **every role** — cheap, byte-exact chain-compat gate before wasting a slot on an incompatible-fork peer | **DEFAULT (all roles, all networks)** | Non-optional network-citizenship. geth is byte authority for the CRC32 format; **core-geth is the authority for ETC's fork schedule** feeding the hash (config-driven `confp.BlockForks`/`TimeForks` computes ETC's idiosyncratic EIP2…ECBP-1100 schedule generically). fukuii must match both. |
| **Range wire-version advertising** | besu (`CapabilityMultiplexer` name→RangeMap), nethermind (`IP2PCapabilityResolver` chain), reth (version-gated type-safe codec), geth (`ProtocolVersions` list + highest-common) | **multi-network / long-lived nodes** facing a heterogeneous peer population on mixed versions | **DEFAULT (target shape) — replaces fukuii's `best()`-collapse + SNAP-bolt-on** | fukuii collapses negotiation to a single `Option[Capability]` via `best()`, then recovers SNAP as a side-channel boolean whose offset depends on the negotiated ETH version. besu/nethermind/reth all separate *which versions exist* from *which are advertised* and multiplex N subprotocols cleanly. This is the §1f gap. |
| **Channel-ownership → single-owner-actor** | go-ethereum (serialized `Server.run()`, no locks on peer set; request-ID dispatcher) | **the actor-granularity blueprint** — any Pekko Typed P2P reimplementation | **DEFAULT (fukuii's Pekko model maps directly)** | geth's CSP is lock-free ownership: all peer-set mutations flow through one goroutine; two-phase admission re-validated inside it avoids TOCTOU. Maps 1:1 onto a single owning Pekko actor; the request-ID dispatcher maps onto `replyTo`/ask correlation. The reference for *how coarse* the owning actor should be. |
| **Sentry: P2P-as-gRPC-service** | erigon (`service Sentry`; local shim vs remote binaries; multi-sentry multiplexer) | **enterprise / archival / product-family** — scale & restart the P2P tier independently; one hardened P2P service, many consumers (execution + txpool) | **OPTIONAL(product-family / enterprise)** | Not needed for a single embedded node. Its value is the product-family seam: expose herald's stack behind a message-passing (Scala/gRPC or Pekko) `Sentry`-style API so sync/execution consumes send-by-id / subscribe-inbound / manage-peers rather than embedding `PeerManagerActor`. Watch the `SetStatus` handshake-ordering race and remote-mode serialization tax. |
| **Advertised ≠ implemented decoupling** | nethermind (`IP2PCapabilityResolver` chain: default eth/68 + merge-gated 69/70/71 + sync-gated snap/1), reth (`ALL_VERSIONS`⊂`EthVersion` enum; 70/71/72 dormant in codec) | **fork-transition & staged rollout** — carry a version in code before advertising it fleet-wide | **DEFAULT (design principle)** | Separates (1) which handlers exist/can be spoken, (2) which are advertised now, (3) why (baseline / fork-gated / sync-gated / per-network). Additive registration instead of edits to shared negotiation code. Caveat: nethermind's eth/69+ advertisement is **merge-coupled** — a never-merging PoW chain (ETC) would never advertise it on that code path, so fukuii must not inherit the merge-gate literally. |

## Best-practice synthesis

**fukuii = Pekko actors (herald's domain).** The networking DEFAULT is three composable pieces:

1. **ForkID byte-exactness, per network.** Adopt the EIP-2124/2364 CRC32 fork-filter for every
   supported chain. **go-ethereum is the wire-format authority; core-geth is the ETC
   fork-schedule authority** feeding it (its config-driven `confp.BlockForks`/`TimeForks` is the
   model — derive the schedule from config, don't hardcode per-chain). Byte-exactness is gated on
   forge/beacon golden hashes (see fukuii implications).

2. **A range-advertising wire-version layer** separating *exists / advertised / why*, replacing
   fukuii's current `best()`-collapse + SNAP-bolt-on (the §1f gap). besu's `CapabilityMultiplexer`
   (name→disjoint-message-code RangeMap, range lives in the `SubProtocol` not the transport) is the
   cleanest reference; nethermind's `IP2PCapabilityResolver` chain and reth's version-gated
   type-safe codec are corroborating shapes. Adding a version becomes: one capability constant +
   one message list/decoder + one negotiation arm — **not** a transport change (already true on
   both fukuii and besu). fukuii already advertises a real range (`InstanceConfig` eth68/69/70 with
   an ETH71 slot marked for spec-007); what's missing is the generic multiplex layer so a third/
   fourth subprotocol registers without a bespoke boolean.

3. **geth channel-ownership = the actor-granularity model.** geth's serialized `Server.run()`
   (lock-free peer-set ownership, two-phase admission re-validated inside the single owner,
   request-ID dispatcher) maps directly onto a single owning Pekko Typed actor + `replyTo`/ask
   correlation. Use it to decide how coarse herald's owning actor should be.

**fukuii INTENTIONALLY retains eth/68.** Peers still speak it, so keeping it is aligned with
besu/erigon (still advertise 68) and nethermind (68 default). geth/reth dropping it *from default*
is a geth-internal receipt-encoding cleanup (`723aae2b4`), **not** a wire-compat signal to follow.
The eth/68 impl detail (message-code table, `NewPooledTransactionHashesPacket` Types/Sizes/Hashes +
equal-length check, `StatusPacket68` TD/Head handshake) is preserved in the wire-evolution topic.

**OPTIONAL(product-family):** erigon's **Sentry P2P-as-gRPC-service** — lift herald's stack behind
a narrow message-passing seam so it can be scaled/restarted independently and shared by multiple
consumers. Enterprise/archival value; not needed for a single embedded node.

**NOTE — WIRE-ETH71-PARITY finding.** fukuii's advertised set (68/69/70) and geth HEAD's (69/70/71)
differ at **both** ends: fukuii carries a version geth dropped (68, deliberately) **and lacks the
newest (71, EIP-8159 Block Access Lists)**. Separately, fukuii's herald charter labels ETH70
"EIP-7706" but geth attributes eth/70 to **EIP-7975** (partial block receipt lists) — an attribution
mismatch to reconcile before implementing ETH70 wire support. reth's codec already carries eth/72
(EIP-8070) — the leading edge, advertised nowhere by default.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

These are **seeds, not verdicts** — recorded for the Phase-3/4 audit, not actioned in this Phase-2 doc.

- **fukuii's `network/`/scalanet stack = SR-02 (BLOCKED-ON-BATCH-4, herald).** The networking
  modernization is gated; this synthesis feeds it but does not unblock it.
- **A `CapabilityMultiplexer`-style wire-version multiplexer is a Phase-4 seed** — replace the
  `best()`-collapse + SNAP-bolt-on with a range-advertiser separating exists/advertised/why
  (`initial-assessment.md:136`). fukuii already has per-version decoders
  (`MessageDecoders.scala` ETH68/69/70) and a config-driven advertised range (`InstanceConfig`) —
  what's missing is the generic negotiation/multiplex layer.
- **ForkId byte-exactness is gated on forge/beacon golden hashes** — the CRC32 output must be
  validated against reference-client hashes per network (core-geth for ETC, geth for ETH) before
  it can be trusted.
- **Open findings carried forward:** `WIRE-ETH71-PARITY-AND-ETH70-EIP-MISLABEL-01` (missing eth/71
  + ETH70 EIP attribution mismatch) and `FORKSCHEDULE-TIMESTAMP-MIGRATION-5.8C-01` remain open.
- Do NOT act on any of the above in Phase 2. Routing: herald (wire/RLPx/ForkID format), forge
  (ETC fork schedule + golden hashes), beacon (ETH/PoS timestamp forks).
