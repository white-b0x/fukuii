# erigon — networking-p2p
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

erigon's defining P2P decision is that **devp2p is a separable gRPC service, not an
embedded library.** The sync/execution core never touches sockets, RLPx, discovery, or
peer state directly. Instead it consumes a `Sentry` gRPC API — a narrow interface of
message-send / message-subscribe / peer-management RPCs. Everything below the wire
(RLPx transport, discovery v4/v5, ENR, the eth/wit sub-protocol handlers, peer lifecycle)
lives behind that seam inside a **Sentry** process (or an in-process server that speaks the
same gRPC API).

Two deployment modes fall out of the single seam, chosen at startup by whether
`--sentry.api.addr` is set (`node/components/sentry/provider.go:222`):

- **Local mode** — the node builds one in-process `sentry.GrpcServer` **per eth protocol
  version**, wraps each in a `SentryClientDirect` (in-memory, no network hop), and shares a
  single `p2p.Server` across them (`provider.go:273-288`, `provider.go:303-389`).
- **Remote mode** — `--sentry.api.addr="host1:port,host2:port"` makes the node dial one or
  more **external `sentry` binaries** over gRPC via `GrpcClient` and skip building any local
  server (`provider.go:222-229`; standalone binary `cmd/sentry/main.go:59`,
  `p2p/sentry/sentry_grpc_server.go:1085` `func Sentry(...)`).

Because both modes produce the same `[]sentryproto.SentryClient` slice
(`provider.go:157-162`), the entire core above the seam — sync, txpool, the multi-client
message pumps — is identical regardless of whether P2P is in-process or a fleet of remote
Sentry processes. This is the **multi-sentry** capability: the node fans messages out to,
and aggregates inbound from, *N* sentries at once through a `sentryMultiplexer`
(`p2p/sentry/libsentry/sentrymultiplexer.go:54`).

The consumer side is `MultiClient` (`p2p/sentry/sentry_multi_client/sentry_multi_client.go`):
it opens streaming `Messages(...)` and `PeerEvents(...)` subscriptions against every sentry
(`RecvMessageLoop`/`PeerEventsLoop`, lines 67-155), decodes inbound RLPx payloads delivered
as opaque `InboundMessage{ id, data, peer_id }`, and pushes outbound via `SendMessageById` /
`SendMessageToRandomPeers` / `SendMessageToAll`. The core sets the eth `status` handshake
data once via `SetStatus` (`sentry_api.go:27`), and the Sentry replays it into every RLPx
handshake it performs (`sentry_grpc_server.go:1737` `SetStatus`, consumed by the per-peer
`Run` closure at `sentry_grpc_server.go:945` `awaitStatus`).

## Key types / interfaces / files

- `node/interfaces/p2psentry/sentry.proto:218` — `service Sentry` — the abstraction. RPCs:
  `SetStatus`, `HandShake`, `SendMessageById`/`SendMessageByMinBlock`/`SendMessageToRandomPeers`/
  `SendMessageToAll`, `Messages` (server-stream of `InboundMessage`), `Peers`/`PeerCount`/
  `PeerById`, `PeerEvents` (server-stream), `AddPeer`/`RemovePeer`/`AddTrustedPeer`/
  `RemoveTrustedPeer`, `PenalizePeer`, `NodeInfo`. **This proto is the entire contract between
  core and networking.**
- `node/interfaces/p2psentry/sentry.proto:10-78` — `enum MessageId` — the wire opcode space,
  numbered by eth version (STATUS_65 … eth/68 NEW_POOLED_TRANSACTION_HASHES_68, eth/69
  STATUS_69/GET_RECEIPTS_69/BLOCK_RANGE_UPDATE_69, eth/70 GET_RECEIPTS_70/RECEIPTS_70, eth/71
  GET_BLOCK_ACCESS_LISTS_71/BLOCK_ACCESS_LISTS_71 (EIP-8159), plus the `wit/0` witness
  messages). Inbound/outbound messages cross the gRPC seam as `(MessageId, bytes)` — the core
  does RLP encode/decode, the Sentry does RLPx framing.
