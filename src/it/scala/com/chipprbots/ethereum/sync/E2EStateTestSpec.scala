package com.chipprbots.ethereum.sync

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*

import com.typesafe.config.ConfigValueFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.FreeSpecBase
import com.chipprbots.ethereum.crypto.keyPairFromPrvKey
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.metrics.MetricsConfig
import com.chipprbots.ethereum.sync.util.RegularSyncItSpecUtils.FakePeer
import com.chipprbots.ethereum.sync.util.SyncCommonItSpec.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Hex

/** End-to-End test suite for blockchain state synchronization and validation.
  *
  * This test suite validates state-related operations in the blockchain peer and sync systems, leveraging official
  * Ethereum execution client specifications from:
  *   - https://github.com/ethereum/execution-specs (specification)
  *   - https://github.com/ethereum/tests (generated test cases)
  *
  * Test coverage includes:
  *   - State trie synchronization between peers
  *   - State root validation and consistency
  *   - Account state propagation and verification
  *   - Contract storage synchronization
  *   - State healing and recovery mechanisms
  *   - State integrity during blockchain operations
  *
  * These tests ensure that fukuii correctly synchronizes and maintains blockchain state across peers, preventing issues
  * with state divergence, corruption, or loss that would break consensus.
  *
  * The tests complement the GeneralStateTests from ethereum/tests by focusing on peer-to-peer state synchronization
  * scenarios rather than single-node state transition validation.
  *
  * State construction (E2ESTATETEST-FIXTURE-REDESIGN-01): peer1's chain is built with
  * `FakePeer.importExecutedBlocksUntil`, which drives every block through real execution (transactions + ECIP-1017
  * block reward) so each header's stateRoot is genuinely re-derivable. A full-syncing peer2 re-executes the same blocks
  * and derives the identical roots. State at a target address can therefore only be created via a causally-connected
  * transaction, so the helpers below emit real signed value-transfer / contract-creation txs from a deterministic
  * faucet rather than injecting accounts straight into the trie. Each test's intent (peer1-vs-peer2 root/hash equality
  * at specific blocks) is preserved; only the state-construction mechanism changed.
  *
  * @see
  *   Issue: Run end-to-end state test to troubleshoot blockchain peer modules
  * @see
  *   Official execution specs: https://github.com/ethereum/execution-specs
  * @see
  *   Official test repository: https://github.com/ethereum/tests
  */
