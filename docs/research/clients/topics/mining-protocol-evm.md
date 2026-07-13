# Topic — node-side mining protocol (EVM clients): `eth_getWork`/`eth_submitWork` + the internal PoW miner

_Cross-cutting deep-question topic (SR Phase 1c §3e). Surveys the **node-side mining
interface** — the `getWork`/`submitWork`/`submitHashrate` RPC that external miners & pools
integrate with, plus the in-process PoW miner that produces the work — across the EVM
reference clients, weighted by **real-world miner adoption** (the mining-pool/validator
use case). Builds on the three PoW-history docs (`{go-ethereum,besu}/history-pow-etc.md`
and `core-geth/consensus-engines.md`); does not re-derive the Ethash algorithm/DAG
(covered there). Read-only w.r.t. fukuii._

**Layering (keep distinct).** This doc = **node-side getWork** (headerHash/seedHash/target
handed out over JSON-RPC + HTTP push; the node still assembles the block and validates the
submitted seal). The **pool-layer Stratum v1/v2** — the miner↔pool protocol that lives in
ethminer / pool software, NOT in an EVM node — is a **parallel survey** of the non-EVM
clients + external pool software. besu's *server-side* Stratum (an EVM-node experiment) is
noted here as the one EVM crossover point; cross-reference the Stratum topic doc for the
real pool layer.

**Refs documented (all `upstream` / archaeology anchors):**
- go-ethereum `v1.10.26` = `e5eb32acee19cc9fca6a03b10283b7484246b15a` (peak-Ethash; HEAD stripped it) — the battle-tested ETH getWork reference.
- core-geth `upstream` = `4185df450364973bbf99efa3923791f5ba40b351` (deprecated Sept-2024, the **frozen ETC byte-authority** — the interface ETC miners actually run).
- besu `upstream` HEAD `3fd233a4…`; mining read at `9ff8ead351` (parent of the June-2026 PoW-removal); Stratum removed `c82b329935` (2025-06).
- nethermind `upstream` = `0d09a09edd0a861d21c647ceaa7f9f5ea1c74255` (2026-07-01) — still ships opt-in Ethash.
- reth — no PoW/getWork at all (negative data point).
- fukuii cross-refs are read-only orientation, not a reference finding.

Documented 2026-07-13.

---

## 0. Executive verdict (the use-case lens)

| Client | Node-side getWork RPC | Internal miner | Miner adoption | Verdict for fukuii's ETC mining |
|---|---|---|---|---|
| **core-geth** | ✅ full (`eth`+`ethash` ns) | ✅ real ethash `Seal` | **Sole miner-adopted ETC client** | **DEFAULT / byte-authority** — match this |
| **go-ethereum** (pre-merge) | ✅ full (the original) | ✅ real ethash `Seal` | **THE dominant ETH PoW client** (ran the whole ETH mining ecosystem) | **Battle-tested reference** for the *interface shape* (ETH values, not ETC) |
| **besu** (history) | ✅ getWork via `PoWBlockCreator`; **+ server-side Stratum** | ✅ `PoWMiningCoordinator`→`PoWSolver` | ETC config maintained 6 yrs but **never PoW-miner-adopted**; Stratum never adopted | **OPTIONAL / cautionary** — JVM structural mirror, cross-validate vs core-geth; Stratum = optional server mode |
| **nethermind** | ❌ **no getWork at all** | ✅ opt-in `EthashSealer` (in-process CPU only) | dev/testnet mining; never shipped ETC | **OPTIONAL(dev)** — internal CPU-mine only, no pool interface |
| **reth** | ❌ none | ❌ none | never had PoW | N/A (negative data point) |

**Bottom line:** the node-side getWork interface is a **geth-lineage design** (geth authored
it; core-geth inherited it verbatim and made it ETC-correct). fukuii's ETC PoW mining must
match **core-geth** (the miner-adopted ETC interface). fukuii **already does** — its
`EthMiningService` + `WorkNotifier` are a faithful port of core-geth `sealer.go`; §5 flags
the one genuine gap (submit-time PoW verification).

