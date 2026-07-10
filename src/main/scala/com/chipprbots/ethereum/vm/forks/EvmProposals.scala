package com.chipprbots.ethereum.vm.forks

import com.chipprbots.ethereum.forks.Proposal
import com.chipprbots.ethereum.forks.ProposalId
import com.chipprbots.ethereum.forks.ProposalId.*
import com.chipprbots.ethereum.forks.ProposalLayer
import com.chipprbots.ethereum.vm.*

/** L1a — the per-EIP/ECIP EVM feature registry (Batch 5 framework §1.2, Row 5.2).
  *
  * Mirrors core-geth's `core/vm/eips.go` `enableNNNN(jt)` activators: one entry per EVM-affecting proposal, each a
  * small **additive delta** over the base (Frontier) opcode set / fee schedule, keyed by its ecosystem [[ProposalId]]
  * and family-agnostic. A fork's opcode set and fee schedule are then *derived* by folding the active proposals (see
  * [[deriveEvm]]) rather than hand-maintained as a monolithic per-fork bundle.
  *
  * ==What this row is (and is NOT)==
  * This row is **additive and provably-equivalent only**. It introduces the registry in *parallel* to the existing
  * `Eth*`/`Etc*` opcode/fee bundles and `EvmConfig.forBlock` dispatch — it does NOT switch `forBlock` onto the fold.
  * `EvmProposalDerivationSpec` proves that folding the registry reproduces every current bundle byte-for-byte (opcode
  * `.toSet` + fee field-tuple), for the full pre-London chain on both networks. Wiring the fold into production
  * `forBlock` (and deleting the bundles) is Row 5.3.
  *
  * ==Finding 3 — `configDelta` reconciliation (document, do not create a third mechanism)==
  * `BlockchainConfigForEvm` already carries 13 `isEipNNNNEnabled` predicates, and `EvmConfig.forBlock` gates the EVM
  * boolean flags (`eip3541Enabled`, `eip3651Enabled`, `eip3860Enabled`, `eip6780Enabled`, `eip6049DeprecationEnabled`,
  * `noEmptyAccounts`, …) via hardcoded per-`*ConfigBuilder` `.copy` sets. To avoid standing up a THIRD parallel
  * enablement path this row, `EvmProposal.configDelta` is present in the *shape* (so 5.3 can populate it) but is left
  * `identity` for every proposal here and is NOT wired into `forBlock` and NOT asserted against production. The
  * mechanism-unification decision — fold the config flags through the registry vs. keep the predicates — is deferred to
  * Row 5.3 when the fold goes live. The derived==bundle proof this row covers the two state-visible EVM lineages only:
  * the opcode set and the fee schedule.
  */
