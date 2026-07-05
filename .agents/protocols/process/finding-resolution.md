# Finding Resolution Protocol

Every audit, review, or research pass produces findings. This protocol governs what
happens to a finding the moment it's written down — it must never end its life as a
bare note in a doc with no owner and no scheduled fix.

Used by: ALL agents that produce audit/research/review output (straggler audits, parity
research, dead-code reviews, code-quality sweeps, PP-series survey prompts)
Referenced by: `inline-cleanup.md` (narrower, incidental-find scope — see distinction below)

---

## Core principle

> A finding is not done being handled when it's written down. It's done being handled
> when it has a resolution owner: either an existing IP's known-site-list absorbs it
> (with an explicit cross-reference), or a new IP is created and scheduled into the
> current sprint plan. "Flagged, not fixed, out of scope" is a valid statement about a
> single audit *pass* — it is never a valid final state for a *finding*.

The failure mode this protocol exists to prevent: an audit agent (correctly) declines to
fix something outside its current task boundary, writes "flagged, not fixed, out of this
round's scope" in its output doc, and the finding is then never looked at again because
no downstream step was ever pointed at it. The finding is technically documented but
practically forgotten. This happened during the July sprint's opaque-type cleanup —
round 2 of the straggler audit found `jsonrpc/TransactionRequest.scala` and
`jsonrpc/EthSimulateService.scala` contradicted round 1's "GasPrice — CLEAN" verdict,
correctly declined to fix it mid-audit, and correctly flagged it — but the flag alone is
not the fix. The orchestrator's job (whoever reads the audit output next) is to convert
every such flag into a scheduled prompt in the same pass that reads the audit, not to
carry it forward as a mental note.

---

## Audit findings vs. incidental finds — these are DIFFERENT things

`inline-cleanup.md` governs incidental finds: a mechanical/idiom issue an agent notices
in a file it opened for unrelated primary work. Deferral to the Chase & Deferred Items
section of `.claude/sprints/QUEUE.md` is correct there — the agent has no mandate to fix
something outside its task, and the pattern needs a critical mass (N=5+) before it's
worth a dedicated sprint slot.

This protocol governs a different case: a **dedicated audit/review pass** whose entire
job is to find issues (straggler audits, parity research, `dead-code-review.md`
verdicts, PP-series consensus surveys, `/code-review` output). For these:

| | Incidental find (inline-cleanup.md) | Audit finding (this protocol) |
|---|---|---|
| Origin | Noticed while doing unrelated primary work | The output of a task whose PURPOSE was to find this |
| Default disposition | Log to Chase & Deferred Items, batch when N=5+ | Schedule a resolution IMMEDIATELY, in the same session that reads the audit |
| Acceptable to leave unscheduled? | Yes, until critical mass | No — every finding gets a resolution owner before the audit is considered closed |

If you are the orchestrator reading an audit's output (not the audit agent itself),
you own converting every finding into a scheduled resolution before moving on to
anything else the audit's output was meant to unblock.

---

## Rule 0: Triage first — fast-track or normal disposition

Before applying Rule 1's three dispositions, ask one question: does this finding meet the
`QUEUE.md` "Critical & Security Fast-Track" section's bar? That bar is deliberately narrow —
an actively exploitable/triggerable security issue (a real trigger path today, not a
hypothetical future one), a live data-loss or consensus-correctness bug, or credential/key
exposure with a real trigger path, not a latent one. See that section's own header for the
full inclusion/exclusion text.

If yes: add it there directly, skip the batch/defer machinery below entirely — no waiting for
scout's research pass, no waiting for a batch's turn in the run order.

If no (the common case — most findings, including most security-adjacent ones like a latent
cleartext-logging risk with no current trigger): proceed to Rule 1's three dispositions as
normal. Most findings are not fast-track-critical; don't inflate a routine hardening item into
one just because it touches security-sensitive code.

## Rule 1: Every finding gets one of three dispositions — no fourth option

1. **Absorbed into an existing scheduled IP.** The finding fits the scope of a prompt
   already being drafted or already in the sprint — add it to that prompt's known-site-list
   with an explicit note: `**newly surfaced by <audit-name>, not in <prior-audit>'s tables**`
   or `**correction to <prior-audit>'s <verdict> — resolved here**`. This is the common case.
2. **A new IP is created and scheduled.** The finding doesn't fit any in-flight prompt's
   scope (different gate, different subsystem, genuinely new work). Create the IP, give it
   an ID, and place it at its correct position in the sprint's run order — not appended
   to the bottom of the doc as an afterthought.
3. **Explicitly deferred to a named future batch, with a tracking entry.** Genuinely
   out-of-scope-for-this-sprint work (e.g. a different subsystem entirely, or blocked on
   an external dependency) gets a real entry in a future batch section — not a bare
   comment. It must be visible in the sprint doc's batch list, not just mentioned in
   passing inside another IP's CONTEXT.

"Flagged in the audit doc, not otherwise scheduled" is not disposition 1, 2, or 3 — it's
the failure mode. If you catch yourself about to leave a finding in that state, stop and
pick one of the three.

---

## Rule 2: Findings Resolution Log

Every sprint tracking doc with an active audit/cleanup batch keeps a **Findings
Resolution Log** — a short table near the top of the batch section:

```markdown
## Findings Resolution Log

| Finding | Source | Resolution | Status |
|---------|--------|-----------|--------|
| GasPrice not clean — TransactionRequest.scala/EthSimulateService.scala | round-2 straggler audit | IP-CL-A (folded in) | SCHEDULED |
```

This is not a duplicate of Chase & Deferred Items — that section is for incidental finds
pending critical mass. This log is specifically for dedicated-audit output, and every row must
have a non-empty Resolution column before the log entry is considered closed. An entry
with an empty or "TBD" Resolution column is a signal the orchestrator hasn't finished
processing the audit yet — not a legitimate final state.

---

## Rule 3: An audit is not "done" until its findings log is fully resolved

When an audit-producing agent (or the orchestrator synthesizing its output) reports
"audit complete," that report must be followed — in the same session, before moving to
unrelated work — by populating or updating the Findings Resolution Log for every new
finding the audit surfaced. A correction to a prior verdict (like round 2's GasPrice
correction) is itself a finding and gets its own log row, even though the underlying
sites end up inside another IP's table rather than a standalone prompt.

---

## Rule 4: Corrections to prior audit verdicts must be visible, not silent

If audit round N contradicts or corrects a verdict from round N-1 (e.g. "type X is not
actually CLEAN"), that correction must be stated explicitly in whatever downstream
artifact resolves it — not just fixed silently. A future reader comparing round N-1's
summary table against round N's should not have to reconstruct the correction from
context; the resolving IP's CONTEXT section states it directly:
`**Corrects round 1's "GasPrice — CLEAN (main)" verdict** — see round 2 finding, resolved
by this prompt's <field list>.`

---

## Anti-pattern reference

| Don't | Do instead |
|-------|-----------|
| "Flagged, not fixed, out of this round's scope" as the final word on a finding | Immediately schedule it (Rule 1) and log it (Rule 2) in the same pass that reads the finding |
| Leaving a correction to a prior verdict as a paragraph buried in a new audit's prose | State it explicitly in the resolving IP's CONTEXT (Rule 4) and in the Findings Resolution Log |
| Assuming "someone will pick this up later" | There is no implicit owner in this workflow — an unscheduled finding has no owner |
| Treating audit-pass findings the same as incidental cleanup finds | Audit findings get scheduled immediately; Chase & Deferred Items/critical-mass batching is for incidental finds only (see the comparison table above) |
