package com.chipprbots.fukuii.trie

import org.apache.pekko.util.ByteString

import scala.collection.mutable.LinkedHashMap
import scala.collection.mutable.ListBuffer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** T2a: path-threading root-neutrality + refcount child-hash extraction.
  *
  * Path-threading must be **root-neutral** — [[TrieReferenceVectorsSpec]] proves the state root itself stays
  * byte-exact; here we prove the mechanism: a commit now keys nodes under their real `(None, path)` locations (not the
  * placeholder [[Location.Root]]) while producing the identical root as the hash-keyed reference. The child-hash
  * extraction ([[MptNode.childHashes]]) is validated for correctness and for forge F-1 (no re-emit of an already-stored
  * node) / F-2 (no child-hash referencing an absent node).
  */
class PathThreadingSpec extends AnyFlatSpec with Matchers:

  private val base = ByteArraySerializable.rawByteArraySerializable

  /** A hash-keyed [[MptStorage]] that also records every `(location, hash)` write — lets a test inspect the physical
    * paths a commit threads while still resolving reads by hash alone (so a Hash-rooted re-commit short-circuits).
    */
  final private class CapturingMptStorage extends MptStorage:
    val stored: LinkedHashMap[ByteString, Array[Byte]] =
      scala.collection.mutable.LinkedHashMap.empty[ByteString, Array[Byte]]
    val writes: ListBuffer[(Location, ByteString)] = scala.collection.mutable.ListBuffer.empty[(Location, ByteString)]

    override def loadNode(location: Location, hash: NodeHash): Option[NodeEncoded] =
      stored.get(hash.bytes).map(NodeEncoded.apply)

    override def storeNode(location: Location, hash: NodeHash, value: NodeEncoded): Unit =
      writes += (location -> hash.bytes)
      stored.update(hash.bytes, value.toArray)

  private val fixtureState: Seq[(Array[Byte], Array[Byte])] =
    Seq(
      "doe".getBytes -> "reindeer".getBytes,
      "dog".getBytes -> "puppy".getBytes,
      "dogglesworth".getBytes -> "cat".getBytes,
      "horse".getBytes -> "stallion".getBytes,
      "zebra".getBytes -> "striped".getBytes
    )

  private def committedOver(storage: MptStorage): MerklePatriciaTrie[Array[Byte], Array[Byte]] =
    fixtureState
      .foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](storage)(using base, base)) { case (t, (k, v)) =>
        t.put(k, v)
      }
      .commit()

  private def ref(tag: Byte): ByteString = ByteString(Array.fill(31)(0.toByte) :+ tag)

  // -- path-threading (root-neutral) ----------------------------------------

  "commit" should "store nodes under their real (None, path) keys, not all Root" in {
    val cap = new CapturingMptStorage
    val _ = committedOver(cap)
    assert(
      cap.writes.nonEmpty &&
        // Every stored node lives in the state/account trie (owner = None).
        cap.writes.forall { case (loc, _) => loc.owner == None } &&
        // The root node is at the empty path; the fixture forces branches/extensions, so at least one interior node
        // is stored under a genuinely non-empty nibble path — proving path is threaded, not left at Location.Root.
        cap.writes.map { case (loc, _) => loc.path }.contains(ByteString.empty) &&
        cap.writes.exists { case (loc, _) => loc.path.nonEmpty },
      "commit must write a non-empty set of (None, path)-keyed nodes including both the empty-path root and at least one non-empty interior path"
    )
  }

  it should "produce the identical root as the hash-keyed in-memory reference (path-threading is root-neutral)" in {
    val cap = new CapturingMptStorage
    val reference = new InMemoryMptStorage
    committedOver(cap).getRootHash shouldBe committedOver(reference).getRootHash
  }

  it should "emit only child-hashes that are present in the committed store (no dangling reference, F-2)" in {
    val cap = new CapturingMptStorage
    val _ = committedOver(cap)
    val present = cap.stored.keySet
    cap.stored.foreach { case (_, bytes) =>
      MptNode.decode(bytes).childHashes.foreach(child => present should contain(child))
    }
  }

  // -- forge F-1: no re-emit of an already-stored, unchanged node ------------

  "a re-commit of an already-committed trie" should "write no new nodes (F-1)" in {
    val cap = new CapturingMptStorage
    val committed = committedOver(cap)
    val _ = assert(cap.writes.nonEmpty)
    cap.writes.clear()
    // The committed trie is rooted at a Hash reference — `store` short-circuits it, persisting nothing anew.
    val _ = committed.commit()
    assert(cap.writes.isEmpty)
  }

  // -- child-hash extraction correctness ------------------------------------

  "childHashes" should "yield every populated Branch child ref" in {
    val children = Vector.tabulate(16)(i => MptNode.Hash(ref(i.toByte)))
    MptNode.Branch(children, None).childHashes shouldBe (0 until 16).map(i => ref(i.toByte))
  }

  it should "yield an Extension's next-ref" in {
    MptNode.Extension(ByteString(Array(1.toByte)), MptNode.Hash(ref(9))).childHashes shouldBe Seq(ref(9))
  }

  it should "yield nothing for a Leaf, a Hash, or Null" in {
    assert(
      MptNode.Leaf(ByteString(Array(1.toByte)), ByteString("v".getBytes)).childHashes.isEmpty &&
        MptNode.Hash(ref(1)).childHashes.isEmpty &&
        MptNode.Null.childHashes.isEmpty,
      "a Leaf, a Hash, and Null must all yield no child hashes"
    )
  }

  it should "exclude embedded (< 32-byte) children — they have no store entry to reference (F-2)" in {
    val embedded = MptNode.Leaf(ByteString(Array(1.toByte)), ByteString("x".getBytes))
    val branch =
      MptNode.Branch(Vector(MptNode.Hash(ref(5)), embedded) ++ Vector.fill(14)(MptNode.Null), None)
    assert(
      embedded.encoded.length < MptNode.MaxEncodedNodeLength &&
        branch.childHashes == Seq(ref(5)) &&
        MptNode.Extension(ByteString(Array(2.toByte)), embedded).childHashes.isEmpty,
      "embedded (< 32-byte) children must be excluded from childHashes since they have no store entry to reference"
    )
  }
