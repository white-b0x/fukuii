# reth — state-trie
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth's state trie is a **cursor-and-hash-builder pipeline**, not a resident in-memory
tree. The canonical Merkle-Patricia-Trie (MPT) root is recomputed by streaming *sorted*
leaves into a `HashBuilder` (imported from the external `alloy_trie` crate) that folds
them into branch/extension/leaf RLP and emits the root — no full trie is ever
materialized. State itself lives in flat, keccak-keyed tables (`HashedPostState`,
`HashedAccounts`/`HashedStorages` on disk); *intermediate* branch nodes are cached
separately, keyed by their **nibble path** (`StoredNibbles`), so a recompute walks only
the changed subtries. reth has three distinct root strategies layered on the same cursor
abstraction:

1. **Sequential `StateRoot`/`StorageRoot`** (`crates/trie/trie/src/trie.rs`) — the
   reference, DB-cursor-driven streaming root with resumable intermediate progress.
2. **`ParallelStateRoot`** (`crates/trie/parallel/src/root.rs`) — fans storage-root
   computation across a blocking thread pool, then walks the account trie once. Now
   explicitly a *fallback*.
3. **Sparse-trie state root** (`crates/trie/sparse/`, driven by
   `crates/trie/parallel/src/state_root_task.rs`) — the primary hot path for
   `newPayload`/block-building: reveals only the touched nodes via proofs and mutates an
   arena-backed partial trie in the background as transactions execute.

The `TrieInput` (`crates/trie/common/src/input.rs`) is the unifying request object:
cached nodes + hashed-state overlay + prefix sets (which nibble paths changed).

## Key types / interfaces / files

- `crates/trie/trie/src/trie.rs:32` — `StateRoot<T, H>`: generic over a `TrieCursorFactory`
  (intermediate nodes) and a `HashedCursorFactory` (leaves); `.root()` /
  `.root_with_updates()` / `.root_with_progress()`.
- `crates/trie/trie/src/trie.rs:160` — `StateRoot::calculate`: the core loop —
  `account_node_iter.try_next()` yields `Branch` → `hash_builder.add_branch`, `Leaf` →
  compute storage root, `into_trie_account`, `hash_builder.add_leaf`; `hash_builder.root()`
  at the end.
- `crates/trie/trie/src/trie.rs:488` — `StorageRoot<T, H>`: per-account storage trie root,
  short-circuits `EMPTY_ROOT_HASH` on empty storage (`trie.rs:740`).
- `crates/trie/trie/src/trie.rs:25` — `DEFAULT_INTERMEDIATE_THRESHOLD = 100_000`: after N
  updates the root computation returns `StateRootProgress::Progress` with a serializable
  `IntermediateStateRootState` (bounded memory / resumable — used by the staged
  `MerkleStage`).
- `crates/trie/common/src/hash_builder/mod.rs:6` — re-exports `alloy_trie::hash_builder::*`;
  the streaming root algorithm itself is upstream in `alloy_trie` (reth wraps it).
- `crates/trie/trie/src/trie_cursor/mod.rs:31,55` — `TrieCursorFactory` +
  `TrieCursor`: the intermediate-node cursor. Contract: iterate keys (nibble paths) in
  **lexicographical order**; `seek`/`seek_exact`/`next`/`current`/`reset`. `TrieStorageCursor`
  adds `set_hashed_address` for per-account storage tries.
- `crates/trie/trie/src/hashed_cursor/mod.rs` — `HashedCursorFactory`/`HashedCursor`: the
  *leaf* cursor over hashed accounts/storage.
- `crates/trie/trie/src/walker.rs:18` — `TrieWalker<C, K>`: lexicographic traversal with a
  `CursorSubNode` stack and `can_skip_current_node` (skips whole subtries whose hash is
  known and prefix is unchanged — the key perf lever). Splittable/resumable via `split()`.
- `crates/trie/trie/src/node_iter.rs:52` — `TrieNodeIter`: merges walker (cached branch
  nodes) with the hashed-leaf cursor, yielding `TrieElement::Branch | Leaf`; caches the last
  seeked entry to avoid redundant seeks.
