# core-geth — consensus-engines

_Commit/branch documented: `b28aa0a0bbb1e3ba72ce11afb9310d9dc38c1832` (branch `main`,
2026-06-26). Vendored at `.claude/repo-references/clients/core-geth`. Documented 2026-07-13._

_**Re-verified against `upstream` 2026-07-13** (SHA `4185df450`, the **deprecated Sept-2024**
last-independent core-geth branch — the frozen ETC byte-authority). **Attribution correction:**
the vendored working tree is checked out at `main` (`b28aa0a0`), which is **+65 commits of
fukuii's OWN ETC modernization overlaid on top of upstream** — the entire Olympia bundle
(ECIP-1111 base-fee floor + Treasury routing, ECIP-1112 treasury address, ECIP-1121 gas-target
schedule / EIP set, ECIP-1122 tip floor). An earlier pass documented this tree without separating
the overlay and so attributed fukuii's own forward work to core-geth-the-reference. **It is not
upstream core-geth.** Every Olympia claim below is now split out under an explicit "**fukuii `main`
overlay — NOT upstream core-geth**" banner; each was confirmed main-only via
`git merge-base --is-ancestor <sha> upstream` returning *not-an-ancestor* (and the files/symbols
are absent from `upstream`). What remains attributed to core-geth (ECIP-1010/1017/1041/1099/1100)
was confirmed present in `upstream`. Line numbers throughout reflect the vendored `main` tree; for
retained upstream material the content is upstream-verified but some line offsets differ from the
`4185df450` tree._

_core-geth is a **go-ethereum fork** (multi-geth lineage). This doc documents what core-geth
**ADDS / CHANGES for ETC** and does **not** re-derive the shared `consensus.Engine` interface —
read the sibling `go-ethereum/consensus-engines.md` for the interface, `ChainHeaderReader` DI seam,
async `VerifyHeaders` channel pattern, and the `beacon` decorator model, then read this for the
ETC-specific divergences. Folds in the B7.0 engine-axis research
(`.local/docs/research-july/b7.0-engine-axis-decision.md`, 2026-07-13) — the positive-keying
`GetConsensusEngineType`, ECIP-1099 epoch parameterization, and inert-beacon-wrap findings were
first surfaced there and are cited/expanded here rather than re-derived._

## Architecture summary
core-geth keeps go-ethereum's algorithm-agnostic `consensus.Engine` interface and its three concrete
engines (`ethash`, `clique`, `beacon`) but **retains the entire ETC PoW ruleset that upstream geth
deleted**: real ethash sealing/mining, the full difficulty-bomb + ECIP-1010/1041 bomb-management
ladder, ECIP-1017 fixed-supply era emission, ECIP-1099 ("ETChash") DAG-epoch resizing, and ECIP-1100
(MESS) subjective reorg protection. **(The Olympia bundle — ECIP-1111/1112/1121/1122 EIP-1559 with
a base-fee floor routed to a Treasury — is NOT in `upstream` core-geth. It is fukuii's own `main`
overlay, documented in its own re-attributed section below, and must not be read as a core-geth
reference finding.)** Crucially, ETChash is **not a separate engine** — it is a DAG
epoch-length *parameter* (`ECIP1099Block`) on the one `ethash.Config`. All ETC fork scheduling lives
in the `CoreGethChainConfig` fork-config object and is dispatched by **`config.IsEnabled(getter,
blockNumber)`** — every ETC rule is block-number-keyed (ETC never merges, so there is no
timestamp-fork axis in the PoW path). Engine *type* selection is done by **positive keying**
(`GetConsensusEngineType` returns `Ethash`/`Clique`/`Lyra2`/`Unknown` from which typed sub-object is
present), a cleaner shape than the `else-means-ethash` fallthrough it inherited at the
`CreateConsensusEngine` layer.

## Key types / interfaces / files

