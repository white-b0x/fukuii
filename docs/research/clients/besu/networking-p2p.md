# besu — networking-p2p

_Commit/branch documented: `3fd233a4f93556e932f734d8feecbad4a047ff67` (branch `upstream`,
`origin/upstream`). Documented 2026-07-13. Read-only research; no fukuii source touched._

_Operator emphasis for this pass: besu advertises **eth/68 through eth/71** simultaneously — a
*range* of wire versions, not a single pinned one — and negotiates per-peer via a generic
capability-multiplexing mechanism. This doc leads with that theme because it is the reference
shape for widening fukuii's ETH68/69(/70) support into a cleanly pluggable version range._

## Architecture summary

besu's p2p stack splits into two Gradle modules with a clean seam between them:
`ethereum/p2p` (`org.hyperledger.besu.ethereum.p2p.*`) owns the **protocol-agnostic** RLPx/devp2p
transport — auth handshake, framing, Snappy, the Hello capability exchange, discovery (v4/v5/DNS),
peer connection lifecycle, `maxPeers`/permissions — and knows nothing about `eth` or `snap`
message semantics. `ethereum/eth` (`org.hyperledger.besu.ethereum.eth.*`) supplies the **eth**
wire-protocol implementation (`EthProtocol`, `EthProtocolManager`, message classes) as one
pluggable `SubProtocol` registered into the transport layer at startup; `SnapProtocol` (same
module) is a second, structurally identical registration. This registration model is the
headline pattern: **a wire-version range is a property of a single `SubProtocol` (version →
message-set switch), and simultaneous multi-subprotocol support is a property of the transport's
`CapabilityMultiplexer` (name → disjoint message-code range), not something either side has to
special-case.** IBFT/QBFT register a *third* kind of `SubProtocol` (their own consensus gossip
messages) through the exact same seam — proof the abstraction generalizes beyond eth/snap.

## Key types / interfaces / files

### The multi-version eth registration (the operator's headline ask)

- `ethereum/eth/src/main/java/org/hyperledger/besu/ethereum/eth/EthProtocol.java:32-35` — four
  `Capability` constants advertised simultaneously: `ETH68`, `ETH69`, `ETH70`, `ETH71`
  (`Capability.create("eth", version)`). `LATEST = ETH71` (`:57`).
- `ethereum/eth/.../EthProtocolVersion.java:28-31,34-90,98-105` — `V68..V71` int constants; three
  distinct message-code lists (`eth68Messages` `:34-48`, `eth69Messages` `:55-70` — adds only
  `BLOCK_RANGE_UPDATE` per EIP-7642 — `eth71Messages` `:73-90` — adds
  `GET_BLOCK_ACCESS_LISTS`/`BLOCK_ACCESS_LISTS`). **eth/70 reuses eth/69's message list verbatim**
  (`getSupportedMessages` `:98-105`, `case V69, V70 -> eth69Messages`) — eth/70 changes field-level
  *shape* inside existing messages (see Status below), not the message set. This exactly parallels
  fukuii's own ETH70 (`GetReceipts70`/`Receipts70` add fields, no new message code).
- `ethereum/eth/.../EthProtocolManager.java:169-186` — `calculateCapabilities`: builds **all
  four** capabilities, then filters by `ethProtocolConfiguration.getMaxEthCapability()` /
  `getMinEthCapability()` (config-driven range, default unbounded — `EthProtocolConfiguration.java:31-32,76-84`).
  Throws if the filtered range is empty (`:180-184`). **This is the seam for adding a new wire
  version**: append the `Capability` constant, add its message list to `EthProtocolVersion`, add
  a line to `calculateCapabilities` — no other file changes to *advertise* the new version.
- `EthProtocolManager.java:210-213` — `getSupportedCapabilities()` returns the full advertised
  range; `:202-208` `getHighestProtocolVersion()` reduces it to a max for logging/metrics only.
  The manager itself is version-polymorphic — one `EthProtocolManager` instance handles every
  version in `supportedCapabilities`, dispatching per-message via
  `EthProtocol.isValidMessageCode(version, code)`.

### The generic multi-subprotocol negotiation (`SubProtocol` + `CapabilityMultiplexer`)

- `ethereum/p2p/.../rlpx/wire/SubProtocol.java:17-55` — the interface every wire protocol
  implements: `getName()` (3-char name), `messageSpace(protocolVersion)` (how many message codes
  this version reserves), `isValidMessageCode(protocolVersion, code)`, `messageName(...)`.
  Version-awareness lives entirely inside the `SubProtocol` implementation — the transport layer
  never special-cases a version number.
