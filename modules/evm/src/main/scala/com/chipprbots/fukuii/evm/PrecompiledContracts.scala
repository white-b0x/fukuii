package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import scala.util.Try

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.ByteStringOps.*
import com.chipprbots.fukuii.bytes.ByteUtils
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.Blake2b
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.crypto.Secp256r1
import com.chipprbots.fukuii.crypto.bls.Bls12381
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.crypto.kzg.Kzg
import com.chipprbots.fukuii.crypto.ripemd160
import com.chipprbots.fukuii.crypto.sha256
import com.chipprbots.fukuii.crypto.zksnark.BN128.BN128G1
import com.chipprbots.fukuii.crypto.zksnark.BN128.BN128G2
import com.chipprbots.fukuii.crypto.zksnark.BN128.Point
import com.chipprbots.fukuii.crypto.zksnark.BN128Fp
import com.chipprbots.fukuii.crypto.zksnark.Fp
import com.chipprbots.fukuii.crypto.zksnark.PairingCheck
import com.chipprbots.fukuii.crypto.zksnark.PairingCheck.G1G2Pair

/** The fork-gated EVM precompiled-contract registry (P5, the AS-IS `vm/PrecompiledContracts.scala` analog).
  *
  * Every precompile here is a thin **gas + input-decode + dispatch shell** that calls **down into the L0 `crypto`
  * primitive** — `Secp256k1`/`Hashes`/`Blake2b`/`zksnark.BN128`/`kzg.Kzg`/`bls.Bls12381`/`Secp256r1`. It never
  * re-inlines a native call at the precompile site: that mislayering (AS-IS inlined the besu `LibEthPairings` and
  * `CKZG4844JNI` calls under `vm/`) is the B-BLS-1/B-KZG-1 fix L0 already made, and this layer honours it.
  *
  * ==Fork gating comes from the resolved config, never an enum-fork param==
  * The AS-IS trait carried `gas(input, etcFork, ethFork)` and each fork-selection re-read the enum-fork ladders. The
  * rebuild gates gas through the P3 proposal fold: a precompile that reprices per fork reads the neutral [[EvmConfig]]
  * intent getters (`eip1108Enabled`, `eip2565Enabled`, `eip7883Enabled`, `eip7823Enabled`) — the one fork-resolution
  * mechanism (P2/P3), no fork name in a wrapper body.
  *
  * ==Which precompiles are active is the fold's job, not this object's==
  * The per-fork *set* lives on [[EvmConfig.precompiles]], resolved by [[EvmConfig.deriveEvmConfigAt]] from the active
  * proposals (each precompile-introducing proposal contributes a `configDelta`). This object exposes the wrappers and
  * the cumulative building-block maps the fold and the named bundles compose; the interpreter reads
  * `context.evmConfig.precompiles`, never a method here.
  *
  * ==Fail-loud at entry==
  * Malformed input is rejected at the point of decode, never given a wrong-but-plausible answer (besu
  * `AbstractBLS12PrecompiledContract` shape): BLS/KZG/alt-bn128/BLAKE2F/ModExp return `None` (⇒
  * [[PreCompiledContractFail]]) on invalid input; P256VERIFY returns an empty output on an invalid signature or
  * wrong-length input, which is the EIP-7951 "invalid" signal (a success with empty output, matching go-ethereum
  * `return nil, nil` / besu `Bytes.EMPTY`), not a fail-loud violation.
  */
