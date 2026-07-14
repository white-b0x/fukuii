# reth — exec-extensions
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth's **Execution Extensions (ExEx)** framework is the flagship reference for
this slot — reth invented it, and it is the direct model for an in-process,
reorg-aware, backpressured data-product seam (indexer, rollup, dRPC provider,
trace-store, archival feed). The core idea (`crates/exex/exex/src/lib.rs:1-24`):

> "An execution extension is a task that listens to state changes of the node.
> Some examples of such state derives are rollups, bridges, and indexers. An
> `ExEx` is a Future resolving to `Result<()>` that is run indefinitely
> alongside the node."

The design is a closed feedback loop between the node and each extension:

1. **Node → ExEx (notifications):** whenever a canonical chain change happens
   (commit / reorg / revert), the node fans a reorg-aware `ExExNotification` out
   to every installed ExEx over a bounded async channel.
2. **ExEx → Node (events / backpressure):** each ExEx reports the height it has
   *finished* processing via `ExExEvent::FinishedHeight`. The node aggregates
   the **lowest** finished height across all ExExes and uses that as the gate for
   what state/WAL is safe to prune. An ExEx that falls behind exerts real
   backpressure — the node's bounded notification buffer fills, and senders stop
   until the slow ExEx catches up.
3. **Reorg-safety (WAL):** every notification from the blockchain tree is first
   committed to a Write-Ahead Log on disk *before* being handed to ExExes, so
   that on restart the node can replay/revert notifications and keep each ExEx's
   derived state consistent with the canonical chain across reorgs.

An ExEx is an **async closure** installed on the `NodeBuilder`
(`install_exex(id, closure)`), receiving an `ExExContext` (node handle +
notification stream + event sender). It runs as a *critical task* — if it
returns or crashes, the node panics (`launch/exex.rs:128-131`), because a
data-product that silently dies would produce a silently-diverging index. This
is the deliberate "fail loudly" choice.

## Key types / interfaces / files

- `crates/exex/exex/src/lib.rs:1-114` — crate root + the canonical
  "Simple Indexer ExEx" example (lines 30-57): loop over
  `ctx.notifications.next().await`, index `notification.committed_chain()`, then
  `ctx.send_finished_height(committed.tip().num_hash())`. This 25-line example
  *is* the intended data-product shape.
