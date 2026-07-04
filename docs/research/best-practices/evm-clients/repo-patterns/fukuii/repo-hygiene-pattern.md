# Fukuii — Repo Hygiene Baseline (self-audit)

Verified fresh against the live repo at `/media/dev/2tb/dev/fukuii/` on 2026-07-03 — do
not trust any prior session summary, including this document's own earlier drafts if one
existed. Every claim below is backed by a direct `ls`/`grep`/`Read` run during this audit,
not by memory of a previous pass.

---

## Security posture

**`SECURITY.md` (root): ABSENT.** `ls SECURITY.md` → `No such file or directory`. There is
no root-level security policy, no documented vulnerability-disclosure process, and no
security contact address anywhere at the repo root. `README.md` links to
`docs/runbooks/security.md` under "Operations and Maintenance," but that is an *operational
hardening runbook* for node operators (RPC exposure, TLS, peer blocking — see
`fukuii-security-hardening` and `fukuii-tls-operations` skills), not a project-level
`SECURITY.md` disclosure policy. These are different documents serving different audiences;
the operational runbook's existence does not substitute for a `SECURITY.md`.

**CI security scanning: ABSENT.** `.github/workflows/` currently contains **29 files**
(confirmed via fresh `ls`):

```
autofix.yml               fast-distro.yml            hive-osaka.yml
auto-version.yml          gh-pages.yml                hive-prague.yml
ci.yml                    hive-consensus.yml          hive-pyspec.yml
dependency-check.yml      hive-consume-engine.yml     hive-rpc-compat.yml
docker.yml                hive-consume-rlp.yml        _hive-sim.yml
docs-link-check.yml       hive-devp2p.yml             hive-smoke-genesis.yml
docs-preview.yml          hive-engine.yml             hive-smoke-network.yml
ethereum-tests-nightly.yml hive-graphql.yml           hive-sync.yml
launchpad-ppa.yml         nightly.yml                 pr-management.yml
README.md                 release.yml
```

A case-insensitive grep across every file in `.github/workflows/` for
`codeql|trivy|semgrep|snyk|dependency-review|sarif` returned **zero matches** (exit code 1,
no output) — confirming no CodeQL analysis, no container/dependency vulnerability scanner,
no SAST tool, and no SARIF upload step exist anywhere in CI today.

The one workflow whose name suggests security scanning, **`dependency-check.yml`**, does
**not** do vulnerability scanning. Reading it in full: it runs weekly (`cron: '0 9 * * 1'`)
plus on PRs touching `Dependencies.scala`/`build.sbt`/`project/plugins.sbt`, and its only
action is `sbt dependencyTree > dep-tree.txt`, which it uploads as an artifact and pastes
the first 100 lines into a markdown report. It is a **dependency-tree visibility report**,
not a CVE/vulnerability scanner — there is no `sbt dependencyCheck` (OWASp Dependency-Check
plugin), no Snyk, no `npm audit`-equivalent. Its PR comment even says "ensure... No security
vulnerabilities are introduced" as a *human checklist item*, not something the workflow
itself verifies. This distinction matters: a naive `ls .github/workflows/` reading the
filename would wrongly conclude security scanning exists.

