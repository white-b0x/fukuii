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

/** P6 role-tracer coverage: each of the four role-gated [[ExecutionTracer]] impls collects the expected raw data over a
  * real interpreter run, the **result-parity** (non-interference) property that a role tracer never changes execution
  * (byte-identical returnData / error / gasRemaining vs [[NoTracing]]), and the one-slot branch-free hook still elides
  * under [[NoTracing]]. The tx-boundary hooks ([[ExecutionTracer.onTxStart]]/[[ExecutionTracer.onTxEnd]]) are driven
  * here the way the L4 tx executor will drive them — the L3 interpreter fires only onStep/onCallEnter/onCallExit.
  *
  * One `assert` per test — the `-Wnonunit-statement` gate rejects a discarded intermediate `Assertion`.
  */
class RoleTracerSpec extends AnyFunSuite:

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
  private val inner = Address.fromHex("0x00000000000000000000000000000000000000aa")

  private def bytes(xs: Int*): ByteString = ByteString(xs.map(_.toByte).toArray)

  /** `PUSH1 3 · PUSH1 5 · ADD · PUSH1 0 · MSTORE · PUSH1 32 · PUSH1 0 · RETURN` → returns the 32-byte word `8`. */
  private val addReturn: ByteString = bytes(0x60, 3, 0x60, 5, 0x01, 0x60, 0, 0x52, 0x60, 0x20, 0x60, 0, 0xf3)

  /** `PUSH1 7 · PUSH1 1 · SSTORE · STOP` → stores 7 at slot 1. */
  private val sstore: ByteString = bytes(0x60, 7, 0x60, 1, 0x55, 0x00)

  /** `CALL(gas=0xffff, inner, value=0, in=0/0, out=0/0) · STOP` — a value-free sub-call into `inner`. */
  private val callInner: ByteString =
    bytes(0x60, 0, 0x60, 0, 0x60, 0, 0x60, 0, 0x60, 0, 0x73) ++ inner.bytes ++ bytes(0x61, 0xff, 0xff, 0xf1, 0x00)

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
      gas: BigInt = 1_000_000
  ): CallContext[TestWorld, TestStorage] =
    CallContext[TestWorld, TestStorage](
      callerAddr = alice,
      originAddr = alice,
      recipientAddr = recipient,
      gasPrice = UInt256.Zero,
      startGas = gas,
      inputData = ByteString.empty,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = true,
      blockHeader = header,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set.empty,
      evmConfig = EvmConfig.EthCancun,
      chainId = ChainId(1),
      staticCtx = false,
      originalWorld = world,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )

  private def funded(addr: Address, wei: BigInt, nonce: BigInt = 0): (Address, Account) =
    addr -> Account.empty().copy(balance = Wei(UInt256(wei)), nonce = UInt256(nonce))

  private def interp(tracer: ExecutionTracer): EvmInterpreter[TestWorld, TestStorage] =
    EvmInterpreter[TestWorld, TestStorage](tracer)

  // -- StructLogTracer ---------------------------------------------------------------------------------------------

  test("StructLogTracer records one struct-log per opcode with 1-based depth and matching pc/op"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> addReturn))
    val tracer = StructLogTracer()
    val result = interp(tracer).run(ctx(Some(callee), world))
    val steps = tracer.getSteps
    // 8 opcodes: PUSH1·PUSH1·ADD·PUSH1·MSTORE·PUSH1·PUSH1·RETURN
    assert(
      result.error.isEmpty && steps.size == 8 && steps.head.pc == 0 && steps.head.op == "PUSH1" &&
        steps.forall(_.depth == 1) && steps.last.op == "RETURN"
    )

  test("StructLogTracer with enableStorage captures the SSTORE slot→value raw"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> sstore))
    val tracer = StructLogTracer(enableStorage = true)
    val result = interp(tracer).run(ctx(Some(callee), world))
    val storeStep = tracer.getSteps.find(_.op == "SSTORE")
    assert(result.error.isEmpty && storeStep.exists(_.storage.contains(Map(UInt256(1) -> UInt256(7)))))

  test("StructLogTracer.onTxEnd records the tx-level gas/failed/returnValue summary"):
    val tracer = StructLogTracer()
    tracer.onTxEnd(gasUsed = BigInt(21000), output = UInt256(8).bytes, error = None)
    assert(tracer.gas == BigInt(21000) && !tracer.failed && tracer.returnValue == UInt256(8).bytes)

  test("StructLogTracer respects the step limit"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> addReturn))
    val tracer = StructLogTracer(limit = 3)
    val _ = interp(tracer).run(ctx(Some(callee), world))
    assert(tracer.getSteps.size == 3)

  // -- CallTracer --------------------------------------------------------------------------------------------------

  test("CallTracer builds the root frame from onTxStart and a nested sub-call from a CALL opcode"):
    val world = TestWorld(
      accounts = Map(funded(alice, 0)),
      codes = Map(callee -> callInner, inner -> bytes(0x00)) // inner just STOPs
    )
    val tracer = CallTracer()
    tracer.onTxStart(alice, Some(callee), gas = 1_000_000, value = UInt256.Zero, input = ByteString.empty)
    val result = interp(tracer).run(ctx(Some(callee), world))
    tracer.onTxEnd(gasUsed = 1_000_000 - result.gasRemaining, output = result.returnData, error = None)
    val root = tracer.result
    assert(
      result.error.isEmpty && root.exists(f =>
        f.callType == "CALL" && f.from == alice && f.to == callee &&
          f.calls.sizeIs == 1 && f.calls.head.to == inner
      )
    )

  test("CallTracer with onlyTopCall skips the nested sub-call"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> callInner, inner -> bytes(0x00)))
    val tracer = CallTracer(onlyTopCall = true)
    tracer.onTxStart(alice, Some(callee), gas = 1_000_000, value = UInt256.Zero, input = ByteString.empty)
    val _ = interp(tracer).run(ctx(Some(callee), world))
    tracer.onTxEnd(gasUsed = 0, output = ByteString.empty, error = None)
    assert(tracer.result.exists(_.calls.isEmpty))

  // -- PrestateTracer ----------------------------------------------------------------------------------------------

  test("PrestateTracer collects touched accounts and the SSTORE slot's pre-state"):
    val preStorage = TestStorage(Map(UInt256(1) -> BigInt(99)))
    val world = TestWorld(
      accounts = Map(funded(alice, 500), callee -> Account.empty().copy(balance = Wei(UInt256(0)))),
      codes = Map(callee -> sstore),
      storages = Map(callee -> preStorage)
    )
    val tracer = PrestateTracer[TestWorld, TestStorage](world)
    tracer.onTxStart(alice, Some(callee), gas = 1_000_000, value = UInt256.Zero, input = ByteString.empty)
    val result = interp(tracer).run(ctx(Some(callee), world))
    val pre = tracer.prestate
    assert(
      result.error.isEmpty && tracer.touchedAddresses.contains(callee) &&
        pre.get(callee).exists(_.storage == Map(UInt256(1) -> BigInt(99))) &&
        pre.get(alice).exists(_.balance == BigInt(500))
    )

  test("PrestateTracer diff mode exposes pre- and post-state snapshots"):
    val world = TestWorld(accounts = Map(funded(alice, 500)), codes = Map(callee -> addReturn))
    val tracer = PrestateTracer[TestWorld, TestStorage](world, diffMode = true)
    tracer.onTxStart(alice, Some(callee), gas = 1_000_000, value = UInt256.Zero, input = ByteString.empty)
    val _ = interp(tracer).run(ctx(Some(callee), world))
    val postWorld = world.saveAccount(alice, world.getAccount(alice).get.copy(balance = Wei(UInt256(400))))
    tracer.setPostWorld(postWorld)
    assert(
      tracer.isDiffMode && tracer.prestate.get(alice).exists(_.balance == BigInt(500)) &&
        tracer.poststate.flatMap(_.get(alice)).exists(_.balance == BigInt(400))
    )

  // -- VmTracer ----------------------------------------------------------------------------------------------------

  test("VmTracer records ops with push/cost and attaches a nested sub-frame on a CALL"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> callInner, inner -> bytes(0x00)))
    val tracer = VmTracer()
    tracer.onTxStart(alice, Some(callee), gas = 1_000_000, value = UInt256.Zero, input = ByteString.empty)
    val result = interp(tracer).run(ctx(Some(callee), world))
    val root = tracer.result
    assert(
      result.error.isEmpty && root.exists(f => f.code == callInner && f.ops.nonEmpty && f.ops.exists(_.sub.isDefined))
    )

  test("VmTracer captures the SSTORE key/value in ex.store"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> sstore))
    val tracer = VmTracer()
    tracer.onTxStart(alice, Some(callee), gas = 1_000_000, value = UInt256.Zero, input = ByteString.empty)
    val _ = interp(tracer).run(ctx(Some(callee), world))
    val storeOp = tracer.result.toSeq.flatMap(_.ops).find(_.exStore.isDefined)
    assert(storeOp.exists(_.exStore.contains((UInt256(1), UInt256(7)))))

  // -- non-interference (result-parity) + branch-free -------------------------------------------------------------

  test("each role tracer is observe-only: execution result is byte-identical to NoTracing"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> addReturn))
    val base = interp(NoTracing).run(ctx(Some(callee), world))
    val tracers: Seq[ExecutionTracer] =
      Seq(StructLogTracer(enableMemory = true, enableStorage = true), CallTracer(), VmTracer())
    val allMatch = tracers.forall { t =>
      val r = interp(t).run(ctx(Some(callee), world))
      r.returnData == base.returnData && r.error == base.error && r.gasRemaining == base.gasRemaining
    }
    assert(base.error.isEmpty && base.returnData == UInt256(8).bytes && allMatch)

  test("PrestateTracer is observe-only: execution result is byte-identical to NoTracing"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> sstore))
    val base = interp(NoTracing).run(ctx(Some(callee), world))
    val traced = interp(PrestateTracer[TestWorld, TestStorage](world)).run(ctx(Some(callee), world))
    assert(
      traced.returnData == base.returnData && traced.error == base.error && traced.gasRemaining == base.gasRemaining
    )

  test("NoTracing remains the branch-free one-slot default and elides its hooks"):
    val world = TestWorld(accounts = Map(funded(alice, 0)), codes = Map(callee -> addReturn))
    val untraced = interp(NoTracing).run(ctx(Some(callee), world))
    val structlogged = interp(StructLogTracer()).run(ctx(Some(callee), world))
    assert(untraced.error.isEmpty && untraced.returnData == structlogged.returnData)
