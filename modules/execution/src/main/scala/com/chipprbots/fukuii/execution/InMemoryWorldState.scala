package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.evm.WorldState
import com.chipprbots.fukuii.trie.MerklePatriciaTrie
import com.chipprbots.fukuii.trie.MptStorage

/** The concrete, state-backed [[WorldState]] — L4's fill of the L3 abstract seam over the L2 trie. It wires:
  *   - the **accounts state trie** `keccak256(address) → RLP(Account)` ([[accountsStateTrie]]),
  *   - a **per-account storage sub-trie** per touched contract ([[contractStorages]]), each backed by the same shared
  *     node store,
  *   - the **code store** ([[codeStorage]]) and a pending [[accountCodes]] map (code held in memory until [[persist]]),
  *   - a [[getBlockHashByNumber]] hook for `BLOCKHASH`, the EIP-161 `accountStartNonce`/`noEmptyAccounts` policy, and
  *     the EIP-161 [[touchedAccounts]] set.
  *
  * Immutable / functional (the seam contract): every mutation returns a new instance sharing the (immutable) trie and
  * the (mutable, side-effecting) `DataSource`/`MptStorage` retrieval backing. Nothing is written to the node store or
  * the code store until [[persist]]; a discarded instance is a rolled-back transaction. `stateRootHash` reflects the
  * accounts trie root — updated only by [[persist]].
  *
  * **P0 node-store keying.** The accounts trie and every storage sub-trie share one [[MptStorage]]. That is
  * collision-free for a **hash-keyed** store (`InMemoryMptStorage`, the DoD/test backing) because nodes are keyed by
  * their own keccak hash. Per-account **owner-scoping** for a path-keyed `PersistedMptStorage` (`Location(owner =
  * Some(accountHash), …)`) is the L2 `INodeStorage` path-scheme concern that L2 defers as SNAP-contingent; a P0 world
  * must be backed by a hash-keyed store. (Flat-state O(1) slot reads and the TrieLog leaf-diff are later-L4 / optional,
  * not P0.)
  */
