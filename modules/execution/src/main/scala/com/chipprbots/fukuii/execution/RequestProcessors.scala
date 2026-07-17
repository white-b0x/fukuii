package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.crypto.sha256
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.domain.Log
import com.chipprbots.fukuii.evm.EvmConfig

/** The EIP-7685 request families, by their type-byte identity (besu `datatypes/RequestType.java`: `DEPOSIT(0x00)`,
  * `WITHDRAWAL(0x01)`, `CONSOLIDATION(0x02)`; go-ethereum `state_processor.go` `requestType` bytes
  * `0x00`/`0x01`/`0x02`). These are the post-Prague ETH request phases (EIP-6110 deposit log-scrape, EIP-7002
  * withdrawal-queue, EIP-7251 consolidation-queue); the ETC/PoW path emits **none** of them.
  *
  * The [[typeByte]] is the EIP-7685 request-type prefix — the first byte of the [[Request.encoded]] form and the sort
  * key the coordinator iterates by (besu `ImmutableSortedMap` natural order = `0x00 < 0x01 < 0x02`).
  */
enum RequestType(val typeByte: Byte):
  case Deposit extends RequestType(0x00)
  case Withdrawal extends RequestType(0x01)
  case Consolidation extends RequestType(0x02)

/** A single EIP-7685 request — the `requestType ‖ data` pair besu models as `core/Request { RequestType type, Bytes
  * data }`. A deposit request's data is the concatenation of the per-log 192-byte flat layouts (EIP-6110); a 7002/7251
  * request's data is the queue contract's system-call return.
  *
  * **[[encoded]] is the byte form fed to the `requestsHash` fold** — `typeByte ‖ data` (besu
  * `Request.getEncodedRequest` \= `Bytes.concatenate(type, data)`; go-ethereum builds the same `requestType` -prefixed
  * byte slice in `processRequestsSystemCall:420` / `ParseDepositLogs`). An [[isEmpty]] request (empty data) is
  * **excluded** from the hash (besu `BodyValidation:82`; go-ethereum `CalcRequestsHash` skips `len(item) > 1`).
  */
final case class Request(requestType: RequestType, data: ByteString):

  /** The EIP-7685 encoded request `typeByte ‖ data` (besu `Request.getEncodedRequest`). */
  def encoded: ByteString = ByteString(requestType.typeByte) ++ data

  /** Whether this request carries no data — excluded from the [[RequestsHash]] fold. */
  def isEmpty: Boolean = data.isEmpty

/** Everything a [[RequestProcessor]] needs to produce its request for one block: the block's [[logs]] (all logs across
  * all receipts, for the EIP-6110 deposit-scrape) and the system-call inputs ([[header]], [[evmConfig]], [[world]],
  * [[chainId]]) for the EIP-7002/7251 queue calls. The [[world]] is threaded — a system-call request mutates the queue
  * contract's storage, so each processor receives the world left by the previous one.
  */
final case class RequestContext(
    header: BlockHeader,
    evmConfig: EvmConfig,
    world: InMemoryWorldState,
    chainId: ChainId,
    logs: List[Log]
)

/** The result of one [[RequestProcessor]] — the (possibly mutated) world and the request it produced. The deposit
  * processor returns the world unchanged; a system-call processor returns the world after the queue contract's dequeue.
  */
final case class RequestOutcome(world: InMemoryWorldState, request: Request)

/** Why the EIP-7685 request phase could not complete — a **fail-LOUD** result (never a silent skip), mirroring
  * go-ethereum's `PostExecution` returning an error and besu throwing from the request processors.
  */
enum RequestError:

  /** A EIP-7002/7251 queue system call did not run to completion (a codeless target or a VM halt) — go-ethereum
    * `processRequestsSystemCall` returns the wrapped `err`, besu's `SystemCallProcessor` throws.
    */
  case SystemCall(requestType: RequestType, error: SystemCallError)

  /** An EIP-6110 deposit log's data was not the canonical 576-byte ABI layout — go-ethereum `DepositLogToRequest`
    * returns `deposit wrong length`, besu `DepositLogDecoder` throws `InvalidDepositLogLayoutException`. Both abort the
    * block.
    */
  case InvalidDepositLog(reason: String)

/** Produces the EIP-7685 requests of one [[RequestType]] for a block. **P5b fills the concrete processors** — the
  * EIP-6110 [[DepositRequestProcessor]] (log-scrape from receipts) and the EIP-7002/7251 [[SystemCallRequestProcessor]]
  * (`SystemAddress` 30M-gas queue calls). besu's structural mirror is `requests/RequestProcessor` +
  * `SystemCallRequestProcessor` / `DepositRequestProcessor` (L4 plan §4, RX-L4-12/14).
  */
trait RequestProcessor:

  /** The request family this processor emits (its type-byte identity). */
  def requestType: RequestType

  /** Produce this family's [[Request]] for the block described by `context`, threading the world. Fails LOUD
    * ([[RequestError]]) on a codeless queue target, a VM halt, or a malformed deposit log — never a silent skip.
    */
  def process(context: RequestContext): Either[RequestError, RequestOutcome]

/** A per-fork wrapper over besu's data-driven `RequestType → RequestProcessor` map
  * (`requests/RequestProcessorCoordinator.java`) — a data-driven map rather than a hard-coded
  * `processPragueSystemCalls` loop (L4 plan §6 row 5).
  *
  * **Fail-loud construction (besu `RequestProcessorCoordinator.build():66-73` throws on an accidental empty map).** An
  * empty processor map is reachable **only** via the explicit [[RequestProcessors.noOp]] factory — the named degraded
  * path for PoW / pre-Prague. [[RequestProcessors.build]] `sys.error`s on an empty map, so a degraded coordinator can
  * never arise by accident (consistent with fail-LOUD, L4 plan §5/§6 row 6). On the ETC path the bundle binds `noOp`
  * (withdrawals/requests hard-rejected, §9).
  */
