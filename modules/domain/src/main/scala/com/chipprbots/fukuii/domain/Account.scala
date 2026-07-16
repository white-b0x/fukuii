package com.chipprbots.fukuii.domain

import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPValue
import com.chipprbots.fukuii.rlp.encode

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

object Account:

  /** `keccak256("")` — the code hash of an account with no code,
    * `c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470`.
    *
    * Network-neutral: identical for every network (ETC, ETH) and every fork. Computed from the L0 keccak primitive over
    * the empty byte string rather than hardcoded, so the constant is self-checking. Matches go-ethereum
    * `core/types/hashes.go:32` (`crypto.Keccak256Hash(nil)`), besu `datatypes.Hash.EMPTY`, and nethermind
    * `Keccak.OfAnEmptyString`.
    */
  val EmptyCodeHash: Hash = Hash(kec256(Array.emptyByteArray))

  /** `keccak256(RLP(""))` — the state root of an empty storage trie,
    * `56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421`.
    *
    * Network-neutral and fork-invariant. The `domain` module must not depend on `trie` (that would be an illegal L1→L2
    * edge), so rather than reuse `MptNode.EmptyRootHash` this is recomputed from the same L0 primitives the trie uses —
    * `keccak256` of the RLP encoding of the empty byte string — keeping the value self-checking and byte-identical
    * without duplicating the trie dependency.
    */
  val EmptyStorageRootHash: Hash = Hash(kec256(encode(RLPValue(Array.emptyByteArray))))

  /** The empty account: zero balance, empty storage, no code, at the given starting nonce (default zero).
    *
    * Mirrors go-ethereum's empty-account constructor and nethermind `Account.TotallyEmpty`. `startNonce` is a parameter
    * because some networks initialize accounts at a non-zero nonce (EIP-161 account-start-nonce).
    */
  def empty(startNonce: UInt256 = UInt256.Zero): Account =
    Account(
      nonce = startNonce,
      balance = Wei.Zero,
      storageRoot = EmptyStorageRootHash,
      codeHash = EmptyCodeHash
    )
