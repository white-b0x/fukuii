package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks.MockVM
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.ValidatorsExecutor
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.forks.ForkActivation
import com.chipprbots.ethereum.forks.ProposalId.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NetworkType

/** Stage 5.4b resolution proof: [[ConsensusEngine.engineFor]] returns the family-correct engine for the 5 real conf
  * files, and does so at NETWORK-FAMILY granularity (not block/timestamp granularity).
  *
  * Two design decisions this spec pins down (both grounded in core-geth `consensus/ethash`):
  *   1. Resolution is family-stable — ETC → [[EngineId.Ethash]], ETH → [[EngineId.EngineApi]] — keyed on the existing
  *      `BlockchainConfig.networkType`. The engine instance never switches at the Merge; [[EngineApiEngine]] handles
  *      the pre-/post-Merge split internally (its `headerValidator` is [[TransitionBlockHeaderValidator]]). So no block
  *      number, timestamp, or TTD is consulted for selection — proven by cross-checking against the derived
  *      `Custom("merge", 0)` schedule proposal / `terminalTotalDifficulty`. 2. ECIP-1099 (ETChash) is a DAG-epoch
  *      PARAMETER (`ecip1099BlockNumber`) within the single Ethash engine, not a distinct engine id — mirroring
  *      core-geth's `Config.ECIP1099Block` + `calcEpochLength`. The 30000→60000 epoch flip is proven byte-correct at
  *      each ETC chain's own boundary via [[EthashUtils.epoch]].
  */
