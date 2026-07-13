# erigon — sync

_Commit/branch documented: `f1d79d699ed4b809abc0d177dcb539d8605edc41` (`HEAD -> main`,
`origin/upstream`, `origin/main`, `upstream`). Vendored at
`.claude/repo-references/clients/erigon`. Documented 2026-07-13._

## Architecture summary

erigon's sync is **staged sync**: not a set of parallel per-block downloaders behind a moving
pivot (geth/besu), but an **ordered list of stages** where each stage runs to completion over the
*entire* block range before the next stage begins. One `Sync` object
(`execution/stagedsync/sync.go:37-53`) holds a `[]*Stage` plus an `unwindOrder` and `pruningOrder`,
and its `Run` loop (`sync.go:376-452`) walks `stages[currentStage]` from 0 to the end, calling each
stage's `Forward` function. A stage is a plain struct of three functions —
`Forward`/`Unwind`/`Prune` — keyed by a string `ID` (`stage.go:47-62`). The default main-chain
pipeline (`default_stages.go:30-169`) is: **Snapshots (OtterSync) → Headers → BlockHashes → Bodies
→ Senders → Execution → TxLookup → Finish** (with a commented-out `CustomTrace` and a
Polygon-only `WitnessProcessing`).

Every stage persists a single integer — the block number it has processed up to — in one KV table
(`kv.SyncStageProgress`, `stages/stages.go:60-101`). That one number per stage **is** the sync
checkpoint: a restart reads each stage's progress and resumes exactly where it left off, with no
in-memory reconstruction. Reorgs are handled by the **inverse operation**: `UnwindTo(block, reason)`
(`sync.go:135-164`) sets an unwind point, and before the next forward pass the `Run` loop walks the
`unwindOrder` (stages in *reverse*, with hand-tuned exceptions) calling each stage's `Unwind`
(`sync.go:392-416`), rolling every stage's progress back to the unwind block. Pruning is a third,
independently-ordered pass (`RunPrune`, `sync.go:454-470`).

Two facts make this HEAD's staged sync **different from the classic Erigon-2 description**, and both
matter for the comparison:

1. In the *main* `DefaultStages` pipeline the **Headers and Bodies stages are hollowed out** — their
   `Forward` is `return nil` and only their `Unwind` is real (`default_stages.go:58-96`,
   `stage_headers.go:42`, `stage_bodies.go:34`). Erigon-3's live header/body acquisition is no longer
   a P2P download *stage*; live blocks arrive via the Engine API / execution module and historical
   bulk data arrives as **frozen snapshot segments** (below). The stages survive as **unwind
   anchors**, not downloaders.
2. Bulk historical sync is the **Snapshots stage a.k.a. "OtterSync"** (`stage_snapshots.go:150-164`),
   which downloads pre-built immutable `.seg` segment files (headers, bodies, txs, and the flat-state
   domains) over a torrent-like downloader, then indexes them — replacing "download every header and
   body from peers" with "fetch verified frozen files." This is the flat-state-coupled bootstrap.

The staged model is tightly coupled to erigon's flat-state DB (MDBX temporal domains, documented in
`storage-persistence.md`): the Execution stage writes account/storage as flat key-values via
`SharedDomains` (`execctx.SharedDomains`, threaded through every stage `Forward`), not by walking a
trie top-down — which is exactly what makes a full-pass-per-stage batch tractable (sequential MDBX
writes) and what makes commitment/trie a *separate* concern computed from the flat state rather than
a store to heal.

## Key types / interfaces / files

### The stage abstraction
- `execution/stagedsync/stage.go:47-62` — **`Stage`**: `{Description, Forward ExecFunc, Unwind
  UnwindFunc, Prune PruneFunc, ID stages.SyncStage, Disabled bool}`. The whole pipeline is a slice of
  these.
