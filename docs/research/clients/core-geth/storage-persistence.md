# core-geth — storage-persistence

_Commit/branch documented: `b28aa0a0bbb1e3ba72ce11afb9310d9dc38c1832` (branch `main`, version
`params.Version = 1.13.0`). Vendored at `.claude/repo-references/clients/core-geth`. Documented 2026-07-13._

_**Re-verified against `upstream` 2026-07-13** (SHA `4185df450`, deprecated Sept-2024 branch):
**upstream-safe.** fukuii's `main` overlay (+65 commits) is consensus/docs-focused and touches no
storage schema — `git diff upstream main -- core/rawdb/ ethdb/ triedb/` shows only a single test-file
change. Every storage claim here reflects genuine upstream core-geth. The one edit made on this pass:
trimmed the authority-note parenthetical below, which had over-included the Olympia ECIPs (1111/1122)
in core-geth's consensus authority — those are fukuii's `main` overlay, not upstream, and storage
authority does not extend to them regardless._

> **Read this as a diff.** core-geth is a go-ethereum fork; its storage layer is inherited essentially
> unchanged. The baseline is the sibling doc `docs/research/clients/go-ethereum/storage-persistence.md`
> (geth `v1.17.4-32-g59e89e81e`). This file only expands where core-geth diverges; everywhere else it says
> "identical, see geth doc." **Headline finding: there is NO ETC-specific storage-schema divergence** — the
> KV backend, key prefixes, freezer, and triedb node store are byte-for-byte the geth design. The only
> genuinely core-geth-specific storage-adjacent difference is at the *chain-config accessor type* layer
> (multi-geth's pluggable `ChainConfigurator`), not the schema. All other differences are **version lag**
> (core-geth 1.13.0 predates geth ~1.14–1.17 storage features).

## Architecture summary

**Identical to go-ethereum** (see geth doc "Architecture summary"). Same three tiers, same package
boundaries, same file names:

1. **KV abstraction (`ethdb/`)** — narrow `KeyValueStore` interface; backends `leveldb`, `pebble`
   (default for a new datadir), `memorydb`, `remotedb`. No column families — key-prefix namespacing.
2. **Ancient store / freezer (`core/rawdb`, freezer\*)** — append-only, Snappy-compressed, number-indexed
   flat-file cold store fronting the mutable LSM, unified behind one `Database` handle.
3. **Schema + accessors (`core/rawdb`)** — `schema.go` (single source of key layouts) + typed
   `accessors_*.go` helpers.

State sits on the KV tier via **`triedb/`** with the same two mutually-exclusive node-storage schemes,
legacy **hashdb** (hash-keyed, ref-counted) and **pathdb** (path-keyed, layered diff tree, state-history
freezers). pathdb **is present** in this snapshot. The one architectural nuance below the schema level is
that `triedb`'s **default** is hashdb here, whereas current geth defaults an empty datadir to pathdb (see
Divergences §5).

## Key types / interfaces / files

All the load-bearing abstractions are the geth ones, at the same paths (`ethdb/database.go`
`KeyValueStore`/`Database`/`AncientStore`; `ethdb/batch.go` `Batch` with `IdealBatchSize = 100 KiB`,
`ethdb/batch.go:21`; `ethdb/iterator.go` `Iterator`; `core/rawdb/schema.go` prefixes;
`core/rawdb/freezer.go` `Freezer`, `freezerTableSize = 2 GB` at `freezer.go:55`; `triedb/database.go`
`Database`/`backend`). Cross-reference the geth doc "Key types / interfaces / files" — the same citations
hold, only the version differs. The points below are where core-geth's copy is *not* the same as the geth
HEAD the sibling doc describes.

## Divergences from go-ethereum (the whole point of this doc)

### 1. `diffs` (total-difficulty) freezer table retained; no `bals` table
`core/rawdb/ancient_scheme.go:22-47` defines the chain-freezer table set as **`headers`, `hashes`,
`bodies`, `receipts`, `diffs`** — `ChainFreezerDifficultyTable = "diffs"` (`ancient_scheme.go:36`), Snappy
disabled for it and `hashes` (`ancient_scheme.go:42-47`). The geth doc's HEAD has **dropped `diffs`**
(post-merge, TD is frozen/irrelevant) and **added `bals`** (block access lists). core-geth keeps `diffs`
because **PoW/ETC still needs per-block total difficulty for fork choice** — this is the single freezer-
schema element that is genuinely relevant to the ETC family, though it is really "pre-merge geth" rather
than an ETC-invented table. core-geth has **no `bals` table** at this snapshot.

