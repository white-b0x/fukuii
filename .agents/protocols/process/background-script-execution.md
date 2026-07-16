# Background Script Execution Protocol

Long-running, noisy, or previously-freeze-prone commands must run through a
log-to-file wrapper script invoked in the calling tool's **background** mode —
never directly in the foreground, and never handed to the operator as a
copy-paste-and-wait relay.

Used by: ALL agents that run `sbt`, disk/DB tooling, or any other command with
unpredictable or high-volume output
Referenced by: `fukuii/CLAUDE.md` ("Shared agent protocols" table)

---

## The incident this protocol exists to prevent

During an IP-CL-A cleanup sprint (Scala 3 opaque-type refactor), running
`sbt compile-all` directly through an agent's own Bash tool — in the
foreground, output streaming live — froze the entire host machine (`uptime`
reset to ~2 minutes after recovery: a full system freeze, not a session hang).
Working hypothesis: large cascading compile-error output, captured/relayed
live by the calling tool, produces enough memory/IO pressure to wedge both the
agent session and the host OS itself.

This is a machine-level failure mode, not merely a slow command. Treat it as a
hard constraint, not a performance nitpick.

---

## The rule

> Any command that is (a) long-running, (b) prone to large/noisy output, or
> (c) has previously caused a stall/freeze/timeout when run directly — must be
> wrapped in a script that writes **all** output to a log file and prints
> **nothing** to its own stdout except a one-line completion summary. That
> wrapper is then invoked with the calling tool's background-execution option
> (e.g. `run_in_background: true`), and the harness's own completion
> notification replaces both manual polling and human relay.

The mechanism that makes this safe is not "it runs faster" — it's that
**output is never streamed live** to the thing capturing it. A wrapper that
still `tee`s to stdout while backgrounded has not actually eliminated the risk;
it has only hidden it until the log volume is large enough to reproduce the
same pressure.

---

## What qualifies as a candidate

- Any command with a known or suspected multi-minute runtime.
- Any command whose failure mode produces cascading, high-volume, or
  unbounded diagnostic output (compiler errors, verbose test runners, chatty
  network tools).
- Any command that has *already* caused a stall, timeout, or freeze when run
  directly — treat one incident as sufficient evidence, don't wait for a
  second.

## What does not qualify

- Commands that reliably finish in single-digit seconds with small, bounded
  output. Wrapping these adds indirection (a log file to go find) with no
  safety benefit. If you evaluate a command and decide it doesn't need this
  treatment, say so explicitly — a command considered-and-rejected is a
  useful data point; a command silently skipped looks identical to one nobody
  thought about.
- Commands that only make sense run interactively at an operator's own
  terminal (they want to watch progress, or may need to answer a prompt).
  This protocol is for agent-invoked, non-interactive commands.

---

## The wrapper script shape

Follow `scripts/agent-tooling/sbt-run.sh` as the reference implementation:

1. `set -uo pipefail` (not `-e` if you need to capture and act on a non-zero
   exit code yourself — capture it explicitly instead).
2. Resolve `SCRIPT_DIR` / `REPO_ROOT` the same way every other script in
   `scripts/agent-tooling/` does (`cd "$(dirname "${BASH_SOURCE[0]}")" && pwd`, then
   `../..`).
3. Write a small header (`started <timestamp>`, the exact command) directly
   into the log file — not stdout.
4. Redirect the real command's stdout+stderr straight into the log file with
   `>> "$LOG_FILE" 2>&1`. Nothing about the command's own output ever touches
   the wrapper's stdout.
5. Capture the real exit code (`$?` immediately after the command).
6. Append a footer to the log file (`finished <timestamp>`, `EXIT CODE: N`).
7. Print exactly **one line** to stdout: `DONE log=<path> exit=<N>`.
8. `exit` with the real command's exit code, so the calling tool's own
   success/failure signal (and the harness notification's status) is accurate.

Do not build a generic "any command" runner. One script per real heavy
command family, matching this shape, is easier to read at 2am than one
parameterized abstraction — see `scripts/agent-tooling/sbt-run.sh` (generalizes
across *sbt tasks* specifically, since sbt tasks share identical invocation
semantics) as the right level of generalization, not a project-wide task
runner.

---

## How to invoke

1. Call the wrapper through the calling tool's background-execution option
   (e.g. Bash tool `run_in_background: true`).
2. Do not poll the log file or the task status in a loop. The harness delivers
   a completion notification the instant the process exits — this is the
   entire point of the pattern. A wakeup/sleep loop to "check in periodically"
   reintroduces the polling this protocol was built to remove.
3. On notification, read the log file's tail first (grep for `ERROR`, the
   footer's `EXIT CODE`, or whatever the command's failure signature is)
   before deciding next steps.
4. If the exit code is non-zero, the log file is the full diagnostic record —
   read enough of it to root-cause before acting, per the project's normal
   "failure is information" discipline.

---

## Subagents: background-then-poll

