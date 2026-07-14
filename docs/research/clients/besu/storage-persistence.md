# besu — storage-persistence

_Commit/branch documented: `3fd233a4f93556e932f734d8feecbad4a047ff6` (branch `upstream`,
`origin/upstream`, 2026-07-01). Vendored at
`.claude/repo-references/clients/besu`. Documented 2026-07-13._

## Architecture summary

besu layers storage as: a backend-agnostic **`SegmentedKeyValueStorage`** interface
(RocksDB **column families**, the direct analogue of RocksDB's segmented API) sitting under a
**`StorageProvider`** that hands out one logical `SegmentedKeyValueStorage`/`KeyValueStorage`
view per subsystem; a **`KeyValueSegmentIdentifier`** enum that is the single source of truth for
every column family (name, byte-id, which `DataStorageFormat`s include it, static-data/high-spec/
GC/cache flags); and, above that, **two mutually-exclusive world-state formats** selected by
`DataStorageFormat` — legacy **Forest** (one column family holding *every* trie node, hash-keyed,
mark-and-sweep pruning) and the modern default **Bonsai/path-based** (flat account/storage state
in dedicated column families + a separate `TRIE_LOG_STORAGE` diff-journal column family that
replaces most trie nodes entirely). RocksDB tuning (block cache, write buffers, WAL sizing,
per-segment `ColumnFamilyOptions`) lives in a single `RocksDBColumnarKeyValueStorage` base class
shared by both concrete backends (plain `TransactionDB` and `OptimisticTransactionDB`, the latter
adding MVCC snapshots).

## Key types / interfaces / files

### KV abstraction (segmented + unsegmented)
- `plugin-api/.../storage/SegmentedKeyValueStorage.java:29` — the segmented contract: `get`,
  `getNearestBefore`/`getNearestAfter` (seek-based nearest-match, RocksDB `seekForPrev`/`seek`
  under the hood), `stream*`/`streamKeys*` (segment-scoped), `tryDelete` (non-blocking, RocksDB
  `NoSlowdown` write option), `startTransaction`/`startLowPriorityTransaction`, `clear(segment)`.
- `plugin-api/.../storage/SegmentedKeyValueStorage.java:202` — `NearestKeyValue` record: the
  wrapper type for seek-based nearest-key lookups, used by state-range-proof/snap-sync-style scans.
- `plugin-api/.../storage/KeyValueStorage.java:37` — the older unsegmented contract (same shape,
  single implicit segment) — used for e.g. `TRIE_LOG_STORAGE`, `VARIABLES`.
- `plugin-api/.../storage/SegmentIdentifier.java:24` — the segment/column-family namespace
  contract: `getName()`, `getId()` (the raw column-family bytes), `containsStaticData()`
  (append-only data eligible for BlobDB), `isEligibleToHighSpecFlag()` (bigger block cache under
  `--Xplugin-rocksdb-high-spec-enabled`), `isStaticDataGarbageCollectionEnabled()`,
  `isCacheIndexAndFilterBlocks()` (index/filter blocks in the shared block cache vs. unbounded
  native memory per SST — default false).
- `ethereum/core/.../keyvalue/KeyValueSegmentIdentifier.java:27` — the **column-family registry**:
  one enum value per segment, each carrying an `EnumSet<DataStorageFormat>` saying which storage
  format(s) include it. `BLOCKCHAIN` (id `{1}`) is in every format; `WORLD_STATE` (id `{2}`) is
  Forest-only; `ACCOUNT_INFO_STATE`/`CODE_STORAGE`/`ACCOUNT_STORAGE_STORAGE`/`TRIE_BRANCH_STORAGE`/
  `TRIE_LOG_STORAGE` (ids `{6..10}`) are Bonsai-only. Retired segments (`PRIVATE_TRANSACTIONS`,
  `GOQUORUM_PRIVATE_STORAGE`) stay in the enum "for DB backwards compatibility" — never
  renumbered, never removed.
- `ethereum/core/.../keyvalue/KeyValueStorageProvider.java` — the `StorageProvider`
  implementation; `getStorageBySegmentIdentifier(s)` is how every subsystem gets its
  `SegmentedKeyValueStorage`/`KeyValueStorage` handle without touching RocksDB directly.

### RocksDB backend (`plugins/rocksdb/`)
- `.../segmented/RocksDBColumnarKeyValueStorage.java:73` — the shared base class for both
  concrete backends. Owns global `DBOptions` (`setGlobalOptions`, line 312: `maxOpenFiles`,
  `Statistics`, **`setStatsDumpPeriodSec(0)`** — disables RocksDB's native periodic stats dump
  because "the native dump path has been a source of JNI SIGSEGVs on some versions/platforms
  under load", `maxTotalWalSize = 1 GiB`, `recycleLogFileNum` derived from
  `WAL_MAX_TOTAL_SIZE / EXPECTED_WAL_FILE_SIZE`) and per-segment `ColumnFamilyDescriptor`s
  (`createColumnDescriptor`, line 192: LZ4 compression, `BlockBasedTableConfig` with a **per-segment
  `LRUCache`** — not one shared cache — sized to `ROCKSDB_BLOCKCACHE_SIZE_HIGH_SPEC` (1 GiB) only
  when both `isHighSpec` and the segment opts in via `isEligibleToHighSpecFlag()`, else
  `configuration.getCacheCapacity()`; `BloomFilter(10, false)` + `setPartitionFilters(true)`;
  `ROCKSDB_BLOCK_SIZE = 32768`).
- `.../RocksDBColumnarKeyValueStorage.java:238-269` — `configureBlobDBForSegment`: segments with
  `containsStaticData() == true` get RocksDB's **BlobDB** (`setEnableBlobFiles(true)`,
  `setMinBlobSize(100)`, `setBlobCompressionType(LZ4)`), keeping large immutable values (receipts,
  trie logs) out of the LSM tree/SST files to reduce compaction write-amplification — this is
  besu's structural equivalent of geth's freezer, but per-column-family rather than a separate
  flat-file store.
