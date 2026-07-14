# Observations — node-lifecycle
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/node-lifecycle.md + consensus-engines observation._

This is the Phase-2 cross-client comparison for the **node-lifecycle** subsystem: how each
reference client models its service/DI container, wires components, orders startup, tears down,
layers config, and (erigon) chooses embedded-vs-distributed deployment. It is the DI/wiring/startup
**complement** to `observations/consensus-engines.md` — that doc covers the *family registry* (which
consensus engine activates, keyed off the parsed chainspec); this doc covers the *container* those
families are assembled and started inside. Where the two touch — a plugin/extension SPI that both
registers a consensus family *and* extends the node — the family-selection half stays in
consensus-engines and only the container/lifecycle half is documented here. Every per-client claim is
cited to that client's `node-lifecycle.md`; no repos were re-researched.

**Authority model (per Phase-0):** go-ethereum = the canonical `Lifecycle` service container
(ordered-start / reverse-stop) + defaults→file→flags config layering; core-geth = the ETC
network-preset mapping (`--classic`/`--mordor` → chainID/genesis/bootnodes/gas-limit), inheriting
geth's container wholesale; besu = the JVM plugin-SPI + runtime `ServiceManager` service container +
`CascadingDefaultProvider` config layering; erigon = embedded-or-remote component wiring (one binary,
monolith *or* distributed, decided by config); nethermind = the self-declaring plugin registry +
`IStep` dependency-DAG init + Autofac DI; reth = the compile-time `NodeBuilder`/`NodeTypes`
typestate SDK.

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | fukuii | Authoritative |
|---|---|---|---|---|---|---|---|---|
| **Service-container / DI model** | `node.Node` service container owning transports; services implement 2-method `Lifecycle`, wiring is explicit imperative Go in `makeFullNode` (no DI framework) | inherits geth's `node.Node`/`Lifecycle` verbatim; no DI additions | **two** DI systems: compile-time **Dagger** (static skeleton) + runtime **`ServiceManager`** (`Map<Class,BesuService>`, plugin-facing/late-bound) | geth-derived generic `node.Node` container (dumb) + `eth.Ethereum` 1672-line backend god-struct (smart); `nodebuilder.Builder` emerging component registry | **Autofac** container; `NethermindModule` composes ~25 sub-modules + one `IModule` per enabled plugin; `IConfig`-typed deps auto-resolved via `ConfigRegistrationSource` | **compile-time generics**, no runtime container: `NodeAdapter<T,C>` delegates to typed components; family fixed by the `NodeTypes` impl the binary is built from | cake / self-type DI — ~60 `*Builder` traits (`self: A & B & C =>` + `lazy val`); god-trait `Node` mixes ~90 (`NodeBuilder.scala:1092`); no runtime container, wiring compile-checked by trait linearization | **geth** (minimal container) / **nethermind** (formal DI) — poles |
| **Plugin SPI + lifecycle** | none — services self-register 3 facets (`RegisterAPIs`/`RegisterProtocols`/`RegisterLifecycle`), no third-party extension seam | none (inherited) | **`ServiceLoader` plugin SPI** (`BesuPlugin`) with an explicit `Lifecycle` **FSM** (`UNINITIALIZED→…→STOPPED`), `checkState`-guarded; fine phases `register`/`beforeExternalServices`/`start`/`afterMainLoop`/`stop` | none as an SPI; extension = the in-flight `Configure→Initialize→Start→Close` provider lifecycle for extracted components | **self-declaring `INethermindPlugin`** discovered by assembly scan; each = metadata + optional Autofac `IModule` + typed hooks (`Init`/`InitNetworkProtocol`/`InitRpcModules`) fired from named steps | **compile-time only** — no runtime plugins; extension = implement `NodeTypes`+`Node` traits, or `map_*`/`ExEx` hooks (`on_component_initialized`/`extend_rpc_modules`) | **none** — no SPI/ServiceLoader; extension = subclass `BaseNode` + `override lazy val` (`TestNode.scala:17`); only config-keyed mining-protocol switch (`MiningBuilder.scala:61`) | **besu** (JVM ServiceLoader SPI) / **nethermind** (richest self-declaring registry) |
| **Startup ordering** | **registration-order** flat list; `Start()` loops in order; ordering is implicit (the list *is* the DAG) | inherited registration-order | **phase-ordered pipeline** (`preparePlugins`→`buildController`→`startExternalServices`→`startPlugins`→`startEthereumMainLoop`), fixed in `BesuCommand` | registration-order container + hand-ordered backend `New`→`Init` split (register happens at end of `Init`) | **step-DAG**: `IStep`s declare `Dependencies`/`Dependents`; `EthereumStepsManager` topo-sorts (Kahn) and runs independent steps **concurrently**, gated by per-step `TaskCompletionSource` | **typestate**: `NodeBuilder`→`with_types`→`with_components`→`with_add_ons`→`launch`; each transition a distinct type; component-order enforced by trait bounds (can't `.network()` before `.pool()`) | imperative 6-phase `BaseNode.start()` (`StdNode.scala:50-89`) — hand-ordered (P2P before RPC; Engine-API 8551 `Await`-bound before 8545); lazy-val forcing, no declared DAG | **nethermind** (declare-deps step-DAG) / **reth** (compile-enforced typestate order) / **geth** (registration-order) |
| **Shutdown** | **strict reverse-order** `stopServices` (`for i:=len-1;i>=0;i--`); errors collected into `StopError`, never fatal; rollback on partial start; DB **unclean-shutdown marker** (`ShutdownTracker`) | inherited reverse-order + marker | JVM shutdown hook → `stopPlugins`→`runner.close`; `Runner.stopServices` reverse-order, each bounded by 30s `waitForServiceToStop` | **hand-ordered drain**, not pure reverse-Lifecycle: cancel ctx → bounded graceful-stops → **explicit `WaitIdle`/`bgComponentsEg.Wait` before `chainDB.Close()`** (skipping it hangs close) | DI-driven `IServiceStopper.StopAllServices()` then dispose root `ILifetimeScope` | tokio graceful shutdown via `NodeExitFuture`; `NodeHandle` is `#[must_use]` (drop-without-await silently exits) | manual hand-ordered `shutdown` (`StdNode.scala:235-275`), each `Await`-bounded; `dataSource.close()` last; **no** `CoordinatedShutdown`, **no** unclean-shutdown marker, **no** drain-before-close | **geth** (reverse-order + DB marker) — the custody-grade reference; **erigon** (explicit drain-before-DB-close) |
| **Config layering** | **defaults → TOML file → CLI flags** (`loadBaseConfig`); one aggregate `gethConfig` doubles as `dumpconfig` format; `AutoEnvVars` gives every flag a `GETH_*` env | inherits geth layering; adds **network-preset flags** (`--classic`/`--mordor`) resolving genesis/bootnodes/DNS/datadir/gas-limit in one switch | **PicoCLI options ARE the schema**; `CascadingDefaultProvider` precedence **CLI > env > TOML config > profile TOML**; **`--profile`** = ship-with-binary named presets | **two config objects** — `nodecfg.Config` (container) + `ethconfig.Config` (backend); urfave/cli flags; per-component `*.api.addr` flags flip embedded↔remote | `ConfigProvider` merges JSON files + CLI + env into `IConfig`-per-module instances; a config surface = "declare `IXxxConfig : IConfig`" (reflection-bound) | `NodeConfig<ChainSpec>` CLI-populated, threaded through the whole typestate builder | HOCON (Typesafe): defaults→file→`-D` sysprop; network = whole conf file swapped by sysprop + `invalidateCaches()` (`App.scala:69`); `public`/`enterprise` via `System.setProperty` (`App.scala:100`); no profile cascade / AutoEnvVars | **besu** (CLI>env>TOML>profile + shipped profiles) / **geth** (defaults→file→flags + AutoEnvVars) |
| **Embedded-or-remote component wiring** | monolith only | monolith only | monolith only | **the authority** — sentry/txpool/downloader/rpcdaemon each run **in-proc OR over gRPC**, decided per component by a `*.api.addr` flag; consumers depend only on the gRPC-generated interface, satisfied by a real client **or** a `direct.*` in-memory shim; same binary also has standalone `cmd/{sentry,txpool,…}` entrypoints | monolith only | monolith only | monolith only; `FukuiiRuntime` runs multiple *whole* nodes/JVM (`runtime/FukuiiRuntime.scala:24`) — multi-instance, not one-interface-two-impls | **erigon** (sole authority — one-interface-two-impls) |
| **Family/component assembly** | services registered by `makeFullNode`; role (full/light/dev/Engine-API) chosen by *which* services register | preset-flag switch fans out network identity; container unchanged | per-consensus `BesuControllerBuilder` subclass chosen from genesis (detail → consensus-engines doc); plugins add RPC/permissioning/tx-selection without forking | components extracted into `node/components/*` providers, registered via `nodebuilder.Builder` (build ordering enforced: downloader before storage) | consensus family = one `IConsensusPlugin` + its `IModule`, `Enabled` keyed off chainspec (detail → consensus-engines doc); exactly-one-consensus guard | `ComponentsBuilder<Pool,Payload,Network,Exec,Cons>` six-slot typestate; `EthereumNode` ~30-line preset; `map_*` to override one component | trait mixin = registration; `Node` linearizes ~90 builders (`NodeBuilder.scala:1092`); role via subclass (`StdNode`/`TestNode`/`ChainInstance`); mining protocol config-keyed (`MiningBuilder.scala:61`) | **reth** (compile-time slot assembly) / **nethermind + besu** (runtime plugin assembly) — cross-ref consensus-engines for the *family-selection* half |

## Approach catalog (use-case-aware)

Verdicts: **DEFAULT** = fukuii's baseline best practice · **OPTIONAL(role)** = offer for a named
use-case (enterprise / custody / validator / product-family / multi-network) · **OBSOLETE** =
understood-but-discarded. Use-case taxonomy per `README.md`'s omni-client lens.

| Approach | Clients using it | Good for (use-case / node-role) | Verdict | Why |
|---|---|---|---|---|
| **Ordered-start / reverse-stop `Lifecycle` service container** | go-ethereum (`node.Node`), core-geth (inherited), erigon (geth-derived) | every node role — the minimal container contract | **DEFAULT** | Registration order *is* the dependency DAG; strict reverse teardown means nothing stops while a later-registered dependent still runs — safe shutdown for stateful services (DB, txpool journal) with **no** explicit dependency graph. Partial-start rollback + state-guarded registration turn wiring bugs into loud boot-time failures. The simplest correct container; fukuii's Pekko `CoordinatedShutdown` phases are the natural home. |
| **ServiceLoader plugin SPI + typed `ServiceManager`** | besu (`BesuPlugin` + `Map<Class,BesuService>`) | enterprise — extend the node (custom RPC, permissioning, tx-selection, custody storage) **without forking** | **OPTIONAL(enterprise extension)** | A typed service container both core and plugins read/write, plus lifecycle callbacks whose ordering is *host-enforced* (state enum + `checkState`), not merely documented. The JVM-idiomatic extension seam; ties to the B7.0.5 NetworkFamily registry (a consensus family is one such plugin) — see consensus-engines. |
| **`IStep` dependency-DAG topo-sorted init** | nethermind (`RunnerStepDependenciesAttribute` + `EthereumStepsManager`) | large multi-subsystem boot; plugins that must inject a step without knowing global order | **OPTIONAL(declare-deps init)** | A step declares *what it needs*, not *when it runs*; the manager topo-sorts and runs independent steps concurrently (shorter cold-start). Powerful, but concurrent init **hides undeclared-dependency bugs** that pass under most scheduling and fail under races — the DAG is only as correct as the declarations. Idiomatic fit for fukuii is lower than the geth container. |
| **Autofac / DI container + self-registering plugins** | nethermind (`NethermindModule` + per-plugin `IModule`, reflection discovery) | single-binary multi-network; drop-in third-party families | **OPTIONAL(formal-DI, single-binary)** | Add a family/feature by *existing and implementing an interface* — no central manifest edited. But reflection + Autofac + the legacy `INethermindApi` god-context are **un-idiomatic in Scala/Pekko**; port the *self-declaration idea* (a `given`-based typeclass registry), not the reflection mechanism (see consensus-engines §B7.0.5). |
| **`NodeBuilder` + `NodeTypes` compile-time typestate** | reth (`ComponentsBuilder` six-slot, trait-bound ordering) | type-safe SDK builds; downstream L2 crates (op-reth) | **OPTIONAL(compile-safety lens)** | Illegal states unrepresentable — no `.launch()` before components, no `.network()` before `.pool()`; a mispaired family is a *compile error*, not a runtime panic. But **one crate/binary per family** with no runtime network-switch — fails fukuii's single-JVM-binary premise. Adopt the type-safety idea (Scala `given`), not the one-binary constraint. |
| **Embedded-or-remote component wiring (one-interface-two-impls)** | erigon (`direct.*` in-proc shim vs gRPC client, `*.api.addr` flag) | product-family — same binary as a **monolith OR a distributed** sentry/txpool/downloader/rpcdaemon | **OPTIONAL(product-family / dRPC seam)** | Each cross-component boundary is a gRPC-generated interface; consumers are written **once** and a per-component flag picks a real gRPC client or an in-memory `direct.*` shim. The monolith and the distributed deployment share one codebase — the exact shape of fukuii's product-family / DRPC-GATEWAY-01 seam. Cost: bare public fields, split `New`/`Init` construction, hand-ordered drain — an in-flight refactor, not a finished design. |
| **Config layering CLI > env > TOML > profile** | besu (`CascadingDefaultProvider` + `--profile`) | multi-network / enterprise — ship named presets, override specifics via env/CLI | **DEFAULT** | Explicit precedence with **shipped, named multi-network profiles** as the lowest layer; env for container deploys; CLI always wins. geth's defaults→file→flags + `AutoEnvVars` is the runner-up (no profile layer). fukuii's HOCON + a profiles-as-shipped-presets layer is the analogue. |
| **Registration-after-start = panic (single-shot FSM)** | go-ethereum, erigon | status quo | **DEFAULT (as a guard, with a caveat)** | Making late registration a hard panic (not a soft error) turns wiring bugs into immediate boot failures. Caveat: it forbids *dynamic* service addition — besu had to bolt on `resetState()`/Ephemery to re-run the FSM in-process, and handle health-checks out-of-band, evidence a strict one-shot FSM doesn't cover every real case. |
| **Global mutable singletons for log/metrics** | go-ethereum (`log.SetDefault`, `metrics.Enable()` global registry) | single-node binary | **OBSOLETE (for multi-node/embedded)** | Process-global logging/metrics are fine for one node per process but a footgun when two nodes share a process (tests, embedded, simulators). fukuii should scope observability to the node instance, not the process — nethermind's per-module static `Metrics` + reth's per-instance `RethTracer` reload-layer are the cleaner references. |

## Best-practice synthesis

**The DEFAULT + OPTIONAL menu that falls out of the six clients.** fukuii uses **Pekko supervision +
HOCON**, so the *mechanism* differs from every client here, but the *shapes* transfer.

1. **Container — DEFAULT: ordered-start / reverse-stop lifecycle (geth).** Registration order = the
   dependency DAG; strict reverse teardown is safe for stateful services with no explicit graph.
   Map to a top-level Pekko supervisor that spawns a network/role-specific child-actor set chosen
   from HOCON, with ports/RPC owned centrally, and drive registration-order-respecting teardown
   through **`CoordinatedShutdown` phases**. Keep besu's *phases* (external-services before
   main-loop before plugins) but distribute them across the supervisor tree rather than a 3000-line
   god-command.

