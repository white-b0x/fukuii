# reth — storage-persistence

_Commit/branch documented: `3d76b93c243f8896f13a39ee865f87241fcd649b` (branch
`main`/`upstream`, 2026-07-01). Vendored at
`.claude/repo-references/clients/reth`. Documented 2026-07-13._

## Architecture summary

reth's storage is a **three-tier hybrid**, unusual among reference clients: a **hot MDBX**
key-value store (memory-mapped B+tree, `crates/storage/db/`) for mutable/recent state and the
canonical table schema; an **immutable static-file tier** (`crates/storage/nippy-jar` +
`crates/storage/provider/src/providers/static_file/`) holding append-only, compressed,
memory-mapped segments (headers/transactions/receipts/senders/changesets) that are written
directly as blocks arrive rather than migrated later from a "hot" period; and, as of the
in-flight **`storage_v2`** layout (`StorageSettings`, `db-api/src/models/metadata.rs`), a
**secondary RocksDB store** (`crates/storage/provider/src/providers/rocksdb/`) for exactly the
three highest-write-fanout indices — `TransactionHashNumbers`, `AccountsHistory`,
`StoragesHistory` — using RocksDB's `OptimisticTransactionDB` to get MDBX-like read-your-writes
transaction semantics from an LSM engine. State itself is **not** flat-only (unlike erigon):
reth persists a genuine hash/nibble-keyed trie-node store (`AccountsTrie`/`StoragesTrie` tables,
keyed by `StoredNibbles`/`StoredNibblesSubKey`), computed incrementally from flat
`HashedAccounts`/`HashedStorages` domains by a Patricia-trie algorithm that reads leaves flat
and persists only branch nodes — positioning reth between erigon (no leaf-node store at all)
and besu/fukuii (full node-hash-keyed store) on the flat-vs-trie spectrum. The whole storage
layer is generic over a `Table` trait (`db-api/src/table.rs`) and a `DatabaseProvider`/
`ProviderFactory` abstraction (`crates/storage/provider/`), and cursor/transaction lifetimes are
enforced by Rust's ownership system (`Drop` on the underlying libmdbx-rs/rust-rocksdb bindings)
rather than a caller-must-`.close()` convention.

## Key types / interfaces / files

### The `Table` trait and macro-generated schema (`crates/storage/db-api/src/table.rs`, `tables/mod.rs`)

- `crates/storage/db-api/src/table.rs:75-85` — `pub trait Table: Send + Sync + Debug + 'static`:
  `const NAME`, `const DUPSORT: bool`, `type Key: Key`, `type Value: Value` — every table is a
  distinct Rust *type* (a zero-sized marker struct), not a runtime string/enum value as in
  RocksDB column-family or MDBX DBI naming schemes. `Key`/`Value` in turn require `Encode`/
  `Decode`/`Compress`/`Decompress` (lines 40-68) — the codec is part of the table's type
  signature, so a caller cannot accidentally decode `Headers`' value as a `Receipts` value; it
  wouldn't compile.
- `crates/storage/db-api/src/table.rs:112-125` — `DupSort: Table { type SubKey: Key }` — MDBX's
  native sorted-duplicate-value tables (same mechanism as erigon's `DupSort`, see
  `erigon/storage-persistence.md`), also type-checked: a table is only `DupSort`-callable if it
  implements the trait.
- `crates/storage/db-api/src/tables/mod.rs:115-306` — the `tables! { table Name { type Key = …;
  type Value = …; [type SubKey = …;] } }` macro: from one declarative block it generates the
  marker struct, the `Table`/`DupSort` impls, a `Tables` enum (`ALL`, `COUNT`, `name()`,
  `is_dupsort()`, `FromStr`), and `tables_to_generic!` (a runtime-enum → compile-time-type
  dispatch macro) — the single source of truth for the entire schema, comparable to besu's
  `SegmentIdentifier` enum or erigon's `TableCfgItem` registry but code-generating the trait
  impls rather than just describing them.
