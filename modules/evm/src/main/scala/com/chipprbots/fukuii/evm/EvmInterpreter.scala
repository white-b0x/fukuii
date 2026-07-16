package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.BlockHeader

/** The concrete EVM execution engine — the network-neutral machine that fills the [[VM]] seam P2 declared abstract.
  *
  * Three responsibilities:
  *
  *   - **[[exec]] — the `@tailrec` opcode-dispatch loop.** Fetch the byte, resolve it through the fork-resolved dense
  *     [[EvmConfig.byteToOpCode]] table (branch-free O(1), the undefined-slot [[InvalidOp]] sentinel loud-fails on its
  *     own execute), run it, fire the one-slot [[tracer]], step until a halt flag is set. Correctness-neutral loop
  *     mechanics.
  *   - **[[call]] (Θ) / [[create]] (Λ) — the byte-consensus re-entry.** Value transfer, EIP-150 all-but-one-64th gas
  *     forwarding (metered opcode-side via [[GasCalculator.gasCap]]), the depth-1024 guard, static write-protection
  *     (opcode-side), EIP-7702 delegated-code resolution, and — for create — CREATE/CREATE2 address derivation
  *     (delegated to the [[WorldState]] helpers), EIP-3860/684/7610/3541 gates and the code-deposit accounting.
  *   - **The immutable [[ProgramState]] loop (`copy`-per-step).** Whether to keep this or move to a mutable frame is a
  *     benchmark-gated OPEN for **P7** (L3 plan §6) — byte-identical either way, so it does not gate consensus. Built
  *     immutable now.
  *
  * **Tracer — one slot, branch-free.** The interpreter carries a single [[tracer]] field (default [[NoTracing]]); the
  * loop and the sub-call boundaries call its hooks unconditionally. When the slot holds [[NoTracing]] the JVM elides
  * the empty bodies (RX-L3-15). This retires the AS-IS two-slot `Option.foreach` (VM ctor *and* `env.tracer`).
  *
  * **Precompiles are P5.** The AS-IS `call` branched to `PrecompiledContracts` before the code path; that registry is
  * not built yet, so this loop always runs the resolved account code. P5 re-adds the precompile short-circuit here.
  *
  * @param tracer
  *   the single execution-observation slot; [[NoTracing]] (the default) is the branch-free disabled path.
  */
