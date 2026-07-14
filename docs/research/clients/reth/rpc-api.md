# reth — rpc-api
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth's RPC layer is split across ~11 crates under `crates/rpc/` and is built on
**jsonrpsee**, whose `#[rpc]` proc-macro generates the server (and, feature-gated, client)
trait for each namespace *at compile time* from a hand-written trait definition. There are
two clean seams worth studying:

1. **Interface vs. implementation split.** `rpc-api/` (plus `rpc-eth-api/core.rs`) hold
   only `#[rpc(...)]`-annotated trait *definitions* — the wire contract. `rpc/` holds the
   concrete implementations. The proc-macro turns each trait into an `…ApiServer` trait; a
   type that implements that trait gets a generated `.into_rpc()` producing a runtime
   `jsonrpsee::RpcModule`.

2. **Trait-composed `eth_` API.** Instead of one monolithic Eth implementation, the `eth_`
   namespace is decomposed into a lattice of small capability traits (`rpc-eth-api/helpers/`):
   `Load*` traits do raw DB reads, `Eth*` traits compose them into method logic, each with
   *default* method bodies. `FullEthApi` is a marker trait that is the *conjunction* of all of
   them, blanket-implemented for any type satisfying every bound. The generated `EthApiServer`
   is likewise blanket-implemented for anything that is `FullEthApi`. So the concrete server is
   assembled purely by satisfying trait bounds — no method is registered by hand.

The RPC server itself is assembled by `RpcModuleBuilder` (`rpc-builder/`): namespaces are
registered into a `HashMap<RethRpcModule, Methods>`, then sliced per transport (HTTP / WS /
IPC) according to a `TransportRpcModuleConfig`. There is **no GraphQL** endpoint in reth
(contrast go-ethereum/besu).

## Key types / interfaces / files

- `crates/rpc/rpc-eth-api/src/core.rs:56-65` — `#[cfg_attr(..., rpc(server, namespace = "eth"))]`
  on `trait EthApi<TxReq, T, B, R, H, RawTx>`; the proc-macro generates `EthApiServer` /
  `EthApiClient`. The trait is generic over six `RpcObject` type params (tx-request, tx, block,
  receipt, header, raw-tx) so the *response types* vary per network.
- `crates/rpc/rpc-eth-api/src/core.rs:29-53` — `FullEthApiServer`: the "unified" bound =
  `EthApiServer<…> + FullEthApi + Clone`, plus its blanket `impl<T> … for T where …`. This is
  the single trait a server type must satisfy to be handed to the builder.
- `crates/rpc/rpc-eth-api/src/helpers/mod.rs:58-84` — `trait FullEthApi: FullEthApiTypes +
  EthApiSpec + EthTransactions + EthBlocks + EthState + EthCall + EthFees + Trace + LoadReceipt
  + GetBlockAccessList {}` with a blanket impl. The composition made explicit.
- `crates/rpc/rpc-eth-api/src/helpers/mod.rs:17-43` — the module list: `block`, `call`, `fee`,
  `state`, `transaction`, `receipt`, `pending_block`, `trace`, `spec`, `signer`,
  `blocking_task` — one file per capability.
- `crates/rpc/rpc-eth-api/src/helpers/block.rs:31-48` — example capability trait `EthBlocks:
  LoadBlock<…>` with a **default** async method body (`rpc_block_header`). Shows Load-trait
  (DB) vs Eth-trait (logic) layering described in the module docs (`helpers/mod.rs:1-15`).
- `crates/rpc/rpc/src/eth/core.rs:69-73` — the concrete `pub struct EthApi<N: RpcNodeCore,
  Rpc: RpcConvert>`, a `#[derive(Deref)]` newtype over `Arc<EthApiInner<N, Rpc>>`. Generic
  over `N` (node components) and `Rpc` (the per-network `RpcConvert` codec) — this is the
  multi-network seam: swap `Rpc` to serve a different network's tx/receipt shapes.
- `crates/rpc/rpc/src/eth/core.rs:135-147` — `impl EthApiTypes for EthApi` binds
  `NetworkTypes = Rpc::Network` and exposes `converter()`; every helper trait routes response
  conversion through this.
- `crates/rpc/rpc-server-types/src/module.rs:296-345` — `#[derive(… VariantNames, Deserialize)]
  #[strum(serialize_all = "kebab-case")] enum RethRpcModule { Admin, Debug, Eth, Net, Trace,
  Txpool, Web3, Rpc, Reth, Ots, Flashbots, Miner, Mev, Testing, #[strum(default)] Other(String) }`.
  The `Other(String)` variant is the extension point for out-of-tree namespaces.
- `crates/rpc/rpc-builder/src/lib.rs:120` — `struct RpcModuleBuilder<N, Provider, Pool, Network,
  EvmConfig, Consensus>`: type-state builder accumulating node components.
- `crates/rpc/rpc-builder/src/lib.rs:388-404` — `fn build<EthApi>(self, module_config, eth,
  engine_events) -> TransportRpcModules` where `EthApi: FullEthApiServer<…>`. Turns components
  + an eth-api into per-transport module sets.
- `crates/rpc/rpc-builder/src/lib.rs:639-760, 949-1039` — `register_admin/web3/eth/debug/
  trace/net/reth/…` each do `self.modules.insert(RethRpcModule::X, xapi.into_rpc().into())`;
  the big `match` in `modules_for_selection` maps every `RethRpcModule` variant to its
  constructed api + `.into_rpc()`.
