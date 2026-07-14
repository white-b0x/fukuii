package com.chipprbots.fukuii.domain

import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.bigIntCodec

/** An EIP-155 chain identifier — opaque *data*, never a type-level network family. Whether a node is running ETC,
  * Mordor, ETH mainnet, or Sepolia is a **runtime** configuration value carried in this type, not a compile-time type
  * parameter (R2: a single binary hosts N possibly-different-family instances at runtime; the reth
  * `NodePrimitives`/`NodeTypes` compile-time monomorphization this rules out is named-and-avoided at `plan/L1.md`
  * §2/§9).
  *
  * Values: ETC mainnet = 61, Mordor = 63 (core-geth `params/config_classic.go:39`, `config_mordor.go:35` — ETC-frozen,
  * sole authority); ETH mainnet = 1, Sepolia = 11155111 (go-ethereum).
  *
  * Type-distinct opaque wrapper over `BigInt` so a chain ID cannot be silently mixed with an arbitrary `BigInt` (a
  * block number, a gas value) — the chain ID feeds the EIP-155 sighash, where a wrong value silently produces the wrong
  * sender (a consensus bug the type distinction guards against, not merely a naming convenience).
  */
opaque type ChainId = BigInt

object ChainId:

  def apply(id: BigInt): ChainId =
    require(id >= 0, s"ChainId must be non-negative, got $id")
    id

  def apply(id: Long): ChainId = apply(BigInt(id))

  given Ordering[ChainId] = math.Ordering.BigInt

  /** RLP scalar codec, delegating to `BigInt`'s minimal-length big-endian scalar encoding — the same rule the EIP-155
    * sighash and the typed-tx envelope's `chainId` field both use. Built via `xmap` over the named `bigIntCodec` (not
    * `summon`) for the same reason [[Wei]]'s given is: opaque transparency would otherwise loop the implicit search.
    */
  given RLPCodec[ChainId] = bigIntCodec.xmap(ChainId.apply, _.toBigInt)

  extension (c: ChainId) def toBigInt: BigInt = c