**Verdict:** the repo has zero automated security scanning (no static analysis, no
dependency CVE scanning, no container image scanning) and no `SECURITY.md` disclosure
policy. Both are genuine gaps relative to the reference clients audited elsewhere in
`repo-patterns/` (e.g. Nethermind's CodeQL-on-every-push, cited in
`nethermind/dev-workflow-skills-pattern.md` and fukuii's own `README.md` "Supply Chain
Security" section, which describes CodeQL/SLSA/Cosign as properties of the **downstream
deployment/release artifacts**, not of the *fukuii repository's own CI*).

---

## Ownership & governance

**`.github/CODEOWNERS`: ABSENT.** `ls .github/CODEOWNERS` → `No such file or directory`.
This is confirmed **deliberate**, not an oversight — `.agents/protocols/github-workflows.md`
(canonical source; symlinked into `.claude/agent-protocols/`) states explicitly:

> "Not currently present, and deliberately not added as part of this pass. `git log` shows
> a single active contributor as of 2026-07 — a `CODEOWNERS` file's entire value is routing
> review by *multiple* owners across paths; with one maintainer, every line would resolve
> to the same name and add process overhead without the benefit CODEOWNERS exists to
> provide. Add this file when a second regular contributor/reviewer joins, mapping paths to
> real owners at that point — don't fabricate placeholder ownership now."

**`CODE_OF_CONDUCT.md`: ABSENT.** `ls CODE_OF_CONDUCT.md` → `No such file or directory`.
`docs/development/contributing.md` has a "Code of Conduct" section (line 16-18) but it is
two sentences of prose ("We are committed to providing a welcoming and inclusive
environment... Please be respectful and professional") — there is no separate
`CODE_OF_CONDUCT.md` file and no adopted external template (e.g. Contributor Covenant).

**`CHARTER.md`: ABSENT.** `ls CHARTER.md` → `No such file or directory`. No project charter
exists at any location checked.

**Actual current maintainer/contributor identities** — fresh `git log --format='%an <%ae>'
| sort | uniq -c | sort -rn` against the live repo, full history (this repo is a fork of
IOHK Mantis, so the list includes the full pre-fork history):

| Commits | Identity | Role |
|---|---|---|
| 2011 | `copilot-swe-agent[bot]` | GitHub Copilot coding-agent automation |
| 898 | Cody Burns `<cody.w.burns@gmail.com>` | Active maintainer |
| 483 | `realcodywburns` (same person, GitHub-noreply alias) | Active maintainer |
| 257 | `github-actions[bot]` | CI automation (auto-version, release-drafter) |
| 187 | Christopher Mercer `<...@users.noreply.github.com>` | Active maintainer |
| 8 | Claude `<noreply@anthropic.com>` | AI-agent commits |
| (remaining hundreds) | Adam Smolarek, Nicolas Tallar, KonradStaniec, Radek Tkaczyk, Lukasz Gasior, Alan Verbner, and ~50 more `@iohk.io` addresses | **Pre-fork Mantis history only** — no evidence of ongoing activity under this repo |

This **confirms** the "single active contributor" framing used to justify the missing
CODEOWNERS is accurate in spirit but slightly undersells reality: `git log` shows **two**
named human maintainers with recent, substantial activity (Cody Burns/realcodywburns and
Christopher Mercer), plus two bot identities (`copilot-swe-agent[bot]`,
`github-actions[bot]`) and one AI-agent identity (`Claude`). The commit-count dominance of
`copilot-swe-agent[bot]` (2011 commits — by far the single largest contributor by volume)
is worth flagging on its own: this is not "one human maintainer plus noise," it is a
repository whose commit history is majority-automated. Whether CODEOWNERS' review-routing
value proposition changes once two humans are actively reviewing each other's work (rather
than one human reviewing bot/agent output) is a judgment call for the maintainers, not
something this audit resolves — but the underlying premise ("effectively one owner, so
CODEOWNERS adds no value yet") still holds for now, since Cody Burns and Christopher Mercer
appear to be the same organizational unit (Chippr Robotics LLC per `README.md`'s "Contact"
section) rather than independent external reviewers.

---

## Contribution & PR process

**`CONTRIBUTING.md` (root)**: present, 3 lines, pure redirect:
```
# Contributing to Fukuii
See [docs/development/contributing.md](docs/development/contributing.md) for the full contributing guide.
```

**`docs/development/contributing.md`**: present, **573 lines**, substantial and current.
Structure (verified via full read):

1. Code of Conduct (2-sentence prose, no separate file)
2. Getting Started — JDK 25, sbt ≥1.10.7, git, optional Python; fork/clone/submodule steps;
   GitHub Codespaces quick-start pointer
3. Development Workflow — branch → change → test → pre-commit → commit → PR
4. Code Quality Standards — Scalafmt, Scalafix, Scapegoat (with report paths under
   `target/scala-3.3/scapegoat-report/`), Scoverage (70% statement-coverage minimum,
   `target/scala-3.3.7/scoverage-report/`), and the combined aliases `formatAll` /
   `formatCheck` / `pp`
5. Scala 3 Development — confirms Scala 3.3.7 LTS + JDK 25 LTS only, no cross-compilation,
   migration from 2.13/JDK 17 completed October 2025, links `INF-001` ADR
6. Pre-commit Hooks — three copy-pasteable hook variants (check-only, auto-fix, staged-files-
   only quick check), plus `--no-verify` bypass documented (with "not recommended" caveat)
7. Testing — `testAll`, three-tier `testEssential`/`testCoverage`/`testComprehensive` (per
   `TEST-002` ADR), per-module test commands, IntegrationTest, and two dedicated
   "async testing best practices" subsections: TestKit patterns vs. `Thread.sleep` (banned),
   and Cats-Effect `IO` error handling via `IO.attempt` + `Status.Failure` vs. the banned
   `onError(...).unsafeToFuture().pipeTo(...)` race-condition pattern, each with an ADR link
   (`INF-004`)
8. Submitting Changes — `sbt pp` gate, commit-message conventions, PR guidelines
   (title/description/testing/documentation/breaking-changes)
9. Continuous Integration — bullet list of what `ci.yml` actually runs (compile-all,
   formatCheck, runScapegoat, testCoverage, artifact build) — matches the live `ci.yml`
10. **Releases and Supply Chain Security** — describes the tag-triggered release automation
    (see Docker & release section below) including SBOM, Cosign signing, SLSA provenance, and
    a `cosign verify` example command; documents `feat:`/`fix:`/`security:`/`docs:` commit
    prefixes for Release Drafter categorization
11. Guidelines for LLM Agents — explicitly delegates to root `AGENTS.md` and `CLAUDE.md`
    rather than duplicating content ("Both are kept current; this section intentionally no
    longer duplicates their content")
12. Additional Resources / Questions or Issues — links to hosted docs site, CI/CD doc,
    branch-protection doc, ADR index, migration history, static-analysis doc

This is a mature, well-cross-referenced contributor guide — not a stub. The redirect
pattern (thin root `CONTRIBUTING.md` → full `docs/development/contributing.md`) is
intentional and matches the same pattern used for `AGENTS.md`/`CLAUDE.md` (portable content
in one canonical file, thin pointers elsewhere).

**`.github/ISSUE_TEMPLATE/`**: 2 files, both read in full:
- `bug_report.md` — a nearly-stock GitHub default bug template (Describe the bug / To
  Reproduce / Expected behavior / Desktop info / Additional context). Generic, not
  fukuii-specific, no YAML issue-forms schema (still the old `.md`-with-frontmatter style,
  not the newer `.yml` structured-forms format GitHub also supports).
- `gorgoroth_field_report.md` — a detailed, project-specific structured template (title
  auto-prefills `[Field Report] Gorgoroth Trial - [Configuration Name]`, auto-applies labels
  `gorgoroth-trials, validation-results`) for the Gorgoroth Trials alpha testing campaign:
  system info, test duration, pass/fail checklists split by trial type (Gorgoroth 3-node/
  fukuii-geth/fukuii-besu/mixed vs. Cirith Ungol mainnet/Mordor), performance metrics,
  free-text sections for issues/suggestions/logs. This is a purpose-built campaign artifact,
  not boilerplate.

**`.github/PULL_REQUEST_TEMPLATE.md`**: present, read in full — 18 lines: `# Description`,
`# Proposed Solution` (optional), `# Important Changes Introduced` (optional), `# Testing`
(optional). Simple, only the Description section is non-optional. Note:
`.agents/protocols/github-workflows.md` states "Checkboxes drive automatic labeling via
`.github/labeler.yml` — don't remove required sections," but the template as it actually
reads today has **no checkboxes** at all (no type-of-change checklist) — this is a
discrepancy between the protocol doc's description and the live template file worth noting
for whoever next touches either file. `labeler.yml`'s auto-labeling is driven entirely by
**changed-file globs**, not PR-body checkbox parsing, so the labeler itself isn't broken —
but the protocol note describing a checkbox-driven mechanism doesn't match what's on disk.

**`.github/labeler.yml`** (2559 bytes, read in full) — path-glob-based labeler config for
the `actions/labeler` GitHub Action. Confirmed current structure: one **automatic**
agent label (`'agent: forge 🔨'`, triggered by `vm/**`, `consensus/**`, `mining/**`, or
`crypto/**` changes) plus nine general module/purpose labels (`documentation`,
`dependencies`, `docker`, `ci/cd`, `tests`, `crypto`, `bytes`, `rlp`, `core`,
`configuration`, `build`). The file's own comment block (lines 18-27) explicitly documents
the **current 12-agent roster** (`forge, beacon, eye, wraith, herald, mithril, prism, loom,
vault, conduit, flow, warden`) and states that ICE and Morgoth — legacy coordination roles
from an earlier 7-agent roster — were retired into `CLAUDE.md`'s own orchestration, with
"no current label exists for them and none should be added." **This confirms the
session's-earlier ICE/Morgoth roster-correction edit is in place** — this is not stale
content, it is the corrected version.

**`.github/AGENT_LABELS.md`** (9733 bytes, read in full) — its own top-of-file scope note
(lines 3-8) states plainly: *"This document describes GitHub issue/PR *labels* only — a
separate, narrower taxonomy from the live Claude Code subagent roster in `.claude/agents/`
(12 agents as of 2026-07...). Only `agent: forge` is currently auto-applied...; the rest are
manual, legacy labels reflecting an earlier 7-agent roster (wraith, mithril, ICE, eye,
forge, herald, Morgoth). Do not treat this file as a mirror of `.claude/agents/`."* **This
confirms the session's-earlier AGENT_LABELS.md scope-note edit is in place.** The body of
the file below that note still documents the legacy 7-agent set in detail (including ICE 🧊
and Morgoth 🎯 sections) — this is intentional per the scope note (it is a historical/legacy
label reference, not required to track the current subagent roster 1:1), and the
"Related Documentation" section at the bottom correctly redirects to `CLAUDE.md` for the
current Morgoth/ICE-successor orchestration.

---

## Docker & release

**`docker/` current Dockerfile list** (fresh `ls docker/`):

```
Dockerfile             Dockerfile.bootnode      Dockerfile.mordor
Dockerfile-base         Dockerfile.distroless
Dockerfile-dev          Dockerfile.mainnet
```

Six Dockerfiles total: `Dockerfile` (main/production), `Dockerfile-base` (foundation image,
per `BRANCH_PROTECTION.md`'s description of the `docker.yml` workflow — base → dev → main),
`Dockerfile-dev` (development environment), `Dockerfile.bootnode` (bootnode variant),
`Dockerfile.distroless` (minimal-attack-surface distroless variant), `Dockerfile.mainnet`
and `Dockerfile.mordor` (network-specific variants). Also present in `docker/`: a `fukuii/`
subdirectory, a `scripts/` subdirectory, a `bootnode/` subdirectory, and four helper shell
scripts (`build.sh`, `build-base.sh`, `build-dev.sh`, `buildhelper.sh`).

**`.github/VERSIONING.md`** (read in full, 85 lines) — confirmed current release-automation
description matches the "fully automatic, no manual gate" characterization:

- **Patch**: auto-incremented on every commit/merged PR to `main`/`master`/`develop`.
- **Minor**: triggered by the literal word "milestone" in commit message, PR title, or PR
  label — patch resets to 0.
- **Major**: manual only, at project completion (e.g. reaching 1.0.0).
- **Automation chain**: `auto-version.yml` bumps `version.sbt` and pushes a version tag on
  every merge → the tag push triggers `release.yml`, which builds the distribution ZIP +
  assembly JAR, generates a CHANGELOG from commit history, produces a CycloneDX SBOM,
  builds/signs Docker images with Cosign (keyless, GitHub OIDC) and SLSA Level 3 provenance
  → `release-drafter.yml` keeps a draft release's categorized notes updated as PRs merge.
- The file states outright: **"Every merge to main/master/develop creates a new release. If
  you want to batch changes, work in feature branches and only merge to main when ready to
  release."** — i.e. there is no manual release gate; merging to a protected branch *is* the
  release trigger. This is corroborated independently by `docs/development/contributing.md`
  §"Releases and Supply Chain Security" (same SBOM/Cosign/SLSA description, same `cosign
  verify` example command reproduced near-verbatim) and by `.github/BRANCH_PROTECTION.md`'s
  description of `release.yml` as tag-triggered with automatic milestone-closing.

**Important nuance the SBOM/Cosign/SLSA language can obscure:** this security tooling
(SBOM generation, Cosign signing, SLSA provenance) applies to **release artifacts produced
after the fact** — it verifies supply-chain integrity of what gets *shipped*, and is
entirely separate from **pre-merge CI security scanning** (CodeQL/Trivy/Semgrep/etc.),
which — per the Security posture section above — does not exist anywhere in this repo's
CI today. A release can be automatically SBOM'd, signed, and attested while containing
code that was never scanned by a SAST tool or dependency-vulnerability checker before it
reached `main`. These are not contradictory facts, but conflating "supply chain security on
release artifacts" with "security scanning in CI" would be a mistake this audit explicitly
guards against.

---

## Confirmation or correction of prior assumptions

The baseline assumed at the start of this session was: **"no `SECURITY.md`, no
`CODEOWNERS`, no CI security scanning, fully automatic release with no manual gate."**

**This baseline is confirmed accurate in full, with two refinements:**

1. **No `SECURITY.md`** — confirmed, file absent at repo root. `docs/runbooks/security.md`
   exists but is an operational hardening runbook for node operators, not a project
   vulnerability-disclosure policy; it does not fill the `SECURITY.md` gap.
2. **No `CODEOWNERS`** — confirmed absent, and confirmed *deliberate* per
   `.agents/protocols/github-workflows.md`'s explicit rationale (single organizational
   maintainer as of 2026-07). Refinement: `git log` shows this is more precisely "two named
   human maintainers from the same organization (Chippr Robotics LLC) plus heavy bot/AI-agent
   commit volume" rather than literally one contributor — the practical conclusion
   (CODEOWNERS adds no value with a single reviewing entity) is unchanged, but the phrase
   "single active contributor" undersells that there are two named humans in the loop.
3. **No CI security scanning** — confirmed via fresh grep across all 29 current workflow
   files: zero matches for `codeql|trivy|semgrep|snyk|dependency-review|sarif`. Refinement:
   `dependency-check.yml` exists and could be mistaken for a scanner by name alone — it is
   read in full above and confirmed to be a dependency-tree *reporting* job with no CVE/
   vulnerability-detection logic, so it does not change the "no security scanning" verdict.
4. **Fully automatic release, no manual gate** — confirmed via full read of
   `VERSIONING.md`, cross-checked against `docs/development/contributing.md` and
   `BRANCH_PROTECTION.md`: every merge to `main`/`master`/`develop` auto-bumps version,
   auto-tags, and the tag auto-triggers the full release pipeline (SBOM, Cosign signing,
   SLSA provenance, GitHub release, milestone-close) with no human approval step in between.

**Also newly verified in this pass (not part of the original baseline, but relevant repo-
hygiene facts for future comparisons):** `CODE_OF_CONDUCT.md` and `CHARTER.md` are both
absent as standalone files; the two-agent-taxonomy documentation edits made earlier this
session (the `labeler.yml` ICE/Morgoth roster-retirement comment and the `AGENT_LABELS.md`
scope note) are both **confirmed present and correct** on disk; and the
`PULL_REQUEST_TEMPLATE.md` currently contains no checkboxes despite
`.agents/protocols/github-workflows.md`'s description of checkbox-driven labeling — a small
doc/reality mismatch worth fixing whichever file is wrong, but not a hygiene regression.

**Net verdict: no correction needed. The prior baseline holds.**
