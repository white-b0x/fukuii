# go-ethereum — historical-distribution
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary

geth splits chain persistence into two stores with different lifecycles, and layers a
portable file format and history-pruning policy on top:

1. **Hot KV store** (LevelDB / Pebble) — recent, mutable, random-access chain and state
   data. Reorg-able head lives here.
2. **Cold freezer / "ancient store"** — an append-only, immutable, flat-file store for
   finalized blocks (`core/rawdb/freezer.go`). A background thread migrates old blocks out
   of the hot KV into the freezer once they are deep enough to be considered immutable.
3. **era1 files** — a self-describing, verifiable, per-8192-block file format
   (`internal/era/`) that geth *invented* for distributing history out-of-band (HTTP
   download, archival) instead of over P2P sync. `geth export-history` / `import-history`
   produce/consume them; `eradl` downloads them.
4. **History expiry (EIP-4444)** — `geth prune-history` + `--history.chain` drop pre-merge
   block bodies/receipts by truncating the freezer *tail*, with era1 files serving as the
   durable backup for the pruned range (`core/rawdb/chain_freezer.go` `eradb`).

The freezer is the storage-lifecycle backbone; era1 is the distribution mechanism; EIP-4444
is the custody policy that ties them together.

## Key types / interfaces / files

### Freezer / ancient store
- `core/rawdb/freezer.go:60` — `Freezer` struct: the append-only immutable store. Holds a
  map of named `freezerTable`s, a filesystem `FLOCK` lock (distinct from LevelDB's `LOCK`,
  `freezer.go:100`), and a `head` atomic item counter.
- `core/rawdb/freezer.go:53` — `freezerTableSize = 2 * 1000 * 1000 * 1000` — data files roll
  over at ~2 GB.
- `core/rawdb/freezer.go:186-218` — `Ancient` / `AncientRange` / `Ancients` — the read API,
  strictly by monotonic item index (block number), not by hash.
- `core/rawdb/freezer.go:258-289` — `ModifyAncients`: the *only* write path. Appends a whole
  batch atomically and rolls every table back to the previous head on error
  (`freezer.go:267-277`) — append-only + all-or-nothing.
- `core/rawdb/freezer.go:291-350` — `TruncateHead` (reorg / unwind) and `TruncateTail`
  (EIP-4444 history expiry) — the only two ways to remove data.
- `core/rawdb/freezer_table.go:54` — `indexEntry{filenum uint32 (stored uint16), offset
  uint32}`, `indexEntrySize = 6`. Each table = one snappy-compressed **data file** + one
  uncompressed **index file** of fixed 6-byte entries (`freezer_table.go:60-62`). Random
  access is O(1): seek `number * 6` in the index to get (file, offset).
- `core/rawdb/chain_freezer.go:46` — `chainFreezer`: wraps the raw `Freezer` and adds the
  background freezing thread plus an optional `eradb.Store` backup.
- `core/rawdb/chain_freezer.go:156-301` — `freeze()`: the background goroutine. Computes the
  freeze threshold, moves each batch into the freezer via `freezeRange`, fsyncs
  (`SyncAncient`), then **deletes** the frozen blocks from the hot KV, and prunes dangling
  side chains.
- `core/rawdb/chain_freezer.go:133-149` — `freezeThreshold = max(finalized, HEAD -
  params.FullImmutabilityThreshold)` — the hot/cold boundary. Only provably-immutable blocks
  get frozen.
- `core/rawdb/chain_freezer.go:36-40` — `freezerRecheckInterval = 1 min`,
  `freezerBatchLimit = 30000` blocks/batch.
- `core/rawdb/ancient_scheme.go:26-67` — the chain freezer table set: `headers`, `hashes`,
  `bodies`, `receipts`, `bals` (EIP-7928 block access lists), plus **tail groups** — bodies
  and receipts share the `blockdata` group and are pruned together; `headers`/`hashes` have
  no tail group and are *never* tail-pruned (`ancient_scheme.go:57-67`).
- `core/rawdb/ancient_scheme.go:98-137` — additional freezers for state history and trienode
  history (path-scheme), each its own on-disk folder (`chain`, `state`, `trienode`, verkle
  variants).

### era1 format
- `internal/era/era.go:33-46` — e2store TLV type constants (`TypeVersion`,
  `TypeCompressedHeader/Body/Receipts`, `TypeTotalDifficulty`, `TypeAccumulator`,
  `TypeBlockIndex`) and `MaxSize = 8192`.