final class InMemoryWorldState private (
    val mptStorage: MptStorage,
    val codeStorage: CodeStorage,
    val accountsStateTrie: MerklePatriciaTrie[Address, Account],
    val contractStorages: Map[Address, InMemoryAccountStorage],
    val accountCodes: Map[Address, ByteString],
    val getBlockHashByNumber: BigInt => Option[Hash],
    override val accountStartNonce: UInt256,
    val touchedAccounts: Set[Address],
    val noEmptyAccountsCond: Boolean,
    val mutations: MutationSink
) extends WorldState[InMemoryWorldState, InMemoryAccountStorage]:

  override def getAccount(address: Address): Option[Account] = accountsStateTrie.get(address)

  override def getEmptyAccount: Account = Account.empty(accountStartNonce)

  override def saveAccount(address: Address, account: Account): InMemoryWorldState =
    mutations.recordAccount(address)
    copyWith(accountsStateTrie = accountsStateTrie.put(address, account))

  override protected def deleteAccount(address: Address): InMemoryWorldState =
    mutations.recordAccount(address)
    copyWith(
      accountsStateTrie = accountsStateTrie.remove(address),
      contractStorages = contractStorages - address,
      accountCodes = accountCodes - address
    )

  override def getCode(address: Address): ByteString =
    accountCodes.getOrElse(
      address,
      getAccount(address).flatMap(account => codeStorage.get(account.codeHash.bytes)).getOrElse(ByteString.empty)
    )

  override def getStorage(address: Address): InMemoryAccountStorage =
    contractStorages.getOrElse(address, buildStorage(address))

  override def saveCode(address: Address, code: ByteString): InMemoryWorldState =
    mutations.recordCode(address)
    copyWith(accountCodes = accountCodes + (address -> code))

  override def saveStorage(address: Address, storage: InMemoryAccountStorage): InMemoryWorldState =
    copyWith(contractStorages = contractStorages + (address -> storage))

  override def touchAccounts(addresses: Address*): InMemoryWorldState =
    if noEmptyAccountsCond then copyWith(touchedAccounts = touchedAccounts ++ addresses.toSet)
    else this

  override protected def clearTouchedAccounts: InMemoryWorldState =
    copyWith(touchedAccounts = Set.empty)

  override protected def noEmptyAccounts: Boolean = noEmptyAccountsCond

  override def keepPrecompileTouched(world: InMemoryWorldState): InMemoryWorldState =
    if world.touchedAccounts.contains(ripmdContractAddress) then
      copyWith(touchedAccounts = touchedAccounts + ripmdContractAddress)
    else this

  override def getBlockHash(number: UInt256): Option[UInt256] =
    getBlockHashByNumber(number.toBigInt).map(hash => UInt256.fromBytes(hash.bytes))

  /** The accounts-state-trie root. Reflects committed state only — updated by [[persist]], not by in-flight mutations
    * held resident in the trie / pending maps.
    */
  def stateRootHash: ByteString = accountsStateTrie.getRootHash

  /** Delete the given accounts — the SELFDESTRUCT end-of-transaction sweep
    * ([[com.chipprbots.fukuii.evm.ExecutionResult.addressesToDelete]]). Public seam over the trait's `protected
    * deleteAccount`, used by the [[TransactionProcessor]]'s account-cleanup phase; applied only after a **successful**
    * transaction (a reverted tx destroys no accounts).
    */
  def deleteAccounts(addresses: Iterable[com.chipprbots.fukuii.bytes.Address]): InMemoryWorldState =
    addresses.foldLeft(this)((world, address) => world.deleteAccount(address))

  /** EIP-161 `Finalise(deleteEmptyObjects=true)` — remove every **touched** account that is now empty (zero nonce, zero
    * balance, no code), then clear the touched set. Under `noEmptyAccounts=false` the touched set is never populated
    * ([[touchAccounts]] is a no-op), so this is inert pre-EIP-161 (mirrors geth's `deleteEmptyObjects` flag = the
    * EIP-161 activation). A no-op empty-account touch (e.g. `addBalance(coinbase, 0)`) followed by this sweep nets to
    * nothing, exactly as geth: the account is touched then deleted, leaving the state root unchanged.
    */
  def deleteEmptyTouchedAccounts: InMemoryWorldState =
    val swept = touchedAccounts.foldLeft(this)((world, address) =>
      if world.isAccountDead(address) then world.deleteAccount(address) else world
    )
    swept.clearTouchedAccounts

  /** Flush all pending changes to the backing stores and return the committed world (its [[stateRootHash]] is the new
    * state root). Persists in **code → contract storage → accounts trie** order, so each account's `codeHash` and
    * `storageRoot` are finalized before the accounts trie is committed.
    */
  def persist: InMemoryWorldState =
    persistAccountsStateTrie(persistContractStorage(persistCode(this)))

  private def persistCode(world: InMemoryWorldState): InMemoryWorldState =
    world.accountCodes.foldLeft(world) { case (updated, (address, code)) =>
      val codeHash = kec256(code)
      val savedCodeStorage = updated.codeStorage.put(codeHash, code)
      updated.copyWith(
        codeStorage = savedCodeStorage,
        accountsStateTrie =
          updated.accountsStateTrie.put(address, updated.getGuaranteedAccount(address).copy(codeHash = Hash(codeHash))),
        accountCodes = Map.empty
      )
    }

  private def persistContractStorage(world: InMemoryWorldState): InMemoryWorldState =
    world.contractStorages.foldLeft(world) { case (updated, (address, storage)) =>
      val persistedStorage = storage.persist
      updated.copyWith(
        contractStorages = updated.contractStorages + (address -> persistedStorage),
        accountsStateTrie = updated.accountsStateTrie
          .put(address, updated.getGuaranteedAccount(address).copy(storageRoot = persistedStorage.storageRoot))
      )
    }

  private def persistAccountsStateTrie(world: InMemoryWorldState): InMemoryWorldState =
    world.copyWith(accountsStateTrie = world.accountsStateTrie.commit())

  /** Build a fresh storage sub-trie for `address`, rooted at the account's `storageRoot` (or the empty-storage root for
    * a non-existent account), over the shared node store. Threads the world's [[mutations]] sink (and `address` as the
    * owner) so slot writes are recorded for the P6 [[BlockStateDiff]] — a branch-free no-op on the baseline path.
    */
  private def buildStorage(address: Address): InMemoryAccountStorage =
    val storageRoot = getAccount(address).map(_.storageRoot).getOrElse(Account.EmptyStorageRootHash)
    val trie = MerklePatriciaTrie[UInt256, BigInt](storageRoot.bytes, mptStorage)(using
      StateMpt.storageKeyEncoder,
      StateMpt.storageValueSerializer
    )
    new InMemoryAccountStorage(trie, address, mutations)

  /** Install a [[MutationSink]] for per-block [[BlockStateDiff]] collection — used by
    * [[BlockProcessor.processBlockWithOutcome]] to attach a [[MutationSink.Recording]] before executing a block. The
    * baseline path never calls this, so `mutations` stays [[MutationSink.NoTracking]] (the structural zero-cost
    * invariant, RX-L4-16). Storages already resident in [[contractStorages]] keep their prior sink; in practice the
    * sink is installed on a fresh world with no resident storages, so every sub-trie is built with it.
    */
  def withMutationSink(sink: MutationSink): InMemoryWorldState = copyWith(mutations = sink)

  private def copyWith(
      codeStorage: CodeStorage = codeStorage,
      accountsStateTrie: MerklePatriciaTrie[Address, Account] = accountsStateTrie,
      contractStorages: Map[Address, InMemoryAccountStorage] = contractStorages,
      accountCodes: Map[Address, ByteString] = accountCodes,
      touchedAccounts: Set[Address] = touchedAccounts,
      mutations: MutationSink = mutations
  ): InMemoryWorldState =
    new InMemoryWorldState(
      mptStorage,
      codeStorage,
      accountsStateTrie,
      contractStorages,
      accountCodes,
      getBlockHashByNumber,
      accountStartNonce,
      touchedAccounts,
      noEmptyAccountsCond,
      mutations
    )