- `crates/storage/db-api/src/tables/mod.rs:308-540` — the actual table list: `CanonicalHeaders`,
  `Headers`, `BlockBodyIndices`, `Transactions`, `TransactionHashNumbers`, `Receipts`,
  `Bytecodes`, `PlainAccountState`/`PlainStorageState` (execution-time working state),
  `AccountsHistory`/`StoragesHistory` (sharded change-index, `ShardedKey`), `AccountChangeSets`/
  `StorageChangeSets` (DupSort, pre-image of a changed value), `HashedAccounts`/`HashedStorages`
  (keccak-keyed, "in preparation for merklization" per the inline doc comment), `AccountsTrie`/
  `StoragesTrie` (the persisted trie-node store), `StageCheckpoints`, `PruneCheckpoints`,
  `VersionHistory`, `ChainState`, `Metadata`.
- `crates/storage/db-api/src/tables/mod.rs:542-572` — `PackedAccountsTrie`/`PackedStoragesTrie`:
  type-level *views* of the same underlying MDBX table under a different, more compact key
  encoding (`PackedStoredNibbles`, 33 bytes vs. the legacy `StoredNibbles`, 65 bytes) — a storage
  migration mechanism expressed purely as an additional `Table` impl, no schema/column-family
  change required.
- `crates/storage/db-api/src/transaction.rs:21-49` — `DbTx: Debug + Send`: `get<T: Table>`,
  `cursor_read<T: Table>() -> Self::Cursor<T>`, `entries<T: Table>()`, `commit(self)` (consumes
  `self`, so a transaction cannot be committed twice at compile time), `abort(self)`. Every
  table-scoped operation is generic over `T: Table`, so the compiler enforces the key/value types
  match the table at every call site.

### MDBX engine (`crates/storage/db/src/`, `crates/storage/libmdbx-rs/`)