// scalastyle:off magic.number
object EvmProposals:

  /** A concrete-value [[FeeSchedule]] the fee fold can `.copy` field-by-field. The existing `FeeSchedule` subclasses
    * express deltas by inheritance/override, which is not composable as a function; this case class is the
    * function-friendly carrier the fold threads. [[from]] projects any existing `FeeSchedule` into it, so the fold base
    * is `FrontierFeeSchedule` read through `from` (no re-declared magic numbers for the base).
    *
    * Fields are in the exact declaration order of the `FeeSchedule` trait.
    */
  final case class FeeScheduleValues(
      G_zero: BigInt,
      G_base: BigInt,
      G_verylow: BigInt,
      G_low: BigInt,
      G_mid: BigInt,
      G_high: BigInt,
      G_balance: BigInt,
      G_sload: BigInt,
      G_jumpdest: BigInt,
      G_sset: BigInt,
      G_sreset: BigInt,
      R_sclear: BigInt,
      R_selfdestruct: BigInt,
      G_selfdestruct: BigInt,
      G_create: BigInt,
      G_codedeposit: BigInt,
      G_call: BigInt,
      G_callvalue: BigInt,
      G_callstipend: BigInt,
      G_newaccount: BigInt,
      G_exp: BigInt,
      G_expbyte: BigInt,
      G_memory: BigInt,
      G_txcreate: BigInt,
      G_txdatazero: BigInt,
      G_txdatanonzero: BigInt,
      G_transaction: BigInt,
      G_log: BigInt,
      G_logdata: BigInt,
      G_logtopic: BigInt,
      G_sha3: BigInt,
      G_sha3word: BigInt,
      G_copy: BigInt,
      G_blockhash: BigInt,
      G_extcode: BigInt,
      G_cold_sload: BigInt,
      G_cold_account_access: BigInt,
      G_warm_storage_read: BigInt,
      G_access_list_address: BigInt,
      G_access_list_storage: BigInt,
      G_initcode_word: BigInt
  ) extends FeeSchedule

  object FeeScheduleValues:
    def from(fs: FeeSchedule): FeeScheduleValues = FeeScheduleValues(
      fs.G_zero,
      fs.G_base,
      fs.G_verylow,
      fs.G_low,
      fs.G_mid,
      fs.G_high,
      fs.G_balance,
      fs.G_sload,
      fs.G_jumpdest,
      fs.G_sset,
      fs.G_sreset,
      fs.R_sclear,
      fs.R_selfdestruct,
      fs.G_selfdestruct,
      fs.G_create,
      fs.G_codedeposit,
      fs.G_call,
      fs.G_callvalue,
      fs.G_callstipend,
      fs.G_newaccount,
      fs.G_exp,
      fs.G_expbyte,
      fs.G_memory,
      fs.G_txcreate,
      fs.G_txdatazero,
      fs.G_txdatanonzero,
      fs.G_transaction,
      fs.G_log,
      fs.G_logdata,
      fs.G_logtopic,
      fs.G_sha3,
      fs.G_sha3word,
      fs.G_copy,
      fs.G_blockhash,
      fs.G_extcode,
      fs.G_cold_sload,
      fs.G_cold_account_access,
      fs.G_warm_storage_read,
      fs.G_access_list_address,
      fs.G_access_list_storage,
      fs.G_initcode_word
    )

  /** One EVM-affecting proposal — an additive delta over the running (opcode set, fee schedule, config).
    *
    * `opcodeDelta`/`feeDelta` are proven equivalent to the bundles this row. `configDelta` is the deferred seam
    * (Finding 3 above): present in the shape, `identity` for every entry here, not wired into `forBlock`.
    */
  final case class EvmProposal(
      id: ProposalId,
      override val layer: ProposalLayer = ProposalLayer.Consensus,
      override val requires: Set[ProposalId] = Set.empty,
      opcodeDelta: List[OpCode] => List[OpCode] = identity,
      feeDelta: FeeScheduleValues => FeeScheduleValues = identity,
      configDelta: EvmConfig => EvmConfig = identity
  ) extends Proposal

  // ---- Opcode-delta proposals (additive over the pre-London chain; compared by `.toSet`) ----------------------------
  // The pre-London chain (Finding 1): the registry must supply the WHOLE opcode lineage ETH inherits transitively,
  // not just the London-onward deltas — otherwise ETH's derived pre-London sets are under-populated.

  /** EIP-7 — DELEGATECALL (Homestead). */
  val Eip7DelegateCall: EvmProposal =
    EvmProposal(Eip(7), opcodeDelta = DELEGATECALL :: _)

  /** EIP-140 — REVERT (Byzantium). */
  val Eip140Revert: EvmProposal =
    EvmProposal(Eip(140), opcodeDelta = REVERT :: _)

  /** EIP-211 — RETURNDATASIZE/RETURNDATACOPY, and STATICCALL (Byzantium). STATICCALL is formally EIP-214; it is grouped
    * with the EIP-211 return-data opcodes here per the Byzantium fork boundary (scout Finding 1/2 authoritative delta
    * list). The derived==bundle proof is unaffected — the union reproduces `ByzantiumOpCodes` regardless of attribution
    * granularity.
    */
  val Eip211ReturnData: EvmProposal =
    EvmProposal(Eip(211), opcodeDelta = xs => STATICCALL :: RETURNDATACOPY :: RETURNDATASIZE :: xs)

  /** EIP-1052 — EXTCODEHASH (Constantinople). */
  val Eip1052ExtCodeHash: EvmProposal =
    EvmProposal(Eip(1052), opcodeDelta = EXTCODEHASH :: _)

  /** EIP-1014 — CREATE2 (Constantinople). */
  val Eip1014Create2: EvmProposal =
    EvmProposal(Eip(1014), opcodeDelta = CREATE2 :: _)

  /** EIP-145 — SHL/SHR/SAR bitwise shifts (Constantinople). */
  val Eip145Shifts: EvmProposal =
    EvmProposal(Eip(145), opcodeDelta = xs => SHL :: SHR :: SAR :: xs)

  /** EIP-1344 — CHAINID (Phoenix/Istanbul). */
  val Eip1344ChainId: EvmProposal =
    EvmProposal(Eip(1344), opcodeDelta = CHAINID :: _)

  /** EIP-1884 — SELFBALANCE opcode + repricing G_sload (200→800) and G_balance (400→700) (Phoenix/Istanbul). */
  val Eip1884: EvmProposal =
    EvmProposal(
      Eip(1884),
      opcodeDelta = SELFBALANCE :: _,
      feeDelta = _.copy(G_sload = 800, G_balance = 700)
    )

  /** EIP-3198 — BASEFEE (ETH London; ETC Olympia). */
  val Eip3198BaseFee: EvmProposal =
    EvmProposal(Eip(3198), opcodeDelta = BASEFEE :: _)

  /** EIP-3855 — PUSH0 (ETC Spiral; ETH Shanghai). */
  val Eip3855Push0: EvmProposal =
    EvmProposal(Eip(3855), opcodeDelta = PUSH0 :: _)

  /** EIP-4844 — BLOBHASH (ETH Cancun; ETH-only). */
  val Eip4844BlobHash: EvmProposal =
    EvmProposal(Eip(4844), opcodeDelta = BLOBHASH :: _)

  /** EIP-7516 — BLOBBASEFEE (ETH Cancun; ETH-only). */
  val Eip7516BlobBaseFee: EvmProposal =
    EvmProposal(Eip(7516), opcodeDelta = BLOBBASEFEE :: _)

  /** EIP-1153 — TLOAD/TSTORE transient storage (ETH Cancun; ETC Olympia). */
  val Eip1153Transient: EvmProposal =
    EvmProposal(Eip(1153), opcodeDelta = xs => TLOAD :: TSTORE :: xs)

  /** EIP-5656 — MCOPY (ETH Cancun; ETC Olympia). */
  val Eip5656Mcopy: EvmProposal =
    EvmProposal(Eip(5656), opcodeDelta = MCOPY :: _)

  /** EIP-7939 — CLZ count-leading-zeros (ETH Osaka; ETC Olympia). */
  val Eip7939Clz: EvmProposal =
    EvmProposal(Eip(7939), opcodeDelta = CLZ :: _)

  // ---- Fee-delta proposals (additive over the FrontierFeeSchedule base; compared by field-tuple) --------------------
  // Order-sensitivity (framework §2.3 composition-order footgun): G_sload is set by EIP-150 (200) → EIP-1884 (800) →
  // EIP-2929 (100), and G_balance by EIP-150 (400) → EIP-1884 (700). `evmApplicationOrder` MUST be fork-chronological
  // so the last write wins correctly. `EvmProposalDerivationSpec` proves this by field-tuple equality per fork.

  /** EIP-2 (Homestead gas repricing) — raise contract-creation-by-tx gas (G_txcreate 0→32000). */
  val Eip2TxCreate: EvmProposal =
    EvmProposal(Eip(2), feeDelta = _.copy(G_txcreate = 32000))

  /** EIP-150 — repricing state-access ops (G_sload 200, G_call 700, G_balance 400, G_selfdestruct 5000, G_extcode 700).
    */
  val Eip150: EvmProposal =
    EvmProposal(
      Eip(150),
      feeDelta = _.copy(G_sload = 200, G_call = 700, G_balance = 400, G_selfdestruct = 5000, G_extcode = 700)
    )

  /** EIP-160 — EXP repricing (G_expbyte 10→50). */
  val Eip160: EvmProposal =
    EvmProposal(Eip(160), feeDelta = _.copy(G_expbyte = 50))

  /** EIP-2028 — cheaper calldata (G_txdatanonzero 68→16) (Phoenix/Istanbul). */
  val Eip2028: EvmProposal =
    EvmProposal(Eip(2028), feeDelta = _.copy(G_txdatanonzero = 16))

  /** EIP-2929 — gas cost increases for state access; warm/cold model (G_sload → warm 100, G_sreset → 2900)
    * (Magneto/Berlin).
    */
  val Eip2929: EvmProposal =
    EvmProposal(Eip(2929), feeDelta = _.copy(G_sload = 100, G_sreset = 2900))

  /** EIP-2930 — optional access lists (Magneto/Berlin). The access-list gas constants (G_access_list_address=2400,
    * G_access_list_storage=1900) are already present in `FrontierFeeSchedule` and are merely brought into USE here — so
    * this proposal carries no fee-*field* delta. Registered for completeness (it is a fee-era proposal per scout
    * Finding 2) with `identity` deltas.
    */
  val Eip2930: EvmProposal =
    EvmProposal(Eip(2930))

  /** EIP-3529 — reduced refunds (R_sclear → 4800, R_selfdestruct → 0) (Mystique; ETH London). */
  val Eip3529Refund: EvmProposal =
    EvmProposal(Eip(3529), feeDelta = _.copy(R_sclear = 4800, R_selfdestruct = 0))

  /** EIP-3860 — initcode word cost (G_initcode_word 0→2) (Mystique/Spiral; ETH London/Shanghai).
    *
    * Note the field-vs-enablement split mirrored from the bundles: `MystiqueFeeSchedule`/`EthLondonFeeSchedule` set
    * `G_initcode_word = 2` at their boundary even though initcode *metering* (`eip3860Enabled`) only flips on at ETC
    * Spiral / ETH Shanghai. This proposal carries the field value (so the derived fee tuple is byte-identical to the
    * bundle at Mystique/London onward); the enablement flag is the deferred `configDelta` (Finding 3), not populated
    * this row.
    */
  val Eip3860InitCode: EvmProposal =
    EvmProposal(Eip(3860), feeDelta = _.copy(G_initcode_word = 2))

  // ---- ECIP composition marker ------------------------------------------------------------------------------------
  /** ECIP-1121 — ETC's Olympia EVM bundle. It contributes NO direct EVM delta of its own; its entire effect is its
    * `requires` set — the shared EIP implementations it composes (CLZ + BASEFEE + transient storage + MCOPY). This is
    * "share the implementation, never the bundle" (framework §1.2) made concrete: ETC Olympia references the same
    * `Eip(7939)/Eip(3198)/Eip(1153)/Eip(5656)` impls ETH uses, rather than a copied opcode list. `requires` is not yet
    * auto-expanded (that is the 5.3 `ForkSchedule` builder) — the EtcOlympia derivation fixture lists the transitive
    * closure explicitly.
    */
  val Ecip1121Olympia: EvmProposal =
    EvmProposal(
      Ecip(1121),
      requires = Set(Eip(7939), Eip(3198), Eip(1153), Eip(5656))
    )

  /** Canonical application order (framework §2.3). Fork-chronological, so order-sensitive fee fields (G_sload,
    * G_balance) resolve last-write-wins correctly. Opcode order is not consensus-visible (`byteToOpCode` is keyed by
    * `op.code`) but is pinned here so the regression diff stays clean.
    */
  val evmApplicationOrder: List[ProposalId] = List(
    Eip(2),
    Eip(7),
    Eip(150),
    Eip(160),
    Eip(140),
    Eip(211),
    Eip(1052),
    Eip(1014),
    Eip(145),
    Eip(1344),
    Eip(1884),
    Eip(2028),
    Eip(2929),
    Eip(2930),
    Eip(3198),
    Eip(3529),
    Eip(3860),
    Eip(3855),
    Eip(4844),
    Eip(7516),
    Eip(1153),
    Eip(5656),
    Eip(7939),
    Ecip(1121)
  )

  /** Every registered proposal, keyed by id. `keySet == evmApplicationOrder.toSet` (asserted in the derivation spec).
    */
  val byId: Map[ProposalId, EvmProposal] = List(
    Eip2TxCreate,
    Eip7DelegateCall,
    Eip150,
    Eip160,
    Eip140Revert,
    Eip211ReturnData,
    Eip1052ExtCodeHash,
    Eip1014Create2,
    Eip145Shifts,
    Eip1344ChainId,
    Eip1884,
    Eip2028,
    Eip2929,
    Eip2930,
    Eip3198BaseFee,
    Eip3529Refund,
    Eip3860InitCode,
    Eip3855Push0,
    Eip4844BlobHash,
    Eip7516BlobBaseFee,
    Eip1153Transient,
    Eip5656Mcopy,
    Eip7939Clz,
    Ecip1121Olympia
  ).map(p => p.id -> p).toMap

  /** Derive a fork's (opcode set, fee schedule) by folding the active proposals over the Frontier base, in
    * `evmApplicationOrder`. This is the fold the design's `EvmConfig.forBlock` adopts in Row 5.3; here it is the engine
    * of the derived==bundle proof only (not yet wired into production dispatch).
    *
    * The base is `FrontierOpCodes` + `FrontierFeeSchedule` (genesis), read through `FeeScheduleValues.from` so no base
    * value is re-declared.
    */
  def deriveEvm(active: Set[ProposalId]): (List[OpCode], FeeScheduleValues) =
    val baseOpCodes = OpCodes.FrontierOpCodes
    val baseFee = FeeScheduleValues.from(new FeeSchedule.FrontierFeeSchedule)
    evmApplicationOrder
      .filter(active.contains)
      .flatMap(byId.get)
      .foldLeft((baseOpCodes, baseFee)) { case ((ops, fee), p) =>
        (p.opcodeDelta(ops), p.feeDelta(fee))
      }
// scalastyle:on magic.number