- `.../RocksDBColumnarKeyValueStorage.java:421-490` — `get`/`getNearestBefore`/`getNearestAfter`/
  `stream*`/`streamKeys` all resolve a `SegmentIdentifier` to a `ColumnFamilyHandle` via
  `safeColumnHandle` (line 413, throws if the handle map doesn't contain the segment — never
  silently reads the wrong/default column family).
- `.../segmented/TransactionDBRocksDBColumnarKeyValueStorage.java:34` — the plain `TransactionDB`
  backend; `startTransaction`/`startLowPriorityTransaction` (line 96: `WriteOptions.setLowPri(true)`
  — RocksDB throttles this transaction's writes more aggressively under compaction back-pressure;
  used for background/non-critical writes).
- `.../segmented/OptimisticRocksDBColumnarKeyValueStorage.java:35` — the `OptimisticTransactionDB`
  backend, adds `takeSnapshot()` (line 111) for MVCC point-in-time reads.
- `.../segmented/RocksDBSnapshot.java:28` — a reference-counted wrapper around one
  `db.getSnapshot()`, so multiple `RocksDBColumnarKeyValueSnapshot` transactions can share one
  underlying RocksDB snapshot and it's released exactly once.
- `.../configuration/RocksDBConfiguration.java:21` / `RocksDBCLIOptions.java:25` — tunables:
  `maxOpenFiles` (auto-derived from available host memory — `calculateMaxOpenFiles`, line 240:
  1024 default up to 16384 at ≥32 GiB), `cacheCapacity` (default 128 MiB, `DEFAULT_CACHE_CAPACITY
  = 134217728`), `backgroundThreadCount` (default 4), `isHighSpec` (opt-in 1 GiB block cache on
  high-spec-eligible segments), `enableReadCacheForSnapshots`, plus BlobDB GC knobs
  (`blobGarbageCollectionAgeCutoff`/`ForceThreshold`) exposed per-segment via
  `isBlockchainGarbageCollectionEnabled`.
