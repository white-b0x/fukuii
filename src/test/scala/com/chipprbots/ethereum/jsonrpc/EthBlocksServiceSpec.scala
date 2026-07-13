package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.BaseFeePerGas
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostEip1559
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.LegacyReceipt
import com.chipprbots.ethereum.domain.MaxFeePerGas
import com.chipprbots.ethereum.domain.Nonce
import com.chipprbots.ethereum.domain.PriorityFeePerGas
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.TotalDifficulty
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Wei
import com.chipprbots.ethereum.jsonrpc.EthBlocksService.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

class EthBlocksServiceSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with MockFactory
    with NormalPatience
    with TypeCheckedTripleEquals:

  implicit val runtime: IORuntime = IORuntime.global

  "EthBlocksService" should "answer eth_blockNumber with the latest block number" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val bestBlockNumber = 10
    blockchainWriter.saveBestKnownBlocks(BlockHash(ByteString.empty), bestBlockNumber)

    val response: BestBlockNumberResponse =
      ethBlocksService.bestBlockNumber(BestBlockNumberRequest()).unsafeRunSync().toOption.get
    response.bestBlockNumber shouldEqual bestBlockNumber

  it should "answer eth_getBlockTransactionCountByHash with None when the requested block isn't taggedAs (UnitTest, RPCTest) in the blockchain" in new TestSetup:
    val request: TxCountByBlockHashRequest = TxCountByBlockHashRequest(BlockHash(blockToRequestHash))
    val response: TxCountByBlockHashResponse =
      ethBlocksService.getBlockTransactionCountByHash(request).unsafeRunSync().toOption.get
    response.txsQuantity shouldBe None

  it should "answer eth_getBlockTransactionCountByHash with the block has no tx when the requested block is taggedAs (UnitTest, RPCTest) in the blockchain and has no tx" in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest.copy(body = BlockBody(Nil, Nil))).commit()
    val request: TxCountByBlockHashRequest = TxCountByBlockHashRequest(BlockHash(blockToRequestHash))
    val response: TxCountByBlockHashResponse =
      ethBlocksService.getBlockTransactionCountByHash(request).unsafeRunSync().toOption.get
    response.txsQuantity shouldBe Some(0)

  it should "answer eth_getBlockTransactionCountByHash correctly when the requested block is taggedAs (UnitTest, RPCTest) in the blockchain and has some tx" in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    val request: TxCountByBlockHashRequest = TxCountByBlockHashRequest(BlockHash(blockToRequestHash))
    val response: TxCountByBlockHashResponse =
      ethBlocksService.getBlockTransactionCountByHash(request).unsafeRunSync().toOption.get
    response.txsQuantity shouldBe Some(blockToRequest.body.transactionList.size)

  it should "answer eth_getBlockByHash with None when the requested block isn't taggedAs (UnitTest, RPCTest) in the blockchain" in new TestSetup:
    val request: BlockByBlockHashRequest = BlockByBlockHashRequest(BlockHash(blockToRequestHash), fullTxs = true)
    val response: BlockByBlockHashResponse = ethBlocksService.getByBlockHash(request).unsafeRunSync().toOption.get
    response.blockResponse shouldBe None

  it should "answer eth_getBlockByHash with the block response correctly when it's chain weight is taggedAs (UnitTest, RPCTest) in blockchain" in new TestSetup:
    blockchainWriter
      .storeBlock(blockToRequest)
      .and(blockchainWriter.storeChainWeight(BlockHash(blockToRequestHash), blockWeight))
      .commit()

    val request: BlockByBlockHashRequest = BlockByBlockHashRequest(BlockHash(blockToRequestHash), fullTxs = true)
    val response: BlockByBlockHashResponse = ethBlocksService.getByBlockHash(request).unsafeRunSync().toOption.get

    val stxResponses: Seq[TransactionResponse] = blockToRequest.body.transactionList.zipWithIndex.map {
      case (stx, txIndex) =>
        TransactionResponse(stx, Some(blockToRequest.header), Some(txIndex))
    }

    response.blockResponse shouldBe Some(
      BlockResponse(blockToRequest, fullTxs = true, weight = Some(blockWeight))
    )
    response.blockResponse.get.totalDifficulty shouldBe Some(blockWeight.totalDifficulty.value)
    response.blockResponse.get.transactions.toOption shouldBe Some(stxResponses)

  it should "answer eth_getBlockByHash with the block response correctly when it's chain weight is not taggedAs (UnitTest, RPCTest) in blockchain" in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()

    val request: BlockByBlockHashRequest = BlockByBlockHashRequest(BlockHash(blockToRequestHash), fullTxs = true)
    val response: BlockByBlockHashResponse = ethBlocksService.getByBlockHash(request).unsafeRunSync().toOption.get

    val stxResponses: Seq[TransactionResponse] = blockToRequest.body.transactionList.zipWithIndex.map {
      case (stx, txIndex) =>
        TransactionResponse(stx, Some(blockToRequest.header), Some(txIndex))
    }

    response.blockResponse shouldBe Some(BlockResponse(blockToRequest, fullTxs = true))
    response.blockResponse.get.totalDifficulty shouldBe None
    response.blockResponse.get.transactions.toOption shouldBe Some(stxResponses)

  it should "answer eth_getBlockByHash with the block response correctly when the txs should be hashed" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    blockchainWriter
      .storeBlock(blockToRequest)
      .and(blockchainWriter.storeChainWeight(BlockHash(blockToRequestHash), blockWeight))
      .commit()

    val request: BlockByBlockHashRequest = BlockByBlockHashRequest(BlockHash(blockToRequestHash), fullTxs = true)
    val response: BlockByBlockHashResponse =
      ethBlocksService.getByBlockHash(request.copy(fullTxs = false)).unsafeRunSync().toOption.get

    response.blockResponse shouldBe Some(
      BlockResponse(blockToRequest, fullTxs = false, weight = Some(blockWeight))
    )
    response.blockResponse.get.totalDifficulty shouldBe Some(blockWeight.totalDifficulty.value)
    response.blockResponse.get.transactions.left.toOption shouldBe Some(blockToRequest.body.transactionList.map(_.hash))

  it should "answer eth_getBlockByNumber with the correct block when the pending block is requested" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    (() => blockGenerator.getPendingBlockAndState)
      .expects()
      .returns(Some(PendingBlockAndState(PendingBlock(blockToRequest, Nil), fakeWorld)))

    val request: BlockByNumberRequest = BlockByNumberRequest(BlockParam.Pending, fullTxs = true)
    val response: BlockByNumberResponse = ethBlocksService.getBlockByNumber(request).unsafeRunSync().toOption.get

    response.blockResponse.isDefined should be(true)
    val blockResponse = response.blockResponse.get

    blockResponse.hash shouldBe None
    blockResponse.nonce shouldBe None
    blockResponse.miner shouldBe None
    blockResponse.number shouldBe blockToRequest.header.number

  it should "answer eth_getBlockByNumber with the latest block pending block is requested and there are no pending ones" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    blockchainWriter
      .storeBlock(blockToRequest)
      .and(blockchainWriter.storeChainWeight(BlockHash(blockToRequestHash), blockWeight))
      .commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.header.number.value)

    (() => blockGenerator.getPendingBlockAndState).expects().returns(None)

    val request: BlockByNumberRequest = BlockByNumberRequest(BlockParam.Pending, fullTxs = true)
    val response: BlockByNumberResponse = ethBlocksService.getBlockByNumber(request).unsafeRunSync().toOption.get
    response.blockResponse.get.hash.get shouldEqual blockToRequest.header.hash

  it should "answer eth_getBlockByNumber with None when the requested block isn't taggedAs (UnitTest, RPCTest) in the blockchain" in new TestSetup:
    val request: BlockByNumberRequest =
      BlockByNumberRequest(BlockParam.WithNumber(blockToRequestNumber), fullTxs = true)
    val response: BlockByNumberResponse = ethBlocksService.getBlockByNumber(request).unsafeRunSync().toOption.get
    response.blockResponse shouldBe None

  it should "answer eth_getBlockByNumber with the block response correctly when it's chain weight is taggedAs (UnitTest, RPCTest) in blockchain" in new TestSetup:
    blockchainWriter
      .storeBlock(blockToRequest)
      .and(blockchainWriter.storeChainWeight(BlockHash(blockToRequestHash), blockWeight))
      .commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)

    val request: BlockByNumberRequest =
      BlockByNumberRequest(BlockParam.WithNumber(blockToRequestNumber), fullTxs = true)
    val response: BlockByNumberResponse = ethBlocksService.getBlockByNumber(request).unsafeRunSync().toOption.get

    val stxResponses: Seq[TransactionResponse] = blockToRequest.body.transactionList.zipWithIndex.map {
      case (stx, txIndex) =>
        TransactionResponse(stx, Some(blockToRequest.header), Some(txIndex))
    }

    response.blockResponse shouldBe Some(
      BlockResponse(blockToRequest, fullTxs = true, weight = Some(blockWeight))
    )
    response.blockResponse.get.totalDifficulty shouldBe Some(blockWeight.totalDifficulty.value)
    response.blockResponse.get.transactions.toOption shouldBe Some(stxResponses)

  it should "answer eth_getBlockByNumber with the block response correctly when it's chain weight is not taggedAs (UnitTest, RPCTest) in blockchain" in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)

    val request: BlockByNumberRequest =
      BlockByNumberRequest(BlockParam.WithNumber(blockToRequestNumber), fullTxs = true)
    val response: BlockByNumberResponse = ethBlocksService.getBlockByNumber(request).unsafeRunSync().toOption.get

    val stxResponses: Seq[TransactionResponse] = blockToRequest.body.transactionList.zipWithIndex.map {
      case (stx, txIndex) =>
        TransactionResponse(stx, Some(blockToRequest.header), Some(txIndex))
    }

    response.blockResponse shouldBe Some(BlockResponse(blockToRequest, fullTxs = true))
    response.blockResponse.get.totalDifficulty shouldBe None
    response.blockResponse.get.transactions.toOption shouldBe Some(stxResponses)

  it should "answer eth_getBlockByNumber with the block response correctly when the txs should be hashed" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    blockchainWriter
      .storeBlock(blockToRequest)
      .and(blockchainWriter.storeChainWeight(BlockHash(blockToRequestHash), blockWeight))
      .commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)

    val request: BlockByNumberRequest =
      BlockByNumberRequest(BlockParam.WithNumber(blockToRequestNumber), fullTxs = true)
    val response: BlockByNumberResponse =
      ethBlocksService.getBlockByNumber(request.copy(fullTxs = false)).unsafeRunSync().toOption.get

    response.blockResponse shouldBe Some(
      BlockResponse(blockToRequest, fullTxs = false, weight = Some(blockWeight))
    )
    response.blockResponse.get.totalDifficulty shouldBe Some(blockWeight.totalDifficulty.value)
    response.blockResponse.get.transactions.left.toOption shouldBe Some(blockToRequest.body.transactionList.map(_.hash))

  it should "get transaction count by block number" taggedAs (UnitTest, RPCTest) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)

    val response: ServiceResponse[GetBlockTransactionCountByNumberResponse] =
      ethBlocksService.getBlockTransactionCountByNumber(
        GetBlockTransactionCountByNumberRequest(BlockParam.WithNumber(blockToRequest.header.number.value))
      )

    response.unsafeRunSync() shouldEqual Right(
      GetBlockTransactionCountByNumberResponse(blockToRequest.body.transactionList.size)
    )

  it should "get transaction count by latest block number" taggedAs (UnitTest, RPCTest) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.header.number.value)

    val response: ServiceResponse[GetBlockTransactionCountByNumberResponse] =
      ethBlocksService.getBlockTransactionCountByNumber(GetBlockTransactionCountByNumberRequest(BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(
      GetBlockTransactionCountByNumberResponse(blockToRequest.body.transactionList.size)
    )

  it should "answer eth_getUncleByBlockHashAndIndex with None when the requested block isn't taggedAs (UnitTest, RPCTest) in the blockchain" in new TestSetup:
    val uncleIndexToRequest = 0
    val request: UncleByBlockHashAndIndexRequest =
      UncleByBlockHashAndIndexRequest(BlockHash(blockToRequestHash), uncleIndexToRequest)
    val response: UncleByBlockHashAndIndexResponse =
      ethBlocksService.getUncleByBlockHashAndIndex(request).unsafeRunSync().toOption.get
    response.uncleBlockResponse shouldBe None

  it should "answer eth_getUncleByBlockHashAndIndex with None when there's no uncle" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)

    val uncleIndexToRequest = 0
    val request: UncleByBlockHashAndIndexRequest =
      UncleByBlockHashAndIndexRequest(BlockHash(blockToRequestHash), uncleIndexToRequest)
    val response: UncleByBlockHashAndIndexResponse =
      ethBlocksService.getUncleByBlockHashAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe None

  it should "answer eth_getUncleByBlockHashAndIndex with None when there's no uncle taggedAs (UnitTest, RPCTest) in the requested index" in new TestSetup:
    blockchainWriter.storeBlock(blockToRequestWithUncles).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequestWithUncles.hash, blockToRequestWithUncles.number.value)

    val uncleIndexToRequest = 0
    val request: UncleByBlockHashAndIndexRequest =
      UncleByBlockHashAndIndexRequest(BlockHash(blockToRequestHash), uncleIndexToRequest)
    val response1: UncleByBlockHashAndIndexResponse =
      ethBlocksService
        .getUncleByBlockHashAndIndex(request.copy(uncleIndex = 1))
        .unsafeRunSync()
        .toOption
        .get
    val response2: UncleByBlockHashAndIndexResponse =
      ethBlocksService
        .getUncleByBlockHashAndIndex(request.copy(uncleIndex = -1))
        .unsafeRunSync()
        .toOption
        .get

    response1.uncleBlockResponse shouldBe None
    response2.uncleBlockResponse shouldBe None

  it should "answer eth_getUncleByBlockHashAndIndex correctly when the requested index has one but there's no chain weight for it" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequestWithUncles).commit()

    val uncleIndexToRequest = 0
    val request: UncleByBlockHashAndIndexRequest =
      UncleByBlockHashAndIndexRequest(BlockHash(blockToRequestHash), uncleIndexToRequest)
    val response: UncleByBlockHashAndIndexResponse =
      ethBlocksService.getUncleByBlockHashAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe Some(BlockResponse(uncle, None, pendingBlock = false))
    response.uncleBlockResponse.get.totalDifficulty shouldBe None
    response.uncleBlockResponse.get.transactions shouldBe Left(Nil)
    response.uncleBlockResponse.get.uncles shouldBe Nil

  it should "anwer eth_getUncleByBlockHashAndIndex correctly when the requested index has one and there's chain weight for it" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    blockchainWriter
      .storeBlock(blockToRequestWithUncles)
      .and(blockchainWriter.storeChainWeight(uncle.hash, uncleWeight))
      .commit()

    val uncleIndexToRequest = 0
    val request: UncleByBlockHashAndIndexRequest =
      UncleByBlockHashAndIndexRequest(BlockHash(blockToRequestHash), uncleIndexToRequest)
    val response: UncleByBlockHashAndIndexResponse =
      ethBlocksService.getUncleByBlockHashAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe Some(BlockResponse(uncle, Some(uncleWeight), pendingBlock = false))
    response.uncleBlockResponse.get.totalDifficulty shouldBe Some(uncleWeight.totalDifficulty.value)
    response.uncleBlockResponse.get.transactions shouldBe Left(Nil)
    response.uncleBlockResponse.get.uncles shouldBe Nil

  it should "answer eth_getUncleByBlockNumberAndIndex with None when the requested block isn't taggedAs (UnitTest, RPCTest) in the blockchain" in new TestSetup:
    val uncleIndexToRequest = 0
    val request: UncleByBlockNumberAndIndexRequest =
      UncleByBlockNumberAndIndexRequest(BlockParam.WithNumber(blockToRequestNumber), uncleIndexToRequest)
    val response: UncleByBlockNumberAndIndexResponse =
      ethBlocksService.getUncleByBlockNumberAndIndex(request).unsafeRunSync().toOption.get
    response.uncleBlockResponse shouldBe None

  it should "answer eth_getUncleByBlockNumberAndIndex with None when there's no uncle" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:

    blockchainWriter.storeBlock(blockToRequest).commit()

    val uncleIndexToRequest = 0
    val request: UncleByBlockNumberAndIndexRequest =
      UncleByBlockNumberAndIndexRequest(BlockParam.WithNumber(blockToRequestNumber), uncleIndexToRequest)
    val response: UncleByBlockNumberAndIndexResponse =
      ethBlocksService.getUncleByBlockNumberAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe None

  it should "answer eth_getUncleByBlockNumberAndIndex with None when there's no uncle taggedAs (UnitTest, RPCTest) in the requested index" in new TestSetup:

    blockchainWriter.storeBlock(blockToRequestWithUncles).commit()

    val uncleIndexToRequest = 0
    val request: UncleByBlockNumberAndIndexRequest =
      UncleByBlockNumberAndIndexRequest(BlockParam.WithNumber(blockToRequestNumber), uncleIndexToRequest)
    val response1: UncleByBlockNumberAndIndexResponse =
      ethBlocksService
        .getUncleByBlockNumberAndIndex(request.copy(uncleIndex = 1))
        .unsafeRunSync()
        .toOption
        .get
    val response2: UncleByBlockNumberAndIndexResponse =
      ethBlocksService
        .getUncleByBlockNumberAndIndex(request.copy(uncleIndex = -1))
        .unsafeRunSync()
        .toOption
        .get

    response1.uncleBlockResponse shouldBe None
    response2.uncleBlockResponse shouldBe None

  it should "answer eth_getUncleByBlockNumberAndIndex correctly when the requested index has one but there's no chain weight for it" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequestWithUncles).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequestWithUncles.hash, blockToRequestWithUncles.number.value)

    val uncleIndexToRequest = 0
    val request: UncleByBlockNumberAndIndexRequest =
      UncleByBlockNumberAndIndexRequest(BlockParam.WithNumber(blockToRequestNumber), uncleIndexToRequest)
    val response: UncleByBlockNumberAndIndexResponse =
      ethBlocksService.getUncleByBlockNumberAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe Some(BlockResponse(uncle, None, pendingBlock = false))
    response.uncleBlockResponse.get.totalDifficulty shouldBe None
    response.uncleBlockResponse.get.transactions shouldBe Left(Nil)
    response.uncleBlockResponse.get.uncles shouldBe Nil

  it should "answer eth_getUncleByBlockNumberAndIndex correctly when the requested index has one and there's chain weight for it" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    blockchainWriter
      .storeBlock(blockToRequestWithUncles)
      .and(blockchainWriter.storeChainWeight(uncle.hash, uncleWeight))
      .commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequestWithUncles.hash, blockToRequestWithUncles.number.value)

    val uncleIndexToRequest = 0
    val request: UncleByBlockNumberAndIndexRequest =
      UncleByBlockNumberAndIndexRequest(BlockParam.WithNumber(blockToRequestNumber), uncleIndexToRequest)
    val response: UncleByBlockNumberAndIndexResponse =
      ethBlocksService.getUncleByBlockNumberAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe Some(BlockResponse(uncle, Some(uncleWeight), pendingBlock = false))
    response.uncleBlockResponse.get.totalDifficulty shouldBe Some(uncleWeight.totalDifficulty.value)
    response.uncleBlockResponse.get.transactions shouldBe Left(Nil)
    response.uncleBlockResponse.get.uncles shouldBe Nil

  it should "get uncle count by block number" taggedAs (UnitTest, RPCTest) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)

    val response: ServiceResponse[GetUncleCountByBlockNumberResponse] =
      ethBlocksService.getUncleCountByBlockNumber(GetUncleCountByBlockNumberRequest(BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(
      GetUncleCountByBlockNumberResponse(blockToRequest.body.uncleNodesList.size)
    )

  it should "get uncle count by block hash" taggedAs (UnitTest, RPCTest) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()

    val response: ServiceResponse[GetUncleCountByBlockHashResponse] =
      ethBlocksService.getUncleCountByBlockHash(
        GetUncleCountByBlockHashRequest(BlockHash(blockToRequest.header.hash.value))
      )

    response.unsafeRunSync() shouldEqual Right(
      GetUncleCountByBlockHashResponse(blockToRequest.body.uncleNodesList.size)
    )

  // Regression: FEEHISTORY-REWARD-AND-PROJECTION-01(a). `reward` must be the gasUsed-weighted
  // percentile of each tx's effective priority fee (min(maxPriorityFeePerGas, maxFeePerGas -
  // baseFee)), not a hardcoded all-zeros row.
  it should "answer eth_feeHistory with reward percentiles computed from tx tips, not hardcoded zeros" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val baseFee: BigInt = 50
    // tip1 = min(maxPriorityFeePerGas=10, maxFeePerGas=100 - baseFee=50) = 10
    // tip2 = min(maxPriorityFeePerGas=50, maxFeePerGas=200 - baseFee=50) = 50
    val tx1: SignedTransaction = dynamicFeeTx(maxFeePerGas = 100, maxPriorityFeePerGas = 10)
    val tx2: SignedTransaction = dynamicFeeTx(maxFeePerGas = 200, maxPriorityFeePerGas = 50)

    val header: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(blockToRequestNumber + 1),
      gasLimit = GasAmount(10000000),
      gasUsed = GasAmount(4000),
      extraFields = HefPostEip1559(BaseFeePerGas(baseFee))
    )
    val block: Block = Block(header, BlockBody(Seq(tx1, tx2), Nil))

    // Per-tx gasUsed, derived from cumulative receipt gas the way go-ethereum's Oracle.processBlock does:
    // tx1 uses 1000, tx2 uses the remaining 3000 (cumulative 4000 == header.gasUsed).
    val receipt1: LegacyReceipt =
      LegacyReceipt.withHashOutcome(ByteString(Array.fill[Byte](32)(0)), GasAmount(1000), BloomFilter.Empty, Nil)
    val receipt2: LegacyReceipt =
      LegacyReceipt.withHashOutcome(ByteString(Array.fill[Byte](32)(0)), GasAmount(4000), BloomFilter.Empty, Nil)

    blockchainWriter
      .storeBlock(block)
      .and(blockchainWriter.storeReceipts(header.hash, Seq(receipt1, receipt2)))
      .commit()
    blockchainWriter.saveBestKnownBlocks(header.hash, header.number.value)

    val request: FeeHistoryRequest = FeeHistoryRequest(
      blockCount = 1,
      newestBlock = BlockParam.WithNumber(header.number.value),
      rewardPercentiles = Some(Seq(10.0, 50.0))
    )
    val response: FeeHistoryResponse = ethBlocksService.feeHistory(request).unsafeRunSync().toOption.get

    // p=10: threshold = 4000*10/100=400 <= tx1's cumulative gasUsed(1000) -> tx1's tip (10)
    // p=50: threshold = 4000*50/100=2000 > tx1's 1000, advances to tx2 -> tx2's tip (50)
    response.reward shouldBe Some(Seq(Seq(BigInt(10), BigInt(50))))

  // Regression: FEEHISTORY-REWARD-AND-PROJECTION-01(b). The extra `newestBlock+1` baseFee entry
  // must be derived via BaseFeeCalculator.calcBaseFee, not defaulted to 0 — and on a chain with a
  // non-zero baseFeeFloor (ETC/Mordor = 1 gwei per ECIP-1111) it must respect that floor.
  it should "answer eth_feeHistory's newestBlock+1 projection via BaseFeeCalculator, respecting baseFeeFloor" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    override def serviceBlockchainConfig: BlockchainConfig =
      blockchainConfig.copy(baseFeeFloor = BigInt(1000000000)) // ECIP-1111: 1 gwei floor on ETC/Mordor

    // gasUsed=0 (fully empty, i.e. the maximal decrease branch) with baseFee=0 makes the naive
    // EIP-1559 decrease formula project 0 for the next block; only the floor clamp saves it.
    val header: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
      gasLimit = GasAmount(10000000),
      gasUsed = GasAmount.Zero,
      extraFields = HefPostEip1559(BaseFeePerGas(BigInt(0)))
    )
    val block: Block = Block(header, BlockBody(Nil, Nil))

    blockchainWriter.storeBlock(block).commit()
    blockchainWriter.saveBestKnownBlocks(header.hash, header.number.value)

    val request: FeeHistoryRequest =
      FeeHistoryRequest(blockCount = 1, newestBlock = BlockParam.Latest, rewardPercentiles = None)
    val response: FeeHistoryResponse = ethBlocksService.feeHistory(request).unsafeRunSync().toOption.get

    val expectedNextBaseFee: BigInt = BaseFeeCalculator.calcBaseFee(header, serviceBlockchainConfig).value
    expectedNextBaseFee shouldBe BigInt(1000000000) // sanity: the floor clamp is actually exercised
    response.baseFeePerGas.last shouldBe expectedNextBaseFee
    response.baseFeePerGas.last should not be BigInt(0)

  class TestSetup() extends EphemBlockchainTestSetup:
    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    val appStateStorage: AppStateStorage = mock[AppStateStorage]

    override lazy val mining: TestMining = buildTestMining().withBlockGenerator(blockGenerator)
    override lazy val miningConfig = MiningConfigs.miningConfig

    // Overridable so a subclass/test can exercise a non-default BlockchainConfig (e.g. a
    // non-zero baseFeeFloor per ECIP-1111). Defaults to the cake's own `blockchainConfig`
    // rather than `EthBlocksService`'s production default (`Config.blockchains.blockchainConfig`).
    def serviceBlockchainConfig: BlockchainConfig = blockchainConfig

    lazy val ethBlocksService = new EthBlocksService(
      blockchain,
      blockchainReader,
      mining,
      blockQueue,
      configuredBlockchainConfig = serviceBlockchainConfig
    )

    // Only the EIP-1559 fields matter for the reward-percentile computation (see
    // ProgramContextEffectiveGasPriceSpec for the same minimal-fixture pattern); other fields use
    // arbitrary fixture values, and the signature need not verify since feeHistory never derives
    // a sender.
    def dynamicFeeTx(maxFeePerGas: BigInt, maxPriorityFeePerGas: BigInt): SignedTransaction =
      val raw = TransactionWithDynamicFee(
        chainId = ChainId(1),
        nonce = Nonce(0),
        maxPriorityFeePerGas = PriorityFeePerGas(maxPriorityFeePerGas),
        maxFeePerGas = MaxFeePerGas(maxFeePerGas),
        gasLimit = GasAmount(100000),
        receivingAddress = Some(Address(ByteString(Array.fill[Byte](20)(0xcc.toByte)))),
        value = Wei(0),
        payload = ByteString.empty,
        accessList = Nil
      )
      SignedTransaction(raw, ECDSASignature(BigInt(0), BigInt(0), BigInt(0)))

    val blockToRequest: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    val blockToRequestNumber = blockToRequest.header.number.value
    val blockToRequestHash = blockToRequest.header.hash.value
    val blockWeight: ChainWeight =
      ChainWeight.totalDifficultyOnly(TotalDifficulty(blockToRequest.header.difficulty.value))

    val uncle = Fixtures.Blocks.DaoForkBlock.header
    val uncleWeight: ChainWeight = ChainWeight.totalDifficultyOnly(TotalDifficulty(uncle.difficulty.value))
    val blockToRequestWithUncles: Block = blockToRequest.copy(body = BlockBody(Nil, Seq(uncle)))

    val fakeWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(BlockNumber(-1)),
      (number: BlockNumber) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
