# Nethermind — Build, Test, Benchmark & Architecture Patterns

Source: `.claude/repo-references/clients/nethermind/` (vendored full clone, verified genuine
git checkout — `.git/` present, working tree matches `NethermindEth/nethermind`)

This document covers Nethermind's multi-solution build layout, its reproducible-benchmark
CI mechanism (`run-expb-reproducible-benchmarks.yml`), its generated-from-source
documentation tooling (`tools/DocGen/`), and cross-checks `AGENTS.md`'s own claims about
project structure against the actual `src/Nethermind/` directory listing. Every claim below
is traceable to a file in the vendored clone; where a specific line number isn't pinned down,
the citation reads "see `<file>`" rather than inventing one.

---

## Multi-solution build organization (.slnx files)

Nethermind has fully migrated off `.sln` to the newer XML-based **`.slnx`** solution format
(no `.sln` file exists anywhere in the vendored clone). `.slnx` is a plain XML manifest of
`<Project Path="..."/>` entries with `<Folder>` groupings for solution-explorer organization —
there is no build logic embedded in the file itself; it is purely a "which `.csproj` files
belong to this solution" list that `dotnet build`/`dotnet test --solution <file>` consume to
scope a restore/build/test run to exactly that project graph.

### The four `src/Nethermind/*.slnx` solutions

`AGENTS.md` (`AGENTS.md:48-54`) states the codebase "is organized into three independent
solutions" and names `Nethermind.slnx`, `EthereumTests.slnx`, and `Benchmarks.slnx`. Listing
the directory directly shows a **fourth**: `Stateless.slnx` (`src/Nethermind/Stateless.slnx`).
This is a real, if minor, staleness in `AGENTS.md`'s own project-structure section — the file
exists, is non-trivial (25 project references), and is not mentioned anywhere in the "Project
structure" prose. See the verdict table for how this maps to a fukuii lesson about
self-documenting build metadata.

| Solution file | Project count | What it covers |
|---|---|---|
| `Nethermind.slnx` | 120 `<Project>` entries | The full client: `Runner`, all consensus/network/storage/RPC libraries, all plugins (Flashbots, Hive, Merge, OpcodeTracing, Signer, StateDiffsWriter, TraceStore, UPnP — each its own `<Folder>`), and the full `*.Test` suite for those libraries. This is "the app" solution — what CI builds and what `dotnet run --project Nethermind.Runner` ships from. |
| `EthereumTests.slnx` | 72 `<Project>` entries | The Ethereum Foundation (EF) test-vector runner: `Ethereum.*.Test` projects (Abi, Basic, Blockchain.Block, Blockchain.Pyspec, Difficulty, HexPrefix, KeyAddress, KeyStore, Legacy.*, PoW, Rlp, Ssz, Transaction, Trie), `Ethereum.Test.Base`, and the runner harnesses `Nethermind.Blockchain.Test.Runner`, `Nethermind.State.Test.Runner.Test`, `Nethermind.Test.Runner`, layered on top of the subset of core `Nethermind.*` libraries the EF vectors need (grouped under one `/Nethermind/` folder — see `EthereumTests.slnx:2-54`). This is the spec-compliance solution — it does not include `Nethermind.Runner` or plugins at all. |
| `Benchmarks.slnx` | 59 `<Project>` entries | BenchmarkDotNet-based performance benchmarks: `Nethermind.Benchmark`, `Nethermind.Benchmark.Runner`, `Nethermind.EthereumTests.Benchmark`, `Nethermind.Evm.Benchmark`, `Nethermind.JsonRpc.Benchmark`, `Nethermind.Merge.Plugin.Benchmark`, `Nethermind.Network.Benchmark`, `Nethermind.Precompiles.Benchmark`(+`.Test`), again layered on a shared core-library subset (`Benchmarks.slnx:2-53`). Per `.agents/rules/test-infrastructure.md:28-40`, correct benchmark setup wires **production DI modules** (`NethermindModule` + a `TestEnvironmentModule` override for `DiagnosticMode.MemDb`) rather than hand-constructing `WorldState`/`TrieStore`/`BlockProcessor` — the benchmark harness is DI-driven, not a bespoke fixture. |
| `Stateless.slnx` | 25 `<Project>` entries | The "stateless client" experiment: `Nethermind.Stateless.Executor` and `Nethermind.Stateless.ZiskGuest`, layered on a minimal core subset (Abi, Blockchain, Config, Consensus, Core, Crypto, Db, Evm(+Precompiles), History, Logging, Network.Contract/.Stats, Serialization.*, Specs, State, Synchronization, Trie, TxPool — `Stateless.slnx:2-26`). Notably excludes `Nethermind.Api`, `Nethermind.JsonRpc`, `Nethermind.Runner`, and all `*.Test` projects except `Ethereum.Test.Base` — this solution is deliberately minimal, appropriate for a guest/zk-execution target that shouldn't drag in RPC or plugin surface. |

