package com.chipprbots.fukuii.crypto.zksnark

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.ByteUtils
import com.chipprbots.fukuii.crypto.zksnark.BN128.BN128G1
import com.chipprbots.fukuii.crypto.zksnark.BN128.BN128G2
import com.chipprbots.fukuii.crypto.zksnark.PairingCheck.G1G2Pair

/** Optimal ate pairing over BN128 — the EIP-197 `ECPAIRING` predicate.
  *
  * Uses the standard alt-bn128 generators: G1 = (1, 2), G2 the canonical twist generator. The core
  * bilinearity property `e(P, Q) · e(-P, Q) = 1` gives a self-checking pairing vector without a
  * hard-coded `Fp12` target value.
  */
class PairingCheckSpec extends AnyFunSuite:

  private val fpP = Fp.P
  private def bs(n: BigInt): ByteString = ByteString(ByteUtils.bigIntToBytes(n, 32))

  // G1 generator and its negation (x, -y).
  private val g1: BN128G1 = BN128G1(bs(1), bs(2)).get
  private val g1Neg: BN128G1 = BN128G1(bs(1), bs(fpP - 2)).get

  // G2 generator, coordinates as (real, imag) pairs — Fp2(a, b) = a + b·u.
  private val g2x0 = BigInt("10857046999023057135944570762232829481370756359578518086990519993285655852781")
  private val g2x1 = BigInt("11559732032986387107991004021392285783925812861821192530917403151452391805634")
  private val g2y0 = BigInt("8495653923123431417604973247489272438418190587263600148770280649306958101930")
  private val g2y1 = BigInt("4082367875863433681332203403145435568316851327593401208105741076214120093531")
  private val g2: BN128G2 = BN128G2(bs(g2x0), bs(g2x1), bs(g2y0), bs(g2y1)).get

  test("the standard generators are valid on-curve group elements"):
    assert(BN128G1(bs(1), bs(2)).isDefined)
    assert(BN128G2(bs(g2x0), bs(g2x1), bs(g2y0), bs(g2y1)).isDefined)

  test("empty pairing set checks true (empty product = 1)"):
    assert(PairingCheck.pairingCheck(Seq.empty))

  test("e(P, Q) · e(-P, Q) == 1 (bilinearity)"):
    val pairs = Seq(G1G2Pair(g1, g2), G1G2Pair(g1Neg, g2))
    assert(PairingCheck.pairingCheck(pairs))

  test("a single non-trivial pairing e(P, Q) != 1"):
    assert(!PairingCheck.pairingCheck(Seq(G1G2Pair(g1, g2))))

  test("pairing with the point at infinity in G1 contributes the identity"):
    val infinityG1 = BN128G1(bs(0), bs(0)).get
    // e(O, Q) = 1, so {e(O,Q)} alone checks true.
    assert(PairingCheck.pairingCheck(Seq(G1G2Pair(infinityG1, g2))))
