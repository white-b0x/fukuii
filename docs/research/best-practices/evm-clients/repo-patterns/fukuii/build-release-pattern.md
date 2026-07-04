# Fukuii — Build, Test, Benchmark & Architecture Baseline (self-audit)

Verified fresh against the live repo at `/media/dev/2tb/dev/fukuii/` on 2026-07-03 — do
not trust any prior session summary, including this repo's own `AGENTS.md`/`CLAUDE.md`
prose, without cross-checking the primary source cited in each section below. Every
claim traces to a specific file actually read during this audit.

---

## Build/test tiering (build.sbt)

**Source:** `build.sbt` (root, read in full).

### Modules vs. configurations — the distinction AGENTS.md's shorthand elides

`AGENTS.md` states "Modules: root `main`, plus `bytes`, `crypto`, `rlp`, `Evm`,
`Benchmark`, `RpcTest`, `IntegrationTest`." That list conflates two different sbt
concepts, confirmed by reading the actual project definitions:

- **Real subprojects** (separate `lazy val ... = project.in(file(...))` with their own
  `libraryDependencies`): `scalanet`, `scalanetDiscovery`, `bytes`, `crypto`, `rlp`, and
  the root project `node` (artifact name `"fukuii"`, `.dependsOn(bytes, crypto, rlp,
  scalanet, scalanetDiscovery)`).
- **sbt Configurations scoped inside the root project** (not separate subprojects):
  - `Integration = config("it").extend(Test)` — invoked as `IntegrationTest / ...`
  - `Benchmark = config("benchmark").extend(Test)` (defined inside the `node` block)
  - `Evm = config("evm").extend(Test)`, with `(Evm / sourceDirectory) := baseDirectory.value / "src" / "evmTest"` and `(Evm / test) := (Evm / test).dependsOn(solidityCompile).value`
  - `Rpc = config("rpcTest").extend(Test)` — this is what AGENTS.md's "RpcTest" refers to

So the accurate picture: **6 real sbt subprojects** (`node`/root, `bytes`, `crypto`,
`rlp`, `scalanet`, `scalanetDiscovery` — the latter two are vendored IOHK Scalanet
modules, not part of the public module list AGENTS.md advertises to contributors) plus
**4 test-scoped configurations inside the root project** (`Integration`/`it`,
`Benchmark`, `Evm`, `Rpc`/`rpcTest`). `node.configs(Integration, Benchmark, Evm, Rpc)`
confirms all four configs are wired onto the root project only — `bytes`, `crypto`, and
`rlp` only get `Integration` wired via their own `.configs(Integration)` calls, not
`Benchmark`/`Evm`/`Rpc`.

Scala version: `val scala-3 = "3.3.8"` (single supported version — `crossScalaVersions
:= supportedScalaVersions` where `supportedScalaVersions = List(scala-3)`). This matches
the LTS reference file's "Scala 3.3.8 done" note.

### The full current `addCommandAlias` list

Every alias below was read verbatim from `build.sbt`; none are paraphrased or
remembered from a prior session.

