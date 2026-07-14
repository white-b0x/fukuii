# besu — rpc-api
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

Scope: `ethereum/api/` module — the JSON-RPC method registry, the Vert.x HTTP/WS/IPC
transports, the separate Engine API service, GraphQL, subscriptions, and plugin-extensible
RPC. besu is the JVM structural mirror to fukuii's `jsonrpc/` (conduit agent's domain);
this file compares besu's **one-class-per-method** registry against fukuii conduit's
**controller-per-namespace** shape.

## Architecture summary

besu splits the RPC surface into three orthogonal concerns, each a distinct set of types:

1. **Method definition** — every RPC method is its own class implementing the
   `JsonRpcMethod` interface (`getName()` + `response(ctx)`). ~192 method classes live under
   `jsonrpc/internal/methods/`. There is no reflection and no annotation scanning (contrast
   go-ethereum, which reflects over exported Go methods on a registered service object).
2. **Method registration** — methods are grouped by namespace. Each namespace has a factory
   class (`EthJsonRpcMethods`, `NetJsonRpcMethods`, `Web3JsonRpcMethods`, …, 15 of them)
   extending `ApiGroupJsonRpcMethods`. `JsonRpcMethodsFactory.methods(...)` instantiates all
   namespace factories, and each factory contributes its methods **only if its namespace is
   in the operator-enabled `rpcApis` set**. The result is a flat `Map<String, JsonRpcMethod>`
   keyed by method name — dispatch is an O(1) map lookup, not a scan.
3. **Transport** — Vert.x-based servers wrap that map: `JsonRpcHttpService` (HTTP + optional
   WS on the same port via `WebSocketService`), `JsonRpcIpcService` (unix domain socket),
   and a **physically separate** `EngineJsonRpcService` for the Engine API (its own port, its
   own JWT auth, its own fork-gated method map). `GraphQLHttpService` is a fourth,
   schema-driven transport that does not use the `JsonRpcMethod` registry at all.

Request execution is a decorator chain of `JsonRpcProcessor` wrappers around a
`JsonRpcExecutor`: `BaseJsonRpcProcessor` → `TimedJsonRpcProcessor` (metrics) →
`TracedJsonRpcProcessor` (OpenTelemetry span) → `AuthenticatedJsonRpcProcessor` (permission
check). The executor validates method availability, builds a `JsonRpcRequestContext`
(request + optional authenticated `User` + liveness supplier), and calls `method.response()`.

## Key types / interfaces / files

- `jsonrpc/internal/methods/JsonRpcMethod.java:23` — the core interface: `getName()`,
  `response(JsonRpcRequestContext)`, plus defaults `isStreaming()` and `getPermissions()`.
  `getPermissions()` derives permission strings from the method name by convention
  (`net_listening` → `["*:*", "net:*", "net:listening"]`) — no per-method wiring needed.
- `jsonrpc/internal/methods/EthGetBalance.java:30` — canonical one-class-per-method example.
  Extends `AbstractBlockParameterOrBlockHashMethod` (template-method base handling the
  `latest`/`pending`/blockhash param), overrides only `resultByBlockHash`. Name comes from
  the `RpcMethod.ETH_GET_BALANCE` enum, not a string literal.
- `jsonrpc/methods/JsonRpcMethods.java:22` — one-method interface `create(enabledRpcApis) →
  Map<String, JsonRpcMethod>`; the namespace-factory contract.
- `jsonrpc/methods/ApiGroupJsonRpcMethods.java:26` — abstract base: `create(apis)` returns the
  namespace's methods **iff `apis.contains(getApiGroup())`**, else empty map. `mapOf(...)`
  collects methods into a name-keyed map. This is where namespace-gating lives.
- `jsonrpc/methods/Web3JsonRpcMethods.java:24` — smallest concrete namespace factory (2
  methods) — the pattern to copy.
- `jsonrpc/methods/JsonRpcMethodsFactory.java:56` — the assembly root: takes ~30 collaborators
  (blockchain queries, txpool, sync, mining coordinator, plugins, …), builds the `List.of(...)`
  of all 15 namespace factories (`jsonrpc/methods/JsonRpcMethodsFactory.java:95`), and folds
  their outputs into the enabled map (`:168`). Also always injects the `rpc_modules` method.
- `jsonrpc/RpcApis.java:22` — the namespace enum (`ETH, DEBUG, MINER, NET, PERM, WEB3, ADMIN,
  TXPOOL, TRACE, PLUGINS, IBFT, ENGINE, QBFT, TESTING`); `DEFAULT_RPC_APIS = [ETH, NET, WEB3]`.
