package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.domain.Wei
import com.chipprbots.fukuii.domain.Withdrawal
import com.chipprbots.fukuii.evm.EvmConfig
import com.chipprbots.fukuii.evm.EvmInterpreter
import com.chipprbots.fukuii.storage.EphemDataSource
import com.chipprbots.fukuii.trie.InMemoryMptStorage
import com.chipprbots.fukuii.trie.MptNode

/** L4 P5a — the [[SystemCallProcessor]] primitive, the [[PreExecutionProcessor]] phase (EIP-4788 beacon-root + EIP-2935
  * block-hash population), and the [[WithdrawalsProcessor]] (EIP-4895). All ETH/PoS consensus (beacon's lane), byte-
  * cited to go-ethereum `state_processor.go` + besu `AbstractBlockProcessor`/`systemcall`; ETC binds absent/reject.
  *
  * The system contracts are stubbed with a minimal storage contract (`CALLDATALOAD(0) → SSTORE(slot 0)`), which proves
  * the framework wiring — SystemAddress sender, 30M budget, the slot write persists, absence on ETC. The canonical
  * ring-buffer contract byte-vectors are a genesis-allocation + eye BlockchainTests concern.
  */
class SystemCallProcessorSpec extends AnyFunSuite:

  private val chainId: ChainId = ChainId(1)
  private val interpreter = new EvmInterpreter[InMemoryWorldState, InMemoryAccountStorage]()
  private val systemCall = new SystemCallProcessor(interpreter)

  private def addr(b: Byte): Address = Address(ByteString(Array.fill[Byte](Address.Length)(b)))

  /** `PUSH1 0; CALLDATALOAD; PUSH1 0; SSTORE; STOP` — stores calldata[0:32] at storage slot 0. */
  private val storeCalldataCode: ByteString = ByteString(Hex.decode("0x60003560005500"))

  /** `CALLER; PUSH1 0; SSTORE; STOP` — stores the caller address at storage slot 0. */
  private val storeCallerCode: ByteString = ByteString(Hex.decode("0x3360005500"))

  private def world(entries: (Address, Account, ByteString)*): InMemoryWorldState =
    val base = InMemoryWorldState(
      codeStorage = new CodeStorage(EphemDataSource()),
      mptStorage = new InMemoryMptStorage,
      getBlockHashByNumber = _ => None,
      accountStartNonce = UInt256.Zero,
      stateRootHash = MptNode.EmptyRootHash,
      noEmptyAccounts = true
    )
    entries.foldLeft(base) { case (w, (a, acc, code)) => w.saveAccount(a, acc).saveCode(a, code) }

  private def contractAccount: Account = Account.empty().copy(nonce = UInt256.One)

  private def header(number: BigInt = 1): BlockHeader =
    BlockHeader(
      parentHash = Hash(ByteString(Array.fill[Byte](Hash.Length)(0xcd.toByte))),
      ommersHash = Hash.Zero,
      beneficiary = addr(0x33),
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 0,
      number = number,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = 0,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty
    )

  // -- the system-call primitive --------------------------------------------------------------------------------------

  test("system call runs as the SystemAddress sender (0xfff…fffe)"):
    val target = addr(0x77)
    val w = world((target, contractAccount, storeCallerCode))
    val outcome = systemCall.process(target, ByteString.empty, header(), EvmConfig.EthCancun, w, chainId).toOption.get
    val stored = outcome.world.getStorage(target).load(UInt256(0))
    assert(stored == BigInt(1, SystemCallProcessor.SystemAddress.bytes.toArray))

  test("system call writes the input data to the target contract's storage and threads the world"):
    val target = addr(0x77)
    val input = ByteString(Array.fill[Byte](32)(0xab.toByte))
    val w = world((target, contractAccount, storeCalldataCode))
    val outcome = systemCall.process(target, input, header(), EvmConfig.EthCancun, w, chainId).toOption.get
    assert(outcome.world.getStorage(target).load(UInt256(0)) == BigInt(1, input.toArray))

  test("system call to a codeless address fails LOUD (NoCodeAtAddress) — never a silent skip"):
    val target = addr(0x77)
    val w = world() // no contract deployed
    assert(
      systemCall.process(target, ByteString.empty, header(), EvmConfig.EthCancun, w, chainId) ==
        Left(SystemCallError.NoCodeAtAddress(target))
    )

  test("system call does not touch the unfunded SystemAddress (doTransfer=false, value 0)"):
    val target = addr(0x77)
    val w = world((target, contractAccount, storeCalldataCode))
    val outcome = systemCall
      .process(target, ByteString(Array.fill[Byte](32)(1)), header(), EvmConfig.EthCancun, w, chainId)
      .toOption
      .get
    assert(outcome.world.getAccount(SystemCallProcessor.SystemAddress).isEmpty)

  // -- PreExecution: EIP-4788 beacon-root ------------------------------------------------------------------------------

  test("EIP-4788 — a pre-exec beacon-root call writes the beacon root to the beacon-roots contract on Cancun+ ETH"):
    val root = Hash(ByteString(Array.fill[Byte](Hash.Length)(0xab.toByte)))
    val w = world((PreExecutionProcessor.BeaconRootsAddress, contractAccount, storeCalldataCode))
    val h = header().withParentBeaconBlockRoot(root)
    val pre = PreExecutionProcessor.EthPreExecution(systemCall, historyStorageActive = false)
    val after = pre.process(h, EvmConfig.EthCancun, w, chainId).toOption.get
    assert(after.getStorage(PreExecutionProcessor.BeaconRootsAddress).load(UInt256(0)) == BigInt(1, root.bytes.toArray))

  test("EIP-4788 — absent on ETC (NoPreExecution leaves the world untouched)"):
    val root = Hash(ByteString(Array.fill[Byte](Hash.Length)(0xab.toByte)))
    val w = world((PreExecutionProcessor.BeaconRootsAddress, contractAccount, storeCalldataCode))
    val h = header().withParentBeaconBlockRoot(root)
    val after = PreExecutionProcessor.NoPreExecution.process(h, EvmConfig.EtcOlympia, w, chainId).toOption.get
    assert(after.getStorage(PreExecutionProcessor.BeaconRootsAddress).load(UInt256(0)) == BigInt(0))

  // -- PreExecution: EIP-2935 block-hash population --------------------------------------------------------------------

  test("EIP-2935 — a pre-exec call populates the history-storage contract with the parent hash on Prague+"):
    val w = world((PreExecutionProcessor.HistoryStorageAddress, contractAccount, storeCalldataCode))
    val h = header() // parentHash = 0xcd…cd
    val pre = PreExecutionProcessor.EthPreExecution(systemCall, historyStorageActive = true)
    val after = pre.process(h, EvmConfig.EthPrague, w, chainId).toOption.get
    assert(
      after.getStorage(PreExecutionProcessor.HistoryStorageAddress).load(UInt256(0)) ==
        BigInt(1, h.parentHash.bytes.toArray)
    )

  test("EIP-2935 — not run when history storage is inactive (Cancun, historyStorageActive=false)"):
    val w = world((PreExecutionProcessor.HistoryStorageAddress, contractAccount, storeCalldataCode))
    val pre = PreExecutionProcessor.EthPreExecution(systemCall, historyStorageActive = false)
    val after = pre.process(header(), EvmConfig.EthCancun, w, chainId).toOption.get
    assert(after.getStorage(PreExecutionProcessor.HistoryStorageAddress).load(UInt256(0)) == BigInt(0))

  test("EIP-2935 — absent on ETC (NoPreExecution leaves the world untouched)"):
    val w = world((PreExecutionProcessor.HistoryStorageAddress, contractAccount, storeCalldataCode))
    val after = PreExecutionProcessor.NoPreExecution.process(header(), EvmConfig.EtcOlympia, w, chainId).toOption.get
    assert(after.getStorage(PreExecutionProcessor.HistoryStorageAddress).load(UInt256(0)) == BigInt(0))

  test("PreExecution runs both 4788 and 2935 on Prague+ when the beacon root is present"):
    val root = Hash(ByteString(Array.fill[Byte](Hash.Length)(0xab.toByte)))
    val w = world(
      (PreExecutionProcessor.BeaconRootsAddress, contractAccount, storeCalldataCode),
      (PreExecutionProcessor.HistoryStorageAddress, contractAccount, storeCalldataCode)
    )
    val h = header().withParentBeaconBlockRoot(root)
    val pre = PreExecutionProcessor.EthPreExecution(systemCall, historyStorageActive = true)
    val after = pre.process(h, EvmConfig.EthPrague, w, chainId).toOption.get
    assert(
      after.getStorage(PreExecutionProcessor.BeaconRootsAddress).load(UInt256(0)) == BigInt(1, root.bytes.toArray) &&
        after.getStorage(PreExecutionProcessor.HistoryStorageAddress).load(UInt256(0)) ==
        BigInt(1, h.parentHash.bytes.toArray)
    )

  // -- Withdrawals (EIP-4895) -----------------------------------------------------------------------------------------

  private def accountWorld(entries: (Address, Account)*): InMemoryWorldState =
    val base = InMemoryWorldState(
      codeStorage = new CodeStorage(EphemDataSource()),
      mptStorage = new InMemoryMptStorage,
      getBlockHashByNumber = _ => None,
      accountStartNonce = UInt256.Zero,
      stateRootHash = MptNode.EmptyRootHash,
      noEmptyAccounts = true
    )
    entries.foldLeft(base)((w, kv) => w.saveAccount(kv._1, kv._2))

  test("EIP-4895 — a withdrawal credits its address, converting Gwei to Wei (× 10^9)"):
    val validator = addr(0x55)
    val w = accountWorld()
    val out = WithdrawalsProcessor.Eip4895WithdrawalsProcessor
      .processWithdrawals(List(Withdrawal(index = 0, validatorIndex = 0, address = validator, amount = 5)), w)
    assert(out.getBalance(validator).toBigInt == BigInt(5) * BigInt(10).pow(9))

  test("EIP-4895 — N withdrawals credit N validator balances additively"):
    val v1 = addr(0x55)
    val v2 = addr(0x56)
    val w = accountWorld(v2 -> Account.empty().copy(balance = Wei(UInt256(BigInt(10).pow(9))))) // v2 starts with 1 Gwei
    val out = WithdrawalsProcessor.Eip4895WithdrawalsProcessor.processWithdrawals(
      List(
        Withdrawal(0, 0, v1, amount = 3),
        Withdrawal(1, 1, v2, amount = 7)
      ),
      w
    )
    assert(
      out.getBalance(v1).toBigInt == BigInt(3) * BigInt(10).pow(9) &&
        out.getBalance(v2).toBigInt == BigInt(8) * BigInt(10).pow(9) // 1 (start) + 7 credited
    )
