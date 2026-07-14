# nethermind — historical-distribution
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind splits "historical distribution" into four independent, plugin-style
projects, each solving a different point on the archival ↔ bootstrap ↔ custody
spectrum:

1. **`Nethermind.Era1`** — the go-ethereum-compatible **era1** portable-history
   format. e2store binary files, one file per 8192-block epoch, Snappy-compressed
   header/body/receipts + total-difficulty + a `HistoricalHashesAccumulator`. This
   is the pre-merge PoW history story, interoperable with geth's era1 tooling and
   filename convention (`{network}-{epoch:05}-{accumulator8}.era1`).
2. **`Nethermind.EraE`** — **"Era Extended"** (2026), a *newer, superset* format
   that carries the **full chain history including post-merge**, backed by beacon-
   chain proofs. It adds slim receipts, inclusion proofs, a component-based index,
   three accumulator regimes (pre-merge accumulator, post-merge historical roots,
   post-Capella historical summaries), and — critically — **on-demand remote epoch
   download** from an era-archive HTTP server.
3. **`Nethermind.History`** — the **EIP-4444 history-expiry** engine: a background
   pruner that deletes blocks/receipts/block-access-lists older than a rolling
   retention window (or up to ancient barriers). Serves the light/custody use case.
4. **`Nethermind.Init.Snapshot`** — a **DB-snapshot bootstrap** plugin: before the
   node starts, download a pre-built database archive over HTTP, checksum it, and
   extract it directly into the datadir. A bootstrap mechanism distinct from both
   era import and peer sync.

Era1 and EraE are import/export tools (offline archival + reconstruction);
Init.Snapshot is a fast cold-start; History is ongoing disk-footprint management.

## Key types / interfaces / files

### Era1 (geth-compatible pre-merge format)
- `Nethermind.Era1/E2StoreReader.cs:19` — the e2store TLV reader (`type:u16`,
  `length:u32`, `reserved:u16`, value). Reads the trailing `BlockIndex` (negative
  offsets from EOF), 50 MiB per-entry size cap, SHA-256 file checksum.
- `Nethermind.Era1/E2StoreWriter.cs` — the writer half.
- `Nethermind.Era1/EntryTypes.cs:6` — era1 section tags: `Version 0x3265`,
  `CompressedHeader 0x03`, `CompressedBody 0x04`, `CompressedReceipts 0x05`,
  `TotalDifficulty 0x06`, `Accumulator 0x07`, `BlockIndex 0x3266`.
- `Nethermind.Era1/EraExporter.cs:35` — `Export(dest, from, to)`; parallel
  per-epoch (`Parallel.ForEachAsync`, `MaxDegreeOfParallelism = Concurrency`),
  writes `accumulators.txt` + `checksums.txt` manifests; renames each file to embed
  its accumulator hash once known.
- `Nethermind.Era1/EraImporter.cs:44` — `Import(src, from, to, accumulatorFile)`;
  epoch-boundary-aligned partitioning for parallel import; tunes blocks/receipts
  RocksDB to `HeavyWrite`; below sync-head it bulk-`Insert`s (WAL disabled), at/above
  head it `SuggestBlockAsync(...ShouldProcess)`. Verifies against a trusted-
  accumulator set loaded from the accumulator file.
- `Nethermind.Era1/AccumulatorCalculator.cs`, `EraStore.cs`, `EraStoreFactory.cs` —
  accumulator computation and the block→epoch-file lookup store.
- `Nethermind.Era1/JsonRpc/EraAdminRpcModule.cs`, `AdminEraService.cs` — `admin_*`
  RPC to trigger export/import/verification on a running node.

### EraE ("Era Extended" — full history incl. post-merge)
- `Nethermind.EraE/E2Store/EntryTypes.cs:8` — reuses era1's `Version/Header/Body/TD`
  tags but **adds** `CompressedSlimReceipts 0x0a`, `Proof 0x0b`,
  `ComponentIndex 0x3267`, and renames `0x07` to `AccumulatorRoot`. Section order:
  `Version | CompressedHeader* | CompressedBody* | CompressedSlimReceipts* |
  TotalDifficulty* | AccumulatorRoot? | ComponentIndex` (`Archive/EraWriter.cs:25`).
- `Nethermind.EraE/Archive/EraSlimReceiptDecoder.cs` — the slim (fields-dropped,
  re-derivable) receipt encoding that shrinks archives vs era1's full receipts.
