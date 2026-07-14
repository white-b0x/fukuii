# reth — cl-engine
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth was built Engine-API-native (PoS-first): the engine is the center of the node,
not a plugin bolted onto a pre-merge block-import path. The consensus layer (CL)
drives the execution layer entirely through three Engine-API messages —
`newPayload`, `forkchoiceUpdated`, and the payload-build handshake — and reth models
this as a **single-threaded state machine that owns an in-memory tree of
executed-but-unfinalized blocks**, decoupled from disk by an **asynchronous
persistence task** running on its own OS thread.

The layering, from the CL socket inward:

1. **`ChainOrchestrator`** (`crates/engine/tree/src/chain.rs`) — the outermost
   `Stream` state machine. It polls a `ChainHandler` to advance the chain and
   arbitrates the one thing that cannot be concurrent: exclusive DB write access
   between live sync and backfill (staged-sync pipeline). Live handler and backfill
   are mutually exclusive by construction.
2. **`EngineHandler`** (`crates/engine/tree/src/engine.rs:39`) — a `ChainHandler`
   that consumes the incoming `BeaconEngineMessage` stream from the Engine-API RPC
   endpoint, forwards each request to the tree, and services on-demand block
   *download* requests the tree emits (for gap-filling during live sync).
3. **`EngineApiTreeHandler`** (`crates/engine/tree/src/tree/mod.rs:266`) — the heart.
   Runs on its own high-priority OS thread (`spawn_os_thread("engine", …)`,
   `mod.rs:474`), owns the in-memory `TreeState`, and processes `newPayload`/`fcU`
   synchronously in a blocking `run()` loop (`mod.rs:531`).
4. **`PersistenceService`** (`crates/engine/tree/src/persistence.rs:41`) — a second
   OS thread that does all blocking RocksDB/static-file I/O (block writes, pruning,
   reorg removals) so the engine thread never blocks on disk.

The two threads communicate over channels only: the engine thread sends
`SaveBlocks`/`RemoveBlocksAbove` actions and gets a `PersistenceResult` back
(`persistence.rs:234`). While a save is in flight the engine keeps executing new
payloads into memory. `build_engine_orchestrator` (`crates/engine/tree/src/launch.rs`)
wires all four together.

## Key types / interfaces / files

- `crates/engine/tree/src/tree/mod.rs:266` — **`EngineApiTreeHandler<N,P,T,V,C>`**: the
  engine tree. Fields of note: `state: EngineApiTreeState` (in-memory tree),
  `persistence: PersistenceHandle` + `persistence_state: PersistenceState` (async flush
  bookkeeping), `canonical_in_memory_state` (RPC-readable view of unpersisted tip),
  `incoming_tx`/`incoming` (self-requeue channel so long work — e.g. a downloaded range
  of up to 3 epochs — is chunked and interleaved with fresh CL requests).
- `crates/engine/tree/src/tree/mod.rs:531` — **`run()`**: the blocking main loop. Each
  iteration: (1) non-blocking poll for persistence completion (`try_poll_persistence`),
  (2) wait for the next event — biased toward persistence completion — (3) handle it,
  (4) `advance_persistence()`.
- `crates/engine/tree/src/tree/mod.rs:717` — **`on_new_payload`**: validates layout/blob
  rules (must run "instantly … even during active sync"), inserts into the tree if
  backfill is idle else buffers, and marks the block canonical if it is the fcU sync
  target.
- `crates/engine/tree/src/tree/mod.rs:1128` — **`on_forkchoice_updated`**: applies the
  CL's head/safe/finalized, makes the target canonical (`make_canonical`, `mod.rs:1929`),
  and — if payload attributes are present — kicks off block building.
- `crates/engine/tree/src/tree/mod.rs:141` — **`EngineApiTreeState`**: `tree_state`
  (the tree), `forkchoice_state_tracker`, `buffer` (detached blocks awaiting parents),
  `invalid_headers` (poisoned-ancestor cache).
- `crates/engine/tree/src/tree/state.rs:24` — **`TreeState`**: the in-memory unfinalized
  tree. `blocks_by_hash: B256Map<ExecutedBlock>`, `blocks_by_number: BTreeMap<_, Vec<_>>`,
  `parent_to_child: B256Map<B256Set>`, `current_canonical_head`. Only stores blocks
  connected to the canonical chain; supports multiple competing branches for reorgs.
