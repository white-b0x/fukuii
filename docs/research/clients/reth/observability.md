# reth — observability
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth's observability rests on three cleanly separated layers, each a small crate
delegating to a best-in-class Rust ecosystem library rather than a hand-rolled
framework:

1. **Metrics definition** — the `reth-metrics` crate (`crates/metrics/`) re-exports
   the `metrics` facade crate and the `metrics-derive` proc-macro (`Metrics`). Every
   subsystem declares a plain `struct` of metric-typed fields and slaps
   `#[derive(Metrics)]` + `#[metrics(scope = "...")]` on it. The macro generates a
   `Default` impl that registers each field (as a named `Gauge`/`Counter`/`Histogram`)
   with the global recorder at struct construction — **compile-time, type-safe metric
   registration**. This is the distinctive pattern.
2. **Metrics export** — the `reth-node-metrics` crate (`crates/node/metrics/`) installs
   a **Prometheus** recorder (`metrics-exporter-prometheus`) as the process-global sink
   and serves it over an HTTP `/metrics`-style endpoint (plus optional Prometheus
   push-gateway). The `metrics` facade decouples definition (layer 1) from export
   (layer 2): subsystems never know Prometheus exists.
3. **Tracing / OTLP** — the `reth-tracing` crate (`crates/tracing/`) builds a layered
   `tracing_subscriber` (stdout/file/journald), and `reth-tracing-otlp`
   (`crates/tracing-otlp/`) adds an **OpenTelemetry** span+log exporter layer that ships
   distributed traces to any OTLP backend (Jaeger, Zipkin, Tempo, …) over HTTP or gRPC.

The `metrics` facade is the load-bearing seam: instrumentation code depends only on the
facade, and the concrete recorder is chosen once at startup.

## Key types / interfaces / files

### Metrics — derive macro + facade
- `crates/metrics/src/lib.rs:17` — `pub use metrics_derive::Metrics;` — re-exports the
  `#[derive(Metrics)]` proc-macro (the macro crate itself is an external paradigmxyz
  dependency, not vendored under `crates/`).
- `crates/metrics/src/lib.rs:24` — `pub use metrics;` — re-exports the `metrics` facade
  (`Gauge`, `Counter`, `Histogram`, `Unit`, `describe_gauge!`, `gauge!` …), so callers
  depend on one crate (`reth-metrics`) for both.
- `crates/metrics/Cargo.toml:16-17` — the only two hard deps: `metrics` + `metrics-derive`.
- `crates/engine/tree/src/metrics.rs:7-12` — canonical usage: a `#[derive(Metrics)]`
  struct with `#[metrics(scope = "consensus.engine.beacon")]`; the field
  `active_block_downloads: Gauge` becomes the metric `consensus.engine.beacon.active_block_downloads`,
  and its `///` doc comment becomes the metric's description.
- `crates/net/network/src/metrics.rs:13` — `NetworkMetrics` under `#[metrics(scope = "network")]`;
  fields are a mix of `Gauge` and `Counter`. Instantiated simply via
  `NetworkMetrics::default()` (e.g. `crates/net/network/src/transactions/fetcher.rs:133`)
  — construction *is* registration.
- `#[metric(skip)]` (e.g. `crates/engine/tree/src/tree/metrics.rs:123`) — per-field
  attribute to exclude a non-metric helper field; `#[metric(rename = ...)]` /
  `#[metric(describe = ...)]` override the derived name/description.

### Prometheus export
- `crates/node/metrics/src/recorder.rs:57-60` — `PrometheusRecorder { handle, upkeep }`
  wraps a `PrometheusHandle`.
- `crates/node/metrics/src/recorder.rs:114-125` — `install_with_builder`: builds the
  recorder, wraps it in a `metrics_util` `Stack` with `PrefixLayer::new("reth")` (every
  metric gets a `reth` global prefix), and installs it as the single global recorder.
- `crates/node/metrics/src/recorder.rs:37` — `PROMETHEUS_RECORDER_HANDLE: OnceLock<…>` —
  install-once global; `install_prometheus_recorder()` (`:16`) is idempotent.
- `crates/node/metrics/src/recorder.rs:79-100` — `spawn_upkeep` runs a 5s Tokio loop
  calling `handle.run_upkeep()` (histogram bucket maintenance).
