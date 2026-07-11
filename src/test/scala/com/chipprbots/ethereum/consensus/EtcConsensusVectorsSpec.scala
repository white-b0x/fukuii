package com.chipprbots.ethereum.consensus

import scala.io.Source

import com.typesafe.config.ConfigFactory
import io.circe.ACursor
import io.circe.Json
import io.circe.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.ledger.BlockRewardCalculator
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Cross-client ETC consensus field-identity oracle.
  *
  * Parses the vendored `etc_consensus_vectors.json` — core-geth's cross-client vector file that names core-geth, besu,
  * AND fukuii as consumers ("Cross-client ETC consensus test vectors. Consumed by core-geth, besu, and fukuii.") — and
  * asserts that fukuii's SHIPPED config and impl values equal the vector's scalar fields. This binds fukuii to the
  * shared oracle: if core-geth or besu revise a value, this spec fails on drift rather than fukuii silently diverging.
  *
  * Byte-value authority is core-geth throughout (`params/config_classic.go`, `params/config_mordor.go`). A DISAGREEMENT
  * between a fukuii value and the vector is a real consensus finding, not a test to relax.
  *
  * SCOPE: this is the scalar field-identity harness (rewards, epochs, difficulty constants, MESS windows, chain IDs,
  * fork blocks, genesis hashes). It is NOT a state-root harness — post-state trie roots for ETC-specific forks are
  * exercised by the FixtureProvider replays in `it/txExecTest/{ForksTest,ECIP1017Test}` and tracked for deeper
  * offline-vector coverage under batch-6 row `ETC-VECTORS-VENDOR-01`.
  *
  * Overlap is deliberate, not redundant:
  * `ChainConfigValidationSpec`/`ETCDaoExclusionSpec`/`ECIP1017EmissionScheduleSpec` hardcode the expected values
  * inline; THIS spec reads them from the external cross-client file, so it is the drift detector against the shared
  * authority. It also extends reward coverage to all 6 emission eras (block + uncle), beyond the eras 0-4 pinned inline
  * elsewhere.
  *
  * The Olympia vectors (`olympia_consensus_vectors.json`) are vendored alongside but are largely for a fork that is not
  * yet scheduled on ETC (`olympia-block-number` = 10^18 placeholder); see the Olympia section below for what maps today
  * and the recorded treasury-address disagreement.
  */
