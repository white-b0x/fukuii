package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPDecoder
import com.chipprbots.fukuii.rlp.RLPEncodeable
import com.chipprbots.fukuii.rlp.RLPEncoder
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.encode as rlpEncode

/** A block header — the fork-variant consensus record whose keccak256(RLP) is the block hash.
  *
  * **The fixed 15-field prefix** is byte-exact to go-ethereum `core/types/block.go:68-108`, in RLP order: `ParentHash →
  * UncleHash(ommers) → Coinbase(beneficiary) → Root(state) → TxHash(transactions) → ReceiptHash(receipts) → Bloom →
  * Difficulty → Number → GasLimit → GasUsed → Time → Extra → MixDigest → Nonce`.
  *
  * **The trailing-optional tail** carries geth's eight `rlp:"optional"` fields in this **exact** order — each present
  * from the fork that introduced it, omitted before, and once present **all earlier trailing fields must also be
  * present** (geth encodes a contiguous present-prefix; a mid-run gap is impossible on the wire and is rejected on
  * encode here):
  *   1. `baseFeePerGas` — EIP-1559 (London), `block.go:86`
  *   1. `withdrawalsRoot` — EIP-4895 (Shanghai), `:89`
  *   1. `blobGasUsed` — EIP-4844 (Cancun), `:92`
  *   1. `excessBlobGas` — EIP-4844 (Cancun), `:95`
  *   1. `parentBeaconBlockRoot` — EIP-4788 (Cancun), `:98`
  *   1. `requestsHash` — EIP-7685 (Prague), `:101`
  *   1. `blockAccessListHash` — EIP-7928 (Osaka+), `:104`
  *   1. `slotNumber` — EIP-7843 (Osaka+), `:107`
  *
  * **The tail is open-ended** (RX-L1-04): each new ETH fork appends more trailing-optionals, so the [[given RLPCodec]]
  * below is written **list-length-driven with no hardcoded max field count** — a future ninth field, if it appears on
  * the wire before this build learns it, is tolerated (not decoded), never a decode crash.
  *
  * **Network-neutral (R1):** the type carries **no** chainId or fork symbol. Which trailing fields a network populates
  * is an L4/L5 decision (the ETH-family, **beacon**-gated, owns the tail; ETC pre-Olympia stays at **zero** trailing
  * fields — a base-fee/blob/withdrawal field on an ETC header is a consensus bug). The per-fork factory is the
  * positive-keyed staged construction below (`with*` methods, the besu `BlockHeaderBuilder` shape as an *immutable*
  * factory, RX-L1-06), **not** an `else-means-ETC`/`else-means-legacy` fallthrough.
  */
final case class BlockHeader(
    parentHash: Hash,
    ommersHash: Hash,
    beneficiary: Address,
    stateRoot: Hash,
    transactionsRoot: Hash,
    receiptsRoot: Hash,
    logsBloom: Bloom,
    difficulty: BigInt,
    number: BigInt,
    gasLimit: Long,
    gasUsed: Long,
    unixTimestamp: Long,
    extraData: ByteString,
    mixHash: Hash,
    nonce: ByteString,
    baseFeePerGas: Option[BigInt] = None,
    withdrawalsRoot: Option[Hash] = None,
    blobGasUsed: Option[Long] = None,
    excessBlobGas: Option[Long] = None,
    parentBeaconBlockRoot: Option[Hash] = None,
    requestsHash: Option[Hash] = None,
    blockAccessListHash: Option[Hash] = None,
    slotNumber: Option[Long] = None
)

