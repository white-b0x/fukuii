package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.domain.Wei

/** EIP-6780 SELFDESTRUCT-restriction coverage, driven through the real [[EvmInterpreter]] (P7's in-memory world
  * double).
  *
  * The consensus point: post-EIP-6780 (ETH Cancun+, ETC Olympia) SELFDESTRUCT only deletes a contract **created in the
  * current transaction**; a pre-existing contract merely transfers its balance. "Created in this transaction" is
  * membership in the per-tx created-address set (populated at create-frame start, before the initcode runs) — the
  * oracle is go-ethereum `StateDB.IsNewContract` / besu `frame.wasCreatedInTransaction`, NOT
  * `originalWorld.accountExists` (which is `initialiseAccount`'d on the CREATE path and would spare a constructor-frame
  * SELFDESTRUCT and a CREATE2-at-a-pre-funded-address).
  */
class Eip6780SelfDestructSpec extends AnyFunSuite:

  final private case class TestStorage(data: Map[UInt256, BigInt] = Map.empty) extends AccountStorage[TestStorage]:
    def store(offset: UInt256, value: BigInt): TestStorage = copy(data = data.updated(offset, value))
    def load(offset: UInt256): BigInt = data.getOrElse(offset, BigInt(0))

  final private case class TestWorld(
      accounts: Map[Address, Account] = Map.empty,
      codes: Map[Address, ByteString] = Map.empty,
      storages: Map[Address, TestStorage] = Map.empty,
      touched: Set[Address] = Set.empty
  ) extends WorldState[TestWorld, TestStorage]:
    def getAccount(address: Address): Option[Account] = accounts.get(address)
    def saveAccount(address: Address, account: Account): TestWorld = copy(accounts = accounts.updated(address, account))
    protected def deleteAccount(address: Address): TestWorld = copy(accounts = accounts - address)
    def getEmptyAccount: Account = Account.empty()
    def touchAccounts(addresses: Address*): TestWorld = copy(touched = touched ++ addresses)
    protected def clearTouchedAccounts: TestWorld = copy(touched = Set.empty)
    protected def noEmptyAccounts: Boolean = true
    def keepPrecompileTouched(world: TestWorld): TestWorld = this
    def getCode(address: Address): ByteString = codes.getOrElse(address, ByteString.empty)
    def getStorage(address: Address): TestStorage = storages.getOrElse(address, TestStorage())
    def getBlockHash(number: UInt256): Option[UInt256] = None
    def saveCode(address: Address, code: ByteString): TestWorld = copy(codes = codes.updated(address, code))
    def saveStorage(address: Address, storage: TestStorage): TestWorld =
      copy(storages = storages.updated(address, storage))

  private val alice = Address.fromHex("0x1111111111111111111111111111111111111111")
  private val victim = Address.fromHex("0x2222222222222222222222222222222222222222")
  private val beneficiary = Address.fromHex("0x3333333333333333333333333333333333333333")

  private def code(xs: Int*): ByteString = ByteString(xs.map(_.toByte).toArray)

  /** `PUSH20 <beneficiary> · SELFDESTRUCT` — a body/constructor that immediately self-destructs to `beneficiary`. */
  private val selfDestructTo: ByteString =
    ByteString(0x73.toByte) ++ beneficiary.bytes ++ ByteString(0xff.toByte)

  private def header: com.chipprbots.fukuii.domain.BlockHeader =
    com.chipprbots.fukuii.domain.BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = Address.Zero,
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 17,
      number = 100,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = 1000,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty,
      baseFeePerGas = None
    )

  private def ctx(
      recipient: Option[Address],
      world: TestWorld,
      input: ByteString = ByteString.empty,
      config: EvmConfig = EvmConfig.EthCancun
  ): ProgramContext[TestWorld, TestStorage] =
    ProgramContext[TestWorld, TestStorage](
      callerAddr = alice,
      originAddr = alice,
      recipientAddr = recipient,
      gasPrice = UInt256.Zero,
      startGas = 1_000_000,
      inputData = input,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = true,
      blockHeader = header,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set.empty,
      evmConfig = config,
      chainId = ChainId(1),
      staticCtx = false,
      originalWorld = world,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )

  private def funded(addr: Address, wei: BigInt): (Address, Account) =
    addr -> Account.empty().copy(balance = Wei(UInt256(wei)))

  private val preSpiralNoEip6780: EvmConfig = EvmConfig.deriveEvmConfigAt(EvmProposals.berlinSet)

  // -- the canonical EIP-6780 case: SELFDESTRUCT in a contract's own constructor still destroys it -------------------

  test("EIP-6780: a contract that SELFDESTRUCTs in its own constructor is destroyed (created in this tx)"):
    val world = TestWorld(accounts = Map(funded(alice, 0)))
    val (result, newAddress) =
      EvmInterpreter[TestWorld, TestStorage]().create(ctx(None, world, input = selfDestructTo))
    assert(result.error.isEmpty && result.addressesToDelete.contains(newAddress))

  test("EIP-6780: CREATE2 at a pre-funded address, self-destructing in-constructor, is still destroyed"):
    val salt = UInt256(0xcafe)
    val target = TestWorld().create2Address(alice, salt, selfDestructTo)
    val world = TestWorld(accounts = Map(funded(alice, 0), funded(target, 100)))
    val (result, newAddress) =
      EvmInterpreter[TestWorld, TestStorage]().create(ctx(None, world, input = selfDestructTo), Some(salt))
    assert(result.error.isEmpty && newAddress == target && result.addressesToDelete.contains(target))

  // -- the other EIP-6780 arm: a pre-existing contract is NOT destroyed (balance transfer only) ---------------------

  test("EIP-6780: SELFDESTRUCT of a pre-existing (not same-tx) contract does not delete it"):
    val world = TestWorld(
      accounts = Map(funded(alice, 0), funded(victim, 100)),
      codes = Map(victim -> selfDestructTo)
    )
    val result = EvmInterpreter[TestWorld, TestStorage]().call(ctx(Some(victim), world), victim)
    assert(result.error.isEmpty && !result.addressesToDelete.contains(victim))

  // -- pre-EIP-6780 behaviour is preserved: a pre-existing contract IS destroyed ------------------------------------

  test("pre-EIP-6780: SELFDESTRUCT of a pre-existing contract deletes it"):
    val world = TestWorld(
      accounts = Map(funded(alice, 0), funded(victim, 100)),
      codes = Map(victim -> selfDestructTo)
    )
    val result =
      EvmInterpreter[TestWorld, TestStorage]().call(ctx(Some(victim), world, config = preSpiralNoEip6780), victim)
    assert(result.error.isEmpty && result.addressesToDelete.contains(victim))

  // -- created-set revert-safety: a reverted CREATE must NOT leave its address in the tx-global created set ----------
  // Reference semantics: go-ethereum reverts the `newContract` flag on RevertToSnapshot (core/state/journal.go
  // createContractChange.revert); besu undoes the creates UndoSet on rollback(). Created-then-reverted ⇒ NOT
  // created-in-tx. The CreateOp/CallOp error arms drop the child's `createdAddresses`; this test fails loudly if a
  // future edit makes an error arm merge them.

  test("created-set: a sub-frame CREATE that reverts leaves no address in the tx-global created set"):
    // Child init-code (reverts immediately): PUSH1 0 · PUSH1 0 · REVERT.
    val childInit = code(0x60, 0x00, 0x60, 0x00, 0xfd)
    // 32-byte word = child init left-aligned + zero pad; MSTORE at mem[0], then CREATE(endowment 0, off 0, size 5).
    val word = childInit ++ ByteString(new Array[Byte](32 - childInit.size))
    val parentCode =
      code(0x7f) ++ word ++ // PUSH32 <word>
        code(0x60, 0x00, 0x52) ++ // PUSH1 0 · MSTORE
        code(0x60, 0x05, 0x60, 0x00, 0x60, 0x00, 0xf0, 0x00) // PUSH1 5 · PUSH1 0 · PUSH1 0 · CREATE · STOP
    val parent = Address.fromHex("0x4444444444444444444444444444444444444444")
    val world = TestWorld(accounts = Map(funded(alice, 0), funded(parent, 0)), codes = Map(parent -> parentCode))
    val result = EvmInterpreter[TestWorld, TestStorage]().call(ctx(Some(parent), world), parent)
    // The parent completes (a failed CREATE just pushes 0), and the reverted child's address is absent from the
    // tx-global created set — any error-arm merge would leak it here and make the set non-empty.
    assert(result.error.isEmpty && result.createdAddresses.isEmpty)
