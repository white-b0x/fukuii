# Reference-client agentic-tooling gap analysis — 2026-07

**Status:** Research/survey only — no implementation. Forward-looking watch-list, per the
framing requested: note what's not currently applicable/compatible but may make sense as
fukuii evolves. Companion to `agentic-tooling-refresh-2026.md` (the executed restructuring)
and `agents-md-decision-2026.md`/`claude-md-refresh-2026.md` (the original AGENTS.md
research). General repo-hygiene findings from this same survey live separately at
`docs/research/best-practices/evm-clients/repo-patterns.md`.

## Method

All 6 vendored reference clients at `.claude/repo-references/clients/` were surveyed in
parallel (confirmed genuine full clones, not sparse): Besu (Java), core-geth (Go, ETC),
Erigon (Go), go-ethereum (Go), Nethermind (C#), Reth (Rust). Each was checked for: an
AGENTS.md/CLAUDE.md-equivalent, a `.claude/`/`.agents/`/`.cursor/`-style directory,
CI-integrated AI code review, MCP server config, and Spec-Kit-like spec-driven-development
tooling.

## Headline finding — revise the "fukuii is way ahead of a sleepy field" assumption

Going in, the assumption (partially validated by the earlier single-client Nethermind
survey) was that most established clients would have little to no agentic tooling. That
holds for **core-geth** (confirmed near-zero) but **not** for the other five:

| Client | Root AGENTS.md/CLAUDE.md? | `.claude`/`.agents` dir? | CI AI-review bot? | MCP server? | Spec-Kit-like? |
|---|---|---|---|---|---|
| **Erigon** | Yes — `agents.md` + `CLAUDE.md` symlink, **plus 4 per-subsystem `agents.md`** (`cl/`, `db/`, `p2p/`, `execution/stagedsync/`) | Yes — `.claude/settings.json` (permissions + PostToolUse hook), `.claude/hooks/comment-policy.py`, `.claude/rules/` (3 docs), `.claude/skills/` (**33** skills) | **Yes, in production** — `.github/workflows/claude.yml` (`anthropics/claude-code-action@v1`, `@claude`-mention trigger, `contents: write`) | **Yes — shipped as a node feature**, not dev tooling (embedded in the `erigon` binary, `127.0.0.1:8553` by default, 40+ tools, also a standalone `mcp` binary) | Partial — `docs/plans/*-objective.md`/`*-spec.md` pairs, no templates/constitution/CLI |
| **Reth** | Yes — `AGENTS.md` (549 lines, rich, PR-derived examples) + `CLAUDE.md` symlink | No (one stray leaked `docs/vocs/.claude/settings.local.json`, looks like an accidental local-session artifact) | No | No | No |
| **Nethermind** | Yes — `AGENTS.md` (canonical) + `.claude/CLAUDE.md` = one-line `@../AGENTS.md` | Yes — `.agents/rules/` (9 docs) + `.agents/skills/` (4 skills), `.claude/skills/` as symlinks | **Yes, in production** — `.github/workflows/claude-review.yml` (structured JSON verdict, branch-protection-gating status, author-association fork-PR gating) | No | No |
| **go-ethereum** | Yes — `AGENTS.md` (103 lines, **added Feb 2026**), no CLAUDE.md | No | No — but has an **anti-AI-slop PR auto-closer** (`validate_pr.yml`) instead | No | No |
| **Besu** | No AGENTS.md/CLAUDE.md — only `.github/copilot-instructions.md` (6 lines, review-tuning only) | No | No | No — `.github/copilot-instructions.md` only, no MCP dir | No |
| **core-geth** | No | No | No | No | No (ECIPs referenced only as external URLs) |

**Revised framing**: 4 of 6 surveyed clients (Erigon, Reth, Nethermind, go-ethereum) have
adopted a root `AGENTS.md` already — this convention has real, fast-moving traction in the
EVM-client ecosystem, not a niche fukuii happened to bet on early. 2 of 6 (Erigon,
Nethermind) have production CI-integrated AI review bots — the exact thing fukuii has
backlogged, with two working reference implementations now available to study. fukuii's
own investment (protocols/skills/subagents/looping harness/Spec Kit) is still the deepest
of the six on raw mechanism count, but it is not alone in the space, and two peers
(Erigon's per-subsystem `agents.md` + shipped MCP server, Nethermind's CI review bot) have
concrete things worth learning from directly.

## What's genuinely portable now vs. watch-for-later

### Immediately worth considering

1. **CI-integrated AI review bot is de-risked, not speculative.** Two production reference
   implementations exist: Erigon's `.github/workflows/claude.yml` (mention-triggered,
   `anthropics/claude-code-action@v1`) and Nethermind's `.github/workflows/claude-review.yml`
   (auto-triggered on PR open, structured verdict, fork-PR security gating via
   `author_association`). This was already flagged as backlog in `agentic-tooling-refresh-2026.md`
   §6 — this survey doesn't change the "not now" call (still separate infrastructure/security
   design work), but it does mean the next time this is picked up, there are two concrete,
   different-shaped implementations to compare rather than a from-scratch design.

### Watch for later — genuinely interesting, not actionable today

