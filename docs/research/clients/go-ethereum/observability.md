# go-ethereum — observability
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary

geth does **not** depend on the Prometheus client library for its internal
instrumentation. Instead it ships its **own self-contained metrics library**
(`metrics/`) — a hard fork of Coda Hale's `rcrowley/go-metrics` (see
`metrics/FORK.md:1`, forked at rcrowley commit `e181e09`) — that defines a small
set of metric primitives (`Counter`, `Gauge`, `Meter`, `Timer`, `Histogram`,
`Healthcheck`) held in a process-global `Registry`. Instrumentation code
throughout the client (`p2p/`, `core/txpool/`, chain, db) registers a metric by
**string name** at package-init time and mutates it in the hot path. The
registry is then read by **pluggable exporters** that translate the same
in-memory metric set into different wire formats on demand:

- **Prometheus** (`metrics/prometheus/`) — HTTP scrape endpoint, text exposition.
- **expvar** (`metrics/exp/`) — Go's stdlib `/debug/vars`-style JSON.
- **InfluxDB v1 & v2** (`metrics/influxdb/`) — push, on a timer.

So there is one instrumentation API and one registry, and the choice of
monitoring backend is a startup/flags decision, not a code decision.

Separately, `ethstats/` is a **telemetry reporter** (not a metrics exporter): it
opens a WebSocket to an ethstats dashboard server and pushes node/block/pending
stats as JSON events (the classic fleet "netstats" green-dashboard).

The whole subsystem is **opt-in and gated on a global flag** — `metrics.Enabled()`
(`metrics/metrics.go:19`) is checked by expensive meters before doing work, and
is flipped once at startup by `metrics.Enable()` (`metrics/metrics.go:31`).

## Key types / interfaces / files

**The metrics library (the fork):**
- `metrics/FORK.md:1` — declares the fork of `rcrowley/go-metrics` at `e181e09`.
- `metrics/metrics.go:19` — `Enabled()`; `metrics.go:31` `Enable()` flips the
  global on and starts the meter ticker loop. Toggling on-then-off is explicitly
  unsupported; must be called once, early.
- `metrics/registry.go:20` — `Registry` interface: `Each`, `Get`, `GetAll`,
  `GetOrRegister`, `Register`, `RunHealthchecks`, `Unregister`. `NewRegistry()`
  at `registry.go:63`; a process-global `DefaultRegistry` is what everything uses.
- `metrics/counter.go:38` — `Counter` (an `atomic.Int64`); `NewCounter()` :17,
  `GetOrRegisterCounter()` :9. Snapshot type `CounterSnapshot` :32.
- `metrics/gauge.go:36` — `Gauge` (atomic int); also `GaugeFloat64`, `GaugeInfo`.
- `metrics/meter.go:70` — `Meter` (moving-average rate: 1/5/15-min + mean, EWMA);
  `startMeterTickerLoop()` :168 drives a shared arbiter ticker updating all meters.
- `metrics/timer.go:51` — `Timer` (a Meter + Histogram: count, rates, percentiles);
  plus `ResettingTimer` and `runtimehistogram.go` for Go runtime histograms.
- `metrics/histogram.go:17` — `Histogram` interface; sampling in `metrics/sample.go`
  (`NewExpDecaySample`, `NewUniformSample` — reservoir sampling for percentiles).
- `metrics/healthcheck.go:11` — `Healthcheck`: a stored `error` + an update
  function, `Healthy()`/`Unhealthy(err)`/`Check()`. Run via `Registry.RunHealthchecks()`.
- `metrics/metrics.go:108` — `CollectProcessMetrics(refresh)` samples CPU, disk I/O,
  memory and Go runtime (GC pauses, heap, goroutines, sched latency) into registered
  gauges; started by `SetupMetrics` every 3s.
- `metrics/config.go:21` — `Config` struct (Enabled, HTTP/Port, InfluxDB v1/v2
  fields); `DefaultConfig` :41 (disabled; HTTP `127.0.0.1:6060`).

**Exporters:**
- `metrics/prometheus/prometheus.go:29` — `Handler(reg)` returns an `http.Handler`
  that sorts metric names, aggregates via a `collector`, and writes text
  exposition. `metrics/prometheus/collector.go:51` `Add()` type-switches each
  metric to the right Prometheus type (gauge/counter/summary with quantiles).
- `metrics/exp/exp.go:39` — `Exp(r)` registers `/debug/metrics` (expvar JSON) **and**
  `/debug/metrics/prometheus` on the default mux; `exp.go:56` `Setup(address)`
  starts a dedicated metrics HTTP server exposing both endpoints.
