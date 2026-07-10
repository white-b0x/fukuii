package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum

import com.chipprbots.ethereum.domain.AccessListItem
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TotalDifficulty
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.forks.ProposalId
import com.chipprbots.ethereum.forks.ProposalId.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm
import com.chipprbots.ethereum.vm.forks.EvmProposals

import EvmConfig.*

// scalastyle:off magic.number
object EvmConfig:

  type EvmConfigBuilder = BlockchainConfigForEvm => EvmConfig

  val MaxCallDepth: Int = 1024

  val MaxMemory: UInt256 = UInt256(
    Int.MaxValue
  ) /* used to artificially limit memory usage by incurring maximum gas cost */

  /** The EVM config for a given block — block-number dispatch only (Row 5.3b: derived by folding the active proposals,
    * no timestamp forks). Delegates to the [[BlockchainConfigForEvm]] overload, which computes the active proposal set
    * from the chain's block-number fields.
    */
  def forBlock(blockNumber: BlockNumber, blockchainConfig: BlockchainConfig): EvmConfig =
    forBlock(blockNumber, BlockchainConfigForEvm(blockchainConfig))

  /** The EVM config for a given block AND timestamp — the full fork axis (Row 5.3b). Computes the active proposal set
    * from the chain's L3 [[com.chipprbots.ethereum.forks.ForkSchedule]] (`isActive` over block / timestamp / TTD), then
    * derives the opcode set, fee schedule and boolean flags by folding those active proposals. Post-Merge ETH timestamp
    * forks (Shanghai/Cancun/Prague/Osaka) enter via the schedule's `ByTimestamp` activations; ETC block forks via
    * `ByBlock`. No EVM proposal activates on the TTD axis, so the Merge does not change the derived EVM config.
    */
  def forBlock(blockNumber: BlockNumber, timestamp: Timestamp, blockchainConfig: BlockchainConfig): EvmConfig =
    val schedule = blockchainConfig.forkSchedule
    val td0 = TotalDifficulty(0)
    val active: Set[ProposalId] =
      EvmProposals.evmApplicationOrder.filter(id => schedule.isActive(id, blockNumber, timestamp, td0)).toSet
    deriveEvmConfigAt(active, BlockchainConfigForEvm(blockchainConfig))

  /** The EVM config for a given block — block-number dispatch (Row 5.3b). The active proposal set is computed from the
    * chain's block-number fields via [[EvmProposals.activeBlockProposals]] (network-aware), then the opcode set, fee
    * schedule and boolean flags are DERIVED by folding those proposals. This is the block-only path: ETH timestamp
    * forks are absent (they require the 3-arg overload). Byte-identical to the former per-fork `*ConfigBuilder` bundle
    * for every fork on both networks (`ForBlockFoldIdentitySpec`).
    */
  def forBlock(blockNumber: BlockNumber, blockchainConfig: BlockchainConfigForEvm): EvmConfig =
    deriveEvmConfigAt(EvmProposals.activeBlockProposals(blockchainConfig, blockNumber), blockchainConfig)

  /** Derive a full [[EvmConfig]] from an active proposal set (Row 5.3b — the single fold both `forBlock` and the
    * `*ConfigBuilder` test fixtures go through). Opcode set + fee schedule come from [[EvmProposals.deriveEvm]] over
    * the Frontier base; the boolean flags come from folding each active proposal's `configDelta` in
    * `evmApplicationOrder`.
    */
  def deriveEvmConfigAt(active: Set[ProposalId], config: BlockchainConfigForEvm): EvmConfig =
    val (opcodes, feeValues) = EvmProposals.deriveEvm(active)
    val base = EvmConfig(
      blockchainConfig = config,
      feeSchedule = feeValues,
      opCodeList = OpCodeList(opcodes),
      exceptionalFailedCodeDeposit = false,
      subGasCapDivisor = None,
      chargeSelfDestructForNewAccount = false,
      traceInternalTransactions = false
    )
    EvmProposals.evmApplicationOrder.iterator
      .filter(active.contains)
      .flatMap(EvmProposals.byId.get)
      .foldLeft(base)((cfg, p) => p.configDelta(cfg))

  val FrontierOpCodes: OpCodeList = OpCodeList(OpCodes.FrontierOpCodes)
  val HomesteadOpCodes: OpCodeList = OpCodeList(OpCodes.HomesteadOpCodes)
  val ByzantiumOpCodes: OpCodeList = OpCodeList(OpCodes.ByzantiumOpCodes)
  val AtlantisOpCodes = ByzantiumOpCodes
  val ConstantinopleOpCodes: OpCodeList = OpCodeList(OpCodes.ConstantinopleOpCodes)
  val AghartaOpCodes = ConstantinopleOpCodes
  val PhoenixOpCodes: OpCodeList = OpCodeList(OpCodes.PhoenixOpCodes)
  val MagnetoOpCodes: OpCodeList = PhoenixOpCodes
  val SpiralOpCodes: OpCodeList = OpCodeList(OpCodes.SpiralOpCodes)
  val EthLondonOpCodes: OpCodeList = OpCodeList(OpCodes.EthLondonOpCodes)
  val EthShanghaiOpCodes: OpCodeList = OpCodeList(OpCodes.EthShanghaiOpCodes)
  val EthCancunOpCodes: OpCodeList = OpCodeList(OpCodes.EthCancunOpCodes)
  val EtcOlympiaOpCodes: OpCodeList = OpCodeList(OpCodes.EtcOlympiaOpCodes)
  val EthOsakaOpCodes: OpCodeList = OpCodeList(OpCodes.EthOsakaOpCodes)

  // Cumulative active-proposal set per fork — the single place fork→proposal membership is declared for the fixture
  // builders below. Each `*ConfigBuilder` is now a thin wrapper over `deriveEvmConfigAt` (Row 5.3b): the opcode set,
  // fee schedule and boolean flags are DERIVED from the fold, not hand-copied. Config-only ids (Eip(161)/Eip(3541)/
  // Eip(3651)/Custom("eip3860-metering",0)/Eip(6049)/Eip(6780)) carry no opcode/fee delta — they only add their flag —
  // so `deriveEvm`'s opcode/fee output is unchanged by their presence (proven byte-identical in EvmProposalDerivationSpec
  // + ForBlockFoldIdentitySpec). Where two ETC/ETH forks share the same EVM content (Atlantis≡Byzantium, Agharta≡
  // Constantinople, Istanbul≡Phoenix, Berlin≡Magneto) the builders share a set — the network divergence is only in the
  // block-number the proposal activates at (see EvmProposals.blockEvmActivations), never in the derived config.
  private val frontierSet: Set[ProposalId] = Set.empty
  private val homesteadSet: Set[ProposalId] = frontierSet ++ Set(Eip(2), Eip(7))
  private val postEip150Set: Set[ProposalId] = homesteadSet + Eip(150)
  private val postEip160Set: Set[ProposalId] = postEip150Set + Eip(160)
  private val postEip161Set: Set[ProposalId] = postEip160Set + Eip(161)
  private val byzantiumSet: Set[ProposalId] = postEip161Set ++ Set(Eip(140), Eip(211))
  private val constantinopleSet: Set[ProposalId] = byzantiumSet ++ Set(Eip(1052), Eip(1014), Eip(145))
  private val phoenixSet: Set[ProposalId] = constantinopleSet ++ Set(Eip(1344), Eip(1884), Eip(2028))
  private val magnetoSet: Set[ProposalId] = phoenixSet ++ Set(Eip(2929), Eip(2930))
  private val mystiqueSet: Set[ProposalId] = magnetoSet ++ Set(Eip(3529), Eip(3541), Eip(3860))
  private val spiralSet: Set[ProposalId] =
    mystiqueSet ++ Set(Eip(3855), Eip(3651), Custom("eip3860-metering", 0), Eip(6049))
  private val etcOlympiaSet: Set[ProposalId] =
    spiralSet ++ Set(Eip(3198), Eip(1153), Eip(5656), Eip(6780), Eip(7939), Ecip(1121))
  // ETH London: EIP-3198 BASEFEE + EIP-3529 refund + EIP-3541 0xEF-reject + EIP-3860 initcode-word value, over Magneto.
  // No Shanghai+ flags (eip3651/eip3860-metering/eip6780) — those enter via the 3-arg timestamp fold.
  private val ethLondonSet: Set[ProposalId] = magnetoSet ++ Set(Eip(3198), Eip(3529), Eip(3541), Eip(3860))

  val FrontierConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(frontierSet, config)
  val HomesteadConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(homesteadSet, config)
  val PostEIP150ConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(postEip150Set, config)
  val PostEIP160ConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(postEip160Set, config)
  val PostEIP161ConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(postEip161Set, config)
  val ByzantiumConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(byzantiumSet, config)
  val ConstantinopleConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(constantinopleSet, config)
  val PetersburgConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(constantinopleSet, config)
  val IstanbulConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(phoenixSet, config)

  // Ethereum classic forks only — same EVM content as their ETH counterparts (see set-sharing note above).
  val AtlantisConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(byzantiumSet, config)
  val AghartaConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(constantinopleSet, config)
  val PhoenixConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(phoenixSet, config)
  val MagnetoConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(magnetoSet, config)
  val BerlinConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(magnetoSet, config)
  val MystiqueConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(mystiqueSet, config)
  val SpiralConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(spiralSet, config)

  /** London-only config for ETH chains (EIP-1559/3198/3529/3541), without Shanghai+ EIPs. Reached on ETH where the
    * `olympia` block field carries the London height; the Shanghai/Cancun/Osaka overlays enter via the 3-arg timestamp
    * fold. ETH-only, so the BASEFEE-carrying opcode set never affects the ETC path.
    */
  val LondonConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(ethLondonSet, config)

  val OlympiaConfigBuilder: EvmConfigBuilder = config => deriveEvmConfigAt(etcOlympiaSet, config)

  case class OpCodeList(opCodes: List[OpCode]):
    val byteToOpCode: Map[Byte, OpCode] =
      opCodes.map(op => op.code -> op).toMap

