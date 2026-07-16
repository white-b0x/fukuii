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

/** P2 opcode-set / dispatch coverage — a representative slice of the transcribed opcode semantics (arithmetic, signed
  * ops, `CHAINID` reading the threaded chain context, block-context reads), the dense-table build-time `validate`, the
  * ETC-table `BLOBHASH`=`InvalidOp` byte fact, `Osaka = Prague + CLZ`, and the EIP-6780 SELFDESTRUCT gating. Uses the
  * same minimal in-memory world double as `WorldStateSpec`; the concrete interpreter loop is P4.
  *
  * One `assert` per test — the `-Wnonunit-statement` build gate rejects a discarded intermediate `Assertion`.
  */
class OpCodeSpec extends AnyFunSuite:

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

  /** A VM seam stub — the re-entrant `call`/`create` (and `run`) are P4; none of these non-reentrant opcode tests
    * invoke them.
    */
  private object StubVM extends VM[TestWorld, TestStorage]:
    def run(context: CallContext[TestWorld, TestStorage]): ExecutionResult[TestWorld, TestStorage] =
      throw new NotImplementedError("VM.run is P4")
    def call(
        context: CallContext[TestWorld, TestStorage],
        ownerAddr: Address
    ): ExecutionResult[TestWorld, TestStorage] =
      throw new NotImplementedError("VM.call is P4")
    def create(
        context: CallContext[TestWorld, TestStorage],
        salt: Option[UInt256]
    ): (ExecutionResult[TestWorld, TestStorage], Address) =
      throw new NotImplementedError("VM.create is P4")

  private val owner = Address.fromHex("0x1111111111111111111111111111111111111111")

  private def header(
      difficulty: BigInt = 17,
      number: BigInt = 100,
      timestamp: Long = 1000,
      gasLimit: Long = 30000000,
      beneficiary: Address = Address.Zero,
      baseFee: Option[BigInt] = None
  ): com.chipprbots.fukuii.domain.BlockHeader =
    com.chipprbots.fukuii.domain.BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = beneficiary,
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = difficulty,
      number = number,
      gasLimit = gasLimit,
      gasUsed = 0,
      unixTimestamp = timestamp,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty,
      baseFeePerGas = baseFee
    )

  /** Build a program state. `stack` is **top-first** — `stack(0)` is the first word an opcode pops. */
  private def mkState(
      config: EvmConfig = EvmConfig.EthCancun,
      code: ByteString = ByteString.empty,
      stack: Seq[UInt256] = Seq.empty,
      world: TestWorld = TestWorld(),
      chainId: ChainId = ChainId(1),
      prevRandao: Option[UInt256] = None,
      hdr: com.chipprbots.fukuii.domain.BlockHeader = header(),
      gas: BigInt = 1_000_000
  ): MessageFrame[TestWorld, TestStorage] =
    val env = ExecutionEnv(
      ownerAddr = owner,
      callerAddr = owner,
      originAddr = owner,
      gasPrice = UInt256.Zero,
      inputData = ByteString.empty,
      value = UInt256.Zero,
      program = EvmCode(code),
      blockHeader = hdr,
      callDepth = 0,
      startGas = gas,
      evmConfig = config,
      chainId = chainId,
      prevRandao = prevRandao
    )
    MessageFrame[TestWorld, TestStorage](
      vm = StubVM,
      env = env,
      gas = gas,
      world = world,
      addressesToDelete = Set.empty,
      stack = Stack.empty().push(stack.reverse),
      originalWorld = world,
      accessedAddresses = Set.empty,
      accessedStorageKeys = Set.empty
    )

  private def top(s: MessageFrame[TestWorld, TestStorage]): UInt256 = s.stack.pop()._1

  private def run(op: OpCode, stack: Seq[UInt256], config: EvmConfig = EvmConfig.EthCancun): UInt256 =
    top(op.execute(mkState(config = config, stack = stack)))

  // -- arithmetic / bitwise / signed -------------------------------------------------------------------------------

  test("ADD/SUB/MUL/DIV wrap and truncate per EVM semantics"):
    assert(
      run(ADD, Seq(UInt256(3), UInt256(5))) == UInt256(8) &&
        run(SUB, Seq(UInt256(10), UInt256(4))) == UInt256(6) &&
        run(MUL, Seq(UInt256(6), UInt256(7))) == UInt256(42) &&
        run(DIV, Seq(UInt256(20), UInt256(0))) == UInt256.Zero // div by zero → 0
    )

  test("SDIV interprets operands as two's-complement signed"):
    // (-4) / 2 = -2  ==  MaxValue-1
    val minus4 = UInt256(UInt256.MaxValue.toBigInt - 3)
    assert(run(SDIV, Seq(minus4, UInt256(2))) == UInt256(UInt256.MaxValue.toBigInt - 1))

  test("SLT signed vs LT unsigned on -1"):
    val minus1 = UInt256.MaxValue // -1 signed, huge unsigned
    assert(run(SLT, Seq(minus1, UInt256(1))) == UInt256.One && run(LT, Seq(minus1, UInt256(1))) == UInt256.Zero)

  test("ISZERO / EQ / NOT"):
    assert(
      run(ISZERO, Seq(UInt256.Zero)) == UInt256.One &&
        run(EQ, Seq(UInt256(9), UInt256(9))) == UInt256.One &&
        run(NOT, Seq(UInt256.Zero)) == UInt256.MaxValue
    )

  test("BYTE selects the big-endian byte; SHL/SHR shift"):
    assert(
      run(BYTE, Seq(UInt256(31), UInt256(0xff))) == UInt256(0xff) && // byte 31 (LSB) of 0x..ff
        run(SHL, Seq(UInt256(1), UInt256(1))) == UInt256(2) && // 1 << 1
        run(SHR, Seq(UInt256(1), UInt256(2))) == UInt256(1) // 2 >> 1
    )

  test("CLZ (EIP-7939): leading-zero count, 256 for zero"):
    assert(
      run(CLZ, Seq(UInt256.Zero), EvmConfig.EthOsaka) == UInt256(256) &&
        run(CLZ, Seq(UInt256.One), EvmConfig.EthOsaka) == UInt256(255)
    )

  // -- chain / block context ---------------------------------------------------------------------------------------

  test("CHAINID reads the chain id threaded on the environment, not the fork config"):
    assert(
      top(CHAINID.execute(mkState(chainId = ChainId(61)))) == UInt256(61) && // ETC
        top(CHAINID.execute(mkState(chainId = ChainId(1)))) == UInt256(1) // ETH
    )

  test("NUMBER / TIMESTAMP / GASLIMIT / COINBASE read the header"):
    val h = header(number = 42, timestamp = 777, gasLimit = 8_000_000, beneficiary = owner)
    assert(
      top(NUMBER.execute(mkState(hdr = h))) == UInt256(42) &&
        top(TIMESTAMP.execute(mkState(hdr = h))) == UInt256(777) &&
        top(GASLIMIT.execute(mkState(hdr = h))) == UInt256(8_000_000) &&
        top(COINBASE.execute(mkState(hdr = h))) == owner.toUInt256
    )

  test("DIFFICULTY returns prevRandao post-Merge (EIP-4399), else the header difficulty"):
    assert(
      top(DIFFICULTY.execute(mkState(hdr = header(difficulty = 17)))) == UInt256(17) &&
        top(DIFFICULTY.execute(mkState(prevRandao = Some(UInt256(99))))) == UInt256(99)
    )

  // -- stack / storage ---------------------------------------------------------------------------------------------

  test("PUSH1 reads its immediate operand from the code"):
    // code: PUSH1 0x2a
    assert(top(PUSH1.execute(mkState(code = ByteString(0x60.toByte, 0x2a.toByte)))) == UInt256(0x2a))

  test("SSTORE then SLOAD round-trips a slot"):
    val w = TestWorld().saveAccount(owner, Account.empty())
    val afterStore = SSTORE.execute(mkState(stack = Seq(UInt256(1), UInt256(42)), world = w))
    val afterLoad = SLOAD.execute(mkState(stack = Seq(UInt256(1)), world = afterStore.world))
    assert(afterStore.world.getStorage(owner).load(UInt256(1)) == BigInt(42) && top(afterLoad) == UInt256(42))

  // -- dense table + validate --------------------------------------------------------------------------------------

  test("denseTable pre-fills all 256 slots and validate accepts a correct table"):
    val table = OpCodes.denseTable(OpCodes.EthCancunOpCodes)
    assert(table.length == 256 && table(0x01) == ADD && table(0x00) == STOP && table(0xef) == InvalidOp(0xef.toByte))

  test("validate rejects an opcode placed at the wrong slot"):
    val bad = IArray.tabulate(256)(i => if i == 5 then ADD else InvalidOp(i.toByte)) // ADD.code == 0x01, not 5
    assertThrows[IllegalArgumentException](OpCodes.validate(bad))

  // -- byte facts: ETH-only blob opcodes, Osaka = Prague + CLZ, Olympia set -----------------------------------------

  test("BLOBHASH/BLOBBASEFEE are ETH-only: present on ETH Cancun, InvalidOp on the ETC Olympia table"):
    assert(
      EvmConfig.EthCancun.byteToOpCode(0x49) == BLOBHASH &&
        EvmConfig.EthCancun.byteToOpCode(0x4a) == BLOBBASEFEE &&
        EvmConfig.EtcOlympia.byteToOpCode(0x49) == InvalidOp(0x49.toByte) &&
        EvmConfig.EtcOlympia.byteToOpCode(0x4a) == InvalidOp(0x4a.toByte)
    )

  test("Osaka = Prague + CLZ: CLZ (0x1e) present on Osaka, InvalidOp on Prague"):
    assert(
      EvmConfig.EthOsaka.byteToOpCode(0x1e) == CLZ &&
        EvmConfig.EthPrague.byteToOpCode(0x1e) == InvalidOp(0x1e.toByte) &&
        OpCodes.EthOsakaOpCodes == CLZ :: OpCodes.EthCancunOpCodes
    )

  test("ETC Olympia opcode set: BASEFEE/TLOAD/MCOPY/CLZ present, blob ops absent"):
    val t = EvmConfig.EtcOlympia
    assert(
      t.byteToOpCode(0x48) == BASEFEE && t.byteToOpCode(0x5c) == TLOAD &&
        t.byteToOpCode(0x5e) == MCOPY && t.byteToOpCode(0x1e) == CLZ
    )

  // -- EIP-6780 SELFDESTRUCT gating --------------------------------------------------------------------------------

  test("EIP-6780 is active on ETC Olympia and gates SELFDESTRUCT same-tx deletion"):
    // A pre-existing contract (present in originalWorld): under EIP-6780 it is NOT deleted, only balance transferred;
    // without EIP-6780 the same account IS scheduled for deletion.
    val w = TestWorld().saveAccount(owner, Account.empty().copy(balance = Wei(UInt256(5))))
    val beneficiaryAddr = Address.fromHex("0x2222222222222222222222222222222222222222")
    val underOlympia =
      SELFDESTRUCT.execute(mkState(config = EvmConfig.EtcOlympia, stack = Seq(beneficiaryAddr.toUInt256), world = w))
    val underFrontier =
      SELFDESTRUCT.execute(mkState(config = EvmConfig.Frontier, stack = Seq(beneficiaryAddr.toUInt256), world = w))
    assert(
      EvmConfig.EtcOlympia.eip6780Enabled && !EvmConfig.Frontier.eip6780Enabled &&
        !underOlympia.addressesToDelete.contains(owner) && // EIP-6780: pre-existing contract not deleted
        underFrontier.addressesToDelete.contains(owner)
    )
