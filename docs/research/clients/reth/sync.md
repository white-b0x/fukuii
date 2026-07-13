# reth — sync

_Commit/branch documented: `3d76b93c243f8896f13a39ee865f87241fcd649b` (branch `main`/`upstream`,
`2026-07-01`). Vendored read-only at `.claude/repo-references/clients/reth`. Documented 2026-07-13.
Read-only research; no fukuii source touched._

## Architecture summary

reth's sync is a **staged Pipeline** — the same structural family as erigon (an ordered list of
`Stage`s, each run to completion over the whole block range before the next begins), but expressed in
**Rust-typed, generic-parameterized** stages and, critically, **driven entirely by an external
consensus layer through the Engine API**. There are two mutually-exclusive sync modes
(`crates/engine/tree/src/backfill.rs:1-8`): **backfill sync** (run the staged `Pipeline` to close a
large gap in ranges) and **live sync** (keep up with the tip by downloading individual blocks the CL
announces). The engine `tree` picks between them purely on **distance**: if the forkchoice head is
more than `MIN_BLOCKS_FOR_PIPELINE_RUN = EPOCH_SLOTS` (32) blocks ahead of the local tip
(`crates/engine/tree/src/tree/mod.rs:82-92, 2527-2529`), it triggers a backfill pipeline run to the
finalized block; otherwise it downloads the missing blocks one/range at a time and inserts them live.

The `Pipeline` (`crates/stages/api/src/pipeline/mod.rs:69-95`) holds `stages: Vec<BoxedStage<…>>` and
a `run_loop` (`:223-259`) that walks the stages in order 0→N, executing each to completion before the
next. Each stage is a `Stage<Provider>` trait object (`crates/stages/api/src/stage.rs:241-308`) with
`execute` (roll forward) + `unwind` (roll back) + an optional tower-`Service`-inspired
`poll_execute_ready` (async pre-fetch). Every stage persists a single **`StageCheckpoint`** (a block
number plus optional typed per-stage detail) in a DB table; a restart reads each stage's checkpoint and
resumes exactly where it left off — the same progress-per-stage resumability erigon has, but with a
richer typed checkpoint. Reorgs/bad blocks are handled by **unwinding stages in reverse execution
order** (`pipeline/mod.rs:319-320`, `self.stages.iter_mut().rev()`) — a plain reverse, **not** erigon's
hand-tuned unwind order.

The **default stage list** (`crates/stages/stages/src/sets.rs:60-83`) is: **Era** (optional ERA1/ERA
file import) → **Headers** → **Bodies** → **SenderRecovery** → **Execution** → **PruneSenderRecovery** →
**MerkleUnwind** → **AccountHashing** → **StorageHashing** → **MerkleExecute** → **TransactionLookup** →
**IndexStorageHistory** → **IndexAccountHistory** → **Prune** → **Finish**. State is built by
**executing every block** in the Execution stage (staged full sync), then hashing the flat state and
building the trie (Merkle stages) — **reth does not consume snap-range state download to bootstrap**
(see the snap note below). Historical data is written **directly to static files** as the Headers/
Bodies stages run (ties to `storage-persistence.md`); the Era stage is an alternative bulk-import path
from ERA files. The pipeline is built (`crates/node/builder/src/setup.rs:52-136`) by wiring a
`ReverseHeadersDownloaderBuilder` + `BodiesDownloaderBuilder` (fed by the network fetch client) into
the Headers/Bodies stages, and a `watch::channel` **tip sender** the pipeline uses to tell the Headers
stage what to sync toward.

## Key types / interfaces / files

