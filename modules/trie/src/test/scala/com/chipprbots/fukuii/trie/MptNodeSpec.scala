package com.chipprbots.fukuii.trie

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.RLPValue
import com.chipprbots.fukuii.rlp.encode as encodeRlp

/** Node-level byte-exact vectors: empty root, node RLP round-trips, the `< 32` inline threshold, hex-prefix compaction,
  * and the L2-F3 fail-loud decode guard. All values transcribed from the S0 reference map / geth.
  */
class MptNodeSpec extends AnyFlatSpec with Matchers:

  private def nibbles(bs: Int*): ByteString = ByteString(bs.map(_.toByte).toArray)

  "MptNode" should "produce the canonical empty-trie root keccak(0x80)" in {
    assert(
      Hex.toHexString(MptNode.EmptyRootHash.toArray) ==
        "56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421" &&
        MptNode.EmptyEncoded.sameElements(Array(0x80.toByte)),
      "the empty-trie root hash must be keccak(0x80) and EmptyEncoded must be the bare byte 0x80"
    )
  }

  it should "encode Null as the bare RLP empty string 0x80, never a 1-item list" in {
    assert(
      MptNode.toRlp(MptNode.Null) == RLPValue(Array.emptyByteArray) &&
        encodeRlp(MptNode.toRlp(MptNode.Null)).sameElements(Array(0x80.toByte)),
      "MptNode.Null must encode as the bare RLP empty string 0x80, never a 1-item list"
    )
  }

  it should "round-trip a leaf node through RLP encode/decode" in {
    val leaf = MptNode.Leaf(nibbles(1, 2, 3, 4, 5), ByteString("hello".getBytes))
    MptNode.decode(leaf.encoded) shouldBe leaf
  }

  it should "round-trip a branch with hash-ref children and a terminator" in {
    val ref1 = ByteString(Array.fill[Byte](32)(0x11))
    val ref2 = ByteString(Array.fill[Byte](32)(0x22))
    val children = Vector.tabulate(16) {
      case 3  => MptNode.Hash(ref1)
      case 10 => MptNode.Hash(ref2)
      case _  => MptNode.Null
    }
    val branch = MptNode.Branch(children, Some(ByteString("val".getBytes)))
    MptNode.decode(branch.encoded) shouldBe branch
  }

  it should "inline a child whose RLP is < 32 bytes and hash-ref a child whose RLP is >= 32 bytes" in {
    val smallLeaf = MptNode.Leaf(nibbles(1), ByteString(Array[Byte](2)))
    val bigLeaf = MptNode.Leaf(nibbles(2), ByteString(Array.fill[Byte](40)(7)))
    val _ = assert(
      smallLeaf.encoded.length < 32 && bigLeaf.encoded.length >= 32,
      "the small leaf must encode under 32 bytes and the big leaf at 32 bytes or more"
    )

    val children = Vector.tabulate(16) {
      case 1 => smallLeaf
      case 2 => bigLeaf
      case _ => MptNode.Null
    }
    val branch = MptNode.Branch(children, None)
    val decoded = MptNode.decode(branch.encoded)
    inside(decoded) { case MptNode.Branch(dc, None) =>
      assert(
        dc(1) == smallLeaf && // inlined, resident
          dc(2) == MptNode.Hash(ByteString(com.chipprbots.fukuii.crypto.kec256(bigLeaf.encoded))), // hash-ref
        "the small child must be inlined and the big child must be hash-referenced"
      )
    }
  }

  "HexPrefix" should "pack odd-length extension nibbles (bit 4 set, bit 5 clear)" in {
    HexPrefix.encode(Array[Byte](1, 2, 3, 4, 5), isLeaf = false) shouldBe Array(0x11.toByte, 0x23, 0x45)
  }

  it should "pack even-length leaf nibbles (bit 5 set, zero pad nibble)" in {
    HexPrefix.encode(Array[Byte](0, 1, 2, 3, 4, 5), isLeaf = true) shouldBe
      Array(0x20.toByte, 0x01, 0x23, 0x45)
  }

  it should "round-trip nibbles for every (parity x leaf/ext) combination" in {
    val cases = Seq(
      Array[Byte](1, 2, 3), // odd
      Array[Byte](1, 2, 3, 4), // even
      Array.emptyByteArray, // empty (even)
      Array[Byte](15) // odd, single high nibble
    )
    for
      key <- cases
      isLeaf <- Seq(true, false)
    do
      val (decoded, leaf) = HexPrefix.decode(HexPrefix.encode(key, isLeaf))
      assert(
        decoded.sameElements(key) && leaf == isLeaf,
        s"HexPrefix must round-trip nibbles ${key.toSeq} (isLeaf=$isLeaf)"
      )
  }

  "MptNode.decode (L2-F3 fail-loud)" should "raise on a wrong-arity list (3 items)" in {
    val threeItemList = encodeRlp(RLPList(RLPValue(Array[Byte](1)), RLPValue(Array[Byte](2)), RLPValue(Array[Byte](3))))
    a[MptNodeDecodeException] should be thrownBy MptNode.decode(threeItemList)
  }

  it should "raise on an oversized (>= 32-byte) embedded node" in {
    // A branch whose child slot 0 is an inlined list that encodes to >= 32 bytes (should have been a hash ref).
    val oversizedChild = RLPList(RLPValue(Array.fill[Byte](40)(1)), RLPValue(Array.fill[Byte](40)(2)))
    val slots = oversizedChild +: Vector.fill(16)(RLPValue(Array.emptyByteArray))
    val branch = encodeRlp(RLPList(slots*))
    a[MptNodeDecodeException] should be thrownBy MptNode.decode(branch)
  }

  it should "raise on a bad hex-prefix flag byte" in {
    // High nibble 0x4 has bit 6 set — not a valid HP flag.
    val badLeaf = encodeRlp(RLPList(RLPValue(Array(0x40.toByte, 0x12)), RLPValue(Array[Byte](9))))
    a[MptNodeDecodeException] should be thrownBy MptNode.decode(badLeaf)
  }

  it should "raise on a standalone value that is neither empty nor 32 bytes" in {
    val badValue = encodeRlp(RLPValue(Array.fill[Byte](10)(1)))
    a[MptNodeDecodeException] should be thrownBy MptNode.decode(badValue)
  }

  it should "raise on structurally invalid RLP bytes" in {
    a[MptNodeDecodeException] should be thrownBy MptNode.decode(Array(0xf8.toByte, 0x05, 0x01))
  }

  private def inside[A](a: A)(pf: PartialFunction[A, org.scalatest.Assertion]): org.scalatest.Assertion =
    if pf.isDefinedAt(a) then pf(a) else fail(s"value did not match: $a")
