# erigon — historical-distribution
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon's defining feature for history distribution is that **frozen history is
distributed over BitTorrent, not P2P block-sync.** Old blocks/receipts/txns and
old state history are periodically frozen out of the hot mutable database (MDBX)
into immutable, compressed `.seg` **segment files**; those files are published as
BitTorrent torrents (with HTTP "WebSeed" fallback served from CDN/S3). A fresh
node bootstraps by *torrenting the pre-frozen segments* — hours instead of days of
P2P header/body sync — verifies each file against a curated list of "preverified"
hashes, and then only runs live P2P sync for the recent, un-frozen tip. The data
flow is one-directional and irreversible: `hot MDBX → freeze to .seg → merge small
segments into bigger ones → prune ancient rows from MDBX`, and **`Unwind` beyond
data already frozen into snapshots is not allowed** (`CLAUDE.md` architecture
notes; `db/agents.md`).

Two layers cooperate:
1. **The freezer** (`db/snapshotsync/freezeblocks/`) — produces the immutable
   `.seg` files by dumping DB ranges through Erigon's custom dictionary/Huffman
   compressor (`db/seg/`), then merging fixed-size ranges.
2. **The downloader** (`db/downloader/`) — a full BitTorrent client
   (anacrolix/torrent) that fetches those `.seg` files from torrent peers and/or
   WebSeed HTTP endpoints, gated by a preverified-hash trust list.

## Key types / interfaces / files

### The downloader (BitTorrent client)
- `db/downloader/downloader.go:92` — `type Downloader struct`; wraps
  `*torrent.Client` (`:109`) — the anacrolix/torrent BitTorrent engine.
  `torrentsByName`/`downloads` maps track per-file torrents.
- `db/downloader/downloader.go:271` — `New(...)` constructor; builds separate HTTP
  transports so WebSeed data requests and torrent-metainfo fetches don't block
  each other (`:274`).
- `db/downloader/downloader.go:339` — `AddTorrentsFromDisk` — loads `.torrent`
  metainfo files present locally.
- `db/downloader/downloader.go:632` — `VerifyData(...)` — re-hashes downloaded
  pieces to confirm integrity.
- `db/downloader/downloader.go:1190` / `:1228` —
  `webseedMetainfoUrls` / `webseedMetainfoUrl` — a torrent's `.torrent` metainfo
  is itself fetched over HTTP from a WebSeed base URL (`base + name + ".torrent"`),
  so even the torrent metadata is CDN-servable, not only P2P-servable.
- `db/downloader/webseed.go:36-46` — `type WebSeeds struct`; doc: _"allow use
  HTTP-based infrastructure to support Bittorrent network — download .torrent files
  and data files from trusted url's (for example: S3 signed url)."_ This is the
  **WebSeed model**: HTTP mirrors act as always-available torrent seeders.
- `db/downloader/webseed.go:221` — `retrieveManifest` — GETs `manifest.txt` from
  each WebSeed provider (HEAD probe first, `:224`), yielding the file→URL map.
- `db/downloader/webseed.go:63` — `checkHasTorrents` — verifies every seedable
  file in a manifest has a matching `.torrent`, and flags dangling torrents.
- `db/downloader/downloadercfg/downloadercfg.go:48-51` —
  `const DefaultPieceSize = 2 * 1024 * 1024` (2 MB pieces — deliberately large
  because Erigon serves few very large files, reducing piece-hashing overhead).

### Novel: P2P discovery of the snapshot info-hash (discv5 ENR)
- `db/downloader/p2p_chaintoml.go:82-121` — `ChainTomlPeer` /
  `DiscoverChainToml` / `DiscoverAllChainToml`. Peers advertise the current
  snapshot set's BitTorrent **info-hash** inside a `"chain-toml"` **discv5 ENR
  entry** (`enr.ChainToml{ InfoHash, KnownBlocks, AuthoritativeBlocks }`). A
  bootstrapping node picks the ENR advertising the highest `KnownBlocks`
  (`:113`), skipping zero-info-hash or stale entries. This lets the *latest
  snapshot torrent be discovered through the P2P network itself*, not only via a
  central server. Wired via `Downloader.enrUpdater`/`nodeSourceFn`
  (`downloader.go:119-125`), enabled by `--snap.p2p-manifest`.

### Preverified-hash trust list + CDN distribution
- `db/snapcfg/util.go:118` — `type Preverified struct { Items PreverifiedItems }`
  — the curated `filename → sha1-info-hash` trust set. `db/snapcfg/util.go:46-47`
  aliases `preverified.Item`. Loaded remotely or embedded from the
  `erigontech/erigon-snapshot` repo (`snapshotGitBranch`, `util.go:50`).
- `db/snapcfg/cdn.go:12-16` — `SnapshotSource` enum: `Github` (0) / `R2` (1). Two
  CDN origins for the preverified TOMLs:
  - `ChainTomlR2URL` → `https://erigon-snapshots.erigon.network/<branch>/<chain>.toml`
    (Cloudflare R2; needs `InsertCloudflareHeaders`, `cdn.go:22`).
  - `ChainTomlGitHubURL` → `raw.githubusercontent.com/erigontech/erigon-snapshot/...`.
