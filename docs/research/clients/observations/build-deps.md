# Observations — build-deps
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/build-deps.md._

This is the Phase-2 cross-client comparison for the **build-deps** subsystem: how each reference
client picks a build tool, decomposes into modules/crates, manages dependency versions, gates the
supply chain, splits into multiple binaries/components, runs codegen, and enforces
dependency-direction. It synthesizes the six per-client `build-deps.md` docs — every claim is cited
to that client's doc, not re-researched from the repos.

**Authority model (per Phase-0, re-stated so the table reads correctly):** **besu** = the JVM
structural authority — closest mirror to fukuii's setup (JVM, multi-module, single build tool);
its `:platform` `java-platform` BOM feeding versionless module files is the pattern fukuii's
`project/Dependencies.scala` should match. **reth** = the crate-granular SDK authority — the most
granular decomposition (108 first-party crates), `[workspace.dependencies]` single-version-source,
and the meta-crate SDK front door. **nethermind** = the plugin-project + Central Package Management
authority — a family-per-assembly decomposition with one `Directory.Packages.props` version source.
**go-ethereum** = the canonical Go/EVM build-tooling baseline (the `ci.go`+`checksums.txt` pattern,
copied by erigon/core-geth). **core-geth** inherits geth's build machinery wholesale — the ETC
byte-authority for consensus, **not** for build. **erigon** = the multi-binary component-decomposition
precedent (separable `sentry`/`txpool`/`rpcdaemon`/`downloader`/`caplin` service binaries). None of
these is a consensus authority in this slot (core-geth remains that, separately).

