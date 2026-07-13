package com.chipprbots.ethereum.vm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

import FeeScheduleFields.fields

/** Batch 5 Row 5.3b acceptance proof — the byte-identity harness for the switch of `EvmConfig.forBlock` onto the
  * [[com.chipprbots.ethereum.vm.forks.EvmProposals]] fold.
  *
  * For every ETC block height (Frontier … Olympia) and every ETH timestamp fork (London base, Shanghai, Cancun, Prague,
  * Osaka), on both `networkType`s, it asserts the NEW folded `forBlock` returns an `EvmConfig` FIELD-IDENTICAL to the
  * old per-fork `*ConfigBuilder` bundle output:
  *   - opcode set — `opCodeList.opCodes.toSet` equal to the unchanged `OpCodes.*` bundle (representation-independent of
  *     list order, which the fold reorders);
  *   - fee schedule — the 41-field tuple equal to the unchanged hand-written `FeeSchedule.*` subclass (the fold yields
  *     a field-identical `FeeScheduleValues`, not the subclass);
  *   - EVERY boolean flag — `exceptionalFailedCodeDeposit`, `subGasCapDivisor`, `chargeSelfDestructForNewAccount`,
  *     `noEmptyAccounts`, `eip3541Enabled`, `eip3651Enabled`, `eip3860Enabled`, `eip6049DeprecationEnabled`,
  *     `eip6780Enabled`.
  *
  * The `OpCodes.*` lists and `FeeSchedule.*` classes are UNCHANGED by Row 5.3b, so comparing the folded output against
  * them is comparing against the old opcode/fee content. The boolean-flag expectations are transcribed from the old
  * `*ConfigBuilder` chain (Finding 4 — the flag-regression guard); a regression fails loudly here.
  */
