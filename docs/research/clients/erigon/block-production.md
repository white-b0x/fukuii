# erigon — block-production
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon has **one engine-agnostic block-building pipeline** used for every
consensus family, driven off the Engine API for PoS and reused verbatim by the
Bor sidechain proposer. The pipeline is three staged steps —
`createBlock → execBlock → finishBlock` — run directly (not through
staged-sync machinery) by `builder.Builder.Build`
(`execution/builder/builder.go:106`). The pluggable part is the last step:
`finishBlock` calls `engine.Seal(...)` on a `rules.Engine` implementation, and
that interface is what differentiates PoS (`merge`), local/external PoW
(`ethash`), PoA (`aura`), and the Polygon sidechain (`bor`). There is no
separate "mining worker" — PoS payload building *is* the mining loop
(`block_builder.go` comments it literally as "PoS mining").

The PoS entrypoint is `forkchoiceUpdated` with `payloadAttributes`: erigon
translates the attributes into `builder.Parameters`, calls
`executionService.AssembleBlock`, which registers an **async** `BlockBuilder`
goroutine keyed by a monotonic `payloadId` and returns immediately. The CL later
calls `engine_getPayload(payloadId)`, which `Stop()`s the builder goroutine and
returns the best block assembled so far. Building runs "open-ended": the exec
loop keeps pulling from the txpool until the CL's `interrupt` flag fires or the
max-build-time timer trips.

**Local PoW mining was gutted in 2021** — erigon is external-`getWork`-only.
The `ethash` engine's `Seal`/`GetWork`/`SubmitWork` machinery survives
(`execution/protocol/rules/ethash/`), and a gRPC `MiningServer`
(`node/privateapi/mining.go`) serves `GetWork`/`SubmitWork`/`SubmitHashRate` to
external miners, but there is no in-process CPU-mining stage, no `MiningState`,
no `--mine` CPU loop. (Cross-ref `topics/consensus-pow-cpu-dev-and-deprecated.md`.)

## Key types / interfaces / files

- `execution/builder/builder.go:44` — `Builder`, the three-step direct block
  builder. Its `Build` (`:106`) satisfies `BlockBuilderFunc`.
- `execution/builder/builder.go:160-183` — `Build` body: opens a read-only
  temporal tx, sets up `SharedDomains` (with parent-SD overlay for concurrent
  background commits), then runs `createBlock → execBlock → finishBlock`, and
  reads the result off `state.BuilderResultCh`.
- `execution/builder/block_builder.go:31` — `BlockBuilder`, the async goroutine
  wrapper: "wraps a goroutine that builds Proof-of-Stake payloads (PoS mining)".
  `NewBlockBuilder` (`:39`) spawns the build goroutine plus a max-build-time
  watchdog; `Stop()` (`:90`) sets the interrupt atomic and blocks on a `sync.Cond`
  until the goroutine publishes its result.
- `execution/builder/parameters.go:26` — `Parameters`, the PoS build inputs
  (mirrors Engine API `PayloadAttributesV1..V4`: withdrawals/Shapella,
  parentBeaconBlockRoot/Dencun, slotNumber+targetGasLimit/Amsterdam+Gloas).
  Includes a `CustomTxnProvider` override hook for the tx source.
- `execution/builder/exec.go:173-202` — the tx-selection loop: pulls txns in
  batches of 50 from the injected `txnprovider.TxnProvider`, adds them via
  `BlockAssembler.AddTransactions`, and stops on `interrupt`, `stop`, or a dry
  txpool. `getNextTransactions` (`:238`) uses an `alreadyYielded` set to avoid
  re-yielding and re-admits nonce-too-high txns as their predecessors land.
- `execution/builder/finish.go:61` — `finishBlock`: assembles the block, stores
  it in `LatestBlockBuiltStore`, pushes a pending (pre-seal) block, then calls
  `cfg.engine.Seal(chain, blockWithReceipts, BuilderResultCh, sealCancel)`.
  **This is the single pluggable seal seam.**