The practical effect of four separate `.slnx` files sharing overlapping subsets of the same
`.csproj` files: `dotnet test --solution EthereumTests.slnx` (or the equivalent
`--project`/`--filter` invocation cited in `AGENTS.md:116-118`) restores and builds **only**
the ~72-project EF-conformance graph — it will not compile `Nethermind.Runner`, the plugins,
or the benchmark harnesses, and vice versa. This lets CI parallelize "does the client build",
"do EF vectors pass", and "do benchmarks compile" as independent, differently-scoped jobs
without one slow monolithic solution build gating all three.

### `tools/*.slnx` — one-solution-per-tool sprawl

Beyond `src/Nethermind/`, fourteen more `.slnx` files exist directly under `tools/`, one per
CLI utility: `tools/DocGen/DocGen.slnx`, `tools/EngineApiProxy/EngineApiProxy.slnx`,
`tools/Evm/Evm.slnx`, `tools/HiveCompare/HiveCompare.slnx`,
`tools/HiveConsensusWorkflowGenerator/HiveConsensusWorkflowGenerator.slnx`,
`tools/JitAsm/JitAsm.slnx`, `tools/Kute/Kute.slnx`, `tools/PgoTrim/PgoTrim.slnx`,
`tools/RpcTests/RpcTests.slnx`, `tools/SchemaGenerator/SchemaGenerator.slnx`,
`tools/SendBlobs/SendBlobs.slnx`, `tools/StatelessInputGen/StatelessInputGen.slnx`,
`tools/StatsAnalyzer/StatsAnalyzer.slnx`, `tools/TxParser/TxParser.slnx`. Each is a
single-purpose utility (doc generation, Engine API proxying/fuzzing, an EVM CLI, Hive-result
comparison, blob-transaction senders, RLP/tx parsers, etc.) with its own isolated solution so
that building or running one tool never requires restoring the full client graph. `AGENTS.md`
lists `tools/` only as "various servicing tools for testing, monitoring, etc." (`AGENTS.md:8`)
without enumerating them — accurate as far as it goes, but the granularity (14 independent
solutions) is a build-organization detail worth knowing before assuming "the tools" build as
one unit.

### fukuii's sbt-module analog

fukuii has no `.slnx`/`.sln` equivalent — a single `build.sbt` defines the module graph via
`lazy val` project declarations and `config(...)` blocks (`build.sbt:188-343`), with modules
`bytes`, `crypto`, `rlp`, `Evm`, `Benchmark`, `RpcTest`, `IntegrationTest` layered on a root
`node` project. The closest correspondence:

| Nethermind concern | Nethermind solution | fukuii equivalent |
|---|---|---|
| "Build/ship the client" | `Nethermind.slnx` | root `main` config (`sbt compile`, `sbt compile-all`) |
| "Run the EF/consensus test vectors" | `EthereumTests.slnx` | `testEthereum`/`testOlympia` command aliases (tagged `testOnly` subsets — not a separate solution, see `build.sbt` alias block) |
| "Performance benchmarking" | `Benchmarks.slnx` | `Benchmark` config (`sbt Benchmark/test`) |
| "Minimal stateless/guest target" | `Stateless.slnx` | none — no fukuii equivalent exists |

The key structural difference: Nethermind's four solutions are **genuinely separate
project/restore graphs** — a CI job can build exactly `EthereumTests.slnx` and touch nothing
else. fukuii's sbt configs (`Benchmark`, `RpcTest`, `IntegrationTest`) live inside one
multi-project build and share a single `sbt compile-all` invocation surface; scoping to "only
compile the EF-vector-equivalent code" isn't a first-class build-graph operation in fukuii —
it's done via test-tag filtering (`-l`/`-n` ScalaTest tags in `build.sbt`'s alias definitions)
after the whole build already compiled. This is a coarser granularity than Nethermind's
solution-level split, though sbt's incremental compiler means the practical cost difference is
smaller than the structural difference suggests.

---

## Reproducible benchmark workflow (run-expb-reproducible-benchmarks.yml)

`.github/workflows/run-expb-reproducible-benchmarks.yml` (2,336 lines) is a ChatOps-style,
manually-triggerable-or-automatic benchmark harness built around an external Python tool,
`expb` (`execution-payloads-benchmarks`), that replays real mainnet block payloads against a
running Nethermind Docker container and measures per-block processing time. It has two
structurally different execution paths — **single-image** (one branch/commit vs. a cached
`master` baseline) and **multi-image** (a sweep across many Docker tags, either explicit or
auto-discovered) — plus a Windows-only post-processing job for profiler output.

### Triggers and the `resolve` job

Three trigger surfaces feed one `resolve` job (`run-expb-reproducible-benchmarks.yml:112-353`)
that normalizes everything into a common output set consumed by every downstream job:

- **`workflow_dispatch`** (lines 4-99): the full manual ChatOps surface. Inputs include
  `state_layout` (`halfpath`|`flat`), `payload_set` (`realblocks`|`superblocks`),
  `delay_seconds`, `amount` (override payload count; defaults 1000 for realblocks / 100 for
  superblocks), `additional_extra_flags` (free-form Nethermind CLI flags, comma- or
  newline-separated), `flat_write_buffer_floor` (defaults to `67108864` bytes — a
  `--FlatDb.PersistenceWriteBufferFloor` value auto-injected for flat-layout runs so small
  CompactSize persistence batches don't shrink RocksDB memtables), `expb_env` (env vars passed
  through to the `expb` process itself, e.g. `EXPB_EVM_WARMUP=1`), `rebuild_docker`,
  `run_count` (repeat the same scenario N times for reproducibility/variance testing),
  `docker_images` / `enable_retrospective` / `retrospective_last` / `retrospective_step`
  (multi-image mode controls), and `dottrace` (enable JetBrains dotTrace profiling).
