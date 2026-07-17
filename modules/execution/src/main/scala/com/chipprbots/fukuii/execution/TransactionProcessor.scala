package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.domain.Log
import com.chipprbots.fukuii.domain.Receipt
import com.chipprbots.fukuii.domain.ReceiptStatus
import com.chipprbots.fukuii.domain.SenderRecovery
import com.chipprbots.fukuii.domain.Transaction
import com.chipprbots.fukuii.domain.Wei
import com.chipprbots.fukuii.evm.CallContext
import com.chipprbots.fukuii.evm.Eip7702
import com.chipprbots.fukuii.evm.EvmInterpreter
import com.chipprbots.fukuii.evm.ProposalId.Eip

/** Why a transaction cannot be **included** — a pre-execution rejection (a block carrying such a tx is itself invalid).
  * Distinct from a *reverted* transaction, which is included, consumes gas, and yields a `status=false` receipt: a
  * revert is a successful [[TransactionProcessor.processTransaction]] returning `Right(result.succeeded = false)`, an
  * inclusion failure is a `Left`.
  */
enum TransactionError:
  /** The tx nonce does not match the sender's account nonce (go-ethereum `ErrNonceTooLow`/`ErrNonceTooHigh`). */
  case InvalidNonce(expected: BigInt, actual: BigInt)

  /** Balance below the upfront cost `gasLimit * feeCap + value` (go-ethereum `ErrInsufficientFunds`, `buyGas`). */
  case InsufficientBalance(required: BigInt, available: BigInt)

  /** `gasLimit < intrinsicGas` (go-ethereum `ErrIntrinsicGas`, `state_transition.go:687`). */
  case IntrinsicGasTooHigh(intrinsic: BigInt, gasLimit: BigInt)

  /** `gasLimit < floorDataGas` under EIP-7623 (go-ethereum `ErrFloorDataGas`, `state_transition.go:694`). */
  case CalldataFloorTooHigh(floor: BigInt, gasLimit: BigInt)

  /** `gasLimit > 2^24` under EIP-7825 (go-ethereum `ErrGasLimitTooHigh`, `state_transition.go:564`). */
  case GasLimitAboveCap(gasLimit: BigInt, cap: BigInt)

/** The outcome of executing one transaction — the record P4's block loop folds into the block's cumulative gas,
  * receipts trie, and state-diff. `succeeded=false` is a **reverted-but-included** tx (gas consumed, state rolled back
  * except the gas accounting), not an inclusion failure (that is a `Left[TransactionError]`).
  *
  * @param gasUsed
  *   the settled gas used (post-refund, post-EIP-7623-floor) — the value the block's `gasUsed` commitment sums.
  * @param world
  *   the world after this tx (uncommitted — the block-level `persist` is P4).
  * @param createdAddress
  *   the new contract address for a **successful** creation, else `None`.
  */
final case class TransactionResult(
    gasUsed: BigInt,
    receipt: Receipt,
    world: InMemoryWorldState,
    logs: Seq[Log],
    returnData: ByteString,
    createdAddress: Option[Address],
    succeeded: Boolean
)

