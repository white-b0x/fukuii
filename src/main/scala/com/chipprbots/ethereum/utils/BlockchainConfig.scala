package com.chipprbots.ethereum.utils

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*
import scala.util.Try

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigRenderOptions

import com.chipprbots.ethereum.consensus.pos.BlobGasUtils
import com.chipprbots.ethereum.consensus.pow.mess.MESSConfig
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TotalDifficulty
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.forks.ForkActivation
import com.chipprbots.ethereum.forks.ForkSchedule
import com.chipprbots.ethereum.forks.ParamValue
import com.chipprbots.ethereum.forks.ProposalId
import com.chipprbots.ethereum.forks.ProposalId.*
import com.chipprbots.ethereum.forks.ProposalParams
import com.chipprbots.ethereum.forks.ScheduledProposal
import com.chipprbots.ethereum.utils.NumericUtils.*
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm
import com.chipprbots.ethereum.vm.forks.EvmProposals

/** Identifies whether the chain follows ETC (PoW indefinitely) or ETH (post-Merge PoS via CL). */
enum NetworkType:
  case ETC
  case ETH

object NetworkType:
  def fromString(s: String): NetworkType = s.toLowerCase match
    case "etc" => ETC
    case "eth" => ETH
    case other => throw new IllegalArgumentException(s"Unknown network-type: $other (expected 'etc' or 'eth')")

/** Timestamp-based fork activation for post-Merge Ethereum forks. */
case class ForkTimestamps(
    shanghaiTimestamp: Option[Long] = None,
    cancunTimestamp: Option[Long] = None,
    pragueTimestamp: Option[Long] = None,
    osakaTimestamp: Option[Long] = None,
    bpo1Timestamp: Option[Long] = None,
    bpo2Timestamp: Option[Long] = None
)

case class BlockchainConfig(
    powTargetTime: Option[Long] = None,
    forkBlockNumbers: ForkBlockNumbers,
    maxCodeSize: Option[BigInt],
    customGenesisFileOpt: Option[String],
    customGenesisJsonOpt: Option[String],
    daoForkConfig: Option[DaoForkConfig],
    accountStartNonce: UInt256,
    chainId: ChainId,
    networkId: Long,
    monetaryPolicyConfig: MonetaryPolicyConfig,
    gasTieBreaker: Boolean,
    ethCompatibleStorage: Boolean,
    bootstrapNodes: Set[String],
    dnsDiscoveryDomains: Seq[String] = Seq.empty,
    allowedMinersPublicKeys: Set[ByteString] = Set.empty,
    messConfig: MESSConfig = MESSConfig(),
    treasuryAddress: Address = Address(0),
    // EIP-6110: beacon deposit contract whose DepositEvent logs are parsed into execution-layer
    // deposit requests. Network-specific: mainnet = 0x00000000219ab540356cBB839Cbe05303d7705Fa,
    // Sepolia = 0x7f02C3E3c98b133055B8B348B2Ac625669Ed295D. Default is mainnet so non-ETH configs
    // and existing named-arg test fixtures are unaffected.
    depositContractAddress: Address = Address("0x00000000219ab540356cBB839Cbe05303d7705Fa"),
    baseFeeFloor: BigInt = BigInt(0),
    minTip: BigInt = BigInt(1000000000),
    networkType: NetworkType = NetworkType.ETC,
    terminalTotalDifficulty: Option[BigInt] = None,
    forkTimestamps: ForkTimestamps = ForkTimestamps()
):
  def isPoS(totalDifficulty: TotalDifficulty): Boolean =
    terminalTotalDifficulty.exists(ttd => totalDifficulty.value >= ttd)

  def isShanghaiTimestamp(timestamp: Timestamp): Boolean =
    forkTimestamps.shanghaiTimestamp.exists(ts => timestamp.toLong >= ts)

  def isCancunTimestamp(timestamp: Timestamp): Boolean =
    forkTimestamps.cancunTimestamp.exists(ts => timestamp.toLong >= ts)

  def isPragueTimestamp(timestamp: Timestamp): Boolean =
    forkTimestamps.pragueTimestamp.exists(ts => timestamp.toLong >= ts)

  def isOsakaTimestamp(timestamp: Timestamp): Boolean =
    forkTimestamps.osakaTimestamp.exists(ts => timestamp.toLong >= ts)

  /** EIP-7892 Blob Parameter Only (BPO) fork activation. BPOs raise the blob target/max without other consensus
    * changes. Sepolia activated BPO1 on 2025-10-21.
    */
  def isBpo1Timestamp(timestamp: Timestamp): Boolean =
    forkTimestamps.bpo1Timestamp.exists(ts => timestamp.toLong >= ts)

  /** EIP-7892 BPO2: second blob-target bump. Sepolia activated 2025-10-28. */
  def isBpo2Timestamp(timestamp: Timestamp): Boolean =
    forkTimestamps.bpo2Timestamp.exists(ts => timestamp.toLong >= ts)

  def withUpdatedForkBlocks(update: (ForkBlockNumbers) => ForkBlockNumbers): BlockchainConfig =
    copy(forkBlockNumbers = update(forkBlockNumbers))

  /** L3 fork schedule (Batch 5 framework §1.4) — a DERIVED VIEW over the existing L2 fields, computed on first access.
    *
    * ADDITIVE (Stage 5.3a): this is not a constructor parameter, so it changes no call site, `.copy`, equality or
    * `hashCode`; every existing `BlockchainConfig` builder and fixture is unaffected. No production dispatch path reads
    * it yet — the `EvmConfig.forBlock` switch is Stage 5.3b. The `ForkBlockNumbers`/`ForkTimestamps` structs remain the
    * permanent L2 HOCON representation (F9); this is an additional L3 view over them, never a replacement.
    */
  lazy val forkSchedule: ForkSchedule = BlockchainConfig.deriveForkSchedule(this)

