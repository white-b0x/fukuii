# go-ethereum — storage-persistence

_Commit/branch documented: `59e89e81e57814a96c429c5cdcaa6ca2e0d6b143` (tag `v1.17.4-32-g59e89e81e`, branch
`upstream`). Vendored at `/media/dev/2tb/dev/reference-clients-evm/go-ethereum` (identical copy at
`.claude/repo-references/clients/go-ethereum`, same SHA). Documented 2026-07-13._

## Architecture summary
go-ethereum layers storage in three tiers, each a clean interface package so the tiers compose without
knowing each other's concrete types:

1. **KV abstraction (`ethdb/`)** — a narrow `KeyValueStore` interface (Has/Get/Put/Delete/DeleteRange/
   iterate/batch/compact) with two production backends: **LevelDB** (goleveldb) and **Pebble** (geth's own
   Go LSM, the default since v1.14), plus an in-memory `memorydb` for tests and a `remotedb` for `geth
   attach`-style read access. There are **no column families** — namespacing is done entirely with key
   prefixes.
2. **Ancient store / freezer (`core/rawdb`, freezer\*)** — an append-only flat-file store for immutable,
   number-indexed historical data (old headers, bodies, receipts, canonical hashes). The `Database`
   interface is `KeyValueStore` + `AncientStore` glued together by `freezerdb`, so callers see one handle
   but recent mutable data lives in the LSM and finalized data is "frozen" out to compressed flat files.
3. **Schema + accessors (`core/rawdb`)** — the single source of truth for every key layout (`schema.go`)
   and typed read/write helpers (`accessors_*.go`) that are the only sanctioned way to touch raw keys.

State (the MPT) sits on top of the KV tier through **`triedb/`**, which offers two mutually-exclusive
node-storage schemes: legacy **hashdb** (hash-keyed nodes, reference-counted GC) and the newer
default **pathdb** (path-keyed nodes with a layered diff tree, bounded on-disk state, and state-history
freezers for rollback).

## Key types / interfaces / files

### KV abstraction (`ethdb/`)
- `ethdb/database.go:99` — `KeyValueStore`: the composed backend contract (Reader + Writer +
  RangeDeleter + Stater + Syncer + Batcher + Iteratee + Compacter + `io.Closer`).
- `ethdb/database.go:39-54` — `KeyValueReader` (`Has`/`Get`) and `KeyValueWriter` (`Put`/`Delete`) — the
  minimal read/write seam almost every helper is written against.
- `ethdb/database.go:59-70` — `KeyValueRangeDeleter.DeleteRange(start,end)` — a comparatively new
  capability (may return `ErrTooManyKeys` after a partial delete); used for cheap pruning of prefix ranges.
- `ethdb/database.go:224-227` — `Database` = `KeyValueStore` + `AncientStore`; the top-level handle the
  whole node is built on.
- `ethdb/batch.go:25-43` — `Batch`: a write-only, non-concurrent buffer flushed atomically by `Write()`;
  `IdealBatchSize = 100 KiB` (`ethdb/batch.go:21`) is the conventional flush threshold callers watch.
- `ethdb/iterator.go:28-50` — `Iterator`: forward-only, ascending-key, **not concurrency-safe**, and
  **must be `Release()`d** (see Iterator lifecycle below).
- `ethdb/pebble/pebble.go:181` — `New(...)`: the Pebble backend constructor (LSM tuning: memtable count,
  block cache, WAL sync bytes).
- `ethdb/leveldb/leveldb.go` — the goleveldb backend (legacy default; still supported).

### Ancient store / freezer (`core/rawdb`)
- `ethdb/database.go:111-175` — `AncientReader`/`AncientWriter`/`AncientWriteOp`: read by (kind, number),
  write via `ModifyAncients` (reverts on error), truncate head (reorg) / tail (prune).
- `core/rawdb/freezer.go:55-74` — `Freezer`: append-only flat-file store; per-table files capped at
  `freezerTableSize = 2 GB` (`freezer.go:53`); a filesystem `flock` prevents double-open.
- `core/rawdb/ancient_scheme.go:26-67` — the chain-freezer table set (`headers`, `hashes`, `bodies`,
  `receipts`, `bals`) and their per-table config (Snappy on/off, tail group).
- `core/rawdb/ancient_scheme.go:44-55` — **tail groups**: `bodies`+`receipts` share one group so they
  prune together; `headers`/`hashes` are non-prunable (retained long-term); BAL prunes independently.
