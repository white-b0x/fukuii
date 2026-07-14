# go-ethereum — rpc-api
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary

geth's RPC stack is built in **two layers with a clean seam between them**:

1. **A transport-agnostic JSON-RPC engine** (`rpc/`) that knows nothing about
   Ethereum. It registers arbitrary Go objects as "services" via reflection, and
   serves them over any byte stream. The same `*rpc.Server` and the same
   per-connection `handler` drive HTTP, WebSocket, IPC (unix socket / Windows
   named pipe), in-process, and stdio transports. Transports differ only in how
   they supply a `ServerCodec` (a read-batch / write-JSON pair) — HTTP is the
   single-shot exception (`serveSingleRequest`, no subscriptions), everything
   else is full-duplex via `ServeCodec`.

2. **The Ethereum method backends** (`internal/ethapi/`, `eth/`, `eth/catalyst/`,
   `graphql/`) that are plain Go structs whose exported methods *become* the
   `eth_*`, `net_*`, `web3_*`, `txpool_*`, `debug_*`, and `engine_*` RPC methods
   by naming convention. They reach chain/state/txpool through a single
   `ethapi.Backend` interface, so full/light/simulated backends are
   interchangeable.

Method dispatch is entirely reflection-driven: `RegisterName(namespace, obj)`
walks the receiver's exported methods, and a method named `GetBalance` on a
service registered under `eth` is callable as `eth_getBalance`. The JSON-RPC
method name is `<namespace>_<lowerFirst(MethodName)>`.

The Engine API (`engine_*`, consensus-layer driver) is *not special-cased in the
RPC engine* — it is just another namespace. What makes it authenticated is a
single `Authenticated: true` flag on its `rpc.API` entry, which routes it onto a
**separate JWT-gated listener** at the `node` layer. This is the key structural
decision: authentication is a deployment/routing concern, not something baked
into the method dispatcher.

## Key types / interfaces / files

- `rpc/server.go:48` — `Server` struct: holds the `serviceRegistry`, an ID
  generator, tracked codecs, and batch/body/ws-read limits. `NewServer` (`:63`)
  auto-registers the `rpc` meta-namespace (`RPCService.Modules`, `:226`).
- `rpc/server.go:108` — `RegisterName(name, receiver)` — the reflection entry
  point; every namespace is registered this way.
- `rpc/server.go:117` — `ServeCodec` — full-duplex serving loop (WS/IPC/inproc):
  one long-lived `handler` per connection until the codec closes.
- `rpc/server.go:165` — `serveSingleRequest` — HTTP path: sets
  `h.allowSubscribe = false`, reads one batch, no persistent connection.
- `rpc/service.go:38` — `serviceRegistry` / `service` / `callback`: the registry
  splits each service's methods into `callbacks` (normal) and `subscriptions`.
- `rpc/service.go:119` — `suitableCallbacks` — iterates `typ.NumMethod()`, keeps
  exported methods that `newCallback` accepts.
- `rpc/service.go:139` — `newCallback` — validates the Go signature: optional
  leading `context.Context`, ≤2 return values, error (if any) must be last.
- `rpc/service.go:236` — `isPubSub` — a method is a subscription iff it is
  `(ctx) (*Subscription, error)`. That signature alone makes it a `*_subscribe`.