- `crates/storage/db/src/implementation/mdbx/mod.rs:130-165` — `DatabaseArguments::new`: default
  geometry `size: 0..8 TERABYTE`, `growth_step: 4 GIGABYTE` — MDBX pre-reserves a huge virtual
  address-space map and grows the backing file in large increments (the mmap analogue of
  RocksDB's SST-file growth); a separate `DatabaseArguments::test()` uses a 64 MB/4 MB geometry
  specifically to avoid exhausting `vm.max_map_count` when many test DBs are open concurrently.
- `crates/storage/db/src/implementation/mdbx/mod.rs:103-121` — `SyncMode::Durable` (full fsync
  per commit) vs. `SyncMode::SafeNoSync` (skip fsync, keep DB structural integrity, accept losing
  the most recent transactions on crash) — an explicit, named durability/performance trade-off
  exposed as node config, comparable to fukuii's `walSizeLimit`/flush-policy knobs but framed as
  a binary mode rather than a size threshold.
- `crates/storage/db/src/mdbx.rs:11-13` — `ORPHAN_TABLES: &[&str] = &["AccountsTrieChangeSets",
  "StoragesTrieChangeSets"]`, dropped at database init — a retired-table cleanup list, the same
  "never silently leave a stale bucket around" discipline as besu's/erigon's deprecated-table
  handling, but proactively deleted rather than merely flagged.
- `crates/storage/db/src/mdbx.rs:15-27` — `warn_if_zfs`: a startup check (via `statfs` magic
  number) that warns if the datadir sits on a ZFS filesystem, because ZFS's own copy-on-write
  semantics compound with MDBX's copy-on-write B+tree and degrade performance significantly — a
  filesystem-level gotcha no other documented client calls out explicitly.
- `crates/storage/libmdbx-rs/src/transaction.rs:335-360` — `impl<K> Drop for
  TransactionInner<K>`: if a transaction was never explicitly `commit()`-ed, `Drop` aborts it
  (read transactions are reset and returned to a lock-free reuse pool; write transactions are
  routed through the `txn_manager` to abort cleanly) — comments explicitly note errors are
  swallowed here ("Drop should never panic… can cause double-panics during unwinding").
- `crates/storage/libmdbx-rs/src/cursor.rs:483-493` — `impl<K> Drop for Cursor<K>`: closes the
  native MDBX cursor unconditionally on drop, via `mdbx_cursor_close`, using a "renew on timeout"
  execution path so a cursor belonging to a since-timed-out transaction can still be closed
  safely.
- `crates/storage/db-common/src/init.rs` / `crates/storage/provider/src/providers/database/mod.rs:77-107`
  — `ProviderFactory<N>` holds `db: N::DB` (MDBX), `static_file_provider: StaticFileProvider<N::Primitives>`,
  and `rocksdb_provider: RocksDBProvider` side by side as peer fields, plus a `ReadOnlySyncState`
  (`last_synced_txnid`, `sync_lock`) that serializes catching a read-only factory's RocksDB
  secondary handle and static-file index up to the latest MDBX write transaction it has seen —
  the concrete mechanism keeping three independently-consistent storage engines in sync under one
  logical "provider."

### Static files (`crates/storage/nippy-jar/`, `crates/storage/provider/src/providers/static_file/`)

- `crates/storage/nippy-jar/src/lib.rs:79-113` — `NippyJar<H>`: "a specialized storage format
  designed for immutable data… organized into a columnar format, enabling column-based
  compression. Data retrieval entails consulting an offset list and fetching the data from file
  via `mmap`." Explicit warning at the top of the module: the format is "not hardened to safely
  read potentially malicious data" — an internal-only format, not a wire format.
- `crates/storage/nippy-jar/src/writer.rs` (`NippyJarWriter`) / `src/cursor.rs`
  (`NippyJarCursor`) — append-only writer and mmap-backed read cursor over `.jar`/`.off`/`.idx`
  file triads (data, offsets, index), with `compression/{lz4,zstd}.rs` column-level compressors.
- `crates/static-file/types/src/segment.rs:30-63` — `StaticFileSegment` enum: `Headers`
  (covers `CanonicalHeaders`+`Headers`+`HeaderTerminalDifficulties`), `Transactions`, `Receipts`,
  `TransactionSenders`, `AccountChangeSets`, `StorageChangeSets` — one static-file segment can
  absorb multiple MDBX tables' worth of data (Headers folds three tables into one segment).
  Doc comments spell out the on-disk row layout for the changeset segments explicitly
  (block-by-block, address-sorted).
- `crates/storage/provider/src/providers/static_file/manager.rs:109-118` — `StaticFileProvider<N>`
  doc comment: "manages all existing `StaticFileJarProvider`… responsible for reading and writing
  to static files" — headers/transactions/receipts explicitly named as the immutable-history
  content.
- `crates/storage/provider/src/providers/static_file/manager.rs:2104-2129` —
  `get_with_static_file_or_database`: the core read-routing primitive. Compares the requested
  block/tx number against `get_highest_static_file_block`/`get_highest_static_file_tx`; if the
  static-file tier already covers that number, reads from static files, otherwise falls back to
  an MDBX table read (used by `receipt()`, which prunes/keeps recent rows in
  `tables::Receipts` until they're frozen — `database/provider.rs:2125-2132`).
- `crates/storage/provider/src/providers/static_file/manager.rs:2558-2568` — `header_by_number`
  (and `sealed_header`, `headers_range`) instead route through
  `get_segment_provider_for_block(...).header_by_number(...)` with **no MDBX fallback at all** —
  headers have no "hot tier" window; they are written straight to static files on the block-write
  path (`writer.rs:1127-1204`, `append_header`/`append_transaction`) and only ever read from
  there. `crates/storage/db-api/src/tables/mod.rs`'s `Headers`/`Transactions` MDBX table
  definitions still exist in the schema (for legacy v1 databases / migration), but the
  v2/static-file code path never reads them for current data.
- `crates/storage/provider/src/providers/static_file/writer.rs:774-810` — `increment_block`:
  when the current static file's block range is exhausted, `commit()`s the file (freezing offsets
  and the `SegmentHeader`) and `open()`s the next one starting at `last_block + 1` — the concrete
  mechanism by which "append directly, seal once full" replaces a separate freeze-migration pass.
- `crates/storage/db/src/version.rs:8-40` and
  `crates/static-file/types/src/*` (`Version{Major, Minor, Patch}` embedded per static-file
  filename, referenced from erigon's doc as the same idea) — reth layers **three independent**
  versioning mechanisms: a whole-datadir `database.version` file (`DB_VERSION: u64 = 2`, MDBX
  schema), per-static-file-segment filename versions, and the node-scoped `StorageSettings.storage_v2`
  flag (below) — three different granularities for three different mutability profiles.

### The `storage_v2` migration and RocksDB secondary store (`crates/storage/provider/src/providers/rocksdb/`)

- `crates/storage/db-api/src/models/metadata.rs:6-27` — `StorageSettings { storage_v2: bool }`:
  "Controls whether this node uses v2 storage layout (static files + RocksDB routing) or v1/legacy
  layout (everything in MDBX)." `v2()` enables: receipts + transaction senders in static files,
  history indices in RocksDB, account/storage changesets in static files, and hashed state as the
  canonical state representation. `v1()` keeps everything in MDBX. This is a **per-node,
  persisted setting** (set at `init_genesis`/`init_db` time), not a compile-time or global build
  flag — different reth nodes can run different storage layouts side by side.
- `crates/storage/provider/src/providers/rocksdb/metrics.rs:9-13` — `ROCKSDB_TABLES`: exactly
  `TransactionHashNumbers`, `StoragesHistory`, `AccountsHistory` — the three sharded/high-fanout
  index tables, moved out of MDBX's B+tree (which pays copy-on-write costs proportional to
  random-insert volume) into RocksDB's LSM engine (which absorbs random writes via memtables +
  background compaction) once `storage_v2` is enabled.