- `crates/engine/tree/src/persistence.rs:41` — **`PersistenceService`**: the disk thread.
  `run()` (`persistence.rs:93`) is an endless `recv()` loop over `PersistenceAction`.
  Batches `finalized`/`safe` updates into the next `SaveBlocks` commit to avoid
  per-update fsyncs (`pending_finalized_block`/`pending_safe_block`).
- `crates/engine/tree/src/persistence.rs:257` — **`PersistenceHandle`** + `spawn_service`
  (`:279`): spawns the persistence OS thread; `save_blocks`/`remove_blocks_above` are the
  action senders. A `ServiceGuard` (`Arc`) joins the thread when the last handle drops so
  RocksDB is released cleanly.
- `crates/engine/tree/src/tree/mod.rs:2063` — **`should_persist`** and `:2080`
  **`get_canonical_blocks_to_persist`**: policy for *which* in-memory blocks flush to disk
  — everything above the last-persisted block, up to `canonical_head - memory_block_buffer_target`.
- `crates/engine/tree/src/tree/payload_processor/mod.rs:282` — **`PayloadProcessor::spawn`**:
  the concurrent execution + state-root pipeline (see state-trie.md). Spawns the
  state-root task (`spawn_state_root`, `:386`) *before* execution begins, so the trie
  multiproof/sparse-trie computation runs concurrently with EVM execution rather than
  after it.
- `crates/engine/primitives/src/message.rs:239` — **`BeaconEngineMessage`**: the Engine-API
  message ADT (`NewPayload`, `RethNewPayload`, `ForkchoiceUpdated`), each carrying a
  `oneshot` reply channel back to the RPC layer.
- `crates/engine/primitives/src/forkchoice.rs:6` — **`ForkchoiceStateTracker`**: remembers
  latest received fcU, current sync target, and last-valid fcU.
- `crates/consensus/consensus/src/lib.rs:74` — **`FullConsensus`/`Consensus`/`HeaderValidator`**:
  the consensus-validation traits the tree calls (`validate_header`,
  `validate_block_pre_execution`, `validate_block_post_execution`). The engine tree is
  generic over these, so PoW-Ethash and PoS validation slot into the *same* driver.
- `crates/engine/primitives/src/config.rs:7` — **`TreeConfig`** knobs:
  `DEFAULT_PERSISTENCE_THRESHOLD = 2` (persist when >2 canonical blocks are in memory),
  `DEFAULT_MEMORY_BLOCK_BUFFER_TARGET = 0` (how many blocks to keep back from disk),
  `DEFAULT_PERSISTENCE_BACKPRESSURE_THRESHOLD = 16`.

## Design decisions & rationale

- **In-memory tree decoupled from disk.** `newPayload` executes a block into the tree
  and returns `VALID` to the CL *without waiting for a disk write*. Persistence happens
  lazily in the background once enough blocks accumulate (`should_persist`, threshold 2).
  This keeps the CL's newPayload→fcU latency off the RocksDB critical path — the single
  most important reason reth achieves fast payload turnaround.
- **Async persistence on a dedicated thread.** All blocking I/O (block writes, static
  files, pruning, reorg block removal) lives in `PersistenceService` so the engine thread
  is never stalled by fsync. The engine only *starts* a save and polls for completion.
- **Soft backpressure, not hard.** When the canonical-to-persisted gap exceeds
  `persistence_backpressure_threshold` (16), the loop stops draining the incoming channel
  and blocks only on persistence completion (`should_backpressure`, `mod.rs:520`;
  `wait_for_persistence_event`, `:606`). The extended comment at `mod.rs:531+` is candid
  that this is only advisory: the Engine API has no backpressure semantics and CLs resend
  after ~8s, so this merely shifts the growing queue from the heavy persistence pipeline
  to the light incoming channel.
- **Biased select toward persistence.** `wait_for_event` (`mod.rs:623`) uses
  `crossbeam_channel::select_biased!` to prefer a completed persistence result over a new
  engine message, so in-memory state and the last-persisted watermark advance promptly and
  unblock further writes.
