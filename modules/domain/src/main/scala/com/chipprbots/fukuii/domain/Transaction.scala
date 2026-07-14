package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.rlp.PrefixedRLPEncodable
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPEncodeable
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.RLPValue

/** The EIP-2718 typed-transaction envelope — a Scala 3 `enum` of the ecosystem's admissible transaction types.
  *
  * Replaces besu's `Transaction.java` optionals-on-one-class shape (11 `Optional<>` fields) with per-variant cases:
  * illegal field combinations (1559 fields on a legacy tx) are unrepresentable, and each variant carries exactly the
  * fields its go-ethereum `core/types/tx_*.go` struct declares, in RLP field order. The type id is the [[txType]]
  * method, not a stored discriminator (`plan/L1.md` §5).
  *
  * **Framework, not ETC/ETH binary.** `domain` models every variant the ecosystem defines; whether a variant is
  * *admissible* on a given network (e.g. a blob tx on ETC's pre-Olympia gas model) is an L4/L5 policy decision, not a
  * modelling omission (`plan/L1.md` §9).
  *
  * **No `DepositTx` (0x7E).** 0x7E is an OP-stack (Optimism) type — absent from both core-geth (the ETC authority) and
  * go-ethereum mainline (RX-L1-07). ETC's and ETH's admissible tx-type set is exactly `{0x00-0x04}`; a future OP-stack
  * `NetworkFamily` would add 0x7E as *that family's* variant, never the base enum.
  */
enum Transaction:

  /** EIP-2718 type `0x00` — go-ethereum `core/types/tx_legacy.go` `LegacyTx{ Nonce, GasPrice, Gas, To, Value, Data, V,
    * R, S }`. RLP field order matches exactly; `to == None` is contract creation (geth `rlp:"nil"` pointer semantics —
    * the empty RLP string, not an empty list).
    */
  case Legacy(
      nonce: UInt256,
      gasPrice: Wei,
      gasLimit: UInt256,
      to: Option[Address],
      value: Wei,
      payload: ByteString,
      signature: ECDSASignature
  )

  /** EIP-2718 type `0x01` (EIP-2930) — go-ethereum `tx_access_list.go` `AccessListTx{ ChainID, Nonce, GasPrice, Gas,
    * To, Value, Data, AccessList, V, R, S }`.
    */
  case AccessList(
      chainId: ChainId,
      nonce: UInt256,
      gasPrice: Wei,
      gasLimit: UInt256,
      to: Option[Address],
      value: Wei,
      payload: ByteString,
      accessList: List[AccessListEntry],
      signature: ECDSASignature
  )

  /** EIP-2718 type `0x02` (EIP-1559) — go-ethereum `tx_dynamic_fee.go` `DynamicFeeTx{ ChainID, Nonce, GasTipCap,
    * GasFeeCap, Gas, To, Value, Data, AccessList, V, R, S }` (`GasTipCap`/`GasFeeCap` a.k.a. `maxPriorityFeePerGas`/
    * `maxFeePerGas`).
    */
  case DynamicFee(
      chainId: ChainId,
      nonce: UInt256,
      maxPriorityFeePerGas: Wei,
      maxFeePerGas: Wei,
      gasLimit: UInt256,
      to: Option[Address],
      value: Wei,
      payload: ByteString,
      accessList: List[AccessListEntry],
      signature: ECDSASignature
  )

  /** EIP-2718 type `0x03` (EIP-4844) — go-ethereum `tx_blob.go` `BlobTx{ ChainID, Nonce, GasTipCap, GasFeeCap, Gas, To,
    * Value, Data, AccessList, BlobFeeCap, BlobHashes, V, R, S }`. `to` is **not** optional — EIP-4844 forbids
    * contract-creation blob transactions. Models only the **consensus** encoding (no sidecar; geth's `Sidecar
    * *BlobTxSidecar \`rlp:"-"\`` is excluded from this RLP by construction) — the network-wrapper form and its RLP
    * given are phase-2b (`plan/L1.md` §9 two-form split).
    *
    * **RLP given not yet implemented** — phase 2b.
    */
  case Blob(
      chainId: ChainId,
      nonce: UInt256,
      maxPriorityFeePerGas: Wei,
      maxFeePerGas: Wei,
      gasLimit: UInt256,
      to: Address,
      value: Wei,
      payload: ByteString,
      accessList: List[AccessListEntry],
      maxFeePerBlobGas: Wei,
      blobVersionedHashes: List[Hash],
      signature: ECDSASignature
  )

  /** EIP-2718 type `0x04` (EIP-7702, ETH-family) — go-ethereum `tx_setcode.go` `SetCodeTx{ ChainID, Nonce, GasTipCap,
    * GasFeeCap, Gas, To, Value, Data, AccessList, AuthList, V, R, S }`. `to` is **not** optional, matching EIP-7702.
    * The inner [[SetCodeAuthorization]] list carries its own, second signature surface (§7 N-1 companion note).
    *
    * **RLP given not yet implemented** — phase 2b.
    */
  case SetCode(
      chainId: ChainId,
      nonce: UInt256,
      maxPriorityFeePerGas: Wei,
      maxFeePerGas: Wei,
      gasLimit: UInt256,
      to: Address,
      value: Wei,
      payload: ByteString,
      accessList: List[AccessListEntry],
      authorizationList: List[SetCodeAuthorization],
      signature: ECDSASignature
  )

  /** The EIP-2718 type id — a method, not a stored discriminator. Byte-exact to go-ethereum `core/types/
    * transaction.go:48-52` (`LegacyTxType=0x00` .. `SetCodeTxType=0x04`); core-geth carries the identical constants.
    */
  def txType: Byte = this match
    case _: Transaction.Legacy     => 0x00
    case _: Transaction.AccessList => 0x01
    case _: Transaction.DynamicFee => 0x02
    case _: Transaction.Blob       => 0x03
    case _: Transaction.SetCode    => 0x04

