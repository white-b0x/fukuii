package com.chipprbots.fukuii.evm

import com.chipprbots.fukuii.bytes.UInt256

/** Marker trait for errors that may occur during program execution. */
sealed trait HaltReason:
  val useWholeGas: Boolean = true

case class InvalidOpCode(code: Byte) extends HaltReason:
  override def toString: String =
    f"${getClass.getSimpleName}(0x${code.toInt & 0xff}%02x)"

case class OpCodeNotAvailableInStaticContext(code: Byte) extends HaltReason:
  override def toString: String =
    f"${getClass.getSimpleName}(0x${code.toInt & 0xff}%02x)"

case object OutOfGas extends HaltReason

case class InvalidJump(dest: UInt256) extends HaltReason:
  override def toString: String =
    f"${getClass.getSimpleName}(${dest.toHex})"

sealed trait StackError extends HaltReason
case object StackOverflow extends StackError
case object StackUnderflow extends StackError

case object InvalidCall extends HaltReason
case object PreCompiledContractFail extends HaltReason

case object RevertOccurs extends HaltReason:
  override val useWholeGas: Boolean = false

case object ReturnDataOverflow extends HaltReason

case object InvalidCode extends HaltReason

case object InitCodeSizeLimit extends HaltReason
