# Reth — Developer-Workflow & Benchmark Patterns

Source: `.claude/repo-references/clients/reth/` (vendored full clone, verified genuine —
`origin` points at a fork `white-b0x/reth`, `upstream` at `paradigmxyz/reth`, `HEAD` at
`3d76b93c2` dated 2026-07-01)

Reth is a Rust execution client with no skills system (no `.agents/skills/` or
`.claude/skills/` directory of its own) — unlike Nethermind's four-skill catalog or
Erigon's Makefile-plus-docs split, Reth's entire developer-workflow story lives in three
places: the root `Makefile` (task-runner conventions), a strikingly heavy CI benchmark
pipeline (`.github/workflows/bench.yml` + `bench-benchmarkoor.yml` + a dozen
`.github/scripts/bench-*` helpers), and two short maintainer-process documents
(`docs/release.md`, `docs/repo/ci.md`). This is deliberately the deepest-dive of the
three areas, per the source material's own framing: it is Reth's richest area precisely
*because* there is no skills layer to distribute the workflow knowledge into.

Every claim below is traceable to a file in the vendored clone. `bench.yml` (1,850 lines)
and `bench-reth-summary.py` (2,155 lines) were read across two passes each covering the
full file rather than sampled — the analysis below describes the actual mechanism, not a
guess extrapolated from the first page. Where a claim rests on a whole-file read rather
than one pinned line, the citation names the function or section instead of inventing a
line number.

---

## Makefile — task-runner conventions

The entire `Makefile` (334 lines) is quoted structurally below; it is a flat file (no
`include`s), following a credited pattern (`Makefile:1`: "Heavily inspired by Lighthouse").

### Composite targets, quoted verbatim

The three targets the task's brief calls out by name:

```make
lint:
	make fmt && \
	make clippy && \
	make lint-typos && \
	make lint-toml

test:
	make cargo-test && \
	make test-doc

pr:
	make lint && \
	make update-book-cli && \
	cargo docs --document-private-items && \
	make test
```

(`Makefile:281-333`) — `pr` is Reth's single canonical pre-PR gate, and it is a strict
superset of `lint` and `test`: `lint` → `fmt` (nightly rustfmt) → `clippy` (workspace,
all features, `-D warnings`, i.e. warnings are build errors) → `lint-typos` (the `typos`
crate) → `lint-toml` (`dprint fmt` on every TOML file) → then, uniquely, `update-book-cli`
(regenerates the CLI reference docs from `--help` output, see below) → a bare
`cargo docs --document-private-items` doc-build-as-a-check step → then `test` (unit +
example + bench-compile tests via `cargo-test`, plus `cargo test --doc`). **`pr` bakes a
documentation build and a CLI-doc-drift check into the same gate as compile/lint/test** —
this is the single structural difference from fukuii's `sbt pp` worth flagging (see
verdict table): `pp` has no analogous "build the docs, fail if they don't build or don't
match generated output" step.

Individual targets referenced by the composites, each named exactly as in the file:

- **`fmt`** (`Makefile:240-241`) — `cargo +nightly fmt`. Nightly is required because
  Reth's formatting rules (`rustfmt.toml`, 328 bytes) use unstable rustfmt options not
  available on stable.
- **`clippy`** (`Makefile:243-251`) — `cargo +nightly clippy --workspace --lib --examples
  --tests --benches --all-features -- -D warnings`. Every compilation unit kind
  (`--lib --examples --tests --benches`) plus every Cargo feature is linted in one pass,
  and any warning is a hard failure.
- **`lint-typos`** / **`ensure-typos`** (`Makefile:253-260`) — `typos` (the `crate-ci/typos`
  spellchecker), gated by an install-check that prints an actionable error (`cargo install
  --locked typos-cli`) instead of failing opaquely if the binary is missing.
- **`lint-toml`** / **`ensure-dprint`** (`Makefile:262-279`) — same install-check pattern
  for `dprint`, formats every TOML file per `dprint.json` (206 bytes).
- **`cargo-test`** (`Makefile:314-320`) — `cargo test --workspace --lib --examples --tests
  --benches --all-features`. Note this is a **plain `cargo test`**, not `cargo nextest` —
  the faster nextest runner (see `test-unit` below) is reserved for the dedicated
  unit-test target, while `pr`'s `test` composite uses the slower-but-simpler stock
  runner, likely because nextest has different doctest-handling semantics
  (`--benches` here compiles benchmark harnesses as a smoke check, not a timing run — see
  the "not a benchmark, a compile check" distinction below).
- **`test-doc`** (`Makefile:322-323`) — `cargo test --doc --workspace --all-features`,
  kept as its own target/step because `cargo nextest` (used for `test-unit`) does not run
  doctests at all — a real gap in nextest's feature set that Reth works around by keeping
  a separate stock-`cargo test --doc` step everywhere doctests must run.
- **`clippy-fix`** / **`fix-lint`** (`Makefile:287-302`) — the auto-fix escapes: `clippy
  --fix --allow-staged --allow-dirty` then `fmt`, for local iteration, not part of `pr`.

### Test targets — a separate family from `pr`'s `test`

- **`test-unit`** (`Makefile:159-162`) — installs `cargo-nextest` then runs `cargo nextest
  run --no-fail-fast` with `UNIT_TEST_ARGS := --locked --workspace --features
  'jemalloc-prof' -E 'kind(lib)' -E 'kind(bin)' -E 'kind(proc-macro)'` (`Makefile:156`) —
  nextest's expression filter restricts this run to library/binary/proc-macro test
  *kinds*, deliberately excluding integration tests and doctests (doctests aren't
  supported by nextest at all, per above). This is the CI-facing "run everything fast"
  target, distinct from `pr`'s slower `cargo-test`.
- **`cov-unit`** / **`cov-report-html`** (`Makefile:165-173`) — `cargo llvm-cov nextest
  --lcov --output-path lcov.info $(UNIT_TEST_ARGS)`, then `cargo llvm-cov report --html`
  and `open` the report — the same `nextest`-filtered scope as `test-unit`, instrumented
  for line coverage via LLVM's source-based coverage rather than a separate coverage tool.
