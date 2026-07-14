---
name: pekko-resource-audit
description: >-
  Audit fukuii's Scala/Pekko actor and stream code for resource leaks — uncancelled
  timers, missing watchWith/Terminated cleanup, stream materialization leaks
  (preMaterialize anti-pattern), and dispatcher starvation from blocking Futures. Use
  when asked to "audit for actor leaks", "check for resource leaks", "find dispatcher
  starvation", or when reviewing a PR that touches Behaviors.withTimers, context.watch,
  stream materialization, or Future/Await usage inside actor code. Does NOT touch
  RocksDB/storage lifecycle (see storage-rocksdb.md) or consensus code (use forge/beacon).
---

# Pekko resource audit

Ported from Nethermind's `resource-leak-audit` skill
(`.claude/repo-references/clients/nethermind/.agents/skills/resource-leak-audit/SKILL.md`),
adapted from C#/.NET `IDisposable`/CTS lifecycle categories to fukuii's actual Pekko
resource categories. Content on *what the current idiom is* already lives in
`.agents/protocols/code-style/pekko-typed-api.md` (P1, P9, P12, P26) — this skill is the audit
*methodology* that finds violations of those idioms, scattered rather than duplicated.

## Mode selection

- **PR mode (default)**: `git diff origin/develop...HEAD --name-only`, filter to
  non-test `*.scala` under `src/main/scala/**/blockchain/`, `**/network/`,
  `**/consensus/` actor code and anything under `**/*Actor.scala`/`**/*Coordinator.scala`.
- **Full audit mode**: all non-test actor/stream code (`grep -rl "extends AbstractBehavior\|Behaviors\.\(setup\|receive\)" src/main/`).

## Categories (map directly to existing protocol entries — read them first)

1. **Uncancelled timers** (`pekko-typed-api.md` P1) — `Behaviors.withTimers` handle not
   cancelled on the actor's terminal transition, or a raw `scheduler.scheduleOnce`/
   `scheduleAtFixedRate` with no matching `Cancellable.cancel()` anywhere.
2. **Missing watch cleanup** (P9) — `context.watch`/`watchWith` registered but never
   unwatched when the watched relationship ends before the watcher does (e.g. a
   short-lived worker watched by a long-lived coordinator that never calls
   `context.unwatch` after the worker's expected lifecycle ends normally, not just via
   `Terminated`).
3. **Stream materialization leaks** (P12) — `preMaterialize()` called but the returned
   `(Source, Future[...])`'s materialized-value `Future` never observed/cancelled, or a
   `Source`/`Flow` built inside a loop or per-request instead of once and reused —
   creates one live stream per invocation with no corresponding `.shutdown()`/kill-switch.
4. **Dispatcher starvation** (P26) — `Await.result`/`Await.ready` on a `Future` inside
   actor message handling or any code that runs on a Pekko dispatcher thread, outside
   `src/test/`/`src/it/`.
5. **Actor mailbox/child leaks** — a coordinator that `context.spawn`s per-request
   children with no corresponding stop (neither self-stop on completion nor
   `context.stop` from the parent) — check whether the child behavior actually
   terminates itself (`Behaviors.stopped`) on its final message.

## Methodology — two-phase, gated

### Phase 1: exhaustive search (breadth-first, don't validate yet)

For each category:
1. **Forward search** — grep for the resource's creation (`Behaviors.withTimers`,
   `context.watch`, `.preMaterialize()`, `Await.result`, `context.spawn`) and follow each
   hit forward to check for the matching cleanup.
2. **Check every match, not a sample.** Report "N total matches, M confirmed findings,
   (N-M) verified clean."
3. **For every candidate**, read the actor's full lifecycle: does a `PostStop`/
   `PreRestart` signal handler exist and does it perform the cleanup? Is cleanup only on
   the happy path, missing on an error/crash path?
4. **Impact assessment (mandatory before recording)**: what actually accumulates (timer
   objects, watched-actor registrations, live stream graphs, blocked dispatcher threads)?
   How fast, under what real traffic pattern (per-peer, per-block, per-request)? Is the
   path actually reachable, or gated behind a config flag / rare error branch?
5. **Mandatory sibling expansion**: after a confirmed finding, grep for the same
   structural pattern across all actors — a missing-cleanup bug in one coordinator often
   repeats in siblings built from the same template.
6. Stop only when all 5 categories are covered and a final reflection pass produces no
   new pattern.

### Phase 2: validation (CRITICAL/HIGH findings only)

For each CRITICAL/HIGH candidate:
- **Triggerability**: trace the actual call path from a real external event (peer
  message, RPC call, block import) to the leak point — not "could theoretically happen."
- **Accumulation rate**: quantify (timers/hour, watched-refs/peer-churn-event,
  live-streams/sync-cycle, blocked-threads/request-burst).
- **Existing work check**: `git log --oneline -10 -- <file>` and check `.claude/sprints/log/`
  for a prior fix to the same pattern.
- **Test strategy**: what test would fail before the fix and pass after (e.g. an
  `ActorTestKit` spec asserting `Cancellable.isCancelled` post-stop, or a
  `LoggingTestKit`-based assertion the actor logs a clean shutdown).

MEDIUM/LOW findings: present with impact assessments, ask the user whether to run deep
validation before spending more time — same policy as `finding-resolution.md`'s
disposition discipline (fix now / schedule / defer with reason, never left unscheduled).

## Self-critique (every finding)

1. Does anything actually accumulate, or does the actor's own supervision/restart
   already clean it up (a restarted actor gets a fresh `Behaviors.setup`, discarding old
   timer refs — is that actually a leak, or just expected restart semantics)?
2. Is the dispatcher-starvation path actually reachable outside a rare/gated branch?
3. Is this a real leak or just a style deviation from `pekko-typed-api.md` with zero
   quantified runtime impact — if so, classify COSMETIC and say why.

## Output format

```
### Finding [N]: [short title]
- **File**: `src/main/scala/.../Actor.scala`
- **Line(s)**: [line numbers]
- **Category**: [1-5 above]
- **What leaks**: [precise description]
- **Severity**: CRITICAL | HIGH | MEDIUM | LOW | COSMETIC
- **Frequency**: [per-peer / per-block / per-request / once / rare-error-path]
- **Impact**: [quantified, or "zero — restart semantics already clean this up"]
- **Fix complexity**: SIMPLE (1-5 lines) | MEDIUM (10-30 lines) | HARD (refactor)
```

Final output: deduplicated findings list, triage by reachability, one-line test-plan
per TESTABLE finding, and a COSMETIC section (practice violated + why impact is zero).

## Rules

- Non-test code only (`src/main/`) — this audits production paths, not test fixtures.
- Read actual code — a grep hit is a candidate, not a finding.
- Config-gated is not dead code — report with the config dependency noted.
- Restart-then-fresh-state via `Behaviors.setup` is not automatically a leak — check
  whether the *old* resource (timer, watch registration, stream) was actually released
  before the restart, not just superseded by a new one.
- Consensus-critical code needs `forge`/`beacon` review in addition to this audit if a
  fix touches `consensus/`, `vm/`, `crypto/`, or `domain/` — this skill covers resource
  hygiene, not consensus correctness.
