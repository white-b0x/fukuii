# nethermind — exec-extensions
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

nethermind's execution-extension surface is a set of **optional, config-gated plugins**
(`INethermindPlugin`) that hang data-product machinery off the block-processing pipeline
without touching consensus. All four projects in this slot share one shape: an
`INethermindPlugin` with an Autofac `IModule`, an `IConfig`-derived options interface whose
`Enabled` flag decides whether the plugin loads, and a private RocksDB column family for the
data it emits. The plugin lifecycle (`InitTxTypesAndRlpDecoders` → `Init` →
`InitNetworkProtocol` → `InitRpcModules`) gives each extension a well-defined attach point.

Two structural patterns recur:

1. **Write side = decorate a producer.** TraceStore adds a `DbPersistingBlockTracer` to
   `BlockchainProcessor.Tracers`; StateDiffsWriter subscribes to `IBlockTree.NewHeadBlock`;
   BalRecorder Autofac-`AddDecorator`s the block/branch processor, validator, and spec
   provider. Each captures a per-block artifact as blocks are processed and persists it.
2. **Read side = decorate the live module with a DB-fallback.** TraceStore's
   `TraceStoreRpcModule` wraps the live `ITraceRpcModule`: it serves `trace_*` from the
   materialized DB when a block is present and **falls through to live re-execution** on a
   miss. This is the distinctive "materialized-trace indexer" idea — pre-compute + persist
   traces so archival `trace_*` calls need no historical state.

`Nethermind.Grpc` is a separate, legacy gRPC transport (a JSON-over-protobuf pub/sub fan-out
stream), decoupled from producers via the generic `Core.PubSub.IPublisher` seam.

## Key types / interfaces / files

**Plugin contract**
- `Nethermind.Api/Extensions/INethermindPlugin.cs` — the extension contract: `Name`,
  `Description`, `Enabled`, `MustInitialize`, `IModule? Module`, and default-no-op lifecycle
  hooks `InitTxTypesAndRlpDecoders` / `Init` / `InitNetworkProtocol` / `InitRpcModules`.

**TraceStore (materialized-trace indexer — the headline pattern)**
- `Nethermind.JsonRpc.TraceStore/TraceStorePlugin.cs:38` — `Enabled => traceStoreConfig.Enabled`;
  `Init` sets up serializer + resolves the keyed `TraceStore` `IDb`; `InitNetworkProtocol`
  wires the persisting tracer into `MainProcessingContext.BlockchainProcessor.Tracers`;
  `InitRpcModules` registers the DB-backed module *only if* a live `ITraceRpcModule` pool exists.
- `.../DbPersistingBlockTracer.cs:66` — decorator `BlockTracer`; on `EndBlockTrace`, gzip+JSON
  serializes the built traces and `_db.Set(currentBlockHash, tracesSerialized)`. Keyed by block hash.
- `.../TraceStoreRpcModule.cs:99` — wraps live `ITraceRpcModule`; `trace_transaction` /
  `trace_block` / `trace_replayBlockTransactions` / `trace_filter` read the DB
  (`TryGetBlockTraces`) and **delegate to `_traceModule` on any miss** (`.cs:118`, `.cs:222`,
  `.cs:290`). `trace_block` bypasses the store when a `fork` override is supplied (`.cs:279`).
  Optional streaming mode (`Utf8JsonWriter` → `PipeWriter`) gated by `EnableTracingStreamMode`.
- `.../TraceStorePruner.cs:44` — subscribes to `IBlockTree.BlockAddedToMain`; deletes the
  trace at `head - BlocksToKeep` on a background `Task.Run`.
- `.../ParityLikeTraceSerializer.cs:32` — GZip + `EthereumJsonSerializer`; depth-guarded;
  optional async round-trip verification.
- `.../ITraceStoreConfig.cs` — `Enabled`, `BlocksToKeep` (0 = keep all), `TraceTypes`,
  `DeserializationParallelization`.

**StateDiffsWriter (per-block state-diff data feed)**
- `Nethermind.StateDiffsWriter/StateDiffsWriterPlugin.cs:23` — resolves `DiffsWriterService`
  eagerly (attaches subscription before first head), starts `DiffsPruner`; **no RPC**,
  aggregation runs out of process.
