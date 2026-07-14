# Besu — Repo Hygiene, Security & Governance Patterns

Source: `.claude/repo-references/clients/besu/` (vendored full clone, verified genuine —
a real `git clone` with a populated `.git/` directory, not a summary or partial checkout).
Every claim below cites a specific file and, where confirmed, an exact line range; where a
mechanism could not be verified by direct read (e.g. a linked wiki page or an org-level
GitHub setting not visible in YAML), the text says so explicitly rather than inventing a
citation.

Besu (Hyperledger Besu) is a Java Ethereum execution client and, unlike every other client
vendored in this repo-references tree, a **Linux Foundation Decentralized Trust (LF
Decentralized Trust) project** — meaning it has a legally-chartered, multi-organization
governance structure (`CHARTER.md`), a large named maintainer roster (`MAINTAINERS.md`,
~20 active + ~30 emeritus), and LF-run shared infrastructure (a cross-project security
triage process, a cross-project Code of Conduct enforcement contact). This is qualitatively
different from Nethermind (single-company-backed, small named-owner CODEOWNERS) or
go-ethereum/Erigon (foundation-adjacent but without a formal LLC charter). Besu's
hygiene/governance surface is consequently the largest and most bureaucratically mature of
the four vendored clients, and this document catalogs it exhaustively — including one
genuinely counterintuitive finding (a governance artifact that exists, is referenced by
policy, and is completely empty) that directly informs how much fukuii should invest in
the equivalent file at its own two-maintainer scale.

---

## SECURITY.md & CI security scanning

### SECURITY.md (24 lines, read in full)

```markdown
# Security Policy

## Reporting a Security Bug

If you think you have discovered a security issue in any of the Linux Foundation Decentralized Trust
(LF Decentralized Trust) projects, we'd love to hear from you. We will take all security bugs
seriously and if confirmed upon investigation we will patch it within a reasonable amount of time and
release a public security bulletin discussing the impact and credit the discoverer.

Besu accepts security bugs at two email addresses:

- [security-besu@lists.hyperledger.org](mailto:security-besu@lists.hyperledger.org) is limited to a
  subset of Besu maintainers and LF Decentralized Trust staff. For highly sensitive bugs, this is the preferred
  address.
- [security@hyperledger.org](mailto:security@hyperledger.org) is limited to a subset of maintainers
  and staff of all LF Decentralized Trust projects, and may be viewed by maintainers outside of Besu.

When sending information to either of these emails, please include a description of the flaw and any
related information (for example, reproduction steps, version, and known active use).

The process by which the LF Decentralized Trust Security Team handles security bugs is documented further in
our [Defect Response page](https://wiki.hyperledger.org/display/SEC/Defect+Response) on our
[wiki](https://wiki.hyperledger.org).
```

