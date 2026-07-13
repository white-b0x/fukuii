# nethermind — multi-network

_Commit/branch documented: `0d09a09edd0a861d21c647ceaa7f9f5ea1c74255` (branch `upstream`, =
`origin/upstream`). Vendored read-only at `.claude/repo-references/clients/nethermind`.
Documented 2026-07-13. Read-only research; no fukuii source touched._

_Companion to `nethermind/consensus-engines.md` and `nethermind/storage-persistence.md`
(documented in parallel) — this file is the network-selection / chain-spec / family-wiring
half of the story, focused on the **reflection-discovery engine-parameters registry**._

## Architecture summary

nethermind is the **runtime self-declaring plugin-registry pole** of the family-abstraction
spectrum (geth single-family → core-geth config-schema → besu closed-if/else → erigon
compile-time-module-registry → **nethermind runtime-plugin-registry** → reth generics-SDK). Where
besu hard-codes an if/else over genesis keys (`../besu/multi-network.md`) and erigon registers
networks into a map via `init()`+blank-import (`../erigon/multi-network.md`), nethermind does the
one thing neither does: it **assembly-scans at runtime for every class implementing a
consensus-config interface and instantiates only the ones the loaded chainspec actually names** —
adding an engine's configuration is one new *loadable* class, **zero central-registry edits**.

There are **two cooperating discovery tiers**, and the distinction is the key nuance:

1. **Config-parameters tier — genuinely reflection-discovered, zero-edit.**
   `IChainSpecEngineParameters` (`Nethermind.Specs/ChainSpecStyle/IChainSpecEngineParameters.cs:8-15`)
   is implemented by each engine's chain-spec params class (`EthashChainSpecEngineParameters`,
   `CliqueChainSpecEngineParameters`, `AuRaChainSpecEngineParameters`,
   `OptimismChainSpecEngineParameters`, `TaikoChainSpecEngineParameters`,
   `XdcChainSpecEngineParameters`, `NethDevChainSpecEngineParameters` — all peers).
   `ChainSpecParametersProvider.InitializeInstances()`
   (`Nethermind.Specs/ChainSpecStyle/ChainSpecParametersProvider.cs:51-62`) calls
   `TypeDiscovery.FindNethermindBasedTypes(typeof(IChainSpecEngineParameters))`
   (`Nethermind.Core/TypeDiscovery.cs:127-142`), assembly-scans, `Activator.CreateInstance`s each
   discovered type, matches its `EngineName` against a key in the loaded chainspec's `engine`
   block (`:57`), and deserializes only the ones present (`:59`). No registry, no list, no `switch`.
2. **Consensus-subsystem tier — self-enabling plugins, embedded-list + runtime-DLL.**
   `IConsensusPlugin` implementations (`EthashPlugin`, `CliquePlugin`, `AuRaPlugin`, `MergePlugin`,
   `OptimismPlugin`, `TaikoPlugin`, …) each self-enable via
   `Enabled => chainSpec.SealEngineType == SealEngineType`
   (`Nethermind.Consensus.Ethash/EthashPlugin.cs:23`) and contribute an Autofac `IModule`. These
   are loaded from a hand-maintained embedded compile-time list
   (`Nethermind.Runner/NethermindPlugins.cs:11-31`) **plus** genuine runtime `.dll` discovery from
   the plugins directory (`Nethermind.Api/Extensions/PluginLoader.cs:53-84`) — drop a DLL, it's
   found, no recompile.

So the reflection-discovery purity is **strongest at the config-params tier** (truly zero-edit)
and *partial* at the subsystem tier (first-party plugins are an embedded list, external ones are
runtime DLLs). This is the honest positioning: nethermind is the cleanest self-declaring model of
all clients documented, but the "add a family with no central edit anywhere" claim holds fully only
for the chain-spec parameters, not for wiring in a brand-new first-party consensus subsystem.

## Key types / interfaces / files

