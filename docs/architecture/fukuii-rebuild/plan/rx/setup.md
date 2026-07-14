# RX — setup (build floor · supply-chain · CI security · DAG ratchets · auto-docs · clean base · F13) per-item reference-client verification

_The depth pass for the layer beneath L0 — the R6 build-floor that gates every layer above. Each item the
setup plan (`plan/setup.md` §1–§10 + the `optimizations.md` cross-cutting build/CI rows +
`requirements.md` R6/R10/R11 cells + `feature-ledger.md` F4/F12/F13) commits to, verified against the
**actual vendored reference-client build/CI source** under
`.claude/repo-references/clients/{besu,core-geth,erigon,go-ethereum,nethermind,reth}/`. Method: `rx/README.md`._

**Every entry answers the four questions before its verdict:** Q1 appropriate decision? · Q2 what we should
implement (vs a better alternative / YAGNI)? · Q3 mechanism + blast radius correct? · Q4 if any "no", the
correct answer + why. Verdict: **CONFIRMS** / **CORRECTS** / **SHARPENS**.

**Spec malleability (README):** setup implements no consensus spec — the items here are fukuii-owned build /
CI / tooling design, fully in scope for a `CORRECTS`. The reference clients are *implementation precedent*,
not frozen authority; where a mechanism the plan attributes to a client is not actually present in the
vendored source, that is itself an RX finding (nothing to transcribe at build time).

**Path-existence pre-check (all cited paths verified present unless flagged):** go-ethereum
`build/ci.go` (`check_baddeps` L521-549, `check_generate` L474-518) ✓, `build/checksums.txt` (`# version:`
toolchain registry) ✓, `.github/workflows/go.yml:39` ✓; besu `gradle/verification-metadata.xml` (10,070
lines) ✓, `plugin-api/build.gradle:71` (`checkAPIChanges`/`FileStateChecker`) ✓, `SECURITY.md` ✓,
`.github/workflows/codeql.yml` (daily, java) ✓, `.github/workflows/container-security-scan.yml` (Trivy,
**scheduled**) ✓; nethermind `.github/workflows/trivy.yml` (**pre-merge**) ✓, `codeql.yml` (csharp+actions) ✓,
`Dockerfile*` (`FROM …@sha256:` pins) ✓; reth `Makefile:223` (`update-book-cli`) + `.github/workflows/lint.yml:255-257`
(`update.sh` + `git diff --exit-code`) ✓, `.github/dependabot.yml` (cooldown default-days: 7) ✓.
**FLAGGED ABSENT:** **Semgrep — present in NO vendored client** (RX-setup-06); a 7-day Dependabot cooldown —
present in **exactly one** client, reth (RX-setup-08).

**Headline findings (build-gate relevant):**
- **RX-setup-06 (CORRECTS)** — the plan reads Semgrep SAST as if adopted from a client ("nethermind (pre-merge
  Trivy) + Semgrep"). **No vendored client runs Semgrep.** nethermind = Trivy + CodeQL(csharp); besu =
  CodeQL(java) + scheduled Trivy. Semgrep is a fukuii-*first* choice (justified: CodeQL genuinely has no Scala
  extractor). There is no client config to transcribe at build time — the Semgrep CI must be designed from
  Semgrep's own Scala ruleset, and the plan must stop implying nethermind provides it.
- **RX-setup-10 (SHARPENS→CORRECTS)** — geth `check_baddeps` is a **curated pairwise forbidden-edge denylist**
  (2 hand-listed rules; comment: *"not an exhaustive list, rather something we build up over time at sensitive
  places"*), **not** an automatic acyclic-DAG topology checker. The plan calls it "the mechanical enforcer of
  the module DAG" — that overstates the mechanism. fukuii's DAG ratchet must either enumerate every forbidden
  upward edge explicitly or use a real sbt arch-lint; check_baddeps only proves the *forbidden-edge pattern*,
  not whole-DAG enforcement.
- **RX-setup-07 (SHARPENS)** — nethermind Trivy is genuinely **pre-merge** (`pull_request: [master]`); besu also
  runs Trivy but **scheduled daily** (`cron '17 8 * * *'`), not pre-merge. The plan's authority pick (nethermind
  for pre-merge) is correct — sharpen that besu is the scheduled cross-check, not a second pre-merge precedent.
- **RX-setup-08 (SHARPENS)** — the 7-day Dependabot cooldown is attested in **exactly one** vendored client
  (reth `dependabot.yml`). besu/geth/nethermind/core-geth ship **no** `dependabot.yml`; erigon ships one **without**
  cooldown. The plan grounds cooldown in the global supply-chain rule — name reth as the sole vendored proof.

---

## Group A — build floor / single-version-source

### RX-setup-01 · Single version source (`Dependencies.scala`, besu-BOM shape), sentinel-gated · Tier A (structural spine) · owner-layer setup
- **Plan claim / disposition:** §2/§4/§5 DEFAULT+STRUCTURAL (R6). `project/Dependencies.scala` **is** the sbt equivalent of besu's published `:platform` BOM; discipline = *never inline a version in a submodule `build.sbt`*; fix the AS-IS duplicates. Dependency changes **sentinel-gated** (memory `dependency-changes-need-gated-agent`).
- **fukuii AS-IS:** `Dependencies.scala` centralizes versions but is **not** a published BOM / versionless-enforced; per `build-deps.md`, 1 literal outside it (`build.sbt:3` kanela-agent), 2 duplicate literals inside (json4s `4.0.7` ×2, bouncycastle `1.84` ×2). (§6 adds `solc` unpinned — not cited in the obs doc; a foundation-dossier claim.)
- **Reference source (byte-cited):**
  - besu `gradle/platform/build.gradle` — `java-platform` BOM, *published*; submodules declare deps versionless. (JVM-impl lens — the direct sbt analog.)
  - nethermind `Directory.Packages.props` — CPM, versionless `<PackageReference>`, transitive pinning on (cross-check).
  - reth `[workspace.dependencies]` — every dep declared once, members `foo.workspace = true` (cross-check).
  - Absence-as-evidence: go-ethereum/core-geth use `go.mod` (module-graph, no BOM) — not the model for a JVM multi-module.
