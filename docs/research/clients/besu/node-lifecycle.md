# besu — node-lifecycle
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

Scope: startup/shutdown orchestration, plugin SPI lifecycle, dependency wiring
(Dagger + a runtime service container), PicoCLI config layering, and
metrics/logging bootstrap. besu is the closest **JVM structural mirror** to fukuii's
node startup: it wires a per-consensus controller, layers config from
CLI/env/TOML, and runs a plugin SPI with an explicit lifecycle FSM — the JVM peer
to nethermind's DI-plugin registry, and the thing to compare fukuii's Pekko
supervision + HOCON against.

## Architecture summary

The boot path is a straight, phase-ordered pipeline, not an actor graph:

```
Besu.main()
  └─ DaggerBesuComponent.create()        # Dagger builds the object graph (compile-time DI)
  └─ besuComponent.getBesuCommand()      # PicoCLI root command, DI-provided
  └─ besuCommand.parse(...)              # PicoCLI: layered config resolution → run()
        │
        ├─ preparePlugins()             # register early services + built-in plugins into ServiceManager
        ├─ besuPluginContext.initialize(pluginConfig)
        ├─ besuPluginContext.registerPlugins()   # ServiceLoader discovery → plugin.register(ctx)
        ├─ buildController()            # BesuControllerBuilder → per-consensus BesuController
        ├─ besuPluginContext.beforeExternalServices()  # plugin hook
        ├─ buildRunner() + runner.startExternalServices()  # metrics, RPC, WS, GraphQL, IPC
        ├─ startPlugins(runner) → besuPluginContext.startPlugins()  # plugin.start()
        ├─ runner.startEthereumMainLoop()   # NAT, P2P NetworkRunner, sync, mining, txpool
        ├─ besuPluginContext.afterExternalServicesMainLoop()  # plugin post-main-loop hook
        └─ addShutdownHook(runner)      # JVM shutdown hook: stopPlugins → runner.close → log flush
```

Two DI mechanisms coexist. **Dagger** (compile-time, `@Component`/`@Module`) builds
the *static* app skeleton — `BesuCommand`, the `MetricsSystem`, cache loaders, the
plugin context singleton. The **`ServiceManager`/`BesuPluginContextImpl`** is a
*runtime* service container (a `Map<Class<?>, BesuService>`) used for late-bound,
plugin-facing services that only exist once config/controller are known. Dagger
wires besu-to-besu; the ServiceManager wires besu-to-plugin and plugin-to-plugin.

The **plugin lifecycle is an explicit state machine** (`Lifecycle` enum in
`BesuPluginContextImpl`) with `checkState` guards at every transition:
`UNINITIALIZED → INITIALIZED → REGISTERING → REGISTERED → BEFORE_EXTERNAL_SERVICES_* →
BEFORE_MAIN_LOOP_* → STOPPING → STOPPED`. Each phase iterates registered plugins and,
on a plugin throwing, either removes that plugin and continues (`--plugin-continue-on-error`)
or aborts the whole boot — a deliberate fail-loud-by-default posture.

## Key types / interfaces / files

- `app/.../besu/Besu.java:34` — `main()`: sets up log4j2/netty/vertx logging system
  properties, builds the Dagger component, hands off to PicoCLI. Deliberately tiny.
- `app/.../besu/cli/BesuCommand.java` (3045 lines) — the PicoCLI `@Command` root. Owns
  the entire CLI option surface, config resolution, and the boot sequence
  (`initialProcess()` at :1010, `preparePlugins()` at :1279, `startPlugins()` at :1349,
  `addShutdownHook()` at :2445). This is the orchestrator god-object.
- `app/.../besu/Runner.java:66` — `AutoCloseable` service-lifecycle controller.
  `startExternalServices()` (:153, metrics/RPC/WS/GraphQL/IPC), `startEthereumMainLoop()`
  (:173, NAT→P2P→sync→mining→txpool), `stopServices()` (:298, reverse order),
  `stop()`/`awaitStop()`/`close()`. Holds every long-lived service as an `Optional<>` field.
- `app/.../besu/RunnerBuilder.java` (1524 lines) — assembles the `Runner` from config:
  decides which services are present (P2P on/off, which RPC transports enabled).
- `plugin-api/.../plugin/BesuPlugin.java:27` — the SPI. `register(ServiceManager)`,
  `beforeExternalServices()`, `start()`, `afterExternalServicePostMainLoop()`, `stop()`,
  `reloadConfiguration()`. `register` is the only time the plugin receives the context —
  it must stash it. All hooks except `register`/`start`/`stop` have no-op defaults.
