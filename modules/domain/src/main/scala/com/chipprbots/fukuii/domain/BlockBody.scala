package com.chipprbots.fukuii.domain

import com.chipprbots.fukuii.rlp.PrefixedRLPEncodable
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPEncodeable
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.RLPValue
import com.chipprbots.fukuii.rlp.encode as rlpEncode

/** A block body — the transactions, ommer (uncle) headers, and the ETH-family trailing-optional withdrawals list.
  *
  * RLP layout is byte-exact to go-ethereum's `Body`/`extblock` (`core/types/block.go:183-187,224-229`): a list
  * `[transactions, uncles, withdrawals?]` where `withdrawals` is `rlp:"optional"` — **omitted** for any ETC body and
  * pre-Shanghai ETH, **present** post-Shanghai ETH. It takes the same contiguous-suffix omission contract as
  * [[BlockHeader]]'s tail (**not** a naive `derives`, RX-L1-05), and the decode is length-driven so a future body field
  * appended after `withdrawals` is tolerated, not a crash.
  *
  * **Typed transactions nest as RLP byte strings, not raw prefixed blobs.** A legacy tx is a bare RLP list (a valid
  * list item); an EIP-2718 typed tx is `typeByte ‖ RLP(payload)` wrapped in an RLP **string** — mirroring go-ethereum,
  * where `[]*Transaction`'s `EncodeRLP` writes a typed tx via `rlp.Encode(w, buf.Bytes())`. Emitting the raw `typeByte
  * ‖ RLP` unwrapped inside a list would mis-parse (the type byte `< 0x80` self-encodes as a separate single-byte item),
  * so the wrapping is consensus-load-bearing. See [[Transaction.decode(bytes:Array*]] for the first-byte dispatch this
  * decode delegates to.
  */
final case class BlockBody(
    transactionList: List[Transaction],
    uncleNodesList: List[BlockHeader],
    withdrawals: Option[List[Withdrawal]] = None
)

object BlockBody:

  val empty: BlockBody = BlockBody(Nil, Nil, None)

  /** Encode one transaction as a block-body list item: a legacy tx keeps its bare list; a typed tx is wrapped as an RLP
    * byte string over its `typeByte ‖ RLP(payload)` binary form (go-ethereum `[]*Transaction` `EncodeRLP`).
    */
  private[domain] def encodeTx(tx: Transaction): RLPEncodeable =
    summon[RLPCodec[Transaction]].encode(tx) match
      case list: RLPList                  => list
      case prefixed: PrefixedRLPEncodable => RLPValue(rlpEncode(prefixed))
      case other                          => other

  /** Decode one block-body list item back to a transaction: a bare list is a legacy tx; a byte string is a typed tx's
    * `typeByte ‖ RLP(payload)` binary form, routed through the EIP-2718 first-byte dispatch.
    */
  private[domain] def decodeTx(item: RLPEncodeable): Transaction = item match
    case list: RLPList   => summon[RLPCodec[Transaction]].decode(list)
    case RLPValue(bytes) => Transaction.decode(bytes)
    case _               => throw RLPException("Cannot decode a block-body transaction item", item)

  /** The body's own RLP fields `[transactions, uncles, withdrawals?]` — reused verbatim by [[Block]]'s flat `extblock`
    * encoding (`header +: bodyFields`), so the two never drift.
    */
  private[domain] def bodyFields(body: BlockBody): List[RLPEncodeable] =
    val txs = RLPList(body.transactionList.map(encodeTx)*)
    val uncles = summon[RLPCodec[List[BlockHeader]]].encode(body.uncleNodesList)
    body.withdrawals match
      case None     => List(txs, uncles)
      case Some(ws) => List(txs, uncles, summon[RLPCodec[List[Withdrawal]]].encode(ws))

  private[domain] def decodeTxs(item: RLPEncodeable): List[Transaction] = item match
    case list: RLPList => list.items.map(decodeTx).toList
    case _             => throw RLPException("Cannot decode block-body transactions: expected an RLPList", item)

  private[domain] def decodeUncles(item: RLPEncodeable): List[BlockHeader] =
    summon[RLPCodec[List[BlockHeader]]].decode(item)

  given RLPCodec[BlockBody] = new RLPCodec[BlockBody]:
    def encode(body: BlockBody): RLPEncodeable = RLPList(bodyFields(body)*)

    def decode(rlp: RLPEncodeable): BlockBody = rlp match
      case list: RLPList =>
        val items = list.items
        if items.length < 2 then
          throw RLPException(
            s"Cannot decode BlockBody: expected at least [transactions, uncles], got ${items.length}",
            rlp
          )
        // Length-driven: a third item is the trailing-optional withdrawals; anything past it is a future body
        // field this build does not model — tolerated, not a crash (same open-suffix discipline as the header).
        val withdrawals =
          if items.length > 2 then Some(summon[RLPCodec[List[Withdrawal]]].decode(items(2))) else None
        BlockBody(decodeTxs(items(0)), decodeUncles(items(1)), withdrawals)
      case _ => throw RLPException("Cannot decode BlockBody: expected an RLPList", rlp)