- `.../configuration/BaseVersionedStorageFormat.java` — **explicit on-disk schema versioning**:
  an enum of `(DataStorageFormat, version)` pairs (`FOREST_ORIGINAL(1)` →
  `FOREST_WITH_VARIABLES(2)` → `FOREST_WITH_RECEIPT_COMPACTION(3)`; same 3-step ladder for
  Bonsai), each version documenting *why* it exists (moving variables to their own CF for BlobDB
  effectiveness; enabling receipt compaction). Persisted via
  `configuration/DatabaseMetadata.java:39` as a `DATABASE_METADATA.json` file in the datadir
  (Jackson-serialized `VersionedStorageFormat`), read on startup to detect/reject/migrate a
  mismatched schema version rather than silently reading a stale layout.

### Iterator lifecycle
- `RocksDbIterator.java:34` — wraps a `RocksIterator` behind `java.util.Iterator` +
  `AutoCloseable`, with an **`AtomicBoolean closed` guard** making `close()` idempotent
  (`close()`, line 156-160) and `assertOpen()` (line 149) throwing `IllegalStateException` on any
  use-after-close — the same "guarded, idempotent release" shape geth uses for Pebble iterators.
- `RocksDbIterator.java:106-147` — `toStream()`/`toStreamKeys()` attach the close via
  **`Stream.onClose(this::close)`** — the iterator is only released when the *returned Java
  `Stream`* is closed (try-with-resources on the `Stream`, or an explicit `.close()`). Several
  call sites in `RocksDBColumnarKeyValueStorage` (`stream`, `streamFromKey`, `streamKeys`, lines
  460-490) create the `RocksIterator` and hand it straight to `RocksDbIterator.create(...).toStream()`
  with **no surrounding try-with-resources** at the creation site itself — release is entirely
  the *caller's* responsibility once they have the `Stream` handle. `getNearestBefore`/
  `getNearestAfter` (lines 434-457) are the exception: those use `try (RocksIterator ...) { ... }`
  directly, so a single seek-and-check never depends on a caller remembering to close a `Stream`.
- `FlatDbStrategy.java:230-241` — `toNavigableMap` shows the required idiom: collect the
  `Stream`, then **explicitly call `pairStream.close()`** afterward — this is the pattern every
  caller of `stream()`/`streamFromKey()`/`streamKeys()` must follow by convention; nothing in the
  type system enforces it beyond `Stream` itself being `AutoCloseable`.

### World state — two mutually exclusive formats
- `plugin-api/.../storage/DataStorageFormat.java:18` — `FOREST` ("store all tries"), `BONSAI`
  ("store one trie, and trie logs to roll forward and backward"), `X_BONSAI_ARCHIVE` (Bonsai +
  retains historical state via `*_ARCHIVE` column families). `isBonsaiFormat()` groups the latter
  two.
- `ethereum/core/.../forest/storage/ForestWorldStateKeyValueStorage.java:37` — the classic
  design: **one column family, every trie node keyed by its hash** (`getTrieNode`, line 68), code
  keyed by code hash. `prune(Predicate<byte[]> inUseCheck)` (line 90) is a **mark-and-sweep**:
  stream every key, test a liveness predicate, `tryDelete` non-live keys under a `ReentrantLock`
  — the classic "walk the whole trie store and delete anything unreferenced" pattern, same shape
  as fukuii's `ReferenceCountNodeStorage`/`ArchiveNodeStorage` family
  (`db/storage/ArchiveNodeStorage.scala`, `db/storage/ReferenceCountNodeStorage.scala`).
