package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.RLPValue
import com.chipprbots.fukuii.rlp.encode as rlpEncode
import com.chipprbots.fukuii.rlp.rawDecode

/** EIP-2718 typed-transaction envelope: per-variant RLP round-trip, the `to`-field `rlp:"nil"` shape, and the
  * first-byte dispatch decoder ([[Transaction.decode(bytes: Array[Byte])]]) — including its `0xc0` boundary, the
  * canonical byte-exact regression gate for consensus code.
  */
class TransactionSpec extends AnyFunSuite with Matchers:

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

  private val versionedHash = Hash.fromHex("0x01" + ("ab" * 31)) // leading 0x01 KZG-version byte

  private def blobTx: Transaction.Blob = Transaction.Blob(
    chainId = ChainId(1),
    nonce = UInt256(0),
    maxPriorityFeePerGas = Wei(UInt256(1000000000L)),
    maxFeePerGas = Wei(UInt256(2000000000L)),
    gasLimit = UInt256(21000),
    to = toAddress,
    value = Wei.Zero,
    payload = ByteString.empty,
    accessList = Nil,
    maxFeePerBlobGas = Wei(UInt256(10)),
    blobVersionedHashes = List(versionedHash),
    signature = ECDSASignature(BigInt(1), BigInt(2), BigInt(1)),
    sidecar = None
  )

  private def blobSidecar: BlobSidecar = BlobSidecar(
    version = BlobSidecar.Version0,
    blobs = List(ByteString(Array.fill[Byte](4)(0x11))),
    commitments = List(ByteString(Array.fill[Byte](48)(0x22))),
    proofs = List(ByteString(Array.fill[Byte](48)(0x33)))
  )

  private def setCodeAuth: SetCodeAuthorization = SetCodeAuthorization(
    chainId = ChainId(1),
    address = toAddress,
    nonce = UInt256(7),
    yParity = 1,
    r = UInt256(BigInt(3)),
    s = UInt256(BigInt(4))
  )

  private def setCodeTx: Transaction.SetCode = Transaction.SetCode(
    chainId = ChainId(1),
    nonce = UInt256(0),
    maxPriorityFeePerGas = Wei(UInt256(1000000000L)),
    maxFeePerGas = Wei(UInt256(2000000000L)),
    gasLimit = UInt256(21000),
    to = toAddress,
    value = Wei.Zero,
    payload = ByteString.empty,
    accessList = List(AccessListEntry(toAddress, List(Hash.fromHex("0x" + ("00" * 31) + "01")))),
    authorizationList = List(setCodeAuth),
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

  test("Blob (consensus form, no sidecar) round-trips through Transaction.decode(bytes)"):
    val tx: Transaction = blobTx
    val bytes = rlpEncode(tx)
    assert(Transaction.decode(bytes) == tx)

  test("SetCode round-trips through Transaction.decode(bytes)"):
    val tx: Transaction = setCodeTx
    val bytes = rlpEncode(tx)
    assert(Transaction.decode(bytes) == tx)

  test("SetCodeAuthorization round-trips through its RLP codec"):
    val codec = summon[com.chipprbots.fukuii.rlp.RLPCodec[SetCodeAuthorization]]
    val encoded = com.chipprbots.fukuii.rlp.encode(codec.encode(setCodeAuth))
    assert(codec.decode(rawDecode(encoded)) == setCodeAuth)

  // --- Blob two-form: consensus vs network wrapper (RX-L1-11, §9 nethermind two-form) ------------------------------

  test("the two Blob forms produce DIFFERENT bytes (consensus omits the sidecar; wrapper carries it)"):
    val withSidecar = blobTx.copy(sidecar = Some(blobSidecar))
    val consensusBytes = rlpEncode(withSidecar: Transaction) // aggregate -> consensus form
    val wrapperBytes = com.chipprbots.fukuii.rlp.encode(
      Transaction.BlobNetworkWrapper.blobNetworkWrapperCodec.encode(withSidecar)
    )
    assert(
      !consensusBytes.sameElements(wrapperBytes) &&
        // the wrapper is strictly longer — it nests the same body list plus the blobs/commitments/proofs tail
        wrapperBytes.length > consensusBytes.length &&
        // both are 0x03-prefixed EIP-2718 envelopes
        consensusBytes(0) == 0x03 && wrapperBytes(0) == 0x03,
      "the consensus and network-wrapper forms must differ, the wrapper must be strictly longer, and both must be 0x03-prefixed"
    )

  test("tx.hash (consensus encoding) is STABLE regardless of the sidecar"):
    val withoutSidecar = rlpEncode(blobTx: Transaction)
    val withSidecar = rlpEncode(blobTx.copy(sidecar = Some(blobSidecar)): Transaction)
    assert(
      // the aggregate codec always uses the consensus form, so the hashed bytes are identical
      withoutSidecar.sameElements(withSidecar) &&
        kec256(withoutSidecar).sameElements(kec256(withSidecar)),
      "the consensus encoding and its hash must be identical with or without a sidecar"
    )

  test("the network wrapper round-trips back to the same Blob, recovering the v0 sidecar"):
    val withSidecar = blobTx.copy(sidecar = Some(blobSidecar))
    val codec = Transaction.BlobNetworkWrapper.blobNetworkWrapperCodec
    val encoded = com.chipprbots.fukuii.rlp.encode(codec.encode(withSidecar))
    val decoded = codec.decode(rawDecode(encoded.tail))
    assert(
      (encoded(0) & 0xff) == 0x03 &&
        decoded == withSidecar &&
        decoded.sidecar.map(_.version).contains(BlobSidecar.Version0),
      "the network wrapper must be 0x03-prefixed and round-trip back to the same Blob with its v0 sidecar"
    )

  test("versionedHash = 0x01 || sha256(commitment)[1:], with the fixed KZG 0x01 leading byte"):
    val commitment = ByteString(Array.fill[Byte](48)(0x22))
    val vh = BlobSidecar.versionedHash(commitment)
    val expected = com.chipprbots.fukuii.crypto.sha256(commitment.toArray).clone()
    expected(0) = 0x01
    assert(
      vh.toArray.sameElements(expected) &&
        vh.toArray.head == 0x01, // fixed KZG version byte, NOT the sidecar version field
      "versionedHash must equal 0x01 || sha256(commitment)[1:]"
    )

  // --- SetCodeAuthorization SigHash: magic byte 0x05, NOT the tx-type 0x04 ----------------------------------------

  test("SetCodeAuthorization.sigHash uses magic byte 0x05 = keccak256(0x05 || RLP([chainId, address, nonce]))"):
    val body = RLPList(
      summon[com.chipprbots.fukuii.rlp.RLPCodec[ChainId]].encode(setCodeAuth.chainId),
      summon[com.chipprbots.fukuii.rlp.RLPCodec[Address]].encode(setCodeAuth.address),
      summon[com.chipprbots.fukuii.rlp.RLPCodec[UInt256]].encode(setCodeAuth.nonce)
    )
    val expected = kec256(0x05.toByte +: com.chipprbots.fukuii.rlp.encode(body))
    // a byte-0x04 (tx-type) prefix would give a DIFFERENT hash — prove the magic byte is not conflated
    val wrong = kec256(0x04.toByte +: com.chipprbots.fukuii.rlp.encode(body))
    assert(
      setCodeAuth.sigHash.toArray.sameElements(expected) &&
        SetCodeAuthorization.MagicByte == 0x05 &&
        !setCodeAuth.sigHash.toArray.sameElements(wrong),
      "sigHash must use magic byte 0x05 and must NOT collide with a byte-0x04-prefixed hash"
    )

  // --- byte-exact Blob (0x03) vector from ethereum/tests BlockchainTests -------------------------------------------
  // Extracted (tx index 3) from ValidBlocks/bcEIP4844-blobtransactions/blockWithAllTransactionTypes.json — the
  // consensus (no-sidecar) encoding go-ethereum places in the block body. Pins the 14-field order to bytes.

  test("byte-exact Blob consensus vector (bcEIP4844-blobtransactions/blockWithAllTransactionTypes) round-trips"):
    val vectorBytes = Hex.decode(
      "0x03f8890103018203e885e8d4a5100094100000000000000000000000000000000000000a0780c00ae1a0" +
        "01a915e4d060149eb4365960e6a7a45f334393093061116b197e3240065ff2d8809f638144c46d5de7a9e630" +
        "c0e7c5c63ae829ecfd8cc94715d9c29fe17c464de0a06c5fc54c3aa868ba35ef31a4e12431611631ab7bcdce" +
        "b4214dd273d83f73b5e1"
    )
    val tx = Transaction.decode(vectorBytes)
    val blobFieldsOk = tx match
      case b: Transaction.Blob =>
        b.chainId == ChainId(1) &&
        b.nonce == UInt256(3) &&
        b.maxFeePerBlobGas == Wei(UInt256(10)) &&
        b.blobVersionedHashes.length == 1 &&
        b.blobVersionedHashes.head.toArray.head == 0x01 && // KZG versioned hash leading byte
        b.sidecar.isEmpty // consensus form has no sidecar
      case other => fail(s"expected a Blob, got $other")
    assert(
      (tx match
        case _: Transaction.Blob => true; case _ => false
      ) &&
        tx.txType == 0x03 &&
        blobFieldsOk &&
        rlpEncode(tx).sameElements(vectorBytes),
      "the decoded tx must be a type-0x03 Blob with the vector's fields and byte-exact re-encoding"
    )

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
    assert(
      (tx match
        case _: Transaction.Legacy => true; case _ => false
      ) &&
        tx.txType == 0x00 &&
        rlpEncode(tx).sameElements(vectorBytes) &&
        kec256(vectorBytes).sameElements(expectedHash),
      "the decoded tx must be a type-0x00 Legacy tx that re-encodes and hashes byte-exact to the vector"
    )

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
    assert(
      (tx match
        case _: Transaction.AccessList => true; case _ => false
      ) &&
        tx.txType == 0x01 &&
        rlpEncode(tx).sameElements(vectorBytes) &&
        kec256(vectorBytes).sameElements(expectedHash),
      "the decoded tx must be a type-0x01 AccessList tx that re-encodes and hashes byte-exact to the vector"
    )

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
    assert(
      (tx match
        case _: Transaction.DynamicFee => true; case _ => false
      ) &&
        tx.txType == 0x02 &&
        rlpEncode(tx).sameElements(vectorBytes) &&
        kec256(vectorBytes).sameElements(expectedHash),
      "the decoded tx must be a type-0x02 DynamicFee tx that re-encodes and hashes byte-exact to the vector"
    )

  // --- EIP-2718 first-byte dispatch ---------------------------------------------------------------------------

  test("a bare legacy list (first byte >= 0xc0) dispatches to Legacy"):
    val bytes = rlpEncode(legacyTx: Transaction)
    assert(
      (bytes(0) & 0xff) >= 0xc0 &&
        (Transaction.decode(bytes) match
          case _: Transaction.Legacy => true; case _ => false
        ),
      "a bare legacy list must have a first byte >= 0xc0 and dispatch to Legacy"
    )

  test("type byte 0x01 dispatches to AccessList"):
    val bytes = rlpEncode(accessListTx: Transaction)
    assert(
      bytes(0) == 0x01 &&
        (Transaction.decode(bytes) match
          case _: Transaction.AccessList => true; case _ => false
        ),
      "type byte 0x01 must dispatch to AccessList"
    )

  test("type byte 0x02 dispatches to DynamicFee"):
    val bytes = rlpEncode(dynamicFeeTx: Transaction)
    assert(
      bytes(0) == 0x02 &&
        (Transaction.decode(bytes) match
          case _: Transaction.DynamicFee => true; case _ => false
        ),
      "type byte 0x02 must dispatch to DynamicFee"
    )

  test("type byte 0x03 dispatches to Blob (consensus form)"):
    val bytes = rlpEncode(blobTx: Transaction)
    assert(
      bytes(0) == 0x03 &&
        (Transaction.decode(bytes) match
          case _: Transaction.Blob => true; case _ => false
        ),
      "type byte 0x03 must dispatch to Blob"
    )

  test("type byte 0x04 dispatches to SetCode"):
    val bytes = rlpEncode(setCodeTx: Transaction)
    assert(
      bytes(0) == 0x04 &&
        (Transaction.decode(bytes) match
          case _: Transaction.SetCode => true; case _ => false
        ),
      "type byte 0x04 must dispatch to SetCode"
    )

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
    val toIsEmptyString = rawDecode(bytes) match
      case RLPList(_, _, _, to: RLPValue, _*) => to.bytes.isEmpty
      case other                              => fail(s"expected a Legacy RLPList with an RLPValue `to`, got $other")
    val decodedToIsNone = Transaction.decode(bytes) match
      case Transaction.Legacy(_, _, _, to, _, _, _) => to == None
      case other                                    => fail(s"expected a Legacy transaction, got $other")
    assert(
      toIsEmptyString && decodedToIsNone,
      "contract-creation must encode `to` as the RLP empty string, not an empty list, and round-trip to None"
    )

  test("a present `to` (call, not creation) round-trips back to Some(address)"):
    val bytes = rlpEncode(legacyTx: Transaction)
    Transaction.decode(bytes) match
      case Transaction.Legacy(_, _, _, to, _, _, _) => assert(to == Some(toAddress))
      case other                                    => fail(s"expected a Legacy transaction, got $other")
