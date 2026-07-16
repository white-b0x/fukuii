package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.Log
import com.chipprbots.fukuii.domain.Wei
import com.chipprbots.fukuii.evm.Uint256Evm.addmod
import com.chipprbots.fukuii.evm.Uint256Evm.byteOf
import com.chipprbots.fukuii.evm.Uint256Evm.byteSize
import com.chipprbots.fukuii.evm.Uint256Evm.fillingAdd
import com.chipprbots.fukuii.evm.Uint256Evm.min
import com.chipprbots.fukuii.evm.Uint256Evm.mulmod
import com.chipprbots.fukuii.evm.Uint256Evm.sdiv
import com.chipprbots.fukuii.evm.Uint256Evm.sgt
import com.chipprbots.fukuii.evm.Uint256Evm.signExtend
import com.chipprbots.fukuii.evm.Uint256Evm.slt
import com.chipprbots.fukuii.evm.Uint256Evm.smod
import com.chipprbots.fukuii.evm.Uint256Evm.sshift
import com.chipprbots.fukuii.evm.Uint256Evm.toInt
import com.chipprbots.fukuii.evm.Uint256Evm.toSign
import com.chipprbots.fukuii.evm.Uint256Evm.uintOf

object OpCode:

  /** A slice of `bytes` of length `size` starting at `offset`, zero-padded on the right when the source runs out (YP
    * `μ_s`); an out-of-range offset yields all zeroes. Transcribed from the AS-IS `OpCode.sliceBytes`.
    */
  def sliceBytes(bytes: ByteString, offset: UInt256, size: UInt256): ByteString =
    val srcLen = UInt256(bytes.size)
    val start = offset.min(srcLen).toInt
    val end = (offset + size).min(srcLen).toInt
    val slice = bytes.slice(start, end)
    val padLen = size.toInt - slice.length
    if padLen > 0 then slice ++ ByteString(Array.fill[Byte](padLen)(0)) else slice

  private[evm] def pop2(stack: Stack): (UInt256, UInt256, Stack) =
    val (xs, s) = stack.pop(2)
    (xs(0), xs(1), s)

  private[evm] def pop3(stack: Stack): (UInt256, UInt256, UInt256, Stack) =
    val (xs, s) = stack.pop(3)
    (xs(0), xs(1), xs(2), s)

/** Base class for all EVM opcodes — a **behavior-bearing** object carrying its `execute`, stack effect
  * (`delta`/`alpha`) and gas computation (RX-L3-04; besu `AbstractOperation`/`AddOperation`). A flat `enum` cannot
  * carry `execute`, so the idiom is a sealed hierarchy of `case object`s (kept from the AS-IS), with the
  * `ConstGas`/`AddrAccessGas`/`StorageAccessGas` mixins as `trait`s.
  *
  * Retyped to the built machine: gas is `BigInt` (AS-IS `GasAmount`); the fee *values* + warm/cold access cost are read
  * from the injected per-fork [[GasCalculator]] on `state.config.gasCalculator` (AS-IS split of `FeeSchedule` values vs
  * the enum-fork read-path is retired — T3/RX-L3-05/09). **No fork name appears in any `exec` body**; where an opcode
  * needs to know a fork rule it reads an intent-named getter on [[EvmConfig]] (`eip3860Enabled`, `eip6780Enabled`, …),
  * never a fork identity (RX-L3-12).
  *
  * @param code
  *   opcode byte
  * @param delta
  *   stack words popped
  * @param alpha
  *   stack words pushed
  * @param baseGasFn
  *   base (constant-tier) gas cost, read from the per-fork [[GasCalculator]]
  */
abstract class OpCode(val code: Byte, val delta: Int, val alpha: Int, val baseGasFn: GasCalculator => BigInt)
    extends Product
    with Serializable:
  def this(code: Int, pop: Int, push: Int, gasFn: GasCalculator => BigInt) = this(code.toByte, pop, push, gasFn)

  def execute[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    if !availableInContext(state) then state.withError(OpCodeNotAvailableInStaticContext(code))
    else if state.stack.size < delta then state.withError(StackUnderflow)
    else if state.stack.size - delta + alpha > state.stack.maxSize then state.withError(StackOverflow)
    else
      val gas: BigInt = calcGas(state)
      if gas > state.gas then state.copy(gas = BigInt(0)).withError(OutOfGas)
      else exec(state).spendGas(gas)

  protected def calcGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    baseGas(state) + varGas(state)

  protected def baseGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    baseGasFn(state.config.gasCalculator)

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt

  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S]

  protected def availableInContext[W <: WorldState[W, S], S <: AccountStorage[S]]: MessageFrame[W, S] => Boolean =
    _ => true

/** EIP-2929-aware account-access base cost — pre-2929 the opcode's own base tier, post-2929 the cold/warm split. The
  * warm/cold decision lands in the [[GasCalculator]] (T3/RX-L3-09), keyed by whether the address is already in the
  * frame's warm set; no fork lookup gates it.
  */
trait AddrAccessGas:
  self: OpCode =>

  override protected def baseGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val addr = address(state)
    state.config.gasCalculator
      .accountAccessCost(baseGasFn(state.config.gasCalculator), state.accessedAddresses.contains(addr))

  protected def address[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): Address

/** EIP-2929-aware storage-slot-access base cost — pre-2929 the opcode's own base tier, post-2929 the cold-sload /
  * warm-read split (keyed by the frame's warm-storage set).
  */
trait StorageAccessGas:
  self: OpCode =>

  override protected def baseGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (addr, key) = addressAndKey(state)
    state.config.gasCalculator.storageAccessCost(
      baseGasFn(state.config.gasCalculator),
      state.accessedStorageKeys.contains((addr, key))
    )

  protected def addressAndKey[W <: WorldState[W, S], S <: AccountStorage[S]](
      state: MessageFrame[W, S]
  ): (Address, UInt256)

sealed trait ConstGas:
  self: OpCode =>
  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt = 0

case object STOP extends OpCode(0x00, 0, 0, _.G_zero) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.withReturnData(ByteString.empty).halt

sealed abstract class UnaryOp(code: Int, baseGasFn: GasCalculator => BigInt)(val f: UInt256 => UInt256)
    extends OpCode(code, 1, 1, baseGasFn)
    with ConstGas:

  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (a, stack1) = state.stack.pop()
    state.withStack(stack1.push(f(a))).step()

sealed abstract class BinaryOp(code: Int, baseGasFn: GasCalculator => BigInt)(val f: (UInt256, UInt256) => UInt256)
    extends OpCode(code.toByte, 2, 1, baseGasFn):

  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (a, b, stack1) = OpCode.pop2(state.stack)
    state.withStack(stack1.push(f(a, b))).step()