- `ethereum/core/.../pathbased/common/storage/PathBasedWorldStateKeyValueStorage.java:49` — the
  Bonsai/path-based base: `composedWorldStateStorage` (one `SegmentedKeyValueStorage` spanning
  `ACCOUNT_INFO_STATE`/`CODE_STORAGE`/`ACCOUNT_STORAGE_STORAGE`/`TRIE_BRANCH_STORAGE`) +
  `trieLogStorage` (a separate `KeyValueStorage` for `TRIE_LOG_STORAGE`). `TRIE_BRANCH_STORAGE`
  still holds a **thin trie** (root hash + branch nodes needed for merkle proofs), while
  `ACCOUNT_INFO_STATE`/`ACCOUNT_STORAGE_STORAGE` hold the account/slot values **flat**, keyed
  directly by account/slot hash — the same flat-vs-trie-node split geth's pathdb makes, but
  besu keeps it as separate RocksDB column families rather than a single keyspace with different
  key prefixes.
- `.../pathbased/bonsai/storage/BonsaiWorldStateKeyValueStorage.java:58` — the concrete Bonsai
  storage: wraps a `BonsaiFlatDbStrategyProvider` (chooses **full** vs **partial** flat-DB read
  strategy, `FlatDbMode`) and an optional `FlatDbCacheManager` (`VersionedFlatDbCacheManager` — a
  version-stamped cross-block read cache for account/storage lookups, opt-in via
  `getBonsaiCrossBlockCacheEnabled()`; `FlatDbCacheManager.NO_OP_CACHE` otherwise).
- `.../common/storage/flat/FlatDbStrategy.java:42` — the flat-read/write abstraction:
  `putFlatAccount`/`removeFlatAccount`, `putFlatAccountStorageValueByStorageSlotHash`, streamed
  range reads (`streamAccountFlatDatabase`/`streamStorageFlatDatabase`, seek-based, hash-ordered)
  used for snap-sync-style range proofs directly off the flat state — no trie walk needed for a
  contiguous key range.
- `.../common/trielog/TrieLogPruner.java:47` — the **online trie-log pruner**: a
  `TrieLogEvent.TrieLogObserver` that, on every new trie log written, enqueues it into a
  `TreeMultimap<Long, Hash>` (descending block number → block hash, to also catch forked/orphan
  trie logs at the same height) and deletes the oldest entries once `numBlocksToRetain` (line 57,
  189: `chainHeadBlockNumber - numBlocksToRetain`, optionally floored at the finalized block via
  `requireFinalizedBlock`) is exceeded, batched by `pruningLimit` per invocation. This is the
  mechanism that keeps Bonsai's reorg/rollback window bounded — analogous to geth's pathdb
  state-history freezer, but implemented as RocksDB deletes on a dedicated column family instead
  of a separate flat-file store.
- `app/.../cli/subcommands/storage/TrieLogHelper.java` — the **offline** counterpart: an
  operator subcommand (`besu storage trie-log prune`) that re-derives and prunes trie logs in
  batch outside of live sync, for datadirs that predate `TrieLogPruner` or need a one-off deep
  prune.

## Design decisions & rationale

- **Column families, not key prefixes.** Every logical dataset is a real RocksDB column family
  (`ColumnFamilyDescriptor` per `SegmentIdentifier`), each with independently tunable
  `ColumnFamilyOptions` (compression, blob-DB, block cache sizing, static-data flags). This is a
  structural choice, not just organizational: static/append-only segments (blockchain data, trie
  logs) can opt into BlobDB and skip index/filter caching; hot segments (state) can opt into the
  high-spec 1 GiB cache. geth cannot express this per-dataset tuning because everything shares one
  keyspace/one set of `Options`.
- **`SegmentIdentifier` as an enum, never renumbered.** Retired segments (`PRIVATE_TRANSACTIONS`,
  `GOQUORUM_PRIVATE_STORAGE`) keep their byte IDs forever "for DB backwards compatibility" — column
  family IDs are a wire format for existing datadirs, so besu treats removing/renumbering one as
  the same class of break as changing a serialization format.
