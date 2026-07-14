# nethermind — networking-p2p
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind's P2P stack is a layered DotNetty (managed Netty port) pipeline split across
several assemblies:

- **`Nethermind.Network`** — the RLPx transport (`Rlpx/`), session lifecycle
  (`Session.cs`), the capability/handshake layer (`P2PProtocolHandler`), the
  `ProtocolsManager` that instantiates subprotocol handlers, and peer management
  (`PeerManager` / `PeerPool`).
- **`Nethermind.Network` / `P2P/Subprotocols/`** — the wire subprotocols themselves:
  one `EthNNProtocolHandler` class per ETH version (V62…V71) plus `Snap`.
- **`Nethermind.Network.Discovery`** — a `CompositeDiscoveryApp` running discv4 and/or
  discv5 side-by-side over a shared Kademlia table.
- **`Nethermind.Network.Enr`** — Ethereum Node Records (EIP-778) signing/decoding.
- **`Nethermind.Network.Stats`** — per-node reputation scoring that drives peer selection.

Wire handlers are wired up through Autofac DI (`Nethermind.Init/Modules/NetworkModule.cs`)
as **protocol-handler factories** (`IProtocolHandlerFactory`), not a hand-written version
switch. On an inbound connection the `ProtocolsManager` walks the registered factories and
calls `TryCreate(session, version, out handler)` (`ProtocolsManager.cs:145-154`); the first
factory that accepts the negotiated version builds the handler, else it throws
`NotSupportedException`. Which versions the node *advertises* (as opposed to which it can
*speak* if a peer offers them) is decided separately by a chain of
`IP2PCapabilityResolver`s that contribute to the Hello capability set.

**ETH wire versions built/registered at this HEAD: eth/66, eth/67, eth/68, eth/69,
eth/70, eth/71** (`NetworkModule.cs:154-159`), plus **snap/1**. Handlers for the older
eth/62–eth/65 classes still exist in-tree as the inheritance base chain but are not
registered as factories. **Advertised by default: eth/68 only** (pre-merge / PoW); the
Merge plugin adds **eth/69, eth/70, eth/71** once the node is post-merge, and snap/1 is
advertised only while state-sync still needs it (see Design decisions).

## Key types / interfaces / files

- `src/Nethermind/Nethermind.Network/Rlpx/RlpxHost.cs:30` — RLPx host; builds the DotNetty
  `ServerBootstrap` (`:131`), binds the listen socket, and installs the per-connection
  channel pipeline (handshake → frame codec → snappy → packet split → session).
- `src/Nethermind/Nethermind.Network/Rlpx/` — the RLPx framing primitives: `Frame.cs`,
  `FrameCipher.cs`, `FrameMacProcessor.cs`, `NettyHandshakeHandler.cs` (ECIES auth),
  `ZeroFrameEncoder/Decoder`, `ZeroSnappyEncoder`/`SnappyDecoder`.
- `src/Nethermind/Nethermind.Network/P2P/Session.cs:504` — session state machine; tracks
  agreed capabilities (`HasAgreedCapability`), disconnect reasons.
- `src/Nethermind/Nethermind.Network/ProtocolsManager.cs` — central registrar.
  `InitProtocol` (`:128`) picks a factory by version; `OnP2PProtocolInitialized` (`:236`)
  enables Snappy compression once p2p version ≥ 5 (`:240`); `ResolveCapabilities`
  logging around `:390`; `GetHighestProtocolVersion` (`:346`).
- `src/Nethermind/Nethermind.Network/P2P/ProtocolHandlers/P2PProtocolHandler.cs` — the p2p
  (Hello/Disconnect/Ping/Pong) base handler; `ProtocolHandlerBase.cs`,
  `SyncPeerProtocolHandlerBase.cs` (shared eth/snap sync-peer plumbing),
  `ZeroProtocolHandlerBase.cs` (zero-allocation message dispatch).
- `src/Nethermind/Nethermind.Network/IP2PCapabilityResolver.cs` — interface: each resolver
  adds/removes capabilities from the advertised set; raises `Changed` to invalidate the
  cached array (advertised set is computed once, not per session).
- `src/Nethermind/Nethermind.Network/DefaultP2PCapabilityResolver.cs:22` — registered
  first; adds **eth/68** as the universal default.
