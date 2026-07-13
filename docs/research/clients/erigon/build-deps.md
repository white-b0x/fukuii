# erigon — build-deps
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon is a **single Go module** (`github.com/erigontech/erigon`, `go 1.25.7`) built
with a **plain Makefile over `go build`** — no Bazel, no multi-repo workspace. Its two
architecturally distinctive build traits are:

1. **The erigon-lib module split is GONE in this vintage.** Historically erigon
   vendored its low-level primitives (MDBX wrappers, ETL, kv, common types) as a
   *separately-versioned* `erigon-lib` Go module. At `f1d79d699e` that split has been
   **fully re-absorbed into the main module** — there is no `erigon-lib/` directory and
   no second `go.mod` for it. Those primitives now live inline under `db/`, `common/`,
   `execution/`, etc. The *only* secondary module in the tree is
   `node/interfaces/go.mod` (`module github.com/erigontech/interfaces`, `go 1.18`) — the
   protobuf/gRPC interface stubs that define the seams *between* erigon's service
   binaries. So the multi-module story collapsed to a single build module, but the
   multi-*binary* story is alive and is where the decomposition now lives.

2. **The node is built as a fleet of separable service binaries**, not one monolith.
   A generic `%.cmd` Makefile rule compiles each `cmd/<name>` directory into its own
   binary. `erigon` is the all-in-one; `sentry`, `txpool`, `rpcdaemon`, `downloader`,
   `caplin` are the components that can each be **run as a standalone process** and wired
   back to the core over gRPC (contracts defined in `node/interfaces`). This is erigon's
   "staged/componentized" architecture: run everything in one process, or split the P2P
   sentry, the txpool, the RPC daemon, the snapshot downloader, and the consensus-layer
   (Caplin) out as independently-scaled services.

The build is **CGO-heavy** (MDBX, secp256k1, blst, libdeflate all compile native C),
which is a defining constraint — erigon cannot be a pure-Go static binary.

## Key types / interfaces / files

- `go.mod:1-3` — single module `github.com/erigontech/erigon`, `go 1.25.7`. ~412
  direct+indirect require lines; no `erigon-lib` requirement (merged in).
- `node/interfaces/go.mod:1-2` — the lone secondary module,
  `github.com/erigontech/interfaces` (`go 1.18`); protobuf-generated gRPC contracts that
  are the seams between the service binaries.
- `.gitmodules:1-3` — only one git submodule: `ethereum/tests` at
  `execution/tests/legacy-tests`. (No erigon-lib submodule.)
- `Makefile:173-181` — the generic `%.cmd` rule: `cd ./cmd/$* && $(GOBUILD) -o $(OUTPUT)`
  — one rule builds any `cmd/<name>` into `$(GOBIN)/<name>`.
- `Makefile:186-206` — binary manifest. `erigon:` (the node) plus the `COMMANDS +=` list:
  `capcli downloader integration pics rpcdaemon rpctest sentry txpool evm caplin snapshots
  mcp`; `all: erigon $(COMMANDS)` builds them all.
- `cmd/` (18 `main.go` targets) — the actual binaries:
  `erigon` (node), `sentry` (standalone P2P/devp2p server), `txpool` (standalone txpool
  service), `rpcdaemon` (standalone JSON-RPC server reading the DB), `downloader`
  (standalone snapshot/torrent downloader), `caplin` (standalone consensus-layer/beacon
  client), plus tooling: `bootnode`, `abigen`, `evm`, `rlpdump`, `rlpgen`, `integration`,
  `rpctest`, `snapshots`, `capcli`, `txnbench`, `bumper`, `mcp`.
- `Makefile:78-91` — build-tag machinery: `BUILD_TAGS =` overridable via
  `EXTRA_BUILD_TAGS`; `GOBUILD = ... go build $(GO_RELEASE_FLAGS) -tags $(BUILD_TAGS)`.
- `Makefile:90-92` — version injection via `-ldflags`: `GitCommit`/`GitBranch`/`GitTag`
  are stamped into `${PACKAGE}/db/version` at link time (source of truth:
  `db/version/app.go`).
- `Makefile:83-88` (CGO_CFLAGS/CGO_LDFLAGS) & `-D__BLST_PORTABLE__` — the native-C
  compile flags; MDBX/blst tuning (`-O3`, `MDBX_DEBUG` toggles) lives here.

## Design decisions & rationale

