package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Wei
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
  * The two schemes: [[RewardScheme.Ecip1017RewardScheme]] (ETC PoW era emission, P4a — byte-exact vs core-geth
  * `rewards_classic.go`/`rewards.go` + besu-etc `ClassicBlockProcessor.java`) and [[RewardScheme.PosNoRewardScheme]]
  * (ETH PoS zero issuance). Selection must **fail LOUD** on an unresolved scheme ([[RewardScheme.require]]) — never
  * nethermind's silent zero-reward (`fail-loud-invariants.md`, L4 plan §6 row 6 / §9).
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

  /** ETC — **ECIP-1017 era emission** (5 → 4 → 3.2 → 2.56 → … ETH, 20% step-down every era). **P4a, forge-gated:**
    * reproduced byte-for-byte from **core-geth** (the SOLE frozen spec authority) `params/mutations/rewards_classic.go`
    * + `rewards.go`, cross-checked against **besu-etc** (`ClassicBlockProcessor.java`, commit `eb4248c997`) — the two
    * authorities agree byte-for-byte (L4 plan §5/§6 row 2/§9, RX-L4-06).
    *
    * **The base reward is Frontier 5 ETH ALWAYS** (`FrontierBlockReward = 5e18`, core-geth
    * `params/vars/protocol_params.go:27`; besu-etc `ClassicProtocolSpecs.java:60` `Wei.fromEth(5)`), reduced **only**
    * by the era schedule — **not** the EIP-649/1234 Byzantium(3)/Constantinople(2) reductions (DAO absence). Era length
    * defaults to `5_000_000` (core-geth `config_classic.go` `ecip1017EraRounds`; besu-etc `DEFAULT_ERA_LENGTH`).
    *
    * **A `case class`, not a `case object`,** so the monetary-policy parameters ([[eraLength]], [[blockReward]]) are
    * per-network config. **ETC mainnet takes the canonical `5_000_000` era length; Mordor does NOT** — core-geth
    * `config_mordor.go:118` sets `ECIP1017EraRounds: 2_000_000` for Mordor (chainId 63), so **L5's Mordor wiring MUST
    * construct this scheme with `eraLength = 2_000_000`**, not the [[Ecip1017RewardScheme.DefaultEraLength]]. A future
    * custom network may vary both (see the [[Ecip1017RewardScheme.canonicalDivisibility]] divisibility rationale).
    * Treasury routing (ECIP-1111) is the separate [[FeeDisposition]] seam (P4b), not this scheme.
    *
    * @param eraLength
    *   blocks per emission era (default `5_000_000`).
    * @param blockReward
    *   the era-0 base reward in wei (default `5e18`).
    */
  final case class Ecip1017RewardScheme(
      eraLength: BigInt = Ecip1017RewardScheme.DefaultEraLength,
      blockReward: BigInt = Ecip1017RewardScheme.DefaultBlockReward
  ) extends RewardScheme:

    import Ecip1017RewardScheme.*

    /** Credit the winner (miner) coinbase — the era-scaled winner reward **plus** the nephew bonus for each included
      * uncle — and each uncle coinbase (the uncle reward). The last state mutation before block commitment (besu
      * `AbstractBlockProcessor:485` reward → `:532` persist; core-geth `AccumulateRewards` `rewards.go:64-72`:
      * `AddBalance(uncle.Coinbase, uncleReward)` per uncle, then `AddBalance(header.Coinbase, minerReward)`).
      *
      * Crediting is an additive `addBalance` on distinct addresses (winner ≠ uncle coinbases in the reward slot), so
      * the order between the winner credit and the uncle credits is state-root-commutative (final balances are
      * order-independent). Every era reward is strictly positive, so no EIP-161 empty-sweep applies (the account
      * becomes non-empty).
      */
    def rewardBlock[WS <: WorldState[WS, S], S <: AccountStorage[S]](
        world: WS,
        header: BlockHeader,
        ommers: Seq[BlockHeader]
    ): WS =
      val era = blockEra(header.number)
      val creditedWinner = addBalance(world, header.beneficiary, minerReward(era, ommers.size))
      ommers.foldLeft(creditedWinner) { (w, ommer) =>
        addBalance(w, ommer.beneficiary, uncleReward(era, header.number, ommer.number))
      }

    /** The zero-indexed emission era of `blockNumber` — integer **DIVISION** `(blockNumber - 1) / eraLength`, with a
      * genesis/negative guard to era 0. **Both authorities agree it is division, not `mod`:** core-geth
      * `rewards_classic.go:49-62` computes `remainder = (blockNum-1) mod eraLength; base = blockNum - remainder; d =
      * base / eraLength; d mod 1` — the trailing `mod 1` (`:59`) is a **no-op** and the whole dance reduces to
      * `floor((blockNum-1)/eraLength)`; besu-etc `getBlockEra:105-112` drops the `mod 1` and returns `base/eraLength`
      * directly. Verified: block 5,000,000 → era 0; block 5,000,001 → era 1.
      */
    private[execution] def blockEra(blockNumber: BigInt): BigInt =
      if blockNumber <= 0 then BigInt(0)
      else (blockNumber - 1) / eraLength

    /** The winner (miner) reward at `era`, before the nephew bonus. Era 0 → [[blockReward]] unchanged; era ≥1 →
      * **separate integer exponentiation** `blockReward · 4^era / 5^era` (core-geth `GetBlockWinnerRewardByEra`
      * `rewards.go:117-128`: `q.Exp(4, era); d.Exp(5, era); r.Mul(reward, q); r.Div(r, d)` — `DisinflationRateQuotient
      * \= 4`, `DisinflationRateDivisor = 5`, `config_classic.go:144-145`; besu-etc `getBlockWinnerRewardByEra:116-139`:
      * `BigInteger.valueOf(4).pow(era)` / `.divide` — identical). `BigInt` division truncates toward zero, matching Go
      * `big.Int.Div` and Java `BigInteger.divide`. **NOT** a `BigDecimal.precision` reconstruction of the 4/5 ratio
      * (the AS-IS byte-hazard this replaces).
      */
    private[execution] def winnerRewardByEra(era: BigInt): BigInt =
      if era == 0 then blockReward
      else blockReward * DisinflationRateQuotient.pow(era.toInt) / DisinflationRateDivisor.pow(era.toInt)

    /** The reward to an **uncle** miner — the Era-0-vs-Era-≥1 formula switch. Era 0 → `(uncleNumber + 8 - headerNumber)
      * · blockReward / 8` (core-geth `GetBlockUncleRewardByEra` `rewards.go:85-89`, the distance-bonus form, base
      * `blockReward`); era ≥1 → `winnerRewardByEra(era) / 32` (`getEraUncleBlockReward` `rewards.go:76-78`). besu-etc
      * `calculateOmmerReward:94-100` writes the era-0 case as the algebraically-equal subtract form `winnerReward −
      * winnerReward·distance/8` — see [[canonicalDivisibility]].
      */
    private[execution] def uncleReward(era: BigInt, headerNumber: BigInt, uncleNumber: BigInt): BigInt =
      if era == 0 then (uncleNumber + UncleGenerationBonus - headerNumber) * blockReward / UncleDivisor
      else winnerRewardByEra(era) / NephewDivisor

    /** The full miner credit — `winnerRewardByEra(era)` **plus** the nephew bonus `ommerCount · (winnerRewardByEra(era)
      * / 32)` (core-geth `GetBlockWinnerRewardForUnclesByEra` `rewards.go:96-105` sums `getEraUncleBlockReward =
      * floor(winnerByEra/32)` per uncle; `ecip1017BlockReward` adds it to the winner reward). The nephew per-uncle
      * bonus is `floor(winnerByEra/32)` for all eras (`:102` "1/32 for winner's uncles remain unchanged from Era 1").
      */
    private[execution] def minerReward(era: BigInt, ommerCount: Int): BigInt =
      winnerRewardByEra(era) + BigInt(ommerCount) * (winnerRewardByEra(era) / NephewDivisor)

    private def addBalance[WS <: WorldState[WS, S], S <: AccountStorage[S]](
        world: WS,
        address: Address,
        amount: BigInt
    ): WS =
      val account = world.getAccount(address).getOrElse(world.getEmptyAccount)
      world.saveAccount(address, account.copy(balance = Wei(account.balance.toUInt256 + UInt256(amount))))

  object Ecip1017RewardScheme:

    /** Blocks per emission era — ECIP-1017 `5_000_000` (core-geth `config_classic.go` `ecip1017EraRounds`; besu-etc
      * `ClassicBlockProcessor.DEFAULT_ERA_LENGTH`).
      */
    val DefaultEraLength: BigInt = 5_000_000

    /** The era-0 base reward in wei — `FrontierBlockReward = 5e18` (core-geth `params/vars/protocol_params.go:27`;
      * besu-etc `ClassicProtocolSpecs.java:60` `Wei.fromEth(5)`). Computed as `5 · 10^18` rather than hardcoded so the
      * constant is self-checking.
      */
    val DefaultBlockReward: BigInt = BigInt(5) * BigInt(10).pow(18)

    /** `DisinflationRateQuotient = 4` (core-geth `config_classic.go:144`). */
    private[execution] val DisinflationRateQuotient: BigInt = 4

    /** `DisinflationRateDivisor = 5` (core-geth `config_classic.go:145`). The 20%-per-era step-down `(4/5)^era`. */
    private[execution] val DisinflationRateDivisor: BigInt = 5

    /** The era-0 uncle generation bonus `+8` (core-geth `rewards.go:86` `big8`). */
    private[execution] val UncleGenerationBonus: BigInt = 8

    /** The era-0 uncle divisor `/8` (core-geth `rewards.go:89` `big8`). */
    private[execution] val UncleDivisor: BigInt = 8

    /** The uncle/nephew divisor `/32` for era ≥1 and the nephew bonus (core-geth `rewards.go:77` `big32`). */
    private[execution] val NephewDivisor: BigInt = 32

    /** **Canonical-form + divisibility rationale (RX-L4-06 §9 two-authority finding).** This scheme reproduces
      * **core-geth's multiply-then-divide** shape for every divided reward — era-0 uncle `(uncleNum+8−headerNum)·R/8`,
      * nephew `ommers·⌊R/32⌋` — because core-geth is the frozen spec authority. besu-etc writes algebraically-equal but
      * arithmetically-**different** shapes (era-0 uncle `R − R·distance/8`; nephew `⌊R·ommers/32⌋`). Under integer
      * truncation the two agree **only because the canonical era reward `R = 2^(18+2·era)·5^(19−era)` is divisible by 8
      * and 32** (`18+2·era ≥ 5` always). So on ETC mainnet/Mordor either shape yields byte-identical balances — but a
      * future custom-network [[blockReward]] **not** divisible by 8/32 could take a 1-wei-diverging path. The canonical
      * mul-then-div form is recorded here so that divergence is deliberate, not silent.
      */
    val canonicalDivisibility: String =
      "core-geth mul-then-div canonical; agrees with besu-etc iff R divisible by 8 and 32 (RX-L4-06 §9)"

  /** Fail-LOUD scheme selection: an absent/unresolved reward scheme is a consensus bug, never a silent zero-reward
    * (`fail-loud-invariants.md`, L4 plan §9). L5's network wiring resolves a `RewardScheme` from chain config and
    * passes it through this guard so an accidental `None` `sys.error`s at construction rather than degrading to a quiet
    * zero.
    */
  def require(scheme: Option[RewardScheme], forContext: => String): RewardScheme =
    scheme.getOrElse(
      sys.error(s"Unresolved RewardScheme for $forContext — refusing a silent zero-reward (fail-loud, L4 §9)")
    )
