package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.storage.EphemDataSource
import com.chipprbots.fukuii.trie.InMemoryMptStorage
import com.chipprbots.fukuii.trie.MptNode

/** L4 P4a — **ECIP-1017 era emission**, the single most consensus-critical item in L4. Every asserted wei value is
  * derived from the two frozen ETC authorities, which agree byte-for-byte:
  *   - **core-geth** (SOLE spec authority) `params/mutations/rewards_classic.go` + `rewards.go` +
  *     `config_classic.go:144-145` (`DisinflationRateQuotient=4`, `Divisor=5`) + `protocol_params.go:27`
  *     (`FrontierBlockReward=5e18`);
  *   - **besu-etc** (cross-check, `eb4248c997`) `ClassicBlockProcessor.java` + `ClassicProtocolSpecs.java:60`
  *     (`MAX_BLOCK_REWARD=Wei.fromEth(5)`).
  *
  * A wrong divisor, era boundary, or Era-0-vs-≥1 uncle/nephew branch is a chain split — the L4 handoff's named hazard.
  */
class Ecip1017RewardSchemeSpec extends AnyFunSuite:

  private val scheme = RewardScheme.Ecip1017RewardScheme()

  private val Eth: BigInt = BigInt(10).pow(18)
  private val FiveEth: BigInt = BigInt(5) * Eth

  private def addr(b: Byte): Address = Address(ByteString(Array.fill[Byte](Address.Length)(b)))
  private val miner: Address = addr(0x33)

  private def emptyWorld: InMemoryWorldState =
    InMemoryWorldState(
      codeStorage = new CodeStorage(EphemDataSource()),
      mptStorage = new InMemoryMptStorage,
      getBlockHashByNumber = _ => None,
      accountStartNonce = UInt256.Zero,
      stateRootHash = MptNode.EmptyRootHash,
      noEmptyAccounts = true
    )

  private def header(number: BigInt, beneficiary: Address = miner): BlockHeader =
    BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = beneficiary,
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 1,
      number = number,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = 0,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty
    )

  // -- era index (core-geth GetBlockEra rewards_classic.go:49-62; besu-etc getBlockEra:105-112 — integer DIVISION) ----

  test("era index is (blockNumber-1)/eraLength — genesis/negative guard to era 0"):
    assert(scheme.blockEra(0) == BigInt(0) && scheme.blockEra(-5) == BigInt(0) && scheme.blockEra(1) == BigInt(0))

  test(
    "era-boundary off-by-one — block 5,000,000 is era 0, block 5,000,001 is era 1 (core-geth rewards_classic.go:55-58)"
  ):
    assert(
      scheme.blockEra(5_000_000) == BigInt(0) && scheme.blockEra(5_000_001) == BigInt(1) &&
        scheme.blockEra(10_000_000) == BigInt(1) && scheme.blockEra(10_000_001) == BigInt(2)
    )

  // -- winner (miner) reward by era (core-geth GetBlockWinnerRewardByEra rewards.go:109-128) --------------------------

  test("winner reward — era 0 is the Frontier base 5 ETH (FrontierBlockReward=5e18)"):
    assert(scheme.winnerRewardByEra(0) == FiveEth)

  test(
    "winner reward — era schedule 5 → 4 → 3.2 → 2.56 ETH via separate integer 4^era/5^era (no BigDecimal.precision)"
  ):
    // 5e18 * 4^era / 5^era, BigInt truncating (Go big.Int.Div / Java BigInteger.divide).
    assert(
      scheme.winnerRewardByEra(1) == BigInt(4) * Eth && // 5e18*4/5
        scheme.winnerRewardByEra(2) == BigInt("3200000000000000000") && // 5e18*16/25 = 3.2e18
        scheme.winnerRewardByEra(3) == BigInt("2560000000000000000") // 5e18*64/125 = 2.56e18
    )

  // -- uncle reward — the Era-0-vs-≥1 switch (core-geth GetBlockUncleRewardByEra rewards.go:81-94) -------------------

  test(
    "uncle reward — era 0 is (uncleNum + 8 - headerNum) * 5e18 / 8 (core-geth rewards.go:85-89 example: 7/8 * 5e18)"
  ):
    // core-geth's own comment example: header 2,534,999, uncle 2,534,998 → (2534998+8-2534999)=7 → 7*5e18/8.
    assert(
      scheme.uncleReward(era = 0, headerNumber = 2_534_999, uncleNumber = 2_534_998) == BigInt(7) * FiveEth / 8 &&
        scheme.uncleReward(era = 0, headerNumber = 2_534_999, uncleNumber = 2_534_998) == BigInt("4375000000000000000")
    )

  test("uncle reward — era ≥1 is winnerRewardByEra(era)/32 (core-geth getEraUncleBlockReward rewards.go:76-78)"):
    // era 1: 4e18/32 = 1.25e17.
    assert(
      scheme.uncleReward(era = 1, headerNumber = 6_000_000, uncleNumber = 5_999_999) == BigInt(4) * Eth / 32 &&
        scheme.uncleReward(era = 1, headerNumber = 6_000_000, uncleNumber = 5_999_999) == BigInt("125000000000000000")
    )

  // -- nephew (winner's uncle-inclusion) bonus (core-geth GetBlockWinnerRewardForUnclesByEra rewards.go:96-105) ------

  test("miner reward — nephew bonus is winnerByEra/32 per included uncle, all eras (core-geth :102)"):
    assert(
      // era 0, 2 ommers: 5e18 + 2*(5e18/32).
      scheme.minerReward(era = 0, ommerCount = 2) == FiveEth + BigInt(2) * (FiveEth / 32) &&
        scheme.minerReward(era = 0, ommerCount = 2) == BigInt("5312500000000000000") &&
        // era 1, 1 ommer: 4e18 + 1*(4e18/32).
        scheme.minerReward(era = 1, ommerCount = 1) == BigInt(4) * Eth + (BigInt(4) * Eth / 32) &&
        scheme.minerReward(era = 1, ommerCount = 1) == BigInt("4125000000000000000")
    )

  test("miner reward — no ommers is just winnerByEra(era)"):
    assert(
      scheme.minerReward(era = 0, ommerCount = 0) == FiveEth &&
        scheme.minerReward(era = 2, ommerCount = 0) == BigInt("3200000000000000000")
    )

  // -- rewardBlock crediting the world (core-geth AccumulateRewards rewards.go:64-72 / besu-etc rewardCoinbase) -------

  test("rewardBlock — era 0, no ommers, credits the miner coinbase the base 5 ETH"):
    val rewarded = scheme.rewardBlock(emptyWorld, header(number = 1), Seq.empty)
    assert(rewarded.getBalance(miner).toBigInt == FiveEth)

  test("rewardBlock — era 1 (block 5,000,001) credits the miner 4 ETH"):
    val rewarded = scheme.rewardBlock(emptyWorld, header(number = 5_000_001), Seq.empty)
    assert(rewarded.getBalance(miner).toBigInt == BigInt(4) * Eth)

  test("rewardBlock — a block with 2 ommers credits miner (winner+nephew) + each ommer coinbase (uncle reward)"):
    val ommerA = addr(0x44)
    val ommerB = addr(0x55)
    val blockNumber = BigInt(2_535_000) // era 0
    val ommers = Seq(
      header(number = blockNumber - 1, beneficiary = ommerA),
      header(number = blockNumber - 2, beneficiary = ommerB)
    )
    val rewarded = scheme.rewardBlock(emptyWorld, header(number = blockNumber), ommers)
    assert(
      // miner: winnerByEra(0) + 2*(5e18/32).
      rewarded.getBalance(miner).toBigInt == FiveEth + BigInt(2) * (FiveEth / 32) &&
        // ommerA at N-1: (N-1 + 8 - N) * 5e18 / 8 = 7 * 5e18 / 8.
        rewarded.getBalance(ommerA).toBigInt == BigInt(7) * FiveEth / 8 &&
        // ommerB at N-2: (N-2 + 8 - N) * 5e18 / 8 = 6 * 5e18 / 8.
        rewarded.getBalance(ommerB).toBigInt == BigInt(6) * FiveEth / 8
    )

  test("configurable era length — a custom shorter era reaches era 1 sooner"):
    val custom = RewardScheme.Ecip1017RewardScheme(eraLength = 1000, blockReward = FiveEth)
    assert(
      custom.blockEra(1000) == BigInt(0) && custom.blockEra(1001) == BigInt(1) &&
        custom.winnerRewardByEra(1) == BigInt(4) * Eth
    )
