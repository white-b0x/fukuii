package com.chipprbots.fukuii.evm

/** Skeleton placeholder for the `evm` module (L3 → domain, crypto, rlp). */
object Evm

/** Number of 32-byte EVM words needed to hold `n` bytes — `ceil(n / 32)`. The AS-IS `july-fourth` package-level helper,
  * transcribed unchanged: it is the gas-cost multiplier for every word-metered opcode (SHA3, `*COPY`, MCOPY, LOG,
  * initcode metering). Network-neutral and fork-invariant.
  */
def wordsForBytes(n: BigInt): BigInt =
  if n <= 0 then BigInt(0) else (n - 1) / 32 + 1
