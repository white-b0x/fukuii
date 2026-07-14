# core-geth — block-production
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary

core-geth is the **only miner-adopted ETC production client**, and its block-production
path is the geth lineage that upstream go-ethereum **deleted at the Merge**: the full
Ethash CPU-sealing engine plus the `eth_getWork`/`eth_submitWork` remote-miner interface
that pools and rigs actually connect to. Two layers cooperate:

1. **Assembly (`miner/`)** — the geth `worker` builds a candidate block: selects/orders
   transactions (`commitTransactions`), runs them, then calls
   `engine.FinalizeAndAssemble` (applies ECIP-1017 rewards + computes state root) to
   produce a sealing task, which it hands to `engine.Seal`. This is essentially unchanged
   from geth; core-geth's PoW divergence is entirely inside the engine.
2. **Sealing (`consensus/ethash/`)** — `Ethash.Seal` fans out to **two** sealers running
   concurrently off the same work: N local CPU `mine` goroutines (real `hashimotoFull`
   search) **and** a `remoteSealer` goroutine serving `getWork` and validating
   `submitWork` from external miners. Whichever produces a valid nonce first delivers the
   sealed block back to the worker's `resultLoop`, which imports it and broadcasts.

The remote path is the production-critical one: real ETC mining is done by external
rigs/pools calling `eth_getWork` → hashing off-node → `eth_submitWork`, **not** by the
in-process CPU miner (which exists mainly for dev/testnets). ECIP-1099 (Etchash)
epoch-doubling is threaded through both the work package (`makeWork`) and verification
(`verifySeal`) so the seed hash / DAG size the node advertises match what miners compute.

## Key types / interfaces / files

Sealing engine (`consensus/ethash/`):
- `sealer.go:68` — `Ethash.Seal(chain, block, results, stop)`: the `consensus.Engine`
  entry point. Handles fake modes, then pushes work to the remote sealer
  (`ethash.remote.workCh <- &sealTask{...}`, line ~139) **and** spawns `threads` local
  `mine` goroutines; first result wins, others aborted via `close(abort)`.
- `sealer.go:178` — `Ethash.mine(...)`: the real local CPU PoW loop. Computes
  `target = 2²⁵⁶ / Difficulty`, then increments a nonce calling
  `hashimotoFull(dataset, SealHash(header), nonce)` until `result ≤ target`; on hit,
  copies the header and sets `Nonce`/`MixDigest`. `runtime.KeepAlive(dataset)` guards the
  mmap'd DAG.
- `sealer.go:~235` — `remoteSealer` struct: owns `works map[Hash]*Block`, `currentWork
  [4]string`, and the channels (`workCh`, `fetchWorkCh`, `submitWorkCh`, `submitRateCh`,
  `fetchRateCh`) that serialize all remote-miner interaction through one goroutine.
- `sealer.go:291` — `startRemoteSealer(...)` / `sealer.go:313` — `loop()`: the single
  goroutine that owns remote state (no locks; everything is channel-serialized). Handles
  new work, `getWork` fetch, `submitWork` verify, hashrate submit/gather, and a 5s ticker
  that garbage-collects stale rates and stale pending `works` (older than
  `staleThreshold=7` blocks, `sealer.go:43`).
- `sealer.go:393` — `remoteSealer.makeWork(block)`: builds the 4-string work package
  `[headerPoWHash, seedHash, target, blockNumberHex]`. **ECIP-1099 lives here** — it calls
  `calcEpochLength(number, ECIP1099Block)` then `SeedHash(epoch, epochLength)` so the
  advertised seed hash matches the doubled-epoch DAG. Registers the block in `works[hash]`.
- `sealer.go:409` — `notifyWork()`: HTTP-POSTs new work to configured push URLs
  (`notifyURLs`); `NotifyFull` sends the whole header, else the `[4]string` array
  (`remoteSealerTimeout = 1s`).
- `sealer.go:452` — **`remoteSealer.submitWork(nonce, mixDigest, sealhash)`**: THE
  verify-before-accept gate. Looks up the pending block by `sealhash`, sets the submitted
  `Nonce`/`MixDigest` on a header copy, and **calls `ethash.verifySeal(nil, header,
  true)` inline** (unless `noverify`). Returns `false` on a bad/stale/absent solution;
  only on a valid seal within `staleThreshold` does it push the sealed block to `results`
  and return `true`.