- **Explicit, versioned, persisted schema (`DATABASE_METADATA.json`).** Rather than inferring
  schema shape from what's present on disk, besu writes an explicit `(format, version)` pair and
  checks it on startup. Each version bump documents *why* (e.g. moving `VARIABLES` to its own
  column family specifically to make BlobDB more effective for `BLOCKCHAIN`). This makes "what
  schema is this datadir" a checked precondition instead of an assumption.
- **Two formats, one interface (`WorldStateKeyValueStorage`), zero shared code paths.** Forest and
  Bonsai are not variations of one code path — `ForestWorldStateKeyValueStorage` and
  `BonsaiWorldStateKeyValueStorage`/`PathBasedWorldStateKeyValueStorage` are separate class
  hierarchies that both implement `WorldStateKeyValueStorage`. This lets Bonsai fully replace its
  storage model (flat state + trie-log diff journal, no full trie-node store) instead of degrading
  gracefully from Forest's shape.
- **Trie-log pruning is online and incremental, not just an offline sweep.** `TrieLogPruner` is
  wired as an event observer on every new trie log, so the reorg window stays bounded continuously
  during sync — the offline `TrieLogHelper` subcommand exists for datadirs that need catch-up or
  migration, not as the primary mechanism.
- **BlobDB per eligible segment, not a separate freezer store.** Where geth physically separates
  hot (LSM) and cold (freezer flat files) storage into different subsystems, besu keeps everything
  in RocksDB and lets **BlobDB** (a RocksDB feature: large values stored outside the LSM/SST
  files, referenced by pointer) achieve a similar write-amplification reduction for static/append-
  only segments, without introducing a second storage engine.
- **Non-blocking `tryDelete`.** `SegmentedKeyValueStorage.tryDelete` (RocksDB `WriteOptions
  .setNoSlowdown(true)`) returns `false` rather than blocking when a write lock can't be acquired
  instantly — used by background/best-effort deletion paths (pruning, `ForestWorldStateKeyValueStorage
  .prune`) that shouldn't stall the hot write path.

## Notable patterns (the reusable ideas)

- **`SegmentIdentifier` as a self-describing config carrier** — not just a name/id pair, but the
  full per-segment tuning contract (static-data, high-spec-eligible, GC-eligible, cache-index-and-
  filter-blocks) attached directly to the enum value that also defines which `DataStorageFormat`s
  include it. One place to reason about a column family's entire lifecycle.
- **Guarded idempotent iterator release + `Stream.onClose` composition** — `RocksDbIterator`'s
  `AtomicBoolean closed` guard is the same idempotent-close shape as geth's Pebble wrapper; wiring
  release to `Stream.onClose` is besu's Java-idiomatic way to make "close the stream" and "release
  the native iterator" the same action, at the cost of relying on callers to actually close the
  `Stream` (no compiler enforcement, no last-resort finalizer, no leak metric — see Gotchas).
- **Reference-counted snapshot sharing (`RocksDBSnapshot`)** — one native `db.getSnapshot()`
  backing multiple logical read-only transactions, released once. Directly relevant if fukuii ever
  needs MVCC-style consistent multi-read views (e.g. concurrent RPC + block-import reads against
  the same historical state) without paying for one native snapshot per reader.
- **Explicit on-disk schema version + migration ladder (`BaseVersionedStorageFormat` +
  `DATABASE_METADATA.json`)** — the single most exportable pattern for fukuii: an append-only enum
  of `(format, version)` with per-version rationale comments, persisted and checked at startup.
- **Online trie-log pruning as an event-driven observer, not a cron job or manual command.**

## Authority note

