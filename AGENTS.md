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

## Status: clean-write rebuild in progress — read the plan before working in `modules/`

fukuii is being **rebuilt from scratch, layer by layer**, under `modules/` in the new namespace
`com.chipprbots.fukuii.*`. The old IOHK-Mantis-lineage tree (`com.chipprbots.ethereum.*`, formerly
under `src/`) is **reference-only** and preserved on branch `july-fourth`; on the working branch it
is **gone — only `modules/` exists**. The layer-by-layer plan, the module DAG, and per-layer status
live in [`docs/architecture/fukuii-rebuild/`](docs/architecture/fukuii-rebuild/) — read
[`plan/README.md`](docs/architecture/fukuii-rebuild/plan/README.md) first. The **domain semantics**
below (PoW vs PoS, consensus rules) are durable and carry into the rebuild; the **exact file
paths/APIs** cited elsewhere in this file are the reference-tree (`july-fourth`) locations — the
rebuild relocates them into `modules/<layer>/` and modernizes them per the plan, and **this file is
updated inline as each layer lands** (see "Key Directories" and the per-layer record docs).

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
are network-prefixed** — `EthCancunOpCodes`/`EthOsakaOpCodes` and the
`EthLondon…→EthCancun…→EthPrague…→EthOsakaFeeSchedule` chain are ETH's;
`EtcOlympiaOpCodes`/`EtcOlympiaFeeSchedule` are ETC's Olympia (ECIP-1121) objects.
Batch 5 Row 5.1 (commit `b46e21ea1`) removed the earlier trap where the unprefixed
`OlympiaOpCodes` was actually ETH's Cancun list; those unprefixed names no longer
exist. The `scala3-style.md` ratchet enforces that no `Eth*` references or extends an
`Etc*` (and vice-versa) — never let a fork codename stand for the wrong network.

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

Modules (clean-write, under `modules/`, namespace `com.chipprbots.fukuii.*`; `build.sbt` is
authoritative): `bytes` · `common` · `crypto` · `rlp` (L0) · `domain` (L1) · `storage` · `trie` (L2)
· `evm` (L3) · `execution` (L4) · `consensus` (L5) · `network` (L6) · `sync` (L7) · `rpc` (L9) ·
`node` (L10, composition root). Integration/Benchmark/Evm/Rpc test suites are sbt **configs scoped
within `node`**, not separate modules. The per-layer plan (scope, dependencies, further splits the
plan proposes — e.g. `consensus` → `-api`/`-pow`/`-pos`, the L8 peripheral split) is
`docs/architecture/fukuii-rebuild/plan/`. Only built layers have real code; see the rebuild
`README.md` index for per-layer status. (The old single-`main` + `Evm`/`RpcTest`/`IntegrationTest`
module layout was the pre-rebuild `src/` tree — reference-only on `july-fourth`.)

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

During the clean-write rebuild the **structural authority is the layer plan**,
[`docs/architecture/fukuii-rebuild/plan/`](docs/architecture/fukuii-rebuild/plan/) — one doc per
layer (L0→L10) with scope, down-only dependencies, per-concern reference authorities, and design.
**Read the relevant `plan/L{n}.md` before making structural changes in `modules/<layer>/`.**

Per-module subsystem breadcrumbs (`modules/<name>/AGENTS.md`) are added **as each layer is built and
gated** — the rebuild's inline-maintenance rule: a layer's completion updates this section and drops
its breadcrumb, the same commit that lands its record doc (`docs/architecture/fukuii-rebuild/NN-*.md`).
Until a layer lands, its `plan/L{n}.md` *is* the breadcrumb.

| Module (when built) | Breadcrumb | Plan |
|------|-----------|------|
| `modules/domain` (L1) — **built** | [`modules/domain/AGENTS.md`](modules/domain/AGENTS.md) | [`04-L1-domain.md`](docs/architecture/fukuii-rebuild/04-L1-domain.md) — value types, `enum Transaction` + EIP-2718 dispatch, sender recovery (EIP-155/H-1/N-1/7702), fork-variant `BlockHeader` + Block/Body + Receipt |
| `modules/storage`, `modules/trie` (L2) | (added when built) | [`plan/L2.md`](docs/architecture/fukuii-rebuild/plan/L2.md) — RocksDB `DataSource` + MPT, byte-pure storage seam |
| `modules/consensus` (L5) | (added when built) | [`plan/L5.md`](docs/architecture/fukuii-rebuild/plan/L5.md) — PoW/PoS dual-family dispatch + `NetworkFamily`; mandatory gate, see `consensus-change-protocol.md` |
| `modules/sync` (L7) | (added when built) | [`plan/L7.md`](docs/architecture/fukuii-rebuild/plan/L7.md) — fast/regular/SNAP/checkpoint strategies |

(The old `src/main/scala/com/chipprbots/ethereum/{blockchain/sync,consensus,db}/AGENTS.md`
breadcrumbs are retired with that tree — see branch `july-fourth` for the reference implementation.)

## Where agent protocols and skills actually live

Shared protocol docs and skill definitions are canonically edited under `.agents/protocols/`
and `.agents/skills/` (tool-agnostic source) — `.claude/agent-protocols/` and
`.claude/skills/` are symlinks into them, kept for Claude Code's own discovery paths. See
`.agents/protocols/tooling/agent-skills.md` for the convention. CLAUDE.md's protocol table and
Specialist subagents section stay Claude-Code-specific orchestration and are not duplicated
here.
