package com.chipprbots.fukuii.trie

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPDecoder
import com.chipprbots.fukuii.rlp.RLPEncodeable
import com.chipprbots.fukuii.rlp.RLPEncoder
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.RLPValue
import com.chipprbots.fukuii.rlp.encode as encodeRlp
import com.chipprbots.fukuii.rlp.rawDecode

/** A raised, typed failure decoding node bytes into an [[MptNode]].
  *
  * Tier-2 runtime guard (L2-F3). Compile-time exhaustiveness over the [[MptNode]] enum covers *our* construction paths,
  * but the bytes→node decode seam is fed adversarial peer input (`GetTrieNodes` / SNAP), so a malformed node blob — a
  * list of the wrong arity, an oversized (`>= 32`) embedded child that should have been a hash reference, or a bad
  * hex-prefix flag byte — must fail loud here, never silently mis-decode into a wrong-but-plausible node.
  */
final class MptNodeDecodeException(message: String, cause: Option[Throwable] = None)
    extends RuntimeException(message, cause.orNull)

/** A Merkle Patricia Trie node — the five consensus node shapes.
  *
  * A closed `enum` with a total `match` over its cases needs no `@unchecked` exhaustivity suppression.
  * `key`/`sharedKey` hold **nibbles** (one nibble per byte, `0..15`); hex-prefix packing to bytes happens only at
  * RLP-encode time.
  *
  *   - [[Leaf]] — a terminal `(key, value)` (2-item node, leaf flag set).
  *   - [[Extension]] — a `(sharedKey, next)` path-compression node (2-item node, leaf flag clear).
  *   - [[Branch]] — 16 child slots + an optional terminator value (17-item node).
  *   - [[Hash]] — a 32-byte keccak reference to a stored `>= 32`-byte node.
  *   - [[Null]] — the empty node; **encodes as the bare RLP empty string `0x80`, never a 1-item list.**
  */
enum MptNode:
  case Leaf(key: ByteString, value: ByteString)
  case Extension(sharedKey: ByteString, next: MptNode)
  case Branch(children: Vector[MptNode], terminator: Option[ByteString])
  case Hash(ref: ByteString)
  case Null

  def isNull: Boolean = this match
    case Null => true
    case _    => false

  /** RLP bytes of this node in its own right (children referenced by cap: inline `< 32`, else a hash ref). */
  def encoded: Array[Byte] = encodeRlp(MptNode.toRlp(this))

  /** The 32-byte keccak hash of this node — the value a parent (or the state root) references it by. A [[Hash]] node
    * *is* that reference, so it returns `ref` directly; every other node is force-hashed regardless of size, matching
    * geth (the root is always hashed even when its RLP is `< 32` bytes; `Null` hashes to the empty-trie root).
    */
  def hash: ByteString = this match
    case Hash(ref) => ref
    case _         => ByteString(kec256(encoded))

  /** Whether this node is persisted as its own store entry (and thus reference-counted independently) rather than
    * inlined into its parent. A [[Hash]] reference always is (it names a stored node from this or a prior commit); a
    * resident node is iff its RLP is `>= 32` bytes (below that it is embedded verbatim in its parent — geth's `< 32`
    * inline rule). [[Null]] never is. This is exactly the predicate under which
    * [[MerklePatriciaTrie.store]]/`persistIfBig` writes a child to storage, so a child that answers `true` is
    * guaranteed present in the committed set/store (upholds forge F-2: no dangling child-hash).
    */
  private def isStoredSeparately: Boolean = this match
    case Null    => false
    case Hash(_) => true
    case _       => encoded.length >= MptNode.MaxEncodedNodeLength

  /** The direct child node-hashes this node contributes as edges to the refcount graph (S3a `RefCountedNodeStore`).
    *
    * A [[Branch]] yields the hash of each populated child slot stored as its own node; an [[Extension]] yields its
    * `next` child's; [[Leaf]] (its value is inline data, not a node), [[Hash]] (a reference, not a node with resident
    * children), and [[Null]] yield none. **Only children that are themselves separate store entries** ([[Hash]] refs,
    * or resident nodes `>= 32` bytes — see [[isStoredSeparately]]) are emitted: an embedded (`< 32`-byte) child has no
    * independent store entry, so emitting its hash would be a dangling reference (forge F-2). The trie extracts these
    * hashes; `storage` refcounts them (byte-pure boundary — `storage` never parses a node).
    */
  def childHashes: Seq[ByteString] = this match
    case Branch(children, _) => children.collect { case c if c.isStoredSeparately => c.hash }
    case Extension(_, next)  => if next.isStoredSeparately then Seq(next.hash) else Nil
    case Leaf(_, _)          => Nil
    case Hash(_)             => Nil
    case Null                => Nil

