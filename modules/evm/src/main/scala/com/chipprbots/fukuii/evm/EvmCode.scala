package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.crypto.kec256

/** Holds a program's code and provides utilities for accessing it (defaulting to zeroes when out of scope).
  *
  * P1 builds the code-access surface (`getByte`/`getBytes`/`length`/`codeHash`). The AS-IS `validJumpDestinations`
  * jump-dest analysis is **deferred to P2**: it classifies each byte via the fork opcode table (`PushOp`/`JUMPDEST`),
  * which P2's opcode set supplies.
  *
  * @param code
  *   the EVM bytecode as bytes
  */
final case class EvmCode(code: ByteString):

  def getByte(pc: Int): Byte =
    code.lift(pc).getOrElse(0.toByte)

  def getBytes(from: Int, size: Int): ByteString =
    val slice = code.slice(from, from + size)
    if slice.length >= size then slice
    else slice ++ ByteString(Array.fill[Byte](size - slice.length)(0))

  val length: Int = code.size

  lazy val codeHash: ByteString =
    kec256(code)

  /** The set of valid `JUMPDEST` (0x5b) positions — a byte `0x5b` counts only when it is an *opcode*, never when it
    * falls inside a `PUSH1`–`PUSH32` (0x60–0x7f) immediate operand. Computed by a single forward scan that skips `n =
    * op - 0x60 + 1` operand bytes after each push. Pure byte analysis (no opcode ADT needed); `JUMP`/`JUMPI` gate on it
    * (YP `D_j`, geth `codeBitmap`/`validJumpdests`).
    */
  lazy val validJumpDestinations: Set[Int] =
    val builder = Set.newBuilder[Int]
    var i = 0
    while i < length do
      val op = code(i) & 0xff
      if op == 0x5b then builder += i
      if op >= 0x60 && op <= 0x7f then i += (op - 0x60 + 1) + 1
      else i += 1
    builder.result()