- `db/downloader/README.md:42-51` — `preverified.toml` format
  (`'v1.0-000000-000500-headers.seg' = '<sha1>'`); explains the trust chain:
  `$CHAIN.toml` (well-known hashes embedded at build time) → `BittorrentInfo`
  (local completion state) → `preverified.toml` (local override / dev pinning).

### The frozen `.seg` segment format
- `db/snaptype/files.go:38-47` — filename scheme:
  `FileName = "<version>-<from/1000 %06d>-<to/1000 %06d>-<type>"`,
  `SegmentFileName` appends `.seg` — e.g. `v1.0-000000-000500-transactions.seg`.
  Ranges are counted in thousands of blocks.
- `db/snaptype/files.go:374` — `IsSeedableExtension`; `:359` `.seg` is the sole
  seedable data extension (`.idx`/`.txt`/`.toml` are the accompanying sidecars).
- `db/snaptype/files.go:391-393` — merge granularity constants:
  `Erigon2OldMergeLimit = 500_000`, `Erigon2MergeLimit = 100_000`,
  `CaplinMergeLimit = 10_000` (blocks per merged segment).
- `db/seg/compress.go:45` / `:90` / `:101` — `type Cfg` + `DefaultCfg` +
  `type Compressor`. The `.seg` codec is Erigon's own **dictionary + Huffman
  "superstring" compressor** (not gzip/zstd): it mines repeated byte patterns
  (`MinPatternScore`, `MinPatternLen=5`, `MaxDictPatterns=64K`) into a
  per-file dictionary, then Huffman-codes references. The in-file comment table
  (`compress.go:53-66`) documents the RAM/size/decode-speed tradeoff of dictionary
  size on a 74 GB BSC transactions segment.
- `db/snapshotsync/freezeblocks/block_snapshots.go:619` — `BlockCompressCfg`
  (block-specific tuning: `MinPatternLen=8`, `MaxDictPatterns=16K` to bound
  Huffman-tree RAM).

### The freezer pipeline (hot → cold)
- `db/snapshotsync/freezeblocks/block_snapshots.go:167` — `NewBlockRetire` (the
  freezer driver).
- `:293` `retireBlocks` → `:577` `DumpBlocks` → `:630` `dumpRange` — dump a DB
  block range into a `.seg` via the compressor. Small ranges below the merge limit
  are stored uncompressed (`AddUncompressedWord`, `:653-659`) — "build fast, merge
  slow."
- `:337` `MergeBlocks` — coalesce many small `.seg` into fewer big immutable ones;
  driven by `db/snapshotsync/merger.go:23` `Merger`, `:40` `FindMergeRanges`,
  `:153` `Merge`.
- `:377` `PruneAncientBlocks` — delete now-frozen rows from hot MDBX (the
  "ancient store" reclamation).
- `:418` `RetireBlocksInBackground` — the async freeze loop.
- `db/snapshotsync/snapshots.go:244` `DirtySegment` / `:269` `VisibleSegment` /
  `:547` `RoSnapshots` — the read side: memory-mapped, reference-counted view over
  the immutable segment set (dirty = on disk but not yet published to readers,
  visible = safe to serve).

### The bootstrap entry point
- `db/snapshotsync/snapshotsync.go:71` `RequestSnapshotsDownload` /
  `:357` `SyncSnapshots` — builds the download request from the preverified cfg
  and hands it to the Downloader; supports `SnapshotDownloadToBlock` to fetch only
  history up to a chosen block (`:381-416`), deleting over-fetched segments after.
  If `snapCfg.Local` (locally-preverified), it skips remote download entirely
  (`:375`).

## Design decisions & rationale

- **BitTorrent over P2P block-sync for history.** Frozen history is static and
  identical for every node, so it's a perfect fit for content-addressed
  distribution: torrent it in parallel from many peers + CDN mirrors instead of
  serially requesting headers/bodies from a handful of eth-wire peers. This is
  the single biggest bootstrap-time reduction Erigon ships.
- **WebSeed = HTTP-backed torrents.** Pure BitTorrent swarms can go cold; Erigon
  keeps availability high by making S3/CDN endpoints act as permanent seeders
  (`webseed.go:37`). The same URLs serve both the `.torrent` metainfo and the
  `.seg` payload, so a node can fall back to plain HTTP when no peers exist.
- **Trust via curated preverified hashes, not just torrent info-hashes.** The
  `preverified.toml` / `$CHAIN.toml` list pins exact `.seg` content hashes signed
  off by the Erigon team, so a node won't accept an attacker-substituted segment
  even if it comes from a malicious peer (`README.md:34-40`,
  `VerifyData` re-hash at `downloader.go:632`).
- **Immutable, append-only segments; no unwind past frozen data.** Because frozen
  history can never change, segments can be memory-mapped read-only and shared,
  and pruning ancient hot-DB rows is safe. The cost is that deep reorgs into
  frozen ranges are structurally disallowed.