The "don't poll — wait for the completion notification" rule above assumes the calling
context is re-invoked when the backgrounded task finishes. That holds for the main
orchestrator loop; it does **not** hold for a subagent, which is never re-invoked after
it yields. A subagent that backgrounds a long `sbt` run and then ends its turn orphans
the result — the run finishes into a log nobody reads (confirmed twice on `eye`'s B1
validation pass, forcing the orchestrator to read the logs manually and re-invoke).

A subagent whose report depends on a backgrounded task's outcome must poll that task to
completion **within its own turn**, before reporting — never yield while the run is
still in flight. Prefer the `Monitor` tool, when the agent holds that grant, to block on
the wrapper's one-line `DONE log=... exit=N` completion marker; otherwise poll via
repeated single-command Bash calls against the log file (plain `&&` chains, not a shell
`while`/`until` construct — see `compound-command-scratch.md` for why a loop needs a
`.local/scratch/` script, which is unavailable to any read-only, no-`Write` agent).

---

## Cleanup — verify teardown, don't just trust it happened

Starting a long-lived resource (a Docker container, a JVM node process, a Hive simulator run,
an ephemeral datadir + P2P/RPC listener) creates an obligation to tear it down — this is a
separate discipline from backgrounding the *command that starts it*, and just as load-bearing.
An orphaned container or stalled JVM process is exactly the kind of resource drain
`resource-management.md`'s "one heavy task at a time" budget assumes doesn't exist.

**Rules:**

1. **Every wrapper script that starts a long-lived resource must tear it down itself**, on
   every exit path — success, failure, or interrupt (`trap ... EXIT` in bash, not just a
   happy-path cleanup step that a mid-script failure skips). `hive-run.sh`'s clone → build →
   run-simulator(s) → tabulate → **cleanup** sequence and `fukuii-ephemeral`'s teardown are the
   two concrete instances of this today — cleanup is part of the script's own contract, not an
   operator afterthought.
2. **Don't assume a script's own cleanup step ran correctly — verify it.** After a background
   task that started a container/process/listener completes, check for real: `docker ps`
   (no orphaned containers matching this run), `ps aux | grep <process>` (no stalled JVM/hive
   process still resident), `lsof -i :<port>` (the port is actually free again). This is a
   cheap, fast check — do it before reporting the task done, not only when something looks
   wrong.
3. **`fukuii-ephemeral`'s "leftover detection" step is a safety net, not the primary
   mechanism** — it exists to catch a PRIOR run's abandoned instance, not to excuse the
   CURRENT run from cleaning up after itself. Don't rely on the next invocation's leftover
   scan to eventually clean up what this invocation should have torn down directly.
4. **This applies to agent-invoked test/build tooling generally**, not just Hive — any
   ephemeral node, container, or background process an agent starts as part of a task is that
   agent's responsibility to stop, not something to leave running "in case it's needed again."

## Anti-pattern table

| Don't | Do instead |
|-------|-----------|
| Run a long/noisy command directly via the foreground Bash tool "because it's just this once" | Wrap it, background it — one incident of freeze/stall is enough evidence to always wrap that command family |
| Wrap a command in a script that still `tee`s to stdout, then background it | Redirect stdout+stderr straight to the log file only; stdout gets exactly one completion line |
| Hand the operator a copy-pasteable command and wait for them to say "done" | Background the wrapper yourself and let the harness notification replace the human relay |
| Poll the log file or task status on a timer "just to check progress" | Wait for the completion notification; read the log once, on notification |
| Build one generic "run anything in the background" framework | One script per real heavy command family, in the same shape as `sbt-run.sh` |
| Script a command "just in case" when it already finishes in seconds with small output | Evaluate it, and if it doesn't qualify, say so explicitly instead of silently adding indirection |
| Start a container/process/ephemeral node and assume the script's own cleanup step handled it | Verify teardown for real (`docker ps`, `ps aux`, `lsof -i`) before reporting the task done |
| Rely on the NEXT run's leftover-detection scan to eventually clean up THIS run's resources | Every wrapper tears down what it started, on every exit path (success/failure/interrupt) |

---

## Existing wrappers

| Script | Covers | Notes |
|--------|--------|-------|
| `scripts/agent-tooling/sbt-run.sh` | Any `sbt` task or space-separated sequence of tasks (`compile-all`, `scalafmtAll`, `formatAll`, `pp`, `testEssential`, `testStandard`, `testComprehensive`, `"IntegrationTest / test"`, etc.) | Already generic across sbt tasks — pass the task name(s) as arguments; no per-task wrapper needed. Validated live for `scalafmtAll`, `compile-all`, and (2026-07-02) `"IntegrationTest / compile"` — the multi-word slash-syntax argument form. Hardened 2026-07-16 against the two false-green shapes below — do not treat its exit code as sbt's raw exit code without reading this note. Always use module-scoped `<mod>/<task>` syntax (`evm/clean`, `evm/compile`), never a bare `project <id>` selector chained with further tasks — see incident 2 below. |

### Stale-detached-sbt-server false-green (2026-07-16, incident 1)

