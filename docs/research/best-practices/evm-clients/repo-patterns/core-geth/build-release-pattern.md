# core-geth — Build Tooling & Lightweight Benchmark CI

Source: `.claude/repo-references/clients/core-geth/` (vendored full clone — `.git/` present,
`origin` a fork at `white-b0x/core-geth`, `upstream` set to `ethereumclassic/core-geth`,
currently checked out on the `main` branch at commit `b28aa0a0bbb1e3ba72ce11afb9310d9dc38c1832`,
2026-06-26). Per the sibling repo-hygiene survey (`docs/research/best-practices/evm-clients/repo-patterns.md`),
core-geth's `SECURITY.md` is flagged as stale unlocalized upstream boilerplate rather than a
maintained document; this doc treats the vendored clone as a genuine git history otherwise —
including its own local `main`-branch commits (e.g. the Olympia ECBP-1100 work referenced
below), which are the current maintainer's own additions on top of upstream, not upstream
artifacts themselves. Every claim below is traceable to a file in the vendored clone.

core-geth is a Go execution-layer client, and specifically an **ETC-focused fork of
go-ethereum** rather than an independent implementation — its Makefile, doc-site tooling,
and CI are all thin, leaner variants of go-ethereum's own patterns (already documented in
`docs/research/best-practices/evm-clients/repo-patterns/go-ethereum/build-release-pattern.md`),
plus a small number of fork-specific additions layered on top. This doc is deliberately
shorter than its go-ethereum, Nethermind, and Reth siblings — core-geth's build surface really
is smaller, and padding it would misrepresent the fork's actual footprint.

---

## Makefile — the `test` / `test-coregeth` split

core-geth's root `Makefile` (129 lines) is architecturally the same "thin wrapper over
`build/ci.go`" pattern as upstream go-ethereum's — `geth` (`Makefile:13-16`), `all`
(`Makefile:19-20`), and `lint` (`Makefile:98-99`) all just shell out to
`$(GORUN) build/ci.go <cmd>`. What's new relative to upstream is a second, parallel test
surface that core-geth adds on top of go-ethereum's shared `test` target, and the Makefile is
where the fork draws the line between the two:

| Target | Body (`Makefile:`) | What it actually tests |
|---|---|---|
| `test` | `23-24`, depends on `all`, then `go run build/ci.go test -timeout 20m` | The **upstream-shared** test surface — same `ci.go test` entrypoint go-ethereum itself uses, run against whatever code lives in this tree (which includes core-geth's changes, but exercises the shared upstream test harness, not fork-specific scenarios) |
| `test-coregeth` | `32-35`, chains `test-coregeth-features`, `test-coregeth-consensus`, `test-coregeth-regression-condensed` | The **fork-specific** test surface — see below |

`test-coregeth`'s three sub-targets are each a distinct kind of fork-only verification:

- **`test-coregeth-features-coregeth`** (`Makefile:58-60`) sets
  `COREGETH_TESTS_CHAINCONFIG_FEATURE_EQUIVALENCE_COREGETH=on` and runs `go test ./tests` —
  this is a chain-config **equivalence** test: it asserts that core-geth's own
  fork-feature/datatype config representation produces identical consensus behavior to
  whatever the "reference" config representation is, rather than testing a specific feature.
- **`test-coregeth-features-clique-consensus`** (`Makefile:62-64`) sets
  `COREGETH_TESTS_CHAINCONFIG_CONSENSUS_EQUIVALENCE_CLIQUE=on` and restricts to `-run
  TestState` explicitly *because* (per the inline comment) "Blockchain tests will care about
  rewards, etc." — i.e., block-reward-sensitive tests are deliberately excluded from a
  consensus-equivalence check that isn't about rewards.
- **`test-coregeth-regression-condensed`** (`Makefile:70-74`, depends on `geth`) runs three
  `./tests/regression/simulated/test.sh` invocations against pre-exported, gzipped RLP chain
  segments (`classic-condense-state`, `foundation-condense-state`,
  `foundation-condense-state-2`) — real historical block data replayed through a built `geth`
  binary to catch import regressions, not synthetic state-test vectors.

There's also a *related but not `test-coregeth`-gated* generation surface —
`tests-generate-state` / `tests-generate-difficulty` (`Makefile:78-95`) — which runs the state/
difficulty test generators under `COREGETH_TESTS_GENERATE_STATE_TESTS=on` /
`COREGETH_TESTS_GENERATE_DIFFICULTY_TESTS=on` and moves the output into
`tests/testdata-etc/{GeneralStateTests,LegacyTests,DifficultyTests}` — this is how the fork's
own generated-test-fixture directories under `testdata-etc/` get refreshed; it's invoked
manually, not part of `test` or `test-coregeth`, and not wired into any CI workflow examined
below.

**Why this is worth calling out as a pattern, not just an inventory item**: the value here
isn't the specific env-var-gated test functions (those are core-geth/go-ethereum-specific
plumbing) — it's the *convention* of using a Makefile target boundary to make "does this
pass upstream's shared test surface" and "does this pass our fork's own consensus/config
equivalence checks" two separately invokable, separately named things. A reviewer (or CI job)
can run `make test` alone to confirm the fork hasn't broken anything upstream cares about, or
`make test-coregeth` alone to confirm the fork's own ETC-specific guarantees, without either
target silently subsuming the other.

