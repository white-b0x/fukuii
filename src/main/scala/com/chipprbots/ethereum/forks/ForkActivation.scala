package com.chipprbots.ethereum.forks

import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TotalDifficulty

/** L3 activation axis for a scheduled proposal (Batch 5 framework §1.4, reth `ForkCondition`).
  *
  * A proposal activates on exactly one of three axes, or not at all:
  *   - `ByBlock` — block-number dispatch (PoW/ETC forks).
  *   - `ByTimestamp` — timestamp dispatch (post-Merge/ETH forks, EIP-6122).
  *   - `ByTotalDifficulty` — the PoS transition ("the Merge" is `EthFamily`'s family-local label for it).
  *   - `Never` — the proposal is absent on this chain (reth `ForkCondition::Never`); a foreign-family fork-name field
  *     parked at a sentinel (`1e18` / `Long.MaxValue`) derives to `Never`.
  *
  * The opaque `BlockNumber`/`Timestamp`/`TotalDifficulty` types (S11) are used throughout — never raw `BigInt`/`Long`.
  */
enum ForkActivation:
  case ByBlock(number: BlockNumber)
  case ByTimestamp(seconds: Timestamp)
  case ByTotalDifficulty(ttd: TotalDifficulty)
  case Never

  /** Whether this activation has taken effect at the given dispatch point. Each axis compares against its own
    * coordinate; `Never` is inactive everywhere. Mirrors the existing production predicates (`>=` block, `>=` timestamp
    * per EIP-6122, `>=` total-difficulty per `BlockchainConfig.isPoS`).
    */
  def isActiveAt(block: BlockNumber, timestamp: Timestamp, td: TotalDifficulty): Boolean = this match
    case ByBlock(n)           => block >= n
    case ByTimestamp(t)       => timestamp >= t
    case ByTotalDifficulty(x) => td >= x
    case Never                => false