2. **Shutdown — DEFAULT: graceful teardown with an unclean-shutdown DB marker (custody-grade).**
   geth's `ShutdownTracker` (write marker on boot, refresh, clear on clean stop; a surviving marker
   ⇒ "unclean shutdown detected") is the anti-corruption story custody demands, paired with reverse-
   order stop and geth's disk-space watchdog → self-SIGTERM. Carry erigon's harder lesson too:
   **explicitly wait for every fire-and-forget task to release its DB transaction *before* closing
   the DB** — the reverse-Lifecycle order alone is not enough (erigon hangs `chainDB.Close()`
   without it).

3. **Config — DEFAULT: layering CLI > env > HOCON > profile.** besu's cascade with **shipped,
   named multi-network profiles** as the base layer + geth's `AutoEnvVars` for container/enterprise
   deploys. Profiles = the built-in `--classic`/`--mordor`/Sepolia presets (core-geth's preset-flag
   mapping is the reference for *what each must resolve to*: chainID/genesis/bootnodes/DNS/datadir/
   gas-limit). HOCON is fukuii's file layer; add a profile layer beneath it and env above it.

4. **Extension — OPTIONAL(enterprise): a plugin/extension SPI (besu ServiceLoader / nethermind
   self-registering).** Extend-without-forking is the enterprise seam (custom RPC, permissioning,
   custody storage, and — crucially — a *consensus family*). This is the container-side of the
   **B7.0.5 NetworkFamily registry**; the family-*selection* half (which engine activates, keyed off
   the chainspec) lives in `consensus-engines.md`. Port the *self-declaration* idea as a Scala 3
   `given`-based typeclass registry (compile-checked instances + a static `ServiceLoader`-style list),
   **not** reflection/Autofac.

