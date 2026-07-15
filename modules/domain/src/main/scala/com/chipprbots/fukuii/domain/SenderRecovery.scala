package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.crypto.curve
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.crypto.pubKeyToAddress
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPEncoder
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.encode as rlpEncode

/** Why sender recovery failed — a total, representable outcome, never a thrown exception. go-ethereum collapses every
  * one of these into a single `ErrInvalidSig`; L1 keeps them distinct so the caller (txpool admission, block
  * validation) can log precisely which rule the signature broke.
  */
enum SigError:
  /** `r < 1`, `s < 1`, `r >= N`, or `s >= N` — a range violation (go-ethereum `crypto/crypto.go` `r.Cmp(Big1) < 0`,
    * `r.Cmp(secp256k1N) < 0`).
    */
  case InvalidRange

  /** `s > N/2` on a homestead-and-later block — the EIP-2 low-S malleability rule (go-ethereum `crypto/crypto.go`
    * `homestead && s.Cmp(secp256k1halfN) > 0`). Legal (`homestead = false`) only for pre-1,150,000 Frontier-era blocks
    * shared by ETC and ETH.
    */
  case HighS

  /** The recovery id `v` is not `0` or `1` after EIP-155/2718 normalization, or a legacy `v` is neither `27/28` nor a
    * valid protected value (`>= 35`).
    */
  case InvalidRecoveryId

  /** `ValidateSignatureValues` passed but the elliptic-curve point recovery produced no valid public key (bad `r`
    * x-coordinate, order-check failure, or a recovery to the point at infinity).
    */
  case RecoveryFailed

/** EIP-155 sender recovery + the N-1 `ValidateSignatureValues` gate (`plan/L1.md` §5, §7; RX-L1-09/10/12).
  *
  * The consensus surface that turns a signed [[Transaction]] into the address that authored it. Every step is
  * byte-cited to go-ethereum `core/types/transaction_signing.go` + `crypto/crypto.go` and core-geth's identical copies
  * — a wrong sighash or a wrong `v`-unwind silently yields the wrong sender, which corrupts the tx-trie root and the
  * block hash (an F-BN-1-class chain split). Nothing here throws: recovery returns `Either[SigError, Address]`.
  *
  * **The `homestead` flag is BLOCK-GATED, not hard-coded (H-1, forge gate 2026-07-14).** ETC and ETH share pre-DAO
  * history, so blocks 0–1,149,999 are Frontier-era on both chains where high-S signatures are legal. All three
  * reference clients select the signer by block number (`MakeSigner` → Frontier/Homestead/EIP155; go-ethereum
  * `transaction_signing.go:52-57`, `FrontierSigner.Sender → recoverPlain(...,false):443`; core-geth `:40-56`
  * identical). The caller therefore plumbs a `homestead: Boolean` derived from block context into [[getSender]]; a
  * from-genesis archival node re-executing the pre-1.15M range MUST pass `homestead = false` for those blocks or it
  * fails to reconstruct the canonical chain. Typed (EIP-2718) transactions did not exist before homestead, so —
  * matching geth's per-type signers, which hard-code `recoverPlain(..., true)` — recovery forces `homestead = true` for
  * every typed variant regardless of the plumbed flag; the flag governs the Legacy path only.
  */
