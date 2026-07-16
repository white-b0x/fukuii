package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.Wei

/** Smoke coverage for the reusable [[MockWorldState]] / [[MockStorage]] VM-level test fixture — the concrete in-memory
  * implementation of the abstract [[WorldState]]/[[AccountStorage]] seams. Exercises the fixture-specific state (code
  * repo, storage map, block-hash window, EIP-161 touch tracking) beyond the seam default helpers already covered by
  * `WorldStateSpec`. One `assert` per test — the `-Wnonunit-statement` gate rejects a discarded intermediate
  * `Assertion`.
  */
class MockWorldStateSpec extends AnyFunSuite:

  private val alice = Address.fromHex("0x1111111111111111111111111111111111111111")
  private val ripmd = Address(UInt256(3))

  test("saveCode / getCode round-trips, and empty code prunes the slot"):
    val code = ByteString(1, 2, 3)
    val w = MockWorldState().saveCode(alice, code)
    val pruned = w.saveCode(alice, ByteString.empty)
    assert(w.getCode(alice) == code && pruned.getCode(alice).isEmpty && !pruned.codeRepo.contains(alice))

  test("saveStorage / getStorage round-trips, and a zero store prunes the slot"):
    val s = MockStorage.Empty.store(UInt256(7), BigInt(42))
    val w = MockWorldState().saveStorage(alice, s)
    assert(w.getStorage(alice).load(UInt256(7)) == BigInt(42) && s.store(UInt256(7), BigInt(0)).isEmpty)

  test("getBlockHash returns Some within the hash window and None beyond it"):
    val w = MockWorldState(numberOfHashes = UInt256(10))
    assert(w.getBlockHash(UInt256(5)).isDefined && w.getBlockHash(UInt256(11)).isEmpty)

  test("touchAccounts is a no-op when EIP-161 (noEmptyAccounts) is off"):
    val w = MockWorldState().touchAccounts(alice)
    assert(w.touchedAccounts.isEmpty)

  test("touchAccounts records the address when EIP-161 is on"):
    val w = MockWorldState(noEmptyAccountsCond = true).touchAccounts(alice)
    assert(w.touchedAccounts == Set(alice))

  test("keepPrecompileTouched retains the RIPEMD-160 address touched by the other world (EIP-161 special case)"):
    val other = MockWorldState(noEmptyAccountsCond = true).touchAccounts(ripmd)
    val kept = MockWorldState().keepPrecompileTouched(other)
    assert(kept.touchedAccounts.contains(ripmd))

  test("seam transfer helper runs against the concrete fixture"):
    val w0 =
      MockWorldState(noEmptyAccountsCond = true).saveAccount(alice, Account.empty().copy(balance = Wei(UInt256(100))))
    val bob = Address.fromHex("0x2222222222222222222222222222222222222222")
    val w1 = w0.transfer(alice, bob, UInt256(30))
    assert(w1.getBalance(alice) == UInt256(70) && w1.getBalance(bob) == UInt256(30))
