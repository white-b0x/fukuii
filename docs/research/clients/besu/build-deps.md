# besu — build-deps
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

## Architecture summary

besu is a **Gradle multi-project JVM build** with ~50 subprojects declared flat in a single
`settings.gradle`, using `:`-delimited paths to express a two-level hierarchy
(`ethereum:core`, `consensus:qbft`, `crypto:algorithms`, `plugins:rocksdb`). All shared build
config lives in the **root `build.gradle`** via a single `configure(allprojects - project(':platform'))`
block — there is no `buildSrc` and no `gradle/*.gradle` convention-plugin split; the root script
_is_ the convention layer.

Version management is centralized in a dedicated **`:platform` module** — a Gradle
`java-platform` (i.e. a self-hosted BOM). Every other project gets `api platform(project(':platform'))`
injected by the root script, so **individual module `build.gradle` files declare dependencies
without versions** (e.g. `implementation 'com.google.guava:guava'` — no version) and the single
`platform/build.gradle` pins every version in one place. This is besu's answer to "version
catalog vs. BOM": they chose an internal published BOM over `libs.versions.toml`.

Supply-chain integrity is enforced by **`gradle/verification-metadata.xml`** (10,070 lines of
SHA-256 checksums for every resolved artifact, jar + pom + sources), plus a license allow-list
gate and per-module spotless/errorprone/`-Werror` compile gates.

The module graph is a clean DAG: leaf utility/codec/crypto modules depend on nothing internal,
mid-tier `datatypes`/`evm` depend on those, and the `:app` fat module wires ~30 subprojects
together at the top. `:plugin-api` sits deliberately low (depends only on `:datatypes` + `:evm`)
and is guarded by a **source-hash freeze** so the extension surface can't drift accidentally.

## Key types / interfaces / files

- `settings.gradle:32-79` — the authoritative module list: ~50 `include` lines. Two-level naming
  groups related modules (`ethereum:{api,core,eth,evmtool,p2p,rlp,trie,verkletrie,...}`,
  `consensus:{clique,common,ibft,ibftlegacy,merge,qbft,qbft-core}`,
  `crypto:{algorithms,services}`, `metrics:{core,rocksdb}`, `plugins:{health,rocksdb}`,
  `services:{kvstore,pipeline,tasks}`).
- `platform/build.gradle:26-38` — imported upstream BOMs (`platform(...)` of jackson, netty,
  grpc, vertx, log4j, junit, mockito, opentelemetry, immutables, slf4j, assertj, prometheus).
- `platform/build.gradle:40-73` — `constraints { api project(':...') }` for every internal module
  → so the published `org.hyperledger.besu:bom` also pins the client's own artifact versions.
- `platform/build.gradle:74-190` — the single flat list of ~90 third-party version pins
  (guava `33.5.0-jre`, rocksdbjni `10.6.2`, bouncycastle `1.84`, tuweni `2.7.2`, picocli `4.7.7`,
  dagger `2.59.2`, netty via BOM, web3j `5.0.3`, etc.).
- `build.gradle:160-176` — root `dependencies` block applied to all projects: injects
  `api platform(project(':platform'))`, errorprone core + `besu-errorprone-checks`, junit launcher.
- `build.gradle:24-34` — root `plugins {}`: spotless `7.0.3`, errorprone `4.4.0`,
  dependency-license-report `3.1.4`, jmh, jacoco + jacoco-report-aggregation, sonarqube, artifactory.
- `build.gradle:143-157` — `java { toolchain { languageVersion = 25 } ; consistentResolution
  { useCompileClasspathVersions() } }` — forces the runtime classpath to match the compile
  classpath version-for-version (a reproducibility guard).
- `build.gradle:271-320` — per-module `JavaCompile` config: `-Werror` + `-Xlint:*` +
  errorprone checks (WildcardImport WARN, InsecureCryptoUsage WARN, FieldCanBeFinal WARN, plus
  a long list of ETC-era/immutables-pattern exemptions).
- `build.gradle:194-263` — root `spotless {}`: googleJavaFormat `1.35.0`, `importOrder`, license
  header enforcement across Java/Groovy-Gradle/Shell/Solidity.
- `gradle/verification-metadata.xml:1-8` — `<verify-metadata>true</verify-metadata>`,
  `<verify-signatures>false</verify-signatures>`; the rest is 10k lines of per-artifact sha256.
- `plugin-api/build.gradle:34-42` — `:plugin-api` deps: only `:datatypes`, `:evm`, commons-lang3,
  tuweni-bytes/units, guava; `vertx-core` is `compileOnly`. Deliberately narrow.
- `plugin-api/build.gradle:46-83` — `FileStateChecker` custom task + `checkAPIChanges`: SHA-256
  of all `.java` source is frozen against a `knownHash` const; `check.dependsOn('checkAPIChanges')`
  fails the build if the plugin API surface changes without a deliberate hash bump.
- `build.gradle:1270-1295` — `calculateVersion()` (calendar-versioning, git-hash fallback) and
  `calculateArtifactId()` (joins the `:`-path segments into `ethereum-core` style artifact ids).
- `gradle.properties:2-5` — `org.gradle.parallel=true`, `org.gradle.caching=true`.
- `gradle/wrapper/gradle-wrapper.properties` — Gradle `9.3.1`. `gradle/gradle-daemon-jvm.properties`
  — JDK toolchain `25`.

## Design decisions & rationale