- `stage.go:33-45` — the three function signatures. `ExecFunc(badBlockUnwind, s *StageState,
  unwinder Unwinder, doms *SharedDomains, rwTx TemporalRwTx, logger)` — note the `Unwinder` handle:
  a stage can *request* an unwind from inside its forward pass (e.g. Execution hits a bad block).
- `stage.go:69-99` — **`StageState`**: carries `BlockNumber` (progress at start of this run) and
  `CurrentSyncCycle {IsInitialCycle, IsFirstCycle}`. `Update(db, newBlockNum)` (`:93-99`) is how a
  stage records forward progress mid-run; it writes straight to `SyncStageProgress`.
- `stage.go:64-67` — **`CurrentSyncCycleInfo`**: `IsInitialCycle` = "not on chain tip, can be several
  cycles"; `IsFirstCycle` = "first cycle". Stages relax validation on the initial (bulk) cycle vs the
  tip cycle.

### The Sync orchestrator + the forward/unwind loop
- `sync.go:37-53` — **`Sync`**: `stages`, `unwindOrder`, `pruningOrder`, `currentStage`,
  `unwindPoint *uint64`, `unwindReason`, `mode stages.Mode`.
- `sync.go:376-452` — **`Run(sd, tx, initialCycle, firstCycle)`**: the pipeline driver. Each call
  runs the *whole* pipeline once (contract enforced by the `defer` resetting `currentStage` to 0,
  `:384-389`). If an `unwindPoint` is set it first drains the unwind order (`:394-416`), then runs
  each enabled stage's `Forward` in order via `runStage` (`:432`). Returns `hasMore` so a caller can
  loop the pipeline until the tip is reached.
- `sync.go:494-515` — **`runStage`**: reads the stage's saved progress into a fresh `StageState`,
  calls `stage.Forward`, and translates a returned `*ErrLoopExhausted` (a stage hit its per-cycle
  iteration cap) into `hasMore=true` rather than an error (`:503-508`) — the "process a bounded batch,
  come back next cycle" backpressure valve.
- `sync.go:529-560` — **`unwindStage`**: builds an `UnwindState{UnwindPoint, CurrentBlockNumber}`,
  short-circuits if the stage is already at/below the unwind point (`:539-541`), else calls
  `stage.Unwind`.
- `sync.go:166-168` — **`IsDone`**: `currentStage >= len(stages) && unwindPoint == nil` — the loop
  terminates only when the last stage is reached *and* no unwind is pending.

### Stage identity + persistence (the checkpoint)
- `stages/stages.go:34-46` — the stage-ID constants: `Snapshots = "OtterSync"`, `Headers`,
  `BlockHashes`, `Bodies`, `Senders`, `Execution`, `CustomTrace`, `WitnessProcessing`, `TxLookup`,
  `Finish`. (Note the DB key for Snapshots is the string `"OtterSync"`.)
- `stages/stages.go:60-101` — **`GetStageProgress`/`SaveStageProgress`**: one big-endian uint64 per
  stage ID in `kv.SyncStageProgress`. This single table is the entire resumability mechanism.
- `stages/stages.go:104-114` — **`GetStagePruneProgress`/`SaveStagePruneProgress`**: prune progress
  stored under a `"prune_"`-prefixed key, so forward and prune positions are tracked separately.
- `stages/sync_mode.go:5-9` — **`Mode`**: `ModeForkValidation` vs `ModeApplyingBlocks` — the pipeline
  is reused both to *apply* the canonical chain and to *validate* a candidate fork in memory.

### Stage ordering — forward, unwind, prune are three distinct orders
- `default_stages.go:334-346` — **`DefaultForwardOrder`**: the natural order (Snapshots → Headers →
  … → Finish), with an explicit "stages below don't use Internet" split at Senders.