### The engine-parameters interface (what every engine implements)
- `Nethermind.Specs/ChainSpecStyle/IChainSpecEngineParameters.cs:8-15` — **`IChainSpecEngineParameters`**,
  the whole abstraction, five members (three with default no-op bodies):
  - `EngineName` (`:10`) — the chainspec `engine`-block key this class binds to (e.g. `"Ethash"`,
    `"AuthorityRound"`, `"clique"`, `"Optimism"`). The *discovery key*.
  - `SealEngineType` (`:11`) — the canonical seal-engine identity string
    (`Nethermind.Core/SealEngineType.cs`: `None`/`AuRa`/`Clique`/`NethDev`/`Ethash`/`BeaconChain`/
    `Optimism`/`Taiko`). What the consensus-plugin tier matches on.
  - `ApplyToChainSpec(ChainSpec)` (`:12`) — write engine-specific fields onto the shared `ChainSpec`
    (e.g. Ethash sets `HomesteadBlockNumber`, glacier bomb-delay blocks).
  - `AddTransitions(SortedSet<ulong> blockNumbers, SortedSet<ulong> timestamps)` (`:13`) — **the
    unified fork-dispatch contribution** (see its own section below).
  - `ApplyToReleaseSpec(ReleaseSpec, ulong startBlock, ulong? startTimestamp)` (`:14`) — given a
    fork boundary, mutate the release spec with this engine's rules.
- `Nethermind.Core/SealEngineType.cs` — the seal-engine constant set. `Ethash`/`Clique`/`AuRa` are
  the traditional trio; `Optimism`/`Taiko`/`BeaconChain`/`NethDev` extend it. besu's `PowAlgorithm`
  enum is ethash-only by comparison (`../besu/multi-network.md`); nethermind names each engine.

### The reflection-discovery provider (the registry that isn't a registry)
- `Nethermind.Specs/ChainSpecStyle/ChainSpecParametersProvider.cs:51-62` — **`InitializeInstances()`**:
  `TypeDiscovery.FindNethermindBasedTypes(typeof(IChainSpecEngineParameters)).Where(x => x.IsClass)`
  → for each type, `Activator.CreateInstance`, read `EngineName`, and **only if the chainspec's
  `engine` block contains that key** deserialize the JSON into an instance stored by `Type` (`:57-59`).
  Engines absent from the chainspec are never instantiated. This is the crux the prompt names: adding
  a config class that is *loadable* is sufficient; nothing central changes.
- `ChainSpecParametersProvider.cs:23-30` — constructor: copies the engine-parameter dictionary with
  `StringComparer.InvariantCultureIgnoreCase` (`:25`) — so `"authorityRound"` in gnosis's chainspec
  matches `AuRaChainSpecEngineParameters.EngineName == "AuthorityRound"` case-insensitively — then
  `InitializeInstances()` + `CalculateSealEngineType()`.
- `ChainSpecParametersProvider.cs:32-49` — **`CalculateSealEngineType()`**: walks the instantiated
  set; **throws `"Multiple seal engines in chain spec"` on >1** (`:41`) and **throws `"No seal engine
  in chain spec"` on 0** (`:48`). This is the positive ambiguity guard besu and geth lack
  (`../besu/multi-network.md` §Gotchas: "no multi-mechanism ambiguity guard") — **the guard fukuii
  ported in B7.0-a**.
- `ChainSpecParametersProvider.cs:64-66` — **`AllChainSpecParameters`** (the instantiated engines) +
  **`GetChainSpecParameters<T>()`** (typed lookup used by consensus modules to pull their own params).
- `Nethermind.Specs/ChainSpecStyle/IChainSpecParametersProvider.cs:8-13` — the interface: `SealEngineType`
  + `AllChainSpecParameters` + `GetChainSpecParameters<T>()`.
- `Nethermind.Core/TypeDiscovery.cs:127-142` — **`FindNethermindBasedTypes(baseType)`**: `Initialize()`
  (walks `AssemblyLoadContext.Default`, transitively loading every `Nethermind*` assembly, `:33-84`)
  then `assemblies.SelectMany(a => a.GetExportedTypes().Where(t => baseType.IsAssignableFrom(t) && t
  != baseType))` (`:141-142`). The assembly-scan engine behind the whole self-declaring model.

