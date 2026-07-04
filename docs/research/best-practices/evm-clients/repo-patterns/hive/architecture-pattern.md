# Hive — Cross-Client Interop Testing Framework

Source: `.claude/repo-references/hive/` (vendored full clone, verified genuine — `.git/`
present, `module github.com/ethereum/hive` at `go.mod:1`, `go 1.24.0` at `go.mod:3`) — this
is shared testing infrastructure, not a peer EVM client, but documented with the same
rigor since fukuii already integrates with it extensively (13 `hive-*.yml` workflows plus
one reusable `_hive-sim.yml`, and a dedicated adapter at `hive/fukuii/`) and several
reference clients (Erigon, Nethermind, Besu) reference Hive-based testing in their own
tooling. Every claim below is traceable to a file in the vendored clone or in fukuii's own
`hive/` and `.github/workflows/` directories.

---

## What Hive is and how it's architected

**The one-sentence definition, from the source itself:** "Hive is a system for running
integration tests against Ethereum clients" (`README.md:3`, repeated verbatim at
`docs/overview.md:5`). In Hive's own vocabulary, an integration test is a **simulation**,
"controlled by a program (the 'simulator') written in any language" that "launches clients
and contains test logic," reporting results back to a central controller for display
(`docs/overview.md:7-10`).

**Three architectural layers, all Docker containers orchestrated by one host process:**

1. **The `hive` host process** (`hive.go`, a `package main` Go binary built at the repo
   root — `hive.go:1`) is the orchestrator. It never runs test logic itself; it discovers
   client and simulator definitions from the filesystem, builds their Docker images, starts
   an HTTP API server, launches the simulator container, and answers that container's API
   calls to spin up/tear down client containers on demand.
2. **Simulator containers** — one Docker image per test suite, built from
   `simulators/<name>/Dockerfile`. A simulator is "a program written against the HTTP-based
   simulation API provided by hive... Simulators can be written in any programming language
   as long as they are packaged using docker" (`docs/simulators.md:7-9`).
3. **Client containers** — one Docker image per client under test, built from
   `clients/<name>/Dockerfile`. "While the simulator build must always work without error,
   it's OK for some client builds to fail as long as one of them succeeds. This is because
   client code pulled from the respective upstream repositories may occasionally fail to
   build" (`docs/overview.md:81-83`) — client-build fragility is treated as an expected,
   tolerated failure mode, not a hard stop for the whole run.

**The build-then-run sequence** (`docs/overview.md:70-109`, cross-checked against the
actual `main()` in `hive.go`):

- The user invokes `./hive --sim <name> --client <list>` (`docs/overview.md:73-75`).
- Hive loads an `Inventory` of every client/simulator directory on disk
  (`libhive.LoadInventory(".")`, `hive.go:115`; implemented by
  `internal/libhive/inventory.go:84-93`, which walks `clients/` and `simulators/` looking
  for `Dockerfile`), matches the `--sim` regex against the simulator set
  (`inv.MatchSimulators(*simPattern)`, `hive.go:119`; the match is anchored with a trailing
  `$` — `inventory.go:69`), and parses the `--client` list into `ClientDesignator` values,
  validating each against the inventory (`ParseClientList` → `validateClients`,
  `inventory.go:239-252, 315-366`) — an unknown client name is a hard `fatal("unknown
  client %q")` (`inventory.go:325`) before any Docker build is attempted.
- `runner.Build(ctx, clientList, simList, simBuildArgs)` (`hive.go:241`) builds every client
  and simulator image via `libdocker.Builder` — `BuildClientImage` tags
  `hive/clients/<name>:latest` from `clients/<name>/<Dockerfile-variant>`
  (`internal/libdocker/builder.go:41-48`); `BuildSimulatorImage` tags
  `hive/simulators/<name>:latest` from `simulators/<name>/Dockerfile`
  (`builder.go:50-71`) — with one build-context override: a `hive_context.txt` file inside
  a simulator directory can redirect the Docker build context to a parent path (used by
  `eth2/dencun`, `eth2/engine`, `eth2/testnet`, and `lean` to share Go modules across
  simulator directories via a local `go.work` — `builder.go:56-67`, documented in
  `docs/simulators.md:40-94`).
- For each matched simulator, `runner.Run(ctx, sim, env, hiveInfo)` (`hive.go:252`) starts an
  HTTP API server bound to a random local port, launches the simulator container with
  `HIVE_SIMULATOR=http://<api-addr>` injected (`internal/libhive/run.go:151, 214`), plus
  `HIVE_PARALLELISM`, `HIVE_LOGLEVEL`, `HIVE_TEST_PATTERN`, `HIVE_RANDOM_SEED`
  (`run.go:215-218`, matching the table at `docs/simulators.md:100-106`), and blocks until
  the simulator container exits.
- The simulation API itself (`internal/libhive/api.go`) is a `gorilla/mux` router with 16
  routes (`api.go:34-53`): suite/test lifecycle (`POST /testsuite`,
  `POST /testsuite/{suite}/test`, `POST .../test/{test}` to end a test with a result,
  `DELETE /testsuite/{suite}` to end the suite), client lifecycle (`POST .../node` to
  start, `DELETE .../node/{node}` to stop, plus `pause`/`unpause`/`exec`/`register` for
  finer control not covered in the published docs), and network management (create/remove
  a Docker network, connect/disconnect a container, query its IP) — this is the same
  surface documented prose-style in `docs/simulators.md:236-570`, confirmed to match the
  live router rather than being aspirational documentation.
- On simulator container exit, hive tallies `failCount` across all suites run
  (`hive.go:250-257`) and exits non-zero if any test failed (`hive.go:260-266`) — the
  overall process exit code is the top-level pass/fail signal a CI job checks (though, as
  noted in fukuii's own `_hive-sim.yml`, many callers intentionally ignore hive's raw exit
  code and instead parse the structured JSON results — see the Local invocation section).