### Engine selection & the (inert) merge wrap
- `eth/ethconfig/config.go:238-277` — **`CreateConsensusEngine`**. Order: `clique != nil` → clique;
  `lyra2 != nil` → lyra2; else ethash (real `ethash.New(...)` with `ECIP1099Block` threaded in at
  `:271`, then `SetThreads(-1)`). Unlike current go-ethereum, **`ethash.New` here is a real sealing
  engine, not a panicking stub.** The result is always wrapped `beacon.New(engine)` (`:277`).
- `params/types/coregeth/chain_config_configurator.go:967-978` — **`GetConsensusEngineType()`**, the
  **positive-keying** discriminant: `Ethash != nil → Ethash`, `Clique != nil → Clique`,
  `Lyra2 != nil → Lyra2`, else `ConsensusEngineT_Unknown`. No silent default — this is the pattern
  B7.0 §A.1 adopts over geth's fallthrough (the `CreateConsensusEngine` layer above still has the
  `else`-ethash shape, but the *config authority* keys positively).
- `consensus/beacon/consensus.go:438-441` — `IsPoSHeader(header)` = `header.Difficulty == 0` (panics
  on nil). `:116` reads `GetEthashTerminalTotalDifficulty()`. **On an ETC config TTD is unset and all
  ETC blocks carry non-zero PoW difficulty, so the beacon wrap is inert** — every header routes to the
  inner ethash engine. core-geth wraps unconditionally (like geth) but ETC never trips the PoS path,
  giving the same runtime result fukuii achieves with a *conditional* wrap (B7.0 §C Option 2).

### ETChash — ECIP-1099 (DAG epoch resize, NOT an engine)
- `consensus/ethash/algorithm.go:41-42` — `epochLengthDefault = 30000`, `epochLengthECIP1099 = 60000`.
- `consensus/ethash/algorithm.go:52-60` — **`calcEpochLength(block, ecip1099FBlock)`**: returns 60000
  once `block >= *ecip1099FBlock`, else 30000. This single function is the whole of ETChash.
- `consensus/ethash/algorithm.go:142-155` — **`seedHash(epoch, epochLength)` subtlety**: the seed-hash
  chain is iterated `block / epochLengthDefault` times using the **default 30000**, not the active
  epoch length — the seed derivation stays anchored to the 30000 grid across the ECIP-1099 boundary.
  A byte-match trap for any reimplementation.
- `consensus/ethash/consensus.go:582-583` — verifySeal recomputes `epochLength = calcEpochLength(
  number, ethash.config.ECIP1099Block)` then `datasetSize(calcEpoch(...))` for the PoW check.
- `consensus/ethash/ethash.go:564` — `ECIP1099Block *uint64` on `ethash.Config`; `:717,:739,:744` —
  cache/dataset epoch math all route through `calcEpochLength`. `:744` special-cases ECIP-1099 epochs
  42 and 195 (the boundary epochs) for cache/dataset alias handling.
- config: `ECIP1099FBlock` = **11,700,000** mainnet (`config_classic.go:92`), **2,520,000** Mordor
  (`config_mordor.go:81`).

### ECIP-1017 — fixed-supply era emission (the reward calculator)
- `params/mutations/rewards.go:37-62` — **`GetRewards`**: if `ECIP1017Transition` enabled →
  `ecip1017BlockReward`; otherwise the standard geth static-reward + uncle formula.
- `params/mutations/rewards_classic.go:27-45` — **`ecip1017BlockReward`**: `blockReward =
  FrontierBlockReward` (5e18), `era = GetBlockEra(header.Number, eraRounds)`, winner reward =
  `GetBlockWinnerRewardByEra` + uncle-inclusion rewards.
- `params/mutations/rewards.go:109-128` — **`GetBlockWinnerRewardByEra`**: era 0 → full 5 ETC; era n →
  `blockReward * 4^n / 5^n` (`DisinflationRateQuotient=4` / `DisinflationRateDivisor=5`,
  `config_classic.go:144-145`). Yields the 5 → 4 → 3.2 → 2.56 … schedule.
- `params/mutations/rewards_classic.go:49-62` — **`GetBlockEra`**: zero-indexed era =
  `floor((blockNum - 1) / eraLength)` (era 1 = index 0). The `-1` offset is consensus-critical.