case class EvmConfig(
    blockchainConfig: BlockchainConfigForEvm,
    feeSchedule: FeeSchedule,
    opCodeList: OpCodeList,
    exceptionalFailedCodeDeposit: Boolean,
    subGasCapDivisor: Option[Long],
    chargeSelfDestructForNewAccount: Boolean,
    traceInternalTransactions: Boolean,
    noEmptyAccounts: Boolean = false,
    eip3541Enabled: Boolean = false,
    eip3651Enabled: Boolean = false,
    eip3860Enabled: Boolean = false,
    eip6049DeprecationEnabled: Boolean = false,
    eip6780Enabled: Boolean = false
):

  import feeSchedule.*
  import EvmConfig.*

  def opCodes: List[OpCode] =
    opCodeList.opCodes

  def byteToOpCode: Map[Byte, OpCode] =
    opCodeList.byteToOpCode

  /** Calculate gas cost of memory usage. Incur a blocking gas cost if memory usage exceeds reasonable limits.
    *
    * @param memSize
    *   current memory size in bytes
    * @param offset
    *   memory offset to be written/read
    * @param dataSize
    *   size of data to be written/read in bytes
    * @return
    *   gas cost
    */
  def calcMemCost(memSize: BigInt, offset: BigInt, dataSize: BigInt): BigInt =

    /** See YP H.1 (222) */
    def c(m: BigInt): BigInt =
      val a = wordsForBytes(m)
      G_memory * a + a * a / 512

    val memNeeded = if dataSize == 0 then BigInt(0) else offset + dataSize
    if memNeeded > MaxMemory then UInt256.MaxValue / 2
    else if memNeeded <= memSize then 0
    else c(memNeeded) - c(memSize)

  /** Calculates transaction intrinsic gas. See YP section 6.2
    */
  def calcTransactionIntrinsicGas(
      txData: ByteString,
      isContractCreation: Boolean,
      accessList: Seq[AccessListItem],
      authorizationListSize: Int = 0
  ): BigInt =
    val txDataZero = txData.count(_ == 0)
    val txDataNonZero = txData.length - txDataZero

    val accessListPrice =
      accessList.size * G_access_list_address +
        accessList.map(_.storageKeys.size).sum * G_access_list_storage

    // EIP-7702: Per-authorization intrinsic gas = PER_AUTH_BASE_COST (25000) per EIP spec
    val authListPrice: BigInt = BigInt(authorizationListSize) * BigInt(25000)

    val initCodeCost: BigInt = if isContractCreation then calcInitCodeCost(txData) else BigInt(0)

    txDataZero * G_txdatazero +
      txDataNonZero * G_txdatanonzero + accessListPrice + authListPrice +
      (if isContractCreation then G_txcreate else 0) +
      G_transaction +
      initCodeCost

  /** If the initialization code completes successfully, a final contract-creation cost is paid, the code-deposit cost,
    * proportional to the size of the created contract’s code. See YP equation (96)
    *
    * @param executionResultData
    *   Transaction code initialization result
    * @return
    *   Calculated gas cost
    */
  def calcCodeDepositCost(executionResultData: ByteString): BigInt =
    G_codedeposit * executionResultData.size

  /** a helper method used for gas adjustment in CALL and CREATE opcode, see YP eq. (224)
    */
  def gasCap(g: BigInt): BigInt =
    subGasCapDivisor.map(d => g - g / d).getOrElse(g)

  def maxCodeSize: Option[BigInt] =
    blockchainConfig.maxCodeSize

  /** EIP-3860: Maximum initcode size (2 * MAX_CODE_SIZE)
    */
  def maxInitCodeSize: Option[BigInt] =
    if eip3860Enabled then maxCodeSize.map(_ * 2) else None

  /** EIP-3860: Calculate gas cost for initcode
    * @param initCode
    *   The initialization code
    * @return
    *   Gas cost (INITCODE_WORD_COST * ceil(len(initcode) / 32))
    */
  def calcInitCodeCost(initCode: ByteString): BigInt =
    if eip3860Enabled then
      val words = wordsForBytes(initCode.size)
      feeSchedule.G_initcode_word * words
    else BigInt(0)