**Two-tier email disclosure, not GitHub Security Advisories.** Unlike Nethermind (private
draft-advisory flow via GitHub's native UI) or go-ethereum/Erigon's typical GHSA-based
approach, Besu's primary channel is **email**, and it is deliberately two-tiered
(`SECURITY.md:10-16`):

- `security-besu@lists.hyperledger.org` — the narrow, Besu-specific list ("a subset of Besu
  maintainers and LF Decentralized Trust staff"), explicitly called out as "the preferred
  address" for highly sensitive reports.
- `security@hyperledger.org` — a **cross-project** LF Decentralized Trust list, visible to
  "a subset of maintainers and staff of all LF Decentralized Trust projects" — i.e. a
  reporter's disclosure may be seen by people outside the Besu team entirely. This exists
  because LF Decentralized Trust runs shared security infrastructure across all its member
  projects (Besu, Fabric, Indy, etc.), not just Besu-specific tooling.

**No project-local triage process — points to a shared LF wiki page.** The entire
"how do we handle this" mechanism is delegated to a single external link, the
[Defect Response page](https://wiki.hyperledger.org/display/SEC/Defect+Response)
(`SECURITY.md:21-23`) on the org-wide Hyperledger wiki. This is not documented inline in the
repo at all — Besu's `SECURITY.md` is a thin pointer into LF-managed process, not a
self-contained policy. This is the direct structural consequence of operating inside a
multi-project foundation: the triage/CVSS-scoring/patch-embargo workflow is defined once,
centrally, and referenced by every member project rather than duplicated per-repo.

**Timeline commitment:** "patch it within a reasonable amount of time" (`SECURITY.md:7`) —
vaguer than Nethermind's concrete "acknowledge within 24 hours" SLA. No numeric commitment
appears anywhere in this file.

**Fukuii verdict:** **port now**, but adapt the *shape*, not the *mechanism*. Fukuii has no
`SECURITY.md` today (planned this session) and has no LF-style shared triage
infrastructure to point to, so the two-tier-email-list structure isn't directly portable —
fukuii should use a single maintainer-controlled email or GitHub Security Advisories (the
mechanism ported from the Nethermind sibling doc). What *is* directly reusable from Besu's
version: stating plainly that a patch-then-disclose-with-credit process exists, even
without a numeric SLA, is better than the alternative of no `SECURITY.md` at all.

### CI security scanning — three independent workflows

Besu runs three non-overlapping scanners, mirroring the same three-scanner shape documented
for Nethermind (CodeQL, container-image scanning, and a paid SaaS static-analysis platform)
but with materially different configuration in each case.

#### CodeQL — `.github/workflows/codeql.yml` (59 lines, read in full)

```yaml
name: "CodeQL"

on:
  workflow_dispatch:
  schedule:
    # * is a special character in YAML so you have to quote this string
    # expression evaluates to midnight every night
    - cron: '0 0 * * *'

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
        language: ['java']  # Add other languages as needed: 'javascript', 'python', etc.
    steps:
    - name: Checkout repository
      uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
    - name: Set up Java
      uses: actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654 # v5.2.0
      with:
        distribution: 'temurin'
        java-version: 25
    - name: Initialize CodeQL
      uses: github/codeql-action/init@0e9f55954318745b37b7933c693bc093f7336125 # v4.35.1
      with:
        languages: ${{ matrix.language }}
        queries: security-and-quality,security-extended
    - name: setup gradle
      uses: gradle/actions/setup-gradle@39e147cb9de83bb9910b8ef8bd7fff0ee20fcd6f # v6.0.1
      with:
        cache-disabled: true
    - name: compileJava noscan
      run: |
        JAVA_OPTS="-Xmx2048M" ./gradlew --no-scan compileJava
    - name: Perform CodeQL Analysis
      uses: github/codeql-action/analyze@0e9f55954318745b37b7933c693bc093f7336125 # v4.35.1
```

**Trigger:** `workflow_dispatch` **plus** a **daily** cron, `0 0 * * *` (`codeql.yml:16-19`)
— midnight UTC, every night. This is materially more frequent than Nethermind's weekly
Sunday-midnight cadence; Besu runs CodeQL as a nightly job rather than a weekly/tag-triggered
one, and — notably — it is *not* triggered on `push`/`pull_request` at all, so it never gates
a PR directly.

**Matrix languages:** `['java']` only (`codeql.yml:32`) — the boilerplate comment above it
("Add other languages as needed: 'javascript', 'python', etc.") is unmodified default
scaffolding from GitHub's CodeQL setup wizard, confirming this file was generated by GitHub's
own onboarding flow and only lightly customized (Java version pin, Gradle build step) rather
than hand-authored from scratch.

**Query packs:** `security-and-quality,security-extended` (`codeql.yml:49`) — both of
GitHub's standard broader-than-default query bundles, combined; no third-party community
pack (contrast Nethermind's added `githubsecuritylab/codeql-csharp-queries`).

**Build step is real compilation, not autobuild:** `./gradlew --no-scan compileJava`
(`codeql.yml:56-57`) — CodeQL's Java extractor needs an actual compile to trace data flow;
`--no-scan` suppresses Gradle's own build-scan upload (unrelated to CodeQL scanning) to avoid
noise/cost in CI.

**Fukuii verdict — validates the sibling docs' "no Scala extractor" finding from the
Java side.** CodeQL has full first-class Java support (this workflow is close to GitHub's
stock wizard output for a JVM project), which is exactly why Besu can run it essentially
out-of-the-box while fukuii cannot — CodeQL's language matrix has no Scala entry (confirmed
independently in the Nethermind sibling doc against GitHub's supported-languages list, and
reconfirmed here: if CodeQL supported Scala, a matrix like `['java', 'scala']` would be the
obvious analog, and no such combination exists in any vendored client's CodeQL config).
Fukuii's planned Semgrep substitute remains the right call. The **daily** cadence (vs.
Nethermind's weekly) is worth noting as a design point once Semgrep is wired up: a nightly
Semgrep run is cheap enough to justify a Besu-style daily cadence rather than defaulting to
weekly.

#### SonarCloud — `.github/workflows/sonarcloud.yml` (39 lines, read in full)

```yaml
name: SonarCloud analysis

on:
  workflow_dispatch:

permissions:
  pull-requests: read # allows SonarCloud to decorate PRs with analysis results

jobs:
  Analysis:
    runs-on: ubuntu-latest
    steps:
      - name: checkout
        uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
        with:
          fetch-depth: 0  # Shallow clones should be disabled for a better relevancy of analysis
      - name: Set up Java
        uses: actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654 # v5.2.0
        with:
          distribution: temurin
          java-version: 25
      - name: Cache SonarCloud packages
        uses: actions/cache@668228422ae6a00e4ad889ee87cd7109ec5666a7 # v5.0.4
        with:
          path: ~/.sonar/cache
          key: ${{ runner.os }}-sonar
          restore-keys: ${{ runner.os }}-sonar
      - name: setup gradle
        uses: gradle/actions/setup-gradle@39e147cb9de83bb9910b8ef8bd7fff0ee20fcd6f # v6.0.1
        with:
          cache-disabled: true
      - name: Build and analyze
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}  # Needed to get PR information, if any
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
          SONAR_ORGANIZATION: ${{ vars.SONAR_ORGANIZATION }}
          SONAR_PROJECT_KEY: ${{ vars.SONAR_PROJECT_KEY }}
        run: ./gradlew build sonar --continue -Dorg.gradle.parallel=true -Dorg.gradle.caching=true
```

**Confirmed present and wired up.** SonarCloud is a full paid SaaS static-analysis and
quality-gate platform (code smells, duplication, cyclomatic complexity, security hotspots,
test-coverage tracking, PR decoration) — distinct from CodeQL (which is purely
security-vulnerability-focused). `permissions: pull-requests: read` (`sonarcloud.yml:8`)
exists specifically so SonarCloud's bot can post inline PR annotations.

**Trigger: `workflow_dispatch` only** (`sonarcloud.yml:5`) — no cron, no push, no
pull_request trigger in this file. This means SonarCloud analysis is either (a) invoked
manually, or (b) more likely, triggered externally by SonarCloud's own GitHub App
integration/webhook rather than by this workflow file's own `on:` block — SonarCloud's
GitHub App can dispatch this workflow directly via `workflow_dispatch` from its own backend
when it detects a new push, which is a common integration pattern that keeps the *trigger
logic* outside the versioned YAML. This document does not assert which is actually
happening in production — only what is verifiable in the YAML — since org-level app
integrations aren't visible from the repo alone.

`fetch-depth: 0` (`sonarcloud.yml:12`) — a full, unshallowed clone, required because
SonarCloud's "new code" and blame-based ownership analysis needs full git history, not just
the tip commit.

**Fukuii verdict — needs design, not portable as-is.** SonarCloud is a paid third-party
SaaS product requiring an organization account, a `SONAR_TOKEN`, and ongoing subscription
cost — fukuii has no SonarCloud presence and this document does not recommend adopting it
purely on the strength of Besu using it. The two genuinely portable *ideas*, independent of
SonarCloud specifically: (1) `pull-requests: read` scoped narrowly just for PR-decoration
use cases is a good minimal-permission pattern regardless of which SaaS tool consumes it,
and (2) `fetch-depth: 0` is a real gotcha worth remembering for any future fukuii workflow
that does blame/history-aware analysis (e.g. a future code-ownership or churn-analysis
tool) — a shallow clone silently breaks such tools without an obvious error.

#### Container security scan — `.github/workflows/container-security-scan.yml` (48 lines, read in full)

```yaml
name: container security scan

on:
  workflow_dispatch:
    inputs:
      tag:
        description: 'Container image tag'
        required: false
        default: 'develop'      
  schedule:
    # Start of the hour is the busy time. Schedule it to run 8:17am UTC
    - cron:  '17 8 * * *'

jobs:
  scan-sarif:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write

    steps:
      - name: Checkout
        uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
 
      - name: Set image tag
        id: tag
        run: |
          echo "TAG=${INPUT_TAG:-develop}" >> "$GITHUB_OUTPUT"
        env:
          INPUT_TAG: ${{ inputs.tag }} 

      - name: Vulnerability scanner
        id: trivy
        uses: aquasecurity/trivy-action@57a97c7e7821a5776cebc9bb87c984fa69cba8f1 # v0.35.0
        with:
          image-ref: hyperledger/besu:${{ steps.tag.outputs.TAG }}
          format: sarif
          output: 'trivy-results.sarif'

      - name: Upload results
        uses: github/codeql-action/upload-sarif@0e9f55954318745b37b7933c693bc093f7336125 # v4.35.1
        with:
          sarif_file: 'trivy-results.sarif'
```

**Trigger: daily 08:17 UTC cron** (`container-security-scan.yml:11-12`), plus
`workflow_dispatch` with a `tag` input (default `develop`). The comment explaining the odd
minute value is preserved verbatim in the file: "Start of the hour is the busy time.
Schedule it to run 8:17am UTC" (`container-security-scan.yml:10-11`) — a deliberate
off-the-hour offset to avoid GitHub Actions' documented thundering-herd congestion at
`:00`/`:05`/`:15`/`:30` marks, the same anti-collision reasoning Nethermind's Trivy workflow
applies with its own odd cron (`29 19 * * 4`).

**Scans a published registry tag, not a freshly-built local image.** Unlike Nethermind's
Trivy workflow (which does `docker build -t $IMAGE_TAG .` against the repo's own Dockerfile
and scans that ephemeral image), Besu's `image-ref: hyperledger/besu:${{
steps.tag.outputs.TAG }}` (`container-security-scan.yml:39`) pulls an **already-published**
Docker Hub image by tag. This is a fundamentally different scanning target: it validates
what's actually been shipped and is running in the wild (the `develop` branch's nightly
image, by default) rather than validating a PR's not-yet-merged Dockerfile changes
pre-merge. Consequently this workflow provides zero pre-merge signal — a vulnerable base
image or dependency introduced by an in-flight PR would not be caught until after that PR's
image is built and published, then picked up by the next nightly scan.

**`security-events: write`** (`container-security-scan.yml:19`) — the SARIF results feed
directly into the repo's Security tab via `upload-sarif`, exactly like CodeQL's own findings
— container CVEs and static-analysis CVEs are unified in the same GitHub Security UI even
though they come from entirely different tools.

**No severity threshold set** — unlike Nethermind's explicit `severity: CRITICAL,HIGH`,
Besu's Trivy invocation here has no `severity:` key at all (`container-security-scan.yml:36-40`),
meaning Trivy's own default severity set (all levels, UNKNOWN through CRITICAL) is scanned
and surfaced — a noisier SARIF feed than Nethermind's deliberately curated one.

**Fukuii verdict — PORT NOW, but port the pre-merge PR-gated shape (Nethermind's), not
this post-publish shape.** Container scanning is 100% language-agnostic and fukuii has none
today. Of the two vendored patterns, Nethermind's (scan on every PR touching `master`,
severity-filtered to CRITICAL/HIGH) catches problems before they ship; Besu's (scheduled
scan of an already-published tag, unfiltered severity) catches problems only after the fact
and is really a "what's currently exposed in production" audit rather than a merge gate.
Fukuii should adopt Nethermind's pre-merge pattern as the primary gate and can optionally
add Besu's scheduled-published-tag pattern as a secondary, low-priority "what's actually
live and vulnerable right now" check once a container registry publishing pipeline exists
to scan against.

---

## CODEOWNERS — present but empty at 20 maintainers

**File:** `.claude/repo-references/clients/besu/CODEOWNERS.md`, confirmed **0 bytes**
(`wc -c CODEOWNERS.md` → `0`; `wc -l` → `0`). This is not a truncated read or a rendering
artifact — the file genuinely contains zero bytes of content. `git log --oneline --
CODEOWNERS.md` shows exactly one commit touching it: `28205875c5 roll back on ALL-CAPS.md
files for TSC proposal (#376)` — the file was evidently emptied (or renamed/normalized to
the `.md` extension without content) as part of a TSC-driven documentation-format rollback,
and never repopulated with actual per-path ownership rules since.

**The counterintuitive finding, stated plainly:** Besu has ~20 active maintainers
(`MAINTAINERS.md`, table below) plus ~30 emeritus maintainers, a legally chartered
governance body (`CHARTER.md`) with a formal maintainer-voting process for adding/removing
maintainers, and an explicit `CONTRIBUTING.md` reference to "Only the primary approver of a
change should do the merge" (`CONTRIBUTING.md:175`) implying *some* reviewer-assignment
process exists in practice — and yet the one file whose entire purpose is codifying
per-path reviewer routing (GitHub's native `CODEOWNERS` mechanism, which auto-requests
review and can gate merges via branch protection) is empty. Whatever review-routing Besu
actually uses day to day (Discord coordination, ad hoc assignment, "if a PR has gone five
working days without a reviewer emerging, you can ask on Discord" per
`CONTRIBUTING.md:177`) evidently works well enough in practice that nobody has circled back
to populate this file, despite the project being at a scale (20 active maintainers, dozens
of files/modules) where CODEOWNERS would ordinarily be considered close to mandatory
infrastructure.

**This is a strong, independent data point for fukuii's own CODEOWNERS sizing decision.**
The prior fukuii-side reasoning (documented in the Nethermind sibling doc and in
`.agents/protocols/github-workflows.md:41-48`) already concluded that Nethermind's ~56-line,
per-`.csproj`-granularity CODEOWNERS is *not* proportionate to fukuii's current two-maintainer
scale, and that a lightweight version was worth adding once a second real maintainer existed.
Besu's empty-file-at-20-maintainers finding validates the *conservative* end of that
reasoning even more strongly: even a mature, 20-maintainer, LF-governed project has not
found per-path CODEOWNERS granularity worth maintaining in practice. This is not an argument
against fukuii's now-planned lightweight `* @realcodywburns @chris-mercer` CODEOWNERS (a
single blanket line at two-maintainer scale is trivially cheap and still provides the
"someone gets auto-requested for review" benefit) — it is an argument against fukuii ever
feeling pressure to grow that file toward Nethermind's per-module density just because the
maintainer count grows. Besu is proof that even a large LF project didn't find that
investment worthwhile.

---

## Governance: MAINTAINERS.md, CHARTER.md, contributor-call

### MAINTAINERS.md (160 lines, read in full)

Two tables, alphabetically sorted by GitHub handle per an explicit comment
(`MAINTAINERS.md:3`, `<!-- Please keep all lists sorted alphabetically by github -->`):

- **Active Maintainers** (`MAINTAINERS.md:9-29`) — 19 rows, three columns: Name / GitHub /
  LFID (Linux Foundation ID — a separate identity system LF projects use for
  voting/access-control purposes, distinct from a GitHub handle). A comment notes
  `besu-maintainers group has maintainer access to besu repo` (`MAINTAINERS.md:7`) — the
  actual GitHub-repo-level permission grant is a separate org-team construct, not derived
  from this markdown table; this file is the human-readable record, not the source of
  enforcement.
- **Emeritus Maintainers** (`MAINTAINERS.md:32-63`) — 30 rows, same three-column shape —
  people who held maintainer status and stepped back (voluntarily or through the inactivity
  process below) but are still credited.

**Becoming a Maintainer** (`MAINTAINERS.md:65-111`) is a fully specified process, not a
vague aspiration:

- **Contribution bar:** "5 significant changes on code have been authored in this repos by
  the proposed maintainer and accepted (merged PRs)" (`MAINTAINERS.md:78`).
- **Nomination:** requires sponsorship from an existing maintainer, who opens a proposal PR
  modifying the maintainer list; the nominee must publicly accept via a PR comment and
  express willingness to commit for 6+ months (`MAINTAINERS.md:85-89`). The proposal must be
  announced across Discord, the mailing list, and any maintainer/community call
  (`MAINTAINERS.md:90-93`).
- **Voting:** approval by at least 3 current maintainers within two weeks, OR an absolute
  majority (half + 1) of all current maintainers (`MAINTAINERS.md:96-97`) — a
  disjunctive threshold, either path suffices.
- **Veto:** any maintainer may veto, but only with a public, Code-of-Conduct-compliant
  explanation as a PR comment; a veto can be retracted (resetting the voting clock), but "it
  is bad form to veto, retract, and veto again" (`MAINTAINERS.md:99-104`) — a norm against
  gaming the process, not a hard rule.
- **Resolution:** maintainer status is granted either after two veto-free weeks following
  the third approval, or immediately upon an absolute-majority vote, provided no standing
  veto exists (`MAINTAINERS.md:106-111`).

**Removing Maintainers** (`MAINTAINERS.md:113-141`) uses the *identical* voting process as
adding one, triggered by resignation, Code-of-Conduct violation, or inactivity — defined
concretely as "no commits or code review comments for two reporting quarters," with
carve-outs for known long-term leave such as parental or medical leave
(`MAINTAINERS.md:123-127`). Returning from emeritus to active status also reuses the
addition process, but skips the 5-significant-changes contribution bar since it was already
met once (`MAINTAINERS.md:140-141`).

**Modifying MAINTAINERS.md itself** requires a PR agreed upon by 2/3 of current maintainers
(`MAINTAINERS.md:159`) — a supermajority bar specifically for changing the governance
document, higher than the simple-majority-or-3-approvals bar for adding an individual
maintainer.

### CHARTER.md (114 lines, read enough to characterize in full)

The **"Technical Charter (the "Charter") for Besu a Series of LF Projects, LLC"**
(`CHARTER.md:1`) — this is a real legal instrument, not a project README. Besu is
constituted as **"Besu a Series of LF Projects, LLC"**, a Delaware series limited liability
company (`CHARTER.md:5`), meaning Besu-the-project has its own distinct legal-entity
"series" under the LF Projects, LLC umbrella, separate from Hyperledger's other member
projects.

Key structural provisions, section-numbered in the source:

- **§1 Mission and Scope** (`CHARTER.md:7-11`): "develop an Ethereum execution client" —
  deliberately narrow, single-sentence mission statement; scope covers documentation,
  testing, integration, and any artifact aiding development/deployment/operation/adoption.
- **§2 Maintainer Oversight** (`CHARTER.md:13-51`): Maintainers hold full technical
  oversight; the MAINTAINERS file (above) is the canonical maintainer list, and the
  Maintainers collectively may establish an alternative body (e.g. a technical steering
  committee) if documented there. Maintainers may elect a Chair to preside over meetings and
  serve as the primary Besu↔LF-Decentralized-Trust communication contact
  (`CHARTER.md:31`). A detailed responsibilities list (`CHARTER.md:33-51`) includes
  approving sub-project scope changes, establishing security-issue-reporting policy
  (i.e., this charter is the reason `SECURITY.md` exists in its LF-pointer shape), and
  approving/implementing the `CONTRIBUTING` process.
- **§3 Voting** (`CHARTER.md:53-61`): default 51% threshold for decisions not resolvable by
  rough consensus; meeting quorum is 50% of Maintainers; unresolved votes may be escalated to
  the "Series Manager" (an LF Projects administrative role external to Besu itself).
- **§4 Compliance with Policies** (`CHARTER.md:63-73`): binds all Collaborators to LF
  Projects' published policies (e.g. antitrust — the same notice appears verbatim in the
  contributor-call template below), requires 30-days'-notice publication before any policy
  amendment takes effect, and contains a strong open-participation clause: "the Project
  community must not seek to exclude any participant based on any criteria... other than
  those that are reasonable and applied on a non-discriminatory basis" (`CHARTER.md:71`).
