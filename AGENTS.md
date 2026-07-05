# AGENTS.md — Working in fukuii

_Last reviewed: 2026-07-03. This is the portable, tool-agnostic project context — the same
content any coding agent (Claude Code, Codex, Cursor, Copilot, Aider, ...) should load first.
Claude Code-specific orchestration (named subagents, Spec Kit, sprint tooling) lives in
`CLAUDE.md`, which imports this file via `@AGENTS.md` rather than duplicating it. See
`docs/agentic-tooling/agents-md-decision-2026.md` for why this split exists and what
stays out of this file on purpose._

`fukuii` is a **multi-network EVM client** (forked from IOHK Mantis, repackaged
under `com.chipprbots`), running on **Scala 3.x LTS** with Pekko actors.
It supports two independent consensus families, each of which can host more
than one network — currently one network pair per family:

- **Proof-of-Work (PoW)** — block-number fork dispatch. Currently: Ethereum
  Classic (ETC/Mordor) — PoW/Ethash, ECIP-1017 fixed-supply emission.
  Chain ID 61 (mainnet), 63 (Mordor).
- **Proof-of-Stake (PoS)** — timestamp fork dispatch. Currently: Ethereum
  (ETH/Sepolia) — post-merge PoS. Chain ID 1 (mainnet), 11155111 (Sepolia).

Network coverage is expected to grow over time within each family — treat
"ETC/Mordor" and "ETH/Sepolia" below as the current instances of "PoW" and
"PoS", not the ceiling of what fukuii supports.

## PoW vs PoS — read this first

**ETC keeps**: PoW/Ethash, ECIP-1017 fixed-supply emission, traditional gas
model, pre-merge opcodes, block-number fork dispatch. Hard forks: Atlantis →
Agharta → Phoenix → Thanos (ECIP-1099) → Magneto → Mystique → **Olympia**
(planned: ECIP-1111/1112/1121).

**ETH/Sepolia has**: PoS consensus, timestamp fork dispatch, EIP-1559 base-fee
burned, validator withdrawals, blob transactions (EIP-4844), Osaka fork.