- **Re-merging erigon-lib into one module** removes the version-skew tax of a
  separately-tagged low-level module (every core change no longer needs a two-step
  "publish erigon-lib, then bump erigon"). The seam that *had* to stay separate
  (`node/interfaces`, the gRPC wire contracts) is exactly the seam that crosses a
  process boundary — so the remaining module boundary is drawn at the network boundary,
  not at an arbitrary code-layer boundary.
- **`%.cmd` generic rule** keeps the binary count cheap to grow — adding a service is
  one `COMMANDS += name` line + a `cmd/name/main.go`, no per-binary Makefile boilerplate.
- **erigon-maintained hard forks of hot dependencies** (see below) — erigon controls the
  performance-critical native libs directly rather than waiting on upstream.
- **CGO accepted as a cost** — MDBX (the DB engine), secp256k1, blst, and libdeflate are
  all native for throughput; erigon trades pure-Go portability for archival-node speed.

## Notable patterns (the reusable idea)

**The multi-binary service decomposition is the transferable pattern.** One codebase,
one build module, but the runtime is a set of independently-launchable processes
(`sentry`, `txpool`, `rpcdaemon`, `downloader`, `caplin`) whose contracts are pinned in a
small dedicated interface module (`node/interfaces`, gRPC/protobuf). The default `erigon`
binary runs them all in-process; operators who need to scale one concern independently
run that concern as its own binary against the same DB / over gRPC. The build system
makes each component a first-class target via a single generic rule, so the seam is
visible in the *build manifest itself* (`COMMANDS +=`), not hidden inside runtime config.

Secondary reusable idea: **draw your remaining module boundary at the process boundary.**
When erigon collapsed erigon-lib back in, the one module it kept separate was the
gRPC interface package — i.e. the module split now coincides exactly with where a network
hop happens. That is a cleaner rule than splitting modules by code layer.

## Authority note

erigon = the performance / DB-architecture authority in the SR reference set (MDBX,
staged sync, flat state). For **build-deps specifically**, its distinctive contributions
are (a) the **multi-binary component decomposition** — `erigon` + `sentry` + `txpool` +
`rpcdaemon` + `downloader` + `caplin` as separable service binaries over a gRPC interface
module — and (b) the **erigon-lib module lifecycle**: a low-level module that was split
out and, by this vintage, merged back in, leaving only the process-boundary interface
module separate. Both are directly relevant to fukuii's "lean node + separately-runnable
components" product-family thesis: erigon is a working precedent that a single EVM
codebase can ship a monolith *and* independently-scalable service binaries from one build,
which is exactly the enterprise/archival "run components separately, scale each
independently" use-case fukuii wants to serve.

## Gotchas / anti-patterns / things they later changed

- **erigon-lib is not a separate module anymore** — any doc/tooling that expects
  `erigon-lib/go.mod` or `github.com/erigontech/erigon-lib` is stale for this vintage.
  Don't cite it as a live example of a multi-module split; cite it as a *split that was
  reversed*.
- **erigon hard-forks its performance-critical deps** rather than using upstream — the
  `require` block pins `github.com/erigontech/mdbx-go`, `erigontech/secp256k1`,
  `erigontech/fastkeccak`, `erigontech/evmone_precompiles`, `erigontech/go-libdeflate`,
  plus `replace` directives redirecting `crate-crypto/go-eth-kzg` →
  `erigontech/go-eth-kzg` (`go.mod:473`) and `holiman/bloomfilter/v2` →
  `AskAlexSharov/bloomfilter/v2` (`go.mod:5`). Several are pinned to
  pseudo-versions (`v0.0.0-<timestamp>-<sha>`), i.e. un-tagged commits — a supply-chain
  reviewer must pin to the SHA, and there is no upstream release cadence to track.
- **CGO is mandatory** — the build shells out to a C toolchain (`CGO_CFLAGS`/`CGO_LDFLAGS`,
  `-D__BLST_PORTABLE__`, MDBX `-DMDBX_*` toggles). No pure-Go / fully-static build path;
  cross-compilation is correspondingly harder. Contrast with a JVM client like fukuii
  where the analogous native concern (RocksDB) is a JNI dependency, not a compile step.
- **`geth:` target is a legacy alias** (`Makefile:183-185`, `geth: erigon`, marked
  "TODO: remove?") — a fossil from the go-ethereum lineage, not a second binary.
- **The multi-binary decomposition is a build/runtime affordance, not a hard boundary**
  — the components still share the single module's code; the isolation is at the process
  and gRPC level, not enforced by the module system. Splitting a component out doesn't
  give it an independent dependency graph.
