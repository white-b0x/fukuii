package com.chipprbots.fukuii.execution

/** The EIP-7685 request families, by their type-byte identity (besu `datatypes/RequestType.java`: `DEPOSIT(0x00)`,
  * `WITHDRAWAL(0x01)`, `CONSOLIDATION(0x02)`). These are the post-Prague ETH request phases (EIP-6110 deposit
  * log-scrape, EIP-7002 withdrawal-queue, EIP-7251 consolidation-queue); the ETC/PoW path emits **none** of them.
  */
enum RequestType:
  case Deposit
  case Withdrawal
  case Consolidation

/** Produces the EIP-7685 requests of one [[RequestType]] for a block. **P1 declares the trait only**; the concrete
  * processors — the EIP-6110 deposit log-scrape and the EIP-7002/7251 `SystemAddress` 30M-gas system calls — are **P5**
  * (beacon-gated). besu's structural mirror is `requests/RequestProcessor` + `SystemCallRequestProcessor` /
  * `DepositRequestProcessor` (L4 plan §4, RX-L4-12/14).
  */
trait RequestProcessor:

  /** The request family this processor emits (its type-byte identity). */
  def requestType: RequestType

/** A per-fork wrapper over besu's data-driven `RequestType → RequestProcessor` map
  * (`requests/RequestProcessorCoordinator.java`) — replaces the AS-IS hard-coded `processPragueSystemCalls` loop (L4
  * plan §6 row 5).
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
