package com.chipprbots.fukuii.domain

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given

/** A validator withdrawal from the consensus layer (EIP-4895) — the ETH-family, post-Shanghai payout record carried in
  * the block body's trailing-optional withdrawals list.
  *
  * Exactly the four consensus fields go-ethereum `core/types/withdrawal.go:31-36` RLP-encodes, in that order: `Index`
  * (monotonic id from the CL) → `Validator` (validator index) → `Address` (target) → `Amount` (Gwei). A straight field
  * list — the naive `derives RLPCodec` DEFAULT (unlike [[BlockBody]]/[[BlockHeader]], it has no trailing-optional). ETC
  * bodies never carry withdrawals (a pre-merge, PoW chain); admissibility is an L4/L5 policy decision, not a modelling
  * omission.
  */
final case class Withdrawal(
    index: Long,
    validatorIndex: Long,
    address: Address,
    amount: Long
) derives RLPCodec
