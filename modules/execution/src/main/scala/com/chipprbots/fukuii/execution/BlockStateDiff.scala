package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.trie.LeafChange

/** The per-block, serializable **world-state envelope diff** — the L4 composition of L2's per-trie
  * [[com.chipprbots.fukuii.trie.LeafChange]] `{prior,updated}` leaf primitive (`trie.TrieLog`, besu Bonsai's
  * `BonsaiTrieLog {getPrior/getUpdated}`, `BonsaiTrieLogFactory.java:44-66`). L2's `TrieLog` owns the leaf-diff of a
  * *single* trie; L4 composes the world-state envelope over it — the account leaf, per-slot storage leaves, and the
  * (non-trie, content-addressed) code — because only `execution` knows the account/storage/code structure the leaf keys
  * map to (`trie.TrieLog` scaladoc: "composed at L4 alongside the account trie's log and each storage sub-trie's log").
  *
  * ==L4 owns the diff *content*; L5 owns the reorg segmentation (layer boundary)==
  * L4 computes this per-block diff and hands each block's [[BlockExecutionOutcome]] up to L5's branch-import driver.
  * **L5 owns the reorg-aware `ChainNotification` segment stream** (`{reverted, committed}`) — it decides reorgs; L4
  * only executes blocks (L4 plan §"Layer boundaries", RX-L4-15). L9 carries L5's notification over gRPC; L2 gates
  * pruning on consumer `FinishedHeight`. None of those live here.
  *
  * ==PROVISIONAL — wire/payload shape OPEN pending the joint L4/L5/L9 WB-R2 review; not a frozen public API.==
  * The on-disk/wire shape is **NOT settled** (L9 holds the payload contract OPEN, `coherence-pass-02` WB-R2). This type
  * is the diff *content* + an internal Scala shape only — **no RLP/wire codec is defined against it yet**, and the
  * field set is subject to the joint review. Two SR caveats it must eventually carry (`exec-extensions.md`, deferred to
  * that review): the payload must be **storage-agnostic** (besu's `TrieLog` is Bonsai-path-tree-specific; fukuii is
  * RocksDB/MPT-inline) and **version-less-additive** (nethermind positional-RLP "absent = zero").
  *
  * ==Byte-reproducible (§7 DoD)==
  * A consumer replays this diff, so it MUST be deterministic: [[accounts]] are held in canonical unsigned-lexicographic
  * address order and each account's [[AccountStateDiff.storage]] in canonical slot order (see [[BlockStateDiff.of]]),
  * so the same block yields the same diff regardless of the order mutations were recorded. Net-unchanged entries are
  * dropped (`LeafChange.isUnchanged`), matching besu's accumulator, which computes the diff from the same state at
  * `persist` — so the diff and the committed state root cannot disagree.
  *
  * @param accounts
  *   one [[AccountStateDiff]] per net-changed account, in canonical address order.
  */
final case class BlockStateDiff(accounts: Seq[AccountStateDiff])

object BlockStateDiff:
  /** The empty diff (a block that mutated nothing observable). */
  val empty: BlockStateDiff = BlockStateDiff(Vector.empty)

  /** Build a diff from unordered account entries, imposing the canonical unsigned-lexicographic address order that
    * keeps [[BlockStateDiff]] byte-reproducible.
    */
  def of(entries: Iterable[AccountStateDiff]): BlockStateDiff =
    BlockStateDiff(entries.toVector.sortBy(_.address)(using ByteOrder.address))

/** The `{prior,updated}` diff for a single account across a block — the account leaf itself plus every touched storage
  * slot and (if changed) its code, tagged with the [[MutationReason]] attributed to this account.
  *
  * @param address
  *   the account whose state changed.
  * @param account
  *   the account-leaf `{prior,updated}` — `RLP(Account)` bytes (besu's account TrieLog entry / geth `StateAccount`).
  *   `LeafChange(None, Some(_))` is a creation, `(Some(_), None)` a deletion (SELFDESTRUCT / EIP-161 sweep).
  * @param storage
  *   per-slot `{prior,updated}` of the touched storage sub-trie leaves, `RLP(trimmed-big-endian value)` bytes; a zero
  *   value is `None` (an absent slot — matches [[InMemoryAccountStorage]]'s zero-is-a-deletion). Canonical slot order.
  * @param code
  *   the code `{prior,updated}` (raw bytes, content-addressed — code is NOT a trie leaf), present only if code changed.
  * @param reason
  *   the [[MutationReason]] attributed to this account (see [[MutationReason]] — a coarse per-block-phase attribution
  *   in this PROVISIONAL model).
  */
final case class AccountStateDiff(
    address: Address,
    account: LeafChange,
    storage: Seq[(UInt256, LeafChange)],
    code: Option[LeafChange],
    reason: MutationReason
):
  /** True when this entry carries no net change on any of account / storage / code — such entries are dropped from a
    * [[BlockStateDiff]] (besu `LogTuple.isUnchanged`).
    */
  def isUnchanged: Boolean = account.isUnchanged && storage.isEmpty && code.forall(_.isUnchanged)

/** Unsigned-byte lexicographic orderings for the canonical, byte-reproducible sort of a [[BlockStateDiff]] — the same
  * unsigned-compare discipline L2's `TrieLog` and `DataSource` require (Java's signed `Array[Byte]` order is wrong for
  * high bytes).
  */
private[execution] object ByteOrder:
  private def unsigned(a: IndexedSeq[Byte], b: IndexedSeq[Byte]): Int =
    val n = math.min(a.length, b.length)
    var i = 0
    var cmp = 0
    while cmp == 0 && i < n do
      cmp = (a(i) & 0xff) - (b(i) & 0xff)
      i += 1
    if cmp != 0 then cmp else a.length - b.length

  val address: Ordering[Address] = (a, b) => unsigned(a.bytes, b.bytes)
  val slot: Ordering[UInt256] = (a, b) => unsigned(a.bytes, b.bytes)
