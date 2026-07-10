package com.chipprbots.ethereum.vm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps

import Fixtures.blockchainConfig
import FeeScheduleFields.fields

/** Tests for EvmConfig.forBlock timestamp-based fork dispatch (ETH/Sepolia path).
  *
  * The 3-arg forBlock overload applies Shanghai/Cancun/Prague/Osaka overrides on top of the block-based config. This
  * spec guards against regressions in that dispatch, including CLZ (0x1e, EIP-7939) which has no other unit test.
  *
  * ETC chain sanity: OlympiaConfigBuilder uses EtcOlympiaOpCodes — no BLOBHASH/BLOBBASEFEE (ETH-only EIPs), CLZ present
  * per ECIP-1121.
  */
class EvmConfigTimestampForkSpec extends AnyFlatSpec with Matchers:

  private val ShanghaiTs: Long = 1_000L
  private val CancunTs: Long = 2_000L
  private val PragueTs: Long = 3_000L
  private val OsakaTs: Long = 4_000L

  // The base block must be POST-London: the ETH timestamp forks are scheduled on networkType = ETH and layer on the
  // block-active London base (BASEFEE/EIP-3198 is block-gated from London, not carried by the ts overlays).
  private val PostLondonBlock: BlockNumber = BlockNumber(20_000_000)
  private val baseEthConfig = Config.blockchains
    .blockchains("eth")
    .copy(
      forkTimestamps = ForkTimestamps(
        shanghaiTimestamp = Some(ShanghaiTs),
        cancunTimestamp = Some(CancunTs),
        pragueTimestamp = Some(PragueTs),
        osakaTimestamp = Some(OsakaTs)
      )
    )

  private def evmAt(ts: Long): EvmConfig = EvmConfig.forBlock(PostLondonBlock, Timestamp(ts), baseEthConfig)

  private val configEtcOlympia: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)

  // ETH-shaped block-number config: spiral > olympia ⇒ etcForksDisabled = true ⇒ the block-number base at
  // block 0 is LondonConfigBuilder (the ETH London base), not ETC's Olympia path. This isolates the ETH London
  // opcode set (EthLondonOpCodes) so the BASEFEE-at-London assertion cannot accidentally pass via the ETC config
  // (which also carries BASEFEE, but at ETC Olympia via EtcOlympiaOpCodes). Mirrors eth/sepolia-chain.conf where
  // olympia-block-number = London height and spiral-block-number = 1e18.
  private val ethLondonBaseCfg: BlockchainConfigForEvm = BlockchainConfigForEvm(
    frontierBlockNumber = BlockNumber(0),
    homesteadBlockNumber = BlockNumber(0),
    eip150BlockNumber = BlockNumber(0),
    eip160BlockNumber = BlockNumber(0),
    eip161BlockNumber = BlockNumber(0),
    byzantiumBlockNumber = BlockNumber(0),
    constantinopleBlockNumber = BlockNumber(0),
    istanbulBlockNumber = BlockNumber(0),
    maxCodeSize = None,
    accountStartNonce = 0,
    atlantisBlockNumber = BlockNumber(Long.MaxValue),
    aghartaBlockNumber = BlockNumber(Long.MaxValue),
    petersburgBlockNumber = BlockNumber(0),
    phoenixBlockNumber = BlockNumber(Long.MaxValue),
    magnetoBlockNumber = BlockNumber(Long.MaxValue),
    berlinBlockNumber = BlockNumber(0),
    mystiqueBlockNumber = BlockNumber(Long.MaxValue),
    spiralBlockNumber = BlockNumber(Long.MaxValue),
    olympiaBlockNumber = BlockNumber(0),
    chainId = ChainId(0x01),
    isEthereum = true
  )

  // ETH London base (block-number dispatch, no timestamp overlay applied).
  private val evmEthLondon: EvmConfig = EvmConfig.forBlock(BlockNumber(0), ethLondonBaseCfg)

  // ts = 0 resolves to the ETH London base (block 20M, no timestamp fork): eip3860 metering off, no PUSH0.
  "EvmConfig.forBlock timestamp dispatch when pre-Shanghai (ts = 0)" should "have eip3860Enabled = false" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    evmAt(0L).eip3860Enabled shouldBe false
  }
  it should "not include PUSH0 (0x5F)" taggedAs (UnitTest, ConsensusTest) in {
    evmAt(0L).byteToOpCode.get(0x5f.toByte) shouldBe None
  }

  "EvmConfig.forBlock timestamp dispatch when at Shanghai (ts = ShanghaiTs)" should "have eip3860Enabled = true" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    evmAt(ShanghaiTs).eip3860Enabled shouldBe true
  }
  it should "include PUSH0 (0x5F) per EIP-3855" taggedAs (UnitTest, ConsensusTest) in {
    evmAt(ShanghaiTs).byteToOpCode.get(0x5f.toByte) shouldBe Some(PUSH0)
  }
  // BEACON-BASEFEE-02: BASEFEE (EIP-3198) is present from London onward on ETH; must survive the Shanghai overlay.
  it should "include BASEFEE (0x48) per EIP-3198 (carried from London)" taggedAs (UnitTest, ConsensusTest) in {
    evmAt(ShanghaiTs).byteToOpCode.get(0x48.toByte) shouldBe Some(BASEFEE)
  }
  // BEACON-CLZ-01: CLZ (EIP-7939) must NOT appear before Osaka.
  it should "not include CLZ (0x1E) — EIP-7939 activates only at Osaka" taggedAs (UnitTest, ConsensusTest) in {
    evmAt(ShanghaiTs).byteToOpCode.get(0x1e.toByte) shouldBe None
  }

  "EvmConfig.forBlock timestamp dispatch when at Cancun (ts = CancunTs)" should "include BLOBHASH (0x49) per EIP-4844" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    evmAt(CancunTs).byteToOpCode.get(0x49.toByte) shouldBe Some(BLOBHASH)
  }
  it should "include BLOBBASEFEE (0x4A) per EIP-7516" taggedAs (UnitTest, ConsensusTest) in {
    evmAt(CancunTs).byteToOpCode.get(0x4a.toByte) shouldBe Some(BLOBBASEFEE)
  }
  it should "include BASEFEE (0x48) per EIP-3198" taggedAs (UnitTest, ConsensusTest) in {
    evmAt(CancunTs).byteToOpCode.get(0x48.toByte) shouldBe Some(BASEFEE)
  }
  it should "have eip6780Enabled = true per EIP-6780" taggedAs (UnitTest, ConsensusTest) in {
    evmAt(CancunTs).eip6780Enabled shouldBe true
  }
  // BEACON-CLZ-01 regression guard: CLZ must be absent at Cancun (go-ethereum adds it only at Osaka).
  it should "not include CLZ (0x1E) — EIP-7939 activates only at Osaka" taggedAs (UnitTest, ConsensusTest) in {
    evmAt(CancunTs).byteToOpCode.get(0x1e.toByte) shouldBe None
  }

  "EvmConfig.forBlock timestamp dispatch when at Prague (ts = PragueTs)" should "use EthPragueFeeSchedule for EIP-7623 calldata floor" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    fields(evmAt(PragueTs).feeSchedule) shouldBe fields(new FeeSchedule.EthPragueFeeSchedule)
  }
  // BEACON-CLZ-01 regression guard: Prague adds EIP-7702 (a tx type, no EVM opcode) — opcode set == Cancun, no CLZ.
  it should "not include CLZ (0x1E) — Prague adds no new EVM opcode over Cancun" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    evmAt(PragueTs).byteToOpCode.get(0x1e.toByte) shouldBe None
  }
  it should "still include BASEFEE (0x48)" taggedAs (UnitTest, ConsensusTest) in {
    evmAt(PragueTs).byteToOpCode.get(0x48.toByte) shouldBe Some(BASEFEE)
  }

  "EvmConfig.forBlock timestamp dispatch when at Osaka (ts = OsakaTs)" should "include CLZ (0x1E) per EIP-7939" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    evmAt(OsakaTs).byteToOpCode.get(0x1e.toByte) shouldBe Some(CLZ)
  }
  it should "still include BASEFEE (0x48)" taggedAs (UnitTest, ConsensusTest) in {
    evmAt(OsakaTs).byteToOpCode.get(0x48.toByte) shouldBe Some(BASEFEE)
  }
  it should "use EthOsakaFeeSchedule" taggedAs (UnitTest, ConsensusTest) in {
    fields(evmAt(OsakaTs).feeSchedule) shouldBe fields(new FeeSchedule.EthOsakaFeeSchedule)
  }

  // BEACON-BASEFEE-02: the ETH London base opcode list (EthLondonOpCodes) must carry BASEFEE. Uses an ETH-shaped
  // config (spiral > olympia) so this exercises LondonConfigBuilder, NOT the ETC Olympia path.
  "ETH London base (block-number dispatch, etcForksDisabled) when resolving at the London base (no timestamp overlay)" should "include BASEFEE (0x48) per EIP-3198" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    evmEthLondon.byteToOpCode.get(0x48.toByte) shouldBe Some(BASEFEE)
  }
  it should "not include PUSH0 (0x5F) — EIP-3855 activates at Shanghai" taggedAs (UnitTest, ConsensusTest) in {
    evmEthLondon.byteToOpCode.get(0x5f.toByte) shouldBe None
  }
  it should "not include CLZ (0x1E) — EIP-7939 activates only at Osaka" taggedAs (UnitTest, ConsensusTest) in {
    evmEthLondon.byteToOpCode.get(0x1e.toByte) shouldBe None
  }
  it should "use EthLondonOpCodes (ETH London base list)" taggedAs (UnitTest, ConsensusTest) in {
    evmEthLondon.opCodeList.opCodes.toSet shouldBe EvmConfig.EthLondonOpCodes.opCodes.toSet
  }

  // BEACON-CLZ-01: Osaka = Cancun + exactly {CLZ}. Guards against CLZ drifting into Cancun again.
  "ETH per-fork opcode-set membership (exact guards) when comparing the ETH opcode lists" should "have ETH Osaka == ETH Cancun plus exactly CLZ" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    EvmConfig.EthOsakaOpCodes.opCodes.toSet shouldBe (EvmConfig.EthCancunOpCodes.opCodes.toSet + CLZ)
  }
  it should "have ETH Cancun contain the blob/transient/mcopy set and BASEFEE but not CLZ" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val cancun = EvmConfig.EthCancunOpCodes.opCodes.toSet
    (cancun should contain).allOf(BASEFEE, BLOBHASH, BLOBBASEFEE, TLOAD, TSTORE, MCOPY, PUSH0)
    cancun should not contain CLZ
  }
  it should "have ETH London contain BASEFEE but not PUSH0 or CLZ" taggedAs (UnitTest, ConsensusTest) in {
    val london = EvmConfig.EthLondonOpCodes.opCodes.toSet
    london should contain(BASEFEE)
    london should not contain PUSH0
    london should not contain CLZ
  }
  it should "have ETH Shanghai contain BASEFEE and PUSH0 but not CLZ" taggedAs (UnitTest, ConsensusTest) in {
    val shanghai = EvmConfig.EthShanghaiOpCodes.opCodes.toSet
    (shanghai should contain).allOf(BASEFEE, PUSH0)
    shanghai should not contain CLZ
  }
  // ETC invariant (untouched by this change) — EtcOlympiaOpCodes keeps CLZ+BASEFEE, excludes ETH-only blob opcodes.
  it should "have ETC Olympia keep CLZ and BASEFEE but exclude BLOBHASH/BLOBBASEFEE" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val etcOlympia = EvmConfig.EtcOlympiaOpCodes.opCodes.toSet
    (etcOlympia should contain).allOf(CLZ, BASEFEE)
    etcOlympia should not contain BLOBHASH
    etcOlympia should not contain BLOBBASEFEE
  }

  "ETC Olympia (block-based, OlympiaConfigBuilder) when using ETC blockchainConfig at Olympia" should "not include BLOBHASH (0x49) — EIP-4844 is ETH-only" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    configEtcOlympia.byteToOpCode.get(0x49.toByte) shouldBe None
  }
  it should "not include BLOBBASEFEE (0x4A) — EIP-7516 is ETH-only" taggedAs (UnitTest, ConsensusTest) in {
    configEtcOlympia.byteToOpCode.get(0x4a.toByte) shouldBe None
  }
  it should "include CLZ (0x1E) per ECIP-1121" taggedAs (UnitTest, ConsensusTest) in {
    configEtcOlympia.byteToOpCode.get(0x1e.toByte) shouldBe Some(CLZ)
  }
