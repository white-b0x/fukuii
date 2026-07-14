package com.chipprbots.fukuii.domain

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given

/** An EIP-2930 access-list entry — an address plus the storage slots a transaction pre-declares access to.
  *
  * A straight two-field product, byte-exact to go-ethereum `core/types/tx_access_list.go` `AccessTuple{ Address
  * common.Address; StorageKeys []common.Hash }` — a genuine `derives RLPCodec` candidate (no trailing-optional /
  * fork-conditional shape, unlike [[Transaction]]'s own envelope).
  */
final case class AccessListEntry(address: Address, storageKeys: List[Hash]) derives RLPCodec
