package com.chipprbots.ethereum.vm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.Fixtures.blockchainConfig
import com.chipprbots.ethereum.vm.MockWorldState.*

// scalastyle:off object.name
class StaticCallOpcodeSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  val config: EvmConfig = EvmConfig.ByzantiumConfigBuilder(blockchainConfig)
  val startState: MockWorldState = MockWorldState(touchedAccounts = Set.empty, noEmptyAccountsCond = true)

  val fxt = new CallOpFixture(config, startState)

  def stateWithProgram(code: Assembly): MockWorldState =
    fxt.worldWithoutExtAccount.saveAccount(fxt.extAddr, fxt.accountWithCode(code.code)).saveCode(fxt.extAddr, code.code)

  {
    val programsWithStateChangingOpcodes = Map(
      CREATE -> stateWithProgram(
        Assembly(
          PUSH1,
          0,
          PUSH1,
          0,
          PUSH1,
          0,
          CREATE
        )
      ),
      SELFDESTRUCT -> stateWithProgram(Assembly(PUSH20, fxt.extAddr.bytes, SELFDESTRUCT)),
      SSTORE -> stateWithProgram(Assembly(PUSH1, 0, PUSH1, 10, SSTORE))
    )

    programsWithStateChangingOpcodes.foreach { case (op, worldState) =>
      val context: PC = fxt.context.copy(world = worldState)
      val staticcall = fxt.ExecuteCall(op = STATICCALL, context)
      val call = fxt.ExecuteCall(op = CALL, context)

      behavior of s"STATICCALL should calling a program that executes a state-changing opcodes should Opcode $op"

      it should "not modify world state" taggedAs (UnitTest, VMTest) in {
        staticcall.world shouldEqual worldState
      }

      it should "balance should be equal to initial balance" taggedAs (UnitTest, VMTest) in {
        staticcall.ownBalance shouldEqual fxt.initialBalance
        staticcall.ownBalance should be > call.ownBalance
      }
    }
  }

  {
    val programsWithLoggingOpcodes = Map(
      LOG0 -> stateWithProgram(Assembly(PUSH1, 0, PUSH1, 0, LOG0)),
      LOG1 -> stateWithProgram(Assembly(PUSH1, 0, PUSH1, 0, PUSH1, 0, LOG1)),
      LOG2 -> stateWithProgram(Assembly(PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, LOG2)),
      LOG3 -> stateWithProgram(Assembly(PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, LOG3)),
      LOG4 -> stateWithProgram(Assembly(PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, LOG4))
    )

    programsWithLoggingOpcodes.foreach { case (op, worldState) =>
      val context: PC = fxt.context.copy(world = worldState)
      val staticcall = fxt.ExecuteCall(op = STATICCALL, context)
      val call = fxt.ExecuteCall(op = CALL, context)

      behavior of s"STATICCALL should calling a program that executes a logging opcodes should Opcode $op"

      it should "should not append any logs" taggedAs (UnitTest, VMTest) in {
        call.stateOut.logs.size should be > 0
        staticcall.stateOut.logs.size shouldEqual 0
      }
    }
  }