- `crates/trie/common/src/input.rs:10` — `TrieInput { nodes, state, prefix_sets }` and
  `TrieInputSorted` (Arc'd sorted variant for cheap multiproof reuse).
- `crates/trie/common/src/hashed_state.rs:29` — `HashedPostState { accounts: B256Map, storages
  }`; `from_bundle_state` (`:49`) hashes revm's `BundleAccount` output. `construct_prefix_sets`
  turns the diff into the changed-nibble-path sets.
- `crates/trie/common/src/nibbles.rs:30` — `StoredNibbles(Nibbles)`: DB key for
  trie-nodes-by-nibble-path, one nibble per byte (≤65 bytes). `StoredNibblesSubKey` (`:73`)
  is the DupSort subkey for storage-trie nodes.
- `crates/trie/common/src/nibbles.rs:148` — `PackedStoredNibbles`: **33-byte packed** nibble
  key (two nibbles/byte + length byte) — halves key size vs. `StoredNibbles`; selected at
  runtime by the `TrieKeyAdapter` (`crates/trie/db/src/trie_cursor.rs`).
- `crates/trie/common/src/nibbles.rs:11` — `depth_first_cmp`: children-before-parents
  ordering used by the depth-first cursor variant.
- `crates/trie/parallel/src/root.rs:35` — `ParallelStateRoot`: spawns one blocking task per
  changed account's storage root (`root.rs:111`), collects via `mpsc::sync_channel`, then
  streams the account trie into a single `HashBuilder`.
- `crates/trie/parallel/src/state_root_task.rs:14,55` — `StateRootMessage` /
  `StateRootHandle`: the background sparse-trie pipeline shared by the engine (`newPayload`)
  and payload builder; streams `StateUpdate(EvmState)` / BAL (EIP-7928) / proof-target
  messages into the sparse trie as execution proceeds.
- `crates/trie/sparse/src/trie.rs:22` — `RevealableSparseTrie<T = ArenaParallelSparseTrie>`:
  `Blind(Option<Box<T>>)` vs `Revealed(Box<T>)`; `reveal_root` / `reveal_v2_proof_nodes`
  transition blind→revealed from proof nodes. Cleared tries are pooled for allocation reuse
  across payloads.
- `crates/trie/sparse/src/state.rs:33` — `SparseStateTrie<A, S>`: account + per-storage
  sparse tries + `BucketedLfu` hot-slot/hot-account trackers + `DeferredDrops` (defers proof
  buffer frees until after the root is computed).
- `crates/trie/sparse/src/arena/mod.rs:31` — `NodeArena = SlotMap<Index, ArenaSparseNode>`:
  the sparse trie is an arena/slotmap of nodes (not pointer-chased `Box`es); `compact_arena`
  (`:74`) GCs orphaned nodes.
- `crates/trie/trie/src/witness.rs:25` — `TrieWitness<T, H>`: builds a stateless-execution
  witness (`B256Map<Bytes>` of RLP nodes) by revealing the touched nodes into a
  `SparseStateTrie`; `ExecutionWitnessMode` selects legacy vs. newer formats.
- `crates/trie/db/src/state.rs:24` — `DatabaseStateRoot`: DB-transaction entry points
  (`from_tx`, `overlay_root`, `overlay_root_from_nodes`) that wire the MDBX-backed cursors
  into `StateRoot`.

## Design decisions & rationale

- **Streaming root over resident trie.** Because state is stored flat and hashed, and
  intermediate nodes are cached by nibble path, the root is a *fold over sorted leaves*.
  This avoids holding a multi-GB trie in RAM and makes the computation trivially
  checkpoint-able (the `threshold` / `IntermediateStateRootState` mechanism). Directly
  comparable to erigon's commitment-on-demand, but reth keeps the trie-node cache as a
  first-class table rather than deriving everything from the flat state each time.
- **Prefix sets = "what changed".** Every root call is scoped by `TriePrefixSets`; the
  `TrieWalker` skips any subtrie whose prefix isn't in the set and whose stored hash is
  intact (`can_skip_current_node`). Incremental root cost is proportional to the diff, not
  to state size.
- **Cursor/factory generics.** `StateRoot` is generic over `TrieCursorFactory` +
  `HashedCursorFactory`, so the *same* algorithm runs against MDBX (`crates/trie/db/`),
  in-memory overlays (`trie_cursor/in_memory.rs`), and mocks. Overlays let a not-yet-persisted
  block's state be rooted without writing to disk.
- **Parallel storage roots.** Storage tries are independent per account, so
  `ParallelStateRoot` computes them concurrently on a blocking pool and only serializes the
  account-trie walk. Missed leaves (unmodified accounts re-encountered because intermediate
  nodes aren't all stored) fall back to a synchronous storage-root recompute
  (`root.rs:162`).
- **Sparse trie as the real hot path.** `ParallelStateRoot`'s own docs
  (`root.rs:31`) call it a fallback: the sparse trie only reveals and mutates the touched
  nodes, computing the root *concurrently with execution* by consuming proofs streamed from
  a multiproof task — so by the time the block finishes executing, the root is (nearly)
  ready.
- **Arena + LFU for the sparse trie.** Nodes live in a `SlotMap` arena (cache-friendly,
  no per-node heap alloc, cheap compaction) and a bucketed LFU keeps hot `(address, slot)`
  entries revealed across payloads, exploiting access locality between consecutive blocks.
- **Packed vs. legacy nibble keys.** `TrieKeyAdapter` lets the DB store trie nodes under
  either 65-byte `StoredNibbles` (one nibble/byte) or 33-byte `PackedStoredNibbles` (two
  nibbles/byte), chosen at runtime — a storage-size optimization without changing the
  cursor algorithm.

## Notable patterns (the reusable idea)

- **The single most transferable idea for fukuii: compute the state root by streaming
  sorted, keccak-hashed leaves into a `HashBuilder`, scoped by a prefix set of changed
  paths, over a `TrieCursor`/`HashedCursor` abstraction** — never materialize the whole
  trie. This one pattern yields bounded memory, resumable/checkpointed roots, trivial
  parallelization of storage roots, and a clean seam for in-memory overlays. fukuii (Mantis
  lineage) computes roots against a resident MPT; adopting the cursor+hash-builder shape
  would be the highest-leverage structural change for archival/validator throughput.
- **Prefix-set-driven subtrie skipping** (`can_skip_current_node`): make incremental root
  cost proportional to the diff by caching intermediate node hashes keyed by nibble path.
- **Blind/Revealed sparse trie fed by proofs**: a partial trie that reveals only touched
  nodes is the natural substrate for both stateless witnesses and concurrent-with-execution
  root computation — one data structure, two use cases.
- **Threshold-based intermediate progress**: return a serializable checkpoint after N
  updates so a long root computation cooperates with staged sync and can resume.

## Authority note

reth = the **parallel-state-root + sparse-trie** authority (the arena sparse trie, the
concurrent-with-execution root pipeline, blind/revealed proof reveal, packed nibble keys
are reth-distinctive). go-ethereum = canonical MPT behavior / the ground-truth
account-and-storage trie semantics fukuii must match byte-for-byte. erigon = the
flat-state / commitment-on-demand peer (same "root from sorted leaves, no resident trie"
philosophy, different data layout). The actual MPT hashing primitive here is *not*
reth-owned — `HashBuilder` lives in the external `alloy_trie` crate; reth contributes the
orchestration (cursors, walker, parallel/sparse strategies), not the RLP node hashing.

## Gotchas / anti-patterns / things they later changed

- **`ParallelStateRoot` is now a fallback, not the main path** (`root.rs:31`). Don't read it
  as reth's flagship — the sparse-trie `state_root_task` pipeline superseded it for
  `newPayload`/build. It's kept for correctness/fallback and simpler call sites.
- **Not all intermediate nodes are persisted**, so a trie walk can re-encounter unmodified
  leaves ("missed leaves", tracked by the parallel tracker at `root.rs:163`) and must
  recompute their storage root — a subtle correctness requirement, not a bug.
- **Cursor ordering is load-bearing.** `TrieCursor` *must* return keys in lexicographic
  order and `HashBuilder` *must* receive leaves sorted; the whole streaming algorithm
  silently produces a wrong root otherwise. `reset()` mandates a following `seek`
  (`trie_cursor/mod.rs:74`).
- **Two nibble encodings coexist** (legacy 65-byte and packed 33-byte via `TrieKeyAdapter`);
  reading trie tables requires selecting the matching adapter — a migration seam that can
  trip up direct table inspection.
- **`HashedPostState` keys are keccak256 of address/slot**, not raw — the entire trie layer
  operates in hashed key space (secure trie); mixing raw and hashed keys is a classic error.
- **Sparse trie `DeferredDrops`** intentionally delays freeing proof-node buffers until after
  the root is computed (`state.rs:26`) — an allocation-lifetime optimization that looks like
  a leak if you don't know why.
```
