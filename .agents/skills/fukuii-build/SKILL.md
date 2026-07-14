---
name: fukuii-build
description: >-
  Build Fukuii — either a full compile (`sbt compile-all`, the default) or a runnable
  fat JAR (`sbt assembly`) — via the backgrounded `sbt-run.sh` wrapper, then verify
  success from the log rather than assuming. Use when asked to "build fukuii", "compile
  everything", "make an assembly jar", or as the first step before `fukuii-ephemeral`
  or `fukuii-test-hive` (both need a fresh assembly). Do NOT use for running tests (see
  `fukuii-test-unit`/`fukuii-test-all`) or for formatting-only passes (`sbt scalafmtAll`
  directly — small enough to skip the wrapper).
disable-model-invocation: true
user-invokable: true
argument-hint: "compile|assembly"
---

# Fukuii build

Wraps fukuii's two build tasks — full compile and runnable-JAR assembly — through the
existing background-execution wrapper. No new script needed: `sbt-run.sh` already
generalizes across any sbt task, `assembly` included.

## When to use

- **`compile`** (default): after a set of file changes, to confirm the whole project
  (main + test sources across all modules) still compiles. Prefer this over `sbt
  compile` alone unless doing a core-domain-type sweep (see
  `.agents/protocols/testing/testing-protocol.md`'s "core domain type sweeps" note).
- **`assembly`**: before `fukuii-ephemeral` or `fukuii-test-hive`, both of which need a
  runnable `fukuii-assembly-*.jar`.

## Procedure

### 1. Run the build (background, log-to-file)

```bash
scripts/agent-tooling/sbt-run.sh <log-name> compile-all
# or, for a runnable jar:
scripts/agent-tooling/sbt-run.sh <log-name> assembly
```

Invoke with `run_in_background: true` per
`.agents/protocols/process/background-script-execution.md` — never run `sbt
compile-all`/`sbt assembly` directly through the foreground Bash tool (this has
previously frozen the host machine; see that protocol's incident writeup). Pick a
`<log-name>` that identifies the calling context (e.g. `fukuii-build-assembly`), since
logs accumulate under `.local/logs/`.

Do not poll. Wait for the harness's completion notification.

### 2. Verify from the log, don't assume

On notification, read the log file's tail:

```bash
tail -20 .local/logs/<log-name>.log
```

Confirm `EXIT CODE: 0` in the footer. The wrapper's own stdout line
(`DONE log=... exit=N`) already carries the exit code — cross-check both, don't trust
only one.

### 3. On failure

Non-zero exit means a compile error (or, for `assembly`, a merge-strategy conflict).
Route to `wraith` for Scala 3 compile-error triage — don't attempt further build
variants (e.g. re-running `assembly` after a `compile-all` failure) until the
underlying error is fixed, since the same error will simply resurface.

### 4. Where the assembly jar lands

`target/scala-3.*/fukuii-assembly-<version>.jar` — confirmed via `build.sbt`'s
`(assembly / assemblyJarName)` setting. `fukuii-ephemeral` and `fukuii-test-hive` both
glob this path (`ls target/scala-3.*/fukuii-assembly-*.jar`) rather than hardcoding a
version.

## Verify (self-test)

`sbt-run.sh compile-all` and `sbt-run.sh assembly` are both pre-existing, already-used
sbt tasks — this skill adds no new script surface, so there's nothing new to smoke-test
beyond invoking the wrapper once and confirming the log's `EXIT CODE: 0` shows up, which
is Step 2 above.
