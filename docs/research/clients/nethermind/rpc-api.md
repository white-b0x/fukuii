# nethermind — rpc-api
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind's JSON-RPC layer (`Nethermind.JsonRpc`) is built on **interface-based RPC
modules discovered by reflection over C# attributes**, then wired through Autofac DI.
The unit of API surface is a *module* — a C# interface tagged `[RpcModule(ModuleType.X)]`
whose methods each carry `[JsonRpcMethod(...)]` metadata (description, availability,
sharability, example response). At startup each module interface + its implementation +
a *pool strategy* are registered as an `RpcModuleInfo` into the DI container's ordered
list; `RpcModuleProvider` collects all of them and reflects each interface's methods
into a flat `method-name → ResolvedMethodInfo` dictionary (frozen into a `FrozenDictionary`
after first use, with a hand-maintained "hot methods" fast path for the ~6 highest-QPS
methods like `engine_newPayloadV4`, `eth_call`, `eth_getBlockByNumber`).

Request flow: transport (HTTP via ASP.NET Core / WS via `Nethermind.Sockets` / IPC / a
subscription duplex client) → `JsonRpcProcessor` (parses the request pipe, handles single
vs. batch vs. streaming, timeouts, recorder) → `JsonRpcService.SendRequestAsync` →
`RpcModuleProvider.Check`/`Rent` resolves the method, checks endpoint + module-enabled
availability, **rents a module instance from that module's pool**, invokes it (via a
pre-compiled typed delegate when possible, else a reflection `MethodInvoker`), then
returns the instance to the pool. Concurrency is bounded per-module by the pool, plus two
global counters (`RpcLimits`) capping queued exclusive waiters and in-flight shared calls.

The Engine API (`engine_*`) is not in core `Nethermind.JsonRpc` at all — it is a
**plugin-contributed module**: `Nethermind.Merge.Plugin` registers `IEngineRpcModule`
through the exact same `RegisterSingletonJsonRpcModule` DI extension any plugin uses, and
JWT authentication is applied by the transport layer to the Engine port. This is the
central reusable idea: a plugin owns a namespace end-to-end (interface, methods, handlers,
DI wiring) without the core RPC layer knowing anything specific about it.

## Key types / interfaces / files

- `Nethermind.JsonRpc/Modules/IRpcModule.cs:6` — the empty marker interface; every RPC
  module interface extends it (`IEthRpcModule`, `IDebugRpcModule`, `IEngineRpcModule`, …).
- `Nethermind.JsonRpc/Modules/RpcModuleAttribute.cs:8` — `[RpcModule("Eth")]` class-level
  attribute; ties a module interface to a namespace string.
- `Nethermind.JsonRpc/Modules/JsonRpcMethodAttribute.cs:9` — per-method metadata:
  `Description`, `IsImplemented`, `IsSharable` (→ can use the shared read-only instance),
  `Availability` (`RpcEndpoint` flags), `ExampleResponse`, `EdgeCaseHint`. Drives docs and
  routing, not just annotation.
- `Nethermind.JsonRpc/Modules/RpcEndpoint.cs:9` — `[Flags]` enum `Http | Ws | IPC | All`;
  a method's `Availability` is AND-ed with the request's endpoint to allow/deny per-transport.
- `Nethermind.JsonRpc/Modules/ModuleType.cs:8` — the canonical namespace name constants
  (`Eth`, `Debug`, `Trace`, `Engine`, `Admin`, `Parity`, `TxPool`, `Subscribe`, …) plus
  `DefaultModules` and `DefaultEngineModules` sets.
- `Nethermind.JsonRpc/Modules/RpcModuleProvider.cs:23` — the registry. `Register<T>(pool)`
  reflects interface `T`'s methods (`GetMethodDict`, `GetMethods`), builds `ResolvedMethodInfo`
  per method, and stamps the rent/return pool onto each. `Check(...)` resolves a call and
  reports `Unknown | EndpointDisabled | Disabled | Enabled`. `ResolvedMethodInfo`
  (line 296) is the heavyweight per-method record: it pre-compiles typed direct-invoker
  delegates for 0–4 parameters (`CreateTypedDirectOneParameterInvoker<…>` etc.) to avoid
  reflection on the hot path, plus parameter-parsing metadata (`ExpectedParameter`).
- `Nethermind.JsonRpc/Modules/IRpcModulePool.cs:8` — pool contract: `GetModule(canBeShared)`
  / `ReturnModule`. Strategies: `BoundedModulePool`, `SingletonModulePool`, `LazyModulePool`.