### The `Stage` trait — execute + unwind + a tower-style readiness hook
- `crates/stages/api/src/stage.rs:241-308` — **`trait Stage<Provider>: Send`**: `id() -> StageId`;
  `execute(&provider, ExecInput) -> Result<ExecOutput>` (roll forward, writes to DB); `unwind(&provider,
  UnwindInput) -> Result<UnwindOutput>` (roll back); `post_execute_commit`/`post_unwind_commit` hooks
  (run *after* the provider commits); and the defaulted **`poll_execute_ready(cx, ExecInput) ->
  Poll<Result<()>>`** (`:271-277`) — "heavily inspired by tower's `Service` trait": any async work
  (moving downloaded items from a downloader into an internal buffer) happens here, returning
  `Poll::Pending` until ready, so `execute` itself stays synchronous. The doc comment (`:262-268`)
  warns the readiness call may reserve buffers that must be released if the stage is dropped before
  `execute`.
- `stage.rs:13-19` — **`ExecInput { target: Option<BlockNumber>, checkpoint: Option<StageCheckpoint> }`**:
  a stage is told the target block and where it last stopped. `stage.rs:201-220` — **`ExecOutput {
  checkpoint, done }`**: `done: false` means "I processed a bounded batch, call me again" (the
  backpressure valve, below); `done: true` means the stage reached the target.
- `stage.rs:167-199` — **`UnwindInput { checkpoint, unwind_to, bad_block }`** / `UnwindOutput
  { checkpoint }`. Both `ExecInput` and `UnwindInput` carry helpers (`next_block_range_with_threshold`,
  `unwind_block_range_with_threshold`) that chunk a large forward/unwind range into bounded sub-ranges.
- `stage.rs:75-84` — **`next_block_range_with_threshold`**: `is_final_range = (end == target)` — the
  mechanism a stage uses to decide whether this batch finishes the job (`done`) or it must be re-entered.

### Stage identity + the checkpoint (the resumability mechanism)
- `crates/stages/types/src/id.rs:10-36` — **`enum StageId`**: `Era, Headers, Bodies, SenderRecovery,
  Execution, PruneSenderRecovery, MerkleUnwind, AccountHashing, StorageHashing, MerkleExecute,
  TransactionLookup, IndexStorageHistory, IndexAccountHistory, Prune, Finish` (+ two `#[deprecated]`
  variants kept for DB checkpoint compatibility, + `Other(&'static str)` for custom stages). `ALL`
  (`:46-62`) and `STATE_REQUIRED` (`:65-75`, the 9 stages needing state) are the load-bearing subsets.
- `id.rs:103-106` — `is_downloading_stage()` = `Era | Headers | Bodies` (the network-consuming stages).
- The pipeline persists one `StageCheckpoint` per `StageId` via `get_stage_checkpoint` /
  `save_stage_checkpoint` (`pipeline/mod.rs:443, 500, 334, 372`) — **that per-stage checkpoint is the
  entire resumability state**, exactly like erigon's `SyncStageProgress`, but the checkpoint can carry
  typed detail (`HeadersCheckpoint`, `EntitiesCheckpoint`, `crates/stages/stages/src/stages/headers.rs:302-314`).

### The Pipeline driver — forward loop + reverse-order unwind
- `crates/stages/api/src/pipeline/mod.rs:69-95` — **`Pipeline<N>`**: `stages`, `max_block`,
  `static_file_producer`, a `tip_tx: Option<watch::Sender<B256>>` (notifies the Headers stage of a new
  sync target), and detached-head-unwind bookkeeping.
- `pipeline/mod.rs:223-259` — **`run_loop`**: `move_to_static_files()` first, then `for stage_index in
  0..stages.len()` run `execute_stage_to_completion`; on `ControlFlow::Unwind` it calls `unwind` and
  returns. One `run_loop` call = one forward pass over all stages; `run` (`:182-210`) loops `run_loop`
  until a `max_block` is reached (or forever in continuous mode).
- `pipeline/mod.rs:431-547` — **`execute_stage_to_completion`**: the per-stage inner loop.
  `await execute_ready(input)` (the `poll_execute_ready` future), then `execute`; on `Ok(ExecOutput{done})`
  it saves the checkpoint, `provider_rw.commit()`s, runs `post_execute_commit`, and — **if `!done`,
  loops to run the stage again** (this is where bounded batches are re-entered). Returns
  `ControlFlow::Continue`/`NoProgress`/`Unwind`.
