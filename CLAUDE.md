# CLAUDE.md — Working in fukuii

_Last reviewed: 2026-07-03. This file carries only Claude Code-specific orchestration
(named subagents, Spec Kit, sprint tooling). Portable project context — ETC/ETH semantics,
build & test commands, working discipline, conventions — lives in `AGENTS.md`, imported
below. Editing domain facts or commands? Edit `AGENTS.md`. Editing subagent routing, Spec
Kit, or sprint tooling? Edit here. See `docs/agentic-tooling/claude-md-refresh-2026.md`
for the audit that produced this structure and `agents-md-decision-2026.md` for why the
import points this direction (only Claude Code resolves `@path`; the dependency can't run
the other way)._

@AGENTS.md

## Shared agent protocols

Tracked protocols that all agents reference are read at `.claude/agent-protocols/`
(symlinks) — **edit them at their canonical location, `.agents/protocols/`**, per
`.agents/protocols/tooling/agent-skills.md`:

| Protocol | Purpose |
|----------|---------|
| `warning-ratchet.md` | 4-step pattern: inventory → risk-stratified commit → defer with @nowarn → promote to build error |
| `testing-protocol.md` | Per-phase test cadence (compile-all per file, testOnly after logic, testEssential once at end) |
| `risk-stratified-commit.md` | Bucket A/B/C commit discipline for sweep changes |
| `consensus-change-protocol.md` | Hard stop + routing table before touching consensus paths |
| `inline-cleanup.md` | "Hunt and seek" — what to fix opportunistically, what to log to the queue |
| `finding-resolution.md` | Every audit/review finding gets scheduled (existing IP, new IP, or a real future-batch entry) — never left as a bare flagged-but-unscheduled note |
| `logging-standards.md` | Preferred logging API, levels, message format, SLF4J patterns |
| `comments.md` | Default-to-no-comment policy; when a comment is warranted; sanctioned exceptions (`// MIGRATION:`, `@nowarn` reason lines, `not given` annotations); scaladoc vs. inline-comment distinction |
| `doc-standards.md` | Durable files (agent charters, protocol docs, subsystem `AGENTS.md` breadcrumbs) carry invariants + single-source pointers, never status snapshots/live counts/dated grep results — where live state actually belongs (QUEUE.md, the one authoritative subsystem doc, `.local/docs/test-quality-log.md`) and what does NOT count as the anti-pattern (historical incidents, `currency:` headers, templates, provenance dates) |
| `nomenclature.md` | Two-tier vocabulary: neutral ecosystem terms (`PoW`/`PoS`, chain ID, EIP/ECIP-NNNN) at the shared/framework level, network-specific fork/event names (`London`, `the Merge`, `Osaka`, `Olympia`) as family-local instance labels only — the anti-conflation ratchet one layer above `scala3-style.md`'s `Eth*`/`Etc*` symbol rule |
| `scala3-style.md` | S1–S11 Scala 3 standards with grep-verifiable ratchets (S11: opaque type full-layer propagation) |
| `scala3-given-migration.md` | G1–G3 operational pitfalls for `given/using` migration (P3a findings, applies to P3b) |
| `pekko-typed-api.md` | P1–P25 Pekko Typed API preferences + TL1/TL2 Cats Effect integration rules |
| `pre-migration-checklist.md` | LOOM pre-flight: grep each actor for sender(), returns, timers, workers before migrating |
| `migration-handoff.md` | Continuation file protocol when a thread ends mid-migration |
| `storage-rocksdb.md` | DataSource contract, column families, iterator lifecycle, WriteBatch, EphemDataSource, RocksDB config |
| `dead-code-review.md` | Three verdicts before any deletion: Wire it / Delete it / Defer — assess gap, git history, and supersession before `git rm` |
| `worktree-protocol.md` | Sprint vs task worktree patterns, naming (`wt/<id>`), lifecycle, bin scripts, agent rules for worktree context |
| `sprint-lifecycle.md` | The permanent queue/log/pattern pipeline for sprint work: research → single queue → fresh-context implementation → close-out (log + pattern capture) → clear → archive |
| `background-script-execution.md` | Long/noisy/freeze-prone commands get a log-to-file wrapper script + `run_in_background: true`, never direct foreground execution or human relay; any long-lived resource it starts (container, JVM process, ephemeral node) must be torn down and that teardown verified, not assumed |
| `compound-command-scratch.md` | Ad hoc `for`/`while`/`until`/`if`/`case` constructs and ad hoc variable-assignment-into-conditional shapes get written once to `.local/scratch/<slug>.sh` and run via `bash .local/scratch/<slug>.sh`, instead of composed inline — the permission system can't pre-allow-list bare shell keywords or per-script variable names; orthogonal to `background-script-execution.md` (syntax shape, not runtime duration) |
| `alert-wrapper-protocol.md` | STOP-AND-ALERT supervision: `watchWith` + structured alarm for actors where restart causes state corruption, instead of restart supervision |
| `loop-handoff.md` | Maker→checker handoff contract for the looping subsystem's EXECUTE→VERIFY transition |
| `dependency-currency.md` | Keeps prescriptive coding-pattern content (not just build pins) current for Scala 3 LTS / Pekko Typed — smell-list for Akka-Classic-era idioms bleeding into "current" docs, `currency:` header convention |
| `git-conventions.md` | Force-push confirmation, merge-conflict escalation, branch-naming outside the worktree system (ported from Nethermind's `git.md`) |
| `github-workflows.md` | Workflow naming/concurrency conventions, PR template, label automation — and the rationale for `.github/CODEOWNERS` staying a lightweight root catch-all rather than growing to per-module density |
| `pr-preflight-checklist.md` | What actually gates a PR (fork-PR differences included) and the local `pr-preflight.sh` composite check that confirms it before pushing, instead of discovering it via CI |
| `batch-research-protocol.md` | Multi-pass research (main+test+it sweep, second broadened pass, precedent/cross-batch-overlap lookup, pre-flight health check) that must run before a QUEUE.md batch's kickoff prompt is trusted — run by the `scout` subagent |
| `systemic-review-protocol.md` | Recurring (monthly) bird's-eye module-vs-reference-client comparison methodology: verdict taxonomy, test-classification taxonomy, reference-client authority model, per-subsystem doc template, parallel-lens/sequential-item execution rule — backs the "Systemic Review" persistent section (`SR-NN`/`SR-EXT-NN`) in QUEUE.md |

## Reference index

`.claude/` carries more than the protocols above — check here before assuming something
doesn't exist:

- **`.claude/skills/`** (symlinks — canonical at **`.agents/skills/`**, per
  `.agents/protocols/tooling/agent-skills.md`) — 24 `fukuii-*` skills: node-operation ones
  (node lifecycle, mining, TLS, peers, disk, logs, security hardening, checkpoint sync,
  custom networks, key management, Engine API setup/debug, dependency audit, tech-debt
  inventory) plus sprint/meta-tooling ones (`fukuii-sprint-queue`, `fukuii-sprint-research`,
  `fukuii-pr-preflight`), 13 `speckit-*` skills backing the Spec Kit workflow below, plus
  `pekko-resource-audit`
  (uncancelled timers, missing `watchWith` cleanup, stream materialization leaks,
  dispatcher starvation — ported from Nethermind's `resource-leak-audit`). **Invoke a
  skill directly for node-operation tasks** (start/stop, config edits, log triage) rather
  than writing ad hoc
  bash; use a specialist subagent instead when the task is source-code analysis or
  modification.
- **`.claude/looping/`** — a DISCOVER → PLAN → EXECUTE → VERIFY automation harness with a
  maker/checker gate (`agent-protocols/loop-handoff.md`). `.claude/looping/registry.yaml` —
  not individual file headers — is the source of truth for which agents are loop-eligible.
  See `.claude/looping/README.md` for how to invoke it.
- **`.claude/hooks/`** — `comment-policy.py` (`PostToolUse` on `Write`/`Edit`/`MultiEdit` to
  `*.scala` files, flags newly added comment lines violating `comments.md`) and
  `compound-command-policy.py` (`PreToolUse` on `Bash`, nudges ad hoc compound/control-flow
  commands toward `.local/scratch/` per `compound-command-scratch.md`) — both advisory only,
  wired via the tracked `.claude/settings.json` (distinct from the untracked, operator-local
  `.claude/settings.local.json`), neither ever blocks a tool call.
- **`.claude/repo-references/`** — ~20 vendored reference repos (pekko, scala2/3, scalafix,
  scapegoat, rocksdb, json4s, circe, hive, ECIPs, EIPs, ethereum/tests, spec-kit, and
  reference clients under `clients/{nethermind,erigon,...}`). `.claude/agents/REFERENCES.md`
  documents which specialist agent uses which repo — check that file rather than guessing.
- **`docs/research/best-practices/`** — research-backed pattern library: `scala/`, `pekko/`,
  `jvm/`, `typelevel/`, `evm-clients/` subdirectories plus a top-level `codebase-audit.md`.
- **`.claude/sprints/`** — the live work-tracking system (operator-local, mostly untracked):
  - `QUEUE.md` — **the single active prompt queue** for sprint work (batches, a findings-
    resolution log, and a standing Chase & Deferred Items section) — see `sprint-lifecycle.md`.
    **This is usually where the fastest-moving, actually-current work lives** — check it
    before assuming a `specs/<NNN>/` plan below reflects "what's happening right now."
  - `completed/`, `archive/` — closed batches, pending then retired.
  - `log/` — permanent, sprint-agnostic record of what changed and why.
  - `patterns/` — reusable execution-pattern library (prompt-writing efficiency patterns,
    not just grep commands).

## Specialist subagents

This project ships project-scoped subagents in `.claude/agents/`. The **main
session is the orchestrator** — subagents cannot spawn other subagents, so you
(the main thread) decide which specialist to delegate to and in what order. Use a
subagent for source-code analysis/modification; use a `.claude/skills/fukuii-*` skill
(above) for operating a running node.

| Agent     | Use it for | Proactive? |
| :-------- | :--------- | :--------- |
| `forge`   | **PoW** consensus (currently ETC/Mordor): EVM, Ethash mining, crypto, state, block rewards, hard forks, EIP/ECIP | **Before** any PoW consensus change |
| `beacon`  | **PoS** consensus (currently ETH/Sepolia): timestamp forks, Osaka EIPs, withdrawals, blobs, execution payload | **Before** any PoS consensus change |
| `banksy`  | **Client-layer policy** (non-consensus, protocol-relevant — sits between forge/beacon and herald): mempool/txpool admission gates, block-production transaction selection/ordering, gas-price/tip floors (ECIP-1122 `MIN_MINER_TIP`), network-authoritative gas-target schedule, subjective fork-choice (MESS/ECIP-1100) | **Before** any change to admission gates, tip/price floors, gas-target enforcement, or MESS/reorg scoring — the state-root litmus decides banksy vs. forge/beacon |
| `eye`     | Validation: compile + run the right test tier, check chain compatibility, report pass/fail | **After** code changes |
| `wraith`  | Scala 3 compile errors / build failures | On compile failures |
| `herald`  | P2P / RLPx / ETH wire protocol, Snappy, handshakes, multi-client interop | On networking issues |
| `mithril` | Idiomatic Scala 3 modernization (opaque types, enums, given/using) | On-demand |
| `prism`   | 8-lens code quality review (non-consensus only): functionality, tests, readability, structure, simplicity, performance, security, scala-fp | Before PRs on non-consensus code |
| `loom`    | Pekko Classic→Typed migration: one actor per session, pre-flight checks, Command ADT, replyTo, timers | On-demand per actor migration |
| `vault`   | RocksDB / storage layer: DataSource contract, iterator lifecycle, WriteBatch, WAL, cache tuning (`db/`) | On storage bugs / config changes |
| `conduit` | JSON-RPC, HTTP, WebSocket, IPC, GraphQL: method compliance, codec, subscriptions (`jsonrpc/`) | On API / transport bugs |
| `flow`    | Pekko Streams: Source/Sink/Flow graphs, materialization, backpressure, `preMaterialize` anti-patterns, stream test synchronization | On streaming graph bugs / silent element drops |
| `warden`  | fukuii's own Claude Code tooling: `scripts/agent-tooling/`, agent-protocols, the looping subsystem, worktree lifecycle, Workflow-based sprint automation, permission/settings guidance | On-demand, for `.claude/` tooling work (not domain code) |
| `scout`   | Pre-implementation research for `QUEUE.md` batches: multi-tree sweep, precedent/cross-batch-overlap lookup, pre-flight health check, drafts the kickoff prompt — read-only, no edits/implementation | **Before** trusting a batch's kickoff prompt (`batch-research-protocol.md`) |
| `sentinel`| **Supply-chain & code-security** specialist: owns ALL dependency changes (`build.sbt`, `project/Dependencies.scala`, plugins, resolvers — the sole gated path; other agents STOP and route here), CVE/security-advisory review, LTS-currency audits, supply-chain risk, code-level security (secrets/injection/key handling). Enforces the supply-chain rules; dependency changes are operator-gated (evidence-based proposal, no unilateral bumps) | **Before** any dependency change; on CVE/security-advisory response; on-demand for LTS-currency/security audits |

### Consensus-Critical Change Protocol (mandatory)

Any change to consensus — EIP/ECIP, chain ID, gas costs, state roots, block
rewards, transaction validation/signing, hard-fork config, mining/PoW, crypto —
**must** follow this order. Do not hand-edit consensus code reactively.

0. **Identify the consensus family**: PoW network (currently ETC/Mordor) → use
   `forge`. PoS network (currently ETH/Sepolia) → use `beacon`. Both families
   affected → use both in sequence.
1. **Plan** (main session): read the spec completely, identify every affected
   component, map side effects.
2. **`forge` or `beacon`** — consult *before* implementing for impact analysis,
   then implement/review with byte-perfect validation against the reference client.
3. **`wraith`** — fix any compilation errors without altering consensus semantics.
4. **`eye`** — validate: tests, consensus compliance, performance.

Triggers: PR/diff mentions "EIP"/"ECIP"; changes under `consensus/`, `vm/`,
`crypto/`, `domain/`; anything affecting block validation, rewards, or signing.
May skip for docs-only, build config, non-consensus test infra, or pure network
formatting (use `herald`).

**The forge/beacon ↔ banksy co-review interface.** `banksy` owns the client-layer
policy tier — mempool/txpool admission, transaction selection, tip/gas-target
floors, MESS subjective fork-choice — which is protocol-relevant but explicitly
*not* consensus (the litmus: does the change alter the state root?). This
produces two required co-review directions, not a one-way handoff:

- **banksy owns, forge co-signs**: MESS / ECIP-1100 (`consensus/pow/mess/`,
  `ledger/BranchResolution.scala`) — subjective and non-state-root, so it's
  banksy's to edit, but its entire purpose is reorg/51%-attack resistance, so
  forge must co-sign every change before it lands.
- **forge owns, banksy is a required consult**: ECIP-1017 emission
  (`BlockRewardCalculator.scala`) and ECIP-1111 base-fee floor/Treasury routing
  (`BlockPreparator.scala`) — both state-affecting and therefore forge's to
  implement, but they define the network security-budget economics that
  banksy's ECIP-1122 tip floor is sized against, so banksy must be consulted
  before either lands.

Route MESS or admission/tip-floor/gas-target changes to `banksy` first; route
emission/base-fee-floor changes to `forge` first but loop in `banksy` before
merging. See `banksy.md` for the full charter and `consensus-change-protocol.md`
for the canonical litmus text.

## OODA loop for large migrations / multi-file work

For comprehensive changes, cycle through:

- **Observe** — map the affected code, read ADRs/specs, study core-geth, and run
  an initial compile to enumerate the real errors. For consensus-touching work,
  run the Consensus-Critical Change Protocol's planning step here.
- **Orient** — prioritize P0 (blocking/core) → P1 (production-readiness) → P2
  (tests) → P3 (polish); map dependencies and the critical path.
- **Decide** — scope what to do now vs. later; route work to the right specialist
  subagent; assess risk and rollback.
- **Act** — small focused commits, compile/test after each, update tracking docs.
  Loop back when new information emerges.

## Continuation protocol (applies to every agent)

When a session is running low on turns **or** a logical phase ends with more work
remaining, write a continuation file before the session ends:

```
.local/docs/continuations/<AgentName>-<Topic>.md
```

**Standard sections** (keep each short — the next thread reads this cold):

```markdown
# <AgentName> continuation — <Topic>

## Status
What phase just completed. What phase is next.

## Files modified
| File | Status | Last action |
|------|--------|-------------|
| path/to/File.scala | IN PROGRESS | removed returns, not yet migrated |

## Open compile errors
Paste exact error messages. If clean, write: "sbt compile-all — 0 errors".

## Next action (first thing to do)
One specific instruction: "Open X.scala line N and change Y to Z."

## Test baseline
Last known result: e.g. "3,621 / 0 — run before and after any change."
```

**The continuation thread** reads this file as its first action before anything
else. Do not re-research what is documented here.

**Prior group summaries** (modernization sprint, archived): if pointed at an
implementation-sprint summaries directory from an older continuation file, check
`.local/docs/archive/2026-06/` first — several June sprint working directories were
moved there and the reference has drifted before.

## Spec-Driven Development (Spec Kit)

New features are built through the Spec Kit workflow, not ad hoc:
`/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`
(use `/speckit-clarify` and `/speckit-analyze` to de-risk). Spec artifacts live
under `specs/<NNN-feature-name>/`. See `specs/` for the full history of past and
in-progress features — several exist with zero completed tasks (not tracked here,
since that list goes stale fast; check each `tasks.md` directly).

**The project constitution at `.specify/memory/constitution.md` is binding.**
Read it before planning or implementing. Highlights:

- Consensus-critical code (EVM/gas, state roots, hashes, RLP, Ethash, rewards,
  hard forks) MUST be byte-for-byte deterministic and ETC-spec compliant — design
  before implementing; follow the `forge` protocol in `.claude/agents/forge.md`.
- Scala 3.x LTS only; code MUST pass `scalafmt` + `scalafix`.
- Tests MUST be deterministic (no `Thread.sleep`); keep statement coverage ≥ 70%.
- Run `sbt pp` before opening a PR; CI gates and review must be green to merge.

**Two work tracks — check both.** `specs/<NNN>/` (above) is Spec Kit's pipeline
for net-new features. `.claude/sprints/QUEUE.md` (see Reference index) is the
day-to-day tracker for modernization/cleanup/audit work on existing code (per
`agent-protocols/sprint-lifecycle.md` Rule 7) — **it is usually where the actual,
fastest-moving work lives.** The block below is auto-regenerated by the
`speckit-agent-context-update` skill and always points at whichever
`specs/*/plan.md` was most recently touched on disk — a doc-only edit or an
unrelated merge can bump a stalled spec's mtime above one that's genuinely
active, so treat this as "a" current Spec Kit plan, not "the" current work.
Any hand-edited prose inside the markers below is silently overwritten the next
time that skill runs — keep commentary about this block above it, not inside it.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
at specs/007-hotpath-alloc-reduction/plan.md
<!-- SPECKIT END -->