- `.../Service/DiffsWriterService.cs:79` — `NewHeadBlock` handler (chosen because it fires
  only after the trie store commits, guaranteeing both roots resolve). `ComputeRecord` opens
  one read-only trie-store scope per root and runs `new TrieDiffWalker().ComputeDiff(...)`.
- `.../Storage/BlockDiffsStore.cs:31` — `WriteBlockDiff` lands the per-block record and the
  changed accounts' running slot counts as **one atomic cross-column-family write batch**.
- `.../Storage/BlockDiffsColumns.cs` — two CFs: `Default` (per-block records, pruned) and
  `SlotCounts` (running per-account slot totals, never pruned).
- `.../Data/BlockDiffRecord.cs:33` — RLP, positional, **version-less and strictly additive**
  ("decoders MUST treat absent fields as zero") — the on-disk contract for the external consumer.
- `.../IStateDiffsWriterConfig.cs` — `Enabled`, `KeepLastNBlocks`, `PruneIntervalSeconds`.

**BalRecorder (block-access-list recorder — EIP-7928, dev/benchmark only)**
- `Nethermind.BalRecorder/BalRecorderPlugin.cs:31` — `BalRecorderModule` Autofac-`AddDecorator`s
  `ISpecProvider`, `IBlockValidator`, `IBranchProcessor`, `IBlockProcessor`. Class remark:
  "DEVELOPMENT / BENCHMARK USE ONLY … must not be enabled on production nodes."
- `.../BalRecordingBlockProcessor.cs:38` — forces `GeneratedBlockAccessList` construction even
  on the parallel/verify-only fast path, then `store.Insert(block, bal)` after `ProcessOne`.
- `.../IRecordedBalStore.cs` / `RecordedBalStore.cs` / `SlotStore.cs` / `SlotFile.cs` — stores
  recorded BALs as "era files" for prewarming benchmarks; supports record and replay.

**Grpc (data-streaming transport seam)**
- `Nethermind.Grpc/Nethermind.proto` (→ generated `Nethermind.cs`, `NethermindGrpc.cs`) —
  `NethermindService` with `Query` (unary) and `Subscribe` (**server-streaming**), messages
  carrying `client` + `data`/`args` strings.
- `.../Servers/GrpcServer.cs:13` — implements `IGrpcServer.PublishAsync<T>`; a per-client
  pub/sub fan-out: `ConcurrentDictionary<string, BlockingCollection<string>>`, JSON-serializes
  each datum and pushes to per-client queues; `Subscribe` drains the queue to the stream.
- `.../Producers/GrpcPublisher.cs:11` — adapts the generic `Core.PubSub.IPublisher` onto the
  gRPC server, so any Nethermind pub/sub producer can feed the stream without knowing about gRPC.
- `.../IGrpcConfig.cs` — `Enabled` (default false), `Host`, `Port` (default 50000);
  `[ConfigCategory(DisabledForCli = true, HiddenFromDocs = true)]` — semi-deprecated surface.

## Design decisions & rationale

- **Config-gated optional plugins, not always-on subsystems.** Every extension is off by
  default (`Enabled => config.Enabled`), loaded only when configured. Data products cost
  nothing on a node that doesn't want them, and each ships/versions independently.
- **Decorator over the live path with fallback (TraceStore).** Rather than a parallel serving
  path, the DB module *wraps* the real `ITraceRpcModule` and only short-circuits on a store
  hit — correctness is preserved (fork overrides, cold blocks re-execute live) while hot
  archival ranges are served from disk without historical state. This is the enabling trick
  for **archival trace serving on a pruned/non-archive node.**
- **Persist keyed by natural identity.** TraceStore keys by block hash; StateDiffsWriter keys
  by big-endian block number. Both dedicate their own RocksDB (column family) so the data
  product never contends with core chain storage.
- **On-disk contract stability for external consumers (StateDiffsWriter).** The record is
  RLP, positional, version-less, and append-only-additive precisely because an out-of-process
  reader decodes it; new fields append and default to zero rather than bumping a version.
