# besu — exec-extensions
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

## Architecture summary

besu's execution-extension mechanism is the **`plugin-api` module** — a single,
separately-versioned Java module (`org.hyperledger.besu.plugin.*`) that is besu's
one public, stability-guaranteed API surface. A third party builds a data product
(indexer, notifier, trace-store, data-feed) as a **plugin jar** dropped into besu's
plugin directory and discovered via `java.util.ServiceLoader`; it never forks besu.

The model has three moving parts:

1. **Lifecycle contract** — every plugin implements `BesuPlugin` (`register` →
   `beforeExternalServices` → `start` → `stop`). At `register` time the plugin is
   handed a `ServiceManager` (the *only* time it gets one), which it stashes.
2. **Service locator** — `ServiceManager.getService(Class<T>)` returns
   `Optional<T>` of any `BesuService`. Services may be absent (not started yet, not
   supported by this besu version, or not applicable to the current network), so
   the plugin degrades gracefully rather than hard-failing.
3. **Event + query services** — the plugin pulls the services it needs:
   `BesuEvents` (subscribe to live chain events), `TraceService` /
   `BlockAwareOperationTracer` (out-of-core re-execution/tracing), `TrieLogService`
   (Bonsai state-diff feed), `BlockchainService` (random-access block/receipt
   reads), and `RpcEndpointService` (expose the data product back out over JSON-RPC).

Everything is **in-process JVM callbacks** — listeners are Java functional
interfaces invoked on besu's own threads. There is no gRPC/Kafka/streaming
transport inside `plugin-api`; the serialization boundary (if any) is the plugin's
own responsibility. This is richer than geth's in-process-only hooks (typed,
filterable, deregisterable events across block/tx/sync/reorg/log/bad-block) but
still in-VM, unlike reth ExEx or nethermind's out-of-process TraceStore.

## Key types / interfaces / files

_All paths under `plugin-api/src/main/java/org/hyperledger/besu/plugin/`._

- `services/BesuEvents.java:49` — **the event-subscription API** (extends
  `BesuService`). Register/deregister listeners for the full downstream-data seam:
  - `:57` `addBlockPropagatedListener` — header validated, about to gossip (pre-body).
  - `:72` `addBlockAddedListener` — block evaluated + validated (canonical progress).
  - `:87` `addBlockReorgListener` — chain head switched to a different fork.
  - `:102` `addInitialSyncCompletionListener` — initial sync done/restarted.
  - `:111` `addTransactionAddedListener` / `:127` `addTransactionDroppedListener` —
    mempool admission/eviction (dropped carries a `reason` string).
  - `:142` `addSyncStatusListener` — synchronizer status changes (`Optional<SyncStatus>`).
  - `:160` `addLogListener(List<Address>, List<List<Bytes32>>, LogListener)` — **a
    server-side-filtered log feed** (address + topic filter), delivering added *and
    removed* `LogWithMetadata` per new block — the direct analog of `eth_getLogs`
    push, ideal for an event-indexer.
  - `:176` `addBadBlockListener` — bad-block header + `BadBlockCause`.
  - Every `add…` returns a `long` id; the matching `remove…(long)` deregisters.
  - Nested listener interfaces at `:186`–`:301` (`BlockAddedListener.onBlockAdded`,
    `SyncStatusListener.onSyncStatusChanged`, `LogListener.onLogEmitted`, etc.).
- `services/TraceService.java:27` (`@Unstable`) — **out-of-core tracing for
  indexers**. `traceBlock(long|Hash, BlockAwareOperationTracer)` re-executes a
  historical block through a plugin-supplied tracer; `trace(from, to,
  Consumer<WorldUpdater> beforeTracing, Consumer<WorldUpdater> afterTracing,
  tracer)` walks a *range* with pre/post world-state hooks — the mechanism a
  trace-store plugin uses to backfill.