- `crates/node/metrics/src/server.rs:71-133` — `MetricServer::serve`: binds a TCP
  listener, on each request renders `handle.render()` (the Prometheus text exposition),
  and after install *describes* every dynamically-emitted metric family
  (`describe_db_metrics`, `describe_rocksdb_metrics`, `describe_static_file_metrics`,
  process/memory/io stats).
- `crates/node/metrics/src/server.rs:330-346` — `handle_request`: routes
  `/debug/pprof/heap` and `/debug/tokio/dump` to diagnostics; **any other path renders
  the metrics text** (the `/metrics` endpoint) with `Content-Type: text/plain`.
- `crates/node/metrics/src/server.rs:186-227` — optional Prometheus **push-gateway**
  task: `PUT`s rendered metrics to a configured URL on an interval.
- `crates/node/metrics/src/version.rs:23-34` — the `info`-metric idiom: a constant `1`
  gauge named `info` carrying build metadata as **labels** (`gauge!("info", &labels)`).

### Tracing + OTLP
- `crates/tracing/src/lib.rs` — `RethTracer` / `Layers` / `LogFormat`: composes
  stdout/file/journald `tracing_subscriber` layers; JSON or human format.
- `crates/tracing-otlp/src/lib.rs:39-76` — `span_layer(OtlpConfig)`: builds an
  `OpenTelemetryLayer` — sets a `TraceContextPropagator`, an OTLP `SpanExporter`
  (HTTP-protobuf or gRPC-tonic), a `Sampler`, and a batch-exporting `SdkTracerProvider`;
  returns a `tracing` layer that bridges spans → OpenTelemetry.
- `crates/tracing-otlp/src/lib.rs:82-113` — `log_layer(OtlpLogsConfig)` (feature
  `otlp-logs`): bridges `tracing` log events → OTLP logs via
  `OpenTelemetryTracingBridge`.
- `crates/tracing-otlp/src/lib.rs:117-181` — `OtlpConfig` (service name/version,
  endpoint `Url`, protocol, sample ratio); `sample_ratio` validated to `0.0..=1.0`.
- `crates/tracing-otlp/src/lib.rs:293-302` — `build_sampler`: `None`/`1.0` → sample all,
  `0.0` → none, else `ParentBased(TraceIdRatioBased(ratio))`.
- `crates/tracing-otlp/src/lib.rs:305-349` — `OtlpProtocol` (`Http` port 4318 needs
  `/v1/traces`; `Grpc` port 4317 must *not*); `validate_endpoint` auto-corrects the path.
- `crates/tracing-otlp/src/lib.rs:244-290` — credential handling: strips
  `user:password` from the endpoint URL and re-encodes them into an
  `Authorization: Basic` OTLP header env var — so credentials never leak into span
  resource attributes or logs.
- `crates/node/core/src/args/trace.rs:17-118` — `DefaultTraceValues` + clap args:
  `--tracing-otlp`, `--logs-otlp`, protocol, service name/version, per-signal filter,
  sample ratio; embeddable binaries can override defaults before CLI parse via `try_init`.

## Design decisions & rationale

- **Facade, not framework.** Instrumentation depends on the `metrics` facade; the
  recorder is chosen once at boot. reth could swap Prometheus for another sink without
  touching a single instrumented subsystem. (`reth-metrics` deliberately has only two
  deps.)
- **Registration = construction.** Because `#[derive(Metrics)]` generates `Default`,
  a subsystem holding a `SomeMetrics` field gets its metrics registered for free the
  moment it is built — no separate "register these metrics" call to forget, no static
  registry to keep in sync.
- **Doc comment is the metric description.** The `///` on each field is lifted verbatim
  into the Prometheus `# HELP` text, so metric docs can't drift from code docs.
- **Scope prefix + global prefix.** `#[metrics(scope = "network")]` namespaces a struct's
  metrics; `PrefixLayer::new("reth")` prefixes everything at the recorder — giving stable,
  hierarchical names (`reth_network_connected_peers`).
- **`info`-gauge for cardinality-free metadata.** Version/chain-spec/storage info is
  emitted as a constant-`1` gauge with labels, the standard Prometheus idiom for exposing
  build/config metadata without inventing per-field metrics.