- `Nethermind.JsonRpc/Modules/BoundedModulePool.cs:65` — the concurrency strategy.
  Constructs `exclusiveCapacity` module instances into a `ConcurrentQueue` guarded by a
  `SemaphoreSlim`, plus **one shared instance** for sharable (read-only) methods. `SharedPath`
  hands back the shared instance (bounded only by the global shared counter); `SlowPath`
  rents an exclusive instance with a timeout (`ModuleRentalTimeoutException` on exhaustion).
  `RpcLimits` (line 15) holds the two process-wide counters (queued waiters, in-flight shared).
- `Nethermind.JsonRpc/Modules/ModuleFactoryBase.cs:9` — base for per-rent module factories;
  `AutoRpcModuleFactory.cs:14` creates a fresh Autofac child lifetime scope per module
  instance (scoped dependencies per rented module) — used by Subscribe/Admin/Eth/Trace/Debug.
- `Nethermind.JsonRpc/Modules/IContainerBuilderExtensions.cs:11` — the DI glue every module
  (core or plugin) uses: `RegisterSingletonJsonRpcModule<T,TImpl>()` (one shared instance)
  and `RegisterBoundedJsonRpcModule<T,TFactory>(maxCount, timeout)` (pooled). Both
  `AddLast<RpcModuleInfo>(...)` into the ordered DI list `RpcModuleProvider` consumes.
- `Nethermind.Init/Modules/RpcModules.cs:58` — central registration of the built-in modules;
  shows the singleton-vs-bounded choice per module (e.g. `IEthRpcModule` bounded to
  `EthModuleConcurrentInstances ?? ProcessorCount`; `IDebugRpcModule` bounded to
  `DebugModuleConcurrentInstances ?? ProcessorCount`; Net/Web3/Parity/TxPool singleton).
- `Nethermind.JsonRpc/JsonRpcProcessor.cs:27` — transport-agnostic request pump: parses the
  `PipeReader`, dispatches single / batch / multi-document (socket) modes, applies timeouts
  (skipped when authenticated/Engine), optional `Recorder` diagnostics.
- `Nethermind.JsonRpc/JsonRpcService.cs:38` — `SendRequestAsync`: resolves, rents, invokes
  (`DirectNoParameterInvoker` / `DirectParameterInvoker` fast paths, else `MethodInvoker`),
  maps exceptions to error codes, returns the module to the pool.
- `Nethermind.JsonRpc/Modules/Subscribe/SubscribeRpcModule.cs:10` + `SubscriptionManager.cs`
  — `eth_subscribe`/`eth_unsubscribe`; the WS pub/sub layer, itself a bounded module (2 instances).
- `Nethermind.Merge.Plugin/IEngineRpcModule.cs:12` — the Engine API module, split across
  **`partial interface` files per hard fork** (`IEngineRpcModule.Paris/Shanghai/Cancun/
  Prague/Osaka/Amsterdam.cs`), each adding that fork's `engine_newPayloadVN` /
  `engine_forkchoiceUpdatedVN` / `engine_getPayloadVN`. Implementation mirrors the split
  (`EngineRpcModule.*.cs`).
- `Nethermind.Merge.Plugin/MergePlugin.cs:273` — the plugin registers its namespace with
  `.RegisterSingletonJsonRpcModule<IEngineRpcModule, EngineRpcModule>()` then `AddSingleton`s
  every per-version handler beneath it — self-contained namespace wiring.
- `Nethermind.Core/Authentication/JwtAuthentication.cs` + `IRpcAuthentication.cs` — JWT
  (HS256) auth; `Nethermind.Runner/Ethereum/Steps/StartRpc.cs:50` selects
  `JwtAuthentication.FromFile(...)` vs. no-auth and applies it to the Engine endpoint only.

## Design decisions & rationale

- **Attribute-driven registration, one interface per namespace.** A module is a single
  interface (`IEthRpcModule` has ~60 methods); the framework reflects it once at startup
  and never again. Method *metadata* (docs, per-transport availability, sharability) lives
  as attributes on each method rather than in a side registry — the interface is the single
  source of truth for both dispatch and generated documentation.
