---
name: fukuii-sprint-research
description: >-
  Run pre-implementation research on a `.claude/sprints/QUEUE.md` batch before its
  kickoff prompt is trusted, via the `scout` subagent and
  `batch-research-protocol.md`. Use when asked to "research batch N", "draft the
  prompt for batch N", "check batch N for gaps before we run it", or before
  resuming any batch that's been sitting as a skeleton/summary. Produces a
  drafted, house-style kickoff prompt plus pre-dispositioned findings, and shows
  a diff/preview before writing anything into QUEUE.md. Does NOT implement the
  batch itself, does NOT drive compile/test loops beyond one pre-flight check,
  and does NOT replace forge/beacon consensus review for consensus-adjacent scope.
argument-hint: "<batch-name-or-topic>"
disable-model-invocation: true
user-invokable: true
---

# Fukuii sprint research

Dispatches the `scout` subagent to run `.claude/agent-protocols/batch-research-protocol.md`
against a named batch or topic, then shows the operator exactly what would change in
`QUEUE.md` before writing it.

## When to use

- Drafting a batch's kickoff prompt for the first time (`sprint-lifecycle.md` Rule 2 —
  "prompts are drafted at the start of the batch they belong to").
- Retroactively checking an already-drafted batch before its implementation starts,
  when there's reason to think the original research was single-pass or narrow in scope.
- "What's left before batch N is safe to run" / "research batch N" / "draft batch N's prompt".

## Procedure

1. Parse `$ARGUMENTS` for the batch name or research topic. If empty, ask which batch —
   don't guess; a vague target produces a vague sweep.
2. Read the batch's current entry in `.claude/sprints/QUEUE.md` (skeleton summary or
   already-drafted prompt) and any linked prior research doc, so `scout` has the existing
   baseline to independently verify and extend — not to re-derive from zero.
3. Invoke `scout` (Agent tool) with: the batch name/topic, the current QUEUE.md entry text,
   any linked research doc paths, and an explicit instruction to follow
   `.claude/agent-protocols/batch-research-protocol.md` rules (a)-(h) in full.
4. `scout` returns a `SCOUT REPORT` (see its own output format): coverage summary, a drafted
   kickoff prompt, a findings table with dispositions already chosen, and a pre-flight health
   check result.
5. **Show the operator an actual diff/preview** of what would be written into `QUEUE.md` —
   the drafted prompt block, plus each findings-table row rendered as its target
   QUEUE.md section (Findings Resolution Log row, Chase & Deferred Items row, or new batch
   entry). Do not just print scout's raw report and apply it — the operator needs to see the
   *edit*, not just the research, before confirming.
6. On confirmation, apply the edit(s) to `QUEUE.md` at the correct position(s), per
   `sprint-lifecycle.md` Rule 1 (single source of truth — no parallel tracker).

## Output

Report what was written into `QUEUE.md` and where (batch section, Findings Resolution Log,
Chase & Deferred Items), plus `scout`'s pre-flight health check verdict. If any finding was
left without a chosen disposition, stop and resolve it per `finding-resolution.md` before
calling the research pass done — never leave a bare "flagged, TBD."
