# fukuii-test-hive — Skill Design

_Status: design draft, ready for Phase 7 implementation. Not yet wired into
`.agents/skills/` — this document contains the complete `SKILL.md` body to be
copied verbatim (then symlinked per `.agents/protocols/agent-skills.md`)._

## Why this skill, and why now

fukuii has **extensive CI-side Hive integration** and **zero local-invocation
tooling** for it. Concretely:

- 13 `.github/workflows/hive-*.yml` caller workflows, each parameterizing one
  `ethereum/hive` simulator against fukuii.
- A shared reusable workflow, `.github/workflows/_hive-sim.yml`, that does the
  real work for 11 of the 13 (build → clone hive → sync adapter → run sim →
  tabulate → upload logs → gate) — see next section for its exact steps.
- A working, hand-maintained client adapter at `hive/fukuii/` (`Dockerfile`,
  `fukuii.sh`, `mapper.jq`, `enode.sh`, `hive.yaml`) that CI copies into a
  freshly-cloned `ethereum/hive` checkout on every run.
- A narrow local smoke-test script, `hive/test-local.sh` (see next section for
  exactly what it does and does not cover).

None of this is invocable on demand from a development session. Today,
reproducing a CI hive failure locally, or validating a change against hive
*before* opening a PR, means either (a) hand-translating 13 workflows' worth of
`--sim`/`--client`/`--sim.buildarg` incantations from memory, or (b) pushing a
branch and waiting for CI. Both are slow and error-prone. Erigon's vendored
reference client (`.claude/repo-references/clients/erigon/.claude/skills/`)
solves exactly this problem for itself with two skills, `hive-test` and
`erigon-test-hive` — this document adapts that *shape* (ephemeral setup, suite
selection, cleanup discipline, JSON-based failure parsing) to fukuii's actual
CI plumbing, which differs from Erigon's in load-bearing ways (JVM cold-start
timing, a Scala `sbt assembly` build step instead of `go build`, a
network-specific fork-config translation layer in `fukuii.sh`, and multiple
already-tracked CI flakes with documented workarounds that a local run must
reproduce faithfully rather than silently "fix").

**Is this "just wrap `test-local.sh`"?** No. `hive/test-local.sh` is a fast
(~60s), self-contained smoke test: it builds the `fukuii-hive:test` image
directly from `hive/fukuii/Dockerfile`, starts one container with a hand-built
test genesis, curls `eth_blockNumber` / `eth_chainId` / `web3_clientVersion` /
`engine_exchangeCapabilities`, execs `enode.sh` inside the container, then
stops and discards it. **It never clones or builds `ethereum/hive`, and it
never runs an actual simulator.** It validates that the adapter *boots and
answers RPC* — nothing about hive's real pass/fail semantics, multi-client
interop, or the 13 CI suites. So the skill designed here is not a thin wrapper
around an existing capability; it is the missing layer *above*
`test-local.sh`. `test-local.sh` remains valuable as an optional fast preflight
(catch a broken adapter/entrypoint before spending 20–90 minutes on a full
simulator run) and the skill design below uses it exactly that way — as Phase
0, not as the thing being wrapped.

## What fukuii already has (verified fresh)

### `_hive-sim.yml`'s exact pipeline (read in full: `.github/workflows/_hive-sim.yml`)

Reusable `workflow_call` with inputs `sim`, `label`, `sim_limit`,
`sim_buildarg`, `sim_timelimit` (default `40m`), `parallelism` (default `4`),
`pass_threshold` (default `0`), `hive_ref` (default `master`), `clients`
(default `fukuii`), `gate_pattern`, `gate_exclude`. Steps, in order:

1. Checkout fukuii (submodules, depth 1).
2. Set up JDK 25 (temurin), cache Coursier/Ivy/sbt.
3. Install sbt 1.10.7 from the GitHub release tarball (with retry-on-corrupt-download
   handling for a known intermittent Azure-blob redirect issue).
4. **`sbt -batch assembly`** — builds the fat JAR.
5. **Tag base Docker image**: generates a *minimal* `Dockerfile` inline
   (`FROM eclipse-temurin:25-jre-noble`, installs `jq curl`, copies the
   assembly jar to `/app/fukuii/lib/fukuii-assembly.jar`) and builds it as
   `chipprbots/fukuii:latest` — this is the base image `hive/fukuii/Dockerfile`
   does `FROM chipprbots/fukuii:latest` against. **The skill must reproduce
   this exact inline Dockerfile**, not assume a prebuilt image exists anywhere.
6. **Check out hive**: `git clone --depth=1 --filter=blob:none
   https://github.com/ethereum/hive.git external/hive` (retried 5× with
   backoff), explicitly **not** `./hive` — that path is fukuii's own adapter
   source directory and would be shadowed. If `hive_ref` isn't `master`, fetch
   and checkout that ref instead.
