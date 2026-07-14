# Erigon — Build, Test, Benchmark & Architecture Patterns

Source: `.claude/repo-references/clients/erigon/` (vendored full clone, verified genuine
git checkout — `.git/` present, working tree matches `erigontech/erigon`)

Erigon is a Go execution-layer client, so its build tooling (Make, not a JVM build tool)
and its architecture-documentation split (a diagram-driven Docusaurus site plus an older,
implementation-detail-heavy `programmers_guide/`, plus two focused design notes) are the
most transferable patterns for fukuii. Every claim below is traceable to a file in the
vendored clone; where a line number isn't pinned to one statement, the citation names the
section instead of inventing a number.

---

## Makefile / justfile — task-runner conventions

### The Makefile is the single build/test/release entry point (670 lines)

`Makefile` is one flat file (no `include`d sub-makefiles) covering six concerns: binary
builds, code generation, linting, the full test matrix (unit/EF-spec/hive/fuzz/benchmark),
Docker/Kurtosis network simulation, and OS-user provisioning for containerized deployment.
It self-documents via a `## target: description` comment convention consumed by its own
`help` target:

```make
## help:                              print commands help
help	:	Makefile
	@sed -n 's/^##//p' $<
```

(`Makefile:668-670`) — every `##`-prefixed comment line becomes one line of `make help`
output, so the target list below is also literally what a contributor sees by running
`make help`, not a hand-maintained parallel list that can drift.

**Toolchain / version gate.** `go-version` (`Makefile:125-129`) parses `go version` output
and hard-fails if the minor version is below 25 — Erigon requires **Go 1.25+** — before any
build target proceeds; `erigon: go-version erigon.cmd` (`Makefile:186`) makes every build of
the main binary depend on this gate.

**Provenance flags, not `git describe`.** `GIT_TAG` is computed by an inline comment-explained
fix (`Makefile:21-28`): rather than `git describe` (which on an untagged branch anchors to an
unrelated older tag and produces a misleading value), it does
`git tag --points-at HEAD --list 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' | head -n 1`
— only an *exact* tag at `HEAD` counts, and the regex allowlist exists specifically because
git ref names permit shell metacharacters and this value flows into an `-ldflags` shell
string (`Makefile:86-87`). The advertised version users see comes from `db/version` (see
Release process below), not from this build-provenance tag — `GIT_TAG` is metadata only.

**Command build via a static pattern rule.** Rather than one target per binary, `%.cmd`
(`Makefile:174-180`) is a generic pattern: `$(GOBIN)/$*` builds by `cd ./cmd/$* && $(GOBUILD)`.
Eleven `COMMANDS` (`capcli`, `downloader`, `integration`, `pics`, `rpcdaemon`, `rpctest`,
`sentry`, `txpool`, `evm`, `caplin`, `snapshots`, `mcp`; `Makefile:189-200`) are expanded
against this one rule via `$(COMMANDS): %: %.cmd` (`Makefile:203`), so adding a new CLI tool
under `cmd/` needs one line appended to `COMMANDS`, not a new build rule.

**Composite targets, quoted verbatim:**

```make
## erigon:                              build erigon
erigon: go-version erigon.cmd
	@rm -f $(GOBIN)/tg$(GOEXE) # Remove old binary to prevent confusion where users still use it because of the scripts

## all:                               run erigon with all commands
all: erigon $(COMMANDS)

## gen:                               generate all auto-generated code in the codebase
gen: mocks solc abigen gencodec graphql grpc stringer versions-gen

## lint:                              run all linters (fast-only first for quick feedback, then full)
lint:
	@go tool golangci-lint run --config ./.golangci.yml --fast-only
	@go tool golangci-lint run --config ./.golangci.yml
	@$(MAKE) check-generated
```

(`Makefile:185-187, 604, 477-481`) — `lint` deliberately runs `golangci-lint` **twice**
(fast-only subset first for quick contributor feedback, then the full ruleset) before
folding in `check-generated` (a `go mod tidy` + `git diff --exit-code` gate on `go.mod`/
`go.sum`, `Makefile:320-324`) — so "lint" bundles static analysis and dependency-hygiene
into one target, matching the vendored `CLAUDE.md`'s instruction to run `make lint` before
every push and to "run it repeatedly until clean" because the linter is non-deterministic
in which files it scans per run.

**Test targets form a small inheritance chain**, all funneling through `test-filtered`
(`Makefile:224-236`), which pipes `go test` output through `tools/filter-test-output`
into `tee run.log`, and on macOS auto-provisions a ramdisk (`tools/create-ramdisk`) for
scratch test I/O unless `ERIGON_EXECUTION_TESTS_TMPDIR` is already set (with a
`hdiutil detach` cleanup trap):

| Target | Override applied | Purpose |
|---|---|---|
| `test-short` | `GO_FLAGS += -short -failfast` | Fast local iteration |
| `test-all` | `GO_FLAGS := -timeout 20m $(GO_FLAGS)` | Full suite, single timeout budget |
| `test-all-race` | `GO_FLAGS := -timeout 60m $(GO_FLAGS) -race` | Full suite under the race detector, 3× the timeout |
| `test-sonar-coverage` | adds `-timeout 60m -coverprofile=coverage-test-all.out` | SonarQube coverage ingestion |
| `test-group TEST_GROUP=<name>` | `GOTEST_PACKAGES := $(shell go list ./... \| ./tools/test-groups packages $(TEST_GROUP))` | Run a named CI-defined package subset (CI shards the full suite this way) |
| `test-bench` | `GO_FLAGS += -run=^$$ -bench=. -benchtime=1x -short -timeout=5m` | Compile-and-run-once smoke test for every `Benchmark*` func — proves benchmarks aren't rotted, not a real measurement |

