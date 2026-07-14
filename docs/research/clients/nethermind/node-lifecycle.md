# nethermind — node-lifecycle
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

Slot coverage: startup/shutdown, DI/plugin SPI, config, metrics/observability. This is
nethermind's highest-value slot: it is the reference implementation of a **self-declaring
plugin registry** layered on an **ordered, dependency-resolved step-based init** and an
**Autofac DI container**, with **one `IConfig` interface per module** and **per-module
static `Metrics` classes**. Everything here is discovered by reflection/assembly-scan, not
hand-wired — the design target for fukuii's B7.0.5 NetworkFamily registry.

## Architecture summary

Boot is a four-layer pipeline (see `Nethermind.Runner/Program.cs`):

1. **Config load** — `ConfigProvider` merges JSON config files, CLI args, and env vars into
   a set of `IConfig`-derived instances (`Program.cs:390` `CreateConfigProvider`).
2. **Plugin discovery + ordering** — `PluginLoader` collects a hardcoded `EmbeddedPlugins`
   list plus any `INethermindPlugin`-implementing type found by scanning DLLs in the plugins
   directory, then sorts them by `IPluginConfig.PluginOrder` (`Program.cs:119`, `:161`,
   `:233`).
3. **DI container build** — `NethermindModule` (the top-level Autofac `Module`) composes
   ~25 sub-modules; each enabled plugin contributes its own `IModule` into the same
   container. The container is built once.
4. **Ordered init** — `EthereumRunner.Start` calls `EthereumStepsManager.InitializeAll`,
   which resolves every `IStep`, computes a dependency DAG, and runs steps **concurrently
   but respecting declared dependencies** via per-step `TaskCompletionSource` gates. Plugin
   SPI hooks (`Init` / `InitNetworkProtocol` / `InitRpcModules`) fire from inside specific
   steps.

Shutdown is symmetric and DI-driven: `EthereumRunner.StopAsync` calls
`IServiceStopper.StopAllServices()` then disposes the root `ILifetimeScope`
(`EthereumRunner.cs:32`).

## Key types / interfaces / files

- `Nethermind.Api/Steps/IStep.cs:9` — the init unit: `Task Execute(CancellationToken)` +
  `bool MustInitialize`. A failed non-must step only warns; a failed must step throws.
- `Nethermind.Api/Steps/RunnerStepDependenciesAttribute.cs:9` — declares a step's
  `Dependencies` (must run before) and `Dependents` (this must run before them), with an
  `Optional` flag so a missing optional dependency is dropped rather than fatal.
- `Nethermind.Api/Steps/StepInfo.cs` — reflects a step type to its **step base type**
  (walks up the inheritance chain while still an `IStep`), so a plugin subclass can
  *replace* a built-in step by sharing its base type.
