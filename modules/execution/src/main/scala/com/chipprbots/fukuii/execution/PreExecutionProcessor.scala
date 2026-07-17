package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.evm.EvmConfig

/** The **pre-execution phase** — the system calls that run **before** the tx loop, as a per-fork [[ProtocolSpec]]
  * bundle collaborator (go-ethereum `core/state_processor.go` `PreExecution:144-167`; besu
  * `AbstractBlockProcessor.java:265` `protocolSpec.getPreExecutionProcessor().process(...)`). The ETH path runs
  * EIP-4788 (beacon-block-root) and EIP-2935 (parent-block-hash population); the ETC/PoW and pre-Cancun path is
  * [[NoPreExecution]] — **absent, not `if(isETC)`** (the bundle binds the fork-resolved collaborator once; L4 plan §5,
  * RX-L4-11).
  *
  * A failure short-circuits the block ([[BlockExecutionError.SystemCallFailed]], fail-LOUD).
  */
sealed trait PreExecutionProcessor:

  /** Run this fork's pre-execution system calls, threading `world`. Returns the mutated world, or a fail-LOUD error if
    * a system call did not run to completion.
    */
  def process(
      header: BlockHeader,
      evmConfig: EvmConfig,
      world: InMemoryWorldState,
      chainId: ChainId
  ): Either[BlockExecutionError, InMemoryWorldState]

object PreExecutionProcessor:

  /** The ETC/PoW and pre-Cancun path — **no** pre-execution system calls (go-ethereum runs neither 4788 nor 2935 before
    * Cancun/Prague; besu's `getPreExecutionProcessor()` is a no-op there). Returns `world` untouched.
    */
  case object NoPreExecution extends PreExecutionProcessor:
    def process(
        header: BlockHeader,
        evmConfig: EvmConfig,
        world: InMemoryWorldState,
        chainId: ChainId
    ): Either[BlockExecutionError, InMemoryWorldState] =
      Right(world)

  /** The ETH post-merge path — EIP-4788 then EIP-2935, each a [[SystemCallProcessor]] call:
    *
    *   - **EIP-4788** (Cancun+): call the beacon-roots contract ([[BeaconRootsAddress]]) with
    *     `header.parentBeaconBlockRoot` (the 32-byte CL root). Gated exactly as go-ethereum — run iff the header
    *     carries a beacon root (`beaconRoot != nil`, `state_processor.go:160-162`); a Cancun+ header always does, so
    *     this is the self-describing Cancun gate.
    *   - **EIP-2935** (Prague+): call the history-storage contract ([[HistoryStorageAddress]]) with `header.parentHash`
    *     to store it in the ring buffer (go-ethereum `ProcessParentBlockHash`, gated `config.IsPrague`,
    *     `state_processor.go:164-166`). L3's `BLOCKHASH` opcode *reads* these slots; this is the *write* (population).
    *     Gated on [[historyStorageActive]] — the fork-resolved flag L5 sets from the schedule (Prague+), so the produce
    *     path (where `header.requestsHash` is not yet filled) still gates correctly.
    *
    * @param historyStorageActive
    *   whether EIP-2935 parent-block-hash population is active (Prague+), resolved once into the bundle.
    */
  final case class EthPreExecution(systemCall: SystemCallProcessor, historyStorageActive: Boolean)
      extends PreExecutionProcessor:

    def process(
        header: BlockHeader,
        evmConfig: EvmConfig,
        world: InMemoryWorldState,
        chainId: ChainId
    ): Either[BlockExecutionError, InMemoryWorldState] =
      for
        afterBeacon <- header.parentBeaconBlockRoot match
          case Some(root) =>
            systemCall
              .process(BeaconRootsAddress, root.bytes, header, evmConfig, world, chainId)
              .map(_.world)
              .left
              .map(BlockExecutionError.SystemCallFailed("EIP-4788 beacon-root", _))
          case None => Right(world)
        afterHistory <-
          if historyStorageActive then
            systemCall
              .process(HistoryStorageAddress, header.parentHash.bytes, header, evmConfig, afterBeacon, chainId)
              .map(_.world)
              .left
              .map(BlockExecutionError.SystemCallFailed("EIP-2935 block-hash", _))
          else Right(afterBeacon)
      yield afterHistory

  /** EIP-4788 beacon-roots contract `0x000F3df6D732807Ef1319fB7B8bB8522d0Beac02` (go-ethereum
    * `params.BeaconRootsAddress`, `params/protocol_params.go:250`).
    */
  val BeaconRootsAddress: Address =
    Address(Hex.decode("0x000F3df6D732807Ef1319fB7B8bB8522d0Beac02"))

  /** EIP-2935 history-storage contract `0x0000F90827F1C53a10cb7A02335B175320002935` (go-ethereum
    * `params.HistoryStorageAddress`, `params/protocol_params.go:254`).
    */
  val HistoryStorageAddress: Address =
    Address(Hex.decode("0x0000F90827F1C53a10cb7A02335B175320002935"))
