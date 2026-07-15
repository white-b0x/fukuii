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

  // L1-F1: anchor the logs->bloom path to the byte-golden `add(data)` primitive. The 100-add
  // `TestBloomExtensively` vector above pins `add(data)` byte-exact to go-ethereum; go-ethereum's `CreateBloom`
  // (`bloom9.go:107-119`) then defines a log's contribution as `add(address)` followed by `add(each topic)`, so a
  // log's full 256-byte bloom MUST equal the raw-add composition of exactly those bytes. This makes the
  // logs->bloom path byte-exact-to-go-ethereum transitively through the verified primitive. (A direct external
  // logs->bloom fixture is not present in the vendored ethereum/tests — bloom9_test.go carries only the raw-add
  // keccak golden + a membership test — and the aggregate is additionally covered by the golden Cancun header's
  // logsBloom.)
  test("L1-F1: a log's bloom is go-ethereum CreateBloom = add(address) then add(each topic), byte-exact"):
    val addr = Address.fromHex("0x1111111111111111111111111111111111111111")
    val topicA = Hash.fromHex("0x" + ("22" * 32))
    val topicB = Hash.fromHex("0x" + ("33" * 32))
    val log = Log(addr, List(topicA, topicB), org.apache.pekko.util.ByteString.empty)

    val viaLog = Bloom.of(List(log))
    // the go-ethereum CreateBloom composition, expressed via the byte-golden raw-add primitive
    val viaComposition = Bloom.Empty.add(addr.toArray).add(topicA.toArray).add(topicB.toArray)
    assert(viaLog.toArray.sameElements(viaComposition.toArray))
    // and it is genuinely non-empty (exactly 3 bits per distinct add) — not a degenerate all-zero pass
    assert(viaLog.toArray.exists(_ != (0: Byte)))

  test("apply is strict — wrong-length input fails loud"):
    intercept[IllegalArgumentException](Bloom(Array.fill[Byte](255)(0)))
    intercept[IllegalArgumentException](Bloom(Array.fill[Byte](257)(0)))
