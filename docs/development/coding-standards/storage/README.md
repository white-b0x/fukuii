# Storage domain — scope stub

**Scope:** RocksDB / storage-layer coding standards for `db/` — the `DataSource` contract,
column families, iterator lifecycle, `WriteBatch` ordering, WAL, `EphemDataSource`
(in-memory test DB), and LRU/block-cache config.

**Owning specialist:** `vault`.

**Authority:** `.claude/repo-references/rocksdb/` (RocksDB wiki + options reference).

**Status:** no content migrated yet. The storage rules currently live in
`.agents/protocols/storage/storage-rocksdb.md` pending the per-domain migration pass
described in `../README.md`. Link to the protocol file in the meantime; do not restate its
content here ahead of that pass.
