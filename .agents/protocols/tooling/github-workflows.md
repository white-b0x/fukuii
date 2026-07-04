# GitHub workflows and automation

Conventions for GitHub Actions and repo automation under `.github/`. Ported and adapted
from Nethermind's `github-workflows.md`
(`.claude/repo-references/clients/nethermind/.agents/rules/github-workflows.md`).

## Workflows

- **Naming**: kebab-case, descriptive (fukuii's existing convention — `ci.yml`,
  `ethereum-tests-nightly.yml`, `hive-consensus.yml`, `docs-link-check.yml`). Follow it for
  any new workflow.
- **Concurrency**: `ci.yml`'s pattern is the reference —
  `group: ci-${{ github.ref }}` with `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`
  (cancel superseded PR runs, but never cancel a `push`-triggered run to `main`/`develop`
  mid-flight). Match this shape for new PR/push-triggered workflows rather than inventing a
  different group key.
- **Triggers**: be explicit — `pull_request:`, `push: branches: [...]`, or
  `workflow_dispatch:` with inputs — not a bare `on: [push, pull_request]`.
- **Secrets**: never log or echo secrets; scope `${{ secrets.X }}` to only the job/step that
  needs it.
- **Runner labels**: `ubuntu-latest` unless a workflow has a documented reason for a
  different runner (e.g. a self-hosted label for a resource-heavy hive/benchmark job).
- **The `hive-*.yml` family** (10 workflows) all wrap the vendored Hive test harness
  (`.claude/repo-references/hive/`) — when adding a new Hive suite, follow the naming and
  structure of an existing `hive-*.yml` rather than inventing a new pattern.

## Pull request template

`.github/PULL_REQUEST_TEMPLATE.md` — fill in the changes section, tick the appropriate
type-of-change checkboxes, complete testing/documentation sections. Checkboxes drive
automatic labeling via `.github/labeler.yml` — don't remove required sections.

## Labels

`.github/labeler.yml` drives path-based auto-labeling; `.github/AGENT_LABELS.md` documents
the (mostly manual) agent-label taxonomy — see that file's scope note for why it's not a
mirror of `.claude/agents/`. `.github/CREATE_LABELS.md` documents how to actually create a
new label via `gh` CLI/API — a markdown/YAML comment update alone does not create a GitHub
label; both steps are needed when adding a genuinely new label.

## CODEOWNERS

**Not currently present, and deliberately not added as part of this pass.** `git log`
shows a single active contributor as of 2026-07 — a `CODEOWNERS` file's entire value is
routing review by *multiple* owners across paths; with one maintainer, every line would
resolve to the same name and add process overhead without the benefit CODEOWNERS exists
to provide. Add this file when a second regular contributor/reviewer joins, mapping paths
to real owners at that point — don't fabricate placeholder ownership now.

## Notes for agents

- Do not change workflow logic (triggers, steps, matrices) without explicit user request —
  same rule as `AGENTS.md`'s general working discipline, restated here because workflow
  changes are easy to treat as "just YAML" when they're actually CI-gating logic.
- When adding a new workflow, follow the closest existing workflow's shape (concurrency,
  env scoping, job naming) rather than inventing a new structure.
