# Mining-protocol survey — non-EVM PoW clients + pool-layer Stratum

_Commits documented: bitcoin `8f4a3ba89` (master, 2026-05-02), monero `22d35df8e` (master,
2026-04-30), zcash `1396d7920` (master, 2026-04-30). Documented 2026-07-13. Sources are full
git clones under `/media/dev/2tb/dev/reference-clients-pow/{bitcoin,monero,zcash}` — no fukuii
overlay concern (non-fukuii repos). Read-only survey; no fukuii source edits._

_Scope: this doc owns the **pool-layer / Stratum** reference material and the **non-EVM PoW**
mining protocols (Bitcoin/Monero/Zcash), per `initial-assessment.md` §3e. The EVM node-side
`getWork`/`submitWork` survey (geth/core-geth — the miner-adopted ETC reference) is a **parallel
agent's** deliverable and is NOT duplicated here. Use-case lens per `README.md`: characterize each
approach by what node-role it serves (default + optional), don't just rank._

---

## 0. The two-layer model (keep these distinct)

The whole point of surveying non-EVM clients is that they make explicit a split the EVM node-side
`getWork` interface blurs:

| Layer | Who owns it | What it does | Reference sources |
|-------|-------------|--------------|-------------------|
| **Node ↔ work-source RPC** | The full node (`bitcoind`/`monerod`/`zcashd`, geth/core-geth) | Assemble a candidate block/template from the mempool, hand out work, accept solved blocks | Bitcoin `getblocktemplate`, Monero `get_block_template`, Zcash `getblocktemplate`; EVM `eth_getWork` (parallel doc) |
| **Pool ↔ miner protocol** | Separate **pool software** (never the node) | Distribute work to many miners, collect shares, do difficulty targeting, pay out | **Stratum v1 / v2** — lives in pool servers + miner firmware, NOT in any reference node |

**Critical finding, repeated across all three non-EVM clients:** none of Bitcoin Core, monerod, or
zcashd ships a Stratum server. The node exposes a template RPC; pool software (a separate process)
consumes that RPC and speaks Stratum to miners. This is the same architecture the miner-adopted EVM
clients use (geth/core-geth expose `eth_getWork`; ethminer/pools speak Stratum on top). besu's
in-node Stratum server (documented in the EVM half) is the *outlier*, and per the authority map it
was never miner-adopted → cautionary, not the pattern to copy.

---

## 1. Bitcoin Core — `getblocktemplate` (the modern node↔pool contract)

### getblocktemplate (BIP 22/23/9/145)
- `src/rpc/mining.cpp:618` — `getblocktemplate`. The node-side work protocol; the successor to the
  removed `getwork`. Help text points at BIPs 22, 23, 9, 145 (`mining.cpp:622-627`).
- Two modes via the `mode` key: **`template`** (hand out work) and **`proposal`** (BIP 23 — miner
  submits a candidate block for the node to validate before committing hashpower). `capabilities`
  and `rules` (e.g. `segwit`, `mining.cpp:637-641`) negotiate features.
- The response is a **full transaction-selected template**, not just a header: `previousblockhash`,
  a `transactions` array (each with `data`/`txid`/`hash`/`depends`/`fee`/`sigops`/`weight`),
  `coinbasevalue`, `coinbaseaux`, `target`, `bits`, `height`, `mintime`/`curtime`, `mutable`
  (what the miner may change: `time`/`transactions`/`prevblock`), `noncerange`,
  `sigoplimit`/`weightlimit`, and `default_witness_commitment` (`mining.cpp:700-700`). The pool
  builds the coinbase itself (inserting its payout + extranonce), which is what makes pooled mining
  decentralized at the transaction-selection layer — **the pool, not the node, decides payout**.
- **Longpoll**: `longpollid` (`mining.cpp:638`) lets a miner hold a request open until the template
  would change materially (new tip / significant fee delta), avoiding poll spin.