Two more Makefile items worth noting for completeness: `sync-parity-chainspecs`
(`Makefile:29-30`) is explicitly commented `# DEPRECATED.` ("No attempt will be made after the
Istanbul fork to maintain Parity configuration support") — dead but not yet deleted; and
`docs-generate` (`Makefile:109-110`) runs
`env COREGETH_GEN_OPENRPC_DOCS=on go test -count=1 -run BuildStatic ./ethclient` — OpenRPC
JSON-RPC API documentation is generated by running a Go test function with a build-tag-style
env var switch, not by a standalone doc-gen binary.

---

## Doc-site tooling — same choice as fukuii (mkdocs)

core-geth ships a full `docs/` tree with top-level categories `core/`, `developers/`,
`getting-started/`, `JSON-RPC-API/`, `tutorials/`, `audits/`, `postmortems/` (plus
`assets/`, `img/`, and a root `index.md`), built with `mkdocs` + the `mkdocs-material` theme
(`mkdocs.yml:1-45`) and a pinned `requirements-mkdocs.txt` (`mkdocs==1.3.0`,
`mkdocs-material==7.0.6`, `mkdocs-git-revision-date-localized-plugin==0.8`, etc.).
`docs/getting-started/installation.md` (73 lines) covers pre-built binaries, Docker
(`docker run` and the `alltools.` image tag convention), and points to a "build from source"
page; `docs/getting-started/run-cli.md` (332 lines) covers the CLI invocation surface
(`--classic`/`--testnet`/`--rinkeby`/`--mordor` network flags, fast-sync vs full-sync,
the JS console). Both are present and populated, not stubs.

This is **not a gap relative to fukuii** — fukuii already has its own root `mkdocs.yml` and
uses the same tool. The only thing worth flagging for cross-reference is that core-geth's
`docs-deploy.yml` workflow (below) deploys via `mkdocs gh-deploy --force` on pushes to
`master`/`main` scoped to `mkdocs.yml`, `docs/**`, `requirements-mkdocs.txt` path filters
(`docs-deploy.yml:4-12`) — a pattern fukuii can compare its own doc-deploy automation against
if/when it stands one up, but it is a "same tool, maybe borrow the path-filter trigger
shape" note, not a "needs design" gap.

---

## CI workflow inventory

`.github/workflows/` contains **9** active workflow files:

| Workflow | Trigger | Purpose |
|---|---|---|
| `test-linux.yml` | push to `master`, PR, `workflow_dispatch` | Three jobs: `lint` (`make lint`), `test-cg` (`make test-coregeth`), `test` (`make all && make test`) — this is where the Makefile's `test`/`test-coregeth` split becomes two distinct CI jobs, confirming the split isn't just a local-dev convenience (`test-linux.yml:14-53`) |
| `go-generate-check.yml` | PR, `workflow_dispatch` | Installs `solc` + devtools, runs `go generate` across the module (minus a `trezor` exception), reverts one specific hand-modified generated-file commit before diffing, then fails if anything changed — a drift check for generated code, not a functional test |
| `evmc.yml` | push/PR to `master` | Builds EVMC-external-interpreter support (installs `cmake`, a pinned `gcc-9`/`g++-9` toolchain for GLIBCXX compatibility, `gomobile`) then runs `make test-evmc` — the same `hera`/`evmone` external-interpreter test path the Makefile's `test-evmc` target defines |
| `audit-bootnodes.yml` | daily cron (`0 6 * * *`), PR touching `params/bootnode*`, `workflow_dispatch` | Builds `devp2p` and pings every bootnode enode found under `./params/*bootnode*go`; on non-PR runs it auto-removes unresponsive entries and opens a PR (`peter-evans/create-pull-request@v3`) titled `params: remove unresponsive bootnodes` — a self-healing config maintenance job, not a test |
| `docs-deploy.yml` | push to `master`/`main` (path-scoped), `workflow_dispatch` | `pip install -r requirements-mkdocs.txt && mkdocs gh-deploy --force` |
| `release-packages.yml` | push of `v*` tags, `workflow_dispatch` | Matrix build (macOS/Linux/ARM/Windows) via `make all` or, for ARM, direct `go run build/ci.go install -dlgo -arch arm ...` invocations per `GOARM` level (5/6/7) plus arm64, then `./build/archive-signing.sh` and a draft GitHub release upload |
| `bench-core.yml` | push to `master`, `workflow_dispatch` | See Benchmark CI section below |
| `bench-trie.yml` | push to `master`, `workflow_dispatch` | See Benchmark CI section below |
| `bench-vm.yml` | push to `master`, `workflow_dispatch` | See Benchmark CI section below |

All nine use `actions/setup-go@v5` pinned to `go-version: '1.26'`.

**Legacy CI configs at repo root — confirmed vestigial, don't copy.** Four files predate the
GitHub Actions migration and are not referenced by any current automation:

- **`circle.yml`** — last touched `2016-07-15` (`git log -1 -- circle.yml`); wires up
  `karalabe/hive` (the pre-2019 hive fork, long superseded by `ethereum/hive`) for Docker-based
  simulation testing. Ten years stale.
- **`.travis.yml`** — last content-relevant touch is a 2024-06-05 bulk "update to go version
  1.22.1" commit (`dd2800c12`) that mechanically swept many unrelated files in the same commit,
  not a deliberate Travis CI maintenance action; the file otherwise still targets
  `go_import_path: github.com/ethereum/go-ethereum` and pre-GitHub-Actions build stages.
- **`appveyor.yml`** — most recent touch is the vendored clone's own May 2026 org-rename sweep
  (`19568d986`, `etclabscore/core-geth` → `ethereumclassic/core-geth` in the `deploy.repository`
  field) — a mechanical find/replace across the whole tree, not evidence of active AppVeyor
  usage. The build logic itself (MSYS2/mingw64 Windows build, NSIS installer) has no current
  GitHub Actions equivalent, but the file isn't wired to anything live.
- **`Jenkinsfile`** — references `meowsbits-github-jenkins` credentials and a
  `Mordor Regression`/`Goerli Regression` GitHub status-check naming scheme from an
  externally-hosted Jenkins instance; no corresponding GitHub Actions job reproduces its
  "assert import of canonical chain data" regression check.

None of the four are referenced by badges in `README.md` (no `travis`/`circleci`/`appveyor`/
`jenkins` matches), and none has a matching `.circleci/` directory or other live wiring. These
are archaeological artifacts of two CI-tooling migrations (Travis/CircleCI/AppVeyor/Jenkins →
GitHub Actions) that were never deleted — worth noting as "this is what abandoned CI config
looks like after a migration," not as a pattern to emulate.

---

## Benchmark CI (`bench-core.yml` / `bench-trie.yml` / `bench-vm.yml`)

These three workflows are core-geth's only CI benchmarking, and they turn out to be exactly
the "simple `go test -bench` wrapper" end of the spectrum the task asked to distinguish —
**much lighter-weight than Nethermind's `run-expb-reproducible-benchmarks.yml`** (a
2,336-line ChatOps-triggered pipeline against a self-hosted runner replaying real mainnet
block payloads via an external `expb` tool) **or Reth's `bench.yml`** (1,850 lines, ChatOps
comment parsing, disk-snapshot restore via `schelk`, ABBA-ordered run pairs to cancel thermal
drift, a Python summary script with bootstrap confidence intervals). There is no
self-hosted runner, no ChatOps trigger, no disk-snapshot machinery, no statistical run-ordering
— each workflow is three `ubuntu-latest` jobs on a plain `push: master` / manual-dispatch
trigger:

1. **`bench_core_geth`** — checks out this repo, runs a targeted `go test -bench=.` against
   the package under test with `-short -count 1 -p 1 -timeout 60m`, tees output to a `.txt`
   file, uploads it as an artifact.
2. **`bench_go_ethereum`** — checks out a *pinned upstream go-ethereum tag*
   (`ethereum/go-ethereum` at `v1.10.26` for core/trie; `v1.11.5` for VM, fetched via a second
   git remote) and runs the identical `go test -bench=.` invocation against upstream's
   unmodified code, uploading its own artifact.
3. **`compare`** — downloads both artifacts, installs `golang.org/x/perf/cmd/benchstat`, and
   runs `benchstat go-ethereum.txt core-geth.txt` to print a statistical comparison. (Both
   workflows print the same `benchstat` invocation twice, under "Analyze Results [COMPRESSED]"
   and "Analyze Results [RAW DELTA]" step names — for `bench-core.yml`/`bench-trie.yml` these
   two steps are byte-identical duplicates; only `bench-vm.yml` actually differentiates them.)

What each workflow actually exercises:

- **`bench-core.yml`** (96 lines) — `go test -short ./core -bench=.` — Go's package-level
  benchmark functions under `core/` (block/state processing internals; the specific
  `Benchmark*` functions live in `core/*_test.go`, not enumerated by this workflow itself
  since `-bench=.` matches everything in the package).
