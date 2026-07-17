package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom

/** L4 P4b — the shared EIP-1559 base-fee **computation** ([[CalcBaseFee]]). The proportional adjustment is byte-cited
  * to go-ethereum `consensus/misc/eip1559/eip1559.go` `CalcBaseFee:66-101`; the family-varying **floor** (ETH 0 vs
  * ECIP-1111 1 gwei) is a parameter, not baked into the computation.
  *
  * Vectors: gasLimit = 20,000,000, elasticity 2 ⇒ target = 10,000,000; denom 8; parent baseFee = 1 gwei (1e9).
  */
class CalcBaseFeeSpec extends AnyFunSuite:

  private val OneGwei: BigInt = BigInt(10).pow(9)

  private def parent(gasUsed: BigInt, baseFee: BigInt, gasLimit: BigInt = 20_000_000): BlockHeader =
    BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = com.chipprbots.fukuii.bytes.Address.Zero,
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 1,
      number = 1,
      gasLimit = gasLimit.toLong,
      gasUsed = gasUsed.toLong,
      unixTimestamp = 0,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty,
      baseFeePerGas = Some(baseFee)
    )

  test("base fee is unchanged when parent gasUsed == target (eip1559.go:68-70)"):
    assert(CalcBaseFee.calcBaseFee(parent(10_000_000, OneGwei), CalcBaseFee.EthBaseFeeFloor) == OneGwei)

  test("base fee INCREASES when parent gasUsed > target (eip1559.go:80-87)"):
    // delta = (15M-10M)*1e9/10M/8 = 62_500_000 ⇒ 1e9 + 62_500_000.
    assert(
      CalcBaseFee.calcBaseFee(parent(15_000_000, OneGwei), CalcBaseFee.EthBaseFeeFloor) == OneGwei + 62_500_000
    )

  test("base fee DECREASES when parent gasUsed < target (eip1559.go:91-96)"):
    // delta = (10M-5M)*1e9/10M/8 = 62_500_000 ⇒ 1e9 - 62_500_000 = 937_500_000.
    assert(
      CalcBaseFee.calcBaseFee(parent(5_000_000, OneGwei), CalcBaseFee.EthBaseFeeFloor) == BigInt(937_500_000)
    )

  test("ETH floor 0 vs ECIP-1111 floor 1 gwei — a decreasing base fee below 1 gwei clamps only on ETC"):
    // Same decreasing parent computes 937_500_000: ETH keeps it (floor 0); ETC clamps up to 1 gwei (ECIP-1111 :61-65).
    val p = parent(5_000_000, OneGwei)
    assert(
      CalcBaseFee.calcBaseFee(p, CalcBaseFee.EthBaseFeeFloor) == BigInt(937_500_000) &&
        CalcBaseFee.calcBaseFee(p, CalcBaseFee.Ecip1111BaseFeeFloor) == OneGwei
    )

  test("ECIP-1111 floor is inert above 1 gwei — an increasing base fee is unclamped"):
    // 1e9 + 62_500_000 > 1 gwei ⇒ the 1-gwei floor does not engage; identical to the ETH (floor-0) result.
    val p = parent(15_000_000, OneGwei)
    assert(
      CalcBaseFee.calcBaseFee(p, CalcBaseFee.Ecip1111BaseFeeFloor) ==
        CalcBaseFee.calcBaseFee(p, CalcBaseFee.EthBaseFeeFloor)
    )

  test("sustained-empty blocks decay the base fee toward 0 under floor 0, but the ECIP-1111 floor holds it at 1 gwei"):
    // gasUsed = 0: delta = 10M*1e9/10M/8 = 125_000_000 ⇒ 875_000_000 (ETH); ECIP-1111 holds at 1 gwei (draft :65).
    val p = parent(0, OneGwei)
    assert(
      CalcBaseFee.calcBaseFee(p, CalcBaseFee.EthBaseFeeFloor) == BigInt(875_000_000) &&
        CalcBaseFee.calcBaseFee(p, CalcBaseFee.Ecip1111BaseFeeFloor) == OneGwei
    )
