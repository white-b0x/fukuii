# erigon — block-execution
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

erigon has **no monolithic `BlockChain.insertChain`** (go-ethereum's model). Block
execution is one **stage** in a linear staged-sync pipeline — the **Execution stage**,
6th of 8 (Snapshots → Headers → BlockHashes → Bodies → Senders → **Execution** →
TxLookup → Finish; `execution/stagedsync/agents.md`). By the time Execution runs, headers
are validated, bodies downloaded, and senders recovered, so the stage does *only* state
transition: initialize → apply txs → verify receipts/bloom/gas → finalize (rewards +
system calls) → commit state. Each stage also implements `Unwind` (reorg rollback) and
`Prune` (drop old changesets/history).

The stage entry is `SpawnExecuteBlocksStage` (`stage_execute.go:369`), which delegates to
`ExecV3` (`exec3.go:110`). `ExecV3` picks one of **two execution engines** off a single
`parallel bool` flag (`stage_execute.go:387`, gated on `dbg.Exec3Parallel ||
cfg.experimentalBAL`):

- **serial** (`serialExecutor`, `exec3.go:253`) — one tx at a time, the default/safe path.
- **parallel** (`parallelExecutor`, `exec3.go:219`) — the notable **exec3** engine: a
  **Block-STM-style optimistic-concurrency** executor. Worker pool speculatively executes
  txs out of order against an in-RAM multi-version state (`VersionMap`), a
  conflict-resolution goroutine validates each tx's read-set against committed versions,
  and any tx that read a value later written by a lower-indexed tx is **re-executed**. The
  block's canonical result is always assembled from the *valid* serial ordering, so the
  state root is deterministic despite parallel execution.

Both engines share the block-execution **core** in `execution/protocol/block_exec.go`
(`ExecuteBlockEphemerally`, the go-ethereum `StateProcessor.Process` equivalent) and read
state through erigon's **flat state** — key/value domain lookups (`ReaderV3.ReadAccountData`
→ `getter.GetLatest(kv.AccountsDomain, ...)`, `rw_v3.go:1469`) rather than MPT node
traversal. Commitment (the state root) is computed separately and can be *deferred* to
block boundaries (`doms.SetDeferCommitmentUpdates`, `exec3.go:200-208`).

## Key types / interfaces / files

### Execution stage (pipeline wrapper)
- `execution/stagedsync/stage_execute.go:63` — `ExecuteBlockCfg`, the stage config: temporal RW db, chain config, `engine rules.Engine`, vm config, batch size, prune mode, notifications.
- `execution/stagedsync/stage_execute.go:369` — `SpawnExecuteBlocksStage`, forward entry; computes `to` from the Senders stage progress and calls `ExecV3`.
- `execution/stagedsync/stage_execute.go:393` — `UnwindExecutionStage`, reorg rollback; replays domain diffsets backward (`unwindExec3`, `:180`) and rewinds the in-RAM overlay vs. disk state distinctly.
- `execution/stagedsync/stage_execute.go:479` — `PruneExecutionStage`, time-budgeted pruning of `ChangeSets3` / `BlockAccessList` beyond `MaxReorgDepth`.

### exec3 dispatch + engines
- `execution/stagedsync/exec3.go:110` — `ExecV3`, sets up `StateV3Buffered`, seeks commitment, restores tx-num, then branches serial/parallel.
- `execution/stagedsync/exec3.go:219` / `:253` — `parallelExecutor` / `serialExecutor` construction (both embed `txExecutor`).
- `execution/stagedsync/exec3_parallel.go:43-82` — the **layered-abstraction doc comment**: IntraBlockState → versionedWriteCollector → TxTask → ParallelExecutionState (in-RAM, all workers see it) → RoTx (committed). Reads always run against a *valid* version of state.
- `execution/stagedsync/exec3_parallel.go:84` — `parallelExecutor` struct (worker pool `in *QueueWithRetry`, `rws *ResultsQueue`, `blockExecutors map`).
- `execution/stagedsync/exec3_parallel.go:2043` — `blockExecutor`, owns one block's `versionMap *state.VersionMap` (`:2078`, `:2177 NewVersionMap`); drives the re-execute-on-conflict loop (`ValidateVersion`, `:2268`; dependency-abort re-execution, `:2291`).
- `execution/state/versionedio.go` — versioned read/write records (`VersionedRead`/`VersionedWrite`) that back conflict detection.
- `execution/state/rw_v3.go:44` — `StateV3`; `:999` `ReaderV3`; `:1469` `ReadAccountData` — the **flat-state read** via `GetLatest` on `kv.AccountsDomain`.