- **Generic over consensus.** The tree takes `Arc<dyn FullConsensus<N>>` and an
  `EngineValidator`; PoW vs PoS is a matter of which validator/consensus impl is injected,
  not a separate code path. `EngineApiKind` (Ethereum vs Optimism) is a small variant, not
  a fork of the driver.
- **Batched finalized/safe writes.** `SaveFinalizedBlock`/`SaveSafeBlock` are deferred and
  folded into the next `SaveBlocks` fsync (`persistence.rs:56`) rather than each triggering
  its own disk sync.

## Notable patterns (the reusable idea)

**An in-memory tree of executed-but-unfinalized blocks + an asynchronous
persistence task, joined by a channel and a "persist when N blocks deep" watermark.**
The engine answers `newPayload`/`fcU` entirely from memory and returns immediately;
a background thread lazily flushes the settled tail of the canonical chain to disk and
reports back a last-persisted watermark, which the engine uses to prune the in-memory
tree (`remove_before`, `mod.rs:3335`; `on_new_persisted_block`, `:2131`). Reorgs are
cheap because competing branches already live in `TreeState`; only when a reorg drops
below the persisted tip does a disk-reorg `RemoveBlocksAbove` fire (`find_disk_reorg`).

A second, orthogonal reusable idea from the same subsystem: the **state root computed
concurrently with execution** (`payload_processor::spawn` starts the state-root task
before EVM execution, `payload_processor/mod.rs:282`) — cross-referenced in
`reth/state-trie.md`. Payload validation overlaps trie work with execution instead of
serializing execute-then-root.

## Authority note

reth = the Engine-API-native engine-tree + async-persistence reference; geth's
engine-driver (`eth/catalyst` `ConsensusAPI` over the classic `BlockChain`), besu's
`TransitionProtocolSchedule`/`MergeCoordinator`, and nethermind's `MergePlugin`/
`EngineRpcModule` are peers. Each of the others retrofitted the merge onto a pre-merge
import path; reth is the one that made the engine the primary architecture, so it is the
cleanest reference for *how an EL should be organized around the Engine API* and for the
in-memory-tree/async-persistence performance pattern specifically.

## Gotchas / anti-patterns / things they later changed

- **PoW/ETC does not use this driver.** The engine tree, `ForkchoiceState`, and the whole
  `BeaconEngineMessage` path are PoS-only. ETC (PoW/Ethash, block-number fork dispatch)
  has no CL and no `forkchoiceUpdated` — it advances via P2P block import, not an engine
  socket. For fukuii, the *transferable* content here is the in-memory-tree +
  async-persistence performance idea, applicable to *any* block-import path, PoW included —
  not the Engine-API driver itself.
- **Backpressure is not real backpressure.** The in-code comment (`mod.rs:531+`) warns that
  under sustained load the incoming channel still grows unbounded because the Engine API has
  no flow control and CLs retry. Do not read `should_backpressure` as a memory-safety
  guarantee — it is a bias to keep the *cheap* queue growing instead of the *expensive* one.
- **Persistence threshold vs memory buffer are distinct knobs.** `persistence_threshold`
  (default 2) decides *when* to start flushing; `memory_block_buffer_target` (default 0)
  decides *how far behind* the head to stop flushing (blocks intentionally held in memory,
  e.g. for L2 reorg windows). They are easy to conflate; `get_canonical_blocks_to_persist`
  (`mod.rs:2080`) uses the second, `should_persist` (`:2063`) uses the first.
- **Backfill and live sync are mutually exclusive by design.** The `ChainOrchestrator`
  invariant is that only one holds exclusive DB write access; a backfill action is refused
  while a persistence save is in flight (`emit_event`, `mod.rs:2042`). Any concurrent-write
  assumption breaks this.
- **`RethNewPayload` vs `NewPayload`.** reth added a second newPayload variant
  (`message.rs:253`) exposing explicit `wait_for_persistence`/`wait_for_caches` flags for
  its own `reth_newPayload` RPC, to get unbiased timing measurements — a sign the
  memory/disk decoupling makes naive latency numbers misleading, and they built tooling to
  measure around it.
- **Ordering assumption in `SaveBlocks`.** Blocks handed to persistence must be in
  increasing block-number order (`persistence.rs:235`); the tree guarantees this via the
  parent-walk in `get_canonical_blocks_to_persist` + a final `reverse()`.
