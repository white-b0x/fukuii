# Sprint Lifecycle Protocol

The end-to-end pipeline for modernization/cleanup/audit/research sprint work — from research
through implementation through permanent record. This is the doc every agent and every other
protocol should point to instead of restating current sprint state inline.

Used by: ALL agents. Referenced by: `CLAUDE.md`, `inline-cleanup.md`, `finding-resolution.md`,
`dead-code-review.md`, `warning-ratchet.md`, `pre-migration-checklist.md`, `pekko-typed-api.md`,
`logging-standards.md`, `consensus-change-protocol.md`.

---

## The pipeline

```
research (.local/docs/research-*/)
  -> single queue (.claude/sprints/QUEUE.md), organized in batches/sections
  -> fresh-context-per-item implementation (self-contained kickoff block, per item)
  -> mid-implementation findings -> finding-resolution.md disposition
     -> inserted into QUEUE.md at the correct batch/logical position (never appended blind)
  -> batch fully closes -> REQUIRED close-out pass (see below)
  -> sprint-clear.sh --apply moves the batch: QUEUE.md -> sprints/completed/<branch>-CLEARED.md
  -> sprint-archive.sh --apply moves the completed doc -> sprints/archive/, gated on a
     sprints/log/ entry existing for it
```

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

## Rule 3: Fresh context per implementation item

Every item that will actually be executed must be self-contained: branch, baseline test count,
exact file paths, exact steps, exact output path — so it can be pasted into a brand-new Claude
Code session with no dependency on the planning thread's context. This matches the established
practice of one fresh session per spec/item (see the Kickoff Thread block style already used
in prior sprint docs) and keeps implementation threads cheap: they read only the kickoff block
and the codebase, not a long planning history.

Writing a good kickoff block is itself a skill — see Rule 4. A kickoff block that makes the
implementing agent re-derive context the operator already has is exactly the "investigation
stall" `sprints/patterns/PATTERNS.md` exists to eliminate.

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
one-off command), extract it into `.claude/scripts/lib/<name>.sh` so agents call it instead of
re-deriving the same choreography by hand.

If no: note "nothing to promote" in the close-out and move on. This is not busywork required
on every batch — only real, confirmed repeats earn a patterns entry.

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

## Rule 8: Relationship to `.claude/agent-protocols/modernization-log/`

That directory is left exactly as-is — an accurate historical record of the June 2026
Pekko/Scala3 modernization effort. `.claude/sprints/log/` is the same idea, permanent and
sprint-agnostic, for every sprint going forward. Don't write new entries into
`modernization-log/`; don't rename or restructure it either.

## Rule 9: Deferred — headless automation

Batch execution stays manual/interactive for now. The reason: the single-queue model needs to
be run by hand across several real batches, with an operator in the loop, before the
prompt/script instruction sets are trustworthy enough to hand to an unattended `claude -p`
runner. `.claude/looping/`'s `README.md` Quick Start section (`claude -p ... --max-turns N`,
`/loop 1w ...`) is the reference point for what that will eventually look like here. This is
an intentional, named next phase — not an oversight if a future session finds no runner script.

## Deferred: retiring the legacy tracker

`.claude/progress-tracking/` (Batch 1's live location, branch `july-fourth`) is untouched by
this protocol's introduction. Once Batch 1 closes under its existing process, that closure is
the trigger for a separate pass: run Batch 1's own close-out once by the old manual method,
decide what (if anything) from its archives is worth preserving into `sprints/log/` or
`sprints/archive/`, then retire the directory. `.claude/sprints/QUEUE.md` starts accepting
work at Batch 2 regardless of when that cleanup happens.
