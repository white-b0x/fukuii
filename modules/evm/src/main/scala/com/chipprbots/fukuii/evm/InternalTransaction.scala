package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.domain.Wei

/** Traces an internal call (`CALL`/`CALLCODE`/`DELEGATECALL`/`STATICCALL`/`CREATE`/`CREATE2`) during code execution.
  * Trace-only (used by the Ethereum Test Suite / debug tracing) — **not** consensus state (it does not affect the state
  * root), so it is correctness-neutral.
  *
  * **P1 retype:** the AS-IS `opcode: OpCode` field is carried here as the **raw opcode byte** because the `OpCode` ADT
  * is P2's deliverable. P2 widens `opcode: Byte` back to the `OpCode` type when the opcode set lands. `gasLimit` is a
  * plain `BigInt` gas counter (the AS-IS `GasAmount` value type was not carried into the rebuild; the plan's gas model
  * is `BigInt`, §3/§9).
  *
  * @param opcode
  *   the raw byte of the opcode that caused the internal tx (P2 widens to `OpCode`)
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