object FeeSchedule:

  class FrontierFeeSchedule extends FeeSchedule:
    override val G_zero = 0
    override val G_base = 2
    override val G_verylow = 3
    override val G_low = 5
    override val G_mid = 8
    override val G_high = 10
    override val G_balance = 20
    override val G_sload = 50
    override val G_jumpdest = 1
    override val G_sset = 20000
    override val G_sreset = 5000
    override val R_sclear = 15000
    override val R_selfdestruct = 24000
    override val G_selfdestruct = 0
    override val G_create = 32000
    override val G_codedeposit = 200
    override val G_call = 40
    override val G_callvalue = 9000
    override val G_callstipend = 2300
    override val G_newaccount = 25000
    override val G_exp = 10
    override val G_expbyte = 10
    override val G_memory = 3
    override val G_txcreate = 0
    override val G_txdatazero = 4
    override val G_txdatanonzero = 68
    override val G_transaction = 21000
    override val G_log = 375
    override val G_logdata = 8
    override val G_logtopic = 375
    override val G_sha3 = 30
    override val G_sha3word = 6
    override val G_copy = 3
    override val G_blockhash = 20
    override val G_extcode = 20

    // note: the access list and cold/warm access do not exist until magneto hard fork
    override val G_cold_sload = 2100
    override val G_cold_account_access = 2600
    override val G_warm_storage_read = 100
    override val G_access_list_address = 2400
    override val G_access_list_storage = 1900
    // note: initcode metering does not exist until spiral hard fork (EIP-3860)
    override val G_initcode_word = 0

  class HomesteadFeeSchedule extends FrontierFeeSchedule:
    override val G_txcreate = 32000

  class PostEIP150FeeSchedule extends HomesteadFeeSchedule:
    override val G_sload = 200
    override val G_call = 700
    override val G_balance = 400
    override val G_selfdestruct = 5000
    override val G_extcode = 700

  class PostEIP160FeeSchedule extends PostEIP150FeeSchedule:
    override val G_expbyte = 50

  class ByzantiumFeeSchedule extends PostEIP160FeeSchedule

  class ConstantionopleFeeSchedule extends ByzantiumFeeSchedule

  class AtlantisFeeSchedule extends PostEIP160FeeSchedule

  class AghartaFeeSchedule extends ByzantiumFeeSchedule

  class PhoenixFeeSchedule extends AghartaFeeSchedule:
    override val G_sload: BigInt = 800
    override val G_balance: BigInt = 700
    override val G_txdatanonzero = 16

  class MagnetoFeeSchedule extends PhoenixFeeSchedule:
    override val G_sload: BigInt = G_warm_storage_read
    override val G_sreset: BigInt = 5000 - G_cold_sload
    override val G_sset: BigInt = 20000 // EIP-2929: G_sset remains 20000, cold access cost added separately in SSTORE
    override val G_access_list_address: BigInt = 2400
    override val G_access_list_storage: BigInt = 1900

  class MystiqueFeeSchedule extends MagnetoFeeSchedule:
    // EIP-3529: Reduce refunds for SSTORE
    // R_sclear = SSTORE_RESET_GAS + ACCESS_LIST_STORAGE_KEY_COST = 2900 + 1900 = 4800
    override val R_sclear: BigInt = 4800
    // EIP-3529: Remove SELFDESTRUCT refund
    override val R_selfdestruct: BigInt = 0
    // EIP-3860: Initcode metering (activated in Spiral fork)
    override val G_initcode_word: BigInt = 2

  /** ETH London fee schedule — ETH-named root for the post-London ETH fee lineage (Cancun→Prague→Osaka). Carries the
    * EIP-3529 refund changes (R_sclear=4800, R_selfdestruct=0) and EIP-3860 initcode metering (G_initcode_word=2)
    * explicitly, so the ETH chain roots on an ETH-named class rather than the ETC-fork-named MystiqueFeeSchedule.
    * Field-identical to MystiqueFeeSchedule (same MagnetoFeeSchedule base, same three overrides) — the de-alias splits
    * the shared class along the network boundary without changing any value. See Batch 5 Row 5.1 (beacon Q5).
    */
  class EthLondonFeeSchedule extends MagnetoFeeSchedule:
    // EIP-3529: Reduce refunds for SSTORE (R_sclear = 2900 + 1900 = 4800)
    override val R_sclear: BigInt = 4800
    // EIP-3529: Remove SELFDESTRUCT refund
    override val R_selfdestruct: BigInt = 0
    // EIP-3860: Initcode metering
    override val G_initcode_word: BigInt = 2

  /** ETH Cancun fee schedule — same fee fields as ETH London (Cancun's EIP-1153/4844/5656/7516 changes are opcode/blob
    * mechanics, not per-op gas-schedule fields). ETH-only.
    */
  class EthCancunFeeSchedule extends EthLondonFeeSchedule

  /** ETC Olympia fee schedule — ECIP-1121, block-based. Field-identical to MystiqueFeeSchedule (empty extension), which
    * is the ETC-lineage shared base carrying the EIP-3529/3860 values ETC adopted. Distinct from EthCancunFeeSchedule
    * so the ETC and ETH Olympia-era fee bundles are segregated (no shared class across networks). ETC-only.
    */
  class EtcOlympiaFeeSchedule extends MystiqueFeeSchedule

  /** ETH Prague fee schedule — EIP-7623 does NOT modify G_txdatazero/G_txdatanonzero (still 4/16). Instead it adds a
    * calldata floor via `calcFloorDataGas` applied by BlockPreparator as `max(executionGasBase, 21000 + tokens * 10)`.
    * See BlockPreparator.calcFloorDataGas. ETH-only.
    */
  class EthPragueFeeSchedule extends EthCancunFeeSchedule

  /** ETH Osaka fee schedule — same as Prague. MODEXP cost doubling (EIP-7883) and input bounds (EIP-7823) are enforced
    * inside the MODEXP precompile itself, not the fee schedule. ETH-only.
    */
  class EthOsakaFeeSchedule extends EthPragueFeeSchedule

