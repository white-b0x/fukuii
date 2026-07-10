package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks.MockVM
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** Proves Stage 5.4a's wrap is behavior-preserving: going THROUGH a [[ConsensusEngine]] produces the same
  * header-validation and finalization results as calling the pre-5.4 path (`ValidatorsExecutor` /
  * `BlockPreparator.payBlockReward`) directly. If these ever diverge, the seam has stopped being a pass-through.
  */
class ConsensusEngineByteIdentitySpec extends AnyFlatSpec with Matchers:

  "EthashEngine" should "expose the wrapped mining's own header validator (same instance)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val engine = new EthashEngine(mining)
    // The engine adds nothing: its validator IS the mining's validator, byte-for-byte the pre-5.4 object.
    (engine.headerValidator should be).theSameInstanceAs(mining.validators.blockHeaderValidator)

  it should "seal (Some) and produce the wrapped mining's own block generator" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val engine = new EthashEngine(mining)
    engine.id shouldBe EngineId.Ethash
    engine.sealer shouldBe Some(mining)
    (engine.blockGenerator should be).theSameInstanceAs(mining.blockGenerator)

  it should "finalize a PoW block identically to a direct payBlockReward call" taggedAs (
    UnitTest,
    ConsensusTest,
    StateTest
  ) in new TestSetup:
    val engine = new EthashEngine(mining)
    val block = powBlock(validAccountAddress, Seq(validAccountAddress2, validAccountAddress3))

    val throughEngine: InMemoryWorldStateProxy = engine.finalizeBlock(block, worldState)
    val direct: InMemoryWorldStateProxy = mining.blockPreparator.payBlockReward(block, worldState)

    throughEngine.stateRootHash shouldEqual direct.stateRootHash
    // Concretely: ECIP-1017 miner+ommer reward is credited exactly as before.
    throughEngine.getGuaranteedAccount(Address(block.header.beneficiary)).balance shouldEqual
      direct.getGuaranteedAccount(Address(block.header.beneficiary)).balance

  "EngineApiEngine" should "route header validation through the transition validator, unchanged" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val engine = new EngineApiEngine(mining)
    engine.id shouldBe EngineId.EngineApi
    engine.sealer shouldBe None
    // Same object the pre-5.4 ValidatorsExecutor resolves for Protocol.EngineApi.
    (engine.headerValidator should be).theSameInstanceAs(ValidatorsExecutor(Protocol.EngineApi).blockHeaderValidator)

  it should "finalize a PoS block with no reward and burned base fee (world unchanged)" taggedAs (
    UnitTest,
    ConsensusTest,
    StateTest
  ) in new TestSetup:
    val engine = new EngineApiEngine(mining)
    val block = posBlock(validAccountAddress)

    val throughEngine: InMemoryWorldStateProxy = engine.finalizeBlock(block, worldState)
    val direct: InMemoryWorldStateProxy = mining.blockPreparator.payBlockReward(block, worldState)

    // isPoS early return: no miner reward, no ommer reward, no Treasury credit — base fee burned.
    throughEngine.stateRootHash shouldEqual direct.stateRootHash
    throughEngine.stateRootHash shouldEqual worldState.stateRootHash
    throughEngine.getGuaranteedAccount(Address(block.header.beneficiary)).balance shouldEqual
      worldState.getGuaranteedAccount(Address(block.header.beneficiary)).balance

  trait TestSetup extends EphemBlockchainTestSetup:
    override lazy val vm: VMImpl = new MockVM()

    val validAccountAddress: Address = Address(0xababab)
    val validAccountAddress2: Address = Address(0xcdcdcd)
    val validAccountAddress3: Address = Address(0xefefef)

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
      .saveAccount(validAccountAddress, Account(balance = 10))
      .saveAccount(validAccountAddress2, Account(balance = 20))
      .saveAccount(validAccountAddress3, Account(balance = 30))

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
