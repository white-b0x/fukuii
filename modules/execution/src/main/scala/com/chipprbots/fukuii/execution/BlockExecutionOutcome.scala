package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.domain.Block

/** The per-block outcome L4 emits **up to L5's branch-import driver** — the R7 seam's L4 half (L4 plan §1/§5,
  * RX-L4-15). L4 executes and diffs a block; L5 aggregates a stream of these outcomes with its own fork-choice
  * decisions into the reorg-aware `ChainNotification` segment stream (`{reverted, committed}`) — **L5 owns reorg
  * authority; L4 does not decide reorgs or define the segment-level wire ADT** (L4 plan §"Layer boundaries").
  *
  * ==Relationship to [[ExecutedBlock]]==
  * [[ExecutedBlock]] (P4) is the **internal** compute result of the pipeline — the committed world plus the four
  * post-execution commitments ([[BlockProcessor.execute]]/`processBlock` return it). `BlockExecutionOutcome` is the
  * **outward-facing** R7 value produced *from* an `ExecutedBlock` (on success) or from a failure (rollback): it wraps
  * the `Block` and the serializable [[BlockStateDiff]], dropping the internal world/roots a downstream consumer neither
  * needs nor can hold across a process boundary. It **wraps, it does not replace** — `processBlock` still returns
  * `ExecutedBlock`; `processBlockWithOutcome` layers this on top.
  *
  * ==PROVISIONAL==
  * Carries the PROVISIONAL [[BlockStateDiff]] — see that type. Not a frozen public API until the joint L4/L5/L9 WB-R2
  * review fixes the payload contract.
  */
enum BlockExecutionOutcome:

  /** The block executed and committed. `stateDiff` is its byte-reproducible per-block `{prior,updated}` envelope diff.
    */
  case Executed(block: Block, stateDiff: BlockStateDiff)

  /** The block's execution or commitment failed — the world / accumulator is discarded (besu `reset()` on a failed
    * block, `AbstractBlockProcessor.java:339`); nothing was committed, so there is no diff. A rolled-back block emits
    * **no** state mutation to any consumer.
    */
  case RolledBack(block: Block)
