# go-ethereum — networking-p2p
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary

geth's P2P stack is a layered pipeline, each layer a distinct Go package that a
network client (`eth`, `snap`) plugs into rather than reimplements:

```
discovery (discv4 UDP / discv5 UDP / dnsdisc)  →  FairMix iterator  →  dialScheduler
                                                                            │
                                     inbound TCP accept  ─────────┐         │
                                                                  ▼         ▼
                                              Server.SetupConn (RLPx enc handshake
                                                       + devp2p proto handshake + checks)
                                                                  │
                                                                  ▼
                                              Peer.run  →  per-capability sub-protocol Run()
                                                                  │
                                          eth.Handle / snap.Handle (Status/ForkID handshake,
                                                     message dispatch, request/response IDs)
```

Layer responsibilities:
- **`p2p.Server`** (`p2p/server.go`) — the top-level owner: holds the node key,
  the `enode.LocalNode` (self ENR), the discovery backends, the dial scheduler,
  the TCP listener, and the live peer set. A single `run()` goroutine is the
  serialization point for all peer-set mutations (add/drop/trusted).
- **`dialScheduler`** (`p2p/dial.go`) — decides *who* to dial and *when*, bounded
  by `MaxDialedConns` and a dial-history cooldown.
- **RLPx transport** (`p2p/rlpx/`) — encrypted, authenticated, framed message
  transport (ECIES auth handshake → AES-CTR + keccak-MAC frames → optional Snappy).
- **discovery** (`p2p/discover/`, `p2p/dnsdisc/`, `p2p/enode/`, `p2p/enr/`) —
  finds candidate nodes and maintains a Kademlia routing table; produces
  `enode.Iterator`s of dial candidates.
- **wire protocols** (`eth/protocols/eth/`, `eth/protocols/snap/`) — the
  application sub-protocols negotiated as devp2p "capabilities"; each provides
  a `[]p2p.Protocol` via `MakeProtocols`, a handshake, message handlers, and
  serve-side DoS bounds.

The `transport` interface (`p2p/server.go:146`) is the seam that lets tests
substitute `MsgPipe` for real sockets — the whole protocol stack runs over an
in-memory pipe.

## Key types / interfaces / files

- `p2p/server.go:74` — `Server`, manages all peer connections; embeds `Config`,
  owns `discv4`/`discv5`/`discmix`/`dialsched`, and the run-loop channels.
- `p2p/server.go:146` — `transport` interface: `doEncHandshake`, `doProtoHandshake`,
  `MsgReadWriter`, `close`. The single abstraction over RLPx vs test pipes.
- `p2p/server.go:142` — `conn` wraps a raw `net.Conn` with `flags` (dyn/static/
  inbound/trusted), the negotiated `caps`, and a `cont` error channel used to
  hand results back from the run loop's checkpoints.
- `p2p/server.go:634` — `Server.run()`, the serialized event loop; `select`s over
  add/removetrusted, `checkpointPostHandshake`, `checkpointAddPeer`, `delpeer`.
- `p2p/server.go:756` / `:771` — `postHandshakeChecks` / `addPeerChecks`: the
  two-phase admission gate (MaxPeers, MaxInboundConns, already-connected, self,
  useless-peer/no-matching-protocol).
- `p2p/server.go:548-560` — `MaxInboundConns()` = `MaxPeers - MaxDialedConns()`;
  `MaxDialedConns()` = `MaxPeers / DialRatio` (default ratio 3 → ~⅓ of slots are
  outbound dials, ⅔ reserved for inbound).
- `p2p/dial.go:98` — `dialScheduler`; `:412` `checkDial` rejects self /
  already-connected / already-dialing / recently-dialed / no-port. `:43`
  `dialHistoryExpiration = inboundThrottleTime + 5s`.
