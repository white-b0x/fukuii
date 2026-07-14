# nethermind — storage-persistence

_Commit/branch documented: `0d09a09edd0a861d21c647ceaa7f9f5ea1c74255` (branch `upstream`,
`origin/upstream`, 2026-07-01). Vendored at
`.claude/repo-references/clients/nethermind`. Documented 2026-07-13._

## Architecture summary

nethermind is a third **RocksDB**-backed JVM-peer client (C#/.NET, RocksDbSharp bindings),
structurally close to besu (both RocksDB, both column-family-per-purpose) but with a more
elaborate performance layer bolted onto the raw RocksDB wrapper: a generic **`IDb`/`IColumnsDb<T>`**
key-value abstraction where column families are typed by an `enum` (`ColumnsDb<T> where T :
struct, Enum` — one CF per enum value, resolved via zero-boxing enum-to-int conversion rather
than a name/string lookup at the hot path); a **thread-local pooled-iterator** read path
(`IteratorManager`) that reuses native `RocksIterator` handles across point-lookup calls
instead of opening/closing one per read; a classic hash-keyed single-CF world-state store
(`NodeStorage`) that supports **two interchangeable key schemes** — `Hash` (legacy, geth-style
content-addressed dedup) and `HalfPath` (default, path-prefixed keys for locality, same
motivating idea as geth's pathdb) — selectable per-datadir and even mid-flight during full
pruning; a sharded, strategy-driven **in-memory dirty-node cache** (`TrieStore`, 256 shards by
default) that decouples *when to evict from RAM* (`IPruningStrategy`) from *when to flush to
disk* (`IPersistenceStrategy`); an **online full-pruning** facade (`FullPruningDb`) that
duplicates live writes into a freshly-created sibling DB while a background visitor walks and
copies the live trie, then atomically swaps the pointer — nethermind's answer to besu's
mark-and-sweep Forest pruning and geth's offline pruner; and, newest (2025+), an **experimental
in-house flat-state engine** (`Nethermind.State.Flat`) that is *not* Paprika (Paprika does not
appear in this vendored tree at all) but nethermind's own RocksDB-column-family-backed
snapshot/compaction pipeline with async channel-driven persistence.

## Key types / interfaces / files

### KV abstraction (typed columns, not string namespaces)
- `Nethermind.Db/IDb.cs:10` — the single-DB contract: `IKeyValueStoreWithBatching` +
  `IDbMeta` (`Flush`, `Clear`, `Compact`, `SetWriteBuffer`, a `DbMetric` struct with
  size/cache/index/memtable/read/write counters) + `IDisposable`. `GetAll`/`GetAllKeys`/
  `GetAllValues(bool ordered)` are the streaming-iteration entry points.
- `Nethermind.Db/IColumnsDb.cs:10` — `IColumnsDb<TKey>`: `GetColumnDb(key)`, `ColumnKeys`,
  `StartWriteBatch()` → `IColumnsWriteBatch<TKey>`, `CreateSnapshot()` →
  `IColumnDbSnapshot<TKey>`. `TKey` is almost always a plain C# `enum` (`ReceiptsColumns`,
  `BlobTxsColumns`, `FlatDbColumns`, `LogIndex`'s topic-column enums) — the column-family
  registry is a first-class generic type parameter, not a shared string/byte namespace table.
- `Nethermind.Db/DbNames.cs:6` — the flat list of top-level (non-columned) logical databases:
  `state`, `flat`, `code`, `blocks`, `headers`, `blockNumbers`, `blockAccessLists`, `receipts`,
  `blockInfos`, `badBlocks`, `metadata`, `blobTransactions`, `discoveryNodes`,
  `discoveryV5Nodes`, `peers`, `logIndex`, `preimage`. Some of these (`receipts`,
  `blobTransactions`, `flat`, `logIndex`) are themselves `IColumnsDb<T>` with their own
  per-purpose column enum, so the real column-family count is materially higher than this list.
- `Nethermind.Db/FullPruning/IFullPruningDb.cs` / `IPruningContext.cs` — the online-pruning
  contract layered on top of `IDb` (see below).

### RocksDB backend (`Nethermind.Db.Rocks/`)
- `DbOnTheRocks.cs:36` — the shared concrete backend
  (`IDb, ITunableDb, IReadOnlyNativeKeyValueStore, ISortedKeyValueStore,
  IMergeableKeyValueStore, IKeyValueStoreWithSnapshot`). `Init`/`BuildOptions` (line 491) parses
  a **semicolon-delimited RocksDB options string** (`dbConfig.RocksDbOptions +
  AdditionalRocksDbOptions`, fed through `rocksdb_get_options_from_string` — the native
  option-string parser, not hand-set C# properties for most knobs) per logical DB *and* per
  column family (`GetForDatabase(Name, columnFamily)`, line 172) — every DB/column pair can
  carry a fully independent tuning string.
- `DbOnTheRocks.cs:512-528` — block-cache resolution order: an explicit per-DB
  `dbConfig.BlockCache` override → else a **shared cache** (`sharedCache` param, see
  `HyperClockCacheWrapper.cs` below) if the DB's options string doesn't set its own
  `block_based_table_factory.block_cache` → else RocksDB's built-in 32 MB default. Several hot
  small DBs (code: 16 MB, blob-tx: 32 MB, receipts: 8 MB) opt out of the shared cache with an
  explicit per-table size in their options string (`Config/DbConfig.cs`).
- `HyperClockCacheWrapper.cs:11` — a `SafeHandle`-wrapped native
  **`rocksdb_cache_create_hyper_clock`** (RocksDB's newer clock-based block cache, an
  alternative to `LRUCache` with lower lock contention under high concurrency), created once and
  shared across every DB that doesn't opt out (`RocksDbFactory.cs:38`) — `GC.AddMemoryPressure`/
  `RemoveMemoryPressure` are wired to the native capacity so the .NET GC accounts for the
  off-heap allocation.
- `DbOnTheRocks.cs:1750` — `IteratorManager`: a **thread-local pooled-iterator** for
  point-lookup reads flagged with `ReadFlags.HintReadAhead*`. `Rent`/`Return` (`RentWrapper`
  struct, `IDisposable`) hand out a cached native `RocksIterator` per thread instead of creating
  one per call; a 10-second `Timer` periodically clears idle pools, and any single iterator is
  force-disposed and replaced after `IteratorUsageLimit = 1_000_000` uses so it doesn't live
  forever. Distinct from `CreateIterator`/`GetAllCore` (line 1085, 1196) used for full scans,
  which always open a fresh iterator.
- `DbOnTheRocks.cs:1077-1229` — `GetAll`/`GetAllKeys`/`GetAllValues`: each opens one
  `Iterator` up front and returns a **C# iterator-block (`yield return`) enumerable**
  (`GetAllCore`/`GetAllKeysCore`/`GetAllValuesCore`) wrapped in `try { seek/iterate } finally {
  iterator.Dispose() }` — the `finally` runs automatically when the caller's `foreach` disposes
  the enumerator (or throws), so release is compiler-enforced via `IDisposable`, not
  convention-based like besu's `Stream.onClose`. Every iterator op
  (`SeekToFirst`/`Next`/`Dispose`) is wrapped with `RocksDbSharpException` → `CreateMarkerIfCorrupt`
  translation (writes a `corrupt.marker` sentinel file, see Gotchas) before rethrowing.
- `DbOnTheRocks.cs:1231-1376` — `StartWriteBatch()` → `RocksDbWriteBatch` (`IWriteBatch`):
  accumulates into a native `WriteBatch`, **committed only on `Dispose()`**
  (`_db.Write(_rocksBatch, ...)`, line 1304) — not per-`Set`/`Delete` call. `WriteBatch`
  instances are pooled per-thread (`[ThreadStatic] _reusableWriteBatch`, `CreateWriteBatch`/
  `ReturnWriteBatch`, lines 1264-1284) to cut native alloc/free churn, but only reused when the
  serialized batch is small (`< 16 KiB`) — larger batches are disposed rather than pooled.
  `FlushOnTooManyWrites` (line 1359) is the one place atomicity is *deliberately* broken (see
  Gotchas).
- `ColumnsDb.cs:18` — `ColumnsDb<T> : DbOnTheRocks, IColumnsDb<T>` — one `ColumnDb` wrapper
  per enum value (`ColumnDb.cs`), backed by the same underlying `RocksDb` handle with a distinct
  `ColumnFamilyHandle`. `CreateSnapshot()` (line 132) returns a `ColumnDbSnapshot` sharing **two
  `ReadOptions` instances across every column reader** rather than one pair per column, and
  caches the column-key array/max-ordinal on the parent `ColumnsDb<T>` — both explicitly to
  avoid finalizer-queue pressure from RocksDbSharp's `ReadOptions` (which has a native finalizer
  but is not itself `IDisposable`; `RocksDbReader.DestroyReadOptions`, line 66-70, explicitly
  destroys the native handle and calls `GC.SuppressFinalize` to stop the finalizer from re-running).
- `RocksDbReader.cs:24` — the shared `Get`/`GetSpan`/`FirstKey`/`LastKey`/`GetViewBetween`
  implementation used by `DbOnTheRocks`, `ColumnDb`, and `ColumnDbSnapshot` alike, so the
  "resolve a `ColumnFamilyHandle`, dispatch through `IteratorManager` if hinted, else plain
  `Get`" logic lives in exactly one place regardless of which of the three owns the native handle.
- `ITunableDb.cs` — `Tune(TuneType)`: `Default`/`WriteBias`/`HeavyWrite`/`AggressiveHeavyWrite`/
  `DisableCompaction`/`EnableBlobFiles`/`HashDb` — a **runtime-mutable compaction/write-buffer
  profile** switched during bulk-write phases (fast/snap sync) and switched back afterward, via
  live RocksDB `SetOptions` calls (`DbOnTheRocks.cs:1611`, `ApplyOptions`) — not just
  once-at-open `Options`.

### World state — NodeStorage: Hash vs HalfPath key schemes
- `Nethermind.Trie/INodeStorage.cs:9` — the storage-agnostic trie-node contract:
  `Get(address, path, keccak)`/`Set(...)`/`StartWriteBatch()`/`KeyExists`/`Flush`/`Compact`, plus
  `KeyScheme { Hash, HalfPath, Current }` and `RequirePath` (true only for `HalfPath` — lets
  hash-scheme code paths skip path tracking entirely for a perf win).
- `Nethermind.Trie/NodeStorage.cs:35-93` — `GetHalfPathNodeStoragePathSpan`: the **half-path
  key layout**, a documented 42-byte (state) / 74-byte (storage) key —
  `section byte | 8 bytes of path | path-length byte | 32-byte node hash`, with a further
  **top-level-vs-lower-level state split** (section byte 0 vs 1 at `path.Length <=
  TopStateBoundary(=5)`) specifically because top-of-trie nodes are "up to 5 times bigger... and
  grew a lot due to pruning," so mixing them with lower nodes hurt block-cache hit rate for the
  much-more-numerous lower nodes (inline rationale comment, lines 57-64). Storage-trie keys are
  further namespaced by a leading account-address byte-range (section byte 2) so account and
  storage nodes never collide.
- `NodeStorage.cs:102-130` — `Get`: tries the *current* scheme's key first, then **falls back to
  the other scheme's key** (`?? _keyValueStore.Get(GetHashBasedStoragePath(...))`) — this is what
  makes a live in-place scheme migration possible (see `FullPruner` below): reads work across
  the boundary while old-scheme keys are still present and new-scheme keys are being written.
- `Nethermind.Trie/Pruning/TrieStore.cs:29` — `TrieStore : ITrieStore, IPruningTrieStore`, the
  **in-memory dirty-node cache** sitting above `INodeStorage`. Sharded
  (`_shardedDirtyNodeCount`, default 256, `pruningConfig.DirtyNodeShardBit`) into per-shard
  `TrieStoreDirtyNodesCache` + `ConcurrentDictionary<HashAndTinyPath, Hash256?>` "persisted
  hashes" maps used for **obsolete-key deletion tracking under HalfPath** (see Gotchas). Cleanly
  separates two orthogonal decisions via strategy interfaces:
  - `IPruningStrategy.cs:6` — `DeleteObsoleteKeys`, `ShouldPruneDirtyNode(state)`,
    `ShouldPrunePersistedNode(state)` — *when to evict from the in-memory dirty cache* (e.g.
    `MemoryLimit.cs:9`: dirty-cache-bytes ≥ a configured cap).
  - `IPersistenceStrategy.cs:6` — `ShouldPersist(blockNumber)` — *when to flush a committed
    block's nodes to `INodeStorage`* (e.g. every N blocks, or at a reorg-safe depth).
- `TrieStore.cs:952-979` — a distinct **persisted-node partial-eviction pass** (separate from
  dirty-node eviction): `targetPruneMemory = PersistedMemoryUsedByDirtyCache *
  _prunePersistedNodePortion`, prunes a proportional number of shards each cycle rather than a
  full sweep — keeps a warm read-cache of recently-persisted nodes without unbounded growth.

### Online full pruning (`FullPruningDb` + `FullPruner` + `CopyTreeVisitor`)
- `Nethermind.Db/FullPruning/FullPruningDb.cs:23` — an `IDb` **facade** wrapping `_currentDb`.
  While `_pruningContext` is non-null, every `Set`/`PutSpan`/write-batch call is **duplicated**
  into `pruningContext.CloningDb` (`Duplicate`, line 109) in addition to the live DB; reads
  optionally duplicate-on-read too (`DuplicateReads`, line 56-59) so a key touched only by a
  historical read during the copy window still lands in the new DB. `StartWriteBatch()` (line
  128) returns a `DuplicatingWriteBatch` that fans every `Set`/`Merge` out to both underlying
  batches when pruning is active.
- `Nethermind.Blockchain/FullPruning/FullPruner.cs:118-185` — `RunFullPruning`: waits for the
  main chain to advance past a safe block, waits for `BestPersistedState` to catch up (so no
  cached-but-unpersisted state is lost), then waits an additional `PruningBoundary` blocks past
  the state-to-copy (**reorg safety margin** before starting the copy). `TryCopyTrie` (line
  224-301) drives the actual copy: creates a `targetNodeStorage` via `_nodeStorageFactory`
  (**can request a different `KeyScheme` than the live DB** — this is how a datadir migrates
  Hash→HalfPath or vice versa, disallowing HalfPath→Hash specifically because of the
  write-during-copy duplication risk, inline comment lines 241-249), runs a parallel
  `CopyTreeVisitor` over the state trie at the chosen historical root
  (`_stateReader.RunTreeVisitor`), and on success advances a **state-availability floor marker**
  (`_stateBoundary.OldestStateBlock = stateToCopy`) *before* calling `pruning.Commit()` — the
  comment at line 276-279 states this ordering is deliberate: `FullPruningDb` mirrors that
  marker write into the cloning DB too, so a crash between the marker write and the swap cannot
  leave the new DB live without the floor already durable in it.
- `FullPruningDb.cs:236-249` — `FinishPruning`/`Commit`: atomically swaps `_currentDb` to the
  cloning DB (`Interlocked.Exchange`) and clears (deletes) the old DB — this is the
  "copy-live-state-to-a-fresh-column-family/DB, then swap the pointer" pattern named in the task
  brief. `PruningContext.Dispose()` (line 278-293) is the failure path: if never `Commit()`-ed,
  it clears/deletes the half-finished cloning DB instead.
- `Nethermind.Blockchain/FullPruning/CopyTreeVisitor.cs:23` — `ITreeVisitor<TContext>`
  implementation that walks branch/extension/leaf nodes and re-persists each node's raw RLP
  unchanged into the target storage (`ConcurrentNodeWriteBatcher`, a batching wrapper not shown
  in full) — a straight structural copy, not a re-derivation; `VisitMissingNode` (line 50) throws
  `TrieException` rather than silently skipping a hole, so a corrupt source trie aborts pruning
  instead of producing a silently-incomplete copy.

### Flat state (`Nethermind.State.Flat` — nethermind's own, not Paprika)
- **Paprika is not present in this vendored tree** — `grep -r "Paprika"` returns only one
  unrelated hit in `Nethermind.Trie/BatchedTrieVisitor.cs`; there is no `Paprika` project,
  csproj reference, or namespace. nethermind's flat-state direction here is a distinct, in-house,
  2025-dated (`FlatDbManager.cs:1` copyright header) module still built directly on RocksDB.
- `Nethermind.State.Flat/FlatDbColumns.cs` — the column enum for this engine: `Metadata`,
  `Account`, `Storage`, `StateNodes`, `StateTopNodes`, `StorageNodes`, `FallbackNodes` — flat
  account/storage values live in dedicated columns, but a **thin trie is still retained**
  (`StateNodes`/`StateTopNodes`/`StorageNodes`/`FallbackNodes`) for merkle-proof support — the
  same "flat plus retained proof trie" shape as besu's Bonsai, not erigon's fully-trie-free
  Domains.
- `Nethermind.State.Flat/Persistence/RocksDbPersistence.cs:10` — implements `IPersistence` via
  `IColumnsDb<FlatDbColumns>`. `CreateReader`/`CreateWriteBatch` both start from
  `db.CreateSnapshot()` — every read view and every write transaction is **snapshot-anchored**.
  `CreateWriteBatch(from, to, flags)` (line 58) does an explicit **compare-and-swap-style
  precondition check**: reads the DB's current persisted `StateId` from the snapshot and throws
  `InvalidOperationException` if it doesn't match the caller's expected `from` state — writes
  cannot silently apply on top of the wrong state.
- `FlatDbManager.cs:18-100` — the orchestrator: three independent `System.Threading.Channels`
  pipelines running as background tasks — a **compactor** (merges/dedupes recent snapshot deltas
  before persistence), a **trie-node-cache populator** (runs in parallel specifically because
  "the node cache is kinda important for performance, so we want it populated as quickly as
  possible" — inline comment), and a **persistence** stage that decides what actually gets
  written to RocksDB. A `_compactorStallTimeout` derived from `0.5 * blockTime * compactSize`
  functions as a liveness guard: if compaction can't keep up with block production at half the
  slot budget, that's treated as a hard failure condition rather than silently falling behind.
- `Nethermind.Trie/NodeStorageCache.cs:8` — a lock-free `SeqlockCache<NodeKey, byte[]>` sitting
  in front of persisted-node lookups, toggleable at runtime (`Enabled`) — a read cache layer
  *below* `TrieStore`'s dirty-node cache and *above* RocksDB's own block cache.

## Design decisions & rationale

- **Enum-typed column families, not string/byte namespaces.** `IColumnsDb<T> where T : struct,
  Enum` makes the column-family set a compile-time-checked C# type rather than a runtime string
  table — `ColumnsDb.cs:256-261`'s `EnumToInt` even special-cases the enum's underlying
  primitive size (`int`/`byte`/`short`) to stay allocation-free. Trade-off vs besu's
  `SegmentIdentifier` enum-as-config-carrier: nethermind's enum is purely an identity/ordinal
  key — per-CF tuning (block cache size, compression, etc.) lives separately in `DbConfig`'s
  per-DB-name option strings, not attached to the enum value itself.
- **Options-string-driven RocksDB tuning, fed through the native parser.** Rather than setting
  each `ColumnFamilyOptions`/`DbOptions` property individually in C#, `DbConfig` builds long
  semicolon-delimited option strings per DB (`StateDbRocksDbOptions`, `FlatDbRocksDbOptions`,
  `CodeDbRocksDbOptions`, ...) parsed via `rocksdb_get_options_from_string` — every knob is
  documented inline with *why* (e.g. `unordered_write=true` on the state DB specifically because
  writes are "done in parallel batch and therefore, not atomic, and the read goes through
  triestore first anyway" — `DbConfig.cs:227-230`). This makes the options themselves the
  primary tuning documentation, with measured before/after write-amplification numbers recorded
  directly in `DbOnTheRocks.cs`'s `Tune` comments (see Gotchas).
- **Two interchangeable node-key schemes with a supported live migration path.** `HalfPath` is
  the default (path-locality for cache/compaction behavior, the same underlying idea as geth's
  pathdb), but `Hash` (pure content-addressed dedup) remains fully supported, and
  `FullPruner`/`NodeStorage`'s dual-scheme fallback read lets a datadir migrate between them
  *during* a full-pruning pass rather than requiring a dedicated one-off migration tool — at the
  cost of disallowing the HalfPath→Hash direction (write-during-copy duplication makes it unsafe,
  see Gotchas).
- **Pruning strategy and persistence strategy are orthogonal, pluggable interfaces.**
  `IPruningStrategy` (when to evict from RAM) and `IPersistenceStrategy` (when to flush to disk)
  are separate one-method interfaces composed into `TrieStore`, rather than one monolithic
  "pruning mode" enum — cleanly mirrors fukuii's own `ArchiveNodeStorage`/
  `ReferenceCountNodeStorage` split at the storage-implementation level, but nethermind pushes
  the decision one layer up, into policy objects above a single `NodeStorage` implementation.
- **Full pruning is online (duplicate-writes-during-copy), not offline/stop-the-world.**
  `FullPruningDb` keeps the node fully live (reading and writing the old DB) while a background
  visitor walks a *fixed historical* trie root into a new DB, then atomically swaps — the same
  goal as besu's `TrieLogPruner`/offline `TrieLogHelper`, but nethermind's mechanism is a
  whole-DB copy-and-swap rather than incremental trie-log deletion, and it explicitly supports
  simultaneous key-scheme migration as a side effect of the copy.
- **A dedicated experimental flat-state engine (`State.Flat`), still RocksDB-backed.**
  Rather than adopting Paprika (a separate nethermind project, a from-scratch storage engine)
  or building flat-state directly into the primary `NodeStorage`/`TrieStore` path, nethermind
  is developing `Nethermind.State.Flat` as an isolated, swappable module with its own snapshot/
  compaction/persistence pipeline and CAS-checked write batches — evolutionary, additive
  architecture rather than a rewrite of the existing hash/half-path path.

## Notable patterns (the reusable ideas)

- **Thread-local pooled RocksDB iterators for point lookups (`IteratorManager`).** Distinct from
  the "one iterator per full scan" pattern every other client uses — nethermind additionally
  reuses native iterator handles across repeated point-lookup calls on the same thread, with a
  periodic timer-based sweep and a usage-count-based forced replacement. A genuinely different
  lever from geth/besu/fukuii's uniformly "open-scan-close" iterator lifecycle, worth evaluating
  if fukuii's `unboundedScan` batches show iterator-creation overhead as a bottleneck.
- **CAS-checked write batches against an expected prior state (`State.Flat`'s
  `CreateWriteBatch(from, to, ...)`).** A cheap correctness backstop for any storage layer that
  applies incremental deltas: read the current persisted state marker inside the same snapshot
  used to build the write, and refuse to apply if it doesn't match what the caller expected.
- **Dual-key-scheme storage with a supported live migration, not a hard cutover.** `NodeStorage`'s
  "try current scheme, fall back to the other" read path plus `FullPruner`'s ability to change
  scheme mid-copy is a genuinely different shape than besu's Forest/Bonsai (two fully separate,
  non-interoperating code paths, migration = full resync) or fukuii's presumed single fixed key
  scheme per pruning mode.
- **Options-string tuning profiles switched live at runtime (`ITunableDb.Tune`).** A named,
  swappable `DbOptions`/`ColumnFamilyOptions` profile keyed by workload phase
  (`WriteBias`/`HeavyWrite`/`AggressiveHeavyWrite`/`DisableCompaction`), applied via
  `SetOptions()` mid-sync and reverted afterward — worth comparing against how fukuii currently
  handles (or doesn't handle) different tuning during fast/SNAP sync vs steady-state block
  processing.
- **Persisted-node partial eviction as a distinct pass from dirty-node eviction.**
  `TrieStore`'s two-tier cache (dirty nodes not yet flushed vs already-persisted nodes kept warm)
  with independently tunable eviction targets is a finer-grained cache model than a single LRU.

## Authority note

**nethermind is a strong second RocksDB structural data point alongside besu** — both confirm
"column families per logical dataset, independently tunable" as the dominant JVM/CLR-client
RocksDB pattern (fukuii's own `Namespaces.scala` byte-prefix scheme is the same idea, one
level less type-safe than either). Where besu is the closer *structural* mirror to fukuii
(enum-as-`SegmentIdentifier`-config-carrier, `RocksDBColumnarKeyValueStorage` base class),
**nethermind is the strongest available authority specifically for online, live-traffic-safe
full pruning** (`FullPruningDb`'s duplicate-writes-during-copy-then-swap mechanism) and for
**path/hash key-scheme interchangeability with a supported live migration** — neither besu
(Forest/Bonsai are non-interoperating, resync-to-migrate) nor fukuii (per `db/AGENTS.md`, a
fixed pruning-mode-per-node-storage-implementation model) currently offer a live scheme-migration
path.

Caveats on authority scope:
- nethermind is **not** an authority for ETC-specific persistence semantics — core-geth remains
  that authority per the project-wide authority model; nothing documented here is ETC-specific.
- `Nethermind.State.Flat` is explicitly experimental/newer (2025+ copyright headers, an
  `_inlineCompaction` debug-only synchronous mode still present) — treat it as a *direction*
  worth watching, not yet a battle-tested authority the way besu's Bonsai or erigon's Domains are.
  Paprika (nethermind's other, separate flat-storage project) is not represented in this vendored
  tree at all and cannot be documented from this source.
- For the flat-state-without-any-trie extreme, erigon's Domains/MDBX remains the more radical
  alternative; nethermind's `State.Flat`, like besu's Bonsai, keeps a retained thin proof trie —
  the middle ground, not the far end.

## Gotchas / anti-patterns / things they later changed

- **`unordered_write=true` on the state DB deliberately breaks write-batch atomicity.** Per the
  inline comment at `Config/DbConfig.cs:227-230`: "a concurrent read may read item on start of
  batch, but not end of batch" — accepted specifically for the state DB because writes are
  already parallel-batched and non-atomic by construction upstream, and reads always go through
  `TrieStore` first (which has its own consistency story) rather than reading raw RocksDB
  concurrently with a write. **This is the opposite of fukuii's Iron Rule 2** ("batches are
  atomic; partial flushes corrupt state") — worth an explicit note that this particular
  divergence is a deliberate, documented trade-off in nethermind, not an oversight, and is scoped
  to one specific DB rather than applied globally.
- **`RocksDbWriteBatch.FlushOnTooManyWrites` also breaks atomicity, but only for `NoWAL` writes.**
  `DbOnTheRocks.cs:1248-1253`: a very large single write batch can stall other concurrent writes
  under RocksDB's internal parallelism, so batches with `WriteFlags.DisableWAL` are auto-flushed
  every 256 writes (`MaxWritesOnNoWal`) instead of committing as one atomic unit — explicitly
  documented as a deliberate atomicity-for-throughput trade that "removes atomicity so it's only
  turned on when the NoWAL flag is on."
- **HalfPath→Hash key-scheme migration is unsupported and silently downgraded to HalfPath.**
  `FullPruner.cs:241-249`: because of the write-during-copy read/write duplication, a HalfPath key
  written mid-copy could land in a target DB whose code paths don't track path (Hash scheme), so
  the migration is refused and silently continues in HalfPath instead, with only a `Warn` log —
  worth checking whether fukuii would want a harder failure mode for an equivalent situation if
  it ever supports live scheme migration.
- **`RocksDBColumnarKeyValueStorage`-equivalent stats dump / native crash workarounds are present
  here too, in a different shape.** Not `setStatsDumpPeriodSec(0)` like besu, but the extensive
  inline commentary throughout `DbOnTheRocks.Tune` (`DisableCompaction`, lines 1575-1594)
  documents real production incidents from aggressive tuning: "the whole system crashes" without
  an OS-level open-file-handle limit, and "peer drops" / "network stack hangs" when a state read
  during snap sync blocks on an uncompacted DB. These are operational gotchas fukuii should treat
  as evidence, not folklore, if it ever exposes similar live-tunable compaction knobs.
- **`ReadOptions`/native handle finalizer pressure is treated as a real, previously-unaddressed
  performance bug, not a hypothetical.** The extensive comments in `ColumnsDb.cs`'s
  `ColumnDbSnapshot` (shared `ReadOptions` across all column readers, explicit
  `DestroyReadOptions` + `GC.SuppressFinalize`) are dated fixes for "Gen1/Gen2 GC pressure from
  finalizer queue buildup" caused by RocksDbSharp's `ReadOptions` type having a native finalizer
  but not implementing `IDisposable` itself — a CLR/RocksDbSharp-specific footgun with no direct
  JVM or fukuii/Scala analogue, but a reminder that *any* native-handle wrapper without an
  explicit dispose path is a latent GC-pressure bug waiting to be measured.
- **`CreateMarkerIfCorrupt` writes a `corrupt.marker` sentinel file on any `RocksDbSharpException`
  surfaced from an iterator or write-batch operation**, not just on open — a wider corruption-
  detection net than "check on startup only," at the cost of potentially marking a DB corrupt
  from a transient/non-corruption RocksDB exception if the exception-classification logic
  (not fully read here) is imprecise.
