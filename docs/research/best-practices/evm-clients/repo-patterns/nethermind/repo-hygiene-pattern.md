# Nethermind — Repo Hygiene & Security Patterns

Source: `.claude/repo-references/clients/nethermind/` (vendored full clone, verified genuine
— this is a real `git clone` with a populated `.git/` directory, not a summary or partial
checkout). Every claim below cites a specific file; where an exact line number could not be
confirmed by direct read, the citation says "see `<file>`" rather than inventing one.

Nethermind is a production C#/.NET Ethereum execution client maintained by Demerzel
Solutions Limited, with a small, named set of maintainers (visible in `.github/CODEOWNERS`).
Its repo-hygiene layer — security policy, CI scanning, ownership routing, contribution
templates, release governance — is mature and battle-tested. This document catalogs that
layer exhaustively and cross-references it against fukuii's current state so a maintainer
can see, at a glance, what to port, what needs redesign, and what genuinely doesn't apply.

---

## SECURITY.md

**File:** `.claude/repo-references/clients/nethermind/SECURITY.md` (11 lines, full text below)

```markdown
# Security policy

If you believe you have found a security vulnerability in our code, we encourage you to report it to us as soon as possible.
We ask that you do not publicly disclose any details of the vulnerability until we have had an opportunity to investigate and address it.

## Reporting a vulnerability

To report a security vulnerability, go to [Report a vulnerability](https://github.com/NethermindEth/nethermind/security/advisories/new). This will create a draft advisory. Please provide as much detail as possible including steps to reproduce the issue and any potential impact it may have.

Alternatively, you can also send an email to security@nethermind.io. We will work to acknowledge your report within 24 hours and will keep you informed throughout our investigation and resolution process.
```

**Disclosure mechanism:** GitHub Security Advisories' private draft-advisory flow
(`.../security/advisories/new`), which creates a repo-scoped private disclosure channel
(reporter + maintainers only) rather than a public issue. This is GitHub's native
coordinated-disclosure primitive — no third-party bug-bounty platform is referenced.

**Fallback channel:** a plain email address, `security@nethermind.io` — useful for
reporters who don't have (or don't want) a GitHub account, or whose finding is too
sensitive to type into a web form tied to their GitHub identity.

**Timeline commitment:** "acknowledge your report within 24 hours" — a concrete,
reporter-facing SLA. No fix-timeline commitment is made (industry-standard practice: fix
timelines depend on severity and can't be promised in the abstract), but the
acknowledgment SLA sets an expectation that reports aren't dropped.

**Scope:** implicitly "any vulnerability in our code" — no explicit in-scope/out-of-scope
list (e.g., no carve-out for third-party dependencies, no bug-bounty reward schedule). The
policy is deliberately minimal: three short paragraphs, no legal boilerplate, no CVSS
scoring rubric.

**Fukuii verdict:** fukuii has no `SECURITY.md` at all. This is the single highest
leverage-per-line file in the entire hygiene layer — it costs nothing to write, and its
absence means a real vulnerability reporter today has no clear channel and no signal that
reports won't be ignored or (worse) publicly dismissed. Port this near-verbatim: point at
GitHub Security Advisories' private draft flow (same mechanism works for any public GitHub
repo) and give a maintainer email as fallback. Adjust the acknowledgment SLA to something
the two actual maintainers (Cody Burns/Chippr Robotics LLC, Christopher Mercer/White B0x
Inc.) can realistically meet.

---

## CI security scanning (CodeQL, Trivy, dependency-review)

Nethermind runs three independent, non-overlapping security scanners in CI, each catching a
different class of problem: CodeQL (source-level static analysis), Trivy (container-image
vulnerability scanning), and `dependency-review-action` (supply-chain gate on PR-introduced
dependencies). None of them depend on each other; losing one doesn't silently degrade
another.

### CodeQL — `.github/workflows/codeql.yml` (51 lines, read in full)

```yaml
name: CodeQL analysis

on:
  push:
    tags: ['*']
  schedule:
    - cron: '0 0 * * 0'
  workflow_dispatch:

jobs:
  analyze:
    name: Analyze
    runs-on: ubuntu-latest
    permissions:
      actions: read
      contents: read
      security-events: write
    strategy:
      fail-fast: false
      matrix:
        language: ['csharp', 'actions']
    steps:
      - name: Free up disk space
        uses: jlumbroso/free-disk-space@54081f138730dfa15788a46383842cd2f914a1be # v1.3.1
        with:
          large-packages: false
          tool-cache: false
          swap-storage: false
      - name: Check out repository
        uses: actions/checkout@v6
      - name: Initialize CodeQL
        uses: github/codeql-action/init@v4
        with:
          languages: ${{ matrix.language }}
          queries: security-and-quality
          packs: githubsecuritylab/codeql-csharp-queries
      - name: Set up .NET
        uses: actions/setup-dotnet@v5
      - name: Build Nethermind
        working-directory: src/Nethermind
        run: dotnet build Nethermind.slnx -c release
      - name: Perform CodeQL analysis
        uses: github/codeql-action/analyze@v4
        with:
          category: '/language:${{ matrix.language }}'
```

