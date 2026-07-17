package com.chipprbots.fukuii.trie

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.decodeStrict
import com.chipprbots.fukuii.rlp.encode as encodeRlp

/** A prior→updated value pair for a single trie leaf key — besu's `TrieLog.LogTuple<T>` (`getPrior`/`getUpdated`/
  * `isUnchanged`), specialised to the trie's byte-level leaf-value space.
  *
  * `None` means "absent" (no leaf for this key on that side of the diff): `LeafChange(None, Some(v))` is an insert,
  * `LeafChange(Some(v), None)` a delete, `LeafChange(Some(a), Some(b))` an update. A leaf's value is captured, never a
  * node — this is the load-bearing property (RX-L2-19): a `{prior, updated}` **leaf** diff is a stable, serializable
  * cross-process identity an out-of-process R7 consumer can `rollForward`/`rollBack` without sharing the trie, whereas
  * a node-hash-keyed diff (naming trie-internal node hashes rather than leaf keys) is coupled to the local trie
  * structure and cannot be handed to a remote consumer.
  */
final case class LeafChange(prior: Option[ByteString], updated: Option[ByteString]) derives RLPCodec:

  /** besu `LogTuple.isUnchanged`: the two sides are equal, so this entry is a no-op on the state root. */
  def isUnchanged: Boolean = prior == updated

  /** besu `LogTuple.isLastStepCleared` analogue: this step removed a previously-present leaf. */
  def isCleared: Boolean = prior.isDefined && updated.isEmpty

/** A per-block journal of trie **leaf-value** diffs — besu Bonsai's `TrieLog` (`plugin-api/…/trielogs/TrieLog.java`),
  * the clean, serializable R7 reorg-event source.
  *
  * ==Leaf-keyed, not node-hash-keyed (RX-L2-19)==
  * Entries are keyed by the trie's **leaf key** (the serialized key bytes — at the L4 world-state layer this leaf key
  * *is* the account's `Address` / a storage `StorageSlotKey` identity, mapped by `execution` when it composes the
  * account/storage/code envelope). It is emphatically **not** keyed by node hash: a node-hash diff names internal trie
  * structure a remote consumer can't interpret without the trie itself; a leaf diff is a self-contained state-change
  * record. Code changes are not trie leaves (content-addressed in the `Code` CF) and are composed at L4 alongside the
  * account trie's log and each storage sub-trie's log — this L2 type is the per-trie leaf primitive L4 builds on, kept
  * free of world-state (account/storage/code) concepts so the layer boundary holds.
  *
  * ==Root-neutral side-journal==
  * A `TrieLog` is captured *beside* a trie transition ([[TrieLog.diff]] reads leaf values, [[TrieLog.Builder]]
  * accumulates them) and never mutates node content, a node's `Location`, or the state root — the same additive
  * discipline as T2a's path-threading. [[rollForward]]/[[rollBack]] reach a sibling state purely by re-applying leaf
  * `put`/`remove` and letting the trie recompute the root bottom-up; the root stays byte-exact because the physical
  * trie over these leaf keys is reconstructed identically.
  *
  * ==Byte-pure storage boundary==
  * The `{prior, updated}` builder lives here in `trie` (it parses leaves); it hands `storage` only the [[serialized]]
  * blob (L0 `rlp` `derives` codecs), never a parsed node — `storage` stays trie-type-free. The *stream* that carries
  * this journal out-of-process (gRPC/ExEx consumer) is L9; this type is the format + builder + roll primitives only.
  *
  * `changes` is held in canonical key-sorted order (unsigned-byte lexicographic) so two logs with the same leaf diffs
  * serialize to identical bytes regardless of the order changes were recorded.
  */