- `internal/era/e2store/e2store.go:37-63` — the underlying **e2store** container: each entry
  is `type (2 bytes) | length (4 bytes) | reserved (2 bytes) | value`, little-endian. Borrowed
  from Nimbus's consensus-layer era format.
- `internal/era/onedb/builder.go:35-74` — the era1 layout, documented inline:
  `era1 := Version | block-tuple* | Accumulator | BlockIndex`, where
  `block-tuple := CompressedHeader | CompressedBody | CompressedReceipts | TotalDifficulty`,
  each snappy-framed. Capped at 8192 blocks/file (the accumulator SSZ list limit).
- `internal/era/accumulator.go:29-45` — `ComputeAccumulator`: the SSZ `hash_tree_root` of a
  list of `headerRecord{hash, totalDifficulty}` — the Portal Network "historical hashes
  accumulator". This root is embedded in the file and in its **filename**, making an era1
  file *self-verifying*.
- `internal/era/onedb/reader.go:38` — `Filename = "<network>-<epoch:05d>-<hexroot[0:8]>.era1"`.
- `internal/era/era.go:104-114` — `Era` read interface (`GetBlockByNumber`,
  `GetRawBodyByNumber`, `GetRawReceiptsByNumber`, `Accumulator`).

### Distribution & expiry plumbing
- `cmd/geth/chaincmd.go:154-216` — `import-history`, `export-history`, `prune-history`
  command definitions; `:481` `importHistory`, `:553` `exportHistory`, `:714` `pruneHistory`.
- `cmd/utils/flags.go:341-346` — `--history.chain` flag: `"all"`, `"postmerge"`,
  `"postprague"`.
- `core/history/historymode.go:26-53` — `HistoryMode` enum: `KeepAll` / `KeepPostMerge` /
  (post-prague); `staticPrunePoints` (`:90`) maps each mode+genesis-hash to a concrete
  `PrunePoint{BlockNumber, BlockHash}` so pruning targets are hard-coded per network, not
  guessed.
- `cmd/geth/chaincmd.go:714-786` — `pruneHistory`: validates the target block hash, checks
  the chain is `FullImmutabilityThreshold` past the target, prunes the tx index, then
  `TruncateTail(ChainFreezerBlockDataGroup, targetBlock)`.
- `core/rawdb/chain_freezer.go:384-394` — after pruning, reads below the freezer tail fall
  through to the `eradb` era backup (`GetRawBody` / `GetRawReceipts`); BAL and everything
  else returns `errOutOfBounds`. **Pruning does not lose data if era files are present.**
- `core/rawdb/eradb/` — `eradb.Store`: a read-only history backend over a directory of era1
  files, with an LRU of open file handles (`openFileLimit = 64`).
- `internal/era/eradl/eradl.go` — `Loader`: downloads era1 files over HTTP(S) against an
  **embedded checksum database** (`checksums_mainnet.txt`, `checksums_sepolia.txt`) — the
  out-of-band bootstrap path, checksum-verified before use.

## Design decisions & rationale

- **Two-store split by mutability, not by data type.** Anything past
  `HEAD - FullImmutabilityThreshold` (or finality) can never reorg, so it moves to
  append-only flat files. This keeps the hot KV small (better compaction, cache hit rate)
  while the bulk of the chain lives in a format optimized for sequential writes and O(1)
  indexed reads. Freezing is deliberately decoupled from block import
  (`chain_freezer.go:151-155`) so it never delays propagation.
- **Append-only + index-file addressing.** Because ancient data is addressed by block number
  (a dense monotonic key), a 6-byte fixed-width index entry per item gives direct offset
  lookup without a B-tree. Compression (snappy) is per-item and disabled where it doesn't pay
  (`hashes` table, `ancient_scheme.go:63`).
