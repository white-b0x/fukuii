package com.chipprbots.fukuii.domain

import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given

/** The Ethereum consensus state-account record — the value stored in the state trie under an address's hashed key.
  *
  * Field order is the RLP order: **Nonce → Balance → StorageRoot → CodeHash**, matching go-ethereum
  * `core/types/state_account.go:31-35` (`StateAccount{ Nonce uint64; Balance *uint256.Int; Root common.Hash; CodeHash
  * []byte }`) and besu `datatypes/AccountValue.java`.
  *
  * `balance` is [[Wei]] — the L0 [[UInt256]] 32-byte-bounded width, **not** an unbounded `BigInt` — because geth's
  * state balance is `*uint256.Int`; the state-account RLP and the state-trie leaf hash depend on this width.
  */
final case class Account(
    nonce: UInt256,
    balance: Wei,
    storageRoot: Hash,
    codeHash: Hash
) derives RLPCodec
