# erigon — state-trie
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

> Repo layout note: the historical `erigon-lib/state/` and `erigon-lib/commitment/`
> paths named in older SR notes have moved. In this checkout the Domains live under
> `db/state/` (with the `SharedDomains` orchestration in `db/state/execctx/`) and the
> commitment trie lives under `execution/commitment/`. All citations below use the
> current paths.

## Architecture summary

Erigon sits at the **far end of the trie spectrum, opposite geth's node-store MPT**.
It does **not** persist a Merkle-Patricia trie of the state. Instead:

1. **State is flat.** Accounts, storage slots, and contract code each live in their
   own **Domain** — a versioned key→value store keyed by the *plain* key
   (20-byte address for accounts, address+slot for storage), **not** by
   `keccak(key)` and **not** embedded in trie nodes. There are no preimage tables,
   no hashed-state tables, no per-node records. The account record itself
   (`Nonce, Balance, Root, CodeHash, Incarnation`) is RLP-ish encoded and stored
   directly under the address (`execution/types/accounts/account.go:35`).

2. **The state root is computed on demand** from that flat state by a transient,
   grid-based **HexPatriciaHashed** trie. When a block finishes, the set of touched
   plain keys is fed to `Trie.Process(...)`; the trie unfolds only the branch nodes
   along the touched paths, **fetches the leaf account/storage values on demand**
   from the flat Domains, hashes them, folds back up, and yields the root. The only
   trie structure ever written back to disk is **branch nodes**, stored in a
   dedicated **CommitmentDomain** keyed by nibble prefix.

3. **No leaf nodes, no full trie, are stored.** The four-method
   `PatriciaContext` interface (`execution/commitment/commitment.go:127`) makes this
   explicit: `Branch`/`PutBranch` read/write branch nodes; `Account`/`Storage`
   *fetch* leaf data from flat state (they have no `Put` counterpart on the trie
   side — leaves are never trie-owned). This is the "zero leaf-trie, commitment
   on-demand" model.

The consequence: writing a block touches N flat-domain keys + the branch nodes on
their paths, instead of rewriting O(N·log) trie nodes. That kills trie-node write
amplification (great for archival + high throughput) but means a Merkle proof
requires *rebuilding* the relevant trie paths rather than reading stored nodes.

This is the "E3" architecture. The legacy geth-style `PlainState` / `HashedAccounts`
/ `HashedStorage` / changeset tables are now **deprecated**
(`db/kv/tables.go:479-495`) — erigon migrated away from a hashed-state + preimage
model to this flat, incarnation-free Domains model.

## Key types / interfaces / files

### The Domains (flat state)
- `db/kv/tables.go:711-720` — the six-value `Domain` enum. `AccountsDomain=0`,
  `StorageDomain=1`, `CodeDomain=2`, `CommitmentDomain=3` ("Merkle Trie"),
  plus `ReceiptDomain`/`RCacheDomain`. `StateDomains` = the first four.
- `db/state/domain.go:73` — `type Domain struct` — one versioned KV store
  (`.kv` value files + `.bt`/`.kvei` accessor+bloom, layered over an MDBX
  `ValuesTable`) with an embedded `*History` for time-travel/unwind. The
  commitment domain additionally carries a `branchCache` (`domain.go:94`).
- `execution/types/accounts/account.go:35` — `type Account struct` (the flat
  account record). `DeserialiseV3` / `SerialiseV3` at `:607` / `:644`. `Root` is
  the *storage-trie* root carried in the account, but the account itself is NOT
  stored in an account trie — it is stored flat under its address.

### SharedDomains (the write/read orchestrator)
- `db/state/execctx/domain_shared.go:97` — `type SharedDomains struct` — the
  transaction-scoped facade over all domains for one execution run. Holds the
  commitment context (`sdCtx`), an in-memory write batch (`mem`), a branch cache,
  changeset accumulators for unwind, and a block-metadata overlay.
- `domain_shared.go:405` `domainPutNoLock(...)` — a `DomainPut` records the new
  value in the flat domain **and** "touches" the plain key so the commitment trie
  knows to recompute that path.
- `domain_shared.go:1679` `ComputeCommitment(...)` — the public entry point: flush
  pending deferred branch updates into the right block's changeset, then delegate
  to `sdCtx.ComputeCommitment`.
- `domain_shared.go:172` `PickTrieVariant()` — selects the commitment trie variant
  (default `hex-patricia-hashed`, or experimental `parallel` / `streaming`).