7. **Sync fukuii's hive adapter**: `mkdir -p external/hive/clients/fukuii` then
   copy `hive/fukuii/{Dockerfile,fukuii.sh,mapper.jq,enode.sh,hive.yaml}` in
   from the fukuii checkout, plus the just-built assembly jar.
8. Set up Go (`stable`, cache disabled — no `go.sum` in the ad hoc checkout).
9. **Build hive**: `go build -o hive .` from `external/hive` — hive's main
   binary is at the repo root (`hive.go`), **not** under `cmd/hive` (`cmd/`
   holds `hivechain` and `hiveview` only — confirmed directly in the vendored
   clone at `.claude/repo-references/hive/hive.go` and `cmd/`).
10. **Run simulator**: `./hive --sim "$SIM" --client "$CLIENTS" --sim.parallelism
    "$PARALLELISM" --sim.timelimit "$SIM_TIMELIMIT" --client.checktimelimit=120s
    --loglevel 3` (+ optional `--sim.limit`, `--sim.buildarg`). Exit code is
    captured but never fails the step directly — `hive` returns non-zero for
    many benign-ish per-sim outcomes, so the *real* signal comes from parsing
    results, not the process exit code. `--client.checktimelimit=120s` (not
    hive's 60s default) exists specifically because fukuii is a JVM client
    whose cold-start/JIT warmup can eat most of a 60s budget before the sink
    reaches its sync target.
11. **Tabulate results**: reads `workspace/logs/*.json` (each has a
    `testCases` map; each case has `summaryResult.pass`). A single `jq` query
    extracts total pass/fail plus an optional "gate" subset (tests whose name
    contains `gate_pattern`, minus any matching `gate_exclude`). Falls back to
    grepping the simulator's stdout log against a union of verdict patterns
    (`] PASSED `, `PASSED tests/`, `--- PASS:` — covering pytest-xdist,
    classic-hive, and go-test output shapes) only if zero JSON files parsed.
    Writes a markdown table to `$GITHUB_STEP_SUMMARY`, including a
    `<details>`-collapsed list of the first 20 failed test names when
    `failed > 0`. **This tabulation logic is the single most valuable piece to
    port verbatim into the skill** — it already handles the real-world mess of
    inconsistent simulator output formats.
12. Upload `external/hive/workspace/logs/` + `hive-run.log` as a CI artifact
    (14-day retention).
13. Optionally enforce `pass_threshold` and/or `gate_pattern` (fail the job if
    the gated subset has any failures) as separate steps after tabulation.

### The 13 caller workflows — confirmed suite mapping

All 13 read directly (`hive-consensus.yml`, `hive-engine.yml`,
`hive-rpc-compat.yml`, `hive-sync.yml`, `hive-devp2p.yml`,
`hive-smoke-genesis.yml`, `hive-smoke-network.yml`, `hive-graphql.yml`,
`hive-pyspec.yml`, `hive-consume-engine.yml`, `hive-consume-rlp.yml`, plus the
two standalone ones `hive-osaka.yml` and `hive-prague.yml`). 11 of the 13 call
`_hive-sim.yml`; **`hive-osaka.yml` and `hive-prague.yml` do not** — they carry
their own full inline pipeline (steps 1–4, 6–9 above duplicated, with a
comment explicitly noting "mirrors `hive-prague.yml`/build steps... stay in
sync") plus an extra step neither the reusable workflow nor the other 11
callers have: resolving a *pinned* `execution-spec-tests` fixtures release
(`v5.4.0`) via an authenticated `gh api` call, to avoid the inner
`consume-cache` step's unauthenticated GitHub API rate-limiting (60/hr,
observed to fail with pytest exit code 3 / INTERNAL_ERROR under CI's shared
egress IP).

| Skill suite name | `--sim` | Non-default params | CI file |
|---|---|---|---|
| `consensus` | `ethereum/consensus` | timelimit 60m | `hive-consensus.yml` |
| `engine` | `ethereum/engine` | timelimit 60m | `hive-engine.yml` |
| `rpc-compat` | `ethereum/rpc-compat` | timelimit 30m | `hive-rpc-compat.yml` |
| `sync` | `ethereum/sync` | `clients=fukuii,go-ethereum,nethermind`; `gate_pattern=fukuii`; `gate_exclude='sync go-ethereum from fukuii\|sync fukuii from nethermind'` (two tracked cross-client bugs); `parallelism=1` (JVM JIT-warmup contention flake under `parallelism>1` on constrained CPU — same class of problem this NUC has); timelimit 40m | `hive-sync.yml` |
| `devp2p` | `devp2p` | timelimit 40m | `hive-devp2p.yml` |
| `smoke-genesis` | `smoke/genesis` | timelimit 20m | `hive-smoke-genesis.yml` |
| `smoke-network` | `smoke/network` | timelimit 20m | `hive-smoke-network.yml` |
| `graphql` | `ethereum/graphql` | `parallelism=1` (order-sensitive `pending`/`sendRawTransaction` tests race the mempool otherwise); timelimit 20m | `hive-graphql.yml` |
| `pyspec` | `ethereum/pyspec` | timelimit 60m | `hive-pyspec.yml` |
| `consume-engine` | `ethereum/eels/consume-engine` | `sim_buildarg disable_strict_exception_matching=nimbus-el,fukuii`; timelimit 60m; optional `sim_limit` (e.g. `.*fork_Cancun.*`); **no pinned `fixtures=` URL** — uses whatever default the simulator image ships with | `hive-consume-engine.yml` |
| `consume-rlp` | `ethereum/eels/consume-rlp` | same buildarg; timelimit 60m; optional `sim_limit` | `hive-consume-rlp.yml` |
| `osaka` | `ethereum/eels/consume-engine` | **standalone pipeline** (not `_hive-sim.yml`); `sim.limit '.*fork_Osaka.*'`; pinned `fixtures=<gh-api-resolved v5.4.0 URL>`; `disable_strict_exception_matching=nimbus-el,fukuii`; `--docker.buildoutput`; parallelism 4; timelimit 40m; threshold default `0` | `hive-osaka.yml` |
| `prague` | `ethereum/eels/consume-engine` | **standalone pipeline**; `sim.limit '.*fork_Prague.*'`; same fixtures pinning; threshold default `400` | `hive-prague.yml` |

### `hive/fukuii/` adapter directory (all four files read in full)

- **`Dockerfile`** — `FROM chipprbots/fukuii:latest`; installs `jq`; copies
  `fukuii.sh`, `mapper.jq`, `enode.sh` in; `EXPOSE 8545 8546 8551 30303
  30303/udp`; sets `HIVE_CHECK_LIVE_PORT=8551` (**not** 8545) — comment
  explains this is deliberate: fukuii's Engine API port binds *last* during
  startup, and using 8545 caused 0.7–2s connection-refused fast-fails in
  `ethereum/sync` tests because hive's readiness probe raced ahead of full
  startup.
- **`fukuii.sh`** — the entrypoint. Translates ~15 `HIVE_FORK_*`/`HIVE_*` env
  vars into `-Dfukuii.blockchains.hive.*` JVM system properties targeting a
  dedicated `hive` network profile (vanilla Ethereum config, no ETC-specific
  defaults). Converts hive's geth-format genesis via `mapper.jq`. Branches
  Engine API on vs. mocked-PoW-mining protocol based on whether
  `HIVE_TERMINAL_TOTAL_DIFFICULTY` is set (post-merge sim vs. pre-merge sim) —
  with an explicit comment about *why* the pre-merge `$MAX` sentinel must not
  be passed through as a real TTD (it would wedge fukuii's SNAP sync
  controller waiting for a CL that will never exist in these sims). Also
  contains a documented, deliberate discv4-discovery workaround: geth's UDP
  discovery packets get rejected by scalanet's decoder in hive mode
  (`PacketException: Failed to unpack message: Invalid hash`), so hive's own
  `static-nodes.json`-supplied bootnode is relied on instead of discovery for
  peer-finding in these ephemeral runs. Runs the JVM with `-XX:TieredStopAtLevel=1`
  (cap at C1 JIT) specifically to minimize cold-start time for these short-lived
  sim runs.
- **`mapper.jq`** — pure genesis-format translation (geth JSON → fukuii JSON),
  passthrough of every header field that affects the genesis hash.
- **`enode.sh`** — returns the node's `enode://` URL via `admin_nodeInfo`, with
  a log-grep fallback and an "unknown" last resort.
- **`hive.yaml`** — declares `roles: [eth1, eth1_engine]`.

### `hive/test-local.sh` and `hive/README.md`

Confirmed above: `test-local.sh` is a ~60s adapter-only smoke test, **not** a
hive-simulator runner. `hive/README.md` documents manual hive setup (clone,
`go build .`, copy/symlink the adapter into `clients/fukuii`) and a "Supported
Test Suites" table that is now **stale** relative to the 13 live CI workflows
— it lists only `ethereum/engine`, `ethereum/rpc-compat`, `ethereum/consensus`,
`smoke` as "Target" and marks `devp2p`/`ethereum/sync` as "Future," even though
CI has been running `hive-devp2p.yml` and `hive-sync.yml` for some time. This
skill's suite table above should be treated as the current source of truth;
`hive/README.md` is a candidate for a follow-up doc-freshness fix (noted under
Open Questions).

### A second, already-drifted local hive checkout exists on this machine

`/media/dev/2tb/dev/reference-clients-evm/hive/` is a separate, long-lived
clone of `ethereum/hive` (branches `main`/`upstream`, plus dozens of upstream
feature branches) that already has a `clients/fukuii/` directory populated.
**`diff -rq` against the fukuii repo's own `hive/fukuii/` shows the
`Dockerfile`, `fukuii.sh`, and `hive.yaml` all differ** — this checkout's
adapter copy is stale, exactly the failure mode CI avoids by re-copying the
adapter fresh on every run (step 7 above). Any local skill **must never trust
a previously-copied adapter in a long-lived hive checkout** — it must
re-sync `hive/fukuii/*` from the current fukuii working tree every single run,
whether it clones fresh or reuses an existing hive checkout's `go build`
output. This is exactly the kind of bug a naive "reuse the existing clone to
save time" shortcut would reintroduce.

## Design

### Mode selection: single suite(s), named groups, or "all"

Following Erigon's `hive-test` skill's group-expansion pattern
(`engine` → 5 suites, `all` → everything), but grounded in fukuii's actual
13-suite CI matrix rather than Erigon's Makefile targets:

| Group | Expands to |
|---|---|
| `smoke` | `smoke-genesis`, `smoke-network` |
| `compliance` | `consensus`, `engine`, `rpc-compat`, `devp2p`, `pyspec`, `graphql` |
| `eels` | `consume-engine`, `consume-rlp`, `osaka`, `prague` |
| `all` | every suite in the table above (all 13) |

Individual suite names are also accepted directly, space-separated
(`/fukuii-test-hive engine rpc-compat`), matching the caller-workflow label
names 1:1 so a user can go from a failing CI check name straight to the local
invocation with no translation step.

### No suite parallelism on this hardware (deliberate divergence from Erigon)

Erigon's `hive-test` skill recommends launching **separate hive sessions in
parallel** as background shell commands when suites use different simulators,
to multiply throughput. **This skill explicitly does not adopt that
recommendation.** Per `/media/dev/2tb/dev/claude-global-settings/rules/resource-management.md`,
this is an Intel NUC10i7FNH (6C/12T, 32GB RAM) with a hard rule of "one heavy
task at a time... reserve 2+ cores for VS Code/OS/Claude." A single hive suite
already runs an `sbt assembly` build, a Docker image build, and 2–4 concurrent
client+simulator containers (the fukuii JVM client alone wants meaningful
heap); running multiple suites' Docker fleets concurrently on a 6-core laptop
risks exactly the VS Code/system-freeze failure mode
`background-script-execution.md` already documents for `sbt compile-all`.
Suites given as a group or via `all` run **sequentially**, one hive invocation
at a time, sharing the single `sbt assembly` + Docker base-image build done
once up front (the simulator loop itself, not the build, is what's repeated
per suite).

### Suite-name mapping baked into the skill, not re-derived per run

The table above (suite → `--sim` / non-default flags) is embedded directly in
the skill body as a lookup table (see Phase 2 in the full draft below) rather
than having the skill re-parse the 13 YAML files at invocation time. This
mirrors how the CI side works (one dedicated caller workflow per suite,
static configuration) and avoids YAML-parsing fragility in what is meant to
be a fast, reliable dev-loop tool. The trade-off — the skill's suite table can
drift from the workflows if someone edits `hive-*.yml` without updating
`SKILL.md` — is called out explicitly in the skill body itself (see
"Keeping this skill's suite table in sync" in the draft) and in Open
Questions below.

### Cleanup discipline (matching Erigon's emphasis, adapted to hive's real CLI)

Erigon's two skills converge on the same worry — hive leaves behind stopped
containers, per-client build images, and simulator images that silently
consume disk/RAM across repeated runs — but reach for a generic
`docker system prune -af --volumes` (`erigon-test-hive`) or `./hive --cleanup`
+ `docker image prune -f` (`hive-test`). Reading the actual vendored
`ethereum/hive` binary's flags (`.claude/repo-references/hive/hive.go:73-77`)
confirms hive ships a **purpose-built** cleanup subsystem: `--cleanup`,
`--cleanup.dry-run`, `--cleanup.instance <id>`, `--cleanup.type
{client|simulator|proxy}`, `--cleanup.older-than <duration>`. This skill uses
hive's own scoped cleanup as the primary mechanism (safer than a blanket
`docker system prune` on a dev machine that may have unrelated containers
running) and reserves `docker image prune -f` for the base/adapter images this
skill itself builds (`chipprbots/fukuii:latest` plus the per-run adapter
image), not for the whole Docker daemon. Cleanup runs inside a `trap ... EXIT
INT TERM` in the wrapper script so it fires on success, failure, timeout, or
Ctrl-C alike — adopting Erigon's `erigon-test-hive` trap pattern verbatim,
since that half of its design has nothing Go/Erigon-specific about it.

