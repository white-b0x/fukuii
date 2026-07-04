# Agentic Tooling Refresh — 2026-07

**Status:** Executed 2026-07-03 (uncommitted — see Execution log below for exact deviations
from plan). Consolidates and supersedes the "Next step" sections of
`agents-md-decision-2026.md` and `claude-md-refresh-2026.md` — those two docs' analysis stands,
but their staged/deferred framing is replaced by the single-pass framing below.

## Execution log (2026-07-03)

All three phases executed in one pass, per §0's framing. Deviations from the plan as
originally written, and why:

- **Phases 1 and 2 order**: a parallel thread had already drafted most of Phase 1's
  `AGENTS.md`/`CLAUDE.md` content split before this execution pass started (adopted as-is —
  quality was equal to or better than this doc's original spec in places, e.g. a more robust
  "check `.local/docs/archive/2026-06/` first" note instead of a hardcoded path). This
  execution pass finished the remaining Phase 1 items (constitution.md, contributing.md,
  AGENT_LABELS.md, labeler.yml, copilot pointer), then did Phase 2 restructuring, then
  updated CLAUDE.md/AGENTS.md's protocol-table and reference-index text to point at the new
  canonical `.agents/` locations.
- **`.specify/memory/constitution.md`**: fixed directly (all 6 stale paths), following the
  constitution's own PATCH-versioning convention (1.1.0 → 1.1.1, new SYNC IMPACT REPORT
  entry) rather than leaving a pointer comment — per the "no more deferrals" framing in §0.
- **Design docs referencing `.local/` from tracked files**: `AGENTS.md`/`CLAUDE.md` (tracked)
  originally pointed at `.local/docs/fukuii-design/{agents-md-decision-2026,
  claude-md-refresh-2026}.md` (gitignored — invisible to anyone but the current operator).
  Moved both, plus this doc, to `docs/agentic-tooling/` (tracked) and updated all
  cross-references, including the two docs' own references to each other.