/** The per-transaction execution engine — besu `MainnetTransactionProcessor` / go-ethereum `state_transition.go`.
  * Drives L3's [[EvmInterpreter]] and produces a per-tx [[TransactionResult]] + [[Receipt]].
  *
  * **Consensus surface.** Every gas rule here is a Tier-A value reproduced byte-for-byte from go-ethereum
  * `core/state_transition.go` (ETH schedule; forge/beacon co-sign at build): intrinsic gas ([[IntrinsicGas]]), the
  * EIP-3529 refund cap, the EIP-7623 calldata floor, the EIP-1559 effective-tip settlement. The ETC schedule reaches
  * these at Magneto (EIP-2929/3529) and Spiral (EIP-3860); every constant flows through the fork-resolved
  * [[com.chipprbots.fukuii.evm.EvmConfig.gasCalculator]] on the [[ProtocolSpec]] bundle, so there is **no `if(isETC)`**
  * anywhere — only the resolved fork's values.
  *
  * **The base-fee portion is NOT settled here.** The coinbase receives only the effective *tip* (`gasUsed *
  * effectiveTip`); the base-fee portion (`gasUsed * baseFee`) is left **uncredited** so the P4 `FeeDisposition`
  * collaborator owns its disposition — burn (ETH) vs treasury (ECIP-1111/ETC). P2 neither burns nor routes it (L4 plan
  * §1/§7/§9, RX-L4-09/10).
  *
  * **The EIP-4844 blob fee IS debited here and burned (F-L4-5, P5).** Under Cancun+ a blob tx additionally pays
  * `blobGas × blobBaseFee` — the *actual* blob base fee derived from the header's `excessBlobGas` ([[CalcBlobFee]]),
  * distinct from the F-L4-3 upfront *check* (which uses the fee cap). It is deducted upfront and credited nowhere (like
  * the base fee) → burned (go-ethereum `buyGas`, `state_transition.go:471-483`). `0` on ETC.
  *
  * **R2:** stateless and immutable; the only per-call variation rides on the immutable [[SimulationOptions]] argument
  * (no `@volatile`, no `object … { var … }`). Concrete over [[InMemoryWorldState]] — L4's single concrete world —
  * rather than generic (L3 is generic because it cannot import L4's world; L4 may be concrete about its own).
  */