RPC surface (`consensus/ethash/api.go`):
- `api.go:~42` — `API.GetWork() [4]string`: fetches `currentWork` via `fetchWorkCh`;
  `errNoMiningWork` if none. Bound to `eth_getWork`.
- `api.go:~66` — `API.SubmitWork(nonce, hash, digest) bool`: forwards to
  `submitWorkCh`, blocks on `errc`, returns `err == nil`. Bound to `eth_submitWork`.
- `api.go:~90` — `API.SubmitHashrate` / `GetHashrate`: `eth_submitHashrate` /
  `eth_hashrate` (aggregate local + remote).

Verification / assembly hooks (`consensus/ethash/consensus.go`):
- `consensus.go:544` — `verifySeal(chain, header, fulldag)`: recomputes the PoW.
  ECIP-1099-aware via `calcEpochLength`/`calcEpoch` → `datasetSize(epoch)`; checks
  `MixDigest` equality and `result ≤ 2²⁵⁶/Difficulty`. Same routine used by both header
  import and `submitWork`.
- `consensus.go:642` — `SealHash(header)`: keccak256 over the pre-seal RLP header field
  list (13 fields + optional `BaseFee`). **Panics** if any post-merge field
  (`WithdrawalsHash`, `ExcessBlobGas`, `BlobGasUsed`, `ParentBeaconRoot`) is set — a hard
  guard that ETH PoS headers never reach the Ethash sealer.
- `consensus.go:620` — `Finalize(...)`: applies rewards via `mutations.AccumulateRewards`
  (ECIP-1017 fixed-supply era emission — see `block-execution.md`, not duplicated here).
- `consensus.go:627` — `FinalizeAndAssemble(...)`: rejects withdrawals (`ethash does not
  support withdrawals`), applies rewards, sets `header.Root`, returns the assembled block.

ECIP-1099 epoch math (`consensus/ethash/algorithm.go`):
- `algorithm.go:41-42` — `epochLengthDefault = 30000`, `epochLengthECIP1099 = 60000`.
- `algorithm.go:53` — `calcEpochLength(block, ecip1099FBlock)`: returns the doubled
  60000-block epoch at/after the ECIP-1099 activation block, else 30000.
- `algorithm.go:63` — `calcEpoch(block, epochLength)` = `block / epochLength`.
- `ethash.go:744` — the epoch-42/195 "bad epoch" workaround under the 60000-length regime
  (Etchash DAG transition edge cases).

Reward dispatch (cross-ref only):
- `params/mutations/rewards.go:38` — `IsEnabled(GetEthashECIP1017Transition)` →
  `ecip1017BlockReward`; `:66` `AccumulateRewards`. Full reward math is in
  `block-execution.md`.

Assembly driver (`miner/worker.go`):
- `worker.go:1280` / `worker.go:1374` — `engine.FinalizeAndAssemble(...)` calls.
- `worker.go:737` — `engine.Seal(w.chain, task.block, w.resultCh, stopCh)` push.
- `worker.go:751` — `resultLoop()`: receives sealed blocks on `resultCh`, dedupes by
  hash/pending-task, writes receipts/logs, imports and broadcasts.

Alt-PoW (`consensus/lyra2/`): a separate engine (`lyra2.go`, `sealer.go`, C sources
`Lyra2.c`/`Sponge.c`) mirroring the Ethash engine shape for a non-Ethash PoW variant —
present in the tree but not the ETC production path.

## Design decisions & rationale

- **Verify the seal inside `submitWork` before acking.** `submitWork` runs the *same*
  `verifySeal` used for block import and returns `false` for a bad nonce, stale work, or
  absent pending block (`sealer.go:452-490`). A miner is never told "accepted" for an
  invalid nonce — the contract's truthfulness is the whole point of the getWork protocol.
- **Channel-serialized remote state, no locks.** All `getWork`/`submitWork`/hashrate
  traffic funnels through the single `remoteSealer.loop()` goroutine over channels, so the
  `works` map and `currentWork` need no mutex. Simpler and race-free under concurrent RPC.
- **Local + remote sealers share one work push.** `Seal` feeds both the CPU threads and
  the remote sealer from the identical `sealTask`; the local miner is a fallback/dev
  convenience while real hashpower comes through getWork.