- `default_stages.go:355-366` — **`DefaultUnwindOrder`**: reverse-ish but *hand-tuned* — `Finish,
  TxLookup, Execution, Senders, Bodies, BlockHashes, Headers`. The comment (`:348-352`) is the load-
  bearing rationale: "not always just stages going backwards … txn pool can be unwound only after
  execution." Unwind order is a real design surface, not a mechanical reverse.
- `default_stages.go:387-398` — **`DefaultPruneOrder`**.
- `default_stages.go:171-276` — **`PipelineStages`**: the **execution-only tail** used when
  header/body acquisition happens *outside* the pipeline (Polygon, and the CL-driven main chain):
  Snapshots → BlockHashes → Senders → Execution → [WitnessProcessing] → TxLookup → Finish. **No
  Headers, no Bodies stage** — they are supplied by an external downloader.
- `default_stages.go:278-332` — **`StateStages`** + `StateUnwindOrder`: a minimal Headers/Bodies/
  BlockHashes/Senders/Execution set used for "process side forks and memory execution."

### Snapshot (frozen-segment) prepipeline
- `stage_snapshots.go:113-116` — **`SpawnStageSnapshots`** → `DownloadAndIndexSnapshotsIfNeed`.
- `stage_snapshots.go:150-164` — **OtterSync**: the Snapshots-stage forward starts the segment
  downloader ("[OtterSync] Starting Ottersync"), fetching pre-built immutable `.seg` files instead of
  replaying P2P headers/bodies.
- `stage_snapshots.go:299-304` — after download, the stage advances every downstream stage's progress
  to `FrozenBlocks()` so the pipeline skips re-processing what the segments already cover — the
  segments *are* the sync for that range.
- The immutability contract (`CLAUDE.md`): "`snapshots` are immutable" and "`Unwind` beyond data in
  snapshots not allowed" — enforced in `UnwindTo` via `CanUnwindBeforeBlockNum` (`sync.go:137-152`),
  which refuses an unwind that would cross into frozen/committed data and returns "too far unwind."

### Post-merge: CL-driven head → pipeline
- `execution/execmodule/executor.go:40-53` — **`PipelineExecutor`** centralises every pipeline
  invocation: `ProcessFrozenBlocks` (startup bulk), `RunLoop` (FCU catch-up), `ValidateBlock` (fork
  validation). Erigon-3 inverts the geth relationship: the CL sends blocks/forkchoice to the
  execution module, which drives the *execution* pipeline — the pipeline no longer discovers the head.
- `executor.go:138-178` — **`RunLoop`**: `sync.Run → Prune → ShouldBreak → CommitCycle` in a
  `hasMore` loop, rotating the MDBX tx + `SharedDomains` each committed cycle. This is the FCU
  catch-up loop: keep running the whole pipeline until `Run` reports no more work.
- `executor.go:180-271` — **`ProcessFrozenBlocks`**: startup path. `RunSnapshots` first (download
  segment files), then loop the pipeline over the frozen blocks until `Finish` progress ≥
  `FrozenBlocks()` (`ShouldBreak`, `:257-267`). The snapshot download is a discrete first step, then
  staged execution of everything the segments delivered.
- `sync.go:255-263` — **`RunSnapshots`**: runs *only* the Snapshots stage (used by the startup path
  before the general loop).
- `stageloop/stageloop.go:275-318` — **`StateStep`**: single-block/side-fork stepping used by the
  fork validator (in-memory execution of a candidate chain via `StateStages`).

### Polygon / Bor sync — an event-driven Sync *outside* staged sync
- `polygon/sync/sync.go:1-64` — Bor/Polygon does **not** run header/body download as a staged-sync
  stage. It has its **own** `Sync` object driven by Heimdall waypoints —
  `SynchronizeCheckpoints`/`SynchronizeMilestones`/`SynchronizeSpans` (`:60-64`) — feeding a
  `CanonicalChainBuilder`. Milestones/checkpoints/spans arrive as **events** (`EventTypeNewMilestone`,
  `sync.go:903-904`) rather than as a linear stage.
