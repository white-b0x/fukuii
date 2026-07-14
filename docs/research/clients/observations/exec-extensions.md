# Observations — exec-extensions
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/exec-extensions.md._

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | fukuii | Authoritative |
|---|---|---|---|---|---|---|---|---|
| Downstream-data mechanism | live-tracing hooks (`core/tracing.Hooks`) + coarse event-feed | old `core/vm.EVMLogger` + `trace_*` RPC facade (no `core/tracing`) | plugin event bus (`BesuEvents` + `TrieLogService`) | gRPC `KV.StateChanges` state-diff stream | materialized-trace-store plugin (`TraceStore`) + `StateDiffsWriter` | ExEx framework (`ExExNotification` stream) | bare `Topic[T]` WS push (`NewBlockImported`/`NewPendingTransaction`) — `SubscriptionManager.scala:89` | reth (ExEx) |
| In-process vs cross-process | in-process Go callbacks only | in-process only (+ `trace_*`/OpenRPC surfaces) | in-process JVM callbacks (ServiceLoader jars) | **both**: native local + protobuf gRPC remote | in-process plugins; separate legacy `Nethermind.Grpc` fan-out | in-process async tasks (WAL enables cross-process resume) | in-process only (WS push + on-demand RPC re-exec); faucet is out-of-proc RPC client (`Faucet.scala:5`) | erigon (native cross-process gRPC) |
| Reorg-awareness | none built in (consumer derives from feed) | none (inherited feed only) | typed `AddedBlockContext.EventType` (HEAD/FORK/REORG/STORED) | first-class `Direction` FORWARD/UNWIND | advisory only — `SlotCounts` NOT rolled back on reorg | first-class `ChainCommitted/Reorged/Reverted` (old+new chains) | none — new-branch-only re-publish (`BlockImporter.scala:677`), `"removed": false` hardcoded (`SubscriptionManager.scala:227`) | reth |
| Backpressure / acknowledgement | none (synchronous hot-path hooks) | none | none — synchronous listeners, no ack | bounded (cap-8 `PrioritizedSend`, best-effort, no ack) | none (background `Task.Run` pruners) | **`ExExEvent::FinishedHeight` ack** → node gates prune on `min()` | none — fire-and-forget `SourceQueue.offer` (`SubscriptionManager.scala:163`) | reth |
| WAL / reorg-safety | none | none | none | register-then-produce race patched; no replay WAL | best-effort persist (swallows failures) | **per-notification WAL** (MessagePack, replay+`into_inverted()` undo) | N/A — confirmed by grep (no WAL/replay/undo) | reth |
| Indexing / trace-store | `filtermaps` log index + `txindexer` (event-driven, in-proc) | OpenEthereum `trace_*` (ECIP-1017 reward traces) | `TraceService.trace(from,to)` backfill + Bonsai `TrieLog` feed | remote-KV pull (`Tx`/`Range`/`HistorySeek`) alongside push | **materialized `TraceStore`** (persist keyed by hash + live fallback) | resume-via-backfill (`ExExHead` cursor) | producers only, no store — `trace_*`/`debug_trace*` re-execute per request (`TraceService.scala:106`); `stateDiff` = `JNull` (`:335`) | nethermind (TraceStore) |
| gRPC data transport | none (productization gap) | none (OpenRPC descriptions only) | none in `plugin-api` (plugin's own concern) | **primary transport**: `KV`/`Mining`/`EthBackend` protobuf services | optional/semi-deprecated `NethermindService` JSON-over-protobuf | none built in (WAL/async is in-process) | N/A — not implemented; forward-looking DRPC-GATEWAY-01 seam unbuilt; removed extvm protobuf is README-only (`protobuf/extvm/README.md:8`) | erigon (StateChanges) |

## Approach catalog (use-case-aware)

| Approach | Clients using it | Good for (use-case/node-role) | Verdict | Why |
|---|---|---|---|---|
| In-process live-tracing hooks (typed-reason `Hooks`) | go-ethereum (core-geth = older `EVMLogger`) | archival/analytics tracers co-located with the node; supply/accounting via reason enums | OPTIONAL(archival/analytics node) | Best *write-side notification shape* (reason-annotated mutations, registry+`--vmtrace` selector) but runs synchronously in the consensus hot path — no cross-process, no backpressure. |
| Plugin event bus (sync callbacks) | besu (`BesuEvents`), nethermind (`INethermindPlugin`) | drop-in data-product jars/plugins on a single node without forking | OPTIONAL(single-node plugin host) | Typed, filterable, deregisterable events (block/reorg/tx/sync/log/trie-log) over a stable ABI — but listeners run on node threads with **no backpressure or prune gate**; a slow/crashed listener has no flow-control. |
| gRPC StateChanges stream (cross-process push+pull) | erigon (`KV.StateChanges`) | standalone RPCDaemon / indexer as a separate process consuming push diffs + pull history from one seam | DEFAULT(cross-process realization) | Natively cross-process protobuf state-diff feed with `Direction` reorg-safety; same `KV` service also serves remote cursors → indexer gets push (diffs) and pull (history/index). No explicit ack (best-effort, cap-8). |
| Materialized-trace-store plugin + live fallback | nethermind (`TraceStore`) | archival `trace_*` serving on a pruned/non-archive node | OPTIONAL(archival trace serving) | Pre-computes + persists traces keyed by block hash, serves from disk, transparently re-executes on miss — decouples "can I serve traces" from "do I keep archival state." Best-effort persist; not a backpressured notification framework. |
| ExEx framework (reorg-aware + FinishedHeight + WAL) | reth | any reorg-safe data product: indexer, rollup, bridge, dRPC provider, trace-store | DEFAULT | The flagship: reorg-explicit notifications (old+new chains), `FinishedHeight` distributed prune barrier (node gates cleanup on `min(consumer heights)`), per-notification WAL for restart reorg-safety, resume-via-backfill. Supplies exactly the backpressure + reorg-safety the synchronous callback designs lack. |
| gRPC data transport (dRPC seam) | erigon (`KV`/`Mining` protobuf), nethermind (`Nethermind.Grpc`) | the wire a dRPC/indexing product consumes out-of-process | DEFAULT(cross-process transport) — erigon typed protobuf; OBSOLETE for nethermind's legacy JSON-string fan-out | erigon's typed protobuf KV/state API is the modern cross-process seam (DRPC-GATEWAY-01 bridge); nethermind's `NethermindService` is `DisabledForCli`/`HiddenFromDocs`, JSON-string only — a legacy seam, not the model. |

## Best-practice synthesis

**DEFAULT** for fukuii's exec-extension / data-product seam = **reth ExEx**, realized cross-process as a **gRPC service (erigon `StateChanges`)**.

The reth ExEx design supplies the three properties a naive in-process callback lacks, and that besu's synchronous `BesuEvents` callbacks explicitly do NOT have:

1. **Reorg-aware notifications** — `ChainCommitted` / `ChainReorged` (carries both `old` and `new` chains) / `ChainReverted`, so a data product undoes and redoes deterministically rather than reconstructing "what did I need to undo?" itself. (erigon's `Direction=FORWARD/UNWIND` is the same idea at the diff granularity.)
2. **`FinishedHeight` distributed prune barrier** — each consumer publishes the height it has finished; the node gates irreversible cleanup (state/WAL prune) on `min(consumer heights)`. This is real backpressure: a slow ExEx fills the bounded buffer and stops the producer until it catches up. besu has no notion of "wait for the indexer before pruning."
3. **WAL for restart reorg-safety** — every tree notification is committed to disk *before* delivery, so on restart the node replays/reverts and keeps each consumer's derived state consistent across reorgs (`into_inverted()` generates the undo). Resume-via-`ExExHead` + backfill makes a restarted indexer deterministic.

**THE gRPC-seam thesis (DRPC-GATEWAY-01).** This is the seam a dRPC/indexing product consumes. Realize the reth ExEx design cross-process as a gRPC service the way erigon does: `StateChanges` **push** (server-streaming protobuf state-diffs) co-located on the *same* service that exposes remote-KV **pull** (`Tx`/`Range`/`HistorySeek`) — so an indexer/dRPC-Provider gets both a live diff feed and historical/index reads from one endpoint. geth's typed, reason-annotated in-process hooks are the correct **write-side notification shape** (the mutation payload, tagged by *why* — reward vs fee-burn vs transfer), but geth stops at the in-process Go boundary; the productizable move is to project that surface across a process boundary with flow-control.

**OPTIONAL menu (by node role):**
- In-process typed-reason hooks (geth) — archival/analytics node that co-locates a tracer; the notification *shape*, not the transport.
- Materialized-trace-store + live fallback (nethermind) — archival `trace_*` serving on a pruned node.
- Plugin event bus (besu/nethermind) — single-node plugin hosting without forking, when backpressure/reorg-safety is not required.
- OpenEthereum `trace_*` facade (core-geth) — an ETC-family RPC-compatibility expectation for explorers/indexers, orthogonal to the streaming seam.

The through-line: fukuii should design in the **async boundary + flow-control (reth `FinishedHeight`) + reorg-safety (WAL)** that a naive in-process callback lacks, and expose it over a **typed gRPC service (erigon)** rather than JSON-string fan-out (nethermind's legacy `Grpc`, OBSOLETE as a model).

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

fukuii has **no first-class exec-extension framework today** — no ExEx-equivalent, no reason-annotated execution-notification interface, no cross-process state-diff seam. This is simultaneously a **Phase-4 architecture seed** and the concrete **DRPC-GATEWAY-01 seam**:

- **Expose a first-class gRPC data-seam** modeled on reth ExEx (reorg-aware `ChainCommitted/Reorged/Reverted` notifications + `FinishedHeight` distributed prune barrier + WAL) and realized as a gRPC service the way erigon does `StateChanges` (push diffs + remote-KV pull on one service), so an indexer / dRPC-Provider attaches with **reorg-safety and backpressure** rather than scraping JSON-RPC.
- **The gRPC seam = the bridge to the dRPC product.** Erigon's gRPC architecture is congruent with a dRPC Provider architecture (memory: erigon gRPC ≡ drpc Provider arch) — the same push+pull service that keeps a remote RPCDaemon's cache coherent is what a dRPC gateway / external indexer consumes.
- **Carry geth's typed-reason write-side shape** into the notification payload (tag each mutation with *why*), and keep the ECIP-1017-aware `trace_*` RPC facade (core-geth) as the ETC-family compatibility expectation — but neither replaces the streaming seam.
- **Watch fukuii's account model.** erigon's `AccountChange.incarnation` and besu's Bonsai-specific `TrieLog` are storage-format assumptions fukuii (RocksDB) must map or make storage-agnostic; the state-diff seam's on-disk/wire contract should be version-less-additive (nethermind's positional-RLP "absent = zero") for out-of-process readers.

These are **seeds, not verdicts** — Phase 3–4 decides the concrete gRPC contract, the state-diff payload shape, and where the prune barrier wires into fukuii's storage/pruning path.