case class ForkBlockNumbers(
    frontierBlockNumber: BlockNumber,
    homesteadBlockNumber: BlockNumber,
    eip106BlockNumber: BlockNumber,
    eip150BlockNumber: BlockNumber,
    eip155BlockNumber: BlockNumber,
    eip160BlockNumber: BlockNumber,
    eip161BlockNumber: BlockNumber,
    difficultyBombPauseBlockNumber: BlockNumber,
    difficultyBombContinueBlockNumber: BlockNumber,
    difficultyBombRemovalBlockNumber: BlockNumber,
    byzantiumBlockNumber: BlockNumber,
    constantinopleBlockNumber: BlockNumber,
    istanbulBlockNumber: BlockNumber,
    atlantisBlockNumber: BlockNumber,
    aghartaBlockNumber: BlockNumber,
    phoenixBlockNumber: BlockNumber,
    petersburgBlockNumber: BlockNumber,
    ecip1099BlockNumber: BlockNumber,
    muirGlacierBlockNumber: BlockNumber,
    magnetoBlockNumber: BlockNumber,
    berlinBlockNumber: BlockNumber,
    mystiqueBlockNumber: BlockNumber,
    spiralBlockNumber: BlockNumber,
    eip1559BlockNumber: BlockNumber,
    // EIP-4345 / EIP-5133 bomb-delay blocks: no EVM effect, but go-ethereum's params/config.go
    // (mainnet only) treats them as EIP-2124 fork-id checksum points. Default Long.MaxValue is
    // the sentinel ForkId.gatherBlockForks filters out — only eth-chain.conf sets real values.
    arrowGlacierBlockNumber: BlockNumber = BlockNumber(Long.MaxValue),
    grayGlacierBlockNumber: BlockNumber = BlockNumber(Long.MaxValue),
    // EIP-3675 / Sepolia post-Merge net-split block (1735371). Block-based fork that
    // must be in the EIP-2124 fork-id checksum chain — go-ethereum's params/config.go
    // lists this for Sepolia. Without it, our forkId hashes for Shanghai+ are off by
    // one CRC32 round and ForkIdValidator.checkSuperset rejects all chain-head peers.
    mergeNetsplitBlockNumber: BlockNumber = BlockNumber(Long.MaxValue),
    // Gas limit targets embedded in the fork schedule (EIP-7935 / ECIP-1121).
    // When Some(target), the miner converges toward that target from the fork activation
    // block onward via the standard ±1/1024 mechanism — the schedule is authoritative
    // regardless of operator config. None → fall back to miningConfig.gasLimitTarget.
    spiralGasTarget: Option[BigInt] = None,
    olympiaGasTarget: Option[BigInt] = None
):
  // Explicit, order-preserving list of every fork *block-number* field (the two trailing
  // `Option[BigInt]` gas-target fields are deliberately excluded — they aren't fork-id
  // checksum points). Order matches field-declaration order above, which matches the
  // pre-existing `productIterator` enumeration this replaced. Feeds ForkId.scala's fork-id
  // hash (network handshake compat) — do not reorder/add/remove entries without
  // `herald`/`forge` review, since that changes the wire-visible fork-id CRC32.
  def all: List[BigInt] = List(
    frontierBlockNumber,
    homesteadBlockNumber,
    eip106BlockNumber,
    eip150BlockNumber,
    eip155BlockNumber,
    eip160BlockNumber,
    eip161BlockNumber,
    difficultyBombPauseBlockNumber,
    difficultyBombContinueBlockNumber,
    difficultyBombRemovalBlockNumber,
    byzantiumBlockNumber,
    constantinopleBlockNumber,
    istanbulBlockNumber,
    atlantisBlockNumber,
    aghartaBlockNumber,
    phoenixBlockNumber,
    petersburgBlockNumber,
    ecip1099BlockNumber,
    muirGlacierBlockNumber,
    magnetoBlockNumber,
    berlinBlockNumber,
    mystiqueBlockNumber,
    spiralBlockNumber,
    eip1559BlockNumber,
    arrowGlacierBlockNumber,
    grayGlacierBlockNumber,
    mergeNetsplitBlockNumber
  ).map(_.value)

  /** Returns the convergence target that
    * [[com.chipprbots.ethereum.consensus.blocks.BlockGeneratorSkeleton.calculateGasLimit]] should aim for at the given
    * block number, based on the fork-embedded gas schedule. None means no fork schedule opinion for this era — caller
    * falls back to miningConfig.gasLimitTarget.
    */
  def gasLimitAdjustmentStartAt(blockNumber: BlockNumber): Option[BigInt] =
    if blockNumber >= eip1559BlockNumber then olympiaGasTarget
    else if blockNumber >= spiralBlockNumber then spiralGasTarget
    else None

