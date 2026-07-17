package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.evm.CallContext
import com.chipprbots.fukuii.evm.EvmConfig
import com.chipprbots.fukuii.evm.EvmInterpreter

/** Why a system call could not run to completion — a **fail-LOUD** result (never a silent skip). Both reference clients
  * treat a non-completing system call as fatal: go-ethereum `panic`s on the EIP-2935 call error
  * (`state_processor.go:361`), besu throws from `SystemCallProcessor.process` on a non-`COMPLETED_SUCCESS` frame or a
  * missing/codeless target. A system call is a consensus mutation the block depends on, so a failure aborts the block.
  */
enum SystemCallError:

  /** The system contract is not deployed / has no code at `address`. besu `SystemCallProcessor` throws
    * `SystemCallNoCodeAtAddressException` here; go-ethereum's `evm.Call` to a codeless address instead returns success
    * with empty output (a silent no-op). On a correctly-initialised Cancun+ chain the contract is always present, so
    * this path is **unreachable in consensus** — fukuii chooses besu's fail-LOUD form so a genesis/allocation
    * misconfiguration surfaces here rather than silently mis-executing every block.
    */
  case NoCodeAtAddress(address: Address)

  /** The system contract's code halted with a VM error (revert / exceptional halt) — go-ethereum `panic(err)`
    * (`state_processor.go:361`), besu throws on a non-`COMPLETED_SUCCESS` frame.
    */
  case Reverted(address: Address, reason: String)

/** The result of a completed system call — the mutated world and the contract's return data. */
final case class SystemCallOutcome(world: InMemoryWorldState, output: ByteString)

/** The reusable **system-call primitive** — execute a system contract as the network's post-merge block machinery does
  * (EIP-4788 beacon-root, EIP-2935 block-hash population, and the EIP-7002/7251 request queues at P5b all use it).
  * go-ethereum `core/state_processor.go` `systemCallGasBudget`/`ProcessBeaconBlockRoot`; besu
  * `mainnet/systemcall/SystemCallProcessor`.
  *
  * A system call is **not** a transaction: the sender is the fixed [[SystemCallProcessor.SystemAddress]]
  * (`0xfff…fffe`), the gas budget is a fixed [[SystemCallProcessor.SystemCallGasLimit]] (30M) that is **not** metered
  * against the block gas limit, and no upfront gas is debited, no fee is credited, and no nonce is bumped — only the
  * contract's own state writes (SSTORE) survive. `gasPrice = value = 0`; **`doTransfer = false`** so the unfunded
  * `SystemAddress` is never touched (go-ethereum's `evm.Call` with value 0 relies on `getOrNewStateObject` +
  * `Finalise(true)` to sweep the empty `SystemAddress`; fukuii's `guaranteedTransfer` would instead fail-loud on the
  * missing account, so a value-less system call omits the transfer entirely — same state root, no empty touch).
  *
  * **R2:** stateless and immutable; the world is threaded in and a new world returned, no `object … { var … }`.
  * Concrete over [[InMemoryWorldState]] — L4's single concrete world — mirroring [[TransactionProcessor]].
  */
final class SystemCallProcessor(interpreter: EvmInterpreter[InMemoryWorldState, InMemoryAccountStorage]):

  import SystemCallProcessor.*

  /** Call `contractAddress` with `inputData` as the `SystemAddress` sender on a 30M budget, threading `world` and
    * returning the mutated world + output. Fails LOUD ([[SystemCallError]]) on a codeless target or a VM halt — never a
    * silent skip (L4 plan §5/§9, RX-L4-11).
    */
  def process(
      contractAddress: Address,
      inputData: ByteString,
      header: BlockHeader,
      evmConfig: EvmConfig,
      world: InMemoryWorldState,
      chainId: ChainId
  ): Either[SystemCallError, SystemCallOutcome] =
    if world.getCode(contractAddress).isEmpty then Left(SystemCallError.NoCodeAtAddress(contractAddress))
    else
      val context = CallContext[InMemoryWorldState, InMemoryAccountStorage](
        callerAddr = SystemAddress,
        originAddr = SystemAddress,
        recipientAddr = Some(contractAddress),
        gasPrice = UInt256.Zero,
        startGas = SystemCallGasLimit,
        inputData = inputData,
        value = UInt256.Zero,
        endowment = UInt256.Zero,
        doTransfer = false, // value-less system call — never touch the unfunded SystemAddress (see class scaladoc)
        blockHeader = header,
        callDepth = 0,
        world = world,
        initialAddressesToDelete = Set.empty,
        evmConfig = evmConfig,
        chainId = chainId,
        staticCtx = false,
        originalWorld = world,
        warmAddresses = Set(contractAddress), // go-ethereum AddAddressToAccessList(target); state-inert, gas-only
        warmStorage = Set.empty
      )
      val result = interpreter.run(context)
      result.error match
        case Some(halt) => Left(SystemCallError.Reverted(contractAddress, halt.toString))
        case None       => Right(SystemCallOutcome(result.world, result.returnData))

object SystemCallProcessor:

  /** The system-call sender/originator `0xfffffffffffffffffffffffffffffffffffffffe` (go-ethereum
    * `params.SystemAddress`, `params/protocol_params.go:247`; besu `SystemCallProcessor.SYSTEM_ADDRESS`). Every
    * pre/post-execution system call is sent from this address.
    */
  val SystemAddress: Address =
    Address(ByteString(Array.fill[Byte](19)(0xff.toByte) ++ Array(0xfe.toByte)))

  /** The fixed system-call gas budget `30_000_000` (go-ethereum `systemCallGasBudget`, `state_processor.go:295`; besu
    * `SystemCallProcessor.SYSTEM_CALL_GAS_LIMIT`). Independent of the block gas limit and **not** metered against it
    * (EIP-2935 §block-processing).
    */
  val SystemCallGasLimit: BigInt = 30_000_000
