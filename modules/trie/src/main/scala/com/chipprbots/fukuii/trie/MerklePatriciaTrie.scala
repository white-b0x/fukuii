package com.chipprbots.fukuii.trie

import scala.annotation.tailrec

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.ByteUtils.matchingLength
import com.chipprbots.fukuii.crypto.kec256

/** An immutable Merkle Patricia Trie over keys `K` and values `V`.
  *
  * Mantis-lineage functional shape: every mutation returns a new trie; nodes are held resident and the state root is
  * computed bottom-up by force-hashing the resident root ([[MptNode.hash]]), children capped inline (`< 32`) or by
  * keccak hash (`>= 32`). The `(location, hash)` node store ([[MptStorage]]) resolves any [[MptNode.Hash]] reference —
  * a resident trie never touches it until [[commit]] persists the tree.
  *
  * Generic over the key/value byte serializers ([[ByteArrayEncoder]]/[[ByteArraySerializable]]): the plain trie uses
  * the identity `Array[Byte]` serializer; the keccak-key "secure trie" wraps the key serializer in
  * [[HashByteArraySerializable]] (no `SecureTrie` subclass).
  */
final class MerklePatriciaTrie[K, V] private (
    val rootNode: Option[MptNode],
    val storage: MptStorage
)(using kSerializer: ByteArrayEncoder[K], vSerializer: ByteArraySerializable[V]):

  import MerklePatriciaTrie.*
  import MptNode.updated

  /** The 32-byte state root — force-hashed resident root, or the empty-trie root for an empty trie. */
  def getRootHash: ByteString = rootNode.map(_.hash).getOrElse(MptNode.EmptyRootHash)

  private def mkKeyNibbles(key: K): Array[Byte] = HexPrefix.bytesToNibbles(kSerializer.toBytes(key))

  // -- reads ----------------------------------------------------------------

  /** The value associated with `key`, if present. */
  def get(key: K): Option[V] =
    pathTraverse[Option[V]](None, mkKeyNibbles(key)) {
      case (_, Some(MptNode.Leaf(_, value)))        => Some(vSerializer.fromBytes(value.toArray))
      case (_, Some(MptNode.Branch(_, terminator))) => terminator.map(t => vSerializer.fromBytes(t.toArray))
      case _                                        => None
    }.flatten

  /** The EIP-1186 Merkle proof for `key` — inclusion (path to the value leaf) or non-inclusion (the walk's terminal
    * node proves absence). The root is always included on a non-empty trie; `None` only for an empty trie.
    */
  def getProof(key: K): Option[Vector[MptNode]] =
    val walked = pathTraverse[Vector[MptNode]](Vector.empty, mkKeyNibbles(key), proofMode = true) { (acc, node) =>
      node match
        case Some(MptNode.Hash(ref))                                                   => acc :+ storage.get(ref)
        case Some(next @ (_: MptNode.Branch | _: MptNode.Extension | _: MptNode.Leaf)) => acc :+ next
        case _                                                                         => acc
    }
    walked.map { proof =>
      if proof.nonEmpty then proof
      else
        rootNode
          .map {
            case MptNode.Hash(ref) => Vector(storage.get(ref))
            case root              => Vector(root)
          }
          .getOrElse(Vector.empty)
    }

  private def pathTraverse[T](acc: T, searchKey: Array[Byte], proofMode: Boolean = false)(
      op: (T, Option[MptNode]) => T
  ): Option[T] =
    @tailrec
    def go(acc: T, node: MptNode, searchKey: Array[Byte]): Option[T] = node match
      case leaf @ MptNode.Leaf(key, _) =>
        if key.toArray.sameElements(searchKey) || proofMode then Some(op(acc, Some(leaf)))
        else Some(op(acc, None))

      case ext @ MptNode.Extension(sharedKey, next) =>
        val (commonKey, remainingKey) = searchKey.splitAt(sharedKey.length)
        if searchKey.length >= sharedKey.length && sharedKey.toArray.sameElements(commonKey) then
          go(op(acc, Some(ext)), next, remainingKey)
        else if proofMode then Some(op(acc, Some(ext)))
        else Some(op(acc, None))

      case branch @ MptNode.Branch(children, _) =>
        if searchKey.isEmpty then Some(op(acc, Some(branch)))
        else go(op(acc, Some(branch)), children(searchKey(0).toInt), searchKey.slice(1, searchKey.length))

      case MptNode.Hash(ref) =>
        go(acc, storage.get(ref), searchKey)

      case MptNode.Null =>
        Some(op(acc, None))

    rootNode match
      case Some(MptNode.Hash(ref)) => go(acc, storage.get(ref), searchKey)
      case Some(root)              => go(acc, root, searchKey)
      case None                    => None

  // -- writes ---------------------------------------------------------------

  /** Insert or update `key -> value`, returning a new trie. */
  def put(key: K, value: V): MerklePatriciaTrie[K, V] =
    val keyNibbles = mkKeyNibbles(key)
    val valueBytes = ByteString(vSerializer.toBytes(value))
    rootNode match
      case Some(root) =>
        val InsertResult(newRoot, _) = putInto(root, keyNibbles, valueBytes)
        new MerklePatriciaTrie(Some(newRoot), storage)
      case None =>
        new MerklePatriciaTrie(Some(MptNode.Leaf(ByteString(keyNibbles), valueBytes)), storage)

  /** Remove `key`, returning a new trie (or `this` if `key` is absent). */
  def remove(key: K): MerklePatriciaTrie[K, V] =
    rootNode match
      case Some(root) =>
        removeFrom(root, mkKeyNibbles(key)) match
          case RemoveResult(true, Some(newRoot), _) => new MerklePatriciaTrie(Some(newRoot), storage)
          case RemoveResult(true, None, _)          => new MerklePatriciaTrie(None, storage)
          case RemoveResult(false, _, _)            => this
      case None => this

  def +(kv: (K, V)): MerklePatriciaTrie[K, V] = put(kv._1, kv._2)
  def -(key: K): MerklePatriciaTrie[K, V] = remove(key)

  /** Apply a batch of removals then upserts. */
  def update(toRemove: Seq[K], toUpsert: Seq[(K, V)]): MerklePatriciaTrie[K, V] =
    val afterRemoval = toRemove.foldLeft(this)((acc, key) => acc - key)
    toUpsert.foldLeft(afterRemoval)((acc, item) => acc + item)

  /** Persist the resident tree into [[storage]], returning a store-backed trie rooted at the hash reference. */
  def commit(): MerklePatriciaTrie[K, V] =
    rootNode match
      case None       => this
      case Some(root) => new MerklePatriciaTrie(Some(MerklePatriciaTrie.store(root, storage)), storage)

  private def putInto(node: MptNode, searchKey: Array[Byte], value: ByteString): InsertResult = node match
    case leaf: MptNode.Leaf     => putInLeaf(leaf, searchKey, value)
    case ext: MptNode.Extension => putInExtension(ext, searchKey, value)
    case branch: MptNode.Branch => val (b, del) = putInBranch(branch, searchKey, value); InsertResult(b, del)
    case MptNode.Hash(ref)      => putInto(storage.get(ref), searchKey, value)
    case MptNode.Null           => throw new MPTException("Cannot put into a Null node")

  private def putInLeaf(node: MptNode.Leaf, searchKey: Array[Byte], value: ByteString): InsertResult =
    val MptNode.Leaf(existingKey, storedValue) = node
    matchingLength(existingKey.toArray, searchKey) match
      case ml if ml == existingKey.length && ml == searchKey.length =>
        // Same key, replace the value.
        InsertResult(MptNode.Leaf(existingKey, value), List(node))

      case 0 =>
        // No common prefix — replace the leaf with a branch.
        val temporalBranch =
          if existingKey.isEmpty then MptNode.branchWithValue(storedValue)
          else MptNode.branchWithChild(existingKey(0).toInt, MptNode.Leaf(existingKey.tail, storedValue), None)
        val (newBranch, del) = putInBranch(temporalBranch, searchKey, value)
        InsertResult(newBranch, node :: del.filterNot(_ == temporalBranch))

      case ml =>
        // Partial shared prefix — an extension over the prefix leading to a branch.
        val (searchKeyPrefix, searchKeySuffix) = searchKey.splitAt(ml)
        val temporalNode =
          if ml == existingKey.length then MptNode.branchWithValue(storedValue)
          else MptNode.Leaf(existingKey.drop(ml), storedValue)
        val InsertResult(inserted, del) = putInto(temporalNode, searchKeySuffix, value)
        val branch = asBranch(inserted, "putInLeaf: split leaf")
        InsertResult(
          MptNode.Extension(ByteString(searchKeyPrefix), branch),
          node :: del.filterNot(_ == temporalNode)
        )

  private def putInExtension(ext: MptNode.Extension, searchKey: Array[Byte], value: ByteString): InsertResult =
    val MptNode.Extension(sharedKey, next) = ext
    matchingLength(sharedKey.toArray, searchKey) match
      case 0 =>
        // No common prefix — replace the extension with a branch.
        val sharedKeyHead = sharedKey(0).toInt
        val temporalBranch =
          if sharedKey.length == 1 then MptNode.branchWithChild(sharedKeyHead, next, None)
          else MptNode.branchWithChild(sharedKeyHead, MptNode.Extension(sharedKey.tail, next), None)
        val (newBranch, del) = putInBranch(temporalBranch, searchKey, value)
        InsertResult(newBranch, ext :: del.filterNot(_ == temporalBranch))

      case ml if ml == sharedKey.length =>
        // The extension's key is a prefix of the insertion key — recurse into its child.
        val InsertResult(inserted, del) = putInto(next, searchKey.drop(ml), value)
        val newChild = asBranch(inserted, "putInExtension: recurse into child")
        InsertResult(MptNode.Extension(sharedKey, newChild), ext :: del)

      case ml =>
        // Partial shared prefix — split the extension.
        val (sharedKeyPrefix, sharedKeySuffix) = sharedKey.splitAt(ml)
        val temporalExtension = MptNode.Extension(sharedKeySuffix, next)
        val InsertResult(inserted, del) = putInto(temporalExtension, searchKey.drop(ml), value)
        val newBranch = asBranch(inserted, "putInExtension: split extension")
        InsertResult(
          MptNode.Extension(sharedKeyPrefix, newBranch),
          ext :: del.filterNot(_ == temporalExtension)
        )

  private def putInBranch(
      branch: MptNode.Branch,
      searchKey: Array[Byte],
      value: ByteString
  ): (MptNode.Branch, List[MptNode]) =
    val MptNode.Branch(children, _) = branch
    if searchKey.isEmpty then (MptNode.Branch(children, Some(value)), List(branch))
    else
      val head = searchKey(0).toInt
      val remaining = searchKey.tail
      if !children(head).isNull then
        val InsertResult(changedChild, del) = putInto(children(head), remaining, value)
        (branch.updated(head, changedChild), branch :: del)
      else (branch.updated(head, MptNode.Leaf(ByteString(remaining), value)), List(branch))

  private def removeFrom(node: MptNode, searchKey: Array[Byte]): RemoveResult = node match
    case leaf: MptNode.Leaf     => removeFromLeaf(leaf, searchKey)
    case ext: MptNode.Extension => removeFromExtension(ext, searchKey)
    case branch: MptNode.Branch => removeFromBranch(branch, searchKey)
    case MptNode.Hash(ref)      => removeFrom(storage.get(ref), searchKey)
    case MptNode.Null           => throw new MPTException("Cannot delete a Null node")

  private def removeFromLeaf(leaf: MptNode.Leaf, searchKey: Array[Byte]): RemoveResult =
    if leaf.key.toArray.sameElements(searchKey) then RemoveResult(hasChanged = true, None, List(leaf))
    else RemoveResult(hasChanged = false, None)

  private def removeFromBranch(node: MptNode.Branch, searchKey: Array[Byte]): RemoveResult =
    (node, searchKey.isEmpty) match
      case (MptNode.Branch(_, None), true) =>
        RemoveResult(hasChanged = false, None)
      case (MptNode.Branch(children, _), true) =>
        RemoveResult(hasChanged = true, Some(fix(MptNode.Branch(children, None))), List(node))
      case (MptNode.Branch(children, term), false) =>
        val head = searchKey(0).toInt
        children(head) match
          case MptNode.Null =>
            RemoveResult(hasChanged = false, None)
          case child =>
            removeFrom(child, searchKey.tail) match
              case RemoveResult(true, maybeNewChild, del) =>
                val nodeToFix = maybeNewChild match
                  case Some(newChild) => node.updated(head, newChild)
                  case None           => MptNode.Branch(children.updated(head, MptNode.Null), term)
                RemoveResult(hasChanged = true, Some(fix(nodeToFix)), node :: del)
              case RemoveResult(false, _, del) =>
                RemoveResult(hasChanged = false, None, del)

  private def removeFromExtension(ext: MptNode.Extension, searchKey: Array[Byte]): RemoveResult =
    val cp = matchingLength(ext.sharedKey.toArray, searchKey)
    if cp == ext.sharedKey.length then
      removeFrom(ext.next, searchKey.drop(cp)) match
        case RemoveResult(true, Some(newChild), del) =>
          RemoveResult(hasChanged = true, Some(fix(MptNode.Extension(ext.sharedKey, newChild))), ext :: del)
        case RemoveResult(true, None, _) =>
          throw new MPTException("A trie with an extension root should have at least 2 values stored")
        case RemoveResult(false, _, del) =>
          RemoveResult(hasChanged = false, None, del)
    else RemoveResult(hasChanged = false, Some(ext))

  /** Restore a node to a valid state after a removal: a single-child branch collapses to an extension, an empty-valued
    * branch to a leaf, and an extension pointing at another extension/leaf is compacted.
    */
  @tailrec
  private def fix(node: MptNode): MptNode = node match
    case MptNode.Branch(children, optValue) =>
      val usedIndexes =
        children.indices.foldLeft[List[Int]](Nil)((acc, i) => if !children(i).isNull then i :: acc else acc)
      (usedIndexes, optValue) match
        case (Nil, None)          => throw new MPTException("Branch with no subvalues")
        case (index :: Nil, None) => fix(MptNode.Extension(ByteString(Array(index.toByte)), children(index)))
        case (Nil, Some(value))   => MptNode.Leaf(ByteString.empty, value)
        case _                    => node
    case ext @ MptNode.Extension(sharedKey, _) =>
      val nextNode = ext.next match
        case MptNode.Hash(ref) => storage.get(ref)
        case other             => other
      nextNode match
        case MptNode.Extension(subSharedKey, subNext)           => MptNode.Extension(sharedKey ++ subSharedKey, subNext)
        case MptNode.Leaf(subKey, subValue)                     => MptNode.Leaf(sharedKey ++ subKey, subValue)
        case _: MptNode.Branch | _: MptNode.Hash | MptNode.Null => node
    case _ => node

  private def asBranch(node: MptNode, ctx: String): MptNode.Branch = node match
    case b: MptNode.Branch => b
    case other             => throw new MPTException(s"$ctx: expected a Branch, got ${other.getClass.getSimpleName}")