final class TransactionProcessor(interpreter: EvmInterpreter[InMemoryWorldState, InMemoryAccountStorage]):

  import TransactionProcessor.*

  /** Process one transaction: validate → deduct upfront gas + bump nonce → apply EIP-7702 authorizations → run the VM →
    * settle gas (EIP-3529 refund cap, EIP-7623 floor) → tip the coinbase → clean up accounts → assemble the receipt.
    *
    * @param sender
    *   the recovered sender (sender recovery + its homestead gating is the caller's / L1's concern — P4 may recover in
    *   parallel off the hot path).
    * @param priorCumulativeGasUsed
    *   the block's cumulative gas used before this tx — folded into the receipt's `cumulativeGasUsed`.
    */
  def processTransaction(
      tx: Transaction,
      sender: Address,
      header: BlockHeader,
      spec: ProtocolSpec,
      world: InMemoryWorldState,
      priorCumulativeGasUsed: BigInt,
      chainId: ChainId,
      simulation: SimulationOptions = SimulationOptions.none
  ): Either[TransactionError, TransactionResult] =
    val evmConfig = spec.evmConfig
    val gc = evmConfig.gasCalculator
    val gasLimit = TxFields.gasLimit(tx)
    val baseFee = header.baseFeePerGas.getOrElse(BigInt(0))
    val effectiveGasPrice = TxFields.effectiveGasPrice(tx, baseFee)
    val value = TxFields.value(tx)
    // EIP-4844 blob base fee (F-L4-5) — the *actual* blob gas price from the header's excessBlobGas, fork-resolved
    // (Cancun 3338477 → Prague+ 5007716 update fraction, EIP-7691). Computed once here: the debit below burns
    // `blobGas × blobBaseFee`, and the same value is threaded into the CallContext for the BLOBBASEFEE opcode. `0` on
    // ETC (EIP-4844 never active) and on any non-Cancun ETH block (go-ethereum eip4844.go `blobBaseFee`).
    val blobBaseFee: BigInt =
      if evmConfig.isActive(Eip(4844)) then
        val updateFraction =
          if evmConfig.isActive(Eip(7691)) then CalcBlobFee.PragueUpdateFraction else CalcBlobFee.CancunUpdateFraction
        CalcBlobFee.blobBaseFee(BigInt(header.excessBlobGas.getOrElse(0L)), updateFraction)
      else BigInt(0)
    val intrinsic = IntrinsicGas.intrinsicGas(tx, evmConfig)
    val floor = IntrinsicGas.floorDataGas(tx, evmConfig)
    val floorActive = IntrinsicGas.isActiveFloor(evmConfig)

    validate(tx, sender, world, evmConfig, gasLimit, intrinsic, floor, floorActive, value) match
      case Some(error) => Left(error)
      case None        =>
        // (b) upfront: debit gasLimit * effectiveGasPrice + blobGas * blobBaseFee (F-L4-5, the hard-gate debit), bump
        // nonce (value is transferred by the VM, not here). The blob fee uses the *actual* blobBaseFee (from
        // excessBlobGas), distinct from the F-L4-3 upfront *check* which uses the fee cap; go-ethereum `buyGas` adds
        // `blobGas × blobBaseFee` to `mgval` under Cancun (`state_transition.go:471-483`). The blob fee is **burned** —
        // deducted here and credited nowhere (creditFees settles only execution gas + tip), like the EIP-1559 base fee.
        val blobFee = TxFields.blobGas(tx) * blobBaseFee
        val worldAfterUpfront = debitGasAndBumpNonce(world, sender, gasLimit * effectiveGasPrice + blobFee)

        // (c) EIP-7702 authorizations (SetCode tx only) — installs delegations, returns the tx-level refund and the
        // warmed authority set. Runs before the VM (geth `applyAuthorizations`); persists across a VM revert.
        val (worldAfterAuth, authRefund, authorities) =
          applyAuthorizations(worldAfterUpfront, TxFields.authorizationList(tx), gc, chainId)

        // (d) warm sets — the access-list addresses/slots + precompiles + recovered authorities (the interpreter itself
        // seeds sender/recipient/COINBASE-if-3651).
        val accessList = TxFields.accessList(tx)
        val warmAddresses =
          accessList.map(_.address).toSet ++ evmConfig.precompiles.keySet ++ authorities
        val warmStorage =
          accessList.flatMap(entry => entry.storageKeys.map(key => (entry.address, UInt256.fromBytes(key.bytes)))).toSet

        // (e) build the top-level CallContext and run the VM.
        val context = CallContext[InMemoryWorldState, InMemoryAccountStorage](
          callerAddr = sender,
          originAddr = sender,
          recipientAddr = TxFields.to(tx),
          gasPrice = UInt256(effectiveGasPrice),
          startGas = gasLimit - intrinsic,
          inputData = TxFields.payload(tx),
          value = UInt256(value),
          endowment = UInt256(value),
          doTransfer = true,
          blockHeader = header,
          callDepth = 0,
          world = worldAfterAuth,
          initialAddressesToDelete = Set.empty,
          evmConfig = evmConfig,
          chainId = chainId,
          staticCtx = false,
          originalWorld = worldAfterAuth,
          warmAddresses = warmAddresses,
          warmStorage = warmStorage,
          precompileRelocations = simulation.precompileRelocations,
          blobVersionedHashes = blobVersionedHashes(tx),
          blobBaseFee = UInt256(blobBaseFee) // EIP-7516 BLOBBASEFEE reads the actual blob base fee (F-L4-5)
        )

        val (execResult, createdAddress) =
          if TxFields.isContractCreation(tx) then
            val (result, address) = interpreter.create(context)
            (result, Some(address))
          else (interpreter.run(context), None)

        val succeeded = execResult.error.isEmpty

        // (f/g) settle gas + tip the coinbase.
        val settled = settleGas(
          gasLimit = gasLimit,
          gasRemaining = execResult.gasRemaining,
          refundCounter = execResult.gasRefund + authRefund,
          refundQuotient = if evmConfig.isActive(Eip(3529)) then 5 else 2,
          floor = floor,
          floorActive = floorActive
        )

        // On failure, discard the VM's state changes (value transfer + writes) — revert to the post-authorization world;
        // authorizations and the gas accounting survive a revert (geth reverts to the snapshot taken *after* auth).
        val baseWorld = if succeeded then execResult.world else worldAfterAuth

        val effectiveTip = if header.baseFeePerGas.isDefined then effectiveGasPrice - baseFee else effectiveGasPrice
        val worldAfterFees = creditFees(
          baseWorld,
          sender,
          header.beneficiary,
          settled.gasLeft * effectiveGasPrice,
          settled.gasUsed * effectiveTip
        )

        // (h) account cleanup — SELFDESTRUCT sweep (success only), then the EIP-161 empty-touched sweep.
        val worldAfterDeletes =
          if succeeded then worldAfterFees.deleteAccounts(execResult.addressesToDelete) else worldAfterFees
        val finalWorld = worldAfterDeletes.deleteEmptyTouchedAccounts

        // (i) assemble the receipt (EIP-658 status form — the post-Byzantium/Atlantis fork our two families run; the
        // pre-Byzantium PostStateRoot receipt is a P4 full-history concern).
        val logs = if succeeded then execResult.logs else Nil
        val receipt = Receipt(
          status = ReceiptStatus.Status(succeeded),
          cumulativeGasUsed = (priorCumulativeGasUsed + settled.gasUsed).toLong,
          logsBloom = Bloom.of(logs),
          logs = logs.toList,
          txType = tx.txType
        )

        Right(
          TransactionResult(
            gasUsed = settled.gasUsed,
            receipt = receipt,
            world = finalWorld,
            logs = logs,
            returnData = execResult.returnData,
            createdAddress = if succeeded then createdAddress else None,
            succeeded = succeeded
          )
        )

  // -- validation (a) --------------------------------------------------------------------------------------------------

  private def validate(
      tx: Transaction,
      sender: Address,
      world: InMemoryWorldState,
      evmConfig: com.chipprbots.fukuii.evm.EvmConfig,
      gasLimit: BigInt,
      intrinsic: BigInt,
      floor: BigInt,
      floorActive: Boolean,
      value: BigInt
  ): Option[TransactionError] =
    val account = world.getAccount(sender).getOrElse(world.getEmptyAccount)
    val senderNonce = account.nonce.toBigInt
    val senderBalance = account.balance.toUInt256.toBigInt
    // EIP-4844 (F-L4-3): under Cancun+ a blob tx's upfront balance must also cover `blobGas * blobGasFeeCap`
    // (go-ethereum `buyGas` adds `blobGasUsed * tx.BlobGasFeeCap` to the balance check, `state_transition.go:456-465`).
    // ETH-only — ETC never activates EIP-4844, so this is always 0 on the ETC path; beacon co-signs at build.
    val blobFeeCost =
      if evmConfig.isActive(Eip(4844)) then TxFields.blobGas(tx) * TxFields.blobGasFeeCap(tx) else BigInt(0)
    val maxUpfrontCost = gasLimit * TxFields.feeCap(tx) + value + blobFeeCost

    if evmConfig.isActive(Eip(7825)) && gasLimit > MaxTxGas then
      Some(TransactionError.GasLimitAboveCap(gasLimit, MaxTxGas))
    else if senderNonce != TxFields.nonce(tx) then Some(TransactionError.InvalidNonce(senderNonce, TxFields.nonce(tx)))
    else if senderBalance < maxUpfrontCost then
      Some(TransactionError.InsufficientBalance(maxUpfrontCost, senderBalance))
    else if intrinsic > gasLimit then Some(TransactionError.IntrinsicGasTooHigh(intrinsic, gasLimit))
    else if floorActive && floor > gasLimit then Some(TransactionError.CalldataFloorTooHigh(floor, gasLimit))
    else None

  // -- upfront (b) -----------------------------------------------------------------------------------------------------

  private def debitGasAndBumpNonce(world: InMemoryWorldState, sender: Address, gasCost: BigInt): InMemoryWorldState =
    val account = world.getAccount(sender).getOrElse(world.getEmptyAccount)
    world.saveAccount(
      sender,
      account.copy(nonce = account.nonce + UInt256.One, balance = Wei(account.balance.toUInt256 - UInt256(gasCost)))
    )

  // -- EIP-7702 authorizations (c) — ETH-family, beacon co-signs -------------------------------------------------------

  /** Apply the EIP-7702 authorization list, byte-cited to go-ethereum `applyAuthorization`
    * (`state_transition.go:1038-1113`), **pre-Amsterdam path** (the Amsterdam EIP-2780 runtime state-gas charging is
    * ETH-future, out of scope). For each tuple, in EIP-7702's mandated order: (1-2) verify chain-id (0 or current) and
    * the EIP-2681 nonce bound, (3) recover the authority (a **second, independent** signature surface from the outer tx
    * signature; [[SenderRecovery.recoverAuthority]]), (4) warm the authority, (5-6) verify the authority-code (empty or
    * already a delegation) and authority-nonce; if all pass, refund `CallNewAccountGas - TxAuthTupleGas` (25000 − 12500
    * \= 12500) for an existing authority, bump its nonce, and install (or clear, on the zero address) the `0xef0100 ‖
    * address` delegation designator. A tuple failing steps 1-3 is skipped **before** warming (a wrong chain-id must not
    * warm the address); a tuple failing steps 5-6 is skipped but the authority **stays warmed** (geth/besu add it to
    * the access list at step 4, before those checks).
    *
    * ⚠️ **beacon co-sign gate:** the 12500 refund value and the recover/validate ordering are ETH consensus. Cited to
    * go-ethereum; flagged for beacon at build.
    */
  private def applyAuthorizations(
      world: InMemoryWorldState,
      auths: List[com.chipprbots.fukuii.domain.SetCodeAuthorization],
      gc: com.chipprbots.fukuii.evm.GasCalculator,
      chainId: ChainId
  ): (InMemoryWorldState, BigInt, Set[Address]) =
    auths.foldLeft((world, BigInt(0), Set.empty[Address])) { case ((w, refund, warm), auth) =>
      SenderRecovery.recoverAuthority(auth) match
        case Left(_) => (w, refund, warm) // bad signature — the authority cannot be warmed (no recovered address)
        case Right(authority) =>
          // EIP-7702 steps 1-2 gate warming (step 4): a wrong chain-id or an EIP-2681-overflowing nonce skips the
          // tuple *before* the authority is added to `accessed_addresses`. go-ethereum `validateAuthorization`
          // returns on the chain-id (`state_transition.go:1002`) and `Nonce+1<Nonce` (`:1006`) checks *before*
          // `AddAddressToAccessList` (`:1019`); besu `CodeDelegationProcessor.processCodeDelegation` returns from
          // `isCodeDelegationValid` (`:95`) before `addAccessedDelegatorAddress` (`:114`). Warming a wrong-chain-id
          // authority would over-warm the address and fork the state on any later EIP-2929 access. The code-emptiness
          // and nonce-match checks (EIP steps 5-6) stay *after* warming — a tuple failing those is still warmed.
          val chainOk = auth.chainId.toBigInt == BigInt(0) || auth.chainId.toBigInt == chainId.toBigInt
          val nonceOverflowOk = auth.nonce.toBigInt < MaxNonce
          if !(chainOk && nonceOverflowOk) then (w, refund, warm) // steps 1-2 fail — authority NOT warmed
          else
            val warmed = warm + authority // step 4 — warmed even if the code/nonce checks below reject the tuple
            val code = w.getCode(authority)
            val codeOk = code.isEmpty || Eip7702.parseDelegation(code).isDefined
            val nonceOk = w.getAccount(authority).map(_.nonce.toBigInt).getOrElse(BigInt(0)) == auth.nonce.toBigInt
            if !(codeOk && nonceOk) then (w, refund, warmed)
            else
              val accountRefund = if w.accountExists(authority) then gc.G_newaccount - TxAuthTupleGas else BigInt(0)
              val account = w.getAccount(authority).getOrElse(w.getEmptyAccount)
              val withNonce = w.saveAccount(authority, account.copy(nonce = UInt256(auth.nonce.toBigInt + 1)))
              val withCode =
                if auth.address == Address.Zero then
                  if Eip7702.parseDelegation(code).isDefined then withNonce.saveCode(authority, ByteString.empty)
                  else withNonce
                else withNonce.saveCode(authority, ByteString(Eip7702.DelegationPrefix) ++ auth.address.bytes)
              (withCode, refund + accountRefund, warmed)
    }

  // -- gas settlement (f) + fee credit (g) -----------------------------------------------------------------------------

  private def creditFees(
      world: InMemoryWorldState,
      sender: Address,
      coinbase: Address,
      senderRefund: BigInt,
      coinbaseTip: BigInt
  ): InMemoryWorldState =
    addBalance(addBalance(world, sender, senderRefund), coinbase, coinbaseTip)

  /** Add `amount` to `address`'s balance, creating the account if absent, and touch it (go-ethereum `AddBalance` =
    * `getOrNewStateObject` + `AddBalance` + touch; an `AddBalance(0)` still touches, so a zero tip to an empty coinbase
    * is created-then-swept by the EIP-161 finalise, a net no-op).
    */
  private def addBalance(world: InMemoryWorldState, address: Address, amount: BigInt): InMemoryWorldState =
    val account = world.getAccount(address).getOrElse(world.getEmptyAccount)
    world
      .saveAccount(address, account.copy(balance = Wei(account.balance.toUInt256 + UInt256(amount))))
      .touchAccounts(address)

  private def blobVersionedHashes(tx: Transaction): Seq[ByteString] = tx match
    case blob: Transaction.Blob => blob.blobVersionedHashes.map(_.bytes)
    case _                      => Seq.empty

