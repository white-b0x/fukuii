# nethermind — history: PoW / Ethash structure & the Ethereum Classic question

_Anchor: upstream `0d09a09edd0a861d21c647ceaa7f9f5ea1c74255` (branch `upstream`, HEAD
`0d09a09ed`, tip commit `40bf00e0b` on that branch's second-newest). Full vendored clone,
**11,010 commits, 2017→2026**, at `.claude/repo-references/clients/nethermind`. Git-log
archaeology (read-only). Documented 2026-07-13. No fukuii source touched._

> **Method note / branch caveat (read first).** This clone carries **two** branches:
> `upstream` (genuine upstream nethermind, the anchor above) and a **downstream `origin/main`**
> that is `+24 / −139` relative to `upstream` and contains a local, **chris-mercer-authored**
> Ethereum-Classic plugin (`Nethermind.Consensus.Classic`). **All ETC-support findings below
> distinguish the two.** "Did nethermind support ETC?" is answered strictly against the
> **upstream** project, not the downstream fork. Where a search used `--all`, that is flagged.

---

## Part 1 — The Ethash consensus / mining structure (upstream)

### 1.1 Architecture summary

Upstream nethermind ships Ethash as **one self-contained consensus plugin assembly**,
`Nethermind.Consensus.Ethash` (17 source files, 1,270 LOC), selected — like every other
consensus mechanism — by the flat `ChainSpec.SealEngineType == "Ethash"` string tag (see the
sibling `consensus-engines.md` for the plugin-selection machinery). It contains a **complete,
standalone CPU implementation of the Ethereum Ethash PoW algorithm** — DAG/cache generation,
Hashimoto, difficulty, seal validation, **and a nonce-searching miner/sealer**. It is actively
maintained: the directory spans **2020-02-22 → 2026-06-25** (last touch
`f4592f3ea:Nethermind.Consensus.Ethash` "Unify types with Geth" (#11937), Jun 2026), with
119 commits touching it.

### 1.2 Key types / files (all `HEAD = upstream 0d09a09ed`)

**The algorithm — `Ethash.cs` (306 LOC), the load-bearing PoW core:**
- `src/Nethermind/Nethermind.Consensus.Ethash/Ethash.cs:37-48` — the canonical Ethash constants
  hard-coded as `const`/`static`: `EpochLength = 30000`, `CacheMultiplier = 1024`,
  `DataSetBytesInit = 1<<30`, `MixBytes = 128`, `Accesses = 64`, etc. **`EpochLength` is a
  compile-time `const ulong`, not chainspec-configurable** — load-bearing for the ETC question
  in Part 3.
- `Ethash.cs:149-194` — **`(Hash256 MixHash, ulong Nonce) Mine(BlockHeader, ulong? startNonce)`**
  — a real nonce search loop calling `Hashimoto` until the target is met. This is mining, not
  a stub.
- `Ethash.cs:200-227` — **`bool Validate(BlockHeader)`** — full PoW seal check (rebuilds the
  epoch dataset via the hint cache, runs `Hashimoto`, compares `MixHash`).
- `Ethash.cs:258-305` — **`static (byte[], ValueHash256, bool) Hashimoto(...)`** — the mixing
  function shared by mine + validate.
- `Ethash.cs:120-146` — `GetSeedHash(epoch)`; `:229-256` — `BuildCache(epoch)` builds the
  light cache.

**DAG / cache (the memory-hardness data structures):**
- `IEthashDataSet.cs`, `EthashCache.cs` (128 LOC — the light cache), `FullDataSet.cs` (27 LOC —
  the full DAG), `HintBasedCache.cs` (134 LOC — LRU of which epoch datasets to keep resident,
  driven by `IEthash.HintRange`). `IEthash.cs:11-15` is the 3-method seam:
  `HintRange` / `Validate` / `Mine`.

**Sealer (mining) — `EthashSealer.cs` (79 LOC):**
- `EthashSealer.cs:14` — **`internal class EthashSealer : ISealer`**; `SealBlock(Block, ct)`
  (`:27-40`) awaits `MineAsync`, i.e. it actually mines the produced block. Wired to a
  `MinedBlockProducer` (39 LOC) via `EthashBlockProducerFactory` (16 LOC).

**Seal validation — `EthashSealValidator.cs` (114 LOC):**
- `EthashSealValidator.cs:18` — `public class EthashSealValidator : ISealValidator`, with a
  `LruCache<ValueHash256,bool>` seal cache (`:26`) and probabilistic sampling
  (`SealValidationIntervalConstantComponent = 1024`, `:27`) so not every historical header is
  re-hashed during sync.

**Difficulty — `EthashDifficultyCalculator.cs` (94 LOC):**
- `EthashDifficultyCalculator.cs:17` — `internal class EthashDifficultyCalculator(ISpecProvider)
  : IDifficultyCalculator`; `InitialDifficultyBombBlock = 200000` (`:19`), Homestead/Byzantium
  bomb + `FixedDifficulty` short-circuit for post-merge (`spec.FixedDifficulty` read from the
  release spec, `:39-41`).

**Plugin wiring — `EthashPlugin.cs` (55 LOC):**
- `EthashPlugin.cs:15` — `EthashPlugin(ChainSpec, IMiningConfig) : IConsensusPlugin`,
  `Enabled => chainSpec.SealEngineType == SealEngineType` (`:23`), `SealEngineType => Ethash`
  (`:27`).
- `EthashPlugin.cs:33-53` — `EthHashModule` `AddSingleton`s `IRewardCalculatorSource` (generic
  `RewardCalculator`), `IDifficultyCalculator`, `IEthash`, `ISealValidator`, and the block
  producer factory — **but adds `ISealer` (the miner) only `if (miningConfig.Enabled)`
  (`:49-52`)**. Validation is always wired; **mining is opt-in**.

### 1.3 Was Ethash *mining* ever supported, and is it still? — YES to both

- Mining was added deliberately in **`8fb6675a7` "Ethash mining (#4034)", 2022-05-19** — roughly
  **three months before the ETH Merge** (Sept 2022). So nethermind gained a working PoW miner
  right as ETH mainnet was leaving PoW; its ongoing purpose is **private/dev PoW networks,
  `hive`/consensus test vectors, and pre-merge header validation during full sync from genesis**,
  not mainnet mining.
- It is **still present at the anchor** — no removal/deprecation commit exists in the Ethash
  dir history (the "Remove…" commits there are unrelated: `2076b2420` witness protocol,
  `90660ef0e` GC finalization, usings cleanups). The merge did not delete Ethash; `MergePlugin`
  **composes over** it (base seal engine ∈ {BeaconChain, Clique, **Ethash**} — see
  `consensus-engines.md`), and Ethash validation is still needed to verify pre-merge history.
- `MiningConfig` was later split from a separate `BlockConfig` to "reduce PoS confusion"
  (`ecabb23b2` #4847) — evidence they consciously kept PoW mining alive alongside PoS.

---

## Part 2 — The Ethereum Classic question (the high-value finding)

### 2.1 Bottom line

**Upstream nethermind never shipped Ethereum Classic support.** At the anchor there is **zero
ETChash, zero ECIP handling, and no classic/mordor/kotti chainspec** anywhere in the tree:

- `git grep -l -i etchash HEAD` → **0 files**.
- `git ls-tree -r HEAD | grep -i 'classic.json\|mordor.json'` → **0 files** (the only embedded
  chainspec under test resources is `Nethermind.Blockchain.Test/chainspec.json`).
- History searches on the upstream branch: `git log -S"etchash" -i` → **no commits**;
  `git log -S"ECIP"` matches only diffs where "ECIP" appears incidentally (EIP-reference comments),
  **no ECIP-1017/1099/1100/1111 implementation**; `-S"mordor"` / `-S"kotti"` → only false
  positives (`6fb32d2a1` "Account optimizations", `66e72f4a5` xDai→Gnosis rename, a
  `daafb4973` hive-chainspec-loading WIP), never a runnable ETC network.

### 2.2 What DOES exist upstream — vestigial chain-ID constants only

`Nethermind.Core/BlockchainIds.cs` carries **name/ID registry constants** for ETC, and nothing
more:
- `BlockchainIds.cs:24-26` — `Morden = 2` (doc-commented "Morden Classic, the public Ethereum
  Classic PoW testnet"), `EthereumClassicMainnet = 61`, `EthereumClassicTestnet = 62`.
- These constants are **referenced only in RLPx handshake unit tests** as arbitrary chain-ID
  values for EIP-155 replay-protection fixtures —
  `HEAD:src/Nethermind/Nethermind.Network.Test/Rlpx/Handshake/AuthEip8MessageSerializerTests.cs:46-47`
  and `AuthMessageSerializerTests.cs:49-50`. There is **no chainspec, no consensus path, no
  discovery bootnode set** behind them.
- They date to a chain-ID/network-ID cleanup, not an ETC feature:
  **`fd34651a7` "Correct usage of chain ID and network ID (#4850)"** formalized them; the raw
  strings trace back to the original 2018-era .NET Core port (`18bb2e752` "everything .NET Core",
  `bdc3f9843` "eip155"). They are **a chain-ID lookup table, not ETC support.**

**Conclusion for the authority model: nethermind is NOT a second live ETC reference.** The
broad-chain reputation is real for **ETH-family + L2 + PoA** (Gnosis, Optimism, Taiko, xDC,
Clique, AuRa — see `consensus-engines.md`) but **does not extend to Ethereum Classic**. core-geth
remains the sole ETC/ETChash/ECIP authority.

### 2.3 The downstream fork — flag, don't credit

The vendored clone's **`origin/main` branch** (NOT upstream) contains a from-scratch ETC plugin,
surfaced only via `git log --all`:
- **`ad7d2a8a3` "feat(etc): add Ethereum Classic plugin, chainspecs, and runner integration"**,
  authored **Christopher Mercer, 2026-05-25, Co-Authored-By Claude Sonnet 4.6** — i.e. **this
  repo owner's own experimental work ("Nethermind/Olympia"), not upstream nethermind.**
- It adds a whole new assembly `Nethermind.Consensus.Classic` (+ `.Test`) and
  `Chains/classic.json` / `Chains/mordor.json`: `EtchashChainSpecEngineParameters`, `EtcBlockTree`,
  `EtcRewardCalculator`, `DifficultyBombCalculator`, `Ecip1017Calculator`,
  `EtchashDifficultyCalculator`, `EtchashEpochCalculator` (ECIP-1099), MESS anti-oscillation
  (`MessCalculator`), Olympia (ECIP-1111/1112/1121) fields, `EtcMiningConfig`,
  `LocalEtchashSealer`/`RemoteEtchashSealer`, port 30307.
- `git log upstream..origin/main -- Nethermind.Consensus.Classic` → **12 commits, all authored
  "Christopher Mercer"** (`origin/main` is `+24` over `upstream` total). **This must not be read
  as evidence that nethermind supported ETC** — it is a fukuii-adjacent proof-of-concept living
  in a fork branch. It is, however, useful as a worked example of *how* one would bolt ETC onto
  nethermind's plugin model (see Part 3).

---

## Part 3 — How ETC fits nethermind's plugin / SealEngineType / chainspec model

**Would ETC be "just a chainspec," or does it need engine changes? → It needs a NEW engine
plugin; a chainspec alone is insufficient.** The stock `Ethash` plugin's chainspec surface,
`EthashChainSpecEngineParameters.cs`, exposes only ETH-shaped knobs and cannot express ETC's
consensus rules:

- **Block reward — not expressible.** `EthashChainSpecEngineParameters.cs:29-30` types
  `BlockReward` as a `SortedDictionary<ulong, UInt256>` — a **finite step function keyed by block
  number** (fine for ETH's 5→3→2 ETH cliffs). The generic `RewardCalculator.GetBlockReward`
  (`Nethermind.Consensus/Rewards/RewardCalculator.cs:16-19`) just reads the single active
  `spec.BlockReward`. **ECIP-1017's continuous 20%-per-5M-block-era emission is not a static step
  table** → the fork needed a bespoke `Ecip1017Calculator` + `EtcRewardCalculator`.
- **DAG epoch — not configurable.** `Ethash.EpochLength` is a hard `const 30000`
  (`Ethash.cs:43`). **ECIP-1099 (Thanos) halves ETChash's epoch to 60000** → not reachable from
  chainspec; the fork added `EtchashEpochCalculator` / `EtchashDifficultyCalculator` in a new
  assembly.
- **Difficulty bomb — wrong shape.** The stock params model bomb *delays* via
  `DifficultyBombDelays` and ETH glacier fields (`ApplyToChainSpec` even hard-maps
  `MuirGlacier`/`ArrowGlacier`/`GrayGlacier` by dictionary index, `:97-101`). **ETC removed the
  bomb (ECIP-1041)** and has its own defusal history → different calculator.
- **Subjective finality — no seam.** ETC's **MESS (ECIP-1100)** anti-51%-reorg scoring has no
  analogue in the Ethash plugin → the fork added a `MessCalculator` + `EtcBlockTree`.

Mechanically, nethermind's architecture makes this a **clean add** *because* it is plugin-based:
ETC ships as its **own `IConsensusPlugin` assembly** claiming a new `SealEngineType` tag, with its
own `IChainSpecEngineParameters`, and — thanks to reflection discovery + the embedded-plugin list —
needs **no edit to shared dispatch code** (only a project reference + a line in
`NethermindPlugins.EmbeddedPlugins`; see `consensus-engines.md` §"Plugin discovery"). The
downstream `ad7d2a8a3` commit is the literal confirmation: it is "a new assembly + two chainspecs
+ one registration line," exactly the "new family = new module, zero central-dispatch edit"
pattern — **but the module itself is substantial consensus code (ETChash DAG, ECIP-1017 emission,
MESS), not configuration.** So: **plugin-cheap to wire, engine-heavy to build.**

---

## Authority note

- **nethermind is NOT an ETC reference — full stop.** Upstream has no ETChash/ECIP/Classic
  anything; the `EthereumClassic*`/`Morden` `BlockchainIds` constants are handshake-test fixtures.
  **core-geth remains the sole ETC/PoW authority** (ETChash, ECIP-1017/1099/1100/1111/1122). Do
  not cite nethermind for ETC consensus behavior.
- **nethermind IS a solid generic-Ethash PoW reference** — a complete, actively-maintained CPU
  Ethash (DAG, Hashimoto, difficulty-bomb, seal validation) **plus an opt-in miner** — useful to
  fukuii as a **fourth+ PoW cross-check for the vanilla-Ethash layer** (alongside go-ethereum,
  besu, erigon), i.e. the pre-ECIP substrate that ETChash specializes. It is authoritative for
  the *plugin-architecture* concern (per the Phase-0 model), not for PoW consensus values.
- **The downstream `origin/main` ETC plugin is fukuii-owner experimental code, not a client
  reference.** Treat it as a design precedent for "how to graft ETC onto a plugin model," never
  as an independent authority — its ECIP semantics must still be validated against core-geth.

## Gotchas / things to not misread

- **Branch trap:** searching `--all` surfaces the downstream `ad7d2a8a3` ETC commit; searching
  the `upstream` branch (the documented anchor) surfaces nothing. Anyone re-running this survey
  must pin the branch or they will wrongly conclude "nethermind supports ETC."
- **Constant trap:** `EthereumClassicMainnet = 61` in `BlockchainIds.cs` looks like support but is
  an unused-except-in-tests registry entry — presence of a chain-ID constant ≠ presence of a
  consensus implementation.
- **Merge did not remove Ethash mining;** it is opt-in (`miningConfig.Enabled`) and retained for
  dev/private-PoW/test + pre-merge validation. Don't assume a PoS-era client dropped its miner.
