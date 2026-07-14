# besu — historical-distribution
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

## Architecture summary

Besu's history-distribution story has **three independent mechanisms**, not geth's
single freezer/era1 pairing:

1. **Genesis checkpoint anchor** — the fast-bootstrap path. A trusted
   `(blockNumber, blockHash, totalDifficulty)` triple baked into the chain-spec
   seeds `SyncState` so a fresh node starts *from the checkpoint block instead of
   genesis*. This is besu's canonical "distribute history cheaply" story: don't
   ship the history, ship a trusted pointer past it and let SNAP fill state.
   (Full sync-decomposition covered in `sync.md` §"Genesis checkpoint anchor" —
   not duplicated here.)

2. **ERA1 files — import AND export.** Contrary to the "besu likely has no era1"
   prior: **besu has full ERA1 support in both directions.** A reusable ERA1
   reader/writer library (`util/era1`), an ERA1 *import prepipeline* that pre-loads
   history from local or HTTP ERA1 files ahead of full sync, and ERA1 as a
   first-class format in the `blocks import` / `blocks export` subcommands.

3. **`blocks import` / `blocks export` subcommands** — the archival/backup path.
   RLP, JSON (import only), and ERA1 formats for offline dump/restore of block
   history, independent of live sync.

Plus a **history-expiry** subcommand (`storage prune-pre-merge-blocks`) that
deletes pre-merge block bodies and receipts, keeping only headers + genesis.

So: **checkpoint anchor = light/fast-bootstrap; ERA1 import prepipeline =
archival fast-bootstrap from files; blocks export/import = offline
archival/backup; prune-pre-merge = history expiry.**

## Key types / interfaces / files

**Genesis checkpoint anchor (fast-bootstrap distribution)**
- `ethereum/eth/.../sync/common/checkpoint/Checkpoint.java:22-30` — the immutable
  `@Value.Immutable` value interface: `blockNumber()`, `blockHash()`,
  `totalDifficulty()`. Built as `ImmutableCheckpoint`. This is the entire
  "distributed history anchor" payload.
- `config/.../CheckpointConfigOptions.java` — parses the checkpoint block from the
  genesis/chain-spec JSON (`genesisConfigOptions.getCheckpointOptions().isValid()`).
- `ethereum/eth/.../sync/state/SyncState.java` — stores `Optional<Checkpoint>`;
  downstream sync bounds how far back to fetch via `getCheckpoint()`.
- (See `sync.md` for `PivotBlockSelector`, the SNAP coupling, and the
  now-deprecated-no-op `--Xcheckpoint-post-merge-enabled` — checkpoint was demoted
  from a *sync mode* to a genesis-embedded *data anchor* consumed by SNAP.)

**ERA1 reader/writer library (shared)**
- `util/.../era1/Era1Reader.java:32-54` — streaming ERA1 file reader; drives an
  `Era1ReaderListener` per section. Header field widths declared as constants
  (`TYPE_LENGTH=2`, `LENGTH_LENGTH=6`, block-index lengths). Snappy-framed
  decompression via `SnappyFactory` / `org.xerial.snappy.SnappyFramedInputStream`.
- `util/.../era1/Era1Type.java:19-36` — the on-disk section-type enum with e2store
  type codes: `COMPRESSED_EXECUTION_BLOCK_HEADER (0x03)`,
  `…_BODY (0x04)`, `…_RECEIPTS (0x05)`, `TOTAL_DIFFICULTY (0x06)`,
  `ACCUMULATOR (0x07)`, `VERSION (0x6532 = "e2")`, `BLOCK_INDEX (0x6632)`.
- `util/.../era1/{Era1BlockIndex,Era1ExecutionBlockHeader,Era1ExecutionBlockBody,
  Era1ExecutionBlockReceipts,Era1TotalDifficulty,Era1Accumulator}.java` — the
  parsed section value types.

**ERA1 import prepipeline (archival fast-bootstrap during FULL sync)**
- `ethereum/eth/.../sync/fullsync/era1prepipeline/Era1ImportPrepipelineFactory.java:39-72`
  — `implements FileImportPipelineFactory`; builds a Pekko-free
  `services.pipeline.Pipeline<URI>` that reads ERA1 files and feeds
  `FullImportBlockStep`. Normalizes a scheme-less `era1DataUri` to `file://`.
- `.../era1prepipeline/Era1FileSource.java` / `Era1HttpFileSource.java:28-67` —
  the `Iterator<URI>` sources: local directory or HTTP (`java.net.http.HttpClient`,
  directory listing → per-file-number URI map). This is the "history over HTTP"
  distribution channel.
- `.../era1prepipeline/Era1FileReader.java` — bridges `Era1Reader` into the pipeline.
- Wired via `app/.../cli/options/SynchronizerOptions.java:108-111` flags:
  `--era1-import-prepipeline-enabled`, `--era1-data-uri`,
  `--era1-import-prepipeline-concurrency` (only active for mode `FULL`).

**`blocks` subcommands (offline archival/backup)**
- `app/.../cli/subcommands/blocks/BlocksSubCommand.java:87` — `COMMAND_NAME = "blocks"`
  with `ImportSubCommand` (`:146`) and `ExportSubCommand`. Holds suppliers/factories
  for RLP, JSON, and ERA1 importers/exporters (`:97-102`).
- `blocks/BlockImportFormat.java:18-24` — `enum { RLP, JSON, ERA1 }`.
- `blocks/BlockExportFormat.java:18-22` — `enum { RLP, ERA1 }` (no JSON export).
- `app/.../chainexport/{Era1BlockExporter,RlpBlockExporter}.java`,
  `app/.../chainexport/Era1{Accumulator,AccumulatorFactory,BlockIndexConverter,
  FileWriter,FileWriterFactory}.java` — the export/writer side.
