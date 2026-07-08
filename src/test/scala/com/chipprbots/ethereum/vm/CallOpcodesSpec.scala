package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.vm.MockWorldState.*

import Fixtures.blockchainConfig

trait CallOpCodesBehaviors extends Matchers:
  this: AnyFlatSpec =>

  def callNormalTermination(fxt: CallOpFixture, call: CallResult): Unit =

    it should "update external account's storage" in {
      call.ownStorage shouldEqual MockStorage.Empty
      call.extStorage.data.size shouldEqual 3
    }

    it should "update external account's balance" in {
      call.extBalance shouldEqual call.value
      call.ownBalance shouldEqual fxt.initialBalance - call.value
    }

    it should "pass correct addresses and value" in {
      Address(call.extStorage.load(StorageKey(fxt.ownerOffset.toBigInt))) shouldEqual fxt.extAddr
      Address(call.extStorage.load(StorageKey(fxt.callerOffset.toBigInt))) shouldEqual fxt.ownerAddr
      call.extStorage.load(StorageKey(fxt.valueOffset.toBigInt)) shouldEqual call.value.toBigInt
    }

    it should "return 1" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256.One
    }

    it should "should store contract's return data in memory" in {
      // here the passed data size is equal to the contract's return data size (half of the input data)

      val expectedData = fxt.inputData.take(fxt.inputData.size / 2)
      val actualData = call.stateOut.memory.load(call.outOffset, call.outSize)._1
      actualData shouldEqual expectedData

      val expectedSize = (call.outOffset + call.outSize).toInt
      val actualSize = call.stateOut.memory.size
      expectedSize shouldEqual actualSize
    }

  def callDepthLimitReached(fxt: CallOpFixture, call: CallResult): Unit =

    it should "not modify world state" in {
      call.world shouldEqual fxt.worldWithExtAccount
    }

    it should "return 0" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256.Zero
    }

  def callValueGreaterThanBalance(fxt: CallOpFixture, call: CallResult): Unit =

    it should "not modify world state" in {
      call.world shouldEqual fxt.worldWithExtAccount
    }

    it should "return 0" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256.Zero
    }

  def callAbnormalTermination(fxt: CallOpFixture, call: CallResult): Unit =
    it should "should not modify world state" in {
      call.world shouldEqual fxt.worldWithInvalidProgram
    }

    it should "return 0" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256.Zero
    }

    it should "extend memory" in {
      UInt256(call.stateOut.memory.size) shouldEqual call.outOffset + call.outSize
    }

  def callNonExistent(fxt: CallOpFixture, call: CallResult): Unit =

    it should "create new account and add to its balance" in {
      call.extBalance shouldEqual call.value
      call.ownBalance shouldEqual fxt.initialBalance - call.value
    }

    it should "return 1" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256.One
    }

  def callPrecompiled(fxt: CallOpFixture, call: CallResult): Unit =

    it should "compute a correct result" in {
      // For invalid signature the return data should be empty, so the memory should not be modified.
      // This is more interesting than checking valid signatures which are tested elsewhere
      val (result, _) = call.stateOut.memory.load(call.outOffset, call.outSize)
      val expected = call.inputData

      result shouldEqual expected
    }

    it should "return 1" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256.One
    }

    it should "update precompiled contract's balance" in {
      call.extBalance shouldEqual call.value + 1
      call.ownBalance shouldEqual fxt.initialBalance - call.value
    }

  def callSelfdestruct(fxt: CallOpFixture): Unit =
    it should "refund the correct amount of gas" in {
      val context: PC = fxt.context.copy(world = fxt.worldWithSelfDestructProgram)
      val call = fxt.ExecuteCall(op = CALL, context)
      call.stateOut.gasRefund shouldBe GasAmount(call.stateOut.config.feeSchedule.R_selfdestruct)
    }

    it should "not refund gas if account was already self destructed" in {
      val context: PC =
        fxt.context.copy(world = fxt.worldWithSelfDestructProgram, initialAddressesToDelete = Set(fxt.extAddr))
      val call = fxt.ExecuteCall(op = CALL, context)
      call.stateOut.gasRefund shouldBe GasAmount.Zero
    }

    it should "destruct ether if own address equals refund address" in {
      val context: PC = fxt.context.copy(world = fxt.worldWithSelfDestructSelfProgram)
      val call = fxt.ExecuteCall(op = CALL, context)
      call.stateOut.world.getGuaranteedAccount(fxt.extAddr).balance shouldEqual UInt256.Zero
      call.stateOut.addressesToDelete.contains(fxt.extAddr) shouldBe true
    }

  def callRevert(fxt: CallOpFixture): Unit =
    val context: PC = fxt.context.copy(world = fxt.worldWithRevertProgram)
    val call = fxt.ExecuteCall(op = CALL, context)

    it should "return 0" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256.Zero
    }

    it should "store cause of reversion in memory" in {
      val resultingMemoryBytes = call.stateOut.memory.load(call.outOffset, 1)._1
      resultingMemoryBytes shouldEqual ByteString(fxt.valueToReturn.toByte)
    }

    it should "extend memory" in {
      UInt256(call.stateOut.memory.size) shouldEqual call.outOffset + call.outSize
    }

  def callMoreGasProvided(fxt: CallOpFixture): Unit =
    def call(config: EvmConfig): fxt.ExecuteCall =
      val context: PC = fxt.context.copy(evmConfig = config)
      fxt.ExecuteCall(op = CALL, context = context, gas = UInt256.MaxValue / 2)

    def callVarMemCost(config: EvmConfig): fxt.ExecuteCall =

      /** Amount of memory which causes the improper OOG exception, if we don take memcost into account during
        * calculation of post EIP150 CALLOp gasCap: gasCap(state, gas, gExtra + memCost)
        */
      val gasFailingBeforeEIP150Fix = 141072

      val context: PC = fxt.context.copy(evmConfig = config)
      fxt.ExecuteCall(
        op = CALL,
        context = context,
        inOffset = UInt256.Zero,
        inSize = fxt.inputData.size,
        outOffset = fxt.inputData.size,
        outSize = gasFailingBeforeEIP150Fix
      )

    it should "go OOG before EIP-150" in {
      call(EvmConfig.HomesteadConfigBuilder(blockchainConfig)).stateOut.error shouldEqual Some(OutOfGas)
    }

    it should "cap the provided gas after EIP-150" in {
      call(EvmConfig.PostEIP150ConfigBuilder(blockchainConfig)).stateOut.stack.pop()._1 shouldEqual UInt256.One
    }

    it should "go OOG before EIP-150 becaouse of extensive memory cost" in {
      callVarMemCost(EvmConfig.HomesteadConfigBuilder(blockchainConfig)).stateOut.error shouldEqual Some(OutOfGas)
    }

    it should "cap memory cost post EIP-150" in {
      val CallResult = callVarMemCost(EvmConfig.PostEIP150ConfigBuilder(blockchainConfig))
      CallResult.stateOut.stack.pop()._1 shouldEqual UInt256.One
    }

  def callCodeNormalTermination(fxt: CallOpFixture, call: CallResult): Unit =
    it should "update own account's storage" in {
      call.extStorage shouldEqual MockStorage.Empty
      call.ownStorage.data.size shouldEqual 3
    }

    it should "not update any account's balance" in {
      call.extBalance shouldEqual UInt256.Zero
      call.ownBalance shouldEqual fxt.initialBalance
    }

    it should "pass correct addresses and value" in {
      Address(call.ownStorage.load(StorageKey(fxt.ownerOffset.toBigInt))) shouldEqual fxt.ownerAddr
      Address(call.ownStorage.load(StorageKey(fxt.callerOffset.toBigInt))) shouldEqual fxt.ownerAddr
      call.ownStorage.load(StorageKey(fxt.valueOffset.toBigInt)) shouldEqual call.value.toBigInt
    }

    it should "return 1" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256(1)
    }

    it should "should store contract's return data in memory" in {
      // here the passed data size is greater than the contract's return data size

      val expectedData = fxt.inputData.take(fxt.inputData.size / 2).padTo(call.outSize.toInt, 0)
      val actualData = call.stateOut.memory.load(call.outOffset, call.outSize)._1
      actualData shouldEqual expectedData

      val expectedSize = (call.outOffset + call.outSize).toInt
      val actualSize = call.stateOut.memory.size
      expectedSize shouldEqual actualSize
    }

  def callCodeNonExistent(fxt: CallOpFixture, call: CallResult): Unit =

    it should "not modify world state" in {
      call.world shouldEqual fxt.worldWithoutExtAccount
    }

    it should "return 1" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256(1)
    }

  def callCodePrecompiled(fxt: CallOpFixture, call: CallResult): Unit =
    it should "compute a correct result" in {
      val (result, _) = call.stateOut.memory.load(call.outOffset, call.outSize)
      val expected = sha256(call.inputData)

      result shouldEqual expected
    }

    it should "return 1" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256.One
    }

    it should "not update precompiled contract's balance" in {
      call.extBalance shouldEqual 1
      call.ownBalance shouldEqual fxt.initialBalance
    }

  def delegateCallNormalTermination(fxt: CallOpFixture, call: CallResult): Unit =
    it should "update own account's storage" in {
      call.extStorage shouldEqual MockStorage.Empty
      call.ownStorage.data.size shouldEqual 3
    }

    it should "not update any account's balance" in {
      call.extBalance shouldEqual UInt256.Zero
      call.ownBalance shouldEqual fxt.initialBalance
    }

    it should "pass correct addresses and value" in {
      Address(call.ownStorage.load(StorageKey(fxt.ownerOffset.toBigInt))) shouldEqual fxt.ownerAddr
      Address(call.ownStorage.load(StorageKey(fxt.callerOffset.toBigInt))) shouldEqual fxt.callerAddr
      call.ownStorage.load(StorageKey(fxt.valueOffset.toBigInt)) shouldEqual fxt.context.value.toBigInt
    }

    it should "return 1" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256(1)
    }

    it should "should store contract's return data in memory" in {
      // here the passed data size is less than the contract's return data size

      val expectedData = fxt.inputData.take(call.outSize.toInt)
      val actualData = call.stateOut.memory.load(call.outOffset, call.outSize)._1
      actualData shouldEqual expectedData

      val expectedSize = (call.outOffset + call.outSize).toInt
      val actualSize = call.stateOut.memory.size
      expectedSize shouldEqual actualSize
    }

  def delegateCallPrecompile(fxt: CallOpFixture, call: CallResult): Unit =
    it should "compute a correct result" in {
      val (result, _) = call.stateOut.memory.load(call.outOffset, call.outSize)
      val expected = ByteUtils.padLeft(ripemd160(call.inputData), 32)

      result shouldEqual expected
    }

    it should "return 1" in {
      call.stateOut.stack.pop()._1 shouldEqual UInt256.One
    }

    it should "not update precompiled contract's balance" in {
      call.extBalance shouldEqual 1
      call.ownBalance shouldEqual fxt.initialBalance
    }

