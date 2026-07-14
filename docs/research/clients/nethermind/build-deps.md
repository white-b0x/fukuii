# nethermind — build-deps
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind is a C#/.NET client built with **MSBuild** (`dotnet build`), targeting
**net10.0**, `LangVersion 14.0`, `TreatWarningsAsErrors=true`. The solution
(`src/Nethermind/Nethermind.slnx`) uses the **new XML `.slnx` solution format** —
not the legacy GUID-laden `.sln` — and enumerates **120 projects (~69 non-test)**,
one assembly per subsystem.

Two build/dependency features define the subsystem and are the reason this slot is
routed to nethermind:

1. **Plugin-per-assembly decomposition.** Every consensus family and optional
   feature is its own `.csproj` → its own DLL, implementing `INethermindPlugin`.
   Core has **no compile-time reference** to any family; the runner discovers
   plugins (an embedded static list + reflection-scanning a `plugins/` directory)
   and each plugin *self-activates* by matching the chain spec's seal engine. This
   is the nethermind hallmark: adding a network family = adding an assembly, zero
   edits to core.
2. **.NET Central Package Management (CPM).** A single root
   `Directory.Packages.props` (`ManagePackageVersionsCentrally=true`) is the sole
   source of every NuGet version via `<PackageVersion>`; the ~120 project files
   carry **versionless** `<PackageReference Include="Snappier" />`. This is the
   .NET analog of besu's Gradle BOM — one file to audit, no version drift.

## Key types / interfaces / files

- `Directory.Packages.props:1` (repo root) — the CPM single-version-source.
  `ManagePackageVersionsCentrally=true`, `CentralPackageTransitivePinningEnabled=true`,
  then ~95 `<PackageVersion Include="…" Version="…" />` rows (Autofac 9.1.0,
  RocksDB `[10.10.1.649]` — bracketed = **exact pin**, NLog, DotNetty, BouncyCastle,
  NUnit, Ckzg.Bindings, Nethermind.Crypto.SecP256k1, etc.).
- `Directory.Build.props:1` (repo root) — global MSBuild defaults inherited by every
  project: `TargetFramework=net10.0`, `LangVersion=14.0`, `TreatWarningsAsErrors=true`,
  `InvariantGlobalization`, `UseArtifactsOutput`, product/copyright metadata.
- `src/Nethermind/Directory.Build.props:1` — solution-tree overrides: version
  (`VersionPrefix 1.40.0` / `VersionSuffix unstable`), reproducible-build
  `SourceDateEpoch`, and an auto-injected analyzer `ProjectReference`
  (`Nethermind.Analyzers`) applied to every project except the analyzer itself.
- `Directory.Build.targets:1` (root) — PGO/ReadyToRun publish profile wiring.
- `nuget.config:1` — feed list plus **`<packageSourceMapping>`** (supply-chain
  hardening: `*` pattern maps only to nuget.org; specific patterns pin
  `Microsoft.Diagnostics.Runtime.*` → dotnet-tools feed, `Nethermind.ZiskOS.*`/
  `Nethermind.Zkvm.*` → nugettest feed) and an `<auditSources>` entry.
- `src/Nethermind/Nethermind.slnx:1` — the `.slnx` solution; a `<Folder Name="/Plugins/">`
  groups plugin projects, `<Folder Name="/Solution Items/">` lists the shared build files.
- `src/Nethermind/Nethermind.Api/Extensions/INethermindPlugin.cs:9` — the plugin
  contract: `Name/Description/Author`, `Enabled`, `MustInitialize`, lifecycle hooks
  `InitTxTypesAndRlpDecoders / Init / InitNetworkProtocol / InitRpcModules`, and an
  optional Autofac `IModule? Module` for DI registration.
- `src/Nethermind/Nethermind.Api/Extensions/IConsensusPlugin.cs` — marker sub-interface
  (`ApiType`) tagging a plugin as a consensus family.
- `src/Nethermind/Nethermind.Api/Extensions/PluginLoader.cs:22` — runtime discovery:
  seeds an embedded `Type` list, then scans `plugins/*.dll` via
  `AssemblyLoadContext.Default.LoadFromAssemblyPath`, reflecting for exported types
  assignable to `INethermindPlugin`. `SinglePluginLoader`/`CompositePluginLoader`
  compose loaders.