- `crates/storage/provider/src/providers/rocksdb/provider.rs:203-242` (`default_options`) — RocksDB
  tuning explicitly cites the RocksDB wiki tuning guide: shared block cache across column families
  (`Cache::new_lru_cache`, `set_block_cache`), `set_compaction_pri(MinOverlappingRatio)`,
  `set_bottommost_compression_type(Zstd)` + `set_compression_type(Lz4)` (bottommost-level-only
  Zstd, faster Lz4 elsewhere — the same tiered-compression strategy RocksDB's own docs
  recommend), a `WriteBufferManager` capping total memtable memory across all column families
  (`DEFAULT_WRITE_BUFFER_MANAGER_SIZE`), and `set_wal_ttl_seconds(0)` + `set_wal_size_limit_mb(0)`
  ("delete obsolete WAL files immediately after all column families have flushed… no archival").
- `crates/storage/provider/src/providers/rocksdb/provider.rs:262-286` —
  `tx_hash_numbers_column_family_options`: a **per-table** override that disables both compression
  and bloom filters for `TransactionHashNumbers` specifically, with an inline rationale: keys are
  32-byte hashes (incompressible), values are varint `u64` (too small to benefit), and "every
  lookup expects a hit" so bloom filters (which only help reject non-existent keys) provide no
  benefit — a concrete example of table-shape-aware RocksDB tuning going the opposite direction
  from the shared defaults.
- `crates/storage/provider/src/providers/rocksdb/provider.rs:395-400` — the read-write handle is
  an `OptimisticTransactionDB`, not a plain `rocksdb::DB`, with an explicit comment: "Use
  `OptimisticTransactionDB` for MDBX-like transaction semantics (read-your-writes, rollback)…
  uses optimistic concurrency control (conflict detection at commit time)" — RocksDB's
  transactional variant chosen specifically so the secondary RocksDB store can offer the same
  transactional guarantees callers already get from the MDBX-backed provider, rather than exposing
  a weaker eventually-consistent side-store.