**Do not mix these code paths.** ETC's block-number fork dispatch is
`EvmConfig.forBlock(blockNumber, blockchainConfig)`; ETH's timestamp-based fork
overlay is the same method's overload, `EvmConfig.forBlock(blockNumber,
timestamp, blockchainConfig)` — there is no separate `forTimestamp()` method.
**Fork-named opcode/fee-schedule objects in `vm/OpCode.scala`/`vm/EvmConfig.scala`
are not reliably named after the network that actually uses them** —
`EtcOlympiaOpCodes` is ETC's real Olympia list, while the unprefixed
`OlympiaOpCodes` is what ETH's Cancun/Osaka path actually uses (`OsakaOpCodes`
is currently a bare alias to it, not an independent definition). Never assume a
name's network from its label alone — see `PARITY-02`
(`.claude/sprints/QUEUE.md`) before touching any of these objects.

## Build & test commands

| Command | What it runs | When to use |
|---------|-------------|-------------|
| `sbt compile-all` | Compiles all modules + test sources | After every file change (exception: core domain sweeps — use `sbt compile` instead, then `compile-all` once at end; see `.claude/agent-protocols/testing-protocol.md`) |
| `sbt compile` | Root main sources only — no test/IT/Benchmark | **Core domain type sweeps only** (BlockHeader, Account, Block, Transaction → 50+ dependents) — avoids cascade on every file; `compile-all` once at end |
| `sbt scalafmtAll` | scalafmt across ALL modules (formatting only) | After every migration commit; safe at any time |
| `sbt scalafmt` | scalafmt on ROOT module only | **Do not use** — misses submodules (bytes, crypto, rlp, Evm, etc.) |
| `sbt formatAll` | scalafixAll + scalafmtAll across all modules | Pre-PR on a clean codebase ONLY — aborts on pre-existing scalafix violations |
| `sbt formatCheck` | Verify formatting without writing | CI / pre-flight check |
| `sbt pp` | `compile-all` + per-module `scalafmtAll` (bytes/crypto/rlp) + root `scalafmtAll` + `rlp/test` + `testQuick` + `IntegrationTest/test` | Pre-PR smoke pass. **Does not run scalafix** — it will not abort on pre-existing scalafix violations the way `formatAll` does. Run `formatAll` separately if a scalafix pass is needed. |
| `sbt "testOnly *Foo*"` | Single test class (seconds) | After each phase that changes logic — not compile-only phases |
| `scripts/agent-tooling/sbt-run.sh <name> testEssential` (background) | Tier 1 full suite | **Pre-push only.** Check `.claude/sprints/QUEUE.md`'s status block before relying on this as a gate — its test count and duration drift over time, and the suite can be blocked repo-wide by pre-existing compile errors independent of your change. `QUEUE.md` is the live source of truth, not a hardcoded number here. Run with `run_in_background: true` — see `.claude/agent-protocols/background-script-execution.md` |
| `scripts/agent-tooling/sbt-run.sh <name> testStandard` (background) | Tier 2 tests | Before opening a PR |
| `scripts/agent-tooling/sbt-run.sh <name> testComprehensive` (background) | Tier 3 full compliance suite | Release gate only |
| `sbt testVM testCrypto` | Tagged test subsets | Targeted validation of specific subsystem — `build.sbt`'s `addCommandAlias` block defines many more (`testConsensus`, `testRPC`, `testState`, `testOlympia`, `testSync`, `testNetwork`, `testDatabase`, `testRLP`, `testMPT`, `testEthereum`, `testEthSmoke`, `testAll`, `testCoverage`) — treat `build.sbt` as the authoritative list rather than this table |
| `scripts/agent-tooling/sbt-run.sh <name> "IntegrationTest / test"` (background) | Integration test module | After protocol-level changes |

**Test cadence during a migration thread:**
1. Every file edit → `sbt compile-all` (mandatory, fast) — **exception**: if the sweep touches a core domain type (BlockHeader, Account, Block, Transaction), use `sbt compile` between files and `sbt compile-all` once at the end (see `testing-protocol.md` → "Core domain type sweeps")
2. Phases that only add types (returns removal, Messages.scala additions) → compile only, no tests
3. After the main migration phase and the callers phase → `testOnly *<ActorName>*` + any touched caller specs
4. Before pushing to origin → full `testEssential` via `sbt-run.sh` (pre-push gate, not mid-sprint)

**The two format commands that look similar but are not:**
- `scalafmt` → root module only → **wrong for this codebase**
- `scalafmtAll` → all modules → **always use this one**
- `formatAll` → runs scalafix first → aborts mid-migration on pre-existing violations → **pre-PR only**
- `pp` → does **not** run scalafix, does **not** inherit `formatAll`'s abort behavior — it's a smoke pass, not a substitute for `formatAll`

Modules: root `main`, plus `bytes`, `crypto`, `rlp`, `Evm`, `Benchmark`,
`RpcTest`, `IntegrationTest`.

## Working discipline (applies to every task)

- **Sequential thinking before action.** State what you understand, what you
  don't, your theory, and your plan. "I don't know" is a valid output.
- **Failure is information.** When something fails, your next move is *words*:
  the raw error, your theory, the proposed step — not another blind tool call.
- **Small batches, then checkpoint.** ~3 changes, then verify reality matches
  your model (compile/test, read output, confirm). >5 actions without
  verification means you're accumulating unjustified beliefs.
- **Evidence standards.** One example is an anecdote; three may be a pattern.
  Never say "all tests pass" unless you ran them — say which tier ran. Use
  `VERIFY: ran <command> — result: PASS | FAIL | DID NOT RUN`.
- **Chesterton's Fence.** Explain why code exists (git history, the tests that
  cover it, the bug it fixed) before changing or deleting it.
- **Root cause, not symptom.** Separate the immediate cause from the systemic one.
- **Fail loudly.** No silent `catch {}` fallbacks that turn hard failures into
  quiet corruption. Let it crash; crashes are data.
- **Irreversible = 10× thought.** Consensus rules, public APIs, DB schemas, and
  git history are one-way doors. When uncertain on a consequential or
  irreversible call, surface options to the user instead of guessing.

## Conventions

- Add files to git individually; know what you're committing (avoid `git add .`).
- Run `sbt scalafmtAll` before committing during a migration. Run `sbt formatAll`
  (which includes scalafix) only before a PR on a clean codebase — `sbt pp` is a
  separate pre-PR smoke pass and does not include a scalafix pass.
- Refer to the human as **user**; be authentic, surface disagreement rather than
  burying it.

## Full contributor workflow

Fork/clone/submodule setup, pre-commit hook recipes, Scalafmt/Scalafix/Scapegoat/
Scoverage detail, async-testing patterns, release process, and the CI checklist
all live in [`docs/development/contributing.md`](docs/development/contributing.md)
— that file is tool-agnostic and current; this file does not duplicate it.

## MCP tooling for a running node

Any MCP-aware AI tool (not just GitHub Copilot, despite the directory name) can expose a
running Fukuii node's JSON-RPC as MCP tools/resources: see
[`.github/copilot/README.md`](.github/copilot/README.md) and
[`.github/copilot/mcp.json`](.github/copilot/mcp.json) for setup and available
tools/resources/prompts.

## Key Directories

Subsystem-level breadcrumbs exist for the areas below — read them before making structural
changes in that directory, not just this top-level file:

| Path | Breadcrumb | Purpose |
|------|-----------|---------|
| `src/main/scala/com/chipprbots/ethereum/blockchain/sync/` | `blockchain/sync/AGENTS.md` | Fast/regular/SNAP sync strategies + shared peer plumbing |
| `src/main/scala/com/chipprbots/ethereum/consensus/` | `consensus/AGENTS.md` | PoW (Ethash/ECIP)/PoS (Engine API) dual-family dispatch — mandatory gate, see `consensus-change-protocol.md` |
| `src/main/scala/com/chipprbots/ethereum/db/` | `db/AGENTS.md` | RocksDB storage layer behind the `DataSource` abstraction |

## Where agent protocols and skills actually live

Shared protocol docs and skill definitions are canonically edited under `.agents/protocols/`
and `.agents/skills/` (tool-agnostic source) — `.claude/agent-protocols/` and
`.claude/skills/` are symlinks into them, kept for Claude Code's own discovery paths. See
`.agents/protocols/tooling/agent-skills.md` for the convention. CLAUDE.md's protocol table and
Specialist subagents section stay Claude-Code-specific orchestration and are not duplicated
here.
