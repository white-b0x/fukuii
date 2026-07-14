# besu — observability
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

## Architecture summary

besu treats observability as a **single provider-agnostic instrumentation API
(`MetricsSystem`) backed by two interchangeable export protocols** — Prometheus
(pull/push) and OpenTelemetry (OTLP push, metrics *and* tracing). Application and
plugin code depends only on the `MetricsSystem` plugin-API interface; the concrete
backend is chosen once at startup from `MetricsConfiguration.getProtocol()` by
`MetricsSystemFactory.create(...)`. This decouples the ~19 instrumented subsystems
(blockchain, sync, txpool, RPC, peers, RocksDB, JVM…) from *how* metrics leave the
process. Metrics are namespaced by a `MetricCategory` (an open, plugin-extensible
enum-style abstraction), and a no-op backend is substituted transparently when
metrics — or an individual category — are disabled, so instrumentation call sites
never need null-guards. A separate, lightweight HTTP `/liveness` + `/readiness`
health surface (Vert.x, not part of the metrics system) covers orchestration probes,
and OTel `Tracer` spans instrument the JSON-RPC request path for distributed tracing.

## Key types / interfaces / files

- `plugin-api/.../plugin/services/MetricsSystem.java:35` — the core provider-agnostic
  factory interface. Rich API: `createLabelledCounter`, `createLabelledTimer`,
  `createSimpleLabelledTimer`, `createLabelledHistogram`, `createLabelledSuppliedGauge`
  /`SuppliedCounter`/`SuppliedSummary` (pull-from-supplier metrics), `createGuavaCacheCollector`,
  plus `getEnabledCategories()`/`isCategoryEnabled()`. Default methods collapse the
  unlabelled cases onto the labelled ones. **This is the seam every subsystem codes against.**
- `plugin-api/.../plugin/services/metrics/LabelledMetric.java:23` — `T labels(String...)`;
  the one-method handle that turns a metric definition + label values into a concrete
  `Counter`/`OperationTimer`/`Histogram` you increment/observe.
- `plugin-api/.../plugin/services/metrics/MetricCategory.java:26` — `getName()` +
  `Optional<String> getApplicationPrefix()`. The extension point: any implementer (core
  enum or plugin) can define a category. Categories group metrics 1:1.
- `plugin-api/.../plugin/services/metrics/MetricCategoryRegistry.java:25` — the plugin
  service that lets a plugin `addMetricCategory(...)` during init so custom categories are
  recognised and can be enabled/exposed; `isMetricCategoryEnabled(...)`.
- `metrics/core/.../metrics/ObservableMetricsSystem.java:23` — extends `MetricsSystem` with
  `streamObservations([category])` + `shutdown()`; the internal (non-plugin) view used for
  debug inspection and for scraping observations back out.
- `metrics/core/.../metrics/MetricsSystemFactory.java:52` — the switch: `NoOpMetricsSystem`
  when disabled, else `PrometheusMetricsSystem` or `OpenTelemetrySystem` per `MetricsProtocol`.
  Also disables `GlobalOpenTelemetry` when OTel isn't the active protocol.
- `metrics/core/.../metrics/MetricsProtocol.java:20` — `PROMETHEUS | OPENTELEMETRY | NONE`.
- `metrics/core/.../metrics/StandardMetricCategory.java:22` — `JVM`, `PROCESS` (provider-neutral,
  no `besu_` prefix).
- `metrics/core/.../metrics/BesuMetricCategory.java:26` — the domain categories: `BLOCKCHAIN`,
  `ETHEREUM`, `EXECUTORS`, `NETWORK`, `PEERS`, `PERMISSIONING`, `KVSTORE_ROCKSDB(_STATS)`,
  `PRUNER`, `RPC`, `SYNCHRONIZER`, `TRANSACTION_POOL`, `BLOCK_PROCESSING`, `BAL`, `BONSAI_CACHE`.
  Carries a `besu_` application prefix and a curated `DEFAULT_METRIC_CATEGORIES` set (RocksDB
  stats categories excluded by default — see gotchas).
- `metrics/core/.../metrics/prometheus/PrometheusMetricsSystem.java:54` — Prometheus backend on
  the modern `io.prometheus.metrics.model.registry.PrometheusRegistry`; registers JVM/process
  collectors in `init()`; per-`(category,name)` caching; `registerCollector` re-registers idempotently.
- `metrics/core/.../metrics/prometheus/MetricsHttpService.java:39` — the `/metrics` scrape endpoint
  via the Prometheus `HTTPServer`, with a host-allowlist `Authenticator`. `MetricsPushGatewayService`
  is the push-gateway variant.
- `metrics/core/.../metrics/opentelemetry/OpenTelemetrySystem.java:76` — OTel backend built on
  `AutoConfiguredOpenTelemetrySdk` (reads standard `OTEL_*` env/system props for the OTLP endpoint,
  exporters, resource attrs). Holds both an `SdkMeterProvider` **and** an `SdkTracerProvider`
  (`getTracerProvider()`), does its own JVM GC/memory instrumentation via `ManagementFactory` MXBeans,
  and `shutdown()` flushes exporters.
- `metrics/core/.../metrics/opentelemetry/MetricsOtelPushService.java:26` — the `MetricsService`
  lifecycle wrapper for the OTel push path (start/stop → `shutdown()` flush).
- `ethereum/api/.../jsonrpc/health/HealthService.java:22` — `/liveness` + `/readiness` HTTP health,
  a `HealthCheck` functional interface returning `HealthCheckResult(healthy, JsonObject details)`;
  `LivenessCheck.java` / `ReadinessCheck.java` are the concrete probes. **Separate from the metrics
  port**, on the JSON-RPC HTTP server.