- `pipeline/mod.rs:303-429` — **`unwind`**: iterates `self.stages.iter_mut().rev()` (**reverse
  execution order — a plain reverse, no hand-tuned order**), and for each stage rolls its checkpoint
  back to `to` in threshold-bounded steps (`while checkpoint.block_number > to`), committing per step;
  skips stages already at/below the target (`:335-345`).
- `pipeline/mod.rs:549-660` — **`on_stage_error`**: the error→control-flow policy. `DetachedHead` →
  unwind by `BEACON_CONSENSUS_REORG_UNWIND_DEPTH * attempts` (`:571-580`); `Block{Validation|Execution}`
  → unwind to the previous checkpoint marking the bad block (`:581-633`); `MissingStaticFileData` →
  unwind one below the block (`:634-646`); fatal → bail; anything else → discard the tx and retry
  (`:650-658`). Reorg/bad-block handling lives here, once, not scattered across stages.

### Stage sets — the pipeline is composed, not hardcoded
- `crates/stages/stages/src/sets.rs:84-181` — **`DefaultStages`** = `OnlineStages` + `OfflineStages` +
  `FinishStage`. It is a `StageSet` whose `builder()` produces the ordered `StageSetBuilder`.
- `sets.rs:183-289` — **`OnlineStages`** (`Era`, `Headers`, `Bodies`) — the stages that need network
  access; they take the header/body downloaders and the `tip` watch receiver.
- `sets.rs:291-358` — **`OfflineStages`** (Execution → hashing → history indexing → prune) — runnable
  without network given the block data is present. `ExecutionStages` (`:360-405`), `HashingStages`
  (`:407-443`, note it prepends `MerkleStage::default_unwind()` then Account/Storage hashing then
  `MerkleStage::new_execution`), `HistoryIndexingStages` (`:445-479`). Each set is a reusable,
  independently-testable sub-pipeline — the composition seam.

### Headers stage — reverse download from the CL-dictated tip, via ETL to static files
- `crates/stages/stages/src/stages/headers.rs:44-62` — **`HeaderStage`**: holds the downloader, a
  `tip: watch::Receiver<B256>` (the sync target, **set by the pipeline from the CL head**), a
  `sync_gap`, and two ETL `Collector`s (hash→number, number→RLP header).
- `headers.rs:199-274` — **`poll_execute_ready`**: computes the `HeaderSyncGap { local_head, target =
  SyncTarget::Tip(*self.tip.borrow()) }`; if closed, done; else drives the downloader
  (`poll_next_unpin`), inserting headers into the ETL collectors **in reverse** until it reaches
  `local_head_number + 1` (the gap is filled). A `DetachedHead` downloader error becomes
  `StageError::DetachedHead` (→ pipeline unwind).
