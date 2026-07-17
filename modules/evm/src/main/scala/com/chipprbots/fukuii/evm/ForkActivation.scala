package com.chipprbots.fukuii.evm

/** The activation axis for a scheduled protocol proposal (reth `ForkCondition`).
  *
  * A proposal activates on exactly one of three axes, or not at all:
  *   - `ByBlock` — block-number dispatch (PoW/ETC forks).
  *   - `ByTimestamp` — timestamp dispatch (post-Merge/ETH forks, EIP-6122).
  *   - `ByTotalDifficulty` — the PoS transition ("the Merge" is the ETH-family's local label for it); no EVM proposal
  *     gates on this axis, so the Merge does not change the derived [[EvmConfig]].
  *   - `Never` — the proposal is absent on this chain (reth `ForkCondition::Never`).
  *
  * The axes are kept **type-distinct as cases** — this is the multi-network-safe form (what "don't conflate the axes"
  * actually means): never a flat geth-style `Rules` bool-struct, never a separate `forTimestamp` method.
  *
  * The coordinate types match the built L1 [[com.chipprbots.fukuii.domain.BlockHeader]] fields — `number: BigInt`,
  * `unixTimestamp: Long` — plain primitives rather than opaque `BlockNumber`/`Timestamp`/`TotalDifficulty` wrapper
  * types, since L1 does not define those types.
  */
enum ForkActivation:
  case ByBlock(number: BigInt)
  case ByTimestamp(seconds: Long)
  case ByTotalDifficulty(ttd: BigInt)
  case Never

  /** Whether this activation has taken effect at the given dispatch point. Each axis compares against its own
    * coordinate (`>=` block per fork height, `>=` timestamp per EIP-6122, `>=` total-difficulty per the PoS
    * transition); `Never` is inactive everywhere.
    */
  def isActiveAt(block: BigInt, timestamp: Long, td: BigInt): Boolean = this match
    case ByBlock(n)           => block >= n
    case ByTimestamp(t)       => timestamp >= t
    case ByTotalDifficulty(x) => td >= x
    case Never                => false
