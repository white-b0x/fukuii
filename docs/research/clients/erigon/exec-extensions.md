# erigon — exec-extensions
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon has **no reth-style `ExEx` framework** (grep for `exex` finds only `exec3_metrics.go`
and a `.drawio` — substrings of "exec3", not an execution-extension API). Its downstream-data
surface is instead a **dual-path notification system**: after each staged-sync execution run,
the execution layer accumulates state changes and dispatches them to consumers over two
distinct transports.

- **Local path** — in-process consumers (the in-embedded TxPool, execmodule cache) receive
  **native Go types** (`BlockBatchNotification`) with zero protobuf overhead.
- **Remote path** — out-of-process consumers (a standalone RPCDaemon or TxPool process)
  receive **protobuf `StateChangeBatch` messages over a server-streaming gRPC RPC**:
  `KV.StateChanges`. This is the seam that makes erigon a *natively cross-process*
  execution client — the same node process that executes blocks publishes a live state-change
  feed that any number of gRPC clients subscribe to and consume.

The `KV.StateChanges` gRPC stream **is** erigon's exec-extension seam: it is a downstream
state-diff feed, already framed as an inter-process gRPC contract, exactly the shape fukuii's
dRPC / indexing product wants. The same `KV` gRPC service also exposes the whole database as
remote cursors/ranges (`Tx`, `Range`, `HistorySeek`, `IndexRange`) — so a remote consumer gets
both the *push* feed (state changes) and *pull* access (historical/index reads) from one seam.
Parallel gRPC services carry mining/txpool events (`Mining.OnPendingBlock` / `OnMinedBlock` /
`OnPendingLogs`) and chain events (`EthBackend.Subscribe` → newHeads/logs).

## Key types / interfaces / files

- `node/interfaces/remote/kv.proto:45` — `rpc StateChanges(StateChangeRequest) returns (stream StateChangeBatch)` — **the exec-extension gRPC contract**: server-streaming state-diff feed on the `KV` service (defined `kv.proto:34`).
- `node/interfaces/remote/kv.proto:108-125` — `StateChangeBatch` → `repeated StateChange change_batch`; each `StateChange` has `Direction` (`FORWARD`/`UNWIND`), `block_height`, `block_hash`, `repeated AccountChange changes`, and `repeated bytes txs` (raw tx RLP, gated by `withTransactions`). Comment at `:137` literally reads "list of StateDiff done in one DB transaction".
- `node/interfaces/remote/kv.proto:81-105` — `enum Action { STORAGE, UPSERT, CODE, UPSERT_CODE, REMOVE }` + `AccountChange{address, incarnation, action, data}` + `StorageChange{location, data}` — the materialized per-account/per-slot diff shape.
- `db/kv/remotedbserver/remotedbserver.go:426` — `func (s *KvServer) StateChanges(...)` — the streaming server handler: subscribes to the internal pub/sub, forwards each `StateChangeBatch` to the gRPC client, exits on ctx cancel. Uses an optional `SubscriptionReadyNotifier` (`:418`) to close the register-then-produce timing hole.
- `db/kv/remotedbserver/remotedbserver.go:446` — `func (s *KvServer) SendStateChanges(...)` — producer entry point; publishes a batch to all subscribers.
- `db/kv/remotedbserver/remotedbserver.go:485-533` — `StateChangePubSub` — a tiny in-memory fan-out (map of buffered channels, cap 8) with `Sub`/`Pub`/`remove`; `Pub` uses `common.PrioritizedSend` (`:511`) to avoid a slow subscriber stalling execution.
- `execution/notifications/interfaces.go:30` — `StateChangeConsumer{ SendStateChanges(ctx, *remoteproto.StateChangeBatch) }` — the protobuf/remote consumer interface, implemented **only** by `KvServer`; `:35` `BlockBatchConsumer{ OnNewBlock(*BlockBatchNotification) }` is the native/local counterpart.
- `execution/notifications/accumulator.go:28` — `Accumulator` — accumulates `ChangeAccount`/`ChangeStorage`/`ChangeCode`/`DeleteAccount` (`:98-161`) during block execution, indexed by address/slot for in-place update.
- `execution/notifications/accumulator.go:54` — `SendAndReset(ctx, c StateChangeConsumer, baseFee, blobFee, gasLimit, finalized)` — builds a `BlockBatchNotification`, calls `batch.ToProtoBatch()` and hands it to the consumer, then resets. **This is the native→protobuf boundary.**
- `execution/execmodule/notification_dispatcher.go:82` — `Dispatcher.Dispatch(...)` → line `:146` `accumulator.SendAndReset(...)` — the single post-execution dispatch point (also fires header/log/receipt notifications).
- `db/kv/kvcache/cache.go:286` — `func (c *Coherent) OnNewBlock(sc *remoteproto.StateChangeBatch)` — the canonical **remote consumer**: the RPCDaemon's coherent state cache applies the streamed diffs to keep its in-memory view consistent with the executing node.
- `cmd/rpcdaemon/cli/config.go` — where a standalone RPCDaemon dials the remote `KV` service and subscribes to `StateChanges` (client side of the seam); `cmd/txpool/main.go` is the standalone-txpool consumer.
- `node/privateapi/mining.go:107-170` — parallel event streams on the `Mining` gRPC service: `OnPendingLogs`/`OnPendingBlock`/`OnMinedBlock` server-streaming RPCs with `StreamBroadcaster` fan-out — the mining/txpool exec-extension events.
- `execution/notifications/README.md` — authoritative in-repo description of all three notification flows (state changes, headers, logs) and the local-vs-remote split.