- **§5 Community Assets** (`CHARTER.md:75-81`): LF Projects — not Besu, not any individual
  maintainer or employer — holds title to all trademarks, GitHub/social accounts, and
  domain registrations.
- **§7 Intellectual Property Policy** (`CHARTER.md:91-109`): mandates Apache License 2.0 for
  all inbound code contributions plus a DCO sign-off (this is the legal basis underlying
  `DCO.md` below), CC-BY-4.0 for documentation, and a two-thirds-Maintainer-vote exception
  process for any alternative license. Also mandates SPDX short-form license identifiers in
  contributed files (`CHARTER.md:109`) — the legal source of `CONTRIBUTING.md`'s SPDX-header
  enforcement, described below.
- **§8 Amendments** (`CHARTER.md:111-113`): the Charter itself may only be amended by a
  two-thirds Maintainer vote, subject to LF Projects approval.

**Fukuii verdict — N/A at fukuii's current scale, explicitly out of scope, watch for much
later.** `CHARTER.md`, the full `MAINTAINERS.md` voting apparatus, and the
`contributor-call.md` recurring-meeting template (below) are governance artifacts that
presuppose a multi-organization, dozens-of-contributors, legally-chartered foundation
structure. Fukuii today has two named maintainers (Cody Burns/Chippr Robotics LLC,
Christopher Mercer/White B0x Inc.) and is not part of any foundation. None of this is
portable now, and attempting to port it prematurely would add process theater without the
underlying organizational complexity it exists to manage. Flag this explicitly as
**"watch for much later"** — the trigger condition worth tracking is not a specific
maintainer count but a genuine shift toward multi-organization governance (e.g. joining a
foundation, or a second organization beyond Chippr Robotics/White B0x becoming a
structural stakeholder) — at that point, Besu's MAINTAINERS.md voting process (concrete
contribution bar, sponsor + public-accept + timed-veto voting) is a well-specified template
worth revisiting rather than designing from scratch.

