# Topic — CPU-PoW / dev-sealing / private-testnet consensus + deprecated-PoW synthesis
_Documented 2026-07-13. Cross-client survey (git for removal commits). Cross-refs history-pow-etc + mining-protocol topics._

**Scope & non-duplication.** This doc covers three *non-production* consensus modes that
fukuii's omni-client charter explicitly keeps — **internal CPU-Ethash sealing** (private PoW
testnets / conformance testing), **dev / instant-seal** (local dev + CI), and **faker/test
PoW engines** — plus a **synthesis** of the deprecated-PoW findings already derived in the
per-client `history-pow-etc.md` docs. It does **not** re-derive the getWork/pool interface
(→ `topics/mining-protocol-evm.md`), the Ethash algorithm/DAG (→ `go-ethereum/history-pow-etc.md`),
or each client's ETC history (→ the six `*/history-pow-etc.md` + `*/consensus-engines.md`).
Read-only survey; no fukuii source touched.

**Anchors (all `upstream`/HEAD unless noted):**
- go-ethereum HEAD `59e89e81e`; PoW-removal `dde2da0ef` (PR #27178, 2023-05-03); peak-Ethash `v1.10.26` = `e5eb32a`.
- core-geth HEAD `b28aa0a0b` (frozen `4185df450` lineage — **the ETC PoW authority, retains sealing**).
- besu HEAD `3fd233a4f9`; mining read at `9ff8ead351` (parent of June-2026 removal).
- nethermind HEAD `0d09a09ed` (2026-07 — **still ships opt-in CPU Ethash**).
- erigon HEAD `f1d79d699e`.
- reth HEAD `3d76b93c2` (**never had PoW**).

---

## Internal CPU-PoW sealing (private PoW testnets / PoW testing)

The node grinds nonces on its **own CPU** via `hashimotoFull` over the full DAG — no external
rig, no pool. Too slow for mainnet, but the natural way to run a **private Ethash/ETC-flavored
net** (the PoW analogue of a Clique/PoA devnet) and to exercise the full block-production
pipeline in CI. Distinct from getWork+external-GPU production (that's `mining-protocol-evm.md`
§1–§2); this survey is the **status of the in-node CPU miner** per client.

| Client | In-node CPU Ethash sealer | Status | Evidence |
|---|---|---|---|
| **core-geth** | ✅ real `Seal`→`mine`→`hashimotoFull` + `remoteSealer` | **RETAINED (byte-authority)** | `4185df450:consensus/ethash/sealer.go:68-176` (`Seal`), `:178+` (`mine`); `PowMode` incl. real mining path |
| **nethermind** | ✅ real `EthashSealer`→`Ethash.Mine` (nonce search) | **RETAINED — opt-in** (`miningConfig.Enabled`, default off) | `EthashSealer.cs:27-40 SealBlock`→`MineAsync`; `Ethash.cs:149-194 Mine(BlockHeader, startNonce)`; gated `EthashPlugin.cs:49-52` |
| **go-ethereum** | ❌ deleted (`Seal` panics) | **REMOVED at merge cleanup** | `dde2da0ef` (2023-05-03, PR #27178) deleted `sealer.go`(−451)/`algorithm.go`(−1152); HEAD `ethash.go:76` panics `"ethash (pow) sealing not supported any more"` |
| **besu** | ❌ deleted (`PoWSolver` gone) | **REMOVED June 2026** (had it ~6 yrs) | Phase 1 `8fc6805f88` (#10656) removed `PoWSolver`/`PoWMiningCoordinator`; solver was `9ff8ead351:.../PoWSolver.java:97 solveFor` |
| **erigon** | ❌ removed early (external-only since) | **REMOVED March 2021** (pre-merge) | `8ccc6b2664` (#1617, 2021-03-28) "remove local pow mining … `--miner.notify` is required now" — gutted local sealing loop, kept only `remoteSealer` |
| **reth** | ❌ never existed | **N/A (never PoW)** | no `hashimoto`/ethash crate; `crates/ethereum/node/tests/e2e/dev.rs` is dev auto-seal, not PoW |

**Two clients still self-mine Ethash on CPU today: core-geth and nethermind.**
- **core-geth** — same binary as the getWork path; `--mine --miner.threads=N` (N>0) = CPU
  sealing, requires an explicit `--miner.etherbase` (else `StartMining` refuses). This is
  the **ETC-correct** CPU miner (ECIP-1099 epoch, ECIP-1017 reward) — fukuii's alignment target.
- **nethermind** — the **cleanest opt-in model**: a single `IMiningConfig.Enabled` boolean
  (default `false`), no getWork surface. Vanilla ETH-Ethash only (`Ethash.EpochLength` is a
  compile-time `const 30000`, not ETChash) — a generic-Ethash cross-check, never ETC.
  Mining was *added* `8fb6675a7` (#4034, 2022-05, ~3 mo before the Merge) precisely for
  private/dev PoW nets + hive/consensus vectors + pre-merge full-sync validation, and no
  removal commit exists in the Ethash dir.

fukuii already has the machinery: `consensus/pow/miners/{EthashMiner,MockedMiner,Miner}.scala`
+ `EthashDAGManager.scala` (per `mining-protocol-evm.md` §5). **Verdict: internal CPU sealing =
OPTIONAL(private-PoW-testnet / dev / conformance) — a real fukuii feature, not obsolete.**

---

## Dev / instant-seal / fake-sealer (local dev + CI)

A **zero/near-zero-difficulty instant-block** producer for local development and CI — a block
per pending transaction (or per interval), no hashpower. Every client ships one; the
*mechanism* differs (Clique-instant, simulated-beacon, fixed-difficulty-Ethash, bespoke
instant engine, or auto-seal). This is a widely-used, first-class mode — **orthogonal to PoW**.

| Client | Dev / instant-seal mechanism | Wiring | Evidence |
|---|---|---|---|
| **go-ethereum** | `--dev` → **SimulatedBeacon** (post-merge); *historically* Clique instant-seal (`Period==0` → seal only when txs pending) | pre-funded developer account, `--dev.period` (0 = on-demand) | `eth/catalyst/simulated_beacon.go:87-137`; Clique-instant fossil `consensus/clique/clique.go:625` (`Period==0 && len(txs)==0` skip); `cmd/utils/flags.go:164-172` `--dev`/`--dev.period` |
| **core-geth** | `--dev` simulated-beacon + Clique-instant lineage (same geth machinery) | same as geth | `core-geth/rpc-api.md:30` (`--dev` simulated-beacon path); `clique.go:625` `Period==0` instant-seal |
| **besu** | built-in **`dev`** network = **Ethash with `fixeddifficulty: 100`** (constant tiny difficulty → near-instant CPU seal) | `NetworkDefinition.DEV`, `dev.json` genesis | `config/src/main/resources/dev.json:6-7` (`"ethash": { "fixeddifficulty": 100 }`); `FixedDifficultyCalculators.java:32-37` (`(time,parent) -> constant`); `FixedDifficultyProtocolSchedule.java` |
| **nethermind** | **`NethDev`** ("Spaceneth") instant-seal engine — a base `IConsensusPlugin` selected by `SealEngineType == NethDev`; also **AuRa** instant-seal | own `NethDevPlugin` + block-producer factory | `NethDevPlugin.cs:15-33` (`Enabled => SealEngineType==NethDev`, "Spaceneth"); `nethermind/consensus-engines.md:73-74` |
| **reth** | `--dev` **local auto-seal** — `MiningMode { Instant, Interval, Trigger }`; **Instant** = build a block as soon as a valid tx hits the pool | local engine miner (no PoW anywhere) | `crates/engine/local/src/miner.rs:26-75` (`enum MiningMode` + `instant`/`interval` ctors); `reth/consensus-engines.md:212` |

**Takeaways for fukuii.** Two viable dev-seal shapes for a PoW-family client: (a) **besu's
fixed-difficulty Ethash** — keeps the *real* PoW pipeline but with trivial difficulty, so it
also doubles as a fast conformance harness (real seal, instant block); (b) **Clique-instant /
a bespoke instant engine** (geth-historic / nethermind `NethDev`) — bypasses PoW entirely for
the fastest local loop. fukuii's private-PoA (Clique) devnet story (NET-02) already covers (b);
besu's `fixeddifficulty` is the cleaner model if the dev net must stay *PoW-shaped*. reth's
`MiningMode::Instant` (block-per-pending-tx) is the canonical "instant on demand" UX to mirror.

---

## PoW-testing consensus (faker engines)

For unit/consensus tests, clients stub the PoW check so fixtures don't burn a DAG. The
geth-lineage **faker family** is the reference shape:

| Engine | Behavior | Client status |
|---|---|---|
| `NewFaker()` / `ModeFake` | accepts every seal as valid, **but blocks still must obey all other consensus rules** | geth HEAD (**stub only** — no real PoW left), core-geth (alongside **real** PoW), erigon (`FakeEthash`) |
| `NewFakeFailer(n)` | faker that fails PoW at exactly block `n` (reorg/negative tests) | geth HEAD, core-geth |
| `NewFakeDelayer(d)` | faker that sleeps `d` before verifying (timing tests) | geth HEAD, core-geth |
| `NewFullFaker()` / `ModeFullFake` | accepts everything, **skips all consensus rules** | geth HEAD, core-geth, erigon |
| `ModeTest` | tiny in-memory DAG for real-but-cheap PoW | core-geth (`ethash.go:522`), geth-history |

Evidence: geth HEAD `consensus/ethash/ethash.go:29-70` — the faker constructors survived the
PoW purge as **header-validation stubs** (struct reduced to `fakeFail`/`fakeDelay`/`fakeFull`
flags; no `hashimoto` body). core-geth `consensus/ethash/ethash.go:522-525,625-683` — the
**full** family with `PowMode ∈ {ModeTest,ModeFake,ModeFullFake,ModeNormal}` *and* the real
sealing body intact, so it can run both faked and genuine PoW conformance. erigon
`execution/protocol/rules/ethash/fake.go:132` `FakeEthash.Seal` — the only in-tree sealer that
self-completes (real mining removed 2021). besu used a **`FixedDifficultyProtocolSchedule`** for
its dev/test PoW (constant difficulty; see dev-seal table). nethermind runs its **real**
`Ethash.Validate` for PoW vectors (`EthashSealValidator.cs` with LRU + probabilistic sampling)
and `NethDev` for instant test blocks.

**Key asymmetry:** geth's faker constructors *look* alive on HEAD but are hollow — only
**core-geth** pairs the faker family with a working PoW body, so it is the only geth-lineage
reference that can validate a *real* ETC seal in-process. fukuii's `MockedMiner.scala` is the
`NewFullFaker` analogue; a `NewFakeFailer`-style "fail-at-N" hook is the gap worth noting for
reorg/negative consensus tests.

---

## Deprecated-PoW synthesis (who had it, removed when, kept by whom) — table

Synthesized from the six `*/history-pow-etc.md` + `*/consensus-engines.md` docs (not re-derived;
SHAs confirmed there). "PoW mining" = internal block-*production* sealing; PoW *validation* of
historical headers outlives mining in every surviving client.

| Client | Had internal PoW mining? | ETC/ETChash ever? | PoW-mining removed | ETC removed | Kept today? |
|---|---|---|---|---|---|
| **core-geth** | ✅ (geth-inherited, ETC-correct) | ✅ **sole living ETC authority** (ECIP-1017/1099/1100/1111/1122) | **never** (frozen Sept-2024, retains all) | never | ✅ **full PoW + ETChash** |
| **go-ethereum** | ✅ (the dominant ETH PoW client, genesis→Merge) | ETC *forked from* it (DAO split, block 1,920,000) but geth = ETH side | **`dde2da0ef`** (2023-05-03, PR #27178) — 8 mo after the Merge | n/a (was never ETC) | ⚠️ **validation shim + faker stubs only** |
| **besu** | ✅ (Pantheon 2019 → June 2026) | ✅ **~6 yr JVM ETC** (DieHard→Spiral, ECIP-1017/1099, even ECIP-1049 Keccak) | **June 2026, 3 phases** (`8fc6805f88`/`26d3251394`/`f9e14c7d64`); Stratum earlier `c82b329935` (2025-06) | **`1167c5a544`** (#9671, 2026-02-10) | ❌ neither (mainnet header-validation defaults remain) |
| **nethermind** | ✅ (added 2022-05, `8fb6675a7`) | ❌ **never** (only `BlockchainIds` chain-ID constants 61/62; ETC plugin is a downstream fukuii-owner fork) | **not removed** — opt-in, retained | n/a | ✅ **opt-in CPU Ethash** (vanilla, no ETChash) |
| **erigon** | ✅ briefly (geth-inherited) | ❌ **never** (0 commits/chainspecs) | **local mining `8ccc6b2664`** (#1617, 2021-03); notify `eb67b7f7ff` (#17813, 2025-11); getWork *pull* still present | n/a | ⚠️ **getWork-serve + validation only**, no internal miner (E3 tried to delete PoW entirely `af4dc9d98d`, **reverted** `c7469426b1`) |
| **reth** | ❌ never | ❌ never | n/a | n/a | ❌ **Engine-API PoS only** |

**One-line reads:**
- **core-geth is the last client standing with a real, ETC-correct PoW miner** — the sole
  byte-authority. Everyone else either removed PoW mining (geth/besu/erigon) or never had ETC
  (nethermind/erigon/reth).
- **besu's ETC was real and long-lived** (~6 yr, DieHard→Spiral, ECIP-1017/1099, briefly the
  ECIP-1049 Keccak PoW-swap testnet) but is **fully deleted** (Feb 2026 ETC, June 2026 PoW) —
  a valid *structural* JVM reference, never a live authority, and its ECIP-1099 formula diverged
  from core-geth (fukuii's own overlay commit corrects it).
- **geth's removal is the archetype**: mining deleted `dde2da0ef` (2023-05), faker/validation
  shims kept for historical-header sync; a naive read of HEAD wildly under-estimates Ethash.
- **nethermind quietly kept opt-in CPU Ethash** through the PoS era — the counter-example to
  "PoS clients dropped their miner."

---

## Cross-client matrix (mode × client × status × fukuii verdict)

| Mode ↓ / Client → | core-geth | go-ethereum | besu | nethermind | erigon | reth | fukuii verdict |
|---|---|---|---|---|---|---|---|
| **Internal CPU-Ethash sealing** | ✅ real (ETC) | ❌ removed 2023 | ❌ removed 2026 | ✅ opt-in (vanilla) | ❌ removed 2021 | ❌ never | **OPTIONAL(private-PoW-testnet / conformance)** — build; core-geth = target, nethermind opt-in gate = model |
| **getWork + external GPU/pool** | ✅ (DEFAULT) | ✅ (history) | ✅→removed | ❌ never | ✅ serve-only | ❌ | **DEFAULT(ETC production)** — match core-geth (see `mining-protocol-evm.md`) |
| **Dev / instant-seal** | Clique/sim-beacon | sim-beacon / Clique-hist | Ethash `fixeddifficulty` | `NethDev` / AuRa | — (staged) | `--dev` auto-seal | **OPTIONAL(local/CI)** — besu fixed-diff (PoW-shaped) or Clique-instant (NET-02) |
| **Faker / test PoW** | ✅ full family + real | stubs only | fixed-diff schedule | real Validate + NethDev | `FakeEthash` | ❌ | **OPTIONAL(test)** — port geth faker family; add `NewFakeFailer` analogue |
| **ETC / ETChash values** | ✅ **authority** | ❌ | deleted (JVM ref) | ❌ | ❌ | ❌ | **core-geth only** |

Legend: ✅ present · ❌ absent/removed · verdict tags per the omni-client lens (DEFAULT / OPTIONAL(role) / OBSOLETE).

---

## fukuii implications (private-PoW-testnet OPTIONAL, dev-seal, PoW conformance)

1. **Internal CPU sealing is a real fukuii feature, not obsolete.** Only two reference clients
   still ship it (core-geth = ETC-correct authority; nethermind = vanilla, opt-in). fukuii's
   `EthashMiner`/`MockedMiner` + `EthashDAGManager` already provide the body; the work is
   **mode wiring + a single opt-in flag**, not new mining code. Adopt **nethermind's gate model**
   (`Mining.Enabled=false` default → one boolean on), **require an explicit coinbase** (geth's
   `StartMining` contract), and keep **getWork-only** (`threads<0`) a co-equal first-class mode.
   Primary use: **disposable CPU-sealed Ethash/ETC net for Olympia (ECIP-1111/1112/1121/1122)
   fork-activation, base-fee→Treasury, and gas-target-ramp testing without any GPU rig**.

2. **Dev / instant-seal — pick the PoW-shaped option.** For a fast local/CI loop that still
   exercises the *real* PoW pipeline, mirror **besu's `fixeddifficulty` Ethash** (constant tiny
   difficulty → instant CPU seal, real `verifySeal` path). For a pure bypass, fukuii's NET-02
   private-PoA (Clique-instant) already covers the geth-historic / nethermind-`NethDev` shape.
   reth's `MiningMode::Instant` (block per pending tx, else idle) is the UX to match for
   on-demand block production.

3. **PoW conformance testing — port the faker family.** fukuii has the `NewFullFaker` analogue
   (`MockedMiner`). Add the **`NewFakeFailer(n)`** ("fail PoW at block n") and
   **`NewFakeDelayer`** analogues for reorg/negative/timing consensus tests, and keep a
   **`ModeTest`-style tiny-DAG real-PoW** path (core-geth `ethash.go:522`) so seal-verification
   is exercised for real without a full mainnet DAG. **core-geth is the only geth-lineage
   reference whose faker sits next to a *working* PoW body** — validate against it, not geth HEAD
   (whose fakers are hollow) or besu (deleted).

4. **Authority discipline (unchanged).** For every ETC/ETChash *value* — epoch (ECIP-1099),
   emission (ECIP-1017), difficulty, seed — **core-geth is the sole authority**; besu-history is
   a structural JVM cross-check only (and its ECIP-1099 formula diverged); nethermind/erigon/reth
   contribute nothing to ETC values. See `mining-protocol-evm.md` §5 for the getWork alignment
   gap (submit-time seal verification) that pairs with the internal-sealing work here.
