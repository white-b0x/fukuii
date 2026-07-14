# Besu — Build, Test, Benchmark & Minimal Agentic Tooling

Source: `.claude/repo-references/clients/besu/` (vendored full clone, verified genuine
git checkout — `.git/` present, `HEAD` at `3fd233a4f9` / "setReachedTerminalDifficulty when
p2p disabled (#10677)", 2026-07-01). `git remote -v` shows `origin` = `white-b0x/besu.git`,
`upstream` = `hyperledger/besu.git` — the same mirrored-fork-of-canonical pattern used for
every other vendored client in this tree (erigon, reth, go-ethereum, nethermind all mirror
through the same `white-b0x` org onto their respective canonical upstreams), so this is a
vendoring artifact, not evidence of anything Besu-specific. One genuine, non-artifact
oddity worth flagging: the checked-out `README.md` itself has been rewritten (commit
`baeb336910`, "Update links to use updated paths") to point at `github.com/besu-eth/besu`
and `docs.besu-eth.org` rather than the historical `hyperledger/besu` /
`besu.hyperledger.org` — sibling clones (erigon, reth, go-ethereum) show no equivalent
rename in their own READMEs, so this reflects a real upstream Besu org/domain migration
that landed in this checkout, not a rendering artifact of the vendoring process.

This document covers Besu's near-absent AI-agentic tooling (confirmed below to not warrant
its own `agentic-tooling-pattern.md`, unlike Nethermind/erigon/reth/go-ethereum), its Gradle
monorepo build organization, its dependency-verification-metadata supply-chain hardening
mechanism, its benchmark and reference-test documentation, its Docker/Goss container testing,
and its externalized architecture documentation. Every claim is traceable to a file in the
vendored clone; where a specific line number isn't pinned down, the citation reads "see
`<file>`" rather than inventing one.

---

## Agentic tooling — minimal (confirmed: no separate file warranted)

Besu's entire AI-agentic footprint is a single 6-line file:

**`.github/copilot-instructions.md`** (verbatim):

```
## Code Review

- Focus on bugs, security issues, and correctness. Do not flag style nits.
- Do not suggest adding comments, javadocs, or type annotations to unchanged code.
- Do not flag naming conventions unless they cause confusion.
- Only flag issues that would affect functionality or maintainability.
```

That is the complete contents — no role/persona framing, no example diffs, no command
references, no boundaries beyond "review scope." Confirmed absent, via direct `find`/`grep`
across the whole tree (not just root):

- `AGENTS.md` — no match, any location
- `CLAUDE.md` — no match, any location
- `.claude/` — no such directory
- `.agents/` — no such directory
- `.cursor/` or `.cursorrules` — no match
- Any MCP server config (`mcp.json`, `.mcp.json`, or the literal string `mcpServers`) — no match anywhere in the tree
- Any skills-directory equivalent (`skills/`) — no such directory
- Any Spec-Kit-equivalent (`.specify/`, `speckit`, "spec-driven") — no match

This confirms the earlier call correctly: Besu does not warrant a separate
`agentic-tooling-pattern.md` file the way Nethermind, erigon, reth, and go-ethereum do —
there is no rules table, no `.agents/rules/*.md` topic files, no CI job that invokes an
LLM, nothing to build a dedicated pattern document around. It is folded into this one
section instead.

### `pre-review.yml` is not an AI reviewer, despite the name

`.github/workflows/pre-review.yml` (137 lines) is the one workflow name in the repo that
could plausibly suggest an AI-review gate. It is not — it is conventional Gradle CI. Job
names and their real steps, quoted verbatim:

- **`gradle-wrapper`** (`pre-review.yml:21-28`) — "Gradle Wrapper Validation": runs
  `gradle/actions/wrapper-validation` to confirm the checked-in `gradlew` wrapper JAR/script
  hasn't been tampered with.
- **`spotless-checkLicense`** (`:29-54`) — "Spotless & Check License": `./gradlew
  spotlessCheck` (code-formatting check, `:47`) then `./gradlew --no-parallel checkLicense`
  (dependency license-header/report check, `:49`, `--no-parallel` worked around due to a
  known `jk1/Gradle-License-Report` concurrency bug cited inline).
- **`compile`** (`:55-74`) — plain `./gradlew build -x test -x spotlessCheck`.
- **`verify-source-metadata`** ("Verify Dependency Source Metadata", `:76-94`) —
  `./gradlew verifySourceArtifacts`, checking the dependency-verification-metadata mechanism
  documented below.
- **`unitTests`** (`:95-125`) — `./gradlew ... test`.
- **`unittests-passed`** (`:137`) — a trivial gate job that fails if any prior job
  failed/was cancelled/was skipped, so branch protection can require one check name.

No step in any of the 15 `.github/workflows/*.yml` files (confirmed by a case-insensitive
grep for `claude|copilot|anthropic|openai|gpt|llm|ai-review` across all of them — zero
hits) invokes an LLM or AI review tool. "pre-review" names *pre-merge CI gating*, not an
AI code reviewer.

### `.lgtm.yml` — legacy cruft, not a model to copy

Root `.lgtm.yml` (full contents):

```yaml
extraction:
  java:
    index:
      java_version: "17"
      build_command: JAVA_OPTS="-Xmx1000M" ./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-11-openjdk-amd64 --no-scan compileJava
```

This configures LGTM.com, Semmle's hosted static-analysis platform (acquired by GitHub in
2019, and shut down in December 2022 after being absorbed into GitHub's native CodeQL code
scanning — which Besu already runs independently via `.github/workflows/codeql.yml`). The
file targets a dead service, references Java 17/11 (the repo now builds on Java 25 per
`gradle.properties`/CI), and is not wired into any active workflow. **Don't copy this
pattern** — it is vestigial configuration nobody has cleaned up, not an example of anything
worth porting.

