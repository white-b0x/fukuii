# reth — historical-distribution
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth splits its chain data into two physically distinct stores by *mutability*, not by
subsystem:

- **Hot / mutable state → MDBX** (the live database): everything still changing —
  latest state trie, indices, tx pool, the tip of the chain.
- **Cold / immutable history → static files** (reth's "freezer"): append-only,
  memory-mapped, columnar segment files for the finalized-and-frozen tables (headers,
  transactions, receipts, sender recovery, and account/storage changesets). Once a block
  range is frozen it is never mutated in place — only appended to or pruned whole-file.

The **static-file store** is the archival substrate: compact, LZ4-compressed, columnar
`NippyJar` files sharded by fixed block ranges and served via mmap for zero-copy reads.
The **`StaticFileProducer`** is the mover that copies finalized rows out of MDBX into new
static-file segments. On top of that, three **ERA** crates give reth an out-of-band history
transport: **era1** files (pre-merge execution history) and **era** files (post-merge
consensus-layer history embedding execution payloads) can be exported from, or imported
into, the static-file store. The **`era-downloader`** HTTP-fetches era1/era files so a fresh
node can bootstrap all history *without* P2P header/body sync. **Pruning / history-expiry**
(EIP-4444) is implemented by deleting whole static-file segments below a threshold block.

Data flow, three ways in:
1. P2P staged sync → MDBX → `StaticFileProducer` freezes into static files.
2. `era-downloader` (HTTP) → `era-utils::import` → writes headers directly into the
   static-file Headers segment + bodies into MDBX, skipping P2P entirely.
3. Genesis-only + expiry: prune drops old segments (EIP-4444 pre-merge history).

## Key types / interfaces / files

### Static-file frozen-segment store (the freezer)
- `crates/static-file/types/src/segment.rs:31` — `StaticFileSegment` enum: the six frozen
  segments (`Headers`, `Transactions`, `Receipts`, `TransactionSenders`, `AccountChangeSets`,
  `StorageChangeSets`). Each maps to the MDBX tables it replaces and to the sync `StageId`
  that fills it (`to_stage_id`, `segment.rs:218`).
- `crates/static-file/types/src/segment.rs:130` — `filename()`:
  `static_file_{segment}_{block_start}_{block_end}` — the on-disk sharding scheme. Files are
  fixed block ranges; `parse_filename` (`segment.rs:167`) round-trips it. Underscores are
  forbidden inside a segment name because they are the path delimiter (`segment.rs:72`).
- `crates/static-file/types/src/segment.rs:113` — `SegmentConfig { compression: Lz4 }`: LZ4
  is the default per-segment compression.
- `crates/static-file/types/src/segment.rs:269` — `SegmentHeader`: per-file metadata —
  `expected_block_range`, actual `block_range`, `tx_range`, segment kind, and (for
  changesets) a `changeset_offsets_len` pointing at a `.csoff` sidecar. This is the
  `NippyJar` user-header, so range info lives *in* the jar.
- `crates/storage/nippy-jar/src/lib.rs:84` — `NippyJar<H>`: the immutable columnar file
  format. "Data retrieval entails consulting an offset list and fetching the data from file
  via `mmap`" (`lib.rs:87`). Uses `memmap2::Mmap` (`lib.rs:15`). Supporting files:
  `.off` (offsets), `.conf` (config), `.csoff` (changeset offsets) — `lib.rs:57-63`.
  Explicitly *not* hardened against malicious data (`lib.rs:4`) — it's an internal store,
  not a network-facing format.
- `crates/storage/provider/src/providers/static_file/mod.rs:29` — `LoadedJar`: holds the
  `NippyJar` + an `Arc<DataReader>` mmap handle, so cursors reuse one mmap
  (`mod.rs:31`, `jar.rs:65`).
- `crates/storage/provider/src/providers/static_file/manager.rs:273` —
  `StaticFileProviderInner`: the store manager. `map: DashMap<(BlockNumber,
  StaticFileSegment), LoadedJar>` (`manager.rs:276`) gives concurrent per-segment/per-range
  access. `get_highest_static_file_block` (`manager.rs:1905`) is the boundary between what
  the static files hold and what MDBX still owns — this *is* the hot/cold seam.
- `crates/storage/provider/src/providers/static_file/jar.rs:30` — `StaticFileJarProvider`:
  implements `HeaderProvider`/`TransactionsProvider`/`ReceiptProvider` over a single jar +
  range, via mmap cursors. Note `chain_info`/`best_block_number` return
  `UnsupportedProvider` (`jar.rs:233-246`) — static files are pure history, not chain tip.
- `crates/static-file/static-file/src/static_file_producer.rs:33` — `StaticFileProducer`:
  the process that freezes finalized MDBX rows into new static-file segments (rayon-parallel
  per segment, `static_file_producer.rs:5`), driven by `PruneModes`
  (`static_file_producer.rs:37`).

### ERA1 / ERA file support (out-of-band history)
- `crates/era/src/era1/file.rs:31` — `Era1File`: structure
  `Version | block-tuple* | other-entries* | Accumulator | BlockIndex` (`file.rs:4`),
  implementing the e2store `era1.md` spec (`file.rs:6`). Each block tuple is
  compressed header + body + receipts + total-difficulty
  (`crates/era/src/era1/types/execution.rs:88`).
- `crates/era/src/era1/types/execution.rs:103` — `MAX_BLOCKS_PER_ERA1 = 8192`: an era1 file
  holds up to 8192 blocks. `Accumulator` (`execution.rs:457`) is an SSZ list of
  header-records — the cryptographic commitment that lets an era1 file be verified
  standalone.
- `crates/era/src/e2s/` — the underlying **e2store** primitives (`file.rs`, `types.rs`) both
  era1 and era build on.
- `crates/era/src/era/file.rs` — `.era` (consensus-layer) files: post-merge history stored as
  `SignedBeaconBlock`s embedding an execution payload.

### era-downloader (HTTP bootstrap)
- `crates/era-downloader/src/lib.rs:1` — crate purpose: "An asynchronous stream interface for
  downloading ERA1 files." Doc example (`lib.rs:16-33`) shows the whole contract: point at a
  URL, stream `Box<Path>` files as they land.
- `crates/era-downloader/src/client.rs:44` — `EraClient<Http>`: HTTP client that fetches the
  index (`fetch_file_list`, `client.rs:196`), parses era filenames out of `index.html`
  (`extract_era_filenames`, `client.rs:219`), downloads each file to a `.tmp` path and
  renames on success so an interrupted download never looks complete
  (`client.rs:88-123`), and verifies each file against a `checksums.txt` SHA-256
  (`verify_checksum`/`assert_checksum`, `client.rs:296-316`). era1/ere ship checksums;
  `.era` files skip verification (`client.rs:283`).
- `crates/era-downloader/src/client.rs:19` — `HttpClient` trait: the transport seam — any
  `reqwest::Client`-like GET-returning-a-byte-stream works (`impl` at `client.rs:30`).
- `crates/era-downloader/src/stream.rs:73` — `EraStream` + `EraStreamConfig`
  (`stream.rs:24`): backpressured download — `with_max_files` bounds files kept on disk,
  `with_max_concurrent_downloads` bounds parallelism (`lib.rs:19-24`); `start_from` maps a
  block number to a file index via `BLOCKS_PER_FILE = 8192` (`stream.rs:51`).
- `crates/era-downloader/src/stream.rs:107` — `EraMeta` trait: `path()` +
  `mark_as_processed()` — the handoff contract between the downloader and the importer;
  marking processed is what lets `max_files` free disk.

### era import/export (bridge to the static-file store)
- `crates/era-utils/src/history.rs:171` — `import(...)`: the bootstrap driver. Downloads run
  on a background tokio task feeding an mpsc channel (`history.rs:194-202`); for each ERA file
  it appends headers into the **static-file Headers segment**
  (`latest_writer(StaticFileSegment::Headers)`, `history.rs:221`) and bodies into MDBX
  (`append_block_bodies`, `history.rs:392`), then records `Headers`/`Bodies`
  **stage checkpoints** so staged sync treats that work as already done
  (`save_stage_checkpoints`, `history.rs:252`). Resumes from the current
  `get_highest_static_file_block(Headers)` (`history.rs:208`).
- `crates/era-utils/src/history.rs:39` — `EraBlockReader` trait, with impls `Era1`
  (`history.rs:48`, `.era1`), `Ere` (`history.rs:78`, `.ere`/`.erae`), and `Era`
  (`history.rs:103`, consensus `.era`). The `Era` reader SSZ-decodes each beacon block,
  extracts the embedded execution payload, and skips pre-merge slots that carry none
  (`history.rs:123`).
- `crates/era-utils/src/history.rs:376` — `process_iter` rejects **non-contiguous** appends:
  because import marks stages complete up to `height`, a gap would leave earlier blocks
  missing while stages report done. This is the key correctness invariant of HTTP bootstrap.
- `crates/era-utils/src/export/mod.rs:1` — `export`: the reverse direction — walks stored
  blocks in `max_blocks_per_file` chunks and hands each to an `EraBlockWriter`
  (`Era1`→`.era1`, `Ere`→`.ere`), computing accumulator/block-index per file.

### History expiry (EIP-4444 / pruning)
- `crates/storage/provider/src/providers/static_file/manager.rs:842` —
  `delete_segment_below_block`: history-expiry = delete whole static-file segments below a
  block; never deletes the file containing the block (files are removed *entirely* only),
  and never deletes the single highest file (`manager.rs:832-839`).
- `crates/storage/provider/src/providers/static_file/manager.rs:279` —
  `earliest_history_height` (`AtomicU64`): tracks the highest expired (missing) block so the
  node cheaply knows its history floor; first non-expired block is
  `earliest_history_height + 1`.
- `crates/storage/provider/src/providers/static_file/manager.rs:2217` — comment: history
  expiry "targets transactions, e.g. pre-merge history expiry would lead to removing all
  static files below the merge height" — the concrete EIP-4444 use case.
- `crates/prune/prune/` — the general pruning crate (segment-based pruning of MDBX tables);
  static-file expiry above is the freezer half.

## Design decisions & rationale

- **Split by mutability, not by table.** MDBX pays for B-tree overhead only on data that
  actually changes; frozen history moves to a flat, mmap'd, compressed columnar file where
  reads are a pointer + offset lookup. This is the whole reason static files exist: MDBX
  page overhead and write amplification are wasted on append-only history.
- **mmap columnar (`NippyJar`) for archival reads.** Per-column compression (LZ4) plus
  memory-mapped access gives compact storage *and* zero-copy, OS-page-cached reads — ideal
  for archive queries that scan ranges of headers/receipts. The range is baked into the
  filename and the `SegmentHeader`, so the manager can pick the right file for a block with
  no index probe.
- **Fixed-range file sharding.** Files are `{start}_{end}` block ranges, so expiry and
  distribution are whole-file operations: you can delete, copy, or seed an individual file
  without touching the rest. This is what makes both EIP-4444 expiry and era distribution
  cheap.
- **ERA as a portable, verifiable history transport.** era1 embeds an SSZ `Accumulator`
  per 8192-block file, so a file downloaded over plain HTTP is self-verifying (plus a
  `checksums.txt` SHA-256 gate). History can therefore be distributed over dumb HTTP/CDN
  instead of trusted P2P peers.
- **HTTP bootstrap writes straight into the freezer + fakes stage checkpoints.** `import`
  appends into the static-file Headers segment and *marks the Headers/Bodies stages
  complete*, so the node comes up as if it had P2P-synced that range — but at HTTP download
  speed. The non-contiguity guard keeps this from silently corrupting the stage model.
- **Interrupted-download safety by construction.** Download to `.tmp`, checksum, then
  atomic rename — a crashed download never leaves a file that later passes as complete.

## Notable patterns (the reusable idea)

1. **Two-tier store split by mutability** — a mutable KV engine for the hot tip, an
   append-only mmap columnar store for frozen history, with a single "highest frozen block"
   watermark as the seam. A "producer" migrates rows across the seam on finalization.
2. **Fixed-block-range file shards as the unit of distribution AND expiry** — the same
   whole-file granularity that lets you `rm` old history (EIP-4444) also lets you copy/seed
   one file. History transport and history pruning fall out of the same design.
3. **Self-verifying history files over dumb HTTP** — an accumulator/commitment baked into
   each fixed-size file + a checksum manifest means you can bootstrap all pre-tip history
   from a static file server (or CDN) and skip P2P sync, while still verifying integrity.
   The download loop is a backpressured stream (`max_files`, `max_concurrent`) with
   tmp-then-rename atomicity and resume-from-highest.
4. **Bootstrap writes into the cold store and satisfies the sync state machine** — importing
   history isn't a special mode; it fills the same static-file segments staged sync would and
   stamps the same stage checkpoints, so the rest of the node is oblivious to *how* history
   arrived.

## Authority note

reth is the reference for the **static-file (mmap frozen-segment `NippyJar`) freezer +
era-downloader HTTP era1-file bootstrap** design. Peers for cross-referencing:
- **geth** — the original **freezer** (ancient store) + **era1** format author; the e2store
  `era1.md` spec reth implements is the eth-clients standard.
- **erigon** — distributes history via **BitTorrent snapshots** (`.seg` files) rather than
  HTTP — a different transport for the same "frozen-history-in-flat-files" idea.
- **nethermind** — **era1 / EraE** import/export support (its own era-family handling).

For fukuii's PoW/ETC lineage, the authoritative reference client is **core-geth**, whose
freezer + era1 support descends from geth; reth here is a structural reference for *how* a
modern client organizes the cold store and the HTTP bootstrap path, not an ETC-consensus
authority.

## Gotchas / anti-patterns / things they later changed

- **Static files are history-only, not chain state.** `StaticFileJarProvider` returns
  `UnsupportedProvider` for `chain_info`/`best_block_number`/`last_block_number`
  (`jar.rs:233-246`) and for indexed lookups like `transactions_by_block` — those need MDBX
  index tables. The freezer holds rows by tx/block number, not the indices that map hash→id.
  Callers must resolve the index in MDBX first, then hit the static file.
- **`NippyJar` is explicitly not hardened against malicious input** (`nippy-jar/src/lib.rs:4`)
  — it is an internal format. Never point it at untrusted files. (era1/era files, by
  contrast, *are* the network-facing format and carry checksums + accumulators.)
- **Non-contiguous ERA import is a hard error, by design** (`history.rs:376`): you cannot
  import an era1 file that starts above `db_height + 1`, because the importer marks stages
  complete up to the imported height. The DB must be synced up to the file's first block − 1
  first. Silent gap-appending (which a naive pre-merge `.era` import could produce) is
  rejected.
- **Expiry can never remove the last/highest file** (`manager.rs:832-839`) and only removes
  whole files — you cannot expire down to an arbitrary block, only to a file boundary. The
  file containing the target block stays.
- **`SegmentHeader` gained a 5th field** (`changeset_offsets_len` + `.csoff` sidecar) for the
  changeset segments; the custom `Deserialize` (`segment.rs:287-333`) handles both the old
  4-field and new 5-field on-disk layouts — evidence the frozen format is versioned and has
  evolved (changesets were added to what static files cover).
- **`.erae`/`.ere` filename ambiguity** (`client.rs:349`): extensions must be matched
  longest-first or `.ere` clips a `.erae` name — a real parsing footgun they added tests for
  (`client.rs:410`).
- **Checksums only cover era1/ere.** `.era` (consensus) downloads skip verification
  (`client.rs:283`) because there is no `checksums.txt`; integrity there relies on the
  embedded consensus data, not the download manifest.
