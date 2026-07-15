package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString
import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.RLPValue
import com.chipprbots.fukuii.rlp.encode as rlpEncode
import com.chipprbots.fukuii.rlp.rawDecode

/** Fork-variant [[BlockHeader]] RLP: per-fork trailing-tail round-trips asserting the **exact tail length at each
  * fork** (ETH legacy → London → Shanghai → Cancun → Prague → Osaka+), the ETC pre-Olympia zero-tail, the mid-run-gap
  * rejection, the open-tail (no fixed max count) property, and a golden byte-exact hash against a go-ethereum-produced
  * `ethereum/tests` Cancun vector. The consensus-critical byte-alignment gate for the header.
  */
class BlockHeaderSpec extends AnyFunSuite:

  private val h0 = Hash.fromHex("0x0000000000000000000000000000000000000000000000000000000000000000")
  private val hA = Hash.fromHex("0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")
  private val hB = Hash.fromHex("0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")
  private val coinbase = Address.fromHex("0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba")

  /** A minimal legacy (pre-fork) header — all eight trailing-optionals default to `None`. This is the immutable
    * per-fork factory's base (besu `BlockHeaderBuilder` shape): higher forks are `.with*` chains onto it.
    */
  private def legacyHeader: BlockHeader = BlockHeader(
    parentHash = h0,
    ommersHash = hA,
    beneficiary = coinbase,
    stateRoot = hB,
    transactionsRoot = hB,
    receiptsRoot = hB,
    logsBloom = Bloom.Empty,
    difficulty = BigInt(0),
    number = BigInt(0),
    gasLimit = 0x7fffffffffffffffL,
    gasUsed = 0,
    unixTimestamp = 0,
    extraData = ByteString.empty,
    mixHash = h0,
    nonce = ByteString(Array.fill[Byte](8)(0))
  )

  /** The count of RLP elements a header encodes to = 15 fixed + the present trailing tail. */
  private def elementCount(h: BlockHeader): Int = rawDecode(rlpEncode(h)) match
    case list: RLPList => list.items.length
    case other         => fail(s"expected an RLPList, got $other")

  test("legacy header: 15 elements (zero trailing) and round-trips"):
    val h = legacyHeader
    assert(elementCount(h) == 15)
    assert(summon[RLPCodec[BlockHeader]].decode(rawDecode(rlpEncode(h))) == h)

  test("ETC pre-Olympia header stays at zero trailing fields (a base-fee field would be a consensus bug)"):
    // A network-neutral header with no populated trailing-optionals — the ETC-family shape — is exactly the bare 15.
    assert(legacyHeader.baseFeePerGas.isEmpty)
    assert(elementCount(legacyHeader) == 15)

  test("London header: +baseFee → 16 elements, tail = [baseFee]"):
    val h = legacyHeader.withBaseFeePerGas(BigInt(0x042c1d80L))
    assert(elementCount(h) == 16)
    assert(roundTrip(h) == h)

  test("Shanghai header: +withdrawalsRoot → 17 elements"):
    val h = legacyHeader.withBaseFeePerGas(BigInt(7)).withWithdrawalsRoot(hB)
    assert(elementCount(h) == 17)
    assert(roundTrip(h) == h)

  test("Cancun header: +blobGasUsed,+excessBlobGas,+parentBeaconRoot → 20 elements"):
    val h = legacyHeader
      .withBaseFeePerGas(BigInt(7))
      .withWithdrawalsRoot(hB)
      .withBlobGas(used = 131072, excess = 262144)
      .withParentBeaconBlockRoot(h0)
    assert(elementCount(h) == 20)
    assert(roundTrip(h) == h)

  test("Prague header: +requestsHash → 21 elements"):
    val h = cancunHeader.withRequestsHash(hB)
    assert(elementCount(h) == 21)
    assert(roundTrip(h) == h)

  test("Osaka+ header: +blockAccessListHash,+slotNumber → 23 elements (the current full 8-field tail)"):
    val h = cancunHeader.withRequestsHash(hB).withBlockAccessListHash(hA).withSlotNumber(99)
    assert(elementCount(h) == 23)
    assert(roundTrip(h) == h)

  test("mid-run gap is rejected on encode (excessBlobGas present without blobGasUsed)"):
    // Construct an illegal in-memory tail: baseFee present (slot 0), then a gap, then excessBlobGas (slot 3).
    val gapped = legacyHeader.copy(baseFeePerGas = Some(BigInt(7)), excessBlobGas = Some(1L))
    val ex = intercept[RLPException](rlpEncode(gapped))
    assert(ex.getMessage.contains("mid-run gap"))

  test("mid-run gap is rejected when the very first trailing field is absent (withdrawalsRoot without baseFee)"):
    val gapped = legacyHeader.copy(withdrawalsRoot = Some(hB))
    assert(intercept[RLPException](rlpEncode(gapped)).getMessage.contains("mid-run gap"))

  test("open tail: an unknown future 9th trailing field does not crash the decoder"):
    // Take a full 23-element (current-max) header and append a synthetic 24th element — a future ETH fork's field
    // this build does not model. The decoder must tolerate it (decode the known 8, ignore the rest), never throw.
    val full = cancunHeader.withRequestsHash(hB).withBlockAccessListHash(hA).withSlotNumber(99)
    rawDecode(rlpEncode(full)) match
      case list: RLPList =>
        val extended = RLPList((list.items :+ RLPValue(Array[Byte](0x2a)))*)
        val decoded = summon[RLPCodec[BlockHeader]].decode(extended)
        // The eight known fields survive intact; the unknown 9th is tolerated, not decoded.
        assert(decoded.slotNumber.contains(99L))
        assert(decoded.blockAccessListHash.contains(hA))
      case other => fail(s"expected an RLPList, got $other")

  test("the codec asserts no fixed max field count — decodes a header with more trailing items than known fields"):
    // Directly hand a decoder a 25-element list (15 fixed + 8 known + 2 unknown-future). No arity match rejects it.
    val full = cancunHeader.withRequestsHash(hB).withBlockAccessListHash(hA).withSlotNumber(99)
    rawDecode(rlpEncode(full)) match
      case list: RLPList =>
        val extended = RLPList((list.items :+ RLPValue(Array[Byte](1)) :+ RLPValue(Array[Byte](2)))*)
        val decoded = summon[RLPCodec[BlockHeader]].decode(extended)
        assert(decoded.slotNumber.contains(99L))
      case other => fail(s"expected an RLPList, got $other")

  test("header.hash is keccak256 of the header RLP"):
    val h = legacyHeader
    assert(h.hash == Hash(ByteString(kec256(rlpEncode(h)))))

  // Golden vector — the genesis block of ethereum/tests BlockchainTests/ValidBlocks/bcExample/basefeeExample.json
  // (network: Cancun). The header carries the contiguous 5-field tail baseFee → withdrawalsRoot → blobGasUsed →
  // excessBlobGas → parentBeaconBlockRoot. Decoding the full block (flat extblock) and re-hashing the header must
  // reproduce go-ethereum's published block hash byte-for-byte — the cross-client byte-alignment proof.
  private val genesisRlpHex =
    "0xf90246f90240a00000000000000000000000000000000000000000000000000000000000000000a01dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347942adc25665018aa1fe0e6bc666dac8fc2697ff9baa0c9f38211bd47d18248e2bd461131b4b454dde6dd63ab70d57e157d2fe058b342a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421b90100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000008080887fffffffffffffff808203b642a0000000000000000000000000000000000000000000000000000000000002000088000000000000000084042c1d80a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b4218080a00000000000000000000000000000000000000000000000000000000000000000c0c0c0"
  private val genesisHash = Hash.fromHex("0x3f820e969b47b806b306aaedf2ad93769b6e53c60d3dbc2af6693f9a2279ec43")

  test("golden Cancun vector: decoded header re-hashes to go-ethereum's published block hash"):
    val block = summon[RLPCodec[Block]].decode(rawDecode(Hex.decode(genesisRlpHex)))
    // The five-field contiguous Cancun tail decoded correctly.
    assert(block.header.baseFeePerGas.contains(BigInt(0x042c1d80L)))
    assert(block.header.withdrawalsRoot.isDefined)
    assert(block.header.blobGasUsed.contains(0L))
    assert(block.header.excessBlobGas.contains(0L))
    assert(block.header.parentBeaconBlockRoot.isDefined)
    assert(block.header.requestsHash.isEmpty)
    // The hash matches — proves the fixed-15 + 5-field-tail RLP is byte-exact to go-ethereum.
    assert(block.header.hash == genesisHash)
    assert(block.hash == genesisHash)

  test("golden Cancun vector: header re-encodes byte-for-byte (round-trip stability)"):
    val block = summon[RLPCodec[Block]].decode(rawDecode(Hex.decode(genesisRlpHex)))
    assert(roundTrip(block.header) == block.header)

  private def roundTrip(h: BlockHeader): BlockHeader =
    summon[RLPCodec[BlockHeader]].decode(rawDecode(rlpEncode(h)))

  private def cancunHeader: BlockHeader = legacyHeader
    .withBaseFeePerGas(BigInt(7))
    .withWithdrawalsRoot(hB)
    .withBlobGas(used = 131072, excess = 262144)
    .withParentBeaconBlockRoot(h0)
