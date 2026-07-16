package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.domain.Account

/** In-memory, immutable [[AccountStorage]] for VM-level tests — the slot map keyed by the 256-bit EVM word.
  *
  * Transcribed from the AS-IS `july-fourth:…/vm/MockStorage.scala` and retyped to the built [[AccountStorage]] seam:
  * the seam offsets on [[UInt256]] (the EVM-native slot word) rather than the L1 `StorageKey` value class the L1
  * rebuild did not carry. Storing a zero value prunes the slot (the AS-IS emptiness contract), so [[isEmpty]] tracks
  * whether the account has any non-zero storage.
  */
object MockStorage:
  val Empty: MockStorage = MockStorage()

  /** Build storage from a dense slot sequence — word `i` lands at slot `i`. */
  def fromSeq(words: Seq[UInt256]): MockStorage =
    MockStorage(words.iterator.zipWithIndex.map((w, i) => UInt256(i.toLong) -> w.toBigInt).toMap)

final case class MockStorage(data: Map[UInt256, BigInt] = Map.empty) extends AccountStorage[MockStorage]:
  def store(offset: UInt256, value: BigInt): MockStorage =
    if value.signum == 0 then copy(data = data - offset)
    else copy(data = data + (offset -> value))

  def load(offset: UInt256): BigInt =
    data.getOrElse(offset, BigInt(0))

  def isEmpty: Boolean =
    data.isEmpty

/** Concrete in-memory [[WorldState]] for VM-level tests — lets the interpreter run against a real (non-persisted) world
  * without the L4 ledger. The production state-backed world state (bound to the L2 trie) is an L4 (`execution`)
  * concern; this fixture stands in for it at L3 so opcode/gas/precompile tests exercise the abstract seam directly.
  *
  * Transcribed from the AS-IS `july-fourth:…/vm/MockWorldState.scala` and retyped from the AS-IS `WorldStateProxy` to
  * the built [[WorldState]] seam. Immutable by construction (each mutation returns a fresh copy), matching the seam's
  * roll-back-by-discard contract; kept a per-test value (no shared mutable object state).
  */
object MockWorldState:
  type TestVM = VM[MockWorldState, MockStorage]
  type PS = MessageFrame[MockWorldState, MockStorage]
  type PC = CallContext[MockWorldState, MockStorage]
  type PR = ExecutionResult[MockWorldState, MockStorage]

final case class MockWorldState(
    accounts: Map[Address, Account] = Map.empty,
    codeRepo: Map[Address, ByteString] = Map.empty,
    storages: Map[Address, MockStorage] = Map.empty,
    numberOfHashes: UInt256 = UInt256.Zero,
    touchedAccounts: Set[Address] = Set.empty,
    noEmptyAccountsCond: Boolean = false
) extends WorldState[MockWorldState, MockStorage]:

  def getAccount(address: Address): Option[Account] =
    accounts.get(address)

  def saveAccount(address: Address, account: Account): MockWorldState =
    copy(accounts = accounts + (address -> account))

  protected def deleteAccount(address: Address): MockWorldState =
    copy(accounts = accounts - address, codeRepo = codeRepo - address, storages = storages - address)

  def getEmptyAccount: Account = Account.empty()

  def touchAccounts(addresses: Address*): MockWorldState =
    if noEmptyAccounts then copy(touchedAccounts = touchedAccounts ++ addresses.toSet)
    else this

  protected def clearTouchedAccounts: MockWorldState =
    copy(touchedAccounts = Set.empty)

  protected def noEmptyAccounts: Boolean = noEmptyAccountsCond

  def keepPrecompileTouched(world: MockWorldState): MockWorldState =
    if world.touchedAccounts.contains(ripmdContractAddress) then
      copy(touchedAccounts = touchedAccounts + ripmdContractAddress)
    else this

  def getCode(address: Address): ByteString =
    codeRepo.getOrElse(address, ByteString.empty)

  def getStorage(address: Address): MockStorage =
    storages.getOrElse(address, MockStorage.Empty)

  def getBlockHash(number: UInt256): Option[UInt256] =
    if numberOfHashes.toBigInt >= number.toBigInt then
      Some(UInt256.fromBytes(kec256(number.toBigInt.toString.getBytes)))
    else None

  def saveCode(address: Address, code: ByteString): MockWorldState =
    if code.isEmpty then copy(codeRepo = codeRepo - address)
    else copy(codeRepo = codeRepo + (address -> code))

  def saveStorage(address: Address, storage: MockStorage): MockWorldState =
    if storage.isEmpty then copy(storages = storages - address)
    else copy(storages = storages + (address -> storage))
