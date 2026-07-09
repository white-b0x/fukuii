# Batch Research Protocol

The multi-pass research methodology that must run **before** a `.claude/sprints/QUEUE.md`
batch's kickoff prompt is trusted — whether it's being drafted for the first time (per
`sprint-lifecycle.md` Rule 2) or being retroactively checked before its implementation starts.
This is what gives Rule 2's "prompts are drafted at the start of the batch" actual teeth: a
rule about *when* to draft is not a rule about *how thoroughly*, and this protocol is the *how*.

Used by: the `scout` subagent (`.claude/agents/scout.md`), the `fukuii-sprint-research` skill.
Referenced by: `sprint-lifecycle.md` (Rule 2/3), `finding-resolution.md` (the disposition every
finding this protocol surfaces must get), CLAUDE.md's protocol table.

---

## Why this exists

Batch 1 (opaque-type migration, July 2026) grew from ~9 originally-scoped prompts into ~25
commits / 11 major items over several days. Reconstructed root causes, each mapped to a rule
below:

1. Research (`opaque-type-gap.md`) grepped `src/main` only — missed ~248 test-source leakage
   sites, which became the single biggest unplanned item (IP-14, 105 files). → Rule (a).
2. A single grep pass under/over-counted per type; only a second, differently-patterned "deep
   detail" audit caught the real numbers — one pass was not sufficient evidence of
   completeness. → Rule (b).
3. Half-typed sibling fields in the same DTO (one field converted, the adjacent field left raw)
   were invisible to textual grep — needed reading full file context, not just matched
   lines. → Rule (c).
4. A prior regression precedent ("convert the whole file-family in one commit, never
   partially") wasn't known when a partial fix was originally scoped, forcing expensive
   mid-sprint re-scoping. → Rule (d).
5. An unrelated, pre-existing test-source compile baseline (~100 errors, nothing to do with the
   sprint) went undiscovered until deep into the sprint, then blocked
   `testOnly`/`testEssential` verification queue-wide. → Rule (e).

A finding this protocol surfaces is an audit finding under `finding-resolution.md`, not an
incidental find under `inline-cleanup.md` — the whole point of running it is to find issues, so
every finding gets one of `finding-resolution.md`'s three dispositions immediately, in the same
pass, never left as a bare "flagged, TBD."

---

## Rule (a): Multi-tree sweep, never main-source only

Every discovery pass uses `scripts/agent-tooling/lib/site-sweep.sh --scope all` (the default —
searches `src/main/`, `src/test/`, and `src/it/` concurrently), not a hand-rolled grep scoped to
one tree. Narrowing to `--scope main` is a deliberate, stated choice for a batch that is
genuinely main-source-only (e.g. a change to a file with no test-source callers at all) — not
the default assumption.

**For a batch with no `src/` surface at all (CI workflows, docs, config — e.g. REPO-\*
items)**, `site-sweep.sh` doesn't apply — there's no `--scope docs`/`--scope github` mode, and
there shouldn't be one bolted on just to force-fit this rule. State the substitution
explicitly instead of narrating a `--scope main` justification that doesn't fit either: the
sweep is a direct `grep`/`ls`/`gh api` pass over the relevant non-Scala tree (`.github/`,
`docs/`, repo root), and it still needs the same rigor — don't skip the multi-pass discipline
in Rule (b) just because the named tool doesn't apply.

## Rule (b): A second, differently-patterned pass is required before trusting "clean"

A single grep pass is not sufficient evidence of completeness. Run the sweep a second time with
a broadened or differently-worded pattern set (synonyms, partial matches, adjacent field/method
names) before declaring a type or pattern family "clean." If the two passes disagree, the
second pass's (broader) count wins, and the disagreement itself is worth noting in the drafted
prompt's CONTEXT — it's a signal the pattern family is more slippery than a single grep can
capture.

**A "dead code, zero references" claim is a specific case of this rule, and grep alone cannot
close it.** A symbol consumed only through an invisible `using` clause has zero textual
references and is not dead — see `dead-code-review.md`'s own incident writeup (Batch 1.5,
2026-07-05: a grep-confirmed "dead" `given` turned out to be required via an invisible `using
BlockchainConfig` parameter, only caught when the implementing agent actually attempted the
deletion and hit a compile error). When a KNOWN FILE LIST entry states something is dead code,
either the research pass has already attempted removal + compile itself, or the entry is
phrased as "grep shows zero references — verify by removal+compile" rather than "confirmed
dead" — never state the stronger claim on grep evidence alone.