- `crates/storage/provider/src/providers/rocksdb/provider.rs:1528-1540`,
  `2486-2500`, `2687-2740` — `RocksReadSnapshot`, `RocksTx` (wrapping `rocksdb::Transaction`),
  `RocksDBIter`/`RocksTxIter` — no explicit `Drop` impls in reth's own wrapper types; cleanup is
  delegated entirely to the underlying `rust-rocksdb` crate's own `Drop` implementations on
  `Transaction`/`SnapshotWithThreadMode`/`DBIteratorWithThreadMode` — the same "Rust ownership
  gives the guarantee for free" pattern as the MDBX side, now applied to RocksDB usage too.

### State trie — hash/nibble-keyed node store, computed incrementally (`crates/trie/`)

- `crates/trie/trie/src/trie.rs:29-45` — `StateRoot<T, H>`: parameterized over a
  `TrieCursorFactory` (`T`, reads/writes `AccountsTrie`/`StoragesTrie`) and a
  `HashedCursorFactory` (`H`, reads `HashedAccounts`/`HashedStorages`) — the state-root algorithm
  is generic over where trie nodes and hashed leaves come from, not hardwired to MDBX.
- `crates/trie/db/src/trie_cursor.rs:1-15` — `DatabaseTrieCursorFactory` (the concrete MDBX-backed
  implementation): reads `AccountsTrie`(`StoredNibbles -> BranchNodeCompact`)/`StoragesTrie`
  (`B256 -> StoredNibblesSubKey -> BranchNodeCompact`, DupSort) directly — **branch nodes are
  persisted**, keyed by their position in the trie (nibble path), giving reth an actual
  hash/path-keyed node store on disk (unlike erigon, which has none).
- `crates/trie/db/src/trie_cursor.rs:17-83` — `TrieKeyAdapter` trait with `LegacyKeyAdapter`
  (`StoredNibbles`, 65-byte keys, 1 nibble/byte) and a packed adapter (`PackedStoredNibbles`,
  33-byte keys) selected at runtime by `DatabaseTrieCursorFactory` — a live storage-format
  migration expressed as two implementations of the same trait rather than a schema rewrite.
- `crates/trie/trie/src/trie_cursor/in_memory.rs:1-50` — `InMemoryTrieCursorFactory<CF, T>`:
  wraps an underlying `TrieCursorFactory` with an in-memory overlay of not-yet-persisted
  `TrieUpdatesSorted`, "always giv[ing] precedence to the data from the trie updates" — the
  pending-block-execution equivalent of the DB-cursor-plus-overlay pattern, used before a block's
  trie changes are committed to MDBX.

## Design decisions & rationale

- **A third storage engine (RocksDB) introduced deliberately, not accidentally.** reth already
  had MDBX + static files; adding RocksDB for exactly three sharded/high-cardinality index tables
  is a targeted response to a specific workload mismatch (random-insert-heavy indices vs. MDBX's
  copy-on-write B+tree cost model) rather than a wholesale engine swap — the opposite instinct
  from erigon's "replace the trie with flat Domains" or besu's "replace forest with Bonsai."
  `StorageSettings.storage_v2` gates this per-node, so it reads as an incremental, reversible
  migration rather than a hard cutover.
- **Static files as the default destination for immutable data, written promptly rather than
  migrated from a hot period.** Headers and transactions are appended directly to static files as
  blocks arrive (no MDBX "recent data" window for them at all); only receipts (subject to pruning)
  and a few other segments retain an MDBX fallback path via `get_with_static_file_or_database`.
  This is a stronger commitment to the static-file tier than the "freeze once old enough" framing
  used elsewhere (e.g. erigon's periodic snapshot-freeze pass, besu's/nethermind's pruning of
  RocksDB directly) — reth treats "is this row part of canonical history" as the freeze criterion,
  not "is this row old."