- `polygon/sync/sync.go:251-318` — `applyNewMilestoneOnTip` / span synchronization: the tip is
  advanced by applying Heimdall milestones; spans must be synced first because milestone/checkpoint
  handling depends on them (`:999-1000`). Blocks the builder accepts are then handed to the
  **`PipelineStages` execution tail** (no Headers/Bodies stage) for BlockHashes → Senders → Execution.
- `default_stages.go:230-244` — the **`WitnessProcessing`** stage is the one Polygon-specific member
  of the pipeline (buffered witness data), filtered out of the unwind order on non-Polygon chains at
  `Sync` construction (`sync.go:201-207`).

## Design decisions & rationale

- **Stage-per-concern, full-pass-per-stage.** Instead of geth's concurrent fetchers (headers/bodies/
  receipts/state all in flight behind one moving pivot), erigon runs one concern to completion, then
  the next. Rationale is disk-access locality against MDBX flat state: Senders recovers *all*
  signatures in one sequential sweep, Execution executes *all* blocks writing flat domains in one
  sweep — each pass is a long sequential I/O pattern the DB and OS page cache love, versus random-
  access trie mutation interleaved per block. Staged sync is the sync-side expression of erigon's
  flat-state DB thesis (`storage-persistence.md`).
- **Progress = one integer per stage; that's the whole checkpoint.** Resumability is not a journal or
  a set of in-flight tasks (geth's `syncProgress`, besu's `ChainSyncState`) — it is literally one
  uint64 per stage in `SyncStageProgress` (`stages/stages.go:60-101`). A crash mid-Execution resumes
  Execution from its saved block; every earlier stage is already complete and untouched. This is the
  cleanest resumability model in the reference set, bought by the strict "each stage finishes before
  the next starts" invariant.
- **Unwind as the first-class inverse of forward, with a hand-tuned order.** Every stage ships an
  `Unwind` beside its `Forward`; a reorg calls `UnwindTo` and the pipeline rolls *all* stages back to
  the fork point in `DefaultUnwindOrder` before re-running forward. The order is deliberately *not* a
  pure reverse (`default_stages.go:348-366`) — dependencies like "txlookup/txpool unwind only after
  execution unwinds" are encoded. Reorg handling is symmetric with sync, not a bolt-on.
- **`ErrLoopExhausted` = bounded batches with backpressure.** A stage that would process an unbounded
  range returns `ErrLoopExhausted`; `runStage` converts it to `hasMore=true` (`sync.go:503-508`) so
  the caller commits, rotates the tx/domains, and re-enters — bounding memory and MDBX tx size per
  cycle without abandoning the stage model.
- **Snapshots (OtterSync) replace bulk P2P download.** Historical data is distributed as immutable,
  verified `.seg` segment files fetched via a torrent-style downloader, then indexed — cheaper and
  more parallel than re-downloading every header/body from peers, and the source of the "immutable,
  cannot unwind below" contract. The Headers/Bodies *stages* consequently degraded to unwind-only
  anchors in the main pipeline (`default_stages.go:58-96`).
- **One pipeline, three roles (apply / fork-validate / memory-step).** The same `Stage` set is reused
  via `Mode` (`stages/sync_mode.go`) and the `StateStages`/`PipelineStages` variants for applying the
  canonical chain, validating a candidate fork in memory (`StateStep`), and Polygon's execution tail.
  The stage abstraction is the unit of reuse.
- **Post-merge: EL is a slave to the CL, pipeline drives *execution* only.** The `PipelineExecutor`
  (`execmodule/executor.go`) runs the pipeline in response to Engine-API forkchoice/newPayload; head
  selection lives entirely in the CL. Where geth grows a reverse *skeleton* of headers from the CL
  head, erigon relies on snapshot segments for history and inserts live blocks into an execution-only
  `PipelineStages` — no skeleton/backfill machinery.

## Notable patterns (the reusable idea)

