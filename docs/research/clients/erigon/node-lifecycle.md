# erigon — node-lifecycle
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon's node lifecycle is a **three-layer stack** with an unusual twist: the
same binary can run its major subsystems (sentry/P2P, txpool, downloader,
rpcdaemon) **in-process (embedded) OR as separate processes talking over gRPC**,
and a single config flag per component decides which. The layers:

1. **`main` / CLI** (`cmd/erigon/main.go`) — urfave/cli app; parses flags, sets
   up logging + metrics + pprof (`node/debug.Setup`), builds two config objects
   (`nodecfg.Config` for the node container, `ethconfig.Config` for the
   Ethereum backend), then constructs and `Serve()`s the node.

2. **`node.Node`** (`node/node.go`) — a **geth-derived generic service container**.
   It knows nothing about Ethereum: it holds a datadir flock, a list of
   `Lifecycle` services, a set of DB closers, and a three-state machine
   (`initializing → running → closed`). Services self-register via
   `RegisterLifecycle`; `Start()` starts them in order and `Close()` stops them
   in **reverse** order. This is a near-verbatim descendant of go-ethereum's
   `node.Node` (the file header even preserves the 2015 geth copyright).

3. **`eth.Ethereum`** (`node/eth/backend.go`, 1672 lines) — the real backend
   "god struct" that constructs and wires every subsystem, then registers
   *itself* as the single `Lifecycle` on the container. `New` builds
   everything; `Init` finishes RPC/private-API wiring and calls
   `stack.RegisterLifecycle(s)`; `Start`/`Stop` drive all the background
   goroutines.

The distinctive lifecycle contribution is the **componentization effort**: heavy
subsystems are being extracted from `backend.go` into
`node/components/{sentry,downloader,storage}` providers, each following a uniform
`Configure → Initialize → Start → Close` provider lifecycle and each encoding the
embedded-vs-remote choice internally. A `nodebuilder.Builder` is the emerging
central registry for these extracted components.

## Key types / interfaces / files

- `cmd/erigon/main.go:39` — `main()`: wraps `app.Run` in profilers; `runErigon`
  (`:56`) does `debug.Setup` → build node/eth configs → `node.New` (the
  `ErigonNode` wrapper in `cmd/erigon/node/node.go`) → `ethNode.Serve()`.
- `cmd/erigon/node/node.go:47` — `ErigonNode{stack *node.Node; backend *eth.Ethereum}`:
  the top wrapper. `Serve()` (`:53`) runs `StartNode` then blocks on
  `stack.Wait()`; `New` (`:136`) constructs the container, then the backend,
  then `ethereum.Init(...)`.
- `node/node.go:53` — `Node`: the generic service container (`lifecycles []Lifecycle`,
  `databases []kv.Closer`, `dirLock *flock.Flock`, `state int`).
- `node/node.go:106` `Start()` — starts registered lifecycles in order; on any
  failure it rolls back by stopping the ones already started.
- `node/node.go:150` `Close()` / `:210` `stopServices` — stops lifecycles in
  **reverse registration order** (`for i := len-1; i>=0; i--`), then closes DBs,
  then releases the datadir flock, then `close(n.stop)` to unblock `Wait`.
- `node/node.go:227` `openDataDir` — acquires an exclusive datadir flock with a
  10-retry / 2-second-backoff loop (`datadir.ErrDataDirLocked`); this is what
  prevents two erigon processes sharing one datadir.
- `node/node.go:415` `StartNode` — calls `stack.Start()` then spawns
  `debug.ListenSignals(...)` so SIGINT/SIGTERM triggers `stack.Close()`.
- `node/lifecycle.go:26` — `Lifecycle interface { Start() error; Stop() error }`:
  the entire service contract (geth-derived).
- `node/eth/backend.go:137` — `Ethereum`: the backend struct. Note the paired
  fields that encode embedded-vs-remote: `txPool *txpool.TxPool` +
  `txPoolGrpcServer txpoolproto.TxpoolServer` + `txPoolRpcClient txpoolproto.TxpoolClient`;
  `privateAPI *grpc.Server`; `sentryProvider *sentrycomp.Provider`;
  `components *nodebuilder.Builder`.
- `node/eth/backend.go:1133` `Init` — finishes wiring and calls
  `stack.RegisterLifecycle(s)` (`:1191`), plus launches the HTTP RPC server and
  Engine API server as background goroutines (`bgComponentsEg`).
- `node/eth/backend.go:1415` `Start` / `:1494` `Stop` — the backend's own
  `Lifecycle` implementation (see shutdown ordering below).
- `node/components/sentry/provider.go:70` `Config` / `:152` `Provider` — the
  **embedded-or-remote P2P component**. The package doc (`:17-34`) states the two
  modes explicitly.
