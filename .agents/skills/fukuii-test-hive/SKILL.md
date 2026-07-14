---
name: fukuii-test-hive
description: >-
  Build a local Fukuii Docker image and run one or more ethereum/hive
  simulator suites against it — the interactive counterpart to fukuii's 12
  CI `hive-*.yml` workflows. Use when asked to "run hive tests", "test
  against hive locally", "run the engine/rpc-compat/sync/consensus/devp2p/
  graphql/smoke/consume-engine/consume-rlp/osaka/prague suite", to
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
| `consume-engine` | `ethereum/eels/consume-engine` | `--sim.buildarg disable_strict_exception_matching=nimbus-el,fukuii`; timelimit 60m; accepts `sim_limit=` override |
| `consume-rlp` | `ethereum/eels/consume-rlp` | same buildarg; timelimit 60m; accepts `sim_limit=` override |
| `osaka` | `ethereum/eels/consume-engine` | `--sim.limit '.*fork_Osaka.*'`; pinned EEST fixtures (see Phase 2b); threshold default 0 |
| `prague` | `ethereum/eels/consume-engine` | `--sim.limit '.*fork_Prague.*'`; pinned EEST fixtures; threshold default 400 |

Groups: `smoke` = smoke-genesis + smoke-network. `compliance` = consensus +
engine + rpc-compat + devp2p + graphql. `eels` = consume-engine +
consume-rlp + osaka + prague. `all` = every suite above.

`ethereum/pyspec` (formerly a 13th suite, `hive-pyspec.yml`) was removed —
upstream Hive deleted the `ethereum/pyspec` simulator directory
(`7b0ce986`), and the workflow had been silently reporting a false-green
0/0/0 "success" ever since. `consume-engine`/`consume-rlp` above are its
already-covered modern replacements.

**Keeping this skill's suite table in sync**: this table is a snapshot of the
12 `.github/workflows/hive-*.yml` files as of the date this skill was
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