- `src/Nethermind/Nethermind.Merge.Plugin/MergeP2PCapabilityResolver.cs:37-39` — adds
  **eth/69, eth/70, eth/71** but only once `TransitionFinished || HasEverReachedTerminalBlock()`.
- `src/Nethermind/Nethermind.Network/SnapP2PCapabilityResolver.cs` — adds **snap/1**;
  recomputed per session so a session opened after state sync completes no longer
  advertises snap (replaced the old start/stop `SnapCapabilitySwitcher`).
- `src/Nethermind/Nethermind.Xdc/XdcP2PCapabilityResolver.cs:23-25` — third-party network
  (XDC) example: advertises eth/62, eth/63, eth/100 — shows the resolver chain is the
  multi-network seam.
- `src/Nethermind/Nethermind.Network/P2P/Subprotocols/Eth/VNN/EthNNProtocolHandler.cs` —
  per-version handlers. Inheritance chain (each version extends the prior):
  `Eth62 ← Eth63 ← Eth64 ← Eth65 ← Eth66 ← Eth67 ← Eth68 ← Eth69 ← Eth70 ← Eth71`.
  Newer versions override only the deltas (e.g. V68 changes
  `NewPooledTransactionHashes` encoding; V69 adds `BlockRangeUpdate` + a new receipts
  layout; V70/V71 add receipt/block-access-list message variants).
- `src/Nethermind/Nethermind.Network/P2P/Subprotocols/Snap/SnapProtocolHandler.cs` +
  `SnapMessageCode.cs` — snap/1 server+client (GetAccountRange, GetStorageRanges,
  GetByteCodes, GetTrieNodes).
- `src/Nethermind/Nethermind.Network/PeerManager.cs:36` — connection scheduler.
  `MaxActivePeers` (`:100`) = config + static peers; `AvailableActivePeersCount` (`:103`);
  candidate selection sorts by `CurrentReputation` (`:704`, `:767`); inbound over-limit
  guard at `:901` (`MaxActivePeerMargin`); `CanConnectToPeer` (`:940`).
- `src/Nethermind/Nethermind.Network/PeerPool.cs`, `Peer.cs`, `PeerComparer.cs` — the
  candidate/active peer collections and ordering.
- `src/Nethermind/Nethermind.Network.Stats/NodeStatsLight.cs` — reputation math
  (comment `:18`: "based on EthereumJ impl"). `CalculateCurrentReputation` (`:325`) =
  `persistedReputation/2 + sessionReputation`; `CalculateSessionReputation` (`:330`)
  sums ping symmetry bonus (`:334`) and per-disconnect-reason reputation deltas
  (local `:338`, remote `:350`) plus event-type deltas (`:360`).
- `src/Nethermind/Nethermind.Network.Discovery/CompositeDiscoveryApp.cs:23` — runs discv4
  and/or discv5 selected by the `DiscoveryVersion` flags enum
  (`DiscoveryVersion.cs`: `V4=1, V5=2, All=V4|V5`).
- `src/Nethermind/Nethermind.Network.Discovery/Discv4/DiscoveryApp.cs` — discv4
  (Ping/Pong/FindNode/Neighbors) over `NettyDiscoveryHandler`; `Discv4/Kademlia/`.
- `src/Nethermind/Nethermind.Network.Discovery/Discv5/DiscoveryV5App.cs` — discv5 with its
  own `MessageCodec.cs`, `Packets/`, `Kademlia/` (native impl at this HEAD — no external
  discv5 package referenced in `Nethermind.Network.Discovery.csproj`).
- `src/Nethermind/Nethermind.Network.Enr/NodeRecord.cs`, `NodeRecordSigner.cs`,
  `EthEntry.cs`, `ForkId.cs` — ENR (EIP-778) with the `eth` ForkId entry used for
  fork-aware peer filtering.

## Design decisions & rationale

- **Factory-per-version, not a version switch.** Each ETH version is a DI-registered
  `IProtocolHandlerFactory`; `ProtocolsManager` just asks each factory to `TryCreate` for
  the negotiated version. Adding a wire version = add one handler class + register it +
  (optionally) advertise it via a resolver. No central switch to edit.
- **Advertise-set decoupled from speak-set via resolver chain.** The set of capabilities
  put in the Hello message is assembled by an ordered list of `IP2PCapabilityResolver`s
  (`AddFirst` default, plugins/`AddLast` snap). This is the explicit multi-network /
  fork-transition seam: the base client advertises eth/68, the Merge plugin *conditionally*
  layers eth/69–71 on top once post-merge, snap is contributed only while state sync needs
  it, and a whole different network (XDC) swaps in its own resolver. The node can still
  *accept* eth/66–71 from a peer because all those factories are registered even if not
  advertised.
