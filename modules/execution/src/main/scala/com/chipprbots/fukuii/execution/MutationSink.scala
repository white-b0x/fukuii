package com.chipprbots.fukuii.execution

import scala.collection.mutable

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256

/** The touched-key accumulator threaded through [[InMemoryWorldState]] — the fukuii analog of besu's
  * `BonsaiWorldStateUpdateAccumulator` / go-ethereum's hooked `StateDB`. It records **which** accounts, storage slots,
  * and code entries a block mutated, so [[BlockProcessor]] can compute the per-block [[BlockStateDiff]] by diffing the
  * baseline world against the committed world over exactly this set (no whole-trie walk).
  *
  * ==Branch-free zero-cost baseline (RX-L4-16, the load-bearing property)==
  * The default sink is [[NoTracking]] — a shared singleton whose `record*` methods are **empty**, so the baseline
  * execution path (no event consumer attached) pays nothing. This is go-ethereum's "don't install the hooked `StateDB`
  * when `Tracer == nil`" (`state_processor.go:77`) realized via **polymorphic dispatch**, NOT a per-mutation `if`
  * (mirrors L3's branch-free `NoTracing`). The [[Recording]] sink is installed **only** when a diff is requested
  * ([[InMemoryWorldState.withMutationSink]] / [[BlockProcessor.processBlockWithOutcome]]).
  *
  * The structural zero-cost invariant a test can assert: a baseline world's `mutations eq MutationSink.NoTracking`.
  *
  * ==PROVISIONAL==
  * This is an internal execution collaborator, not a public API — its shape is subject to the joint L4/L5/L9 WB-R2
  * review that fixes the reorg-event payload contract. A [[Recording]] instance is per-block and mutable (besu's
  * accumulator is likewise mutable); it is never a process-global / `object … { var … }` (R2) — the emitted
  * [[BlockStateDiff]] / [[BlockExecutionOutcome]] are immutable values.
  */
sealed trait MutationSink:
  /** Record that `address`'s account leaf was written (balance / nonce / storageRoot / codeHash). */
  def recordAccount(address: Address): Unit

  /** Record that `address`'s contract code was written. */
  def recordCode(address: Address): Unit

  /** Record that `owner`'s storage `slot` was written. */
  def recordSlot(owner: Address, slot: UInt256): Unit

object MutationSink:

  /** The branch-free no-op sink — the baseline (no consumer attached). Empty method bodies dispatch to nothing; the JIT
    * inlines them away, so the hot path carries no diff-collection cost and no per-mutation branch.
    */
  case object NoTracking extends MutationSink:
    def recordAccount(address: Address): Unit = ()
    def recordCode(address: Address): Unit = ()
    def recordSlot(owner: Address, slot: UInt256): Unit = ()

  /** The recording accumulator — installed only when a diff is requested. Accumulates the touched key set in insertion
    * order (canonicalised to sorted order at diff-build time for byte-reproducibility). Repeated records for the same
    * key coalesce into the set.
    */
  final class Recording extends MutationSink:
    private val accounts: mutable.LinkedHashSet[Address] = mutable.LinkedHashSet.empty
    private val codes: mutable.LinkedHashSet[Address] = mutable.LinkedHashSet.empty
    private val slots: mutable.LinkedHashMap[Address, mutable.LinkedHashSet[UInt256]] = mutable.LinkedHashMap.empty

    def recordAccount(address: Address): Unit =
      accounts += address
      ()

    def recordCode(address: Address): Unit =
      codes += address
      ()

    def recordSlot(owner: Address, slot: UInt256): Unit =
      slots.getOrElseUpdate(owner, mutable.LinkedHashSet.empty) += slot
      ()

    /** The union of every address whose account, code, or storage was touched — the candidate key set the diff builder
      * walks (unchanged entries are dropped by [[com.chipprbots.fukuii.trie.LeafChange.isUnchanged]]).
      */
    def touchedAddresses: Set[Address] = accounts.toSet ++ codes.toSet ++ slots.keySet.toSet

    /** Whether `address`'s code was written (so the diff builder reads its code on both sides). */
    def touchedCode(address: Address): Boolean = codes.contains(address)

    /** The storage slots written under `address` (empty if none). */
    def touchedSlots(address: Address): Set[UInt256] = slots.get(address).map(_.toSet).getOrElse(Set.empty)
