package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.domain.Wei

/** Traces an internal call (`CALL`/`CALLCODE`/`DELEGATECALL`/`STATICCALL`/`CREATE`/`CREATE2`) during code execution.
  * Trace-only (used by the Ethereum Test Suite / debug tracing) — **not** consensus state (it does not affect the state
  * root), so it is correctness-neutral.
  *
  * `opcode` is carried as the **raw opcode byte** rather than the `OpCode` ADT: this is a trace record consumed by the
  * test suite / debug tracer, so it stores exactly the byte the interpreter dispatched on with no need to round-trip
  * through the ADT. `gasLimit` is a plain `BigInt` gas counter, per the plan's gas model (§3/§9).
  *
  * @param opcode
  *   the raw byte of the opcode that caused the internal tx
  * @param from
  *   the account that executes the opcode
  * @param to
  *   the account to which the call was made
  * @param gasLimit
  *   gas available to the sub-execution
  * @param data
  *   call data
  * @param value
  *   call value
  */
final case class InternalTransaction(
    opcode: Byte,
    from: Address,
    to: Option[Address],
    gasLimit: BigInt,
    data: ByteString,
    value: Wei
)