- `src/Nethermind/Nethermind.Runner/NethermindPlugins.cs:11` — the **`EmbeddedPlugins`**
  static list (compiled-in families/features): `AuRaPlugin`, `CliquePlugin`,
  `EthashPlugin`, `NethDevPlugin`, `MergePlugin`, `AuRaMergePlugin`, `OptimismPlugin`,
  `TaikoPlugin`, `XdcPlugin`/`XdcSubnetPlugin`, `ShutterPlugin`, `HivePlugin`,
  `HealthChecksPlugin`, `EthStatsPlugin`, `Flashbots`, `SnapshotPlugin`, `UPnPPlugin`,
  `BalRecorderPlugin`, `StateDiffsWriterPlugin`.
- `src/Nethermind/Nethermind.Runner/Program.cs:119` — constructs `PluginLoader` with
  `--plugins-directory` (default `"plugins"`) + `NethermindPlugins.EmbeddedPlugins`.
- `src/Nethermind/Nethermind.Consensus.Clique/CliquePlugin.cs:24` — canonical family
  plugin: `Enabled => chainSpec.SealEngineType == SealEngineType` (self-gates on the
  chain spec), `Init` wires a block preprocessor, `InitRpcModules` registers its RPC
  module. Its `.csproj` references only `Nethermind.Api` + core — never the reverse.

### Solution project groups (from `Nethermind.slnx`)

- **Core / serialization:** `Nethermind.Core`, `.Crypto`, `.Serialization.{Rlp,Json,Ssz}`,
  `.Serialization.SszGenerator` (source generator), `.Abi`, `.Specs`, `.Config`, `.Logging*`.
- **EVM / state / db:** `Nethermind.Evm`, `.Evm.Precompiles`, `.State`, `.State.Flat`,
  `.Trie`, `.Db`, `.Db.Rocks`, `.Db.Rpc`, `.TxPool`.
- **Consensus (each a plugin assembly):** `Nethermind.Consensus` (shared), plus
  `.Consensus.Clique`, `.Consensus.AuRa`, `.Consensus.Ethash`.
- **Blockchain / sync / network:** `Nethermind.Blockchain`, `.Synchronization`,
  `.Network`, `.Network.{Discovery,Dns,Enr,Stats,Contract}`, `.Kademlia`, `.Era1`, `.EraE`,
  `.History`.
- **API / init / runner:** `Nethermind.Api`, `Nethermind.Init`, `.Init.Snapshot`,
  `Nethermind.Runner` (entrypoint), `.Facade`, `.JsonRpc`, `.JsonRpc.SourceGenerator`,
  `.Sockets`, `.Grpc`, `.HealthChecks`, `.Monitoring`, `.Wallet`, `.KeyStore`.
- **Plugins folder (`/Plugins/…`):** `Nethermind.Merge.Plugin`, `.Flashbots`, `.Hive`,
  `.UPnP.Plugin`, `.ExternalSigner.Plugin`, `.OpcodeTracing.Plugin`,
  `.JsonRpc.TraceStore`, `.StateDiffsWriter` (+`.StateDiff.Core`).
- **L2 / alt-family plugins (top-level projects):** `Nethermind.Optimism`,
  `Nethermind.Taiko`, `Nethermind.Xdc`, `Nethermind.Shutter`, `Nethermind.Merge.AuRa`,
  `Nethermind.BalRecorder`.
- **Analyzers / codegen:** `Nethermind.Analyzers` (Roslyn analyzer auto-referenced by
  every project via `src/Nethermind/Directory.Build.props`), `Nethermind.Test.Analyzers`.

## Design decisions & rationale

- **A family is an assembly, not a package of `if`-branches.** Core depends on the
  `INethermindPlugin` abstraction only; Optimism/Taiko/Merge/Clique/AuRa each live in
  a leaf project referencing *inward* to `Nethermind.Api`. New network → new `.csproj`
  → add to `EmbeddedPlugins` (or drop a DLL in `plugins/`). No core edit, no core
  recompile-for-a-feature.
- **Self-activating plugins.** `Enabled` is computed from the loaded `ChainSpec`
  (`chainSpec.SealEngineType == SealEngineType`). The runner loads *all* plugins but
  only the one matching the chain runs — so a single binary ships every family and
  the network config selects at boot. Directly serves the multi-network/enterprise
  single-binary use case.
- **DI via Autofac `IModule` per plugin.** Each plugin optionally exposes `Module`,
  so a family registers its own services into the container instead of core knowing
  about them — keeps the wiring inside the plugin's assembly boundary.