class EngineResolutionSpec extends AnyWordSpec with Matchers:

  private val etcFamily = List("etc", "mordor", "gorgoroth")
  private val ethFamily = List("eth", "sepolia")
  private val confNames = etcFamily ::: ethFamily
  private val confs: Map[String, BlockchainConfig] =
    confNames.map(n => n -> Config.blockchains.blockchains(n)).toMap

  private val EpochLenBeforeEcip1099 = 30000L
  private val EpochLenAfterEcip1099 = 60000L

  "ConsensusEngine.engineIdFor (network-family resolution)" should {

    "resolve every ETC-family conf to EngineId.Ethash (PoW)" in {
      etcFamily.foreach { n =>
        withClue(s"[$n] ") {
          confs(n).networkType shouldBe NetworkType.ETC
          ConsensusEngine.engineIdFor(confs(n)) shouldBe EngineId.Ethash
        }
      }
    }

    "resolve every ETH-family conf to EngineId.EngineApi (PoS)" in {
      ethFamily.foreach { n =>
        withClue(s"[$n] ") {
          confs(n).networkType shouldBe NetworkType.ETH
          ConsensusEngine.engineIdFor(confs(n)) shouldBe EngineId.EngineApi
        }
      }
    }

    "agree with the existing family markers (terminalTotalDifficulty / the derived merge proposal)" in {
      // The resolution keys on `networkType`; this proves it is consistent with the two markers the pre-5.4 codebase
      // already trusts — `terminalTotalDifficulty.isDefined` (SyncController's `isPoSChain`) and the derived
      // `Custom("merge", 0)` schedule activation. ETH ⇔ TTD defined ⇔ merge proposal ≠ Never; ETC is the negation.
      confs.foreach { case (n, cfg) =>
        withClue(s"[$n] ") {
          val isEth = ConsensusEngine.engineIdFor(cfg) == EngineId.EngineApi
          isEth shouldBe cfg.terminalTotalDifficulty.isDefined
          isEth shouldBe (cfg.forkSchedule.activationOf(Custom("merge", 0)) != ForkActivation.Never)
        }
      }
    }
  }

  "ECIP-1099 (ETChash) as a DAG-epoch parameter of the single Ethash engine" should {

    "flip the epoch length 30000→60000 at exactly each ETC chain's ecip1099BlockNumber (byte-correct on ETC mainnet and Mordor)" in {
      // core-geth parity: calcEpochLength(block, ecip1099FBlock) returns 30000 below the boundary and 60000 at/above.
      // We infer the length from EthashUtils.epoch (= block / length): a flip in the divisor is the observable effect.
      etcFamily.foreach { n =>
        withClue(s"[$n] ") {
          val ecip = confs(n).forkBlockNumbers.ecip1099BlockNumber.value.toLong
          ecip should be > 0L // real activation height, not a sentinel, on every ETC chain

          // Just below the boundary: still on the 30000 epoch.
          EthashUtils.epoch(ecip - 1, ecip) shouldBe (ecip - 1) / EpochLenBeforeEcip1099
          // At the boundary: the 60000 epoch takes effect.
          EthashUtils.epoch(ecip, ecip) shouldBe ecip / EpochLenAfterEcip1099
          // Well past the boundary: still 60000.
          EthashUtils.epoch(
            ecip + EpochLenAfterEcip1099,
            ecip
          ) shouldBe (ecip + EpochLenAfterEcip1099) / EpochLenAfterEcip1099
        }
      }
    }

    "keep ETH-family chains on the default 30000 epoch at every realistic height (ecip1099 parked at the pending sentinel)" in {
      // ETH never adopted ECIP-1099: its ecip1099BlockNumber is the 1e18 sentinel, so the doubled epoch never activates.
      val ethHeights = List(1L, 4_370_000L, 15_537_394L, 21_000_000L) // Byzantium-ish, the Merge, well past
      ethFamily.foreach { n =>
        withClue(s"[$n] ") {
          val ecip = confs(n).forkBlockNumbers.ecip1099BlockNumber.value
          ecip shouldBe BigInt("1000000000000000000") // pending sentinel
          val ecipL = ecip.toLong
          ethHeights.foreach { h =>
            EthashUtils.epoch(h, ecipL) shouldBe h / EpochLenBeforeEcip1099
          }
        }
      }
    }
  }

  "ConsensusEngine.engineFor (resolved wrapper vs pre-5.4 path)" should {

    "wrap the ETC-family engine so its header validator IS the mining's own (same instance as the pre-5.4 path)" in new TestSetup:
      val engine = ConsensusEngine.engineFor(mining, confs("etc"))
      engine.id shouldBe EngineId.Ethash
      engine.sealer shouldBe Some(mining) // PoW self-seals
      (engine.headerValidator should be).theSameInstanceAs(mining.validators.blockHeaderValidator)

    "wrap the ETH-family engine so its header validator IS the transition validator (spans the Merge, one instance)" in new TestSetup:
      val engine = ConsensusEngine.engineFor(mining, confs("eth"))
      engine.id shouldBe EngineId.EngineApi
      engine.sealer shouldBe None // PoS blocks come from the CL, not self-sealed
      // Same object the pre-5.4 ValidatorsExecutor resolves for Protocol.EngineApi — the per-header transition router.
      (engine.headerValidator should be).theSameInstanceAs(ValidatorsExecutor(Protocol.EngineApi).blockHeaderValidator)

    "finalize a PoW block through the resolved ETC engine identically to a direct payBlockReward call" in new TestSetup:
      implicit val cfg: BlockchainConfig = confs("etc")
      val engine = ConsensusEngine.engineFor(mining, cfg)
      val block = powBlock(minerA, Seq(minerB, minerC))
      val throughEngine = engine.finalizeBlock(block, worldState)
      val direct = mining.blockPreparator.payBlockReward(block, worldState)
      throughEngine.stateRootHash shouldEqual direct.stateRootHash

    "finalize a PoS block through the resolved ETH engine identically to a direct payBlockReward call (no reward, base fee burned)" in new TestSetup:
      implicit val cfg: BlockchainConfig = confs("eth")
      val engine = ConsensusEngine.engineFor(mining, cfg)
      val block = posBlock(minerA)
      val throughEngine = engine.finalizeBlock(block, worldState)
      val direct = mining.blockPreparator.payBlockReward(block, worldState)
      throughEngine.stateRootHash shouldEqual direct.stateRootHash
      throughEngine.stateRootHash shouldEqual worldState.stateRootHash // isPoS early return: world unchanged

    // Stage 5.4c-3 header-cutover byte-identity guard. BlockValidation now sources the pre-execution header (seal)
    // validator from `consensusEngine.headerValidator` instead of `mining.validators.blockHeaderValidator`. That
    // redirect is byte-identical ONLY IF the two are the SAME instance for every conf the client ships. Prove it for
    // all 5 real confs, pairing each engine with a mining whose validators were resolved the production way
    // (`ValidatorsExecutor(Protocol.PoW)` — TTD-aware per 5.4c-1): ETC → EthashEngine returns exactly that field;
    // ETH → EngineApiEngine returns the singleton TransitionBlockHeaderValidator, which is also what the TTD-aware
    // validators resolve. If this ever fails, the header cutover has stopped being a no-op.
    "source the header validator identically to the mining's own for all 5 real confs (byte-identical redirect)" in new TestSetup:
      confNames.foreach { n =>
        withClue(s"[$n] ") {
          val cfg = confs(n)
          val perConfMining = mining.withValidators(ValidatorsExecutor(Protocol.PoW)(using cfg))
          val engine = ConsensusEngine.engineFor(perConfMining, cfg)
          (engine.headerValidator should be).theSameInstanceAs(perConfMining.validators.blockHeaderValidator)
        }
      }
  }

  trait TestSetup extends EphemBlockchainTestSetup:
    override lazy val vm: VMImpl = new MockVM()

    val minerA: Address = Address(0xababab)
    val minerB: Address = Address(0xcdcdcd)
    val minerC: Address = Address(0xefefef)

    implicit override lazy val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

    val worldState: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(BlockNumber(-1)),
      (number: BlockNumber) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
      .saveAccount(minerA, Account(balance = 10))
      .saveAccount(minerB, Account(balance = 20))
      .saveAccount(minerC, Account(balance = 30))

    private val sampleBlockNumber = 10

    def powBlock(minerAddress: Address, ommerMiners: Seq[Address] = Nil): Block =
      Block(
        header = Fixtures.Blocks.Genesis.header.copy(
          beneficiary = minerAddress.bytes,
          number = BlockNumber(sampleBlockNumber)
        ),
        body = Fixtures.Blocks.Genesis.body.copy(
          uncleNodesList = ommerMiners.map { address =>
            Fixtures.Blocks.Genesis.header.copy(beneficiary = address.bytes, number = BlockNumber(5))
          }
        )
      )

    // isPoS = difficulty == Zero && baseFee.isDefined — HefPostOlympia supplies the base fee.
    def posBlock(minerAddress: Address): Block =
      Block(
        header = Fixtures.Blocks.Genesis.header.copy(
          beneficiary = minerAddress.bytes,
          number = BlockNumber(sampleBlockNumber),
          difficulty = Difficulty.Zero,
          extraFields = HefPostOlympia(BaseFeePerGas(BigInt("1000000000")))
        ),
        body = Fixtures.Blocks.Genesis.body
      )
