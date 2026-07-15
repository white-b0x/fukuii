package com.chipprbots.fukuii.domain

import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPDecoder
import com.chipprbots.fukuii.rlp.RLPEncodeable
import com.chipprbots.fukuii.rlp.RLPEncoder
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPList

/** A full block — its [[BlockHeader]] and [[BlockBody]] together.
  *
  * The consensus RLP is go-ethereum's **flat** `extblock` (`core/types/block.go:224-229`): `[header, transactions,
  * uncles, withdrawals?]` — the header followed by the body's fields **inlined**, not a nested `[header, body]` pair.
  * The body fields (including typed-tx string-wrapping and the trailing-optional withdrawals) come verbatim from
  * [[BlockBody.bodyFields]], so the standalone-body and in-block encodings never drift.
  *
  * The **block hash is the header hash** ([[BlockHeader.hash]] = keccak256 of the header RLP), never a hash of this
  * whole structure — this codec is the wire/storage block encoding, not the hash preimage.
  */
final case class Block(header: BlockHeader, body: BlockBody):
  /** The block hash — the header's keccak256(RLP) (go-ethereum `Block.Hash()` delegates to `Header.Hash()`). */
  def hash: Hash = header.hash

object Block:

  given RLPCodec[Block] = new RLPCodec[Block]:
    def encode(b: Block): RLPEncodeable =
      val header = RLPEncoder.encode(b.header)
      RLPList((header +: BlockBody.bodyFields(b.body))*)

    def decode(rlp: RLPEncodeable): Block = rlp match
      case list: RLPList =>
        val items = list.items
        if items.length < 3 then
          throw RLPException(
            s"Cannot decode Block: expected at least [header, transactions, uncles], got ${items.length}",
            rlp
          )
        val header = RLPDecoder.decode[BlockHeader](items(0))
        // Length-driven, mirroring the body: item 3 is the trailing-optional withdrawals; anything past it is a
        // future field, tolerated not crashed.
        val withdrawals =
          if items.length > 3 then Some(RLPDecoder.decode[List[Withdrawal]](items(3))) else None
        val body = BlockBody(BlockBody.decodeTxs(items(1)), BlockBody.decodeUncles(items(2)), withdrawals)
        Block(header, body)
      case _ => throw RLPException("Cannot decode Block: expected an RLPList", rlp)
