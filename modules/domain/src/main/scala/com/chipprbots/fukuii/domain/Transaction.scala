package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.rlp.PrefixedRLPEncodable
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPDecoder
import com.chipprbots.fukuii.rlp.RLPEncodeable
import com.chipprbots.fukuii.rlp.RLPEncoder
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

  /** EIP-2718 type `0x03` (EIP-4844) — go-ethereum `tx_blob.go:47-68` `BlobTx{ ChainID, Nonce, GasTipCap, GasFeeCap,
    * Gas, To, Value, Data, AccessList, BlobFeeCap, BlobHashes, Sidecar, V, R, S }`. `to` is **not** optional — EIP-4844
    * forbids contract-creation blob transactions.
    *
    * **Two-form encoding (`plan/L1.md` §9, nethermind two-form discipline).** The [[sidecar]]
    * (blobs+commitments+proofs) is present **only** in the P2P network-wrapper form, never in the consensus form —
    * mirroring geth's `Sidecar *BlobTxSidecar \`rlp:"-"\`` tag (`tx_blob.go:62`). The two forms are two explicitly
    * named codecs over this one case (`Transaction.blobConsensusCodec` = default given, used by `tx.hash`/tx-trie;
    * `Transaction.BlobNetworkWrapper` \= the explicitly-imported wire form) — **never one codec that conditionally
    * includes the sidecar**, since hashing the wrapper is a consensus split (§9). Two `Blob`s differing only in
    * [[sidecar]] therefore encode to **identical** consensus bytes (and the same tx hash).
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
      signature: ECDSASignature,
      sidecar: Option[BlobSidecar] = None
  )

  /** EIP-2718 type `0x04` (EIP-7702, ETH-family) — go-ethereum `tx_setcode.go` `SetCodeTx{ ChainID, Nonce, GasTipCap,
    * GasFeeCap, Gas, To, Value, Data, AccessList, AuthList, V, R, S }`. `to` is **not** optional, matching EIP-7702.
    * The inner [[SetCodeAuthorization]] list carries its own, second signature surface (§7 N-1 companion note) — its
    * own RLP codec and `sigHash` (magic byte `0x05`, distinct from this type's `0x04`) live in
    * [[SetCodeAuthorization]].
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

  // --- the `to` field -------------------------------------------------------------------------------------------
  // geth's `rlp:"nil"` pointer semantics: a nil `*common.Address` encodes as the RLP **empty string** (`0x80`), not
  // an empty list. This must not be confused with the generic `RLPCodecs.optionCodec[T]` (list-shaped: `None` ->
  // empty list, `Some(v)` -> single-element list) — that codec is the wrong shape for this field and is
  // deliberately not used here.

  // `private[domain]` (not `private`): the sender-recovery sighash builder (`SenderRecovery.scala`) reuses this exact
  // `to` encoding so the signed message's `to` field is byte-identical to the envelope's — a single source, no risk of
  // a divergent nil-pointer encoding between the two.
  private[domain] def encodeTo(to: Option[Address]): RLPEncodeable = to match
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
        RLPEncoder.encode(tx.nonce),
        RLPEncoder.encode(tx.gasPrice),
        RLPEncoder.encode(tx.gasLimit),
        encodeTo(tx.to),
        RLPEncoder.encode(tx.value),
        RLPEncoder.encode(tx.payload),
        RLPEncoder.encode(tx.signature.v),
        RLPEncoder.encode(tx.signature.r),
        RLPEncoder.encode(tx.signature.s)
      )
    def decode(rlp: RLPEncodeable): Legacy = rlp match
      case RLPList(nonce, gasPrice, gasLimit, to, value, payload, v, r, s) =>
        Legacy(
          nonce = RLPDecoder.decode[UInt256](nonce),
          gasPrice = RLPDecoder.decode[Wei](gasPrice),
          gasLimit = RLPDecoder.decode[UInt256](gasLimit),
          to = decodeTo(to),
          value = RLPDecoder.decode[Wei](value),
          payload = RLPDecoder.decode[ByteString](payload),
          signature = ECDSASignature(
            r = RLPDecoder.decode[BigInt](r),
            s = RLPDecoder.decode[BigInt](s),
            v = RLPDecoder.decode[BigInt](v)
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
          RLPEncoder.encode(tx.chainId),
          RLPEncoder.encode(tx.nonce),
          RLPEncoder.encode(tx.gasPrice),
          RLPEncoder.encode(tx.gasLimit),
          encodeTo(tx.to),
          RLPEncoder.encode(tx.value),
          RLPEncoder.encode(tx.payload),
          RLPEncoder.encode(tx.accessList),
          RLPEncoder.encode(tx.signature.v),
          RLPEncoder.encode(tx.signature.r),
          RLPEncoder.encode(tx.signature.s)
        )
      )
    def decode(rlp: RLPEncodeable): AccessList = rlp match
      case RLPList(chainId, nonce, gasPrice, gasLimit, to, value, payload, accessList, v, r, s) =>
        AccessList(
          chainId = RLPDecoder.decode[ChainId](chainId),
          nonce = RLPDecoder.decode[UInt256](nonce),
          gasPrice = RLPDecoder.decode[Wei](gasPrice),
          gasLimit = RLPDecoder.decode[UInt256](gasLimit),
          to = decodeTo(to),
          value = RLPDecoder.decode[Wei](value),
          payload = RLPDecoder.decode[ByteString](payload),
          accessList = RLPDecoder.decode[List[AccessListEntry]](accessList),
          signature = ECDSASignature(
            r = RLPDecoder.decode[BigInt](r),
            s = RLPDecoder.decode[BigInt](s),
            v = RLPDecoder.decode[BigInt](v)
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
          RLPEncoder.encode(tx.chainId),
          RLPEncoder.encode(tx.nonce),
          RLPEncoder.encode(tx.maxPriorityFeePerGas),
          RLPEncoder.encode(tx.maxFeePerGas),
          RLPEncoder.encode(tx.gasLimit),
          encodeTo(tx.to),
          RLPEncoder.encode(tx.value),
          RLPEncoder.encode(tx.payload),
          RLPEncoder.encode(tx.accessList),
          RLPEncoder.encode(tx.signature.v),
          RLPEncoder.encode(tx.signature.r),
          RLPEncoder.encode(tx.signature.s)
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
          chainId = RLPDecoder.decode[ChainId](chainId),
          nonce = RLPDecoder.decode[UInt256](nonce),
          maxPriorityFeePerGas = RLPDecoder.decode[Wei](maxPriorityFeePerGas),
          maxFeePerGas = RLPDecoder.decode[Wei](maxFeePerGas),
          gasLimit = RLPDecoder.decode[UInt256](gasLimit),
          to = decodeTo(to),
          value = RLPDecoder.decode[Wei](value),
          payload = RLPDecoder.decode[ByteString](payload),
          accessList = RLPDecoder.decode[List[AccessListEntry]](accessList),
          signature = ECDSASignature(
            r = RLPDecoder.decode[BigInt](r),
            s = RLPDecoder.decode[BigInt](s),
            v = RLPDecoder.decode[BigInt](v)
          )
        )
      case list: RLPList =>
        throw RLPException(s"Cannot decode DynamicFee transaction: expected 12 elements, got ${list.items.length}", rlp)
      case _ => throw RLPException("Cannot decode DynamicFee transaction: expected an RLPList", rlp)

  // --- Blob (0x03), two-form (RX-L1-11): consensus (no sidecar) vs network wrapper (blobs+commitments+proofs) ----
  // The consensus body is one 14-element list; both forms share it. `blobBodyList`/`blobFromBody` factor it so the
  // wrapper form nests the *same* body list, never a re-derived one — the byte-difference between the two forms is
  // exactly the sidecar tail, nothing else (§9: the consensus body must be byte-identical across both forms).

  private def blobBodyList(tx: Blob): RLPList =
    RLPList(
      RLPEncoder.encode(tx.chainId),
      RLPEncoder.encode(tx.nonce),
      RLPEncoder.encode(tx.maxPriorityFeePerGas),
      RLPEncoder.encode(tx.maxFeePerGas),
      RLPEncoder.encode(tx.gasLimit),
      RLPEncoder.encode(tx.to),
      RLPEncoder.encode(tx.value),
      RLPEncoder.encode(tx.payload),
      RLPEncoder.encode(tx.accessList),
      RLPEncoder.encode(tx.maxFeePerBlobGas),
      RLPEncoder.encode(tx.blobVersionedHashes),
      RLPEncoder.encode(tx.signature.v),
      RLPEncoder.encode(tx.signature.r),
      RLPEncoder.encode(tx.signature.s)
    )

  private def blobFromBody(body: RLPEncodeable, sidecar: Option[BlobSidecar]): Blob = body match
    case RLPList(
          chainId,
          nonce,
          maxPrio,
          maxFee,
          gasLimit,
          to,
          value,
          payload,
          accessList,
          maxBlobFee,
          hashes,
          v,
          r,
          s
        ) =>
      Blob(
        chainId = RLPDecoder.decode[ChainId](chainId),
        nonce = RLPDecoder.decode[UInt256](nonce),
        maxPriorityFeePerGas = RLPDecoder.decode[Wei](maxPrio),
        maxFeePerGas = RLPDecoder.decode[Wei](maxFee),
        gasLimit = RLPDecoder.decode[UInt256](gasLimit),
        to = RLPDecoder.decode[Address](to),
        value = RLPDecoder.decode[Wei](value),
        payload = RLPDecoder.decode[ByteString](payload),
        accessList = RLPDecoder.decode[List[AccessListEntry]](accessList),
        maxFeePerBlobGas = RLPDecoder.decode[Wei](maxBlobFee),
        blobVersionedHashes = RLPDecoder.decode[List[Hash]](hashes),
        signature = ECDSASignature(
          r = RLPDecoder.decode[BigInt](r),
          s = RLPDecoder.decode[BigInt](s),
          v = RLPDecoder.decode[BigInt](v)
        ),
        sidecar = sidecar
      )
    case list: RLPList =>
      throw RLPException(s"Cannot decode Blob transaction body: expected 14 elements, got ${list.items.length}", body)
    case _ => throw RLPException("Cannot decode Blob transaction body: expected an RLPList", body)

  /** The **consensus** blob encoding — `0x03 ‖ RLP([chainId, …, blobVersionedHashes, v, r, s])`, **no** sidecar. This
    * is the default `given RLPCodec[Blob]`: `tx.hash`, the tx-trie root, and the aggregate [[Transaction]] codec all
    * resolve to it, so the sidecar can never leak into a hashed/rooted encoding (§9).
    */
  given blobConsensusCodec: RLPCodec[Blob] = new RLPCodec[Blob]:
    def encode(tx: Blob): RLPEncodeable = PrefixedRLPEncodable(0x03, blobBodyList(tx))
    def decode(rlp: RLPEncodeable): Blob = blobFromBody(rlp, None)

  /** The **network-wrapper** blob encoding (EIP-4844 v0 / Cancun `PooledTransactions`): `0x03 ‖ RLP([txBody, blobs,
    * commitments, proofs])`, where `txBody` is the *same* consensus body list. Byte-cited to geth `tx_blob.go:328-353`
    * (`blobTxWithBlobsV0{ BlobTx, Blobs, Commitments, Proofs }`) — the v0 form carries no version byte (that is the v1
    * form, `:341-348`), so decode fixes `sidecar.version = BlobSidecar.Version0`.
    *
    * Deliberately kept **out of default implicit scope** (a nested object, not a top-level `given RLPCodec[Blob]`): a
    * second top-level given would be an ambiguous-implicit compile error *and*, worse, would let the wrapper be
    * summoned for `tx.hash` — a consensus split. Import `Transaction.BlobNetworkWrapper.given` explicitly at the L6
    * wire layer to select it. Both forms are named; only the consensus form is the default.
    */
  object BlobNetworkWrapper:
    given blobNetworkWrapperCodec: RLPCodec[Blob] = new RLPCodec[Blob]:
      def encode(tx: Blob): RLPEncodeable =
        val sc = tx.sidecar.getOrElse(
          throw RLPException("Blob network-wrapper encoding requires a sidecar, but the transaction has none")
        )
        PrefixedRLPEncodable(
          0x03,
          RLPList(
            blobBodyList(tx),
            RLPEncoder.encode(sc.blobs),
            RLPEncoder.encode(sc.commitments),
            RLPEncoder.encode(sc.proofs)
          )
        )
      def decode(rlp: RLPEncodeable): Blob = rlp match
        case RLPList(body, blobs, commitments, proofs) =>
          val sc = BlobSidecar(
            version = BlobSidecar.Version0,
            blobs = RLPDecoder.decode[List[ByteString]](blobs),
            commitments = RLPDecoder.decode[List[ByteString]](commitments),
            proofs = RLPDecoder.decode[List[ByteString]](proofs)
          )
          blobFromBody(body, Some(sc))
        case _ =>
          throw RLPException(
            "Cannot decode Blob network wrapper: expected [txBody, blobs, commitments, proofs]",
            rlp
          )

  // --- SetCode (0x04, EIP-7702, ETH-family, RX-L1-12) -----------------------------------------------------------

  given RLPCodec[SetCode] = new RLPCodec[SetCode]:
    def encode(tx: SetCode): RLPEncodeable =
      PrefixedRLPEncodable(
        0x04,
        RLPList(
          RLPEncoder.encode(tx.chainId),
          RLPEncoder.encode(tx.nonce),
          RLPEncoder.encode(tx.maxPriorityFeePerGas),
          RLPEncoder.encode(tx.maxFeePerGas),
          RLPEncoder.encode(tx.gasLimit),
          RLPEncoder.encode(tx.to),
          RLPEncoder.encode(tx.value),
          RLPEncoder.encode(tx.payload),
          RLPEncoder.encode(tx.accessList),
          RLPEncoder.encode(tx.authorizationList),
          RLPEncoder.encode(tx.signature.v),
          RLPEncoder.encode(tx.signature.r),
          RLPEncoder.encode(tx.signature.s)
        )
      )
    def decode(rlp: RLPEncodeable): SetCode = rlp match
      case RLPList(chainId, nonce, maxPrio, maxFee, gasLimit, to, value, payload, accessList, authList, v, r, s) =>
        SetCode(
          chainId = RLPDecoder.decode[ChainId](chainId),
          nonce = RLPDecoder.decode[UInt256](nonce),
          maxPriorityFeePerGas = RLPDecoder.decode[Wei](maxPrio),
          maxFeePerGas = RLPDecoder.decode[Wei](maxFee),
          gasLimit = RLPDecoder.decode[UInt256](gasLimit),
          to = RLPDecoder.decode[Address](to),
          value = RLPDecoder.decode[Wei](value),
          payload = RLPDecoder.decode[ByteString](payload),
          accessList = RLPDecoder.decode[List[AccessListEntry]](accessList),
          authorizationList = RLPDecoder.decode[List[SetCodeAuthorization]](authList),
          signature = ECDSASignature(
            r = RLPDecoder.decode[BigInt](r),
            s = RLPDecoder.decode[BigInt](s),
            v = RLPDecoder.decode[BigInt](v)
          )
        )
      case list: RLPList =>
        throw RLPException(s"Cannot decode SetCode transaction: expected 13 elements, got ${list.items.length}", rlp)
      case _ => throw RLPException("Cannot decode SetCode transaction: expected an RLPList", rlp)

  // --- the aggregate `Transaction` codec ------------------------------------------------------------------------
  // `decode(rlp: RLPEncodeable)` can only ever see a bare `RLPList` here (a typed tx's leading type byte is
  // consumed *before* AST parsing — see `Transaction.decode(bytes)` below); it is therefore always the Legacy
  // shape. Use `Transaction.decode(bytes: Array[Byte])` for EIP-2718 dispatch.

  given RLPCodec[Transaction] = new RLPCodec[Transaction]:
    def encode(tx: Transaction): RLPEncodeable = tx match
      case l: Legacy     => RLPEncoder.encode(l)
      case a: AccessList => RLPEncoder.encode(a)
      case d: DynamicFee => RLPEncoder.encode(d)
      case b: Blob       => RLPEncoder.encode(b) // consensus form (blobConsensusCodec) — tx.hash uses this
      case s: SetCode    => RLPEncoder.encode(s)

    def decode(rlp: RLPEncodeable): Transaction = rlp match
      case list: RLPList => RLPDecoder.decode[Legacy](list)
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
    if first >= 0xc0 then RLPDecoder.decode[Legacy](com.chipprbots.fukuii.rlp.rawDecodeStrict(bytes))
    else
      first match
        case 0x01 => RLPDecoder.decode[AccessList](com.chipprbots.fukuii.rlp.rawDecodeStrict(bytes.tail))
        case 0x02 => RLPDecoder.decode[DynamicFee](com.chipprbots.fukuii.rlp.rawDecodeStrict(bytes.tail))
        // 0x03 → the **consensus** form (blobConsensusCodec, the default given); the network wrapper is never
        // reached by binary-envelope decode — it is a wire-layer concern selected explicitly at L6.
        case 0x03 => RLPDecoder.decode[Blob](com.chipprbots.fukuii.rlp.rawDecodeStrict(bytes.tail))
        case 0x04 => RLPDecoder.decode[SetCode](com.chipprbots.fukuii.rlp.rawDecodeStrict(bytes.tail))
        case other =>
          throw RLPException(
            f"Unrecognized transaction type byte 0x$other%02x — not a legacy list header (>= 0xc0) and not a " +
              "known EIP-2718 type (0x01-0x04); rejected, not treated as legacy"
          )
