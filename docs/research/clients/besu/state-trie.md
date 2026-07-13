# besu — state-trie
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

## Architecture summary

besu separates the **Merkle Patricia Trie (MPT) engine** from the **world-state /
account model** that layers over it, and then offers **two world-state formats** that
consume the trie engine very differently:

- **The MPT engine** (`ethereum/trie/`) is a self-contained, storage-agnostic library.
  `MerkleTrie<K,V>` is the interface; `StoredMerklePatriciaTrie` is the on-disk
  implementation that lazily materializes nodes through a `NodeLoader` (a
  `Function<location→Bytes>` view of the KV store) and flushes dirty nodes back through a
  `NodeUpdater` on `commit`. Everything the trie touches is expressed through the
  Visitor pattern (Get/Put/Remove/Commit/Proof visitors) rather than open-coded
  traversal — the same visitors implement point reads, range scans (SNAP sync),
  witness/proof generation, and structural mutation.
- **Forest** (`ethereum/trie/forest/`) is the classic "full node store" model: it holds
  the account state in a `StoredMerklePatriciaTrie` keyed by node hash, one storage trie
  per contract, and reads/writes state purely through the trie. `rootHash()` is literally
  `accountStateTrie.getRootHash()`. This is the archival-friendly, hash-addressed design
  and is the one that structurally matches how a Mantis/fukuii-lineage client already
  stores state.
- **Bonsai / path-based** (`ethereum/trie/pathbased/bonsai/`) is the "flattened / thin
  trie" model. Live account and storage values are stored **flat** (path-addressed, in
  dedicated column families — see `storage-persistence.md` for the KV split), so the vast
  majority of reads never touch a trie node at all. The MPT is retained only enough to
  recompute the state root, and every block emits a **TrieLog** — a serialized
  prior→updated reverse-diff of exactly the leaves (accounts, code, storage slots) that
  changed. The TrieLog is what lets Bonsai roll a mutable world state backward and forward
  across a reorg without keeping historical trie nodes around.

The unifying seam is a small stack of EVM-facing interfaces —
`WorldState` → `MutableWorldState` → `WorldUpdater`/`WorldView` — that both Forest and
Bonsai implement. The EVM only ever sees these interfaces; it has no idea whether state is
hash-addressed nodes (Forest) or flat values + a diff journal (Bonsai). **That interface
seam is the transferable idea for fukuii.**

## Key types / interfaces / files

