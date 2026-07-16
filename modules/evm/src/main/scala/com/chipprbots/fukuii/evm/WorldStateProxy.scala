package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.Wei
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.RLPValue
import com.chipprbots.fukuii.rlp.encode

/** The single entry point to all VM interactions with the persisted state — a **pure abstract seam**. The VM is
  * *parameterized* over `WS`/`S` and never imports `storage`/`trie`/`mpt`; the concrete state-backed implementation
  * lives at L4 (`execution`), which supplies a `WS` bound to the L2 trie. Implementations are meant to be immutable so
  * that rolling back a transaction is equivalent to discarding resulting changes; changes are kept in memory and
  * applied only after a transaction completes without errors (this does not forbid mutable caches for DB retrieval).
  *
  * **P2 fill (the P1 deferral resolved).** The account-lifecycle / value-transfer default helpers —
  * `transfer`/`guaranteedTransfer`, `initialiseAccount`, `removeAllEther`, `increaseNonce`, `isAccountDead`,
  * `nonEmptyCodeOrNonce*`, `createAddress`/`create2Address` — are now filled. The L1 rebuild kept [[Account]] a **pure
  * value record** (no `increaseBalance`/`isEmpty`/`nonEmptyCodeOrNonce` behavior methods on the record), so the
  * emptiness / non-empty checks are private helpers on **this seam**
  * ([[accountIsEmpty]]/[[accountNonEmptyCodeOrNonce]]) over the account fields, and the mutations go through
  * `Account.copy(...)` + `Account.empty()` + `Account.EmptyCodeHash`/`Account.EmptyStorageRootHash` — behavior lives on
  * the world-state seam (besu `MutableAccount`/`WorldUpdater`), never on the value record. The seam stays **abstract**
  * — the concrete state-backed implementation is L4; the DAG stays `domain`/`crypto`/`rlp` (no `storage` edge).
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

  /** The pre-compiled RIPEMD-160 address `0x…03` — the EIP-161 touched-then-reverted special case
    * ([[keepPrecompileTouched]]).
    */
  protected val ripmdContractAddress: Address = Address(UInt256(3))

  /** An account guaranteed to exist by the caller's context (the executing account, a transfer target). If it is
    * absent, that is a hard invariant violation — fail loud rather than fabricate an empty account.
    */
  protected def getGuaranteedAccount(address: Address): Account =
    getAccount(address).getOrElse(
      throw new IllegalStateException(s"Account not found for address $address")
    )

  // -- EIP-161 emptiness, computed over the pure Account value fields (no behavior on the record) --

  /** EIP-161: an account is *empty* when it has zero nonce (the network's account-start nonce), zero balance, and no
    * code. **Storage root is NOT checked** (a non-empty storage root does not make an account non-empty for EIP-161).
    */
  protected def accountIsEmpty(acc: Account): Boolean =
    acc.nonce == accountStartNonce && acc.balance.isZero && acc.codeHash == Account.EmptyCodeHash

  /** EIP-684: an account has non-empty code OR a non-start nonce (the "would a CREATE collide" check). */
  protected def accountNonEmptyCodeOrNonce(acc: Account): Boolean =
    acc.nonce != accountStartNonce || acc.codeHash != Account.EmptyCodeHash

  /** EIP-161: an account is *dead* when it is non-existent or empty. */
  def isAccountDead(address: Address): Boolean =
    getAccount(address).forall(accountIsEmpty)

  def nonEmptyCodeOrNonceAccount(address: Address): Boolean =
    getAccount(address).exists(accountNonEmptyCodeOrNonce)

  /** EIP-7610 (Prague): a create at `address` must revert if the account has non-empty code, a non-start nonce, OR
    * non-empty storage. Extends EIP-684 (which checked only code + nonce) with the storage-root test.
    */
  def nonEmptyCodeOrNonceOrStorageAccount(address: Address): Boolean =
    getAccount(address).exists { acc =>
      accountNonEmptyCodeOrNonce(acc) || acc.storageRoot != Account.EmptyStorageRootHash
    }

  def isZeroValueTransferToNonExistentAccount(address: Address, value: UInt256): Boolean =
    noEmptyAccounts && value.isZero && !accountExists(address)

  // -- Value transfer (EIP-161-aware) --

  /** Transfer `value` from `from` to `to`. A self-transfer or a zero-value transfer to a non-existent account under
    * EIP-161 only *touches* the sender (no state write); otherwise the balances move and both accounts are touched.
    */
  def transfer(from: Address, to: Address, value: UInt256): WS =
    if from == to || isZeroValueTransferToNonExistentAccount(to, value) then touchAccounts(from)
    else guaranteedTransfer(from, to, value).touchAccounts(from, to)

  /** The unconditional balance move — `from` is guaranteed to exist; `to` is created empty if absent. Balance
    * arithmetic goes through [[Wei]] (opaque over [[UInt256]]) via `Account.copy`.
    */
  def guaranteedTransfer(from: Address, to: Address, value: UInt256): WS =
    val debited = getGuaranteedAccount(from)
    val debitedAcc = debited.copy(balance = Wei(debited.balance.toUInt256 - value))
    val creditedBase = getAccount(to).getOrElse(getEmptyAccount)
    val creditedAcc = creditedBase.copy(balance = Wei(creditedBase.balance.toUInt256 + value))
    saveAccount(from, debitedAcc).saveAccount(to, creditedAcc)

  /** Initialise a freshly-created contract account (YP eq. 79): empty code + empty storage, nonce set to the network's
    * account-start nonce (or +1 under EIP-161, so the new account is not immediately empty).
    */
  def initialiseAccount(newAddress: Address): WS =
    val base = getAccount(newAddress)
      .getOrElse(getEmptyAccount)
      .copy(codeHash = Account.EmptyCodeHash, storageRoot = Account.EmptyStorageRootHash)
    val withNonce =
      if !noEmptyAccounts then base.copy(nonce = accountStartNonce)
      else base.copy(nonce = accountStartNonce + UInt256.One)
    saveAccount(newAddress, withNonce)

  /** Destroy an account's ether (SELFDESTRUCT to self) — the balance is zeroed and the account touched. */
  def removeAllEther(address: Address): WS =
    val debited = getGuaranteedAccount(address).copy(balance = Wei.Zero)
    saveAccount(address, debited).touchAccounts(address)

  /** Increase the nonce of a guaranteed account by one. */
  def increaseNonce(address: Address): WS =
    val acc = getGuaranteedAccount(address)
    saveAccount(address, acc.copy(nonce = acc.nonce + UInt256.One))

  /** YP eq. 82 — the CREATE contract address `keccak256(rlp([sender, nonce]))[12:]`. The sender is RLP-encoded as a
    * single 20-byte string (not a byte list), and the nonce is the creator's nonce **minus one** (the creator's nonce
    * was already incremented before the sub-execution). The 32-byte keccak is right-aligned to the low 20 bytes.
    */
  def createAddress(creatorAddr: Address): Address =
    val creatorAccount = getGuaranteedAccount(creatorAddr)
    val nonceMinusOne = (creatorAccount.nonce - UInt256.One).toBigInt
    val encoded = encode(RLPList(RLPValue(creatorAddr.bytes.toArray), RLPValue(minimalUnsignedBytes(nonceMinusOne))))
    Address.fromBytesTruncating(kec256(encoded))

  /** EIP-1014 — the CREATE2 address `keccak256(0xff ++ sender ++ salt ++ keccak256(initCode))[12:]`. */
  def create2Address(creatorAddr: Address, salt: UInt256, code: ByteString): Address =
    val prefix = ByteString(0xff.toByte)
    val preimage = prefix ++ creatorAddr.bytes ++ salt.bytes ++ kec256(code)
    Address.fromBytesTruncating(kec256(preimage.toArray))

  /** Minimal-length big-endian encoding of a non-negative integer (RLP scalar form): no leading zero bytes, and 0
    * encodes to the empty byte array.
    */
  private def minimalUnsignedBytes(n: BigInt): Array[Byte] =
    if n <= 0 then Array.emptyByteArray
    else
      val b = n.toByteArray
      if b.length > 1 && b(0) == 0 then b.tail else b