- **Advertised set is cached, not recomputed per session.** `IP2PCapabilityResolver`
  contract: resolvers whose contribution depends on mutable runtime state (post-merge flag,
  sync mode) must raise `Changed` to invalidate the cached array. Keeps capability
  resolution off the per-connection hot path.
- **Reputation-ordered dialing.** `PeerManager` keeps a candidate pool sized `MaxActivePeers*2`
  and dials best-reputation-first; reputation blends a decayed persisted score with a live
  session score derived from ping symmetry and disconnect-reason weights. Static/trusted
  peers bypass the active-peer cap (`MaxActivePeers` includes `StaticPeerCount`).
- **Snappy gated on p2p ≥ 5.** Compression is turned on in the pipeline only after the
  Hello exchange confirms p2p version ≥ 5 (`ProtocolsManager.cs:240`), matching devp2p.
- **discv4 + discv5 coexist.** `CompositeDiscoveryApp` runs both under one `IDiscoveryApp`,
  feeding a `CompositeNodeSource`; operators pick via the `DiscoveryVersion` flag. ENR
  ForkId lets peers be filtered by fork compatibility before a full handshake.

## Notable patterns (the reusable idea)

**The `IP2PCapabilityResolver` chain is the single most transferable idea for fukuii.** It
cleanly separates three concerns that a naive client conflates: (1) which wire-protocol
handlers exist/can be spoken, (2) which are *advertised* right now, and (3) *why* — default
baseline vs. fork-transition-gated vs. sync-state-gated vs. per-network override. Each
concern is an independent, testable, composable unit; enabling a new ETH version post-fork,
or supporting a second network with a different version set, is an additive DI registration
rather than an edit to shared negotiation code. A secondary reusable pattern is the
**delta-inheritance handler chain** (`EthNN extends EthNN-1`, overriding only changed
messages), which keeps each version's diff small and auditable.

## Authority note

go-ethereum is the canonical reference for devp2p / RLPx / ETH+snap wire behavior (frame
format, ECIES handshake, message codes, ForkId, discv4/v5, ENR). Nethermind is a
**C# re-implementation** — its value here is the *structural* pattern (ProtocolHandler-per-
version behind a factory+resolver chain, DotNetty transport), not byte-level authority.
Cross-check any on-the-wire encoding against go-ethereum, not against this doc.

## Gotchas / anti-patterns / things they later changed

- **eth/68 is the only unconditional advertisement.** Reading `DefaultP2PCapabilityResolver`
  in isolation is misleading — eth/69/70/71 come from `MergeP2PCapabilityResolver` and are
  gated on `TransitionFinished || HasEverReachedTerminalBlock()`. The header comment on that
  resolver documents a subtlety: `TransitionFinished` alone flips true too late (inside
  `PoSSwitcher.ForkchoiceUpdated`, which raises no event), so it also checks
  `HasEverReachedTerminalBlock()` to rebuild the cache when the terminal block fires. A PoW
  network (like fukuii's ETC) that never merges would, on this code path, never advertise
  eth/69+ — the version story is inherently merge-coupled here.
- **"Registered" ≠ "advertised".** All of eth/66–71 are registered factories, so the node
  will *speak* them if a peer's Hello offers them, even when the resolver chain only
  advertised eth/68. Don't infer the supported-version set from the Hello capabilities alone.
- **Snap advertisement is now per-session, not a start/stop switch.** They explicitly
  replaced the former `SnapCapabilitySwitcher` (add-on-start / remove-when-`SyncMode.Full`)
  with `SnapP2PCapabilityResolver` recomputed per session — a session opened after sync
  completes simply omits snap. Older nethermind docs describing the switcher are stale.
- **discv5 is now a native implementation.** At this HEAD the discv5 app ships its own
  `MessageCodec`/`Packets`/`Kademlia` with no external discv5 library package reference —
  earlier nethermind wrapped a third-party discv5 (Lantern). Treat discv5 as first-party
  code now.
- **eth/62–eth/65 handler classes still exist but are not registered** — they survive only
  as the base of the inheritance chain. Their presence in the tree does not mean the node
  offers those versions.
