package com.chipprbots.fukuii.evm

import scala.annotation.unused

import com.chipprbots.fukuii.bytes.UInt256

/** The per-fork gas strategy — **one injected object holding both the fee *values* and the gas *computation***.
  *
  * This is the layer's headline structural target (L3 plan §2 v2 / §5 / §6, RX-L3-05): it retires the AS-IS split where
  * fee *values* lived in the `FeeSchedule` inheritance chain while the gas *computation* (`baseGas`/`varGas`,
  * `calcMemCost`, `gasCap`, the EIP-2929 warm/cold access cost) lived on the opcode / `EvmConfig`. besu's
  * `GasCalculator` is the JVM model (`gascalculator/GasCalculator.java`): the whole schedule is one interface — the
  * constant tier costs **and** the dynamic computation methods — and a fork diff is literally the overridden methods.
  * `EtcOlympiaGasCalculator` / `EthOsakaGasCalculator` are **siblings** off a shared base overriding only what changed;
  * no shared mega-switch, no fork name in an opcode body.
  *
  * **EIP-2929 warm/cold access cost lands here (T3 / RX-L3-09), not on the retired enum-fork read-path.** The base
  * calculator's [[accountAccessCost]]/[[storageAccessCost]] are the pre-2929 pass-through (the opcode's own base tier);
  * [[Eip2929GasCalculator]] onward override them to the warm/cold split (besu's
  * `getColdAccountAccessCost`/`getWarmStorageReadCost`). The override *is* the enablement — there is no separate
  * `eip2929Enabled` fork lookup.
  *
  * **Gas is JVM `BigInt`** (T4 / RX-L3-06): besu itself uses `long` + clamped/saturating arithmetic
  * (`Words.clampedAdd`, `OsakaGasCalculator.java:19-21`), **not** `BigInteger`; fukuii's `BigInt` is the conscious
  * correctness-over-throughput choice (no overflow possible), a perf candidate under the immutable-loop bench, **not**
  * a besu mirror.
  *
  * **R2:** every calculator is a stateless, immutable singleton, freely shareable across `ChainInstance`s. The fields
  * are `def`s (not `var`s); no process-global mutable EVM state.
  *
  * The fee `def`s are abstract on the trait ([[FrontierGasCalculator]] concretizes them, matching the AS-IS `trait
  * FeeSchedule` + `FrontierFeeSchedule`); the computation methods are concrete and reference the fee `def`s, so they
  * resolve against whichever fork instance is injected.
  */
