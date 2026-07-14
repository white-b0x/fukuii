# nethermind — consensus-engines

_Commit/branch documented: `0d09a09edd0a861d21c647ceaa7f9f5ea1c74255` (branch `upstream`,
`2026-07-01`). Vendored read-only at `.claude/repo-references/clients/nethermind`.
Documented 2026-07-13. Read-only research; no fukuii source touched._

_Folds in the B7.0 engine-axis research (`.local/docs/research-july/b7.0-engine-axis-decision.md`,
2026-07-13), which already banked nethermind's `IConsensusPlugin` / `SealEngineType` /
reflection-discovery / multi-engine-guard / `MergePlugin` co-activation findings — cited and
expanded here into the full subsystem rather than re-derived._

## Architecture summary

nethermind is the **self-declaring-plugin-architecture authority**: it has the strongest "add a
consensus family without editing shared dispatch code" pattern of the six reference clients. There is
**no central switch, type-switch, or `ProtocolSchedule` decorator to edit** when a new mechanism is
added — each consensus mechanism ships as **its own assembly** (`Nethermind.Consensus.Ethash`,
`Nethermind.Consensus.Clique`, `Nethermind.Consensus.AuRa`, `Nethermind.Merge.Plugin`, plus the
L2/alt-chain families `Nethermind.Optimism`, `Nethermind.Taiko`, `Nethermind.Xdc` as **peer**
plugins) exposing an `IConsensusPlugin : INethermindPlugin`. Each plugin **self-declares its own
applicability** via `bool Enabled => chainSpec.SealEngineType == <its own string tag>`; the framework
enumerates all discovered plugins, keeps the enabled ones, and wires the winner's Autofac `IModule`
into the DI container. Adding a family = one new assembly with a plugin class + an engine-parameters
class; **zero edits to any shared file**.

Two orthogonal layers, same as besu but encoded differently:
1. **Mechanism selection** — driven by `ChainSpec.SealEngineType`, a **flat `string` tag** (not a
   closed enum). `ChainSpecParametersProvider.CalculateSealEngineType()` derives it from *which*
   `IChainSpecEngineParameters` object the parsed chain-spec produced, throwing if more than one
   claims a seal engine — the exact uniqueness guard fukuii's B7.0-a ported. A second, independent
   guard in `PluginLoader.LoadPlugins` throws if `>1` *enabled* `IConsensusPlugin` survives.
2. **Fork/hardfork dispatch** — each engine-parameters object contributes its transition points to a
   single sorted milestone set via `AddTransitions(SortedSet<ulong> blockNumbers, SortedSet<ulong>
   timestamps)` — one method that carries **both** the block-number and timestamp axes, so a family
   need not know which axis a fork gates on.

**The merge is a *composing* plugin, not a wrapper.** `MergePlugin` is an `INethermindPlugin` (**not**
an `IConsensusPlugin`, so it never trips the "only one consensus plugin" guard) that activates
**alongside** whichever base plugin is enabled, keyed on `MergeEnabled` (base seal engine is
`BeaconChain | Clique | Ethash` and `mergeConfig.Enabled`). It layers PoS components onto the base
engine's DI registrations via Autofac decorators, rather than one plugin instantiating/wrapping the
other (contrast geth's `beacon.New(ethone)` and besu's `TransitionProtocolSchedule`). The
AuRa→PoS path is a dedicated `AuRaMergePlugin : MergePlugin` peer.

## Key types / interfaces / files

### The plugin abstraction (the headline pattern)
- `Nethermind.Api/Extensions/INethermindPlugin.cs:9-27` — the base plugin contract:
  `Name`/`Description`/`Author`, lifecycle hooks `InitTxTypesAndRlpDecoders` / `Init` /
  `InitNetworkProtocol` / `InitRpcModules` (all default no-ops), `bool MustInitialize`, **`bool
  Enabled { get; }`** (the self-declaration seam), and `IModule? Module` (the Autofac module the
  plugin contributes to the DI container). Every capability — not just consensus — is a plugin.
- `Nethermind.Api/Extensions/IConsensusPlugin.cs:8-11` — **`IConsensusPlugin : INethermindPlugin`**,
  adding only `Type ApiType => typeof(NethermindApi)`. The marker sub-interface that the uniqueness
  guard counts (`plugins.OfType<IConsensusPlugin>()`). A consensus mechanism = an `IConsensusPlugin`;
  a *composing* layer like merge stays a bare `INethermindPlugin`.