- `p2p/rlpx/rlpx.go:50` — `Conn`; `:300` `Handshake` (ECIES auth), `:162`
  `readFrame` / `:231` `writeFrame` (AES-CTR + keccak `hashMAC`), `:131` `Read` /
  `:211` `Write` do Snappy de/compress. `maxUint24` frame-size cap (`:215`).
- `p2p/peer.go:270` — `Peer.run()` fans out `readLoop`, `pingLoop` (15s interval,
  `:48`), and `startProtocols`; `:46` `snappyProtocolVersion = 5` gates Snappy.
- `p2p/discover/table.go:45-46` — `alpha = 3` (Kademlia concurrency), `bucketSize = 16`.
- `p2p/discover/v4_udp.go:52-57` — `respTimeout 500ms`, `expiration 20s`,
  `bondExpiration 24h`, `maxFindnodeFailures 5`, `ntpFailureThreshold 32`.
- `p2p/discover/v5_udp.go` + `p2p/discover/v5wire/encoding.go:505` — discv5
  WHOAREYOU-challenge handshake and encrypted session packets.
- `p2p/enode/idscheme.go:31` — `ValidSchemes` (v4 / secp256k1 ENR identity);
  `:45` `SignV4`, `:93` `Secp256k1` ENR key.
- `p2p/enode/iter.go:320` — `FairMix`: fairly interleaves multiple discovery
  `Iterator`s (v4, v5, dnsdisc) into one candidate stream for the dialer.
- `p2p/dnsdisc/client.go:109` — DNS-based discovery: resolves a signed Merkle
  tree of ENRs from TXT records (`SyncTree`, `NewIterator`).
- `core/forkid/forkid.go:66` — `ID{Hash [4]byte, Next uint64}`; `:75` `NewID`,
  `:114` `NewFilter`, `:227` `checksumUpdate` (rolling CRC32), `:134` `newFilter`
  implements the 4-rule compatibility check.
- `eth/protocols/eth/protocol.go:33-48` — version constants, `ProtocolVersions`,
  `protocolLengths`, message codes.
- `eth/protocols/eth/handshake.go:38` — `Peer.Handshake`: sends/reads `StatusMsg`,
  validates NetworkID, ProtocolVersion, Genesis, ForkID filter, block range.
- `eth/protocols/eth/handler.go:108` — `MakeProtocols` builds one `p2p.Protocol`
  per advertised version, each carrying `DialCandidates` (a discovery iterator)
  and the ForkID ENR attribute.
- `eth/protocols/eth/dispatcher.go:44` / `:192` — `Request`/`Response` with a
  request-ID-matched `dispatcher()` goroutine.
- `eth/protocols/snap/protocol.go:29-45` — snap `SNAP1`/`SNAP2` versions and
  message codes (SNAP2 adds `GetAccessLists`/`AccessLists`).

## Design decisions & rationale