trait GasCalculator:

  // -- Tier / operation fee VALUES (the AS-IS FeeSchedule fields, transcribed byte-for-byte) --
  def G_zero: BigInt
  def G_base: BigInt
  def G_verylow: BigInt
  def G_low: BigInt
  def G_mid: BigInt
  def G_high: BigInt
  def G_balance: BigInt
  def G_sload: BigInt
  def G_jumpdest: BigInt
  def G_sset: BigInt
  def G_sreset: BigInt
  def R_sclear: BigInt
  def R_selfdestruct: BigInt
  def G_selfdestruct: BigInt
  def G_create: BigInt
  def G_codedeposit: BigInt
  def G_call: BigInt
  def G_callvalue: BigInt
  def G_callstipend: BigInt
  def G_newaccount: BigInt
  def G_exp: BigInt
  def G_expbyte: BigInt
  def G_memory: BigInt
  def G_txcreate: BigInt
  def G_txdatazero: BigInt
  def G_txdatanonzero: BigInt
  def G_transaction: BigInt
  def G_log: BigInt
  def G_logdata: BigInt
  def G_logtopic: BigInt
  def G_sha3: BigInt
  def G_sha3word: BigInt
  def G_copy: BigInt
  def G_blockhash: BigInt
  def G_extcode: BigInt
  def G_cold_sload: BigInt
  def G_cold_account_access: BigInt
  def G_warm_storage_read: BigInt
  def G_access_list_address: BigInt
  def G_access_list_storage: BigInt
  def G_initcode_word: BigInt

  // -- EIP-2929 warm/cold access cost (in-calculator, T3 / RX-L3-09) --

  /** The cost of touching `address` given whether it is already warm. Pre-2929 (Frontier→[[Eip1884GasCalculator]]) this
    * is the opcode's own base tier (`preGas`, unchanged by warmth); [[Eip2929GasCalculator]] onward overrides it to the
    * EIP-2929 warm/cold split. besu `getColdAccountAccessCost`/`getWarmStorageReadCost`.
    */
  def accountAccessCost(preGas: BigInt, @unused isWarm: Boolean): BigInt = preGas

  /** The cost of touching storage slot `(address, key)` given whether it is already warm. Pre-2929 the opcode base
    * tier; [[Eip2929GasCalculator]]+ the EIP-2929 cold-sload / warm-read split.
    */
  def storageAccessCost(preGas: BigInt, @unused isWarm: Boolean): BigInt = preGas

  // -- Gas-cap divisor (EIP-150 "all but one 64th", YP eq. 224) --

  /** `None` pre-EIP-150 (Frontier/Homestead — the full remaining gas is forwarded to a sub-call); `Some(64)` from
    * [[Eip150GasCalculator]] onward (all-but-one-64th retained). Owned here rather than on `EvmConfig` because it is a
    * per-fork gas rule.
    */
  def subGasCapDivisor: Option[Long] = None

  /** Gas forwarded to a `CALL`/`CREATE` sub-execution — YP eq. (224). */
  def gasCap(g: BigInt): BigInt =
    subGasCapDivisor.map(d => g - g / d).getOrElse(g)

  // -- Memory expansion cost (YP H.1) --

  /** Gas cost of expanding memory to hold `dataSize` bytes at `offset`, given the current `memSize`. Incurs a blocking
    * (unpayable) cost past [[GasCalculator.MaxMemory]]. Transcribed from the AS-IS `EvmConfig.calcMemCost`.
    */
  def calcMemCost(memSize: BigInt, offset: BigInt, dataSize: BigInt): BigInt =
    def c(m: BigInt): BigInt =
      val a = wordsForBytes(m)
      G_memory * a + a * a / 512

    val memNeeded = if dataSize == 0 then BigInt(0) else offset + dataSize
    if memNeeded > GasCalculator.MaxMemory then GasCalculator.MemoryCostBlocker
    else if memNeeded <= memSize then BigInt(0)
    else c(memNeeded) - c(memSize)

  /** EIP-3860 initcode word-metering cost — `G_initcode_word * ceil(len / 32)`. Zero pre-EIP-3860 (`G_initcode_word ==
    * 0`).
    */
  def calcInitCodeCost(codeSize: BigInt): BigInt =
    G_initcode_word * wordsForBytes(codeSize)

object GasCalculator:

  /** Artificial memory ceiling (AS-IS `EvmConfig.MaxMemory = UInt256(Int.MaxValue)`) past which expansion is priced
    * unpayable, capping memory use via gas.
    */
  val MaxMemory: BigInt = BigInt(Int.MaxValue)

  /** The blocking (effectively unpayable) memory cost returned past [[MaxMemory]] — byte-identical to the AS-IS
    * `UInt256.MaxValue / 2` (= 2^255 - 1).
    */
  private[evm] val MemoryCostBlocker: BigInt = UInt256.MaxValue.toBigInt / 2

  // Singleton instances — the P2 direct per-fork construction (the fold that selects the active calculator at
  // (header, schedule) is P3). Ordered along the shared → ETC-lineage / ETH-lineage split. Shared bases are keyed by
  // their defining gas EIP; only the family-local leaves carry a network fork name (nomenclature.md).
  val Frontier: GasCalculator = new FrontierGasCalculator
  val Homestead: GasCalculator = new HomesteadGasCalculator
  val Eip150: GasCalculator = new Eip150GasCalculator
  val Eip160: GasCalculator = new Eip160GasCalculator
  val Eip1884: GasCalculator = new Eip1884GasCalculator
  val Eip2929: GasCalculator = new Eip2929GasCalculator
  val Eip3529: GasCalculator = new Eip3529GasCalculator
  val Eip3860: GasCalculator = new Eip3860GasCalculator

  /** ETC-only. */
  val EtcOlympia: GasCalculator = new EtcOlympiaGasCalculator

  /** ETH-only. */
  val EthLondon: GasCalculator = new EthLondonGasCalculator
  val EthCancun: GasCalculator = new EthCancunGasCalculator
  val EthPrague: GasCalculator = new EthPragueGasCalculator
  val EthOsaka: GasCalculator = new EthOsakaGasCalculator

// ---------------------------------------------------------------------------------------------------------------------
// The shared (network-neutral) lineage, keyed by defining gas EIP: Frontier → Homestead → EIP-150 → EIP-160 →
// EIP-1884 → EIP-2929 → EIP-3529. EIP-keyed rather than fork-named because a shared base must carry no network fork
// codename (nomenclature.md): the ETH and ETC families share these gas values byte-for-byte, the network divergence
// being only the activation height. The two opcode-only forks between EIP-160 and EIP-1884 activate no gas-fee EIP
// (no gas-schedule delta), so they have no defining gas EIP to key on and are omitted from the gas spine (their opcode
// layers live on the separate P2 opcode spine).
//
// Class inheritance (not `export`) is used deliberately: Scala 3 `export` creates forwarder members that do NOT
// implement a parent trait's abstract members, so a fork diff must `override` inherited concrete `def`s.
// ---------------------------------------------------------------------------------------------------------------------