// scalastyle:off magic.number
object PrecompiledContracts:

  private def addr(n: Long): Address = Address(UInt256(n))

  val EcRecoverAddr: Address = addr(0x01)
  val Sha256Addr: Address = addr(0x02)
  val Ripemd160Addr: Address = addr(0x03)
  val IdentityAddr: Address = addr(0x04)
  val ModExpAddr: Address = addr(0x05)
  val Bn128AddAddr: Address = addr(0x06)
  val Bn128MulAddr: Address = addr(0x07)
  val Bn128PairingAddr: Address = addr(0x08)
  val Blake2fAddr: Address = addr(0x09)

  /** EIP-4844 KZG point-evaluation precompile (`0x0a`) — **ETH only** (ETC never adopts EIP-4844/blobs). */
  val KzgPointEvalAddr: Address = addr(0x0a)

  /** EIP-2537 BLS12-381 precompiles — the final spec is **seven** precompiles at `0x0b–0x11` (no `0x12`). */
  val BlsG1AddAddr: Address = addr(0x0b)
  val BlsG1MsmAddr: Address = addr(0x0c)
  val BlsG2AddAddr: Address = addr(0x0d)
  val BlsG2MsmAddr: Address = addr(0x0e)
  val BlsPairingAddr: Address = addr(0x0f)
  val BlsMapG1Addr: Address = addr(0x10)
  val BlsMapG2Addr: Address = addr(0x11)

  /** EIP-7951 P256VERIFY precompile (`0x0100`, the L2 precompile space). */
  val P256VerifyAddr: Address = addr(0x100)

  // -- building-block maps (composed by the fold's configDeltas and by the named bundles) -----------------------------

  /** Frontier precompiles (`0x01–0x04`) — present from genesis on both fork clocks; the fold's base set. */
  val FrontierPrecompiles: Map[Address, PrecompiledContract] = Map(
    EcRecoverAddr -> EllipticCurveRecovery,
    Sha256Addr -> Sha256,
    Ripemd160Addr -> Ripemd160,
    IdentityAddr -> Identity
  )

  /** EIP-198 ModExp (`0x05`) — Byzantium (ETH) / Atlantis (ETC). */
  val Eip198Precompiles: Map[Address, PrecompiledContract] = Map(ModExpAddr -> ModExp)

  /** EIP-196 alt-bn128 add/mul (`0x06`/`0x07`) — Byzantium / Atlantis. */
  val Eip196Precompiles: Map[Address, PrecompiledContract] =
    Map(Bn128AddAddr -> Bn128Add, Bn128MulAddr -> Bn128Mul)

  /** EIP-197 alt-bn128 pairing (`0x08`) — Byzantium / Atlantis. */
  val Eip197Precompiles: Map[Address, PrecompiledContract] = Map(Bn128PairingAddr -> Bn128Pairing)

  /** EIP-152 BLAKE2F (`0x09`) — Istanbul (ETH) / Phoenix (ETC). */
  val Eip152Precompiles: Map[Address, PrecompiledContract] = Map(Blake2fAddr -> Blake2bCompress)

  /** EIP-4844 KZG point evaluation (`0x0a`) — **ETH only** (Cancun onward). */
  val Eip4844Precompiles: Map[Address, PrecompiledContract] = Map(KzgPointEvalAddr -> KzgPointEvaluation)

  /** EIP-2537 BLS12-381 (`0x0b–0x11`) — ETH Prague / ETC Olympia. */
  val BlsPrecompiles: Map[Address, PrecompiledContract] = Map(
    BlsG1AddAddr -> BlsG1Add,
    BlsG1MsmAddr -> BlsG1Msm,
    BlsG2AddAddr -> BlsG2Add,
    BlsG2MsmAddr -> BlsG2Msm,
    BlsPairingAddr -> BlsPairing,
    BlsMapG1Addr -> BlsMapG1,
    BlsMapG2Addr -> BlsMapG2
  )

  /** EIP-7951 P256VERIFY (`0x0100`) — ETH Osaka / ETC Olympia (the dual-activation asymmetry vs `0x0a` KZG). */
  val P256Precompiles: Map[Address, PrecompiledContract] = Map(P256VerifyAddr -> P256Verify)

  // -- cumulative per-fork sets (the byte-identity oracle the fold must reproduce) -------------------------------------

  private val ByzantiumAtlantis: Map[Address, PrecompiledContract] =
    FrontierPrecompiles ++ Eip198Precompiles ++ Eip196Precompiles ++ Eip197Precompiles

  private val IstanbulPhoenix: Map[Address, PrecompiledContract] =
    ByzantiumAtlantis ++ Eip152Precompiles

  /** ETH Cancun set: `0x01–0x0a` (Istanbul/Phoenix base + the `0x0a` KZG point-evaluation precompile). */
  val EthCancunPrecompiles: Map[Address, PrecompiledContract] =
    IstanbulPhoenix ++ Eip4844Precompiles

  /** ETH Prague set: Cancun + BLS12-381 (`0x0b–0x11`). */
  val EthPraguePrecompiles: Map[Address, PrecompiledContract] =
    EthCancunPrecompiles ++ BlsPrecompiles

  /** ETH Osaka set: Prague + P256VERIFY (`0x0100`). */
  val EthOsakaPrecompiles: Map[Address, PrecompiledContract] =
    EthPraguePrecompiles ++ P256Precompiles

  /** ETC Olympia set (ECIP-1121, forge co-signed): Istanbul/Phoenix base + BLS12-381 (`0x0b–0x11`) + P256VERIFY
    * (`0x0100`). **Deliberately excludes the `0x0a` KZG precompile** — ETC never adopts EIP-4844/blobs, so `0x0a` is an
    * empty account on ETC. Routing ETC Olympia through ETH's Cancun/Osaka set (which carries `0x0a`) would fork the
    * chain on any CALL to `0x0a`.
    */
  val EtcOlympiaPrecompiles: Map[Address, PrecompiledContract] =
    IstanbulPhoenix ++ BlsPrecompiles ++ P256Precompiles

  /** A precompiled contract: a gas cost, an execution over the raw input, and the [[run]] shell that meters and
    * packages the result. Gas may depend on the resolved [[EvmConfig]] (fork-repriced precompiles read its intent
    * getters); most precompiles ignore it.
    */
  sealed trait PrecompiledContract:
    def exec(inputData: ByteString): Option[ByteString]
    def gas(inputData: ByteString, config: EvmConfig): BigInt

    /** Charge [[gas]], run [[exec]], and package a [[ProgramResult]]: out-of-gas if the cost exceeds `startGas`, a
      * [[PreCompiledContractFail]] if `exec` rejects the input, otherwise the returned data with the remaining gas.
      */
    def run[W <: WorldState[W, S], S <: AccountStorage[S]](context: ProgramContext[W, S]): ProgramResult[W, S] =
      val g = gas(context.inputData, context.evmConfig)
      val (result, error, gasRemaining): (ByteString, Option[HaltReason], BigInt) =
        if g <= context.startGas then
          exec(context.inputData) match
            case Some(returnData) => (returnData, None, context.startGas - g)
            case None             => (ByteString.empty, Some(PreCompiledContractFail), BigInt(0))
        else (ByteString.empty, Some(OutOfGas), BigInt(0))

      ProgramResult(
        result,
        gasRemaining,
        context.world,
        Set.empty,
        Nil,
        Nil,
        BigInt(0),
        error,
        Set.empty,
        Set.empty
      )

  // Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-2.md (ecrecover, address 0x01)
  object EllipticCurveRecovery extends PrecompiledContract:
    def exec(inputData: ByteString): Option[ByteString] =
      val data = inputData.padRight(128, 0.toByte)
      val h = data.slice(0, 32)
      val v = data.slice(32, 64)
      val r = data.slice(64, 96)
      val s = data.slice(96, 128)

      if hasOnlyLastByteSet(v) then
        val recovered = Try(ECDSASignature(r, s, v.last).publicKey(h)).getOrElse(None)
        Some(
          recovered
            .map(pub => ByteUtils.padLeft(kec256(pub).slice(12, 32), 32))
            .getOrElse(ByteString.empty)
        )
      else Some(ByteString.empty)

    def gas(inputData: ByteString, config: EvmConfig): BigInt = BigInt(3000)

    private def hasOnlyLastByteSet(v: ByteString): Boolean =
      v.dropWhile(_ == 0).size == 1

  object Sha256 extends PrecompiledContract:
    def exec(inputData: ByteString): Option[ByteString] =
      Some(sha256(inputData))

    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      BigInt(60) + BigInt(12) * wordsForBytes(inputData.size)

  object Ripemd160 extends PrecompiledContract:
    def exec(inputData: ByteString): Option[ByteString] =
      Some(ByteUtils.padLeft(ripemd160(inputData), 32))

    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      BigInt(600) + BigInt(120) * wordsForBytes(inputData.size)

  object Identity extends PrecompiledContract:
    def exec(inputData: ByteString): Option[ByteString] =
      Some(inputData)

    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      BigInt(15) + BigInt(3) * wordsForBytes(inputData.size)

  /** ModExp (`0x05`) — EIP-198 gas, repriced by EIP-2565 (Berlin/Magneto), then EIP-7883 (Osaka/Olympia). EIP-7823
    * (Osaka/Olympia) additionally rejects any operand length above 1024 bytes at entry. All three gates read the
    * resolved [[EvmConfig]] intent getters, never a fork name.
    *
    * Spec: https://eips.ethereum.org/EIPS/eip-198, eip-2565, eip-7823, eip-7883
    */
  object ModExp extends PrecompiledContract:

    private val lengthBytes = 32
    private val totalLengthBytes = 3 * lengthBytes

    /** EIP-7823: maximum operand length in bytes (1024 = 8192 bits). */
    private val maxOperandLength = 1024

    override def run[W <: WorldState[W, S], S <: AccountStorage[S]](
        context: ProgramContext[W, S]
    ): ProgramResult[W, S] =
      val input = context.inputData
      val boundsViolated =
        context.evmConfig.eip7823Enabled && {
          val baseLength = getLength(input, 0)
          val expLength = getLength(input, 1)
          val modLength = getLength(input, 2)
          baseLength > maxOperandLength || expLength > maxOperandLength || modLength > maxOperandLength
        }

      if boundsViolated then
        ProgramResult[W, S](
          ByteString.empty,
          BigInt(0),
          context.world,
          Set.empty,
          Nil,
          Nil,
          BigInt(0),
          Some(PreCompiledContractFail),
          Set.empty,
          Set.empty
        )
      else super.run(context)

    def exec(inputData: ByteString): Option[ByteString] =
      val baseLength = getLength(inputData, 0)
      val expLength = getLength(inputData, 1)
      val modLength = getLength(inputData, 2)

      val result =
        if baseLength == 0 && modLength == 0 then BigInt(0)
        else
          val mod = getNumber(inputData, safeAdd(totalLengthBytes, safeAdd(baseLength, expLength)), modLength)
          if mod == 0 then BigInt(0)
          else
            val base = getNumber(inputData, totalLengthBytes, baseLength)
            val exp = getNumber(inputData, safeAdd(totalLengthBytes, baseLength), expLength)
            base.modPow(exp, mod)
      Some(ByteString(ByteUtils.bigIntToBytes(result, modLength)))

    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      val baseLength = getLength(inputData, 0)
      val expLength = getLength(inputData, 1)
      val modLength = getLength(inputData, 2)

      val expBytes =
        inputData.slice(
          safeAdd(totalLengthBytes, baseLength),
          safeAdd(safeAdd(totalLengthBytes, baseLength), expLength)
        )

      if config.eip7883Enabled then PostEIP7883Cost.calculate(baseLength, modLength, expLength, expBytes)
      else if config.eip2565Enabled then PostEIP2565Cost.calculate(baseLength, modLength, expLength, expBytes)
      else PostEIP198Cost.calculate(baseLength, modLength, expLength, expBytes)

    // Spec: https://eips.ethereum.org/EIPS/eip-198
    object PostEIP198Cost:
      private val GQUADDIVISOR = 20

      def calculate(baseLength: Int, modLength: Int, expLength: Int, expBytes: ByteString): BigInt =
        val multComplexity = getMultComplexity(math.max(baseLength, modLength))
        val adjusted = adjustExpLength(expBytes, expLength)
        multComplexity * math.max(adjusted, 1) / GQUADDIVISOR

      private def getMultComplexity(x: BigInt): BigInt =
        val x2 = x * x
        if x <= 64 then x2
        else if x <= 1024 then x2 / 4 + 96 * x - 3072
        else x2 / 16 + 480 * x - 199680

    // Spec: https://eips.ethereum.org/EIPS/eip-2565
    object PostEIP2565Cost:
      private val GQUADDIVISOR = 3

      def calculate(baseLength: Int, modLength: Int, expLength: Int, expBytes: ByteString): BigInt =
        val multComplexity = getMultComplexity(math.max(baseLength, modLength))
        val adjusted = adjustExpLength(expBytes, expLength)
        val r = multComplexity * math.max(adjusted, 1) / GQUADDIVISOR
        if r <= 200 then 200 else r

      // ceiling(x/8)^2
      private def getMultComplexity(x: BigInt): BigInt =
        ((x + 7) / 8).pow(2)

    // Spec: https://eips.ethereum.org/EIPS/eip-7883
    // EIP-7883: ModExp gas increase — higher multiplication complexity, no divisor, min 500.
    object PostEIP7883Cost:

      def calculate(baseLength: Int, modLength: Int, expLength: Int, expBytes: ByteString): BigInt =
        val multComplexity = getMultComplexity(math.max(baseLength, modLength))
        val adjusted = adjustExpLength7883(expBytes, expLength)
        val r = multComplexity * math.max(adjusted, 1)
        if r < 500 then 500 else r

      // For maxLen <= 32: 16 (flat); for maxLen > 32: 2 * ceiling(maxLen/8)^2.
      private def getMultComplexity(x: BigInt): BigInt =
        if x <= 32 then BigInt(16)
        else
          val words = (x + 7) / 8
          2 * words.pow(2)

      // EIP-7883 adjusted exponent length uses multiplier 16 (not 8 like EIP-2565).
      private def adjustExpLength7883(expBytes: ByteString, expLength: Int): Long =
        val expHead =
          if expLength <= lengthBytes then expBytes.padRight(expLength, 0.toByte)
          else expBytes.take(lengthBytes).padRight(lengthBytes, 0.toByte)

        val highestBitIndex = math.max(ByteUtils.toBigInt(expHead).bitLength - 1, 0)

        if expLength <= lengthBytes then highestBitIndex
        else 16L * (expLength - lengthBytes) + highestBitIndex

    private def getNumber(bytes: ByteString, offset: Int, length: Int): BigInt =
      val number = bytes.slice(offset, safeAdd(offset, length)).padRight(length, 0.toByte)
      ByteUtils.toBigInt(number)

    private def safeAdd(a: Int, b: Int): Int =
      safeInt(BigInt(a) + BigInt(b))

    private def safeInt(value: BigInt): Int =
      if value.isValidInt then value.toInt else Integer.MAX_VALUE

    private def getLength(bytes: ByteString, position: Int): Int =
      val start = position * lengthBytes
      safeInt(ByteUtils.toBigInt(bytes.slice(start, start + lengthBytes)))

    private def adjustExpLength(expBytes: ByteString, expLength: Int): Long =
      val expHead =
        if expLength <= lengthBytes then expBytes.padRight(expLength, 0.toByte)
        else expBytes.take(lengthBytes).padRight(lengthBytes, 0.toByte)

      val highestBitIndex = math.max(ByteUtils.toBigInt(expHead).bitLength - 1, 0)

      if expLength <= lengthBytes then highestBitIndex
      else 8L * (expLength - lengthBytes) + highestBitIndex

  /** alt-bn128 ECADD (`0x06`) → L0 [[BN128Fp]]. EIP-1108 (Istanbul/Phoenix) reprices; gated by `eip1108Enabled`. Spec:
    * https://github.com/ethereum/EIPs/blob/master/EIPS/eip-196.md
    */
  object Bn128Add extends PrecompiledContract:
    private val expectedBytes = 4 * 32

    def exec(inputData: ByteString): Option[ByteString] =
      val paddedInput = inputData.padRight(expectedBytes, 0.toByte)
      val (x1, y1, x2, y2) = getCurvePointsBytes(paddedInput)

      val result =
        for
          p1 <- BN128Fp.createPoint(x1, y1)
          p2 <- BN128Fp.createPoint(x2, y2)
          p3 = BN128Fp.toEthNotation(BN128Fp.add(p1, p2))
        yield p3

      result.map(fpPointToBytes)

    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      if config.eip1108Enabled then BigInt(150) else BigInt(500)

    private def getCurvePointsBytes(input: ByteString): (ByteString, ByteString, ByteString, ByteString) =
      (input.slice(0, 32), input.slice(32, 64), input.slice(64, 96), input.slice(96, 128))

  /** alt-bn128 ECMUL (`0x07`) → L0 [[BN128Fp]]. Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-196.md */
  object Bn128Mul extends PrecompiledContract:
    private val expectedBytes = 3 * 32
    private val maxScalar: BigInt = BigInt(2).pow(256) - 1

    def exec(inputData: ByteString): Option[ByteString] =
      val paddedInput = inputData.padRight(expectedBytes, 0.toByte)
      val (x1, y1, scalarBytes) = getCurvePointsBytes(paddedInput)
      val scalar = ByteUtils.toBigInt(scalarBytes)

      val result =
        for
          p <- BN128Fp.createPoint(x1, y1)
          s <- if scalar <= maxScalar then Some(scalar) else None
          p3 = BN128Fp.toEthNotation(BN128Fp.mul(p, s))
        yield p3

      result.map(fpPointToBytes)

    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      if config.eip1108Enabled then BigInt(6000) else BigInt(40000)

    private def getCurvePointsBytes(input: ByteString): (ByteString, ByteString, ByteString) =
      (input.slice(0, 32), input.slice(32, 64), input.slice(64, 96))

  /** alt-bn128 ECPAIRING (`0x08`) → L0 [[PairingCheck]]. The G2 points are decoded through [[BN128G2]], which performs
    * the mandatory order-`r` subgroup check (`[r]·P = ∞`, the F-BN-1 chain-split guard) — an on-curve-but-off-subgroup
    * input is rejected, not silently paired. Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-197.md
    */
  object Bn128Pairing extends PrecompiledContract:
    private val wordLength = 32
    private val inputLength = 6 * wordLength

    private val positiveResult: ByteString = ByteUtils.padLeft(ByteString(1.toByte), wordLength)
    private val negativeResult: ByteString = ByteString(Array.fill[Byte](wordLength)(0.toByte))

    def exec(inputData: ByteString): Option[ByteString] =
      if inputData.length % inputLength != 0 then None
      else
        getPairs(inputData.grouped(inputLength)).map { pairs =>
          if PairingCheck.pairingCheck(pairs) then positiveResult else negativeResult
        }

    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      val k = inputData.length / inputLength
      if config.eip1108Enabled then BigInt(34000) * k + BigInt(45000)
      else BigInt(80000) * k + BigInt(100000)

    /** Fold the 6-word chunks into pairs; a single invalid coordinate/off-subgroup point collapses the whole set to
      * `None` (fail-loud), matching the AS-IS short-circuit — the product is commutative, so accumulation order is
      * irrelevant.
      */
    private def getPairs(chunks: Iterator[ByteString]): Option[Seq[G1G2Pair]] =
      chunks.foldLeft(Option(List.empty[G1G2Pair])) { (acc, chunk) =>
        acc.flatMap(list => getPair(chunk).map(_ :: list))
      }

    private def getPair(input: ByteString): Option[G1G2Pair] =
      for
        g1 <- BN128G1(getBytesOnPosition(input, 0), getBytesOnPosition(input, 1))
        g2 <- BN128G2(
          getBytesOnPosition(input, 3),
          getBytesOnPosition(input, 2),
          getBytesOnPosition(input, 5),
          getBytesOnPosition(input, 4)
        )
      yield G1G2Pair(g1, g2)

    private def getBytesOnPosition(input: ByteString, pos: Int): ByteString =
      val from = pos * wordLength
      input.slice(from, from + wordLength)

  /** BLAKE2F (`0x09`) → L0 [[Blake2b.compress]]. The 213-byte framing / rounds parse / per-round gas live here; a
    * malformed input (wrong length or a final-block flag other than `0x00`/`0x01`) fails loud (`compress` returns
    * `None`, gas is 0). Spec: https://eips.ethereum.org/EIPS/eip-152
    */
  object Blake2bCompress extends PrecompiledContract:
    def exec(inputData: ByteString): Option[ByteString] =
      Blake2b.compress(inputData.toArray).map(ByteString.fromArrayUnsafe)

    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      val inputArray = inputData.toArray
      if Blake2b.isValidInput(inputArray) then BigInt(Blake2b.parseNumberOfRounds(inputArray))
      else BigInt(0)

  /** P256VERIFY (`0x0100`, EIP-7951) → L0 [[Secp256r1.verify]]. Fixed 6900 gas. Input MUST be exactly 160 bytes (`hash
    * ‖ r ‖ s ‖ qx ‖ qy`); any other length, or a well-formed-but-invalid signature, returns an **empty** output (the
    * EIP-7951 "invalid" signal — a success with empty output, matching go-ethereum `return nil, nil` and besu
    * `Bytes.EMPTY`), while a valid signature returns `0x01` left-padded to 32 bytes. Spec:
    * https://eips.ethereum.org/EIPS/eip-7951
    */
  object P256Verify extends PrecompiledContract:
    private val expectedInputLength = 160

    def exec(inputData: ByteString): Option[ByteString] =
      if inputData.length != expectedInputLength then Some(ByteString.empty)
      else
        val hash = inputData.slice(0, 32).toArray
        val r = inputData.slice(32, 64).toArray
        val s = inputData.slice(64, 96).toArray
        val x = inputData.slice(96, 128).toArray
        val y = inputData.slice(128, 160).toArray

        if Secp256r1.verify(hash, r, s, x, y) then Some(ByteUtils.padLeft(ByteString(1.toByte), 32))
        else Some(ByteString.empty)

    def gas(inputData: ByteString, config: EvmConfig): BigInt = BigInt(6900)

  /** EIP-4844 KZG point evaluation (`0x0a`) → L0 [[Kzg.verifyKzgProof]], which pins the mainnet ceremony trusted setup.
    * Input: `versioned_hash(32) ‖ z(32) ‖ y(32) ‖ commitment(48) ‖ proof(48)` = 192 bytes. Output:
    * `FIELD_ELEMENTS_PER_BLOB(32) ‖ BLS_MODULUS(32)` = 64 bytes. Fixed 50000 gas. **ETH only** — excluded from the ETC
    * Olympia set. Spec: https://eips.ethereum.org/EIPS/eip-4844
    */
  object KzgPointEvaluation extends PrecompiledContract:
    private val KzgGas = BigInt(50000)
    private val VersionedHashVersionKzg: Byte = 0x01
    private val BlsModulus =
      BigInt("52435875175126190479447740508185965837690552500527637822603658699938581184513")
    private val FieldElementsPerBlob = BigInt(4096)

    def gas(inputData: ByteString, config: EvmConfig): BigInt = KzgGas

    def exec(inputData: ByteString): Option[ByteString] =
      if inputData.length != 192 then None
      else
        val versionedHash = inputData.slice(0, 32)
        val z = inputData.slice(32, 64)
        val y = inputData.slice(64, 96)
        val commitment = inputData.slice(96, 144)
        val proof = inputData.slice(144, 192)

        val commitmentHash =
          val h = sha256(commitment.toArray)
          h(0) = VersionedHashVersionKzg
          ByteString(h)

        val valid =
          versionedHash(0) == VersionedHashVersionKzg &&
            ByteUtils.toBigInt(z) < BlsModulus &&
            ByteUtils.toBigInt(y) < BlsModulus &&
            commitmentHash == versionedHash &&
            Try(Kzg.verifyKzgProof(commitment.toArray, z.toArray, y.toArray, proof.toArray)).getOrElse(false)

        if valid then
          Some(
            ByteString(ByteUtils.bigIntToBytes(FieldElementsPerBlob, 32)) ++
              ByteString(ByteUtils.bigIntToBytes(BlsModulus, 32))
          )
        else None

  // ===== EIP-2537: BLS12-381 precompiles (0x0b–0x11) → L0 crypto.bls.Bls12381 =====
  // Each wrapper is a gas + dispatch shell over the L0 primitive (the byte-exact eth_pairings backend). The native
  // library is guarded by `Bls12381.isAvailable`; if absent, the precompile fails closed (None ⇒ PreCompiledContractFail)
  // rather than calling into an unloaded library.

  sealed trait BlsPrecompile extends PrecompiledContract:
    protected def perform(input: Array[Byte]): Either[String, Array[Byte]]

    def exec(inputData: ByteString): Option[ByteString] =
      if Bls12381.isAvailable then perform(inputData.toArray).toOption.map(ByteString.fromArrayUnsafe)
      else None

  object BlsG1Add extends BlsPrecompile:
    protected def perform(input: Array[Byte]): Either[String, Array[Byte]] = Bls12381.g1Add(input)
    def gas(inputData: ByteString, config: EvmConfig): BigInt = BigInt(375)

  object BlsG1Msm extends BlsPrecompile:
    private val pairSize = 160 // 128-byte G1 point + 32-byte scalar
    protected def perform(input: Array[Byte]): Either[String, Array[Byte]] = Bls12381.g1Msm(input)
    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      val k = math.max(1, inputData.length / pairSize)
      BigInt(12000) * k * blsG1MsmDiscount(k) / 1000

  object BlsG2Add extends BlsPrecompile:
    protected def perform(input: Array[Byte]): Either[String, Array[Byte]] = Bls12381.g2Add(input)
    def gas(inputData: ByteString, config: EvmConfig): BigInt = BigInt(600)

  object BlsG2Msm extends BlsPrecompile:
    private val pairSize = 288 // 256-byte G2 point + 32-byte scalar
    protected def perform(input: Array[Byte]): Either[String, Array[Byte]] = Bls12381.g2Msm(input)
    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      val k = math.max(1, inputData.length / pairSize)
      BigInt(22500) * k * blsG2MsmDiscount(k) / 1000

  object BlsPairing extends BlsPrecompile:
    private val pairSize = 384 // 128-byte G1 + 256-byte G2
    protected def perform(input: Array[Byte]): Either[String, Array[Byte]] = Bls12381.pairing(input)
    def gas(inputData: ByteString, config: EvmConfig): BigInt =
      val k = math.max(1, inputData.length / pairSize)
      BigInt(32600) * k + BigInt(37700)

  object BlsMapG1 extends BlsPrecompile:
    protected def perform(input: Array[Byte]): Either[String, Array[Byte]] = Bls12381.mapFpToG1(input)
    def gas(inputData: ByteString, config: EvmConfig): BigInt = BigInt(5500)

  object BlsMapG2 extends BlsPrecompile:
    protected def perform(input: Array[Byte]): Either[String, Array[Byte]] = Bls12381.mapFp2ToG2(input)
    def gas(inputData: ByteString, config: EvmConfig): BigInt = BigInt(23800)

  private def fpPointToBytes(point: Point[Fp]): ByteString =
    ByteString(ByteUtils.bigIntToBytes(point.x.inner, 32) ++ ByteUtils.bigIntToBytes(point.y.inner, 32))

  /** EIP-2537 G1 MSM discount table (128 entries). max_discount = 519 at k >= 128. */
  private def blsG1MsmDiscount(k: Int): Int =
    val table = Array(
      1000, 949, 848, 797, 764, 750, 738, 728, 719, 712, 705, 698, 692, 687, 682, 677, 673, 669, 665, 661, 658, 654,
      651, 648, 645, 642, 640, 637, 635, 632, 630, 627, 625, 623, 621, 619, 617, 615, 613, 611, 609, 608, 606, 604, 603,
      601, 599, 598, 596, 595, 593, 592, 591, 589, 588, 586, 585, 584, 582, 581, 580, 579, 577, 576, 575, 574, 573, 572,
      570, 569, 568, 567, 566, 565, 564, 563, 562, 561, 560, 559, 558, 557, 556, 555, 554, 553, 552, 551, 550, 549, 548,
      547, 547, 546, 545, 544, 543, 542, 541, 540, 540, 539, 538, 537, 536, 536, 535, 534, 533, 532, 532, 531, 530, 529,
      528, 528, 527, 526, 525, 525, 524, 523, 522, 522, 521, 520, 520, 519
    )
    if k <= 0 then 1000
    else if k <= table.length then table(k - 1)
    else 519

  /** EIP-2537 G2 MSM discount table (128 entries). max_discount = 524 at k >= 128. */
  private def blsG2MsmDiscount(k: Int): Int =
    val table = Array(
      1000, 1000, 923, 884, 855, 832, 812, 796, 782, 770, 759, 749, 740, 732, 724, 717, 711, 704, 699, 693, 688, 683,
      679, 674, 670, 666, 663, 659, 655, 652, 649, 646, 643, 640, 637, 634, 632, 629, 627, 624, 622, 620, 618, 615, 613,
      611, 609, 607, 606, 604, 602, 600, 598, 597, 595, 593, 592, 590, 589, 587, 586, 584, 583, 582, 580, 579, 578, 576,
      575, 574, 573, 571, 570, 569, 568, 567, 566, 565, 563, 562, 561, 560, 559, 558, 557, 556, 555, 554, 553, 552, 552,
      551, 550, 549, 548, 547, 546, 545, 545, 544, 543, 542, 541, 541, 540, 539, 538, 537, 537, 536, 535, 535, 534, 533,
      532, 532, 531, 530, 530, 529, 528, 528, 527, 526, 526, 525, 524, 524
    )
    if k <= 0 then 1000
    else if k <= table.length then table(k - 1)
    else 524
