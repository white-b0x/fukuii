# erigon — history: PoW (Ethash) & ETC/Ethereum Classic

_Anchor commit: `f1d79d699ed4b809abc0d177dcb539d8605edc41` (branch `main` == `upstream`,
verified clean: `git rev-list --count upstream..main` = 0 — no fukuii overlay on this repo).
Vendored read-only at `.claude/repo-references/clients/erigon` (FULL clone, 31,437 commits back
to 2013 — erigon shares go-ethereum's early history as a geth/turbo-geth derivative).
Documented 2026-07-13. Git-log archaeology; read-only w.r.t. fukuii — no source edits, no commit,
no README grid edit._

_Companion to `consensus-engines.md` (current-HEAD structure). This file is the **historical
PoW/ETC** lens: how erigon organized Ethash before staged-sync/PoS, whether it mined, and whether
it ever touched Ethereum Classic._

## TL;DR (the three answers)

1. **Ethash structure**: erigon inherited geth's `pow/` → `consensus/ethash` package essentially
   verbatim (algorithm/difficulty/sealer/consensus/api) and still ships it today, but its
   *execution model* diverged hard: PoW header verification runs inside the **staged-sync
   stageloop / forkchoice** path, not geth's inline `blockchain.InsertChain`.
2. **Mining**: erigon **removed internal/local CPU PoW mining in March 2021** (pre-merge) and has
   ever since delegated sealing to **external miners via getWork/submitWork** (the `remoteSealer`).
   It never was a miner-oriented client; block production is staged (`Mining stage`, now
   bor-centric). No internal Ethash CPU miner remains.
3. **ETC / Ethereum Classic**: **NEVER supported.** Zero commits, zero chainspecs, zero code for
   `classic` (as ETC), `mordor`, `etchash`, or any `ECIP-NNNN`. The only "classic" hits in 31k
   commits are `eosclassicteam` (a geth-inherited 2018 GitHub org PR) and Grafana "Classic"
   dashboard widgets. erigon is **not** an ETC reference for values or behavior.

---

## 1. Ethash / PoW structure — pre-merge and its path lineage

### Path lineage (inherited from geth, then repeatedly relocated)
The Ethash package has been carried and moved four times but never rewritten:

| Date | Commit | Path move |
|------|--------|-----------|
| 2017-03-09 | `3b00a77de5` `crypto, pow: add pure Go implementation of ethash` | **geth-era** `pow/ethash.go` created (shared ancestry — this is a go-ethereum commit) |
| 2019-11-01 | `fe01bccbb8` `Apply Turbo-Geth modifications to go-ethereum codebase` | turbo-geth (→ erigon) **fork point** off go-ethereum |
| 2021-05-20 | `0be3044b7e` `rename (#1978)` | `pow/ethash.go` → **`consensus/ethash/ethash.go`** (rename `R080`) |
| 2025-04-11 | `c33b050f64` `dir improvements: move consensus into execution (#14555)` | `consensus/ethash` → **`execution/consensus/ethash`** (`R099`) |
| 2025-11-07 | `ed22e06701` `execution: rename consensus engine to rules engine (#17807)` | `execution/consensus/ethash` → **`execution/protocol/rules/ethash`** (`R097`); geth's `consensus` package concept renamed **`rules`** |

So today's `execution/protocol/rules/ethash/` (the location `consensus-engines.md` documents) is
the same lineage geth put in `consensus/ethash` — erigon renamed the *abstraction* (`consensus`→
`rules`, split into `EngineReader`/`EngineWriter`) but kept the Ethash *implementation*.

### Pre-merge package contents (the classic geth Ethash layout)
Snapshot of the restored ethash package at `c7469426b1` (Aug 2024, see §the E3 saga below), which
is the canonical pre-merge shape:

- `algorithm.go` — **DAG**: dataset/cache generation, `hashimoto`, epoch seed logic (the Ethash
  memory-hard core).
- `difficulty.go` — **difficulty calculators** incl. the difficulty-bomb delay:
  `makeDifficultyCalculator(bombDelay)` with `bombDelayFromParent`, `expDiffPeriod`
  (`execution/protocol/rules/ethash/difficulty.go:137-183`). Homestead/Byzantium/… bomb schedules.
- `consensus.go` (today `rules.go`) — **header verification** (`VerifySeal`/`verifySeal`,
  `execution/protocol/rules/ethash/rules.go:333-340`), uncle rules, reward application, `CalcDifficulty`.
- `sealer.go` — **`Seal` + `remoteSealer`** (mining / getWork machinery).
- `api.go` — the getWork/submitWork/submitHashrate **RPC surface** for external miners.
- `ethash.go` — engine struct, cache/dataset LRU management, config.
- `fake.go` — `FakeEthash` (no-verify test engine).
- `meter.go`, `ethashcfg/` — hashrate metering, config type.

This is geth's ethash package almost 1:1 — `51db5975cc consensus/ethash: move remote agent logic to
ethash internal (#15853)` (2018-08-03) and `09777952ee core, consensus: pluggable consensus engines
(#3817)` (2017-04-05) are both **go-ethereum** commits inherited through the shared history, i.e.
the pluggable-engine abstraction and the internal remote-sealer both predate the fork.

### Structural divergence from geth: staged sync
The key erigon-vs-geth divergence is **not** in the ethash package but in *how it's driven*:

- **geth**: PoW header verification is inline in the blockchain insert path
  (`BlockChain.InsertChain` → `engine.VerifyHeaders`), state is a live trie.
- **erigon**: verification is a **stage**. `.VerifyHeader(...)` is invoked from the staged-sync
  driver — `execution/stagedsync/stageloop/stageloop.go` and `execution/execmodule/forkchoice.go`
  (the only non-test, non-`rules/` callers of `VerifyHeader` in the tree) — and state is
  **flat/MDBX**, not a live trie. Header download + PoW verification is a discrete pipeline stage
  feeding the flat-state executor, rather than a per-block inline check.
- Block *production* is likewise a stage: `aff859edc0 Mining stage (#1554)` (2021-03-23) introduced
  `SpawnMiningExecStage`. That staged-mining pipeline is now the vehicle for **bor** block
  production (`1da4d3abbf`, `f2d0118a33`, `74ec3a9db7`), i.e. mining-as-a-stage survived but points
  at PoA/sidechain production, not Ethash CPU mining.

**Net**: same Ethash *rules code* as geth, radically different *sync/execution scaffolding*
(staged sync + flat state). fukuii's ETC PoW verification should treat erigon's ethash package as a
structural cross-check only — the difficulty/DAG/verify logic is geth-derived, but the driving
model (staged, flat-state) is erigon-specific and **not** the ETC authority (core-geth is).

---

## 2. PoW mining — external-miner only, removed local mining early

erigon leaned on external miners/tooling from the start and **never shipped a production internal
CPU Ethash miner** in its own era. Two removal milestones bracket the story:

### 2021-03-28 — `8ccc6b2664` "Mining: remove local pow mining (from ethash), --miner.notify is required now" (#1617)
- Gutted the **local CPU sealing loop** from `consensus/ethash/sealer.go` (−138 net in that file;
  also `consensus.go`, `ethash.go`, `miner/worker.go`).
- After this, `--miner.notify` (push work to an external miner) became **required** to mine, and the
  seal cycle became non-blocking. The **`remoteSealer`** (getWork/submitWork/submitHashrate over
  channels) was kept — i.e. mining = *delegate to an external miner*, no in-process hashing.
- Landed 5 days after the staged **Mining stage** (`aff859edc0`, 2021-03-23): erigon's block
  production is a staged pipeline that *assembles* a block and hands sealing outward.

### 2025-11-11 — `eb67b7f7ff` "Remove PoW mining (#17813)"
- 784 deletions across 16 files. Removed the **outbound HTTP notify** to external miners:
  `remoteSealerTimeout`, `notifyURLs`, `notifyCtx`, `notifyWork()`, and the `startRemoteSealer(urls)`
  URL parameter (`git show eb67b7f7ff -- .../ethash/sealer.go`).
- **Kept** the getWork/submitWork RPC channels: current `Seal`
  (`execution/protocol/rules/ethash/sealer.go:47-64`) still pushes work onto `remote.workCh`, and
  `remoteSealer` retains `fetchWorkCh`/`submitWorkCh`/`fetchRateCh`. So an external miner can still
  *pull* work via getWork RPC and *submit* solutions; erigon just no longer *pushes* notifications.

### Current state
- **No internal miner.** `Seal` delegates to the `remoteSealer` (a getWork/`eth_submitWork` RPC
  interface). The only in-tree "sealer" that self-completes is `FakeEthash.Seal`
  (`.../ethash/fake.go:132`) for tests.
- Verdict for the use-case lens: erigon's PoW is oriented at **archival / RPC / data-serving**
  nodes, explicitly **not** the **mining-pool / validator** (block-producing) role. It can *serve*
  getWork to an external miner but is not itself a miner and never optimized for that path.

---

## 3. ETC / Ethereum Classic — never supported (evidence)

**Finding: erigon has NEVER supported Ethereum Classic / Mordor.** No support, no abandoned
attempt, no removed code. Evidence across the full 31,437-commit history:

| Probe | Command | Result |
|-------|---------|--------|
| Classic in commit msgs | `git log -i --grep=classic` | 4 hits, **all noise** |
| Classic in code (add/del) | `git log -S classic -i` | ~40 hits, **all Grafana "Classic" widgets** |
| Mordor (msg + code) | `git log -i --grep=mordor` / `git log -S mordor` | **0 hits** |
| ETChash (msg + code) | `git log -i --grep=etchash` / `git log -S etchash` | **0 hits** |
| ECIP-NNNN | `git log -i --grep=ecip` / `git log -S ECIP` | hits are **substring "recip"** (Recipient) etc. — no ECIP-NNNN |
| Current tree strings | `grep -ri "ethereum classic\|etchash\|ecip-\|mordor" --include=*.go` | **0 files** |

The four `-i --grep=classic` hits, disproven:
- `127553253e` / `7c71e936a7` — merges from **`eosclassicteam`** (a GitHub org, "Enable
  constantinople on Ropsten") — a **go-ethereum 2018 PR** (author Péter Szilágyi) inherited via the
  shared history; nothing to do with ETC.
- `7220985280` "execution/vm: skip PUSH-free words in JUMPDEST analysis" — "classic" used
  colloquially in the message.
- `f1dbc48b59` — a checkpoint.go dedup fix; incidental word match.

The registered network set confirms it — `execution/chain/spec/config.go:39-45` registers exactly:
**Mainnet, Sepolia, Hoodi, Gnosis, Chiado, Test, Bloatnet** (+ `polygon/chain/chainspecs/`:
amoy, bor-mainnet, mumbai, bor-devnet). All ETH-family, Gnosis (AuRa PoA), or Polygon (bor). No
`classic`, no `mordor`, no chain IDs 61/63.

---

## 4. geth inheritance vs. divergence (since erigon is a geth derivative)

- **Inherited from geth (shared 2013–2019 history)**: the entire Ethash implementation — DAG
  (`algorithm.go`), difficulty + bomb (`difficulty.go`), header verify (`consensus.go`), sealer +
  remoteSealer (`sealer.go`), getWork/submitWork RPC (`api.go`), and the **pluggable-engine**
  abstraction itself (`09777952ee #3817`, a geth commit). erigon did not author Ethash; it carried it.
- **Diverged (erigon-era)**:
  - **Renamed the abstraction** `consensus` → **`rules`**, and split the monolithic
    `consensus.Engine` into **`EngineReader`/`EngineWriter`** for read-only consensus
    (`ed22e06701 #17807`; see `consensus-engines.md`). geth keeps one monolithic interface.
  - **Removed local CPU mining** in 2021 (`#1617`) — geth kept an internal CPU miner far longer;
    erigon went external-only immediately.
  - **Staged sync + flat/MDBX state** — PoW header verification became a pipeline stage
    (`stageloop.go`/`forkchoice.go`) over flat state rather than geth's inline trie-based insert.
    This is the structural fork that makes erigon's PoW path materially different from geth's even
    pre-merge, despite identical rules code.
  - **The E3 removal-and-revert saga** (a divergence attempt that failed):
    `af4dc9d98d "E3: Remove Proof-Of-Work Consensus code (#11556)"` (2024-08-10, −5,694 lines,
    stripped all Total-Difficulty/difficulty testing as "a PoW concept") was **reverted 5 days
    later** by `c7469426b1 (#11628)` (2024-08-15) because it crashed Hive tests ("Bad Hash on
    NewPayload"). Erigon 3 *tried* to drop PoW consensus code entirely and couldn't — so the
    geth-inherited Ethash package remains in the tree today, even though erigon does not mine and
    supports no PoW mainnet-family network of its own beyond historical ETH verification.

---

## Authority note

**erigon is NOT a PoW/ETC authority.** For fukuii's ETC PoW work it is a **weak, structural-only,
non-miner-oriented cross-reference** — well below core-geth and even below besu-history:

- **core-geth** — the *sole* miner-adopted ETC client and the frozen **byte-authority** for
  ETChash + ECIP-1017/1099/1100/1111/1122. erigon has **zero** ECIP awareness.
- **besu-history (2019–2026)** — a second JVM ETC implementation; the primary *structural* ETC
  reference. erigon offers no ETC structure at all.
- **geth pre-merge** — the ancestral Ethash reference. **erigon's Ethash is a copy of exactly this**
  — so consulting erigon's ethash package is nearly redundant with consulting geth's, minus geth's
  own later evolution.

Where erigon *is* worth citing for PoW-adjacent design: (a) the **external-miner-only** posture
(getWork/submitWork with the internal miner removed) as a data point for the archival/RPC node-role,
and (b) the **staged-sync driving of header verification** as a structurally different way to run
PoW verify than geth's inline insert. Neither is an ETC value/behavior authority; both are
"characterize the approach" material for the use-case lens, not alignment targets.

## Gotchas for anyone re-running this survey

- `git log -S classic` / `-i --grep classic` is **polluted** by `eosclassicteam` (geth-inherited)
  and Grafana "Classic" dashboards — filter them; neither is Ethereum Classic.
- `git log -S ECIP` matches the substring "recip" (Recipient) — use case-sensitive `ECIP` and still
  eyeball; there are **no** ECIP-NNNN references.
- The ethash package's `consensus.go` was renamed `rules.go` (`#17807`) — grep both names when
  archaeology crosses the Nov-2025 boundary.
- Don't be fooled by the *present* `sealer.go`/`Seal` existing: it delegates to a getWork
  `remoteSealer`, it is not an internal CPU miner (removed 2021, `#1617`).
