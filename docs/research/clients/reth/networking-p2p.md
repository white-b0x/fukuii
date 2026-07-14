# reth — networking-p2p
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth's P2P stack lives under `crates/net/` and is a **tokio-async, poll-driven**
implementation of the Ethereum devp2p/RLPx stack. It is layered into small,
independently-usable crates rather than one monolith:

- **Transport / encryption**: `ecies/` — the RLPx ECIES framed transport (AES + MAC,
  the `Codec`/`stream` handshake).
- **Wire codec (transport-free)**: `eth-wire-types/` holds the pure message types +
  RLP encode/decode and the `EthVersion` enum; `eth-wire/` wraps them in framed
  streams (`P2PStream`, `EthStream`, `multiplex`, `eth_snap`) plus the p2p `Hello`
  and eth `Status` handshakes.
- **Discovery**: `discv4/` (native kademlia-like UDP impl), `discv5/` (thin wrapper
  around the `sigp/discv5` crate), `dns/` (EIP-1459 DNS-tree discovery).
- **Orchestration**: `network/` — the `NetworkManager` (an endless `Future`), the
  `Swarm` (a `Stream` of `SwarmEvent`s), `SessionManager`, `NetworkState`, and
  `PeersManager` (reputation/ban).
- **Peer/session data & policy**: `network-types/` (reputation, backoff, session
  state), `peers/`, `network-api/` (the public `NetworkEvent`/handle traits).

The defining structural choice: **everything is a `Future`/`Stream` advanced by
`poll`**, not an actor or a goroutine-per-connection. The `NetworkManager` is a
single top-level future; per-peer sessions are spawned as tokio tasks that
communicate back over channels, and the manager multiplexes them by polling the
`Swarm` in a strict priority loop. This contrasts geth's model of one goroutine
per peer coordinated through Go channels/`select`.

## Key types / interfaces / files

- `crates/net/eth-wire-types/src/version.rs:21` — `EthVersion` enum: `Eth66`
  through `Eth72`. `LATEST = Eth69` (`:50`); `ALL_VERSIONS = [Eth69, Eth68, Eth67,
  Eth66]` (`:53`) — see Authority/advertised note below. RLP-encoded as a single
  byte 66–72 (`:102`, `:114`); `try_from` rejects anything outside 66–72.
- `crates/net/eth-wire/src/hello.rs:207` — `HelloMessageBuilder::build()`: when
  protocols are unset, defaults capabilities to `EthVersion::ALL_VERSIONS` — i.e.
  **the node advertises eth/66, eth/67, eth/68, eth/69 by default**. Eth70/71/72
  are defined in the enum but are *not* in `ALL_VERSIONS`, so they are implemented
  ahead of finalization and not advertised by default.
- `crates/net/eth-wire-types/src/capability.rs:70` — `Capability { name, version }`
  (RLP `["eth", 69]`); `:216` `Capabilities` precomputes per-version booleans;
  `:253` `supports_eth_at_least` encodes that eth versions are *additive* (a peer
  on eth/71 implicitly supports all eth/66..71 messages).
- `crates/net/eth-wire/src/protocol.rs:11` — `Protocol { cap, messages }`: a
  capability plus its reserved message-count, used for **RLPx message-ID
  multiplexing** across sub-protocols on one connection. `EthMessageID::message_count(version)`
  drives the offsets (eth/66–68 = 17, eth/69–70 = 18, eth/71 = 20).
- `crates/net/eth-wire-types/src/message.rs:61` — `ProtocolMessage<N>` / `EthMessage<N>`
  (`:312`): the type-safe message ADT. `decode_message(version, buf)` (`:99`) is
  version-gated: it returns `MessageError::Invalid(version, id)` when a message ID
  is not legal for the negotiated version (e.g. `GetNodeData` on ≥eth/67 `:160`,
  `BlockRangeUpdate` on <eth/69 `:197`, `GetBlockAccessLists` on <eth/71 `:203`).
  Same ID also decodes into *different* structs by version (`NewPooledTransactionHashes`
  66/68/72 `:132`; `Receipts`/`Receipts69`/`Receipts70` `:178`; `Status` vs
  `StatusEth69` per EIP-7642 `:117`).
