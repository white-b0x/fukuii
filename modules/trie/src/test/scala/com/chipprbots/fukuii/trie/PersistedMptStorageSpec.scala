package com.chipprbots.fukuii.trie

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.fukuii.storage.EphemDataSource
import com.chipprbots.fukuii.storage.HashKeyedNodeStorage
import com.chipprbots.fukuii.storage.PathKeyedNodeStorage

/** S2 gate: the same state committed under a hash-keyed profile and a path-keyed profile must produce the
  * byte-identical state root — only the on-disk key layout differs, never the content-addressed commitment (geth
  * `accessors_trie.go:184-201` writes the identical node blob under either key).
  */
class PersistedMptStorageSpec extends AnyFlatSpec with Matchers:

  private val base = ByteArraySerializable.rawByteArraySerializable

  private def rootOverStorage(storage: MptStorage, pairs: Seq[(Array[Byte], Array[Byte])]): ByteString =
    val committed = pairs
      .foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](storage)(using base, base)) { case (trie, (k, v)) =>
        trie.put(k, v)
      }
      .commit()
    committed.getRootHash

  private val fixtureState: Seq[(Array[Byte], Array[Byte])] =
    Seq(
      "doe".getBytes -> "reindeer".getBytes,
      "dog".getBytes -> "puppy".getBytes,
      "dogglesworth".getBytes -> "cat".getBytes,
      "horse".getBytes -> "stallion".getBytes,
      "zebra".getBytes -> "striped".getBytes
    )

  "the same committed state" should "produce an identical root under hash-keyed and path-keyed storage" in {
    val hashRoot = rootOverStorage(new PersistedMptStorage(new HashKeyedNodeStorage(EphemDataSource())), fixtureState)
    val pathRoot = rootOverStorage(new PersistedMptStorage(new PathKeyedNodeStorage(EphemDataSource())), fixtureState)
    hashRoot shouldBe pathRoot
  }

  it should "produce the same root as the in-memory reference storage" in {
    val referenceRoot = rootOverStorage(new InMemoryMptStorage, fixtureState)
    val hashRoot = rootOverStorage(new PersistedMptStorage(new HashKeyedNodeStorage(EphemDataSource())), fixtureState)
    hashRoot shouldBe referenceRoot
  }

  "an empty trie" should "have the canonical empty root under both keying schemes" in {
    val expected = "56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"
    val hashRoot = rootOverStorage(new PersistedMptStorage(new HashKeyedNodeStorage(EphemDataSource())), Seq.empty)
    val pathRoot = rootOverStorage(new PersistedMptStorage(new PathKeyedNodeStorage(EphemDataSource())), Seq.empty)
    com.chipprbots.fukuii.bytes.Hex.toHexString(hashRoot.toArray) shouldBe expected
    com.chipprbots.fukuii.bytes.Hex.toHexString(pathRoot.toArray) shouldBe expected
  }

  it should "round-trip after being reloaded from persisted (non-resident) storage" in {
    val dataSource = EphemDataSource()
    val nodeStorage = new HashKeyedNodeStorage(dataSource)
    val committedRoot = rootOverStorage(new PersistedMptStorage(nodeStorage), fixtureState)

    // A fresh trie instance, rooted at the persisted hash, backed by a NEW PersistedMptStorage over the SAME
    // underlying DataSource — proves the nodes are genuinely retrievable from storage, not merely held resident.
    val reloaded =
      MerklePatriciaTrie[Array[Byte], Array[Byte]](
        committedRoot,
        new PersistedMptStorage(new HashKeyedNodeStorage(dataSource))
      )(using
        base,
        base
      )
    fixtureState.foreach { case (k, v) => reloaded.get(k).map(_.toSeq) shouldBe Some(v.toSeq) }
  }