**besu is the strongest structural authority for fukuii's storage layer.** Both are JVM clients
backing everything with **RocksDB column families** (fukuii: `db/storage/Namespaces.scala`'s
18 single-byte namespace prefixes map 1:1 to real RocksDB `ColumnFamilyDescriptor`s opened in
`RocksDbDataSource.scala:626-628` — the exact same shape as besu's `KeyValueSegmentIdentifier` →
`ColumnFamilyDescriptor` in `RocksDBColumnarKeyValueStorage.java:192-243`, not geth's single-
keyspace byte-prefix scheme). Where geth is the canonical *ETH/PoS baseline* and erigon is the
alternative *flat-state/MDBX* authority, besu is the authority for **"how do you actually tune and
segment a RocksDB-backed multi-consensus JVM client"** — the closest apples-to-apples comparison
available to fukuii's own `DataSource`/RocksDB code.

Caveats on authority scope:
- besu is **not** an authority for ETC-specific persistence semantics (core-geth remains that
  authority per the project-wide authority model) — nothing here is ETC-specific; this is purely
  structural/mechanical storage-engine guidance.
- For the *flat-state-without-a-trie* extreme (erigon's Domains, no trie-node storage at all),
  erigon remains the more radical alternative; besu's Bonsai format is the middle ground — flat
  state **plus** a retained thin trie for proofs, which is architecturally closer to what fukuii
  would need if it ever pursued a flat-storage migration (fukuii already has `FlatAccountStorage`/
  `FlatSlotStorage` per `db/AGENTS.md` — worth a direct Bonsai comparison in Phase 3/4).

## Gotchas / anti-patterns / things they later changed

- **Stream-based iterator release has no compiler or runtime backstop.** Unlike geth's `Release()`-
  mandated `Iterator` interface (a hard contract with a `liveIterGauge` metric), besu's
  `RocksIterator` → `RocksDbIterator` → `Stream.onClose` chain leaks silently if a caller drops the
  `Stream` reference without `.close()` or try-with-resources — there is no interface-level
  enforcement and no exposed live-iterator metric in the code read here. `getNearestBefore`/
  `getNearestAfter` sidestep this entirely by using `try (RocksIterator ...) {}` directly instead
  of routing through a `Stream`; the plain `stream()`/`streamFromKey()`/`streamKeys()` methods do
  not. **fukuii's own batched-iterator design (`RocksDbDataSource.scala` — `unboundedScan` opens,
  drains, and closes a native `RocksIterator` per self-contained batch, so no native iterator
  outlives a single batch regardless of caller discipline, per the `#1355` fix referenced in that
  file) is structurally more defensive than besu's own pattern here** — worth preserving rather
  than "aligning to besu" on this specific point.
- **`RocksDBColumnarKeyValueStorage.setStatsDumpPeriodSec(0)`** disables RocksDB's native periodic
  stats dump specifically because it "has been a source of JNI SIGSEGVs on some versions/platforms
  under load" (inline comment, line 318-320) — a documented native-crash workaround, not a
  performance choice; worth checking whether fukuii's RocksDB JNI binding has (or needs) the same
  guard.
- **Forest and Bonsai are fully separate code paths with no automatic conversion** — an existing
  datadir keeps its `DataStorageFormat` (checked via `DATABASE_METADATA.json`); switching requires
  an explicit resync/migration, the same "scheme is sticky per datadir" discipline geth enforces
  for hashdb/pathdb.
- **Retired column families are never removed or renumbered**, only left unused — a real
  Chesterton's Fence pattern in the storage layer itself (`PRIVATE_TRANSACTIONS`,
  `GOQUORUM_PRIVATE_STORAGE`, `PRUNING_STATE` for Forest-only pruning bookkeeping that Bonsai
  doesn't need).
- **`tryDelete`'s `NoSlowdown` write option can silently no-op** — callers must check the boolean
  return and not assume the key was removed; `ForestWorldStateKeyValueStorage.prune` treats a
  `false` as "try again on a future sweep," not an error.
