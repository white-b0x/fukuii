# nethermind — observability
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

nethermind's observability is four separable subsystems, each a distinct project, wired
in through the plugin/DI (Autofac) layer:

- **`Nethermind.Monitoring`** — Prometheus metrics. The defining convention is
  **per-module static `Metrics` classes**: every subsystem (`TxPool`, `Blockchain`,
  `Synchronization`, `Serialization.Rlp`, ...) declares a `public static class Metrics`
  whose members are `[Description]`-attributed counters/gauges. A single
  `MetricsController` **reflection-scrapes** all of them on a timer and publishes to
  Prometheus (scrape endpoint via embedded Kestrel, and/or push to Pushgateway). Metric
  code never references Prometheus — it just increments a static field.
- **`Nethermind.HealthChecks`** — first-class node liveness/readiness. Exposes both a
  JSON-RPC method (`health_nodeStatus`) and an ASP.NET Core `/health` HTTP endpoint (via
  `Microsoft.Extensions.Diagnostics.HealthChecks`), plus an optional HealthChecks-UI and
  webhook notifications. This is the enterprise/k8s-probe surface.
- **`Nethermind.EthStats`** — WebSocket reporter that pushes node stats (blocks, latency,
  pending count) to an ethstats dashboard server.
- **`Nethermind.Seq`** — a config shim for the NLog → Seq structured-logging sink; the
  actual sink is an NLog target (`NLog.Targets.Seq`) declared in `Runner/NLog.config`.

## Key types / interfaces / files

### Monitoring (Prometheus)
- `Nethermind.Monitoring/Metrics/MetricsController.cs:214` — `RegisterMetrics(Type)`:
  reflects over a `Metrics` class's properties+fields and builds an `IMetricUpdater[]`
  (gauge / per-key gauge / key-is-label gauge / summary / histogram) per member.
- `MetricsController.cs:355` — `RunTimer` / `UpdateAllMetrics`: a `PeriodicTimer`
  (interval = `IntervalSeconds/2`) that pulls every registered member's current value into
  its Prometheus gauge. Pull model — metric owners never push.
- `MetricsController.cs:163` — `DetermineMetricInfo`: name from `[DataMember]` or
  camelCase→snake_case (`BuildGaugeName`, prefixed `nethermind_`), help text from
  `[Description]`, per-metric tags from `[MetricsStaticDescriptionTag]`.
- `MetricsController.cs:183` — `_commonStaticTags`: instance/network/sync-type/pruning/
  version/commit stamped as static labels on every metric.
- `Nethermind.Monitoring/Metrics/MetricsStaticDescriptionTagAttribute.cs:20` — attaches a
  static-field-sourced label to a metric.
- `Nethermind.Monitoring/MonitoringService.cs:63` — `StartAsync`: optionally starts a
  `MetricPusher` (Pushgateway) and/or a `NethermindKestrelMetricServer` (scrape endpoint),
  then runs the controller timer.
- `Nethermind.Init/Modules/MonitoringModule.cs:69` — the discovery seam:
  `TypeDiscovery.FindNethermindBasedTypes(nameof(Metrics))` finds every `Metrics` class in
  loaded assemblies and registers each. Adds `AddMetricsUpdateAction` callbacks (sync time,
  allocator stats) for values that must be computed each tick rather than read from a field.
- `Nethermind.Monitoring/Config/IMetricsConfig.cs` — `ExposeHost`/`ExposePort` (scrape),
  `PushGatewayUrl`, `Enabled`, `CountersEnabled` (dotnet-counters via `System.Diagnostics.
  Metrics.Meter`), `NodeName` (Grafana label).
- `Nethermind.TxPool/Metrics.cs:9` — representative per-module class: `[CounterMetric]` +
  `[Description(...)]` on `public static long` members. This is the pattern to replicate.

### HealthChecks
- `Nethermind.HealthChecks/NodeHealthService.cs:64` — `CheckHealth()`: the actual health
  logic. Different rulesets for PoS (has `terminalTotalDifficulty`: sync + peers + CL-alive)
  vs PoW/mining (sync + peers + processing + producing), plus a low-disk-space check across
  drives. Returns `Healthy`, `IsSyncing`, human messages, and machine-readable `Errors`
  (`NoPeers`, `NotProducingBlocks`, `ClUnavailable`, `LowDiskSpace`, `SyncDegraded`, ...).
- `Nethermind.HealthChecks/HealthRpcModule.cs:22` — `health_nodeStatus()` JSON-RPC method.
- `Nethermind.HealthChecks/NodeHealthCheck.cs:21` — ASP.NET `IHealthCheck` adapter →
  `HealthCheckResult.Healthy/Unhealthy` for the `/health` HTTP probe.
- `Nethermind.HealthChecks/HealthCheckJsonRpcConfigurer.cs:26` — registers
  `AddHealthChecks().AddTypeActivatedCheck<NodeHealthCheck>("node-health")`, optional
  HealthChecks-UI (`AddHealthChecksUI`, in-memory storage) and webhook notifications.
