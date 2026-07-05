---
name: fukuii-test-all
description: >-
  Run Fukuii's fuller local test tiers (`testStandard`/`testComprehensive` via
  `sbt-run.sh`, background) and/or dispatch and monitor fukuii's real remote CI
  workflows via `gh workflow run`/`gh run watch`. Use when asked to "run the full
  test suite", "run testStandard/testComprehensive", "kick off CI for this branch",
  or "run the Hive suite on CI" without pushing new commits. Covers local tiers
  AND the remote CI-dispatch table together — the local-only fast tier is
  `fukuii-test-unit`; the local Hive simulator runner is `fukuii-test-hive`.
disable-model-invocation: true
user-invokable: true
argument-hint: "testStandard|testComprehensive|<workflow-name>"
---

# Fukuii full test tiers + CI dispatch

Two related capabilities: running fukuii's fuller local sbt test tiers, and
dispatching/monitoring fukuii's real GitHub Actions workflows on demand — the
remote-CI half nothing else in fukuii's skill set currently covers.

## When to use

- **Local tiers**: before opening a PR (`testStandard`), or as a release gate
  (`testComprehensive`) — see AGENTS.md's Build & test commands table for what each
  tier actually covers (drifts over time; that table is the source of truth, not a
  number hardcoded here).
- **Remote dispatch**: a branch needs CI to run without a new push (e.g. re-running
  after a flake, or validating a branch like a long-lived worktree that doesn't
  auto-trigger `on: push`/`pull_request`), or you want a specific Hive suite run on
  CI's infrastructure rather than locally (`fukuii-test-hive` is the local
  equivalent).

## Procedure — local tiers (background, log-to-file)

```bash
scripts/agent-tooling/sbt-run.sh <log-name> testStandard
scripts/agent-tooling/sbt-run.sh <log-name> testComprehensive
```

Invoke with `run_in_background: true` per
`.agents/protocols/process/background-script-execution.md`. Do not poll; read the
log's tail on completion notification for the pass/fail summary and `EXIT CODE`.

## Procedure — remote CI dispatch

### Dispatch table

fukuii has 27 workflow files as of this writing; the ones most relevant to dispatch
on demand (verified `workflow_dispatch:` support against each file directly — don't
assume from this table alone if it's been a while):

| Workflow name (`gh workflow run "<name>"`) | File | Notes |
|---|---|---|
| `CI` | `ci.yml` | Main build+test gate. **Required this session's `workflow_dispatch:` addition to be dispatchable at all** — every other workflow below already supported it. |
| `Hive · consensus` | `hive-consensus.yml` | One of 13 Hive suites — see `fukuii-test-hive` for the local equivalent and full suite table. |
| `Hive · engine` | `hive-engine.yml` | |
| `Hive · rpc-compat` | `hive-rpc-compat.yml` | |
| `Hive · sync` | `hive-sync.yml` | |
| `Hive · devp2p` | `hive-devp2p.yml` | |
| `Hive · smoke-genesis` | `hive-smoke-genesis.yml` | |
| `Hive · smoke-network` | `hive-smoke-network.yml` | |
| `Hive · graphql` | `hive-graphql.yml` | |
| `Hive · pyspec` | `hive-pyspec.yml` | |
| `Hive · consume-engine` | `hive-consume-engine.yml` | |
| `Hive · consume-rlp` | `hive-consume-rlp.yml` | |
| `Hive Osaka Suite` | `hive-osaka.yml` | Named differently from the other 12 Hive workflows — doesn't follow the `Hive · <suite>` convention. |
| `Hive Prague Suite` | `hive-prague.yml` | Same naming note as Osaka. |
| `Ethereum/Tests Nightly` | `ethereum-tests-nightly.yml` | Full EF test corpus, normally nightly-scheduled. |
| `Fast Distro` | `fast-distro.yml` | |
| `Release` | `release.yml` | Do not dispatch casually — confirm intent first, this cuts a release. |

`_hive-sim.yml` is a reusable `workflow_call`-only workflow — it has no
`workflow_dispatch` and cannot be targeted directly; dispatch the specific
`Hive · <suite>` workflow instead, which calls it internally.

### Dispatch and monitor

```bash
gh workflow run "<name>" --ref <branch>
gh run list --workflow="<name>" --limit 5
gh run watch <run-id>
```

`gh run watch` blocks until the run completes — this is an acceptable foreground
wait (it's `gh` polling GitHub's API, not local compute pressure), unlike sbt
commands, which must go through the background wrapper.

### Keeping this table in sync

This table is a snapshot of `.github/workflows/*.yml` as of the date this skill was
written. If a workflow is renamed, added, or removed, update this table in the same
change — don't let it silently drift from what `gh workflow list` actually shows.
