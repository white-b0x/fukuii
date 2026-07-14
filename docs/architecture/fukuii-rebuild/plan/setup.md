# Setup plan — the repo foundation (build floor · CI · hygiene · clean base · agent alignment)

_The layer beneath L0. It has no code of its own, but it is the **R6 build-floor that gates every layer
above** (SR↔dev directive: "a dep-bump or build-modernization item must clear before the structural work
it unblocks can land"). It was unplanned in the first draft (the `00-repo-setup.md` record is a thin
harvest note) — this is setup brought to the same rubric bar as L0–L10. Governed by
[`observations/build-deps.md`](../../research/clients/observations/build-deps.md) +
[`repo-patterns/`](../../research/best-practices/evm-clients/repo-patterns/); the foundation mining dossier's
Part A is the evidence base; R6/R10/R11 + F12/F13 are the requirements it satisfies._

## 1. Scope

Setup delivers the repo's foundation, on the **clean `upstream/staging` base** (per the trunk decision —
the mechanics are `plan/migration-runbook.md`; this doc is the *target config*):

- **Build floor** — sbt build, module DAG, single-version-source (`Dependencies.scala`), the sbt-2 cutover.
- **Supply-chain gate** — checksummed artifact verification + `resolution-age`/Dependabot cooldown (sentinel).
- **CI security automation (F12/R11)** — SAST, Dependabot (deps + security), pre-merge Trivy, `SECURITY.md`.
- **CI correctness gates** — the dependency-direction/DAG ratchet, auto-doc drift check, format/lint.
- **Repo hygiene** — CODEOWNERS, license, the akka-cruft cleanup, Docker digest-pinning.
- **Dev-rich environment (F5/R9)** — `fukuii-custom-networks`, the `ops/` dashboards, network origination.
- **Root-doc + full agent-charter reconciliation (F13)** — the initial "bring every agent onto the rebuild"
  so the specialists are current before any per-layer work.

## 2. SR slots & verdicts (honor, do not invent)

From `observations/build-deps.md` (the DEFAULT/OPTIONAL catalog):

- **DEFAULT — versionless-submodule deps + one central version source.** *"besu's `:platform` BOM,
  nethermind's CPM, and reth's `[workspace.dependencies]` are three encodings of one rule: no submodule
  declares a version; one file pins them all … the single most transferable pattern."* fukuii's
  `Dependencies.scala` **is** the sbt equivalent (besu BOM ≙ `Dependencies.scala`).
- **DEFAULT — a checksummed supply-chain gate.** *"besu's checked-in `verification-metadata.xml`
  (per-artifact sha256, fail-on-mismatch) and geth's checksummed toolchain … the Gradle-native-lockfile
  equivalent for sbt aligns fukuii's build with the global supply-chain rules … sentinel owns this gate."*
- **DEFAULT (as a CI ratchet) — a dependency-direction assertion.** geth `check_baddeps` = *"the build-layer
  analog of fukuii's `Eth*`/`Etc*` no-cross-reference rule."* **Scope it correctly (RX-setup-10):** geth's
  `doCheckBadDeps` (`build/ci.go:524-549`) is a **curated pairwise forbidden-edge denylist** (its own comment:
  *"not an exhaustive list, rather something we build up over time at sensitive places"*), **not** an automatic
  acyclic-topology enforcer. So fukuii splits DAG enforcement in two: **(a) sbt's own cyclic-dependency compile
  error** enforces the acyclic module topology structurally, and **(b) a `check_baddeps`-style enumerated
  forbidden-edge ratchet** grown over time carries the `Eth*`/`Etc*` cross-reference + cross-layer-edge policy.
  One tool cannot do both.
- **OPTIONAL(product-family) — multi-binary component split** (erigon runtime process seams / reth+nethermind
  compile-time family gating): *"a lean mining-pool build and an enterprise multi-network build differing only
  by which modules/binaries are aggregated."*