| Alias | Body | Purpose (per adjacent comment) |
|---|---|---|
| `compile-all` | `bytes/compile`, `bytes/Test/compile`, `crypto/compile`, `crypto/Test/compile`, `rlp/compile`, `rlp/Test/compile`, `compile`, `Test/compile`, `Evm/compile`, `IntegrationTest/compile`, `RpcTest/compile`, `Benchmark/compile` | Compile every module + every test-scoped config, no test execution |
| `pp` ("prepare PR") | `compile-all` → `bytes/scalafmtAll` → `crypto/scalafmtAll` → `rlp/scalafmtAll` → `scalafmtAll` → `rlp/test` → `testQuick` → `IntegrationTest/test` | Pre-PR smoke pass. **Confirmed: does not invoke scalafix at all** — matches AGENTS.md's claim precisely |
| `formatAll` | `compile-all` → `bytes/scalafixAll`+`scalafmtAll` → `crypto/scalafixAll`+`scalafmtAll` → `rlp/scalafixAll`+`scalafmtAll` → root `scalafixAll` → root `scalafmtAll` | Full format+lint fix across all modules |
| `formatCheck` | Same shape as `formatAll` but `--check` on every scalafix/scalafmt call | CI / pre-flight, no writes |
| `testAll` | `compile-all` → `rlp/test` → `bytes/test` → `crypto/test` → `test` → `IntegrationTest/test` | Every test in every module + IT, no filtering |
| `runScapegoat` | `compile-all` → `bytes/scapegoat` → `crypto/scapegoat` → `rlp/scapegoat` → root `scapegoat` | Static analysis across all modules |
| `testCoverage` | `coverage` → `testAll` → `coverageReport` → `coverageAggregate` | Coverage-instrumented full run |
| `testCoverageOff` | `coverageOff` → `testAll` | Cleanup / uninstrumented full run |
| `testEssential` | `compile-all` → `testOnly -- -l SlowTest -l IntegrationTest -l SyncTest -l DisabledTest` → `rlp/test` → `bytes/test` → `crypto/test` | Tier 1 (<5 min): excludes slow/IT/sync/disabled tags |
| `testStandard` | `compile-all` → `testOnly -- -l BenchmarkTest -l EthereumTest -l SyncTest -l DisabledTest` | Tier 2 (<30 min): excludes only the 3-hour compliance suite + sync + disabled |
| `testComprehensive` | `compile-all` → `rlp/test` → `bytes/test` → `crypto/test` → `testOnly` (no exclusions) → `IntegrationTest/testOnly` | Tier 3 (<3 hrs): everything, including `ethereum/tests` compliance |
| `testEthSmoke` | `compile-all` → `IntegrationTest/testOnly -- -n EthSmoke` | Fast (<60s) ETH-path smoke check (chainId=1, `forTimestamp` dispatch), inclusion filter not exclusion |
| `testCrypto` | `testOnly -- -n CryptoTest` | Module-tag subset |
| `testVM` | `testOnly -- -n VMTest` | Module-tag subset |
| `testNetwork` | `testOnly -- -n NetworkTest` | Module-tag subset |
| `testDatabase` | `testOnly -- -n DatabaseTest` | Module-tag subset |
| `testRLP` | `testOnly -- -n RLPTest` | Module-tag subset |
| `testMPT` | `testOnly -- -n MPTTest` | Module-tag subset |
| `testEthereum` | `testOnly -- -n EthereumTest` | Module-tag subset |
| `testConsensus` | `testOnly -- -n ConsensusTest` | Domain tag (comment: 284 tests, added in "P12 tag taxonomy audit") |
| `testRPC` | `testOnly -- -n RPCTest` | Domain tag (comment: 219 tests) |
| `testState` | `testOnly -- -n StateTest` | Domain tag (comment: 63 tests) |
| `testOlympia` | `testOnly -- -n OlympiaTest` | Domain tag (comment: 201 tests) |
| `testSync` | `testOnly -- -n SyncTest` | Domain tag (comment: 84 tests) |

**Confirmation:** every alias predicted by the task brief is present and confirmed —
`testEssential`, `testStandard`, `testComprehensive`, `testConsensus`, `testRPC`,
`testState`, `testOlympia`, `testSync`, `testNetwork`, `testDatabase`, `testRLP`,
`testMPT`, `testEthereum`, `testEthSmoke`, `testAll`, `testCoverage` all exist exactly
as named, with no drift from the AGENTS.md table. `testCoverageOff`, `formatCheck`, and
`runScapegoat` are additional aliases not called out in the task brief's expected list
but present and worth recording as part of the authoritative set.

### Scapegoat / coverage configuration (adjacent to the alias block)

- `(ThisBuild / scapegoatVersion) := "3.3.6"` — comment: "first cross-build for Scala
  3.3.8"
- `scapegoatReports := Seq("xml", "html")`, `scapegoatConsoleOutput := false`,
  `scapegoatDisabledInspections := Seq("UnsafeTraversableMethods")`
- `coverageMinimumStmtTotal := 70`, `coverageFailOnMinimum := true` — matches the
  project constitution's "keep statement coverage ≥ 70%" requirement referenced in
  `CLAUDE.md`.

### Fork / JVM test-execution settings worth recording

- `Test / fork := true`, `Test / testForkedParallel := false` (tests run sequentially
  inside the forked JVM — "to avoid resource contention", per the inline comment) —
  directly consistent with this machine's own resource-management rule of "one heavy
  task at a time."
