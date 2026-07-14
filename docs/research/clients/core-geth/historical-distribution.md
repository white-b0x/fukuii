# core-geth — historical-distribution
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

> **Read `../go-ethereum/historical-distribution.md` first.** core-geth is a go-ethereum
> fork frozen 2025-01-23; its freezer/ancient store is **inherited byte-for-byte** and its
> era1 support is a **frozen 2023-vintage snapshot** of geth's original era1 (before geth's
> later restructure and EIP-4444 history-expiry work). This file documents only the diffs.

## Architecture summary

**Freezer / ancient store: inherited, unchanged.** core-geth carries geth's two-store split
verbatim — a hot KV store (LevelDB/Pebble) for the reorg-able head, and a cold append-only
freezer (`core/rawdb/freezer.go`) for provably-immutable finalized blocks, with a background
thread migrating blocks across the boundary once they are `params.FullImmutabilityThreshold`
deep. All the constants match geth (`freezerTableSize = 2 GB`, `freezerBatchLimit = 30000`,
`freezerRecheckInterval = 1 min`). Nothing ETC-specific about the mechanism — it just stores
ETC blocks instead of ETH blocks.

**era1: present, but the older flat 2023 layout.** core-geth has era1 export/import at
`internal/era/` — but the *original* single-package form (`era.go`, `builder.go`,
`accumulator.go`, `iterator.go`, `e2store/`), **not** geth's later restructure into
`internal/era/onedb/`, `internal/era/execdb/`, `internal/era/eradl/`, and
`core/rawdb/eradb/`. Consequently core-geth has **none** of geth's downstream history
machinery: no EIP-4444 history-expiry (`core/history/historymode.go`, `prune-history`,
`--history.chain`), no HTTP era1 downloader with embedded checksum DB (`eradl`), no
read-only era backing store for pruned ranges (`eradb`), and no post-merge execdb era
variant. era1 here is strictly the pre-merge, accumulator-per-epoch archival format with
`export-history` / `import-history` commands and nothing more.

## Key types / interfaces / files

### Freezer / ancient store (inherited from geth — see geth doc for detail)
- `core/rawdb/freezer.go:69` — `Freezer` struct; identical semantics to geth (mmap
  append-only, `FLOCK` lock distinct from LevelDB `LOCK`, `errSymlinkDatadir` rejection).