### The recurring contributor-call issue template — `.github/ISSUE_TEMPLATE/contributor-call.md` (21 lines, read in full)

```markdown
---
name: Contributor Call Agenda
about: Agenda for the Besu contributor call
title: 'YYYY-MM-DD Contributor Call'
labels: [agenda]
assignees: [jflo]

---

### Housekeeping

- [Antitrust Policy](https://www.linuxfoundation.org/legal/antitrust-policy) notice
- This meeting is being recorded, transcribed, and machine summarized
- Please mute unless speaking
- If you have a question use the raise hand feature

### Agenda Items

1. **[Topic 1]** — [Brief description]
2. **[Topic 2]** — [Brief description]
3. **[Topic 3]** — [Brief description]
```

**A GitHub issue template used as a recurring public-meeting agenda**, not a bug/feature
form — a distinctive pattern: every contributor call gets a fresh issue (via the
`YYYY-MM-DD Contributor Call` title placeholder), pre-labeled `agenda`, pre-assigned to a
specific maintainer (`jflo`, i.e. Justin Florentine, who also appears as the DCO example
signer in `CONTRIBUTING.md:200` and as an active maintainer in `MAINTAINERS.md:18`) who
presumably runs the calls.

**The antitrust-policy notice is not boilerplate** — it is a real legal requirement for
any LF Decentralized Trust project meeting where competitors (multiple companies'
employees) discuss technical roadmap together; LF explicitly mandates this notice at the
start of member-project calls to protect participants and the foundation from antitrust
liability exposure. This is the single clearest artifact in the whole repo of what "real
multi-org governance" concretely requires that a single-company or two-person project
never needs to think about.

**Fukuii verdict — not applicable, watch for much later.** A recurring public
contributor-call cadence with a standing antitrust notice presupposes multiple
organizations' employees participating in shared technical governance calls — categorically
not fukuii's situation. No action; revisit only if fukuii's governance model changes as
described above.

---

## Assisted-By DCO convention for agentic contributions

### DCO.md (21 lines, read in full)

Standard DCO sign-off requirement: legal-name sign-off via `git commit -s`, with a pointer
to a wiki page for GitHub-web-UI users and alias setup (`DCO.md:15-19`). Nothing unusual on
its own — every LF/Apache-style project requires this.

### CONTRIBUTING.md's agentic-contribution guidance (`CONTRIBUTING.md:194-201`, quoted verbatim)

```markdown
### Guidelines for submitting agentic contributions

DCO sign-offs are required for all contributions, and only a human may sign off on a commit. Agents are encouraged to use the `Co-Authored-By` or `Assisted-By` keys in DCO statements, and to include their model name, version, and context size. Example:

```text
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
Signed-off-by: jflo <justin+github@florentine.us>
```
```

**This is the single most forward-looking hygiene artifact found across all four vendored
clients.** Besu is the only client in this repo-references tree that has explicitly written
down a policy for *AI-agent-authored* contributions, and the policy is precise about the
legal chain of accountability: **"only a human may sign off on a commit"** — the DCO
`Signed-off-by` line remains a human legal certification (a human is asserting they have
the right to contribute this code under the Apache 2.0 + DCO terms), while the agent's
involvement is disclosed separately via `Co-Authored-By` or `Assisted-By` trailers that
additionally capture **model name, version, and context size** — metadata with no
equivalent in a purely-human commit trailer convention. The example shows both trailers
coexisting: the agent gets attribution (`Co-Authored-By`), the human maintainer retains
sign-off responsibility (`Signed-off-by`).

**Why context size is called out specifically:** it is a reproducibility/provenance detail
unique to LLM-authored work — the same model at a smaller context window may have had less
of the surrounding codebase in view when generating a change, which is potentially relevant
information for a future maintainer auditing why a change was shaped the way it was. No
other vendored client's contributing docs mention this at all (confirmed: this document's
research pass across Besu found no equivalent guidance in Nethermind, Erigon, or
go-ethereum's contributing docs per the sibling documents' contents).

**Fukuii verdict — genuinely novel, watch for later, needs a deliberate decision before
adoption.** Fukuii's own `.agents/protocols/git-conventions.md` and the harness-level global
commit-workflow instructions already establish a `Co-Authored-By: Claude Sonnet 5
<noreply@anthropic.com>` convention for agent-assisted commits (see this repo's top-level
`CLAUDE.md` commit-workflow guidance), but neither documents Besu's richer
model-name/version/context-size metadata convention, nor the `Assisted-By` alternate key,
nor — because fukuii has no DCO requirement today — the human-sign-off/agent-attribution
separation Besu's policy is built around. If fukuii ever adopts DCO (a real possibility if
external contributors join, since DCO is the standard "no CLA needed" contribution-licensing
mechanism), Besu's exact two-trailer pattern (`Co-Authored-By`/`Assisted-By` +
`Signed-off-by`, human-only sign-off) is a directly reusable template worth adopting
verbatim rather than reinventing. Until DCO adoption is a live question, this is
correctly scoped as "watch for later," not "port now."

---

## CONTRIBUTING.md & PR discipline

`.claude/repo-references/clients/besu/CONTRIBUTING.md`, 257 lines (confirmed via read),
organized into 14 numbered sections with a table of contents (`CONTRIBUTING.md:9-24`). The
most operationally significant sections:

**Contribution workflow** (`CONTRIBUTING.md:50-69`) — a 12-step fork→clone→branch→commit→
test→push→PR→review→merge lifecycle. Step 3 states: "Starting the branch name with the
issue number is a good practice and a reminder to fix only one issue per PR"
(`CONTRIBUTING.md:58`) — branch naming is used as a *behavioral nudge* toward the
one-issue-per-PR discipline, not merely an organizational convention. Step 6 explicitly
names the local pre-push validation command: `./gradlew clean check test`
(`CONTRIBUTING.md:61`) — "helps you be confident that your changes will pass CI once pushed
as a PR," i.e. the exact same command CI itself effectively runs, given as an explicit local
pre-flight recipe.