object InMemoryWorldState:

  /** Build a world rooted at `stateRootHash` (use `MptNode.EmptyRootHash` for a fresh chain).
    *
    * Constructor params are trimmed to P0: an `ethCompatibleStorage` toggle is unnecessary since geth-compatible
    * storage is the only consensus form fukuii supports, and a `flatSlotStorage` O(1)-read cache (a later-L4 / optional
    * perf seam) is out of P0 scope.
    */
  def apply(
      codeStorage: CodeStorage,
      mptStorage: MptStorage,
      getBlockHashByNumber: BigInt => Option[Hash],
      accountStartNonce: UInt256,
      stateRootHash: ByteString,
      noEmptyAccounts: Boolean
  ): InMemoryWorldState =
    val accountsStateTrie = MerklePatriciaTrie[Address, Account](stateRootHash, mptStorage)(using
      StateMpt.addressKeyEncoder,
      StateMpt.accountSerializer
    )
    new InMemoryWorldState(
      mptStorage = mptStorage,
      codeStorage = codeStorage,
      accountsStateTrie = accountsStateTrie,
      contractStorages = Map.empty,
      accountCodes = Map.empty,
      getBlockHashByNumber = getBlockHashByNumber,
      accountStartNonce = accountStartNonce,
      touchedAccounts = Set.empty,
      noEmptyAccountsCond = noEmptyAccounts,
      mutations = MutationSink.NoTracking
    )
