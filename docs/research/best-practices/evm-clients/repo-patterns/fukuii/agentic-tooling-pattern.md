# Fukuii — Agentic Tooling Baseline (self-audit)

This document audits fukuii's OWN current agentic-tooling state with the same rigor applied
to the 6 vendored reference clients in sibling directories — a ground-truth baseline for the
comparison, verified fresh against the live repo (`/media/dev/2tb/dev/fukuii/`, real repo
root, not a vendored clone) on **2026-07-03** rather than assumed from prior session context.

Verification method: every file below was read directly (full read for the primary docs;
first ~10-15 lines plus targeted greps for the protocol/skill inventories; YAML frontmatter
read for all subagent files; `git ls-files` / `git check-ignore` used to confirm tracked vs.
untracked status). No claim here is carried over from an earlier summary.

---

## AGENTS.md / CLAUDE.md — current structure

Both files carry `_Last reviewed: 2026-07-03_` headers and are explicitly split by a
documented rationale (`docs/agentic-tooling/agents-md-decision-2026.md`): **AGENTS.md is
portable, tool-agnostic project context**; **CLAUDE.md is Claude-Code-specific
orchestration** that imports AGENTS.md via `@AGENTS.md` (the `@path` import mechanism only
resolves for Claude Code — the dependency direction is deliberate and cannot run the other
way, per CLAUDE.md's own header comment).

### `AGENTS.md` (131 lines) — actual sections, in order

1. **Header / provenance note** — states the portable/tool-agnostic purpose and points at
   `docs/agentic-tooling/agents-md-decision-2026.md` for the split rationale.
2. **Intro paragraph** — fukuii is a multi-network EVM client (forked from IOHK Mantis,
   repackaged under `com.chipprbots`), Scala 3.x LTS, Pekko actors. Describes **two
   consensus families, each currently one network pair**: PoW (ETC/Mordor, chain ID 61/63)
   and PoS (ETH/Sepolia, chain ID 1/11155111) — explicitly framed as "current instances,"
   not a ceiling, since network coverage is expected to grow within each family.
3. **"PoW vs PoS — read this first"** — ETC-keeps vs. ETH/Sepolia-has bullet lists; the
   `OlympiaOpCodes`/`forBlock()` vs. `OsakaOpCodes`/`forTimestamp()` code-path rule.
4. **"Build & test commands"** — a 10-row table (`sbt compile-all`, `compile`, `scalafmtAll`,
   `scalafmt` [flagged wrong], `formatAll`, `formatCheck`, `pp`, `testOnly`, three
   `scripts/agent-tooling/sbt-run.sh <name> <tier>` background rows, tagged-subset row,
   IntegrationTest row) plus a 4-step "test cadence during a migration thread" list and a
   "two format commands that look similar but are not" callout. Explicitly says `build.sbt`
   is the authoritative list of tagged test aliases, not this table.
5. **"Working discipline (applies to every task)"** — 7 bullets: sequential thinking before
   action, failure-is-information, small-batches-then-checkpoint (~3 changes),
   evidence standards (`VERIFY: ran <command> — result: PASS | FAIL | DID NOT RUN`),
   Chesterton's Fence, root-cause-not-symptom, fail-loudly (no silent `catch {}`),
   irreversible-decisions-get-10x-thought.
6. **"Conventions"** — stage files individually (no `git add .`), `scalafmtAll` before
   migration commits vs. `formatAll` (includes scalafix) only pre-PR, refer to the human as
   "user."
7. **"Full contributor workflow"** — pointer to `docs/development/contributing.md` for
   fork/clone/pre-commit/Scalafmt-Scalafix-Scapegoat-Scoverage/release/CI detail (explicitly
   not duplicated here).
8. **"MCP tooling for a running node"** — pointer to `.github/copilot/README.md` and
   `.github/copilot/mcp.json`, with the note that this is usable by any MCP-aware tool, not
   just Copilot.
9. **"Where agent protocols and skills actually live"** — states the canonical-source
   convention: `.agents/protocols/` and `.agents/skills/` are canonical; `.claude/agent-protocols/`
   and `.claude/skills/` are symlinks kept for Claude Code's discovery paths. Points to
   `.agents/protocols/agent-skills.md` for the full convention.
10. **"MCP tooling for a running node" (duplicate section)** — the same MCP section from
    item 8 appears again verbatim at the end of the file (lines 125-131 repeat lines
    108-114). This is a literal duplication in the live file as of this read, not an
    artifact of this audit — worth fixing but not this document's job to fix.

### `CLAUDE.md` (214 lines) — actual sections, in order

1. **Header / provenance note** — states CLAUDE.md carries only Claude-Code-specific
   orchestration; portable content lives in AGENTS.md; points to
   `docs/agentic-tooling/claude-md-refresh-2026.md` (the audit that produced this
   structure) and `agents-md-decision-2026.md` (why the import points this direction).
2. **`@AGENTS.md`** — the literal import line (line 12).
3. **"Shared agent protocols"** — a 22-row table mapping each protocol file (referenced by
   its `.claude/agent-protocols/` symlink path, edited canonically at `.agents/protocols/`)
   to a one-line purpose. Verified against the live `.agents/protocols/` directory below —
   every row in this table corresponds to a real file; no stale entries found.
4. **"Reference index"** — a bulleted list covering what else lives under `.claude/`:
   - `.claude/skills/` — states "22 `fukuii-*` operational skills ... 13 `speckit-*` skills
     ... plus `pekko-resource-audit`" — **verified accurate**: live count is exactly
     22 + 13 + 1 = 36 (see skills inventory below).
   - `.claude/looping/` — one-line description of the DISCOVER→PLAN→EXECUTE→VERIFY harness,
     correctly pointing at `registry.yaml` (not file headers) as the loop-eligibility source
     of truth.
   - `.claude/repo-references/` — ~20 vendored reference repos; points to
     `.claude/agents/REFERENCES.md` for the per-agent mapping (verified present, 29,477
     bytes, contains the full clone convention and repository index).
   - `docs/research/best-practices/` — this pattern library itself.
   - `.claude/sprints/` — the live work-tracking system (`QUEUE.md`, `completed/`,
     `archive/`, `log/`, `patterns/`), flagged as operator-local/mostly-untracked and as
     "usually where the fastest-moving, actually-current work lives."
5. **"Specialist subagents"** — a 12-row table (`forge`, `beacon`, `eye`, `wraith`,
   `herald`, `mithril`, `prism`, `loom`, `vault`, `conduit`, `flow`, `warden`) with
   use-case and "Proactive?" columns. **Verified accurate**: exactly 12 agent definition
   files exist at `.claude/agents/*.md` (excluding `REFERENCES.md`, which is documentation,
   not an agent definition) — see subagent roster below.
6. **"Consensus-Critical Change Protocol (mandatory)"** — a 5-step order (identify PoW/PoS
   family → plan in main session → `forge`/`beacon` impact analysis → `wraith` fixes
   compile errors → `eye` validates) plus trigger conditions (EIP/ECIP mentions; changes
   under `consensus/`, `vm/`, `crypto/`, `domain/`) and explicit skip conditions (docs-only,
   build config, non-consensus test infra, pure network formatting).
7. **"OODA loop for large migrations / multi-file work"** — Observe/Orient/Decide/Act,
   each with fukuii-specific detail (P0-P3 prioritization in Orient; route to specialist
   subagent in Decide).
8. **"Continuation protocol (applies to every agent)"** — file path convention
   (`.local/docs/continuations/<AgentName>-<Topic>.md`), a standard-sections template
   (Status / Files modified / Open compile errors / Next action / Test baseline), and a
   note that prior June-sprint summary directories may have moved to
   `.local/docs/archive/2026-06/`.
9. **"Spec-Driven Development (Spec Kit)"** — the `/speckit-specify` → `/speckit-plan` →
   `/speckit-tasks` → `/speckit-implement` pipeline (`/speckit-clarify`/`/speckit-analyze`
   for de-risking), a summary of `.specify/memory/constitution.md` highlights, the
   "two work tracks" callout (`specs/<NNN>/` vs. `.claude/sprints/QUEUE.md`), and an
   auto-regenerated `<!-- SPECKIT START --> ... <!-- SPECKIT END -->` block (currently
   pointing at `specs/007-hotpath-alloc-reduction/plan.md` as of this read — this block is
   silently overwritten by the `speckit-agent-context-update` skill on every run, so its
   content is a snapshot, not durable).

---

## .agents/protocols/ — full current inventory

23 files present (confirmed via `ls`). Every file was read (first ~15 lines minimum) to
verify its stated purpose against its actual header/first section. The `currency:` HTML
comment header convention (introduced by `dependency-currency.md`, which governs itself and
is exempt from needing a currency header of its own) is present on exactly **3 of 23**
files.

| File | Purpose (verified against actual content) | Currency header present |
|---|---|:---:|
| `agent-skills.md` | Canonical-source + symlink convention: `.agents/{protocols,skills}/` are canonical; `.claude/{agent-protocols,skills}/` are symlinks, never independent copies. Disambiguates `.agents/` vs `.claude/agents/` | N |
| `alert-wrapper-protocol.md` | STOP-AND-ALERT supervision pattern: `watchWith` + structured alarm instead of restart supervision, for actors where restart causes state corruption | N |
| `background-script-execution.md` | Long/noisy/freeze-prone commands must run via a log-to-file wrapper script in background mode — never foreground, never a copy-paste-and-wait relay to the operator | N |
| `consensus-change-protocol.md` | Hard-stop specialist-review requirement before touching consensus-critical code; defines FORGE (PoW/ETC-Mordor) vs. BEACON (PoS/ETH-Sepolia) scope | N |
| `dead-code-review.md` | Three verdicts before `git rm` — Wire it / Delete it / Defer — "zero call sites does not mean zero value" | N |
| `dependency-currency.md` | Keeps *prescriptive coding-pattern content* (not build pins) current for Scala 3 LTS / Pekko Typed; distinguishes itself from `fukuii-dependency-audit` skill (which checks pinned versions); defines the `currency:` header convention | Y (self-referential: "this protocol governs itself") |
| `finding-resolution.md` | Every audit/review finding must resolve to one of three dispositions — never left as a bare unscheduled note | N |
| `git-conventions.md` | Force-push confirmation, no silent code loss in merges, branch-naming outside the worktree system — ported from Nethermind's `git.md` | N |
| `github-workflows.md` | Workflow naming/concurrency conventions (kebab-case, `ci-${{ github.ref }}` group pattern), PR template, label automation — ported from Nethermind | N |
| `inline-cleanup.md` | "Hunt and Seek" — what to fix opportunistically in files already open vs. flag-but-don't-touch, to keep scope from exploding | N |
| `logging-standards.md` (23,945 bytes — largest protocol) | Logging-as-observability-instrument philosophy; Kamon→Micrometer→Prometheus→Grafana metrics stack; preferred SLF4J API/levels/message format | N |
| `loop-handoff.md` | Maker→checker handoff contract at the end of every EXECUTE phase in the looping subsystem; distinct from `migration-handoff.md` (one-off manual sessions) | N |
| `migration-handoff.md` | Continuation-file protocol when a session ends mid-migration with work remaining | N |
| `pekko-typed-api.md` (31,840 bytes — 2nd largest) | P1-P25 Pekko Typed API preferences + TL1/TL2 Cats Effect integration rules, each with a grep pattern for regression detection | **Y** — `<!-- currency: verified idiomatic for Scala 3.3.8 LTS / Pekko 1.6.0 — 2026-07-03 -->` |
| `pre-migration-checklist.md` | LOOM pre-flight checklist run before touching any file in a Classic→Typed migration (sender(), returns, timers, workers, `@volatile` etc.) | N |
| `risk-stratified-commit.md` | Bucket A (mechanical/compiler-guaranteed) / B / C commit-splitting discipline for sweep changes | N |
| `scala3-given-migration.md` | G1-G3 operational pitfalls from W2-P3a (`implicit`→`given/using`, 334 sites, commit `7210311bb`) — e.g. G1: wildcard imports don't pull `given` instances in Scala 3 | N |
| `scala3-style.md` | S1-S11 idiomatic Scala 3 standards, each grep-verifiable; S11 covers opaque-type full-layer propagation | **Y** — `<!-- currency: verified idiomatic for Scala 3.3.8 LTS / Pekko 1.6.0 — 2026-07-03 -->` |
| `sprint-lifecycle.md` | The permanent research→queue→implementation→close-out→clear→archive pipeline for sprint work; referenced by nearly every other protocol | N |
| `storage-rocksdb.md` | The `DataSource` contract (never call RocksDB directly), column families, iterator lifecycle, WriteBatch, `EphemDataSource`, RocksDB config | N |
| `testing-protocol.md` | The three-tier ADR-017 test cadence (compile-all per file → testOnly after logic changes → testEssential once pre-push); rationale: full suite is 24 minutes | N |
| `warning-ratchet.md` | 4-step pattern: inventory → risk-stratified commit → defer with `@nowarn` → promote cleared category to build error | N |
| `worktree-protocol.md` | Git worktree lifecycle for isolating in-flight work; `.claude/worktrees/<id>` on branch `wt/<id>`; bin scripts automate lifecycle | N |

**Observation on the currency-header convention:** only the two protocols that teach
version-sensitive *coding idioms* (`pekko-typed-api.md`, `scala3-style.md`) plus the
self-governing `dependency-currency.md` carry the header — this is consistent with
`dependency-currency.md`'s own stated scope ("prescriptive content... in
`.agents/protocols/*.md`"), which in principle applies to all 23, but in practice only the
two idiom-heavy docs have been dated. The other 20 protocols are process/workflow docs
(commit discipline, handoff contracts, git conventions) whose content isn't
version-sensitive in the same way, so the absence of a header on them does not by itself
indicate staleness — but it does mean `dependency-currency.md`'s own audit scope is broader
than what's currently been applied.

---

## .agents/skills/ — full current inventory

**36 total** directories (verified fresh via `ls -1 | wc -l`, not carried over from any
prior count): **22 `fukuii-*`** + **13 `speckit-*`** + **1 other** (`pekko-resource-audit`).
This exactly matches CLAUDE.md's own "Reference index" claim ("22 ... 13 ... plus
`pekko-resource-audit`") — CLAUDE.md's skill count is currently accurate, not stale.

`fukuii-*` (22, operational/node-lifecycle skills):
`fukuii-backup-restore`, `fukuii-checkpoint-service`, `fukuii-cl-setup`,
`fukuii-custom-networks`, `fukuii-dependency-audit`, `fukuii-disk-management`,
`fukuii-engine-api-debug`, `fukuii-engine-api-setup`, `fukuii-first-start`,
`fukuii-key-management`, `fukuii-log-triage`, `fukuii-mining-operations`,
`fukuii-node-configuration`, `fukuii-node-health-check`, `fukuii-peer-management`,
`fukuii-pos-node-health`, `fukuii-security-hardening`, `fukuii-sepolia-sync`,
`fukuii-sprint-queue`, `fukuii-sync-troubleshooting`, `fukuii-tech-debt-inventory`,
`fukuii-tls-operations`.

`speckit-*` (13, Spec Kit workflow skills):
`speckit-agent-context-update`, `speckit-analyze`, `speckit-bug-assess`,
`speckit-bug-fix`, `speckit-bug-test`, `speckit-checklist`, `speckit-clarify`,
`speckit-constitution`, `speckit-implement`, `speckit-plan`, `speckit-specify`,
`speckit-tasks`, `speckit-taskstoissues`.

Other (1): `pekko-resource-audit` — ported from Nethermind's `resource-leak-audit`
(uncancelled timers, missing `watchWith` cleanup, stream materialization leaks,
dispatcher starvation).

`.claude/skills/` was confirmed to be a directory of **symlinks** into `.agents/skills/`
(e.g. `fukuii-backup-restore -> ../../.agents/skills/fukuii-backup-restore`), plus one
non-symlinked file, `CONVENTIONS.md` (6,217 bytes), which lives only under `.claude/skills/`
and is not part of the canonical/symlink pair. Likewise `.claude/agent-protocols/` is
confirmed to be a directory of symlinks into `.agents/protocols/` (e.g.
`alert-wrapper-protocol.md -> ../../.agents/protocols/alert-wrapper-protocol.md`) — the
canonical-source-plus-symlink convention documented in `agent-skills.md` is real, not
aspirational.

---

## .claude/agents/ — subagent roster

**12 subagent definition files** confirmed at `.claude/agents/*.md` (a 13th file,
`REFERENCES.md`, is documentation — the per-agent reference-repo index — not an agent
definition, and correctly excluded from CLAUDE.md's 12-row table). Every file's YAML
frontmatter (`name`, `description`, `tools`, `model`, `color`) was read directly.

| Agent | Domain (from frontmatter `description`) | Tools | Model | Proactive? (per CLAUDE.md) |
|---|---|---|---|:---:|
| `forge` | PoW consensus specialist (currently ETC/Mordor) — EVM, Ethash, crypto, state, rewards, hard forks, EIP/ECIP. `OlympiaOpCodes`/`forBlock()`. Byte-perfect vs. core-geth | Read, Grep, Glob, Edit, Write, Bash | opus | **Yes** — before any PoW consensus change |
| `beacon` | PoS consensus specialist (currently ETH/Sepolia) — Osaka, EIPs, timestamp fork dispatch, withdrawals, blobs, execution payload. `OsakaOpCodes`/`forTimestamp()`. Byte-perfect vs. go-ethereum | Read, Grep, Glob, Edit, Write, Bash | opus | **Yes** — before any PoS consensus change |
| `eye` | Validation: compile + run appropriate test tier, chain-compatibility check (ETC vs. ETH specifics), performance regressions. Read-only, does not edit source | Read, Grep, Glob, Bash | sonnet | **Yes** — after code changes |
| `wraith` | Scala 3 compile-error specialist; categorizes errors, applies known Scala 2→3 fix patterns, preserves semantics exactly | Read, Grep, Glob, Edit, Bash | sonnet | **Yes** — on compile failures |
| `herald` | P2P/RLPx/ETH wire protocol specialist; peer disconnects, Snappy failures, ForkId/handshake, devp2p v4/v5 discovery, TCP server infra. ETH68/69/70 (ETH63-67 removed) | Read, Grep, Glob, Edit, Bash | sonnet | On networking issues |
| `mithril` | Scala 3 modernization (opaque types, enums, given/using, union types); preserves behavior exactly | Read, Grep, Glob, Edit, Bash | sonnet | On-demand |
| `prism` | 8-lens code quality review (functionality, tests, readability, structure, simplicity, performance, security, scala-fp), non-consensus code only; never edits source | Read, Grep, Glob, Bash | sonnet | Before PRs on non-consensus code |
| `loom` | Pekko Classic→Typed migration, one actor per session, pre-flight before touching files, delegates to wraith/prism/forge/beacon | Read, Grep, Glob, Edit, Bash | opus | Does NOT auto-invoke — explicit per-actor call |
| `vault` | RocksDB/storage layer (`db/`, 50 files) — DataSource contract, LRU cache sizing, batch commit, iterator lifecycle | Read, Grep, Glob, Edit, Bash | sonnet | On storage bugs/config changes |
| `conduit` | JSON-RPC/HTTP/WebSocket/IPC/GraphQL (`jsonrpc/`, 79 files) — method compliance, transport, codec, subscriptions | Read, Grep, Glob, Edit, Bash | sonnet | On API/transport bugs |
| `flow` | Pekko Streams — Source/Sink/Flow, materialization, backpressure, `preMaterialize` anti-patterns, stream test sync | Read, Grep, Glob, Edit, Bash | sonnet | On streaming graph bugs/silent drops |
| `warden` | fukuii's own Claude tooling & integration specialist — `scripts/agent-tooling/`, agent-protocols, looping subsystem, worktrees, Workflow-based sprint automation, permission/settings guidance, AND any direct Claude API/Agent SDK/MCP usage. Explicitly does NOT touch Scala/EVM domain code | Read, Grep, Glob, Edit, Bash | sonnet | On-demand, `.claude/` tooling work only |

Note on tool grants: `forge` and `beacon` are the only two agents with `Write` in addition
to `Edit` (consistent with them being the two agents expected to author new
consensus-config/fork-schedule code, not just edit existing files). All others have
`Read, Grep, Glob, Edit, Bash` (or, for the two read-only reviewers `eye` and `prism`,
`Read, Grep, Glob, Bash` with no `Edit`/`Write`).

**Loop-harness participation is 11 of the 12, by design, not staleness.** `warden.md`'s own
body states: "The other 11 specialists (mithril, wraith, loom, eye, prism, forge, beacon,
herald, vault, conduit, flow) own the Scala 3 / multi-network EVM domain" — `warden` is
deliberately excluded because it doesn't do domain-code maker/checker work. This is
confirmed independently by `.claude/looping/registry.yaml`, whose `agents:` map lists
exactly these same 11 (no `warden` entry), and by `.claude/looping/README.md`'s "Build
Report" section, which states "All 11 agents in `.claude/agents/` have loop-metadata
blocks" — a true statement as of this read, not a stale count from before `warden` existed.

---

## .claude/looping/ — automation harness

Directory contents confirmed: `README.md` (7,961 bytes), `DISCOVERY.md` (6,833 bytes),
`ELIGIBILITY.md` (2,987 bytes), `LOOP_SPEC.md` (5,075 bytes), `registry.yaml` (6,085
bytes), plus `bin/`, `recipes/`, `state/`, `verify/` subdirectories.

**Mechanism** (DISCOVER → PLAN → EXECUTE → VERIFY → ITERATE, per `LOOP_SPEC.md`):

- **DISCOVER** — work out the delta between current state and goal.
- **PLAN** — state the single highest-impact next step.
- **EXECUTE** — make the smallest change that moves the gate; the **maker** owns this
  phase (`wraith`, `mithril`, `loom` — all `never_self_check: true` in `registry.yaml`).
- **VERIFY** — run `verify.sh <recipe-id>`; read *sentinel lines*, never the maker's
  narrative claim. The **checker** (`eye`, `prism`, `forge`, `beacon`, `herald`, `vault`,
  `conduit`, `flow`) owns confirmation. `forge` and `beacon` are marked `proactive: true` —
  they must be consulted in DISCOVER, *before* the maker executes, per the
  Consensus-Critical Change Protocol.
- **ITERATE** — gate failed or delta remains → record failure, fix weakest point, repeat.

**Ralph Guard** (self-grading prevention): a loop may declare DONE only when (1) the
transcript shows `LOOP:<id> ALL_GATES:PASS`, (2) the checker explicitly states
`CONFIRM:DONE` referencing that line, and (3) the ledger shows no open delta. A maker
asserting "I'm done" is never sufficient on its own.

**Sentinel line protocol**: gate scripts (`verify/compile.sh`, `warnings.sh`, `tests.sh`,
`format.sh`, `conformance.sh`) each print exactly one `GATE:<name> RESULT:PASS|FAIL
detail=<reason>` line; `bin/verify.sh` aggregates into `LOOP:<id> ALL_GATES:PASS|FAIL`.

**Recipes** (5, under `recipes/*.loop.md`): `warning-ratchet`, `spec-conformance`,
`test-greening`, `actor-migration`, `ref-parity-audit`. Each recipe fills a fixed YAML
schema (`id`, `goal`, `maker`, `checker`, `gates`, `refresh_refs`, `constraints`, `budget`
with `max_iterations`/`max_tokens`/`max_wallclock`/`min_accept_rate`, `stop_on`).

**State ledger** (`state/<id>-<timestamp>/`): `ledger.md` (append-only iteration log),
`attempts.json` (per-iteration approach/outcome/reason), `start_time`, `ref_shas.md`. Four
real run directories exist under `state/` from a 2026-06-24 bootstrap/self-verification
pass (`self-verify-20260624T223503Z`, three `warning-ratchet-*` runs) — this is not just
a designed-but-unused mechanism; it has actually executed.

**Reference-repo discipline**: `refresh-refs.sh` pulls only the `upstream` branch for each
reference client; `main` (where ETC overlays exist for besu/core-geth/nethermind) is never
touched by the loop subsystem — conformance gates always diff against `upstream` (except
core-geth, whose `upstream` is deprecated/stale since 2024, so its overlay `main` is what
gets diffed instead, per both `registry.yaml`'s comment and `REFERENCES.md`'s matching
explanation).

---

## .specify/ — Spec Kit framework

**Constitution**: `.specify/memory/constitution.md` — **version 1.1.2**, ratified
2026-06-05, **last amended 2026-07-03**. Three SYNC IMPACT REPORT blocks are stacked at the
top of the file documenting the amendment history: 1.0.0 (initial ratification, 7
principles) → 1.1.0 (2026-06-13, reframed from ETC-only to multi-network ETC+ETH/Sepolia)
→ 1.1.1 (2026-07-03, fixed 6 incorrect `.github/agents/*.md` path references to the correct
`.claude/agents/*.md`) → 1.1.2 (2026-07-03, same day, reframed "two networks" language to
"two consensus families, each currently one network pair" — a wording-only PATCH, no
principle changed).

Seven core principles: **I. Consensus Determinism Is Sacred (NON-NEGOTIABLE)** — PoW domain
(forge) vs. PoS domain (beacon), `OlympiaOpCodes`/`forBlock()` vs. `OsakaOpCodes`/
`forTimestamp()`, wire-format-per-negotiated-capability (herald); **II. Spec-Driven
Development** — the speckit pipeline, Constitution Check gate; **III. Test Discipline &
Tiered Coverage** — no `Thread.sleep`, three tiers, ≥70% statement coverage; **IV.
Idiomatic, Formatted Scala 3** — scalafmt 3.8.3/120-col, scalafix rule set, `com.chipprbots.ethereum`
namespace only; **V. Quality Gates Are Mandatory** — `sbt pp` before PR, CI must be green,
never bypass with `--no-verify`; **VI. Security & Operational Safety** — no committed
secrets, RPC private-by-default; **VII. Transparent Versioning & Decision Records** — semver,
conventional commit prefixes, ADRs under `docs/adr/`.

**Templates** (`.specify/templates/`, 5 files): `checklist-template.md`,
`constitution-template.md`, `plan-template.md`, `spec-template.md`, `tasks-template.md`.

**Scripts** (`.specify/scripts/bash/`, 5 files): `check-prerequisites.sh`, `common.sh`,
`create-new-feature.sh`, `setup-plan.sh`, `setup-tasks.sh`.

**Extensions** (`.specify/extensions/`): one extension installed — `agent-context`
(version 1.0.0, per `.specify/extensions/.registry`), registering the
`speckit.agent-context.update` command for both `claude` and `copilot` integrations, with
hooks configured to (optionally, `auto_execute_hooks: true`) run after `speckit-specify`
and `speckit-plan`. Contains `README.md`, `agent-context-config.yml`, `extension.yml`,
`commands/speckit.agent-context.update.md`, and both bash + PowerShell versions of
`scripts/update-agent-context.sh`.

**Workflows** (`.specify/workflows/`): `workflow-registry.json` and a single
`speckit/workflow.yml`.

**Integration state**: `.specify/integration.json` shows Spec Kit version `0.9.5.dev0`,
single installed integration `claude`. `.specify/integrations/claude.manifest.json` and
`speckit.manifest.json` are file-hash manifests (installed 2026-06-05) tracking which
`.claude/skills/speckit-*/SKILL.md` files and `.specify/{templates,scripts}/*` files belong
to the Spec Kit install, for drift detection.

**Currently active feature**: `.specify/feature.json` → `specs/007-hotpath-alloc-reduction`
— this is the same spec CLAUDE.md's auto-regenerated `<!-- SPECKIT START -->` block points
at, confirming the two are in sync as of this read.

**A related, easily-confused directory**: `.github/agents/speckit.agent-context.update.agent.md`
exists and is the *only* file under `.github/agents/` — this is the unrelated Spec Kit
context-update command definition referenced by the constitution's 1.1.1 sync report (which
corrected 6 mistaken references to `.github/agents/*.md` for subagent definitions — those
definitions have always lived at `.claude/agents/*.md`, never `.github/agents/`).

