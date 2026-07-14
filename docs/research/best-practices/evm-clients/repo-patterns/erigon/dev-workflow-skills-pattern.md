# Erigon — Developer-Workflow Skills Catalog (26 skills)

Source: `.claude/repo-references/clients/erigon/.claude/skills/` (vendored full clone, verified genuine)

Erigon vendors 26 Claude Code skills in its repository, confirmed fresh for this document via
`ls .claude/skills/` and `find .claude/skills -iname SKILL.md | wc -l` (both return 26; an
earlier pass in this research effort had estimated 33 and then corrected to 26 — this document
re-confirms 26 by direct enumeration). Every one of the 26 `SKILL.md` files was read in full for
this catalog; nothing below is transcribed from a summary. Erigon is a Go client with a
fundamentally different storage architecture (MDBX + immutable `.seg` snapshot files via a
staged-sync pipeline) than fukuii (RocksDB, Pekko actor/stream-based sync, JVM/Scala), so several
skills have no direct port path — those are called out explicitly rather than forced into a port
recommendation.

A significant grounding fact discovered while writing verdicts below: **fukuii already has
extensive Hive CI integration** — 13 dedicated `hive-*.yml` GitHub Actions workflows plus a
reusable `_hive-sim.yml` (sbt assembly → Docker image → clone `ethereum/hive` → build → run
simulator → parse pass/fail), and a `hive/fukuii/` adapter directory with its own
`test-local.sh` smoke test. This changes the verdict on Erigon's two Hive-related skills from
"needs design" to "port now" — the CI-side plumbing already exists in fukuii; what's missing is
only the local-invocation wrapper skill, which is exactly what Erigon's `hive-test` and
`erigon-test-hive` already demonstrate how to build.

## Full catalog

### autoresearch

- **What it does**: A domain-agnostic autonomous iteration loop ("Karpathy's autoresearch"
  principles): modify → verify → keep/discard → repeat, forever or for a user-specified `/loop N`
  count. Requires a mechanical verification metric (tests pass, build succeeds, benchmark
  improves) defined up front, one atomic change per iteration, automatic git-revert on
  regression, and a results log. The "Adapting to Different Domains" table lists generic domains
  (backend code, frontend UI, ML training, content, performance, refactoring) — no Erigon-specific
  content appears anywhere in the file.
  (`.claude/skills/autoresearch/SKILL.md`)
- **Erigon-specific or generic pattern**: Fully generic — this skill has nothing to do with
  Erigon, Go, or blockchain clients. It is a portable productivity pattern that happens to be
  vendored in Erigon's skill directory.
- **fukuii equivalent exists?**: Yes, in spirit and with more rigor. fukuii's `.claude/looping/`
  subsystem already implements a DISCOVER → PLAN → EXECUTE → VERIFY loop with a maker/checker
  gate (`agent-protocols/loop-handoff.md`) and a `registry.yaml` source of truth for which agents
  are loop-eligible — this is a more disciplined, project-aware version of the same
  modify/verify/keep-or-discard idea, already wired into fukuii's testing-protocol.md cadence
  rules (compile-all per file, testOnly after logic changes, testEssential at sprint boundaries).
- **Verdict**: Already covered by an existing fukuii skill/doc
- **Reasoning**: Adding a second, less rigorous autonomous-loop mechanism alongside the existing
  maker/checker looping subsystem would create two competing "keep iterating" patterns with no
  clear ownership boundary. The functional idea (bounded, verifiable, auto-reverting iteration)
  is already present and more mature in `.claude/looping/`.

### bal-devnet-ab-test

- **What it does**: A/B tests Erigon's Block Access List (BAL) parallel-execution scheduling
  against non-BAL scheduling on any `bal-devnet-N` ethpandaops network. Brings up Instance A (BAL,
  default) via the `launch-devnet` skill, then clones its config to stand up Instance B with
  `IGNORE_BAL=true` at a `+300` port offset, waits for both to reach chain tip, and compares
  `gas/s`, `repeat%`, `abort`, and `invalid` counters parsed from Erigon's execution log lines. Has
  a dedicated procedure for distinguishing "just a throughput difference" from "a genuine
  state-root correctness divergence between the two instances" (the latter routes to
  `launch-devnet`'s failure-investigation flow instead).
  (`.claude/skills/bal-devnet-ab-test/SKILL.md`)
- **Erigon-specific or generic pattern**: Erigon-specific. BAL (EIP-7928) parallel-execution
  scheduling, `IGNORE_BAL` env var, and Erigon's specific execution-log metric format are all
  unique to this codebase's parallel executor.
- **fukuii equivalent exists?**: No. fukuii has no BAL/parallel-execution-scheduling feature and
  no ethpandaops-style hosted devnet ecosystem for ETC/Mordor or ETH/Sepolia to A/B test against.
- **Verdict**: Not portable
- **Reasoning**: Architecture mismatch on two independent axes — fukuii's execution model has no
  BAL scheduling to toggle, and there is no equivalent "spin up two named instances of a public
  devnet" infrastructure for fukuii's supported networks. No aspirational path exists until both
  preconditions change.

### benchmarkoor

- **What it does**: Drives `ethpandaops/benchmarkoor` against a locally-built Erigon binary to
  measure `engine_newPayloadV<N>` throughput (MGas/s) per EEST test case. Covers Docker image
  rebuilds via a fast overlay (copy binary + re-tag, avoiding a full multi-stage rebuild), five
  datadir isolation methods (`direct`/hybrid with an external rsync reset script — what's actually
  used today; `overlayfs` — documented as broken for Erigon's MDBX dataset due to kernel copy-up
  during database open; `zfs` — promising, untested, host is ext4; raw `direct` — dangerous if
  misconfigured; `copy`/`fuse-overlayfs` — unexplored), CPU pinning discipline (`cpuset:` over
  `cpuset_count:` for determinism, matching ethpandaops's 6-physical-core reference runs), and a
  Python comparison script that reads two run directories and produces a per-test speedup table.
  (`.claude/skills/benchmarkoor/SKILL.md`, 355 lines — the longest and most operationally detailed
  skill in the set)
- **Erigon-specific or generic pattern**: Erigon-specific tooling (benchmarkoor is
  ethpandaops/Erigon-ecosystem infrastructure) wrapping a generic idea (per-test throughput
  regression comparison via Engine API `newPayload` calls).
- **fukuii equivalent exists?**: Partial. fukuii's own `Benchmark` sbt module has real timed
  ScalaTest suites — `MerklePatriciaTreeSpeedSpec.scala` and `RLPSpeedSuite.scala` — but these are
  micro-benchmarks of internal data structures (MPT operations, RLP encode/decode), not a
  full-node Engine-API throughput harness comparing execution speed across a chain/dataset before
  and after a change.
- **Verdict**: Needs design
- **Reasoning**: The operational need (before/after throughput comparison to catch performance
  regressions) is real and only half-covered — fukuii's Benchmark module measures isolated
  primitives, not end-to-end block-execution throughput via the JSON-RPC/Engine-API surface. No
  benchmarkoor-equivalent tool exists for ETC/Mordor or ETH/Sepolia, no dataset-snapshot
  infrastructure, and no established datadir-isolation method for fukuii's RocksDB store (the
  `overlayfs`-breaks-on-heavy-database-open lesson would need to be independently re-verified for
  RocksDB rather than assumed to transfer). Building a fukuii equivalent is a multi-step
  infrastructure project, not a skill port.

### erigon-build

- **What it does**: Builds the Erigon binary via `make erigon` from the repo root, placing the
  output at `./build/bin/erigon`. Documents what the build does (version stamping from git,
  CGO flags, prerequisites: Go per `go.mod`, a C compiler, Make) and how to verify success
  (`./build/bin/erigon --version`).
  (`.claude/skills/erigon-build/SKILL.md`)
- **Erigon-specific or generic pattern**: Generic pattern (wrap the project's canonical build
  command in a single-purpose skill) with Erigon-specific commands.
- **fukuii equivalent exists?**: No dedicated skill; the command exists (`sbt compile-all`,
  `sbt assembly` for a runnable JAR) and is documented in AGENTS.md's Build & test commands
  table, but there is no skill wrapping it the way `erigon-build` does.
