package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString
import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.RLPValue
import com.chipprbots.fukuii.rlp.rawDecode
import com.chipprbots.fukuii.rlp.encode as rlpEncode

/** EIP-2718 typed-transaction envelope: per-variant RLP round-trip, the `to`-field `rlp:"nil"` shape, and the
  * first-byte dispatch decoder ([[Transaction.decode(bytes: Array[Byte])]]) — including its `0xc0` boundary, the
  * canonical byte-exact regression gate for consensus code.
  */
class TransactionSpec extends AnyFunSuite:

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
    value = Wei.Zero,
    payload = ByteString.empty,
    accessList = List(AccessListEntry(toAddress, List(Hash.fromHex("0x" + ("00" * 31) + "01")))),
    signature = ECDSASignature(BigInt(1), BigInt(2), BigInt(0))
  )

  private def dynamicFeeTx: Transaction.DynamicFee = Transaction.DynamicFee(
    chainId = ChainId(1),
    nonce = UInt256(0),
    maxPriorityFeePerGas = Wei(UInt256(1000000000L)),
    maxFeePerGas = Wei(UInt256(2000000000L)),
    gasLimit = UInt256(21000),
    to = Some(toAddress),
    value = Wei.Zero,
    payload = ByteString.empty,
    accessList = List(AccessListEntry(toAddress, List(Hash.fromHex("0x" + ("00" * 31) + "01")))),
    signature = ECDSASignature(BigInt(1), BigInt(2), BigInt(1))
  )

  // --- RLP round-trip, one representative instance per implemented variant ---------------------------------------

  test("Legacy round-trips through Transaction.decode(bytes)"):
    val tx: Transaction = legacyTx
    val bytes = rlpEncode(tx)
    assert(Transaction.decode(bytes) == tx)

  test("AccessList round-trips through Transaction.decode(bytes)"):
    val tx: Transaction = accessListTx
    val bytes = rlpEncode(tx)
    assert(Transaction.decode(bytes) == tx)

  test("DynamicFee round-trips through Transaction.decode(bytes)"):
    val tx: Transaction = dynamicFeeTx
    val bytes = rlpEncode(tx)
    assert(Transaction.decode(bytes) == tx)

  // --- byte-exact reference vectors (ethereum/tests TransactionTests) — the consensus gate -------------------------
  // A structural round-trip alone cannot catch a field-order or width bug that happens to be self-consistent; these
  // vectors pin the encoding to bytes (and the resulting tx hash) that go-ethereum/besu-generated fixtures agree on.

  test(
    "byte-exact Legacy vector (TransactionTests/ttSignature/RightVRSTest.json) round-trips and hashes correctly"
  ):
    // txbytes + hash from ethereum/tests TransactionTests/ttSignature/RightVRSTest.json — valid (no "exception") on
    // every fork in the fixture, hash/sender recorded for Frontier through Prague.
    val vectorBytes = Hex.decode(
      "0xf85f030182520894b94f5374fce5edbc8e2a8697c15331677e6ebf0b0a801ca098ff921201554726367d2be8c804a7ff89ccf285ebc57dff8ae4c44b9c19ac4aa01887321be575c8095f789dd4c743dfe42c1820f9231f98a962b210e3ac2452a3"
    )
    val expectedHash = Hex.decode("0x1cbb233404f49e96cb795d0ea74f485eca2c41a216e0ce80694cef4dd7a45b50")

    val tx = Transaction.decode(vectorBytes)
    assert(tx.isInstanceOf[Transaction.Legacy])
    assert(tx.txType == 0x00)
    assert(rlpEncode(tx).sameElements(vectorBytes))
    assert(kec256(vectorBytes).sameElements(expectedHash))

  test(
    "byte-exact AccessList (0x01) vector (TransactionTests/ttEIP2930/accessListStorage32Bytes.json) round-trips and hashes correctly"
  ):
    // Valid (hash/sender recorded) on Berlin/London/Paris/Cancun+ — the "TYPE_NOT_SUPPORTED" exception on pre-Berlin
    // forks in the fixture is a fork-admissibility check, not an RLP-decode failure, and is out of scope here.
    val vectorBytes = Hex.decode(
      "0x01f89a018001826a4094095e7baea6a6c7c4c2dfeb977efac326af552d878080f838f794a95e7baea6a6c7c4c2dfeb977efac326af552d87e1a0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff80a05cbd172231fc0735e0fb994dd5b1a4939170a260b36f0427a8a80866b063b948a07c230f7f578dd61785c93361b9871c0706ebfa6d06e3f4491dc9558c5202ed36"
    )
    val expectedHash = Hex.decode("0xb4f8b14a7aaf85ec2f76be9fbe4155deae1f87b2da95af73be3c27ed8d4c8cb7")

    val tx = Transaction.decode(vectorBytes)
    assert(tx.isInstanceOf[Transaction.AccessList])
    assert(tx.txType == 0x01)
    assert(rlpEncode(tx).sameElements(vectorBytes))
    assert(kec256(vectorBytes).sameElements(expectedHash))

  test(
    "byte-exact DynamicFee (0x02) vector (TransactionTests/ttEIP1559/GasLimitPriceProductOverflowtMinusOneFiller.json) round-trips and hashes correctly"
  ):
    // Valid (hash/sender recorded) on Cancun/London/Paris/Prague/Shanghai — the "TYPE_NOT_SUPPORTED" exception on
    // pre-London forks is fork-admissibility, not an RLP-decode failure.
    val vectorBytes = Hex.decode(
      "0x02f885018084773594009f02ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff82520894095e7baea6a6c7c4c2dfeb977efac326af552d878080c080a05cbd172231fc0735e0fb994dd5b1a4939170a260b36f0427a8a80866b063b948a07c230f7f578dd61785c93361b9871c0706ebfa6d06e3f4491dc9558c5202ed36"
    )
    val expectedHash = Hex.decode("0xdad8bff3ecfcf95169b1d5625b47f3372be795802bc4fe570991cf332f609334")

    val tx = Transaction.decode(vectorBytes)
    assert(tx.isInstanceOf[Transaction.DynamicFee])
    assert(tx.txType == 0x02)
    assert(rlpEncode(tx).sameElements(vectorBytes))
    assert(kec256(vectorBytes).sameElements(expectedHash))

  // --- EIP-2718 first-byte dispatch ---------------------------------------------------------------------------

  test("a bare legacy list (first byte >= 0xc0) dispatches to Legacy"):
    val bytes = rlpEncode(legacyTx: Transaction)
    assert((bytes(0) & 0xff) >= 0xc0)
    assert(Transaction.decode(bytes).isInstanceOf[Transaction.Legacy])

  test("type byte 0x01 dispatches to AccessList"):
    val bytes = rlpEncode(accessListTx: Transaction)
    assert(bytes(0) == 0x01)
    assert(Transaction.decode(bytes).isInstanceOf[Transaction.AccessList])

  test("type byte 0x02 dispatches to DynamicFee"):
    val bytes = rlpEncode(dynamicFeeTx: Transaction)
    assert(bytes(0) == 0x02)
    assert(Transaction.decode(bytes).isInstanceOf[Transaction.DynamicFee])

  test("type byte 0x03 (Blob) throws the phase-2b notYetSupported guard, not a decode error"):
    val ex = intercept[RLPException](Transaction.decode(Array[Byte](0x03)))
    assert(ex.getMessage.contains("not yet supported"))

  test("type byte 0x04 (SetCode) throws the phase-2b notYetSupported guard, not a decode error"):
    val ex = intercept[RLPException](Transaction.decode(Array[Byte](0x04)))
    assert(ex.getMessage.contains("not yet supported"))

  test("type byte 0x05 is REJECTED, not silently treated as legacy"):
    intercept[RLPException](Transaction.decode(Array[Byte](0x05)))

  test(
    "the 0xc0 boundary hazard: a gap byte < 0xc0 that is not a known type id (e.g. 0x80) is REJECTED, " +
      "never mis-dispatched to the legacy branch"
  ):
    // 0x80 is below the legacy list-header threshold (0xc0) and is not one of {0x01,0x02,0x03,0x04} — this is
    // exactly the hazard the docstring on Transaction.decode calls out: silently accepting this as legacy would
    // misinterpret a malformed/future type byte as a well-formed (and wrong) legacy transaction.
    intercept[RLPException](Transaction.decode(Array[Byte](0x80.toByte)))

  test("an empty input is rejected"):
    intercept[RLPException](Transaction.decode(Array.emptyByteArray))

  // --- the `to` field: rlp:"nil" pointer semantics (empty RLP string, not empty list) -------------------------

  test("contract-creation (to = None) encodes `to` as the RLP empty string, not an empty list, and round-trips"):
    val creationTx: Transaction = legacyTx.copy(to = None)
    val bytes = rlpEncode(creationTx)

    // RLPValue is array-backed, so AST `==` is reference equality (see RLPSpec) — compare `.bytes` instead.
    rawDecode(bytes) match
      case RLPList(_, _, _, to: RLPValue, _*) => assert(to.bytes.isEmpty)
      case other                              => fail(s"expected a Legacy RLPList with an RLPValue `to`, got $other")

    Transaction.decode(bytes) match
      case Transaction.Legacy(_, _, _, to, _, _, _) => assert(to == None)
      case other                                    => fail(s"expected a Legacy transaction, got $other")

  test("a present `to` (call, not creation) round-trips back to Some(address)"):
    val bytes = rlpEncode(legacyTx: Transaction)
    Transaction.decode(bytes) match
      case Transaction.Legacy(_, _, _, to, _, _, _) => assert(to == Some(toAddress))
      case other                                    => fail(s"expected a Legacy transaction, got $other")