(`Makefile:238-347`) — `default_test_timeout := 20m` and `default_test_race_timeout := 60m`
(`Makefile:98-99`) are named variables specifically so CI can override them via `GO_FLAGS`
without editing the targets.

**Fixture-download targets are split by test-suite family**, each pulling a pinned tarball
URL out of `test-fixtures.json` rather than hardcoding it in the Makefile:

- `test-fixtures-cl` (`Makefile:249-260`) downloads the `cl_mainnet` consensus-spec tarball
  and then deletes four fork-directories (`eip6110`, `whisk`, `eip7441`, `eip7805`) that
  don't pass against Caplin yet — applied post-extract so the same exclusion holds whether
  the fixtures were pulled via this target, `test-all`, or `test-group`.
- `test-fixtures-eest` (`Makefile:262-265`) pulls three named EEST tarballs (`eest_stable`,
  `eest_devnet`, `eest_benchmark`) in one call.
- `test-fixtures-zkevm` (`Makefile:267-270`) pulls a separate `eest_zkevm` execution-witness
  tarball.

**EEST spec-test sharding — the standout CI-scaling pattern** (`Makefile:272-300`). A
`tools/eest-spec-shards.yml` manifest is the declared "single source of truth" (its own
header comment, `tools/eest-spec-shards.yml:1-9`) consumed by three independent readers:
the Makefile itself, `.github/workflows/test-eest-spec.yml`'s matrix-loading step, and
`tools/run-eest-spec-test.sh`'s runtime shard lookup. Each shard entry declares `shard`,
`workers` (parallelism), `max-allowed-failures` (a failure budget — explicitly commented
"bump only with a comment explaining why and a tracking issue"), and an optional
`exec3-parallel` flag. The Makefile parses this **only when an `eest-spec-*` goal is
actually requested** (`Makefile:287`, `ifneq ($(filter eest-spec-%,$(MAKECMDGOALS)),)`) so
`make erigon` never pays the `yq`/`jq` parse cost or requires those tools in `PATH`.
Shard names containing `-race` are routed to a separately built `evm.race` binary
(`Makefile:281-283`, built with `-race` and the same tags), so race-detector coverage is
opt-in per shard rather than doubling the whole spec-test bill. Each shard target
provisions **only its own fixture subset** via `tools/run-eest-spec-test.sh` — the full
corpora across all shards exceed 20 GB extracted and don't fit on the smaller CI runner
disks, which is the stated reason for sharding at all (`Makefile:277-280` comment block).

**This EEST sharding pattern is the single most directly relevant Erigon finding for
fukuii** — see the verdict table; fukuii's `ethereum-tests-nightly.yml` currently runs the
EF test suite as one serial job with no manifest-driven shard/failure-budget split.

**Hive and Kurtosis targets wrap external test harnesses without vendoring them.**
`test-hive` (`Makefile:355-365`) shells out to `act` (the local GitHub Actions simulator,
nektos/act) to run the `test-hive`/`test-hive-eest` *workflow jobs* locally, requiring
`GITHUB_TOKEN` in the environment — this runs the actual CI job definition locally rather
than a hand-duplicated local-only test path. `eest-devnet`, `hive-local`, `eest-hive`
(`Makefile:373-441`) each build a local Docker image tagged with the short commit, clone
`ethereum/hive` fresh into a `temp/` scratch directory, patch its Erigon Dockerfile via
`sed` to point at the just-built local image instead of a published tag, then run one or
more hive suites via a shared `run_suite` `define` block (`Makefile:386-406`) that greps
suite pass/fail counts out of hive's own textual summary output (ANSI-stripped via
`sed -r 's/\x1B\[[0-9;]*[a-zA-Z]//g'`). `kurtosis-{pectra,regular,fusaka}-assertoor`
(`Makefile:457-464`) similarly wrap `kurtosis run` against `ethpandaops/ethereum-package`
with per-fork `.io` args files, guarded by a `check-kurtosis` prerequisite that fails fast
with an install link if the `kurtosis` binary is missing.

**Code-generation is centralized under one `gen` umbrella** (`Makefile:604`) fanning out to
six independent generators — `mocks` (mockgen, with a `grep -r -l` pass that first deletes
every existing `// Code generated by MockGen` file before regenerating, so stale mocks for
deleted interfaces don't linger), `solc` (Solidity contract compilation, delegating to a
sub-`Makefile` in `txnprovider/shutter`), `abigen`, `gencodec` (struct marshalling codegen),
`graphql` (gqlgen), and `grpc`/`protoc-all` (a full protobuf/gRPC pipeline that downloads a
pinned `protoc` release from GitHub directly rather than requiring it pre-installed,
`Makefile:495-502`). A twelfth generator, `versions-gen` (`Makefile:598-601`), is newer and
narrower — it regenerates `db/state/statecfg/version_schema_gen.go` from `versions.yaml` via
a purpose-built `cmd/bumper` tool, called out separately from `gen`'s original five because
it's schema-version-specific rather than interface/RPC-shape codegen.

**Fuzzing is a parameterized single target**, not per-target Makefile entries:
`make fuzz PKG=<pkg> FUZZ=<FuzzName> [FUZZTIME=60s]` (`Makefile:307-315`) runs
`go test $(PKG) -run '^$$' -fuzz '^$(FUZZ)$$' -fuzztime <duration>`, printing a usage
message and exiting 2 if either required variable is missing — this is Go's native
`go test -fuzz` harness, not a third-party fuzzing framework, wrapped only for a friendlier
invocation surface.