- `app/.../chainimport/{Era1BlockImporter,RlpBlockImporter,JsonBlockImporter}.java`
  — the import side.

**History expiry**
- `app/.../cli/subcommands/storage/PrunePreMergeBlockDataSubCommand.java:34-69` —
  `prune-pre-merge-blocks` (alias `x-prune-pre-merge-blocks`): "Prunes all
  pre-merge blocks and associated transaction receipts, leaving only headers and
  genesis block", range-batched (`--prune-range-size`, default 10000).

## Design decisions & rationale

- **Ship a pointer, not the history (checkpoint anchor).** The primary
  distribution mechanism is a 3-field value in the chain-spec, not a file format.
  A checkpoint is *data about where to start*, so it lives in config and is
  consumed by whatever sync mode runs (SNAP today) — no separate CHECKPOINT
  algorithm. This is deliberately minimal versus geth's era1 archive shipping.
- **ERA1 for archival bootstrap, layered on FULL sync only.** Archival/full-verifying
  nodes that *must* execute every block don't have to re-download history from
  peers — they import it from ERA1 files (local dir or HTTP) as a front-end to full
  sync, then continue live. History distribution is decoupled from the P2P layer.
- **Reusable ERA1 codec in `util`, not buried in sync.** `Era1Reader`/`Era1Type`
  live in the shared `util` module, so the same reader backs both the sync
  prepipeline and the `blocks import --format=ERA1` subcommand — one codec, two
  consumers (streaming-into-sync vs. offline-import).
- **Three format enum for blocks import (RLP/JSON/ERA1), two for export (RLP/ERA1).**
  JSON import exists for human-authored test/dev chains (timestamped synthetic
  blocks); it has no export counterpart because it isn't an archival format.
- **History-expiry as an explicit operator subcommand, not automatic.** Pruning
  pre-merge bodies/receipts is opt-in and range-batched, keeping headers + genesis
  so the chain remains header-verifiable after expiry.

## Notable patterns (the reusable idea)

- **Checkpoint-as-chain-spec-data.** A trusted `(number, hash, totalDifficulty)`
  triple embedded in the genesis config, parsed by `CheckpointConfigOptions`, and
  fed to `SyncState` as an `Optional<Checkpoint>` that *bounds* how far back sync
  fetches. The distribution artifact is tiny, versionable with the chain-spec, and
  orthogonal to the sync algorithm. **This is the single most transferable idea for
  fukuii's checkpoint-sync** — it matches fukuii's checkpoint-service/checkpoint-sync
  skill almost exactly: a trusted pivot anchor imported at bootstrap so the node
  skips consensus-negotiated pivot selection.
- **File-source abstraction for history (`Iterator<URI>`).** `Era1FileSource` vs.
  `Era1HttpFileSource` behind one iterator interface means "history over local
  disk" and "history over HTTP" are the same pipeline with a swapped source — the
  same shape fukuii's checkpoint-service uses (local file **or** HTTP URL archive).
- **One codec, two consumers.** Shared `util/era1` reader used by both live-sync
  prepipeline and the offline `blocks` subcommand.

## Authority note

geth is the freezer/era1 authority (physically-separate cold flat-file store +
era1 archive files as the canonical history-distribution format). **besu is the
checkpoint-anchor-bootstrap variant** — its *primary* history-distribution story
is the genesis-embedded checkpoint triple (ship a trusted pointer, let SNAP fill
state), which is the closest reference match to fukuii's checkpoint-sync /
checkpoint-service. Besu *also* implements ERA1 (import prepipeline + blocks
export/import), so it is not an either/or — but for the "how do you bootstrap a
node without shipping the whole chain" question, besu's answer is checkpoint,
where geth's is era1. Storage-format/`DATABASE_METADATA.json` schema versioning is
covered in `storage-persistence.md` (besu uses per-column-family BlobDB, **not** a
geth-style separate freezer store).

## Gotchas / anti-patterns / things they later changed

- **CHECKPOINT sync mode was removed and folded into SNAP.**
  `--Xcheckpoint-post-merge-enabled` is a **deprecated no-op** that only logs a
  warning (`SynchronizerOptions.java:380-393`); requesting checkpoint behaviour is
  coerced to SNAP ("Using SNAP sync mode instead. Your checkpoint configuration
  will be used automatically.", `BesuCommand.java:444`). The checkpoint *data*
  still drives bootstrap; only the *mode* is gone. Anyone grepping for a
  `SyncMode.CHECKPOINT` enum value will not find one.
- **ERA1 import is FULL-sync-only.** `--era1-import-prepipeline-enabled` +
  `--era1-data-uri` are ignored unless `--sync-mode=FULL`. It's not a SNAP
  accelerator; it's an archival-node history preload.
- **`Era1HttpFileSource` opens a fresh `HttpClient` per fetch** (`:66`,
  `try (HttpClient …)`) — fine for a bounded one-shot bootstrap, but not a
  connection-pooled long-lived channel; don't generalize it into a hot path.
- **JSON block format is import-only** (`BlockExportFormat` has no `JSON`) — a
  round-trip export/import assumption using JSON will fail on the export side.
- **`prune-pre-merge-blocks` is destructive and ETH/PoS-oriented** ("pre-merge"
  bodies/receipts). It is not a general history-expiry knob for a PoW/ETC chain
  that never merged — irrelevant to fukuii's ETC path, relevant only conceptually.
