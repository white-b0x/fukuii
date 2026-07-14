# Implementation Plan — Reference-Client Pattern Mining, 2026-07

Synthesizes 26 documents (~14,700 lines) from a sequential, per-client research swarm across
Besu, core-geth, Erigon, go-ethereum, Nethermind, Reth, the shared Hive testing framework,
and fukuii's own self-audit. Each per-client doc is exact file:line-cited — this plan is the
tiered, actionable rollout; read the linked docs for full reasoning on any item.

**Framing correction from the original (summary-level) pass:** "leading" is measured by
useful, relevant tooling coverage, not raw mechanism count. Half the surveyed clients
(Erigon, Reth, Nethermind, go-ethereum) already have a root `AGENTS.md`; two (Erigon,
Nethermind) have production CI-integrated AI review bots. fukuii still leads on raw
mechanism (protocols/skills/subagents/looping/Spec-Kit), but "leading" now means: close the
real gaps, adopt the genuinely valuable patterns from across all 6 clients (not just the 2
originally deep-dived), and map out what's left for later — which is what this plan does.

Status: research complete, implementation not yet started (except the 4 low-risk doc-drift
fixes noted in Tier 0). Sequenced by cost/confidence/risk.

---

## Tier 0 — Already done during research (no action needed)

- Fixed `AGENTS.md`'s literal duplicated "MCP tooling for a running node" section (found by
  the fukuii self-audit).
- Fixed `docs/architecture/README.md`'s stale index (was missing 11 of 17 files — all
  `SNAP_SYNC_*.md` and both `PROTOCOL_*.md` docs).
- Fixed `ops/README.md`'s stale dashboard inventory (documented an old flat 4-file
  `grafana/` layout; actual current state is 15 dashboards across 5 categorized
  subdirectories).
- Fixed the matching stale dashboard path in `docs/operations/metrics-and-monitoring.md`.

## Tier 1 — Repo hygiene, cheapest/highest-confidence

1. **`SECURITY.md`** (repo root) — GitHub Security Advisories as the primary reporting path
   (no invented email — no existing security-contact convention exists anywhere in fukuii's
   `README.md`/`.github/`). Response-time targets need human confirmation before publishing.
   See `besu/repo-hygiene-pattern.md` and `nethermind/repo-hygiene-pattern.md` for the
   leanest, most directly adaptable templates. **Manual step**: enable "Private
   vulnerability reporting" in repo Settings → Security.