**Two distinct PoW node roles — do not conflate them** (operator emphasis):
1. **Production path (DEFAULT)** — getWork RPC + HTTP notify + **external GPU rigs/pools** own
   the hashpower and the full DAG. The node serves work, validates seals. §1–§2.
2. **Internal CPU sealing (OPTIONAL — private testnet/dev)** — the node's *own* built-in
   Ethash miner grinds nonces on CPU via `hashimotoFull`, no external miner. Slow for mainnet,
   but ideal for a **private PoW testnet/devnet** — the PoW companion to a Clique private-PoA
   net, and the specific fit for **fukuii's Olympia upgrade testing** (spin up a private
   Ethash/ETC-flavored net that seals on CPU with zero GPU rigs). §3.5.

---

## 1. `eth_getWork` / `eth_submitWork` / `eth_submitHashrate` — the pool/miner-facing RPC

### 1a. go-ethereum — the battle-tested original (`v1.10.26`)

geth was THE ETH PoW-mining client; every ETH pool + rig integrated against its getWork.
The surface is four RPC methods backed by a **`remoteSealer` goroutine** (a channel-fronted
state machine), not direct method calls:

- `e5eb32a:consensus/ethash/api.go:41` `GetWork()` → `[4]string` = `{headerPowHash, seedHash,
  target, blockNumber}` (see 1c for the format). `:66` `SubmitWork(nonce, hash, digest) bool`.
  `:92` `SubmitHashrate(rate, id) bool`. `:110` `GetHashrate()`.
- Exposed under **two namespaces** for compatibility — `eth_getWork` **and** `ethash_getWork`
  (`e5eb32a:consensus/ethash/ethash.go` `APIs()` returns both `Namespace:"eth"` and
  `"ethash"`).
- `makeWork` seed: `e5eb32a:consensus/ethash/sealer.go:349` — `SeedHash(block.NumberU64())`,
  a **single-arg**, 30000-epoch-grid derivation (ETH has no ECIP-1099).

### 1b. core-geth — the ETC byte-authority (`upstream` `4185df450`)

core-geth is a geth fork; the `remoteSealer`/`api.go` machinery is **structurally identical**
to geth's, with the ETC-correct seed derivation threaded through. This is the interface ETC
miners run today.

- `4185df450:consensus/ethash/api.go:42` `GetWork() ([4]string, error)` — identical doc-comment
  format to geth; routes through `api.ethash.remote.fetchWorkCh` (`:52`), returns
  `errNoMiningWork` if no block pending, `errEthashStopped` on shutdown.
- `4185df450:consensus/ethash/api.go:67` `SubmitWork(nonce types.BlockNonce, hash, digest
  common.Hash) bool` — pushes a `mineResult` onto `submitWorkCh`; **returns `false` for an
  invalid solution, stale work, OR non-existent work** (doc-comment `:65-66`).
- `4185df450:consensus/ethash/api.go:93` `SubmitHashrate` — combined-rate reporting; `:111`
  `GetHashrate()`.
- Namespaces: `4185df450:consensus/ethash/ethash.go:823-834` `APIs()` — same **`eth` +
  `ethash` dual namespace**.
- **The ETC delta is in `makeWork`** (`4185df450:consensus/ethash/sealer.go:393-405`):
  ```
  epochLength := calcEpochLength(block.NumberU64(), s.ethash.config.ECIP1099Block)  // 30000→60000
  epoch       := calcEpoch(block.NumberU64(), epochLength)
  currentWork[0] = SealHash(header).Hex()                                  // pow-hash (no nonce/mixdigest)
  currentWork[1] = SeedHash(epoch, epochLength).Hex()                      // ECIP-1099-AWARE seed
  currentWork[2] = (2^256 / block.Difficulty()).Hex()                      // target/boundary
  currentWork[3] = EncodeBig(block.Number())
  ```
  **`SeedHash(epoch, epochLength)` is two-arg** (`ethash.go:838`) vs geth's single-arg
  `SeedHash(block)` — the ECIP-1099 "ETChash" epoch-doubling changes which DAG epoch (and
  therefore which seedHash) the external miner selects after block 11,700,000 (mainnet) /
  2,520,000 (Mordor). **A reimplementation that hard-codes the 30000 grid produces the wrong
  seedHash post-ECIP-1099 and every rig mines a useless DAG.** (Subtlety: the seedHash *chain*
  is still iterated on the 30000 default grid internally — see `core-geth/consensus-engines.md`
  gotcha; only the epoch *index* handed out shifts.)

