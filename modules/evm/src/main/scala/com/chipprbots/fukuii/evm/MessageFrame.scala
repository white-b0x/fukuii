package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Log

/** Intermediate state updated with execution of each opcode in the program — the **immutable** per-step EVM state
  * (`copy`-per-opcode, a fresh [[Stack]]/[[Memory]] on mutation). Whether the interpreter loop keeps this immutable
  * frame or moves to a mutable frame with an explicit revert journal is a **benchmark-gated OPEN for P4/P7** (L3 plan
  * §6) — it is byte-identical either way (correctness-neutral), so P1 does not decide it.
  *
  * Retyped to the built L0/L1 APIs: gas counters are `BigInt` (the plan's gas model, §3/§9 — L1 does not define a
  * `GasAmount` value type, and a wrapping `UInt256` would break out-of-gas detection); logs are L1 [[Log]];
  * storage-slot keys are the EVM word [[UInt256]].
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
final case class MessageFrame[W <: WorldState[W, S], S <: AccountStorage[S]](
    vm: VM[W, S],
    env: ExecutionEnv,
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
    createdAddresses: Set[Address] = Set.empty,
    transientStorage: Map[(Address, UInt256), BigInt] = Map.empty,
    opcodeGasCost: BigInt = 0,
    stateGasReservoir: BigInt = 0
):

  def config: EvmConfig = env.evmConfig

  def ownAddress: Address = env.ownerAddr

  def ownBalance: UInt256 = world.getBalance(ownAddress)

  def storage: S = world.getStorage(ownAddress)

  def gasUsed: BigInt = env.startGas - gas

  def withWorld(updated: W): MessageFrame[W, S] =
    copy(world = updated)

  def withStorage(updated: S): MessageFrame[W, S] =
    withWorld(world.saveStorage(ownAddress, updated))

  def program: EvmCode = env.program

  def inputData: ByteString = env.inputData

  def spendGas(amount: BigInt): MessageFrame[W, S] =
    copy(gas = gas - amount)

  def refundGas(amount: BigInt): MessageFrame[W, S] =
    copy(gasRefund = gasRefund + amount)

  def step(i: Int = 1): MessageFrame[W, S] =
    copy(pc = pc + i)

  def goto(i: Int): MessageFrame[W, S] =
    copy(pc = i)

  def withStack(stack: Stack): MessageFrame[W, S] =
    copy(stack = stack)

  def withMemory(memory: Memory): MessageFrame[W, S] =
    copy(memory = memory)

  def withError(error: HaltReason): MessageFrame[W, S] =
    copy(error = Some(error), returnData = ByteString.empty, halted = true)

  def withReturnData(data: ByteString): MessageFrame[W, S] =
    copy(returnData = data)

  def withAddressToDelete(addr: Address): MessageFrame[W, S] =
    copy(addressesToDelete = addressesToDelete + addr)

  def withAddressesToDelete(addresses: Set[Address]): MessageFrame[W, S] =
    copy(addressesToDelete = addressesToDelete ++ addresses)

  def withLog(log: Log): MessageFrame[W, S] =
    copy(logs = logs :+ log)

  def withLogs(log: Seq[Log]): MessageFrame[W, S] =
    copy(logs = logs ++ log)

  def withInternalTxs(txs: Seq[InternalTransaction]): MessageFrame[W, S] =
    copy(internalTxs = internalTxs ++ txs)

  def halt: MessageFrame[W, S] =
    copy(halted = true)

  def revert(data: ByteString): MessageFrame[W, S] =
    copy(error = Some(RevertOccurs), returnData = data, halted = true)

  def addAccessedAddress(addr: Address): MessageFrame[W, S] =
    copy(accessedAddresses = accessedAddresses + addr)

  def addAccessedStorageKey(addr: Address, key: UInt256): MessageFrame[W, S] =
    copy(accessedStorageKeys = accessedStorageKeys + ((addr, key)))

  def addAccessedAddresses(addresses: Set[Address]): MessageFrame[W, S] =
    copy(accessedAddresses = accessedAddresses ++ addresses)

  def addAccessedStorageKeys(storageKeys: Set[(Address, UInt256)]): MessageFrame[W, S] =
    copy(accessedStorageKeys = accessedStorageKeys ++ storageKeys)

  /** EIP-6780: register a contract address as created in the current transaction. Populated at create-frame start (the
    * contract's own address) — mirrors go-ethereum `StateDB.CreateContract` (core/vm/evm.go, set before the initcode
    * runs) and besu `MessageFrame.addCreate` (ContractCreationProcessor, before `CODE_EXECUTING`) — so a contract that
    * SELFDESTRUCTs inside its own constructor is seen as same-tx-created.
    */
  def addCreatedAddress(addr: Address): MessageFrame[W, S] =
    copy(createdAddresses = createdAddresses + addr)

  def addCreatedAddresses(addresses: Set[Address]): MessageFrame[W, S] =
    copy(createdAddresses = createdAddresses ++ addresses)

  def toResult: ExecutionResult[W, S] =
    ExecutionResult[W, S](
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
      transientStorage,
      createdAddresses
    )
