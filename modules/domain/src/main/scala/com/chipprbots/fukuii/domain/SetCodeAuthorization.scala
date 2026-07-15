package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPDecoder
import com.chipprbots.fukuii.rlp.RLPEncodeable
import com.chipprbots.fukuii.rlp.RLPEncoder
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.encode as rlpEncode

/** An EIP-7702 authorization tuple — an account's delegation of its own code to `address`, itself signed with its own
  * `(chainId, address, nonce)` sighash: a **second, independent** signing/recovery surface from the outer
  * [[Transaction.SetCode]] transaction signature (geth `core/types/tx_setcode.go:72-79` `SetCodeAuthorization{ ChainID,
  * Address, Nonce, V, R, S }`).
  *
  * The plain data shape + its RLP codec + its [[SetCodeAuthorization.sigHash]] live here (ETH-family, beacon-gated).
  * The actual `Authority()` sender-recovery (geth `tx_setcode.go:121-142`) is phase 3 — this layer builds only the
  * SigHash encoding and the round-trippable RLP form.
  */
final case class SetCodeAuthorization(
    chainId: ChainId,
    address: Address,
    nonce: UInt256,
    yParity: Byte,
    r: UInt256,
    s: UInt256
)

object SetCodeAuthorization:

  /** The EIP-7702 authorization magic byte — `0x05` (`SetCodeMagicByte`), the domain-separation prefix of the
    * authorization SigHash. **NOT** the SetCode transaction type id `0x04` — geth `tx_setcode.go:112-118`
    * `prefixedRlpHash(0x05, ...)` (a bare `0x05` literal, distinct from `SetCodeTxType`).
    */
  val MagicByte: Byte = 0x05

  /** RLP codec — `RLPList(chainId, address, nonce, yParity, r, s)`, field order byte-exact to geth
    * `tx_setcode.go:72-79` (ChainID uint256 scalar, Address 20-byte value, Nonce uint64/minimal scalar, V=yParity byte
    * scalar, R/S uint256 scalars). Hand-written rather than `derives` to keep the typed-tx family's codecs uniformly
    * explicit.
    */
  given RLPCodec[SetCodeAuthorization] = new RLPCodec[SetCodeAuthorization]:
    def encode(auth: SetCodeAuthorization): RLPEncodeable =
      RLPList(
        RLPEncoder.encode(auth.chainId),
        RLPEncoder.encode(auth.address),
        RLPEncoder.encode(auth.nonce),
        RLPEncoder.encode(auth.yParity),
        RLPEncoder.encode(auth.r),
        RLPEncoder.encode(auth.s)
      )
    def decode(rlp: RLPEncodeable): SetCodeAuthorization = rlp match
      case RLPList(chainId, address, nonce, yParity, r, s) =>
        SetCodeAuthorization(
          chainId = RLPDecoder.decode[ChainId](chainId),
          address = RLPDecoder.decode[Address](address),
          nonce = RLPDecoder.decode[UInt256](nonce),
          yParity = RLPDecoder.decode[Byte](yParity),
          r = RLPDecoder.decode[UInt256](r),
          s = RLPDecoder.decode[UInt256](s)
        )
      case list: RLPList =>
        throw RLPException(
          s"Cannot decode SetCodeAuthorization: expected 6 elements, got ${list.items.length}",
          rlp
        )
      case _ => throw RLPException("Cannot decode SetCodeAuthorization: expected an RLPList", rlp)

  /** The signing hash of an authorization: `keccak256(0x05 ‖ RLP([chainId, address, nonce]))` — byte-exact to geth
    * `tx_setcode.go:112-118` `SigHash()` (`prefixedRlpHash(0x05, [ChainID, Address, Nonce])`). Only the `(chainId,
    * address, nonce)` triple is signed; `yParity/r/s` are the signature *over* this hash, not inputs to it.
    *
    * ⚠️ The prefix is the authorization [[MagicByte]] `0x05`, NOT the SetCode transaction type `0x04`.
    */
  def sigHash(chainId: ChainId, address: Address, nonce: UInt256): Hash =
    val body = RLPList(
      RLPEncoder.encode(chainId),
      RLPEncoder.encode(address),
      RLPEncoder.encode(nonce)
    )
    Hash(ByteString(kec256(MagicByte +: rlpEncode(body))))

  extension (auth: SetCodeAuthorization)
    /** This authorization's [[SetCodeAuthorization.sigHash]]. */
    def sigHash: Hash = SetCodeAuthorization.sigHash(auth.chainId, auth.address, auth.nonce)
