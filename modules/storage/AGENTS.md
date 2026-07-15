# `modules/storage` — L2 subsystem breadcrumb

_Byte-pure persistence layer. Depends **down-only** on `domain`, `common` — an upward `.dependsOn` is a
compile error, and it imports **no `trie` node types and no `crypto`** (byte-pure boundary, below). Full
record: [`docs/architecture/fukuii-rebuild/implementation-reports/03-L2-storage-trie.md`](../../docs/architecture/fukuii-rebuild/implementation-reports/03-L2-storage-trie.md);
plan: [`plan/L2.md`](../../docs/architecture/fukuii-rebuild/plan/L2.md); byte-cited RX evidence:
[`plan/rx/L2.md`](../../docs/architecture/fukuii-rebuild/plan/rx/L2.md). Read the record before structural
changes here._

## What lives here

The KV substrate every layer above persists through: `DataSource` (the IO/fs2 contract) with
`RocksDbDataSource` + `EphemDataSource` backends; `enum Namespace` (the column-family registry, besu
`SegmentIdentifier` flags); `StorageProfile` (per-network×role profile — five live axes + a reserved
`engine` seam); `INodeStorage` scheme-indirection (`Hash`/`Path`/`Both`) + `NodeLocation`; `SchemaMarker`;
composable pruning (`PruningStrategy`/`PruningBookkeeping`/`PruningStore`); the `ColdStore` freezer +
byte-canonical `Era1Shard`/`CheckpointArchive`/`FlatStorage`; `PersistedTrieLogStore`; `ChainWeight` (TD).

## Invariants (do not break)

- **Byte-pure boundary (DoD grep gate).** `storage` imports **no `trie` node types** and has **no `crypto`
  dep** — it moves opaque bytes keyed by `(Namespace, key)`. Hashes are **injected** by the caller; the
  `EmptyNode` hash is a duplicated consensus-fixed literal, never computed here. This is the seam that keeps
  L2 out of the old 13-package cycle — do not add a `trie`/`crypto`/`rlp` dependency.
- **`NodeLocation` is account-scoped `(owner, path)`.** `owner = None` → state trie, `Some(accountKey)` →
  that account's storage sub-trie; the path-scheme physical key is `owner ++ path ++ nodeHash` (D4 hash-tail
  folded in). A bare nibble path would collide storage-subtrie nodes at the same path across accounts.
- **Scheme dual-read is directional + migration-gated.** A read probes the *other* scheme **only when
  `migrationInProgress`** (steady-state single probe, geth-aligned); writes/deletes touch the **active
  scheme alone** (D3 delete-asymmetry — a path-scheme delete leaves any hash-keyed archival copy intact).
- **Atomic block + ChainWeight write (BUG-W7).** A block (Header/Body/Receipts) and its `ChainWeight` (TD)
  land in **one `WriteBatch`** — a crash-consistency DoD gate. `WriteBatch` is all-or-nothing (L2-F4), WAL
  durability is contract (L2-F2), and the `dbLock` is **per-instance** (R2 — never a process-global).
  Canonical TD is sourced from local `ChainWeight`, never the wire (the L6 §5 invariant this CF backs).
- **`prune(safeHeight)` R7 barrier.** No prune — incremental refcount or online full-prune — may drop a
  node at/above `safeHeight`.
- **`Era1Shard`/`CheckpointArchive` are byte-canonical** (records sorted, per-shard MerkleFold accumulator,
  `ShardManifest`). These byte formats are the substrate the L7 distribution transports (HTTP / BitTorrent /
  DB-snapshot) serve **additively** — do not change the on-disk byte layout without re-cutting the format.

## Boundaries (occupancy deferred — seam built here, consumer above)

Account-`owner` composition → L4 `WorldStateProxy`; typed `putBlock` → L4/L5; SNAP range-serve + the
era1/BitTorrent distribution transports → L7; the R7 prune-barrier consumer registry → L9. `storage` builds
the seams/primitives/formats; occupancy lives at the consuming layer (see the record's Deferrals).
