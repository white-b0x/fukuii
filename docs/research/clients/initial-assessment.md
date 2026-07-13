# Reference-Client Research — Initial Assessment & Deep-Question Map

_**INTERIM orientation synthesis (2026-07-13).** This is NOT the Phase-2 comparison. It summarizes what the
★-tier orientation pass (storage / consensus / sync / multi-network across all 6 clients) established, and —
more importantly — enumerates the **deeper questions** that pass exposed. The orientation taught us the
*shape* of the differences; the deep review (every subsystem, per each client's own dependency graph, plus
the second-wave questions below) is where the real understanding comes from. The comparison (where clients
align → best practice; where they differ → why) runs only AFTER that deep review. Superseded then by
`observations/*.md`._

Clients + commits documented: go-ethereum `59e89e81e`, core-geth `b28aa0a0`, besu `3fd233a4`, erigon
`f1d79d699e`, nethermind `0d09a09e`, reth `3d76b93c`. besu additionally has `networking-p2p` documented.

---

## 1. What the orientation established — the four spectrums

### 1a. Family-abstraction spectrum (how a client selects/adds a consensus family)
From least to most modular:
- **go-ethereum** — single-family (ETH + its testnets). A "network" = Genesis + embedded ChainConfig. `else-means-ethash` fallthrough. WEAK multi-network authority.
- **core-geth** — config-schema pluggability: a network is a `ChainConfigurator` *interface* value; 2 schemas (coregeth/goethereum) satisfy one `Configurator` contract; but a **closed 3-engine enum** (`ConsensusEngineT` ethash/clique/lyra2). Positive keying (no fallthrough) in its own schema.
- **besu** — genesis-config **positively selects the mechanism** (`isClique/isQbft/getPowAlgorithm`), 5 co-equal mechanisms, but a **closed hand-maintained if/else** `BesuControllerBuilder` dispatch. Key pattern: **mechanism DECORATES the fork-schedule** (`ProtocolSchedule` + `ProtocolSpecAdapters`).
- **erigon** — **compile-time module registry** (blank-import `init()` self-registration; polygon ships its own chainspecs). Broadest *real* multi-family reach (ETH/PoS + Polygon/Bor + Gnosis/Aura). But **leaky** (`FrozenBorBlocks` in the shared reader; Bor's config-is-interface breaks the pointer convention).
- **nethermind** — **runtime self-declaring plugin registry** (`IConsensusPlugin` per-assembly; reflection-discovered `IChainSpecEngineParameters`; `SealEngineType` a flat string). The pole. Honest nuance: **two tiers** — config-params are genuinely zero-edit, but wiring a new consensus *subsystem* still uses a hand-maintained `EmbeddedPlugins` list.
- **reth** — **compile-time-generics SDK** (`NodeTypes` associated types; a family = a downstream crate). Type-safe, but one-crate-per-family. Small leak: `is_optimism()` in the shared trait.

**Synthesis for fukuii (single-binary JVM):** neither pole is a drop-in. Target = a **Scala 3 `given`-based typeclass registry** — reth's compile-time safety inside nethermind's single-binary runtime family-selection — onto fukuii's DESIGNED-not-built `Sealer`/`ValidatorProvider`/`BlockInterface` seams (which map 1:1 onto besu's). The **uniqueness guard** is already ported (B7.0-a).

### 1b. Merge / transition model (validates B7.0-c)
All PoW/PoS clients model the merge as a **composition, content-derived** — geth `beacon` wrapper (`difficulty==0`), core-geth same (inert on ETC), besu `TransitionProtocolSchedule` (conditional) + block-keyed PoA→PoA, nethermind `MergePlugin` co-activation (TTD-gated), erigon conditional `merge.New`, reth Engine-API-native. **None buries it in a monolith** — confirming B7.0-c's conditional beacon-style `EngineSchedule` wrapper. ETC stays unwrapped/permanently-PoW.

### 1c. Fork-dispatch unification (block-number vs timestamp)
fukuii splits into two `EvmConfig.forBlock` overloads. Three reference unifications: **nethermind `AddTransitions(blockNumbers, timestamps)`**, **reth `ForkCondition{Block,Timestamp,TTD,Never}`** + `Hardforks` capability queries, **besu `MilestoneType.{BLOCK_NUMBER,TIMESTAMP}`**. → a candidate fukuii unification (family-neutral single seam).

