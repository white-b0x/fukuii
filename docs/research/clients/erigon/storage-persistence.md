# erigon — storage-persistence

_Commit/branch documented: `f1d79d699ed4b809abc0d177dcb539d8605edc41` (branch
`upstream`/`origin/upstream`, 2026-07-01). Vendored at
`.claude/repo-references/clients/erigon`. Documented 2026-07-13._

## Architecture summary

Erigon's storage is a two-tier **temporal database**: a **hot MDBX** key-value store
(`db/kv/mdbx/`) for recent/mutable data, sitting under a **`TemporalDB`**
(`db/kv/temporal/kv_temporal.go`) that also owns an **`Aggregator`** (`db/state/aggregator.go`)
managing immutable, memory-mapped **snapshot files** (`.seg`/`.kv`/`.idx`/`.bt`) for
everything old enough to be "frozen." State itself is not stored as a persistent Merkle-Patricia
trie of hash-addressed nodes at all (the "E3"/flat-state architecture): each conceptual piece of
state — Accounts, Storage, Code, Commitment (branch nodes) — is a **`Domain`**
(`db/state/domain.go`), a flat, plain-keyed `key -> latest value` mapping with an attached
**`History`** (per-key value-at-txNum change log) and **`InvertedIndex`** (key -> [txNums it
changed at], for range/time-travel queries). The state root is **recomputed on demand** by a
Patricia-trie algorithm (`execution/commitment.HexPatriciaHashed`) that reads account/storage
leaves directly from the flat Accounts/Storage domains and reads/writes only the trie's internal
**branch nodes** through a fourth domain (`CommitmentDomain`) — so a full hash-keyed trie-node
store for leaves never exists on disk, only a lazily-maintained branch-node cache. MDBX itself
uses real named **tables** (LMDB/MDBX "DBI"s) with an explicit **DupSort** flag for
sorted-duplicate-value tables, rather than RocksDB-style column families or key prefixes.

## Key types / interfaces / files

### MDBX engine (`db/kv/mdbx/`, `db/kv/kv_interface.go`)

- `db/kv/kv_interface.go:18-92` — the header comment block is itself the best primary source:
  naming conventions (`tx`/`txn`, `RoTx`/`RwTx`, `step`, `Table`, `DupSort`, `Cursor`, `Stream`)
  and the three-layer abstraction model (LowLevel: DB/Tx + Snapshots; MediumLevel: TemporalDB +
  InvertedIndex/History/Domain; HighLevel: Application). Read this comment before anything else
  in the package — it is the closest thing erigon has to an architecture doc for this subsystem.
- `db/kv/kv_interface.go:126-163` — `RoDB`/`RwDB`: `BeginRo`/`BeginRw` (long-lived transaction
  handles) and `View`/`Update` (function-scoped, auto-rollback/commit). Both expose `CHandle()
  unsafe.Pointer` — a raw pointer to the underlying `*C.MDBX_env`/`*C.MDBX_txn`, an explicit escape
  hatch into the C API that RocksDB's Java/JNI-wrapped `DataSource` model has no equivalent for.
- `db/kv/kv_interface.go:165-213` — `Tx`/`RwTx`: `Cursor(table)`/`CursorDupSort(table)` (low-level,
  MDBX-native navigation) plus high-level `Range`/`Prefix`/`RangeDupSort` returning a
  `stream.KV` — erigon's server-side-streaming-friendly iterator abstraction (distinct from the
  raw `Cursor`).
- `db/kv/kv_interface.go:216-321` — `Cursor`/`RwCursor`/`CursorDupSort`/`RwCursorDupSort`: the
  full MDBX cursor vocabulary, including **DupSort-specific navigation**
  (`SeekBothExact`/`SeekBothRange`/`FirstDup`/`NextDup`/`NextNoDup`/`PrevDup`/`PrevNoDup`/
  `LastDup`/`CountDuplicates`) — operations with no RocksDB analogue, since DupSort tables let one
  key own many sorted values natively in the B+tree rather than requiring a synthetic
  `key+suffix` composite key.
- `db/kv/kv_interface.go:315-333` — `RwCursorPseudoDupSort`: a compatibility shim wrapping a plain
  `RwCursor` to satisfy the `PseudoDupSortRwCursor` interface for non-DupSort tables (each key has
  exactly one "duplicate" — itself) — lets pruning/history code written against the DupSort
  vocabulary run against ordinary tables without a second code path.