**What makes a good pull request** (`CONTRIBUTING.md:138-159`) — the most quotable
discipline section:

- **"One pull request, one change"** (`CONTRIBUTING.md:140-143`): "This limits the surface
  area of the change and makes it easier to identify root causes when issues arise." Also
  flags a common accidental-scope-creep cause: an out-of-date fork pulling in commits that
  aren't actually part of the intended change.
- **"Minimize lines of code (LOC) per PR"** (`CONTRIBUTING.md:145-147`): "PRs get
  near-exponentially longer to review as the number of lines of code increases. Ideally,
  keep your changes under **300 LOC**." This is a concrete, numeric target — not "keep PRs
  small" hand-waving — and explicitly frames the review-time cost as non-linear (worse than
  proportional) in PR size, which is the actual underlying justification.
- **"Write meaningful commit messages"** and **"Be responsive"** (`CONTRIBUTING.md:149-155`)
  — the latter includes a concrete anti-stall instruction: mark a paused PR as `Draft` or
  comment explicitly, rather than letting it silently rot until it needs a full rebase.
- **Failing status checks** (`CONTRIBUTING.md:157-159`): if a contributor believes a failure
  is unrelated to their change, leave a comment explaining why; a maintainer re-runs it, and
  a *confirmed* false positive becomes a tracked issue against the CI suite itself — i.e.
  flaky-CI reports have an explicit escalation path rather than being silently re-run and
  forgotten.

**Changelog policy** (`CONTRIBUTING.md:161-169`): a changelog entry is required in the
*same PR* whenever the change introduces a future breaking change/deprecation, a new
feature, or a bug fix with user impact — added under an `## Unreleased` heading, in the
appropriate subsection (Breaking Changes / Additions and Improvements / Bug fixes), as a
single line ending in a link to the PR/issue. Multiple PRs contributing to one larger
feature may share a single changelog entry, attributed to "the feature developer"
(`CONTRIBUTING.md:169`) — an explicit anti-changelog-noise provision for multi-PR features.

**Code reviews** (`CONTRIBUTING.md:171-177`): non-trivial changes require review from
"someone who knows the areas the change touches," and *may* require two reviewers for
non-trivial changes, with the primary reviewer nominating a second when needed
(`CONTRIBUTING.md:173`). "Except for trivial changes, PRs should not be merged until
relevant parties... have had a reasonable chance to look at the PR in their local business
hours" (`CONTRIBUTING.md:173`) — an explicit timezone-fairness norm for a globally
distributed maintainer group (relevant given `MAINTAINERS.md`'s international roster).
"Only the primary approver of a change should do the merge" (`CONTRIBUTING.md:175`) — a
one-person-owns-the-merge-button norm, reducing the risk of two people merging the same PR
via a race or duplicate action.

**Copyright and license / SPDX header enforcement** (`CONTRIBUTING.md:203-233`) — mandatory
Apache-2.0 SPDX header on every new source file, enforced by Gradle's `spotlessCheck` task
(build fails without it) with `spotlessApply` available to auto-insert it
(`CONTRIBUTING.md:210`). The full example header (`CONTRIBUTING.md:218-233`) is a
multi-line Javadoc-style block, not just the one-line SPDX identifier — it includes a
"Copyright contributors to Besu" line and the full Apache-2.0 disclaimer text, wrapping the
short-form `SPDX-License-Identifier: Apache-2.0` identifier at both the top and bottom of
the block.

**Guidelines for non-code and other trivial contributions** (`CONTRIBUTING.md:239-241`, the
section this document was specifically asked to locate) — quoted in full:

> "Please keep in mind that we do not accept non-code contributions like fixing comments,
> typos, or other trivial fixes. Although we appreciate the extra help, managing lots of
> these small contributions is unfeasible and puts extra pressure on our continuous
> delivery systems (running all tests, and so on). Feel free to open an issue pointing out
> any of these errors, and we will batch them into a single change."

This is a deliberate, explicit policy of **declining drive-by typo/comment PRs**, with the
stated rationale being CI capacity/cost (every PR — however trivial — triggers the full
`acceptance-tests`/`reference-tests`/`integration-tests`/CodeQL/SonarCloud suite documented
throughout this file, which is expensive at scale), not reviewer time. The suggested
alternative — file an issue, let maintainers batch several such fixes into one PR — trades
contributor convenience for CI-cost control.

**Fukuii verdict — mixed, mostly port-now.**
- The "one PR, one change" + "minimize LOC, target under 300" discipline: **port now** as
  explicit guidance in `AGENTS.md`'s working-discipline section or a project `CONTRIBUTING.md`
  if fukuii adds one — cheap, high-clarity, and directly reinforces fukuii's existing "small
  batches, then checkpoint" working-discipline rule (`AGENTS.md`'s "Working discipline"
  section) with a concrete numeric anchor.
- Branch-naming-as-behavioral-nudge (issue-number-first): **compatible with, not
  redundant with**, fukuii's existing `wt/<id>` worktree-branch convention and
  `feat/`/`fix/`/`perf/`-prefix convention for direct-branch work
  (`.agents/protocols/git-conventions.md`) — worth layering the "start with the issue/spec
  number" idea on top of the existing prefix convention rather than replacing it.
  Fukuii already runs Spec Kit (`specs/<NNN-feature-name>/`), so an analogous norm would be
  natural: `fix/042-snap-sync-pivot-race` referencing spec 042, for instance.