- **`pull_request` (`types: [labeled]`)** (lines 100-101): fires whenever *any* label is
  added, but the `resolve` job's `if:` condition (line 114) and its internal branch (lines
  210-216) gate execution to the specific label `performance is good` **and** requires
  `github.event.pull_request.head.repo.full_name == github.repository` (i.e. same-repo PRs
  only — forks are rejected even with the label present, presumably to keep the self-hosted
  `reproducible-benchmarks` runner from executing untrusted fork code).
- **`push` to `master`**: runs unconditionally with fixed defaults (`flat` layout, both
  `superblocks` and `realblocks` payload sets, single run, no dottrace) — this is what
  populates the master-baseline cache other runs compare against.

`resolve` computes, among other things, the benchmark **config file** to use — a 2×2 matrix of
`state_layout` × `payload_set` mapping to one of four YAML files in `/mnt/sda/expb-data`
(`github-action-{compressed-,}mainnet{-flat,}.yaml`, lines 310-318 and mirrored at
509-517/1644-1653 in later jobs) — and a **scenario name**
(`nethermind-<layout>-<payload_set>-<clean_branch>-delay<N>s`) used both as the YAML scenario
key and as the cache/artifact naming key throughout the rest of the workflow.

### `prepare-docker`: build-or-reuse, with cross-workflow coordination

For single-image runs, `prepare-docker` (lines 355-478) decides whether to build a fresh
Docker image or reuse an existing one tagged `nethermindeth/nethermind:<clean_branch>`. On
`master`/`paprika`/`performance`/`release/*` branches it deliberately does **not** trigger a
rebuild (`should_trigger_publish_docker=false`), because `publish-docker.yml` already runs in
parallel for pushes to those branches — instead, on `push` events specifically, it polls the
GitHub Actions REST API (lines 400-478) for up to 5 minutes to *discover* that parallel run and
then up to 2 hours for it to *complete*, so the benchmark doesn't race against a stale image.
For other branches (PR/dispatch), it triggers `publish-docker.yml` via
`benc-uk/workflow-dispatch@v1` and waits on it via `scripts/wait-for-workflow.sh`.

### `benchmark` job: config rendering, `expb` execution, and log analysis

This is the core single-image job (lines 480-1041), matrixed over `run` × `payload_set` with
`max-parallel: 1` (benchmarks never run concurrently on the same self-hosted
`reproducible-benchmarks` runner) and a 720-minute timeout.

**Render benchmark config** (lines 561-649) never edits the source YAML in `expb-data` — it
copies to a temp file and does placeholder substitution with `sed`: `<<DOCKER_TAG>>` →
resolved image tag, `<<DELAY>>` → `delay_seconds`, `<<AMOUNT>>` → payload count, and renames
the YAML scenario key from the literal `nethermind:` to the computed scenario name. Free-form
`additional_extra_flags` are normalized by an `awk` pass that converts space-separated
`--Key Value` into `--Key=Value` (to avoid bare-token ambiguity) before being appended into the
scenario's `extra_flags` array with `yq` (chosen specifically because it preserves YAML
comments, unlike a naive `sed`-based array rewrite).

**Install or upgrade expb** (lines 651-674) always reinstalls: `uv tool install --force --from
git+https://github.com/<repo>[@<branch>] expb` — there is no version pinning; every run gets
whatever is at the tip of the configured `expb_repo`/`expb_branch` (default
`NethermindEth/execution-payloads-benchmarks@main`).

**Run expb scenarios** (lines 676-760) is the actual execution: it exports any
`expb_env` KEY=VALUE pairs, sets a `trap on_terminate TERM INT` handler that on cancellation
sends `SIGTERM` to the `expb` process and waits up to `cleanup_grace_seconds` (default 90s)
before escalating to `SIGKILL` — protecting against orphaned Docker containers when a GitHub
Actions run is cancelled mid-benchmark. Output is captured through a named pipe (`mkfifo`) fed
into `tee` so the raw log is written to disk while still streaming to the job console, then
`expb execute-scenarios --config-file <rendered> --per-payload-metrics
--per-payload-metrics-logs --print-logs [--dottrace]` runs against it.