- **Verdict**: Port now
- **Reasoning**: This is the exact skill proposed this session as `fukuii-build`. It is the
  simplest possible skill shape (one command, one precondition, one verification step) and the
  mechanical translation is direct: `make erigon` → `sbt compile-all` (or `sbt assembly` when a
  runnable artifact is needed), `./build/bin/erigon --version` → run the assembled JAR with a
  version flag or check `sbt version` / git describe output the way fukuii's own `auto-version.yml`
  workflow does.

### erigon-cherry-pick

- **What it does**: A 9-line skill: "Pick PRs from git branch A to B. Create separated PRs on
  GitHub. Don't put your name into these PRs and don't sign commits. Don't need much description —
  just refer to the original PR." Gives one example (`release/3.4` → `main`).
  (`.claude/skills/erigon-cherry-pick/SKILL.md`)
- **Erigon-specific or generic pattern**: Generic git/GitHub workflow pattern, described at
  minimum specificity — it assumes an established multi-branch release structure (`release/3.4`,
  `main`) that the skill itself doesn't set up.
- **fukuii equivalent exists?**: No skill, and no precondition either — fukuii is currently a
  single-maintainer repo with no `release/X.Y` branch structure (per `github-workflows.md`'s
  explicit note about why `.github/CODEOWNERS` isn't present yet). `git-conventions.md` covers
  force-push confirmation and merge-conflict escalation but not a scripted cross-branch
  cherry-pick-and-PR workflow.
- **Verdict**: Needs design
- **Reasoning**: The mechanical git steps are trivial and would port cleanly, but there is
  nothing to port *to* yet — fukuii has no long-lived release branches to cherry-pick between.
  This becomes a same-day port the moment fukuii adopts a release-branch model; until then it's
  premature. (Contrast with `github-pr-cleanup` below, which needs no such precondition and is
  usable today.)

### erigon-ci

- **What it does**: The orchestration layer over Erigon's local test tiers and remote CI. Documents
  a local test-groups table (lint, unit `make test-short` ~5min, all `GOGC=80 make test-all`
  ~30min, race `make test-all-race` ~60min, EEST spec shards, `cl/spectest` consensus spec,
  `make test-hive`, RPC integration, Kurtosis assertoor — remote-only) with three escalating local
  gates (quick: lint+test-short; full: lint+build+test-all; race: lint+test-all-race), then a
  remote-dispatch section that loops `gh workflow run "<name>" --ref $BRANCH` across ten named
  GitHub Actions workflows (useful for branches like `bal-devnet-2` that don't auto-trigger CI),
  a workflow-dispatch-support table mapping each CI workflow name to its file and its dedicated
  drill-down skill, and monitoring via `gh run list`/`gh run watch`.
  (`.claude/skills/erigon-ci/SKILL.md`)
- **Erigon-specific or generic pattern**: Generic orchestration pattern (local-tier table +
  named-workflow dispatch table + monitor commands) populated with Erigon-specific workflow names.
- **fukuii equivalent exists?**: Partial. fukuii's AGENTS.md already has a more detailed local
  test-tier table (`compile-all`, `testEssential`/`testStandard`/`testComprehensive` via
  `sbt-run.sh`, tagged subsets like `testConsensus`/`testOlympia`) than Erigon's, but there is no
  skill that also does `gh workflow run` dispatch against fukuii's real CI. fukuii's
  `.github/workflows/` directory has real, named, `workflow_dispatch`-enabled workflows to target:
  `ci.yml` ("CI"), 13 `hive-*.yml` workflows (each independently dispatchable, e.g. "Hive ·
  engine", "Hive · rpc-compat"), `ethereum-tests-nightly.yml`, `fast-distro.yml`, `release.yml`.
- **Verdict**: Port now
- **Reasoning**: fukuii has exactly the ingredient this skill needs — a real, named, multi-workflow
  CI surface with `workflow_dispatch` already enabled on most of it — but no skill ties local test
  tiers to `gh workflow run` dispatch and `gh run list`/`gh run watch` monitoring. This should be
  built as the umbrella skill over the proposed `fukuii-build`/`fukuii-test-unit`/`fukuii-test-all`
  triad, with a dispatch table populated from fukuii's actual `.github/workflows/*.yml` names
  (verified in this session: `CI`, `Hive · engine`, `Hive · consensus`, `Hive · rpc-compat`,
  `Hive · consume-engine`, `Hive · consume-rlp`, `Hive · devp2p`, `Hive · graphql`, `Hive ·
  osaka`, `Hive · prague`, `Hive · pyspec`, `Hive · smoke-genesis`, `Hive · smoke-network`,
  `Hive · sync`, plus `ethereum-tests-nightly`, `fast-distro`, `release`) rather than Erigon's.

### erigon-datadir

- **What it does**: A `user-invocable: false` reference skill (called by other skills, not
  directly by users) for duplicating an Erigon datadir. Runs precondition checks in strict order
  (source exists; source has `nodekey` file + `snapshots/` + `chaindata/` dirs; destination
  doesn't already exist; destination's parent is writable), reports source size via `du -sh`
  before proceeding, detects APFS via the `mount` output for the destination's filesystem, and
  branches: on APFS, uses `cp -ac` for a near-instant copy-on-write clone (then deletes
  `chaindata/` from the clone since a fresh execution state is usually wanted); on any other
  filesystem, checks free disk space against source size before a full `cp -a`.
  (`.claude/skills/erigon-datadir/SKILL.md`)
- **Erigon-specific or generic pattern**: Generic pattern (precondition-gated, filesystem-aware
  directory duplication with a CoW fast path) applied to Erigon-specific directory names
  (`nodekey`, `snapshots/`, `chaindata/`).
- **fukuii equivalent exists?**: Partial. `fukuii-backup-restore` covers backup/restore of the
  node key, keystore, config, known peers, and the RocksDB blockchain/state database, and
  `fukuii-disk-management` covers space triage — but neither implements "clone the datadir into a
  disposable copy for safe experimentation," which is what this skill (and its caller,
  `erigon-ephemeral`) actually needs.
- **Verdict**: Needs design
- **Reasoning**: The precondition-check-then-CoW-or-full-copy pattern is mechanically portable —
  substitute fukuii's actual datadir layout (node key file, RocksDB directory, config file) for
  Erigon's `nodekey`/`snapshots/`/`chaindata/` — but no discrete "duplicate this datadir" building
  block exists yet in fukuii's skill set. It's a small, well-scoped piece of work that becomes a
  direct prerequisite the moment `fukuii-ephemeral` (see next entry) is built with a clone mode.

### erigondb-sync-integration-test-plan

- **What it does**: A `user-invocable: false` integration test plan verifying that
  `erigondb.toml` settings (specifically `step_size`) resolve correctly across three runtime
  scenarios: (A) a legacy datadir that has `preverified.toml` but no `erigondb.toml` — expects the
  file to be created immediately with legacy settings (`step_size = 1562500`); (B) a fresh datadir
  with the downloader enabled — expects code defaults first, then the network's published
  `erigondb.toml` values to arrive during the header-chain download phase; (C) a fresh datadir
  with `--no-downloader` — expects code defaults written immediately, since there's no downloader
  to deliver network-published settings. Each scenario runs at a different port offset (+100/+200/
  +300) via ephemeral clones, checks specific log messages, and inspects the resulting TOML file.
  (`.claude/skills/erigondb-sync-integration-test-plan/SKILL.md`)
- **Erigon-specific or generic pattern**: Deeply Erigon-specific — `erigondb.toml`, `step_size`,
  `preverified.toml`, the downloader-delivers-network-config mechanism, and the specific log
  message strings checked are all unique to Erigon's snapshot/segment sync design.
- **fukuii equivalent exists?**: No, and there is no analogous concept. fukuii syncs via RocksDB
  directly without a segment/step-size architecture or a downloader-delivered config file.
- **Verdict**: Not portable
- **Reasoning**: This tests a mechanism (network-published sync-parameter resolution via a
  downloaded TOML file, layered over legacy-vs-fresh datadir detection) that has no counterpart
  in fukuii's architecture at all — not "not yet built," but "the underlying feature this tests
  doesn't and likely won't exist" given fukuii's non-staged, non-segment-file sync design.

### erigon-ephemeral

- **What it does**: Spins up a temporary, throwaway Erigon instance in a `mktemp`-created datadir
  (never hand-constructed paths). Two modes: empty datadir (default, via `mktemp -d`) or clone an
  existing datadir (via `mktemp -u` for a not-yet-created destination path, then delegating to
  `erigon-datadir`'s duplication procedure). Enforces port-conflict avoidance: never use default
  ports, start at +100, check every candidate port with `lsof` (both TCP and UDP, chosen for
  cross-platform behavior over Linux-only `ss`), and step to +200/+300 on conflict — backed by an
  explicit port table covering 11 always-present flags plus 2 conditional ones (`--pprof.port`,
  `--metrics.port`). Starts the process in the background, always prints the full CLI invocation
  before launching, and reports the PID. Has an explicit cleanup step (ask before deleting) and a
  separate, independently invocable "leftover detection" step that scans the system temp
  directory for abandoned `erigon-ephemeral.*` datadirs from prior sessions and checks whether a
  process is still attached to each.
  (`.claude/skills/erigon-ephemeral/SKILL.md`)
- **Erigon-specific or generic pattern**: Generic pattern (safe, conflict-avoiding, cleanup-aware
  ephemeral instance management) with Erigon's specific port list and build step.
- **fukuii equivalent exists?**: No. `fukuii-first-start` brings up a single, intentional node;
  nothing in fukuii's 19 operator skills manages disposable/throwaway/multi-instance node
  lifecycles with automatic port offsetting.
- **Verdict**: Port now
- **Reasoning**: This is the exact skill proposed this session as `fukuii-ephemeral`. Every piece
  ports cleanly: `mktemp -d` for the datadir, `lsof`-based TCP/UDP conflict detection generalizes
  directly, the +100/+200/+300 escalation strategy is filesystem- and protocol-agnostic, and the
  "always print the full command before launching" / "leftover detection as an independently
  runnable step" disciplines are pure process hygiene with no Erigon dependency. The one
  prerequisite substitution needed is fukuii's own port table (see `erigon-network-ports` below)
  in place of Erigon's, and — for clone mode — the `erigon-datadir` equivalent flagged above as
  "needs design."

### erigon-exec-from-0

- **What it does**: Re-executes all blocks from genesis to reproduce state-root mismatches,
  validate fixes, or test new execution logic end-to-end. Three steps: (1) remove state
  snapshots only (`snapshots rm-all-state-snapshots`), explicitly keeping block snapshots so
  there's no need to re-download block data; (2) reset the execution stage
  (`integration stage_exec --reset`), clearing execution-stage progress in the DB so the next run
  starts at block 0; (3) run execution (`integration stage_exec --batchSize=8m`). Documents
  optional flags: `--pprof`/`--pprof.port` for profiling, `ERIGON_ASSERT=true` for slower
  correctness assertions when hunting state bugs, `--block=<N>` to stop at a specific block, and a
  pre-check for snapshot corruption via `erigon seg integrity --sample=0.001`.
  (`.claude/skills/erigon-exec-from-0/SKILL.md`)
- **Erigon-specific or generic pattern**: Erigon-specific mechanism (a discrete, individually
  resettable "execution stage" within a staged-sync pipeline, decoupled from block-data storage)
  wrapping a generic debugging goal (deterministic full re-execution to reproduce a bug).
- **fukuii equivalent exists?**: No direct equivalent. `fukuii-sync-troubleshooting` covers
  general sync-stall triage, but fukuii's Pekko actor/stream-based sync has no discrete,
  independently resettable "execution stage" separate from block/header storage the way Erigon's
  staged-sync pipeline does.
- **Verdict**: Needs design
- **Reasoning**: The debugging goal (deterministically re-run execution from genesis to
  reproduce a state-root mismatch or validate a fix) is valuable and would benefit fukuii too, but
  the concrete mechanism doesn't transfer — it depends on Erigon's stage-reset commands and its
  block-snapshot/execution-state separation. Building a fukuii equivalent first requires
  establishing whether fukuii's storage schema can cleanly separate "wipe execution/state" from
  "keep headers and bodies," which is an open architecture question, not a skill-writing task.

### erigon-implement-eip

- **What it does**: A rich, argument-driven ($0 = fork name, $1 = EIP number, $2 = optional
  devnet name) 9-step workflow for implementing a new EIP: (1) fetch and understand the EIP spec
  from `eips.ethereum.org`, mapping it to affected packages/files; (2) fetch dependent/referenced
  EIPs the same way; (3) look up the fork's meta-EIP at `eips.ethereum.org/meta` to see which EIPs
  are Considered/Scheduled/Proposed/Declined For Inclusion (CFI/SFI/PFI/DFI), with a note on
  EL/CL portmanteau fork naming (e.g. Glamsterdam = Gloas CL + Amsterdam EL) and Erigon's split
  EL/CL implementation; (4) optionally fetch the devnet spec from ethresear.ch notes for the given
  devnet name, useful for pinpointing exactly what changed since the prior devnet iteration; (5)
  cross-reference the Ethereum Yellow Paper (valid through Shanghai; later changes must come from
  subsequent forks' EIPs read in order); (6) check for and reconcile prior partial implementation
  against an older devnet spec; (7) implement, then lint repeatedly (linter is non-deterministic);
  (8) run local tests via `/erigon-test-all`, with detailed guidance on which EEST spec-test
  shards matter most for a fork under development (`-devnet` shards are primary signal, `-stable`
  shards are regression checks), a strong warning about stale `evm`/`evm.race` binaries silently
  inflating or hiding failures, and an explicit ethical rule: "question the tests — do not
  silently fix them" — if a protocol test itself appears wrong, write up findings and ask for
  review rather than quietly patching the test; (9) wrap up with a structured summary (packages
  touched, design decisions, dependent EIPs, open questions, test coverage status) saved to
  `agentspecs/eip-$1-implementation/summary.md` (a gitignored local-notes directory).
  (`.claude/skills/erigon-implement-eip/SKILL.md`, 191 lines)
- **Erigon-specific or generic pattern**: The overall shape (spec-fetch → dependency-mapping →
  fork-context → prior-work-check → implement → lint → test → structured wrap-up) is a generic,
  highly transferable EIP/ECIP-implementation methodology; the concrete test-shard commands
  (`make eest-spec-*`) and Erigon's package layout are implementation-specific.
- **fukuii equivalent exists?**: No discrete skill, though the surrounding governance exists:
  CLAUDE.md's Consensus-Critical Change Protocol (identify PoW/PoS family → `forge`/`beacon`
  impact analysis → `wraith` for compile fixes → `eye` for validation) covers the oversight and
  subagent-routing side, and `/media/dev/2tb/dev/ECIPs/_specs/` holds local ECIP specs for the
  ETC-family analog to EIPs.
- **Verdict**: Port now
- **Reasoning**: Explicitly named in this session's task as the proposed `fukuii-implement-eip`.
  The methodology transplants almost verbatim: swap the EIP-only spec-fetch step for a
  fork-aware branch (EIP for the PoS/`beacon` family via timestamp forks, ECIP for the PoW/`forge`
  family via block-number forks, checking `/media/dev/2tb/dev/ECIPs/_specs/` first for ECIPs);
  replace the EEST/`evm` test-shard mechanics with fukuii's `testOlympia`/`testEthSmoke`/
  `testConsensus` sbt tags and a mandatory `forge`/`beacon` consultation step per the existing
  Consensus-Critical Change Protocol; replace the `agentspecs/` wrap-up location with
  `.claude/sprints/log/` or a new `specs/<NNN>/` Spec Kit artifact, matching fukuii's existing
  spec-driven-development convention rather than inventing a parallel one. The "question the
  tests, don't silently fix them" ethic and the CFI/SFI/PFI/DFI fork-scoping technique are both
  worth carrying over unchanged.

### erigon-mdbx-compact

- **What it does**: A `user-invocable: false` skill compacting an MDBX database
  (`<datadir>/chaindata/mdbx.dat`) by copying it without garbage-collected pages via the
  `mdbx_copy` tool (built with `make db-tools`). Requires Erigon and rpcdaemon stopped, ~2x free
  disk space (compacted copy written alongside the original), and reports before/after size via
  `du -sh`. Optionally diagnoses first via `mdbx_stat -ef` to check the "Reclaimable" percentage
  before committing to a run that "can take hours to days for large databases."
  (`.claude/skills/erigon-mdbx-compact/SKILL.md`)
- **Erigon-specific or generic pattern**: The operational need (shrink an on-disk database file,
  reclaim garbage-collected/free space) is generic; the mechanism (`mdbx_copy`, MDBX's specific
  page-reclamation model) is entirely MDBX-specific.
- **fukuii equivalent exists?**: No. fukuii uses RocksDB, not MDBX — a completely different
  storage engine with its own compaction model (level-based compaction, `CompactRange`, or the
  `ldb`/`sst_dump` command-line tools), documented at a conceptual level in the
  `storage-rocksdb.md` protocol but with no compaction-skill wrapper. `fukuii-disk-management`
  handles disk-usage triage more broadly but doesn't drive RocksDB compaction specifically.
- **Verdict**: Needs design
- **Reasoning**: The operational need genuinely matches (users of both clients eventually want to
  reclaim space from a growing database file), but literally nothing in the mechanism ports —
  `mdbx_copy` doesn't exist for RocksDB, and RocksDB's own compaction triggers work completely
  differently (automatic background compaction vs. an explicit copy-and-rebuild tool). A fukuii
  equivalent would need to be designed from RocksDB's own APIs/CLI tools and `storage-rocksdb.md`,
  not translated from this skill.

### erigon-network-ports

- **What it does**: A `user-invocable: false` pure-reference skill: a table of every Erigon CLI
  flag that binds a port (`--private.api.addr`, `--http.port`, `--authrpc.port`, `--ws.port`,
  `--torrent.port`, `--port`, `--caplin.discovery.port`/`.tcpport`, `--sentinel.port`,
  `--beacon.api.port`, `--mcp.port`, plus conditional `--pprof.port`/`--metrics.port`), each with
  its default, protocol (TCP/UDP), and description — plus a worked two-instance example showing
  every flag offset by +100.
  (`.claude/skills/erigon-network-ports/SKILL.md`)
- **Erigon-specific or generic pattern**: Generic pattern (a canonical port-reference table other
  skills point to instead of duplicating port knowledge) populated with Erigon-specific flags.
- **fukuii equivalent exists?**: No discrete skill. `fukuii-node-configuration` touches ports as
  one part of broader HOCON config editing, but there's no single canonical port-reference table
  that other skills (like the proposed `fukuii-ephemeral`) can point to.
- **Verdict**: Port now
- **Reasoning**: Explicitly named in this session's task as the proposed `fukuii-network-ports`.
  Purely mechanical: enumerate fukuii's actual port-bearing HOCON config keys (JSON-RPC HTTP/WS
  ports, TLS RPC port, P2P listen port, discovery port, and any metrics endpoint) in the same
  table shape, then reuse it as the shared reference for `fukuii-ephemeral`'s conflict-detection
  step exactly as Erigon's `erigon-ephemeral` and `launch-devnet` both reference this file rather
  than re-deriving the port list.

### erigon-seg-integrity

- **What it does**: Runs `erigon seg integrity --datadir=<path>` to validate snapshot/segment file
  consistency. Requires Erigon stopped (exclusive file lock) and the binary built. Notably
  instructs the agent to *dynamically discover* the current list of available checks via
  `--check`'s `--help` output rather than assuming a fixed list, since "the list of available
  checks is dynamic and changes over time." Supports `--check`/`--skip-check` to scope which
  checks run and `--failFast` (default true) to stop at the first problem or `false` to collect
  all warnings.
  (`.claude/skills/erigon-seg-integrity/SKILL.md`)
- **Erigon-specific or generic pattern**: Erigon-specific mechanism (validating `.seg` snapshot
  files) wrapping a generic operational need (verify on-disk data-store integrity before trusting
  it, especially after an unclean shutdown).
- **fukuii equivalent exists?**: No skill and no direct architectural counterpart — fukuii has no
  segment/snapshot files, only a single RocksDB store. RocksDB's own toolchain has a
  conceptually similar (but mechanically unrelated) capability via `sst_dump --command=verify_checksum`
  or `ldb` / `PRAGMA`-style integrity checks, referenced only implicitly in
  `storage-rocksdb.md`.
- **Verdict**: Needs design
- **Reasoning**: There is a genuine, real integrity-checking need for fukuii's RocksDB store
  (corruption after a crash, verifying a copied/restored datadir), and RocksDB does expose tools
  for it — but nothing here is a literal port; the skill would need to be built fresh around
  RocksDB's own checksum/verification primitives, reusing only the *shape* of this skill
  (dynamically discover available checks, support fail-fast vs. collect-all-warnings) rather than
  any of its commands.

### erigon-seg-rebase

- **What it does**: Runs `erigon seg step-rebase --datadir=<path> --new-step-size=<size>` to
  change the granularity ("step size") of an existing datadir's snapshot segments — common values
  1562500 (full), 781250 (half), 390625 (quarter). The command is interactive (prints a full
  rename/delete plan, waits for `y/N` confirmation). Supports `--keep-blocks` to preserve
  chaindata and only reset execution state (reusing the same reset logic as
  `integration stage_exec --reset`) instead of deleting the whole chaindata DB and requiring a
  full block re-download.
  (`.claude/skills/erigon-seg-rebase/SKILL.md`)
- **Erigon-specific or generic pattern**: Entirely Erigon-specific — "step size" is a concept
  intrinsic to Erigon's segment-file layout and has no analog in a single-database storage model.
- **fukuii equivalent exists?**: No, and no aspirational path — fukuii's RocksDB store has no
  concept of "step size" or segment granularity to rebase.
- **Verdict**: Not portable
- **Reasoning**: Architecture mismatch with no bridge — this entire skill exists to manage a
  storage-layout parameter (segment step size) that is unique to Erigon's immutable-snapshot
  design. fukuii's single-database model has nothing analogous to rebase.

### erigon-seg-retire

- **What it does**: Runs `erigon seg retire --datadir=<path>`, which performs a sequence of
  operations preparing snapshot files for publication: build missing indices, freeze blocks from
  the DB into snapshot files, remove overlapping snapshot files, prune ancient blocks that have
  been successfully snapshotted, build and prune state-history snapshot files, then merge smaller
  snapshot files into larger ones. Documented as step 2 of a six-step "Publishable v2" snapshot
  release workflow (shutdown → `seg retire` → optional `seg clearIndexing` → `seg index` →
  create torrent files → integrity check → `publishable` command).
  (`.claude/skills/erigon-seg-retire/SKILL.md`)
- **Erigon-specific or generic pattern**: Entirely Erigon-specific — this is the mechanics of
  Erigon's freeze-to-immutable-snapshot-and-publish-via-torrent pipeline, which has no
  counterpart in a single-RocksDB-store client.
- **fukuii equivalent exists?**: No. fukuii has no snapshot-freezing or torrent-based
  snapshot-distribution pipeline at all; `fukuii-disk-management` prunes data within RocksDB
  directly rather than migrating it to separate immutable files for distribution.
- **Verdict**: Not portable
- **Reasoning**: This skill exists entirely to support Erigon's snapshot-publishing pipeline
  (freeze → merge → torrent → publish), a piece of infrastructure fukuii doesn't have and that
  isn't implied by anything in fukuii's current architecture or roadmap.

### erigon-test-all

- **What it does**: Runs `ERIGON_EXECUTION_TESTS_TMPDIR=$path GOGC=80 make test-all` (~30 minutes,
  60-minute timeout, coverage output) — the local equivalent of the "All tests" CI workflow. Notes
  that EEST spec tests (state/blockchain/engine-x) and the `cl/spectest` consensus spec test moved
  out of `go test ./...` into dedicated `make eest-spec-*` targets and a separate CI workflow
  respectively, so `test-all` no longer downloads those fixture tarballs. Requires a RAM disk
  (created via `tools/create-ramdisk`, cross-platform: Linux tmpfs via sudo, macOS hdiutil,
  Windows ImDisk) because `execution/tests` does heavy temp-file I/O — CI mirrors this via
  `ramdisk: true` in the workflow config. Gives drill-down commands for isolating a specific
  failing package/test once the full run fails, and a CI-equivalence table mapping each local
  command to its GitHub Actions workflow file.
  (`.claude/skills/erigon-test-all/SKILL.md`)
- **Erigon-specific or generic pattern**: Generic pattern (full-suite runner with drill-down
  guidance and CI-equivalence mapping), Erigon-specific commands and RAM-disk-for-temp-I/O detail.
- **fukuii equivalent exists?**: No dedicated skill; the underlying tiers exist —
  `testStandard`/`testComprehensive` via `scripts/agent-tooling/sbt-run.sh`, run in the
  background per `background-script-execution.md`.
- **Verdict**: Port now
- **Reasoning**: Explicitly named in this session's task as part of the proposed
  `fukuii-test-unit` + `fukuii-test-all` pair. The RAM-disk-for-heavy-temp-I/O detail may or may
  not be needed for fukuii's test suite (sbt/JVM test I/O patterns differ from Go's) — verify
  before assuming it transfers — but the core shape (one full-tier command, drill-down
  instructions for isolating a failure, and a CI-equivalence table pointing at fukuii's real
  `ci.yml`) ports directly.

### erigon-test-hive

- **What it does**: Documents two ways to run Erigon's Hive simulator tests: `make test-hive`
  (uses `act` to simulate the GitHub Actions workflow locally, needs `GITHUB_TOKEN`) or
  `make hive-local` (builds hive and runs suites directly, no `act` needed), plus
  `make eest-devnet` for BAL/Amsterdam-specific EIP-7928 fixtures. Carries a strong Docker-resource
  warning (Hive spawns one container per client instance plus one per simulator plus intermediate
  build images per test run — always run `docker system prune -af --volumes` on exit, matching
  what the CI workflow itself does even on failure) and a recommended trap-based cleanup wrapper
  for direct hive invocations. Documents drill-down via `hiveview --serve` for browsing results
  and re-running a single failing simulator with `--sim.limit`. Explicitly defers to the richer
  `/hive-test` skill for interactive/guided runs, positioning itself as the CI-Makefile-target
  reference document.
  (`.claude/skills/erigon-test-hive/SKILL.md`)
- **Erigon-specific or generic pattern**: Generic pattern (local Hive invocation + aggressive
  Docker cleanup discipline + drill-down via hiveview) with Erigon-specific Makefile targets and
  suite names.
- **fukuii equivalent exists?**: No local-invocation skill, but very strong CI-side scaffolding
  already exists: fukuii has 13 dedicated `hive-*.yml` GitHub Actions workflows (`hive-engine.yml`,
  `hive-consensus.yml`, `hive-consume-engine.yml`, `hive-consume-rlp.yml`, `hive-devp2p.yml`,
  `hive-graphql.yml`, `hive-osaka.yml`, `hive-prague.yml`, `hive-pyspec.yml`,
  `hive-rpc-compat.yml`, `hive-smoke-genesis.yml`, `hive-smoke-network.yml`, `hive-sync.yml`) plus
  a reusable `_hive-sim.yml` workflow that already does: `sbt assembly` → build a thin JRE overlay
  Docker image tagged `chipprbots/fukuii:latest` → clone `ethereum/hive` with retry logic → sync
  fukuii's own `hive/fukuii/` adapter (Dockerfile, `fukuii.sh`, `mapper.jq`, `enode.sh`) into
  `external/hive/clients/fukuii/` → `go build -o hive .` → run the requested simulator → parse
  pass/fail. There's also `hive/test-local.sh`, but it only smoke-tests the adapter container
  directly (custom genesis, TCP readiness check) — it does not build or run the actual `hive`
  framework or its simulators.
- **Verdict**: Port now
- **Reasoning**: This is a much stronger finding than a typical "port now" — fukuii's CI already
  contains a working, exercised, step-by-step recipe (in `_hive-sim.yml`) for everything this
  skill and `hive-test` (below) do locally: assemble the artifact, build the Docker image, clone
  and build `ethereum/hive`, run a named simulator, parse results, clean up. The gap is purely the
  *local-invocation wrapper* — a `fukuii-test-hive` skill should mirror `_hive-sim.yml`'s steps
  almost line-for-line (reusing the exact Dockerfile-generation snippet and adapter-sync commands
  already proven in CI) rather than inventing a new build path. Erigon's own two-skill split
  (`erigon-test-hive` as the terse Makefile-target reference, `hive-test` as the rich guided
  runner) is worth studying but fukuii likely only needs one combined skill, since `_hive-sim.yml`
  is already the single source of truth for the recipe (unlike Erigon, which has several
  Makefile targets doing overlapping things).

### erigon-test-race

- **What it does**: Runs the full suite with Go's race detector (`ERIGON_EXECUTION_TESTS_TMPDIR=
  $path make test-all-race`, 30–60 minutes, same RAM-disk prerequisite as `test-all`). Explains
  that EEST blocktest race coverage now lives in dedicated `make eest-spec-blocktests-*-race-*`
  shards (auto-building a race-instrumented `evm.race` binary) rather than the main suite.
  Documents how to read Go's race-detector stack-trace output (read vs. previous-write goroutine
  locations) and lists historically race-prone areas: `execution/exec3/` (parallel executor,
  `SharedDomains`, `AsyncTx`), `p2p/` (sentry, peer manager), `txpool/`, `cl/` (caplin consensus
  layer goroutines). Notes there is no dedicated race-detector CI workflow — this is a
  local-only check.
  (`.claude/skills/erigon-test-race/SKILL.md`)
- **Erigon-specific or generic pattern**: The underlying tool (`go test -race`) and its stack-trace
  output format are Go-specific language/runtime features with no JVM/Scala equivalent.
- **fukuii equivalent exists?**: No, and none is straightforwardly buildable. The JVM has no
  built-in flag equivalent to Go's `-race`; JVM-level data-race detection tools (e.g. research
  tools like RV-Predict, or instrumentation-heavy approaches) are not part of fukuii's toolchain
  or documented anywhere in its protocols. The closest existing coverage of concurrency
  correctness is the `flow` subagent (Pekko Streams backpressure/materialization bugs) and the
  `loom` subagent (actor migration correctness), which catch classes of concurrency bugs through
  code review and targeted testing rather than a language-level race detector.
- **Verdict**: Not portable
- **Reasoning**: There is no JVM/Scala equivalent to `go test -race` available in fukuii's
  toolchain, so the skill's central mechanism cannot be translated. The *idea* of maintaining a
  documented list of historically concurrency-fragile modules is generically useful and is already
  informally captured by the existence of dedicated `flow` and `loom` subagents for streaming and
  actor-migration correctness respectively — but that's an existing pattern, not a new port.

### erigon-test-rpc

- **What it does**: Runs Erigon's "QA RPC Integration Tests": starts `rpcdaemon` against a
  pre-synced mainnet datadir (tests at block ~24.3M require sync to at least that height), backs
  up chaindata first (since `integration run_migrations` mutates the DB), then runs
  `run_tests.py` from the `erigontech/rpc-tests` repo, which compares live JSON-RPC responses
  against golden expected-output JSON files per API method, restoring the original chaindata
  afterward. Documents running a single test manually, drilling down into a failing API group
  with `--json-diff`/`--dump-response`, and which tests are intentionally disabled in CI (Engine
  API tests requiring a full node, peer-state-dependent tests, known-broken tests). Has a
  CI-equivalence table for three workflow variants (mainnet, latest-blocks-remote, Gnosis).
  (`.claude/skills/erigon-test-rpc/SKILL.md`)
- **Erigon-specific or generic pattern**: Generic pattern (compare live RPC responses against a
  golden-output corpus) built around Erigon-specific tooling (`erigontech/rpc-tests`,
  `rpcdaemon`, `integration run_migrations`).
- **fukuii equivalent exists?**: Yes, functionally, via a different mechanism: fukuii's
  `hive-rpc-compat.yml` workflow already runs the `ethereum/rpc-compat` Hive simulator against
  fukuii through the same `_hive-sim.yml` reusable workflow described under `erigon-test-hive`
  above. `ethereum/rpc-compat` is itself a golden-response JSON-RPC conformance suite
  (`ethereum/execution-apis`-derived), serving the same purpose as Erigon's bespoke
  `erigontech/rpc-tests` corpus.
- **Verdict**: Already covered by an existing fukuii skill/doc
- **Reasoning**: fukuii doesn't need a bespoke golden-corpus RPC test skill the way Erigon built
  one, because the equivalent conformance coverage already runs in CI via Hive's `rpc-compat`
  simulator (`.github/workflows/hive-rpc-compat.yml`). Once the `fukuii-test-hive` skill proposed
  above exists, running `rpc-compat` locally through it delivers the same local-invocation
  capability this skill provides for Erigon, without needing to adopt or build a separate
  `rpc-tests`-style corpus.

### erigon-test-unit

- **What it does**: Runs `make test-short` (`-short -failfast`), ~5 minutes, the local equivalent
  of the "Unit tests" CI workflow (`ci.yml`). Documents running a single package or test by name,
  and reiterates it must run from the repo root. Positioned as the fast pre-push gate, paired
  with `make lint`.
  (`.claude/skills/erigon-test-unit/SKILL.md`)
- **Erigon-specific or generic pattern**: Generic pattern (fast unit-test gate), Erigon-specific
  command.
- **fukuii equivalent exists?**: The tier exists (`testEssential` via `sbt-run.sh`,
  `sbt "testOnly *Foo*"` for a single class) but no skill wraps it.
- **Verdict**: Port now
- **Reasoning**: Explicitly named in this session's task as the proposed `fukuii-test-unit`.
  Direct 1:1 mapping: `make test-short` → `sbt testEssential` (background, per
  `background-script-execution.md`) or `sbt "testOnly *Foo*"` for a single class — this is the
  simplest and lowest-risk of all the proposed new ports.

### github-pr-cleanup

- **What it does**: Given a GitHub PR URL, first confirms via `gh pr view` that the PR is
  actually `MERGED` (stopping otherwise). Then: finds the local git worktree tracking the PR's
  head branch via `git worktree list --porcelain`, removes it (force-removing with a warning if
  uncommitted changes block it), deletes the local branch, fetches the target branch and verifies
  the squash-merge commit exists locally, then parses the PR body for cherry-pick checklist items
  (`- [ ] Cherry-pick merge commit to \`<branch>\``). If found and unchecked, it creates an
  ephemeral `cherry-pick-PR<n>-to-<target>` branch, cherry-picks the squash commit (stopping on
  conflict rather than auto-resolving), pushes it, opens a new PR titled
  `[cherry-pick] [rX.Y] <original title>`, updates the original PR's checklist item to checked
  with a link to the new PR, and cleans up the ephemeral branch. Ends with a structured summary
  of everything done.
  (`.claude/skills/github-pr-cleanup/SKILL.md`)
