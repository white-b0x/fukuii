# nethermind — state-trie
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

> Cross-ref: [`storage-persistence.md`](storage-persistence.md) (★) covers the KV backend
> (`IDb`/`IColumnsDb`, RocksDB wrapper, `FullPruningDb` at the DB level). This doc focuses on
> the **trie structure**, the **Hash vs HalfPath node-key schemes**, and the **in-memory
> pruning `TrieStore`** that makes online pruning possible. Where the two overlap
> (`NodeStorage` key layout, the full-pruning DB swap) this doc gives the trie-level view.

## Architecture summary

nethermind's state is a pair of **Merkle-Patricia tries** (`StateTree` for accounts, one
`StorageTree` per contract) built on a shared `PatriciaTree` engine, sitting on a **four-layer
stack** that cleanly separates concerns:

1. **`PatriciaTree`** (`Nethermind.Trie`) — the pure MPT algorithm: 4 node types
   (`Branch`/`Extension`/`Leaf`/`Unknown`), nibble-path traversal, RLP encode/decode, root-hash
   (Keccak) computation. Knows nothing about databases or pruning — it talks to an
   `IScopedTrieStore` resolver interface for node load/commit.
2. **`TrieStore`** (`Nethermind.Trie.Pruning`) — the **in-memory dirty-node cache** and
   persistence boundary. This is where online pruning lives: a 256-shard cache of live
   `TrieNode` objects, a `BlockCommitSet` queue tracking one root per block, and two orthogonal
   policy knobs — `IPruningStrategy` (*when to evict from RAM*) and `IPersistenceStrategy`
   (*when to flush a snapshot to disk*).
3. **`NodeStorage`** (`Nethermind.Trie`) — the **key-scheme translator**. Turns a
   `(address?, TreePath, keccak)` node identity into a physical DB key under one of **two
   interchangeable schemes**: `Hash` (32-byte content-addressed, geth-legacy, archival) or
   `HalfPath` (path-prefixed for locality, the default — geth-pathdb-equivalent, prunable).
4. **`IKeyValueStore` / RocksDB** — the raw byte store (★ storage doc).

The distinctive nethermind ideas are all in layers 2–3: the **dual key scheme selectable
per-datadir and convertible mid-flight**, and the **memory-bounded dirty cache** whose
`PrepareStableState` lock lets an **online full-pruner** copy the live trie into a sibling DB
and atomically swap it — pruning without downtime, and optionally re-keying from Hash→HalfPath
in the same pass.

### Use-case menu (the lens)

| Config | Key scheme | Pruning | Serves |
|--------|-----------|---------|--------|
| Archive node | `Hash` | none (`No`/`Archive`) | full history, geth-key-compatible, largest disk |
| Default full node | `HalfPath` + memory-bounded pruning | in-memory prune + periodic snapshot | **pruned/light + custody** — smallest disk, locality-friendly |
| Online repack | `Hash`→`HalfPath` via full pruner | online full pruning | **custody** — shrink + re-key a running node with no downtime |

HalfPath is the smaller, prunable, snap-serving-friendly scheme (pruned/light + custody). Hash
is the archival scheme. Online full pruning is the custody enabler: prune (and re-key) without
stopping the node.

## Key types / interfaces / files

### The MPT engine
- `Nethermind.Trie/PatriciaTree.cs:23` — the core trie. `RootRef` (`TrieNode?`) is the live
  root; `RootHash` get/set drives lazy resolve. `Commit(skipRoot, writeFlags)` (`:134`) walks
  dirty nodes through an `ICommitter` obtained from `TrieStore.BeginCommit`; `UpdateRootHash`
  (`:327`) calls `RootRef.ResolveKey` to (re)compute the Keccak commitment. `PatriciaTree.BulkSet.cs`
  is a batched insert path used by `StateTree.BeginSet`.
- `Nethermind.Trie/TrieNode.cs` (1524 lines) + `TrieNode.Decoder.cs` / `TrieNode.Visitor.cs` —
  the node object: lazy `ResolveNode`/`ResolveKey`, `IsDirty`/`IsPersisted` flags,
  `PrunePersistedRecursively`, `FullRlp`. Nodes < 32 bytes are inlined in the parent RLP and
  carry no Keccak (see `PersistNode` assert, `:1211`).
- `Nethermind.Trie/NodeType.cs` — the 4-variant enum (`Unknown, Branch, Extension, Leaf`).
- `Nethermind.Trie/TreePath.cs`, `TinyTreePath.cs`, `Nibbles.cs`, `HexPrefix.cs` — nibble-path
  representation; `TreePath` is the in-memory path passed everywhere in `INodeStorage`.