object ForkBlockNumbers:
  val Empty: ForkBlockNumbers = ForkBlockNumbers(
    frontierBlockNumber = BlockNumber(0),
    homesteadBlockNumber = BlockNumber(Long.MaxValue),
    difficultyBombPauseBlockNumber = BlockNumber(Long.MaxValue),
    difficultyBombContinueBlockNumber = BlockNumber(Long.MaxValue),
    difficultyBombRemovalBlockNumber = BlockNumber(Long.MaxValue),
    eip106BlockNumber = BlockNumber(Long.MaxValue),
    eip150BlockNumber = BlockNumber(Long.MaxValue),
    eip160BlockNumber = BlockNumber(Long.MaxValue),
    eip155BlockNumber = BlockNumber(Long.MaxValue),
    eip161BlockNumber = BlockNumber(Long.MaxValue),
    byzantiumBlockNumber = BlockNumber(Long.MaxValue),
    constantinopleBlockNumber = BlockNumber(Long.MaxValue),
    istanbulBlockNumber = BlockNumber(Long.MaxValue),
    atlantisBlockNumber = BlockNumber(Long.MaxValue),
    aghartaBlockNumber = BlockNumber(Long.MaxValue),
    phoenixBlockNumber = BlockNumber(Long.MaxValue),
    petersburgBlockNumber = BlockNumber(Long.MaxValue),
    ecip1099BlockNumber = BlockNumber(Long.MaxValue),
    muirGlacierBlockNumber = BlockNumber(Long.MaxValue),
    magnetoBlockNumber = BlockNumber(Long.MaxValue),
    berlinBlockNumber = BlockNumber(Long.MaxValue),
    mystiqueBlockNumber = BlockNumber(Long.MaxValue),
    spiralBlockNumber = BlockNumber(Long.MaxValue),
    eip1559BlockNumber = BlockNumber(Long.MaxValue),
    mergeNetsplitBlockNumber = BlockNumber(Long.MaxValue)
  )