- `db/kv/mdbx/kv_mdbx.go:97-122` — `New(label, log) MdbxOpts`: per-DB defaults keyed by
  `kv.Label` (chaindata vs. auxiliary DBs get different flag sets); `DefaultMapSize = 2 TB`,
  `DefaultGrowthStep = 1 GB` (`kv_mdbx.go:94-95`) — MDBX pre-reserves a huge virtual address-space
  map and grows the backing file in 1 GB increments, the mmap-based analogue of RocksDB's SST-file
  growth.
- `db/kv/mdbx/kv_mdbx.go:188-330` (`Open`) — env tuning: `OptMaxDB=200` (max named tables),
  `OptMaxReaders = kv.ReadersLimit` (`= 32000`, `kv/kv_interface.go:647`, just under MDBX's own
  hard cap of 32767 reader slots), `OptRpAugmentLimit`, `SetGeometry` (map-size/growth-step/
  page-size), and a warning comment (`kv_mdbx.go:86-88`) that **without a Go-side semaphore
  limiting concurrent read transactions, 10K blocked goroutines waiting on MDBX reader-slot I/O
  can crash the Go runtime** — the direct justification for `roTxsLimiter` below.
- `db/kv/mdbx/kv_mdbx.go:680-722` (`BeginRo`) — read transactions are gated by a
  **`*semaphore.Weighted` (`roTxsLimiter`)**, defaulting to `9_000` concurrent slots
  (`kv_mdbx.go:360-365`, "1 less than max to allow unlocking to happen"; field doc comment at
  `kv_mdbx.go:454` recommends `runtime.NumCPU()` as a tighter value for callers that want to share
  the limiter across other IO-bound components like `Decompressor`), acquired before
  `env.BeginTxn` and released in both the error path and `Commit`/`Rollback`. Supports a
  non-blocking `TryAcquire` variant (`kv.IsNonBlockingAcquire(ctx)`) that fails fast with
  `kv.ErrReadTxLimitExceeded` instead of queueing — used by callers that would rather reject than
  stall.
- `db/kv/mdbx/kv_mdbx.go:745-763` (`beginRw`) — write transactions call
  **`runtime.LockOSThread()`** before `env.BeginTxn` and `runtime.UnlockOSThread()` only on
  `Commit`/`Rollback` (`kv_mdbx.go:1132`, `1199`) — MDBX's single-writer model is enforced at the
  OS-thread level in Go, not just at the transaction-object level; a goroutine holding an RwTx is
  pinned to its OS thread for the transaction's lifetime.
- `db/kv/mdbx/kv_mdbx.go:1121-1221` — `Commit`/`Rollback`/`closeCursors`: **the transaction owns
  and closes every cursor it created.** `MdbxTx.toCloseMap map[uint64]kv.Closer` accumulates every
  `Cursor()`/`RwCursor()` call; `Commit`/`Rollback` both call `closeCursors()` first, which
  iterates and closes every entry, then nils the map. A caller that forgets to `.Close()` an
  individual cursor does **not** leak it — the enclosing transaction's own lifecycle guarantees
  release. `statelessCursors` (`kv_mdbx.go:1223-1237`) is a per-table cache so repeated
  `Cursor(sameTable)` calls within one transaction reuse one native cursor instead of allocating a
  new one.
- `common/dbg/leak_detector.go` — `LeakDetector`: an **opt-in, stack-trace-capturing** long-lived-
  resource tracker. `Add()`/`Del()` bracket a transaction's lifetime (`kv_mdbx.go:723`, `765`,
  `1134`, `1201`); a background goroutine (`NewLeakDetector`, line 56-65) wakes every 60s and logs
  any resource alive longer than `slowThreshold`, with its creation stack trace. Disabled by
  default (`slowThreshold <= 0` short-circuits `Add`/`Del` to no-ops) — a debug/observability
  feature, not a correctness guard.

### Table/schema registry (`db/kv/tables.go`)

- `db/kv/tables.go:511-524` — `TableFlags` (`Default`/`ReverseKey`/`DupSort`/`IntegerKey`/
  `IntegerDup`/`ReverseDup`) and `TableCfgItem{Flags, IsDeprecated, DBI}` — the **erigon analogue
  of besu's `SegmentIdentifier` enum / RocksDB `ColumnFamilyDescriptor`**, but far thinner: no
  per-table cache sizing, no BlobDB eligibility, no static-data flag. MDBX tables are cheap (real
  B+trees sharing the same env-level page cache/mmap), so per-table tuning knobs that matter a lot
  for RocksDB's per-column-family LSM/block-cache model mostly don't apply here.
