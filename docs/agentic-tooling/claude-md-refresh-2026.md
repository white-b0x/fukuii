# CLAUDE.md refresh — research & audit

**Date:** 2026-07-03
**Status:** Living research doc, NOT a decision record. This feeds a follow-up rewrite of
`/media/dev/2tb/dev/fukuii/CLAUDE.md` — nothing in `CLAUDE.md` itself was changed as part of
this doc. Re-verify any tool-support / line-count claims below if this is read more than ~2
months after the date above; the AGENTS.md ecosystem is moving fast.

**Related doc:** `docs/agentic-tooling/agents-md-decision-2026.md` (same date) already
recommends restructuring `CLAUDE.md` around an `@AGENTS.md` import, modeled on the
`nethermind` reference client's `.claude/CLAUDE.md` → `@../AGENTS.md` pattern. That doc and
this one were produced independently but converge hard: both find the file has grown past
its useful always-loaded size, and both recommend splitting content out rather than editing
in place. Section 4 below treats the AGENTS.md split as one candidate structure, not the only
one — the two docs should be read together before executing a rewrite.

---

## 1. Executive summary

- **The file is not just long, it's actively wrong in places that would mislead a new
  session on day one.** Two claims are flat-out false (`sbt pp` does not run `formatAll`/
  scalafix at all — CLAUDE.md:35,215), one command is currently non-functional repo-wide with
  no warning (`testEssential`, CLAUDE.md:37 — blocked queue-wide by ~399-411 pre-existing
  test-source compile errors per `.claude/sprints/QUEUE.md`), and the "current plan" the file
  points to (`specs/007-hotpath-alloc-reduction/plan.md`, CLAUDE.md:239) has been stalled for
  3 days while the *actual* live work (the IP-CL-* opaque-type sweep) is tracked entirely in
  `QUEUE.md` and never mentioned in CLAUDE.md.
- **Six broken path references**, the worst being a whole block: `.local/best-practices/`
  (CLAUDE.md:89-95, six paths) should be `.local/docs/best-practices/` — every single path in
  that section is wrong. `.github/agents/forge.md` (CLAUDE.md:231) doesn't exist and the error
  is inherited from `.specify/memory/constitution.md`, which is itself wrong about agent-file
  locations in at least 6 places.
- **Two entire subsystems are invisible from CLAUDE.md**: `.claude/skills/` (35 skill
  directories — 23 operational `fukuii-*` skills plus 12 `speckit-*` skills) and
  `.claude/looping/` (a full DISCOVER→PLAN→EXECUTE→VERIFY automation harness with maker/
  checker gates, referenced only once, in passing, in the `warden` row). A new session has no
  way to discover either without stumbling into a raw `ls .claude/`.
- **External research converges on a structural verdict, not just a length verdict**: at
  ~252-253 lines the file sits past the ~60-150 line range most sources (Anthropic, HumanLayer,
  linting tools) treat as ideal, though still well under the one hard vendor-enforced ceiling
  found (claudelint's 40KB warning threshold) and comparable to at least one large real-world
  monorepo's shared file (Cloudflare workers-sdk's AGENTS.md, 247 lines). The actionable
  takeaway isn't "cut to N lines" but "keep only what Claude cannot infer (commands, non-default
  conventions, hard boundaries); push architecture narrative, protocol detail, and anything
  that changes weekly (current-plan status) into linked docs or Skills loaded on demand."
- **Recommended immediate fixes are cheap and high-value**: this is a rewrite, not a research
  gap — every finding below has an exact file:line and a known-correct replacement value. The
  rewrite should (a) fix the 2 false claims and 6 broken paths, (b) add one paragraph each on
  skills/ and looping/, (c) replace the SPECKIT block's implicit "this is the current plan"
  framing with an explicit pointer to `QUEUE.md` as the day-to-day source of truth, and (d)
  either split into `AGENTS.md` + thin `CLAUDE.md` overlay (see related doc) or at minimum move
  the protocol-description table and best-practices index into a linked reference file.

---

## 2. External best-practices findings

### 2.1 Anthropic's own guidance on CLAUDE.md

- **What belongs at the top level:** commands Claude can't guess, code style that diverges
  from language defaults, testing/verification instructions and preferred test runners, repo
  etiquette (branch naming, PR conventions), project-specific architectural decisions,
  environment quirks, and non-obvious gotchas.
- **What should NOT be there:** anything inferable from reading the code, standard language
  conventions Claude already knows, detailed API docs (link out instead), **information that
  changes frequently**, long tutorials, file-by-file descriptions, self-evident practices.
- **The core test Anthropic recommends:** for every line, ask "would removing this cause
  Claude to make mistakes? If not, cut it."
- **Named failure pattern, "the over-specified CLAUDE.md":** "If your CLAUDE.md is too long,
  Claude ignores half of it because important rules get lost in the noise. Fix: Ruthlessly
  prune. If Claude already does something correctly without the instruction, delete it or
  convert it to a hook."
- **Progressive disclosure is explicitly endorsed**: "For domain knowledge or workflows that
  are only relevant sometimes, use skills instead. Claude loads them on demand without
  bloating every conversation." `@path/to/file` imports exist precisely so detail can live in
  referenced docs instead of being inlined.
- **Maintenance model:** commit CLAUDE.md to git ("the file compounds in value over time"),
  treat it like code — review when things go wrong, prune regularly, verify changes by
  observing whether Claude's behavior actually shifts. `/init`-generated content is a starting
  point to be refined by real usage, not a finished artifact.
