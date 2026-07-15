package com.chipprbots.fukuii.trie

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Functional-trie behaviour: reads/writes, EIP-1186 proofs, the store-backed commit round-trip, and the fail-loud
  * missing-node guard.
  */
class MerklePatriciaTrieSpec extends AnyFlatSpec with Matchers:

  private given ByteArraySerializable[Array[Byte]] = ByteArraySerializable.rawByteArraySerializable

  private def emptyTrie: MerklePatriciaTrie[Array[Byte], Array[Byte]] =
    MerklePatriciaTrie[Array[Byte], Array[Byte]](new InMemoryMptStorage)

  private def bytes(s: String): Array[Byte] = s.getBytes("UTF-8")

  "MerklePatriciaTrie" should "return the empty root for an empty trie" in {
    emptyTrie.getRootHash shouldBe MptNode.EmptyRootHash
  }

  it should "put and get values" in {
    val trie =
      emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy")).put(bytes("doge"), bytes("coin"))
    trie.get(bytes("do")).map(new String(_)) shouldBe Some("verb")
    trie.get(bytes("dog")).map(new String(_)) shouldBe Some("puppy")
    trie.get(bytes("doge")).map(new String(_)) shouldBe Some("coin")
    trie.get(bytes("cat")) shouldBe None
  }

  it should "update an existing key" in {
    val trie = emptyTrie.put(bytes("k"), bytes("v1")).put(bytes("k"), bytes("v2"))
    trie.get(bytes("k")).map(new String(_)) shouldBe Some("v2")
  }

  it should "remove a key and restore the prior root on re-insertion" in {
    val base = emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy"))
    val added = base.put(bytes("doge"), bytes("coin"))
    val removed = added.remove(bytes("doge"))
    removed.getRootHash shouldBe base.getRootHash
    removed.get(bytes("doge")) shouldBe None
    removed.get(bytes("dog")).map(new String(_)) shouldBe Some("puppy")
  }

  it should "return to the empty root when all keys are removed" in {
    val trie = emptyTrie.put(bytes("a"), bytes("1")).put(bytes("b"), bytes("2"))
    trie.remove(bytes("a")).remove(bytes("b")).getRootHash shouldBe MptNode.EmptyRootHash
  }

  it should "produce an inclusion proof ending in the value leaf" in {
    val trie =
      emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy")).put(bytes("doge"), bytes("coin"))
    val proof = trie.getProof(bytes("dog"))
    proof shouldBe defined
    proof.get should not be empty
    proof.get.last match
      case MptNode.Leaf(_, value)        => new String(value.toArray) shouldBe "puppy"
      case MptNode.Branch(_, Some(term)) => new String(term.toArray) shouldBe "puppy"
      case other                         => fail(s"expected value-bearing terminal, got $other")
  }

  it should "produce a non-inclusion proof (root included) for an absent key on a non-empty trie" in {
    val trie = emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy"))
    val proof = trie.getProof(bytes("cat"))
    proof shouldBe defined
    proof.get should not be empty
  }

  it should "return None for a proof on an empty trie" in {
    emptyTrie.getProof(bytes("x")) shouldBe None
  }

  it should "round-trip through a store-backed commit (same root, same values)" in {
    val storage = new InMemoryMptStorage
    val resident = MerklePatriciaTrie[Array[Byte], Array[Byte]](storage)
      .put(bytes("do"), bytes("verb"))
      .put(bytes("dog"), bytes("puppy"))
      .put(bytes("doge"), bytes("coin"))
      .put(bytes("horse"), bytes("stallion"))
    val rootHash = resident.getRootHash

    val committed = resident.commit()
    committed.getRootHash shouldBe rootHash

    // Reconstruct a fresh trie from the persisted root hash and read every value back.
    val reopened = MerklePatriciaTrie[Array[Byte], Array[Byte]](rootHash, storage)
    reopened.getRootHash shouldBe rootHash
    reopened.get(bytes("do")).map(new String(_)) shouldBe Some("verb")
    reopened.get(bytes("dog")).map(new String(_)) shouldBe Some("puppy")
    reopened.get(bytes("doge")).map(new String(_)) shouldBe Some("coin")
    reopened.get(bytes("horse")).map(new String(_)) shouldBe Some("stallion")
  }

  it should "fail loud when a referenced node is missing from storage" in {
    val bogusRoot = ByteString(Array.fill[Byte](32)(0x99.toByte))
    val trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](bogusRoot, new InMemoryMptStorage)
    a[MptNodeDecodeException] should be thrownBy trie.get(bytes("anything"))
  }
