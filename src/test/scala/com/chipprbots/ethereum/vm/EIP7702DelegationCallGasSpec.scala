package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.MockWorldState.*

import Fixtures.blockchainConfig

/** EIP-7702 (F-6): a CALL whose target is a `0xef0100`-delegated account charges for resolving the delegation target —
  * cold `G_cold_account_access` (2600) when the target is not yet in the access list, warm `G_warm_storage_read` (100)
  * when it is. The warm branch previously charged 0 (undercharge), matching go-ethereum
  * `core/vm/operations_acl.go:337-343` (`WarmStorageReadCostEIP2929`).
  *
  * These are pure upfront-gas assertions on the CALL opcode: gas forwarded to the callee is pinned to 0 and value
  * transfer is disabled, so the only variable between runs is the delegation-resolution cost.
  */
class EIP7702DelegationCallGasSpec extends AnyFlatSpec with Matchers:

  private val config: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)

  private val startState: MockWorldState = MockWorldState(touchedAccounts = Set.empty, noEmptyAccountsCond = true)
  private val fxt = new CallOpFixture(config, startState)

  private val targetAddr: Address = Address(0x7a4e70)
  private val delegatedAddr: Address = fxt.extAddr

  private val delegationCode: ByteString = SetCodeTransaction.addressToDelegation(targetAddr)

  // Delegated account: code is the 23-byte delegation designator pointing at targetAddr.
  private val delegatedWorld: MockWorldState = fxt.worldWithoutExtAccount
    .saveAccount(delegatedAddr, Account.empty())
    .saveCode(delegatedAddr, delegationCode)
    .saveAccount(targetAddr, Account.empty())

  // Baseline account: same address, but ordinary (non-delegation) empty code — no delegation cost.
  private val plainWorld: MockWorldState = fxt.worldWithoutExtAccount
    .saveAccount(delegatedAddr, Account.empty())

  private def ctx(world: MockWorldState, warm: Set[Address]): PC =
    fxt.context.copy(
      startGas = com.chipprbots.ethereum.domain.GasAmount(1000000),
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = false,
      world = world,
      originalWorld = world,
      warmAddresses = warm
    )

  // gas=0 forwarded, value=0, zero-length in/out windows -> the only per-run difference is delegation cost.
  private def gasUsed(context: PC): BigInt =
    fxt
      .ExecuteCall(
        op = CALL,
        context = context,
        inputData = ByteString.empty,
        gas = 0,
        to = delegatedAddr,
        value = UInt256.Zero,
        inOffset = UInt256.Zero,
        inSize = UInt256.Zero,
        outOffset = UInt256.Zero,
        outSize = UInt256.Zero
      )
      .stateOut
      .gasUsed
      .value

  // delegatedAddr is warm in every scenario; only targetAddr warmth (or delegation presence) varies.
  private val coldGas: BigInt = gasUsed(ctx(delegatedWorld, Set(delegatedAddr)))
  private val warmGas: BigInt = gasUsed(ctx(delegatedWorld, Set(delegatedAddr, targetAddr)))
  private val plainGas: BigInt = gasUsed(ctx(plainWorld, Set(delegatedAddr)))

  "EIP-7702 CALL delegation resolution" should "charge cold access (2600) for an unaccessed delegation target" taggedAs (
    OlympiaTest,
    VMTest
  ) in {
    // cold delegation adds 2600 over the non-delegated baseline
    (coldGas - plainGas) shouldBe BigInt(2600)
  }

  it should "charge warm access (100), not 0, for an already-accessed delegation target" taggedAs (
    OlympiaTest,
    VMTest
  ) in {
    // warm delegation adds exactly 100 over the non-delegated baseline (regression: was 0)
    (warmGas - plainGas) shouldBe BigInt(100)
  }

  it should "save exactly 2500 gas on the warm path versus the cold path" taggedAs (OlympiaTest, VMTest) in {
    // 2600 (cold) - 100 (warm) = 2500; before the fix this was 2600 - 0 = 2600
    (coldGas - warmGas) shouldBe BigInt(2500)
  }