- `ethereum/api/.../handlers/JsonRpcExecutorHandler.java` / `AbstractJsonRpcExecutor.java:48` /
  `JsonRpcObjectExecutor.java:78` — the JSON-RPC request path takes an OTel `Tracer`, extracts the
  incoming span `Context` (`SPAN_CONTEXT`), and wraps method execution in spans — request-level
  distributed tracing, not just aggregate metrics.

## Design decisions & rationale

- **One instrumentation API, swappable exporters.** Call sites depend on `MetricsSystem`; the
  Prometheus-vs-OTel decision is a single startup config knob. Neither the exporter choice nor
  "metrics off" leaks into subsystem code.
- **No-op substitution instead of conditionals.** When metrics or a category are disabled, the
  factories hand back `NoOpMetricsSystem` metrics (see the `else` branches throughout
  `PrometheusMetricsSystem`/`OpenTelemetrySystem`). Instrumentation is always safe to call.
- **Categories as the namespacing + enablement unit.** `MetricCategory` is an interface, not a
  closed enum, so plugins extend the taxonomy; enablement is per-category, and the `besu_` prefix
  keeps app metrics distinct from neutral `jvm`/`process` ones.
- **Supplier/callback metrics for externally-computed values.** `createLabelledSuppliedGauge`
  /`SuppliedCounter`/`SuppliedSummary` pull values on scrape from a supplier — explicitly used to
  surface **RocksDB's own statistics** and Guava cache stats without besu maintaining shadow counters.
- **First-class OpenTelemetry via auto-configuration.** `AutoConfiguredOpenTelemetrySdk` means OTLP
  endpoint/exporters/resource attributes come from the OTel standard env vars — zero besu-specific
  config surface for the enterprise monitoring stack, and standards-based metrics **and** traces.
- **Health decoupled from metrics.** Liveness/readiness live on the RPC HTTP server as cheap probes
  for k8s/orchestration, independent of whether the (heavier) metrics subsystem is enabled.

## Notable patterns (the reusable idea)

**The provider-agnostic `MetricsSystem` seam + no-op fallback is the single most transferable
pattern.** Define one instrumentation interface (create counter/gauge/timer/histogram, labelled,
grouped by an *extensible* category), let a factory bind it to a concrete exporter at startup, and
return no-op instruments when disabled. Everything else — Prometheus pull, OTLP push, plugin-registered
categories, supplier-backed gauges for foreign stats (RocksDB), distributed RPC tracing — plugs in
behind that seam without touching instrumented code. Two secondary reusable ideas: **supplier/callback
metrics** to export a subsystem's *own* internal statistics on scrape rather than duplicating them,
and **standards-based OTel auto-configuration** so the enterprise observability endpoint is configured
by convention (`OTEL_*`) rather than a bespoke config block.

## Authority note

besu is the JVM **MetricsSystem + OpenTelemetry** observability reference for fukuii: same JVM,
same available ecosystem (Micrometer / OpenTelemetry Java SDK / Prometheus `client_java`), same
MXBean-based JVM/GC instrumentation, and the same enterprise expectation of Prometheus scrape +
OTLP export + `/liveness`/`/readiness` probes — directly relevant to fukuii's enterprise/archival
use case. Its `MetricsSystem`-behind-a-plugin-service shape is the model for fukuii adding a
pluggable, exporter-agnostic metrics seam. geth is the *self-contained-metrics-lib* peer (its
`go-ethereum/metrics` is an in-tree Go metrics library with an expvar/Prometheus bridge, not a
standards-first OTel design); for standards-based JVM observability, besu is the authority, geth
the contrast.

## Gotchas / anti-patterns / things they later changed

- **OTel backend has real feature gaps vs Prometheus.** In `OpenTelemetrySystem`,
  `createLabelledHistogram` and `createLabelledSuppliedSummary` return **NoOp** ("not yet
  supported", `:245`, `:291`), and `createGuavaCacheCollector` is a no-op (`:296`). Choosing
  `OPENTELEMETRY` silently drops histograms/summaries/cache metrics that Prometheus would export.
- **RocksDB stats categories are off by default on purpose.** `BesuMetricCategory` excludes
  `KVSTORE_ROCKSDB*` from `DEFAULT_METRIC_CATEGORIES` with the comment "They hurt performance under
  load" (`:68`). Observability has a measured cost; enable deliberately.
- **Prometheus and push are mutually exclusive.** `MetricsHttpService.validateConfig` rejects
  `isEnabled() && isPushEnabled()` (`:66`) — a scrape server and a push gateway can't both run.
- **Global OpenTelemetry is a process singleton.** `MetricsSystemFactory` forcibly sets
  `GlobalOpenTelemetry` to no-op unless OTel is the active protocol (`:40`), and `setAsGlobal`
  is passed `true` — beware multiple `MetricsSystem` instances (e.g. in tests) fighting over the
  global, which is why `globalOpenTelemetryDisabled` guards a one-shot set.
- **Prometheus client migrated to the 1.x model API.** This code uses
  `io.prometheus.metrics.model.registry.PrometheusRegistry` and the `io.prometheus.metrics.instrumentation.jvm.*`
  collectors (the newer `client_java` 1.x), not the legacy `io.prometheus.client.CollectorRegistry`
  / `DefaultExports` — a dependency-currency signal if fukuii mirrors this stack.
- **Metrics endpoint auth is host-allowlist only**, not credential-based (`MetricsHttpService`
  `RestrictedDefaultHandler` + allowlist `Authenticator`) — expose it on a trusted network, not
  publicly.