**User-provisioning targets exist for container deployment**, not developer workstations:
`user_linux`/`user_macos` (`Makefile:644-666`) create a dedicated non-root `erigon` OS user
(default UID/GID `3473`) for containerized/hardened deployments, and `validate_docker_build_args`
(`Makefile:131-142`) checks the host OS user referenced by `DOCKER_UID`/`DOCKER_GID` actually
exists before a volume-mounted Docker build proceeds, printing the resolved username.

### justfile — a single-target escape hatch, not a parallel task runner

The entire `justfile` (3 lines):

```just
check-windows-cross-builds:
	# utp requires ws2tcpip.h which isn't in my cross compiler setup
	CC=x86_64-w64-mingw32-gcc CGO_ENABLED=1 GOARCH=amd64 GOOS=windows go build -v -tags disable_libutp ./...
```

(`justfile:1-3`). This is not a second task-runner competing with the Makefile for general
duties — it exists because this one cross-compilation check (verifying the codebase still
builds for `windows/amd64` via a MinGW cross-compiler, with the `libutp` P2P transport
disabled because `ws2tcpip.h` isn't available in the maintainer's cross-compile setup) is
apparently easier to keep as a standalone `just` recipe (simpler syntax, no `.PHONY`
boilerplate, no interaction with the Makefile's `GOBIN`/`CGO_CFLAGS` variable plumbing) than
to fold into the Makefile's existing `%.cmd` pattern rule, which assumes the host toolchain
rather than a cross-compiler. It is not registered in CI (no `just` invocation found in
`.github/workflows/`), so it reads as a maintainer's personal pre-flight check rather than
a gated requirement — a single recipe living in a `justfile` purely so it doesn't have to be
squeezed into Make's syntax for a one-off, rarely-run cross-build check.

### fukuii's sbt equivalent

fukuii has no Makefile/justfile — `build.sbt`'s `addCommandAlias` block plays the same
"named, composable task" role that Make's targets and phony dependencies play here. The
directional correspondence:

| Erigon concern | Erigon target | fukuii equivalent |
|---|---|---|
| "Build the main binary" | `make erigon` | `sbt compile` / `sbt compile-all` |
| "Run the fast local test loop" | `make test-short` | `sbt "testOnly *Foo*"` / `testQuick` |
| "Run the full suite with a timeout budget" | `make test-all` | `scripts/agent-tooling/sbt-run.sh <name> testEssential` (background) |
| "Run under a stricter/instrumented mode" | `make test-all-race` (Go race detector) | no direct analog — the JVM has no equivalent single-flag data-race detector; closest is enabling stricter GC/assert flags per `testEssential` |
| "Shard a large external spec suite with per-shard failure budgets" | `eest-spec-<shard>` + `tools/eest-spec-shards.yml` | **does not exist** — `ethereum-tests-nightly.yml` runs the EF suite as one serial job |
| "Regenerate all derived/generated code from one command" | `make gen` | no analog — fukuii has no codegen step analogous to mocks/gRPC/ABI generation |
| "One-off cross-platform build check outside the main task runner" | `justfile`'s `check-windows-cross-builds` | no analog — JVM bytecode is platform-independent, so there is no Erigon-style native cross-compile check to port |

The `tools/eest-spec-shards.yml` pattern — a single YAML manifest read by three independent
consumers (Makefile, CI workflow, runtime shard-selection script) so the shard list, worker
counts, and failure budgets can never drift between "what CI runs" and "what a contributor
running `make eest-spec-<shard>` locally runs" — is the one piece of this section worth
scoping as a real fukuii improvement; see the verdict table.

---

## Architecture documentation — the standout pattern

Erigon's architecture documentation is split across three tiers that together form a
product-level → code-level pairing directly relevant to how fukuii should evolve its own
`docs/architecture/` SNAP-sync documentation:

### Tier 1 — `docs/site/docs/fundamentals/architecture.md`: diagram-driven, product-level (135 lines)

This is the newer, Docusaurus-rendered overview (frontmatter `sidebar_position: 2`,
`docs/site/docs/fundamentals/architecture.md:1-5`), and it opens with a **Mermaid
`flowchart TB` diagram** (`architecture.md:13-40`) showing the six components that run
inside the single `erigon` binary — Sentry, Downloader, Execution (Staged Sync), RPC
Daemon, TxPool, Caplin (CL) — all reading/writing one `datadir` node, with explicit edge
labels (`Caplin -->|"new blocks (Engine API)"| Execution`, `Sentry -->|tx gossip| TxPool`)
and a `classDef`-based color scheme distinguishing "component" boxes from the "storage"
box. This single diagram answers "what talks to what" before any prose does.

The prose then covers, in order:

1. **Staged sync** (`architecture.md:44-69`) — describes Erigon 3's six-stage pipeline
   (Snapshots → Headers → Bodies → Senders → Execution → Finalization) as a text diagram,
   explicitly notes there is **no separate Commitment stage** in Erigon 3 (trie/state-root
   computation runs *inside* Execution), and states the two practical consequences: faster
   initial sync (stages are CPU/IO-bound differently, so download parallelizes with
   execution warm-up) and cheap restarts (per-stage progress checkpoints mean a killed
   process resumes from the last completed stage, never re-executing from genesis). It
   also notes Erigon 3's Execution stage absorbed several previously-separate Erigon 2
   stages (`stage_hash_state`, `stage_trie`, `log_index`, `history_index`, `trace_index`)
   into one pass — named as one reason Erigon 3 syncs faster than Erigon 2.
2. **Modular processes** (`architecture.md:71-82`) — Sentry/Downloader/TxPool/RPC
   Daemon/Caplin can each run as an independent process talking over a private gRPC API;
   the doc gives four concrete reasons to split them out (resource isolation, horizontal
   scaling of RPC Daemons, swapping in a custom TxPool/Sentry implementation, and
   security-surface separation of p2p vs JSON-RPC) rather than just asserting modularity
   as a feature.
3. **Storage model** (`architecture.md:84-98`) — a two-row table contrasting mutable
   `chaindata/` (MDBX, ~15 GB on mainnet) against immutable, content-addressed
   `snapshots/` (`.seg` files, BitTorrent-distributable, byte-identical across every node
   in the world once finalized) — explicitly framed as *why* Erigon's disk footprint is
   small relative to other archive nodes.
4. **Embedded consensus (Caplin)** (`architecture.md:100-106`) — the built-in CL client
   runs by default with no JWT secret to manage and no separate binary; `--externalcl`
   switches to the standard Engine-API path identical to Geth/Besu/Reth.
5. **Flat key-value state vs. Merkle trie** (`architecture.md:108-115`) — the explicit
   architectural trade-off discussion requested: "Where most clients store account state
   in a Merkle Patricia Trie *inside* the database, Erigon stores it as flat key-value
   pairs and computes the trie root incrementally as part of Execution." Read path is a
   single key lookup rather than a multi-level trie traversal; write path defers trie
   hashing until state-root computation actually requires it. The doc names this "the
   single largest reason Erigon's RPC performance stays flat under load" — no background
   trie compaction that can spike CPU during an `eth_call` burst.
6. **Pruning as a retention decision, not a sync mode** (`architecture.md:117-128`) — a
   four-row table (`archive`/`full`/`blocks`/`minimal`) framed explicitly as "Erigon 3 has
   one sync pipeline; the user-facing choice is *what to retain after sync*."

The page closes with a "Where to go next" list of four sibling docs (Database, Modules,
Pruning Modes, Optimizing Storage) — it is deliberately an entry point, not exhaustive.

### Tier 2 — `docs/programmers_guide/guide.md`: older, code-level, symbol-cited (562 lines)

This is a materially different document in age, audience, and density. It has no
frontmatter, no diagrams, and reads as a from-first-principles explanation of Ethereum
state representation aimed at someone about to *modify* the trie/commitment code, not
someone operating a node. It covers ground `architecture.md` never touches:

- **Account content field-by-field** (Nonce, Balance, Root, Code hash) with the exact
  Go symbol responsible for each — `preCheck` in `TxnExecutor`
  (`execution/protocol/txn_executor.go`), `Create`/`Create2` in `EVM`
  (`execution/vm/evm.go`), `EmptyRoot` in `execution/commitment/trie/trie.go`, `SetCode`
  in `IntraBlockState` (`execution/state/intra_block_state.go`) — every claim names the
  file and function (`guide.md:9-87`).
- **The hexary Patricia trie construction algorithm from scratch** (`guide.md:105-278`):
  prefix groups, leaf/branch/extension nodes, and a from-scratch specification of a
  **stack-machine opcode set** (`LEAF`, `LEAFHASH`, `EXTENSION`, `EXTENSIONHASH`,
  `BRANCH`, `BRANCHHASH`, `HASH`) for producing a trie root from a sorted key-value
  sequence in one pass with two parallel stacks (a "hash stack" and a "node stack") — this
  is the actual algorithm `HashBuilder` implements
  (`execution/commitment/trie/hashbuilder.go`, cited at `guide.md:276-277`).
- **Multiproofs** (`guide.md:279-317`) — defined precisely as "a sequence of key-value
  pairs + a sequence of hashes + structural information," with a worked example, backed by
  `execution/commitment/trie/retain_list.go`'s `RetainList` type.
- **The structural-information generation algorithm** (`guide.md:318-410`), a dense,
  fully worked step-by-step trace through two example leaves (`30` and `31`) showing
  exactly how the `groups` slice, common-prefix computation, and recursive step invocation
  produce `LEAF`/`BRANCH`/`EXTENSION` opcodes — implemented by `GenStructStep`
  (`execution/commitment/trie/gen_struct_step.go`).
- **Four opcode extensions for accounts with storage/code** (`CODE`, `CODEHASH`,
  `ACCOUNTLEAF`, `ACCOUNTLEAFHASH`, `EMPTYROOT`; `guide.md:434-478`).
- **Merkle root calculation over cursors** (`guide.md:480-524`) — the "two cursors, one
  over sorted state, one over cached intermediate hashes" preorder-traversal technique
  that makes root recomputation hardware-friendly (sequential reads and forward jumps
  only), with a literal annotated trace of what each cursor step returns.
- **The self-destruct-with-huge-storage attack and its mitigation** (`guide.md:525-534`)
  — an "Incarnation" counter on accounts, bumped on every `SELFDESTRUCT`/`CREATE2`, folded
  into the storage key (`{account_key}{incarnation}{storage_hash}`) so stale storage from
  a destroyed account's prior incarnation is skipped rather than requiring an expensive
  synchronous delete of potentially huge storage.
- A one-line pointer to `docs/readthedocs/source/stagedsync.rst` for staged-sync detail
  (`guide.md:553`) and a legacy dev-net-with-geth-nodes bootstrap recipe (`guide.md:556-562`)
  that reads as historically vestigial (references a hardcoded devnet key and a `geth`
  binary, not Erigon's own tooling) — a sign this file predates the current get-started
  docs and has not been fully pruned.

**Why this pairing matters for fukuii.** `architecture.md` is "what a node operator or new
contributor needs to form a correct mental model in ten minutes"; `guide.md` is "what a
contributor implementing or debugging the trie/commitment code needs, with exact symbol
citations and worked traces." Neither replaces the other — `architecture.md` never
explains the `HashBuilder` opcode stack machine, and `guide.md` never explains why
`--externalcl` exists or what `--prune.mode` values mean. fukuii's `docs/architecture/`
(16 files) mixes both altitudes within single files rather than maintaining this split —
e.g. `architecture-overview.md` (983 lines) and the `SNAP_SYNC_*.md` cluster (8 files)
together cover ground comparable in complexity to Erigon's staged-sync pipeline, but
without a dedicated top-level diagram-first entry point analogous to `architecture.md`
Tier 1, nor a symbol-cited deep-dive analogous to `guide.md` Tier 2 for the SNAP-sync
internals specifically (state-domain layering, MDBX table shapes, actor message flow).

### Tier 3 — Standalone deep-dive design notes: `domain-epoch-unwind.md` and `merry-go-round-sync.md`

These sit outside both `docs/site/` and `docs/programmers_guide/` as **root-level `docs/`
design notes**, each documenting one specific mechanism in isolation rather than being
folded into either tier above:

**`docs/domain-epoch-unwind.md`** (388 lines) is an **exploratory design proposal**, not
documentation of shipped behavior — its own header states "Status: exploratory design"
(`domain-epoch-unwind.md:3`) and frames the commitment domain specifically as "the open
problem" deferred to a separate workstream (`domain-epoch-unwind.md:4-11`). It proposes
extending an existing in-memory `(txNum, epoch)` lazy-unwind mechanism (from a cited PR,
`#21386`) into the **persistent MDBX domain layer**: today, unwinding a domain (Account/
Storage/Code/Commitment) is eager changeset replay — `O(number of changed keys)`, deleting
current values and restoring prior ones per key (cited at `db/state/domain.go:1231-1314`).
The proposal's core idea is to make unwind `O(1)` by incrementing a global epoch counter
and marking post-floor entries stale rather than eagerly rewriting them, falling through to
history on a stale read (`domain-epoch-unwind.md:88-102`, citing `domain.go:1621-1662`).
Section 8 treats the **commitment domain as the genuinely hard case** — because
`HistoryDisabled: true` for commitment (cited at `statecfg/state_schema.go:274`), there's
no history fall-through available, so the recommended shape is a hybrid: lazy-epoch unwind
for Account/Storage/Code (which have history to fall back to), but **recompute** commitment
branches from the reverted state rather than storing commitment priors at all — trading
rare-case CPU (recompute on deep reorgs) for eliminating commitment's ~187 GB prior-value
storage stream. The doc also proposes a **"backoff retention" schedule** for commitment
checkpoints (dense for the most recent ~96 blocks — matching the existing `maxReorgDepth`
constant — geometrically sparser beyond that, capped at ~4096 blocks) so that in the common
case (small reorgs) the unwind is an exact restore and recompute is only a rare deep-reorg
safety net. Sections 8's later subsections extend well beyond the domain/unwind topic into
BitTorrent-v2 Merkle-proof sub-file access and web-standards alignment (RFC 9110 Range, RFC
9530 digests, MICE) for verifiable partial snapshot reads — flagged in the doc itself as
"the heaviest open item" requiring sign-off from whoever owns the chain/snapshot
definition, since anchoring a snapshot roll-up root in the canonical chain is a
consensus-surface change.

**`docs/merry-go-round-sync.md`** (55 lines) documents a considerably older and more
narrowly scoped idea: a **cyclic peer-to-peer state-distribution protocol**, originally
proposed on ethresear.ch (linked at the top, `merry-go-round-sync.md:3`) and unrelated to
the epoch-unwind mechanism above. It defines "seeders" (nodes with the full current state,
continuously updating it) and "leechers" (nodes joining to acquire all or part of the
state), organizes time into **cycles** (one full state-distribution round, expected to take
"some hours" on mainnet) subdivided into **ticks** (each anchored to a mined block plus a
fixed duration, e.g. 30 seconds), and specifies reorg-handling rules for tick timing when a
block at the same height is replaced mid-tick. The state itself is split into "pieces" via
four possible bound-pair specifications keyed on `keccak256(address)` and optionally
`keccak256(address, storage_location)` (`merry-go-round-sync.md:40-50`), and a **"sync
schedule"** maps tick number to state piece — seeders are expected to derive this schedule
independently, "by virtue of having the entire Ethereum state available," with no extra
coordination needed. This document is much closer to a historical protocol proposal than an
implementation guide — it references an illustrative image
(`./assets/mgr-sync-1.png`) and does not cite any Go source files, unlike every other
document reviewed in this report.

**Why this pairing is directly relevant inspiration for fukuii's SNAP-sync docs.** Erigon
keeps its architecture-level overview (Tier 1), its code-level trie/commitment mechanics
(Tier 2), and its **specific, high-complexity sync/unwind design problems** (Tier 3, one
file per mechanism) as separate, differently-scoped documents rather than one large file
trying to be all three. fukuii's `docs/architecture/SNAP_SYNC_*.md` cluster (8 files:
`ACTOR_CONCURRENCY`, `ACTOR_IMPLEMENTATION`, `BYTECODE_IMPLEMENTATION`,
`CLEANUP_IMPLEMENTATION`, `ERROR_HANDLING`, `IMPLEMENTATION`,
`PROGRESS_MONITORING_SUMMARY`, `STATE_STORAGE_REVIEW`, `STATE_VALIDATION`) is already
organized close to Erigon's Tier-3 pattern — one focused file per sync sub-mechanism — but
lacks an equivalent Tier-1 diagram-first overview page that a new contributor would read
*before* any of the eight `SNAP_SYNC_*` files, and lacks a Tier-2 symbol-cited deep-dive on
the state-domain/MDT internals comparable to `guide.md`'s trie chapter. The concrete,
low-cost port is Tier 1: a single Mermaid-diagram-led `docs/architecture/architecture.md`
(or a renamed/promoted section of the existing `architecture-overview.md`) that shows
fukuii's own component graph (RPC/JSON-RPC, P2P/herald's domain, SNAP-sync actors, RocksDB
storage) the way Erigon's diagram shows Sentry/Downloader/Execution/RPC/TxPool/Caplin —
see the verdict table.