- **Serialized run loop, no locks on the peer set.** All peer-set mutations flow
  through channels into the single `Server.run()` goroutine (`server.go:634`).
  Handshake-completion "checkpoints" (`checkpointPostHandshake`, `checkpointAddPeer`)
  are the *only* way a connection joins the peer set, and they re-run the
  admission checks inside the loop so the peer count can't race
  (`addPeerChecks` at `:771` explicitly "repeats the post-handshake checks
  because the peer set might have changed"). This is CSP-style ownership rather
  than shared-mutable-state locking — the direct analog for a Pekko actor model.

- **Two-phase handshake with an early cheap gate.** `setupConn` (`server.go:900`+)
  runs the RLPx *encryption* handshake first, checkpoints (`postHandshakeChecks`
  — MaxPeers/self/duplicate) *before* spending effort on the devp2p *capability*
  handshake, then checkpoints again (`addPeerChecks` — drops peers with no
  overlapping protocols, `DiscUselessPeer`). Cheap rejections happen before
  expensive work.

- **DialRatio reserves inbound capacity.** `MaxDialedConns = MaxPeers/DialRatio`
  (default 3) caps outbound dials at ~⅓, leaving ⅔ for inbound so a node stays
  reachable and doesn't monopolize its own slots (`server.go:552`).

- **Discovery is pluggable and merged, not hardcoded.** `FairMix`
  (`enode/iter.go:320`) unifies discv4, discv5, and DNS discovery behind one
  `enode.Iterator`; the dial scheduler consumes candidates without knowing their
  source. Each wire protocol carries its own `DialCandidates` iterator
  (`eth/protocols/eth/handler.go:129`), so eth peers are found via an eth-filtered
  discovery stream.

- **ForkID over raw genesis/TD.** The eth handshake identifies chain compatibility
  by a 4-byte rolling-CRC32 checksum of genesis + passed fork points plus the
  *next* fork block/time (`forkid.go`). The `NewFilter` 4-rule state machine
  (`forkid.go:174`+) lets a synced node reject peers on incompatible forks while
  still accepting peers that are merely ahead/behind (subset/superset of known
  forks) — this is EIP-2124/2364, and geth is the canonical implementation.

- **ETH69 replaced TD-based status with an available-block-range.** Post-merge,
  total difficulty is meaningless, so `StatusPacket` (`protocol.go:91`) carries
  `EarliestBlock`/`LatestBlock`/`LatestBlockHash` and a separate
  `BlockRangeUpdateMsg` (0x11) lets peers announce their served range live
  (`peer.go:611`, gated `version < ETH69`).

- **Serve-side soft limits everywhere.** Every serve handler bounds both byte
  size (`softResponseLimit = 2MB`) and item count (`maxHeadersServe`/
  `maxBodiesServe`/`maxReceiptsServe = 1024`; snap `maxCodeLookups`/
  `maxTrieNodeLookups = 1024`, `maxTrieNodeTimeSpent = 5s`). A malicious `Get*`
  can never force an unbounded response.

## Notable patterns (the reusable idea)

- **The `transport` interface as a test seam.** Making the encrypted transport an
  interface (`server.go:146`) lets the entire handshake + protocol stack run over
  an in-memory `MsgPipe` in unit tests with zero sockets. Any P2P reimplementation
  should keep the transport abstract behind the peer/protocol logic.

- **Request-ID dispatcher for async request/response.** `dispatcher.go` tags each
  outbound `Get*` with a request ID, registers a pending `Request`, and a single
  `dispatcher()` goroutine matches incoming responses back by ID with timeout and
  metadata validation — decoupling "who asked" from "who replied" over a single
  multiplexed connection. This is exactly the correlation problem a Pekko
  ask-pattern / `replyTo` solves.

- **Rolling-checksum fork identity (ForkID).** A tiny, cheap-to-compare 4-byte
  value that encodes an entire fork schedule, computed incrementally
  (`checksumUpdate` = `CRC32(prev-blob || fork)`), plus a state-machine filter
  that distinguishes "incompatible" from "just out of sync." This is the single
  most transferable piece — see below.

- **Two-phase admission with re-validation inside the serialized owner.** Reject
  cheaply and early, then re-check under the single-writer invariant before
  committing — avoids TOCTOU races without fine-grained locking.

## Authority note

geth is the **canonical reference** for devp2p (RLPx enc handshake, capability
negotiation), the discovery protocols (discv4, discv5, ENR/EIP-778, DNS
discovery/EIP-1459), the `eth` and `snap` wire protocols, and ForkID
(EIP-2124/2364). fukuii's PoS/ETH-family networking must match geth byte-for-byte
on the wire; the `herald` agent owns this parity. (For PoW/ETC-specific
divergence — e.g. ETC network IDs and its own fork schedule feeding ForkID —
core-geth is the ETC authority, but the wire *format* is identical to geth.)

## Gotchas / anti-patterns / things they later changed

- **Advertised eth versions have moved past fukuii's assumption.** The task brief
  says "ETH68/69/70", but at this commit geth advertises
  **`ProtocolVersions = {ETH71, ETH70, ETH69}`** (`protocol.go:44`) — **ETH68 is
  gone and ETH71 is added.** `protocolLengths` = `{ETH71:20, ETH69:18, ETH70:18}`
  (`protocol.go:48`). Highest common version wins in devp2p negotiation. fukuii's
  herald charter (ETH68/69/70) is now one version behind geth's head; a parity
  audit should confirm whether ETH71 (BlockAccessLists / EIP-8159 support,
  message codes `0x12`/`0x13`) needs porting or is intentionally out of scope for
  the ETC-first product.

- **ETH70/71 and SNAP2 add block-access-list messages.** `GetBlockAccessListsMsg`
  (0x12) / `BlockAccessListsMsg` (0x13) at the eth level and `GetAccessListsMsg`
  (0x08) / `AccessListsMsg` (0x09) at snap/2 (`snap/protocol.go`) are new; snap/2
  is gated behind a feature flag in `MakeProtocols`, so it isn't always
  advertised even though it's implemented. Version-guard any handler you port
  (`peer.go:405` `if p.version > ETH69`, `:613` `if p.version < ETH69`).

- **Snappy only above p2p protocol v5.** `snappyProtocolVersion = 5`
  (`peer.go:46`); frames are only compressed once the base devp2p protocol
  version is ≥5. A transport that always compresses (or never) will fail interop
  against older peers. Snappy decode also re-checks `maxUint24` on the
  *decompressed* size to prevent decompression bombs (`rlpx.go:153`).

- **The handshake `Status` must be the very first message.** `readStatusMsg`
  (`handshake.go:96`) rejects anything whose code isn't `StatusMsg` before the
  eth handshake completes, with a 5s `handshakeTimeout`. Sending any other
  message first is an instant drop.

- **ForkID "accept rather than reject" fallthrough.** `newFilter` ends with
  `log.Error("Impossible fork ID validation"); return nil` (`forkid.go:220`) — a
  deliberate fail-open on logically-unreachable states. Don't "tidy" this into a
  reject; it's intentional defense against a bug isolating the node.

- **Inbound throttling is per-source-IP and LAN-exempt.** `inboundThrottleTime =
  30s` (`server.go:53`); repeated inbound attempts from the same non-LAN IP are
  rejected via `inboundHistory` (`server.go:860`+). LAN peers bypass it, which
  matters for private/consortium deployments where many nodes share a subnet.

- **discv4 bonding TTL is 24h, findnode-failure eviction at 5.** `bondExpiration
  = 24h`, `maxFindnodeFailures = 5` (`v4_udp.go:56`); a node that fails 5 findnode
  requests is dropped from its bucket (`table.go:730`), and pings older than 24h
  force re-bonding. Getting these constants wrong causes slow, churny discovery.

## Use-case / node-role fitness

- **Discovery + dial scheduler + RLPx + eth handshake** — foundational for *every*
  role (enterprise, custody, validator, light, archival/RPC, multi-network). This
  is the non-optional core.
- **snap serve handlers** (`snap/handler.go`, `maxTrieNodeTimeSpent` etc.) — matter
  most for **archival/RPC** nodes acting as *snap servers* to bootstrap others;
  a leaf/custody node that never serves state can run with snap-serving disabled.
- **ForkID filtering + block-range status (ETH69+)** — critical for **validator**
  and **multi-network** nodes to avoid wasting slots on incompatible-fork or
  out-of-range peers.
- **Trusted-peer + DialRatio + inbound throttle knobs** — the **enterprise /
  consortium** surface: pin a static trusted set (`AddTrustedPeer`,
  `server.go:271`, trusted peers bypass MaxPeers), bias inbound/outbound, and rely
  on LAN-exemption for co-located fleets.
- fukuii models this stack with **Pekko actors** (herald's domain:
  `PeerDiscoveryManager`, `ServerActor`, ETH wire handlers); geth's serialized
  `run()` loop maps naturally onto a single owning actor, and its request-ID
  dispatcher onto Pekko's `replyTo`/ask correlation.