- `metrics/influxdb/influxdb.go` — `InfluxDBWithTags(...)` (v1) and
  `InfluxDBV2WithTags(...)` push the registry to InfluxDB on an interval,
  namespaced `geth.` with tag maps.
- `metrics/opentsdb.go`, `metrics/syslog.go` — additional inherited reporters
  (OpenTSDB, syslog) carried from the upstream go-metrics fork.

**Flags / wiring:**
- `cmd/utils/flags.go:983` — `MetricsEnabledFlag` (`--metrics`), `:992`
  `MetricsHTTPFlag`, `:997` `MetricsPortFlag`, `:1004`+ the `--metrics.influxdb*`
  and `--metrics.influxdbv2*` flag block.
- `cmd/utils/flags.go:2232` — `SetupMetrics(cfg)`: the single wiring point — calls
  `metrics.Enable()`, spawns the InfluxDB v1/v2 push goroutine, calls
  `exp.Setup(address)` for the HTTP endpoint, and launches
  `CollectProcessMetrics(3s)`.
- `internal/debug/flags.go:105` — `--pprof` / `--pprof.addr` / `--pprof.port`
  (default `127.0.0.1:6060`), plus `--pprof.memprofilerate`, `.blockprofilerate`,
  `.cpuprofile`. `internal/debug/flags.go:320` `StartPProf(address, withMetrics)`
  imports `net/http/pprof` (blank import :25) and, if metrics are on, also mounts
  `exp.Exp(DefaultRegistry)` so `/debug/metrics` rides on the pprof server.
- `internal/debug/trace.go`, `internal/debug/pyroscope.go` — Go execution tracing
  and optional Pyroscope continuous-profiling hooks; `internal/debug/api.go` is
  the `debug_*` RPC namespace (start/stop CPU profile, gc stats, etc.).

**ethstats telemetry:**
- `ethstats/ethstats.go:84` — `Service` struct (p2p server, backend, consensus
  engine, node name/pass/host, pong & history channels, event subscriptions).
- `ethstats/ethstats.go:176` — `New(node, backend, engine, url)` parses
  `nodename:secret@host` via `parseEthstatsURL` :154 and registers a node
  lifecycle service.
- `ethstats/ethstats.go:222` — `loop(...)` subscribes to `ChainHeadEvent`,
  `NewTxsEvent`, `NewPayloadEvent`, dials the dashboard over `gorilla/websocket`
  (:296, 5s handshake timeout), and drives a **15s full-report ticker** (:329)
  plus event-driven per-block reports.
- `ethstats/ethstats.go:486` — `login()` sends the `hello`/`authMsg` with node
  info + secret and waits for a `ready` ack. `report()` :533 fans out to
  `reportLatency` (WS ping RTT) :551, `reportBlock` :650 (`blockStats` :590),
  `reportPending` :778, and `reportStats` :807 (`nodeStats` :797: active, syncing,
  peers, gasPrice, uptime).

## Design decisions & rationale

- **Vendor the metrics library rather than depend on Prometheus client.** By
  forking go-metrics, geth owns a stable, minimal metric API decoupled from any
  single backend. Instrumentation authors never touch Prometheus/Influx types;
  they call `metrics.NewRegisteredMeter("txpool/pending/discard", nil)`. The
  format is decided at the exporter edge. This is the load-bearing decision:
  **one instrumentation surface, many pluggable sinks.**
- **String-named metrics with a slash hierarchy.** Names like `p2p/ingress`,
  `p2p/dials/error/connection`, `txpool/pending/discard`, `txpool/reorgtime`
  (see `p2p/metrics.go:40-61`, `core/txpool/legacypool/legacypool.go:85-115`)
  encode a subsystem tree in the name itself. The Prometheus exporter rewrites
  `/` and `.` into valid Prometheus names.
- **Global gate for cheap disable.** `Enabled()` is checked inside expensive
  meter updates so that when `--metrics` is off, the hot path pays almost
  nothing — metrics are near-zero-cost when disabled, and there's no compile-time
  split.
- **`GetOrRegister*` for threadsafe idempotent registration** (`registry.go:344`,
  and per-type helpers) — bare `Register` is not threadsafe; the `GetOrRegister`
  family is the sanctioned path.
- **Two HTTP mount points, deliberately separated.** pprof (`net/http/pprof` on
  its own server) and the metrics endpoint are conceptually different but geth
  co-mounts `/debug/metrics` onto the pprof server when both are enabled, while
  `exp.Setup` can also stand up a dedicated metrics-only server — so operators can
  expose metrics without exposing pprof.
