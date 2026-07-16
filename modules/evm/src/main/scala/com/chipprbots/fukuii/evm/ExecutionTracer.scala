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
  * **P4 scope.** This is the minimal all-default trait + the [[NoTracing]] singleton only. The role-gated structlog /
  * call / prestate implementations (typed over the opcode ADT, emitting `debug_traceTransaction` / `trace_*` output)
  * are the OPTIONAL(archival/RPC role) seam built in **P6** (RX-L3-16) — the hook is defined now, the full tracer ships
  * later.
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

/** The monomorphic no-op tracer — the branch-free disabled path (besu `NO_TRACING`). Holding this in the interpreter's
  * single tracer slot lets the JVM elide every hook call.
  */
object NoTracing extends ExecutionTracer

/** The default tracer any interpreter resolves when a caller does not wire one — the disabled (branch-free) path. */
given ExecutionTracer = NoTracing
