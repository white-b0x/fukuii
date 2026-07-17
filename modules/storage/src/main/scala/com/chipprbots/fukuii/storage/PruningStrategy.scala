package com.chipprbots.fukuii.storage

/** A snapshot of trie-store height/memory metrics an [[EvictionStrategy]] or [[PersistenceStrategy]] consults to decide
  * whether its policy fires right now (nethermind `Nethermind.Trie.Pruning.TrieStoreState`). `storage` holds no
  * in-memory dirty-node cache of its own (that is a trie/L4 concern) — [[RefCountedNodeStore]] fills in
  * [[latestCommittedBlock]] / [[lastPersistedBlock]] at every commit/prune call; the two memory fields exist for
  * shape-fidelity with the reference interface and default to `0` where a caller has no cache to report.
  */
final case class TrieStoreState(
    persistedCacheMemory: Long,
    dirtyCacheMemory: Long,
    latestCommittedBlock: BigInt,
    lastPersistedBlock: BigInt
)

/** The eviction-policy half of the composable pruning split (nethermind `Nethermind.Trie.Pruning.IPruningStrategy`) — a
  * **two-method** decision, not a single "evict?" predicate (RX-L2-15): [[shouldPruneDirtyNode]] governs whether a node
  * that just dropped to zero references is filed onto death row at all; [[shouldPrunePersistedNode]] governs whether a
  * death-row (already zero-ref, already persisted) node is physically removed once [[PruningStore.prune]] considers it.
  * Evicting a dirty in-RAM node and pruning a persisted disk node are distinct decisions.
  */
trait EvictionStrategy:
  def shouldPruneDirtyNode(state: TrieStoreState): Boolean
  def shouldPrunePersistedNode(state: TrieStoreState): Boolean

object EvictionStrategy:

  /** Never files a node to death row and never physically prunes one — layering this atop [[RefCountedNodeStore]]'s
    * mechanism reproduces [[ArchivePruningStore]]-like retention without changing which mechanism is selected.
    */
  val never: EvictionStrategy = new EvictionStrategy:
    def shouldPruneDirtyNode(state: TrieStoreState): Boolean = false
    def shouldPrunePersistedNode(state: TrieStoreState): Boolean = false

  /** Always files and always prunes — the permissive default [[RefCountedNodeStore]] composes with unless a caller
    * supplies a different policy; under this policy the mechanism's own refcount/horizon bookkeeping is the sole gate
    * (RX-L2-15).
    */
  val always: EvictionStrategy = new EvictionStrategy:
    def shouldPruneDirtyNode(state: TrieStoreState): Boolean = true
    def shouldPrunePersistedNode(state: TrieStoreState): Boolean = true

/** The flush-policy half of the composable pruning split (nethermind `Nethermind.Trie.Pruning.IPersistenceStrategy`) —
  * whether a given block's bookkeeping writes should be treated as durability-critical. [[RefCountedNodeStore]]
  * consults this per commit; a caller wiring it to [[DataSource.updateSync]] vs [[DataSource.update]] is the concrete
  * durability effect, kept outside this seam (`storage`'s WAL-off exposure discipline, see [[DataSource]]).
  */
trait PersistenceStrategy:
  def shouldPersist(blockNumber: BigInt): Boolean

object PersistenceStrategy:
  val always: PersistenceStrategy = (_: BigInt) => true
  val never: PersistenceStrategy = (_: BigInt) => false

  /** Persists (fsync-tier) every `n`th block only — the "commit every N blocks" shape some reference clients expose. */
  def everyNBlocks(n: Int): PersistenceStrategy = (blockNumber: BigInt) => n > 0 && (blockNumber % n == 0)