- **OPTIONAL(SDK) — meta-module front door** (reth `reth-ethereum` re-export crate / nethermind plugin-project):
  *"for enterprise consumers assembling a custom node against stable seams."*
- **From repo-patterns:** `SECURITY.md`, CI SAST, pre-merge container scan, DCO agentic-attribution,
  auto-doc-update (`repo-patterns/{go-ethereum,reth}/dev-workflow-pattern.md`).

## 3. Per-concern authorities

| Concern | Authority | besu JVM-impl reference |
|---|---|---|
| Build tool + module DAG | **besu** (JVM/Gradle multi-project = fukuii/sbt peer) | `settings.gradle` layered subprojects; leaf modules zero-internal-deps |
| Single-version-source | **besu** (`:platform` BOM, *published*) + nethermind CPM + reth `[workspace.dependencies]` | `gradle/platform/build.gradle` |
| Checksummed supply-chain gate | **besu** `verification-metadata.xml` + geth `checksums.txt` | (sbt has no native equivalent → **needs design**, sentinel) |
| Dependency-direction / DAG ratchet | **geth** `check_baddeps` (`build/ci.go`) | besu `plugin-api` `FileStateChecker` source-hash freeze |
| CI security (SAST/scan) | **nethermind** (pre-merge Trivy, unchanged) + **Semgrep SAST — fukuii-first** (CodeQL has no Scala extractor, verified; Semgrep runs on *no* vendored client — SAST-for-Scala is a real ecosystem gap, not a fukuii miss, per `docs/research/best-practices/scala-security-tooling-2026.md`; config authored from `p/scala`+`p/default` **community** rulesets, not lifted) + **`scalacenter/sbt-dependency-submission`** → GitHub Dependency Graph → native **Dependabot** alerts (the actual ecosystem-standard source-SCA mechanism — confirmed live in pekko/sbt/scala3) | besu daily CodeQL (Java) for any Java-interop surface |
| Auto-doc-update | **reth** `update-book-cli` + `git diff --exit-code`; **go-ethereum** `check_generate` | — |
| Multi-binary / SDK seams (OPTIONAL) | **erigon** (process split) / **reth** + **nethermind** (compile-time) | — |
| Repo hygiene | **besu** `SECURITY.md` + **nethermind** Trivy + Docker `@sha256` pin | — |

**besu is the JVM implementation guide throughout** (its Gradle multi-project + BOM + verification-metadata
map most directly onto sbt).

## 4. besu structural mirror

- **BOM** (`java-platform`, published) → `project/Dependencies.scala` as the single catalog, and **consider
  publishing a fukuii "platform" artifact** so product-family/plugin consumers (pool-software, dRPC-gateway,
  MCP) pin the same versions (build-deps obs §fukuii-implications).
