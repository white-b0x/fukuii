# L2 — storage + trie: `storage`, `trie`

_Layer L2 (byte-pure persistence + the Merkle Patricia Trie / state-root consensus core). `storage` depends
down-only on `domain`, `common`; `trie` depends down-only on `domain`, `crypto`, `storage`. This is where the
state root gets its bytes and the datadir gets its schema — consensus-load-bearing (state root) and the
persistence seam every layer above (execution, sync, rpc) is written against. Forward-looking plan:
[`plan/L2.md`](../plan/L2.md); per-item byte-cited RX evidence: [`plan/rx/L2.md`](../plan/rx/L2.md); the S0
byte-cited reference map built before implementation: `.local/docs/L2-S0-reference-map.md`. The state-root
surface (node RLP, `< 32` inline, HP nibbles, keccak key, bottom-up root) was matched against **go-ethereum
`trie/` HEAD** and **besu** as the JVM-implementation lens, with **core-geth** as the ETC byte oracle
(confirming ETC introduces zero state-structure divergence) — the four authorities agree byte-for-byte on
every state-root surface, zero divergences. Storage engineering (RocksDB CF/cache, freezer,
scheme-indirection) is a go-ethereum/besu/nethermind/erigon concern ETC and ETH share; core-geth is
authoritative there only for **what an ETC cold store retains** (total difficulty). Built in seven phases
(T1 · S1 · S2 · S3a · S3b + BUG-W7 · T2a · T2b · T2c), each consensus-validated (forge ETC co-sign on the
state-root path) and eye-tested._

## Scope

L2 delivers two sbt modules, one directional edge apart. **`storage` is byte-pure**: it moves
`(Namespace, Array[Byte]) → Array[Byte]` (and `(NodeLocation, hash) → nodeBytes`) with **zero awareness of
what is stored above it** — no `MptNode`, no block/account/receipt domain types. **`trie` owns the entire
node-shape contract** (node RLP encode/decode, the state-root algorithm, child-hash extraction, the TrieLog
leaf-diff) and depends *down* on `storage`. That single directional edge is the structural resolution of the
old codebase's most-cited defect — the `db ↔ mpt` 2-cycle (§1).

What `trie` builds:

- **`enum MptNode`** — the five consensus node shapes (`Leaf`/`Extension`/`Branch`/`Hash`/`Null`), `HexPrefix`
  nibble compaction, the `< 32`-byte inline cap, fail-loud RLP decode; the empty-trie root `56e81f…b421`.
- **`MerklePatriciaTrie[K,V]`** — the immutable functional MPT (Mantis lineage), bottom-up state root,
  `getProof` (EIP-1186 inclusion + non-inclusion), and the `MptStorage`/`NodeLoader`/`NodeUpdater` load/store
  seam over `storage`.
- **`ByteArraySerializable`** + `HashByteArraySerializable` — the keccak-key "secure trie" as **serializer
  composition**, not a wrapper class.
- **`TrieLog`** — the besu-Bonsai `{prior, updated}` **leaf-diff** journal (T2b) + roll-forward/roll-back.

What `storage` builds:

- **`DataSource`** — the `IO`/`fs2.Stream` byte-pure contract; `RocksDbDataSource` (per-instance handle,
  atomic `WriteBatch`, incident-driven hardening) and `EphemDataSource` (test/staging parity).
- **`enum Namespace`** — the self-describing besu-`SegmentIdentifier`-shaped column-family registry
  (28 CFs), including the first-class **`ChainWeight`** TD CF and the SNAP/path/freezer schema reservations.
- **`StorageProfile`** (5 live axes + 1 reserved) + **`SchemaMarker`** (checked-at-open, CF-set-vs-profile)
  + **`INodeStorage`** (hash **and** path node keying behind a scheme-indirection seam).
- **Composable pruning** — `EvictionStrategy` × `PersistenceStrategy` under `PruningMode`, the
  `prune(safeHeight)` R7 barrier, refcount death-row/cascade bookkeeping.
- **`ColdStore`** freezer (TD-retaining) + byte-canonical **era1** E2Store shard files + per-shard
  `ShardAccumulator` + `ShardManifest`; the accumulator-committed `CheckpointArchive`; `FlatStorage`.

**Not redefined here:** `Address`/`Hash`/`UInt256` are L0 `bytes`; `kec256` is L0 `crypto`; the RLP engine is
L0 `rlp` (trie uses `derives RLPCodec`; `storage` has no `rlp`/`crypto` dependency and hand-rolls its byte
codecs). The world-state the EVM runs against (`WorldStateProxy`), the ECIP-1017 reward mutation, and every
network-specific state change live *upstream* at L4/L5 — never in the trie.

## Design decisions & empirical logic

### 1. Byte-pure storage boundary — the db↔mpt 2-cycle dissolved by construction

The single most-cited structural defect of the pre-rebuild tree was the `db ↔ mpt` 2-cycle: `mpt` imported
`db.storage.MptStorage` (9 edges) and `db` imported `mpt` node types (7 edges), so the seam was split across
two mutually-cyclic packages. The rebuild takes the **byte-pure-storage realization** (target-arch OPEN #2's
strictly-cleaner third option): `storage` stores only `(NodeLocation, nodeHash) → nodeBytes` and knows nothing
about `MptNode`; `trie` owns the entire node-shape contract and depends *down* on `storage`.

