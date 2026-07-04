---
name: fukuii-pr-preflight
description: >-
  Pre-flight a branch against everything CI actually gates before opening or updating
  a PR against chippr-robotics/fukuii — scalafmt, compile, Tier 1 tests, and (if
  docs/** changed) the mkdocs --strict build plus a doc-link check. Use when asked to
  "preflight this PR", "check CI will pass", "run the pr checklist", or before pushing
  a branch that's about to open a PR. Read-only; safe to run anytime. Reports
  PASS/FAIL/WARN per category and labels any known-upstream-bug WARN as such rather
  than something to chase locally.
disable-model-invocation: true
user-invokable: true
model: haiku
argument-hint: "base-ref"
---

# Fukuii PR preflight

This skill does **not** modify any file. It runs the same gates CI runs and reports
where the branch stands before you push.

## When to use
- Before opening a PR against `chippr-robotics/fukuii` (`staging`/`develop`/`main`)
- Before pushing new commits to an already-open PR, to confirm a fix actually landed
- When asked "will this pass CI?" or "check the PR checklist"

## Procedure

### 1. Run the collector script (background)
Let `pr-preflight.sh` resolve the base ref itself — it checks, in order, an explicit
override, a remote named `upstream` with a `staging` branch, `origin/staging` when
`origin` itself is `chippr-robotics/fukuii`, then a local `staging` branch, and fails
loudly rather than guessing (see
`.agents/protocols/tooling/pr-preflight-checklist.md`). Only pass an explicit
`<base-ref>` if the user names one.

```bash
scripts/agent-tooling/pr-preflight.sh <log-name> [<base-ref>]
```

Invoke with `run_in_background: true` — this runs `sbt compile-all`/`testEssential`,
which take real time. Do not poll; you'll be notified on completion.

### 2. Read the log once notified
The script's final `DONE ...` line and the log's summary table give a PASS/FAIL/WARN
per category: `format`, `compile`, `tests`, `docs-build`, `doc-links`.

### 3. Report

For each category:
- **PASS** — say so plainly, no further action.
- **FAIL** — quote the exact local fix command from
  `pr-preflight-checklist.md`'s "Local repro commands" section (`sbt scalafmtAll` for
  format, the relevant `mkdocs`/link fix for docs) and point at the specific log lines
  that show the failure.
- **WARN** — check whether it's a **known upstream issue** (the lychee CLI arg break
  or the missing fork-guard on `docs-link-check.yml`'s comment step, both documented
  in `pr-preflight-checklist.md`) versus a **tool not installed locally**
  (mkdocs/lychee absent). Label it explicitly as one or the other — never present a
  known-upstream WARN as something the user needs to fix on their branch.
- **SKIP** — the gate didn't apply (e.g. docs-build/doc-links skipped because
  `docs/**` didn't change) — not a finding, just note it ran zero-scope.

Keep the report to the table plus one line per non-PASS category. Don't re-explain
what CI does in general — that's `pr-preflight-checklist.md`'s job, not this report's.