sealed abstract class TernaryOp(code: Int, baseGasFn: GasCalculator => BigInt)(
    val f: (UInt256, UInt256, UInt256) => UInt256
) extends OpCode(code.toByte, 3, 1, baseGasFn):

  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (a, b, c, stack1) = OpCode.pop3(state.stack)
    state.withStack(stack1.push(f(a, b, c))).step()

sealed abstract class ConstOp(code: Int)(
    val f: MessageFrame[? <: WorldState[?, ? <: AccountStorage[?]], ? <: AccountStorage[?]] => UInt256
) extends OpCode(code, 0, 1, _.G_base)
    with ConstGas:

  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.withStack(state.stack.push(f(state))).step()

sealed abstract class ShiftingOp(code: Int, shiftFn: (UInt256, UInt256) => UInt256)
    extends OpCode(code, 2, 1, _.G_verylow)
    with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (shift, value, stack1) = OpCode.pop2(state.stack)
    val result = if shift >= UInt256(256) then UInt256.Zero else shiftFn(value, shift)
    state.withStack(stack1.push(result)).step()

case object ADD extends BinaryOp(0x01, _.G_verylow)(_ + _) with ConstGas

case object MUL extends BinaryOp(0x02, _.G_low)(_ * _) with ConstGas

case object SUB extends BinaryOp(0x03, _.G_verylow)(_ - _) with ConstGas

case object DIV extends BinaryOp(0x04, _.G_low)(_ / _) with ConstGas

case object SDIV extends BinaryOp(0x05, _.G_low)(_.sdiv(_)) with ConstGas

case object MOD extends BinaryOp(0x06, _.G_low)(_.mod(_)) with ConstGas

case object SMOD extends BinaryOp(0x07, _.G_low)(_.smod(_)) with ConstGas

case object ADDMOD extends TernaryOp(0x08, _.G_mid)(_.addmod(_, _)) with ConstGas

case object MULMOD extends TernaryOp(0x09, _.G_mid)(_.mulmod(_, _)) with ConstGas

case object EXP extends BinaryOp(0x0a, _.G_exp)(_.pow(_)):
  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (_, m, _) = OpCode.pop2(state.stack)
    state.config.gasCalculator.G_expbyte * m.byteSize

case object SIGNEXTEND extends BinaryOp(0x0b, _.G_low)((a, b) => a.signExtend(b)) with ConstGas

case object LT extends BinaryOp(0x10, _.G_verylow)((a, b) => uintOf(a < b)) with ConstGas

case object GT extends BinaryOp(0x11, _.G_verylow)((a, b) => uintOf(a > b)) with ConstGas

case object SLT extends BinaryOp(0x12, _.G_verylow)((a, b) => uintOf(a.slt(b))) with ConstGas

case object SGT extends BinaryOp(0x13, _.G_verylow)((a, b) => uintOf(a.sgt(b))) with ConstGas

case object EQ extends BinaryOp(0x14, _.G_verylow)((a, b) => uintOf(a == b)) with ConstGas

case object ISZERO extends UnaryOp(0x15, _.G_verylow)(a => uintOf(a.isZero)) with ConstGas

case object AND extends BinaryOp(0x16, _.G_verylow)(_ & _) with ConstGas

case object OR extends BinaryOp(0x17, _.G_verylow)(_ | _) with ConstGas

case object XOR extends BinaryOp(0x18, _.G_verylow)(_ ^ _) with ConstGas

case object NOT extends UnaryOp(0x19, _.G_verylow)(a => a.unary_~) with ConstGas

case object BYTE extends BinaryOp(0x1a, _.G_verylow)((a, b) => a.byteOf(b)) with ConstGas

// logical shift left
case object SHL extends ShiftingOp(0x1b, (value, shift) => value.shiftLeft(shift.toInt))

// logical shift right
case object SHR extends ShiftingOp(0x1c, (value, shift) => value.shiftRight(shift.toInt))

// arithmetic (sign-extending) shift right
case object SAR extends OpCode(0x1d, 2, 1, _.G_verylow) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (shift, value, stack1) = OpCode.pop2(state.stack)
    val result =
      if shift >= UInt256(256) then if value.toSign >= 0 then UInt256.Zero else UInt256.MaxValue
      else value.sshift(shift)
    state.withStack(stack1.push(result)).step()

/** EIP-7939: Count Leading Zero bits — pushes the count of leading zero bits (0..256); `0` yields 256. */
case object CLZ extends UnaryOp(0x1e, _.G_low)(v => UInt256(256 - v.toBigInt.bitLength)) with ConstGas

case object SHA3 extends OpCode(0x20, 2, 1, _.G_sha3):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (offset, size, stack1) = OpCode.pop2(state.stack)
    val (input, mem1) = state.memory.load(offset, size)
    val ret = UInt256.fromBytes(kec256(input.toArray))
    state.withStack(stack1.push(ret)).withMemory(mem1).step()

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (offset, size, _) = OpCode.pop2(state.stack)
    val memCost = state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), offset.toBigInt, size.toBigInt)
    val shaCost = state.config.gasCalculator.G_sha3word * wordsForBytes(size.toBigInt)
    memCost + shaCost

case object ADDRESS extends ConstOp(0x30)(_.env.ownerAddr.toUInt256)

case object BALANCE extends OpCode(0x31, 1, 1, _.G_balance) with AddrAccessGas with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (accountAddress, stack1) = state.stack.pop()
    val addr = Address(accountAddress)
    val accountBalance = state.world.getBalance(addr)
    state.withStack(stack1.push(accountBalance)).addAccessedAddress(addr).step()

  protected def address[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): Address =
    Address(state.stack.pop()._1)

case object ORIGIN extends ConstOp(0x32)(_.env.originAddr.toUInt256)

case object CALLER extends ConstOp(0x33)(_.env.callerAddr.toUInt256)

case object CALLVALUE extends ConstOp(0x34)(_.env.value)

case object CALLDATALOAD extends OpCode(0x35, 1, 1, _.G_verylow) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (offset, stack1) = state.stack.pop()
    val data = OpCode.sliceBytes(state.inputData, offset, UInt256(32))
    state.withStack(stack1.push(UInt256.fromBytes(data))).step()

case object CALLDATASIZE extends ConstOp(0x36)(s => UInt256(s.inputData.size))