### 1c. The work-package format (byte-authoritative — both geth & core-geth)

The `[4]string` returned by `eth_getWork` (hex-encoded):

| Index | Field | Value | Notes |
|---|---|---|---|
| `[0]` | header pow-hash | `SealHash(header)` = keccak of the RLP header **without** `nonce`+`mixDigest` | what the rig hashes against |
| `[1]` | seed hash | `SeedHash(epoch[, epochLength])` | epoch selector for the DAG; **ETC = ECIP-1099-aware** |
| `[2]` | target / boundary | `2²⁵⁶ / difficulty` (big-endian) | solution must be `≤` this |
| `[3]` | block number | `hexutil.EncodeBig(number)` | |

### 1d. `submitWork` validation — the accept/reject gate (core-geth `sealer.go:452-499`)

`eth_submitWork` does **not** just enqueue — the `remoteSealer.submitWork` runs a full check
before accepting, and **this is the miner-feedback contract** fukuii must weigh against:

1. `s.currentBlock == nil` → error "Pending work without block" → `false` (`:453-456`).
2. **Look up the pending block by sealhash** `block := s.works[sealhash]`; nil → "Work
   submitted but none pending" → `false` (`:458-462`). (The `works` map is the node's memory
   of every handed-out work package, pruned by staleThreshold — `sealer.go:373`.)
3. Stamp `header.Nonce = nonce; header.MixDigest = mixDigest` (`:465-467`).
4. **Re-verify the PoW inline** (unless `--noverify`): `s.ethash.verifySeal(nil, header,
   true)` — recomputes `hashimotoFull`, checks `MixDigest` match + `result ≤ target`; fail →
   "Invalid proof-of-work submitted" → `false` (`:469-473`). **Immediate rejection of a bad
   nonce, before the block touches the import pipeline.**
5. **Stale check:** `solution.NumberU64() + staleThreshold(=7) > s.currentBlock.NumberU64()`
   → deliver on `s.results`; else "Work submitted is too old" → `false` (`:486-498`).

`remoteSealer.loop` (`4185df450:consensus/ethash/sealer.go:313-383`) is the single-goroutine
serializer: `workCh` (new work from the miner → `makeWork` + `notifyWork`), `fetchWorkCh`
(getWork), `submitWorkCh` (submitWork), `submitRateCh`/`fetchRateCh` (hashrate), and a 5-sec
ticker that GC's stale `works` entries + stale hashrate reports.

### 1e. HTTP push notification (`notifyWork`) — the pool-push side of getWork

Beyond pull-based getWork, the node **pushes** new work to configured miner URLs:
`4185df450:consensus/ethash/sealer.go:409-447`. `notifyWork` POSTs the `[4]string` JSON array
(or the full header JSON when `NotifyFull` is set) to each `--miner.notify` URL, 1-sec
timeout, fire-and-forget per URL. This is the node-side equivalent of a pool pushing new jobs
— the seam where a getWork node meets a stratum proxy.

---

## 2. The internal miner / PoW block production

The node must **produce** the work before it can hand it out. Two-stage in geth-lineage: the
`miner/worker` assembles a candidate block → hands it to `engine.Seal` → the ethash engine
either grinds locally (CPU) or parks it in the `remoteSealer` for external rigs.

### 2a. geth/core-geth — `miner/worker` → `Seal` handoff

- `4185df450:miner/worker.go:717-742` — the **seal task loop**: on a new `task`, compute
  `SealHash(header)`, dedupe against the previous, `interrupt()` the prior seal, stash the
  task in `pendingTasks[sealHash]` (so `submitWork` can find it), then
  `w.engine.Seal(w.chain, task.block, w.resultCh, stopCh)`. `resultLoop` (`:751+`) receives
  the sealed block and commits it.