- **Attach at the signal that guarantees committed state.** StateDiffsWriter uses
  `NewHeadBlock` (post-commit) so both parent and new state roots resolve; BalRecorder records
  at `IBlockProcessor.ProcessOne` (runs for every block) not the branch processor (guarded by
  a read-only-chain check).
- **Transport decoupled from producer via a generic publisher.** `GrpcPublisher : IPublisher`
  means the gRPC stream is just one sink for Nethermind's internal pub/sub — the producer
  doesn't depend on gRPC.

## Notable patterns (the reusable idea)

- **Materialized-trace indexer with live fallback.** Pre-compute traces during processing,
  gzip+persist keyed by block hash, and serve `trace_*` from the store while transparently
  falling back to re-execution on a miss. Turns expensive archival trace queries into a disk
  read and decouples "can I serve traces" from "do I keep archival state."
- **Data-extension = plugin + decorator + dedicated CF.** A downstream data product is a
  self-contained `INethermindPlugin`: decorate a producer (tracer / processor / block-tree
  event) on the write side, optionally decorate the RPC module on the read side, persist to a
  private column family, prune on a schedule, expose a metric. Enable via one config flag.
- **Version-less additive on-disk record for out-of-process readers.** Positional RLP + "absent
  = zero" is a lightweight forward-compatible wire contract when the consumer is a separate
  process/language.
- **Atomic multi-CF write batch** ties a mutable index (running slot counts) to the pruned
  per-block payload so the two never diverge.
- **Publisher-fronted streaming transport** — a generic `IPublisher` seam lets a gRPC (or any)
  streaming sink subscribe to internal events without the producers knowing the transport.

## Authority note

nethermind is the reference for the **TraceStore materialized-trace indexer**, the
**plugin-based optional data-extension model** (decorate-a-producer + dedicated column family +
config gate), and the **gRPC data-streaming seam** fronted by a generic publisher. Peers:
**reth ExEx** (execution extensions as first-class post-execution hooks/subscriptions) and
**erigon's gRPC `StateChanges`/kv remote** (state-diff streaming over gRPC as the primary
indexing transport). Where erigon makes gRPC state-streaming the backbone, nethermind keeps
it an optional, semi-deprecated plugin and leans on JSON-RPC + persisted-trace materialization
instead — the two bracket the design space for fukuii's dRPC/exec-extension seam.

## Gotchas / anti-patterns / things they later changed

- **`Nethermind.Grpc` is semi-deprecated.** `DisabledForCli = true, HiddenFromDocs = true`,
  disabled by default, and `Query` returns an empty response (only `Subscribe` streams). It is
  a thin JSON-string fan-out, not a typed/columnar data API — treat it as a legacy seam, not
  the model for a modern gRPC data plane (contrast erigon's typed protobuf kv/state APIs).
- **BalRecorder is explicitly not production-safe.** Its decorators wrap *every* registered
  processor/validator/spec-provider (including simulation and eth_call paths); the plugin
  remark forbids enabling it on production nodes. It's a benchmark/prewarming tool, not a data
  product.
- **StateDiffsWriter reorg caveat.** `SlotCounts` running totals are **not rolled back on
  reorg**, so `OldCount` is "advisory across a reorg"; the consumer must reconstruct running
  totals from the diff chain. Surfaced via a metric + warn rather than corrected in-place.
- **TraceStore tracing is best-effort.** `EndBlockTrace` swallows serialization failures with a
  warn (`Couldn't save traces for block …`) — a persist failure silently degrades that block to
  the live-re-execution fallback rather than failing block processing. Fine for a data product;
  would be wrong for anything consensus-relevant.
- **`trace_block` with a `fork` override bypasses the store** — fork-override replays require
  live re-execution, so the materialized cache is only valid for canonical semantics.
- **Pruners run on background `Task.Run` off block-tree events** (TraceStore) or a timer
  (StateDiffsWriter); retention is a config window (`BlocksToKeep` / `KeepLastNBlocks`), so a
  non-archive TraceStore serves only a sliding window of history.
