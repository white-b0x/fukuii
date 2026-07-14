# Reth — Agentic Tooling Patterns

Source: `.claude/repo-references/clients/reth/` (vendored full clone, verified genuine —
`git remote -v` shows `origin` = `https://github.com/white-b0x/reth.git` (a fork),
`upstream` = `https://github.com/paradigmxyz/reth.git`; `git log --oneline -3` shows real
recent merged PRs, e.g. `3d76b93c2 fix(engine): use execution version header for SSZ
routes (#25925)`)

## Summary

Reth inverts the pattern seen in Nethermind and Erigon. Both of those repos build a
*mechanism-heavy* stack around a thin root doc: Nethermind ships 9 `.agents/rules/*.md`
files plus a CI-integrated `claude-review.yml` bot; Erigon ships per-subsystem
`agents.md` breadcrumbs, `.claude/hooks/`, a shipped node-level MCP server, and its own
`claude.yml` review bot. Reth has **none of that mechanism** — no `.claude/` directory at
the repo root at all, no skills, no hooks, no MCP config, no CI job that mentions an LLM
by name — but its single root file, `AGENTS.md` (549 lines, read in full for this
document), is unusually *prose-rich*: real linked PR numbers for six contribution
patterns, a fully worked-out "Opening PRs" section with good/bad description examples,
an explicit commenting philosophy with paired ❌/✅ code blocks, and a Rust-specific
type-ordering convention tied to a real regression PR (#22133).

The contrast to hold in mind throughout this document: Nethermind and Erigon invested in
*automation surface area* (bots, hooks, skills, MCP); Reth invested almost entirely in
*written judgment* — the kind of guidance that only pays off if an agent actually reads
the whole file rather than skimming section headers. The one place Reth's mechanism
shows real strength is `HARDFORK-CHECKLIST.md` — 27 lines, but the single most
directly-portable artifact found across any of the three client audits, because it names
exact crates and traits rather than describing a process abstractly.

## AGENTS.md / CLAUDE.md-as-symlink — a deliberate single-file philosophy

```
$ readlink CLAUDE.md
AGENTS.md
```

`CLAUDE.md` at the repo root is a literal symlink to `AGENTS.md` (confirmed via
`readlink`, and via `ls -la`: `lrwxrwxrwx 1 dev dev 9 ... CLAUDE.md -> AGENTS.md`). There
is no Claude-specific content anywhere at the root — Claude Code and any other
AGENTS.md-aware tool (Codex, Cursor, Aider, Copilot via its own convention) read
byte-identical content. This is the opposite end of the spectrum from fukuii's own
current split: fukuii's `CLAUDE.md` imports `AGENTS.md` via `@AGENTS.md` (a Claude-Code-
only include directive) and then layers ~120 lines of genuinely Claude-specific
orchestration on top — the subagent roster table, the Consensus-Critical Change
Protocol, the OODA loop, Spec Kit wiring, the continuation-file protocol. Reth's
approach has zero layering: one file, two names pointing at it.

**Neither approach is wrong; they optimize for different things.** Reth's symlink
guarantees the portable and the tool-specific content can never drift apart, because
they are the same bytes — there is no possibility of the "Claude-only" layer silently
duplicating or contradicting the "portable" layer, a failure mode fukuii's own
`docs/agentic-tooling/agents-md-decision-2026.md` explicitly reasons about (per fukuii's
`CLAUDE.md` header comment, `@AGENTS.md` import only resolves for Claude Code, so the
dependency direction is deliberate and one-way). The cost of Reth's approach is that it
cannot express content that is *only* relevant to one tool's mechanism — Reth has no
subagent roster, no protocol table, no skill index to describe, so the symlink costs it
nothing. If Reth ever adopted named subagents or a skill system, the single-file model
would force that content into the portable file too, diluting it for Codex/Cursor
readers who have no equivalent construct. fukuii's split exists precisely because it
*does* have that Claude-specific mechanism (12 subagents, a looping harness, Spec Kit);
Reth's single file exists precisely because it doesn't.

### Structure of AGENTS.md (549 lines, in full)

The file has one clear internal shape: architecture orientation → workflow commands →
six PR-pattern examples → testing/performance/pitfalls → CI requirements → a full
"Opening PRs" playbook → commenting philosophy → a Rust style convention → one worked
example end-to-end → a quick-reference command block. Every section below cites the
actual line numbers.

**Architecture Overview by crate** (`AGENTS.md:9–28`) lists nine components with their
crate paths and one-line responsibilities:

| # | Component | Crate path | Line |
|---|-----------|-----------|------|
| 1 | Consensus | `crates/consensus/` | 13 |
| 2 | Storage (MDBX + static files) | `crates/storage/` | 14 |
| 3 | Networking | `crates/net/` | 15 |
| 4 | RPC | `crates/rpc/` | 16 |
| 5 | Execution | `crates/evm/`, `crates/ethereum/` | 17 |
| 6 | Pipeline (staged sync) | `crates/stages/` | 18 |
| 7 | Trie (parallel state root) | `crates/trie/` | 19 |
| 8 | Node Builder | `crates/node/` | 20 |
| 9 | Consensus Engine (Engine API) | `crates/engine/` | 21 |