- Changelog-entry-in-same-PR policy: **port the idea if/when fukuii adds a CHANGELOG.md** —
  currently fukuii's `.github/VERSIONING.md`/auto-version workflow drives fully automatic
  releases (per the Nethermind sibling doc's release-governance finding), which may already
  generate release notes from commit messages rather than needing a hand-maintained
  `Unreleased` section; this needs a design check against fukuii's existing auto-versioning
  pipeline before porting, not an assumption that both models are compatible.
  drop-in.
- SPDX header + `spotlessCheck`-style enforcement: **needs design**, same conclusion as the
  Nethermind sibling doc reached independently — Scala's `//` comment syntax is compatible,
  but fukuii has no license-header convention today; a deliberate decision, not silent
  adoption.
- "Guidelines for non-code and other trivial contributions" (declining typo PRs): **watch
  for later** — this policy exists to protect CI capacity at a scale (dozens of
  contributors, LF-mandated broad test matrix) fukuii is not at; revisit only if fukuii ever
  opens to a wide external-contributor base and CI capacity becomes a real constraint.

---

## Release-checklist-as-issue-template

**File:** `.github/ISSUE_TEMPLATE/release-checklist.md`, 54 lines, read in full — a
30+-item release-manager runbook, encoded as a GitHub issue template rather than a wiki
page or a standalone doc, so each release gets its own trackable, checkbox-driven issue
with a persistent audit trail (who checked what, when).

Full checklist, transcribed with structure preserved:

1. Confirm at least 24 hours prior anything outstanding for release with other maintainers
   on `#besu-release` in Discord.
2. Update `CHANGELOG.md` if necessary and merge a PR for it to `main`; notify maintainers
   about updating the changelog for any in-flight PRs.
3. *(Optional, hotfixes only)* Create a release branch (`release-<version>-hotfix`),
   cherry-pick, and open a PR into `main` purely to observe CI checks pass.
4. On the appropriate branch/commit, create a **calver tag** for the release candidate —
   format example `24.4.0-RC1` — and push it (`git tag`/`git push upstream`).
5. Sign off with the team and announce the tag in `#besu-release`, targeting that tag for
   burn-in.
6. **Consensys staff start burn-in** using the RC tag: a new burn-in fleet stood up
   alongside comparison nodes from the previous release, plus updating existing canary and
   validator nodes to the RC.
7. Seek sign-off for burn-in: pass → proceed; fail → announce the abort in
   `#besu-release`.
8. *(Optional)* Dry-run the release process against a sandbox repo
   (`consensys/protocols-release-sandbox`) using the RC's exact git SHA, tagging and pushing
   a fictitious final version there and manually running its `draft-release.yml` — a full
   rehearsal against a throwaway target before touching the real tag.
9. **Using the same git SHA as the RC**, create the calver tag for the **full release**
   (e.g. `24.4.0`) and push it upstream.
10. Manually run `draft-release.yml` from `main` using the full-release tag — this
    "publishes artefacts and version-specific docker tags but does not fully publish the
    GitHub release so subscribers are not yet notified" — an explicit two-phase publish
    (draft, then a separate publish step) so artifacts exist and can be spot-checked before
    the public-notification point of no return.
11. Verify all `draft-release` workflow jobs went green; check binary SHAs are correct on
    the release page; check artifacts landed in the JFrog Maven repository
    (`hyperledger.jfrog.io`).
12. Update release notes on the GitHub **draft** release and sign off with the team.
13. **IMPORTANT** (called out in bold caps in the source): confirm the tag name is the
    *only* text in the release title — "otherwise it will break the Docker Promote
    workflow" (`docker-promote.yml`) — a documented footgun where free-text in the title
    field breaks a downstream automation that presumably parses the title for the version
    string.
14. **Publish** the draft release, marked as "latest" if appropriate — this is the actual
    public/irreversible step: it notifies subscribed users, marks the release "latest" on
    GitHub, and publishes the `latest`-tag Docker variants.
15. Verify the `docker-promote.yml` workflow went green.
16. Create a Homebrew release PR via a separate repo's (`hyperledger/homebrew-besu`)
    `update-version` workflow — creating the PR manually from the auto-generated
    `update-<version>` branch if the automation didn't fire on its own.
17. **Verify the Homebrew release** once merged, by actually running
    `brew tap hyperledger/besu && brew install besu` on macOS and confirming the version.
18. Delete the burn-in nodes (unless retained for further performance analysis).
19. Social announcements.

**Structural takeaways:** (a) the checklist interleaves *human coordination* steps
(Discord sign-off, burn-in approval) with *mechanical* steps (git tag/push, workflow
dispatch) rather than separating them — reflecting that a real release is a
socio-technical process, not purely a script; (b) it has an explicit two-tag protocol
(RC tag first, full-release tag reusing the *exact same git SHA* later) so the artifact
that gets burned in is bit-for-bit identical to what eventually ships — no rebuild happens
between RC sign-off and final release; (c) it names a **specific documented footgun** (item
13) that would otherwise only be discovered by breaking production automation once; (d) the
"burn-in" step (canary/validator nodes running the RC in a comparison fleet before full
release) is a distinctly execution-client-specific QA gate with no equivalent in typical
web-app release checklists — analogous in spirit to fukuii's own soak-testing needs for
consensus-critical software, though fukuii has no formal burn-in-fleet infrastructure today.

**Fukuii verdict — not portable as a whole (assumes Consensys staff, a burn-in fleet, a
separate homebrew-tap repo, and JFrog artifact hosting fukuii has none of), but the
*mechanism* — a release checklist encoded as a reusable GitHub issue template, so every
release gets a persistent, checkbox-tracked audit record — is directly reusable and cheap.**
Fukuii's release process is already fully automated (per the Nethermind sibling doc's
finding: auto-version + auto-release on every merge, SBOM + Cosign signing, no human
approval gate) which is a different release *philosophy* than Besu's human-gated,
burn-in-tested model — that difference is a deliberate prior choice, not a gap. What fukuii
lacks and could cheaply add: an **issue-template-encoded checklist for the human-judgment
parts that remain** even in an automated pipeline — e.g. "confirm no active P0 regressions
before this auto-release ships," "spot-check the last N auto-releases' SBOM diffs," "verify
Cosign signature on the latest published image" — turning ad hoc pre/post-release sanity
checks into a trackable, repeatable issue-template artifact, without adopting Besu's
human-approval-gated release model wholesale.

---

## Matrix-sharded, timing-balanced reference-test execution

Besu runs **two distinct test-sharding strategies** across two different workflows — this
document initially expected one unified pattern but the actual implementation splits it in
two, and the distinction matters for what fukuii should port.

### `reference-tests.yml` — 4-way round-robin sharding (`.github/workflows/reference-tests.yml`, 100 lines, read in full)

```yaml
env:
  GRADLE_OPTS: "-Xmx6g -Dorg.gradle.daemon=false -Dorg.gradle.parallel=true -Dorg.gradle.caching=true"
  total-runners: 4
...
    strategy:
      fail-fast: true
      matrix:
        runner_index: [1,2,3,4]
    steps:
      ...
      - name: execute generate reference tests
        run: ./gradlew ethereum:referencetests:referenceTestClasses -Dorg.gradle.parallel=true -Dorg.gradle.caching=true
      - name: list test files generated
        run: find ethereum/referencetests/build/generated/sources/reference-test -name "*.java" | sort >> filenames.txt
      - name: list test files written
        run: find ethereum/referencetests/src/reference-test/java -name "*.java" | sort >> filenames.txt
      - name: Split tests
        run: ./.github/workflows/splitList.sh filenames.txt ${{env.total-runners}}
```

