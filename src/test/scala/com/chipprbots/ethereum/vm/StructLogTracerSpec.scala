package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.vm.Fixtures.blockchainConfig
import com.chipprbots.ethereum.vm.MockWorldState.PC
import com.chipprbots.ethereum.vm.MockWorldState.TestVM

/** Unit test for [[StructLogTracer]] — regression guard for STRUCTLOG-01 (debug_traceTransaction's default tracer
  * returned JNothing because getResult never assembled the collected steps/gas/failed/returnValue into JSON).
  *
  * go-ethereum reference: eth/tracers/logger/logger.go — ExecutionResult{Gas, Failed, ReturnValue, StructLogs}.
  */
class StructLogTracerSpec extends AnyWordSpec with Matchers:

  "StructLogTracer.getResult" should {

    "return JNothing-free JSON with gas/failed/returnValue/structLogs after a traced transaction" in new TestSetup:
      val tracer = new StructLogTracer(enableMemory = false, enableStorage = false)

      tracer.onStep(ADD, prevState, nextState)

      val output: ByteString = ByteString(Array[Byte](0x2a))
      tracer.onTxEnd(gasUsed = GasAmount(21123), output = output, error = None)

      val result: JValue = tracer.getResult

      result should not be JNothing

      val JObject(fields) = result: @unchecked
      val fieldMap = fields.toMap

      fieldMap("gas") shouldBe JInt(21123)
      fieldMap("failed") shouldBe JBool(false)
      fieldMap("returnValue") shouldBe JString("0x2a")

      val JArray(structLogs) = fieldMap("structLogs"): @unchecked
      structLogs should have size 1

      val JObject(stepFields) = structLogs.head: @unchecked
      val stepMap = stepFields.toMap
      stepMap("op") shouldBe JString("ADD")
      stepMap("pc") shouldBe JInt(0)
      stepMap("depth") shouldBe JInt(1)
      val JArray(stack) = stepMap("stack"): @unchecked
      // Stack.push(Seq(1, 2)) makes 2 the top-most element; toSeq (and thus the structLog) lists top-of-stack first.
      stack shouldBe List(JString("0x2"), JString("0x1"))

    "mark failed = true and preserve the revert data when the traced tx errors" in new TestSetup:
      val tracer = new StructLogTracer()

      tracer.onTxEnd(gasUsed = GasAmount(50000), output = ByteString.empty, error = Some("execution reverted"))

      val JObject(fields) = tracer.getResult: @unchecked
      val fieldMap = fields.toMap

      fieldMap("failed") shouldBe JBool(true)
      fieldMap("returnValue") shouldBe JString("0x")
      fieldMap("structLogs") shouldBe JArray(Nil)
  }

  trait TestSetup:
    val config: EvmConfig = EvmConfig.ConstantinopleConfigBuilder(blockchainConfig)
    val vm = new TestVM

    val senderAddr: Address = Address(0xcafebabeL)
    val senderAcc: Account = Account(nonce = 1, balance = 1000000)
    val assemblyCode: ByteString = ByteString(ADD.code)

    val defaultWorld: MockWorldState = MockWorldState()
      .saveAccount(senderAddr, senderAcc.copy(codeHash = CodeHash(kec256(assemblyCode))))
      .saveCode(senderAddr, assemblyCode)

    val blockHeader = BlockFixtures.ValidBlock.header.copy(
      difficulty = Difficulty(1000000),
      number = BlockNumber(1),
      gasLimit = GasAmount(10000000),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(0)
    )

    val context: PC = ProgramContext(
      callerAddr = senderAddr,
      originAddr = senderAddr,
      recipientAddr = None,
      gasPrice = 1,
      startGas = GasAmount(1000000),
      inputData = ByteString.empty,
      value = 100,
      endowment = 100,
      doTransfer = true,
      blockHeader = blockHeader,
      callDepth = 0,
      world = defaultWorld,
      initialAddressesToDelete = Set(),
      evmConfig = config,
      originalWorld = defaultWorld,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )
    val env: ExecEnv = ExecEnv(context, assemblyCode, context.originAddr)

    val prevState: ProgramState[MockWorldState, MockStorage] =
      ProgramState(vm, context, env).withStack(Stack.empty().push(Seq(UInt256(1), UInt256(2))))

    val nextState: ProgramState[MockWorldState, MockStorage] =
      prevState.spendGas(GasAmount(3)).step(1).withStack(Stack.empty().push(UInt256(3)))
