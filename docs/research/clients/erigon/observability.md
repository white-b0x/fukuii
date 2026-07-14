# erigon — observability
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon splits operational visibility into **three distinct layers**, which is the
distinctive thing worth copying:

1. **Raw metrics** — a self-contained Prometheus registry (`diagnostics/metrics/`)
   exposed at `http://<metrics.addr>:<metrics.port>/debug/metrics/prometheus`, plus
   `pprof` on a parallel (optionally shared) mux, plus optional Pyroscope continuous
   profiling and a `/health` JSON endpoint on the RPC daemon.
2. **A dedicated diagnostics subsystem** (`diagnostics/`, package `diaglib`) — a
   structured, *semantic* diagnostics stream that is richer than raw counters: typed
   snapshots of every sync stage/sub-stage, per-peer network statistics, snapshot
   (torrent) download progress, block-execution throughput, and hardware/resource
   usage (disk, RAM, CPU, per-process). This is the "support desk" layer.
3. **Shipped Grafana dashboards** (`dashboards/`) — versioned dashboard JSON checked
   into the repo so any operator gets a first-class visualization out of the box.

The diagnostics subsystem is a **publish/subscribe fan-in** design. Instrumented code
anywhere in the tree calls `diaglib.Send(someTypedInfo)` (fire-and-forget, non-blocking,
drops on backpressure). A `DiagnosticClient` owns an HTTP mux and exposes a WebSocket
endpoint `/ws`; the external Erigon diagnostics UI / `erigon support` tunnel dials in
and receives a live typed stream. If nothing is connected, `Send`/`Notify` silently
no-op — instrumentation cost is near-zero when unobserved.

## Key types / interfaces / files

Diagnostics subsystem (the distinctive part):

- `diagnostics/diaglib/client.go:26` — `DiagnosticClient` struct: owns `metricsMux
  *http.ServeMux`, the tracked `syncStages []SyncStage`, and the single `conn
  *websocket.Conn` (one support client at a time).
- `diagnostics/diaglib/notifier.go:23` — `SetupNotifier()` registers `/ws` on the
  existing metrics mux; `Notify(msg DiagMessages)` (`:32`) writes JSON to the connected
  support client, or discards silently if none. `HandleConnections` (`:52`) upgrades the
  socket via `github.com/coder/websocket`. This is the **support-desk tunnel**.
- `diagnostics/diaglib/provider.go:80` — the `Provider` / `Info` / `Type` registry:
  `Send[I Info](info I)` (`:134`) is the non-blocking publish primitive — `select { case
  c <- info: default: /* drop */ }` (`:154`) so a busy receiver never blocks the node.
- `diagnostics/diaglib/stages.go:28` — `SyncStage` / `SyncSubStage` / `SyncStageStats`
  (`timeElapsed`, `timeLeft`, `progress`) — **per-stage sync diagnostics beyond raw
  metrics**. `StageState` enum Queued/Running/Completed (`:66`).
- `diagnostics/diaglib/entities.go:24` — `PeerStatistics`; `:75` `SyncStatistics`
  (snapshot download/indexing/fill-DB); `:160` `HardwareInfo` = `DiskInfo` + `RAMInfo` +
  `[]CPUInfo`. Rich structured resource/peer/snapshot telemetry.
- `diagnostics/diaglib/network.go:141` — `PeerStats.GetPeers()` per-peer in/out byte
  and message-type accounting.
- `diagnostics/diaglib/block_execution.go:28` — `BlockExecutionStatistics`
  (`blkPerSec`, `txPerSec`, `mgasPerSec`, `alloc`, `sys`) — execution throughput.
- `node/eth/backend.go:72` (import) and `:1436` — the backend emits the stage list:
  `diaglib.Send(diaglib.SyncStageList{StagesList: diaglib.InitStagesFromList(...)})`.
  Instrumentation is a plain function call at the call site, decoupled from transport.
- `diagnostics/sysutils/sysutils.go:53` — `GetProcessesInfo()` per-process CPU/RAM;
  `diagnostics/mem/mem.go`, `diagnostics/diskutils/diskutils_{linux,darwin,windows}.go`
  — OS-specific resource probes; `diagnostics/syscheck/syscheck.go:22`
  `CheckKernelAllocationHints` — startup environment sanity check.

Raw metrics / profiling / health:

- `diagnostics/metrics/setup.go:33` — `Setup(address, logger)`: registers `defaultSet`
  with `prometheus.DefaultRegisterer`, serves `/debug/metrics/prometheus` via
  `promhttp.Handler()` on a dedicated goroutine server.
- `diagnostics/metrics/register.go:35` — `NewCounter` / `GetOrCreateCounter` /
  gauges / histograms / summaries / timers — a Prometheus-compatible metric façade
  (`foo{bar="baz"}` label syntax) used everywhere (`execution/metrics/`,
  `execution/stagedsync/`, `execution/engineapi/metrics.go`, `node/shards/`, etc.).
- `node/debug/flags.go:67` — `metrics`, `metrics.addr`/`metrics.port`, `pprof`,
  `pprof.addr`/`pprof.port` flags; `Setup(...)` (`:239`) wires metrics + pprof (shared
  mux when addresses match) + Pyroscope; `SetupCobra` (`:135`) starts periodic
  `mem.LogMemStats` and `disk.UpdateDiskStats` goroutines and near-OOM heap dumps.
- `cmd/rpcdaemon/health/health.go:53` — `ProcessHealthcheckIfNeeded`: `/health`
  endpoint with composable checks — `synced`, `min_peer_count`, `check_block`,
  `max_seconds_behind` — driven via the `X-ERIGON-HEALTHCHECK` header or a JSON body.

