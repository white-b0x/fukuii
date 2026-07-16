package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Account

/** The single entry point to all VM interactions with the persisted state — a **pure abstract seam**. The VM is
  * *parameterized* over `WS`/`S` and never imports `storage`/`trie`/`mpt`; the concrete state-backed implementation
  * lives at L4 (`execution`), which supplies a `WS` bound to the L2 trie. Implementations are meant to be immutable so
  * that rolling back a transaction is equivalent to discarding resulting changes; changes are kept in memory and
  * applied only after a transaction completes without errors (this does not forbid mutable caches for DB retrieval).
  *
  * **P1 scope note.** The AS-IS `july-fourth` `WorldStateProxy` carried a set of concrete *default* helpers —
  * `transfer`/`guaranteedTransfer`, `initialiseAccount`, `removeAllEther`, `increaseNonce`, `isAccountDead`,
  * `nonEmptyCodeOrNonce*`, `createAddress`/`create2Address` — that mutate/inspect an [[Account]] through behavior
  * methods (`increaseBalance`, `increaseNonce`, `isEmpty`, `nonEmptyCodeOrNonce`, `Account.EmptyCodeHash`,
  * `Account.EmptyStorageRootHash`). The L1 rebuild deliberately made `Account` a **pure value record** with none of
  * that behavior, so those helpers cannot be transcribed here. They are the account-lifecycle / value-transfer surface
  * the opcodes (P2 `CALL`/`CREATE`) and the pipeline (L4) drive — deferred until either the L1 `Account` behavior is
  * back-filled (forge/beacon-gated, consensus domain) or the logic is homed at L4. This seam keeps the state-access
  * contract the interpreter dispatches through; the derived helpers layer over it once `Account` behavior exists.
  */
trait WorldStateProxy[WS <: WorldStateProxy[WS, S], S <: Storage[S]]:
  self: WS =>

  def getAccount(address: Address): Option[Account]
  def saveAccount(address: Address, account: Account): WS
  protected def deleteAccount(address: Address): WS
  def getEmptyAccount: Account
  def touchAccounts(addresses: Address*): WS
  protected def clearTouchedAccounts: WS
  protected def noEmptyAccounts: Boolean
  protected def accountStartNonce: UInt256 = UInt256.Zero

  /** EIP-161 special case: a precompiled account touched-then-reverted must stay touched (parity/geth compat, to avoid
    * a chain rewind). See https://github.com/ethereum/EIPs/issues/716.
    */
  def keepPrecompileTouched(world: WS): WS

  def getCode(address: Address): ByteString
  def getStorage(address: Address): S
  def getBlockHash(number: UInt256): Option[UInt256]

  def saveCode(address: Address, code: ByteString): WS
  def saveStorage(address: Address, storage: S): WS

  def newEmptyAccount(address: Address): WS =
    saveAccount(address, getEmptyAccount)

  def accountExists(address: Address): Boolean =
    getAccount(address).isDefined

  def getBalance(address: Address): UInt256 =
    getAccount(address).map(a => a.balance.toUInt256).getOrElse(UInt256.Zero)