---

## Get-started documentation depth

`docs/site/docs/get-started/` is a dedicated Docusaurus section (its own `_category_.json`,
`sidebar_position: 2` on the section index) with six top-level entries:

| File/dir | Size | Content |
|---|---|---|
| `index.mdx` | 81 lines | Card-grid landing page linking to all five siblings plus an external "Interacting with Erigon" section — pure navigation, no prose content of its own |
| `why-using-erigon.mdx` | 182 lines | Marketing/positioning page: a four-stat strip (10× cheaper backup, <2 TB archive node, 1 binary, "40+ AI query tools via built-in MCP server"), an eight-card "architectural & performance advantages" grid (Caplin, flat-DB state, immutable BitTorrent data, staged sync, predictable RPC, modularity, OtterSync, flexible pruning), a three-card "benefits by audience" grid (RPC providers/large stakers, home users/solo stakers, developers), and a dedicated "AI-Native Node Access (MCP)" section documenting a **built-in Model Context Protocol server** bound to `127.0.0.1:8553` by default, read-only, with example natural-language queries and a one-line `claude mcp add` registration command (`why-using-erigon.mdx:155-181`) |
| `hardware-requirements.mdx` | 71 lines | Per-network (Ethereum mainnet / Gnosis Chain / Polygon), per-pruning-mode disk-size and RAM tables, driven by a `disk-sizes.json` data import rather than hand-typed numbers (`hardware-requirements.mdx:9, 34-36`) — Polygon's table is explicitly frozen with a warning that the last Erigon release officially supporting Polygon is `3.1.*` and the figures (from Sept 2025) are "no longer automatically updated" |
| `installation/` (3 files, `index.mdx` 557 lines) | Largest single file in this section | Tabbed by OS (Linux/macOS/Windows) linking to `<details>`-collapsed sections for Docker, pre-built binaries (with SHA256 checksum verification steps), build-from-source (Git tag checkout → `make -j<n> erigon`), Windows native compilation (Chocolatey/MinGW, including a callout about false-positive antivirus detections on MinGW's compiler-detection temp files), and WSL2 (with an explicit performance table warning against `/mnt/c/`-mounted Windows partitions for the datadir); a separate `upgrading.md` (89 lines, not read in full here) covers version-upgrade steps |
| `migrating-from-geth.mdx` | 134 lines | What changes vs. go-ethereum and how to preserve existing data when switching clients |
| `easy-nodes/` (index + 3 network guides) | index 35 lines | One-command guided setups for Ethereum, Gnosis Chain, and Polygon nodes with sensible defaults — explicitly framed as the "easy path" alternative to hand-assembling flags from the rest of the docs |

The installation page's JS-driven `<details>` accordion behavior (`installation/index.mdx:10-69`)
— clicking one OS-method link auto-closes any other open `<details>`, updates the URL hash,
and smooth-scrolls once the resulting layout height stabilizes — is a materially more
interactive documentation surface than a typical static Markdown page; it exists purely to
keep the single 557-line file navigable despite covering four platforms × multiple install
methods each.

### Comparison to fukuii's `docs/getting-started/`

fukuii's equivalent section is four flat Markdown files with no sub-navigation:
`index.md` (115 lines), `quickstart.md` (120 lines), `build-from-source.md` (234 lines),
`codespaces.md` (95 lines) — 564 lines total versus Erigon's get-started section (roughly
1,150+ lines across `index.mdx`, `why-using-erigon.mdx`, `hardware-requirements.mdx`,
`installation/` incl. `upgrading.md`, `migrating-from-geth.mdx`, and `easy-nodes/`).
Structurally, fukuii has:

- **No dedicated hardware-requirements page** with per-network, per-mode disk/RAM tables —
  sizing guidance, if present, is folded into other docs rather than a single reference
  table driven by a JSON data file.
- **No "why use this client" positioning page** — no direct analog to `why-using-erigon.mdx`'s
  stat-strip/advantages-grid/benefits-by-audience structure, and no fukuii-native MCP-server
  callout equivalent to Erigon's dedicated "AI-Native Node Access" section (fukuii does
  document MCP tooling, but for driving a *running node* via `.github/copilot/`, not as a
  get-started-page selling point for new users).
- **A comparable but smaller installation surface** — `build-from-source.md` (234 lines)
  covers source builds in similar depth to Erigon's `install-source` `<details>` block, but
  fukuii has no equivalent to Erigon's OS-tabbed single-page accordion covering
  Docker/binary/source/Windows-native/WSL in one navigable page, nor a Docker-first
  pre-built-binary path with checksum verification instructions.
- **No "migrating from X" page** — no fukuii analog to `migrating-from-geth.mdx` (arguably
  lower priority for fukuii today since it isn't positioning itself as a drop-in Geth
  replacement, but worth noting as a documented pattern other clients use to lower
  switching friction).
- **No "easy nodes" guided-setup tier** — Erigon's `easy-nodes/` provides copy-paste,
  sensible-defaults setups per network as a middle ground between "read the full docs" and
  "figure out every flag yourself"; fukuii's `fukuii-first-start` skill covers similar
  ground operationally (interactive node bring-up) but has no static-doc equivalent for a
  reader who isn't running Claude Code.