- **`bench-trie.yml`** (95 lines) — identical shape, targeted at `./trie` — Merkle Patricia
  trie operation benchmarks.
- **`bench-vm.yml`** (106 lines) — targeted at `./tests -bench=VM` (note: package `tests`, not
  `core/vm`, and the bench filter is `VM` not `.`) with `submodules: recursive` checked out for
  both the core-geth and go-ethereum legs — EVM opcode-level execution benchmarks driven by
  the shared `tests/` fixture submodule. This workflow also does something the other two
  don't: after checking out upstream go-ethereum at `v1.11.5`, it explicitly
  `git checkout $GITHUB_SHA -- tests/vm_bench_test.go` (`bench-vm.yml:57`) — overwriting
  upstream's own VM benchmark test file with *this branch's* version before running it, so
  both legs of the comparison run literally the same benchmark code against two different
  EVM implementations. It also pipes both benchmark outputs through a small local script,
  `build/bench-suite-compress.sh` (24 lines), before the "COMPRESSED" `benchstat` step — the
  script strips the per-test-case filename prefix from each `Benchmark.../<file>.json-N` line
  (e.g. collapsing `BenchmarkVM/vmArithmeticTest/addmod1_overflow2.json-12` down to
  `BenchmarkVM/vmArithmeticTest`) so `benchstat` treats many individual fixture-file benchmarks
  as repeated samples of one logical benchmark, "which yields a more generalized statistic
  including almost believable p values" (per the script's own header comment). `bench-core.yml`
  and `bench-trie.yml` have no equivalent compression step — their "COMPRESSED" step name is
  vestigial copy-paste from a shared workflow template, not a real distinction from "RAW DELTA"
  in those two files.

None of the three workflows gate merges (no `pull_request` trigger — only `push: master` and
manual dispatch), so they function as ongoing trend/regression visibility against upstream
go-ethereum rather than a PR check.

---

## Fukuii verdict summary table