- `crates/rpc/rpc-api/src/engine.rs:43-45` — `#[cfg_attr(..., rpc(server, namespace = "engine"),
  server_bounds(Engine::PayloadAttributes: DeserializeOwned))] trait EngineApi<Engine:
  EngineTypes>`. Generic over `EngineTypes` (per-network payload/attribute types); the macro's
  `server_bounds`/`client_bounds` inject extra `where` clauses onto the generated traits.
- `crates/rpc/rpc-api/src/engine.rs:48-349` — versioned Engine methods: `newPayloadV1..V5`,
  `forkchoiceUpdatedV1..V4`, `getPayloadV1..V6`, `getPayloadBodiesBy{Hash,Range}V1/V2`,
  `getBlobsV1..V4`, `getClientVersionV1`, `exchangeCapabilities`. Version proliferation is
  handled by distinct method names, not overloads.
- `crates/rpc/rpc-engine-api/src/engine_api.rs` (2371 lines) — the Engine API implementation
  (`newPayload`/`forkchoiceUpdated` handlers, capabilities in `capabilities.rs`).
- `crates/rpc/rpc-builder/src/lib.rs:1086-1180` — `RpcServerConfig` with `with_http` /
  `with_ws` / `with_ipc`, all layered on `jsonrpsee::server::ServerBuilder` /
  `ServerConfigBuilder`.
- `crates/rpc/ipc/src/server/mod.rs:52-53, 752` — `struct IpcServer`, "an adapted jsonrpsee
  Server, but for Ipc connections"; `crates/rpc/ipc/src/stream_codec.rs` — `StreamCodec` frames
  newline/brace-delimited JSON over the unix-socket/named-pipe stream.

## Design decisions & rationale

- **Compile-time trait generation over runtime registration.** Method signatures, param
  decoding, and JSON serialization are generated from the trait by `#[rpc]`. A wrong return
  type or a missing method is a *compile error*, not a runtime 404 — the type-safe end of the
  RPC-registration spectrum.
- **Capability decomposition with default methods.** The `eth_` surface is dozens of methods;
  splitting it into `Load*`/`Eth*` traits with default bodies means a new network implements
  only the handful of `Load*` DB-access methods and inherits all the composed logic for free.
- **Generic over network via `RpcConvert` and `EngineTypes`.** `EthApi<N, Rpc>` and
  `EngineApi<Engine>` are parameterized by per-network conversion/payload types, so L2s and
  alt-networks reuse the entire method-logic layer and only supply codecs/payload shapes.
- **`RethRpcModule` as the namespace enum with an `Other(String)` escape hatch.** Selection,
  per-transport gating, and CLI parsing (strum kebab-case) all key off one enum; custom
  namespaces don't need to touch it.
- **Transport-agnostic `Methods`.** Namespaces are registered once into a
  `HashMap<RethRpcModule, Methods>`, then the same methods are mounted onto HTTP, WS, and IPC
  servers per `TransportRpcModuleConfig` — one registration, three transports.

## Notable patterns (the reusable idea)

The transferable idea is the **two-layer contract/implementation split with blanket-impl
composition**: (a) a hand-written trait is the single source of truth for the wire contract,
and a codegen step derives the transport glue from it; (b) the implementation is *not* one
class but a conjunction of small capability traits with default method bodies, and the
"server" type is whatever satisfies all the bounds — assembled by the type system, registered
by iterating an enum, never wired method-by-method. Adding a network means implementing the
few data-access traits and swapping a conversion type param, not re-listing every method.

## Authority note

go-ethereum is the canonical reference for `eth_*`/Engine API *behavior* (return shapes,
error codes, fork gating). reth is the reference here only for the *registration mechanism*:
jsonrpsee `#[rpc]` compile-time trait generation + the trait-composed `FullEthApi`. Contrast
the spectrum: geth = reflection over method-name conventions (runtime); besu = one class per
JSON-RPC method registered into a map; nethermind = C# attributes + reflection; **reth =
compile-time proc-macro trait generation** (the most type-safe, least dynamic point).

## Gotchas / anti-patterns / things they later changed

- **Bound-error opacity.** Because the server type is "anything satisfying `FullEthApiServer`",
  a single missing `Load*`/`Eth*` impl surfaces as a wall of unsatisfied-trait-bound errors at
  the `build::<EthApi>` call site, far from the real cause — the classic cost of deep
  trait-composition in Rust.
- **`#[rpc]` feature-gating duplication.** Every trait carries paired
  `#[cfg_attr(not(feature="client"), rpc(server, …))]` / `#[cfg_attr(feature="client",
  rpc(server, client, …))]` attributes (e.g. `core.rs:56-57`, `engine.rs:43-44`) so the
  client trait is only generated when the `client` feature is on — easy to get out of sync
  when adding methods.
- **Engine version sprawl handled by naming, not types.** `newPayloadV1..V5` /
  `getPayloadV1..V6` are separate trait methods; there is no single versioned dispatch, so
  each hard-fork bump adds another method + capability string (`capabilities.rs`).
- **No GraphQL.** Unlike go-ethereum and besu, reth ships no GraphQL endpoint; if fukuii's
  conduit needs GraphQL parity, reth is not a template for it.
- **IPC is a forked jsonrpsee server.** reth had to vendor/adapt jsonrpsee's server for IPC
  (`ipc/src/server`, with its own `StreamCodec`) because upstream jsonrpsee targets HTTP/WS —
  a reminder that "one framework for all transports" leaks at the socket layer.
