# Fukuii operations skills

Project-scoped [Agent Skills](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview)
that turn Fukuii's **maintenance and node-management runbooks** into repeatable,
guard-railed procedures. Each skill encodes *one* operational workflow, calls the
node's real interfaces (JSON-RPC `admin_*` / `eth_*` / `miner_*`, the in-process
MCP tools, the `fukuii cli` subcommands), and links back to the authoritative
runbook under `docs/runbooks/` for deep reference.

The goal is **consistent prompting and control**: every operator and every agent
runs the same validated steps against the same endpoints, with the same safety
gates, instead of improvising one-off commands.

## Why skills (not just docs or tools)

| Form | Fukuii has | Good for |
| :--- | :--- | :--- |
| **Tools** (atomic calls) | JSON-RPC `admin_*`/`eth_*`/`miner_*`, 15 read-only MCP tools, `fukuii cli` | One deterministic operation |
| **Runbooks** (prose) | `docs/runbooks/`, `docs/operations/` | Human reading, reference depth |
| **Skills** (this dir) | — | Multi-step *workflows* that orchestrate tools + judgement + safety gates, loaded on demand |

Skills sit on top of the existing tools. They do **not** replace the runbooks —
they reference them (Level-3 progressive disclosure), so the runbook stays the
single source of truth.

## Skill index

| Skill | Workflow | Backing runbook(s) |
| :--- | :--- | :--- |
| `fukuii-node-health-check`   | Full health verdict: status, sync, peers, height, log scan | operations/metrics-and-monitoring, MCP health prompt |
| `fukuii-sync-troubleshooting`| Diagnose stalled/slow SNAP or full sync; tuning levers | runbooks/snap-sync-*, operations/monitoring-snap-sync |
| `fukuii-peer-management`     | Inspect/add/remove/trust peers, maxPeers, static nodes | runbooks/peering, network-management; for-operators/static-nodes |
| `fukuii-backup-restore`      | Back up & restore datadir/keys; export/import chain | runbooks/backup-restore |
| `fukuii-disk-management`     | Disk-pressure triage, pruning, datadir sizing | runbooks/disk-management |
| `fukuii-log-triage`          | Set log level at runtime; pattern-triage logs | runbooks/log-triage, operations/LOGGING |
| `fukuii-mining-operations`   | Validate & control ETC mining via `miner_*`/`eth_*` | runbooks/mining-operations |
| `fukuii-key-management`      | Generate/encrypt keys & genesis allocs via `fukuii cli` | cli/CliCommands, runbooks/first-start |
| `fukuii-tls-operations`      | TLS for JSON-RPC; cert rotation | runbooks/tls-operations, security |
| `fukuii-checkpoint-service`  | Operate the checkpointing service | runbooks/checkpoint-service |
| `fukuii-node-configuration`  | Edit `fukuii.conf`, pick operating mode safely | runbooks/node-configuration, operating-modes |
| `fukuii-first-start`         | Bootstrap a brand-new node end to end | runbooks/first-start |
| `fukuii-security-hardening`  | IP block/unblock, trusted peers, RPC exposure review | runbooks/security; admin block/trusted methods |
| `fukuii-custom-networks`     | Stand up a private/consortium/custom-genesis chain | runbooks/custom-networks, enterprise-deployment |

## ETH/Sepolia — PoS operations

ETH/Sepolia is post-Merge Proof-of-Stake: the Execution Layer (Fukuii) is driven
by a Consensus Layer (CL) client over the Engine API. These skills cover that
pairing; they do **not** apply to ETC/Mordor (PoW, no CL).

| Skill | Workflow | Backing source |
| :--- | :--- | :--- |
| `fukuii-cl-setup`            | Pair a CL (Prysm/Lighthouse/Teku) with Fukuii; JWT, flags, startup order, 401 liveness check | `fukuii-engine-api-setup`, CL client docs |
| `fukuii-engine-api-setup`    | EL-side Engine API authrpc config: JWT secret, enable authrpc, verify handshake | base/network.conf, ops sepolia.conf |
| `fukuii-engine-api-debug`    | Troubleshoot Engine API faults: JWT 401, forkchoice timeout, newPayload INVALID, version mismatch, EL-vs-CL localization | sepolia-chain.conf, BEACON agent |
| `fukuii-sepolia-sync`        | Sepolia sync: pre/post-Merge phases (block 1735371), CL-driven import, post-Cancun blobs | base/chains/sepolia-chain.conf |
| `fukuii-pos-node-health`     | PoS-side health: CL↔EL liveness, attestation participation, blob gossip peers, Engine API latency | `fukuii-node-health-check`, Sepolia staking dashboard |

## Build & dependency

