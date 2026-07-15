package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.RLPValue
import com.chipprbots.fukuii.rlp.encode as rlpEncode
import com.chipprbots.fukuii.rlp.rawDecode
import org.scalatest.funsuite.AnyFunSuite

/** [[BlockBody]] RLP: the trailing-optional `withdrawals` list (present post-Shanghai ETH, omitted for ETC /
  * pre-Shanghai), and the legacy-vs-typed transaction nesting (a typed tx wraps as an RLP byte string inside the
  * transaction list, byte-exact to go-ethereum's `[]*Transaction` encoding).
  */
class BlockBodySpec extends AnyFunSuite:

  private val toAddress = Address.fromHex("0x095e7baea6a6c7c4c2dfeb977efac326af552d87")

  private def legacyTx: Transaction.Legacy = Transaction.Legacy(
    nonce = UInt256(5),
    gasPrice = Wei(UInt256(1000000000L)),
    gasLimit = UInt256(21000),
    to = Some(toAddress),
    value = Wei(UInt256(100)),
    payload = ByteString.empty,
    signature = ECDSASignature(BigInt(1), BigInt(2), BigInt(27))
  )

  private def accessListTx: Transaction.AccessList = Transaction.AccessList(
    chainId = ChainId(1),
    nonce = UInt256(0),
    gasPrice = Wei(UInt256(1000000000L)),
    gasLimit = UInt256(21000),
    to = Some(toAddress),
    value = Wei(UInt256(100)),
    payload = ByteString.empty,
    accessList = Nil,
    signature = ECDSASignature(BigInt(1), BigInt(2), BigInt(0))
  )

  private def withdrawal(i: Long): Withdrawal = Withdrawal(i, i + 1, toAddress, 32000000000L)

  private def roundTrip(body: BlockBody): BlockBody =
    summon[RLPCodec[BlockBody]].decode(rawDecode(rlpEncode(body)))

  test("ETC / pre-Shanghai body: withdrawals omitted → 2 elements, round-trips with None"):
    val body = BlockBody(List(legacyTx), Nil, None)
    rawDecode(rlpEncode(body)) match
      case list: RLPList => assert(list.items.length == 2)
      case other         => fail(s"expected a 2-element RLPList, got $other")
    val decoded = roundTrip(body)
    assert(decoded.withdrawals.isEmpty)
    assert(decoded == body)

  test("post-Shanghai body: withdrawals present → 3 elements, round-trips with Some"):
    val body = BlockBody(List(legacyTx), Nil, Some(List(withdrawal(0), withdrawal(1))))
    rawDecode(rlpEncode(body)) match
      case list: RLPList => assert(list.items.length == 3)
      case other         => fail(s"expected a 3-element RLPList, got $other")
    val decoded = roundTrip(body)
    assert(decoded.withdrawals.contains(List(withdrawal(0), withdrawal(1))))
    assert(decoded == body)

  test("empty post-Shanghai body: withdrawals present but empty is distinct from omitted"):
    val present = BlockBody(Nil, Nil, Some(Nil))
    val omitted = BlockBody(Nil, Nil, None)
    assert(rlpEncode(present).sameElements(rlpEncode(omitted)) == false)
    assert(roundTrip(present).withdrawals.contains(Nil))
    assert(roundTrip(omitted).withdrawals.isEmpty)

  test("a legacy tx nests as a bare RLP list item; a typed tx nests as an RLP byte string"):
    val body = BlockBody(List(legacyTx, accessListTx), Nil, None)
    rawDecode(rlpEncode(body)) match
      case RLPList(txs: RLPList, _, _*) =>
        assert(txs.items.length == 2)
        assert(txs.items(0).isInstanceOf[RLPList], "legacy tx should be a bare list")
        assert(txs.items(1).isInstanceOf[RLPValue], "typed tx should be a wrapped byte string")
      case other => fail(s"expected [txs, uncles], got $other")

  test("mixed legacy + typed transaction body round-trips"):
    val body = BlockBody(List(legacyTx, accessListTx), Nil, Some(List(withdrawal(0))))
    val decoded = roundTrip(body)
    assert(decoded.transactionList == List(legacyTx, accessListTx))
    assert(decoded == body)

  test("uncle headers nest and round-trip inside the body"):
    val uncle = BlockHeaderSpecFixtures.sampleHeader
    val body = BlockBody(Nil, List(uncle), None)
    assert(roundTrip(body).uncleNodesList == List(uncle))

  test("full Block flat extblock: [header, txs, uncles, withdrawals] round-trips and hash follows the header"):
    val header = BlockHeaderSpecFixtures.sampleHeader
    val body = BlockBody(List(legacyTx, accessListTx), Nil, Some(List(withdrawal(0))))
    val block = Block(header, body)
    rawDecode(rlpEncode(block)) match
      case list: RLPList => assert(list.items.length == 4) // header + txs + uncles + withdrawals
      case other         => fail(s"expected a 4-element RLPList, got $other")
    val decoded = summon[RLPCodec[Block]].decode(rawDecode(rlpEncode(block)))
    assert(decoded == block)
    assert(decoded.hash == header.hash)

/** Shared header fixture so the body/block tests do not re-derive a header. */
object BlockHeaderSpecFixtures:
  import com.chipprbots.fukuii.bytes.Hash

  val sampleHeader: BlockHeader = BlockHeader(
    parentHash = Hash.fromHex("0x0000000000000000000000000000000000000000000000000000000000000000"),
    ommersHash = Hash.fromHex("0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"),
    beneficiary = Address.fromHex("0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba"),
    stateRoot = Hash.fromHex("0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"),
    transactionsRoot = Hash.fromHex("0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"),
    receiptsRoot = Hash.fromHex("0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"),
    logsBloom = Bloom.Empty,
    difficulty = BigInt(131072),
    number = BigInt(1),
    gasLimit = 3141592,
    gasUsed = 21000,
    unixTimestamp = 1426516743,
    extraData = ByteString.empty,
    mixHash = Hash.fromHex("0x0000000000000000000000000000000000000000000000000000000000000000"),
    nonce = ByteString(Array.fill[Byte](8)(0))
  )
