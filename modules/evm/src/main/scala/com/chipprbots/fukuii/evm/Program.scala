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
final case class Program(code: ByteString):

  def getByte(pc: Int): Byte =
    code.lift(pc).getOrElse(0.toByte)

  def getBytes(from: Int, size: Int): ByteString =
    val slice = code.slice(from, from + size)
    if slice.length >= size then slice
    else slice ++ ByteString(Array.fill[Byte](size - slice.length)(0))

  val length: Int = code.size

  lazy val codeHash: ByteString =
    kec256(code)