**Analyze benchmark output** (lines 762-937) is where log parsing happens:
- Strips ANSI escape codes first (`sed -E 's/\x1B\[[0-9;?]*[ -/]*[@-~]//g'`).
- Hard-flags: any case-insensitive `Exception` line, any `invalid[_ -]*block` pattern.
- Warn-only (not fatal): `Unhandled`, `Fatal`, `ERROR` patterns (lines 801-808).
- **Metrics source preference** (lines 810-830): greps for
  `[payload-server] client_metric block_number=N processing_ms=X` lines — Nethermind's own
  internal SSE (server-sent events) instrumentation of block-processing time — as the
  **primary** signal. Only if zero such lines exist does it fall back to parsing the K6
  load-test tool's per-payload pipe-table (`| payload | gas_used | processing_ms |` rows) for
  **TTFB** (time-to-first-byte), which measures HTTP round-trip time and is a noisier,
  secondary proxy for the same thing. When SSE data *is* available, the workflow additionally
  computes TTFB as a secondary cross-check (lines 885-900) so both numbers are visible.
- Percentiles (`compute_percentiles`, lines 839-881) are computed with a hand-rolled
  `sort -n` + `awk`/`sed` pipeline: mean via `awk` sum/count, median via exact-middle or
  averaged-adjacent-pair depending on odd/even `n`, and P90/P95/P99 via a ceiling-rounded index
  (`(90*n+99)/100`) — no external stats library, pure POSIX shell arithmetic.

**Enforce run quality gates** (lines 962-987) is unconditional (`if: always()`) and hard-fails
the job if `expb`'s own exit code wasn't success, or if any exception/invalid-block line was
found — there is no soft-fail or warn-only mode for these two conditions in single-image runs.

**Master metrics caching vs. PR comparison** (lines 540-548, 947-960, `report` job
1046-1270): on a successful `push` to `master` with no exceptions/invalid blocks, the job
writes its metrics to `actions/cache/save@v4` under key
`expb-master-metrics-v3-<payload_set>-<github.run_id>` — a *rolling* cache keyed by run ID
rather than commit SHA, meaning "the most recent master run" is always the one a subsequent PR
compares against via the `restore-keys` prefix-match fallback (`expb-master-metrics-v3-<set>-`)
when the exact base-SHA key isn't found (lines 546-548, 1063-1065, 1073-1075). PR runs, in a
separate `report` job, download every `expb-single-metrics-*` artifact across the matrix,
restore both `superblocks` and `realblocks` master caches independently, and build one
Markdown comment with a per-metric percentage-delta table (`percentage_delta`, lines
1116-1124) for each payload set — SSE numbers as the primary table, K6 TTFB folded into a
`<details>` disclosure when SSE was available. The comment is upserted (not re-posted) by
searching existing PR comments for an HTML marker (`<!-- expb-reproducible-benchmark-report
-->`, line 1092) via `actions/github-script@v8` and updating in place if found.

**`single-summary` job** (lines 1276-1466): downloads all `expb-single-metrics-*` artifacts and
renders one aggregate table into `GITHUB_STEP_SUMMARY`. When `run_count > 1` it builds a
per-run table plus a **Mean/Best/Worst** aggregate row across runs (computed with more
`awk` one-liners, lines 1396-1417) — this is the reproducibility-testing path: run the same
scenario N times and see how much the numbers actually vary.

### Multi-image path (retrospective sweeps and explicit image comparisons)

`resolve-images` (lines 1471-1616) builds the job matrix for "multi" mode, which triggers when
either `docker_images` (explicit comma-separated image refs) or `enable_retrospective` is set.
Retrospective mode paginates the Docker Hub v2 API
(`hub.docker.com/v2/repositories/nethermindeth/nethermind/tags`) filtering tags matching
`^master-[0-9a-f]{7}$`, takes the most recent `retrospective_last` (default 100), then
downsamples by taking every `retrospective_step`-th tag (default every 10th) — a way to scan,
e.g., "the last 100 master builds, sampled every 10th commit" without benchmarking every single
one. The resulting tag list is expanded by `run_count` into a GitHub Actions matrix (capped at
256 entries, the platform limit, with an explicit truncation warning if exceeded). The
`benchmark-multi` job (lines 1618-2096) is a near-duplicate of the single-image `benchmark`
job's render/run/analyze steps, keyed by `<tag>-run<N>` instead of `<payload_set>-run<N>`, with
one deliberate behavioral difference: **`fail-fast: false`** and the quality-gate step only
*logs* failures rather than exiting non-zero (lines 2033-2047) — a multi-image sweep is
explicitly designed to tolerate individual image failures and still report on the rest, unlike
the single-image path's hard gate. The `summary` job (lines 2098-2251) renders a comparison
table with a **delta-vs-first-image** column (the first successfully-measured image in the
ordered list becomes the implicit baseline) and inline warning glyphs for
exceptions/invalid-blocks per row.

### dotTrace: snapshot capture and Windows-side XML report generation

When the `dottrace` input is `true`, both the single- and multi-image `benchmark` jobs pass
`--dottrace` to `expb execute-scenarios`, then locate any `dottrace`-named output directories
created *after* a marker file touched immediately before the run (`find ... -newer
"${MARKER}"`, lines 998, 2058), zip them (`zip -9r`), and upload as a per-run artifact
(`dottrace-<payload_set|tag>-run<N>`).