- `node/components/sentry/provider.go:217` `Initialize` — the branch point:
  `if len(p.cfg.P2P.SentryAddr) > 0` → dial external sentry processes via
  `sentry_multi_client.GrpcClient` (remote); else build one in-process
  `sentry.GrpcServer` per protocol version and wrap each in
  `direct.NewSentryClientDirect` (local).
- `node/components/downloader/provider.go:49-50,110-113` — same pattern:
  `Downloader *dl.Downloader // nil when using external downloader`; `Client`
  always set (local or remote). `initDownloader` dials
  `--downloader.api.addr` via `downloadergrpc.NewClient` when set, else builds a
  local `dl.NewGrpcServer` + `DirectGrpcServerClient`.
- `node/nodebuilder/builder.go:44` `Builder` — central component registry
  (`Downloader`, `Storage` providers today); `New()` pre-allocates, `Build*`
  methods enforce ordering (downloader before storage).
- `node/eth/backend.go:702-729` — txpool embedded/disabled toggle: `TxPool.Disable`
  → `GrpcDisabled` stub, else in-process `txpool.Assemble` fed the sentry clients.
- `node/eth/backend.go:735-752` `rpcdaemoncli.EmbeddedServices` — builds
  **in-process** RPC clients (`ethRpcClient`, `txPoolRpcClient`, `miningRpcClient`)
  so the embedded HTTP JSON-RPC server calls the backend directly instead of over
  gRPC.
- `node/eth/backend.go:867-889` — the **remote toggle for rpcdaemon**: only when
  `stack.Config().PrivateApiAddr != ""` does it `privateapi2.StartGrpc` to expose
  the KV/EthBackend/TxPool/Mining gRPC services that a *separate* `rpcdaemon`
  process would dial. TLS optional via `TLSConnection`.
- `node/debug/flags.go:241` `Setup` — one-shot observability bootstrap: logger
  (`logging.SetupLoggerCtx`), OTEL tracer, Prometheus metrics mux
  (`metrics.Setup`, gated on `--metrics`), pprof mux (gated on `--pprof`,
  co-hosted on the metrics mux if addresses match), optional pyroscope profiler,
  and a periodic near-OOM heap-profile goroutine. Returns `(logger, tracer,
  metricsMux, pprofMux)`.
- `cmd/{rpcdaemon,sentry,txpool,downloader,caplin}/main.go` — the **standalone
  process entrypoints**: the same subsystems that can run embedded also each have
  their own binary. This is the other half of the embedded-or-remote story.
- Config flags: `--sentry.api.addr` (`cmd/utils/flags.go:560`),
  `--downloader.api.addr` (`:568`), `--txpool.api.addr` (`:463`),
  `--private.api.addr` (`node/cli/flags.go:65`). Each non-empty value flips a
  component from embedded to remote (or exposes it for a remote consumer).

## Design decisions & rationale

- **Two config objects, not one.** `nodecfg.Config` configures the generic
  container (name, dirs, P2P, IPC, private-API address, TLS); `ethconfig.Config`
  configures the Ethereum protocol/backend. `main.go` builds them separately
  (`NewNodConfigUrfave` / `NewEthConfigUrfave`) so the container stays
  domain-agnostic. `ethconfig.Defaults` is copied (not pointer-shared) per node.
- **The container is dumb; the backend is smart.** `node.Node` only knows the
  `Lifecycle` contract and DB/flock ownership. All Ethereum-specific wiring lives
  in `eth.New`. This is deliberately geth's design, kept so erigon can track
  upstream node-container fixes.
- **Register-once, start-in-order, stop-in-reverse.** `RegisterLifecycle` panics
  if called after `running` or on a duplicate; `Start` records which lifecycles
  actually started so a mid-startup failure unwinds cleanly; `stopServices`
  reverses order so dependencies tear down after dependents.
- **Embedded is the default; remote is opt-in per component.** Every heavy
  component (`sentry`, `downloader`, `txpool`, `rpcdaemon`) defaults to in-process
  and switches to gRPC only when its `*.api.addr` flag is set. In-process wiring
  uses `direct.*` clients (`NewSentryClientDirect`, `DirectGrpcServerClient`,
  `NewEthBackendClientDirect`) — thin adapters that satisfy the same gRPC-generated
  interface but call the server object in-memory, so **consumers are written once
  against the gRPC interface regardless of mode.**
- **Componentization is an in-flight refactor.** `nodebuilder` and
  `node/components/*` exist to migrate subsystems out of the 1672-line
  `backend.go` one at a time; the builder's doc lists build ordering and serves as
  "an inventory of what has been componentized vs what remains." The uniform
  `Configure/Initialize/Start/Close` provider lifecycle is the target shape.
- **Shared p2p.Server behind per-protocol sentries** (local mode): one
  `p2p.Server` backs all per-protocol `GrpcServer`s so the node publishes exactly
  one ENR / Node ID (`provider.go:235-296`). The comment records the bug this
  fixed — a Server-per-protocol raced in the discovery DHT under one Node ID.