- `rpc/service.go:187` — `callback.call` — invokes via `reflect.Call`, with a
  `recover()` that converts a handler panic into an internal error (one bad
  method can't crash the server).
- `rpc/service.go:247` — `formatName` — lowercases the first rune (`GetBalance`
  → `getBalance`).
- `rpc/handler.go:57` — `handler` — one per connection, **not** concurrency-safe
  itself; it launches each call on a background goroutine (`startCallProc`,
  `:438`) tracked by a `WaitGroup`, so slow methods never block the read loop.
- `rpc/handler.go:179` / `:299` — `handleBatch` / `handleMsg` — the two entry
  points. Batch enforces `batchRequestLimit` (item count) and
  `batchResponseMaxSize` (cumulative bytes) with an over-limit error.
- `rpc/handler.go:606` — `handleSubscribe` — installs a `Notifier` in the call
  context; the subscription callback pulls it out via `NotifierFromContext`.
- `rpc/subscription.go:102` — `Notifier` / `:191` `Subscription`: server-side
  pub/sub. Notifications are **buffered until `activate()`** (`:160`) so the
  client always receives the subscription ID before its first event.
- `rpc/types.go:32` — `rpc.API` struct: `{Namespace, Service, Authenticated}` —
  the unit every subsystem hands to the node. `Version`/`Public` are retained
  but dead.
- `rpc/http.go:338` — `Server.ServeHTTP` — the `net/http` adapter;
  `validateRequest` (`:372`) enforces method/content-type/body-limit and answers
  empty GETs `200` for load-balancer health checks (`:340`).
- `rpc/websocket.go:50` — `WebsocketHandler(allowedOrigins)` — upgrades and
  enforces an origin allowlist (`originIsAllowed`, `:129`); 32 MiB default read
  limit.
- `rpc/ipc.go:28` — `ServeListener` — accepts unix-socket/named-pipe conns and
  hands each to `ServeCodec` (full duplex, so IPC supports subscriptions).
- `internal/ethapi/backend.go:42` — `Backend` — the ~60-method interface every
  method backend depends on (headers, state, txpool, logs, gas oracle, chain
  config, engine). The single seam between "the API" and "the node".
- `internal/ethapi/backend.go:108` — `GetAPIs(backend)` — returns the `eth`,
  `txpool`, `debug` namespace bundle. Note **multiple services share the `eth`
  namespace** (EthereumAPI + BlockChainAPI + TransactionAPI + AccountAPI) —
  registration merges their callbacks.
- `eth/catalyst/api.go:50` — `Register(stack, backend)` — registers `engine`
  with `Authenticated: true`. `ConsensusAPI` (`:88`) exposes
  `ForkchoiceUpdatedV1..V4`, `NewPayloadV1..V5`, `GetPayloadV1..V6`,
  version-gated by fork.
- `node/node.go:583` — `getAPIs()` — splits registered APIs into
  `unauthenticated` (everything) vs `all`, driving which listener serves what.
- `node/node.go:150-154` — the node runs **two server pairs**: `http`/`ws` (public
  namespaces) and `httpAuth`/`wsAuth` (JWT-gated, serves `all`, including engine).
- `node/jwt_handler.go:35` — `newJWTHandler` — HS256-only, checks `iat` within
  60 s drift in either direction; wraps the auth listener's handler.
- `node/rpcstack.go:649` — `RegisterApis` — module allowlist: only namespaces in
  the operator's `--http.api` list get registered (empty list ⇒ all).

## Design decisions & rationale

- **Reflection over codegen/annotations.** A namespace is a struct; its exported
  methods are the API. No IDL, no registration boilerplate per method, no
  generated stubs. Adding `eth_foo` = adding a `Foo` method. The cost (no
  compile-time method-name checking, runtime signature validation) is accepted
  because the API surface is large and churns with every hard fork.
- **One handler serves all transports.** HTTP/WS/IPC/inproc all funnel through
  `handler` + a `ServerCodec`. Transport code is thin; all dispatch, batching,
  timeout, tracing, and subscription logic lives once in `handler.go`.
- **HTTP is deliberately degraded.** `serveSingleRequest` disables subscriptions
  and closes after one batch — HTTP has no server-push channel, so subscriptions
  belong on WS/IPC only. This is enforced structurally (`allowSubscribe=false`),
  not by documentation.
- **Per-call goroutines + context timeouts.** Every call runs concurrently and is
  cancelled by a `context` deadline derived partly from the HTTP server's
  `WriteTimeout` minus 100 ms (`ContextRequestTimeout`, `http.go:398`), so geth
  responds with a timeout error *before* the HTTP layer severs the connection.
- **Subscription activation ordering.** `Notifier` buffers events until the
  subscribe response (carrying the sub ID) has been written, eliminating the race
  where a client gets an event for a subscription it hasn't learned the ID of.
- **Auth as a routing flag, not dispatcher logic.** `Authenticated: true` +
  `getAPIs()` split + a second listener behind `jwtHandler`. The RPC engine stays
  auth-agnostic; the node decides exposure. Engine API also gets its own batch and
  body limits distinct from the public server (`node.go:439-441`).
- **DoS caps threaded through the Backend.** `RPCGasCap`, `RPCEVMTimeout`,
  `RPCTxFeeCap`, batch item/response-size limits, HTTP body limit, WS read limit —
  all first-class config, because a public RPC endpoint is an attack surface.

## Notable patterns (the reusable idea)

- **Convention-driven method registry.** Turning object methods into a wire API by
  reflection + a signature validator is the single most transferable idea: it
  decouples "what methods exist" from "how they're dispatched/served."
- **Codec abstraction = transport independence.** Defining a tiny
  `ServerCodec`/`jsonWriter` interface (read a batch, write JSON, report closed)
  lets one dispatch core serve five transports. New transports implement the
  interface; the engine is untouched.
- **Capability flags on the registration record** (`Authenticated`) rather than in
  the handler — pushes cross-cutting policy (auth, module allowlisting) to the
  composition layer.
- **Buffer-until-acknowledged** for async delivery ordering (the notifier
  activation gate) — a clean solution to "reply must precede pushed events."
- **Panic isolation per call** (`recover()` in `callback.call`) so a single
  malformed handler degrades to one error response, not a server crash.

## Authority note

**geth is the canonical reference for the `eth_*` / `net_*` / `web3_*` JSON-RPC
surface and for the Engine API (`engine_*`)** — the CL↔EL contract
(`forkchoiceUpdated`/`newPayload`/`getPayload` versioning, JWT HS256 auth with a
60 s `iat` window, the authenticated-listener split) is defined by geth's
implementation and the `execution-apis` spec it co-authors. For ETC, method
*semantics* that touch consensus (gas caps, fork-gated fields, receipt shapes)
must still be validated against **core-geth**, but the transport/dispatch
architecture and the non-consensus method contracts here are authoritative.
Engine API applies only to fukuii's PoS family (ETH/Sepolia); ETC/Mordor nodes
never expose it.

## Gotchas / anti-patterns / things they later changed

- **Multiple services, one namespace.** `eth` is populated by four separate
  structs (`GetAPIs`). Registration *merges* their callbacks into one namespace;
  a method-name collision across those structs silently loses one. fukuii's
  `jsonrpc/` controller-per-namespace model avoids this but must consciously
  aggregate what geth spreads across services.
- **`CodecOption` is dead.** `ServeCodec`'s `options` arg and the
  `OptionMethodInvocation`/`OptionSubscriptions` constants are no longer honored
  (`server.go:36`). Don't port them.
- **`rpc.API.Version` / `Public` are vestigial.** `Public` used to gate exposure;
  that job now belongs entirely to the module allowlist + the authenticated/public
  listener split. Copying the old `Public` semantics would be wrong.
- **No compile-time method validation.** A method with an unsupported signature is
  silently skipped by `suitableCallbacks` (returns `nil` callback) — it just never
  appears on the wire, with no error. Registration only errors if a service has
  *zero* suitable methods.
- **HTTP silently can't subscribe.** A `*_subscribe` over HTTP returns
  `ErrNotificationsUnsupported`, not a transport error — clients must know to use
  WS/IPC.
- **JWT `exp` is optional but `iat` is mandatory and drift-checked.** Tokens with
  `iat` more than 60 s in the past *or future* are rejected (`jwt_handler.go:73-76`);
  clock skew between CL and EL is a real operational failure mode.
- **Batch limits default to 0 = unlimited** on a bare `rpc.Server`; only the
  `node` layer sets real caps. Any embedder standing up a raw server must set
  `SetBatchLimits`/`SetHTTPBodyLimit` or expose an unbounded endpoint.
- **GraphQL is not JSON-RPC.** It is a separate `http.Handler` mounted at
  `/graphql` (`graphql/service.go:155`), reusing the same `ethapi.Backend` but
  none of the JSON-RPC dispatch — a parallel API surface, easy to forget when
  reasoning about "the RPC layer."