- **OTLP behind feature flags.** `otlp` / `otlp-logs` (`crates/tracing-otlp/Cargo.toml:31-45`)
  keep the heavy OpenTelemetry dependency tree opt-in; the whole crate is `#![cfg(feature = "otlp")]`.
- **Sampling + batching for enterprise scale.** Parent-based ratio sampling and a
  batch span exporter make full distributed tracing affordable at production volume.
- **Diagnostics share the metrics port.** `pprof` heap dumps and `tokio` task dumps are
  served off the same HTTP server, so a single exposed port covers metrics + on-demand
  profiling.

## Notable patterns (the reusable idea)

**Compile-time, type-safe metric registration via a derive macro over a facade.** A
struct of typed metric fields (`Gauge`/`Counter`/`Histogram`), annotated
`#[derive(Metrics)] #[metrics(scope="...")]`, becomes a fully-registered metric set whose
names derive from field names, whose descriptions derive from doc comments, and whose
registration happens at construction — all checked by the compiler. This is the Rust
analog of nethermind's attributed static `Metrics` classes, but reth resolves the
mapping at **compile time with zero reflection**: a typo'd metric type won't compile, and
there is no runtime scrape of attributed fields. Sitting on the `metrics` *facade* means
the export backend (Prometheus here) is a one-line boot-time choice, fully decoupled from
every instrumented call site.

For fukuii (Scala/JVM), the transferable shape is: define metric sets as data
(case-class-of-fields or an enum), register them once at construction against a single
facade (e.g. Micrometer / dropwizard behind one interface), derive names from the field
identifiers and descriptions from a doc/annotation, and pick the exporter (Prometheus)
exactly once at startup — rather than scattering `registry.counter("literal.string")`
calls that drift and can't be checked.

## Authority note

reth is the reference for **derive-macro compile-time metrics** (`#[derive(Metrics)]`
over the `metrics` facade) and for a **clean `tracing-otlp` OpenTelemetry integration**
(HTTP/gRPC span+log export, ratio sampling, credential-stripping). Peers cover the same
slot differently: **nethermind** uses attributed *static* `Metrics` classes scraped via
reflection (runtime, .NET); **besu** wires OpenTelemetry + a Prometheus registry through
DI; **erigon** exposes a Go `diagnostics`/pprof + Prometheus surface. For fukuii's
enterprise/archival use case, reth's derive-macro metrics represent the **type-safe,
zero-reflection end** of the design space vs nethermind's reflection-scrape — and its
OTLP layer is the model for distributed-tracing export a JVM client would mirror with
Micrometer + the OpenTelemetry Java agent.

## Gotchas / anti-patterns / things they later changed

- **Recorder must be installed before any metric is *described*** (`recorder.rs:8-13`).
  `install()` only sets the global recorder — it does **not** spawn the exporter or the
  upkeep loop; callers must separately call `spawn_upkeep()` (`recorder.rs:79`) from
  inside a Tokio runtime (it panics otherwise) and stand up the `MetricServer`. Three
  distinct steps that are easy to half-wire.
- **Install-once global via `OnceLock`.** A second install attempt panics
  (`init_prometheus_recorder`, `recorder.rs:30-33`) — fine for a node binary, a trap for
  tests/embedders (hence the idempotent `install_prometheus_recorder()` getter).
- **`metrics`-crate version skew is a real hazard.** The test at `recorder.rs:128-146`
  exists specifically because deps built against different `metrics` versions (0.21 vs
  0.22) silently fail to talk through the global recorder — a facade-decoupling cost.
- **The `/metrics` route is a catch-all**, not an exact match: `handle_request`
  (`server.rs:336-346`) serves metrics for *any* path except the two `/debug/...`
  diagnostic routes.
- **OTLP endpoint path/protocol coupling** (`lib.rs:318-348`): HTTP silently *appends*
  `/v1/traces`; gRPC *rejects* a URL that includes it. Misconfiguring protocol vs URL is
  a common OTLP footgun that reth guards against but still surfaces as an error.
- **`unsafe { std::env::set_var }`** is used to inject the OTLP auth header
  (`lib.rs:283-288`) — safe only because it runs at startup before exporter worker
  threads spawn; a load-bearing ordering assumption documented in a `// SAFETY:` note.