## Notable patterns (the reusable idea)

**The single most transferable pattern: the `direct` in-process client adapter
against a gRPC-generated service interface.** Erigon defines each cross-component
boundary (sentry, txpool, downloader, eth-backend) as a protobuf/gRPC service.
Consumers depend only on the generated *client interface*. Two implementations
satisfy it:

- a real gRPC client (`GrpcClient`, `downloadergrpc.NewClient`) — used when the
  component runs as a separate process, and
- a `direct.*` shim (`NewSentryClientDirect`, `DirectGrpcServerClient`) that wraps
  the server struct and dispatches calls in-memory with zero network hops.

A single config flag (`--<component>.api.addr`, or `PrivateApiAddr` for the
inbound direction) picks the implementation at wiring time. **The monolith and
the distributed deployment share one codebase and one set of consumers; only the
edge adapter differs.** Secondary reusable ideas: (a) the generic `Lifecycle`
container with register-once / start-ordered / stop-reversed semantics decoupled
from domain logic; (b) the `Configure → Initialize → Start → Close` provider
lifecycle that separates cheap config capture from side-effectful init from
goroutine launch from drain-on-close; (c) the datadir flock with bounded retry as
the single-writer guard.

## Authority note

For **node-lifecycle**, erigon is the authority on **embedded-or-remote component
wiring** — one binary that runs monolithic or distributed, decided by config, with
`direct` in-process shims standing in for gRPC clients. Cross-references:

- **geth** is the canonical authority for the **generic service container**
  (`node.Node` + `Lifecycle`): erigon's `node/node.go` and `node/lifecycle.go` are
  direct descendants (preserved 2015 geth copyright), so read geth's `node`
  package for the base register/start/stop/Wait semantics and erigon's for the
  multi-service-wiring layer on top.
- **nethermind** is the authority for **plugin-based DI** (formal `INethermindApi`
  / `IStep` init-step graph and an `IPlugin` system) — a more declarative take on
  the same "wire N services with ordering + optional components" problem that
  erigon solves imperatively in `backend.go` + `nodebuilder`.

Consult all three when designing a service-container/lifecycle layer: geth for the
minimal container contract, nethermind for declarative DI/plugins, erigon for the
embedded-or-remote deployment seam.

## Gotchas / anti-patterns / things they later changed

- **`backend.go` is a 1672-line god-struct.** `eth.New` does nearly all wiring
  imperatively; the `nodebuilder`/`components` effort exists precisely because
  this file became unmanageable. Treat it as the anti-pattern erigon is actively
  refactoring away from, not the target design.
- **Public fields, set-after-Initialize, read directly.** The sentry `Provider`
  exposes `Servers`, `Sentries`, `Client`, `StatusDataProvider`, etc. as bare
  public fields that consumers read directly (no getters). Ordering is a landmine:
  fields are only valid after `Initialize`, and `Client` only after the *separate*
  `BuildMultiClient` call (which needs the consensus engine, constructed later).
  Get the call order wrong and you read a nil.
- **Split construction: `New` then `Init`.** The backend can't finish RPC/private-API
  wiring in `New` (it needs the fully-built engine and pools), so `cmd/erigon/node`
  calls `eth.New(...)` then `ethereum.Init(...)` as two steps. Registration with
  the container happens at the *end* of `Init`, not in `New`.
- **Shutdown is a hand-ordered drain, not just reverse-Lifecycle.** `Ethereum.Stop`
  (`backend.go:1494`) manually orders teardown: cancel `sentryCtx` first → stop
  ethstats/downloader/private-API (with a 1s graceful-stop deadline before a hard
  `Stop`) → close engine and sentry provider → **then `bgComponentsEg.Wait()` plus
  three separate bounded WaitIdle/WaitForWarmup/kzg waits** to ensure every
  fire-and-forget goroutine has released its DB read transaction *before*
  `chainDB.Close()`. The comments record that skipping these waits hangs
  `chainDB.Close()` in `waitTxsAllDoneOnClose` — a real bug they hit.
- **IPC force-disabled.** `NewNodeConfig` sets `IPCPath = ""` unconditionally
  (`cmd/erigon/node/node.go:168`) — erigon does not expose the geth IPC endpoint;
  the private gRPC API is the equivalent seam.
- **`node.Node.stopRPC` is commented out** (`node/node.go:211`) — RPC lifecycle
  moved into the backend (`bgComponentsEg` goroutines), leaving dead scaffolding
  in the geth-derived container. Evidence the container↔backend split is still
  settling.
- **pprof/metrics muxes are threaded by hand** from `debug.Setup` down through
  `runErigon` into the node config (`DebugMux`) and downloader — not a global
  registry; a component that wants to expose debug handlers must be passed the mux
  explicitly.
