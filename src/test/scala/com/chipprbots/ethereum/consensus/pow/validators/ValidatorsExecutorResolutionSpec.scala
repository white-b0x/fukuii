package com.chipprbots.ethereum.consensus.pow.validators

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.pos.PoSBlockHeaderValidator
import com.chipprbots.ethereum.consensus.TransitionBlockHeaderValidator
import com.chipprbots.ethereum.consensus.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderDifficultyError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NetworkType

/** Stage 5.4c-1: [[ValidatorsExecutor.apply]] selects the block-header validator from the merge signal
  * (`terminalTotalDifficulty`), NOT the `protocol` string.
  *
  * Both ETC and ETH run `protocol=pow` — the string cannot distinguish them (verdict H2). The discriminator is the TTD:
  * a chain that defines one (ETH/Sepolia) spans PoW→PoS and gets [[TransitionBlockHeaderValidator]] (per-header Ethash
  * for difficulty>0, PoS for difficulty==0); a chain without one (ETC/Mordor/gorgoroth) keeps unconditional Ethash
  * sealing via [[PoWBlockHeaderValidator]]. This mirrors go-ethereum's `consensus/beacon` and Besu's
  * `getTerminalTotalDifficulty().isPresent()` transition-schedule guard.
  *
  * The change is surgical: only the `Protocol.PoW` arm became TTD-aware. `MockedPow`, `RestrictedPoW`, and `EngineApi`
  * resolve exactly as before, regardless of TTD.
  */
// scalastyle:off magic.number
class ValidatorsExecutorResolutionSpec extends AnyWordSpec with Matchers:

  private val etcFamily = List("etc", "mordor", "gorgoroth")
  private val ethFamily = List("eth", "sepolia")
  private val confs: Map[String, BlockchainConfig] =
    (etcFamily ::: ethFamily).map(n => n -> Config.blockchains.blockchains(n)).toMap

  private def headerValidatorFor(protocol: Protocol, conf: BlockchainConfig) =
    ValidatorsExecutor(protocol)(using conf).blockHeaderValidator

  private val EmptyNonce: ByteString = ByteString(Array.fill[Byte](8)(0))

  "ValidatorsExecutor.apply (TTD-aware header-validator selection)" should {

    "resolve every ETC-family conf (no TTD) to PoWBlockHeaderValidator — unchanged, one shared instance" in {
      etcFamily.foreach { n =>
        withClue(s"[$n] ") {
          confs(n).networkType shouldBe NetworkType.ETC
          confs(n).terminalTotalDifficulty shouldBe None
          (headerValidatorFor(Protocol.PoW, confs(n)) should be).theSameInstanceAs(PoWBlockHeaderValidator)
        }
      }
    }

    "resolve every ETH-family conf (TTD defined) to TransitionBlockHeaderValidator" in {
      ethFamily.foreach { n =>
        withClue(s"[$n] ") {
          confs(n).networkType shouldBe NetworkType.ETH
          confs(n).terminalTotalDifficulty shouldBe defined
          (headerValidatorFor(Protocol.PoW, confs(n)) should be).theSameInstanceAs(TransitionBlockHeaderValidator)
        }
      }
    }

    "leave the non-PoW protocol arms untouched by TTD (only the PoW arm became TTD-aware)" in {
      // Even under an ETH conf whose TTD is defined, these arms resolve exactly as before the change.
      val ethConf = confs("eth")
      (headerValidatorFor(Protocol.MockedPow, ethConf) should be).theSameInstanceAs(MockedPowBlockHeaderValidator)
      (headerValidatorFor(Protocol.RestrictedPoW, ethConf) should be)
        .theSameInstanceAs(RestrictedEthashBlockHeaderValidator)
      (headerValidatorFor(Protocol.EngineApi, ethConf) should be).theSameInstanceAs(TransitionBlockHeaderValidator)
      // And under an ETC conf (no TTD), EngineApi still routes to the transition validator (unchanged).
      (headerValidatorFor(Protocol.EngineApi, confs("etc")) should be).theSameInstanceAs(TransitionBlockHeaderValidator)
    }
  }

  "The resolved ETH header validator (per-header dispatch through the transition router)" should {

    // A post-merge (difficulty==0) header that satisfies the PoS invariants, at a pre-Shanghai timestamp so no
    // withdrawalsRoot/blob-gas fields are required.
    val posHeader: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
      difficulty = Difficulty.Zero,
      nonce = EmptyNonce,
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(1L),
      extraData = ByteString("t".getBytes),
      extraFields = HefEmpty
    )

    // A pre-merge (difficulty>0) header — routed to the Ethash path, not the PoS path.
    val powHeader: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
      difficulty = Difficulty(BigInt(17_000_000_000L)),
      unixTimestamp = Timestamp(1L)
    )

    "accept a difficulty==0 header via the PoS branch (NOT reject it), identically to PoSBlockHeaderValidator" in {
      implicit val cfg: BlockchainConfig = confs("eth")
      val ethValidator = headerValidatorFor(Protocol.PoW, cfg)
      ethValidator.validateHeaderOnly(posHeader) shouldBe Right(BlockHeaderValid)
      ethValidator.validateHeaderOnly(posHeader) shouldBe PoSBlockHeaderValidator.validateHeaderOnly(posHeader)
    }

    "route a difficulty>0 header through the Ethash branch, identically to PoWBlockHeaderValidator" in {
      implicit val cfg: BlockchainConfig = confs("eth")
      val ethValidator = headerValidatorFor(Protocol.PoW, cfg)
      // Identical result to the PoW validator proves Ethash-branch routing...
      ethValidator.validateHeaderOnly(powHeader) shouldBe PoWBlockHeaderValidator.validateHeaderOnly(powHeader)
      // ...and it is NOT rejected as a PoS difficulty violation (which is what a PoS-branch route would yield).
      ethValidator.validateHeaderOnly(powHeader) should not be Left(HeaderDifficultyError)
    }
  }
// scalastyle:on magic.number