class EtcConsensusVectorsSpec extends AnyFlatSpec with Matchers:

  // ===== fukuii shipped configs (loaded exactly as production does) =====
  private val fullConfig = ConfigFactory.load()
  private val etcConfig: BlockchainConfig =
    BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.etc"))
  private val mordorConfig: BlockchainConfig =
    BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.mordor"))

  // ETC byzantium/constantinople are the pending sentinel (10^18) on the ETC-family confs, so the
  // reward calculator uses firstEraBlockReward — matching ECIP-1017 emission exactly.
  private val etcRewardCalc = new BlockRewardCalculator(
    etcConfig.monetaryPolicyConfig,
    etcConfig.forkBlockNumbers.byzantiumBlockNumber,
    etcConfig.forkBlockNumbers.constantinopleBlockNumber
  )
  private val mordorRewardCalc = new BlockRewardCalculator(
    mordorConfig.monetaryPolicyConfig,
    mordorConfig.forkBlockNumbers.byzantiumBlockNumber,
    mordorConfig.forkBlockNumbers.constantinopleBlockNumber
  )

  // ===== vector file (vendored from core-geth tests/live_etc/testdata) =====
  private val vectors: Json = loadVectors("consensus-vectors/etc_consensus_vectors.json")

  private def loadVectors(resource: String): Json =
    val src = Source.fromResource(resource)
    try parser.parse(src.mkString).fold(err => fail(s"failed to parse $resource: $err"), identity)
    finally src.close()

  private def at(json: Json, keys: String*): ACursor = keys.foldLeft(json.hcursor: ACursor)((c, k) => c.downField(k))
  private def bigStr(c: ACursor): BigInt = BigInt(
    c.as[String].fold(e => fail(s"expected string-encoded BigInt: $e"), identity)
  )
  private def long(c: ACursor): Long = c.as[Long].fold(e => fail(s"expected Long: $e"), identity)
  private def str(c: ACursor): String = c.as[String].fold(e => fail(s"expected String: $e"), identity)

  // -----------------------------------------------------------------------
  // ECIP-1017 emission — era lengths, per-era block reward + uncle inclusion (all 6 eras)
  // -----------------------------------------------------------------------

  "etc_consensus_vectors.json ecip1017_rewards" should "match fukuii's ETC and Mordor era durations" taggedAs (
    UnitTest,
    EthereumTest,
    ConsensusTest
  ) in {
    long(
      at(vectors, "ecip1017_rewards", "mainnet_era_length")
    ) shouldBe etcConfig.monetaryPolicyConfig.eraDuration.toLong
    long(
      at(vectors, "ecip1017_rewards", "mordor_era_length")
    ) shouldBe mordorConfig.monetaryPolicyConfig.eraDuration.toLong
  }

  it should "match fukuii's per-era block reward and uncle-inclusion wei on BOTH the 5M and 2M schedules" taggedAs (
    UnitTest,
    EthereumTest,
    ConsensusTest
  ) in {
    val etcEra = etcConfig.monetaryPolicyConfig.eraDuration.toLong // 5,000,000
    val mordorEra = mordorConfig.monetaryPolicyConfig.eraDuration.toLong // 2,000,000

    val eraVectors =
      at(vectors, "ecip1017_rewards", "vectors").values.getOrElse(fail("ecip1017_rewards.vectors missing"))
    eraVectors.foreach { v =>
      val era = long(v.hcursor.downField("era"))
      val blockRewardWei = bigStr(v.hcursor.downField("block_reward_wei"))
      val uncleInclusionWei = bigStr(v.hcursor.downField("uncle_inclusion_wei"))

      // First block of era N on each schedule (N * eraLength + 1).
      val etcFirstBlock = BlockNumber(BigInt(era) * etcEra + 1)
      val mordorFirstBlock = BlockNumber(BigInt(era) * mordorEra + 1)

      withClue(s"era $era block reward (ETC 5M schedule): ") {
        etcRewardCalc.calculateMiningRewardForBlock(etcFirstBlock).value shouldBe blockRewardWei
      }
      withClue(s"era $era block reward (Mordor 2M schedule): ") {
        mordorRewardCalc.calculateMiningRewardForBlock(mordorFirstBlock).value shouldBe blockRewardWei
      }
      withClue(s"era $era uncle inclusion (ETC 5M schedule): ") {
        etcRewardCalc.calculateMiningRewardForOmmers(etcFirstBlock, 1).value shouldBe uncleInclusionWei
      }
      withClue(s"era $era uncle inclusion (Mordor 2M schedule): ") {
        mordorRewardCalc.calculateMiningRewardForOmmers(mordorFirstBlock, 1).value shouldBe uncleInclusionWei
      }
    }
  }

  // -----------------------------------------------------------------------
  // ECIP-1099 — DAG epoch length doubling (30k -> 60k) and epoch = block / epochLength
  // -----------------------------------------------------------------------

  "etc_consensus_vectors.json ecip1099_epochs" should "match fukuii's epoch-length constants" taggedAs (
    UnitTest,
    EthereumTest,
    ConsensusTest
  ) in {
    // Every vector uses epoch_length 30000 (pre-1099) or 60000 (post-1099).
    EthashUtils.EPOCH_LENGTH_BEFORE_ECIP_1099.toLong shouldBe 30000L
    EthashUtils.EPOCH_LENGTH_AFTER_ECIP_1099.toLong shouldBe 60000L
  }

  it should "match fukuii's epoch computation (epoch = block / epoch_length) for every vector" taggedAs (
    UnitTest,
    EthereumTest,
    ConsensusTest
  ) in {
    val epochVectors =
      at(vectors, "ecip1099_epochs", "vectors").values.getOrElse(fail("ecip1099_epochs.vectors missing"))
    epochVectors.foreach { v =>
      val block = long(v.hcursor.downField("block"))
      val epochLength = long(v.hcursor.downField("epoch_length"))
      val expectedEpoch = long(v.hcursor.downField("expected_epoch"))

      // The vector self-describes which regime applies via epoch_length. Drive fukuii's real
      // EthashUtils.epoch with an activation that forces that regime, so we test the actual math
      // (block / epochLength) network-agnostically. The real per-network activation blocks are
      // asserted separately via fork_blocks.thanos below.
      val activation = if epochLength == 30000L then Long.MaxValue else 0L
      withClue(s"epoch(block=$block, epochLength=$epochLength): ") {
        EthashUtils.epoch(block, activation) shouldBe expectedEpoch
      }
    }
  }

  it should "compute the real epoch at the classic and Mordor ECIP-1099 boundaries" taggedAs (
    UnitTest,
    EthereumTest,
    ConsensusTest
  ) in {
    val classicActivation = etcConfig.forkBlockNumbers.ecip1099BlockNumber.value.toLong // 11,700,000
    val mordorActivation = mordorConfig.forkBlockNumbers.ecip1099BlockNumber.value.toLong // 2,520,000

    // classic: 11,699,999 -> epoch 389 (30k regime); 11,700,000 -> epoch 195 (60k regime)
    EthashUtils.epoch(11699999L, classicActivation) shouldBe 389L
    EthashUtils.epoch(11700000L, classicActivation) shouldBe 195L
    // mordor: 2,519,999 -> epoch 83 (30k regime); 2,520,000 -> epoch 42 (60k regime)
    EthashUtils.epoch(2519999L, mordorActivation) shouldBe 83L
    EthashUtils.epoch(2520000L, mordorActivation) shouldBe 42L
  }

  // -----------------------------------------------------------------------
  // Difficulty constraints
  // -----------------------------------------------------------------------

  "etc_consensus_vectors.json difficulty_constraints" should "match fukuii's minimum difficulty and bomb schedule" taggedAs (
    UnitTest,
    EthereumTest,
    ConsensusTest
  ) in {
    long(
      at(vectors, "difficulty_constraints", "minimum_difficulty")
    ) shouldBe DifficultyCalculator.MinimumDifficulty.value.toLong

    // ECIP-1010/1041 bomb schedule on classic.
    val pause = long(at(vectors, "difficulty_constraints", "bomb_pause_block_classic"))
    val pauseLength = long(at(vectors, "difficulty_constraints", "bomb_pause_length_classic"))
    val disposal = long(at(vectors, "difficulty_constraints", "bomb_disposal_block_classic"))

    etcConfig.forkBlockNumbers.difficultyBombPauseBlockNumber shouldBe BlockNumber(pause)
    // continue = pause + pauseLength
    etcConfig.forkBlockNumbers.difficultyBombContinueBlockNumber shouldBe BlockNumber(pause + pauseLength)
    etcConfig.forkBlockNumbers.difficultyBombRemovalBlockNumber shouldBe BlockNumber(disposal)
  }

  // -----------------------------------------------------------------------
  // ECBP-1100 (MESS) activation windows
  // -----------------------------------------------------------------------

  "etc_consensus_vectors.json ecbp1100_windows" should "match fukuii's MESS activation/deactivation windows" taggedAs (
    UnitTest,
    EthereumTest,
    ConsensusTest
  ) in {
    etcConfig.messConfig.activationBlock shouldBe Some(
      BlockNumber(long(at(vectors, "ecbp1100_windows", "classic", "activation")))
    )
    etcConfig.messConfig.deactivationBlock shouldBe Some(
      BlockNumber(long(at(vectors, "ecbp1100_windows", "classic", "deactivation")))
    )
    mordorConfig.messConfig.activationBlock shouldBe Some(
      BlockNumber(long(at(vectors, "ecbp1100_windows", "mordor", "activation")))
    )
    mordorConfig.messConfig.deactivationBlock shouldBe Some(
      BlockNumber(long(at(vectors, "ecbp1100_windows", "mordor", "deactivation")))
    )
  }

  // -----------------------------------------------------------------------
  // Chain identifiers
  // -----------------------------------------------------------------------

  "etc_consensus_vectors.json chain_identifiers" should "match fukuii's chain and network IDs" taggedAs (
    UnitTest,
    EthereumTest,
    ConsensusTest
  ) in {
    etcConfig.chainId.value shouldBe BigInt(long(at(vectors, "chain_identifiers", "classic", "chain_id")))
    etcConfig.networkId shouldBe long(at(vectors, "chain_identifiers", "classic", "network_id"))
    mordorConfig.chainId.value shouldBe BigInt(long(at(vectors, "chain_identifiers", "mordor", "chain_id")))
    mordorConfig.networkId shouldBe long(at(vectors, "chain_identifiers", "mordor", "network_id"))
  }

  // -----------------------------------------------------------------------
  // Fork block numbers
  // -----------------------------------------------------------------------

  "etc_consensus_vectors.json fork_blocks.classic" should "match fukuii's ETC fork block numbers" taggedAs (
    UnitTest,
    EthereumTest,
    ConsensusTest
  ) in {
    val f = etcConfig.forkBlockNumbers
    def classic(k: String): BlockNumber = BlockNumber(long(at(vectors, "fork_blocks", "classic", k)))

    f.homesteadBlockNumber shouldBe classic("homestead")
    etcConfig.daoForkConfig.map(_.forkBlockNumber) shouldBe Some(classic("dao_fork_rejected"))
    f.eip150BlockNumber shouldBe classic("tangerine_whistle")
    f.difficultyBombPauseBlockNumber shouldBe classic("diehard")
    f.difficultyBombContinueBlockNumber shouldBe classic("gotham")
    f.difficultyBombRemovalBlockNumber shouldBe classic("defuse")
    f.atlantisBlockNumber shouldBe classic("atlantis")
    f.aghartaBlockNumber shouldBe classic("agharta")
    f.phoenixBlockNumber shouldBe classic("phoenix")
    f.ecip1099BlockNumber shouldBe classic("thanos") // ECIP-1099 (Thanos) epoch-doubling block
    f.magnetoBlockNumber shouldBe classic("magneto")
    f.mystiqueBlockNumber shouldBe classic("mystique")
    f.spiralBlockNumber shouldBe classic("spiral")
  }

  "etc_consensus_vectors.json fork_blocks.mordor" should "match fukuii's Mordor fork block numbers" taggedAs (
    UnitTest,
    EthereumTest,
    ConsensusTest
  ) in {
    val f = mordorConfig.forkBlockNumbers
    def mordor(k: String): BlockNumber = BlockNumber(long(at(vectors, "fork_blocks", "mordor", k)))

    f.atlantisBlockNumber shouldBe mordor("atlantis")
    f.aghartaBlockNumber shouldBe mordor("agharta")
    f.phoenixBlockNumber shouldBe mordor("phoenix")
    f.ecip1099BlockNumber shouldBe mordor("thanos")
    f.magnetoBlockNumber shouldBe mordor("magneto")
    f.mystiqueBlockNumber shouldBe mordor("mystique")
    f.spiralBlockNumber shouldBe mordor("spiral")
  }

  // -----------------------------------------------------------------------
  // Fields NOT asserted here (documented, not silently dropped)
  // -----------------------------------------------------------------------
  //   - genesis_hashes: computing the genesis block hash requires loading the genesis JSON and
  //     RLP-hashing the header — not a cheap scalar accessor. Deferred to ETC-VECTORS-VENDOR-01.
  //   - precompiles_per_fork: mapping requires building EvmConfig.forBlock per fork and enumerating
  //     active precompile addresses. Deferred to ETC-VECTORS-VENDOR-01.

  // -----------------------------------------------------------------------
  // Olympia vectors (unscheduled fork — olympia-block-number = 10^18 placeholder)
  // -----------------------------------------------------------------------

  private val olympiaVectors: Json = loadVectors("consensus-vectors/olympia_consensus_vectors.json")

  "olympia_consensus_vectors.json" should "pin fukuii's ECIP-1112 treasury address (documenting the vector-file divergence)" taggedAs (
    UnitTest,
    EthereumTest,
    OlympiaTest
  ) in {
    // Olympia is unscheduled on ETC (olympia-block-number = 10^18), so the treasury address is not
    // yet consensus-active. The core-geth vector lists a placeholder (0xCfE1..bEe2) that disagrees
    // with fukuii's ECIP-1112 address (0x60d0..d79b, blockchains.conf `fukuii.olympia.treasury-address`).
    // We pin fukuii's own value and assert the divergence is still present, rather than asserting
    // equality against a placeholder — so this fails loudly if either side changes before Olympia is
    // scheduled and the ECIP-1112 address is finalized.
    val vectorTreasury = str(at(olympiaVectors, "olympia_treasury", "treasury_address")).toLowerCase
    val fukuiiTreasury = etcConfig.treasuryAddress

    fukuiiTreasury shouldBe Address("0x60d0A7394f9Cd5C469f9F5Ec4F9C803F5294d79b")
    // The vector's placeholder differs from fukuii's ECIP-1112 address — assert the divergence is
    // still present so this test fails loudly (prompting a re-check) if either side changes.
    vectorTreasury should not be fukuiiTreasury.toString.toLowerCase
  }
