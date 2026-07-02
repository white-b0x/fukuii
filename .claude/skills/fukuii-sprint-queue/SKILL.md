---
name: fukuii-sprint-queue
description: >-
  Report status of, clear closed batches from, and archive logged batches out of
  the permanent sprint queue at .claude/sprints/ — the single active prompt list
  for modernization/cleanup/audit/research sprint work (see
  .claude/agent-protocols/sprint-lifecycle.md). Use when asked to "check sprint
  status", "clear the sprint queue", "archive a completed batch", or "what's
  outstanding in the sprint". Does NOT drive or launch sprint implementation
  work itself — status/clear/archive only. Read-only for status; clear/archive
  mutate untracked local files under .claude/sprints/ (dry-run by default).
argument-hint: "status|clear|archive <file>"
disable-model-invocation: true
user-invokable: true
---

# Fukuii sprint queue

Dispatches to the three collector scripts in `.claude/scripts/` that keep
`.claude/sprints/` tidy. See `.claude/agent-protocols/sprint-lifecycle.md` for the
full pipeline this supports, and `.claude/sprints/QUEUE.md` for the live queue.

## When to use

- `status` — "what's outstanding", "check sprint status", start-of-session orientation.
- `clear` — a batch in `QUEUE.md` is marked `CLOSED` and ready to move to `completed/`.
- `archive <file>` — a file in `sprints/completed/` has a `sprints/log/` entry and is
  ready to retire to `sprints/archive/`.

## Procedure

Parse `$ARGUMENTS` for the subcommand (default to `status` if empty):

### status
```bash
.claude/scripts/sprint-status.sh
```
Read-only. Report the output directly — batch counts, Chase & Deferred item count,
`completed/`/`archive/` contents, and the legacy-tracker note during the Batch 1→2
transition period.

### clear
```bash
.claude/scripts/sprint-clear.sh          # dry run — always show this first
```
If it reports CLOSED batches ready to move, show the preview to the operator and
confirm before applying:
```bash
.claude/scripts/sprint-clear.sh --apply
```
Before running `--apply`, confirm Rule 5 steps 1–2 of `sprint-lifecycle.md` have
already happened for that batch (the `sprints/log/` entry is written, and the
pattern-capture check has been done) — this script only moves the text, it
doesn't verify those steps were done.

### archive
```bash
.claude/scripts/sprint-archive.sh <file>          # dry run — always show this first
```
The script refuses on its own if no `sprints/log/` reference exists for the file
— that's the log-before-archive gate working as intended, not an error to work
around. If it refuses and no log entry exists yet, write one instead of reaching
for `--force`. Only pass `--force` if you've confirmed the file is genuinely
logged under different wording. On a real reference being found:
```bash
.claude/scripts/sprint-archive.sh <file> --apply
```

## Output

Report the script's own output verbatim — it's already structured. Don't
paraphrase batch counts or file lists; the raw sentinel-style output is the
evidence.