case object CALLDATACOPY extends OpCode(0x37, 3, 0, _.G_verylow):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (memOffset, dataOffset, size, stack1) = OpCode.pop3(state.stack)
    val data = OpCode.sliceBytes(state.inputData, dataOffset, size)
    state.withStack(stack1).withMemory(state.memory.store(memOffset, data)).step()

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (offset, _, size, _) = OpCode.pop3(state.stack)
    val memCost = state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), offset.toBigInt, size.toBigInt)
    memCost + state.config.gasCalculator.G_copy * wordsForBytes(size.toBigInt)

case object CODESIZE extends ConstOp(0x38)(s => UInt256(s.env.program.length))

case object CODECOPY extends OpCode(0x39, 3, 0, _.G_verylow):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (memOffset, codeOffset, size, stack1) = OpCode.pop3(state.stack)
    val bytes = OpCode.sliceBytes(state.program.code, codeOffset, size)
    state.withStack(stack1).withMemory(state.memory.store(memOffset, bytes)).step()

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (offset, _, size, _) = OpCode.pop3(state.stack)
    val memCost = state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), offset.toBigInt, size.toBigInt)
    memCost + state.config.gasCalculator.G_copy * wordsForBytes(size.toBigInt)

case object GASPRICE extends ConstOp(0x3a)(_.env.gasPrice)

case object EXTCODESIZE extends OpCode(0x3b, 1, 1, _.G_extcode) with AddrAccessGas with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (addrUint, stack1) = state.stack.pop()
    val addr = Address(addrUint)
    val codeSize = state.world.getCode(addr).size
    state.withStack(stack1.push(UInt256(codeSize))).addAccessedAddress(addr).step()

  protected def address[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): Address =
    Address(state.stack.pop()._1)

case object EXTCODECOPY extends OpCode(0x3c, 4, 0, _.G_extcode) with AddrAccessGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (xs, stack1) = state.stack.pop(4)
    val addr = Address(xs(0))
    val (memOffset, codeOffset, size) = (xs(1), xs(2), xs(3))
    val codeCopy = OpCode.sliceBytes(state.world.getCode(addr), codeOffset, size)
    state.withStack(stack1).withMemory(state.memory.store(memOffset, codeCopy)).addAccessedAddress(addr).step()

  override protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (xs, _) = state.stack.pop(4)
    val (memOffset, size) = (xs(1), xs(3))
    val memCost = state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), memOffset.toBigInt, size.toBigInt)
    memCost + state.config.gasCalculator.G_copy * wordsForBytes(size.toBigInt)

  protected def address[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): Address =
    Address(state.stack.pop(4)._1(0))

case object RETURNDATASIZE extends ConstOp(0x3d)(s => UInt256(s.returnData.size))

case object RETURNDATACOPY extends OpCode(0x3e, 3, 0, _.G_verylow):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (memOffset, dataOffset, size, stack1) = OpCode.pop3(state.stack)
    if dataOffset.fillingAdd(size) > UInt256(state.returnData.size) then
      state.withStack(stack1).withError(ReturnDataOverflow)
    else
      val data = OpCode.sliceBytes(state.returnData, dataOffset, size)
      state.withStack(stack1).withMemory(state.memory.store(memOffset, data)).step()

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (offset, _, size, _) = OpCode.pop3(state.stack)
    val memCost = state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), offset.toBigInt, size.toBigInt)
    memCost + state.config.gasCalculator.G_copy * wordsForBytes(size.toBigInt)

case object EXTCODEHASH extends OpCode(0x3f, 1, 1, _.G_balance) with AddrAccessGas with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (accountAddress, stack1) = state.stack.pop()
    val addr = Address(accountAddress)
    // EIP-1052: 0 for a non-existent-or-empty (EIP-161) account; else the keccak of its code.
    val accountExists = !state.world.isAccountDead(addr)
    val codeHash =
      if accountExists then
        val code = state.world.getCode(addr)
        if code.isEmpty then UInt256.fromBytes(Account.EmptyCodeHash.bytes) else UInt256.fromBytes(kec256(code))
      else UInt256.Zero
    state.withStack(stack1.push(codeHash)).addAccessedAddress(addr).step()

  protected def address[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): Address =
    Address(state.stack.pop()._1)

case object BLOCKHASH extends OpCode(0x40, 1, 1, _.G_blockhash) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (blockNumber, stack1) = state.stack.pop()
    val current = state.env.blockHeader.number
    val outOfLimits = current - blockNumber.toBigInt > 256 || blockNumber.toBigInt >= current
    val hash = if outOfLimits then UInt256.Zero else state.world.getBlockHash(blockNumber).getOrElse(UInt256.Zero)
    state.withStack(stack1.push(hash)).step()

case object COINBASE extends ConstOp(0x41)(_.env.blockHeader.beneficiary.toUInt256)

case object TIMESTAMP extends ConstOp(0x42)(s => UInt256(s.env.blockHeader.unixTimestamp))

case object NUMBER extends ConstOp(0x43)(s => UInt256(s.env.blockHeader.number))

case object DIFFICULTY
    extends ConstOp(0x44)(s =>
      // EIP-4399: post-Merge, 0x44 returns prevRandao (threaded on the env, geth `Context.Random`); pre-Merge the
      // header difficulty.
      s.env.prevRandao.getOrElse(UInt256(s.env.blockHeader.difficulty))
    )

case object GASLIMIT extends ConstOp(0x45)(s => UInt256(s.env.blockHeader.gasLimit))

case object CHAINID extends ConstOp(0x46)(s => UInt256(s.env.chainId.toBigInt))

case object SELFBALANCE extends OpCode(0x47, 0, 1, _.G_low) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.withStack(state.stack.push(state.ownBalance)).step()

/** EIP-3198: BASEFEE — pushes the block's base fee; 0 when the header carries none (pre-London/pre-Olympia). */
case object BASEFEE extends OpCode(0x48, 0, 1, _.G_base) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val baseFee = state.env.blockHeader.baseFeePerGas.getOrElse(BigInt(0))
    state.withStack(state.stack.push(UInt256(baseFee))).step()

/** EIP-4844: BLOBHASH — the versioned hash at `index` from the tx's blob hashes, 0 if out of bounds. **ETH-only.** */
case object BLOBHASH extends OpCode(0x49, 1, 1, _.G_verylow) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (index, stack1) = state.stack.pop()
    val hashes = state.env.blobVersionedHashes
    val result =
      if index.toBigInt >= 0 && index.toBigInt < hashes.size then UInt256.fromBytes(hashes(index.toInt))
      else UInt256.Zero
    state.withStack(stack1.push(result)).step()

/** EIP-7516: BLOBBASEFEE — the block's precomputed blob base fee (threaded on the env, geth `Context.BlobBaseFee`).
  * **ETH-only.**
  */