- **Custom dictionary/Huffman `.seg` codec** rather than a general compressor:
  block/txn data has heavy structural repetition; a per-file mined dictionary
  beats generic algorithms on ratio while keeping random-access decode cheap
  (each word is independently decodable).
- **Large 2 MB pieces**: few huge files means large pieces cut piece-hash
  bookkeeping without hurting parallelism (`downloadercfg.go:48`).

## Notable patterns (the reusable idea)

**Freeze static history into content-addressed, curated-hash-verified segment
files, then distribute them out-of-band (BitTorrent + HTTP-CDN WebSeed) so a new
node bootstraps by downloading pre-built history in parallel instead of replaying
P2P block-sync.** The trust anchor is a small, signed `filename→hash` list; the
transport is swappable (torrent swarm, S3, CDN, or — Erigon's novel twist — an
info-hash advertised through discv5 ENR so the swarm is self-describing over the
existing P2P discovery layer). The freeze side is equally reusable: a
one-directional `hot-DB → immutable compressed segment → merge → prune-ancient`
pipeline with a hard "no unwind past frozen" rule.

For **fukuii**, the transferable core is the *out-of-band bootstrap* idea. Fukuii
already ships **checkpoint-sync** (see `fukuii-checkpoint-service`: point a fresh
ETC/Mordor node at a trusted checkpoint archive so it imports a pivot and starts
SNAP sync immediately). Erigon's snapshot distribution is the *same problem class
— bootstrap acceleration — via a different mechanism*: checkpoint-sync accelerates
the *state* pivot, whereas snapshot-torrenting accelerates *history* delivery.
The two compose: a checkpoint gets you a recent state root fast; torrented frozen
segments would get you the *backfilled history* (bodies/receipts for archival/RPC)
without days of reverse P2P sync. The most directly liftable pieces are (1) the
**curated preverified-hash trust list served from a CDN/GitHub with a fallback
mirror**, and (2) the **WebSeed pattern** — publish frozen ETC history as
content-addressed files an operator can host on any HTTP/S3 endpoint, giving
reproducible, auditable enterprise bootstrap. This is a strong
**OPTIONAL(fast-bootstrap / archival)** for fukuii, not a core requirement.

## Authority note

Erigon is **THE reference for BitTorrent-based snapshot distribution + the frozen
`.seg` segment format + the WebSeed model + discv5-ENR snapshot discovery** — no
other client in the survey distributes history this way. The alternatives are
mechanism-different: **go-ethereum's freezer/"ancient store"** (append-only flat
files pruned from LevelDB, but *not* torrent-distributed) and its **era1 files**
(the standardized `.era1` history-archive format for import/export/backfill), and
**besu's checkpoint sync** (bootstrap from a trusted checkpoint block, closest in
spirit to fukuii's own checkpoint-service but state-pivot- rather than
history-file-oriented). Note: this Erigon v3 tree (commit `f1d79d699e`) has **no
`.era`/`.era1` package** — grep for `ExportEra`/`ImportEra`/`.era1` returns
nothing; Erigon standardized on `.seg` snapshots and does not ship geth-style era
files. (Any "era-downloader"/"era-utils" crates from a crate list belong to a
Rust client such as reth, not Erigon.)

## Gotchas / anti-patterns / things they later changed

- **Segments are immutable and un-unwindable.** A deep reorg into an
  already-frozen range is disallowed by design (`CLAUDE.md`, `db/agents.md`).
  Freezing is a one-way door — freeze conservatively behind the reorg-safe depth.
- **Torrents can stall on a stale/unavailable snapshot.** The documented recovery
  is `erigon snapshot reset` or a downloader restart to re-sync to a newer
  snapshot (`README.md:9-11`); `--local=false` keeps files not in the latest
  snapshot to avoid re-downloading unchanged data.
- **Merge-limit history matters.** The old 500 000-block merge limit
  (`Erigon2OldMergeLimit`) was superseded by the 100 000 limit
  (`Erigon2MergeLimit`) in the E3 era to "retire earlier → prune earlier" and keep
  the hot DB small (`block_snapshots.go:649-653`); both values are still honored
  when reading legacy files (`snapcfg/util.go:395-396`, `oldMergeSteps` at `:430`).
- **Preverified hash vs. self-generated hash collisions.** When an operator's
  self-generated file X (hash H1) differs from a later Erigon release's
  preverified file X (hash H2), the mismatch must be handled by *not* sending H2
  to the Downloader (explicit corner-case comment,
  `snapshotsync.go:425-427`) — otherwise the node would try to re-download data it
  already has under a different hash.
- **Small ranges are stored uncompressed on purpose** (`dumpRange` `noCompress`
  branch, `block_snapshots.go:653-659`): "build must be fast" for recent freezes;
  compression ratio is recovered later during (slow, expensive) merges. Don't
  assume every `.seg` is compressed.
- **CDN header quirk.** R2 access requires a hard-coded Cloudflare header
  (`cdn.go:18-24`); a node pointed at the R2 origin without it silently gets
  rejected responses.
