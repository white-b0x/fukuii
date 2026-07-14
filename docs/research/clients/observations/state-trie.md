# Observations — state-trie
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/state-trie.md + initial-assessment §1d (trie spectrum) + storage-persistence observation._

> Scope: this doc is about **trie / commitment structure** — how each client organizes the Merkle-Patricia
> commitment, couples (or decouples) it from persistence, and computes the state root. The **KV backend**
> (RocksDB CFs vs MDBX vs Pebble, node key layout on disk, iterator safety, schema-versioning) lives in
> [`storage-persistence.md`](storage-persistence.md) and is not re-derived here. Where the two touch (path vs
> hash node keying, flat-state CFs) this doc gives the **trie-level** view; the storage doc gives the byte view.

## Comparison table
| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | Authoritative |
|---|---|---|---|---|---|---|---|
| **Trie structure** (node-store MPT vs flat/commitment-on-demand vs nibble-path) | Node-store MPT (4 node types); hashdb hash-keyed **or** pathdb path-keyed | **Inherits geth verbatim** — hash-keyed node-store MPT, hex secure trie; zero ETC divergence | **Two formats**: Forest = hash-keyed full node store; Bonsai = flat live values + thin proof trie | **Flat, zero leaf-trie** — state in versioned Domains by plain key; only branch nodes persisted | Node-store MPT (`PatriciaTree`), **Hash or HalfPath** node key scheme, in-memory pruning `TrieStore` | **No resident trie** — streaming `HashBuilder` over sorted hashed leaves; intermediate nodes cached by nibble path | **go-ethereum** (canonical MPT) |
| **Trie↔persistence coupling** | **Decoupled**: `Trie` is a pure fn, emits a `NodeSet`; `triedb` backend owns storage policy | Same — geth's decoupling inherited | **Decoupled**: `MerkleTrie` depends only on a `NodeLoader`/`NodeUpdater` function pair | **Decoupled** via a 4-method `PatriciaContext` (Branch/PutBranch/Account/Storage) | **Decoupled**: `PatriciaTree` talks to `IScopedTrieStore`; `NodeStorage` translates node identity→key scheme | **Decoupled**: `StateRoot` generic over `TrieCursorFactory`+`HashedCursorFactory` (MDBX / in-mem / mock) | **besu** (interface-shape) / **go-ethereum** (NodeSet) |
| **WorldState / account-model abstraction** | `StateDB` + `stateObject` (journal + 4-tier storage cache); talks to trie via `Database` seam | Inherits geth `StateDB`; account layout ecosystem-shared | **`WorldState`→`MutableWorldState`→`WorldUpdater`** interface stack; EVM never sees trie vs flat | `SharedDomains` façade over the Domains; account is a flat record, not a trie leaf | **`WorldState`** = `StateProvider`+`PersistentStorageProvider`; per-block change journal | `HashedPostState` overlay + `TrieInput`; revm `BundleState` hashed into leaves | **besu** (cleanest decoupling) |
| **Flat-state accelerator** | **Yes** — `snapshot` (hash mode) / pathdb flat layer; `hash(key)→value` mirror, O(1) reads, trie as fallback | Same mechanism as geth (circa early-2025 vintage) | Bonsai **is** flat-first (values live flat; trie only for root); Forest has none | State **is** flat (no accelerator needed — flat is the primary store) | Experimental `State.Flat` (flat + retained thin trie); default is node-store | State **is** flat hashed tables; trie-node cache is the accelerator over them | **go-ethereum** (accelerator-under-fallback) |
| **Commitment computation** (resident vs streaming vs on-demand) | **Resident** MPT, committed bottom-up (storage roots→accounts→state root) | Resident, geth-identical | **Resident** (Forest walks trie); Bonsai **recomputes root** from flat + thin trie per block | **On-demand**: transient grid `HexPatriciaHashed` folds touched keys, fetches leaves on demand | **Resident** dirty-node cache; `RootHash` via `ResolveKey` Keccak recompute | **Streaming `HashBuilder`** over sorted leaves; sparse-trie recompute concurrent with execution | **erigon** (on-demand) / **reth** (streaming) |
| **Witnesses / stateless / sparse** | `Trie.Prove`/`VerifyRangeProof` (range proofs underpin snap sync) | Inherited | Visitor-based `ProofVisitor`/`getValueWithProof`; SNAP range collectors | Proofs **rebuilt** (leaves/nodes not navigable) — `CollapseTracer` witness plumbing | Proof via node walk; snap-serve friendly under HalfPath | **`SparseStateTrie`** (blind/revealed via proofs) + `TrieWitness` for stateless execution | **reth** (sparse/witness) |
| **Reorg rollback** (trie-log / reverse-diff) | pathdb **reverse-diff history** (layer tree + state history, ~90k blocks); hashdb none | hashdb — none (geth vintage predates path default) | **TrieLog** — per-block `{prior, updated}` leaf journal; `rollForward`/`rollBack` engine walks it to a shared ancestor | Versioned Domains — **unwind replays domain changesets** (`WithHistory()` suppresses branch writes) | Reorg-boundary-gated cache eviction (`_maxDepth`); no persisted reverse-diff, reconstructs from dirty cache | Overlay/prefix-set model; not-yet-persisted block rooted via in-memory overlay | **besu** (TrieLog) / **go-ethereum** (pathdb reverse-diff) |