// scalastyle:off object.name
// scalastyle:off file.size.limit
class CallOpcodesSpec extends AnyFlatSpec with CallOpCodesBehaviors with Matchers with ScalaCheckPropertyChecks:

  val config: EvmConfig = EvmConfig.ByzantiumConfigBuilder(blockchainConfig)
  val startState: MockWorldState = MockWorldState(touchedAccounts = Set.empty)
  import config.feeSchedule.*

  val fxt = new CallOpFixture(config, startState)

  behavior of "CALL when external contract terminates normally"

  locally {
    val call = fxt.ExecuteCall(op = CALL)

    behave.like(callNormalTermination(fxt, call))

    it should "consume correct gas (refund unused gas)" in {
      val expectedGas = fxt.requiredGas - G_callstipend + G_call + G_callvalue + fxt.expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALL when call depth limit is reached"

  locally {
    val context: PC = fxt.context.copy(callDepth = EvmConfig.MaxCallDepth)
    val call = fxt.ExecuteCall(op = CALL, context = context)

    behave.like(callDepthLimitReached(fxt, call))

    it should "consume correct gas (refund call gas)" in {
      val expectedGas = G_call + G_callvalue - G_callstipend + config.calcMemCost(32, 32, 16)
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALL when call value is greater than balance"

  locally {
    val call = fxt.ExecuteCall(op = CALL, value = fxt.initialBalance + 1)
    behave.like(callValueGreaterThanBalance(fxt, call))

    it should "consume correct gas (refund call gas)" in {
      val expectedGas = G_call + G_callvalue - G_callstipend + config.calcMemCost(32, 32, 16)
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALL when call value is zero"

  locally {
    val call = fxt.ExecuteCall(op = CALL, value = 0)

    it should "adjust gas cost" in {
      val expectedGas = fxt.requiredGas + G_call + fxt.expectedMemCost - (G_sset - G_sreset)
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALL when external contract terminates abnormally"

  locally {
    val context: PC = fxt.context.copy(world = fxt.worldWithInvalidProgram)
    val call = fxt.ExecuteCall(op = CALL, context)

    behave.like(callAbnormalTermination(fxt, call))

    it should "consume all call gas" in {

      val expectedGas = fxt.requiredGas + fxt.gasMargin + G_call + G_callvalue + fxt.expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALL when calling a non-existent account"

  locally {
    val context: PC = fxt.context.copy(world = fxt.worldWithoutExtAccount)
    val call = fxt.ExecuteCall(op = CALL, context)

    behave.like(callNonExistent(fxt, call))

    it should "consume correct gas (refund call gas, add new account modifier)" in {
      val expectedGas = G_call + G_callvalue + G_newaccount - G_callstipend + fxt.expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALL when calling a precompiled contract"

  locally {
    val contractAddress = Address(1) // ECDSA recovery
    val invalidSignature = ByteString(Array.fill(128)(0.toByte))
    val world = fxt.worldWithoutExtAccount.saveAccount(contractAddress, Account(balance = 1))
    val context: PC = fxt.context.copy(world = world)
    val call = fxt.ExecuteCall(
      op = CALL,
      context = context,
      to = contractAddress,
      inputData = invalidSignature,
      inOffset = 0,
      inSize = 128,
      outOffset = 0,
      outSize = 128
    )

    behave.like(callPrecompiled(fxt, call))

    it should "consume correct gas" in {
      val contractCost = UInt256(3000)
      val expectedGas = contractCost - G_callstipend + G_call + G_callvalue // memory not increased
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALL when calling a program that executes a SELFDESTRUCT"
  behave.like(callSelfdestruct(fxt))

  behavior of "CALL when calling a program that executes a REVERT"
  behave.like(callRevert(fxt))

  behavior of "CALL when calling a program that executes a SSTORE that clears the storage"

  locally {
    val context: PC = fxt.context.copy(world = fxt.worldWithSstoreWithClearProgram)
    val call = fxt.ExecuteCall(op = CALL, context)

    it should "refund the correct amount of gas" in {
      call.stateOut.gasRefund shouldBe GasAmount(call.stateOut.config.feeSchedule.R_sclear)
    }
  }

  behavior of "CALL when more gas than available is provided"
  behave.like(callMoreGasProvided(fxt))

  behavior of "CALLCODE when external code terminates normally"

  locally {
    val call = fxt.ExecuteCall(op = CALLCODE, outSize = fxt.inputData.size * 2)

    behave.like(callCodeNormalTermination(fxt, call))

    it should "consume correct gas (refund unused gas)" in {
      val expectedMemCost = config.calcMemCost(fxt.inputData.size, fxt.inputData.size, call.outSize)
      val expectedGas = fxt.requiredGas - G_callstipend + G_call + G_callvalue + expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALLCODE when call depth limit is reached"

  locally {
    val context: PC = fxt.context.copy(callDepth = EvmConfig.MaxCallDepth)
    val call = fxt.ExecuteCall(op = CALLCODE, context = context)

    behave.like(callDepthLimitReached(fxt, call))

    it should "consume correct gas (refund call gas)" in {
      val expectedGas = G_call + G_callvalue - G_callstipend + fxt.expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALLCODE when call value is greater than balance"

  locally {

    val call = fxt.ExecuteCall(op = CALLCODE, value = fxt.initialBalance + 1)

    behave.like(callValueGreaterThanBalance(fxt, call))

    it should "consume correct gas (refund call gas)" in {
      val expectedGas = G_call + G_callvalue - G_callstipend + fxt.expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALLCODE when call value is zero"

  locally {
    val call = fxt.ExecuteCall(op = CALLCODE, value = 0)

    it should "adjust gas cost" in {
      val expectedGas = fxt.requiredGas + G_call + fxt.expectedMemCost - (G_sset - G_sreset)
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALLCODE when external code terminates abnormally"

  locally {
    val context: PC = fxt.context.copy(world = fxt.worldWithInvalidProgram)
    val call = fxt.ExecuteCall(op = CALLCODE, context)

    behave.like(callAbnormalTermination(fxt, call))

    it should "consume all call gas" in {
      val expectedGas = fxt.requiredGas + fxt.gasMargin + G_call + G_callvalue + fxt.expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALLCODE when external account does not exist"

  locally {
    val context: PC = fxt.context.copy(world = fxt.worldWithoutExtAccount)
    val call = fxt.ExecuteCall(op = CALLCODE, context)

    behave.like(callCodeNonExistent(fxt, call))

    it should "consume correct gas (refund call gas)" in {
      val expectedGas = G_call + G_callvalue - G_callstipend + fxt.expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALLCODE when calling a precompiled contract"

  locally {
    val contractAddress = Address(2) // SHA256
    val inputData = ByteString(Array.fill(128)(1.toByte))
    val world = fxt.worldWithoutExtAccount.saveAccount(contractAddress, Account(balance = 1))
    val context: PC = fxt.context.copy(world = world)
    val call = fxt.ExecuteCall(
      op = CALLCODE,
      context = context,
      to = contractAddress,
      inputData = inputData,
      inOffset = 0,
      inSize = 128,
      outOffset = 128,
      outSize = 32
    )

    behave.like(callCodePrecompiled(fxt, call))

    it should "consume correct gas" in {
      val contractCost = 60 + 12 * wordsForBytes(inputData.size)
      val expectedGas = contractCost - G_callstipend + G_call + G_callvalue + config.calcMemCost(128, 128, 32)
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "CALLCODE when calling a program that executes a SELFDESTRUCT"

  locally {

    val context: PC = fxt.context.copy(world = fxt.worldWithSelfDestructProgram)
    val call = fxt.ExecuteCall(op = CALLCODE, context)

    it should "refund the correct amount of gas" in {
      call.stateOut.gasRefund shouldBe GasAmount(call.stateOut.config.feeSchedule.R_selfdestruct)
    }

  }

  behavior of "CALLCODE when calling a program that executes a SSTORE that clears the storage"

  locally {

    val context: PC = fxt.context.copy(world = fxt.worldWithSstoreWithClearProgram)
    val call = fxt.ExecuteCall(op = CALLCODE, context)

    it should "refund the correct amount of gas" in {
      call.stateOut.gasRefund shouldBe GasAmount(call.stateOut.config.feeSchedule.R_sclear)
    }
  }

  behavior of "CALLCODE when more gas than available is provided"

  locally {
    def call(config: EvmConfig): fxt.ExecuteCall =
      val context: PC = fxt.context.copy(evmConfig = config)
      fxt.ExecuteCall(op = CALLCODE, context = context, gas = UInt256.MaxValue / 2)

    it should "go OOG before EIP-150" in {
      call(EvmConfig.HomesteadConfigBuilder(blockchainConfig)).stateOut.error shouldEqual Some(OutOfGas)
    }

    it should "cap the provided gas after EIP-150" in {
      call(EvmConfig.PostEIP150ConfigBuilder(blockchainConfig)).stateOut.stack.pop()._1 shouldEqual UInt256.One
    }
  }

  behavior of "DELEGATECALL when external code terminates normally"

  locally {
    val call = fxt.ExecuteCall(op = DELEGATECALL, outSize = fxt.inputData.size / 4)

    behave.like(delegateCallNormalTermination(fxt, call))
    it should "consume correct gas (refund unused gas)" in {
      val expectedMemCost = config.calcMemCost(fxt.inputData.size, fxt.inputData.size, call.outSize)
      val expectedGas = fxt.requiredGas + G_call + expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "DELEGATECALL when call depth limit is reached"

  locally {

    val context: PC = fxt.context.copy(callDepth = EvmConfig.MaxCallDepth)
    val call = fxt.ExecuteCall(op = DELEGATECALL, context = context)

    behave.like(callDepthLimitReached(fxt, call))

    it should "consume correct gas (refund call gas)" in {
      val expectedGas = G_call + fxt.expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "DELEGATECALL when external code terminates abnormally"

  locally {
    val context: PC = fxt.context.copy(world = fxt.worldWithInvalidProgram)
    val call = fxt.ExecuteCall(op = DELEGATECALL, context)

    behave.like(callAbnormalTermination(fxt, call))

    it should "consume all call gas" in {
      val expectedGas = fxt.requiredGas + fxt.gasMargin + G_call + fxt.expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "DELEGATECALL when external account does not exist"

  locally {
    val context: PC = fxt.context.copy(world = fxt.worldWithoutExtAccount)
    val call = fxt.ExecuteCall(op = DELEGATECALL, context)

    behave.like(callCodeNonExistent(fxt, call))

    it should "consume correct gas (refund call gas)" in {
      val expectedGas = G_call + fxt.expectedMemCost
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "DELEGATECALL when calling a precompiled contract"

  locally {
    val contractAddress = Address(3) // RIPEMD160
    val inputData = ByteString(Array.fill(128)(1.toByte))
    val world = fxt.worldWithoutExtAccount.saveAccount(contractAddress, Account(balance = 1))
    val context: PC = fxt.context.copy(world = world)
    val call = fxt.ExecuteCall(
      op = DELEGATECALL,
      context = context,
      to = contractAddress,
      inputData = inputData,
      inOffset = 0,
      inSize = 128,
      outOffset = 128,
      outSize = 32
    )

    behave.like(delegateCallPrecompile(fxt, call))

    it should "consume correct gas" in {
      val contractCost = 600 + 120 * wordsForBytes(inputData.size)
      val expectedGas = contractCost + G_call + config.calcMemCost(128, 128, 20)
      call.stateOut.gasUsed shouldEqual expectedGas
    }
  }

  behavior of "DELEGATECALL when calling a program that executes a SELFDESTRUCT"

  locally {

    val context: PC = fxt.context.copy(world = fxt.worldWithSelfDestructProgram)
    val call = fxt.ExecuteCall(op = DELEGATECALL, context)

    it should "refund the correct amount of gas" in {
      call.stateOut.gasRefund shouldBe GasAmount(call.stateOut.config.feeSchedule.R_selfdestruct)
    }

  }

  behavior of "DELEGATECALL when calling a program that executes a SSTORE that clears the storage"

  locally {

    val context: PC = fxt.context.copy(world = fxt.worldWithSstoreWithClearProgram)
    val call = fxt.ExecuteCall(op = DELEGATECALL, context)

    it should "refund the correct amount of gas" in {
      call.stateOut.gasRefund shouldBe GasAmount(call.stateOut.config.feeSchedule.R_sclear)
    }
  }

  behavior of "DELEGATECALL when more gas than available is provided"

  locally {
    def call(config: EvmConfig): fxt.ExecuteCall =
      val context: PC = fxt.context.copy(evmConfig = config)
      fxt.ExecuteCall(op = DELEGATECALL, context = context, gas = UInt256.MaxValue / 2)

    it should "go OOG before EIP-150" in {
      call(EvmConfig.HomesteadConfigBuilder(blockchainConfig)).stateOut.error shouldEqual Some(OutOfGas)
    }

    it should "cap the provided gas after EIP-150" in {
      call(EvmConfig.PostEIP150ConfigBuilder(blockchainConfig)).stateOut.stack.pop()._1 shouldEqual UInt256.One
    }
  }

  /** This test should result in an OutOfGas error as (following the equations. on the DELEGATECALL opcode in the YP):
    * DELEGATECALL cost = memoryCost + C_extra + C_gascap and memoryCost = 0 (result written were input was) C_gascap \=
    * u_s[0] = UInt256.MaxValue - C_extra + 1 Then CALL cost = UInt256.MaxValue + 1 As the starting gas (startGas =
    * C_extra - 1) is much lower than the cost this should result in an OutOfGas exception
    */
  behavior of "DELEGATECALL when gas cost bigger than available gas DELEGATECALL"

  locally {

    val c_extra = config.feeSchedule.G_call
    val startGas = c_extra - 1
    val gas = UInt256.MaxValue - c_extra + 1 // u_s[0]
    val context: PC = fxt.context.copy(startGas = GasAmount(startGas))
    val call = fxt.ExecuteCall(
      op = DELEGATECALL,
      gas = gas,
      context = context,
      outOffset = UInt256.Zero
    )
    it should "return an OutOfGas error" in {
      call.stateOut.error shouldBe Some(OutOfGas)
    }
  }

  Seq(CALL, CALLCODE, DELEGATECALL).foreach { opCode =>
    s"CallOpCodes when $opCode processes returned data" should "handle memory expansion properly" in {

      val inputData = ByteString(Array[Byte](1).padTo(32, 1.toByte))
      val context: PC = fxt.context.copy(world = fxt.worldWithReturnSingleByteCode)

      val table = Table[Int](
        "Out Offset",
        0,
        inputData.size / 2,
        inputData.size * 2
      )

      forAll(table) { outOffset =>
        val call = fxt.ExecuteCall(
          op = opCode,
          outSize = inputData.size,
          outOffset = outOffset,
          context = context,
          inputData = inputData
        )

        val expectedSize = inputData.size + outOffset
        val expectedMemoryBytes =
          call.stateIn.memory.store(outOffset, fxt.valueToReturn.toByte).load(0, expectedSize)._1
        val resultingMemoryBytes = call.stateOut.memory.load(0, expectedSize)._1

        call.stateOut.memory.size shouldEqual expectedSize
        resultingMemoryBytes shouldEqual expectedMemoryBytes

      }
    }
  }
