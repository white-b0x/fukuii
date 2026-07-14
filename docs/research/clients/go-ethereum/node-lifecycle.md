# go-ethereum — node-lifecycle
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary

geth's node subsystem is built around one small idea: a **service container**
(`node.Node`) that owns process-wide resources (P2P server, RPC stack, datadir
lock, open databases, account manager) and drives an ordered **start/stop
lifecycle** over a flat list of registered services. Services never construct
each other's transports or manage each other's shutdown; each implements a
two-method `Lifecycle` interface (`Start() error` / `Stop() error`) and
registers itself with the container. The container starts services in
registration order and stops them in strict reverse order, guaranteeing
dependency-respecting teardown.

Construction and wiring happen in three cleanly separated phases:

1. **Config assembly** (`cmd/geth/config.go`) — layer defaults → optional TOML
   file → CLI flags into a `gethConfig`, then build a bare `node.Node` from the
   `node.Config` slice of that struct.
2. **Service registration** (`cmd/geth/config.go:makeFullNode`) — construct each
   backend (`eth.Ethereum`, ethstats, GraphQL, the Engine API "catalyst"
   service, dev-mode simulated beacon, etc.) passing it the `*node.Node`; each
   backend calls back into the container to register its APIs, P2P protocols,
   and itself as a `Lifecycle`. The container is still in `initializingState`
   here — registration after start panics.
3. **Run** (`cmd/geth/main.go:geth` → `utils.StartNode`) — `stack.Start()` opens
   endpoints and starts every lifecycle; a background goroutine waits on
   `SIGINT`/`SIGTERM` and a low-disk monitor, then calls `stack.Close()` for a
   graceful, reverse-order shutdown. `stack.Wait()` blocks main until closed.

The container itself carries no Ethereum-specific knowledge — it manages P2P,
RPC (HTTP/WS/IPC/inproc, plus a separate JWT-authenticated Engine API stack),
datadir locking, and database handles. All chain logic lives in registered
services. This is the canonical **inversion of control** boundary that lets one
binary host mainnet-full, light, dev, or blsync roles by varying *which*
services get registered, not by branching inside the container.

## Key types / interfaces / files

- `node/lifecycle.go:23` — `Lifecycle` interface: exactly `Start() error` and
  `Stop() error`. The entire service contract. Comment notes `Start` runs after
  all services are constructed *and* networking is initialized.
- `node/node.go:45` — `Node` struct: the service container. Holds `config`,
  `server *p2p.Server`, four `*httpServer` (http/ws + httpAuth/wsAuth),
  `ipc`, `inprocHandler`, `lifecycles []Lifecycle`, `rpcAPIs []rpc.API`,
  `databases map`, `dirLock *flock.Flock`, and a `state int`.
- `node/node.go:70-74` — lifecycle state machine: `initializingState` →
  `runningState` → `closedState`. Guards all registration and start/stop.
- `node/node.go:77` — `New(conf *Config)`: copies config, resolves datadir to
  absolute path, validates name, acquires datadir lock (`openDataDir`), sets up
  P2P key/name, and constructs the RPC servers. Does **not** start anything.
- `node/node.go:163` — `Start()`: single-shot (errors if already running/
  closed), sets `runningState`, opens endpoints, then starts each registered
  lifecycle in order; on any failure it rolls back already-started services and
  closes. Lines `190-195` are the ordered start loop.
- `node/node.go:206` — `Close()`: dispatches on state; if running, calls
  `stopServices(n.lifecycles)` then `doClose`.
- `node/node.go:284` — `stopServices(running []Lifecycle)`: stops RPC, then
  iterates `for i := len(running)-1; i >= 0; i--` — **reverse-order teardown** —
  collecting per-service errors into a `StopError` keyed by `reflect.Type`, then
  stops P2P. Line `289` is the reverse loop.
- `node/node.go:232` — `doClose(errs)`: closes databases (under lock, synced
  with `OpenDatabase*`), closes account manager, removes ephemeral keydir,
  releases datadir lock, and `close(n.stop)` to unblock `Wait`.
- `node/node.go:304` — `openDataDir()`: `flock.New(.../LOCK).TryLock()` — the
  single-instance guard; returns `ErrDatadirUsed` if another process holds it.
- `node/node.go:547` — `RegisterLifecycle(lifecycle)`: appends to `lifecycles`;
  panics if node not in `initializingState` or if the same lifecycle is
  registered twice (`slices.Contains`).
- `node/node.go:561` / `node/node.go:572` — `RegisterProtocols` /
  `RegisterAPIs`: same init-state guard; append to the P2P server's protocols
  and the node's API list respectively.
- `node/node.go:542` — `Wait()`: blocks on the `stop` channel until `doClose`
  closes it. This is what keeps `geth` alive.
- `node/node.go:696` — `OpenDatabaseWithOptions`: services open DBs *through*
  the node so it can track and auto-close them; `closeTrackingDB`
  (`node/node.go:769`) un-tracks a DB the service closed itself, avoiding
  double-close.