- **`staleThreshold = 7`.** Submissions/pending work more than 7 blocks behind the current
  block are dropped (`sealer.go:43`, GC at `:373`, acceptance window at `:486`) — bounds
  memory and rejects hopelessly late solutions while tolerating brief reorg/latency slack.
- **ECIP-1099 computed at work-creation, not just verification.** `makeWork` derives the
  seed hash from the doubled-epoch length so the node and external miner agree on the DAG;
  a node that only applied ECIP-1099 in `verifySeal` would advertise a wrong `seedHash`
  and every submission would fail.
- **Post-merge fields are a panic, not a soft reject.** `SealHash` panics on withdrawals /
  blob-gas / beacon-root, structurally guaranteeing ETH PoS headers can't be Ethash-sealed.

## Notable patterns (the reusable idea)

The **getWork/submitWork state machine** is the transferable design: a single owner
(here, one goroutine + a `works map[sealHash]*Block`) that (1) snapshots the current
sealing candidate into a 4-tuple work package keyed by seal-hash, (2) serves it to any
number of external miners, and (3) on submission, re-attaches the submitted nonce+mix to
the *stored* candidate and runs the exact same PoW verifier the import path uses —
returning a truthful accept/reject **synchronously**, and only forwarding a genuinely
sealed block downstream. The seal-hash is the join key between "work handed out" and
"solution submitted"; the staleThreshold window bounds how long a handed-out package
stays valid. Any client wanting pool compatibility must reproduce this exact contract.

## Authority note

core-geth = THE ETC block-production + Ethash-sealing + getWork/submitWork authority — the
only miner-adopted ETC production client. Upstream go-ethereum deleted the entire CPU
sealer and remote-miner interface at the Merge, so for ETC there is no other reference:
the byte-level behavior of `eth_getWork` (seedHash under ECIP-1099), `eth_submitWork`
(verify-before-ack, staleThreshold), the SealHash field list, and FinalizeAndAssemble's
reward/withdrawal handling are all defined here. fukuii's `EthMiningService` +
`WorkNotifier` are validated against this file — see
`docs/research/clients/topics/mining-protocol-evm.md`.

## Gotchas / anti-patterns / things they later changed

- **`submitWork` must verify before returning `true` — fukuii currently does not.** Finding
  **SUBMITWORK-VERIFY-SEAL-01** (`.claude/sprints/queue/chase-deferred.md`): fukuii's
  `jsonrpc/EthMiningService.scala:155-185` forwards the block to the sync controller and
  returns `SubmitWorkResponse(true)` **before** verifying the PoW seal, then the block is
  silently dropped in import — a rig submitting an invalid nonce is told it succeeded.
  core-geth's `sealer.go:452-490` verifies inline and returns `false`. This is a
  miner-facing getWork-**contract** divergence (not consensus: invalid blocks drop, no
  state-root effect), and mining pools are a top-priority fukuii use case. Fix: verify the
  seal before acking, matching core-geth.
- **`GetWork` errors if the remote sealer isn't running** (`api.go`: `not supported` when
  `ethash.remote == nil`). getWork is only wired when the node is started as a miner.
- **Fake PoW modes short-circuit everything.** `ModeFake`/`ModeFullFake`/`ModePoissonFake`
  return zero/random nonces without hashing (`Seal`) and accept any seal (`verifySeal`) —
  test-only; never enable on a real network.
- **DAG/cache liveness under mmap.** Both `mine` and `verifySeal` end with
  `runtime.KeepAlive(dataset/cache)` because the mmap'd Ethash data is unmapped by a
  finalizer; forgetting this frees the DAG mid-hash. A GC-language port (JVM/fukuii) using
  off-heap DAG memory faces the identical lifetime hazard.
- **Withdrawals are a hard error in the PoW assembler** (`FinalizeAndAssemble`:
  `ethash does not support withdrawals`) — do not route ETH PoS block-building through the
  Ethash engine.
- **This is a frozen deprecated authority.** Documented at `upstream@4185df450`
  (2025-01); it is the byte-reference, not a maintained target. fukuii carries the ETC
  path forward (Olympia overlay on `main`) — never read core-geth `main` for ETC
  production semantics.