### The engine implementations (all peers of one interface)
- `Nethermind.Consensus.Ethash/EthashChainSpecEngineParameters.cs:15-101` — **PoW**. `EngineName =
  SealEngineType = "Ethash"` (`:17-18`). Block-number config (`HomesteadTransition`,
  `DaoHardforkTransition`, `DifficultyBombDelays`, `BlockReward`). `AddTransitions` adds only
  **blockNumbers** (`:35-56`); `ApplyToChainSpec` writes glacier/homestead/DAO block numbers (`:94-101`).
- `Nethermind.Consensus.Clique/CliqueChainSpecEngineParameters.cs:9-16` — **PoA (Clique)**. `EngineName
  = SealEngineType = "Clique"` (`:11-12`), just `Epoch`/`Period`/`Reward`. Tiny — a PoA network's whole
  config surface is three fields. (nethermind, unlike modern besu, still *runs* Clique — see Authority.)
- `Nethermind.Consensus.AuRa/Config/AuRaChainSpecEngineParameters.cs:20-23+` — **PoA (AuthorityRound)**.
  Note the split: `EngineName => "AuthorityRound"` (the chainspec key) but `SealEngineType => "AuRa"`
  (the internal identity) — the two identities can differ. Rich config: `StepDuration`, validator
  sets, block-reward contracts, POSDAO transition.
- `Nethermind.Optimism/OptimismChainSpecEngineParameters.cs:13-81` — **L2 (OP Stack)**. `EngineName =
  SealEngineType = "Optimism"` (`:15-16`). Its forks are **timestamps** (`RegolithTimestamp`…
  `KarstTimestamp`), so `AddTransitions` adds only **timestamps** (`:65-72`), and `ApplyToReleaseSpec`
  installs an OP-specific `BaseFeeCalculator` (`:58`). The clean contrast with Ethash: *same interface,
  opposite fork-dispatch axis* — no separate method, no branch.
- Further peers confirming the open set: `Nethermind.Taiko/TaikoSpec/TaikoChainSpecEngineParameters.cs`,
  `Nethermind.Xdc/Spec/XdcChainSpecEngineParameters.cs`, `Nethermind.Consensus.Ethash/
  NethDevChainSpecEngineParameters.cs`, `Nethermind.Optimism/CL/CLChainSpecEngineParameters.cs`.

### `AddTransitions` — block-number AND timestamp dispatch unified in one call
- `Nethermind.Specs/ChainSpecStyle/ChainSpecBasedSpecProvider.cs:34-51` — **`BuildTransitions()`**:
  seeds `{0}`, then for every discovered engine calls `item.AddTransitions(transitionBlockNumbers,
  transitionTimestamps)` (`:40-43`) — each engine drops its own fork boundaries into the shared
  block-number set, the shared timestamp set, or both. It *then* reflection-scans property names
  ending `"BlockNumber"` / `"Transition"` (→ block set) and `"TransitionTimestamp"` (→ timestamp set,
  `:46-48`). So a network's complete fork schedule is the union of (a) engine contributions and (b)
  name-convention scanning — no engine has to know whether its sibling dispatches by block or time.
- `Nethermind.Consensus.Ethash/EthashChainSpecEngineParameters.cs:35-56` vs
  `Nethermind.Optimism/OptimismChainSpecEngineParameters.cs:65-72` — the concrete proof: Ethash adds
  to `blockNumbers`, Optimism adds to `timestamps`, through the *identical* signature. This is the
  **direct reference for fukuii's `EvmConfig.forBlock(block)` vs `forBlock(block, timestamp)` overload
  split** (`AGENTS.md`: block-number fork dispatch for PoW/ETC vs timestamp overlay for PoS/ETH) — a
  candidate unification: one `addTransitions(blockNumbers, timestamps)` seam each family participates
  in, instead of two overloads the caller must choose between.
- `ChainSpecBasedSpecProvider.cs:358-362` — **`ApplyToReleaseSpec` loop**: for a given fork block, each
  engine's `ApplyToReleaseSpec(releaseSpec, block, timestamp)` mutates the release spec — the
  per-boundary counterpart to `AddTransitions`.

