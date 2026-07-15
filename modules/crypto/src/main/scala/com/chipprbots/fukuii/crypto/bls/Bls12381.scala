package com.chipprbots.fukuii.crypto.bls

import com.sun.jna.ptr.IntByReference
import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings

/** BLS12-381 curve operations for EIP-2537, backed by the besu `bls12-381` native library (`LibEthPairings`, the
  * matter-labs EIP-1962 `eth_pairings` backend via JNA — not gnark; besu's own precompile has since moved to its gnark
  * `LibGnarkEIP2537` binding, but `LibEthPairings` is verified byte-correct against the full EIP-2537 KAT set here).
  *
  * ==Layering==
  * These are the byte-exact *primitives* — the group/field operations the EVM BLS12-381 precompiles call. The
  * precompile wrappers (gas schedule, MSM discount table, input-length dispatch, and the precompile-address mapping,
  * which differs across EIP-2537 revisions) are a separate `evm` (L3) concern and do NOT live here. Old fukuii inlined
  * these native calls directly at the precompile site (`vm/PrecompiledContracts.scala`); this module fixes that
  * mislayering by making BLS12-381 a first-class `crypto` L0 primitive, a peer of the alt-bn128 (`zksnark`) tower and
  * [[com.chipprbots.fukuii.crypto.kzg.Kzg]].
  *
  * ==Encoding==
  * Inputs and outputs are the canonical EIP-2537 byte encodings, passed through to the native backend unmodified:
  *   - Fp field element: 64 bytes (16-byte zero pad ‖ 48-byte big-endian).
  *   - G1 point: 128 bytes (x ‖ y, each an Fp).
  *   - G2 point: 256 bytes (x ‖ y, each an Fp2 = c0 ‖ c1).
  *   - scalar: 32 bytes, big-endian.
  *   - pairing result: 32 bytes, `0…01` (pairing product is identity) or `0…00`.
  *
  * The native backend performs the mandatory subgroup checks; malformed points, non-canonical field elements, and
  * off-curve inputs return a [[Left]] error rather than a wrong answer.
  */
object Bls12381:

  /** An EIP-2537 operation and its fixed output size (bytes). The raw op byte is the value the native
    * `eip2537_perform_operation` dispatches on.
    */
  enum Op(val rawValue: Byte, val outputSize: Int):
    case G1Add extends Op(LibEthPairings.BLS12_G1ADD_OPERATION_RAW_VALUE, 128)
    case G1Mul extends Op(LibEthPairings.BLS12_G1MUL_OPERATION_RAW_VALUE, 128)
    case G1Msm extends Op(LibEthPairings.BLS12_G1MULTIEXP_OPERATION_RAW_VALUE, 128)
    case G2Add extends Op(LibEthPairings.BLS12_G2ADD_OPERATION_RAW_VALUE, 256)
    case G2Mul extends Op(LibEthPairings.BLS12_G2MUL_OPERATION_RAW_VALUE, 256)
    case G2Msm extends Op(LibEthPairings.BLS12_G2MULTIEXP_OPERATION_RAW_VALUE, 256)
    case Pairing extends Op(LibEthPairings.BLS12_PAIR_OPERATION_RAW_VALUE, 32)
    case MapFpToG1 extends Op(LibEthPairings.BLS12_MAP_FP_TO_G1_OPERATION_RAW_VALUE, 128)
    case MapFp2ToG2 extends Op(LibEthPairings.BLS12_MAP_FP2_TO_G2_OPERATION_RAW_VALUE, 256)

  /** True if the native BLS12-381 library loaded on this platform. Guards the two throwing failure modes
    * (`NoClassDefFoundError` if the jar is absent, `UnsatisfiedLinkError` if no native lib matches the platform) so
    * callers can branch without catching.
    */
  def isAvailable: Boolean =
    try LibEthPairings.ENABLED
    catch case _: UnsatisfiedLinkError | _: NoClassDefFoundError => false

  /** Dispatch an EIP-2537 operation against the native backend.
    *
    * @param op
    *   the operation to perform.
    * @param input
    *   the canonical EIP-2537-encoded input for `op`.
    * @return
    *   [[Right]] with the `op.outputSize`-byte result on success, or [[Left]] with the native error message on invalid
    *   input (off-curve point, non-canonical field element, wrong length, …).
    */
  def perform(op: Op, input: Array[Byte]): Either[String, Array[Byte]] =
    val result = new Array[Byte](LibEthPairings.EIP2537_PREALLOCATE_FOR_RESULT_BYTES)
    val error = new Array[Byte](LibEthPairings.EIP2537_PREALLOCATE_FOR_ERROR_BYTES)
    val outLen = new IntByReference(LibEthPairings.EIP2537_PREALLOCATE_FOR_RESULT_BYTES)
    val errLen = new IntByReference(LibEthPairings.EIP2537_PREALLOCATE_FOR_ERROR_BYTES)

    val status =
      LibEthPairings.eip2537_perform_operation(op.rawValue, input, input.length, result, outLen, error, errLen)

    if status == 0 then Right(result.take(outLen.getValue))
    else Left(new String(error, 0, errLen.getValue, java.nio.charset.StandardCharsets.UTF_8))

  /** G1 point addition: input two G1 points (256 bytes), output one G1 point (128 bytes). */
  def g1Add(input: Array[Byte]): Either[String, Array[Byte]] = perform(Op.G1Add, input)

  /** G1 scalar multiplication: input G1 ‖ scalar (160 bytes), output G1 (128 bytes). */
  def g1Mul(input: Array[Byte]): Either[String, Array[Byte]] = perform(Op.G1Mul, input)

  /** G1 multi-scalar multiplication: input `k × (G1 ‖ scalar)` (k·160 bytes), output G1 (128 bytes). */
  def g1Msm(input: Array[Byte]): Either[String, Array[Byte]] = perform(Op.G1Msm, input)

  /** G2 point addition: input two G2 points (512 bytes), output one G2 point (256 bytes). */
  def g2Add(input: Array[Byte]): Either[String, Array[Byte]] = perform(Op.G2Add, input)

  /** G2 scalar multiplication: input G2 ‖ scalar (288 bytes), output G2 (256 bytes). */
  def g2Mul(input: Array[Byte]): Either[String, Array[Byte]] = perform(Op.G2Mul, input)

  /** G2 multi-scalar multiplication: input `k × (G2 ‖ scalar)` (k·288 bytes), output G2 (256 bytes). */
  def g2Msm(input: Array[Byte]): Either[String, Array[Byte]] = perform(Op.G2Msm, input)

  /** Pairing check: input `k × (G1 ‖ G2)` (k·384 bytes), output 32 bytes — `0…01` if the product of pairings is the
    * identity in GT, else `0…00`.
    */
  def pairing(input: Array[Byte]): Either[String, Array[Byte]] = perform(Op.Pairing, input)

  /** Map field element to G1: input one Fp (64 bytes), output G1 (128 bytes). */
  def mapFpToG1(input: Array[Byte]): Either[String, Array[Byte]] = perform(Op.MapFpToG1, input)

  /** Map field element to G2: input one Fp2 (128 bytes), output G2 (256 bytes). */
  def mapFp2ToG2(input: Array[Byte]): Either[String, Array[Byte]] = perform(Op.MapFp2ToG2, input)