- `cmd/geth/config.go:109` — `gethConfig` struct: aggregates `ethconfig.Config`,
  `node.Config`, `ethstatsConfig`, `metrics.Config` — the whole app config in
  one TOML-marshalable object.
- `cmd/geth/config.go:144` — `loadBaseConfig`: the **config-layering** core —
  starts from `ethconfig.Defaults` / `defaultNodeConfig()` / `metrics.DefaultConfig`,
  optionally overlays a TOML file (`--config`), then applies CLI flags via
  `utils.SetNodeConfig`. Precedence: defaults < file < flags.
- `cmd/geth/config.go:225` — `makeFullNode`: constructs the node and registers
  every service; the branching at `298-324` selects dev-mode / blsync / normal
  Engine-API roles by registering different lifecycles.
- `cmd/geth/main.go:50-210` — `nodeFlags` / `rpcFlags` / `metricsFlags`: the
  large flag sets, concatenated into `app.Flags` at `256`; `flags.AutoEnvVars`
  (`263`) auto-derives `GETH_*` env-var equivalents for every flag.
- `cmd/geth/main.go:310` — `geth(ctx)`: `makeFullNode` → `startNode` →
  `stack.Wait()`. The blocking run loop.
- `cmd/utils/cmd.go:85` — `StartNode`: `stack.Start()` then a goroutine that
  `signal.Notify(sigc, SIGINT, SIGTERM)` and on signal calls `go stack.Close()`;
  a second+ signal escalates to a loud panic. **Graceful-shutdown wiring.**
- `cmd/utils/cmd.go:134` — `monitorFreeDiskSpace`: polls free disk every 30s and
  injects a `SIGTERM` to gracefully shut down before the DB corrupts on a full
  disk — a custody/reliability safeguard.
- `eth/backend.go:381-383` — the canonical service-registration triple:
  `stack.RegisterAPIs(...)`; `stack.RegisterProtocols(...)`;
  `stack.RegisterLifecycle(eth)`. A service self-registers all three facets.
- `eth/backend.go:465` / `eth/backend.go:599` — `Ethereum.Start` / `.Stop`:
  the service's own ordered internal bring-up (discovery → shutdown-tracker →
  handler → dropper → subscriptions → filtermaps) and its exact reverse
  teardown.
- `internal/shutdowncheck/shutdown_tracker.go:30` — `ShutdownTracker`: writes a
  DB marker on startup, refreshes it every 5 min, clears it on clean stop; a
  surviving marker on next boot ⇒ "unclean shutdown detected." Detects crashes
  vs. graceful stops.
- `log/root.go` / `log/handler.go` — logging built on the Go stdlib
  `log/slog`: a global `Root()` logger (`SetDefault`), pluggable `slog.Handler`
  implementations (`TerminalHandler`, `JSONHandler`, `LogfmtHandler`,
  `DiscardHandler`) and a `GlogHandler` (`log/handler_glog.go`) for
  per-module/`-vmodule` verbosity. Structured key-value logging throughout
  (`log.Info("msg", "key", val)`).
- `metrics/config.go:21` — `metrics.Config` + `DefaultConfig`; metrics are
  globally gated by `metrics.Enable()` (called from `utils.SetupMetrics`,
  `cmd/utils/flags.go:2232`) and served on a Prometheus/expvar HTTP endpoint
  (default `127.0.0.1:6060`) with optional InfluxDB v1/v2 push.

## Design decisions & rationale

- **Ordered start, reverse-ordered stop.** Registration order encodes the
  dependency DAG as a simple list; reverse teardown (`node.go:289`) means a
  service is never stopped while something registered *after* it (and thus
  potentially depending on it) is still running. No explicit dependency graph is
  needed — the list *is* the order. This is what makes shutdown safe for
  stateful services (DB, txpool journal) without bespoke coordination.
- **Container owns transports, services own logic.** The `Node` owns the P2P
  server and RPC stack; services contribute protocols and APIs but never open
  ports themselves. This centralizes port binding, CORS/vhost policy, JWT auth,
  and the single-instance datadir lock in one audited place, and lets the RPC
  surface be assembled from all services' `RegisterAPIs` contributions at once.
- **State-guarded registration.** `RegisterLifecycle/Protocols/APIs/Handler` all
  panic unless `state == initializingState`. Registration is a construction-time
  concern only; making it a hard panic (not a soft error) turns a whole class of
  wiring bugs into immediate, loud failures during boot rather than subtle races.
- **Rollback on partial start.** `Start` tracks `started []Lifecycle` and, on any
  service's start failure, stops exactly those already started and closes the
  node (`node.go:189-200`). No half-started node is ever left running.
- **Config layering with explicit precedence.** defaults → TOML file → flags
  (`loadBaseConfig`), with a single aggregate `gethConfig` that is also the
  `dumpconfig` output format. Deprecated fields are tolerated-with-warning
  (`deprecatedConfigFields`, `config.go:92`) rather than hard-erroring, easing
  upgrades. `AutoEnvVars` gives every flag a `GETH_*` env equivalent for
  container/enterprise deployment without code changes.