### getwork — removed
`getwork` (the original one-header-at-a-time protocol) is **entirely gone** from the tree
(no hits in `src/rpc/mining.cpp`); Bitcoin deprecated/removed it years ago in favor of GBT because
`getwork` couldn't express full-block transaction selection (it handed out only an 80-byte header,
forcing all transaction-selection policy into the node). This is the historical arc EVM clients are
still on: `eth_getWork` is a `getwork`-shaped header-only interface; Bitcoin already moved past it.

### Block submission
- `src/rpc/mining.cpp:1060` — `submitblock` (BIP 22 submission path): full serialized block in,
  accept/reject reason out.
- `src/rpc/mining.cpp:1111` — `submitheader`: validate just a header (used for header-first flows).
- `src/rpc/mining.cpp:504` — `prioritisetransaction` lets an operator bias template selection
  (fee-bump a txid in-memory) — a policy lever the pool/node operator controls.

### The C++ `Mining` interface (and why it matters for SV2)
`src/interfaces/mining.h` factors block-template production into a clean abstraction, not just an RPC
handler:
- `interfaces/mining.h:96` — `class Mining` with `createNewBlock()` (`:135`), `getTip()` (`:108`),
  `waitTipChanged()` (`:121`), `checkBlock()` (`:155`), `isInitialBlockDownload()` (`:105`).
