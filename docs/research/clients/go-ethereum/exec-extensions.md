# go-ethereum — exec-extensions
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary

geth has **no reth-style out-of-process "ExEx" framework** and **no gRPC data
stream** (contrast reth ExEx, erigon's gRPC KV/execution API, nethermind's
TraceStore). Its downstream-execution-notification seam is instead an **in-process
callback interface** — the **live tracing `Hooks`** struct — combined with the
older **event-feed / subscription** system used by the log-filter/indexer path.

Two distinct downstream-data mechanisms exist, at two different altitudes:

1. **Live tracing hooks** (`core/tracing/hooks.go`) — a single fat struct of
   optional function pointers that the core state transition fires at every
   granularity: chain init/close, block start/end, tx start/end, call
   enter/exit, per-opcode, and per-state-mutation (balance/nonce/code/storage/log).
   A tracer registers a constructor by name in a global `LiveDirectory`; the node
   is launched with `--vmtrace <name>` and the resulting `*tracing.Hooks` is
   installed as `vmConfig.Tracer`, so it is invoked *during normal block import*
   (not just on-demand `debug_trace*` RPC). This IS geth's exec-extension seam.

2. **Event feed / filter indexing** (`event/feed.go`, `core/events.go`,
   `eth/filters/`, `core/filtermaps/`, `core/txindexer.go`) — a one-to-many
   in-process pub/sub (`event.Feed`) carrying `ChainEvent`/`ChainHeadEvent`/
   `NewTxsEvent`/logs, consumed by the filter system and the log/tx indexers to
   build queryable indexes for `eth_getLogs`, subscriptions, and tx lookups.

Hooks = "tell me about every execution step as it happens" (push, fine-grained,
synchronous, in the hot path). Feeds = "tell me a block/tx/log batch landed"
(push, coarse, decoupled, used to build query indexes). Both are in-process Go
callbacks — neither crosses a process boundary.

## Key types / interfaces / files

- `core/tracing/hooks.go:277` — `type Hooks struct` — the central seam: ~30
  optional function-pointer fields grouped as VM events (`OnTxStart`, `OnTxEnd`,
  `OnEnter`, `OnExit`, `OnOpcode`, `OnFault`, `OnGasChange`/`OnGasChangeV2`),
  chain events (`OnBlockchainInit`, `OnClose`, `OnBlockStart`, `OnBlockEnd`,
  `OnSkippedBlock`, `OnGenesisBlock`, `OnSystemCallStart/End`, `OnStateUpdate`),
  and state events (`OnBalanceChange`, `OnNonceChange`, `OnCodeChange`,
  `OnStorageChange`, `OnLog`, `OnBlockHashRead`). Any nil field is simply skipped.
- `core/tracing/hooks.go:129-275` — the hook type aliases (`TxStartHook`,
  `EnterHook`, `OpcodeHook`, `StateUpdateHook`, …) — the exact signatures a
  consumer implements.
- `core/tracing/hooks.go:339-541` — `BalanceChangeReason` / `GasChangeReason` /
  `NonceChangeReason` / `CodeChangeReason` enums — semantic tags so a downstream
  consumer knows *why* a mutation happened (reward vs fee-burn vs transfer vs
  selfdestruct). This is what makes the supply-tracer possible.
- `core/tracing/hooks.go:314` — `HasGasHook()` and `:326` `EmitGasChange(...)` —
  the short-circuit helper: when no gas hook is registered the hot path avoids
  even constructing the argument vector. The dispatch-cost-avoidance idiom.
- `core/tracing/journal.go:40` — `WrapWithJournal(hooks *Hooks)` — optional
  wrapper that emits `*Revert` reason variants so a tracer sees mutations rolled
  back on call failure (opt-in; off by default to save cost).