- `Nethermind.EraE/Proofs/Validator.cs:51` — `VerifyBlocksRootContext` dispatches on
  `AccumulatorType`: **`HistoricalHashesAccumulator`** (pre-merge, era1-style),
  **`HistoricalRoots`** (post-merge → Capella), **`HistoricalSummaries`** (post-
  Capella). This tri-modal verification is the heart of what EraE adds.
- `Nethermind.EraE/Proofs/BlockProofs.cs:11` — SSZ `HistoricalBatch` (block_roots +
  state_roots, `SlotsPerHistoricalRoot = 8192`) used for beacon-anchored proofs.
- `Nethermind.EraE/Proofs/BeaconApiRootsProvider.cs`,
  `HistoricalSummariesRpcProvider.cs`, `BeaconApiHttpClient.cs` — fetch beacon block
  roots / historical summaries from a CL node (`EraEConfig.BeaconNodeUrl`) during
  post-merge export.
- `Nethermind.EraE/Store/HttpRemoteEraClient.cs:9` — fetches a `checksums_sha256.txt`
  manifest and downloads individual `.ere` epoch files over HTTP; filename→epoch
  parsing, path-traversal guard (`IsPlainFilename`).
- `Nethermind.EraE/Store/RemoteEraStoreDecorator.cs:23` — wraps a (possibly null)
  local store; **downloads missing epochs on demand**, SHA-256-verifies, optionally
  content-verifies via `EraReader.VerifyContent`, and manages a **bounded reader pool**
  (`ProcessorCount*2`, LRU-evicting lowest epoch) with per-epoch semaphores to avoid
  fd exhaustion and duplicate downloads.
- `Nethermind.EraE/Config/IEraEConfig.cs:8` — adds `BeaconNodeUrl`, `RemoteBaseUrl`
  (e.g. `https://data.ethpandaops.io/erae/{network}/`), `RemoteDownloadDirectory`,
  `RemoteChecksumFile` over the era1 config surface.

### History (EIP-4444 expiry)
- `Nethermind.History/HistoryPruner.cs:27` — background pruner. `TryPruneHistory`
  runs on `blockProcessingQueue.ProcessingQueueEmpty`, under a scheduler with a
  timeout (default 2 s) so it never blocks shutdown. Prunes blocks+receipts and
  block-access-lists (BALs) separately, persisting `blocksDeletePointer` /
  `balsDeletePointer` (RLP) in the metadata DB. Defensive: never deletes genesis or
  anything at/past the sync pivot.
- `Nethermind.History/IHistoryConfig.cs:9` — `PruningModes { Disabled, Rolling,
  UseAncientBarriers }`; `RetentionEpochs` default **82125** (the EIP-4444 mainnet
  floor, `~1yr` at 32 slots/epoch); `BalRetentionEpochs` default 3533 (weak-
  subjectivity period). Rolling cutoff = `head - retentionEpochs*32`.

### Init.Snapshot (DB-snapshot bootstrap)
- `Nethermind.Init.Snapshot/InitDatabaseSnapshot.cs:25` — an `IStep` that runs
  **before** `InitializeBlockTree`. Download → SHA-256 verify → extract → delete
  archive, each stage gated by a persisted checkpoint. `CheckDiskSpace` requires
  `2.5×` the archive size free.
- `Nethermind.Init.Snapshot/SnapshotDownloader.cs:17` — **resumable** HTTP download:
  sends a `Range` header, manually follows redirects (stock `HttpClient` strips
  `Range` on auto-redirect), and on a `200` with a partial local file it *consumes-
  and-skips* the already-downloaded prefix rather than re-downloading.
- `Nethermind.Init.Snapshot/SnapshotExtractor.cs:14` — extracts `.zip` or
  tar+`.zst`/`.gz` (bz2/xz explicitly unsupported), with `--strip-components`
  semantics and a zip-slip / tar-escape guard.
- `Nethermind.Init.Snapshot/SnapshotCheckpoint.cs:14` — `Started → Downloaded →
  Verified → Extracted → Completed` written atomically (temp-file + `WriteThrough`
  flush + rename) so a mid-write crash cannot corrupt resume state.
- `Nethermind.Init.Snapshot/SnapshotPlugin.cs:8` / `ISnapshotConfig.cs:8` —
  plugin enabled only when `Enabled: true` **and** `DownloadUrl` is set.

