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
| `scripts/agent-tooling/sbt-run.sh` | Any `sbt` task or space-separated sequence of tasks (`compile-all`, `scalafmtAll`, `formatAll`, `pp`, `testEssential`, `testStandard`, `testComprehensive`, `"IntegrationTest / test"`, etc.) | Already generic across sbt tasks — pass the task name(s) as arguments; no per-task wrapper needed. Validated live for `scalafmtAll`, `compile-all`, and (2026-07-02) `"IntegrationTest / compile"` — the multi-word slash-syntax argument form. |

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
