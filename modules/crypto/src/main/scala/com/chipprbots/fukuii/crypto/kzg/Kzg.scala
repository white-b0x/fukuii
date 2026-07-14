package com.chipprbots.fukuii.crypto.kzg

import java.util.concurrent.atomic.AtomicReference

import ethereum.ckzg4844.CKZG4844JNI

/** KZG polynomial-commitment primitive for EIP-4844 (blob transactions) and EIP-7594 (PeerDAS cell proofs), backed by
  * the c-kzg-4844 native library via the `jc-kzg-4844` JNI bindings.
  *
  * ==Layering==
  * This is the byte-exact *primitive* only — the operations `evm` (L3) calls from the point-eval precompile (`0x0a`)
  * and the consensus layer calls for blob/cell verification. The EVM precompile wrapper (gas schedule, 192-byte input
  * decode, versioned-hash check, `0x0a` dispatch) is a separate `evm` concern and does NOT live here. KZG is a peer of
  * the alt-bn128 (`zksnark`) tower: both are consensus pairing-based primitives, and both belong in `crypto` L0.
  *
  * ==Native backend rationale==
  * KZG has no practical pure-JVM implementation at consensus throughput — every reference client
  * (geth/besu/nethermind/reth) uses the same c-kzg-4844 (or the crate-crypto rust) native library, and the
  * trusted-setup + FFT arithmetic is the shared byte-authority. Native JNI is therefore the default here, not an
  * OPTIONAL(role) fast-path (contrast keccak/secp256k1/alt-bn128, where a pure BC path is the correctness floor). The
  * `jc-kzg-4844` jar bundles the platform native libs (linux/darwin × x86-64/aarch64), so no separate native install is
  * required.
  *
  * ==Trusted-setup lifecycle invariant (process-global)==
  * The c-kzg native library holds the trusted setup in **process-global** state — there is exactly one loaded setup per
  * JVM, guarded inside the native `.so`. [[loadTrustedSetup]] is idempotent and thread-safe at the Scala layer: the
  * first call loads the bundled mainnet ceremony `trusted_setup.txt`; subsequent calls are no-ops. Every operation
  * below calls [[loadTrustedSetup]] first, so callers never have to sequence the load explicitly. Do NOT call
  * `CKZG4844JNI.freeTrustedSetup()` from application code while any KZG op may run — freeing is a one-way global
  * teardown and would break every other consumer in the process.
  */
