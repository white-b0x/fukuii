package com.chipprbots.ethereum.vm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.testing.Tags.*

import BlockchainConfigForEvm.EtcForks.EtcFork
import Fixtures.blockchainConfig
import FeeScheduleFields.fields

/** Tests for Olympia EIP enablement flags and constants.
  *
  * Verifies that all 14 Olympia EIPs are correctly gated by fork block number and that critical constants (EIP-2935
  * system contract address, history window) are correct.
  */
class OlympiaEipEnablementSpec extends AnyFlatSpec with Matchers:

  val configOlympia: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)
  val configSpiral: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)

  val olympiaEtcFork: EtcFork =
    blockchainConfig.etcForkForBlockNumber(BlockNumber(Fixtures.OlympiaBlockNumber))
  val spiralEtcFork: EtcFork =
    blockchainConfig.etcForkForBlockNumber(BlockNumber(Fixtures.SpiralBlockNumber))

  "Olympia EIP enablement when EIP-1559 (base fee)" should "be enabled at Olympia" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    BlockchainConfigForEvm.isEip1559Enabled(olympiaEtcFork) shouldBe true
  }
  it should "be disabled pre-Olympia" taggedAs (UnitTest, OlympiaTest) in {
    BlockchainConfigForEvm.isEip1559Enabled(spiralEtcFork) shouldBe false
  }

  "Olympia EIP enablement when EIP-1153 (transient storage)" should "be enabled at Olympia" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    BlockchainConfigForEvm.isEip1153Enabled(olympiaEtcFork) shouldBe true
  }
  it should "be disabled pre-Olympia" taggedAs (UnitTest, OlympiaTest) in {
    BlockchainConfigForEvm.isEip1153Enabled(spiralEtcFork) shouldBe false
  }

  "Olympia EIP enablement when EIP-5656 (MCOPY)" should "be enabled at Olympia" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    BlockchainConfigForEvm.isEip5656Enabled(olympiaEtcFork) shouldBe true
  }
  it should "be disabled pre-Olympia" taggedAs (UnitTest, OlympiaTest) in {
    BlockchainConfigForEvm.isEip5656Enabled(spiralEtcFork) shouldBe false
  }

  "Olympia EIP enablement when EIP-6780 (SELFDESTRUCT restriction)" should "be enabled at Olympia via EvmConfig flag" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    configOlympia.eip6780Enabled shouldBe true
  }
  it should "be disabled pre-Olympia via EvmConfig flag" taggedAs (UnitTest, OlympiaTest) in {
    configSpiral.eip6780Enabled shouldBe false
  }
  it should "be enabled at Olympia via fork helper" taggedAs (UnitTest, OlympiaTest) in {
    BlockchainConfigForEvm.isEip6780Enabled(olympiaEtcFork) shouldBe true
  }

  "Olympia EIP enablement when EIP-7702 (set code transaction)" should "be enabled at Olympia" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    BlockchainConfigForEvm.isEip7702Enabled(olympiaEtcFork) shouldBe true
  }
  it should "be disabled pre-Olympia" taggedAs (UnitTest, OlympiaTest) in {
    BlockchainConfigForEvm.isEip7702Enabled(spiralEtcFork) shouldBe false
  }

  "Olympia EIP enablement when EIP-2935 (historical block hashes)" should "be enabled at Olympia" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    BlockchainConfigForEvm.isEip2935Enabled(olympiaEtcFork) shouldBe true
  }
  it should "be disabled pre-Olympia" taggedAs (UnitTest, OlympiaTest) in {
    BlockchainConfigForEvm.isEip2935Enabled(spiralEtcFork) shouldBe false
  }

  "Olympia EIP enablement when EIP-2537 (BLS12-381 precompiles)" should "be enabled at Olympia" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    BlockchainConfigForEvm.isEip2537Enabled(olympiaEtcFork) shouldBe true
  }
  it should "be disabled pre-Olympia" taggedAs (UnitTest, OlympiaTest) in {
    BlockchainConfigForEvm.isEip2537Enabled(spiralEtcFork) shouldBe false
  }

  "Olympia EIP enablement when EIP-7951 (P256Verify precompile)" should "be enabled at Olympia" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    BlockchainConfigForEvm.isEip7951Enabled(olympiaEtcFork) shouldBe true
  }
  it should "be disabled pre-Olympia" taggedAs (UnitTest, OlympiaTest) in {
    BlockchainConfigForEvm.isEip7951Enabled(spiralEtcFork) shouldBe false
  }

  "ETC Olympia precompile set (ECIP-1121, etcOlympiaContracts)" should
    "include P256VERIFY at address 0x100 (EIP-7951)" taggedAs (UnitTest, OlympiaTest) in {
      (PrecompiledContracts.etcOlympiaContracts should contain).key(PrecompiledContracts.P256VerifyAddr)
    }

  it should "include all seven BLS12-381 precompiles at 0x0b-0x11 (EIP-2537)" taggedAs (UnitTest, OlympiaTest) in {
    val blsAddrs = Seq(
      PrecompiledContracts.BlsG1AddAddr,
      PrecompiledContracts.BlsG1MultiExpAddr,
      PrecompiledContracts.BlsG2AddAddr,
      PrecompiledContracts.BlsG2MultiExpAddr,
      PrecompiledContracts.BlsPairingAddr,
      PrecompiledContracts.BlsMapG1Addr,
      PrecompiledContracts.BlsMapG2Addr
    )
    blsAddrs.foreach(addr => (PrecompiledContracts.etcOlympiaContracts should contain).key(addr))
  }

  it should "EXCLUDE the Cancun 0x0a KZG precompile (EIP-4844 not adopted on ETC — core-geth has no EIP4844FBlock)" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    // Regression guard for the latent chain-split: ETC Olympia previously routed through ETH's
    // osakaContracts, which inherits the Cancun 0x0a KZG precompile. A CALL to 0x0a on ETC would then
    // charge 50000 gas + run KZG in fukuii vs hit an empty account in core-geth → state-root split.
    PrecompiledContracts.etcOlympiaContracts should not contain key(PrecompiledContracts.KzgPointEvalAddr)
  }

  "ETH Osaka precompile set (osakaContracts)" should
    "retain the Cancun 0x0a KZG precompile (EIP-4844 IS active on ETH)" taggedAs (UnitTest, OlympiaTest) in {
      // ETH path is unchanged by the ETC decoupling: 0x0a stays a precompile on ETH.
      (PrecompiledContracts.osakaContracts should contain).key(PrecompiledContracts.KzgPointEvalAddr)
    }

  "ETH Prague precompile set (pragueContracts, BLS-only)" should
    "NOT include P256VERIFY (EIP-7951 is Osaka-only on ETH)" taggedAs (UnitTest, OlympiaTest) in {
      PrecompiledContracts.pragueContracts should not contain key(PrecompiledContracts.P256VerifyAddr)
    }

  "ETC Olympia precompile dispatch (ECIP-1121) when P256VerifyAddr" should "be Address(0x100) per EIP-7951" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    PrecompiledContracts.P256VerifyAddr shouldEqual Address(0x100)
  }

  "EIP-2935 constants when history storage contract" should "use correct system contract address" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    BlockExecution.HistoryStorageAddress shouldEqual Address("0x0000F90827F1C53a10cb7A02335B175320002935")
  }

  it should "have 8191-block history window" taggedAs (UnitTest, OlympiaTest) in {
    BlockExecution.HistoryServeWindow shouldEqual BigInt(8191)
  }

  it should "have non-empty contract code" taggedAs (UnitTest, OlympiaTest) in {
    BlockExecution.HistoryStorageCode should not be empty
  }

  it should "have contract code starting with expected prefix" taggedAs (UnitTest, OlympiaTest) in {
    // The deployed bytecode should start with CALLER opcode (0x33)
    BlockExecution.HistoryStorageCode.head shouldEqual 0x33.toByte
  }

  "Olympia opcode set when compared to Spiral opcode set" should
    "include all new opcodes: BASEFEE, TLOAD, TSTORE, MCOPY" taggedAs (UnitTest, OlympiaTest) in {
      val olympiaOps = configOlympia.byteToOpCode
      val spiralOps = configSpiral.byteToOpCode

      // New opcodes in Olympia
      olympiaOps.get(0x48.toByte) shouldBe Some(BASEFEE) // EIP-3198
      olympiaOps.get(0x5c.toByte) shouldBe Some(TLOAD) // EIP-1153
      olympiaOps.get(0x5d.toByte) shouldBe Some(TSTORE) // EIP-1153
      olympiaOps.get(0x5e.toByte) shouldBe Some(MCOPY) // EIP-5656

      // Not in Spiral
      spiralOps.get(0x48.toByte) shouldBe None
      spiralOps.get(0x5c.toByte) shouldBe None
      spiralOps.get(0x5d.toByte) shouldBe None
      spiralOps.get(0x5e.toByte) shouldBe None
    }

  it should "retain all pre-existing opcodes (Spiral opcodes are a subset)" taggedAs (UnitTest, OlympiaTest) in {
    val olympiaOps = configOlympia.byteToOpCode
    val spiralOps = configSpiral.byteToOpCode

    // Every opcode in Spiral should also be in Olympia
    spiralOps.foreach { case (byte, opcode) =>
      olympiaOps.get(byte) shouldBe Some(opcode)
    }
  }

  "Olympia fee schedule" should "be field-identical to EtcOlympiaFeeSchedule" taggedAs (UnitTest, OlympiaTest) in {
    fields(configOlympia.feeSchedule) shouldBe fields(new FeeSchedule.EtcOlympiaFeeSchedule)
  }

  it should "inherit Mystique fee schedule values" taggedAs (UnitTest, OlympiaTest) in {
    // EtcOlympiaFeeSchedule extends MystiqueFeeSchedule — verify key inherited values
    configOlympia.feeSchedule.R_selfdestruct shouldEqual 0 // EIP-3529
    configOlympia.feeSchedule.R_sclear shouldEqual 4800 // EIP-3529
    configOlympia.feeSchedule.G_warm_storage_read shouldEqual 100
    configOlympia.feeSchedule.G_cold_account_access shouldEqual 2600
    configOlympia.feeSchedule.G_initcode_word shouldEqual 2 // EIP-3860
  }
