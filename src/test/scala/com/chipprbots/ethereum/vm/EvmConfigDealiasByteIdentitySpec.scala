package com.chipprbots.ethereum.vm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Byte-identity harness for the Batch 5 Row 5.1 de-alias.
  *
  * The de-alias split the old shared `OlympiaOpCodes`/`OsakaOpCodes`/`OlympiaFeeSchedule`/`PragueFeeSchedule`/
  * `OsakaFeeSchedule` names into disjoint, network-prefixed classes (`Eth*` for ETH timestamp forks, `Etc*` for the ETC
  * block-based Olympia fork). This is a pure rename: every renamed object must be FIELD-IDENTICAL to the content it
  * replaced, so no consensus value moves. These assertions prove that field-identity per fork, both networks.
  *
  * The pre-de-alias facts being pinned:
  *   - ETH Cancun/Prague/Osaka fee fields carried the EIP-3529/3860 values (R_sclear=4800, R_selfdestruct=0,
  *     G_initcode_word=2) — identical to the ETC-lineage MystiqueFeeSchedule.
  *   - `EthLondonFeeSchedule` is the ETH-named root, field-identical to MystiqueFeeSchedule.
  *   - `EtcOlympiaFeeSchedule` is an empty extension of MystiqueFeeSchedule.
  *   - `EthCancunOpCodes` = {BASEFEE, BLOBHASH, BLOBBASEFEE, TLOAD, TSTORE, MCOPY} over Spiral, no CLZ.
  *   - `EthOsakaOpCodes` = EthCancunOpCodes + exactly {CLZ}.
  *   - `EtcOlympiaOpCodes` = CLZ + {BASEFEE, TLOAD, TSTORE, MCOPY} over Spiral, no ETH-only blob opcodes.
  */
class EvmConfigDealiasByteIdentitySpec extends AnyFlatSpec with Matchers:

  import FeeSchedule.*

  /** All 42 declared fields of the FeeSchedule trait, in declaration order. Two schedules are field-identical iff their
    * `fields` sequences are equal — this is the byte-identity check for the fee half of the de-alias.
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

  private val mystique = new MystiqueFeeSchedule
  private val ethLondon = new EthLondonFeeSchedule
  private val ethCancun = new EthCancunFeeSchedule
  private val ethPrague = new EthPragueFeeSchedule
  private val ethOsaka = new EthOsakaFeeSchedule
  private val etcOlympia = new EtcOlympiaFeeSchedule

  // ---- Fee-schedule field identity ----------------------------------------------------------------------------------

  "EthLondonFeeSchedule (de-alias root of the ETH fee lineage)" should "be field-identical to MystiqueFeeSchedule" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    fields(ethLondon) shouldBe fields(mystique)
  }

  it should "carry the exact EIP-3529/3860 anchor values (R_sclear=4800, R_selfdestruct=0, G_initcode_word=2)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    ethLondon.R_sclear shouldBe BigInt(4800)
    ethLondon.R_selfdestruct shouldBe BigInt(0)
    ethLondon.G_initcode_word shouldBe BigInt(2)
  }

  "EthCancunFeeSchedule" should "be field-identical to EthLondonFeeSchedule (Cancun changes are opcode/blob, not fee-field)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    fields(ethCancun) shouldBe fields(ethLondon)
  }

  it should "be field-identical to MystiqueFeeSchedule (the pre-de-alias shared content)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    fields(ethCancun) shouldBe fields(mystique)
  }

  "EthPragueFeeSchedule" should "be field-identical to EthCancunFeeSchedule (EIP-7623 floor is applied outside the fee schedule)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    fields(ethPrague) shouldBe fields(ethCancun)
  }

  "EthOsakaFeeSchedule" should "be field-identical to EthPragueFeeSchedule (EIP-7883/7823 enforced in MODEXP, not the fee schedule)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    fields(ethOsaka) shouldBe fields(ethPrague)
  }

  "EtcOlympiaFeeSchedule (empty extension of MystiqueFeeSchedule)" should "be field-identical to MystiqueFeeSchedule" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    fields(etcOlympia) shouldBe fields(mystique)
  }

  it should "carry the exact EIP-3529/3860 anchor values ETC adopted (R_sclear=4800, R_selfdestruct=0, G_initcode_word=2)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    etcOlympia.R_sclear shouldBe BigInt(4800)
    etcOlympia.R_selfdestruct shouldBe BigInt(0)
    etcOlympia.G_initcode_word shouldBe BigInt(2)
  }

  // ---- Opcode-set identity ------------------------------------------------------------------------------------------

  "EthCancunOpCodes (ETH Cancun opcode set)" should "equal {BASEFEE, BLOBHASH, BLOBBASEFEE, TLOAD, TSTORE, MCOPY} over Spiral" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    OpCodes.EthCancunOpCodes.toSet shouldBe (Set(
      BASEFEE,
      BLOBHASH,
      BLOBBASEFEE,
      TLOAD,
      TSTORE,
      MCOPY
    ) ++ OpCodes.SpiralOpCodes.toSet)
  }

  it should "not contain CLZ (EIP-7939 is Osaka-only, not Cancun)" taggedAs (UnitTest, ConsensusTest) in {
    OpCodes.EthCancunOpCodes should not contain CLZ
  }

  "EthOsakaOpCodes" should "equal EthCancunOpCodes plus exactly {CLZ}" taggedAs (UnitTest, ConsensusTest) in {
    OpCodes.EthOsakaOpCodes.toSet shouldBe (OpCodes.EthCancunOpCodes.toSet + CLZ)
  }

  "EtcOlympiaOpCodes (ETC block-based Olympia)" should "equal CLZ + {BASEFEE, TLOAD, TSTORE, MCOPY} over Spiral" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    OpCodes.EtcOlympiaOpCodes.toSet shouldBe (Set(CLZ, BASEFEE, TLOAD, TSTORE, MCOPY) ++ OpCodes.SpiralOpCodes.toSet)
  }

  it should "exclude the ETH-only blob opcodes BLOBHASH/BLOBBASEFEE (absent from core-geth/Besu ETC Olympia)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    OpCodes.EtcOlympiaOpCodes should not contain BLOBHASH
    OpCodes.EtcOlympiaOpCodes should not contain BLOBBASEFEE
  }

  it should "keep CLZ and BASEFEE (ECIP-1121)" taggedAs (UnitTest, ConsensusTest) in {
    OpCodes.EtcOlympiaOpCodes should contain(CLZ)
    OpCodes.EtcOlympiaOpCodes should contain(BASEFEE)
  }

  // ---- Cross-network disjointness (the §2.4.3 grep ratchet, expressed as a runtime invariant) ------------------------
  // The two networks' Olympia-era opcode sets differ by construction: ETH carries blob opcodes, ETC carries none;
  // ETH Cancun has no CLZ, ETC Olympia does. If a future edit collapsed them back onto a shared literal, one of these
  // membership facts would break — this is the runtime expression of the "no Eth* extends/refs Etc*" invariant.

  "The ETH and ETC Olympia-era opcode sets" should "be non-identical (blob opcodes are the discriminator)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    OpCodes.EthCancunOpCodes.toSet should not be OpCodes.EtcOlympiaOpCodes.toSet
    ((OpCodes.EthCancunOpCodes.toSet -- OpCodes.EtcOlympiaOpCodes.toSet) should contain).allOf(BLOBHASH, BLOBBASEFEE)
    (OpCodes.EtcOlympiaOpCodes.toSet -- OpCodes.EthCancunOpCodes.toSet) should contain(CLZ)
  }
