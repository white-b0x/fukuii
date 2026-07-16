package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import scala.io.Source
import scala.util.Using

import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.ByteUtils
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.crypto.bls.Bls12381
import com.chipprbots.fukuii.crypto.pubKeyFromPrvKey
import com.chipprbots.fukuii.crypto.pubKeyToAddress
import com.chipprbots.fukuii.crypto.sha256
import com.chipprbots.fukuii.crypto.zksnark.Fp
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.evm.PrecompiledContracts.*

/** P5 byte-consensus KATs for the precompiled-contract wrappers and the fork-gated precompile set.
  *
  * Each wrapper is a gas + decode + dispatch shell over the L0 `crypto` primitive; these tests exercise the wrapper
  * concerns (gas, address→op dispatch, fail-loud at entry, run-shell packaging) against the canonical EIP vectors, plus
  * the fold-resolved per-fork set (the ETC `0x0a`-exclusion and the P256 `0x0100` dual-activation), plus the
  * interpreter short-circuit.
  */
class PrecompiledContractsSpec extends AnyFunSuite:

  private def bs(n: BigInt): ByteString = ByteString(ByteUtils.bigIntToBytes(n, 32))
  private def hx(s: String): ByteString = ByteString(Hex.decode(s.stripPrefix("0x")))
  private def toHex(b: ByteString): String = Hex.toHexString(b.toArray)

  // BN128 base-field prime (for the G1 negation used in the ECPAIRING bilinearity vector).
  private val fpP = Fp.P

  // ================================================================================================================
  //  Classic set — ecrecover (0x01), sha256 (0x02), ripemd160 (0x03), identity (0x04)
  // ================================================================================================================

  test("sha256 (0x02) — empty-input KAT"):
    assert(
      toHex(Sha256.exec(ByteString.empty).get) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )

  test("sha256 (0x02) — gas is 60 + 12·words"):
    assert(Sha256.gas(ByteString(Array.fill[Byte](64)(0)), EvmConfig.Frontier) == BigInt(60 + 12 * 2))

  test("ripemd160 (0x03) — empty-input KAT, left-padded to 32 bytes"):
    assert(
      toHex(Ripemd160.exec(ByteString.empty).get) ==
        "0000000000000000000000009c1185a5c5e9fc54612808977ee8f548b2258d31"
    )

  test("identity (0x04) — returns the input unchanged and prices 15 + 3·words"):
    val input = hx("0xdeadbeef")
    assert(Identity.exec(input).contains(input) && Identity.gas(input, EvmConfig.Frontier) == BigInt(15 + 3 * 1))

  test("ecrecover (0x01) — recovers the signer address (independent private-key derivation)"):
    val prvKey = hx("0x1122334455667788112233445566778811223344556677881122334455667788")
    val hash = hx("0x5a1b2c3d4e5f60718293a4b5c6d7e8f9000102030405060708090a0b0c0d0e0f")
    val expectedAddr = pubKeyToAddress(pubKeyFromPrvKey(prvKey))
    val sig = ECDSASignature.sign(hash, prvKey)
    val input =
      hash ++
        ByteUtils.padLeft(ByteString(sig.v.toByte), 32) ++
        ByteUtils.padLeft(ByteString(ByteUtils.bigIntToBytes(sig.r, 32)), 32) ++
        ByteUtils.padLeft(ByteString(ByteUtils.bigIntToBytes(sig.s, 32)), 32)
    val expected = ByteUtils.padLeft(expectedAddr.bytes, 32)
    assert(EllipticCurveRecovery.exec(input).contains(expected))

  test("ecrecover (0x01) — a v with more than the last byte set returns empty"):
    val input = ByteString(Array.fill[Byte](32)(0)) ++ bs(BigInt("256")) ++ bs(1) ++ bs(1)
    assert(EllipticCurveRecovery.exec(input).contains(ByteString.empty))

  test("ecrecover (0x01) — fixed 3000 gas"):
    assert(EllipticCurveRecovery.gas(ByteString.empty, EvmConfig.Frontier) == BigInt(3000))

  // ================================================================================================================
  //  alt-bn128 — ECADD (0x06), ECMUL (0x07), ECPAIRING (0x08); EIP-196/197
  // ================================================================================================================

  // Standard alt-bn128 generators (EIP-196/197).
  private val g2x0 = BigInt("10857046999023057135944570762232829481370756359578518086990519993285655852781")
  private val g2x1 = BigInt("11559732032986387107991004021392285783925812861821192530917403151452391805634")
  private val g2y0 = BigInt("8495653923123431417604973247489272438418190587263600148770280649306958101930")
  private val g2y1 = BigInt("4082367875863433681332203403145435568316851327593401208105741076214120093531")

  // 2·G1 (EIP-196 ECADD/ECMUL reference output).
  private val g2Doubled_x = BigInt("1368015179489954701390400359078579693043519447331113978918064868415326638035")
  private val g2Doubled_y = BigInt("9918110051302171585080402603319702774565515993150576347155970296011118125764")

  /** ECPAIRING G2 encoding is `[x_imag, x_real, y_imag, y_real]` per Fp2 element (EIP-197). */
  private def g2Encoded(x0: BigInt, x1: BigInt, y0: BigInt, y1: BigInt): ByteString =
    bs(x1) ++ bs(x0) ++ bs(y1) ++ bs(y0)

  test("ECADD (0x06) — G + G = 2G (EIP-196 vector)"):
    val input = bs(1) ++ bs(2) ++ bs(1) ++ bs(2)
    assert(Bn128Add.exec(input).contains(bs(g2Doubled_x) ++ bs(g2Doubled_y)))

  test("ECADD (0x06) — infinity + infinity = infinity (all-zero input/output)"):
    assert(Bn128Add.exec(ByteString(Array.fill[Byte](128)(0))).contains(ByteString(Array.fill[Byte](64)(0))))

  test("ECADD (0x06) — a point not on the curve is rejected (fail-loud)"):
    val input = bs(1) ++ bs(3) ++ bs(1) ++ bs(2)
    assert(Bn128Add.exec(input).isEmpty)

  test("ECADD (0x06) — EIP-1108 gas repricing (150 Istanbul, 500 pre-Istanbul)"):
    assert(
      Bn128Add.gas(ByteString.empty, EvmConfig.EthCancun) == BigInt(150) &&
        Bn128Add.gas(ByteString.empty, EvmConfig.Frontier) == BigInt(500)
    )

  test("ECMUL (0x07) — G · 2 = 2G (EIP-196 vector)"):
    val input = bs(1) ++ bs(2) ++ bs(2)
    assert(Bn128Mul.exec(input).contains(bs(g2Doubled_x) ++ bs(g2Doubled_y)))

  test("ECMUL (0x07) — G · 0 = infinity"):
    val input = bs(1) ++ bs(2) ++ bs(0)
    assert(Bn128Mul.exec(input).contains(ByteString(Array.fill[Byte](64)(0))))

  test("ECMUL (0x07) — EIP-1108 gas repricing (6000 Istanbul, 40000 pre-Istanbul)"):
    assert(
      Bn128Mul.gas(ByteString.empty, EvmConfig.EthCancun) == BigInt(6000) &&
        Bn128Mul.gas(ByteString.empty, EvmConfig.Frontier) == BigInt(40000)
    )

  test("ECPAIRING (0x08) — empty input checks true (empty product = 1)"):
    assert(Bn128Pairing.exec(ByteString.empty).contains(bs(1)))

  test("ECPAIRING (0x08) — bilinearity e(P, Q)·e(-P, Q) = 1"):
    val pair1 = bs(1) ++ bs(2) ++ g2Encoded(g2x0, g2x1, g2y0, g2y1)
    val pair2 = bs(1) ++ bs(fpP - 2) ++ g2Encoded(g2x0, g2x1, g2y0, g2y1)
    assert(Bn128Pairing.exec(pair1 ++ pair2).contains(bs(1)))

  test("ECPAIRING (0x08) — a single non-trivial pairing e(P, Q) != 1 returns 0"):
    val pair = bs(1) ++ bs(2) ++ g2Encoded(g2x0, g2x1, g2y0, g2y1)
    assert(Bn128Pairing.exec(pair).contains(bs(0)))

  test("ECPAIRING (0x08) — an on-curve G2 point outside the order-r subgroup is rejected (F-BN-1 guard)"):
    val offSubX0 = BigInt(0)
    val offSubX1 = BigInt(1)
    val offSubY0 = BigInt("16030832648161758264004549876281670301789752035901655478622495684390734237343")
    val offSubY1 = BigInt("18388737662781650394536484925627864106167267116893329563320683980901771000933")
    val pair = bs(1) ++ bs(2) ++ g2Encoded(offSubX0, offSubX1, offSubY0, offSubY1)
    assert(Bn128Pairing.exec(pair).isEmpty)

  test("ECPAIRING (0x08) — an input length not a multiple of 192 is rejected (fail-loud)"):
    assert(Bn128Pairing.exec(hx("0xdeadbeef")).isEmpty)

  test("ECPAIRING (0x08) — EIP-1108 gas 34000·k + 45000 (k = 2)"):
    val input = ByteString(Array.fill[Byte](2 * 192)(0))
    assert(Bn128Pairing.gas(input, EvmConfig.EthCancun) == BigInt(34000) * 2 + BigInt(45000))

  // ================================================================================================================
  //  BLAKE2F (0x09) — EIP-152
  // ================================================================================================================

  // EIP-152 reference vectors: (input hex, expected output hex or None for a malformed input).
  private val blake2fVectors: List[(String, Option[String])] = List(
    // vector 4: rounds = 0.
    "0000000048c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000001" ->
      Some(
        "08c9bcf367e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d282e6ad7f520e511f6c3e2b8c68059b9442be0454267ce079217e1319cde05b"
      ),
    // vector 5: rounds = 12.
    "0000000c48c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000001" ->
      Some(
        "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923"
      ),
    // vector 6: rounds = 12, final-block flag 0x00.
    "0000000c48c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000" ->
      Some(
        "75ab69d3190a562c51aef8d88f1c2775876944407270c42c9844252c26d2875298743e7f6d5ea2f2d3e8d226039cd31b4e426ac4f2d3d666a610c2116fde4735"
      ),
    // vector 8: final-block flag byte 0x02 — malformed, rejected.
    "0000000c48c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000002" -> None
  )

  test("BLAKE2F (0x09) — all EIP-152 reference vectors"):
    assert(
      blake2fVectors.forall((in, expected) => Blake2bCompress.exec(hx(in)).map(toHex) == expected)
    )

  test("BLAKE2F (0x09) — a wrong-length input is rejected (fail-loud) and priced 0"):
    val short = hx("0x00")
    assert(Blake2bCompress.exec(short).isEmpty && Blake2bCompress.gas(short, EvmConfig.Frontier) == BigInt(0))

  test("BLAKE2F (0x09) — gas equals the round count"):
    val rounds12 = hx(blake2fVectors(1)._1)
    assert(Blake2bCompress.gas(rounds12, EvmConfig.Frontier) == BigInt(12))

  // ================================================================================================================
  //  BLS12-381 (0x0b–0x11) — EIP-2537, wrappers over crypto.bls.Bls12381
  // ================================================================================================================

  final private case class BlsVec(name: String, input: String, expected: Option[String])

  private def loadBlsVectors(resource: String): List[BlsVec] =
    val raw = Using.resource(Source.fromInputStream(getClass.getResourceAsStream(resource)))(_.mkString)
    val body = raw.trim.stripPrefix("[").stripSuffix("]")
    val field = (obj: String, name: String) => s""""$name"\\s*:\\s*"([^"]*)"""".r.findFirstMatchIn(obj).map(_.group(1))
    body
      .split("\\}\\s*,\\s*\\{")
      .toList
      .map(_.stripPrefix("{").stripSuffix("}"))
      .filter(_.contains("\"Input\""))
      .map(obj => BlsVec(field(obj, "Name").getOrElse(""), field(obj, "Input").getOrElse(""), field(obj, "Expected")))

  private def blsSuccessKat(resource: String, wrapper: PrecompiledContract): Unit =
    val vecs = loadBlsVectors(resource)
    val _ = assert(vecs.nonEmpty, s"no vectors from $resource")
    vecs.foreach { v =>
      val expected = v.expected.getOrElse(fail(s"vector ${v.name} has no Expected"))
      assert(wrapper.exec(hx(v.input)).map(toHex).contains(expected), s"mismatch for ${v.name}")
    }

  private def blsFailKat(resource: String, wrapper: PrecompiledContract): Unit =
    val vecs = loadBlsVectors(resource)
    val _ = assert(vecs.nonEmpty, s"no vectors from $resource")
    vecs.foreach(v => assert(wrapper.exec(hx(v.input)).isEmpty, s"expected rejection for ${v.name}"))

  test("BLS native library is available on this platform"):
    assert(Bls12381.isAvailable)

  test("BLS12-381 G1 add (0x0b) — EIP-2537 KAT"):
    blsSuccessKat("/eip2537/add_G1_bls.json", BlsG1Add)

  test("BLS12-381 G1 MSM (0x0c) — EIP-2537 KAT"):
    blsSuccessKat("/eip2537/msm_G1_bls.json", BlsG1Msm)

  test("BLS12-381 G2 add (0x0d) — EIP-2537 KAT"):
    blsSuccessKat("/eip2537/add_G2_bls.json", BlsG2Add)

  test("BLS12-381 G2 MSM (0x0e) — EIP-2537 KAT"):
    blsSuccessKat("/eip2537/msm_G2_bls.json", BlsG2Msm)

  test("BLS12-381 pairing (0x0f) — EIP-2537 KAT"):
    blsSuccessKat("/eip2537/pairing_check_bls.json", BlsPairing)

  test("BLS12-381 map Fp→G1 (0x10) — EIP-2537 KAT"):
    blsSuccessKat("/eip2537/map_fp_to_G1_bls.json", BlsMapG1)

  test("BLS12-381 map Fp2→G2 (0x11) — EIP-2537 KAT"):
    blsSuccessKat("/eip2537/map_fp2_to_G2_bls.json", BlsMapG2)

  test("BLS12-381 G1 add (0x0b) — fail-* malformed/off-curve/off-subgroup rejection"):
    blsFailKat("/eip2537/fail-add_G1_bls.json", BlsG1Add)

  test("BLS12-381 G2 add (0x0d) — fail-* malformed/off-curve/off-subgroup rejection"):
    blsFailKat("/eip2537/fail-add_G2_bls.json", BlsG2Add)

  test("BLS12-381 G1 MSM (0x0c) — fail-* incl. subgroup rejection"):
    blsFailKat("/eip2537/fail-msm_G1_bls.json", BlsG1Msm)

  test("BLS12-381 G2 MSM (0x0e) — fail-* incl. subgroup rejection"):
    blsFailKat("/eip2537/fail-msm_G2_bls.json", BlsG2Msm)

  test("BLS12-381 pairing (0x0f) — fail-* incl. the 9 subgroup-rejection KATs (B-BLS-1)"):
    blsFailKat("/eip2537/fail-pairing_check_bls.json", BlsPairing)

  test("BLS12-381 map Fp→G1 (0x10) — fail-* rejection"):
    blsFailKat("/eip2537/fail-map_fp_to_G1_bls.json", BlsMapG1)

  test("BLS12-381 map Fp2→G2 (0x11) — fail-* rejection"):
    blsFailKat("/eip2537/fail-map_fp2_to_G2_bls.json", BlsMapG2)

  test("BLS12-381 — fixed gas costs (G1Add 375, G2Add 600, MapG1 5500, MapG2 23800)"):
    assert(
      BlsG1Add.gas(ByteString.empty, EvmConfig.EthPrague) == BigInt(375) &&
        BlsG2Add.gas(ByteString.empty, EvmConfig.EthPrague) == BigInt(600) &&
        BlsMapG1.gas(ByteString.empty, EvmConfig.EthPrague) == BigInt(5500) &&
        BlsMapG2.gas(ByteString.empty, EvmConfig.EthPrague) == BigInt(23800)
    )

  test("BLS12-381 — G1 MSM discount gas for k = 1 (12000·1·1000/1000 = 12000)"):
    val oneG1Pair = ByteString(Array.fill[Byte](160)(0))
    assert(BlsG1Msm.gas(oneG1Pair, EvmConfig.EthPrague) == BigInt(12000))

  test("BLS12-381 — G2 MSM discount gas for k = 2 (22500·2·1000/1000 = 45000)"):
    val twoG2Pairs = ByteString(Array.fill[Byte](2 * 288)(0))
    assert(BlsG2Msm.gas(twoG2Pairs, EvmConfig.EthPrague) == BigInt(22500) * 2 * 1000 / 1000)

  test("BLS12-381 — pairing gas 32600·k + 37700 (k = 1)"):
    val onePair = ByteString(Array.fill[Byte](384)(0))
    assert(BlsPairing.gas(onePair, EvmConfig.EthPrague) == BigInt(32600) + BigInt(37700))

  // ================================================================================================================
  //  P256VERIFY (0x0100) — EIP-7951
  // ================================================================================================================

  // Wycheproof SHA-256 #1 (valid), matching go-ethereum/besu P256VERIFY test data.
  private val p256Hash = hx("0xbb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023")
  private val p256R = hx("0x2ba3a8be6b94d5ec80a6d9d1190a436effe50d85a1eee859b8cc6af9bd5c2e18")
  private val p256S = hx("0x4cd60b855d442f5b3c7b11eb6c4e0ae7525fe710fab9aa7c77a67f79e6fadd76")
  private val p256Qx = hx("0x2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838")
  private val p256Qy = hx("0xc7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e")

  test("P256VERIFY (0x0100) — a valid signature returns 0x01 left-padded to 32 bytes"):
    val input = p256Hash ++ p256R ++ p256S ++ p256Qx ++ p256Qy
    assert(P256Verify.exec(input).contains(ByteUtils.padLeft(ByteString(1.toByte), 32)))

  test("P256VERIFY (0x0100) — a tampered signature returns empty output (EIP-7951 invalid signal)"):
    val badR = hx("0xd45c5740946b2a147f59262ee6f5bc90bd01ed280528b62b3aed5fc93f06f739")
    val badS = hx("0xb329f479a2bbd0a5c384ee1493b1f5186a87139cac5df4087c134b49156847db")
    val input = p256Hash ++ badR ++ badS ++ p256Qx ++ p256Qy
    assert(P256Verify.exec(input).contains(ByteString.empty))

  test("P256VERIFY (0x0100) — a wrong-length input returns empty output"):
    assert(P256Verify.exec(hx("0xdeadbeef")).contains(ByteString.empty))

  test("P256VERIFY (0x0100) — fixed 6900 gas"):
    assert(P256Verify.gas(ByteString.empty, EvmConfig.EthOsaka) == BigInt(6900))

  // ================================================================================================================
  //  KZG point evaluation (0x0a) — EIP-4844 (ETH only)
  // ================================================================================================================

  // Mainnet verify_kzg_proof_case_correct_proof_05c1f3685f3393f0.
  private val kzgCommitment =
    hx("0xa572cbea904d67468808c8eb50a9450c9721db309128012543902d0ac358a62ae28f75bb8f1c7c42c39a8c5529bf0f4e")
  private val kzgZ = hx("0x564c0a11a0f704f4fc3e8acfe0f8245f0ad1347b378fbf96e206da11a5d36306")
  private val kzgY = hx("0x0000000000000000000000000000000000000000000000000000000000000002")
  private val kzgProof =
    hx("0xc00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000")

  private def kzgVersionedHash(commitment: ByteString): ByteString =
    val h = sha256(commitment.toArray)
    h(0) = 0x01
    ByteString(h)

  test("KZG (0x0a) — a valid point-evaluation proof returns FIELD_ELEMENTS_PER_BLOB ‖ BLS_MODULUS"):
    val input = kzgVersionedHash(kzgCommitment) ++ kzgZ ++ kzgY ++ kzgCommitment ++ kzgProof
    val expected =
      ByteString(ByteUtils.bigIntToBytes(BigInt(4096), 32)) ++
        ByteString(
          ByteUtils.bigIntToBytes(
            BigInt("52435875175126190479447740508185965837690552500527637822603658699938581184513"),
            32
          )
        )
    assert(KzgPointEvaluation.exec(input).contains(expected))

  test("KZG (0x0a) — a tampered proof is rejected (fail-loud)"):
    val badProof = kzgProof.dropRight(1) ++ ByteString((kzgProof.last ^ 0x01).toByte)
    val input = kzgVersionedHash(kzgCommitment) ++ kzgZ ++ kzgY ++ kzgCommitment ++ badProof
    assert(KzgPointEvaluation.exec(input).isEmpty)

  test("KZG (0x0a) — a mismatched versioned hash is rejected"):
    val wrongHash = ByteString(Array.fill[Byte](32)(0x01.toByte))
    val input = wrongHash ++ kzgZ ++ kzgY ++ kzgCommitment ++ kzgProof
    assert(KzgPointEvaluation.exec(input).isEmpty)

  test("KZG (0x0a) — a wrong-length input is rejected"):
    assert(KzgPointEvaluation.exec(hx("0xdeadbeef")).isEmpty)

  test("KZG (0x0a) — fixed 50000 gas"):
    assert(KzgPointEvaluation.gas(ByteString.empty, EvmConfig.EthCancun) == BigInt(50000))

  // ================================================================================================================
  //  ModExp (0x05) — EIP-198 → EIP-2565 → Osaka (EIP-7883 gas + EIP-7823 bounds)
  // ================================================================================================================

  private def modexpInput(base: Int, exp: Int, mod: Int): ByteString =
    bs(1) ++ bs(1) ++ bs(1) ++ ByteString(base.toByte) ++ ByteString(exp.toByte) ++ ByteString(mod.toByte)

  test("ModExp (0x05) — 3^2 mod 5 = 4"):
    assert(ModExp.exec(modexpInput(3, 2, 5)).contains(ByteString(0x04.toByte)))

  test("ModExp (0x05) — per-fork gas differs across EIP-198 / EIP-2565 / EIP-7883"):
    // A large operand so the three cost models diverge.
    val baseLen = 64
    val input = bs(baseLen) ++ bs(1) ++ bs(baseLen) ++
      ByteString(Array.fill[Byte](baseLen)(0xff.toByte)) ++ ByteString(0x03.toByte) ++
      ByteString(Array.fill[Byte](baseLen)(0xff.toByte))
    val eip198 = ModExp.gas(input, EvmConfig.Frontier) // no 2565/7883
    val eip2565 = ModExp.gas(input, EvmConfig.EthCancun) // 2565, not 7883
    val osaka = ModExp.gas(input, EvmConfig.EthOsaka) // 7883
    assert(eip198 != eip2565 && eip2565 != osaka && eip198 > BigInt(0))

  test("ModExp (0x05) — EIP-2565 minimum floor is 200 gas"):
    assert(ModExp.gas(modexpInput(3, 2, 5), EvmConfig.EthCancun) == BigInt(200))

  test("ModExp (0x05) — EIP-7883 minimum floor is 500 gas"):
    assert(ModExp.gas(modexpInput(3, 2, 5), EvmConfig.EthOsaka) == BigInt(500))

  test("ModExp (0x05) — EIP-7823 rejects an operand length above 1024 bytes at entry (Osaka)"):
    val input = bs(2000) ++ bs(1) ++ bs(1)
    val result = ModExp.run(ctx(Some(ModExpAddr), TestWorld(), input = input, config = EvmConfig.EthOsaka))
    assert(result.error.contains(PreCompiledContractFail))

  test("ModExp (0x05) — the EIP-7823 bound does NOT apply pre-Osaka (Cancun)"):
    val input = bs(2000) ++ bs(0) ++ bs(0)
    val result = ModExp.run(ctx(Some(ModExpAddr), TestWorld(), input = input, config = EvmConfig.EthCancun))
    assert(result.error.isEmpty)

  // ================================================================================================================
  //  Fold-resolved precompile set — per-fork membership (forge co-sign)
  // ================================================================================================================

  test("Frontier precompile set is exactly 0x01–0x04"):
    assert(EvmConfig.Frontier.precompiles.keySet == Set(1, 2, 3, 4).map(a => Address(UInt256(a.toLong))))

  test("ETH Cancun precompile set is 0x01–0x0a (adds KZG 0x0a)"):
    assert(
      EvmConfig.EthCancun.precompiles.keySet == (1 to 10).map(a => Address(UInt256(a.toLong))).toSet &&
        EvmConfig.EthCancun.precompiles.contains(KzgPointEvalAddr)
    )

  test("ETH Prague adds the BLS12-381 0x0b–0x11 precompiles"):
    val blsAddrs = (0x0b to 0x11).map(a => Address(UInt256(a.toLong))).toSet
    assert(blsAddrs.subsetOf(EvmConfig.EthPrague.precompiles.keySet))

  test("ETH Osaka adds P256VERIFY 0x0100 and retains KZG 0x0a"):
    assert(
      EvmConfig.EthOsaka.precompiles.contains(P256VerifyAddr) &&
        EvmConfig.EthOsaka.precompiles.contains(KzgPointEvalAddr)
    )

  test("ETC Olympia EXCLUDES the 0x0a KZG precompile (no EIP-4844 on ETC)"):
    assert(!EvmConfig.EtcOlympia.precompiles.contains(KzgPointEvalAddr))

  test("ETC Olympia INCLUDES BLS 0x0b–0x11 and P256VERIFY 0x0100 (forge co-sign)"):
    val blsAddrs = (0x0b to 0x11).map(a => Address(UInt256(a.toLong))).toSet
    assert(
      blsAddrs.subsetOf(EvmConfig.EtcOlympia.precompiles.keySet) &&
        EvmConfig.EtcOlympia.precompiles.contains(P256VerifyAddr)
    )

  test("P256VERIFY 0x0100 is present under BOTH ETH Osaka AND ETC Olympia (dual-activation)"):
    assert(
      EvmConfig.EthOsaka.precompiles.contains(P256VerifyAddr) &&
        EvmConfig.EtcOlympia.precompiles.contains(P256VerifyAddr)
    )

  test("ETC Olympia is the ETH-Osaka set minus 0x0a KZG"):
    assert(
      EvmConfig.EtcOlympia.precompiles.keySet == EvmConfig.EthOsaka.precompiles.keySet - KzgPointEvalAddr
    )

  test("the fold reproduces the named EtcOlympia precompile set byte-for-byte"):
    assert(
      EvmConfig.deriveEvmConfigAt(EvmProposals.etcOlympiaSet).precompiles == EvmConfig.EtcOlympia.precompiles
    )

  test("the fold reproduces the named EthOsaka precompile set byte-for-byte"):
    assert(
      EvmConfig.deriveEvmConfigAt(EvmProposals.ethOsakaSet).precompiles == EvmConfig.EthOsaka.precompiles
    )

  // ================================================================================================================
  //  Interpreter short-circuit — a CALL to a precompile runs the wrapper, not account code
  // ================================================================================================================

  final private case class TestStorage(data: Map[UInt256, BigInt] = Map.empty) extends AccountStorage[TestStorage]:
    def store(offset: UInt256, value: BigInt): TestStorage = copy(data = data.updated(offset, value))
    def load(offset: UInt256): BigInt = data.getOrElse(offset, BigInt(0))

  final private case class TestWorld(
      accounts: Map[Address, Account] = Map.empty,
      codes: Map[Address, ByteString] = Map.empty,
      storages: Map[Address, TestStorage] = Map.empty,
      touched: Set[Address] = Set.empty
  ) extends WorldState[TestWorld, TestStorage]:
    def getAccount(address: Address): Option[Account] = accounts.get(address)
    def saveAccount(address: Address, account: Account): TestWorld = copy(accounts = accounts.updated(address, account))
    protected def deleteAccount(address: Address): TestWorld = copy(accounts = accounts - address)
    def getEmptyAccount: Account = Account.empty()
    def touchAccounts(addresses: Address*): TestWorld = copy(touched = touched ++ addresses)
    protected def clearTouchedAccounts: TestWorld = copy(touched = Set.empty)
    protected def noEmptyAccounts: Boolean = true
    def keepPrecompileTouched(world: TestWorld): TestWorld = this
    def getCode(address: Address): ByteString = codes.getOrElse(address, ByteString.empty)
    def getStorage(address: Address): TestStorage = storages.getOrElse(address, TestStorage())
    def getBlockHash(number: UInt256): Option[UInt256] = None
    def saveCode(address: Address, code: ByteString): TestWorld = copy(codes = codes.updated(address, code))
    def saveStorage(address: Address, storage: TestStorage): TestWorld =
      copy(storages = storages.updated(address, storage))

  private val caller = Address.fromHex("0x1111111111111111111111111111111111111111")

  private def header: com.chipprbots.fukuii.domain.BlockHeader =
    com.chipprbots.fukuii.domain.BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = Address.Zero,
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 17,
      number = 100,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = 1000,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty,
      baseFeePerGas = None
    )

  private def ctx(
      recipient: Option[Address],
      world: TestWorld,
      input: ByteString = ByteString.empty,
      gas: BigInt = 1_000_000,
      config: EvmConfig = EvmConfig.EthCancun
  ): CallContext[TestWorld, TestStorage] =
    CallContext[TestWorld, TestStorage](
      callerAddr = caller,
      originAddr = caller,
      recipientAddr = recipient,
      gasPrice = UInt256.Zero,
      startGas = gas,
      inputData = input,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = true,
      blockHeader = header,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set.empty,
      evmConfig = config,
      chainId = ChainId(1),
      originalWorld = world,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )

  private def funded(addr: Address): (Address, Account) = addr -> Account.empty()

  test("CALL to sha256 (0x02) short-circuits to the precompile, not account code"):
    val world = TestWorld(accounts = Map(funded(caller)))
    val result = EvmInterpreter[TestWorld, TestStorage]().run(ctx(Some(Sha256Addr), world, input = hx("0xdeadbeef")))
    assert(result.error.isEmpty && toHex(result.returnData) == toHex(Sha256.exec(hx("0xdeadbeef")).get))

  test("CALL to a precompile with insufficient gas halts OutOfGas"):
    val world = TestWorld(accounts = Map(funded(caller)))
    // ecrecover costs 3000; a 100-gas budget cannot cover it.
    val result = EvmInterpreter[TestWorld, TestStorage]().run(ctx(Some(EcRecoverAddr), world, gas = 100))
    assert(result.error.contains(OutOfGas))

  test("CALL to 0x0a under an ETC-Olympia config hits an empty account, not the KZG precompile"):
    // 0x0a is not in the ETC precompile set, so the call runs (empty) account code and returns cleanly.
    val world = TestWorld(accounts = Map(funded(caller)))
    val result =
      EvmInterpreter[TestWorld, TestStorage]().run(ctx(Some(KzgPointEvalAddr), world, config = EvmConfig.EtcOlympia))
    assert(result.error.isEmpty && result.returnData.isEmpty)

  test("CALL to 0x0a under an ETH-Cancun config DOES dispatch to the KZG precompile"):
    // A malformed (empty) input makes the KZG precompile fail loud — proving it was dispatched, not treated as code.
    val world = TestWorld(accounts = Map(funded(caller)))
    val result =
      EvmInterpreter[TestWorld, TestStorage]().run(ctx(Some(KzgPointEvalAddr), world, config = EvmConfig.EthCancun))
    assert(result.error.contains(PreCompiledContractFail))