- `core/rawdb/freezer_table.go`, `freezer_batch.go` — per-table append/read; `freezer_memory.go` — an
  in-memory freezer for dev mode; `freezer_resettable.go` — the resettable wrapper used by state freezers.

### Schema & accessors (`core/rawdb`)
- `core/rawdb/schema.go:30-171` — every key prefix in the DB, defined once. Single-byte data prefixes
  (`h` header, `b` body, `r` receipts, `H` hash→number, `c` code, `a`/`o` snapshot account/storage,
  `A`/`O` path-scheme trie nodes) plus multi-byte config/metadata keys.
- `core/rawdb/schema.go:107-127` — **why single-byte prefixes** ("avoid mixing data types") and the split
  between hash-scheme (`SnapshotAccountPrefix`) and **path-scheme** trie-node keys (`TrieNodeAccountPrefix
  = "A" + hexPath`, `TrieNodeStoragePrefix = "O" + accountHash + hexPath`).
- `core/rawdb/schema.go:264-359` — key **classifier** helpers (`IsCodeKey`, `ResolveAccountTrieNodeKey`,
  `IsStorageTrieNode`, `IsLegacyTrieNode`) that let the inspector/pruner tell node kinds apart by key shape
  alone.
- `core/rawdb/accessors_chain.go`, `accessors_state.go`, `accessors_trie.go`, `accessors_snapshot.go` —
  typed getters/setters; callers never hand-assemble keys.

### State persistence — triedb (`triedb/`)
- `triedb/database.go:89-121` — `Database`: entrypoint wrapping one `backend` (hashdb **or** pathdb;
  configuring both is `log.Crit`, `database.go:112-114`) plus an optional preimage store.
- `triedb/database.go:199-205` — `Scheme()` returns `rawdb.PathScheme` or `rawdb.HashScheme`.
- `triedb/hashdb/database.go` — hash-keyed nodes, in-memory dirty set + `fastcache` clean cache,
  reference counting (`Reference`/`Dereference`, `triedb/database.go:259-282`) and `Cap` for GC.
- `triedb/pathdb/database.go:38-87` — the `layer` interface (disk layer + stacked diff layers); state is
  a diff tree over a bounded disk layer.
- `triedb/pathdb/disklayer.go:33-55` — `diskLayer`: clean-node + clean-state `fastcache` caches, a live
  write `buffer` and a `frozen` buffer awaiting flush, and a `stale` flag guarding progressed state.
- `core/rawdb/accessors_trie.go:37,45` — `HashScheme = "hash"`, `PathScheme = "path"`; scheme selection at
  `accessors_trie.go:222-289` (`ReadStateScheme`/`ParseStateScheme`).

## Design decisions & rationale

- **Interface-first, backend-agnostic KV.** Everything above `ethdb` is written to `KeyValueStore`, never
  to LevelDB or Pebble directly. This let geth swap the *default* backend from goleveldb to its own Pebble
  fork (better write throughput, fewer compaction stalls, actively maintained) without touching a single
  accessor. `remotedb` and `memorydb` satisfy the same interface, so the inspector, tests, and `geth
  attach` reuse all the accessor code.
- **Key prefixes instead of column families.** goleveldb/Pebble are single-keyspace LSMs, so geth encodes
  "table" identity in the first byte(s) of the key (`schema.go:107-171`). Prefixes are deliberately
  single-byte where possible to keep keys small and iteration ranges tight. This is the structural
  contrast with erigon (real MDBX sub-DBs/tables) and with Besu/Nethermind (RocksDB column families).