case object BLOBBASEFEE extends OpCode(0x4a, 0, 1, _.G_base) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.withStack(state.stack.push(state.env.blobBaseFee)).step()

case object POP extends OpCode(0x50, 1, 0, _.G_base) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.withStack(state.stack.pop()._2).step()

case object MLOAD extends OpCode(0x51, 1, 1, _.G_verylow):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (offset, stack1) = state.stack.pop()
    val (word, mem1) = state.memory.load(offset)
    state.withStack(stack1.push(word)).withMemory(mem1).step()

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (offset, _) = state.stack.pop()
    state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), offset.toBigInt, BigInt(UInt256.Size))

case object MSTORE extends OpCode(0x52, 2, 0, _.G_verylow):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (offset, value, stack1) = OpCode.pop2(state.stack)
    state.withStack(stack1).withMemory(state.memory.store(offset, value)).step()

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (offset, _) = state.stack.pop()
    state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), offset.toBigInt, BigInt(UInt256.Size))

case object MSTORE8 extends OpCode(0x53, 2, 0, _.G_verylow):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (offset, value, stack1) = OpCode.pop2(state.stack)
    val valueToByte = value.mod(UInt256(256)).toBigInt.toByte
    state.withStack(stack1).withMemory(state.memory.store(offset, valueToByte)).step()

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (offset, _) = state.stack.pop()
    state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), offset.toBigInt, BigInt(1))

case object SLOAD extends OpCode(0x54, 1, 1, _.G_sload) with StorageAccessGas with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (offset, stack1) = state.stack.pop()
    val value = state.storage.load(offset)
    state.withStack(stack1.push(UInt256(value))).addAccessedStorageKey(state.ownAddress, offset).step()

  protected def addressAndKey[W <: WorldState[W, S], S <: AccountStorage[S]](
      state: MessageFrame[W, S]
  ): (Address, UInt256) =
    (state.ownAddress, state.stack.pop()._1)

case object SSTORE extends OpCode(0x55, 2, 0, _.G_zero):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val gc = state.config.gasCalculator
    val eip2200Enabled = state.config.eip2200Enabled
    val eip1283Enabled = state.config.eip1283Enabled

    val (offset, newValue, stack1) = OpCode.pop2(state.stack)
    val currentValue = state.storage.load(offset)

    val refund: BigInt = if eip2200Enabled || eip1283Enabled then
      val originalValue = state.originalWorld.getStorage(state.ownAddress).load(offset)
      if currentValue != newValue.toBigInt then
        if originalValue == currentValue then // fresh slot
          if originalValue != 0 && newValue.isZero then gc.R_sclear else 0
        else // dirty slot
          val clear =
            if originalValue != 0 then
              if currentValue == 0 then -gc.R_sclear
              else if newValue.isZero then gc.R_sclear
              else BigInt(0)
            else BigInt(0)
          val reset =
            if originalValue == newValue.toBigInt then
              if UInt256(originalValue).isZero then gc.G_sset - gc.G_sload else gc.G_sreset - gc.G_sload
            else BigInt(0)
          clear + reset
      else BigInt(0)
    else if newValue.isZero && !UInt256(currentValue).isZero then gc.R_sclear
    else 0
    val updatedStorage = state.storage.store(offset, newValue.toBigInt)
    state
      .addAccessedStorageKey(state.ownAddress, offset)
      .withStack(stack1)
      .withStorage(updatedStorage)
      .refundGas(refund)
      .step()

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val gc = state.config.gasCalculator
    val (offset, newValue, _) = OpCode.pop2(state.stack)
    val currentValue = state.storage.load(offset)

    val eip2200Enabled = state.config.eip2200Enabled
    val eip1283Enabled = state.config.eip1283Enabled

    val originalCharge: BigInt =
      if eip2200Enabled && state.gas <= gc.G_callstipend then gc.G_callstipend + 1 // Out of gas error
      else if eip2200Enabled || eip1283Enabled then
        if currentValue == newValue.toBigInt then gc.G_sload
        else
          val originalValue = state.originalWorld.getStorage(state.ownAddress).load(offset)
          if originalValue == currentValue then // fresh slot
            if originalValue == 0 then gc.G_sset else gc.G_sreset
          else gc.G_sload // dirty slot
      else if UInt256(currentValue).isZero && !newValue.isZero then gc.G_sset
      else gc.G_sreset

    // EIP-2929: SSTORE additionally charges COLD_SLOAD_COST when the slot is cold (warm surcharge is 0 here, unlike
    // the generic storage-access split).
    val coldCharge: BigInt =
      if state.config.eip2929Enabled && !state.accessedStorageKeys.contains((state.ownAddress, offset)) then
        gc.G_cold_sload
      else 0

    originalCharge + coldCharge

  override protected def availableInContext[W <: WorldState[W, S], S <: AccountStorage[S]]
      : MessageFrame[W, S] => Boolean = !_.staticCtx

case object JUMP extends OpCode(0x56, 1, 0, _.G_mid) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (pos, stack1) = state.stack.pop()
    val dest = pos.toInt
    if pos == UInt256(dest) && state.program.validJumpDestinations.contains(dest) then
      state.withStack(stack1).goto(dest)
    else state.withError(InvalidJump(pos))

case object JUMPI extends OpCode(0x57, 2, 0, _.G_high) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (pos, cond, stack1) = OpCode.pop2(state.stack)
    val dest = pos.toInt
    if cond.isZero then state.withStack(stack1).step()
    else if pos == UInt256(dest) && state.program.validJumpDestinations.contains(dest) then
      state.withStack(stack1).goto(dest)
    else state.withError(InvalidJump(pos))

case object PC extends ConstOp(0x58)(s => UInt256(s.pc))

case object MSIZE extends ConstOp(0x59)(s => UInt256(BigInt(UInt256.Size) * wordsForBytes(BigInt(s.memory.size))))

case object GAS extends ConstOp(0x5a)(state => UInt256(state.gas - state.config.gasCalculator.G_base))

case object JUMPDEST extends OpCode(0x5b, 0, 0, _.G_jumpdest) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.step()

/** EIP-1153: TLOAD — transient-storage load. */
case object TLOAD extends OpCode(0x5c, 1, 1, _.G_warm_storage_read) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (offset, stack1) = state.stack.pop()
    val value = state.transientStorage.getOrElse((state.ownAddress, offset), BigInt(0))
    state.withStack(stack1.push(UInt256(value))).step()

