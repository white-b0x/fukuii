# Sprint Lifecycle Protocol

The end-to-end pipeline for modernization/cleanup/audit/research sprint work — from research
through implementation through permanent record. This is the doc every agent and every other
protocol should point to instead of restating current sprint state inline.

Used by: ALL agents. Referenced by: `CLAUDE.md`, `inline-cleanup.md`, `finding-resolution.md`,
`dead-code-review.md`, `warning-ratchet.md`, `pre-migration-checklist.md`, `pekko-typed-api.md`,
`logging-standards.md`, `consensus-change-protocol.md`, `batch-research-protocol.md`.

---

## The pipeline

```
research (.local/docs/research-*/)
  -> single queue (.claude/sprints/QUEUE.md), organized in Persistent Sections + Batches
  -> fresh-context-per-item implementation (self-contained kickoff block, per Item/Thread)
  -> mid-implementation findings -> finding-resolution.md disposition
     -> inserted into QUEUE.md at the correct position (never appended blind)
  -> a Batch fully closes -> REQUIRED close-out pass (see below); a Persistent Section never
     closes as a whole — only its individual Items do
  -> sprint-clear.sh --apply moves a closed Batch: QUEUE.md -> sprints/completed/<branch>-CLEARED.md
  -> sprint-archive.sh --apply moves the completed doc -> sprints/archive/, gated on a
     sprints/log/ entry existing for it
```

---

## Document hierarchy (as of the 2026-07-05 restructuring)

`QUEUE.md` has exactly two top-level containers, plus one always-first triage gate:

- **`## Critical & Security Fast-Track`** — not part of either container below; checked first,
  ahead of everything, for the narrow set of findings that meet its bar (see the section's own
  header and `finding-resolution.md` Rule 0). Everything else goes through one of the two
  containers.
- **`## Persistent Sections`** — ongoing themes that never fully close: **REPO** (repo
  hygiene/CI/agentic tooling), **Security** (non-urgent hardening backlog), **Parity**
  (reference-client comparison — re-extended every time a reference client updates or a new
  EIP/ECIP lands), **Modernization** (Scala/Pekko/dependency currency — will gain a whole new
  wave of Items the next time a Scala/Pekko LTS ships), **Performance** (optimization backlog).
  Each holds numbered **Items** (`REPO-NN`, `PP-NN`, `MOD-NN`, `OPT-NNN`, `SEC-NN`) in its own
  table/list. An Item gets drafted into a full kickoff prompt (a **Thread** — see below) and
  executed the same way a Batch is, but the Section itself stays open indefinitely.
- **`## Batches`** — finite, numbered units of work (`### Batch N — <STATUS>`) that DO close
  and archive via `sprint-clear.sh`/`sprint-archive.sh` once done. A Batch may be split into
  **Groupings** (sub-batches, e.g. `2a`/`2b`/`2c`) when too large for one Thread.

A **Thread** is the actual fresh-context kickoff-prompt unit — one Batch, one Grouping, or one
Persistent-Section Item, self-contained enough to paste into a brand-new Claude Code session
with no dependency on the planning thread's context (Rule 3 below).

The broader, largely-implicit top level is a **Phase** — an entire sprint arc spanning
potentially weeks (e.g. "the July sprint"), tracked at `sprints/log/INDEX.md`'s
"Cross-Cutting Entries" level, not as a `QUEUE.md` heading; `QUEUE.md` only ever shows the
*current* Phase's live work.

**Why this separation matters mechanically, not just for readability:** `sprint-clear.sh` only
ever operates between the `## Batches` header and the next `## ` header. A Persistent Section
can never be mistaken for a closeable Batch, structurally, no matter what its own heading text
says — see that script's own header comment for the incident (2026-07-05) that motivated this.

---

## Rule 1: Single source of truth

One active file: `.claude/sprints/QUEUE.md`. No parallel tracker, no per-batch follow-up file,
no separate CHASE-QUEUE/DEFERRED-BACKLOG file. Incidental cross-file finds (per
`inline-cleanup.md`) and genuinely-deferred items (per `finding-resolution.md` disposition 3)
are sections *within* QUEUE.md — see its "Chase & Deferred Items" section — not separate
documents. This is the direct fix for the failure mode that motivated this protocol: multiple
tracking files silently disagreeing with each other about whether something was done.

