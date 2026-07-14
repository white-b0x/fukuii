# Erigon — Agentic Tooling Patterns

Source: `.claude/repo-references/clients/erigon/` (vendored full clone, verified genuine
git repository — `HEAD` at `f1d79d699ed4b809abc0d177dcb539d8605edc41`, 2026-07-01, `origin`
= `https://github.com/white-b0x/erigon.git`, `upstream` = `https://github.com/erigontech/erigon.git`).
All paths below are relative to that vendored clone root unless stated otherwise.

## Summary

Erigon has the richest agentic tooling of the six surveyed reference clients, and it is
richer along a different axis than Nethermind's CI-review-bot strength: where Nethermind's
standout artifact is a single, deep CI workflow, Erigon's standout is **breadth and depth of
in-repo, load-on-demand documentation** — a root `agents.md` with a Key-Directories table
linking to four per-subsystem `agents.md` breadcrumbs, plus (found independently of anything
this task's brief anticipated) a *second, deeper* tree of genuinely distinct `CLAUDE.md`
files nested under `cl/` that provide function-level consensus-spec-to-code maps for the
three most consensus-critical directories in the whole repository. Layered on top of that:
an advisory (never-blocking) comment-policy hook enforced via `PostToolUse`, a strict
project-wide TDD/no-test-skip policy embedded directly in the agent-context file, a shipped
node-level MCP server (both embedded-in-binary and standalone-binary variants, 40+ tools),
`llms.txt`/`llms-full.txt` at the repo root, and a lightweight `docs/plans/` objective+spec
pairing convention. The one place Erigon is *thinner* than Nethermind is CI-integrated
review automation: `.github/workflows/claude.yml` is a bare 50-line `@claude`-mention
responder with **no fork-PR author-association gate at all** — a materially different (and
more permissive) security posture than Nethermind's `claude-review.yml`, confirmed directly
against the current file content below.

## agents.md / CLAUDE.md structure

**`agents.md`** (root, 221 lines) is Erigon's single portable agent-context file — note the
lower-case filename, distinct from Nethermind's `AGENTS.md`. Structure, in order:

- **Lines 1–5**: title, one-line purpose statement, and a requirements line (Go 1.25+, GCC
  10+/Clang, 32GB+ RAM, SSD/NVMe).
- **Lines 7–18 — Build & Test**: a six-command `make` block (`make erigon`, `make
  integration`, `make lint`, `make test-short`, `make test-all`, `make gen`) plus a one-line
  pre-commit rule: `make lint && make erigon integration`.
- **Lines 20–25 — Architecture Overview**: four bullets — Erigon is an Ethereum execution
  client; data flow is `db -> snapshots`; snapshots are immutable; `Unwind` beyond data held
  in snapshots is not allowed. This terse "data flow + hard invariant" framing recurs almost
  verbatim in `db/agents.md` (see below).
- **Lines 27–36 — Key Directories (the breadcrumb table)**: this is the routing mechanism —
  a five-row Markdown table (`cmd/`, `execution/stagedsync/`, `db/`, `cl/`, `p2p/`,
  `rpc/jsonrpc/`) mapping each directory to a one-line purpose and, for four of the six
  rows, a `[agents.md](<dir>/agents.md)` relative link. `cmd/` and `rpc/jsonrpc/` have no
  linked doc (`-` in the Component Docs column) — confirmed by the `find` inventory below,
  there is no `rpc/jsonrpc/agents.md` in this clone.
- **Lines 38–43 — Running**: two example invocations (`--chain=mainnet`, `--chain=dev` for
  PoS dev mode).
- **Lines 45–113 — Test-Driven Development**: by far the largest single section relative to
  Nethermind's `agents.md`, which has no TDD section at all. Documents Red→Green→Refactor
  (lines 47–51), a bug-fix-specific rule that the failing test must be written *before* the
  fix (lines 53–57: "Never write the fix first and then 'add a test for it'"), new-feature
  guidance (outside-in API design, lines 59–63), an anti-patterns list (lines 65–70), and a
  "when pragmatism applies" carve-out for pure refactors/spikes/mechanical changes (lines
  73–80) that still requires the PR description to say explicitly that TDD was skipped and
  why.
- **Lines 82–113 — Test skips**: a hard, explicitly agent-scoped rule. Line 88 cites a
  concrete incident (`#21153` removing a stale `t.Skip` for `TestGeneratedTraceApiCollision`
  that hid a real parallel-exec bug, which then broke downstream PR `#21017` when the skip
  was removed) as the rationale for the policy, not an abstract argument. Lines 90–98 define
  the only two legitimate reasons a skip may ever exist (imported external test suites for
  unimplemented features; flaky tests with a linked issue and a documented local-repro
  investigation) — both explicitly restricted to *human* contributors. Lines 100–111 state,
  in bold: **"Automated agents must never add a skip. Period."** — enumerating the exact
  forms this could take (`t.Skip`, `t.SkipNow`, `t.Skipf`, `SkipLoad`, `bt.SkipLoad`,
  build-tag exclusions, `dbg.*` env-flag conditionals, matrix-removal without a tracking
  issue) and closing every loophole an agent could argue for ("not even with a 'the user can
  review it' framing... not as an option in `AskUserQuestion` menus"). Line 113 carves out
  exactly one escape hatch: a user explicitly overriding the rule in the current turn, and
  even then the agent must still flag the trade-off and ensure a tracking issue exists.
- **Lines 115–123 — Conventions**: commit-message prefix convention (`eth, rpc: make trace
  configs optional`); an explicit statement that Claude attribution
  (`Co-Authored-By: Claude`, the 🤖 footer) is disabled repo-wide via
  `.claude/settings.json`'s `includeCoAuthoredBy: false` (verified below); the non-deterministic-linter
  warning repeated twice (once here, once again at line 194–196 under "Lint Notes" — a
  literal duplication in the source file, not an artifact of this reading).
- **Lines 125–171 — Code Style → Comments**: the single most detailed, most rigorously
  specified section in the file — see its own subsection below.
- **Lines 173–189 — Pull Requests & Workflows**: a workflow-dispatch-transparency rule
  (manually dispatching a non-default workflow requires a PR comment explaining which one,
  why, and a link to the run); a GitHub auto-link gotcha rule (write "point 1"/"item 1", not
  bare `#1`, when referring to a numbered list item — bare `#1` auto-links to an unrelated
  issue/PR); and a detailed backport-PR convention for `release/3.4` (title prefix
  `[r3.4]` — dominant form among observed variants `[r34]`/`[3.4]`; body conventions for
  straight cherry-picks vs. adapted backports; branch-name conventions with worked examples).
- **Lines 190–206 — Pre-push / Lint Notes**: repeats the `make lint` pre-push rule and gives
  a table of eight specific `golangci-lint` categories with fixes (`ruleguard` defer-order
  rule, `prealloc`, `unslice`, `newDeref`, `appendCombine`, `rangeExprCopy`, `dupArg`,
  loop-ruleguard-in-benchmarks) — a "patterns actually seen in this codebase" reference list,
  the same documentation habit Nethermind's `performance.md` uses.
- **Lines 208–212 — Workflows**: cross-platform-shell-script requirement for GitHub Actions,
  pointing to `CI-GUIDELINES.md` before touching workflow files.
- **Lines 214–220 — Go Test Caching**: an unusually specific piece of build-infrastructure
  knowledge — Go's test cache keys on mtime+size of every file a test reads; CI normalizes
  mtimes via `git restore-mtime` in `.github/actions/setup-erigon/action.yml`; any test that
  reads a runtime data file outside `testdata/` must be added to that action's pattern list
  or its package's test results will never cache in CI. Six existing covered patterns are
  listed as a reference (`**/testdata/**`, `execution/tests/test-corners/**`,
  `cl/spectest/**/data_*/**`, `cl/transition/**/test_data/**`,
  `cl/utils/eth2shuffle/spec/**`, `execution/state/genesiswrite/*.json`).

**`CLAUDE.md`** (root) is confirmed a genuine symlink: `ls -la CLAUDE.md` shows
`CLAUDE.md -> agents.md`, and `readlink CLAUDE.md` returns `agents.md`. Byte-for-byte
identical content to `agents.md` follows automatically — there is zero Claude-Code-specific
content layered on top at the root, the same pattern Nethermind uses for its own
`.claude/CLAUDE.md` → `@../AGENTS.md` import (though Erigon uses a filesystem symlink,
Nethermind uses Claude Code's `@path` import directive — different mechanisms, same
zero-duplication goal).

### Code Style → Comments (lines 125–171) — the most heavily engineered section

This section is worth calling out on its own because it is the most prescriptive,
example-driven piece of prose in the whole file, and — per an explicit closing note at
lines 171 — it exists *because* earlier, gentler phrasings failed:

> "For automated agents specifically: previous iterations of this guidance were not
> enough — agents kept producing multi-paragraph block comments enumerating call sites and
> incident history. Treat the rules above as hard limits."

The rule itself (lines 127–138): default to **no comment**; a comment is warranted only for
a non-obvious invariant the types don't enforce, a workaround for a dependency/runtime bug
(with a linked issue/commit), a genuinely surprising edge case, or a performance-sensitive
choice where the obvious code would be wrong. When one is warranted it must be one sentence
(rarely two, never a paragraph, no bulleted sub-sections), high-level rather than
scenario-specific (state the invariant, not a walkthrough of specific call sites a reader
could `grep` for themselves), free of forensic detail (no dates, devnet/branch names,
PR/issue/review references, "used by X, Y, Z" callsite lists — "that history belongs in the
commit message and PR description, where it survives intact; in source it rots"), and not a
restatement of the code. Lines 140–165 give a paired good/bad example — a one-sentence
comment on `MemoryMutation.Rollback()` versus a ~15-line "badly written" version of the same
constraint that name-drops internal callers (`SharedDomains.blockOverlay`), threads in
review history, and narrates a hypothetical future refactor — closing with: "If a constraint
really needs to be enforced for the codebase's safety, prefer **code that enforces it** (a
runtime assert, a type the caller can't misuse, a single private constructor) over a comment
that describes it. A `panic` survives refactors; a long comment doesn't." Line 169 extends
the same discipline to `// TODO`: only acceptable with a linked tracking issue and an owner;
otherwise, file the issue and skip the TODO, or fix it now.

This same policy is independently re-stated, in near-identical language, in
`.claude/rules/comments.md` (see below) and is the one policy in this entire vendored clone
that is *also* mechanically enforced by a hook (`comment-policy.py`) rather than left as
prose alone — a three-layer reinforcement (agents.md prose → rules/comments.md restatement
→ PostToolUse hook) that no other single rule in the Erigon or Nethermind clones receives.

## Per-subsystem agents.md breadcrumbs — Erigon's most distinctive pattern

`find /media/dev/2tb/dev/fukuii/.claude/repo-references/clients/erigon -iname "agents.md"`
returns exactly **five** files: the root `agents.md` plus four subsystem breadcrumbs —
`cl/agents.md` (66 lines), `db/agents.md` (68 lines), `execution/stagedsync/agents.md`
(66 lines), and `p2p/agents.md` (73 lines). This matches the four candidate directories the
root table links to (`cmd/` and `rpc/jsonrpc/` are listed in the same table but have no
linked doc). All four were read in full; none is a Claude-specific file — there is no
`cl/CLAUDE.md` distinct from `cl/agents.md` at *this* level (a separate, much deeper
`CLAUDE.md`-only tree does exist further down inside `cl/`; see the next section — it is
not part of this `agents.md` family and was not something this task's brief anticipated
finding).

All four breadcrumbs share one shape: no YAML frontmatter, a one-line subsystem
description, a "Directory Structure" or "Key Components" table mapping sub-paths to
purpose, then a small number of topic-specific sections (message tables, stage-pipeline
tables, port tables, or CLI invocations) — consistently terse, consistently a directory map
first and a concept explainer second. None of the four link back to the root `agents.md` or
to `.claude/rules/*.md` — the linkage is one-directional (root → subsystem), unlike
Nethermind's rules files, several of which cross-reference each other and `AGENTS.md`
explicitly.

### `cl/agents.md` (66 lines) — Caplin (Consensus Layer)

Opens with a one-line description ("Caplin is Erigon's embedded Beacon Chain client
implementing Ethereum's proof-of-stake consensus," line 3) then a nine-row Directory
Structure table (lines 7–16: `beacon/`, `phase1/forkchoice/`, `phase1/execution_client/`,
`phase1/core/state/`, `phase1/network/`, `cltypes/`, `sentinel/`, `pool/`, `validator/`).
"Key Components" (lines 19–41) gives four subsections — Fork Choice (four named files: `forkchoice.go`,
`on_block.go`, `on_attestation.go`, plus a one-line note that it tracks finality/justification
checkpoints), Engine API (three named RPCs: `NewPayload`, `ForkchoiceUpdated`,
`GetPayload`), Beacon State (fork-upgrade list: Altair, Bellatrix, Capella, Deneb — notably
*not* extended to Electra/Fulu/Gloas, which the deeper spec-map files below do cover — a
sign this breadcrumb is not kept as current as the nested spec maps), and Sentinel
(libp2p/GossipSub one-liner). A "Beacon API" section (lines 43–49) lists REST endpoint
categories without file names. "Enable/Disable" (lines 51–59) and "Archive Mode"
(lines 61–66) are runnable CLI examples (`--internalcl` default, `--externalcl`,
`--caplin.archive`).

### `db/agents.md` (68 lines) — Database Layer

Opens by naming the architecture directly: "Erigon uses a temporal database architecture
separating hot (mutable) from cold (immutable) data" (line 3), then a four-step Data Flow
list (lines 5–10) ending in the same hard invariant as root `agents.md`: "`Unwind` beyond
data in snapshots not allowed" (line 10) — this exact sentence appears in both the root file
(line 25) and here, the one piece of text repeated verbatim between the two tiers. A
Storage Architecture ASCII tree (lines 12–22) shows `datadir/{chaindata/, snapshots/{domain/,
history/, idx/, accessor/}}`. "Key Components" (lines 24–40) names MDBX (`kv/mdbx/`, "Fork
of LMDB optimized for Erigon's access patterns," `kv/tables.go` for table definitions),
Temporal Database (`kv/temporal/kv_temporal.go`'s `TemporalDB` wrapping MDBX+Aggregator,
methods `GetLatest()`/`HistorySeek()`/`RangeAsOf()`/`IndexRange()`, time-travel via
`GetAsOf(txNum)`), and State Aggregator (`state/aggregator.go`, `domain.go`, `history.go`,
`inverted_index.go`). A **Four Domains** table (lines 42–49: AccountsDomain, StorageDomain,
CodeDomain, CommitmentDomain, each with a one-line content description) is the clearest
"what to know before touching this subsystem" artifact in the file. Closing sections cover
the ETL Framework (`etl/`, three-step sort-before-insert description, lines 56–61) and
Snapshots (`seg/`, `downloader/` — `.seg` immutable files, BitTorrent+WebSeed distribution,
2MB default piece size, verification-on-download, lines 63–68).

### `p2p/agents.md` (73 lines) — P2P Networking

Opens with an architecture-in-one-line diagram (line 7–9: `Execution Node ←→ gRPC ←→ Sentry
←→ P2P Network`) and states the Sentry's purpose in three bullets (P2P isolation,
multi-sentry resilience, resource limiting — lines 11–14). "Key Components" (lines 16–36)
covers Server (`server.go` — dial/accept fairness, protocol negotiation, peer lifecycle),
Discovery (`discover/v4` legacy UDP vs. `discover/v5` ENR-based), RLPx (encrypted
transport/handshake/framing), and Protocols (`protocols/eth/`, `protocols/wit/` — the
witness protocol for stateless clients, notable as a protocol Erigon documents that fukuii's
own `herald` agent domain does not yet track). "Sentry Service" (lines 38–43) names three
files (`sentry_grpc_server.go`, `eth_handshake.go`, `libsentry/`). An **ETH Protocol
Messages** table (lines 45–55, seven message types: Status, NewBlockHashes, Transactions,
GetBlockHeaders, GetBlockBodies, NewBlock, PooledTransactions) and a **Ports** table (lines
57–63: `30303` eth/69, `30304` eth/70, `9091` Sentry gRPC internal) are the two most
immediately reusable artifacts — a reader can look up "what port does eth/70 use" or "what
does message X do" in one glance rather than grepping source. Closes with a two-command
"Running Separate Sentry" example (lines 65–73).

### `execution/stagedsync/agents.md` (66 lines) — Staged Sync

Opens with the one-line architecture statement that frames the entire subsystem: "Erigon
synchronizes via ordered stages. Each stage processes blocks independently and can unwind
during reorgs" (line 3). An eight-item numbered **Stage Pipeline Order** list (lines 5–14:
Snapshots → Headers → BlockHashes → Bodies → Senders → Execution → TxLookup → Finish) is the
single most load-bearing artifact in the file — it is the canonical ordering, and the later
Stage Implementations table cross-references it by name. "Key Files" (lines 16–21) names
four orchestration files: `sync.go` (`Sync` struct, unwind-point tracking), `stageloop/
stageloop.go` (`StageLoop()`), `default_stages.go` (`DefaultStages()`,
`DefaultForwardOrder`, `DefaultUnwindOrder` factory functions), `stages/stages.go`
(`SyncStage` constants). A **Stage Interface** Go snippet (lines 23–33) shows the exact
three-function shape every stage implements (`Forward ExecFunc`, `Unwind UnwindFunc`, `Prune
PruneFunc`) — this is the one subsystem breadcrumb that embeds a code snippet rather than
only prose/tables. "Configuration Pattern" (lines 35–41) documents the per-stage config
struct convention (`HeadersCfg`, `ExecuteBlockCfg`, etc., each holding DB handles, P2P
handlers, chain config, batch-size tuning). "Reorg Handling" (lines 43–48) is a four-step
list explaining `UnwindTo()` → reverse-order unwind via `DefaultUnwindOrder` → state rollback
via domain writers → resume from unwind point. A **Stage Implementations** table (lines
50–61, eight rows mapping each of the eight pipeline stages to its implementing file) closes
the loop with the pipeline-order list at the top. "Supporting Modules" (lines 63–66) names
`headerdownload/` and `bodydownload/`.

## Beyond agents.md: a second, deeper CLAUDE.md-only spec-map tree under `cl/`

This is a genuinely distinct pattern from the four `agents.md` breadcrumbs above, and it was
not anticipated by this task's brief — `find -iname agents.md` does not surface it because
these files are named `CLAUDE.md`, and unlike the root `CLAUDE.md` they are **not
symlinks**. `find . -iname "CLAUDE.md"` returns five files: the root symlink, plus
`cl/CLAUDE.md` (1,760 bytes), `cl/phase1/forkchoice/CLAUDE.md` (12,860 bytes),
`cl/transition/CLAUDE.md` (13,432 bytes), and `cl/phase1/core/state/CLAUDE.md` (8,815
bytes) — all four confirmed as ordinary regular files via `ls -la` (no symlink arrows). A
`diff` against `cl/agents.md` confirms `cl/CLAUDE.md` is genuinely different content, not a
duplicate under a different name.

`cl/CLAUDE.md` (see contents above) is a short index: it states that Caplin is
consensus-critical and must be reviewed "against the upstream Ethereum consensus
specifications, not only local tests" (line 4), links `https://github.com/ethereum/consensus-specs`
as the primary reference, then a three-row table pointing to the three deeper files —
`phase1/forkchoice/CLAUDE.md` (fork choice: `on_block`, `on_attestation`, `get_head`,
payload votes, timing, data availability), `transition/CLAUDE.md` (state transition: block
operations, epoch processing, slot processing), `phase1/core/state/CLAUDE.md` (beacon state
helpers: accessors, mutators, builder/deposit routing) — plus four "Cross-Cutting
Principles" bullets: locate the spec section before modifying consensus code (and find it
yourself if a function is missing from the maps below); partial-vs-full-list accumulators
are often intentional, not a bug to "fix"; validate before mutating state, since spec
handlers that fail assertions must not have already changed state; and preserve pre-fork
semantics explicitly per fork (Phase0 through Gloas).

Each of the three deeper files is a genuine **function-to-spec-section map**, not a
directory tour — this is the single most rigorous documentation artifact found across any
of the six surveyed clients' agentic tooling. Representative structure, verified by reading
all three in full:

- **`cl/phase1/forkchoice/CLAUDE.md`** (12,860 bytes) opens with six spec-URL references
  (Phase0/Bellatrix/Deneb/Electra/Fulu/Gloas fork-choice spec pages), then a ~35-row
  **Forkchoice Spec Map** table, one row per Go file/function, e.g.: `forkchoice.go:
  NewForkChoiceStore` → "Phase0 `get_forkchoice_store`; Gloas `get_forkchoice_store` and
  extended `Store` fields"; `on_block.go: OnBlock` → "Phase0 `on_block`; Bellatrix execution
  payload validation; Deneb blob availability; Electra execution requests; Gloas deferred
  payload/envelope processing; Fulu modified `on_block` data availability call" — each row
  threading every fork variant that touched that one function, not just the latest. A "Fulu
  Review Notes" subsection calls out a specific regression risk in prose: "Do not reintroduce
  a Fulu dependency on `block.body.blob_kzg_commitments` in fork choice" — the kind of
  fork-migration gotcha a spec map alone wouldn't surface. Closes with a nine-item "Review
  Checklist" (conditional-vs-fallback preservation, missing-disjunct auditing across five
  named functions, epoch-boundary edge cases, timeliness `<` vs `<=` boundaries, Gloas
  payload-status tracing, PTC vote independence, store-mutation-safety-before-assert,
  cache-invalidation-on-every-mutating-path, pre-fork-vs-post-fork gating, Electra
  execution-request nil-vs-empty-list normalization).
- **`cl/transition/CLAUDE.md`** (13,432 bytes) is organized into three separate maps —
  Transition Pipeline Map (`machine/transition.go`, `machine/block.go`,
  `impl/eth2/validation.go`, `compat.go`), Block Operation Spec Map (`operations.go`'s ~25
  functions, from `ProcessProposerSlashing` through `ProcessPayloadAttestation`), and Epoch
  Processing Spec Map (`statechange/`'s per-file epoch sub-transitions) — each row again
  threading every relevant fork. The Review Checklist's fourth bullet is a concrete,
  copy-pasteable pattern description: "the local pattern from pending deposits: iterate
  until the first unprocessed item, `ShallowCopy`, `Cut(processedCount)`, append postponed
  items in order, then write the list back... Watch for code that processes only a prefix and
  silently drops the tail."
- **`cl/phase1/core/state/CLAUDE.md`** (8,815 bytes) splits into an Accessor Spec Map
  (`accessors.go`, `cache_accessors.go`, `util.go`, `epbs.go`) and a Mutator Spec Map
  (`mutators.go`, `cache_mutators.go`), each row again fork-annotated. The Review Checklist
  states an invariant with real bite: "`get_total_balance` must return at least
  `EFFECTIVE_BALANCE_INCREMENT`, and `decrease_balance` must not underflow" — a
  numerically-verifiable assertion, not a vague style note.

None of these three files link back to `cl/CLAUDE.md` or the root `agents.md`/`CLAUDE.md` —
the linkage, again, is strictly downward (index → detail), never upward. This tree exists
*only* for `cl/` — there is no equivalent nested `CLAUDE.md` map for `db/`, `p2p/`, or
`execution/stagedsync/`, confirming that Erigon reserves this heaviest-weight documentation
tier specifically for the one subsystem (PoS consensus) where a subtle fork-conditional bug
is both hardest to catch by testing alone and most consequential if wrong.

## `.claude/` directory — hooks, rules, settings

`.claude/` (top level: `hooks/`, `rules/`, `settings.json`, `skills/` — no `agents/`
subdirectory, i.e. no named-subagent definitions comparable to fukuii's 12) confirmed via
`ls -la`.

### `.claude/settings.json` (43 lines, read in full)

```json
{
  "permissions": {
    "allow": [
      "Bash(make test-all:*)", "Bash(go test:*)", "Bash(go get:*)", "Bash(go vet:*)",
      "Bash(make lint:*)", "Bash(git fetch:*)", "Bash(go mod tidy:*)",
      "WebFetch(domain:github.com)", "WebSearch", "WebFetch(domain:raw.githubusercontent.com)",
      "Bash(git status:*)", "Bash(gh pr edit:*)", "Bash(grep:*)", "Bash(git add:*)",
      "WebFetch(domain:pypi.org)", "Bash(CGO_CFLAGS=-D__BLST_PORTABLE__ go test:*)",
      "Bash(find:*)", "Bash(gh pr:*)"
    ]
  },
  "hooks": {
    "PostToolUse": [
      { "matcher": "Write|Edit|MultiEdit",
        "hooks": [{ "type": "command",
          "command": "python3 \"$CLAUDE_PROJECT_DIR/.claude/hooks/comment-policy.py\"" }] }
    ]
  },
  "includeCoAuthoredBy": false,
  "attribution": { "commit": "", "pr": "" }
}
```

Notable specifics: the allow-list is a mix of general build/VCS commands and one
build-tag-specific invocation (`Bash(CGO_CFLAGS=-D__BLST_PORTABLE__ go test:*)` — a BLS
library portable-build flag, presumably needed for `cl/` consensus-crypto tests on some
platforms); three `WebFetch` domains are pre-allowlisted (`github.com`,
`raw.githubusercontent.com`, `pypi.org` — the last is unexplained by anything else in this
clone, possibly a leftover from an unrelated task) plus bare `WebSearch`. The `PostToolUse`
hook fires on every `Write`/`Edit`/`MultiEdit` and unconditionally invokes
`comment-policy.py` with no file-type filtering at the settings level (the Python script
itself filters to `.go` files — see below). `includeCoAuthoredBy: false` is the mechanism
`agents.md` line 119 refers to when it says Claude attribution is "disabled repo-wide."

### `.claude/hooks/comment-policy.py` (90 lines, read in full) — advisory, never blocking

The module docstring (lines 2–10) states its own nature plainly: "Advisory PostToolUse
hook: flag comment-policy violations in Go edits. **Never blocks.**" Mechanism: on every
`Write`/`Edit`/`MultiEdit` whose `file_path` ends in `.go` (line 55), it extracts only the
*added* comment lines (`new_string` for `Edit`, concatenated `edits[].new_string` for
`MultiEdit`, full `content` for `Write` — lines 27–43, filtering to lines starting with
`//`, `/*`, or `*`) and checks each against exactly three regexes (lines 17–24):

| Label | Regex (case-insensitive) |
|---|---|
| scope/limitation narration (→ PR body) | `forward[- ](only\|prevention)\|safety[- ]?net\|cannot repair\|snapshot unwind\|\bNOTE:\s` |
| incident/reproduction narration (→ PR body) | `\bmainnet\b\|\bblock[s]?\s+\d{6,9}\b\|\b2[0-9]{7}\b\|\bcalled\b.*\bblocks? later\b` |
| task/PR/issue reference (→ commit msg/PR; keep genuine workaround links) | `#\d{3,6}\b\|\bPR\s*#?\d{3,6}\b\|as requested\|in review` |

If any comment line matches, the hook exits 0 (never a non-zero/blocking exit — line 50's
`sys.exit(0)` on JSON-parse failure and the unconditional `sys.exit(0)` at the end of `main()`
both confirm this is purely advisory) and emits a `PostToolUse` JSON payload with
`hookSpecificOutput.additionalContext` (lines 79–85) — a message listing up to 12 flagged
lines with their violation label, closing with "If a flagged line is a genuine why-comment
(e.g. a workaround issue link), keep it." This surfaces as extra context the agent sees on
its next turn, giving it a chance to self-correct — a fundamentally different enforcement
mechanism than a blocking pre-commit hook or CI check: it nudges rather than gates. The
regex set is deliberately narrow (three patterns, comment on line 16: "conservative to avoid
false-positive noise on legitimate why-comments") rather than attempting a general prose
classifier.

### `.claude/rules/` (three files, all read in full)

- **`comments.md`** (1,627 bytes) — a condensed restatement of `agents.md`'s Comments
  section, organized as "Write a comment only for" (four bullets, matching agents.md's four
  categories) vs. "Never put in code (→ goes in commit message or PR body)" (five bullets:
  scope/limitation narration, incident/reproduction narration, task references, restating
  the code, repeating the same rationale at multiple sites — this last one, "state the *why*
  once at the canonical place... use terse pointers elsewhere," does not appear in
  `agents.md` itself, making this rules file a genuine superset, not a pure duplicate). Closes
  with a one-line carve-out for test docstrings ("slightly more latitude... but the same
  rules apply").
- **`branch-naming.md`** (1,854 bytes) — documents release branches (`release/3.3` stable,
  `release/3.4` current stable, `main` = next feature release 3.5) and the repo-wide
  developer-branch convention `<github-username>/<short-desc>` (both kebab-case and
  snake_case observed and accepted — "pick one and stay consistent within your own
  branches"). Gives four real example branches sampled from `origin`
  (`yperbasis/overlay-flush-append`, `mh/parallel-exec-heuristic-removal`,
  `lupin012/fix_vrs_null_mainnet`, `awskii/eest-witness-newmain`) and an explicit
  instruction to sample a contributor's own recent branches before naming one on their
  behalf, rather than imposing a house style. Documents one contributor's personal
  release-suffix scheme (Alex's `alex/<short_desc>_<release_num>[_suffix]`, e.g.
  `alex/seg_header_meta2_34`) with an explicit "this is one contributor's personal scheme —
  do not apply it to others" — an unusually granular, almost anthropological level of
  convention documentation not seen in any other surveyed client's rules files. Also
  documents non-personal cross-cutting prefixes: `feature/<username>/<desc>`,
  `cp/<pr-number>-to-<target>` (cherry-picks), `ci-fix/<desc>`, `wip/<desc>`.
- **`lint-fixes.md`** (825 bytes) — the identical eight-category `golangci-lint` fix table
  that also appears inline in `agents.md` (lines 198–206) — a genuine duplicate, not a
  superset, confirmed by comparing the two; this is the one rules file that adds nothing
  `agents.md` doesn't already say.

## CI-integrated review bot (`claude.yml`) — full mechanism, and the fork-PR gate finding

Path: `.github/workflows/claude.yml`, **50 lines total** (read in full — this is
dramatically shorter than Nethermind's 231-line `claude-review.yml`).

### Triggers (lines 3–11)

```yaml
on:
  issue_comment:
    types: [created]
  pull_request_review_comment:
    types: [created]
  issues:
    types: [opened, assigned]
  pull_request_review:
    types: [submitted]
```

Four event types. Notably **no `pull_request` trigger at all** — unlike Nethermind's
workflow, there is no auto-review-on-PR-open path; every invocation here is
mention-triggered.

### The job-level `if:` gate (lines 15–19) — verified finding

```yaml
if: |
  (github.event_name == 'issue_comment' && contains(github.event.comment.body, '@claude')) ||
  (github.event_name == 'pull_request_review_comment' && contains(github.event.comment.body, '@claude')) ||
  (github.event_name == 'pull_request_review' && contains(github.event.review.body, '@claude')) ||
  (github.event_name == 'issues' && (contains(github.event.issue.body, '@claude') || contains(github.event.issue.title, '@claude')))
```

**This is a plain substring check for `@claude` in the comment/review/issue body — there is
no `author_association` check, no allow-list of `MEMBER`/`COLLABORATOR`/`OWNER`, and no
same-repository-PR restriction anywhere in this condition or elsewhere in the file.** This
task's brief stated "a prior pass claimed there is NOT one" (a fork-PR security gate) and
asked for direct verification: **confirmed absent.** Any GitHub user who can leave a comment
containing the literal string `@claude` on an issue, a PR review comment, a PR review body,
or an issue body/title triggers this job — including, per GitHub Actions semantics for
`issue_comment` (which the workflow's own permissions block below implicitly relies on),
comments left on a fork's PR, which run in the base-repository's context with full secret
access. Compare directly against Nethermind's `claude-review.yml`, whose job-level `if:`
gates every mention path on
`contains(fromJSON('["MEMBER","COLLABORATOR","OWNER"]'), github.event.comment.author_association)`
(and separately restricts its *auto-review* path to same-repository PRs) — Erigon's
workflow has neither of those two guards. This is a materially more permissive security
posture, not a documentation gap: the mechanism to burn the project's Claude API budget (or
worse, given the permissions below) via a throwaway account commenting `@claude do X` on any
issue is not gated at all in this file.

### Permissions (lines 21–26)

```yaml
permissions:
  contents: write # Required so Claude can push commits to branches it creates
  pull-requests: read
  issues: read
  id-token: write
  actions: read # Required for Claude to read CI results on PRs
```

`contents: write` is the standout difference from Nethermind's review-only posture:
Nethermind's workflow deliberately grants neither `Edit` nor `Write` to either of its two
Claude paths, keeping the bot comment-only. Erigon's workflow grants `contents: write` at
the job level specifically so **Claude can push commits to branches it creates** (per the
inline comment) — i.e. this workflow is designed to let a mentioned Claude actually
implement a requested change and push it, not just comment. Combined with the absent
author-association gate, this means an unauthenticated external commenter can, in principle,
prompt a workflow run that has write access to the repository's contents — the workflow's
own `claude_args` (see below) doesn't restrict this either, since the config comments it out.
No `statuses: write` (there is no commit-status-posting step at all — unlike Nethermind,
this workflow never posts a machine-readable merge-gating verdict).

### The single step (lines 28–50)

`actions/checkout@v7` with `fetch-depth: 1`, then `anthropics/claude-code-action@v1` with:
`claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}` (same OAuth-token-secret
auth pattern as Nethermind); `additional_permissions: | actions: read` (a nested permission
grant specifically so Claude can read CI results on PRs, inline-commented as such); a
**commented-out** `prompt:` line showing the intended override shape
(`'Update the pull request description to include a summary of changes.'`) but left inactive
— meaning, per the action's own documented fallback behavior (same as Nethermind's mention
path), Claude parses the triggering comment/review/issue body itself as its instruction; and
a **commented-out** `claude_args:` line (`'--allowed-tools Bash(gh pr *)'`) — meaning no
model is pinned (unlike Nethermind's explicit `--model opus` on both its paths) and no tool
allow-list is actually active, so the action's own defaults govern which tools Claude can
use during the run.

### Summary comparison against Nethermind's `claude-review.yml`

| Aspect | Erigon `claude.yml` | Nethermind `claude-review.yml` |
|---|---|---|
| Lines | 50 | 231 |
| Trigger paths | 1 (mention-only) | 2 (auto-review + mention) |
| Fork-PR / author-association gate | **Absent — confirmed** | Present (`MEMBER`/`COLLABORATOR`/`OWNER` allow-list + same-repo-PR restriction) |
| `contents: write` | **Yes** — Claude can push commits | No — review-only, no Edit/Write in either tool allow-list |
| Model pin | None (commented-out `claude_args`) | `--model opus`, both paths |
| Tool allow-list | None active (commented-out) | Explicit narrow allow-list per path |
| Structured/JSON-schema verdict | None | Yes — `mergeable`/severity-counts forced via `--json-schema` |
| Commit-status posting | None | Yes — `claude-review/reviewed`, branch-protection-gating |
| Custom prompt | None (commented-out example) | Fixed, hardcoded review prompt on the auto-review path |

## Shipped node-level MCP server

Documented at `docs/site/docs/fundamentals/mcp.mdx` (310 lines, read in full — this is a
Docusaurus MDX doc, not plain Markdown, hence the `import Tabs`/`<Tabs>` JSX at the top).

**Two server variants:**

1. **Embedded** (inside the `erigon` binary itself) — enabled by default, listening on
   `127.0.0.1:8553` (lines 24–32), disabled entirely with `--mcp.disable`. Uses **SSE
   (Server-Sent Events)** transport over HTTP. Flags: `--mcp.disable` (default `false`),
   `--mcp.addr` (default `127.0.0.1`), `--mcp.port` (default `8553`) — table at lines 53–59.
   The doc explicitly notes binding to localhost-only means it's unreachable externally by
   default, and recommends distinct `--mcp.port` values when running multiple instances on
   one machine (lines 61–66).
2. **Standalone** (`mcp` binary, built via `make mcp` — lines 42–49) — supports both
   **stdio** (for Claude Desktop) and **SSE** transports (`--transport`, default `stdio`;
   `--sse.addr`, default `127.0.0.1:8553`). Flags table (lines 68–78): `--rpc.url` (default
   `http://127.0.0.1:8545`), `--port` (shorthand for `--rpc.url` on a given port),
   `--datadir` (enables direct-DB-access mode), `--private.api.addr` (default
   `127.0.0.1:9090`, used with `--datadir`), `--log.dir` (enables log-analysis tools).

**Three connection modes for the standalone binary**, tried in priority order (lines 80–117):
(1) **JSON-RPC Proxy** (recommended) — forwards tool calls to a running node's HTTP
JSON-RPC endpoint, works with any node that has RPC enabled; (2) **Direct Datadir Access** —
opens the MDBX database directly (similar to an external `rpcdaemon`), via gRPC private API
or read-only local disk access; (3) **Auto-Discovery** — with no flags, probes
`localhost:8545`, `8546`, `8547` for a live JSON-RPC endpoint.

**Setup documented for three clients** (lines 149–256): Claude Desktop (JSON config at
`~/.config/claude-desktop/config.json`, three tabbed examples — JSON-RPC mode, datadir mode,
embedded-SSE mode); Claude Code (`claude mcp add --transport sse erigon
http://127.0.0.1:8553/sse` for embedded-SSE — described as "zero extra binaries" — or
`claude mcp add erigon /path/to/build/bin/mcp -- --port 8545` for standalone stdio;
verification via `claude mcp list`; removal via `claude mcp remove erigon`); OpenAI Codex
CLI (`~/.codex/config.yaml`, `mcp_servers:` key, both SSE and stdio variants shown).

**Tool/resource/prompt counts** (lines 258–310): the doc states the server exposes **"over
40 tools"** grouped into five namespaces — `eth_*` (standard JSON-RPC methods:
`eth_blockNumber`, `eth_getBlockByNumber`, `eth_getBalance`, `eth_getLogs`, `eth_call`,
`eth_getProof`, and more), `erigon_*` (Erigon-specific extensions: `erigon_nodeInfo`,
`erigon_forks`, `erigon_getBalanceChangesInBlock`, and more), `ots_*` (Otterscan-compatible:
`ots_traceTransaction`, `ots_getContractCreator`, and more), `logs_*` (`logs_tail`,
`logs_head`, `logs_grep`, `logs_stats` — require `--log.dir` or `--datadir`), and `metrics_*`
(`metrics_list`, `metrics_get` — embedded-mode only, standalone mode returns a descriptive
placeholder message instead). **Eight resources** (table, lines 288–297: `erigon://node/info`,
`erigon://chain/config`, `erigon://blocks/recent`, `erigon://network/status`,
`erigon://gas/current`, `erigon://address/{address}/summary`, `erigon://block/{number}/summary`,
`erigon://transaction/{hash}/analysis`). **Seven prompts** (table, lines 299–309:
`analyze_transaction`, `investigate_address`, `analyze_block`, `gas_analysis`, `debug_logs`,
`torrent_status`, `sync_analysis`). The doc frames the whole feature as read-only by design
(line 17 tip callout: "No write operations... are available") and gives three worked
example natural-language interactions (balance/tx-count lookup, ERC-20 Transfer-event
scanning, top-gas-consumers-in-a-block) plus two node-debugging examples (sync-status +
log-tail triage; torrent-download-stats analysis).

This is categorically richer than fukuii's own `.github/copilot/` MCP server (per fukuii's
own current-state audit: one `http` transport server, 5 tools with empty `"schema": {}`
placeholders, 5 resources, 3 prompts, no embedded-in-binary variant, no stdio transport, no
direct-datadir-access mode) — Erigon's is a first-class, dual-binary, dual-transport,
40+-tool shipped feature with its own docs-site page; fukuii's is a single JSON config file
plus a README, with unimplemented tool schemas.

## llms.txt / docs/plans/ (lightweight spec-kit analog)

**`llms.txt`** (12,699 bytes) and **`llms-full.txt`** (397,082 bytes) both confirmed present
at repo root via `ls -la`. `llms.txt` follows the emerging llms.txt convention (a curated,
link-based site index for LLM consumption): a one-paragraph project description followed by
`##`-sectioned bullet lists of `[Title](url): one-line description` entries pointing at the
live Docusaurus docs site (`docs.erigon.tech`) — sections observed include Get Started,
Fundamentals (23 entries, covering everything from architecture to the MCP server page
itself), and further sections for Interacting with Erigon (JSON-RPC/GraphQL/gRPC) beyond
what was read in full. `llms-full.txt` is roughly 31× larger — the same convention's
"full-text" variant, presumably the same page set with full body content inlined rather than
just links, though this document does not claim to have read all 397KB of it in full (link
list confirmed; full-text-inlining format inferred from the file-size ratio and llms.txt
naming convention, not verified line-by-line). Neither file overlaps with `agents.md` — this
is documentation aimed at *any* LLM answering questions about how to *use* Erigon as an
operator, not at coding agents modifying Erigon's own source.

**`docs/plans/`** is a lightweight, Spec-Kit-adjacent planning convention with no tooling
behind it (no CLI, no templates directory, no YAML frontmatter schema — just a Markdown
naming and section convention). Active plans (confirmed via `find`, 5 objective/spec pairs
plus 2 standalone specs and 1 standalone plan as of this read) each split into a
`<slug>-objective.md` (short: 24–52 lines) and a `<slug>-spec.md` (long: 133–371 lines).
Read in full: `20260326-prefix-index-standalone-objective.md` (52 lines) and its paired
`-spec.md` (352 lines). The objective file's section shape is **Problem** (numbered,
concrete pain points — e.g. "BpsTree complexity: BpsTree maintains two search paths... and
dead weight (`M` field, `trace` field) that a prefix-first design doesn't need"), **Goal**
(a numbered list of what the new type must do), a **Key Insight** section with a
before/after ASCII comparison specific to this change (global M-th sampling vs. per-prefix
even distribution), **Success Criteria** (numbered, independently verifiable — including a
literal benchmark comparison requirement: "`BenchmarkBpsTreeSeek` with 12M keys: equal or
better performance vs current prefix index"), and **Non-Goals** (explicitly scoping out
on-disk-format changes, domain-level API changes, sub-2-byte keys, and full removal of the
old type in this same change). The spec file's shape is far more elaborate than a typical
Spec-Kit `spec.md`: Overview, Architecture (before/after ASCII diagrams), Data Structures
(Go struct definitions), a phased Building Strategy with a **Context (key code locations)**
table citing exact file:line ranges in the *existing* codebase (e.g. "`BpsTree.WarmUp()`
(prefix build) | `bps_tree.go` | 317-384"), a Development Approach note ("Testing approach:
Regular (code first, then tests)" — explicitly *not* the root `agents.md`'s TDD-by-default
policy for this particular plan, called out as a deliberate exception), a fully numbered,
checkbox-driven **Implementation Tasks** section (six tasks, each with explicit file
creates/modifies and a design-note subsection reasoning through three named alternative
approaches with tradeoffs before recommending one), an Edge Cases table, a Memory Budget
table with worst-case/realistic estimates, and a Risks and Mitigations table. Task 6's final
checklist item is literally "Move plan to `docs/plans/completed/`" — the self-documenting
archival step.

**Archival convention, verified**: `docs/plans/completed/` holds 15 files as of this read,
including a duplicate of the fold-refactor pair also still present in the active
`docs/plans/` directory — `diff` confirms the two copies are byte-identical, and `git log`
shows the active-directory copy was last touched four days *after* the completed-directory
copy, meaning the "move to completed" step was not actually followed for this one plan (a
copy exists in both places rather than a clean move) — a minor, concrete instance of the
convention being informally enforced (a checklist item in the spec file, not a script or CI
check) and therefore occasionally skipped, not evidence the convention itself is
unreliable.

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable / Already have equivalent | Reasoning |
|---|---|---|
| Root `agents.md` load-triggered directory table (Key Directories, lines 27–36) | Already have equivalent | fukuii's own `CLAUDE.md` "Reference index" + "Specialist subagents" tables serve the same lazy-load routing purpose; the mechanism (Markdown table with per-row link/purpose) is the same shape. |
| Root `CLAUDE.md` = filesystem symlink to `agents.md` | Not portable (as-is) | fukuii deliberately layers Claude-Code-only orchestration (named subagents, Spec Kit, sprint tooling) on top of the `@AGENTS.md` import; a bare symlink would erase that layer. Same conclusion reached for Nethermind's equivalent zero-orchestration import. |
| Four subsystem `agents.md` breadcrumbs (`cl/`, `db/`, `p2p/`, `execution/stagedsync/`) | **Port now** | fukuii has explicitly identified `blockchain/sync/`, `consensus/`, `db/` as candidate locations for this exact pattern (planned, not yet built, per fukuii's own current-state audit). Erigon's four files are a directly usable template: one-line subsystem description, directory/component table, 2-4 short topical subsections, no code beyond one struct-shape snippet. Low effort, concrete payoff — closes a gap fukuii has already scoped for itself. |
| Nested `cl/CLAUDE.md` → three deep, function-level consensus-spec-map files (`forkchoice/`, `transition/`, `core/state/`) | **Needs design** | This is the single richest artifact in the whole clone and maps almost exactly onto fukuii's `forge`/`beacon` PoW/PoS consensus split — but as *static, checked-in* per-function spec-citation tables (Go file/function → spec section, fork-by-fork), rather than living entirely inside a subagent's working memory. fukuii's `forge.md`/`beacon.md` subagent definitions currently carry this knowledge as prose/protocol references, not as a maintained, greppable table. Building fukuii-equivalent tables (`consensus/` file/function → ECIP/EIP section, Olympia-fork-annotated) would be high-value but is a substantial standalone documentation project, not a quick port — start with the highest-churn consensus files (fork-dispatch, gas-cost tables) rather than attempting full coverage at once. |
| `.claude/hooks/comment-policy.py` (advisory `PostToolUse` hook, three narrow regexes, never blocks) | **Port now** (design adapted to Scala) | fukuii has confirmed zero `.claude/hooks/` today. This is a small, low-risk, well-scoped example: three conservative regexes, advisory-only (never blocks, never fails a commit), targeted at a real recurring problem (inline comments carrying incident/forensic narration that belongs in commit messages). A Scala-adapted version would target `//`/`/**` comment lines the same way, with fukuii-specific patterns (e.g. flagging git-blame-style narration, sprint/batch references, or `@nowarn` justification text that duplicates `warning-ratchet.md`'s own log). Directly buildable from this file as a template. |
| `.claude/settings.json` `includeCoAuthoredBy: false` + narrow Bash/WebFetch allow-list | Not portable (as literal config) / Needs design (concept) | fukuii's global CLAUDE.md already forbids adding `Co-Authored-By: Claude` unless the commit-workflow explicitly calls for it (a user-level convention, not a per-repo settings flag); the *specific* allow-list entries (`go test`, `make lint`, etc.) are Go-toolchain-specific. The general pattern — a checked-in, narrow `permissions.allow` list scoped to the actual commands a repo's agents need — is worth fukuii adopting for its own `.claude/settings.json` (currently absent/untracked per fukuii's own audit) once fukuii decides its own tool allow-list. |
| `.claude/rules/comments.md`, `branch-naming.md`, `lint-fixes.md` | Not portable (branch-naming, lint-fixes) / Already have equivalent (comments policy) | `lint-fixes.md` is Go/`golangci-lint`-specific with no Scala/`scalafix` equivalent worth forcing; `branch-naming.md`'s per-contributor convention-sampling approach is interesting but fukuii already has its own `worktree-protocol.md` (`wt/<id>` branches) covering the same ground differently, by design. The comments policy itself is functionally equivalent to fukuii's own comment-discipline expectations (implicit in `AGENTS.md`'s working-discipline section) — no gap, though Erigon's three-tier enforcement (prose + rules-file + hook) is more rigorous than fukuii's prose-only approach today. |
| `.claude/skills/` (26 skills: `autoresearch`, `erigon-build`, `erigon-test-*` ×5, `erigon-seg-*` ×3, `hive-test`, `kurtosis-test`, `launch-devnet`, etc.) | Not covered here | Per this task's scope, the full skill catalog is intentionally left to the dedicated sibling document `dev-workflow-skills-pattern.md`, to avoid duplicating a per-skill assessment across two files that could drift out of sync — count and directory confirmed present (26 entries via `ls`), no further analysis performed here. |
| `.github/workflows/claude.yml` — mention-triggered Claude Code Action, `contents: write`, **no fork-PR/author-association gate** | **Not portable as-is; Needs design if fukuii builds a CI review bot** | This is the fork-PR security gate finding this task was asked to verify: **confirmed absent** in Erigon's current file — a plain `contains(..., '@claude')` substring check with no `author_association` allow-list and no same-repo-PR restriction, materially more permissive than Nethermind's `claude-review.yml`. fukuii should **not** copy this workflow's trigger logic if it ever builds a CI-integrated review bot — Nethermind's `claude-review.yml` (already documented as fukuii's single biggest CI gap, "port now" in the sibling Nethermind doc) remains the correct reference implementation to build from; this file is useful only as a negative example of what to avoid. |
| Shipped node-level MCP server (embedded `--mcp.*` flags + standalone `mcp` binary, 40+ tools, 3 connection modes, stdio+SSE transports) | **Needs design** | fukuii's `.github/copilot/` MCP server is real but much narrower (5 tools with unimplemented schemas, 1 transport, no embedded-in-binary variant). Erigon's dual-variant design (embedded default-on + standalone with three connection-priority modes) is a good target shape, but building it requires fukuii to first flesh out its own MCP tool schemas and decide whether an embedded-in-JVM-binary MCP server is worth the added attack surface on a consensus client — a genuine design question, not a quick port. |
| `llms.txt` / `llms-full.txt` | **Port now** (llms.txt only) | fukuii has confirmed zero `llms.txt` today. The llms.txt convention itself is trivial to adopt for fukuii's own docs (a curated link index over `docs/`, `docs/development/contributing.md`, `docs/research/`, etc.) — low effort, no dependency on anything else in this table. `llms-full.txt` (a much larger full-text inline variant) is lower priority — worth deferring until fukuii's docs are large/stable enough to justify a ~30×-larger generated artifact. |
| `docs/plans/` objective+spec pairing, `docs/plans/completed/` archival | Already have equivalent (fukuii's is more formalized) | fukuii's `.specify/` Spec Kit framework (versioned constitution, CLI-driven `/speckit-*` pipeline, YAML-templated `plan.md`/`tasks.md`) is a strictly more tooled version of the same problem Erigon's informal `<slug>-objective.md`/`<slug>-spec.md` pairing solves by convention alone. The one thing genuinely worth borrowing is Erigon's spec-file discipline of embedding a "Context (key code locations)" table with exact file:line citations into *existing* code as part of the spec itself — fukuii's `plan-template.md`/`spec-template.md` could adopt that specific subsection if not already present, but this is a template tweak, not a new system. |