**Client container lifecycle, from the simulator's point of view**
(`docs/clients.md:61-79`): "The simulator can customize the container by passing
environment variables with prefix `HIVE_`. It may also upload files into the container
before it starts. Once the container is created, hive simply runs the entry point defined
in the `Dockerfile`." Readiness is polled: "hive waits for TCP port 8545 to open before
considering the client ready for use by the simulator" — configurable via
`HIVE_CHECK_LIVE_PORT` (0 disables the check), with a timeout enforced by
`--client.checktimelimit` (default 3 minutes, `docs/commandline.md:112-115`).

**Results are written to a result directory** (default `./workspace/logs`,
`docs/overview.md:106`) as one JSON file per test suite — `id`, `name`, `description`,
`clientVersions`, `simLog`, and a `testCases` map keyed by test-case ID, each with
`summaryResult.pass` (`docs/overview.md:113-147`). This JSON schema is what fukuii's own
`_hive-sim.yml` tabulation step parses (see the Local invocation section) rather than
scraping simulator stdout, because "the simulator stdout log format varies wildly between
simulators" (`_hive-sim.yml:301-303`, a comment fukuii's own CI author already discovered
independently — it matches Hive's own documented model exactly).

---

## Simulator catalog (`simulators/`)

Six top-level families exist in the vendored tree: `devp2p`, `eth2`, `ethereum`, `lean`,
`portal`, `smoke`. fukuii is an execution-layer (EL) client only, so `eth2` (consensus-layer
client interop: `dencun`, `engine`, `testnet`, `withdrawals`, plus shared `common` code),
`lean` (the lean-consensus / "lean Ethereum" experimental spec client track, Rust,
`Cargo.toml`-based), and `portal` (Portal Network light-client protocol, also Rust) are
out of scope by design, not gaps — no `hive-*.yml` workflow references them and none
should be added unless fukuii ever ships a CL, lean, or Portal component.

### `ethereum/` — the family fukuii actually exercises

| Simulator dir | What it tests | Representative file read |
|---|---|---|
| `ethereum/consensus` | Runs the classic Ethereum-1 state-test/blockchain-test JSON fixtures (EF "consensus tests") against every client, "to ensure that none are skipped" even if a client's own CI runs a stale copy (`docs/overview.md:45-48`) | `main.go` gates each client on `client.HasRole("eth1")` before running (`simulators/ethereum/consensus/main.go:107`) |
| `ethereum/engine` | Engine API (`engine_newPayloadVX`, `engine_forkchoiceUpdatedVX`, `engine_exchangeCapabilities`, ...) spec-compliance — "the test suite 'pretends' to be a consensus client" (`docs/overview.md:59-61`) | Large Go module (30+ files: `client/engine.go`, `clmock/clmock.go`, `config/fork.go`, `types/` for blob/withdrawal payload encoding, embedded KZG trusted-setup data at `helper/trusted_setup.json`) |
| `ethereum/graphql` | Loads a fixed test chain, enables the GraphQL endpoint, and diffs query responses against 51 golden `testcases/*.json` files (`ls simulators/ethereum/graphql/testcases` — 51 entries, `01_eth_blockNumber.json` through `51_eth_getBlock_4844.json`) | `graphql.go`, `graphql_test.go` |
| `ethereum/rpc-compat` | "Conformance tests against all available clients... to ensure clients return the same values over JSON-RPC so that they may be treated as a black box by downstream tooling" (`simulators/ethereum/rpc-compat/README.md:3-6`); tests come from the separate `ethereum/execution-apis` repo's `tests/` directory, cloned at Docker-build time | `main.go`, `testload.go` — no local fixture data checked into this repo |
| `ethereum/sync` | Cross-client blockchain sync — one "source" client per implementation loads a fixed `chain/chain.rlp`, every "sink" client (including itself) attempts to sync from it (`docs/overview.md:39-43`) | `sync.go` — see role-gating detail below |
| `ethereum/eels/{consume-engine,consume-enginex,consume-rlp,consume-sync,execute-blobs}` | Five sibling Dockerfile-only simulators, all thin wrappers around Python `execution-specs` (EELS) test tooling — they `git clone` `ethereum/execution-specs`, `uv sync`, then invoke that repo's own `consume engine`/`consume rlp`/etc. CLI verbs against fixture tarballs (`fixtures_stable.tar.gz` or similar, resolved via a `fixtures` build-arg) | `eels/consume-engine/Dockerfile:1-34`, `eels/consume-rlp/Dockerfile:1-30` — both read in full; near-identical apart from the `consume engine` vs `consume rlp` entrypoint verb and a `disable_strict_exception_matching` build-arg unique to `consume-engine` |
| `ethereum/pyspec` | **Does not exist in the vendored clone.** Removed upstream by commit `7b0ce986` — "`simulators/ethereum: add eest consume engine/rlp and remove pyspec (#1178)`" (`git log --oneline -- simulators/ethereum/pyspec`) — the modern replacement is exactly the `eels/consume-engine` + `eels/consume-rlp` pair above. **fukuii's `hive-pyspec.yml` still targets the deleted `ethereum/pyspec` path** — see the fukuii-integration section below; this is a real, actionable bug, not a naming quibble. |

### `devp2p` and `smoke`

- **`devp2p`** (`simulators/devp2p/main.go`) runs 'eth', 'snap', and 'discv4' wire-protocol
  conformance suites maintained upstream in go-ethereum, adapted into Hive by launching the
  client against a known chain (`init/genesis.json`, `init/fullchain.rlp`,
  `init/halfchain.rlp`) and sending raw protocol messages at its `enode://` endpoint
  (`docs/overview.md:32-37`). All four `hivesim.Suite` entries in `main.go` declare
  `Role: "eth1"` (`main.go:25,46,96,116`).
