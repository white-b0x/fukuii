package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given

/** A canonical block's accumulated PoW chain weight — the locally-computed, PoW-validated total difficulty the
  * heaviest-chain fork choice compares against (never a peer's claimed value; L6 §5 TD-sourcing invariant, `Namespace`
  * `ChainWeight` scaladoc). Minimal here: a single `totalDifficulty`. The AS-IS `ChainWeight` also carried a
  * last-checkpoint number for ECIP-1100/MESS scoring — added when that scoring lands (L5), not needed for the P6 atomic
  * write.
  *
  * ==PROVISIONAL placement.==
  * `ChainWeight` is described as an L2 storage-tier type (its column family is `Namespace.ChainWeight`), but no value
  * type exists there yet. It is defined here, scoped to [[AtomicBlockWriter]] (the L4 durability concern that must
  * write it atomically with its block), until its permanent home — L1 `domain` (a chain-weight value) or an L2
  * `storage` value + its `ChainWeightStorage` — is settled cross-layer. The byte shape here is minimal and must be
  * revisited when that home is chosen; not a frozen contract.
  *
  * @param totalDifficulty
  *   Σ block difficulty from genesis to this block (core-geth `"diffs"` ancient table; besu total-difficulty record).
  */
final case class ChainWeight(totalDifficulty: BigInt) derives RLPCodec