final class RequestProcessors private (val processors: Map[RequestType, RequestProcessor]):

  /** The processor for a request family, if this coordinator carries one (`noOp` carries none). */
  def processorFor(requestType: RequestType): Option[RequestProcessor] =
    processors.get(requestType)

  /** Whether this is the degraded (empty) coordinator — the PoW / pre-Prague path. */
  def isNoOp: Boolean =
    processors.isEmpty

  /** Run every processor **in ascending `RequestType` order** (besu `RequestProcessorCoordinator.process` streams an
    * `ImmutableSortedMap` — the request order the `requestsHash` fold depends on), threading the world through each and
    * collecting the requests in that order. `noOp` yields `(world, Nil)`. Short-circuits on the first [[RequestError]].
    *
    * go-ethereum runs the same fixed sequence in `PostExecution`: EIP-6110 deposits (`0x00`), then EIP-7002 withdrawals
    * (`0x01`), then EIP-7251 consolidations (`0x02`).
    */
  def process(context: RequestContext): Either[RequestError, (InMemoryWorldState, List[Request])] =
    val ordered = processors.toList.sortBy((requestType, _) => requestType.typeByte & 0xff).map(_._2)
    ordered.foldLeft[Either[RequestError, (InMemoryWorldState, List[Request])]](Right((context.world, Nil))) {
      case (left @ Left(_), _) => left
      case (Right((world, acc)), processor) =>
        processor.process(context.copy(world = world)).map(outcome => (outcome.world, acc :+ outcome.request))
    }

object RequestProcessors:

  /** The **only** empty-map path — the named degraded coordinator for PoW / pre-Prague (besu
    * `RequestProcessorCoordinator.noOp():52-54`).
    */
  val noOp: RequestProcessors =
    new RequestProcessors(Map.empty)

  /** Build a coordinator from a non-empty processor map; **fails LOUD on an empty map** (besu `build():66-73`), forcing
    * the degraded path to be the explicit [[noOp]] factory rather than a silent empty `Map()`.
    */
  def build(processors: Map[RequestType, RequestProcessor]): RequestProcessors =
    if processors.isEmpty then
      sys.error(
        "RequestProcessors.build: empty processor map — use RequestProcessors.noOp for the degraded " +
          "(PoW / pre-Prague) path (besu RequestProcessorCoordinator.build:66-73)"
      )
    else new RequestProcessors(processors)

  /** The **Prague+ ETH** coordinator — the `{Deposit, Withdrawal, Consolidation}` map besu wires in
    * `MainnetRequestsProcessor.pragueRequestsProcessors`. The deposit contract address is a **network parameter**
    * (mainnet `0x0000…7705fa`, Sepolia `0x7f02…295d`; go-ethereum `config.DepositContractAddress`); the 7002/7251 queue
    * addresses are protocol constants ([[SystemCallRequestProcessor.WithdrawalQueueAddress]] /
    * [[SystemCallRequestProcessor.ConsolidationQueueAddress]]).
    */
  def prague(
      systemCall: SystemCallProcessor,
      depositContractAddress: com.chipprbots.fukuii.bytes.Address
  ): RequestProcessors =
    build(
      Map(
        RequestType.Deposit -> DepositRequestProcessor(depositContractAddress),
        RequestType.Withdrawal -> SystemCallRequestProcessor(
          systemCall,
          SystemCallRequestProcessor.WithdrawalQueueAddress,
          RequestType.Withdrawal
        ),
        RequestType.Consolidation -> SystemCallRequestProcessor(
          systemCall,
          SystemCallRequestProcessor.ConsolidationQueueAddress,
          RequestType.Consolidation
        )
      )
    )

/** The EIP-7685 `requestsHash` fold — the header commitment over a block's requests. **Both reference clients agree
  * byte-for-byte** (besu `BodyValidation.java:82`, go-ethereum `core/types/block.go:480` `CalcRequestsHash`):
  *
  *   - inner `sha256` per **non-empty** request over its [[Request.encoded]] form (`typeByte ‖ data`);
  *   - outer `sha256` over the **concatenation** of those inner hashes;
  *   - **empty requests are excluded** from the fold (go-ethereum `if len(item) > 1`; besu `if !data.isEmpty()`);
  *   - the empty set yields [[Empty]] = `sha256("")` (go-ethereum `EmptyRequestsHash`, `core/types/hashes.go:43`).
  *
  * `requestsHash = sha256( sha256(req_0.encoded) ‖ sha256(req_1.encoded) ‖ … )`.
  */
object RequestsHash:

  /** `sha256("")` — the known empty-request-set commitment (go-ethereum `EmptyRequestsHash`, `core/types/hashes.go:44`
    * \= `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`).
    */
  val Empty: ByteString = sha256(ByteString.empty)

  /** Fold `requests` into the EIP-7685 `requestsHash` (empty requests excluded; the empty set → [[Empty]]). */
  def compute(requests: List[Request]): ByteString =
    val innerHashes = requests.filterNot(_.isEmpty).map(request => sha256(request.encoded))
    sha256(innerHashes.foldLeft(ByteString.empty)(_ ++ _))