- `core/rawdb/chain_freezer.go:46,65` — `threshold` seeded from `vars.FullImmutabilityThreshold`
  (core-geth's params package is `params/vars`, a fork-rename — the only cosmetic diff).
- `core/rawdb/freezer_table.go` — 6-byte fixed-width index + snappy data file per table; O(1)
  number-addressed reads. Unchanged.
- `core/rawdb/ancient_scheme.go:23-46` — **the notable diff.** core-geth's chain freezer has
  **5 tables**: `headers`, `hashes`, `bodies`, `receipts`, **`diffs` (total difficulty)**.
  Current geth *removed* the TD/`diffs` table post-merge and added `bals` (EIP-7928) plus
  tail-group metadata for EIP-4444. core-geth **retains `diffs`** because ETC is PoW and total
  difficulty is consensus-live forever (MESS/reorg scoring, fork choice).
- `core/rawdb/ancient_scheme.go:71-76` — only **two** freezers: `chain` and `state`. Current
  geth also has `trienode` (and verkle) freezers; core-geth's 2025-01 vintage predates those.
- `chainFreezerNoSnappy` (`ancient_scheme.go:41-47`) — `hashes` and `diffs` stored
  uncompressed; headers/bodies/receipts snappy-compressed (same policy as geth for the tables
  they share).

### era1 (frozen 2023 form)
- `internal/era/era.go:37-46` — TLV type constants (`TypeVersion=0x3265`,
  `TypeCompressedHeader/Body/Receipts`, `TypeTotalDifficulty`, `TypeAccumulator`,
  `TypeBlockIndex=0x3266`) and `MaxEra1Size = 8192`. Byte-identical to geth's original era1.
- `internal/era/era.go:52` — `Filename(network, epoch, root) = "<network>-<epoch:05d>-<hexroot[2:10]>.era1"`
  — accumulator root embedded in the filename → self-verifying, same as geth.
- `internal/era/builder.go` — `Builder` producing `era1 := Version | block-tuple* |
  other-entries* | Accumulator | BlockIndex`, block-tuple `:= CompressedHeader |
  CompressedBody | CompressedReceipts | TotalDifficulty`. e2store container borrowed from
  Nimbus (documented inline).
- `internal/era/accumulator.go` — SSZ `hash_tree_root` over `(hash, TD)` records (Portal
  Network historical-hashes accumulator).
- `cmd/geth/chaincmd.go:129-152` — `import-history` / `export-history` command definitions
  only. **No `prune-history`.**
- `cmd/utils/cmd.go:246` `ImportHistory`, `:406` `ExportHistory` — the import/export drivers;
  export steps in `era.MaxEra1Size` (8192) block epochs.

## Design decisions & rationale

- **Retaining the `diffs` (TD) freezer table is the one deliberate ETC divergence.** Post-merge
  ETH deleted total-difficulty storage; ETC never merged, so TD remains a first-class,
  permanently-stored quantity. core-geth keeps the TD freezer table where upstream geth later
  dropped it. Any consumer replaying core-geth's freezer must expect a TD column.
- **Freezer inherited wholesale** because immutability-by-depth is chain-agnostic; ETC's
  `FullImmutabilityThreshold` is the same reorg-depth policy, so no fork was needed.
- **era1 was taken at its 2023 shape and frozen.** core-geth adopted geth's original era1 for
  archival ETC history distribution but did not track geth's subsequent EIP-4444 / history-expiry
  program — sensible, since ETC has no merge boundary and (as of this vintage) no history-expiry
  mandate.

## Notable patterns (the reusable idea)

Same transferable core as geth (hot-KV / cold-immutable split + O(1) number-addressed flat
freezer + self-verifying per-epoch era1 file). The core-geth-specific lesson for fukuii:
**a PoW chain must keep total difficulty in the cold store** — the ETH freezer's post-merge
table set is the wrong template for an ETC successor; core-geth's 5-table (`diffs`-retaining)
chain freezer is the right reference.

## Authority note

core-geth is fukuii's **PoW/ETC byte-authority** and is the reference for what an *ETC*
freezer looks like — specifically the retained TD (`diffs`) table and the two-freezer
(`chain`+`state`) layout at this vintage. For freezer *mechanism* detail and for the full
modern era1 / EIP-4444 story, go-ethereum is the authority (core-geth is a strict, older
subset). era1 semantics here are identical to geth's original 2023 era1; the checksum-verified
HTTP distribution (`eradl`) and prune-and-restore workflow are geth-only and post-date this
freeze.

## Gotchas / anti-patterns / things they later changed

- **No history expiry at all.** There is no `prune-history`, no `--history.chain`, no
  `core/history/historymode.go`, no `eradb` fall-through. A fresh reader expecting geth's
  EIP-4444 surface will not find it — core-geth stores full history, period.
- **era1 is import/export only.** No downloader (`eradl` absent), no embedded ETC checksum
  database — an out-of-band era1 bootstrap for ETC would need its checksums/distribution built
  from scratch; core-geth ships only the file format and the local `import-history`/`export-history`
  commands.
- **`params` rename footgun.** core-geth's fork renamed geth's `params` constants into
  `params/vars` (`vars.FullImmutabilityThreshold`), so cross-referencing line-for-line against
  geth requires translating the package path.
- **The `diffs` table is a compatibility fork-point.** Because upstream geth removed it, a
  freezer produced by core-geth is *not* schema-compatible with a modern geth freezer and vice
  versa — the table set diverged at the merge.

---

### Alignment with fukuii

fukuii's `fukuii-checkpoint-service` skill (point a fresh ETC/Mordor node at a trusted
checkpoint archive to skip P2P history acquisition) occupies the same out-of-band-bootstrap
niche as era1. core-geth confirms the correct ETC shape: a freezer that **retains total
difficulty**, and — if fukuii wants a verifiable historical-segment format — the *original*
era1 (accumulator-committed, checksum-in-filename) is the minimal, ETC-appropriate target,
without geth's later merge-specific execdb/EIP-4444 complexity that ETC does not need.
