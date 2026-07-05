# db — Storage Layer

<!-- breadcrumb-currency: directory/file listing verified against source tree 2026-07-05 (a68dbec1f); re-verify when subpackages are added/removed/renamed, not on every code change inside existing files -->

RocksDB-backed persistence behind a `DataSource[K, V]`-shaped abstraction — production code
never calls RocksDB directly; everything routes through `DataSource`, so `EphemDataSource`
(in-memory) can substitute in tests without touching callers. See
`.claude/agent-protocols/storage-rocksdb.md` for the full contract, iterator-lifecycle rules,
and column-family/namespace discipline.

## Directory Structure

| Path | Purpose |
|------|---------|
| `db/dataSource/` | The `DataSource` abstraction itself + both implementations |
| `db/components/` | DI/wiring layer — which `DataSource`/`Storages` implementation gets built |
| `db/cache/` | LRU/map caching wrappers, independent of any one storage type |
| `db/storage/` | ~30 typed storage components, one (or a small family) per logical data collection |
| `db/storage/encoding/` | Shared encoding helpers for storage keys/values |
| `db/storage/pruning/` | Pruning-mode package object |

## Key Components

**`dataSource/`**: `DataSource.scala` (the contract — `get`/`getOptimized`/
`update(Seq[DataUpdate])`/batch semantics); `RocksDbDataSource.scala` (production);
`EphemDataSource.scala` (in-memory, tests + light-client/staging paths);
`DataSourceUpdate.scala`/`DataSourceBatchUpdate.scala`; `RocksDbCacheMetrics.scala`.

**`components/`** — the DI seam: `DataSourceComponent`/`EphemDataSourceComponent`/
`RocksDbDataSourceComponent` (which concrete `DataSource` gets wired in), `StoragesComponent`/
`Storages.scala` (aggregates every `storage/` component).

**`cache/`** — `Cache.scala` (interface), `LruCache.scala`/`MapCache.scala` (implementations),
`CacheComponent.scala` (wiring), `AppCaches.scala` (app-wide cache aggregate).

**`storage/`** — one file per logical column family (`Namespaces.scala` has the full list of 18
namespace byte-prefixes). Notable groups:
- Chain data: `BlockHeadersStorage`, `BlockBodiesStorage`, `ReceiptStorage`, `ChainWeightStorage`
- Trie/state: `NodeStorage`, `MptStorage`, `StateStorage`, `EvmCodeStorage`, and the
  pruning-mode node-storage family (`ArchiveNodeStorage`, `ReferenceCountNodeStorage`/
  `CachedReferenceCountedStorage`, `ReadOnlyNodeStorage`, `PathNodeStorage`)
- Sync-specific persisted state: `FastSyncNodeStorage`/`FastSyncStateStorage`,
  `SnapSyncProgressStorage`, `HealingFrontierStorage`, `BfsQueueStorage`, `FlatAccountStorage`/
  `FlatSlotStorage`
- App/node bookkeeping: `AppStateStorage`, `KnownNodesStorage`, `TransactionMappingStorage`,
  `BlockFirstSeenStorage`/`BlockFirstSeenRocksDbStorage`, `RecoveryProgress`,
  `BlockNumberMappingStorage`

## Cross-references

- Owning specialist: `vault` (`.claude/agents/vault.md`)
- Full contract/rules: `.claude/agent-protocols/storage-rocksdb.md`
