package com.chipprbots.ethereum.consensus.engine

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.vm.CLZ
import com.chipprbots.ethereum.vm.EvmConfig
import com.chipprbots.ethereum.vm.FeeSchedule
import com.chipprbots.ethereum.vm.FeeScheduleFields.fields

/** ETH mainnet Osaka + EIP-7892 BPO1/BPO2 activation guard. Loads the real `blockchains("eth")` config and asserts the
  * fork timestamps, Osaka opcode set, and BPO blob schedule against go-ethereum ground truth.
  *
  * Ground truth: go-ethereum `params/config.go` `MainnetChainConfig` (local clone
  * `.claude/repo-references/clients/go-ethereum` @ 59e89e81e): OsakaTime = 1764798551 (:64), BPO1Time = 1765290071
  * (:65), BPO2Time = 1767747671 (:66). Audit map:
  * `.local/docs/research-july/eip-ecip-conformance/eth-sepolia-conformance.md` §3 F-1/F-2.
  */
class EthMainnetOsakaActivationSpec extends AnyWordSpec with Matchers:

  // go-ethereum MainnetChainConfig timestamps (params/config.go:64-66).
  private val OsakaTs: Long = 1764798551L // 2025-12-03 21:49:11 UTC
  private val Bpo1Ts: Long = 1765290071L // 2025-12-09 14:21:11 UTC
  private val Bpo2Ts: Long = 1767747671L // 2026-01-07 01:01:11 UTC

  private val ethConf = Config.blockchains.blockchains("eth")

  private val PostOsakaBlock: BlockNumber = BlockNumber(23_000_000)

  "ETH mainnet fork schedule (blockchains(\"eth\"))" should {

    "carry the go-ethereum Osaka timestamp (config.go:64 = 1764798551)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      ethConf.forkTimestamps.osakaTimestamp shouldBe Some(OsakaTs)
    }

    "carry the go-ethereum BPO1 timestamp (config.go:65 = 1765290071)" taggedAs (UnitTest, ConsensusTest) in {
      ethConf.forkTimestamps.bpo1Timestamp shouldBe Some(Bpo1Ts)
    }

    "carry the go-ethereum BPO2 timestamp (config.go:66 = 1767747671)" taggedAs (UnitTest, ConsensusTest) in {
      ethConf.forkTimestamps.bpo2Timestamp shouldBe Some(Bpo2Ts)
    }

    "activate the Osaka fork predicate at the mainnet Osaka timestamp" taggedAs (UnitTest, ConsensusTest) in {
      ethConf.isOsakaTimestamp(Timestamp(OsakaTs - 1)) shouldBe false
      ethConf.isOsakaTimestamp(Timestamp(OsakaTs)) shouldBe true
    }
  }

  "ETH mainnet EVM at a post-Osaka timestamp" should {

    "yield the Osaka opcode set (CLZ 0x1E present, EIP-7939)" taggedAs (UnitTest, ConsensusTest) in {
      val evm = EvmConfig.forBlock(PostOsakaBlock, Timestamp(OsakaTs), ethConf)
      evm.byteToOpCode.get(0x1e.toByte) shouldBe Some(CLZ)
      evm.opCodeList.opCodes.toSet shouldBe EvmConfig.EthOsakaOpCodes.opCodes.toSet
    }

    "use the Osaka fee schedule" taggedAs (UnitTest, ConsensusTest) in {
      fields(
        EvmConfig.forBlock(PostOsakaBlock, Timestamp(OsakaTs), ethConf).feeSchedule
      ) shouldBe fields(new FeeSchedule.EthOsakaFeeSchedule)
    }
  }

  "ETH mainnet blob schedule (EIP-7892)" should {

    "resolve Prague params just before BPO1 (target 6 / max 9)" taggedAs (UnitTest, ConsensusTest) in {
      BlobGasUtils.targetBlobGasPerBlock(Timestamp(Bpo1Ts - 1), ethConf) shouldBe BlobGasUtils.PRAGUE_TARGET_BLOB_GAS
      BlobGasUtils.maxBlobGasPerBlock(Timestamp(Bpo1Ts - 1), ethConf) shouldBe BlobGasUtils.PRAGUE_MAX_BLOB_GAS
    }

    "resolve BPO1 params at the mainnet BPO1 timestamp (target 10 / max 15)" taggedAs (UnitTest, ConsensusTest) in {
      BlobGasUtils.targetBlobGasPerBlock(Timestamp(Bpo1Ts), ethConf) shouldBe BlobGasUtils.BPO1_TARGET_BLOB_GAS
      BlobGasUtils.maxBlobGasPerBlock(Timestamp(Bpo1Ts), ethConf) shouldBe BlobGasUtils.BPO1_MAX_BLOB_GAS
    }

    "resolve BPO2 params at the mainnet BPO2 timestamp (target 14 / max 21)" taggedAs (UnitTest, ConsensusTest) in {
      BlobGasUtils.targetBlobGasPerBlock(Timestamp(Bpo2Ts), ethConf) shouldBe BlobGasUtils.BPO2_TARGET_BLOB_GAS
      BlobGasUtils.maxBlobGasPerBlock(Timestamp(Bpo2Ts), ethConf) shouldBe BlobGasUtils.BPO2_MAX_BLOB_GAS
      // 14 target / 21 max blobs, byte-exact with go-ethereum DefaultBPO2BlobConfig.
      BlobGasUtils.targetBlobGasPerBlock(Timestamp(Bpo2Ts), ethConf) shouldBe (BigInt(14) * BlobGasUtils.GAS_PER_BLOB)
      BlobGasUtils.maxBlobGasPerBlock(Timestamp(Bpo2Ts), ethConf) shouldBe (BigInt(21) * BlobGasUtils.GAS_PER_BLOB)
      BlobGasUtils.updateFractionFor(
        Timestamp(Bpo2Ts),
        ethConf
      ) shouldBe BlobGasUtils.BPO2_BLOB_BASE_FEE_UPDATE_FRACTION
    }
  }
