package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.evm.EvmConfig
import com.chipprbots.fukuii.evm.ForkSchedule

/** The per-chain **bundle lookup** — fukuii's besu-`ProtocolSchedule` analog. Built once, it resolves the immutable
  * [[ProtocolSpec]] active at a header (besu `ProtocolSchedule.getByBlockHeader`), and the *next* header's spec for the
  * producer path (besu `getForNextBlockHeader`) — one schedule behind **both** verify (import) and produce, so the two
  * cannot diverge (L4 plan §2 v3, RX-L4-03).
  *
  * **The fork is resolved ONCE, held in the spec, never re-derived mid-execution** — the load-bearing §2.1 invariant.
  * [[getByBlockHeader]] wraps L3's single `EvmConfig.forBlock(header, forkSchedule)`; the resolved [[EvmConfig]] is a
  * value the bundle *holds*, not a factory any use-site re-invokes. Adding a fork = one [[ForkSchedule]] entry (+ the
  * L3 proposal) — the loop never rots.
  *
  * **P1 collaborator scoping.** The economics collaborators ([[RewardScheme]], [[FeeDisposition]],
  * [[RequestProcessors]], the [[WithdrawalsProcessor]]) are supplied at construction as **network-level values** and
  * bundled with the per-header [[EvmConfig]]. Making them *fork-varying within a network* (e.g.
  * `RequestProcessors.noOp` pre-Prague → an active map post-Prague; [[FeeDisposition.Absent]] pre-1559 →
  * `Burn`/`RedirectToTreasury` after) requires the request impls (P5) and the base-fee/era math (P4) to exist before
  * the variation is meaningful, so P1 wires them constant and P4/P5 make them header-derived. This keeps the "resolve
  * once" spine honest without inventing economics before its gated phase.
  */
final class ProtocolSchedule private (
    forkSchedule: ForkSchedule,
    rewardScheme: RewardScheme,
    requests: RequestProcessors,
    withdrawals: Option[WithdrawalsProcessor],
    feeDisposition: FeeDisposition
):

  /** The [[ProtocolSpec]] active at `header` — the import/verify path. Resolves the fork **once** via L3's
    * `EvmConfig.forBlock(header, forkSchedule)` and holds the result in the returned bundle.
    */
  def getByBlockHeader(header: BlockHeader): ProtocolSpec =
    bundle(EvmConfig.forBlock(header, forkSchedule))

  /** The [[ProtocolSpec]] for the to-be-built block at `(number, timestamp)` — the producer path (besu
    * `getForNextBlockHeader`). There is no header yet, so this resolves the active set directly via
    * `forkSchedule.activeAt` — the exact resolution `EvmConfig.forBlock` performs internally — keeping the single-
    * resolution invariant while accepting a not-yet-built block's coordinates (RX-L4-03 Q3).
    */
  def getForNextBlockHeader(number: BigInt, timestamp: Long): ProtocolSpec =
    bundle(EvmConfig.deriveEvmConfigAt(forkSchedule.activeAt(number, timestamp)))

  private def bundle(evmConfig: EvmConfig): ProtocolSpec =
    ProtocolSpec(evmConfig, rewardScheme, requests, withdrawals, feeDisposition)

object ProtocolSchedule:

  /** Build a schedule over a [[ForkSchedule]] and a network's economics collaborators. The [[RewardScheme]] is passed
    * concrete (compile-time non-null); L5's config wiring resolves it through [[RewardScheme.require]] first so an
    * unresolved scheme fails LOUD before it ever reaches here (L4 plan §9).
    */
  def apply(
      forkSchedule: ForkSchedule,
      rewardScheme: RewardScheme,
      requests: RequestProcessors,
      withdrawals: Option[WithdrawalsProcessor],
      feeDisposition: FeeDisposition
  ): ProtocolSchedule =
    new ProtocolSchedule(forkSchedule, rewardScheme, requests, withdrawals, feeDisposition)