1. **Staged sync itself**: sync decomposed into an ordered list of independent, resumable stages,
   each a full pass over the range, each persisting a single progress integer. The structurally
   different alternative to per-block/pivot download.
2. **`Stage` as three pure functions + an ID** (`Forward`/`Unwind`/`Prune`) with **three separate
   orderings** (forward, unwind, prune) — reorg handling and pruning are first-class inverse passes,
   not special cases inside the forward path.
3. **Progress-integer-per-stage checkpointing** — restartability with zero in-memory reconstruction;
   the DB table is the sync state.
4. **Bounded-batch backpressure via a typed sentinel error** (`ErrLoopExhausted` → `hasMore`) — a
   stage yields control to bound tx/memory size and is re-entered, without leaving the stage model.
5. **Frozen immutable segment prepipeline (OtterSync)** with an enforced "cannot unwind below frozen"
   floor — bulk history as verified files, not a peer download.
6. **One pipeline reused for apply / fork-validate / memory-execute** via a `Mode` enum and
   stage-subset variants (`StateStages`, `PipelineStages`).

## Authority note

**erigon is THE authority for staged sync and flat-state-coupled batch sync.** The ordered-stages
decomposition, the `StageState`/`Unwind` model, progress-per-stage resumability, the hand-tuned
unwind order, and the OtterSync frozen-segment prepipeline all originate/culminate here and have no
equal in the other reference clients (geth, besu, core-geth, nethermind, reth all use a
concurrent-fetcher + moving-pivot + heal shape). Where the question is "**stage-based,
restartable, flat-state batch pipeline**," erigon leads.

Authority boundaries:
- **go-ethereum remains the authority for snap-protocol wire semantics** (`GetAccountRange`/
  `GetStorageRanges`/`GetByteCodes`/`GetTrieNodes`, range-proof placement). erigon does **not** do
  moving-pivot snap state download + trie-node healing at all — it has no trie-node store to heal
  (flat-state domains + separately-computed commitment), so the snap heal model is simply absent.
  A fukuii node that must be a good snap network citizen matches geth, not erigon, on the wire.
- **core-geth remains the authority for PoW/ETC sync semantics** (ETChash, ECIP-1017 emission
  continuity). erigon's staged pipeline is chain-agnostic in *shape* but not a PoW/ETC reference.
- **besu is the authority for the sync-mode-as-config-axis / per-consensus-family `PivotBlockSelector`
  pattern**; erigon expresses "which strategy" instead as *which stage list* (`DefaultStages` vs
  `PipelineStages` vs `StateStages`) plus an external downloader for Polygon.

## The comparative point for fukuii (alternative sync architecture — do NOT adopt wholesale)

fukuii's sync is **actor-based fast/regular/SNAP** (Pekko actors, per `blockchain/sync/AGENTS.md`) —
a concurrent, message-passing decomposition much closer to geth/besu's fetcher model than to
erigon's staged pipeline. erigon's staged sync is the **reference for stage-based resumability +
flat-state-coupled batch processing**, and it is instructive precisely because it is *structurally
incompatible* with fukuii's model in several ways:

- **It presupposes a flat-state DB with separately-computed commitment.** Full-pass-per-stage
  Execution writing flat MDBX domains is what makes staged sync efficient; fukuii stores an MPT and
  computes state roots inline. Adopting staged Execution without the flat-state/commitment split
  would lose the entire benefit (you'd be doing random trie mutation in a batch loop). This couples
  directly to `storage-persistence.md` — staged sync and the flat-state DB are one design, not two.
- **It has no snap heal phase and no moving pivot** — because there is no trie-node store to reconcile.
  fukuii's SNAP path (range download + heal against a moving pivot) is the geth model; erigon deletes
  that problem by construction. This is a divergence to *understand*, not to copy: fukuii needs the
  geth-authoritative SNAP protocol on the wire regardless.
- **It replaces bulk P2P header/body sync with frozen immutable segments (OtterSync).** fukuii has no
  segment-file distribution format; the Headers/Bodies-as-unwind-anchors state (`default_stages.go`)
  is meaningless without it.
- **Portable ideas, decoupled from the above:** (a) **progress-integer-per-stage / per-phase
  resumability** — fukuii's actor sync could persist a small durable "phase reached" checkpoint for
  cheap crash resume; (b) **unwind as a first-class ordered inverse pass** with a hand-tuned order,
  rather than reorg logic scattered across handlers; (c) **bounded-batch backpressure via a sentinel
  ("come back next cycle")** to cap memory/DB-tx size. These are extractable without the flat-state DB.

Record for Phase 3–4 as: erigon = the "what a fully different, resumable, flat-state sync
decomposition looks like" reference; the takeaways are *resumability discipline* and *first-class
unwind ordering*, not the pipeline itself.

## Gotchas / anti-patterns / things they later changed

- **The classic "8-stage P2P download pipeline" is out of date for the *main* chain.** A reader
  expecting `Headers`/`Bodies` stages to *download* from peers will find their `Forward` is
  `return nil` in `DefaultStages` (`default_stages.go:58-96`) — only `Unwind` is implemented
  (`stage_headers.go:42`, `stage_bodies.go:34`). Erigon-3 moved live acquisition to the CL/execution
  module and history to OtterSync segments; the stages persist as **unwind anchors**. The
  agents.md/legacy "Stage Pipeline Order" list still reads as if Headers/Bodies download — treat the
  code, not the doc, as truth.
- **`Snapshots` stage's DB key is the string `"OtterSync"`** (`stages/stages.go:35`), not
  `"Snapshots"` — a mismatch that bites anyone reading the raw `SyncStageProgress` table.
- **"Unwind beyond snapshots is forbidden."** `UnwindTo` silently *clamps* or rejects unwinds that
  cross the committed/frozen boundary (`sync.go:137-152`, `CanUnwindBeforeBlockNum`) and can even
  return `nil` (no-op) when snapshots are ahead of commitment (`:142-144`). An unwind request is not
  guaranteed to reach the block you asked for.
- **Bad-block unwind breaks the loop early on purpose.** In `Run`, a `badBlockUnwind` sets a flag and
  `break`s the pipeline before running forward stages (`sync.go:417-421`) so the Engine API gets its
  reply — otherwise sync "gets stuck in Headers with 'Waiting for Consensus Layer.'" A subtle
  control-flow exception to "Run always runs the full pipeline."
- **`currentStage` is reset by a `defer` on every exit** (`sync.go:384-389`, and again in
  `RunNoInterrupt` `:293-298`) — because an error return from a stage would otherwise leave the next
  `Run` starting mid-pipeline. Anyone refactoring `Run`'s error paths must preserve this reset.
- **Unwind order is filtered per chain at construction.** `New` drops stages absent from the built
  `stageMap` (e.g. `WitnessProcessing` on non-Polygon, `sync.go:201-214`); the `DefaultUnwindOrder`
  constant is a superset, not the effective order for a given chain.
- **Polygon sync is a separate `Sync` engine, not a stage.** `polygon/sync/` runs its own
  event/milestone-driven loop and only hands blocks to the *execution tail* (`PipelineStages`).
  Looking for "Heimdall span sync as a stage" inside `execution/stagedsync/` finds nothing — the span/
  milestone/checkpoint logic lives entirely in `polygon/sync/sync.go` (`:60-64, 903-904, 999-1000`).
- **`STOP_BEFORE_STAGE`/`STOP_AFTER_STAGE` env flags `os.Exit(0)` mid-pipeline** (`sync.go:643-656`) —
  a debugging hook that hard-exits the process; not an anti-pattern per se, but a surprising
  side-channel to know exists when reproducing stuck-sync reports.
</content>
</invoke>
