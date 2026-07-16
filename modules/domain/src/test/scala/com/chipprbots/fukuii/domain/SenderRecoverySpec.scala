package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.crypto.curve
import com.chipprbots.fukuii.crypto.pubKeyFromPrvKey
import com.chipprbots.fukuii.crypto.pubKeyToAddress

/** EIP-155 sender recovery + the N-1 `ValidateSignatureValues` gate (`plan/L1.md` §5, §7; RX-L1-09/10/12).
  *
  * Byte-exact against go-ethereum / core-geth for the shared vectors, with self-consistent signing KATs where no
  * external ETC vector exists (core-geth removed no such vector — ETC has none in ethereum/tests). Covers: per-chainId
  * recovery (ETC 61, ETH 1, unprotected 27/28, AccessList, DynamicFee); the H-1 block-gated `homestead` acceptance of
  * full-N `s`; the N-1 rejection fail-set; and the second (EIP-7702) authorization-recovery surface.
  */
class SenderRecoverySpec extends AnyFunSuite with Matchers:

  private val N: BigInt = BigInt(curve.getN)
  private val halfN: BigInt = N >> 1

  // A fixed private key with a stable derived address, for the self-consistent signing KATs.
  private val prvKey: ByteString = ByteString(Hex.decode("0x" + "11" * 32))
  private val expectedAddr: Address = pubKeyToAddress(pubKeyFromPrvKey(prvKey))

  private val toAddress = Address.fromHex("0x095e7baea6a6c7c4c2dfeb977efac326af552d87")

  // --- external byte-exact KATs (known signed tx -> known sender) --------------------------------------------------

  test("Legacy unprotected (v=27/28) recovers the recorded sender — TransactionTests/ttSignature/RightVRSTest"):
    // v = 0x1c = 28 (pre-EIP-155). Sender recorded on every fork in the fixture.
    val bytes = Hex.decode(
      "0xf85f030182520894b94f5374fce5edbc8e2a8697c15331677e6ebf0b0a801ca098ff921201554726367d2be8c804a7ff89ccf285ebc57dff8ae4c44b9c19ac4aa01887321be575c8095f789dd4c743dfe42c1820f9231f98a962b210e3ac2452a3"
    )
    val tx = Transaction.decode(bytes)
    val expected = Address.fromHex("0x58d79230fc81a042315da7d243272798e27cb40c")
    assert(
      // unprotected is Homestead-era in this fixture -> homestead = true
      tx.getSender(homestead = true) == Right(expected) &&
        // recovery id is not chainId-encoded, so the block-context flag does not change the recovered address here
        tx.getSender(homestead = false) == Right(expected),
      "the recorded sender must be recovered regardless of the homestead block-context flag"
    )

  test("Legacy EIP-155 protected chainId 1 (ETH) recovers the sender — the canonical EIP-155 example vector"):
    // EIP-155 spec example: privkey 0x4646..46, chainId 1, v = 0x25 = 37 = 0 + 35 + 2*1.
    val bytes = Hex.decode(
      "0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83"
    )
    val tx = Transaction.decode(bytes)
    val expected = Address.fromHex("0x9d8a62f656a8d1615c1294fd71e9cfb3e4855a4f")
    assert(
      (tx match
        case _: Transaction.Legacy => true; case _ => false
      ) &&
        tx.getSender(homestead = true) == Right(expected),
      "the decoded tx must be a Legacy transaction and recover the canonical EIP-155 example sender"
    )

  test("AccessList (0x01) recovers the recorded sender — TransactionTests/ttEIP2930/accessListStorage32Bytes"):
    val bytes = Hex.decode(
      "0x01f89a018001826a4094095e7baea6a6c7c4c2dfeb977efac326af552d878080f838f794a95e7baea6a6c7c4c2dfeb977efac326af552d87e1a0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff80a05cbd172231fc0735e0fb994dd5b1a4939170a260b36f0427a8a80866b063b948a07c230f7f578dd61785c93361b9871c0706ebfa6d06e3f4491dc9558c5202ed36"
    )
    val tx = Transaction.decode(bytes)
    val expected = Address.fromHex("0xebe76799923fd62804659fb00b4f0f1a94c0eb1e")
    assert(tx.getSender(homestead = true) == Right(expected))

  test("DynamicFee (0x02) recovers the recorded sender — TransactionTests/ttEIP1559/GasLimitPriceProductOverflow…"):
    val bytes = Hex.decode(
      "0x02f885018084773594009f02ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff82520894095e7baea6a6c7c4c2dfeb977efac326af552d878080c080a05cbd172231fc0735e0fb994dd5b1a4939170a260b36f0427a8a80866b063b948a07c230f7f578dd61785c93361b9871c0706ebfa6d06e3f4491dc9558c5202ed36"
    )
    val tx = Transaction.decode(bytes)
    val expected = Address.fromHex("0xae2aec498d20869d441eaaf708fb1e375ae1787d")
    assert(tx.getSender(homestead = true) == Right(expected))

  // --- self-consistent per-chainId signing KATs (ETC 61 has no external ethereum/tests vector) --------------------

  /** Build a Legacy tx, EIP-155-sign its sighash for `chainId` with [[prvKey]], and return the signed tx. The signing
    * hash is taken from the tx itself (a placeholder-`v` copy that encodes the same chainId), so the message signed is
    * exactly the one [[Transaction.getSender]] recomputes.
    */
  private def signedLegacy(chainId: Long): Transaction.Legacy =
    val base: Transaction.Legacy = Transaction.Legacy(
      nonce = UInt256(7),
      gasPrice = Wei(UInt256(1000000000L)),
      gasLimit = UInt256(21000),
      to = Some(toAddress),
      value = Wei(UInt256(1000)),
      payload = ByteString.empty,
      // placeholder v encodes `chainId` (yParity 0) so `signingHash` builds the 9-element EIP-155 form for it
      signature = ECDSASignature(BigInt(1), BigInt(1), BigInt(35 + 2 * chainId))
    )
    val sh = SenderRecovery.signingHash(base)
    val sig = ECDSASignature.sign(ByteString(sh), prvKey)
    val yParity = sig.v.toInt - 27
    val v155 = BigInt(35 + 2 * chainId + yParity)
    base.copy(signature = ECDSASignature(sig.r, sig.s, v155))

  test("Legacy EIP-155 chainId 61 (ETC) round-trips: sign -> recover -> the signing key's address"):
    val tx = signedLegacy(61)
    assert(tx.getSender(homestead = true) == Right(expectedAddr))

  test("Legacy EIP-155 chainId 1 (ETH) round-trips: sign -> recover -> the signing key's address"):
    val tx = signedLegacy(1)
    assert(tx.getSender(homestead = true) == Right(expectedAddr))

  test("Legacy EIP-155 chainId 11155111 (Sepolia) round-trips: exercises the large-v arithmetic branch"):
    // v = 35 + 2*11155111 + yParity ≈ 22.3M — the only large-v path chainId 1 (v∈{37,38}) doesn't reach;
    // guards against a future narrowing of the chainId derivation to Int (beacon rec).
    val tx = signedLegacy(11155111)
    assert(tx.getSender(homestead = true) == Right(expectedAddr))

  test("chainId is baked into the sighash: a chainId-61 signature does NOT recover to the same address under 63"):
    // Re-tag the chainId-61 signed tx as chainId 63 (Mordor) by rebuilding v; the recovered sender must differ
    // (or fail) because the signed message included chainId 61.
    val etc = signedLegacy(61)
    val yParity = ((etc.signature.v - 35 - 2 * 61)).toInt
    val vMordor = BigInt(35 + 2 * 63 + yParity)
    val reTagged = etc.copy(signature = ECDSASignature(etc.signature.r, etc.signature.s, vMordor))
    assert(reTagged.getSender(homestead = true) != Right(expectedAddr))

  // --- H-1: the homestead flag is BLOCK-GATED — full-N `s` is legal pre-homestead --------------------------------

  test("H-1 direct: validateSignatureValues accepts full-N s under homestead=false, rejects under homestead=true"):
    val highS = halfN + 1 // > N/2, a malleable high-S value, still < N
    assert(
      SenderRecovery.validateSignatureValues(0, r = BigInt(1), s = highS, homestead = false).isEmpty &&
        SenderRecovery
          .validateSignatureValues(0, r = BigInt(1), s = highS, homestead = true)
          .contains(SigError.HighS),
      "full-N s must be accepted pre-homestead and rejected as HighS from homestead onward"
    )

  test("H-1 end-to-end: a high-S legacy tx recovers under homestead=false but is rejected under homestead=true"):
    // Sign normally (low-S), then malleate to the high-S sibling (r, N-s) with the flipped recovery parity — a
    // signature that recovers the SAME key. A pre-1.15M Frontier-era block (homestead=false) must accept it; a
    // homestead-and-later block must reject it (EIP-2 low-S).
    val base: Transaction.Legacy = Transaction.Legacy(
      nonce = UInt256(3),
      gasPrice = Wei(UInt256(1000000000L)),
      gasLimit = UInt256(21000),
      to = Some(toAddress),
      value = Wei(UInt256(5)),
      payload = ByteString.empty,
      signature = ECDSASignature(BigInt(1), BigInt(1), BigInt(27)) // unprotected placeholder
    )
    val sh = SenderRecovery.signingHash(base)
    val lowSig = ECDSASignature.sign(ByteString(sh), prvKey) // low-S by construction (toCanonicalS)
    val yParityLow = lowSig.v.toInt - 27
    val sHigh = N - lowSig.s // > N/2
    val vHigh = BigInt(27 + (1 - yParityLow)) // flipped parity recovers the same pubkey
    val highTx = base.copy(signature = ECDSASignature(lowSig.r, sHigh, vHigh))

    assert(
      highTx.getSender(homestead = false) == Right(expectedAddr) && // accepted pre-homestead, recovers the key
        highTx.getSender(homestead = true) == Left(SigError.HighS), // rejected homestead-and-later
      "a high-S tx must recover pre-homestead and be rejected as HighS from homestead onward"
    )

  // --- N-1 fail-set (ValidateSignatureValues rejection vectors) ---------------------------------------------------

  test("N-1 fail-set: r=0, s=0, r=N, s=N, s=N/2+1(homestead), and a bad recovery id are all rejected"):
    assert(
      SenderRecovery
        .validateSignatureValues(0, r = BigInt(0), s = BigInt(1), homestead = true)
        .contains(SigError.InvalidRange) &&
        SenderRecovery
          .validateSignatureValues(0, r = BigInt(1), s = BigInt(0), homestead = true)
          .contains(SigError.InvalidRange) &&
        SenderRecovery
          .validateSignatureValues(0, r = N, s = BigInt(1), homestead = true)
          .contains(SigError.InvalidRange) &&
        // s=N must use homestead=false to isolate the range check: geth evaluates `homestead && s>halfN` BEFORE
        // `s<N` (crypto.go), so s=N under homestead=true is (correctly) caught by the high-S branch first — both
        // reject.
        SenderRecovery
          .validateSignatureValues(0, r = BigInt(1), s = N, homestead = false)
          .contains(SigError.InvalidRange) &&
        // ...and the same s=N under homestead=true IS caught by the high-S branch FIRST — asserting the evaluation
        // order at the exact s=N boundary that lines above only narrate (crypto.go: `homestead && s>halfN` before
        // `s<N`):
        SenderRecovery.validateSignatureValues(0, r = BigInt(1), s = N, homestead = true).contains(SigError.HighS) &&
        SenderRecovery
          .validateSignatureValues(0, r = BigInt(1), s = halfN + 1, homestead = true)
          .contains(SigError.HighS) &&
        SenderRecovery
          .validateSignatureValues(2, r = BigInt(1), s = BigInt(1), homestead = true)
          .contains(SigError.InvalidRecoveryId) &&
        // a valid canonical low-S with a good recovery id passes the gate
        SenderRecovery.validateSignatureValues(1, r = BigInt(1), s = halfN, homestead = true).isEmpty,
      "every N-1 fail-set vector must be rejected with the expected SigError, and the valid low-S vector must pass"
    )

  test("N-1 through getSender: a legacy tx with an out-of-range legacy v is rejected as InvalidRecoveryId"):
    val badV = Transaction.Legacy(
      nonce = UInt256(0),
      gasPrice = Wei.Zero,
      gasLimit = UInt256(21000),
      to = Some(toAddress),
      value = Wei.Zero,
      payload = ByteString.empty,
      signature = ECDSASignature(BigInt(1), BigInt(1), BigInt(30)) // 30 is neither 27/28 nor >= 35
    )
    assert(badV.getSender(homestead = true) == Left(SigError.InvalidRecoveryId))

  // --- EIP-7702 authorization recovery (the second, independent recovery surface) --------------------------------

  test("7702 authorization recovers its authority — distinct from the outer SetCode tx sender"):
    // The authorization is signed by `prvKey`; recover its authority to that key's address.
    val chainId = ChainId(1)
    val authNonce = UInt256(9)
    val sh = SetCodeAuthorization.sigHash(chainId, toAddress, authNonce)
    val sig = ECDSASignature.sign(sh.bytes, prvKey)
    val yParity = (sig.v.toInt - 27).toByte
    val auth = SetCodeAuthorization(
      chainId = chainId,
      address = toAddress,
      nonce = authNonce,
      yParity = yParity,
      r = UInt256(sig.r),
      s = UInt256(sig.s)
    )

    // Build an outer SetCode tx signed by a DIFFERENT key; its sender must differ from the auth's authority,
    // proving the two recovery surfaces are independent.
    val outerKey = ByteString(Hex.decode("0x" + "22" * 32))
    val outerAddr = pubKeyToAddress(pubKeyFromPrvKey(outerKey))
    val setCodeBase: Transaction.SetCode = Transaction.SetCode(
      chainId = chainId,
      nonce = UInt256(0),
      maxPriorityFeePerGas = Wei(UInt256(1000000000L)),
      maxFeePerGas = Wei(UInt256(2000000000L)),
      gasLimit = UInt256(21000),
      to = toAddress,
      value = Wei.Zero,
      payload = ByteString.empty,
      accessList = Nil,
      authorizationList = List(auth),
      signature = ECDSASignature(BigInt(1), BigInt(1), BigInt(0)) // placeholder yParity 0
    )
    val outerSh = SenderRecovery.signingHash(setCodeBase)
    val outerSig = ECDSASignature.sign(ByteString(outerSh), outerKey)
    val outerTx = setCodeBase.copy(signature = ECDSASignature(outerSig.r, outerSig.s, BigInt(outerSig.v.toInt - 27)))
    assert(
      auth.authority == Right(expectedAddr) &&
        outerTx.getSender(homestead = true) == Right(outerAddr) &&
        outerTx.getSender(homestead = true) != auth.authority &&
        expectedAddr != outerAddr, // the two surfaces recovered two different accounts
      "the authorization must recover its authority, the outer tx must recover its own distinct sender, and the two surfaces must never collide"
    )

  // --- tx.hash (consensus hash) — legacy vs typed, blob uses consensus form --------------------------------------

  test("tx.hash is keccak256 of the consensus encoding (matches the recorded EIP-155 example hash)"):
    val bytes = Hex.decode(
      "0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83"
    )
    val tx = Transaction.decode(bytes)
    val expected = Hash(ByteString(com.chipprbots.fukuii.crypto.kec256(bytes)))
    assert(tx.hash == expected)