2. **`.github/workflows/dependency-review.yml`** — stock `actions/dependency-review-action@v4`,
   `fail-on-severity: high`. **Prerequisite, easy to miss:** this action only diffs what's in
   GitHub's Dependency Graph, and nothing populates that graph for sbt on its own — add
   `scalacenter/sbt-dependency-submission` first (submits fukuii's actual resolved sbt
   dependency graph via the Dependency Submission API) or `dependency-review.yml` will run
   green while silently checking nothing. Also add `nMoncho/sbt-dependency-check` (maintained
   fork of the OWASP Dependency-Check sbt plugin — the original `albuch/sbt-dependency-check`
   is archived/unmaintained, don't reach for it) as a complementary CI job scanning directly
   against the NVD CVE database; needs an NVD API key provisioned as a repo secret (same
   "manual step" pattern as REPO-03's `CLAUDE_CODE_OAUTH_TOKEN`). Keep all of this clearly
   distinct from the existing `dependency-check.yml` (version-currency scheduled diff, not a
   vulnerability scan) with a cross-comment in both. Confirmed current as of 2026-07 — GitHub's
   own Dependabot added native sbt *version-update* support in May 2026, but that's currency
   monitoring, not CVE/security alerting; the submission-action path above is still required
   for that.
3. **Lightweight `.github/CODEOWNERS`** — root catch-all `* @realcodywburns @chris-mercer`
   (Cody Burns/Chippr Robotics LLC + Christopher Mercer/White B0x Inc — both real, active
   maintainers, confirmed via `git log`). Every surveyed client with an empty or
   near-trivial CODEOWNERS (Besu at 20 maintainers, core-geth) validates that a flat
   catch-all is the right starting granularity, not a per-module file — grow into per-module
   density (Nethermind's per-project, Reth's per-crate, go-ethereum's per-package models are
   the references) only once fukuii has 3+ regular contributors with distinct subsystem
   ownership.

## Tier 2 — CI security scanning

4. **`.github/workflows/semgrep.yml`** — **not CodeQL** (verified: no Scala extractor
   exists upstream). `p/scala` + `p/security-audit` + `p/secrets` rulesets (free Community
   Edition), SARIF → Security tab via `github/codeql-action/upload-sarif@v3` (generic
   SARIF-ingestion only, not running CodeQL — comment this in the workflow so it isn't
   misread later).
5. **`.github/workflows/container-scan.yml`** — Trivy against the images `docker.yml`
   already publishes to `ghcr.io`, daily schedule + `workflow_dispatch`, `exit-code: 0`
   (report-only) burn-in period before tightening to fail-on-finding.
6. **Dependency hash-pinning (needs design, not urgent)** — Besu's
   `gradle/verification-metadata.xml` (cryptographic hash-pinning for every resolved
   dependency artifact) is a genuinely tool-agnostic supply-chain-hardening idea with no
   sbt/Coursier equivalent noted in fukuii today. Worth a dedicated research spike on
   whether Coursier has an analogous lock-and-verify mechanism before committing to build
   one — see `besu/build-release-pattern.md`.

## Tier 3 — Cheap, high-value documentation

7. **`docs/architecture/FORK_DIVERGENCE.md`** (new, living doc) — core-geth's exact
   3-section model (Additional Features / Divergent Design / Limitations), with a worked
   before/after code example in the "Divergent Design" section per core-geth's own pattern.
   See `core-geth/repo-hygiene-pattern.md` for the full quoted template (`docs/core/index.md`).
   Explicitly avoid core-geth's own anti-pattern: an undocumented 24,839-line raw `git.diff`
   checked into their repo root with zero explanatory context — never do this.
8. **Postmortem convention** (new template only, don't touch existing archived docs) —
   `docs/runbooks/incident-postmortem-template.md` modeled on go-ethereum's
   `docs/postmortems/2021-08-22-split-postmortem.md` (the standout artifact of the entire
   survey — full timeline, byte-level root cause, honest retrospective, coordinated-
   disclosure notes). Cross-link fukuii's existing `docs/historical/reviews/*.md` (4 already-
   frozen docs) as informal prior art — do not retrofit them.

## Tier 4 — Agentic tooling: CI-integrated review bot

9. **`.github/workflows/claude.yml`** — build now (per approved plan), informed by BOTH of
   Nethermind's Claude Code Action workflows (`claude-review.yml` the primary review-bot
   reference, plus the newly-found `gas-benchmark-analysis.yml` — a comment-triggered
   `/gas-benchmark`-style pattern worth keeping in mind for Tier 6's benchmark skill) and
   Erigon's `claude.yml` (confirmed to have **no** fork-PR security gate at all — don't copy
   that laxity). Design: mention-only trigger, `author_association` fork-PR gate (ported
   from Nethermind), `sonnet` default model, prompt-composed routing off
   `consensus-change-protocol.md`'s existing path table (consensus paths → enforce the
   protocol + blocking-severity finding if unacknowledged; non-consensus → reference
   `prism.md`'s 8-lens checklist by path; `network/` → `herald.md`; `db/` → `vault.md`).
   Pilot Task-tool subagent delegation before depending on it. **Manual step**: provision
   `CLAUDE_CODE_OAUTH_TOKEN`. See `nethermind/agentic-tooling-pattern.md` and
   `erigon/agentic-tooling-pattern.md` for both full workflow reads.
10. **Deferred**: Nethermind's fuller auto-review + structured-verdict + branch-protection-
    gate shape — revisit once Tier 1's CODEOWNERS is in active use with a real second
    reviewer.

## Tier 5 — Per-subsystem `AGENTS.md` breadcrumbs

11. Three files, Erigon's flat-breadcrumb pattern (`blockchain/sync/AGENTS.md`,
    `consensus/AGENTS.md`, `db/AGENTS.md`) — per the approved plan, unchanged.
12. **New consideration from the swarm**: Erigon also has a *nested* `CLAUDE.md` tree under
    `cl/` (`cl/CLAUDE.md`, `cl/phase1/forkchoice/CLAUDE.md`, `cl/transition/CLAUDE.md`,
    `cl/phase1/core/state/CLAUDE.md`) — function-level Go-code-to-consensus-spec maps,
    fork-by-fork. This is a *deeper* pattern than the flat breadcrumbs and could be a good
    model specifically for fukuii's `consensus/` subsystem (which has its own ETC-vs-ETH
    fork-by-fork complexity: `pow/`, `engine/`, `validators/{pow,std}/`). Flagged as a
    "consider going deeper here" note for item 11's `consensus/AGENTS.md`, not a 4th
    breadcrumb file — see `erigon/agentic-tooling-pattern.md`.

