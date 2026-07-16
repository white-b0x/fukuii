package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.Wei

/** Coverage for the P2-filled [[WorldStateProxy]] default helpers — value transfer, EIP-161 emptiness (which does NOT
  * consult the storage root), EIP-7610 storage-aware collision, and the CREATE/CREATE2 address derivations. Uses a
  * minimal in-memory test double; the concrete state-backed implementation is L4.
  *
  * One `assert` per test — the `-Wnonunit-statement` build gate rejects a discarded intermediate `Assertion`.
  */
class WorldStateProxySpec extends AnyFunSuite:

  final private case class TestStorage(data: Map[UInt256, BigInt] = Map.empty) extends Storage[TestStorage]:
    def store(offset: UInt256, value: BigInt): TestStorage = copy(data = data.updated(offset, value))
    def load(offset: UInt256): BigInt = data.getOrElse(offset, BigInt(0))

  final private case class TestWorld(
      accounts: Map[Address, Account] = Map.empty,
      codes: Map[Address, ByteString] = Map.empty,
      storages: Map[Address, TestStorage] = Map.empty,
      touched: Set[Address] = Set.empty,
      eip161: Boolean = true
  ) extends WorldStateProxy[TestWorld, TestStorage]:
    def getAccount(address: Address): Option[Account] = accounts.get(address)
    def saveAccount(address: Address, account: Account): TestWorld = copy(accounts = accounts.updated(address, account))
    protected def deleteAccount(address: Address): TestWorld = copy(accounts = accounts - address)
    def getEmptyAccount: Account = Account.empty()
    def touchAccounts(addresses: Address*): TestWorld = copy(touched = touched ++ addresses)
    protected def clearTouchedAccounts: TestWorld = copy(touched = Set.empty)
    protected def noEmptyAccounts: Boolean = eip161
    def keepPrecompileTouched(world: TestWorld): TestWorld = this
    def getCode(address: Address): ByteString = codes.getOrElse(address, ByteString.empty)
    def getStorage(address: Address): TestStorage = storages.getOrElse(address, TestStorage())
    def getBlockHash(number: UInt256): Option[UInt256] = None
    def saveCode(address: Address, code: ByteString): TestWorld = copy(codes = codes.updated(address, code))
    def saveStorage(address: Address, storage: TestStorage): TestWorld =
      copy(storages = storages.updated(address, storage))

  private val alice = Address.fromHex("0x1111111111111111111111111111111111111111")
  private val bob = Address.fromHex("0x2222222222222222222222222222222222222222")

  test("transfer moves balance and touches both accounts"):
    val w0 = TestWorld().saveAccount(alice, Account.empty().copy(balance = Wei(UInt256(100))))
    val w1 = w0.transfer(alice, bob, UInt256(30))
    assert(
      w1.getBalance(alice) == UInt256(70) && w1.getBalance(bob) == UInt256(30) &&
        w1.touched.contains(alice) && w1.touched.contains(bob)
    )

  test("zero-value transfer to a non-existent account under EIP-161 only touches the sender"):
    val w0 = TestWorld().saveAccount(alice, Account.empty().copy(balance = Wei(UInt256(100))))
    val w1 = w0.transfer(alice, bob, UInt256.Zero)
    assert(w1.getAccount(bob).isEmpty && w1.touched == Set(alice))

  test("isAccountDead: empty account is dead, funded account is alive"):
    val empty = TestWorld().saveAccount(alice, Account.empty())
    val funded = TestWorld().saveAccount(alice, Account.empty().copy(balance = Wei(UInt256(1))))
    assert(empty.isAccountDead(alice) && empty.isAccountDead(bob) && !funded.isAccountDead(alice))

  test("EIP-161 emptiness does NOT consult the storage root"):
    // zero nonce, zero balance, empty code, but a NON-empty storage root → still empty/dead per EIP-161,
    // yet EIP-7610's storage-aware check does flag it.
    val acct = Account.empty().copy(storageRoot = Hash(kec256(Array[Byte](1, 2, 3))))
    val w = TestWorld().saveAccount(alice, acct)
    assert(
      w.isAccountDead(alice) && w.nonEmptyCodeOrNonceOrStorageAccount(alice) && !w.nonEmptyCodeOrNonceAccount(alice)
    )

  test("increaseNonce bumps the guaranteed account by one"):
    val w1 = TestWorld().saveAccount(alice, Account.empty()).increaseNonce(alice)
    assert(w1.getAccount(alice).map(_.nonce).contains(UInt256.One))

  test("create2Address matches the EIP-1014 example-0 vector"):
    // EIP-1014: address 0x00..00, salt 0x00..00, init_code 0x00 → 0x4D1A2e2bB4F88F0250f26Ffff098B0b30B26Bf38
    val got = TestWorld().create2Address(Address.Zero, UInt256.Zero, ByteString(0x00.toByte))
    assert(got == Address.fromHex("0x4D1A2e2bB4F88F0250f26Ffff098B0b30B26Bf38"))

  test("createAddress is deterministic, 20 bytes, and nonce-sensitive"):
    // nonce is decremented inside createAddress (creator nonce is pre-incremented before the sub-execution)
    val w1 = TestWorld().saveAccount(alice, Account.empty().copy(nonce = UInt256.One))
    val w2 = TestWorld().saveAccount(alice, Account.empty().copy(nonce = UInt256(2)))
    val a1 = w1.createAddress(alice)
    assert(a1.bytes.length == 20 && a1 == w1.createAddress(alice) && a1 != w2.createAddress(alice))