Each of the 4 matrix jobs (`runner_index: [1,2,3,4]`) **independently regenerates the full
test-class list** (both codegen'd reference tests from `ethereum:referencetests:
referenceTestClasses` and hand-written ones), then calls a tiny (`splitList.sh`, 9 lines of
logic) **round-robin** splitter:

```bash
N=$2 # Number of groups
i=0 # Initialize counter
cat $1 | while read line; do
  echo "$line" >> "group_$((i % N + 1)).txt"
  let i++
done
```

This is deliberately the simplest possible sharding strategy: line 1 → group 1, line 2 →
group 2, line 3 → group 3, line 4 → group 4, line 5 → group 1 again, and so on
(`i % N + 1`). It has **no awareness of per-test execution time** — it assumes the input
list's ordering (alphabetical, from `find ... | sort`) distributes runtime evenly enough in
aggregate across 4 shards, which is a much weaker assumption than the timing-based approach
below, but costs nothing to compute and needs no historical data.

**The safety check this document was specifically asked to confirm** —
`reference-tests.yml:66-74`:

```yaml
- name: verify test file count matches
  run: |
    listed=$(wc -l < filenames.txt | tr -d ' ')
    actual=$({ find ethereum/referencetests/build/generated/sources/reference-test -name '*.java'; find ethereum/referencetests/src/reference-test/java -name '*.java'; } | wc -l | tr -d ' ')
    echo "Test files listed: $listed, Test files on disk: $actual"
    if [ "$actual" -ne "$listed" ]; then
      echo "::error::Test file count mismatch: filenames.txt has $listed entries but $actual files found on disk"
      exit 1
    fi
```

This runs *after* the shard's tests execute, re-`find`s the actual `.java` files on disk,
and compares the count against the `filenames.txt` list that was sharded at the start of the
job. Its purpose: catch a codegen non-determinism or a race where the discovered test-file
count silently drifts between the moment tests were listed/sharded and the moment they
actually ran (e.g. a build-cache issue regenerating a different file set on a re-run) —
without this check, a shard could silently execute *fewer* tests than intended and the
workflow would still report green. Combined with `strategy.fail-fast: true`
(`reference-tests.yml:30`), a single shard's file-count mismatch fails the whole matrix
immediately rather than letting three other shards complete first.

**A single required-status-check aggregator job**, `reftests-passed`
(`reference-tests.yml:81-99`), depends on all 4 `runner_index` shards and fails the overall
check if *any* shard result was `failure`, `cancelled`, or `skipped` — this is the pattern
that lets branch protection require exactly **one** named status check
(`reftests-passed`) rather than needing branch protection rules to separately track 4
dynamically-numbered matrix job names (which GitHub's branch-protection UI handles poorly
for matrix builds).

### `acceptance-tests.yml` — 14-way JUnit-timing-aware bin-packing (`.github/workflows/acceptance-tests.yml`, 118 lines, read in full)

This is the workflow that actually uses the more sophisticated `splitTestsByTime.sh`
script this document was asked to investigate — `total-runners: 14`
(`acceptance-tests.yml:19`), a substantially wider shard count than reference-tests' 4,
reflecting a much larger and more execution-time-variable acceptance-test suite (full
node-startup, multi-node interaction tests, not pure unit-style reference-test vectors).

**Historical timing data is fetched from the previous run before sharding**
(`acceptance-tests.yml:53-61`):

```yaml
- name: Get acceptance test reports
  uses: dawidd6/action-download-artifact@e7466d1a7587ed14867642c2ca74b5bcc1e19a2d
  continue-on-error: true
  with:
    branch: main
    workflow: update-test-reports.yml
    name: acceptance-test-results
    path: tmp/junit-xml-reports-downloaded
    if_no_artifact_found: ignore
```

A **separate workflow**, `update-test-reports.yml` (triggered on every push to `main`),
downloads the acceptance-test JUnit XML artifacts from the latest *merged* PR's
`acceptance-tests.yml` run and re-uploads them under a stable artifact name
(`acceptance-test-results`) — this is the mechanism that makes "yesterday's timing data"
discoverable by name for tomorrow's sharding run, since GitHub Actions artifacts are
normally scoped to the run that produced them. `continue-on-error: true` +
`if_no_artifact_found: ignore` means a cold-start (no prior timing data — e.g. a brand new
CI setup) degrades gracefully rather than failing the whole job.

**`splitTestsByTime.sh` (129 lines, read in full) implements greedy longest-processing-time
bin-packing**, not round-robin:

1. Extracts per-test-class execution time from the downloaded JUnit XML via `xmlstarlet`
   (`splitTestsByTime.sh:24`), producing a `timing.tsv` of `(time, classname, module)`
   tuples.
2. Sorts descending by time (`sorted=($(sort -nr tmp/timing.tsv))`, `splitTestsByTime.sh:27`)
   — this is the classic greedy bin-packing heuristic: place the *largest* items first, each
   into whichever bin currently has the smallest running total, which provably keeps the
   final max-bin-size within a bounded factor of optimal.
3. For each test (largest-first), finds the shard (`idx_min_sum`) with the current smallest
   cumulative time and assigns the test there, updating that shard's running sum
   (`splitTestsByTime.sh:58-77`).
4. **New tests** (present in the current test-dry-run list but absent from historical
   timing data — i.e. no prior run to learn from) are appended round-robin across shards
   *after* all timed tests are placed (`splitTestsByTime.sh:81-90`) — a graceful fallback for
   tests with no history, rather than crashing or arbitrarily assigning them all to shard 0.
5. **Deduplication**: a test seen twice in the timing data (possible since JUnit reports can
   list retried/rerun tests) is only placed once, tracked via `tmp/processedTests.list`
   (`splitTestsByTime.sh:49-53`).
6. Finally, the assigned tests for the requested `$SPLIT_INDEX` are grouped by their Gradle
   module directory and re-emitted as `:module:path:test --tests A --tests B ...` Gradle CLI
   arguments (`splitTestsByTime.sh:100-128`) — because a single shard's tests may span
   multiple Gradle modules, and each module needs its own `:module:test` task invocation
   with only that module's subset of `--tests` filters.

**`reportSlowestTests.sh` (72 lines, read in full, actually a Python script despite the
`.sh` extension)** — invoked from a *different* workflow, `pre-review.yml:169`
(`python3 .github/workflows/reportSlowestTests.sh 10`), not from either test-sharding
workflow directly. It parses all downloaded `TEST-*.xml` JUnit reports (unit tests, in this
invocation — `pre-review.yml`'s job downloads `unit-test-results` artifacts,
`pre-review.yml` context around line 158), sorts by per-suite `time` descending, and emits a
collapsible Markdown `<details>` table of the top-N slowest test classes directly into the
GitHub Actions **step summary** (`GITHUB_STEP_SUMMARY`) — visible right on the workflow-run
page without downloading any artifact. This is a distinct, smaller reporting utility from
the sharding scripts above — it consumes the same kind of JUnit XML data but produces a
human-facing summary rather than driving shard assignment.

### Fukuii's current state, for contrast

Fukuii's `.github/workflows/ethereum-tests-nightly.yml` runs the entire EF test suite
(`sbt "IntegrationTest / testOnly com.chipprbots.ethereum.ethtest.*"`) as a **single
unsharded job**, `timeout-minutes: 60`, on a nightly cron — no matrix, no shard count, no
timing data, no per-shard parallelism at all. As the suite grows, this job's wall-clock time
grows linearly and eventually risks hitting the 60-minute timeout with no mitigation besides
raising the timeout further.

**Fukuii verdict — needs design, high leverage, directly relevant to the known
single-job-timeout risk.** Two genuinely different sharding strategies are available to
draw from:

- **Besu's `splitList.sh` round-robin** — trivially simple (9 lines of bash), needs zero
  historical data, and is "good enough" if fukuii's ethereum/tests suite has roughly uniform
  per-test-class execution time (plausible for many EF test-vector-driven test classes,
  since each is often driven by a similarly-sized generated fixture set).
- **Besu's `splitTestsByTime.sh` bin-packing**, fed by a companion `update-test-reports.yml`-
  style workflow that persists JUnit timing artifacts across runs — meaningfully more
  effective if fukuii's test classes have high execution-time variance (plausible if some
  ethereum/tests categories, e.g. large state tests, run far longer than others), at the
  cost of needing a second small workflow to persist and hand forward timing data, plus
  `xmlstarlet` (or a JVM/Scala equivalent parser) in CI.

Either approach requires first splitting fukuii's current single `testOnly` invocation into
a matrix, which is itself the larger design question — this document does not prescribe
runner count or which of the two scripts to adapt, since that depends on measuring fukuii's
actual current per-test-class timing distribution first (a straightforward one-time
diagnostic: run the suite once with `-Dsbt.log.noformat` or equivalent JUnit-XML output
enabled and inspect the resulting time spread before choosing round-robin vs. bin-packing).
The **file-count verification safety check** (`reference-tests.yml:66-74`) is worth porting
regardless of which sharding strategy is chosen — it is a cheap, sharding-strategy-agnostic
guard against a matrix silently running fewer tests than intended, which is a background
risk fukuii's current single-job design doesn't need but any matrix conversion would
introduce. Note also Erigon's sibling doc documents a third, more sophisticated
manifest-driven sharding approach (`tools/eest-spec-shards.yml`) — worth comparing all three
patterns side by side once fukuii commits to sharding its own EF suite, rather than picking
the first one read.

---

## Docker (Goss-based container testing)

**Single production Dockerfile**, `docker/Dockerfile` (86 lines, read in full) — a
two-stage build: an Eclipse Temurin JRE base stage (`java-base`, pinned by digest) copied
into a final Ubuntu 24.04 stage (also pinned by digest). Runs as a non-root `besu` user
created with a fixed UID 1000 (`Dockerfile:20-24`, explicitly commented: "we need 1000 for
besu... Ensure we use a stable UID for besu, as file permissions are tied to UIDs" — i.e.
volume-mounted data directories from a prior container version must retain consistent
ownership across upgrades). Ships an embedded Pyroscope continuous-profiling Java agent
(`pyroscope.jar`, downloaded via `ADD` from a pinned GitHub release URL,
`Dockerfile:35`) alongside `docker/pyroscope.properties` (20 lines, read in full — sane
production defaults: 10ms profiling interval, JFR format, 15s upload interval, `itimer`
event type) — disabled by default (`BESU_OPTS=-javaagent:...` must be set explicitly to
activate it) but baked into every image so enabling production profiling needs no image
rebuild. `HEALTHCHECK` is a simple PID-file-existence check
(`Dockerfile:72`, `[ -f /tmp/pid ]`) rather than an HTTP probe against the RPC port.

**Container validation via Goss** (`docker/tests/README.md`, 4 lines, read in full): "Besu
Docker images are validated using Goss https://github.com/goss-org/goss. Run `./gradlew
testDocker` to download Goss scripts and execute docker tests." Goss is a
YAML-declarative server-validation tool (assert "port X is listening," "process Y is
running," "file Z has these permissions") — lighter-weight than a full integration-test
framework for the narrow question of "does the container actually start up correctly and
expose the right surface." The actual test driver, `docker/test.sh` (67 lines, read in
full), runs two named Goss scenarios against a locally built image: **test 01** ("normal
startup with ports opened," starting Besu with `--rpc-http-enabled --rpc-ws-enabled
--graphql-http-enabled` and validating listening ports) and **test 02** ("directory
permissions," mounting a named volume at `/var/lib/besu` and validating the non-root `besu`
user can actually write to it) — both produce JUnit-format output
(`GOSS_OPTS="... --format junit"`, `test.sh:40`) so they slot into the same JUnit-artifact
pipeline as every other test category in this repo.

A separate, later-pipeline-stage workflow, `container-verify.yml` (60 lines, read in full)
— triggered manually post-release, matrixed across `linux/amd64`/`linux/arm64` and the
`latest` tag alongside the just-released version tag — starts an actual published
`hyperledger/besu:<tag>` container and runs `BesuContainerVerify.sh` (a log-grep-based
startup-success poller with retry/sleep, checking for a specific log message with up to 10
retries at 8s intervals) plus, per its `verify-latest-version` input, a check that the
`latest` tag genuinely matches the intended release version — catching a class of bug where
the `latest` Docker tag drifts from the actual latest GitHub release.

**Fukuii verdict — the Goss pattern is a genuinely new idea worth evaluating; the rest is
not portable as a set (fukuii already has its own differently-shaped Dockerfile family).**
Goss-based container-startup validation (declarative "is the right port open, is the
right user able to write here" assertions, JUnit-output-compatible) is a lighter-weight
alternative to writing bespoke shell-script container smoke tests, and fukuii has no
equivalent today — worth a scoped follow-up evaluation once fukuii's own container-image
work stabilizes, independent of anything else in this document. The specific two Goss
scenarios (port-open check, volume-permission check) map directly onto real fukuii
concerns (RPC port exposure, RocksDB datadir permissions under a non-root container user)
if fukuii's Dockerfiles run as non-root — worth checking as a quick gap-audit. The
Pyroscope-embedded-but-disabled-by-default profiling agent is a reusable idea independent
of Goss — bake a profiling agent into the image, gate activation behind an env var, avoid a
rebuild-to-profile cycle — relevant to fukuii's broader profiling/benchmarking ambitions
(see the Nethermind sibling doc's `dottrace-report.sh` discussion for the JVM/`.NET`
profiling-tooling angle generally).

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable / Watch for much later (LF-scale) | Reasoning |
|---|---|---|
| `SECURITY.md` (two-tier LF email + external wiki pointer) | **Port the concept, adapt the mechanism** | Fukuii has none today; the two-tier-email-list + shared-triage-wiki shape presupposes LF infrastructure fukuii doesn't have — use a single maintainer email or GHSA (per the Nethermind sibling doc), but the underlying "patch-then-credit-then-disclose" commitment is worth stating explicitly even without an SLA |
| CodeQL (`java` matrix, daily cron) | **Not portable (validates existing finding)** | No Scala extractor exists for CodeQL — Besu's clean, near-default Java config from the Java side reconfirms the gap is language-support, not configuration effort; daily-vs-weekly cadence choice is worth reusing once Semgrep exists |
| SonarCloud | **Needs design, not recommended purely on precedent** | Paid SaaS, requires org account + token; `pull-requests: read` minimal-permission pattern and `fetch-depth: 0` gotcha are worth remembering independent of adopting SonarCloud itself |
| Container security scan (Trivy, scheduled, published-tag target) | **Port a variant now** | Container scanning is language-agnostic and fukuii has none; prefer Nethermind's pre-merge-PR-gated pattern as the primary gate, optionally add Besu's scheduled-published-tag pattern later as a secondary "what's live right now" audit |
| CODEOWNERS (present, 0 bytes, at 20 maintainers) | **Strong validation for fukuii's lightweight approach** | A mature 20-maintainer LF project has not found per-path CODEOWNERS worth maintaining in practice — reinforces that fukuii's now-planned lightweight `* @realcodywburns @chris-mercer` blanket line is the right scope, and there's no pressure to grow it toward Nethermind's per-module density as maintainer count grows |
| `MAINTAINERS.md` full voting/nomination/removal process | **Watch for much later (LF-scale)** | Presupposes dozens of contributors and a formal maintainer-promotion pipeline; not applicable at fukuii's two-maintainer scale — revisit only on genuine multi-org governance shift |
| `CHARTER.md` (LF Decentralized Trust legal charter) | **Not portable, N/A** | A legal instrument for a foundation-chartered LLC series; fukuii is not part of a foundation |
| `contributor-call.md` template (antitrust-notice recurring meeting agenda) | **Watch for much later (LF-scale)** | Antitrust notice requirement only applies when multiple organizations' employees jointly govern technical direction on calls — not fukuii's situation |
| `Assisted-By`/`Co-Authored-By` DCO convention with model metadata | **Watch for later, needs a deliberate decision** | Genuinely novel among all 4 vendored clients; fukuii's existing `Co-Authored-By: Claude Sonnet 5` convention lacks Besu's model-version/context-size metadata and human-only-sign-off separation — directly reusable template if/when fukuii adopts DCO, not before |
| CONTRIBUTING.md: "one PR one change" + "target <300 LOC" | **Port now** | Cheap, reinforces fukuii's existing "small batches" working-discipline rule with a concrete numeric anchor |
| CONTRIBUTING.md: branch-name-starts-with-issue-number nudge | **Port now, layered** | Compatible with fukuii's existing `wt/<id>`/`feat:`-prefix convention; natural fit with Spec Kit's `specs/<NNN>/` numbering |
| CONTRIBUTING.md: changelog-entry-in-same-PR policy | **Needs design** | Check compatibility with fukuii's existing fully-automatic auto-version/release pipeline before assuming a hand-maintained `Unreleased` section is still the right model |
| SPDX header + `spotlessCheck`-style enforcement | **Needs design** | Same open question independently reached in the Nethermind sibling doc — Scala-compatible syntax, but no license-header convention decided yet |
| "Guidelines for non-code/trivial contributions" (declining typo PRs) | **Watch for later** | Protects CI capacity at a contributor scale fukuii isn't at yet |
| Release-checklist-as-issue-template (30+ items, calver, burn-in, Homebrew) | **Port the mechanism, not the content** | Fukuii's release model is fully automated (different philosophy, not a gap); the "checklist encoded as a reusable issue template" idea is cheap and reusable for whatever human-judgment steps remain even in an automated pipeline |
| `reference-tests.yml` 4-way round-robin sharding (`splitList.sh`) | **Needs design** | Simple, needs no historical data; a candidate for fukuii's single-job `ethereum-tests-nightly.yml` if per-test-class timing is roughly uniform — needs a one-time timing measurement first |
| `acceptance-tests.yml` 14-way timing-based bin-packing (`splitTestsByTime.sh` + `update-test-reports.yml`) | **Needs design** | More effective if timing variance is high; costs a second persistence workflow plus an XML-timing-extraction step; compare against Erigon's manifest-driven sharding (sibling doc) before choosing |
| File-count verification safety check (`reference-tests.yml:66-74`) | **Port now, sharding-strategy-agnostic** | Cheap guard against a matrix silently running fewer tests than intended; worth adding to whichever sharding approach fukuii picks |
| `reportSlowestTests.sh` step-summary slow-test table | **Port now** | Small, self-contained, language-agnostic (parses JUnit XML); directly usable in fukuii's own CI once JUnit-format test output exists for the target suite |
| Goss-based container-startup validation | **Needs design, genuinely new idea** | No fukuii equivalent today; port-worthy pattern (declarative port/permission assertions, JUnit output) independent of Besu's specific Dockerfile shape |
| Pyroscope-embedded-but-disabled profiling agent in the production image | **Needs design** | Reusable idea (bake a profiler in, gate by env var, avoid rebuild-to-profile) relevant to fukuii's broader profiling ambitions; needs its own JVM-profiler choice, not a literal port |
| Single production Dockerfile + Goss test suite (as a Dockerfile-family shape) | **Not portable as a set** | Fukuii already has its own multi-Dockerfile family serving different purposes; no gap here beyond the Goss idea above |