- **Tail groups make EIP-4444 pruning safe.** Bodies and receipts must be pruned as a unit;
  headers and hashes must survive (they're needed for proofs and fork-id forever). Encoding
  this as `tailGroup` metadata (`ancient_scheme.go:44-79`) lets one truncate call prune the
  right tables to a consistent tail while the header/hash tables stay whole.
- **era1 is verifiable-by-construction.** The accumulator root is a Merkle commitment over
  every (hash, TD) in the epoch and is baked into the filename, so a downloader can verify an
  era1 file against a known root *before* trusting its contents — enabling trustless
  out-of-band history distribution (HTTP CDN, torrents) as an alternative to P2P sync.
- **Static prune points per network.** `staticPrunePoints` hard-codes the merge/prague block
  and hash per genesis (`historymode.go:90`), so `prune-history` targets an
  audited, hash-checked boundary rather than a user-supplied number.

## Notable patterns (the reusable idea)

**The hot-KV / cold-immutable-history split with a portable, self-verifying segment format
as the distribution layer.** Three transferable pieces:

1. **Freeze boundary = provable immutability.** Migrate blocks out of the mutable KV once
   they're past a reorg-impossible depth; the cold store is append-only and addressed by
   block number, so it needs only a flat data file + a fixed-width index file per column.
2. **Prune the tail, not the whole store.** History expiry = truncating the *tail* of the
   cold store, gated behind a hard-coded, hash-verified target, with headers retained.
3. **Distribute history as verifiable files, not peer traffic.** A per-epoch file whose
   Merkle-root commitment is in its own name/contents lets a fresh node bootstrap history by
   downloading checksum-verified files over HTTP instead of syncing it block-by-block from
   peers.

## Authority note

geth is the authority for **the freezer/ancient store** and **the era1 file format** (it
invented both; era1 reuses Nimbus's e2store container and the Portal Network accumulator).
The alternatives are structurally different: **erigon** uses immutable "snapshots"
(`.seg` segment files) as its primary block/txn store rather than a hot/cold split, and
**reth** uses "static files" for the same historical-segment role. When cross-checking
history-distribution behavior, geth is the reference for freezer semantics and era1; erigon
snapshots / reth static-files are the comparison points for the segment-store approach.

## Gotchas / anti-patterns / things they later changed

- **Freezer is number-addressed, KV is hash-addressed.** Ancient reads take a block *number*
  (`Ancient(kind, number)`), not a hash. A block whose canonical number→hash mapping is
  missing at the freeze frontier (unclean shutdown) blocks freezing with an explicit error
  (`chain_freezer.go:313-318`) rather than silently corrupting.
- **Two separate on-disk locks.** The freezer uses `FLOCK`, deliberately named to avoid
  colliding with LevelDB/Pebble's `LOCK` in the same directory (`freezer.go:100-102`).
  Symlinked ancient dirs are rejected outright (`errSymlinkDatadir`, `freezer.go:47-49`).
- **Pruning is one-way and needs a backup.** `prune-history` truncates the tail; you cannot
  "unprune" — the only recovery is `import-history` from era1 files
  (`chaincmd.go:722-724, 758`). If you prune without era files present, reads below the tail
  return `errOutOfBounds` (`chain_freezer.go:385`). Note the in-code `TODO` about a crash
  between the tx-index prune and the tail truncate (`chaincmd.go:784`) — the two-step prune
  is not yet crash-atomic.
- **Crash-consistency is repaired on open, not journaled.** On non-readonly open the freezer
  runs `repair()` (`freezer.go:429`) to truncate all tables back to a common head; readonly
  open only `validate()`s (`freezer.go:371`). Torn appends from a crash are healed by
  discarding above the shortest table.
- **Format evolution.** era1 predates the merge and is pre-merge-only (always has an
  accumulator + TD per block); post-merge history uses the CL `Ere`/execdb variant
  (`internal/era/execdb/`, `era.go:78-96`), and a newer slim-receipt encoding
  (`TypeCompressedSlimReceipts`, eth/69) and per-block `TypeProof` entry were added — so
  "era file" now spans multiple related formats behind the shared `era.Era` interface.

---

### Alignment with fukuii

fukuii ships a **checkpoint-sync** capability (the `fukuii-checkpoint-service` skill: point a
fresh ETC/Mordor node at a trusted checkpoint archive so it starts SNAP sync from a known
pivot instead of waiting on peer consensus). That is the same *use-case* as era1's
out-of-band bootstrap — download a trusted, verifiable artifact to skip slow P2P history
acquisition. geth's model suggests a natural extension for fukuii's history distribution:
(1) a **freezer-style hot/cold split** to keep the RocksDB working set small once ETC blocks
are past reorg depth, and (2) a **verifiable per-epoch history file** (accumulator-committed,
checksum-in-filename) that a checkpoint/archival source could publish over HTTP — turning
fukuii's existing checkpoint-archive distribution into a full, verifiable historical-segment
format rather than a single pivot snapshot.