- `ethereum/p2p/.../rlpx/wire/Capability.java:32-84` — `(name, version)` pair, RLP
  read/write (`writeTo`/`readFrom` `:52-67`, the wire form of one entry in Hello's capability
  list per devp2p's `rlpx.md`), `equals`/`hashCode` on the pair.
- `ethereum/p2p/.../rlpx/wire/CapabilityMultiplexer.java:32-49,118-145` — **the negotiation
  engine**. Constructed per-connection from `(List<SubProtocol> subProtocols, List<Capability> a,
  List<Capability> b)` — `a`/`b` are the local and remote Hello capability lists.
  `calculateAgreedCapabilities` (`:118-145`): sorts by `(name, version desc)`, intersects
  (`retainAll`), then walks the sorted list keeping **only the first (= highest-version) entry
  per protocol name** (`:131-134`, `if curProtocol.equalsIgnoreCase(prevProtocol) continue`) and
  assigns each surviving capability a **disjoint message-code range**
  (`Range.closedOpen(offset, offset+messageSpace)` `:139`, offsets accumulate starting after the
  16 codes reserved for wire-level Hello/Disconnect/Ping/Pong, `WIRE_PROTOCOL_MESSAGE_SPACE=16`
  `:34`). Result: an `ImmutableRangeMap<Integer, Capability>` — **N subprotocols, each at its own
  independently-negotiated version, coexist on one connection**, code-offset multiplexed exactly
  per devp2p's RLPx spec (`multiplex`/`demultiplex` `:66-91`).
- `AbstractPeerConnection.java:81-84,166-168` — each connection stores its own
  `Set<Capability> agreedCapabilities` from the multiplexer and a `protocolToCapability` map;
  `capability(String protocol)` returns the version this *specific peer* negotiated for that
  protocol name. **Different peers on the same node can be on different eth versions
  simultaneously** — there is no node-global "the" eth version.
- `DeFramer.java:194-198,220-238` — where negotiation actually fires: on receiving the peer's
  Hello, builds `new CapabilityMultiplexer(subProtocols, localNode.getPeerInfo().getCapabilities(),
  peerInfo.getCapabilities())`; if `getAgreedCapabilities()` is empty, disconnects with
  `USELESS_PEER_NO_SHARED_CAPABILITIES` (`:232-238`).
- `p2pVersion >= 5` (`DeFramer.java:189-192`) unconditionally enables Snappy for the whole
  connection (`framer.enableCompression()`) — a transport-level flag orthogonal to which eth/snap
  version was negotiated.

### Registration wiring (how `EthProtocol`/`SnapProtocol` reach the transport)

- `ethereum/p2p/.../config/SubProtocolConfiguration.java:23-42` — a simple accumulator:
  `withSubProtocol(SubProtocol, ProtocolManager)` appends to parallel `List<SubProtocol>` /
  `List<ProtocolManager>`.
- `app/.../controller/BesuControllerBuilder.java:1225-1232` — `createSubProtocolConfiguration`:
  `new SubProtocolConfiguration().withSubProtocol(EthProtocol.get(), ethProtocolManager)`, then
  conditionally `.withSubProtocol(SnapProtocol.get(), snapProtocolManager)` if snap sync is
  enabled. **Every PoA build (Clique/IBFT/QBFT) reuses this exact base method** — consensus
  mechanisms only ever *add* subprotocols, never touch eth/snap registration.
- `app/.../RunnerBuilder.java:707-718` — pulls both lists out of `SubProtocolConfiguration`
  (`:713-714`); flattens **all** ProtocolManagers' `getSupportedCapabilities()` into one
  `Set<Capability> supportedCapabilities` (`:716-718`,
  `.flatMap(pm -> pm.getSupportedCapabilities().stream())`) — this flattened set becomes the
  local Hello capability list, i.e. `{eth/68, eth/69, eth/70, eth/71, snap/1}` all in one Hello.
- `RunnerBuilder.java:735` — `.setSupportedProtocols(subProtocols)` (the 2-element `SubProtocol`
  list, one entry per protocol *name*) feeds `RlpxConfiguration`, which `NetworkRunner` later
  passes to `DeFramer`'s constructor. `RunnerBuilder.java:808` —
  `.supportedCapabilities(caps)` (the flattened `Capability` set, one entry per protocol
  *version*) feeds `DefaultP2PNetwork.Builder`, which builds `LocalNode` from it
  (`DefaultP2PNetwork.java:554,580-581,629-636`) and serializes it into the outbound Hello.

### Status message — version-conditional shape inside one message (not a new message)

- `ethereum/eth/.../messages/StatusMessage.java:219-260,262-333` — `EthStatus` carries **both**
  `totalDifficulty` and `blockRange` fields but enforces mutual exclusion by protocol version at
  construction (`checkArgument` `:241-252`: `blockRange` only for `>= V69`, `totalDifficulty` only
  for `<= V68`). `writeTo`/`readFrom` branch on `totalDifficulty != null` — i.e. **the wire shape
  is content-derived at decode time** (`isEth69Shape = in.nextIsList()` at the 4th RLP element,
  `:301-317`), with an explicit RLP-level cross-check that throws if the detected shape disagrees
  with the declared `protocolVersion` field (`:306-317`). eth/68 layout:
  `[version, networkId, td, blockHash, genesis, forkId]`; eth/69+ layout:
  `[version, networkId, genesis, forkId, earliestBlock, latestBlock, blockHash]` (`:263-266,292-295`).
- `EthProtocolManager.handleNewConnection:356-389` — builds Status using
  `cap.getVersion()` from **this connection's negotiated capability**
  (`connection.capability(getSupportedProtocol())` `:360`), branching TD-vs-blockRange via
  `EthProtocol.isEth69Compatible(cap)` (`:370-375`). One `EthProtocolManager`, N connections, each
  building a version-correct Status independently.

### Discovery — v4, v5/ENR, DNS

- `ethereum/p2p/.../discovery/discv4/` — self-implemented Kademlia-style discv4: `PeerTable.java`
  (k-buckets), `RecursivePeerRefreshState.java` (iterative FIND_NODE lookup), `PacketType.java` +
  `packet/` (PING/PONG/FINDNEIGHBORS/NEIGHBORS **and** `enrrequest`/`enrresponse` — the EIP-868 ENR
  extension bolted onto v4).
- `ethereum/p2p/.../discovery/discv5/PeerDiscoveryAgentV5.java:46-51,70-107` — discv5 is **not**
  self-implemented; besu delegates to Teku's `org.ethereum.beacon.discovery` library
  (`MutableDiscoverySystem`, `DiscoverySystemBuilder`). Adaptive discovery cadence: 1s while
  under-connected, 30s once peer target is met (class doc `:60-65`). ENR built lazily in `start()`
  once the real RLPx TCP port is known, so the local ENR's `tcp`/`tcp6` fields are correct
  (`:111-116`).
- `ethereum/p2p/.../discovery/dns/DNSDaemon.java` + `EthereumNodeRecord.java` — EIP-1459 DNS
  discovery tree client (enrtree:// root resolution).
- `discovery/NodeRecordManager.java` — owns the local ENR record shared across v4/v5/DNS.

### Peer connection lifecycle & permissions

- `ethereum/p2p/.../rlpx/RlpxAgent.java:82-119,399-400` — top-level connection manager:
  `maxPeers` cap, `peerPermissions`/`peerPrivileges` gate, a `peersConnectingCache` (30s dedup on
  in-flight outbound attempts, `:95-100`), a stackless sentinel exception
  (`NO_PROTOCOL_MANAGER_EXCEPTION` `:70-80`) thrown when **no** registered `ProtocolManager` wants
  to accept an inbound peer — avoids the ~1-3KB stack-trace-capture cost per rejection at high
  connection-attempt rates (explicitly documented, `:63-69`).
- `ethereum/eth/.../manager/PeerReputation.java:35-64` — a numeric score (`DEFAULT_INITIAL_SCORE
  =100`, `DEFAULT_MAX_SCORE=150`) decremented on request timeouts (`TIMEOUT_THRESHOLD=5`) and
  "useless responses" (`USELESS_RESPONSE_THRESHOLD=5` within a 1-minute sliding window) — feeds
  `USELESS_PEER_BY_REPUTATION` disconnects.
- `ethereum/p2p/.../rlpx/wire/messages/DisconnectMessage.java:109-156` — disconnect reasons are
  richly subdivided beyond the devp2p spec's byte codes: many *named* reasons share wire byte
  `0x03` (`USELESS_PEER_*` — no shared capabilities, world state unavailable, mismatched pivot,
  by-reputation, by-chain-comparator, exceeds trailing-peer cap) and `0x10`
  (`SUBPROTOCOL_TRIGGERED_*` — mismatched network id / fork id / genesis hash, unparsable status,
  PoW-after-TTD, invalid block range) — the byte on the wire is spec-compliant; the Java enum
  value is besu's internal diagnostic granularity.

## Design decisions & rationale

- **Version range lives in the `SubProtocol`, not the transport.** `CapabilityMultiplexer` never
  parses a version number for eth/snap-specific meaning — it only calls
  `subProtocol.messageSpace(version)`. Widening a supported range is entirely local to
  `EthProtocol`/`EthProtocolVersion`; the RLPx/Hello/framing layer is untouched. Trade-off: the
  `SubProtocol` author must keep `messageSpace`/`isValidMessageCode`/`messageName` in sync across
  versions by hand (a `switch` per version, as seen in `EthProtocol.messageSpace` `:70-76`).
- **Per-connection, per-protocol-name version pinning.** `CapabilityMultiplexer` picks the highest
  *mutually supported* version **independently per protocol name** (`:131-134`) — a peer that only
  speaks eth/68 and a peer that speaks eth/71 are both valid simultaneous connections to the same
  node. This is what "supports a range" concretely buys: heterogeneous peer population without a
  node-wide version pin.
- **Message-code space is a shared, ordered resource.** Reserving `WIRE_PROTOCOL_MESSAGE_SPACE=16`
  codes for Hello/Disconnect/Ping/Pong (`:34`) then packing each negotiated `SubProtocol` into a
  contiguous `Range` (`offsetMessageCode` `:93-116`) means N subprotocols can multiplex on one
  connection without collision — this is exactly devp2p/RLPx's documented multiplexing scheme
  (`rlpx.md`), not a besu-specific invention, but the *generality* of the implementation
  (`List<SubProtocol>`, works for any N) is besu's contribution worth citing.
- **Status message shape is content-derived, mirroring the consensus-engines lesson.** Rather than
  a version-number-only switch, `StatusMessage` cross-validates the RLP shape against the declared
  version (`:306-317`) — the same "derive from content, cross-check against the stated axis"
  philosophy already documented in `besu/consensus-engines.md` for fork dispatch.
- **discv5 is vendored, not reimplemented.** besu leans on Teku's audited discv5 library rather
  than re-deriving ENR/handshake/session-key crypto — reduces attack surface for a security-
  sensitive subsystem at the cost of an external dependency.

## Notable patterns (the reusable idea)

- **`SubProtocol` as a pluggable capability registration point.** One interface
  (`getName`/`messageSpace(version)`/`isValidMessageCode(version,code)`/`messageName(version,code)`)
  is implemented once per wire protocol family (`EthProtocol`, `SnapProtocol`, BFT gossip
  protocols) and registered via `SubProtocolConfiguration.withSubProtocol(protocol, manager)` — a
  flat accumulator, not a special-cased list. **This is the direct answer to "how does fukuii
  widen ETH68/69 into a fuller range": model the version range as one `SubProtocol`-equivalent
  object with a version→message-set function, not as N special-cased branches.**
- **`CapabilityMultiplexer`: local caps × remote caps → per-name-highest-version RangeMap.** The
  reusable shape for negotiating an *arbitrary* number of simultaneously active subprotocols on
  one connection, each independently versioned.
- **Capability advertisement = flatten every registered `ProtocolManager`'s supported-version
  list into one Hello.** `RunnerBuilder.java:716-717`'s one-line `flatMap` is the entire "advertise
  a version range" mechanism — no bespoke per-protocol Hello-building code.
- **Config-bounded advertised range** (`EthProtocolConfiguration.getMin/MaxEthCapability`,
  default unbounded) — lets an operator cap the advertised range without touching code, useful for
  staged rollout of a new wire version across a fleet.

## Authority note

**besu is a strong authority for multi-wire-version support and the `SubProtocol`/pluggable-
capability registration model** — it is the only client in this sprint's roster with a live 4-
version-wide eth range (68-71) and a generic N-subprotocol multiplexer exercised in production by
three consensus families (mainnet PoW/PoS, Clique, IBFT/QBFT) each adding their own gossip
subprotocol through the same seam. For eth/snap **message semantics** (what each version's fields
mean), go-ethereum remains authoritative — besu implements the same devp2p `eth`/`snap` specs, it
does not originate them. besu is **not** an ETC authority: no ECIP-aware wire behavior; core-geth
remains sole authority for ETC-specific network config (Mordor bootnodes, ECIP fork-ID rules).

## Gotchas / anti-patterns / things they later changed

- **eth/70 has zero new messages of its own** — its only wire difference from eth/69 is internal
  to `GetReceipts`/`Receipts` field shape (per EIP-7706, not modeled as a separate message class
  in this besu commit's `EthProtocolVersion` — the message-set list is identical to eth/69,
  `:98-105`). Don't assume "new version number" implies "new message list" when porting this
  pattern to fukuii's `ETH70MessageDecoder`.
- **`DEFAULT_MAX_CAPABILITY = Integer.MAX_VALUE`** (`EthProtocolConfiguration.java:31`) means an
  unconfigured besu node always advertises its full compiled-in range (currently 68-71) — there is
  no accidental version pin; a narrower range must be explicit operator config.
- **`CapabilityMultiplexer` silently drops a subprotocol with `messageSpace <= 0`** (`:138`,
  `if (messageSpace > 0) builder.put(...)`) rather than erroring — a `SubProtocol` implementation
  that returns 0 for an unrecognized version fails closed (protocol just isn't offered) rather than
  throwing, which is a deliberate but easy-to-miss soft-failure mode when adding a new version to
  `messageSpace`'s switch and forgetting a case (falls into `default -> 0`, `EthProtocol.java:70-76`).
- **discv4's `enrrequest`/`enrresponse` packets are v4-transport ENR retrofit (EIP-868), not
  discv5** — don't conflate the two when reading the `discv4/internal/packet/` directory; discv5
  proper is the separate Teku-backed `discv5/` package.

## fukuii comparison (grounded in current fukuii source, not a forward-ref placeholder)

fukuii's negotiation is structurally narrower than besu's, in two specific ways confirmed by
reading the current code:

1. **`Capability.negotiate` (`src/main/scala/com/chipprbots/ethereum/network/p2p/messages/Capability.scala:47-82`)
   returns a single `Option[Capability]`, not a per-name-highest RangeMap.** It does compute a
   highest-common-version intersection *within* the ETH family
   (`ethVersions1.map(_.version).toSet.intersect(...)`, mirroring besu's per-name logic) and
   *within* SNAP (exact match only, `snapVersions1.intersect(snapVersions2)`), but then collapses
   both results through `best()` (`:85-101`) into **one** winning capability, prioritizing ETH over
   SNAP. SNAP support is recovered afterward as a side-channel boolean
   (`RLPxConnectionHandler.scala:469-470`: `supportsSnap = capabilities.contains(SNAP1) &&
   hello.capabilities.contains(SNAP1)`), and SNAP's wire message-code offset is derived from the
   *negotiated ETH version* rather than from an independent multiplexed range
   (`network/p2p/messages/SNAP.scala:23-25`: "Wire offsets therefore depend on the negotiated ETH
   version"). This is the functional equivalent of besu's `CapabilityMultiplexer`, but hand-rolled
   for exactly two protocol families instead of generalized over `List[SubProtocol]`.
2. **fukuii already advertises a real range today** —
   `InstanceConfig.scala:50-59` builds `supportedCapabilities` from config flags
   (`Option.when(p.eth68)(ETH68)`, `.eth69`, `.eth70` — with an explicit code comment marking the
   `ETH71` slot for spec-007) — so the "advertise several versions, let per-peer negotiation pick
   the common one" half of besu's pattern is already in place; what's missing is the *generic*
   negotiation/multiplex layer (item 1) that would let a third or fourth simultaneous subprotocol
   (or a future non-ETH/non-SNAP wire protocol) register without a new bespoke boolean flag.
3. Per-version message decoders already exist and are dispatched by a version match
   (`network/p2p/MessageDecoders.scala:339-345`, `ethMessageDecoder`: `ETH68 -> ETH68MessageDecoder`,
   `ETH69 -> ETH69MessageDecoder`, `ETH70 -> ETH70MessageDecoder`) — structurally the same idea as
   besu's `EthProtocolVersion.getSupportedMessages(version)` switch, just organized as one object
   per version instead of one switch inside one class. Adding ETH71 here is the same shape of
   change on both clients: new `Capability` case object, new message-list/decoder, one new switch
   arm — **not** a transport-layer change, on either client.

This is a forward-ref for the Phase-3/4 fukuii audit, not a verdict — recorded here because it was
directly grounded while reading besu's negotiation code side-by-side with fukuii's.
