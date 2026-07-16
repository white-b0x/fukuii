package com.chipprbots.fukuii.crypto.kzg

import scala.util.Using

import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite

/** KZG (EIP-4844 / EIP-7594) known-answer and round-trip tests against the c-kzg-4844 native backend loaded with the
  * mainnet ceremony trusted setup.
  *
  * Byte-exact KATs are the consensus-spec-tests `kzg-mainnet` fixtures (as shipped in go-kzg-4844 v1.1.0 `tests/`): the
  * zero-blob commitment and three `verify_kzg_proof` cases (two valid, one incorrect). The blob-proof and EIP-7594 cell
  * paths are exercised by full compute→verify round-trips over a deterministic canonical blob (with tamper-rejection),
  * which drives the native commitment, proof, batch and PeerDAS cell/recovery code paths end-to-end.
  */
class KzgSpec extends AnyFunSuite:

  private def hex(s: String): Array[Byte] = Hex.decode(s.stripPrefix("0x"))
  private def toHex(b: Array[Byte]): String = Hex.toHexString(b)

  /** A canonical, deterministic non-zero blob: 4096 field elements, element `i` holds `i` in its two low bytes (≤ 65535
    * < BLS_MODULUS), so every element is canonical.
    */
  private def canonicalBlob(): Array[Byte] =
    val blob = new Array[Byte](Kzg.BytesPerBlob)
    var i = 0
    while i < 4096 do
      val base = i * Kzg.BytesPerFieldElement
      blob(base + 30) = ((i >> 8) & 0xff).toByte
      blob(base + 31) = (i & 0xff).toByte
      i += 1
    blob

  test("bundled trusted_setup.txt provenance — pinned SHA-256 + zero-blob commitment KAT"):
    // Provenance lock: the bundled ceremony resource must hash to this pinned canonical-mainnet digest.
    val bytes = Using.resource(getClass.getResourceAsStream("/trusted_setup.txt"))(_.readAllBytes())
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    assert(
      toHex(sha) == "d39b9f2d047cc9dca2de58f264b6a09448ccd34db967881a6713eacacf0f26b7" &&
        // Transitive proof the loaded setup is the ceremony: the all-zero blob commits to c0‖00·47.
        toHex(Kzg.blobToKzgCommitment(new Array[Byte](Kzg.BytesPerBlob))) == "c0" + "00" * 47,
      "bundled trusted setup must match the pinned SHA-256 and produce the zero-blob commitment KAT"
    )

  test("blobToKzgCommitment matches the zero-blob mainnet KAT"):
    // consensus-spec-tests: blob_to_kzg_commitment_case_valid_blob_0951cfd9ab47a8d3 (all-zero blob).
    val zeroBlob = new Array[Byte](Kzg.BytesPerBlob)
    val commitment = Kzg.blobToKzgCommitment(zeroBlob)
    assert(
      commitment.length == Kzg.BytesPerCommitment &&
        toHex(commitment) == "c0" + "00" * 47,
      "zero-blob commitment must have the correct length and match the mainnet KAT"
    )

  test("verifyKzgProof matches mainnet verify_kzg_proof KATs (valid + incorrect)"):
    assert(
      // verify_kzg_proof_case_correct_proof_05c1f3685f3393f0 → true
      Kzg.verifyKzgProof(
        hex("0xa572cbea904d67468808c8eb50a9450c9721db309128012543902d0ac358a62ae28f75bb8f1c7c42c39a8c5529bf0f4e"),
        hex("0x564c0a11a0f704f4fc3e8acfe0f8245f0ad1347b378fbf96e206da11a5d36306"),
        hex("0x0000000000000000000000000000000000000000000000000000000000000002"),
        hex("0xc00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000")
      ) &&
        // verify_kzg_proof_case_correct_proof_08f9e2f1cb3d39db → true
        Kzg.verifyKzgProof(
          hex("0xb7f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"),
          hex("0x73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000000"),
          hex("0x73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000000"),
          hex("0xc00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000")
        ) &&
        // verify_kzg_proof_case_incorrect_proof_02e696ada7d4631d → false
        !Kzg.verifyKzgProof(
          hex("0xc00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"),
          hex("0x0000000000000000000000000000000000000000000000000000000000000002"),
          hex("0x0000000000000000000000000000000000000000000000000000000000000000"),
          hex("0x97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb")
        ),
      "the two correct-proof KATs must verify and the incorrect-proof KAT must be rejected"
    )

  test("computeKzgProof + verifyKzgProof round-trip over a canonical blob"):
    val blob = canonicalBlob()
    val commitment = Kzg.blobToKzgCommitment(blob)
    val z = hex("0x0000000000000000000000000000000000000000000000000000000000000002")
    val py = Kzg.computeKzgProof(blob, z)
    val badY = py.y.clone(); badY(31) = (badY(31) ^ 0x01).toByte // tamper the claimed evaluation
    assert(
      py.proof.length == Kzg.BytesPerProof &&
        py.y.length == Kzg.BytesPerFieldElement &&
        Kzg.verifyKzgProof(commitment, z, py.y, py.proof) &&
        !Kzg.verifyKzgProof(commitment, z, badY, py.proof), // tampered evaluation must be rejected
      "proof/eval lengths must match, the correct proof must verify, and a tampered evaluation must be rejected"
    )

  test("computeBlobKzgProof + verifyBlobKzgProof round-trip and tamper-reject"):
    val blob = canonicalBlob()
    val commitment = Kzg.blobToKzgCommitment(blob)
    val proof = Kzg.computeBlobKzgProof(blob, commitment)
    val otherCommitment =
      Kzg.blobToKzgCommitment(new Array[Byte](Kzg.BytesPerBlob)) // wrong commitment (different blob)
    assert(
      proof.length == Kzg.BytesPerProof &&
        Kzg.verifyBlobKzgProof(blob, commitment, proof) &&
        !Kzg.verifyBlobKzgProof(blob, otherCommitment, proof), // mismatched commitment must be rejected
      "proof length must match, the correct commitment must verify, and a mismatched commitment must be rejected"
    )

  test("verifyBlobKzgProofBatch over two blobs"):
    val blob1 = canonicalBlob()
    val blob2 = new Array[Byte](Kzg.BytesPerBlob)
    val c1 = Kzg.blobToKzgCommitment(blob1)
    val c2 = Kzg.blobToKzgCommitment(blob2)
    val p1 = Kzg.computeBlobKzgProof(blob1, c1)
    val p2 = Kzg.computeBlobKzgProof(blob2, c2)
    assert(
      Kzg.verifyBlobKzgProofBatch(blob1 ++ blob2, c1 ++ c2, p1 ++ p2, 2L) &&
        !Kzg.verifyBlobKzgProofBatch(blob1 ++ blob2, c1 ++ c2, p2 ++ p1, 2L), // swapped proofs must fail the batch
      "the correctly-ordered batch must verify and the swapped-proof batch must be rejected"
    )

  test("EIP-7594 computeCellsAndKzgProofs + verifyCellKzgProofBatch round-trip"):
    val blob = canonicalBlob()
    val commitment = Kzg.blobToKzgCommitment(blob)
    val cp = Kzg.computeCellsAndKzgProofs(blob)
    // verify all 128 cells against the single commitment
    val commitments = Array.fill(Kzg.CellsPerExtBlob)(commitment).flatten
    val indices = (0 until Kzg.CellsPerExtBlob).map(_.toLong).toArray
    val flatCells = cp.cells.flatten
    val flatProofs = cp.proofs.flatten
    assert(
      cp.cells.length == Kzg.CellsPerExtBlob &&
        cp.proofs.length == Kzg.CellsPerExtBlob &&
        cp.cells.forall(_.length == Kzg.BytesPerCell) &&
        cp.proofs.forall(_.length == Kzg.BytesPerProof) &&
        Kzg.verifyCellKzgProofBatch(commitments, indices, flatCells, flatProofs),
      "cell/proof counts and lengths must match, and the full-cell batch must verify against the commitment"
    )

  test("EIP-7594 recoverCellsAndKzgProofs from the first half reconstructs all cells"):
    val blob = canonicalBlob()
    val full = Kzg.computeCellsAndKzgProofs(blob)
    val half = Kzg.CellsPerExtBlob / 2
    val indices = (0 until half).map(_.toLong).toArray
    val partialCells = full.cells.take(half).flatten
    val recovered = Kzg.recoverCellsAndKzgProofs(indices, partialCells)
    assert(
      recovered.cells.length == Kzg.CellsPerExtBlob &&
        // recovered cells are byte-identical to the originally computed cells
        recovered.cells.zip(full.cells).forall { case (r, f) => toHex(r) == toHex(f) },
      "recovered cell count must match and recovered cells must be byte-identical to the originally computed cells"
    )