**Empirical logic:** besu proves `storage` needs no node-type awareness — its `NodeLoader`/`NodeUpdater`
(`Optional<Bytes> getNode(Bytes location, Bytes32 hash)` / `store(location, hash, value)`) and
`StoredNodeFactory` (the RLP decode) both live under `ethereum/trie/`, **not** `plugin-api/storage/`
(S0 reference map). go-ethereum's `triedb` backend likewise stores `[]byte` blobs keyed by hash/path while the
`trie` package owns `node.go` decode. The node contract has exactly **one owner (`trie`)**, so a separate
`storage-api` module is unnecessary — node-aware refcount GC lives in `trie`, which hands `storage` explicit
child-hash lists rather than parsed nodes (`MptNode.childHashes`, `NodeCommit.childHashes`). The realization is
`storage/PersistedMptStorage` (in `trie`) adapting `trie`'s opaque `(Location, NodeHash)` to `storage`'s
`(NodeLocation, IndexedSeq[Byte])`. **An upward edge is a compile error, not a lint finding** — the cycle
cannot re-form. The invariant is a DoD grep gate: `storage` imports nothing from `com.chipprbots.fukuii.trie.*`.
A visible consequence — `EmptyNode.hash` in `storage` is a **duplicated consensus-fixed literal**
(`56e81f…b421`), not `crypto.kec256(0x80)`, precisely because `storage` has no `crypto` dependency
(`storage → domain, common`); duplicating a frozen constant is cheaper than an up-the-DAG dependency and the
value cannot drift.

### 2. `enum MptNode` + the state-root core — five shapes, `Null = 0x80`, fail-loud decode (T1, consensus)

`MptNode` is a Scala 3 `enum` over the five consensus node shapes — `Leaf(key, value)`,
`Extension(sharedKey, next)`, `Branch(children[16], terminator)`, `Hash(ref)`, `Null` — replacing the
reference-tree `sealed abstract class MptNode` + subclass hierarchy and its `@unchecked` exhaustivity
suppressions. A total `match` over the enum is compiler-checked, so a future node-shape change *is* caught.

**Empirical logic (all forge co-signed, byte-exact):** go-ethereum `trie/node.go:38-49` has exactly this
4-shape (+ null) model (`fullNode[17]` / `shortNode` / `hashNode` / `valueNode`). RLP encode: a branch is a
17-slot list (nil child → empty string), a leaf/extension a 2-item `[HP(key), val]`; children capped inline
when `< 32` bytes, replaced by a 32-byte keccak ref at `>= 32` (`MptNode.capped`, `MaxEncodedNodeLength = 32`,
`hasher.go` cutoff). Decode dispatches on RLP list arity (`ListSize = 17` branch, `PairSize = 2`
leaf/extension by HP leaf flag) — geth `node.go:161-177` `rlp.CountValues`. Three T1 build-carry decisions:

- **`Null` encodes as the bare RLP empty string `0x80`, never a 1-item list.** The S0 map flagged besu-etc's
  `StoredNodeFactory` decoder tolerance (a single-element list ⇒ NullNode) as a JVM decode *leniency*, not a
  canonical encoding — T1 must never emit a 1-item list. `EmptyRootHash = keccak(0x80) = 56e81f…b421`.
- **The `< 32` inline cap and root force-hash.** `MptNode.hash` returns a `Hash(ref)`'s ref directly; every
  other node is force-hashed regardless of size (the root is always hashed even when its RLP is `< 32` bytes,
  matching geth). This closed a **getRootHash re-hash bug caught at the T1 gate** — an earlier form was
  re-hashing a `Hash` ref instead of returning it; a commit round-trip test now guards it.
- **Fail-loud decode (L2-F3).** `MptNode.decode` raises a typed `MptNodeDecodeException` on any malformed
  input — wrong list arity, an oversized (`>= 32`) embedded child that should have been a hash ref, or a bad
  hex-prefix flag byte (`validateHexPrefixFlag`: high nibble must be in `{0,1,2,3}`, even-length keys need a
  zero pad nibble). The bytes→node seam is fed adversarial peer input (`GetTrieNodes`/SNAP), so it must fail
  loud, never silently mis-decode into a wrong-but-plausible node.

**Keccak-key "secure trie" is L1/`trie`-serializer composition, not a wrapper class.** `HashByteArraySerializable`
wraps a base key encoder so the trie sees `keccak256(base.toBytes(key))` — byte-identical to geth's
`StateTrie`/`secure_trie.go` (hash the key before insertion), but expressed as a key serializer, not a
`SecureTrie` subclass. The DoD `*_secureTrie` fixtures key on raw hex through this wrapper; **don't search
`trie` for a secure-trie type.**

### 3. Root-neutral path threading — the `(owner, path)` `Location` (T2a)

`MerklePatriciaTrie.store` threads the **nibble path from the trie root** through the descent so each node is
stored under its real `Location = (owner, pathFromRoot)` — geth `committer.commit(path, n)`
(`committer.go:51-118`): a node at its own `path`, an extension's child at `path ++ sharedKey`, a branch's
child `i` at `path :+ i`. This is **root-neutral by construction**: node bytes (`MptNode.encoded`), the keccak
hash, and the returned `Hash` reference are untouched; only the `Location` handed to `storage` changes from the
placeholder `Location.Root` to the real path. A hash-keyed store ignores `location` entirely, so the state root
is byte-identical under either keying scheme — verified in code (`Location.Root == Location(None, empty)`,
byte-identical) and by the T2a `PathThreadingSpec` (11 tests via a capturing `MptStorage`) with T1's reference
vectors unchanged.