- **`smoke/{clique,genesis,network}`** are Hive's own self-test suites, not Ethereum
  protocol tests: `smoke/genesis` verifies genesis-state edge cases (5 genesis fixture
  variants: empty, non-empty, precompile-with-empty-code, precompile-with-storage,
  precompile-with-zero-balance — `simulators/smoke/genesis/genesis-*.json`), `smoke/clique`
  exercises Clique PoA block production, and `smoke/network` is explicitly a **test of
  Hive's own simulation API**, not of any client behavior — its README states outright:
  "The network API smoke test ensures that the following hive network endpoints are
  working as intended: create/remove network, connect/disconnect container to/from
  network, get IP address of container on network" (`simulators/smoke/network/README.md:3-14`),
  i.e. it is Hive testing itself, with client containers as incidental test fixtures.

### Role-gated simulators: `eth1` vs `eth1_snap`

`docs/clients.md:35-48` explains the mechanism generically: `hive.yaml` in a client
directory declares a `roles:` list; "if `hive.yaml` is missing or doesn't declare roles,
the `eth1` role is assumed" — matching the actual Go default,
`Roles: []string{"eth1"}` (`internal/libhive/inventory.go:138`). Roles are **freeform
strings with no fixed enum** — `ClientMetadata.Roles` is just `[]string` decoded from YAML
(`inventory.go:167-180`); nothing in `libhive` validates role names against a known set.
Only individual simulators choose to filter on a role string via
`hivesim.Client.HasRole(...)` or `sim.ClientsWithRole(...)`.

`ethereum/sync/sync.go` is the concrete example fukuii must understand precisely, since
`hive-sync.yml` is one of its 13 workflows. It defines **two independent suites**:

- A `"snapsync"` suite, gated entirely on role `"eth1_snap"` — both the *source*
  `ClientTestSpec.Role` and the `RunAllClients` *sink* role passed into `runSourceTest` are
  `"eth1_snap"` (`sync.go:37-56`), and the whole suite is skipped unless
  `sim.ClientsWithRole("eth1_snap")` returns at least one client (`sync.go:54-56`).
- A `"sync"` (full-sync) suite, always run, gated on role `"eth1"` for both source and sink
  (`sync.go:60-76`).

Because a client's role list determines **both** which suite includes it as a source and
which suite includes it as a sink (`runSourceTest`'s `role` parameter is reused for
`t.RunAllClients(hivesim.ClientTestSpec{Role: role, ...})`, `sync.go:100-107`), a client
that does not declare `eth1_snap` is entirely absent from the snap-sync suite — it neither
serves as a snap source nor is snap-synced against as a sink. It still fully participates
in the plain full-sync suite via its default `eth1` role. `docs/clients.md:146-153`
documents the "snap sync roles" section generically: "Execution-layer clients with an
implementation of the [snap] protocol should support the `eth1_snap` role," with the single
env var `HIVE_NODETYPE=snap` forcing snap-sync mode.

---

## Client integration contract (`clients/`)

`clients/` holds 32 subdirectories (`ls clients/`), spanning EL clients (`go-ethereum`,
`besu`, `nethermind`, `erigon`, `reth`, `ethereumjs`, `ethrex`, `gean`, `samba`,
`nimbus-el`, ...) and a long tail of CL/lean/portal client wrappers. Three representative
EL clients were read in full — `go-ethereum` (the canonical reference the docs cite
directly, `docs/clients.md:11-12`) and `besu` (a JVM client, the closest architectural
analog to fukuii among Hive-supported clients) — to extract the exact contract, plus a
directory listing of `nethermind`'s files for comparison.

### The five files every EL client directory provides