**When fixing a known bug pattern across a set of known-affected files, also sweep for the same
pattern shape across every structurally-similar file in the codebase, not just the ones already
flagged.** A partial rollout of a fix is a live bug waiting for an unlucky trigger — Batch 1.5
found `ChainId.scala`/`Timestamp.scala` still had a self-referential `given Ordering[X]`
deadlock that 10 sibling opaque BigInt/Long-backed types had already been fixed for during
Batch 1's migration; nothing had swept for every instance of the *pattern*, only the specific
types a straggler audit happened to touch. See `scala3-style.md` S12 for the resulting
grep-verifiable ratchet — run it (or its equivalent for whatever pattern is in play) as part of
this rule's "second pass," not just a narrower re-check of the original known sites.

**For docs/config batches, the same "broadened pass" idea applies to copy-pasted claims, not
just code patterns.** REPO-01's research (2026-07-05) found a false security claim ("SLSA
Level 3 provenance," "CodeQL static analysis on every push," both untrue) repeated across 11
files — prior research had caught only 2 of them by checking the files it happened to cite. A
narrow reading of Rule (b) ("re-verify in its original file") would have missed the other 9.
When a doc/config batch finds a specific claim is wrong in one file, grep the *exact phrase*
across the entire doc tree before treating the fix as scoped to that one file — copy-paste
drift is the docs-world equivalent of a partial code rollout.

## Rule (c): Semantic sibling-field check

For every match a sweep finds, read the surrounding ~10-20 lines of the actual file — not just
the matched line — to check for half-typed adjacent fields or parameters in the same
class/DTO/case class that the pattern didn't catch textually (e.g. one field already converted
to an opaque type, the field next to it still raw). A mechanical grep only ever proves "this
exact pattern exists here," not "this file is fully consistent." The same principle applies
to a doc/config match: read the surrounding section, not just the matched line, to check
whether adjacent claims in that same section are stale or inconsistent with what you're about
to change (this is exactly how REPO-01's research found the SLSA/CodeQL claims sitting next to
what would otherwise have been an isolated, correct fix).

## Rule (d): Precedent and regression lookup