- **Per-module bounded concurrency instead of a single global lock.** Each module chooses
  its pooling: cheap stateless modules (Net, Web3) are singletons; expensive stateful ones
  (Eth, Debug, Trace) get a bounded pool sized to CPU count. This isolates a slow `debug_*`
  call from starving `eth_*` throughput. The shared-instance fast path means read-only
  ("sharable") calls skip the semaphore entirely and are only bounded by a global counter.
- **Two-tier availability gating.** A call is checked against (a) the method's
  `RpcEndpoint` availability (is this method allowed over the transport it arrived on?) and
  (b) whether the module is enabled on that URL/config. This is how the same binary safely
  exposes a public `eth`/`net` HTTP port and a JWT-gated `engine` port with different module
  sets (`DefaultModules` vs. `DefaultEngineModules`) without per-endpoint code.
- **Engine API as a plugin, authenticated at the edge.** Consensus-layer coupling
  (`engine_*`, forkchoice, payload building) is entirely in `Nethermind.Merge.Plugin`; the
  core RPC layer has no `Engine` special-casing beyond the shared `ModuleType.Engine`
  constant. JWT is enforced by the transport/runner, not inside the module.
- **Hot-method interning + FrozenDictionary.** After registration the method table is frozen
  and the ~6 hottest method names are resolved into an array indexed by reference-equality
  check — a micro-optimization acknowledging that a handful of `engine_*`/`eth_*` methods
  dominate real traffic.
- **Fork-versioned partial interfaces.** The Engine module grows one `partial interface`
  file per fork, so adding Osaka's `engine_*` methods is an additive file, never an edit to
  a monolith — a clean seam for hard-fork evolution.

## Notable patterns (the reusable idea)

**A namespace = (marker interface + attribute metadata + a pool strategy) registered into
one ordered DI list.** The provider reflects every registered interface into a flat method
map; dispatch is a dictionary lookup + rent-from-pool + typed-delegate invoke + return.
Adding a namespace — core or third-party plugin — is one `RegisterXxxJsonRpcModule<IFoo,
Foo>()` call; the plugin supplies the interface, the methods, and their handlers, and the
core dispatcher needs zero knowledge of them. Concurrency, per-transport availability, and
enable/disable are all declarative (pool choice + `[JsonRpcMethod]` flags + config), not
imperative per-method plumbing.

This sits deliberately **between two extremes**: geth registers whole namespace *structs*
and reflects their methods at runtime (dynamic, little metadata); besu leans toward
one class per method (`JsonRpcMethod` objects, verbose but explicit). Nethermind's
interface-with-attributes gives geth-like compactness (one interface, methods auto-discovered)
*plus* besu-like explicit per-method metadata and per-method routing — without a class per method.

## Authority note

go-ethereum is the canonical reference for `eth_*` / `net_*` / `web3_*` and Engine API
*behavior* (method semantics, error codes, payload shapes). Nethermind is **not** a behavior
authority — cite it as the reference for the *structural* pattern: attribute-driven modular
RPC, per-module bounded pooling, plugin-contributed namespaces, and per-transport/JWT gating.

## Gotchas / anti-patterns / things they later changed

- **Reflection cost is front-loaded, invocation is not.** Registration reflects every
  interface method and *compiles typed delegates* for 0–4-parameter methods so the hot path
  avoids `MethodInfo.Invoke`. Methods with >4 parameters or by-ref/value-type params fall
  back to the slower `MethodInvoker` — a subtle perf cliff tied to signature shape.
- **`IsSharable` correctness is a footgun.** A method marked `IsSharable = true` is served
  from the *single shared module instance* concurrently. If such a method mutates
  module-instance state it is a data race. Sharable must mean genuinely read-only; the
  bounded pool only protects non-sharable ("exclusive") calls.
- **Global `RpcLimits` are process-wide static counters**, not per-endpoint — a burst on one
  transport can exhaust shared-call slots for all transports. Configured once via
  `RpcLimits.Init`.
- **Registering an impl without `[RpcModule]` silently no-ops** — `Register<T>` logs a warn
  and returns if the attribute is missing; the namespace just never appears. Easy to miss.
- **Module enable/disable is namespace-granular, not method-granular** at config level; to
  filter individual methods you need the `CallsFilterFilePath` `RpcMethodFilter` (an opt-in
  file), applied at registration time (`RpcModuleProvider` ctor).
- **Timeouts are suppressed for authenticated (Engine) requests** (`JsonRpcProcessor` line
  66/79) — the CL is trusted to drive its own deadlines; a hung `engine_*` handler won't be
  cut off by the RPC timeout the way a public `eth_*` call would.