- `services/tracer/BlockAwareOperationTracer.java:29` — the tracer contract
  (extends EVM `OperationTracer`) with block-boundary hooks `traceStartBlock` /
  `traceEndBlock` (`:51`, `:63`) and a `NO_TRACING` no-op default (`:35`).
- `services/TrieLogService.java:29` + `services/trielogs/TrieLogEvent.java:18` —
  **the Bonsai state-diff feed**. A plugin registers `TrieLogObserver`s
  (`TrieLogEvent.java:40`); `onTrieLogAdded(event)` fires with a `TrieLog` *layer*
  (per-block account/storage delta) — the richest raw signal for a state-indexing
  product. Plugin can also supply its own `TrieLogFactory` (serde) and a
  `TrieLogProvider` for retrieval (`TrieLogService.java:43`,`:50`).
- `services/BlockchainService.java:33` (`@Unstable`) — random-access reads:
  `getBlockByNumber/Hash`, `getBlockHeaderByHash`, `getReceiptsByBlockHash`,
  `getTransactionByHash`, chain-head/safe/finalized accessors, `getChainId`,
  `getHardforkId` — plus write paths `storeBlock` (`:96`) and `setFinalizedBlock` /
  `setSafeBlock` (non-PoS only, `:134`,`:144`).
- `services/RpcEndpointService.java:30` — **how the data product exposes itself**:
  `registerRPCEndpoint(namespace, functionName, Function<PluginRpcRequest, T>)`
  publishes a `namespace_functionName` JSON-RPC method backed by plugin code
  (Jackson-serialized result); `call(methodName, params)` lets the plugin invoke
  besu's own in-process RPC methods.
- `BesuPlugin.java:27` — lifecycle base interface; ServiceLoader discovery
  documented at `:22`. Register listeners in `start()`, remove them in `stop()`.
- `ServiceManager.java:24` — the service locator (`addService` / `getService →
  Optional`), with a `SimpleServiceManager` `ConcurrentHashMap` impl for tests (`:59`).
- `services/BesuService.java:23` — empty marker interface; the type bound on every
  resolvable service.
- `data/AddedBlockContext.java:28` — event payload for block-added/reorg: carries
  an `EventType` enum (`HEAD_ADVANCED` / `FORK` / `CHAIN_REORG` / `STORED_ONLY`,
  `:36`) plus the block's `TransactionReceipt`s — so a listener knows *why* the
  block arrived without re-querying.

## Design decisions & rationale

- **plugin-api as a first-class, isolated public API.** The seam lives in its own
  Gradle module with no besu-internal dependencies leaking in — payloads are
  plugin-local `data/*` interfaces (`AddedBlockContext`, `BlockContext`,
  `LogWithMetadata`, `SyncStatus`), not besu's internal domain classes. This lets
  besu evolve internals while holding the plugin ABI stable.
- **Service-locator over dependency-injection.** `getService` returns `Optional`
  precisely because "plugins are automatically loaded, unless the user has
  specifically requested functionality provided by the plugin, no error should be
  raised if required services are unavailable" (`ServiceManager.java:47`). A plugin
  that wants `TraceService` on a besu build/network that lacks it simply no-ops.
- **`long`-id listener tokens.** Symmetric `add…→long`, `remove…(long)` gives
  clean per-listener lifecycle without exposing listener identity/equals semantics.
- **Server-side log filtering in the event API.** `addLogListener` takes the
  address + topic filter up front (`BesuEvents.java:160`) so besu does the matching
  and the plugin only receives relevant logs — critical for indexer throughput.
- **Typed reorg semantics.** `AddedBlockContext.EventType` distinguishes head
  advance / fork / reorg / stored-only, so a data product can maintain a correct
  canonical view (roll back on `CHAIN_REORG`) instead of guessing.
- **`@Unstable` annotation** on `TraceService`, `BlockchainService`, and several
  methods explicitly carves out APIs that may change, keeping the *stable* core of
  the ABI small and trustworthy.

