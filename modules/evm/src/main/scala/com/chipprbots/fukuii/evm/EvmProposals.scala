package com.chipprbots.fukuii.evm

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.evm.ProposalId.Ecip
import com.chipprbots.fukuii.evm.ProposalId.Eip

/** The family-agnostic per-EIP/ECIP EVM feature registry — core-geth's `enableNNNN(jt)` activators (`core/vm/eips.go`)
  * realized as small **additive deltas** keyed by [[ProposalId]], the SR DEFAULT(ETC path) (L3 plan §5, §6). A fork's
  * resolved `(opcode table, GasCalculator, config flags)` bundle is *derived* by folding the active proposals
  * ([[EvmConfig.deriveEvmConfigAt]]) in a pinned chronological order, not hand-maintained as a per-fork mega-table.
  *
  * ==P3: the fold is the production path; the named bundles are the oracle==
  * P2 populated [[EvmConfig.EthCancun]]/[[EvmConfig.EthOsaka]]/[[EvmConfig.EtcOlympia]]/… by **direct per-fork
  * construction**. P3 wires the single [[EvmConfig.forBlock]] onto this registry's fold and keeps the named bundles as
  * the byte-identity **oracle** — `EvmProposalFoldIdentitySpec` proves the fold reproduces every bundle byte-for-byte
  * at every activation height on both fork clocks before the fold is trusted as production (Chesterton's Fence, L3 plan
  * §8/§9).
  *
  * ==Three delta axes, retyped to the P2 shape==
  *   - `opcodeDelta` — additive over the [[OpCodes.FrontierOpCodes]] base; the fold `denseTable`s the union (order is
  *     not consensus-visible — `denseTable` keys by `op.code`).
  *   - `gasDelta` — **a [[GasCalculator]] *selection*, not a field fold.** P2 models the per-fork gas schedule as one
  *     injected strategy object (besu `GasCalculator`), so a proposal that opens a new gas generation carries that
  *     generation's calculator; the fold takes the **last selection in [[evmApplicationOrder]]** (chronological
  *     last-wins). Network leaves are keyed by a network-exclusive proposal ([[Ecip.apply]]`(1121)` → ETC Olympia;
  *     `Eip(4844)`/`Eip(7691)`/ `Eip(7918)` → ETH Cancun/Prague/Osaka) so an ETC set never selects an ETH leaf and vice
  *     versa.
  *   - `configDelta` — the per-`EvmConfig` boolean/`maxCodeSize` flags the interpreter reads.
  */
