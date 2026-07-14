# go-ethereum — historical PoW / Ethash / ETC-ancestry survey

_Historical (git-archaeology) companion to `consensus-engines.md`. Reconstructs the **PoW/Ethash
era** that current HEAD has stripped. **ETC was originally a go-ethereum fork (DAO split, block
1,920,000, July 2016)** and go-ethereum ran real PoW/Ethash from genesis until the Merge
(mainnet 2022-09-15), so the canonical Ethash structure that fukuii's ETC consensus descends from
lives in geth's pre-merge history — not its HEAD._

**Anchor commit (peak-Ethash reference):** `v1.10.26` = `e5eb32acee19cc9fca6a03b10283b7484246b15a`
(2022-11-03; the last `v1.10.x`, structurally identical to the pre-merge peak — it retains the
complete Ethash mining+DAG package). Secondary anchors cited inline:
`v1.10.8` = pre-beacon downloader (2021-08-24); `09777952e` = engine-abstraction birth (2017-04-05);
`dde2da0ef` = PoW removal (PR #27178, 2023-05-03).
Vendored full clone: `/media/dev/2tb/dev/reference-clients-evm/go-ethereum` (17,145 commits, 2013→2026).
Documented 2026-07-13. Read-only survey — no fukuii source touched.

## Architecture summary
Pre-merge, `consensus/ethash` was a **complete, self-contained PoW engine**: nonce-search mining
(`sealer.go`), the full Ethash memory-hard algorithm with DAG/cache generation (`algorithm.go`),
PoW-hash seal verification + the ice-age difficulty ladder + block rewards (`consensus.go`), an
LRU-cached DAG/cache lifecycle with mmap-to-disk (`ethash.go`), a `getWork`/`submitWork` remote-miner
RPC (`api.go`), and the difficulty-calculator table (`difficulty.go`). All of this implemented the
algorithm-agnostic `consensus.Engine` interface introduced in 2017 (`09777952e`, PR #3817
"pluggable consensus engines") — before which PoW lived in a separate `pow/` package with a
vendored C `libethash`. The Merge (beacon engine added 2021-11, PoW mining stripped 2023-05 in
`dde2da0ef`) collapsed this ~3,700-line package to a ~4-file difficulty/field-verification shim.
**This mature pre-merge organization is the shared ancestor of core-geth's ETChash and, through it,
of fukuii's ETC consensus.**

## 1. Peak Ethash consensus structure (pre-merge, `v1.10.26`)

The package was 12 files. HEAD retains only `consensus.go`, `consensus_test.go`, `difficulty.go`,
`ethash.go` (as a faker shim). The **PoW-bearing files HEAD deleted** were `algorithm.go` (1,152
LOC), `algorithm_test.go` (815), `api.go` (113), `sealer.go` (451), `sealer_test.go` (298),
`mmap_help_{linux,other}.go`, and ~649 LOC of `ethash.go` (per the `dde2da0ef` diffstat).

### 1a. Mining / nonce search — `sealer.go`
- `e5eb32a:consensus/ethash/sealer.go:51` — **`Seal(chain, block, results, stop)`**: the
  `consensus.Engine` mining entry point. Fake modes return a zero nonce immediately (`:52-59`); a
  shared engine delegates (`:63`); otherwise it seeds a PRNG and spins up one `mine` goroutine per
  thread (`:97-99`), returning the first solved block on `results` or aborting on `stop`.
- `e5eb32a:consensus/ethash/sealer.go:132` — **`mine(block, id, seed, abort, found)`**: the actual
  nonce grind. Loops `nonce++` from a random seed (`:144,182`), computing
  `hashimotoFull(dataset.dataset, hash, nonce)` (`:166`) each iteration; when the result satisfies
  the target it stamps `header.Nonce = EncodeNonce(nonce)` + `header.MixDigest` (`:170`) and reports
  the sealed block. `runtime.KeepAlive(dataset)` guards the mmap'd DAG from finalization mid-hash.
- `e5eb32a:consensus/ethash/sealer.go:193-386` — **`remoteSealer`**: the pool-miner path. Channels
  `workCh/fetchWorkCh/submitWorkCh/submitRateCh` (`:206-210`) back the `getWork`/`submitWork` RPC;
  `makeWork` (`:346`) packages `[headerHash, seedHash, target, blockNumber]` and `notifyWork`
  (`:360`) POSTs it to configured miner URLs. This is the stratum-adjacent surface.

### 1b. The Ethash algorithm — `algorithm.go`
- `e5eb32a:consensus/ethash/algorithm.go:37-48` — **the protocol constants**: `datasetInitBytes =
  1<<30` (1 GiB genesis DAG), `datasetGrowthBytes = 1<<23`, `cacheInitBytes = 1<<24`,
  **`epochLength = 30000`** (blocks per epoch), `mixBytes = 128`, `hashBytes = 64`,
  `datasetParents = 256`, `cacheRounds = 3`, `loopAccesses = 64`. **These are the exact values
  core-geth/fukuii must match for ETC** (ETC's ECIP-1099 later *doubled* `epochLength` to 60000 —
  the "ETChash" divergence — but the base constants originate here).
- `e5eb32a:consensus/ethash/algorithm.go:139` — **`generateCache(dest, epoch, seed)`**: builds the
  ~16 MiB+ verification cache via `cacheRounds` of RandMemoHash over keccak512.
- `e5eb32a:consensus/ethash/algorithm.go:269` — **`generateDataset(dest, epoch, cache)`**: builds
  the full multi-GiB mining DAG, parallelized across CPUs, each item from `generateDatasetItem`
  (`:236`) mixing 256 pseudo-random cache nodes.
- `e5eb32a:consensus/ethash/algorithm.go:338` — **`hashimoto(hash, nonce, size, lookup)`**: the core
  memory-hard mix — `loopAccesses = 64` random dataset reads FNV-mixed into a 128-byte mix, folded to
  the 32-byte `result` + `digest`. `hashimotoLight` (`:381`, cache-based, for verification) and
  `hashimotoFull` (`:399`, DAG-based, for mining) are the two entry points.
- `e5eb32a:consensus/ethash/algorithm.go:407` — **`maxEpoch = 2048`** + the `datasetSizes` /
  `cacheSizes` precomputed lookup tables (the first 2048 epochs' sizes, avoiding recomputation).

### 1c. Verification + rewards + difficulty — `consensus.go`
- `e5eb32a:consensus/ethash/consensus.go:524` — **`verifySeal(chain, header, fulldag)`**: the PoW
  check HEAD deleted. Recomputes `digest,result` via `hashimotoLight` (cache, `:566`) or
  `hashimotoFull` (DAG, `:552`), rejects on `header.MixDigest != digest` (`errInvalidMixDigest`,
  `:580`), then enforces the work target `result ≤ 2²⁵⁶ / header.Difficulty` (`:582-584`,
  `errInvalidPoW`). **This is the actual proof-of-work gate; its absence on HEAD is why geth is no
  longer a PoW authority.**
- `e5eb32a:consensus/ethash/consensus.go:315` — `verifyHeader` calls `verifySeal` only when
  `seal==true`, after the field/difficulty/gas checks — the ordering fukuii's `BlockHeaderValidator`
  mirrors.
- `e5eb32a:consensus/ethash/consensus.go:339` — **`CalcDifficulty(config, time, parent)`**: the fork
  ladder — `GrayGlacier→ArrowGlacier→London→MuirGlacier→Constantinople→Byzantium→Homestead→Frontier`
  (`:341-359`), each returning a bomb-delayed calculator.
- `e5eb32a:consensus/ethash/consensus.go:374` — **`makeDifficultyCalculator(bombDelay)`**: the
  EIP-100 Byzantium formula `diff = parent_diff + parent_diff/2048 · max((2 if uncles else 1) −
  (t−t_parent)//9, −99) + 2^(periodCount−2)`, with the ice-age "bomb" delayed by `bombDelay`
  (`:414-427`). **ETC removed the bomb entirely (ECIP-1041/Thanos)** — this geth ladder is the
  pre-divergence baseline.
- `e5eb32a:consensus/ethash/consensus.go:42-45` — **block reward constants**: `FrontierBlockReward =
  5e18`, `ByzantiumBlockReward = 3e18`, `ConstantinopleBlockReward = 2e18` wei.
- `e5eb32a:consensus/ethash/consensus.go:652` — **`accumulateRewards(config, state, header,
  uncles)`**: static block reward selected by fork (`:654-659`), plus per-uncle reward
  `(uncle.Number + 8 − header.Number) · reward / 8` (`:665-668`) and the `reward/32` nephew bonus
  (`:670`). **ETC replaced this entire schedule with ECIP-1017 fixed-supply emission** (5→4→3.2 ETC,
  −20% per 5M-block era) — `BlockRewardCalculator.scala` is fukuii's divergent descendant of exactly
  this function.

### 1d. DAG/cache lifecycle + config — `ethash.go`
- `e5eb32a:consensus/ethash/ethash.go:439-462` — **`Ethash` struct**: `caches *lru` / `datasets *lru`
  (LRU-managed, mmap-backed), mining fields (`rand`, `threads`, `update`, `hashrate`, `remote`), and
  test hooks (`shared`, `fakeFail`, `fakeDelay`). HEAD's struct keeps only the three fake flags.
- `e5eb32a:consensus/ethash/ethash.go:419-433` — **`Config`**: `CacheDir`, `CachesInMem/OnDisk`,
  `DatasetDir`, `DatasetsInMem/OnDisk`, `*LockMmap`, `PowMode`, `NotifyFull` — the on-disk DAG cache
  tuning surface.
- `e5eb32a:consensus/ethash/ethash.go:221` (`cache`) / `:300` (`dataset`) — the wrapped
  cache/dataset with `sync.Once` generation + a `finalizer` that unmaps the mmap; `newlru` (`:182`)
  keeps a bounded set of epochs hot and pre-generates the *next* epoch's dataset asynchronously
  (`dataset(block, async)`, `:596`) so an epoch boundary doesn't stall mining.
- `e5eb32a:consensus/ethash/ethash.go:464` (`New`), `:501` (`NewFaker`), `:549` (`NewShared`) — the
  constructor family; `MakeCache`/`MakeDataset` (`:396,402`) are the CLI DAG-pregeneration hooks.

### 1e. Remote-miner RPC — `api.go`
- `e5eb32a:consensus/ethash/api.go:41` (`GetWork`), `:66` (`SubmitWork`), `:92` (`SubmitHashrate`),
  `:110` (`GetHashrate`) — the `eth_getWork`/`eth_submitWork` JSON-RPC surface for external miners.
  Deleted wholesale by `dde2da0ef`.

## 2. The pre-merge PoW sync path (`eth/downloader`)

**Confirmed present and TD-driven.** Two shapes, before and during the transition:

**Pure pre-beacon (`v1.10.8`, 2021-08-24, before PR #23761):**
- `v1.10.8:eth/downloader/downloader.go:352` — **`synchronise(id, hash, td, mode)`**: no `ttd`, no
  `beaconMode`. The doc comment (`:350`) is explicit: *"it will use the best peer possible and
  synchronize if its TD is higher than our own."* **Total-difficulty head selection** is the entire
  fork-choice.
- `v1.10.8:eth/downloader/downloader.go:138` — **`cancelPeer string`** = the **master peer**: one
  peer is designated to anchor the sync; if it drops, the sync cancels (`:415` "mark the master
  peer").
- `v1.10.8:eth/downloader/downloader.go:462` — `fetchHead(p)` returns `(latest, pivot)`;
  **header-first from genesis with a state pivot near the head** — `fsMinFullBlocks = 64` (`:63`),
  `fsHeaderSafetyNet = 2048` (`:60`), `fsHeaderForceVerify = 24` (`:61`), `pivotHeader` written to
  `rawdb.WriteLastPivotNumber` (`:497`) so a rollback can't cross it. `FastSync` downloads the
  **header chain from genesis** but pulls **state only at the pivot** (`:466-501`). This is the
  "fast-sync-from-genesis" header path core-geth still carries and geth HEAD deleted.
- Sync modes at `v1.10.8:eth/downloader/modes.go`: `FullSync/FastSync/SnapSync/LightSync`. Fast+light
  strategies date to `f186b3901` (2015-10-19, "add fast and light sync strategies") — the ancestral
  fast-sync.

**Transitional (`v1.10.26`, merge-aware but still TD-capable):**
- `v1.10.26:eth/downloader/downloader.go:333` — **`LegacySync(id, head, td, ttd, mode)`** and
  `synchronise(..., beaconMode, beaconPing)` (`:363`) now thread a `beaconMode` flag: legacy path
  still does TD head-selection + master-peer `findAncestor` (`:798`, with span/binary variants
  `:864,924`); beacon path is CL-driven. `processHeaders(origin, td, ttd, beaconMode)` (`:1252`)
  still enforces the **"peer violated its TD promise"** check (`:1304`) in legacy mode. This hybrid
  is the structural fossil of the PoW→PoS sync transition.

**Relevance:** core-geth (and thus fukuii's PoW sync design) keeps the master-peer + TD-highest +
header-first-fast-sync-from-genesis shape. geth HEAD replaced it entirely with skeleton/beacon sync
driven by the consensus layer — inapplicable to a PoW chain that has no CL.

## 3. The Merge removal (structural dating of PoW→PoS)

Three structural commits bracket the transition:

1. **Beacon engine added — `3038e480f` (2021-11-26, PR #23761 "all: core rework for the merge
   transition").** Introduced `consensus/beacon/consensus.go` (the decorator wrapper documented in
   `consensus-engines.md`) *without* removing Ethash — PoW and PoS co-existed, engine chosen by TTD.
2. **PoW mining stripped — `dde2da0ef` (2023-05-03, PR #27178 "all: remove ethash pow, only retain
   shims needed for consensus and tests"),** author Péter Szilágyi. The decisive structural cut. Its
   diffstat deletes `algorithm.go` (−1152), `algorithm_test.go` (−815), `api.go` (−113),
   `sealer.go` (−451), `sealer_test.go` (−298), `mmap_help_*` (−71), and guts `ethash.go` (−649) and
   `consensus.go` (−185). After this, `Seal` panics `"ethash (pow) sealing not supported any more"`
   (HEAD `ethash.go:76-78`) and `verifySeal`/`hashimoto`/`mixDigest` no longer exist in-tree —
   Ethash survives only as a difficulty-ladder + header-field verifier for historical blocks, always
   wrapped by `beacon`. This dates the structural PoW→PoS transition to **mid-2023** (≈8 months after
   the mainnet Merge, once no supported live network still needed to mine).
3. **PoS-only boot guard — `b4d99e39` (2024-11-26, PR #30807 "improve error message if TTD
   missing").** `CreateConsensusEngine` now refuses any chain without `TerminalTotalDifficulty`
   ("Geth only supports PoS networks") — the final door-close making standalone PoW unbootable.

## 4. ETC relevance — the shared ancestor

- **The DAO split (ETC's genesis-as-a-separate-chain), 2016-07-15**: geth commits
  `6060e098c` ("implement flags to control dao fork blocks"), `1e24c2e4f` ("special extradata for
  DAO fork start"), and `a87089fd2` ("add extradata validation to consensus rules") implemented the
  DAO state-reversal hard fork at **block 1,920,000**. **ETC is the chain that declined this fork.**
  geth carries `DAOForkBlock = 1_920_000, DAOForkSupport = true` to this day
  (`e5eb32a:params/config.go:64-65`); ETC's config sets `DAOForkSupport = false`. The
  `consensus/misc/dao.go` extradata guard (still shared code on HEAD) is the fossil of this split —
  every ETC client, fukuii included, must implement the *opposite* side of exactly this rule.
- **Engine-abstraction birth, 2017-04-05 (`09777952e`, PR #3817).** Before this, PoW lived in a
  standalone `pow/` package (`pow/ethash.go`, `pow/ethash_algo.go`, `pow/pow.go`, `pow/xor.go` +
  vendored C `libethash`). PR #3817 extracted the `consensus.Engine` interface and moved Ethash to
  `consensus/ethash/`. **This algorithm-agnostic engine boundary is the direct structural ancestor
  of fukuii's own consensus-engine seam** (the `consensus/` dual-family dispatch) — core-geth
  inherited it verbatim and never removed the PoW body, so it remains fukuii's live ETC reference.
- **What fukuii's ETC path inherited from this pre-merge structure** (and where it then diverged):
  - Ethash constants (`epochLength=30000`, DAG/cache init sizes, `hashimoto` loop) — inherited;
    ETC's ECIP-1099 **doubles `epochLength`→60000** ("ETChash") to slow DAG growth. core-geth is the
    authority for that delta.
  - Ice-age difficulty ladder (`makeDifficultyCalculator` bomb) — inherited then **removed** by ETC
    (Thanos/ECIP-1041 defuses the bomb permanently).
  - `accumulateRewards` static+uncle reward — inherited then **replaced** by ECIP-1017 fixed-supply
    emission (fukuii `BlockRewardCalculator.scala`).
  - Master-peer + TD-highest + header-first fast-sync-from-genesis — inherited and **kept** (ETC has
    no CL, so geth HEAD's beacon-driven sync is inapplicable; core-geth retains the legacy path).

## Authority note
go-ethereum's **pre-merge history is a first-class historical PoW/Ethash reference** — the mature,
canonical organization of an Ethash engine (mining/DAG/verify/difficulty/reward cleanly separated
behind `consensus.Engine`), the ancestral structure that core-geth (the *live* ETC authority) and
fukuii both descend from. It is authoritative for **the *shape* of a well-organized Ethash engine
and for pre-fork ETH baseline values**, but **NOT for current ETC rule values** — every ETC-specific
divergence (ECIP-1099 epoch doubling, ECIP-1041 bomb removal, ECIP-1017 emission, `DAOForkSupport =
false`) post-dates the split and lives in core-geth. Use this doc to learn *how a mature client
structured Ethash*; use core-geth to learn *what ETC's values are*.

## Gotchas / things to carry into fukuii's design
- **HEAD hides the reference.** Anyone reading current `consensus/ethash` sees a 4-file shim and
  would wrongly conclude Ethash is trivial. The real reference is `v1.10.26` and earlier — always
  git-archaeology, never trust HEAD, for PoW structure.
- **The engine seam is the durable win, not the algorithm.** The 2017 `consensus.Engine` extraction
  (`09777952e`) is what let geth later add clique + beacon without rewriting the node. fukuii's
  dual-family `consensus/` dispatch is the same idea; keep the PoW engine's mining+verify body intact
  behind that seam (the thing geth deleted) rather than following HEAD's collapse.
- **DAG lifecycle is non-trivial and easy to under-build.** The pre-merge `lru` + `sync.Once` +
  mmap-finalizer + async next-epoch pregeneration (`ethash.go:182,596`) is production-grade DAG
  management. A naive ETC miner that regenerates the DAG synchronously at every epoch boundary stalls
  — this is the reference for doing it right.
- **`runtime.KeepAlive` around mmap'd DAG reads** (`sealer.go:186`, `consensus.go:556`) is a subtle
  correctness requirement geth learned the hard way — the mmap can be finalized mid-hash. The Scala
  equivalent (keeping the DAG buffer strongly reachable across a native/verify call) is the analogous
  trap for fukuii.