- **ethstats is push, not scrape.** It targets a community dashboard model
  (WebSocket to a central netstats server) rather than a pull-based monitoring
  system; it reports semantic node/block state, not the raw metric registry.

## Notable patterns (the reusable idea)

The single most transferable idea: **a self-contained, backend-agnostic metrics
registry with a `GetOrRegister(name)` API and pluggable exporters**. Domain code
depends only on a tiny in-house metric abstraction (`Counter/Gauge/Meter/Timer/
Histogram`) keyed by a slash-delimited name; the concrete monitoring backend
(Prometheus text, expvar JSON, InfluxDB push) is a thin translation layer that
walks the registry via `Each`/`Get` and is selected by flags at startup. Adding a
new backend never touches instrumentation; toggling `Enabled()` makes the whole
system near-free when off. Secondary reusable pattern: **push-based fleet
telemetry over WebSocket** (ethstats) as a separate, semantic reporter distinct
from the raw metric plane.

## Authority note

For fukuii's **observability** slot, geth is the authority for two specific
patterns: (1) the **self-contained metrics library + pluggable-exporter**
architecture (the go-metrics fork with Prometheus/InfluxDB/expvar sinks), and
(2) the **ethstats WebSocket telemetry** reporter — both are distinctive to the
geth lineage and directly relevant because fukuii inherits the ETC/mining-pool
ecosystem where ethstats dashboards are common. Note the contrast for
cross-referencing: **nethermind and reth do not fork a metrics library** — they
consume standard, off-the-shelf Prometheus client libraries (and increasingly
OTLP/OpenTelemetry) and expose a conventional `/metrics` scrape endpoint. So when
documenting those clients' observability slots, expect "standard Prometheus/OTLP
exporter" rather than geth's in-house registry. fukuii on the JVM would most
naturally reach for a JVM-standard metrics facade (Micrometer / Dropwizard
Metrics — itself the Coda Hale library geth's fork descends from) with a
Prometheus registry, i.e. it gets geth's *pattern* (registry + pluggable
exporter) for free from the JVM ecosystem without needing to vendor the library.
fukuii's enterprise/multi-network use case (production monitoring for
archival/enterprise via Prometheus+pprof-equivalent JVM profiling; fleet
dashboards via ethstats for the mining-pool community; liveness/health for
custody) needs this observability plane as a first-class subsystem, not an
afterthought.

## Gotchas / anti-patterns / things they later changed

- **`Register` is not threadsafe; meters/timers leak if not unregistered.** The
  upstream README (`metrics/README.md`) explicitly warns that short-lived Meters
  and Timers leak memory unless `Unregister`ed (they hold a goroutine-driven EWMA
  tick). Use `GetOrRegister*` and prefer long-lived, package-global metrics.
- **`Enable()` is one-way and not concurrency-safe.** Must be called once at
  startup before any collection; toggling off-then-on is unsupported
  (`metrics/metrics.go:26-31`). A late `Enable()` misses early metrics.
- **`--metrics.port` without `--metrics.addr` silently does nothing** — `SetupMetrics`
  logs a warning and does not start the server (`cmd/utils/flags.go:2270`). Easy
  operator footgun.
- **`--metrics.influxdb` and `--metrics.influxdbv2` are mutually exclusive** — geth
  `Fatalf`s if both are set (`cmd/utils/flags.go:2244`).
- **Exposure risk:** the metrics/expvar and pprof endpoints default to
  `127.0.0.1` for a reason — `/debug/pprof` and `/debug/vars` leak internal state
  and allow CPU/heap profiling; never bind to `0.0.0.0` on an untrusted network.
  The `EnabledExpensive` config flag (`metrics/config.go:23`, `toml:"-"`) is a
  vestige of a coarser cheap/expensive split.
- **ethstats reports a hardcoded `Client: "0.1.1"` and `Uptime: 100`**
  (`ethstats/ethstats.go:511`, `:840`) — these are placeholder/legacy values in the
  netstats protocol, not real telemetry; don't trust them as node metadata.
- **Forked-library drift:** because `metrics/` is a hard fork frozen at
  rcrowley `e181e09`, upstream go-metrics fixes do not flow in automatically;
  geth has repeatedly modernized it in-tree (atomic-based Counter/Gauge, added
  `GaugeInfo`, `ResettingTimer`, Go-runtime histograms, the Prometheus/InfluxDBv2
  exporters). A consumer copying the pattern should expect to maintain the
  library, not track an external dependency.