A separate, **Windows-only** job, `generate-dottrace-reports` (lines 2257-2335), runs only if
`dottrace == 'true'` and at least one of `benchmark`/`benchmark-multi` succeeded. It downloads
every `dottrace-*` artifact, downloads JetBrains' `dotTrace.CommandLineTools.windows-x64`
package directly from NuGet (pinned version `2026.1.0.1`, not restored via a project file — a
raw `Invoke-WebRequest`), extracts each `.zip` snapshot, locates the `.dtp` file (explicitly
excluding numbered chunk files like `.dtp.0000` by matching on exact `.dtp` extension), and
runs `Reporter.exe report <snapshot.dtp> --pattern=pattern.xml --save-to=<name>-report.xml` per
snapshot, where `pattern.xml` is a minimal inline pattern (`<Pattern
PrintCallstacks="Full">.*</Pattern>`) matching every function and requesting full call stacks.
All resulting XML reports are uploaded together as one `dottrace-reports` artifact.

Per `AGENTS.md:152`, each report XML contains `<Function>` nodes with `FQN`, `TotalTime`,
`OwnTime`, `Calls`, and call-stack attributes — `OwnTime` is the sort key for hot-spot analysis,
`CallStack` attributes support call-tree reconstruction. These reports run 50-70MB and
`AGENTS.md:207` is explicit that agents must **never load the full XML into context** — instead
use `scripts/dottrace-report.sh top <report.xml> [N]` (top-N functions by `OwnTime`, default
30) or `scripts/dottrace-report.sh compare <a.xml> <b.xml> [N]` (regressions/improvements sorted
by absolute delta). The script is pure `grep`+`sed`+`awk` (no XML parser), extracting
`FQN`/`TotalTime`/`OwnTime` via a single `sed` capture-group pattern per `<Function ` line and
keeping only the max-`OwnTime` occurrence per FQN (a function can appear multiple times in a
call tree; the script de-duplicates to its hottest occurrence) — this is what makes it run in
under two seconds against a 70MB file per the script's own header comment
(`scripts/dottrace-report.sh:1-8`).

### `AGENTS.md`'s benchmark-workflow guidance section, verbatim conventions

`AGENTS.md:129-207` ("Reproducible Benchmark Workflow Guidance") documents operational
conventions for agents inspecting workflow runs, not just the workflow's own behavior:

- **Log structure reference** (`AGENTS.md:163-184`): a specific reference run is pinned for
  structure validation — `https://github.com/NethermindEth/nethermind/actions/runs/22185801008`,
  job `64159725161` — fetched with `gh run view 22185801008 --job 64159725161 --log`. GitHub
  job logs are documented as tab-separated in the shape
  `<job-name>\t<step-name>\t<timestamp>\t<message>`, with named example steps (`Print resolved
  inputs`, `Render benchmark config`, `Install or upgrade expb`, `Run expb scenarios`). The
  `Run expb scenarios` step specifically mixes four log streams: EXPB's own structured
  `timestamp=... level=info event="..."` lines, K6 progress/percentile blocks, raw Nethermind
  runtime logs, and the per-payload metrics table — and the guidance explicitly warns that ANSI
  codes must be stripped before searching/parsing, and that "non-ASCII time-unit glyphs" can
  render mangled in plain terminal output (prefer numeric fields when aggregating).