- `4185df450:consensus/ethash/sealer.go:68-176` — **`Seal`**: fake modes short-circuit;
  otherwise (a) **if `ethash.remote != nil`, push `&sealTask{block, results}` onto
  `remote.workCh`** (`:135-136`) — this is what makes the block available to external miners
  — **and** (b) spin up `threads` local `mine` goroutines (`threads==0` → `NumCPU`;
  `threads<0` → disable local mining, remote-only). Both paths share the same `results`
  channel; first solver wins.
- `mine` (`sealer.go:178+`) is the local nonce grind (`hashimotoFull` over the mmap'd DAG);
  documented in `go-ethereum/history-pow-etc.md §1a`. `runtime.KeepAlive(dataset)` guards the
  mmap mid-hash.

### 2b. DAG readiness / scheduling

The remote sealer hands out `seedHash` so the rig builds its own DAG; the **node** only needs
the light cache for `verifySeal`. Local mining needs the full DAG — geth's `lru` +
`sync.Once` + async next-epoch pregeneration (`ethash.go:182,596`, see
`go-ethereum/history-pow-etc.md §1d`) ensures an epoch boundary doesn't stall sealing. For a
**getWork-only node** (`--miner.threads=0`), no full DAG is needed on the node at all — the
rigs own it. This is the common ETC pool topology: node serves getWork, rigs+pool own DAG and
hashpower.

### 2c. `threads<0` = "remote-only mining"

A load-bearing detail for the pool use case: `sealer.go:64` sets `threads=0` (no local
goroutines) when configured negative, "Allows disabling local mining without extra logic
around local/remote." The node still runs the `remoteSealer`, still serves getWork/notify —
it just doesn't burn CPU. **This is the mode a pool operator runs.**

---

## 3. besu server-side Stratum + nethermind opt-in Ethash (the EVM crossovers)

### 3a. besu — getWork via `PoWBlockCreator` **+ a server-side Stratum** (history)

besu's mining (read at `9ff8ead351`, pre-June-2026 removal; full detail in
`besu/history-pow-etc.md §1`) used a **mechanism-generic actor chain**
`PoWMiningCoordinator → PoWMinerExecutor → PoWBlockCreator → PoWSolver → PoWHasher`, where the
hasher is a **field on the fork's `ProtocolSpec`** (`getPoWHasher()`), read per-block.

- **getWork surface:** `9ff8ead351:…/blockcreation/PoWBlockCreator.java:91` `getWorkDefinition()`
  (→ `eth_getWork`), `:99` `submitWork(PoWSolution)` (→ `eth_submitWork`), `:95`
  `hashesPerSecond()`. Same conceptual surface as geth, OO-structured.
- **Server-side Stratum (the EVM crossover):** besu shipped an in-node **Stratum server**
  (`Stratum1Protocol`, `Stratum1EthProxyProtocol`, `StratumServer`, `StratumConnection`,
  `PoWObserver`) so external rigs could Stratum-connect **directly to the besu node** — no
  separate pool. **Removed 2025-06-12 (`c82b329935`, #8802)**, a year *before* the mining loop
  itself. ⚠ **Never miner-adopted** — a besu-specific experiment, not the battle-tested pool
  path. **Cautionary, not a reference to copy.** (The real Stratum authority is pool software
  / non-EVM clients — see the parallel Stratum topic doc.)
- After Stratum's removal, only the `eth_getWork`/`eth_submitWork` RPC path (via
  `PoWBlockCreator`) remained for external miners — converging besu onto the geth-lineage
  getWork shape.

### 3b. nethermind — opt-in **internal** Ethash, **no getWork** (`upstream` HEAD)

nethermind still ships an Ethash consensus plugin, but it is **internal CPU sealing only**:

- `upstream:…/Nethermind.Consensus.Ethash/EthashSealer.cs:27` `SealBlock(Block, ct)` →
  `MineAsync` → `Mine` → `_ethash.Mine(block.Header, startNonce)` (`:73`) — an in-process
  `ISealer` that grinds the nonce on the node itself.