object MptNode:

  /** RLP list length of a branch node (16 children + terminator). */
  val ListSize: Int = 17

  /** RLP list length of a leaf/extension node (packed key + value/next). */
  val PairSize: Int = 2

  /** Child count of a branch node. */
  val NumberOfChildren: Int = 16

  /** The `< 32`-byte inline threshold: a node whose RLP is strictly shorter is embedded in its parent verbatim; at `>=
    * 32` bytes it is replaced by its 32-byte keccak hash reference.
    */
  val MaxEncodedNodeLength: Int = 32

  private val emptyChildren: Vector[MptNode] = Vector.fill(NumberOfChildren)(MptNode.Null)

  /** RLP of the empty node (`0x80`). */
  val EmptyEncoded: Array[Byte] = encodeRlp(RLPValue(Array.emptyByteArray))

  /** `keccak256(RLP(""))` — the canonical empty-trie state root `56e81f…b421`. */
  val EmptyRootHash: ByteString = ByteString(kec256(EmptyEncoded))

  /** A fresh branch with all-`Null` children. */
  def emptyBranch(terminator: Option[ByteString]): Branch = Branch(emptyChildren, terminator)

  /** A branch carrying only a value (temporarily childless). */
  def branchWithValue(value: ByteString): Branch = Branch(emptyChildren, Some(value))

  /** A branch with a single populated child slot (and optional value). */
  def branchWithChild(position: Int, child: MptNode, terminator: Option[ByteString]): Branch =
    Branch(emptyChildren.updated(position, child), terminator)

  extension (branch: Branch)
    /** A new branch with one child slot replaced. */
    def updated(position: Int, child: MptNode): Branch =
      branch.copy(children = branch.children.updated(position, child))

  // -- RLP encode -----------------------------------------------------------

  /** This node's own RLP AST (children in their capped reference form). */
  def toRlp(node: MptNode): RLPEncodeable = node match
    case Leaf(key, value) =>
      RLPList(RLPValue(HexPrefix.encode(key.toArray, isLeaf = true)), RLPValue(value.toArray))
    case Extension(sharedKey, next) =>
      RLPList(RLPValue(HexPrefix.encode(sharedKey.toArray, isLeaf = false)), capped(next))
    case Branch(children, terminator) =>
      val slots = children.map(capped) :+ RLPValue(terminator.map(_.toArray).getOrElse(Array.emptyByteArray))
      RLPList(slots*)
    case Hash(ref) => RLPValue(ref.toArray)
    case Null      => RLPValue(Array.emptyByteArray)

  /** The reference a parent stores for `node`: inline it (`< 32` bytes) or a 32-byte keccak hash ref (`>= 32`). */
  private def capped(node: MptNode): RLPEncodeable = node match
    case Hash(ref) => RLPValue(ref.toArray)
    case Null      => RLPValue(Array.emptyByteArray)
    case other =>
      val rlp = toRlp(other)
      val bytes = encodeRlp(rlp)
      if bytes.length < MaxEncodedNodeLength then rlp
      else RLPValue(kec256(bytes))

  // -- RLP decode (fails loud, L2-F3) ---------------------------------------

  /** Decode node bytes, raising [[MptNodeDecodeException]] on any malformed input. */
  def decode(bytes: Array[Byte]): MptNode =
    try parse(rawDecode(bytes))
    catch
      case e: MptNodeDecodeException => throw e
      case e: Throwable => throw new MptNodeDecodeException(s"Malformed MPT node bytes: ${e.getMessage}", Some(e))

  private def parse(rlp: RLPEncodeable): MptNode = rlp match
    case list: RLPList if list.items.size == ListSize =>
      val items = list.items
      val children = Vector.tabulate(NumberOfChildren)(i => parseChild(items(i)))
      val terminator = items(NumberOfChildren) match
        case RLPValue(bytes) => if bytes.isEmpty then None else Some(ByteString(bytes))
        case other =>
          throw new MptNodeDecodeException(s"Branch terminator must be a value, got ${describe(other)}")
      Branch(children, terminator)

    case list: RLPList if list.items.size == PairSize =>
      val keyBytes = list.items.head match
        case RLPValue(bytes) => bytes
        case other => throw new MptNodeDecodeException(s"2-item node key must be a value, got ${describe(other)}")
      validateHexPrefixFlag(keyBytes)
      val (key, isLeaf) = HexPrefix.decode(keyBytes)
      if isLeaf then
        val value = list.items(1) match
          case RLPValue(bytes) => ByteString(bytes)
          case other => throw new MptNodeDecodeException(s"Leaf value must be a value, got ${describe(other)}")
        Leaf(ByteString(key), value)
      else Extension(ByteString(key), parseChild(list.items(1)))

    case _: RLPList =>
      throw new MptNodeDecodeException(
        s"Invalid MPT node list arity: expected $PairSize or $ListSize items"
      )

    case RLPValue(bytes) if bytes.isEmpty                        => Null
    case RLPValue(bytes) if bytes.length == MaxEncodedNodeLength => Hash(ByteString(bytes))
    case RLPValue(bytes) =>
      throw new MptNodeDecodeException(
        s"Invalid standalone node value: expected empty or $MaxEncodedNodeLength bytes, got ${bytes.length}"
      )
    case other =>
      throw new MptNodeDecodeException(s"Unexpected RLP node shape: ${describe(other)}")

  /** Parse a branch/extension child slot: empty ⇒ `Null`, 32 bytes ⇒ `Hash`, an embedded list ⇒ a resident node whose
    * RLP must be strictly `< 32` bytes (an oversized embedded node is malformed — it should have been a hash ref).
    */
  private def parseChild(item: RLPEncodeable): MptNode = item match
    case RLPValue(bytes) if bytes.isEmpty                        => Null
    case RLPValue(bytes) if bytes.length == MaxEncodedNodeLength => Hash(ByteString(bytes))
    case RLPValue(bytes) =>
      throw new MptNodeDecodeException(
        s"Invalid child reference: expected empty or $MaxEncodedNodeLength bytes, got ${bytes.length}"
      )
    case list: RLPList =>
      val embeddedLength = encodeRlp(list).length
      if embeddedLength >= MaxEncodedNodeLength then
        throw new MptNodeDecodeException(
          s"Oversized embedded node: $embeddedLength bytes (must be < $MaxEncodedNodeLength; should be a hash ref)"
        )
      parse(list)
    case other =>
      throw new MptNodeDecodeException(s"Unexpected child reference shape: ${describe(other)}")

  /** A well-formed hex-prefix flag byte has its high nibble in `{0,1,2,3}` (only bits 4/5 may be set) and, for an
    * even-length key, a zero low nibble in `buf[0]`. Anything else is a bad HP flag ⇒ loud.
    */
  private def validateHexPrefixFlag(keyBytes: Array[Byte]): Unit =
    if keyBytes.isEmpty then throw new MptNodeDecodeException("Empty hex-prefix key")
    val flag = keyBytes(0) & 0xff
    if (flag & 0xc0) != 0 then
      throw new MptNodeDecodeException(f"Bad hex-prefix flag byte: 0x$flag%02x (high bits set)")
    val odd = (flag & 0x10) != 0
    if !odd && (flag & 0x0f) != 0 then
      throw new MptNodeDecodeException(f"Bad hex-prefix flag byte: 0x$flag%02x (even-length with non-zero pad nibble)")

  private def describe(rlp: RLPEncodeable): String = rlp match
    case _: RLPList  => "a list"
    case _: RLPValue => "a value"
    case _           => "an unexpected node"

  /** Node RLP codec — the L0 `rlp` `given` surface consumers summon. Self-recursion is a direct companion call, not a
    * cross-type codec dispatch, so no S13 sibling-summon applies (keys/values encode directly as `RLPValue`).
    */
  given rlpCodec: RLPCodec[MptNode] =
    RLPCodec.from(RLPEncoder.instance(toRlp), RLPDecoder.instance(parse))