- `jsonrpc/RpcMethod.java` — enum of all valid method-name strings; used by
  `validateMethodAvailability` to distinguish METHOD_NOT_FOUND (unknown) vs
  METHOD_NOT_ENABLED (known but namespace off).
- `jsonrpc/execution/JsonRpcExecutor.java:48` — dispatch core. `prepareExecution`
  (`:125`) parses, handles notifications, opens a trace span, validates availability
  (`:166`), then a **plain `rpcMethods.get(name)` map lookup** (`:153`) and delegates to the
  processor chain. Also supports streaming methods (`isStreaming()` → `streamProcess`).
- `jsonrpc/execution/{Base,Timed,Traced,Authenticated}JsonRpcProcessor.java` — the decorator
  chain (metrics, tracing, permission enforcement) wrapping every call.
- `jsonrpc/JsonRpcHttpService.java:90` — Vert.x `HttpServer` + `Router`; body/CORS handlers,
  TLS (`PfxOptions`, `ClientAuth`), health endpoints, and dispatch to the executor. Hosts the
  WebSocket upgrade and (optionally) the GraphQL route on the same port.
- `jsonrpc/EngineJsonRpcService.java:102` — the **separate** Engine API server. Own port,
  own `Map<String, JsonRpcMethod>` (only the ENGINE namespace), own JWT `AuthenticationService`
  (`:147`, `:340` reads the `Authorization: Bearer <jwt>` header), own HTTP+WS handlers,
  own health/QoS timer. Constructed independently of the general RPC service.
- `jsonrpc/methods/ExecutionEngineJsonRpcMethods.java` — the ENGINE namespace factory. Builds
  the base engine methods (V1/V2/V3 newPayload/forkchoiceUpdated/getPayload) then
  **fork-gates the versioned additions by protocol milestone**:
  `protocolSchedule.milestoneFor(CANCUN/PRAGUE/OSAKA/AMSTERDAM).isPresent()` adds V3/V4/V5/V6
  payload + blob methods. 36 classes under `internal/methods/engine/`.
- `jsonrpc/websocket/WebSocketService.java:56` / `WebSocketMessageHandler.java` — WS transport.
- `jsonrpc/websocket/subscription/SubscriptionManager.java:47` — a Vert.x `AbstractVerticle`
  holding a `ConcurrentHashMap<Long, Subscription>` and an `AtomicLong` id counter; drives
  `eth_subscribe`/`eth_unsubscribe` (newHeads, logs, newPendingTransactions, syncing) and
  pushes notifications over the Vert.x event bus (`EVENTBUS_REMOVE_SUBSCRIPTIONS_ADDRESS`).
- `jsonrpc/ipc/JsonRpcIpcService.java` — unix-domain-socket transport reusing the same executor.
- `graphql/GraphQLProvider.java:58` — builds a `graphql-java` `GraphQL` from an **SDL schema
  file** (`Resources.getResource("schema.graphqls")`) + `RuntimeWiring` bound to
  `GraphQLDataFetchers`. Entirely separate from the JSON-RPC method registry.
- `jsonrpc/methods/PluginsJsonRpcMethods.java:24` — the `PLUGINS` namespace factory.
- `jsonrpc/internal/methods/PluginJsonRpcMethod.java:30` — adapter wrapping a plugin-supplied
  `Function<PluginRpcRequest, ?>` as a `JsonRpcMethod`; catches `PluginRpcEndpointException`
  and maps it to a JSON-RPC error.
- `plugin-api/.../services/RpcEndpointService.java:56` — the public plugin SPI:
  `registerRPCEndpoint(namespace, functionName, function)` — method name is
  `namespace + "_" + functionName`. `app/.../services/RpcEndpointServiceImpl.java` implements it
  and merges plugin methods into the enabled map at startup.

## Design decisions & rationale

- **One class per method, explicit registration.** Each method is a small, individually
  testable, individually permissioned class. No reflection/annotation magic → the full method
  set is statically discoverable by reading the namespace factories, and a method's
  dependencies are explicit constructor args. Cost: verbosity (192 classes) and a long-arg
  `JsonRpcMethodsFactory` wiring all collaborators.
- **Namespace = enabling unit.** `ApiGroupJsonRpcMethods.create(apis)` short-circuits to an
  empty map when the namespace isn't in `rpcApis`. Operators enable coarse namespaces
  (`ETH,NET,WEB3`); the disabled-vs-unknown distinction is preserved as
  METHOD_NOT_ENABLED vs METHOD_NOT_FOUND so clients get an accurate error.