During L3 EVM validation, a long-lived detached sbt server left over from a prior
session answered `clean ; compile ; Test/compile` requests with a fast `[success]`
while doing **no real recompilation** — nothing under `target/` changed across two
full cycles. Root cause: sbt's persistent server does not reload
`build.sbt`/`project/*.scala`/`project/build.properties` changes on its own: a
server that has been sitting since before the last build-definition edit is
running a stale settings graph, and can answer requests without them meaning
what they look like they mean. (A tempting but **wrong** diagnostic during this
incident was `show <mod>/Compile/compile`'s printed `Analysis: N Scala sources`
summary — it does not reliably report the target module's own scope even
against a freshly-started server, so don't use it to judge staleness.)

`sbt-run.sh` closes this with a pre-run guard: if the server registered in
`project/target/active.json` started before the newest build-definition file's
mtime, it is killed so the sbt invocation that follows starts fresh and reloads.

### `project <id>` lead-form swallows chained tasks (2026-07-16, incident 2)

A second, distinct hollow-success shape slipped past incident 1's guard during
L3 P4 validation: `sbt-run.sh <name> "project evm" "clean" "compile" "Test/compile"`
— an sbt `project <id>` selector followed by chained tasks in the same
semicolon-joined command string — silently runs **only** the project switch.
`clean`/`compile`/`Test/compile` never execute, yet sbt still exits 0. Confirmed
by reproducing directly against sbt: the log shows exactly
`[info] set current project to fukuii-evm` then `[success]`, with no compiling
evidence and no compile-output change.

The first cut of the post-run freshness check (incident 1) did **not** catch
this, because it scanned the whole `target/` tree for "did anything change."
`target/` contains files — `target/global-logging/sbt-global-log*.log`, and
every project's own `streams`/`update`/`meta` dirs — that get touched by **any**
sbt invocation, including a bare `project X` switch that does nothing else.
Confirmed empirically: `project evm` alone touches `target/global-logging` but
never touches the real compile-output paths. That produced a false "something
changed" reading that masked the hollow run.

`sbt-run.sh` now closes both gaps:

1. **Pre-run, reject outright (no sbt invocation at all):** the joined command
   string is split on `;`; if any token except the last is a `project <id>`
   selector, the run is rejected before sbt ever starts (exit **3**, with a
   message pointing at the safe `<mod>/<task>` form). This shape is unsafe by
   construction — module-scoped syntax never needs a project switch and does
   not exhibit this failure mode, so there is no legitimate reason to allow it
   through `sbt-run.sh`.
2. **Post-run, correctly scoped freshness check:** when the task list includes
   a `clean` task (which always invalidates cached compile state — a
   legitimate no-op is impossible after a real clean) and sbt exited 0, the
   script verifies the **real compile-output paths** —
   `target/out/*/scala-*/*/{classes,test-classes,zinc,test-zinc}` (the sbt 2.x
   content-addressed-store layout; per-module `modules/*/target/scala-*/...` is
   checked too for back-compat) — actually advanced. It never scans the whole
   `target/` tree. If the compile-output paths didn't advance, the exit code is
   overridden to **97** with a loud banner in the log, instead of passing
   through sbt's own (wrong) 0.

Trust `sbt-run.sh`'s gate results on this basis: exit 0 with `clean` in the task
list means real compilation was independently verified to have happened at the
correct output path, not just that sbt itself said so, and any `project <id>`
lead-form is refused before it can run at all.

**`fukuii-test` retired (2026-07-02):** it was fully superseded by `sbt-run.sh` — every
sbt task it could invoke (`testEssential`, `testStandard`, `testOnly ...`), `sbt-run.sh`
invokes too, more safely (no `tee`-to-stdout, which was the same live-streaming risk this
protocol exists to eliminate). It also existed as two byte-identical, silently-driftable
copies (`scripts/fukuii-test` and `.local/scripts/fukuii-test`). Both copies were deleted;
all agent-facing references now point at `sbt-run.sh`. For a targeted single-spec run
(seconds, small output), just call `sbt "testOnly *Spec*"` directly — no wrapper needed
at that scale.

## Evaluated and rejected (2026-07-02 audit)

Recorded here so a future pass doesn't re-litigate the same ground:

- **RocksDB export/import/integrity ops** (`admin_exportChain`,
  `admin_importChain`, `du -sh rocksdb/`) — these are operator actions against
  a *running node* (JSON-RPC calls or single fast filesystem commands), not
  commands an agent invokes directly from this repo's Bash tool during a
  build/test workflow. No live node runs inside a coding-agent session in this
  project. Revisit if that changes.
- **Straggler-audit greps** (`.local/docs/research-july/straggler-audit-results.md`)
  — single-pattern greps scoped to `domain/` or similar, sub-second in
  practice. Not heavy enough to warrant wrapping.
- **Node-operational scripts** (`fukuii-inject-loop`, `fukuii-run-tick`,
  `snap-monitor.sh`, `refresh-bootnodes-dns.sh`, `update-bootnodes.sh`) — these
  operate live running nodes / external network calls, not this repo's
  compile/test workflow. Out of scope for this protocol's audit pass.
