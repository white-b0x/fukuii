# Scala/JVM security-automation landscape (2026)

_Researched 2026-07-14 by sentinel, prompted by the RX-setup-06 finding that the rebuild plan's
`setup.md` attributed "Semgrep" to nethermind as if it were a vendored-client precedent — no
vendored reference client (besu, core-geth, erigon, go-ethereum, nethermind, reth) runs Semgrep.
This doc answers the follow-up question RX-setup-06 left open: what does the **Scala/JVM
ecosystem itself** actually use for automated code-security in 2026, so fukuii's choice is
grounded in ecosystem practice rather than being invented from nothing._

**Four separate concerns, four different answers — do not conflate them:**

| Concern | What it catches | 2026 ecosystem answer |
|---|---|---|
| SAST (static app security testing) | code-level vulnerability *patterns* — injection, unsafe deser, secrets-in-code, crypto misuse | **Semgrep CE** (GA for Scala, community ruleset — thin, not none) |
| SCA (software composition analysis) | known-CVE *dependencies* | **GitHub Dependency Graph** via `scalacenter/sbt-dependency-submission` → native Dependabot security alerts; **Trivy** for container images |
| Dependency-*update* automation | keeping deps current (distinct from CVE alerts) | **Scala Steward** (ecosystem-native) — Dependabot's sbt version-updates (shipped 2026-05-26) is a young alternative, not yet the incumbent |
| Secrets scanning | hardcoded credentials/keys | **Gitleaks** (+ GitHub secret scanning/push protection as the platform layer) — language-agnostic, no Scala-specific angle |

---

## 1. Why CodeQL is not the answer for Scala source

