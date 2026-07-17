package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.evm.AccountStorage
import com.chipprbots.fukuii.evm.WorldState

/** The **sole economics seam** between family-agnostic execution and family-specific consensus issuance — declared **in
  * `execution`**, implementation carried by the per-fork [[ProtocolSpec]] bundle. This placement is the `ledger ↔
  * consensus` **DAG inversion** (L4 plan §6 row 1 / §9): the reward trait lives here and consensus (L5) calls *down*
  * into it; an upward `import ...consensus...` would re-form the old 13-package cycle and is a compile error.
  *
  * The single method mirrors besu's one abstract `rewardCoinbase` — the only economics delegation in an otherwise
  * concrete block loop (besu `AbstractBlockProcessor.java:652` `abstract boolean rewardCoinbase(...)`, called once at
  * `:483`). ETC's ECIP-1017 emission goes in *exactly* the slot ETH's zero-reward goes; the family split is *which*
  * scheme is bound at the seam, never an `if(isPoW)` scattered through the pipeline (L4 plan §2 v1, RX-L4-01/07).
  * besu-etc's own `ClassicBlockProcessor extends AbstractBlockProcessor` overriding **only** `rewardCoinbase`
  * (`eb4248c997`) is the strongest proof this one seam carries both families.
  *
  * **P1 scope:** the seam and its two case identities are declared; the ECIP-1017 era **math** is deferred to P4
  * (forge-gated, byte-exact vs core-geth `rewards_classic.go` + besu-etc `ClassicBlockProcessor.java`). Selection must
  * **fail LOUD** on an unresolved scheme ([[RewardScheme.require]]) — never nethermind's silent zero-reward
  * (`fail-loud-invariants.md`, L4 plan §6 row 6 / §9).
  */
sealed trait RewardScheme:

  /** Apply this block's issuance to `world` and return the mutated world — the last state mutation before commitment
    * (besu reward `:483` → persist `:532`). Generic over the [[WorldState]] self-type so the seam is state-backing
    * agnostic (mirrors besu's `MutableWorldState` parameter). `ommers` are the block's uncles (ECIP-1017 nephew/uncle
    * rewards on the PoW path; unused on the zero-reward PoS path).
    */
  def rewardBlock[WS <: WorldState[WS, S], S <: AccountStorage[S]](
      world: WS,
      header: BlockHeader,
      ommers: Seq[BlockHeader]
  ): WS

object RewardScheme:

  /** PoS / ETH — **zero issuance**. `blockReward = 0` and `skipZeroBlockRewards = true`: the empty coinbase must
    * **not** be touched, because a spurious `addBalance(0)` would dirty the account and shift the state root (besu
    * threads `skipZeroBlockRewards` into the abstract method at `AbstractBlockProcessor.java:483`; RX-L4-01/07). This
    * is why the P1 body is a genuine **no-op that returns `world` untouched** — not deferred math, but the correct
    * inert behavior.
    */
  case object PosNoRewardScheme extends RewardScheme:
    val blockReward: BigInt = BigInt(0)
    val skipZeroBlockRewards: Boolean = true

    def rewardBlock[WS <: WorldState[WS, S], S <: AccountStorage[S]](
        world: WS,
        header: BlockHeader,
        ommers: Seq[BlockHeader]
    ): WS =
      // skipZeroBlockRewards + zero reward ⇒ the coinbase is never touched (no addBalance(0), no account dirtied).
      world

  /** ETC — **ECIP-1017 era emission** (5 → 4 → 3.2 → … ETH, 20% step-down every era). DECLARED here so the seam and the
    * bundle wire in P1; the byte-exact era math (integer era index `(blockNumber-1)/eraLength`, separate integer
    * `4^era`/`5^era` before multiply-divide, the Era-0-vs-≥1 uncle/nephew formula switch) is **P4** — forge-gated,
    * reproduced byte-for-byte from core-geth `rewards_classic.go` and cross-checked against besu-etc
    * `ClassicBlockProcessor.java` (L4 plan §2 v6 / §6 / §9, RX-L4-06). P4 will likely turn this `case object` into a
    * `case class` carrying the chain's monetary-policy config (era length, base reward, treasury) once L4's config
    * types exist.
    */
  case object Ecip1017RewardScheme extends RewardScheme:
    def rewardBlock[WS <: WorldState[WS, S], S <: AccountStorage[S]](
        world: WS,
        header: BlockHeader,
        ommers: Seq[BlockHeader]
    ): WS =
      sys.error(
        "ECIP-1017 era emission is P4 (forge-gated, byte-exact vs core-geth rewards_classic.go + besu-etc " +
          "ClassicBlockProcessor.java) — not implemented in L4 P1"
      )

  /** Fail-LOUD scheme selection: an absent/unresolved reward scheme is a consensus bug, never a silent zero-reward
    * (`fail-loud-invariants.md`, L4 plan §9). L5's network wiring resolves a `RewardScheme` from chain config and
    * passes it through this guard so an accidental `None` `sys.error`s at construction rather than degrading to a quiet
    * zero.
    */
  def require(scheme: Option[RewardScheme], forContext: => String): RewardScheme =
    scheme.getOrElse(
      sys.error(s"Unresolved RewardScheme for $forContext — refusing a silent zero-reward (fail-loud, L4 §9)")
    )
