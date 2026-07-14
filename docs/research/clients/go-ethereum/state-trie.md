# go-ethereum — state-trie
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary

go-ethereum layers the state-trie subsystem into four tiers, each a clean seam:

1. **`trie/`** — the pure Merkle Patricia Trie (MPT). `Trie` (`trie/trie.go:41`) is
   the in-memory structure over four node types (`fullNode`/`shortNode`/`hashNode`/
   `valueNode`, `trie/node.go:38`). It knows nothing about persistence — it reads
   nodes through a `Reader` (`trie/trie_reader.go`) and, on `Commit`, hands back a
   `trienode.NodeSet` of dirty nodes rather than writing anything itself. Keys are
   keccak256-hashed by the `StateTrie` wrapper (`trie/secure_trie.go:66`), never by
   the raw `Trie`.
2. **`triedb/`** — the node database. A `triedb.Database` (`triedb/database.go:89`)
   wraps a pluggable `backend` interface (`triedb/database.go:62`) with two
   implementations: **hashdb** (legacy, node-keyed-by-hash) and **pathdb** (newer,
   node-keyed-by-path with layered diffs + state history). This is the
   hashdb→pathdb evolution and the heart of the "what to adopt as default" question.
3. **`core/state/`** — `StateDB` (`core/state/statedb.go:79`), the account/storage
   model and the mutation/journal machinery the EVM talks to. It caches `stateObject`s
   (`core/state/state_object.go:49`), each of which lazily opens its own storage trie.
4. **`core/state/snapshot/`** (hashdb mode) or pathdb's internal flat state (path
   mode) — the **flat state layer**: a `hash(key)→value` mirror of the trie's leaves
   that turns an O(log n) trie descent into an O(1) lookup for reads.

The state root is computed bottom-up: storage tries commit first, their roots go
into `types.StateAccount`, accounts commit into the account trie, and its root is
the block state root. `StateDB.commit` (`core/state/statedb.go:1251`) orchestrates
this and produces a `StateUpdate` that is handed to `triedb.Update`
(`triedb/database.go:161`) as a single atomic layer.

## Key types / interfaces / files

- `trie/trie.go:41` — `Trie`: the core MPT. Not concurrency-safe; becomes unusable
  after `Commit` (`committed` flag) — callers must reopen with the new root.
- `trie/node.go:38` — the four node types. `fullNode` is a 17-way branch,
  `shortNode` is extension/leaf, `hashNode` is a 32-byte reference to an
  un-loaded child, `valueNode` is the terminal payload. Separate `fullnodeEncoder`/
  `extNodeEncoder`/`leafNodeEncoder` (`trie/node.go:54`) exist purely to cut
  allocations during RLP encoding.
- `trie/secure_trie.go:66` — `StateTrie` (formerly `SecureTrie`, alias kept at
  `:43`): wraps `Trie` and keccak-hashes every key so an attacker can't craft
  deep node chains. `GetAccount`/`UpdateAccount` RLP-decode/encode
  `types.StateAccount` here.
- `trie/hasher.go:30` — `hasher`: collapses nodes to hashes. Pooled
  (`hasherPool`, `:38`); nodes < 32 bytes are inlined into their parent instead of
  hashed (`trie/hasher.go:68`) — a consensus-load-bearing MPT rule.
- `trie/committer.go` / `trie/trienode/node.go:91` — `NodeSet`: the set of dirty
  nodes a commit produces, keyed **by path**, carrying `Origins` (prev values) so
  pathdb can build reverse diffs. `MergedNodeSet` aggregates the account trie plus
  every touched storage trie.
- `trie/stacktrie.go:46` — `StackTrie`: an insert-in-sorted-order, memory-frugal
  trie that hashes and frees subtrees as soon as they're complete. Used for
  deriving roots during snap-sync and receipt/tx roots — never for mutable state.
- `trie/proof.go:37` — `Trie.Prove` / `VerifyProof`: Merkle proof construction and
  verification, including range proofs (`VerifyRangeProof`) that underpin snap sync.
- `triedb/database.go:62` — `backend` interface: `NodeReader`/`StateReader`/`Commit`/
  `Update`/`Size`/`Close`. The whole pluggability of hashdb vs pathdb lives behind
  this five-method seam.
- `triedb/hashdb/database.go:80` — hashdb `Database`: `dirties map[hash]*cachedNode`
  forming a flush-list (`oldest`/`newest`), a `fastcache` clean cache, and explicit
  `Reference`/`Dereference` GC. Clean cache **disabled by default** (`Defaults`,
  `:70`).
