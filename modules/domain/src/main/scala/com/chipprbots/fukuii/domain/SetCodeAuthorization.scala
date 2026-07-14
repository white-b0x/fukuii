package com.chipprbots.fukuii.domain

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256

/** An EIP-7702 authorization tuple — an account's delegation of its own code to `address`, itself signed with its own
  * `(chainId, address, nonce)` sighash: a **second, independent** signing/recovery surface from the outer
  * [[Transaction.SetCode]] transaction signature (geth `core/types/tx_setcode.go:61-72` `SetCodeAuthorization{ ChainID,
  * Address, Nonce, V, R, S }`).
  *
  * Modelled here as the plain data shape only, per `plan/L1.md` §7's phase split — its own RLP codec and
  * `SigHash`/recovery are phase-2b (ETH-family, beacon-gated), matching [[Transaction.SetCode]]'s own RLP given being
  * unimplemented until then.
  */
final case class SetCodeAuthorization(
    chainId: ChainId,
    address: Address,
    nonce: UInt256,
    yParity: Byte,
    r: UInt256,
    s: UInt256
)