- `plugin-api/.../plugin/ServiceManager.java:24` — the runtime service container interface:
  `addService(Class<T>, T)` / `getService(Class<T>) → Optional<T>`. Includes a
  `SimpleServiceManager` test impl.
- `app/.../besu/services/BesuPluginContextImpl.java:52` — the production `ServiceManager` +
  plugin loader + lifecycle FSM. `detectPlugins()` (:361) does the `ServiceLoader` discovery
  over a `URLClassLoader` of jars in the plugins dir; per-phase methods drive every plugin
  through its lifecycle with per-plugin try/catch.
- `plugin-api/.../plugin/services/` — 21 service SPIs plugins can provide or consume:
  `PicoCLIOptions` (plugins add CLI flags), `RpcEndpointService` (custom RPC),
  `PermissioningService`, `TransactionSelectionService`, `StorageService`,
  `SecurityModuleService`, `BesuEvents`, `MetricsSystem`, `HealthCheckService`, etc.
- `app/.../besu/components/BesuComponent.java:44` — Dagger `@Singleton @Component` root;
  lists the modules (`BesuCommandModule`, `MetricsSystemModule`, `BesuPluginContextModule`, …).
- `app/.../besu/controller/BesuControllerBuilder.java` + `MainnetBesuControllerBuilder` /
  `CliqueBesuControllerBuilder` / `QbftBesuControllerBuilder` / `MergeBesuControllerBuilder` /
  `ConsensusScheduleBesuControllerBuilder` — one builder per consensus mechanism; the
  builder is chosen from genesis, producing a `BesuController` that exposes
  `getSynchronizer()`, `getMiningCoordinator()`, `getTransactionPool()`, `getProtocolContext()`.
  (Consensus detail is in the ★ consensus doc; here it is just "the per-consensus wiring node".)
- `app/.../besu/cli/util/CascadingDefaultProvider.java:26` — PicoCLI `IDefaultValueProvider`
  that returns the first non-null default across an ordered list of providers.
- `app/.../besu/cli/util/ConfigDefaultValueProviderStrategy.java:69` — assembles the cascade:
  `[EnvironmentVariableDefaultProvider, TOML config-file provider, TOML profile provider]`.
- `app/.../besu/cli/util/{EnvironmentVariableDefaultProvider,TomlConfigurationDefaultProvider,
  ProfileFinder,ConfigFileFinder}.java` — the four config sources. `ProfileFinder` resolves
  `--profile`/`BESU_PROFILE` to a bundled TOML (`InternalProfileName`) — named,
  ship-with-the-binary config presets.
- `app/.../besu/cli/logging/BesuLoggingConfigurationFactory.java` — custom log4j2
  `ConfigurationFactory`, wired via a system property in `Besu.setupLogging()`.
- `metrics/core/.../metrics/prometheus/` — Prometheus is the metrics backend
  (`MetricsHttpService`, `MetricsPushGatewayService`, `PrometheusCollector`); `MetricsService`
  (`metrics/core/.../metrics/MetricsService.java`) is the start/stop unit the `Runner` drives.

## Design decisions & rationale

- **PicoCLI options ARE the config schema.** There is no separate config object model;
  every setting is a CLI option, and files/env are merely *default providers* that fill
  options the user didn't pass. This gives one source of truth and free `--help`, at the
  cost of a 3000-line command class.
- **Layered precedence via cascade** (`CascadingDefaultProvider`): explicit CLI args always
  win (PicoCLI semantics); for anything unset, the cascade resolves in order
  **env var → TOML config file → profile TOML**, first non-null wins. Profiles serve
  multi-network/enterprise: ship a named preset, let env/CLI override specifics.
- **Plugin lifecycle split into fine phases** (`register` / `beforeExternalServices` /
  `start` / `afterExternalServicePostMainLoop` / `stop`) so a plugin can hook the exact
  moment it needs: `register` is *before* config is fully known (only safe to add CLI
  options and grab the context); `start` is *after* external services and the controller
  exist (safe to use services and spawn threads). This ordering constraint is enforced by
  the FSM `checkState` guards, not left to plugin authors.