- `params/mutations/rewards.go:76-105` — uncle-reward-by-era: era 0 uses the geth `(uncleNum + 8 -
  headerNum) * reward / 8` inclusion formula; era ≥ 1 flattens to `winnerReward / 32` per uncle.
- config: `ECIP1017FBlock` = 5,000,000 & `ECIP1017EraRounds` = **5,000,000** mainnet
  (`config_classic.go:54-55`); FBlock 0 / EraRounds **2,000,000** Mordor (`config_mordor.go:117-118`).
- `params/mutations/rewards.go:66-72` — **`AccumulateRewards`**: credits uncle coinbases then the
  header coinbase.

### Difficulty — the bomb ladder + ECIP-1010 pause/ECIP-1041 disposal
- `consensus/ethash/consensus.go:366-524` — **`CalcDifficulty`**, one function selecting the adjustment
  algorithm by fork (`EthashEIP100BTransition` → Byzantium uncle-aware; `EIP2Transition` → Homestead;
  else Frontier) then applying bomb management.
- `consensus/ethash/consensus.go:417-419` — **ECIP-1041 (bomb disposal)**: if
  `GetEthashECIP1041Transition` enabled, **`return out` before the explosion block entirely** — the
  difficulty bomb is permanently removed, not delayed. `GetEthashECIP1041Transition` maps to
  `DisposalBlock` (`chain_config_configurator.go:1414-1418`; mainnet 5,900,000
  `config_classic.go:57`).
- `consensus/ethash/consensus.go:426-427` + `consensus/ethash/consensus_classic.go:24-33` —
  **ECIP-1010 (bomb pause/delay)**: `ecip1010Explosion` freezes the explosion reference at the pause
  block until the continue block, then subtracts the pause length. Mainnet pause 3,000,000 / length
  2,000,000 → continue 5,000,000 (`config_classic.go:51-52`). Mordor sets these `nil`
  (`config_mordor.go:119-120`) — no ECIP-1010 on Mordor.
- `consensus/ethash/difficulty.go:43-191` — the U256 fast-path calculators (Frontier / Homestead /
  `MakeDifficultyCalculatorU256` Byzantium bomb-delay) used for fuzzing/testing parity.

### ECIP-1100 (ECBP-1100 / MESS) — subjective reorg protection
- `core/blockchain_af.go:112-149` — **`ecbp1100`**: rejects a proposed reorg when
  `proposedSubchainTD * 128 < ecbp1100PolynomialV(current.Time - commonAncestor.Time) *
  localSubchainTD` — i.e. an old-rooted reorg must carry disproportionately more TD the further back
  its common ancestor. Returns `errReorgFinality` (`🔒 ECBP1100-MESS status=rejected`).
- `core/blockchain_af.go:173-233` — **`ecbp1100PolynomialV`**, the integer cubic sine-approximation
  `3x² - 2x³/xcap`, rescaled by `height`. Constants (**must byte-match**):
  `CURVE_FUNCTION_DENOMINATOR = 128`, `xcap = 25132` (= floor(8000·π)), `ampl = 15`,
  `height = 128 · 15 · 2 = 3840` (`:221-233`). `x = current.Time - commonAncestor.Time`, capped at
  `xcap`.
- `core/blockchain_af.go:58-89` — **`EnableArtificialFinality`** gating: MESS only engages between
  `ECBP1100Transition` and `ECBP1100DeactivateTransition`, and (unless `--ecbp1100.nodisable`) also
  self-disables on low peer count / stale head (subjective, node-local — never a state-root rule).
- config: mainnet activate **11,380,000**, deactivate **19,250,000** (= Spiral), reactivate at Olympia
  (`config_classic.go:89-91`); Mordor activate 2,380,000, deactivate 10,400,000, reactivate Olympia
  (`config_mordor.go:121-123`).

