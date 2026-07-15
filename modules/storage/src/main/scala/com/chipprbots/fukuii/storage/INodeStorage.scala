package com.chipprbots.fukuii.storage

/** A trie node's location, mirroring `trie`'s opaque `Location` in byte-pure form (`storage` never imports `trie` node
  * types — DoD grep). `owner = None` -> a node in the state/account trie; `owner = Some(accountKey)` -> a node in that
  * account's storage sub-trie. Load-bearing for [[PathKeyedNodeStorage]]: a bare nibble `path` would collide
  * storage-subtrie nodes at the same path across different accounts sharing this store.
  */
final case class NodeLocation(owner: Option[IndexedSeq[Byte]], path: IndexedSeq[Byte])

object NodeLocation:
  val Root: NodeLocation = NodeLocation(None, IndexedSeq.empty)

/** The canonical empty-trie node: `keccak256(RLP(""))`, RLP-encoded as the bare byte `0x80`. Neither
  * [[HashKeyedNodeStorage]] nor [[PathKeyedNodeStorage]] ever persists a physical entry for it — a read for this hash
  * always resolves to the known encoding, and a write for it is a no-op (nethermind `NodeStorage.cs:104-108, 162-164`;
  * besu `ForestWorldStateKeyValueStorage.java:69`). Duplicated here as a literal rather than computed via
  * `crypto.kec256`: `storage` does not depend on `crypto` (`storage -> domain, common` only), and this is a
  * consensus-fixed constant, not a value that could drift.
  */
object EmptyNode:
  private def hexToBytes(hex: String): Array[Byte] =
    hex.grouped(2).map(b => Integer.parseInt(b, 16).toByte).toArray

  /** `56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421` — the canonical empty-trie root. */
  val hash: IndexedSeq[Byte] =
    hexToBytes("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421").toIndexedSeq

  /** The bare RLP empty-string byte, `0x80`. */
  val encoded: IndexedSeq[Byte] = IndexedSeq(0x80.toByte)

/** The scheme-indirected trie-node store seam (nethermind `Nethermind.Trie/INodeStorage.cs`): resolves a node's RLP
  * bytes by `(location, nodeHash)`, independent of which physical keying scheme backs it. Both [[HashKeyedNodeStorage]]
  * and [[PathKeyedNodeStorage]] implement this directly (single-scheme); [[SchemeIndirectedNodeStorage]] composes them
  * behind a mutable active [[Scheme]] with directional dual-read fallback.
  */
trait INodeStorage:
  def get(location: NodeLocation, nodeHash: IndexedSeq[Byte]): Option[IndexedSeq[Byte]]
  def put(location: NodeLocation, nodeHash: IndexedSeq[Byte], value: IndexedSeq[Byte]): Unit
  def remove(location: NodeLocation, nodeHash: IndexedSeq[Byte]): Unit

/** Hash-keyed (besu Forest-equivalent) [[INodeStorage]]: the physical key is the node hash alone, content-addressed —
  * `location` is ignored entirely (this is the archival profile's substrate, `Namespace.Node`).
  */
final class HashKeyedNodeStorage(dataSource: DataSource) extends INodeStorage:

  override def get(location: NodeLocation, nodeHash: IndexedSeq[Byte]): Option[IndexedSeq[Byte]] =
    if nodeHash == EmptyNode.hash then Some(EmptyNode.encoded)
    else dataSource.get(Namespace.Node, nodeHash)

  override def put(location: NodeLocation, nodeHash: IndexedSeq[Byte], value: IndexedSeq[Byte]): Unit =
    if nodeHash != EmptyNode.hash then
      dataSource.update(Seq(DataSourceUpdate(Namespace.Node, Nil, Seq(nodeHash -> value))))

  override def remove(location: NodeLocation, nodeHash: IndexedSeq[Byte]): Unit =
    dataSource.update(Seq(DataSourceUpdate(Namespace.Node, Seq(nodeHash), Nil)))

/** Path-keyed (besu Bonsai-equivalent, geth pathdb, nethermind HalfPath) [[INodeStorage]]: the physical key is
  * `owner-prefix ++ path ++ nodeHash`, split across two column families by `location.owner` — `Namespace.StateTriePath`
  * when `owner = None` (no owner prefix needed, the CF itself scopes it), `Namespace.StorageTriePath` when `owner =
  * Some(_)` (account-scoped, so the owner prefix disambiguates sub-tries sharing this CF). The node hash is folded into
  * the key tail (D4) rather than discarded — a path read can re-verify `keccak(blob) == hash` the way a hash-keyed read
  * is inherently self-verifying, and it keeps distinct nodes from colliding on-disk even where the `(owner, path)`
  * prefix alone is not yet fully discriminating.
  */