---

## Gradle build organization (44 unique modules / 45 include statements)

`settings.gradle` (67 lines) declares **45 `include` statements**, but `datatypes` is
listed twice (`settings.gradle:37` and `:40`, straddling the `crypto:` block — apparent
copy-paste residue, not a real second module), so the real count is **44 unique Gradle
modules**. Full list, in file order (`settings.gradle:23-67`):

```
acceptance-tests:catalogless-test-plugins, acceptance-tests:dsl, acceptance-tests:tests,
acceptance-tests:tests:shanghai, acceptance-tests:tests:osaka, app, config,
consensus:clique, consensus:common, consensus:ibft, consensus:ibftlegacy, consensus:merge,
consensus:qbft, consensus:qbft-core, datatypes, crypto:algorithms, crypto:services,
ethereum:api, ethereum:blockcreation, ethereum:core, ethereum:eth, ethereum:evmtool,
ethereum:mock-p2p, ethereum:p2p, ethereum:permissioning, ethereum:referencetests,
ethereum:rlp, ethereum:ethstats, ethereum:trie, ethereum:verkletrie, evm,
metrics:core, metrics:rocksdb, nat, platform, plugin-api, plugins:health,
plugins:rocksdb, services:kvstore, services:pipeline, services:tasks, testfuzz,
testutil, util
```

Grouping-directory breakdown (immediate children on disk):

| Grouping dir | Gradle subprojects under it |
|---|---|
| `ethereum/` | 14 dirs on disk: `api, blockcreation, core, eth, ethstats, evmtool, mock-p2p, p2p, permissioning, referencetests, rlp, stratum, trie, verkletrie` — note `ethereum/stratum` exists on disk but has **no** corresponding `include` in `settings.gradle`, i.e. it's currently unreferenced/dead from the build's perspective |
| `consensus/` | 7: `clique, common, ibft, ibftlegacy, merge, qbft, qbft-core` |
| `crypto/` | 2: `algorithms, services` |
| `services/` | 3: `kvstore, pipeline, tasks` |
| `plugins/` | 2: `health, rocksdb` |
| `metrics/` | 2: `core, rocksdb` |
| `acceptance-tests/` | `catalogless-test-plugins, dsl, tests` (+ `tests:shanghai`, `tests:osaka` nested sub-subprojects); two more dirs on disk (`detached-outdated-test-plugins`, `detached-test-plugins`) are likewise not in `settings.gradle` |
| Single-module top-level dirs | `datatypes`, `evm`, `nat`, `platform`, `plugin-api`, `testfuzz`, `testutil`, `util`, `app`, `config` |

