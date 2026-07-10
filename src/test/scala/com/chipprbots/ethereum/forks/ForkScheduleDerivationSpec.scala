package com.chipprbots.ethereum.forks

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TotalDifficulty
import com.chipprbots.ethereum.forks.ProposalId.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NetworkType

/** Stage 5.3a derivation proof: `BlockchainConfig.forkSchedule` (the derived L3 view) reproduces, for the 5 real conf
  * files, the exact activation decision each underlying L2 field already makes — for every registered proposal, at
  * before/at/after boundary points on each activation axis. This proves the schedule is a faithful derived view without
  * switching any production dispatch onto it.
  *
  * The reference predicate reuses the EXISTING production methods (`isCancunTimestamp`, `isBpo1/2Timestamp`, `isPoS`)
  * where they exist, so `isActive == <production predicate>` is a byte-for-byte equivalence against live behaviour.
  */
class ForkScheduleDerivationSpec extends AnyWordSpec with Matchers:

  private val confNames = List("etc", "mordor", "gorgoroth", "eth", "sepolia")
  private val confs: Map[String, BlockchainConfig] =
    confNames.map(n => n -> Config.blockchains.blockchains(n)).toMap

  // Neutral coordinates for the axes a given proposal ignores.
  private val bZero = BlockNumber(0)
  private val tsZero = Timestamp(0)
  private val tdZero = TotalDifficulty(0)

  private val OlympiaPendingSentinel = BigInt("1000000000000000000")
  private val MaxBlockSentinel = BigInt(Long.MaxValue)

  /** The independent reference for the ETC-family Olympia bundle (ECIP-1111/1112/1122): active only on an ETC network,
    * at a real (non-sentinel) `olympiaBlockNumber`, from that height onward.
    */
  private def olympiaRef(cfg: BlockchainConfig, block: BigInt): Boolean =
    val olympia = cfg.forkBlockNumbers.olympiaBlockNumber.value
    cfg.networkType == NetworkType.ETC &&
    olympia != OlympiaPendingSentinel &&
    olympia != MaxBlockSentinel &&
    block >= olympia

  private def tsPoints(fork: Option[Long]): List[Long] =
    fork.toList.flatMap(t => List(t - 1, t, t + 1)) ::: List(0L, 1000000000L, Long.MaxValue)

  private def tdPoints(ttd: Option[BigInt]): List[BigInt] =
    ttd.toList.flatMap(t => List(t - 1, t, t + 1)) ::: List(BigInt(0), BigInt(1))

  private def blockPoints(cfg: BlockchainConfig): List[BigInt] =
    val olympia = cfg.forkBlockNumbers.olympiaBlockNumber.value
    List(BigInt(0), BigInt(1), BigInt(1000000), olympia - 1, olympia, olympia + 1, MaxBlockSentinel)

  "BlockchainConfig.forkSchedule (derived L3 view)" should {

    "keep ECIP-1017 emission active from genesis on every network (F6: activation = ByBlock(0), era is a param)" in {
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          cfg.forkSchedule.activationOf(Ecip(1017)) shouldBe ForkActivation.ByBlock(BlockNumber(0))
          // ByBlock(0) ⇒ active iff block >= 0 (emission applies from genesis onward; a real height is never negative,
          // but blockPoints synthesises olympia-1 = -1 on Sepolia where olympia=0, which correctly derives inactive).
          blockPoints(cfg).foreach { b =>
            cfg.forkSchedule.isActive(Ecip(1017), BlockNumber(b), tsZero, tdZero) shouldBe (b >= BigInt(0))
          }
          // The MonetaryPolicy param is carried and equals the config's monetary-policy verbatim.
          cfg.forkSchedule.paramsOf(Ecip(1017)).monetaryPolicy shouldBe Some(cfg.monetaryPolicyConfig)
        }
      }
    }

    "reproduce the ETC Olympia bundle (ECIP-1111/1112/1121-family) activation for every registered id at every height" in {
      val bundle = List(Ecip(1111), Ecip(1112), Ecip(1122))
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          bundle.foreach { id =>
            blockPoints(cfg).foreach { b =>
              cfg.forkSchedule.isActive(id, BlockNumber(b), tsZero, tdZero) shouldBe olympiaRef(cfg, b)
            }
          }
        }
      }
    }

    "derive the Olympia bundle to Never on every current conf (ETC parks olympia at the 1e18 pending sentinel; ETH gates it out)" in {
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          List(Ecip(1111), Ecip(1112), Ecip(1122)).foreach { id =>
            cfg.forkSchedule.activationOf(id) shouldBe ForkActivation.Never
          }
        }
      }
    }

    "reproduce EIP-4844 (Cancun blob fork) exactly against the existing isCancunTimestamp predicate" in {
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          tsPoints(cfg.forkTimestamps.cancunTimestamp).foreach { t =>
            cfg.forkSchedule.isActive(Eip(4844), bZero, Timestamp(t), tdZero) shouldBe
              cfg.isCancunTimestamp(Timestamp(t))
          }
        }
      }
    }

    "reproduce EIP-7892 BPO1 exactly against the existing isBpo1Timestamp predicate (beacon F1)" in {
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          tsPoints(cfg.forkTimestamps.bpo1Timestamp).foreach { t =>
            cfg.forkSchedule.isActive(Custom("bpo", 1), bZero, Timestamp(t), tdZero) shouldBe
              cfg.isBpo1Timestamp(Timestamp(t))
          }
        }
      }
    }

    "reproduce EIP-7892 BPO2 exactly against the existing isBpo2Timestamp predicate (beacon F1)" in {
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          tsPoints(cfg.forkTimestamps.bpo2Timestamp).foreach { t =>
            cfg.forkSchedule.isActive(Custom("bpo", 2), bZero, Timestamp(t), tdZero) shouldBe
              cfg.isBpo2Timestamp(Timestamp(t))
          }
        }
      }
    }

    "reproduce the PoS transition (the Merge, ByTotalDifficulty) exactly against the existing isPoS predicate" in {
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          tdPoints(cfg.terminalTotalDifficulty).foreach { td =>
            cfg.forkSchedule.isActive(Custom("merge", 0), bZero, tsZero, TotalDifficulty(td)) shouldBe
              cfg.isPoS(TotalDifficulty(td))
          }
        }
      }
    }

    "carry the ECIP-1122 ClientPolicy params (MIN_MINER_TIP + gas-target schedule) verbatim from the config (banksy-consulted shape)" in {
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          val params = cfg.forkSchedule.paramsOf(Ecip(1122))
          params.number(ProposalParams.MinTipKey) shouldBe Some(cfg.minTip)
          params.number(ProposalParams.SpiralGasTargetKey) shouldBe cfg.forkBlockNumbers.spiralGasTarget
          params.number(ProposalParams.OlympiaGasTargetKey) shouldBe cfg.forkBlockNumbers.olympiaGasTarget
        }
      }
    }

    "carry the ECIP-1111 Treasury + base-fee-floor params verbatim from the config" in {
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          val params = cfg.forkSchedule.paramsOf(Ecip(1111))
          params.address(ProposalParams.TreasuryAddressKey) shouldBe Some(cfg.treasuryAddress)
          params.number(ProposalParams.BaseFeeFloorKey) shouldBe Some(cfg.baseFeeFloor)
        }
      }
    }

    "derive foreign-family / absent forks to Never (sentinel and None cases)" in {
      // ETC family: no timestamp forks, no TTD -> the ETH-axis proposals derive to Never.
      List("etc", "mordor", "gorgoroth").foreach { n =>
        val cfg = confs(n)
        withClue(s"[$n] ") {
          cfg.forkSchedule.activationOf(Eip(4844)) shouldBe ForkActivation.Never
          cfg.forkSchedule.activationOf(Custom("bpo", 1)) shouldBe ForkActivation.Never
          cfg.forkSchedule.activationOf(Custom("bpo", 2)) shouldBe ForkActivation.Never
          cfg.forkSchedule.activationOf(
            Custom("merge", 0)
          ) shouldBe ForkActivation.Never // ETC has no terminal-total-difficulty
        }
      }
    }

    "prove the active ByBlock path for the Olympia bundle when olympia is a real ETC height (derived from the real etc conf)" in {
      // withUpdatedForkBlocks keeps the load-from-conf provenance while flipping the pending sentinel to a real height,
      // so the derived schedule's active branch is exercised (all 5 shipped confs currently park Olympia as pending).
      val real = BigInt(25000000)
      val cfg = confs("etc").withUpdatedForkBlocks(_.copy(olympiaBlockNumber = BlockNumber(real)))
      cfg.networkType shouldBe NetworkType.ETC
      cfg.forkSchedule.activationOf(Ecip(1111)) shouldBe ForkActivation.ByBlock(BlockNumber(real))
      List(Ecip(1111), Ecip(1112), Ecip(1122)).foreach { id =>
        cfg.forkSchedule.isActive(id, BlockNumber(real - 1), tsZero, tdZero) shouldBe false
        cfg.forkSchedule.isActive(id, BlockNumber(real), tsZero, tdZero) shouldBe true
        cfg.forkSchedule.isActive(id, BlockNumber(real + 1), tsZero, tdZero) shouldBe true
      }
    }
  }