- **CPM = one version source.** Versionless `PackageReference` everywhere + one
  `Directory.Packages.props`; `CentralPackageTransitivePinningEnabled` also pins
  transitive deps. Bracketed versions (`[10.10.1.649]`) express exact pins for
  native/critical bindings (RocksDB). Serves supply-chain: one file to audit/patch.
- **Reproducible builds.** `SourceDateEpoch` + `ContinuousIntegrationBuild` +
  `UseArtifactsOutput` normalize output for deterministic CI artifacts.

## Notable patterns (the reusable idea)

**The single most transferable idea for fukuii: consensus/feature families as
self-contained, self-activating plugin modules behind a stable core-facing
interface, aggregated by one runner list — with versions centralized in one file.**

- fukuii's sbt multi-module layout (`bytes`, `crypto`, `rlp`, `Evm`, root `main`…) is
  the JVM analog of nethermind's `.csproj`-per-subsystem. The gap fukuii can close is
  turning each **consensus family (PoW/ETC, PoS/ETH, future PoA)** into a *module that
  registers itself* against a stable `INethermindPlugin`-style seam and *gates on the
  chain config* — matching the "add a family without touching core" property. This is
  exactly the file-tree/seam direction already noted for Batch 5/7 (consensus/ by
  mechanism category with pluggable production-side seams: Sealer / ValidatorProvider /
  BlockInterface).
- **CPM → sbt `Dependencies.scala`.** fukuii already funnels versions through
  `project/Dependencies.scala`; nethermind validates the "one version source, no
  per-module version literals" discipline (the besu-BOM equivalent) and adds
  `nuget.config` `packageSourceMapping` as a supply-chain guard worth mirroring in
  spirit (resolver/source pinning, sentinel's remit).
- **Runner-owned embedded list + optional dynamic DLL dir.** The `EmbeddedPlugins`
  array is a clean, greppable manifest of what ships in the binary; the `plugins/`
  scan allows third-party/enterprise extensions without a rebuild — a concrete model
  for fukuii's product-family (mining-pool build vs. enterprise multi-network build)
  differing only by which family modules are aggregated.

## Authority note

nethermind = the **plugin-per-assembly + Central Package Management** authority; besu
is the Gradle-BOM JVM peer (single-version-source expressed as a BOM instead of CPM).
Where besu shows the JVM/Gradle idiom fukuii can most literally port, nethermind shows
the *decomposition target* — the degree of modularization (each consensus family and
each optional feature is a separately-compiled, reflection-loadable, self-activating
assembly) that a maximally-modular fukuii would aim its sbt module + product-family
layout toward.

## Gotchas / anti-patterns / things they later changed

- **`.slnx`, not `.sln`.** The charter/template referenced `Nethermind.sln`; upstream
  at this commit has **migrated to the XML `.slnx` format** (`Nethermind.slnx`, plus
  `Benchmarks.slnx`, `EthereumTests.slnx`, `Stateless.slnx`). The legacy `.sln` is gone.
  Any tooling assuming `.sln` must be updated.
- **`Directory.Packages.props` lives at repo root**, not under `src/Nethermind/`
  (the template guessed the latter). Same for the root `Directory.Build.props`; a
  *second* `src/Nethermind/Directory.Build.props` layers solution-specific settings on
  top — MSBuild merges nearest-ancestor props, so both apply.
- **`Nethermind.Init.Steps`** (named in the charter) is not a standalone project — init
  ordering lives inside `Nethermind.Init` / `Nethermind.Api`; the plugin lifecycle hooks
  (`Init`/`InitNetworkProtocol`/`InitRpcModules`) are the step-sequencing mechanism.
- **Reflection plugin loading is trust-sensitive.** `PluginLoader` will load *any*
  `*.dll` in the plugins dir implementing `INethermindPlugin` into the default
  `AssemblyLoadContext` (no isolation, no signature check) — flexible but a supply-chain
  surface; enterprise deployments must control the plugins directory.
- **`MevPlugin` example is commented out** in `Program.cs` (a `SinglePluginLoader` +
  `CompositePluginLoader` demo) — shows the intended extension seam even though it's
  not wired by default.
- **`TreatWarningsAsErrors=true` globally** with `LangVersion 14.0` on `net10.0` — an
  aggressive-currency posture (bleeding-edge .NET/C#), the opposite of fukuii's
  Scala-3.3.x-LTS conservatism; the modularization lesson transfers, the
  version-aggressiveness does not.