### The dual key scheme (the distinctive part)
- `Nethermind.Trie/INodeStorage.cs:38` — `enum KeyScheme { Hash, HalfPath, Current }`. `Current`
  is a config-only sentinel meaning "whatever the datadir already uses" (an enum can't be null
  in config). The interface keys everything by `(Hash256? address, TreePath path, ValueHash256
  keccak)` — `address == null` means a state-trie node, non-null means a storage-trie node.
- `Nethermind.Trie/NodeStorage.cs:14` — `NodeStorage(keyValueStore, scheme = HalfPath,
  requirePath = true)`. `Scheme` is a **mutable get/set property** — this is what lets the full
  pruner flip the whole store's scheme atomically after a copy.
- `NodeStorage.cs:36` `GetHalfPathNodeStoragePathSpan` — the **HalfPath key layout**, documented
  inline: state node = `[section byte | 8 bytes of path | path-length byte | 32-byte hash]`
  (42 bytes); storage node = `[2 | 32-byte address | 8 bytes of path | path-length byte |
  32-byte hash]` (74 bytes). The **section byte** buckets keys: `0` = top state (path len ≤ 5),
  `1` = deeper state, `2` = storage. Rationale in the comment: top-level nodes are ~5× bigger
  and grow under pruning, so isolating them keeps the lower-node key space dense and improves
  block-cache hit rate + snap-serve leaf traversal.
- `NodeStorage.cs:94` `GetHashBasedStoragePath` — the **Hash key layout**: just the 32-byte
  keccak. Content-addressed, so identical subtrees dedup across the whole DB (geth-legacy behavior).
- `NodeStorage.cs:112` `Get(...)` — **dual-read fallback**: in HalfPath mode it tries the
  HalfPath key then falls back to the Hash key (and vice-versa in Hash mode). This is what makes
  a scheme migration safe *while it's in progress* — old-scheme keys stay readable.
- `NodeStorage.cs:170` `Set(...)` — on delete (`data == null`) it **only removes the HalfPath
  key, never the Hash key** ("DO NOT delete hash based key") — Hash keys are shared/dedup'd and
  may still be referenced.
- `Nethermind.Trie/NodeStorageFactory.cs:20` `DetectCurrentKeySchemeFrom` / `:51`
  `DetectKeyScheme` — **auto-detects the datadir's scheme by sampling 20 keys**: if > 10 are
  exactly 32 bytes long → `Hash`, else → `HalfPath`. `WrapKeyValueStore(store,
  forceUsePreferredKeyScheme)` (`:33`) resolves the effective scheme and the `requirePath` flag.

### The pruning TrieStore (the online-pruning enabler)
- `Nethermind.Trie/Pruning/TrieStore.cs:29` `sealed class TrieStore : ITrieStore,
  IPruningTrieStore` (72 KB). Fields: `_dirtyNodes` = `TrieStoreDirtyNodesCache[256]` (shard
  count `1 << DirtyNodeShardBit`, default bit 8), `_commitSetQueue`, `_pruningStrategy`,
  `_persistenceStrategy`, `_nodeStorage`, and two locks — `_scopeLock` + `_pruningLock`.
- `TrieStore.cs:255` `GetNodeShardIdx(path, hash)` / `:266` `GetDirtyNodeShard` — nodes are
  sharded by a hash of `(path, keccak)`; same-path nodes always land in the same shard so the
  remove logic stays correct.
- `Nethermind.Trie/Pruning/TrieStoreDirtyNodesCache.cs` (20 KB) — one shard: a
  `ConcurrentDictionary` of `Key(address, path, keccak) → NodeRecord(node, blockNumber)`, plus
  per-shard memory accounting and the per-shard `PruneCache`.
- `TrieStore.cs:453` `BeginBlockCommit(blockNumber)` → `IBlockCommitter` — the per-block commit
  scope `PatriciaTree.Commit` runs inside. `FinishBlockCommit` (`:466`) seals the
  `BlockCommitSet`, calls `set.Prune()` (2-level shallow prune of persisted branches), enqueues
  it, and triggers `Prune()`.
- `TrieStore.cs:544` `Prune()` / `:563` `TrySyncPrune` — checks the strategy against
  `CaptureCurrentState()` and, if over budget, runs pruning on a **background task** (delayed so
  it prunes in the block gap, not on the hot path).
- `TrieStore.cs:652` `PersistAndPruneDirtyCache` + `:1193` `PersistNode` — flush a snapshot: walk
  the oldest reorg-safe `BlockCommitSet`, write each dirty node via `NodeStorage.Set`, mark
  `IsPersisted = true`. `:908` `PruneCache` evicts nodes from the shards in parallel
  (`ParallelUnbalancedWork.For`, work-stealing, capped at 16 cores to avoid starving block
  processing).