- `Nethermind.Consensus.Ethash/EthashPlugin.cs:15-30` — the canonical minimal consensus plugin:
  `Name => SealEngineType` (`:17`), **`Enabled => chainSpec.SealEngineType == SealEngineType`**
  (`:23`, the self-declared applicability test), `SealEngineType => Core.SealEngineType.Ethash`
  (`:27`), and `Module => new EthHashModule(...)` (`:29`) that `AddSingleton`s the mechanism's
  `IRewardCalculatorSource` / `IDifficultyCalculator` / `ISealValidator` / block-producer factory
  (`:32-54`). PoW mining components are added only `if (miningConfig.Enabled)` (`:49-52`).
- `Nethermind.Consensus.Clique/CliquePlugin.cs:16-97` — same shape (`Enabled` `:24`,
  `SealEngineType => Clique` `:58`), plus a real `InitRpcModules` that registers `CliqueRpcModule`
  **only if `SealEngineType == Clique`** (`:39-56`) and a `CliqueModule` that maps the
  `CliqueChainSpecEngineParameters` out of the chain-spec provider and decorates `ICliqueConfig` with
  its `Period`/`Epoch` (`:74-84`).
- `Nethermind.Consensus.AuRa/AuRaPlugin.cs:41-58` — AuRa plugin; note it overrides
  `Type ApiType => typeof(AuRaNethermindApi)` (`:57`) — a mechanism may demand a **richer API type**,
  and the plugin declares it. Its `AuRaModule` (`:60-149`) is the heaviest, wiring validator store,
  finalization manager, step calculator, reporting validator, gas-limit override, and merge-aware
  block processing — proof that "a family is a module" scales to a complex PoA.
- `Nethermind.Consensus.Ethash/NethDevPlugin.cs:15-33` — the `NethDev` instant-seal dev engine, a
  fourth base `IConsensusPlugin` selected by `SealEngineType == NethDev`.

### The seal-engine string tag (open, not a closed enum)
- `Nethermind.Core/SealEngineType.cs:6-16` — **`static class SealEngineType`**, a set of `const
  string` tags: `None`/`AuRa`/`Clique`/`NethDev`/`Ethash`/`BeaconChain`/`Optimism`/`Taiko`. It is a
  **flat string namespace, not an `enum`** — so a new family can pick an unused string with **no core
  edit**. Proof: `Nethermind.Xdc/XdcConstants.cs:12-13` declares `XDPoS`/`XDPoSSubnet` **outside**
  this file entirely, and `XdcPlugin.cs:19-20` uses `SealEngineType => XdcConstants.XDPoS` — a full
  consensus family whose seal-engine tag the core `SealEngineType` class has never heard of.

### Mechanism selection & the two uniqueness guards
- `Nethermind.Specs/ChainSpecStyle/IChainSpecEngineParameters.cs:8-15` — the per-family config
  contract: `EngineName` (the chain-spec key it hydrates from), **`SealEngineType`** (the tag it
  claims, or `null` for a non-seal-engine parameter block), `ApplyToChainSpec`, **`AddTransitions(
  SortedSet<ulong> blockNumbers, SortedSet<ulong> timestamps)`** (fork-dispatch contribution across
  both axes), and `ApplyToReleaseSpec`.
- `Nethermind.Consensus.Clique/CliqueChainSpecEngineParameters.cs:9-16` — a concrete impl:
  `EngineName => SealEngineType` (`:11`), `SealEngineType => Core.SealEngineType.Clique` (`:12`), plus
  the mechanism's own params (`Epoch`, `Period`, `Reward`). Every family ships one of these
  (`AuRa`/`Ethash`/`NethDev`/`Optimism`/`Taiko`/`Xdc` all have a sibling).
- `Nethermind.Specs/ChainSpecStyle/ChainSpecParametersProvider.cs:32-49` —
  **`CalculateSealEngineType()`**: iterates every instantiated `IChainSpecEngineParameters`, and if
  **two** have a non-null `SealEngineType` throws `"Multiple seal engines in chain spec"` (`:41`);
  if none, throws `"No seal engine in chain spec"` (`:48`). **This is the guard fukuii ported in
  B7.0-a.** The seal engine is *derived from the config*, never from an external `NetworkType`.
- `ChainSpecParametersProvider.cs:51-62` — **`InitializeInstances()`**: reflection discovery —
  `TypeDiscovery.FindNethermindBasedTypes(typeof(IChainSpecEngineParameters))` finds every impl
  across all loaded assemblies, instantiates each, and hydrates the ones whose `EngineName` key is
  present in the parsed chain-spec JSON. **A new family's parameters object is found automatically**;
  nothing enumerates them by hand.