object EvmProposals:

  /** One EVM-affecting proposal — an additive delta over the running `(opcodes, gas, config)` triple.
    *
    * `requires` records the transitive EIP set a composite ECIP references ("share the implementation, never the
    * bundle", L3 plan §5) — load-bearing for [[etcOlympiaSet]], which is literally `etcSpiralSet ++ {Ecip(1121)} ++
    * Ecip1121Olympia.requires`.
    */
  final case class EvmProposal(
      id: ProposalId,
      requires: Set[ProposalId] = Set.empty,
      opcodeDelta: List[OpCode] => List[OpCode] = identity,
      gasDelta: Option[GasCalculator] = None,
      configDelta: EvmConfig => EvmConfig = identity
  )

  private def eips(ns: Int*): Set[ProposalId] = ns.iterator.map(Eip.apply).toSet

  /** A `configDelta` that adds `ps` to the resolved config's precompile set (union — precompile addresses are disjoint
    * across proposals, and the resolved [[EvmConfig.precompiles]] map stays immutable once folded, R2).
    */
  private def addPrecompiles(
      ps: Map[Address, PrecompiledContracts.PrecompiledContract]
  ): EvmConfig => EvmConfig =
    cfg => cfg.copy(precompiles = cfg.precompiles ++ ps)

  /** EIP-170 contract code-size limit (24576) — the modern `maxCodeSize`, set at Spurious Dragon. */
  private val CodeSizeLimit: Option[BigInt] = Some(BigInt(24576))

  // -- Homestead → Spurious Dragon (gas + config, some opcode) -------------------------------------------------------

  /** EIP-2 (Homestead) — the `G_txcreate` intrinsic (Homestead gas) and, per EIP-2 item 3, a failed code deposit
    * becomes exceptional (`exceptionalFailedCodeDeposit`) rather than an empty contract.
    */
  val Eip2: EvmProposal =
    EvmProposal(
      Eip(2),
      gasDelta = Some(GasCalculator.Homestead),
      configDelta = _.copy(exceptionalFailedCodeDeposit = true)
    )

  /** EIP-7 — DELEGATECALL (Homestead). */
  val Eip7DelegateCall: EvmProposal =
    EvmProposal(Eip(7), opcodeDelta = DELEGATECALL :: _)

  /** EIP-150 — state-access repricing + the all-but-one-64th gas cap, plus charging `G_newaccount` on SELFDESTRUCT to a
    * fresh beneficiary (`chargeSelfDestructForNewAccount`).
    */
  val Eip150: EvmProposal =
    EvmProposal(
      Eip(150),
      gasDelta = Some(GasCalculator.Eip150),
      configDelta = _.copy(chargeSelfDestructForNewAccount = true)
    )

  /** EIP-160 — EXP byte-cost increase (Homestead gas → EIP-160 gas). */
  val Eip160: EvmProposal =
    EvmProposal(Eip(160), gasDelta = Some(GasCalculator.Eip160))

  /** EIP-161 — no empty accounts (`noEmptyAccounts`). */
  val Eip161NoEmptyAccounts: EvmProposal =
    EvmProposal(Eip(161), configDelta = _.copy(noEmptyAccounts = true))

  /** EIP-170 — 24576-byte contract code-size limit (`maxCodeSize`). */
  val Eip170CodeSize: EvmProposal =
    EvmProposal(Eip(170), configDelta = _.copy(maxCodeSize = CodeSizeLimit))

  // -- Byzantium / Constantinople / Istanbul opcodes ----------------------------------------------------------------

  /** EIP-140 — REVERT (Byzantium / ETC Atlantis). */
  val Eip140Revert: EvmProposal =
    EvmProposal(Eip(140), opcodeDelta = REVERT :: _)

  /** EIP-211 — RETURNDATASIZE/RETURNDATACOPY, grouped with STATICCALL (formally EIP-214) at the Byzantium boundary.
    * Attribution granularity is irrelevant to identity — `denseTable` unions by code.
    */
  val Eip211ReturnData: EvmProposal =
    EvmProposal(Eip(211), opcodeDelta = xs => STATICCALL :: RETURNDATACOPY :: RETURNDATASIZE :: xs)

  /** EIP-214 — STATICCALL (membership marker; the opcode ships under [[Eip211ReturnData]]). */
  val Eip214StaticCall: EvmProposal =
    EvmProposal(Eip(214))

  /** EIP-198 — ModExp precompile `0x05` (Byzantium / ETC Atlantis). */
  val Eip198Modexp: EvmProposal =
    EvmProposal(Eip(198), configDelta = addPrecompiles(PrecompiledContracts.Eip198Precompiles))

  /** EIP-196 — alt-bn128 ECADD `0x06` / ECMUL `0x07` precompiles (Byzantium / ETC Atlantis). */
  val Eip196Bn128: EvmProposal =
    EvmProposal(Eip(196), configDelta = addPrecompiles(PrecompiledContracts.Eip196Precompiles))

  /** EIP-197 — alt-bn128 ECPAIRING `0x08` precompile (Byzantium / ETC Atlantis). */
  val Eip197Bn128Pairing: EvmProposal =
    EvmProposal(Eip(197), configDelta = addPrecompiles(PrecompiledContracts.Eip197Precompiles))

  /** EIP-152 — BLAKE2F precompile `0x09` (Istanbul / ETC Phoenix). */
  val Eip152Blake2f: EvmProposal =
    EvmProposal(Eip(152), configDelta = addPrecompiles(PrecompiledContracts.Eip152Precompiles))

  /** EIP-1108 — alt-bn128 gas repricing (Istanbul / ETC Phoenix); the cheaper ECADD/ECMUL/ECPAIRING cost gated by
    * `eip1108Enabled`, membership-only here.
    */
  val Eip1108AltBn128Gas: EvmProposal =
    EvmProposal(Eip(1108))

  /** EIP-1052 — EXTCODEHASH (Constantinople / ETC Agharta). */
  val Eip1052ExtCodeHash: EvmProposal =
    EvmProposal(Eip(1052), opcodeDelta = EXTCODEHASH :: _)

  /** EIP-1014 — CREATE2 (Constantinople / ETC Agharta). */
  val Eip1014Create2: EvmProposal =
    EvmProposal(Eip(1014), opcodeDelta = CREATE2 :: _)

  /** EIP-145 — SHL/SHR/SAR bitwise shifts (Constantinople / ETC Agharta). */
  val Eip145Shifts: EvmProposal =
    EvmProposal(Eip(145), opcodeDelta = xs => SHL :: SHR :: SAR :: xs)

  /** EIP-1344 — CHAINID (Istanbul / ETC Phoenix). */
  val Eip1344ChainId: EvmProposal =
    EvmProposal(Eip(1344), opcodeDelta = CHAINID :: _)

  /** EIP-1884 — SELFBALANCE + repriced SLOAD/BALANCE + cheaper calldata (Istanbul / ETC Phoenix gas). */
  val Eip1884: EvmProposal =
    EvmProposal(Eip(1884), opcodeDelta = SELFBALANCE :: _, gasDelta = Some(GasCalculator.Eip1884))

  /** EIP-2028 — the calldata repricing is folded into the [[GasCalculator.Eip1884]] generation; here a membership
    * marker.
    */
  val Eip2028: EvmProposal =
    EvmProposal(Eip(2028))

  /** EIP-2200 — net-metered SSTORE (Istanbul / ETC Phoenix); a metering algorithm, membership-only here. */
  val Eip2200: EvmProposal =
    EvmProposal(Eip(2200))

  // -- Berlin / London / Mystique gas + flags -----------------------------------------------------------------------

  /** EIP-2929 — warm/cold state-access gas (the warm/cold cost lives on [[GasCalculator.Eip2929]]). */
  val Eip2929: EvmProposal =
    EvmProposal(Eip(2929), gasDelta = Some(GasCalculator.Eip2929))

  /** EIP-2930 — optional access lists (Berlin / ETC Magneto); membership-only (constants already present). */
  val Eip2930: EvmProposal =
    EvmProposal(Eip(2930))

  /** EIP-2565 — ModExp `0x05` gas repricing (Berlin / ETC Magneto); the reduced-cost model gated by `eip2565Enabled`,
    * membership-only here.
    */
  val Eip2565Modexp: EvmProposal =
    EvmProposal(Eip(2565))

  /** EIP-3198 — BASEFEE (ETH London; ETC Olympia). */
  val Eip3198BaseFee: EvmProposal =
    EvmProposal(Eip(3198), opcodeDelta = BASEFEE :: _)

  /** EIP-3529 — reduced refunds + EIP-3860 initcode-word value ([[GasCalculator.Eip3529]]). */
  val Eip3529Refund: EvmProposal =
    EvmProposal(Eip(3529), gasDelta = Some(GasCalculator.Eip3529))

  /** EIP-3541 — reject new code beginning with the 0xEF byte (membership; enforced in create). */
  val Eip3541RejectEF: EvmProposal =
    EvmProposal(Eip(3541))

  /** EIP-3860 — initcode word metering + size limit (membership → `eip3860Enabled`). Selects the
    * [[GasCalculator.Eip3860]] gas leaf (`G_initcode_word = 2`), which the fold applies at ETH Shanghai / ETC Spiral —
    * one fork later than EIP-3529 (go-ethereum `newShanghaiInstructionSet`; core-geth `EIP3860FBlock` 19_250_000).
    */
  val Eip3860: EvmProposal =
    EvmProposal(Eip(3860), gasDelta = Some(GasCalculator.Eip3860))

  /** EIP-3855 — PUSH0 (ETC Spiral; ETH Shanghai). */
  val Eip3855Push0: EvmProposal =
    EvmProposal(Eip(3855), opcodeDelta = PUSH0 :: _)

  /** EIP-3651 — warm COINBASE (ETC Spiral; ETH Shanghai); membership-only. */
  val Eip3651WarmCoinbase: EvmProposal =
    EvmProposal(Eip(3651))

  /** EIP-6049 — deprecate SELFDESTRUCT (warning-only, ETC Spiral); membership-only. */
  val Eip6049Deprecation: EvmProposal =
    EvmProposal(Eip(6049))

  /** EIP-1559 — base-fee context (L3 owns the header read half; the economics are L4/ECIP-1111); membership-only at L3.
    */
  val Eip1559BaseFee: EvmProposal =
    EvmProposal(Eip(1559))

  // -- Cancun / Olympia shared opcodes + semantics ------------------------------------------------------------------

  /** EIP-4844 — BLOBHASH (ETH Cancun; **ETH-only**), the ETH Cancun gas leaf selector, and the KZG point-evaluation
    * precompile `0x0a`. **ETH-only** — ETC never activates EIP-4844, so `0x0a` never enters an ETC precompile set.
    */
  val Eip4844BlobHash: EvmProposal =
    EvmProposal(
      Eip(4844),
      opcodeDelta = BLOBHASH :: _,
      gasDelta = Some(GasCalculator.EthCancun),
      configDelta = addPrecompiles(PrecompiledContracts.Eip4844Precompiles)
    )

  /** EIP-7516 — BLOBBASEFEE (ETH Cancun; **ETH-only**). */
  val Eip7516BlobBaseFee: EvmProposal =
    EvmProposal(Eip(7516), opcodeDelta = BLOBBASEFEE :: _)

  /** EIP-1153 — TLOAD/TSTORE transient storage (ETH Cancun; ETC Olympia). */
  val Eip1153Transient: EvmProposal =
    EvmProposal(Eip(1153), opcodeDelta = xs => TLOAD :: TSTORE :: xs)

  /** EIP-5656 — MCOPY (ETH Cancun; ETC Olympia). */
  val Eip5656Mcopy: EvmProposal =
    EvmProposal(Eip(5656), opcodeDelta = MCOPY :: _)

  /** EIP-6780 — SELFDESTRUCT only for a same-tx-created contract (membership → `eip6780Enabled`). */
  val Eip6780SelfdestructSameTx: EvmProposal =
    EvmProposal(Eip(6780))

  // -- Prague / Osaka (ETH) + Olympia precompile/gas EIPs (membership at L3; wrappers land in P5) --------------------

  /** EIP-7939 — CLZ count-leading-zeros (ETH Osaka; ETC Olympia). */
  val Eip7939Clz: EvmProposal =
    EvmProposal(Eip(7939), opcodeDelta = CLZ :: _)

  /** EIP-2537 — BLS12-381 precompiles `0x0b–0x11` (seven; ETH Prague, ETC Olympia). */
  val Eip2537Bls: EvmProposal =
    EvmProposal(Eip(2537), configDelta = addPrecompiles(PrecompiledContracts.BlsPrecompiles))

  /** EIP-7702 — set-code (Type-4) transaction call/create path (Prague; ETC Olympia); membership at L3. */
  val Eip7702SetCode: EvmProposal =
    EvmProposal(Eip(7702))

  /** EIP-7623 — calldata floor gas (Prague; ETC Olympia); an L4 block-level `max(...)`, membership at L3. */
  val Eip7623CalldataFloor: EvmProposal =
    EvmProposal(Eip(7623))

  /** EIP-7691 — blob throughput increase (**ETH-only** Prague), and the ETH Prague gas leaf selector. */
  val Eip7691BlobThroughput: EvmProposal =
    EvmProposal(Eip(7691), gasDelta = Some(GasCalculator.EthPrague))

  /** EIP-7951 — P256VERIFY precompile `0x0100` (ETH Osaka, ETC Olympia — the dual-activation asymmetry vs `0x0a` KZG,
    * which ETC excludes).
    */
  val Eip7951P256: EvmProposal =
    EvmProposal(Eip(7951), configDelta = addPrecompiles(PrecompiledContracts.P256Precompiles))

  /** EIP-7883 — MODEXP gas increase (Osaka; ETC Olympia); enforced in the P5 MODEXP wrapper. */
  val Eip7883Modexp: EvmProposal =
    EvmProposal(Eip(7883))

  /** EIP-7823 — MODEXP input bounds (Osaka; ETC Olympia); enforced in the P5 MODEXP wrapper. */
  val Eip7823ModexpBounds: EvmProposal =
    EvmProposal(Eip(7823))

  /** EIP-7918 — blob base-fee reserve pricing (**ETH-only** Osaka), and the ETH Osaka gas leaf selector. */
  val Eip7918BlobReserve: EvmProposal =
    EvmProposal(Eip(7918), gasDelta = Some(GasCalculator.EthOsaka))

  /** EIP-7892 — blob-parameter-only (BPO) forks (**ETH-only** Osaka); membership-only. */
  val Eip7892Bpo: EvmProposal =
    EvmProposal(Eip(7892))

  /** EIP-7825 — per-transaction gas cap `2^24` (**ETH-only** Osaka; **NOT an ETC EIP**, forge co-signed the exclusion).
    * A pure L4 tx-validation reject (`gasLimit > 2^24` ⇒ `GasLimitAboveCap`), so it carries **no opcode/gas/config
    * delta** at L3 — a membership marker exactly like [[Eip7623CalldataFloor]]. go-ethereum gates it at Osaka until the
    * future Amsterdam relaxes it (`core/state_transition.go:564`, `!rules.IsAmsterdam && rules.IsOsaka && msg.GasLimit
    * > params.MaxTxGas`); the cap value is `params.MaxTxGas = 1<<24` (`params/protocol_params.go:31`), byte-confirmed
    * against besu `EIP_7825_TRANSACTION_GAS_LIMIT_CAP = 16_777_216L`. fukuii has no Amsterdam fork, so `isActive(Eip(
    * 7825))` at Osaka is the correct dispatch for the L4 [[TransactionProcessor]] cap.
    */
  val Eip7825TxGasCap: EvmProposal =
    EvmProposal(Eip(7825))

  /** ECIP-1121 — ETC's Olympia EVM bundle. It contributes **no direct opcode delta of its own** — its effect is (a)
    * selecting the [[GasCalculator.EtcOlympia]] gas leaf and (b) its `requires` closure, the shared EIP implementations
    * it composes. The reconciled set (L3 forge impact §2/§2g, forge co-signed): the four opcodes originally modeled
    * (7939/3198/1153/5656) **plus** the six under-counted EIPs — EIP-6780 semantics, EIP-2537/7951 precompiles,
    * EIP-7883/7823/7623 gas, EIP-7702 set-code, and the EIP-1559 base-fee read. **Excludes EIP-4844/7516** (no blobs on
    * ETC). Precompile wrappers land in P5.
    */
  val Ecip1121Olympia: EvmProposal =
    EvmProposal(
      Ecip(1121),
      requires = eips(3198, 1153, 5656, 7939, 6780, 2537, 7951, 7883, 7823, 7623, 7702, 1559),
      gasDelta = Some(GasCalculator.EtcOlympia)
    )

  /** Canonical application order (fork-chronological). The fold folds proposals in **this order filtered by
    * set-membership**, never by `Set` iteration (unspecified order) — the load-bearing determinism point: two proposals
    * can select gas leaves / touch the same opcode slot, so last-wins requires a pinned sequence. Network-exclusive gas
    * selectors are ordered `Eip(4844) < Eip(7691) < Eip(7918)` (ETH Cancun < Prague < Osaka) and `Ecip(1121)` last, so
    * each ladder resolves its own final leaf.
    */
  val evmApplicationOrder: List[ProposalId] = List(
    Eip(2),
    Eip(7),
    Eip(150),
    Eip(160),
    Eip(161),
    Eip(170),
    Eip(140),
    Eip(211),
    Eip(214),
    Eip(198),
    Eip(196),
    Eip(197),
    Eip(1052),
    Eip(1014),
    Eip(145),
    Eip(152),
    Eip(1108),
    Eip(1344),
    Eip(1884),
    Eip(2028),
    Eip(2200),
    Eip(2929),
    Eip(2930),
    Eip(2565),
    Eip(3198),
    Eip(3529),
    Eip(3541),
    Eip(3860),
    Eip(3855),
    Eip(3651),
    Eip(6049),
    Eip(1559),
    Eip(4844),
    Eip(7516),
    Eip(1153),
    Eip(5656),
    Eip(6780),
    Eip(7939),
    Eip(2537),
    Eip(7702),
    Eip(7623),
    Eip(7691),
    Eip(7951),
    Eip(7883),
    Eip(7823),
    Eip(7825),
    Eip(7918),
    Eip(7892),
    Ecip(1121)
  )

  /** Every registered proposal, keyed by id. `keySet == evmApplicationOrder.toSet` (asserted in the fold-identity
    * spec).
    */
  val byId: Map[ProposalId, EvmProposal] = List(
    Eip2,
    Eip7DelegateCall,
    Eip150,
    Eip160,
    Eip161NoEmptyAccounts,
    Eip170CodeSize,
    Eip140Revert,
    Eip211ReturnData,
    Eip214StaticCall,
    Eip198Modexp,
    Eip196Bn128,
    Eip197Bn128Pairing,
    Eip1052ExtCodeHash,
    Eip1014Create2,
    Eip145Shifts,
    Eip152Blake2f,
    Eip1108AltBn128Gas,
    Eip1344ChainId,
    Eip1884,
    Eip2028,
    Eip2200,
    Eip2929,
    Eip2930,
    Eip2565Modexp,
    Eip3198BaseFee,
    Eip3529Refund,
    Eip3541RejectEF,
    Eip3860,
    Eip3855Push0,
    Eip3651WarmCoinbase,
    Eip6049Deprecation,
    Eip1559BaseFee,
    Eip4844BlobHash,
    Eip7516BlobBaseFee,
    Eip1153Transient,
    Eip5656Mcopy,
    Eip6780SelfdestructSameTx,
    Eip7939Clz,
    Eip2537Bls,
    Eip7702SetCode,
    Eip7623CalldataFloor,
    Eip7691BlobThroughput,
    Eip7951P256,
    Eip7883Modexp,
    Eip7823ModexpBounds,
    Eip7825TxGasCap,
    Eip7918BlobReserve,
    Eip7892Bpo,
    Ecip1121Olympia
  ).map(p => p.id -> p).toMap

  // -- Reconciled fork-membership sets (the fold input per fork; the identity oracle keys off these) ------------------
  // These declare, per fork, the cumulative active proposal set. In production the set comes from
  // `ForkSchedule.activeAt` (the L5-supplied schedule); these named vals are the byte-identity fixtures
  // and the human-readable record of "what each fork activates" (forge co-signs the ETC/Olympia ladder).

  val frontierSet: Set[ProposalId] = Set.empty
  val homesteadSet: Set[ProposalId] = frontierSet ++ eips(2, 7)
  val eip150Set: Set[ProposalId] = homesteadSet + Eip(150)
  val eip160Set: Set[ProposalId] = eip150Set + Eip(160)
  val spuriousDragonSet: Set[ProposalId] = eip160Set ++ eips(161, 170)
  // Byzantium/Atlantis add the ModExp (198) + alt-bn128 (196/197) precompiles alongside REVERT/returndata/staticcall.
  val byzantiumSet: Set[ProposalId] = spuriousDragonSet ++ eips(140, 211, 214, 198, 196, 197)
  val constantinopleSet: Set[ProposalId] = byzantiumSet ++ eips(1052, 1014, 145)
  // Istanbul/Phoenix add BLAKE2F (152) + the alt-bn128 gas repricing (1108).
  val istanbulSet: Set[ProposalId] = constantinopleSet ++ eips(1344, 1884, 2028, 2200, 152, 1108)
  // Berlin/Magneto add the ModExp EIP-2565 gas repricing (2565) alongside 2929/2930.
  val berlinSet: Set[ProposalId] = istanbulSet ++ eips(2929, 2930, 2565)

  // ETH branch: London adds BASEFEE + reduced-refund gas + EIP-1559 (NOT EIP-3860 — go-ethereum activates initcode
  // metering at Shanghai: `newLondonInstructionSet` enable3529/enable3198 only, `newShanghaiInstructionSet` adds
  // enable3860, core/vm/jump_table.go:136-162). Shanghai adds PUSH0 + warm-COINBASE + EIP-3860; Cancun adds the
  // blob/transient/mcopy opcodes; Prague/Osaka add precompile/gas/tx EIPs (opcode: only CLZ at Osaka).
  val ethLondonSet: Set[ProposalId] = berlinSet ++ eips(3198, 3529, 3541, 1559)
  val ethShanghaiSet: Set[ProposalId] = ethLondonSet ++ eips(3855, 3651, 3860)
  val ethCancunSet: Set[ProposalId] = ethShanghaiSet ++ eips(4844, 7516, 1153, 5656, 6780)
  val ethPragueSet: Set[ProposalId] = ethCancunSet ++ eips(2537, 7702, 7623, 7691)
  // EIP-7825 (per-tx gas cap 2^24) is an Osaka tx-validation membership marker — no opcode/gas/config delta, so it is
  // byte-neutral to the fold; its only effect is `isActive(Eip(7825)) == true`, which arms the L4 TransactionProcessor
  // cap. ETH-only — never added to any ETC set (go-ethereum `state_transition.go:564` gates it on `rules.IsOsaka`).
  val ethOsakaSet: Set[ProposalId] = ethPragueSet ++ eips(7939, 7951, 7883, 7823, 7825, 7918, 7892)

  // ETC branch: Mystique adds reduced-refund gas + EIP-3541 WITHOUT EIP-1559 and WITHOUT EIP-3860 (core-geth
  // config_classic.go: EIP3529FBlock/EIP3541FBlock = 14_525_000 Mystique, but EIP3860FBlock = 19_250_000 Spiral,
  // lines 101-108). Spiral adds PUSH0 + warm-COINBASE + EIP-3860 + SELFDESTRUCT-deprecation (no BASEFEE — that
  // arrives at Olympia). Olympia = Spiral + Ecip(1121) + its closure.
  val etcMystiqueSet: Set[ProposalId] = berlinSet ++ eips(3529, 3541)
  val etcSpiralSet: Set[ProposalId] = etcMystiqueSet ++ eips(3855, 3651, 3860, 6049)
  val etcOlympiaSet: Set[ProposalId] = etcSpiralSet ++ Set(Ecip(1121)) ++ Ecip1121Olympia.requires