- **`ef-tests`** (`Makefile:193-195`, with the two fixture-download rules at
  `Makefile:178-191`) — downloads and unpacks two pinned Ethereum conformance corpora:
  `EF_TESTS_TAG := v17.0` from `ethereum/tests` (legacy state/blockchain tests) into
  `./testing/ef-tests/ethereum-tests`, and `EEST_TESTS_TAG := v4.5.0` from
  `ethereum/execution-spec-tests`'s `fixtures_stable.tar.gz` release asset into
  `./testing/ef-tests/execution-spec-tests`; both via plain `wget`+`tar --strip-components=1`
  (no submodule, unlike fukuii's `ets/tests` submodule pattern), and both pinned to an
  explicit tag rather than tracking a branch. The actual run is `cargo nextest run
  --no-fail-fast -p ef-tests --release --features ef-tests` — a single dedicated
  workspace crate (`ef-tests`) gates on both corpora at once, run in `--release` profile
  (state-test replay is CPU-heavy enough that a debug build would be prohibitively slow).
  This is a flat, unsharded run — no per-fork or per-shard split exists in the Makefile
  for `ef-tests` (contrast with Erigon's `tools/eest-spec-shards.yml`-driven `eest-spec-%`
  pattern, documented in the sibling `erigon/build-release-pattern.md`).

### `db-tools` — compiling MDBX debugging binaries

```make
db-tools: ## Compile MDBX debugging tools.
	@$(MAKE) -C $(MDBX_PATH) IOARENA=1 tools > /dev/null
	@mkdir -p $(DB_TOOLS_DIR)
	@cd $(MDBX_PATH) && \
		mv mdbx_chk $(FULL_DB_TOOLS_DIR) && \
		mv mdbx_copy $(FULL_DB_TOOLS_DIR) && \
		mv mdbx_dump $(FULL_DB_TOOLS_DIR) && \
		mv mdbx_drop $(FULL_DB_TOOLS_DIR) && \
		mv mdbx_load $(FULL_DB_TOOLS_DIR) && \
		mv mdbx_stat $(FULL_DB_TOOLS_DIR)
	@$(MAKE) -C $(MDBX_PATH) IOARENA=1 clean > /dev/null
	@echo "Run \"$(DB_TOOLS_DIR)/mdbx_stat\" for the info about MDBX db file."
	@echo "Run \"$(DB_TOOLS_DIR)/mdbx_chk\" for the MDBX db file integrity check."
```

(`Makefile:205-221`) — builds against the vendored libmdbx C sources at
`MDBX_PATH = "crates/storage/libmdbx-rs/mdbx-sys/libmdbx"` (`Makefile:8`) — the same path
`CLAUDE.md` explicitly forbids editing ("Never modify files in
`crates/storage/libmdbx-rs/mdbx-sys/libmdbx/` - this is vendored third-party code"). Six
binaries move into a top-level `db-tools/` directory: `mdbx_chk` (integrity check),
`mdbx_copy`, `mdbx_dump`, `mdbx_drop`, `mdbx_load`, `mdbx_stat`. `IOARENA=1` on both the
build and the immediately-following `clean` invocation silences a benchmarking info
message libmdbx's own build prints to stderr — noted inline in the Makefile as a comment
on both lines (`Makefile:208, 218`), not a build-correctness flag.

### `update-book-cli` — auto-generated CLI reference docs

```make
update-book-cli: build-debug ## Update book cli documentation.
	@./docs/cli/update.sh $(CARGO_TARGET_DIR)/debug/reth
```

(`Makefile:223-226`) — depends on `build-debug` (a plain `cargo build --bin reth --features
"$(FEATURES)"`, no `--release`), then runs `docs/cli/update.sh` against the built debug
binary. The vendored `CLAUDE.md` (Reth's own AI-agent guide, read in full for this report)
confirms the enforcement mechanism explicitly: "The CLI reference pages under
`docs/vocs/docs/pages/cli/` are **auto-generated** from the `reth` binary's `--help`
output. **Do not edit these files manually**... The `book` CI job
(`.github/workflows/lint.yml`) enforces this by regenerating the docs and running `git
diff --exit-code`. If the committed docs don't match the generated output, CI fails."
This is the single concrete "auto-generated CLI doc with a CI drift-check" mechanism the
verdict table below calls out as something fukuii has zero equivalent of — fukuii has no
generated CLI reference at all, so there is nothing that could drift.

### Cross-compile / release-build targets (brief — not the doc's focus)

`build`/`build-debug` (plain native builds), `build-%` (via `cross`, requiring Docker and
the `cross` binary — `Makefile:82-104`), `build-native-%` (native cross-target builds,
with a `JEMALLOC_SYS_WITH_LG_PAGE=16` override for `aarch64` targets since cross-compiled
jemalloc must not inherit the host's page size — `Makefile:92-98`), `build-%-reproducible`
(reproducible builds gated to `x86_64-unknown-linux-gnu` only, pinning `SOURCE_DATE_EPOCH`
to the last commit timestamp, stripping symbol-mangling/build-id variance —
`Makefile:63-73`), `build-deb-%` (Debian packaging via `cargo-deb@3.6.0`, restricted to
three architectures — `Makefile:116-130`), and `build-release-tarballs` (produces
`.tar.gz` release artifacts for `x86_64`/`aarch64` Linux only, explicitly excluding macOS
"because of SDK licensing issues" — `Makefile:142-152`). `profiling`/`maxperf`/
`maxperf-no-asm` (`Makefile:228-238`) build with `-C target-cpu=native` at increasingly
aggressive Cargo profiles for local performance work — `maxperf-no-asm` additionally
drops the `asm-keccak` feature and re-enables a long explicit feature list
(`jemalloc,min-trace-logs,otlp,otlp-logs,reth-revm/portable,js-tracer,keccak-cache-global,gmp,rocksdb`)
for environments where hand-written assembly Keccak isn't portable.

### `help` — the self-documenting target

Unlike Erigon's `## target: description` convention (parsed by `sed`), Reth's `help`
target uses a two-space-prefixed `## description` comment on the *same line* as the
target, parsed by `awk`, with an additional `##@ Section` heading convention:

```make
help: ## Display this help.
	@awk 'BEGIN {FS = ":.*##"; printf "Usage:\n  make \033[36m<target>\033[0m\n"} /^[a-zA-Z_0-9-]+:.*?##/ { printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2 } /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) } ' $(MAKEFILE_LIST)
```

(`Makefile:38-40`) — only targets with a trailing `## description` comment appear in
`make help` output, grouped under `##@ Build`, `##@ Test`, `##@ Other` section headers
(`Makefile:36, 154, 197`). Several targets used internally by `lint`/`pr`/`test`
(`fmt`, `clippy`, `cargo-test`, `test-doc`, `pr` itself) deliberately have **no** trailing
`##` comment and so do not appear in `make help` — the self-documentation convention
covers primary entry points, not every internal composite step.

---

## Benchmark pipeline (bench.yml + bench-reth-summary.py)

This is confirmed, after reading both files in full across two passes each, to be
**heavier infrastructure than Nethermind's `gas-benchmark` skill**, not lighter — the
`nethermind/dev-workflow-skills-pattern.md` verdict already flags Nethermind's pipeline
(Docker build + cross-repo workflow trigger + dotTrace analysis) as needing infrastructure
fukuii doesn't have. Reth's version adds self-hosted bare-metal runners with kernel-level
tuning, a custom disk-snapshot-rollback tool (`schelk`), MinIO-hosted binary manifests,
OTLP/Grafana/VictoriaMetrics observability export, Slack notification with a user-mapping
file, and a cross-repo GitHub artifact-storage side-channel (`decofe/reth-bench-charts`).
None of this infra exists in fukuii today, and building it is explicitly out of scope for
a "port this workflow" exercise — see the verdict table. The one genuinely portable piece,
independent of any of that infra, is the **statistical methodology** in
`bench-reth-summary.py`: whole-run cluster bootstrap confidence intervals plus a
per-metric practical-significance floor. That method is a reusable idea worth carrying
forward for whenever fukuii eventually has any benchmark pipeline at all, even a purely
local one — it is documented in full below specifically so it isn't lost.

### Trigger surfaces and the org-membership gate

`bench.yml` (1,850 lines) triggers on two events (`bench.yml:9-108`): `issue_comment`
(ChatOps — a PR comment starting `@decofe bench`/`derek bench` or the `clear` variants)
and `workflow_dispatch` with an extensive input schema (`blocks`, `big_blocks`, `reorg`,
`bal`, `warmup`, `baseline`/`feature` refs, `wait_time`, `baseline_args`/`feature_args`,
`samply`, `tracing_chrome`, `cores`, `slack`, `run_pairs`, `otlp`, `metrics`).

The `bench-ack` job's **"Check org membership" step** (`bench.yml:175-205`) is the
security gate: for `issue_comment` triggers, it calls
`github.rest.orgs.checkMembershipForUser` against the `paradigmxyz` org for *both* the
commenter and the PR author, using a scoped `DEREK_BENCH_ACK_TOKEN` secret, and hard-fails
(`core.setFailed`) if either check fails. This exists because `issue_comment` triggers run
with the base repo's permissions/secrets regardless of who wrote the comment — without
this gate, any GitHub user could comment on any PR (including from a fork) and trigger a
workflow with access to `DEREK_PAT`/`DEREK_TOKEN`/Slack tokens/OTLP endpoints on
self-hosted runners. `workflow_dispatch` triggers skip this check because dispatching a
workflow already requires write access to the repo.

### Argument parsing — a small parser embedded in the workflow

The "Parse arguments" step (`bench.yml:250-548`) is a ~300-line inline JS parser (via
`actions/github-script`) handling two independent input surfaces (`workflow_dispatch`
inputs vs. a free-text ChatOps comment body) and normalizing them to one output set. The
ChatOps parser (`bench.yml:342-464`) tokenizes `key=value` pairs with a regex that
respects quoted values (`argRegex = /(\S+?="[^"]*"|\S+?='[^']*'|\S+)/g`,
`bench.yml:355`), validates each key against typed argument sets (`intArgs`, `refArgs`,
`boolArgs`, `enumArgs` with `Map`-based enum choices, `durationArgs` matched against
`/^\d+(ms|s|m)$/`, `stringArgs`), and — notably — **posts a PR comment listing every
unknown or invalid argument before failing the job**, rather than failing silently in the
Actions log where a non-maintainer commenter would never see it (`bench.yml:431-444`).

Two default-value rules worth calling out precisely because they're conditional on
`big_blocks`: `defaultBlocks` returns `500` normally, `30` in big-blocks mode
(`bench.yml:274`), and `defaultRunPairs` returns `6` normally, `10` in big-blocks mode
(`bench.yml:276`) — big-block runs are noisier (fewer, heavier blocks) so more run pairs
are needed for the same statistical confidence.

### The ABBA/AB run-order interleaving — reducing systematic bias

`benchRunOrder` (`bench.yml:283-289`, re-implemented identically in bash at
`bench.yml:1226-1238` for the actual execution loop) computes the run sequence from
`run_pairs`:

```js
const benchRunOrder = (runPairs) => {
  const n = Number(runPairs);
  if (!Number.isInteger(n) || n < 1) {
    throw new Error('run pairs must be a positive integer');
  }
  return n % 2 === 0 ? 'ABBA'.repeat(n / 2) : 'AB'.repeat(n);
};
```

An even `run_pairs` produces `ABBA` blocks (e.g. `run_pairs=6` → `n/2=3` repeats of
`ABBA` → `ABBAABBAABBA`, 12 runs total: 6 feature + 6 baseline); an odd `run_pairs`
produces plain alternating `AB` blocks. The inline comment at the execution
site states the intent directly: "Interleaved run order (A=feature, B=baseline) to reduce
systematic bias from thermal drift and cache warming" (`bench.yml:1215-1216`). `ABBA`
blocks are a stronger anti-drift pattern than plain alternation because they nest each
baseline-adjacent pair symmetrically around the midpoint of every 4-run block, so a
monotonic drift (e.g. steadily rising ambient temperature over an hour-long run) affects
the average position of A-runs and B-runs almost equally — plain `AB` repetition instead
lets a monotonic drift systematically favor whichever side runs first in each pair.

### The `schelk` snapshot-rollback mechanism

`schelk` (installed via `cargo install --git https://github.com/tempoxyz/schelk --locked`,
`bench.yml:926-929`) plus `thin-provisioning-tools` (installed by cloning
`jthornber/thin-provisioning-tools` and running `make install`, providing `era_invalidate`
which `schelk` depends on, `bench.yml:918-923`) together implement instant disk-snapshot
rollback on the self-hosted runners. The mechanism, read in full from
`.github/scripts/bench-reth-snapshot.sh` (180 lines):

1. A `SCHELK_MOUNT` (e.g. `/reth-bench`) is a dedicated block-device-backed mount managed
   by `schelk`. Before each benchmark run, `schelk recover -y --kill` (or
   `full-recover -y` as a fallback) rolls the mount back to its last-promoted snapshot
   state, so every run starts from byte-identical on-disk state regardless of what the
   previous run wrote (`bench-reth-snapshot.sh:85`, and again in `bench.yml:1148` as a
   pre-flight cleanup step before the actual run loop).
2. The script compares a **remote manifest** (fetched from
   `BENCH_SNAPSHOT_MANIFEST_URL`, canonicalized via `jq -S .` and SHA-256 hashed) against
   a **local manifest marker** (`$DATADIR/manifest.json`) to decide whether the snapshot
   needs refreshing at all — a `--check`-only mode exits `10` if stale without doing the
   (expensive) refresh (`bench-reth-snapshot.sh:26-34, 128-145`).
3. If stale, it re-downloads the snapshot via the built `reth` binary's own `reth
   download --manifest-path ... --minimal --datadir ...` subcommand (not a generic
   file-fetch tool — Reth's own snapshot-download machinery is reused here,
   `bench-reth-snapshot.sh:163-167`), verifies the resulting layout has both `db/` and
   `static_files/` directories, writes the new local manifest marker, and finally calls
   `sudo schelk promote -y` to make the freshly-downloaded state the new rollback
   baseline (`bench-reth-snapshot.sh:175-180`).
4. A separate, weekly-rotating manifest path exists for the **big-blocks** dataset
   (`resolve_big_blocks_manifest`, `bench-reth-snapshot.sh:43-62`): it computes an
   ISO-week string N weeks in the past (`BENCH_BIG_BLOCKS_SNAPSHOT_AGE_WEEKS`, default 2)
   and derives a snapshot name/URL from it — so the big-blocks fixture is deliberately a
   couple of weeks stale rather than the freshest possible chain tip, presumably to avoid
   fixture churn destabilizing week-over-week comparisons.

This gives every benchmark run (baseline and feature alike) an identical starting
database state in roughly the time it takes to roll back a filesystem snapshot, rather
than the minutes-to-hours it would take to re-sync or re-copy a multi-hundred-GB chain
datadir between runs — this is the single most infrastructure-heavy piece of the whole
pipeline and has no analog anywhere in fukuii's tooling.

### Kernel/hardware tuning for reproducible measurements

The "System setup" step (`bench.yml:1076-1141`) performs OS-level tuning specific to
bare-metal AMD self-hosted runners before every benchmark run: switches `amd_pstate` to
`passive` mode so the kernel governor (not hardware EPP) controls frequency, pins all
cores to their CPPC nominal frequency (reading `acpi_cppc/nominal_freq` or
`cpufreq/base_frequency`), disables swap, disables ASLR
(`kernel.randomize_va_space=0`), disables SMT/hyperthreading (offlining every sibling
thread via `topology/thread_siblings_list`), disables transparent huge pages (noting THP
compaction "causes latency spikes"), holds a `/dev/cpu_dma_latency` file descriptor open
via a background `sleep infinity` process to prevent deep C-state entry, migrates all IRQ
affinity to core 0, and stops a list of noisy background services (`irqbalance`, `cron`,
`atd`, `unattended-upgrades`, `snapd`, several `prometheus-node-exporter-*` timers,
`sysstat-collect`/`sysstat-summary`). A matching "Restore system settings" step
(`bench.yml:1833-1850`, gated `if: always()`) undoes every change — restoring frequency
scaling range from `cpuinfo_min_freq`/`cpuinfo_max_freq`, switching `amd_pstate` back to
`active` (EPP) mode, killing the C-state-pinning background process, and restarting the
stopped services — so the runner is left in its normal operating mode for whatever job
runs next.

### big_blocks / reorg / bal / samply / profiling modes

Five orthogonal knobs, each documented from the `workflow_dispatch` input schema
(`bench.yml:19-108`) and their downstream handling:

- **`big_blocks`** — `false`/`true`/an explicit gas target like `100M`/`2G`
  (`parseBigBlocks`, `bench.yml:267-273`). When enabled, block count defaults to `30`
  instead of `500`, run pairs default to `10` instead of `6`, the binary built/run is
  `reth-bb` instead of `reth` (`nodeBin` selection, `bench.yml:523`), and the snapshot
  synced is the separate `datadir-big-blocks` dataset via the weekly-rotating manifest
  path described above.
- **`reorg`** — an optional positive-integer reorg depth (`parseReorg`,
  `bench.yml:277-282`); when set, benchmark replay exercises a chain reorganization of
  that depth rather than a straight-line replay.
- **`bal`** — a four-way choice (`false`/`true`/`feature`/`baseline`,
  `validBalModes`, `bench.yml:257`) controlling whether Block Access Lists are replayed
  during big-block benchmarks, and if so, on which side of the comparison only (useful
  for isolating BAL-specific overhead on just the feature or just the baseline binary).
- **`samply`** — CPU sampling profiler integration. When enabled, `samply` (installed
  from `github.com/DaniPopes/samply`'s `edge` branch, `bench.yml:931-935`) captures a
  profile per run; after the run loop, each `samply-profile.json.gz` is uploaded directly
  to the public Firefox Profiler API (`profiler.firefox.com`) via its
  `compressed-store`/`shorten` endpoints, with the resulting short URL persisted to
  `samply-profile-url.txt` per run directory (`bench.yml:1409-1452`) and surfaced both in
  the PR comment and Slack notification.
- **`tracing_chrome`** — Chrome-trace-format recording; traces are gzip-compressed and
  pushed alongside the chart PNGs to the `decofe/reth-bench-charts` side repo, then linked
  through a generated `ui.perfetto.dev` URL (`bench.yml:1630-1652`) rather than the
  Firefox Profiler flow samply uses.

`profiling`/`maxperf` build profiles (Makefile, described above) are the *build-time*
counterpart to these *runtime* modes — a maintainer benchmarking locally would typically
build with `maxperf` and only need `bench.yml`'s CI machinery for a PR-triggered,
statistically-compared run.

### OTLP / VictoriaMetrics / Grafana export

Three independent, individually-toggleable observability exports run per benchmark:

- **OTLP traces/logs** (`otlp` input, on by default) — `BENCH_OTLP_TRACES_ENDPOINT`/
  `BENCH_OTLP_LOGS_ENDPOINT` secrets are explicitly masked twice: once via
  `core.setSecret(endpoint)` on the raw value, and again on a credential-stripped version
  (username/password removed from the URL) so the sanitized form doesn't accidentally
  leak the full endpoint including embedded auth if only the raw mask misses a
  log-formatting edge case (`bench.yml:867-886`). OTLP is force-disabled
  (`otlp = 'false'`) whenever either ref being compared looks like a release tag
  (`releaseRef` regex `^v\d+\.\d+\.\d+`) or the trigger itself is a tag push
  (`bench.yml:501-504`) — release-tag benchmarks aren't meant to pollute the live
  tracing backend with one-off release-validation noise.
- **VictoriaMetrics** (`metrics` input, off by default) — `BENCH_VICTORIAMETRICS_URL`
  is masked (`bench.yml:1154-1156`) and used to export txgen scrape metrics; a
  `BENCH_TARGET_METRICS_CONFIG` file (`.github/config/bench-metrics-targets.json`) and a
  200ms scrape interval (`BENCH_TARGET_METRICS_SCRAPE_INTERVAL_MS`) drive which metrics
  get sampled during the run for later target-metric comparison (see
  `compute_target_metric_change` in the summary script, referenced but not fully detailed
  here — it's the mechanism behind `--target-metrics-config` in
  `bench-reth-summary.py`'s CLI).
- **Grafana** — a pre-built dashboard URL (`GRAFANA_URL`,
  `bench.yml:1289`) plus two ad-hoc-generated Grafana Explore URLs (one for logs, one for
  traces) are constructed via an inline Python heredoc that builds the Explore pane JSON
  and URL-encodes it (`bench.yml:1290-1364`) — these become the "Logs"/"Traces" links in
  both the PR comment and the Slack notification.

### The statistical core (`bench-reth-summary.py`) — the reusable idea

This is the piece worth carrying forward independent of any of the infra above. Read in
full across two passes (function-name inventory via `grep '^def '`, then the specific
statistics functions in detail).

**Point estimates: pooled, with a per-run-averaged fallback for percentiles.**
`compute_point_stats` (`bench-reth-summary.py:271-286`) pools all rows from every run of
one side (baseline or feature) for the mean/wall-clock/mgas figures, but for `p50`/`p90`/
`p99` — when 2+ runs exist — instead averages the *per-run* percentile values
(`_mean([run_stats[key] for run_stats in per_run_stats])`) rather than computing one
percentile over the pooled multi-run dataset. The doc comment explains why: "Cluster CIs
estimate percentile changes from one percentile value per run. Match that estimator for
displayed percentile point estimates instead of pooling repeated block rows into one
percentile sample" (`bench-reth-summary.py:272-276`) — i.e. the point estimate and the
confidence interval must use the *same* underlying estimator, or the displayed
"change %" and its CI would be describing two different statistics.

**Confidence intervals: whole-run cluster bootstrap, 10,000 iterations.**
`BOOTSTRAP_ITERATIONS = 10_000` (`bench-reth-summary.py:35`). The primary method,
`_cluster_bootstrap_ci` (`bench-reth-summary.py:425-456`), is used whenever both sides
have 2+ runs (the normal case given `run_pairs` defaults to 6 or 10):

1. For each of 7 metrics (`mean_ms`, `p50_ms`, `p90_ms`, `p99_ms`, `mgas`, `wall_clock_ms`,
   `persist_ms`), compute one scalar value **per whole run** (`_per_run_metric_values`,
   `bench-reth-summary.py:402-422` — literally `compute_stats(run)` called once per run,
   not per block).
2. For each of 10,000 bootstrap iterations: resample the baseline run indices with
   replacement (`rng.randrange` × `baseline_count` draws) and independently resample the
   feature run indices the same way; compute `mean(feature_sample) - mean(baseline_sample)`
   for that iteration.
3. Sort the 10,000 resulting differences and take the 95% CI half-width as
   `(samples[hi] - samples[lo]) / 2` where `lo`/`hi` are the 2.5th/97.5th percentile
   indices (`_ci_half_width`, `bench-reth-summary.py:387-395`).

The critical design choice is resampling **whole runs**, not individual blocks — the
doc comment states this explicitly: "This estimates run-to-run noise without expanding
reused baseline/feature runs into independent block-level datapoints"
(`bench-reth-summary.py:433-435`). Treating every block within a run as an independent
observation would understate the true variance, since blocks within one run share
systematic run-level noise (thermal state, cache warmth, background load at that moment)
— cluster bootstrapping over runs is the standard fix for this kind of correlated-data
problem.

**Fallback: block-level paired bootstrap for single-run comparisons.** When either side
has fewer than 2 runs, `compute_ci_stats` (`bench-reth-summary.py:459-507`) falls back to
`_paired_data` (matching baseline/feature rows by `block_number`,
`bench-reth-summary.py:308-349`) plus `_bootstrap_ci`/`_bootstrap_percentile_ci`
(`bench-reth-summary.py:352-384`), which resample individual block-level diffs with
replacement instead of whole runs — explicitly a weaker estimator, used only when there
aren't enough runs to cluster-bootstrap over.

**Practical-significance floor, per metric — not just statistical significance.**

```python
PRACTICAL_FLOOR_PCT = {
    "mean": 1.20,
    "p50": 1.20,
    "p90": 1.35,
    "p99": 5.0,
    "mgas_s": 1.20,
    "wall_clock": 0.70,
    "persist_wait": 5.0,
}
```

(`bench-reth-summary.py:46-54`). `significance()` (`bench-reth-summary.py:552-564`)
labels a result `"good"`/`"bad"`/`"neutral"` by checking whether the **entire confidence
interval** clears this floor, not just whether the point estimate does:

```python
def significance(pct, ci_pct, floor_pct, lower_is_better):
    improvement_pct = -pct if lower_is_better else pct
    if improvement_pct - ci_pct > floor_pct:
        return "good"
    if improvement_pct + ci_pct < -floor_pct:
        return "bad"
    return "neutral"
```

This is a materially stricter bar than "is the CI's lower bound above zero" (plain
statistical significance) — a 0.3% latency improvement with a tight, entirely
zero-excluding CI is still reported `"neutral"` here, because 0.3% doesn't clear the
1.20% practical floor for `mean`. The floor is a fixed percentage of the baseline value
for every metric except `p99`, which additionally requires a minimum sample size
(`P99_MIN_VERDICT_BLOCKS = 125`, `bench-reth-summary.py:37`) below which the whole verdict
is downgraded to "informational" regardless of what the numbers say
(`informational_reason`, `bench-reth-summary.py:575-592`) — p99 is inherently noisy at
low block counts and the pipeline refuses to render a false-confidence verdict on it.
`persist_wait` gets a similar informational carve-out when the persistence-wait
contribution isn't `persist_wait_is_material` (below `max(0.5ms, 0.1% of total latency)`,
`bench-reth-summary.py:567-572`) on *either* side — a near-zero wait time being 40% "worse"
in relative terms is meaningless noise, not a regression.

**Overall verdict roll-up.** `verdict()` (`.github/scripts/bench-utils.js:29-37`, called
from both the Slack notifier and, by inference from its shared-module placement, the PR
comment renderer) reduces the per-metric `good`/`bad`/`neutral` labels — filtering out any
marked `informational` — to one of four overall labels: `⚠️ Mixed Results` (both a good and
a bad metric present), `❌ Regression` (bad, no good), `✅ Improvement` (good, no bad), or
`⚪ No Difference` (neither). `isWin()` (`bench-utils.js:39-42`) is the stricter
"unambiguous improvement" check used to decide whether to post to the public Slack channel
(only genuine wins, never mixed results, get broadcast — see below).

### Slack notification and ClickHouse dashboard — distribution mechanism (brief)

`.github/scripts/bench-slack-notify.js` (371 lines, read in full) posts Slack
Block-Kit-formatted messages via `chat.postMessage`. The routing policy, read directly
from `success()` (`bench-slack-notify.js:269-339`): results are posted to a shared public
channel (`SLACK_BENCH_CHANNEL`) **only** when `isWin(summary.changes)` is true; otherwise,
if a GitHub-username → Slack-user-ID mapping exists in `bench-slack-users.json`, the actor
gets a private DM instead; in `slack=on-win` mode, non-win results produce no
notification at all. Failures always DM the triggering actor (never the public channel) —
`failure()` (`bench-slack-notify.js:342-369`). `.github/scripts/bench-upload-clickhouse.py`
(150 lines, read in full) is a separate, simpler mechanism: it reads the same
`summary.json` the Slack notifier reads, and does a raw `INSERT INTO
bench_dual_comparisons` over ClickHouse's HTTP interface (credentials via header, not
embedded in the URL) — feeding a PM-facing dashboard rather than an engineering
notification channel. Both mechanisms are downstream consumers of the same
`summary.json` the statistical core above produces; neither adds new statistical
machinery of its own.

---

## bench-benchmarkoor.yml — narrower micro-benchmark suite

A structurally separate, narrower-scope workflow (719 lines) from the main
`bench.yml` engine-API replay pipeline. Its own header comment states the distinction
directly: "Unlike the normal reth benchmark workflow, this prepares a post gas-bump/funding
baseline with benchmarkoor-replay. Each selected test then resets to that baseline, runs
setup outside the measured window, restarts Reth, drops Linux page cache, and measures
only the testing fixture replay" (`bench-benchmarkoor.yml:1-6`).

Key differences from `bench.yml`, all read directly from the `workflow_dispatch` input
schema (`bench-benchmarkoor.yml:8-142`):

- **Test selection is opcode/gas-bucket-shaped, not block-range-shaped.** Inputs include
  `opcode` (an opcode selector), `gas_bucket` (e.g. `"210M"`), `cache_strategy` (e.g.
  `NO_CACHE`), `account_mode` (e.g. `EXISTING_EOA`), plus `contains`/`pattern`/
  `exact_test` string/regex selectors and a `limit` (default 20) capping how many matched
  tests run in one invocation. This is a fine-grained micro-benchmark harness targeting
  specific opcode/gas/account-shape combinations, not a whole-block engine-API replay.
- **A MinIO-hosted snapshot, not a schelk-managed manifest URL.** The `snapshot` input
  defaults to `"minio/reth-snapshots/perfnet-24358000-full/"`
  (`bench-benchmarkoor.yml:51-55`) and a dedicated `mc` (MinIO client) install step exists
  (`bench-benchmarkoor.yml:217-220`, same install pattern as `bench.yml`'s dependency
  step). `BENCHMARKOOR_SNAPSHOT_MC_ROOT: "minio"` (`bench-benchmarkoor.yml:174`) confirms
  the snapshot source is an `mc`-addressed MinIO bucket rather than an arbitrary HTTPS
  manifest URL.
- **Per-test reset strategy is selectable** (`reset_strategy`: `schelk` or `unwind`,
  `bench-benchmarkoor.yml:114-121`) — `schelk` reuses the same disk-snapshot-rollback tool
  `bench.yml` uses; `unwind` is a lighter-weight alternative presumably using Reth's own
  chain-unwind machinery to roll state back between tests instead of a full disk-snapshot
  restore, avoiding the schelk round-trip cost for tests that don't need a byte-identical
  disk state.
- **1 or 2 repetitions per test**, not a configurable `run_pairs`
  (`repetitions`: a `choice` input restricted to `"1"`/`"2"`, `bench-benchmarkoor.yml:101-108`)
  — a much shallower statistical sampling than `bench.yml`'s default 6-10 run pairs,
  consistent with this being a broad opcode/gas-bucket sweep (many tests × few repetitions)
  rather than a single high-confidence A/B comparison (few comparisons × many repetitions).
- **Its own summary script uses plain median/statistics, not the cluster bootstrap.**
  `.github/scripts/bench-benchmarkoor-summary.py` (313 lines) imports Python's stdlib
  `statistics` module and computes `statistics.median` (`bench-benchmarkoor-summary.py:67`)
  — no `BOOTSTRAP_ITERATIONS`, no per-metric practical floor, no cluster resampling
  anywhere in the file (confirmed via `grep '^def compute\|^def summarize'` — only
  `summarize_reset_timings` and a handful of formatting helpers exist). This confirms the
  elaborate statistical machinery described above is specific to `bench-reth-summary.py`
  and the main engine-API-replay pipeline; the micro-benchmark suite trades statistical
  rigor for breadth (many opcode/gas-bucket combinations per invocation, `limit` capping
  at 20 by default).
- Runs on the same `[self-hosted, Linux, X64, available]` runner pool as `bench.yml`
  (`bench-benchmarkoor.yml:154`), with its own dedicated 32G memory cap
  (`BENCH_MEMORY_MAX: "32G"`, `bench-benchmarkoor.yml:163`) and a 180-minute timeout
  (`bench-benchmarkoor.yml:157`) — longer than `bench.yml`'s 120-minute timeout
  (`bench.yml:737`), consistent with sweeping up to 20 individual tests per run rather
  than one A/B comparison.

---

## Release checklist (docs/release.md)

Reth "does not currently have a regular release cadence while it is still experimental
software" (`docs/release.md:5`). The maintainer checklist is the entire remaining content
of the 48-line file, reproduced here in full since the task requires it verbatim:

**Release PR:**
- [ ] Create a new branch (e.g. `release/vx.y.z`) and open a pull request for it
- [ ] Ensure *all* tests and lints pass for the chosen commit
- [ ] Version bump — Update the version in *all* `Cargo.toml`'s
- [ ] Commit the changes — message format `release: vx.y.z`
- [ ] The PR should be reviewed to see if anything was missed
- [ ] Once reviewed, merge the PR

**Releasing:**
- [ ] Tag the new commit on main with `vx.y.z` (`git tag vx.y.z SHA`)
- [ ] Push the tag (`git push origin vx.y.z`) — a footnote explicitly discourages `git
  push --tags` since "it can be very difficult to get rid of bad tags"
- [ ] Update the Homebrew Tap (`paradigmxyz/homebrew-brew`)
- [ ] Run the release commit on testing infrastructure for **1-3 days** to check for
  inconsistencies and bugs — this infra syncs and keeps up with a live testnet, monitoring
  bandwidth/CPU/disk space etc. (`docs/release.md:29-30`)

A boxed note stresses the `v` prefix on the tag is load-bearing: "If it is missing, the
release workflow **will not run**" (`docs/release.md:34`). Once the tag is pushed,
artifacts build automatically and a **draft** release is created with a template that
must be manually filled out: a summary of highlights, the update priority, and an
auto-generated changelog. The changelog source is explicit: PRs labeled
[`M-changelog`](https://github.com/paradigmxyz/reth/labels/M-changelog) — maintainers tag
PRs with this label as they merge, and release-note-writing is reduced to reading that
label's PR list rather than reconstructing "what changed" from the full commit history.
This is a materially lighter-weight release process than a fully automated one: version
bump, tag, and Homebrew update are manual checklist items with no CI automation gating
them beyond the tag-triggered artifact build itself, and the 1-3 day soak test on live
testnet infra is an explicit, non-automatable manual gate before a release is considered
safe to publish for real.

---

## Docker pre-merge checklist (docs/repo/ci.md)

`docs/repo/ci.md` (69 lines) is, as expected, mostly a flat index of workflow links
grouped under `Code`/`Docs`/`Meta`/`Integration Testing`/`Linting and Checks` headers —
one line per workflow, each linking to the actual `.github/workflows/*.yml` file on
GitHub. The one substantive exception is the **"Docker workflow changes"** subsection,
which is a genuine six-step pre-merge checklist, reproduced here in full as required:

> Docker changes usually affect more than one workflow. Before merging changes to
> Dockerfiles, Docker build inputs, or files copied into images:
>
> - Check all Dockerfiles and bake targets that share the repository build context:
>   `Dockerfile`, `Dockerfile.depot`, `.github/scripts/hive/Dockerfile`, and
>   `docker-bake.hcl`.
> - Keep `.dockerignore` in sync with every file or directory copied from the repository
>   context. A file that exists in git can still be missing from Docker builds if it is
>   not allowlisted there.
> - Check the workflow users of those targets: `docker.yml`, reusable `docker-test.yml`,
>   and callers such as `hive.yml` and `kurtosis.yml`.
> - Run local syntax checks before opening or merging the PR when BuildKit/buildx is
>   available, for example `docker build --check -f Dockerfile .`, `docker build --check
>   -f Dockerfile.depot .`, and `docker buildx bake --print -f docker-bake.hcl`.
> - Run at least one GitHub Actions dry run for the affected workflow. For the publishing
>   workflow, use `docker.yml` with `workflow_dispatch`, `build_type=git-sha`, and
>   `dry_run=true`.
> - When updating shared image contents, look at recent Docker-related PRs and existing
>   workflow history to identify when failures started and whether sibling workflows are
>   affected.

(`docs/repo/ci.md:24-41`). This checklist exists because Reth genuinely has multiple
Dockerfiles sharing one build context (`Dockerfile`, `Dockerfile.depot`,
`Dockerfile.reproducible` at the repo root, confirmed present via `ls`, plus a
hive-specific one under `.github/scripts/hive/`) and a `docker-bake.hcl` (2,338 bytes)
coordinating multi-target builds across them — a single `.dockerignore` miss or
`bake`-target drift can silently break one consumer workflow (`hive.yml`, `kurtosis.yml`)
while `docker.yml` itself still passes, which is exactly the failure mode this checklist
is designed to catch before merge rather than after a downstream workflow starts failing
mysteriously.

The rest of the file: `unit`/`integration`/`bench`/`sync`/`stage` under Code; `book`
(builds/tests/deploys the Docusaurus-based `vocs` book) under Docs; `release`/
`release-dist`/`dependencies` (periodic `cargo update`)/`stale`/`docker` under Meta;
`kurtosis`/`hive` under Integration Testing; `lint`/`lint-actions`/`label-pr` under
Linting and Checks — each a one-line description plus a link, confirming the file's own
framing of itself as "the CI runs a couple of workflows" (`docs/repo/ci.md:3`) rather than
a deep-dive (that role is filled by reading the individual workflow YAML files directly,
which this report does for `bench.yml`/`bench-benchmarkoor.yml` above).

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable | Reasoning |
|---|---|---|
| `pr`'s doc-build-as-a-check step (`cargo docs --document-private-items` inside the gate, plus `update-book-cli`'s CLI-doc-drift check enforced by `book` CI via `git diff --exit-code`) | **Needs design** | `sbt pp` has no analog: it runs `compile-all` + formatting + `rlp/test` + `testQuick` + `IntegrationTest/test`, but never builds Scaladoc as a pass/fail gate, and fukuii has no auto-generated CLI reference docs at all (so there is nothing that could drift the way Reth's `docs/vocs/docs/pages/cli/` can). If fukuii ever adds a CLI-doc-generation step, the "regenerate + `git diff --exit-code`" enforcement pattern is directly reusable; if not, the doc-build-as-a-check idea alone (fail `pp` if Scaladoc doesn't build) is a small, low-cost addition worth considering on its own. |
| `db-tools` — compiling vendored-C MDBX debugging binaries (`mdbx_chk`/`mdbx_dump`/`mdbx_stat` etc.) into a dedicated `db-tools/` directory | **Not portable** | fukuii uses RocksDB (via `vault` agent's domain, `db/` package), not MDBX — there is no vendored MDBX C source tree to build debugging tools from. RocksDB ships its own `ldb`/`sst_dump` CLI tools as part of the RocksDB distribution itself rather than requiring a client to vendor and build them; if fukuii needs equivalent DB-inspection tooling, the fix is documenting/wrapping RocksDB's own existing CLI tools (already the `fukuii-disk-management` skill's territory), not porting Reth's `db-tools` Makefile target. |
| The ABBA/AB interleaved run-order pattern (`benchRunOrder`, resample-resistant to thermal/cache drift) | **Needs design (concept only, until any benchmark pipeline exists)** | fukuii has zero CI benchmark workflow today — the existing `Benchmark` sbt module (`build.sbt:240`, `MerklePatriciaTreeSpeedSpec`/`RLPSpeedSuite`) is two ad hoc `log.info`-timed ScalaTest suites with no run-ordering discipline at all (confirmed in `nethermind/dev-workflow-skills-pattern.md`'s `gas-benchmark` verdict). The ABBA-interleaving *idea* — alternate baseline/feature runs symmetrically rather than running all of one side then all of the other — is infrastructure-independent and costs nothing to adopt whenever `fukuii-benchmark-diff` (or any successor) runs more than one pair of timed runs. Not urgent on its own; bundle it into whatever benchmark-tooling design eventually happens. |
| The cluster-bootstrap-CI + practical-significance-floor statistical method (`_cluster_bootstrap_ci`, `PRACTICAL_FLOOR_PCT`, `significance()`) | **Needs design (the one idea worth pre-registering now)** | This is the single most valuable extractable idea in the whole pipeline, and it is genuinely infrastructure-independent — it operates on a list of per-run scalar values and needs nothing beyond Python's (or Scala's) stdlib RNG. fukuii's current benchmark suites produce exactly the per-run scalar shape this method consumes (a timing number per `benchmark:testOnly` invocation). When fukuii eventually builds any benchmark-comparison tooling (`fukuii-benchmark-diff` or a real CI pipeline), this method — resample whole runs with replacement 10,000×, take the 95% CI half-width from the 2.5/97.5 percentile of the resampled differences, and only call a result "good"/"bad" if the *entire* CI clears a fixed practical-significance floor (not just statistical zero-exclusion) — should be the default statistical design, not raw before/after percentage deltas. Document this now so it isn't rediscovered from scratch later. |
| The full `bench.yml`/`bench-benchmarkoor.yml` infrastructure superstructure: self-hosted bare-metal runners with kernel/CPU tuning, `schelk` disk-snapshot rollback, MinIO-hosted snapshots, OTLP/Grafana/VictoriaMetrics export, ChatOps trigger with org-membership gating, Slack notification with a user-ID mapping file, cross-repo chart-artifact storage, ClickHouse PM dashboard ingestion | **Not portable** | This is confirmed, after a full read of both workflows, to be *more* infra-heavy than Nethermind's `gas-benchmark` skill (already flagged in `nethermind/dev-workflow-skills-pattern.md` as needing infrastructure fukuii doesn't have — no devnet, no Docker publish pipeline). Reth adds several more independent infra dependencies on top of that (self-hosted runners with root-level kernel tuning, a bespoke Rust snapshot-rollback tool requiring `thin-provisioning-tools`, a MinIO deployment, a ClickHouse instance, a Slack app with a bot token and a maintained username-mapping file). None of this is buildable as a side effect of writing a fukuii benchmark skill — it is a standing platform investment. Do not attempt to replicate any of it; treat the statistical-method row above as the only piece worth carrying forward independent of this infra. |
| `docs/release.md`'s three-part maintainer checklist (release PR → tag/push → 1-3 day live-testnet soak test, changelog sourced from an `M-changelog` PR label) | **Needs design (lightweight)** | fukuii has no formal release process or tagging discipline documented yet. The specific mechanics don't transfer literally (fukuii has no Homebrew tap, and "all `Cargo.toml`'s" has no fukuii analog beyond `build.sbt`'s version string), but the *shape* — a short, mostly-manual checklist ending in a mandatory multi-day soak test on live network infra before publishing, plus sourcing changelog content from a merge-time PR label rather than reconstructing history after the fact — is a reasonable lightweight starting point whenever fukuii formalizes releases. Worth a short equivalent doc, not urgent. |
| Docker pre-merge six-step checklist (Dockerfile/bake-target sync, `.dockerignore` sync, workflow-caller check, local `docker build --check`, `workflow_dispatch` dry run, recent-Docker-PR-history check) | **Port now** | This is directly and cheaply applicable regardless of Reth's Rust/MDBX specifics — it's a checklist about *process discipline around shared Docker build contexts*, not about Reth's own Dockerfile contents. fukuii's own `docker/` directory (referenced in the global `docker-deployment.md` rules) would benefit from an equivalent explicit checklist the next time a Dockerfile or `docker-bake`-equivalent changes, especially the "run a `workflow_dispatch` dry run before merging" and "check recent Docker PR history for when failures started" habits, which generalize to any project with more than one Docker-consuming CI workflow. |
| `help`'s `##`/`##@`-comment-driven self-documenting Makefile convention (materially the same idea as Erigon's, already assessed as "no action needed" in `erigon/build-release-pattern.md` since fukuii already keeps `build.sbt`'s `addCommandAlias` block as the authoritative command list) | **Not portable as literal syntax; principle already satisfied** | Same conclusion as the Erigon report reached independently: sbt has no lightweight equivalent of parsing inline comments into generated help text, but fukuii already treats `build.sbt`'s `addCommandAlias` block as the single source of truth for command definitions (per `AGENTS.md`'s build-command table explicitly deferring to it). No new action needed beyond continuing that existing discipline. |

---

*Compiled from a direct read of every file cited above in the vendored clone at
`.claude/repo-references/clients/reth/`. `bench.yml` (1,850 lines) and
`bench-reth-summary.py` (2,155 lines) were read across two full-file passes each; all
other files cited (`Makefile`, `docs/release.md`, `docs/repo/ci.md`,
`bench-reth-snapshot.sh`, `bench-slack-notify.js`, `bench-upload-clickhouse.py`,
`bench-benchmarkoor.yml`, `bench-benchmarkoor-summary.py`, `CLAUDE.md`) were read in full.
Line numbers refer to the vendored clone's checkout at commit `3d76b93c2` (2026-07-01);
re-verify against `git log` if the vendored copy is refreshed.*