- `TrieStore.cs:1217` `IsNoLongerNeeded(lastCommit)` — the reorg-safety gate: a node can be
  dropped only once `LatestCommittedBlock ≥ _maxDepth` **and** `lastCommit <
  LatestCommittedBlock − _maxDepth` (i.e. past the reorg boundary).
- `TrieStore.cs:1386` `PrepareStableState(ct)` → `StableLockScope` (a `ref struct`) — acquires
  **both** `_scopeLock` and `_pruningLock`, flushes the commit buffer, and persists the cache.
  While held, the trie is quiescent — the precondition for the full pruner to copy and swap.

### Pruning strategies (the two orthogonal knobs)
- `Nethermind.Trie/Pruning/IPruningStrategy.cs` — *when to evict RAM*: `ShouldPruneDirtyNode` /
  `ShouldPrunePersistedNode` / `DeleteObsoleteKeys`. Impls: `MemoryLimit` (evict when
  `DirtyCacheMemory ≥ limit`), `PersistedMemoryLimit`, `MinBlockInCachePruneStrategy` /
  `MaxBlockInCachePruneStrategy` (bound how often snapshots happen relative to the reorg
  boundary), `DontDeleteObsoleteNodeStrategy`, `NoPruning`. Composed fluently via `Prune.cs`
  (`Prune.WhenCacheReaches(bytes).UnlessLastPersistedBlockIsTooNew(...)`).
- `Nethermind.Trie/Pruning/IPersistenceStrategy.cs` — *when to snapshot to disk*:
  `ShouldPersist(blockNumber)`. Impls: `Archive` (every block), `ConstantInterval` (every N
  blocks, via `Persist.EveryNBlock`), `CompositePersistenceStrategy`, `NoPersistence`.
- `Nethermind.Trie/Pruning/BlockCommitSet.cs` — one block's root + seal state; `Prune()` does a
  shallow 2-level `PrunePersistedRecursively`.

### Online full pruning (trie ↔ DB coupling)
- `Nethermind.Blockchain/FullPruning/FullPruner.cs:218` `RunFullPruning` — the orchestration:
  1. `_trieStore.PrepareStableState` to freeze, then `_fullPruningDb.TryStartPruning` to spin up
     the sibling clone DB (`FullPruningDb` duplicates all live writes into it, `FullPruningDb.cs:14`).
  2. `_nodeStorageFactory.WrapKeyValueStore(pruning, usePreferredKeyScheme: true)` gives a
     `targetNodeStorage` that may use a *different* scheme (`FullPruner.cs:239`).
  3. `CopyTree<TContext>` (`:303`) visits the live trie from `baseBlock`'s root and writes every
     reachable node into `targetNodeStorage` — a mark-live-copy-forward GC, not mark-and-sweep.
  4. Under a second `PrepareStableState`, `pruning.Commit()` atomically swaps the clone in as the
     new live DB, then `_nodeStorage.Scheme = targetNodeStorage.Scheme` flips the key scheme
     (`:287`). On error, scheme reverts (`:294`).
- `FullPruner.cs:241` — **the one asymmetry**: HalfPath→Hash is **refused** (downgraded back to
  HalfPath) because write-on-read duplication may have already written HalfPath keys the Hash
  reader can't find. Hash→HalfPath is supported (warns to raise the memory budget).

### State providers (account/storage model)
- `Nethermind.State/StateTree.cs:18` — the account trie: `Get/Set(Address, Account)` keys by
  `Keccak(address)`, RLP-encodes accounts (`AccountDecoder`), `EmptyAccountRlp` for
  totally-empty accounts. `StateTreeBulkSetter` batches inserts.
- `Nethermind.State/StorageTree.cs:20` — per-contract storage trie: keys by `Keccak(slot)`
  (with a precomputed 1024-entry `Lookup` table for small slot indices), RLP-encodes values,
  zero → empty. Constructed with a `storageRoot`, so each contract's storage is an independent trie
  rooted at the account's `StorageRoot`.
- `Nethermind.State/WorldState.cs:33` — the façade combining `StateProvider` (accounts) +
  `PersistentStorageProvider` (storage), exposing `StateRoot`, `Commit`, `RecalculateStateRoot`,
  and `WarmUp` prefetch. `GetAccount` splices the storage root back in via
  `WithChangedStorageRoot` (`:91`).
- `Nethermind.State/StateProvider.cs` (999 lines) + `PersistentStorageProvider.cs` (649) +
  `TransientStorageProvider.cs` — the mutable working-set layer over the trees, with per-block
  change journaling / commit.

## Design decisions & rationale

- **Two orthogonal policies, not one pruning mode.** Splitting `IPruningStrategy` (RAM eviction)
  from `IPersistenceStrategy` (disk snapshot) means an operator dials memory footprint and disk
  snapshot cadence independently, and the archive node is just `NoPruning` + `Archive` — same
  code path, different strategy objects.