## Tier 6 — New developer-workflow skills

The core finding: fukuii's skill set is real (36 skills) but skewed toward operator/
node-lifecycle work (19) plus a small existing dev-workflow set (3: `fukuii-dependency-audit`,
`fukuii-tech-debt-inventory`, `pekko-resource-audit`) and project-management (1:
`fukuii-sprint-queue`) — see `fukuii/dev-workflow-skills-pattern.md` for the corrected
baseline. None of the existing 3 dev-workflow skills touch build/test/benchmark/EIP-
implementation/EF-test-triage. This tier closes that specific gap:

13. **`fukuii-implement-eip`** (from Erigon's `erigon-implement-eip`) — the single most
    valuable port. Wraps fukuii's EXISTING `consensus-change-protocol.md` routing and
    `forge`/`beacon` subagents in a structured spec-fetch → dependent-EIP/ECIP mapping →
    prior-work check → implement → `eye` validation → wrap-up-summary pipeline. Two
    flavors (EIP→beacon, ECIP→forge). See `erigon/dev-workflow-skills-pattern.md`.
14. **`fukuii-ethtest-triage`** (from Nethermind's `fix-nethtest`) — the 4-phase EF-test
    failure-taxonomy decision tree, genuinely portable since fukuii runs the identical EF
    corpus. **Concrete implementation detail found**: fukuii already has
    `StructLogTracer.scala` implementing the exact go-ethereum-compatible trace field set
    (`pc`/`op`/`gas`/`gasCost`/`stack`/`depth`/`opName`/`error`) but it's currently wired
    only into `debug_traceTransaction`, NOT into the ethtest specs — wiring it in is a
    prerequisite sub-task for this skill to have real per-opcode trace data to work with,
    not just the flat pass/fail string the harness currently returns. See
    `nethermind/dev-workflow-skills-pattern.md`.
15. **`fukuii-build`**, **`fukuii-test-unit`/`fukuii-test-all`** (from `erigon-build`/
    `erigon-test-unit`/`erigon-test-all`/`erigon-ci`) — trivial sbt-alias wrappers + a new
    remote CI-dispatch/monitor half (`gh workflow run <name>` / `gh run watch`) for fukuii's
    27 workflow files, which the existing sbt aliases don't cover.
16. **`fukuii-network-ports`** (from `erigon-network-ports`) — reference skill.
17. **`fukuii-ephemeral`** (from `erigon-ephemeral`) — throwaway/sandboxed node instance,
    port-offset conflict detection, structured cleanup.
18. **`fukuii-benchmark-diff`** — reconsider scope given the swarm's findings. Nethermind's
    `gas-benchmark` full CI/Docker/dotTrace superstructure stays blocked (confirmed heavier,
    not lighter, when compared against Reth's — see below). But **core-geth's
    `bench-core.yml`/`bench-trie.yml`/`bench-vm.yml` pattern is genuinely lightweight**:
    plain `go test -bench` + `benchstat` diffing against a pinned upstream tag, no
    self-hosted runners/ChatOps/infra at all (see `core-geth/build-release-pattern.md`).
    This changes the verdict from "local-diff-only, CI stays deferred indefinitely" to
    "a CI-gated lightweight benchmark tier using fukuii's existing `Benchmark` sbt module
    (`MerklePatriciaTreeSpeedSpec`/`RLPSpeedSuite`) is plausibly a near-term 'port now,' not
    just a local workaround." Design both: (a) the local-only diff subset (original scope),
    and (b) a core-geth-style lightweight CI job, as two separate, independently-gateable
    pieces of this skill/workflow pair.
19. **`fukuii-rlp-roundtrip`** (from go-ethereum's `FuzzRLP`) — a decode→encode→byte-
    identical-round-trip ScalaCheck property (ScalaCheck already a dependency). **Concrete
    design already sketched**: fukuii's existing `RLPSuite.scala` has 9 `forAll(Gen...)`
    round-trip tests, but all of them generate *valid domain values first* — none feed
    arbitrary bytes straight to the decoder the way a real fuzz-style test does. The new
    property should target that specific gap using `RLP.rawDecode`/`decode[T]`/
    `RLPException` directly. See `go-ethereum/dev-workflow-pattern.md` for the exact test
    code sketch with fukuii's real API line citations.
20. **`fukuii-test-hive`** (from Erigon's `hive-test`/`erigon-test-hive`, but grounded
    directly in fukuii's own Hive setup, not just adapted from Erigon's Go/Docker specifics)
    — **a full, ready-to-copy `SKILL.md` draft already exists** at
    `hive/fukuii-test-hive-skill-design.md`. Key grounding finding: fukuii's
    `hive/test-local.sh` is only a ~60-second adapter smoke test (build+curl+stop), not a
    real simulator wrapper — so this skill is genuinely new capability layered above
    `_hive-sim.yml`'s CI pipeline, not just "wrap an existing script." The design correctly
    scopes cleanup to Hive's own documented `--cleanup`/`--cleanup.dry-run`/
    `--cleanup.older-than` flags (verified present in the vendored `hive.go`) rather than a
    blanket `docker system prune`, and explicitly rejects Erigon's "run suites in parallel"
    advice given this machine's one-heavy-task-at-a-time resource constraint.

## Tier 7 — PR title/format validation and dependency-gate promotion

21. **PR title/format validator** (from go-ethereum's `validate_pr.yml`, full text quoted in
    `go-ethereum/agentic-tooling-pattern.md`) — adapt the spam-title regex + directory-
    existence checker to fukuii's own conventions. Standalone-useful regardless of fork-PR
    volume — note the source's own irony (its directory-checker was itself AI-generated,
    later used to reject AI-slop).
22. **Promote the Akka-BSL grep to a CI gate** (from go-ethereum's `check_baddeps`, exact
    2-rule denylist quoted in `go-ethereum/dev-workflow-pattern.md`) — `fukuii-dependency-
    audit`'s existing "zero Akka imports" grep is currently on-demand only; wire it into
    `ci.yml` or a dedicated lint job. The logic already exists.
23. **`hardfork-implementation-checklist.md`** (new protocol doc, from Reth's
    `HARDFORK-CHECKLIST.md`, full 27-line text quoted in `reth/agentic-tooling-pattern.md`)
    — a mechanical, checkbox-style extension-point map (fork-config classes
    `OlympiaOpCodes`/`OsakaOpCodes`, ECIP-1017 emission tables, Engine API validator
    equivalents, genesis/chain-config schema), complementing (not replacing)
    `consensus-change-protocol.md`'s process-gate framing.

## Tier 8 — EF-test sharding for `ethereum-tests-nightly.yml`

24. fukuii currently runs the whole EF suite in one serial job (confirmed via the fukuii
    self-audit). Two reference patterns now documented, in increasing sophistication:
    Besu's simple 4-way round-robin split (`splitList.sh` + a file-count safety check) and
    Erigon's manifest-driven sharding (`tools/eest-spec-shards.yml`, read by both `Makefile`
    and CI, with per-shard failure budgets requiring a tracking issue to raise — see
    `erigon/build-release-pattern.md`). **Recommend Erigon's manifest-driven approach as the
    primary model** (single source of truth, not duplicated shard logic between local and CI
    invocation) with Besu's file-count safety check folded in. Besu's own timing-balanced
    variant (`splitTestsByTime.sh`) is a separate, more complex mechanism used by their
    14-way `acceptance-tests.yml`, not their `reference-tests.yml` — sequence that
    refinement, if wanted, after the manifest-driven simple split is proven.

## Tier 9 — Hive integration bug fixes (found during research, not hypothetical)

Concrete, verified bugs in fukuii's own current Hive CI integration — see
`hive/architecture-pattern.md` for full detail on each:

25. **`hive-osaka.yml`/`hive-prague.yml` duplicate the entire `_hive-sim.yml` pipeline**
    (~200 lines each) instead of calling the reusable workflow like the other 11 do —
    driftable duplication. Fix: extend `_hive-sim.yml` with a fixtures-URL-resolution input
    both callers need, then convert them to call it like the rest.
26. **fukuii lacks the `eth1_snap` role / `HIVE_NODETYPE` handling** in `hive/fukuii/
    fukuii.sh` — silently excludes fukuii from `ethereum/sync`'s snapsync suite despite
    having a real SNAP-sync implementation. Worth fixing given SNAP sync is one of fukuii's
    most actively-developed subsystems.
27. **`HIVE_LOGLEVEL` unhandled** in `fukuii.sh` (a documented "must support" env var) —
    small gap.
28. **`enode.sh`'s hardcoded placeholder fallback** (`enode://unknown@127.0.0.1:30303`,
    exit 0) contradicts fukuii's own "fail loudly" working-discipline principle — neither
    reference client (geth/besu) fabricates a value on failure.

## Tier 10 — `.agents/` sub-tree reorganization

29. Per the approved plan: empirical skill-nesting test first (create a throwaway probe
    skill in a subdirectory, confirm Claude Code actually discovers it — no documentation
    found either way), then reorganize `.agents/skills/` into `ops/`/`dev/`/`project/`/
    `speckit/` and `.agents/protocols/` into thematic groups, OR fall back to a naming-prefix
    scheme if nesting doesn't work. Sequence this BEFORE Tier 6's new skills land, so they go
    directly into the right place.

## Comment-policy hook (unchanged from approved plan)

30. Document the enforceable rule first (`.agents/protocols/scala3-style.md` or new
    `comments.md`), then `.claude/hooks/comment-policy.py` (Scala-adapted from Erigon's Go
    version), then a new *tracked* `.claude/settings.json` + `.gitignore` exceptions —
    never the existing operator-local `.claude/settings.local.json`.

---

## Explicitly mapped for later (documented, gated, not built this pass)

| Item | Source | Gate |
|---|---|---|
| Full gas-benchmarks/dotTrace/Reth-bench.yml-equivalent CI pipeline | Nethermind, Reth | Confirmed both are heavier infra than initially assumed (self-hosted runners, schelk/ClickHouse/Slack for Reth); the lightweight core-geth-style tier (Tier 6, item 18b) is the near-term substitute |
| Nethermind's full auto-review+structured-verdict+branch-protection CI-bot shape | Nethermind | Tier 1 CODEOWNERS + real branch protection in active use with a second reviewer |
| Reth's statistical benchmark method (bootstrap CI + significance floor) | Reth | Worth pre-registering as a design once ANY benchmark pipeline exists — independent of Reth's own heavy infra |
| `erigon-test-race`-equivalent (JVM/Pekko concurrency-bug detection) | Erigon | No JVM `-race` analog exists; needs its own tool/design |
| `erigon-test-rpc`/golden-fixture RPC conformance | Erigon | Confirmed fukuii already has extensive Hive CI plumbing (`_hive-sim.yml`) — flips this from "needs design" toward "mostly covered," revisit once Tier 6's `fukuii-test-hive` lands |
| `kurtosis-test`-style local multi-client testnet + cross-client divergence-triage methodology ("nobody is right yet") | Erigon | New local-testnet infra; the triage methodology itself is worth designing once local multi-client testing exists, applying naturally to fukuii's vendored reference clients |
| `erigon-cherry-pick`/`github-pr-cleanup` backport automation | Erigon | Contingent on fukuii adopting a release-branch model (doesn't have one) |
| RocksDB-native compaction skill (goal of `erigon-mdbx-compact`) | Erigon | Needs design via `vault`/`storage-rocksdb.md` — MDBX vs RocksDB mechanism differs entirely |
| `internal/build`-style release-automation library | go-ethereum | No urgent need — fukuii's release pipeline isn't complex enough yet |
| `tools/DocGen`-style generated-from-source docs (config/RPC/metrics reference) | Nethermind | Watch for later — only relevant if fukuii's config/RPC-surface docs start drifting from source in practice; no drift problem observed today |
| Observability `docker-compose.yml` (Loki/Promtail + lightweight Kong-free entry point) | Reth, Erigon | fukuii already has `ops/barad-dur/` (Prometheus+Grafana+15 dashboards) — this is "add a tier," not "build from scratch" |
| `docs/operations/creating-a-dashboard.md` | Erigon | Low effort, could move up if desired |
| Reviewer-facing `CONTRIBUTING.md` section | Reth | Revisit alongside CODEOWNERS/branch-protection maturity |
| Node-facing MCP server as a product feature | Erigon | Product-direction question, bigger scope than current dev-facing MCP wiring |
| `llms.txt`/`llms-full.txt` | Erigon | Cheap, fold into next docs-site work |
| ECIP-specific test-fixture regeneration tooling | core-geth | Contingent — fukuii doesn't currently fork its own copy of the EF test corpus |
| `zizmor`-equivalent GitHub Actions security linter | Erigon | Cheap, stock tool — could pair with Tier 2's security scanning, not urgent |
| ExEx-equivalent plugin architecture, `examples/`-style library-usage crates | Reth | Only relevant if fukuii's product direction shifts toward embeddable-library consumption — it hasn't |
| Assisted-By/model-metadata DCO convention | Besu | Only relevant if/when fukuii adopts DCO sign-off |
| Anti-AI-slop PR auto-closer (distinct from Tier 7's title validator) | go-ethereum | Only matters at meaningful outside-contributor PR volume |