| Skill | Workflow | Backing source |
| :--- | :--- | :--- |
| `fukuii-dependency-audit`    | Audit all library versions; flag stale, CVE-affected, or non-LTS deps | `build.sbt`, endoflife.date, CVE feeds |
| `fukuii-tech-debt-inventory` | Inventory technical debt: deprecated APIs, suppressed warnings, TODO/FIXME, scalafmt violations | Source scan + scapegoat report |
| `pekko-resource-audit`       | Audit for uncancelled timers, missing `watchWith` cleanup, stream materialization leaks, dispatcher starvation (ported from Nethermind's `resource-leak-audit`) | `.agents/protocols/code-style/pekko-typed-api.md` |
| `fukuii-pr-preflight`        | Pre-flight a branch against everything CI gates before opening/updating a PR against `chippr-robotics/fukuii`: scalafmt, compile, Tier 1 tests, conditional docs-build + doc-link check | `pr-preflight-checklist.md`, `pr-preflight.sh` |
| `fukuii-sprint-queue`        | Report status of, clear closed batches from, and archive logged batches out of the permanent sprint queue at `.claude/sprints/` | `sprint-lifecycle.md` |
| `fukuii-sprint-research`     | Pre-implementation research on a `QUEUE.md` batch before its kickoff prompt is trusted — multi-tree sweep, precedent lookup, pre-flight health check, drafts the kickoff prompt | `scout` subagent, `batch-research-protocol.md` |
| `fukuii-implement-eip`       | Structured EIP (→`beacon`)/ECIP (→`forge`) implementation pipeline: spec-fetch → dependent-spec map → prior-work check → implement → mandatory forge/beacon consult → wraith → eye → wrap-up summary | `consensus-change-protocol.md`, `/media/dev/2tb/dev/ECIPs/_specs/` (ported from Erigon's `erigon-implement-eip`) |
| `fukuii-build`               | Build fukuii — `sbt compile-all` (default) or `sbt assembly` (runnable JAR) via `sbt-run.sh`, backgrounded | `sbt-run.sh` (ported from Erigon's `erigon-build`) |
| `fukuii-test-unit`           | Run the fast unit-test tier — `sbt testEssential` via `sbt-run.sh`, or `sbt "testOnly *Foo*"` directly for a single class | `sbt-run.sh` (ported from Erigon's `erigon-test-unit`) |
| `fukuii-test-all`            | Run fuller local tiers (`testStandard`/`testComprehensive`) plus dispatch/monitor fukuii's real CI workflows via `gh workflow run`/`gh run watch` | `sbt-run.sh`, `.github/workflows/*.yml` (ported from Erigon's `erigon-test-all`/`erigon-ci`) |
| `fukuii-network-ports`       | Canonical port-reference table (P2P 30303 TCP+UDP, RPC HTTP 8546, WS 8552, Engine API 8551, metrics 13798) other skills point to instead of re-deriving | `network.conf`, `metrics.conf` (ported from Erigon's `erigon-network-ports`) |
| `fukuii-ephemeral`           | Launch a throwaway Fukuii instance in a `mktemp` datadir with port-conflict avoidance (+100/+200/+300 escalation) and leftover-instance detection | `fukuii-network-ports` (ported from Erigon's `erigon-ephemeral`; clone-datadir mode deferred) |
| `fukuii-test-hive`           | Build a local Fukuii Docker image and run one or more `ethereum/hive` simulator suites against it — the local counterpart to fukuii's 13 CI `hive-*.yml` workflows | `hive/fukuii/`, `_hive-sim.yml`, `hive-run.sh` (design: `hive/fukuii-test-hive-skill-design.md`; ported from Erigon's `hive-test`/`erigon-test-hive`) |
| `fukuii-benchmark-diff`      | Run the local `benchmark:testOnly` suites (MPT, RLP), extract timing lines via regex, store a baseline, diff two sequential runs as a before/after delta table | `build.sbt`'s `Benchmark` config, `bench-mpt.yml`/`bench-rlp.yml` (local-only subset ported from Nethermind's `gas-benchmark`) |

**Category note**: everything above this line groups as "operator/node-lifecycle"
(node health, sync, peers, backup, disk, logs, mining, keys, TLS, checkpointing, config,
first-start, security, custom networks, PoS/CL pairing). This "Build & dependency" section
plus `pekko-resource-audit` group as "developer-workflow" (code/build/test-quality, not node
operation) — see `docs/research/best-practices/evm-clients/repo-patterns/IMPLEMENTATION_PLAN.md`
Tier 6 for further developer-workflow skills planned to land here (EF-test triage,
benchmark diffing, an RLP round-trip property test). No physical directory reorganization was
done for skills (Claude Code's support for nested skill discovery is unconfirmed —
see `.agents/protocols/tooling/agent-skills.md`); this index is the categorization mechanism instead.

## Spec Kit — Bug triage

Three-step structured bug triage. Artifacts land in `.specify/bugs/<slug>/`
and can be referenced in PRs. The workflow is strictly sequential.

| Skill | Step | What it produces |
| :--- | :--- | :--- |
| `speckit-bug-assess` | 1 — Assess | `.specify/bugs/<slug>/assessment.md`: root cause, reproduction steps, remediation options |
| `speckit-bug-fix`    | 2 — Fix    | `.specify/bugs/<slug>/fix.md`: changes applied, tests added, `sbt testEssential` result |
| `speckit-bug-test`   | 3 — Verify | `.specify/bugs/<slug>/test.md`: symptom reproduction verdict (`verified`/`partial`/`failed`) |

## Validation

Every interface these skills name (RPC method, MCP tool/prompt, `fukuii cli`
subcommand, config key) is cross-checked against the node source on this branch —
see [`VALIDATION.md`](./VALIDATION.md) for the method, the last result, and the
runbook-vs-code drift the check caught. **Re-run it whenever the RPC surface,
CLI, or config schema changes.**

## Shared conventions

Every skill assumes the contract in [`CONVENTIONS.md`](./CONVENTIONS.md):
how to locate the node, call its RPC, and — most importantly — the
**guarded-write protocol** (confirm before any state-changing or irreversible
action). Read it once; skills reference it rather than repeating it.

## Authoring / extending

New operational workflow? Add `.claude/skills/<verb-noun>/SKILL.md` following the
template in [`CONVENTIONS.md`](./CONVENTIONS.md#skill-authoring-template), point it
at the relevant runbook, and add a row above. Keep `SKILL.md` bodies lean (the
heavy reference lives in `docs/`); the frontmatter `description` must say **what**
the skill does **and when** to use it.