## Comparison table
| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | fukuii | Authoritative |
|---|---|---|---|---|---|---|---|---|
| **Build tool** | Makefile shim over self-hosted `go run build/ci.go` orchestrator (compile/test/lint/package/release in one Go program) | inherits geth's `ci.go`+Makefile+Dockerfile+PPA wholesale (build system not forked) | **Gradle multi-project** (~50 subprojects, root `build.gradle` _is_ the convention layer, no `buildSrc`) | plain **Makefile over `go build`** (no Bazel/workspace); generic `%.cmd` rule per binary | **MSBuild** (`dotnet build`), new XML `.slnx` solution, 120 projects (~69 non-test) | single **Cargo workspace** (142 members, 108 first-party `reth-*` crates) | **sbt 1.10.7** (`project/build.properties:1`), single tool, no secondary orchestrator | **besu** (JVM/Gradle = fukuii/sbt peer) |
| **Module/crate decomposition granularity** | flat vendorless module graph; one root `go.mod` + a `cmd/keeper` module | same as geth; no module rename (`module github.com/ethereum/go-ethereum` retained) | ~50 Gradle subprojects, `:`-delimited 2-level hierarchy (`ethereum:core`, `crypto:algorithms`); clean layered DAG, leaf modules have zero internal deps | **single** Go module (erigon-lib re-absorbed); decomposition moved to multi-*binary*, not multi-module | one assembly per subsystem, ~69 non-test `.csproj`; each consensus family its own DLL | **most granular** — 108 crates; traits/types split from impl (`reth-storage-api` vs `reth-db` vs `reth-provider`) | 6 sbt modules (`bytes`/`crypto`/`rlp`/`scalanet`/`scalanetDiscovery`/root `node`, `build.sbt:198-432`) + 4 test configs (`it`/`evm`/`rpcTest`/`benchmark`); boundaries echo besu's leaf modules ~1:1 | **reth** (finest: SDK-grade) / **besu** (JVM boundaries map ~1:1 to fukuii `bytes`/`crypto`/`rlp`/`Evm`) |
| **Version-management (single-source?)** | two-tier: `go.mod`/`go.sum` for libs + `build/checksums.txt` `# version:` registry for build-*tools* incl. the compiler | inherited; adds a few ETC-only deps (OpenRPC, EVMC) inline | **YES — self-hosted `:platform` `java-platform` BOM**; modules declare deps _versionless_, one file pins ~90 versions, BOM also published for downstream | `[require]` in the single `go.mod`; hard-forks hot native deps to `erigontech/*`, some pinned to pseudo-versions | **YES — .NET CPM**: one root `Directory.Packages.props`, versionless `<PackageReference>`, transitive pinning on | **YES — `[workspace.dependencies]`**: every dep (1st + 3rd party) declared once, members use `foo.workspace = true` | **mostly** — `project/Dependencies.scala` (216 lines) is the single catalog object modules import vals from; not a BOM/platform-constraint (not published/versionless-enforced), 1 literal lives outside it (`build.sbt:3` kanela-agent), 2 duplicate literals inside it (json4s `4.0.7` ×2, bouncycastle `1.84` ×2) | **besu** (BOM) / **nethermind** (CPM) / **reth** (workspace.deps) — the three single-source idioms; besu closest to `Dependencies.scala` |
| **Supply-chain gates (checksums/verification)** | `checksums.txt` sha256 of every build artifact incl. bootstrap Go (`-dlgo`); reproducible-build ldflags | inherited; `-trimpath` **disabled** (OpenRPC breaks it) — a reproducible-build regression | **`gradle/verification-metadata.xml`** (10,070 lines of sha256 for every artifact: jar+pom+sources) + license allow-list + `consistentResolution` | none checked-in beyond `go.sum`; pseudo-version git pins force SHA-review; CGO shells to a C toolchain | `nuget.config` **`packageSourceMapping`** (feed pinning) + `auditSources`; exact bracket pins `[10.10.1.649]` for native bindings | `go.sum`-equivalent Cargo.lock; but ships a **branch-pinned git dep** (`revmc` branch, not SHA) — a smell | **none** — no checksum/lockfile artifact checked in; resolution relies on Maven Central + declared `resolvers` (`build.sbt:31-37`) each build | **besu** (verification-metadata = Gradle-native lockfile+checksum) / **geth** (checksummed toolchain download) |
| **Multi-binary/component split** | one `ci.go` builds the full `cmd/...` matrix (deb/NSIS/Docker/archive) but a monolith node | inherited | single fat `:app` node module; no runtime component split | **YES — fleet of separable service binaries**: `erigon` all-in-one + `sentry`/`txpool`/`rpcdaemon`/`downloader`/`caplin` standalone over a gRPC `node/interfaces` module | **YES (compile-time)** — family-per-assembly; runner loads all plugins, one self-activates on chain spec; single binary ships every family | **YES (compile-time)** — feature-flag composition (`consensus`/`evm`/`node`/`full`) + `reth-node-builder` composition crate | single assembled artifact — one `assembly`/`JavaAppPackaging` output (`build.sbt:409-410`); no service-binary or compile-time family-gated split | **erigon** (runtime process split — product-family seams) / **nethermind**+**reth** (compile-time family gating) |
| **Codegen build steps** | push-based `//go:generate` (32 files); CI **check_generate** hash-diff gate (committed, proven fresh, not regenerated per build) | inherited; OpenRPC doc generation is the ETC-added codegen | spotless/errorprone as compile gates (formatting/static-analysis wired into every module's compile task) | protobuf/gRPC stubs in `node/interfaces`; ldflags version stamping | Roslyn analyzer auto-referenced by every project; source generators (`SszGenerator`, `JsonRpc.SourceGenerator`) | proc-macros (`tables!` macro); no separate codegen build step, done at compile | `BuildInfoPlugin` only (`build.sbt:301-320`) — embeds git commit(8-hex, Lighthouse-compat)/branch/tags + full dep list; no freshness/hash-diff gate | **geth** (check_generate freshness gate — reusable for codec-heavy code) |
| **Dependency-direction enforcement** | **`check_baddeps`** — `go list -deps` allow-list gate forbidding named package→package edges (CI fitness function) | inherited (unused for ETC-specific layering) | layered DAG + `api` vs `implementation` discipline; `plugin-api` **source-hash freeze** (`FileStateChecker`) locks the extension seam | boundary drawn at the **process/gRPC boundary** (the one kept-separate module is the wire-contract module) | core has **no compile ref** to any family; families reference _inward_ to `Nethermind.Api` only | `*-api`/`*-types` crates split from impl → consumers depend on interface without implementation; per-crate `[lints] workspace = true` | none at the build-tool layer — the `Eth*`/`Etc*` no-cross-reference rule is a convention/review protocol (`scala3-style.md`), not an sbt/CI fitness function | **geth** (`check_baddeps` = the `Eth*`/`Etc*` no-cross-ref ratchet analog) / **besu** (plugin-api hash-freeze) |

## Approach catalog (use-case-aware)

Verdicts: **DEFAULT** = fukuii's baseline best practice · **OPTIONAL(role)** = offer for a named
use-case (enterprise / mining-pool / archival / multi-network product-family) · **OBSOLETE** =
understood-but-discarded.

| Approach | Clients using it | Good for (use-case/node-role) | Verdict | Why |
|---|---|---|---|---|
| **Single-version-source** (besu `:platform` BOM / nethermind .NET CPM / reth `[workspace.dependencies]`) | besu, nethermind, reth (three idioms, one principle) | every build; the pattern fukuii's `project/Dependencies.scala` should match | **DEFAULT** | All three JVM/.NET/Rust peers converged on it independently: one file pins every version, modules declare deps *versionless*, bumping a dep touches exactly one file. fukuii already funnels versions through `Dependencies.scala` — the discipline is *never inline a version in a submodule `build.sbt`*. besu's BOM is _also published_ so downstream/plugin consumers align on the same versions — the closest literal port. |
| **Checksummed supply-chain gate** (geth `checksums.txt` / besu `verification-metadata.xml`) | go-ethereum, besu (nethermind `packageSourceMapping` partial) | enterprise/CEX supply-chain posture; CI hermeticity; air-gapped builds | **DEFAULT** | Every resolved artifact's sha256 pinned and verified at resolution; mismatch fails the build. besu's `verification-metadata.xml` is the Gradle-native equivalent of a lockfile+checksum and the direct model for an sbt build. Aligns with the global `resolution-age`/Dependabot-cooldown supply-chain rules (verify-before-use, exact pins) — sentinel's remit. |
| **Multi-binary component decomposition** (erigon/reth — product-family seams) | erigon (runtime process split), reth (compile-time features) | archival/enterprise "run components separately, scale each independently"; mining-pool vs enterprise product-family builds | **OPTIONAL(product-family / archival)** | erigon proves one EVM codebase can ship a monolith *and* independently-scalable service binaries (`sentry`/`txpool`/`rpcdaemon`) from one build, boundary drawn at the process/gRPC hop. Ties to erigon's one-interface-two-impls and fukuii's lean-node + separately-runnable-components thesis. Not the baseline — a named-use-case affordance. |
| **Dependency-direction assertion as a test** (geth `check_baddeps`) | go-ethereum (core-geth inherits) | multi-network/omni-client codebases keeping consensus-family paths from leaking into each other | **DEFAULT (as a CI ratchet)** | A `go list -deps` allow-list forbidding named package→package edges — a lightweight, incrementally-grown fitness function. Directly analogous to fukuii's `Eth*`/`Etc*` no-cross-reference ratchet; adoptable as an sbt/CI layering gate. |
| **Meta-crate / plugin-project SDK front door** (reth `reth-ethereum` / nethermind plugin-per-assembly) | reth (meta-crate + feature cascade), nethermind (`INethermindPlugin` + `EmbeddedPlugins`) | enterprise "assemble a custom node from modules"; drop-in third-party families without a core edit | **OPTIONAL(SDK / multi-network product-family)** | reth ships one façade crate re-exporting the seams downstream assembles against; nethermind makes each family a self-activating assembly aggregated by one runner list. Both are the *decomposition target* a maximally-modular fukuii aims its sbt-module + product-family layout toward — the "add a family without touching core" property. Aggressive currency (net10/edition-2024) does **not** transfer; the modularization does. |

## Best-practice synthesis

**DEFAULT (the baseline every JVM/.NET/Rust peer converged on):**

1. **Versionless-submodule deps + one central version source.** besu's `:platform` BOM,
   nethermind's CPM, and reth's `[workspace.dependencies]` are three encodings of one rule: no
   submodule declares a version; one file pins them all. fukuii's `project/Dependencies.scala` is
   the sbt equivalent (besu BOM ≙ `Dependencies.scala`) — keep **every** version there, never
   inline one in a submodule's `build.sbt`. This is the single most transferable pattern.

2. **A checksummed supply-chain gate.** besu's checked-in `verification-metadata.xml` (per-artifact
   sha256, fail-on-mismatch) and geth's checksummed toolchain download are the reference posture.
   Adopting the Gradle-native-lockfile equivalent for sbt aligns fukuii's build with the global
   supply-chain rules (resolution-age gate, Dependabot cooldown, exact pins for crypto/build
   packages, GitHub deps pinned to SHAs — the `revmc` branch-pin in reth is the counter-example to
   avoid). sentinel owns this gate.