- **Mandatory log checks** (`AGENTS.md:186-199`): review must fail if any of `Exception`,
  `Invalid Block`, `Invalid Blocks` appear — stated as a hard requirement ("any detected
  `Exception` in run output must fail the workflow after reporting matching lines"), matching
  the actual `Enforce run quality gates` step's behavior. `Unhandled`, `Fatal`, `ERROR` are
  documented as severe-but-secondary signals, and normal shutdown is confirmed via
  `Nethermind is shut down` and `event="Cleanup completed"` markers.
- **`EXPB_EVM_WARMUP` performance-tuning flag** (`AGENTS.md:206`): passing
  `-f expb_env="EXPB_EVM_WARMUP=1"` enables expb's per-block EVM warmup — an `eth_simulateV1`
  call executed before each measured block so the measured block's reads come from warm
  caches. Documented effect: lowers run-to-run coefficient of variation from roughly 1.8% to
  0.55% on flat-layout realblocks, and lowers the AVG. The **caveat** is explicit and important:
  this must be paired with a raised RPC gas cap
  (`-f additional_extra_flags="--JsonRpc.GasCap=1000000000000"`), because the default 100M
  per-request gas budget is exhausted by the warmup `eth_simulateV1` call on dense blocks,
  which then fails with error `-38013` (intrinsic gas) and **silently** leaves those blocks
  un-warmed — a failure mode that wouldn't surface as an `Exception`/`Invalid Block` line and
  would otherwise pass the quality gates while quietly corrupting the low-variance intent.
  `AGENTS.md` also flags that this mode measures a low-variance *compute* signal specifically
  because it minimizes cold RocksDB/storage interaction — so it should not be used when the
  change under test is storage-layer-related.

---

## tools/DocGen — generated-from-source documentation

`tools/DocGen/` is a small (5-file) console tool that regenerates specific sections of an
**external** Docusaurus documentation site (`NethermindEth/docs`, a separate GitHub repo) from
reflection over the built `Nethermind.*.dll` assemblies. It is invoked by
`.github/workflows/update-docs.yml` (triggered on `release: published` or manual
`workflow_dispatch`), which checks out both the Nethermind repo (as `n/`) and the docs repo (as
`d/`), builds `DocGen.csproj`, runs it as `n/DocGen/DocGen $GITHUB_WORKSPACE/d --config
--jsonrpc --metrics` (`update-docs.yml:42-46`), then opens a PR against the docs repo with the
changes (`update-docs.yml:53-67`, labeled `docgen`). **This tool does not write into
`src/Nethermind`'s own repository at all** — there is no `docs/` directory at the Nethermind
repo root (confirmed by direct listing), and all four generators write into a `docs-dir`
argument that in practice is always the checked-out sibling `NethermindEth/docs` repo.

`Program.cs` (`tools/DocGen/Program.cs`) is a `System.CommandLine`-based CLI dispatcher with
four independent, composable boolean flags — `--config`, `--jsonrpc`, `--metrics`, and
`--dbsize` (which additionally requires `--dbsize-src <path>`, enforced by a `Validators.Add`
callback at `Program.cs:23-27`) — plus a required positional `docs-dir` argument. Each flag
maps to exactly one generator's `Generate(...)` call; none of the four generators depend on
each other or must run together.

| Generator | Source of truth | Output target | Injection mechanism |
|---|---|---|---|
| `ConfigGenerator.cs` | Reflection over every exported type in `Nethermind.*.dll` that implements `IConfig` (`ConfigGenerator.cs:20-25`), reading `[ConfigCategory]` (category-level `HiddenFromDocs`) and `[ConfigItem]` (per-property `Description`, `DefaultValue`, `CliOptionAlias`, `HiddenFromDocs`) attributes | `docs/fundamentals/configuration.md` | Rewrites content strictly between `<!--[start autogen]-->`/`<!--[end autogen]-->` HTML comment markers, preserving everything outside them (`ConfigGenerator.cs:17-18, 39-65`) |
| `JsonRpcGenerator.cs` | Reflection over a fixed list of five named assemblies (`Nethermind.Consensus.Clique`, `Nethermind.Era1`, `Nethermind.Flashbots`, `Nethermind.HealthChecks`, `Nethermind.JsonRpc`) for `IRpcModule`-derived interfaces carrying `[RpcModule]`, then per-method `[JsonRpcMethod(IsImplemented=true)]` (unimplemented methods are skipped) | One Markdown file per RPC namespace under `docs/interacting/json-rpc-ns/` (e.g. `eth.md`, `net.md`) | Full-file regeneration (deletes and rewrites every `*.md` in the target dir except hand-authored `eth_subscribe.md`/`eth_unsubscribe.md`, which are spliced back in verbatim via `WriteFromFile`, `JsonRpcGenerator.cs:41-49, 114-120`) |
| `MetricsGenerator.cs` | Reflection over every exported type literally named `Metrics` across all `Nethermind.*.dll`, reading each property's `[Description]` attribute | `docs/monitoring/metrics.md` | Same start/end-marker splice pattern as `ConfigGenerator` |
| `DBSizeGenerator.cs` | **Not reflection-based** — reads per-chain JSON files (`mainnet.json`, `sepolia.json`, etc.) from an external `--dbsize-src` directory containing pre-measured database sizes for a fixed `_dbList` (`state`, `receipts`, `blocks`, `headers`, `code`, `blobTransactions`) | `docs/fundamentals/database.md`, written twice — once for the current docs tree and once into `versioned_docs/version-<latest>/fundamentals/` (`DBSizeGenerator.cs:49-50`) | Same start/end-marker splice pattern |

The RPC generator's type-expansion logic (`JsonRpcGenerator.cs:242-336`) is the most intricate
piece: it recursively walks a method's parameter and return types, detecting
enumerable/dictionary shapes, mapping known C# types to a fixed JSON-type vocabulary
(`Address` → `_string_ (address)`, `UInt256`/`BigInteger`/`Int64` → `_string_ (hex integer)`,
`Hash256` → `_string_ (hash)`, etc., `JsonRpcGenerator.cs:317-335`), and guarding against
infinite recursion on circular object graphs by tracking a `parentTypes` chain and emitting an
explicit `<!--[circular ref]-->` marker when a type reappears in its own ancestry
(`JsonRpcGenerator.cs:246-251`).

**Drift-risk analysis for fukuii.** fukuii currently has no analog to any of these four
generators — its JSON-RPC namespace docs, HOCON configuration reference, and metrics reference
are all hand-maintained prose (fukuii's `jsonrpc/` module has 79 files per `conduit`'s agent
description, and there is no `[ConfigItem]`-equivalent attribute system on fukuii's config case
classes). This is a genuine, concrete drift risk that Nethermind's pattern solves structurally:
every time a Nethermind config property, RPC method, or metric is added, the doc generator
picks it up automatically on the next release-triggered `update-docs.yml` run, so the doc and
the code cannot silently diverge — the doc *is* the code's attribute annotations, re-rendered.
Porting this to fukuii would require (a) a Scala 3 annotation or trait-derivation equivalent of
`[ConfigItem(Description=..., DefaultValue=...)]` on config case classes, (b) an equivalent
"is this RPC method actually implemented" marker on fukuii's JSON-RPC module traits, and (c) a
small standalone tool (plausibly a `sbt-run.sh`-style script invoking a dedicated module via
reflection or Scala 3 macros) plus a CI job wired to fukuii's own `docs/` tree (fukuii, unlike
Nethermind, keeps its docs in-repo under `docs/architecture/`, so there'd be no need for the
cross-repo PR dance `update-docs.yml` does). See the verdict table for a "Needs design" call —
this is valuable but nontrivial, not a drop-in port.

