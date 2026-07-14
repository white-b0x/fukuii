# Dependency-currency protocol

<!-- currency: this protocol governs itself; see "Scope" below for what it does and does not cover -->

This protocol keeps the **prescriptive content** of fukuii's agentic tooling — the coding
patterns taught in `.agents/protocols/*.md`, `docs/research/best-practices/*` (excluding
`archive/`), and `AGENTS.md`/`CLAUDE.md` — honest about which version of Scala/Pekko it
describes and whether it still reads as idiomatic practice for that version. This is a
sibling concern to `.agents/skills/fukuii-dependency-audit/SKILL.md`, not a duplicate of it:

- `fukuii-dependency-audit` verifies **what version we're on** — pinned versions vs.
  endoflife.date, `sbt dependencyUpdates`, BSL-incompatible import scanning. Mechanical,
  grep/tool-verifiable.
- This protocol verifies **whether our documentation about writing code for that version is
  itself accurate** — a judgment call about whether a paragraph reads as current Pekko 1.6+
  Typed idiom or stale Akka-Classic-era thinking. Prose comprehension, not grep-gateable.

Run this deliberately, immediately after `fukuii-dependency-audit` reports a pinned-version
bump (the moment content is most likely to have drifted from new idioms), or every ~6 months
of no version change — whichever comes first. This is guidance, not a hard mechanical gate;
do not try to force this into the `.claude/looping/` DISCOVER→PLAN→EXECUTE→VERIFY harness —
that subsystem is for grep/compile/test-gateable recipes (`sprint-lifecycle.md` Rule 6), and
judging prose currency doesn't fit that shape.

## Current pinned versions — derive, don't hardcode

Never copy a version number into this protocol or any governed file as a bare string — it
will go stale the moment the pin changes and nobody will notice. Always re-derive:

```bash
grep -E 'scala-3|pekkoVersion' build.sbt project/Dependencies.scala
```

## The Akka-Classic smell-list

Lifted directly from `.agents/protocols/code-style/pekko-typed-api.md`'s own P1/P2/P3/P9 "avoid"
columns — that file already correctly encodes what's stale, so this protocol just points
the same yardstick at every *other* doc. A governed file that presents any of these as
**current** practice (not explicitly flagged as pre-migration/historical reference) is a
finding:

- `context.become` for actor state transitions (Typed: two-behavior state machine, P6)
- Untyped `Receive` / `def receive` (Typed: `Behaviors.receive[Command]`)
- `sender()` (Typed: explicit `replyTo: ActorRef[Reply]` parameter, P3)
- `SupervisorStrategy` / `OneForOneStrategy` outside a `Behaviors.supervise` wrapper (P19/P20)
- `context.system.scheduler.scheduleOnce(...)` instead of `Behaviors.withTimers` (P1)
- Blocking `Await.result`/`Await.ready` on a `Future` inside an actor's message handling or
  any Pekko dispatcher thread (deadlocks the dispatcher under load — see the addendum in
  `pekko-typed-api.md` this protocol's first run should add if not already present)

## Currency sources per dependency

- **Scala 3**: official docs + the vendored `.claude/repo-references/clients/scala3/`
  changelog (`fukuii-dependency-audit` already checks this).
- **Pekko**: the vendored `.claude/repo-references/clients/pekko*/docs/src/main/paradox/`
  and its `release-notes/` — **not currently cross-referenced by any existing audit
  mechanism**; this protocol is what closes that gap. `fukuii-dependency-audit` should gain
  a matching step (see that skill's own TODO, added alongside this protocol):
  ```bash
  git -C "$REFS/pekko" pull --ff-only
  ls "$REFS/pekko/docs/src/main/paradox/release-notes/"
  ```

## Governed-file scope

- `.agents/protocols/*.md` (all of them)
- `docs/research/best-practices/**/*.md` — **excluding** `archive/` (historical sprint
  records are correctly not held to a currency bar — see
  `.claude/progress-tracking/`'s own precedent of gating forward-looking claims like
  "Scala 3.9 LTS — GATED, not yet available" rather than asserting them as current)
- `AGENTS.md`, `CLAUDE.md` themselves

## Procedure

1. Re-derive current pinned versions (command above).
2. For each governed file: read it, check every code pattern and version claim against the
   smell-list and the vendored changelogs above.
3. For anything found stale, disposition it via the existing `finding-resolution.md`
   discipline (fix now / schedule / defer with reason) — never left as a bare
   flagged-but-unscheduled note.
4. Stamp the file with the header convention below, whether or not anything changed — a
   clean pass is itself a result worth recording.

## Header convention

First line after the H1 title of every governed file, once reviewed:

```
<!-- currency: verified idiomatic for Scala 3.3.8 LTS / Pekko 1.6.0 — 2026-07-03 -->
```

Grep-discoverable (`grep -rn "currency:" .agents/protocols/`) and diffable in review — a PR
that bumps the pinned Pekko version while touching zero `currency:` lines is a visible
signal the docs weren't revisited.

`.agents/protocols/code-style/pekko-typed-api.md` and `.agents/protocols/code-style/scala3-style.md` are the two
files already verified accurate as of this protocol's authoring (2026-07-03) — apply the
header to them first, as the template for what "verified current" looks like, before
working through the rest of the governed-file list.