- `node/interfaces/p2psentry/sentry.proto:156-165` — `enum Protocol { ETH65…ETH71, WIT0 }` and
  `sentry.proto:146-154` `StatusData` (network_id, td, best_hash, `Forks{genesis, height_forks,
  time_forks}`, block-range fields). The `Forks` message carries **both** block-number forks
  and timestamp forks — the same dual-dispatch fukuii needs for PoW+PoS.
- `p2p/sentry/sentry_grpc_server.go:1129` — `type GrpcServer struct { sentryproto.Unimplemented
  SentryServer; Protocols []p2p.Protocol; p2pServer *p2p.Server; … }` — the **server side**:
  embeds a real devp2p `p2p.Server`, registers eth `p2p.Protocol` run-loops, and translates
  RLPx traffic ↔ gRPC. One `GrpcServer` per eth version.
- `p2p/sentry/sentry_grpc_server.go:907` — `func NewGrpcServer(...)` — builds the `p2p.Protocol`
  whose `Run` closure (`:934-1000`) performs the per-peer eth `status` handshake
  (`handShake[eth.StatusPacket69]` for ≥ETH69, else `StatusPacket`), registers the peer, and
  streams its messages to gRPC subscribers.
- `p2p/sentry/sentry_grpc_server.go:1085` — `func Sentry(ctx, dirs, sentryAddr, …)` — the
  **standalone-process entrypoint**: stands up the p2p stack + a real gRPC server
  (`RegisterSentryServer`, `:889`) bound to `sentryAddr`. This is what `cmd/sentry` runs.
- `p2p/sentry/sentry_multi_client/sentry_multi_client.go:156` — `type MultiClient struct {
  sentries []sentryproto.SentryClient; … }` — the **consumer side**. Per-message handlers
  (`getBlockHeaders66`, `getReceipts69/70`, `getBlockAccessLists71`, `blockRange69`,
  witness handlers) dispatch on `InboundMessage.id`.
- `p2p/sentry/sentry_multi_client/sentry_api.go:27` — `func (cs *MultiClient) SetStatus` —
  pushes the eth status handshake data to **every** connected sentry, skipping any not yet
  `Ready()`.
- `p2p/sentry/libsentry/sentrymultiplexer.go:54` — `func NewSentryMultiplexer([]SentryClient)`
  — presents *N* sentries as one `SentryClient`: `SetStatus`/`SendMessageToAll` fan out,
  `HandShake` aggregates advertised protocol versions. This is the object that makes
  multi-sentry transparent to callers.
- `node/direct/sentry_client.go:72` `SentryClientRemote` / `:160` `SentryClientDirect` — the two
  `SentryClient` implementations: a gRPC stub (remote) and an in-process shim that calls the
  server's methods directly (local, zero-copy, no serialization hop).
- `node/components/sentry/provider.go:179` — `Provider` — the wiring seam. `Initialize`
  (`:217`) chooses remote vs local, populates `Servers` (`:157`, local only), `Sentries`
  (`:162`, always), `Multiplexer` (`:421`); `BuildMultiClient` (`:458`) constructs the
  `MultiClient` over whatever `Sentries` contains.
- `cmd/utils/flags.go:560` `SentryAddrFlag` (`--sentry.api.addr`), parsed to a **list** at
  `:1425` (`common.CliString2Array`) — the multi-sentry entry point. Docs: `cmd/sentry/README.md:25-30`.
- Underlying devp2p (mostly inherited from go-ethereum, unchanged): `p2p/server.go`,
  `p2p/rlpx/`, `p2p/discover/v4_udp.go` + `v5_udp.go` (discovery v4 **and** v5),
  `p2p/enode/` + `p2p/enr/` (ENR), `p2p/dnsdisc/` (EIP-1459 DNS discovery), `p2p/forkid`.