- **Erigon-specific or generic pattern**: Fully generic git/GitHub post-merge hygiene workflow —
  nothing in it depends on Go, Erigon's build system, or Erigon's codebase. The only
  Erigon-specific detail is the `[rX.Y]` title-tag convention for its release branches.
- **fukuii equivalent exists?**: No skill, but a strongly compatible foundation: fukuii already
  has `worktree-protocol.md` defining sprint-vs-task worktree patterns and a `wt/<id>` naming
  convention — exactly the kind of naming this skill's `git worktree list --porcelain` matching
  step expects to find. fukuii also already uses `gh` throughout its workflow (PR review,
  `gh workflow run` dispatch per `erigon-ci` above).
- **Verdict**: Port now
- **Reasoning**: This is immediately usable with no preconditions to build first — unlike
  `erigon-cherry-pick`, it needs no release-branch structure to be valuable (the worktree +
  branch cleanup in Steps 1–5 stands alone and is directly useful today for fukuii's existing
  `wt/<id>` worktrees). The cherry-pick-to-release-branch portion (Steps 6–7) can simply be left
  dormant/no-op until fukuii adopts release branches, at which point it activates without further
  changes. High value, low risk, mechanical, and structurally aligned with fukuii's existing
  worktree conventions.

### hive-test

- **What it does**: A fully turnkey, user-invocable Hive test runner working from a clean
  environment (no pre-existing hive install assumed). Supports individual suites
  (`exchange-capabilities`, `withdrawals`, `cancun`, `api`, `auth`, `rpc-compat`, `eest`,
  `eest-devnet`, `eest-rlp`) and groups (`engine` = all five engine suites, `all` = everything),
  documents CI failure-budget thresholds sourced from the actual workflow files (`test-hive.yml`,
  `test-hive-eest.yml`), and runs a four-phase procedure: **Phase 0** auto-discovers the latest
  EEST/BAL fixture release tags from the GitHub API (skippable via explicit version overrides) and
  probes whether the discovered EEST mapper already has needed Erigon-specific exception entries
  before deciding whether a `disable_strict_exception_matching` workaround is needed; **Phase 1**
  sets up an isolated `mktemp`-based work directory, clones `ethereum/hive`, copies or clones the
  Erigon source into it, writes a `Dockerfile.local` (with exact base-image and dependency
  requirements documented), configures P2P protocol negotiation (deliberately don't override it)
  and a specific fix needed for parallel execution during Amsterdam/BAL blocks (BAL validation
  only runs in the parallel-execution path — a serial-executed Amsterdam block would silently
  skip BAL checks and produce false negatives on invalid-BAL test cases), builds the hive binary;
  **Phase 2** runs suites with `--sim.parallelism 12`, launching separate parallel hive sessions
  per distinct simulator to maximize throughput, with exact CLI invocations for each suite/group
  including the EEST and EEST-BAL and EEST-RLP variants; **Phase 3** parses ANSI-stripped status
  lines for suite/test/failed counts plus per-test JSON result files; **Phase 4** always runs
  cleanup (`./hive --cleanup`, workdir removal, `docker image prune -f`) regardless of outcome.
  (`.claude/skills/hive-test/SKILL.md`, 323 lines)
- **Erigon-specific or generic pattern**: The overall four-phase shape (auto-discover fixture
  versions → isolated setup → parallel suite execution → parse-and-cleanup) is a generic,
  highly transferable Hive-runner pattern; the specific Dockerfile base image, P2P-negotiation
  note, and the Amsterdam/BAL parallel-execution gotcha are Erigon-specific.
- **fukuii equivalent exists?**: Same finding as `erigon-test-hive` above — no local-invocation
  skill exists yet, but fukuii's `_hive-sim.yml` reusable CI workflow already implements the
  functional equivalent of Phases 1–3 (build artifact, Docker image, clone+build hive, run
  simulator, parse pass/fail) and `hive/fukuii/` already has a working Dockerfile-based adapter
  used both in CI and by `hive/test-local.sh`'s lighter smoke test.
- **Verdict**: Port now
- **Reasoning**: Given the sophistication already proven out in fukuii's own `_hive-sim.yml` and
  `hive/fukuii/` adapter, this skill is the best model to follow for building `fukuii-test-hive` —
  more so than `erigon-test-hive`, since `hive-test`'s Phase-0 fixture-auto-discovery and
  Phase-4 unconditional-cleanup disciplines are exactly the kind of robustness fukuii's local
  wrapper should have. The concrete suite list should be re-derived from fukuii's own 13
  `hive-*.yml` workflows (which already cover engine, consensus, rpc-compat, consume-engine,
  consume-rlp, devp2p, graphql, osaka, prague, pyspec, smoke-genesis, smoke-network, sync) rather
  than Erigon's list, and the Dockerfile-generation step should reuse `_hive-sim.yml`'s existing
  `sbt assembly` + thin-JRE-overlay recipe rather than reinventing a build. As with
  `erigon-test-hive`, fukuii likely wants one combined `fukuii-test-hive` skill rather than
  Erigon's two-skill split, since `_hive-sim.yml` is already fukuii's single source of truth for
  the underlying recipe.

### kurtosis-test

- **What it does**: The richest skill in the set (377 lines). Runs a local Kurtosis Ethereum
  testnet (`ethereum-package`) against a locally-built `test/erigon:current` Docker image,
  mirroring the `test-kurtosis-assertoor.yml` CI workflow but driving the raw `kurtosis` CLI
  directly instead of the GitHub-Actions-only `ethpandaops/kurtosis-assertoor-github-action`
  wrapper. Maps config files to specific pinned `ethereum-package` branch versions (with a
  documented reason: `main` post-commit `835dd9b` requires kurtosis CLI ≥1.18.1 for a
  `GpuConfig` Starlark feature the pinned older branches predate). Runs three parallel health
  checks on a polling loop: **Check A** — block-height progress, computing a stall window as
  `3× seconds_per_slot` parsed straight out of the config YAML, with a documented false-positive
  guard (don't declare a stall before the first block, since `genesis_delay` can legitimately
  exceed the stall window on some configs); **Check B** — assertoor `test_runs` API polling for
  `result=success`/`failure`/stuck; **Check C** — cross-service log scanning for
  panic/fatal/error patterns, with an explicit cross-client variant pulling the same scan from
  every EL/CL/VC service plus `snooper-engine-*` if enabled (full Engine API request/response
  trace capture). Provides a full triage decision tree: reproduce once before triaging;
  classify the symptom; do a cross-client comparison against at least two other, differently
  -vendored EL clients (not just one, to avoid comparing against another buggy client) with three
  possible outcomes — "erigon wrong" (peers + assertoor agree against erigon), "peer wrong" (check
  the peer's image tag for staleness, don't touch erigon), or "both disagree with spec" (escalate
  to the user, likely a spec ambiguity); cross-reference the actual EIP text as authoritative over
  any client's behavior; rule out config drift and enclave-plumbing issues before blaming code. A
  detailed symptom→likely-owner→next-action triage table covers ten concrete scenarios. An
  auto-iterating fix→rebuild→rerun loop (capped at `max-attempts`, default 5) only auto-applies a
  fix when triage classified the issue as "erigon wrong" with high confidence — otherwise it
  pauses for the user even before the cap is hit. Cleanup (`kurtosis enclave dump` then `rm -f`)
  always runs as the final step of every iteration.
  (`.claude/skills/kurtosis-test/SKILL.md`)
- **Erigon-specific or generic pattern**: The concrete tool wiring (Kurtosis CLI, the
  `ethereum-package`, specific config-file-to-branch pinning, `test/erigon:current` image tag) is
  Erigon/ethpandaops-ecosystem-specific and PoS-oriented (validator sets, slots, fork epochs — an
  `ethereum-package` config models a beacon-chain testnet, which has no PoW/Ethash analog). The
  **triage methodology** (cross-client 2-vs-1 divergence rule; three-way outcome classification;
  spec-as-ground-truth; the specific evidence bar required before escalating to the user) is a
  generic, highly transferable debugging pattern independent of any particular tool.
- **fukuii equivalent exists?**: No. `fukuii-custom-networks` stands up private/consortium/custom
  networks but has no Kurtosis/`ethereum-package` integration, no assertoor-style automated
  test-run health checking, and — architecturally — `ethereum-package` is oriented around PoS
  testnets (validator clients, slots, fork epochs), which maps only to fukuii's PoS family
  (ETH/Sepolia), not its PoW family (ETC/Mordor) at all.
- **Verdict**: Needs design
- **Reasoning**: Two things are worth separating here. The concrete infrastructure (Kurtosis +
  `ethereum-package` + assertoor + a fukuii Docker image + fukuii-specific assertoor test
  playbooks) is a substantial, PoS-family-only infrastructure project — squarely "needs design,"
  not a skill port. But the **triage methodology** — reproduce once, classify the symptom,
  cross-check against 2+ differently-implemented peers before concluding "we're wrong," treat the
  EIP/ECIP text as the actual ground truth rather than majority vote, and the specific
  evidence-bar format required before escalating to a human — is a pattern fukuii should capture
  regardless of whether Kurtosis itself ever gets wired up. It overlaps closely with the same
  methodology embedded in `launch-devnet` below, and is worth extracting into a shared
  investigation-methodology doc (candidate home: a new `agent-protocols/` entry, or an addendum
  to `consensus-change-protocol.md`) independent of any specific test-runner skill.

### launch-devnet

- **What it does**: Given only an ethpandaops devnet landing-page URL (e.g.
  `https://bal-devnet-3.ethpandaops.io`), derives every other parameter at runtime: fetches
  genesis/CL-config/CL-genesis-ssz/node-inventory from the network's config service, extracts
  chain ID, fork timestamps/epochs, CL client images, and EL/CL bootnode lists, and refuses to
  silently substitute defaults or stub data if any expected artifact 404s. Writes a
  machine-parseable `devnet-info.txt` summary (plain `key: value` lines) that sibling skills
  (`bal-devnet-ab-test`) read rather than re-deriving. Generates `start-erigon.sh`/`start-cl.sh`/
  `stop.sh`/`clean.sh` scripts with `mktemp`-safe temp handling, PID-based (not
  `pkill -f`-regex-based) process targeting for safe shutdown, and a strict startup order — erigon
  first (it generates the JWT secret), polled (never `sleep`-guessed) for both `jwt.hex` existing
  and the authrpc port being bound, before the CL container starts (otherwise the CL fails JWT
  auth on its first `newPayload` call). Documents port-conflict handling identical in shape to
  `erigon-ephemeral`'s. The centerpiece is a long "Investigating failures — finding the absolute
  truth" section with the same core methodology as `kurtosis-test`'s triage tree (default
  assumption: nobody is right yet; compare against ≥2 differently-vendored peers; three outcomes
  — erigon-bug / peer-bug / spec-ambiguity — each with a specific next action; drill down to a
  specific divergent block/account/storage-slot using `LOG_HASH_MISMATCH_REASON=true` and
  `debug_traceTransaction`/`debug_traceBlockByNumber`; a documented list of common false-positive
  signals that look scary but usually aren't; and a strict evidence bar — reproducible, a specific
  divergence point identified, and at least one non-erigon EL or concrete spec text supporting a
  side — required before surfacing to the user, with a worked "bad escalation" vs. "good
  escalation" example pair).
  (`.claude/skills/launch-devnet/SKILL.md`)
- **Erigon-specific or generic pattern**: Same split as `kurtosis-test` — the concrete tool wiring
  (ethpandaops's hosted devnet config-service API, its specific URL conventions, Erigon's own CLI
  flags and JWT-generation behavior) is Erigon/ethpandaops-ecosystem-specific; the triage
  methodology and the disciplined "poll, don't sleep-guess; PID-track, don't regex-pkill" process
  hygiene are both generic and transferable.
- **fukuii equivalent exists?**: No. `fukuii-custom-networks` builds private/consortium genesis
  configs and `fukuii-first-start` brings up a single node, but nothing auto-discovers a *public*
  devnet's parameters from a config-service API — and no such hosted-devnet ecosystem exists for
  ETC/Mordor or (as far as documented in fukuii's memory notes) for ETH/Sepolia either.
- **Verdict**: Not portable
- **Reasoning**: The mechanical wiring depends entirely on an ethpandaops-style hosted
  config-service API for public devnets that has no counterpart for fukuii's supported networks —
  there is no "bal-devnet-N.ethpandaops.io"-equivalent service to point a fukuii version of this
  skill at. As with `kurtosis-test`, the triage methodology embedded in this skill (the
  "Investigating failures" section) is independently valuable and should be captured once as a
  shared protocol rather than duplicated inside two "not portable" skill write-ups — see the
  `kurtosis-test` entry above for the same recommendation.

### panda-install

- **What it does**: Installs and configures EthPandaOps' Panda CLI/MCP server — a Docker-based
  local server giving a single authenticated entry point to Xatu ClickHouse (beacon-chain data
  lake), Prometheus, Loki, and read-only Ethereum-node RPC, either via EthPandaOps's hosted proxy
  (GitHub-OAuth-gated, allowlist-based) or a self-hosted proxy pointed at an org's own
  credentials. Handles the full OAuth device-flow handoff carefully (URL/code must be presented as
  plain markdown first so it's clickable/copyable, then a minimal follow-up question — long URLs
  don't render reliably inside `AskUserQuestion` option text), distinguishes a
  not-actually-expired-code allowlist failure (`bad_verification_code` immediately after
  device-flow approval) from a genuinely expired code, and gives a request-allowlist template with
  exact fields (Dex issuer, OAuth client ID, reproducer steps) and known maintainer contacts.
  Documents wiring the resulting local server into Claude Code's MCP config
  (`~/.claude.json` → `mcpServers.ethpandaops-panda`).
  (`.claude/skills/panda-install/SKILL.md`)
- **Erigon-specific or generic pattern**: Entirely third-party-vendor-specific. This is
  installation/configuration tooling for EthPandaOps's own organizational infrastructure (their
  hosted proxy, their GitHub OAuth app, their ClickHouse "xatu" beacon-chain data lake) — it has no
  relationship to Erigon's codebase, build system, or consensus logic at all; it happens to be
  vendored in Erigon's skill directory because Erigon's maintainers are EthPandaOps-adjacent.
- **fukuii equivalent exists?**: No, and there's no reason one would exist — fukuii has no
  relationship to the EthPandaOps organization, its hosted ClickHouse/Prometheus/Loki
  infrastructure, or its GitHub-allowlist OAuth app.
- **Verdict**: Not portable
- **Reasoning**: This is vendor/organization-specific infrastructure tooling, not a generic
  developer-workflow pattern. Nothing about it generalizes to fukuii — there's no aspirational
  path because the entire skill exists to onboard a team member onto a specific third-party
  service that fukuii's maintainers have no relationship with.

## Summary table

| # | Skill | Verdict | One-line reason |
|---|-------|---------|------------------|
| 1 | autoresearch | Already covered | fukuii's `.claude/looping/` maker/checker harness already does modify→verify→keep/discard, more rigorously |
| 2 | bal-devnet-ab-test | Not portable | No BAL/parallel-execution scheduling and no ethpandaops devnet ecosystem in fukuii |
| 3 | benchmarkoor | Needs design | fukuii's `Benchmark` module covers micro-benchmarks only; no full-node throughput harness or datadir-isolation infra exists |
| 4 | erigon-build | Port now | Trivial 1:1 — `make erigon` → `sbt compile-all`/`assembly`; proposed `fukuii-build` |
| 5 | erigon-cherry-pick | Needs design | Mechanically trivial but fukuii has no release-branch structure to cherry-pick between yet |
| 6 | erigon-ci | Port now | fukuii already has a real named multi-workflow CI surface (`ci.yml` + 13 `hive-*.yml` + more) to dispatch against, just no skill doing it |
| 7 | erigon-datadir | Needs design | Precondition/CoW pattern is portable in shape; no "clone datadir for experimentation" building block exists in fukuii yet |
| 8 | erigondb-sync-integration-test-plan | Not portable | Tests a snapshot/step_size/downloader mechanism fukuii's architecture doesn't have |
| 9 | erigon-ephemeral | Port now | Direct match — proposed `fukuii-ephemeral`; port-conflict/mktemp/cleanup pattern is architecture-agnostic |
| 10 | erigon-exec-from-0 | Needs design | Debugging goal is valid for fukuii too, but depends on Erigon's discrete resettable execution stage, which fukuii's sync doesn't have |
| 11 | erigon-implement-eip | Port now | Explicit proposed port `fukuii-implement-eip`; methodology transplants almost verbatim onto ECIP/EIP + forge/beacon protocol |
| 12 | erigon-mdbx-compact | Needs design | Operational need (reclaim DB space) matches; mechanism (`mdbx_copy`) is 100% MDBX-specific, RocksDB needs its own approach |
| 13 | erigon-network-ports | Port now | Explicit proposed port `fukuii-network-ports`; pure reference table, mechanical substitution |
| 14 | erigon-seg-integrity | Needs design | Real integrity-check need for RocksDB exists but nothing here (`erigon seg integrity`) is MDBX/segment-specific and ports directly |
| 15 | erigon-seg-rebase | Not portable | "Step size" is intrinsic to Erigon's segment-file layout; no analog in fukuii's single-RocksDB-store model |
| 16 | erigon-seg-retire | Not portable | Entirely about Erigon's freeze-to-snapshot-and-torrent-publish pipeline, which fukuii doesn't have |
| 17 | erigon-test-all | Port now | Proposed `fukuii-test-all`; direct tier mapping to `testStandard`/`testComprehensive` |
| 18 | erigon-test-hive | Port now | fukuii's `_hive-sim.yml` CI already implements the exact recipe this local-invocation skill needs to wrap |
| 19 | erigon-test-race | Not portable | No JVM/Scala equivalent to Go's `-race` flag in fukuii's toolchain |
| 20 | erigon-test-rpc | Already covered | fukuii's `hive-rpc-compat.yml` (via Hive's `rpc-compat` simulator) already provides equivalent golden-response RPC conformance testing |
| 21 | erigon-test-unit | Port now | Proposed `fukuii-test-unit`; direct 1:1 to `sbt testEssential` |
| 22 | github-pr-cleanup | Port now | No preconditions needed; fukuii's existing `wt/<id>` worktree convention is directly compatible |
| 23 | hive-test | Port now | Best model for `fukuii-test-hive`; fukuii's own adapter + CI recipe already prove out most of the mechanics |
| 24 | kurtosis-test | Needs design | Kurtosis/`ethereum-package`/assertoor infra is a real project (PoS-family only); its triage methodology should be extracted separately |
| 25 | launch-devnet | Not portable | No ethpandaops-style hosted devnet config-service exists for fukuii's networks; triage methodology worth extracting separately |
| 26 | panda-install | Not portable | Vendor/organization-specific (EthPandaOps) infrastructure with no relationship to fukuii |

## Grouped by verdict

### Port now (straightforward, clear fukuii analog)

- **erigon-build** → `fukuii-build`
- **erigon-ephemeral** → `fukuii-ephemeral`
- **erigon-network-ports** → `fukuii-network-ports`
- **erigon-implement-eip** → `fukuii-implement-eip`
- **erigon-test-unit** → `fukuii-test-unit`
- **erigon-test-all** → `fukuii-test-all`
- **erigon-ci** → informs a `fukuii-ci`-style dispatch skill over fukuii's real `.github/workflows/*.yml`
- **erigon-test-hive** and **hive-test** → both should converge into one `fukuii-test-hive` skill, built directly from fukuii's already-proven `_hive-sim.yml` recipe and `hive/fukuii/` adapter
- **github-pr-cleanup** → usable as-is today (Steps 1–5), cherry-pick portion (Steps 6–7) dormant until release branches exist

### Needs design (real gap, more thought/infra needed first)

- **benchmarkoor** — full-node Engine-API throughput harness; fukuii's `Benchmark` module only covers micro-benchmarks
- **erigon-cherry-pick** — waits on fukuii adopting a release-branch model
- **erigon-datadir** — "clone datadir for experimentation" building block, prerequisite for a clone mode on `fukuii-ephemeral`
- **erigon-exec-from-0** — valuable debugging goal, blocked on whether fukuii's storage can cleanly separate "wipe execution state" from "keep headers/bodies"
- **erigon-mdbx-compact** — real need (reclaim DB space), zero code reuse (RocksDB ≠ MDBX)
- **erigon-seg-integrity** — real need (verify datastore integrity), zero code reuse (RocksDB ≠ MDBX/segment files)
- **kurtosis-test** — substantial PoS-family-only infrastructure project; its cross-client triage methodology should be captured as a protocol doc regardless of whether the tool itself gets built

### Not portable (architecture mismatch, no aspirational path)

- **bal-devnet-ab-test** — no BAL/parallel-execution scheduling in fukuii
- **erigondb-sync-integration-test-plan** — tests a mechanism (segment step-size/downloader config resolution) fukuii's architecture doesn't have
- **erigon-seg-rebase** — "step size" concept intrinsic to Erigon's segment files
- **erigon-seg-retire** — Erigon's freeze-to-snapshot-and-torrent-publish pipeline has no fukuii counterpart
- **erigon-test-race** — no JVM/Scala equivalent to Go's race detector
- **launch-devnet** — no ethpandaops-style hosted devnet config-service for fukuii's networks (triage methodology worth extracting separately, see above)
- **panda-install** — EthPandaOps-organization-specific vendor tooling, no relationship to fukuii

### Already covered by an existing fukuii skill/doc

- **autoresearch** — superseded in rigor by `.claude/looping/`'s maker/checker DISCOVER→PLAN→EXECUTE→VERIFY harness
- **erigon-test-rpc** — functionally covered by `hive-rpc-compat.yml`'s `ethereum/rpc-compat` Hive simulator, once `fukuii-test-hive` exists to invoke it locally