final class PathKeyedNodeStorage(dataSource: DataSource) extends INodeStorage:

  private def namespaceAndKey(location: NodeLocation, nodeHash: IndexedSeq[Byte]): (Namespace, IndexedSeq[Byte]) =
    location.owner match
      case None        => (Namespace.StateTriePath, location.path ++ nodeHash)
      case Some(owner) => (Namespace.StorageTriePath, owner ++ location.path ++ nodeHash)

  override def get(location: NodeLocation, nodeHash: IndexedSeq[Byte]): Option[IndexedSeq[Byte]] =
    if nodeHash == EmptyNode.hash then Some(EmptyNode.encoded)
    else
      val (ns, key) = namespaceAndKey(location, nodeHash)
      dataSource.get(ns, key)

  override def put(location: NodeLocation, nodeHash: IndexedSeq[Byte], value: IndexedSeq[Byte]): Unit =
    if nodeHash != EmptyNode.hash then
      val (ns, key) = namespaceAndKey(location, nodeHash)
      dataSource.update(Seq(DataSourceUpdate(ns, Nil, Seq(key -> value))))

  override def remove(location: NodeLocation, nodeHash: IndexedSeq[Byte]): Unit =
    val (ns, key) = namespaceAndKey(location, nodeHash)
    dataSource.update(Seq(DataSourceUpdate(ns, Seq(key), Nil)))

/** Which physical [[INodeStorage]] inhabitant is currently active in a [[SchemeIndirectedNodeStorage]]. Mirrors
  * [[NodeKeying]] (that enum names the profile-level choice; this one names the seam's runtime dispatch).
  */
enum Scheme:
  case Hash, Path

object Scheme:

  /** `NodeKeying.Both` has no single active scheme of its own — it names a CF-opening footprint (both scheme-gated
    * column families open, e.g. [[StorageProfile.default]]), not a dispatch choice. A caller building a
    * [[SchemeIndirectedNodeStorage]] over a `Both`-opened profile picks the active scheme explicitly (e.g. for a live
    * migration); this mapping defaults it to `Hash` only so [[of]] stays total.
    */
  def of(keying: NodeKeying): Scheme = keying match
    case NodeKeying.Hash => Scheme.Hash
    case NodeKeying.Path => Scheme.Path
    case NodeKeying.Both => Scheme.Hash

/** The `INodeStorage` scheme-indirection seam (nethermind `Nethermind.Trie/INodeStorage.cs`): a mutable active
  * [[Scheme]] over both inhabitants, with a directional dual-read fallback gated by `migrationInProgress`.
  *
  * ==Directional dual-read==
  * A read always tries the active scheme first; it only probes the other scheme when `migrationInProgress` is true
  * (`Scheme.Path` active -> `get(pathKey) orElse get(hashKey)`; `Scheme.Hash` active -> `get(hashKey) orElse
  * get(pathKey)`). With `migrationInProgress = false` the other scheme is never even probed — the common case (no
  * migration in flight) pays no cost for a scheme indirection it isn't using. This steady-state single-probe is a
  * fukuii choice, geth-aligned (`ReadTrieNode` reads one scheme with no fallback); it is NOT nethermind's `RequirePath`
  * (which governs skipping path *computation* in hash-only mode — nethermind's `Get` dual-reads unconditionally).
  *
  * ==Writes and deletes use only the active scheme==
  * [[put]] and [[remove]] forward to the active scheme's inhabitant alone, never both — this is the D3 delete asymmetry
  * (nethermind `NodeStorage.cs:167-171`, "DO NOT delete hash based key"): removing a node under an active `Path` scheme
  * deletes only the path-keyed copy, leaving any hash-keyed copy (e.g. an archival migration source copy) untouched.
  */
final class SchemeIndirectedNodeStorage(
    hashStore: INodeStorage,
    pathStore: INodeStorage,
    initialScheme: Scheme,
    migrationInProgress: Boolean = false
) extends INodeStorage:

  @volatile private var activeScheme: Scheme = initialScheme

  def currentScheme: Scheme = activeScheme

  def switchScheme(scheme: Scheme): Unit = activeScheme = scheme

  private def active: INodeStorage = activeScheme match
    case Scheme.Hash => hashStore
    case Scheme.Path => pathStore

  private def inactive: INodeStorage = activeScheme match
    case Scheme.Hash => pathStore
    case Scheme.Path => hashStore

  override def get(location: NodeLocation, nodeHash: IndexedSeq[Byte]): Option[IndexedSeq[Byte]] =
    active.get(location, nodeHash) match
      case found @ Some(_) => found
      case None            => if migrationInProgress then inactive.get(location, nodeHash) else None

  override def put(location: NodeLocation, nodeHash: IndexedSeq[Byte], value: IndexedSeq[Byte]): Unit =
    active.put(location, nodeHash, value)

  override def remove(location: NodeLocation, nodeHash: IndexedSeq[Byte]): Unit =
    active.remove(location, nodeHash)