- `interfaces/mining.h:34` — `class BlockTemplate` with `getBlockHeader()`, `getBlock()`,
  `getTxFees()`, `getCoinbaseMerklePath()` (`:53`), `submitSolution(version,timestamp,nonce,coinbase)`
  (`:73`), and **`waitNext()`** (`:86`) — a native, in-process longpoll ("give me the next template
  when the tip changes or fees rise by X", `interfaces/mining.h:78-86`).
- This same interface is **exported over IPC** (`src/ipc/capnp/mining.capnp:20` —
  `interface Mining $Proxy.wrap("interfaces::Mining")`). That IPC surface is Bitcoin Core's
  multiprocess mining boundary and is the substrate an **external Stratum v2 Template Provider**
  drives — i.e. Bitcoin's answer to "should the node speak the pool protocol?" is *no*: expose a
  typed template interface (in-proc + IPC) and let a separate SV2 process own the pool protocol.

### Architecture (node RPC ↔ pool ↔ Stratum ↔ miner)
```
bitcoind ──getblocktemplate/submitblock──▶ pool server (e.g. CKPool, BTCPool)
   (mempool + tx selection)                    │
                                               └──Stratum v1/v2──▶ miner firmware / ASICs
                                                       (shares, vardiff, payout)
```
Bitcoin Core deliberately stops at the RPC/IPC boundary. Pools run their own Stratum servers on top.

---

## 2. Stratum v1 & Stratum v2 (the pool-layer protocol itself)

Neither version lives in a reference **node**; both are described from the protocol structure and
the pool/miner ecosystem. This is the layer fukuii's mining-pool use case needs to interoperate with.

### Stratum v1 (the ubiquitous incumbent)
- Line-delimited **JSON-RPC over a persistent TCP socket**, pool→miner. Core method set:
  `mining.subscribe` (miner connects, gets a subscription + extranonce1), `mining.authorize`
  (worker login), `mining.notify` (pool pushes a new job: prevhash, coinbase parts `coinb1`/`coinb2`,
  merkle branches, version/nbits/ntime, `clean_jobs` flag), `mining.submit` (miner returns a share:
  job_id + extranonce2 + ntime + nonce), `mining.set_difficulty` (vardiff — pool tunes per-miner
  share difficulty), and `mining.set_extranonce`.
- **The pool builds the coinbase, the miner iterates extranonce2 + nonce.** The pool derives the
  coinbase from `coinb1 || extranonce1 || extranonce2 || coinb2`, so each miner searches a disjoint
  nonce space. Shares (below pool difficulty, above network difficulty only occasionally) prove work
  for payout accounting.
- **EVM parallel:** the ETC/ETH variant ("EthereumStratum" / stratum for ethash) carries the getWork
  triple (headerhash, seedhash, target) in `mining.notify` and a nonce/mixhash in `mining.submit` —
  same shape, different work primitive. That variant is what ethminer/pools actually speak on top of
  geth/core-geth's `eth_getWork`.
- **Known weaknesses (why v2 exists):** cleartext (hashrate hijacking / share-withholding are easy),
  the pool alone chooses which transactions go in the block (mining centralization of tx-selection),
  and it's chatty/inefficient for high job-churn.

### Stratum v2 (SV2 / the binary successor)
- A **binary, encrypted (Noise handshake)** protocol, restructured into sub-protocols rather than one
  channel:
  1. **Mining Protocol** — pool↔miner job distribution (binary, lower overhead than v1's JSON).
  2. **Job Declaration Protocol (JDP)** — lets the **miner declare its own block template** (own
     transaction selection) to the pool, then mine it. This is SV2's headline decentralization win:
     tx-selection moves back from the pool to the miner.
  3. **Template Distribution Protocol (TDP)** — talks to the node's template provider (in Bitcoin,
     the `interfaces::Mining` IPC surface in §1) to fetch/update templates.
- SV2's reference implementation is the **Stratum Reference Implementation (SRI)** — a *separate*
  project, not in Bitcoin Core. Bitcoin Core's contribution is the **template-provider IPC interface**
  (`src/ipc/capnp/mining.capnp`, `src/interfaces/mining.h`) that a TDP endpoint drives; the SV2
  server/proxy/miner roles are external.
- **Design lesson for fukuii:** the modern best-practice answer to "how should a node support pools"
  is *not* "embed a Stratum server" — it's "expose a clean, typed, IPC-capable block-template
  interface and let pool software own the pool protocol." Node stays consensus-focused; the pool
  layer is pluggable and independently upgradeable (v1→v2 without a node change).

---

## 3. Monero — `get_block_template` + RandomX (CPU-oriented, different ecosystem)

### Mining RPC
- `src/rpc/core_rpc_server.cpp:1940` — `on_getblocktemplate`
  (`COMMAND_RPC_GETBLOCKTEMPLATE`). Response fields
  (`src/rpc/core_rpc_server_commands_defs.h:958-982`):
  - `blocktemplate_blob` — the full serialized block template.
  - `blockhashing_blob` — **just the bytes the miner hashes** (the pre-hashing pre-image); Monero
    hands the miner exactly the hashing input, so a pool/miner never has to re-serialize.
  - `reserved_offset` — the byte offset of a **reserved scratch region** in the template. The caller
    passes `reserve_size` or an explicit `extra_nonce`; the pool writes its extranonce/payout marker
    into that reserved slot (`core_rpc_server.cpp:1954-1978`). This is Monero's equivalent of
    Bitcoin's pool-built coinbase — a pool-controlled mutable region for share attribution.
  - `seed_hash` / `next_seed_hash` — **RandomX seed rotation** (see below).
  - `difficulty`/`wide_difficulty`, `prev_hash`, `height`.
- `src/rpc/core_rpc_server.cpp:2280` — `on_submitblock` (`SUBMIT_BLOCK`): submit the solved block.
- `src/rpc/core_rpc_server.cpp:2036` — `on_getminerdata` (`GET_MINER_DATA`): a leaner,
  template-free feed (major_version, height, prev_id, seed_hash, difficulty, median_weight,
  already_generated_coins, tx_backlog) designed for **P2Pool**, which assembles its own templates
  rather than repeatedly calling the heavier `get_block_template`.
- `src/rpc/core_rpc_server.cpp:2134` — `on_add_aux_pow` (`ADD_AUX_POW`): merge-mining support
  (inject an auxiliary-PoW merkle root into the template).
- Built-in daemon miner: `on_start_mining` / `on_stop_mining` / `on_mining_status`
  (`core_rpc_server.cpp:1450/1501/1521`) — monerod can mine itself (solo), unlike EVM nodes that
  increasingly dropped internal mining.

### RandomX (the differentiator)
- `src/crypto/rx-slow-hash.c` wraps `external/randomx`. RandomX is a **CPU-optimized, ASIC-resistant**
  PoW (random program execution against a multi-GB dataset) — a fundamentally different ecosystem
  from Ethash's GPU/memory-hard model: Monero deliberately targets commodity CPUs to keep mining
  decentralized, so its pool ecosystem (and P2Pool) is CPU/home-miner oriented, not ASIC-farm oriented.
- **Seed rotation** (`rx-slow-hash.c:136-137`): `SEEDHASH_EPOCH_BLOCKS = 2048`,
  `SEEDHASH_EPOCH_LAG = 64`. The RandomX dataset is keyed by a `seed_hash` that changes every ~2048
  blocks (with a 64-block lag), and the node maintains a `main`/`secondary` seed pair
  (`rx-slow-hash.c:54-76`) so miners can pre-compute the next epoch's dataset — hence
  `seed_hash` **and** `next_seed_hash` in the template. Ethash has an analogous DAG-epoch concept
  (30000-block epochs); the template-level `next_seed_hash` hand-off is the notable pattern for
  smooth epoch transitions without a mining stall.

### Monero's approach vs Bitcoin's
Monero pushes toward node-native, low-friction solo/decentralized mining: it hands the miner the
exact hashing blob (`blockhashing_blob`), a reserved pool region (`reserved_offset`), a lean P2Pool
feed (`get_miner_data`), and a built-in miner. P2Pool (a separate sidechain-based decentralized pool)
is the ecosystem's answer to pool centralization — conceptually where SV2's Job Declaration aims, but
shipped and adopted.

---

## 4. Zcash — `getblocktemplate` + Equihash

- `src/rpc/mining.cpp:431` — `getblocktemplate` (a Bitcoin-GBT descendant; zcashd forked from Bitcoin
  Core). Same longpoll/proposal/capabilities shape (`mining.cpp:454-457`), same `coinbasetxn`
  variant (`mining.cpp:492`, `529`).
- `src/rpc/mining.cpp:848` — `submitblock`. `getblocksubsidy` (`:915`) and `getmininginfo` (`:327`,
  reporting `localsolps`/`networksolps` — **solutions/sec**, Equihash's unit, not hashes/sec).
- **Equihash** PoW (`src/consensus/params.h:493-494` `nEquihashN`/`nEquihashK`;
  `src/chainparams.cpp:99` `N=200, K=9` mainnet; `:820` `N=48,K=5` regtest). Equihash is a
  **memory-hard, solution-based** PoW: the miner finds an Equihash *solution* that goes in the block
  header's `solution` field (not just a nonce), which is why the header carries a solution and mining
  metrics are "Sol/s." The GBT template omits the solution (the miner produces it); the solver check
  lives at `src/rpc/mining.cpp:223-260` (`nEquihashN` fed into the solver, `solutionTargetChecks`).
- **Stratum:** none in the daemon (no `stratum` hits in `src/`). Zcash pools run external Stratum
  servers speaking a Zcash-specific variant (jobs carry the Equihash-parameterized header; miners
  return `solution`+`nonce`). Same node-stops-at-GBT architecture as Bitcoin.

---

## 5. Synthesis for fukuii (ETC / Ethash mining-pool use case)

fukuii is ETC/Ethash — GPU/memory-hard, block-number PoW, whose **byte- and production-authority is
core-geth** (the sole miner-adopted ETC client; its `eth_getWork` path is what ETC miners actually
run, surveyed in the parallel EVM-node-side doc). This survey answers: *what does the non-EVM +
Stratum world offer that the EVM node-side `getWork` doesn't, and should fukuii adopt it?*

### What the non-EVM clients do that EVM node-side getWork doesn't
1. **Full-block templates instead of header-only work.** Bitcoin/Monero/Zcash hand out a
   transaction-selected template (`getblocktemplate`) where the **pool** builds the coinbase and can
   re-select transactions; `eth_getWork` hands out only `(headerhash, seedhash, target)` — the node
   did all tx-selection. GBT is strictly more expressive and is where SV2's decentralization gains
   come from. **Verdict for fukuii: OPTIONAL(mining-pool).** ETC's miner-adopted reality is
   `getWork`; matching core-geth is the DEFAULT and non-negotiable for miner interop. A GBT-style
   full-template RPC is a valuable *additional* mode for pools that want tx-selection control, but it
   is not what ETC miners run today — offer it, don't default to it.
2. **Reserved/mutable pool region baked into the template** (Monero's `reserved_offset`, Bitcoin's
   pool-built coinbase). Clean share-attribution without the node knowing about the pool.
3. **A lean pool feed distinct from the heavy template call** (Monero `get_miner_data` for P2Pool).
   If fukuii ever wants a decentralized-pool story, a lightweight feed beats hammering the full
   template RPC.
4. **`next_seed_hash` in the work payload** (Monero) for smooth epoch pre-computation — fukuii's
   Ethash DAG-epoch transition could hand miners the next epoch's seed proactively rather than letting
   them discover it.
5. **A typed, IPC-capable block-template interface** (Bitcoin `interfaces::Mining` +
   `mining.capnp`) as the *seam* pool software plugs into — the architecturally clean alternative to
   an in-node Stratum server.

### Should fukuii offer a native Stratum server?
**No — default to `getWork` + external pool software (the geth/core-geth, miner-adopted model);
treat any in-node Stratum as OPTIONAL and cautionary.** The evidence is uniform:
- **Every** reference PoW node surveyed (Bitcoin, Monero, Zcash) deliberately stops at the
  template/`getWork` RPC and lets *separate* pool software own Stratum. That is the battle-tested
  architecture.
- The one EVM client that embedded a Stratum server (**besu**) was **never miner-adopted** (per the
  authority map in `initial-assessment.md` §2/§3e) — a cautionary data point, not a model.
- Stratum (v1→v2) evolves on its own cadence; coupling it into the consensus node freezes the node to
  one pool-protocol version and bloats the consensus-critical binary with a network-facing,
  DoS-exposed service.

**Recommended shape (default + optional, per the omni-client lens):**
- **DEFAULT (mining-pool / validator role):** a correct, core-geth-byte-compatible
  `eth_getWork`/`eth_submitWork`(+`eth_submitHashrate`) path — this is the floor; ETC miners and every
  ETC pool already speak it. Everything else is additive.
- **OPTIONAL(mining-pool):** a **typed, IPC/RPC block-template seam** (Bitcoin `interfaces::Mining`
  pattern) so external pool/Stratum software — including a future SV2 template provider — can drive
  fukuii's block production without the node itself speaking Stratum. This is the forward-looking
  investment and keeps the pool layer pluggable.
- **OPTIONAL(mining-pool), lower priority:** a GBT-style full-template RPC (pool-controlled
  tx-selection) and a lean P2Pool-style feed, if a decentralized-pool use case materializes for ETC.
- **OBSOLETE / do-not-build:** an in-node Stratum server (besu's un-adopted path). Skip it; the whole
  ecosystem puts Stratum in separate pool software for good reasons.

### Feeds into Phase 2 / Phase 4
- Approach-catalog rows for `rpc-api`/mining: `getWork` (DEFAULT, mining-pool — miner-adopted) vs
  full-template GBT (OPTIONAL, pool tx-selection) vs in-node Stratum (OBSOLETE, cautionary via besu).
- A Phase-4 seed: **typed block-template interface as the pool seam** (Bitcoin's `interfaces::Mining`/
  IPC) — the clean substrate for external Stratum/SV2 without embedding the pool protocol.
- Cross-ref: the EVM node-side `getWork` authority (core-geth) is owned by the parallel EVM survey;
  this doc's job was the pool-layer + non-EVM contrast, which confirms fukuii should stay on the
  `getWork` + external-pool architecture that ETC miners actually run.
