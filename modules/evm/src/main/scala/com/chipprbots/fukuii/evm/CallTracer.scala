package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.util.control.NonFatal

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256

/** One node of the go-ethereum `callTracer` call tree, as an **immutable snapshot of raw EVM-native values**. L9
  * hex-encodes the addresses / bytes / value and shapes the `debug_traceTransaction` (`{tracer: "callTracer"}`) JSON.
  *
  * @param callType
  *   `"CALL"`, `"STATICCALL"`, `"DELEGATECALL"`, `"CALLCODE"`, `"CREATE"`, or `"CREATE2"`
  * @param calls
  *   the ordered nested sub-calls
  */
final case class CallFrame(
    callType: String,
    from: Address,
    to: Address,
    gas: BigInt,
    gasUsed: BigInt,
    value: UInt256,
    input: ByteString,
    output: ByteString,
    error: Option[String],
    revertReason: Option[String],
    calls: Seq[CallFrame]
)

/** Native `callTracer` (go-ethereum `eth/tracers/native/call.go`): builds the nested call tree for
  * `debug_traceTransaction` / `debug_traceCall`.
  *
  * A **role-gated** per-execution [[ExecutionTracer]] (RX-L3-16, R2). The **root** frame is the top-level call/create,
  * established from [[onTxStart]] (the L4 tx boundary); sub-calls come from the L3 interpreter's [[onCallEnter]] /
  * [[onCallExit]] (`callDepth > 0`). Collection uses an internal mutable builder; [[result]] returns the immutable
  * [[CallFrame]] tree for L9.
  *
  * @param onlyTopCall
  *   capture only the top-level call (skip sub-calls)
  */
class CallTracer(onlyTopCall: Boolean = false) extends ExecutionTracer:

  /** Internal mutable accumulator; frozen to the immutable [[CallFrame]] at [[result]]. */
  final private class Builder(
      val callType: String,
      val from: Address,
      val to: Address,
      val gas: BigInt,
      val value: UInt256,
      val input: ByteString
  ):
    var gasUsed: BigInt = 0
    var output: ByteString = ByteString.empty
    var error: Option[String] = None
    var revertReason: Option[String] = None
    val calls: mutable.ArrayBuffer[Builder] = mutable.ArrayBuffer.empty

    def freeze: CallFrame =
      CallFrame(callType, from, to, gas, gasUsed, value, input, output, error, revertReason, calls.toSeq.map(_.freeze))

  private val callStack = mutable.Stack[Builder]()
  private var rootFrame: Option[Builder] = None

  override def onTxStart(from: Address, to: Option[Address], gas: BigInt, value: UInt256, input: ByteString): Unit =
    val callType = if to.isDefined then "CALL" else "CREATE"
    val frame = Builder(callType, from, to.getOrElse(Address(UInt256.Zero)), gas, value, input)
    callStack.push(frame)
    rootFrame = Some(frame)
    ()

  override def onTxEnd(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit =
    if callStack.nonEmpty then
      val frame = callStack.pop()
      close(frame, gasUsed, output, error)
    ()

  override def onCallEnter(
      callType: String,
      caller: Address,
      recipient: Address,
      gas: BigInt,
      value: UInt256,
      input: ByteString
  ): Unit =
    if !onlyTopCall then callStack.push(Builder(callType, caller, recipient, gas, value, input))
    ()

  override def onCallExit(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit =
    if !onlyTopCall && callStack.size > 1 then
      val frame = callStack.pop()
      close(frame, gasUsed, output, error)
      if callStack.nonEmpty then callStack.top.calls += frame
    ()

  /** The completed call tree (root frame), or `None` if no tx was traced. */
  def result: Option[CallFrame] = rootFrame.map(_.freeze)

  private def close(frame: Builder, gasUsed: BigInt, output: ByteString, error: Option[String]): Unit =
    frame.gasUsed = gasUsed
    frame.output = output
    frame.error = error
    // The error string is the interpreter's HaltReason.toString; L9 normalises it to the geth-compatible label.
    // Only the ABI revert-reason parse ("Error(string)") lives here.
    if error.exists(_.contains("execution reverted")) && output.length >= 4 then
      frame.revertReason = parseRevertReason(output)

  /** Parse a Solidity revert reason from ABI-encoded `Error(string)` data: `0x08c379a0 ‖ offset ‖ length ‖ utf8`. */
  private def parseRevertReason(data: ByteString): Option[String] =
    if data.length < 68 then None
    else if data.take(4) != ByteString(0x08, 0xc3, 0x79, 0xa0) then None
    else
      try
        val offset = BigInt(1, data.slice(4, 36).toArray).toInt
        val length = BigInt(1, data.slice(36 + offset, 68 + offset).toArray).toInt
        if data.length < 68 + offset + length then None
        else Some(new String(data.slice(68 + offset, 68 + offset + length).toArray, "UTF-8"))
      catch case NonFatal(_) => None
