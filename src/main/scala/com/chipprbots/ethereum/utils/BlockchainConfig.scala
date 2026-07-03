package com.chipprbots.ethereum.utils

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*
import scala.util.Try

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigRenderOptions

import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TotalDifficulty
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.utils.NumericUtils.*

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
    olympiaBlockNumber: BlockNumber,
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
  def all: List[BigInt] = this.productIterator.toList.collect { case i: BigInt =>
    i
  }

  /** Returns the convergence target that
    * [[com.chipprbots.ethereum.consensus.blocks.BlockGeneratorSkeleton.calculateGasLimit]] should aim for at the given
    * block number, based on the fork-embedded gas schedule. None means no fork schedule opinion for this era — caller
    * falls back to miningConfig.gasLimitTarget.
    */
  def gasLimitAdjustmentStartAt(blockNumber: BlockNumber): Option[BigInt] =
    if blockNumber >= olympiaBlockNumber then olympiaGasTarget
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
    olympiaBlockNumber = BlockNumber(Long.MaxValue),
    mergeNetsplitBlockNumber = BlockNumber(Long.MaxValue)
  )

object BlockchainConfig:

  // scalastyle:off method.length
  def fromRawConfig(blockchainConfig: TypesafeConfig): BlockchainConfig =
    val powTargetTime: Option[Long] =
      ConfigUtils
        .getOptionalValue(blockchainConfig, _.getDuration, "pow-target-time")
        .map(_.getSeconds)
    val frontierBlockNumber: BigInt = BigInt(blockchainConfig.getString("frontier-block-number"))
    val homesteadBlockNumber: BigInt = BigInt(blockchainConfig.getString("homestead-block-number"))
    val eip106BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip106-block-number"))
    val eip150BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip150-block-number"))
    val eip155BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip155-block-number"))
    val eip160BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip160-block-number"))
    val eip161BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip161-block-number"))
    val byzantiumBlockNumber: BigInt = BigInt(blockchainConfig.getString("byzantium-block-number"))
    val constantinopleBlockNumber: BigInt = BigInt(blockchainConfig.getString("constantinople-block-number"))
    val istanbulBlockNumber: BigInt = BigInt(blockchainConfig.getString("istanbul-block-number"))

    val atlantisBlockNumber: BigInt = BigInt(blockchainConfig.getString("atlantis-block-number"))
    val aghartaBlockNumber: BigInt = BigInt(blockchainConfig.getString("agharta-block-number"))
    val phoenixBlockNumber: BigInt = BigInt(blockchainConfig.getString("phoenix-block-number"))
    val petersburgBlockNumber: BigInt = BigInt(blockchainConfig.getString("petersburg-block-number"))
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

    val ecip1099BlockNumber: BigInt = BigInt(blockchainConfig.getString("ecip1099-block-number"))
    val muirGlacierBlockNumber: BigInt = BigInt(blockchainConfig.getString("muir-glacier-block-number"))
    val magnetoBlockNumber: BigInt = BigInt(blockchainConfig.getString("magneto-block-number"))
    val berlinBlockNumber: BigInt = BigInt(blockchainConfig.getString("berlin-block-number"))
    val mystiqueBlockNumber: BigInt = BigInt(blockchainConfig.getString("mystique-block-number"))
    val spiralBlockNumber: BigInt = BigInt(blockchainConfig.getString("spiral-block-number"))
    val olympiaBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("olympia-block-number"))).getOrElse(BigInt(Long.MaxValue))
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
      Try(BigInt(blockchainConfig.getString("min-tip"))).getOrElse(BigInt(1))

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
          .orElse(Try(BigInt(blockchainConfig.getString("olympia-block-number"))).toOption)
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
        olympiaBlockNumber = BlockNumber(olympiaBlockNumber),
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
