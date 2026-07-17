package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.domain.Log

/** EIP-6110 — the deposit-request processor. Unlike the EIP-7002/7251 queue processors it makes **no system call**: it
  * scrapes the block's receipts for deposit-event logs on the network's deposit contract and folds each log's ABI
  * payload into the flat request layout (go-ethereum `core/state_processor.go` `ParseDepositLogs:426-445`; besu
  * `requests/DepositRequestProcessor` + `encoding/DepositLogDecoder`).
  *
  * A matching log is one emitted **by** [[depositContractAddress]] whose first topic is the [[DepositEventTopic]]. Each
  * matching log's 576-byte ABI data is decoded to its 192-byte flat form ([[DepositLogDecoder]]) and the flat forms are
  * **concatenated** into a single deposit request's data — go-ethereum accumulates into one `deposits` byte slice, besu
  * `.reduce(Bytes::concatenate)`. The resulting [[Request]]'s [[Request.encoded]] form is `0x00 ‖ deposit_0 ‖ deposit_1
  * ‖ …`; when no deposit log matches, the request has empty data (excluded from the `requestsHash` fold — go-ethereum
  * only appends when `len(deposits) > 1`, besu returns `Bytes.EMPTY`; the fold-level exclusion reconciles the two).
  *
  * The deposit contract address is a **network parameter** (a genesis/config value — mainnet vs Sepolia differ), so it
  * is injected, not a protocol constant.
  */
final class DepositRequestProcessor(depositContractAddress: Address) extends RequestProcessor:

  def requestType: RequestType = RequestType.Deposit

  def process(context: RequestContext): Either[RequestError, RequestOutcome] =
    val matchingLogs = context.logs.filter(isDepositLog)
    val decoded: Either[RequestError, ByteString] =
      matchingLogs.foldLeft[Either[RequestError, ByteString]](Right(ByteString.empty)) {
        case (left @ Left(_), _) => left
        case (Right(acc), log)   => DepositLogDecoder.decode(log.data).map(acc ++ _)
      }
    // The deposit-scrape never mutates the world (it reads receipts) — the world is threaded through unchanged.
    decoded.map(data => RequestOutcome(context.world, Request(RequestType.Deposit, data)))

  /** A deposit event: emitted by the deposit contract, with the [[DepositEventTopic]] as its first topic (go-ethereum
    * `log.Address == config.DepositContractAddress && len(log.Topics) > 0 && log.Topics[0] == depositTopic`; besu
    * `isDepositEvent`).
    */
  private def isDepositLog(log: Log): Boolean =
    log.address == depositContractAddress && log.topics.headOption.contains(DepositRequestProcessor.DepositEventTopic)

object DepositRequestProcessor:

  /** The EIP-6110 deposit-event topic `0x649bbc62d0e31342afea4e5cd82d4049e7e1ee912fc0889aa790803be39038c5` (go-ethereum
    * `state_processor.go` `depositTopic`; besu `DepositRequestProcessor.DEPOSIT_EVENT_TOPIC`).
    */
  val DepositEventTopic: Hash =
    Hash(Hex.decode("0x649bbc62d0e31342afea4e5cd82d4049e7e1ee912fc0889aa790803be39038c5"))

/** Decodes one EIP-6110 deposit log's 576-byte ABI payload into the flat 192-byte request layout, byte-identical to
  * go-ethereum `core/types/deposit.go` `DepositLogToRequest:28` and besu `encoding/DepositLogDecoder.decodeFromLog`.
  *
  * The log data is the ABI encoding of five dynamic `bytes` fields, laid out as five 32-byte position words, then for
  * each field a 32-byte length word followed by the padded value. The flat request drops the position/length words and
  * concatenates only the values: `pubkey(48) ‖ withdrawalCredentials(32) ‖ amount(8) ‖ signature(96) ‖ index(8)` =
  * **192 bytes**. The field values sit at the fixed offsets both clients hard-code (each is `32 + fieldOffset` into the
  * data, skipping the leading length word).
  */
object DepositLogDecoder:

  /** The canonical deposit-log ABI payload length (go-ethereum `if len(data) != 576`; besu `DEPOSIT_LOG_LENGTH`). */
  val DepositLogLength: Int = 576

  private val FlatLength: Int = 192

  // Field (value) offsets into the data, each read at `LengthWord + offset` (go-ethereum's running `b` pointer; besu's
  // *_OFFSET constants). value = (start, length).
  private val LengthWord: Int = 32
  private val PubKey: (Int, Int) = (160, 48)
  private val WithdrawalCredentials: (Int, Int) = (256, 32)
  private val Amount: (Int, Int) = (320, 8)
  private val Signature: (Int, Int) = (384, 96)
  private val Index: (Int, Int) = (512, 8)

  /** Decode `data` (the log's ABI payload) into the flat 192-byte deposit request, or fail LOUD
    * ([[RequestError.InvalidDepositLog]]) if it is not the canonical 576-byte layout.
    */
  def decode(data: ByteString): Either[RequestError, ByteString] =
    if data.length != DepositLogLength then
      Left(
        RequestError.InvalidDepositLog(
          s"deposit log wrong length: want $DepositLogLength, have ${data.length} (go-ethereum deposit.go:29)"
        )
      )
    else
      val flat = field(data, PubKey) ++ field(data, WithdrawalCredentials) ++
        field(data, Amount) ++ field(data, Signature) ++ field(data, Index)
      // The five fixed-length values sum to exactly 192 bytes by construction.
      assert(flat.length == FlatLength, s"deposit flat layout is ${flat.length} bytes, expected $FlatLength")
      Right(flat)

  private def field(data: ByteString, valueSpec: (Int, Int)): ByteString =
    val (offset, length) = valueSpec
    data.slice(LengthWord + offset, LengthWord + offset + length)