### Commitment context (bridges flat state ↔ trie)
- `execution/commitment/commitmentdb/commitment_context.go:327`
  `SharedDomainsCommitmentContext.ComputeCommitment(...)` — the on-demand root
  computation. If zero updates, returns the memoized `patriciaTrie.RootHash()`;
  otherwise builds a `TrieContext`, optionally wires warmup/parallel/trace, and
  calls `patriciaTrie.Process(ctx, updates, ...)`. Persists the encoded trie root
  state afterwards iff `saveState`.
- `commitment_context.go:823` `type TrieContext struct` + its methods — **the
  bridge that implements `PatriciaContext`**:
  - `Branch(prefix)` (`:841`) → `readDomain(CommitmentDomain, prefix)` — load a
    stored branch node by its nibble prefix (copies bytes out because the state
    cache aliases shared storage).
  - `PutBranch(prefix, data, prev)` (`:854`) → `DomainPut(CommitmentDomain, ...)`
    — the *only* thing written back to the trie store. Skipped entirely when
    operating on history (`stateReader.WithHistory()`).
  - `Account(plainKey)` (`:875`) → `readDomain(AccountsDomain, plainKey)`,
    deserialize, emit a `commitment.Update` — **leaf fetched on demand, never
    stored as a trie node.**
  - `Storage(plainKey)` (`:919`) → `readDomain(StorageDomain, plainKey)`.
- `commitment_context.go:682` `SeekCommitment(...)` — on startup, restore the trie
  root state (`restorePatriciaState`, `:778`) from the `KeyCommitmentState` record
  in the commitment domain; if none but blocks were executed, fall back to sync
  progress. `encodeAndStoreCommitmentState` (`:722`) writes the encoded root cell
  state back under `KeyCommitmentState` (`:646`) — the trie root itself is stored
  as a special branch entry, not a node graph.

### HexPatriciaHashed (the transient on-demand trie)
- `execution/commitment/commitment.go:91` — `type Trie interface`
  (`RootHash`, `Process`, `Reset`, `ResetContext`, `Variant`, `Release`).
- `execution/commitment/commitment.go:127` — `type PatriciaContext interface`
  (the 4-method Branch/PutBranch/Account/Storage contract — the crux of the model).
- `execution/commitment/hex_patricia_hashed.go:64` — `type HexPatriciaHashed struct`.
  Note the transient working state: `grid [128][16]cell` (rows 0-63 = account trie,
  64-127 = storage subtrie), `touchMap`/`afterMap`/`branchBefore` per row. It is a
  fold/unfold *engine*, pooled and reused (`Release()` → `hphPool`), holding no
  persistent tree.
- `hex_patricia_hashed.go:239` — `type cell struct`. A cell carries the **plain**
  `accountAddr`/`storageAddr` (so leaves are dereferenced on demand) plus a memoized
  `hash`/`stateHash`; leaf values are not held in the cell.
- `hex_patricia_hashed.go:2753` `Process(...)` — folds updates into the grid,
  unfolding branch nodes from `ctx.Branch` and leaf values from
  `ctx.Account`/`ctx.Storage`, then persists branch nodes via `ctx.PutBranch`.
- `hex_patricia_hashed.go:2478` `RootHash()` — hashes the root cell; the leading
  `128+len` prefix byte is stripped.
- `execution/commitment/commitment.go:668` `BranchEncoder.EncodeBranch(...)` +
  `BranchData` (`:734`) — the on-disk encoding of a branch node (bitmap of present
  children + per-child fields). This byte blob is what lands in the CommitmentDomain.

## Design decisions & rationale

- **Flat state, hash on demand.** Storing state by plain key means a state write is
  one KV put, not a cascade of trie-node rewrites up to the root. Erigon only pays
  the Merkle cost once per block (batched over all touched keys) and only persists
  branch nodes, not the full O(state) node set. This is the central archival /
  high-throughput bet.
- **Branch-only persistence.** The commitment trie is reconstructable from flat
  state; only the interior branch nodes are cached in the CommitmentDomain to avoid
  re-walking untouched subtrees. Leaves are intentionally *not* stored as trie
  nodes — they already exist, canonically, in the account/storage domains. Single
  source of truth for leaf data; no leaf duplication between "state" and "trie".
- **Incarnation-free keys.** The E3 domains dropped the per-account incarnation
  counter that the legacy `PlainState`/`StorageChangeSet` keys baked in
  (`db/kv/tables.go:481-484`), simplifying the storage key.
- **Versioned Domains = free history + unwind.** Each Domain embeds a `History`;
  the state root at any past block can be recomputed by reading historical domain
  values (with `WithHistory()`, `PutBranch` is suppressed so history reads never
  mutate the live branch cache — `commitment_context.go:855`). Unwind replays
  domain changesets rather than reverting a node store.