- `Nethermind.Api/Extensions/PluginLoader.cs:125-173` — **`LoadPlugins`**: builds each discovered
  plugin type via Autofac, keeps only those with `plugin.Enabled` (`:154-157`), and then enforces the
  **second** guard: `if (plugins.OfType<IConsensusPlugin>().Count() > 1) throw ... "Only one
  consensus plugin can be enabled at any one time"` (`:165-170`). Two independent nets catch a
  mis-specified chain: one at chain-spec parse, one at plugin resolution.

### Plugin discovery, ordering & composition (the DI lifecycle)
- `Nethermind.Api/Extensions/PluginLoader.cs:30-91` — **`Load()`**: seeds the type list from an
  **embedded** list, then scans the `plugins/` directory for `*.dll`, `LoadFromAssemblyPath`s each,
  and adds every exported non-interface type assignable to `INethermindPlugin` (`:74-84`). So plugins
  are both compiled-in **and** drop-in discoverable from disk.
- `Nethermind.Runner/NethermindPlugins.cs:9-31` — **`EmbeddedPlugins`**, the compiled-in list:
  `AuRaPlugin`, `CliquePlugin`, `EthashPlugin`, `NethDevPlugin`, `AuRaMergePlugin`, `MergePlugin`,
  `OptimismPlugin`, `TaikoPlugin`, `XdcPlugin`, `XdcSubnetPlugin`, plus non-consensus plugins
  (`HealthChecks`, `Hive`, `Snapshot`, `UPnP`, `Flashbots`, `Shutter`, …). The **only** file that
  names the consensus families — and it's a flat list, not a dispatch tree.
- `Nethermind.Runner/Program.cs:118-136` — construction: `new PluginLoader("plugins", …,
  NethermindPlugins.EmbeddedPlugins)` then `pluginLoader.Load()`, then
  `TypeDiscovery.Initialize(typeof(INethermindPlugin))`. `Program.cs:164` calls
  `pluginLoader.OrderPlugins(pluginConfig)`.
- `PluginLoader.cs:93-123` — **`OrderPlugins`**: sorts by a configured `PluginOrder` priority list,
  falling back to alphabetical for un-prioritized plugins. Ordering matters because composing plugins
  must initialize in a defined sequence.
- `Nethermind.Api/Extensions/PluginConfig.cs:8` — the default order:
  `HealthChecks, Clique, Aura, Ethash, Optimism, Shutter, Taiko, AuRaMerge, Merge, Flashbots, MEV,
  Hive`. **`AuRaMerge` is ordered before `Merge`** (its `<remarks>` at `AuRaMergePlugin.cs:36`
  demands it) so the more-specific composing plugin wins for AuRa chains.

### Merge as a composing (co-activating) plugin
- `Nethermind.Merge.Plugin/MergePlugin.cs:49-67` — **`MergePlugin(ChainSpec, IMergeConfig) :
  INethermindPlugin`** (deliberately **not** `IConsensusPlugin`). `MergeEnabled => mergeConfig.Enabled
  && chainSpec.SealEngineType is BeaconChain or Clique or Ethash` (`:64-65`); `Enabled => MergeEnabled`
  (`:67`). Because it's a bare `INethermindPlugin`, it can be enabled **at the same time** as the base
  Ethash/Clique consensus plugin without violating the single-consensus-plugin guard.
- `MergePlugin.cs:185` — **`HasTtd() => _api.SpecProvider?.TerminalTotalDifficulty is not null ||
  mergeConfig.TerminalTotalDifficulty is not null`** — the never-merging-chain escape hatch. Even
  when the plugin is loaded, TTD-dependent wiring (Engine-API JSON-RPC enforcement,
  `EnsureJsonRpcUrl` `:139-168`) is skipped when no TTD is set (`:141-142`), so a permanently-PoW
  chain isn't force-migrated. `MustInitialize => true` (`:197`).
- `MergePlugin.cs:99-101` — the composition mechanism: `Init` **decorates** the base engine's runtime
  — wraps `_api.GossipPolicy` in a `MergeGossipPolicy` and prepends a `MergeProcessingRecoveryStep` to
  the block-preprocessor — layering PoS behavior onto the base plugin's registrations rather than
  replacing them.
- `Nethermind.Merge.AuRa/AuRaMergePlugin.cs:37-60` — **`AuRaMergePlugin : MergePlugin`**, the
  AuRa→PoS peer. `MergeEnabled => _mergeConfig.Enabled && _chainSpec.SealEngineType ==
  SealEngineType.AuRa` (`:44`). Its `AuRaMergeModule` (`:68-106`) `AddDecorator`s the AuRa engine's
  `IBlockProducerFactory` / `IHeaderValidator` / `IUnclesValidator` / `ISealValidator` / `ISealer`
  with the `Merge*` variants (`:83-96`) and swaps in `AuRaMergeBlockProcessor` — the concrete proof
  that "merge composes over **any** base engine," not just Ethash.