Before finalizing scope, grep `.claude/sprints/log/INDEX.md` and the current
`.claude/sprints/QUEUE.md`'s Chase & Deferred Items for any standing precedent that would
expand a partial fix into a full-family fix (e.g. an "all files touching this wire format land
in one commit" rule born from a past regression). If one applies, state it explicitly in the
drafted prompt's CONTEXT — don't let a future implementer discover the constraint mid-batch the
way it was discovered the first time.

**Also check whether the finding is already being worked on, not just whether it's already
happened.** `sprints/log/INDEX.md` only records *closed* work — it says nothing about an
open PR already fixing the exact thing you're about to draft a prompt for. Before finalizing
any finding as new work, run `gh pr list --state all --search "<relevant keywords>"` (or `git
log --all --grep` for a merged-but-unlinked fix) against the actual GitHub repo, not just the
local sprint tracker. **Incident (REPO-01, 2026-07-05):** a real CI failure (`Documentation
Preview`/`Check Documentation Links`) was found during a pre-flight health check and nearly
logged as a new, unaddressed Chase item — it was already root-caused and fixed in an open PR
(discovered only because the operator asked "did you check the PRs/commit log" after the
finding was drafted, not because the research pass checked first). Treat "is this already
being fixed elsewhere" as a mandatory check alongside "has this happened before," not an
afterthought triggered by someone else noticing the gap.

**`gh pr list`/`git log --all --grep` only find work that was pushed as a PR or landed as a
commit — also run `git branch -a` (and, if relevant, `git log --all --grep` against *branch
names*, not just commit messages) to catch in-progress or abandoned work sitting on an
unmerged branch that was never opened as a PR at all.** This is not a redundant restatement of
the PR/commit check above — it is a genuinely different search surface with different blind
spots, and a real, unmerged fix can exist on the canonical remote without ever surfacing in
either of the other two checks. **Incident (REPO-07, 2026-07-05):** `gh pr list --state all
--search` for every relevant keyword came back clean, but `git branch -a` surfaced
`upstream/fix/hive-sync-enode` — an unmerged, un-PR'd branch, one day old, already containing a
better fix for the exact bug being drafted (`enode.sh`'s placeholder anti-pattern), plus a
prerequisite bug (a missing JSON-RPC namespace) the research hadn't otherwise found. When a
matching branch is found, the disposition of *its other, unrelated content* (this incident's
branch bundled ~30 additional files outside the fix being cherry-picked) is not this rule's
job to resolve — surface it as its own scheduled finding per `finding-resolution.md` (an
operator decision: finish it, PR it, or discard it), not something to fold silently into
whatever prompt is being drafted. If the matching branch's content looks like it could be
directly ported or emulated rather than re-derived from scratch, say so explicitly in the
drafted prompt — re-deriving a fix that already exists, tested or not, is wasted work Rule (d)
exists specifically to prevent.

## Rule (e): Pre-flight baseline health check

Before drafting, confirm the target area is currently healthy, independent of the planned
change. For Scala/sbt-built code: a backgrounded `sbt-run.sh compile-all` (per
`background-script-execution.md` — never foreground) plus a representative `testOnly` sample
from the target file list. For non-Scala batches (docs, CI config, scripts): the equivalent
health signal — does the current CI pass, does `mkdocs build --strict` succeed, does the
script's own smoke test pass. **For batches touching CI/security/dependency tooling
specifically, check live state, not just that workflow files parse** — `gh api`/`gh run list`
against the actual repo (Dependency Graph contents, whether private vulnerability reporting is
already on, recent workflow run results) surfaces facts no static file read can (REPO-01's
research directly verified the Dependency Graph had zero JVM packages, and that vulnerability
reporting was already enabled, both of which changed what the drafted prompt needed to say).
If the baseline is already broken, that pre-existing breakage
becomes its own finding (per `finding-resolution.md`) and gets called out explicitly in the new
batch's CONTEXT, so implementers aren't confused later about whose failure it is.

## Rule (f): Cascade / follow-up anticipation

Before finalizing the drafted prompt, explicitly answer: "if this batch's change lands, what is
the next-order effect?" — a shared test fixture used by specs outside this batch's file list, a
consensus-adjacent file needing forge/beacon review, a wire-format/API field needing
conduit/versioning review, a new script or skill other protocols should cross-reference. Every
answer gets an immediate `finding-resolution.md` disposition (absorbed / new item / explicit
deferred entry) — this question is not rhetorical, its answers are findings.

## Rule (g): Cross-batch file-overlap check

Check `QUEUE.md`'s OTHER open/blocked batch sections for file overlap with the batch being
researched — not just historical precedent in `log/INDEX.md`. A cross-batch collision (two
batches independently planning to touch the same file, or a `BLOCKED-ON-BATCH-X` dependency
that's stated but not actually re-verified against the current file list) is exactly the class
of surprise Rule (d) catches for *past* incidents; this rule catches it for *concurrently
planned* work.

## Rule (h): Output contract

The research pass produces exactly two things:

1. A drafted, house-style kickoff prompt (CONTEXT / KNOWN FILE LIST / INSTRUCTIONS /
   CONSTRAINTS — matching the shape already used throughout `QUEUE.md`'s batches), with an
   explicit coverage/confidence note: which scopes were swept, how many independent pattern
   passes ran, and the resulting file/site count.
2. Any out-of-scope findings pre-filled into `QUEUE.md`'s Findings Resolution Log and/or Chase &
   Deferred Items, each with a disposition already chosen per `finding-resolution.md` — never a
   bare "flagged, not otherwise scheduled."

**`scout` holds no `Write` grant** — per-agent `Write` cannot be scoped to a subtree like
`.local/**` in current Claude Code (`tools:` grants are tool-name-only; see
`testing-protocol.md`'s "Permission-grant scope boundary" section), so `scout` stays fully
read-only rather than holding an unscoped grant it shouldn't use. `scout` returns the drafted
kickoff prompt (plus its coverage note and findings table) **inline, in full**, as its final
message — never a summary that omits the actual prompt text. **The orchestrator (the calling
session) persists that report to `.local/docs/research-july/<slug>.md` before dispatching any
implementation prompt built from it** — inline-only output that exists solely in the calling
session's transcript, with nothing durable written before dispatch, is exactly the failure mode
this rule closes (a vetted draft that existed nowhere durable). This is the same handoff-and-
persist pattern already used elsewhere in the sprint workflow (see `sprint-lifecycle.md` §8.4):
the read-only agent returns structured output, the orchestrator is the one durable-writing party.
`eye`/`prism` are the same shape — read-only, no `Write` — so the same orchestrator-persists rule
applies to their verdicts when worth keeping past the transcript (not universal — see
`finding-resolution.md`'s incidental-finds distinction).

## Rule (i): The "can't-fix" disposition requires a verified-no-alternative check

No agent may disposition a finding as can't-fix / unfixable / library-inherent /
genuine-boundary / `@nowarn`-candidate / suppress without first verifying no current or
typed alternative exists — check the dependency set (`project/Dependencies.scala`) AND the
authority repo under `.claude/repo-references/`, and cite that evidence in the finding
itself. Absent that check, the only valid disposition is "separate item, needs its own
scoping," never "unfixable."

**Incident (2026-07-08):** `scout` scoping B1 dispositioned ~165 test-side E165 sites as
"unfixable — library API, not our code." They were Classic Pekko TestKit usages
(`org.apache.pekko.testkit.*`, `TestActor.AutoPilot`, `TestProbe.ignoreMsg`) in 25 files,
while 115 sibling files already used the Typed TestKit
(`org.apache.pekko.actor.testkit.typed.*`) — already a project dependency
(`project/Dependencies.scala`, `pekko-actor-testkit-typed`). The correct disposition was
migration debt (a new scheduled IP per `finding-resolution.md` Rule 1), not "unfixable." No
alternative-check was ever run before the claim was made. This is the identical
accuracy-vs-authority failure `docs/development/coding-standards/README.md`'s VALIDATE gate
exists to catch for standards content — this rule applies that same check to a finding's
disposition, and that README's Governance section cross-references back here.

**The check is two-dimensional — verify both, not just one (sharpened 2026-07-09 after a
second incident of the same root cause):** before suppressing any warning at all
(`@nowarn`/`@SuppressWarnings`/`@unchecked`/disabling a test), confirm (1) **no newer version
of the same library types the call** — check `project/Dependencies.scala` for a current pin
and the authority repo under `.claude/repo-references/` for what that version's signature
actually is — AND (2) **no recommended, maintained typed alternative exists at the call site
itself** — a successor library, or a typed-idiom rewrite of the call site that avoids needing
the untyped signature at all. Checking only dimension (1) and stopping there is not
sufficient: `queue/nowarn-candidates.md`'s `KeyStoreImplSpec.scala` `matchPattern` entry was
first dispositioned "genuine-boundary CONFIRMED" on dimension (1) alone (ScalaTest 3.2.20's
`matchPattern(right: PartialFunction[Any, _]): Matcher[Any]` signature is real and
library-owned) without checking whether the call site could avoid that signature entirely —
it could, via a plain typed `match { case ... => succeed; case other => fail(...) }`. If
either dimension turns up an alternative, the disposition is a scheduled fix (a MOD or an
existing IP), never a suppression, regardless of the migration's size or risk.

**Outcome when this two-dimension gate is actually run end to end (Batch 4.5 Tier E,
2026-07-09, commit `ef0ddce14` and the commits it built on):** 36 E165 sites previously
dispositioned "library-forced, must suppress" were re-run through both dimensions. Every
single one resolved to a fix, a typed rewrite, or a scheduled modernization item (Pekko
Classic `Tcp`→Streams-`Tcp`, Sangria→Caliban, ScalaMock→typed test double,
`productIterator`→`Tuple.fromProductTyped`, the `matchPattern` rewrite above, and others) —
**zero** ended up genuinely unsuppressible. Treat that outcome as the expected result of
running this gate honestly, not an unusual one: a "can't-fix" verdict should be the rare
exception that survives both checks, not the default when a fix looks large or gated.

## Who runs this, and what it does not cover

The `scout` subagent (`Read`, `Grep`, `Glob`, `Bash` — no `Edit`, no `Write` of any kind) runs
this protocol end to end for a given batch/topic, dispatched via the `fukuii-sprint-research`
skill. `scout` does not edit `QUEUE.md` itself — the calling session reviews its output (the
skill shows an actual diff/preview) before applying the edit, the same handoff shape already
used for `eye`'s and `prism`'s read-only review output.

This protocol does **not** cover: implementation (that's the batch's assigned specialist —
mithril, forge, beacon, warden, etc.), compile/test loops beyond the single pre-flight check in
Rule (e) (routine compile/test cadence during implementation is `testing-protocol.md`'s
territory), or QUEUE.md's own clear/archive mechanics (that's `sprint-lifecycle.md` Rule 5 and
the `fukuii-sprint-queue` skill).
