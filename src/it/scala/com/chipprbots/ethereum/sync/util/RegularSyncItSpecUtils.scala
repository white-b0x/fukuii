package com.chipprbots.ethereum.sync.util

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*

import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcasterActor
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcasterActor.BroadcastBlock
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
import com.chipprbots.ethereum.consensus.Consensus
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.engine.ConsensusEngine
import com.chipprbots.ethereum.consensus.mining.FullMiningConfig
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.Protocol.NoAdditionalPoWData
import com.chipprbots.ethereum.consensus.pow
import com.chipprbots.ethereum.consensus.pow.EthashConfig
import com.chipprbots.ethereum.consensus.pow.PoWMining
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator.intByteArraySerializable
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.nodebuilder.VmSetup
import com.chipprbots.ethereum.vm.EvmConfig
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.sync.util.SyncCommonItSpecUtils.*
import com.chipprbots.ethereum.sync.util.SyncCommonItSpecUtils.FakePeerCustomConfig.defaultConfig
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.*

object RegularSyncItSpecUtils:

  class ValidatorsExecutorAlwaysSucceed extends MockValidatorsAlwaysSucceed:
    override def validateBlockAfterExecution(
        block: Block,
        stateRootHash: ByteString,
        receipts: Seq[Receipt],
        gasUsed: GasAmount
    )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] = Right(
      BlockExecutionSuccess
    )

  object ValidatorsExecutorAlwaysSucceed extends ValidatorsExecutorAlwaysSucceed

  class FakePeer(peerName: String, fakePeerCustomConfig: FakePeerCustomConfig)
      extends CommonFakePeer(peerName, fakePeerCustomConfig):

    def buildEthashMining(): pow.PoWMining =
      val miningConfig: MiningConfig = MiningConfig(Config.config)
      val specificConfig: EthashConfig = pow.EthashConfig(config)
      val fullConfig = FullMiningConfig(miningConfig, specificConfig)
      val vm = VmSetup.vm(VmConfig(config))
      val mining =
        PoWMining(
          vm,
          storagesInstance.storages.evmCodeStorage,
          bl,
          blockchainReader,
          fullConfig,
          ValidatorsExecutorAlwaysSucceed,
          NoAdditionalPoWData
        )
      mining

    lazy val peersClient: typed.ActorRef[PeersClient.Command] =
      system.spawn(
        PeersClient.behavior(etcPeerManager, peerEventBus, blacklist, testSyncConfig),
        "peers-client"
      )

    lazy val mining: PoWMining = buildEthashMining()

    lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, syncConfig)
    lazy val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)
    lazy val blockExecution =
      new BlockExecution(
        bl,
        blockchainReader,
        blockchainWriter,
        storagesInstance.storages.evmCodeStorage,
        mining.blockPreparator,
        ConsensusEngine.engineFor(mining, blockchainConfig),
        blockValidation
      )
    lazy val consensus: Consensus =
      new ConsensusImpl(
        blockchainReader,
        blockchainWriter,
        blockExecution
      )
    lazy val consensusAdapter = new ConsensusAdapter(
      consensus,
      blockchainReader,
      blockQueue,
      blockValidation,
      IORuntime.global
    )

    lazy val ommersPool: typed.ActorRef[OmmersPool.Command] =
      system.spawn(OmmersPool(blockchainReader, 1), "ommers-pool")

    lazy val pendingTxTopic: typed.ActorRef[
      org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewPendingTransaction]
    ] = system.spawn(
      org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewPendingTransaction](
        "pending-tx-topic"
      ),
      "pending-tx-topic"
    )

    lazy val pendingTransactionsManager: typed.ActorRef[PendingTransactionsManager.Command] = system.spawn(
      PendingTransactionsManager(TxPoolConfig(config), peerManager, etcPeerManager, peerEventBus, pendingTxTopic),
      "pending-transactions-manager"
    )

    lazy val blockTopic: typed.ActorRef[
      org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
    ] = system.spawn(
      org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
        "block-imported-topic"
      ),
      "block-imported-topic"
    )

    lazy val validators: ValidatorsExecutor = buildEthashMining().validators

    val broadcasterRef: typed.ActorRef[BlockBroadcasterActor.BroadcasterMsg] =
      system.spawn(
        BlockBroadcasterActor.apply(
          new BlockBroadcast(etcPeerManager),
          peerEventBus,
          etcPeerManager,
          blacklist,
          syncConfig
        ),
        "block-broadcaster"
      )

    val fetcher: typed.ActorRef[BlockFetcher.FetchCommand] =
      system.spawn(
        BlockFetcher(
          peersClient,
          peerEventBus,
          regularSync.toTyped[RegularSync.ProgressProtocol],
          syncConfig,
          validators.blockValidator
        ),
        "block-fetcher"
      )

    lazy val blockImporter: typed.ActorRef[BlockImporter.Command] =
      system.spawn(
        BlockImporter.apply(
          fetcher,
          consensusAdapter,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.evmCodeStorage,
          new BranchResolution(blockchainReader),
          syncConfig,
          ommersPool,
          broadcasterRef,
          pendingTransactionsManager,
          blockTopic,
          regularSync.toTyped[RegularSync.Command],
          peerEventBus.toClassic,
          etcPeerManager,
          bl,
          blacklist,
          this
        ),
        "block-importer"
      )

    lazy val regularSync: ActorRef = system
      .spawnAnonymous(
        RegularSync.apply(
          peersClient,
          etcPeerManager,
          peerEventBus.toClassic,
          consensusAdapter,
          bl,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.evmCodeStorage,
          new BranchResolution(blockchainReader),
          validators.blockValidator,
          blacklist,
          testSyncConfig,
          ommersPool,
          pendingTransactionsManager,
          blockTopic,
          this,
          system.toTyped.ignoreRef[com.chipprbots.ethereum.blockchain.sync.SyncController.Command]
        )
      )
      .toClassic

    def startRegularSync(): IO[Unit] = IO {
      regularSync ! SyncProtocol.Start
    }

    def broadcastBlock(
        blockNumber: Option[Int] = None
    )(updateWorldForBlock: (BigInt, InMemoryWorldStateProxy) => InMemoryWorldStateProxy): IO[Unit] =
      IO(blockNumber match
        case Some(bNumber) =>
          blockchainReader
            .getBlockByNumber(blockchainReader.getBestBranch, BlockNumber(bNumber))
            .getOrElse(throw new RuntimeException(s"block by number: $bNumber doesn't exist"))
        case None => blockchainReader.getBestBlock.get
      ).flatMap { block =>
        IO {
          val currentWeight = blockchainReader
            .getChainWeightByHash(block.hash)
            .getOrElse(throw new RuntimeException(s"ChainWeight by hash: ${block.hash} doesn't exist"))
          val currentWorld = getMptForBlock(block)
          val (newBlock, newWeight, _) = createChildBlock(block, currentWeight, currentWorld)(updateWorldForBlock)
          broadcastBlock(newBlock, newWeight)
        }
      }

    def waitForRegularSyncLoadLastBlock(blockNumber: BigInt): IO[Boolean] =
      // Scale timeout based on block number - larger syncs need more time
      // Use minimum 90 retries, but add 1 retry per 20 blocks for large syncs
      val baseRetries = 90
      val additionalRetries = if blockNumber > 1000 then ((blockNumber - 1000) / 20).toInt else 0
      val maxRetries = baseRetries + additionalRetries
      retryUntilWithDelay(IO(blockchainReader.getBestBlockNumber == blockNumber), 1.second, maxRetries)(isDone =>
        isDone
      )

    def mineNewBlock(
        plusDifficulty: BigInt = 0
    )(updateWorldForBlock: (BigInt, InMemoryWorldStateProxy) => InMemoryWorldStateProxy): IO[Unit] = IO {
      val block: Block = blockchainReader.getBestBlock.get
      val currentWeight = blockchainReader
        .getChainWeightByHash(block.hash)
        .getOrElse(throw new RuntimeException(s"ChainWeight by hash: ${block.hash} doesn't exist"))
      val currentWorld = getMptForBlock(block)
      val (newBlock, _, _) =
        createChildBlock(block, currentWeight, currentWorld, plusDifficulty)(updateWorldForBlock)
      regularSync ! SyncProtocol.MinedBlock(newBlock)
    }

    def mineNewBlocks(delay: FiniteDuration, nBlocks: Int)(
        updateWorldForBlock: (BigInt, InMemoryWorldStateProxy) => InMemoryWorldStateProxy
    ): IO[Unit] =
      if nBlocks > 0 then
        mineNewBlock()(updateWorldForBlock)
          .delayBy(delay)
          .flatMap(_ => mineNewBlocks(delay, nBlocks - 1)(updateWorldForBlock))
      else IO(())

    private def getMptForBlock(block: Block) =
      InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        bl.getBackingMptStorage(BlockNumber(block.number.value)),
        (number: BlockNumber) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
        UInt256.Zero,
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )

    private def broadcastBlock(block: Block, weight: ChainWeight) =
      broadcasterActor ! BroadcastBlock(BlockToBroadcast(block, weight))

    private def createChildBlock(
        parent: Block,
        parentWeight: ChainWeight,
        parentWorld: InMemoryWorldStateProxy,
        plusDifficulty: BigInt = 0
    )(
        updateWorldForBlock: (BigInt, InMemoryWorldStateProxy) => InMemoryWorldStateProxy
    ): (Block, ChainWeight, InMemoryWorldStateProxy) =
      val newBlockNumber = parent.header.number + 1
      val newWorld = updateWorldForBlock(newBlockNumber.value, parentWorld)
      val newBlock = parent.copy(header =
        parent.header.copy(
          parentHash = parent.header.hash,
          number = newBlockNumber,
          stateRoot = TrieRoot(newWorld.stateRootHash),
          difficulty = Difficulty(plusDifficulty) + parent.header.difficulty
        )
      )
      val newWeight = parentWeight.increase(newBlock.header)
      (newBlock, newWeight, parentWorld)

    // === E2ESTATETEST-FIXTURE-REDESIGN-01 ===
    // Real, re-executable block builder for E2EStateTestSpec.
    //
    // CommonFakePeer.importBlocksUntil fabricates each header's stateRoot by injecting accounts
    // straight into the trie (no tx execution, no block reward), producing headers whose stateRoot
    // no peer can re-derive; a peer that full-syncs and re-executes such a chain fails with
    // MissingAccountNodeException. This builder instead drives every block through
    // BlockExecution.executeBlockNoValidation (real txs + ECIP-1017 payBlockReward), so the
    // persisted stateRootHash is genuinely derived from the block. A syncing peer re-executes the
    // same block and derives the identical root. See
    // .local/docs/research-july/e2estatetest-fixture-redesign-01.md.

    private val executedBlockGasLimit: GasAmount = GasAmount(BigInt(8000000))

    /** World state at `block`'s stateRoot, for reading account nonces/balances while crafting txs. */
    private def worldAtStateRoot(block: Block): InMemoryWorldStateProxy =
      InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        bl.getBackingMptStorage(BlockNumber(block.number.value)),
        (number: BlockNumber) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
        blockchainConfig.accountStartNonce,
        block.header.stateRoot.value,
        noEmptyAccounts = EvmConfig.forBlock(block.number, blockchainConfig).noEmptyAccounts,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )

    private def buildFixtureMpt[K](entities: Seq[K], vSerializable: ByteArraySerializable[K]): ByteString =
      val storage = StateStorage.getReadOnlyStorage(EphemDataSource())
      val mpt = MerklePatriciaTrie[Int, K](storage)(intByteArraySerializable, vSerializable)
      ByteString(entities.zipWithIndex.foldLeft(mpt) { case (trie, (value, key)) => trie.put(key, value) }.getRootHash)

    private def executeChildBlock(
        parent: Block,
        parentWeight: ChainWeight,
        beneficiary: Address,
        txs: Seq[SignedTransaction]
    ): (Block, ChainWeight, Seq[Receipt]) =
      // executeBlockNoValidation derives the world from the PARENT's stateRoot, so the child's own
      // stateRoot is irrelevant during execution and is patched afterwards from the real result.
      val draftHeader = parent.header.copy(
        parentHash = parent.header.hash,
        number = parent.header.number + 1,
        beneficiary = beneficiary.bytes,
        gasLimit = executedBlockGasLimit,
        unixTimestamp = parent.header.unixTimestamp + 1
      )
      val draftBlock = Block(draftHeader, BlockBody(txs, Nil))
      blockExecution.executeBlockNoValidation(draftBlock) match
        case Left(error) =>
          throw new RuntimeException(
            s"Fixture block ${draftHeader.number.value} failed real execution: ${error.toString}"
          )
        case Right((receipts, gasUsed, stateRoot)) =>
          val receiptsLogs: Seq[Array[Byte]] =
            BloomFilter.Empty.toArray +: receipts.map(_.logsBloomFilter.toArray)
          val header = draftHeader.copy(
            stateRoot = TrieRoot(stateRoot),
            transactionsRoot = TrieRoot(buildFixtureMpt(txs, SignedTransaction.byteArraySerializable)),
            receiptsRoot = TrieRoot(buildFixtureMpt(receipts, Receipt.byteArraySerializable)),
            logsBloom = BloomFilter(ByteString(ByteUtils.or(receiptsLogs*))),
            gasUsed = gasUsed
          )
          val block = Block(header, BlockBody(txs, Nil))
          (block, parentWeight.increase(block.header), receipts)

    /** Build and directly persist a chain of real, re-executable blocks up to number `n`.
      *
      * @param beneficiary
      *   block-reward recipient for every block (the E2E state tests use a fixed faucet address so their signed
      *   value-transfer / contract-creation txs have a funded sender).
      * @param txsForBlock
      *   given the new block number and the parent world state (for nonce/balance lookups), returns the signed
      *   transactions to include in that block.
      */
    def importExecutedBlocksUntil(n: BigInt, beneficiary: Address)(
        txsForBlock: (BigInt, InMemoryWorldStateProxy) => Seq[SignedTransaction]
    ): IO[Unit] =
      IO(blockchainReader.getBestBlock.get).flatMap { parent =>
        if parent.number.value >= n then IO.unit
        else
          IO {
            val parentWeight = blockchainReader
              .getChainWeightByHash(parent.hash)
              .getOrElse(throw new RuntimeException(s"ChainWeight by hash: ${parent.hash} doesn't exist"))
            val parentWorld = worldAtStateRoot(parent)
            val txs = txsForBlock(parent.header.number.value + 1, parentWorld)
            val (block, weight, receipts) = executeChildBlock(parent, parentWeight, beneficiary, txs)
            blockchainWriter.save(block, receipts, weight, saveAsBestBlock = true)
          }.flatMap(_ => importExecutedBlocksUntil(n, beneficiary)(txsForBlock))
      }

  object FakePeer:

    def startFakePeer(peerName: String, fakePeerCustomConfig: FakePeerCustomConfig): IO[FakePeer] =
      for
        peer <- IO(new FakePeer(peerName, fakePeerCustomConfig))
        _ <- peer.startPeer()
      yield peer

    def start1FakePeerRes(
        fakePeerCustomConfig: FakePeerCustomConfig = defaultConfig,
        name: String
    ): Resource[IO, FakePeer] =
      Resource.make {
        startFakePeer(name, fakePeerCustomConfig)
      } { peer =>
        peer.shutdown()
      }

    def start2FakePeersRes(
        fakePeerCustomConfig1: FakePeerCustomConfig = defaultConfig,
        fakePeerCustomConfig2: FakePeerCustomConfig = defaultConfig
    ): Resource[IO, (FakePeer, FakePeer)] =
      for
        peer1 <- start1FakePeerRes(fakePeerCustomConfig1, "Peer1")
        peer2 <- start1FakePeerRes(fakePeerCustomConfig2, "Peer2")
      yield (peer1, peer2)