/** EIP-1153: TSTORE — transient-storage store; unavailable in static context. */
case object TSTORE extends OpCode(0x5d, 2, 0, _.G_warm_storage_read) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (offset, value, stack1) = OpCode.pop2(state.stack)
    val updated = state.transientStorage.updated((state.ownAddress, offset), value.toBigInt)
    state.copy(transientStorage = updated).withStack(stack1).step()

  override protected def availableInContext[W <: WorldState[W, S], S <: AccountStorage[S]]
      : MessageFrame[W, S] => Boolean = !_.staticCtx

/** EIP-5656: MCOPY — memory-to-memory copy with overlap handling. */
case object MCOPY extends OpCode(0x5e, 3, 0, _.G_verylow):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (dst, src, size, stack1) = OpCode.pop3(state.stack)
    if size.isZero then state.withStack(stack1).step()
    else
      val (data, mem1) = state.memory.load(src, size)
      state.withStack(stack1).withMemory(mem1.store(dst, data)).step()

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (dst, src, size, _) = OpCode.pop3(state.stack)
    if size.isZero then 0
    else
      val copyCost = state.config.gasCalculator.G_copy * wordsForBytes(size.toBigInt)
      val srcEnd = src + size
      val dstEnd = dst + size
      val maxEnd = if srcEnd > dstEnd then srcEnd else dstEnd
      val memCost = state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), BigInt(0), maxEnd.toBigInt)
      copyCost + memCost

case object PUSH0 extends OpCode(0x5f, 0, 1, _.G_base) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.withStack(state.stack.push(UInt256.Zero)).step()

sealed abstract class PushOp(code: Int) extends OpCode(code, 0, 1, _.G_verylow) with ConstGas:
  val i: Int = code - 0x60

  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val n = i + 1
    val bytes = state.program.getBytes(state.pc + 1, n)
    state.withStack(state.stack.push(UInt256.fromBytes(bytes))).step(n + 1)

case object PUSH1 extends PushOp(0x60)
case object PUSH2 extends PushOp(0x61)
case object PUSH3 extends PushOp(0x62)
case object PUSH4 extends PushOp(0x63)
case object PUSH5 extends PushOp(0x64)
case object PUSH6 extends PushOp(0x65)
case object PUSH7 extends PushOp(0x66)
case object PUSH8 extends PushOp(0x67)
case object PUSH9 extends PushOp(0x68)
case object PUSH10 extends PushOp(0x69)
case object PUSH11 extends PushOp(0x6a)
case object PUSH12 extends PushOp(0x6b)
case object PUSH13 extends PushOp(0x6c)
case object PUSH14 extends PushOp(0x6d)
case object PUSH15 extends PushOp(0x6e)
case object PUSH16 extends PushOp(0x6f)
case object PUSH17 extends PushOp(0x70)
case object PUSH18 extends PushOp(0x71)
case object PUSH19 extends PushOp(0x72)
case object PUSH20 extends PushOp(0x73)
case object PUSH21 extends PushOp(0x74)
case object PUSH22 extends PushOp(0x75)
case object PUSH23 extends PushOp(0x76)
case object PUSH24 extends PushOp(0x77)
case object PUSH25 extends PushOp(0x78)
case object PUSH26 extends PushOp(0x79)
case object PUSH27 extends PushOp(0x7a)
case object PUSH28 extends PushOp(0x7b)
case object PUSH29 extends PushOp(0x7c)
case object PUSH30 extends PushOp(0x7d)
case object PUSH31 extends PushOp(0x7e)
case object PUSH32 extends PushOp(0x7f)

sealed abstract class DupOp private (code: Int, val i: Int)
    extends OpCode(code, i + 1, i + 2, _.G_verylow)
    with ConstGas:
  def this(code: Int) = this(code, code - 0x80)

  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.withStack(state.stack.dup(i)).step()

case object DUP1 extends DupOp(0x80)
case object DUP2 extends DupOp(0x81)
case object DUP3 extends DupOp(0x82)
case object DUP4 extends DupOp(0x83)
case object DUP5 extends DupOp(0x84)
case object DUP6 extends DupOp(0x85)
case object DUP7 extends DupOp(0x86)
case object DUP8 extends DupOp(0x87)
case object DUP9 extends DupOp(0x88)
case object DUP10 extends DupOp(0x89)
case object DUP11 extends DupOp(0x8a)
case object DUP12 extends DupOp(0x8b)
case object DUP13 extends DupOp(0x8c)
case object DUP14 extends DupOp(0x8d)
case object DUP15 extends DupOp(0x8e)
case object DUP16 extends DupOp(0x8f)

sealed abstract class SwapOp(code: Int, val i: Int) extends OpCode(code, i + 2, i + 2, _.G_verylow) with ConstGas:
  def this(code: Int) = this(code, code - 0x90)

  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.withStack(state.stack.swap(i + 1)).step()

case object SWAP1 extends SwapOp(0x90)
case object SWAP2 extends SwapOp(0x91)
case object SWAP3 extends SwapOp(0x92)
case object SWAP4 extends SwapOp(0x93)
case object SWAP5 extends SwapOp(0x94)
case object SWAP6 extends SwapOp(0x95)
case object SWAP7 extends SwapOp(0x96)
case object SWAP8 extends SwapOp(0x97)
case object SWAP9 extends SwapOp(0x98)
case object SWAP10 extends SwapOp(0x99)
case object SWAP11 extends SwapOp(0x9a)
case object SWAP12 extends SwapOp(0x9b)
case object SWAP13 extends SwapOp(0x9c)
case object SWAP14 extends SwapOp(0x9d)
case object SWAP15 extends SwapOp(0x9e)
case object SWAP16 extends SwapOp(0x9f)

sealed abstract class LogOp(code: Int, val i: Int) extends OpCode(code, i + 2, 0, _.G_log):
  def this(code: Int) = this(code, code - 0xa0)

  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (items, stack1) = state.stack.pop(delta)
    val offset = items(0)
    val size = items(1)
    val topics = items.drop(2)
    val (data, memory) = state.memory.load(offset, size)
    val logEntry = Log(state.env.ownerAddr, topics.map(w => com.chipprbots.fukuii.bytes.Hash(w.bytes)).toList, data)
    state.withStack(stack1).withMemory(memory).withLog(logEntry).step()

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (items, _) = state.stack.pop(delta)
    val offset = items(0)
    val size = items(1)
    val memCost = state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), offset.toBigInt, size.toBigInt)
    val logCost = state.config.gasCalculator.G_logdata * size.toBigInt + i * state.config.gasCalculator.G_logtopic
    memCost + logCost

  override protected def availableInContext[W <: WorldState[W, S], S <: AccountStorage[S]]
      : MessageFrame[W, S] => Boolean = !_.staticCtx