- `Nethermind.HealthChecks/HealthChecksPlugin.cs:32` — `Enabled => true` (always on);
  wires the module, and starts `FreeDiskSpaceChecker` + CL request tracking.
- `HealthChecksConfig.cs` — `Slug = "/health"`, `PollingInterval`, `LowStorageSpace*`
  thresholds, `MaxIntervalWithoutProcessed/ProducedBlock`, `MaxIntervalClRequestTime`.

### EthStats / Seq
- `Nethermind.EthStats/Integrations/EthStatsIntegration.cs:29` — WebSocket reporter loop.
- `Nethermind.EthStats/EthStatsPlugin.cs` — opt-in plugin (server URL + secret).
- `Nethermind.Seq/Config/SeqConfig.cs:6` — `MinLevel = "Off"` (disabled by default),
  `ServerUrl`, `ApiKey`. The sink itself is `Runner/NLog.config` target `seq`
  (`<add assembly="NLog.Targets.Seq"/>`); `Seq.MinLevel`/`Seq.ServerUrl` rewrite the target
  at startup. `LogEventKind` is published as a queryable Seq property.

## Design decisions & rationale

- **Metric declaration decoupled from the metrics backend.** A subsystem increments a
  plain `static long`; it takes no dependency on Prometheus, needs no DI, and stays trivial
  to unit-test. All Prometheus knowledge is centralized in one `MetricsController`.
- **Reflection + convention over registration.** Adding a metric = add one attributed
  static field. `TypeDiscovery.FindNethermindBasedTypes("Metrics")` auto-discovers it — no
  central registry to edit, no wiring. Attributes carry all metadata (help, tags, counter
  vs gauge, detailed-only, histogram buckets).
- **Pull-on-a-timer for field-backed metrics; observer for latency.** Simple counters are
  read each tick; summaries/histograms implement `IMetricObserver` and are written at
  `Observe()` time (needed for distributions that can't be reconstructed from a snapshot).
- **Two Prometheus delivery modes.** Scrape (Kestrel endpoint) for pull-based Prometheus,
  and Pushgateway for short-lived/firewalled nodes — both from the same controller.
- **Health as a first-class, always-on subsystem with dual surfaces.** RPC for tooling,
  a real HTTP `/health` for k8s/load-balancer probes, machine-readable `Errors` for
  automation, and consensus-aware rules (post-merge CL liveness vs PoW block production).
- **Structured logging opt-in and safe by default.** Seq `MinLevel` defaults to `Off`; the
  NLog target self-removes unless configured, so no accidental log shipping.

## Notable patterns (the reusable idea)

**The attributed-static-`Metrics`-class + single reflection-scraping controller.** Each
module owns a `static class Metrics` of `[Description]`-annotated counters; one central
controller reflects over all of them on a timer and translates to Prometheus. This gives
zero-friction metric authoring (one line, no DI), a single choke point for the metrics
backend (swap Prometheus without touching call sites), and self-documenting metrics (the
`[Description]` becomes the Prometheus HELP text). For fukuii this maps cleanly onto Scala:
a per-module `object Metrics` (or annotated vals) + a single scraper actor/service.

The **HealthChecks** second idea: a consensus-aware `CheckHealth()` returning both
human messages and machine `Errors`, surfaced simultaneously as JSON-RPC and an HTTP
`/health` probe — the exact readiness surface an enterprise/k8s deployment needs.

## Authority note

nethermind = the **attributed-static-`Metrics` + first-class HealthChecks** reference. Its
convention-driven, reflection-scraped metric declaration and its dual RPC+HTTP health probe
are the transferable designs here. besu (OpenTelemetry + Prometheus exporter) and erigon
(its `diagnostics` subsystem / support tunnel) are the peer references for the same slot;
consult them for OTel-native tracing and for a diagnostics-over-HTTP model respectively.

## Gotchas / anti-patterns / things they later changed

- **Static mutable metric state.** `Metrics` classes are process-global `static` fields —
  simple, but shared across everything, unfriendly to parallel test isolation, and the
  reason `InitializeStaticLabels` guards against double static-label registration
  (`_staticLabelsInitialized`). A Scala port should prefer instance-scoped counters even if
  it keeps the declaration convention.
- **Reflection cost / AOT-hostility.** Discovery and per-member accessor building rely on
  reflection (`GetValueAccessor`, `GetProperties`); fine at startup, but a consideration for
  trimming/NativeAOT and not free.
- **`Enabled` vs `CountersEnabled` are orthogonal.** `Enabled` gates Prometheus;
  `CountersEnabled` gates the parallel `System.Diagnostics.Metrics.Meter` (dotnet-counters)
  path. Neither being set falls back to `NoopMonitoringService`.
- **Health rules are consensus-family-specific.** `CheckHealth` branches on
  `terminalTotalDifficulty != null` (PoS) vs mining/PoW; a fukuii port must keep separate
  PoW (block-production liveness) and PoS (CL-alive) health criteria, not one shared rule.
- **Seq/EthStats are off by default and easy to miss.** Seq self-removes its NLog target
  unless `Seq.MinLevel` is set; EthStats needs an explicit server URL + secret.