2. **Per-subsystem `agents.md` breadcrumbs (Erigon's pattern).** Erigon's root `agents.md`
   links out to `cl/agents.md`, `db/agents.md`, `p2p/agents.md`,
   `execution/stagedsync/agents.md` — each a short, dense primer for that subsystem (e.g.
   `db/agents.md` documents the state-domain/MDBX layering; `execution/stagedsync/agents.md`
   documents the stage interface contract). fukuii currently has one large `ARCHITECTURE.md`
   plus `.agents/protocols/*.md` (cross-cutting conventions, not subsystem maps). A
   lightweight per-subsystem breadcrumb (e.g. under `blockchain/sync/`, `db/storage/`) is a
   cheap, incremental pattern worth reconsidering if `ARCHITECTURE.md` or the protocol docs
   start feeling too coarse-grained for a specific hot subsystem — not urgent now.

3. **A PostToolUse hook enforcing a house style (Erigon's `comment-policy.py`).** Erigon's
   `.claude/hooks/comment-policy.py` is a `Write|Edit|MultiEdit` hook that regex-scans new Go
   comment lines for narration patterns the project doesn't want (incident narration,
   scope-hedging, bare issue-number references), returning `additionalContext` rather than
   blocking. This mechanizes what fukuii currently only states in prose (e.g. the global
   CLAUDE.md's "default to no comments" rule, `scala3-style.md`'s conventions). Worth
   considering if prose-only enforcement of a specific convention proves to keep drifting in
   practice — not a general recommendation to hook everything.

4. **A node-facing MCP server, as a product feature (Erigon's pattern).** Erigon ships MCP
   support *inside the release binary* (40+ tools, resources, pre-built prompts, documented
   for Claude Desktop/Code and OpenAI Codex) — materially bigger scope than fukuii's current
   `.github/copilot/` MCP wiring (dev/CI-facing JSON-RPC exposure). Building a user-facing,
   product-level MCP server for node operators is a legitimate future feature, not a docs/
   structure change — flag as a distinct, larger initiative if fukuii's product direction
   ever points that way, not something to fold into the current agentic-tooling scope.

5. **`llms.txt`/`llms-full.txt` (Erigon's pattern).** A machine-readable index + full-text
   dump of the public docs site, in the emerging `llms.txt` convention. Cheap to generate
   from fukuii's existing `mkdocs.yml` docs site once/if it becomes worth doing — low
   priority, no urgency signal from this survey.

6. **An anti-AI-slop PR auto-closer (go-ethereum's `validate_pr.yml`).** Auto-closes PRs
   whose title matches an AI-codegen-style conventional-commit pattern
   (`^(feat|chore|fix)(\(.*\))?\s*:`) not matching go-ethereum's own `<package>: description`
   convention, verifying the referenced package path actually exists. Only relevant once
   fukuii opens to outside contributors at meaningful volume (currently single-maintainer
   per `.agents/protocols/github-workflows.md`) — watch for later, not urgent.

7. **`Assisted-By`/model-metadata DCO convention (Besu's pattern).** Besu's `CONTRIBUTING.md`
   (lines 194–201) documents an explicit governance decision for agentic contributions: DCO
   sign-off must come from a human, but agents are encouraged to add `Co-Authored-By`/
   `Assisted-By` with model name, version, and context size (example:
   `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`). fukuii's
   `git-conventions.md` already covers `Co-Authored-By` conventions but not model-metadata
   or a distinct `Assisted-By` key. Relevant only if/when fukuii adopts DCO sign-off — watch
   for later.

8. **Gitignore discipline for stray per-subproject Claude settings (Reth's cautionary
   example).** `docs/vocs/.claude/settings.local.json` leaked into Reth's repo — looks like
   an accidental local-session artifact, not a deliberate convention. Not something to adopt;
   a reminder to keep an eye on fukuii's own subdirectories (e.g. if a docs-site subproject
   is ever added) for the same leak pattern.

### Confirmed non-gaps (don't chase these)

- **core-geth and Besu validate fukuii's overall agentic-tooling investment as ahead of
  baseline**, not behind — both have essentially nothing beyond a thin Copilot-review-tuning
  file (Besu) or nothing at all (core-geth).
- **Reth's single-file AGENTS.md/CLAUDE.md-symlink philosophy is a deliberate contrast, not
  a model to copy** — it's rich in prose content but has none of the mechanism investment
  (skills, subagents, looping harness) fukuii already has. fukuii's layered approach
  (portable `AGENTS.md` + Claude-specific `CLAUDE.md` orchestration + `.agents/protocols/`
  + `.agents/skills/` + 12 named subagents) is the more elaborate, not less, of the two.
- **go-ethereum's own AGENTS.md (added Feb 2026) is a thin compliance checklist**, not an
  onboarding/context doc — no architecture, no domain semantics, no testing philosophy.
  fukuii's `AGENTS.md` (ETC/ETH domain context, build/test tier table, working discipline)
  is materially richer even from the most mature client in this comparison set.

## Cross-reference

- Execution record for the already-completed restructuring: `agentic-tooling-refresh-2026.md`.
- Original AGENTS.md/CLAUDE.md split rationale: `agents-md-decision-2026.md`,
  `claude-md-refresh-2026.md`.
- General (non-agentic) repo-hygiene findings from this same 6-client survey:
  `docs/research/best-practices/evm-clients/repo-patterns.md`.
