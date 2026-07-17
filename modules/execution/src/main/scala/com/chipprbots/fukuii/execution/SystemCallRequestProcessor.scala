package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hex

/** The reusable EIP-7002 / EIP-7251 request processor — "call a queue system contract, wrap its return as a
  * [[Request]]" (besu `requests/SystemCallRequestProcessor`; go-ethereum `core/state_processor.go`
  * `processRequestsSystemCall:385`).
  *
  * It drives the P5a [[SystemCallProcessor]] against [[contractAddress]] with **empty input** as the `SystemAddress`
  * sender on the fixed 30M budget (go-ethereum `msg.Data = nil`; besu `Bytes.EMPTY`), then prefixes the [[requestType]]
  * byte onto the contract's return — go-ethereum `requestsData[0] = requestType` (`:420`), besu `new
  * Request(requestType, systemCallOutput)`. The queue contract's dequeue mutates its own storage, so the world is
  * threaded through.
  *
  * A queue call that does not run to completion (a codeless target or a VM halt) fails LOUD
  * ([[RequestError.SystemCall]]) — go-ethereum returns the wrapped error, besu throws. An **empty** return is a valid
  * "no pending requests" outcome: the request is produced with empty data and excluded from the `requestsHash` fold
  * (go-ethereum `if len(ret) == 0 { return nil }` skips appending; besu produces an empty-data request the fold then
  * excludes — same commitment).
  */
final class SystemCallRequestProcessor(
    systemCall: SystemCallProcessor,
    contractAddress: Address,
    reqType: RequestType
) extends RequestProcessor:

  def requestType: RequestType = reqType

  def process(context: RequestContext): Either[RequestError, RequestOutcome] =
    systemCall
      .process(contractAddress, ByteString.empty, context.header, context.evmConfig, context.world, context.chainId)
      .left
      .map(RequestError.SystemCall(reqType, _))
      .map(outcome => RequestOutcome(outcome.world, Request(reqType, outcome.output)))

object SystemCallRequestProcessor:

  /** EIP-7002 withdrawal-queue contract `0x00000961Ef480Eb55e80D19ad83579A64c007002` (go-ethereum
    * `params.WithdrawalQueueAddress`, `params/protocol_params.go:258`; besu `withdrawalRequestContractAddress`).
    */
  val WithdrawalQueueAddress: Address =
    Address(Hex.decode("0x00000961Ef480Eb55e80D19ad83579A64c007002"))

  /** EIP-7251 consolidation-queue contract `0x0000BBdDc7CE488642fb579F8B00f3a590007251` (go-ethereum
    * `params.ConsolidationQueueAddress`, `params/protocol_params.go:262`; besu `consolidationRequestContractAddress`).
    */
  val ConsolidationQueueAddress: Address =
    Address(Hex.decode("0x0000BBdDc7CE488642fb579F8B00f3a590007251"))
