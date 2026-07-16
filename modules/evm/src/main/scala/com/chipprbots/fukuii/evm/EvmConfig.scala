package com.chipprbots.fukuii.evm

import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.evm.ProposalId.Eip

/** The per-block **resolved** EVM configuration — the value object the interpreter runs against, built once for a fork
  * and cached (fukuii's `Rules`/`IReleaseSpec`/`ProtocolSpec` analog).
  *
  * Carries the active proposal set, the injected per-fork [[GasCalculator]], the dense opcode dispatch table, and the
  * per-fork config flags the opcodes read. It does **not** carry chain identity or block/tx context (chainId,
  * blobBaseFee, prevRandao) — those are per-instance runtime values threaded through [[ExecEnv]]/[[ProgramContext]]
  * (geth `BlockContext`/`TxContext`), never a property of the fork schedule (R2, RX-L3-13).
  *
  * **P2 shape.** The [[opCodes]] dense table + the per-fork [[gasCalculator]] land here now, populated by **direct
  * per-fork construction** (the named [[EvmConfig.EthCancun]]/[[EvmConfig.EthOsaka]]/[[EvmConfig.EtcOlympia]]/…
  * bundles). The ordered `deriveEvmConfigAt` fold that derives them at `(header, schedule)` — and the `forBlock`
  * collapse onto it — is **P3**; [[forBlock]] here keeps its P0 membership-only shape (the two headline fields default
  * to a loud-failing all-[[InvalidOp]] table + Frontier gas until P3 wires the fold).
  *
  * **R2 (multi-instance isolation):** immutable and freely shareable — two `ChainInstance`s in one binary may share a
  * cached `header → EvmConfig` safely (the `IArray` table is immutable). No `object … { var … }` / process-global EVM
  * state anywhere in `evm`.
  */
final case class EvmConfig(
    activeProposals: Set[ProposalId],
    gasCalculator: GasCalculator = GasCalculator.Frontier,
    opCodes: IArray[OpCode] = OpCodes.InvalidTable,
    noEmptyAccounts: Boolean = false,
    exceptionalFailedCodeDeposit: Boolean = false,
    chargeSelfDestructForNewAccount: Boolean = false,
    maxCodeSize: Option[BigInt] = None
):

  /** Raw proposal membership. The intent-named getters (`eip2929Enabled`, `eip6780Enabled`, …) are the fork-name-free
    * layer over this; the interpreter reads intent, never a fork identity.
    */
  def isActive(id: ProposalId): Boolean =
    activeProposals.contains(id)

  /** Dense branch-free O(1) opcode dispatch — the [[opCodes]] `IArray` indexed by the opcode byte, every undefined slot
    * pre-filled with the loud-failing [[InvalidOp]] sentinel (no `Option`, no null). Replaces the AS-IS `Map[Byte,
    * OpCode]` hash probe (RX-L3-02).
    */
  def byteToOpCode(byte: Byte): OpCode =
    opCodes(byte & 0xff)

