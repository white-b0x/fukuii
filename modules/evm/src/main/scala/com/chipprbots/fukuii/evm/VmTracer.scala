package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256

/** One executed operation of a Parity/OpenEthereum `vmTrace` frame, as an **immutable snapshot of raw values**. L9
  * hex-encodes and shapes the `trace_replayTransaction` / `trace_replayBlockTransactions` `vmTrace.ops[].ex` JSON.
  *
  * @param exPush
  *   the words this op pushed (top-most first), raw [[UInt256]]
  * @param exMem
  *   `(offset, data)` for a memory write (`MSTORE`/`MSTORE8`), else `None`
  * @param exStore
  *   `(key, value)` for a storage write (`SSTORE`), else `None`
  * @param sub
  *   the nested sub-frame this op triggered (`CALL`/`CREATE`), else `None`
  */
final case class VmOp(
    pc: Int,
    cost: BigInt,
    exUsed: BigInt,
    exPush: Seq[UInt256],
    exMem: Option[(BigInt, ByteString)],
    exStore: Option[(UInt256, UInt256)],
    sub: Option[VmFrame]
)

/** A `vmTrace` frame: the executing code plus its ordered ops (each op may carry a nested `sub` frame). */
final case class VmFrame(code: ByteString, ops: Seq[VmOp])

/** Parity/OpenEthereum-format `vmTrace` tracer for `trace_replayTransaction` / `trace_replayBlockTransactions`.
  *
  * A **role-gated** per-execution [[ExecutionTracer]] (RX-L3-16, R2). The frame stack mirrors call depth: [[onTxStart]]
  * pushes the root frame, [[onCallEnter]] pushes a sub-frame, and [[onCallExit]] freezes it and attaches it as the
  * `sub` of the triggering op in the parent frame. Collection uses internal mutable builders; [[result]] returns the
  * immutable [[VmFrame]] tree for L9.
  */
class VmTracer extends ExecutionTracer:

  /** Internal mutable op accumulator; frozen to [[VmOp]] at [[FrameBuilder.freeze]]. */
  final private class OpBuilder(
      val pc: Int,
      val cost: BigInt,
      val exUsed: BigInt,
      val exPush: Seq[UInt256],
      val exMem: Option[(BigInt, ByteString)],
      val exStore: Option[(UInt256, UInt256)]
  ):
    var sub: Option[VmFrame] = None
    def freeze: VmOp = VmOp(pc, cost, exUsed, exPush, exMem, exStore, sub)

  /** Internal mutable frame accumulator; frozen to [[VmFrame]]. */
  final private class FrameBuilder:
    var code: ByteString = ByteString.empty
    val ops: mutable.ArrayBuffer[OpBuilder] = mutable.ArrayBuffer.empty
    def freeze: VmFrame = VmFrame(code, ops.toSeq.map(_.freeze))

  private val frameStack = mutable.Stack[FrameBuilder]()
  private var rootFrame: Option[FrameBuilder] = None

  override def onTxStart(from: Address, to: Option[Address], gas: BigInt, value: UInt256, input: ByteString): Unit =
    val frame = FrameBuilder()
    frameStack.push(frame)
    rootFrame = Some(frame)
    ()

  override def onStep[W <: WorldState[W, S], S <: AccountStorage[S]](
      opCode: OpCode,
      prevState: ProgramState[W, S],
      nextState: ProgramState[W, S]
  ): Unit =
    if frameStack.nonEmpty then
      val frame = frameStack.top
      if frame.code.isEmpty then frame.code = prevState.env.program.code

      val exPush: Seq[UInt256] =
        if opCode.alpha > 0 then nextState.stack.toSeq.take(opCode.alpha) else Seq.empty

      val exMem: Option[(BigInt, ByteString)] = opCode match
        case MSTORE if prevState.stack.size >= 2 =>
          val offset = prevState.stack.toSeq.head
          Some((offset.toBigInt, nextState.memory.load(offset, UInt256(32))._1))
        case MSTORE8 if prevState.stack.size >= 2 =>
          val offset = prevState.stack.toSeq.head
          Some((offset.toBigInt, nextState.memory.load(offset, UInt256(1))._1))
        case _ => None

      val exStore: Option[(UInt256, UInt256)] = opCode match
        case SSTORE if prevState.stack.size >= 2 =>
          Some((prevState.stack.toSeq(0), prevState.stack.toSeq(1)))
        case _ => None

      frame.ops += OpBuilder(
        pc = prevState.pc,
        cost = prevState.gas - nextState.gas,
        exUsed = nextState.gas,
        exPush = exPush,
        exMem = exMem,
        exStore = exStore
      )
    ()

  override def onCallEnter(
      callType: String,
      caller: Address,
      recipient: Address,
      gas: BigInt,
      value: UInt256,
      input: ByteString
  ): Unit =
    frameStack.push(FrameBuilder())
    ()

  override def onCallExit(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit =
    if frameStack.size > 1 then
      val encoded = frameStack.pop().freeze
      if frameStack.nonEmpty && frameStack.top.ops.nonEmpty then frameStack.top.ops.last.sub = Some(encoded)
    ()

  /** The completed vmTrace tree (root frame), or `None` if no tx was traced. */
  def result: Option[VmFrame] = rootFrame.map(_.freeze)