object MerklePatriciaTrie:

  /** A structural inconsistency in the trie — a bug or corrupt input, always raised (fail loud). */
  final class MPTException(message: String) extends RuntimeException(message)

  final private case class InsertResult(newNode: MptNode, toDelete: List[MptNode] = Nil)
  final private case class RemoveResult(hasChanged: Boolean, newNode: Option[MptNode], toDelete: List[MptNode] = Nil)

  /** An empty trie backed by `storage`. */
  def apply[K, V](
      storage: MptStorage
  )(using k: ByteArrayEncoder[K], v: ByteArraySerializable[V]): MerklePatriciaTrie[K, V] =
    new MerklePatriciaTrie[K, V](None, storage)

  /** A trie rooted at an existing `rootHash` in `storage`. */
  def apply[K, V](rootHash: ByteString, storage: MptStorage)(using
      k: ByteArrayEncoder[K],
      v: ByteArraySerializable[V]
  ): MerklePatriciaTrie[K, V] =
    if rootHash == MptNode.EmptyRootHash then new MerklePatriciaTrie[K, V](None, storage)
    else new MerklePatriciaTrie[K, V](Some(MptNode.Hash(rootHash)), storage)

  /** Persist a resident tree into `updater`, returning the force-hashed root reference (always a [[MptNode.Hash]]). */
  private[trie] def store(node: MptNode, updater: NodeUpdater): MptNode = storeNode(node, updater, force = true)

  private def storeNode(node: MptNode, updater: NodeUpdater, force: Boolean): MptNode = node match
    case MptNode.Null    => MptNode.Null
    case h: MptNode.Hash => h
    case _: MptNode.Leaf => persistIfBig(node, updater, force)
    case MptNode.Extension(_, next) =>
      val _ = storeNode(next, updater, force = false)
      persistIfBig(node, updater, force)
    case MptNode.Branch(children, _) =>
      children.foreach(c => storeNode(c, updater, force = false))
      persistIfBig(node, updater, force)

  private def persistIfBig(node: MptNode, updater: NodeUpdater, force: Boolean): MptNode =
    val bytes = node.encoded
    if force || bytes.length >= MptNode.MaxEncodedNodeLength then
      val h = ByteString(kec256(bytes))
      updater.storeNode(Location.Root, NodeHash(h), NodeEncoded(bytes))
      MptNode.Hash(h)
    else node
