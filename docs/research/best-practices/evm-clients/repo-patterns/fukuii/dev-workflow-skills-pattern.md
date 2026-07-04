# Fukuii — Developer-Workflow Skills Baseline (self-audit)

Verified fresh against the live `.agents/skills/` directory — do not trust any prior count
from earlier context.

**Method:** `ls /media/dev/2tb/dev/fukuii/.agents/skills/` was re-run at the start of this
audit (not taken from memory or from the tool-list injected into context, which is itself
a stale/partial snapshot — see the discrepancy note below). Every one of the 36 resulting
`SKILL.md` files was read in full. Categorization below is based on what each file's body
actually instructs the agent to do, not on the skill's name or its own one-line description
where that description undersells or oversells the content.

`.claude/skills/` is a symlink farm into this same directory
(`.agents/skills/` is canonical, per `.agents/protocols/agent-skills.md`) — reading either
path returns identical content; this audit read the canonical `.agents/skills/` copies.

---

## Full current inventory

36 skill directories, each containing exactly one `SKILL.md`.

| Skill | One-line purpose (from reading SKILL.md) | Category |
|---|---|---|
| `fukuii-backup-restore` | Back up node key/keystore/config/RocksDB and restore/import a chain; hybrid backup-strategy guidance with a guarded-write tier table | operator/node-lifecycle |
| `fukuii-checkpoint-service` | Configure and diagnose ETC/Mordor checkpoint-archive rapid sync (`checkpoint-sync-file`/`-url` config keys, pivot-import gating on a fresh DB) | operator/node-lifecycle |
| `fukuii-cl-setup` | End-to-end bring-up of a Consensus Layer client (Prysm/Lighthouse/Teku/Nimbus/Lodestar) paired with Fukuii's EL for ETH/Sepolia, incl. JWT secret sharing and startup-order discipline | operator/node-lifecycle |
| `fukuii-custom-networks` | Author a custom genesis (chain id, allocs, fork schedule), wire bootstrap/static nodes, and stand up a private/consortium/L2-style network | operator/node-lifecycle |
| `fukuii-dependency-audit` | Read-only, no-running-node audit of Scala/sbt/Pekko/CE3 pin currency vs. endoflife.date and upstream changelogs, plus a BSL-1.1 Akka-import guard, for LTS/modernization-sprint planning | **developer-workflow** |
| `fukuii-disk-management` | Triage RocksDB/datadir disk usage, distinguish prunable (logs) vs. unavoidable (blockchain data) growth, and guide safe datadir relocation | operator/node-lifecycle |
| `fukuii-engine-api-debug` | Six-case decision tree for a broken EL↔CL Engine API channel (JWT 401, forkchoiceUpdated timeout, newPayload INVALID, EL SYNCING, capability mismatch, EL-vs-CL localization) | operator/node-lifecycle |
| `fukuii-engine-api-setup` | Configure the `network.engine-api` authrpc block (JWT secret, port, interface) so a CL can drive ETH/Sepolia block import | operator/node-lifecycle |
| `fukuii-first-start` | End-to-end brand-new node bring-up: pick network, generate node key, minimal config, start, verify peers/sync | operator/node-lifecycle |
| `fukuii-key-management` | Generate/derive/encrypt cryptographic material via the five real `fukuii cli` subcommands (node keys, key pairs, address derivation, keystore encryption, genesis allocs) | operator/node-lifecycle |
| `fukuii-log-triage` | Runtime log-level control (`admin_changeLogLevel`) plus a pattern→cause→hand-off table for common log signatures (peer drops, RocksDB, OOM, TLS) | operator/node-lifecycle |
| `fukuii-mining-operations` | Validate/control ETC PoW/Ethash mining: status checks, start/stop, coinbase changes, external-miner wiring | operator/node-lifecycle |
| `fukuii-node-configuration` | Safely edit layered HOCON config (RPC apis/ports, sync mode, Engine API, mining, TLS) with a back-up-then-restart discipline | operator/node-lifecycle |
| `fukuii-node-health-check` | Read-only HEALTHY/DEGRADED/ACTION-REQUIRED verdict: liveness, sync, chain tip, peers, mining (ETC), Engine API/CL (ETH), disk, log scan | operator/node-lifecycle |
| `fukuii-peer-management` | Inspect/add/remove/trust peers, tune max-peers, configure static/bootstrap nodes, diagnose low peer count | operator/node-lifecycle |
| `fukuii-pos-node-health` | ETH/Sepolia-specific health add-on: Engine API liveness, CL attestation participation, post-Cancun blob-gossip peer count, Engine API latency | operator/node-lifecycle |
| `fukuii-security-hardening` | Audit RPC/P2P exposure surface, block/unblock abusive IPs, pin trusted peers, protect key material | operator/node-lifecycle |
| `fukuii-sepolia-sync` | Diagnose ETH Sepolia sync by pre-Merge (peer-driven) vs. post-Merge (CL-driven) phase, incl. post-Cancun blob-payload handling | operator/node-lifecycle |
| `fukuii-sprint-queue` | Report status of / clear closed batches from / archive logged batches out of `.claude/sprints/` via three collector scripts — explicitly does NOT drive sprint implementation itself | **project-management** |
| `fukuii-sync-troubleshooting` | Diagnose stalled/slow sync for both ETC/Mordor (SNAP pivot, concurrency tuning by CPU-count table) and ETH/Sepolia (CL-connectivity check) | operator/node-lifecycle |
| `fukuii-tech-debt-inventory` | Read-only, no-running-node grep-based census of Scala-2-legacy patterns (implicit val/def, implicit class, wildcard imports, sealed-trait+case-object, Classic actors) ranked by file, to seed MITHRIL modernization-sprint planning | **developer-workflow** |
| `fukuii-tls-operations` | Configure/rotate the RPC HTTPS keystore (`network.rpc.http.certificate` block); distinguishes this from the separate faucet TLS config | operator/node-lifecycle |
| `pekko-resource-audit` | Two-phase gated audit of actor/stream code for 5 Pekko-native resource-leak categories (uncancelled timers, missing watch cleanup, stream-materialization leaks, dispatcher starvation, child-actor leaks); explicitly a port of Nethermind's `resource-leak-audit` | **developer-workflow** |
| `speckit-agent-context-update` | Refresh the managed Spec Kit marker block inside `CLAUDE.md`/`AGENTS.md`, pointing at the most recently modified `specs/*/plan.md` | spec-kit |
| `speckit-analyze` | Read-only cross-artifact consistency/quality analysis across spec.md/plan.md/tasks.md before implementation (duplication, ambiguity, coverage-gap, constitution-conflict detection) | spec-kit |
| `speckit-bug-assess` | Step 1 of a fukuii-specific bug-triage pipeline: produce `.specify/bugs/<slug>/assessment.md` (root-cause hypothesis, confidence level, consensus-critical gate) without touching source | spec-kit |
| `speckit-bug-fix` | Step 2: apply the remediation defined in `assessment.md`, add tests, run `sbt compile-all`/`testEssential`, write `fix.md` | spec-kit |
| `speckit-bug-test` | Step 3: validate the fix against the original symptom, run the regression suite, write a verified/partial/failed/not-reproducible verdict to `test.md` | spec-kit |
| `speckit-checklist` | Generate a "unit tests for requirements-writing" quality checklist for the current feature spec (completeness/clarity/consistency, not implementation verification) | spec-kit |
| `speckit-clarify` | Ask up to 5 targeted clarification questions against a taxonomy of spec ambiguity categories and encode answers back into spec.md | spec-kit |
| `speckit-constitution` | Create/update `.specify/memory/constitution.md` with semantic-versioned principle changes and propagate them to dependent templates | spec-kit |
| `speckit-implement` | Execute all tasks in `tasks.md` phase-by-phase, respecting dependencies/parallel markers, gated on checklist completion | spec-kit |
| `speckit-plan` | Generate design artifacts (research.md, data-model.md, contracts/, quickstart.md) from a feature spec per the Constitution-gated planning workflow | spec-kit |
| `speckit-specify` | Create/update a feature spec directory (`specs/<NNN>-slug/spec.md`) from a natural-language feature description, with a self-validating quality checklist | spec-kit |
| `speckit-tasks` | Generate a dependency-ordered, user-story-organized `tasks.md` from plan.md/spec.md/data-model.md/contracts | spec-kit |
| `speckit-taskstoissues` | Convert `tasks.md` entries into GitHub issues via the GitHub MCP server, gated to matching the actual git remote | spec-kit |