object EvmConfig:

  /** The single fork-dispatch entry point — resolve the EVM config active at `header` under `schedule`.
    *
    * Replaces the two AS-IS `EvmConfig.forBlock` overloads with **one** `forBlock(header, schedule)`: the header
    * supplies both number and timestamp, and each proposal's [[ForkActivation]] case decides its own boundary (besu's
    * single `getByBlockHeader`). **P0/P2 resolves the active proposal *set* only** (order-independent membership); the
    * ordered `deriveEvmConfigAt` fold that populates the [[EvmConfig.gasCalculator]] + [[EvmConfig.opCodes]] from this
    * set — and proves byte-identity with the two-overload AS-IS — is **P3**'s fold-identity gate (Chesterton's Fence).
    */
  def forBlock(header: BlockHeader, schedule: ForkSchedule): EvmConfig =
    EvmConfig(schedule.activeAt(header.number, header.unixTimestamp))

  // -- intent-named getters (RX-L3-12): the interpreter reads a neutral EIP-keyed intent, never a fork name -----------

  extension (c: EvmConfig)
    /** EIP-2929 warm/cold state access is active (the warm/cold *cost* lives on the [[GasCalculator]]; this gates the
      * SSTORE / SELFDESTRUCT cold surcharge, which has a 0 warm surcharge unlike the generic access split).
      */
    def eip2929Enabled: Boolean = c.isActive(Eip(2929))

    /** EIP-1283 net-metered SSTORE (Constantinople-only, repealed at Petersburg). */
    def eip1283Enabled: Boolean = c.isActive(Eip(1283))

    /** EIP-2200 net-metered SSTORE (Istanbul / ETC Phoenix onward). */
    def eip2200Enabled: Boolean = c.isActive(Eip(2200))

    /** EIP-3860 initcode word metering + size limit. */
    def eip3860Enabled: Boolean = c.isActive(Eip(3860))

    /** EIP-6780 — SELFDESTRUCT only destroys a same-transaction-created contract (a *semantic*, not a new opcode). */
    def eip6780Enabled: Boolean = c.isActive(Eip(6780))

    /** EIP-3860 max initcode size — twice [[EvmConfig.maxCodeSize]] when EIP-3860 is active, else `None`. */
    def maxInitCodeSize: Option[BigInt] =
      if c.eip3860Enabled then c.maxCodeSize.map(_ * 2) else None

  // -- named per-fork bundles (direct per-fork construction; the fold is P3) ------------------------------------------

  /** EIP-170 contract code-size limit (24576) — the modern `maxCodeSize`. */
  private val CodeSizeLimit: Option[BigInt] = Some(BigInt(24576))

  /** The shared opcode/behavior EIPs a modern (post-Spiral/Shanghai) fork has active — the markers the intent getters
    * and the shared bundle bases reference (opcode EIPs + the 2929/2200/3529/3855 gas/metering markers).
    */
  private val ModernSharedProposals: Set[ProposalId] =
    Set(7, 140, 214, 211, 1052, 1014, 145, 1344, 1884, 3855, 2929, 2200, 3529, 3860).map(Eip.apply)

  /** Frontier — the genesis config (membership-empty, no modern flags). */
  val Frontier: EvmConfig =
    EvmConfig(Set.empty, GasCalculator.Frontier, OpCodes.denseTable(OpCodes.FrontierOpCodes))

  /** ETH Cancun — Shanghai base + EIP-1153/4844/5656/7516 + EIP-6780 semantics + EIP-3198 BASEFEE. */
  val EthCancun: EvmConfig =
    EvmConfig(
      activeProposals = ModernSharedProposals ++ Set(3198, 1153, 5656, 4844, 7516, 6780).map(Eip.apply),
      gasCalculator = GasCalculator.EthCancun,
      opCodes = OpCodes.denseTable(OpCodes.EthCancunOpCodes),
      noEmptyAccounts = true,
      exceptionalFailedCodeDeposit = true,
      chargeSelfDestructForNewAccount = true,
      maxCodeSize = CodeSizeLimit
    )

  /** ETH Prague — Cancun + EIP-2537/7702/7623/7691 (+ CL-side EIP-6110/7251/7002/7685); no new opcode. */
  val EthPrague: EvmConfig =
    EthCancun.copy(
      activeProposals = EthCancun.activeProposals ++ Set(2537, 7702, 7623, 7691).map(Eip.apply),
      gasCalculator = GasCalculator.EthPrague,
      opCodes = OpCodes.denseTable(OpCodes.EthPragueOpCodes)
    )

  /** ETH Osaka — Prague + CLZ (EIP-7939) + P256VERIFY (EIP-7951) + MODEXP EIP-7823/7883 + EIP-7918/7892. */
  val EthOsaka: EvmConfig =
    EthPrague.copy(
      activeProposals = EthPrague.activeProposals ++ Set(7939, 7951, 7883, 7823, 7918, 7892).map(Eip.apply),
      gasCalculator = GasCalculator.EthOsaka,
      opCodes = OpCodes.denseTable(OpCodes.EthOsakaOpCodes)
    )

  /** ETC Olympia (ECIP-1121) — Spiral base + EIP-3198/1153/5656/7939 opcodes, EIP-6780 semantics, EIP-2537/7951
    * precompiles, EIP-7883/7823/7623 gas, EIP-7702 call/create, EIP-1559 base-fee read. **Excludes EIP-4844/7516** (no
    * blob opcodes on ETC). forge co-signs the byte values.
    */
  val EtcOlympia: EvmConfig =
    EvmConfig(
      activeProposals = ModernSharedProposals ++
        Set(3198, 1153, 5656, 7939, 6780, 2537, 7951, 7883, 7823, 7623, 7702, 1559).map(Eip.apply),
      gasCalculator = GasCalculator.EtcOlympia,
      opCodes = OpCodes.denseTable(OpCodes.EtcOlympiaOpCodes),
      noEmptyAccounts = true,
      exceptionalFailedCodeDeposit = true,
      chargeSelfDestructForNewAccount = true,
      maxCodeSize = CodeSizeLimit
    )