A new finding — whether from a dedicated audit pass or cascaded out of an implementation
session — is placed at its correct logical/batch position in QUEUE.md, not appended to the
bottom and not split off into a side file. Follow `finding-resolution.md`'s three dispositions
exactly; there is no fourth option.

## Rule 2: Prompts are drafted at the start of the batch they belong to

Not upfront for the whole queue. A batch further out than "next" gets a placeholder row, not a
fully-written kickoff prompt — writing it early just means it goes stale before it's used.

This rule says *when* to draft; `batch-research-protocol.md` says *how thoroughly* — run its
multi-pass sweep (main+test+it, a second broadened pass, precedent/cross-batch-overlap lookup,
a pre-flight health check) via the `scout` subagent before trusting the draft, not just a single
pass over whatever research doc happens to exist. Batch 1's growth from ~9 prompts to 11 major
items came directly from skipping this rigor the first time.

## Rule 3: Fresh context per implementation item

Every item that will actually be executed must be self-contained: branch, baseline test count,
exact file paths, exact steps, exact output path — so it can be pasted into a brand-new Claude
Code session with no dependency on the planning thread's context. This matches the established
practice of one fresh session per spec/item (see the Kickoff Thread block style already used
in prior sprint docs) and keeps implementation threads cheap: they read only the kickoff block
and the codebase, not a long planning history.

Writing a good kickoff block is itself a skill — see Rule 4. A kickoff block that makes the
implementing agent re-derive context the operator already has is exactly the "investigation
stall" `sprints/patterns/PATTERNS.md` exists to eliminate. `batch-research-protocol.md`'s output
contract (rule (h)) is what actually produces this self-contained block — the research pass
front-loads the context so the implementation thread doesn't have to re-derive it.

## Rule 4: Pattern capture is required at batch close, not optional

When a batch closes, **before** it's cleared and archived, the close-out pass must ask: did
this batch's approach recur (this is at least the second time we've done it this way), or is
it clearly reusable for future batches? This is about *how prompts are written and sequenced*
for efficiency — removing investigation stalls (an agent burning turns re-deriving context it
could have been handed up front), compile/test stalls (re-running a slow check after every
small edit instead of batching it), and general token burn — not merely capturing the literal
grep commands or file lists a batch happened to use.

If yes: write it up in `.claude/sprints/patterns/PATTERNS.md` using its entry format (what
stall it removes, the old way vs. the pattern, a worked example, a pointer to a mechanical
helper if one exists). If the mechanical part is genuinely reusable as a tool (not just a
one-off command), extract it into `scripts/agent-tooling/lib/<name>.sh` so agents call it instead of
re-deriving the same choreography by hand — or, for a whole *command family* prone to
long/noisy output (see `background-script-execution.md`), a top-level `scripts/agent-tooling/<name>.sh`
wrapper, following `sbt-run.sh` as the reference shape.

If no: note "nothing to promote" in the close-out and move on. This is not busywork required
on every batch — only real, confirmed repeats earn a patterns entry.

