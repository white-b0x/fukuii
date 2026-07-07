# Testing Protocol

Test cadence for all agent sessions on the fukuii codebase. The full test suite
takes 24 minutes — running it between phases compounds into hours of stall time.
This protocol keeps feedback fast without sacrificing coverage.

Used by: ALL agents
Referenced by: `fukuii/CLAUDE.md`, loom.md, mithril.md, wraith.md, flow.md

---

## The three tiers (ADR-017)

| Command | What runs | Time | Baseline |
|---------|-----------|------|---------|
| `sbt testEssential` | 3,621 unit tests | ~24 min | 3,621 / 0 failures |
| `sbt testStandard` | Unit + integration | ~30 min | — |
| `sbt testComprehensive` | Full ethereum/tests compliance | <3 h | — |

---

## Per-phase cadence

### After EVERY file edit
```bash
sbt compile-all    # mandatory, fast — type errors surface immediately
```
Never batch multiple file edits before compiling. One file, one compile.

**Exception — core domain type sweeps:** see the dedicated section below.

### After formatting-only phases
(Returns removal, Messages.scala type additions, import cleanup)
```bash
sbt scalafmtAll    # formatting check — no tests needed, no logic changed
```
No tests. These phases change syntax only — compile is the full signal.

### After logic-changing phases
(Main migration, caller updates, behavior changes)
```bash
sbt "testOnly *<ActorNameSpec>*"                     # actor-specific, seconds
sbt "testOnly *<SubsystemSuite>*"                    # subsystem if callers touched
```
Run targeted tests only. Do not run testEssential here. These finish in seconds with
small output — run directly, no wrapper needed.

### Pre-push gate — before `git push origin`
```bash
scripts/agent-tooling/sbt-run.sh <log-name> testEssential  # full testEssential (~24 min)
```
Invoke with `run_in_background: true` — see `background-script-execution.md`. Run exactly
once before pushing to origin. This is the regression gate for a batch or PR.
Do not run it mid-thread or between phases — it is a 24-minute blocker.
Do not run it as a sanity check during a sprint; use targeted tests (`sbt "testOnly *Spec*"`) instead.

---

## Core domain type sweeps

When a sweep touches a **heavily-imported domain type** — `BlockHeader`,
`Account`, `Block`, `Transaction`, or any type imported by >50 files — Zinc's
incremental compiler cascades the change through every dependent compilation
unit across all 12 modules. This is not a hang or a freeze: it is the expected
one-time cost of touching the most-depended-on types in the codebase.

### How to detect before starting

```bash
# Count files importing the type you are about to change
grep -rl "BlockHeader\b" src/ --include="*.scala" | wc -l   # e.g. 180+
grep -rl "Account\b"     src/ --include="*.scala" | wc -l   # e.g. 90+
```

If result > 50, you are in core domain sweep territory.

### Compile strategy during the sweep

Replace the per-file `sbt compile-all` with `sbt compile` (root main only):

```bash
sbt compile     # root main sources only — no test/IT/Benchmark modules
```

- The first `sbt compile` after touching a core type will be slow (1–3 min
  full cascade). Every subsequent compile in the same sweep is fast (incremental
  delta only, seconds).
- `sbt compile` still catches all type errors in main-source files — it is a
  complete signal for correctness of the changes.
- Test sources, IT, and Benchmark modules are excluded. They import the same
  types but do not need to recompile between each main-source edit.

At the very end of the sweep (all 18 / N files done):

```bash
sbt compile-all    # once — catches any issues in test/IT/Benchmark sources
```

### Root cause (why `compile-all` is slow here but not normally)

Normal changes touch leaf or mid-level files; Zinc's dependency graph is
shallow, so the cascade is small. Touching `BlockHeader` (or similar) is
touching the root of the dependency graph — every file that imports it must
recompile. `compile-all` includes test + IT + Benchmark sources, so the
cascade is 3–4× larger than root-main alone. Using `sbt compile` between
edits limits each cascade to main sources only; test sources recompile once
at the end.

### Affected types (known core domain, as of June 2026)

| Type | Location | Approx. dependents |
|------|----------|-------------------|
| `BlockHeader` | `domain/src/…/domain/blockchain/block/BlockHeader.scala` | 180+ |
| `Account` | `domain/src/…/domain/blockchain/state/Account.scala` | 90+ |
| `Block` | `domain/src/…/domain/blockchain/block/Block.scala` | 120+ |
| `Transaction` | `domain/src/…/domain/blockchain/transaction/Transaction.scala` | 100+ |

If you are unsure whether a type qualifies, run the grep above before starting.

---

## Targeted test patterns

```bash
# By actor name:
sbt "testOnly *AccountRangeCoordinatorSpec*"
sbt "testOnly *ByteCodeCoordinatorSpec*"

# By subsystem:
sbt "testOnly *SNAPSuite*"      # all SNAP tests (~263)
sbt "testOnly *NetworkSuite*"

# By tag (sbt native):
sbt testNetwork
sbt testRLP
sbt testCrypto
sbt testVM
```

---

## Format commands