- `triedb/pathdb/database.go:120` — pathdb `Database`: a `layer` tree
  (`triedb/pathdb/database.go:40`) — one on-disk `diskLayer`
  (`triedb/pathdb/disklayer.go`) plus a stack of in-memory `diffLayer`s
  (`triedb/pathdb/difflayer.go:32`), each keyed by state root and state id, with
  reverse `history` for rollback. Single-writer enforced (`readOnly` flag,
  `database.go:124`).
- `triedb/pathdb/config.go:75` — pathdb `Defaults`: `StateHistory =
  params.FullImmutabilityThreshold` (~90k blocks of reverse diffs), 16 MiB clean
  trie cache, 16 MiB clean state cache, node write buffer.
- `core/state/statedb.go:79` — `StateDB`: live `stateObjects`, `stateObjectsDestruct`,
  `mutations`, and the `journal` (`:142`) that backs `Snapshot`/`RevertToSnapshot`.
- `core/state/state_object.go:49` — `stateObject`: account data plus the four-tier
  storage cache (`originStorage`/`dirtyStorage`/`pendingStorage`/`uncommittedStorage`,
  `:60`) that separates tx-scope from block-scope mutations (Byzantium changed commit
  cadence from per-tx to per-block).
- `core/state/journal.go` — the reverse-operation journal; every mutation appends an
  undo entry so reverts are O(changes) with no trie work.
- `core/state/reader.go` — the read path. `flatReader` (`:85`) hits the flat state,
  `mptTrieReader` (`:155`) descends the trie, and `multiStateReader` (`:333`) chains
  them **flat-first, trie-as-gatekeeper-fallback**.
- `core/state/database_mpt.go:59` — `MPTDatabase.StateReader`: the wiring that decides,
  per scheme, whether the flat reader is the snapshot (hash mode) or pathdb's flat
  layer (path mode), then always appends the trie reader as backstop.
- `trie/trie_id.go:22` — `ID{StateRoot, Owner, Root}`: the triple that names a trie.
  `Owner` is the account-address hash (zero for the account trie), giving storage
  tries a namespace so pathdb can path-key nodes across all tries in one store.

## Design decisions & rationale

- **The trie never persists; it emits a NodeSet.** `Commit` returns dirty nodes and
  marks the trie dead (`trie/trie.go:47`, `core/state/database.go:143`). Persistence
  policy (GC vs. layered history) is entirely the triedb backend's concern. This is
  the seam that let geth swap hashdb for pathdb without touching the MPT.
- **Path-keying over hash-keying (pathdb).** hashdb stores nodes under their own
  keccak hash, so identical subtrees dedupe but pruning is near-impossible (you can't
  know if any other trie still references a hash without full refcounting, which is
  slow and was a chronic source of GC bugs). pathdb keys nodes by `(owner, path)`,
  so each state has exactly one node per path — enabling bounded state history,
  cheap online pruning, and reverse-diff rollback.
- **Flat state layer to escape trie read amplification.** Every trie read touches
  O(log n) nodes scattered across disk. The snapshot (hash mode) / flat state (path
  mode) maintains a direct `keccak(addr)→account` and `keccak(addr)+keccak(slot)→slot`
  map, so a warm read is one KV lookup. The trie stays authoritative for the root and
  as the fallback (`core/state/database_mpt.go:82`); the flat layer is an accelerator,
  "optional and only partially useful if not fully generated."
- **Two-phase caching in stateObject.** `dirtyStorage` (tx scope) → `pendingStorage`
  (block scope) → `uncommittedStorage` (since last DB commit) separates revert
  granularity from commit granularity, matching the Byzantium per-block commit change
  without collapsing tx-level snapshots.
- **Journal-based revert, not trie-based.** Snapshots are integer markers into the
  journal (`core/state/journal.go`); reverting replays undo entries. The trie is only
  touched at `Finalise`/`Commit`, keeping the EVM's frequent nested reverts cheap.
- **StackTrie for write-once roots.** Sorted-insert + immediate-hash-and-free
  (`trie/stacktrie.go:44`) gives O(1) memory for computing a root you'll never read
  back — ideal for tx/receipt/withdrawal roots and snap-sync healing.

## Notable patterns (the reusable idea)

- **Trie/persistence decoupling via a returned dirty-node set** — the single most
  transferable idea. The MPT is a pure function `(root, mutations) → (newRoot,
  NodeSet)`; the backend decides how to store it. This is what makes the
  hashdb↔pathdb choice a config flag, not a rewrite.