**Trigger:** `push: tags: ['*']` (runs on every release tag) **plus** a weekly cron
(`0 0 * * 0` — every Sunday at midnight UTC) **plus** `workflow_dispatch` for manual runs.
Notably *not* triggered on every PR or every push to `master` — CodeQL runs are expensive
(the workflow explicitly frees disk space first, `codeql.yml:23-28`) and a weekly cadence
plus tag-triggered runs is the tradeoff Nethermind made between coverage and CI cost.

**Matrix languages:** `['csharp', 'actions']` (`codeql.yml:21`) — the C# application code
*and* the GitHub Actions workflow YAML itself (CodeQL has a dedicated `actions` query pack
that catches injection vulnerabilities in workflow files, e.g. unsanitized
`${{ github.event.issue.title }}` interpolated into a `run:` shell step).

**Query pack:** `security-and-quality` (broader than the default `security-extended`) plus
an extra third-party pack, `githubsecuritylab/codeql-csharp-queries` (`codeql.yml:37-38`)
— community-maintained C#-specific queries beyond GitHub's own bundled set.

**SARIF upload:** handled implicitly by `github/codeql-action/analyze@v4` — unlike Trivy
below, CodeQL's own `analyze` action uploads SARIF to the repo's Security tab internally;
no separate `upload-sarif` step is needed in this workflow.