---

## Project/architecture structure

`AGENTS.md:56-108` ("Architecture" under "Project structure") groups the `src/Nethermind/`
libraries into ten labeled categories (Entry point/initialization, General API, Consensus
algorithms, Core blockchain, State and storage, Networking, Transaction management, RPC and
external interface, Monitoring, Serialization, Third-party integration, Tests). Cross-checking
this against the full `src/Nethermind/` directory listing shows the category breakdown is
accurate for everything it lists, but the listing is **not exhaustive** — the following
existing project directories are not mentioned in any category (verified by direct `ls
src/Nethermind/`, not present in `AGENTS.md:56-108`'s prose):

- `Nethermind.BalRecorder` (+`.Test`) — balance-change recording, no category
- `Nethermind.EraE` (+`.Test`) — alongside `Nethermind.Era1`, which *is* mentioned nowhere either despite both existing
- `Nethermind.History` (+`.Test`) — this one *is* actually listed, under no explicit heading of its own (folded loosely near storage in the file but worth double-checking against future edits)
- `Nethermind.State.Flat` (+`.Test`) — a second state-storage implementation alongside `Nethermind.State`, not called out as distinct from it
- `Nethermind.Stateless.Executor` / `Nethermind.Stateless.ZiskGuest` — the entire `Stateless.slnx` concern (see above) has no architecture-doc presence at all
- `Nethermind.Xdc` (+`.Test`) — a network/chain integration (XDC Network) with no mention alongside `Optimism`/`Taiko`/`Flashbots` in the "Third-party integration" bullet
- `Nethermind.Kademlia` — a standalone Kademlia DHT implementation, not mentioned alongside `Nethermind.Network.Discovery` in "Networking"
- `Nethermind.BalRecorder`, `Nethermind.StateDiff.Core`/`StateDiffsWriter` — state-diff tooling, absent from the category list (though `StateDiffsWriter` does appear as a `/Plugins/StateDiffsWriter/` folder in `Nethermind.slnx:28-32`)
- `Nethermind.Analyzers` / `Nethermind.Test.Analyzers` — Roslyn analyzers, unmentioned

None of this contradicts what `AGENTS.md` *does* say — it's additive gaps, not errors, and
several (Xdc, EraE, State.Flat, Stateless) look like genuinely newer additions that the doc
hasn't caught up with yet. This is the same category of finding as the missing `Stateless.slnx`
mention above: Nethermind's own agent-facing documentation has a mild, ongoing staleness
problem in exactly the place fukuii should be careful not to replicate — a hand-maintained
"here is the module map" prose section that silently falls behind as new top-level modules are
added, with no CI check enforcing that every `src/Nethermind/*/` directory maps to a category
bullet.

**Where the load-bearing project rules actually live.** `AGENTS.md:34-44` documents a
`.agents/rules/` directory of nine topic files (`coding-style.md`, `di-patterns.md`,
`test-infrastructure.md`, `robustness.md`, `performance.md`, `package-management.md`,
`github-workflows.md`, `git.md`, `agent-skills.md`), each with an explicit trigger condition
("load when...") and an instruction that these must be read from disk on every relevant task
rather than assumed from memory. This is a materially different pattern from fukuii's own
`CLAUDE.md`/`AGENTS.md` split (which centralizes protocol references in a table rather than
conditionally-loaded rule files) — Nethermind's `.agents/rules/` is the closer analog to
fukuii's `.claude/agent-protocols/` directory (also topic-file-per-concern, also canonically
edited elsewhere and symlinked — fukuii's canonical location is `.agents/protocols/`, mirroring
Nethermind's `.agents/rules/` naming convention closely enough that it was likely a direct
inspiration; fukuii's own `git-conventions.md` protocol is explicitly noted as "ported from
Nethermind's `git.md`").

**MCP tooling for a running node.** Nethermind does not appear to ship a `.github/copilot/`
MCP-server config exposing its own running JSON-RPC as MCP tools within this vendored clone
(no such directory found under `.github/`) — this is a pattern fukuii's own `AGENTS.md` already
documents as present in fukuii itself (`.github/copilot/README.md`, `.github/copilot/mcp.json`)
rather than something borrowed from Nethermind; worth noting only to avoid mis-attributing it.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable | Reasoning |
|---|---|---|
| Split the build into scoped, independently-buildable solutions (Nethermind's 4 `.slnx` files) | **Not portable as-is** | sbt's single multi-project `build.sbt` with `config()`-based sub-configurations (`Benchmark`, `RpcTest`, `IntegrationTest`) is architecturally different from MSBuild's per-solution project graphs — there's no sbt equivalent of "restore/build only this subset of modules as a wholly separate solution file." The *goal* (scope CI jobs to build only what they test) is already achievved differently in fukuii via `sbt compile` (core-only) vs `compile-all` and via tagged `testOnly`/alias commands; not worth restructuring `build.sbt` to imitate `.slnx` splitting. |
| Reproducible-benchmark CI workflow (`run-expb-reproducible-benchmarks.yml`) as a whole | **Needs design** | fukuii has zero CI benchmark workflow today (27 `.github/workflows/*.yml` files, none benchmark-shaped) and only a manual-profiler runbook (`docs/runbooks/snap-sync-performance-tuning.md`, covering async-profiler/VisualVM/JConsole/Eclipse MAT/JProfiler by hand). The specific mechanism — external replay tool + Docker image + self-hosted runner + master-baseline cache + PR comparison comment — is a substantial, valuable pattern but requires: (a) a fukuii-equivalent block-replay benchmark tool (no equivalent to `expb` exists), (b) a self-hosted runner, (c) a decision on what "processing time" signal fukuii can emit analogous to Nethermind's SSE `client_metric` lines. This is a multi-week infrastructure project, not a drop-in file copy. |
| SSE-client-metric-over-K6-TTFB preference for measuring processing time | **Needs design** | The *principle* — prefer an internal, low-noise instrumentation signal over an external HTTP-timing proxy — is directly applicable to any future fukuii benchmark harness, independent of adopting `expb` itself. Fukuii would need its own internal per-block timing emission (e.g. a debug/metrics hook) before this preference is actionable. |
| `EXPB_EVM_WARMUP` + RPC-gas-cap-caveat pattern (warm caches before measuring, but watch for silent under-measurement) | **Needs design** | The specific flag is `expb`/Nethermind-only, but the underlying caution — a performance-tuning knob that can silently degrade to a no-op under a resource cap without producing a hard error — is a generally reusable code-review heuristic worth carrying into any fukuii benchmark-tuning documentation once a benchmark harness exists. |
| dotTrace snapshot → Windows Reporter.exe → XML → `grep`+`awk` summarizer (`dottrace-report.sh`) | **Not portable** | dotTrace is a .NET-specific profiler; fukuii is JVM/Scala. The *pattern* (never load a 50-70MB profiler report into agent context; ship a purpose-built grep/awk summarizer script instead) is worth keeping in mind, but fukuii's existing `snap-sync-performance-tuning.md` runbook already documents JVM-native equivalents (async-profiler flamegraphs, JFR) — a similar "don't load the raw profiler output into context, use a small extraction script" helper script would be a reasonable, low-effort addition if agent-driven profiling analysis becomes routine, but nothing here to literally port. |
| `tools/DocGen` — reflection/attribute-driven config, RPC, and metrics doc generation | **Needs design** | Directly addresses a real fukuii gap: config, RPC (`jsonrpc/`, 79 files), and metrics docs are entirely hand-maintained today with no drift protection. Porting requires a Scala 3 annotation/derivation mechanism for config case classes and RPC method markers plus a small generator tool and (unlike Nethermind, which round-trips through a separate `NethermindEth/docs` repo) a CI job targeting fukuii's own in-repo `docs/` tree directly — architecturally simpler than Nethermind's cross-repo version, but still a genuine build. Worth scoping as a discrete future spec (`specs/<NNN>-doc-generation/`) rather than an inline addition. |
| DBSizeGenerator's external-JSON-file (non-reflection) pattern for per-chain DB size docs | **Not portable now** | Requires an established, automated process that measures and publishes per-chain DB sizes as JSON artifacts in the first place — fukuii has no such measurement pipeline yet. Revisit once/if fukuii starts tracking DB size trends per network. |
| `AGENTS.md`'s own project-structure section silently drifting behind new modules (missing `Stateless.slnx`, `Nethermind.Xdc`, `Nethermind.EraE`, `Nethermind.State.Flat`, etc.) | **Port now (as a process lesson, not code)** | fukuii's `CLAUDE.md`/`AGENTS.md` should treat this as a cautionary example: hand-maintained "here is the module map" prose reliably drifts. fukuii's current architecture-doc tree (`docs/architecture/`, 16 files) and module list in `AGENTS.md` should periodically be diffed against the real `build.sbt` module list and `docs/architecture/` directory listing — a cheap, no-infrastructure habit (a `git diff`-style spot-check at sprint boundaries) rather than a build change. |
| `.agents/rules/` conditionally-loaded, trigger-annotated rule files | **Already ported** | fukuii's `.agents/protocols/` (symlinked to `.claude/agent-protocols/`) already follows this exact pattern and is explicitly cross-referenced as inspired by Nethermind's `git.md` for one specific protocol (`git-conventions.md`). No further action — noted here only to confirm the lineage and that fukuii's version is, if anything, more complete (22 protocol files in `CLAUDE.md`'s table vs. Nethermind's 9 `.agents/rules/` files). |

---

*Compiled from a direct read of every file cited above in the vendored clone at
`.claude/repo-references/clients/nethermind/`. Line numbers refer to that clone's current
checkout; re-verify against `git log` if the vendored copy is refreshed.*
