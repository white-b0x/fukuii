# Systemic Review Protocol

The recurring, durable methodology for a bird's-eye, module-by-module comparison of fukuii
against its 6 vendored reference clients (go-ethereum, Besu, erigon, reth, core-geth,
nethermind) plus Scala 3 / Pekko Typed best practice — the practice that answers, per
subsystem: is this sound and just needs polish, does it need a structural rewrite to match
reference-client best practice, is there a performance edge fukuii could claim over every
reference client, is this a genuine fukuii differentiator worth leaning into, and is there a
security-automation gap here. It exists because narrow, symptom-by-symptom patching (e.g. the
~15+ separate SNAP/fast-sync PRs against the same handful of files) accumulates faster than a
"measure twice" structural pass would have prevented — fukuii has no production users yet,
which is exactly the window to do this properly instead of continuing to patch forever.

Used by: any agent dispatched against an `SR-NN`/`SR-EXT-NN` item in
`.claude/sprints/QUEUE.md`'s "Systemic Review" persistent section. Referenced by:
`sprint-lifecycle.md` (this is a Persistent Section, not a Batch — never fully closes),
`finding-resolution.md` (every finding this protocol surfaces gets one of its three
dispositions), `dead-code-review.md` (its WIRE/DELETE/DEFER taxonomy is reused directly, not
reinvented here), CLAUDE.md's protocol table.

This protocol is cycle-agnostic — it does not name a specific month or output directory. Each
run ("cycle") gets its own dated output folder, e.g. `docs/research/systemic-review-july/` for
the July 2026 cycle; that folder's own `00-methodology.md` is a short pointer back to this file
plus whatever is specific to that cycle (which items are gated on other in-flight work, which
QUEUE.md items it supersedes). Do not duplicate this protocol's taxonomies into a cycle folder —
cite this file.

---

## Cadence

Run a full cycle **monthly**, or ad hoc whenever a large structural refactor is about to be
proposed for a subsystem this protocol hasn't reviewed recently — the whole point is that the
codebase's self-knowledge (what's sound, what's dated, what's a gap) stays current rather than
going stale the way `docs/historical/`'s pre-Orbita architecture docs did.

**Recurring-cycle efficiency rule:** a second or later cycle does **not** start from zero. It
starts by diffing against the prior cycle's `01-findings-index.md` — re-verify what changed in
the intervening period (new commits, new dependencies, new fork activations), and only
re-research areas that actually moved. Treat an unchanged area's prior-cycle findings as still
valid evidence, cited, not re-derived. This is what makes "monthly" tractable instead of a full
from-scratch effort every time.

---

## Execution model — the core rule

**Parallel WITHIN one subsystem item, sequential ACROSS items.**