### The chainspec format & how a network is selected
- `Nethermind.Specs/ChainSpecStyle/Json/ChainSpecJson.cs:18,25-29` — **`EngineJson.CustomEngineData`
  carries `[JsonExtensionData]`** (`:27`). This is why the model is open: *any* key inside the
  chainspec's `engine` object is captured into a `Dictionary<string, JsonElement>`, then matched
  against discovered engines' `EngineName`s. There is no fixed `engine` schema to edit when adding a
  family. nethermind's chainspec is **Parity/OpenEthereum lineage** (`engine`/`params`/`genesis`/
  `accounts`), *not* geth's genesis JSON.
- `Nethermind.Specs/ChainSpecStyle/ChainSpecLoader.cs:306-322` — **`LoadEngine`**: builds the
  `engineParameters` dict from `chainSpecJson.Engine.CustomEngineData` (unwrapping the `params`
  sub-object per key, `:308-310`), constructs `new ChainSpecParametersProvider(engineParameters,
  serializer)` (`:312`), and derives `SealEngineType` from it — **throwing `"unknown seal engine in
  chainspec"` if none resolves** (`:318-321`).
- `ChainSpecLoader.cs:44-61` — `InitChainSpecFrom`: `networkId = Params.NetworkId ?? Params.ChainId ??
  1`; `ChainId = Params.ChainId ?? networkId` (`:44-48`) — **network-id and chain-id are separable**
  (like erigon), defaulting to each other. Then Params → Genesis → **Engine** → Allocations →
  Bootnodes → Transitions.
- `ChainSpecLoader.cs:296-303` — after transitions load, every discovered engine's `ApplyToChainSpec`
  runs, letting each write its fields onto the shared `ChainSpec`.
- `Nethermind.Specs/ChainSpecStyle/ChainSpec.cs:35,39` — **`ChainSpec.SealEngineType`** (the resolved
  engine) + **`ChainSpec.EngineChainSpecParametersProvider`** (the live provider consensus modules pull
  typed params from).
- Real engine blocks: `Chains/foundation.json` → `"engine": { "Ethash": { "params": {…} } }`;
  `Chains/gnosis.json` → `"engine": { "authorityRound": {…} }`. The key *is* the family selector.
- `Nethermind.Runner/configs/mainnet.json` → `Init.ChainSpecPath = "chainspec/foundation.json"` — the
  operator picks a **config** (`--config mainnet`); the config names the **chainspec**; the chainspec's
  `engine` block declares the **family**. Three levels: config → chainspec → engine.

### The consensus-subsystem plugin tier (self-enabling, not fully edit-free)
- `Nethermind.Consensus.Ethash/EthashPlugin.cs:15-29` — `EthashPlugin : IConsensusPlugin`, `Enabled =>
  chainSpec.SealEngineType == SealEngineType` (`:23`), exposes an Autofac `IModule` (`:29`) that wires
  reward calculator / difficulty / sealer. The plugin activates itself off the chainspec-resolved seal
  engine — no dispatch table.
- `Nethermind.Consensus.Clique/CliquePlugin.cs:16-66` — same self-enable pattern; its `CliqueModule`
  (`:69+`) `Map`s `CliqueChainSpecEngineParameters` out of `EngineChainSpecParametersProvider
  .GetChainSpecParameters<T>()` — the two tiers meet here (subsystem pulls its own reflection-discovered
  config).
- `Nethermind.Api/Extensions/PluginLoader.cs:30-90` — loads the **embedded** list first (`:32-36`) then
  scans the plugins directory for `*.dll`, adding every exported `INethermindPlugin` type (`:53-84`) —
  the genuine runtime-DLL half.
- `Nethermind.Runner/NethermindPlugins.cs:11-31` — **`EmbeddedPlugins`**, the hand-maintained
  compile-time list (AuRa, Clique, Ethash, NethDev, Merge, AuRaMerge, Optimism, Taiko, Xdc, …). This is
  the erigon-blank-import analogue that keeps the subsystem tier from being *fully* zero-edit for
  first-party engines.