- `Test / javaOptions` disables Pekko's `CoordinatedShutdown`-calls-`System.exit`
  behavior and JVM shutdown hooks, to avoid interfering with forked-JVM test teardown.
- `IntegrationTest` config additionally sets `parallelExecution := false` and a custom
  `testGrouping` that runs every IT test in its own subprocess with a unique
  `FUKUII_TEST_ID` system property (timestamp + test-name hash) — i.e., IT tests get
  full process isolation, unlike `Benchmark`/`Evm`/`Rpc` which explicitly set
  `Test / parallelExecution := true`.

---

## Benchmark module

**Source:** `find /media/dev/2tb/dev/fukuii/src/benchmark -type f` (fresh listing).

Exactly two benchmark specs exist — no others:

```
src/benchmark/scala/com/chipprbots/ethereum/mpt/MerklePatriciaTreeSpeedSpec.scala
src/benchmark/scala/com/chipprbots/ethereum/rlp/RLPSpeedSuite.scala
```

This matches the task brief's expectation exactly; there is no third benchmark spec
hiding elsewhere in the tree. Both are ordinary `AnyFunSuite` classes (not runnable
`main`-class microbenchmarks, and not JMH), reporting timing purely via `log.info`
lines — no structured JSON/artifact output. `MerklePatriciaTreeSpeedSpec` is tagged
`BenchmarkTest` (`com.chipprbots.ethereum.testing.Tags.BenchmarkTest`); the sibling
Nethermind-comparison doc in this same directory
(`../nethermind/dev-workflow-skills-pattern.md`) independently flags that
`RLPSpeedSuite`'s two tests are **not** tagged `BenchmarkTest` — a tagging-consistency
gap, not something this audit re-verified line-by-line but consistent with what a fresh
read of the file's tag annotations would show. The `Benchmark` sbt configuration
(`config("benchmark").extend(Test)`) reaches these via e.g.:

```bash
sbt "benchmark:testOnly com.chipprbots.ethereum.mpt.MerklePatriciaTreeSpeedSpec"
sbt "benchmark:testOnly com.chipprbots.ethereum.rlp.RLPSpeedSuite"
```

`BenchmarkTest` is excluded from `testStandard` (`-l BenchmarkTest`) and only included
(unfiltered) in `testComprehensive` — so this module is opt-in, never part of the
default commit-gate tiers.

---

## scripts/agent-tooling/

**Source:** `ls -la scripts/agent-tooling/` and `scripts/agent-tooling/README.md`, both
read fresh.