- `db/kv/tables.go:526-594` — `ChaindataTablesCfg`/`AuRaTablesCfg`/`BorTablesCfg`: table-name
  constants map to `TableCfgItem`s; most state-adjacent tables (`TblAccountVals`,
  `TblStorageVals`, `TblCommitmentVals`, history-key/value and inverted-index tables) declare
  `Flags: DupSort` so a single logical key can carry multiple time-ordered entries natively.
- `db/kv/tables.go:31-33` — `DBSchemaVersion` (a `Major.Minor.Patch` triple) with inline comments
  documenting what changed at each bump (e.g. "6.1 - Canonical/NonCanonical/BadBlock transitions
  now stored in same table") — erigon's coarse-grained version marker; see Gotchas for how this
  differs from besu's per-column-family versioned-format model.

### Temporal DB / Domains — the flat-state ("E3") model (`db/state/`, `db/kv/temporal/`)

- `db/agents.md` — erigon's own internal breadcrumb doc for this exact subsystem: hot MDBX tables
  → periodic snapshot freeze → compressed cold storage; `Unwind` beyond what's in snapshots is
  disallowed. Confirms the "four domains" model (Accounts/Storage/Code/Commitment) and the
  datadir layout: `chaindata/` (hot MDBX) vs. `snapshots/{domain,history,idx,accessor}/`.
- `db/state/domain.go:68-79` — `Domain` struct doc comment: "Domain is a part of the state
  (examples are Accounts, Storage, Code)… Domain should not have any go routines or locks." Embeds
  `*History` (so every Domain gets change-history for free) plus its own `.kv`/`.bt`/`.kvei` file
  triad (values, B-tree offset index, existence/bloom filter) for the cold/frozen portion.
- `db/state/domain.go:1636-1734` — `DomainRoTx.getLatestFromDb` / `getLatestFromFiles` /
  `GetLatest`: the read path is **hot-then-cold** — `getLatestFromDb` does an MDBX
  `SeekExact`/`Seek` against the live table first (keys are stored with an **inverted step-number
  suffix**, `^binary.BigEndian.Uint64`, so the most-recently-written value for a key sorts first
  under DupSort); only on a miss (or when the found step is older than what's already covered by
  frozen files) does it fall through to `getLatestFromFiles`, which resolves the value from the
  immutable `.kv` snapshot segments via their B-tree/existence-filter indices. No separate trie
  walk is needed for either path — both return the flat value directly.
- `db/state/domain.go:1525-1538` — `DomainRoTx.Close()`: idempotent (`dt.files == nil` guard),
  releases index-map readers and the reusable-getter cache back to the visible-file-set pool — the
  same "guarded idempotent close" shape RocksDB/besu iterators use, but scoped to a whole
  file-backed read-transaction rather than one cursor.
- `db/state/dirty_files.go:116-128` — `FilesItem{decompressor, index, bindex, existence,
  startTxNum, endTxNum, refcount atomic.Int32, canDelete atomic.Bool}` — every snapshot segment is
  **atomically reference-counted**; `Aggregator.acquireVisibleFiles()`
  (`db/state/aggregator.go:2333`) bumps refcounts when a read-transaction's file view is built
  (`BeginFilesRo`, `aggregator.go:2380-2401`), and the last releaser physically deletes a
  merged/garbage file from disk. This is the concrete mechanism behind the header-comment
  invariant in `kv_interface.go:64-66`: "existing readers can't see new files, new readers can't
  see garbage files."
- `execution/commitment/commitment.go:127-138` — `PatriciaContext` interface: `Account(plainKey)`
  and `Storage(plainKey)` fetch leaf values **directly from the flat Accounts/Storage domains by
  plain key** (no trie traversal to find a leaf); `Branch(prefix)`/`PutBranch(prefix, data,
  prevData)` read/write **only the trie's internal branch nodes**, persisted in the
  `CommitmentDomain` keyed by nibble prefix. This is the precise mechanism of "commitment computed
  on demand": leaves are never trie nodes on disk, but branch nodes are cached/persisted so a
  touched subtree's hash doesn't require rehashing every sibling from scratch on every block.
- `execution/commitment/hex_patricia_hashed.go:63-66` — `HexPatriciaHashed`: "implements
  commitment based on patricia merkle tree with radix 16, with keys pre-hashed by keccak256" — the
  actual state-root algorithm, driven by `PatriciaContext` reads/writes rather than owning its own
  node store.
- `db/state/domain_committed.go:36-83` — `replaceShortenedKeysInBranch`/
  `ExpandShortenedKeysInBranch`: for large enough commitment-branch ranges
  (`ValuesPlainKeyReferencingThresholdReached`), branch data stores **shortened references**
  (file offsets into the Account/Storage domain files) instead of full plain keys, expanded back
  out lazily on read — a space optimization specific to having leaves and branches in separate,
  independently-addressable flat/domain stores.
- `db/config3/config3.go:21-53` — step-size and retention constants: `DefaultStepSize = 390_625`
  txNums per "step" (the unit of aggregation/merge scheduling), `DefaultStepsInFrozenFile = 256`
  steps before a file range is considered fully frozen/immutable, `DefaultPruneDistance = 262_144`
  blocks (2^18, citing EIP-8252's `REORG_RETENTION_WINDOW`) for `full`/`blocks` prune modes vs.
  `MinimalPruneDistance = 100_000` for the `minimal` mode that deliberately opts out of that
  compliance target for less disk usage.
- `db/kv/prune/prune.go:53-60` — `StorageMode` enum (`DefaultStorageMode`, `KeyStorageMode`,
  `PrefixValStorageMode`, `StepValueStorageMode`, `StepKeyStorageMode`,
  `ValueOffset8StorageMode`) — pruning is table-shape-aware; `HashSeekingPrune` takes an explicit
  `mode` because a DupSort inverted-index table and a plain step-suffixed value table need
  different key/value deletion strategies.
- `db/state/forkables.md` — a second, distinct storage shape (`Forkable`) for
  simply-numbered/append-only structures (blocks, headers, checkpoints): **unmarked** forkables
  (`Num -> bytes`, no canonical-hash indirection, optimized index formats for gap-free vs.
  gappy primary keys) vs. **marked** forkables (two tables: `CanonicalHash: Num -> hash` +
  `Values: Num+hash -> value`, the shape blocks/headers need to survive reorgs before a range
  is frozen). A third abstraction layer alongside Domain/History/InvertedIndex, for data that
  doesn't need per-key history tracking, only per-number storage with optional canonicity.

### Explicit, per-file schema versioning (`db/version/`)

- `db/version/file_version.go:47-97` — `Version{Major, Minor, Patch}` embedded directly in
  **snapshot filenames** (`v1-...-accounts.0-64.kv` style), with `Less`/`Greater`/`Cmp`/`Eq` and
  `BumpMinor`/`BumpMajor`. `Versions{MinSupported, Current}.MustSupport(ver, filename)`
  (line 193-213) panics if an on-disk file's version falls outside the supported range — schema
  compatibility is checked **per file**, not once per datadir.

## Design decisions & rationale

- **MDBX over an LSM engine, for the hot tier only.** MDBX (a hardened LMDB fork) is a
  memory-mapped, copy-on-write B+tree: single-writer/multi-reader MVCC with zero write-ahead log
  and no background compaction. Erigon deliberately keeps MDBX's hot tier small (recent,
  reorg-able data only) — the design explicitly avoids relying on MDBX to scale to "all of
  Ethereum history," instead offloading anything old enough to be immutable into flat,
  memory-mapped `.seg`/`.kv` snapshot files that are never touched by MDBX at all.
- **Real DupSort tables instead of key-prefix encoding.** Where RocksDB (and fukuii) encode
  "multiple values per logical key" by concatenating a suffix onto the key and relying on prefix
  iteration, MDBX's DupSort flag makes this native to the B+tree: one key genuinely owns a sorted
  set of values, with dedicated cursor operations (`NextDup`/`SeekBothRange`/etc.) that don't need
  prefix-matching logic in application code. State-value tables still use an inverted
  step-suffix encoding within the DupSort value (`getLatestFromDb`, above), so hot-tier state
  lookups get an explicit `DupSort`-native "find the newest value for this key" query instead of
  a full prefix scan.
- **Flat state, not a persisted trie-node store, for the leaves.** This is the deepest structural
  divergence from besu/geth/fukuii: Accounts/Storage/Code are plain `key -> value` Domains, full
  stop — there is no on-disk representation of "trie leaf node." The trie only exists
  conceptually, reconstructed by `HexPatriciaHashed` on demand from flat reads. Branch nodes
  **are** persisted (via `CommitmentDomain`), which keeps repeated root-hash computation
  incremental rather than a full walk from genesis, but this is a cache of intermediate hashing
  state, not a leaf-addressable node store the way ArchiveNodeStorage/ReferenceCountNodeStorage
  are in fukuii or ForestWorldStateKeyValueStorage is in besu.
- **Temporal separation of "latest" from "history."** Every Domain pairs a flat latest-value store
  with a `History` (value-at-txNum log) and an `InvertedIndex` (key -> txNums it changed at). This
  lets `GetAsOf(txNum)`/`HistorySeek` answer "what was this value at block N" without walking a
  historical trie or replaying blocks — the InvertedIndex turns "did key K change between txA and
  txB" into a direct range query.
- **A bounded hot tier is a correctness requirement, not just a performance tuning choice.** geth
  uses timestamp-based recency as its DB-vs-freezer boundary as a purely operational decision;
  MDBX's own single-writer, copy-on-write-B+tree design makes "small, bounded hot tier" closer to
  a hard requirement — an unbounded MDBX map eventually pays copy-on-write costs that scale with
  map size, giving erigon extra pressure (beyond just disk usage) to keep pushing data into the
  immutable snapshot tier promptly.
- **Refcounted, generation-based file visibility instead of RocksDB-style snapshot isolation for
  cold data.** Because `.seg`/`.kv` files are immutable once written, erigon doesn't need MVCC
  machinery over them — it needs only "don't delete a file a reader still holds." Atomic
  `refcount` + a `canDelete` flag give that with plain integer ops, no lock contention on the hot
  read path.
- **Per-file schema version, not per-datadir.** Embedding `Major.Minor.Patch` in every snapshot
  filename means different pieces of history can, in principle, be produced/read at different
  schema versions within the same datadir — appropriate for a system whose cold data is
  file-based and merge-produced over time, as opposed to besu's single `DATABASE_METADATA.json`
  which versions the whole datadir's RocksDB column-family layout at once.

## Notable patterns (the reusable ideas)

- **Transaction-owns-its-cursors (`toCloseMap`).** `MdbxTx` tracks every cursor it opened and
  closes them all in `Commit`/`Rollback`, before the underlying `mdbx.Txn` itself is finalized.
  This makes "forgot to close a cursor" a non-issue for correctness (though still a resource-churn
  smell) — a stronger default than either RocksDB's caller-must-`.close()`-the-iterator contract
  or besu's `Stream.onClose`-composition pattern (see besu's `storage-persistence.md` Gotchas).