5. **Deployment — OPTIONAL(product-family): erigon embedded-or-remote component wiring.** One
   interface, two impls (in-proc `direct.*` shim / gRPC client), selected per component by config —
   the same binary runs as a **monolith OR distributed**. This is fukuii's dRPC / product-family seam
   (DRPC-GATEWAY-01 + separable components): define each heavy subsystem (sync, txpool, P2P, RPC)
   against one interface and let deployment pick the wiring.

**Read-only / role-specialized assembly (OPTIONAL):** the same container hosts validator / archival-
RPC / light roles by varying *which* services register + a config profile — no branching inside the
core (geth). Pairs with erigon's EngineReader/EngineWriter split (consensus-engines) for data-serving
nodes that never seal.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

These are **seeds**, not verdicts to implement in this doc.

- **fukuii node startup = the SR-11 basket** (nodebuilder / CLI / runtime wiring). The **Pekko
  supervision tree is the lifecycle container** — the actor-idiomatic equivalent of geth's
  `node.Node`: a top supervisor owns transports/ports and spawns a role/network-specific child set
  from HOCON; `CoordinatedShutdown` phases are where registration-order-respecting, reverse-order
  teardown + a clean-shutdown DB marker belong.

- **The embedded-or-remote wiring is the product-family seam.** erigon's one-interface-two-impls
  (`direct.*` shim vs gRPC) is the concrete shape for **DRPC-GATEWAY-01 + separable components** —
  design each heavy subsystem against a single interface so the same binary is monolith or
  distributed. Carry erigon's cautions (split `New`/`Init`, drain-before-DB-close) as known costs.

- **The Classic→Typed migration should adopt besu `ServiceManager`-style spawn-time `ActorRef`
  injection** (a README cross-cutting theme): dependencies handed to an actor *at spawn* through a
  typed context (besu's "`register(ctx)` once, field-store it, no global singleton lookup"), rather
  than late global lookups — the Pekko-Typed analogue of a typed service container, and the
  direct counter to nethermind's `INethermindApi` god-context anti-pattern the DI migration is
  retiring.

- **Config layering + profiles** slot onto HOCON: add a shipped-profile layer beneath HOCON and an
  env layer above it (besu cascade), with core-geth's preset mapping defining what `--classic`/
  `--mordor`/Sepolia resolve to.

- **The plugin/extension SPI ties to consensus-engines' B7.0.5 registry** — the container-side
  (register a family's component bundle + hooks) and the selection-side (activate it off the
  chainspec) are two halves of the same design; keep them cross-referenced, not duplicated.

Seeds, not verdicts.