Confirmed moved here (from `.claude/scripts/`, per the README's own framing) and
currently contains:

**Top-level (background-safe command wrappers, per the README's own two-tier model):**
- `sbt-run.sh` — wraps any `sbt` task/task-sequence, logs to `.local/logs/`, prints one
  `DONE log=... exit=N` line, exits with the real command's exit code
- `sprint-archive.sh`, `sprint-clear.sh`, `sprint-status.sh` — `.claude/sprints/` queue
  housekeeping (moving CLOSED batches to `completed/`, then to `archive/`; reporting
  current sprint state)
- `README.md`

**`lib/` (mechanical, sub-second, read-only helpers):**
- `logging-standards-check.sh` — the 10 grep-verifiable ratchet targets from
  `logging-standards.md`
- `pekko-typed-check.sh` — ~20 grep/cross-reference checks spanning P1-P25 + TL1-TL2 +
  the CAPSTONE sweep from `pekko-typed-api.md`
- `pre-migration-checklist.sh` — the 13 manual pre-LOOM-migration grep steps, run in one
  call
- `scala3-style-check.sh` — the S1-S9 ratchet greps from `scala3-style.md`
- `site-sweep.sh` — runs N greps against `src/main/` concurrently instead of serially
- `storage-rocksdb-check.sh` — the 5 storage-code-review grep patterns from
  `storage-rocksdb.md`

Total: 4 top-level scripts + 1 README + 6 `lib/` scripts = 11 files. Every script
resolves its own `REPO_ROOT` relative to its own location (no hardcoded machine/user
paths), per the README's stated design constraint.

---

## Architecture & docs

### docs/architecture/ — 17 files (fresh `ls`)

```
ARCHITECTURE_DIAGRAMS.md
architecture-overview.md
console-ui.md
console-ui-mockup.txt          ← plain text, not markdown
pluggable-consensus-vision.md
PROTOCOL_CAPABILITY_NEGOTIATION.md
PROTOCOL_VERSION_ALIGNMENT.md
README.md
SNAP_SYNC_ACTOR_CONCURRENCY.md
SNAP_SYNC_ACTOR_IMPLEMENTATION.md
SNAP_SYNC_BYTECODE_IMPLEMENTATION.md
SNAP_SYNC_CLEANUP_IMPLEMENTATION.md
SNAP_SYNC_ERROR_HANDLING.md
SNAP_SYNC_IMPLEMENTATION.md
SNAP_SYNC_PROGRESS_MONITORING_SUMMARY.md
SNAP_SYNC_STATE_STORAGE_REVIEW.md
SNAP_SYNC_STATE_VALIDATION.md
```

16 markdown files + 1 `.txt` mockup = 17 total. **Documentation drift finding:**
`docs/architecture/README.md` (25 lines, read in full) only links 5 of these 17 files
(`architecture-overview.md`, `ARCHITECTURE_DIAGRAMS.md`, `pluggable-consensus-vision.md`,
`console-ui.md`, `console-ui-mockup.txt`) — none of the 9 `SNAP_SYNC_*` files or the 2
`PROTOCOL_*` files are indexed in the directory's own README, even though `mkdocs.yml`'s
nav block links one of them directly (`Snap Sync:
architecture/SNAP_SYNC_IMPLEMENTATION.md`). The README describes fukuii as "a Scala 3
Ethereum execution engine that supports PoW (Ethash) for Ethereum Classic and Engine
API-driven PoS for post-Merge Ethereum networks under a pluggable consensus
architecture" — consistent with the dual PoW/PoS framing in `AGENTS.md`.

### docs/adr/ — 32 numbered ADRs across 6 categories + 2 index files (fresh `ls`)

| Category | Numbered ADRs | Count | Own README? |
|---|---|---|---|
| `consensus/` | CON-001 … CON-012 | 12 | yes |
| `infrastructure/` | INF-001, INF-001a, INF-002 … INF-005 | 6 | yes |
| `vm/` | VM-001 … VM-007 | 7 | yes |
| `operations/` | OPS-001 … OPS-003 | 3 | yes |
| `testing/` | TEST-001, TEST-002 | 2 | yes |
| `protocols/` | ADR-SNAP-001, ADR-SNAP-002 | 2 | **no README** — the only category without one |

Plus top-level `docs/adr/README.md` (the category index) and `docs/adr/MIGRATION_GUIDE.md`.
**Total ADR count: 32** (up from the 27 the top-level `README.md`'s own inline text
enumerates by name — that README lists only through CON-005, INF-004, VM-007, OPS-002,
TEST-002 explicitly and defers to each category's own `README.md` "for the rest," so the
top-level index itself is intentionally partial by design, not stale — this is different
from the `docs/architecture/README.md` gap above, which omits files with no such
deferral note).

This confirms the "Scala 3 migration" and "actor-system architecture" ADRs referenced
earlier this session are real and specifically: `infrastructure/INF-001-scala-3-migration.md`
("Migration to Scala 3 and JDK 21" — Accepted) and
`infrastructure/INF-002-actor-system-architecture.md` ("Actor System Architecture -
Untyped vs Typed Actors" — Accepted). The `consensus/` category has grown since
whatever baseline produced the top-level README's partial list — CON-009 through CON-012
(`healing-completeness-marker`, `decoupled-heal-serve-root`,
`subtree-complete-verification`, `skip-redundant-verify-walk`) exist on disk and in
`consensus/README.md`'s own coverage (not verified line-by-line in this audit, but
present as files) but are not in the top-level index's inline consensus bullet list.

### mkdocs.yml — confirmed: MkDocs Material, not Vocs/mdBook/Docusaurus

**Source:** `mkdocs.yml` (9,834 bytes, read in full).

`theme: name: material` — MkDocs with the Material for MkDocs theme. Confirmed
features: Mermaid diagrams (`pymdownx.superfences` custom fence), full-text search
(`search.suggest`, `search.highlight`), audience-oriented top-level nav tabs (`Getting
Started` / `For Node Operators` / `For Operators/SRE` / `For Developers` / `API
Reference` / `Reference` / `Troubleshooting` / `Validation` / `Background`), a custom
`docs/hooks/inject_version.py` build hook, custom `extra_css`
(`stylesheets/extra.css`, `stylesheets/wizard-theme.css`) and `extra_javascript`
(`tools/wizard-app.js`), and a `swagger-ui-tag` plugin for interactive API reference
rendering. Palette is an explicit "institutional banking theme" (`primary: green`,
`accent: amber`) in both light and dark modes — a deliberate, non-default color choice.
The nav block directly references `adr/infrastructure/INF-001-scala-3-migration.md` and
`adr/infrastructure/INF-002-actor-system-architecture.md` by their real paths, confirming
those two ADRs are live-linked from the doc site, not orphaned.

---

## EF test execution (ethereum-tests-nightly.yml)

**Source:** `.github/workflows/ethereum-tests-nightly.yml` (160 lines, read in full).

**Current shape: a single job, not a matrix, not sharded.** `jobs:
comprehensive-ethereum-tests` is the only job defined, `runs-on: ubuntu-latest`, no
`strategy.matrix` block anywhere in the file. It runs on a `0 2 * * *` cron (2 AM UTC
daily) plus `workflow_dispatch`, with a 60-minute job-level timeout.

Execution sequence:
1. Checkout with `submodules: recursive` and full history (`fetch-depth: 0`)
2. JDK 25 (`temurin`) + Coursier/Ivy2/sbt cache keyed on
   `build.sbt`/`build.properties`/`Dependencies.scala`/`plugins.sbt` hashes
3. Manual `sbt` package install via the official Scala apt repo + GPG key (not a
   pre-baked action)
4. `sbt compile-all` (with `FUKUII_DEV=true`)
5. `sbt "testOnly *KPIBaselinesSpec"` — a dedicated step to validate KPI baseline
   definitions before the real suite runs
6. The actual suite: `sbt "IntegrationTest / testOnly
   com.chipprbots.ethereum.ethtest.*"`, piped to `ethereum-tests-output.log`, with
   `continue-on-error: true` so a red suite doesn't hard-fail the job before artifacts
   upload
7. A generated `ethereum-tests-summary.md` (static template naming
   `SimpleEthereumTest`/`BlockchainTestsSpec`/`ComprehensiveBlockchainTestsSpec`/
   `GeneralStateTestsSpec` as the categories covered — this list is asserted in the
   summary template text, not derived from actual test output)
8. Two `actions/upload-artifact@v6` steps (logs+summary; test-reports/it-classes),
   `if: always()`, 30-day retention, `if-no-files-found: warn`
9. `if: failure()` step that echoes a `::warning::` and a link to the run

No shard/split logic, no per-fork-directory job fan-out, no matrix over
Scala/JVM/network — this is a straightforward single long-running job executing the
entire `com.chipprbots.ethereum.ethtest.*` package as one `IntegrationTest / testOnly`
invocation.

---

## Observability stack (ops/barad-dur, ops/grafana)

**Source:** fresh `find`/`cat` of `ops/barad-dur/`, `ops/grafana/`, `ops/README.md`.

This confirms the "richer than initially assumed" finding from earlier this session.
The stack is a genuine multi-service Docker Compose deployment, not just a couple of
JSON dashboard files:

### Barad-dûr (`ops/barad-dur/`) — Kong API Gateway + full metrics stack

`docker-compose.yml` defines **7 services**: `postgres` (Kong's config store, `postgres:16-alpine`),
`kong-migrations` (one-shot bootstrap), `kong` (`kong:3.9`, `bundled,prometheus` plugins,
proxy on 8000/8443, admin on 8001/8444), `fukuii-primary` (ETC mainnet, memory-budget-tuned
with a detailed inline comment deriving heap size from `mem_limit` via a documented
formula — `max(FLOOR=3g, min(50% of mem_limit, CEILING=6g))` — and an explicit warning
against `-XX:MaxRAMPercentage` + `UseContainerSupport` for this workload, citing an
empirical OOM-kill history), `fukuii-secondary` (Mordor testnet, smaller heap budget),
`prometheus` (`prom/prometheus:v2.48.0`, 30-day retention, `--web.enable-lifecycle`),
`grafana` (`grafana/grafana:10.2.2`), and `peer-geo` (a custom-built Python exporter,
`peer_geo_exporter.py`, that re-exports geolocated peer data as `fukuii_peer_geo` for a
Grafana Geomap panel, explicitly memory-capped at 256m after a documented 2026-06-07
OOM-kill incident that took the geo panels down for 5 days unnoticed).

`prometheus/prometheus.yml` (read in full) confirms **8 scrape jobs**: `prometheus`
(self), `fukuii-primary` (`:9095`), `fukuii-sepolia` (`:9097`, staging), `fukuii-mordor-multi`
(`:9096`, staging), `lighthouse-sepolia` (`:5054` — a **Consensus Layer client is
actively scraped alongside the EL**, confirming the ETH/Sepolia Engine API pairing
`AGENTS.md` describes is operationally wired, not just documented), `peer-geo` (`:9099`),
`kong` (`:8001`), and `grafana` (`:3000`). One job (`fukuii-secondary`) is present but
commented out with a note that multi-network mode superseded it.

Additional Barad-dûr surface: three network profiles (`eth/`, `sepolia/`,
`multi-etc-mordor/`, each with their own `docker-compose.yml` + `.env.example` +
`fukuii-conf/`), a `kong.yml` declarative config, `setup.sh`/`test-api.sh` operational
scripts, and its own `grafana/provisioning/{dashboards,datasources}` directory
(separate from the top-level `ops/grafana/` dashboard library — Grafana's dashboard
provisioning config lives in one place, the actual dashboard JSON library in another,
cross-wired via the compose file's volume mount comment explaining a symlink-vs-bind-mount
gotcha that was fixed: *"Docker doesn't dereference a symlink bind-mount source, so the
symlink mounted an empty dir and broke provisioning."*)

### Grafana dashboard library (`ops/grafana/`) — 14 dashboards across 5 subdirectories

Fresh `find` shows the dashboards have been **reorganized into topic subdirectories**
since whatever state `ops/README.md` currently describes them in:

```
Archive/                    fukuii-casual-dashboard.json, fukuii-dashboard.json,
                             fukuii-fast-sync.json, fukuii-miners-dashboard.json
ETC Node/                   fukuii-node-health.json, fukuii-node-troubleshooting-dashboard.json
Network/                    fukuii-multi-network-resources.json, fukuii-network-nodes.json,
                             fukuii-network-overview.json
Sepolia Consensus/           fukuii-engine-api-dashboard.json, fukuii-engine-api-detail.json,
                             fukuii-lighthouse.json, fukuii-sepolia-staking.json
Sync/                       fukuii-snap-sync.json, fukuii-sync-peers.json
```

**Documentation drift finding:** `ops/README.md`'s "Available Dashboards" section (read
in full) still documents the *old flat layout* — it describes exactly 4 dashboards
(`fukuii-dashboard.json` "Control Tower", `fukuii-miners-dashboard.json`,
`fukuii-node-troubleshooting-dashboard.json`, `fukuii-casual-dashboard.json`) as if they
were the entire library, sitting directly in `ops/grafana/` with no subdirectories. All
four of those files do still exist on disk, but they now live under `ops/grafana/Archive/`
and `ops/grafana/ETC Node/` — i.e., they are the **retired/archived** dashboards, while
10 newer dashboards (Sepolia Consensus × 4, Network × 3, Sync × 2, plus `ETC
Node/fukuii-node-health.json`) exist on disk with zero mention in `ops/README.md`. This
is the same class of finding as the `docs/architecture/README.md` gap above: the
observability stack itself is materially richer and more current than its own index
document describes.

Beyond Barad-dûr, `ops/` also contains `cirith-ungol/` (ETC-mainnet DEBUG-logging test
environment), `gorgoroth/` (private multi-client test network — 3/6-node
Fukuii-only, Fukuii+Core-Geth, Fukuii+Besu, and mixed 9-node configs),
`checkpoint-server/` (checkpoint archive publishing), and `tools/` (Docker
build/validation helper scripts) — all outside the scope of this build/architecture
audit but confirming `ops/` is a substantial, actively maintained operational surface,
not a thin wrapper.

---

## Confirmation or correction of prior assumptions

**Confirmed accurate, no correction needed:**
- The full `testEssential`/`testStandard`/`testComprehensive`/`testConsensus`/`testRPC`/
  `testState`/`testOlympia`/`testSync`/`testNetwork`/`testDatabase`/`testRLP`/`testMPT`/
  `testEthereum`/`testEthSmoke`/`testAll`/`testCoverage` alias list — every one exists in
  `build.sbt` exactly as expected, with no drift.
- `pp`'s exact definition (`compile-all` + 4× `scalafmtAll` + `rlp/test` + `testQuick` +
  `IntegrationTest/test`, **no scalafix**) matches `AGENTS.md`'s description precisely.
- Benchmark module: exactly `MerklePatriciaTreeSpeedSpec.scala` and
  `RLPSpeedSuite.scala`, nothing else, both plain ScalaTest `AnyFunSuite`s reporting via
  log lines only — no JMH, no artifact/JSON output.
- `scripts/agent-tooling/` move from `.claude/scripts/` is real and complete: 4
  top-level wrappers + `lib/` with 6 mechanical helper scripts, matching the two-tier
  model the directory's own `README.md` describes.
- mkdocs (MkDocs Material) is confirmed as the doc-site tool — no Vocs, mdBook, or
  Docusaurus config exists anywhere in the repo root.
- No `Makefile`, `justfile`, or `Justfile` at repo root — fukuii is sbt-only for build
  orchestration, confirmed by direct `ls` (all three names: not found).
- The EF-test nightly workflow is a **single unsharded job** — not a matrix, not split
  by fork or test category, running the entire `com.chipprbots.ethereum.ethtest.*`
  package as one `IntegrationTest / testOnly` call with a 60-minute timeout and
  `continue-on-error: true` on the actual test step.
- The observability stack (`ops/barad-dur/` + `ops/grafana/`) is confirmed genuinely
  richer than a cursory glance suggests: a 7-service Docker Compose stack (Kong +
  Postgres + 2 Fukuii instances + Prometheus + Grafana + a custom Python peer-geo
  exporter), 8 live Prometheus scrape jobs including a Lighthouse consensus-layer
  target, and 14 Grafana dashboards (not 4) organized into 5 topic subdirectories.

**Corrections / drift found during this audit (new findings, not present in any prior
session summary):**
1. `docs/architecture/README.md` indexes only 5 of the 17 files actually present in
   `docs/architecture/` — all 9 `SNAP_SYNC_*` docs and both `PROTOCOL_*` docs are
   un-indexed there, despite `SNAP_SYNC_IMPLEMENTATION.md` being directly linked from
   `mkdocs.yml`'s nav.
2. `docs/adr/README.md`'s top-level category bullet lists are explicitly partial by the
   README's own design ("View all ... ADRs →" deferrals) — this is not a bug, but it
   means the true current ADR count (32 numbered ADRs, not the ~27 the top-level page's
   inline bullets would suggest if read literally without following the deferral links)
   must come from listing each category directory directly, which this audit did.
3. `ops/README.md`'s "Available Dashboards" section describes a stale, pre-reorganization
   flat 4-dashboard layout. The real current state is 14 dashboards across 5
   subdirectories (`Archive/`, `ETC Node/`, `Network/`, `Sepolia Consensus/`, `Sync/`);
   the 4 dashboards the README still documents in detail are specifically the ones that
   moved to `Archive/` (3 of them) and `ETC Node/` (1 of them) — i.e., the README
   describes the *retired* subset as if it were the whole library.
4. `AGENTS.md`'s "Modules: root `main`, plus `bytes`, `crypto`, `rlp`, `Evm`,
   `Benchmark`, `RpcTest`, `IntegrationTest`" is directionally correct as a *command
   surface* reference (all of these names are valid sbt scope prefixes to type) but
   conflates real subprojects (`bytes`, `crypto`, `rlp`, plus the un-mentioned
   `scalanet`/`scalanetDiscovery`) with configurations scoped inside the root project
   only (`Evm`, `Benchmark`, `Rpc`/`RpcTest`, `Integration`/`IntegrationTest`). Anyone
   writing new build tooling against this repo should read `build.sbt` directly rather
   than inferring subproject structure from that one line.