---

## Category totals

| Category | Count | Members |
|---|---|---|
| operator/node-lifecycle | 19 | backup-restore, checkpoint-service, cl-setup, custom-networks, disk-management, engine-api-debug, engine-api-setup, first-start, key-management, log-triage, mining-operations, node-configuration, node-health-check, peer-management, pos-node-health, security-hardening, sepolia-sync, sync-troubleshooting, tls-operations |
| developer-workflow | 3 | fukuii-dependency-audit, fukuii-tech-debt-inventory, pekko-resource-audit |
| project-management | 1 | fukuii-sprint-queue |
| spec-kit | 13 | agent-context-update, analyze, bug-assess, bug-fix, bug-test, checklist, clarify, constitution, implement, plan, specify, tasks, taskstoissues |
| **Total** | **36** | matches `ls .agents/skills/ | wc -l` exactly |

Of the 22 skills carrying the `fukuii-*` prefix specifically: 19 are operator/node-lifecycle,
2 are developer-workflow (`fukuii-dependency-audit`, `fukuii-tech-debt-inventory`), and 1 is
project-management (`fukuii-sprint-queue`). `pekko-resource-audit` (developer-workflow) and
the 13 `speckit-*` skills (spec-kit) sit outside the `fukuii-*` namespace, as before.