- **Explicit reader-transaction admission control (`roTxsLimiter`), justified by a documented Go
  runtime failure mode.** The semaphore isn't just a performance knob — the inline comment
  (`kv_mdbx.go:86-88`) cites a real `golang-dev` thread about 10K blocked goroutines crashing the
  runtime. A concrete, cited justification for bounding concurrent read transactions, worth
  treating as a general principle: any client wrapping a native blocking C call in Go needs an
  explicit concurrency cap, not just "let the runtime scheduler handle it."
- **OS-thread pinning for write transactions.** `runtime.LockOSThread()`/`UnlockOSThread()`
  bracketing an RwTx's lifetime is MDBX's single-writer invariant enforced at the goroutine
  scheduler level, not just via a mutex around `BeginRw`. Directly relevant to any JVM/Scala
  client comparison: the JVM has no equivalent "pin this thread" primitive exposed to application
  code the way Go's runtime does, so a hypothetical MDBX binding for a JVM client would need a
  different mechanism (e.g. a dedicated single-thread executor) to get the same guarantee.
- **Refcounted immutable-file generations as the MVCC substitute for cold data.** Instead of
  RocksDB snapshots or MVCC transactions over historical data, erigon relies on "files are
  immutable once frozen, so hold a reference until you're done" — a much cheaper mechanism when
  the underlying data genuinely never mutates in place.