This list is curated, not exhaustive — `ls crates/` in the vendored clone shows 40
top-level crate directories (`chainspec`, `chain-state`, `cli`, `config`, `consensus`,
`e2e-test-utils`, `engine`, `era`, `era-downloader`, `era-utils`, `errors`, `ethereum`,
`etl`, `evm`, `exex`, `fs-util`, `metrics`, `net`, `node`, `payload`, `prune`, `revm`,
`rpc`, `stages`, `static-file`, `storage`, `tasks`, `tokio-util`, `tracing`,
`tracing-otlp`, `transaction-pool`, `trie`, …). The nine chosen are the crates an agent
is statistically most likely to touch or need to reason about — a deliberate
"orientation map," not a directory listing. It is immediately followed by four "Key
Design Principles" (`AGENTS.md:23–28`: Modularity, Performance, Extensibility, Type
Safety) stated as one-line bullets with no elaboration — these read as context for why
the codebase is organized the way it is, not actionable instructions.

**Code Style and Standards** (`AGENTS.md:32–47`) gives three exact commands with no
elaboration beyond the command itself:
- Formatting: `cargo +nightly fmt --all` (line 36) — nightly rustfmt is required, a
  detail that would silently fail if an agent ran stable `cargo fmt`.
- Linting: `cargo +nightly clippy --workspace --lib --examples --tests --benches
  --all-features` (line 41).
- Testing: `cargo nextest run --workspace` (line 46) — nextest, not `cargo test`,
  for speed.

### Common Contribution Types — six real, PR-linked examples (`AGENTS.md:49–115`)

This is the section's centerpiece and its most distinctive feature versus Nethermind
and Erigon: every pattern is illustrated with an actual diff snippet and a real PR
number, not a synthetic example.

