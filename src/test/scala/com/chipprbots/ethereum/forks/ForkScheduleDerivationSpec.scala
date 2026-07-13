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
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm
import com.chipprbots.ethereum.vm.forks.EvmProposals

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
    * at a real (non-sentinel) `eip1559BlockNumber`, from that height onward.
    */
  private def olympiaRef(cfg: BlockchainConfig, block: BigInt): Boolean =
    val olympia = cfg.forkBlockNumbers.eip1559BlockNumber.value
    cfg.networkType == NetworkType.ETC &&
    olympia != OlympiaPendingSentinel &&
    olympia != MaxBlockSentinel &&
    block >= olympia

  private def tsPoints(fork: Option[Long]): List[Long] =
    fork.toList.flatMap(t => List(t - 1, t, t + 1)) ::: List(0L, 1000000000L, Long.MaxValue)

  private def tdPoints(ttd: Option[BigInt]): List[BigInt] =
    ttd.toList.flatMap(t => List(t - 1, t, t + 1)) ::: List(BigInt(0), BigInt(1))

  private def blockPoints(cfg: BlockchainConfig): List[BigInt] =
    val olympia = cfg.forkBlockNumbers.eip1559BlockNumber.value
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

    // ---- Row 5.8a: fork-id-only block markers (PoW/ETC + neutral half; forge) --------------------------------------
    // Each marker must reproduce the OLD raw-field predicate (block >= field, unless the field is a not-scheduled
    // sentinel) at before/at/after boundary points, on the ETC-family confs where these ETC/neutral forks live.

    // Independent reference: a block field is active iff it is a real (non-sentinel) height that the block has reached.
    def blockFieldRef(v: BigInt, block: BigInt): Boolean =
      v != OlympiaPendingSentinel && v != MaxBlockSentinel && block >= v

    // Boundary heights around a specific field value, plus the shared block grid.
    def fieldPoints(v: BigInt, cfg: BlockchainConfig): List[BigInt] =
      (List(v - 1, v, v + 1).filter(_ >= 0) ::: blockPoints(cfg)).distinct

    val forkIdMarkers: List[(ProposalId, BlockchainConfig => BigInt)] = List(
      Ecip(1099) -> (_.forkBlockNumbers.ecip1099BlockNumber.value),
      Custom("ecip1010", 1) -> (_.forkBlockNumbers.difficultyBombPauseBlockNumber.value),
      Custom("ecip1010", 2) -> (_.forkBlockNumbers.difficultyBombContinueBlockNumber.value),
      Ecip(1041) -> (_.forkBlockNumbers.difficultyBombRemovalBlockNumber.value),
      Eip(106) -> (_.forkBlockNumbers.eip106BlockNumber.value),
      Eip(155) -> (_.forkBlockNumbers.eip155BlockNumber.value)
    )

    "reproduce every Row 5.8a fork-id block marker against its raw field, at boundary heights on the ETC-family confs" in {
      List("etc", "mordor", "gorgoroth").foreach { name =>
        val cfg = confs(name)
        withClue(s"[$name] ") {
          forkIdMarkers.foreach { case (id, field) =>
            val v = field(cfg)
            withClue(s"${id.label} (field=$v) ") {
              fieldPoints(v, cfg).foreach { b =>
                cfg.forkSchedule.isActive(id, BlockNumber(b), tsZero, tdZero) shouldBe blockFieldRef(v, b)
              }
            }
          }
        }
      }
    }

    "keep the fork-id-only markers structurally invisible to the EVM fold (not in the allowlist; deriveEvm invariant)" in {
      val markers: Set[ProposalId] =
        Set(Ecip(1099), Custom("ecip1010", 1), Custom("ecip1010", 2), Ecip(1041), Eip(106), Eip(155))
      // The fold that produces the EVM config iterates `evmApplicationOrder` / `byId`, never `schedule.entries`, so an
      // id absent from both cannot alter the derived opcode set, fee schedule or config flags.
      markers.foreach { id =>
        EvmProposals.evmApplicationOrder should not contain id
        EvmProposals.byId.keySet should not contain id
      }
      // Direct byte-identity proof: injecting the markers into any active proposal set leaves `deriveEvm` unchanged.
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          val cfgEvm = BlockchainConfigForEvm(cfg)
          blockPoints(cfg).filter(_ >= 0).foreach { b =>
            val active = EvmProposals.activeBlockProposals(cfgEvm, BlockNumber(b))
            val (opsWith, feeWith) = EvmProposals.deriveEvm(active ++ markers)
            val (opsBase, feeBase) = EvmProposals.deriveEvm(active)
            opsWith.toSet shouldBe opsBase.toSet
            feeWith shouldBe feeBase
          }
        }
      }
    }

    // ---- Row 5.8a: fork-id-only block markers (PoS/ETH half; beacon) -----------------------------------------------
    // The ETH-lineage pre-merge block markers (Petersburg, the three Glacier bomb-delays, Sepolia's merge-netsplit) and
    // the conditional DAO fork-id-list entry. Each reproduces the OLD raw-field predicate on the ETH-family confs. These
    // reuse forge's `blockFieldRef`/`fieldPoints` helpers above.

    val forkIdMarkersEth: List[(ProposalId, BlockchainConfig => BigInt)] = List(
      Eip(1716) -> (_.forkBlockNumbers.petersburgBlockNumber.value),
      Eip(2384) -> (_.forkBlockNumbers.muirGlacierBlockNumber.value),
      Eip(4345) -> (_.forkBlockNumbers.arrowGlacierBlockNumber.value),
      Eip(5133) -> (_.forkBlockNumbers.grayGlacierBlockNumber.value),
      Custom("merge-netsplit", 0) -> (_.forkBlockNumbers.mergeNetsplitBlockNumber.value)
    )

    "reproduce every Row 5.8a PoS/ETH fork-id block marker against its raw field, at boundary heights on the ETH confs" in {
      List("eth", "sepolia").foreach { name =>
        val cfg = confs(name)
        withClue(s"[$name] ") {
          forkIdMarkersEth.foreach { case (id, field) =>
            val v = field(cfg)
            withClue(s"${id.label} (field=$v) ") {
              fieldPoints(v, cfg).foreach { b =>
                cfg.forkSchedule.isActive(id, BlockNumber(b), tsZero, tdZero) shouldBe blockFieldRef(v, b)
              }
            }
          }
        }
      }
    }

    // DAO (EIP-779) is Option-conditional, not a plain block field: it contributes iff `includeOnForkIdList == true`,
    // reproducing `ForkId.gatherBlockForks`'s exact predicate (ETH true; ETC/Mordor false -> Never).
    def daoRef(cfg: BlockchainConfig, block: BigInt): Boolean =
      cfg.daoForkConfig match
        case Some(dao) if dao.includeOnForkIdList =>
          val v = dao.forkBlockNumber.value
          v != OlympiaPendingSentinel && v != MaxBlockSentinel && block >= v
        case _ => false

    "gate the DAO (EIP-779) fork-id entry on includeOnForkIdList: Never on ETC/Mordor, ByBlock(1920000) on ETH" in {
      confs("etc").forkSchedule.activationOf(Eip(779)) shouldBe ForkActivation.Never
      confs("mordor").forkSchedule.activationOf(Eip(779)) shouldBe ForkActivation.Never
      confs("eth").forkSchedule.activationOf(Eip(779)) shouldBe ForkActivation.ByBlock(BlockNumber(1920000))
      // Boundary-height equivalence against the includeOnForkIdList-gated raw predicate, across every conf.
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          val daoBlock = cfg.daoForkConfig.map(_.forkBlockNumber.value).getOrElse(BigInt(1920000))
          (List(daoBlock - 1, daoBlock, daoBlock + 1).filter(_ >= 0) ::: blockPoints(cfg)).distinct.foreach { b =>
            cfg.forkSchedule.isActive(Eip(779), BlockNumber(b), tsZero, tdZero) shouldBe daoRef(cfg, b)
          }
        }
      }
    }

    "keep the PoS/ETH fork-id markers structurally invisible to the EVM fold (not in the allowlist; deriveEvm invariant)" in {
      val markers: Set[ProposalId] =
        Set(Eip(1716), Eip(2384), Eip(4345), Eip(5133), Custom("merge-netsplit", 0), Eip(779))
      markers.foreach { id =>
        EvmProposals.evmApplicationOrder should not contain id
        EvmProposals.byId.keySet should not contain id
      }
      // Direct byte-identity proof: injecting the PoS markers into any active proposal set leaves `deriveEvm` unchanged.
      confs.foreach { case (name, cfg) =>
        withClue(s"[$name] ") {
          val cfgEvm = BlockchainConfigForEvm(cfg)
          blockPoints(cfg).filter(_ >= 0).foreach { b =>
            val active = EvmProposals.activeBlockProposals(cfgEvm, BlockNumber(b))
            val (opsWith, feeWith) = EvmProposals.deriveEvm(active ++ markers)
            val (opsBase, feeBase) = EvmProposals.deriveEvm(active)
            opsWith.toSet shouldBe opsBase.toSet
            feeWith shouldBe feeBase
          }
        }
      }
    }

    "prove the active ByBlock path for the Olympia bundle when olympia is a real ETC height (derived from the real etc conf)" in {
      // withUpdatedForkBlocks keeps the load-from-conf provenance while flipping the pending sentinel to a real height,
      // so the derived schedule's active branch is exercised (all 5 shipped confs currently park Olympia as pending).
      val real = BigInt(25000000)
      val cfg = confs("etc").withUpdatedForkBlocks(_.copy(eip1559BlockNumber = BlockNumber(real)))
      cfg.networkType shouldBe NetworkType.ETC
      cfg.forkSchedule.activationOf(Ecip(1111)) shouldBe ForkActivation.ByBlock(BlockNumber(real))
      List(Ecip(1111), Ecip(1112), Ecip(1122)).foreach { id =>
        cfg.forkSchedule.isActive(id, BlockNumber(real - 1), tsZero, tdZero) shouldBe false
        cfg.forkSchedule.isActive(id, BlockNumber(real), tsZero, tdZero) shouldBe true
        cfg.forkSchedule.isActive(id, BlockNumber(real + 1), tsZero, tdZero) shouldBe true
      }
    }
  }