### Olympia — ECIP-1111/1112/1121/1122 — fukuii `main` overlay, NOT upstream core-geth (Phase 3-4 material)
> **ATTRIBUTION BANNER.** None of the symbols/files in this section exist in `upstream`
> (`4185df450`, deprecated Sept-2024 core-geth). `params/olympia_treasury.go`,
> `GetBaseFeeMinValue`/`ForkGasTarget`/`OlympiaGasTarget`, the `Finalize` treasury credit, and
> every Olympia config field were confirmed **main-only** — `git merge-base --is-ancestor <sha>
> upstream` returns *not-an-ancestor*, and `git cat-file`/`grep upstream:` show the files/symbols
> absent from `upstream`. This is **fukuii's own ETC modernization**, checked into core-geth's
> `main` fork as a working overlay; read it as fukuii Phase 3-4 design of record, **not** as a
> core-geth reference finding. Upstream core-geth (the deprecated ETC byte-authority) has **no**
> EIP-1559 / base-fee-floor / treasury / gas-target implementation at all. Line numbers below are
> the `main`-tree overlay's, not upstream's.
- `consensus/misc/eip1559/eip1559.go:87-138` — **`CalcBaseFee`** with the **ECIP-1111 base-fee floor**:
  after the standard EIP-1559 up/down adjustment, `if floor := GetBaseFeeMinValue(); floor != nil &&
  baseFee < floor { return floor }` (`:133-135`). ETC sets the floor to `InitialBaseFee` = **1 gwei**;
  ETH returns `nil` (no floor, base fee can decay to 0). The empty-block decrease delta is floored at 1
  wei *before* the ECIP-1111 clamp (`:126-128`).
- `consensus/ethash/consensus.go:617-634` — **`Finalize` routes base-fee revenue to the Treasury**:
  when EIP-1559 is enabled, credit `header.GasUsed * header.BaseFee` to
  `GetOlympiaTreasuryAddress()` **BEFORE** miner/ommer rewards (ECIP-1111 ordering) — base fee is
  **not burned** (the PoS/ETH divergence). Logs an error and skips if the treasury address is nil.
- `params/olympia_treasury.go:10` — **ECIP-1112 Treasury address**
  `0x60d0A7394f9Cd5C469f9F5Ec4F9C803F5294d79b`, shared by mainnet + Mordor; comment notes it matches
  Besu, Fukuii, and Nethermind reference impls.
- `consensus/misc/eip1559/eip1559.go:39-53` — **ECIP-1121 network-authoritative gas target
  schedule**: `ForkGasTarget` returns `OlympiaGasTarget` (60M) at/after Olympia, `SpiralGasTarget`
  (8M) at/after Spiral, else nil. `:64-69` — the London `parentGasLimit * ElasticityMultiplier`
  doubling is **skipped for ETC** (a fork-parameterised 8M→60M ramp replaces it).
  `config_classic.go:115-116` / `config_mordor.go:96-97`.
- **ECIP-1122** `TxPoolPriceLimit = InitialBaseFee` (1 gwei `MIN_MINER_TIP`) — a txpool/policy floor,
  set alongside `BaseFeeMinValue` (`config_classic.go:135-136`). (Client-layer policy, banksy's
  concern, but co-located here.)
- Olympia EIP set is present but **not yet scheduled** — both `olympiaMainnetBlock` and
  `olympiaMordorBlock` are the sentinel `1_000_000_000_000_000_000` (`config_classic.go:32`,
  `config_mordor.go:29`); every Olympia EIP field references that one constant (block-number keyed,
  never timestamp).

## Design decisions & rationale
- **ETChash is a DAG parameter, not an engine** (`algorithm.go:52-60`). ECIP-1099 only doubles the
  epoch length (30000→60000) to slow DAG growth; the PoW algorithm, seal check, and header format are
  unchanged. Modeling it as a `*uint64` on the shared `ethash.Config` (rather than a new
  `consensus.Engine`) is the "same mechanism, different parameterization" axis B7.0 §A.2 makes
  authoritative — fukuii mirrors this with `ecip1099BlockNumber`.
- **Bomb disposal vs. bomb delay are distinct code paths** (`consensus.go:417-419` early-return for
  ECIP-1041 vs. the `ecip1010Explosion` pause at `:426`). ETC *removed* the bomb (ECIP-1041) after
  first *pausing* it (ECIP-1010); core-geth keeps both as separate, independently-gated transitions
  rather than collapsing them.