## eth wire versions advertised (feeds §3b wire-evolution cross-ref)

erigon advertises **eth/68, eth/69, eth/70, eth/71** (`p2p/protocols/eth/protocol.go:38-52`:
`ProtocolLengths = {ETH68:17, ETH69:18, ETH70:18, ETH71:20}`), plus the erigon-specific
**`wit/0`** witness sub-protocol (block-witness exchange, `sentry.proto:60-64`). Notes for the
§3b topic:

- **eth/68** — `NewPooledTransactionHashes` carries tx types + sizes (still supported as floor).
- **eth/69** — status message becomes `StatusPacket69` with explicit min/latest block range
  (`BLOCK_RANGE_UPDATE_69`), drops total-difficulty semantics; receipts reworked (`GET_RECEIPTS_69`).
- **eth/70** — receipts messages reworked again (`GET_RECEIPTS_70`/`RECEIPTS_70`).
- **eth/71** — **EIP-8159 Block Access List Exchange**: `GetBlockAccessLists`/`BlockAccessLists`
  (`protocol.go:73-140`, handler `getBlockAccessLists71` at `sentry_multi_client.go:265`).
- Cross-ref to fukuii's herald agent, which advertises **ETH68/ETH69/ETH70** (ETH63-67 removed)
  — erigon is **one version ahead** (already ships eth/71). fukuii has no eth/71 / EIP-8159 yet.
- All these are **ETH/PoS-lineage** wire versions. erigon carries **no ETC/ETChash wire path**;
  for PoW/ETC wire behavior core-geth remains the authority (see [[reference-client-authority]]).

## Design decisions & rationale

- **P2P behind a narrow message-passing gRPC contract.** The core speaks only `(MessageId,
  bytes, peer_id)` + peer-management RPCs. It never sees RLPx, sockets, or discovery. This is
  the whole point: wire-protocol handling is decoupled from sync/execution, so either side can
  be scaled, replaced, restarted, or run out-of-process independently.
- **Opaque payloads at the seam.** `InboundMessage.data` / `OutboundMessageData.data` are raw
  RLP `bytes`; the Sentry does not parse eth message bodies (it only needs `MessageId` to pick
  the RLPx offset). The core owns RLP codec + business logic. This keeps the Sentry protocol-
  version-agnostic below the opcode number and lets the core evolve message semantics without
  redeploying sentries.
- **One GrpcServer per eth version, one shared `p2p.Server`.** Local mode builds a distinct
  `GrpcServer` per advertised version but backs them all with a single discovery/RLPx
  `p2p.Server` and a shared `PeerStore` (`provider.go:303-389`), so a peer speaking any
  supported version is handled by the right server without duplicating the socket layer.
- **`SetStatus` is core→sentry, replayed per handshake.** The core computes the eth `status`
  once (chain id, td, head, forks) and pushes it to every sentry; each sentry replays it into
  every RLPx handshake it performs. The core never participates in the per-peer handshake.
- **Multi-sentry via a multiplexer, not core changes.** Fan-out/aggregate lives in
  `sentryMultiplexer`; the sync core is written against a single `SentryClient` and is unaware
  whether it's talking to one in-process server or five remote processes.
- **Standalone `sentry` binary + `txpool` binary.** `cmd/sentry` runs the P2P stack as its own
  process; `cmd/txpool` also consumes `--sentry.api.addr` (`cmd/txpool/main.go:82`). The gRPC
  seam lets **multiple independent consumers** (execution node + txpool) share one P2P fleet.

## Notable patterns (the reusable idea)

**P2P-as-a-service: put a narrow, transport-agnostic RPC contract at the wire boundary, and
make in-process and out-of-process deployment the same code path.** The transferable mechanics:

1. **Define the seam as a message-passing interface**, not a shared object graph:
   send-by-id / send-broadcast / subscribe-to-inbound / manage-peers / set-handshake-status.
   Everything wire-specific stays on the far side.