### L2 / alt-chain families as PEER plugins
- `Nethermind.Optimism/OptimismPlugin.cs:46-59` — **`OptimismPlugin : IConsensusPlugin`**,
  `Enabled => chainSpec.SealEngineType == SealEngineType` (`:55`), `SealEngineType =>
  Core.SealEngineType.Optimism` (`:59`). Optimism is a **peer** consensus family, selected by its own
  string tag, not a wrapper over Ethash/Merge.
- `Nethermind.Taiko/TaikoPlugin.cs:48-122` — same peer pattern, `SealEngineType => Taiko` (`:122`).
- `Nethermind.Xdc/XdcPlugin.cs:12-20` — same, with the seal-engine tag (`XDPoS`) that lives entirely
  outside `Core.SealEngineType` — the definitive proof that a new family needs **no** core edit.

## Design decisions & rationale

- **Self-declaring plugins over central dispatch.** Rather than a `switch`/decorator/registry that a
  human edits per family (geth's configurator fallthrough, erigon's type-switch, besu's
  `BesuController` dispatch), nethermind inverts control: each mechanism declares `Enabled` against a
  string tag and the framework selects the one that matches. The cost of a new family is a new
  assembly, not a diff to shared code — the single strongest "open for extension, closed for
  modification" realization among the reference clients.
- **Reflection discovery + embedded list = drop-in *and* compiled-in.** `TypeDiscovery` +
  `PluginLoader.Load()`'s `plugins/` dir scan let a third party ship a consensus family as a DLL with
  zero recompilation, while `EmbeddedPlugins` compiles the first-party families in. The extension
  point is real, not theoretical.
- **Flat string seal-engine tag, not a closed enum.** `SealEngineType` as `const string`s means the
  discriminant space is open; `XDPoS` proves a family can add a tag the core never enumerated. An
  `enum` would force a central edit per family — exactly the closed-world trap fukuii's `NetworkType`
  binary is.
- **Two independent uniqueness guards.** The chain-spec-parse guard
  (`CalculateSealEngineType`, "Multiple seal engines") and the plugin-resolution guard
  (`LoadPlugins`, "Only one consensus plugin") catch a mis-specified chain at two different layers —
  defense in depth for the one invariant that must hold (exactly one base consensus mechanism).
- **Merge composes, it does not wrap.** Keeping `MergePlugin` a bare `INethermindPlugin` that
  co-activates and *decorates* the base engine's DI registrations (rather than a `beacon.New(inner)`
  wrapper object) means the merge is orthogonal to the base mechanism — the same `MergePlugin` layers
  over Ethash, Clique, or a beacon-genesis chain, and `AuRaMergePlugin` reuses it for AuRa. TTD-gating
  via `HasTtd()` keeps a never-merging chain unforced.
- **A plugin may demand a richer API surface.** `AuRaPlugin.ApiType => typeof(AuRaNethermindApi)`
  lets a mechanism escalate the shared `NethermindApi` to a family-specific one — the plugin, not the
  core, decides.

## Notable patterns (the reusable idea)

1. **Self-declaring consensus-family plugin registry (the headline, the B7.0.5 reference).** A
   family = an assembly exposing `IConsensusPlugin` with `Enabled => chainSpec.SealEngineType == <its
   own tag>`; the framework enumerates, filters by `Enabled`, and wires the winner. New family = new
   module, **no central-dispatch edit**. Proven live across `ethash`/`clique`/`aura`/`nethdev`/
   `merge`/`optimism`/`taiko`/`xdc`. This is the pattern fukuii's B7.0.5 `NetworkFamily` registry aims
   at — portable to Scala as a **given-based typeclass registry** or a `ServiceLoader`.
2. **Flat string discriminant (not a closed enum) + reflection discovery.** `SealEngineType` const
   strings + `TypeDiscovery.FindNethermindBasedTypes` = an open discriminant space where a new tag
   (`XDPoS`) needs no core edit. The anti-`NetworkType`-binary shape.
3. **Config-derived seal engine + double uniqueness guard.** Derive the discriminant from *which*
   `IChainSpecEngineParameters` the chain-spec produced, and reject `>1` at both parse
   (`CalculateSealEngineType`) and plugin-resolution (`LoadPlugins`) layers. The parse-time guard is
   the direct port fukuii's B7.0-a took.