## Design decisions & rationale

- **Two transports, one accumulation.** The execution layer accumulates changes once (native
  types), then a single dispatch fans them out to native consumers directly and to remote
  consumers via a protobuf conversion at the gRPC boundary (`node/privateapi/` / `KvServer`),
  **not** in the execution layer. Rationale (README): in-process consumers must not pay
  serialization cost; only the genuinely remote hop pays protobuf.
- **The DB gRPC service carries the feed.** Erigon reuses the `KV` remote-database service
  (remote cursors/ranges) to also carry `StateChanges`. A remote RPCDaemon therefore gets a
  *consistent* combination: pull historical/index data via `Tx`/`Range`/`HistorySeek` and push
  live diffs via `StateChanges`, coordinated by MDBX `view_id`/`tx_id` for snapshot consistency.
- **State cache coherence is the whole point of the remote diff.** `kvcache.Coherent.OnNewBlock`
  clones its parent view and applies `AccountChange`/`StorageChange`/`Code` to build a coherent
  view — so a remote RPCDaemon can serve `eth_call`/`eth_getBalance` from cache without a round
  trip per read. The protobuf `AccountChange` list exists specifically so the remote cache can
  update incrementally.
- **Unwind is a first-class direction.** `StateChange.Direction` carries `FORWARD`/`UNWIND`, so
  reorgs are streamed as explicit unwind batches — downstream consumers reverse state rather
  than resync. Directly relevant to any indexer that must stay reorg-correct.
- **Backpressure is bounded, not blocking.** `StateChangePubSub` uses cap-8 buffered channels
  and `PrioritizedSend` so a lagging subscriber cannot stall block execution — the feed favors
  the producer's liveness over guaranteed delivery to a slow consumer.

## Notable patterns (the reusable idea)

**Accumulate-once, dispatch-to-a-typed-consumer-interface, and let the *remote* consumer be a
gRPC stream of protobuf state-diffs.** The transferable core for fukuii is the *shape of the
seam*, not the Go code: (1) an `Accumulator` that records per-account/per-slot diffs during
execution; (2) a narrow `StateChangeConsumer.SendStateChanges(batch)` interface at the dispatch
point; (3) a server-streaming gRPC RPC (`StateChanges → stream StateChangeBatch`) as the
concrete remote implementation, with `Direction` for reorg-safety and raw `txs` bundled in.
Fukuii's dRPC / indexing product wants exactly this: a cross-process, reorg-aware, protobuf
state-diff feed that an indexer / archival RPC / trace-store subscribes to — and erigon proves
it can be the *same* service that also exposes remote DB reads, so one gRPC seam serves both
push (diffs) and pull (history/index).

## Authority note

reth's `ExEx` is the dedicated execution-extension framework (in-process async tasks that
receive `ExExNotification`s and emit back-pressure via `ExExEvent`, committed against a
finality height). **Erigon is the gRPC-`StateChanges`-stream variant** — no in-process
extension-task API, but the notification is *already cross-process* over gRPC by design, which
is closer to fukuii's dRPC seam than reth's in-process model. For PoW/ETC consensus semantics
core-geth remains the authority; erigon here is authoritative only for the *downstream-data /
gRPC-feed architecture*, which is consensus-neutral plumbing.

## Gotchas / anti-patterns / things they later changed

- **`node/shards` is now a compatibility shim.** `node/shards/state_change_accumulator.go` and
  `events.go` are thin re-export wrappers ("New code should import `execution/notifications`
  directly") — the accumulator/notifier moved to `execution/notifications`. Don't cite `shards`
  as the source of truth; it's a deprecation seam.
- **Delivery is best-effort, not guaranteed.** The cap-8 `PrioritizedSend` fan-out will drop
  behind (or the priority-send can preempt) for a slow subscriber — an indexer that must not
  miss a batch cannot rely on the stream alone; it needs a resync/reconcile path (which is why
  the same `KV` service also exposes range/history reads).
- **Register-then-produce race was patched.** The `SubscriptionReadyNotifier` /
  `NotifySubscribed` hook (`remotedbserver.go:418-434`) exists because there was a timing hole
  between the server goroutine starting and the subscription actually being live — a subscriber
  could miss the first batch. Any reimplementation needs an explicit "subscription ready" ack.
- **Protobuf conversion location matters.** Native→protobuf conversion happens at the gRPC
  boundary, not in execution (`SendAndReset` → `ToProtoBatch`). Putting serialization in the
  execution/accumulation hot path would tax in-process consumers that don't need it — the
  README calls this out as deliberate.
- **`incarnation` is an MDBX/erigon storage concept.** `AccountChange.incarnation` reflects
  erigon's account-versioning for self-destruct/recreate; a consumer that doesn't model
  incarnations (fukuii uses RocksDB, different account model) must map or ignore it carefully.