### Cross-client ingest — geth genesis as a first-class second loader
- `Nethermind.Specs/ChainSpecStyle/GethGenesisLoader.cs:60,311-359` — nethermind also loads **geth
  genesis JSON** through a *separate* `GethGenesisLoader` that builds its own
  `IChainSpecParametersProvider` hard-wired to a single `GethEthashChainSpecEngineParameters` (`:315-336,
  359`), throwing if a geth genesis tries to use a non-Ethash engine (`:331-333`). Contrast besu's
  one-way geth→besu *transform* (`../besu/multi-network.md`): nethermind keeps geth genesis as a
  parallel *loader* feeding the same provider abstraction, not a rewrite.

## Design decisions & rationale

- **Reflection discovery instead of a registry.** The defining choice: `TypeDiscovery` assembly-scans
  for `IChainSpecEngineParameters` implementers (`ChainSpecParametersProvider.cs:53`,
  `TypeDiscovery.cs:141-142`), and the chainspec's `[JsonExtensionData]` engine block
  (`ChainSpecJson.cs:27`) supplies the keys to match. Adding an engine's *config* requires **no edit
  to any central file** — not a `switch` (geth/besu), not a `map` you register into (erigon), not an
  enum. The class exists and is loadable ⇒ it participates. This is the strongest decoupling of any
  client documented.
- **A positive ambiguity guard.** `CalculateSealEngineType` throws on both >1 and 0 seal engines
  (`ChainSpecParametersProvider.cs:41,48`). nethermind refuses to silently pick the first of two
  configured engines — the exact gap called out in geth and besu (`../besu/multi-network.md` §Gotchas).
  fukuii ported this guard in B7.0-a.
- **One transition contribution, two dispatch axes.** `AddTransitions(blockNumbers, timestamps)`
  (`IChainSpecEngineParameters.cs:13`) lets a block-number engine (Ethash) and a timestamp engine
  (Optimism) coexist behind one interface, unioned in `BuildTransitions` (`ChainSpecBasedSpecProvider
  .cs:40-48`). The rationale: fork dispatch is not a per-family method choice, it's a set-union the
  provider owns — so a new family that dispatches either way needs no change to the shared machinery.
- **Seal-engine identity is separate from the discovery key.** `EngineName` (chainspec key) and
  `SealEngineType` (internal identity) are distinct members; AuRa uses `"AuthorityRound"` for the
  former and `"AuRa"` for the latter (`AuRaChainSpecEngineParameters.cs:22-23`). This decouples the
  on-disk Parity-lineage vocabulary from the code's engine identity.
- **Two tiers with different edit costs, deliberately.** Config-params discovery is pure reflection
  (zero-edit); consensus-*subsystem* plugins are an embedded list + runtime DLLs
  (`NethermindPlugins.cs`, `PluginLoader.cs`). nethermind accepts that wiring a full new consensus
  subsystem (DI module, block producer, RPC) into the first-party build is a listed plugin, while the
  *configuration* of any engine is genuinely self-declaring. The purity is at the config layer.
- **Parity chainspec as the native format, geth genesis as a peer loader.** The `engine`/`params`
  Parity lineage (`Chains/foundation.json`) is the home format; geth genesis is supported via a
  distinct loader (`GethGenesisLoader.cs`) rather than a transform, keeping both first-class.

## Notable patterns (the reusable idea)

1. **Reflection-discovered, self-declaring engine registry.** Assembly-scan for an interface, match
   each implementer's declared key against an *open* (`[JsonExtensionData]`) config block, instantiate
   only what's named. The nameable pattern for the observations table: nethermind's "add a family =
   drop in a loadable class, edit nothing central" pole — the cleanest point on the pluggability
   spectrum, strictly beyond erigon's compile-time `init()`+blank-import module registry.
2. **Positive multi-engine ambiguity guard** (`CalculateSealEngineType` throws on >1). The explicit
   rejection that geth/besu lack — a fukuii multi-family config layer wants exactly this (already
   ported, B7.0-a).