## Design decisions & rationale

- **Era1 stays byte-compatible with geth** so the same public era1 archives serve
  every client; the epoch-offset comment in `EraExporter.WriteEpoch` explicitly
  matches geth's boundary behaviour.
- **EraE is a clean-slate second project, not a version bump of Era1.** Post-merge
  history can't be proven by a single running accumulator, so EraE introduces a
  proof section (`0x0b`) plus three accumulator regimes and a beacon-node
  dependency. Keeping it separate avoids polluting the geth-interop era1 path.
- **Remote-on-demand era store (EraE) vs local-directory-only (Era1).** Era1 import
  requires the whole archive on local disk; EraE's `RemoteEraStoreDecorator` lets a
  node stream only the epochs it needs from an HTTP archive, with a bounded reader
  pool sized to the OS fd limit and per-epoch download deduplication.
- **Init.Snapshot trades trust for speed.** A DB snapshot is a fully-built RocksDB —
  no execution, no proof, just a checksummed tarball. The resumability machinery
  (Range + checkpoint + atomic writes) exists because these archives are huge and a
  restart mid-download must not start over.
- **History pruning is opportunistic and bounded.** It piggybacks on an idle block-
  processing queue and hard-caps each pass with a timeout, so expiry never competes
  with live processing or delays shutdown.

## Notable patterns (the reusable idea)

- **Stage-checkpointed resumable bootstrap** (`SnapshotCheckpoint` +
  `InitDatabaseSnapshot`): a tiny on-disk enum, advanced only after each stage's
  side effect is durable, written atomically. Any long, interruptible, multi-stage
  operation (snapshot download, checkpoint import, era backfill) should carry one.
- **Manual-Range resumable HTTP download** that survives redirects and servers that
  ignore `Range` (falls back to skip-the-prefix). Directly transferable to fukuii's
  checkpoint-archive fetch.
- **Bounded, LRU-evicting reader pool with per-key semaphores** for streaming a large
  set of remote files without exhausting fds or double-downloading.
- **Tri-modal history proof dispatch** (`Validator.VerifyBlocksRootContext`): one
  archive format, three verification strategies keyed by fork era.

## Authority note

geth = era1/freezer authority (the canonical era1 e2store format + `ancient`
freezer store); erigon = torrent-based snapshot distribution. **nethermind is the
era1+EraE + DB-snapshot-bootstrap variant**: it re-implements geth-compatible era1,
*extends* it to full post-merge history via beacon proofs (EraE, with remote
on-demand fetch), and independently offers a download-a-prebuilt-RocksDB bootstrap
(Init.Snapshot) that neither geth's era1 nor erigon's torrents provide. For
PoW/ETC purposes, geth/core-geth era1 remains the format authority; EraE's post-
merge machinery (beacon roots/summaries) is ETH-PoS-specific and not relevant to
ETC's pre-merge history.

## Gotchas / anti-patterns / things they later changed

- **`.era1` vs `.ere`/legacy `.erae`.** EraE files use a different extension and a
  different index tag (`ComponentIndex 0x3267` vs era1 `BlockIndex 0x3266`); the two
  formats are *not* interchangeable despite sharing the low entry tags.
- **EraE post-merge export needs a live CL node** (`BeaconNodeUrl`). Without it the
  providers resolve to `Null*` implementations and post-merge epochs can't be
  proof-annotated — a silent capability downgrade, not an error.
- **Init.Snapshot only bootstraps an *empty* datadir.** If `dbPath` exists and the
  checkpoint shows extraction completed, it skips; if extraction was interrupted it
  **deletes the whole db directory** and restarts — dangerous to point at a datadir
  you care about.
- **`StripComponents` must match the archive layout** (default 1); a mismatch
  silently extracts to the wrong depth. bz2/xz tarballs are matched by the format
  sniff but then throw `NotSupportedException` (no .NET BCL decompressor).
- **Checksum failure in Init.Snapshot is non-fatal**: it deletes the bad file,
  resets the checkpoint, and lets the node continue (to peer-sync) rather than
  aborting — intentional, but easy to miss in logs.
- **EraE remote `RemoteEraStoreDecorator.BlockRange.Last` is an upper-bound estimate**
  (`(maxEpoch+1)*maxEraSize - 1`) to avoid downloading the final huge epoch just to
  learn its exact last block; importers relying on it must tolerate `(null,null)`
  past the real end.
