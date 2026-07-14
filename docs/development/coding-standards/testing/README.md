# Testing domain — scope stub

**Scope:** Test-*code* standards for fukuii's spec files — spec-style conventions
(ScalaTest `AnyFlatSpec`/`AnyWordSpec`/`AnyFunSuite`/`AnyFreeSpec`, and which purpose each
style is reserved for, if the ratified standard is purpose-per-style rather than
single-style), mocking (`scalamock`), test determinism (no `Thread.sleep` — `ManualTime`/
deterministic barriers instead), and integration/multi-client-conformance shape (`hive`
simulators). Distinct from `.agents/protocols/testing-protocol.md`, which is operational —
per-phase test *cadence* (when to run `compile-all` vs. `testOnly` vs. `testEssential`) —
and stays a protocol; this domain covers how test *code itself* should be written and
organized, not when to run it.

**Owning specialist:** `eye` (test validation, spec-style conformance), `prism` (review) —
plus the relevant domain specialist for tests exercising that domain (e.g. `forge`/`beacon`
for consensus test-vector specs, `herald` for `simulators/devp2p/`-adjacent specs).

**Authority:** `.claude/repo-references/scalamock` (idiomatic Scala 3 mock patterns) and
`.claude/repo-references/hive` (multi-client black-box compliance simulators —
`simulators/ethereum/`, `simulators/eth2/`, `simulators/devp2p/`, `simulators/smoke/`).
ScalaTest itself — the spec-style framework this domain's first standard is about — is a
build dependency, not a vendored repo under `repo-references/`: no local clone exists to
cite by file/line, so spec-style rules cite ScalaTest's own published docs/source directly
by version, the same fallback any non-vendored dependency uses.

**Status:** net new — no existing `.agents/protocols/` file covers test-*code* style (only
`testing-protocol.md`'s cadence). First content candidate is **`SPEC-STYLE-STANDARD-01`**
(`.claude/sprints/queue/chase-deferred.md`): the ScalaTest spec-style decision (single-style
convergence vs. purpose-per-style, for `AnyFlatSpec`/`AnyWordSpec`/`AnyFunSuite`/
`AnyFreeSpec`) is currently OPEN, flagged after Batch 4's `vm/` `WordSpec`→`AnyFlatSpec`
sweep exposed that no documented standard exists. Lands here once ratified, per the
VALIDATE gate in `../README.md`.
