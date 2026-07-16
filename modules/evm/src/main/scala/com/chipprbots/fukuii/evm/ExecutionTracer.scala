package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256

/** The execution-observation hook the interpreter loop fires per opcode and on sub-call entry/exit. Every method is an
  * **all-default no-op** (besu `OperationTracer` shape): a consumer overrides only the hooks it cares about, and the
  * disabled path costs nothing.
  *
  * **Branch-free disabled path (RX-L3-15, L3 plan §5).** The interpreter resolves its tracer from **one** slot (the
  * concrete [[EvmInterpreter.tracer]] field) — never the AS-IS two-slot `Option.foreach` (VM ctor *and* `env.tracer`).
  * The loop calls `tracer.onStep(...)` **unconditionally**; when that slot holds the monomorphic [[NoTracing]]
  * singleton, the JVM inlines the empty body and the call disappears (besu `NO_TRACING`, geth's nil-`Hooks`
  * short-circuit). No `Option` alloc, no per-opcode branch on the common (untraced) path.
  *
  * **P4/P6 scope.** P4 built this all-default trait + the [[NoTracing]] singleton (the branch-free disabled path). P6
  * (RX-L3-16) added the two tx-boundary hooks ([[onTxStart]]/[[onTxEnd]], besu `traceStartTransaction`/
  * `traceEndTransaction`) and the role-gated [[StructLogTracer]] / [[CallTracer]] / [[PrestateTracer]] / [[VmTracer]]
  * implementations — the OPTIONAL(archival/RPC role) machinery selected only when a tracing role is active. The role
  * tracers **collect raw EVM-native values** (no json4s / no `debug_traceTransaction` JSON shape); the RPC/JSON
  * formatting is L9. Every hook stays an all-default no-op so [[NoTracing]] keeps the disabled path branch-free.
  */
trait ExecutionTracer:

  /** Fired after each opcode executes, with the pre- and post-step [[ProgramState]]. Generic in the world/storage type
    * params so a single tracer instance observes every fork's state.
    */
  def onStep[W <: WorldState[W, S], S <: AccountStorage[S]](
      opCode: OpCode,
      before: ProgramState[W, S],
      after: ProgramState[W, S]
  ): Unit = ()

  /** Fired on entry to a `CALL`/`CALLCODE`/`DELEGATECALL`/`STATICCALL`/`CREATE`/`CREATE2` sub-execution. */
  def onCallEnter(
      callType: String,
      caller: Address,
      recipient: Address,
      gas: BigInt,
      value: UInt256,
      input: ByteString
  ): Unit = ()

  /** Fired on exit from a sub-execution, with the gas actually consumed, the returned bytes, and the error if any. */
  def onCallExit(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit = ()

  /** Fired once for the top-level transaction before execution begins (besu `traceStartTransaction`). This is the only
    * place a role tracer sees the **root** call/create frame — the L3 interpreter loop knows only calls/creates and
    * fires [[onCallEnter]] for sub-executions (`callDepth > 0`) only, so the tx-level frame is driven at the tx
    * boundary by the L4 transaction executor. Defined here (all-default no-op) so [[NoTracing]] elides it; L3 itself
    * never fires it.
    */
  def onTxStart(from: Address, to: Option[Address], gas: BigInt, value: UInt256, input: ByteString): Unit = ()

  /** Fired once when the top-level transaction returns (besu `traceEndTransaction`) — L4-driven at the tx boundary,
    * like [[onTxStart]]. Carries the tx-level gas used, output, and error for the role tracer's summary/root frame.
    */
  def onTxEnd(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit = ()

/** The monomorphic no-op tracer — the branch-free disabled path (besu `NO_TRACING`). Holding this in the interpreter's
  * single tracer slot lets the JVM elide every hook call.
  */
object NoTracing extends ExecutionTracer

/** The default tracer any interpreter resolves when a caller does not wire one — the disabled (branch-free) path. */
given ExecutionTracer = NoTracing
