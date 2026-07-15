package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.rlp.PrefixedRLPEncodable
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPEncodeable
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.RLPValue
import com.chipprbots.fukuii.rlp.rawDecodeStrict

/** The first `receiptRLP` element — the **mutually-exclusive** union go-ethereum encodes as `PostStateOrStatus []byte`
  * (`core/types/receipt.go:92`): a **pre-fork** receipt carries a 32-byte **post-state root**; a post-fork receipt
  * carries a **1-byte status** (success `0x01` / failure empty-string). The fork that flips a network from the root
  * form to the status form is **per-network** — ETH at Byzantium (EIP-658), ETC at Atlantis (its Byzantium-equivalent)
  * — an L4/L5 decision; L1 models **both** encodings.
  */
enum ReceiptStatus:

  /** Pre-Byzantium/pre-Atlantis: the 32-byte intermediate state root after the transaction (geth `PostState`). */
  case PostStateRoot(root: Hash)

  /** Byzantium+/Atlantis+: a 1-byte status — `true` ⇒ `0x01` (success), `false` ⇒ the RLP empty string (failure) (geth
    * `receiptStatusSuccessfulRLP = {0x01}` / `receiptStatusFailedRLP = {}`, `receipt.go:38-39`).
    */
  case Status(succeeded: Boolean)

object ReceiptStatus:

  private val SuccessfulRLP: Array[Byte] = Array[Byte](0x01)
  private val FailedRLP: Array[Byte] = Array.emptyByteArray

  /** Encode the union exactly as geth's `statusEncoding()` (`receipt.go:240-249`): a post-state root is its raw 32
    * bytes; a status is `{0x01}` (success) or `{}` (failure).
    */
  private[domain] def encode(status: ReceiptStatus): RLPEncodeable = status match
    case PostStateRoot(root) => RLPValue(root.toArray)
    case Status(true)        => RLPValue(SuccessfulRLP)
    case Status(false)       => RLPValue(FailedRLP)

  /** Decode the union exactly as geth's `setStatus()` (`receipt.go:226-238`): `{0x01}` ⇒ success, `{}` ⇒ failure, a
    * 32-byte string ⇒ post-state root; any other length is an invalid receipt status.
    */
  private[domain] def decode(rlp: RLPEncodeable): ReceiptStatus = rlp match
    case RLPValue(bytes) if bytes.sameElements(SuccessfulRLP) => Status(true)
    case RLPValue(bytes) if bytes.isEmpty                     => Status(false)
    case RLPValue(bytes) if bytes.length == Hash.Length       => PostStateRoot(Hash(ByteString(bytes)))
    case RLPValue(bytes) =>
      throw RLPException(s"Invalid receipt PostStateOrStatus: ${bytes.length} bytes", rlp)
    case _ => throw RLPException("Cannot decode receipt PostStateOrStatus: expected an RLPValue", rlp)

/** A transaction receipt — the consensus result of executing one transaction.
  *
  * The four consensus fields are go-ethereum's `receiptRLP` (`core/types/receipt.go:90-96`), in order:
  * `PostStateOrStatus` ([[status]]) → `CumulativeGasUsed` → `Bloom` → `Logs`. [[txType]] carries the EIP-2718 type for
  * typed receipts (`0x00` legacy, `0x01`–`0x04` typed), byte-exact to the transaction type ids.
  *
  * **This is the consensus `receiptRLP`, which includes the [[logsBloom]].** geth's `storedReceiptRLP`, which omits the
  * bloom to save disk, is an L2/db-storage concern and is **not** modelled here.
  *
  * **Legacy vs typed prefix.** A legacy receipt (`txType == 0x00`) is a bare RLP list `[status, gasUsed, bloom, logs]`;
  * a typed receipt is `typeByte ‖ RLP([status, gasUsed, bloom, logs])` (go-ethereum `EncodeIndex`/ `MarshalBinary`, the
  * raw `type ‖ RLP` form used for the receipts trie — **not** the extra outer RLP-string wrap `EncodeRLP` applies when
  * a receipt is embedded as a single item in a wider list, which is an L6 wire concern).
  */
final case class Receipt(
    status: ReceiptStatus,
    cumulativeGasUsed: Long,
    logsBloom: Bloom,
    logs: List[Log],
    txType: Byte = 0x00
)

object Receipt:

  /** The four-element consensus body `[PostStateOrStatus, CumulativeGasUsed, Bloom, Logs]`, shared by the legacy and
    * typed encodings (only the leading type byte differs).
    */
  private def body(r: Receipt): RLPList =
    RLPList(
      ReceiptStatus.encode(r.status),
      summon[RLPCodec[Long]].encode(r.cumulativeGasUsed),
      summon[RLPCodec[Bloom]].encode(r.logsBloom),
      summon[RLPCodec[List[Log]]].encode(r.logs)
    )

  private def fromBody(txType: Byte, rlp: RLPEncodeable): Receipt = rlp match
    case RLPList(status, gasUsed, bloom, logs) =>
      Receipt(
        status = ReceiptStatus.decode(status),
        cumulativeGasUsed = summon[RLPCodec[Long]].decode(gasUsed),
        logsBloom = summon[RLPCodec[Bloom]].decode(bloom),
        logs = summon[RLPCodec[List[Log]]].decode(logs),
        txType = txType
      )
    case list: RLPList =>
      throw RLPException(s"Cannot decode receipt body: expected 4 elements, got ${list.items.length}", rlp)
    case _ => throw RLPException("Cannot decode receipt body: expected an RLPList", rlp)

  /** The consensus receipt codec: legacy ⇒ the bare body list; typed ⇒ `typeByte ‖ RLP(body)` via
    * [[PrefixedRLPEncodable]]. Decode from the AST only sees the **legacy** shape (a typed receipt's leading type byte
    * is consumed before AST parsing — use [[decode(bytes:Array*]] for the EIP-2718 first-byte dispatch).
    */
  given RLPCodec[Receipt] = new RLPCodec[Receipt]:
    def encode(r: Receipt): RLPEncodeable =
      if r.txType == (0x00: Byte) then body(r)
      else PrefixedRLPEncodable(r.txType, body(r))

    def decode(rlp: RLPEncodeable): Receipt = rlp match
      case list: RLPList => fromBody(0x00, list)
      case _ =>
        throw RLPException(
          "Cannot decode a typed receipt from a bare RLPEncodeable — use Receipt.decode(bytes: Array[Byte])",
          rlp
        )

  /** The EIP-2718 first-byte dispatch decoder for a standalone (binary) receipt — the mirror of
    * [[Transaction.decode(bytes:Array*]]. A legacy receipt is a bare RLP list (header byte `>= 0xc0`); a typed receipt
    * is `typeByte ‖ RLP(body)`, `typeByte` in `{0x01, 0x02, 0x03, 0x04}`. Any other leading byte (the `0x05..0xbf` gap)
    * is **rejected**, never silently treated as legacy.
    */
  def decode(bytes: Array[Byte]): Receipt =
    if bytes.isEmpty then throw RLPException("Cannot decode an empty receipt")
    val first = bytes(0) & 0xff
    if first >= 0xc0 then fromBody(0x00, rawDecodeStrict(bytes))
    else
      first match
        case 0x01 | 0x02 | 0x03 | 0x04 => fromBody(first.toByte, rawDecodeStrict(bytes.tail))
        case other =>
          throw RLPException(
            f"Unrecognized receipt type byte 0x$other%02x — not a legacy list header (>= 0xc0) and not a known " +
              "EIP-2718 type (0x01-0x04)"
          )