- **Never put secrets in it.**
- Sources: [Best practices for Claude Code](https://code.claude.com/docs/en/best-practices),
  [Using CLAUDE.md files](https://claude.com/blog/using-claude-md-files)

### 2.2 The AGENTS.md standard

- Formalized August 2025 (OpenAI, Google, Cursor, Factory), donated to the Linux Foundation's
  Agentic AI Foundation December 2025. 60,000+ projects, 20+ supporting tools as of Dec 2025.
- Positioned as "a README for agents" — build steps, tests, conventions that would clutter a
  human README, in a predictable location. No required format.
- Recommended sections: Project Overview, Development Environment, Build & Test Commands, Code
  Style, Testing Instructions, Contribution Guidelines, Security Considerations, PR/commit
  conventions, deployment steps.
- Monorepo pattern: nested `AGENTS.md` per subproject, closest file wins — same directory-scoping
  model Claude Code already uses for CLAUDE.md.
- **Real line counts fetched directly** (useful size benchmarks against fukuii's 253 lines):
  - `openai/codex` root `AGENTS.md`: 322 lines (but 88 nested subdirectory files split load;
    Rust-specific rules only load inside `codex-rs/`)
  - `cloudflare/workers-sdk` `AGENTS.md`: 247 lines; their `CLAUDE.md` is a **5-line stub**
    (`This file provides guidance... See @AGENTS.md`)
  - `carlrannaberg/claudekit` `AGENTS.md`: 759 lines — an outlier, itself a Claude-tooling
    meta-project, not representative of an application repo
  - The AGENTS.md spec's own reference file: 43 lines
- **GitHub's analysis of 2,500+ AGENTS.md files**: effective pattern = give the agent a
  specific persona/job, exact executable commands placed early with flags (not just tool
  names), explicit boundaries (never touch secrets/vendor dirs/prod config), specific
  tech-stack versions, real code examples over prose. Most failing files fail from being **too
  vague**, not too long — no length threshold was published.
- **Cross-tool sync pattern** relevant to fukuii: keep canonical instructions in `AGENTS.md`,
  make `CLAUDE.md` either a symlink or a one-line `@AGENTS.md` import with Claude-only rules
  appended below — rather than maintaining two diverging files. This is exactly what the sibling
  `agents-md-decision-2026.md` doc recommends, independently, citing the vendored `nethermind`
  reference client as a live example already inside this repo (`.claude/repo-references/
  clients/nethermind/.claude/CLAUDE.md` → `@../AGENTS.md`).
- Sources: [agents.md](https://agents.md/), [InfoQ](https://www.infoq.com/news/2025/08/agents-md/),
  [GitHub Blog: lessons from 2,500+ repos](https://github.blog/ai-and-ml/github-copilot/how-to-write-a-great-agents-md-lessons-from-over-2500-repositories/),
  [openai/codex AGENTS.md](https://github.com/openai/codex/blob/main/AGENTS.md),
  [cloudflare/workers-sdk CLAUDE.md](https://github.com/cloudflare/workers-sdk/blob/main/CLAUDE.md) /
  [AGENTS.md](https://github.com/cloudflare/workers-sdk/blob/main/AGENTS.md),
  [ClaudeLog: CLAUDE.md/AGENTS.md symlink](https://claudelog.com/faqs/claude-md-agents-md-symlink/)

### 2.3 Procedural vs. narrative, progressive disclosure

- Converged pattern: separate "what Claude can't infer" (procedural — commands, env quirks,
  non-default conventions) from architecture/history narrative; push the latter to linked docs.
  HumanLayer's framing: top-level file covers WHAT (stack, structure) / WHY (purpose) / HOW
  (workflow); everything else — including roadmap — goes in `agent_docs/`, invoked on demand.
- Progressive disclosure is now dominant, explicitly analogized to Claude's own Skills
  architecture (SKILL.md + `references/`): a thin always-loaded coordination layer plus deeper
  docs fetched only when relevant. An academic "Codified Context" study (108K-line C# codebase,
  19 subagents, 283 sessions) formalizes this as **hot-memory** (always-loaded constitution +
  retrieval hooks) vs. **cold-memory** (on-demand specification docs) — this maps directly onto
  fukuii's existing root CLAUDE.md + 19 `.claude/agent-protocols/` files + 12 subagents pattern,
  meaning fukuii already has the right *shape*, just with some content in the wrong tier.
- Roadmap/current-status content is explicitly called out by Anthropic as something to exclude
  from the always-loaded file ("information that changes frequently") — directly relevant to
  the SPECKIT block finding in §3.5 below.
- "Prefer pointers to copies" (HumanLayer): reference code via `file:line`, not pasted snippets,
  since snippets silently drift. The SPECKIT block's inlined plan summary (CLAUDE.md:239-249)
  is exactly this anti-pattern — a paragraph-length paraphrase of `plan.md` that can and did
  drift out of sync with the doc it summarizes.
- Empirical grounding: Chroma's "Context Rot" study (18 frontier models) found performance
  degrades well before the context-window limit — meaningful degradation by ~50K tokens on a
  200K model, 30%+ accuracy drops on tasks with mid-document answers — the mechanistic reason
  an always-loaded file has a continuous, not one-time, cost.
  ([trychroma.com/research/context-rot](https://www.trychroma.com/research/context-rot))
- A Feb 2026 study (Gloaguen et al.) found LLM-*generated* context files reduced task success
  ~3% and increased inference cost 20%+ vs. no context file; human-curated files gave only ~4%
  gain over none at the same overhead — the bar for "worth keeping content" is high.
  ([AllStacks](https://www.allstacks.com/blog/agents-md-files-the-research-says-youre-probably-doing-them-wrong))
- A landscape survey of 2,853 repos found context files dominate agentic configuration and are
  often the *only* mechanism used; Claude Code users employ the broadest range of mechanisms
  (skills, subagents) of any tool studied — reinforcing that fukuii's use of Skills + subagents
  is itself best-practice, but only if CLAUDE.md actually tells a session they exist.
  ([arXiv 2602.14690](https://arxiv.org/abs/2602.14690))

### 2.4 Staleness detection / maintenance cadence

- No universal cadence standard, but purpose-built drift-detection tooling exists:
  - **ctxlint** (YawLabs) — checks `staleness` ("Last updated N days ago, `src/x/` has M commits
    since"), `paths`, `commands`, per-section token budgets (default 1,000/section, 4,000
    combined), `redundancy`, `contradictions` across nested files, CI-coverage, CI-secrets.
    Ships a GitHub Action (`--strict`, SARIF output) and pre-commit hook.
    ([github.com/YawLabs/ctxlint](https://github.com/YawLabs/ctxlint))
  - **AgentLint** — 33 evidence-backed checks across CLAUDE.md, AGENTS.md, `.cursor/rules`,
    `.github/copilot-instructions.md`, CI workflows, pre-commit, `.gitignore`.
    ([agentlint.app](https://www.agentlint.app/))
  - **AgentLinter** — dedicated "Freshness Check" for stale file-path references and
    undocumented scripts. ([agentlinter.com](https://agentlinter.com/))
- **Treat-as-code review pattern:** Claude Code's own `/code-review` reads CLAUDE.md as PR
  context and flags newly-introduced violations of it — the closest thing to an enforced
  cadence, since drift becomes visible at every PR rather than requiring a separate audit.
  ([Claude Code Docs: Code Review](https://code.claude.com/docs/en/code-review))
- **Repeated general principle:** context files decay as the codebase evolves (dependency
  bumps, refactors, renamed commands) with no automatic recomputation; teams that don't lint or
  periodically review them accumulate silent staleness. Fukuii currently has none of this — no
  linter, no scheduled review — which is consistent with the volume of drift found in §3.
- **Applicability finding:** none of ctxlint/AgentLint/AgentLinter appear to be installed or
  referenced anywhere in fukuii's `.claude/` or CI config. Adding even a lightweight path-
  existence check (grep every backtick path in CLAUDE.md, `test -f` each) as a CI or pre-commit
  step would have caught every broken-path finding in §3.4 automatically.

### 2.5 Length guidance

No single canonical number; converging soft ceilings plus one hard vendor threshold:

| Source | Guidance |
|---|---|
| Anthropic (official) | No fixed number; qualitative — ruthlessly prune, cut anything Claude already does correctly |
| HumanLayer | Target under 300 lines, ideally ~60 lines for the core file, detail pushed to `agent_docs/*.md` |
| Buildcamp "Ultimate Guide to CLAUDE.md 2026" | Aim for under ~200 lines |
| Community heuristic (T. Saunders) | Under 150 lines |
| Practical/HN-cited range | 80-120 lines as the "high-signal" limit; "most CLAUDE.md files are 200 lines of noise" |
| **claudelint** (only hard, tool-enforced threshold found) | 40KB file-size warning (configurable `maxSize`) — mirrors an internal Claude Code performance-degradation warning past 40KB |

**Synthesis relevant to fukuii:** the current file is 253 lines / roughly 12-13KB — well under
the only hard limit (40KB) and comparable to Cloudflare's 247-line AGENTS.md, but past the
~60-150 line range multiple sources treat as ideal for a Claude-only file, *and* structured
differently from every comparison point found: Cloudflare and Nethermind both keep the
substantive content in the portable `AGENTS.md` and reduce `CLAUDE.md` itself to a thin pointer.
Fukuii is the only repo in this comparison set carrying all 253 lines directly in the
Claude-only file. The length finding and the structural finding point at the same fix.

---

## 3. Internal audit findings

Audit performed by direct read of `/media/dev/2tb/dev/fukuii/CLAUDE.md` (253 lines) plus
filesystem cross-checks — `git status`, `git diff`, `git log`, `ls`, targeted `grep` for every
backtick-quoted path. No files were edited during the audit.

### 3.1 Command table (CLAUDE.md:27-41)

| Command | CLAUDE.md claim | Verdict |
|---|---|---|
| `sbt compile-all` (29), `compile` (30), `scalafmtAll`/`scalafmt` (31-32), `formatCheck` (34), `testOnly` (36), `testVM`/`testCrypto` (40) | as described | **Accurate** — verified against `build.sbt:408-580` `addCommandAlias` bodies |
| `sbt formatAll` (33) | "scalafixAll + scalafmtAll across all modules" | **Accurate** — `build.sbt:440-453`: `compile-all` → per-module `scalafixAll` → per-module `scalafmtAll` → root `scalafixAll` → root `scalafmtAll` |
| `sbt pp` (35), repeated at **215** | "compile-all + formatAll + quick + integration tests," "same caveat as formatAll" (aborts on scalafix violations) | **FALSE.** `build.sbt:426-438` actual body: `compile-all ; {bytes,crypto,rlp}/scalafmtAll ; scalafmtAll ; rlp/test ; testQuick ; IntegrationTest/test`. **`pp` never calls `scalafixAll`.** It does not include `formatAll` and cannot inherit its scalafix-abort behavior. |
| `.claude/scripts/sbt-run.sh <name> testEssential` (37) | "24 min, 3,621 tests," "pre-push only" | **Stale number, and missing a live blocker.** `.claude/sprints/QUEUE.md:117` records the last baseline as **3,595 tests** (not 3,621); this session's own memory records an 11m20s runtime (not 24 min). More importantly, `QUEUE.md`'s status block (dated 2026-07-03, top of file) documents that `testOnly`/`testEssential` **currently cannot run at all queue-wide** — blocked by ~399-411 pre-existing test-source compile errors (GasAmount/BaseFeePerGas mismatches) not yet absorbed by in-flight work. CLAUDE.md gives zero indication the "pre-push gate" it recommends is presently broken. |

**Coverage gap:** `build.sbt` defines 10+ additional tagged-test aliases never mentioned in
CLAUDE.md: `testNetwork`, `testDatabase`, `testRLP`, `testMPT`, `testEthereum`, `testConsensus`
(284 tests), `testRPC` (219), `testState` (63), `testOlympia` (201), `testSync` (84), plus
`testEthSmoke`, `testAll`, `testCoverage`/`testCoverageOff`, `runScapegoat`. Only `testVM
testCrypto` is exemplified.

### 3.2 Shared agent-protocols table (CLAUDE.md:59-79)

All 17 listed protocol files exist under `.claude/agent-protocols/` and spot-checked
descriptions match content (`scala3-style.md` S1-S11 confirmed at lines 32-191 including S11
at :191; `pekko-typed-api.md` P1-P25 + TL1/TL2 confirmed at lines 32-677/692/705).

**Missing from the table, present on disk and in active use:**
- `.claude/agent-protocols/alert-wrapper-protocol.md` — the STOP-AND-ALERT supervision pattern
  (watchWith + structured alarm for actors where restart causes state corruption, e.g.
  `PeerEventBusActor`, `SyncController`). Not referenced anywhere in CLAUDE.md.
- `.claude/agent-protocols/loop-handoff.md` — the maker→checker handoff contract for the
  looping subsystem's EXECUTE→VERIFY transition. Consistent with the looping subsystem itself
  being essentially unmentioned (see §3.6).
- `.claude/agent-protocols/modernization-log/` (directory) — a legacy record explicitly being
  retired per `sprint-lifecycle.md:119-125`; correctly *not* mentioned, but a rewrite should
  avoid accidentally re-introducing a reference to it.

### 3.3 Specialist subagents table (CLAUDE.md:103-116)

**Accurate, including `warden`.** All 12 listed agents (`forge`, `beacon`, `eye`, `wraith`,
`herald`, `mithril`, `prism`, `loom`, `vault`, `conduit`, `flow`, `warden`) have a matching file
in `.claude/agents/`, which also contains a 13th file, `REFERENCES.md` (a shared doc, not an
agent — not mentioned anywhere in CLAUDE.md, see §3.6).

- `.claude/agents/warden.md` **exists on disk and is untracked** (`git status .claude/agents/`
  → `Untracked files: .claude/agents/warden.md`). Its frontmatter description matches CLAUDE.md's
  one-liner (row 116) reasonably well — `.claude/scripts/`, agent-protocols, looping subsystem,
  worktree lifecycle, Workflow-based sprint automation, permission/settings all present in
  both. `warden.md` additionally claims direct Claude API / Agent SDK / MCP-integration scope
  that CLAUDE.md's one-liner omits — a minor under-description, not a contradiction.
- **`CLAUDE.md` itself has an uncommitted local change**: `git diff HEAD -- CLAUDE.md` shows
  exactly one unstaged insertion — the `warden` table row (line 116). The agent file and its
  CLAUDE.md row were added together, in the working tree, not yet committed together. Not a
  bug, but a fragility worth noting: if this diff were lost, `warden` and its CLAUDE.md
  description would silently fall out of sync.

### 3.4 Broken path references

**Confirmed as suspected, plus additional broken paths found by a full backtick-path sweep.**

- **CLAUDE.md:231** — `` follow the `forge` protocol in `.github/agents/forge.md`. `` **Broken.**
  `.github/agents/forge.md` does not exist; `.github/agents/` contains only
  `speckit.agent-context.update.agent.md` (a Spec-Kit stub). The real file is
  `.claude/agents/forge.md`.
  - **This bug is not local to CLAUDE.md** — `.specify/memory/constitution.md`, which
    CLAUDE.md:226 calls "binding" and instructs the reader to read, itself repeatedly cites
    `.github/agents/{forge,beacon,herald}.md` at **constitution.md:34, 100, 101, 110, 263, 265,
    271** ("Agent files are in `.github/agents/`"). CLAUDE.md:231 is echoing an error that
    originates upstream in the constitution. A rewrite should flag this for a separate fix,
    since "read the constitution" (CLAUDE.md:226) sends the reader straight into more wrong
    paths.
- **CLAUDE.md:89-95** — the entire `.local/best-practices/` block is wrong: `` `.local/best-practices/` `` (89) and its five children `` `scala/type-safety.md` ``, `` `pekko/typed-patterns.md` ``,
  `` `pekko/concurrency.md` ``, `` `evm-clients/` ``, `` `typelevel/patterns.md` ``,
  `` `codebase-audit.md` `` (90-95) are **all missing**. The real location is
  **`.local/docs/best-practices/`** (confirmed to exist with exactly these six items), which
  also has a `jvm/` subdirectory CLAUDE.md never mentions at all. CLAUDE.md is missing the
  `docs/` path segment throughout this entire section — every one of the 6 paths is broken.
- **CLAUDE.md:195** — `` `.local/docs/moderization-review-june/implementation-sprint/summaries/` ``
  **missing.** Real path is **`.local/docs/archive/2026-06/moderization-review-june/
  implementation-sprint/summaries/`** — archived under `archive/2026-06/` since this line was
  written. (Note: "moderization" — missing the "n" — is a pre-existing typo baked into the real
  directory name too, not introduced by CLAUDE.md; leave the typo as-is if copying the path
  verbatim, or fix both together deliberately.)
- **All other paths check out**: all 17 `.claude/agent-protocols/*.md` paths (63-79), `.claude/
  sprints/QUEUE.md` + `completed/`/`log/`/`patterns/` (82-86), `.specify/memory/
  constitution.md` (226), `specs/007-hotpath-alloc-reduction/plan.md` (239), `specs/004-
  decoupled-heal-serve-root/plan.md` and `specs/003-scoped-heal-verification/plan.md` (250-251)
  all exist. Bare package-name references without a leading path (`jsonrpc/`, `consensus/`,
  `vm/`, `domain/`, `db/`, `crypto/`, lines 113-134) resolve fine as shorthand for
  `src/{main,test}/scala/com/chipprbots/ethereum/<name>/` — not flagged as defects.

### 3.5 SPECKIT block (CLAUDE.md:236-252, between `<!-- SPECKIT START -->` / `<!-- SPECKIT END -->`)

**Stale/drifted — actively misleading about "the current plan."**

- `specs/007-hotpath-alloc-reduction/plan.md` and its siblings (`spec.md`, `tasks.md`,
  `research.md`, `data-model.md`, `quickstart.md`, `checklists/`, `contracts/`) all exist.
- `tasks.md`: **21 of 28 tasks checked done**; 7 open — T002 (baseline benchmark capture),
  T003/T004 (benchmark scaffolding + A/B replay harness), and the entire Phase 6 close-out
  (T023 record deltas, T024 `sbt pp`+`formatCheck` clean, T025 forge+beacon sign-off, T026
  version bump + PR). Genuinely incomplete, and also **stalled**: every file under
  `specs/007-hotpath-alloc-reduction/` has an mtime of Jun 30 19:37 (3 days stale as of
  2026-07-03), and `git log` shows no commits touching that directory since the v0.8.0 release
  commit (`e16855453`).
- **`.claude/sprints/QUEUE.md` is the actual live work-tracking doc**, and its "Status
  (2026-07-03): OPEN" header describes an entirely different, much larger, actively-committed
  effort — the "IP-CL-*" batch sweep doing Scala 3 opaque-type propagation (`BlockNumber`/
  `BlockHash`/`Wei`/`GasAmount`/`StorageKey`) across sync/network/consensus/engine-API code,
  with commits landing hours apart (`4f42f4e31`, `5930054cf`, `0b14b46c7`, `a9ace9f42`,
  `c9b34213a`, `477cb7f9b`, `3bab1108f`, all dated 2026-07-02/03). **This has nothing to do with
  spec 007's hot-path allocation reduction**, and `QUEUE.md` never mentions spec 007.
- The two tracks are formally distinct by design (`sprint-lifecycle.md:112-115`, "Rule 7:
  Relationship to Spec-Kit" — net-new features go through Spec Kit under `specs/<NNN>/`;
  `sprints/QUEUE.md` is for modernization/cleanup/audit work on existing code). So the SPECKIT
  block isn't *technically* wrong about what spec 007 is — but framing it via `<!-- SPECKIT
  START -->...END -->` as "the current plan," with no pointer to `QUEUE.md`, strongly implies
  it's *the* active work when the visible day-to-day activity (every recent commit, the whole
  of `QUEUE.md`) is the unrelated IP-CL sweep. A session reading only CLAUDE.md has no way to
  know the real current work lives elsewhere.
- **"Prior plans" (250-251)** lists only `specs/004-decoupled-heal-serve-root/plan.md` and
  `specs/003-scoped-heal-verification/plan.md`. Four more spec directories exist and are
  omitted: `001-healing-frontier-scale` (22/30 tasks done), `002-bfs-heal-performance` (0/59
  done), `005-subtree-complete-verification` (0/32 done), `006-skip-redundant-verify-walk`
  (0/18 done). The 0-completed specs (002, 005, 006) are particularly worth flagging in a
  rewrite — either genuinely never started, abandoned, or not using the tasks.md checkbox
  convention; either way "prior plans" is incomplete and silent about the zero-progress ones.

### 3.6 Coverage gap — `.claude/` subdirectories vs. what CLAUDE.md mentions

| Dir | Contents (1 level) | Mentioned in CLAUDE.md? |
|---|---|---|
| `agent-protocols/` | 19 `.md` + `modernization-log/` | Yes — 2 files missing from the table (§3.2) |
| `agents/` | 12 agent `.md` + `REFERENCES.md` | Table: yes. `REFERENCES.md`: **no** |
| `looping/` | `README.md`, `DISCOVERY.md`, `ELIGIBILITY.md`, `LOOP_SPEC.md`, `registry.yaml`, `bin/`, `recipes/` (5: actor-migration, ref-parity-audit, spec-conformance, test-greening, warning-ratchet), `state/`, `verify/` | **One passing mention**, inline in the `warden` row only |
| `progress-tracking/` | `archived-logged/`, `completed/`, `working-docs/` | **No** — this is the "legacy tracker" explicitly being retired per `QUEUE.md`, but CLAUDE.md never says so, so a new session could mistake it for still-active |
| `repo-references/` | 20 vendored reference repos (pekko + pekko-http/connectors/management, scala3, scala2, docs.scala-lang, scalafix, scapegoat, scalamock, rocksdb, json4s, circe, sangria, hive, ECIPs, EIPs, ethereum, spec-kit, virtuslab, `clients/{nethermind,erigon,...}`) | **No** — despite `.claude/agents/REFERENCES.md` explicitly documenting per-agent usage of these repos |
| `scripts/` | `sbt-run.sh` (mentioned); `sprint-status.sh`/`sprint-clear.sh`/`sprint-archive.sh` (not mentioned in CLAUDE.md, only via the `fukuii-sprint-queue` skill); `lib/` (5 mechanical-check scripts) | Partial |
| `skills/` | **35 skill directories** — 23 `fukuii-*` operational skills (node lifecycle, mining, TLS, peers, disk, logs, security, checkpoint sync, custom networks, key management, etc.) + 12 `speckit-*` skills | **Not mentioned at all** |
| `sprints/` | `QUEUE.md`, `completed/`, `archive/`, `log/`, `patterns/` | Yes, reasonably well covered (81-88) |
| `workflows/` | `sprint-executor.js` (a `Workflow`-tool script orchestrating `agent()` calls) | Only implied via warden's row wording ("Workflow-based sprint automation"); never named or explained |
| `worktrees/` | `bin/`, `local-trees/` | Only via the `worktree-protocol.md` table pointer; no inline guidance in the CLAUDE.md body |

**Biggest gap:** `.claude/skills/` (35 skills, including 23 operational runbooks a new session
would otherwise reinvent from scratch) and `.claude/looping/` (a maker/checker automation
harness with its own protocol, `loop-handoff.md`, not in the protocols table either) are
completely undiscoverable from CLAUDE.md alone. `.claude/repo-references/` (20 cloned reference
repos actively wired into several specialist agents per `agents/REFERENCES.md`) is likewise
invisible.

### 3.7 Ranked — most likely to actively mislead a new session

1. SPECKIT block presents spec 007 as "the current plan" (236-252) when the real, fast-moving
   work is entirely in `.claude/sprints/QUEUE.md` (§3.5).
2. `testEssential`/`testOnly` guidance (37) doesn't warn the command is currently non-functional
   repo-wide (§3.1).
3. `sbt pp` description is factually wrong (35, 215) — doesn't run scalafix, doesn't inherit
   `formatAll`'s abort caveat (§3.1).
4. `.github/agents/forge.md` (231) is broken, inherited from a "binding" constitution that's
   wrong about agent-file locations in 6+ places (§3.4).
5. `.local/best-practices/` (89-95) — entire 6-path block wrong, missing `docs/` segment (§3.4).
6. Two entire subsystems (`skills/` — 35 skills, `looping/` — the automation harness) are
   essentially undocumented (§3.6).
7. Protocols table missing `alert-wrapper-protocol.md` and `loop-handoff.md` (§3.2) — minor,
   both real and in use.
8. `.local/docs/moderization-review-june/.../summaries/` (195) — one archive-move stale;
   low current relevance (historical).
9. "Prior plans" (250-251) omits 4 of 6 actual prior specs, 3 of which show 0 completed tasks.

---

## 4. Recommended outline for a rewritten CLAUDE.md

This is deliberately concrete — section-by-section content, not just headers — since it's prep
for an actual rewrite. Two structural options are laid out; pick one before drafting (see the
related `agents-md-decision-2026.md` for the full case for Option A).

**Option A (recommended, matches external best practice + sibling `nethermind` reference
client already vendored in this repo):** split into a portable `AGENTS.md` (domain knowledge,
build/test commands, working discipline — useful to *any* agent, not just Claude Code) plus a
thin `CLAUDE.md` that opens with `@AGENTS.md` and then carries only genuinely Claude-Code-
specific orchestration (subagent routing table, Spec Kit slash commands, continuation-file
protocol, sprint tooling paths). This directly fixes the length concern (§2.5) by construction
— the always-loaded Claude-only tail becomes ~100 lines, and the domain content becomes
reusable by Codex/Cursor/Copilot without duplication risk.

**Option B (smaller lift, if AGENTS.md split is deferred):** keep one file, but move the
protocol-description table and the best-practices index out to a single linked reference doc
(e.g. `.claude/repo-references/AGENT_REFERENCE.md`), and trim per §4 below. Still fixes the
concrete errors, doesn't fix the cross-tool duplication risk.

The section list below applies to either option — under Option A, sections 1-4 go in
`AGENTS.md` and 5-9 stay in `CLAUDE.md`; under Option B everything stays in one file with 6-7
moved to a linked doc.

1. **Project identity** (current lines 1-4, keep as-is) — multi-network EVM client, Mantis
   fork lineage, `com.chipprbots` package, Scala 3 + Pekko.

2. **ETC vs ETH — read this first** (current lines 6-24, keep as-is) — this is the single
   highest-value, purely-domain section; no changes needed, verified accurate.

3. **Build & test commands** — keep the table structure, but:
   - Fix `pp`'s description (§3.1): does NOT run scalafix/formatAll; it's `compile-all` +
     per-module `scalafmtAll` + root `scalafmtAll` + `rlp/test` + `testQuick` +
     `IntegrationTest/test`. Rewrite line 215's parenthetical accordingly.
   - Add a one-line caveat on `testEssential`: check `.claude/sprints/QUEUE.md`'s status block
     before relying on it as a pre-push gate — note it can be blocked repo-wide by pre-existing
     compile errors, and that the test-count/duration figures drift (point to `QUEUE.md` as the
     live source of truth rather than hardcoding a number that will go stale again).
   - Add the missing tagged-test aliases (`testConsensus`, `testRPC`, `testState`, `testOlympia`,
     `testSync`, etc. — §3.1) or, better, a one-line pointer to `build.sbt`'s `addCommandAlias`
     block as the authoritative list instead of hand-maintaining a partial table that will drift
     again.

4. **Working discipline** (current lines 145-160, keep near-verbatim) — sequential thinking,
   failure-as-information, small-batch checkpointing, evidence standards, Chesterton's Fence,
   root-cause, fail-loudly, irreversible=10x-thought. This is high-value, tool-agnostic, and
   accurate as written.

5. **Shared agent protocols** — keep the table but add the 2 missing rows
   (`alert-wrapper-protocol.md`, `loop-handoff.md`, §3.2), and consider shrinking the table to
   name + one-line purpose only (already the case) while moving anything longer to the protocol
   files themselves (already true) — no further action needed here besides the 2 additions.

6. **Reference index** (new section) — one compact paragraph replacing the current scattered
   mentions, pointing to:
   - `.local/docs/best-practices/` (fix the path — §3.4 — and add `jvm/`)
   - `.claude/repo-references/` (20 vendored reference clients/specs — currently absent
     entirely, §3.6) with one line on how specialist agents use them (point to
     `.claude/agents/REFERENCES.md` rather than re-describing it)
   - `.claude/skills/` — new paragraph: "23 `fukuii-*` operational skills exist for node
     lifecycle/ops tasks (start/stop, mining, peers, TLS, disk, logs, security, checkpoint
     sync, key management, custom networks) — invoke these directly for operational tasks
     rather than writing ad hoc bash. 12 `speckit-*` skills back the Spec-Kit workflow in
     section 8. Use a specialist subagent instead of a skill when the task is source-code
     analysis/modification rather than node operation." This is the single highest-value
     addition per §3.6/§3.7.
   - `.claude/looping/` — new paragraph: one or two sentences naming the DISCOVER→PLAN→
     EXECUTE→VERIFY automation harness, its maker/checker gate (`loop-handoff.md`), and that
     `registry.yaml` — not file headers — is the source of truth for which agents are loop-
     eligible (per this session's own memory note); point to `.claude/looping/README.md` for
     detail rather than inlining it.

7. **Specialist subagents table** (current lines 103-116) — keep as-is; verified fully
   accurate including `warden`. Consider adding one sentence clarifying skills vs. subagents
   (see item 6) right above this table so the two delegation mechanisms are taught together.

8. **Spec-Driven Development / Spec Kit** — keep the `/speckit-*` workflow description
   (232-235), but:
   - Fix the constitution reference: either fix `.specify/memory/constitution.md`'s own
     `.github/agents/*.md` paths in a separate pass, or — cheaper — add a parenthetical in
     CLAUDE.md itself: "(note: the constitution's own `.github/agents/*.md` paths are stale;
     the real files are at `.claude/agents/*.md`)" until the constitution is fixed.
   - **Replace the SPECKIT block's implicit framing** (§3.5). Don't present
     `specs/007-hotpath-alloc-reduction/plan.md` as unconditionally "the current plan." Instead:
     a) state plainly that `specs/<NNN>/` is for net-new feature work via Spec Kit, while
     `.claude/sprints/QUEUE.md` is the actual day-to-day active-work tracker for modernization/
     cleanup/audit sprints (per `sprint-lifecycle.md` Rule 7) — tell the reader to check
     `QUEUE.md` first for "what's happening right now"; b) either drop the inlined plan
     paraphrase entirely (point to `plan.md` instead — "prefer pointers to copies," §2.3) or
     keep a one-line pointer only, since the multi-paragraph summary is exactly the content
     that goes stale fastest and already has (3 days, per §3.5); c) fix "prior plans" to either
     list all 6 actual prior specs (flagging the 3 with zero completed tasks) or drop the list
     entirely in favor of "see `specs/` for the full history."
   - Consider whether the SPECKIT-managed block (`<!-- SPECKIT START/END -->`) should be
     auto-regenerated by the `speckit-agent-context-update` skill rather than hand-edited — if
     that skill already exists to keep this block in sync (it appears to, per the skills list),
     the drift found in §3.5 suggests it either isn't being run on a cadence or doesn't pull
     from `QUEUE.md`. Worth a quick check of that skill's actual behavior before the rewrite.

9. **Continuation protocol** (current lines 161-193) — keep as-is, verified accurate; just fix
   the one broken path at line 195 (§3.4) to
   `.local/docs/archive/2026-06/moderization-review-june/implementation-sprint/summaries/`.

**Not recommended for inclusion:** hardcoded test counts/durations, a hand-maintained full list
of `build.sbt` test aliases, or a paraphrase of any `specs/<NNN>/plan.md` — all three are
exactly the "information that changes frequently" Anthropic's own guidance says to exclude, and
all three were found stale in this audit.

---

## 5. Findings general enough for the cross-repo best-practices library

Flagging these for the separate best-practices library mirror, since they're not fukuii-specific:

- **"Information that changes frequently" is the single highest-value exclusion rule, and the
  audit gives a concrete before/after**: every stale/broken finding in §3 that actually
  misleads (SPECKIT block, test-count/duration, "prior plans" list) is content that changes on
  a weekly-or-faster cadence, hardcoded into an always-loaded file that's reviewed rarely. The
  generalizable rule: **anything that changes faster than the review cadence of the context
  file itself must be a pointer, never inlined content.** If a context file is reviewed monthly
  but a fact changes weekly, that fact will be wrong most of the time it's read.
- **"Prefer pointers to copies" needs teeth, not just guidance** — the SPECKIT block shows that
  even a well-intentioned paraphrase-of-a-doc, written accurately at the time, drifts within
  days once the underlying doc's context (an unrelated but higher-priority sprint) moves faster
  than the summary. A generalizable process fix: any block in a context file that summarizes
  another doc's content (rather than just linking to it) should be treated as a code-generated
  artifact with an owner script (regenerate-on-demand), not hand-maintained prose — exactly the
  pattern Spec Kit's own `speckit-agent-context-update` skill seems designed for, worth
  verifying it's actually wired into a cadence.
- **Two-way error inheritance is a real failure mode**: CLAUDE.md:231's broken path isn't a
  local typo — it's inherited from a "binding" governance doc (`constitution.md`) that's wrong
  in 6 places. When multiple agent-context files reference each other's claims (constitution →
  CLAUDE.md → agent files), an audit of the "child" file alone will miss that the error
  actually needs fixing upstream. **Any CLAUDE.md audit should trace referenced governance
  docs, not just the file itself**, or errors get fixed in the wrong place repeatedly.
  Generalizes directly to any repo with a constitution/AGENTS.md/CLAUDE.md chain.
  ([applies to the AGENTS.md decision doc too] `docs/agentic-tooling/agents-md-decision-2026.md` independently found the same cross-file-drift pattern in `docs/development/contributing.md` and `.github/AGENT_LABELS.md`.)
- **Undiscoverable subsystems are a bigger real-world risk than a slightly-too-long file**: the
  35-skill and looping-harness gaps (§3.6) will cost a new session far more (reinventing
  existing tooling, or never finding it) than the file's raw line count. When auditing an
  agent-context file, an explicit `.claude/` (or equivalent) directory diff against what the
  file actually mentions is worth doing every time — length audits alone won't surface this;
  you have to enumerate the directory tree and check for zero-mention subsystems specifically.
- **A single grep-based CI check pays for itself immediately**: "extract every backtick-quoted
  path in CLAUDE.md/AGENTS.md, `test -f`/`test -d` each" is a five-line script that would have
  caught 3 of the 9 ranked findings in §3.7 (the `.github/agents/forge.md`, `.local/best-
  practices/` block, and the archived-summaries path) with zero LLM cost, matching what tools
  like ctxlint already automate. Worth adding as a pre-commit or CI step to any repo with a
  context file over ~100 lines, independent of whether the file is otherwise well-maintained.
- **Untracked-but-referenced content is a quiet integrity risk**: `warden.md` + its CLAUDE.md
  table row exist together only in the working tree (§3.3) — accurate today, but if lost before
  commit, the agent and its doc-reference silently desync with no error surfaced anywhere.
  Generalizable check: when auditing a context file, run `git status` on any directory it
  references and flag untracked files whose only description lives in the (committed) context
  file.
- **Length thresholds are contested and don't generalize well as a standalone metric** — the
  external research found real, well-regarded repos ranging from 5-line stub to 759-line
  meta-project files, with the more useful, broadly-applicable heuristic being structural
  ("does this file carry content that's inferable, that changes weekly, or that duplicates a
  linked doc — not "is it under N lines"). Worth carrying into the best-practices library as
  the primary framing over a hard line-count rule.

---

## Appendix: where the raw agent findings came from

This report synthesizes two independent Sonnet-agent passes run in parallel: one external
web-research pass (WebSearch/WebFetch against Anthropic docs, agents.md, GitHub Blog, arXiv,
Chroma research, and 4 real-repo AGENTS.md/CLAUDE.md files fetched directly for line counts),
and one internal filesystem audit pass (full read of CLAUDE.md, `build.sbt` alias verification,
`.claude/agent-protocols/` and `.claude/agents/` directory diffs, `git status`/`git diff`/`git
log` on the relevant paths, and a full backtick-path sweep of CLAUDE.md against the filesystem).
No repo files were modified by either pass or by this synthesis.