object BlockHeader:

  /** The count of the fixed (always-present) prefix fields — the split point between the fixed head and the
    * trailing-optional tail. Used only to *split* the decoded list; never as an upper bound on its length.
    */
  private val FixedFieldCount = 15

  /** Build the trailing-optional suffix, enforcing the contiguous-present-prefix invariant (geth `rlp:"optional"`): a
    * present field after an omitted earlier one (a mid-run gap — e.g. `excessBlobGas` without `blobGasUsed`) is a
    * consensus-invalid header and is rejected on encode. Emits exactly up to the last present field.
    */
  private def encodeTrailing(trailing: List[Option[RLPEncodeable]]): List[RLPEncodeable] =
    val lastPresent = trailing.lastIndexWhere(_.isDefined)
    if lastPresent < 0 then Nil
    else
      val prefix = trailing.take(lastPresent + 1)
      if prefix.exists(_.isEmpty) then
        throw RLPException(
          "Cannot encode BlockHeader: a trailing-optional field is present after an omitted earlier one " +
            "(mid-run gap) — RLP trailing-optionals must form a contiguous present prefix"
        )
      prefix.flatten

  given RLPCodec[BlockHeader] = new RLPCodec[BlockHeader]:
    def encode(h: BlockHeader): RLPEncodeable =
      val fixed: List[RLPEncodeable] = List(
        RLPEncoder.encode(h.parentHash),
        RLPEncoder.encode(h.ommersHash),
        RLPEncoder.encode(h.beneficiary),
        RLPEncoder.encode(h.stateRoot),
        RLPEncoder.encode(h.transactionsRoot),
        RLPEncoder.encode(h.receiptsRoot),
        RLPEncoder.encode(h.logsBloom),
        RLPEncoder.encode(h.difficulty),
        RLPEncoder.encode(h.number),
        RLPEncoder.encode(h.gasLimit),
        RLPEncoder.encode(h.gasUsed),
        RLPEncoder.encode(h.unixTimestamp),
        RLPEncoder.encode(h.extraData),
        RLPEncoder.encode(h.mixHash),
        RLPEncoder.encode(h.nonce)
      )
      // Positive-keyed, in geth's exact tail order — each slot is Some(encoded) iff that field is populated.
      val trailing: List[Option[RLPEncodeable]] = List(
        h.baseFeePerGas.map(v => RLPEncoder.encode(v)),
        h.withdrawalsRoot.map(v => RLPEncoder.encode(v)),
        h.blobGasUsed.map(v => RLPEncoder.encode(v)),
        h.excessBlobGas.map(v => RLPEncoder.encode(v)),
        h.parentBeaconBlockRoot.map(v => RLPEncoder.encode(v)),
        h.requestsHash.map(v => RLPEncoder.encode(v)),
        h.blockAccessListHash.map(v => RLPEncoder.encode(v)),
        h.slotNumber.map(v => RLPEncoder.encode(v))
      )
      RLPList((fixed ++ encodeTrailing(trailing))*)

    def decode(rlp: RLPEncodeable): BlockHeader = rlp match
      case list: RLPList =>
        val items = list.items
        if items.length < FixedFieldCount then
          throw RLPException(
            s"Cannot decode BlockHeader: expected at least $FixedFieldCount fixed fields, got ${items.length}",
            rlp
          )
        val trailing = items.drop(FixedFieldCount)
        // Length-driven positional decode of the known trailing-optionals. Items past the known eight belong to a
        // future ETH fork this build does not model yet: tolerated (not decoded), never a crash — the open tail.
        def opt[T](i: Int)(using dec: RLPCodec[T]): Option[T] =
          if i < trailing.length then Some(dec.decode(trailing(i))) else None
        BlockHeader(
          parentHash = RLPDecoder.decode[Hash](items(0)),
          ommersHash = RLPDecoder.decode[Hash](items(1)),
          beneficiary = RLPDecoder.decode[Address](items(2)),
          stateRoot = RLPDecoder.decode[Hash](items(3)),
          transactionsRoot = RLPDecoder.decode[Hash](items(4)),
          receiptsRoot = RLPDecoder.decode[Hash](items(5)),
          logsBloom = RLPDecoder.decode[Bloom](items(6)),
          difficulty = RLPDecoder.decode[BigInt](items(7)),
          number = RLPDecoder.decode[BigInt](items(8)),
          gasLimit = RLPDecoder.decode[Long](items(9)),
          gasUsed = RLPDecoder.decode[Long](items(10)),
          unixTimestamp = RLPDecoder.decode[Long](items(11)),
          extraData = RLPDecoder.decode[ByteString](items(12)),
          mixHash = RLPDecoder.decode[Hash](items(13)),
          nonce = RLPDecoder.decode[ByteString](items(14)),
          baseFeePerGas = opt[BigInt](0),
          withdrawalsRoot = opt[Hash](1),
          blobGasUsed = opt[Long](2),
          excessBlobGas = opt[Long](3),
          parentBeaconBlockRoot = opt[Hash](4),
          requestsHash = opt[Hash](5),
          blockAccessListHash = opt[Hash](6),
          slotNumber = opt[Long](7)
        )
      case _ => throw RLPException("Cannot decode BlockHeader: expected an RLPList", rlp)

  extension (h: BlockHeader)
    /** The block hash — keccak256 of the header's RLP encoding (go-ethereum `Header.Hash()` = `rlpHash(h)`). */
    def hash: Hash = Hash(ByteString(kec256(rlpEncode(h))))

    // Staged, immutable per-fork construction (besu `BlockHeaderBuilder` shape, RX-L1-06): the default-`None`
    // case-class constructor yields a legacy/pre-fork header, and each `with*` sets one positive-keyed trailing
    // field. Build a fork's tail by chaining in geth's order — the encoder rejects any resulting mid-run gap.
    def withBaseFeePerGas(v: BigInt): BlockHeader = h.copy(baseFeePerGas = Some(v))
    def withWithdrawalsRoot(v: Hash): BlockHeader = h.copy(withdrawalsRoot = Some(v))
    def withBlobGas(used: Long, excess: Long): BlockHeader =
      h.copy(blobGasUsed = Some(used), excessBlobGas = Some(excess))
    def withParentBeaconBlockRoot(v: Hash): BlockHeader = h.copy(parentBeaconBlockRoot = Some(v))
    def withRequestsHash(v: Hash): BlockHeader = h.copy(requestsHash = Some(v))
    def withBlockAccessListHash(v: Hash): BlockHeader = h.copy(blockAccessListHash = Some(v))
    def withSlotNumber(v: Long): BlockHeader = h.copy(slotNumber = Some(v))