trait FeeSchedule:
  val G_zero: BigInt
  val G_base: BigInt
  val G_verylow: BigInt
  val G_low: BigInt
  val G_mid: BigInt
  val G_high: BigInt
  val G_balance: BigInt
  val G_sload: BigInt
  val G_jumpdest: BigInt
  val G_sset: BigInt
  val G_sreset: BigInt
  val R_sclear: BigInt
  val R_selfdestruct: BigInt
  val G_selfdestruct: BigInt
  val G_create: BigInt
  val G_codedeposit: BigInt
  val G_call: BigInt
  val G_callvalue: BigInt
  val G_callstipend: BigInt
  val G_newaccount: BigInt
  val G_exp: BigInt
  val G_expbyte: BigInt
  val G_memory: BigInt
  val G_txcreate: BigInt
  val G_txdatazero: BigInt
  val G_txdatanonzero: BigInt
  val G_transaction: BigInt
  val G_log: BigInt
  val G_logdata: BigInt
  val G_logtopic: BigInt
  val G_sha3: BigInt
  val G_sha3word: BigInt
  val G_copy: BigInt
  val G_blockhash: BigInt
  val G_extcode: BigInt
  val G_cold_sload: BigInt
  val G_cold_account_access: BigInt
  val G_warm_storage_read: BigInt
  val G_access_list_address: BigInt
  val G_access_list_storage: BigInt
  val G_initcode_word: BigInt
