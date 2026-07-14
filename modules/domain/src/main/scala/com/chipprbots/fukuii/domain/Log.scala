package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given

/** A contract log event — the LOG opcode's consensus output.
  *
  * Exactly the three consensus fields go-ethereum `core/types/log.go:31-38` RLP-encodes: `Address` (the emitting
  * contract), `Topics` (indexed by the contract), `Data` (the ABI-encoded payload). Block/tx metadata (`BlockNumber`,
  * `TxHash`, `TxIndex`, `BlockHash`, ...) is derived by the node and tagged `rlp:"-"` there — not part of the consensus
  * RLP, and not modelled here.
  */
final case class Log(
    address: Address,
    topics: List[Hash],
    data: ByteString
) derives RLPCodec