case object LOG0 extends LogOp(0xa0)
case object LOG1 extends LogOp(0xa1)
case object LOG2 extends LogOp(0xa2)
case object LOG3 extends LogOp(0xa3)
case object LOG4 extends LogOp(0xa4)

abstract class CreateOp(opcode: Int, delta: Int) extends OpCode(opcode, delta, 1, _.G_create):
  // Precompute the gas cost once and hand it to exec via state.opcodeGasCost (avoids the duplicate calc CreateOp.exec
  // would otherwise do — AS-IS EC-243).
  override def execute[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    if !availableInContext(state) then state.withError(OpCodeNotAvailableInStaticContext(code))
    else if state.stack.size < delta then state.withError(StackUnderflow)
    else if state.stack.size - delta + alpha > state.stack.maxSize then state.withError(StackOverflow)
    else
      val gas: BigInt = calcGas(state)
      if gas > state.gas then state.copy(gas = BigInt(0)).withError(OutOfGas)
      else exec(state.copy(opcodeGasCost = gas)).spendGas(gas)

  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (endowment, inOffset, inSize, stack1) = OpCode.pop3(state.stack)

    // EIP-3860: initcode size limit.
    if state.config.eip3860Enabled && state.config.maxInitCodeSize.exists(max => inSize.toBigInt > max) then
      state.withStack(stack1.push(UInt256.Zero)).withError(InitCodeSizeLimit).step()
    else
      val availableGas: BigInt = state.gas - state.opcodeGasCost
      val startGas: BigInt = state.config.gasCalculator.gasCap(availableGas)
      val (initCode, memory1) = state.memory.load(inOffset, inSize)
      val world1 = state.world.increaseNonce(state.ownAddress)

      val context: CallContext[W, S] = CallContext(
        callerAddr = state.env.ownerAddr,
        originAddr = state.env.originAddr,
        recipientAddr = None,
        gasPrice = state.env.gasPrice,
        startGas = startGas,
        inputData = initCode,
        value = endowment,
        endowment = endowment,
        doTransfer = true,
        blockHeader = state.env.blockHeader,
        callDepth = state.env.callDepth + 1,
        world = world1,
        initialAddressesToDelete = state.addressesToDelete,
        evmConfig = state.config,
        chainId = state.env.chainId,
        originalWorld = state.originalWorld,
        warmAddresses = state.accessedAddresses,
        warmStorage = state.accessedStorageKeys,
        createdAddresses = state.createdAddresses,
        transientStorage = state.transientStorage,
        precompileRelocations = state.env.precompileRelocations,
        blobVersionedHashes = state.env.blobVersionedHashes,
        blobBaseFee = state.env.blobBaseFee,
        prevRandao = state.env.prevRandao,
        traceTransfers = state.env.traceTransfers
      )

      val ((result, newAddress), stack2) = this match
        case CREATE => (state.vm.create(context), stack1)
        case _ =>
          val (salt, s2) = stack1.pop()
          (state.vm.create(context, Some(salt)), s2)

      result.error match
        case Some(error) =>
          val world2 = if error == InvalidCall then state.world else world1
          val returnData = if error == RevertOccurs then result.returnData else ByteString.empty
          state
            .spendGas(startGas - result.gasRemaining)
            .withWorld(world2)
            .withStack(stack2.push(UInt256.Zero))
            .withReturnData(returnData)
            .addAccessedAddresses(if error == InvalidCall then Set.empty else Set(newAddress))
            .step()

        case None =>
          val internalTx = InternalTransaction(
            CREATE.code,
            context.callerAddr,
            None,
            context.startGas,
            context.inputData,
            Wei(context.endowment)
          )
          state
            .spendGas(startGas - result.gasRemaining)
            .withWorld(result.world)
            .refundGas(result.gasRefund)
            .withStack(stack2.push(newAddress.toUInt256))
            .withAddressesToDelete(result.addressesToDelete)
            .withLogs(result.logs)
            .withMemory(memory1)
            .withInternalTxs(internalTx +: result.internalTxs)
            .withReturnData(ByteString.empty)
            .addAccessedStorageKeys(result.accessedStorageKeys)
            .addAccessedAddresses(result.accessedAddresses + newAddress)
            // EIP-6780: a successful create commits its whole subtree's created addresses to the parent frame (the
            // reverted case is the `Some(error)` arm above, which drops them — mirrors geth journal-revert / besu UndoSet).
            .addCreatedAddresses(result.createdAddresses)
            .copy(transientStorage = result.transientStorage)
            .step()

  override protected def availableInContext[W <: WorldState[W, S], S <: AccountStorage[S]]
      : MessageFrame[W, S] => Boolean = !_.staticCtx

case object CREATE extends CreateOp(0xf0, 3):
  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (_, inOffset, inSize, _) = OpCode.pop3(state.stack)
    val memCost = state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), inOffset.toBigInt, inSize.toBigInt)
    val initCodeGasCost =
      if state.config.eip3860Enabled then state.config.gasCalculator.G_initcode_word * wordsForBytes(inSize.toBigInt)
      else BigInt(0)
    memCost + initCodeGasCost

case object CREATE2 extends CreateOp(0xf5, 4):
  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (_, inOffset, inSize, _) = OpCode.pop3(state.stack)
    val memCost = state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), inOffset.toBigInt, inSize.toBigInt)
    val hashCost = state.config.gasCalculator.G_sha3word * wordsForBytes(inSize.toBigInt)
    val initCodeGasCost =
      if state.config.eip3860Enabled then state.config.gasCalculator.G_initcode_word * wordsForBytes(inSize.toBigInt)
      else BigInt(0)
    memCost + hashCost + initCodeGasCost