- **Trie nodes persisted, but keyed by trie path, not by node hash.** `StoredNibbles`/
  `StoredNibblesSubKey` keys encode a *position in the trie*, so a re-org that changes a subtree's
  contents overwrites the same key rather than creating an orphaned hash-keyed node the way
  fukuii's `ArchiveNodeStorage`/`ReferenceCountNodeStorage` (hash-keyed) would. This trades away
  content-addressing (no natural node deduplication across trie versions) for update locality
  (an update to one path is one write, not a hash recompute cascading through unrelated content
  addresses) — a different point on the trade-off curve than either fukuii/besu's hash-addressed
  stores or erigon's leafless flat-only model.
- **Everything table-scoped is a Rust generic, not a runtime string/enum.** The `Table` trait +
  `tables!` macro means the compiler — not a runtime schema registry — enforces that a
  `TransactionHashNumbers` cursor can never accidentally decode a `Receipts` value. This is a
  stronger static guarantee than RocksDB column-family-by-string-name (fukuii, besu, nethermind)
  or even MDBX-native `TableCfgItem` string-keyed configs (erigon) can offer in a non-Rust host
  language.
- **Cursor/transaction lifetime safety delegated entirely to Rust's ownership model.** Neither
  reth's own `Tx`/`Cursor` wrappers nor its RocksDB wrappers implement custom cleanup logic beyond
  what the underlying libmdbx-rs/rust-rocksdb `Drop` impls already provide — `commit(self)`
  consuming the transaction by value means "committed twice" and "used after commit" are both
  compile errors, not runtime `IllegalStateException`s. This is structurally the strongest
  guarantee among all documented clients (stronger than JVM's caller-must-`.close()`, stronger
  even than Go's transaction-owns-its-cursors pattern in erigon, which is a *runtime* invariant
  enforced by application code rather than the language).

## Notable patterns (the reusable ideas)

- **`OptimisticTransactionDB` for a secondary RocksDB store that must match a primary engine's
  transactional guarantees.** Choosing RocksDB's transactional variant specifically so a
  secondary side-store doesn't downgrade the consistency model callers already rely on — directly
  relevant if fukuii or any RocksDB-primary client ever adds a second storage engine for a
  workload-mismatched table subset.
- **Per-table RocksDB tuning that goes the *opposite* direction from shared defaults when the key
  shape warrants it.** `tx_hash_numbers_column_family_options` disabling compression AND bloom
  filters for a hash-keyed, always-hit table is a concrete, cited (not hand-waved) example worth
  matching against fukuii's own per-column-family RocksDB config (`db/AGENTS.md`).
- **Table identity as a Rust type, not a config value.** The `Table`/`DupSort` trait pair +
  `tables!` macro is the single strongest schema-safety mechanism across all documented clients —
  worth naming explicitly in the Phase-2 observations table as "type-level schema" vs. "string/
  enum-keyed schema" (besu/erigon/nethermind/fukuii all fall into the latter category, being
  JVM/Go/Scala without Rust's zero-cost phantom-type tables).
- **"Append directly to the immutable tier, freeze on range completion" instead of "write hot,
  migrate cold later."** `NippyJarWriter::increment_block`'s freeze-and-reopen pattern removes an
  entire class of "hot tier grew larger than intended because the freeze pass didn't run" bugs
  (an M2-style failure mode) by making the write path itself boundary-aware.
- **Trie nodes keyed by path (nibbles), not by content hash — the third point on the
  flat-vs-trie spectrum.** Besu documents "flat state + thin hash-keyed trie for proofs" (Bonsai);
  erigon documents "flat state, no persisted leaf trie nodes, branch-node cache only, addressed
  by prefix"; reth is close to erigon's branch-node-persistence approach but goes one step further
  and persists branch nodes keyed by nibble-path rather than existing only as a merge-time cache —
  useful as a third concrete design point when the Phase-2 `state-trie` subsystem doc is written.
- **Storage-layout versioning as a live migration flag, not a big-bang cutover.**
  `StorageSettings.storage_v2` being a per-node persisted setting (rather than a build-time
  feature or a one-way `database.version` bump) is a pattern worth naming for any future fukuii
  storage-layout change that needs a gradual rollout.