`owner = None` denotes the state/account trie; `owner = Some(accountHash)` scopes a node to that account's
storage sub-trie. **The per-account `owner` is composed at L4 world-state, not here** — the T2a trie always
threads `owner = None`; L4's `WorldStateProxy` supplies the account hash for storage-subtrie path keys
(deferred, §Deferrals). This is the load-bearing S0 finding (§4 below).

### 4. `INodeStorage` scheme-indirection — hash **and** path node keying, the D1 `(owner, path)` correction (S2)

`INodeStorage` (nethermind `Nethermind.Trie/INodeStorage.cs`) resolves a node's RLP bytes by
`(NodeLocation, nodeHash)` independent of the physical keying scheme. Two single-scheme inhabitants —
`HashKeyedNodeStorage` (besu Forest / archival: physical key is the node hash alone, `location` ignored,
`Namespace.Node`) and `PathKeyedNodeStorage` (besu Bonsai / geth pathdb / nethermind HalfPath) — sit behind
`SchemeIndirectedNodeStorage`, which composes them under a mutable active `Scheme` with a fallback dual-read.
This retires the pre-rebuild `PathNodeStorage` **one-way-locked SNAP island**: path-keying becomes a
first-class general backend, and `Scheme.switch` + the fallback read is both a per-datadir role choice and an
online Hash→HalfPath migration substrate.

**The D1 correction — the path key is account-scoped `(owner, path)`, the built model, not besu's global path.**
The plan (RX-L2-08) offered besu's global `location` *or* nethermind's explicit `(address, path, keccak)`; the
S0 map pinned the decision (forge + beacon co-sign vs source): fukuii's single node store is shared by the
state trie and every per-contract storage trie, so a bare per-subtrie nibble path would **collide
storage-subtrie nodes at the same path across accounts** — a state-root correctness bug, not style
(nethermind `NodeStorage.cs:32-35`). The as-built `NodeLocation(owner: Option[…], path)` folds the account
scope in explicitly: `PathKeyedNodeStorage` splits across two CFs — `StateTriePath` (`owner = None`, the CF
scopes it) vs `StorageTriePath` (`owner = Some(_)`, the owner prefix disambiguates), physical key
`owner ++ path ++ nodeHash`. **D4 hash-tail:** the node hash is folded into the key tail (not discarded) so a
path read can re-verify `keccak(blob) == hash` the way a hash-keyed read is inherently self-verifying, and
distinct nodes never collide on-disk even where `(owner, path)` alone is not yet fully discriminating.

Two further mechanism refinements the code makes deliberately explicit:

- **Directional dual-read gated by `migrationInProgress`, not unconditional.** A read always tries the active
  scheme first; it probes the other scheme **only when `migrationInProgress` is true** (`Path` active →
  `pathKey orElse hashKey`; `Hash` active → `hashKey orElse pathKey`). With migration off, the other scheme is
  never probed — the steady-state common case pays no cost for an indirection it isn't using. This is a fukuii
  choice, geth-aligned (`ReadTrieNode` reads one scheme, no fallback); it is **explicitly not** nethermind's
  unconditional `Get` dual-read, and distinct from nethermind's `RequirePath` (which governs skipping path
  *computation*, a different axis).
- **D3 delete asymmetry** (nethermind `NodeStorage.cs:167-171`, "DO NOT delete hash based key"): `put`/`remove`
  forward to the active scheme's inhabitant **alone**, never both — removing a node under an active `Path`
  scheme deletes only the path-keyed copy, leaving any hash-keyed archival/migration-source copy untouched.

`NodeKeying` carries **three** cases — `Hash`, `Path`, and `Both`. `Both` is not a runtime dispatch scheme; it
names the CF-opening footprint (both scheme-gated CFs open) that `StorageProfile.default` uses to reproduce the
pre-S2 "open everything, no gating" shape byte-for-byte, and is also the footprint an online Hash↔Path
migration profile needs. The `EmptyNode` short-circuit is honored by both inhabitants (read → known encoding,
write → no-op; nethermind `NodeStorage.cs:104-108`, besu `ForestWorldStateKeyValueStorage.java:69`).

### 5. `DataSource` contract + `RocksDbDataSource` — atomic `WriteBatch`, WAL durability, per-instance isolation (S1)

`DataSource` is the single byte-pure KV contract every backend implements identically. It returns
`fs2.Stream[IO, …]` for unbounded scans and synchronous `Option`/`Iterator` for point/bounded access — the
Typelevel effect shape (R5, TL1/TL2), which matches **no** reference client (they are imperative/async); the
plan ports the *intent* (bounded native-iterator lifetime), not any client's API. geth expresses release via
`Release()`, besu via `.onClose`; fs2 bracketing makes release automatic and cancellation-safe.

- **Atomic `WriteBatch` (L2-F4).** `update`/`updateSync` commit the entire `Seq[DataUpdate]` as one atomic
  write regardless of how many `Namespace` values it spans — `RocksDbDataSource` backs it with a single native
  `WriteBatch`; a crash mid-batch leaves *none* of the batch applied. This is the named substrate for
  cross-namespace atomicity (BUG-W7, §6). `EphemDataSource.update` threads a single accumulator through the
  whole batch and assigns `storage` **once** at the end, so a mid-batch throw reveals nothing — a
  **prism-found non-atomicity bug fixed at S1** (`update` had been applying per-`DataUpdate`).