- `execution/execmodule/block_building.go:51` — `ExecModule.AssembleBlock`:
  semaphore-guarded, deduplicates identical build requests by `Parameters`,
  evicts old builders (`MaxBuilders` cap), increments `nextPayloadId`, and
  registers a new `BlockBuilder`.
- `execution/execmodule/block_building.go:98` — `GetAssembledBlock`: `Stop()`s
  the keyed builder and returns the block plus `blockValue` (`:78`, the fee
  recipient's expected wei — effective-tip × gas summed over txns).
- `execution/engineapi/engine_server.go:730` — `forkchoiceUpdated`; `:786`
  guards on `s.proposing` (proposer disabled via `--proposer.disable`); `:825`
  builds `builder.Parameters` from `payloadAttributes`; `:849` calls
  `AssembleBlock` inside `waitForResponse` (waits a full slot, 12s, for the
  exec service to be free); returns the `payloadId`.
- `execution/engineapi/engine_server.go:646` — `getPayload`: `:669` calls
  `GetAssembledBlock(payloadId)` and packages the `GetPayloadResponse`.
- `execution/protocol/rules/merge/merge.go:385` — `Merge.Seal` (PoS): if not a
  PoS header, delegate to `eth1Engine.Seal`; otherwise stamp
  `ProofOfStakeNonce` and push the sealed block immediately (no PoW).
- `execution/protocol/rules/ethash/sealer.go:44` — `Ethash.Seal` (local PoW,
  used only for pre-merge / non-CL chains; delegates to `shared` if set).
- `execution/protocol/rules/ethash/api.go:42,66` — `API.GetWork`/`SubmitWork`,
  the **external-miner** RPC surface (returns header pow-hash / seed / target /
  block number; accepts nonce+mixdigest solutions).
- `node/privateapi/mining.go:63,74,84` — gRPC `MiningServer.GetWork`/
  `SubmitWork`/`SubmitHashRate`, each erroring "not supported, consensus engine
  is not ethash" when no ethash engine is present. This is the only surviving
  "mining" server surface.
- `polygon/bor/bor.go:926` — `Bor.Seal` (Polygon sidechain proposer): the
  sprint-based producer. Loads the producer set from `spanReader.Producers`,
  computes a `GetSignerSuccessionNumber` (0 = in-turn primary producer), waits
  `delay`/`wiggle` (out-of-turn producers back off), signs the header with
  `MimetypeBor`, and pushes the sealed block after the timer — all in a
  goroutine cancellable via `stop`.
- `polygon/bor/bor.go:1041` — `Bor.IsProposer` (`successionNumber == 0`);
  `:1022` `IsValidator`; `:1076` `CalcDifficulty` uses `SafeDifficulty(signer)`.
- `polygon/bor/bor.go:69,180,515` — sprint machinery: `defaultSprintLength`
  `{"0": 64}`, `IsSprintStart` gates producer rotation / early announcement,
  `IsSprintEnd` gates when the validator list appears in `extra-data`.

## Design decisions & rationale

- **One pipeline, pluggable Seal.** Rather than a PoW-specific miner and a
  separate PoS builder, erigon has a single `createBlock/execBlock/finishBlock`
  path and hides all consensus-family differences behind `rules.Engine.Seal`.
  PoS `Merge.Seal` is a near-no-op nonce stamp; `Bor.Seal` is a sprint-scheduled
  sign; `Ethash.Seal` is real PoW (only when no CL). The builder code doesn't
  branch on consensus at all.
- **Async build + interrupt = "build as long as you're allowed."** FCU starts
  the goroutine and returns a `payloadId` instantly; `getPayload` interrupts and
  harvests. This matches the Engine API's "start early, collect late" contract
  and lets erigon keep folding in higher-tip txns right up until the CL asks.
  The `maxBuildTimeSecs` watchdog (`SecondsPerSlot()/4`) bounds it.
- **`blockValue` returned with the payload** so the CL/MEV layer can compare
  local vs. builder-relay payloads — production is MEV-aware at the interface
  even though the local builder itself does no reordering beyond tip-priority.
- **External-miner-only PoW.** Erigon deliberately dropped local CPU mining
  (2021): the maintenance and performance cost of an in-process miner wasn't
  worth it for a client optimized for archive/sync, so PoW is served via
  `getWork` to dedicated external miners. The ethash `Seal` remains for tests
  and non-CL private PoW chains.
- **State root computed from accumulated domain writes, not re-execution.**
  `exec.go:227` computes the commitment directly from the `SharedDomains` writes
  produced during assembly (`ComputeCommitment`), avoiding a second execution
  pass over the just-built block.

## Notable patterns (the reusable idea)

**A single engine-agnostic block-assembly pipeline whose only consensus-variant
seam is a `Seal(chain, block, results, stop)` interface method.** Erigon builds
every block — PoS payload, PoA block, Bor sidechain block, (legacy) PoW block —
through the same `createBlock → execBlock → finishBlock` steps, and the *only*
place consensus family matters is the final `engine.Seal` call. Seal is uniform:
it takes a results channel and a stop channel and may complete synchronously
(PoS: stamp nonce, push) or asynchronously in a goroutine (Bor: wait for the
producer's slot; PoW: grind nonces). This cleanly separates **what goes in the
block** (tx selection, execution, state-root) from **how the block is
authorized** (nonce stamp vs. signature vs. PoW). For fukuii — which already has
pluggable *validation*-side consensus seams and is designing production-side
seams (Sealer / ValidatorProvider / BlockInterface, per file-tree memo) — this
is the reference shape: keep the assembly pipeline consensus-blind and route all
family-specific production logic through a `Seal`-shaped interface plus a
`Parameters` struct that carries the payload-attribute superset.

A second reusable idea: the **async builder keyed by payloadId with an interrupt
atomic + Cond-var harvest** (`BlockBuilder` + `ExecModule.builders` map) is a
clean way to implement the Engine API's start-early/collect-late payload
lifecycle without blocking the FCU RPC.

## Authority note

erigon = PoS payload-build (Engine API `forkchoiceUpdated`→`AssembleBlock`→
`getPayload`) + Bor-sidechain sprint-proposer authority. It is **not** the ETC
PoW sealing authority — erigon dropped local CPU mining in 2021 and serves PoW
only via external `getWork`. For ETC/Ethash PoW *sealing* semantics (block
rewards, ECIP-1017 emission, DAG/Ethash sealing), **core-geth is the sole
authority**. Use erigon here for the PoS payload-building shape and the Bor
sidechain proposer pattern, not for PoW sealing correctness.

## Gotchas / anti-patterns / things they later changed

- **Local PoW mining is gone — don't look for a `MiningState`, mining stage, or
  `--mine` CPU loop; they don't exist.** Only the external-`getWork` server
  (`node/privateapi/mining.go`) and the ethash `Seal`/`GetWork` engine methods
  remain. Grep for `MiningState`/`SpawnMiningExec` returns nothing on this commit.
- **`finish.go` is littered with commented-out dedup/resubmit logic** (`:64-67`,
  `:82-88`) carried over from go-ethereum's miner worker. The stale-seal /
  duplicate-sealhash guards were *not* ported — a sign the async single-shot
  builder replaced go-ethereum's continuously-resubmitting worker, and those
  guards no longer apply.
- **Proposer is opt-in and off by default in spirit**: `forkchoiceUpdated`
  errors out with "execution layer not running as a proposer" unless the
  `--proposer.disable` flag is *absent* (`engine_server.go:804`) — the naming is
  inverted, easy to misread.
- **PendingResultCh is best-effort and dropped if unread** (`finish.go:104`) to
  avoid deadlock when `Build` is re-entered before the previous pending block is
  consumed. Don't treat it as a reliable notification stream.
- **Bor out-of-turn sealing uses a `wiggle`/succession-number backoff**
  (`bor.go:960-970`): non-primary producers delay proportionally to their
  succession number so the in-turn producer wins. A naive port that ignored
  `successionNumber` would cause every validator to seal simultaneously.
- **`*current = exec.AssembledBlock{}` in `finish.go:79`** is an explicit "hack
  to clean global data" (their words) — the builder state is reused across
  builds and must be zeroed, an easy source of cross-build state bleed if missed.
