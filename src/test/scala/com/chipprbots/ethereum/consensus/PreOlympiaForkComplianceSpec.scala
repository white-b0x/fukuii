package com.chipprbots.ethereum.consensus

import org.scalatest.ParallelTestExecution
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks
import com.chipprbots.ethereum.vm.EvmConfig
import com.chipprbots.ethereum.vm.FeeSchedule
import com.chipprbots.ethereum.vm.FeeScheduleFields.fields
import com.chipprbots.ethereum.vm.PUSH0

// scalastyle:off magic.number
/** Verifies that each pre-Olympia ETC fork selects the correct EVM configuration (fee schedule, opcode list, and EIP
  * feature flags) via EvmConfig.forBlock().
  *
  * Extends the 1-test EvmConfigEtcForkSelectionSpec to cover all forks.
  *
  * Reference: Besu ClassicProtocolSpecsTest (15 tests validating fork dispatch)
  */
class PreOlympiaForkComplianceSpec extends AnyFlatSpec with Matchers with ParallelTestExecution:

  /** Helper: create a BlockchainConfigForEvm with all forks at Long.MaxValue (inactive), then selectively activate
    * specific forks.
    */
  private def cfgWith(overrides: BlockchainConfigForEvm => BlockchainConfigForEvm): BlockchainConfigForEvm =
    overrides(
      BlockchainConfigForEvm(
        frontierBlockNumber = BlockNumber(Long.MaxValue),
        homesteadBlockNumber = BlockNumber(Long.MaxValue),
        eip150BlockNumber = BlockNumber(Long.MaxValue),
        eip160BlockNumber = BlockNumber(Long.MaxValue),
        eip161BlockNumber = BlockNumber(Long.MaxValue),
        byzantiumBlockNumber = BlockNumber(Long.MaxValue),
        constantinopleBlockNumber = BlockNumber(Long.MaxValue),
        istanbulBlockNumber = BlockNumber(Long.MaxValue),
        maxCodeSize = None,
        accountStartNonce = 0,
        atlantisBlockNumber = BlockNumber(Long.MaxValue),
        aghartaBlockNumber = BlockNumber(Long.MaxValue),
        petersburgBlockNumber = BlockNumber(Long.MaxValue),
        phoenixBlockNumber = BlockNumber(Long.MaxValue),
        magnetoBlockNumber = BlockNumber(Long.MaxValue),
        berlinBlockNumber = BlockNumber(Long.MaxValue),
        mystiqueBlockNumber = BlockNumber(Long.MaxValue),
        spiralBlockNumber = BlockNumber(Long.MaxValue),
        eip1559BlockNumber = BlockNumber(Long.MaxValue),
        chainId = ChainId(0x3d)
      )
    )

  private case class ForkCase(
      label: String,
      blockNumber: BlockNumber,
      configFn: BlockchainConfigForEvm => BlockchainConfigForEvm,
      assertFn: EvmConfig => Unit
  )

  // ===== Fork Dispatch Table =====

  // Row 5.3b: `forBlock` DERIVES the config by folding active proposals, so assertions are on VALUES — the fee 41-field
  // tuple (`fields`) and the opcode `.toSet` — rather than the old `isInstanceOf`/`OpCodeList ==` representation. The
  // ladders are MONOTONIC (every pre-fork reached): the fold gates each proposal on its own block, so a lower fork left
  // at a sentinel would (correctly) drop its opcodes/fees, unlike the old unconditional builder chain.
  private val forkCases: Seq[ForkCase] = Seq(
    ForkCase(
      label = "derive the Frontier fee/opcodes for Frontier blocks",
      blockNumber = BlockNumber(0),
      configFn = _.copy(frontierBlockNumber = BlockNumber(0)),
      assertFn = evm =>
        fields(evm.feeSchedule) shouldBe fields(new FeeSchedule.FrontierFeeSchedule)
        evm.opCodeList.opCodes.toSet shouldBe EvmConfig.FrontierOpCodes.opCodes.toSet
    ),
    ForkCase(
      label = "derive the Homestead fee/opcodes for Homestead blocks",
      blockNumber = BlockNumber(10),
      configFn = _.copy(frontierBlockNumber = BlockNumber(0), homesteadBlockNumber = BlockNumber(10)),
      assertFn = evm =>
        fields(evm.feeSchedule) shouldBe fields(new FeeSchedule.HomesteadFeeSchedule)
        evm.opCodeList.opCodes.toSet shouldBe EvmConfig.HomesteadOpCodes.opCodes.toSet
        evm.exceptionalFailedCodeDeposit shouldBe true
    ),
    ForkCase(
      label = "derive the Atlantis fee/opcodes for Atlantis blocks",
      blockNumber = BlockNumber(100),
      configFn = _.copy(
        frontierBlockNumber = BlockNumber(0),
        homesteadBlockNumber = BlockNumber(10),
        eip150BlockNumber = BlockNumber(20),
        eip160BlockNumber = BlockNumber(30),
        atlantisBlockNumber = BlockNumber(100)
      ),
      assertFn = evm =>
        fields(evm.feeSchedule) shouldBe fields(new FeeSchedule.AtlantisFeeSchedule)
        evm.opCodeList.opCodes.toSet shouldBe EvmConfig.AtlantisOpCodes.opCodes.toSet
        evm.noEmptyAccounts shouldBe true
    ),
    ForkCase(
      label = "apply Atlantis (and ignore byzantium on the ETC path) at the same height",
      blockNumber = BlockNumber(0),
      configFn = _.copy(
        frontierBlockNumber = BlockNumber(0),
        homesteadBlockNumber = BlockNumber(0),
        eip150BlockNumber = BlockNumber(0),
        eip160BlockNumber = BlockNumber(0),
        byzantiumBlockNumber = BlockNumber(0),
        atlantisBlockNumber = BlockNumber(0)
      ),
      assertFn = evm =>
        fields(evm.feeSchedule) shouldBe fields(new FeeSchedule.AtlantisFeeSchedule)
        evm.opCodeList.opCodes.toSet shouldBe EvmConfig.AtlantisOpCodes.opCodes.toSet
    ),
    ForkCase(
      label = "derive the Agharta fee/opcodes for Agharta blocks",
      blockNumber = BlockNumber(100),
      configFn = _.copy(
        frontierBlockNumber = BlockNumber(0),
        homesteadBlockNumber = BlockNumber(1),
        eip150BlockNumber = BlockNumber(2),
        eip160BlockNumber = BlockNumber(3),
        atlantisBlockNumber = BlockNumber(10),
        aghartaBlockNumber = BlockNumber(100)
      ),
      assertFn = evm =>
        fields(evm.feeSchedule) shouldBe fields(new FeeSchedule.ConstantionopleFeeSchedule)
        evm.opCodeList.opCodes.toSet shouldBe EvmConfig.AghartaOpCodes.opCodes.toSet
    ),
    ForkCase(
      label = "derive the Phoenix fee/opcodes for Phoenix blocks",
      blockNumber = BlockNumber(100),
      configFn = _.copy(
        frontierBlockNumber = BlockNumber(0),
        homesteadBlockNumber = BlockNumber(1),
        eip150BlockNumber = BlockNumber(2),
        eip160BlockNumber = BlockNumber(3),
        atlantisBlockNumber = BlockNumber(10),
        aghartaBlockNumber = BlockNumber(20),
        phoenixBlockNumber = BlockNumber(100)
      ),
      assertFn = evm =>
        fields(evm.feeSchedule) shouldBe fields(new FeeSchedule.PhoenixFeeSchedule)
        evm.opCodeList.opCodes.toSet shouldBe EvmConfig.PhoenixOpCodes.opCodes.toSet
    ),
    ForkCase(
      label = "derive the Magneto fee/opcodes for Magneto blocks",
      blockNumber = BlockNumber(100),
      configFn = _.copy(
        frontierBlockNumber = BlockNumber(0),
        homesteadBlockNumber = BlockNumber(1),
        eip150BlockNumber = BlockNumber(2),
        eip160BlockNumber = BlockNumber(3),
        atlantisBlockNumber = BlockNumber(10),
        aghartaBlockNumber = BlockNumber(20),
        phoenixBlockNumber = BlockNumber(30),
        magnetoBlockNumber = BlockNumber(100)
      ),
      assertFn = evm =>
        fields(evm.feeSchedule) shouldBe fields(new FeeSchedule.MagnetoFeeSchedule)
        evm.opCodeList.opCodes.toSet shouldBe EvmConfig.MagnetoOpCodes.opCodes.toSet
    ),
    ForkCase(
      label = "derive the Mystique fee/opcodes for Mystique blocks",
      blockNumber = BlockNumber(100),
      configFn = _.copy(
        frontierBlockNumber = BlockNumber(0),
        homesteadBlockNumber = BlockNumber(1),
        eip150BlockNumber = BlockNumber(2),
        eip160BlockNumber = BlockNumber(3),
        atlantisBlockNumber = BlockNumber(10),
        aghartaBlockNumber = BlockNumber(20),
        phoenixBlockNumber = BlockNumber(30),
        magnetoBlockNumber = BlockNumber(40),
        mystiqueBlockNumber = BlockNumber(100)
      ),
      assertFn = evm =>
        fields(evm.feeSchedule) shouldBe fields(new FeeSchedule.MystiqueFeeSchedule)
        evm.eip3541Enabled shouldBe true
    ),
    ForkCase(
      label = "derive the Mystique fee with Spiral opcodes for Spiral blocks",
      blockNumber = BlockNumber(100),
      configFn = _.copy(
        frontierBlockNumber = BlockNumber(0),
        homesteadBlockNumber = BlockNumber(1),
        eip150BlockNumber = BlockNumber(2),
        eip160BlockNumber = BlockNumber(3),
        atlantisBlockNumber = BlockNumber(10),
        aghartaBlockNumber = BlockNumber(20),
        phoenixBlockNumber = BlockNumber(30),
        magnetoBlockNumber = BlockNumber(40),
        mystiqueBlockNumber = BlockNumber(50),
        spiralBlockNumber = BlockNumber(100)
      ),
      assertFn = evm =>
        // Spiral keeps the Mystique fee schedule (no new fee fields)
        fields(evm.feeSchedule) shouldBe fields(new FeeSchedule.MystiqueFeeSchedule)
        evm.opCodeList.opCodes.toSet shouldBe EvmConfig.SpiralOpCodes.opCodes.toSet
        evm.eip3651Enabled shouldBe true
        evm.eip3860Enabled shouldBe true
        evm.eip6049DeprecationEnabled shouldBe true
    )
  )

  forkCases.foreach { fc =>
    "Pre-Olympia fork compliance" should fc.label taggedAs (UnitTest, ConsensusTest) in {
      val cfg = cfgWith(fc.configFn)
      val evm = EvmConfig.forBlock(fc.blockNumber, cfg)
      fc.assertFn(evm)
    }
  }

  // ===== PUSH0 Gating at Spiral =====

  it should "not include PUSH0 opcode before Spiral" taggedAs (UnitTest, ConsensusTest) in {
    val cfg = cfgWith(
      _.copy(
        frontierBlockNumber = BlockNumber(0),
        atlantisBlockNumber = BlockNumber(10),
        aghartaBlockNumber = BlockNumber(20),
        phoenixBlockNumber = BlockNumber(30),
        magnetoBlockNumber = BlockNumber(40),
        mystiqueBlockNumber = BlockNumber(50),
        spiralBlockNumber = BlockNumber(Long.MaxValue) // Spiral not yet active
      )
    )
    val evm = EvmConfig.forBlock(BlockNumber(50), cfg)

    evm.opCodeList.byteToOpCode.get(PUSH0.code) shouldBe None
  }

  it should "include PUSH0 opcode at and after Spiral" taggedAs (UnitTest, ConsensusTest) in {
    val cfg = cfgWith(
      _.copy(
        frontierBlockNumber = BlockNumber(0),
        atlantisBlockNumber = BlockNumber(10),
        aghartaBlockNumber = BlockNumber(20),
        phoenixBlockNumber = BlockNumber(30),
        magnetoBlockNumber = BlockNumber(40),
        mystiqueBlockNumber = BlockNumber(50),
        spiralBlockNumber = BlockNumber(100)
      )
    )
    val evm = EvmConfig.forBlock(BlockNumber(100), cfg)

    evm.opCodeList.byteToOpCode.get(PUSH0.code) shouldBe Some(PUSH0)
  }

  // ===== EtcForks Enum Ordering =====

  it should "have EtcForks enum values in strictly increasing order" taggedAs (UnitTest, ConsensusTest) in {
    val forks = EtcForks.values.toList
    forks.sliding(2).foreach {
      case List(a, b) =>
        withClue(s"$a (${a.id}) should be less than $b (${b.id}): ") {
          a.id should be < b.id
        }
      case _ => ()
    }
  }

  // ===== EIP Feature Flag Helpers =====

  it should "report EIP-2929 enabled for Magneto+ (ETC) and Berlin+ (ETH)" taggedAs (UnitTest, ConsensusTest) in {
    BlockchainConfigForEvm.isEip2929Enabled(EtcForks.Phoenix, BlockchainConfigForEvm.EthForks.Istanbul) shouldBe false
    BlockchainConfigForEvm.isEip2929Enabled(EtcForks.Magneto, BlockchainConfigForEvm.EthForks.Istanbul) shouldBe true
    BlockchainConfigForEvm.isEip2929Enabled(EtcForks.Phoenix, BlockchainConfigForEvm.EthForks.Berlin) shouldBe true
  }

  it should "report EIP-3855 (PUSH0) enabled only at Spiral+" taggedAs (UnitTest, ConsensusTest) in {
    BlockchainConfigForEvm.isEip3855Enabled(EtcForks.Mystique) shouldBe false
    BlockchainConfigForEvm.isEip3855Enabled(EtcForks.Spiral) shouldBe true
  }
// scalastyle:on magic.number
