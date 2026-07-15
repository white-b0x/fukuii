package com.chipprbots.fukuii.trie

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.fukuii.rlp.RLPException

/** T2b: the besu-Bonsai `{prior, updated}` leaf-diff `TrieLog` — the R7 reorg-event source.
  *
  * Proves the load-bearing properties: leaf-keyed (not node-hash), serialize→deserialize round-trip byte-stable,
  * `rollForward`/`rollBack` reach the sibling state **byte-exactly** (the state root of the reconstructed trie
  * matches), capture is root-neutral (journaling never perturbs the trie it observed), and the storage boundary is
  * byte-pure (the journal hands out only serialized bytes). L2-F3 loud-decode is exercised on a malformed journal blob.
  */
class TrieLogSpec extends AnyFlatSpec with Matchers:

  private given ByteArraySerializable[Array[Byte]] = ByteArraySerializable.rawByteArraySerializable

  private def emptyTrie: MerklePatriciaTrie[Array[Byte], Array[Byte]] =
    MerklePatriciaTrie[Array[Byte], Array[Byte]](new InMemoryMptStorage)

  private def bs(s: String): ByteString = ByteString(s.getBytes("UTF-8"))
  private def bytes(s: String): Array[Byte] = s.getBytes("UTF-8")

  // -- LeafChange semantics (besu LogTuple) ---------------------------------

  "LeafChange" should "classify insert / update / delete / unchanged" in {
    LeafChange(None, Some(bs("v"))).isUnchanged shouldBe false
    LeafChange(Some(bs("a")), Some(bs("b"))).isUnchanged shouldBe false
    LeafChange(Some(bs("a")), Some(bs("a"))).isUnchanged shouldBe true
    LeafChange(Some(bs("a")), None).isCleared shouldBe true
    LeafChange(None, Some(bs("v"))).isCleared shouldBe false
  }

  // -- serialize -> deserialize round-trip ----------------------------------

  "a TrieLog" should "round-trip through serialize -> deserialize byte-stably" in {
    val log = TrieLog
      .Builder()
      .record(bs("dog"), None, Some(bs("puppy")))
      .record(bs("horse"), Some(bs("mare")), Some(bs("stallion")))
      .record(bs("zebra"), Some(bs("striped")), None)
      .build
    val blob = log.serialized
    val restored = TrieLog.deserialize(blob)
    restored shouldBe log
    // Re-serialising the restored log yields the identical bytes (canonical form is stable).
    restored.serialized shouldBe blob
  }

  it should "serialize identically regardless of the order changes were recorded (canonical sort)" in {
    val a = TrieLog
      .Builder()
      .record(bs("zebra"), Some(bs("striped")), None)
      .record(bs("dog"), None, Some(bs("puppy")))
      .build
    val b = TrieLog
      .Builder()
      .record(bs("dog"), None, Some(bs("puppy")))
      .record(bs("zebra"), Some(bs("striped")), None)
      .build
    a.serialized shouldBe b.serialized
  }

  it should "round-trip the empty journal" in {
    TrieLog.deserialize(TrieLog.empty.serialized) shouldBe TrieLog.empty
  }

  // -- rollForward / rollBack reach the sibling state byte-exactly -----------

  "rollForward" should "advance the prior-state trie to the updated-state root byte-exactly" in {
    val prior = emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy"))
    val updated = prior
      .put(bytes("dog"), bytes("hound")) // update
      .put(bytes("doge"), bytes("coin")) // insert
      .remove(bytes("do")) // delete

    val log = TrieLog.diff(prior, updated, Seq(bs("do"), bs("dog"), bs("doge")))
    log.rollForward(prior).getRootHash shouldBe updated.getRootHash
  }

  "rollBack" should "revert the updated-state trie to the prior-state root byte-exactly" in {
    val prior = emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy"))
    val updated = prior
      .put(bytes("dog"), bytes("hound"))
      .put(bytes("doge"), bytes("coin"))
      .remove(bytes("do"))

    val log = TrieLog.diff(prior, updated, Seq(bs("do"), bs("dog"), bs("doge")))
    log.rollBack(updated).getRootHash shouldBe prior.getRootHash
  }

  it should "restore the exact prior leaf values (a committed change, then rollBack, restores the prior)" in {
    val prior = emptyTrie.put(bytes("k1"), bytes("v1")).put(bytes("k2"), bytes("v2"))
    val updated = prior.put(bytes("k1"), bytes("v1-new")).remove(bytes("k2")).put(bytes("k3"), bytes("v3"))

    val log = TrieLog.diff(prior, updated, Seq(bs("k1"), bs("k2"), bs("k3")))
    val reverted = log.rollBack(updated)

    reverted.getRootHash shouldBe prior.getRootHash
    new String(reverted.get(bytes("k1")).get) shouldBe "v1"
    new String(reverted.get(bytes("k2")).get) shouldBe "v2"
    reverted.get(bytes("k3")) shouldBe None
  }

  it should "round-trip a state through deserialize before rolling (the out-of-process consumer path)" in {
    val prior = emptyTrie.put(bytes("a"), bytes("1")).put(bytes("b"), bytes("2"))
    val updated = prior.put(bytes("a"), bytes("11")).put(bytes("c"), bytes("3"))
    val log = TrieLog.diff(prior, updated, Seq(bs("a"), bs("b"), bs("c")))

    // Simulate crossing a process boundary: serialize, ship bytes, rehydrate, then roll.
    val shipped = TrieLog.deserialize(log.serialized)
    shipped.rollForward(prior).getRootHash shouldBe updated.getRootHash
    shipped.rollBack(updated).getRootHash shouldBe prior.getRootHash
  }

  // -- root-neutrality: journaling never perturbs the observed trie ----------

  "capturing a TrieLog" should "not change the observed tries' roots (root-neutral side-journal)" in {
    val prior = emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy"))
    val updated = prior.put(bytes("doge"), bytes("coin"))
    val priorRootBefore = prior.getRootHash
    val updatedRootBefore = updated.getRootHash

    val _ = TrieLog.diff(prior, updated, Seq(bs("do"), bs("dog"), bs("doge")))

    prior.getRootHash shouldBe priorRootBefore
    updated.getRootHash shouldBe updatedRootBefore
  }

  it should "drop entries that netted out unchanged" in {
    val t = emptyTrie.put(bytes("x"), bytes("1"))
    // Same trie on both sides: nothing changed.
    TrieLog.diff(t, t, Seq(bs("x"))).changes shouldBe empty
    // Builder coalesces a no-op transition.
    TrieLog.Builder().record(bs("x"), Some(bs("1")), Some(bs("1"))).build.changes shouldBe empty
  }

  // -- L2-F3: loud decode on a malformed journal blob -----------------------

  "deserialize" should "fail loud (RLPException) on a malformed / truncated journal blob" in {
    a[RLPException] should be thrownBy TrieLog.deserialize(ByteString(Array[Byte](0x01, 0x02, 0x03)))
  }

  it should "reject trailing bytes past the single record (strict decode)" in {
    val valid = TrieLog.Builder().record(bs("k"), None, Some(bs("v"))).build.serialized
    a[RLPException] should be thrownBy TrieLog.deserialize(valid ++ ByteString(Array[Byte](0x00)))
  }
