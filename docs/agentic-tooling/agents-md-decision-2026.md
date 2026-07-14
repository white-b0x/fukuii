# AGENTS.md / copilot-instructions.md decision — should fukuii add them?

**Date:** 2026-07-03 (living research doc — re-verify tool-support claims before acting if this is read more than ~2 months later; this space moves fast)
**Status:** Research/recommendation only. No files created as part of this doc — see "Next step" at the end.

---

## 1. Recommendation

**Add `AGENTS.md` — yes, but as a restructuring, not an addition.**
**Add `.github/copilot-instructions.md` — no.**

### Why AGENTS.md, and why it doesn't have to create a second file to maintain

The naive move — write a new `AGENTS.md` next to the existing 252-line `CLAUDE.md` — creates exactly the drift risk you're worried about. Fukuii already has a live example of that drift: `docs/development/contributing.md` has a "Guidelines for LLM Agents" section (lines 546–671) that documents an agent roster of **wraith, mithril, ICE, eye, forge, herald, Morgoth** — but the current `CLAUDE.md` has moved on to a 12-agent roster (`forge, beacon, eye, wraith, herald, mithril, prism, loom, vault, conduit, flow, warden`) and folded ICE/Morgoth's responsibilities into the main session. `.github/AGENT_LABELS.md` is in the same stale state (it does note the ICE/Morgoth fold-in in its "Related Documentation" section, but its own label reference table is still the old 7-agent set). **This is a live case of the two-context-files problem already happening inside the repo, with only one AI-facing file (`CLAUDE.md`) actually being kept current.** Adding a third loosely-synced file would make this worse, not better.

The fix that avoids this: **don't duplicate content — invert which file is the import target.**

Claude Code officially supports a `@AGENTS.md` import line inside `CLAUDE.md` (confirmed against [Claude Code's memory docs](https://code.claude.com/docs/en/memory), see §3.1 below). That import is a Claude Code-only mechanism; a plain-markdown `AGENTS.md` has no equivalent way to pull in `CLAUDE.md`, because every other tool reads `AGENTS.md` as literal text — an `@CLAUDE.md` line in `AGENTS.md` would just render as dead text to Codex/Cursor/etc. **The dependency can only flow one direction: the portable file must be the plain one (`AGENTS.md`), and the vendor file (`CLAUDE.md`) imports it.**

This is exactly the pattern already used by **Nethermind** (`.claude/repo-references/clients/nethermind/` — already vendored in this repo as a reference client): their `.claude/CLAUDE.md` is a *one-line file*:

```
@../AGENTS.md
```

`AGENTS.md` is Nethermind's real, substantive context file (repo structure, build/test, coding style, a `.agents/rules/*.md` reference library analogous to fukuii's `.claude/agent-protocols/*.md`), and `CLAUDE.md` does nothing but import it. Nethermind is a directly comparable production Ethereum execution client, so this isn't a toy example — it's a sibling project solving the identical problem.

**Fukuii's situation is the mirror image of Nethermind's**: fukuii's `CLAUDE.md` is the mature, actively-maintained file; `AGENTS.md` doesn't exist yet. So the move isn't "delete CLAUDE.md's content and import it back" wholesale — most of `CLAUDE.md`'s content is genuinely Claude Code-specific (named subagents that only Claude Code can invoke, Spec Kit slash commands, `.claude/agent-protocols/` cross-references, the continuation-file protocol, sprint tooling paths). Instead:

1. **Extract the tool-agnostic subset of `CLAUDE.md`** into a new `AGENTS.md` — verbatim where possible, since it's already well-written. This is roughly the top half of the current file (see §2 below for the exact split).
2. **Replace that material in `CLAUDE.md` with `@AGENTS.md`** at the top, followed by everything that's genuinely Claude Code-specific.
3. Going forward, editing tool-agnostic facts (build commands, ETC vs ETH semantics, working discipline) happens in exactly one place (`AGENTS.md`); editing Claude-only orchestration (subagent routing, speckit, sprint tooling) happens in `CLAUDE.md`. No dual-editing, no drift — the import guarantees Claude Code always sees both.
4. As part of the same pass, fix the two now-provably-stale docs: strip or rewrite the "Guidelines for LLM Agents" section of `docs/development/contributing.md` to point at `AGENTS.md`/`CLAUDE.md` instead of re-describing the agent roster, and refresh `.github/AGENT_LABELS.md`'s label table (or explicitly mark it historical/GitHub-issue-labeling-only, decoupled from the live subagent roster).

This turns "two files that can drift" into "one primary portable file + one thin vendor overlay that structurally cannot drift, plus a one-time cleanup of two files that already have drifted."

### Why not `.github/copilot-instructions.md`

Three independent, current findings converge on "skip it":

- **GitHub's Copilot coding agent already reads `AGENTS.md` natively** — confirmed via GitHub's own changelog: ["Copilot coding agent now supports AGENTS.md custom instructions"](https://github.blog/changelog/2025-08-28-copilot-coding-agent-now-supports-agents-md-custom-instructions/) (2025-08-28). Once fukuii has an `AGENTS.md`, Copilot's coding agent picks it up with zero extra file.
- **Copilot's coding agent additionally reads `CLAUDE.md` and `GEMINI.md` directly**, per the same GitHub documentation trail (confirmed via search of GitHub's docs/changelog ecosystem, not just a blog aggregator) — so even today, before any `AGENTS.md` exists, Copilot is not context-blind on this repo.
- The repo's existing `.github/copilot/` directory is scoped narrowly to **MCP server wiring** (`.github/copilot/mcp.json`, `.github/copilot/README.md` — exposing the running Fukuii node's JSON-RPC as MCP tools/resources for Copilot to call). That's a different concern from "what are the project's conventions" and doesn't need to be folded into a new instructions file; it stays as-is.