---

## Release process (RELEASE_INSTRUCTIONS.md)

The entire file (19 lines) is a three-step maintainer checklist, reproduced in full:

**1. Update DB Schema version if required.** `db/kv/tables.go` defines
`DBSchemaVersion = typesproto.VersionReply{Major: 7, Minor: 0, Patch: 0}`
(`db/kv/tables.go:32`) — bumped whenever a database schema change requires data migration;
"in most cases, it is enough to bump minor version" (`RELEASE_INSTRUCTIONS.md:3-6`).

**2. Update remote KV version if required.** `db/kv/remotedbserver/remotedbserver.go`
defines `KvServiceAPIVersion = &typesproto.VersionReply{Major: 7, Minor: 0, Patch: 0}`
(`remotedbserver.go:66`) — bumped for changes to the remote KV interface or an underlying
schema change; the instructions explicitly recommend changing both the DB schema version
and the remote KV version **together** (`RELEASE_INSTRUCTIONS.md:8-12`). Cross-checking the
source: `remotedbserver.go:117-120` actually compares `KvServiceAPIVersion.Major` against a
connecting client's reported `dbSchemaVersion.Major` to decide remote-KV compatibility —
these two version numbers are load-bearing at runtime, not just documentation metadata.

**3. Update `app.go`.** After a release branch is cut for, e.g., Erigon v3.6.0:
on the new `release/3.6` branch of `erigon`, set `Major = 3`, `Minor = 6`, `Micro = 0`,
`Modifier = ""`, and `DefaultSnapshotGitBranch = "release/3.6"` in
`db/version/app.go` — plus create a matching `release/3.6` branch of the separate
`erigon-snapshot` repository. On `main`, bump forward: `Major = 3`, `Minor = 7`,
`Micro = 0`, `Modifier = "dev"` (`RELEASE_INSTRUCTIONS.md:14-19`). Reading the live file
confirms the current state matches this pattern exactly: `db/version/app.go:31-36` has
`Major = 3, Minor = 6, Micro = 0, Modifier = "dev", DefaultSnapshotGitBranch = "release/3.4"`
— i.e. `main` is mid-cycle for the *next* release (3.6) while still pointing its default
snapshot branch at the last cut release branch (3.4), consistent with the checklist's
"bump main forward, but the release branch you just cut owns its own snapshot branch"
model. CalVer-adjacent versioning is used loosely — the file comments "see
https://calver.org" (`app.go:29`) even though the scheme (Major.Minor.Micro + a
free-text `Modifier`) is closer to semver with a dev/release modifier than to a
calendar-based scheme.