---

## .github/copilot/ — MCP server

Two files, both read in full: `mcp.json` (3,264 bytes) and `README.md` (4,970 bytes).

`mcp.json` defines one MCP server, `fukuii`, transport `http`, `url:
http://localhost:8545`, `env: { FUKUII_NETWORK: "etc" }`, `rpcApis: ["eth", "web3", "net",
"personal", "mcp"]`.

**Tools exposed (5)**: `mcp_node_status` (running state, sync status, peer count, current
block), `mcp_node_info` (version, network, capabilities), `mcp_blockchain_info` (latest
block number, difficulty, timestamp), `mcp_sync_status` (current/highest block, sync
percentage), `mcp_peer_list` (connected peers with connection info). All five have an empty
`"schema": {}` placeholder in the JSON — no parameter schema is actually defined yet.

**Resources exposed (5)**: `fukuii://node/status`, `fukuii://node/config`,
`fukuii://blockchain/latest`, `fukuii://peers/connected`, `fukuii://sync/status` — all
`application/json` mime type.

**Prompts exposed (3)**: `mcp_node_health_check` (comprehensive health check),
`mcp_sync_troubleshooting` (sync issue troubleshooting guide), `mcp_peer_management` (peer
management guidance).

`README.md` documents setup (enabling `mcp` in the `rpcApis` list via `application.conf`),
two Copilot configuration paths (repo-level, already wired via this file; user-level, via
VS Code settings or `~/.config/github-copilot/mcp-servers.json`), raw JSON-RPC examples for
`mcp_initialize`/`tools/list`/`tools/call`, a security-considerations note (localhost
default, no auth shown for production), and a troubleshooting section. Despite the
directory name (`copilot`), both AGENTS.md and this README explicitly note the config is
usable by any MCP-aware AI tool, not GitHub Copilot exclusively.