### Block-execution core (engine-agnostic)
- `execution/protocol/block_exec.go:81` — `ExecuteBlockEphemerally`: `InitializeBlockExecution` → per-tx `ApplyTransaction` loop → receipt/bloom/gas-used checks (`:153-179`) → `FinalizeBlockExecution`.
- `execution/protocol/block_exec.go:114` / `:362` — `InitializeBlockExecution` → `engine.Initialize` (pre-block system calls, e.g. EIP-2935/4788 beacon-root writes).
- `execution/protocol/block_exec.go:330` — `FinalizeBlockExecution` → `engine.Finalize` (or `FinalizeAndAssemble` when mining) applies **rewards + post-block system calls** (withdrawals, EIP-7002/7251 requests), then `ibs.CommitBlock`.
- `execution/protocol/block_exec.go:230` — `SysCallContract`: system-call harness; **author = `header.Coinbase` for Bor, `params.SystemAddress` otherwise** — a real multi-family divergence.
- `execution/protocol/state_processor.go:95` — `ApplyTransaction` / `FinalizeTx` (single-tx application).
- `execution/protocol/rules/ethash/rules.go:420` — `Ethash.Finalize` → `accumulateRewards` (block + uncle rewards; Frontier 5 ETH → Byzantium 3 → Constantinople 2, `:51-53`). This is the PoW reward path behind the shared `engine.Finalize` interface.

### Bor (Polygon) — the multi-family example
- `polygon/bor/bor.go:796` — `Bor.Finalize`: **no block reward, no uncles, no withdrawals** (validators paid out-of-band). At **sprint start** it does two sidechain-specific things via `syscall`:
  - `polygon/bor/bor.go:1092` — `checkAndCommitSpan`: rotates the validator set (span) by calling the Bor validator-set system contract.
  - `polygon/bor/bor.go:1217` — `CommitStates`: replays **state-sync events** fetched from the Heimdall **bridge** (`c.bridgeReader.Events` / `EventsWithinTime`) into the `StateReceiver` contract via `syscall(event.To(), event.Data())` — this is how L1→sidechain deposits enter the chain.
- `polygon/bor/state_receiver.go:40` — `ChainStateReceiver.CommitState`, the per-event commit call.
- `polygon/bor/statefull/processor.go:25` — `ChainContext`, the `rules.ChainReader`+`Engine` adapter passed to `CommitStates`.
- `execution/protocol/block_exec.go:203-223` — Bor **state-sync receipt** synthesis: logs emitted by state-sync (present in `ibs.Logs()` but not in any tx receipt) are gathered into a synthetic `StateSyncReceipt` via `DeriveFieldsForBorReceipt`.

## Design decisions & rationale

- **Execution as a stage, not a loop.** Isolating execution behind the stage boundary means it always receives fully-validated headers/bodies/senders and needs only the state transition. Unwind/Prune are first-class per-stage operations, so reorgs are a structured rewind of domain diffsets, not ad-hoc chain surgery.
- **Flat state + separated commitment.** State lives as flat KV domains (`AccountsDomain`, `StorageDomain`, `CommitmentDomain`), so a read is a single `GetLatest`, not a root-to-leaf trie walk. The Merkle root is recomputed as a *separate* commitment step that can be **deferred to block boundaries** during initial sync / fork validation — turning per-tx trie churn into per-block batched commitment.
- **Optimistic parallelism over static partitioning.** exec3 doesn't try to statically prove tx independence; it executes speculatively and *detects* conflicts through a multi-version read/write map, re-executing only the txs that actually conflicted. Correctness is preserved because the committed result is assembled in canonical order from valid versions.
- **One `rules.Engine` interface, per-network implementations.** Ethash (PoW rewards + uncles), the merge/PoS engine (withdrawals + requests, no reward), and Bor (span rotation + state-sync, no reward) all satisfy the same `Initialize` / `Finalize` / `FinalizeAndAssemble` contract. The block-execution core calls the interface; network-specific behavior lives entirely in the engine.