---

## Confirmation or correction of the "zero developer-workflow skills" claim

**Corrected — not confirmed as stated.** The prior working assumption ("22 fukuii-* skills,
ALL operator-workflow, ZERO developer-workflow skills, plus 13 speckit-* and 1
pekko-resource-audit") got the **total count right** (22 + 13 + 1 = 36, matching the
directory) but was **wrong about composition**. Reading every file fresh shows three
`fukuii-*` skills are not operator/node-lifecycle at all:

1. **`fukuii-dependency-audit`** — states explicitly in its own frontmatter: *"This skill
   does not require a running Fukuii node and makes no RPC calls... Read-only... Run freely
   at any time."* It greps `Dependencies.scala`/`build.properties`, calls
   `sbt dependencyUpdates`, hits the endoflife.date API, and cross-references vendored
   `scala3`/`pekko` changelogs. This is dependency/LTS-currency auditing — pure
   developer-workflow, zero node interaction.
2. **`fukuii-tech-debt-inventory`** — same "no running node, no RPC calls, read-only" framing.
   It greps `src/main/scala/` for Scala-2-legacy patterns (implicit val/def, implicit class,
   wildcard imports, sealed-trait+case-object, Classic-actor inventory) to seed MITHRIL
   modernization-sprint planning. Also pure developer-workflow — a static-analysis/inventory
   tool, not an operator runbook.
3. **`fukuii-sprint-queue`** — not developer-workflow, but also not operator/node-lifecycle:
   it dispatches to `scripts/agent-tooling/sprint-{status,clear,archive}.sh` to manage
   `.claude/sprints/` and explicitly disclaims driving implementation work. This is
   **project-management**, a fourth category the prior assumption didn't have room for.

`pekko-resource-audit` was already known to sit outside the "zero dev-workflow" claim (it
was called out by name in the assumption as a separate item), and reading it in full
confirms it too is developer-workflow, not operator: a static/behavioral audit of actor and
stream code for resource leaks, explicitly framed as a direct port of Nethermind's
`resource-leak-audit` skill (see `docs/research/best-practices/evm-clients/repo-patterns/nethermind/dev-workflow-skills-pattern.md`,
"resource-leak-audit — status: ALREADY PORTED").

So the accurate count is **3 developer-workflow-flavored skills already exist**
(`fukuii-dependency-audit`, `fukuii-tech-debt-inventory`, `pekko-resource-audit`), not zero —
plus one project-management skill (`fukuii-sprint-queue`) the prior assumption didn't
account for as its own bucket.

**No skills have been added, removed, or renamed relative to CLAUDE.md's own summary** — the
`Reference index` section of `/media/dev/2tb/dev/fukuii/CLAUDE.md` (as of this same commit)
already lists "22 `fukuii-*` operational skills (node lifecycle, mining, TLS, peers, disk,
logs, security hardening, checkpoint sync, custom networks, key management, Engine API
setup/debug, **dependency audit, tech-debt inventory**)" — note CLAUDE.md's own prose already
names `dependency audit` and `tech-debt inventory` in that list, yet still calls the whole
group of 22 "operational skills." That's the actual discrepancy worth flagging: **it isn't
that a skill was added since the "zero dev-workflow" assumption was made — it's that the
assumption, and CLAUDE.md's own summary sentence, both mislabel 3 of the 22 as operator work
when their own SKILL.md bodies say otherwise ("does not require a running Fukuii node,"
"read-only," "makes no RPC calls").** `fukuii-sprint-queue` isn't named at all in that
CLAUDE.md sentence, even though it's one of the 22 counted.