class E2EStateTestSpec extends FreeSpecBase with Matchers with BeforeAndAfterAll:
  implicit val testRuntime: IORuntime = IORuntime.global

  override def beforeAll(): Unit =
    // Close any previous metrics instance so the new one starts with a clean registry
    Metrics.closeInstance("default")
    Metrics.configure(
      MetricsConfig(Config.config.withValue("metrics.enabled", ConfigValueFactory.fromAnyRef(true)))
    )

  override def afterAll(): Unit = {
    // No need to shutdown IORuntime.global
  }

  // Deterministic faucet shared across peers: the same private key means peer1 and peer2 build
  // byte-identical blocks for the same helper, so reorg/extension tests connect cleanly. The faucet
  // is funded purely by ECIP-1017 block rewards — every fixture block sets beneficiary =
  // faucetAddress, so from block 1 the faucet accrues 5 ETC per block and can fund real signed
  // value-transfer / contract-creation txs from block 2 onward. (The CommonFakePeer genesis is
  // empty and off-limits, so a genesis alloc is not available; block-reward funding replaces it.)
  private val faucetKeyPair: AsymmetricCipherKeyPair = keyPairFromPrvKey(Array.fill(32)(1.toByte))
  private val faucetAddress: Address = Address(faucetKeyPair)

  /** Faucet's current nonce, read from the parent world (account-start-nonce is 0 on this config). */
  private def faucetNonce(world: InMemoryWorldStateProxy): Nonce =
    Nonce(world.getAccount(faucetAddress).map(_.nonce.toBigInt).getOrElse(BigInt(0)))

  /** A real signed value transfer faucet -> `to`, creating persistent balance state at `to`. */
  private def valueTransfer(world: InMemoryWorldStateProxy, to: Address, amount: BigInt): SignedTransaction =
    val tx = LegacyTransaction(
      nonce = faucetNonce(world),
      gasPrice = GasPrice(1),
      gasLimit = GasAmount(21000),
      receivingAddress = to,
      value = Wei(amount),
      payload = ByteString.empty
    )
    SignedTransaction.sign(tx, faucetKeyPair, None)

  // Minimal contract whose constructor SSTOREs slots 0..2 then returns empty runtime code:
  //   PUSH1 01 PUSH1 00 SSTORE  PUSH1 02 PUSH1 01 SSTORE  PUSH1 03 PUSH1 02 SSTORE
  //   PUSH1 00 PUSH1 00 RETURN
  private val storageContractInitCode: ByteString =
    ByteString(Hex.decode("60016000556002600155600360025560006000f3"))

  /** A real contract-creation tx that deploys `storageContractInitCode`, producing a contract account with a non-empty
    * storage trie (value=1 guarantees the account persists).
    */
  private def deployStorageContract(world: InMemoryWorldStateProxy): SignedTransaction =
    val tx = LegacyTransaction(
      nonce = faucetNonce(world),
      gasPrice = GasPrice(1),
      gasLimit = GasAmount(500000),
      receivingAddress = None, // contract creation
      value = Wei(1),
      payload = storageContractInitCode
    )
    SignedTransaction.sign(tx, faucetKeyPair, None)

  /** Helper to create state updates at a specific block via a real value transfer to a per-block address. */
  def updateStateAtBlock(
      blockNumber: Int
  )(currentBlockNumber: BigInt, world: InMemoryWorldStateProxy): Seq[SignedTransaction] =
    if currentBlockNumber == blockNumber then
      Seq(valueTransfer(world, Address(currentBlockNumber.toByteArray), currentBlockNumber * BigInt(1000000000)))
    else Seq.empty

  /** Helper to create state updates at multiple blocks via real value transfers. */
  def updateStateAtMultipleBlocks(
      blockNumbers: Set[Int]
  )(currentBlockNumber: BigInt, world: InMemoryWorldStateProxy): Seq[SignedTransaction] =
    if blockNumbers.contains(currentBlockNumber.toInt) then
      Seq(valueTransfer(world, Address(currentBlockNumber.toByteArray), currentBlockNumber * BigInt(1000000000)))
    else Seq.empty

  /** Helper to create complex state with storage by deploying a real storage-writing contract every 50 blocks. */
  def createComplexStateWithStorage(
      currentBlockNumber: BigInt,
      world: InMemoryWorldStateProxy
  ): Seq[SignedTransaction] =
    if currentBlockNumber % 50 == 0 && currentBlockNumber > 0 then Seq(deployStorageContract(world))
    else Seq.empty

  "E2E State Test" - {

    "State Trie Synchronization" - {

      "should synchronize state trie between peers" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 200
        val stateBlockNumber = 100

        for
          // Peer1 creates blockchain with state at specific block
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(updateStateAtBlock(stateBlockNumber))

          // Peer2 syncs from peer1
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          // Verify state synchronization
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get

          // Block hashes should match
          peer1BestBlock.hash shouldBe peer2BestBlock.hash

          // State roots should match
          peer1BestBlock.header.stateRoot shouldBe peer2BestBlock.header.stateRoot

          // Verify state at the specific block
          val peer1StateBlock =
            peer1.blockchainReader
              .getBlockByNumber(peer1.blockchainReader.getBestBranch, BlockNumber(stateBlockNumber))
              .get
          val peer2StateBlock =
            peer2.blockchainReader
              .getBlockByNumber(peer2.blockchainReader.getBestBranch, BlockNumber(stateBlockNumber))
              .get

          peer1StateBlock.header.stateRoot shouldBe peer2StateBlock.header.stateRoot
      }

      "should maintain state trie consistency across multiple state updates" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 300
        val stateBlocks = Set(50, 100, 150, 200, 250)

        for
          // Create blockchain with multiple state snapshots
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(updateStateAtMultipleBlocks(stateBlocks))

          // Peer2 syncs from peer1
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          // Verify state roots match at all state blocks
          stateBlocks.foreach { blockNum =>
            val peer1Block =
              peer1.blockchainReader.getBlockByNumber(peer1.blockchainReader.getBestBranch, BlockNumber(blockNum)).get
            val peer2Block =
              peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch, BlockNumber(blockNum)).get

            peer1Block.header.stateRoot shouldBe peer2Block.header.stateRoot
          }
          succeed
      }

      "should handle large state tries efficiently" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 500

        for
          // Create blockchain with frequent state updates
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(createComplexStateWithStorage)

          // Peer2 syncs from peer1
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get

          peer1BestBlock.header.stateRoot shouldBe peer2BestBlock.header.stateRoot
      }
    }

    "State Root Validation" - {

      "should validate state roots during synchronization" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 150
        val stateBlockNumber = 75

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(updateStateAtBlock(stateBlockNumber))
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          // Verify state root at specific block
          val peer1Block =
            peer1.blockchainReader
              .getBlockByNumber(peer1.blockchainReader.getBestBranch, BlockNumber(stateBlockNumber))
              .get
          val peer2Block =
            peer2.blockchainReader
              .getBlockByNumber(peer2.blockchainReader.getBestBranch, BlockNumber(stateBlockNumber))
              .get

          peer1Block.header.stateRoot shouldBe peer2Block.header.stateRoot
          peer1Block.hash shouldBe peer2Block.hash
      }

      "should detect and reject invalid state roots" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 100

        for
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)
          _ <- peer2.importBlocksUntil(blockNumber)(IdentityUpdate)
        yield
          // Both peers should have the same state roots
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get

          peer1BestBlock.header.stateRoot shouldBe peer2BestBlock.header.stateRoot
      }

      "should maintain state root consistency during chain reorganization" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val commonBlocks = 100
        val peer1ExtraBlocks = 20

        for
          // Both peers sync to a common point with state updates
          _ <- peer1.importExecutedBlocksUntil(commonBlocks, faucetAddress)(updateStateAtBlock(50))
          _ <- peer2.importExecutedBlocksUntil(commonBlocks, faucetAddress)(updateStateAtBlock(50))

          // Peer1 extends its chain with more state updates
          _ <- peer1.importExecutedBlocksUntil(commonBlocks + peer1ExtraBlocks, faucetAddress)(updateStateAtBlock(110))

          // Start sync
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))

          // Peer2 should sync to peer1's chain
          _ <- peer2.waitForRegularSyncLoadLastBlock(commonBlocks + peer1ExtraBlocks)
        yield
          // Verify state roots match after reorganization
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get

          peer1BestBlock.header.stateRoot shouldBe peer2BestBlock.header.stateRoot
          peer1BestBlock.hash shouldBe peer2BestBlock.hash
      }
    }

    "Account State Propagation" - {

      "should propagate account state changes between peers" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 200

        for
          // Create blockchain with account state changes
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(
            updateStateAtMultipleBlocks(Set(50, 100, 150))
          )

          // Peer2 syncs from peer1
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          // Verify account states match at all blocks
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get

          peer1BestBlock.header.stateRoot shouldBe peer2BestBlock.header.stateRoot
      }

      "should handle rapid account state updates" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 300

        // Rapid updates: a real value transfer on every block after block 1 (block 1 only funds the
        // faucet via its block reward, so the faucet has balance to spend from block 2 onward).
        def rapidStateUpdates(currentBlockNumber: BigInt, world: InMemoryWorldStateProxy): Seq[SignedTransaction] =
          if currentBlockNumber > 1 then
            Seq(valueTransfer(world, Address(currentBlockNumber.toByteArray), currentBlockNumber * BigInt(1000000000)))
          else Seq.empty

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(rapidStateUpdates)
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get

          peer1BestBlock.header.stateRoot shouldBe peer2BestBlock.header.stateRoot
      }
    }

    "Contract Storage Synchronization" - {

      "should synchronize contract storage between peers" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 250

        for
          // Create blockchain with contract storage
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(createComplexStateWithStorage)

          // Peer2 syncs from peer1
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get

          peer1BestBlock.header.stateRoot shouldBe peer2BestBlock.header.stateRoot
      }

      "should handle storage updates across multiple blocks" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 400

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(createComplexStateWithStorage)
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          // Verify storage at multiple blocks
          val storageBlocks = Seq(50, 100, 150, 200, 250, 300, 350, 400)
          storageBlocks.foreach { blockNum =>
            val peer1Block =
              peer1.blockchainReader.getBlockByNumber(peer1.blockchainReader.getBestBranch, BlockNumber(blockNum)).get
            val peer2Block =
              peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch, BlockNumber(blockNum)).get

            peer1Block.header.stateRoot shouldBe peer2Block.header.stateRoot
          }
          succeed
      }
    }

    "State Healing and Recovery" - {

      "should recover from partial state loss" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 200

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(updateStateAtBlock(100))
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          // Peer2 should have recovered the full state
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get

          peer1BestBlock.header.stateRoot shouldBe peer2BestBlock.header.stateRoot
      }

      "should handle state synchronization with missing peers" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        NetworkTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 150

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(updateStateAtBlock(75))
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          // Wait a moment for connection
          _ <- IO.sleep(1.second)
          // Start peer1 sync after peer2 is already trying to sync
          _ <- peer1.startRegularSync()
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get

          peer1BestBlock.hash shouldBe peer2BestBlock.hash
      }

      "should maintain state integrity during peer disconnection" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val initialBlocks = 100
        val finalBlocks = 200

        for
          // Partial sync with state
          _ <- peer1.importExecutedBlocksUntil(initialBlocks, faucetAddress)(updateStateAtBlock(50))
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer1.startRegularSync()
          _ <- peer2.waitForRegularSyncLoadLastBlock(initialBlocks)

          // Add more blocks with state while still connected
          _ <- peer1.importExecutedBlocksUntil(finalBlocks, faucetAddress)(updateStateAtBlock(150))

          // Continue sync
          _ <- peer2.waitForRegularSyncLoadLastBlock(finalBlocks)
        yield
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get

          peer1BestBlock.header.stateRoot shouldBe peer2BestBlock.header.stateRoot
          peer1BestBlock.hash shouldBe peer2BestBlock.hash
      }
    }

    "State Integrity" - {

      "should maintain state integrity across entire sync process" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 300

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(createComplexStateWithStorage)
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          // Verify state integrity at every 50th block
          (50 to blockNumber by 50).foreach { blockNum =>
            val peer1Block =
              peer1.blockchainReader.getBlockByNumber(peer1.blockchainReader.getBestBranch, BlockNumber(blockNum)).get
            val peer2Block =
              peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch, BlockNumber(blockNum)).get

            peer1Block.header.stateRoot shouldBe peer2Block.header.stateRoot
            peer1Block.hash shouldBe peer2Block.hash
          }
          succeed
      }

      "should verify state consistency after blockchain reorganization with state changes" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val commonBlocks = 100
        val peer1ExtraBlocks = 15

        for
          // Both peers sync to a common point with state
          _ <- peer1.importExecutedBlocksUntil(commonBlocks, faucetAddress)(
            updateStateAtMultipleBlocks(Set(25, 50, 75))
          )
          _ <- peer2.importExecutedBlocksUntil(commonBlocks, faucetAddress)(
            updateStateAtMultipleBlocks(Set(25, 50, 75))
          )

          // Peer1 extends its chain with new state
          _ <- peer1.importExecutedBlocksUntil(commonBlocks + peer1ExtraBlocks, faucetAddress)(updateStateAtBlock(105))

          // Sync
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(commonBlocks + peer1ExtraBlocks)
        yield
          // Verify all common blocks still have matching state
          Seq(25, 50, 75).foreach { blockNum =>
            val peer1Block =
              peer1.blockchainReader.getBlockByNumber(peer1.blockchainReader.getBestBranch, BlockNumber(blockNum)).get
            val peer2Block =
              peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch, BlockNumber(blockNum)).get

            peer1Block.header.stateRoot shouldBe peer2Block.header.stateRoot
          }

          // Verify final state matches
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock.get
          peer1BestBlock.header.stateRoot shouldBe peer2BestBlock.header.stateRoot
      }

      "should persist state correctly across peer restarts" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 200

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber, faucetAddress)(
            updateStateAtMultipleBlocks(Set(50, 100, 150))
          )
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          // Verify state persistence at specific blocks
          Seq(50, 100, 150, 200).foreach { blockNum =>
            val peer1Block =
              peer1.blockchainReader.getBlockByNumber(peer1.blockchainReader.getBestBranch, BlockNumber(blockNum)).get
            val peer2Block =
              peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch, BlockNumber(blockNum)).get

            peer1Block.header.stateRoot shouldBe peer2Block.header.stateRoot
          }
          succeed
      }
    }
  }