## Approach catalog (use-case-aware)
| Approach | Clients using it | Good for (use-case / node-role) | Verdict | Why |
|---|---|---|---|---|
| **Node-store MPT** (hash-keyed node graph) | geth hashdb, core-geth, besu Forest, nethermind Hash, **fukuii** | archival / data-serving+RPC; historical-state; multi-network legacy | **DEFAULT** (fukuii's current camp) / **OPTIONAL(archival)** for the pure hash-keyed variant | The canonical, simplest model — subtree dedup + full historical trie; but unbounded growth + fragile refcount GC. fukuii already lives here |
| **Path/nibble-keyed node store** | geth pathdb, nethermind HalfPath (default), reth nibble-path | full / pruned nodes; block-processing hot path | **DEFAULT-candidate** | One path = one write (no hash-recompute cascade), better block-cache locality, bounded disk + cheap online pruning. The direction all three independently converged on. Storage-layer keying — see storage doc |
| **Flat state + commitment-on-demand** | erigon (zero-leaf Domains), reth (HashBuilder streaming) | archival with minimal disk; high-throughput; data-serving | **OPTIONAL(archival/perf)** — NOT the default hot path | State write = one KV put, Merkle cost paid once per block over touched keys only; kills node write-amplification. But proofs must be *rebuilt*, and (erigon) it's MDBX-coupled — a ground-up re-architecture, not a swap |
| **Bonsai thin-trie + TrieLog** (flat live values + reverse-diff journal) | besu Bonsai (nethermind `State.Flat`, reth partial) | light / end-user; pruned full nodes; fast state reads; custody | **OPTIONAL(light/custody)** | Flat reads without a trie walk + bidirectional reorg rollback via `{prior,updated}` leaf diffs — no archival trie kept. But historical state is *reconstructed* from trie-logs (pruned logs = unservable history). fukuii already has flat-storage overlay pieces |
| **Trie-independent WorldState interface** (besu `MerkleTrie`/`WorldUpdater`; geth `Trie`+NodeSet) | besu, go-ethereum, nethermind, reth (all decouple) | all roles — enables coexisting archival + pruned modes | **DEFAULT** (the decoupling itself) | The EVM sees a `WorldState`/`WorldUpdater`; the trie depends only on a load/store function pair. This one seam lets a client swap an entire storage philosophy (hash-store ↔ flat+journal) under a fixed EVM contract |
| **Flat-state accelerator under a trie fallback** | go-ethereum (snapshot/flat layer) | archival/RPC hot reads, CEX/custody, validator | **DEFAULT** (adopt as default-on) | O(1) warm reads via `hash(key)→value` mirror; trie stays authoritative + fallback, so correctness never depends on the accelerator being complete. Low-risk: additive, degrades gracefully |
| **Parallel / concurrent state-root** | reth (`ParallelStateRoot` fallback; sparse-trie `state_root_task` primary) | validator / block-building throughput; `newPayload` | **OPTIONAL(perf/validator)** | Storage tries are independent per account → compute concurrently; sparse trie computes the root *concurrently with execution* so it's ready when the block finishes. Depends on the streaming/flat substrate |
| **Sparse trie / witnesses** | reth (`SparseStateTrie`, `TrieWitness`) | stateless / light clients; custody; witness generation | **OPTIONAL(light/custody)** | A blind→revealed partial trie fed by proofs is one data structure that serves both stateless witnesses and concurrent-with-execution root computation. Needs proof plumbing fukuii doesn't have yet |

## Best-practice synthesis
fukuii is a **hash-keyed node-store MPT** (geth-hashdb / besu-Forest / nethermind-Hash camp), backed by RocksDB
with a partial flat-state overlay (`FlatAccountStorage`/`FlatSlotStorage`). The state-root **algorithm** itself
(node encoding, `<32`-byte inlining, keccak key hashing, RLP) is consensus-load-bearing and must stay
geth/core-geth byte-identical — those are DEFAULT-not-negotiable, not menu items. What *is* a menu is the
**structure around** that algorithm:

**DEFAULT (adopt):**
- **Decouple the trie from persistence behind a WorldState-style interface.** This is the single highest-value
  structural move and every non-erigon client already does it: geth's pure-function `Trie` that emits a
  `NodeSet` while the `triedb` backend owns storage policy; besu's `MerkleTrie` (depends only on a
  `NodeLoader`/`NodeUpdater` pair) under a `WorldState`→`MutableWorldState`→`WorldUpdater` stack the EVM talks
  to. besu is fukuii's closest JVM structural target. This seam is the precondition for *every* optional mode
  below — it's what makes archival vs pruned a config choice, not a rewrite.