| Finding | Verdict | Reasoning |
|---|---|---|
| `test` / `test-coregeth` Makefile split (shared-upstream vs fork-specific test surface) as a *convention* | **Port now** | fukuii already has an analogous instinct (`sbt compile` vs `compile-all`, tagged `testOnly`/alias commands in `build.sbt`) but nothing that names "does this pass what upstream/core-geth-equivalent tests expect" vs "does this pass fukuii-only ETC/ETH consensus-equivalence checks" as two separately invokable surfaces. Since fukuii tracks two consensus families (PoW/ETC and PoS/ETH) rather than one fork-vs-upstream split, the directly portable piece is the *naming convention* — e.g. a `testEthereum`-vs-`testFukuiiSpecific`-shaped alias distinction already partially exists per `build.sbt`'s alias list (`testConsensus`, `testOlympia`, etc.) and could be tightened to explicitly separate "shared Ethereum-family behavior" from "fukuii/ECIP-only" checks, mirroring this doc's Makefile pattern rather than the specific Go commands. |
| mkdocs as the doc-site tool | **Already same choice** | fukuii's own root `mkdocs.yml` already uses mkdocs; no gap, no action. |
| `docs-deploy.yml`'s path-scoped push trigger (`mkdocs.yml`, `docs/**`, `requirements-mkdocs.txt`) | **Port now** (low-cost) | Simple, low-risk pattern to reuse if/when fukuii wires up its own doc-site auto-deploy — avoids redeploying docs on unrelated commits. |
| Legacy CI configs (`circle.yml`, `.travis.yml`, `appveyor.yml`, `Jenkinsfile`) | **Not portable** | Confirmed vestigial (stale 2016–2024 touches, no README badges, no live wiring) — explicitly flagged as "don't copy, it's cruft from an earlier CI-migration," not a pattern. |
| `go-generate-check.yml` (drift check: run `go generate`, diff, fail if dirty) | **Port now** (if fukuii ever adds codegen) | Simple, generically useful CI shape for any generated-code drift; not currently applicable since fukuii has no `go generate`-equivalent step today, but the pattern (generate → diff → fail) is directly reusable for any Scala codegen fukuii might add. |
| `audit-bootnodes.yml` (daily cron pings bootnodes, auto-PRs removal of dead ones) | **Needs design** | Directly relevant — fukuii maintains its own bootnode lists for ETC/Mordor/ETH/Sepolia — but requires a fukuii-side devp2p ping utility equivalent to `devp2p discv4 ping` and a decision on auto-PR-vs-manual-review for config changes; not a drop-in copy since it's Go-tool-specific. |
| Benchmark CI (`bench-core.yml`/`bench-trie.yml`/`bench-vm.yml`) as a *shape* — plain `go test -bench` + pinned-upstream-comparison + `benchstat` + artifact upload, no self-hosted runner | **Port now** (as a starting point, not a destination) | This is a genuinely lighter-weight benchmark-CI pattern than Nethermind's `expb`-based pipeline or Reth's `bench.yml`/`bench-reth-summary.py`, both already flagged in their own docs as multi-week infrastructure projects fukuii hasn't undertaken. core-geth's version needs only: (a) a fukuii JMH- or sbt-`Benchmark`-config-driven equivalent of `go test -bench`, (b) a pinned "upstream" comparison target (fukuii's analogue would be comparing a branch against a pinned prior fukuii release, or against core-geth/Besu on identical fixtures, not "upstream" in the fork sense), (c) a `benchstat`-equivalent statistical diff tool for whatever benchmark output format fukuii's `Benchmark` sbt config produces. All three are meaningfully smaller lifts than expb/schelk/self-hosted-runner infrastructure — this is the more realistic first benchmark-CI fukuii could stand up, even though it will show noisier results than the heavier pipelines. |
| `bench-vm.yml`'s cross-checkout trick (overwrite upstream's benchmark test file with the current branch's version before running, so both legs execute identical benchmark code) | **Port now** (if a comparison benchmark is built) | Small, clever, directly reusable technique independent of the rest of the pipeline: ensures a fair A/B by holding the benchmark *harness* constant while only the implementation under test differs. |
| `bench-suite-compress.sh`'s per-fixture-file result collapsing before `benchstat` | **Needs design** | Useful only if fukuii's benchmark output is similarly one-line-per-fixture-file; worth remembering as a technique (collapse many fixture-level results into one logical benchmark name before statistical comparison) rather than a literal script to copy. |