- **Fixed-supply emission as a config-gated reward calculator** (`rewards.go:37-39`). The ECIP-1017
  era schedule is selected by a single `IsEnabled` check; everything else (the geth static-reward
  path) is preserved for pre-ECIP-1017 history. Era indexing carries a deliberate `-1` offset
  (`rewards_classic.go:55`) so "Era 1" = index 0.
- **MESS is deliberately subjective and non-state-affecting** (`blockchain_af.go:58-89`). It lives in
  `core/blockchain_af.go` (fork-choice), *not* in the engine's header verification, and self-disables
  on low peers / stale head. It changes which valid chain a node *prefers*, never whether a block is
  valid — so it never alters a state root. This is exactly why fukuii routes MESS to `banksy` (policy)
  with `forge` co-sign, not to `forge` alone.
- **(fukuii `main` overlay, NOT upstream core-geth)** **EIP-1559 base fee is redirected, not burned**
  (`consensus.go:620-630`). ECIP-1111 keeps the fee revenue on-chain by crediting the ECIP-1112
  Treasury in `Finalize`, ordered *before* block rewards. This is the single largest ETC-vs-ETH
  divergence in the fee market and the clearest reason fukuii's ETC path must not copy ETH's burn —
  but the *implementation* documented here is fukuii's own (absent from `upstream`), not a core-geth
  reference to match.
- **(fukuii `main` overlay, NOT upstream core-geth)** **Gas target is network-authoritative,
  overriding operator flags** (`eip1559.go:36-38` comment). The 8M→60M schedule comes from chain
  config, not `--miner.gaslimit`, so the throughput ramp is a consensus rule rather than an operator
  choice — and the London 2× gas-limit doubling is suppressed so the ramp stays smooth across the
  Olympia boundary. (ECIP-1121; fukuii-authored, not in upstream core-geth.)

