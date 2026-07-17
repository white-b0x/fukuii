package com.chipprbots.fukuii.execution

import org.scalatest.funsuite.AnyFunSuite

/** L4 P5a — the EIP-4844 blob-gas fee market [[CalcBlobFee]]. Reference values are computed from go-ethereum's exact
  * `fakeExponential` (`consensus/misc/eip4844/eip4844.go`) with the Cancun (`3338477`) and Prague+ (`5007716`,
  * EIP-7691) update fractions; the fork-selection difference at a fixed `excessBlobGas` is asserted so the wrong
  * fraction cannot pass. beacon co-signs the byte values.
  */
class CalcBlobFeeSpec extends AnyFunSuite:

  test("blobBaseFee(excessBlobGas = 0) is the 1-wei minimum on both fractions (BlobTxMinBlobGasprice)"):
    assert(
      CalcBlobFee.blobBaseFee(0, CalcBlobFee.CancunUpdateFraction) == BigInt(1) &&
        CalcBlobFee.blobBaseFee(0, CalcBlobFee.PragueUpdateFraction) == BigInt(1)
    )

  test("blobBaseFee — Cancun fraction 3338477, byte-exact vs go-ethereum fakeExponential"):
    // Reference: fakeExponential(1, excess, 3338477) — computed from the go-ethereum algorithm.
    assert(
      CalcBlobFee.blobBaseFee(10_000_000, CalcBlobFee.CancunUpdateFraction) == BigInt(19) &&
        CalcBlobFee.blobBaseFee(100_000_000, CalcBlobFee.CancunUpdateFraction) == BigInt("10203769476395")
    )

  test("blobBaseFee — Prague fraction 5007716 (EIP-7691), byte-exact vs go-ethereum fakeExponential"):
    assert(
      CalcBlobFee.blobBaseFee(10_000_000, CalcBlobFee.PragueUpdateFraction) == BigInt(7) &&
        CalcBlobFee.blobBaseFee(100_000_000, CalcBlobFee.PragueUpdateFraction) == BigInt(470442149)
    )

  test("the update fraction is load-bearing — the same excessBlobGas prices differently Cancun vs Prague"):
    // At excess = 10_000_000, Cancun → 19, Prague → 7: selecting the wrong fraction diverges the state root.
    assert(
      CalcBlobFee.blobBaseFee(10_000_000, CalcBlobFee.CancunUpdateFraction) !=
        CalcBlobFee.blobBaseFee(10_000_000, CalcBlobFee.PragueUpdateFraction)
    )

  test("fakeExponential(factor, 0, denom) == factor — the zeroth Taylor term only"):
    assert(
      CalcBlobFee.fakeExponential(1, 0, CalcBlobFee.CancunUpdateFraction) == BigInt(1) &&
        CalcBlobFee.fakeExponential(7, 0, CalcBlobFee.PragueUpdateFraction) == BigInt(7)
    )