3. **Unified `AddTransitions(blockNumbers, timestamps)` fork dispatch.** One interface method a
   block-number engine and a timestamp engine both satisfy, set-unioned by the provider — the
   reference pattern for collapsing fukuii's `forBlock(block)` / `forBlock(block, timestamp)` overload
   split into a single family-neutral seam.
4. **`EngineName` (discovery key) decoupled from `SealEngineType` (identity).** The on-disk
   family-name vocabulary need not equal the code's engine identity.
5. **Self-enabling subsystem plugins** (`Enabled => chainSpec.SealEngineType == SealEngineType`) that
   contribute a DI module — the consensus subsystem activates off the resolved engine with no central
   dispatch, loaded from an embedded list + runtime DLLs.
6. **Geth genesis as a parallel loader feeding the same provider abstraction** (not a transform) —
   a cleaner cross-client-ingest shape than besu's one-way rewrite.

## Position on the pluggability spectrum

nethermind is the **runtime self-declaring plugin-registry pole** — the aspirational endpoint for
fukuii's B7.0.5:

- **vs geth / core-geth (single-family / config-schema):** not comparable on reach; nethermind's
  engine set is open and reflection-discovered, geth's is one family with a vestigial clique option and
  core-geth's is a closed 3-engine enum behind a `Configurator` (`../core-geth/multi-network.md`).
- **vs besu (closed if/else over genesis keys → hard-coded controller-builders):** nethermind removes
  the if/else entirely. besu's `fromGenesisFile` (`../besu/multi-network.md`) is a hand-maintained
  dispatch you edit to add a mechanism; nethermind's `ChainSpecParametersProvider` discovers the
  mechanism's config by assembly scan and self-enables the plugin by seal-engine match. nethermind also
  has the ambiguity guard besu lacks. besu remains the deeper *private-PoA-origination* authority
  (`generate-blockchain-config`, `extraData` validator encoding), but not the pluggability authority.
- **vs erigon (compile-time `init()`+blank-import module registry):** the closest comparison and the
  decisive one. erigon's registry is real modularity but **compile-time and leaky** — a binary that
  forgets `import _ ".../polygon/chain"` silently has no Polygon, and a bor-specific `FrozenBorBlocks`
  leaks into the shared `ChainHeaderReader` (`../erigon/multi-network.md` §Gotchas). nethermind's
  config tier needs **no import edit at all** (assembly scan), and the engine-params interface has no
  observed cross-family leak — each engine's concerns stay in its own `ApplyToReleaseSpec`/
  `ApplyToChainSpec`/`AddTransitions`. nethermind is one tier more decoupled and cleaner.
- **vs reth (generics/SDK — the next pole, forward-ref, no verdict):** reth reaches modularity through
  compile-time generics (`NodeTypes`, associated types) rather than runtime reflection — a
  type-system pole vs nethermind's reflection pole. The two are different *mechanisms* for the same
  "add a family without editing shared dispatch" goal; reth trades runtime flexibility for compile-time
  guarantees. Documented next; this file forward-refs it without a verdict.

The one honest caveat that keeps nethermind from being an unqualified "zero-edit everywhere": the
**consensus-subsystem tier** (not the config tier) still lists first-party plugins in
`NethermindPlugins.EmbeddedPlugins` (`:11-31`). The reflection purity is total for chain-spec
*parameters*, partial for wiring a new first-party consensus *subsystem*.

## Authority note

**For `multi-network`, nethermind is THE plugin-registry authority — the cleanest self-declaring
family model of every client documented — and the reference for the `AddTransitions` unified
(block-number + timestamp) fork-dispatch seam.** It is the client to study for:

- **The reflection-discovery registry** (`ChainSpecParametersProvider.InitializeInstances`,
  `TypeDiscovery.FindNethermindBasedTypes`, the `[JsonExtensionData]` open engine block) — the
  aspirational pole for **fukuii's B7.0.5** "add a family = drop in a module, no shared-dispatch edit"
  (memory: file-tree-seam-direction — the DESIGNED-not-built production-side seams).
