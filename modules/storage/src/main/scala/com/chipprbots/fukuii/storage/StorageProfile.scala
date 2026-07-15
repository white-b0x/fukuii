package com.chipprbots.fukuii.storage

/** The trie-node key scheme: which physical [[INodeStorage]] inhabitant a [[StorageProfile]] selects.
  *
  * `Hash` (besu Forest-equivalent) is content-addressed — the archival choice, full subtree dedup and history. `Path`
  * (besu Bonsai-equivalent, geth pathdb, nethermind HalfPath) is nibble-path-addressed — better block-cache locality,
  * one write per path (no hash-recompute cascade up the tree), and the substrate a later online Hash-\>Path migration
  * keys off. `Path` is the modern reference-client default (geth defaults an empty datadir to pathdb; nethermind
  * defaults to HalfPath); `Hash` is the archival opt-in. `Both` opens the union of both scheme-gated column families
  * without picking an active scheme — [[StorageProfile.default]] uses it to reproduce the pre-S2 "everything open, no
  * gating" shape byte-for-byte, and it is also the CF-opening footprint an online Hash<->Path migration profile would
  * need (the active-scheme dispatch itself is `INodeStorage.Scheme`, chosen by the caller, not derived from `Both`).
  */
enum NodeKeying:
  case Hash, Path, Both

/** The pruning-mode axis (besu Forest mark-and-sweep vs. Bonsai online trie-log pruner; nethermind
  * `IPruningStrategy`/`IPersistenceStrategy`). A named placeholder for S2: only [[NodeKeying]] has real behavior wired
  * at this layer (the `INodeStorage` seam); the composable `EvictionStrategy`/`PersistenceStrategy` split behind this
  * selector lands with the pruning-mode work itself.
  */
enum PruningMode:
  case Archive, Basic, InMemory

/** The flat-state-accelerator axis (besu Bonsai flat strategy; go-ethereum snapshot layer) — an O(1) `hash(key) ->
  * value` mirror in front of the trie, trie remains authoritative. A named placeholder for S2; the accelerator itself
  * is `FlatAccountStorage`/`FlatSlotStorage` work, not this seam.
  */
enum FlatAccelerator:
  case Off, On

/** The cold-storage / freezer axis (hot/cold split, era1 export). A named placeholder for S2; the `ColdStore` seam
  * itself is separate work.
  */
enum ColdStoreMode:
  case Off, Freezer, Era1

/** The history-expiry axis (EIP-4444). A named placeholder for S2. */
enum HistoryExpiry:
  case Off, Enabled

/** The key-value engine axis — RocksDB is the sole live inhabitant (MDBX is OBSOLETE for fukuii, no mature JVM
  * binding). Reserved as a single-inhabitant axis: this does NOT restate [[NodeKeying]] (there is no `keying=Path,
  * engine=<hash-only>` combination to guard against) — it is the KV backend choice, orthogonal to how trie nodes are
  * keyed within it.
  */
enum KvEngine:
  case RocksDb

/** A role x network storage-approach selector — the besu `DataStorageFormat` shape, composing six axes (five live + the
  * reserved single-inhabitant [[KvEngine]]) resolved once per `ChainInstance` at open and recorded by [[SchemaMarker]].
  * Only [[keying]] has real behavior wired at S2 (the [[INodeStorage]] scheme-indirection seam and
  * [[StorageProfile.namespacesFor]]'s column-family gating); the remaining axes are the seam's declared shape for the
  * later increments that give them behavior (pruning-strategy composition, the flat accelerator, the cold-store
  * freezer, history expiry).
  */
final case class StorageProfile(
    keying: NodeKeying,
    pruning: PruningMode,
    flat: FlatAccelerator,
    freezer: ColdStoreMode,
    expiry: HistoryExpiry,
    engine: KvEngine
)

