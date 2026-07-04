# Canonical source + symlink convention

Canonical protocol docs live in `.agents/protocols/`; canonical skill definitions live in
`.agents/skills/`. `.claude/agent-protocols/` and `.claude/skills/` contain **symlinks** to
the canonical files — never independent copies. This mirrors the convention used by
Nethermind (`.claude/repo-references/clients/nethermind/.agents/rules/agent-skills.md`), a
directly comparable production Ethereum execution client vendored in this repo as a
reference client.

## Disambiguation: `.agents/` vs `.claude/agents/`

These are two unrelated directories with confusingly similar names:

- **`.agents/`** (this directory) — canonical source for shared protocol docs and skill
  definitions. Tool-agnostic; any coding agent could in principle read it.
- **`.claude/agents/`** — the 12 Claude Code subagent *definitions* (`forge`, `beacon`,
  `eye`, `wraith`, `herald`, `mithril`, `prism`, `loom`, `vault`, `conduit`, `flow`,
  `warden`). Claude Code-specific; see `CLAUDE.md`'s Specialist subagents table.

Don't conflate them. A protocol doc or skill belongs in `.agents/`; a new specialist
subagent's behavior definition belongs in `.claude/agents/`.

## Rules

- **Single source of truth**: always create and edit protocol docs in
  `.agents/protocols/<name>.md` and skills in `.agents/skills/<name>/SKILL.md`. Never place
  a standalone protocol or skill file directly under `.claude/agent-protocols/` or
  `.claude/skills/`.
- **Protocols symlink per file**: `.claude/agent-protocols/` holds file-level symlinks
  (`.claude/agent-protocols/<name>.md` → `../../.agents/protocols/<category>/<name>.md`),
  one per protocol doc — not a single directory-level symlink. `modernization-log/` is the
  one exception: it's a separate, already-in-progress retirement (see
  `.agents/protocols/process/sprint-lifecycle.md` §"Retiring the legacy tracker") and stays
  a real subdirectory at `.claude/agent-protocols/modernization-log/`, untouched by this
  convention.
- **Protocols are grouped into thematic subdirectories** under `.agents/protocols/`
  (2026-07): `consensus/`, `code-style/`, `testing/`, `process/`, `tooling/`, `storage/`.
  This is purely a canonical-source organizational grouping — since every consumer reads
  through the flat `.claude/agent-protocols/<name>.md` symlink layer, no cross-reference
  outside `.agents/protocols/` itself needed to change when this grouping was introduced.
  Skills, by contrast, are **not** physically grouped this way (see below) — Claude Code's
  support for nested skill discovery under `.claude/skills/` is unconfirmed (a live empirical
  test — creating both a nested and a flat probe skill and attempting to invoke each — was
  inconclusive: neither was discovered mid-session, which could mean skill discovery is
  session-start-only rather than depth-limited, but that couldn't be isolated). Given the
  risk of guessing wrong across 36 existing skills, categorization for skills is handled by
  `.claude/skills/README.md`'s indexed table instead of physical directory grouping.
- **Skills symlink per directory**: `.claude/skills/<name>` is a directory-level symlink
  (`.claude/skills/<name>` → `../../.agents/skills/<name>`) — symlink the whole skill
  subdirectory, not individual files inside it, since each skill directory is a single
  self-contained `SKILL.md` (plus an optional `references/`). The four loose meta-docs
  (`README.md`, `CONVENTIONS.md`, `VALIDATION.md`, `REFERENCES.md`) living directly in
  `.claude/skills/` are shared documentation about the skills collection, not per-skill
  definitions — they stay exactly where they are and don't participate in this convention.
- **Relative paths**: protocol symlinks use `../../.agents/protocols/<category>/<name>.md`;
  skill symlinks use `../../.agents/skills/<name>` (relative to `.claude/agent-protocols/`
  or `.claude/skills/` respectively — both sit two levels below repo root, same as
  `.agents/protocols/` and `.agents/skills/`).
- **Preserve on copy**: when copying `.agents/` or `.claude/` to another location, use
  `cp -a` to preserve symlinks rather than dereferencing them into independent copies.
- **In-file relative references**: a skill's `SKILL.md` must reference shared sibling docs
  (`CONVENTIONS.md`, `REFERENCES.md`) by the repo-root-relative form
  `.claude/skills/CONVENTIONS.md`, not `../CONVENTIONS.md` — an upward-relative reference
  is ambiguous once the skill's own directory is a symlink (physical vs. logical
  resolution), while the repo-root-relative form is unambiguous regardless of which path
  a reader opened the file through.

## Adding a new protocol

Pick the closest existing category (`consensus/`, `code-style/`, `testing/`, `process/`,
`tooling/`, `storage/`) — add a new one only if none fit:

```bash
# From repo root
touch .agents/protocols/<category>/<name>.md
ln -s "../../.agents/protocols/<category>/<name>.md" ".claude/agent-protocols/<name>.md"
git add ".agents/protocols/<category>/<name>.md" ".claude/agent-protocols/<name>.md"
```

## Adding a new skill

```bash
# From repo root
mkdir -p ".agents/skills/<name>"
# ... add SKILL.md there, using .claude/skills/CONVENTIONS.md-style repo-root-relative
# references for any shared sibling doc ...
ln -s "../../.agents/skills/<name>" ".claude/skills/<name>"
git add ".agents/skills/<name>" ".claude/skills/<name>"
```