- **The positive multi-engine ambiguity guard** (`CalculateSealEngineType`) — the pattern fukuii
  already ported in **B7.0-a**.
- **The unified fork-dispatch seam** (`AddTransitions(blockNumbers, timestamps)`) — the candidate
  unification of fukuii's `EvmConfig.forBlock(block)` vs `forBlock(block, timestamp)` overloads into
  one family-neutral call.
- **`IChainSpecEngineParameters` as the interface template** — five members
  (`EngineName`/`SealEngineType`/`ApplyToChainSpec`/`AddTransitions`/`ApplyToReleaseSpec`) that a new
  fukuii `NetworkFamily`/engine-params abstraction can mirror.

Authority caveats to surface at Phase 4:

- **Not the ETC/PoW consensus-content authority** (that is core-geth — ETChash/ECIP-1017/1099/1111/
  1122; nethermind's Ethash has no ECIP awareness) **nor the canonical ETH/PoS baseline** (go-ethereum).
  nethermind is the authority for the *pluggability mechanism*, not for any single family's rules.
- **Reflection discovery is a .NET-native mechanism.** fukuii is Scala 3 / JVM: the *pattern*
  (self-declaring engines behind one interface, discovered rather than switched) transfers, but the
  *mechanism* would be a JVM `ServiceLoader` / explicit registry / given-instance summoning, not C#
  `AssemblyLoadContext` scanning. Adopt the shape, not the reflection.
- **The subsystem tier is not fully edit-free** for first-party plugins (`NethermindPlugins.cs`) — cite
  nethermind's *config-params* tier, not its plugin list, as the zero-edit reference.

## Gotchas / anti-patterns / things they later changed

- **Reflection discovery + trimming/AOT are in tension.** `TypeDiscovery` walks
  `AssemblyLoadContext.Default.Assemblies` and `GetExportedTypes()` (`TypeDiscovery.cs:42-84,141-142`).
  Assembly-scan discovery is fragile under aggressive IL trimming / NativeAOT (types can be trimmed
  away) — a cost the erigon blank-import and besu if/else don't pay. A JVM port via `ServiceLoader`
  faces the analogous GraalVM-native-image reflection-config problem. Discovery flexibility trades
  against static-linking guarantees.
- **`EngineName` vs `SealEngineType` can silently disagree.** They're independent members
  (`AuRaChainSpecEngineParameters.cs:22-23`); nothing documented forces a class's discovery key and its
  seal identity to be consistent. A copy-paste engine that sets the wrong `SealEngineType` self-enables
  the wrong subsystem.
- **Two tiers must be kept in sync manually.** Discovering a config class (reflection) does *not* wire
  its consensus subsystem — that plugin must also be in `EmbeddedPlugins` or dropped as a DLL. A new
  engine whose params are discovered but whose plugin isn't loaded resolves a `SealEngineType` no
  enabled plugin claims. The clean config tier can outrun the listed subsystem tier.
- **The ambiguity guard is at parameter-provider construction, not schema validation.** `CalculateSeal
  EngineType` throws only when *two configured engines both declare a `SealEngineType`*
  (`ChainSpecParametersProvider.cs:35-46`). A chainspec `engine` block with a typo'd key that matches
  *no* engine simply contributes nothing and falls through to the "No seal engine" throw (`:48`) /
  `LoadEngine`'s "unknown seal engine" throw (`ChainSpecLoader.cs:318-321`) — a mis-keyed engine is a
  missing engine, not a validation error naming the typo.
- **Geth genesis is Ethash-only in nethermind** (`GethGenesisLoader.cs:331-333` throws on non-Ethash
  engine params). A PoA/AuRa network must be expressed as a Parity-lineage chainspec, not a geth
  genesis — the two input formats are not equally expressive.
- **Case-insensitive engine-key matching is load-bearing.** gnosis's `"authorityRound"` matches
  `"AuthorityRound"` only because the provider dict uses `InvariantCultureIgnoreCase`
  (`ChainSpecParametersProvider.cs:25`). A future exact-match refactor would silently break
  differently-cased chainspec keys.
</content>
</invoke>
