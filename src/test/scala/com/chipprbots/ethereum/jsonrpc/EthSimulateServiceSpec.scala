package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.testing.Tags.*

/** Unit tests for EthSimulateService (eth_simulateV1).
  *
  * geth reference: internal/ethapi/api.go (simulateOpts / doCall), core/state/simulator.go
  */
class EthSimulateServiceSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with NormalPatience:

  implicit val runtime: IORuntime = IORuntime.global

  // ── request-level validation ────────────────────────────────────────────────

  "EthSimulateService.ethSimulate" should
    "return SimulateClientLimitExceeded when blockStateCalls exceeds the max count" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      val req: EthSimulateRequest =
        EthSimulateRequest(blockStateCalls = Seq.fill(MaxBlockStateCalls + 1)(BlockStateCall()))

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isLeft shouldBe true
      result.swap.getOrElse(fail("expected Left")).code shouldBe -38026

  it should "return SimulateClientLimitExceeded when gap-filling blocks exceed the max count" taggedAs (
    UnitTest,
    RPCTest
  ) in
    new TestSetup:
      saveAsLatest(block)

      val req: EthSimulateRequest = EthSimulateRequest(
        blockStateCalls = Seq(
          BlockStateCall(blockOverrides = Some(BlockOverrides(number = Some(block.header.number.value + 300))))
        )
      )

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isLeft shouldBe true
      val err: JsonRpcError = result.swap.getOrElse(fail("expected Left"))
      err.code shouldBe -38026
      err.message should include("too many blocks")

  it should "return a LogicError when the base block cannot be resolved" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      val req: EthSimulateRequest = EthSimulateRequest(
        blockStateCalls = Seq.empty,
        blockTag = BlockParam.WithNumber(BigInt(999999999))
      )

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result shouldBe Left(JsonRpcError.LogicError("header not found"))

  it should "return SimulateBlockNumberNotIncreasing when a later block number does not increase" taggedAs (
    UnitTest,
    RPCTest
  ) in
    new TestSetup:
      saveAsLatest(block)

      val req: EthSimulateRequest = EthSimulateRequest(
        blockStateCalls = Seq(
          BlockStateCall(),
          BlockStateCall(blockOverrides = Some(BlockOverrides(number = Some(block.header.number.value + 1))))
        )
      )

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isLeft shouldBe true
      result.swap.getOrElse(fail("expected Left")).code shouldBe -38020

  it should "return SimulateTimestampNotIncreasing when a later timestamp does not increase" taggedAs (
    UnitTest,
    RPCTest
  ) in
    new TestSetup:
      saveAsLatest(block)
      val baseTs: BigInt = BigInt(block.header.unixTimestamp.toLong)

      val req: EthSimulateRequest = EthSimulateRequest(
        blockStateCalls = Seq(
          BlockStateCall(blockOverrides = Some(BlockOverrides(time = Some(baseTs + 100)))),
          BlockStateCall(blockOverrides = Some(BlockOverrides(time = Some(baseTs + 50))))
        )
      )

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isLeft shouldBe true
      result.swap.getOrElse(fail("expected Left")).code shouldBe -38021

  // ── core simulate path ──────────────────────────────────────────────────────

  it should "execute a simple value-transfer call and report success" taggedAs (UnitTest, RPCTest) in new TestSetup:
    saveAsLatest(block)
    val sender: Address = Address(0xaaaa1)
    val receiver: Address = Address(0xaaaa2)

    val req: EthSimulateRequest = EthSimulateRequest(
      blockStateCalls = Seq(
        BlockStateCall(
          stateOverrides = Some(Map(sender -> StateOverride(balance = Some(BigInt("1000000000000000000"))))),
          calls = Some(Seq(SimulateCall(from = Some(sender), to = Some(receiver), value = Some(Wei(100)))))
        )
      )
    )

    val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

    result.isRight shouldBe true
    val response: EthSimulateResponse = result.getOrElse(fail("expected Right"))
    response.blocks.size shouldBe 1
    response.blocks.head.calls.size shouldBe 1
    response.blocks.head.calls.head.status shouldBe BigInt(1)

  it should "return SimulateInsufficientFunds when the sender cannot cover the value transfer" taggedAs (
    UnitTest,
    RPCTest
  ) in
    new TestSetup:
      saveAsLatest(block)
      val sender: Address = Address(0xbbbb1)
      val receiver: Address = Address(0xbbbb2)

      val req: EthSimulateRequest = EthSimulateRequest(
        blockStateCalls = Seq(
          BlockStateCall(calls =
            Some(Seq(SimulateCall(from = Some(sender), to = Some(receiver), value = Some(Wei(100)))))
          )
        )
      )

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isLeft shouldBe true
      result.swap.getOrElse(fail("expected Left")).code shouldBe -38014

  it should "simulate multiple blocks and increase block numbers sequentially" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      saveAsLatest(block)

      val req: EthSimulateRequest = EthSimulateRequest(blockStateCalls = Seq(BlockStateCall(), BlockStateCall()))

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isRight shouldBe true
      val response: EthSimulateResponse = result.getOrElse(fail("expected Right"))
      response.blocks.size shouldBe 2
      response.blocks(0).header.number shouldBe block.header.number + 1
      response.blocks(1).header.number shouldBe block.header.number + 2

  it should "honor an explicit nonce override on the resulting transaction" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      saveAsLatest(block)
      val sender: Address = Address(0xcccc1)
      val receiver: Address = Address(0xcccc2)

      val req: EthSimulateRequest = EthSimulateRequest(
        blockStateCalls = Seq(
          BlockStateCall(
            calls = Some(Seq(SimulateCall(from = Some(sender), to = Some(receiver), nonce = Some(Nonce(BigInt(5))))))
          )
        )
      )

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isRight shouldBe true
      val response: EthSimulateResponse = result.getOrElse(fail("expected Right"))
      response.blocks.head.transactions.head.tx.nonce shouldBe Nonce(BigInt(5))

  // ── state overrides ──────────────────────────────────────────────────────────

  it should "apply a code override and execute real EVM bytecode" taggedAs (UnitTest, RPCTest) in new TestSetup:
    saveAsLatest(block)
    val contractAddr: Address = Address(0xdddd1)
    // PUSH1 0x2a; PUSH1 0x00; MSTORE; PUSH1 0x20; PUSH1 0x00; RETURN  -- returns the word 42
    val returnFortyTwo: ByteString = ByteString(Hex.decode("602a60005260206000f3"))

    val req: EthSimulateRequest = EthSimulateRequest(
      blockStateCalls = Seq(
        BlockStateCall(
          stateOverrides = Some(Map(contractAddr -> StateOverride(code = Some(returnFortyTwo)))),
          calls = Some(Seq(SimulateCall(to = Some(contractAddr))))
        )
      )
    )

    val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

    result.isRight shouldBe true
    val response: EthSimulateResponse = result.getOrElse(fail("expected Right"))
    response.blocks.head.calls.head.status shouldBe BigInt(1)
    response.blocks.head.calls.head.returnData shouldBe UInt256(BigInt(42)).bytes

  it should
    "differentiate `state` (full replace) from `stateDiff` (merge) across successive blocks" taggedAs (
      UnitTest,
      RPCTest
    ) in
    new TestSetup:
      saveAsLatest(block)
      val contractAddr: Address = Address(0xeeee1)
      // PUSH1 0x00; CALLDATALOAD; SLOAD; PUSH1 0x00; MSTORE; PUSH1 0x20; PUSH1 0x00; RETURN
      // Reads storage at the slot given by the first 32 bytes of calldata and returns it.
      val sloadFromCalldata: ByteString = ByteString(Hex.decode("6000355460005260206000f3"))
      def readSlot(slot: BigInt): SimulateCall =
        SimulateCall(to = Some(contractAddr), input = Some(UInt256(slot).bytes))

      val req: EthSimulateRequest = EthSimulateRequest(
        blockStateCalls = Seq(
          // Block 1: deploy the contract and set slot 0 = 100 via a full `state` replace.
          BlockStateCall(
            stateOverrides = Some(
              Map(
                contractAddr -> StateOverride(
                  code = Some(sloadFromCalldata),
                  state = Some(Map(BigInt(0) -> BigInt(100)))
                )
              )
            ),
            calls = Some(Seq(readSlot(0)))
          ),
          // Block 2: another full `state` replace with only slot 1 set — slot 0 must be cleared.
          BlockStateCall(
            stateOverrides = Some(Map(contractAddr -> StateOverride(state = Some(Map(BigInt(1) -> BigInt(200)))))),
            calls = Some(Seq(readSlot(0), readSlot(1)))
          ),
          // Block 3: a `stateDiff` merge adding slot 2 — slot 1 must be preserved.
          BlockStateCall(
            stateOverrides = Some(Map(contractAddr -> StateOverride(stateDiff = Some(Map(BigInt(2) -> BigInt(300)))))),
            calls = Some(Seq(readSlot(1), readSlot(2)))
          )
        )
      )

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isRight shouldBe true
      val blocks: Seq[SimulateBlockResult] = result.getOrElse(fail("expected Right")).blocks

      blocks(0).calls(0).returnData shouldBe UInt256(BigInt(100)).bytes

      blocks(1).calls(0).returnData shouldBe UInt256(BigInt(0)).bytes // wiped by full replace
      blocks(1).calls(1).returnData shouldBe UInt256(BigInt(200)).bytes

      blocks(2).calls(0).returnData shouldBe UInt256(BigInt(200)).bytes // preserved by merge
      blocks(2).calls(1).returnData shouldBe UInt256(BigInt(300)).bytes

  it should "reject movePrecompileToAddress on a non-precompile source address" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      saveAsLatest(block)
      val notAPrecompile: Address = Address(0x50)

      val req: EthSimulateRequest = EthSimulateRequest(
        blockStateCalls = Seq(
          BlockStateCall(
            stateOverrides = Some(Map(notAPrecompile -> StateOverride(movePrecompileToAddress = Some(Address(0x9999)))))
          )
        )
      )

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isLeft shouldBe true
      result.swap.getOrElse(fail("expected Left")).message should include("is not a precompile")

  it should "accept movePrecompileToAddress on a legitimate precompile address" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      saveAsLatest(block)

      val req: EthSimulateRequest = EthSimulateRequest(
        blockStateCalls = Seq(
          BlockStateCall(
            stateOverrides = Some(Map(Address(1) -> StateOverride(movePrecompileToAddress = Some(Address(0x9999)))))
          )
        )
      )

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isRight shouldBe true

  // ── base-fee propagation (EIP-1559) ─────────────────────────────────────────

  it should "compute the next base fee via the EIP-1559 formula when validation is enabled" taggedAs (
    UnitTest,
    RPCTest
  ) in
    new TestSetup:
      val baseFeeHeader: BlockHeader = block.header.copy(
        gasLimit = GasAmount(1000000),
        gasUsed = GasAmount(0),
        extraFields = HefPostEip1559(BaseFeePerGas(BigInt(1000000000)))
      )
      val baseFeeBlock: Block = Block(baseFeeHeader, BlockBody.empty)
      saveAsLatest(baseFeeBlock)

      val req: EthSimulateRequest =
        EthSimulateRequest(blockStateCalls = Seq(BlockStateCall()), validation = true)

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isRight shouldBe true
      val response: EthSimulateResponse = result.getOrElse(fail("expected Right"))
      response.blocks.head.header.baseFee shouldBe Some(BaseFeePerGas(BigInt(875000000)))

  it should "use an explicit baseFeePerGas override instead of computing one" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      val baseFeeHeader: BlockHeader = block.header.copy(
        gasLimit = GasAmount(1000000),
        gasUsed = GasAmount(0),
        extraFields = HefPostEip1559(BaseFeePerGas(BigInt(1000000000)))
      )
      val baseFeeBlock: Block = Block(baseFeeHeader, BlockBody.empty)
      saveAsLatest(baseFeeBlock)

      val req: EthSimulateRequest = EthSimulateRequest(
        blockStateCalls = Seq(BlockStateCall(blockOverrides = Some(BlockOverrides(baseFeePerGas = Some(BigInt(42)))))),
        validation = true
      )

      val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

      result.isRight shouldBe true
      val response: EthSimulateResponse = result.getOrElse(fail("expected Right"))
      response.blocks.head.header.baseFee shouldBe Some(BaseFeePerGas(BigInt(42)))

  // ── Cancun / Prague system-contract injection (EIP-4788 / EIP-2935) ─────────

  it should "produce a Cancun-shaped header when the simulated timestamp is at or beyond the Cancun activation" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    saveAsLatest(block)

    val req: EthSimulateRequest = EthSimulateRequest(
      blockStateCalls = Seq(BlockStateCall(blockOverrides = Some(BlockOverrides(time = Some(BigInt(9999999995L))))))
    )

    val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

    result.isRight shouldBe true
    val header: BlockHeader = result.getOrElse(fail("expected Right")).blocks.head.header
    header.withdrawalsRoot shouldBe Some(EmptyWithdrawalsRoot)
    header.parentBeaconBlockRoot shouldBe Some(BlockHash(ByteString(new Array[Byte](32))))
    header.blobGasUsed shouldBe Some(BigInt(0))

  it should "produce a Prague-shaped header when the simulated timestamp is at or beyond the Prague activation" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    saveAsLatest(block)

    val req: EthSimulateRequest = EthSimulateRequest(
      blockStateCalls = Seq(BlockStateCall(blockOverrides = Some(BlockOverrides(time = Some(BigInt(9999999998L))))))
    )

    val result: Either[JsonRpcError, EthSimulateResponse] = service.ethSimulate(req).unsafeRunSync()

    result.isRight shouldBe true
    val header: BlockHeader = result.getOrElse(fail("expected Right")).blocks.head.header
    header.withdrawalsRoot shouldBe Some(EmptyWithdrawalsRoot)
    header.requestsHash shouldBe Some(EmptyRequestsHash)

  // ── TestSetup ────────────────────────────────────────────────────────────────

  class TestSetup() extends EphemBlockchainTestSetup:

    // The fixture header's stateRoot is arbitrary fixture data unbacked by any real trie
    // nodes in the ephemeral storage. Swap it for the canonical empty-trie root so the
    // simulated world starts from a genuinely empty (but valid) account trie.
    val block: Block = Block(
      Fixtures.Blocks.Block3125369.header.copy(stateRoot = TrieRoot(EmptyTrieRoot)),
      BlockBody.empty
    )

    def saveAsLatest(b: Block): Unit =
      blockchainWriter.storeBlock(b).commit()
      blockchainWriter.saveBestKnownBlocks(b.hash, b.number.value)

    lazy val service: EthSimulateService = new EthSimulateService(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      mining,
      blockchainConfig
    )