Confirmed by direct check: CodeQL's supported-language extractor list is Java/Kotlin, C#, Go,
Rust, JavaScript/TypeScript, Python, Ruby, Swift, C/C++ — **no Scala extractor exists**. This is
consistent across 2026 GitHub documentation. GitHub's 2025-04-22 GA of "CodeQL for GitHub Actions
workflows" analyzes **workflow YAML** (script-injection, permission misconfig in `.yml` files),
not Scala source — an easy thing to conflate, since it means "CodeQL" now legitimately appears in
a Scala repo's toolchain, just not for the Scala code itself.
[GitHub Changelog: Actions workflow CodeQL GA](https://github.blog/changelog/2025-04-22-github-actions-workflow-security-analysis-with-codeql-is-now-generally-available/)

This is why besu's daily-CodeQL(Java) precedent genuinely doesn't transcribe to a Scala 3 codebase
— it isn't a Scala-vs-fukuii gap, it's a tooling-category gap that has to be filled some other way.

## 2. SAST: Semgrep — GA engine, community-tier ruleset (not none, not Pro-complete)

Verified directly against Semgrep's own docs (2026):

- **Semgrep Code (SAST):** Scala is listed **Generally Available**, with cross-function dataflow
  analysis and "Community rules." [docs.semgrep.dev/languages/scala](https://docs.semgrep.dev/languages/scala)
- **Semgrep Supply Chain (SCA):** also GA for Scala via Maven-family package managers, with
  reachability analysis (Semgrep's dataflow-reachability expansion reduces supply-chain
  false-positives up to 98% per Semgrep's own 2024 blog, extended to Scala/Swift). [Semgrep product update](https://semgrep.dev/products/product-updates/ga-support-for-multiple-languages/)
- **The caveat that matters for fukuii:** the free/open-source `semgrep` CLI ships `p/default` +
  `p/scala` — **community-contributed** rules only. Semgrep's own docs state framework-specific
  **Pro rules will fail to return findings on Semgrep CE** — full OWASP-Top-10-style coverage
  requires the paid Pro tier (`semgrep login && semgrep ci`). [docs.semgrep.dev/languages/scala](https://docs.semgrep.dev/languages/scala)
- **No vendored reference client runs Semgrep** (confirmed by `grep -riln semgrep` across all six
  vendored clients — empty). Nothing to transcribe; a fukuii Semgrep CI config must be **authored**
  from Semgrep's own `p/scala` + `p/default` registry rulesets, not copied from a client.

**Net assessment:** Semgrep is a legitimate, free, GA-for-Scala choice — but it is a **fukuii-first
adoption of an ecosystem-standard tool**, not "the Scala ecosystem's incumbent SAST." No major
Scala project inspected below runs it in CI (see §5). Treat it as: technically sound, thin
out-of-the-box ruleset, config must be hand-built.

## 3. Alternatives considered and rejected

| Tool | Scala 3 status (2026) | Verdict |
|---|---|---|
| **SonarQube (official SonarSource Scala analyzer, SLang-based)** | Actively maintained, built into all SonarQube Server/Cloud editions, post-Nov-2024 releases under SSALv1 (source-available, not pure OSS) | Rejected for fukuii: requires standing up/paying for a SonarQube server (Server/Cloud), a heavier infra lift than a CI-only tool; the **community** `sonar-scala`/`SonarSource/sonar-scala` plugin is stale (targets SonarQube 9.4/8.9 LTS and Scala 2.11–2.13 only — no Scala 3). [SonarSource/sonar-scala](https://github.com/SonarSource/sonar-scala), [sonar-scala/sonar-scala](https://github.com/sonar-scala/sonar-scala) |
| **Snyk (Code=SAST, Open Source=SCA)** | Scala supported for both Snyk Code and Snyk Open Source; sbt support via the legacy `sbt-dependency-graph` invocation (the sbt-1.4+ native call path is explicitly **not** compatible with Snyk); June-2026 update fixed sbt-custom-config scanning bugs | Rejected as primary: commercial product, per-seat/scan pricing, and the sbt integration has a documented compatibility footgun (must use legacy `addSbtPlugin()` path, not sbt 1.4+'s built-in invocation). [Snyk support: sbt legacy method](https://support.snyk.io/hc/en-us/articles/360004167317-How-to-install-the-SBT-dependency-graph-plugin-to-test-Scala-projects-with-Snyk-CLI) |
| **OWASP Dependency-Check via `sbt-dependency-check`** | Original `albuch/sbt-dependency-check` is **archived/unmaintained**; forks (`nMoncho/sbt-dependency-check`, sbt ≥1.9) are community-maintained continuations | Rejected in favor of Trivy (plan's existing SCA pick for container images) + GitHub Dependency Graph/Dependabot alerts (below) for source-level SCA — avoids depending on an unmaintained-then-forked plugin lineage for a security-critical gate. |
| **Scapegoat** (vendored, `.claude/repo-references/scapegoat`) | Actively maintained (sksamuel/scapegoat, HEAD 2026-06-18, cross-built to Scala 3.8.4 and 2.12/2.13) | **Not a security tool** — it's a compiler-plugin code-quality linter. Its ~110 inspections are correctness/style (null handling, exception-swallowing, shadowing, format-string bugs) with a few security-*adjacent* ones (`CatchNpe`, `CatchThrowable`, `SwallowedException`, `InvalidRegex`) but **no** SQLi/deserialization/secrets/crypto-misuse detectors. Keep it for code quality (already in scope elsewhere in the plan); it does not satisfy F12/R11. |

## 4. SCA + dependency-update: what the ecosystem actually runs

Two GitHub changelog entries from 2026 change the picture materially versus what an
older/generic playbook would assume:

- **`scalacenter/sbt-dependency-submission`** (Scala Center-maintained GitHub Action, v3.2.3,
  2026-05-28, sbt ≥1.5) submits the sbt build's resolved dependency graph to GitHub's
  Dependency Submission API. This populates the repo's **Dependency Graph** (Insights tab) and —
  once Dependabot is enabled — **native Dependabot security alerts fire for sbt dependencies**,
  the same mechanism npm/pip/cargo repos get automatically from their native lockfiles.
  [scalacenter/sbt-dependency-submission](https://github.com/scalacenter/sbt-dependency-submission)
- **Dependabot version updates for sbt** shipped 2026-05-26 — `dependabot.yml` can now declare
  `package-ecosystem: "sbt"` and get version-bump PRs the same way it already did for npm/pip.
  This is explicitly **version updates only, not security updates** in this initial release — the
  security-alert path still runs through the Dependency Graph submission above, not through this
  ecosystem entry directly. [GitHub Changelog 2026-05-26](https://github.blog/changelog/2026-05-26-dependabot-version-updates-now-support-the-sbt-ecosystem/)
- **Scala Steward** remains the ecosystem-native updater and is what real Scala projects run in
  practice (see §5) — it groups related bumps, supports migration-aware upgrades (via
  scalafix-based migrations), and predates Dependabot's sbt support by years. The two are
  complementary rather than exclusive: "Scala Steward as a preventive tool… Dependabot as a
  monitoring tool" is the framing the ecosystem itself uses.
  [scala-steward-org/scala-steward](https://github.com/scala-steward-org/scala-steward)

**Trivy** (already in the plan for container-image scanning, following nethermind's pre-merge
pattern) stays exactly where it is — it is a container/IaC scanner, not a source-dependency SCA
tool for sbt; it doesn't compete with the above, it's orthogonal.

## 5. Evidence: what major Scala 3 / sbt-native projects actually run

Checked directly (`.github/workflows/` listing + file contents, 2026-07-14):

| Project | CodeQL? | Semgrep? | SonarQube? | Snyk? | Dependency Graph submission | Scala Steward |
|---|---|---|---|---|---|---|
| `apache/pekko` | No | No | No | No | **Yes** — `scalacenter/sbt-dependency-submission@d84eef4…` (SHA-pinned) | No (ASF project — governance precludes bot auto-merge patterns) |
| `sbt/sbt` | No | No | No | No | **Yes** — `dependency-graph.yml` present | No |
| `scala/scala3` | No | No | No | No | **Yes** — `dependency-graph.yml` present | No |
| `typelevel/cats` | No | No | No | No | Not found in the two-workflow (`ci.yml`, `clean.yml`) CI | No |
| `http4s/http4s` | No | No | No | No | Not found in the two-workflow CI | No |
| `zio/zio` (series/2.x) | No | No | No | No | Not found | **Yes** — dedicated `scala-steward.yml`, daily cron, GitHub App auth |

**Reading this table:** none of the six inspected — including sbt itself and the Scala 3 compiler
repo — run a dedicated SAST tool (Semgrep/SonarQube/Snyk/CodeQL) on their own Scala source in CI.
The two things that *are* ecosystem-standard and directly observed in production: (1) the
`scalacenter/sbt-dependency-submission` action feeding GitHub's native Dependency Graph/Dependabot
alerts, and (2) Scala Steward for automated dependency-update PRs. SAST-for-Scala-specifically is
genuinely an underserved gap across the ecosystem, not just in fukuii — which validates (rather
than undermines) the RX finding: Semgrep is the best available answer, but it is undeniably
fukuii adopting a general-purpose tool into a gap the Scala ecosystem hasn't filled natively,
not fukuii picking up something the ecosystem already standardized on.

## 6. Secrets scanning

Language-agnostic, so no Scala-specific angle — noted for completeness since F12/R11 bundles it:

- **Gitleaks** — single static binary, pattern/entropy matching over diffs or full history, no
  network calls, has a maintained GitHub Action, emits SARIF (native GitHub code-scanning
  integration). The standard choice for pre-commit + CI blocking.
- **TruffleHog** — verification-first (confirms a matched credential is *live* via a provider API
  call before flagging), classifies 800+ secret types; better for scheduled/history-wide scans
  than commit-blocking latency.
- **GitHub secret scanning + push protection** — the platform layer; free for public repos,
  available via GitHub Advanced Security for private repos.
- 2026 practitioner consensus: run Gitleaks at the edge (pre-commit + every PR) and TruffleHog on
  a schedule for verified-confidence sweeps; add the platform layer as a backstop.

---

## Recommended fukuii stack (summary — see inline reply for the setup.md edit)

| Layer | Tool | Standard or fukuii-first |
|---|---|---|
| SAST | Semgrep CE (`p/scala` + `p/default`, authored config) | fukuii-first (ecosystem gap, best available) |
| SCA / vuln alerts (source deps) | `scalacenter/sbt-dependency-submission` → GitHub Dependency Graph → Dependabot security alerts | Ecosystem-standard (pekko, sbt, scala3 all run this) |
| SCA (container images) | Trivy (already in plan, nethermind-pattern) | Ecosystem-standard (unchanged) |
| Dependency-update PRs | Scala Steward | Ecosystem-standard (zio and most Typelevel-adjacent projects) |
| Dependabot (sbt version-updates) | Optional/secondary — complements Scala Steward, does not replace it | Ecosystem-standard but young (shipped 2026-05-26) |
| Secrets | Gitleaks (CI+pre-commit) + GitHub secret scanning/push protection | Ecosystem-standard, language-agnostic |