- `crates/exex/exex/src/context.rs:15-40` — **`ExExContext<Node>`**: what an ExEx
  receives. Fields: `head` (chain head at launch), `config`/`reth_config`,
  `events: UnboundedSender<ExExEvent>` (the ack channel — doc-commented "the exex
  should emit a `FinishedHeight` whenever a processed block is safe to prune"),
  `notifications: ExExNotifications` (the reorg-aware event stream), and
  `components: Node` (full node handle — pool, provider, evm_config, network,
  payload builder, task executor via accessors at lines 76-108).
- `crates/exex/exex/src/context.rs:126-131` — **`send_finished_height(height)`**:
  the ergonomic wrapper that sends `ExExEvent::FinishedHeight`. This single call
  is the entire ExEx→node backpressure/ack contract from the extension's side.
- `crates/exex/types/src/notification.rs:10-28` — **`ExExNotification<N>`**, the
  reorg-aware event enum with exactly three variants:
  - `ChainCommitted { new }` — chain extended, no reorg.
  - `ChainReorged { old, new }` — reorg: both the reverted (`old`) and the new
    chain are delivered, so a data product can undo and redo.
  - `ChainReverted { old }` — chain rolled back.
  Helpers `committed_chain()` / `reverted_chain()` (lines 33-47) and
  `into_inverted()` (lines 55-61, used by WAL replay to undo a notification).
  Built `From<CanonStateNotification>` (lines 64-71) — ExEx notifications are the
  canonical-state stream, re-shaped for reorg-explicitness.
- `crates/exex/exex/src/event.rs:5-13` — **`ExExEvent::FinishedHeight(BlockNumHash)`**:
  "Highest block processed by the ExEx. The ExEx must guarantee it will not
  require all earlier blocks in the future, meaning Reth is allowed to prune
  them. On reorgs, it's possible for the height to go down." One variant — the
  whole ack protocol is this one message.
- `crates/exex/types/src/finished_height.rs:5-18` — **`FinishedExExHeight`**:
  the aggregated gate. `NoExExs` (nothing installed) / `NotReady` (not all ExExes
  have acked yet — nothing may be pruned) / `Height(n)` = the **lowest common
  denominator** across all ExExes; "all blocks `<= finished_height` are safe to
  prune." This is the value the rest of the node reads to gate pruning.
- `crates/exex/exex/src/manager.rs:207-260` — **`ExExManager<P, N>`**: the driver.
  Owns the `Vec<ExExHandle>`, a monotonic-ID notification `buffer: VecDeque`, the
  `max_capacity`/`current_capacity` (the backpressure knob), the `Wal`, the
  `finalized_header_stream`, and two `watch` channels (`is_ready`,
  `finished_height`). Doc-listed responsibilities (lines 209-215): "Receiving
  events… Backpressure… Error handling… Monitoring."
- `crates/exex/exex/src/manager.rs:458-552` — the manager's `Future::poll` loop,
  the heart of the system (see Notable patterns).
- `crates/exex/exex/src/manager.rs:78-93` — **`ExExHandle`**: per-ExEx state the
  manager holds: `sender: PollSender<ExExNotification>` (bounded, capacity 1 —
  line 107), `receiver: UnboundedReceiver<ExExEvent>`, `next_notification_id`,
  and `finished_height: Option<BlockNumHash>`.
- `crates/exex/exex/src/manager.rs:130-188` — `ExExHandle::send`: the
  finished-height-aware delivery. If the ExEx's `finished_height >= new.tip()`
  for a `ChainCommitted`, the notification is **skipped** (already processed);
  but `ChainReorged`/`ChainReverted` are *always* delivered even below the
  finished height (lines 155-160) — an ExEx must always see reverts/reorgs.
- `crates/exex/exex/src/manager.rs:34-48` — capacity constants:
  `DEFAULT_EXEX_MANAGER_CAPACITY = 1024` ("3.5 hours of mainnet blocks") and
  `DEFAULT_WAL_BLOCKS_WARNING = 128` (warn if WAL isn't clearing → the ExEx isn't
  emitting `FinishedHeight`).
- `crates/exex/exex/src/wal/mod.rs:28-81` — **`Wal<N>`**: the reorg-safety WAL.
  "stores the notifications sent to ExExes… On every new canonical chain
  notification call `commit`. When the chain is finalized call `finalize` to
  prevent infinite growth." Backed by a directory of files + an in-memory
  `BlockCache` to avoid re-decoding on every finalize/iterate.
- `crates/exex/exex/src/wal/storage.rs:16-18` — WAL storage: **one file per
  notification**, MessagePack-encoded (`{file_id}.wal`).
- `crates/exex/exex/src/notifications.rs:258-293` — **`ExExNotificationsWithHead`**:
  the "resume from where I left off" mode. Given an `ExExHead` (the height the
  ExEx persisted), on launch it (a) checks the ExEx head is still canonical and
  reverts it if a reorg happened while offline (`pending_check_canonical`), then
  (b) runs a **backfill job** to catch the ExEx up to the node head
  (`pending_check_backfill` + `backfill_job`), buffering live notifications that
  arrive during backfill so none are dropped (`pending_notifications`, lines
  290-292). This is how a restarted indexer deterministically resumes.
- `crates/exex/types/src/head.rs:5-9` — **`ExExHead { block: BlockNumHash }`**:
  "the highest host block committed to the internal ExEx state" — the ExEx's
  durable cursor.
- `crates/node/builder/src/builder/states.rs:220-228` &
  `crates/node/builder/src/builder/mod.rs:645-662` — **`install_exex(id, closure)`**
  / `install_exex_if(cond, id, closure)`: the public NodeBuilder API. The closure
  is `FnOnce(ExExContext) -> Future<Output = Future<Output = Result<()>>>` — an
  async init that resolves to the long-running ExEx future.
- `crates/node/builder/src/launch/exex.rs:69-175` — **`ExExLauncher::launch`**:
  the wiring. Opens the shared `Wal` at `datadir/exex/wal` (lines 82-89), builds
  an `ExExHandle` + `ExExContext` per extension, spawns each ExEx as a critical
  task (panics if it exits), spawns the `ExExManager`, then bridges
  `provider().subscribe_to_canonical_state()` into the manager as
  `ExExNotificationSource::BlockchainTree` notifications (lines 158-170).

## Design decisions & rationale

- **One-message ack = FinishedHeight.** Rather than a rich acknowledgement
  protocol, an ExEx reports a single monotonic-ish height (can decrease on
  reorg). The node keeps the *lowest* across all ExExes as the prune gate. This
  makes "when is it safe to prune/finalize?" a simple `min()` over a set of
  watch-channel values — trivially correct and composable across N independent
  extensions.
- **Bounded buffer = automatic backpressure.** The manager buffer is capped
  (`max_capacity`, default 1024). `update_capacity` (`manager.rs:345-354`)
  publishes `capacity > 0` on an `is_ready` watch channel; senders call
  `has_capacity()`/`ready()` before sending (`manager.rs:598-655`). A slow ExEx
  → full buffer → `is_ready=false` → the execution stage/tree stops producing.
  The doc on `ExExManager::new` (lines 271-272): "When the capacity is exceeded
  (which can happen if an ExEx is slow) no one can send notifications… until
  there is capacity again." No unbounded memory growth, no dropped events.
- **Per-ExEx bounded sender, manager-owned reorderable buffer.** Each
  `ExExHandle` sender has capacity 1 (`manager.rs:107`); the manager holds the
  real buffer with per-notification monotonic IDs and tracks `next_notification_id`
  per ExEx, so a fast ExEx isn't blocked by a slow one — the manager only retains
  a buffered notification until it has been delivered to **all** ExExes
  (`manager.rs:529-532`, `retain(|(id,_)| id >= min_id)`).
- **WAL only for tree notifications, never pipeline.** `ExExNotificationSource`
  (`manager.rs:54-60`) distinguishes `Pipeline` (staged sync — blocks already
  final, never reorged) from `BlockchainTree` (live — reorg-able). Only tree
  notifications are committed to the WAL (`manager.rs:491-499`); pipeline ones
  skip it. Rationale in the enum doc: pipeline notifications "are already
  finalized," so WAL-logging them would be wasted disk.
- **Finalize only when all ExExes are canonical.** `finalize_wal`
  (`manager.rs:374-437`) will only trim the WAL if *every* ExEx's finished-height
  hash is still canonical (`provider.is_known(hash)`); it then finalizes to the
  min of (lowest ExEx finished height, finalized header). If any ExEx is on a
  non-canonical hash, it refuses to finalize and logs the laggards — the WAL is
  the thing that lets that ExEx recover, so it must not be discarded.
- **Resume via head + backfill, not "start from genesis every time."** An ExEx
  persists its own `ExExHead`; on restart the WithHead stream reverts any
  now-orphaned head, backfills to the node tip, and only then resumes live —
  making an indexer's derived state deterministic across restarts and reorgs.
- **ExEx exit is fatal.** A finished/crashed ExEx panics the node
  (`launch/exex.rs:128-131`) — a silently-dead data product is worse than a dead
  node, because downstream consumers would trust a frozen index.

## Notable patterns (the reusable idea)

**The reusable idea: a downstream extension processes reorg-aware chain
notifications in-process but async, and its acknowledged height gates node
pruning.** Concretely, the manager's single `poll` loop
(`manager.rs:447-552`) executes this order every wakeup:

1. **Drain ExEx events** → update each handle's `finished_height`
   (lines 461-470).
2. **Drain the finalized-header stream → finalize the WAL** if all ExExes are
   canonical (lines 472-479).
3. **Ingest new notifications** into the buffer (up to capacity), WAL-committing
   tree notifications (lines 481-505).
4. **Deliver** buffered notifications to each ready ExEx by ID (lines 508-527).
5. **Retain** only notifications not yet seen by *all* ExExes; advance `min_id`
   (lines 529-532).
6. **Recompute capacity** and wake senders if the buffer drained (lines 534-541).
7. **Publish** the new lowest `FinishedExExHeight` on the watch channel
   (lines 544-549).

Two sub-patterns worth stealing wholesale:

- **`FinishedHeight` as a distributed prune barrier.** N independent async
  consumers each publish a height; the producer gates irreversible cleanup on
  `min(heights)`. This is the exact backpressure+safety mechanism that besu's
  synchronous `BesuEvents` callbacks lack (see `../besu/exec-extensions.md`) — a
  besu listener can be slow or crash and the node has no notion of "wait for the
  indexer before pruning."
- **Reorg-explicit notifications + WAL replay.** Because every notification
  carries both `old` and `new` chains for reorgs, and the WAL persists them,
  a data product never has to reconstruct "what did I need to undo?" — the node
  hands it the reverted chain directly, and `into_inverted()`
  (`notification.rs:55-61`) lets the WAL generate the undo on recovery.

## Authority note

reth = **THE ExEx (Execution Extensions) authority** — the flagship
exec-extension framework; reth invented it, and every piece here (the
reorg-aware `ExExNotification`, the `FinishedHeight` backpressure/prune gate,
the per-notification WAL for reorg-safety, resume-via-backfill) is the reference
design for an in-process data-product seam. The peer clients are strictly
weaker on this axis and lack its backpressure + WAL:
- **besu** `BesuEvents` — synchronous in-process listeners, no height ack, no
  prune gate, no WAL (`../besu/exec-extensions.md`).
- **erigon** gRPC `StateChanges` — out-of-process streaming, no built-in
  reorg-safe replay or acked-height pruning contract.
- **nethermind** `TraceStore` / plugin events — trace persistence, not a
  backpressured reorg-aware notification framework.

## Gotchas / anti-patterns / things they later changed

- **Forgetting to emit `FinishedHeight` = unbounded WAL growth.** If an ExEx
  never acks, the WAL is never finalized. reth guards this with an explicit
  warning once the WAL exceeds `wal_blocks_warning` (default 128,
  `manager.rs:409-416`): *"WAL contains too many blocks and is not getting
  cleared… Check that you emit the FinishedHeight event from your ExExes."*
  For fast L2 block times this threshold must be raised proportionally
  (`with_wal_blocks_warning`, `manager.rs:333-341`).
- **Reverts/reorgs are delivered even below your finished height.** The
  skip-optimization only applies to `ChainCommitted` (`manager.rs:135-160`) — an
  ExEx must handle `ChainReorged`/`ChainReverted` for blocks it thought it had
  finished, because on a reorg its finished height can legitimately move *down*.
- **A finished/crashed ExEx panics the whole node** (`launch/exex.rs:128-131`).
  ExExes must be written to run indefinitely and recover from transient DB/network
  errors internally (`lib.rs:59-71` invariants), not to return.
- **ExEx must not block the node** (`lib.rs:66-71`). Because the per-ExEx sender
  has capacity 1 and the manager buffer is bounded, a synchronously-slow ExEx
  directly throttles block execution — CPU-heavy work belongs on a spawned task
  via `ctx.task_executor()`, not inline in the notification loop.
- **A real deadlock was fixed here.** The manager must `wake_by_ref()` after the
  buffer drains from full (`manager.rs:537-541`); without it a full buffer that
  later clears could leave the manager asleep with a pending item stuck in the
  unbounded ingest channel. The regression test
  `test_deadlock_manager_wakes_after_buffer_clears` (`manager.rs:1422-1494`)
  pins this — a caution that the backpressure interaction between the bounded
  buffer and the unbounded ingest channel is subtle.
- **`ExExNotificationSource` must be set correctly.** Mislabeling a live
  (tree) notification as `Pipeline` would skip its WAL commit and break reorg
  recovery for that block; the distinction is load-bearing, not cosmetic.