4. **Composing (co-activating) merge plugin, TTD-gated.** A non-consensus `INethermindPlugin` that
   activates alongside the base consensus plugin and decorates its DI registrations, keyed on
   `MergeEnabled` (base seal engine ∈ {BeaconChain, Clique, Ethash}) and gated on `HasTtd()` for
   never-merging chains — contrast geth's wrapper object and besu's `TransitionProtocolSchedule`.
5. **Unified fork-dispatch contribution across both axes.** `IChainSpecEngineParameters.AddTransitions(
   SortedSet<ulong> blockNumbers, SortedSet<ulong> timestamps)` — one method carrying both the
   block-number and timestamp fork axes into a single milestone set, so a family need not care which
   axis a fork gates on (the seam fukuii's twin `EvmConfig.forBlock` overloads occupy).

## Authority note

**nethermind is THE self-declaring-plugin-architecture authority** — per the Phase-0 authority model
("nethermind — plugin architecture (self-declaring consensus/family plugins)"). This file is **the
direct reference for fukuii's B7.0.5 `NetworkFamily` registry**: the strongest evidence that a family
can self-register without central-dispatch edits, proven across seven-plus mechanisms
(ethash/clique/aura/nethdev/merge/optimism/taiko/xdc). It is the pole the besu and erigon
`consensus-engines` docs forward-referenced when they described *closed*-dispatch approaches
(besu's `BesuController` `switch`, erigon's `CreateRulesEngine` type-switch) — nethermind is the
open-dispatch contrast.

nethermind is a **strong ETH-family cross-check** (Ethash PoW, the merge, the full mainnet fork
schedule via `ApplyToReleaseSpec`), secondary to go-ethereum for canonical ETH baseline. Its **AuRa**
and **Clique** plugins are a secondary PoA reference (besu remains the **primary** multi-consensus/PoA
authority for B7.1 Clique / B7.2 IBFT-QBFT, with the richer `ValidatorProvider`/`BlockInterface`/
`Sealer` seams). nethermind is **not** the ETC/PoW authority — that is core-geth
(ETChash/ECIP-1017/1099/1111/1122); nethermind has no ECIP awareness.

**The trade-off to note for fukuii.** The plugin model buys extensibility at the cost of **reflection
+ DI-container indirection** (`TypeDiscovery`, Autofac `IModule`s, `AssemblyLoadContext`), which is
idiomatic in C#/.NET but **less idiomatic in Scala/Pekko**. The portable form is a compile-time,
type-safe analogue: a **given-based typeclass registry** (each family provides a `given
NetworkFamily` instance) or a `ServiceLoader`-discovered set, rather than runtime reflection.
nethermind's *uniqueness guard* (§A.6 of the B7.0 brief) is portable **now**, independent of whether
fukuii adopts the full self-registration model — a cheap `require(sealEngines.size == 1)` at config
parse.

## Gotchas / anti-patterns / things they later changed

- **`SealEngineType` is a string, not an `enum` — no compile-time exhaustiveness.** The open
  discriminant that buys extensibility also means a typo'd tag silently matches nothing and the node
  fails at the "No seal engine in chain spec" guard rather than at compile time. A Scala port using a
  sealed trait / typeclass would recover exhaustiveness the C# design forgoes.
- **Selection depends on reflection + assembly load order.** `TypeDiscovery` /
  `AssemblyLoadContext.Default.LoadFromAssemblyPath` mean the set of candidate families is a runtime
  property of what DLLs are on disk in `plugins/` — powerful for drop-in extensions, but the
  dependency graph is implicit and not statically checkable. Do not copy the *reflection* mechanism
  into Scala; copy the *self-declaration* idea with a static registry.
- **Two guards, two error sites for the same invariant.** "Multiple seal engines"
  (`ChainSpecParametersProvider:41`) and "Only one consensus plugin" (`PluginLoader:167`) both enforce
  single-consensus, at different layers — a mis-specified chain can fail at either, so operators must
  know both messages. fukuii should port **one** clearly-sited guard, not replicate both.
- **`MergePlugin` being an `INethermindPlugin` (not `IConsensusPlugin`) is load-bearing but subtle.**
  It's what lets merge co-activate without tripping the single-consensus guard — but it means "is
  this a consensus mechanism?" is answered by *which interface* a plugin implements, an implicit
  convention a reader must know. Document the invariant explicitly if porting.
- **Plugin ordering is a hand-maintained string list.** `PluginConfig.PluginOrder` (`:8`) hard-codes
  the priority order, and `AuRaMerge` must precede `Merge` (`AuRaMergePlugin.cs:36`) or the wrong
  composing plugin wins. The self-declaration is clean, but *composition ordering* is still a manual,
  stringly-typed list — a residual central-config point the plugin model didn't fully eliminate.