- **`verification-metadata.xml`** (per-artifact sha256, fail-on-mismatch) → the sbt supply-chain gate to design.
- **Layered `settings.gradle` DAG** (leaf modules zero-internal-deps) → the `build.sbt` module DAG (the
  boundaries already echo besu's `util`/`crypto:algorithms`/`ethereum:rlp`/`evm` ~1:1).
- **`plugin-api` source-hash freeze** (`FileStateChecker`) → the extension-seam lock if/when the R7/R9
  plugin-api lands.

## 5. sbt / Scala-3 idiom & born-modern targets (R6)

- **sbt-2 cutover** (MOD-19 Wave S) — on the current sbt-1.10.7/Scala-3.3.8/Pekko-1.6 stack now, cut to sbt-2
  at GA; the two known plugin blockers (`sbt-kanela-runner` — moot once Kamon is dropped, R10) tracked.
- **`Dependencies.scala` single-version-source discipline** — *never inline a version in a submodule*; one
  `val` per version (fix the AS-IS duplicates + the outside-catalog `kanela` literal + the unpinned `solc`).
- **Born-modern deps from line one** — circe (not json4s), Caliban 3.1.2 (not Sangria), Streams-Tcp (not
  Classic-Tcp), Micrometer/Prometheus (not Kamon). Never re-introduce the old library.
- **GitHub deps pinned to commit SHAs** (never a branch — reth's `revmc` branch-pin is the counter-example);
  exact pins for crypto/build packages; `resolution-age` 7-day gate.

## 6. Improvements over old fukuii (AS-IS gaps this layer closes)

| Old fukuii (AS-IS) | Rebuild setup | Why it matters |
|---|---|---|
| `Dependencies.scala` single catalog but **1 literal outside it** (`kanela` `build.sbt:3`), **2 duplicates inside** (json4s ×2, bouncycastle ×2), **`solc` unpinned** (shells to `$PATH`) | One `val` per version, zero inline/duplicate/unpinned | The single-version-source discipline actually enforced |
| **No checksum/lockfile artifact** — resolution trusts Maven Central each build | Checksummed supply-chain gate (besu `verification-metadata` equivalent), sentinel-owned | Closes the *published-bytes-changed-under-a-fixed-version* attack Coursier's default checksums miss |
| **Zero CI SAST** (no codeql/trivy/semgrep across 29 workflows); **no `SECURITY.md`** | Semgrep SAST (fukuii-first, authored — SAST-for-Scala is a genuine ecosystem gap) + pre-merge Trivy + **`scalacenter/sbt-dependency-submission`→Dependabot alerts** (ecosystem-standard source-SCA) + **Scala Steward** dep-updates + **Gitleaks** secrets + `SECURITY.md` (F12/R11). The CI-Action additions are **operator-APPROVED (2026-07-14)** — sentinel wires them SHA-pinned, 7-day cooldown (`scala-security-tooling-2026.md`) | fukuii ships **none** today — a real security hole |
| **No dependency-direction CI gate** — `Eth*`/`Etc*` is convention/review only | **(a)** sbt's cyclic-dependency compile error enforces the acyclic module DAG (topology) + **(b)** a `check_baddeps`-style **enumerated forbidden-edge** ratchet carries the `Eth*`/`Etc*` + cross-layer-edge policy (R1/R3), **plus the R2 isolation-regression grep** (`object … { var … }` + direct `Config.`-read **+ the metrics process-globals `PrometheusRegistry.defaultRegistry` / `CollectorRegistry.defaultRegistry` / `GlobalOpenTelemetry`** — the enforced home for L2/L8/L9/L10's per-instance gate) | Family-neutrality, the acyclic DAG, **and multi-instance isolation** become mechanical, not hoped-for |
| **No auto-doc / drift gate** — `sbt pp` never builds Scaladoc; no generated CLI reference | reth `update-book-cli` + `git diff --exit-code` (F4/R10); metric↔dashboard drift check | Generated surfaces can't silently drift |
| **Single assembled artifact**, no product-family seam | The multi-binary/meta-module seams left *designed* (OPTIONAL, R7/R9) | The product-family lift stays non-invasive |
| **`assembly/assemblyMergeStrategy` still special-cases `"akka"`** (post-migration cruft) | Cleaned | Born-modern, no Akka-era residue |
| **Root docs + agent charters point at the dead `src/…` tree** | Full reconciliation onto `modules/` (root-doc + charters) — F13 | Every agent loads a *current* map, not a phantom tree |

## 7. Deferrals landing here / left as designed seams (OPTIONAL, with the disposition)

- **Multi-binary component split** (erigon process seams) → OPTIONAL(product-family): draw the process/gRPC
  boundary now (R7), ship the split when the product-family work lands. Don't preclude it.
- **Meta-module SDK front door** (reth `reth-ethereum` façade) → OPTIONAL(SDK/enterprise): the "assemble a
  custom node from modules" affordance; the decomposition target, not now.
- **Published fukuii BOM** → OPTIONAL(product-family): when downstream consumers need version alignment.
- **DCO agentic-contribution metadata** (besu — `Co-Authored-By`/`Assisted-By` + model/version/context-size)
  → adopt if DCO is added (fukuii already has the `Co-Authored-By` convention).
Each is *seam-now, occupancy-later* — none is dropped; the multi-pass checks they're consciously placed.

## 8. Exit DoD (GREEN bar)

Setup is done when:
- `sbt compile-all` clean on the clean-from-staging base; the module DAG compiles (an upward edge = error).
- `Dependencies.scala` is the sole version source (grep: no version literal in any submodule `build.sbt`).
- The supply-chain gate verifies + fails-on-mismatch (sentinel-signed); `resolution-age`/Dependabot cooldown live.
- CI runs: Semgrep SAST (fukuii-authored Scala rules), pre-merge Trivy, `scalacenter/sbt-dependency-submission`
  (→ Dependabot alerts), the sbt-cyclic + `check_baddeps`-enumerated DAG ratchets, the **R2 isolation-regression
  grep** (`object … { var … }` + direct `Config.`-read **+ the metrics process-globals
  `PrometheusRegistry.defaultRegistry` / `CollectorRegistry.defaultRegistry` / `GlobalOpenTelemetry`** in
  per-instance code — the repo-wide enforced home for the gate L2/L8/L9/L10 each assert, so it can't drift
  per-layer, R2-F3), the auto-doc drift check, formatCheck.
- `SECURITY.md` + `dependabot.yml` (deps + security, 7-day cooldown) + Docker `@sha256` pins present.
- **Root docs (AGENTS.md/CLAUDE.md) + every fukuii agent charter describe `modules/` + the plan** (no dead
  `src/…` pointer); the standing rebuild context is in each charter (F13 — §10).
- Dev-rich env intact (`fukuii-custom-networks`, `ops/` dashboards).

## 9. Risks & flags

- **Setup gates everything** — a wrong dep floor or a broken DAG blocks every layer. Do it first, verify hard.
- **Dependency changes are sentinel-gated** — no unilateral bumps; evidence-based, CVE-safe versions only.
- **The supply-chain gate has no sbt-native equivalent** — it needs design (a custom sbt task snapshotting +
  verifying resolved-artifact SHA-256, or a vetted Coursier plugin); sentinel owns it.
- **sbt-2 cutover risk** — plugin compatibility (the `<module>/test` false-green quirk; the cutover's
  `Test /` scoping was necessary but **not sufficient** — a residual `test`≡`testQuick` skip-cache
  false-green survived it and was caught by the L0 gate, fixed by rewriting the push-gate aliases to
  `testOnly *` in `735b0607a`); stage it, don't rush the GA cut.

## 10. Agentic alignment (F13 — the initial full reconciliation; step 5 for setup)

Setup does the **one-time full agent-charter reconciliation** the per-layer step 5 then maintains:
- Every fukuii work-charter (`.claude/agents/*.md`) gets the **standing rebuild context** (code in `modules/`,
  the plan at `docs/architecture/fukuii-rebuild/plan/`, the R1–R11 authority model, Rule 0, the besu-JVM lens,
  the multi-pass gate) and its **dead `src/…` paths repointed** at `modules/<layer>/` or the plan doc.
- `.claude/agents/REFERENCES.md` extended with the per-concern authority map + the best-practices library +
  the SR resource set.
- AGENTS.md/CLAUDE.md reconciled (module list, Key Directories → the plan).
- warden owns the mechanics; the broader `TOOLING-AUDIT-01` agentic-polish runs in parallel (not gating).

**Then each subsequent layer's step 5 keeps its slice current** — agents are *always current and helpful*
through the build.

## Layer boundaries (what lives elsewhere)

- **The git migration itself** (clean `fukuii-rebuild` branch off `upstream/staging`, curated foundation
  series) → `plan/migration-runbook.md`. Setup describes the *target config*; the runbook is the *move*.
- **Per-layer build config** (a module's own `build.sbt` deps) → that layer's plan; setup owns only the DAG
  + the version source + the CI gates.
- **The dev-rich environment's per-network content** (genesis, testnet configs) → L5/multi-network + F5.
