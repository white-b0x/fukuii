package com.chipprbots.ethereum.vm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.vm.MockWorldState.*

import Fixtures.blockchainConfig

// scalastyle:off object.name
class CallOpcodesSpecPostEip161 extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  val config: EvmConfig = EvmConfig.PostEIP161ConfigBuilder(blockchainConfig)
  val startState: MockWorldState = MockWorldState(touchedAccounts = Set.empty, noEmptyAccountsCond = true)
  import config.feeSchedule.*

  val fxt = new CallOpFixture(config, startState)

  "CALL when call depth limit is reached" should "not modify world state" in {
    val context: PC = fxt.context.copy(callDepth = EvmConfig.MaxCallDepth)
    val call = fxt.ExecuteCall(op = CALL, context = context)

    call.world shouldEqual fxt.worldWithExtAccount
  }

  "CALL when call value is greater than balance" should "not modify world state" in {
    val call = fxt.ExecuteCall(op = CALL, value = fxt.initialBalance + 1)

    call.world shouldEqual fxt.worldWithExtAccount
  }

  "CALL when external contract terminates abnormally" should "modify only touched accounts by precompiled ripmd contract in world state" in {
    val touchedPrecompile = Address(3)

    val context: PC =
      fxt.context.copy(world = fxt.worldWithInvalidProgram.copy(touchedAccounts = Set(touchedPrecompile)))
    val call = fxt.ExecuteCall(op = CALL, context)

    call.world shouldEqual fxt.worldWithInvalidProgram.touchAccounts(touchedPrecompile)
  }

  "CALL when calling an empty" should "consume correct gas (refund call gas, add new account modifier) when transferring value to Empty Account" in {
    val contextEmptyAccount: PC = fxt.context.copy(world = fxt.worldWithExtEmptyAccount)
    val callEmptyAccount = fxt.ExecuteCall(op = CALL, contextEmptyAccount)

    val expectedGas = G_call + G_callvalue + G_newaccount - G_callstipend + fxt.expectedMemCost
    callEmptyAccount.stateOut.gasUsed shouldEqual expectedGas
    callEmptyAccount.world.touchedAccounts.size shouldEqual 2
  }

  it should "consume correct gas when transferring no value to Empty Account" in {
    val contextEmptyAccount: PC = fxt.context.copy(world = fxt.worldWithExtEmptyAccount)
    val callZeroTransfer = fxt.ExecuteCall(op = CALL, contextEmptyAccount, value = UInt256.Zero)

    val expectedGas = G_call + fxt.expectedMemCost
    callZeroTransfer.stateOut.gasUsed shouldEqual expectedGas
    callZeroTransfer.world.touchedAccounts.size shouldEqual 2
  }