- **Q1 appropriate?** Yes — three JVM/.NET/Rust peers converged independently; `build-deps.md` calls it "the single most transferable pattern." besu's BOM is the closest literal port to `Dependencies.scala`.
- **Q2 what to implement?** Yes — the discipline (one `val` per version, zero inline/duplicate) is the right target. The *published*-BOM affordance is correctly held as OPTIONAL(product-family, §7), not forced now.
- **Q3 mechanism + blast radius?** Correct + **under-named blast radius**: this is the sentinel-gated seam — **every** dependency touch across all layers routes through this one file, and the supply-chain gate (RX-setup-05) + Dependabot (RX-setup-08) both key off it. Plan states sentinel ownership; it does not name that a `check_baddeps`-style version-source lint (grep: version literal in any submodule `build.sbt`) is the mechanical enforcer — fold into §8 DoD (it already has the grep, but not tied to the CI ratchet home of RX-setup-10/11).
- **Q4:** n/a.
- **Verdict:** CONFIRMS (sharpen: bind the "no version literal in a submodule" grep to the setup CI ratchet job, not just a manual DoD check).

### RX-setup-02 · sbt-2 cutover (MOD-19 Wave S) · Tier B · owner-layer setup
- **Plan claim / disposition:** §5 R6 — stay on sbt-1.10.7/Scala-3.3.8/Pekko-1.6 **now**, cut to sbt-2 at GA; two known plugin blockers tracked (`sbt-kanela-runner` moot once Kamon dropped, R10).
- **fukuii AS-IS:** sbt 1.10.7 (`project/build.properties:1`, per `build-deps.md`), single build tool.
- **Reference source (byte-cited):** no reference-client analog (all six use Gradle/Cargo/Makefile/MSBuild, not sbt) — this is a fukuii-internal build-modernization item, correctly grounded in MOD-19, not a client.
- **Q1 appropriate?** Yes — the SR↔dev directive (`build-deps.md` §SR↔dev) is explicit: build-floor modernization is a *prereq floor* staged ahead of the structural work, and the current stack carries zero throwaway risk (memory `mod19-modernization-waves`).
- **Q2 what to implement?** Yes — deferring the GA cut (stage, don't rush) is right; the `<module>/test` sbt-2 false-green quirk (warden BUILD-1) is a live gotcha the plan flags (§9).
- **Q3 mechanism + blast radius?** Correct — sbt-2 is a whole-build event; blast radius is every module. Plan states it as Wave S, not gating the current work.
- **Verdict:** CONFIRMS.

### RX-setup-03 · Born-modern deps + GitHub SHA pins + `resolution-age` · Tier B · owner-layer setup
- **Plan claim / disposition:** §5 R6 — circe (not json4s), Caliban 3.1.2 (not Sangria), Streams-Tcp (not Classic-Tcp), Micrometer/Prometheus (not Kamon), never re-introduce the old lib; GitHub deps pinned to commit SHAs (reth `revmc` branch-pin = the counter-example); `resolution-age` 7-day gate; exact pins for crypto/build.
- **fukuii AS-IS:** mixed — the successor deps are the rebuild target; the old libs live on `july-fourth`.
- **Reference source (byte-cited):**
  - reth `Cargo.lock` + `dependabot.yml` — but **ships a branch-pinned git dep** (`revmc` branch, not SHA) — `build-deps.md` names it "a smell"; the plan correctly cites it as the anti-pattern to avoid.
  - go-ethereum `build/checksums.txt` — checksummed toolchain download (SHA-pinned tools).
  - Global rule: `~/.claude/.../supply-chain-security.md` `resolution-age=10080` (7-day) — the source of the gate.
- **Q1 appropriate?** Yes — matches the global supply-chain rules; the reth branch-pin counter-example makes the SHA-pin rule concrete.
- **Q2 what to implement?** Yes — successor-from-line-one is R6's core; no better alternative.
- **Q3 mechanism + blast radius?** Correct — sentinel-gated (RX-setup-01); each successor swap is a per-dep decision, evidence-gated.
- **Verdict:** CONFIRMS.

### RX-setup-04 · Module DAG (besu layered `settings.gradle` → `build.sbt` DAG) · Tier A (structural spine) · owner-layer setup
- **Plan claim / disposition:** §3/§4 — the layered `build.sbt` module DAG mirrors besu's `settings.gradle` layered subprojects (leaf modules zero-internal-deps); boundaries echo besu's `util`/`crypto:algorithms`/`ethereum:rlp`/`evm` ~1:1. An upward edge = compile error (§8).
- **fukuii AS-IS:** `build-deps.md` — fukuii's sbt modules already echo besu's leaf modules ~1:1; "the boundaries are largely right."
- **Reference source (byte-cited):**
  - besu `settings.gradle` — layered subprojects, clean layered DAG, leaf modules have zero internal deps (`build-deps.md` comparison-table row 2).
  - reth 108 crates, `*-api`/`*-types` split from impl (finest decomposition, cross-check).
  - nethermind: core has no compile ref to any family; families reference *inward* to `Nethermind.Api` only (cross-check).
- **Q1 appropriate?** Yes — the DAG is R1/R3's structural spine (network-neutral lower layers ⇒ thin L5 `NetworkFamily`); the module boundaries are the enforcement surface.
- **Q2 what to implement?** Yes — the acyclic down-only DAG is non-negotiable for the rebuild.
- **Q3 mechanism + blast radius?** Correct + **widest blast radius in setup**: the DAG constrains *every* layer L0→L10; a wrong edge blocks the whole rebuild (§9 "setup gates everything"). The mechanical enforcer is RX-setup-10 — see that entry for the caveat that sbt's own "upward edge = compile error" only catches *cyclic/undeclared* edges, not *policy* edges (Eth→Etc, or a technically-legal but forbidden cross-layer reach).
- **Verdict:** CONFIRMS (the enforcement-mechanism caveat lands on RX-setup-10).

## Group B — supply-chain / checksum gate

### RX-setup-05 · Checksummed supply-chain gate (besu `verification-metadata.xml` + geth `checksums.txt`) · Tier A (structural, sentinel) · owner-layer setup
- **Plan claim / disposition:** §2/§3/§4/§6 DEFAULT+STRUCTURAL (R6/R11). besu `verification-metadata` equivalent for sbt; §3 + §9 honesty: **"sbt has no native equivalent → needs design"** (a custom sbt task snapshotting+verifying resolved-artifact SHA-256, or a vetted Coursier plugin); sentinel-owned. Closes the *published-bytes-changed-under-a-fixed-version* attack Coursier's default checksums miss (§6).
- **fukuii AS-IS:** `build-deps.md` — **none**; "no checksum/lockfile artifact checked in; resolution relies on Maven Central + declared `resolvers` each build."
- **Reference source (byte-cited):**
  - besu `gradle/verification-metadata.xml` — **10,070 lines**; `<verify-metadata>true</verify-metadata>` / `<verify-signatures>false</verify-signatures>`; per-artifact `<sha256 value="…" origin="Generated by Gradle"/>` for jar+pom+sources. Gradle-native lockfile+checksum, fail-on-mismatch.
  - go-ethereum `build/checksums.txt` — `# version:golang 1.25.10`, `# version:golangci …`, `# version:protoc …`; sha256 of every build artifact incl. bootstrap Go. Checksummed *toolchain*, not deps.
  - nethermind `nuget.config` `packageSourceMapping` + exact bracket pins (`[10.10.1.649]`) — partial (feed-pinning, not per-artifact checksum).
  - Absence-as-evidence: reth ships only `Cargo.lock` + a branch-pinned git dep (weaker).
- **Q1 appropriate?** Yes — besu's `verify-metadata` is the direct model; note besu keeps signatures *off*, checksums *on* — the achievable posture (signature verification is the harder, often-skipped tier). The enterprise/CEX supply-chain posture (`build-deps.md` approach-catalog) is exactly fukuii's GTM.
- **Q2 what to implement?** Yes — but the plan's **"needs design" honesty is CONFIRMED correct**: there is genuinely no sbt-native `verification-metadata` equivalent in the vendored set (only Gradle/Cargo/NuGet have native lockfile-checksum tiers). The build-time deliverable is a *design*, not a transcription — the entry cannot become a copy-paste spec. Flag this explicitly so build-time doesn't expect a client config to lift.
- **Q3 mechanism + blast radius?** Correct — sentinel-owned; keys off RX-setup-01's single version source. Blast radius: every resolved artifact across every module; a mismatch fails the whole build (a hard supply-chain gate, aligned to the global rules).
- **Verdict:** CONFIRMS (with the design-not-transcription flag: no sbt-native precedent exists to copy; this is genuinely fukuii-first engineering, sentinel-owned).

## Group C — CI security automation (SAST / Trivy / Dependabot / SECURITY.md)

### RX-setup-06 · CI SAST — Semgrep (CodeQL has no Scala extractor) · Tier B · owner-layer setup/CI
- **Plan claim / disposition:** §2/§3/§6 + F12 + R11 — **"nethermind (pre-merge Trivy) + Semgrep (CodeQL has no Scala extractor)"**; fukuii ships **zero** SAST today (a real security hole). besu daily CodeQL (Java) cited as the JVM cross-reference.
- **fukuii AS-IS:** `build-deps.md` / dossier A8 — **zero** CI SAST across 29 workflows; no codeql/trivy/semgrep.
- **Reference source (byte-cited):**
  - nethermind `.github/workflows/codeql.yml:21` — `language: ['csharp', 'actions']`, `packs: githubsecuritylab/codeql-csharp-queries`. **Not** Scala.
  - besu `.github/workflows/codeql.yml` — `schedule: cron '0 0 * * *'` (daily), `language: ['java']`.
  - **Semgrep: `grep -riln "semgrep"` across nethermind (and every other client) returns EMPTY.** No vendored client runs Semgrep.
  - CodeQL supported-language check (external fact): C/C++, C#, Go, Java/Kotlin, JS/TS, Python, Ruby, Swift, Rust(beta) — **no Scala**. The plan's "CodeQL has no Scala extractor" is **verified true**.
- **Q1 appropriate?** Yes in *intent* (fukuii needs SAST; CodeQL can't cover Scala) — but the *attribution* is wrong.
- **Q2 what to implement?** Semgrep is a defensible fukuii-first choice (it *does* support Scala), but it is **unattested in the reference set** — this is not "adopt what a client does," it is "design a mechanism no reference client has." A leaner alternative worth stating: run CodeQL on the *Java* surface (if any interop) + Semgrep for Scala, mirroring besu's daily-CodeQL cadence for the parts CodeQL covers.
- **Q3 mechanism + blast radius?** **Mechanism understanding is wrong as written** — the plan implies nethermind provides the Semgrep precedent. It does not. Blast radius: CI-only (no code ripple), but at build time there is **no client Semgrep config to transcribe** — it must be authored from Semgrep's Scala ruleset.
- **Q4 correct answer + why:** Reword the plan to: *"Semgrep SAST (fukuii-first — CodeQL has no Scala extractor, verified; no vendored client runs Semgrep, so the config is authored from Semgrep's Scala ruleset, not lifted) + nethermind-pattern pre-merge Trivy + besu-cadence daily CodeQL for any Java surface."* Because the source shows Semgrep is absent everywhere and CodeQL genuinely can't parse Scala.
- **Verdict:** CORRECTS.
- **Plan edit:** §3 authority table (CI security row) + §6 (row 3) + §2 (F12 line) — stop attributing Semgrep to nethermind; label it fukuii-first/unattested, with the CodeQL-no-Scala fact as the rationale and the authored-not-transcribed caveat.

### RX-setup-07 · Pre-merge Trivy container scan (nethermind) · Tier B · owner-layer setup/CI
- **Plan claim / disposition:** §2/§3/§6 — pre-merge Trivy, nethermind the authority; besu daily CodeQL is the JVM cross-reference; part of the F12/R11 automation fukuii ships none of.
- **fukuii AS-IS:** none (dossier A8).
- **Reference source (byte-cited):**
  - nethermind `.github/workflows/trivy.yml` — `on: pull_request: [master]` (**pre-merge**) + push + `schedule: cron '29 19 * * 4'`; builds `docker build -t nethermind:$sha .`, scans with `aquasecurity/trivy-action@…v0.35.0`, `severity: CRITICAL,HIGH`, uploads SARIF via `codeql-action/upload-sarif`.
  - besu `.github/workflows/container-security-scan.yml` — same `trivy-action@…v0.35.0` on `hyperledger/besu` image, but **`schedule: cron '17 8 * * *'` (daily), not pull_request** — scheduled, not pre-merge.
- **Q1 appropriate?** Yes — nethermind is the correct pre-merge authority; the container scan closes the image-vuln gap.
- **Q2 what to implement?** Yes — a pre-merge container scan on the assembled fukuii image (Trivy image-ref + SARIF upload) is directly liftable from nethermind.
- **Q3 mechanism + blast radius?** Correct, with a sharpening: two clients run Trivy but at **different cadences** — nethermind pre-merge, besu scheduled. The plan should name besu as the *scheduled* cross-check (a defense-in-depth pattern: pre-merge gate + daily drift scan), not fold both under "pre-merge."
- **Verdict:** SHARPENS.
- **Plan edit:** §3 CI-security row / §6 row 3 — note nethermind = pre-merge Trivy, besu = scheduled daily Trivy (adopt both cadences: pre-merge gate + daily rescan of the published image).

### RX-setup-08 · Dependabot cooldown (deps + security, 7-day) · Tier B · owner-layer setup/CI
- **Plan claim / disposition:** §2 + §8 + F12 — `dependabot.yml` (deps + security, 7-day cooldown); auto-CVE-patch PRs; sentinel-owned, evidence-gated (no unilateral bumps).
- **fukuii AS-IS:** none.
- **Reference source (byte-cited):**
  - reth `.github/dependabot.yml` — `github-actions` + `cargo` ecosystems, both with `cooldown: default-days: 7`; cargo group has `open-pull-requests-limit: 1`, `commit-message.prefix: "chore(deps)"`, `labels: ["A-dependencies"]`. **The only vendored client with a cooldown.**
  - erigon `.github/dependabot.yml` — `github-actions` + `npm` ×2, **no cooldown**.
  - besu / go-ethereum / nethermind / core-geth — **no `dependabot.yml` at all** (grep empty).
- **Q1 appropriate?** Yes — matches the global supply-chain rule (`resolution-age=10080` 7-day, Dependabot cooldown mandatory on live repos).
- **Q2 what to implement?** Yes — reth's config is the direct template (ecosystem list + `cooldown.default-days: 7`; the global rule adds `semver-major-days: 21`, which reth omits — fukuii should include it per the rule).
- **Q3 mechanism + blast radius?** Correct — Dependabot security advisories bypass the cooldown automatically (that's the "auto-CVE-patch" F12 piece); the cooldown only gates non-security bumps. Blast radius: CI/PR-flow only; keys off RX-setup-01 (the single version source is what Dependabot edits). Sharpen: the 7-day cooldown is attested in **exactly one** vendored client (reth) — the plan grounds it in the global rule, which is fine, but name reth as the sole in-repo proof and note fukuii's config should *exceed* reth (add `semver-major-days: 21`).
- **Verdict:** SHARPENS.
- **Plan edit:** §2 F12 line — cite reth `dependabot.yml` as the vendored precedent (only client with cooldown); §8 DoD add `semver-major-days: 21` per the global rule.

### RX-setup-09 · `SECURITY.md` + Docker `@sha256` base-image pins · Tier B · owner-layer setup
- **Plan claim / disposition:** §2/§6/§8 (R11) — `SECURITY.md` present; Docker `@sha256` digest-pinning; besu `SECURITY.md` + nethermind Trivy + Docker `@sha256` pin cited (§3 repo-hygiene row).
- **fukuii AS-IS:** no `SECURITY.md` (§6); Docker pinning unverified here.
- **Reference source (byte-cited):**
  - `SECURITY.md` present in **besu, core-geth, go-ethereum, nethermind, reth** (5/6; **absent only in erigon**) — near-universal.
  - nethermind `Dockerfile:4,29` — `FROM …dotnet/sdk:10.0.301-resolute@sha256:196f61c6…`, `FROM …dotnet/aspnet:10.0.9-resolute@sha256:0aa8645b…`; same digest-pinning in `Dockerfile.chiseled`, `Dockerfile.pgo`. Every base image pinned by `@sha256`.
- **Q1 appropriate?** Yes — `SECURITY.md` is a 5/6-client norm; `@sha256` base pins are the supply-chain-correct Docker posture.
- **Q2 what to implement?** Yes — both are low-cost, high-value; no better alternative.
- **Q3 mechanism + blast radius?** Correct — repo-hygiene, no code ripple.
- **Verdict:** CONFIRMS.

## Group D — DAG + isolation CI ratchets

### RX-setup-10 · `check_baddeps`-style DAG / `Eth*`/`Etc*` ratchet · Tier A (structural spine) · owner-layer setup/CI
- **Plan claim / disposition:** §2/§3/§6 DEFAULT-as-CI-ratchet (R1/R3/R10). geth `check_baddeps` = "the build-layer analog of fukuii's `Eth*`/`Etc*` no-cross-reference rule" **and** "the mechanical enforcer of the module DAG"; §6 — "enforcing the `Eth*`/`Etc*` rule **and** the module DAG (R1/R3)."
- **fukuii AS-IS:** `build-deps.md` — **none** at the build-tool layer; the `Eth*`/`Etc*` rule is convention/review only (`scala3-style.md`), not an sbt/CI fitness function.
- **Reference source (byte-cited):**
  - go-ethereum `build/ci.go:524-549` (`doCheckBadDeps`) — `baddeps := [][2]string{ {rawdb, ethdb/leveldb}, {rawdb, ethdb/pebbledb} }` — **exactly 2 hand-listed forbidden edges**; per rule runs `go list -deps <A>` and fails if `<B>` appears. Comment L521-523: *"This is not an exhaustive list, rather something we build up over time at sensitive places."* Invoked via `.github/workflows/go.yml`.
  - besu `plugin-api/build.gradle:71` `FileStateChecker` — a *different* mechanism (source-hash freeze, RX-setup-12), not a DAG topology check.
  - nethermind — core has no compile ref to any family (enforced by *project references*, i.e. the build graph itself), no separate denylist tool.
- **Q1 appropriate?** Yes for the `Eth*`/`Etc*` no-cross-reference use — a forbidden-edge pair is exactly the shape (`{Eth-package, Etc-package}` and vice-versa), directly liftable.
- **Q2 what to implement?** Yes for the forbidden-edge ratchet — but Q3 reveals the framing overreaches.
- **Q3 understanding + blast radius?** **Mechanism partly wrong as written.** `check_baddeps` is a *curated denylist of specific forbidden edges*, **not** an automatic acyclic-DAG topology checker. It cannot, by itself, "mechanically enforce the module DAG" — it only catches the specific edges you enumerate. Two distinct enforcement needs are being conflated: (a) **cyclic/undeclared** upward edges — already caught by sbt compilation (RX-setup-04, "upward edge = compile error"); (b) **policy** edges that are technically legal but forbidden (`Eth→Etc`; a lower layer reaching a higher one via a legal-but-wrong path) — these need the check_baddeps *pattern*, enumerated edge-by-edge. Blast radius is correctly "every layer," but the plan overstates that one adopted tool delivers full-DAG enforcement.
- **Q4 correct answer + why:** Reframe: the CI ratchet is a **`check_baddeps`-style enumerated forbidden-edge list** (each `Eth*→Etc*` / cross-layer-policy edge added as a rule, grown over time exactly as geth does) layered **on top of** sbt's own cyclic-dependency compile error. Whole-DAG *topology* enforcement (acyclicity + down-only) is either (i) the enumerated denylist grown to cover each forbidden layer pair, or (ii) a custom sbt arch-lint task walking `moduleGraph`. Do not claim the geth tool auto-enforces the DAG — the source proves it is a hand-curated pair list.
- **Verdict:** CORRECTS.
- **Plan edit:** §3 (DAG-ratchet row) + §6 (row 4) — describe `check_baddeps` accurately as a *curated forbidden-edge denylist* (geth ships 2 rules, grows over time); state that fukuii's DAG enforcement = sbt's cyclic-edge compile error (topology) **+** an enumerated `Eth*`/`Etc*`/cross-layer forbidden-edge ratchet (policy), not one tool doing both.

### RX-setup-11 · R2 isolation-regression grep (`object … { var … }` + direct `Config.`-read) · Tier A (structural spine) · owner-layer setup/CI
- **Plan claim / disposition:** §6 (row 4) + §8 — the CI grep for `object … { var … }` + direct `Config.`-read in per-instance code is "the repo-wide enforced home for the gate L2/L8/L9/L10 each assert, so it can't drift per-layer" (R2-F3).
- **fukuii AS-IS:** the R2 litmus exists as a *convention* (`requirements.md` R2 line: "Litmus: grep for `object … { var … }` / global singletons") — no CI job runs it.
- **Reference source (byte-cited):** **fukuii-invented** — no reference client uses actors, so "no global mutable singleton (would break per-instance isolation)" has no direct client analog. The nearest precedent is nethermind's *no-global-static* discipline (each family references inward to `Nethermind.Api`; the runner loads plugins per-instance) and besu's per-instance Lifecycle FSM — architectural, not a grep. The grep mechanism itself is fukuii's own R2 enforcement.
- **Q1 appropriate?** Yes — R2 (concurrent multi-instance single-binary) is *the* enterprise differentiator (memory `sr-phase3`); a per-instance isolation break is silent until two instances collide, so a mechanical repo-wide grep is the right guard.
- **Q2 what to implement?** Yes — centralizing the grep at setup CI (vs re-asserting it in each of L2/L8/L9/L10's §8) is the correct anti-drift placement; the plan's rationale ("so it can't drift per-layer") is sound.
- **Q3 understanding + blast radius?** Correct + correctly-named blast radius: L2 (per-instance DB handle), L8 (per-instance metric registry — the old Kamon global was the anti-pattern), L9 (per-instance routing), L10 (multi-`ChainInstance` runtime) all assert this gate; setup CI is their shared enforced home. Note it's a *heuristic* grep (a legitimate `object { val }` constant is fine; only mutable `var` + process-global `Config.` reads are flags) — the plan should note the grep needs an allow-list for sanctioned singletons or it false-positives.
- **Verdict:** CONFIRMS (sharpen: it's fukuii-invented, not client-adopted; add that the grep needs a sanctioned-singleton allow-list to avoid false positives on immutable `object { val }`).

### RX-setup-12 · besu `plugin-api` source-hash freeze (`FileStateChecker`) · Tier C (OPTIONAL/deferred) · owner-layer setup
- **Plan claim / disposition:** §3/§4 — besu `plugin-api` `FileStateChecker` source-hash freeze → "the extension-seam lock **if/when** the R7/R9 plugin-api lands." OPTIONAL, seam-later.
- **fukuii AS-IS:** no plugin-api yet; nothing to freeze.
- **Reference source (byte-cited):**
  - besu `plugin-api/build.gradle:71-76` — `tasks.register('checkAPIChanges', FileStateChecker) { files = sourceSets.main.allJava.files; knownHash = 'nKgL6IVQtUfwVXjf8zg8XtEVZm+ImSiM3BAz9owTLUc=' }`; `check.dependsOn('checkAPIChanges')`. The task (L55-68) SHA-hashes every `.java` file sorted by canonical path, base64-encodes, compares to `knownHash`, throws `GradleException` on mismatch (message: *"the checksum of the project did not match … update knownHash … if this is a deliberate change where you have thought through backwards compatibility"*).
- **Q1 appropriate?** Yes — a source-hash freeze forcing *deliberate* API changes on a published extension surface is the correct posture; but the seam it locks (R7/R9 plugin-api) doesn't exist yet.
- **Q2 what to implement?** **Correctly deferred (YAGNI-until-the-seam-exists).** Building the freeze before there's a plugin-api to freeze would guard nothing. OPTIONAL(role) seam-later is the right call.
- **Q3 mechanism + blast radius?** Correct — the mechanism is a build-task checksum gate (not a runtime concern); when the plugin-api lands it becomes that seam's CI gate. Plan states the conditional ("if/when").
- **Verdict:** CONFIRMS.

## Group E — auto-doc regenerate-and-verify

### RX-setup-13 · Auto-doc update (reth `update-book-cli` + `git diff --exit-code`; geth `check_generate`) · Tier B · owner-layer setup/CI
- **Plan claim / disposition:** §2/§3/§6 + F4 + R10 DEFAULT — reth `update-book-cli` + `git diff --exit-code` (generated CLI reference); go-ethereum `check_generate` (freshness); metric↔dashboard drift check. fukuii AS-IS has no auto-doc/drift gate (§6).
- **fukuii AS-IS:** `build-deps.md` — `BuildInfoPlugin` only; "no freshness/hash-diff gate."
- **Reference source (byte-cited):**
  - reth `Makefile:223-226` — `update-book-cli: build-debug … ./docs/cli/update.sh $(CARGO_TARGET_DIR)/debug/reth` (regenerates CLI pages from the binary `--help`). CI: `.github/workflows/lint.yml:252-257` — `cargo build --bin reth` → `./docs/cli/update.sh target/debug/reth` → step "Check docs changes" `run: git diff --exit-code`. (reth `CLAUDE.md` confirms: the `book` CI job "regenerat[es] the docs and running `git diff --exit-code`. If the committed docs don't match the generated output, CI fails.")
  - go-ethereum `build/ci.go:474-518` (`doCheckGenerate`) — `HashFolder` → `go generate ./...` → re-`HashFolder` → `DiffHashes`; `log.Fatal("One or more generated files were updated …")` on any change; then `go mod tidy -diff`. Invoked `.github/workflows/go.yml:39`.
- **Q1 appropriate?** Yes — regenerate-then-`diff`-fail is the canonical "generated surfaces can't silently drift" pattern, in two clients independently.
- **Q2 what to implement?** Yes — directly liftable: fukuii regenerates the CLI/RPC-method reference from the binary and fails CI on an uncommitted diff. The metric↔dashboard drift check is the same pattern applied to the shipped Grafana dashboards vs the per-instance metric names (R10/F6, L8-owned but the CI gate lives here).
- **Q3 mechanism + blast radius?** Correct, with one path-precision sharpening: the *enforcing* `git diff --exit-code` step is in reth **`lint.yml:255-257`** (the docs/book job), while **`book.yml`** builds/deploys the mdbook *site* (no diff gate). The plan cites "reth `update-book-cli`" (Makefile — correct) but the SR obs implies the gate lives in `book.yml`; the actual CI gate is `lint.yml`. Minor, but pin it so build-time looks at the right file.
- **Verdict:** SHARPENS.
- **Plan edit:** §3 auto-doc row — the reth `git diff --exit-code` gate is in `.github/workflows/lint.yml` (docs job), not `book.yml` (which only builds/deploys the site); geth `check_generate` = `build/ci.go` `doCheckGenerate` (HashFolder+go-generate+DiffHashes) invoked from `go.yml`.

## Group F — clean base / migration / hygiene

### RX-setup-14 · Clean `upstream/staging` base + akka-cruft cleanup · Tier B · owner-layer setup
- **Plan claim / disposition:** §1 + §6 + §Layer-boundaries — setup is the *target config* on the clean `upstream/staging` base; the *move* is `plan/migration-runbook.md`. §6: the AS-IS `assembly/assemblyMergeStrategy` still special-cases `"akka"` (post-migration cruft) → cleaned; CODEOWNERS + license + Docker digest-pinning.
- **fukuii AS-IS:** `assemblyMergeStrategy` special-cases `"akka"` (Pekko migration residue).
- **Reference source (byte-cited):** no reference-client analog for the akka→pekko cleanup (fukuii-specific); Docker `@sha256` pinning grounded in nethermind (RX-setup-09); CODEOWNERS grounded in `github-workflows.md` protocol (root catch-all, per CLAUDE.md protocol table).
- **Q1 appropriate?** Yes — born-modern (§6) means no Akka-era residue; the clean-base decision is the trunk decision (memory `research-into-cohesive-plan-before-building` → clean rebuild).
- **Q2 what to implement?** Yes — the target-config-vs-move split (setup = config, runbook = migration) is the correct separation of concerns; setup owns the DAG + version source + CI gates, not the git mechanics.
- **Q3 mechanism + blast radius?** Correct — the akka-string cleanup is a one-line assembly-strategy fix; blast radius is the assembled artifact only. Migration-runbook boundary correctly names where the move lives.
- **Verdict:** CONFIRMS.

### RX-setup-15 · Multi-binary / meta-module SDK seams (OPTIONAL) · Tier C (OPTIONAL, seam-designed) · owner-layer setup
- **Plan claim / disposition:** §2/§7 OPTIONAL(product-family / SDK) — multi-binary component split (erigon process seams) + meta-module SDK front door (reth `reth-ethereum` / nethermind plugin-project); "seam-now, occupancy-later," none dropped.
- **fukuii AS-IS:** `build-deps.md` — single assembled artifact; "no service-binary or compile-time family-gated split."
- **Reference source (byte-cited):**
  - erigon — fleet of separable service binaries (`sentry`/`txpool`/`rpcdaemon`/`downloader`/`caplin`) over a gRPC `node/interfaces` module; boundary at the process/gRPC hop (runtime split).
  - reth — feature-flag composition (`consensus`/`evm`/`node`/`full`) + `reth-node-builder`; meta-crate `reth-ethereum` re-export front door (compile-time).
  - nethermind — family-per-assembly, `INethermindPlugin` + `EmbeddedPlugins`, one runner aggregates all (compile-time).
- **Q1 appropriate?** Yes — OPTIONAL(product-family) is correct: the lean-mining-pool vs enterprise-multi-network build differing only by aggregated modules is the GTM (memory `fukuii-mission`), but it's a named-use-case affordance, not the baseline (`build-deps.md` approach-catalog).
- **Q2 what to implement?** **Correctly seam-now / build-later.** Drawing the process/gRPC boundary now (R7) without shipping the split keeps the product-family lift non-invasive; occupancy-later is right (memory `planned-work-is-scope-floor` — the *seam* is the floor, the split is the deferred occupancy, and the plan does place the seam consciously).
- **Q3 mechanism + blast radius?** Correct — ties to R7 (one gRPC boundary = internal decomposition + dRPC bridge); the seam producer lands at L9/L10, setup only reserves the module-DAG shape so it's not precluded.
- **Verdict:** CONFIRMS.

### RX-setup-16 · Dev-rich environment (F5/R9 — `fukuii-custom-networks`, `ops/` dashboards, network origination) · Tier B · owner-layer setup
- **Plan claim / disposition:** §1/§8 + F5 + R9 — dev-rich env as first-class: `fukuii-custom-networks`, `ops/` dashboards, network origination (besu `generate-blockchain-config`); per-network content (genesis/testnet) → L5/F5 (Layer-boundaries).
- **fukuii AS-IS:** `fukuii-custom-networks` skill exists (skill listing); shipped Grafana dashboards exist (R10 — "the only client besides erigon that ships them").
- **Reference source (byte-cited):**
  - besu `generate-blockchain-config` — custom-network origination (cited in F5/R9; grounded `multi-network.md`).
  - erigon — the other client shipping Grafana dashboards (R10 note).
- **Q1 appropriate?** Yes — R9 widens fukuii from "join a network" to "erect + serve" one (feature-ledger positioning shift F6-F8); dev-rich env is the first-class enabler, not an afterthought.
- **Q2 what to implement?** Yes — setup owns the *environment* (tooling + dashboards + origination affordance); the *per-network content* (genesis, testnet configs) correctly defers to L5/F5. The boundary is right.
- **Q3 mechanism + blast radius?** Correct — setup preserves the existing skill + dashboards; the origination seam ties to L5 (network-as-data) and L6 (bootnode/ENR serving). Blast radius named in the Layer-boundaries section.
- **Verdict:** CONFIRMS.

## Group G — agentic reconciliation (F13)

### RX-setup-17 · F13 full agent-charter reconciliation (§10) · Tier C (build-process, no client analog) · owner-layer setup
- **Plan claim / disposition:** §6/§8/§10 + F13 — the one-time full reconciliation: every `.claude/agents/*.md` charter gets the standing rebuild context (code in `modules/`, plan location, R1–R11 authority model, Rule 0, besu-JVM lens, multi-pass gate) + dead `src/…` paths repointed at `modules/<layer>/`; `REFERENCES.md` extended; AGENTS.md/CLAUDE.md reconciled; **warden owns**, `TOOLING-AUDIT-01` parallel (not gating).
- **fukuii AS-IS:** §6 — root docs + agent charters point at the dead `src/…` tree (the documented miss, memory `phase4-relocation-strategy` / F13 "the dead-`src/`-charter miss").
- **Reference source (byte-cited):** **no reference-client analog** — this is build-process tooling (how Claude is used to build fukuii), correctly flagged in the ledger as "not a client feature." warden's remit (CLAUDE.md subagent table).
- **Q1 appropriate?** Yes — an agent loading a phantom `src/` tree is actively harmful (wrong file map); the operator's documented failure mode (memory `research-into-cohesive-plan-before-building`, the plugin-api miss) is partly a stale-context failure. Reconciling charters onto `modules/` + the plan is the direct guard.
- **Q2 what to implement?** Yes — the one-time full reconciliation at setup + per-layer step-5 maintenance (plan/README lifecycle) is the right cadence: bring everything current once, then keep each slice current as its layer lands. No better alternative (a per-layer-only approach would leave the majority of charters stale through the whole build).
- **Q3 understanding + blast radius?** Correct + correctly-named blast radius: ripples to **every** `.claude/agents/*.md` charter + `REFERENCES.md` + AGENTS.md/CLAUDE.md. warden-owned mechanics; `TOOLING-AUDIT-01` parallel and explicitly non-gating (so it doesn't block the build floor). Blast radius is stated.
- **Q4:** n/a.
- **Verdict:** CONFIRMS.

---

## Rollup — CORRECTS / SHARPENS to apply before setup is READY

| ID | Verdict | One-line | Lands in |
|---|---|---|---|
| RX-setup-06 | **CORRECTS** | Semgrep is attested in NO vendored client; reword from "nethermind + Semgrep" to fukuii-first (CodeQL-no-Scala verified true), config authored not transcribed | §2 (F12), §3 (CI-security row), §6 (row 3) |
| RX-setup-10 | **CORRECTS** | geth `check_baddeps` is a curated 2-rule forbidden-edge denylist ("not exhaustive"), NOT an auto acyclic-DAG checker; split fukuii DAG enforcement into sbt-cyclic-compile-error (topology) + enumerated forbidden-edge ratchet (policy) | §3 (DAG-ratchet row), §6 (row 4) |
| RX-setup-07 | SHARPENS | nethermind Trivy = pre-merge; besu Trivy = scheduled daily — adopt both cadences | §3, §6 (row 3) |
| RX-setup-08 | SHARPENS | 7-day cooldown attested only in reth `dependabot.yml`; add `semver-major-days: 21` per global rule | §2 (F12), §8 |
| RX-setup-13 | SHARPENS | reth `git diff --exit-code` gate is in `lint.yml` (docs job), not `book.yml`; geth = `doCheckGenerate` in `build/ci.go` | §3 (auto-doc row) |
| RX-setup-01 | SHARPENS | bind the "no version literal in submodule build.sbt" grep to the setup CI ratchet job (not just a manual DoD) | §8 |
| RX-setup-05 | (CONFIRMS+flag) | "no sbt-native equivalent → needs design" is verified correct — no client precedent to transcribe; genuinely fukuii-first, sentinel-owned | §3/§9 (already stated — keep) |
| RX-setup-11 | (CONFIRMS+flag) | R2 isolation grep is fukuii-invented (no client analog); add a sanctioned-singleton allow-list to avoid false positives on `object { val }` | §8 |

**All other items (RX-setup-02/03/04/09/12/14/15/16/17): CONFIRMS**, no plan change.

**Paths the plan cited that are ABSENT / unattested in the vendored tree:**
- **Semgrep** — in no client (RX-setup-06). Not a missing *path* but a mechanism with no reference config.
- **7-day Dependabot cooldown** — in exactly one client, reth (RX-setup-08); the plan grounds it in the global rule, so not a defect, but the vendored proof is singular.
- All explicitly-cited client paths (besu `verification-metadata.xml`, `plugin-api` `FileStateChecker`, geth `check_baddeps`/`check_generate`, reth `update-book-cli`/`git diff --exit-code`, nethermind Trivy + `@sha256` pins) are **present and verified**.