object TransactionProcessor:

  /** EIP-7825 per-transaction gas cap `2^24` (16,777,216) — go-ethereum `params.MaxTxGas`
    * (`params/protocol_params.go:31`), co-authority besu `EIP_7825_TRANSACTION_GAS_LIMIT_CAP = 16_777_216L` (the stale
    * besu-`main` "30M" comment is superseded). **Active on ETH Osaka:** L3 carries `Eip(7825)` in
    * `EthOsaka`/`ethOsakaSet` (ETH-only — NOT `EtcOlympia`), so `evmConfig.isActive(Eip(7825))` arms the cap check on
    * an ETH Osaka header. go-ethereum gates it `!rules.IsAmsterdam && rules.IsOsaka` (`core/state_transition.go:564`);
    * fukuii has no Amsterdam fork, so Osaka membership is the correct dispatch.
    */
  val MaxTxGas: BigInt = BigInt(1) << 24

  /** EIP-7702 `TxAuthTupleGas` (go-ethereum `params.TxAuthTupleGas = 12500`) — the per-authority base cost; the
    * intrinsic phase charged `CallNewAccountGas` (25000), so an existing authority is refunded `25000 − 12500 = 12500`
    * (`state_transition.go:1047`).
    */
  val TxAuthTupleGas: BigInt = 12500

  /** EIP-2681 nonce cap `2^64 - 1` — an EIP-7702 authorization whose nonce equals this is invalid and its tuple is
    * skipped **before** the authority is warmed (go-ethereum `validateAuthorization` rejects on `auth.Nonce+1 <
    * auth.Nonce`, `state_transition.go:1006`; besu `isCodeDelegationValid` rejects on `nonce() == MAX_NONCE`,
    * `CodeDelegationProcessor.java:161`).
    */
  val MaxNonce: BigInt = (BigInt(1) << 64) - 1

  /** The gas settled for a transaction — `gasUsed` folds into the block commitment; `gasLeft` is refunded to the sender
    * at the effective gas price. `private[execution]` so the settle formula (the EIP-3529 cap + EIP-7623 floor) is
    * unit-testable against cited go-ethereum values without a full VM run.
    */
  final private[execution] case class SettledGas(gasUsed: BigInt, gasLeft: BigInt)

  /** Settle gas after the VM — go-ethereum `settleGas` (`state_transition.go:938-990`), classic (pre-EIP-8037) path:
    *
    *   - `gasUsedBeforeRefund = gasLimit - gasRemaining` (the VM was handed `gasLimit - intrinsic`, so its returned
    *     `gasRemaining` reflects the whole-tx leftover);
    *   - **EIP-3529 refund cap** `refund = min(refundCounter, gasUsedBeforeRefund / quotient)`, quotient 5 post-London/
    *     Magneto, 2 before;
    *   - `gasLeft = gasRemaining + refund`, `gasUsed = gasUsedBeforeRefund - refund`;
    *   - **EIP-7623 floor** `if gasUsed < floor then { gasLeft -= floor - gasUsed; gasUsed = floor }`.
    */
  private[execution] def settleGas(
      gasLimit: BigInt,
      gasRemaining: BigInt,
      refundCounter: BigInt,
      refundQuotient: BigInt,
      floor: BigInt,
      floorActive: Boolean
  ): SettledGas =
    val gasUsedBeforeRefund = gasLimit - gasRemaining
    val refund = refundCounter.min(gasUsedBeforeRefund / refundQuotient)
    val gasLeftAfterRefund = gasRemaining + refund
    val gasUsedAfterRefund = gasUsedBeforeRefund - refund
    if floorActive && gasUsedAfterRefund < floor then
      SettledGas(gasUsed = floor, gasLeft = gasLeftAfterRefund - (floor - gasUsedAfterRefund))
    else SettledGas(gasUsed = gasUsedAfterRefund, gasLeft = gasLeftAfterRefund)
