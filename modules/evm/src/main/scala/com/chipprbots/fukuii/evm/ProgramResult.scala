package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Log

/** Representation of the result of execution of a contract.
  *
  * Retyped to the built L0/L1 APIs: gas counters are `BigInt` (the AS-IS `GasAmount` value type was not carried into
  * the rebuild — the plan's gas model is `BigInt`); the log entry is L1 [[Log]] (AS-IS `TxLogEntry`); storage-slot keys
  * are the EVM word [[UInt256]] (AS-IS `domain.StorageKey`).
  *
  * @param returnData
  *   bytes returned by the executed contract (set by the `RETURN` opcode)
  * @param gasRemaining
  *   amount of gas remaining after execution
  * @param world
  *   represents changes to the world state
  * @param addressesToDelete
  *   list of addresses of accounts scheduled to be deleted
  * @param internalTxs
  *   list of internal transactions (for debugging/tracing) if enabled in config
  * @param error
  *   defined when the program terminated abnormally
  */
final case class ProgramResult[W <: WorldState[W, S], S <: AccountStorage[S]](
    returnData: ByteString,
    gasRemaining: BigInt,
    world: W,
    addressesToDelete: Set[Address],
    logs: Seq[Log],
    internalTxs: Seq[InternalTransaction],
    gasRefund: BigInt,
    error: Option[HaltReason],
    accessedAddresses: Set[Address],
    accessedStorageKeys: Set[(Address, UInt256)],
    transientStorage: Map[(Address, UInt256), BigInt] = Map.empty
)