- **Freezer for immutable data.** Finalized chain segments never change, so paying LSM
  write-amplification and compaction to store them is wasteful. The freezer moves them to append-only,
  Snappy-compressed flat files (`ancient_scheme.go:61-67`) indexed by block number — sequential on disk,
  cheap to read in ranges (`AncientRange`), and it shrinks the hot LSM so compaction stays fast. Hashes
  skip Snappy (they don't compress) and headers/hashes are never tail-pruned.
- **Tail groups for coordinated pruning.** Tables that must prune in lockstep (bodies + receipts) share a
  named tail group so their tails always agree (`ancient_scheme.go:44-55`); non-grouped tables are
  non-prunable. This encodes the pruning invariant in the table config rather than in scattered call
  sites.
- **Two state schemes, one default flip.** hashdb keys nodes by hash and reference-counts them (mature,
  but unbounded on-disk state and heavy GC). **pathdb keys nodes by trie path**, keeps a **bounded** disk
  layer with a stack of in-memory diff layers, and writes **state history** to dedicated freezers so it
  can `Recover`/rollback to a recent historical root (`triedb/database.go:284-305`). Since this snapshot,
  geth's default for an **empty** datadir is **path** (`accessors_trie.go:275`, "State schema set to
  default: path"), while an existing datadir keeps whatever scheme it was created with
  (`ReadStateScheme`, `accessors_trie.go:224-255`).
- **Layered writes deferred to disk.** pathdb aggregates dirty nodes in a live `buffer`, moves it to a
  `frozen` buffer, and flushes asynchronously (`disklayer.go:45-46`) — batching many block updates into
  few LSM writes and keeping recent state reorg-friendly (diff layers, not flattened).

## Notable patterns (the reusable ideas)

- **`Release()`-mandated iterator contract** (see Iterator lifecycle) — the interface itself, not just
  convention, requires explicit release; every backend makes `Release()` idempotent.
- **Freezer = append-only flat-file cold store fronting a mutable LSM hot store**, unified behind one
  `Database` interface. Worth naming as the canonical "hot KV + cold ancient" split.
- **Bounded-disk, layered state (pathdb)** with state-history freezers enabling rollback — the design that
  makes geth's disk footprint predictable and that erigon/reth approach differently (flat domains).
- **Schema-as-single-file** (`schema.go`): every key layout and every key-classifier in one place, so the
  DB inspector and pruners can reason about arbitrary keys structurally.
- **Tail groups**: encode "these tables prune together" declaratively in table config.

## Iterator lifecycle (called out — directly relevant to a fukuii fix)

geth treats iterator release as a **hard contract, not a convention**:

- **Interface-level mandate.** `ethdb/iterator.go:19-50` documents it explicitly: *"An iterator must be
  released after use... Calling `Release` is still necessary [even on error]."* `Iterator` is
  forward-only (`Next() bool`), deferred-error (`Error()` queried after iteration, not per-step), and
  **not safe for concurrent use** — but multiple independent iterators may run concurrently.
- **`defer it.Release()` is the universal idiom.** Every internal call site pairs creation with an
  immediate deferred release, e.g. `ethdb/leveldb/leveldb.go:220-221` (`it := db.NewIterator(...); defer
  it.Release()`) and the range-delete fallback at `leveldb.go:480-481`. There is no reliance on GC/
  finalizers to reclaim iterators.
- **Idempotent, guarded `Release()`.** The Pebble wrapper stores a `released bool` and no-ops on repeat
  calls (`ethdb/pebble/pebble.go:812-819`): `if !iter.released { iter.iter.Close(); iter.released = true }`.
  This makes "release twice" (e.g. explicit release + deferred release) safe, which is exactly the failure
  mode a strict close-once resource is prone to.
- **`NewIterator` seeds position eagerly.** Pebble's `NewIterator` calls `iter.First()` immediately and
  tracks a `moved` flag so the first `Next()` returns the already-positioned element instead of skipping it
  (`pebble.go:773-789`) — the prefix is applied via `IterOptions`, and (per `Iteratee` doc,
  `iterator.go:52-60`) the caller must **not** prepend the prefix to `start`.
- **Close vs. open iterators.** `Database.Close()` (`pebble.go:377-393`) takes a write lock, sets `closed`,
  stops the metrics goroutine, and closes the underlying store; it does **not** itself join outstanding
  iterators — the surrounding code is responsible for having released them (Pebble will error/panic if an
  iterator outlives its DB). A `liveIterGauge` (`pebble.go:88,368`) exposes the live-iterator count as a
  metric, i.e. iterator leaks are observable in production dashboards, not just latent bugs.

**The reusable lesson for fukuii:** put the release obligation *in the type/interface contract*, make the
release **idempotent** (guard flag), and make the live-iterator count an exported metric so a leak surfaces
operationally. geth's `released bool` guard is the specific shape that neutralizes double-release, and the
`defer Release()`-at-creation idiom is what neutralizes leak-on-early-return.

## Authority note

**go-ethereum is a strong authority for the storage-persistence concern** — a mature, battle-tested LSM KV
abstraction (LevelDB → its own Pebble fork), the freezer/ancient design, and the path-based state scheme
are all geth-originated and widely copied. It is the canonical reference for the *ETH/PoS* baseline, and its
KV/freezer/scheme model is the natural comparison target for any Merkle-Patricia, hash-or-path node store.

Caveats on authority scope:
- For **ETC-specific** persistence semantics (nothing structural here — ETC and ETH share the storage
  model), **core-geth** is the ETC authority, but it inherits geth's `ethdb`/`rawdb`/`triedb` largely
  unchanged, so geth remains the design reference.
- **erigon is the alternative authority** for an intentionally *different* storage architecture (see
  divergence below) — flat-state, staged sync, MDBX. Where the question is "smallest disk footprint / flat
  state / staged pipeline," erigon leads, not geth.

## Where erigon diverges (alternative authority)

Documented against the vendored erigon at `.claude/repo-references/clients/erigon` (`f1d79d699e`):

- **MDBX, not an LSM.** erigon backs everything with a single memory-mapped **MDBX** B+tree
  (`db/kv/mdbx/kv_mdbx.go`) rather than LevelDB/Pebble LSMs. MDBX is a single-writer/many-reader,
  copy-on-write B+tree — no compaction, cheaper random reads, ACID via mmap — vs. geth's LSM
  (write-optimized, background compaction).
- **Real tables + DupSort, not key prefixes.** erigon uses MDBX named sub-databases (`db/kv/tables.go`,
  hundreds of table constants) and `DupSort` tables (e.g. `PlainState`, `tables.go:531`) instead of
  geth's single-keyspace-with-byte-prefixes. This is closer to Besu/Nethermind's RocksDB column families.
- **Flat state ("E3" Domains), not an MPT node store.** erigon's execution path stores state in
  incarnation-free **Domains** — `AccountsDomain`/`StorageDomain`/`CodeDomain`/`CommitmentDomain`
  (`db/kv/tables.go:711-715`) — i.e. flat account/storage records with the Merkle commitment as a separate
  domain, rather than geth's path/hash-keyed trie-node blobs. Legacy `PlainState`/`HashedStorage` tables
  are being retired (`tables.go:481-488`). This is a fundamentally different state model: geth persists
  trie nodes; erigon persists flat state and derives commitments.
- **Staged sync** (erigon's defining pipeline) drives writes stage-by-stage over MDBX, which pairs with the
  flat model — orthogonal to geth's freezer-plus-triedb approach.

The takeaway for a comparison table: **geth = LSM + prefix keys + freezer + MPT node store (hash/path);
erigon = MDBX + real tables + flat Domains + staged sync.** Besu/Nethermind land between them (RocksDB with
column families). fukuii (RocksDB via `DataSource`, per `db/AGENTS.md`) is architecturally closer to the
Besu/Nethermind column-family side than to geth's single-keyspace-prefix side — noted here only as a
forward-reference for Phase 2/3, not a verdict.

## Gotchas / anti-patterns / things they later changed

- **Iterators leak silently if not released** — the whole reason release is an interface mandate with an
  idempotent guard and a live-count metric (above). An un-`Release()`d iterator pins LSM resources; this is
  the class of bug fukuii just fixed on its side.
- **`DeleteRange` can partially complete.** It may return `ErrTooManyKeys` after deleting *some* entries
  (`ethdb/database.go:56,67-69`) — callers must treat range-delete as resumable, not atomic.
- **hashdb clean cache must be closed or it leaks.** `hashdb.Defaults` sets `CleanCacheSize: 0` *on
  purpose* because a non-zero fastcache "database must be closed when it's no longer needed to prevent
  memory leak" (`triedb/hashdb/database.go:68-75`).
- **Configuring both schemes is fatal.** Setting `HashDB` and `PathDB` together is `log.Crit`
  (`triedb/database.go:112-114`) — scheme is one-or-the-other, chosen once per datadir.
- **Scheme is sticky per datadir.** An existing datadir keeps its original scheme; only an empty datadir
  gets the current default (path). Forcing the wrong `--state.scheme` errors out
  (`ParseStateScheme`, `accessors_trie.go:268-289`) rather than silently converting — conversion requires
  an explicit offline migration.
- **Default backend flipped to Pebble.** goleveldb is legacy; Pebble is the default and the one under
  active tuning (`ethdb/pebble/pebble.go` carries the LSM knobs). New work should read Pebble, not
  LevelDB, as geth's intended path.
- **Standalone snapshot layer subsumed by pathdb.** Historical hash-scheme "snapshot" (`a`/`o` prefixes,
  `SnapshotAccountPrefix`) coexists with pathdb's own flat-state generation; the long-run direction is
  path-based flat state, with the legacy snapshot retained for hashdb compatibility.