2. **Keep payloads opaque** at the seam (tagged blobs) so the boundary doesn't need to track
   protocol-version churn.
3. **Provide two interchangeable implementations** of the same interface — an in-process shim
   (zero-copy, the default) and a remote stub (gRPC) — selected by one config flag. The core
   above is byte-identical either way.
4. **Aggregate N backends behind the same single-backend interface** (the multiplexer) so
   "run one" and "run a resilient fleet" require no core changes.

For fukuii's product-family seams this is the reference: fukuii's networking (herald's devp2p /
RLPx / ETH-wire stack) could be exposed behind an equivalent Scala/gRPC (or Pekko) `Sentry`-style
service so the sync/execution core consumes a message-passing API rather than embedding
`PeerManagerActor`/`EtcPeerManager`. That decoupling is exactly what the enterprise/archival
use-case wants (run and scale P2P separately; multi-sentry for resilience and geographic
spread; restart the P2P tier without bouncing the execution node) and what a multi-network
product family wants (one hardened P2P service, many consumers). See
[[file-tree-seam-direction]] — this is the networking analogue of the consensus-side pluggable
seams.

## Authority note

erigon = **the P2P-as-service (Sentry) decomposition authority** — the reference for how to
lift the wire stack out of the node behind a gRPC seam, run it standalone, and multiplex
several instances. It is **not** the authority for the low-level devp2p behavior itself:
RLPx framing, discovery v4/v5, ENR, and the canonical eth-wire message semantics are inherited
largely unchanged from **go-ethereum**, which remains the authority for that layer. For **PoW /
ETC / ETChash** wire specifics erigon is silent — **core-geth** is the sole authority (see
[[reference-client-authority]]). Treat erigon here as "how to package and decouple P2P," and
go-ethereum/core-geth as "what the packets actually are."

## Gotchas / anti-patterns / things they later changed

- **The status/handshake race.** With a shared `p2p.Server`, the RLPx listener can accept
  inbound dials **before** the core has called `SetStatus`; without a guard those peers bounce
  on `DiscProtocolError`. erigon added `awaitStatus(awaitStatusTimeout)` in the per-peer `Run`
  closure (`sentry_grpc_server.go:945-951`) to briefly wait for the first status. A naive
  P2P-as-service port will hit this same startup ordering bug — the seam introduces a window
  where the wire layer is live but the core hasn't primed it.
- **Serialization tax in remote mode.** Local mode uses `SentryClientDirect` (in-memory calls,
  no marshalling); remote mode pays gRPC serialization on every inbound/outbound message and
  every streamed `PeerEvent`. The seam is free in-process but not across the wire — high-
  throughput message classes (tx gossip, header/body streams) cross it constantly.
- **`MessageId` numbering is version-coupled and non-obvious.** IDs are assigned per eth version
  with gaps (eth/67 removed GetNodeData/NodeData, leaving holes; `sentry.proto:54-55`), and the
  `Protocol` enum has a non-sequential value (`ETH70 = 6`, `WIT0 = 5`; `sentry.proto:156-165`).
  Any reimplementation of this proto must preserve the exact numbers, not renumber densely.
- **`wit/0` is erigon-specific.** The witness sub-protocol multiplexed alongside eth (dedup'd to
  a single shared `GrpcServer`, `sentry_grpc_server.go:1110`) is not a standard eth-wire
  protocol; don't treat it as part of the portable wire contract.
- **`Ready()` gating on multi-sentry.** `SetStatus` skips sentries that aren't `Ready()`
  (`sentry_api.go:34-37`); a hybrid local+remote fleet can therefore have some sentries primed
  and others not at any instant — consumers must not assume uniform status across the fleet.
- **eth/71 (EIP-8159) is bleeding-edge.** erigon already advertises it; most of the network does
  not. It's a floor-vs-ceiling reminder for §3b: erigon's advertised set (68-71) is ahead of
  fukuii's (68-70).
