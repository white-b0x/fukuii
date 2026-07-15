package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.encode as rlpEncode
import com.chipprbots.fukuii.rlp.rawDecode

/** [[Receipt]] RLP: the legacy (bare list) vs typed (`type ‖ RLP`) prefix, the **status-vs-post-state** mutually-
  * exclusive union (a pre-Byzantium/pre-Atlantis receipt carries a 32-byte post-state root, a post-fork receipt a
  * 1-byte status), and byte-exact logs-bloom. The consensus receipt-root byte-alignment gate.
  */
class ReceiptSpec extends AnyFunSuite:

  private val contract = Address.fromHex("0x095e7baea6a6c7c4c2dfeb977efac326af552d87")
  private val topic = Hash.fromHex("0x000000000000000000000000000000000000000000000000000000000000dead")
  private val postStateRoot = Hash.fromHex("0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")

  private def sampleLog: Log = Log(contract, List(topic), ByteString(Array[Byte](1, 2, 3)))

  private def statusReceipt(succeeded: Boolean, txType: Byte = 0x00): Receipt =
    val logs = List(sampleLog)
    Receipt(
      status = ReceiptStatus.Status(succeeded),
      cumulativeGasUsed = 21000,
      logsBloom = Bloom.of(logs),
      logs = logs,
      txType = txType
    )

  private def roundTripLegacy(r: Receipt): Receipt =
    summon[RLPCodec[Receipt]].decode(rawDecode(rlpEncode(r)))

  private def roundTripBinary(r: Receipt): Receipt =
    Receipt.decode(rlpEncode(r))

  test("legacy success receipt: bare 4-element list, status encodes as 0x01"):
    val r = statusReceipt(succeeded = true)
    rawDecode(rlpEncode(r)) match
      case RLPList(status, _, _, _) =>
        assert(rlpEncode(status).sameElements(Array[Byte](0x01)))
      case other => fail(s"expected a 4-element RLPList, got $other")
    assert(roundTripLegacy(r) == r)

  test("legacy failure receipt: status encodes as the RLP empty string (0x80)"):
    val r = statusReceipt(succeeded = false)
    rawDecode(rlpEncode(r)) match
      case RLPList(status, _, _, _) =>
        // empty-string RLP is the single byte 0x80
        assert(rlpEncode(status).sameElements(Array[Byte](0x80.toByte)))
      case other => fail(s"expected a 4-element RLPList, got $other")
    assert(roundTripLegacy(r) == r)

  test("pre-fork receipt: a 32-byte post-state root, NOT a status byte (the union switch)"):
    val logs = List(sampleLog)
    val r = Receipt(
      status = ReceiptStatus.PostStateRoot(postStateRoot),
      cumulativeGasUsed = 21000,
      logsBloom = Bloom.of(logs),
      logs = logs
    )
    rawDecode(rlpEncode(r)) match
      case RLPList(status, _, _, _) =>
        assert(rlpEncode(status).length == 33, "a 32-byte string RLP-encodes to 33 bytes (0xa0 + 32)")
        assert(Hash(ByteString(rlpEncode(status).drop(1))) == postStateRoot)
      case other => fail(s"expected a 4-element RLPList, got $other")
    val decoded = roundTripLegacy(r)
    assert(decoded.status == ReceiptStatus.PostStateRoot(postStateRoot))
    assert(decoded == r)

  test("post-state and status forms are mutually exclusive and distinct on the wire"):
    val logs = List(sampleLog)
    val bloom = Bloom.of(logs)
    val statusForm = Receipt(ReceiptStatus.Status(true), 21000, bloom, logs)
    val rootForm = Receipt(ReceiptStatus.PostStateRoot(postStateRoot), 21000, bloom, logs)
    assert(rlpEncode(statusForm).sameElements(rlpEncode(rootForm)) == false)

  test("typed receipt (0x01): type ‖ RLP prefix, round-trips through the binary dispatch"):
    val r = statusReceipt(succeeded = true, txType = 0x01)
    val bytes = rlpEncode(r)
    assert((bytes(0) & 0xff) == 0x01, "typed receipt starts with its type byte, not a list header")
    assert(roundTripBinary(r) == r)

  test("typed receipts for each EIP-2718 type (0x01-0x04) round-trip"):
    for t <- List[Byte](0x01, 0x02, 0x03, 0x04) do
      val r = statusReceipt(succeeded = true, txType = t)
      assert((rlpEncode(r)(0) & 0xff) == (t & 0xff))
      assert(roundTripBinary(r) == r)

  test("binary dispatch rejects an unknown receipt type byte (0x05), never treats it as legacy"):
    val bogus = Array[Byte](0x05, 0xc0.toByte)
    assert(intercept[RLPException](Receipt.decode(bogus)).getMessage.contains("Unrecognized receipt type"))

  test("logs-bloom is byte-exact: the receipt's bloom equals CreateBloom over its logs"):
    val logs = List(sampleLog, Log(contract, Nil, ByteString.empty))
    val r = Receipt(ReceiptStatus.Status(true), 21000, Bloom.of(logs), logs)
    val decoded = roundTripLegacy(r)
    assert(decoded.logsBloom == Bloom.of(logs))
    // the bloom is 256 bytes and survives the round-trip byte-for-byte
    assert(decoded.logsBloom.toArray.sameElements(r.logsBloom.toArray))

  test("an invalid PostStateOrStatus length (e.g. 5 bytes) is rejected"):
    // Build a receipt body list whose status element is an out-of-spec 5-byte string.
    val r = statusReceipt(succeeded = true)
    val encoded = rawDecode(rlpEncode(r))
    encoded match
      case RLPList(_, gasUsed, bloom, logsRlp) =>
        val bad = RLPList(
          com.chipprbots.fukuii.rlp.RLPValue(Array[Byte](1, 2, 3, 4, 5)),
          gasUsed,
          bloom,
          logsRlp
        )
        assert(intercept[RLPException](summon[RLPCodec[Receipt]].decode(bad)).getMessage.contains("PostStateOrStatus"))
      case other => fail(s"unexpected shape $other")
