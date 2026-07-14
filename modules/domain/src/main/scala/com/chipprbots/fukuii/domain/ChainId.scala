package com.chipprbots.fukuii.domain

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

  extension (c: ChainId) def toBigInt: BigInt = c