### 1d. Storage-model spectrum
- **RocksDB + column families** (fukuii's camp): **besu** (`SegmentIdentifier` + Bonsai flat / Forest archival), **nethermind** (C#-enum CFs + **online full-pruning** + Hash/HalfPath dual scheme), fukuii (namespace-prefix CFs + hash-keyed trie + partial flat overlay). → **besu = fukuii's closest storage authority.**
- **MDBX + flat/static** (the alternative): **erigon** (MDBX B+tree + **zero leaf-trie** flat Domains + frozen segments), **reth** (MDBX + static files + secondary RocksDB `OptimisticTransactionDB` + trie-nodes-by-nibble-path).
- **go-ethereum** — single-keyspace LSM (Pebble) + freezer + hashdb/pathdb.
- **Trie spectrum:** erigon (zero leaf-trie, commitment on demand) → reth (nibble-path nodes) → besu Bonsai (thin trie) → besu Forest / nethermind Hash / fukuii (hash-keyed node store).
- **Iterator/cursor safety:** fukuii's batched `unboundedScan` (the L1 fix) ≈ erigon (transaction-owns-cursors) ≈ reth (RAII `commit(self)`-consumes) — the *strong* structural end; > besu (stream-close) ≈ geth (`Release()`-mandate + leak metric). **fukuii's L1 fix is well-aligned.**
- **Schema-version check:** besu (`DATABASE_METADATA.json` + versioned-format enum), erigon (per-file version in filenames), reth (`tables!` type-level). **fukuii has NONE** → Phase-4 gap.

### 1e. Sync-decomposition spectrum
- **go-ethereum** — single downloader; post-merge skeleton/BeaconSync; snap/1 + experimental snap/2. Dropped PoW-from-genesis.
- **core-geth** — **RETAINS full peer-driven PoW `LegacySync` from genesis** (master-peer + TD head selection), MESS/ECBP-1100 wired into the sync loop; snap/1 only. **THE PoW-sync-from-genesis authority.**
- **besu** — `SyncMode` contracted to `{FULL,SNAP}`; the multi-approach lives in **seams**: `SyncMode`↔storage-format coupling, **per-family `PivotBlockSelector`**, genesis **Checkpoint anchor**; two-phase heal.
- **erigon** — serial **staged pipeline**; checkpoint = one progress integer/stage; first-class ordered unwind; no snap-heal (no trie store).
- **nethermind** — **parallel `SyncFeed`/dispatcher** + **combinable `MultiSyncModeSelector`** + FastBlocks backward fill.
- **reth** — staged pipeline, Engine-API-driven; no PoW-from-genesis path.
→ **Multi-sync-approach Phase-4 theme** (expand fukuii's single snap/v1): the patterns are **seams** (besu PivotBlockSelector) + **parallel feeds** (nethermind) + **staged resumability** (erigon) + **checkpoint anchor** (besu) — not "more mode enums."

### 1f. Wire-version handling
besu advertises **ETH68/69/70/71 simultaneously** via `CapabilityMultiplexer` (generic per-protocol-name RangeMap; add a version = 1 constant + 1 message list + 1 switch arm). **fukuii collapses to one `Option[Capability]` via `best()` + SNAP bolt-on boolean** → the concrete gap. (Other clients' wire handling not yet deeply surveyed — see deep questions.)

---

## 2. Refined authority map (per concern)
- **ETC / PoW / ETChash / ECIP** — **core-geth = the byte-authority AND the ONLY production / PoW-miner-adopted ETC client** (Go; deprecated Sept-2024 but still the frozen authority; the only client ETC miners actually run, then and now). **A second ETC reference exists but was never miner-adopted:** **besu's git history is a ~6-year (2019→Feb-2026), JVM, complete & accurate ETC implementation** (`ClassicProtocolSpecs`, `Ecip1099EpochCalculator`, ECIP-1017 emission, ECIP-1049 Keccak mining, Stratum) — fukuii's closest-language STRUCTURAL reference — **BUT it had NO PoW-miner adoption** (besu wasn't run by ETC miners) and its ECIP-1099 diverged from core-geth → NOT byte-authoritative, and its **MINING path specifically is cautionary** (validate against core-geth, the miner-adopted one). **go-ethereum pre-merge** = ancestral Ethash structure. **nethermind** = generic Ethash (still opt-in mining) but never shipped ETC. **NET: core-geth for *values* + the *mining/production* path (sole miner-adopted client); besu-history + geth-history for *structure* (esp. JVM/besu), with mining paths cross-validated against core-geth.**
- **ETH / PoS + the `consensus.Engine` interface + snap wire semantics** — **go-ethereum**.
- **Multi-consensus / PoA** — **besu** (Clique *validation*+config, IBFT2/QBFT *production*); **Clique *sealing* → core-geth** (besu removed Clique block production).
- **Sidechain / Bor / external-infra-injection** — **erigon** (NET-01 reference).
- **Self-declaring plugin registry** — **nethermind**.
- **Compile-time-generics SDK / type-safety** — **reth**.
- **Storage:** RocksDB+CF (fukuii's mirror) → **besu**; MDBX/flat alternative → **erigon**/reth; **online full-pruning → nethermind**.
- **Sync:** PoW-from-genesis → **core-geth**; snap wire → go-ethereum; parallel-feed → nethermind; staged-resumable → erigon/reth; checkpoint → besu.

---

## 3. DEEP-QUESTION MAP (drives the deep review — Phase 1b/1c)

### 3a. ★ HIGHEST VALUE — Historical PoW / Ethereum Classic support (operator-requested)
The orientation only saw each client's *current* HEAD. But **most of these clients supported PoW (and some ETC) historically** — mining their git history gives fukuii **many PoW/ETC reference implementations, not just core-geth**:
- **go-ethereum** — ran PoW/Ethash until the Merge (Sept 2022); **ETC was originally a go-ethereum fork** — the *original* Ethash/ETC structure lives in its pre-merge history. What did `consensus/ethash` + the pre-merge downloader look like? When/how was PoW removed?
- **besu** — had Ethash mining pre-merge (`PowAlgorithm.ETHASH`); did it ever support ETC? What did its full PoW mining/sealing path look like before it was stubbed?
- **nethermind** — Ethash consensus plugin; **did nethermind support ETC?** (It historically did support many chains.) What's the Ethash mining structure?
- **erigon** — Ethash pre-merge; structure of its PoW path before staged-sync assumed PoS?
- **core-geth** — current ETC authority (already documented; the baseline to compare the others' *historical* structure against).
- **reth** — NEVER had PoW (confirmed: no ethash/pow crate) — a negative data point.
→ For each: **git-log/tag archaeology** of the PoW/Ethash era + any ETC/Classic support, documenting that structure. This directly enriches fukuii's PoW consensus with 4-5 more reference implementations.

### 3b. Wire protocol ETH68→ETH71 — commit-log evolution
What the **git history** shows each client did for ETH68/69/70/71: how/when each version was added, how they carry a *range*, the message-set deltas, deprecation of old versions. (besu's current state documented; need the *evolution* + the other clients.)

### 3c. Deeper on the ★ subsystems (follow-ups the orientation raised)
- **networking** — full devp2p/discovery(v4/v5/ENR)/RLPx per client (only besu done); peer scoring, DoS.
- **snap/v1** — the exact snap protocol impl + serving DoS bounds per client (partially surfaced); the healing algorithms.
- **storage** — the deep mechanics (compaction, caching, pruning modes, commitment computation).
- **sync modes** — the full mode/feed/stage machinery per client + node-role mapping (archival/full/light).

### 3e. Mining-protocol layer (operator 2026-07-13) — the mining-pool / validator use case
The orientation covered *consensus* but not the **mining-protocol** surface. **Two layers — keep them
distinct:** (1) **node-side `getWork`/`submitWork`** — the battle-tested, miner-adopted interfaces:
**go-ethereum was THE dominant ETH PoW-mining client** (until the Merge — the whole ETH mining ecosystem ran
geth), and **core-geth is the sole ETC one**; both are real-world-proven and authoritative. nethermind still
ships opt-in Ethash mining. (2) **pool-layer Stratum v1/v2** — lived in pool/miner software (ethminer, pools),
*between miner↔pool*, NOT in geth; reference sources are besu's server-side Stratum impl (implemented, never
miner-adopted → cautionary), external pool software, and the non-EVM clients. Plus external-miner integration
and `getblocktemplate`.
Consult the **non-EVM PoW clients** (`reference-clients-pow/{bitcoin,monero,zcash}`) as the richer
mining-protocol/pool reference. **Authority for the ETC mining/production path = core-geth** (the ONLY
miner-adopted production ETC client); besu's Stratum/getWork is a secondary *structural* reference that was
never miner-adopted → cross-validate, don't treat as battle-tested. Target: fukuii's ETC PoW mining (internal
Ethash + external-miner wiring) must match what ETC miners actually run — what to offer for the mining-pool
use case (default + optional per the omni-client lens).

### 3d. Full subsystem coverage (the 10 non-★ slots, per client, dependency-graph-ordered)
`build-deps · primitives · state-trie · evm · block-execution · txpool · networking-p2p · rpc · testing · node-lifecycle` — each client traversed low→high through its OWN module/dependency graph (the "understand it from its dep graph up" discipline). This is the bulk of the deep review.

---

### 3f. GUI (operator 2026-07-13) — end-user/enterprise-operator + mining-pool use cases
fukuii's planned **GUI** is a deliverable, not a reference-client subsystem — but the EVM clients don't offer a
strong GUI reference. **Inspiration = `reference-clients-pow/monero-gui`** (Qt/QML desktop client). Study its
UX/architecture for: node lifecycle + sync/peer status, **mining controls** (start/stop, threads, hashrate —
pairs with §3e internal-CPU-sealing + mining-pool), multi-network switching (enterprise), onboarding wizard,
i18n, cross-platform packaging. A Phase-4 deliverable seed, not a comparison input.

## 4. fukuii Phase-4 seeds accumulated so far (NOT acted on — for the post-comparison backlog)

**Governing principle (operator 2026-07-13) — fukuii is an OMNI-CLIENT: default = best practice, + optional
approaches per use-case.** Each seed below is therefore not "adopt X instead of Y" but "make the best-practice
the default AND offer the alternative as a mode/flag where a use-case (enterprise · CEX/custody ·
mining/validator · light/end-user · archival/data-serving+RPC · multi-network) benefits." The research
characterizes approaches by what they're GOOD FOR (see README "Use-case / node-role lens"), verdict =
DEFAULT / OPTIONAL(role) / OBSOLETE — we only discard the genuinely obsolete, and only after understanding why
it existed. Storage (Forest/archival vs Bonsai/pruned vs full-pruning/custody) and sync (full/snap/checkpoint/
PoW-from-genesis, parallel-feed) are already use-case menus, not single winners.

1. **`given`-based typeclass family registry** for consensus/family selection (B7.0.5) — reth-safety + nethermind-single-binary.
2. **`CapabilityMultiplexer`-style wire-version multiplexer** (replace `best()`-collapse + SNAP-bolt-on).
3. **On-disk schema-version check** (besu `DATABASE_METADATA` pattern) — fukuii has none.
4. **Fork-dispatch unification** (`AddTransitions`/`ForkCondition`) — collapse the two `forBlock` overloads into one family-neutral seam.
5. **Multi-sync expansion via seams + feeds** — `PivotBlockSelector`-per-family, parallel feeds, staged resumability, checkpoint anchor (archival/full/light node roles).
6. **Online full-pruning** (nethermind copy-live-to-fresh-CF + atomic swap).
7. **Family-neutrality guards** — avoid `FrozenBorBlocks`/`is_optimism()`-style leaks in shared seams (a NetworkFamily-design invariant).
8. **Clique sealing sourced from core-geth**, not besu (NET-02).
9. **Iterator/cursor safety** — L1 fix confirmed well-aligned with the strong (erigon/reth) end.

---

## 5. Corrected phasing (operator 2026-07-13)
1. ✅ **Phase 1a — ★ orientation** (this pass; 6 clients × storage/consensus/sync/multi-network + besu networking).
2. **This doc — initial assessment + deep-question map.**
3. **Phase 1b — deep full review:** every subsystem, per client, traversed via each client's own dependency graph.
4. **Phase 1c — second-wave deep questions** (woven into 1b): §3a historical PoW/ETC, §3b wire commit-log, §3c deeper ★-subsystem follow-ups.
5. **Phase 2 — comparison** (`observations/*.md`): align → best practice; differ → *why* (language/runtime, performance, legacy-vs-modernized). **Only after 1b/1c.**
6. **Phase 3–4 — fukuii snapshot + alignment audit → modernization backlog** (`.local/`).