### The MPT engine (`ethereum/trie/`)
- `.../MerkleTrie.java:32` — the trie contract: `get`/`getPath`, `put`/`putPath`,
  `remove`, `getRootHash`, `commit(NodeUpdater)`, `getValueWithProof` (witnesses),
  `entriesFrom(startKeyHash, limit)` (range scan), `visitAll`/`visitLeafs`. Note the
  dual key/**path** access — path-based methods are what Bonsai's flat model needs.
- `.../StoredMerkleTrie.java:101` — abstract stored trie; `getValueWithProof` runs a
  `ProofVisitor` to collect the ordered node list for a Merkle proof.
- `.../patricia/StoredMerklePatriciaTrie.java:33` — the concrete on-disk MPT. Constructed
  from a `NodeLoader` + `rootHash` (+ optional root location) and value ser/deser
  functions; wires in a `StoredNodeFactory` that decodes nodes on demand.
- `.../Node.java:22` — node interface (accept-visitor, `getPath`, `getLocation`,
  `getValue`, `getChildren`). Concrete node types in `.../patricia/`: `BranchNode`,
  `ExtensionNode`, `LeafNode`, plus `NullNode`/`StoredNode` (lazy) and `MissingNode`.
- `.../NodeLoader.java` / `.../NodeUpdater.java` — the two-function boundary between the
  trie and any KV store: load a node by (location, hash), store a node by (location, hash,
  bytes). This is how the trie stays storage-agnostic.
- `.../Proof.java:22` + `.../ProofVisitor.java` — witness/proof machinery.
- `.../StorageEntriesCollector.java`, `RangeStorageEntriesCollector.java`,
  `RangeManager.java`, `SnapCommitVisitor.java` — range-proof / SNAP-sync trie scanning.

### The EVM-facing world-state interfaces
- `evm/.../worldstate/WorldState.java:39` — immutable view: `rootHash()`,
  `streamAccounts(...)`, and (via `WorldView`) account/storage reads.
- `evm/.../worldstate/WorldUpdater.java:35` — the buffered mutable view the EVM runs
  against: `createAccount`, `getAccount` (returns `MutableAccount`), `deleteAccount`,
  `getTouchedAccounts`, `revert`, `commit`, and `parentUpdater()` — **updaters nest**, so
  a transaction/call frame gets a child updater that commits into its parent (this is the
  EVM snapshot/revert mechanism).
- `evm/.../account/Account.java`, `MutableAccount.java`, `AccountState.java` — the account
  model the trie stores leaves for (nonce, balance, codeHash, storageRoot).
- `plugin-api/.../worldstate/MutableWorldState.java:31` — bridges the EVM `WorldState` to
  persistence: `persist(BlockHeader, StateRootCommitter)` recomputes the state root and
  writes changes; `freezeStorage()` yields a read-only snapshot.
- `ethereum/core/.../common/PmtStateTrieAccountValue.java` — the RLP account leaf value
  (`readFrom`/`writeTo`) that Forest stores in the account trie.

### Forest (hash-addressed, archival)
- `ethereum/trie/forest/worldview/ForestMutableWorldState.java:55` — holds a
  `MerkleTrie<Bytes32,Bytes> accountStateTrie` (line 61); `newAccountStateTrie(rootHash)`
  (line 103) and `newAccountStorageTrie` (111) build `StoredMerklePatriciaTrie`s;
  `rootHash()` = `accountStateTrie.getRootHash()` (120). Pure trie in, trie out.

### Bonsai (flat state + trie-log reverse-diff)
- `plugin-api/.../trielogs/TrieLog.java:33` — the public TrieLog contract: per-block maps
  of account/code/storage changes, each a `LogTuple<T>` carrying **both** `getPrior()` and
  `getUpdated()` (plus `isCleared*` flags). Prior+updated is what makes the diff
  bidirectionally applicable.
- `ethereum/core/.../pathbased/common/trielog/TrieLogLayer.java:44` — concrete TrieLog;
  class doc: "encapsulates the changes … to transition one block to the next … only the
  'Leaves' are tracked." Holds `Map<Address, PathBasedValue<AccountValue>> accounts`,
  `code`, and nested `storage` maps; `freeze()` locks it before persistence.
- `ethereum/core/.../pathbased/common/worldview/accumulator/PathBasedValue.java:22` — the
  reverse-diff tuple: `{prior, updated, clearedAtLeastOnce}`.
- `.../accumulator/PathBasedWorldStateUpdateAccumulator.java` — the `WorldUpdater`
  implementation for path-based state; it accumulates changes as `PathBasedValue`s and
  emits a `TrieLogLayer`. `rollForward(TrieLog)` (line 627) applies `updated` values;
  `rollBack(TrieLog)` (647) applies `prior` values — the two directions of a reorg.
- `.../pathbased/common/worldview/PathBasedWorldState.java:53` — abstract base;
  `persist(...)` (174) recomputes the root, and when transitioning block→block calls
  `trieLogManager.saveTrieLog(...)` (196) to journal the diff.
- `.../pathbased/common/provider/PathBasedWorldStateProvider.java:255`
  (`rollFullWorldStateToBlockHash`) — **the reorg engine**: given a target block hash it
  walks TrieLogs, `rollBacks` the divergent suffix and `rollForwards` to the target,
  rolling back and forward in tandem until it hits a shared ancestor state (lines
  265–301). This is the whole point of the trie-log.
- `.../pathbased/common/trielog/TrieLogPruner.java` — bounds journal growth (Bonsai must
  prune old trie-logs or they accumulate unboundedly).
- `.../bonsai/worldview/accumulator/BonsaiWorldStateUpdateAccumulator.java` +
  `.../bonsai/trielog/BonsaiTrieLogFactory.java` — Bonsai-specific accumulator/factory.

## Design decisions & rationale

- **Trie engine is a library, not a service.** `MerkleTrie` depends only on a
  `NodeLoader`/`NodeUpdater` pair (two functions) and value ser/deser functions — never on
  RocksDB, blockchain, or config. Any KV store can back it; tests use in-memory
  (`SimpleMerklePatriciaTrie`, `KeyValueMerkleStorage`). This is why the same trie code
  serves Forest state, Bonsai root recomputation, and SNAP-sync range proofs.
- **Everything is a Visitor.** Point read, mutation, delete, commit, proof, and range scan
  are all `NodeVisitor`/`PathNodeVisitor` implementations. Adding a new traversal (e.g.
  SNAP `SnapCommitVisitor`, `RestoreVisitor`) means adding a visitor, not touching node
  types.
- **Two world-state formats behind one interface.** Forest = hash-addressed full node
  store (simple, archival, heavy on disk and node reads). Bonsai = flat live state + a
  reverse-diff journal (small, fast reads, but historical state must be *reconstructed*
  from trie-logs rather than read directly). The `DataStorageFormat` enum picks one; the
  EVM is oblivious because both implement `MutableWorldState`.
- **TrieLog stores prior AND updated per leaf.** That redundancy is deliberate: `updated`
  drives roll-forward, `prior` drives roll-back, so a single journal entry supports reorg
  in both directions and does not require re-executing blocks.
- **Nested updaters = EVM snapshots.** `WorldUpdater.parentUpdater()` lets each call frame
  buffer changes and either `commit()` into the parent or `revert()`, giving the EVM
  cheap, correct rollback of `REVERT`/failed calls without touching the trie.

## Notable patterns (the reusable idea)

**The `NodeLoader`/`NodeUpdater` two-function seam + the `WorldState` →
`MutableWorldState` → `WorldUpdater` interface stack.** Together they let besu swap an
entire storage philosophy (archival hash-store vs. pruned flat-store-with-journal) under a
fixed EVM contract. For fukuii the directly transferable move is: **define the
world-state/updater interface independent of the trie implementation, and make the trie
depend only on a load/store function pair** — so a thin/pruned (light/custody) mode and a
full/archival mode can coexist as two implementations of the same interface, exactly as
Forest and Bonsai do here.

Second reusable idea: **the TrieLog as a first-class, serializable, bidirectional
reverse-diff** (`{prior, updated}` per changed leaf) is a clean, storage-independent way to
get reorg rollback without archival trie nodes or block re-execution.

## Authority note

besu = fukuii's closest **JVM structural** trie/world-state authority — same language
family, same "trie engine + world-state interface stack" layering fukuii can mirror
directly, and the canonical implementation of the Bonsai/thin-trie + trie-log idea.
go-ethereum remains the authority for **canonical MPT byte-level behavior** (node
encoding, hashing, key nibble compaction) — validate wire/consensus-visible trie output
against geth, but mirror besu for interface *shape*. (For PoW/ETC specifically, core-geth
is the consensus authority per repo policy; besu's ETC support was removed Feb 2026, so
its state-trie code is a structural reference, not an ETC-behavior reference.)

## Gotchas / anti-patterns / things they later changed

- **Package rename churn.** The Bonsai code now lives under
  `ethereum/trie/pathbased/{common,bonsai}/` — an earlier `bonsai`-only layout was
  refactored to a `pathbased` common base with Bonsai as one implementation (so a second
  path-based format can share the accumulator/trielog machinery). Grep for `pathbased`,
  not just `bonsai`.
- **Bonsai historical reads are reconstructed, not read.** There is no historical trie to
  point at; `rollFullWorldStateToBlockHash` replays trie-logs. If the required trie-logs
  were pruned (`TrieLogPruner`) you cannot serve that historical state — a real
  operational constraint for light/pruned deployments and a reason archival nodes still
  want Forest (or Bonsai-archive).
- **TrieLog only tracks leaves.** `TrieLogLayer`'s own doc notes "only the 'Leaves' are
  tracked" — it is a state-change journal, not a structural trie diff; do not mistake it
  for a way to reconstruct intermediate trie nodes.
- **`setStatsDumpPeriodSec(0)` and per-segment caches** live on the KV side, not here — see
  `storage-persistence.md`; don't duplicate the RocksDB backend discussion into trie docs.
- **Retired-but-not-removed enum segments.** The Bonsai/Forest column-family split is fixed
  in the `KeyValueSegmentIdentifier` registry; segments are never renumbered for DB
  backward-compat (again a `storage-persistence.md` concern, cross-referenced only).