- **Flat-state accelerator under a trie fallback** (geth's snapshot/flat layer). Low-risk, additive: a
  `hash(key)→value` mirror gives O(1) warm reads while the trie stays authoritative and as fallback, so
  correctness never depends on the accelerator being complete. fukuii's overlay is a partial start on this.

**OPTIONAL (role-gated):**
- **OPTIONAL(archival/perf)** — flat state + commitment-on-demand (erigon zero-leaf Domains / reth streaming
  `HashBuilder`). Kills node write-amplification for archival/high-throughput roles, but it is **not** the
  default hot-path choice for a hash-keyed client: proofs get expensive and (erigon) it's an MDBX-coupled
  re-architecture.
- **OPTIONAL(light/custody)** — Bonsai-style thin-trie + TrieLog reorg-rollback (besu) and sparse-trie /
  witnesses (reth). Flat fast reads, bounded reorg window via bidirectional leaf diffs, stateless-friendly —
  for light/end-user and custody roles where historical archival trie is not required.
- **OPTIONAL(validator/perf)** — parallel / concurrent state-root (reth) once a streaming/flat substrate exists.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)
Seeds, not verdicts:

- **fukuii's hash-keyed node store = SR-09 (mpt).** SR-09 and SR-05 (db / storage-persistence) are **cyclic** —
  the node key scheme (hash vs path) and the flat-state overlay live at the trie/KV boundary, so SR-09 must be
  scheduled *after* SR-05 but co-designed with it. core-geth confirms **ETC introduces zero state-structure
  divergence** — this whole layer tracks geth-canonical semantics, and network-specific behavior (ECIP-1017
  rewards, fork-selected VM rules) is applied *upstream* as `StateDB`/state mutations, never in the trie.
- **Decouple trie from persistence behind a WorldState-style interface** — the low-risk, high-leverage default
  adopt. besu's `MerkleTrie` + `WorldUpdater` stack is the apples-to-apples JVM shape; validate byte output
  against go-ethereum. This is the enabling seam for any later pruned/archival mode split.
- **Flat-state accelerator is the low-risk default adopt** — fukuii already carries `FlatAccountStorage`/
  `FlatSlotStorage` as a read overlay; geth's snapshot-under-trie-fallback is the pattern for promoting it to
  default-on without correctness risk.
- **Path-keying and full-flat are the larger OPTIONAL bets** — path/nibble node keying (geth pathdb /
  nethermind HalfPath / reth) is the block-processing-hot-path direction worth offering as a mode; full flat
  Domains (erigon) is an archival-role aspiration, not a near-term change to the hot path. Both are storage-
  layer decisions co-scheduled with SR-05.
- **Bonsai thin-trie + TrieLog + sparse witnesses** map to the light/end-user and custody roles — Phase-3/4
  comparison target for whether to promote fukuii's flat overlay into a first-class pruned-node mode.
