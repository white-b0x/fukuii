package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.evm.ForkActivation
import com.chipprbots.fukuii.evm.ForkSchedule
import com.chipprbots.fukuii.evm.ProposalId.Eip
import com.chipprbots.fukuii.storage.EphemDataSource
import com.chipprbots.fukuii.trie.InMemoryMptStorage
import com.chipprbots.fukuii.trie.MptNode

/** L4 P1 — the [[ProtocolSpec]] bundle spine: the fork is resolved once (wrapping L3's `forBlock`) at activation
  * heights on **both** fork clocks; `RequestProcessors.noOp` is the only empty-map path; an unresolved [[RewardScheme]]
  * fails loud; `PosNoRewardScheme` is inert. No reward-value / request-value assertions (no math until P4/P5).
  */
class ProtocolSpecSpec extends AnyFunSuite:

  private def mkHeader(number: BigInt, timestamp: Long): BlockHeader =
    BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = Address.Zero,
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 0,
      number = number,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = timestamp,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty
    )

  private def emptyWorld: InMemoryWorldState =
    InMemoryWorldState(
      codeStorage = new CodeStorage(EphemDataSource()),
      mptStorage = new InMemoryMptStorage,
      getBlockHashByNumber = _ => None,
      accountStartNonce = UInt256.Zero,
      stateRootHash = MptNode.EmptyRootHash,
      noEmptyAccounts = true
    )

  // A block-clock proposal (EIP-1559 @ block 100) and a timestamp-clock proposal (EIP-3651 @ ts 2000) — one schedule
  // exercises both dispatch axes, so we can assert the bundle resolves the right EvmConfig on each clock.
  private val schedule: ForkSchedule =
    ForkSchedule(
      Map(
        Eip(1559) -> ForkActivation.ByBlock(BigInt(100)),
        Eip(3651) -> ForkActivation.ByTimestamp(2000L)
      )
    )

  private val powSchedule: ProtocolSchedule =
    ProtocolSchedule(
      forkSchedule = schedule,
      rewardScheme = RewardScheme.Ecip1017RewardScheme(),
      requests = RequestProcessors.noOp,
      withdrawals = None,
      feeDisposition = FeeDisposition.Absent
    )

  test("getByBlockHeader resolves the block-clock fork once — inactive below the height, active at/above it"):
    val below = powSchedule.getByBlockHeader(mkHeader(number = 50, timestamp = 0))
    val at = powSchedule.getByBlockHeader(mkHeader(number = 100, timestamp = 0))
    assert(!below.evmConfig.isActive(Eip(1559)) && at.evmConfig.isActive(Eip(1559)))

  test("getByBlockHeader resolves the timestamp-clock fork once — inactive below the ts, active at/above it"):
    val below = powSchedule.getByBlockHeader(mkHeader(number = 0, timestamp = 1000))
    val at = powSchedule.getByBlockHeader(mkHeader(number = 0, timestamp = 2000))
    assert(!below.evmConfig.isActive(Eip(3651)) && at.evmConfig.isActive(Eip(3651)))

  test("getForNextBlockHeader resolves the producer's next spec on both clocks"):
    val byBlock = powSchedule.getForNextBlockHeader(number = 100, timestamp = 0)
    val byTs = powSchedule.getForNextBlockHeader(number = 0, timestamp = 2000)
    assert(byBlock.evmConfig.isActive(Eip(1559)) && byTs.evmConfig.isActive(Eip(3651)))

  test("the bundle carries the network's economics collaborators"):
    val spec = powSchedule.getByBlockHeader(mkHeader(number = 0, timestamp = 0))
    assert(
      spec.rewardScheme == RewardScheme.Ecip1017RewardScheme() &&
        spec.feeDisposition == FeeDisposition.Absent &&
        spec.requests.isNoOp &&
        spec.withdrawals.isEmpty
    )

  test("RequestProcessors.noOp is the empty-map path and reports isNoOp"):
    assert(RequestProcessors.noOp.isNoOp && RequestProcessors.noOp.processorFor(RequestType.Deposit).isEmpty)

  test("RequestProcessors.build fails LOUD on an accidental empty map (besu build:66-73)"):
    val ex = intercept[RuntimeException](RequestProcessors.build(Map.empty))
    assert(ex.getMessage.contains("empty processor map") && ex.getMessage.contains("RequestProcessors.noOp"))

  test("RequestProcessors.build accepts a non-empty map"):
    val deposit: RequestProcessor = new RequestProcessor:
      def requestType: RequestType = RequestType.Deposit
    val built = RequestProcessors.build(Map(RequestType.Deposit -> deposit))
    assert(!built.isNoOp && built.processorFor(RequestType.Deposit).contains(deposit))

  test("RewardScheme.require fails LOUD on an unresolved scheme — never a silent zero"):
    intercept[RuntimeException](RewardScheme.require(None, "test-network"))

  test("RewardScheme.require passes a resolved scheme through"):
    assert(RewardScheme.require(Some(RewardScheme.PosNoRewardScheme), "test-network") == RewardScheme.PosNoRewardScheme)

  test("PosNoRewardScheme is inert — zero reward, skipZeroBlockRewards, and rewardBlock leaves the world untouched"):
    val world = emptyWorld
    val rewarded = RewardScheme.PosNoRewardScheme.rewardBlock(world, mkHeader(0, 0), Seq.empty)
    // no coinbase touch: the same world instance back, state root unchanged (no spurious addBalance(0)).
    assert(
      RewardScheme.PosNoRewardScheme.blockReward == BigInt(0) &&
        RewardScheme.PosNoRewardScheme.skipZeroBlockRewards &&
        (rewarded eq world) &&
        rewarded.stateRootHash == world.stateRootHash
    )

  test(
    "Ecip1017RewardScheme is implemented (P4a) — era-0 credits the coinbase (full vectors in Ecip1017RewardSchemeSpec)"
  ):
    val coinbase = Address(ByteString(Array.fill[Byte](Address.Length)(0x33)))
    val rewarded = RewardScheme
      .Ecip1017RewardScheme()
      .rewardBlock(emptyWorld, mkHeader(0, 0).copy(beneficiary = coinbase), Seq.empty)
    // era 0 (block 0 → guard → era 0): the base 5 ETH.
    assert(rewarded.getBalance(coinbase).toBigInt == BigInt(5) * BigInt(10).pow(18))