object SenderRecovery:

  /** The secp256k1 group order `N` and its half — the malleability boundary. Computed from the same BouncyCastle curve
    * the L0 signer uses (`ECDSASignature.toCanonicalS` derives `halfCurveOrder` identically), so the N-1 gate and the
    * signer agree to the bit.
    */
  private val N: BigInt = BigInt(curve.getN)
  private val halfN: BigInt = N >> 1

  /** The N-1 gate — byte-exact to go-ethereum `crypto/crypto.go` `ValidateSignatureValues(v, r, s, homestead)` and
    * core-geth's identical copy: reject `r < 1 || s < 1`; reject `s > N/2` when `homestead`; require `r < N && s < N &&
    * v ∈ {0,1}`. Returns `None` when the signature values are admissible, `Some(err)` otherwise. Run **before**
    * recovery (core-geth `recoverPlain:615` validates before `Ecrecover:625`).
    *
    * @param yParity
    *   the normalized recovery id (`0` or `1`); any other value fails as [[SigError.InvalidRecoveryId]].
    */
  def validateSignatureValues(yParity: Int, r: BigInt, s: BigInt, homestead: Boolean): Option[SigError] =
    if r < 1 || s < 1 then Some(SigError.InvalidRange)
    else if homestead && s > halfN then Some(SigError.HighS)
    else if !(r < N && s < N) then Some(SigError.InvalidRange)
    else if !(yParity == 0 || yParity == 1) then Some(SigError.InvalidRecoveryId)
    else None

  /** Gate then recover: `ValidateSignatureValues` → `Ecrecover` → `Keccak256(pub)[12:]`. The point sign handed to
    * `ECDSASignature.recoverPubBytes` is `27 + yParity` (its 27/28 convention); recovery returning `None` maps to
    * [[SigError.RecoveryFailed]] (go-ethereum `recoverPlain` `invalid public key`).
    */
  private def recover(
      sigHash: Array[Byte],
      yParity: Int,
      r: BigInt,
      s: BigInt,
      homestead: Boolean
  ): Either[SigError, Address] =
    validateSignatureValues(yParity, r, s, homestead) match
      case Some(err) => Left(err)
      case None =>
        val pointSign = (yParity + 27).toByte
        ECDSASignature.recoverPubBytes(r, s, pointSign, sigHash) match
          case Some(pub) => Right(pubKeyToAddress(pub))
          case None      => Left(SigError.RecoveryFailed)

  // --- per-variant signing hash (the signed message) ------------------------------------------------------------
  // Legacy pre-EIP-155  = keccak(RLP([nonce, gasPrice, gasLimit, to, value, data]))                (6 elems)
  //   go-ethereum FrontierSigner.Hash (transaction_signing.go), core-geth :589-598.
  // Legacy EIP-155      = keccak(RLP([nonce, gasPrice, gasLimit, to, value, data, chainId, 0, 0]))  (9 elems)
  //   go-ethereum EIP155Signer.Hash, core-geth :517-527. The trailing `0, 0` are `uint(0)` → RLP empty string (0x80).
  // Typed 0x01/0x02/0x03/0x04 = keccak(typeByte ‖ RLP([payload without v,r,s]))
  //   go-ethereum {eip2930,eip1559,eip4844,setCode}Signer.Hash (prefixedRlpHash), core-geth :432/358/232/291.

  private def legacyPre155SigHash(tx: Transaction.Legacy): Array[Byte] =
    val body = RLPList(
      RLPEncoder.encode(tx.nonce),
      RLPEncoder.encode(tx.gasPrice),
      RLPEncoder.encode(tx.gasLimit),
      Transaction.encodeTo(tx.to),
      RLPEncoder.encode(tx.value),
      RLPEncoder.encode(tx.payload)
    )
    kec256(rlpEncode(body))

  private def legacy155SigHash(tx: Transaction.Legacy, chainId: ChainId): Array[Byte] =
    val body = RLPList(
      RLPEncoder.encode(tx.nonce),
      RLPEncoder.encode(tx.gasPrice),
      RLPEncoder.encode(tx.gasLimit),
      Transaction.encodeTo(tx.to),
      RLPEncoder.encode(tx.value),
      RLPEncoder.encode(tx.payload),
      RLPEncoder.encode(chainId),
      RLPEncoder.encode(BigInt(0)),
      RLPEncoder.encode(BigInt(0))
    )
    kec256(rlpEncode(body))

  private def accessListSigHash(tx: Transaction.AccessList): Array[Byte] =
    val body = RLPList(
      RLPEncoder.encode(tx.chainId),
      RLPEncoder.encode(tx.nonce),
      RLPEncoder.encode(tx.gasPrice),
      RLPEncoder.encode(tx.gasLimit),
      Transaction.encodeTo(tx.to),
      RLPEncoder.encode(tx.value),
      RLPEncoder.encode(tx.payload),
      RLPEncoder.encode(tx.accessList)
    )
    kec256(0x01.toByte +: rlpEncode(body))

  private def dynamicFeeSigHash(tx: Transaction.DynamicFee): Array[Byte] =
    val body = RLPList(
      RLPEncoder.encode(tx.chainId),
      RLPEncoder.encode(tx.nonce),
      RLPEncoder.encode(tx.maxPriorityFeePerGas),
      RLPEncoder.encode(tx.maxFeePerGas),
      RLPEncoder.encode(tx.gasLimit),
      Transaction.encodeTo(tx.to),
      RLPEncoder.encode(tx.value),
      RLPEncoder.encode(tx.payload),
      RLPEncoder.encode(tx.accessList)
    )
    kec256(0x02.toByte +: rlpEncode(body))

  private def blobSigHash(tx: Transaction.Blob): Array[Byte] =
    val body = RLPList(
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
      RLPEncoder.encode(tx.blobVersionedHashes)
    )
    kec256(0x03.toByte +: rlpEncode(body))

  private def setCodeSigHash(tx: Transaction.SetCode): Array[Byte] =
    val body = RLPList(
      RLPEncoder.encode(tx.chainId),
      RLPEncoder.encode(tx.nonce),
      RLPEncoder.encode(tx.maxPriorityFeePerGas),
      RLPEncoder.encode(tx.maxFeePerGas),
      RLPEncoder.encode(tx.gasLimit),
      RLPEncoder.encode(tx.to),
      RLPEncoder.encode(tx.value),
      RLPEncoder.encode(tx.payload),
      RLPEncoder.encode(tx.accessList),
      RLPEncoder.encode(tx.authorizationList)
    )
    kec256(0x04.toByte +: rlpEncode(body))

  /** The signing hash (signed message) of a transaction — the input to `Ecrecover`. For a Legacy transaction the form
    * (pre-EIP-155 6-element vs EIP-155 9-element) and the chainId are derived from the signature's `v` (see
    * [[getSender]]); for a typed transaction the chainId is a field of the envelope.
    */
  def signingHash(tx: Transaction): Array[Byte] = tx match
    case l: Transaction.Legacy     => legacyUnwind(l).map(_._2).getOrElse(legacyPre155SigHash(l))
    case a: Transaction.AccessList => accessListSigHash(a)
    case d: Transaction.DynamicFee => dynamicFeeSigHash(d)
    case b: Transaction.Blob       => blobSigHash(b)
    case s: Transaction.SetCode    => setCodeSigHash(s)

  // --- v-unwind -------------------------------------------------------------------------------------------------

  /** Unwind a Legacy `v` to `(yParity, signingHash)`.
    *
    *   - `v ∈ {27, 28}` → unprotected (pre-EIP-155): `yParity = v - 27`, 6-element Frontier sighash. (go-ethereum
    *     `Homestead/FrontierSigner`; core-geth `deriveChainId` `v==27||28 → 0`.)
    *   - `v >= 35` → EIP-155 protected: `chainId = (v - 35) / 2`, `yParity = (v - 35) mod 2`, 9-element sighash with
    *     that chainId. (go-ethereum `EIP155Signer` `V.Sub(V, chainIdMul); V.Sub(V, big8)`; core-geth `deriveChainId`
    *     `(v - 35) / 2`.) The chainId is recovered from `v` itself, so the recovered sender is self-consistent with the
    *     signer that produced it — network chainId admissibility (`have %d want %d`) is an L4/L5 check, not recovery.
    *   - any other `v` (e.g. `29..34`, `< 27`) → [[SigError.InvalidRecoveryId]].
    */
  private def legacyUnwind(tx: Transaction.Legacy): Either[SigError, (Int, Array[Byte])] =
    val v = tx.signature.v
    if v == BigInt(27) || v == BigInt(28) then Right(((v - 27).toInt, legacyPre155SigHash(tx)))
    else if v >= BigInt(35) then
      val chainId = (v - 35) / 2
      val yParity = (v - 35 - chainId * 2).toInt // == (v - 35) mod 2 ∈ {0,1}
      Right((yParity, legacy155SigHash(tx, ChainId(chainId))))
    else Left(SigError.InvalidRecoveryId)

  /** The bare typed-tx recovery id: a valid typed `v` is exactly `0` or `1`; anything else returns `-1`, which
    * [[validateSignatureValues]] rejects as [[SigError.InvalidRecoveryId]] (guards against a `.toInt` of a large `v`
    * wrapping into a spurious `0`/`1`).
    */
  private def typedYParity(v: BigInt): Int =
    if v == BigInt(0) then 0 else if v == BigInt(1) then 1 else -1

  /** Recover the address that signed a transaction. Runs the N-1 [[validateSignatureValues]] gate **before** recovery.
    * `homestead` governs the Legacy path only (typed variants force `homestead = true`, matching geth's per-type
    * signers). Never throws.
    */
  def getSender(tx: Transaction, homestead: Boolean): Either[SigError, Address] = tx match
    case l: Transaction.Legacy =>
      legacyUnwind(l).flatMap((yParity, sh) => recover(sh, yParity, l.signature.r, l.signature.s, homestead))
    case a: Transaction.AccessList =>
      recover(accessListSigHash(a), typedYParity(a.signature.v), a.signature.r, a.signature.s, homestead = true)
    case d: Transaction.DynamicFee =>
      recover(dynamicFeeSigHash(d), typedYParity(d.signature.v), d.signature.r, d.signature.s, homestead = true)
    case b: Transaction.Blob =>
      recover(blobSigHash(b), typedYParity(b.signature.v), b.signature.r, b.signature.s, homestead = true)
    case s: Transaction.SetCode =>
      recover(setCodeSigHash(s), typedYParity(s.signature.v), s.signature.r, s.signature.s, homestead = true)

  /** Recover the authorizing account of an EIP-7702 authorization tuple — the **second, independent** recovery surface
    * from the outer [[Transaction.SetCode]] signature. Byte-exact to go-ethereum `tx_setcode.go` `Authority()`:
    * `ValidateSignatureValues(v, r, s, true)` — `homestead` is **always true** here (`:121`), the sighash is the
    * `keccak256(0x05 ‖ RLP([chainId, address, nonce]))` magic-byte-`0x05` hash (`SetCodeAuthorization.sigHash`), and
    * the recovery id is the stored `yParity`. (ETH-family / beacon-gated.)
    */
  def recoverAuthority(auth: SetCodeAuthorization): Either[SigError, Address] =
    recover(auth.sigHash.toArray, auth.yParity.toInt, auth.r.toBigInt, auth.s.toBigInt, homestead = true)