- **Per-file schema version embedded in the filename**, checked against a `[MinSupported,
  Current]` range at open time (`Versions.MustSupport`) — a finer-grained sibling to besu's
  datadir-wide versioned-format pattern (both worth comparing against in Phase 2 observations).
- **`PseudoDupSortRwCursor`**: a thin adapter so pruning/history code written once against the
  DupSort cursor vocabulary can run unmodified against non-DupSort tables — avoids a
  parallel non-DupSort code path purely because MDBX's cursor interface happens to have two
  shapes.

## Authority note

**erigon is the authority for the flat-state/MDBX alternative** — the project-wide authority
model (`docs/research/clients/README.md`) names erigon specifically for "performance/DB (MDBX)."
This is the most radical divergence from fukuii's own model of any reference client documented so
far: fukuii is RocksDB (LSM, column families, WAL) with a **hash-keyed trie-node store**
(`db/storage/{ArchiveNodeStorage,ReferenceCountNodeStorage,...}` per `db/AGENTS.md`) plus a
partial flat-storage overlay (`FlatAccountStorage`/`FlatSlotStorage`) used as a read-path
optimization *alongside* the trie, not a replacement for it. erigon has no leaf trie-node store at
all — flat state is the *only* representation of account/storage data, and the trie is rebuilt
on demand from it. This is architecturally further from fukuii than besu's Bonsai format (flat
state **plus** a retained thin trie for proofs) — erigon retains no leaf-level trie data
whatsoever, only branch-node commitment metadata.

