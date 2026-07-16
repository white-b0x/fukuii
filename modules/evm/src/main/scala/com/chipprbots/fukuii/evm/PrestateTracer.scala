package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Account

/** A touched account's state snapshot — balance / nonce / code / (touched, non-zero) storage slots — as **raw values**.
  * L9 hex-encodes and, in diff mode, reduces the post-state to changed fields for the go-ethereum `prestateTracer`
  * shape.
  */
final case class AccountState(
    balance: BigInt,
    nonce: BigInt,
    code: ByteString,
    storage: Map[UInt256, BigInt]
)

/** Native `prestateTracer` (go-ethereum `eth/tracers/native/prestate.go`): records the accounts (and storage slots)
  * touched by a transaction so the pre-transaction state — and, in diff mode, the post-transaction state — can be
  * reported.
  *
  * A **role-gated** per-execution [[ExecutionTracer]] (RX-L3-16, R2). It observes which addresses/slots are touched via
  * [[onTxStart]] (tx from/to), [[onCallEnter]] (sub-call from/to), and [[onStep]] (SLOAD/SSTORE slots, BALANCE /
  * EXTCODE* / SELFDESTRUCT address operands). The account **values** are read from the supplied world snapshots
  * ([[preWorld]], and [[setPostWorld]] for diff mode); L9 formats them (and applies the diff-field reduction).
  *
  * The pre/post world snapshots are the concrete state-backed `W` (L4). Because the tracer only ever *reads* the world
  * to build the snapshot at [[prestate]]/[[poststate]], it never influences execution.
  *
  * @param preWorld
  *   the world snapshot taken before transaction execution
  * @param diffMode
  *   whether L9 should emit the `{pre, post}` diff (vs. the pre-state only) — carried here for L9 to read
  */
class PrestateTracer[W <: WorldState[W, S], S <: AccountStorage[S]](
    val preWorld: W,
    diffMode: Boolean = false
) extends ExecutionTracer:

  private val touched = mutable.LinkedHashSet[Address]()
  private val touchedStorage = mutable.HashMap[Address, mutable.LinkedHashSet[UInt256]]()
  private var postWorld: Option[W] = None

  /** Supply the post-execution world for diff mode. */
  def setPostWorld(world: W): Unit =
    postWorld = Some(world)

  /** Whether the diff (`{pre, post}`) shape was requested — read by L9 to choose the output form. */
  def isDiffMode: Boolean = diffMode

  override def onTxStart(from: Address, to: Option[Address], gas: BigInt, value: UInt256, input: ByteString): Unit =
    touched += from
    to.foreach(addr => touched += addr)
    ()

  override def onCallEnter(
      callType: String,
      caller: Address,
      recipient: Address,
      gas: BigInt,
      value: UInt256,
      input: ByteString
  ): Unit =
    touched += caller
    touched += recipient
    ()

  override def onStep[W2 <: WorldState[W2, S2], S2 <: AccountStorage[S2]](
      opCode: OpCode,
      prevState: MessageFrame[W2, S2],
      nextState: MessageFrame[W2, S2]
  ): Unit =
    opCode match
      case SLOAD | SSTORE if prevState.stack.size >= 1 =>
        val addr = prevState.env.ownerAddr
        touched += addr
        touchedStorage.getOrElseUpdate(addr, mutable.LinkedHashSet.empty) += prevState.stack.toSeq.head
      case BALANCE | EXTCODESIZE | EXTCODECOPY | EXTCODEHASH if prevState.stack.size >= 1 =>
        touched += Address(prevState.stack.toSeq.head)
      case SELFDESTRUCT if prevState.stack.size >= 1 =>
        touched += Address(prevState.stack.toSeq.head)
        touched += prevState.env.ownerAddr
      case _ => ()
    ()

  /** The touched accounts, in first-touch order. */
  def touchedAddresses: Seq[Address] = touched.toSeq

  /** The pre-transaction state of every touched account that exists in [[preWorld]]. */
  def prestate: Map[Address, AccountState] = buildStates(preWorld)

  /** The post-transaction state of every touched account, if the post-world was supplied ([[setPostWorld]]). */
  def poststate: Option[Map[Address, AccountState]] = postWorld.map(buildStates)

  private def buildStates(world: W): Map[Address, AccountState] =
    touched.toSeq.flatMap(addr => world.getAccount(addr).map(acc => addr -> accountState(addr, acc, world))).toMap

  private def accountState(addr: Address, account: Account, world: W): AccountState =
    val storage = world.getStorage(addr)
    val slots = touchedStorage
      .getOrElse(addr, mutable.LinkedHashSet.empty)
      .toSeq
      .flatMap { key =>
        val v = storage.load(key)
        if v != BigInt(0) then Some(key -> v) else None
      }
      .toMap
    AccountState(
      balance = account.balance.toUInt256.toBigInt,
      nonce = account.nonce.toBigInt,
      code = world.getCode(addr),
      storage = slots
    )