abstract class CallOp(opcode: Int, delta: Int, alpha: Int) extends OpCode(opcode, delta, alpha, _.G_zero):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (params, stack1) = getParams(state)
    val to = params(1)
    val callValue = params(2)
    val inOffset = params(3)
    val inSize = params(4)
    val outOffset = params(5)
    val outSize = params(6)

    val toAddr = Address(to)
    val (inputData, mem1) = state.memory.load(inOffset, inSize)
    val (owner, caller, value, endowment, doTransfer, static) = this match
      case CALL       => (toAddr, state.ownAddress, callValue, callValue, true, state.staticCtx)
      case STATICCALL => (toAddr, state.ownAddress, UInt256.Zero, UInt256.Zero, true, true)
      case CALLCODE   => (state.ownAddress, state.ownAddress, callValue, callValue, false, state.staticCtx)
      case _          => (state.ownAddress, state.env.callerAddr, callValue, UInt256.Zero, false, state.staticCtx)
    val startGas: BigInt = calcStartGas(state, params, endowment)

    // EIP-7702: warm the delegation target of the callee, if any.
    val stateWithDelegationWarming =
      Eip7702.parseDelegation(state.world.getCode(toAddr)) match
        case Some(target) => state.addAccessedAddress(target)
        case None         => state

    val context: CallContext[W, S] = CallContext(
      callerAddr = caller,
      originAddr = state.env.originAddr,
      recipientAddr = Some(toAddr),
      gasPrice = state.env.gasPrice,
      startGas = startGas,
      inputData = inputData,
      value = value,
      endowment = endowment,
      doTransfer = doTransfer,
      blockHeader = state.env.blockHeader,
      callDepth = state.env.callDepth + 1,
      world = state.world,
      initialAddressesToDelete = state.addressesToDelete,
      evmConfig = state.config,
      chainId = state.env.chainId,
      staticCtx = static,
      originalWorld = state.originalWorld,
      warmAddresses = stateWithDelegationWarming.accessedAddresses,
      warmStorage = state.accessedStorageKeys,
      createdAddresses = state.createdAddresses,
      transientStorage = state.transientStorage,
      precompileRelocations = state.env.precompileRelocations,
      blobVersionedHashes = state.env.blobVersionedHashes,
      blobBaseFee = state.env.blobBaseFee,
      prevRandao = state.env.prevRandao,
      traceTransfers = state.env.traceTransfers
    )

    val result = state.vm.call(context, owner)

    lazy val sizeCap = outSize.min(UInt256(result.returnData.size)).toInt
    lazy val output = result.returnData.take(sizeCap)
    lazy val mem2 = mem1.store(outOffset, output).expand(outOffset, outSize)

    result.error match
      case Some(error) =>
        val world1 = state.world.keepPrecompileTouched(result.world)
        val gasAdjustment: BigInt =
          if error == InvalidCall then -startGas
          else if error == RevertOccurs then -result.gasRemaining
          else BigInt(0)
        val memoryAdjustment = if error == RevertOccurs then mem2 else mem1.expand(outOffset, outSize)
        state
          .withStack(stack1.push(UInt256.Zero))
          .withMemory(memoryAdjustment)
          .withWorld(world1)
          .spendGas(gasAdjustment)
          .withReturnData(result.returnData)
          .addAccessedAddress(toAddr)
          .step()

      case None =>
        val internalTx = internalTransaction(state.env, to, startGas, inputData, endowment)
        state
          .spendGas(-result.gasRemaining)
          .refundGas(result.gasRefund)
          .withStack(stack1.push(UInt256.One))
          .withMemory(mem2)
          .withWorld(result.world)
          .withAddressesToDelete(result.addressesToDelete)
          .withInternalTxs(internalTx +: result.internalTxs)
          .withLogs(result.logs)
          .withReturnData(result.returnData)
          .addAccessedStorageKeys(result.accessedStorageKeys)
          .addAccessedAddresses(result.accessedAddresses + toAddr)
          // EIP-6780: propagate any contracts created inside the sub-call up to the parent (tx-global created set); the
          // error arm above intentionally drops them.
          .addCreatedAddresses(result.createdAddresses)
          .copy(transientStorage = result.transientStorage)
          .step()

  protected def internalTransaction(
      env: ExecutionEnv,
      callee: UInt256,
      startGas: BigInt,
      inputData: ByteString,
      endowment: UInt256
  ): InternalTransaction =
    val from = env.ownerAddr
    val to = if this == CALL then Address(callee) else env.ownerAddr
    InternalTransaction(code, from, Some(to), startGas, inputData, Wei(endowment))

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (params, _) = getParams(state)
    val gas = params(0)
    val to = params(1)
    val callValue = params(2)
    val inOffset = params(3)
    val inSize = params(4)
    val outOffset = params(5)
    val outSize = params(6)
    val endowment = if this == DELEGATECALL || this == STATICCALL then UInt256.Zero else callValue

    val memCost = calcMemCost(state, inOffset, inSize, outOffset, outSize)

    // EIP-7702: charge for resolving a delegation target — cold if not yet accessed, else warm.
    val delegationCost: BigInt =
      Eip7702.parseDelegation(state.world.getCode(Address(to))) match
        case Some(target) if !state.accessedAddresses.contains(target) =>
          state.config.gasCalculator.G_cold_account_access
        case Some(_) => state.config.gasCalculator.G_warm_storage_read
        case None    => BigInt(0)

    val gExtra: BigInt = gasExtra(state, endowment, Address(to))
    val gCap: BigInt = gasCap(state, gas, gExtra + memCost + delegationCost)
    memCost + gCap + gExtra + delegationCost

  protected def calcMemCost[W <: WorldState[W, S], S <: AccountStorage[S]](
      state: MessageFrame[W, S],
      inOffset: UInt256,
      inSize: UInt256,
      outOffset: UInt256,
      outSize: UInt256
  ): BigInt =
    val gc = state.config.gasCalculator
    val memCostIn = gc.calcMemCost(BigInt(state.memory.size), inOffset.toBigInt, inSize.toBigInt)
    val memCostOut = gc.calcMemCost(BigInt(state.memory.size), outOffset.toBigInt, outSize.toBigInt)
    memCostIn.max(memCostOut)

  protected def getParams[W <: WorldState[W, S], S <: AccountStorage[S]](
      state: MessageFrame[W, S]
  ): (Seq[UInt256], Stack) =
    val (gasTo, stack1) = state.stack.pop(2)
    val (value, stack2) = if this == DELEGATECALL || this == STATICCALL then (state.env.value, stack1) else stack1.pop()
    val (rest, stack3) = stack2.pop(4)
    (Seq(gasTo(0), gasTo(1), value, rest(0), rest(1), rest(2), rest(3)), stack3)

  protected def calcStartGas[W <: WorldState[W, S], S <: AccountStorage[S]](
      state: MessageFrame[W, S],
      params: Seq[UInt256],
      endowment: UInt256
  ): BigInt =
    val gas = params(0)
    val to = params(1)
    val inOffset = params(3)
    val inSize = params(4)
    val outOffset = params(5)
    val outSize = params(6)
    val memCost = calcMemCost(state, inOffset, inSize, outOffset, outSize)
    val gExtra = gasExtra(state, endowment, Address(to))
    val gCap = gasCap(state, gas, gExtra + memCost)
    if endowment.isZero then gCap else gCap + state.config.gasCalculator.G_callstipend

  private def gasCap[W <: WorldState[W, S], S <: AccountStorage[S]](
      state: MessageFrame[W, S],
      g: UInt256,
      consumedGas: BigInt
  ): BigInt =
    if state.config.gasCalculator.subGasCapDivisor.isDefined && state.gas >= consumedGas then
      g.toBigInt.min(state.config.gasCalculator.gasCap(state.gas - consumedGas))
    else g.toBigInt

  private def gasExtra[W <: WorldState[W, S], S <: AccountStorage[S]](
      state: MessageFrame[W, S],
      endowment: UInt256,
      to: Address
  ): BigInt =
    val isValueTransfer = endowment > UInt256.Zero

    def postEip161CostCondition: Boolean = state.world.isAccountDead(to) && this == CALL && isValueTransfer
    def preEip161CostCondition: Boolean = !state.world.accountExists(to) && this == CALL

    val gc = state.config.gasCalculator
    val c_new: BigInt =
      if state.config.noEmptyAccounts && postEip161CostCondition ||
        !state.config.noEmptyAccounts && preEip161CostCondition
      then gc.G_newaccount
      else 0
    val c_xfer: BigInt = if endowment.isZero then 0 else gc.G_callvalue
    val callCost: BigInt = gc.accountAccessCost(gc.G_call, state.accessedAddresses.contains(to))
    callCost + c_xfer + c_new