/** Frontier — the concrete base carrying every fee value (AS-IS `FrontierFeeSchedule`). */
class FrontierGasCalculator extends GasCalculator:
  def G_zero: BigInt = 0
  def G_base: BigInt = 2
  def G_verylow: BigInt = 3
  def G_low: BigInt = 5
  def G_mid: BigInt = 8
  def G_high: BigInt = 10
  def G_balance: BigInt = 20
  def G_sload: BigInt = 50
  def G_jumpdest: BigInt = 1
  def G_sset: BigInt = 20000
  def G_sreset: BigInt = 5000
  def R_sclear: BigInt = 15000
  def R_selfdestruct: BigInt = 24000
  def G_selfdestruct: BigInt = 0
  def G_create: BigInt = 32000
  def G_codedeposit: BigInt = 200
  def G_call: BigInt = 40
  def G_callvalue: BigInt = 9000
  def G_callstipend: BigInt = 2300
  def G_newaccount: BigInt = 25000
  def G_exp: BigInt = 10
  def G_expbyte: BigInt = 10
  def G_memory: BigInt = 3
  def G_txcreate: BigInt = 0
  def G_txdatazero: BigInt = 4
  def G_txdatanonzero: BigInt = 68
  def G_transaction: BigInt = 21000
  def G_log: BigInt = 375
  def G_logdata: BigInt = 8
  def G_logtopic: BigInt = 375
  def G_sha3: BigInt = 30
  def G_sha3word: BigInt = 6
  def G_copy: BigInt = 3
  def G_blockhash: BigInt = 20
  def G_extcode: BigInt = 20
  // EIP-2929 cold/warm and access-list costs do not apply until EIP-2929 — the values exist but the
  // access-cost methods pass through the pre-2929 base until Eip2929GasCalculator overrides them.
  def G_cold_sload: BigInt = 2100
  def G_cold_account_access: BigInt = 2600
  def G_warm_storage_read: BigInt = 100
  def G_access_list_address: BigInt = 2400
  def G_access_list_storage: BigInt = 1900
  // EIP-3860 initcode metering does not exist until EIP-3860.
  def G_initcode_word: BigInt = 0

/** Homestead — EIP-2/7; the only gas-schedule delta is the `G_txcreate` intrinsic. */
class HomesteadGasCalculator extends FrontierGasCalculator:
  override def G_txcreate: BigInt = 32000

/** EIP-150 — repricing of state-access opcodes + the all-but-one-64th gas cap. */
class Eip150GasCalculator extends HomesteadGasCalculator:
  override def G_sload: BigInt = 200
  override def G_call: BigInt = 700
  override def G_balance: BigInt = 400
  override def G_selfdestruct: BigInt = 5000
  override def G_extcode: BigInt = 700
  override def subGasCapDivisor: Option[Long] = Some(64)

/** EIP-160 — EXP byte cost increase. */
class Eip160GasCalculator extends Eip150GasCalculator:
  override def G_expbyte: BigInt = 50

/** EIP-1884 — repriced SLOAD/BALANCE (EIP-1884) + cheaper calldata (EIP-2028); EIP-2200 SSTORE net metering lands in
  * the same generation but is a metering algorithm, not a tier field. Extends [[Eip160GasCalculator]] directly: no
  * gas-fee EIP activates between EIP-160 and here (the intervening opcode-only forks carry no gas-schedule delta).
  */
class Eip1884GasCalculator extends Eip160GasCalculator:
  override def G_sload: BigInt = 800
  override def G_balance: BigInt = 700
  override def G_txdatanonzero: BigInt = 16

/** EIP-2929 — warm/cold state access (EIP-2929) + access lists (EIP-2930). The warm/cold access cost lands here (T3 /
  * RX-L3-09): the base pass-through is replaced by the cold/warm split, so no separate fork lookup gates it.
  */
class Eip2929GasCalculator extends Eip1884GasCalculator:
  override def G_sload: BigInt = G_warm_storage_read
  // EIP-2929: SSTORE_RESET_GAS = 5000 - COLD_SLOAD_COST (cold access charged separately in SSTORE).
  override def G_sreset: BigInt = 5000 - G_cold_sload
  override def G_sset: BigInt = 20000

  override def accountAccessCost(preGas: BigInt, isWarm: Boolean): BigInt =
    if isWarm then G_warm_storage_read else G_cold_account_access

  override def storageAccessCost(preGas: BigInt, isWarm: Boolean): BigInt =
    if isWarm then G_warm_storage_read else G_cold_sload