### Long-running invocation goes through `background-script-execution.md`

Per-suite timelimits range from 20m (`smoke-genesis`) to 90m (job-level
timeout on the CI side; realistic wall time up to ~60m for `pyspec`/
`consume-engine`). Combined with the `sbt assembly` build (itself a multi-minute
compile), a full hive run is squarely in "long-running, prone to
large/noisy output" territory — the exact profile
`.agents/protocols/background-script-execution.md` requires a log-to-file
wrapper + `run_in_background: true` for (the wrapper never `tee`s to its own
stdout — the fukuii `sbt compile-all` host-freeze incident happened precisely
because output was streamed live). The skill therefore:

1. Builds the assembly via the existing `scripts/agent-tooling/sbt-run.sh
   <log-name> assembly` wrapper (already generic across sbt tasks — no new
   wrapper needed for this step).
2. Wraps the hive clone/build/run/tabulate/cleanup sequence in a **new**
   purpose-built script (`scripts/agent-tooling/hive-run.sh`, proposed —
   see Open Questions) following the same shape: header → redirect
   everything to a log file → capture exit code → footer → single `DONE
   log=... exit=N` line to stdout.
3. Both steps are invoked with `run_in_background: true`; the skill does not
   poll — it waits for the harness's completion notification, then reads the
   log tail, per the protocol's explicit anti-pattern table.