- **WAL durability (L2-F2).** The contract exposes **no unqualified WAL-off bulk-write path**. `update` writes
  WAL-enabled (survives crash); `updateSync` additionally fsyncs (survives power loss) for rare
  durability-critical writes. A bare "disable WAL" knob is out of scope by design: with L7's persisted SNAP
  frontier journal, a lost memtable after a WAL-off write would mark already-lost trie nodes "done" — silent
  state corruption, not a replay-safe no-op (nethermind's `WriteFlags.DisableWAL` is safe there only because it
  re-runs the whole phase). Any future bulk-tuning seam MUST qualify every WAL-off variant.
- **Per-instance isolation (R2).** `dbLock` (a `ReentrantReadWriteLock`) is a **per-instance field**, not a
  process-global — the S1 fix from `july-fourth`'s shared shape. The `DataSource`, RocksDB handle, block cache, and
  `liveIterator` gauge are all per-`ChainInstance`; two instances in one process open distinct datadirs with no
  shared mutable static (a DoD grep gate). `iterate()` had two **prism-found bugs fixed at S1** — the no-arg
  form returned the internal `(namespace.id ++ key)` form instead of the bare key, and leaked the internal
  `SchemaMeta` CF; both now strip the namespace byte and match `iterate(namespace)`.
- **Incident-driven hardening carried forward (DEFAULT, stronger than besu).** The `#1355` batched-iterator
  discipline (`newIterator` only inside a `dbLock.readLock()` + `assureNotClosed()` bracket, drained in bounded
  batches so no native iterator survives a suspension/concurrent `close()`), `deleteRange` native tombstone
  (never a point-delete loop — the cited ~140M-key, ~30-min incident), the global `dbWriteBufferSize`/
  `maxTotalWalSize` memtable ceiling (the ~1.9 GiB SNAP-starvation incident), and the `setStatsDumpPeriodSec(0)`
  JNI-SIGSEGV guard are all preserved. `EphemDataSource` documents its divergence edges (unsigned-byte
  comparator matching RocksDB CF ordering, O(n) scans, no CFs) and is confined to throwaway stateless computes.

### 6. TD/`ChainWeight` first-class + BUG-W7 block-atomicity — the PoW fork-choice backing store