class ForBlockFoldIdentitySpec extends AnyFlatSpec with Matchers:

  import FeeSchedule.*

  /** Golden expectation for one fork: the opcode bundle, fee schedule, and every boolean flag. */
  final private case class Expect(
      opcodes: List[OpCode],
      fee: FeeSchedule,
      exceptionalFailedCodeDeposit: Boolean,
      subGasCapDivisor: Option[Long],
      chargeSelfDestructForNewAccount: Boolean,
      noEmptyAccounts: Boolean,
      eip3541: Boolean,
      eip3651: Boolean,
      eip3860: Boolean,
      eip6049: Boolean,
      eip6780: Boolean
  )

  private def assertIdentity(label: String, evm: EvmConfig, e: Expect): Unit =
    withClue(s"$label opcode set: ")(evm.opCodeList.opCodes.toSet shouldBe e.opcodes.toSet)
    withClue(s"$label fee field-tuple: ")(fields(evm.feeSchedule) shouldBe fields(e.fee))
    withClue(s"$label exceptionalFailedCodeDeposit: ")(
      evm.exceptionalFailedCodeDeposit shouldBe e.exceptionalFailedCodeDeposit
    )
    withClue(s"$label subGasCapDivisor: ")(evm.subGasCapDivisor shouldBe e.subGasCapDivisor)
    withClue(s"$label chargeSelfDestructForNewAccount: ")(
      evm.chargeSelfDestructForNewAccount shouldBe e.chargeSelfDestructForNewAccount
    )
    withClue(s"$label noEmptyAccounts: ")(evm.noEmptyAccounts shouldBe e.noEmptyAccounts)
    withClue(s"$label eip3541Enabled: ")(evm.eip3541Enabled shouldBe e.eip3541)
    withClue(s"$label eip3651Enabled: ")(evm.eip3651Enabled shouldBe e.eip3651)
    withClue(s"$label eip3860Enabled: ")(evm.eip3860Enabled shouldBe e.eip3860)
    withClue(s"$label eip6049DeprecationEnabled: ")(evm.eip6049DeprecationEnabled shouldBe e.eip6049)
    withClue(s"$label eip6780Enabled: ")(evm.eip6780Enabled shouldBe e.eip6780)

  // ---- ETC block ladder (2-arg forBlock, BlockchainConfigForEvm, isEthereum = false) --------------------------------
  // A monotonic ETC ladder: each fork one block above the last; the ETH-only fork fields (eip161/byzantium/…/berlin)
  // stay at the sentinel, exactly as on etc-chain.conf. Olympia is DATED here (it is pending on the real conf) so the
  // full ETC lineage including Olympia is exercised.

  private val etcLadder: BlockchainConfigForEvm = BlockchainConfigForEvm(
    frontierBlockNumber = BlockNumber(0),
    homesteadBlockNumber = BlockNumber(1),
    eip150BlockNumber = BlockNumber(2),
    eip160BlockNumber = BlockNumber(3),
    eip161BlockNumber = BlockNumber(Long.MaxValue),
    byzantiumBlockNumber = BlockNumber(Long.MaxValue),
    constantinopleBlockNumber = BlockNumber(Long.MaxValue),
    istanbulBlockNumber = BlockNumber(Long.MaxValue),
    maxCodeSize = None,
    accountStartNonce = 0,
    atlantisBlockNumber = BlockNumber(4),
    aghartaBlockNumber = BlockNumber(5),
    petersburgBlockNumber = BlockNumber(Long.MaxValue),
    phoenixBlockNumber = BlockNumber(6),
    magnetoBlockNumber = BlockNumber(7),
    berlinBlockNumber = BlockNumber(Long.MaxValue),
    mystiqueBlockNumber = BlockNumber(8),
    spiralBlockNumber = BlockNumber(9),
    eip1559BlockNumber = BlockNumber(10),
    chainId = ChainId(0x3d),
    isEthereum = false
  )

  private val etcLadderCases: List[(String, BlockNumber, Expect)] = List(
    (
      "ETC Frontier",
      BlockNumber(0),
      Expect(
        OpCodes.FrontierOpCodes,
        new FrontierFeeSchedule,
        false,
        None,
        false,
        false,
        false,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETC Homestead",
      BlockNumber(1),
      Expect(
        OpCodes.HomesteadOpCodes,
        new HomesteadFeeSchedule,
        true,
        None,
        false,
        false,
        false,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETC PostEIP150",
      BlockNumber(2),
      Expect(
        OpCodes.HomesteadOpCodes,
        new PostEIP150FeeSchedule,
        true,
        Some(64),
        true,
        false,
        false,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETC PostEIP160",
      BlockNumber(3),
      Expect(
        OpCodes.HomesteadOpCodes,
        new PostEIP160FeeSchedule,
        true,
        Some(64),
        true,
        false,
        false,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETC Atlantis",
      BlockNumber(4),
      Expect(
        OpCodes.ByzantiumOpCodes,
        new AtlantisFeeSchedule,
        true,
        Some(64),
        true,
        true,
        false,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETC Agharta",
      BlockNumber(5),
      Expect(
        OpCodes.ConstantinopleOpCodes,
        new ConstantionopleFeeSchedule,
        true,
        Some(64),
        true,
        true,
        false,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETC Phoenix",
      BlockNumber(6),
      Expect(
        OpCodes.PhoenixOpCodes,
        new PhoenixFeeSchedule,
        true,
        Some(64),
        true,
        true,
        false,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETC Magneto",
      BlockNumber(7),
      Expect(
        OpCodes.PhoenixOpCodes,
        new MagnetoFeeSchedule,
        true,
        Some(64),
        true,
        true,
        false,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETC Mystique",
      BlockNumber(8),
      Expect(
        OpCodes.PhoenixOpCodes,
        new MystiqueFeeSchedule,
        true,
        Some(64),
        true,
        true,
        true,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETC Spiral",
      BlockNumber(9),
      Expect(OpCodes.SpiralOpCodes, new MystiqueFeeSchedule, true, Some(64), true, true, true, true, true, true, false)
    ),
    (
      "ETC Olympia",
      BlockNumber(10),
      Expect(
        OpCodes.EtcOlympiaOpCodes,
        new EtcOlympiaFeeSchedule,
        true,
        Some(64),
        true,
        true,
        true,
        true,
        true,
        true,
        true
      )
    )
  )

  for (label, block, expect) <- etcLadderCases do
    s"forBlock on the ETC ladder at $label" should "be field-identical to the old bundle (opcodes + fees + all flags)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      assertIdentity(label, EvmConfig.forBlock(block, etcLadder), expect)
    }

  // ---- ETH timestamp forks (3-arg forBlock, real blockchains("eth")) ------------------------------------------------
  // A post-London block (>= every ETH block fork) paired with each ETH timestamp fork. EIP-6049 is never flagged on ETH.

  private val ethConf = Config.blockchains.blockchains("eth")
  private val ethBlock = BlockNumber(23_000_000)
  private val shanghaiTs = Timestamp(ethConf.forkTimestamps.shanghaiTimestamp.get)
  private val cancunTs = Timestamp(ethConf.forkTimestamps.cancunTimestamp.get)
  private val pragueTs = Timestamp(ethConf.forkTimestamps.pragueTimestamp.get)
  private val osakaTs = Timestamp(ethConf.forkTimestamps.osakaTimestamp.get)

  private val ethCases: List[(String, Timestamp, Expect)] = List(
    (
      "ETH London",
      Timestamp(0),
      Expect(
        OpCodes.EthLondonOpCodes,
        new EthLondonFeeSchedule,
        true,
        Some(64),
        true,
        true,
        true,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETH Shanghai",
      shanghaiTs,
      Expect(
        OpCodes.EthShanghaiOpCodes,
        new EthLondonFeeSchedule,
        true,
        Some(64),
        true,
        true,
        true,
        true,
        true,
        false,
        false
      )
    ),
    (
      "ETH Cancun",
      cancunTs,
      Expect(
        OpCodes.EthCancunOpCodes,
        new EthCancunFeeSchedule,
        true,
        Some(64),
        true,
        true,
        true,
        true,
        true,
        false,
        true
      )
    ),
    (
      "ETH Prague",
      pragueTs,
      Expect(
        OpCodes.EthCancunOpCodes,
        new EthPragueFeeSchedule,
        true,
        Some(64),
        true,
        true,
        true,
        true,
        true,
        false,
        true
      )
    ),
    (
      "ETH Osaka",
      osakaTs,
      Expect(
        OpCodes.EthOsakaOpCodes,
        new EthOsakaFeeSchedule,
        true,
        Some(64),
        true,
        true,
        true,
        true,
        true,
        false,
        true
      )
    )
  )

  for (label, ts, expect) <- ethCases do
    s"forBlock on real ETH mainnet at $label" should "be field-identical to the old bundle (opcodes + fees + all flags)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      assertIdentity(label, EvmConfig.forBlock(ethBlock, ts, ethConf), expect)
    }

  // ---- ETH pre-London BLOCK heights (closes the residual ETH block-axis gap: Byzantium 4.37M, Constantinople 7.28M) --
  // These exercise the ETH branch of the block fold at intermediate heights on the real eth conf (ts = 0, no ts fork).

  private val ethBlockCases: List[(String, BlockNumber, Expect)] = List(
    (
      "ETH Byzantium",
      BlockNumber(4_370_000),
      Expect(
        OpCodes.ByzantiumOpCodes,
        new ByzantiumFeeSchedule,
        true,
        Some(64),
        true,
        true,
        false,
        false,
        false,
        false,
        false
      )
    ),
    (
      "ETH Constantinople",
      BlockNumber(7_280_000),
      Expect(
        OpCodes.ConstantinopleOpCodes,
        new ConstantionopleFeeSchedule,
        true,
        Some(64),
        true,
        true,
        false,
        false,
        false,
        false,
        false
      )
    )
  )

  for (label, block, expect) <- ethBlockCases do
    s"forBlock on real ETH mainnet at $label height" should "be field-identical to the old bundle (opcodes + fees + all flags)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      assertIdentity(label, EvmConfig.forBlock(block, Timestamp(0), ethConf), expect)
    }
