package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.ChainId

/** Execution environment constants of an EVM program. See section 9.3 in the Yellow Paper for more detail.
  *
  * **P1 deferrals.** Two AS-IS members need P2 types and are deferred: (1) the `tracer: Option[ExecutionTracer]` field
  * — `ExecutionTracer`'s hooks are typed over the P2 `OpCode` ADT and return json4s, so the tracer plumbing lands with
  * P2; (2) the companion `apply(context, code, ownerAddr)` copy factory, which reads a [[ProgramContext]] whose smart
  * constructor is itself P2. `precompileRelocations` is carried per the AS-IS shape but is a live **OPEN** (§6 /
  * RX-L4-23): its only consumer is L4 simulation and it must not survive as a dormant mutable-global-backed remap —
  * L4's immutable `SimulationOptions` decides whether it is threaded here or removed.
  *
  * @param ownerAddr
  *   I_a: address of the account that owns the code
  * @param callerAddr
  *   I_s: address of the account which caused the code to be executing
  * @param originAddr
  *   I_o: sender address of the transaction that originated this execution
  * @param gasPrice
  *   I_p
  * @param inputData
  *   I_d
  * @param value
  *   I_v
  * @param program
  *   I_b
  * @param blockHeader
  *   I_H
  * @param callDepth
  *   I_e
  * @param startGas
  *   gas provided for execution
  * @param evmConfig
  *   EVM configuration (forks)
  * @param chainId
  *   I_chainId — the EIP-155 chain id the `CHAINID` opcode reads. Threaded as an L1 [[ChainId]] value on the per-call
  *   environment (geth `opChainID` reads `evm.chainConfig.ChainID`), **not** carried on the fork-resolved [[EvmConfig]]
  *   — chain identity is per-instance runtime config, never a property of the fork schedule (R2).
  * @param blobBaseFee
  *   the block's **precomputed** blob base fee that `BLOBBASEFEE` (EIP-7516) pushes (geth `opBlobBaseFee` reads
  *   `evm.Context.BlobBaseFee`). Computed by the caller (L4) so the EVM stays free of the blob-schedule timestamp
  *   lookups; `Zero` pre-blob.
  * @param prevRandao
  *   the block's `prevRandao` (EIP-4399) that `DIFFICULTY`/`PREVRANDAO` (0x44) returns post-Merge (geth `opRandom`
  *   reads `evm.Context.Random`); `None` pre-Merge, where 0x44 returns the header difficulty instead.
  */
final case class ExecEnv(
    ownerAddr: Address,
    callerAddr: Address,
    originAddr: Address,
    gasPrice: UInt256,
    inputData: ByteString,
    value: UInt256,
    program: Program,
    blockHeader: BlockHeader,
    callDepth: Int,
    startGas: BigInt,
    evmConfig: EvmConfig,
    chainId: ChainId,
    blobBaseFee: UInt256 = UInt256.Zero,
    prevRandao: Option[UInt256] = None,
    precompileRelocations: Map[Address, Address] = Map.empty,
    blobVersionedHashes: Seq[ByteString] = Seq.empty,
    traceTransfers: Boolean = false
)
