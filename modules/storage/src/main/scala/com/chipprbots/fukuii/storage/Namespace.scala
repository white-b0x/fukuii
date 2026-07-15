package com.chipprbots.fukuii.storage

/** Self-describing RocksDB column-family registry.
  *
  * Every [[Namespace]] case IS a column family: `RocksDbDataSource` opens one CF per case (plus RocksDB's own `DEFAULT`
  * CF), keyed by [[id]]. `EphemDataSource` uses the same enum as a logical partition key over a single in-memory map.
  * Modelled on besu's `SegmentIdentifier` (`plugin-api/.../storage/SegmentIdentifier.java`) /
  * `KeyValueSegmentIdentifier` (`ethereum/core/.../storage/keyvalue/KeyValueSegmentIdentifier.java`) — six per-segment
  * properties instead of a bare byte tag, so callers can express storage-engine tuning intent (append-only vs.
  * hot-mutable, GC-eligible, cacheable) at the point a namespace is declared rather than threading ad hoc booleans
  * through `RocksDbConfig`.
  *
  * ==Namespace-ID immutability (Iron Rule)==
  * [[id]] is a frozen on-disk contract: it becomes the literal RocksDB column-family name byte. Renumbering,
  * reordering, or reusing an `id` after removing a case corrupts every on-disk database built against the prior
  * assignment — column families are matched by name/id at open, not by enum ordinal. Only ever ADD a new case with an
  * unused `id`; never edit an existing one. [[Namespace.byId]]'s construction-time uniqueness check exists precisely to
  * catch an accidental collision before it reaches production.
  *
  * ==Profile-membership reservation (L2-F1)==
  * [[profiles]] declares which storage profile(s) a namespace's column family belongs to. S1 does not gate CF open on
  * this field — `RocksDbDataSource` opens the full [[Namespace.values]] set unconditionally, matching the AS-IS
  * behaviour (`Namespaces.nsSeq` was always the complete fixed list). This field exists so S2's
  * `StorageProfile`/`SchemaMarker` (checked-at-open CF-set-vs-profile validation, besu's
  * `includeInDatabaseFormat(DataStorageFormat)` gate) has every profile-scoped CF already declared — S1's job is
  * reserving the schema slot, not building the gating machinery. [[Namespace.Profile.Snap]] marks the SNAP crash-resume
  * frontier journal CFs (`HealingFrontier`, `BfsQueue`, `SnapSyncProgress`); [[Namespace.Profile.PathScheme]] marks the
  * path-keyed trie CFs (`StateTriePath`, `StorageTriePath`) that only apply under `INodeStorage`'s `HalfPath` scheme
  * (S2). Both are schema reservations only — no journaling or scheme-dispatch logic lives here.
  */
