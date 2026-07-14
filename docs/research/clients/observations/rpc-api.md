# Observations — rpc-api
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/rpc-api.md._

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | Authoritative |
|---|---|---|---|---|---|---|---|
| Method-registration model | Reflection over exported Go methods on a namespace struct (`RegisterName` walks methods; `<ns>_<lowerFirst(Method)>`) | Inherited from geth unchanged (reflection) | One-class-per-method (`JsonRpcMethod` iface) grouped by namespace factory (`ApiGroupJsonRpcMethods`) | Reflection-style geth-derived `rpc.API` list; namespaces built in `APIList(...)` | Attribute-metadata: `[RpcModule]` interface + `[JsonRpcMethod]` per method, reflected once into a frozen dict | Proc-macro trait codegen (jsonrpsee `#[rpc]`) + trait-composition (`FullEthApi` blanket impl) | besu (registry shape); nethermind (attribute-metadata); reth (proc-macro) |
| Transport abstraction (codec seam serving http/ws/ipc) | One `handler` + `ServerCodec` drives HTTP/WS/IPC/inproc/stdio; HTTP single-shot exception | Same as geth | Vert.x servers wrap the flat method map; HTTP+WS same port, separate IPC service | Same handler code, transport is a wiring choice; deployment topology chosen at one function | Transport-agnostic `JsonRpcProcessor` pump over HTTP(ASP.NET)/WS/IPC/duplex | Namespaces → `HashMap<RethRpcModule, Methods>` sliced per transport via `TransportRpcModuleConfig`; IPC is a forked jsonrpsee server | go-ethereum (codec seam) |
| Engine API isolation (separate service + JWT) | Not special-cased in dispatcher: `Authenticated: true` flag routes to a separate JWT listener at node layer | Present (`eth/catalyst`) but inert on PoW ETC — dev-tooling only, no live CL | Physically separate `EngineJsonRpcService`: own port, own map, own JWT, own QoS timer | Fourth separate JWT server exposing only `eth`+`engine`; stripped from public whitelist | Plugin-contributed `IEngineRpcModule`; JWT applied by transport to the Engine port only | Separate `EngineApi<EngineTypes>` trait; versioned method names; JWT at transport | go-ethereum (auth-flag-on-registration); all agree on separate JWT service |
| Namespace enabling / gating | Module allowlist at `RegisterApis` (`--http.api`; empty ⇒ all) | Same as geth | Registry-level: `create(apis)` returns empty map when namespace off → METHOD_NOT_ENABLED vs METHOD_NOT_FOUND | Per-deployment namespace whitelist (`cfg.API`); opt-in per node role | Two-tier: method `RpcEndpoint` availability AND module-enabled-on-URL; `DefaultModules` vs `DefaultEngineModules` | `RethRpcModule` enum selection + per-transport gating | besu (enabling-as-registry-property) |
| Plugin-contributed namespaces | — (no plugin RPC SPI) | — | `RpcEndpointService.registerRPCEndpoint(ns, fn, function)` → `PluginJsonRpcMethod` adapter; whole `PLUGINS` namespace | — (extension namespaces `erigon_`/`ots_` in-tree, not plugin SPI) | Any plugin uses the same `RegisterSingletonJsonRpcModule` DI extension (Engine API itself is a plugin) | `Other(String)` enum escape hatch for out-of-tree namespaces | besu + nethermind (enterprise plugin SPI) |
| Subscriptions | Server-side `Notifier`/`Subscription`; buffer-until-`activate()` so client gets sub-ID first; WS/IPC only, HTTP can't | Same as geth | `SubscriptionManager` Vert.x Verticle: `ConcurrentHashMap<Long,Subscription>` + `AtomicLong`; pushes over event bus | Filters/subscriptions via the private-API/state-change stream | `SubscribeRpcModule` + `SubscriptionManager`, itself a bounded module (2 instances) | jsonrpsee-driven subscriptions | go-ethereum (activation-ordering) |
| Separable-process (remote-KV) | — (in-process) | — | — | **RPCDaemon**: standalone binary, reads chain/state over remote-KV gRPC (cursor-over-gRPC `Tx` stream); same handler code embedded or remote | — | — | erigon (RPCDaemon + remote-KV) |

## Approach catalog (use-case-aware)