- `eth/tracers/live.go:30` — `var LiveDirectory = liveDirectory{...}` — the
  **global name→constructor registry**. `:37` `Register(name, ctorFunc)` (called
  from each tracer's `init()`), `:42` `New(name, config)` builds a `*tracing.Hooks`.
- `eth/tracers/live.go:26` — `type ctorFunc func(config json.RawMessage)
  (*tracing.Hooks, error)` — the plugin contract: a JSON-configured constructor
  returning a populated hook set.
- `eth/tracers/live/supply.go:39` — `LiveDirectory.Register("supply", ...)` — the
  reference live tracer: reconstructs ETH supply deltas (issuance/burn) purely
  from balance-change reasons, streaming JSONL to a rotating log file
  (`lumberjack`). `noop.go` is the minimal template.
- `cmd/utils/flags.go:618` `VMTraceFlag` (`--vmtrace`), `:623`
  `VMTraceJsonConfigFlag` (`--vmtrace.jsonconfig`) — CLI surface; `:2064` copies
  them into `cfg.VMTrace` / `cfg.VMTraceJsonConfig`.
- `eth/backend.go:267-277` — the wiring: if `config.VMTrace != ""`, build the
  tracer via `tracers.LiveDirectory.New(...)` and set `options.VmConfig.Tracer = t`.
- `core/blockchain.go:425` — `logger: cfg.VmConfig.Tracer` — the blockchain holds
  the hook set as `bc.logger`; `:538` fires `OnBlockchainInit`, `:2210` fires
  `OnBlockStart`/`OnBlockEnd` around block processing. Every call site is
  nil-guarded (`if bc.logger != nil && bc.logger.OnX != nil`).
- `event/feed.go:33` — `type Feed struct` — one-to-many single-type channel
  broadcast; the substrate for all `Subscribe*Event` methods.
- `core/events.go:27-45` — `NewTxsEvent`, `RemovedLogsEvent`, `ChainEvent`,
  `ChainHeadEvent`, `NewPayloadEvent` — the coarse-grained event payloads.
- `eth/filters/filter_system.go:71-74` — `SubscribeNewTxsEvent` /
  `SubscribeChainEvent` / `SubscribeRemovedLogsEvent` / `SubscribeLogsEvent` — the
  backend contract the filter/index layer consumes.
- `core/filtermaps/filtermaps.go` — `FilterMaps`, the log-address/topic index
  (schema `databaseVersion = 2`), driven off chain events; the query engine
  behind `eth_getLogs`. `--history.logs.disable` turns it off.
- `core/txindexer.go` — `txIndexer` — the tx-hash→location index (a
  head-relative window of blocks), also event-driven.

## Design decisions & rationale

- **One fat struct of nil-able function pointers, not an interface.** A consumer
  fills only the hooks it wants; the core nil-checks each before firing. This
  keeps the hot path cheap for partial tracers and lets the hook set grow
  (new `OnStateUpdate`, `OnSystemCallStart`, gas-V2) without breaking existing
  tracers — additive evolution over a versioned interface.
- **Same `Hooks` type serves both on-demand RPC tracing and always-on live
  tracing.** `debug_traceTransaction` and `--vmtrace` share `core/tracing`; a
  "live tracer" is just a `Hooks` installed into `vmConfig.Tracer` at node start
  rather than per-request. One mechanism, two lifetimes.
- **Reasons are first-class enums, not free-text.** Balance/gas/nonce/code changes
  carry a typed reason. This is what lets a *downstream* consumer (indexer,
  accounting, supply tracker) reconstruct semantics without re-deriving them from
  raw state diffs — the single most valuable design choice for a data product.
- **Registry + JSON config = zero-arg plugin discovery.** Tracers self-register in
  `init()`; the operator selects one by string name and hands it opaque JSON.
  No compile-time wiring in `backend.go` per tracer.
- **Dispatch-cost avoidance is explicit.** `HasGasHook()` / `EmitGasChange` and the
  per-field nil checks mean "tracing off" costs ~a pointer compare per event, not
  argument marshalling. Fine-grained hooks in the consensus hot path force this.

## Notable patterns (the reusable idea)

**The single most transferable idea for fukuii: a typed, reason-annotated,
in-process execution-notification interface installed into the block-processing
path — the seam a downstream data product consumes.** Concretely:

- A **`Hooks`-equivalent** (a set of optional callbacks fired by the EVM/ledger at
  block/tx/call/opcode/state-mutation granularity), with every mutation tagged by
  a **typed reason enum**, gives indexers, archival RPC, trace-stores, and
  analytics a stable feed without each re-walking state or re-executing blocks.
- The **name→constructor registry + `--vmtrace`-style selector** is the clean way
  to make it pluggable without hard-wiring consumers into node startup.
- The **coarse event-feed layer** (chain/head/logs/txs pub-sub) is the right
  altitude for building *query indexes* (log filter, tx lookup), separate from the
  fine-grained per-step hooks.

For fukuii's **dRPC / indexing product idea**, this is exactly the consumption
seam: an indexer or data-feed service subscribes to reason-annotated execution
events instead of scraping RPC. It also directly informs the **product-family
gRPC-seam thesis** — geth stops at *in-process* Go callbacks; the productizable
move (and where reth ExEx / erigon-gRPC / nethermind TraceStore go further) is to
**project that same hook/feed surface across a process boundary** (gRPC/IPC
stream) so an external data product can consume it out-of-process. fukuii would
carry geth's typed-reason hook design *and* add the cross-process projection geth
lacks.

## Authority note

reth ExEx and nethermind TraceStore are the richer exec-extension authority
(out-of-process / persistent trace store, stronger productized data-feed
surface). **geth is the live-tracing-hooks + event-feed variant** — an in-process,
typed-callback design that is the best reference for the *interface shape* and
*reason-enum semantics*, but not for the process-boundary/streaming aspect. For
PoW/ETC specifics, core-geth remains the ETC authority; upstream go-ethereum's
tracing subsystem is essentially identical there and is a fine reference for the
hook interface regardless of network.

## Gotchas / anti-patterns / things they later changed

- **Live-tracer hooks run synchronously in the consensus hot path.** A slow or
  panicking hook stalls/breaks block import. The supply tracer buffers to a
  rotating file (`lumberjack`) rather than doing blocking work inline — the
  intended pattern. A downstream consumer must be non-blocking or offload.
- **Versioned hooks accumulate (`OnGasChange` vs `OnGasChangeV2`,
  `OnNonceChange` vs `V2`, `OnCodeChange` vs `V2`, `OnSystemCallStart` vs `V2`).**
  Register *exactly one* of a V1/V2 pair or you double-count; when both are set,
  only V2 fires (see `EmitGasChange`, `hooks.go:326`). The additive struct design
  trades interface stability for this V1/V2 sprawl.
- **Revert visibility is opt-in.** Without `WrapWithJournal` a tracer never sees
  the `*Revert` reason variants, so state mutations that were rolled back on call
  failure are simply not reported as reverted — a consumer doing accounting must
  opt in or it will over-count.
- **`GasChangeIgnored` (0xFF)** is a sentinel meaning "this change is tracked
  manually elsewhere, drop it" — a consumer that naively sums all gas-change
  events without filtering it will mis-total.
- **EIP-8037 (Amsterdam) multi-dimensional gas** forced the V2 gas hook: pre-fork
  the `State` dimension is always zero. A V2-only tracer is safe across the fork
  boundary; a V1 tracer silently sees only the regular dimension post-fork.
- **No out-of-process consumer.** Everything here is in-process Go. There is no
  gRPC/IPC execution stream — an external indexer must either embed as a live
  tracer (recompile geth) or fall back to polling JSON-RPC. This is the concrete
  relative gap vs reth/erigon/nethermind and the productization opportunity.