Dashboards:

- `dashboards/erigon_custom_metrics/erigon_custom_metrics.internal.json` — 36 panels:
  snapshot storage, block execution, per-txn timings/breakdown, state I/O rates,
  commitment internals, DB, Process, RPC, Network, TxPool, Shutter pools.
- `dashboards/erigonQA/erigonQA.internal.json` — 53 panels: cross-network
  (Ethereum/Gnosis/Polygon/Holesky/Sepolia/Amoy) sync-from-scratch time, dir size,
  time-to-tip, per-block size — a **release-regression QA dashboard**.

## Design decisions & rationale

- **Semantic diagnostics distinct from raw metrics.** Prometheus counters answer "how
  many / how fast"; `diaglib` answers "what is the node *doing right now*" — which sync
  stage, which sub-stage, % progress, ETA, which peers, snapshot download state. That
  domain-shaped view is what a support engineer actually needs, and it can't be
  reconstructed from scalar time series.
- **Non-blocking, drop-on-busy publish.** `Send` uses a buffered channel with a
  `default` drop branch (`provider.go:154`) and a `recover()` guard (`:135`), so
  instrumentation can never stall or crash a hot path. Observability is strictly
  best-effort by construction.
- **Transport decoupled from instrumentation.** Call sites only know `diaglib.Send`;
  they don't know about WebSocket, the UI, or whether anyone is listening. The
  `DiagnosticClient`/`Notify` transport can change without touching instrumented code.
- **Support tunnel over WebSocket, pull-shaped.** The node exposes `/ws`; the external
  diagnostics UI connects *inbound* to the operator's node. One connection at a time,
  `InsecureSkipVerify` on the upgrade (intended for a locally-bridged/tunneled support
  session, not public exposure).
- **Dashboards as versioned repo artifacts.** Shipping the Grafana JSON in-tree means
  the dashboards evolve lockstep with the metrics they read, and the QA dashboard
  doubles as a performance-regression gate across networks.
- **Composable, header-driven health checks.** `/health` lets an orchestrator
  (k8s/LB probe) assemble exactly the liveness/readiness predicate it wants (synced +
  N peers + block freshness) rather than a fixed boolean.

## Notable patterns (the reusable idea)

**The single most transferable idea: a dedicated, semantic diagnostics subsystem that
sits *above* raw metrics** — a fire-and-forget `Send(typedEvent)` publish primitive
whose transport is a decoupled support-desk tunnel, feeding a live view of sync
stages / peers / resource usage that no scalar metric can express.

Supporting reusable pieces:
- Non-blocking best-effort publish (buffered channel + `default` drop + `recover`) so
  instrumentation is safe to sprinkle on hot paths.
- Instrumentation call-site decoupled from transport (call sites only import a `Send`).
- Health endpoint as a **composable check set** selected by header/body, not a fixed
  status.
- Dashboards versioned in-repo alongside the metrics, including a cross-network
  regression-QA dashboard.

For fukuii's enterprise/multi-network use case, a `Diagnostics`-style subsystem
(typed per-stage sync + peer + resource events over a single support channel, plus a
Prometheus registry and shipped Grafana JSON) would give operators built-in
operational visibility and a support-tunnel differentiator — value that plain
Prometheus scraping alone does not deliver.

## Authority note

For the observability slot, **erigon is the built-in-diagnostics-subsystem +
shipped-dashboards reference** — the `diagnostics/diaglib` semantic stream + support
tunnel and the in-repo Grafana JSON are the distinctive assets. **besu** is the
OTel/Prometheus JVM peer (OpenTelemetry tracing + Micrometer/Prometheus on the JVM),
closest to fukuii's own runtime. **geth** is the self-contained-metrics-library
reference (its own `metrics` package with expvar/InfluxDB/Prometheus exporters).
Erigon's metrics façade (`diagnostics/metrics/`) is itself a geth-lineage
self-contained registry; the diaglib layer and dashboards are what set it apart.

## Gotchas / anti-patterns / things they later changed

- **`/ws` upgrade uses `InsecureSkipVerify: true`** (`notifier.go:53`) and diagnostics
  bind alongside the metrics mux — this is designed for a local/tunneled support
  session, **not** for public exposure. Don't expose the diagnostics/metrics port to
  untrusted networks.
- **Single support connection.** `DiagnosticClient.conn` is one socket
  (`client.go:31`); a second connector replaces or contends — it's a support-desk
  bridge, not a fan-out telemetry bus.
- **Diagnostics is lossy by design.** `Send` drops messages under backpressure
  (`provider.go:154`); never treat the diagnostics stream as an authoritative/lossless
  audit log — it's an operational snapshot.
- **The diagnostics *UI* lives in a separate repo** (`erigontech/diagnostics`); the
  in-tree `diaglib` is only the node-side provider + WebSocket server. The CHANGELOG
  (`diagnostics/CHANGELOG.md`) references HTTP endpoints (`logs/list`, `db/read`,
  `cmdline`, versioning) that are consumed/served by that external UI — don't expect the
  full support-desk HTTP surface inside this tree.
- **Typo carried in the public type name**: `BlockEexcStatsData` /
  `BlockEexcStatsData` (`block_execution.go:23`) — a reminder these diagnostic structs
  are serialized JSON contracts, so even typos become semi-stable API.
- Metrics + pprof **share a mux when addresses coincide** (`flags.go:216`); if you set
  `pprof.addr`==`metrics.addr` you get both on one server, otherwise two — easy to
  misconfigure into an unexpected extra listener.