- **HalfPath default over Hash.** Path-prefixed keys keep sibling trie nodes physically adjacent
  in RocksDB (better block-cache locality, faster snap-range serving) and make the keyspace
  prunable, at the cost of losing cross-subtree dedup. Hash is kept for archive nodes and
  geth-key compatibility. The **section-byte bucketing** of top-state vs deep-state vs storage
  is a locality micro-optimization justified with measured out-of-order-key rates (0.03–0.07%).
- **Sharded dirty cache (256 shards).** Concurrent block processing commits nodes into different
  shards without lock contention; pruning parallelizes across shards with work-stealing, capped
  at 16 cores so it never starves the critical path (state-root/receipts computation).
- **Reorg-boundary-gated eviction.** `IsNoLongerNeeded` + `_maxDepth` guarantee a node is never
  dropped from cache until it's past the deepest possible reorg, so the trie is always
  reconstructable for any in-window block without touching disk.
- **Online full pruning by live-copy + atomic swap** (vs geth's offline pruner or besu's
  mark-and-sweep Forest). `FullPruningDb` duplicates writes into a sibling DB while a background
  visitor copies only reachable nodes; a stable-state lock + atomic pointer swap cuts over with
  no downtime — and the same pass can **re-key Hash→HalfPath**.
- **State-availability floor written atomically with the swap** (`FullPruner.cs:280`,
  `_stateBoundary.OldestStateBlock`) — a crash mid-swap can't leave a live DB whose oldest-state
  marker lies about what's actually present.

## Notable patterns (the reusable idea)

- **A key-scheme indirection layer between the trie and the KV store.** `INodeStorage` is a thin,
  swappable translator from logical node identity `(address, path, keccak)` to physical bytes,
  with a **mutable `Scheme` property** and **dual-read fallback**. This single seam is what makes
  scheme choice a per-datadir config *and* an online migration — the trie code above and the
  RocksDB code below are both scheme-agnostic. **The single most transferable idea for fukuii.**
- **Persistence policy as composable strategy objects** (`Prune.WhenCacheReaches(...)
  .UnlessLastPersistedBlockIsTooNew(...)`, `Persist.EveryNBlock(...).Or(...)`) — the archive vs
  pruned vs custody distinction is data, not branching code.
- **Prune as forward-copy GC, not in-place sweep** — copy-live-to-new + atomic swap sidesteps
  the fragmentation and long-lock problems of in-place deletion, and doubles as a re-key/compact.

## Authority note

nethermind = the **Hash/HalfPath dual-scheme + online-pruning-trie** authority (the mutable
`INodeStorage.Scheme`, dual-read fallback, live-copy full pruner with in-pass re-keying are
nethermind-distinctive). **go-ethereum** = canonical MPT behavior and the original hashdb vs
pathdb split HalfPath mirrors. **besu** = closest JVM trie mirror (Bonsai flat-DB + Forest
mark-and-sweep pruning; contrast besu's mark-and-sweep with nethermind's live-copy-and-swap).
For byte-level MPT/RLP/commitment correctness fukuii should still validate against go-ethereum /
core-geth, not nethermind.

## Gotchas / anti-patterns / things they later changed

- **Hash-keyed deletes are forbidden.** `NodeStorage.Set` deletes only the HalfPath key on
  removal — Hash keys are content-addressed and shared, so deleting one could orphan another
  subtree. Any pruner over Hash-scheme data must treat it as effectively append-only.
- **HalfPath→Hash conversion is unsupported** (`FullPruner.cs:241`) — write-on-read duplication
  may have already written HalfPath keys into the target; a Hash-scheme reader started without
  path tracking can't find them. Only Hash→HalfPath repacking works online.
- **`KeyScheme.Current` is a config sentinel, not a real scheme** — resolved via
  `DetectKeyScheme` sampling 20 keys. A near-empty DB (< 20 keys) returns `null` ("can't
  decide") and falls back to the preferred/HalfPath default.
- **HalfPath truncates the path to 8 bytes**, accepting a tiny fraction (~0.03–0.07% on mainnet)
  of out-of-order keys as a deliberate size/locality trade — fine as long as collisions land in
  the same RocksDB data block. The 32-byte keccak still disambiguates.
- **Pruning is memory-bounded and background** — under a burst that outpaces the background prune
  task, dirty-cache memory can overshoot the configured limit before eviction catches up; the
  `MinBlockInCachePruneStrategy`/`MaxBlockInCachePruneStrategy` guards exist to keep snapshot
  cadence sane rather than to hard-cap RAM instantaneously.
- **`StableLockScope` is a `ref struct`** — it holds two live locks and must be stack-scoped in a
  `using`; it cannot be boxed/stored, which is intentional (prevents leaking the global
  block-processing freeze).
