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
    assert(
      trie.get(bytes("do")).map(new String(_)) == Some("verb") &&
        trie.get(bytes("dog")).map(new String(_)) == Some("puppy") &&
        trie.get(bytes("doge")).map(new String(_)) == Some("coin") &&
        trie.get(bytes("cat")).isEmpty,
      "every put key must read back its own value, and an absent key must read as None"
    )
  }

  it should "update an existing key" in {
    val trie = emptyTrie.put(bytes("k"), bytes("v1")).put(bytes("k"), bytes("v2"))
    trie.get(bytes("k")).map(new String(_)) shouldBe Some("v2")
  }

  it should "remove a key and restore the prior root on re-insertion" in {
    val base = emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy"))
    val added = base.put(bytes("doge"), bytes("coin"))
    val removed = added.remove(bytes("doge"))
    assert(
      removed.getRootHash == base.getRootHash &&
        removed.get(bytes("doge")).isEmpty &&
        removed.get(bytes("dog")).map(new String(_)) == Some("puppy"),
      "removing the added key must restore the prior root and the prior key's value, and the removed key must read as None"
    )
  }

  it should "return to the empty root when all keys are removed" in {
    val trie = emptyTrie.put(bytes("a"), bytes("1")).put(bytes("b"), bytes("2"))
    trie.remove(bytes("a")).remove(bytes("b")).getRootHash shouldBe MptNode.EmptyRootHash
  }

  it should "produce an inclusion proof ending in the value leaf" in {
    val trie =
      emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy")).put(bytes("doge"), bytes("coin"))
    val proof = trie.getProof(bytes("dog"))
    val terminalValueOk = proof.get.last match
      case MptNode.Leaf(_, value)        => new String(value.toArray) == "puppy"
      case MptNode.Branch(_, Some(term)) => new String(term.toArray) == "puppy"
      case other                         => fail(s"expected value-bearing terminal, got $other")
    assert(
      proof.isDefined &&
        proof.get.nonEmpty &&
        terminalValueOk,
      "the inclusion proof must be defined, non-empty, and end in a value-bearing terminal matching the stored value"
    )
  }

  it should "produce a non-inclusion proof (root included) for an absent key on a non-empty trie" in {
    val trie = emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy"))
    val proof = trie.getProof(bytes("cat"))
    assert(
      proof.isDefined && proof.get.nonEmpty,
      "a non-inclusion proof must still be defined and non-empty (the root is always included)"
    )
  }

  // -- EIP-1186 non-inclusion (go-ethereum trie/proof.go `Prove`) ------------

  it should "anchor the non-inclusion proof at the state root (first node hashes to the root)" in {
    val trie = emptyTrie
      .put(bytes("do"), bytes("verb"))
      .put(bytes("dog"), bytes("puppy"))
      .put(bytes("horse"), bytes("stallion"))
    // A key that diverges at the top branch — the walk stops on the absence-proving node.
    val proof = trie.getProof(bytes("cat")).get
    proof.head.hash shouldBe trie.getRootHash
  }

  it should "end a non-inclusion proof on the node that proves absence (empty branch slot)" in {
    // "do"/"dog" share the "do" prefix; "z" diverges at the very first branch nibble, whose slot is empty.
    val trie = emptyTrie.put(bytes("do"), bytes("verb")).put(bytes("dog"), bytes("puppy"))
    val proof = trie.getProof(bytes("z")).get
    // The proof must not contain a value leaf for the absent key.
    val lastNotVerb = proof.last match
      case MptNode.Leaf(_, value) => new String(value.toArray) != "verb"
      case _                      => true
    assert(
      proof.head.hash == trie.getRootHash && lastNotVerb,
      "the non-inclusion proof must be anchored at the root and must not terminate in a value leaf for the absent key"
    )
  }

  it should "diverge at a leaf whose key mismatches (non-inclusion terminal is that leaf/extension)" in {
    // Single stored key: the root is a leaf; an absent sibling proves absence via that same leaf.
    val trie = emptyTrie.put(bytes("dog"), bytes("puppy"))
    val proof = trie.getProof(bytes("cat")).get
    assert(
      proof.head.hash == trie.getRootHash && proof.size == 1, // just the root leaf, which proves the absence
      "the non-inclusion proof must be anchored at the root and contain just the single root leaf"
    )
  }

  it should "always include the root even when its RLP is < 32 bytes (resident and committed)" in {
    // A single short entry: the root node's own RLP is well under 32 bytes, yet it must appear in the proof.
    val resident = emptyTrie.put(bytes("a"), bytes("b"))
    val residentProof = resident.getProof(bytes("a")).get

    // Same after a store-backed commit (root force-hashed into storage, resolved back on the walk).
    val storage = new InMemoryMptStorage
    val committed = MerklePatriciaTrie[Array[Byte], Array[Byte]](storage).put(bytes("a"), bytes("b")).commit()
    val committedProof = committed.getProof(bytes("a")).get

    assert(
      resident.rootNode.get.encoded.length < MptNode.MaxEncodedNodeLength &&
        residentProof.head.hash == resident.getRootHash &&
        committedProof.head.hash == committed.getRootHash &&
        // Non-inclusion on the committed short-root trie still yields a root-anchored proof.
        committed.getProof(bytes("c")).get.head.hash == committed.getRootHash,
      "the root must always appear in the proof even when its RLP is < 32 bytes, both resident and store-backed committed"
    )
  }

  it should "return None for a proof on an empty trie" in {
    emptyTrie.getProof(bytes("x")) shouldBe None
  }

  // -- L2-F3: loud decode when a node resolved mid-traversal is malformed ----

  it should "fail loud when a node resolved during a get/proof traversal decodes malformed" in {
    // A structurally-valid RLP list of the wrong arity (3 items) stored under the root hash: the traversal decode
    // (storage.get -> MptNode.decode) must reject it, not silently mis-decode.
    val storage = new InMemoryMptStorage
    val rootHash = ByteString(Array.fill[Byte](32)(0x42.toByte))
    val malformed = com.chipprbots.fukuii.rlp.encode(
      com.chipprbots.fukuii.rlp.RLPList(
        com.chipprbots.fukuii.rlp.RLPValue(Array[Byte](1)),
        com.chipprbots.fukuii.rlp.RLPValue(Array[Byte](2)),
        com.chipprbots.fukuii.rlp.RLPValue(Array[Byte](3))
      )
    )
    storage.storeNode(Location.Root, NodeHash(rootHash), NodeEncoded(malformed))
    val trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](rootHash, storage)
    val _ = a[MptNodeDecodeException] should be thrownBy trie.getProof(bytes("anything"))
    a[MptNodeDecodeException] should be thrownBy trie.get(bytes("anything"))
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

    // Reconstruct a fresh trie from the persisted root hash and read every value back.
    val reopened = MerklePatriciaTrie[Array[Byte], Array[Byte]](rootHash, storage)
    assert(
      committed.getRootHash == rootHash &&
        reopened.getRootHash == rootHash &&
        reopened.get(bytes("do")).map(new String(_)) == Some("verb") &&
        reopened.get(bytes("dog")).map(new String(_)) == Some("puppy") &&
        reopened.get(bytes("doge")).map(new String(_)) == Some("coin") &&
        reopened.get(bytes("horse")).map(new String(_)) == Some("stallion"),
      "a store-backed commit and a fresh reopen from the persisted root must preserve the root hash and every value"
    )
  }

  it should "fail loud when a referenced node is missing from storage" in {
    val bogusRoot = ByteString(Array.fill[Byte](32)(0x99.toByte))
    val trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](bogusRoot, new InMemoryMptStorage)
    a[MptNodeDecodeException] should be thrownBy trie.get(bytes("anything"))
  }