/** Sender-recovery + consensus-hash extension methods on the signed [[Transaction]] envelope (`plan/L1.md` §5 —
  * `signedTx.senderAddress`, `tx.hash`). No `implicit class`/`implicit def`; the recovery math lives in
  * [[SenderRecovery]] and never throws.
  */
extension (tx: Transaction)

  /** The **consensus** transaction hash — `keccak256` of the consensus encoding (Legacy = `keccak(RLP)`; typed =
    * `keccak(typeByte ‖ RLP(payload))`). Uses the aggregate [[Transaction]] codec, which resolves `Blob` to its
    * **consensus** form (`blobConsensusCodec`), so the blob network-wrapper sidecar can never leak into the hashed
    * bytes (go-ethereum `transaction.go:579-588` `Hash()` → `rlpHash`/`prefixedRlpHash`; §9).
    */
  def hash: Hash = Hash(ByteString(kec256(rlpEncode(tx))))

  /** The transaction's signing hash (signed message) — see [[SenderRecovery.signingHash]]. */
  def signingHash: ByteString = ByteString(SenderRecovery.signingHash(tx))

  /** Recover the sender address, gating on `ValidateSignatureValues` first — see [[SenderRecovery.getSender]].
    * `homestead` is the block-context flag (Legacy path only; typed variants force `true`).
    */
  def getSender(homestead: Boolean): Either[SigError, Address] = SenderRecovery.getSender(tx, homestead)

extension (auth: SetCodeAuthorization)
  /** The authorizing account of this EIP-7702 authorization — see [[SenderRecovery.recoverAuthority]]. */
  def authority: Either[SigError, Address] = SenderRecovery.recoverAuthority(auth)
