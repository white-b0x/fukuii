# Testing Protocol

Test cadence for all agent sessions on the fukuii codebase. The full test suite
is a long-running, pre-push-only pass — running it between phases compounds into
significant stall time across a multi-phase thread.
This protocol keeps feedback fast without sacrificing coverage.

Used by: ALL agents
Referenced by: `fukuii/CLAUDE.md`, loom.md, mithril.md, wraith.md, flow.md

---

## The three tiers (ADR-017)

| Command | What runs | Time | Baseline |
|---------|-----------|------|---------|
| `sbt testEssential` | Unit tests | Long — see `.local/docs/test-quality-log.md`'s `Tier baselines` table for the current wall-clock figure; do not hardcode a number here, it drifts | 0 failures — see `.local/docs/test-quality-log.md`'s `Tier baselines` table for the current test count; do not hardcode a number here, it drifts |
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

### Format gate — before `git commit` of a phase (MANDATORY)
```bash
sbt "<module>/scalafmtCheck" "<module>/Test/scalafmtCheck"   # scoped to the module you touched
```
`scalafmtCheck` **verifies** formatting without writing — it is the gate, run it before
every phase commit (not just `scalafmtAll`, which writes silently and can be forgotten).
If it fails, `sbt "<module>/scalafmt" "<module>/Test/scalafmt"` to fix, re-check, then commit.
**Why this gate exists:** a phase's build agent can finish (or die mid-run — a stalled agent
once left two consensus files unformatted) with formatCheck-dirty output; committing it
defers the failure to CI `formatCheck`. Catch it at the phase boundary — one cheap check —
so formatting is never a downstream catch. The code itself is written Scala-3-idiomatic from
the start (`scala3-style.md` S1–S11); this gate is purely about whitespace/wrap conformance
to `.scalafmt.conf` (`runner.dialect = scala3`), not idiom conversion.

### Pre-push gate — before `git push origin`
```bash
scripts/agent-tooling/sbt-run.sh <log-name> testEssential  # full testEssential — see test-quality-log.md
```
Invoke with `run_in_background: true` — see `background-script-execution.md`. Run exactly
once before pushing to origin. This is the regression gate for a batch or PR.
Do not run it mid-thread or between phases — it is a long blocker (current wall-clock
figure: `.local/docs/test-quality-log.md`'s `Tier baselines` table).
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

### Affected types (known core domain)

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
- Test count: no lower than the last recorded baseline in `.local/docs/test-quality-log.md`
  (a missing test class = silent deletion) — never hardcode the number in this file, it drifts
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

## Permission-grant scope boundary (STOP-and-report)

The same discipline applies when the wall is a **tool/permission grant**, not a task-scope
boundary. Per-agent `tools:` frontmatter in `.claude/agents/*.md` grants whole tools
(`Read`, `Edit`, `Bash`, `Write`) — it is **not** path-scoped in current Claude Code: a glob
like `Write(.local/**)` is silently unenforced (parsed as plain `Write`), and
`.claude/settings.json` permissions are global across all agents, not per-agent. There is no
mechanism today to give one agent Write access to `.local/**` but not `src/**`. Confirmed by
direct probe (PERMISSION-OVERHAUL, 2026-07-07): an agent holding only `Write(.local/**)` was
able to write outside `.local/`. See `warden.md`'s Permissions/settings section for the fuller
mechanism note — also note that editing a `tools:` grant only takes effect after a session
restart, never for a mid-session spawn.

Because grants are coarse (per-tool, not per-path), the fix is procedural, not technical:

- If a task needs a capability the agent's `tools:` line doesn't grant (most commonly:
  `Edit`-only agents needing to create a new file, which requires `Write`), **STOP and report
  the specific gap** — the tool/path that's missing, exactly what action needs it, and what
  grant would unblock it. Use a `PERMISSION-BLOCK:` marker so the gap is easy to find in a
  transcript.
- **Never invent a workaround.** Not a Bash-heredoc substituting for a missing `Write` (the
  exact anti-pattern that motivated this rule — an `Edit`-only agent creating new spec files
  via `cat <<EOF > file` instead of stopping), not writing outside the agent's intended area,
  not handing the artifact to the orchestrator to materialize on the agent's behalf as a
  routine substitute for a real grant.
- This is a feedback loop, not a one-shot guess: reported `PERMISSION-BLOCK`s are how tool
  grants get tuned to what agents actually need, empirically, over time — a workaround hides
  the signal that the grant was wrong.

This mirrors the test-only-scope rule above: "the fix seems to need crossing outside the
stated scope" applies equally whether the boundary is a file/task scope or a tool grant.

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