Separately, the live Skill-tool listing surfaced to this session at the time of writing
*omits* `fukuii-cl-setup`, `fukuii-engine-api-debug`, `fukuii-engine-api-setup`,
`fukuii-pos-node-health`, `fukuii-sepolia-sync`, `fukuii-sprint-queue`,
`fukuii-tech-debt-inventory`, and `fukuii-dependency-audit` (though a couple of the latter
two's `# loop:` variants surfaced elsewhere) — a reminder that the tool-provided skill list
is a snapshot, not a live directory read, and should never substitute for `ls` + reading each
`SKILL.md` when the accuracy of a count matters, as it does here.

---

## Gap framing for the reference-client comparison

With the corrected baseline, the accurate framing for the reference-client comparison is:

**Fukuii is not devoid of developer-workflow skills — it has three, and they are all
audit/inventory tools (dependency currency, legacy-pattern census, resource-leak audit), not
build/test/implementation-workflow tools.** None of the three existing developer-workflow
skills overlaps with, substitutes for, or partially covers any of the specific ports already
proposed this session from the Erigon/Nethermind/go-ethereum patterns:

| Proposed port | Does any existing fukuii skill cover this? |
|---|---|
| `fukuii-implement-eip` (EIP/ECIP implementation workflow) | No — nothing in the current inventory drives spec-to-code EIP implementation. `speckit-specify`/`plan`/`tasks`/`implement` cover generic feature specs, not EIP-specific fork-dispatch/opcode work; the `forge`/`beacon` subagents (not skills) currently carry this responsibility ad hoc. |
| `fukuii-ethtest-triage` (ethereum/tests run→classify→fix loop) | No — `fukuii-tech-debt-inventory` and `fukuii-dependency-audit` are both static-analysis tools with no relationship to running or triaging the `ethereum/tests` IT specs. |
| `fukuii-build` (build orchestration) | No — no skill wraps `sbt compile`/`compile-all`/`scalafmtAll`/`pp` into a single guided workflow; these are documented in `AGENTS.md` as raw commands only. |
| `fukuii-test-unit` / `fukuii-test-all` (test-tier runner) | No — same gap; `AGENTS.md`'s build/test table is documentation, not a skill. `speckit-bug-fix`/`speckit-bug-test` invoke `sbt testEssential` but only as one step inside the narrow bug-triage pipeline, not as a general test-running skill. |
| `fukuii-network-ports` (port/network management) | No — port numbers appear as reference tables inside several operator skills (`fukuii-node-configuration`, `fukuii-peer-management`) but there is no skill for managing/allocating ports across the multi-client dev fleet described in project memory (`fukuii-run-labeling.md`). |
| `fukuii-ephemeral` (spin up a throwaway node for a test) | No — every operator skill above assumes a persistent, already-running node; none scaffolds a disposable instance for local testing. |
| `fukuii-benchmark-diff` | No — `fukuii-tech-debt-inventory`/`fukuii-dependency-audit` don't touch the `Benchmark` sbt config or the two existing speed specs (`MerklePatriciaTreeSpeedSpec`, `RLPSpeedSuite`) at all; this remains exactly the gap already identified against Nethermind's `gas-benchmark` skill in `nethermind/dev-workflow-skills-pattern.md`. |

**Net effect on the reference-client "port now" recommendations: unchanged.** All seven
proposed skills remain genuine gaps — the correction here doesn't remove any item from that
list, it only means the baseline claim should read *"fukuii has zero build/test/benchmark/
EIP-implementation-workflow skills, though it does already have three narrower
audit/inventory developer-adjacent skills (dependency currency, tech-debt census, resource-leak
audit) and one project-management skill (sprint-queue tracking) that should not be
conflated with that gap or double-counted against it."* Citing "zero developer-workflow
skills" without that nuance would overstate how thin fukuii's tooling is in one direction
(implying no dev-facing automation exists at all) while understating, in the other direction,
exactly how narrow the existing three actually are (none of them run code, run tests, or
touch the EVM/consensus/build path — they are read-only greps and version-currency checks).
