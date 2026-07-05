package com.chipprbots.ethereum.domain

opaque type ChainId = BigInt

object ChainId:
  def apply(v: BigInt): ChainId = v

  extension (c: ChainId) def value: BigInt = c

  // Pin the BigInt ordering explicitly — avoids self-referential lazy-val init deadlock
  // that occurs when Ordering.by(_.value) resolves the implicit to the given being defined
  // (same footgun as Nonce/Wei/TotalDifficulty/etc. — see OpaqueOrderingResolutionSpec, which
  // hung the JVM on this exact given before this fix).
  given Ordering[ChainId] = Ordering.by[ChainId, BigInt](_.value)(using scala.math.Ordering.BigInt)