object Kzg:

  /** Blob size in bytes (4096 field elements × 32 bytes) — EIP-4844 `BYTES_PER_BLOB`. */
  val BytesPerBlob: Int = CKZG4844JNI.BYTES_PER_BLOB

  /** KZG commitment size — a compressed G1 point, 48 bytes. */
  val BytesPerCommitment: Int = CKZG4844JNI.BYTES_PER_COMMITMENT

  /** KZG proof size — a compressed G1 point, 48 bytes. */
  val BytesPerProof: Int = CKZG4844JNI.BYTES_PER_PROOF

  /** Field-element size — 32 bytes (a `z` evaluation point or `y` value). */
  val BytesPerFieldElement: Int = CKZG4844JNI.BYTES_PER_FIELD_ELEMENT

  /** EIP-7594 extended-blob cell count — 128 cells per blob. */
  val CellsPerExtBlob: Int = CKZG4844JNI.CELLS_PER_EXT_BLOB

  /** EIP-7594 cell size — 2048 bytes (64 field elements). */
  val BytesPerCell: Int = CKZG4844JNI.BYTES_PER_CELL

  /** Resource path (classpath-absolute) of the bundled mainnet KZG ceremony trusted setup. Byte -identical to the
    * c-kzg-4844 / besu mainnet `trusted_setup.txt` (PeerDAS format: 4096 g1 lagrange + 65 g2 monomial + 4096 g1
    * monomial points).
    */
  private val TrustedSetupResource: String = "/trusted_setup.txt"

  /** MSM precompute width passed to the native loader. 0 disables the precompute table: correctness is unaffected (it
    * is a pure MSM speed/space tradeoff for the EIP-7594 cell path), and 0 keeps the load allocation-light and
    * deterministic.
    */
  private val Precompute: Long = 0L

  private enum SetupState:
    case Unloaded, Loaded

  private val state: AtomicReference[SetupState] = new AtomicReference(SetupState.Unloaded)

  /** Load the bundled mainnet trusted setup into the process-global native state. Idempotent and thread-safe: the first
    * successful call loads it; later calls return immediately. Safe to call at the head of every operation.
    */
  def loadTrustedSetup(): Unit =
    if state.get() ne SetupState.Loaded then
      synchronized {
        if state.get() ne SetupState.Loaded then
          CKZG4844JNI.loadNativeLibrary()
          CKZG4844JNI.loadTrustedSetupFromResource(TrustedSetupResource, getClass, Precompute)
          state.set(SetupState.Loaded)
      }

  /** True once the trusted setup has been loaded in this JVM. */
  def isTrustedSetupLoaded: Boolean = state.get() eq SetupState.Loaded

  // ---------------------------------------------------------------------------------------------
  // EIP-4844 — blob commitments and proofs
  // ---------------------------------------------------------------------------------------------

  /** Commit to a blob: `blob → KZG commitment` (compressed G1 point, 48 bytes).
    *
    * @param blob
    *   a [[BytesPerBlob]]-byte blob (4096 canonical BLS field elements).
    */
  def blobToKzgCommitment(blob: Array[Byte]): Array[Byte] =
    loadTrustedSetup()
    CKZG4844JNI.blobToKzgCommitment(blob)

  /** A KZG opening proof together with the claimed evaluation `y = p(z)`. */
  final case class ProofAndY(proof: Array[Byte], y: Array[Byte])

  /** Compute the KZG opening proof for a blob at evaluation point `z`.
    *
    * @param blob
    *   a [[BytesPerBlob]]-byte blob.
    * @param z
    *   the 32-byte evaluation point (a canonical field element).
    * @return
    *   the 48-byte proof and the 32-byte evaluation `y = p(z)`.
    */
  def computeKzgProof(blob: Array[Byte], z: Array[Byte]): ProofAndY =
    loadTrustedSetup()
    val r = CKZG4844JNI.computeKzgProof(blob, z)
    ProofAndY(r.getProof, r.getY)

  /** Compute the blob KZG proof (the EIP-4844 blob-tx proof binding a blob to its commitment).
    *
    * @param blob
    *   a [[BytesPerBlob]]-byte blob.
    * @param commitment
    *   the 48-byte commitment produced by [[blobToKzgCommitment]].
    */
  def computeBlobKzgProof(blob: Array[Byte], commitment: Array[Byte]): Array[Byte] =
    loadTrustedSetup()
    CKZG4844JNI.computeBlobKzgProof(blob, commitment)

  /** Verify a KZG opening: does `commitment` open to `y` at `z` under `proof`? This is the exact check the
    * point-evaluation precompile (`0x0a`) performs after decoding its 192-byte input.
    *
    * @param commitment
    *   48-byte commitment.
    * @param z
    *   32-byte evaluation point.
    * @param y
    *   32-byte claimed evaluation.
    * @param proof
    *   48-byte proof.
    */
  def verifyKzgProof(commitment: Array[Byte], z: Array[Byte], y: Array[Byte], proof: Array[Byte]): Boolean =
    loadTrustedSetup()
    CKZG4844JNI.verifyKzgProof(commitment, z, y, proof)

  /** Verify a blob KZG proof: does `proof` bind `blob` to `commitment`?
    *
    * @param blob
    *   [[BytesPerBlob]]-byte blob.
    * @param commitment
    *   48-byte commitment.
    * @param proof
    *   48-byte blob proof from [[computeBlobKzgProof]].
    */
  def verifyBlobKzgProof(blob: Array[Byte], commitment: Array[Byte], proof: Array[Byte]): Boolean =
    loadTrustedSetup()
    CKZG4844JNI.verifyBlobKzgProof(blob, commitment, proof)

  /** Batch-verify blob KZG proofs. The three arrays are the flat concatenations of the per-blob blobs, commitments and
    * proofs; all three must describe the same `count` entries.
    *
    * @param blobs
    *   `count × `[[BytesPerBlob]]` bytes.
    * @param commitments
    *   `count × `[[BytesPerCommitment]]` bytes.
    * @param proofs
    *   `count × `[[BytesPerProof]]` bytes.
    * @param count
    *   number of blob/commitment/proof triples.
    */
  def verifyBlobKzgProofBatch(
      blobs: Array[Byte],
      commitments: Array[Byte],
      proofs: Array[Byte],
      count: Long
  ): Boolean =
    loadTrustedSetup()
    CKZG4844JNI.verifyBlobKzgProofBatch(blobs, commitments, proofs, count)

  // ---------------------------------------------------------------------------------------------
  // EIP-7594 — PeerDAS cells and cell proofs
  // ---------------------------------------------------------------------------------------------

  /** The 128 extended-blob cells and their 128 cell proofs, split per-cell. */
  final case class CellsAndProofs(cells: Array[Array[Byte]], proofs: Array[Array[Byte]])

  /** Compute the [[CellsPerExtBlob]] extension cells for a blob (no proofs).
    *
    * @return
    *   `cells(i)` is [[BytesPerCell]] bytes; there are [[CellsPerExtBlob]] cells.
    */
  def computeCells(blob: Array[Byte]): Array[Array[Byte]] =
    loadTrustedSetup()
    CKZG4844JNI.computeCells(blob).grouped(BytesPerCell).toArray

  /** Compute the EIP-7594 cells and cell proofs for a blob.
    *
    * @return
    *   [[CellsPerExtBlob]] cells (each [[BytesPerCell]] bytes) and [[CellsPerExtBlob]] proofs (each [[BytesPerProof]]
    *   bytes).
    */
  def computeCellsAndKzgProofs(blob: Array[Byte]): CellsAndProofs =
    loadTrustedSetup()
    val r = CKZG4844JNI.computeCellsAndKzgProofs(blob)
    CellsAndProofs(r.getCells.grouped(BytesPerCell).toArray, r.getProofs.grouped(BytesPerProof).toArray)

  /** Recover all cells and proofs from a partial (≥ 50%) set of cells (PeerDAS erasure recovery).
    *
    * @param cellIndices
    *   the indices (0 ..< [[CellsPerExtBlob]]) of the supplied cells.
    * @param cells
    *   the flat concatenation of the supplied cells (`cellIndices.length × `[[BytesPerCell]]` bytes).
    * @return
    *   the full [[CellsPerExtBlob]] cells and proofs.
    */
  def recoverCellsAndKzgProofs(cellIndices: Array[Long], cells: Array[Byte]): CellsAndProofs =
    loadTrustedSetup()
    val r = CKZG4844JNI.recoverCellsAndKzgProofs(cellIndices, cells)
    CellsAndProofs(r.getCells.grouped(BytesPerCell).toArray, r.getProofs.grouped(BytesPerProof).toArray)

  /** Batch-verify cell KZG proofs. The arrays are the flat per-cell concatenations, one entry per cell being verified.
    *
    * @param commitments
    *   flat commitments, one [[BytesPerCommitment]]-byte commitment per cell.
    * @param cellIndices
    *   the cell index for each cell.
    * @param cells
    *   flat cells (`n × `[[BytesPerCell]]` bytes).
    * @param proofs
    *   flat proofs (`n × `[[BytesPerProof]]` bytes).
    */
  def verifyCellKzgProofBatch(
      commitments: Array[Byte],
      cellIndices: Array[Long],
      cells: Array[Byte],
      proofs: Array[Byte]
  ): Boolean =
    loadTrustedSetup()
    CKZG4844JNI.verifyCellKzgProofBatch(commitments, cellIndices, cells, proofs)