- When one `SR-NN`/`SR-EXT-NN` item is dispatched, the executing thread **must** fan out into
  parallel sub-agents — at minimum one per review lens (structural-comparison, Scala3/Pekko-
  currency, test-quality, dead-code, opportunities). For the structural-comparison lens
  specifically, dispatch one sub-agent per reference client (or a small client group, e.g.
  Besu+Erigon+Reth together if the subsystem's surface there is thin) rather than one agent
  serially skimming all 6 — every reference client's actual source must get read in full for
  the areas being compared, not sampled. A single serial pass by one agent does **not** satisfy
  this protocol for a Tier A (large) subsystem — see the per-subsystem template below for the
  Tier A/B distinction.
- Only **one** `SR-NN`/`SR-EXT-NN` item runs at a time. Do not dispatch multiple subsystems'
  agent fleets concurrently. Two reasons: resource bound (read-heavy research agents are safe
  in parallel per `resource-management.md`, but many large concurrent research contexts against
  the same repo tree and the same vendored reference-client checkouts is still worth avoiding on
  this hardware), and quality control (one subsystem's findings get synthesized and reviewed
  before the next subsystem starts, so a bad synthesis pattern doesn't propagate across 14
  items before anyone notices).
- The dispatching thread (main session, or — if explicitly requested — a `Workflow` pipeline)
  owns synthesizing the parallel lens outputs into the per-subsystem doc set. No lens's raw
  output ships un-synthesized into the findings docs.
- "Flip every rock": every file named in an item's KNOWN FILE LIST gets read in full by at
  least one lens's sub-agent. A dead-code claim, a "no test exists for X" claim, or a "these 6
  clients converge on a different design" claim must each be traceable to a specific read, not
  a grep count alone (see the dead-code taxonomy below for the concrete failure mode this
  guards against).

---

## Verdict taxonomy

Tags, not mutually exclusive — a single finding may carry more than one (e.g. a finding can be
both `NEEDS-REWRITE` and `FUKUII-DIFFERENTIATOR` if the rewrite is what would *unlock* the
differentiator).

| Verdict | Definition | Bar to apply it |
|---|---|---|
| `POLISH-ONLY` | Design is sound in principle; the issue is implementation-quality, not architecture | Fixing it does not change any public/actor-message/wire-format contract |
| `NEEDS-REWRITE` | Structural misalignment with reference-client best practice severe enough that incremental patching will keep recurring | At least 2 of the 6 reference clients converge on a materially different structural approach **AND** the affected file(s) have ≥2 prior narrow-patch commits in recent history |
| `PERFORMANCE-OPPORTUNITY` | A change here could make fukuii measurably faster/leaner than ALL 6 reference clients, not merely on par | Must name the specific mechanism (avoided allocation, avoided round-trip, avoided lock, etc.) and which reference client currently does it best — falsifiable, not vague |
| `FUKUII-DIFFERENTIATOR` | A place where Scala 3 / Pekko Typed / fukuii's dependency choices enable something none of the 6 reference clients can cleanly express in their own language/framework | Must name why the other 5 languages/frameworks structurally cannot match the idiom as cleanly — not just "we happen to use Scala" |
| `SECURITY-AUTOMATION-GAP` | Missing automated CVE scanning / agentic review coverage for this subsystem specifically | Must name the concrete missing automation (tool, CI hook, agentic pipeline stage) — not just "could be more secure" |

## Test-classification taxonomy

| Classification | Definition | Distinguishing question |
|---|---|---|
| `MODERNIZE` | Test is well-written, assertions still correct, but uses a dated harness/idiom | Would a mechanical idiom swap (no logic change) bring it current? |
| `REWRITE` | Test's assertions/structure are poor even by the standards of when it was written | Does fixing it require re-deriving what it should assert, not just modernizing syntax? |
| `DELETE` | Redundant with another test, tests dead/superseded code, or asserts nothing meaningful | Does removing it lose zero real coverage? (Chesterton's Fence — check git blame/history first) |
| `COVERAGE-GAP` | No test exists for a behavior that should have one | Name the specific untested scenario; cite a reference-client test that covers the equivalent behavior if one exists |

## Dead/bad-code taxonomy — reused, not reinvented

This protocol's "bad code / dead code" findings use `dead-code-review.md`'s existing three
verdicts directly: **WIRE** (should be connected, not deleted) / **DELETE** / **DEFER**. Every
dead-code candidate must run through that protocol's assessment questions before a verdict is
assigned. A bare zero-callers grep result is evidence, not proof — attempt a scratch
removal-plus-compile check for any strong dead-code claim, or explicitly phrase the finding as
"grep shows zero references — verify by removal+compile" if that check was not performed.

## Reference-client authority model

| Client | Authority role | Notes |
|---|---|---|
| **go-ethereum** | Primary ETH/EIP authority | Default tie-breaker when ETH-side clients disagree |
| **Besu** | Independent ETH cross-check **and** architectural mirror | See "Authority vs. architectural mirror" below — Besu's dual role is deliberate, not a table-grouping accident |
| **Erigon**, **Reth** | Independent ETH cross-checks | Disagreement among these + geth is a signal to investigate, not a majority vote to auto-resolve |
| **Nethermind** | Independent ETH cross-check (same tier as Besu/Erigon/Reth) | Carries its own ETC overlay on `main` in the vendored fork — check `main`, not just `upstream`, when a finding is ETC-relevant and Nethermind's ETC behavior specifically matters |
| **core-geth** | ECIP/ETC-ONLY authority | **NEVER** cite as ETH-authoritative, even where its code visibly still contains ETH-mainline logic inherited from its multi-geth lineage — a concrete instance of this trap: core-geth's SNAP-sync code (`eth/protocols/snap/`, `eth/handler_snap.go`) is inherited verbatim from go-ethereum, not independently ECIP-derived, so it must never be cited as justifying an ETC-specific SNAP design choice |

**Branch-checkout caveat:** besu/nethermind/core-geth carry a real ETC overlay on `main` (their
resting branch per `.claude/agents/REFERENCES.md`); go-ethereum/reth/erigon currently rest on
`upstream` (no overlay written). Check which branch is actually checked out before trusting a
citation's network context.

### Authority vs. architectural mirror

The table above answers one question — **which client is correct?** (the right bytes/values:
EIP formula, ECIP emission schedule, gas cost, opcode behavior). It does not answer a second,
orthogonal question that any structural-comparison lens also needs: **which client's code is
structured closest to how fukuii's Scala should be structured?** These are different axes and
neither substitutes for the other:

- **Authority (correctness axis)** — go-ethereum for EIP behavior, core-geth for ECIP behavior.
  Cite these for "is this the right value."
- **Architectural mirror (structure axis)** — **Besu**. Both fukuii and Besu are JVM clients
  with object-structured protocol schedules and validator/builder factories (fukuii's Scala
  `object`-per-fork-schedule idiom has a direct structural analog in Besu's Java class-per-
  schedule/builder idiom) — the closest codebase kinship among the 6 vendored clients. Cite
  Besu for "how should this be shaped," independent of whether Besu's own values are the ones
  fukuii should copy.

A structural-comparison lens should run **both** consults, not just the authority one: confirm
correctness against the byte-authority, then separately check Besu for a transferable
dispatch/organization pattern. Neither consult is optional and neither is a replacement for the
other — a change can be byte-correct per go-ethereum and still be structured worse than Besu's
equivalent, or structured like Besu and still wrong per go-ethereum's values.

**Illustrative case (method, not a status line):** deciding whether a header is PoW or PoS-era
requires keying off `terminalTotalDifficulty` presence, then dispatching per-header on
`difficulty == 0`. All three clients converge on this: go-ethereum's `consensus/beacon`
`IsPoSHeader`, Besu's `TransitionProtocolSchedule` + `TransitionUtils.
dispatchFunctionAccordingToMergeState` (`difficulty == 0`) selected via
`TransitionBesuControllerBuilder` when `getTerminalTotalDifficulty().isPresent()`, and fukuii's
`TransitionBlockHeaderValidator`. Besu's `TransitionBesuControllerBuilder ⇄ TTD-keyed
ValidatorsExecutor` and `TransitionProtocolSchedule ⇄ TransitionBlockHeaderValidator` shape is
what made the corresponding fukuii structure obvious — go-ethereum confirmed the dispatch key
was correct, Besu confirmed how to organize the dispatch.

## Citation convention

`<client>/<path-from-repo-root>:<line>` relative to `.claude/repo-references/clients/<client>/`
(or the working copy at `/media/dev/2tb/dev/reference-clients-evm/<client>/`, whichever is
faster to grep — cross-check if a finding is ambiguous). fukuii citations relative to
`src/main/scala/com/chipprbots/ethereum/`. Any citation not directly verified against the
vendored clone — plausible but time-boxed, not confirmed — must be marked `VERIFY:` inline,
never presented as confirmed fact.

## Granularity ladder

Disambiguates "module → sub-module → sub-system → system → root" against this repo's actual
nesting:

| Term | Maps to | Example |
|---|---|---|
| root | whole repo | fukuii |
| system | top-level `src/main/scala/.../ethereum/<name>/` package | `blockchain/`, `jsonrpc/` |
| sub-system | one directory below system | `blockchain/sync/` |
| sub-module | one directory below sub-system | `blockchain/sync/snap/`, `blockchain/sync/snap/actors/` |
| module (finding-level) | individual file/class | `SNAPSyncController.scala` |

## Specialist dispatch table

| Area | Primary | Consult |
|---|---|---|
| `blockchain/sync` (all strategies) | `herald` (wire/protocol) + `flow` (Pekko Streams/actor) | `forge`/`beacon` for consensus-adjacent validation (state-root, pivot correctness) |
| `consensus/pow`, `consensus/mining` | `forge` | — |
| `consensus/pos` (PoS) | `beacon` | — |
| `consensus/eip1559`, `consensus/validators` (spans both families) | `forge` + `beacon` | — |
| `network/` | `herald` | — |
| `db/` | `vault` | — |
| `jsonrpc/` | `conduit` | — |
| Scala3/Pekko-currency lens, all subsystems | `mithril` (idiom) + `loom` (actor-migration specifics) | — |
| Test-quality lens, all subsystems | `eye` | — |
| Dead/bad-code lens, all subsystems | `prism` (non-consensus) | `forge`/`beacon` for consensus-adjacent dead code |
| Pre-flight/precedent lookup before any item starts | `scout` | — |

## Relationship to prior research — cross-reference, do not re-derive

Before drafting findings for any subsystem, check whether the finding is already covered by:
`docs/research/best-practices/codebase-audit.md` (June 2026 Scala3-idiom + actor-migration-
quality audit, S-numbered rule IDs), `docs/research/best-practices/evm-clients/*.md`
(anti-patterns/error-recovery/p2p/snap-sync synthesis docs), `docs/agentic-tooling/
reference-clients-gap-analysis-2026.md` (a comparison-table format precedent, different
subject matter), `.claude/progress-tracking/completed/CODEBASE-AUDIT.md` (process precedent:
parallel sub-agent sweep → numbered findings → per-finding resolution). If a finding restates
an existing S-rule or prior audit item, cite the ID instead of re-describing the pattern.

## Per-subsystem doc template

**Tier A** (full 6-doc split — subsystems with genuinely large, multi-sub-module scope):
`00-overview.md`, `01-structural-comparison.md`, `02-scala3-pekko-currency.md`,
`03-test-quality.md`, `04-dead-inefficient-code.md`, `05-opportunities.md`,
`06-verdicts-followups.md`. **Tier B** (one flat doc — small subsystems): the same 7 sections
collapsed into one file as `##` headers. A Tier B doc may be promoted to Tier A mid-review if
research finds more material than expected — a reversible judgment call, not a rigid rule.

- `00-overview.md` — scope (files covered, src/test counts, link to any existing subsystem
  `AGENTS.md` breadcrumb), dispatched agent(s), summary verdict table, cross-references.
- `01-structural-comparison.md` — design-decision inventory stated up front (so the
  comparison table's row selection is traceable to a decision, not an arbitrary grep hit), then
  a matrix: rows = design decisions, columns = fukuii + 6 reference clients, cells = brief
  description + file:line citation (or "N/A — no equivalent" where genuinely absent — do not
  force-fit a row where a client's architecture is categorically different, e.g. erigon's
  snapshot-distribution model is not "SNAP sync done differently," it's a different category of
  solution — say so explicitly), then narrative analysis of notable divergences, then
  verdict(s) with rationale.
- `02-scala3-pekko-currency.md` — cross-reference existing S-rules/IPs first (list which
  already cover this subsystem, to avoid duplicate work); old-pattern inventory table
  (file:line, pattern, violates-what, already-tracked-as-or-NEW); verdict per finding.
- `03-test-quality.md` — test inventory (file count, src:test ratio, compared against the
  repo's own extremes — `vm`'s ~2.2x ratio as the well-tested benchmark); classification table
  (file:line, test name, classification, rationale, reference-client test to model for
  coverage gaps); coverage-gap detail section.
- `04-dead-inefficient-code.md` — dead-code candidates (file:line, description,
  WIRE/DELETE/DEFER verdict, assessment-question answers); inefficiency findings; odd-design
  findings (works, but diverges from all 6 clients' converged approach — worth asking why).
- `05-opportunities.md` — performance-opportunity table, differentiator table,
  security-automation-gap table, each row meeting the falsifiability bar from the verdict
  taxonomy above.
- `06-verdicts-followups.md` — closing table: Finding ID | Verdict(s) | Test class. |
  Proposed QUEUE.md destination | Priority | Disposition. Disposition is always one of
  `finding-resolution.md`'s three: absorbed into an existing IP/rule, new IP scheduled, or
  deferred to a named future batch — never a bare "flagged, TBD."

## Findings-Resolution Log interplay

Each subsystem's own `00-findings-index.md` (or the equivalent in a Tier B doc) is the
exhaustive record — every finding, every verdict, no exceptions. At an item's close-out,
QUEUE.md's central Findings Resolution Log gets: one summary row for the item itself, plus one
row per CRITICAL/HIGH finding (and any MEDIUM affecting 3+ files) — each resolved as either
fast-tracked (only if it genuinely meets the Critical & Security Fast-Track bar — check
explicitly, don't assume research-only framing exempts a real live issue) or "logged as future
FIX-<subsystem>-NN, not yet drafted — implementation is a separate post-research batch" (Status:
`DEFERRED`, since actual fixes are out of scope for a systemic-review cycle by design).
`POLISH-ONLY`/`DIFFERENTIATOR`-verdict LOW findings stay subsystem-local only — this keeps the
central log's signal-to-noise high while still giving every finding that matters an explicit,
auditable resolution.

## Non-goals

- No source-code edits, ever. This protocol produces documentation only.
- No consensus-rule proposals — those route through the Consensus-Critical Change Protocol
  later, during implementation, not during a systemic-review cycle.
- Does not replace `scout`/`batch-research-protocol.md` (which validates ONE already-scoped
  implementation batch before it runs) or `eye`'s post-implementation validation — this
  protocol is upstream of both, feeding future batches, not validating in-flight ones.