| Approach | Clients using it | Good for (use-case / node-role) | Verdict | Why |
|---|---|---|---|---|
| Reflection registration (namespace struct → methods) | go-ethereum, core-geth | Large fast-churning API surface; minimal per-method boilerplate | OPTIONAL(geth-lineage) | Zero registration boilerplate, but no compile-time method-name check and **silent collision when multiple structs share one namespace** (`eth`); a bad signature is silently skipped |
| One-class-per-method registry (namespace-gated map) | besu (`ApiGroupJsonRpcMethods`) | Per-method tests, per-method permissions, accurate enabled-vs-unknown errors | **DEFAULT** | Enabling is a property of the registry (empty map = METHOD_NOT_ENABLED vs NOT_FOUND); flat name-keyed `Map` = O(1) dispatch; method granularity is a free choice under the same pattern |
| Attribute-metadata modules | nethermind (`[RpcModule]`+`[JsonRpcMethod]`) | Per-transport availability + per-module bounded concurrency + generated docs from one source | OPTIONAL(DI-heavy runtime) | Interface is the single truth for dispatch AND docs; per-module pooling isolates slow `debug_*` from `eth_*`; sits between geth's dynamism and besu's verbosity |
| Proc-macro trait codegen | reth (jsonrpsee `#[rpc]`) | Compile-time type safety; multi-network response-type reuse via generics | OPTIONAL(Rust/codegen stack) | Wrong return type / missing method = compile error, not runtime 404; capability-trait composition lets a new network implement only `Load*` DB traits — but bound-errors are opaque |
| Engine-API-as-separate-JWT-service | all (geth flag; besu/erigon separate server; nethermind/reth transport-JWT) | Validator / PoS node (ETH/Sepolia) | **DEFAULT** (PoS) / not exposed (PoW) | CL↔EL channel has distinct security (mandatory JWT), lifecycle (QoS timer), and fork-gated method versions; keep it off the public data-serving port. Inert/omittable on PoW ETC |
| Plugin-contributed RPC namespaces | besu, nethermind | Enterprise / consortium extension without forking core | OPTIONAL(enterprise) | A plugin owns a namespace end-to-end (interface, methods, DI) with zero core-dispatcher knowledge; the enterprise extension seam |
| RPCDaemon separable-process + remote-KV | erigon | Archival / read-scaling; RPC hosts distinct from execution hosts | OPTIONAL(archival/RPC role) | All RPC data access expressed as one narrow serializable DB-read seam (KV cursors + state-change subscription) → RPC server becomes relocatable (in-process / co-located / remote) with the same handler code; read load can never stall block execution |

## Best-practice synthesis

**DEFAULT.** A **namespace-gated registration returning a name-keyed dispatch map** (besu's
`ApiGroupJsonRpcMethods`): each namespace factory contributes its methods only if the
namespace is operator-enabled, folded into one flat `Map<name, method>` for O(1) dispatch.
The load-bearing property is that **enabling is a property of the registry, not the
transport** — a disabled namespace contributes zero methods, and the executor preserves the
METHOD_NOT_ENABLED (known, namespace off) vs METHOD_NOT_FOUND (unknown) distinction so
clients get an accurate error. fukuii's conduit **controller-per-namespace is the coarser
variant of exactly this idea** — same registry pattern, just coarser method granularity;
adopting besu's namespace-gating + versioned registration does not require abandoning the
controller grain. Pair it with **Engine API as a separate JWT-gated service** — not
special-cased in the dispatcher (geth's authenticated-flag-on-registration is the cleanest
statement: auth is a routing/deployment concern, not dispatcher logic), physically separate
where the stack allows (besu/erigon), and off the public port. On PoW ETC the Engine API is
inert and omittable entirely (core-geth: present only as dev-tooling).

Avoid the geth reflection gotcha in the coarse-grained form: **multiple services merged into
one namespace silently lose colliding method names**. conduit's controller-per-namespace
already sidesteps this by aggregating each namespace in one place.

**OPTIONAL menu (by node role):**
- **Archival / RPC role** → separable RPCDaemon over a **remote-KV read seam** (erigon):
  express all RPC data access as one narrow serializable DB-read interface so the RPC tier
  becomes a relocatable, independently-scalable read replica. Ties to the product-family
  decomposition and DRPC-GATEWAY-01.
- **Enterprise / consortium** → plugin-contributed namespaces (besu `RpcEndpointService`,
  nethermind DI module) so extensions ship without forking core.
- **Per-module concurrency isolation** (nethermind bounded pools) if a slow `debug_*`/`trace_*`
  surface must not starve `eth_*` throughput.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

- fukuii's `jsonrpc/` is **conduit**'s domain; the registry modernization tracks **SR-03
  (BLOCKED-ON-BATCH-4)**. Seeds below, not verdicts.
- conduit is already **controller-per-namespace**, which sidesteps geth's namespace-collision
  gotcha (multiple structs merged into one namespace, silent method loss) — it is the coarser
  form of the besu registry DEFAULT, so the gap is namespace-gating-as-registry-property +
  versioned/fork-gated registration, not a rewrite.
- Open finding: **FEEHISTORY-PERCENTILE-VALIDATION-01**.
- The RPCDaemon / remote-KV separable-process pattern is the **dRPC-Provider seam
  (DRPC-GATEWAY-01)** — the read seam as a process boundary is the archival/read-scaling and
  enterprise-deployment story; it lands on fukuii as a DB-read-seam definition in `jsonrpc/`,
  not an immediate split.
- Engine API applies only to fukuii's PoS family (ETH/Sepolia); ETC/Mordor never exposes it —
  keep it a separate JWT service, not a dispatcher special-case.