The checklist is intentionally minimal — three steps, no automation, no CI gate enforcing
any of them — and is entirely about **coordinating three separately-versioned surfaces**
(on-disk DB schema, remote gRPC KV API, and the user-facing binary version) that would
otherwise drift independently across a release boundary.

---

## Historical audits

`docs/audits/` contains exactly four PDFs, all inherited from the go-ethereum lineage
(none reference Erigon-specific code — the naming and dates predate Erigon's own
`erigontech` fork):

| File | Subject | Year |
|---|---|---|
| `2017-04-25_Geth-audit_Truesec.pdf` | go-ethereum security audit by Truesec | 2017 |
| `2018-09-14_Clef-audit_NCC.pdf` | Clef (geth's external signer) audit by NCC Group | 2018 |
| `2019-10-15_Discv5_audit_LeastAuthority.pdf` | Node discovery v5 protocol audit by Least Authority | 2019 |
| `2020-01-24_DiscV5_audit_Cure53.pdf` | A second, later Discv5 audit by Cure53 | 2020 |

No third-party audit specific to Erigon's own staged-sync pipeline, flat-KV state layout,
MDBX integration, or Caplin consensus-layer implementation is present in this directory —
these four documents cover only the shared devp2p/discovery/signer components Erigon
inherited from (or interoperates with) the broader go-ethereum ecosystem. This is worth
noting as a gap rather than a pattern to imitate: a reader browsing `docs/audits/`
expecting Erigon-specific audit coverage will find none.

---

## `db/agents.md` and `execution/stagedsync/agents.md`

Both files are short (68 and 66 lines respectively), AI-agent-facing architecture summaries
in the same spirit as fukuii's own module-level agent context files — not full design docs.
`db/agents.md` describes the temporal-database architecture (hot MDBX vs. cold immutable
snapshots), the four state domains (Accounts/Storage/Code/Commitment) each with current +
historical + index components, and the ETL sort-before-insert framework. `execution/stagedsync/agents.md`
lists the eight-stage pipeline (Snapshots → Headers → BlockHashes → Bodies → Senders →
Execution → TxLookup → Finish — a finer-grained enumeration than `architecture.md`'s
six-stage simplified summary, since it separately lists `BlockHashes` and `TxLookup` index
stages that `architecture.md`'s "simplified pipeline" folds into "Finalization"), the
`Stage` interface contract (`ID`, `Forward`, `Unwind`, `Prune` function fields), and the
reorg-handling flow (`UnwindTo()` → stages unwind in `DefaultUnwindOrder` → state rolled
back via domain writers → execution resumes). Both are consistent with, and slightly more
granular than, the corresponding sections of `architecture.md` and `db/agents.md`'s own
prose — no discrepancies found between these agent-facing summaries and the fuller docs.
These two files were reviewed directly for this report rather than deferred to a separate
agentic-tooling document, since no `erigon/agentic-tooling-pattern.md` exists yet in this
research tree (only `nethermind/agentic-tooling-pattern.md` and
`fukuii/agentic-tooling-pattern.md` currently do) — a future pass specifically comparing
Erigon's `CLAUDE.md`, `.claude/rules/`, and `agents.md` file network against Nethermind's
`.agents/rules/` pattern would be additive to this report, not a duplicate of it.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable | Reasoning |
|---|---|---|
| `tools/eest-spec-shards.yml` — single YAML manifest driving shard list, worker counts, and per-shard failure budgets, read identically by the Makefile, the CI workflow, and a runtime script | **Needs design** | Directly addresses a known fukuii gap: `ethereum-tests-nightly.yml` runs the EF suite as one serial job (a separate finding already tracks Besu's sharding pattern as a proposed fix). Erigon's version additionally pins a **failure budget per shard** with a mandatory tracking-issue comment before it can be raised — a stricter, more auditable discipline than "just split the job." Porting requires: a fukuii-native shard-manifest format (YAML is fine — sbt/CI can both read it), a small script analogous to `tools/run-eest-spec-test.sh` to resolve shard→test-tag mapping, and wiring the existing `testEthereum`/`testOlympia` aliases to consume it instead of running as one undivided tagged run. Scope as a discrete follow-up alongside the Besu-sharding finding, not a copy-paste. |
| `make help`'s self-documenting `## target: description` convention, deriving the help text from the same comments a contributor already reads inline | **Not portable as literal syntax, but the principle is portable** | sbt has no equivalent lightweight `## comment → generated help text` convention for `addCommandAlias` entries. The underlying discipline — one canonical, always-current list of "what does this named task do" — is already fukuii's practice via `AGENTS.md`'s build-command table, which is explicitly flagged in that same file as needing to defer to `build.sbt`'s `addCommandAlias` block as the authoritative source rather than being re-typed. No action needed beyond continuing that discipline. |
| Architecture doc Tier 1: a single Mermaid-diagram-led overview page (`architecture.md`) as the deliberate first thing a new contributor reads | **Port now** | Low-cost, high-value. fukuii's `docs/architecture/` (16 files, including `ARCHITECTURE_DIAGRAMS.md` which already contains diagrams) has no single page that plays `architecture.md`'s specific role: one diagram + a five-topic prose walkthrough (staged sync/stages, modular processes, storage model, embedded-consensus trade-off, pruning-as-retention) sized to be readable in minutes. Consider promoting or restructuring `architecture-overview.md` (983 lines — too long for this role today) into a short diagram-first entry point that links out to the existing longer documents, mirroring Erigon's "Where to go next" pattern. |
| Architecture doc Tier 2: an older, symbol-cited, worked-example-heavy deep-dive (`programmers_guide/guide.md`) coexisting with the newer Tier-1 doc rather than being deleted or merged | **Port now (as a process lesson)** | The lesson is structural, not content: it is fine — good, even — to keep an older, denser, code-citation-heavy document alongside a newer polished one, provided each serves a distinct audience (operator/newcomer vs. implementer-debugging-the-code). fukuii should apply this when deciding whether to prune or consolidate its own `docs/architecture/SNAP_SYNC_*.md` cluster: the eight files already largely serve the "implementer" audience Tier 2 serves; what's missing is the Tier-1 counterpart (see above), not a deletion of Tier-2-equivalent content. |
| Architecture doc Tier 3: standalone, single-mechanism deep-dive design notes (`domain-epoch-unwind.md`, `merry-go-round-sync.md`) living outside both the polished site and the older guide | **Already largely ported; keep doing it** | fukuii's `docs/architecture/SNAP_SYNC_*.md` cluster already follows this exact pattern — one focused file per sync sub-mechanism (actor concurrency, bytecode implementation, cleanup, error handling, progress monitoring, state storage, state validation). No structural change needed; the gap is the missing Tier-1 pairing above, not this tier. |
| `RELEASE_INSTRUCTIONS.md`'s three-surface version-coordination checklist (DB schema version, remote KV API version, user-facing app version, kept in a plain markdown checklist with no automation) | **Needs design (lightweight)** | fukuii doesn't have three independently-versioned surfaces in quite the same shape (no separate remote-KV gRPC service), but the *general* problem — DB schema version and any client-facing API/protocol version drifting independently across a release — is universal. Worth a short, equivalent checklist doc if/when fukuii formalizes a release process; not worth inventing new versioned surfaces just to have something to check. |
| `docs/audits/` containing only inherited go-ethereum-lineage PDFs, none Erigon-specific | **Not portable / informational only** | Nothing to port — this is a gap in Erigon's own documentation, not a pattern. Noted only so a future reader doesn't assume Erigon has independently-audited its own staged-sync/flat-DB/Caplin code based on this directory's existence. |
| Get-started section depth: dedicated hardware-requirements page (data-file-driven tables), a "why use this client" positioning page with an AI/MCP callout, a single OS-tabbed accordion installation page, and a guided "easy nodes" tier | **Needs design (staged)** | fukuii's `docs/getting-started/` (564 lines across 4 files) is smaller and flatter than Erigon's equivalent (~1,150+ lines across 6 entries). Highest-value, lowest-cost pieces to prioritize: (1) a dedicated hardware-requirements page — fukuii likely already has informal sizing knowledge that could be tabulated; (2) documenting fukuii's own MCP tooling (`.github/copilot/`) as a get-started-page selling point, not just an operational how-to, mirroring `why-using-erigon.mdx`'s dedicated MCP section. Lower priority: a "migrating from geth/besu/nethermind" page and an OS-tabbed single-page installation accordion, both real but non-urgent given fukuii's current user base size. |
| `db/agents.md` / `execution/stagedsync/agents.md` — short, focused, module-scoped AI-agent context files distinct from the fuller architecture docs | **Already ported** | fukuii's per-module context (subagent routing table in `CLAUDE.md`, `.claude/agents/REFERENCES.md`) already plays an equivalent role; no specific gap identified from reviewing these two files against fukuii's existing agent-context structure. |

---

*Compiled from a direct read of every file cited above in the vendored clone at
`.claude/repo-references/clients/erigon/`. Line numbers refer to that clone's current
checkout; re-verify against `git log` if the vendored copy is refreshed.*