### Parallel build / build cache — set in `gradle.properties`, not `build.gradle`

`gradle.properties` (full contents):

```properties
org.gradle.welcome=never
org.gradle.parallel=true
# caching is disabled in .github/workflow configs. enabling it here allows developers to utilize LOCAL caches
# use --no-build-cache or --rerun-tasks to bypass if needed.
org.gradle.caching=true

# Optional - set custom build version
# version=24.5.6-acme
# versionappendcommit=true

# Optional, skip dependency verification for dev/debug builds
# org.gradle.dependency.verification=lenient

# Set exports/opens flags required by Google Java Format and ErrorProne plugins. (JEP-396)
org.gradle.jvmargs=-Xmx4g \
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
--add-opens jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
# Could be moved to sonar properties after https://sonarsource.atlassian.net/browse/SONARGRADL-134
systemProp.sonar.gradle.skipCompile=true
```

The comment on line 15 is explicit about *why* these flags exist: Google Java Format and
ErrorProne are annotation-processor-style tools that reach into `javac`'s internal API
surface (package `com.sun.tools.javac.*`), which the JDK's module system encapsulates by
default since JEP 396 (strong encapsulation of JDK internals, default-on since JDK 17) —
without these `--add-exports`/`--add-opens` flags the build would fail to even *invoke*
those tools on a modern JDK. Note CI deliberately disables the shared Gradle build cache
(per the inline comment) and only local developers benefit from `org.gradle.caching=true`.

`build.gradle` itself (1,539 lines / 55,519 bytes) contains no `org.gradle.parallel`/
`org.gradle.caching` settings (confirmed via grep — zero hits), but repeats the same class
of JVM-encapsulation flags at three further, more specific call sites, each solving a
different reflection-into-JDK-internals problem for a different execution context:

- `build.gradle:378-400` — the `test {}` task: `--add-opens java.base/java.util(...)`
  (Mockito/Jackson reflection) plus the same eight javac `--add-exports` (ErrorProne's own
  test suite needs the same internals as the annotation processor).
- `build.gradle:679-684` — the `integrationTest` task: `--add-opens
  java.base/java.util[.concurrent]=ALL-UNNAMED`.
- `build.gradle:863-874` — the main application's own JVM run args: `--add-opens
  java.base/sun.security.provider` (Bouncy Castle), `--add-opens java.base/java.util`
  (Jackson `OptionalLong`), `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`
  (Netty), `--add-opens java.base/java.nio`, plus `--enable-native-access=ALL-UNNAMED`.

The pattern worth naming: annotation-processor/build-tool encapsulation flags
(`gradle.properties`, applies to every Gradle-invoked JVM) and runtime encapsulation flags
(scattered through `build.gradle`'s task definitions, applies only to that specific task's
JVM) are kept separate rather than one global flag list — each flag is scoped to exactly
the JVM process that actually needs it.

---

## Dependency verification-metadata — supply-chain hardening

`gradle/verification-metadata.xml` is a large (10,070 lines / 644,942 bytes) generated file
that cryptographically hash-pins **every resolved dependency artifact** (jar, sources jar,
and pom, for every group:name:version in the dependency graph) using Gradle's native
[dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
feature. A representative entry (`verification-metadata.xml:7-16`):

```xml
<component group="aopalliance" name="aopalliance" version="1.0">
   <artifact name="aopalliance-1.0-sources.jar">
      <sha256 value="e6ef91d439ada9045f419c77543ebe0416c3cdfc5b063448343417a3e4a72123" origin="Generated by Gradle"/>
   </artifact>
   <artifact name="aopalliance-1.0.jar">
      <sha256 value="..." origin="Generated by Gradle"/>
   </artifact>
   <artifact name="aopalliance-1.0.pom">
      <sha256 value="..." origin="Generated by Gradle"/>
   </artifact>
