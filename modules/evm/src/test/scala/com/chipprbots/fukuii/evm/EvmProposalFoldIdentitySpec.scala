package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.evm.ProposalId.Ecip
import com.chipprbots.fukuii.evm.ProposalId.Eip

/** P3 fold-identity gate (L3 plan §8/§9, the AS-IS `EvmProposalDerivationSpec`/`ForBlockFoldIdentitySpec` analog).
  *
  * Proves the [[EvmConfig.deriveEvmConfigAt]] fold reproduces the direct-construction per-fork bundles
  * (`OpCodes.*`/`GasCalculator.*`/`EvmConfig.*`) **byte-for-byte at every activation height on both fork clocks** — ETC
  * (Frontier→…→Spiral→Olympia, block clock) and ETH (Frontier→…→Cancun→Prague→Osaka, timestamp clock). This is the
  * Chesterton's Fence proof that the single [[EvmConfig.forBlock]] resolves the same `(opcode table, gas schedule,
  * config flags)` the named bundles carry, so the fold is safe as the production dispatch. Consensus-critical: forge
  * (ETC/Olympia) + beacon (ETH/Osaka) co-sign the byte values.
  */
class EvmProposalFoldIdentitySpec extends AnyFunSuite:

  import EvmProposals.*

  // -- oracles: byte-identity comparators (IArray/GasCalculator lack structural ==) ---------------------------------

  /** The full observable gas signature — the 41 fee fields + the gas-cap divisor + the EIP-2929 warm/cold access-cost
    * probes. `GasCalculator` is a strategy object with no structural `==`, so byte-identity is field-value identity.
    */
  private def gasSig(g: GasCalculator): List[BigInt] = List[BigInt](
    g.G_zero,
    g.G_base,
    g.G_verylow,
    g.G_low,
    g.G_mid,
    g.G_high,
    g.G_balance,
    g.G_sload,
    g.G_jumpdest,
    g.G_sset,
    g.G_sreset,
    g.R_sclear,
    g.R_selfdestruct,
    g.G_selfdestruct,
    g.G_create,
    g.G_codedeposit,
    g.G_call,
    g.G_callvalue,
    g.G_callstipend,
    g.G_newaccount,
    g.G_exp,
    g.G_expbyte,
    g.G_memory,
    g.G_txcreate,
    g.G_txdatazero,
    g.G_txdatanonzero,
    g.G_transaction,
    g.G_log,
    g.G_logdata,
    g.G_logtopic,
    g.G_sha3,
    g.G_sha3word,
    g.G_copy,
    g.G_blockhash,
    g.G_extcode,
    g.G_cold_sload,
    g.G_cold_account_access,
    g.G_warm_storage_read,
    g.G_access_list_address,
    g.G_access_list_storage,
    g.G_initcode_word,
    BigInt(g.subGasCapDivisor.getOrElse(-1L)),
    g.accountAccessCost(g.G_call, isWarm = true),
    g.accountAccessCost(g.G_call, isWarm = false),
    g.storageAccessCost(g.G_sload, isWarm = true),
    g.storageAccessCost(g.G_sload, isWarm = false)
  )

  private def gasEquiv(a: GasCalculator, b: GasCalculator): Boolean = gasSig(a) == gasSig(b)

  /** Opcode tables compared element-wise — `IArray[OpCode]` `==` is Array reference equality; the dense-table build
    * canonicalizes order by `op.code`, so a set-equal opcode fold produces an element-equal table.
    */
  private def opcodesEquiv(actual: IArray[OpCode], expected: List[OpCode]): Boolean =
    actual.toList == OpCodes.denseTable(expected).toList

  /** Full-bundle byte-identity: opcode table + gas schedule + every config flag. `activeProposals` is intentionally NOT
    * compared — the fold carries the fuller cumulative set while the direct bundle carries a compact one; the gate is
    * over the *resolved* `(opcodes, gas, flags)`, not the input set.
    */
  private def bundleEquiv(actual: EvmConfig, oracle: EvmConfig): Boolean =
    actual.opCodes.toList == oracle.opCodes.toList &&
      gasEquiv(actual.gasCalculator, oracle.gasCalculator) &&
      actual.noEmptyAccounts == oracle.noEmptyAccounts &&
      actual.exceptionalFailedCodeDeposit == oracle.exceptionalFailedCodeDeposit &&
      actual.chargeSelfDestructForNewAccount == oracle.chargeSelfDestructForNewAccount &&
      actual.maxCodeSize == oracle.maxCodeSize

  private def mkHeader(number: BigInt, timestamp: Long): BlockHeader =
    BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = Address.Zero,
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 0,
      number = number,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = timestamp,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty
    )

  // -- registry integrity -------------------------------------------------------------------------------------------

  test("byId keyset equals evmApplicationOrder, with no duplicate in the order"):
    assert(
      byId.keySet == evmApplicationOrder.toSet &&
        evmApplicationOrder.distinct.sizeIs == evmApplicationOrder.size
    )

  // -- full-bundle fold identity (forks that have a named EvmConfig oracle) ------------------------------------------

  test("fold identity — Frontier bundle"):
    assert(bundleEquiv(EvmConfig.deriveEvmConfigAt(frontierSet), EvmConfig.Frontier))

  test("fold identity — ETH Cancun bundle"):
    assert(bundleEquiv(EvmConfig.deriveEvmConfigAt(ethCancunSet), EvmConfig.EthCancun))

  test("fold identity — ETH Prague bundle"):
    assert(bundleEquiv(EvmConfig.deriveEvmConfigAt(ethPragueSet), EvmConfig.EthPrague))

  test("fold identity — ETH Osaka bundle"):
    assert(bundleEquiv(EvmConfig.deriveEvmConfigAt(ethOsakaSet), EvmConfig.EthOsaka))

  test("fold identity — ETC Olympia bundle (forge co-sign)"):
    assert(bundleEquiv(EvmConfig.deriveEvmConfigAt(etcOlympiaSet), EvmConfig.EtcOlympia))

  // -- opcode-ladder fold identity (every named OpCodes bundle on both clocks) ---------------------------------------

  test("fold identity — opcode table at every activation height on both fork clocks"):
    val ladder: List[(Set[ProposalId], List[OpCode])] = List(
      frontierSet -> OpCodes.FrontierOpCodes,
      homesteadSet -> OpCodes.HomesteadOpCodes,
      byzantiumSet -> OpCodes.Eip140OpCodes,
      constantinopleSet -> OpCodes.Eip145OpCodes,
      istanbulSet -> OpCodes.Eip1344OpCodes,
      ethShanghaiSet -> OpCodes.EthShanghaiOpCodes,
      ethLondonSet -> OpCodes.EthLondonOpCodes,
      ethCancunSet -> OpCodes.EthCancunOpCodes,
      ethPragueSet -> OpCodes.EthPragueOpCodes,
      ethOsakaSet -> OpCodes.EthOsakaOpCodes,
      etcSpiralSet -> OpCodes.Eip3855OpCodes,
      etcOlympiaSet -> OpCodes.EtcOlympiaOpCodes
    )
    assert(ladder.forall((set, ops) => opcodesEquiv(EvmConfig.deriveEvmConfigAt(set).opCodes, ops)))

  // -- gas-ladder fold identity (every named GasCalculator; ETH London/Shanghai are field-identical to Eip3529) ------

  test("fold identity — gas schedule at every activation height on both fork clocks"):
    val ladder: List[(Set[ProposalId], GasCalculator)] = List(
      frontierSet -> GasCalculator.Frontier,
      homesteadSet -> GasCalculator.Homestead,
      eip150Set -> GasCalculator.Eip150,
      eip160Set -> GasCalculator.Eip160,
      istanbulSet -> GasCalculator.Eip1884,
      berlinSet -> GasCalculator.Eip2929,
      etcMystiqueSet -> GasCalculator.Eip3529,
      ethLondonSet -> GasCalculator.EthLondon,
      ethShanghaiSet -> GasCalculator.EthLondon,
      ethCancunSet -> GasCalculator.EthCancun,
      ethPragueSet -> GasCalculator.EthPrague,
      ethOsakaSet -> GasCalculator.EthOsaka,
      etcOlympiaSet -> GasCalculator.EtcOlympia
    )
    assert(ladder.forall((set, gas) => gasEquiv(EvmConfig.deriveEvmConfigAt(set).gasCalculator, gas)))

  // -- ordered-fold determinism: the last gas selection in evmApplicationOrder wins (NOT Set iteration) --------------

  test("gas leaf selection is canonically ordered — ETC Olympia stays EtcOlympia, never an ETH leaf"):
    val o = EvmConfig.deriveEvmConfigAt(etcOlympiaSet).gasCalculator
    assert(
      gasEquiv(o, GasCalculator.EtcOlympia) &&
        !etcOlympiaSet.contains(Eip(4844)) && !etcOlympiaSet.contains(Eip(7516)) &&
        !etcOlympiaSet.contains(Eip(7691)) && !etcOlympiaSet.contains(Eip(7918))
    )

  test("gas leaf selection is canonically ordered — ETH Osaka resolves EthOsaka over earlier Cancun/Prague selectors"):
    // ethOsakaSet contains 4844(→Cancun), 7691(→Prague) and 7918(→Osaka); last-in-order (7918) must win.
    assert(
      ethOsakaSet.contains(Eip(4844)) && ethOsakaSet.contains(Eip(7691)) && ethOsakaSet.contains(Eip(7918)) &&
        gasEquiv(EvmConfig.deriveEvmConfigAt(ethOsakaSet).gasCalculator, GasCalculator.EthOsaka)
    )

  // -- Olympia reconciliation (RX-L3-21; forge co-sign) -------------------------------------------------------------

  test("Ecip1121Olympia.requires is the reconciled 12-EIP set and folds to the EtcOlympia bundle"):
    assert(
      Ecip1121Olympia.requires ==
        Set(3198, 1153, 5656, 7939, 6780, 2537, 7951, 7883, 7823, 7623, 7702, 1559).map(Eip.apply) &&
        etcOlympiaSet.contains(Ecip(1121)) &&
        bundleEquiv(EvmConfig.deriveEvmConfigAt(etcOlympiaSet), EvmConfig.EtcOlympia)
    )

  // -- forBlock ≡ fold, over the axis-tagged ForkSchedule (block clock ⊥ timestamp clock) ----------------------------

  /** Build a `ForkSchedule` from a monotonic `(coordinate, cumulativeSet)` ladder: each proposal activates at the
    * lowest coordinate whose cumulative set first contains it, on the given axis.
    */
  private def scheduleOf(ladder: List[(Long, Set[ProposalId])], axis: Long => ForkActivation): ForkSchedule =
    val entries = evmApplicationOrder.iterator.flatMap { id =>
      ladder.find(_._2.contains(id)).map(h => id -> axis(h._1))
    }.toMap
    ForkSchedule(entries)

  private val etcLadder: List[(Long, Set[ProposalId])] = List(
    10L -> homesteadSet,
    20L -> eip150Set,
    30L -> eip160Set,
    40L -> spuriousDragonSet,
    50L -> byzantiumSet,
    60L -> constantinopleSet,
    70L -> istanbulSet,
    80L -> berlinSet,
    90L -> etcMystiqueSet,
    100L -> etcSpiralSet,
    110L -> etcOlympiaSet
  )

  private val ethLadder: List[(Long, Set[ProposalId])] = List(
    10L -> homesteadSet,
    20L -> eip150Set,
    30L -> eip160Set,
    40L -> spuriousDragonSet,
    50L -> byzantiumSet,
    60L -> constantinopleSet,
    70L -> istanbulSet,
    80L -> berlinSet,
    90L -> ethLondonSet,
    100L -> ethShanghaiSet,
    110L -> ethCancunSet,
    120L -> ethPragueSet,
    130L -> ethOsakaSet
  )

  private val byBlockAxis: Long => ForkActivation = h => ForkActivation.ByBlock(BigInt(h))
  private val byTimestampAxis: Long => ForkActivation = h => ForkActivation.ByTimestamp(h)

  test("forBlock over the BLOCK clock (ETC) reproduces the fold at every fork height"):
    val schedule = scheduleOf(etcLadder, byBlockAxis)
    assert(
      etcLadder.forall((h, set) =>
        bundleEquiv(EvmConfig.forBlock(mkHeader(number = h, timestamp = 0), schedule), EvmConfig.deriveEvmConfigAt(set))
      )
    )

  test("forBlock over the TIMESTAMP clock (ETH) reproduces the fold at every fork height"):
    val schedule = scheduleOf(ethLadder, byTimestampAxis)
    assert(
      ethLadder.forall((h, set) =>
        bundleEquiv(EvmConfig.forBlock(mkHeader(number = 0, timestamp = h), schedule), EvmConfig.deriveEvmConfigAt(set))
      )
    )

  test("forBlock at the ETC Olympia height byte-matches the EtcOlympia named bundle"):
    val schedule = scheduleOf(etcLadder, byBlockAxis)
    assert(bundleEquiv(EvmConfig.forBlock(mkHeader(number = 110, timestamp = 0), schedule), EvmConfig.EtcOlympia))

  test("forBlock at the ETH Osaka timestamp byte-matches the EthOsaka named bundle"):
    val schedule = scheduleOf(ethLadder, byTimestampAxis)
    assert(bundleEquiv(EvmConfig.forBlock(mkHeader(number = 0, timestamp = 130), schedule), EvmConfig.EthOsaka))