### 2. No tail groups (coordinated freezer pruning)
`ancient_scheme.go` here is a flat `chainFreezerNoSnappy map[string]bool` (`ancient_scheme.go:40-47`) with
**no per-table tail-group config**. The geth doc's "tail groups" (bodies+receipts prune in lockstep,
headers/hashes non-prunable, declared in table config) are a newer geth feature absent here.

### 3. No `DeleteRange` on the KV interface
`ethdb/database.go`'s `KeyValueStore` (line 58ff) has **no `KeyValueRangeDeleter` / `DeleteRange(start,end)`**
member — geth added that (with `ErrTooManyKeys` partial-delete semantics) after this snapshot. Prefix-range
pruning in core-geth is done the older, iterate-and-delete way.

### 4. Chain-config accessor returns multi-geth's `ChainConfigurator` interface (core-geth-specific, NOT version lag)
This is the **only difference that is core-geth's own design rather than a geth version gap**, and it is at
the *accessor type* layer — **the on-disk layout is identical**. `core/rawdb/accessors_metadata.go:57-85`:
`ReadChainConfig` returns `ctypes.ChainConfigurator` (an *interface*) via
`generic.UnmarshalChainConfigurator(data)`, and `WriteChainConfig` `json.Marshal`s the same interface. geth
reads/writes a concrete `*params.ChainConfig`. Both store a JSON blob under the **same key**
(`configPrefix = "ethereum-config-"` + genesis hash; `schema.go:121`, `configKey` `schema.go:257-260`) and
the same genesis-state key (`genesisPrefix = "ethereum-genesis-"`, `schema.go:122`). So multi-geth's
pluggable chain-config system (the reason `ReadChainConfig` is polymorphic) changes *what type the bytes
deserialize into*, not the schema — a config-system difference, not a storage-schema one. An ETC datadir and
an ETH datadir are indistinguishable at the rawdb key layer.

