# Compound Command → Scratch Script Convention

Ad hoc Bash commands that need shell control-flow (`for`/`while`/`until`/`if`/
`case`) with a multi-statement body, or an ad hoc variable assignment that
feeds a later command/conditional in the same invocation, must be written
once to a disposable script under `.local/scratch/` and run as a single flat
invocation — never composed inline in the calling tool's Bash call.

Used by: ALL agents that write throwaway multi-step Bash checks (file
existence sweeps, ad hoc filtering, one-off log inspection)
Referenced by: `fukuii/CLAUDE.md` ("Shared agent protocols" table)

---

## The mechanism this protocol works around

Claude Code's Bash permission system decomposes compound commands on shell
operators (`&&`, `;`, newlines) and checks each resulting fragment against
prefix-based allow rules in `.claude/settings.local.json`. A rule like
`Bash(git *)` matches any fragment that literally starts with `git `. Shell
*keywords* — `for`, `do`, `if`, `then`, `else`, `fi`, `done`, `case`, `esac` —
are not command names, so a fragment that starts with one of them (which is
exactly what a `for`/`if` loop decomposes into) can never match a prefix rule
built around a real executable's name. Worse, an ad hoc variable assignment
(`path="$f"`) is a fragment of its own that starts with whatever the variable
happens to be called that day — unenumerable in principle, since the name
varies per script.

This is not a gap that more permission rules can close. Enumerating `for`/
`do`/`if`/`then`/`fi`/`done`/etc. as bare allow rules (already present in
`.claude/settings.local.json` as a stopgap) covers the *keyword* fragments,
but does nothing for the variable-assignment fragments inside the body — that
part of the friction is structural, not a missing entry.

## The incidents this protocol exists to prevent

Two confirmed cases in one session (2026-07-06), both requiring manual
approval despite an already-large project-local allow-list:

1. A poll loop: `until grep -q "..." log 2>/dev/null; do sleep 20; done; echo DONE`.
2. A file-existence sweep: `for f in <list>; do path="..."; if [ -f "$path" ]; then echo OK; else echo MISSING; fi; done`.

Neither is fixable by adding more prefix rules — see "Relationship to
`background-script-execution.md`" below for how (1) actually resolves.

---

## The rule

> Any Bash invocation that requires `for`/`while`/`until`/`if`/`case` with a
> multi-statement body, or an ad hoc variable assignment that feeds a later
> command/conditional in the same invocation, gets written once as a script
> under `.local/scratch/<slug>.sh` and run via `bash .local/scratch/<slug>.sh`
> — never composed inline as a compound Bash command.

The single stable permission rule this earns
(`Bash(bash .local/scratch/*)`) is the entire payoff: one rule, covering every
future scratch script, instead of chasing each new keyword/variable-name
permutation individually.

## What qualifies

- A `for`/`while`/`until` loop whose `do ... done` body has 2+ statements.
- An `if`/`then`/`elif`/`else`/`fi` whose body has 2+ statements, or that's
  nested inside a loop.
- A `case`/`esac` construct.
- An ad hoc variable assignment (`var=value`, `path="$f"`) consumed by a
  later `;`-joined command or conditional test in the same invocation.
- Self-check: if decomposing the command by `;`/`&&`/`||`/newline would
  produce a bare shell keyword (`if`, `then`, `do`, `done`, `fi`, `elif`,
  `case`, `esac`) as its own fragment, it qualifies.

## What does not qualify

- A single command, however long its argument list: `grep -rl pattern dir`,
  `git log --oneline -5`, `find . -name "*.scala" | wc -l`.
- A single `&&`/`||` two-step chain with no control-flow keywords: `cd dir &&
  ls`, `mkdir -p foo && touch foo/bar`.
- A single bracket-test idiom with one `&&`/`||` and no `if`/`then`:
  `[ -f file ] && echo yes`.
- Anything already governed by `background-script-execution.md` — see below,
  don't double-wrap.

---

## Relationship to `background-script-execution.md`

Orthogonal, not competing: that protocol is about **runtime duration and
output volume** (long/noisy/freeze-prone → background + log file); this one
is about **syntactic shape** (control-flow/compound → scratch file). A
command can be neither, either, or both.

The poll-loop incident above is the overlap case, and its real fix is
`background-script-execution.md`'s, not this doc's: **don't poll at all** —
launch the real work with `run_in_background: true` and let the harness's own
completion notification replace the wakeup loop. Writing a scratch script
whose entire content is "poll in a loop" launders the anti-pattern into a
file instead of removing it. Fix the poll first; only reach for a scratch
script if genuine ad hoc multi-step logic remains after that fix.

## Relationship to `scripts/agent-tooling/`'s promotion path

`.local/scratch/` is for logic used once, within one thread. `sprint-lifecycle.md`
Rule 4 already governs promotion: if the same mechanical choreography recurs
(a second occurrence, same session or a later one), that's the signal to
extract it into `scripts/agent-tooling/lib/<name>.sh` (fast read-only
collectors) or a top-level `scripts/agent-tooling/<name>.sh` (a
background-safe command-family wrapper) — not to keep re-writing near-identical
scratch scripts by hand.

This convention is deliberately **not** a staging ground that funnels
everything toward permanence. Most scratch scripts should stay disposable,
gitignored, and forgotten — that is the point, not a shortcoming.

---

## Naming, location, invocation

- Location: `.local/scratch/<short-task-slug>.sh`, kebab-case, one file per
  throwaway check (e.g. `.local/scratch/check-missing-fixtures.sh`).
- No header-comment ceremony, no `set -euo pipefail` mandate — lower ceremony
  than `scripts/agent-tooling/`'s tracked scripts. `set -uo pipefail` is a
  reasonable default, not a requirement.
- No README/index entry — `.local/` is gitignored and operator-local; there is
  nothing to keep in sync.
- Already covered by the repo's blanket `.local/` gitignore rule — no cleanup
  obligation, no new gitignore line needed.
- **Always invoke as `bash .local/scratch/<slug>.sh`** — never `chmod +x` +
  direct execution. `chmod +x` is itself a mutating command needing its own
  approval, and `./scratch/foo.sh` / `.local/scratch/foo.sh` / an absolute
  path are three different prefix strings to the permission matcher.
  Collapsing to one invocation shape is what makes a single allow-rule cover
  every future scratch script.

---

## Anti-pattern table

| Don't | Do instead |
|-------|-----------|
| Compose a `for`/`if`/`until` construct inline in the Bash tool because it's "just this once" | Write it to `.local/scratch/<slug>.sh`, run via `bash .local/scratch/<slug>.sh` |
| Chain an ad hoc variable assignment into a conditional via `;` inline (`path="$f"; if [ -f "$path" ]; then ...`) | Put the assignment and the conditional in the same scratch script |
| Re-derive the same multi-step check inline a second time in the same or a later thread | Recognize the recurrence — that's `sprint-lifecycle.md` Rule 4's promotion signal, extract to `scripts/agent-tooling/lib/` |
| Treat a polling `until ...; do sleep N; done` as fixed by moving it into a scratch script | Fix the poll itself first, per `background-script-execution.md` — background the real work |
| `chmod +x` a scratch script and invoke it directly | Always `bash .local/scratch/<slug>.sh` — one invocation shape, one allow-rule |
| Add a scratch script to `scripts/agent-tooling/`'s README table "just in case" | Leave it in `.local/scratch/`, gitignored, until it genuinely recurs |

---

## Evaluated and rejected

None yet — first pass, 2026-07-06.