- `crates/net/eth-wire-types/src/message.rs:800` — `RequestPair<T> { request_id: u64,
  message: T }`: the request/response correlation wrapper (eth/66+ request IDs),
  generic and reused across every request message.
- `crates/net/eth-wire/src/multiplex.rs:1` + `eth_snap.rs:1` — RLPx sub-protocol
  multiplexer: `eth` is the primary protocol, `snap/2` (EIP-8189) rides as a
  "dependent satellite" demuxed by capability message-id offset over one connection.
- `crates/net/eth-wire/src/ethstream.rs:34` — `UnauthedEthStream` → `EthStream`: the
  `Status` handshake gate (a stream can't send app messages until Status is exchanged).
- `crates/net/network/src/manager.rs:108` — `NetworkManager<N>`: the top-level
  `#[must_use]` future owning `Swarm`, the `NetworkHandle` command channel, and
  bounded channels out to the transactions-manager and eth-request-handler tasks.
- `crates/net/network/src/swarm.rs:51` — `Swarm<N>` = `{ incoming: ConnectionListener,
  sessions: SessionManager, state: NetworkState }`; its `Stream::poll_next` (`:315`)
  is the core priority loop; `SwarmEvent` (`:357`) is the internal event ADT
  (`SessionEstablished`, `ValidMessage`, `BadMessage`, `ProtocolBreach`, …).
- `crates/net/network/src/peers.rs:50` — `PeersManager`: reputation
  (`ReputationChangeWeights`, `DEFAULT_REPUTATION`), inbound/outbound slot limits,
  trusted-peer handling, and backoff/ban (`banlist/`).
- `crates/net/discv4/src/lib.rs` — `Discv4` (channel frontend) / `Discv4Service`
  (drives the UDP socket, owns the kademlia table, emits `DiscoveryUpdate`).
- `crates/net/discv5/src/lib.rs:1` — transparent wrapper around `discv5::Discv5`
  (sigp crate) rather than a from-scratch reimplementation.

## Design decisions & rationale

- **Type-safe, version-gated wire codec (correctness lens).** The wire protocol is a
  Rust ADT (`EthMessage`), and decoding is always parameterised by the *negotiated*
  `EthVersion`. Illegal-for-version messages are a typed decode error, not a
  silently-accepted payload; version differences in the *same* message ID are
  distinct types (`Receipts69` vs `Receipts70`). This pushes wire-compatibility
  bugs to compile/decode time instead of runtime.
- **Transport-free codec split.** `eth-wire-types` (pure RLP types) is separated from
  `eth-wire` (framed streams) so the message types can be reused without the tokio
  transport — enabling fuzzing, `arbitrary` round-trip tests, and reuse by tools.
- **Poll-priority scheduling (throughput lens).** `Swarm::poll_next` drains in a
  fixed order: local `NetworkState` work first, then existing peer `sessions`, then
  new incoming connections last (`swarm.rs:318`). Local/existing work is favoured
  over new inbound connections to avoid connection-flood starvation. The manager
  further uses **budgeted draining** (`poll_nested_stream_with_budget`,
  `DEFAULT_BUDGET_TRY_DRAIN_SWARM`) so no single stream monopolises the poll.
- **Bounded channels between subsystems.** eth-request and transaction traffic flow
  over explicitly bounded mpsc channels (`manager.rs:135`) so a flood of cheap-to-
  request/expensive-to-serve messages produces backpressure rather than unbounded
  memory growth. `MAX_MESSAGE_SIZE = 10 MiB` (`message.rs:30`) and a
  `TX_MEMORY_BUDGET_MULTIPLIER` cap (`:39`) bound per-message decode memory.
- **Additive-version capability check.** `supports_eth_at_least` (`capability.rs:253`)
  lets request logic gate on a *minimum* protocol version (e.g. BAL needs eth/71)
  instead of exact-match, matching the spec's additive versioning.
- **Wrap vs. reimplement per protocol.** discv4 is a native reth implementation
  (fine control over the ETC/ETH-shared kademlia table + NAT/IP discovery); discv5
  is a thin wrapper over the mature `sigp/discv5` crate — reuse where a good crate
  exists, own it where reth needs tight integration.

## Notable patterns (the reusable idea)

**Negotiate a version once, then let the type system carry it.** The single most
transferable idea: the negotiated `EthVersion` is threaded through *every* decode
call (`decode_message(version, buf)`), and the codec both (a) rejects messages that
are illegal for that version and (b) resolves the same message ID to different typed
structs per version. Wire-format drift between protocol versions becomes a data
model concern the compiler and decoder enforce, not a scatter of runtime `if
version >= N` checks spread through handlers.

Secondary reusable pattern: **a single top-level future (`NetworkManager`) that owns
a priority poll-loop over sub-streams**, with per-peer work offloaded to spawned
tasks that report back over bounded channels — giving explicit, auditable scheduling
priority and backpressure without a per-connection thread/goroutine.

## Authority note

go-ethereum is the canonical reference for devp2p/RLPx/wire behavior (reth even
pins `MAX_MESSAGE_SIZE` to geth's constant, `message.rs:29`). reth is the
tokio-async, type-safe-wire implementation of that same spec — valuable to fukuii as
a *structural* reference (how to model versioned messages and schedule an async P2P
core), not as an independent consensus/behavior authority. For fukuii's own §3b
cross-ref: **at upstream HEAD reth advertises eth/66–69 by default (`ALL_VERSIONS`,
`LATEST = Eth69`)**; eth/70, eth/71, eth/72 are implemented in the codec (EIP-7975
receipt pagination, EIP-8159 block access lists, EIP-8070 sparse blobpool) and are
negotiable if explicitly configured, but are **not** in the default advertised set.
`snap/2` rides as a satellite of eth. p2p protocol version defaults to `V5`.

## Gotchas / anti-patterns / things they later changed

- **Enum breadth ≠ advertised set.** `EthVersion` contains Eth70/71/72, and casual
  reading of the enum could suggest reth speaks them by default. It does not — only
  `ALL_VERSIONS` (66–69) is advertised. Always read the advertised set from
  `HelloMessageBuilder::build`/`ALL_VERSIONS`, not the enum's max variant.
- **`supports_eth_at_least` is not exact-match.** A peer advertising only eth/71
  returns `true` for `Eth66..=Eth71`. The code carries an explicit doc warning
  (`capability.rs:244`) because using it as an exact-version check is a real
  foot-gun; use the `supports_eth_vXX` booleans for verbatim checks.
- **Silent transaction drops under memory pressure.** `Transactions`/`PooledTransactions`
  decoding stops and *silently drops* remaining txs once the cumulative in-memory
  size exceeds `max_message_size * TX_MEMORY_BUDGET_MULTIPLIER` (`message.rs:32`).
  Correct for DoS resistance, but a caller must not assume a decoded tx list is
  complete.
- **`arbitrary` generator lags the enum.** The `Capability` fuzz generator still
  ranges `66..=71` (`capability.rs:209`) while the enum now reaches 72 — a small
  drift to watch when relying on property tests for full coverage.
- **`Capabilities::supports_eth_v72` helper missing.** The struct tracks `eth_72`
  but exposes `supports_eth_v66..v71` only (no `supports_eth_v72`, `capability.rs:308`),
  an asymmetry left as versions were added incrementally.
- **discv4 vs discv5 authorship differ.** discv4 is reth-native; discv5 delegates to
  `sigp/discv5`. Bugs/behavior for the two discovery protocols must be triaged in
  different codebases.