case object CALL extends CallOp(0xf1, 7, 1):
  override protected def availableInContext[W <: WorldState[W, S], S <: AccountStorage[S]]
      : MessageFrame[W, S] => Boolean = state =>
    !state.staticCtx || {
      val (xs, _) = state.stack.pop(3)
      xs(2).isZero
    }
case object STATICCALL extends CallOp(0xfa, 6, 1)
case object CALLCODE extends CallOp(0xf2, 7, 1)
case object DELEGATECALL extends CallOp(0xf4, 6, 1)

case object RETURN extends OpCode(0xf3, 2, 0, _.G_zero):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (offset, size, stack1) = OpCode.pop2(state.stack)
    val (ret, mem1) = state.memory.load(offset, size)
    state.withStack(stack1).withReturnData(ret).withMemory(mem1).halt

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (offset, size, _) = OpCode.pop2(state.stack)
    state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), offset.toBigInt, size.toBigInt)

case object REVERT extends OpCode(0xfd, 2, 0, _.G_zero):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (offset, len, stack1) = OpCode.pop2(state.stack)
    val (ret, mem1) = state.memory.load(offset, len)
    state.withStack(stack1).withMemory(mem1).revert(ret)

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val (offset, len, _) = OpCode.pop2(state.stack)
    state.config.gasCalculator.calcMemCost(BigInt(state.memory.size), offset.toBigInt, len.toBigInt)

case object INVALID extends OpCode(0xfe, 0, 0, _.G_zero) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.withError(InvalidOpCode(code))

/** SELFDESTRUCT (0xff). EIP-3529 already removed the refund; EIP-6780 (semantic-gated by [[EvmConfig]] intent, not a
  * new opcode) restricts deletion to same-transaction-created contracts.
  */
case object SELFDESTRUCT extends OpCode(0xff, 1, 0, _.G_selfdestruct):
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    val (refund, stack1) = state.stack.pop()
    val refundAddr: Address = Address(refund)
    val gasRefund: BigInt =
      if state.addressesToDelete.contains(state.ownAddress) then 0 else state.config.gasCalculator.R_selfdestruct

    // EIP-6780: post-Olympia SELFDESTRUCT only destroys contracts created in the same transaction; a pre-existing
    // contract only has its balance transferred. Membership in the per-tx created set is the oracle — go-ethereum
    // `StateDB.IsNewContract` (core/vm/instructions.go:943, the journaled `newContract` flag) and besu
    // `frame.wasCreatedInTransaction` (SelfDestructOperation.java:125, the UndoSet `creates`). It must NOT be
    // approximated by `originalWorld.accountExists`: `originalWorld` is `initialiseAccount`'d on the CREATE path
    // (EvmInterpreter.create), so that check is true inside a constructor frame and would wrongly spare a contract
    // that SELFDESTRUCTs in its own constructor.
    val createdInThisTx = state.createdAddresses.contains(state.ownAddress)
    val shouldDelete = !state.config.eip6780Enabled || createdInThisTx

    val world =
      if state.ownAddress == refundAddr then
        if shouldDelete then state.world.removeAllEther(state.ownAddress)
        else state.world.touchAccounts(state.ownAddress)
      else state.world.transfer(state.ownAddress, refundAddr, state.ownBalance)

    val state1 = state
      .withWorld(world)
      .refundGas(gasRefund)
      .addAccessedAddress(refundAddr)
      .withStack(stack1)
      .withReturnData(ByteString.empty)

    if shouldDelete then state1.withAddressToDelete(state.ownAddress).halt
    else state1.halt

  protected def varGas[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): BigInt =
    val gc = state.config.gasCalculator
    val isValueTransfer = state.ownBalance > UInt256.Zero
    val refundAddress = Address(state.stack.pop()._1)

    def postEip161CostCondition: Boolean =
      state.config.chargeSelfDestructForNewAccount && isValueTransfer && state.world.isAccountDead(refundAddress)
    def preEip161CostCondition: Boolean =
      state.config.chargeSelfDestructForNewAccount && !state.world.accountExists(refundAddress)

    val baseCharge: BigInt =
      if state.config.noEmptyAccounts && postEip161CostCondition ||
        !state.config.noEmptyAccounts && preEip161CostCondition
      then gc.G_newaccount
      else 0

    // SELFDESTRUCT charges COLD_ACCOUNT_ACCESS when the recipient is cold, but never a warm surcharge.
    val addressAccessCharge: BigInt =
      if state.config.eip2929Enabled && !state.accessedAddresses.contains(refundAddress) then gc.G_cold_account_access
      else 0
    baseCharge + addressAccessCharge

  override protected def availableInContext[W <: WorldState[W, S], S <: AccountStorage[S]]
      : MessageFrame[W, S] => Boolean = !_.staticCtx

/** The dense-table sentinel — one instance per slot carrying its byte, so an undefined-opcode dispatch fails loud with
  * the correct byte (`InvalidOpCode(byte)`), consuming all gas (besu `InvalidOperation`). The defined `INVALID` (0xfe)
  * opcode has identical behavior; the sentinel fills every *other* undefined slot. Lives here (not `OpCodes.scala`)
  * because it extends the sealed [[ConstGas]].
  */
final case class InvalidOp(byte: Byte) extends OpCode(byte, 0, 0, _.G_zero) with ConstGas:
  protected def exec[W <: WorldState[W, S], S <: AccountStorage[S]](state: MessageFrame[W, S]): MessageFrame[W, S] =
    state.withError(InvalidOpCode(byte))