- `MinedBlockProducer.cs` drives it; `EthashPlugin.cs:23` self-declares `Enabled` when
  `chainSpec.SealEngineType == Ethash`; wiring is opt-in via `miningConfig.Enabled`.
- **No `eth_getWork`/`eth_submitWork` anywhere** (`git grep -i eth_getwork upstream` = empty).
  nethermind cannot serve external rigs — it is a **self-mining dev/testnet node**, not a
  pool-facing node. Never shipped ETC.

### 3c. reth — negative data point

No ethash/PoW crate, no getWork, no miner. reth is Engine-API-driven PoS only. Confirms that
aligning fukuii's ETC mining to reth (or current geth HEAD, which panics
`"ethash (pow) sealing not supported any more"`) would be a **regression** — the node-side
mining interface is a *history* reference, alive only in core-geth `upstream`.

---

## 3.5. Internal CPU sealing — the private-PoW-testnet / dev use-case

A **distinct node role** from §1–§2's getWork-plus-external-GPU production path: the node
seals its **own** blocks on CPU via `hashimotoFull` over the full DAG, with **no external
miner or pool**. Too slow to compete on mainnet, but perfectly functional — and the natural
way to run a **private Ethash/ETC-flavored net** (the PoW analogue of a Clique private-PoA
devnet). Every geth-lineage client shares one binary for both roles; the difference is purely
config.

| Client | Internal CPU-sealing-only? | Enablement | DAG on CPU |
|---|---|---|---|
| **go-ethereum** / **core-geth** | ✅ yes (same binary as getWork) | `--mine` + `--miner.threads=N` (N>0) + `--miner.etherbase=0x…` (**required**, else `StartMining` errors "Cannot start mining without etherbase") | full DAG built via `lru`+`sync.Once`+mmap, async next-epoch pregen (`ethash.go:182,596`) — node owns it |
| **nethermind** | ✅ yes — **internal-only** (no getWork at all) | `Mining.Enabled=true` (config, default `false`) + a signer/coinbase; **this opt-in flag is the clean model** | `EthashSealer.Mine`→`_ethash.Mine` builds/uses the CPU dataset in-process |
| **besu** (history) | ✅ yes | mining coordinator + `setCoinbase(Address)`; `--miner-enabled` | `EthHashCacheFactory`/`EthHash` cache+dataset on the node |
| **reth** | ❌ no PoW path | — | — |

**Enablement model (geth/core-geth — the canonical shape):**
- `--mine` (`MiningEnabledFlag`, `cmd/utils/flags.go:581-582`) turns block production on.
- `--miner.threads=N` (`:587`) sets CPU sealing goroutines. **N>0 = internal CPU sealing.**
  `N=0`/negative → `Seal` runs remote-only (getWork), no CPU grind (`sealer.go:64`) — the two
  roles are literally one flag apart.