- `headers.rs:96-183` — **`write_headers`**: although downloaded in reverse, the ETL collector iterates
  **ascending**, writing straight to the **Headers static file** via `latest_writer(StaticFileSegment::
  Headers)` plus the `HeaderNumbers` DB table. This is where "sync writes directly to static files"
  concretely happens (ties to `storage-persistence.md`'s append-directly-to-immutable-tier model).
- `crates/net/downloaders/src/headers/reverse_headers.rs` — the `ReverseHeadersDownloader` the stage
  drives; `crates/net/downloaders/src/bodies/bodies.rs` — the body downloader. Both are built from the
  network **fetch client** in `setup.rs:52-59`.

### Execution stage — build state by executing every block, in bounded batches
- `crates/stages/stages/src/stages/execution/mod.rs:80-117` — **`ExecutionStage`** holds
  `thresholds: ExecutionStageThresholds` (`max_blocks`, `max_cumulative_gas`, elapsed).
- `execution/mod.rs:398-406` — inside the execute loop, `self.thresholds.is_end_of_batch(blocks,
  size_hint, cumulative_gas, elapsed)` → `break`, returning `ExecOutput{done:false}` so the pipeline
  re-enters the stage — **reth's equivalent of erigon's `ErrLoopExhausted → hasMore` bounded-batch
  backpressure**, bounding the write-tx/memory size per commit. State is produced here by real
  execution, not by snap range download.

### Engine-API-driven mode selection — backfill (pipeline) vs live sync
- `crates/engine/tree/src/backfill.rs:18-56` — **`BackfillSyncState { Idle, Pending, Active }`** +
  `trait BackfillSync { on_action(BackfillAction), poll(cx) -> Poll<BackfillEvent> }`. The staged
  pipeline is the backfill implementation.
- `backfill.rs:79-203` — **`PipelineSync<N>`**: wraps the `Pipeline` in an `Idle(Box<Pipeline>)` /
  `Running(oneshot::Receiver<…>)` state machine. `BackfillAction::Start(PipelineTarget)` sets a pending
  target (`set_pipeline_sync_target`, refusing the zero hash); `poll` spawns `pipeline.run_as_fut(target)`
  on a blocking task and reports `Started`/`Finished`. The `Idle`/`Running` split matters because a
  running pipeline **holds the DB write lock** (`:210-213`), so the tree must not forward writes while
  it runs.
- `crates/stages/api/src/pipeline/mod.rs:150-178` — **`run_as_fut(Option<PipelineTarget>)`**:
  `PipelineTarget::Sync(tip)` calls `set_tip(tip)` (pushes the hash down the `tip_tx` watch channel to
  the Headers stage) then `run_loop`; `PipelineTarget::Unwind(target)` unwinds instead. This is the one
  seam by which "the CL's chosen head" enters the staged pipeline.
- `crates/engine/tree/src/tree/mod.rs:82-92, 2527-2529` — **`MIN_BLOCKS_FOR_PIPELINE_RUN = EPOCH_SLOTS`**
  + `exceeds_backfill_run_threshold(local_tip, block) = block > local_tip && block - local_tip >
  MIN_BLOCKS_FOR_PIPELINE_RUN`. The **distance test** that chooses backfill over live sync.
- `tree/mod.rs:2542-2619` — **`backfill_sync_target`**: the target is the **finalized** block hash
  (`backfill_target_hash`, `:2549-2555`; head hash on OP Stack), unless already canonicalized. Contains
  the **optimistic-sync fallback** (`:2600-2615`): when the CL hasn't finalized (head hash only), sync
  toward the head hash — the sync-side counterpart of "the head always comes from the CL."
- `crates/engine/tree/src/download.rs:59-207` — **`BasicBlockDownloader`** (the **live-sync** path):
  wraps a `FullBlockClient`; `DownloadAction::Download(BlockSet|BlockRange)` fetches individual blocks/
  small ranges over the eth wire and yields `DownloadOutcome::Blocks` for the tree to insert and
  execute live — the small-gap alternative to the pipeline.

### ERA import — bulk historical import as an alternative to P2P download
- `crates/stages/stages/src/stages/era.rs:1-11` — **`EraStage`**: imports headers+bodies from ERA1
  (pre-merge execution blocks) / ERE / ERA (CL blocks embedding execution payloads) files, reader chosen
  per file extension. Optional (does nothing unless an `EraImportSource` is configured,
  `sets.rs:275-278`) — reth's answer to "don't re-download all of history from peers," analogous in
  spirit to erigon's OtterSync frozen segments but using the standard **ERA file format** rather than a
  bespoke segment/torrent distribution.

## Design decisions & rationale

- **Staged pipeline, Rust-typed, serial-to-completion.** Like erigon: one concern per stage, each a
  full pass over the range, so disk access is long sequential sweeps (Senders recovers all signatures,
  Execution executes all blocks) rather than random per-block interleaving. reth expresses each stage as
  a `Stage<Provider>` trait object generic over the provider, so the pipeline is a `Vec<Box<dyn Stage>>`
  composed from reusable `StageSet`s — a **type-checked, monomorphized** version of erigon's
  string-keyed stage structs.
- **Progress = one typed `StageCheckpoint` per stage.** Resumability is a per-stage block number in the
  DB (optionally with typed detail), read on restart — no in-memory reconstruction, the cleanest
  resumability model along with erigon's. A crash mid-Execution resumes Execution from its checkpoint;
  earlier stages are untouched.
- **Unwind is a plain reverse pass — deliberately simpler than erigon.** reth unwinds
  `stages.iter_mut().rev()` with no hand-tuned order (`pipeline/mod.rs:319-320`). It can afford this
  because its stage dependencies are simpler (no separate txpool-unwind-after-execution constraint like
  erigon's `DefaultUnwindOrder`); the error→unwind *policy* (which block to unwind to, how deep) is
  centralized in `on_stage_error` instead.
- **`poll_execute_ready` separates async acquisition from sync execution.** Borrowing tower's `Service`
  readiness, a stage does its network/IO wait in `poll_execute_ready` (filling an internal buffer / ETL
  collector) and its DB writes in the synchronous `execute`. This keeps the commit path off the async
  runtime and makes "download then persist" a two-phase stage rather than an interleaved loop.
- **Bounded batches via `done:false` + thresholds.** A stage that would process an unbounded range
  breaks at an `ExecutionStageThresholds` boundary and returns `done:false`; `execute_stage_to_completion`
  commits and re-enters it (`pipeline/mod.rs:529-535`). Same backpressure idea as erigon's
  `ErrLoopExhausted`, expressed as a boolean on the output rather than a sentinel error.
- **Engine-API/CL-driven, two modes chosen by distance.** reth is post-merge-native: the CL drives the
  head via `engine_forkchoiceUpdated`/`engine_newPayload`, and the engine `tree` chooses **backfill
  pipeline** (gap > 32 blocks, target = finalized) vs **live block download** (small gap) purely on
  distance (`tree/mod.rs:2527-2529, 2563-2619`). The head is never discovered from peers.
- **State built by execution, history written straight to static files, ERA as a bulk shortcut.** reth
  bootstraps state by executing every block (staged full sync) rather than snap-downloading it; headers/
  bodies land directly in static files as the stages run; and pre-merge history can be bulk-imported
  from ERA files instead of P2P. This is coherent with reth's storage design (`storage-persistence.md`):
  static files are the immutable-history tier, written on the sync path itself.

## Notable patterns (the reusable idea)

1. **Staged pipeline as typed trait objects + composable `StageSet`s.** The ordered
   `Vec<Box<dyn Stage>>` built from reusable sets (`DefaultStages`/`OnlineStages`/`OfflineStages`/
   `ExecutionStages`/`HashingStages`) is the Rust-typed expression of staged sync — stages are
   independently testable and re-orderable, the pipeline is data.
2. **`Stage` = `execute` + `unwind` + a tower-`Service` `poll_execute_ready`.** The readiness hook
   cleanly splits async acquisition (fill a buffer) from synchronous, committed DB writes — a portable
   two-phase stage shape.
3. **Typed `StageCheckpoint`-per-stage resumability** (block number + optional typed detail), the DB
   table *is* the sync state; restart resumes with zero reconstruction.
4. **Bounded-batch backpressure via `ExecOutput{done:false}` + thresholds** (`max_blocks`/
   `max_cumulative_gas`/elapsed) — cap the commit/tx size and be re-entered, without leaving the stage
   model.
5. **Distance-thresholded mode selection (`MIN_BLOCKS_FOR_PIPELINE_RUN`)**: a single numeric gap test
   chooses the heavy staged backfill vs light live block download — a clean, auditable seam between the
   two sync modes.
6. **CL head → staged pipeline via a `watch` tip channel + `PipelineTarget::Sync/Unwind`** — one narrow
   seam by which the external consensus layer's chosen head (or an unwind request) enters the pipeline.
7. **ERA-file bulk history import as a stage** — pre-built history files as an alternative to
   re-downloading from peers (the standard ERA format, vs erigon's bespoke OtterSync segments).

## Authority note

**reth is a staged-pipeline + Engine-API-driven-sync authority (Rust-typed), alongside erigon.** The
ordered-`Stage`-trait decomposition, the typed `StageCheckpoint`-per-stage resumability, the
`poll_execute_ready` two-phase stage, the `done:false` bounded-batch valve, and the distance-thresholded
backfill-vs-live mode selection are the reth reference. Where erigon leads on *flat-state-coupled* staged
sync (OtterSync segments, no trie-node store), reth leads on *typed, execution-based* staged sync driven
by an external CL.

reth is **explicitly NOT a PoW-sync-from-genesis authority.** There is **no master-peer / TD-head
selection orchestrator** anywhere in `crates/stages/` or `crates/engine/tree/` — the head always comes
from the CL forkchoice, and the Headers stage's sync target is a `watch::Receiver<B256>` the pipeline
fills from the CL head (`headers.rs:53, 213`; `pipeline/mod.rs:110-116`). **reth cannot sync a PoW chain
from genesis without a consensus-layer client.** This is the sync-side counterpart of reth having no PoW
consensus / no seal engine (`consensus-engines.md`): a reth-shaped EL depends on a CL to tell it what the
head is. For ETC's need to sync PoW-without-a-CL, **core-geth remains the authority** (peer-driven head
selection, ETChash, ECIP-1017 emission continuity); reth, like current go-ethereum HEAD, deleted that
path.

Authority boundaries:
- **go-ethereum remains the authority for snap-protocol wire semantics** (`GetAccountRange`/
  `GetStorageRanges`/`GetByteCodes`/`GetTrieNodes`, range-proof placement). reth defines the snap
  message types and a `SnapClient` (`crates/net/p2p/src/snap/client.rs`, `crates/net/eth-wire-types/src/
  snap.rs`) for protocol completeness, but its **own sync does not consume snap ranges to bootstrap
  state** — a tree-wide grep finds **no `SnapClient`/`GetAccountRange` consumer in `crates/stages/`,
  `crates/engine/`, or `crates/node/`**; reth rebuilds state by executing blocks. A fukuii node that
  must be a good snap citizen matches geth on the wire, not reth.
- **erigon is the parallel authority for flat-state-coupled staged sync** (OtterSync frozen segments,
  hand-tuned unwind order, no trie-node store). reth's staged sync is execution-based with a
  persisted trie and a plain-reverse unwind — the same *shape*, a different *state model*.
- **core-geth remains the authority for PoW/ETC sync semantics** — reth's pipeline is chain-agnostic in
  shape but is not, and cannot be, a PoW-from-genesis reference.

## The comparative point for fukuii (staged pipeline — portable ideas, inapplicable path)

fukuii's sync is **actor-based fast/regular/SNAP** (Pekko actors, per `blockchain/sync/AGENTS.md`) — a
concurrent, message-passing decomposition, structurally closer to geth/besu/nethermind's fetcher/feed
models than to reth's serial staged pipeline. reth is instructive as the **typed staged-pipeline +
Engine-API-driven** reference, but two of its defining properties are *inapplicable* to fukuii and must
be understood as divergences, not targets:

- **No PoW-from-genesis path.** reth's entire sync assumes a CL supplies the head; there is no
  peer-driven head-selection/header-sync-from-genesis orchestrator. fukuii's ETC (PoW) network **must
  retain** exactly the peer-driven head selection reth lacks — this is the sync-side twin of reth having
  no PoW consensus. Do not read reth as evidence an EL can drop CL-less sync; core-geth is that authority.
- **State by execution, not snap-download.** reth bootstraps state by re-executing every block (staged
  full sync) and does not consume snap ranges. fukuii's SNAP path (range download + heal, the geth
  model) is a capability reth simply does not have on the consuming side — a divergence to keep, not
  align away.

**Portable ideas, decoupled from the above** (adoptable independent of the staged/actor split):
- **Typed per-stage/per-phase resumable checkpoints** — fukuii's actor sync could persist a small
  durable "phase reached" checkpoint (like `StageCheckpoint`) for cheap crash resume, instead of
  reconstructing in-memory sync state.
- **The `poll_execute_ready` two-phase shape** — separate async acquisition (fill a buffer) from the
  committed write, keeping the DB-commit path off the async path.
- **Bounded-batch backpressure via an explicit "not done, re-enter me" signal** (reth's `done:false` +
  thresholds) — cap DB-tx/memory size per commit without abandoning the sync unit.
- **A single distance threshold to choose heavy backfill vs light live sync** (`MIN_BLOCKS_FOR_PIPELINE_
  RUN`) — a clean, auditable mode-selection seam, if fukuii ever separates a "catch-up in bulk" mode from
  a "keep up with tip" mode.
- **A narrow CL-head → sync seam** (a `watch` tip channel + a `Sync/Unwind` target enum) rather than
  threading the head through many handlers — relevant to fukuii's Engine-API/PoS path specifically.

Record for Phase 3–4 as: reth = the "typed, execution-based, CL-driven staged pipeline" reference; the
takeaways are *typed resumable checkpoints*, *two-phase stages*, and *distance-thresholded mode
selection* — **not** the pipeline wholesale, and explicitly not its CL-only / snap-less bootstrap, which
fukuii's ETC PoW support cannot adopt.

## Gotchas / anti-patterns / things they later changed

- **Reth is a staged FULL-sync client that rebuilds state by execution — it does not snap-sync itself.**
  A reader expecting a snap/state-heal stage (as in geth/besu/nethermind) will not find one; the snap
  message types + `SnapClient` exist for protocol completeness/serving, with **no sync consumer** in
  `crates/stages`/`crates/engine`/`crates/node`. State comes from the Execution stage re-running blocks.
- **The head *always* comes from the CL — there is no PoW head-selection code to find.** The Headers
  stage syncs toward a `watch::Receiver<B256>` filled by the pipeline from the CL forkchoice
  (`headers.rs:213`, `pipeline/mod.rs:112-116`). Looking for a master-peer/TD-heaviest-chain selector
  in reth finds nothing — it was never built (reth is post-merge-native).
- **Unwind order is a plain reverse, not a hand-tuned order.** `pipeline/mod.rs:319-320` iterates
  `stages.iter_mut().rev()`. Anyone porting reth's unwind expecting an erigon-style
  `DefaultUnwindOrder` will not find one — the ordering constraints reth needs are simpler, and the
  *policy* (unwind depth/target) lives in `on_stage_error`, not in an ordered list.
- **`StageId` carries `#[deprecated]` variants (`StaticFile`, `MerkleChangeSets`) kept only for DB
  checkpoint compatibility** (`id.rs:11-18`). They are not real stages — a reader enumerating stages must
  use `StageId::ALL` (15 entries), not the full enum.
- **`move_to_static_files` is a legacy storage.v1 backfill step and a no-op under storage.v2**
  (`pipeline/mod.rs:277-298`): v2 writes directly to static files + RocksDB, so there is no MDBX→
  static-file migration pass. Reading it as "the pipeline always migrates to static files" is wrong for
  a v2 node (see `storage-persistence.md` on the v1/v2 split).
- **A running pipeline holds the DB write lock — the tree must not forward writes during backfill.**
  The `PipelineState::Idle/Running` distinction exists precisely for this (`backfill.rs:205-227`):
  forwarding a write-producing message to the tree while the pipeline runs would deadlock. Any port of
  the two-mode design must preserve this mutual exclusion between backfill and live-sync writes.
- **ERA import is optional and silent when unconfigured.** The `EraStage` "does nothing unless an
  `EraImportSource` is specified" (`sets.rs:204-205, 275-278`) — a node without ERA files still syncs,
  just via Headers/Bodies P2P download. Don't assume ERA files are part of every reth sync.
- **Headers download in reverse but persist ascending.** The stage fills ETL collectors from tip
  downward, then the collector iterates ascending into static files (`headers.rs:118-122, 251-256`) — a
  reversal that bites anyone reasoning about the on-disk write order from the download order.