Adding `.github/copilot-instructions.md` on top of `AGENTS.md` would reintroduce the exact duplication problem AGENTS.md is meant to solve, for a tool that already reads AGENTS.md directly. Only reconsider this if a future need arises for Copilot-specific behavior that must diverge from the shared `AGENTS.md`/`CLAUDE.md` content (rare, and better handled with a short, clearly-scoped addendum than a full parallel file).

---

## 2. Proposed structure (if this is greenlit as a follow-up)

### `AGENTS.md` (new, plain markdown, portable, no Claude-specific syntax except the reverse dependency doesn't exist — this file must stay tool-neutral)

Move from `CLAUDE.md` (condensed/reworded only where it currently assumes a Claude Code audience):

| Section | Source in current `CLAUDE.md` | Notes |
|---|---|---|
| Project overview (multi-network EVM client, Mantis fork, package rebrand) | Lines 1–4 | As-is |
| ETC vs ETH — read this first | Lines 12–24 | As-is — this is pure domain knowledge, not Claude-specific |
| Build & test commands table | Lines 25–55 | As-is, this is the single highest-value table for *any* agent (Codex, Cursor, Aider) touching this repo — currently invisible to them |
| Working discipline (sequential thinking, small batches, Chesterton's Fence, root cause not symptom, fail loudly, irreversible=10x thought) | Lines 138–157 | As-is — none of this mentions Claude-specific tooling |
| Conventions (git add specifics, scalafmtAll before commit vs formatAll pre-PR only, "refer to the human as user") | Lines 212–217 | As-is |
| Module layout | Line 54–55 (folded into build table) | Keep |
| Pointer to `docs/development/contributing.md` for full contributor workflow (fork/clone, pre-commit hooks, PR process) | New | This content already lives in `contributing.md` — don't duplicate it, just link, since that file already has broad tool-agnostic value and is human+agent-facing today |

Stays **out** of `AGENTS.md` (too Claude Code-specific to be actionable by another tool, or would mislead one):
- The subagent routing table (`forge`, `beacon`, `eye`, …) and the Consensus-Critical Change Protocol that depends on invoking them by name — Codex/Cursor/Aider have no mechanism to "call the forge subagent."
- `.claude/agent-protocols/*.md` references — these encode process that assumes a Claude Code main-session/subagent split.
- Continuation-file protocol (`.local/docs/continuations/`), OODA loop section, Spec Kit slash-command workflow, sprint tracking paths (`.claude/sprints/`) — all Claude-Code-workflow-specific.

### `CLAUDE.md` (rewritten, thin header + everything Claude-specific)

```markdown
# CLAUDE.md — Working in fukuii

@AGENTS.md

## Shared agent protocols
...(unchanged)

## Specialist subagents
...(unchanged — table + Consensus-Critical Change Protocol)

## Continuation protocol
...(unchanged)

## OODA loop for large migrations
...(unchanged)

## Spec-Driven Development (Spec Kit)
...(unchanged, including the SPECKIT START/END managed block)
```

Net effect: `CLAUDE.md` shrinks to roughly its bottom half; `AGENTS.md` becomes the new home for the top half, verbatim. Total content is not smaller, but it is no longer possible for the two files to say different things about build commands or ETC/ETH semantics, because there's only one copy.

### What stays in `docs/development/contributing.md`, unchanged

- Prerequisites, fork/clone/submodule setup, pre-commit hook recipes, Scalafmt/Scalafix/Scapegoat/Scoverage detail, async-testing patterns (`eventually` vs `Thread.sleep`), Cats Effect `IO`/`Status.Failure` pattern, release process, CI checklist. None of this is agent-routing content — it's contributor documentation that happens to also help agents, and it's already tool-agnostic and current.

**Rewrite/remove:** the "Guidelines for LLM Agents" section (lines 546–671). Replace with 2–3 sentences: "See `AGENTS.md` (portable) and `CLAUDE.md` (Claude Code-specific orchestration) at the repo root for AI agent guidance." This removes the stale wraith/mithril/ICE/eye/forge/herald/Morgoth roster and the now-duplicate "Quality Checklist" / "Rules" / "Prompts for Common Tasks" content that overlaps both `AGENTS.md`'s build-command table and the actual contributing-guide sections above it in the same file.

**Also update:** `.github/AGENT_LABELS.md`'s "Related Documentation" pointer is already half-correct (it notes Morgoth/ICE moved into `CLAUDE.md`) — but the label reference table itself (7 agents) should either be regenerated from the current 12-agent roster or explicitly scoped as "GitHub issue/PR labeling only, not a 1:1 mirror of `.claude/agents/`" so nobody mistakes it for a live agent list again.

---

## 3. External research findings

### 3.1 Claude Code's actual, current AGENTS.md behavior (verified against official docs)

Checked directly against [Claude Code's memory documentation](https://code.claude.com/docs/en/memory), not blog aggregators:

- Claude Code does **not** auto-read `AGENTS.md` as a fallback when `CLAUDE.md` is absent, and does not merge the two automatically. Native AGENTS.md support is an open feature request (`anthropics/claude-code` issue #34235), not shipped.
- The `@path` import syntax **is** officially documented and works for `@AGENTS.md` specifically — Anthropic's own recommended pattern is:
  ```markdown
  @AGENTS.md

  ## Claude Code
  [Claude-specific overrides]
  ```
- Symlinking (`ln -s AGENTS.md CLAUDE.md`) works normally on macOS/Linux (this machine); on Windows it needs Developer Mode or admin rights, so the import line is the more portable mechanism. This doesn't matter for fukuii specifically (Linux dev environment) but is worth knowing if contributors are on Windows.
- `/init` (Claude Code's project scaffolder) already reads an existing `AGENTS.md` and incorporates it when generating `CLAUDE.md` — further evidence this is the sanctioned direction of information flow (`AGENTS.md` → `CLAUDE.md`, not the reverse).

**Caution flagged during this research:** a first pass of web search surfaced several confident-sounding domains (`buildthisnow.com`, `blink.new/blog`, `thepromptshelf.dev`, `agyn.io`, `hivetrail.com`, `termdock.com`, `bestagent.dev`) making near-identical claims about AGENTS.md/CLAUDE.md — these read as SEO content farms (generic "definitive guide" framing, no primary sourcing, suspiciously synchronized publication dates around the same query). Their substantive claims (import pattern, symlink direction, "don't duplicate") turned out to be *directionally correct* once cross-checked against the official docs and a GitHub gist referencing a real, high-engagement `anthropics/claude-code` issue thread (#6235, 5,200+ reactions per the gist author) — but they should not be treated as sources on their own. Recommend treating any single-source claim from this cluster of sites as unverified until cross-checked.

### 3.2 Governance and adoption of AGENTS.md as a standard

This part checks out against clearly primary, first-party sources:

- **AGENTS.md is now governed by the Agentic AI Foundation (AAIF)**, a directed fund under the Linux Foundation, announced **2025-12-09**. AAIF's three founding project contributions were Anthropic's **MCP**, Block's **goose**, and OpenAI's **AGENTS.md**. Sources: [Linux Foundation press release](https://www.linuxfoundation.org/press/linux-foundation-announces-the-formation-of-the-agentic-ai-foundation), [OpenAI's own announcement](https://openai.com/index/agentic-ai-foundation/), [Anthropic's own announcement](https://www.anthropic.com/news/donating-the-model-context-protocol-and-establishing-of-the-agentic-ai-foundation), [MCP's blog confirming the same](https://blog.modelcontextprotocol.io/posts/2025-12-09-mcp-joins-agentic-ai-foundation/).
- Platinum AAIF members include AWS, **Anthropic**, Block, Bloomberg, Cloudflare, Google, Microsoft, and OpenAI — i.e., Anthropic itself is a governing member of the body that now stewards AGENTS.md, which is a meaningful signal this isn't a competitor-only standard Claude Code will be slow to interoperate with.
- Per the [official agents.md site](https://agents.md) (now AAIF-stewarded): 20+ platforms document support, including OpenAI Codex, Gemini CLI, Jules, VS Code, JetBrains Junie, GitHub Copilot, Cursor, and Aider. The site reports "over 60,000 open-source projects" using the format — **this specific number is self-reported by the standard's own site and should be treated as a promotional figure, not an independently audited stat** (flagging as speculation/unverified-precision, though directionally the format is clearly widely adopted).
- **GitHub Copilot coding agent added AGENTS.md support 2025-08-28**, confirmed via [GitHub's own changelog](https://github.blog/changelog/2025-08-28-copilot-coding-agent-now-supports-agents-md-custom-instructions/) — a first-party, dated, verifiable source (higher confidence than the general adoption list above).

### 3.3 Real-world pattern: what public repos with both files actually do

Found via search and by reading vendored copies already in this repo at `fukuii/.claude/repo-references/`:

- **Nethermind** (`.claude/repo-references/clients/nethermind/`, already vendored here as fukuii's own reference client) — **AGENTS.md-primary pattern**. `AGENTS.md` at repo root is the full, substantive context file (repo structure, coding style, a `.agents/rules/*.md` folder for domain-specific detail — direct structural analog to fukuii's `.claude/agent-protocols/`). `.claude/CLAUDE.md` is a **one-line file**: `@../AGENTS.md`. This is the cleanest real-world precedent for "thin overlay imports the portable file," and it's from a directly comparable project (production Ethereum execution client, C#/.NET instead of Scala/Pekko, but same domain).
- **ethereum/consensus-specs** (`.claude/repo-references/ethereum/consensus-specs/AGENTS.md`, 235 lines) and **scala/scala3** (`.claude/repo-references/scala3/AGENTS.md`, 72 lines) — both AGENTS.md-only (no CLAUDE.md found in the vendored copies), self-contained, comprehensive rather than thin-pointer files. Scala3's is notably short and just points into `CONTRIBUTING.md` for a "Forbidden" section rather than duplicating it — same "link, don't copy" discipline recommended above for fukuii's `docs/development/contributing.md`.
- **openai/codex** (`openai/codex/AGENTS.md`, ~320 lines, checked live via WebFetch) — comprehensive and self-contained (Rust/TS conventions, code review rules, test conventions) rather than a thin pointer. No CLAUDE.md alongside it (Codex is OpenAI's own tool, expected).
- **Community-documented symlink pattern** (`carlrannaberg/claudekit`, and a referenced-but-unverified `kedro-org/kedro` issue proposing the same for "all Kedro ecosystem repositories") — `CLAUDE.md` as a **symlink** to `AGENTS.md` (`ln -s AGENTS.md CLAUDE.md`), functionally equivalent to the import pattern but literally the same bytes rather than an import directive. SSW.com.au's public engineering rules page recommends this same symlink direction and adds one caution worth carrying into fukuii's cleanup: **don't** symlink the entire `.claude/` directory alongside it — Claude Code-specific config (`settings.json`, `settings.local.json`, and by extension fukuii's `.claude/agents/`, `.claude/skills/`, `.claude/sprints/`) has no equivalent in other tools and shouldn't be forced into a shared location.

**Overall pattern across every real example found: nobody maintains two independently-authored files with overlapping content.** It's either (a) thin-import/symlink from the vendor file to the portable file (Nethermind, claudekit, SSW's stated rule), or (b) AGENTS.md-only with no vendor file at all (consensus-specs, scala3, codex). Fukuii's proposed structure in §2 is (a), which is the right choice specifically *because* `CLAUDE.md` already carries load-bearing Claude-only content (subagents, Spec Kit) that (b) can't accommodate without losing that tooling.

---

## 4. Findings general enough for the best-practices library

These are not fukuii-specific and should be mirrored into `.local/docs/best-practices/` (or the global `/media/dev/2tb/dev/claude-global-settings/rules/` library, since this pattern will recur on every public repo with a rich CLAUDE.md):

1. **The import direction is fixed, not a style choice.** `AGENTS.md` must be the plain-text, tool-neutral file; `CLAUDE.md` (or any other vendor file) imports it via `@AGENTS.md` or a symlink. The reverse doesn't work because only Claude Code resolves `@path` imports — every other tool reading `AGENTS.md` sees literal, unexpanded text. This is a durable technical constraint, not a preference, and should inform any future "which file is primary" decision on any project, not just fukuii.
2. **`@AGENTS.md` import is official, documented Claude Code behavior**, not a community workaround (verified against `code.claude.com/docs/en/memory`, 2026-07). Worth adding to `claude-global-settings/rules/` as a known-good pattern for any project maintaining both files, rather than re-discovering it per-project.
3. **GitHub Copilot's coding agent reads `CLAUDE.md`, `GEMINI.md`, and `AGENTS.md` natively**, confirmed via GitHub's own 2025-08-28 changelog entry — meaning on many repos, a dedicated `.github/copilot-instructions.md` is now redundant work rather than a required addition. Check this before recommending `.github/copilot-instructions.md` as a default scaffolding step for new projects (currently listed as a standard step in `claude-global-settings/rules/agentic-setup.md` — worth revisiting given this finding).
4. **Stale multi-file agent-guidance is a real, observable failure mode, not a hypothetical.** Fukuii's own `docs/development/contributing.md` "Guidelines for LLM Agents" section had already drifted from the authoritative `CLAUDE.md` (referencing a retired agent roster) before this research even started — a concrete example worth citing when arguing for single-source-of-truth patterns on other projects.
5. **AGENTS.md now has real institutional backing** (AAIF under the Linux Foundation, with Anthropic itself as a platinum member) as of December 2025 — this de-risks adopting it on any project; it is not a competitor-controlled format Claude Code is likely to under-support long-term.
6. **When splitting a CLAUDE.md, the split criterion that held up across every real example is "would a tool other than Claude Code be able to act on this instruction?"** — routing tables to named subagents, slash-command workflows (Spec Kit), and continuation-file protocols tied to a specific harness's session model all fail that test and belong in the vendor file; build commands, domain semantics, and general working discipline (small batches, Chesterton's Fence, fail loudly) pass it and belong in `AGENTS.md`.

---

## Next step (not part of this doc)

If this recommendation is accepted: create `AGENTS.md` with the §2 content split, rewrite `CLAUDE.md`'s top section to `@AGENTS.md` + Claude-specific material, and clean up the stale agent-roster references in `docs/development/contributing.md` and `.github/AGENT_LABELS.md`. Do not add `.github/copilot-instructions.md`.