## Notable patterns (the reusable idea)

**exec3 = Block-STM optimistic-parallel execution over flat, multi-versioned state.** The
transferable core is the *layered commit* discipline (`exec3_parallel.go:43-82`): each
abstraction level (per-worker IBS → versioned write collector → in-RAM shared state →
committed RoTx) accumulates changes and atomically promotes them downward *only after
validation*, so no partial/invalid state is ever visible. Conflict detection is a
read-set-vs-version-map check with bounded re-execution — not locking, not static
dependency analysis. This is what lets erigon re-execute historical ranges at high
throughput (archival / trace / re-org validation).

Second reusable idea: **network multi-family via a single engine interface plus a
system-call harness.** Everything network-specific (rewards, validator rotation, sidechain
state-sync, EIP request extraction) is expressed as engine hooks invoked through one
`SysCallContract` harness whose only per-family branch is the author address. Adding a new
consensus family is "implement `rules.Engine`", not "fork the block processor."

## Authority note

erigon is the reference for **staged/parallel execution (exec3 / Block-STM)** and for
**Bor/Polygon sidechain block-execution** (state-sync, span rotation — the NET-01
multi-network reference). It is **not** the authority for canonical ETH/ETC *execution
semantics* — gas schedules, opcode behavior, reward amounts, RLP/receipt encoding — which
remain go-ethereum (ETH) and core-geth (ETC/PoW, ECIP-1017 emission). erigon's Ethash
reward constants (`rules/ethash/rules.go:51-53`) are the vanilla ETH schedule and carry
*no* ETC ECIP-1017 fixed-supply logic; do not read ETC emission rules out of this repo.

## Gotchas / anti-patterns / things they later changed

- **Two execution paths, easy to conflate.** serial and parallel exec3 must produce
  byte-identical state roots; the parallel path carries most of the subtlety (deferred
  commitment, changeset-accumulator single-writer discipline — `exec3_parallel.go:109-127`,
  `ensureChangesetAccumulator`). Their own `CLAUDE.md` documents `#21153`: a `t.Skip`
  hid a real parallel-exec CREATE2-reincarnation / SD bug that was never fixed on the
  branch that claimed to fix it — a caution that exec3 conflict-detection edge cases
  (re-incarnation, self-destruct) are genuinely hard.
- **Unwind has two distinct sub-paths.** `UnwindExecutionStage` (`stage_execute.go:393`)
  treats `u.UnwindPoint >= s.BlockNumber` (in-RAM overlay only, *must not*
  `ResetPendingUpdates`) differently from a true disk unwind (discards pending commitment
  updates, then `SeekCommitment` to rebuild). The load-bearing comments (`:396-427`) warn
  that mixing them mischarges SSTORE gas (SET vs RESET) on re-execution.
- **Unwind beyond snapshot data is forbidden** (repo `CLAUDE.md`: "Unwind beyond data in
  snapshots not allowed"; `ErrTooDeepUnwind`, `stage_execute.go:148`) — snapshots are
  immutable, so deep reorgs past the frozen boundary cannot be rolled back.
- **Bor state-sync receipts are synthetic.** They don't correspond to a transaction;
  their logs are sliced out of `ibs.Logs()` past the last tx-receipt log
  (`block_exec.go:210-219`). Consumers that assume "every receipt maps to a tx" break on
  Bor blocks.
- **`experimentalBAL` (Block Access Lists) still gates the parallel path on** and is
  flagged experimental — the parallel engine is not the unconditional default at tip.