## Authority note

**reth is a strong, but split, authority.** Per the project-wide authority model
(`docs/research/clients/README.md`), reth is named for "modularity / SDK (`NodeTypes`,
compile-time chain families)" — that authority is confirmed here at the `Table`/`ProviderFactory`
level (trait-generic storage, compile-time-enforced schema). But this document surfaces a second,
narrower authority: **reth is now also a live, in-production precedent for adding RocksDB as a
secondary/targeted store alongside a different primary engine** — directly relevant to VAULT's own
domain (`db/AGENTS.md`) even though fukuii's primary engine (RocksDB) and reth's primary engine
(MDBX) are swapped relative to each other. The `OptimisticTransactionDB` choice and the per-table
compression/bloom-filter tuning (`tx_hash_numbers_column_family_options`) are concrete, citable
RocksDB tuning decisions worth cross-checking against fukuii's own `RocksDbConfig`.

Scope caveats:
- reth is **not** an authority for ETC-specific persistence semantics (core-geth remains that
  authority) — nothing documented here is ETC-specific; reth's `olympia` branch (visible in
  `git branch -a` on the vendored repo) was not examined for this document.
- reth is not the authority for "how to tune a JVM+RocksDB client" (besu is) or "flat-only state"
  (erigon is) — reth's trie-node-persistence choice sits between those two positions, not past
  either of them.
- The `storage_v2`/RocksDB-secondary-store migration is **actively in flight** at the documented
  commit (both v1 and v2 code paths coexist, gated by `StorageSettings`) — treat this document as
  describing an architecture mid-transition, not a settled end state; re-check `StorageSettings`
  and `ROCKSDB_TABLES` if this doc is revisited later, as the v2 table set may grow.

## Gotchas / anti-patterns / things they later changed

- **Legacy MDBX `Headers`/`Transactions` tables still exist in the schema but are dead for
  current writes under v2.** `tables::Headers`/`tables::Transactions` remain valid `Table` impls
  (needed for v1/legacy databases and migration), but the v2 read/write paths never touch them —
  a "type still compiles, but is functionally retired for new data" situation comparable to
  erigon's `PlainState`/`*Deprecated` tables kept alive purely for a migration path.
  `ORPHAN_TABLES` (`db/src/mdbx.rs:12`) shows the *next* stage of this lifecycle: tables that were
  fully retired and are now actively dropped at database init.
- **Three separate, independently-scoped versioning mechanisms coexist** (`database.version`
  whole-datadir file, per-static-file-segment filename versions, `StorageSettings.storage_v2` node
  flag). None of the three subsumes the others — a node could in principle have a current
  `database.version`, old-format static files, and `storage_v2 = false`, all at once. Worth
  tracking as a real operational complexity, not just a documentation nuance, if fukuii ever
  layers multiple independent storage-format version markers.
- **ZFS + MDBX is a documented performance trap, not just a suggestion.** `warn_if_zfs`'s
  message is specific enough to be a real operational gotcha other clients don't surface: ZFS's
  own copy-on-write semantics compound with MDBX's copy-on-write B+tree, and the fix is
  "use ext4 or xfs instead," not a tuning knob.
- **The RocksDB secondary store adds a real synchronization surface, not a free lunch.**
  `ReadOnlySyncState { last_synced_txnid, sync_lock }` on `ProviderFactory` exists specifically
  because a read-only factory's RocksDB handle and static-file index can lag the MDBX write
  transaction they logically belong with — a second engine bought a genuine cross-engine
  consistency problem that a single-engine design (fukuii's RocksDB-only, MDBX-only clients like
  erigon) does not have to solve at all.
- **`reth`'s own `CLAUDE.md` explicitly forbids modifying vendored libmdbx sources**
  (`crates/storage/libmdbx-rs/mdbx-sys/libmdbx/`) — the same "never edit the vendored C library"
  discipline any client wrapping a native storage engine needs, worth remembering when reading
  fukuii's own RocksDB JNI dependency boundary.
