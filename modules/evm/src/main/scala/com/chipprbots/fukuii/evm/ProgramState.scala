package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Log

/** Intermediate state updated with execution of each opcode in the program — the **immutable** per-step EVM state
  * (`copy`-per-opcode, a fresh [[Stack]]/[[Memory]] on mutation). This is the AS-IS pure-functional shape, transcribed
  * faithfully: whether the interpreter loop keeps this immutable frame or moves to a mutable frame with an explicit
  * revert journal is a **benchmark-gated OPEN for P4/P7** (L3 plan §6) — it is byte-identical either way
  * (correctness-neutral), so P1 does not decide it.
  *
  * Retyped to the built L0/L1 APIs: gas counters are `BigInt` (the AS-IS `GasAmount` value type was not carried into
  * the rebuild; the plan's gas model is `BigInt`, §3/§9 — and a wrapping `UInt256` would break out-of-gas detection);
  * logs are L1 [[Log]] (AS-IS `TxLogEntry`); storage-slot keys are the EVM word [[UInt256]] (AS-IS
  * `domain.StorageKey`).
  *
  * **P1 deferrals.** The smart companion `apply(vm, context, env)` factory — which seeds the EIP-2929 warm sets from
  * `PrecompiledContracts.getContracts` and the EIP-3651 warm-COINBASE via `EvmConfig.eip3651Enabled` — is deferred to
  * P2, where the precompile registry and those `EvmConfig` intent-getters land. [[withInternalTxs]] always appends
  * here; P2 re-adds the `EvmConfig.traceInternalTransactions` gate (trace data is correctness-neutral).
  *
  * @param stateGasReservoir
  *   **F11 / RX-L3-14 seam (sized now, unused).** EIP-8037 multidimensional gas needs a *second* gas accumulator on the
  *   frame (besu `frame.consumeStateGas`/`incrementStateGasReservoir`); the immutable state is *shaped* to carry it now
  *   so the deferred EIP-8037 work (an Amsterdam EIP, not built) does not retrofit this class. Also the L3 carrier for
  *   heterogeneous-family (ZK-EVM) custom cost models behind the same seam.
  */
final case class ProgramState[W <: WorldState[W, S], S <: AccountStorage[S]](
    vm: VM[W, S],
    env: ExecEnv,
    gas: BigInt,
    world: W,
    addressesToDelete: Set[Address],
    stack: Stack = Stack.empty(),
    memory: Memory = Memory.empty,
    pc: Int = 0,
    returnData: ByteString = ByteString.empty,
    gasRefund: BigInt = 0,
    internalTxs: Vector[InternalTransaction] = Vector.empty,
    logs: Vector[Log] = Vector.empty,
    halted: Boolean = false,
    staticCtx: Boolean = false,
    error: Option[HaltReason] = None,
    originalWorld: W,
    accessedAddresses: Set[Address],
    accessedStorageKeys: Set[(Address, UInt256)],
    transientStorage: Map[(Address, UInt256), BigInt] = Map.empty,
    opcodeGasCost: BigInt = 0,
    stateGasReservoir: BigInt = 0
):

  def config: EvmConfig = env.evmConfig

  def ownAddress: Address = env.ownerAddr

  def ownBalance: UInt256 = world.getBalance(ownAddress)

  def storage: S = world.getStorage(ownAddress)

  def gasUsed: BigInt = env.startGas - gas

  def withWorld(updated: W): ProgramState[W, S] =
    copy(world = updated)

  def withStorage(updated: S): ProgramState[W, S] =
    withWorld(world.saveStorage(ownAddress, updated))

  def program: Program = env.program

  def inputData: ByteString = env.inputData

  def spendGas(amount: BigInt): ProgramState[W, S] =
    copy(gas = gas - amount)

  def refundGas(amount: BigInt): ProgramState[W, S] =
    copy(gasRefund = gasRefund + amount)

  def step(i: Int = 1): ProgramState[W, S] =
    copy(pc = pc + i)

  def goto(i: Int): ProgramState[W, S] =
    copy(pc = i)

  def withStack(stack: Stack): ProgramState[W, S] =
    copy(stack = stack)

  def withMemory(memory: Memory): ProgramState[W, S] =
    copy(memory = memory)

  def withError(error: HaltReason): ProgramState[W, S] =
    copy(error = Some(error), returnData = ByteString.empty, halted = true)

  def withReturnData(data: ByteString): ProgramState[W, S] =
    copy(returnData = data)

  def withAddressToDelete(addr: Address): ProgramState[W, S] =
    copy(addressesToDelete = addressesToDelete + addr)

  def withAddressesToDelete(addresses: Set[Address]): ProgramState[W, S] =
    copy(addressesToDelete = addressesToDelete ++ addresses)

  def withLog(log: Log): ProgramState[W, S] =
    copy(logs = logs :+ log)

  def withLogs(log: Seq[Log]): ProgramState[W, S] =
    copy(logs = logs ++ log)

  def withInternalTxs(txs: Seq[InternalTransaction]): ProgramState[W, S] =
    copy(internalTxs = internalTxs ++ txs)

  def halt: ProgramState[W, S] =
    copy(halted = true)

  def revert(data: ByteString): ProgramState[W, S] =
    copy(error = Some(RevertOccurs), returnData = data, halted = true)

  def addAccessedAddress(addr: Address): ProgramState[W, S] =
    copy(accessedAddresses = accessedAddresses + addr)

  def addAccessedStorageKey(addr: Address, key: UInt256): ProgramState[W, S] =
    copy(accessedStorageKeys = accessedStorageKeys + ((addr, key)))

  def addAccessedAddresses(addresses: Set[Address]): ProgramState[W, S] =
    copy(accessedAddresses = accessedAddresses ++ addresses)

  def addAccessedStorageKeys(storageKeys: Set[(Address, UInt256)]): ProgramState[W, S] =
    copy(accessedStorageKeys = accessedStorageKeys ++ storageKeys)

  def toResult: ProgramResult[W, S] =
    ProgramResult[W, S](
      returnData,
      if error.exists(_.useWholeGas) then BigInt(0) else gas,
      world,
      addressesToDelete,
      logs,
      internalTxs,
      gasRefund,
      error,
      accessedAddresses,
      accessedStorageKeys,
      transientStorage
    )