final class EvmInterpreter[W <: WorldState[W, S], S <: AccountStorage[S]](
    val tracer: ExecutionTracer = NoTracing
) extends VM[W, S]:

  private type PC = ProgramContext[W, S]
  private type PR = ProgramResult[W, S]
  private type PS = ProgramState[W, S]

  /** Execute a top-level program: a message call when a recipient is present, otherwise a contract creation. */
  def run(context: PC): PR =
    context.recipientAddr match
      case Some(recipientAddr) => call(context, recipientAddr)
      case None                => create(context)._1

  /** Message call — Θ in the Yellow Paper. `ownerAddr` is the account whose code runs (the callee for CALL/STATICCALL,
    * the caller's own address for CALLCODE/DELEGATECALL, decided opcode-side).
    */
  def call(context: PC, ownerAddr: Address): PR =
    val isSubCall = context.callDepth > 0
    if isSubCall then
      tracer.onCallEnter(
        callTypeName(context),
        context.callerAddr,
        context.recipientAddr.getOrElse(Address(UInt256.Zero)),
        context.startGas,
        context.endowment,
        context.inputData
      )
    var exitResult: PR = invalidCallResult(context)
    val result =
      try
        val r =
          if !isValidCall(context) then invalidCallResult(context)
          else
            val recipientAddr = context.recipientAddr.getOrElse(
              throw new IllegalArgumentException("Recipient address must be defined for message call")
            )

            val world1: W =
              if context.doTransfer then context.world.transfer(context.callerAddr, recipientAddr, context.endowment)
              else context.world
            val context1: PC = context.copy(world = world1)

            // Precompile short-circuit: a call whose target is a precompile in the fork-resolved set runs the wrapper
            // (charge its gas, exec, return) instead of resolving/running account code — precompile addresses carry no
            // code. Relocation remap (`precompileRelocations`) is an L4 simulation concern, not wired here (default
            // empty). Byte-authorities: go-ethereum `core/vm/interpreter.go`, besu `MessageCallProcessor`.
            context1.evmConfig.precompiles.get(recipientAddr) match
              case Some(precompile) => precompile.run(context1)
              case None =>
                val code = resolveCode(world1, recipientAddr)
                val env = execEnvOf(context1, code, ownerAddr)

                // EIP-7702: if the callee's code is a delegation designator, warm the delegation target (it was already
                // charged opcode-side); the resolved `code` above is the delegate's code.
                val initialState: PS = initialProgramState(context1, env)
                val warmState = Eip7702.parseDelegation(world1.getCode(recipientAddr)) match
                  case Some(target) => initialState.addAccessedAddress(target)
                  case None         => initialState
                exec(warmState).toResult
        exitResult = r
        r
      finally
        if isSubCall then
          tracer.onCallExit(
            context.startGas - exitResult.gasRemaining,
            exitResult.returnData,
            exitResult.error.map(_.toString)
          )
    result

  /** EIP-7702: resolve delegation code one level deep — if `addr`'s code is the `0xef0100 ‖ target` designator, run the
    * target's code instead.
    */
  private def resolveCode(world: W, addr: Address): ByteString =
    val code = world.getCode(addr)
    Eip7702.parseDelegation(code) match
      case Some(target) => world.getCode(target)
      case None         => code

  /** Contract creation — Λ in the Yellow Paper. `salt` present ⇒ CREATE2 (EIP-1014), else CREATE. Returns the result
    * and the derived new-contract address.
    */
  def create(context: PC, salt: Option[UInt256] = None): (PR, Address) =
    val isSubCall = context.callDepth > 0
    val opName = if salt.isDefined then "CREATE2" else "CREATE"
    if isSubCall then
      tracer.onCallEnter(
        opName,
        context.callerAddr,
        Address(UInt256.Zero),
        context.startGas,
        context.endowment,
        context.inputData
      )
    var exitResult: PR = invalidCallResult(context)
    val (result, newAddress) =
      try
        val pair =
          if !isValidCall(context) then (invalidCallResult(context), Address(UInt256.Zero))
          else
            require(context.recipientAddr.isEmpty, "recipient address must be empty for contract creation")
            require(context.doTransfer, "contract creation will always transfer funds")

            // EIP-3860: initcode size limit — abort arm flows through onCallExit below.
            val maxInitCodeSize = context.evmConfig.maxInitCodeSize
            if context.evmConfig.eip3860Enabled && maxInitCodeSize.exists(max => context.inputData.size > max) then
              (
                invalidCallResult(context).copy(error = Some(InitCodeSizeLimit), gasRemaining = BigInt(0)),
                Address(UInt256.Zero)
              )
            else
              // Address derivation: reuse the WorldState helpers (P2, EIP-1014-vector-tested) — never re-derive here.
              val contractAddr = salt
                .map(s => context.world.create2Address(context.callerAddr, s, context.inputData))
                .getOrElse(context.world.createAddress(context.callerAddr))

              // EIP-684: revert a CREATE when the target already has non-empty code or a non-start nonce.
              // EIP-7610 (Prague / PoS): additionally revert on non-empty storage. PoS = difficulty 0 && baseFee set.
              val conflict =
                if isPoS(context.blockHeader) then context.world.nonEmptyCodeOrNonceOrStorageAccount(contractAddr)
                else context.world.nonEmptyCodeOrNonceAccount(contractAddr)

              // EIP-1283 `originalValue` ambiguity: the ets corpus expects the original world taken *after* account
              // initialisation (which clears storage), not `context.originalWorld` verbatim (geth/parity diverge here —
              // holiman/0154f00d5fcec5f89e85894cbb46fcb2).
              val originInitialisedAccount = context.originalWorld.initialiseAccount(contractAddr)

              val world1: W =
                context.world
                  .initialiseAccount(contractAddr)
                  .transfer(context.callerAddr, contractAddr, context.endowment)

              val code = if conflict then ByteString(INVALID.code) else context.inputData
              val env = execEnvOf(context.copy(world = world1), code, contractAddr).copy(inputData = ByteString.empty)

              val initialState: PS =
                initialProgramState(context.copy(world = world1, originalWorld = originInitialisedAccount), env)
                  .addAccessedAddress(contractAddr)

              val execResult = exec(initialState).toResult
              (saveNewContract(contractAddr, execResult, env.evmConfig), contractAddr)
        exitResult = pair._1
        pair
      finally
        if isSubCall then
          tracer.onCallExit(
            context.startGas - exitResult.gasRemaining,
            exitResult.returnData,
            exitResult.error.map(_.toString)
          )
    (result, newAddress)

  /** The interpreter loop — fetch the opcode via the dense branch-free [[EvmConfig.byteToOpCode]] table, execute it,
    * fire the one-slot [[tracer]], recurse until a halt flag is set (STOP/RETURN/REVERT/INVALID/error/out-of-gas). The
    * undefined-slot [[InvalidOp]] sentinel loud-fails on its own execute, so the loop needs no miss branch.
    */
  @tailrec
  final def exec(state: PS): PS =
    val byte = state.program.getByte(state.pc)
    val opCode = state.config.byteToOpCode(byte)
    val newState = opCode.execute(state)
    tracer.onStep(opCode, state, newState)
    if newState.halted then newState
    else exec(newState)

  /** Derive the EVM call-variant name for tracing from the sub-context (they all reach [[call]] but differ in
    * staticCtx/doTransfer/endowment, matching `OpCode.CallOp`).
    */
  private def callTypeName(context: PC): String =
    if context.staticCtx then "STATICCALL"
    else if !context.doTransfer && context.endowment == UInt256.Zero then "DELEGATECALL"
    else if !context.doTransfer then "CALLCODE"
    else "CALL"

  /** A call is valid when the caller can cover the endowment and the re-entry depth is within
    * [[EvmConfig.MaxCallDepth]] (1024).
    */
  protected def isValidCall(context: PC): Boolean =
    context.endowment <= context.world.getBalance(context.callerAddr) &&
      context.callDepth <= EvmConfig.MaxCallDepth

  private def invalidCallResult(context: PC): PR =
    ProgramResult[W, S](
      ByteString.empty,
      context.startGas,
      context.world,
      Set.empty,
      Nil,
      Nil,
      BigInt(0),
      Some(InvalidCall),
      Set.empty,
      Set.empty
    )

  /** Build the execution environment for a sub-execution (AS-IS `ExecEnv.apply(context, code, ownerAddr)`). The built
    * `ExecEnv` carries no tracer field — the tracer lives on the interpreter's single slot.
    */
  private def execEnvOf(context: PC, code: ByteString, ownerAddr: Address): ExecEnv =
    ExecEnv(
      ownerAddr = ownerAddr,
      callerAddr = context.callerAddr,
      originAddr = context.originAddr,
      gasPrice = context.gasPrice,
      inputData = context.inputData,
      value = context.value,
      program = Program(code),
      blockHeader = context.blockHeader,
      callDepth = context.callDepth,
      startGas = context.startGas,
      evmConfig = context.evmConfig,
      chainId = context.chainId,
      blobBaseFee = context.blobBaseFee,
      prevRandao = context.prevRandao,
      precompileRelocations = context.precompileRelocations,
      blobVersionedHashes = context.blobVersionedHashes,
      traceTransfers = context.traceTransfers
    )

  /** Seed the initial [[ProgramState]] for a sub-execution (AS-IS `ProgramState.apply`). The EIP-2929 accessed-address
    * set starts with origin, the executing account, the propagated `warmAddresses`, and — under EIP-3651 — the block
    * COINBASE. **The precompile addresses are NOT seeded here** (that registry is P5); at the re-entrant call level the
    * warm sets already arrive through `context.warmAddresses`.
    */
  private def initialProgramState(context: PC, env: ExecEnv): PS =
    val coinbase: Set[Address] =
      if context.evmConfig.eip3651Enabled then Set(context.blockHeader.beneficiary) else Set.empty

    ProgramState[W, S](
      vm = this,
      env = env,
      gas = env.startGas,
      world = context.world,
      addressesToDelete = context.initialAddressesToDelete,
      staticCtx = context.staticCtx,
      originalWorld = context.originalWorld,
      accessedAddresses = Set(
        context.originAddr,
        context.recipientAddr.getOrElse(context.callerAddr)
      ) ++ context.warmAddresses ++ coinbase,
      accessedStorageKeys = context.warmStorage,
      transientStorage = context.transientStorage
    )

  /** PoS signal (post-Merge): difficulty is zero and a base fee is present — the AS-IS `BlockHeader.isPoS`, computed
    * locally (the L1 record carries no behavior method). Selects EIP-7610 (PoS) vs EIP-684 (PoW) create conflict.
    */
  private def isPoS(header: BlockHeader): Boolean =
    header.difficulty.signum == 0 && header.baseFeePerGas.isDefined

  /** Deposit the returned init-code result as the new contract's runtime code, applying the deposit-gas and the
    * EIP-3541 / EIP-170 / out-of-gas guards (AS-IS `saveNewContract`).
    *
    *   - error already set ⇒ keep a revert's remaining gas, else consume all gas;
    *   - EIP-3541: runtime code starting with `0xEF` is an exceptional abort;
    *   - EIP-170: runtime code over [[EvmConfig.maxCodeSize]] is an exceptional abort (the fork-resolved `maxCodeSize`
    *     presence *is* the activation gate — no block-number branch);
    *   - out-of-gas on deposit: exceptional abort iff [[EvmConfig.exceptionalFailedCodeDeposit]], else keep the result;
    *   - otherwise: charge the deposit gas and store the code.
    */
  private def saveNewContract(address: Address, result: PR, config: EvmConfig): PR =
    if result.error.isDefined then
      if result.error.contains(RevertOccurs) then result else result.copy(gasRemaining = BigInt(0))
    else
      val contractCode = result.returnData
      val codeDepositCost: BigInt = config.gasCalculator.G_codedeposit * contractCode.size
      val maxCodeSizeExceeded = config.maxCodeSize.exists(limit => contractCode.size > limit)
      val codeStoreOutOfGas = result.gasRemaining < codeDepositCost
      val startsWithEF = config.eip3541Enabled && contractCode.nonEmpty && contractCode.head == 0xef.toByte

      if startsWithEF then result.copy(error = Some(InvalidCode), gasRemaining = BigInt(0))
      else if maxCodeSizeExceeded || (codeStoreOutOfGas && config.exceptionalFailedCodeDeposit) then
        result.copy(error = Some(OutOfGas), gasRemaining = BigInt(0))
      else if codeStoreOutOfGas && !config.exceptionalFailedCodeDeposit then result
      else
        result.copy(
          gasRemaining = result.gasRemaining - codeDepositCost,
          world = result.world.saveCode(address, result.returnData)
        )