object BlockchainConfig:

  // The two "not scheduled" sentinels the fork fields park at (mirrors ForkId.scala): 10^18 is the genesis-JSON
  // "pending" marker (ETC parks eip1559-block-number here until Olympia is dated) and Long.MaxValue is the in-code
  // missing-key fallback. Both derive to `ForkActivation.Never`.
  private val OlympiaPendingSentinel: BigInt = BigInt("1000000000000000000")
  private val MaxBlockSentinel: BigInt = BigInt(Long.MaxValue)

  private def isPending(bn: BlockNumber): Boolean =
    bn.value == OlympiaPendingSentinel || bn.value == MaxBlockSentinel

  /** Derive the L3 [[ForkSchedule]] (framework §1.4) as a pure function of the existing L2 fields. ADDITIVE — no
    * production code reads the result in Stage 5.3a. `ForkScheduleDerivationSpec` proves, for the 5 real conf files,
    * that `isActive` reproduces the exact activation decision each underlying field already makes.
    *
    * Registered proposals (each exercises one activation axis so the derivation is provably faithful across all axes):
    *   - `Ecip(1017)` — always `ByBlock(0)` (emission applies from genesis; the per-network schedule differs only by
    *     the `MonetaryPolicy` param, not the activation height — F6).
    *   - `Ecip(1111/1112/1122)` — the ETC-family Olympia bundle, co-activated at `eip1559BlockNumber`. On ETH/Sepolia
    *     the `eip1559-block-number` field carries London/EIP-1559 (base-fee BURN), NOT ECIP-1111 Treasury routing, so
    *     the ECIP bundle is `Never` there (networkType gate); a pending/absent sentinel also derives to `Never`.
    *   - `Eip(4844)` — ETH-only blob fork, `ByTimestamp(cancunTimestamp)` (no ETC block-fork collision).
    *   - `Custom("bpo", 1/2)` — EIP-7892 Blob-Parameter-Only forks, `ByTimestamp(bpo{1,2}Timestamp)` (beacon F1). Their
    *     blob target/max params reference the single-sourced `BlobGasUtils` constants — additive, and NOT rewired into
    *     `BlobGasUtils` (which keeps reading `isBpo{1,2}Timestamp` directly; that rewire is out of 5.3a/b scope).
    *   - `Custom("merge", 0)` — the PoS transition ("the Merge" is `EthFamily`'s label), `ByTotalDifficulty(ttd)`.
    */
  def deriveForkSchedule(cfg: BlockchainConfig): ForkSchedule =
    val fb = cfg.forkBlockNumbers
    val ft = cfg.forkTimestamps

    val olympiaActivation: ForkActivation =
      if cfg.networkType == NetworkType.ETC && !isPending(fb.eip1559BlockNumber) then
        ForkActivation.ByBlock(fb.eip1559BlockNumber)
      else ForkActivation.Never

    def tsActivation(o: Option[Long]): ForkActivation =
      o.map(t => ForkActivation.ByTimestamp(Timestamp(t))).getOrElse(ForkActivation.Never)

    val ttdActivation: ForkActivation =
      cfg.terminalTotalDifficulty
        .map(td => ForkActivation.ByTotalDifficulty(TotalDifficulty(td)))
        .getOrElse(ForkActivation.Never)

    // A block field derives to ByBlock unless parked at a not-scheduled sentinel (isPending: 10^18 / Long.MaxValue),
    // which is Never. Block 0 is not a sentinel: a genesis-active marker derives to ByBlock(0). The `v == 0` fork-id
    // dedup lives in `ForkId.gatherBlockForks` (Row 5.8b), not here.
    def byBlockIfReal(bn: BlockNumber): ForkActivation =
      if isPending(bn) then ForkActivation.Never else ForkActivation.ByBlock(bn)

    // ECIP-1122 ClientPolicy params (banksy-owned): MIN_MINER_TIP + the gas-target schedule (Spiral 8M / Olympia 60M).
    val ecip1122Params: ProposalParams = ProposalParams(
      Map(ProposalParams.MinTipKey -> ParamValue.Number(cfg.minTip))
        ++ fb.spiralGasTarget.map(t => ProposalParams.SpiralGasTargetKey -> ParamValue.Number(t))
        ++ fb.olympiaGasTarget.map(t => ProposalParams.OlympiaGasTargetKey -> ParamValue.Number(t))
    )

    val rewardAndPolicyEntries: Map[ProposalId, ScheduledProposal] = Map(
      Ecip(1017) -> ScheduledProposal(
        ForkActivation.ByBlock(BlockNumber(0)),
        ProposalParams(Map(ProposalParams.MonetaryPolicyKey -> ParamValue.MonetaryPolicy(cfg.monetaryPolicyConfig)))
      ),
      Ecip(1111) -> ScheduledProposal(
        olympiaActivation,
        ProposalParams(
          Map(
            ProposalParams.TreasuryAddressKey -> ParamValue.Addr(cfg.treasuryAddress),
            ProposalParams.BaseFeeFloorKey -> ParamValue.Number(cfg.baseFeeFloor)
          )
        )
      ),
      Ecip(1112) -> ScheduledProposal(
        olympiaActivation,
        ProposalParams(Map(ProposalParams.TreasuryAddressKey -> ParamValue.Addr(cfg.treasuryAddress)))
      ),
      Ecip(1122) -> ScheduledProposal(olympiaActivation, ecip1122Params),
      Eip(4844) -> ScheduledProposal(tsActivation(ft.cancunTimestamp)),
      Custom("bpo", 1) -> ScheduledProposal(
        tsActivation(ft.bpo1Timestamp),
        ProposalParams(
          Map(
            ProposalParams.BlobTargetKey -> ParamValue.Number(BlobGasUtils.BPO1_TARGET_BLOB_GAS),
            ProposalParams.BlobMaxKey -> ParamValue.Number(BlobGasUtils.BPO1_MAX_BLOB_GAS)
          )
        )
      ),
      Custom("bpo", 2) -> ScheduledProposal(
        tsActivation(ft.bpo2Timestamp),
        ProposalParams(
          Map(
            ProposalParams.BlobTargetKey -> ParamValue.Number(BlobGasUtils.BPO2_TARGET_BLOB_GAS),
            ProposalParams.BlobMaxKey -> ParamValue.Number(BlobGasUtils.BPO2_MAX_BLOB_GAS)
          )
        )
      ),
      Custom("merge", 0) -> ScheduledProposal(ttdActivation)
    )

    // EVM proposal activations (Row 5.3b) — the same source of truth `EvmConfig.forBlock(block, BlockchainConfigForEvm)`
    // uses for block dispatch. `blockEvmActivations` supplies the BLOCK axis (network-aware; ETH's Shanghai+/Cancun/Osaka
    // proposals are absent there); the ETH timestamp overlay below supplies those on the `ByTimestamp` axis, so a single
    // `schedule.isActive` in the 3-arg `forBlock` resolves every EVM proposal on the correct axis.
    val cfgEvm = BlockchainConfigForEvm(cfg)
    val evmBlockEntries: Map[ProposalId, ScheduledProposal] =
      EvmProposals.blockEvmActivations(cfgEvm).map { case (id, act) => id -> ScheduledProposal(act) }
    val evmTimestampEntries: Map[ProposalId, ScheduledProposal] =
      if cfg.networkType == NetworkType.ETH then
        Map(
          // Shanghai — PUSH0 (EIP-3855), warm COINBASE (EIP-3651), initcode metering FLAG (EIP-3860). EIP-6049 is NOT
          // flagged on ETH (only ETC Spiral set it), so it stays absent -> Never here, preserving byte-identity.
          Eip(3855) -> ScheduledProposal(tsActivation(ft.shanghaiTimestamp)),
          Eip(3651) -> ScheduledProposal(tsActivation(ft.shanghaiTimestamp)),
          Custom("eip3860-metering", 0) -> ScheduledProposal(tsActivation(ft.shanghaiTimestamp)),
          // Cancun — BLOBBASEFEE (EIP-7516), transient storage (EIP-1153), MCOPY (EIP-5656), SELFDESTRUCT-same-tx
          // (EIP-6780). BLOBHASH (EIP-4844) is already scheduled above at the same cancun timestamp.
          Eip(7516) -> ScheduledProposal(tsActivation(ft.cancunTimestamp)),
          Eip(1153) -> ScheduledProposal(tsActivation(ft.cancunTimestamp)),
          Eip(5656) -> ScheduledProposal(tsActivation(ft.cancunTimestamp)),
          Eip(6780) -> ScheduledProposal(tsActivation(ft.cancunTimestamp)),
          // Osaka — CLZ (EIP-7939).
          Eip(7939) -> ScheduledProposal(tsActivation(ft.osakaTimestamp))
        )
      else Map.empty

    // Fork-id-only registry markers (Row 5.8a) — block forks that carry NO EVM/reward/base-fee delta but must be
    // enumerated for the EIP-2124 fork-id (Row 5.8b migrates `ForkId.gatherBlockForks` onto `.entries`). Their ids are
    // absent from `EvmProposals.evmApplicationOrder`/`byId`, the allowlist the `EvmConfig.forBlock` fold iterates, so
    // they are structurally invisible to `deriveEvm`/the config fold — no byte-identity risk to the EVM config or the
    // RPC surface. PoW/ETC + neutral half (forge); the PoS/ETH half (petersburg, glaciers, merge-netsplit, DAO) is
    // added by beacon reusing this exact `byBlockIfReal` layer.
    val forkIdBlockMarkers: Map[ProposalId, ScheduledProposal] = Map(
      Ecip(1099) -> ScheduledProposal(byBlockIfReal(fb.ecip1099BlockNumber)), // Thanos / ETChash (ETC)
      Custom("ecip1010", 1) -> ScheduledProposal(byBlockIfReal(fb.difficultyBombPauseBlockNumber)), // Die Hard pause
      Custom("ecip1010", 2) -> ScheduledProposal(byBlockIfReal(fb.difficultyBombContinueBlockNumber)), // bomb continue
      Ecip(1041) -> ScheduledProposal(byBlockIfReal(fb.difficultyBombRemovalBlockNumber)), // defuse bomb (ETC)
      Eip(106) -> ScheduledProposal(byBlockIfReal(fb.eip106BlockNumber)), // dead everywhere (sentinel -> Never)
      Eip(155) -> ScheduledProposal(byBlockIfReal(fb.eip155BlockNumber)) // replay protection (ETC + ETH)
    )

    // DAO (EIP-779) fork-id-list entry — reproduces `ForkId.gatherBlockForks`'s predicate exactly: contributes its
    // block ONLY when `includeOnForkIdList == true` (ETH), else `Never` (ETC/Mordor set it false). This is the fork-id
    // registry entry alone; the DAO irregular-state-change validators are a separate mechanism, out of Row 5.8 scope.
    val daoForkIdActivation: ForkActivation =
      cfg.daoForkConfig match
        case Some(dao) if dao.includeOnForkIdList => byBlockIfReal(dao.forkBlockNumber)
        case _                                    => ForkActivation.Never

    // PoS/ETH fork-id-only registry markers (Row 5.8a, beacon) — block forks with NO EVM/reward/base-fee delta, added
    // for the EIP-2124 fork-id (Row 5.8b migrates `ForkId.gatherBlockForks` onto `.entries`). Same `byBlockIfReal` layer
    // as the PoW half above; ids kept OUT of `EvmProposals.evmApplicationOrder`/`byId`, so they are structurally
    // invisible to `deriveEvm`/the config fold — no byte-identity risk to the EVM config or the RPC surface.
    val forkIdBlockMarkersPos: Map[ProposalId, ScheduledProposal] = Map(
      Eip(1716) -> ScheduledProposal(byBlockIfReal(fb.petersburgBlockNumber)), // Petersburg (ETH; == constantinople)
      Eip(2384) -> ScheduledProposal(byBlockIfReal(fb.muirGlacierBlockNumber)), // Muir Glacier bomb delay (ETH)
      Eip(4345) -> ScheduledProposal(byBlockIfReal(fb.arrowGlacierBlockNumber)), // Arrow Glacier bomb delay (ETH)
      Eip(5133) -> ScheduledProposal(byBlockIfReal(fb.grayGlacierBlockNumber)), // Gray Glacier bomb delay (ETH)
      Custom("merge-netsplit", 0) -> ScheduledProposal(byBlockIfReal(fb.mergeNetsplitBlockNumber)), // Sepolia netsplit
      Eip(779) -> ScheduledProposal(daoForkIdActivation) // DAO fork-id-list entry (includeOnForkIdList-gated)
    )

    ForkSchedule(
      rewardAndPolicyEntries ++ evmBlockEntries ++ evmTimestampEntries ++ forkIdBlockMarkers ++ forkIdBlockMarkersPos
    )

  // scalastyle:off method.length
  def fromRawConfig(blockchainConfig: TypesafeConfig): BlockchainConfig =
    val powTargetTime: Option[Long] =
      ConfigUtils
        .getOptionalValue(blockchainConfig, _.getDuration, "pow-target-time")
        .map(_.getSeconds)
    val frontierBlockNumber: BigInt = BigInt(blockchainConfig.getString("frontier-block-number"))
    val homesteadBlockNumber: BigInt = BigInt(blockchainConfig.getString("homestead-block-number"))
    // Foreign/dead fork-name fields (Row 5.5a Stage 1): parse via the safe `Try(...).getOrElse(sentinel)` pattern so
    // Stage 2 can remove the foreign HOCON keys from each network's conf without a `ConfigException.Missing` at startup.
    // Sentinel is `OlympiaPendingSentinel` (10^18), NOT `Long.MaxValue` — the confs declare 10^18 literally today, and
    // `MilestoneLog.formatMilestones` filters only `== Long.MaxValue`, so 10^18 preserves byte-identity in logs too.
    // No-op at this stage: the confs still declare every key, so no default is exercised.
    val eip106BlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("eip106-block-number"))).getOrElse(OlympiaPendingSentinel)
    val eip150BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip150-block-number"))
    val eip155BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip155-block-number"))
    val eip160BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip160-block-number"))
    val eip161BlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("eip161-block-number"))).getOrElse(OlympiaPendingSentinel)
    val byzantiumBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("byzantium-block-number"))).getOrElse(OlympiaPendingSentinel)
    val constantinopleBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("constantinople-block-number"))).getOrElse(OlympiaPendingSentinel)
    val istanbulBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("istanbul-block-number"))).getOrElse(OlympiaPendingSentinel)

    val atlantisBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("atlantis-block-number"))).getOrElse(OlympiaPendingSentinel)
    val aghartaBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("agharta-block-number"))).getOrElse(OlympiaPendingSentinel)
    val phoenixBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("phoenix-block-number"))).getOrElse(OlympiaPendingSentinel)
    val petersburgBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("petersburg-block-number"))).getOrElse(OlympiaPendingSentinel)
    val maxCodeSize: Option[BigInt] = Try(BigInt(blockchainConfig.getString("max-code-size"))).toOption
    val difficultyBombPauseBlockNumber: BigInt = BigInt(
      blockchainConfig.getString("difficulty-bomb-pause-block-number")
    )
    val difficultyBombContinueBlockNumber: BigInt = BigInt(
      blockchainConfig.getString("difficulty-bomb-continue-block-number")
    )
    val difficultyBombRemovalBlockNumber: BigInt = BigInt(
      blockchainConfig.getString("difficulty-bomb-removal-block-number")
    )
    val customGenesisFileOpt: Option[String] = Try(blockchainConfig.getString("custom-genesis-file")).toOption
    val customGenesisJsonOpt: Option[String] = Try(
      blockchainConfig.getObject("custom-genesis-file").render(ConfigRenderOptions.concise())
    ).toOption

    val daoForkConfig = Try(blockchainConfig.getConfig("dao")).toOption.map(DaoForkConfig(_))
    val accountStartNonce: UInt256 = UInt256(BigInt(blockchainConfig.getString("account-start-nonce")))

    val chainId: ChainId =
      val s = blockchainConfig.getString("chain-id")
      ChainId(parseHexOrDecNumber(s))

    val networkId: Long = Try(blockchainConfig.getLong("network-id")).getOrElse {
      Try(BigInt(blockchainConfig.getString("network-id")).toLong).getOrElse(1L)
    }

    val monetaryPolicyConfig = MonetaryPolicyConfig(blockchainConfig.getConfig("monetary-policy"))

    val gasTieBreaker: Boolean = blockchainConfig.getBoolean("gas-tie-breaker")

    val ethCompatibleStorage: Boolean = blockchainConfig.getBoolean("eth-compatible-storage")

    val bootstrapNodes: Set[String] = blockchainConfig.getStringList("bootstrap-nodes").asScala.toSet
    val dnsDiscoveryDomains: Seq[String] = ConfigUtils
      .getOptionalValue(blockchainConfig, _.getStringList, "dns-discovery-domains")
      .map(_.asScala.toSeq)
      .getOrElse(Seq.empty)
    val allowedMinersPublicKeys = readPubKeySet(blockchainConfig, "allowed-miners")

    // Row 5.5a Stage 1 (continued): same safe-default treatment for the remaining foreign fork-name fields.
    val ecip1099BlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("ecip1099-block-number"))).getOrElse(OlympiaPendingSentinel)
    val muirGlacierBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("muir-glacier-block-number"))).getOrElse(OlympiaPendingSentinel)
    val magnetoBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("magneto-block-number"))).getOrElse(OlympiaPendingSentinel)
    val berlinBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("berlin-block-number"))).getOrElse(OlympiaPendingSentinel)
    val mystiqueBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("mystique-block-number"))).getOrElse(OlympiaPendingSentinel)
    val spiralBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("spiral-block-number"))).getOrElse(OlympiaPendingSentinel)
    val eip1559BlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("eip1559-block-number"))).getOrElse(BigInt(Long.MaxValue))
    val arrowGlacierBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("arrow-glacier-block-number"))).getOrElse(BigInt(Long.MaxValue))
    val grayGlacierBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("gray-glacier-block-number"))).getOrElse(BigInt(Long.MaxValue))
    val mergeNetsplitBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("merge-netsplit-block-number"))).getOrElse(BigInt(Long.MaxValue))
    val spiralGasTarget: Option[BigInt] =
      Try(BigInt(blockchainConfig.getString("spiral-gas-target"))).toOption
    val olympiaGasTarget: Option[BigInt] =
      Try(BigInt(blockchainConfig.getString("olympia-gas-target"))).toOption

    val treasuryAddress: Address =
      Try(Address(blockchainConfig.getString("treasury-address"))).getOrElse(Address(0))

    // EIP-6110: default to the mainnet beacon deposit contract when unspecified.
    val depositContractAddress: Address =
      Try(Address(blockchainConfig.getString("deposit-contract-address")))
        .getOrElse(Address("0x00000000219ab540356cBB839Cbe05303d7705Fa"))

    val baseFeeFloor: BigInt =
      Try(BigInt(blockchainConfig.getString("base-fee-floor"))).getOrElse(BigInt(0))

    val minTip: BigInt =
      Try(BigInt(blockchainConfig.getString("min-tip"))).getOrElse(BigInt(1000000000))

    val networkType: NetworkType =
      Try(NetworkType.fromString(blockchainConfig.getString("network-type"))).getOrElse(NetworkType.ETC)

    val terminalTotalDifficulty: Option[BigInt] =
      Try(BigInt(blockchainConfig.getString("terminal-total-difficulty"))).toOption

    val forkTimestamps: ForkTimestamps = ForkTimestamps(
      shanghaiTimestamp = Try(blockchainConfig.getLong("shanghai-timestamp")).toOption,
      cancunTimestamp = Try(blockchainConfig.getLong("cancun-timestamp")).toOption,
      pragueTimestamp = Try(blockchainConfig.getLong("prague-timestamp")).toOption,
      osakaTimestamp = Try(blockchainConfig.getLong("osaka-timestamp")).toOption,
      bpo1Timestamp = Try(blockchainConfig.getLong("bpo1-timestamp")).toOption,
      bpo2Timestamp = Try(blockchainConfig.getLong("bpo2-timestamp")).toOption
    )

    val messConfig: MESSConfig = Try {
      val messConf = blockchainConfig.getConfig("mess")
      MESSConfig(
        enabled = Try(messConf.getBoolean("enabled")).getOrElse(false),
        activationBlock = Try(BigInt(messConf.getString("ecbp1100-block-number"))).toOption.map(BlockNumber.apply),
        deactivationBlock =
          Try(BigInt(messConf.getString("ecbp1100-deactivate-block-number"))).toOption.map(BlockNumber.apply),
        reactivationBlock = Try(BigInt(messConf.getString("ecbp1100-reactivate-block-number"))).toOption
          .orElse(Try(BigInt(blockchainConfig.getString("eip1559-block-number"))).toOption)
          .map(BlockNumber.apply)
      )
    }.getOrElse(MESSConfig())

    BlockchainConfig(
      powTargetTime = powTargetTime,
      forkBlockNumbers = ForkBlockNumbers(
        frontierBlockNumber = BlockNumber(frontierBlockNumber),
        homesteadBlockNumber = BlockNumber(homesteadBlockNumber),
        eip106BlockNumber = BlockNumber(eip106BlockNumber),
        eip150BlockNumber = BlockNumber(eip150BlockNumber),
        eip155BlockNumber = BlockNumber(eip155BlockNumber),
        eip160BlockNumber = BlockNumber(eip160BlockNumber),
        eip161BlockNumber = BlockNumber(eip161BlockNumber),
        difficultyBombPauseBlockNumber = BlockNumber(difficultyBombPauseBlockNumber),
        difficultyBombContinueBlockNumber = BlockNumber(difficultyBombContinueBlockNumber),
        difficultyBombRemovalBlockNumber = BlockNumber(difficultyBombRemovalBlockNumber),
        byzantiumBlockNumber = BlockNumber(byzantiumBlockNumber),
        constantinopleBlockNumber = BlockNumber(constantinopleBlockNumber),
        istanbulBlockNumber = BlockNumber(istanbulBlockNumber),
        atlantisBlockNumber = BlockNumber(atlantisBlockNumber),
        aghartaBlockNumber = BlockNumber(aghartaBlockNumber),
        phoenixBlockNumber = BlockNumber(phoenixBlockNumber),
        petersburgBlockNumber = BlockNumber(petersburgBlockNumber),
        ecip1099BlockNumber = BlockNumber(ecip1099BlockNumber),
        muirGlacierBlockNumber = BlockNumber(muirGlacierBlockNumber),
        magnetoBlockNumber = BlockNumber(magnetoBlockNumber),
        berlinBlockNumber = BlockNumber(berlinBlockNumber),
        mystiqueBlockNumber = BlockNumber(mystiqueBlockNumber),
        spiralBlockNumber = BlockNumber(spiralBlockNumber),
        eip1559BlockNumber = BlockNumber(eip1559BlockNumber),
        arrowGlacierBlockNumber = BlockNumber(arrowGlacierBlockNumber),
        grayGlacierBlockNumber = BlockNumber(grayGlacierBlockNumber),
        mergeNetsplitBlockNumber = BlockNumber(mergeNetsplitBlockNumber),
        spiralGasTarget = spiralGasTarget,
        olympiaGasTarget = olympiaGasTarget
      ),
      maxCodeSize = maxCodeSize,
      customGenesisFileOpt = customGenesisFileOpt,
      customGenesisJsonOpt = customGenesisJsonOpt,
      daoForkConfig = daoForkConfig,
      accountStartNonce = accountStartNonce,
      chainId = chainId,
      networkId = networkId,
      monetaryPolicyConfig = monetaryPolicyConfig,
      gasTieBreaker = gasTieBreaker,
      ethCompatibleStorage = ethCompatibleStorage,
      bootstrapNodes = bootstrapNodes,
      dnsDiscoveryDomains = dnsDiscoveryDomains,
      allowedMinersPublicKeys = allowedMinersPublicKeys,
      messConfig = messConfig,
      treasuryAddress = treasuryAddress,
      depositContractAddress = depositContractAddress,
      baseFeeFloor = baseFeeFloor,
      minTip = minTip,
      networkType = networkType,
      terminalTotalDifficulty = terminalTotalDifficulty,
      forkTimestamps = forkTimestamps
    )
  // scalastyle:on method.length
  private def readPubKeySet(blockchainConfig: TypesafeConfig, path: String): Set[ByteString] =
    val keys: Seq[String] = ConfigUtils
      .getOptionalValue(blockchainConfig, _.getStringList, path)
      .map(_.asScala.toSeq)
      .getOrElse(Nil)
    keys.map(ByteStringUtils.string2hash).toSet