</component>
```

The config header (`verification-metadata.xml:3-4`) sets `<verify-metadata>true</verify-metadata>`
and `<verify-signatures>false</verify-signatures>` — i.e. Besu verifies hashes but not GPG
signatures. With this file present, Gradle **fails the build** if any resolved artifact's
downloaded bytes don't match the pinned hash — the same class of attack the industry-wide
"CVE-2026-45321 Mini Shai-Hulud" npm incident (`supply-chain-security.md`) exploited by
publishing a malicious version under a trusted package name; hash-pinning at resolution
time is a build-tool-level defense against exactly that, independent of a 7-day
resolution-age gate.

Documented in **`README.md:75-83`**, quoted:

> #### Dependency Verification
>
> This project uses [Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html). When adding or updating dependencies, regenerate `gradle/verification-metadata.xml` with:
>
> ```shell
> ./gradlew --write-verification-metadata sha256 resolveSourceArtifacts
> ```
>
> The `resolveSourceArtifacts` task ensures source JARs are included in the metadata, which is required for IDE sync (e.g. IntelliJ automatically downloads sources).

This is enforced, not just documented-and-hoped-for: `build.gradle:547` defines the
`resolveSourceArtifacts` task ("Resolves source artifacts for all configurations so they
can be included in dependency verification metadata"), and `build.gradle:566-608` defines
a companion task, `verifySourceArtifacts`, that **fails the build** if
`verification-metadata.xml` is missing source-artifact entries, printing the exact same
regeneration command in its own failure message (`build.gradle:601`) — the same
`verify-source-metadata` job runs in CI (see above), so a PR that adds a dependency without
regenerating the metadata file fails CI with an actionable error rather than silently
merging with an unverified artifact.

**fukuii gap (tool-agnostic, not Gradle-specific).** fukuii is sbt-based and has **no**
equivalent mechanism at all — no hash-pinning of resolved Coursier/Ivy artifacts, no CI
step that would fail if a dependency's published bytes changed underneath a fixed version
string. This is a real gap independent of build tool choice: sbt/Coursier has no built-in
"verification-metadata" feature comparable to Gradle's, but the underlying idea — pin and
verify the actual bytes of every resolved dependency, not just its declared version — is a
supply-chain-hardening practice fukuii currently has zero coverage for. See the verdict
table.

---

## Benchmarking (thin) vs. Reference-test execution (substantive)

### `BENCHMARKING.md` — thin, command-syntax only (100 lines, read in full)

Confirmed: this file documents *only* how to invoke the JMH Gradle plugin, with no
discussion of benchmarking methodology, no baseline storage/comparison mechanism, and no CI
integration whatsoever (no mention of a workflow file anywhere in it). Structure:
prerequisites (Java 25+, gradle wrapper, optional async-profiler) → run all benchmarks in a
module (`./gradlew :ethereum:core:jmh`) → force a rerun (`--rerun-tasks`, since Gradle
otherwise treats an unchanged JMH task as up-to-date and skips it) → filter by
name/case with regex-capable `-Pincludes`/`-Pexcludes`/`-Pcases` (example,
`BENCHMARKING.md:40`: `./gradlew :ethereum:core:jmh -Pincludes=Mod -Pexcludes=Mul,Add,SMod
-Pcases=MOD_256_128,MOD_256_192 --rerun-tasks`) → `./gradlew help --task jmh` for the full
option surface → one concrete module-specific example (`ethereum:api:jmh
-Pincludes=EngineGetPayloadBodiesParallel`, benchmarking sequential-vs-parallel
`engine_getPayloadBodies*` DB lookups under simulated warm/cold RocksDB latency) →
async-profiler integration flags (`-PasyncProfiler=<path>`,
`-PasyncProfilerOptions="output=flamegraph"`, producing `flame-cpu-forward.html` /
`flame-cpu-reverse.html`) → a two-line sample JMH output block. Nothing here tells a
developer where results are meant to be stored, compared against a prior run, or gated in
CI — it is purely "how do I invoke the tool," full stop.

### `REFERENCE_TESTS.md` — the substantive document (204 lines, read in full)

This is where Besu's real test-execution documentation effort went. Three distinct,
genuinely useful mechanisms:

**1. Generated-test-class naming and hardfork/EIP filtering** (`REFERENCE_TESTS.md:18-75`).
Execution-spec-tests generate JUnit class names that encode their hardfork and EIP/topic
directly, following the pattern (`:64-67`, quoted exactly):

```
ExecutionSpec{Blockchain,State}Test_{hardfork}_{eip_or_topic}_{batch_index}
```

with real examples (`:70-73`): `ExecutionSpecBlockchainTest_prague_eip7702_set_code_tx_0`,
`ExecutionSpecStateTest_cancun_eip4844_blobs_2`,
`ExecutionSpecBlockchainTest_static_stCreate2_1`,
`ExecutionSpecBlockchainTest_frontier_opcodes_0`. Because the class name itself carries
hardfork/EIP metadata, ordinary Gradle `--tests` glob filters become a full hardfork/EIP
query language with no special tooling required — documented examples include by-hardfork
(`--tests "*ExecutionSpec*_prague_*"`, `:26`), by-EIP (`--tests "*eip7702*"`, `:39`), and
combined (`--tests "*_prague_eip2537_*"`, `:49`, filtering to just the Prague EIP-2537 BLS
precompile tests). A caveat (`:75`) notes this filtering only applies to the newer
execution-spec-tests-generated classes — the legacy `GeneralStateReferenceTest`/
`BlockchainReferenceTest` classes use plain sequential numbering and are instead filtered
via runtime system properties `test.ethereum.state.eips` / `test.ethereum.include`.

**2. `referenceTestsDevnet` — a separate task for pre-release fixtures**
(`REFERENCE_TESTS.md:77-113`). Besu maintains a second Gradle task, `referenceTestsDevnet`,
pulling in a distinct set of pre-release/devnet fixtures (e.g. for an upcoming Amsterdam
fork) via a separate `devnetTarConfig` dependency configuration declared in
`ethereum/referencetests/build.gradle`. Devnet-generated classes follow the same naming
convention with an `ExecutionSpecDevnet` prefix (`:98-102`). Critically, the default
`referenceTests` task **excludes** devnet fixtures (`:94`, "so CI is unaffected") — pinning
unstable, not-yet-final upcoming-fork test vectors is deliberately kept off the main CI
gate. The devnet-fixture version-bump procedure is a checklist (`:106-109`, quoted): "1.
Update the `version` in the `devnetTarConfig` dependency in
`ethereum/referencetests/build.gradle` 2. Make any required infrastructure changes (new
header fields, etc.) 3. Run `./gradlew --write-verification-metadata sha256` to update
checksums 4. Commit all changes together" — note step 3 ties directly back into the
verification-metadata mechanism above; bumping a test-fixture dependency is treated with
the same supply-chain hash-repinning discipline as any other dependency bump.

**3. `BlockAwareJsonTracer` — an opt-in JSON opcode tracer for test debugging**
(`REFERENCE_TESTS.md:115-192`). Enabled via either a JVM system property
(`-Dbesu.debug.traceBlocks=true`) or an environment variable
(`export BESU_TRACE_BLOCKS=true`); the enabling logic, quoted from
`org.hyperledger.besu.ethereum.mainnet.BlockAwareJsonTracer` (`:188-191`):

```java
if (Boolean.getBoolean("besu.debug.traceBlocks")
    || "true".equalsIgnoreCase(System.getenv("BESU_TRACE_BLOCKS"))) {
  return new BlockAwareJsonTracer();
}
```

It is a fallback tracer used only when no plugin already provides a custom
`BlockAwareOperationTracer`, and it emits per-opcode JSON (pc, opcode, gas, gas cost, stack,
memory size, call depth, refund — `:139-144`) into the Gradle test report HTML
(`build/reports/tests/test/index.html`, `:133`), not the console — a deliberate choice
given trace volume can be large per block.

**fukuii comparison.** fukuii's own analog to (1) is genuinely thinner: the EF-conformance
suite runs as one serial job, `comprehensive-ethereum-tests`, in
`.github/workflows/ethereum-tests-nightly.yml` (nightly cron + manual dispatch, 60-minute
timeout, single `sbt compile-all` then a KPI-baseline validation step) — there is no
hardfork/EIP-scoped test-class naming or `--tests`-style glob filtering surface, because
fukuii's EF-test adapter classes aren't currently generated with that metadata baked into
their names. This is a genuinely useful idea worth carrying forward, but it is a
test-generation-layer change (how fukuii's own EF-adapter test classes get named/generated),
not something that can be bolted on as a script or skill without first restructuring how
those classes are produced. fukuii's `Benchmark` sbt config (`build.sbt:240`, `Benchmark =
config("benchmark").extend(Test)`) backs exactly two hand-written timed ScalaTest suites
(`src/benchmark/scala/.../mpt/MerklePatriciaTreeSpeedSpec.scala` and
`.../rlp/RLPSpeedSuite.scala`) — thinner even than Besu's already-thin `BENCHMARKING.md`,
which at least wraps a real microbenchmark harness (JMH) with profiler integration; fukuii's
two suites are closer to ad hoc stopwatch tests.

---

## Docker (Goss-based container testing)

`docker/Dockerfile` (86 lines, two-stage, both base images pinned by `@sha256` digest, not
just tag):

```dockerfile
FROM mirror.gcr.io/library/eclipse-temurin@sha256:1bda4d9e668f44f399abed30636c34e0befb727408fba27b1e6aaefcf9df346b AS java-base
...
FROM mirror.gcr.io/library/ubuntu:24.04@sha256:c4a8d5503dfb2a3eb8ab5f807da5bc69a85730fb49b5cfca2330194ebcc41c7b
```

(Dockerfile:4 builder, :7 runtime — the `# syntax=` directive at the top of the file is
itself digest-pinned too.) Only the built JRE (`/opt/java/openjdk`) is copied out of the
builder stage (`:27`) — there is no Besu compile step inside this Dockerfile at all; the
actual `besu` distribution is produced separately by Gradle's `distDocker` task and copied
in (`:33`), meaning the Dockerfile is a runtime-packaging artifact, not a build artifact.
The image creates a fixed-UID-1000 `besu` user (first removing Ubuntu 23.10+'s default
UID-1000 `ubuntu` user to avoid a collision, `:20-23`), runs as that non-root user (`:30`,
dropping to `root` only transiently to `chmod +x` the entrypoint, `:66-67`), and defines a
`HEALTHCHECK` (`:72`).

