---
name: fukuii-benchmark-diff
description: >-
  Run fukuii's local sbt Benchmark-config suites (MerklePatriciaTreeSpeedSpec,
  RLPSpeedSuite), extract their log.info/log.debug timing lines via suite-specific
  regex, store a baseline under .local/benchmarks/, and diff two sequential runs as a
  before/after percentage-delta table. Use when asked to "benchmark the MPT/RLP
  suite", "diff perf between branches", or "did this change regress trie/RLP speed".
  Local-only — does NOT build Docker images, trigger a remote workflow, do
  dotTrace/profiler analysis, or post PR comments (ported as a deliberately narrow
  subset of Nethermind's gas-benchmark skill; see "Explicitly out of scope" below).
disable-model-invocation: true
user-invokable: true
argument-hint: "mpt|rlp|both [--diff <base-sha>]"
---

# Fukuii benchmark diff

Local-only "run the suite, extract the numbers, diff two runs" workflow for fukuii's
existing sbt `Benchmark` configuration (`build.sbt:240`). There is no fukuii
equivalent of Nethermind's `gas-benchmarks` cross-repo pipeline, dotTrace profiler, or
artifact-based percentile analysis — this skill only covers the subset that's real
today: two ScalaTest suites that log elapsed-time numbers via fukuii's own `Logger`
trait, not a structured JSON/artifact output.

## The two suites

| Suite | Invocation | What it measures |
|---|---|---|
| `MerklePatriciaTreeSpeedSpec` | `sbt "benchmark:testOnly com.chipprbots.ethereum.mpt.MerklePatriciaTreeSpeedSpec"` | 1000-round in-memory MPT insert (`log.info`); 20,000,000-insert RocksDB-backed MPT insert, logged every 100,000 elements (`log.debug` — enable debug logging or it won't appear) |
| `RLPSpeedSuite` | `sbt "benchmark:testOnly com.chipprbots.ethereum.rlp.RLPSpeedSuite"` | Block/TX (de)serializations per second (`log.info`); 10,000,000-iteration RLP decode timing (`log.info`) |

Both suites are tagged (or, for `RLPSpeedSuite`, effectively scoped) out of
`testEssential`/`testStandard` — they only run under the `benchmark` sbt config, never
as part of the default test tiers (`build.sbt`'s exclusion list: `-l BenchmarkTest`).

## Procedure

### 1. Run (background, log-to-file)

```bash
scripts/agent-tooling/sbt-run.sh bench-mpt-<label> "benchmark:testOnly com.chipprbots.ethereum.mpt.MerklePatriciaTreeSpeedSpec"
scripts/agent-tooling/sbt-run.sh bench-rlp-<label> "benchmark:testOnly com.chipprbots.ethereum.rlp.RLPSpeedSuite"
```

Invoke with `run_in_background: true` per `background-script-execution.md` — this is a
real `sbt` invocation, not a quick command. Run MPT and RLP **sequentially**, never in
parallel (one heavy task at a time; the 20M-insert RocksDB test is itself heavy). Pick
`<label>` to identify which ref/branch/sha is being measured (e.g. the git short-SHA).

### 2. Extract timings (suite-specific regex — output is unstructured log text)

```bash
# MPT
grep -oP 'Time taken\(ms\): \K\d+' .local/logs/bench-mpt-<label>.log
grep -oP '=== \K\d+(?= elements put, time for batch is: [\d.]+ sec)' .local/logs/bench-mpt-<label>.log
grep -oP 'time for batch is: \K[\d.]+(?= sec)' .local/logs/bench-mpt-<label>.log

# RLP
grep -oP '(Block|TX) (de)?serializations / sec: \(\K[\d.]+(?=\))' .local/logs/bench-rlp-<label>.log
grep -oP 'Result decode\(\)\s*: \K\d+(?=ms)' .local/logs/bench-rlp-<label>.log
```

### 3. Store a baseline

Write extracted numbers + timestamp + git SHA to `.local/benchmarks/<suite>-<sha>.json`
(gitignored — this is local scratch, not a committed artifact). No fixed schema is
prescribed beyond "one JSON file per suite per SHA" — capture whatever the regex above
extracted plus the SHA and ISO timestamp.

### 4. Diff two runs

Run the suite on two refs (e.g. base branch, then current branch — sequentially, per
step 1), then report one row per metric:

| Metric | Before | After | Delta % |
|---|---|---|---|

mirroring Nethermind's Phase 4b-compare table shape, but with far fewer rows (a
handful of logged numbers, not per-fixture-file granularity).

## Explicitly out of scope (do not attempt to port these)

- **Docker build + remote-workflow-trigger superstructure** (Nethermind's Phases 0–3).
  fukuii has no `gas-benchmarks`-equivalent external repo, no devnets, no
  `publish-docker.yml`/`repricing-nethermind.yml` pair to orchestrate.
- **dotTrace / profiler-report analysis** (Phase 4e). No .NET-profiler equivalent
  wired into the JVM/Scala toolchain; a future version could target `async-profiler`
  or JFR output, but that is new tooling work, not a skill extension.
- **CI PR-comment-bot integration** (`@claude-bench` equivalent). Overlaps fukuii's
  own planned CI-review-bot design (see `nethermind/agentic-tooling-pattern.md`) —
  don't duplicate that design here. The CI half of this benchmark workflow is
  `.github/workflows/bench-mpt.yml`/`bench-rlp.yml` (push/dispatch only, no PR gate).

## Verify (self-test)

Regex design was validated against the suites' actual `log.info`/`log.debug` call
sites (`MerklePatriciaTreeSpeedSpec.scala`, `RLPSpeedSuite.scala`), not run live as
part of authoring this skill — the exact format strings are quoted directly from
source above. Run once against fresh log output before relying on this in a real
diff, and adjust the regex if a suite's log message wording ever changes.