| Command | What it does | When to use |
|---------|-------------|-------------|
| `sbt scalafmtAll` | scalafmt across ALL modules | After every commit during migrations |
| `sbt scalafmt` | scalafmt ROOT module only | **Never** — misses submodules |
| `sbt formatAll` | scalafixAll + scalafmtAll | Pre-PR on clean codebase ONLY |
| `sbt scalafmtAll` ≠ `sbt formatAll` | formatAll runs scalafix | formatAll aborts on pre-existing violations |

---

## What "baseline holds" means

The thread-end `testEssential` run must show:
- Test count: **3,621** (not fewer — a missing test class = silent deletion)
- Failures: **0**
- If count drops: investigate before closing the thread

**Per-thread delta tracking:** Before starting any migration or sweep thread, record
the current test count from the most recent `testEssential` run (see the test quality
log at `.local/docs/test-quality-log.md` for the last known baseline). At thread end,
compare. A negative delta — even by 1 — means a test class was silently deleted or a
`@Test` annotation was dropped. This commonly happens when a Classic actor spec is
deleted during migration but no Typed replacement spec is written. Investigate before
closing; do not accept a lower count as the new baseline without a recorded reason.

---

## Inline test standards (when writing new tests)

- No `Thread.sleep` — use `eventually(...)` or `TestProbe.expectMsg(duration)`
- No `@Ignore` without a one-line comment explaining the gate condition
- Deterministic: same result on every machine and CI run
- Each test covers one behavior — not a scenario script
- New tests for migrated actors: use `ActorTestKit` (Typed), not `TestActorRef` (Classic)

---

## Test-only task scope boundary (STOP-and-report)

A task scoped to tests only (or to a named file/area) that appears to require a
production-code change to make a test pass is a **stop condition, not an
implementation decision**. This mirrors `AGENTS.md`'s "Failure is information —
your next move is words, not another blind tool call": when the fix seems to
need crossing outside the stated scope, say so and stop, rather than crossing it
silently.

- **STOP and report the blocker** — state the specific file, the specific reason
  the test can't be made to pass within the stated scope, and a proposed next
  step. Do not edit the production file to "just see if it helps."
- **Never instrument production code to diagnose a failing test.** Adding
  `System.err.println`/`println`/`printStackTrace` trace statements — or
  temporary DEBUG `<logger>` entries in `logback-test.xml` — to a production
  file while chasing a test failure is a scope violation on top of a logging
  standards violation. See `logging-standards.md`'s "Debug instrumentation in
  production code" section for the full ban and its done-gate grep.
- **Instrument the test, not the production code.** If runtime visibility is
  genuinely needed to diagnose the failure, add temporary logging/assertions to
  the test file itself, or use an already-wired debug facility scoped to the
  test run. Revert any test-scope config change (e.g. a temp DEBUG logger
  entry) before the task is done — it is not a permanent change.
- **A real incident this rule exists to prevent:** given a test-only task
  (migrate a spec file), an agent got the test failing, could not resolve it
  within scope, and — instead of stopping — edited two production actor files
  to add `System.err.println("<AGENT>-DEBUG ...")` trace statements and added
  temp DEBUG logger entries to `logback-test.xml`, leaving both uncommitted in
  the working tree. Both edits were undone; this section is the fix so it
  doesn't happen silently again.

This is a general rule, not migration-specific — it applies to any agent given
a scoped task (test-only, file-only, subsystem-only) that hits a wall.

## Protocol for failing tests after migration

1. Run targeted test first: `sbt "testOnly *<FailingSpec>*"`
2. Read the failure — understand it before touching anything
3. If failure is in the migrated actor: fix the migration, not the test
4. If failure is in a test that tests Classic behavior: update the test for Typed API
5. If failure is in an unrelated test: note it, do not fix it (out of scope), surface to user
6. Never modify a test to make it pass without understanding why it failed — and never
   cross into production code to do so without stopping and reporting first (see
   "Test-only task scope boundary" above)

---

## Avoiding copy-pasted near-identical specs (ScalaTest 3.2.x + ScalaCheck)

Ported from Nethermind's `test-infrastructure.md` "Test guidelines" — the underlying
discipline (don't duplicate a test body that differs only by input/expected-output) is
language-agnostic; this is fukuii's ScalaTest-specific version.

- **Before writing a new test, check if an existing one can be extended** with another case
  in a table-driven test rather than copy-pasting the whole spec body.
- **Tests that differ only by input/expected output** → use ScalaTest's `TableDrivenPropertyChecks`
  (`forAll(Table(("input", "expected"), (a, x), (b, y), ...)) { (input, expected) => ... }`)
  instead of N near-identical `"should ..." in { ... }` blocks.
- **Property-style invariants over many generated inputs** → ScalaCheck's `forAll` (via
  `org.scalatestplus:scalacheck-1-18`, already a project dependency) rather than a
  hand-enumerated table, when the property holds for a wide input space rather than a
  handful of specific cases.
- **Shared arrange/setup or assertion logic, not the whole test** → extract into a private
  helper method or an existing fixture trait, not a copy-pasted block — keep each test body
  focused on what makes that case unique; the helper shouldn't hide behavior the reader
  needs to see to understand the test.
- **Signal to look for**: grep a spec file for `"should ` occurrences with near-identical
  bodies differing only in literal values — that's the concrete tell this section exists to
  catch. `grep -c '"should ' <SpecFile>.scala` followed by a quick read is usually enough to
  spot it.
