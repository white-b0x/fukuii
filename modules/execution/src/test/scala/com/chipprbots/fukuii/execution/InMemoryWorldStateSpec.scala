package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.Wei
import com.chipprbots.fukuii.storage.EphemDataSource
import com.chipprbots.fukuii.trie.InMemoryMptStorage
import com.chipprbots.fukuii.trie.MptNode

/** P0 coverage for the concrete state-backed [[InMemoryWorldState]] / [[InMemoryAccountStorage]] over an
  * `EphemDataSource`-backed, hash-keyed node store: account / storage / code round-trips (in-flight and after a
  * persist-and-reload from the committed state root), EIP-161 emptiness, and state-root determinism.
  *
  * `-Wnonunit-statement` gate: one `assert` per test (the final expression); every intermediate world is bound to a
  * `val`.
  */
class InMemoryWorldStateSpec extends AnyFunSuite:

  /** A shared backing (one `DataSource` + one hash-keyed node store + one code store) so a persisted world can be
    * reloaded from its committed root against the same stores.
    */
  final private class Backing:
    val dataSource: EphemDataSource = EphemDataSource()
    val nodeStore: InMemoryMptStorage = new InMemoryMptStorage
    val codeStore: CodeStorage = new CodeStorage(dataSource)

  private def worldOn(backing: Backing, root: ByteString = MptNode.EmptyRootHash): InMemoryWorldState =
    InMemoryWorldState(
      codeStorage = backing.codeStore,
      mptStorage = backing.nodeStore,
      getBlockHashByNumber = _ => None,
      accountStartNonce = UInt256.Zero,
      stateRootHash = root,
      noEmptyAccounts = true
    )

  private def addr(b: Byte): Address = Address(ByteString(Array.fill[Byte](Address.Length)(b)))

  test("empty world has the canonical empty-trie state root"):
    val world = worldOn(new Backing)
    // keccak256(RLP("")) = 56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421 —
    // the well-known empty-trie root (go-ethereum types.EmptyRootHash; MptNode.EmptyRootHash is the same L0 value).
    assert(world.stateRootHash == MptNode.EmptyRootHash)

  test("persisting an empty world keeps the empty-trie root"):
    val world = worldOn(new Backing)
    assert(world.persist.stateRootHash == MptNode.EmptyRootHash)

  test("account round-trips through persist and reload from the committed root"):
    val backing = new Backing
    val account = Account.empty().copy(nonce = UInt256(1), balance = Wei(UInt256(1000)))
    val committed = worldOn(backing).saveAccount(addr(0x11), account).persist
    val reloaded = worldOn(backing, committed.stateRootHash)
    assert(reloaded.getAccount(addr(0x11)).contains(account))

  test("storage slot round-trips through persist and reload"):
    val backing = new Backing
    val base = worldOn(backing).saveAccount(addr(0x22), Account.empty())
    val storage = base.getStorage(addr(0x22)).store(UInt256(2), BigInt(42))
    val committed = base.saveStorage(addr(0x22), storage).persist
    val reloaded = worldOn(backing, committed.stateRootHash)
    assert(reloaded.getStorage(addr(0x22)).load(UInt256(2)) == BigInt(42))

  test("storing zero deletes the slot (storage root returns to empty)"):
    val backing = new Backing
    val base = worldOn(backing).saveAccount(addr(0x33), Account.empty())
    val cleared = base.getStorage(addr(0x33)).store(UInt256(7), BigInt(5)).store(UInt256(7), BigInt(0))
    assert(cleared.storageRoot == Account.EmptyStorageRootHash)

  test("persisted account records the storage root of its committed storage trie"):
    val backing = new Backing
    val base = worldOn(backing).saveAccount(addr(0x44), Account.empty())
    val storage = base.getStorage(addr(0x44)).store(UInt256(1), BigInt(9))
    val committed = base.saveStorage(addr(0x44), storage).persist
    val reloaded = worldOn(backing, committed.stateRootHash)
    assert(reloaded.getAccount(addr(0x44)).map(_.storageRoot).contains(storage.persist.storageRoot))

  test("code round-trips through persist and reload; the code hash is recorded"):
    val backing = new Backing
    val code = ByteString(Array[Byte](0x60, 0x00, 0x60, 0x00))
    val committed = worldOn(backing).saveAccount(addr(0x55), Account.empty()).saveCode(addr(0x55), code).persist
    val reloaded = worldOn(backing, committed.stateRootHash)
    assert(
      reloaded
        .getCode(addr(0x55)) == code && reloaded.getAccount(addr(0x55)).exists(_.codeHash != Account.EmptyCodeHash)
    )

  test("EIP-161: an empty account is dead"):
    val world = worldOn(new Backing).saveAccount(addr(0x66), Account.empty())
    assert(world.isAccountDead(addr(0x66)))

  test("EIP-161: an account with balance is not dead, and emptiness ignores the storage root"):
    val funded = Account.empty().copy(balance = Wei(UInt256(1)))
    // A non-empty storage root must NOT make an otherwise-empty account non-dead (EIP-161 checks nonce/balance/code).
    val withStorageRootOnly = Account.empty().copy(storageRoot = Hash(MptNode.EmptyRootHash))
    val world = worldOn(new Backing).saveAccount(addr(0x77), funded).saveAccount(addr(0x78), withStorageRootOnly)
    assert(!world.isAccountDead(addr(0x77)) && world.isAccountDead(addr(0x78)))

  test("state root is deterministic: identical writes yield identical committed roots"):
    val account = Account.empty().copy(nonce = UInt256(5), balance = Wei(UInt256(999)))
    val rootA = worldOn(new Backing).saveAccount(addr(0x11), account).persist.stateRootHash
    val rootB = worldOn(new Backing).saveAccount(addr(0x11), account).persist.stateRootHash
    assert(rootA == rootB)

  test("getBlockHash resolves through the injected block-number hook"):
    val backing = new Backing
    val hash = Hash(ByteString(Array.fill[Byte](Hash.Length)(0xab.toByte)))
    val world = InMemoryWorldState(
      codeStorage = backing.codeStore,
      mptStorage = backing.nodeStore,
      getBlockHashByNumber = n => if n == BigInt(10) then Some(hash) else None,
      accountStartNonce = UInt256.Zero,
      stateRootHash = MptNode.EmptyRootHash,
      noEmptyAccounts = true
    )
    assert(world.getBlockHash(UInt256(10)).contains(UInt256.fromBytes(hash.bytes)))