`Namespace.ChainWeight` (CF id `'w'`) is a **first-class, PoW-load-bearing hot CF**, enumerated so the rebuild
cannot silently drop it (the same omission class as the SNAP-progress CFs). It is the O(1)-keyed backing store
the L6 §5 TD-sourcing invariant reads: total difficulty is **computed from PoW-validated headers and compared
against this locally-stored canonical chain-weight**, *never* trusted from the wire (eth/69+ removed TD from the
protocol and it was never the security boundary; a peer's claimed weight is an unverified hint).

**BUG-W7 (required, not optional).** A block's `Header`/`Body` write and its `ChainWeight` write MUST land in
the **same `WriteBatch`** — so a crash between them is structurally impossible. A block visible without its
weight (or a weight with no block) would corrupt the heaviest-chain decision on restart, with no recovery path
to reconstruct a missing TD. This was **elevated from an optional pointer to a required L2 DoD gate** (operator
decision): `RocksDbDataSourceSpec` pins block+chain-weight crash-consistency at the primitive level with a real
close/reopen over the WAL. The cold store retains TD too (§11) — fukuii-specific first-class treatment:
post-merge PoS clients demote TD (the CL owns fork choice), but for a PoW successor it is load-bearing at both
the hot (`ChainWeight`) and cold (`ColdChainWeight`) tiers.

### 7. `enum Namespace` — the self-describing CF registry (besu `SegmentIdentifier` shape)

`Namespace` replaces `july-fourth`'s `type Namespace = IndexedSeq[Byte]` + runtime `Seq[Namespace]` param with a
closed compile-time `enum` whose cases carry six besu-`SegmentIdentifier`-shaped self-describing flags
(`containsStaticData`, `isEligibleToHighSpecFlag`, `isStaticDataGarbageCollectionEnabled`,
`isCacheIndexAndFilterBlocks`, plus `profiles` and the `includeInDatabaseFormat` predicate). This replaces the
`july-fourth` one-shared-`ColumnFamilyOptions`-for-all-CFs: static CFs (`Receipts`/`Header`/`Body`/`Cold*`) declare
BlobDB/GC eligibility a hot CF (`Node`) does not; the hot `Node`/`FlatAccount`/`FlatSlot`/`RefCount` CFs declare
cache priority (besu `KeyValueSegmentIdentifier` `WORLD_STATE`/`ACCOUNT_INFO_STATE`/`TRIE_LOG_STORAGE`).

- **Namespace-ID immutability (Iron Rule).** `id` is a frozen on-disk contract — it becomes the literal RocksDB
  column-family name byte. A construction-time uniqueness `require` catches an accidental collision before it
  reaches production; only ever ADD a case with an unused `id`.
- **Dedicated bookkeeping CFs.** Refcount `RefCount`, `DeathRow`, `RetainedRoot`, `PruneSnapshot`, and `TrieLog`
  are their own CFs, not prefixes within the hot `Node` CF (the `july-fourth` anti-pattern, L2 improvement #15).
- **Profile-membership reservation (L2-F1).** `profiles` declares CF membership for the SNAP crash-resume
  journal (`HealingFrontier`/`BfsQueue`/`SnapSyncProgress`, `Profile.Snap`), the path-keyed trie CFs
  (`StateTriePath`/`StorageTriePath`, `Profile.PathScheme`), and the freezer CFs (`Cold*`, `Profile.Freezer`).
  S1 opens the full CF set unconditionally (`july-fourth` behavior); the field exists so S2's `SchemaMarker` has every
  profile-scoped CF declared. L2 **owns the CF registry**, so it declares the SNAP-progress/frontier CFs that
  L7's heal writes into — reciprocating L7 §6.8.1.

### 8. `StorageProfile` + `SchemaMarker` — 5 live axes + 1 reserved, CF-set-vs-profile gate (S2)

`StorageProfile` is the besu `DataStorageFormat` analogue as a **product** of six axes
`{keying × pruning × flat × freezer × expiry × engine}` resolved once per `ChainInstance` at open, recorded in
the marker, threaded downward. Named role presets (`ArchivalDApp`, `TipServer`, `PrunedRelay`, `ResourceLight`,
`Validator`, `MiningPool`) are sugar; the record is the seam.

**Five live axes + one reserved (the RX-L2-07 sharpening, built as such).** Only `keying` (`NodeKeying`) has
real behavior wired at S2 — the `INodeStorage` seam and `StorageProfile.namespacesFor`'s CF gating; `pruning`
/`flat`/`freezer`/`expiry` are named placeholders whose behavior lands with S3a/S3b/L4. `engine` (`KvEngine`)
is a **reserved single-inhabitant axis** — RocksDB is its only live value (MDBX is OBSOLETE: no mature JVM
binding, LSM-vs-COW mismatch; the OBSOLETE call is *positive evidence* — no JVM client uses MDBX). `keying` and
`engine` are orthogonal and do **not** both encode hash-vs-path, so there is no invalid `keying=Path, engine=…`
combination to guard.

**`SchemaMarker` — two independent checks, not one scalar equality (the highest-value single storage item).**
`SchemaMarker(format, version, profile)` is written once to a dedicated CF (`SchemaMeta`) on a fresh datadir and
checked at open **before any other CF is touched**. Because the resolved profile also **gates which CFs exist**
(besu `KeyValueSegmentIdentifier.includeInDatabaseFormat` — Forest opens `WORLD_STATE`, Bonsai opens
`ACCOUNT_INFO_STATE`/`TRIE_LOG` instead), `ensureCompatible` reconciles **both**: (1) the opened CF set against
`namespacesFor(profile)` — a profile whose CF set doesn't match fails structurally, even before any marker
exists; and (2) the persisted marker against `(format, version, profile)`. A mismatch raises a typed
`SchemaMismatchException`, never RocksDB undefined behavior. This makes namespace-ID immutability a *checked*
precondition. `StorageProfile.default` (`keying = Both`) opens the full CF set, so the default open path is
unchanged byte-for-byte and its marker only ever compares itself to itself.

**Storage codecs are hand-rolled, not `derives Codec`.** The plan sketched `StorageProfile … derives Codec`;
the as-built encodes it as six ordinal bytes (`SchemaMarker.encode`/`decode`), and the pruning/era1/checkpoint
records use small hand-rolled length-prefixed codecs (`PruningCodec`, `Era1Shard.Codec`) — because `storage`
has **no `rlp`/`circe` dependency** (`storage → domain, common` only), and these records never interop outside
the module. (The `trie`-side `TrieLog`/`LeafChange` *do* use `derives RLPCodec` — `trie` has `rlp` on its DAG.)

### 9. Composable pruning + the `prune(safeHeight)` R7 barrier (S3a)

`PruningMode` stays the user-facing named selector (`Archive`/`Basic`/`InMemory`), but each mode is **composed**
of an `EvictionStrategy` ("when to evict RAM") × `PersistenceStrategy` ("where/when to flush disk") —
nethermind's `IPruningStrategy`/`IPersistenceStrategy` split. This kills the `july-fourth` gap of two
independently-maintained refcount impls: `Basic` and `InMemory` share one `RefCountedNodeStore` mechanism,
differing only in bookkeeping backing (`PersistedBookkeeping` over dedicated CFs vs `InMemoryBookkeeping`).

- **`EvictionStrategy` is a two-method split** (nethermind `IPruningStrategy.cs:10-11`): `shouldPruneDirtyNode`
  (file a just-zeroed node onto death row at all?) vs `shouldPrunePersistedNode` (physically remove a death-row
  node?) — evicting a dirty in-RAM node and pruning a persisted disk node are distinct decisions. The
  permissive `always/always` default reproduces the historically-monolithic behavior.
- **The refcount graph.** `commitBlock` increments each new node's children and the block's own root
  (an anchor hold); when a root falls `historyBlocks` deep its hold is released, and a decrement that reaches
  zero files the node on death row and **cascades** through its recorded `childHashes`, so an orphaned subtree
  becomes GC-eligible in one call. `rollback` replays the exact inverse, cascading a **resurrection** on the
  symmetric zero→one transition — so a rolled-back release brings the orphaned subtree back with it, never
  leaving a resurrected parent pointing at still-orphaned children.
- **`prune(safeHeight): IO[PruneReport]` — the R7 barrier** (reth ExEx `FinishedHeight`). Pruning takes an
  external `safeHeight = min(local reorg horizon, min(consumer FinishedHeights))` and **never** removes a node
  whose orphaning block is above it, regardless of local reorg depth. Absent a consumer, `safeHeight` = the
  local reorg horizon (today's additive, zero-regression behavior). The consumer registry is L9's gRPC seam;
  the *hook* is designed into L2 now because it is one-way to retrofit. Undo-log snapshots at or below
  `safeHeight` are discarded alongside the nodes (a rollback beyond the barrier is never valid).

### 10. `TrieLog` `{prior, updated}` leaf-diff + persistence — the clean R7 reorg-event source (T2b/T2c)

`TrieLog` (besu Bonsai `plugin-api/…/trielogs/TrieLog.java`) is a per-block journal of **leaf-value** diffs
(`LeafChange(prior, updated)` = besu `LogTuple`), the clean serializable R7 reorg-event source the pruned/flat
(`Path`) profile builds *beside* a trie transition. `july-fourth`'s reorg substrate was `ReferenceCountNodeStorage`'s
**node-level** death-row snapshots — coupled to the hash-keyed store and un-serializable across a process
boundary; the hash-keyed archival profile keeps those (S3a), the pruned profile gets the TrieLog.

**Empirical logic:** the diff is keyed by the trie's **leaf key**, not a node hash — precisely what makes it a
self-contained, serializable state-change record an out-of-process consumer can `rollForward`/`rollBack`
without sharing the trie (a node-hash diff would require the consumer to hold the trie itself, RX-L2-19). It is
**root-neutral**: `rollForward`/`rollBack` reach a sibling state purely by re-applying leaf `put`/`remove` and
letting the trie recompute the root bottom-up. `changes` is held in canonical key-sorted (unsigned-byte
lexicographic) order so two logs with the same diffs serialize to identical bytes. The `{prior, updated}`
builder lives in `trie` (it parses leaves); `PersistedTrieLogStore` (T2c, `storage`) stores only the
`TrieLog.serialized` bytes in the `TrieLog` CF, never parsing a node — the byte-pure boundary holds
(`prune(belowBlock)` is a single `deleteRange`). Leaf keys map to `Address`/`StorageSlotKey` identities and
code changes are composed at L4; this L2 type is the per-trie leaf primitive, kept free of world-state concepts.

### 11. `ColdStore` freezer + byte-canonical era1 + checkpoint accumulator (S3b)

The `ColdStore` seam (`historical-distribution.md` DEFAULT storage-lifecycle) is a sealed, number-addressed,
append-only store for block ranges below the reorg-safe boundary, realized **in-engine** over dedicated static
CFs (`ColdHeader`/`ColdBody`/`ColdReceipts`/`ColdChainWeight`/`ColdShardMeta`) keyed by fixed-width big-endian
block number — besu's BlobDB-per-static-segment shape, not a second on-disk store (RocksDB is the sole engine).

- **TD retained (the ETC PoW invariant, verified against the living authority).** Every `ColdBlockRecord`
  carries `totalDifficulty` — core-geth `core/rawdb/ancient_scheme.go:35-36` `ChainFreezerDifficultyTable =
  "diffs"`, retained `:46`, appended `chain_freezer.go:293`. The post-merge ETH freezer *drops* TD — the wrong
  template for a PoW successor; fukuii always retains it (harmless for PoS, required for PoW). Bounds
  (`ColdShardMeta`) update in the **same atomic batch** as every cold-record write.
- **Write-time-boundary freeze (reth shape).** `freeze` is called incrementally, at the moment a caller (L4/L5)
  determines a contiguous run has crossed the reorg-safe boundary — never as a later sweep re-reading hot data
  end to end (avoids the geth freeze-pass bug class). It is idempotent per block number (a plain upsert).
- **One fixed-block-range sharded format → freeze + distribute + expire from one design.** `expireShard` is a
  single `deleteRange` per cold CF (EIP-4444 expiry as a whole-file `rm`, mechanism only — no policy).
- **Byte-canonical era1 shard files + per-shard accumulator (the committed L7-distribution seam).** `Era1Shard`
  encodes a full ERA1 8192-block epoch as a byte-canonical E2Store-shaped TLV file (Version + per-block
  Header/Body/Receipts/TotalDifficulty + a trailing `ShardAccumulator` record) so two independent nodes holding
  the same block range produce **byte-identical** output — the property a BitTorrent infohash needs (a RocksDB
  SST's physical layout differs node-to-node even for identical content). Each shard carries its **own
  `ShardAccumulator`** (a `MerkleFold` over `(hash(header), TD)` leaves) so a shard fetched from an untrusted
  peer self-verifies against a known root before trust. `ShardManifest` (block-range → epoch → accumulator-root)
  is the L2 format primitive L7 turns into torrent metainfo / WebSeed URLs.
- **F-S3b-2 epoch-label check (a real attack, forge-found, mandatory).** `importShard` takes a **mandatory
  `expectedEpochIndex`** — the epoch the caller intends the shard to fill. A genuine, self-verifying shard
  (its accumulator commits to `(blockHash, TD)` pairs, which say nothing about *which* epoch slot) could be
  served mislabeled onto the wrong block-number range, corrupting the number→hash index. `importShard` rejects
  a claimed-vs-expected epoch mismatch **before any freeze**, and derives the freeze keys from the caller's
  expected epoch, never the shard's self-declared numbering (defense in depth). It then checks self-consistency
  (recomputed accumulator == embedded root) and an optional `trustedRoot`.
- **`CheckpointArchive` — accumulator-committed state pivot** (upgrading `july-fourth`'s CRC32-only trailer). A pivot
  block, a `CheckpointAccumulator` over the `(blockHash, TD)` chain of trust (go-ethereum `internal/era/
  accumulator.go` shape), and opaque state records; `importInto` verifies against a trusted root **first** and
  only then applies every record as **one atomic `updateSync` batch** — a crash mid-import leaves the datadir
  untouched, a verification failure writes nothing. `encode` is byte-canonical (records sorted by
  `(namespace id, key)`, duplicate-key rejected).
- **`FlatStorage` primitives.** `FlatAccountStorage`/`FlatSlotStorage` deliver the flat CF + the `seekFrom`/
  `seekStorageRange` range-serve primitives (besu Bonsai `BonsaiFlatDbStrategy` `storageToPairStream`, the SNAP
  `GetAccountRange` serving primitive); values are opaque bytes (byte-pure), slot keys are account-scoped
  (`owner ++ slotKey`) for the same non-collision reason as the path node store.

**Both accumulators share one `MerkleFold` with a documented scope note:** the pairwise binary fold carries an
odd trailing node up *unchanged* (not duplicated — a doubled entry must not be indistinguishable from an
odd-length list) and does **no** leaf/internal domain separation. That is safe today because every caller does
whole-list-equality (recompute the full fold, compare roots), never a Merkle *inclusion* proof; a
`// MIGRATION`-style scope note records that domain separation (RFC-6962 / CVE-2012-2459 hardening) MUST be
added before any inclusion-proof consumer.

## Improvements over old fukuii (`fukuii/july-fourth`, v0.8.1-series, `42959353b`)

| Old fukuii (`july-fourth`) | Rebuild L2 | Why it matters |
|---|---|---|
| `db ↔ mpt` 2-cycle (seam split across two mutually-cyclic packages) | `storage` byte-pure; `trie` owns the node contract; `trie → storage` down-only | The cycle cannot re-form — an upward edge is a compile error |
| One general node store, **hash-keyed only**; `PruningMode` a 3-value enum over one CF | `StorageProfile` selector + `INodeStorage` hash **and** path behind the scheme-indirection seam | Multi-approach from line one; retrofitting a 2nd backend later is a rewrite |
| `PathNodeStorage` a one-way-locked SNAP island | path-keying a first-class general backend + online Hash→Path migration substrate | The ref-client standard (geth pathdb-default, nethermind HalfPath-default) |
| No persisted schema-version marker | `SchemaMarker(format, version, profile)` checked at open incl. CF-set-vs-profile | The highest-value single storage item; ID immutability becomes *enforced* |
| Two independent refcount impls (disk vs in-memory) | one composed `EvictionStrategy` × `PersistenceStrategy` under `PruningMode` | Two class hierarchies collapsed into one policy |
| No external prune barrier (keys purely on local reorg depth) | `prune(safeHeight)` takes `min(consumer FinishedHeights)`; hook designed now | R7 is a one-way retrofit |
| Reorg substrate = node-level refcount snapshots (un-serializable) | besu-TrieLog `{prior, updated}` leaf diff on the pruned profile | Node snapshots can't feed an out-of-process consumer; a leaf diff can |
| One shared `ColumnFamilyOptions` for all CFs; GC keys prefixed within `Node` | `enum Namespace` self-describing per-CF config; dedicated bookkeeping CFs | Per-dataset tuning — the whole point of CFs, previously unused |
| No hot/cold split, no era1, no expiry; checkpoint CRC32-only | `ColdStore` freezer (TD retained) + byte-canonical era1 + accumulator-committed checkpoint | Unifies freeze + distribute + expire; content commitment, not just corruption detection |
| `@unchecked` exhaustivity suppressions in insert paths | total `match` over `enum MptNode` | Compiler catches a future node-shape change |
| `BootstrapDownload` (asserts folder `== "leveldb"`, throws before any op) | retired (superseded by the checkpoint archive) | Removes a built-but-broken CLI verb |

## What the build caught

Findings surfaced and resolved *during* the build (each with a fail-then-pass regression test), recorded here
per the finding-resolution discipline:

- **S1 (prism, 3 bugs fixed):** no-arg `iterate()` returned the internal `(namespace.id ++ key)` form instead
  of the bare key; the same path leaked the internal `SchemaMeta` CF; `EphemDataSource.update` was non-atomic
  across `DataUpdate` entries (now single assemble-then-assign-once, matching the `WriteBatch` contract).
- **T1 (forge gate, getRootHash re-hash bug):** an earlier `MptNode.hash` re-hashed a `Hash` ref instead of
  returning it — caught at the state-root gate; a commit round-trip test guards it.
- **S3a (F-3 shared-subtree gap, 4 regression tests):** the composed refcount split had to preserve a subtree
  shared between a released and a retained root — releasing one root must not orphan nodes the other still
  references; each invariant line was mutated to prove the test fails, then reverted to green.
- **S3b (F-S3b-2 epoch misplacement):** `importShard` gained a **mandatory** `expectedEpochIndex` and rejects a
  content-valid but mislabeled shard before any freeze (fail-then-pass verified), plus dup-`(namespace, key)`
  rejection and the `MerkleFold` domain-separation scope note.

## Deferrals / layer boundaries (what lives elsewhere, and why)

_Durable placement decisions — L2 built the seam/primitive/format; the consuming layer supplies the occupancy.
Two deferral classes, both correct (`.local/docs/L2-deferrals-to-upper-layers.md`): **physical-constraint**
(L2 lacks the account model / root-computation context; doing it here breaks the byte-pure boundary) and
**seam-built-occupancy-deferred** (the seam IS built; only the role-gated consumer defers)._

- **→ L4 (execution / world-state).** `WorldStateProxy`/`InMemoryWorldStateProxy` — the copy-on-write
  world-state, the touched-account journal, the bottom-up `persistState`. L4 composes the per-account **owner**
  (account hash) for storage-subtrie path keys (L2 threads `owner = None`), and wires **flat-first account
  reads** (`getAccount` flat-first, MPT-fallback) onto the L2 `FlatAccountStorage` CF + seam. **F-S3b-1**: L2
  accumulators bind header-chain identity + TD only; L4/`trie` must verify body↔header-roots and
  state↔pivot-stateRoot before trusting an untrusted payload (byte-pure `storage` cannot compute those roots).
  Checkpoint Path-import re-keying (Hash direct; Path needs path-assignment by traversal) is likewise L4.
- **→ L4/L5/L6 (execution / consensus).** Typed `putBlock` writes Header/Body/Receipts + `ChainWeight` in one
  BUG-W7 `WriteBatch`. The L6 §5 TD-sourcing invariant computes TD from PoW-validated headers and compares
  against the local canonical `ChainWeight`, never the wire (forge/banksy/herald co-review). ECIP-1017 rewards
  and all network-specific state mutation are applied *upstream* as account mutations, never in the trie.
- **→ L7 (sync).** SNAP heal / range-serve drives the L2 `seekFrom`/`seekStorageRange` primitives (DoS
  budgeting, snap/1↔snap/2 `Syncer`; note range-serve needs `flat = On`); the online full-pruning **mode**
  (copy-live-and-swap, may re-key Hash→Path, **honors `safeHeight`**) plugs into the `PersistenceStrategy` seam;
  the **committed** era1/checkpoint/DB-snapshot **bootstrap distribution** (torrent client, WebSeed/HTTP server,
  resumable download driver) consumes L2's byte-canonical formats + accumulators + manifest + `SchemaMarker` —
  L2 built the byte-canonical seed-able units so L7's transports are additive, never a cold-store rewrite;
  EIP-4444 expiry occupancy; the R7 reorg-event **stream** carrying the TrieLog journal. **T3** (StackTrie /
  ShardEnumerator / HashBuilder streaming state-root substrate) is the one deviation from the plan's literal
  "seam-now" — deferred **entirely** to L7 because it is a standalone leaf component (sorted leaves → root) with
  no upstream L2 interface to pre-establish and no L2 consumer; its DoD when built is the forge-gated
  streaming-root == resident-MPT-root parity.
- **→ L9 (rpc).** The R7 prune-barrier **consumer registry** — the gRPC/dRPC seam supplying
  `min(consumer FinishedHeights)` and carrying the TrieLog reorg stream. L2 owns the `prune(safeHeight)` *hook*
  and the TrieLog *format*.
- **→ L8 (observability).** The per-instance `liveIterator` gauge counters; L2 emits per-instance (R2), the
  registry lives at L8. Keystore / V3 KDFs are an L8 concern, never storage.
- **OPTIONAL register (role-gated, tracked not floor):** Bonsai thin-trie + full TrieLog rollForward/rollBack
  engine; sparse-trie + witness (reth stateless, F11); snap/2 BAL (EIP-7928 — track, do not adopt: ETC
  network-wide BAL retention doesn't hold). MDBX + flat Domains (erigon) and reth's secondary-engine split are
  **OBSOLETE** (named-and-avoided; the `engine` axis does not offer them).

## Verification

`eye` validated the two touched modules at the T2c close: **storage 115 / 115 tests green, trie 57 / 57 tests
green** (`compile-all` + `scalafmtCheck` + scalafix clean; DAG / network-neutrality / per-instance-isolation
grep gates hold). The state-root path is forge co-signed byte-exact against go-ethereum + besu (with core-geth
as the ETC oracle). Load-bearing suites:

- **`TrieReferenceVectorsSpec`** — the empty-trie root `56e81f…b421` as a fixed assertion, plus the applicable
  `ethereum/tests/TrieTests` root fixtures (plain `trietest.json` **and** `trietest_secureTrie.json`) re-hashing
  byte-exact; secure-trie roots exercise the L1/`trie` key-encoder, not a wrapper type.
- **`MptNodeSpec`** — the L2-F3 fail-loud decode surface (wrong arity, oversized embedded child, bad HP flag).
- **`PathThreadingSpec`** (11) — root-neutrality of the `(owner, path)` threading via a capturing `MptStorage`.
- **`StorageProfileSpec` / `SchemaMarkerSpec` / `INodeStorageSpec`** — cross-scheme same-root parity, the
  CF-set-vs-profile gate, and the directional dual-read.
- **`RocksDbDataSourceSpec`** — the **required BUG-W7** block + chain-weight crash-consistency gate (real
  close/reopen over the WAL), the `#1355` iterator-safety shape, cross-namespace isolation.
- **`PruningStoreSpec`** — per-mode state-root preservation, the shared-subtree F-3 regressions, the
  `prune(safeHeight)` barrier (never removes above the height).
- **`Era1ShardSpec` / `ColdStoreSpec` / `CheckpointArchiveSpec` / `TrieLogStoreSpec`** — byte-canonicity across
  independent producers, TD retention through a freeze, the F-S3b-2 epoch-mismatch rejection, accumulator
  tamper-rejection, and TrieLog prune key-ordering.
