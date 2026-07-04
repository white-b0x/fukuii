# Reference-client repo-pattern research — index

A sequential, per-client research swarm (one client at a time, 4 parallel agents each
covering a distinct angle, each writing directly into its own file) surveyed 6 vendored
Ethereum-client full clones, the shared Hive interop-test framework, and fukuii's own
current state — 26 documents, ~14,700 lines total, every claim file:line-cited against the
real vendored source. This replaces the earlier single-file
`docs/research/best-practices/evm-clients/repo-patterns.md`, which was summary-level and
demonstrably missed things (see `IMPLEMENTATION_PLAN.md`'s framing note).

**Start here:** `IMPLEMENTATION_PLAN.md` — the tiered rollout roadmap synthesizing all 26
docs into concrete, prioritized fukuii action items. This README is a pure index.

## Per-client documents

| Client | Docs | Headline finding |
|---|---|---|
| **fukuii** (self-audit) | [agentic-tooling](fukuii/agentic-tooling-pattern.md), [dev-workflow-skills](fukuii/dev-workflow-skills-pattern.md), [repo-hygiene](fukuii/repo-hygiene-pattern.md), [build-release](fukuii/build-release-pattern.md) | fukuii's own baseline, verified fresh — found a duplicated section in `AGENTS.md` (fixed), 2 stale-README drift issues (fixed), and corrected the skill-inventory framing (19 operator / 3 dev-workflow / 1 project-mgmt / 13 speckit, not "zero dev-workflow") |
| **Nethermind** | [agentic-tooling](nethermind/agentic-tooling-pattern.md), [dev-workflow-skills](nethermind/dev-workflow-skills-pattern.md), [repo-hygiene](nethermind/repo-hygiene-pattern.md), [build-release](nethermind/build-release-pattern.md) | Production `claude-review.yml` CI review bot (the primary reference for fukuii's own) — plus a second, previously-unseen Claude Code Action usage (`gas-benchmark-analysis.yml`) |
| **Erigon** | [agentic-tooling](erigon/agentic-tooling-pattern.md), [dev-workflow-skills](erigon/dev-workflow-skills-pattern.md), [repo-hygiene](erigon/repo-hygiene-pattern.md), [build-release](erigon/build-release-pattern.md) | Richest agentic tooling surveyed: 26 skills (not 33 as first estimated), 4 per-subsystem `agents.md` breadcrumbs, a *nested* `cl/CLAUDE.md` tree with function-level Go-to-spec maps, a shipped node-level MCP server, and a manifest-driven EF-test sharding pattern (`tools/eest-spec-shards.yml`) |
| **Reth** | [agentic-tooling](reth/agentic-tooling-pattern.md), [dev-workflow](reth/dev-workflow-pattern.md), [repo-hygiene](reth/repo-hygiene-pattern.md), [build-release](reth/build-release-pattern.md) | `HARDFORK-CHECKLIST.md` (quoted in full) is the standout portable artifact; its own `.claude/settings.local.json` is actually **committed** to git history, not just untracked — a stronger cautionary tale than expected |
| **go-ethereum** | [agentic-tooling](go-ethereum/agentic-tooling-pattern.md), [dev-workflow](go-ethereum/dev-workflow-pattern.md), [repo-hygiene](go-ethereum/repo-hygiene-pattern.md), [build-release](go-ethereum/build-release-pattern.md) | `docs/postmortems/2021-08-22-split-postmortem.md` is the standout artifact of the *entire* survey; `FuzzRLP` is a genuine zero-infra fuzzing pilot, already adapted into a concrete ScalaCheck design for fukuii's own RLP codec |
| **Besu** | [repo-hygiene](besu/repo-hygiene-pattern.md), [build-release](besu/build-release-pattern.md) | `gradle/verification-metadata.xml` (dependency hash-pinning, tool-agnostic supply-chain idea); confirms CODEOWNERS-at-scale is genuinely optional (empty file at 20 maintainers) |
| **core-geth** | [repo-hygiene](core-geth/repo-hygiene-pattern.md), [build-release](core-geth/build-release-pattern.md) | `docs/core/index.md` is the direct template for fukuii's planned `FORK_DIVERGENCE.md`; its `bench-core/trie/vm.yml` is a genuinely lightweight benchmark-CI pattern (plain `go test -bench` + `benchstat`, no heavy infra) |
| **Hive** (shared testing infra, not a peer client) | [architecture](hive/architecture-pattern.md), [fukuii-test-hive skill design](hive/fukuii-test-hive-skill-design.md) | Found real, actionable bugs in fukuii's own Hive integration: `hive-pyspec.yml` targets a simulator directory upstream deleted (broken workflow), `hive-osaka.yml`/`hive-prague.yml` duplicate the whole pipeline instead of reusing `_hive-sim.yml`, and fukuii is silently excluded from the `ethereum/sync` snapsync suite (missing `eth1_snap` role handling) despite having real SNAP sync |

## Cross-reference

- Original (superseded) summary-level survey: `agentic-tooling-refresh-2026.md` and
  `reference-clients-gap-analysis-2026.md` in `docs/agentic-tooling/` — those docs' framing
  ("fukuii is way ahead, mine 2 clients for a few items") was corrected by this deeper
  swarm into "fukuii leads on raw mechanism, but half the field has adopted AGENTS.md
  already and there's a large, genuinely useful cross-client tooling surface to mine."
- Execution record: `docs/agentic-tooling/agentic-tooling-refresh-2026.md`'s Execution log
  section documents what was already implemented before this swarm ran.