### Failure-parsing/reporting format

Ports `_hive-sim.yml`'s `jq` tabulation query verbatim (same JSON schema,
same fallback-to-grep behavior for the three known verdict-string formats).
Output to the user mirrors the CI job-summary shape (a `passed | failed |
total | source` line) plus, when `failed > 0`, the first 20 failing test
names — giving parity with what a developer would see on the PR check page,
so "run it locally" and "read the CI summary" produce visually comparable
output.

## Full draft SKILL.md content

The following is the complete, ready-to-use content for
`.agents/skills/fukuii-test-hive/SKILL.md` (to be symlinked into
`.claude/skills/fukuii-test-hive` per `.agents/protocols/agent-skills.md`
once Phase 7 implementation begins).

````markdown
---
name: fukuii-test-hive
description: >-
  Build a local Fukuii Docker image and run one or more ethereum/hive
  simulator suites against it — the interactive counterpart to fukuii's 13
  CI `hive-*.yml` workflows. Use when asked to "run hive tests", "test
  against hive locally", "run the engine/rpc-compat/sync/consensus/devp2p/
  graphql/pyspec/smoke/consume-engine/consume-rlp/osaka/prague suite", to
  reproduce a failing hive check from a PR, or to validate a change before
  opening a PR. Builds the fukuii assembly, syncs `hive/fukuii/` into an
  ephemeral hive checkout (never trusting a previously-copied adapter),
  builds and runs the simulator(s), tabulates pass/fail from hive's JSON
  results the same way CI does, and always cleans up Docker
  containers/images afterward — including on failure or interrupt. Do NOT
  use this for editing the CI workflows themselves
  (`.github/workflows/hive-*.yml` / `_hive-sim.yml` directly) or as a
  substitute for `hive/test-local.sh`'s <60s adapter boot/RPC smoke check —
  this skill calls that script as an optional fast preflight, it does not
  replace it.