object StorageProfile:

  /** Deep archival / dApp-serving: hash-keyed (full subtree history), never prunes, retains a cold store. */
  val ArchivalDApp: StorageProfile =
    StorageProfile(
      NodeKeying.Hash,
      PruningMode.Archive,
      FlatAccelerator.Off,
      ColdStoreMode.Freezer,
      HistoryExpiry.Off,
      KvEngine.RocksDb
    )

  /** Tip-of-branch server: path-keyed (the modern default, D2), basic pruning, flat accelerator on. */
  val TipServer: StorageProfile =
    StorageProfile(
      NodeKeying.Path,
      PruningMode.Basic,
      FlatAccelerator.On,
      ColdStoreMode.Off,
      HistoryExpiry.Off,
      KvEngine.RocksDb
    )

  /** Pruned RPC-relay: path-keyed, basic pruning, flat accelerator on (fast account/slot reads for RPC serving). */
  val PrunedRelay: StorageProfile =
    StorageProfile(
      NodeKeying.Path,
      PruningMode.Basic,
      FlatAccelerator.On,
      ColdStoreMode.Off,
      HistoryExpiry.Off,
      KvEngine.RocksDb
    )

  /** Resource-light (end-user node): path-keyed, basic pruning, flat accelerator on, bounded disk footprint. */
  val ResourceLight: StorageProfile =
    StorageProfile(
      NodeKeying.Path,
      PruningMode.Basic,
      FlatAccelerator.On,
      ColdStoreMode.Off,
      HistoryExpiry.Off,
      KvEngine.RocksDb
    )

  /** Validator: path-keyed, basic pruning, flat accelerator on — locality favors the block-processing hot path. */
  val Validator: StorageProfile =
    StorageProfile(
      NodeKeying.Path,
      PruningMode.Basic,
      FlatAccelerator.On,
      ColdStoreMode.Off,
      HistoryExpiry.Off,
      KvEngine.RocksDb
    )

  /** Mining pool: path-keyed, basic pruning, flat accelerator on — same hot-path shape as `Validator`. */
  val MiningPool: StorageProfile =
    StorageProfile(
      NodeKeying.Path,
      PruningMode.Basic,
      FlatAccelerator.On,
      ColdStoreMode.Off,
      HistoryExpiry.Off,
      KvEngine.RocksDb
    )

  /** The no-op-gate default: `keying = Both` resolves [[namespacesFor]] to the full `Namespace.values` set — the same
    * unconditional "open everything" shape `RocksDbDataSource` used before [[SchemaMarker]] existed. Every
    * `RocksDbDataSource.apply` call site that does not pass an explicit [[StorageProfile]] gets this value, so the
    * default open path is unchanged byte-for-byte: same CF set, same handles, and a marker that — once written — only
    * ever compares itself to itself on every subsequent default-profile reopen.
    */
  val default: StorageProfile =
    StorageProfile(
      NodeKeying.Both,
      PruningMode.Basic,
      FlatAccelerator.Off,
      ColdStoreMode.Off,
      HistoryExpiry.Off,
      KvEngine.RocksDb
    )

  /** The three trie-node column families whose membership is gated by [[NodeKeying]] — mutually exclusive by scheme
    * (never both open under one profile at S2; a live Hash<->Path migration, when built, is the exception that opens
    * both).
    */
  private val hashSchemeNamespaces: Set[Namespace] = Set(Namespace.Node)
  private val pathSchemeNamespaces: Set[Namespace] = Set(Namespace.StateTriePath, Namespace.StorageTriePath)
  private val schemeGatedNamespaces: Set[Namespace] = hashSchemeNamespaces ++ pathSchemeNamespaces

  /** The column-family set a resolved profile opens (besu `KeyValueSegmentIdentifier.includeInDatabaseFormat`
    * format-gating realized over `keying`): every namespace not tied to a specific node-keying scheme, plus the
    * scheme-specific trie-node CF the profile's [[NodeKeying]] selects. [[SchemaMarker]] checks this set against both
    * the requested profile and the datadir's actually-open namespaces before any other CF is touched.
    */
  def namespacesFor(profile: StorageProfile): Set[Namespace] =
    val schemeSpecific = profile.keying match
      case NodeKeying.Hash => hashSchemeNamespaces
      case NodeKeying.Path => pathSchemeNamespaces
      case NodeKeying.Both => schemeGatedNamespaces
    (Namespace.values.toSet -- schemeGatedNamespaces) ++ schemeSpecific
