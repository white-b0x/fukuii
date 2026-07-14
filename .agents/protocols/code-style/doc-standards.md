# Doc Standards Protocol

Durable files — agent charters (`.claude/agents/*.md`), protocol docs (`.agents/protocols/`),
and subsystem breadcrumbs (`**/AGENTS.md`) — carry role/charter content, **invariants**, and
**single-source pointers**. They never carry status snapshots, live counts, "IN PROGRESS"
tables, or dated grep/compile results. Live, time-varying state belongs in
`.claude/sprints/QUEUE.md` (sprint/batch work) or the one authoritative subsystem doc for that
fact (e.g. `blockchain/sync/AGENTS.md` § Actor migration status for actor-migration state,
`.local/docs/test-quality-log.md` for the current `testEssential` test-count baseline).

Used by: ALL agents writing or editing `.claude/agents/*.md`, `.agents/protocols/**`, or any
`**/AGENTS.md` breadcrumb.
Referenced by: `fukuii/CLAUDE.md`.

## Origin

`DOC-HYGIENE-SWEEP-01` (2026-07-07) updated stale Pekko-migration status embedded in 5
`.claude/agents/*.md` files from stale to current. That fix was itself a treadmill: rewriting
"in progress" to "complete" only resets the staleness clock — the next actor migration, warning
sweep, or count-based claim added the same way will go stale again the same way.
`DOC-FUTUREPROOF-01` (2026-07-07) is the follow-up that removed the pattern instead of
re-dating it, and this protocol is where that principle is codified so future doc work
defaults to the durable shape instead of re-deriving it.

## The anti-pattern

A durable file asserting any of:

- A live count that a future commit can silently invalidate: test counts, warning counts,
  compile-error counts, file counts, "N sites use pattern X."
- A progress table with per-item status (`✅ DONE` / `🔄 IN PROGRESS` / `⬜ NEXT`) for
  ongoing work.
- A dated grep/compile result presented as an ongoing fact ("repo-wide grep, 2026-07-05,
  confirms 0 hits") rather than a command the reader can re-run.
- "As of \<date\>" or "currently N" phrasing attached to something that is expected to keep
  changing after that date.

Each of these is true only at the moment it was written. The next unrelated change (a new test
added, a new actor migrated, a new citation comment written) makes it false, silently, with no
diff to the file that asserted it.

## What this is NOT (don't over-apply)

These forms are legitimate and should not be "fixed" by this protocol:

- **Historical incident/postmortem records** — "Batch 1.5 (2026-07-05): prism's BATCH-1-CLOSE
  review stated X, root cause was Y" describes a bounded, completed past event. It does not
  assert anything about the *current* state of the codebase, so it cannot go stale the way a
  live count does. `batch-research-protocol.md`'s "Why this exists" section and
  `pekko-typed-api.md`'s "Test-kit pitfalls (discovered §8a-retro batch 4, 2026-06-23)" are
  examples.
- **`currency:` header dating** — `dependency-currency.md`'s convention
  (`<!-- currency: verified idiomatic for Scala 3.3.8 LTS / Pekko 1.6.0 — 2026-07-03 -->`) is a
  deliberate, separately-governed mechanism for tracking when a doc's *prescriptive coding
  pattern* content was last checked against the current Scala/Pekko version — a different axis
  from this protocol, with its own re-verification trigger. Don't strip it.
- **Templates and format examples** — `migration-handoff.md`'s continuation-file template
  (`"3,621 / 0 — verified after Phase 0 commit <sha>"`) and `loop-handoff.md`'s diffstat example
  (`"3 files changed, 42 insertions..."`) illustrate the *shape* a live report should take; they
  are filled in fresh by whoever writes the actual report, not asserted as fukuii's current
  state by the protocol doc itself.
- **Provenance/origin dates** — "Origin: operator design decision, 2026-07-07" or "Found by:
  Batch 1.5 (2026-07-05)" record *when a durable rule was adopted or a bug was discovered*.
  These never go stale — the decision was made on that date regardless of what the codebase
  looks like later. This is different from asserting the codebase's current compliance state.
- **Breadcrumb-currency markers on directory listings** — `<!-- breadcrumb-currency:
  directory/file listing verified against source tree ... -->` on subsystem `AGENTS.md` files
  states its own re-verify trigger ("re-verify when subpackages are added/removed/renamed")
  rather than a bare date stamp — this is the correct shape for a doc's self-maintenance
  signal, not the anti-pattern.

## The rule

Before adding or leaving in place any status/count/date claim in a durable file, ask:

1. **Will this specific claim go silently false the next time someone does ordinary, unrelated
   work** (adds a test, migrates an actor, fixes a warning)? If yes, it's the anti-pattern —
   convert it.
2. **Is there already one authoritative live source for this fact** (QUEUE.md, a subsystem
   AGENTS.md, `.local/docs/test-quality-log.md`)? If yes, point to it instead of asserting the
   number here.
3. **Is the fact itself durable** (a completed one-time migration count, a git-commit
   reference, a design decision's adoption date)? If yes, it's not the anti-pattern — leave it.
4. **Is the file a template showing report shape**, not asserting fukuii's actual state? If
   yes, it's not the anti-pattern — leave it.

## Where live state belongs

| Kind of live state | Authoritative home |
|---|---|
| Sprint/batch work in flight, findings, dispositions | `.claude/sprints/QUEUE.md` |
| Per-subsystem authoritative status (e.g. actor migration) | The one canonical subsystem `AGENTS.md` for that subsystem |
| Test-count baselines | `.local/docs/test-quality-log.md` |
| Commit-level historical detail (which actor moved in which commit) | `.claude/sprints/log/` |
| Prescriptive-content idiom currency (is this doc's coding guidance still current for the pinned Scala/Pekko version) | `dependency-currency.md`'s `currency:` header convention |

## Checklist before finalizing a durable-doc edit

- Re-read every status/count/date sentence you are about to write or leave in place against
  the rule above.
- If converting a snapshot to a pointer, confirm the pointer target actually exists and
  documents the fact (Chesterton's Fence — don't point at a target you haven't verified).
- If a count is genuinely load-bearing as a regression-detection threshold (e.g. "don't let the
  test count silently drop"), phrase the *mechanism* ("compare against the last recorded
  baseline") as the durable content — never the number itself.
