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

/** P4 interpreter-loop + re-entry coverage: the `@tailrec` exec loop (PUSH/ADD/RETURN, mid-loop out-of-gas), the
  * byte-consensus `call` (value transfer, depth-1024 guard, static write-protection, EIP-7702 delegated exec) and
  * `create` (CREATE/CREATE2 address reuse of the [[WorldState]] helpers, incl. the EIP-1014 example-0 vector), and the
  * branch-free one-slot [[NoTracing]] tracer. Reuses the in-memory world double from `WorldStateSpec`/`OpCodeSpec`.
  *
  * One `assert` per test — the `-Wnonunit-statement` build gate rejects a discarded intermediate `Assertion`.
  */
class EvmInterpreterSpec extends AnyFunSuite:

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
  private val callee = Address.fromHex("0x2222222222222222222222222222222222222222")
  private val delegate = Address.fromHex("0x3333333333333333333333333333333333333333")

  private def bytes(xs: Int*): ByteString = ByteString(xs.map(_.toByte).toArray)

  /** `PUSH1 3 · PUSH1 5 · ADD · PUSH1 0 · MSTORE · PUSH1 32 · PUSH1 0 · RETURN` → returns the 32-byte word `8`. */
  private val addReturn: ByteString = bytes(0x60, 3, 0x60, 5, 0x01, 0x60, 0, 0x52, 0x60, 0x20, 0x60, 0, 0xf3)

  /** `PUSH1 42 · PUSH1 0 · MSTORE · PUSH1 32 · PUSH1 0 · RETURN` → returns the 32-byte word `42`. */
  private val return42: ByteString = bytes(0x60, 42, 0x60, 0, 0x52, 0x60, 0x20, 0x60, 0, 0xf3)

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
      value: UInt256 = UInt256.Zero,
      input: ByteString = ByteString.empty,
      gas: BigInt = 1_000_000,
      callDepth: Int = 0,
      staticCtx: Boolean = false,
      config: EvmConfig = EvmConfig.EthCancun
  ): ProgramContext[TestWorld, TestStorage] =
    ProgramContext[TestWorld, TestStorage](
      callerAddr = alice,
      originAddr = alice,
      recipientAddr = recipient,
      gasPrice = UInt256.Zero,
      startGas = gas,
      inputData = input,
      value = value,
      endowment = value,
      doTransfer = true,
      blockHeader = header,
      callDepth = callDepth,
      world = world,
      initialAddressesToDelete = Set.empty,
      evmConfig = config,
      chainId = ChainId(1),
      staticCtx = staticCtx,
      originalWorld = world,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )

  /** A tracer that counts `onStep` firings — proves the one-slot hook fires per opcode. */
  final private class CountingTracer extends ExecutionTracer:
    var steps: Int = 0
    override def onStep[W <: WorldState[W, S], S <: AccountStorage[S]](
        opCode: OpCode,
        before: ProgramState[W, S],
        after: ProgramState[W, S]
    ): Unit = steps += 1

  private def funded(addr: Address, wei: BigInt, nonce: BigInt = 0): (Address, Account) =
    addr -> Account.empty().copy(balance = Wei(UInt256(wei)), nonce = UInt256(nonce))

  // -- 1. the @tailrec exec loop -----------------------------------------------------------------------------------

  test("exec loop runs PUSH/ADD/MSTORE/RETURN and returns the computed word"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> addReturn))
    val result = EvmInterpreter[TestWorld, TestStorage]().run(ctx(Some(callee), world))
    assert(result.error.isEmpty && result.returnData == UInt256(8).bytes)

  test("out-of-gas mid-loop halts with OutOfGas and consumes all gas"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> addReturn))
    // PUSH1 costs G_verylow (3); a 2-gas budget cannot afford the first opcode.
    val result = EvmInterpreter[TestWorld, TestStorage]().run(ctx(Some(callee), world, gas = 2))
    assert(result.error.contains(OutOfGas) && result.gasRemaining == BigInt(0))

  // -- 2. call (Θ) — value transfer, depth guard, static write-protection ------------------------------------------

  test("call transfers value to the recipient and returns the callee's output"):
    val world = TestWorld(accounts = Map(funded(alice, 100)), codes = Map(callee -> addReturn))
    val result = EvmInterpreter[TestWorld, TestStorage]().run(ctx(Some(callee), world, value = UInt256(30)))
    assert(
      result.error.isEmpty && result.world.getBalance(callee) == UInt256(30) && result.returnData == UInt256(8).bytes
    )

  test("call beyond MaxCallDepth (1024) is rejected as an invalid call"):
    val world = TestWorld(accounts = Map(funded(alice, 100)), codes = Map(callee -> addReturn))
    val result = EvmInterpreter[TestWorld, TestStorage]().run(ctx(Some(callee), world, callDepth = 1025))
    assert(result.error.contains(InvalidCall))

  test("SSTORE in a static context is rejected (write protection)"):
    // PUSH1 1 · PUSH1 0 · SSTORE
    val sstore = bytes(0x60, 1, 0x60, 0, 0x55)
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> sstore))
    val result = EvmInterpreter[TestWorld, TestStorage]().run(ctx(Some(callee), world, staticCtx = true))
    assert(result.error.contains(OpCodeNotAvailableInStaticContext(0x55.toByte)))

  // -- 3. create (Λ) — CREATE / CREATE2 address reuse of the WorldState helpers ------------------------------------

  test("CREATE derives its address via WorldState.createAddress and deploys the runtime code"):
    val world = TestWorld(accounts = Map(funded(alice, 0, nonce = 1)))
    val (result, addr) = EvmInterpreter[TestWorld, TestStorage]().create(ctx(None, world, input = ByteString.empty))
    assert(result.error.isEmpty && addr == world.createAddress(alice))

  test("CREATE2 matches the EIP-1014 example-0 vector (address 0, salt 0, initcode 0x00)"):
    val zeroFunded = TestWorld(accounts = Map(Address.Zero -> Account.empty()))
    val context = ProgramContext[TestWorld, TestStorage](
      callerAddr = Address.Zero,
      originAddr = Address.Zero,
      recipientAddr = None,
      gasPrice = UInt256.Zero,
      startGas = 1_000_000,
      inputData = ByteString(0x00.toByte),
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = true,
      blockHeader = header,
      callDepth = 0,
      world = zeroFunded,
      initialAddressesToDelete = Set.empty,
      evmConfig = EvmConfig.EthCancun,
      chainId = ChainId(1),
      originalWorld = zeroFunded,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )
    val (result, addr) = EvmInterpreter[TestWorld, TestStorage]().create(context, Some(UInt256.Zero))
    assert(result.error.isEmpty && addr == Address.fromHex("0x4D1A2e2bB4F88F0250f26Ffff098B0b30B26Bf38"))

  // -- 4. EIP-7702 delegated-code execution ------------------------------------------------------------------------

  test("call to a 0xef0100-delegated account runs the delegate's code and warms the target"):
    val designator = ByteString(Eip7702.DelegationPrefix) ++ delegate.bytes
    val world = TestWorld(
      accounts = Map(funded(alice, 0)),
      codes = Map(callee -> designator, delegate -> return42)
    )
    val result = EvmInterpreter[TestWorld, TestStorage]().run(ctx(Some(callee), world))
    assert(result.returnData == UInt256(42).bytes && result.accessedAddresses.contains(delegate))

  // -- 5. branch-free NoTracing (one slot) -------------------------------------------------------------------------

  test("a counting tracer observes one onStep per executed opcode (PUSH·PUSH·ADD·STOP = 4)"):
    // PUSH1 3 · PUSH1 5 · ADD · STOP
    val program = bytes(0x60, 3, 0x60, 5, 0x01, 0x00)
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> program))
    val tracer = CountingTracer()
    val result = EvmInterpreter[TestWorld, TestStorage](tracer).run(ctx(Some(callee), world))
    assert(result.error.isEmpty && tracer.steps == 4)

  test("NoTracing is the branch-free default and elides its hooks without effect"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> addReturn))
    val traced = EvmInterpreter[TestWorld, TestStorage](CountingTracer()).run(ctx(Some(callee), world))
    val untraced = EvmInterpreter[TestWorld, TestStorage](NoTracing).run(ctx(Some(callee), world))
    assert(traced.returnData == untraced.returnData && untraced.error.isEmpty)
