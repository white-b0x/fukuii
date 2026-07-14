package com.chipprbots.fukuii.domain

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.crypto.kec256

/** Byte-exact against go-ethereum `core/types/bloom9_test.go` — the `bloomValues`/`Add` bit-index scheme, not just
  * "some" bloom filter shape.
  */
class BloomSpec extends AnyFunSuite:

  test("TestBloomExtensively vector: 100 adds, keccak256(bloom bytes) matches go-ethereum"):
    val bloom = (0 until 100).foldLeft(Bloom.Empty) { (b, i) =>
      b.add(s"xxxxxxxxxx data $i yyyyyyyyyyyyyy".getBytes)
    }
    val got = Hex.toHexString(kec256(bloom.toArray))
    assert(got == "c8d3ca65cdb4874300a9e39475508f23ed6da09fdbc487f89a2dcf50b09eb263")

  test("Empty is 256 zero bytes"):
    assert(Bloom.Empty.toArray.length == Bloom.Length)
    assert(Bloom.Empty.toArray.forall(_ == (0: Byte)))

  test("of(logs) aggregates every log's address and topics into one filter"):
    val addr1 = Address.fromHex("0x00000000000000000000000000000000000011")
    val addr2 = Address.fromHex("0x00000000000000000000000000000000000022")
    val topic1 = Hash.fromHex("0x" + ("aa" * 32))

    val log1 = Log(addr1, List(topic1), org.apache.pekko.util.ByteString.empty)
    val log2 = Log(addr2, Nil, org.apache.pekko.util.ByteString.empty)

    val aggregated = Bloom.of(List(log1, log2))
    val incremental = Bloom.Empty.add(log1).add(log2)
    assert(aggregated.toArray.sameElements(incremental.toArray))

  test("apply is strict — wrong-length input fails loud"):
    intercept[IllegalArgumentException](Bloom(Array.fill[Byte](255)(0)))
    intercept[IllegalArgumentException](Bloom(Array.fill[Byte](257)(0)))
