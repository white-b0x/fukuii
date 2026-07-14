package com.chipprbots.fukuii.crypto.bls

import scala.io.Source
import scala.util.Using

import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite

/** BLS12-381 (EIP-2537) known-answer tests against the besu `bls12-381` native backend.
  *
  * Vectors are the EIP-2537 reference test vectors (the `EIPs/assets/eip-2537` JSON set), the canonical
  * matter-labs/gnark-cross-checked KATs the precompile spec ships. Each success vector's `Input` and `Expected` are the
  * raw EIP-2537-encoded precompile input/output, so this is a byte-exact check of the primitive with no re-encoding.
  * Failure vectors (`fail-*.json`) assert the native backend rejects malformed input (wrong length, off-curve,
  * non-canonical field element) instead of returning a wrong answer.
  */
class Bls12381Spec extends AnyFunSuite:

  final private case class Vec(name: String, input: String, expected: Option[String], expectedError: Option[String])

  /** Minimal reader for the flat EIP-2537 vector files: a JSON array of objects whose string field values contain no
    * braces, so splitting on object boundaries and extracting `"Field": "value"` is sufficient (no JSON dependency on
    * the crypto test classpath).
    */
  private def loadVectors(resource: String): List[Vec] =
    val raw = Using.resource(Source.fromInputStream(getClass.getResourceAsStream(resource)))(_.mkString)
    val body = raw.trim.stripPrefix("[").stripSuffix("]")
    val field = (obj: String, name: String) => s""""$name"\\s*:\\s*"([^"]*)"""".r.findFirstMatchIn(obj).map(_.group(1))
    body
      .split("\\}\\s*,\\s*\\{")
      .toList
      .map(_.stripPrefix("{").stripSuffix("}"))
      .filter(_.contains("\"Input\""))
      .map(obj =>
        Vec(
          field(obj, "Name").getOrElse(""),
          field(obj, "Input").getOrElse(""),
          field(obj, "Expected"),
          field(obj, "ExpectedError")
        )
      )

  private def checkSuccess(resource: String, run: Array[Byte] => Either[String, Array[Byte]]): Unit =
    val vecs = loadVectors(resource)
    assert(vecs.nonEmpty, s"no vectors loaded from $resource")
    vecs.foreach { v =>
      val expected = v.expected.getOrElse(fail(s"vector ${v.name} has no Expected"))
      run(Hex.decode(v.input)) match
        case Right(out) => assert(Hex.toHexString(out) == expected, s"mismatch for ${v.name}")
        case Left(err)  => fail(s"vector ${v.name} unexpectedly failed: $err")
    }

  test("native BLS12-381 library is available on this platform"):
    assert(Bls12381.isAvailable)

  test("G1 add — EIP-2537 KAT"):
    checkSuccess("/eip2537/add_G1_bls.json", Bls12381.g1Add)

  test("G1 mul — EIP-2537 KAT"):
    checkSuccess("/eip2537/mul_G1_bls.json", Bls12381.g1Mul)

  test("G1 MSM — EIP-2537 KAT"):
    checkSuccess("/eip2537/msm_G1_bls.json", Bls12381.g1Msm)

  test("G2 add — EIP-2537 KAT"):
    checkSuccess("/eip2537/add_G2_bls.json", Bls12381.g2Add)

  test("G2 mul — EIP-2537 KAT"):
    checkSuccess("/eip2537/mul_G2_bls.json", Bls12381.g2Mul)

  test("G2 MSM — EIP-2537 KAT"):
    checkSuccess("/eip2537/msm_G2_bls.json", Bls12381.g2Msm)

  test("pairing check — EIP-2537 KAT"):
    checkSuccess("/eip2537/pairing_check_bls.json", Bls12381.pairing)

  test("map fp to G1 — EIP-2537 KAT"):
    checkSuccess("/eip2537/map_fp_to_G1_bls.json", Bls12381.mapFpToG1)

  test("map fp2 to G2 — EIP-2537 KAT"):
    checkSuccess("/eip2537/map_fp2_to_G2_bls.json", Bls12381.mapFp2ToG2)

  test("pairing identity e(P, -P)·e(P, P) = 1 (from the KAT set) yields the '01' word"):
    // bls_pairing_e(0,0): input is two (G1,G2) pairs whose pairing product is the GT identity.
    val vecs = loadVectors("/eip2537/pairing_check_bls.json")
    val identity = vecs.find(_.name.contains("e(0,0)")).getOrElse(vecs.head)
    Bls12381.pairing(Hex.decode(identity.input)) match
      case Right(out) =>
        assert(out.length == 32)
        assert(Hex.toHexString(out) == "0000000000000000000000000000000000000000000000000000000000000001")
      case Left(err) => fail(s"pairing identity vector failed: $err")

  test("malformed G1 add input is rejected (fail KAT)"):
    val vecs = loadVectors("/eip2537/fail-add_G1_bls.json")
    assert(vecs.nonEmpty)
    vecs.foreach { v =>
      assert(Bls12381.g1Add(Hex.decode(v.input)).isLeft, s"expected rejection for ${v.name}")
    }

  test("malformed pairing input is rejected (fail KAT)"):
    val vecs = loadVectors("/eip2537/fail-pairing_check_bls.json")
    assert(vecs.nonEmpty)
    vecs.foreach { v =>
      assert(Bls12381.pairing(Hex.decode(v.input)).isLeft, s"expected rejection for ${v.name}")
    }
