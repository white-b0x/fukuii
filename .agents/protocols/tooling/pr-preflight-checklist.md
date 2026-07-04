# PR Preflight Checklist

What actually gates a PR against `chippr-robotics/fukuii`'s `staging`/`develop`/`main`
branches, how that differs for a **fork** PR specifically, and how to check all of it
locally before pushing — so CI is a confirmation, not a discovery process.

Used by: all agents preparing a PR, WARDEN (owns this doc + its companion script)
Referenced by: `warden.md`

---

## The real gates

Source of truth — don't re-derive, cite these:

- `.specify/memory/constitution.md` Principle V: CI gates + at least one review must
  pass, all conversations resolved, before merge.
- `docs/development/contributing.md`: `scalafmtCheckAll`/`scalafmtAll`, `scalafixAll`,
  Scoverage coverage threshold (70% statement minimum), the `pp`/`formatAll` pre-PR
  commands.
- `.github/BRANCH_PROTECTION.md`: required status checks (`Test and Build`, `Build
  Docker Images`) at the GitHub branch-protection-rule level.

Concretely, `.github/workflows/ci.yml`'s `Test and Build` job runs, in order:
`scalafmtCheckAll` → `compile-all` → Tier 1 `testEssential` → Tier 2 `testStandard`
+ coverage (skipped for PRs targeting/pushes to `staging` — see `ci.yml`'s own
comment, full Tier 2 still runs for `develop`/`main`) → KPI baseline spec → the
deterministic Gating Integration subset (`ethtest`/`txExecTest`, EVM compliance +
ECIP-1017) → `assembly` → `dist`. `docs-preview.yml`'s `mkdocs build --strict` gate
only runs when `docs/**`/`mkdocs.yml`/`requirements-docs.txt` changed (its own
`paths:` filter).

## Fork-PR asymmetry

A PR from a fork (`GITHUB_TOKEN` is read-only there) silently loses some
non-blocking, self-healing automation that same-repo PRs get for free. Know which
checks still gate you and which just quietly don't run:

| Check | Same-repo PR | Fork PR |
|-------|--------------|---------|
| `autofix.yml` scalafmt auto-commit | Runs, pushes a fix commit | **Skipped** (fork guard) — `ci.yml`'s `scalafmtCheckAll` is the only backstop, and it's a hard failure, not an auto-fix |
| `pr-management.yml` auto-label / milestone-check / issue-link-comment | Runs | **Skipped** (fork guard) — non-blocking either way, cosmetic only |
| `ci.yml` (`scalafmtCheckAll`, `compile-all`, test tiers, KPI, Gating IT, `assembly`/`dist`) | Runs, blocking | **Runs, blocking** — identical on both, this is the real gate |
| `docs-preview.yml`'s `mkdocs build --strict` step | Runs, blocking, only if `docs/**`/`mkdocs.yml` changed | **Runs, blocking**, same trigger condition — identical on both; this is the real correctness gate |
| `docs-preview.yml`'s "Deploy Preview" step | Runs, pushes preview to `gh-pages` | **Skipped** (fork guard, once the fix below merges) — pushing to `gh-pages` needs a write token forks don't get; a red job here (before the fix) was the deploy step 403ing, not the `mkdocs build --strict` step above it failing |
| `docs-link-check.yml` (Link Checker + PR comment) | Runs; comment step succeeds | **Currently broken on both** — see "Known upstream bugs" below; not caused by your diff |

Net effect: a fork PR gets **less help** (no auto-fix, no auto-label) but faces the
**same real gates**. Don't rely on `autofix.yml` catching a formatting slip — it can't
reach your branch.

## Known upstream bugs — not your regression, don't chase locally

Two bugs in `.github/workflows/docs-link-check.yml` fail on essentially every fork
PR that touches `docs/**`, independent of what the PR actually changes:

1. `lycheeverse/lychee-action@v2` (a floating major-version tag) currently resolves
   to a lychee CLI release that removed the `--exclude-mail` flag the workflow
   hardcodes in its `args:` block — the Link Checker step errors out before checking
   a single real link.
2. The "Comment on PR" step's `if:` only checks `steps.lychee.outcome == 'failure'
   && github.event_name == 'pull_request'` — unlike every sibling job
   (`autofix.yml`, `pr-management.yml`'s `link-issue`/`label-pr` jobs), it's missing
   `&& github.event.pull_request.head.repo.full_name == github.repository`. On a
   fork PR this tries to write a PR comment with a read-only `GITHUB_TOKEN` and
   403s, turning what should be a clean skip into a second hard failure.

If `Check Documentation Links` is red on your fork PR and the failure is the lychee
CLI erroring on an unrecognized flag (not a real broken link it actually found) or a
403 on the comment step, it's this — not something your branch caused or can fix by
editing docs. Cross-reference the fix PR once it exists: **`chippr-robotics/fukuii`
PR fixing `docs-link-check.yml`'s lychee args + fork-guard** (opened separately per
`github-workflows.md`'s "no workflow-logic changes without explicit request" rule —
do not bundle a workflow-logic fix into an unrelated feature/fix PR).

A third, related bug: `docs-preview.yml`'s "Deploy Preview" step
(`rossjrw/pr-preview-action@v1`, which pushes the built site to `gh-pages`) 403s on
every fork PR with `Permission to chippr-robotics/fukuii.git denied to
github-actions[bot]` — the default `GITHUB_TOKEN` is read-only on a fork PR
regardless of the workflow's own `permissions:` block; this is a GitHub
platform-level restriction, not a config mistake fixable by granting more scope. If
`Documentation Preview` is red on your fork PR, check which step failed first: the
earlier `mkdocs build --strict` step (a real, fixable regression in your diff) or
this later Deploy Preview step (a known upstream issue, not yours). Fixed in the
same PR as the two `docs-link-check.yml` bugs above by adding the same fork-guard to
this step.

## Local repro commands

Run these before pushing, matching what CI actually runs:

**Format (always):**
```bash
sbt scalafmtAll                      # fix
scripts/agent-tooling/sbt-run.sh <log-name> scalafmtCheckAll compile-all testEssential   # verify, background
```

**Docs build (only if `docs/**`/`mkdocs.yml`/`requirements-docs.txt` changed):**
```bash
mkdocs build --strict                # needs requirements-docs.txt installed, e.g. into a local venv
```

**Doc links (only if `docs/**`/`mkdocs.yml` changed, and only if `lychee` is
installed locally — it's optional, not a required local dependency):**
```bash
lychee --verbose --no-progress --accept 200,204 \
  --exclude 'localhost' --exclude '127.0.0.1' \
  --exclude 'github.com/.*/edit/' --exclude 'github.com/.*/issues/new' \
  './site/**/*.html'
```
Note: no `--exclude-mail` — that flag doesn't exist in current lychee CLI releases;
mail addresses are excluded by default now. This mirrors the corrected args, not
`docs-link-check.yml`'s current (broken) ones.

**Mechanical shortcut:** all of the above, in one backgrounded pass with one
consolidated PASS/FAIL/WARN report:

```bash
scripts/agent-tooling/pr-preflight.sh <log-name> [<base-ref>]
```

Invoke with the calling tool's background-execution option (e.g. Bash tool
`run_in_background: true`) — see `background-script-execution.md`. See
`scripts/agent-tooling/README.md` for its base-ref resolution order (it does not
hardcode `upstream/staging` — the same script must work from a fork clone or from
inside the canonical repo itself).
