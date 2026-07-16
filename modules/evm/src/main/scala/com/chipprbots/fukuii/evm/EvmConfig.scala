package com.chipprbots.fukuii.evm

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.evm.ProposalId.Eip

/** The per-block **resolved** EVM configuration — the value object the interpreter runs against, built once for a fork
  * and cached (fukuii's `Rules`/`IReleaseSpec`/`ProtocolSpec` analog).
  *
  * Carries the active proposal set, the injected per-fork [[GasCalculator]], the dense opcode dispatch table, and the
  * per-fork config flags the opcodes read. It does **not** carry chain identity or block/tx context (chainId,
  * blobBaseFee, prevRandao) — those are per-instance runtime values threaded through [[ExecutionEnv]]/[[CallContext]]
  * (geth `BlockContext`/`TxContext`), never a property of the fork schedule (R2, RX-L3-13).
  *
  * **P3 shape.** [[EvmConfig.forBlock]] resolves this value via the ordered [[EvmConfig.deriveEvmConfigAt]] fold over
  * the [[EvmProposals]] registry — the production path. The named per-fork bundles
  * ([[EvmConfig.EthCancun]]/[[EvmConfig.EthOsaka]]/[[EvmConfig.EtcOlympia]]/…), built by **direct per-fork
  * construction**, remain the fold-identity **oracle** (`EvmProposalFoldIdentitySpec`) — what the fold must reproduce
  * byte-for-byte at every activation height on both fork clocks.
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
    maxCodeSize: Option[BigInt] = None,
    precompiles: Map[Address, PrecompiledContracts.PrecompiledContract] = Map.empty
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
    * **One** `forBlock(header, schedule)` — never the two AS-IS overloads, never a `forTimestamp`: the header supplies
    * both number and timestamp, and each proposal's [[ForkActivation]] case decides its own boundary (besu's single
    * `getByBlockHeader` over `MilestoneType.{BLOCK_NUMBER,TIMESTAMP}`, the axes staying type-distinct as enum cases).
    * `schedule.activeAt` resolves the active `Set[ProposalId]`; [[deriveEvmConfigAt]] folds it into the resolved
    * `(opCodes, gasCalculator, flags)` bundle.
    *
    * **P3 wires this onto the fold** (P0/P2 stubbed it to a membership-only [[EvmConfig]] with the all-[[InvalidOp]]
    * table + Frontier gas). The named per-fork bundles ([[EthCancun]]/[[EthOsaka]]/[[EtcOlympia]]/…) remain the
    * byte-identity **oracle** proven by `EvmProposalFoldIdentitySpec` at every activation height on both fork clocks
    * (Chesterton's Fence, L3 plan §8/§9) — production dispatch reads the fold, not the bundles.
    */
  def forBlock(header: BlockHeader, schedule: ForkSchedule): EvmConfig =
    deriveEvmConfigAt(schedule.activeAt(header.number, header.unixTimestamp))

  /** Fold an active proposal set into the resolved [[EvmConfig]] — the single ordered fold both [[forBlock]] and the
    * fold-identity oracle go through (L3 plan §5, SR DEFAULT(ETC path)).
    *
    * **Iterates [[EvmProposals.evmApplicationOrder]] filtered by set-membership, NEVER the `Set` directly.** Scala
    * `Set` iteration order is unspecified; gas-leaf selection is last-wins and two proposals can touch the same opcode
    * slot, so folding over `Set` iteration would be a latent byte-divergence (forge's P0 obligation — the load-bearing
    * correctness point of P3). The three axes fold over the one canonically-ordered proposal list:
    *   1. opcodes — additive over [[OpCodes.FrontierOpCodes]], then `denseTable`d (order-neutral: keyed by `op.code`);
    *      2. gas — the **last** `gasDelta` selection wins (chronological last-wins over the pinned order); 3. config
    *      flags — each `configDelta` applied in order.
    */
  def deriveEvmConfigAt(active: Set[ProposalId]): EvmConfig =
    val proposals: List[EvmProposals.EvmProposal] =
      EvmProposals.evmApplicationOrder.iterator.filter(active.contains).flatMap(EvmProposals.byId.get).toList
    val opcodes = proposals.foldLeft(OpCodes.FrontierOpCodes)((ops, p) => p.opcodeDelta(ops))
    val gas = proposals.foldLeft(GasCalculator.Frontier)((gc, p) => p.gasDelta.getOrElse(gc))
    // The precompile set folds in from the Frontier base (0x01–0x04, present from genesis) through each proposal's
    // configDelta; the resolved map is immutable once built (R2).
    val base =
      EvmConfig(active, gas, OpCodes.denseTable(opcodes), precompiles = PrecompiledContracts.FrontierPrecompiles)
    proposals.foldLeft(base)((cfg, p) => p.configDelta(cfg))

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

    /** EIP-3541 — reject a deployed contract whose code begins with the `0xEF` byte (London / ETC Mystique onward);
      * enforced at code-deposit time in [[EvmInterpreter.create]].
      */
    def eip3541Enabled: Boolean = c.isActive(Eip(3541))

    /** EIP-3651 — the block's `COINBASE` starts warm (Shanghai / ETC Spiral onward); seeds the top-of-call EIP-2929
      * accessed-address set.
      */
    def eip3651Enabled: Boolean = c.isActive(Eip(3651))

    /** EIP-6780 — SELFDESTRUCT only destroys a same-transaction-created contract (a *semantic*, not a new opcode). */
    def eip6780Enabled: Boolean = c.isActive(Eip(6780))

    /** EIP-1108 — alt-bn128 (`0x06`/`0x07`/`0x08`) gas repricing (Istanbul / ETC Phoenix); selects the cheaper
      * ECADD/ECMUL/ECPAIRING cost in the precompile wrappers.
      */
    def eip1108Enabled: Boolean = c.isActive(Eip(1108))

    /** EIP-2565 — ModExp (`0x05`) gas repricing (Berlin / ETC Magneto). */
    def eip2565Enabled: Boolean = c.isActive(Eip(2565))

    /** EIP-7823 — ModExp operand-length upper bound (≤ 1024 bytes / 8192 bits; ETH Osaka, ETC Olympia); enforced at
      * entry in the ModExp wrapper.
      */
    def eip7823Enabled: Boolean = c.isActive(Eip(7823))

    /** EIP-7883 — ModExp (`0x05`) gas increase (ETH Osaka, ETC Olympia); supersedes the EIP-2565 cost. */
    def eip7883Enabled: Boolean = c.isActive(Eip(7883))

    /** EIP-3860 max initcode size — twice [[EvmConfig.maxCodeSize]] when EIP-3860 is active, else `None`. */
    def maxInitCodeSize: Option[BigInt] =
      if c.eip3860Enabled then c.maxCodeSize.map(_ * 2) else None

  // -- named per-fork bundles (direct per-fork construction; the fold is P3) ------------------------------------------

  /** The maximum EVM call/create re-entry depth (EIP-150 / YP `1024`). A sub-call at a deeper depth fails as an invalid
    * call (`InvalidCall`) rather than executing — the [[EvmInterpreter.isValidCall]] guard.
    */
  val MaxCallDepth: Int = 1024

  /** EIP-170 contract code-size limit (24576) — the modern `maxCodeSize`. */
  private val CodeSizeLimit: Option[BigInt] = Some(BigInt(24576))

  /** The shared opcode/behavior EIPs a modern (post-Spiral/Shanghai) fork has active — the markers the intent getters
    * and the shared bundle bases reference (opcode EIPs + the 2929/2200/3529/3855 gas/metering markers). Includes the
    * precompile-gas markers EIP-1108 (alt-bn128 repricing) and EIP-2565 (ModExp repricing), and the behavior markers
    * EIP-3541 (reject 0xEF) + EIP-3651 (warm COINBASE), so the named bundles are self-consistent for
    * `eip1108Enabled`/`eip2565Enabled`/`eip3541Enabled`/`eip3651Enabled` when used directly, not only via the fold.
    * Both consumers (ETH Cancun+, ETC Olympia) are post-London/Mystique (EIP-3541) and post-Shanghai/Spiral (EIP-3651),
    * so both carry these; EIP-6049 (SELFDESTRUCT deprecation) is ETC-Spiral-only in the fold and is added to
    * [[EtcOlympia]] alone (the ETH fold sets do not yet include it — drift risk noted: keep in sync with the fold).
    */
  private val ModernSharedProposals: Set[ProposalId] =
    Set(7, 140, 214, 211, 1052, 1014, 145, 1344, 1884, 3855, 2929, 2200, 3529, 3860, 1108, 2565, 3541, 3651)
      .map(Eip.apply)

  /** Frontier — the genesis config (membership-empty, no modern flags; the `0x01–0x04` genesis precompiles). */
  val Frontier: EvmConfig =
    EvmConfig(
      Set.empty,
      GasCalculator.Frontier,
      OpCodes.denseTable(OpCodes.FrontierOpCodes),
      precompiles = PrecompiledContracts.FrontierPrecompiles
    )

  /** ETH Cancun — Shanghai base + EIP-1153/4844/5656/7516 + EIP-6780 semantics + EIP-3198 BASEFEE. */
  val EthCancun: EvmConfig =
    EvmConfig(
      activeProposals = ModernSharedProposals ++ Set(3198, 1153, 5656, 4844, 7516, 6780).map(Eip.apply),
      gasCalculator = GasCalculator.EthCancun,
      opCodes = OpCodes.denseTable(OpCodes.EthCancunOpCodes),
      noEmptyAccounts = true,
      exceptionalFailedCodeDeposit = true,
      chargeSelfDestructForNewAccount = true,
      maxCodeSize = CodeSizeLimit,
      precompiles = PrecompiledContracts.EthCancunPrecompiles
    )

  /** ETH Prague — Cancun + EIP-2537/7702/7623/7691 (+ CL-side EIP-6110/7251/7002/7685); no new opcode. Adds the
    * BLS12-381 (`0x0b–0x11`) precompiles.
    */
  val EthPrague: EvmConfig =
    EthCancun.copy(
      activeProposals = EthCancun.activeProposals ++ Set(2537, 7702, 7623, 7691).map(Eip.apply),
      gasCalculator = GasCalculator.EthPrague,
      opCodes = OpCodes.denseTable(OpCodes.EthPragueOpCodes),
      precompiles = PrecompiledContracts.EthPraguePrecompiles
    )

  /** ETH Osaka — Prague + CLZ (EIP-7939) + P256VERIFY (EIP-7951) + MODEXP EIP-7823/7883 + EIP-7918/7892. Adds the
    * P256VERIFY (`0x0100`) precompile.
    */
  val EthOsaka: EvmConfig =
    EthPrague.copy(
      activeProposals = EthPrague.activeProposals ++ Set(7939, 7951, 7883, 7823, 7918, 7892).map(Eip.apply),
      gasCalculator = GasCalculator.EthOsaka,
      opCodes = OpCodes.denseTable(OpCodes.EthOsakaOpCodes),
      precompiles = PrecompiledContracts.EthOsakaPrecompiles
    )

  /** ETC Olympia (ECIP-1121) — Spiral base + EIP-3198/1153/5656/7939 opcodes, EIP-6780 semantics, EIP-2537/7951
    * precompiles, EIP-7883/7823/7623 gas, EIP-7702 call/create, EIP-1559 base-fee read. **Excludes EIP-4844/7516** (no
    * blob opcodes on ETC). forge co-signs the byte values.
    */
  val EtcOlympia: EvmConfig =
    EvmConfig(
      activeProposals = ModernSharedProposals ++
        Set(3198, 1153, 5656, 7939, 6780, 2537, 7951, 7883, 7823, 7623, 7702, 1559, 6049).map(Eip.apply),
      gasCalculator = GasCalculator.EtcOlympia,
      opCodes = OpCodes.denseTable(OpCodes.EtcOlympiaOpCodes),
      noEmptyAccounts = true,
      exceptionalFailedCodeDeposit = true,
      chargeSelfDestructForNewAccount = true,
      maxCodeSize = CodeSizeLimit,
      precompiles = PrecompiledContracts.EtcOlympiaPrecompiles
    )