- **`.github/CODEOWNERS`** (§3.1's action list, item 5): **not created.** `git log` shows a
  single active contributor as of 2026-07 — CODEOWNERS' entire value is multi-owner
  path-based review routing, which doesn't apply yet. `.agents/protocols/github-workflows.md`
  documents this reasoning explicitly rather than fabricating placeholder ownership; add
  CODEOWNERS when a second regular contributor/reviewer joins.
- **P26 addendum**: added to `.agents/protocols/pekko-typed-api.md` as planned (blocking
  `Await.result`/`Await.ready` inside actor/dispatcher code) — no deviation.
- **Everything else** (§1-§3's remaining action items) executed as specified: 19 protocols +
  35 skills moved into `.agents/`, 21 SKILL.md relative references fixed, 7 broken
  best-practices paths fixed, `concurrency.md` pre-migration banner added, 2 `currency:`
  headers applied, `dependency-currency.md` + `git-conventions.md` + `github-workflows.md`
  protocols authored, `pekko-resource-audit` skill authored, Pekko-changelog step added to
  `fukuii-dependency-audit`.
- **Post-execution follow-ups (same session, user-directed), not in the original plan:**
  - Moved `.local/docs/fukuii-design/{agents-md-decision-2026,claude-md-refresh-2026}.md`
    (gitignored, referenced from tracked `AGENTS.md`/`CLAUDE.md`) to `docs/agentic-tooling/`
    alongside this doc — a tracked file should never point at gitignored content.
  - Moved `.local/docs/best-practices/` (same problem — referenced from `CLAUDE.md` and
    several `.agents/`/`.claude/agents/` files) to `docs/research/best-practices/`.
    `jvm/` was empty and not moved (nothing to move; git doesn't track empty dirs).
  - Moved `docs/reviews/` (4 already self-flagged "historical" SNAP-sync docs, June 2026)
    into the existing `docs/historical/reviews/` — matches that directory's established
    purpose exactly; fixed its 2 internal cross-references and documented it in
    `docs/historical/README.md`.
  - Moved `.claude/scripts/` (11 files: `sbt-run.sh`, sprint tooling, `lib/*-check.sh`
    collectors — confirmed tracked/committed, not operator-local) to root
    `scripts/agent-tooling/`, matching Nethermind's own convention of keeping genuinely
    portable helper scripts at repo-root `scripts/` rather than under `.claude/` or
    `.agents/`. Updated ~23 cross-references repo-wide and removed the now-stale
    `!.claude/scripts/` `.gitignore` rule. `.claude/agents/` (the 12 subagent definitions)
    and `.claude/worktrees/` were confirmed to stay exactly where they are — both are
    genuinely Claude-Code-specific (no other tool has an equivalent subagent-invocation or
    worktree-lifecycle-orchestration mechanism), matching the original decision doc's own
    "would a non-Claude tool act on this?" exclusion test.
- **Not committed.** All changes are staged/on-disk per the global commit-workflow rule
  (commit only on explicit request) — verify per §5 below, then commit in logical phase
  groups if/when asked.

## 0. Framing

This is a one-time structural + content alignment of fukuii's AI-agent tooling, not a phased
rollout. Rationale (user's framing, 2026-07-03): fukuii is mid-migration off deprecated Scala 2
/ Akka Classic onto Scala 3 LTS / Pekko 1.6+ Typed — exactly the kind of transition where stale,
LLM-trained-on-old-patterns guidance actively hurts. Getting the structure and content right now,
in one pass, means every future addition (new protocol, new skill, a version bump) lands in the
correct place automatically instead of accumulating new drift on top of old.

Three phases, sequenced (each depends on the previous landing):

1. **Root `AGENTS.md` / `CLAUDE.md` split** — portable conventions vs. Claude-only orchestration.
2. **`.agents/` canonical-source + symlink restructuring** — Nethermind's pattern, so future
   protocol/skill additions have one correct place to live regardless of which tool reads them.
3. **Nethermind gap-audit adoption + dependency-currency governance** — port the genuine gaps,
   fix every stale reference found this session, and stand up a mechanism so this doesn't
   silently rot again.

No deferrals within this scope: every concretely-identified fix below (stale paths, stale
rosters, the Classic-actor-labeled-as-current-practice doc) gets fixed in this pass. Two items
remain explicitly out of scope because they are categorically different work (new CI
infrastructure, not docs/structure) — see §6.

---

## 1. Phase 1 — Root `AGENTS.md` / `CLAUDE.md` split

Full design doc: `agents-md-decision-2026.md` (§1-§2) and `claude-md-refresh-2026.md`. This
section is the execution-ready summary, verified directly against the repo on 2026-07-03.

### 1.1 New file: `AGENTS.md` (repo root)

Plain markdown, no Claude-specific syntax. Populate by moving these blocks out of current
`CLAUDE.md` (verbatim unless noted):

1. **Header** — `# AGENTS.md — Working in fukuii`, project identity (current CLAUDE.md lines 1–4).
2. **"ETC vs ETH — read this first"** — current lines 6–24, verbatim.
3. **"Build & test commands"** — current lines 25–55, with two fixes applied during the move:
   - `sbt pp` row: replace the vague "compile-all + formatAll + quick + integration tests" with
     the verified-accurate sequence from `build.sbt:426-438`: **`compile-all` → per-module
     (`bytes`,`crypto`,`rlp`) `scalafmtAll` → root `scalafmtAll` → `rlp/test` → `testQuick` →
     `IntegrationTest/test`**. State explicitly it does *not* run `scalafixAll`.
   - `testEssential` row: drop the hardcoded "24 min, 3,621 tests" claim (this drifts constantly).
     Replace with: "Pre-push only — do not run mid-sprint. Check `.claude/sprints/QUEUE.md`'s
     status header for current count/duration; it can be blocked repo-wide by pre-existing
     test-source compile errors independent of your change."
   - Add one line pointing to `build.sbt`'s `addCommandAlias` block as the authoritative list of
     tagged-test aliases, instead of hand-listing them (avoids recreating this same staleness).
4. **"Working discipline (applies to every task)"** — current lines 145–160, verbatim.
5. **"Conventions"** — current lines 212–217, verbatim.
6. **New "Reference index" section** (net new — makes currently-invisible subsystems
   discoverable from the portable file):
   - Best-practices library at `.local/docs/best-practices/` (corrected path — see §3.3 fix #1),
     listing all six children: `scala/type-safety.md`, `pekko/typed-patterns.md`,
     `pekko/concurrency.md` (flag: pre-migration reference, see §3.3 fix #2), `evm-clients/`,
     `typelevel/patterns.md`, `codebase-audit.md`, `jvm/`.
   - One line pointing to `docs/development/contributing.md` for full contributor workflow.
   - One line: "operational runbooks for node lifecycle/ops live under `.agents/skills/`
     (canonical) / `.claude/skills/` (symlinked)" — structure fact only; *how to invoke* a
     skill stays in CLAUDE.md since Skills are a Claude Code-specific mechanism.
   - One line pointing to `.github/copilot/README.md` for MCP/JSON-RPC tool wiring (any
     MCP-aware client, not just Copilot, can use this — belongs in the portable file, not
     CLAUDE.md; see §1.4).
   - One line pointing to `.agents/protocols/` (post-Phase-2) as the canonical location for
     editing shared agent protocols, cross-referencing CLAUDE.md's protocol table.

**Explicitly excluded from AGENTS.md** (fails the "would a non-Claude tool be able to act on
this?" test — stays CLAUDE.md-only): subagent routing table, Consensus-Critical Change Protocol,
Shared agent protocols table, continuation-file protocol, OODA loop section, Spec Kit
slash-command section, sprint tooling paths.

### 1.2 Rewritten `CLAUDE.md` (repo root)

```markdown
# CLAUDE.md — Working in fukuii

@AGENTS.md

## Skills vs. subagents
[new 2-3 sentence paragraph: operational skills under .agents/skills/ (symlinked at
.claude/skills/) for node lifecycle/ops (start/stop, mining, peers, TLS, disk, logs, security,
checkpoint sync, key management, custom networks) — invoke directly for operational tasks.
speckit-* skills back the Spec Kit workflow below. Use a specialist subagent instead when the
task is source-code analysis/modification, not node operation.]

## Shared agent protocols
[table, current lines 59-79, PLUS two missing rows: alert-wrapper-protocol.md, loop-handoff.md.
 Update the intro line to say protocols are edited at .agents/protocols/ (canonical),
 read at .claude/agent-protocols/ (symlinked) — post Phase 2.]
[unchanged: Sprint tracking bullet list, current lines 81-86]

## Specialist subagents
[unchanged table + intro para, current lines 97-116 — verified fully accurate incl. warden]

### Consensus-Critical Change Protocol (mandatory)
[unchanged, current lines 118-135, WITH the .github/agents/forge.md → .claude/agents/forge.md
 fix applied — see §3.3 fix #3]

## Continuation protocol (applies to every agent)
[unchanged, current lines 162-193, WITH the "Prior group summaries" path fixed — see §3.3 fix #4]

## OODA loop for large migrations / multi-file work
[unchanged, current lines 197-210]

## Spec-Driven Development (Spec Kit)
[current lines 219-235, with the .github/agents/forge.md fix propagated here too]

<!-- SPECKIT START -->
[REWRITTEN: drop the inlined multi-paragraph paraphrase of specs/007's plan.md. Replace with:
 "Current spec-kit plan (if any): see specs/<NNN>/plan.md — check its own progress state, don't
 trust a cached summary here. The actual live work-in-progress tracker is .claude/sprints/QUEUE.md;
 check that first for 'what's happening right now.' Prior plans: see specs/ for full history."]
<!-- SPECKIT END -->
```

Net effect: `AGENTS.md` ~110-120 lines; `CLAUDE.md` shrinks to genuinely Claude-Code-specific
content, roughly its current bottom half plus the one-line import.

### 1.3 `docs/development/contributing.md`

Replace lines **546–672** (the entire `## Guidelines for LLM Agents` section, up to but not
including `## Additional Resources` at 673) with:

```markdown
## Guidelines for LLM Agents

See `AGENTS.md` (portable, tool-agnostic conventions and build/test commands) and `CLAUDE.md`
(Claude Code-specific subagent routing, Spec Kit workflow, and orchestration) at the repo root
for AI agent guidance. Both are kept current; this section intentionally no longer duplicates
their content.
```

This removes the stale 7-agent roster (wraith, mithril, ICE, eye, forge, herald, Morgoth).

### 1.4 `.github/copilot/` pointer

Decision: pointer line lives in **`AGENTS.md`** (§1.1 item 6), not CLAUDE.md — MCP/JSON-RPC node
wiring is tool-agnostic (any MCP client, not just Copilot), so it belongs in the portable file.
`.github/copilot/{mcp.json,README.md}` content itself is unmodified.

### 1.5 `.github/AGENT_LABELS.md`

Add a scoping note near the top (do not attempt a full 12-agent roster regeneration — see
reasoning in §3.3, this pairs with the `labeler.yml` fix):

```markdown
> **Scope note:** This document describes GitHub issue/PR *labels* only — a separate, narrower
> taxonomy from the live Claude Code subagent roster in `.claude/agents/` (12 agents as of
> 2026-07; see `CLAUDE.md`'s Specialist subagents table). Only `agent: forge` is currently
> auto-applied (via `.github/labeler.yml`); the rest are manual, legacy labels reflecting an
> earlier 7-agent roster (wraith, mithril, ICE, eye, forge, herald, Morgoth). Do not treat this
> file as a mirror of `.claude/agents/`.
```

### 1.6 `.specify/memory/constitution.md`

Fix the 6 stale `.github/agents/forge.md` → `.claude/agents/forge.md` path references (lines 34,
100, 101, 110, 265, 271). This is a mechanical path correction, not a principle change — follow
the constitution's own amendment/versioning convention if it requires one for any edit (check its
own meta-instructions; use `/speckit-constitution` if that's the sanctioned edit path), but do not
let the process overhead block fixing a factual bug.

### 1.7 Verification

1. Extract every backtick-quoted path from `AGENTS.md` and the new `CLAUDE.md`, confirm each
   resolves (`test -f`/`test -d`).
2. `grep -rn "\.github/agents/forge\|\.local/best-practices/" --include=*.md .` → zero hits
   anywhere (this fix list eliminates the constitution.md exception that a deferred approach
   would have left).
3. `grep -n "wraith\|mithril\|ICE\|Morgoth" docs/development/contributing.md .github/AGENT_LABELS.md`
   → contributing.md: zero hits. AGENT_LABELS.md: legacy table entries are fine (that's what the
   scope note is for), but the scope note itself must be present.
4. Start a fresh Claude Code session at repo root; confirm it reports having loaded both
   `AGENTS.md` and `CLAUDE.md` (ask it a fact that only lives in AGENTS.md, e.g. the ETC/ETH
   chain-ID table) — confirms the bare `@AGENTS.md` import resolves (both files are at repo root,
   so no `../` needed, unlike Nethermind's nested `.claude/CLAUDE.md` → `@../AGENTS.md`).

---

## 2. Phase 2 — `.agents/` canonical source + symlink restructuring

Full design doc basis: Nethermind's `.agents/rules/agent-skills.md` convention (vendored at
`.claude/repo-references/clients/nethermind/`). Verified directly against fukuii's repo.

### 2.1 Canonical directory naming

**`.agents/protocols/`** (not `.agents/rules/`). Reasoning: fukuii's own vocabulary is already
deeply embedded and self-consistent — CLAUDE.md's "Shared agent protocols" header, every subagent
file's "Shared protocols" section, filenames like `testing-protocol.md`,
`consensus-change-protocol.md`, both `.claude/looping/README.md` and `DISCOVERY.md` saying
"Relevant Existing Protocols." Renaming to "rules" is pure terminology churn with zero functional
benefit, since only the directory *contents* move — every existing
`.claude/agent-protocols/...` / `~/.claude/agent-protocols/...` reference keeps working unmodified
regardless of the canonical directory's internal name (the rename is cheap to revisit later if
fukuii ever needs cross-tool "rules" terminology for a Cursor-style integration).

**`.agents/skills/`** — matches Nethermind's naming exactly, no ambiguity here.

### 2.2 Exclusions from the move

- **`.claude/agent-protocols/modernization-log/`** (19 top-level `.md` files' sibling, itself a
  35-file subtree) — **do not move.** It is already mid-flight in its own, separately-documented
  retirement (`sprint-lifecycle.md` §"Retiring the legacy tracker": Phase A copied it verbatim to
  `.claude/sprints/log/legacy-modernization-log/`, now canonical; Phase B, pending explicit
  sign-off, deletes the original via `git rm`). Migrating it into `.agents/protocols/` would
  resurrect content already scheduled for deletion and fork its history across two unrelated
  migrations. Leave it exactly where it is; its Phase B deletion proceeds independently.
- **4 loose non-per-skill files** in `.claude/skills/`: `README.md`, `CONVENTIONS.md`,
  `VALIDATION.md`, `REFERENCES.md` — leave untouched in `.claude/skills/`. They're shared
  meta-docs about the skills collection, not per-skill canonical definitions, so they don't
  participate in the symlink convention (Nethermind's own rule — "symlink individual skill
  subdirectories, not the entire folder" — implies ordinary siblings can coexist).

### 2.3 Git mechanics

**Protocols (19 files, flat, file-level symlinks):**
```bash
mkdir -p .agents/protocols
git mv .claude/agent-protocols/<file>.md .agents/protocols/<file>.md
ln -s ../../.agents/protocols/<file>.md .claude/agent-protocols/<file>.md
git add .claude/agent-protocols/<file>.md
```
Files: `alert-wrapper-protocol.md`, `background-script-execution.md`,
`consensus-change-protocol.md`, `dead-code-review.md`, `finding-resolution.md`,
`inline-cleanup.md`, `logging-standards.md`, `loop-handoff.md`, `migration-handoff.md`,
`pekko-typed-api.md`, `pre-migration-checklist.md`, `risk-stratified-commit.md`,
`scala3-given-migration.md`, `scala3-style.md`, `sprint-lifecycle.md`, `storage-rocksdb.md`,
`testing-protocol.md`, `warning-ratchet.md`, `worktree-protocol.md`. `modernization-log/` is left
completely alone.

**Skills (35 directories, directory-level symlinks):**
```bash
mkdir -p .agents/skills
git mv .claude/skills/<name> .agents/skills/<name>
ln -s ../../.agents/skills/<name> .claude/skills/<name>
git add .claude/skills/<name>
```
All 35 (`fukuii-*` × 22, `speckit-*` × 13). Bundle into the same commit: rewrite 21 SKILL.md
files' fragile relative references (`../CONVENTIONS.md` → `.claude/skills/CONVENTIONS.md`,
`../REFERENCES.md` → `.claude/skills/REFERENCES.md`) at their new canonical location — this
removes physical-vs-logical symlink-resolution ambiguity for near-zero cost since these files are
already being touched by the move.

**New file:** `.agents/protocols/agent-skills.md` — fukuii's version of Nethermind's
`agent-skills.md`, documenting this exact convention (canonical-source-plus-symlink, per-item not
per-folder, `cp -a` to preserve symlinks when copying). Create as part of this phase so the path
is real when Phase 1's AGENTS.md references it.

**Expected git status shape:** each item shows as a **rename** (full similarity) plus a **new
file add** at the old path (the symlink, ~30-50 byte blob). This is correct, not a mistake —
`git log --follow` continues to walk pre-move history at the new canonical path.

**Commit sequencing:** 2 commits — (1) protocols move+symlink, (2) skills move+symlink+21-file
text fix. Land this phase's commits before Phase 1's CLAUDE.md/AGENTS.md content edits, so Phase 1
can reference the final `.agents/` paths directly instead of guessing.

### 2.4 `.gitignore`

**No change required.** Verified via `git check-ignore -v .agents` (exit 1, no match) — `.agents/`
is a sibling top-level directory, never nested under `.claude/`, so the existing `.claude/*`
catch-all and its negations (`!.claude/agents/`, `!.claude/skills/`, `!.claude/agent-protocols/`)
are unaffected and keep working exactly as before on the now-symlink-containing directories.
Optional: add a one-line comment above the `.claude/*` block noting `.agents/` is the new,
always-tracked canonical source, purely for a future reader's orientation.

### 2.5 Cross-reference sweep

| Location | Needs edit? | Why |
|---|---|---|
| `CLAUDE.md` protocol table | No (paths stable through symlink) — but update intro line per §1.2 | — |
| `.claude/agents/*.md` (12 files, `~/.claude/agent-protocols/...` refs) | No | Home-dir symlink chain (`~/.claude/agent-protocols` → this repo's `.claude/agent-protocols/`) still resolves; only leaf files became symlinks, the directory itself didn't move |
| `.claude/scripts/*.sh`, `.claude/looping/README.md`/`DISCOVERY.md` | No | Path stable |
| `.claude/looping/registry.yaml` | No | Verified: zero references to `agent-protocols` or `skills/` paths |
| `speckit-agent-context-update` skill | No | Operates on CLAUDE.md/AGENTS.md content, not these paths |
| 21 SKILL.md files' relative refs | **Yes** | Bundle with the skills-move commit (§2.3) |

### 2.6 Verification checklist

1. `find .claude/agent-protocols -maxdepth 1 -type l | wc -l` → 19. Non-symlink entry → exactly
   `modernization-log/`.
2. `find .claude/skills -maxdepth 1 -type l | wc -l` → 35.
3. `find .claude/agent-protocols .claude/skills -type l ! -exec test -e {} \; -print` → nothing
   (no broken symlinks).
4. `readlink -f ~/.claude/agent-protocols/testing-protocol.md` resolves and `cat`s real content
   (spot-check 2-3 files — this is the one operator-only, untracked dependency in the chain).
5. `git log --follow --oneline .agents/protocols/testing-protocol.md` and
   `.agents/skills/fukuii-first-start/SKILL.md` both show pre-move commit history.
6. `git check-ignore -v .agents/protocols/testing-protocol.md` and `.claude/skills/fukuii-first-start`
   both exit 1 (not ignored).
7. Invoke a moved skill (e.g. `/fukuii-first-start`) through its `.claude/skills/` symlinked path;
   confirm the rewritten `.claude/skills/CONVENTIONS.md` reference resolves.
8. Trigger a `discover`-tier loop recipe once; confirm it still locates
   `fukuii-dependency-audit`/`fukuii-node-health-check`/`fukuii-sync-troubleshooting` (registry.yaml
   references by skill *name*, confirmed no path dependency).
9. `git diff` on both commits shows zero changes under `.claude/agent-protocols/modernization-log/`.

### 2.7 Naming-collision risk (surface to future readers, don't avoid)

`.claude/agents/` (12 subagent *definitions*) and `.agents/` (canonical protocol/skill *source*)
are similarly named but functionally unrelated — this is inherent to matching Nethermind's
convention (they have no competing `.claude/agents/`). Add a one-line disambiguation in the new
`.agents/protocols/agent-skills.md` stub: "`.agents/` is this repo's canonical protocol/skill
source (this convention doc). Not to be confused with `.claude/agents/`, the 12 Claude Code
subagent definitions (forge, beacon, eye, ...) — see CLAUDE.md's Specialist subagents table."

---

## 3. Phase 3 — Nethermind gap-audit adoption + dependency-currency governance

Verified pinned versions (`build.sbt:50`, `project/Dependencies.scala:6-7`): **Scala 3.3.8 LTS**,
**Pekko 1.6.0**, **Pekko HTTP 1.3.0** — these are correct and current as of this pass. The
problem this phase addresses is not the version pins themselves (already correct) but (a) genuine
capability gaps vs. Nethermind's agentic tooling, and (b) prescriptive *content* in fukuii's own
docs that silently teaches deprecated Akka-Classic-era patterns without flagging them as such.

### 3.1 Nethermind gap audit — verdicts

Cross-checked every `.agents/rules/*.md` and `.agents/skills/*/SKILL.md` in the vendored
Nethermind clone against fukuii's actual `.claude/agents/*.md` + `.claude/agent-protocols/*.md` +
`.claude/skills/*`.

| Nethermind file | Fukuii equivalent? | Verdict |
|---|---|---|
| `coding-style.md` | `scala3-style.md`, `logging-standards.md` | Covered — different language surface, no port |
| `di-patterns.md` (Autofac) | None — fukuii uses manual builder/cake pattern (`NodeBuilder.scala`), not a DI framework | **Not portable** — no analogous problem exists |
| `package-management.md` (NuGet CPM) | `Dependencies.scala` already centralizes versions | **Not portable** — sbt structurally solves this already |
| `performance.md` (Span/SIMD/ref structs) | `.local/docs/best-practices/pekko/concurrency.md`, `codebase-audit.md`, `type-safety.md` | Overlapping intent, no direct port (no JVM stackalloc analog); soft gap — hot-path work happens ad hoc per-feature (`specs/007-hotpath-alloc-reduction`) rather than as a standing doc. Not urgent. |
| `robustness.md` (async pitfalls, IDisposable) | `warning-ratchet.md`, `dead-code-review.md`, `storage-rocksdb.md`, CLAUDE.md's "Fail loudly" | Mostly covered under different names. **One real gap**: no enumeration of Pekko-Future-specific deadlock footguns (blocking `Await.result` inside an actor receive / dispatcher thread) analogous to C#'s `.Result`/`.Wait()` warning. |
| `test-infrastructure.md` (dedup parameterized tests) | `testing-protocol.md` (cadence/tiers only) | **Genuine gap** — no guidance on ScalaTest table-driven tests / avoiding copy-pasted near-identical specs. |
| `github-workflows.md` + CODEOWNERS | Nothing in `.claude/`; `.github/` has real CI content but no agent-facing rules doc; `ls .github/CODEOWNERS` → confirmed absent | **Genuine gap**, both the rules doc and CODEOWNERS itself. |
| `git.md` (force-push confirmation, branch-naming, merge-conflict escalation) | `worktree-protocol.md` covers lifecycle, not these specific rules | **Genuine, small gap** — cheap to port near-verbatim. |
| `fix-nethtest` skill | `eye`/`forge`/`beacon` triage flow + `test-greening`/`ref-parity-audit` loop recipes | Substantially covered, different organization (subagent+protocol vs. single skill) — not a gap. |
| `gas-benchmark` skill | No fukuii equivalent; no CI benchmark workflow exists at all | **Genuine gap, but blocked** — needs a CI benchmark mechanism before a skill can wrap it. Not actionable this pass. |
| `resource-leak-audit` skill | Content scattered (`pekko-typed-api.md` P1/P9 timer/watch rules, `concurrency.md` §5) but no standalone invokable audit skill | **Genuine gap, best single port candidate** — a `pekko-resource-audit` skill (timers, actor watch cycles, stream materialization leaks, dispatcher starvation). |
| `review` skill + `claude-review.yml` + CODEOWNERS | `prism`/`forge`/`beacon` cover review content; no CI integration | Confirmed genuine gap (CI-integration specifically) — **out of scope this pass**, see §6. |

**Net action list for this phase** (genuine, non-overlapping, actionable now or as a
near-immediate follow-on within this same effort):
1. `.agents/protocols/git-conventions.md` — force-push confirmation, branch-naming, merge-conflict
   escalation (ported from Nethermind's `git.md`).
2. Addendum to `testing-protocol.md` (or new `.agents/protocols/scala-test-patterns.md`) —
   ScalaTest table-driven/`forAll` guidance to prevent copy-pasted near-identical specs.
3. New skill `.agents/skills/pekko-resource-audit/SKILL.md` — timers/watch/materialization/
   dispatcher-starvation audit methodology, adapted from Nethermind's `resource-leak-audit`
   two-phase (exhaustive search → validation gate) structure.
4. Addendum to `pekko-typed-api.md` (or `concurrency.md`) — blocking-`Future`-inside-dispatcher
   deadlock footgun, analogous to the C# `.Result`/`.Wait()` warning.
5. `.agents/protocols/github-workflows.md` — CI naming/concurrency conventions — **and**
   `.github/CODEOWNERS` (per-module ownership, mirroring Nethermind's per-project granularity).

### 3.2 Dependency-currency governance mechanism

**New protocol: `.agents/protocols/dependency-currency.md`** (canonical; symlinked at
`.claude/agent-protocols/dependency-currency.md` per Phase 2's convention), combined with a
lightweight per-file header marker. Rejected alternatives and why:

- Headers with no protocol: inert without a stated procedure/cadence — becomes copy-pasted dates
  nobody re-derives.
- A `.claude/looping/` recipe: wrong tool — the looping subsystem is built for mechanically-gated,
  grep-verifiable, low-judgment loops (warning ratchets, test-greening). Judging whether a
  prescriptive paragraph still reads as idiomatic Pekko-1.6-Typed vs. Akka-Classic-era thinking is
  inherently prose-comprehension work, not grep-gateable.
- Folding into `fukuii-dependency-audit`: wrong target — that skill's whole design center is
  mechanical version-drift detection (pinned version → endoflife.date → `sbt dependencyUpdates`).
  Conflating it with judgment-based content review would overload one skill with two
  incompatible verification methods. Keep them siblings, cross-referenced: one verifies *what
  version we're on*, the other verifies *whether our docs about writing code for that version are
  still accurate*.

**Protocol contents:**
- Current pinned versions sourced by *command*, never hardcoded: `` grep -E 'scala-3|pekkoVersion' build.sbt project/Dependencies.scala ``.
- Definition of "current-year idiomatic": primary sources per dependency (Scala 3 official docs +
  vendored `scala3/changelogs/`; Pekko's own vendored `docs/src/main/paradox/release-notes/`,
  currently unused by any existing audit mechanism).
- **Akka-Classic smell-list** (lifted directly from `pekko-typed-api.md`'s own P1/P2/P3/P9 "avoid"
  columns, since that file already correctly encodes what's stale): `context.become`, untyped
  `Receive`, `sender()`, `SupervisorStrategy`/`OneForOneStrategy` outside `Behaviors.supervise`,
  `scheduler.scheduleOnce` instead of `Behaviors.withTimers`.
- Procedure: on trigger, read each governed file, check against the smell-list + vendored
  changelogs, disposition any finding via the existing `finding-resolution.md` discipline (fix
  now / schedule / defer with reason) rather than inventing a new one.
- Cadence: run immediately after `fukuii-dependency-audit` reports a version bump (the moment
  content is most likely to have drifted from new idioms), or every ~6 months of no version
  change — stated as guidance, not a hard mechanical gate.

**Header convention** — first line after the H1 title of every governed file:
```
<!-- currency: verified idiomatic for Scala 3.3.8 LTS / Pekko 1.6.0 — 2026-07-03 -->
```
Grep-discoverable (`grep -rn "currency:" .agents/protocols/`), diffable in review (a PR bumping
the pinned Pekko version with zero `currency:` lines touched is a visible signal).

**Governed-file scope**: everything under `.agents/protocols/`, `.local/docs/best-practices/`
(excluding `archive/`), and `AGENTS.md`/`CLAUDE.md` themselves. Explicitly exclude
`.local/docs/archive/**` — historical sprint records, not live prescriptive docs, correctly not
held to a currency bar. `.claude/progress-tracking/` future-gated items (e.g. "Scala 3.9 LTS —
GATED, not yet available") are already the correct pattern for forward-looking claims — no fix
needed, use as the template.

### 3.3 Concrete fixes (execute in this pass — verified exact locations)

1. **8 broken `.local/best-practices/` → `.local/docs/best-practices/` path references**:
   `CLAUDE.md` (line 89, moves into AGENTS.md per §1.1 — fix at the new location),
   `.claude/agent-protocols/scala3-style.md:194`, `.claude/agents/mithril.md:50,51,101`,
   `.claude/agents/prism.md:41,44`, `.claude/agents/loom.md:355,356`.
2. **`.local/docs/best-practices/pekko/concurrency.md`** — add a banner at the top of the file:
   > These patterns describe **pre-migration Pekko Classic actor code** (SNAPSyncController,
   > NetworkPeerManagerActor, etc.) as historical/handoff reference for code not yet migrated by
   > LOOM — they are NOT the current Typed-actor prescription for new code. See `pekko-typed-api.md`
   > P1 (timers), P2 (PostStop), P3 (replyTo), P6 (two-behavior state machines), P9 (watchWith),
   > P19/P20 (Behaviors.supervise) for the current equivalents.

   This is the highest-priority fix in this phase: an LLM skimming this file in isolation today
   would write new Classic-style code, presented with no caveat as "Fukuii — correct."
3. **`.github/agents/forge.md` → `.claude/agents/forge.md`** — fix in CLAUDE.md's Spec Kit
   section and propagate to `.specify/memory/constitution.md` (§1.6).
4. **`.local/docs/moderization-review-june/.../summaries/` → `.local/docs/archive/2026-06/moderization-review-june/implementation-sprint/summaries/`**
   — fix in CLAUDE.md's Continuation protocol section.
5. **`.github/labeler.yml`** lines 21, 24 — `agent: ICE 🧊` and `agent: Morgoth 🎯` reference
   subagents that no longer exist among the current 12. Update or remove these comment lines to
   reference real current agents / mark as retired roles (pairs with the `AGENT_LABELS.md` scope
   note in §1.5 — do not attempt a full 12-agent label taxonomy regeneration in this pass, since
   that requires creating new GitHub labels via `CREATE_LABELS.md`'s process, a materially
   different kind of change from a markdown/YAML comment fix).
6. **`.claude/skills/fukuii-dependency-audit/SKILL.md`** — add a Pekko-changelog cross-reference
   step to the "Reference repos" section, mirroring the existing Scala 3 changelog check:
   `git -C "$REFS/pekko" pull --ff-only; ls "$REFS/pekko/docs/src/main/paradox/release-notes/"`.
   Confirmed gap: this skill currently checks Scala currency but has zero mechanism touching the
   already-vendored Pekko changelog.

### 3.4 Files verified accurate — no fix needed, use as templates

`.claude/agent-protocols/pekko-typed-api.md` (explicitly names Pekko 1.6.0, correctly teaches
`Behaviors.withTimers`/`replyTo`/`Behaviors.supervise` as current, correctly lists `sender()`/
`context.become` as anti-patterns) and `.claude/agent-protocols/scala3-style.md` (S1-S11, correctly
frames Scala 2 patterns as migration-source, not current practice) — both are accurate and
current. Apply the `currency:` header to these two first, as the model for what "verified
current" looks like before working through the rest of the governed-file list.

---

## 4. Order of operations (all phases)

1. **Phase 2 first** (structural): protocols move+symlink commit, then skills move+symlink+text-fix
   commit, then create `.agents/protocols/agent-skills.md` stub.
2. **Phase 1** (content split): create `AGENTS.md` referencing final `.agents/` paths; rewrite
   `CLAUDE.md`; update `docs/development/contributing.md`; update `.github/AGENT_LABELS.md`; fix
   `.specify/memory/constitution.md`'s 6 path references (via its own amendment convention if one
   is required).
3. **Phase 3** (content-currency + gap-fill), in this order:
   a. Execute all 6 concrete fixes in §3.3 (mechanical, fully specified, no further judgment needed).
   b. Author `.agents/protocols/dependency-currency.md` per §3.2's design.
   c. Apply `currency:` headers, starting with `pekko-typed-api.md` and `scala3-style.md`.
   d. Port the 5 genuine Nethermind gaps in §3.1's action list (`git-conventions.md`, ScalaTest
      addendum, `pekko-resource-audit` skill, blocking-Future footgun addendum,
      `github-workflows.md` + `CODEOWNERS`).

## 5. Verification (all phases)

Combine the checklists in §1.7, §2.6, plus:
- `grep -rn "currency:" .agents/protocols/` shows entries for every file touched in §3.3/§3.4.
- `.github/CODEOWNERS` exists and has per-module entries mirroring the module list in AGENTS.md.
- A fresh Claude Code session at repo root correctly reports the full chain: `CLAUDE.md` →
  `AGENTS.md` (import resolves) → protocol table paths resolve through `.claude/agent-protocols/`
  symlinks → canonical edits at `.agents/protocols/` are what a session actually reads.

## 6. Explicitly deferred (different in kind, not part of this refresh)

These remain out of scope — not because of caution/hedging, but because they are categorically
different work (new automated infrastructure requiring its own security/design decisions, or
blocked on a prerequisite that doesn't exist yet), not a docs/structure alignment:

- **CI-integrated Claude/Copilot review workflow** (`.github/workflows/claude-review.yml`
  equivalent — auto-review on PR open, structured verdict, branch-protection gating status,
  author-association security gating against fork PRs). Real gap, confirmed via Nethermind's
  example, but needs its own follow-up design covering trigger scope, model choice, and fork-PR
  security gating.
- **`gas-benchmark`-equivalent skill** — blocked on fukuii not having a CI benchmark workflow to
  wrap in the first place; the skill is meaningless without that infrastructure existing first.
