package com.chipprbots.ethereum.vm.forks

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.forks.ProposalId
import com.chipprbots.ethereum.forks.ProposalId.*
import com.chipprbots.ethereum.forks.ProposalLayer
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.*

/** Batch 5 Row 5.2 acceptance proof: the per-EIP/ECIP [[EvmProposals]] registry, folded over the active proposal set
  * for each fork, reproduces every current opcode/fee bundle **byte-for-byte** — opcode `.toSet` AND fee-schedule
  * field-tuple — for the FULL pre-London chain on both networks (ETC block lineage + ETH London→Osaka lineage).
  *
  * This is the "derived == bundle" harness. It proves the registry is a provably-equivalent PARALLEL to the existing
  * `Eth*`/`Etc*` bundles (which remain the production source of truth until Row 5.3 switches `forBlock` onto the fold).
  * Under-registering the pre-London chain (scout Finding 1) fails loudly here: a missing DELEGATECALL/REVERT/…​ delta
  * makes a derived opcode set diverge from its bundle.
  */
class EvmProposalDerivationSpec extends AnyFlatSpec with Matchers:

  import FeeSchedule.*

  /** The 42 declared FeeSchedule fields in declaration order — mirrors `EvmConfigDealiasByteIdentitySpec.fields` (the
    * Row 5.1 byte-identity helper). Two schedules are field-identical iff their `fields` sequences are equal.
    */
  private def fields(fs: FeeSchedule): Seq[BigInt] = Seq(
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

  private case class ForkCase(name: String, active: Set[ProposalId], opcodes: List[OpCode], fee: FeeSchedule)

  // ---- ETC / shared block-based lineage (Frontier … Olympia) --------------------------------------------------------
  // Cumulative active-proposal sets. The pre-London opcode chain (EIP-7/140/211/1052/1014/145/1344/1884) is the
  // shared base ETH inherits transitively (Finding 1) — registered here, proven for both networks.

  private val frontier       = Set.empty[ProposalId]
  private val homestead      = frontier ++ Set(Eip(2), Eip(7))
  private val postEip150      = homestead + Eip(150)
  private val postEip160      = postEip150 + Eip(160)
  private val byzantium      = postEip160 ++ Set(Eip(140), Eip(211))
  private val constantinople = byzantium ++ Set(Eip(1052), Eip(1014), Eip(145))
  private val phoenix        = constantinople ++ Set(Eip(1344), Eip(1884), Eip(2028))
  private val magneto        = phoenix ++ Set(Eip(2929), Eip(2930))
  private val mystique       = magneto ++ Set(Eip(3529), Eip(3860))
  private val spiral         = mystique + Eip(3855)
  private val etcOlympia     = spiral ++ Set(Eip(3198), Eip(1153), Eip(5656), Eip(7939), Ecip(1121))

  private val etcCases: List[ForkCase] = List(
    ForkCase("Frontier", frontier, OpCodes.FrontierOpCodes, new FrontierFeeSchedule),
    ForkCase("Homestead", homestead, OpCodes.HomesteadOpCodes, new HomesteadFeeSchedule),
    ForkCase("PostEIP150", postEip150, OpCodes.HomesteadOpCodes, new PostEIP150FeeSchedule),
    ForkCase("PostEIP160", postEip160, OpCodes.HomesteadOpCodes, new PostEIP160FeeSchedule),
    ForkCase("Byzantium", byzantium, OpCodes.ByzantiumOpCodes, new ByzantiumFeeSchedule),
    ForkCase("Constantinople", constantinople, OpCodes.ConstantinopleOpCodes, new ConstantionopleFeeSchedule),
    ForkCase("Phoenix", phoenix, OpCodes.PhoenixOpCodes, new PhoenixFeeSchedule),
    // Magneto/Berlin: opcode set unchanged from Phoenix (MagnetoOpCodes = PhoenixOpCodes); fee = MagnetoFeeSchedule.
    ForkCase("Magneto", magneto, OpCodes.PhoenixOpCodes, new MagnetoFeeSchedule),
    // Mystique: opcode set still Phoenix; fee adds EIP-3529 refund + EIP-3860 initcode-word field.
    ForkCase("Mystique", mystique, OpCodes.PhoenixOpCodes, new MystiqueFeeSchedule),
    // Spiral: adds PUSH0; keeps the Mystique fee schedule (SpiralConfigBuilder does not override feeSchedule).
    ForkCase("Spiral", spiral, OpCodes.SpiralOpCodes, new MystiqueFeeSchedule),
    ForkCase("EtcOlympia", etcOlympia, OpCodes.EtcOlympiaOpCodes, new EtcOlympiaFeeSchedule)
  )

  // ---- ETH London → Osaka lineage (shares the pre-London chain above) ----------------------------------------------

  private val ethLondon   = magneto ++ Set(Eip(3198), Eip(3529), Eip(3860))
  private val ethShanghai = ethLondon + Eip(3855)
  private val ethCancun   = ethShanghai ++ Set(Eip(4844), Eip(7516), Eip(1153), Eip(5656))
  // Prague adds no EVM opcode and no fee-schedule field (EIP-7623 floor is applied in BlockPreparator, not the fee
  // schedule), so its active set equals Cancun's; the distinct bundle is only the (identical-valued) EthPrague fee.
  private val ethPrague   = ethCancun
  private val ethOsaka    = ethCancun + Eip(7939)

  private val ethCases: List[ForkCase] = List(
    ForkCase("EthLondon", ethLondon, OpCodes.EthLondonOpCodes, new EthLondonFeeSchedule),
    // Shanghai overlay keeps the London fee schedule; only the opcode set (PUSH0) and config flags change.
    ForkCase("EthShanghai", ethShanghai, OpCodes.EthShanghaiOpCodes, new EthLondonFeeSchedule),
    ForkCase("EthCancun", ethCancun, OpCodes.EthCancunOpCodes, new EthCancunFeeSchedule),
    ForkCase("EthPrague", ethPrague, OpCodes.EthCancunOpCodes, new EthPragueFeeSchedule),
    ForkCase("EthOsaka", ethOsaka, OpCodes.EthOsakaOpCodes, new EthOsakaFeeSchedule)
  )

  private def proveDerivedEqualsBundle(fc: ForkCase): Unit =
    val (derivedOpCodes, derivedFee) = EvmProposals.deriveEvm(fc.active)
    withClue(s"${fc.name} opcode set: ") {
      derivedOpCodes.toSet shouldBe fc.opcodes.toSet
    }
    withClue(s"${fc.name} fee field-tuple: ") {
      fields(derivedFee) shouldBe fields(fc.fee)
    }

  // ---- The proof: derived == bundle for every fork, both networks ---------------------------------------------------

  for fc <- etcCases do
    s"The derived EVM config for ETC ${fc.name}" should "equal the hand-written bundle (opcode .toSet + fee field-tuple)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      proveDerivedEqualsBundle(fc)
    }

  for fc <- ethCases do
    s"The derived EVM config for ETH ${fc.name}" should "equal the hand-written bundle (opcode .toSet + fee field-tuple)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      proveDerivedEqualsBundle(fc)
    }

  // ---- Loud regression guards on the network discriminators (framework §2.4.2) --------------------------------------

  "The derived EtcOlympia opcode set" should "contain CLZ and BASEFEE but neither blob opcode (ETC-only shape)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val derived = EvmProposals.deriveEvm(etcOlympia)._1.toSet
    derived should contain(CLZ)
    derived should contain(BASEFEE)
    derived should not contain BLOBHASH
    derived should not contain BLOBBASEFEE
  }

  "The derived EthCancun opcode set" should "carry the blob opcodes but not CLZ (EIP-7939 is Osaka-only)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val derived = EvmProposals.deriveEvm(ethCancun)._1.toSet
    derived should contain(BLOBHASH)
    derived should contain(BLOBBASEFEE)
    derived should not contain CLZ
  }

  "The derived EthLondon opcode set" should "add BASEFEE over Phoenix without any blob/transient opcode" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val derived = EvmProposals.deriveEvm(ethLondon)._1.toSet
    derived should contain(BASEFEE)
    derived should not contain PUSH0
    derived should not contain TLOAD
  }

  // ---- Registry structural invariants ------------------------------------------------------------------------------

  "EvmProposals.byId" should "have exactly the keys in evmApplicationOrder (no orphan / unordered entries)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    EvmProposals.byId.keySet shouldBe EvmProposals.evmApplicationOrder.toSet
    EvmProposals.evmApplicationOrder.distinct shouldBe EvmProposals.evmApplicationOrder
  }

  it should "tag every registered proposal Consensus (Row 5.2 registers no ClientPolicy entries)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    EvmProposals.byId.values.foreach(_.layer shouldBe ProposalLayer.Consensus)
  }

  "ECIP-1121 (ETC Olympia composition marker)" should "require exactly the shared EIP impls it bundles (CLZ/BASEFEE/1153/5656)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    EvmProposals.byId(Ecip(1121)).requires shouldBe Set(Eip(7939), Eip(3198), Eip(1153), Eip(5656))
  }