- `Nethermind.Init/Steps/EthereumStepsManager.cs:58` — `CreateAndExecuteSteps` builds the
  DAG (wiring `Dependents` back into each dependent's `Dependencies`, line 72–104),
  logs a Kahn's-topological-sort tree, then launches all steps; `StepWrapper.StartExecute`
  (line 274) awaits its dependencies' `TaskCompletionSource`s before running. This is the
  distinctive **declare-dependencies, resolve-order-automatically** pattern.
- `Nethermind.Init/Steps/EthereumStepsLoader.cs:37` — `SelectImplementation`: when several
  step types share a base, picks the one whose constructor takes the **most-derived API
  type** the active consensus plugin exposes (`IConsensusPlugin.ApiType`). This is how a
  plugin overrides a step: register a step subclass whose ctor takes the plugin's API type.
- `Nethermind.Init/Modules/BuiltInStepsModule.cs` — the canonical `BuiltInSteps[]` list
  (InitializeBlockchain, InitializeNetwork, InitializePlugins, RegisterRpcModules, …),
  each registered via `builder.AddStep(...)`.
- `Nethermind.Api/Extensions/INethermindPlugin.cs:9` — **the plugin SPI**. Metadata
  (`Name`/`Description`/`Author`), `Enabled`, `MustInitialize`, an optional Autofac
  `IModule Module`, plus default-method lifecycle hooks:
  `InitTxTypesAndRlpDecoders` → `Init(api)` → `InitNetworkProtocol()` → `InitRpcModules()`.
- `Nethermind.Api/Extensions/IConsensusPlugin.cs:8` — a plugin that also names an `ApiType`;
  `PluginLoader` enforces **exactly one enabled `IConsensusPlugin`** (`PluginLoader.cs:165`).
- `Nethermind.Api/Extensions/PluginLoader.cs` — `Load()` (embedded + assembly scan, line 30),
  `OrderPlugins()` (config-driven priority sort, line 93), `LoadPlugins()` (builds a throwaway
  Autofac container to instantiate plugins with config/chainspec injected, filters by
  `Enabled`, line 125).
- `Nethermind.Runner/NethermindPlugins.cs:11` — the `EmbeddedPlugins` static list (AuRa,
  Clique, Ethash, NethDev, Merge, Optimism, Taiko, Xdc, Shutter, Flashbots, HealthChecks…).
- `Nethermind.Consensus.Ethash/EthashPlugin.cs:15` — **the family-registry exemplar**: a
  `IConsensusPlugin` whose `Enabled => chainSpec.SealEngineType == SealEngineType` and whose
  `IModule` (`EthHashModule`) registers the entire PoW component set (reward calculator,
  difficulty calculator, seal validator, block-producer factory). Adding a consensus family
  = adding one plugin + one module; core is untouched.
- `Nethermind.Init/Steps/InitializePlugins.cs:16` — the step (depends on
  `InitializeBlockTree`) that loops `api.Plugins` and awaits each `plugin.Init(api)`.
  `InitializeNetwork.cs:261` awaits `InitNetworkProtocol()`; `RegisterPluginRpcModules.cs:25`
  awaits `InitRpcModules()` — the three SPI hooks are pumped from three distinct steps.
- `Nethermind.Init/Modules/NethermindModule.cs:38` — top-level DI composition root; nests
  `AppInputModule` (config/chainspec/logManager), NetworkModule, DbModule, WorldStateModule,
  BlockProcessingModule, RpcModules, MonitoringModule, `BuiltInStepsModule`, etc.
- `Nethermind.Config/IConfig.cs` — a **bare marker interface**. Each subsystem declares its
  own `IXxxConfig : IConfig` (IInitConfig, INetworkConfig, IJsonRpcConfig, IMetricsConfig…).
- `Nethermind.Config/ConfigProvider.cs:55` — `TypeDiscovery.FindNethermindBasedTypes(IConfig)`
  reflection-scans all assemblies for `IConfig` interfaces and materializes each.
- `Nethermind.Config/ConfigRegistrationSource.cs` — an Autofac `IRegistrationSource` that
  **dynamically resolves any `IConfig`-typed dependency** by delegating to `ConfigProvider`
  — so a component just constructor-injects `IJsonRpcConfig` and DI supplies it, no explicit
  registration per config type.
- `Nethermind.Specs/ChainSpecStyle/IChainSpecEngineParameters.cs:8` +
  `ChainSpecParametersProvider.cs:53` — engine params are **reflection-discovered** the same
  way: `TypeDiscovery.FindNethermindBasedTypes(IChainSpecEngineParameters)`, `Activator`-
  instantiated, and JSON-deserialized from the matching chainspec section. Same self-
  declaring-registry mechanism as config and plugins.
- `Nethermind.Api/INethermindApi.cs` / `IApiWithNetwork.cs` — the layered **god-context**
  (`IApiWithBlockchain` → `IApiWithNetwork` → `INethermindApi`): a large mutable property bag
  (RpcModuleProvider, SyncPeerPool, ProtocolsManager…) that plugins read/write. Being
  actively decomposed into DI modules (`NethermindModule` doc-comment: "fallback to
  INethermindApi").
- `Nethermind.<Subsystem>/Metrics.cs` (e.g. `Nethermind.TxPool/Metrics.cs`) — per-module
  **static classes** of `[CounterMetric]`/`[GaugeMetric]` + `[Description]`-annotated
  properties, scraped by `MonitoringModule` for Prometheus. Observability co-locates with the
  subsystem, not centralized.

## Design decisions & rationale

- **Steps declare dependencies, not order.** No central ordered list to maintain; a new step
  (or plugin step) just declares what it needs. The manager topo-sorts and runs independent
  steps concurrently, shortening cold-start. Missing-but-optional deps degrade gracefully.
- **Plugin = metadata + one Autofac `IModule` + lifecycle hooks.** A plugin extends the node
  purely additively: its module contributes registrations into the shared container, its
  hooks fire at the right init phases. Core code never references a specific plugin.
- **`Enabled` is data-driven, not compile-time.** `EthashPlugin.Enabled` keys off
  `chainSpec.SealEngineType`; all consensus plugins are embedded, and exactly one activates
  per chainspec. One binary, many networks/families, selected by config.
- **Reflection-discovery is the uniform registry mechanism.** Plugins (assembly scan),
  configs (`TypeDiscovery`), and engine parameters (`TypeDiscovery`) all self-register by
  *existing and implementing an interface* — no manifest edited when a new one is added.
- **Config is a marker interface + dynamic registration source.** Adding a config surface is
  "declare `IXxxConfig : IConfig`"; binding from JSON/CLI/env and DI resolution are automatic.

## Notable patterns (the reusable idea)

**The transferable core for fukuii's B7.0.5 NetworkFamily registry:** a family/feature is a
*self-describing unit that declares (a) whether it's active for the current chain and (b) the
component bundle it contributes*, and the runtime *discovers and wires those units by their
type/interface* rather than by a hand-maintained switch. In nethermind:

- discovery = assembly scan / `TypeDiscovery` (open set, no central list to edit),
- activation = a data-driven `Enabled` predicate over the chainspec,
- contribution = an `IModule` merged into one DI container,
- ordering/lifecycle = declared dependencies resolved into an execution DAG + fixed SPI hooks.

For fukuii (Scala 3, single binary): the nethermind analogue of "each `IConsensusPlugin`
supplies its `IModule` and an `Enabled` gate" maps to a **given-based typeclass registry** —
each `NetworkFamily` provides a `given` instance carrying its component bundle, summoned by
the active chain's family tag. That preserves nethermind's single-binary multi-network
add-a-plugin-without-touching-core property while gaining reth-style compile-time safety
(missing family instance = compile error, not a runtime "plugin not found"). The `IStep`
dependency-DAG is the model for fukuii's startup ordering; `IConfig`-per-module +
`ConfigRegistrationSource` is the model for config-driven multi-network without a central
config god-object.

## Authority note

nethermind = **THE** self-declaring-plugin-registry + step-based-init + Autofac-DI authority
among the EVM clients: the richest plugin SPI (typed lifecycle hooks, per-plugin DI module,
consensus-plugin selection), dependency-resolved concurrent init, and uniform reflection
discovery across plugins/configs/engine-params. **besu** is the JVM plugin-SPI peer
(`BesuPlugin` service registration via `ServiceLoader`), useful as the JVM-idiom cross-check.
**geth** is a simpler service container (`node.Node` + `RegisterLifecycle`, ordered
start/stop, no plugin SPI). For a runtime family/plugin registry, document from nethermind
first.

## Gotchas / anti-patterns / things they later changed

- **The `INethermindApi` god-context is legacy being unwound.** `NethermindModule`'s own
  doc-comment calls it a "fallback to `INethermindApi`" and the project is decomposing the
  property-bag into DI modules. Do **not** hold it up as the pattern — it's the anti-pattern
  the DI migration is retiring. The plugin SPI still passes it to `Init(api)`, so it lingers.
- **Autofac migration is recent.** DI was previously more manual; `di-patterns.md` explicitly
  bans `.Add<IFoo>(ctx => new Foo(ctx.Resolve<...>()))` and `IComponentContext` used outside
  wiring as anti-patterns — the container is meant to see constructor deps directly.
- **Step-override-by-base-type + ctor-API-type selection is subtle.** `EthereumStepsLoader`
  picking the step whose constructor takes the most-derived API type
  (`StepInfoByAssignabilityComparer`) is powerful but non-obvious; the comparer's header notes
  it was rewritten to fix a reflexivity/antisymmetry bug in the original lambda — a real
  correctness footgun in this area.
- **Concurrent step init hides ordering bugs.** Because independent steps run in parallel, an
  *undeclared* dependency can pass most of the time and fail under scheduling races; the DAG
  is only as correct as the declared `Dependencies`/`Dependents`.
- **Plugins instantiated in a throwaway container.** `PluginLoader.LoadPlugins` builds a
  separate short-lived Autofac container just to construct plugin instances (config +
  chainspec injected); their real `IModule` contributions land in the main container later.
  Two-phase, easy to conflate.
- **Exactly one consensus plugin.** More than one enabled `IConsensusPlugin` throws
  (`PluginLoader.cs:165`) — a hard single-active-family constraint fukuii's registry must
  decide whether to mirror.