3. **A dependency-direction CI ratchet.** geth's `check_baddeps` shows layering assertions as a
   cheap test — the build-layer analog of fukuii's `Eth*`/`Etc*` no-cross-reference rule.

**OPTIONAL menu (named use-cases, not the baseline):**

- **Multi-binary component split** (erigon runtime process seams / reth+nethermind compile-time
  family gating) — for the product-family thesis: a lean mining-pool build and an enterprise
  multi-network build differing only by which modules/binaries are aggregated. Ties directly to
  erigon's one-interface-two-impls precedent.
- **Meta-module SDK front door** (reth `reth-ethereum` / nethermind plugin-project) — for
  enterprise consumers assembling a custom node against stable seams.

**SR↔dev integration.** The build layer is a *prerequisite floor*, not a green-light itself: the
Scala 3 dependency bumps and the sbt-2 cutover (MOD-19 waves — Wave M now on the current
sbt-1/Scala-3.3.8/Pekko-1.6 stack, Wave S at the sbt-2 GA cutover) are the build-floor the SR's
Phase-4 green-lights sit on top of. A dep-bump or build-modernization item must clear before the
structural work it unblocks can land.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

Seeds, not verdicts — these forward-reference Phase 3–4 and are not decisions to act on now:

- **fukuii's sbt modules (`bytes`/`crypto`/`rlp`/`Evm`, plus `Benchmark`/`RpcTest`/`IntegrationTest`)
  ≙ the granular module decomposition.** These already echo besu's leaf modules (`util`,
  `crypto:algorithms`, `ethereum:rlp`, `evm`) almost one-to-one and reth's crate groups — the
  boundaries are largely right; the reth/nethermind lesson is how much *further* a maximally-modular
  fukuii could slice (trait/type crates split from impl; family-per-module).

- **`project/Dependencies.scala` = the single-version-source.** It already centralizes versions;
  the discipline to ratchet is *never inline a version in a submodule `build.sbt`* (the besu-BOM /
  CPM / workspace.deps rule) — and, following besu, consider whether a fukuii "platform"/BOM should
  be *published* so product-family/plugin consumers align on the same versions.

- **A checksummed supply-chain gate + a `check_baddeps`-style layering ratchet** are the two CI
  additions the reference set most strongly recommends (besu verification-metadata; geth
  check_baddeps ≙ the `Eth*`/`Etc*` ratchet).

- **The dep-bump / sbt-build modernization (MOD-19 waves) is a PREREQ floor for Phase-4
  green-lights** per the SR↔dev integration directive — schedule build-floor items ahead of the
  structural work they gate, not concurrently.
