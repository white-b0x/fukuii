# Incident Postmortem Template

This is a **blank template**, not a filled example — copy it to a new file
(suggested naming: `docs/runbooks/postmortems/<YYYY-MM-DD>-<short-slug>.md`,
creating that directory the first time it's used) and fill in every section
for a real incident. It is modeled on go-ethereum's
`docs/postmortems/2021-08-22-split-postmortem.md` (analyzed in
`docs/research/best-practices/evm-clients/repo-patterns/go-ethereum/repo-hygiene-pattern.md`):
a timestamped timeline, a technical root cause with a worked before/after
example, the actual handling decision made (with room for honest retrospective
disagreement), an exploit/impact section, lessons learned, and a coordinated
disclosure record — not just prose, but durable enough to double as a
regression-test specification.

Fill this in **the moment a real consensus-affecting bug, security incident, or
network-split event is found and fixed** — regardless of whether it was ever
exploited in the wild. Waiting until pressure forces a writeup produces a worse
document than writing one now, while the details are still fresh.

## Informal prior art

fukuii does not yet have any postmortem in this shape. Two existing `docs/`
directories already contain informal incident/investigation write-ups that
predate this template and are **not** being retrofitted to it — they stay
exactly as they are, and are cross-linked here only as prior art a postmortem
author may want to skim for tone and prior findings before writing a new one:

- `docs/historical/reviews/` — four frozen historical review documents:
  [`COMPRESSION_FIX_WIRE_PROTOCOL.md`](../historical/reviews/COMPRESSION_FIX_WIRE_PROTOCOL.md),
  [`RUN_007_INVESTIGATION_SUMMARY.md`](../historical/reviews/RUN_007_INVESTIGATION_SUMMARY.md),
  [`SNAP_PROTOCOL_COMPLIANCE_VALIDATION.md`](../historical/reviews/SNAP_PROTOCOL_COMPLIANCE_VALIDATION.md),
  [`SNAP_SYNC_IMPLEMENTATION_REVIEW.md`](../historical/reviews/SNAP_SYNC_IMPLEMENTATION_REVIEW.md).
- [`docs/analysis/`](../analysis/README.md) — investigation reports such as
  `CONTRACT_TEST_FAILURE_ANALYSIS.md` and `FASTSYNC_TIMEOUT_INVESTIGATION.md`
  (see that directory's own `README.md` for its indexed list).

Neither directory follows this template's structure, and neither should be
rewritten to match it after the fact — they are frozen records of what was
investigated and found at the time. Only *new* incident writeups should use
this template going forward.

---

<!-- Delete everything above this line when starting a real postmortem. -->

# <Incident Title> — <YYYY-MM-DD>

**Status:** Draft / Under Review / Published
**Affected network(s):** <e.g. ETC mainnet, Mordor, ETH Sepolia>
**Affected version(s):** <fukuii version/commit range>
**Severity:** <e.g. consensus-splitting / non-consensus crash / data corruption / informational>

## Timeline

<Chronological, timestamped list of every event from initial report/discovery
through final resolution. Use UTC. Include who reported it and through what
channel.>

- `YYYY-MM-DD HH:MM UTC` — <event>
- `YYYY-MM-DD HH:MM UTC` — <event>

## Technical Root Cause

<Full technical explanation of the underlying defect. Name the exact
component/module/function. If the bug is subtle, walk through a concrete
worked example of the failure mode step by step (state before → operation →
corrupted state after), the way go-ethereum's postmortem shows the
`RETURNDATA` aliasing bug as a 3-step memory diagram rather than prose alone.>

**Before (buggy behavior):**

```scala
// paste the exact vulnerable code, or a minimal reproducing snippet
```

**After (fix):**

```scala
// paste the exact fixed code
```

<If applicable, a worked before/after state or memory diagram:>

```
1. <step>
   <state>
2. <step>
   <state>
3. <step>
   <state>
```

## Handling Decision

<What was decided once the bug was understood: patch immediately vs.
coordinated disclosure with a delay, whether the fix was shipped under an
unrelated-looking title/PR to avoid tipping off potential attackers before the
network could upgrade, whether any unrelated in-flight changes had to be
reverted or delayed to avoid compounding the release. Quote the actual
reasoning discussed at the time, including dissenting views. If, in
hindsight, the team would make a different call today, say so explicitly —
an honest reconsideration is more valuable than a decision presented as
obviously correct after the fact.>

## Exploit / Impact (if applicable)

<Delete this section entirely if the bug was never triggered outside of
testing. If it was: exact block number/height, timestamp, transaction
hash(es), how the exploit was detected (tooling vs. community report), who
detected it and through what channel, whether other networks/clients sharing
the same code lineage were also affected, and any attacker-attribution details
worth recording plainly.>

## Lessons Learned

<Concrete, specific process changes committed to as a result of this incident
— not vague "we'll do better" statements. E.g.: a new monitoring/alerting gap
that was closed, a disclosure-list gap that was found (who should have been
notified but wasn't), a testing gap (what kind of fuzzing/differential
testing would have caught this earlier), tooling that behaved poorly during
the incident and needs hardening.>

## Coordinated Disclosure Notes

<Who was notified in advance of a public fix/announcement, through what
channel, and when. Note anyone who should have been notified but was missed.
If a private security-advisory process was used
(see `SECURITY.md` once it exists), record the advisory ID/link here once
published.>

## Reproduction

<If applicable: a runnable reproduction — a state test, integration test, or
exact CLI/RPC sequence — that permanently regression-tests this exact bug. A
postmortem that includes a runnable reproduction doubles as a permanent test
specification, not just a narrative.>

```json
// paste a reproducing test fixture here, if one exists
```

## References

- <Links to the PR/commit containing the fix>
- <Links to any related GitHub Security Advisory>
- <Links to any external report that triggered this investigation>