- **Self-hosted `:platform` BOM over a version catalog.** All versions live in one
  `java-platform` module that is _also published_ as `org.hyperledger.besu:bom`. Downstream
  consumers (plugins, embedders) import the same BOM the client builds against, so internal and
  external version alignment share one source of truth. Trade-off vs. a `libs.versions.toml`
  catalog: the BOM is consumable by external Maven/Gradle users; a catalog is build-local only.
- **`consistentResolution { useCompileClasspathVersions() }`** — guarantees the runtime classpath
  cannot silently upgrade a transitive dependency past what was compiled against; kills a class of
  "works in test, NoSuchMethodError in prod" bugs.
- **Dependency verification metadata checked into git** — reproducible builds and supply-chain
  defense: any artifact whose sha256 doesn't match the pinned value fails resolution. This is the
  Gradle-native equivalent of pnpm's `resolution-age` gate + lockfile.
- **Plugin API frozen by source hash** — `:plugin-api` is the extension seam for enterprise/
  multi-network use (plug in without forking). Its stability is enforced mechanically, not by
  review discipline alone.
- **`-Werror` everywhere + errorprone + spotless as build gates** — formatting and static
  analysis are not optional CI steps bolted on; they're wired into every module's compile task.
- **Explicit conflict resolution in root script** (`build.gradle:178-200`): bouncycastle
  capability → `selectHighestVersion`, and `exclude`s that force the ConsenSys tuweni coordinates
  over the legacy `io.tmio` ones — a documented migration artifact handled centrally, not per-module.

## Notable patterns (the reusable idea)

1. **Versionless module `build.gradle` + one BOM module.** Every dependency line in a leaf module
   omits its version; the version is resolved from `:platform`. Adding/bumping a dependency touches
   exactly one file. This is the single most transferable pattern for fukuii.
2. **Layered DAG with leaf modules that have zero internal deps.** `util`, `ethereum:rlp`,
   `crypto:algorithms` depend on no other besu module (only third-party libs). `datatypes` →
   {`crypto:algorithms`, `ethereum:rlp`}; `evm` → {`crypto:algorithms`, `datatypes`,
   `ethereum:rlp`, `util`}; `plugin-api` → {`datatypes`, `evm`}; `:app` → ~30 modules. Low→high
   order is strictly acyclic and mirrors fukuii's `bytes`/`crypto`/`rlp`/`Evm` sbt modules almost
   one-to-one.
3. **`api` vs. `implementation` discipline.** Modules expose only what downstream needs via `api`
   (e.g. `crypto:algorithms` exposes bouncycastle as `api`; `plugins:rocksdb` re-exports
   `:plugin-api` as `api`) and hide the rest behind `implementation`, so the compile classpath of
   a consumer stays minimal and rebuild scope is contained.
4. **Extension seam pinned by checksum.** The plugin API's Java source is SHA-256-frozen; changing
   it forces a conscious hash update — makes "did we just break the plugin ABI?" a compile-time
   answer.
5. **Central conflict/exclusion block** in the root `configure(allprojects)` rather than scattered
   per-module `resolutionStrategy` — coordinate migrations (tuweni relocation, bouncycastle
   capability) are handled once.

## Authority note

besu is the **JVM structural authority** for this SR slot: it's the closest mirror to fukuii's
setup (JVM, multi-module, single build tool). Where fukuii uses sbt sub-projects
(`bytes`, `crypto`, `rlp`, `Evm`, `Benchmark`, `RpcTest`, `IntegrationTest`), besu uses Gradle
subprojects (`util`, `crypto:algorithms`, `ethereum:rlp`, `evm`, ...). The **module _boundaries_**
map almost directly — fukuii's good modules already echo besu's leaf modules. The **version-
management strategy** (one `:platform` BOM feeding versionless module files) is the piece fukuii's
sbt setup can adopt via a single `project/Dependencies.scala` object (fukuii already centralizes
here — the takeaway is to keep every version there and never inline a version in a submodule's
`build.sbt`). besu is _not_ an authority for consensus/EVM semantics (that's core-geth for PoW).

## Gotchas / anti-patterns / things they later changed

- **No `buildSrc` / no convention plugins.** All shared logic lives in one ~1,300-line root
  `build.gradle` with a giant `configure(allprojects - project(':platform'))` block. It works but
  is monolithic; modern Gradle guidance favors `buildSrc` or included-build convention plugins for
  this. fukuii should not copy the "one huge root script" shape — sbt's `commonSettings` val is the
  cleaner equivalent.
- **`:platform` must be _excluded_ from the allprojects config** (`configure(allprojects -
  project(':platform'))`) because a `java-platform` project can't apply `java-library`. Easy to
  break if you forget the subtraction.
- **`io.tmio` → `io.consensys.tuweni` and `tech.pegasys.discovery` → `io.consensys.protocols`
  relocations** are handled with `exclude`/`capabilitiesResolution` hacks in the root script —
  a lingering artifact of upstream coordinate churn (tuweni/discovery moved orgs). Documents that
  even a well-managed BOM accumulates migration cruft.
- **`consistentResolution` can surface "version conflict" failures** that a laxer build would
  silently paper over — a feature, but it means a transitive bump can fail the build until the BOM
  pin is updated. Intended cost of reproducibility.
- **Flat `settings.gradle` include list** must be hand-maintained; there's no auto-discovery of
  subprojects. Adding a module = editing `settings.gradle` _and_ adding a constraint in
  `platform/build.gradle`. Two-file coupling is a minor footgun (a new module without a BOM
  constraint won't be version-managed for downstream consumers).
- **ETC removed upstream.** At this commit besu no longer has ETC/classic fork config — expected
  for post-Feb-2026 upstream; the module _structure_ documented here is unaffected and is what
  matters for the fukuii mirror.