- **`backend` interface with a scheme string** (`triedb/database.go:62`,
  `Scheme()` at `:200`) — one wrapper, two node-storage strategies, chosen at open
  time; capability-specific methods (`Cap`/`Reference` for hashdb; `Recover`/
  `Journal`/`AccountIterator` for pathdb) type-assert the backend and return
  "not supported" otherwise.
- **Flat-state accelerator layered under a trie fallback** — `multiStateReader`
  (`core/state/reader.go:333`) tries the fast flat reader first and silently falls
  back to the authoritative trie, so correctness never depends on the accelerator
  being complete.
- **Layer tree with reverse diffs for reorg-safe pruning** (pathdb) — in-memory
  diff layers stacked on one mutable disk layer, plus on-disk state history to roll
  back deeper than the layers reach (`triedb/pathdb/database.go:108` doc comment).
- **Path-keyed NodeSet carrying origin values** (`trienode/node.go:91`, `Origins`) —
  a commit output that is simultaneously the forward write and the material for the
  reverse diff.

### Use-case lens (what to adopt as default vs optional)

| Pattern | GOOD FOR | Notes |
|---|---|---|
| **pathdb (path-keyed, layered, pruned)** | enterprise, CEX/custody, light, validator | Bounded disk via online pruning + state history; reorg-safe. geth's forward-looking default. |
| **hashdb (hash-keyed, refcount GC)** | archival/RPC, multi-network legacy | Natural subtree dedup, simpler, but unbounded growth and fragile GC. geth keeps it as the *documented legacy default* (`triedb/database.go:97`) but steers new nodes to pathdb. |
| **snapshot / flat state** | archival/RPC, CEX/custody (hot reads), validator | O(1) reads; optional accelerator, degrades gracefully. Adopt as default-on. |
| **StackTrie** | validator/mining-pool (block production), snap-sync | Cheap root derivation; not for mutable state. |
| **StateTrie key hashing** | all roles | Consensus-mandatory (keccak keys); not optional. |

For fukuii (hash-keyed node store today, akin to geth's hashdb): the flat-state
accelerator is the low-risk, high-value adopt-as-default; path-keying is the larger
strategic bet that unlocks pruning for custody/light roles.

## Authority note

**geth is the canonical MPT + snapshot/pathdb reference for Ethereum.** The node
inlining threshold (< 32 bytes, `trie/hasher.go:68`), keccak key hashing
(`trie/secure_trie.go`), RLP node encoding (`trie/node_enc.go`), and range-proof
verification (`trie/proof.go`) are consensus-load-bearing and must match geth
byte-for-byte for ETH/Sepolia. hashdb vs pathdb, and snapshot-vs-flat, are **storage
policy, not consensus** — they produce identical state roots, so fukuii is free to
choose per node-role. For PoW/ETC specifics the authority is core-geth (which tracks
geth's trie but lags on pathdb); the MPT commitment structure itself is shared.

## Gotchas / anti-patterns / things they later changed

- **hashdb clean cache is off by default** (`triedb/hashdb/database.go:70`) —
  because a live `fastcache` leaks unless the DB is explicitly `Close`d. A subtle
  lifecycle trap if you enable it.
- **A committed trie is dead** (`trie/trie.go:47`). Reusing one after `Commit` returns
  `ErrCommitted`, silently hiding the latest state. Always reopen from the new root.
- **hashdb→pathdb migration is one-directional and disruptive** — you cannot mix
  (`triedb/database.go:112` `log.Crit` if both configured); switching schemes
  requires a resync or offline conversion. Choose deliberately.
- **pathdb is single-writer, globally** (`triedb/pathdb/database.go:117` doc):
  opening two writable databases panics. Enforced by the `readOnly`/`stale` flags;
  double-committing into the same disk base panics (`disklayer.go` `markStale`).
- **Storage-trie root key collides with account key** — the disk-layer comment
  (`triedb/pathdb/disklayer.go:38`) notes node and state caches must be *separate*
  because a storage-trie root node's key equals its owning account's data key. A
  merged cache would corrupt.
- **Snapshot generation is asynchronous and may be partial** — readers must treat
  the flat layer as best-effort and fall through to the trie
  (`core/state/database_mpt.go:64`), never assume it's complete.
- **`secure_trie.go`'s `secKeyCache` and the `SecureTrie` alias are legacy** — the
  file still carries `// Deprecated: use StateTrie` (`:41`), and a
  `TODO(fjl): remove this when StateTrie is removed` sits on `GetKey`
  (`core/state/database.go:81`); the preimage/key-cache machinery is being unwound.
