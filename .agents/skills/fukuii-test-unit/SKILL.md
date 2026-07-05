---
name: fukuii-test-unit
description: >-
  Run Fukuii's fast unit-test tier — `sbt testEssential` (background, via
  `sbt-run.sh`) for the whole tier, or `sbt "testOnly *Foo*"` directly for a single
  test class. Use when asked to "run the tests", "run the unit tests", "run
  testEssential", or to verify a single spec after a logic change. This is the
  pre-push fast gate, not the full compliance suite (see `fukuii-test-all` for
  `testStandard`/`testComprehensive`).
disable-model-invocation: true
user-invokable: true
argument-hint: "testEssential|ClassName"
---

# Fukuii unit tests

## When to use

- After a phase that changes logic (not compile-only phases) — per
  `.agents/protocols/testing/testing-protocol.md`'s per-phase test cadence.
- Before pushing, as the fast pre-push gate — check `.claude/sprints/QUEUE.md`'s
  status block for the current test count/duration; it drifts over time and this
  skill doesn't hardcode a number.

## Procedure

### Whole tier — background via `sbt-run.sh`

```bash
scripts/agent-tooling/sbt-run.sh <log-name> testEssential
```

Invoke with `run_in_background: true` per
`.agents/protocols/process/background-script-execution.md` — this is a full-suite
run with real duration and volume. Do not poll; wait for the completion
notification, then read `.local/logs/<log-name>.log`'s tail for the pass/fail
summary and `EXIT CODE`.

### Single test class — direct, no wrapper needed

```bash
sbt "testOnly *Foo*"
```

Small enough (seconds, bounded output) to skip the background wrapper entirely —
per `background-script-execution.md`'s own "what does not qualify" list. Run this
directly in the foreground.

## On failure

- Whole-tier failure: read the log for which spec(s) failed; don't re-run blindly.
  If the failure looks pre-existing/unrelated to the current change, check
  `.claude/sprints/QUEUE.md`'s Chase & Deferred Items section before assuming it's
  new.
- Single-class failure: read the actual assertion output before changing anything —
  "question the tests, don't silently fix them" (see `fukuii-implement-eip`'s
  carried-over ethic) applies here too, especially for consensus-adjacent specs.

## Verify (self-test)

`testEssential` and `testOnly` are both pre-existing sbt tasks already exercised via
`sbt-run.sh` and directly — this skill wraps existing, validated invocations rather
than introducing new mechanics.