final case class TrieLog(changes: Seq[(ByteString, LeafChange)]) derives RLPCodec:

  /** Apply each entry's `updated` value — advance a trie at the **prior** state to the **updated** state. A `Some`
    * upserts the leaf, a `None` removes it. Returns a new trie; the input is untouched (root-neutral).
    */
  def rollForward(
      trie: MerklePatriciaTrie[Array[Byte], Array[Byte]]
  ): MerklePatriciaTrie[Array[Byte], Array[Byte]] =
    apply(trie, _.updated)

  /** Apply each entry's `prior` value — revert a trie at the **updated** state back to the **prior** state (the reorg
    * "walk back toward a shared ancestor" primitive). Returns a new trie; the input is untouched (root-neutral).
    */
  def rollBack(
      trie: MerklePatriciaTrie[Array[Byte], Array[Byte]]
  ): MerklePatriciaTrie[Array[Byte], Array[Byte]] =
    apply(trie, _.prior)

  private def apply(
      trie: MerklePatriciaTrie[Array[Byte], Array[Byte]],
      side: LeafChange => Option[ByteString]
  ): MerklePatriciaTrie[Array[Byte], Array[Byte]] =
    changes.foldLeft(trie) { case (t, (key, change)) =>
      side(change) match
        case Some(value) => t.put(key.toArray, value.toArray)
        case None        => t.remove(key.toArray)
    }

  /** The canonical RLP bytes handed to `storage` — a serialized leaf diff, never a parsed node. */
  def serialized: ByteString = ByteString(encodeRlp(this))

object TrieLog:

  /** Unsigned-byte lexicographic order over leaf keys — the canonical sort keeping [[serialized]] deterministic. */
  private val keyOrdering: Ordering[ByteString] = (a: ByteString, b: ByteString) =>
    val n = math.min(a.length, b.length)
    var i = 0
    var cmp = 0
    while cmp == 0 && i < n do
      cmp = (a(i) & 0xff) - (b(i) & 0xff)
      i += 1
    if cmp != 0 then cmp else a.length - b.length

  private def canonical(changes: Iterable[(ByteString, LeafChange)]): Seq[TrieLog.Entry] =
    changes.toVector.sortBy(_._1)(keyOrdering)

  private type Entry = (ByteString, LeafChange)

  /** The empty journal (a no-op block). */
  val empty: TrieLog = TrieLog(Vector.empty)

  /** Rehydrate a persisted journal — strict, so trailing bytes past the single record are rejected. */
  def deserialize(bytes: ByteString): TrieLog = decodeStrict[TrieLog](bytes.toArray)

  /** Build a journal by **diffing two committed trie states** over the set of keys that could have changed — the
    * leaf-parsing capture path: it reads each key's leaf value on both sides ([[MerklePatriciaTrie.get]]) and records
    * only the leaves that actually differ (`isUnchanged` entries are dropped). `touchedKeys` is the working set the
    * block mutated (the world-state layer knows it); an unchanged key in that set contributes nothing.
    */
  def diff(
      before: MerklePatriciaTrie[Array[Byte], Array[Byte]],
      after: MerklePatriciaTrie[Array[Byte], Array[Byte]],
      touchedKeys: Iterable[ByteString]
  ): TrieLog =
    val entries = touchedKeys.iterator.distinct.flatMap { key =>
      val prior = before.get(key.toArray).map(ByteString(_))
      val updated = after.get(key.toArray).map(ByteString(_))
      val change = LeafChange(prior, updated)
      if change.isUnchanged then None else Some(key -> change)
    }
    TrieLog(canonical(entries.toVector))

  /** An accumulator matching besu's `TrieLogAccumulator` — the world-state layer records each leaf's prior/updated
    * value as it touches it, and [[build]] emits the canonical journal. Prior/updated are supplied by the caller (which
    * already read the prior to compute the updated), so the accumulator never parses a node or re-walks the trie.
    * Repeated records for the same key coalesce: the earliest `prior` and the latest `updated` win.
    */
  final class Builder:
    private val staged = scala.collection.mutable.LinkedHashMap.empty[ByteString, LeafChange]

    /** Record a leaf transition `key: prior -> updated`. */
    def record(key: ByteString, prior: Option[ByteString], updated: Option[ByteString]): Builder =
      val _ = staged.updateWith(key) {
        case Some(existing) => Some(LeafChange(existing.prior, updated))
        case None           => Some(LeafChange(prior, updated))
      }
      this

    /** Emit the canonical journal, dropping any entry that netted out unchanged. */
    def build: TrieLog =
      TrieLog(canonical(staged.iterator.filterNot(_._2.isUnchanged).toVector))