## Notable patterns (the reusable idea)

**The single most transferable pattern for fukuii: a versioned, isolated
`plugin-api` module whose center is an in-process, typed, deregisterable *event
subscription* service (`BesuEvents`) — plus a paired outbound seam
(`RpcEndpointService`) so the extension can publish its product back out.**

Concretely, the reusable shape is:

1. A marker-typed **service locator** (`getService(Class): Optional`) that
   decouples "what besu offers" from "what the plugin needs" and degrades
   gracefully.
2. An **event bus of `add…(listener): long` / `remove…(long)`** methods covering
   the downstream-data surface: block-added, reorg (with typed cause), tx
   added/dropped, sync-status, **filtered logs**, bad-block, trie-log (state diff).
3. Payloads that are **API-local interfaces carrying enough context** (receipts,
   reorg `EventType`, log metadata) that a listener rarely needs a follow-up query.
4. A **backfill seam** (`TraceService.trace(from,to,…)` with before/after world
   hooks) so a newly-installed indexer can catch up on history, not just tail live.

For fukuii's dRPC/indexing product-family component, this is the exact contract a
data product would consume: subscribe to canonical block/log/state-diff events,
maintain a reorg-correct index, backfill via a trace/range seam, and re-expose
queries via a plugin-registered RPC namespace — **all without forking the node**.

## Authority note

besu = the **plugin-event-subscription JVM authority**: the reference design for
an in-process, typed, filterable, deregisterable event API (block/reorg/tx/sync/
**log-filtered**/bad-block/trie-log) delivered to third-party jars over a stable
plugin ABI. It is the JVM-native analog fukuii can mirror most directly.

The **richer, out-of-process peers** are reth's **ExEx** (Execution Extensions —
async state-diff streams with explicit `FinishedHeight` back-pressure/reorg
acknowledgement, designed for decoupled processes) and nethermind's **TraceStore /
plugin pipeline**. besu's seam is more capable than geth's (which is largely
in-process live hooks only) but stops at the JVM boundary — it has no built-in
gRPC/streaming transport, so any cross-process fan-out is the plugin's to build.

## Gotchas / anti-patterns / things they later changed

- **In-VM coupling / no back-pressure.** Listeners run on besu's own threads with
  no queue or flow-control in the API. A slow `onBlockAdded`/`onLogEmitted` blocks
  node progress — the plugin must hand off to its own executor. This is exactly the
  gap reth's ExEx `FinishedHeight` signal closes; fukuii should design in an async
  boundary + back-pressure from the start rather than copying the synchronous shape.
- **`@Unstable` surfaces will move.** `TraceService`, `BlockchainService`, and
  `HardforkId` accessors are annotated unstable — don't treat them as a frozen ABI.
- **`register`-time-only `ServiceManager`.** The context is passed exactly once, at
  `register` (`BesuPlugin.java:44`); a plugin that fails to stash it can never
  reach any service. `RpcEndpointService` registration is *also* restricted to the
  registration callback (`RpcEndpointService.java:26`).
- **Manual dereg is mandatory.** Listeners are not auto-removed; a plugin that
  doesn't `remove…(id)` in `stop()` leaks callbacks across reloads.
- **`PluginRpcRequest` inputs are strings only.** Complex input objects aren't
  supported and JS numbers arrive as strings (`RpcEndpointService.java:44`) — the
  plugin parses/validates them itself.
- **`TrieLogService` is Bonsai-specific.** The state-diff feed presupposes the
  Bonsai (trie-log) storage format; on a non-Bonsai/flat or forest layout there is
  no trie-log stream to observe. A fukuii port must decide whether its state-diff
  seam is storage-format-agnostic.
- **`TrieLogEvent.Type` has a single value (`ADDED`).** No removal/prune event yet
  — an indexer can't observe trie-log pruning through this API.