object Transaction:

  private def notYetSupported(what: String): Nothing =
    throw RLPException(s"$what RLP is not yet supported (phase 2b)")

  // --- the `to` field -------------------------------------------------------------------------------------------
  // geth's `rlp:"nil"` pointer semantics: a nil `*common.Address` encodes as the RLP **empty string** (`0x80`), not
  // an empty list. This must not be confused with the generic `RLPCodecs.optionCodec[T]` (list-shaped: `None` ->
  // empty list, `Some(v)` -> single-element list) — that codec is the wrong shape for this field and is
  // deliberately not used here.

  private def encodeTo(to: Option[Address]): RLPEncodeable = to match
    case Some(address) => RLPValue(address.toArray)
    case None          => RLPValue(Array.emptyByteArray)

  private def decodeTo(rlp: RLPEncodeable): Option[Address] = rlp match
    case RLPValue(bytes) if bytes.isEmpty => None
    case RLPValue(bytes)                  => Some(Address(ByteString(bytes)))
    case _                                => throw RLPException("Cannot decode `to`: expected an RLPValue", rlp)

  // --- per-variant hand-written givens (RX-L1-19: the reth "hand-write the special cases" pattern) ---------------

  given RLPCodec[Legacy] = new RLPCodec[Legacy]:
    def encode(tx: Legacy): RLPEncodeable =
      RLPList(
        summon[RLPCodec[UInt256]].encode(tx.nonce),
        summon[RLPCodec[Wei]].encode(tx.gasPrice),
        summon[RLPCodec[UInt256]].encode(tx.gasLimit),
        encodeTo(tx.to),
        summon[RLPCodec[Wei]].encode(tx.value),
        summon[RLPCodec[ByteString]].encode(tx.payload),
        summon[RLPCodec[BigInt]].encode(tx.signature.v),
        summon[RLPCodec[BigInt]].encode(tx.signature.r),
        summon[RLPCodec[BigInt]].encode(tx.signature.s)
      )
    def decode(rlp: RLPEncodeable): Legacy = rlp match
      case RLPList(nonce, gasPrice, gasLimit, to, value, payload, v, r, s) =>
        Legacy(
          nonce = summon[RLPCodec[UInt256]].decode(nonce),
          gasPrice = summon[RLPCodec[Wei]].decode(gasPrice),
          gasLimit = summon[RLPCodec[UInt256]].decode(gasLimit),
          to = decodeTo(to),
          value = summon[RLPCodec[Wei]].decode(value),
          payload = summon[RLPCodec[ByteString]].decode(payload),
          signature = ECDSASignature(
            r = summon[RLPCodec[BigInt]].decode(r),
            s = summon[RLPCodec[BigInt]].decode(s),
            v = summon[RLPCodec[BigInt]].decode(v)
          )
        )
      case list: RLPList =>
        throw RLPException(s"Cannot decode Legacy transaction: expected 9 elements, got ${list.items.length}", rlp)
      case _ => throw RLPException("Cannot decode Legacy transaction: expected an RLPList", rlp)

  given RLPCodec[AccessList] = new RLPCodec[AccessList]:
    def encode(tx: AccessList): RLPEncodeable =
      PrefixedRLPEncodable(
        0x01,
        RLPList(
          summon[RLPCodec[ChainId]].encode(tx.chainId),
          summon[RLPCodec[UInt256]].encode(tx.nonce),
          summon[RLPCodec[Wei]].encode(tx.gasPrice),
          summon[RLPCodec[UInt256]].encode(tx.gasLimit),
          encodeTo(tx.to),
          summon[RLPCodec[Wei]].encode(tx.value),
          summon[RLPCodec[ByteString]].encode(tx.payload),
          summon[RLPCodec[List[AccessListEntry]]].encode(tx.accessList),
          summon[RLPCodec[BigInt]].encode(tx.signature.v),
          summon[RLPCodec[BigInt]].encode(tx.signature.r),
          summon[RLPCodec[BigInt]].encode(tx.signature.s)
        )
      )
    def decode(rlp: RLPEncodeable): AccessList = rlp match
      case RLPList(chainId, nonce, gasPrice, gasLimit, to, value, payload, accessList, v, r, s) =>
        AccessList(
          chainId = summon[RLPCodec[ChainId]].decode(chainId),
          nonce = summon[RLPCodec[UInt256]].decode(nonce),
          gasPrice = summon[RLPCodec[Wei]].decode(gasPrice),
          gasLimit = summon[RLPCodec[UInt256]].decode(gasLimit),
          to = decodeTo(to),
          value = summon[RLPCodec[Wei]].decode(value),
          payload = summon[RLPCodec[ByteString]].decode(payload),
          accessList = summon[RLPCodec[List[AccessListEntry]]].decode(accessList),
          signature = ECDSASignature(
            r = summon[RLPCodec[BigInt]].decode(r),
            s = summon[RLPCodec[BigInt]].decode(s),
            v = summon[RLPCodec[BigInt]].decode(v)
          )
        )
      case list: RLPList =>
        throw RLPException(s"Cannot decode AccessList transaction: expected 11 elements, got ${list.items.length}", rlp)
      case _ => throw RLPException("Cannot decode AccessList transaction: expected an RLPList", rlp)

  given RLPCodec[DynamicFee] = new RLPCodec[DynamicFee]:
    def encode(tx: DynamicFee): RLPEncodeable =
      PrefixedRLPEncodable(
        0x02,
        RLPList(
          summon[RLPCodec[ChainId]].encode(tx.chainId),
          summon[RLPCodec[UInt256]].encode(tx.nonce),
          summon[RLPCodec[Wei]].encode(tx.maxPriorityFeePerGas),
          summon[RLPCodec[Wei]].encode(tx.maxFeePerGas),
          summon[RLPCodec[UInt256]].encode(tx.gasLimit),
          encodeTo(tx.to),
          summon[RLPCodec[Wei]].encode(tx.value),
          summon[RLPCodec[ByteString]].encode(tx.payload),
          summon[RLPCodec[List[AccessListEntry]]].encode(tx.accessList),
          summon[RLPCodec[BigInt]].encode(tx.signature.v),
          summon[RLPCodec[BigInt]].encode(tx.signature.r),
          summon[RLPCodec[BigInt]].encode(tx.signature.s)
        )
      )
    def decode(rlp: RLPEncodeable): DynamicFee = rlp match
      case RLPList(
            chainId,
            nonce,
            maxPriorityFeePerGas,
            maxFeePerGas,
            gasLimit,
            to,
            value,
            payload,
            accessList,
            v,
            r,
            s
          ) =>
        DynamicFee(
          chainId = summon[RLPCodec[ChainId]].decode(chainId),
          nonce = summon[RLPCodec[UInt256]].decode(nonce),
          maxPriorityFeePerGas = summon[RLPCodec[Wei]].decode(maxPriorityFeePerGas),
          maxFeePerGas = summon[RLPCodec[Wei]].decode(maxFeePerGas),
          gasLimit = summon[RLPCodec[UInt256]].decode(gasLimit),
          to = decodeTo(to),
          value = summon[RLPCodec[Wei]].decode(value),
          payload = summon[RLPCodec[ByteString]].decode(payload),
          accessList = summon[RLPCodec[List[AccessListEntry]]].decode(accessList),
          signature = ECDSASignature(
            r = summon[RLPCodec[BigInt]].decode(r),
            s = summon[RLPCodec[BigInt]].decode(s),
            v = summon[RLPCodec[BigInt]].decode(v)
          )
        )
      case list: RLPList =>
        throw RLPException(s"Cannot decode DynamicFee transaction: expected 12 elements, got ${list.items.length}", rlp)
      case _ => throw RLPException("Cannot decode DynamicFee transaction: expected an RLPList", rlp)

  // --- the aggregate `Transaction` codec ------------------------------------------------------------------------
  // `decode(rlp: RLPEncodeable)` can only ever see a bare `RLPList` here (a typed tx's leading type byte is
  // consumed *before* AST parsing — see `Transaction.decode(bytes)` below); it is therefore always the Legacy
  // shape. Use `Transaction.decode(bytes: Array[Byte])` for EIP-2718 dispatch.

  given RLPCodec[Transaction] = new RLPCodec[Transaction]:
    def encode(tx: Transaction): RLPEncodeable = tx match
      case l: Legacy     => summon[RLPCodec[Legacy]].encode(l)
      case a: AccessList => summon[RLPCodec[AccessList]].encode(a)
      case d: DynamicFee => summon[RLPCodec[DynamicFee]].encode(d)
      case _: Blob       => notYetSupported("Blob transaction")
      case _: SetCode    => notYetSupported("SetCode transaction")

    def decode(rlp: RLPEncodeable): Transaction = rlp match
      case list: RLPList => summon[RLPCodec[Legacy]].decode(list)
      case _ =>
        throw RLPException(
          "Cannot decode a typed (EIP-2718) transaction from a bare RLPEncodeable — " +
            "use Transaction.decode(bytes: Array[Byte]) for the first-byte dispatch",
          rlp
        )

  /** The EIP-2718 first-byte dispatch decoder — the canonical "binary" transaction envelope (go-ethereum
    * `Transaction.UnmarshalBinary`; besu `TransactionType.fromOpaque`).
    *
    * A legacy transaction is a bare RLP list, whose header byte is always `>= 0xc0`; a typed transaction is `typeByte
    * \|| RLP(payload fields)`, `typeByte` in `{0x01, 0x02, 0x03, 0x04}`. **The boundary is `first byte >= 0xc0`, not
    * `first byte > 0x04`** — the gap `0x05..0xbf` (and any other unrecognized byte) is **rejected**, never silently
    * treated as legacy. This mirrors besu's `transactionTypeByOpaqueByte` positive-key dispatch table exactly: an
    * unrecognized opaque byte returns `Optional.empty` (rejected), not `FRONTIER` — go-ethereum's `decodeTyped`
    * likewise `default: return nil, ErrTxTypeNotSupported`. Treating that gap as legacy would silently accept a
    * malformed/future type byte as a well-formed legacy list — a wrong-tx-hash consensus-split hazard.
    */
  def decode(bytes: Array[Byte]): Transaction =
    if bytes.isEmpty then throw RLPException("Cannot decode an empty transaction")
    val first = bytes(0) & 0xff
    if first >= 0xc0 then summon[RLPCodec[Legacy]].decode(com.chipprbots.fukuii.rlp.rawDecodeStrict(bytes))
    else
      first match
        case 0x01 => summon[RLPCodec[AccessList]].decode(com.chipprbots.fukuii.rlp.rawDecodeStrict(bytes.tail))
        case 0x02 => summon[RLPCodec[DynamicFee]].decode(com.chipprbots.fukuii.rlp.rawDecodeStrict(bytes.tail))
        case 0x03 => notYetSupported("Blob transaction (0x03)")
        case 0x04 => notYetSupported("SetCode transaction (0x04)")
        case other =>
          throw RLPException(
            f"Unrecognized transaction type byte 0x$other%02x — not a legacy list header (>= 0xc0) and not a " +
              "known EIP-2718 type (0x01-0x04); rejected, not treated as legacy"
          )