### 5. Default state scheme is hashdb, not pathdb
`triedb/database.go:43-45` `HashDefaults` and the nil-config fallback `config = HashDefaults`
(`triedb/database.go:96`); `core/rawdb/accessors_trie.go:287-311` `ReadStateScheme` falls back to
`HashScheme`. Current geth flips an **empty** datadir to **path** by default (geth doc §"Two state schemes,
one default flip"). core-geth 1.13.0 predates the flip — pathdb exists and is selectable, but hash is the
default. Same fatal `log.Crit("Both 'hash' and 'path' mode are configured")` both-scheme guard
(`triedb/database.go:107-108`).

### 6. Retains LES / light-client tables geth later removed
`schema.go` still carries `ChtPrefix`/`ChtTablePrefix`/`ChtIndexTablePrefix`, `BloomTriePrefix` family,
`skeletonHeaderPrefix = "S"`, and the sync-committee prefixes (`BestUpdateKey`, `FixedCommitteeRootKey`,
`SyncCommitteeKey`) — light-client/LES-era key space that newer geth has pruned. Version lag, not
ETC-specific.

### 7. Overall version lag
core-geth `1.13.0` vs geth `1.17.4` (~4 minor versions). Everything in §1–3, §5–6 is a consequence: the
storage layer is a **snapshot of ~geth v1.13-era rawdb/ethdb/triedb** (triedb package split and pathdb
already landed; bals table, tail groups, `DeleteRange`, and the path-default flip had not).

## What is IDENTICAL (cite the geth doc)

- **KV backends & interface-first design** — `ethdb/{leveldb,pebble,memorydb,remotedb}`, `KeyValueStore`,
  pebble default for new DB. Identical; see geth doc.
- **Key-prefix namespacing, single-byte prefixes, no column families** — same prefixes (`h`/`b`/`r`/`H`/`c`,
  snapshot `a`/`o`, path-scheme trie nodes `A`/`O`, `L` state-id), same "avoid mixing data types" rationale
  (`schema.go`). Identical.
- **Freezer** — append-only flat files, `freezerTableSize = 2 GB`, Snappy, `flock` single-open. Identical
  design; only the *table set* differs (§1).
- **triedb hashdb + pathdb**, `Scheme()`, both-scheme `log.Crit` guard, state-history freezers
  (`ancient_scheme.go:48-80` `stateHistory*`, `NewStateFreezer`). Identical.
- **Iterator lifecycle** — the `Release()`-mandated contract, idempotent guarded release, `defer
  Release()`-at-creation idiom, live-iterator metric — **inherited from go-ethereum unchanged**. See the
  geth doc "Iterator lifecycle" section in full; nothing core-geth-specific here.
- **Constants** — `IdealBatchSize = 100 KiB`, `freezerTableSize = 2 GB`. Identical.

## Design decisions & rationale

Inherited from go-ethereum — see the sibling doc "Design decisions & rationale" (interface-first backend-
agnostic KV, key prefixes over column families, freezer for immutable data, two state schemes, layered
pathdb writes). The **one core-geth-authored decision** in this subsystem is making the chain-config
accessor polymorphic over `ctypes.ChainConfigurator` (§4) so a single binary can serve ETC, ETH, and other
multi-geth-configured networks from the same rawdb layout — a *config-system* choice that deliberately
leaves the storage schema untouched. The **kept-`diffs`-table** decision (§1) is the storage-visible
consequence of core-geth remaining a PoW client (TD-based fork choice), where geth HEAD is post-merge.

## Notable patterns (the reusable idea)

- **"Fork the config system, not the storage schema."** core-geth adds a whole pluggable multi-network
  chain-config abstraction yet keeps the rawdb/ethdb/triedb layout byte-identical to upstream — the ETC/ETH
  distinction lives entirely above the KV layer. The reusable lesson for a multi-network client (fukuii is
  exactly this) is that **network identity belongs in the config/type layer, not in storage keys**: the same
  datadir schema can serve every family.
- Everything else worth naming (freezer hot/cold split, schema-as-single-file, bounded-disk layered pathdb,
  Release-mandated iterators) is geth's — named in the sibling doc.

## Authority note

For **storage-persistence, go-ethereum remains the authority; core-geth inherits it** and is *not* an
independent storage authority — its rawdb/ethdb/triedb is a lagging copy of geth's. The value of this doc is
the confirmation itself: **fukuii may use the go-ethereum storage doc as the reference for the ETC family
too**, because ETC introduces no storage-schema divergence. Where core-geth *is* the ETC authority
(consensus: ETChash, ECIP-1017/1099/1100 in `upstream` — the Olympia ECIPs 1111/1122 are fukuii's own
`main` overlay, not upstream core-geth) that authority does **not** extend to storage. **erigon**
remains the alternative authority for a deliberately different storage architecture (MDBX + real tables +
flat "Domains" + staged sync) — see the geth doc "Where erigon diverges." fukuii's RocksDB-via-`DataSource`
(column-family) model sits on the Besu/Nethermind side of the axis, closer to neither geth's single-keyspace
prefixes nor erigon's MDBX — a Phase 2/3 forward-reference, not a verdict here.

## Gotchas / anti-patterns / things they later changed

- **Do not assume geth-HEAD storage features exist in core-geth.** No `DeleteRange`, no `bals` freezer table,
  no tail groups, default state scheme is hash not path — all §1–§6 above. Reading upstream geth docs for
  storage will over-state what this snapshot has.
- **Iterators still leak silently if not `Release()`d** — the geth mandate applies unchanged; same class of
  bug fukuii fixed on its side (see geth doc "Gotchas").
- **`diffs` freezer table is load-bearing for PoW**, unlike current geth where TD is post-merge cruft —
  don't "modernize" it away by copying geth HEAD; ETC fork choice depends on total difficulty.
- **Chain-config bytes deserialize into an *interface*** (`ChainConfigurator`), so a corrupt/foreign config
  blob fails at `generic.UnmarshalChainConfigurator` (logged, returns `nil`) rather than into a fixed struct
  — a core-geth-specific failure surface at `accessors_metadata.go:63-69`.