| File | go-ethereum | besu | Purpose |
|---|---|---|---|
| `Dockerfile` (+ `Dockerfile.git`, `Dockerfile.local`) | `clients/go-ethereum/Dockerfile:1-29` | `clients/besu/Dockerfile:1-27` | Primary build recipe. Both wrap a **pre-built image**, not a from-source build: geth does `FROM $baseimage:$tag as builder` where `baseimage` defaults to `ethereum/client-go` (`Dockerfile:1-4`); besu does the same against `hyperledger/besu:develop` (`Dockerfile:3-4`). `Dockerfile.git` (present for both, not read in full) is the from-source alternative selected via `--client-file`'s `dockerfile: git` (`docs/commandline.md:52-53, 62-63`). |
| `hive.yaml` | `roles: ["eth1", "eth1_snap"]` (`clients/go-ethereum/hive.yaml:1-3`) | identical: `["eth1", "eth1_snap"]` (`clients/besu/hive.yaml:1-3`) | Declares protocol-role support (see above). |
| `mapper.jq` | 87 lines (`clients/go-ethereum/mapper.jq`) | not read in full, but same purpose per `docs/clients.md:99-102` | Converts the geth-format `/genesis.json` Hive uploads into the target client's native genesis format. **This is the single largest per-client artifact** — geth's own mapper is 87 lines encoding the *entire* fork-activation schedule (16 block-number forks, 6 timestamp forks including `osaka`/`amsterdam`, and a `blobSchedule` map with 9 named phases: `cancun` through `bpo5`, each with `target`/`max`/`baseFeeUpdateFraction` sub-fields defaulted inline via `jq`'s `//` operator when the corresponding `HIVE_*_BLOB_*` env var is absent — `mapper.jq:32-116`). |
| `<client>.sh` (`geth.sh` / `besu.sh`) | 166 lines | 187 lines | Entry point: env-var-to-flag translation, genesis load (`init`), optional `chain.rlp`/`blocks/` import, RPC/Engine-API/mining wiring, then exec the client. |
| `enode.sh` (in `/hive-bin/`) | 17 lines, single `curl` + `jq -r '.result.enode'` against `admin_nodeInfo`, no retry | 22 lines — **adds a retry loop**: polls up to 50×100ms until the response contains the string `enode` (`clients/besu/enode.sh:13-18`) before parsing | Returns the running instance's `enode://` URL; invoked by simulators through the `/hive-bin/enode.sh` convention (`docs/clients.md:56-59, 113-116`). |

### The genesis-translation contract in detail (`mapper.jq`)

The uploaded `/genesis.json` is geth-format (`docs/clients.md:93-94`: "contains Ethereum
genesis state in the JSON format used by Geth... mandatory"). go-ethereum's own
`mapper.jq` is therefore close to an identity transform on the `config` object, built
entirely from `env.HIVE_*` values with two small jq helpers: `remove_empty` (strips
null/empty-string/empty-array/empty-key entries so partially-configured forks don't
pollute the output — `mapper.jq:1-17`) and `to_int`/`to_bool` (string→typed conversions,
`mapper.jq:19-29`). Every fork env var from `docs/clients.md`'s table maps 1:1 to a
genesis `config` key: `HIVE_FORK_HOMESTEAD` → `homesteadBlock`,
`HIVE_TERMINAL_TOTAL_DIFFICULTY` → `terminalTotalDifficulty` (defaulted to
`9223372036854775807` — i.e. `math.MaxInt64` — when unset, `mapper.jq:56`, so an
unspecified TTD reads as "never merges" rather than "merged at genesis"), and the blob
schedule sub-object per fork (`mapper.jq:63-109`).

geth's own entry script then runs this transform in-place: `mv /genesis.json
/genesis-input.json; jq -f /mapper.jq /genesis-input.json > /genesis.json` (`geth.sh:82-83`)
before calling `geth ... init /genesis.json` (`geth.sh:96`). Besu's `besu.sh` does the
identical two-line dance (`besu.sh:67-68`) before passing `--genesis-file=/genesis.json`.
**This mv-then-transform-in-place idiom is the de facto standard pattern** any new client
adapter is expected to follow, not merely one option among several.

### The full `HIVE_*` environment-variable contract for `eth1` clients

Reproduced from `docs/clients.md:119-144` (the canonical, must-support list) — 19
variables in six groups: identity/logging (`HIVE_LOGLEVEL`, `HIVE_NODETYPE`,
`HIVE_BOOTNODE`), feature toggles (`HIVE_GRAPHQL_ENABLED`), mining
(`HIVE_MINER`, `HIVE_MINER_EXTRA`), Clique PoA (`HIVE_CLIQUE_PERIOD`,
`HIVE_CLIQUE_PRIVATEKEY`), chain identity (`HIVE_NETWORK_ID`, `HIVE_CHAIN_ID`), and 11
block-number fork-transition variables from `HIVE_FORK_HOMESTEAD` through
`HIVE_FORK_LONDON`. A separate, additive snap-sync section
(`docs/clients.md:146-153`) adds `HIVE_NODETYPE=snap` as the trigger for the `eth1_snap`
role. Neither table includes the newer timestamp-fork variables
(`HIVE_SHANGHAI_TIMESTAMP`, `HIVE_CANCUN_TIMESTAMP`, `HIVE_PRAGUE_TIMESTAMP`,
`HIVE_OSAKA_TIMESTAMP`, `HIVE_AMSTERDAM_TIMESTAMP`) or the blob-schedule variables — those
are documented only implicitly, by their presence in `go-ethereum`'s own `mapper.jq`, which
means `docs/clients.md` has fallen behind the actual, load-bearing env-var surface that
Hive's newest simulators (`ethereum/engine`, `eels/consume-engine`) exercise. A client
author reading only the prose docs would miss `HIVE_OSAKA_TIMESTAMP` entirely and would
need to read `go-ethereum`'s `mapper.jq` as the real spec.

### Files and scripts, restated precisely

- `/genesis.json` — mandatory, geth-format (`docs/clients.md:93-94`).
- `/chain.rlp` — optional, RLP-encoded blocks to import before startup
  (`docs/clients.md:95`); both geth (`geth.sh:103-107`) and besu
  (`besu.sh:92-96`) treat its absence as a soft warning, not a failure.
- `/blocks/` — optional directory of individually numbered `.rlp` files, imported in
  filename order after `/chain.rlp` (`docs/clients.md:96, 104-109`); "the reason for
  requiring two different block sources is that specifying a single chain is more optimal,
  but tests requiring forking chains cannot create a single chain" (`docs/clients.md:106-107`).
  Besu's script has a specific documented quirk here: because Besu's block-import CLI exits
  if *only one* file is given and that file fails, `besu.sh` appends a literal non-existent
  `dummy` filename to the import list as a workaround (`besu.sh:99-108`, citing upstream
  issue `besu-eth/besu#1992`).
- `/hive-bin/enode.sh` — mandatory for all `eth1` clients (`docs/clients.md:113-115`).
- `/version.txt` — must be generated at build time (`docs/clients.md:50-54`); both
  reference clients generate it via a one-line `RUN` invoking the client's own
  `--version`/console command and redirecting to the file (`Dockerfile:11` for geth,
  `Dockerfile:13` for besu).

---

## fukuii's own integration — contract-compliance check

fukuii's adapter lives at `hive/fukuii/` in the fukuii repo (copied into
`external/hive/clients/fukuii/` at CI time — see Local invocation) and consists of exactly
the same five-file shape as the reference clients: `Dockerfile`, `hive.yaml`, `mapper.jq`,
`enode.sh`, and one entry script (`fukuii.sh`, playing the `geth.sh`/`besu.sh` role).

### Dockerfile and hive.yaml — compliant, with one deliberate deviation

`hive/fukuii/Dockerfile:5` does `FROM chipprbots/fukuii:latest` — matching the "wrap a
pre-built image" pattern of geth/besu, **except** fukuii has no published Docker Hub image;
`chipprbots/fukuii:latest` is instead built locally by CI immediately beforehand (the "Tag
base Docker image" step present in every `hive-*.yml` — e.g. `hive-osaka.yml`'s `Tag base
Docker image` step, and identically in `_hive-sim.yml:165-179`). This is a reasonable
adaptation given fukuii isn't yet on a public registry, but it means fukuii's Hive adapter
**cannot be used standalone** by someone who clones `ethereum/hive` directly without also
separately building and tagging `chipprbots/fukuii:latest` first — exactly what fukuii's own
`hive/test-local.sh:18` does (`docker build -t fukuii-hive:test -f "$ADAPTER_DIR/Dockerfile"
"$ADAPTER_DIR"` — note this even uses a *different* tag, `fukuii-hive:test`, than the
Dockerfile's `FROM chipprbots/fukuii:latest` expects, so `test-local.sh` only works if
`chipprbots/fukuii:latest` was already built by some other means; the script doesn't build
it). fukuii has no `Dockerfile.git` alternative, unlike both reference clients — reasonable,
since fukuii's own repo already contains the source and the `sbt assembly` step in CI plays
that role.

`hive/fukuii/hive.yaml:1-3` declares `roles: ["eth1", "eth1_engine"]`. Cross-checked against
`internal/libhive/inventory.go` and every simulator's role-check call sites (grepped across
`internal/` and `simulators/`): **no simulator in the vendored tree ever calls
`HasRole("eth1_engine")` or `ClientsWithRole("eth1_engine")`** — the only role strings any
simulator actually filters on are `"eth1"` (default, checked explicitly in
`ethereum/consensus/main.go:107`, `ethereum/rpc-compat/main.go:47`,
`ethereum/graphql/graphql.go:34`, `devp2p/main.go`, `smoke/genesis/main.go`,
`smoke/clique/clique.go`) and `"eth1_snap"` (`ethereum/sync/sync.go:43,50,54,66,73`).
`eth1_engine` is therefore **inert metadata today** — it does not grant fukuii inclusion in
any role-gated suite, since none exists for it; it neither helps nor hurts. The absence of
`eth1_snap`, by contrast, is consequential: fukuii is excluded from `ethereum/sync`'s
snapsync suite entirely (as source and as sink) per the role semantics above, even though
fukuii's own `AGENTS.md`/`CLAUDE.md` and `.claude/agent-protocols/storage-rocksdb.md`
describe a working SNAP-sync implementation. Declaring `eth1_snap` and setting
`HIVE_NODETYPE=snap` handling (see below) is a genuine, scoped opportunity.

### mapper.jq — architecturally different, not non-compliant

fukuii's `mapper.jq` (`hive/fukuii/mapper.jq:1-25`) does **not** build a `config` object at
all — it passes through only header/state fields (`difficulty`, `gasLimit`, `nonce`,
`timestamp`, `coinbase`, `mixHash`, `extraData`, `gasUsed`, `parentHash`,
`baseFeePerGas`, `excessBlobGas`, `blobGasUsed`, `alloc`) with sensible defaults for a
handful of them (`mapper.jq:6-12`), then strips null-valued keys
(`with_entries(select(.value != null))`, `mapper.jq:25`). Fork-schedule translation happens
nowhere in the genesis file: `fukuii.sh` reads the same `HIVE_FORK_*`/`HIVE_*_TIMESTAMP` env
vars directly and emits them as `-Dfukuii.blockchains.hive.*-block-number=...`/
`*-timestamp=...` JVM system properties (`fukuii.sh:69-98`) rather than folding them into
the genesis JSON's `config` block the way geth/besu do. This is a legitimate,
internally-consistent architectural choice (fukuii's HOCON network-config system separates
"genesis state" from "fork schedule" as two different config surfaces, unlike geth's single
JSON document that carries both) — Hive's contract only requires that the documented env
vars produce the documented effect, not that any particular file carries the translation.
**Compliant via a different mechanism, no action needed.**

One curiosity worth flagging rather than silently correcting: `fukuii.sh:81` maps
`HIVE_FORK_LONDON` (Hive's vanilla-Ethereum vocabulary — the last block-number-indexed fork
in the documented table, `docs/clients.md:144`) directly onto
`-Dfukuii.blockchains.hive.olympia-block-number` — i.e., for the purposes of Hive's "hive"
test network profile, fukuii's own Olympia (ECIP-1111/1112/1121) fork slot stands in for
where upstream would put London. This is a deliberate placeholder for a fork Hive's env-var
vocabulary has no name for, not a mislabeling bug, but it means the "hive" network profile
is not a faithful stand-in for testing Olympia-specific ECIP behavior — it borrows the slot
only to give fukuii *some* block-number fork to bind the last documented `HIVE_FORK_*`
variable to.

### fukuii.sh — the environment-variable compliance matrix

Checked every documented `HIVE_*` variable (`docs/clients.md:119-153`, plus the
`mapper.jq`-only timestamp/blob variables) against `hive/fukuii/fukuii.sh` line-by-line:

| Variable | fukuii handling | Citation |
|---|---|---|
| `HIVE_FORK_HOMESTEAD` … `HIVE_FORK_LONDON` (11 vars) | Mapped 1:1 to `-D...*-block-number` properties, defaulted to a `$MAX` sentinel (`10^18`) when unset so an unconfigured fork reads as "never activates" | `fukuii.sh:18-28, 70-81` |
| `HIVE_NETWORK_ID`, `HIVE_CHAIN_ID` | Mapped directly | `fukuii.sh:30-31, 61-62` |
| `HIVE_TERMINAL_TOTAL_DIFFICULTY` | Mapped **conditionally** — only emitted if it differs from the `$MAX` sentinel, specifically to avoid wedging fukuii's `SNAPSyncController` into "wait for the CL" mode on pre-merge sims that have no CL at all; documented inline with a direct reference to a GitHub issue (`#1208`) | `fukuii.sh:32, 84-92` |
| `HIVE_SHANGHAI_TIMESTAMP`, `HIVE_CANCUN_TIMESTAMP`, `HIVE_PRAGUE_TIMESTAMP`, `HIVE_OSAKA_TIMESTAMP` | Mapped, only when non-empty | `fukuii.sh:33-36, 95-98` |
| `HIVE_BOOTNODE` | Written to `static-nodes.json` in the datadir (HOCON can't express arrays via `-D` overrides) | `fukuii.sh:148-152` |
| `HIVE_MINER` | Enables mining, sets coinbase | `fukuii.sh:155-158` |
| `HIVE_LOGLEVEL` | **Not handled anywhere in the script** — documented as a must-support var (`docs/clients.md:124`); fukuii's own log level is fixed regardless of what the simulator requests | absence confirmed by full-file read and targeted grep |
| `HIVE_NODETYPE` (`archive`/`full`/`snap`) | **Not handled** — no case statement branches on it; fukuii always syncs however its default "hive" network profile is configured, silently ignoring the requested mode rather than erroring on an unsupported value the way geth (`geth.sh:69-79`) and besu (`besu.sh:138-146`) both explicitly do (`exit 1` on an unrecognized value) | absence confirmed |
| `HIVE_MINER_EXTRA` | Not handled | absence confirmed |
| `HIVE_CLIQUE_PERIOD`, `HIVE_CLIQUE_PRIVATEKEY` | Not handled — reasonable, since neither ETC nor ETH mainnet/testnets use Clique and no `hive-*.yml` workflow runs `smoke/clique` against fukuii | absence confirmed, consistent with scope |
| `HIVE_GRAPHQL_ENABLED` | Not handled, **but this is compliant by a different mechanism**: fukuii's GraphQL endpoint (`GraphQLConfig`, `src/main/scala/.../utils/Config.scala:245-264`) defaults `enabled = true` whenever its config block is absent — "Default to enabled when the block is absent so users pick up the feature transparently" (`Config.scala:254`) — and is mounted as an EIP-1767 `/graphql` path on the same JSON-RPC HTTP port fukuii already always enables (`fukuii.sh:101-104`). Unlike geth/besu, which use `HIVE_GRAPHQL_ENABLED` to *switch* port 8545 from plain JSON-RPC into GraphQL-only mode, fukuii mounts both simultaneously and always — so `hive-graphql.yml` should reach a working endpoint without fukuii ever reading the flag. | `Config.scala:245-264`, `fukuii.sh:101-104` |
| `HIVE_ALLOW_UNPROTECTED_TX` | Not handled (used by `ethereum/graphql` for unprotected-tx submission tests — `docs/simulators.md` doesn't document it but `geth.sh:154-156` does) | absence confirmed |
| `HIVE_FORK_DAO_BLOCK`, `HIVE_FORK_DAO_VOTE` | Not handled at all — no `MAX`-sentinel default the way the 11 other fork vars get | absence confirmed |

**Net assessment:** the fork-schedule, chain-identity, TTD, timestamp-fork, bootnode, and
mining variables are fully and thoughtfully handled (the TTD and JIT-tuning comments in
`fukuii.sh` show real prior debugging effort against Hive's actual behavior, not a
first-pass guess). `HIVE_LOGLEVEL` and `HIVE_NODETYPE` are the two genuine, documented "must
support" gaps with no compensating mechanism — see the verdict table.

### enode.sh — more defensive than the reference clients, at a cost

fukuii's `enode.sh` (`hive/fukuii/enode.sh:1-25`) tries `admin_nodeInfo` first (matching
geth/besu), then falls back to grepping a running log file for an `enode://` string
(`enode.sh:18-21`), then falls back to a **hardcoded placeholder**,
`"enode://unknown@127.0.0.1:30303"` (`enode.sh:25`), printed with exit code 0. Neither
geth's nor besu's script has this third tier: geth's fails outright if `admin_nodeInfo`
doesn't respond usefully (no retry at all, `clients/go-ethereum/enode.sh`); besu's retries
up to 50 times then falls through to printing whatever `jq -r '.result.enode'` produces
against a possibly-empty response (likely `null`, not a fabricated value). fukuii's
placeholder path means a test that depends on the enode value for peer-dialing would
receive syntactically valid-looking but semantically useless data instead of a clear
failure — a soft violation of fukuii's own stated "fail loudly, no silent fallbacks that
turn hard failures into quiet corruption" principle (`AGENTS.md`, Working discipline
section). This is a plausible, if narrow, source of a confusing false-pass/false-fail in
any future simulator that consumes `enode.sh` output blindly (currently only
`ethereum/sync` and `devp2p` do, per `docs/clients.md:113-116`).

### Port-check ordering — a documented, deliberate fix

`Dockerfile:23-28` sets `HIVE_CHECK_LIVE_PORT=8551` (the Engine API authrpc port) instead of
the Hive-wide default of 8545, with an explicit comment explaining why: fukuii's Engine API
server binds *after* its JSON-RPC HTTP server during startup, and using the default 8545
check caused connection-refused fast-fails in four numbered `ethereum/sync` sub-tests
before this fix. This is exactly the kind of client-specific startup-ordering knowledge
Hive's docs assume each adapter author discovers through trial and error
(`docs/clients.md:68-72` only documents that the variable *exists*, not how to choose it) —
fukuii's comment trail here is a model of what every other `HIVE_*` handling decision in
this file should look like, and mostly does.

---

## Local invocation (Makefile/test.sh)

**The root `Makefile` is not the Go build entry point** — it is scoped entirely to the Rust
side of the repository. Its four targets (`lint`, `fmt`, `lint-fix`, `check`, `build`,
`Makefile:1-18`) all shell out to `cargo` against `$(RUST_WORKSPACE_FLAGS)` (`--workspace
--all-targets`). This is confirmed by the root `Cargo.toml`: `[workspace] members =
["hivesim-rs", "simulators/lean"]`, `exclude = ["simulators/portal"]` (`Cargo.toml:1-4`) —
i.e. the Makefile builds/lints exactly `hivesim-rs` (the Rust client-library equivalent of
the Go `hivesim` package, used by Rust-based simulators) plus the `lean` simulator; `portal`
deliberately sits outside this workspace (its own `Cargo.lock`/`Cargo.toml` exist
independently, presumably to avoid a dependency-version conflict with `lean`). **None of
this Rust tooling is relevant to fukuii** (an EL-only client with no `lean`/`portal`
integration), but it's important to know the root `Makefile` is not "the way to build
hive" — that's a common false assumption for anyone coming from a Go-Makefile-convention
background.

**The actual local-build and local-run commands**, per `docs/commandline.md:3-170` (read in
full) and cross-checked against fukuii's own `.github/workflows/_hive-sim.yml` (which
performs the identical sequence in CI):

```bash
# Build the hive binary itself — no Makefile target; this is the literal command.
git clone https://github.com/ethereum/hive
cd hive
go build .                       # produces ./hive at repo root (hive.go is package main)

# Build the results viewer (optional, for browsing JSON results in a browser).
go build ./cmd/hiveview
./hiveview --serve --logdir ./workspace/logs   # http://127.0.0.1:8080

# Build the test-chain generator (optional, for producing custom chain.rlp fixtures).
go build ./cmd/hivechain
./hivechain generate -genesis ./genesis.json -length 200

# Run a simulation.
./hive --sim <simulator-path> --client <comma-separated-client-list>
```

Confirmed against `docs/commandline.md:13-17` (build), `:30-40` (basic run invocation),
`:136-144` (`hiveview` build+serve), `:150-154` (`hivechain` build+generate) — and
independently re-derived by fukuii's own CI, whose "Build hive" step comment even documents
the one non-obvious fact a first-time reader would get wrong: "ethereum/hive's main binary
lives at the repo root (`hive.go`), NOT under `cmd/hive` (which doesn't exist — `cmd/` holds
`hivechain` + `hiveview`)" (`_hive-sim.yml:12-13, 233-234`, repeated verbatim in every
standalone `hive-*.yml` that predates the reusable workflow).