- `--miner.etherbase` (`:614`) is **mandatory** for internal mining — `StartMining`
  (`eth/backend.go:467+`) refuses to start without it ("etherbase must be explicitly
  specified", `:401`). The reward/coinbase address.
- Note: even with `--miner.threads>0`, the `remoteSealer` still runs, so a CPU-mining node
  *also* serves getWork. Pure "internal only" just means no rig ever calls it.

**nethermind is the cleanest opt-in gate:** `IMiningConfig.Enabled` ("Whether to produce
blocks", default `false`) — a single boolean, no getWork surface to worry about. This is the
**model for fukuii's optional internal-mining mode**: default off, one flag on, self-mines on
CPU.

**Verdict: internal CPU sealing = OPTIONAL(private-PoW-testnet / dev).** Not the DEFAULT
production path (that's getWork + external GPU + pool, §4). Genuinely valuable for:
- **fukuii Olympia upgrade testing** — a private CPU-sealed Ethash/ETC net to exercise
  ECIP-1111/1112/1121/1122 fork activation, base-fee→Treasury routing, and gas-target ramp
  **without any GPU rig or pool**. Fast to spin up, deterministic-ish, disposable.
- Local integration/hive-style testing of the full PoW block-production pipeline.
- The PoW companion to fukuii's private-PoA (Clique) devnet story (NET-02).

DAG-readiness caveat on CPU: internal sealing needs the **full DAG on the node** (unlike a
getWork-only node, where rigs own it). For a private testnet at low block numbers the DAG is
small (epoch 0), so CPU sealing starts instantly; the `lru`+async-pregen machinery matters
only if the test net crosses an epoch boundary.

## 4. Adoption verdict per client (the use-case lens)

- **core-geth = DEFAULT / byte-authority.** The only client ETC miners+pools actually run;
  the getWork format, dual namespace, ECIP-1099-aware seedHash, and submit-validation
  (verifySeal + staleThreshold=7) are the ETC mining contract. Match byte-for-byte.
- **go-ethereum (pre-merge) = battle-tested interface reference.** Authored the whole
  getWork/remoteSealer/notify design; proven at ETH-network scale. Use for the *shape* and
  edge cases (stale handling, dual namespace, threads<0 remote-only) — but its **values are
  ETH** (single-arg `SeedHash`, no ECIP-1099). HEAD deleted it → history only.
- **besu = OPTIONAL / cautionary (JVM structural mirror).** Its ProtocolSpec-field hasher +
  mechanism-generic mining actors are the closest JVM structure to fukuii; useful for *how to
  structure* an OO miner. But **never miner-adopted**, ECIP-1099 diverged from core-geth (its
  own overlay commit "correct ECIP-1099 epoch formula to match core-geth" proves it), and its
  **Stratum server was never adopted** → cross-validate against core-geth, treat Stratum as an
  optional-mode idea, not a proven path.
- **nethermind = OPTIONAL(dev).** Opt-in internal CPU mining, no external-miner interface →
  only good for a self-mining dev/testnet node, never a pool. Interesting for a fukuii
  "internal CPU-mine" mode; irrelevant to the pool use case.
- **reth = OBSOLETE-for-PoW / N/A.** No PoW path.

---

## 5. fukuii target (default + optional, per the omni-client lens)

**fukuii already targets core-geth's node-side interface — and does so faithfully.** Orientation
(read-only) of `EthMiningService.scala` + `WorkNotifier.scala` + `consensus/pow/miners/`:

- **getWork** (`jsonrpc/EthMiningService.scala:118-153`): assembles a candidate via
  `blockGenerator.generateBlock`, then returns
  `GetWorkResponse(powHeaderHash, dagSeed, target, blockNumber)` where
  - `powHeaderHash = kec256(BlockHeader.getEncodedWithoutNonce(header))` — matches core-geth
    `SealHash` (RLP without nonce/mixDigest),
  - **`dagSeed = EthashUtils.seed(number, ecip1099BlockNumber)`** — **ECIP-1099-aware, matches
    core-geth's two-arg `SeedHash`** ✅ (the byte-critical ETC detail is correct),
  - `target = 2²⁵⁶ / difficulty`, `blockNumber` — all matching the §1c format.
- **HTTP push** (`consensus/pow/WorkNotifier.scala`): explicitly documented as mirroring
  core-geth `sealer.go notifyWork()`; POSTs the `["0xsealhash","0xseed","0xtarget","0xnum"]`
  JSON array to `notifyUrls`. ✅ Matches §1e (array form; **does not** yet implement geth's
  `NotifyFull` full-header variant — minor, optional).
- **submitWork** (`EthMiningService.scala:155-185`): looks up the prepared block by
  `powHeaderHash` (`blockGenerator.getPrepared` = core-geth's `s.works[sealhash]`), applies the
  **staleThreshold check citing core-geth `sealer.go`** ✅, stamps nonce+mixHash, routes a
  `SyncProtocol.MinedBlock` to the sync controller.
- **Internal miner:** `consensus/pow/miners/{EthashMiner,MockedMiner,Miner}.scala` +
  `EthashDAGManager.scala` — a real Scala Ethash miner + DAG manager (the local-grind path,
  analogous to geth's `mine`).

**The one genuine alignment gap (flag for Phase 4, forge-owned):**
> fukuii's `submitWork` returns `SubmitWorkResponse(true)` as soon as it forwards the block to
> the sync controller — it **does not re-verify the PoW seal inline** the way core-geth's
> `submitWork` does (`sealer.go:469-473`, `verifySeal(header, true)` before accepting). A rig
> that submits an **invalid nonce** gets `true` back from fukuii, then the block is silently
> dropped downstream in block import. core-geth returns `false` immediately, giving the miner
> correct, fast rejection feedback (the doc-contract "either an invalid solution, a stale
> work[,] a non-existent work will return false"). This is a **miner-facing behavioral
> divergence** from the byte-authority — not a state-root/consensus issue (the invalid block
> can't be imported either way), but a getWork-contract correctness gap. **Recommend: fukuii
> `submitWork` should run the Ethash seal verification before returning `true`, matching
> core-geth's accept/reject semantics.** (Verify against `EthashUtils`/`EthashEngine` for the
> existing `verifySeal` primitive before wiring.)

**Default + optional menu for fukuii (omni-client):**
- **DEFAULT** — core-geth-shaped getWork RPC (`eth_getWork`/`eth_submitWork`/
  `eth_submitHashrate`, dual `eth`+`ethash` namespace worth adding if only `eth` exists) +
  HTTP `notifyUrls` push + ECIP-1099-aware seedHash + submit-time seal verification +
  staleThreshold=7. This is the pool/miner-adopted ETC contract. **Match core-geth.**
- **OPTIONAL(pool-direct)** — a server-side Stratum endpoint on the node (besu's removed
  idea): lets rigs connect without separate pool software. Never battle-tested in an EVM node;
  offer as an explicit opt-in mode, cross-validated against the pool-layer Stratum topic doc,
  not as the default.
- **OPTIONAL(private-PoW-testnet / dev)** — **internal CPU sealing** (§3.5): the node mines
  its own blocks on CPU via the Ethash miner, **no external rig/pool**. The PoW companion to
  fukuii's private-PoA (Clique) devnet, and specifically the vehicle for **Olympia upgrade
  testing** — a disposable CPU-sealed Ethash/ETC net exercising ECIP-1111/1112/1121/1122
  without GPU hardware. **Gate it behind a single opt-in flag (nethermind's
  `Mining.Enabled=false`-default model), require an explicit coinbase/etherbase (geth's
  `StartMining` contract), and keep getWork-only (`threads<0`, pool node) as a co-equal
  first-class mode** (geth `sealer.go:64`). fukuii's `EthashMiner`/`MockedMiner` +
  `EthashDAGManager` already provide the internal-sealing machinery; the work is the mode
  wiring + flag, not new mining code.

---

## 6. Layering & cross-references

- **This doc (node-side getWork):** the node assembles the block, hands out
  headerHash/seedHash/target, validates the submitted seal. Battle-tested in geth (ETH) +
  core-geth (ETC). fukuii's alignment target = **core-geth**.
- **Pool-layer Stratum v1/v2 (parallel survey):** the miner↔pool wire protocol
  (`mining.subscribe`/`mining.notify`/`mining.submit`, difficulty vardiff, job distribution) —
  lives in ethminer/pool software + the non-EVM clients, **not** in a geth-lineage EVM node.
  besu's removed server-side Stratum (§3a) is the sole EVM crossover and was never adopted.
  → see the Stratum topic doc for the real pool layer; treat getWork (here) and Stratum
  (there) as two distinct integration surfaces a pool bridges.
- **Consensus/DAG/Ethash algorithm:** not re-derived here — see
  `go-ethereum/history-pow-etc.md`, `besu/history-pow-etc.md`, and
  `core-geth/consensus-engines.md` (ECIP-1099 epoch math, difficulty, rewards).
- **Phase-2 fold-in:** the getWork-interface rows belong in a future
  `observations/rpc-api.md` (mining-method coverage) and the mining verdict in
  `observations/consensus-engines.md` (block-production seam). Do not act on the §5 gap here —
  it is a Phase-4 seed, forge-owned (submit-time seal verification is state-adjacent but the
  fix is RPC-contract, not consensus-rule).