`docker/pyroscope.properties` is a static config for the Pyroscope continuous-profiling
agent (server address, JFR event format, 10ms sampling interval, allocation/lock sampling,
15s upload interval), wired in via the Dockerfile's `PYROSCOPE_CONFIGURATION_FILE` env var
for operators who want always-on production profiling.

`docker/test.sh` (66 lines) is an architecture-detecting (amd64/arm64) smoke-test runner:
it invokes **Goss** (`github.com/goss-org/goss`, a YAML-driven server/container validation
tool) twice via `dgoss run` — once checking normal startup with RPC/WS/GraphQL ports live,
once checking data-directory permissions under a mounted volume — writing JUnit XML results
to `./reports/`. `docker/tests/README.md` (4 lines, quoted in full): "Besu Docker images are
validated using Goss ... Run `./gradlew testDocker` to download Goss scripts and execute
docker tests." Confirmed the wiring in `build.gradle:1129-1143`: `testDocker` is an `Exec`
task depending on `distDocker` (build the distribution) and `downloadGossBinaries`, which
then `cd`s into `docker/` and runs `./test.sh <image>` — i.e. the entire container-smoke-test
suite is declarative Goss YAML plus a thin bash driver, not a bespoke test framework.

---

## Externalized architecture docs

Besu's in-repo `docs/` is deliberately small and does **not** attempt to be a full
architecture reference:

```
docs/CHANGELOG_ARCHIVE.md   321KB — archived historical changelog entries
docs/PROFILING.md           4.4KB — Async Profiler (asprof) setup/usage guide
docs/README.md              266B  — points elsewhere (quoted below)
docs/trace_rpc_apis.md       1.3KB — trace_replayBlockTransactions deviations vs. other clients
docs/tracing/               OpenTelemetry JSON-RPC tracing walkthrough (docker-compose + otel-collector config)
docs/trie/parallel-merkle-trie.md — ParallelStoredMerklePatriciaTrie's 3-phase batched update design
```

`docs/README.md` (full contents, 3 lines): "Besu user documentation was moved to a separate
repository to help manage versions and releases. If you want to contribute to the doc
site, make a pull request against https://github.com/besu-eth/besu-docs / The generated doc
website is at https://docs.besu-eth.org/" — confirming the real user/architecture
documentation lives externally, in a separate `besu-docs` repository, published as a
versioned Docusaurus-style site. Root `README.md` reinforces the same pointer repeatedly
(`README.md:25`, `:41`, `:57`, `:62-63`, `:72-73`, `:103-104` — e.g. "The [Besu
documentation](https://docs.besu-eth.org/) answers many common questions"), and
`.github/pull_request_template.md` has an explicit checklist item instructing contributors
to add a `doc-change-required` label pointing at the external `besu-eth/besu-docs` repo
when a change needs a doc update — i.e. the doc-update workflow is a manual PR-labeling
convention bridging two repositories, not an in-repo doc directory contributors edit
directly.

**Contrast with fukuii, not a verdict on which is "right."** fukuii keeps its architecture
documentation in-tree: `docs/architecture/` (17 files) plus `docs/adr/` (32 architecture
decision records), both versioned alongside the code they describe and reviewable in the
same PR as the change that motivated them. Besu's externalized-docs pattern trades that
co-location for independent versioning/release cadence of the doc site (useful when a
docs team ships on a different schedule than the code, and when the doc site itself needs
its own build/deploy pipeline) at the cost of splitting "what changed" across two PRs in
two repositories. Neither approach is a mistake; fukuii's in-tree approach is already the
richer one for a single-maintainer-plus-agents project where doc and code review happen in
the same place, and there is no indication here that Besu's split-repo model would improve
anything for fukuii specifically.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable / Already ahead | Reasoning |
|---|---|---|
| 6-line `copilot-instructions.md` as Besu's entire agentic-tooling footprint | **Already ahead** | fukuii's `AGENTS.md`+`CLAUDE.md` split, 18 shared agent protocols, 22 operational skills, 13 Spec Kit skills, and the looping subsystem are categorically more developed than anything in Besu. Nothing to port; noted only to confirm the "doesn't warrant its own file" call was correct. |
| `pre-review.yml`'s name suggesting an AI reviewer, but being conventional CI | **Not portable (naming lesson only)** | No code to port — the useful takeaway is purely cautionary: a workflow named "pre-review" or similar should not be assumed to be an AI-review gate without reading it; fukuii's own `github-workflows.md` protocol already covers workflow-naming conventions and can note this as a real-world example of a misleading-by-name file. |
| `.lgtm.yml` (vestigial Semmle/LGTM.com config) | **Not portable** | Targets a service that shut down in 2022; explicitly called out above as "don't copy, it's cruft." fukuii has no equivalent file and should not add one — CodeQL (which fukuii doesn't currently run either, worth a separate check) is the living successor, not LGTM. |
| Gradle dependency `verification-metadata.xml` hash-pinning + CI-enforced regeneration (`verifySourceArtifacts`) | **Needs design** | This is a real, tool-agnostic supply-chain-hardening gap: fukuii (sbt/Coursier) has no mechanism that fails a build when a resolved dependency's actual bytes don't match a pinned hash — independent of the `~/.npmrc` `resolution-age` time-gate fukuii's own web projects already use. sbt/Coursier has no built-in Gradle-verification-metadata equivalent, so this would need either a custom sbt plugin/task that snapshots and checks SHA-256 hashes of resolved artifacts, or adopting a third-party tool if one exists for the Scala/Coursier ecosystem. Worth scoping as a discrete investigation, not a drop-in port. |
| JVM `--add-exports`/`--add-opens` split between `gradle.properties` (annotation-processor-time) and per-task blocks in `build.gradle` (runtime) | **Port now (as a documentation/organization habit)** | fukuii already runs on JDK 25 and Scala 3's own tooling doesn't reach into `javac` internals the way Google-Java-Format/ErrorProne do, so there's no direct flag to copy — but the *organizational principle* (scope JVM-encapsulation workaround flags to exactly the process that needs them, comment inline why each flag exists, don't accumulate a single global flag soup) is a good habit if/when fukuii's own build ever needs `--add-opens`-style flags for a JVM tool (e.g. a profiler agent, a reflection-heavy test library). |
| BENCHMARKING.md's thinness (command syntax only, no methodology/baseline/CI) | **Not portable / non-issue** | fukuii's own `Benchmark` sbt config (two ScalaTest speed specs) is thinner still, but Besu's thin doc isn't a pattern worth emulating either — it's a gap in Besu, not a model. No action for fukuii beyond noting fukuii's benchmark tooling is even less developed than this already-thin baseline. |
| REFERENCE_TESTS.md's hardfork/EIP-scoped generated-class naming + `--tests` glob filtering | **Needs design** | Directly useful *if* fukuii's own EF-conformance test-adapter classes were restructured to bake hardfork/EIP metadata into generated class names — today fukuii runs the whole EF suite as one serial job (`ethereum-tests-nightly.yml`, single `comprehensive-ethereum-tests` job, no per-hardfork/EIP scoping). This is a test-generation-layer redesign, not a script or skill that can be added on top of the current adapter classes; worth a future spec once/if the EF-adapter layer is revisited for other reasons. |
| `referenceTestsDevnet` — separate task + version-bump checklist for pre-release fixtures, explicitly excluded from the default CI-gating task | **Needs design** | The pattern (keep not-yet-final upcoming-fork test vectors on a separate, non-gating task, with an explicit bump checklist tied to dependency re-verification) is a clean idea fukuii could use once/if it wants to track upstream `ethereum/execution-spec-tests` devnet fixtures for an upcoming ETH fork ahead of them becoming final — currently fukuii's EF-test pipeline doesn't distinguish stable vs. devnet fixtures at all. |
| `BlockAwareJsonTracer` opt-in JSON opcode tracer gated by system property / env var, output routed to the test-report HTML rather than console | **Needs design** | The *mechanism* (a debug-only tracer, enabled by an env var or `-D` flag, that doesn't pollute normal console/log output) is a reasonable pattern for any EVM-implementing client's test-debugging story; fukuii would need its own equivalent hook point in its EVM interpreter's tracer plugin surface — not a direct port given fukuii's tracer architecture differs from Besu's `BlockAwareOperationTracer` plugin interface, but the "gate it behind an env var, write it to the test report, not stdout" shape is worth keeping in mind. |
| Docker multi-stage build with both base images pinned by `@sha256` digest (not just tag) | **Port now** | fukuii's Docker patterns (per `docker-deployment.md`) already recommend pinned base image *versions*, but digest-pinning (`@sha256:...` in addition to a tag) is a stricter, immediately-adoptable hardening step for any fukuii Dockerfile — no fukuii-specific redesign needed, purely additive. |
| Goss-based container smoke testing (`docker/test.sh` + `./gradlew testDocker`) | **Needs design** | fukuii has no equivalent declarative container-validation step today. Goss itself is a generic, language-agnostic tool (YAML assertions against a running container: ports open, files present, process running) — adoptable as-is for fukuii's own Docker images without any Scala-specific translation, but requires deciding what to assert (RPC ports, datadir permissions, healthcheck response) and wiring a CI job, so it's a scoped addition rather than a one-line copy. |
| Externalized user/architecture docs in a separate `besu-docs` repo, bridged by a PR-template label convention | **Not portable / fukuii already ahead** | fukuii's in-tree `docs/architecture/` (17 files) + `docs/adr/` (32 ADRs), reviewed in the same PR as the code they describe, is already a stronger co-location model for a project of fukuii's size and single-maintainer-plus-agents workflow. Besu's split exists to support independent doc-team release cadence, a concern fukuii doesn't currently have. No action — noted as a deliberate non-adoption, not an oversight. |
| `datatypes` module double-included in `settings.gradle` (lines 37 and 40) | **Not portable (observation only)** | A minor, harmless duplicate-include in Besu's own build file — not a pattern to emulate or a gap to fix in fukuii; noted only because it was independently verified while enumerating the module list and is worth knowing about if this doc is ever used to cross-check "does fukuii have anything similarly redundant in `build.sbt`'s module list" (a cheap, no-infrastructure sprint-boundary spot-check, same habit recommended in the Nethermind build-release-pattern.md verdict table). |

---

*Compiled from a direct read of every file cited above in the vendored clone at
`.claude/repo-references/clients/besu/`. Line numbers refer to that clone's current
checkout (`HEAD` `3fd233a4f9`, 2026-07-01); re-verify against `git log` if the vendored
copy is refreshed.*