/** EIP-3529 — reduced refunds. Shared ETC/ETH base for the reduced-refund era (Berlin→London on ETH, Magneto→Mystique
  * on ETC). **Carries NO initcode metering**: EIP-3860 activates one fork later than EIP-3529 on BOTH clocks (ETH
  * Shanghai vs London — go-ethereum `newShanghaiInstructionSet` enable3860, core/vm/jump_table.go:136-142; ETC Spiral
  * 19_250_000 vs Mystique 14_525_000 — core-geth `EIP3860FBlock` config_classic.go:108), so `G_initcode_word` stays at
  * the base 0 here and is raised only by [[Eip3860GasCalculator]].
  */
class Eip3529GasCalculator extends Eip2929GasCalculator:
  // EIP-3529: R_sclear = SSTORE_RESET_GAS (2900) + ACCESS_LIST_STORAGE_KEY_COST (1900) = 4800.
  override def R_sclear: BigInt = 4800
  // EIP-3529: remove the SELFDESTRUCT refund.
  override def R_selfdestruct: BigInt = 0

/** EIP-3860 — initcode word metering (`G_initcode_word = 2`), over the reduced-refund [[Eip3529GasCalculator]] base.
  * The EIP-keyed shared base for the initcode-metering era; activated at ETH Shanghai / ETC Spiral (see
  * [[Eip3529GasCalculator]] for the divergent activation heights vs EIP-3529). Both family leaves (EtcOlympia,
  * EthCancun) root on this so the metering value is carried without an Etc*↔Eth* cross-reference.
  */
class Eip3860GasCalculator extends Eip3529GasCalculator:
  // EIP-3860: initcode word metering.
  override def G_initcode_word: BigInt = 2

// ---------------------------------------------------------------------------------------------------------------------
// Network-prefixed leaves — the Etc*/Eth* scala3-style.md ratchet boundary: an Etc* never extends/references an Eth*
// and vice versa, and neither is ever a shared base. Both extend an EIP-keyed shared base, never each other.
// ---------------------------------------------------------------------------------------------------------------------

/** ETC Olympia (ECIP-1121) — field-identical to [[Eip3860GasCalculator]] (the reduced-refund base plus EIP-3860
  * initcode metering, both active by Olympia — Olympia = Spiral + Ecip(1121)). The remaining Olympia-era gas deltas
  * (EIP-7883 MODEXP, EIP-2537 BLS, EIP-7951 P256, EIP-7623 calldata floor) are precompile / intrinsic-gas rules
  * enforced in the P5 precompile wrappers and L4 intrinsic-gas, not per-opcode tier fields. **ETC-only.**
  */
class EtcOlympiaGasCalculator extends Eip3860GasCalculator

/** ETH London — EIP-3529 refund reduction over [[Eip2929GasCalculator]], WITHOUT EIP-3860 initcode metering (that
  * arrives at Shanghai — see [[Eip3860GasCalculator]]). The ETH-named root of the post-London ETH fee lineage;
  * field-identical to [[Eip3529GasCalculator]] but rooted on an ETH-named class so the ETH chain never references an
  * ETC leaf. **ETH-only.**
  */
class EthLondonGasCalculator extends Eip2929GasCalculator:
  override def R_sclear: BigInt = 4800
  override def R_selfdestruct: BigInt = 0

/** ETH Cancun — the reduced-refund fields plus EIP-3860 initcode metering (active since Shanghai), so it roots on the
  * EIP-keyed [[Eip3860GasCalculator]] (which extends [[Eip3529GasCalculator]], preserving London's EIP-3529 refund
  * fields). EIP-1153/4844/5656/7516 are opcode/blob mechanics, not per-op tier fields. **ETH-only.**
  */
class EthCancunGasCalculator extends Eip3860GasCalculator

/** ETH Prague — EIP-7623 adds a calldata *floor* (an L4 block-level `max(...)`, not a tier field), so the opcode gas
  * schedule is unchanged from Cancun. **ETH-only.**
  */
class EthPragueGasCalculator extends EthCancunGasCalculator

/** ETH Osaka — EIP-7883 (MODEXP gas) + EIP-7823 (MODEXP input bound) are enforced inside the MODEXP precompile (P5),
  * not the opcode gas schedule; opcode fields unchanged from Prague. **ETH-only.**
  */
class EthOsakaGasCalculator extends EthPragueGasCalculator