allowed-tools: Bash, Read, Write, Edit, Glob, Grep
---

# fukuii-test-hive

Run one or more `ethereum/hive` simulator suites against a freshly-built local
Fukuii Docker image, on demand, from a working session — without pushing a
branch and waiting for CI.

## When to use

- A `hive-*` check is red on a PR and you want to reproduce it locally with
  full logs before iterating.
- You changed anything under `src/`, `hive/`, `build.sbt`, `project/`, or the
  `_hive-sim.yml`/`hive-*.yml` workflows themselves and want to validate
  before pushing.
- You want a quick adapter sanity check without a full simulator run — use
  Phase 0 alone (delegates to `hive/test-local.sh`).

## Prerequisites

- Docker running locally (confirm: `docker version`).
- Go toolchain available (`go version`) — used to build the `hive` binary
  from source each run, matching CI (hive ships no prebuilt binary).
- For the `osaka` and `prague` suites only: `gh` CLI authenticated
  (`gh auth status`) — these two suites resolve a pinned
  execution-spec-tests fixtures release via `gh api` (see Phase 2b). All
  other 11 suites need no GitHub auth.
- This machine (Intel NUC10i7FNH, 6C/12T, 32GB RAM): run **one suite
  invocation at a time**. Do not launch multiple hive sessions in parallel
  (unlike Erigon's equivalent skill) — a single suite already drives an
  `sbt assembly` build plus 2–4 concurrent Docker containers, which is
  already the practical ceiling for this hardware per
  `resource-management.md`. Suite groups (`smoke`, `compliance`, `eels`,
  `all`) run sequentially, sharing one build.

## Suite reference

| Suite name | `--sim` | Notable non-default params |
|---|---|---|
| `consensus` | `ethereum/consensus` | timelimit 60m |
| `engine` | `ethereum/engine` | timelimit 60m |
| `rpc-compat` | `ethereum/rpc-compat` | timelimit 30m |
| `sync` | `ethereum/sync` | `--client fukuii,go-ethereum,nethermind`; gate on `fukuii`-touching tests only; excludes two tracked cross-client bugs (`sync go-ethereum from fukuii`, `sync fukuii from nethermind`); `--sim.parallelism 1` (JVM JIT-warmup flake under contention); timelimit 40m |
| `devp2p` | `devp2p` | timelimit 40m |
| `smoke-genesis` | `smoke/genesis` | timelimit 20m |
| `smoke-network` | `smoke/network` | timelimit 20m |
| `graphql` | `ethereum/graphql` | `--sim.parallelism 1` (order-sensitive mempool tests); timelimit 20m |
| `pyspec` | `ethereum/pyspec` | timelimit 60m |
| `consume-engine` | `ethereum/eels/consume-engine` | `--sim.buildarg disable_strict_exception_matching=nimbus-el,fukuii`; timelimit 60m; accepts `sim_limit=` override |
| `consume-rlp` | `ethereum/eels/consume-rlp` | same buildarg; timelimit 60m; accepts `sim_limit=` override |
| `osaka` | `ethereum/eels/consume-engine` | `--sim.limit '.*fork_Osaka.*'`; pinned EEST fixtures (see Phase 2b); threshold default 0 |
| `prague` | `ethereum/eels/consume-engine` | `--sim.limit '.*fork_Prague.*'`; pinned EEST fixtures; threshold default 400 |

Groups: `smoke` = smoke-genesis + smoke-network. `compliance` = consensus +
engine + rpc-compat + devp2p + pyspec + graphql. `eels` = consume-engine +
consume-rlp + osaka + prague. `all` = every suite above.

**Keeping this skill's suite table in sync**: this table is a snapshot of the
13 `.github/workflows/hive-*.yml` files as of the date this skill was
written. If a workflow's `--sim`, timelimit, `clients`, `gate_pattern`, or
`gate_exclude` changes, update this table in the same PR — do not let it
drift the way `hive/README.md`'s own suite-status table already has.

## Procedure

### Phase 0 — optional fast preflight (~60s)

Before spending 20–90 minutes on a full simulator run, optionally validate
the adapter itself boots and answers RPC:

```bash
./hive/test-local.sh
```

This builds `fukuii-hive:test` directly from `hive/fukuii/Dockerfile`, starts
one container, and curls `eth_blockNumber`/`eth_chainId`/
`web3_clientVersion`/`engine_exchangeCapabilities` plus `enode.sh`. It does
**not** exercise any real hive simulator — skip straight to Phase 1 if you
already know the adapter is healthy (e.g. you haven't touched `hive/fukuii/`).

### Phase 1 — build the fukuii assembly (background, log-to-file)

```bash
scripts/agent-tooling/sbt-run.sh hive-test-assembly assembly
```

Run with `run_in_background: true` per `.agents/protocols/background-script-execution.md`
— never stream `sbt assembly` output directly to the session. Wait for the
harness's completion notification; then read `.local/logs/hive-test-assembly.log`'s
tail to confirm `EXIT CODE: 0` before continuing. On non-zero, stop here —
route to `wraith` (compile errors) rather than proceeding to a hive run that
will only fail identically.

### Phase 2 — set up the ephemeral hive checkout

**Always clone fresh; never reuse a previous checkout's adapter copy.** A
long-lived hive clone elsewhere on this machine
(`/media/dev/2tb/dev/reference-clients-evm/hive/`) has already been observed
to carry a stale `clients/fukuii/{Dockerfile,fukuii.sh,hive.yaml}` that
differs from the current `hive/fukuii/` working tree — reusing it without
re-syncing would silently test old adapter code.

```bash
WORKDIR=$(mktemp -d /tmp/fukuii-hive-test-XXXXXX)
git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=30 \
  clone --depth=1 --filter=blob:none \
  https://github.com/ethereum/hive.git "$WORKDIR/hive"
```

Retry up to 5× with backoff on failure, matching `_hive-sim.yml`'s clone
step. If a non-`master` hive ref is requested, `fetch --depth=1 origin
<ref>` and `checkout FETCH_HEAD` inside `$WORKDIR/hive`.

**Tag the base image** (mirrors `_hive-sim.yml` step 5 exactly — do not
assume `chipprbots/fukuii:latest` already exists):

```bash
mkdir -p target/quick-docker
cp target/scala-3.*/fukuii-assembly-*.jar target/quick-docker/
cat > target/quick-docker/Dockerfile <<'DOCKER'
FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends jq curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir -p /app/fukuii/lib /app/data /app/hive-conf
COPY fukuii-assembly-*.jar /app/fukuii/lib/fukuii-assembly.jar
ENTRYPOINT ["java", "-jar", "/app/fukuii/lib/fukuii-assembly.jar"]
DOCKER
docker build -t chipprbots/fukuii:latest target/quick-docker/
```

**Sync the adapter** (fresh copy, every run, no exceptions):

```bash
mkdir -p "$WORKDIR/hive/clients/fukuii"
cp hive/fukuii/Dockerfile "$WORKDIR/hive/clients/fukuii/"
cp hive/fukuii/fukuii.sh  "$WORKDIR/hive/clients/fukuii/"
cp hive/fukuii/mapper.jq  "$WORKDIR/hive/clients/fukuii/"
cp hive/fukuii/enode.sh   "$WORKDIR/hive/clients/fukuii/"
cp hive/fukuii/hive.yaml  "$WORKDIR/hive/clients/fukuii/"
cp target/scala-3.*/fukuii-assembly-*.jar "$WORKDIR/hive/clients/fukuii/"
```

**Build the hive binary** — the main package is at the repo root
(`hive.go`), not `cmd/hive` (that path doesn't exist; `cmd/` holds
`hivechain` and `hiveview`):

```bash
(cd "$WORKDIR/hive" && go build -o hive .)
```

### Phase 2b — EEST fixtures resolution (osaka / prague only)

Only for the `osaka` and `prague` suites. Resolve a pinned
execution-spec-tests release the same way `hive-osaka.yml`/`hive-prague.yml`
do — an authenticated `gh api` call sidesteps the inner `consume-cache`
step's unauthenticated GitHub rate limit:

```bash
FIXTURES_TAG='v5.4.0'
FIXTURES_URL="https://github.com/ethereum/execution-spec-tests/releases/download/${FIXTURES_TAG}/fixtures_stable.tar.gz"
gh api -H 'Accept: application/vnd.github+json' \
  "repos/ethereum/execution-spec-tests/releases/tags/${FIXTURES_TAG}" \
  --jq '.assets[] | select(.name == "fixtures_stable.tar.gz") | .browser_download_url' \
  | grep -qx "$FIXTURES_URL"
```

If the pinned tag has moved on since this skill was written, check
`hive-osaka.yml`/`hive-prague.yml` for the current tag rather than guessing.

### Phase 3 — run the simulator(s) (background, log-to-file)

For each requested suite, invoke hive with the flags from the Suite
reference table above, e.g. for `rpc-compat`:

```bash
(cd "$WORKDIR/hive" && ./hive \
  --sim ethereum/rpc-compat \
  --client fukuii \
  --sim.parallelism 4 \
  --sim.timelimit 30m \
  --client.checktimelimit=120s \
  --loglevel 3 2>&1 | tee hive-run.log)
```

(`--client.checktimelimit=120s`, not hive's 60s default — fukuii's JVM
cold-start/JIT warmup needs the extra budget; this is load-bearing, taken
directly from `_hive-sim.yml`, do not shorten it.) Sequence multiple
requested suites one after another — never concurrently (see Prerequisites).
Wrap the whole clone → build → run loop in a single backgrounded script per
`.agents/protocols/background-script-execution.md` (proposed:
`scripts/agent-tooling/hive-run.sh`, following `sbt-run.sh`'s exact shape —
header, redirect-only, capture exit code, footer, one `DONE log=... exit=N`
line). Do not poll; wait for the completion notification.

### Phase 4 — tabulate results

Port `_hive-sim.yml`'s tabulation `jq` query verbatim against
`$WORKDIR/hive/workspace/logs/*.json` (schema: `.testCases | to_entries[]
| .value.summaryResult.pass`). Fall back to grepping
`$WORKDIR/hive/hive-run.log` (or the newest `workspace/logs/*-simulator-*.log`)
against `(] PASSED |PASSED tests/|--- PASS:)` / `(] FAILED |FAILED
tests/|--- FAIL:)` only if zero JSON files parsed. Report:

```
Hive · <suite> (<sim>)
passed=<N> failed=<N> total=<N> source=<N JSON result(s) | grep fallback | none>
```

If `failed > 0`, list up to the first 20 failing test names (same
`grep -E` patterns, `head -20`). For `sync`, apply the same gate-pattern
logic as CI — report the full failure list, but call out separately which
failures fall inside the `fukuii`-gate subset vs. the two already-tracked,
excluded cross-client bugs, so a genuinely new regression isn't buried
alongside known noise.

### Phase 5 — cleanup (always, even on failure/interrupt)

Wrap the whole run in a `trap ... EXIT INT TERM` so this fires regardless of
outcome:

```bash
cleanup() {
  (cd "$WORKDIR/hive" && ./hive --cleanup --cleanup.older-than 0s) 2>/dev/null || true
  docker image prune -f
  rm -rf "$WORKDIR"
}
trap cleanup EXIT INT TERM
```

Prefer hive's own scoped `--cleanup` (removes hive-launched client/simulator/
proxy containers specifically — confirmed flags: `--cleanup`,
`--cleanup.dry-run`, `--cleanup.instance`, `--cleanup.type`,
`--cleanup.older-than`, from `hive.go`) over a blanket `docker system prune
-af --volumes` — this dev machine may have unrelated containers running that
a blanket prune would also remove. Use `--cleanup.dry-run` first if unsure
what would be removed. `docker image prune -f` (dangling images only) mops up
the `chipprbots/fukuii:latest` base-image layers and the per-suite adapter
build — not the whole Docker image cache.

## Output contract

Report, per suite run:
- **Suite name and `--sim`** actually invoked.
- **passed / failed / total**, and the source of that count (JSON vs. grep
  fallback — flag grep-fallback results as lower-confidence).
- **First N failing test names**, if any, with the `sync`-suite gate/exclude
  caveat above where relevant.
- **Log location** (`$WORKDIR` before cleanup, or wherever the backgrounded
  wrapper's log file landed) so a failure can be drilled into after the run
  completes but before `$WORKDIR` is removed — read the log *before*
  triggering cleanup if deep investigation is needed.
- **Cleanup confirmation** — that `hive --cleanup` and `docker image prune -f`
  both ran, or why they didn't (e.g. Docker wasn't running to begin with).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Sink sync tests fail near the timeout boundary | JVM cold-start/JIT warmup eating the check budget | Confirm `--client.checktimelimit=120s` is set, not hive's 60s default |
| `sync` suite: geth↔fukuii or fukuii↔nethermind fails | Two tracked, already-excluded cross-client bugs | Expected — not a new regression unless a *different* test pair fails |
| `graphql` suite flakes on `pending`/`sendRawTransaction` | Order-sensitive mempool tests raced under `parallelism>1` | Confirm `--sim.parallelism 1` is set for this suite |
| `consume-engine`/`consume-rlp` fixture fetch fails with rate-limit / exit 3 | Unauthenticated `api.github.com` call inside the simulator's `consume-cache` step | Only `osaka`/`prague` pin fixtures via authenticated `gh api`; other EELS suites currently accept this risk — retry, or add fixtures pinning here too (see Open Questions) |
| `external/hive`-style path collides with fukuii's own `hive/` adapter dir | Cloned hive into `./hive` instead of a separate path | Always clone into a `mktemp -d` workdir, never `./hive` |
| Stale adapter behavior despite editing `hive/fukuii/*` | Reused a long-lived hive checkout without re-syncing the adapter | Always re-copy `hive/fukuii/*` fresh into the target checkout's `clients/fukuii/`, every run |
| Docker containers/images pile up after repeated runs | Cleanup skipped (e.g. session killed mid-run) | `./hive --cleanup` (from any hive checkout with the binary built) + `docker image prune -f`; use `--cleanup.dry-run` first if unsure |
````

## Open questions / needs-design items

1. **`scripts/agent-tooling/hive-run.sh` does not exist yet.** This design
   references it as the proposed background-execution wrapper for the
   clone/build/run/tabulate/cleanup sequence (Phase 3), following
   `sbt-run.sh`'s shape per `background-script-execution.md`. Writing this
   script is part of Phase 7 implementation, not covered by this design doc.
2. **No documented local Docker/Go prerequisite check exists anywhere in
   fukuii today** — `hive/README.md` mentions "Go 1.21+" and "Docker" as
   prerequisites in prose but there is no automated preflight check. This
   design's Prerequisites section states minimum expectations
   (`docker version`, `go version`, `gh auth status` for two suites) but a
   real implementation should decide whether the skill verifies these
   automatically and fails fast with a clear message, or just documents them
   for the user to self-check. On this development machine specifically,
   Docker 29.6.1 and Go 1.26.1 are both already installed and confirmed
   working, so the *design* doesn't need to solve a missing-tool problem
   here — but the skill will run on other machines too.
3. **`hive/README.md`'s "Supported Test Suites" table is stale** — it marks
   `devp2p` and `ethereum/sync` as "Future" despite both having live,
   passing-or-gated CI workflows (`hive-devp2p.yml`, `hive-sync.yml`).
   Whether to fix this doc as part of the same PR that adds this skill, or
   file it as a separate small cleanup, is a scoping call for whoever
   implements Phase 7.
4. **Fixtures pinning is inconsistent across the EELS-family suites** —
   `osaka`/`prague` pin `v5.4.0` via authenticated `gh api`; the plain
   `consume-engine`/`consume-rlp` suites (both CI-side and in this skill
   design) do not pin anything and rely on the simulator's own default. This
   asymmetry is CI's current behavior, faithfully reproduced here, but it's
   worth flagging as a possible future consistency improvement (would need a
   decision, and possibly a CI change, before the skill's design should
   diverge from what CI actually does).
5. **No decision made here on whether the skill should support a
   `--reuse-clone <path>` fast path** pointing at an existing hive checkout
   (e.g. `/media/dev/2tb/dev/reference-clients-evm/hive/`) to skip the ~depth-1
   clone step on repeat runs, provided the adapter re-sync (Phase 2) still
   always happens unconditionally. The design above defaults to a fresh
   `mktemp -d` clone every time for correctness and simplicity; adding a
   reuse fast path is a reasonable follow-up once the straightforward version
   is working and its wall-clock cost is measured.
6. **Threshold/gate enforcement semantics for local runs** — CI has
   `pass_threshold` and `gate_pattern`/`gate_exclude` enforcement that fails
   the *build*. Locally, failing the whole skill invocation on a threshold
   miss may be too strict for exploratory debugging (the point of running
   locally is often to see *why* something fails, not to get gated off from
   seeing results). This design reports thresholds/gates as informational
   context (Phase 4) rather than as a hard local failure; confirm that's the
   right default before implementation, versus an opt-in `--strict` flag that
   mirrors CI's fail-the-build behavior exactly.