- **Engine API as a physically separate service, not a namespace on the main port.** The CL↔EL
  channel has different security (mandatory JWT), different lifecycle (QoS timer detecting a
  silent CL), and version methods gated on fork activation. Isolating it in
  `EngineJsonRpcService` keeps validator-facing concerns off the general RPC port, which can be
  exposed with a different (or no) auth posture.
- **Fork-gated method versions.** Engine method *versions* register conditionally on
  `protocolSchedule.milestoneFor(fork)`, so a node only advertises `engine_getPayloadV5` once
  Osaka is scheduled. Method availability tracks the fork schedule instead of being
  unconditionally present.
- **Decorator processor chain** keeps cross-cutting concerns (auth, metrics, tracing) out of
  the individual method classes — each `JsonRpcMethod.response()` stays pure business logic.
- **Convention-derived permissions.** `getPermissions()` computes the permission set from the
  method name; wildcard `*:*`, namespace `eth:*`, and exact `eth:getBalance` fall out
  automatically, so per-method auth config is unnecessary in the common case.

## Notable patterns (the reusable idea)

**The single most transferable pattern for fukuii's conduit: `ApiGroupJsonRpcMethods` —
namespace-gated registration returning a name-keyed method map, folded into one flat dispatch
`Map<String, JsonRpcMethod>`.** fukuii's conduit already groups methods by namespace
(controller-per-namespace); besu's variant differs in two respects worth borrowing:

1. **Enabling is a property of the registry, not the transport.** A namespace contributes
   zero methods when disabled (`create(apis)` returns empty), so the transport layer never
   needs to know which namespaces are on — it just holds the map. The METHOD_NOT_FOUND vs
   METHOD_NOT_ENABLED distinction is preserved at the executor.
2. **Method granularity is a free choice under that registry.** besu goes finer than fukuii
   — one class per method — which buys per-method unit tests, per-method permissions, and
   template-method base classes (`AbstractBlockParameterOrBlockHashMethod`) that factor out the
   repetitive block-parameter parsing. fukuii's controller-per-namespace is the coarser
   variant; the registry pattern is identical either way, so fukuii could adopt besu's
   namespace-gating and versioned-registration without abandoning its controller grain.

Secondary reusable ideas: (a) the **decorator processor chain** for auth/metrics/tracing;
(b) **fork-gated method registration** via `milestoneFor(...)`; (c) the **plugin RPC SPI**
(`registerRPCEndpoint(namespace, fn, function)` → `PluginJsonRpcMethod` adapter) that lets
enterprise plugins add whole namespaces without touching core — the enterprise extension seam.

## Authority note

besu = JVM RPC **structural** reference (how to organize a method registry, transports, and
the Engine service in a JVM/actor-adjacent codebase). go-ethereum remains the canonical
authority for **behavioral** correctness of `eth_*` / Engine API semantics (return shapes,
error codes, edge cases). Where besu and geth disagree on a method's observable behavior,
geth wins; besu wins on how to lay the code out.

## Gotchas / anti-patterns / things they later changed

- **Long-arg factory.** `JsonRpcMethodsFactory.methods(...)` takes ~30 positional parameters —
  a wiring bottleneck that grows with every new collaborator. The one-class-per-method model
  pushes all dependency injection up into this single seam. fukuii should expect the same
  pressure if it goes finer-grained, and may prefer a context/builder object over a mega-arg
  method.
- **Method-count sprawl.** 192 method classes + 36 engine classes is a lot of files;
  discoverability depends on the namespace factories being the index. Without them the flat
  method map is opaque.
- **Engine service duplicates transport plumbing.** `EngineJsonRpcService` re-implements much of
  `JsonRpcHttpService`'s Vert.x router/handler/WS setup rather than sharing it — the price of
  physical separation. Watch for drift between the two when changing transport behavior.
- **SubscriptionManager coupling to Vert.x event bus.** Subscriptions are a Vert.x Verticle
  broadcasting over a named event-bus address; it is not transport-agnostic. Porting the
  subscription model to fukuii's Pekko world means replacing the event-bus mechanism, not just
  the data structures (the `ConcurrentHashMap<Long, Subscription>` + `AtomicLong` id scheme
  ports directly; the delivery bus does not).
- **GraphQL is a parallel stack.** GraphQL does not reuse the JSON-RPC registry at all — it's a
  separate SDL schema + `graphql-java` `DataFetchers`. Adding a field to one surface does not
  add it to the other; the two RPC surfaces must be kept in sync by hand.
- **Deprecated constructor still present.** `SubscriptionManager` keeps a 2-arg constructor
  (`MetricsSystem, Blockchain`) that ignores the `Blockchain` arg and delegates to the 1-arg
  form — dead parameter left for call-site compatibility.