- **Two-tier service registration** (`registerEarlyServices` in `preparePlugins()` vs
  `registerRuntimeServices` in `startPlugins()`): services that exist pre-controller
  (PicoCLI options, storage, security module, metrics categories) are added early so
  `register()` can see them; services that need the built controller/runner
  (`BlockchainService`, in-process RPC methods, mining params) are added just before
  `startPlugins()`. Mirrors the plugin `register`→`start` split.
- **`register(ctx)` gives the context exactly once** and the plugin must field-store it —
  a deliberate "no global singleton lookup" contract that keeps the dependency explicit.
- **Graceful, ordered shutdown for custody safety.** A JVM shutdown hook runs
  `stopPlugins() → runner.close()`; `Runner.stopServices()` tears down in dependency-reverse
  order (RPC/txpool first, then mining, sync, network, NAT, then `besuController.close()`),
  each wrapped in a 30s-bounded `waitForServiceToStop`. Prevents half-open RPC while state
  is still flushing.

## Notable patterns (the reusable idea)

**The plugin SPI = ServiceLoader discovery + a lifecycle FSM + a typed service container.**
The single most transferable idea: a `Map<Class<T>, T>` "service manager" that both core and
plugins write into and read from, combined with a small set of explicitly-ordered lifecycle
callbacks whose ordering is *enforced by the host* (via a state enum + `checkState`), not
merely documented. This lets third parties extend the node — custom RPC, permissioning,
transaction selection, alternative storage — **without forking**, which is exactly the
enterprise use case (extend for permissioning/custody/custom RPC and keep upstream).

Other reusable bits:
- **Cascading default-value providers** behind one CLI schema: env → file → profile, unified,
  with named ship-with-binary profiles for multi-network presets.
- **`Runner` as an explicit `AutoCloseable` service graph** with symmetric start/stop and
  per-service bounded-timeout shutdown — the graceful-shutdown discipline custody demands.
- **`Optional<Service>` fields** to model "this transport/service may be disabled by config"
  without null-checks scattered through start/stop.
- **Fail-loud-by-default, opt-in-continue** on plugin errors (`isContinueOnPluginError`).

## Authority note

besu = the **JVM plugin-SPI + runtime service-container structural reference** — the closest
analogue to how fukuii could expose extension seams on the JVM. **nethermind is the broader
plugin-registry / DI-container authority** (its `INethermindPlugin` + Autofac module registry
is the more elaborate model); cross-reference `docs/research/clients/nethermind/node-lifecycle.md`
for the fuller plugin-registry design. For consensus-family controller selection, cross-ref
the besu ★ consensus doc (`BesuControllerBuilder` subclasses). besu is a structural mirror,
**not** a consensus authority for ETC — core-geth remains the sole PoW/ETC authority.

## Gotchas / anti-patterns / things they later changed

- **`BesuCommand` is a 3045-line god-object.** It is orchestrator, CLI schema, config
  resolver, plugin driver, and shutdown-hook owner all at once. Do not treat it as a model
  to copy wholesale — the *phases* it runs are the reusable part, not its monolithic shape.
  fukuii's Pekko-supervision equivalent should keep these phases but distribute them
  (supervisor tree / HOCON) rather than centralize them in one class.
- **Compile-time Dagger vs runtime ServiceManager is a genuine split-brain.** Two DI systems
  in one process; knowing which one owns a given dependency (static skeleton → Dagger,
  plugin-facing/late-bound → ServiceManager) is non-obvious and a frequent source of "where
  is this wired?" confusion.
- **`register()`-time services are a trap for plugin authors.** Most services are absent
  until `start()` (`ServiceManager.getService` returns `Optional.empty()` early). A plugin
  that calls `getService` in `register()` will silently get nothing — the SPI docs explicitly
  warn about this. The lifecycle ordering is load-bearing.
- **Plugin `URLClassLoader` stays open for the whole process** and is only closed in
  `stopPlugins()` (plugins load classes lazily) — a deliberate file-handle lifetime the
  comment at `BesuPluginContextImpl:372` calls out.
- **`resetState()` / Ephemery restart** (`Runner.startEphemery`/`stopEphemery`,
  `BesuPluginContextImpl.resetState()`) re-runs the whole plugin FSM in-process to support the
  Ephemery auto-restarting testnet. This is a special case that bends the "one-shot FSM"
  invariant — evidence that in-process lifecycle-reset is bolted on, not native.
- **Liveness/readiness check plugins are handled out-of-band** (registered/started/stopped
  directly in `BesuCommand`, not through the generic plugin list — see :884, :1365, :2451),
  a sign the generic SPI didn't cleanly cover health-check ordering.