- **Root state stored as a single record.** Rather than reload/rebuild the whole
  trie at startup, erigon serializes just the root cell + trie cursor state to
  `KeyCommitmentState` and restores it via `SeekCommitment`. Subsequent blocks
  extend from there.
- **Pluggable trie variants.** The `Trie` interface admits `HexPatriciaHashed`
  (default), `ParallelPatriciaHashed`, and a streaming committer that overlaps
  folding with execution — an optimization axis the flat model unlocks because the
  trie is a pure function of (flat state + touched keys).

## Notable patterns (the reusable idea)

**The single most transferable idea: separate the state *store* from the state
*commitment* behind a narrow 4-method context interface, so the Merkle trie becomes
a stateless, on-demand function over flat KV rather than a persisted node graph.**

`PatriciaContext { Branch, PutBranch, Account, Storage }`
(`execution/commitment/commitment.go:127`) is the entire coupling surface between
"where state lives" and "how the root is computed." Leaf reads go through
`Account`/`Storage`; only branch nodes are persisted through `PutBranch`. Any client
that adopts this seam can:
- swap the storage backend without touching the trie,
- recompute historical roots by pointing the context at historical values,
- parallelize / batch commitment independently of execution.

Secondary reusable ideas: (a) storing the trie **root cursor state** as one record
so restart doesn't rebuild the trie; (b) the fold/unfold **grid engine** that keeps
only the touched frontier in memory (`grid [128][16]cell`) and is pooled for reuse.

## Authority note

Erigon is **THE flat-state / commitment-on-demand authority** — the far end of the
trie spectrum, the deliberate opposite of geth's node-store MPT (where every trie
node is a persisted `hash→node` record and the leaf lives inside the trie). When SR
needs the canonical reference for "state as flat versioned Domains + a rebuild-on-
demand HexPatriciaHashed commitment trie that persists only branch nodes," this is
it. For geth's node-store MPT (the DEFAULT hash-keyed model fukuii already follows),
cite that client instead; erigon is the OPTIONAL(archival/perf) counter-design.

## Gotchas / anti-patterns / things they later changed

- **This is NOT the default fukuii should adopt.** fukuii is a hash-keyed MPT
  client; a flat-Domains + commitment-on-demand rewrite is a ground-up storage-model
  change (its own MDBX-style versioned domains, an aggregator, snapshot/step file
  machinery — see `erigon/storage-persistence.md`). Treat this as an
  **OPTIONAL(archival/perf)** reference for a future archival mode, not a change to
  the hot path. The transferable *seam* (state-store vs commitment behind a narrow
  interface) is adoptable incrementally; the *whole model* is not.
- **Proofs are harder.** Because leaves and interior nodes aren't stored as a
  navigable trie, generating a Merkle/`eth_getProof` witness means *rebuilding* the
  touched paths through the commitment engine (there is explicit
  `CollapseTracer`/witness plumbing in `hex_patricia_hashed.go:169-174` precisely
  because the paths must be reconstructed). Node-store MPTs get proofs almost for
  free; erigon pays for them.
- **They migrated to this model (E2 → E3).** Earlier erigon used geth-like
  `PlainState` + `HashedAccounts`/`HashedStorage` + changeset tables; those are now
  in `ChaindataDeprecatedTables` with a `drop_legacy_e2_tables` migration
  (`db/kv/tables.go:479-495`). The flat commitment-on-demand design is the *result*
  of that migration, not an original choice — evidence it's a considered, hard-won
  architecture rather than a happy accident.
- **The repo path moved.** `erigon-lib/state` / `erigon-lib/commitment` no longer
  exist at those paths in this checkout; anything scripted against them must target
  `db/state/**` and `execution/commitment/**`.
- **Execution is coupled to unwind-side changeset machinery.** The `changesetMu`
  band-aid (`db/state/execctx/domain_shared.go:132-140`) exists because the parallel
  commitment calculator can misroute a block N+1 write into block N's changeset
  during its swap window, producing wrong roots on reorg. The in-code comment flags
  it as removable once per-block changesets are derived post-hoc — a live sharp edge
  in the flat/parallel-commitment design, not a settled invariant.
- **Deferred / parallel commitment is subtle.** `SetLeaveDeferredForCaller`,
  `pendingUpdate`, and the per-worker ETL collectors merged via `PutBranch`
  (`commitment_context.go:461-518`) exist so branch writes can be deferred/parallel
  during fork validation and parallel apply. A naive port that applies branches
  inline loses that concurrency but is far simpler — start there.