**Safety-severity exception:** most patterns wait for a second occurrence before they're worth
writing down (per `PATTERNS.md`'s own "confirmed reusable" field). A pattern whose failure mode
is severe enough on its own — a session or host freeze, data loss risk, anything beyond wasted
time — gets captured and promoted to a protocol immediately, mid-batch, not deferred to
close-out. `background-script-execution.md` (born from a full host freeze during IP-CL-A) is
the precedent: one incident was sufficient evidence, no need to wait and see if it recurred.

## Rule 5: Batch close-out, in order

1. **Write/update the `sprints/log/` entry** for every area the batch touched — what changed,
   why, which commits. This is the permanent record; write it before clearing the batch out of
   the queue, not after, while the detail is still fresh in context.
2. **Pattern check** (Rule 4).
3. **`sprint-clear.sh --apply`** — moves the closed batch out of `QUEUE.md` into
   `sprints/completed/<branch>-CLEARED.md`.
4. **`sprint-archive.sh <file> --apply`** — moves a `sprints/completed/` file into
   `sprints/archive/`, but only once step 1's log entry exists for it. The script enforces
   this mechanically; it refuses without `--force` if it can't find a reference.

## Rule 6: Relationship to `.claude/looping/`

Looping recipes are for work that is **fully mechanically gate-verifiable** — a scripted
sentinel line (`GATE:<name> RESULT:PASS|FAIL`) is sufficient to declare it done, no human or
forge/beacon judgment required (warning-ratchet, test-greening, actor-migration, etc.).
`sprints/QUEUE.md` is for work that needs human or consensus-checker judgment — opaque-type
propagation with forge/beacon gates, audits, research, anything where "done" is a judgment
call, not a script's exit code. Don't route looping-eligible work through the queue, and don't
try to force queue work (that genuinely needs a human/consensus call) into a looping recipe
just to get it automated.

## Rule 7: Relationship to Spec-Kit

Net-new features go through `/speckit-specify` → `/speckit-plan` → `/speckit-tasks` →
`/speckit-implement`, with artifacts under `specs/<NNN-feature-name>/`. `sprints/QUEUE.md` is
for modernization/cleanup/audit/research sprint work on **existing** code, not new features.

## Rule 8: The permanent record lives at `.claude/sprints/log/`

`.claude/sprints/log/` is git-tracked (an intentional exception in `.gitignore`, matching the
precedent set for `scripts/agent-tooling/`) — the durable, team-shared, sprint-agnostic record of
what changed and why, for every sprint going forward.

**Transitional note (as of this migration):** the June 2026 Pekko/Scala3 modernization
effort's historical record has been copied verbatim into
`.claude/sprints/log/legacy-modernization-log/`. The original at
`.claude/agent-protocols/modernization-log/` is retired in Phase B of the
progress-tracking/modernization-log cleanup (see below) once that copy is confirmed — until
then both paths exist; `.claude/sprints/log/legacy-modernization-log/` is canonical going
forward, don't add new entries to the original. Once Phase B completes, this note and the
original path are both gone and this rule can drop the caveat.

## Rule 9: Deferred — headless automation

Batch execution stays manual/interactive for now. The reason: the single-queue model needs to
be run by hand across several real batches, with an operator in the loop, before the
prompt/script instruction sets are trustworthy enough to hand to an unattended `claude -p`
runner. `.claude/looping/`'s `README.md` Quick Start section (`claude -p ... --max-turns N`,
`/loop 1w ...`) is the reference point for what that will eventually look like here. This is
an intentional, named next phase — not an oversight if a future session finds no runner script.

## Cutover: `.claude/sprints/QUEUE.md` takes over Batch 1's remaining run order at IP-CL-A

The queue does not wait for the entire Batch 1 to close (`BATCH-1-CLOSE`, the last item in the
run order) before it starts being used — that would mean 16 more items ran from the legacy
location first. Instead, the cutover point is **IP-CL-A closing** (its 4th and final sub-batch
landing): from `IP-CL-J` onward, including `IP-14` and `BATCH-1-CLOSE` themselves, Batch 1's
remaining run order executes from `.claude/sprints/QUEUE.md`, not
`JULY_SPRINT_PROMPTS.md`/`july-follow-ups.md`. See `QUEUE.md`'s own Batch 1 section for the
live status and the exact trigger sequence (write IP-CL-A's log entry → flip GATED-ON-IP-CL-A
to OPEN → hand off the drafted IP-CL-J kickoff).

## Retiring the legacy tracker — Phase A complete, Phase B pending explicit sign-off

`.claude/progress-tracking/` and `.claude/agent-protocols/modernization-log/` are being
retired in two hard-separated phases (see the retirement plan): **Phase A** copies every piece
of content to its new home in `.claude/sprints/*` and verifies nothing is lost — that phase is
done as of this note. **Phase B** deletes the now-redundant originals, but only file-by-file,
each gated on the user's own verbal review of where that content landed — not an automated
follow-on to Phase A passing its checks. Until Phase B runs, **both the old and new paths
exist simultaneously**; treat `.claude/sprints/*` as canonical for anything new, but don't
assume the old paths are gone. This note itself gets deleted once Phase B completes.
