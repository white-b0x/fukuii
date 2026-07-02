# .claude/scripts/

Mechanical helper and wrapper scripts for the agent workflow in this repo. Shared,
portable team tooling — every script resolves its own `REPO_ROOT` relative to its own
location (`cd "$(dirname "${BASH_SOURCE[0]}")"` + a fixed `..` traversal), so none of
these depend on a specific machine, username, or clone path.

Two tiers (see `.claude/agent-protocols/sprint-lifecycle.md`, Rule 4, and
`.claude/agent-protocols/background-script-execution.md` for the rationale):

- **Top-level (`.claude/scripts/*.sh`)** — command-family wrappers for commands prone
  to long runtimes or large/noisy output, meant to be invoked with the calling tool's
  background-execution option (e.g. Bash tool `run_in_background: true`) so output never
  streams live. Follow `sbt-run.sh`'s shape: log everything to `.local/logs/`, print one
  completion line, exit with the real command's exit code.
- **`lib/`** — general-purpose mechanical helpers that replace a repeated multi-step
  choreography (several greps, a cross-reference check) with one fast, read-only call.
  These are not background candidates — they finish in well under a second and print
  their report directly to stdout.

## Top-level (background-safe command wrappers)

| Script | Wraps | Used by |
|--------|-------|---------|
| `sbt-run.sh` | Any `sbt` task or space-separated task sequence (`compile-all`, `scalafmtAll`, `formatAll`, `pp`, `testEssential`, `testStandard`, `testComprehensive`, `"IntegrationTest / test"`, ...) — logs to `.local/logs/<name>.log`, prints one `DONE log=... exit=N` line | `background-script-execution.md` |
| `sprint-status.sh` | Reports the current state of `.claude/sprints/` (batches, Chase & Deferred Items, completed/archive) | `fukuii-sprint-queue` skill |
| `sprint-clear.sh` | Moves CLOSED batches out of `QUEUE.md` into `completed/` (dry-run by default, `--apply` to write) | `fukuii-sprint-queue` skill |
| `sprint-archive.sh` | Moves a `completed/` file into `archive/` (dry-run by default, `--apply` to write) | `fukuii-sprint-queue` skill |

## `lib/` (mechanical helpers)

| Script | Replaces | Used by |
|--------|----------|---------|
| `site-sweep.sh` | Running N greps against `src/main/` one at a time before/after an edit pass — runs them concurrently, dedupes, reports per-file counts | `.claude/sprints/patterns/PATTERNS.md` ("known-sites-first, unknowns-concurrent" pattern) |
| `pre-migration-checklist.sh` | The 13 manual `grep` steps in `pre-migration-checklist.md` run by hand against one actor file before every LOOM migration — runs all of them in one call and prints the protocol's own "Pre-flight facts block" format, plus mechanically-detectable red flags | `pre-migration-checklist.md`, LOOM |
| `scala3-style-check.sh` | The S1-S9 ratchet greps in `scala3-style.md`, run and compared against target in one call | `scala3-style.md`, MITHRIL, PRISM |
| `logging-standards-check.sh` | The 10 "grep-verifiable ratchet targets" in `logging-standards.md` | `logging-standards.md`, all agents |
| `storage-rocksdb-check.sh` | The 5 "grep patterns for storage code review" in `storage-rocksdb.md` | `storage-rocksdb.md`, VAULT |
| `pekko-typed-check.sh` | ~20 grep/cross-reference checks across P1-P25 + TL1-TL2 + the CAPSTONE sweep in `pekko-typed-api.md` (excludes checks needing a per-actor name parameter) | `pekko-typed-api.md`, LOOM, PRISM |

## Adding a new script

1. Decide the tier: does it wrap one long/noisy *command* (top-level, background-safe
   shape) or replace a repeated *multi-step choreography* of fast read-only checks
   (`lib/`)?
2. Follow the shape of the closest existing script in the same tier — don't invent a
   new structure per script.
3. Add a row to the appropriate table above and, if it formalizes a protocol's manual
   steps, wire a pointer to it into that protocol doc (see how `pre-migration-checklist.md`
   references `pre-migration-checklist.sh`).