enum Namespace(
    val id: Byte,
    val profiles: Set[Namespace.Profile] = Set(Namespace.Profile.Base),
    val containsStaticData: Boolean = false,
    val isEligibleToHighSpecFlag: Boolean = false,
    val isStaticDataGarbageCollectionEnabled: Boolean = false,
    val isCacheIndexAndFilterBlocks: Boolean = false
):

  /** besu `SegmentIdentifier.includeInDatabaseFormat(DataStorageFormat)` equivalent: is this namespace's column family
    * part of the given storage profile? S2's `SchemaMarker` is the actual enforcement point — this is the predicate it
    * will call.
    */
  def includeInDatabaseFormat(profile: Namespace.Profile): Boolean = profiles.contains(profile)

  /** Block receipts (append-only, one write per block, never mutated in place). */
  case Receipts
      extends Namespace(
        'r'.toByte,
        containsStaticData = true,
        isEligibleToHighSpecFlag = true,
        isStaticDataGarbageCollectionEnabled = true
      )

  /** Block headers (append-only blockchain data). */
  case Header
      extends Namespace(
        'h'.toByte,
        containsStaticData = true,
        isEligibleToHighSpecFlag = true,
        isStaticDataGarbageCollectionEnabled = true
      )

  /** Block bodies (append-only blockchain data). */
  case Body
      extends Namespace(
        'b'.toByte,
        containsStaticData = true,
        isEligibleToHighSpecFlag = true,
        isStaticDataGarbageCollectionEnabled = true
      )

  /** MPT trie nodes, hash-keyed (content-addressed, high write/read volume — the hottest CF in the database). */
  case Node extends Namespace('n'.toByte, isEligibleToHighSpecFlag = true, isCacheIndexAndFilterBlocks = true)

  /** EVM contract bytecode, content-addressed by code hash (immutable once written, prunable when unreferenced). */
  case Code extends Namespace('c'.toByte, containsStaticData = true, isStaticDataGarbageCollectionEnabled = true)

  /** Per-block chain weight / total difficulty — the first-class, PoW-load-bearing hot-path backing store for the L6 §5
    * TD-sourcing invariant: total difficulty is COMPUTED from PoW-validated headers and compared against THIS
    * locally-stored canonical chain-weight record, never against a value read off the wire (a peer's claimed TD is an
    * unverified hint, never a source of truth for the heaviest-chain decision). O(1)-keyed by canonical block — the
    * AS-IS `ChainWeightStorage` shape, preserved here.
    *
    * ==Written atomically with its block (BUG-W7)==
    * A block's [[Header]] (and [[Body]]) write and its `ChainWeight` write MUST land in the SAME [[DataSource.update]]
    * batch — never two separate calls — so a crash between them is structurally impossible (Iron Rule #2, batches are
    * atomic; `RocksDbDataSourceSpec`'s close/reopen crash-consistency test pins this at the primitive level). A block
    * visible without its chain-weight (or a chain-weight with no corresponding block) would corrupt the heaviest-chain
    * decision on restart — there is no recovery path that reconstructs a missing TD from partial data.
    *
    * ==fukuii-specific: PoW keeps TD first-class, unlike post-merge PoS clients==
    * Post-merge ETH clients demote total difficulty entirely (the consensus layer owns fork choice via attestations,
    * not accumulated work) — that is the WRONG template for a PoW successor. fukuii keeps TD first-class at BOTH tiers:
    * this hot CF for the live chain, and [[ColdChainWeight]] for the frozen historical range (`ColdStore`, mirroring
    * core-geth's retained `"diffs"` ancient table) — never dropped, at either tier.
    */
  case ChainWeight extends Namespace('w'.toByte)

  /** Node application/sync-progress bookkeeping (best block, sync status). */
  case AppState extends Namespace('s'.toByte)

  /** Discovered peer-node bookkeeping. */
  case KnownNodes extends Namespace('k'.toByte)

  /** Block-number -> hash mapping (append-only). */
  case Heights extends Namespace('i'.toByte, containsStaticData = true)

  /** Legacy fast-sync progress bookkeeping (transient, cleared once sync completes). */
  case FastSyncState extends Namespace('f'.toByte)

  /** Transaction-hash -> (block, index) mapping (append-only, prunable alongside receipts). */
  case TransactionMapping
      extends Namespace('l'.toByte, containsStaticData = true, isStaticDataGarbageCollectionEnabled = true)

  /** First-seen timestamp bookkeeping per block hash (small, mutated rarely). */
  case BlockFirstSeen extends Namespace('m'.toByte)

  /** Flat storage-slot overlay (besu `ACCOUNT_STORAGE_STORAGE` analogue) — hot, high write volume. */
  case FlatSlot extends Namespace('d'.toByte, isEligibleToHighSpecFlag = true, isCacheIndexAndFilterBlocks = true)

  /** Flat account overlay (besu `ACCOUNT_INFO_STATE` analogue, geth `'a'` convention) — hot, high write volume. */
  case FlatAccount extends Namespace('a'.toByte, isEligibleToHighSpecFlag = true, isCacheIndexAndFilterBlocks = true)

  /** Post-SNAP healing frontier (node hash -> pathset): SNAP crash-resume journal, schema-reserved per L2-F1. */
  case HealingFrontier extends Namespace('g'.toByte, profiles = Set(Namespace.Profile.Base, Namespace.Profile.Snap))

  /** BFS level queue for streaming frontier rebuild: SNAP crash-resume journal, schema-reserved per L2-F1. */
  case BfsQueue extends Namespace('q'.toByte, profiles = Set(Namespace.Profile.Base, Namespace.Profile.Snap))

  /** SNAP download progress cursors (stateRoot -> account/storage-range cursors): SNAP crash-resume journal,
    * schema-reserved per L2-F1.
    */
  case SnapSyncProgress extends Namespace('p'.toByte, profiles = Set(Namespace.Profile.Base, Namespace.Profile.Snap))

  /** State-trie nodes, path-keyed — only populated under `INodeStorage`'s `HalfPath` scheme (S2). */
  case StateTriePath extends Namespace('t'.toByte, profiles = Set(Namespace.Profile.Base, Namespace.Profile.PathScheme))

  /** Storage-trie nodes, path-keyed and account-scoped — only populated under `INodeStorage`'s `HalfPath` scheme (S2).
    * See the S0 reference map's `Location` finding: the account scope is load-bearing, not cosmetic — a bare
    * per-subtrie nibble path collides storage-subtrie nodes across accounts sharing this CF.
    */
  case StorageTriePath
      extends Namespace('u'.toByte, profiles = Set(Namespace.Profile.Base, Namespace.Profile.PathScheme))

  /** The [[SchemaMarker]] record (S2): `(format, version, StorageProfile)`, checked at open against both the requesting
    * profile and the actually-open column-family set (`StorageProfile.namespacesFor`) before any other CF is touched.
    * One fixed key per datadir; tiny, never pruned.
    */
  case SchemaMeta extends Namespace('z'.toByte)

  /** Per-node refcount-GC bookkeeping (S3): `nodeHash -> RefEntry(refCount, location, childHashes, lastUsedByBlock)`.
    * As hot as [[Node]] itself — every trie-node commit touches it. Dedicated CF rather than a prefix within [[Node]]
    * (the AS-IS anti-pattern this replaces, L2 improvement #15).
    */
  case RefCount extends Namespace('e'.toByte, isEligibleToHighSpecFlag = true, isCacheIndexAndFilterBlocks = true)

  /** Death-row bookkeeping (S3): `nodeHash -> blockNumber` at which the node's refcount reached zero — the
    * [[PruningStore.prune]] safe-height barrier's candidate set. Dedicated CF, not a prefix within [[Node]].
    */
  case DeathRow extends Namespace('j'.toByte)

  /** The retained-root ring (S3): `blockNumber -> rootHash` for every block still within the local retention window —
    * the anchor [[PruningStore.commitBlock]] releases once a root falls `historyBlocks` deep. Small, bounded by the
    * window size.
    */
  case RetainedRoot extends Namespace('v'.toByte)

  /** Per-block pruning undo-log (S3): `blockNumber -> BlockSnapshot`, replayed by [[PruningStore.rollback]] on a reorg.
    * Append-only until [[PruningStore.prune]] discards entries at or below the safe height (a rollback beyond it is
    * never valid).
    */
  case PruneSnapshot
      extends Namespace('x'.toByte, containsStaticData = true, isStaticDataGarbageCollectionEnabled = true)

  /** Frozen block headers (S3b, [[ColdStore]]): number-addressed, append-only, one fixed-block-range shard is a single
    * [[DataSource.deleteRange]] away from being dropped whole (RX-L2-21/22). Schema-reserved per the L2-F1 precedent —
    * `namespacesFor` does not gate CF-open on [[Profile.Freezer]] yet; that occupancy is deferred alongside the
    * `freezer` [[StorageProfile]] axis itself.
    */
  case ColdHeader
      extends Namespace(
        'H'.toByte,
        profiles = Set(Namespace.Profile.Base, Namespace.Profile.Freezer),
        containsStaticData = true,
        isStaticDataGarbageCollectionEnabled = true
      )

  /** Frozen block bodies — see [[ColdHeader]]. */
  case ColdBody
      extends Namespace(
        'B'.toByte,
        profiles = Set(Namespace.Profile.Base, Namespace.Profile.Freezer),
        containsStaticData = true,
        isStaticDataGarbageCollectionEnabled = true
      )

  /** Frozen block receipts — see [[ColdHeader]]. */
  case ColdReceipts
      extends Namespace(
        'R'.toByte,
        profiles = Set(Namespace.Profile.Base, Namespace.Profile.Freezer),
        containsStaticData = true,
        isStaticDataGarbageCollectionEnabled = true
      )

  /** Frozen per-block total difficulty — the ETC PoW fork-choice invariant a cold store MUST retain (core-geth
    * `ancient_scheme.go:35-36` `ChainFreezerDifficultyTable = "diffs"`, retained `:46`; the post-merge ETH freezer
    * drops this table, the wrong template for a PoW successor). See [[ColdHeader]].
    */
  case ColdChainWeight
      extends Namespace(
        'W'.toByte,
        profiles = Set(Namespace.Profile.Base, Namespace.Profile.Freezer),
        containsStaticData = true,
        isStaticDataGarbageCollectionEnabled = true
      )

  /** [[ColdStore]] bookkeeping (S3b): the lowest/highest frozen block-number markers. Small, updated once per
    * [[ColdStore.freeze]] call.
    */
  case ColdShardMeta extends Namespace('M'.toByte, profiles = Set(Namespace.Profile.Base, Namespace.Profile.Freezer))

object Namespace:

  /** Storage-profile tags a namespace's column family may belong to. `Base` = every profile (the default); `Snap`,
    * `PathScheme`, and `Freezer` mark schema reservations (L2-F1 / L2-S0 / S3b). This is deliberately NOT S2's full
    * `StorageProfile` (5 live axes + 1 reserved `engine`) — it is the minimal membership tag S1 needs to reserve CF
    * slots without building the full profile-gating machinery for every axis.
    */
  enum Profile:
    case Base, Snap, PathScheme, Freezer

  private val duplicateIds: Set[Byte] =
    values.toList.groupBy(_.id).collect { case (id, cases) if cases.sizeIs > 1 => id }.toSet

  require(
    duplicateIds.isEmpty,
    s"Namespace id collision(s): $duplicateIds — namespace ids are a frozen on-disk contract, never reassign"
  )

  /** Reverse lookup, e.g. for diagnostics over a raw column-family name read back from RocksDB. */
  val byId: Map[Byte, Namespace] = values.map(n => n.id -> n).toMap
