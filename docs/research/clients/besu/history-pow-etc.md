# besu — history: PoW/Ethash mining & ETC/Classic support (git archaeology)

_Anchor: repo checked out on branch `upstream` (pristine hyperledger/besu), HEAD
`3fd233a4f93556e932f734d8feecbad4a047ff67`. This file reconstructs history from
the `upstream` lineage ONLY — every SHA below is verified an ancestor of `upstream`
via `git merge-base --is-ancestor <sha> upstream`. Read-only research; no fukuii
source touched. Documented 2026-07-13._

_Method: git archaeology on the FULL vendored clone (6,977 commits, 2018→2026).
File contents read at historical SHAs via `git show <sha>:<path>`. Two removal
frontiers anchor the reads: **ETC removal** `1167c5a544` (#9671, 2026-02-10) — read
ETC files at its parent `1167c5a544^`; **PoW-mining removal** `8fc6805f88` (#10656
Phase 1, 2026-06-17) — read mining files at its parent `9ff8ead35116a4faa174203b9035a56e80bbfe38`._

> **⚠️ Overlay warning (read before trusting any grep of this repo).** The vendored
> repo also carries a `main` branch that is **47 commits ahead of `upstream`** and is
> **fukuii's OWN Olympia/ETC overlay ("olympia-besu-v26.6.1"), NOT upstream besu.**
> A naive `git log --all -S"classic"` / `-S"olympia"` mixes the two. Everything in
> the main body below is `upstream`-verified. The overlay is described, clearly
> labelled, in the final section — do not cite it as besu behaviour.

## Architecture summary

Pristine upstream besu **did** carry a complete Ethash PoW mining/sealing path and
**did** support Ethereum Classic + Mordor as first-class networks for ~6 years
(Pantheon era 2019 → Feb 2026). Both were removed only recently, in two separate
campaigns:

- **ETC/Classic + Mordor networks** removed **2026-02-10** (`1167c5a544`, #9671).
- **PoW/Ethash mining infrastructure** removed **June 2026** in a staged 3-phase
  effort (`8fc6805f88`/`26d3251394`/`f9e14c7d64`), after the merge had long made it
  dead weight.

Mining plugged into besu's **mechanism-decorates-fork-schedule** structure (the same
pattern the current-HEAD `consensus-engines.md` documents for Clique/BFT): PoW was
not a subclass of the schedule — the hasher and difficulty calculator were **fields
on each `ProtocolSpec`** (`Optional<PoWHasher> powHasher`, `DifficultyCalculator`),
and the mining actors (`PoWMiningCoordinator` → `PoWMinerExecutor` →
`PoWBlockCreator` → `PoWSolver` → `PoWHasher`) read the per-fork spec to know how to
seal. ETC was expressed purely as a fork-schedule variant (`ClassicProtocolSpecs`
decorating `MainnetProtocolSpecs`) plus ETC-specific difficulty/reward/epoch pieces —
**no separate consensus engine**.

## Timeline (upstream-verified SHAs + dates)

| Date | SHA | PR | Event |
|------|-----|----|-------|
| 2019-11-08 | `cb7570135e` | #176 | **First ETC support** — "ETC Configuration, classic fork peer validator" (Pantheon era) |
| 2019-11-12 | `bfa29cdd8a` | #177 | ETC DieHard fork support (+ `classic.json` genesis) |
| 2019-11 | `e60aa3fdb6` | #178 | ETC Gotham fork |
| (2019) | `f2639c3a88`→`cc0b0e46c2` | #192/#199 | Mordor testnet added, then reverted ("failed field testing"), re-added later |
| 2020-10-09 | `787871a13a` | #1421 | **ECIP-1099** — "Implement ECIP-1099: Calibrate Epoch Duration" (ETChash epoch doubling) |
| (2020) | `2d7cdf0da4` | #1462 | rename genesis option `Ecip1099Block` → `ThanosBlock` |
| (2020) | `7b695c7c99` | #1633 | add `epochStartBlock` to `EpochCalculator` |
| 2021-03-05 | `db23aef122` | #1882 | **ECIP-1049 Keccak256 mining** (`KeccakHasher` — experimental ETC PoW-swap network) |
| 2023-04-20 | `031101603b` | #5371 | Retire ECIP-1049 network (KeccakHasher removed) |
| 2023-12-12 | `f58f6cffca`/`d42380ed17` | #6078/#6267 | **ETC "Spiral"** network upgrade + mainnet activation block |
| 2025-06-12 | `c82b329935` | #8802 | remove **Stratum** mining options/services (external-miner server) |
| **2026-02-10** | **`1167c5a544`** | **#9671** | **remove classic and mordor network support** (ETC fully gone from upstream) |
| 2026-06-17 | `8fc6805f88` | #10656 | **PoW-removal Phase 1** — mining infra (coordinator/executor/creator/solver) |
| 2026-06-22 | `26d3251394` | #10659 | PoW-removal Phase 2 — Ethash + PoW **validation** code (`PoWHasher`, `EthHashCacheFactory`, `EpochCalculator`, `EthHash`) |
| 2026-06 | `f9e14c7d64` | #10662 | PoW-removal Phase 3 — `miner_start`/`miner_stop`/`eth_mining` RPC methods |
| 2026-06 | `ae1e10f09d` | #10703 | remove vestigial `ethash{}` from genesis files |

## 1. The full Ethash mining/sealing structure before removal

_Read at `9ff8ead351` (parent of Phase-1 removal), package `org.hyperledger.besu`
(migrated from Pantheon-era `net.consensys.pantheon` → `tech.pegasys.pantheon`)._

### Mining actor chain (`ethereum/blockcreation/`)
- `9ff8ead351:ethereum/blockcreation/.../PoWMiningCoordinator.java:32-70` —
  `PoWMiningCoordinator extends AbstractMiningCoordinator<PoWBlockMiner> implements
  BlockAddedObserver`. Holds a `PoWMinerExecutor executor` + `SyncState`; exposes
  `setCoinbase(Address)` (`:46`, delegates to executor), `onResumeMining`/`onPauseMining`
  (`:51`/`:56`), `changeTargetGasLimit` (`:61`), `getEpochCalculator` (`:70`). This is
  the direct analogue the current-HEAD `consensus-engines.md` names as the (now-stubbed)
  Clique `NoopMiningCoordinator`'s live predecessor.
- `.../PoWMinerExecutor.java:34-113` — `extends AbstractMinerExecutor<PoWBlockMiner>`;
  holds `EpochCalculator epochCalculator` (`:36`). Per block it news up a
  `PoWSolver` from **`nextBlockProtocolSpec.getPoWHasher().get()`** (`:79-84`) and a
  `Function<BlockHeader, PoWBlockCreator>` (`:85-93`) — i.e. the executor asks the
  **fork's `ProtocolSpec`** which hasher to use.
- `.../PoWBlockCreator.java:37-106` — `extends AbstractBlockCreator`, holds
  `PoWSolver nonceSolver`. `createFinalBlockHeader` (`:62`) builds a `PoWSolverInputs`
  work definition, `nonceSolver.solveFor(PoWSolverJob.createFromInputs(...))` (`:66`),
  and stamps `.mixHash(solution.getMixHash()).nonce(solution.getNonce())` (`:74-75`)
  onto the sealed header. Also the **external-miner surface**: `getWorkDefinition()`
  (`:91`, → `eth_getWork`), `hashesPerSecond()` (`:95`), `submitWork(PoWSolution)`
  (`:99`, → `eth_submitWork`), `cancel()` (`:106`).
- `.../PoWBlockMiner.java`, `AbstractMiningCoordinator.java` (224 LOC),
  `AbstractMinerExecutor.java`, `IncrementingNonceGenerator`/`RandomNonceGenerator` —
  the generic mine-loop scaffolding, all deleted in Phase 1.

### Solver + hasher + DAG (`ethereum/core/.../mainnet/`)
- `9ff8ead351:ethereum/core/.../mainnet/PoWSolver.java:33-160` — the mining loop.
  Inner `PoWSolverJob` (`:39`, wraps a `CompletableFuture<PoWSolution>`, cancelable
  `:65`); fields `PoWHasher poWHasher` (`:80`), `volatile long hashesPerSecond`,
  `EpochCalculator epochCalculator`, an `ExpiringMap<Bytes, PoWSolverJob>` of live jobs
  (`:84`). `solveFor` (`:97`) iterates nonces via `testNonce` (`:129`) tracking
  hashrate; `submitSolution` (`:150`, external submissions) and `getWorkDefinition`
  (`:139`) back the RPC. Registers `PoWObserver`s (`:93`, Stratum callback wiring).
- `.../PoWHasher.java:19-67` — the interface. `PoWSolution hash(long nonce, long number,
  EpochCalculator epochCalc, Bytes prePowHash)` (`:33`); constants
  `ETHASH_LIGHT = new EthashLight()` (`:21`) and an `Unsupported` impl that throws
  (`:57-67`). `EthashLight` (`:36`) is the light-verification Ethash.
- `.../EthHashCacheFactory.java:23-61` — DAG-cache generation. `EthHashDescriptor`
  (datasetSize + `int[] cache`); `cacheFor(blockNumber, EpochCalculator)` computes the
  epoch index via `epochCalc.cacheEpoch(...)` and builds the cache with
  `EthHash.mkCache(EthHash.cacheSize(epochIndex), blockNumber, epochCalculator)` and
  `EthHash.datasetSize(epochIndex)`.
- `.../EpochCalculator.java` — interface `epochStartBlock` / `cacheEpoch`.
  `DefaultEpochCalculator` uses `EthHash.EPOCH_LENGTH` (30000). **At `9ff8ead351`
  (June 2026) only `DefaultEpochCalculator` remains** — the ETChash variant was
  removed with ETC in Feb; see §3.
- `.../EthHash.java` (Phase-2 removal) — the raw Ethash primitive (`mkCache`,
  `cacheSize`, `datasetSize`, `EPOCH_LENGTH`).
- `PowAlgorithm` enum (`config/.../PowAlgorithm.java`, read at `9ff8ead351`) had
  shrunk to just `{ UNSUPPORTED, ETHASH }` — `KECCAK256` was already gone (ECIP-1049
  retired 2023, #5371).

### External miners (Stratum) — removed a year earlier
- Stratum server + protocol (`Stratum1Protocol`, `Stratum1EthProxyProtocol`,
  `StratumServer`, `StratumConnection`, `PoWObserver`) let external rigs mine against
  a besu node. **Removed 2025-06-12** (`c82b329935`, #8802) — a year before the mining
  loop itself. After that, only the in-process solver + `eth_getWork`/`eth_submitWork`
  RPC path remained for external miners.

## 2. The removal (staged, dated, rationale)

Two independent campaigns, both by Sally MacFarlane (besu maintainer):

**(a) ETC/Classic removal — 2026-02-10, `1167c5a544` (#9671)** "remove classic and
mordor network support." Deleted `ClassicProtocolSpecs`, `ClassicDifficultyCalculators`,
`classic.json`/`mordor.json`, the ETChash `Ecip1099EpochCalculator`, and the Classic
network CLI wiring. Terse commit body — no elaborate rationale, consistent with pruning
a long-unmaintained non-ETH network from the canonical ETH client.

**(b) PoW/Ethash mining removal — June 2026, three phases** (each commit body states
the scope explicitly; PRs co-authored with Claude Sonnet 4.6):
- **Phase 1** `8fc6805f88` (#10656, 2026-06-17): "remove PoW mining infrastructure" —
  `PoWBlockCreator`, `PoWBlockMiner`, `PoWMinerExecutor`, `PoWMiningCoordinator`,
  `AbstractMinerExecutor`, `AbstractMiningCoordinator`, nonce generators, `PoWSolver`,
  `PoWSolverInputs`, `PoWObserver` (22 files, 1,773 deletions). `NoopMiningCoordinator`
  becomes the sole coordinator.
- **Phase 2** `26d3251394` (#10659, 2026-06-22): "remove Ethash and PoW validation
  code" — `PoWHasher`, `EthHashCacheFactory`, `EpochCalculator`, `EthHash`.
- **Phase 3** `f9e14c7d64` (#10662): remove `miner_start`/`miner_stop`/`eth_mining`
  RPC methods; `ae1e10f09d` (#10703) strips vestigial `ethash{}` from genesis files.

Structural rationale: this is the natural end-state after The Merge — geth/reth had
already dropped standalone PoW; besu kept the code dormant for years and only garbage-
collected it in mid-2026. **The current-HEAD `consensus-engines.md` "Ethash is besu's
default ruleset" note describes what's LEFT (header-validation defaults on the mainnet
`ProtocolSpec`), not a working miner — block *production* for PoW is gone.**

## 3. ETC / Ethereum Classic — YES, upstream besu supported it (2019 → Feb 2026)

**This is the headline correction: pristine upstream besu had a genuinely complete ETC
implementation for ~6 years.** Not an abandoned attempt — a first-class, maintained
network, verified on `upstream` (every SHA in §Timeline passes
`merge-base --is-ancestor … upstream`).

### The fork schedule (`ClassicProtocolSpecs`, read at `1167c5a544^`)
`ethereum/core/.../mainnet/ClassicProtocolSpecs.java` — the ETC ruleset, built by the
**same decorate-the-previous-fork pattern** as mainnet. Each fork method calls the
prior fork's `ProtocolSpecBuilder` and overrides only the ETC-specific fields:
- `classicRecoveryInitDefinition` → decorates `MainnetProtocolSpecs.homesteadDefinition`
- `tangerineWhistleDefinition` → `dieHardDefinition` (`:106`,
  `ClassicDifficultyCalculators.DIFFICULTY_BOMB_PAUSED`)
- `gothamDefinition` (`:125`, `MAX_BLOCK_REWARD` + ECIP-1017 emission reward calculator,
  `DIFFICULTY_BOMB_DELAYED`)
- `defuseDifficultyBombDefinition` (`:161`, `DIFFICULTY_BOMB_REMOVED`)
- `atlantisDefinition` (`:183`, `ClassicDifficultyCalculators.EIP100`)
- `aghartaDefinition` (`:231`) → `phoenixDefinition` (`:254`)
- **`thanosDefinition`** (`:279`) — wires **`new EpochCalculator.Ecip1099EpochCalculator()`
  + `powHasher(PowAlgorithm.ETHASH)`** (`:298-302`): this is ETChash — ECIP-1099 epoch
  doubling (30k→60k) layered onto Ethash via the ProtocolSpec's hasher+epoch fields.
- `magnetoDefinition` (`:306`) → `mystiqueDefinition` (`:336`) → `spiralDefinition` (`:360`)

So the ETC fork chain **DieHard → Gotham → Defuse → Atlantis → Agharta → Phoenix →
Thanos → Magneto → Mystique → Spiral** is expressed entirely as
`ProtocolSpecBuilder` decorations over the mainnet specs, with three ETC-specific
ingredients: `ClassicDifficultyCalculators` (difficulty-bomb schedule), an ECIP-1017
fixed-emission block-reward calculator, and (from Thanos) `Ecip1099EpochCalculator`
(ETChash). `powHasher` is imported from `MainnetProtocolSpecs` — ETC reused the
mainnet Ethash hasher plumbing, only swapping the epoch calculator.

### ECIP coverage besu actually had
- **ECIP-1017** — fixed-supply monetary policy (ETC block reward era decay) via the
  Gotham/Classic reward calculators.
- **ECIP-1099 (ETChash)** — `Ecip1099EpochCalculator`, added #1421 (2020), wired at
  Thanos. Full epoch-length calibration.
- **ECIP-1049 (Keccak256 PoW)** — `KeccakHasher` + `PowAlgorithm.KECCAK256`, added
  #1882 (2021) for the experimental ETC PoW-algorithm-swap testnet, **retired** #5371
  (2023). A second PoW hasher besu once shipped alongside Ethash.
- **Spiral** — the ETC network upgrade (#6078/#6267, 2023), besu's last ETC fork.

### What besu's native ETC did NOT have
No MESS/ECBP-1100 subjective fork-choice, no ECIP-1111/1112/1121/1122 (Olympia) — those
postdate besu's ETC removal and exist only in fukuii's overlay (next section). besu's
ETC was PoW/Ethash + ECIP-1017/1099 network config; it stopped at Spiral.

## 4. besu's ProtocolSchedule/ProtocolSpec structure for PoW

The **mechanism-decorates-fork-schedule** pattern (documented in full in
`consensus-engines.md`) applied to Ethash mining exactly as it does to PoA — but
with a key difference: **PoW was the DEFAULT ruleset, so it needed no decorator
layer.** The Ethash pieces live directly on the mainnet `ProtocolSpec`:
- `ProtocolSpec.java` (read at `9ff8ead351`): `Optional<PoWHasher> powHasher` field
  (`:80`, getter `getPoWHasher()` `:388`) and `DifficultyCalculator difficultyCalculator`
  (`:68`, getter `:324`). These are the two fields a PoW ruleset populates —
  `MainnetProtocolSpecs.powHasher(PowAlgorithm.ETHASH)` for ETH, and
  `ClassicProtocolSpecs` overriding `difficultyCalculator` (bomb schedule) +
  `Ecip1099EpochCalculator` for ETC.
- The **mining actors read the schedule, not vice versa**: `PoWMinerExecutor` fetches
  `nextBlockProtocolSpec.getPoWHasher().get()` per block (`9ff8ead351:...PoWMinerExecutor.java:79-84`).
  The fork ruleset stays authoritative for "how do I hash/seal block N"; the coordinator
  is mechanism-generic.
- **Controller wiring** (`9ff8ead351:app/.../controller/MainnetBesuControllerBuilder.java:39-67`):
  `createMiningCoordinator` news a `PoWMinerExecutor` (with a controller-level
  `DefaultEpochCalculator`, `:39`) wrapped in a `PoWMiningCoordinator` — this was the
  live coordinator for a PoW network (now `NoopMiningCoordinator`). Mechanism selection
  routed here via `getPowAlgorithm()==ETHASH` → `MainnetBesuControllerBuilder` (the
  same positive-marker dispatch `consensus-engines.md` documents).

Contrast with the current-HEAD Clique/BFT decorators: those swap `blockReward`,
`miningBeneficiaryCalculator`, header validators. PoW instead owned `powHasher` +
`difficultyCalculator` as the *base* schedule's fields — a structural asymmetry worth
naming: **the default mechanism doesn't decorate; the non-default mechanisms do.**

## Authority note

**Historical besu is a valid SECONDARY PoW reference for fukuii — and a distinct JVM
data point.** Its ProtocolSpec-based PoW organization (hasher + difficulty as
per-fork spec fields, mechanism-generic mining actors reading the fork spec) is a
different structural take than core-geth's Go `consensus.Engine`/`ethash` package, and
being JVM it maps more naturally onto fukuii's Scala. **Crucially, besu is the only
JVM reference client that shipped a real ETChash (ECIP-1099) + ECIP-1017 + full
ETC-fork-schedule implementation** — so its `ClassicProtocolSpecs` decorate-pattern and
`Ecip1099EpochCalculator` are directly comparable to fukuii's ETC code.

**But it is NOT the ETC authority** and its ETC code is now DELETED from upstream
(Feb 2026). core-geth remains the sole living authority for ETChash/ECIP-1017/1099/
1111/1122. Two cautions when mining besu history for ETC: (1) it's frozen at Spiral —
no Olympia/MESS; (2) fukuii's own overlay commit `8fd892c146` is titled "correct
ECIP-1099 epoch formula **to match core-geth**", implying besu's upstream ECIP-1099
formula diverged from core-geth — **do not treat besu's ETChash as byte-authoritative;
cross-check against core-geth.**

## Gotchas / anti-patterns / things they later changed

- **All of this is DELETED from current HEAD.** Cite these as *history* (with the SHAs
  above), never as present besu behaviour. `consensus-engines.md` documents the
  post-removal state; this file documents what was removed.
- **Two separate removals, ~4 months apart** — ETC networks (Feb 2026, #9671) then
  PoW mining (June 2026, #10656+). Don't conflate them: after #9671 besu still had a
  working Ethash miner for ETH-family/dev chains for 4 more months; after June it had
  neither.
- **`PowAlgorithm` enum shrank over time** — once `{ ETHASH, KECCAK256, UNSUPPORTED }`
  (ECIP-1049 era), reduced to `{ ETHASH, UNSUPPORTED }` by 2023, and the whole enum is
  Phase-2-removal-adjacent. A moving target across history.
- **Stratum removed a year before the solver** (#8802, 2025-06) — external-miner
  *server* support died before in-process mining did. If reconstructing besu's external-
  miner story, the `eth_getWork`/`eth_submitWork` RPC path (via `PoWBlockCreator`)
  outlived Stratum.
- **The mining actors are mechanism-generic but the naming isn't** — `EthHash*` classes
  were renamed to `PoW*` mid-history (`EthHashMiningCoordinator`→`PoWMiningCoordinator`,
  `EthHashBlockCreator`→`PoWBlockCreator`, `EthHashSolver`→`PoWSolver`). A `git log -S`
  on either name only finds half the history; search both.

---

## SEPARATE NOTE — fukuii's `olympia-besu` ETC overlay (this repo's `main` branch — NOT besu)

**This is fukuii's OWN work, committed onto a `main` branch in the vendored clone; it
is NOT upstream besu and must never be cited as besu behaviour.** Recorded here only so
a future reader who greps this repo and hits these commits knows what they are.

- **What it is:** `main` is **47 commits ahead of `upstream`** (`git rev-list --count
  upstream..main`), branched from besu at merge-base `c44c1e3e37` (2026-06-12) —
  *before* upstream's June PoW-mining removal, so the overlay still carries the full
  `PoWMiningCoordinator`/`PoWSolver`/`PoWHasher` infrastructure. Client renamed
  `olympia-besu-v26.6.1` (`1b6783cb7a`). Verified overlay-only: none of its commits are
  ancestors of `upstream`.
- **What it adds** (all overlay-only SHAs): `d8b7bc7cdb` "restore Classic and Mordor
  network support" (explicitly "Undeprecate both networks" — confirming besu *had* and
  *removed* them); `1b31ec473e` "re-enable PoW mining with ETChash and MESS";
  `d36826fa77` full Olympia hard fork (config/EVM/protocol); `bbb56131f7` parameterize
  ECIP-1099 activation block; `8fd892c146` "correct ECIP-1099 epoch formula to match
  core-geth"; ECIP-1111 base-fee/treasury; ECIP-1112 treasury address; ECIP-1121
  EIP-7939 CLZ opcode; ECIP-1122 tip/gas-floor; ECBP-1100 MESS; ETH69 3-tier PoW-TD;
  `94b4713ee1` "comprehensive 86-test Olympia suite with **Fukuii parity**".
- **Why it matters for the survey:** it is direct evidence that (a) besu's PoW +
  ClassicProtocolSpecs structure is a viable base for a modern Olympia-era ETC client,
  and (b) fukuii already experimented with porting Olympia onto besu. It is a fukuii
  artefact, not a reference-client data point — keep it out of the observations tables.