---

## scripts/agent-tooling/ — mechanical helper scripts

Confirmed at `/media/dev/2tb/dev/fukuii/scripts/agent-tooling/` — **the move from
`.claude/scripts/` referenced in AGENTS.md/CLAUDE.md's build-command table is complete and
consistent**: `.claude/scripts/` does not exist (`ls` returns "No such file or directory"),
and every reference in AGENTS.md/CLAUDE.md/the protocol files to this tooling already uses
the `scripts/agent-tooling/` path — no stale `.claude/scripts/` references were found in the
files read for this audit.

Two tiers, per the directory's own `README.md` (read in full, 3,950 bytes):

**Top-level (background-safe command wrappers)** — 4 files:
| Script | Wraps |
|---|---|
| `sbt-run.sh` | Any `sbt` task/sequence; logs to `.local/logs/<name>.log`, prints one `DONE log=... exit=N` line |
| `sprint-status.sh` | Reports `.claude/sprints/` state (batches, Chase & Deferred Items, completed/archive) |
| `sprint-clear.sh` | Moves CLOSED batches from `QUEUE.md` into `completed/` (dry-run default, `--apply` to write) |
| `sprint-archive.sh` | Moves a `completed/` file into `archive/` (dry-run default, `--apply` to write) |

**`lib/` (fast read-only mechanical collectors)** — 6 files, all confirmed present on disk
matching the README's table exactly:
| Script | Replaces |
|---|---|
| `site-sweep.sh` | Running N greps against `src/main/` one at a time — runs concurrently, dedupes, reports per-file counts |
| `pre-migration-checklist.sh` | The 13 manual grep steps in `pre-migration-checklist.md`, run in one call |
| `scala3-style-check.sh` | The S1-S9 ratchet greps in `scala3-style.md` |
| `logging-standards-check.sh` | The 10 grep-verifiable ratchet targets in `logging-standards.md` |
| `storage-rocksdb-check.sh` | The 5 grep patterns for storage code review in `storage-rocksdb.md` |
| `pekko-typed-check.sh` | ~20 grep/cross-reference checks across P1-P25 + TL1-TL2 + the CAPSTONE sweep in `pekko-typed-api.md` |