Scope caveats:
- erigon is **not** an authority for ETC-specific persistence semantics (core-geth remains that
  authority per the project-wide model) — nothing documented here is ETC-specific.
- erigon is not the authority for "how to tune a JVM+RocksDB client" (besu is, per its own doc) —
  the two are complementary references for fundamentally different storage-engine choices, not
  competing guidance on the same engine.
- If fukuii ever seriously considered a flat-storage-only migration (going further than the
  existing `FlatAccountStorage`/`FlatSlotStorage` overlay), erigon's `Domain`/`History`/
  `InvertedIndex`/`CommitmentDomain` split is the concrete reference architecture to study next —
  but note the JVM has no MDBX binding of erigon's maturity, and RocksDB's LSM model doesn't map
  cleanly onto MDBX's copy-on-write B+tree assumptions (no compaction, no WAL, different write-
  amplification profile) — this would be a genuine re-architecture, not a drop-in swap.

## Gotchas / anti-patterns / things they later changed

- **DupSort tables preserved for E2-era compatibility even though marked deprecated.**
  `db/kv/tables.go:526-534` keeps `PlainState`/`HashedStorageDeprecated`/
  `AccountChangeSetDeprecated`/`StorageChangeSetDeprecated` with `Flags: DupSort` explicitly so an
  existing (E2-era) database can still be opened with the right bucket flags for the
  `drop_legacy_e2_tables` migration to run — the exact same "never renumber/remove a retired
  segment because the flag is a wire-format commitment" discipline besu applies to
  `PRIVATE_TRANSACTIONS`/`GOQUORUM_PRIVATE_STORAGE`.
- **`LeakDetector` is opt-in and off by default** (`dbg.SlowTx()` env-gated threshold) — unlike
  fukuii's structural fix for the RocksDB iterator-leak class of bug (batched `unboundedScan`
  opening/draining/closing a native iterator per self-contained batch, per `RocksDbDataSource
  .scala`'s `#1355` fix), erigon's defense here is observability (log slow/leaked transactions
  after the fact with a stack trace), not a structural guarantee that a leak can't happen. The
  actual structural guarantee for MDBX cursors is the transaction-owns-its-cursors pattern above;
  the leak detector's job is transactions that themselves live too long, which no automatic
  mechanism can prevent.
- **`Unwind` beyond what's retained in snapshot files is not allowed** (`db/agents.md:11`,
  `CLAUDE.md`'s "Architecture Overview") — a hard invariant, not a soft preference: once data has
  been frozen into an immutable `.seg` file and the corresponding hot-tier rows pruned, a reorg
  deeper than the retained window cannot be serviced from local storage at all. This is a direct
  consequence of the flat-state design (no historical trie nodes to replay from) and makes prune
  distance a correctness-relevant parameter, not just a disk-usage one — see `DefaultPruneDistance`
  citing EIP-8252's `REORG_RETENTION_WINDOW` above.
- **`RwCursorPseudoDupSort`/`PseudoDupSortRwCursor` are named and doc-commented as adapters, not
  the primary API** (`kv_interface.go:315-333`: "wraps any RwCursor to satisfy PseudoDupSortRwCursor
  for non-DupSort tables"). Reaching for them signals code written for the DupSort vocabulary
  being force-fit onto plain tables; a design smell worth recognizing rather than imitating if
  starting fresh.
- **Two distinct storage-shape systems coexist by design** (`Domain`/`History`/`InvertedIndex` for
  state, `Forkable` for numbered append-only entities like blocks/headers) rather than one unified
  abstraction — `db/state/forkables.md` documents this as a deliberate split (blocks don't need
  per-key value-history tracking the way accounts do), not an accidental duplication, but it does
  mean two mental models to hold when reading erigon's storage code.