1. **Small Bug Fixes (1–10 lines)** (line 53) — [#16767](https://github.com/paradigmxyz/reth/pull/16767),
   a one-line fix changing `parent.parent_beacon_block_root()` to
   `parent.parent_beacon_block_root().map(|_| B256::ZERO)` (lines 56–59).
2. **Integration with Upstream Changes** (line 61) — [#16752](https://github.com/paradigmxyz/reth/pull/16752),
   replacing a boolean fork-check (`is_shanghai_activated()`) with a value returned
   from the fork tracker (`max_initcode_size()`) after a revm API change (lines 64–69).
3. **Adding Comprehensive Tests** (line 71) — [#16759](https://github.com/paradigmxyz/reth/pull/16759),
   an ETH69 protocol connectivity test using `tokio::test(flavor = "multi_thread")`
   (lines 74–80).
4. **Making Components Generic** (line 82) — [#16758](https://github.com/paradigmxyz/reth/pull/16758),
   `EthEvmConfig<EvmFactory>` hardcoded to `ChainSpec` becomes
   `EthEvmConfig<C = ChainSpec, EvmFactory>` with a `C: EthereumHardforks` trait bound
   (lines 84–95) — this is the same "make it generic over chain spec" refactor pattern
   fukuii's own multi-EVM architecture (`OlympiaOpCodes`/`OsakaOpCodes` per network
   family) already committed to independently.
5. **Resource Management Improvements** (line 97) — [#16770](https://github.com/paradigmxyz/reth/pull/16770),
   adding `fs::remove_dir_all(&etl_path)` cleanup on launch with a `warn!` on failure
   (lines 100–104).
6. **Feature Additions** (line 106) — [#16756](https://github.com/paradigmxyz/reth/pull/16756),
   a new `ShardedMempoolAnnouncementFilter<T>` struct for transaction announcement
   filtering (lines 108–115).

The rationale for this structure is implicit rather than stated: it teaches an agent
*what a typical accepted Reth PR actually looks like* by diff shape, rather than
prescribing style rules in the abstract. This is closer to few-shot prompting than to a
style guide.

### Testing Guidelines, Performance, Pitfalls (`AGENTS.md:117–155`)

Five testing categories are named without elaboration (Unit, Integration, Benchmarks,
Fuzz, Property — line 119–123), followed by a generic example test-module skeleton
(lines 125–143) using `#[cfg(test)] mod tests { ... }` / Arrange-Act-Assert comments —
notably the *only* fabricated (non-PR-linked) code example in the file, in contrast to
the six real-PR examples above it.

Performance Considerations (`AGENTS.md:145–150`): avoid allocations in hot paths, use
`rayon` for CPU-bound parallel work, `tokio` for I/O-bound async, and — a Reth-specific
detail an agent would not otherwise know — use `reth_fs_util` instead of `std::fs` "for
better error handling" (line 150).

Common Pitfalls (`AGENTS.md:152–155`): don't block async tasks — use `spawn_blocking`
for CPU-intensive or blocking-I/O-heavy work (line 154); use `?` and proper error types
(line 155).

### What to Avoid (`AGENTS.md:157–165`)

Five explicit anti-patterns, the last of which names an exact vendored path:

```
5. **Modifying libmdbx sources**: Never modify files in
   `crates/storage/libmdbx-rs/mdbx-sys/libmdbx/` - this is vendored third-party code
```

Verified in the vendored clone: `crates/storage/libmdbx-rs/mdbx-sys/` contains
`build.rs`, `Cargo.toml`, `libmdbx` (the vendored C sources), and `src`. This is the
same class of guidance as fukuii's own note in `AGENTS.md` about never modifying
`.claude/agent-protocols/` symlinks directly (edit the canonical `.agents/protocols/`
target instead) — both are "this looks like normal source but isn't; edit somewhere
else" warnings that only a written note (not a linter) can catch reliably, since the
files are otherwise ordinary-looking source in the tree.

### CI Requirements (`AGENTS.md:167–176`)

Six items to check before submitting: format check (`cargo +nightly fmt --all --check`),
clippy with zero warnings, all tests passing, updated doc comments (with `cargo docs
--document-private-items`), CLI docs regeneration if the CLI changed (see next), and
Conventional Commit-format commit messages.

### CLI Reference Docs — auto-generated docs, CI-enforced (`AGENTS.md:178–190`)

This is a distinct, well-engineered pattern worth calling out on its own: the CLI
reference pages under `docs/vocs/docs/pages/cli/` are generated from `reth --help`
output via `make update-book-cli`, which builds `reth` in debug mode and runs
`docs/cli/update.sh` (`AGENTS.md:182–188`; confirmed in `Makefile:223–226`:
`update-book-cli: build-debug` runs `./docs/cli/update.sh
$(CARGO_TARGET_DIR)/debug/reth`). The `book` CI job — confirmed as
`.github/workflows/lint.yml` per `AGENTS.md:190`, and independently as `book.yml`
existing at `.github/workflows/book.yml` (which builds Vocs docs, including running
`bash scripts/build-cargo-docs.sh`) — regenerates the docs and runs `git diff
--exit-code`, failing the build if committed docs don't match freshly generated output.
The instruction to the agent is unambiguous: "Manually editing these pages is never
productive — always use `make update-book-cli`" (`AGENTS.md:190`). This is a portable
idea fukuii doesn't currently have an equivalent for (fukuii has no CLI-flags-to-docs
generation step), but the general pattern — CI diffs a regenerated artifact against the
committed copy to prevent silent drift — is a reusable primitive.

### Opening PRs against paradigmxyz/reth (`AGENTS.md:192–272`) — the fullest section

**Titles** (lines 194–209): Conventional Commits with an optional scope,
`<type>(<scope>): <short description>`. Types: `feat`, `fix`, `perf`, `refactor`,
`docs`, `test`, `chore`. Scope is crate/area (`evm`, `trie`, `rpc`, `engine`, `net`).
Three concrete examples given verbatim:
```
fix(rpc): correct gas estimation for ERC-20 transfers
perf: batch trie updates to reduce cursor overhead
feat(engine): add new_payload_interval metric
```
This is independently enforced by CI: `.github/workflows/pr-title.yml` runs
`amannn/action-semantic-pull-request` and posts a sticky PR comment on failure with its
own example list (`feat`, `fix`, `chore`, `test`, `bench`, `perf`, `refactor`, `docs`,
`ci`, `revert`, `deps` — a superset of AGENTS.md's list, notably adding `bench`, `ci`,
`revert`, `deps`). The workflow step is `continue-on-error: true` and a separate step
explicitly fails the job afterward (`pr-title.yml`, step "Fail workflow if title
invalid") — a two-step pattern that lets the bot post/retract a helpful comment
independent of the hard gate.

**Descriptions** (lines 211–245): "Keep it short. Say what changed and why — nothing
more." The Do list: 1–3 sentences, explain *why* if not obvious, link issues/EIPs,
include benchmark numbers for perf changes. The Don't list, verbatim, is unusually
specific about anti-patterns other clients don't call out explicitly:
```
- List every file changed — that's what the diff is for
- Repeat the title in the body
- Add "Files changed" or "Changes" sections
- Write walls of text that go stale when the diff is updated
- Use filler like "This PR introduces...", "comprehensive", "robust", "enhance", "leverage"
```
The last bullet — banning specific hedge/filler words — is a directly reusable idea:
it's the kind of thing that catches LLM-flavored PR prose specifically (an agent
drafting a PR description is exactly the failure mode this guards against). The
good/bad example pair (lines 238–262) contrasts a 2-sentence description against a
`## Summary` / `## Changes` / `## Files Changed` template — the bad example is
structurally identical to what an unguided agent tends to produce by default (bulleted
file lists, a "Summary" header, filler like "comprehensive improvements").

**Labels and CI** (lines 264–272):
```
* when changes are RPC related, add A-rpc label
* when changes are docs related, add C-docs label
* ... and so on, check the available labels for more options.
* if being tasked to open a pr, ensure that all changes are properly formatted:
  `cargo +nightly fmt --all`

If changes in reth include changes to dependencies, run commands `zepter` and
`make lint-toml` before finalizing the pr. Assume `zepter` binary is installed.
```
The instruction is deliberately open-ended ("check the available labels") rather than
enumerating the full label taxonomy — it delegates discovery to the agent rather than
maintaining a list that would drift. Labeling itself is partially automated:
`.github/workflows/label-pr.yml` triggers on PR open and runs
`.github/scripts/label_pr.js`, which is pure deterministic JS (regex-matches a
`closes #NNN` reference in the PR body, fetches that issue's labels, and copies a
filtered subset onto the PR — filtering out `S-*` status labels, `C-tracking-issue`,
`M-prevent-stale`, and `D-*` difficulty labels). This is issue→PR label propagation, not
content-based classification — there is no LLM in the loop. `zepter` and `make
lint-toml` are real: `Makefile:268–284` defines `lint-toml: ensure-dprint` running
`dprint fmt` on TOML files, with `ensure-dprint` checking the binary is installed and
printing an install command if not.

### Debugging Tips, Finding Where to Contribute (`AGENTS.md:274–294`)

Debugging: use `tracing::debug!(target: "reth::component", ?value, "description")`
(line 278); add metrics via `metrics::counter!("reth_component_operations").increment(1)`
(line 283); use separate test databases/directories for isolation (line 286). Finding
contributions: check `good-first-issue`/`help-wanted` labels, search `TODO` comments,
improve low-coverage tests, improve docs, profile hot paths with benchmarks
(lines 290–294).

### Common PR Patterns — a second, unlinked list (`AGENTS.md:296–322`)

A structural quirk worth flagging: this section restates contribution-pattern
categories a second time, but *without* the PR links or real diffs the earlier section
(`AGENTS.md:49–115`) provided — "Small, Focused Changes," "Integration Work," "Test
Improvements," "Making Code More Generic" are near-duplicates of items 1, 2, 3, and 4
above, generalized back into abstract descriptions. This reads as the file having been
assembled incrementally (the PR-linked section added later without removing or merging
the earlier generic pass) rather than as two deliberately distinct sections — a small
authoring artifact, not a load-bearing convention, but worth noting since it's the kind
of duplication that accumulates in hand-maintained agent-context files over time.

### Commenting philosophy (`AGENTS.md:324–401`) — the most developed section in the file

"Write comments that remain valuable after the PR is merged. Future readers won't have
PR context - they only see the current code" (line 326).

**✅ DO: Add Value** — three code examples, each pairing a rule with a real snippet:
- Explain WHY / non-obvious behavior — an `unsafe impl GlobalAlloc` comment explaining
  atomicity requirements between dealloc-on-drop and concurrent limit checks
  (lines 330–334); a binary-search precondition comment (line 336); a magic-number
  rationale (`TRACER_TIMEOUT` = 5s "to match EVM block processing limits", line 340).
- Document constraints/assumptions — a doc comment noting `deep_size_of` "may
  undercount shared references (Rc/Arc)" (lines 343–350).
- Explain complex logic — a thread-local reset comment explaining tokio's
  `spawn_blocking` pool thread reuse causing state bleed across tasks without an
  explicit reset (lines 352–357).

**❌ DON'T: Describe Changes** — three paired bad/good examples (lines 360–384):
change-description comments ("Changed from Vec to HashMap...") versus decision-rationale
comments ("HashMap provides O(1) symbol lookups during trace replay"); PR-specific
context ("Fix for issue #234...") versus behavior documentation; and stating-the-obvious
comments ("Increment counter") versus purpose documentation.

The comment/don't-comment checklist (lines 386–395) and the closing heuristic (lines
398–400) crystallize the whole section into one question:

> Before adding a comment, ask: Would someone reading just the current code (no PR, no
> history) find this helpful?

This "Test: Will this make sense in 6 months?" framing is the single most portable idea
in the commenting section — it's a test an agent can actually apply mechanically while
drafting a comment, rather than a vague "write good comments" instruction.

### Rust Style Guides — Type Ordering in Files (`AGENTS.md:403–469`)

A convention specific to Rust's file-as-module structure: the file's primary type
(matching the filename) comes first, then public auxiliary types, then public traits,
then private helper types, then private helper functions (lines 407–436, with a full
annotated code skeleton).

The bad/good contrast is tied to a real regression, `#22133`
(`AGENTS.md:438`) — new auxiliary types (`CacheWaitDurations`) and a new trait
(`WaitForCaches`) were added *above* the file's primary type (`PayloadProcessor`),
burying it. The fix (lines 453–469) moves the new additions below the primary type and
its `impl` block. This is the kind of convention that has no compiler or linter to
enforce it — it depends entirely on an agent (or reviewer) knowing the rule and a real
past mistake to anchor it to.

### Example Contribution Workflow (`AGENTS.md:471–519`)

A single worked example, start to finish: branch (`git checkout -b
fix-external-ip-resolution`), search (`rg "external.*ip" --type rust`), fix
(`nat::external_ip().or_else(...).or_else(...)` fallback chain in
`crates/net/discv4/src/lib.rs`), test, run checks (`cargo +nightly fmt --all`, `cargo
clippy --workspace --all-features` with the comment "Make sure WHOLE WORKSPACE
compiles!" at line 508, `cargo nextest run -p reth-discv4`), then commit with a
multi-line message explaining the before/after behavior. This mirrors the working style
fukuii's own `AGENTS.md` prescribes under "Working discipline" (small batches, then
checkpoint; verify reality matches your model) but expressed as one concrete narrative
example rather than as abstract principles.

### Quick Reference (`AGENTS.md:521–549`)

A closing command block repeating the essential commands already given inline —
format, clippy, nextest, `cargo bench`, `cargo build --release`, `cargo check
--workspace --all-features`, `cargo docs --document-private-items`, and
`make update-book-cli`. Functionally a cheat-sheet appendix; the file is long enough
(549 lines) that a compressed final block genuinely helps an agent that skimmed the
prose sections.

## `docs/vocs/CLAUDE.md` — an un-symlinked, subproject-scoped exception

Reth's "one file, two names" philosophy holds at the root but **does not extend to the
repo's subprojects.** `docs/vocs/` (the Vocs-based documentation site — confirmed via
`docs/vocs/vocs.config.ts`, `bun`-based tooling) has its own `docs/vocs/CLAUDE.md`
(102 lines, read in full) with **no `AGENTS.md` counterpart** (`ls docs/vocs/AGENTS.md`
→ `No such file or directory`) and **no symlink** — it is an ordinary, independently
authored file, git-tracked as its own artifact.

Its content is entirely subproject-scoped and has nothing to do with the root file's
Rust/crate conventions:

- **Project Overview** (lines 5–7): "the Reth documentation website built with
  [Vocs](https://vocs.dev)."
- **Repository Structure** (lines 9–18): `docs/pages/` subdirectories (`cli/`, `exex/`,
  `installation/`, `introduction/`, `jsonrpc/`, `run/`, `sdk/`), `docs/snippets/`,
  `sidebar.ts`, `vocs.config.ts`.
- **Essential Commands** (lines 20–29): `bun install`, `bun run dev`, `bun run build`,
  `bun run preview` — Bun/Vite tooling entirely distinct from the root's Cargo/Rust
  commands.
- **Development Workflow** (lines 31–56): MDX content organization, sidebar
  registration, snippet reuse, asset placement.
- **Content Guidelines** (lines 58–63): "Be Practical," "Code First," "Consistent
  Structure," "Cross-References," "Keep Current."
- **File Naming Conventions** (lines 65–69): kebab-case, URL structure mirrors file
  structure.
- **Common Tasks** (lines 71–83): three worked recipes — adding a CLI command doc,
  adding a new guide, updating code examples.
- **Development Notes** (lines 85–90): "This is a TypeScript/React project using Vocs
  framework."

The header is itself the tell: `# CLAUDE.md` / "This file provides guidance to Claude
Code (claude.ai/code) when working with code in this repository" (lines 1–3) — Reth's
own default Claude-Code-generated boilerplate header, distinct from the intentionally
tool-neutral framing of the root `AGENTS.md`'s first line ("Reth Development Guide for
AI Agents"). This is consistent with the file having been produced by running Claude
Code's own `/init`-style scaffolding *inside* `docs/vocs/` as an independent working
directory, rather than being deliberately authored as part of the root file's design.
The practical implication for a multi-subproject repo like fukuii: a monorepo's root
"single file" philosophy does not automatically propagate to subdirectories that get
their own independent Claude Code sessions — each subproject needs either an explicit
symlink back to a shared root file, or an explicit decision that it's allowed to diverge
(as `docs/vocs/CLAUDE.md` has, silently).

## Stray `docs/vocs/.claude/settings.local.json` — a gitignore-discipline cautionary tale

```
$ cat docs/vocs/.claude/settings.local.json
{
  "permissions": {
    "allow": [
      "Bash(git checkout:*)"
    ],
    "deny": []
  }
}
```

This is a **committed, git-tracked** local Claude Code session artifact — not a stray
untracked file sitting in a contributor's working copy. Confirmed with `git ls-files
docs/vocs/.claude/settings.local.json` (returns the path — it is tracked) and `git log
--all --oneline -- docs/vocs/.claude/settings.local.json`, which shows exactly one
commit: `a33be2e02 chore(docs): move to docs from book (#17096)`, authored by Yash
Atreya, dated `Fri Jun 27 18:18:45 2025 +0530`. The commit subject ("move to docs from
book") indicates this file was swept in incidentally as part of a bulk directory
reorganization, not deliberately added as project convention — nobody chose to check in
a `permissions.allow` list scoped to `Bash(git checkout:*)`; it rode along with
everything else under the old `book/` (now `docs/vocs/`) tree during the move.

**Root cause: the repo's `.gitignore` never mentions `.claude` at all.** A full read of
the root `.gitignore` (60+ lines) shows patterns for `target/`, `.idea`, `.DS_Store`,
`data/`, `proptest-regressions/`, `dist/`, `db-tools/`, `.vscode`, `lcov.info`,
`jwttoken/`, `.ccls-cache/`, and several MDBX/CMake-specific paths — but zero mention of
`.claude/`, `.claude/settings.local.json`, or any Claude/agent-tooling pattern. There is
no other `.claude/` directory anywhere else in the repo (`find . -type d -iname
".claude"` returns only this one, under `docs/vocs/`), so this isn't a case of "we
ignore it everywhere except here" — the project has simply never added the pattern, and
this is the one time it mattered.

**Why this matters for fukuii specifically:** fukuii's own root `.gitignore` discipline
(per the user's global `gitignore-security.md` rules) explicitly covers `.claude/` at
the repo root. But fukuii is itself a multi-subproject-adjacent repo with its own
docs/tooling subdirectories (`docs/research/`, `scripts/agent-tooling/`,
`.claude/repo-references/*/` — this very vendored-clone tree) — a root-level
`.gitignore` pattern for `.claude/` does not automatically protect a *nested*
subproject's own `.claude/` directory if that subproject is ever vendored, submoduled,
or spun out with its own git history and a naive `.gitignore` copy. Reth's own
`docs/vocs/` is exactly that shape: a subdirectory with independent tooling
(Bun/Vite/Vocs) and, evidently, its own ad hoc Claude Code session that nobody scoped a
gitignore rule around. The concrete lesson: **gitignore rules for agent-tooling
directories need to be verified per-subproject, not assumed to inherit from the repo
root**, especially for any subdirectory that has (or could grow) its own independent
build tooling and, consequently, its own independent Claude Code working directory.

## Confirmed absent

Systematically checked and confirmed absent from the vendored clone:

- **`.claude/skills/`, `.agents/skills/`, or any skills-equivalent directory** —
  `find . -type d -iname "skills"` returns nothing.
- **`.agents/` directory** (the canonical-source pattern fukuii itself uses for
  `.agents/protocols/` + `.agents/skills/`, symlinked into `.claude/`) — absent.
- **`.cursor/` or any Cursor-specific config** — absent.
- **Any MCP server configuration** — `grep -ril "mcp" --include="*.json"
  --include="*.toml" .` (excluding `.git/` and `target/`) returns nothing. Reth ships no
  node-level MCP server, unlike Erigon (which ships one at the node level per the
  sibling Erigon research doc).
- **Any `.specify/`-equivalent Spec-Kit framework** — no `.specify/` directory, no
  `specs/<NNN>/` convention.
- **Any CI workflow that mentions an LLM/AI tool by name** — every file under
  `.github/workflows/` (29 workflows: `bench-benchmarkoor.yml`, `bench-scheduled.yml`,
  `bench.yml`, `book.yml`, `check-alloy.yml`, `compact.yml`, `dependencies.yml`,
  `docker-tag-latest.yml`, `docker-test.yml`, `docker.yml`, `e2e.yml`,
  `fetch-grafana-dashboard.yml`, `grafana.yml`, `hive.yml`, `integration.yml`,
  `kurtosis.yml`, `label-pr.yml`, `lint-actions.yml`, `lint.yml`, `pr-audit.yml`,
  `pr-title.yml`, `release-dist.yml`, `release-reproducible.yml`, `release.yml`,
  `reproducible-build.yml`, `stage.yml`, `stale.yml`, `sync-era.yml`, `sync.yml`,
  `unit.yml`) was grepped for `claude|copilot|anthropic|openai` (case-insensitive) —
  zero matches. There is no `claude-review.yml` (Nethermind) or `claude.yml` (Erigon)
  equivalent. `pr-audit.yml` is a webhook publisher gated on a `cyclops` label — an
  internal Paradigm event-bus integration unrelated to AI review; `label-pr.yml` runs
  pure deterministic label-propagation JS (`.github/scripts/label_pr.js`, 57 lines,
  read in full — regex-matches `closes #NNN`, copies filtered issue labels onto the PR,
  no LLM call anywhere in the script).
- **A `.github/CODEOWNERS`-driven review-bot trigger** — `CODEOWNERS` exists (162 lines,
  per-crate ownership like `crates/consensus/ @mattsse @Rjected`) and is a completely
  conventional human-reviewer routing file with no automation hook into it.

What Reth *does* have that is real but unglamorous: `dependabot.yml`
(`.github/dependabot.yml`, 20 lines) configuring weekly `github-actions` and `cargo`
ecosystem updates with a `cooldown: default-days: 7` on both — the same 7-day
release-age gate fukuii's own supply-chain-security rules mandate independently, and one
data point that this is converging industry practice rather than a fukuii-specific
invention. `dependencies.yml` runs a separate scheduled `cargo update` job via a shared
reusable workflow (`tempoxyz/ci/.github/workflows/cargo-update-pr.yml@main`) gated to
`if: github.repository == 'paradigmxyz/reth'` (so forks don't spam PRs against
themselves) — again, ordinary dependency hygiene, not agentic tooling.

## HARDFORK-CHECKLIST.md — the standout portable artifact

Read in full — the entire file is 27 lines:

```markdown
# Non-exhaustive checklist for integrating new changes for an upcoming hard fork/devnet

## Introducing new EIP types or changes to primitive types

- Make required changes to primitive data structures on [alloy](https://github.com/alloy-rs/alloy)
- All new EIP data structures/constants/helpers etc. go into the `alloy-eips` crate at first.
- New transaction types go into `alloy-consensus`
- If there are changes to existing data structures, such as `Header` or `Block`, apply them to the types in
  `alloy-consensus` (e.g. new `request_hashes` field in Prague)

## Engine API

- If there are changes to the engine API (e.g. a new `engine_newPayloadVx` and `engine_getPayloadVx` pair) add the new
  types to the `alloy-rpc-types-engine` crate.
- If there are new parameters to the `engine_newPayloadVx` endpoint, add them to the `ExecutionPayloadSidecar` container
  type. This types contains all additional parameters that are required to convert an `ExecutionPayload` to an EL block.

## Reth changes

### Updates to the engine API

- Add new endpoints to the `EngineApi` trait and implement endpoints.
- Update the `ExecutionPayload` + `ExecutionPayloadSidecar` to `Block` conversion if there are any additional
  parameters.
- Update version specific validation checks in the `EngineValidator` trait.
```

### Why this is the single most valuable artifact in the whole audit

Every other piece of Reth's agentic tooling — the six PR examples, the commenting
philosophy, the type-ordering convention — teaches an agent *how to write code the Reth
maintainers will accept*. `HARDFORK-CHECKLIST.md` is different in kind: it is a
**mechanical extension-point map** for the one category of change most likely to be
attempted by an agent with incomplete context and the highest blast radius if done
wrong — hard-fork/devnet integration. It works because it names concrete traits and
crates rather than describing a process:

1. **Section 1 (primitives)** routes new EIP-driven type changes to exactly two
   external-but-Reth-adjacent crates — `alloy-eips` (new constants/helpers first) and
   `alloy-consensus` (new transaction types, and any change to existing types like
   `Header`/`Block` — with a real worked example: Prague's new `request_hashes` field).
   This tells an agent *where the change starts* even though that crate lives outside
   the `reth` repo proper (in `alloy-rs/alloy`) — an unusually honest acknowledgment
   that not all of a hard fork's surface lives in the repo being edited.
2. **Section 2 (Engine API)** names the exact pair of endpoint types
   (`engine_newPayloadVx` / `engine_getPayloadVx`) and the exact container type
   (`ExecutionPayloadSidecar`) that new Engine API parameters must be threaded through,
   again in `alloy-rpc-types-engine`.
3. **Section 3 (Reth changes proper)** is the only section scoped to the Reth repo
   itself, and names three concrete traits/conversions: the `EngineApi` trait (add new
   endpoints + implementations), the `ExecutionPayload` + `ExecutionPayloadSidecar` →
   `Block` conversion function, and the `EngineValidator` trait (version-specific
   validation checks).

The file's own title calls it "non-exhaustive" — it is explicitly a checklist, not a
specification, and makes no claim to completeness. That honesty is part of why it
works: it doesn't try to enumerate every fork-touched file (an impossible and
fast-staling list), it names the small number of *extension-point* traits/crates that
almost any fork change will need to touch, regardless of which specific EIP is being
implemented.

### A concrete sketch: what fukuii's own `hardfork-implementation-checklist.md` should name

fukuii is planning a new `hardfork-implementation-checklist.md` protocol doc this
session (a mechanical extension-point checklist, complementing the existing
process-gate doc `.agents/protocols/consensus-change-protocol.md`, which governs *when*
to stop and consult `forge`/`beacon` but does not enumerate *which classes/tables to
touch*). Using Reth's three-section shape as a template, fukuii's equivalent extension
points are:

| Reth's checklist item | fukuii's equivalent extension point |
|---|---|
| `alloy-eips` — new EIP constants/helpers | New ECIP/EIP constants added to the relevant fork-config object — `OlympiaOpCodes` (PoW/ETC, `forBlock()` dispatch) or `OsakaOpCodes` (PoS/ETH, `forTimestamp()` dispatch) — per `AGENTS.md`'s own "Do not mix these code paths" rule |
| `alloy-consensus` — new transaction types / `Header`/`Block` field changes | fukuii's domain block/transaction/header case classes — the "core domain type sweep" category `AGENTS.md`'s testing-protocol.md already treats specially (`sbt compile` between files, not `compile-all`, because these types have 50+ dependents) |
| ECIP-1017-style emission changes | The block-reward/emission table for PoW networks (ECIP-1017 fixed-supply schedule) — the PoS equivalent has no emission table (ETH/Sepolia burns base fee, no block reward) |
| `alloy-rpc-types-engine` — new Engine API payload/param types | fukuii's Engine API request/response types (`engine_newPayloadVx`/`engine_getPayloadVx` equivalents) — relevant only to the PoS/ETH family, since ETC/Mordor has no consensus-layer Engine API |
| `ExecutionPayloadSidecar` container | Whatever container type threads new Engine API parameters through fukuii's payload-to-block conversion — needs a name-check against the actual PoS payload conversion code path |
| `EngineApi` trait (new endpoints) | fukuii's own Engine API controller/trait boundary (PoS-only; see `conduit` agent's JSON-RPC/Engine API scope) |
| `EngineValidator` trait (version-specific checks) | fukuii's equivalent payload-validation dispatch point — wherever fork-version-gated payload validation currently branches |
| Genesis/chain-config schema | fukuii's genesis/chain-config parsing for both families — where a new fork's activation block (PoW) or timestamp (PoS) gets registered |

The key structural lesson to port, independent of the specific class names above: Reth's
checklist succeeds by naming **traits and container types**, not files or line numbers
(which would go stale) and not abstract process steps (which fukuii's
`consensus-change-protocol.md` already covers). fukuii's new checklist should do the
same — name the actual Scala trait/class/object an agent needs to open, one bullet per
extension point, organized by "which family does this apply to" (PoW-only, PoS-only, or
both) the same way Reth's is organized by "which crate does this live in."

## Fukuii verdict summary table

| Finding | Verdict | Reasoning |
|---|---|---|
| Root `AGENTS.md` symlinked from `CLAUDE.md` | Not portable (structural mismatch) | fukuii's `CLAUDE.md` genuinely carries Claude-only orchestration (subagents, Spec Kit, sprint tooling) that Reth has none of; collapsing to a symlink would either delete that content or force it into the portable file. Correct as-is per fukuii's own `agents-md-decision-2026.md`. |
| Six PR-linked contribution-pattern examples | Port now | Cheap, high-value: fukuii's `AGENTS.md`/`CLAUDE.md` could add 3–6 real merged-PR diff snippets (one per subagent domain: a `forge` consensus fix, a `conduit` RPC fix, a `loom` migration commit) the same way Reth anchors abstract patterns to real diffs. Low effort, directly improves few-shot grounding. |
| "Filler word" ban in PR description guidance (`"comprehensive", "robust", "enhance", "leverage"`) | Port now | Trivial to add to fukuii's own commit/PR conventions; directly targets LLM-flavored prose. |
| "Will this make sense in 6 months?" comment heuristic | Port now | One-line addition to fukuii's conventions section; more actionable than a general "write good comments" instruction. |
| Rust type-ordering convention (primary type first) | Not portable (language-specific) | Convention is about file-as-module ordering in Rust; Scala's package/companion-object structure doesn't have the same "file matches primary type" convention to violate. No direct analog needed. |
| `make update-book-cli` CI-diffs-generated-artifact pattern | Needs design | fukuii has no CLI-flags-to-docs generation step today; the general primitive (CI regenerates an artifact and diffs it against the committed copy) is reusable if fukuii ever adds generated reference docs (e.g. HOCON config reference, JSON-RPC method tables). Not urgent. |
| `docs/vocs/CLAUDE.md` — un-symlinked, subproject-scoped, independently drifted | Already have equivalent (partially) | fukuii doesn't have a comparable independent subproject with its own Claude Code session, but the lesson (subproject tooling dirs can silently diverge from root conventions) is worth a one-line note in `AGENTS.md`'s reference index the next time a genuinely independent subproject (e.g. a future SDK package) is added. |
| Stray but git-tracked `docs/vocs/.claude/settings.local.json` | Already have equivalent (gitignore covers root) | fukuii's root `.gitignore` already covers `.claude/` per the user's global gitignore-security rules. Residual risk: verify any *vendored/submoduled* subdirectory with independent tooling (this very `repo-references/` tree, or a future spun-out package) carries its own equivalent `.gitignore` coverage rather than assuming inheritance from the fukuii root. |
| No skills system, no MCP config, no CI review bot | Already have equivalent (fukuii is ahead here) | fukuii already has 22 operational skills, an MCP server (`.github/copilot/`), and no CI review bot either (a gap shared with all three audited clients — none has a genuinely LLM-integrated CI review job beyond Nethermind/Erigon's `claude-review.yml`/`claude.yml`, which fukuii also lacks and could consider). |
| `HARDFORK-CHECKLIST.md` (27-line trait/crate-named extension-point map) | Port now (as new doc, in progress) | This is the standout artifact of the audit. fukuii's planned `hardfork-implementation-checklist.md` should copy its exact shape — short, non-exhaustive, organized by "which crate/trait to touch" rather than by process — using the extension-point mapping table above as the first draft of its content. |
| Dependabot `cooldown: default-days: 7` on cargo + github-actions ecosystems | Already have equivalent | fukuii's own supply-chain-security rules already mandate the same 7-day cooldown; this is convergent validation, not a new idea to port. |
