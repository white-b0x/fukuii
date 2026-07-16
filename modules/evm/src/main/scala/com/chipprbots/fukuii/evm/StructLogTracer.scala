package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.fukuii.bytes.UInt256

/** A single step in EVM execution — the go-ethereum `structLog` shape, collected as **raw EVM-native values** (no hex,
  * no JSON). L9 (`debug_traceTransaction`) hex-encodes the stack / memory words / storage slots and wraps the tx-level
  * `{gas, failed, returnValue}` summary.
  *
  *   - `stack` — the full operand stack, top-most element first ([[Stack.toSeq]] order), raw [[UInt256]] words.
  *   - `memory` — 32-byte memory words as raw [[ByteString]]s (present only when memory capture is enabled).
  *   - `storage` — the SLOAD/SSTORE slot→value touched this step, raw (present only when storage capture is enabled).
  */
final case class StructLog(
    pc: Int,
    op: String,
    gas: BigInt,
    gasCost: BigInt,
    depth: Int,
    stack: Seq[UInt256],
    memory: Option[Seq[ByteString]],
    storage: Option[Map[UInt256, UInt256]],
    error: Option[String]
)

/** Collects an opcode-by-opcode execution trace in go-ethereum `structLog` form — the data behind
  * `debug_traceTransaction` with the default (struct-logger) tracer.
  *
  * A **role-gated** [[ExecutionTracer]] (RX-L3-16): an instance is placed in the interpreter's single
  * [[EvmInterpreter.tracer]] slot only when the struct-log role is active; the default [[NoTracing]] path pays nothing.
  * Per-execution state (R2) — no process-global mutable state.
  *
  * `memory`/`storage` capture is opt-in (expensive) and `limit` caps the number of steps (0 = unlimited), matching the
  * go-ethereum logger config.
  *
  * @param enableMemory
  *   capture the memory snapshot per step
  * @param enableStorage
  *   capture the SLOAD/SSTORE slot diff per step
  * @param limit
  *   maximum number of steps to capture (0 = unlimited)
  */
class StructLogTracer(
    enableMemory: Boolean = false,
    enableStorage: Boolean = false,
    limit: Int = 0
) extends ExecutionTracer:

  private val steps = mutable.ArrayBuffer[StructLog]()
  private var _gas: BigInt = 0
  private var _failed: Boolean = false
  private var _returnValue: ByteString = ByteString.empty

  override def onStep[W <: WorldState[W, S], S <: AccountStorage[S]](
      opCode: OpCode,
      prevState: MessageFrame[W, S],
      nextState: MessageFrame[W, S]
  ): Unit =
    if limit > 0 && steps.size >= limit then ()
    else
      val gasCost: BigInt = prevState.gas - nextState.gas

      val memorySnapshot: Option[Seq[ByteString]] =
        if enableMemory then
          val mem = prevState.memory
          if mem.size > 0 then Some((0 until mem.size by 32).map(off => mem.load(UInt256(off), UInt256(32))._1).toSeq)
          else Some(Seq.empty)
        else None

      val storageSnapshot: Option[Map[UInt256, UInt256]] =
        if enableStorage then
          opCode match
            case SLOAD if prevState.stack.size >= 1 =>
              Some(Map(prevState.stack.toSeq.head -> nextState.stack.toSeq.head))
            case SSTORE if prevState.stack.size >= 2 =>
              Some(Map(prevState.stack.toSeq(0) -> prevState.stack.toSeq(1)))
            case _ => None
        else None

      steps += StructLog(
        pc = prevState.pc,
        op = opCode.toString,
        gas = prevState.gas,
        gasCost = gasCost,
        depth = prevState.env.callDepth + 1, // go-ethereum uses 1-based depth
        stack = prevState.stack.toSeq,
        memory = memorySnapshot,
        storage = storageSnapshot,
        error = nextState.error.map(_.toString)
      )
      ()

  /** Set the tx-level summary directly (the L4/L9 alternative to driving [[onTxEnd]]). */
  def setResult(gas: BigInt, returnValue: ByteString, failed: Boolean): Unit =
    _gas = gas
    _returnValue = returnValue
    _failed = failed

  override def onTxEnd(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit =
    setResult(gasUsed, output, error.isDefined)

  def getSteps: Seq[StructLog] = steps.toSeq
  def gas: BigInt = _gas
  def failed: Boolean = _failed
  def returnValue: ByteString = _returnValue