**Fukuii verdict — NOT DIRECTLY PORTABLE, substitute confirmed needed.** CodeQL has no
Scala extractor (verified this session against GitHub's supported-languages list) — it
cannot analyze fukuii's Scala 3 codebase at all. The `actions`-language matrix entry
*would* still work unmodified (it analyzes YAML, not the app language), so a fukuii
CodeQL workflow scanning only `.github/workflows/*.yml` for Actions-injection bugs is
legitimately portable in isolation. For the application code itself, recommend **Semgrep**
(has first-class Scala support, runs as a GitHub Action, uploads SARIF the same way) as
the CodeQL-equivalent gap-filler — fukuii has zero Semgrep configuration today (confirmed:
no hits for "semgrep" anywhere in the repo).

### Trivy — `.github/workflows/trivy.yml` (47 lines, read in full)

```yaml
name: Trivy scanner

on:
  pull_request:
    branches: [master]
  push:
    branches: [master]
  schedule:
    - cron: '29 19 * * 4'
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    name: Build
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write
      actions: read
    env:
      IMAGE_TAG: nethermind:${{ github.sha }}
    steps:
      - name: Check out repository
        uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd #v6.0.2
      - name: Build Docker image
        run: docker build -t $IMAGE_TAG .
      - name: Scan
        uses: aquasecurity/trivy-action@57a97c7e7821a5776cebc9bb87c984fa69cba8f1 #v0.35.0
        with:
          image-ref: ${{ env.IMAGE_TAG }}
          format: template
          template: '@/contrib/sarif.tpl'
          output: trivy-results.sarif
          severity: CRITICAL,HIGH
        env:
          TRIVY_DB_REPOSITORY: public.ecr.aws/aquasecurity/trivy-db
      - name: Upload scan results
        uses: github/codeql-action/upload-sarif@0c0c5dc2f136b98cb0537075ccfa21f94cd9a63e #v2.24.3
        with:
          sarif_file: trivy-results.sarif
```

**What it builds/scans:** the *default* `Dockerfile` at repo root (`docker build -t
$IMAGE_TAG .` — `trivy.yml:30`, implicitly using the unqualified `Dockerfile`, not
`Dockerfile.chiseled`/`.pgo`/`.diag`). The image is tagged `nethermind:${{ github.sha }}`
and scanned in place — it is never pushed anywhere; the build exists solely to produce a
scannable artifact for this workflow.

**Severity threshold:** `severity: CRITICAL,HIGH` (`trivy.yml:39`) — Trivy is configured to
report only Critical and High findings; Medium/Low/Unknown are scanned but not surfaced
here, keeping the SARIF feed actionable rather than noisy.

**Trigger:** `pull_request: branches: [master]` **and** `push: branches: [master]` **and**
a weekly cron (`29 19 * * 4` — Thursdays at 19:29 UTC, an intentionally "off" time to avoid
thundering-herd scheduling collisions with other repos' `0 0 * * *`-style crons) **and**
`workflow_dispatch`. Every PR targeting `master` gets scanned pre-merge, not just
periodically — the strongest gate of the three scanners here.

**SARIF pipeline:** Trivy's `template`/`@/contrib/sarif.tpl` output mode converts its
native JSON report to SARIF (`trivy.yml:36-38`), then a *separate*
`github/codeql-action/upload-sarif` step uploads it to the Security tab
(`trivy.yml:43-46`) — unlike CodeQL's own `analyze` action, Trivy's action does not
self-upload, so this repo reuses CodeQL's upload action as a generic SARIF-to-GitHub
bridge.

**Fukuii verdict — PORT NOW, cheap and language-agnostic.** Trivy scans the built
container image, not source code, so it is 100% portable regardless of language — it
would scan fukuii's own `docker/Dockerfile` output (OS packages, JRE, native libs) exactly
as usefully as it scans Nethermind's .NET runtime image. Fukuii has no container scanning
today. This is a near copy-paste port: swap the `docker build` target to
`docker/Dockerfile`, keep the `CRITICAL,HIGH` threshold and the SARIF-upload pattern
unchanged.

### Dependency review — `.github/workflows/dependency-review.yml` (18 lines, read in full)

```yaml
name: Dependency review

on: [pull_request]

permissions:
  contents: read

jobs:
  dependency-review:
    name: Dependency review
    runs-on: ubuntu-latest
    steps:
      - name: Check out repository
        uses: actions/checkout@v6
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
```

**Exact `fail-on-severity` setting:** `high` (`dependency-review.yml:18`) — the workflow
fails the PR check if any newly introduced or changed dependency has a known
High-or-above severity advisory. No other configuration is set (no `allow-licenses`,
`deny-licenses`, or `allow-ghsas` list) — this is the action's near-default posture, using
only the one setting that matters most.

**Trigger:** bare `on: [pull_request]` — every PR, no branch filter. This is the lightest
of the three scanners (GitHub's own hosted advisory database diff, no image build, no
long-running scan) so running it unconditionally on every PR is cheap.

**Fukuii verdict — PORT NOW, but check ecosystem support first.**
`actions/dependency-review-action` works off the GitHub Dependency Graph, which is
populated from supported ecosystem manifests. Fukuii's dependencies are declared in
`build.sbt`/`project/*.sbt` (sbt/Maven-coordinate style) — GitHub's Dependency Graph does
support Maven-format coordinates for JVM ecosystems, so this should work with fukuii's sbt
manifests, but this needs a one-time verification run (open a test PR bumping one
dependency's version and confirm the check populates) rather than an assumption. If it
works, this is a straight port at `fail-on-severity: high`, matching fukuii's existing
"vendor-confirmed CVE-safe versions only" supply-chain discipline.

---

## CODEOWNERS — per-project granularity

**File:** `.github/CODEOWNERS`, 56 lines (`wc -l` confirmed), full text read.

Nethermind's CODEOWNERS is organized as one line per top-level C# project directory under
`src/Nethermind/`, not one line per team or one blanket catch-all. This gives PR review
auto-assignment at the granularity of an individual `.csproj`, which in a ~50-project
solution means a change to, say, `Nethermind.Trie` notifies exactly the four people who
own trie code, not the whole maintainer list.

**Representative entries, quoted verbatim:**

```
/.github @rubo
/scripts @rubo

/Dockerfile* @rubo
/LICENSE* @rubo @LukaszRozmej @MarekM25
*.md @rubo @LukaszRozmej @MarekM25

/src/Nethermind/**/I*Config.cs @rubo
/src/Nethermind/Ethereum.Test.Base @flcl42
/src/Nethermind/Nethermind.Blockchain @LukaszRozmej @MarekM25 @flcl42
/src/Nethermind/Nethermind.Consensus @LukaszRozmej @MarekM25 @flcl42
/src/Nethermind/Nethermind.Crypto @LukaszRozmej @Marchhill
/src/Nethermind/Nethermind.Db.Rocks @LukaszRozmej @asdacap @damian-orzechowski
/src/Nethermind/Nethermind.Evm @LukaszRozmej @benaadams
/src/Nethermind/Nethermind.JsonRpc @LukaszRozmej @benaadams @smartprogrammer93 @svlachakis
/src/Nethermind/Nethermind.Merge.Plugin @LukaszRozmej @MarekM25 @flcl42 @benaadams @Marchhill
/src/Nethermind/Nethermind.Network @LukaszRozmej @flcl42 @asdacap @marcindsobczak
/src/Nethermind/Nethermind.State @LukaszRozmej @benaadams @asdacap @flcl42 @damian-orzechowski
/src/Nethermind/Nethermind.Synchronization @LukaszRozmej @benaadams @asdacap @flcl42 @marcindsobczak
/src/Nethermind/Nethermind.TxPool @LukaszRozmej @benaadams @flcl42 @marcindsobczak
```

**The sub-file-pattern entry:** `/src/Nethermind/**/I*Config.cs @rubo` (`CODEOWNERS:8`) —
notably more granular than a whole-project rule: it matches any file starting with `I` and
ending `Config.cs` (i.e., configuration *interfaces*, by C# naming convention) in *any*
nested directory under `src/Nethermind/`, regardless of which project it lives in. This
routes review of config-surface changes (the public contract of every module's tunable
settings) to one person, `@rubo`, cutting across all ~50 project-ownership lines above it.
It is the one line in the file that is neither "whole project" nor "whole repo" — it
targets a cross-cutting *concept* (config interfaces) via a glob.

**Cross-cutting rules** (apply repo-wide regardless of project, listed first in the file
so later, more specific project rules can still layer on top per Git's
last-match-wins-within-equal-specificity CODEOWNERS semantics):

| Pattern | Owners | Scope |
|---|---|---|
| `/.github` | `@rubo` | All CI/workflow/template changes |
| `/scripts` | `@rubo` | Build and CI helper scripts |
| `/Dockerfile*` | `@rubo` | Every Dockerfile variant (main, chiseled, pgo, diag) |
| `/LICENSE*` | `@rubo @LukaszRozmej @MarekM25` | Any licensing file — three owners, matching CONTRIBUTING.md's rule that license-related PRs get extra scrutiny |
| `*.md` | `@rubo @LukaszRozmej @MarekM25` | Every markdown file repo-wide (docs, READMEs, this file's counterpart) |

**Structure takeaway:** ownership is scoped to *directories that map 1:1 to C# projects*
(the natural module boundary the language already enforces via `.csproj`), with a small
number of repo-wide catch-alls for infra/docs/licensing layered on top, and exactly one
cross-cutting content-pattern rule (`I*Config.cs`) for a concept that doesn't map to a
single directory.

**Fukuii verdict — ALREADY PLANNED (was deliberately deferred, now scoped).**
`.agents/protocols/github-workflows.md`'s CODEOWNERS section previously deferred this file
indefinitely on the grounds that "a single active contributor" makes ownership routing
meaningless — every line would resolve to the same name. That premise needs updating:
fukuii now has two real, distinct maintainers — Cody Burns/Chippr Robotics LLC (repo
owner) and Christopher Mercer/White B0x Inc. (co-maintainer) — so a **lightweight**
CODEOWNERS (not Nethermind's ~50-line per-.csproj-equivalent granularity, since fukuii's
module boundaries and team size don't yet justify that resolution) is worth adding: a
handful of cross-cutting lines (`.github/`, `docs/`, `Dockerfile*`, `*.md`) plus perhaps
2-3 consensus-critical-path lines (`vm/`, `crypto/`, `domain/`) mapped to whichever of the
two maintainers is the de facto reviewer for that area. Do not attempt to replicate
Nethermind's per-module density until the contributor count grows to justify it — a
CODEOWNERS file where every line names the same one or two people adds process overhead
(mandatory review requests) without adding the routing signal it exists to provide.

---

## CONTRIBUTING.md / CODE_OF_CONDUCT.md / templates

### CODE_OF_CONDUCT.md

Present, 135 lines. Standard Contributor Covenant v2.1 adoption (`CODE_OF_CONDUCT.md:117-121`
attributes it explicitly), with the usual four-tier enforcement ladder (Correction →
Warning → Temporary Ban → Permanent Ban) and a dedicated report address,
`community@nethermind.io`, distinct from the security email. Nothing Nethermind-specific
in its content — it is the boilerplate Covenant text — but its *presence* signals that
community conduct has a designated enforcement path and contact, separate from the
security-vulnerability channel.

### CONTRIBUTING.md (84 lines, read in full)

Structured around a strict **PR lifecycle**, `CONTRIBUTING.md:29-45`:

1. Create a feature branch following the branch-naming convention (below).
2. Push and open the PR — if work-in-progress, open as **Draft** and label `wip`.
3. When opened (including as Draft) or moved Draft→Ready, an **automated review** runs
   and posts findings as a PR comment. PRs labeled `wip` (or `WIP`/`[WIP]` in the title)
   are skipped; removing the label triggers the review.
4. Address findings: Critical/High/Medium must be fixed or explicitly acknowledged with
   rationale in a PR comment; Low-severity/nits addressed "when reasonable."
5. Re-run the review after fixes by commenting `@claude review`; new commits invalidate
   the previous `claude-review/reviewed` status, requiring another `@claude review` pass.
6. Self-review the full diff.
7. Mark **Ready for Review** — CODEOWNERS auto-assigns reviewers.

Two enforcement notes worth flagging explicitly (`CONTRIBUTING.md:39,43,45`): "the
automated review does not run on PRs from forks" (a maintainer triggers it manually via
`@claude review`), and merges to `master` are **gated by a required
`claude-review/reviewed` status check** — the PR literally cannot merge while unresolved
Critical/High/Medium findings stand, enforced at the branch-protection level, not just as
a social norm.

**DOs and DON'Ts** (`CONTRIBUTING.md:49-63`) — the DON'Ts are the more load-bearing half:
no PRs for pure style/grammar changes; no new file without the proper file header; no
"big pull requests" without a prior issue/discussion; no committing code the contributor
didn't write without first filing an issue; no PRs touching licensing files/headers; no
PRs modifying CI/CD infrastructure without an explicit justification (mirrors fukuii's own
"don't change workflow logic without explicit user request" rule almost word for word).

**Branch-naming convention** (`CONTRIBUTING.md:65-72`): `kebab-case` or `snake_case`, all
lowercase, ideally `project-if-any/type-of-change/issue-title`, e.g.
`feature/1234-issue-title`, `shanghai/feature/1234-issue-title`, `fix/1234-bug-description`.

**SPDX header requirement** (`CONTRIBUTING.md:74-83`) — mandatory in every source file
"if possible":

```
// SPDX-FileCopyrightText: 2026 Demerzel Solutions Limited
// SPDX-License-Identifier: LGPL-3.0-only
```

with the comment marker substituted per language (`#` for shell, etc.). This is visibly
enforced in practice — every Dockerfile read for this document (`Dockerfile`,
`Dockerfile.chiseled`, `Dockerfile.pgo`, `scripts/build/Dockerfile`) opens with exactly
this two-line header.

### Issue templates — `.github/ISSUE_TEMPLATE/` (2 templates)

**`bug_report.md`** (39 lines): sections for Description, Steps to Reproduce, Actual
behavior, Expected behavior, Screenshots, a **Desktop** block, and Logs. The Desktop block
is the interesting part for an execution client (`bug_report.md:28-33`):

```
**Desktop (please complete the following information):**
Please provide the following information regarding your setup:
 - Operating System: [e.g. Windows]
 - Version: [e.g. 1.17.0]
 - Installation Method: [e.g. GitHub Release/PPA/Homebrew/Docker]
 - Consensus Client: [e.g. Lodestar v1.4.3]
```

Four fields, each solving a real triage problem specific to a PoS execution client: OS
(cross-platform client — Windows/macOS/Linux all first-class, unlike most EL clients),
exact version, **installation method** (GitHub Release vs. PPA vs. Homebrew vs. Docker —
each has different packaging bugs), and **Consensus Client + version** (an EL bug report
is frequently actually a CL-side Engine API incompatibility, so capturing this up front
saves a round-trip).

**`feature_request.md`** (20 lines): plain four-section template (problem, desired
solution, alternatives considered, additional context) — no client-specific fields, this
one is generic.

### Pull request template — `.github/pull_request_template.md` (57 lines, read in full)

Checkbox-driven across three sections: **Types of changes** (Bugfix / New feature /
Breaking change / Optimization / Refactoring / Documentation update / Build-related
changes / Other), **Testing** (Requires testing Yes/No → If yes, did you write tests?
Yes/No → free-text testing notes), **Documentation** (Requires documentation update
Yes/No → Requires explanation in Release Notes Yes/No).

**Confirmed: the checkboxes do drive real auto-labeling.** There is no
`.github/labeler.yml` in this repo (path-based labeling doesn't exist here) — instead,
`.github/workflows/pr-labeler.yml` implements the entire labeling logic as an inline
`actions/github-script` step, triggered on `pull_request_target: [opened, edited,
ready_for_review, synchronize]`. It does four independent things, in order:

1. Parses the PR body for checked (`- [x]`) template checkboxes and maps them to labels,
   e.g. `'Bugfix (a non-breaking change...)' → 'bug fix + reliability'`,
   `'Breaking change...' → 'BREAKING'`, `'Optimization' → 'performance is good'`.
2. Parses the PR **title** for a Conventional-Commits-style prefix (`fix:`, `feat:`,
   `perf:`, `refactor:`, `chore:`, `docs:`, `test:`, `ci:`, `build:`) and maps that too.
3. Scans changed file **paths** for module-specific labels (e.g. anything under
   `Nethermind.Optimism` → `optimism`, `Nethermind.Evm` → `evm`, `Nethermind.Db.Rocks` →
   `rocksdb`), plus special-cases like an all-Test-project diff → `test` label, an
   all-deletions diff → `cleanup` label, and any `SnapSync`-named file → `snap sync`.
4. Diffs the desired label set against current labels and adds/removes only the
   difference, scoped to a `managedLabels` set so it never touches labels the automation
   doesn't own.

This is a materially richer automation than a static `labeler.yml` (which can only do
path-based matching) — it fuses PR-template-checkbox state, conventional-commit title
parsing, and path matching into one label-computation pass.

**Fukuii verdict — mixed.**
- CODE_OF_CONDUCT.md: **port now** — zero cost, standard Covenant text, fukuii has none.
- CONTRIBUTING.md's DOs/DON'Ts and branch-naming convention: **port now**, adapted —
  fukuii's worktree protocol (`.agents/protocols/worktree-protocol.md`) already covers
  branch naming (`wt/<id>`) for sprint work; the DON'Ts list (no unjustified big PRs, no
  infra changes without justification, no committing code you didn't write) is a clean,
  low-cost addition to `CONTRIBUTING.md` if fukuii adds one, or to `AGENTS.md`'s existing
  working-discipline section if not.
- The `claude-review/reviewed` required-status-check gate: **needs design, not a direct
  port** — fukuii would need an equivalent automated-review-on-PR workflow before a
  required check referencing it would make sense; note the mechanism (branch protection
  requiring a named status check that an AI review workflow sets) as a genuinely reusable
  pattern once such a workflow exists.
- SPDX header requirement: **not portable as literally written** (Scala files use `//`
  already, so the syntax is compatible, but fukuii has no license-header convention
  documented anywhere today) — worth considering independently of everything else in this
  document, since it is a one-line addition per new file and gives unambiguous
  per-file licensing provenance.
- Issue templates: **port the Desktop/environment-fields idea**, not the literal template
  — fukuii already has `bug_report.md` and a custom `gorgoroth_field_report.md`
  (`.github/ISSUE_TEMPLATE/`); check whether the existing `bug_report.md` captures
  network (ETC vs Mordor vs ETH vs Sepolia) and sync-mode (SNAP vs fast) fields the way
  Nethermind's captures Consensus-Client-version — if not, that's the concrete gap to
  close, not a wholesale template replacement.
- PR-template-driven auto-labeling: **port the mechanism**, adapted — fukuii already has
  its own `.github/labeler.yml` (path-based) and `PULL_REQUEST_TEMPLATE.md`; Nethermind's
  `pr-labeler.yml` shows a strictly more capable pattern (checkbox + title-prefix + path,
  fused, with a `managedLabels` diff-and-reconcile step) worth studying as an upgrade path
  for fukuii's labeler rather than a first-time introduction of the concept.

---

## Docker & release process

### Dockerfile inventory

| File | Purpose |
|---|---|
| `Dockerfile` | Production image — two-stage build (`dotnet/sdk:10.0.301-resolute` → `dotnet/aspnet:10.0.9-resolute`), publishes `Nethermind.Runner` as `nethermind`, keeps a symlinked `Nethermind.Runner` for backward compatibility with the old executable name (`Dockerfile:23`) |
| `Dockerfile.chiseled` | Same build, but the runtime stage is Microsoft's "chiseled" (Ubuntu Chiseled, distroless-style) ASP.NET image — smaller attack surface, runs as non-root `USER app` (`Dockerfile.chiseled:36`), pre-creates `keystore`/`logs`/`nethermind_db` dirs at build time since chiseled has no shell to `mkdir` at runtime |
| `Dockerfile.pgo` | Not a distribution image — builds *without* ReadyToRun (`-p:PublishReadyToRun=false`) so the JIT compiles every method, enabling EventPipe-based Profile-Guided Optimization data collection; the resulting `.nettrace`/`.jit` files feed `PgoTrim` and `dotnet-pgo` to produce `.mibc` profile data consumed by later production builds (`Dockerfile.pgo:4-8` header comment) |
| `Dockerfile.diag` | Diagnostics image — adds `dotnet-dump`, `dotnet-gcdump`, `dotnet-trace`, `JetBrains.dotTrace.GlobalTools`, and `JetBrains.dotMemory.Console` to a normal build, mounted at `/opt/diag-tools` via `PATH` (`Dockerfile.diag:26-29,43-47`); a `/nethermind/diag` volume gives a landing spot for captured traces/dumps |
| `scripts/build/Dockerfile` | CI package-building image used by `release.yml` — a single-stage SDK image that runs `scripts/build/build.sh` and copies output to a mounted `/output` volume; this is the image `release.yml`'s `build` job actually invokes (`release.yml:47-50`), separate from all four runtime Dockerfiles above |
| `tools/SendBlobs/Dockerfile`, `tools/EngineApiProxy/Dockerfile`, `tools/Kute/Dockerfile` | Standalone images for each auxiliary tool — out of scope for this document's release-image analysis, listed here only for completeness |

**No docker-compose file exists anywhere in the vendored clone** (confirmed via
repo-wide search) — Nethermind ships Dockerfiles only; multi-container orchestration
(if a user wants it) is left entirely to the consumer.

### `Dockerfile.diag` diagnostic tooling (detail)

Confirmed via full read: the diag image installs, as global .NET tools plus one
JetBrains console tool copied from a NuGet package —

- `dotnet-dump` — process/core dump capture and analysis
- `dotnet-gcdump` — GC heap dump capture
- `dotnet-trace` — EventPipe-based CPU/runtime event tracing
- `JetBrains.dotTrace.GlobalTools` — dotTrace's CLI profiler (the tool that produces the
  large XML reports discussed below)
- `JetBrains.dotMemory.Console` — dotMemory's CLI memory profiler, added to the build
  stage via `dotnet add ... package JetBrains.dotMemory.Console.linux-$arch
  --package-directory /tmp` (`Dockerfile.diag:20-22`) and copied into `/opt/diag-tools/dotmemory`

All tools land on `PATH` at `/opt/diag-tools` (`Dockerfile.diag:47`), so a running
container can be `docker exec`'d into and profiled without a separate image or rebuild —
the production entrypoint binary and the full diagnostic toolchain coexist in the same
container image, trading a larger image for zero-friction production profiling.

### Release governance — `.github/workflows/release.yml` (222 lines, read in full)

**Trigger: `workflow_dispatch` only** (`release.yml:4`) — no `push`, no tag trigger, no
schedule. A release is always a deliberate, manually-initiated action; nothing in CI can
accidentally cut a release as a side effect of a merge.

**Job sequence:** `build` → `approval` → {`publish-github`, `publish-docker`} (the latter
two run in parallel, both gated on `approval`).

1. **`build`** (`release.yml:17-110`): detects the version from `Directory.Build.props`
   via `xmlstarlet` (reading `VersionPrefix`/`VersionSuffix`), builds
   `Nethermind.Runner` inside `scripts/build/Dockerfile`, archives the packages, **GPG
   signs each per-RID archive** (linux-arm64/x64, macos-arm64/x64, windows-x64) using
   `secrets.PPA_GPG_SECRET_KEY` + `secrets.PPA_GPG_PASSPHRASE` (`release.yml:55-68`,
   detached ASCII-armored `.asc` signatures via `gpg --pinentry-mode loopback`), and
   uploads each platform's package plus a reference-assemblies package as separate
   artifacts with a 7-day retention (`env.PACKAGE_RETENTION: 7`, `release.yml:13`).

2. **`approval`** (`release.yml:112-121`) — a job whose entire body is
   `echo "Waiting for approval..."`, but whose real function is the
   `environment: {name: Releases}` block (`release.yml:116-118`). GitHub Environments
   support **required reviewers** configured at the repo-settings level (not visible in
   this YAML file itself, since environment protection rules are repo/org configuration,
   not workflow syntax) — when a job targets a protected environment, the workflow run
   pauses at that job until a designated approver clicks Approve in the GitHub UI. This is
   the manual-approval gate: it's not a YAML `if:` condition or a bot check, it's GitHub's
   native environment-protection primitive, and the `url:` field
   (`release.yml:118`, pointing at the not-yet-published release page) is purely
   informational for the approver's convenience.

3. **`publish-github`** (`release.yml:123-179`, needs `[approval, build]`) — mints a
   short-lived GitHub App token (`actions/create-github-app-token`, not a PAT), downloads
   all build artifacts, and either drafts a new release or publishes an already-drafted
   one, embedding the GPG key fingerprint in the release-notes template
   (`AD12 7976 5093 C675 9CD8 A400 24A7 7461 6F1E 617E`, `release.yml:163`) so consumers
   can verify package signatures against a known-good key without hunting for it
   elsewhere.

4. **`publish-docker`** (`release.yml:181-222`, needs `[approval, build]`) — builds and
   pushes multi-arch (`linux/amd64,linux/arm64`) images for both `Dockerfile` and
   `Dockerfile.chiseled` variants to Docker Hub (`nethermind/nethermind`), tagging
   `:<version>` always and `:latest`/`:latest-chiseled` only when the build is not a
   prerelease (`release.yml:215`).

**Distribution channels actually wired up in this repository's workflows** (cross-checked
against the full `.github/workflows/` listing, not just `release.yml`):

| Channel | Workflow | Mechanism |
|---|---|---|
| GitHub Releases (signed ZIPs) | `release.yml` | GPG-signed archives, `gh release create`/`edit` |
| Docker Hub | `release.yml` (`publish-docker` job) + `publish-docker.yml` (nightly/on-demand) | `docker buildx build --push`, multi-arch |
| Launchpad PPA | `launchpad-ppa.yml` (name inferred from workflow listing; not read in full for this document) | Ubuntu PPA packaging |
| winget / Homebrew | Referenced in `bug_report.md`'s Installation Method field (`GitHub Release/PPA/Homebrew/Docker`) as user-facing install paths; the winget/Homebrew *packaging* automation itself was not located as a workflow in this pass and may be manifest-file-based (e.g. a Homebrew tap repo) rather than living in this repository — flagged as unverified rather than asserted |

**Fukuii verdict — the release *shape* is fundamentally different by design, not a gap.**
Fukuii's release process (`.github/VERSIONING.md`, `.github/workflows/auto-version.yml` +
`release.yml`) is **fully automatic**: every merge to `main`/`master`/`develop`
auto-increments the version, tags it, and `release.yml` cuts a complete release
unattended — SBOM generation (CycloneDX, `release.yml:73-132` in fukuii's own workflow),
and Cosign-signed container images with `provenance: true`/`sbom: true` build-time
attestation (`release.yml:304-360` in fukuii's workflow) are already wired up. This is the
opposite governance model from Nethermind's manual `environment: Releases`
approval-gate — fukuii chose continuous, ungated releases; Nethermind chose deliberate,
human-gated releases. **Correction to a stated assumption:** fukuii's release notes
(`release.yml:384-395`, fukuii's own file) explicitly document that **SLSA provenance
generation via the `slsa-framework` reusable workflow was removed on 2026-04-27** after
persistent `startup_failure` errors — Cosign image signing remains as the baseline
supply-chain attestation, but fukuii should not be described as currently SLSA-attesting;
re-introducing it needs its own validation pass per that comment. If fukuii ever wants a
human-gated *promotion* step (e.g., "auto-release to a `nightly` channel, but promoting to
a `stable` tag needs sign-off"), the `environment: {name: X}` + required-reviewers pattern
demonstrated here is the concrete, reusable mechanism — but that would be a deliberate
model change, not a bug fix, and should be raised as a design decision rather than ported
reflexively.

---

## dotTrace / profiling tooling

**File:** `scripts/dottrace-report.sh` (104 lines, read in full)

**Why it exists:** Nethermind's own `AGENTS.md` states the constraint directly — dotTrace
Reporter XML output is **50-70MB per file**, and "Never load full XML into context." An
agent (or a human) that `cat`s or `Read`s such a file directly either blows a context
window or wastes enormous token budget parsing XML it doesn't need in full. The script
exists purely to make that data *queryable* without ever materializing the whole file.

**How it works:** two subcommands, both implemented as `grep` + `awk` (no XML parser, no
external dependency) —

- **`top <report.xml> [N]`** (default N=30): greps every `<Function ...>` line, extracts
  `FQN` (fully-qualified name), `TotalTime`, and `OwnTime` via a single `sed` capture
  (`dottrace-report.sh:12-22`), keeps only the *maximum* `OwnTime` observation per FQN
  (functions can appear multiple times in a call-tree-shaped report — `awk`'s associative
  array in `extract()` deduplicates by keeping the best), then sorts descending by
  `OwnTime` and prints a fixed-width table of rank/function/OwnTime/TotalTime.
- **`compare <a.xml> <b.xml> [N]`**: runs the same `extract()` against both files, `join`s
  them on the function name, computes `delta = b_own - a_own` and a percentage change, and
  prints two separate top-N tables — **regressions** (B slower than A, sorted by largest
  positive delta) and **improvements** (B faster than A, sorted by largest negative
  delta). This directly answers "did my change make anything worse" without a human
  eyeballing two 70MB files side by side.

**Performance claim, verified plausible by design:** the header comment states "parses
70MB files in <2 seconds" (`dottrace-report.sh:8`) — credible given the entire
implementation is a single `grep` pass piped through `sed`/`awk`, both of which are
line-oriented streaming tools that never hold the full file in memory.

**Fukuii verdict — genuinely reusable idea, needs a fresh implementation, not a literal
port.** Fukuii has no equivalent tool today, and the underlying problem is
language-agnostic: any JVM profiler capable of producing large flame-graph/call-tree
exports (async-profiler's collapsed-stack or JFR output, JProfiler snapshots, VisualVM
`.nps` files) will hit the exact same "don't load 50-70MB of structured text into an
agent's context window" wall. The *pattern* worth porting is: (1) treat the profiler's raw
output as opaque, (2) build a tiny `grep`/`awk` (or `jq`, if JSON) extraction step that
projects only `{function, totalTime, ownTime}`-shaped rows, (3) offer `top` and `compare`
subcommands as the two operations an agent or engineer actually needs. The concrete format
differs entirely for JFR (binary, needs `jfr print` first) vs. async-profiler's
collapsed-stack text format (already line-oriented, even easier than XML) — so this needs
its own script tailored to whichever profiler fukuii's benchmarking sprint standardizes
on, not a transliteration of the XML-specific `sed` pattern here. Track this as a small,
well-scoped follow-up once fukuii's `gas-benchmark`-equivalent profiling workflow exists.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable / Already planned | Reasoning |
|---|---|---|
| `SECURITY.md` | **Port now** | Zero cost, highest leverage-per-line item found; fukuii has none today |
| CodeQL (`csharp` matrix entry) | **Not portable** | No Scala extractor exists for CodeQL — confirmed this session |
| CodeQL (`actions` matrix entry) | **Port now** | Scans workflow YAML, not app code — language-independent, works unmodified |
| Semgrep (as CodeQL substitute) | **Needs design** | First-class Scala support; fukuii has zero Semgrep config today — needs a fresh workflow, not a port |
| Trivy container scan | **Port now** | Scans the built image, not source — 100% language-agnostic; fukuii has no container scanning today |
| `dependency-review-action` | **Port now** (verify ecosystem support first) | Cheap, PR-gated; needs a one-time check that GitHub's Dependency Graph parses fukuii's sbt manifests before trusting the gate |
| CODEOWNERS (full ~56-line, per-.csproj density) | **Not portable at this density** | Fukuii's contributor count (2) doesn't justify per-module granularity yet |
| CODEOWNERS (lightweight, cross-cutting version) | **Already planned** | Two real named maintainers now exist (Cody Burns, Christopher Mercer) — a handful of cross-cutting + consensus-path lines is the right-sized scope, not the deliberate indefinite deferral the current protocol doc still states |
| `CODE_OF_CONDUCT.md` | **Port now** | Standard Contributor Covenant boilerplate, zero cost, fukuii has none |
| CONTRIBUTING.md DOs/DON'Ts + branch-naming | **Port now** (adapt) | Low-cost, high-clarity; branch-naming already partially covered by `worktree-protocol.md` |
| `claude-review/reviewed` required-status-check gate | **Needs design** | Reusable mechanism (branch protection + named status check) but presupposes an automated-review-on-PR workflow fukuii doesn't have yet |
| SPDX file-header convention | **Needs design** | Syntactically compatible with Scala's `//` comments; fukuii has no license-header convention documented today — worth a deliberate decision, not silent adoption |
| Issue-template Desktop/environment fields | **Port the idea, not the template** | Check fukuii's existing `bug_report.md` for network/sync-mode-equivalent fields; close the gap if missing rather than replacing the template |
| PR-template checkbox-driven auto-labeling | **Port the mechanism (upgrade path)** | Fukuii already has `labeler.yml` (path-only); Nethermind's fused checkbox+title+path `pr-labeler.yml` is a strictly richer pattern worth adopting as an enhancement |
| Docker: 4 runtime/build Dockerfile variants (default, chiseled, pgo, diag) | **Not portable as a set** | Fukuii already has its own 7-Dockerfile set serving analogous but not identical purposes (base/dev/bootnode/distroless/mainnet/mordor + default) — no gap here |
| No docker-compose at the Dockerfile level | **Already matches fukuii** | Fukuii also has no compose file alongside its core Dockerfiles (compose files exist only for `ops/barad-dur/`-style testnet/observability stacks, a different layer) |
| `release.yml` manual-approval gate (`environment: Releases`) | **Not portable as a default; reusable if fukuii ever wants gated promotion** | Fukuii deliberately chose fully-automatic release-per-merge; the environment+required-reviewers mechanism is worth knowing about but adopting it would be a release-model change, not a hygiene fix |
| SLSA provenance claim | **Correction, not a finding** | Fukuii's own `release.yml` comments confirm SLSA-framework attestation was removed 2026-04-27 after `startup_failure`s; Cosign signing + buildx `provenance:true`/`sbom:true` remain — don't describe fukuii as currently SLSA-attesting |
| `dottrace-report.sh`'s grep/awk large-report-summarization pattern | **Needs design (fresh implementation)** | Genuinely reusable idea — context-window-safe profiler-output summarization — but fukuii's profiler (whichever is standardized on: async-profiler/JFR/etc.) has a different raw format than dotTrace XML, so this needs a purpose-built script, not a transliteration |
