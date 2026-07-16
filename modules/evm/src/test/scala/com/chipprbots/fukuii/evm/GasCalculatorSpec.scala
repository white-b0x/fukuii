package com.chipprbots.fukuii.evm

import org.scalatest.funsuite.AnyFunSuite

/** Byte-exact coverage for the per-fork [[GasCalculator]] — the fee VALUES transcribed from the AS-IS `FeeSchedule`,
  * the EIP-2929 warm/cold access cost moved *into* the calculator (T3 / RX-L3-09), and the memory / gas-cap / word
  * helpers. Consensus-critical values: forge (ETC/Olympia) + beacon (ETH/Osaka) co-sign.
  *
  * One `assert` per test (the value-returning last statement) — the `-Wnonunit-statement` build gate rejects a
  * discarded intermediate `Assertion`.
  */
class GasCalculatorSpec extends AnyFunSuite:

  test("Frontier tier values match the YP fee schedule"):
    val f = GasCalculator.Frontier
    assert(
      f.G_zero == BigInt(0) && f.G_base == BigInt(2) && f.G_verylow == BigInt(3) && f.G_low == BigInt(5) &&
        f.G_mid == BigInt(8) && f.G_high == BigInt(10) && f.G_sload == BigInt(50) &&
        f.G_transaction == BigInt(21000) && f.G_create == BigInt(32000) && f.subGasCapDivisor.isEmpty
    )

  test("EIP-150 reprices state access and enables the all-but-one-64th gas cap"):
    val g = GasCalculator.Eip150
    assert(
      g.G_sload == BigInt(200) && g.G_call == BigInt(700) && g.G_balance == BigInt(400) &&
        g.G_extcode == BigInt(700) && g.subGasCapDivisor.contains(64L) &&
        g.gasCap(64) == BigInt(63) && g.gasCap(6400) == BigInt(6300)
    )

  test("EIP-160 raises the EXP byte cost to 50"):
    assert(GasCalculator.Eip160.G_expbyte == BigInt(50))

  test("EIP-1884 (Istanbul) reprices SLOAD/BALANCE and cheapens calldata"):
    val p = GasCalculator.Eip1884
    assert(p.G_sload == BigInt(800) && p.G_balance == BigInt(700) && p.G_txdatanonzero == BigInt(16))

  test("pre-EIP-2929 access cost is the opcode base tier (warmth ignored)"):
    val p = GasCalculator.Eip1884
    assert(
      p.accountAccessCost(p.G_call, isWarm = true) == p.G_call &&
        p.accountAccessCost(p.G_call, isWarm = false) == p.G_call &&
        p.storageAccessCost(p.G_sload, isWarm = true) == p.G_sload
    )

  test("EIP-2929 (Berlin) moves warm/cold access cost into the calculator (T3/RX-L3-09)"):
    val m = GasCalculator.Eip2929
    assert(
      m.accountAccessCost(m.G_call, isWarm = true) == BigInt(100) &&
        m.accountAccessCost(m.G_call, isWarm = false) == BigInt(2600) &&
        m.storageAccessCost(m.G_sload, isWarm = true) == BigInt(100) &&
        m.storageAccessCost(m.G_sload, isWarm = false) == BigInt(2100) &&
        m.G_sload == BigInt(100) && m.G_sreset == BigInt(2900) && m.G_sset == BigInt(20000)
    )

  test("EIP-3529 (London) applies refund reductions and EIP-3860 initcode metering"):
    val m = GasCalculator.Eip3529
    assert(m.R_sclear == BigInt(4800) && m.R_selfdestruct == BigInt(0) && m.G_initcode_word == BigInt(2))

  test("EtcOlympia gas fields are field-identical to EIP-3529 (ETC-only leaf)"):
    val o = GasCalculator.EtcOlympia
    val m = GasCalculator.Eip3529
    assert(
      o.R_sclear == m.R_sclear && o.R_selfdestruct == m.R_selfdestruct &&
        o.G_initcode_word == m.G_initcode_word && o.G_sload == m.G_sload &&
        o.accountAccessCost(o.G_call, isWarm = false) == BigInt(2600)
    )

  test("ETH London→Osaka carry the EIP-3529/3860 values (ETH-only leaves)"):
    val leaves = Seq(GasCalculator.EthLondon, GasCalculator.EthCancun, GasCalculator.EthPrague, GasCalculator.EthOsaka)
    assert(
      leaves.forall(g =>
        g.R_sclear == BigInt(4800) && g.R_selfdestruct == BigInt(0) && g.G_initcode_word == BigInt(2) &&
          g.accountAccessCost(g.G_call, isWarm = true) == BigInt(100)
      )
    )

  test("wordsForBytes is ceil(n/32)"):
    assert(
      wordsForBytes(0) == BigInt(0) && wordsForBytes(1) == BigInt(1) && wordsForBytes(32) == BigInt(1) &&
        wordsForBytes(33) == BigInt(2) && wordsForBytes(64) == BigInt(2)
    )

  test("calcMemCost: no cost when the region already fits, quadratic growth otherwise"):
    val f = GasCalculator.Frontier
    assert(
      f.calcMemCost(memSize = 0, offset = 0, dataSize = 0) == BigInt(0) &&
        f.calcMemCost(memSize = 64, offset = 0, dataSize = 32) == BigInt(0) &&
        f.calcMemCost(memSize = 0, offset = 0, dataSize = 32) == BigInt(3)
    )