- **Signal handling lives in the CLI layer, not the container.** `node.Node`
  exposes `Close()`/`Wait()` and stays signal-agnostic; `cmd/utils.StartNode`
  owns the OS-signal → `Close` bridge. This keeps `Node` embeddable as a library
  (tests, simulators, tooling) without hijacking process signals.
- **Crash detection via DB marker.** The shutdown tracker gives operators a
  clear "was the last stop clean?" signal — important because an unclean stop can
  mean an inconsistent pruned state that warrants extra caution.
- **Disk-space watchdog triggers graceful shutdown.** Rather than letting the DB
  hit a hard write failure, `monitorFreeDiskSpace` self-injects `SIGTERM` so the
  normal reverse-order teardown flushes and closes cleanly.

## Notable patterns (the reusable idea)

**The Lifecycle service container.** A minimal two-method interface + a container
that (a) collects services via `Register*`, (b) starts them in registration order
after transports are up, (c) stops them in strict reverse order collecting all
errors, and (d) rolls back a partial start. Registration is guarded to
construction time; transports/resources are owned centrally; the run loop is a
thin CLI shell that bridges OS signals to `Close()` and blocks on `Wait()`.

Supporting reusable pieces:
- **Config layering as an explicit function** (defaults → file → flags) producing
  one aggregate config object that doubles as the serialization format.
- **Self-registration facets**: a service exposes `APIs()`, `Protocols()`, and
  `Start/Stop`, and registers all three with one call site — the container
  assembles the union.
- **Resource tracking with un-track on external close** (`closeTrackingDB`) so
  the container can auto-close leaked handles without double-closing ones the
  service already closed.
- **Crash-vs-clean detection** via a periodically-refreshed DB marker.

## Authority note

geth is the **canonical authority for the service-container / `Lifecycle`
ordered-start/stop pattern** and for the defaults→file→flags config-layering
model — document those here. For **plugin-based dependency injection** (a formal
DI container, plugin discovery/registration, per-plugin config), **nethermind is
the authority** — cross-reference its plugin/`INethermindApi` DI docs; do not
document DI-container design from geth, which deliberately keeps wiring as
explicit imperative Go in `makeFullNode` rather than a DI framework. For the
PoW/ETC-specific angle, core-geth inherits this exact `node.Node`/`Lifecycle`
structure, so the pattern transfers directly to the ETC path.

## Gotchas / anti-patterns / things they later changed

- **Registration-after-start panics, not errors.** `RegisterLifecycle` etc.
  `panic` if the node has already started. Any dynamic/late service addition is
  impossible by design — all wiring must complete before `Start()`. Porting this
  shape means the equivalent "register" phase must be strictly pre-start.
- **Flat list = implicit dependency ordering.** There is no explicit dependency
  declaration; correctness depends entirely on services being registered in the
  right order in `makeFullNode`. Reordering registrations silently changes
  start/stop order. This is simple but fragile — a real DI graph (nethermind)
  makes dependencies explicit at the cost of complexity.
- **Global mutable singletons.** Both logging (`log.SetDefault`/`Root()`) and
  metrics (`metrics.Enable()`, global registry) are process-global. Two nodes in
  one process share them. Fine for a single-node binary; a footgun for
  multi-node/embedded scenarios.
- **Light-mode `eth` can be nil.** `makeFullNode` guards `if eth != nil` (light
  mode) — a reminder that "the Ethereum service" is not guaranteed to exist; the
  container tolerates heterogeneous service sets.
- **`Stop` errors are collected, not fatal.** `stopServices` gathers per-service
  `Stop()` errors into a `StopError` but continues stopping everything; a failing
  service can't block the rest of teardown. Good for shutdown robustness, but
  means a `Stop` error is easy to miss unless the returned `StopError` is logged.
- **`DataDir()` deprecated in favor of `InstanceDir()`** (`node.go:641`) — files
  should live under the instance dir, not the raw datadir; a lingering API kept
  for compatibility.
- **Historical churn — the light-client service is gone.** The `LightServ/
  LightPeers/…` config fields survive only in `deprecatedConfigFields`
  (`config.go:92-103`) as tolerated-but-ignored TOML keys; LES was removed. A
  caution that the "roles" a container hosts shift over time, and config
  compatibility handling matters.

---

### Fukuii relevance (use-case lens)

fukuii uses Pekko actor supervision + HOCON config, so the mechanism differs, but
the *shape* transfers:

- **Enterprise / multi-network**: the container-owns-transports + config-layering
  model is exactly how one binary serves many roles/networks by varying which
  services register — map to a top-level supervisor that spawns a
  network/role-specific set of child actors chosen from HOCON, with ports/RPC
  owned centrally.
- **Custody**: ordered graceful shutdown (reverse-order stop, disk-space
  watchdog → SIGTERM, unclean-shutdown DB marker) is the anti-corruption story.
  fukuii's Pekko `CoordinatedShutdown` phases are the natural home for a
  registration-order-respecting teardown and a clean-shutdown marker.
- **Validator / light / archival-RPC**: same container, different registered
  service set + different flag/config profile — no branching inside the core.