---

## Confirmed absent

- **`.claude/hooks/`** — confirmed absent (`ls` returns "No such file or directory"; `git
  ls-files '.claude/hooks*'` returns zero matches). No hook scripts exist for this repo as
  of this read.
- **Tracked `.claude/settings.json`** — confirmed absent. Only `.claude/settings.local.json`
  exists on disk (230 bytes), and it is confirmed **untracked/gitignored**:
  `git check-ignore -v .claude/settings.local.json` matches `.gitignore:82: .claude/*` →
  `.claude/settings.local.json`. `git ls-files | grep '^\.claude/settings'` returns zero
  rows — no settings file of any kind is committed to the repo.
- Both of these match the expected state going into this audit; nothing has changed here.

### Other `.claude/` top-level entries noted in passing (not otherwise covered above)

`ls -la .claude/` shows, beyond `agent-protocols/`, `agents/`, `looping/`, `repo-references/`,
`settings.local.json`, and `skills/` (all covered above): `progress-tracking/`, `sprints/`,
`workflows/` (containing one file, `sprint-executor.js`, a `Workflow`-tool script referenced
by `warden.md`'s domain description), and `worktrees/`. These exist and are consistent with
what CLAUDE.md's "Reference index" and `warden.md`'s domain description claim, but a
line-by-line audit of their contents was out of scope for this pass (they are operator-local
working-state directories, not tooling-definition surfaces comparable to the vendored
clients' `.agents/`/`.claude/`/`AGENTS.md` layouts).

Total tracked files: `git ls-files | grep -c '^\.agents/'` → 59; `git ls-files | grep -c
'^\.claude/'` → 171 (the larger `.claude/` count reflects that `.claude/repo-references/`,
`.claude/agents/`, `.claude/looping/bin+recipes+verify/`, and the symlink files themselves
are all tracked, in addition to the 59 canonical files under `.agents/`).
