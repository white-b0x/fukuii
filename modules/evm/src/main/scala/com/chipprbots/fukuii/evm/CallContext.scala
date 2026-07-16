package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.ChainId

/** Input parameters to a program executed on the EVM. Apart from the code itself it holds all (interfaces to) the data
  * accessible from the EVM. Execution constants, see section 9.3 in the Yellow Paper for more detail.
  *
  * **P1 deferrals.** The smart companion `apply(signedTx, blockHeader, senderAddress, world, evmConfig)` — which folds
  * intrinsic gas via `EvmConfig.calcTransactionIntrinsicGas`, reads the effective (EIP-1559) gas price, and pattern-
  * matches the `enum Transaction` variants for the access list / blob hashes / authorization list — is **deferred to
  * P2**, where those `EvmConfig` methods and the tx accessors are wired. The `tracer: Option[ExecutionTracer]` field is
  * likewise deferred with the P2 tracer plumbing. Gas counters are `BigInt`; storage-slot keys are the EVM word
  * [[UInt256]] (AS-IS `domain.StorageKey`).
  *
  * @param recipientAddr
  *   recipient of the call, empty if contract creation
  * @param endowment
  *   value that appears to be transferred between accounts
  * @param doTransfer
  *   false for CALLCODE/DELEGATECALL/STATICCALL, true otherwise
  * @param startGas
  *   initial gas for the execution
  * @param world
  *   provides interactions with world state
  * @param initialAddressesToDelete
  *   initial set of addresses to delete (from lower-depth calls)
  * @param staticCtx
  *   a flag to indicate static context (EIP-214)
  * @param originalWorld
  *   state of the world at the beginning of the current transaction, read-only (EIP-1283)
  */
final case class CallContext[W <: WorldState[W, S], S <: AccountStorage[S]](
    callerAddr: Address,
    originAddr: Address,
    recipientAddr: Option[Address],
    gasPrice: UInt256,
    startGas: BigInt,
    inputData: ByteString,
    value: UInt256,
    endowment: UInt256,
    doTransfer: Boolean,
    blockHeader: BlockHeader,
    callDepth: Int,
    world: W,
    initialAddressesToDelete: Set[Address],
    evmConfig: EvmConfig,
    chainId: ChainId,
    staticCtx: Boolean = false,
    originalWorld: W,
    warmAddresses: Set[Address],
    warmStorage: Set[(Address, UInt256)],
    createdAddresses: Set[Address] = Set.empty,
    transientStorage: Map[(Address, UInt256), BigInt] = Map.empty,
    precompileRelocations: Map[Address, Address] = Map.empty,
    blobVersionedHashes: Seq[ByteString] = Seq.empty,
    blobBaseFee: UInt256 = UInt256.Zero,
    prevRandao: Option[UInt256] = None,
    traceTransfers: Boolean = false
)