**Client-registration step** — not part of upstream `docs/commandline.md` (which assumes a
client the public Hive instance already knows about), but the load-bearing step for any
new/private client adapter, demonstrated identically by every fukuii workflow:

```bash
mkdir -p external/hive/clients/fukuii
cp hive/fukuii/{Dockerfile,fukuii.sh,mapper.jq,enode.sh,hive.yaml} external/hive/clients/fukuii/
cp target/scala-3.*/fukuii-assembly-*.jar external/hive/clients/fukuii/
```

(`_hive-sim.yml:210-218`, and duplicated near-verbatim in `hive-osaka.yml`/`hive-prague.yml`
prior to the reusable workflow's introduction). This is the mechanical equivalent of "copy
or symlink your client directory into hive's `clients/`" that fukuii's own
`hive/README.md:23-33` already documents for a human running things by hand.

**`test.sh`** (repo root, 69 lines, read in full) is Hive's **own** maintainer smoke script,
not a template fukuii should imitate directly — it's explicitly scoped to "a some trial
runs of clients" for local development (`test.sh:5`), stores results under
`/tmp/TestResults`, and its actual test invocations are almost entirely commented out
(only `testsync besu_latest`/`testsync nethermind_latest` — both annotated "# fails" —
and `testgraphql go-ethereum_latest`/`testgraphql besu_latest` are live; every
`testconsensus`/`testdevp2p`/other `testsync` line is commented out, `test.sh:56-94`). It
predates the newer `eels/consume-engine`/`consume-rlp` simulators and the current fork set
entirely (no Cancun/Prague/Osaka awareness) — read for context, not as a pattern to port.

**Direct relevance to a future `fukuii-test-hive` skill:** the entire local sequence above
(clone hive → `go build .` → copy fukuii's five adapter files + a fresh assembly jar into
`external/hive/clients/fukuii/` → `./hive --sim <path> --client fukuii --sim.parallelism N
--client.checktimelimit <duration>`) is exactly what such a skill would need to script,
minus the CI-only steps (Docker image tagging via a synthetic `target/quick-docker/Dockerfile`,
GitHub Actions artifact upload, and the authenticated `execution-spec-tests` fixtures-URL
resolution used only by `hive-osaka.yml`/`hive-prague.yml`). fukuii's existing
`hive/test-local.sh` already covers the narrower "does the adapter boot and answer
JSON-RPC/Engine-API at all" smoke check without invoking Hive itself — a genuinely useful
complementary fast-path, not a superseded artifact.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Already compliant | Reasoning |
|---|---|---|
| `hive-pyspec.yml` targets `--sim ethereum/pyspec`, a directory upstream Hive **deleted** (`7b0ce986`, "add eest consume engine/rlp and remove pyspec") | **Needs design (fix now)** | This is a real, concrete break: `LoadInventory`/`MatchSimulators` will not find `ethereum/pyspec` in a current `master` clone, so `hive-pyspec.yml` either silently no-ops (`inv.MatchSimulators` returns empty, and `hive.go:123-125` only `fatal`s on empty match when `*simPattern != ""` — which it is here, so it likely *does* hard-fail with "no simulators for pattern") or fails opaquely in CI. fukuii already has the correct modern replacements wired up as separate workflows (`hive-consume-engine.yml`, `hive-consume-rlp.yml`, both targeting `ethereum/eels/consume-engine`/`consume-rlp` via `_hive-sim.yml`). The fix is deletion of `hive-pyspec.yml`, not a path-rename — its function is already covered twice over. |
| `hive-osaka.yml` and `hive-prague.yml` fully duplicate `_hive-sim.yml`'s ~200-line pipeline (checkout, JDK setup, sbt assembly, Docker tag, hive clone+build, tabulate, upload, threshold gate) instead of calling the reusable workflow the way `hive-consume-engine.yml`/`hive-consume-rlp.yml`/9 others do | **Needs design** | `_hive-sim.yml` already exposes `sim_limit` (regex, used here for `.*fork_Osaka.*`/`.*fork_Prague.*`) and `pass_threshold` (used here for the 0/400 gates) as first-class inputs — nothing about osaka/prague's *filtering or gating* logic requires standalone duplication. The one piece `_hive-sim.yml` genuinely lacks is the authenticated `gh api` resolution of an exact `execution-spec-tests` fixtures release URL (done to dodge anonymous GitHub API rate-limiting inside the simulator's own `consume cache` step). Scoped fix: add an optional `fixtures_tag`/`resolve_fixtures` input to `_hive-sim.yml` that performs the same `gh api` lookup and folds the result into `sim_buildarg`, then convert `hive-osaka.yml`/`hive-prague.yml` into thin callers matching `hive-consume-engine.yml`'s shape. This removes ~180 duplicated, driftable lines. |
| `hive/fukuii/hive.yaml` declares `roles: ["eth1", "eth1_engine"]`, but no simulator in the vendored tree filters on `"eth1_engine"` — it's inert | **Already compliant / informational only** | Not a bug — harmless unused metadata. No action needed unless a future Hive simulator introduces an `eth1_engine`-gated suite, at which point fukuii is already positioned to benefit without a doc change. |
| fukuii's `hive.yaml` does **not** declare `eth1_snap`, and `fukuii.sh` has no `HIVE_NODETYPE` handling at all | **Needs design** | Concrete, scoped consequence: fukuii is entirely excluded from `ethereum/sync`'s snapsync suite (`sync.go:37-56`) despite having a real SNAP-sync implementation (`.claude/agent-protocols/storage-rocksdb.md`, the `vault` subagent's domain). To participate: (1) add `eth1_snap` to `hive/fukuii/hive.yaml`'s role list, (2) add a `case "$HIVE_NODETYPE"` branch in `fukuii.sh` that maps `snap`/`full`/`archive` to fukuii's own `sync.do-snap-sync`/`do-fast-sync` HOCON keys (visible already in `src/main/resources/conf/hive.conf:8-11`, currently hardcoded off for the plain "hive" profile). This is genuinely new work, not a one-line fix — scope it as its own follow-up. |
| `HIVE_LOGLEVEL` is a documented "must support" env var (`docs/clients.md:124`) that `fukuii.sh` never reads | **Needs design (small)** | Low-risk, low-effort: map `$HIVE_LOGLEVEL` (0-5) onto fukuii's own log-level config key the way `besu.sh:56-64` maps it onto Besu's `--logging=` flag with a `case` statement. Mainly affects diagnosability of hive-run failures (currently fukuii always logs at its own default level regardless of what a simulator or `--sim.loglevel` requests), not correctness. |
| `hive/fukuii/enode.sh`'s third fallback tier prints a hardcoded placeholder enode (`enode://unknown@127.0.0.1:30303`) with exit 0 instead of failing | **Needs design (small)** | Contradicts fukuii's own stated "fail loudly" principle. Neither reference client (geth, besu) fabricates a value — they either hard-fail (geth) or pass through whatever the RPC call actually returned, including `null` (besu). Recommend removing the placeholder tier and letting the script fail if neither `admin_nodeInfo` nor a log-scraped enode string is found, so a genuinely broken peer-identity path surfaces as a clear test failure rather than a confusing pass/false-peer-connect downstream. |
| `HIVE_GRAPHQL_ENABLED` is unhandled by `fukuii.sh`, but fukuii's `GraphQLConfig` defaults to `enabled = true` and mounts `/graphql` on the same HTTP port fukuii already always enables | **Already compliant (different mechanism)** | No action needed — this was verified against fukuii's own `Config.scala:245-264`, not assumed. `hive-graphql.yml` should be exercising a genuinely live endpoint today. Worth a quick manual confirmation run given it was never previously verified end-to-end against this specific finding, but no code change is implicated. |
| Genesis fork-schedule is carried via JVM system properties (`fukuii.sh`) rather than folded into the genesis JSON's `config` object the way every reference client's `mapper.jq` does | **Already compliant (different mechanism)** | Architecturally distinct from geth/besu but satisfies the same env-var contract; no action needed. Documented here so a future contributor doesn't "fix" `mapper.jq` to add a `config` block that fukuii's genesis loader doesn't consume. |
| The root `Makefile` and `test.sh` are irrelevant to fukuii's integration (Rust-workspace tooling and Hive's own stale maintainer smoke script, respectively) | **Not portable / informational only** | Nothing to port. Flagged only so a future `fukuii-test-hive` skill author doesn't waste time trying to reuse either file — the real local-invocation sequence is the `go build .` / adapter-copy / `./hive --sim ... --client fukuii` flow documented above, independently re-derived already by fukuii's own CI comments. |
| `docs/clients.md`'s documented `HIVE_*` env-var table (`:119-153`) omits the newer timestamp-fork and blob-schedule variables that `go-ethereum/mapper.jq` and fukuii's own `fukuii.sh` both already implement | **Not portable / informational only** | A genuine gap in Hive's own upstream docs, not something fukuii should "fix" locally — noted so a future reader trusts the reference client's `mapper.jq` over the prose docs when the two disagree on which variables exist. |
| No `hive-*.yml` workflow exercises `smoke/clique`, `eels/consume-enginex`, `eels/consume-sync`, or `eels/execute-blobs` | **Not portable / informational only** | Scope-appropriate omissions, not gaps: fukuii doesn't implement Clique PoA (irrelevant to ETC/ETH), and the three unexercised `eels/*` simulators are narrower variants of the already-covered `consume-engine`/`consume-rlp` pair (enginex is an experimental Engine-API variant; consume-sync and execute-blobs target sync-specific and blob-specific EEST fixture subsets). Revisit only if EEST ships fixture categories fukuii doesn't otherwise cover via `consume-engine`/`consume-rlp`. |

---

*Compiled from a direct read of every file cited above in the vendored clone at
`.claude/repo-references/hive/` and in fukuii's own `hive/` and `.github/workflows/`
directories. Line numbers refer to the current checkout (`go.mod` pins `go 1.24.0`,
`module github.com/ethereum/hive`); re-verify against `git log` if the vendored copy is
refreshed. The `ethereum/pyspec` removal was confirmed via
`git log --oneline -- simulators/ethereum/pyspec` inside the vendored clone, not inferred
from directory absence alone.*