## Notable patterns (the reusable idea)
1. **Parameterize, don't multiply engines.** ECIP-1099 (epoch length), ECIP-1017 (reward schedule),
   and ECIP-1010/1041 (bomb management) are all *config-gated parameters and `IsEnabled` branches on
   one ethash engine* — not new engine types (Olympia's base-fee floor / gas target / treasury follow
   the same shape, but as fukuii's `main` overlay, not upstream core-geth). The engine count stays at
   three; ETC identity lives entirely in `CoreGethChainConfig`.
2. **Positive engine-type keying** (`GetConsensusEngineType`, `chain_config_configurator.go:967-978`):
   selection from which typed sub-object is present, with an explicit `Unknown` rather than a silent
   default — the shape B7.0 §A.1 ports (and the one geth's `CreateConsensusEngine` fallthrough lacks).
3. **Subjective fork-choice as a separable, self-disabling layer** (MESS in `blockchain_af.go`,
   gated + peer/staleness-aware) — reorg-resistance decoupled from block validity, so it can be toggled
   without touching consensus determinism.
4. **Fee redirection via a `Finalize` pre-reward credit** — the same hook geth uses for rewards, reused
   to route base fee to a Treasury address, preserving ETC's fixed-supply-plus-treasury economics
   inside the standard engine interface. **(This pattern is realized in fukuii's `main` overlay, not
   in upstream core-geth — it is a fukuii Phase 3-4 design, listed here as the reusable idea, not as a
   core-geth reference.)**

## Authority note
**core-geth `upstream` (the deprecated Sept-2024 branch, `4185df450`) is THE authority for ETC / PoW /
ETChash and the *pre-Olympia* ECIP consensus rules** — ECIP-1010, ECIP-1017, ECIP-1041, ECIP-1099, and
ECIP-1100 (MESS). Per the Phase-0 authority model it is the *only* authority for that ETC consensus
surface; go-ethereum and reth have **dropped standalone PoW** and their ethash remnants are
historical-header verifiers, so they are **not** ETC authorities. Where this doc gives constants for
those ECIPs, **fukuii must byte-match core-geth `upstream`**:
- **ECIP-1017**: `FrontierBlockReward` 5e18, disinflation `4/5` per era, era length 5,000,000 (mainnet)
  / 2,000,000 (Mordor), zero-indexed era `floor((n-1)/eraLen)`, and the era-0-vs-era-≥1 uncle formulas.
- **ECIP-1099**: epoch 30000→60000 at block 11,700,000 (mainnet) / 2,520,000 (Mordor), and the
  seed-hash chain anchored to the **30000** grid (`algorithm.go:146-151`).
- **ECIP-1010/1041**: pause 3,000,000 / length 2,000,000 / continue 5,000,000; disposal 5,900,000
  (bomb removed via early return).
- **ECIP-1100 (MESS)**: polynomial constants `denominator=128`, `xcap=25132`, `ampl=15`, `height=3840`;
  activation 11,380,000 / deactivation 19,250,000 (mainnet). **(The MESS *reactivation-at-Olympia* field
  `ECBP1100ReactivateFBlock` is fukuii's `main` overlay, not upstream — see the Olympia banner.)**

**The Olympia bundle (ECIP-1111/1112/1121/1122) is NOT part of this authority.** It does not exist in
`upstream` core-geth (files absent; `merge-base --is-ancestor` → not-an-ancestor). The base-fee floor
(1 gwei), Treasury address `0x60d0A7394f9Cd5C469f9F5Ec4F9C803F5294d79b`, the `gasUsed*baseFee`
`Finalize` credit before rewards, the 8M→60M gas target with no London 2× doubling, and the 1-gwei tip
floor are **fukuii's own `main`-overlay values** (fukuii Phase 3-4), cross-checked against the Olympia
ECIP drafts + Besu/Nethermind — **not** inherited from core-geth. There is no core-geth reference to
byte-match for Olympia; fukuii is authoring it.

fukuii diverges from core-geth deliberately only in *architecture*, not in *values*: fukuii uses a
**conditional** merge wrap (skipped for permanently-PoW ETC) whereas core-geth wraps `beacon.New`
unconditionally and relies on the wrap being inert (TTD unset ⇒ no PoS header ever). Runtime behavior
is identical; fukuii's shape simply makes "ETC never merges" explicit rather than emergent.

## Gotchas / anti-patterns / things they later changed
- **The `CreateConsensusEngine` `else`-ethash fallthrough survives** (`eth/ethconfig/config.go:239-275`)
  even though the config *authority* (`GetConsensusEngineType`) keys positively. Read the two layers
  separately: the configurator is the clean pattern to port; the `CreateConsensusEngine` selector still
  carries geth's fallthrough shape.
- **Unconditional `beacon.New` wrap on a chain that can never go PoS** (`config.go:277`). It works only
  because ETC headers always have non-zero difficulty and TTD is unset — a latent footgun if a future
  config ever set TTD or produced a zero-difficulty header. fukuii's conditional wrap removes the
  latent path.
- **ECIP-1010 nil-vs-set is silent on Mordor** (`config_mordor.go:119-120` sets pause/length `nil`).
  Mordor genuinely has no ECIP-1010 bomb pause; do not "helpfully" backfill defaults — the nils are
  load-bearing (they route `CalcDifficulty` away from `ecip1010Explosion`).
- **`GetBlockEra`'s `-1` offset** (`rewards_classic.go:55`) is easy to drop; off-by-one there shifts
  the entire emission schedule by one block at every era boundary.
- **seedHash uses `epochLengthDefault`, not the active epoch length** (`algorithm.go:146-151`) — a
  reimplementation that "corrects" this to the ECIP-1099 length would produce wrong DAG seeds after
  block 11,700,000 and fail every post-ECIP-1099 seal check.
- **The `beacon.New` wrapper stays because ETC keeps permanent PoW** — core-geth intentionally did NOT
  follow go-ethereum in deleting ethash sealing (`ethash.New` at `config.go:260` is a real engine, not
  geth's panicking stub). Any alignment pass that "modernizes" fukuii's ETC path toward current geth's
  PoS-only engine creation would break a live network — the canonical README example of
  authority-aware alignment.
