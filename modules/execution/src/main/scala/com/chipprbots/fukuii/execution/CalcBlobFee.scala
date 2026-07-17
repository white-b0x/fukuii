package com.chipprbots.fukuii.execution

import scala.annotation.tailrec

/** The EIP-4844 blob-gas fee market — the `blobBaseFee` a Cancun+ ETH block charges per blob gas, derived from the
  * header's `excessBlobGas` via go-ethereum's `fakeExponential` (`consensus/misc/eip4844/eip4844.go`
  * `blobBaseFee`/`fakeExponential`). This is the ETH-only companion to [[CalcBaseFee]] (the execution-gas base fee):
  * the blob base fee is a **separate** fee dimension, priced by its own excess-gas market and — like the EIP-1559 base
  * fee — **burned**, never credited to the coinbase (go-ethereum `core/state_transition.go:471-483` deducts `blobGas ×
  * blobBaseFee` from the sender and credits it nowhere).
  *
  * **F-L4-5 (the P5 hard gate).** The upfront balance *check* (F-L4-3) uses the fee **cap** (`maxFeePerBlobGas`); the
  * actual *debit* — this object — uses the **actual** blob base fee from `excessBlobGas`, so a committed Cancun+ block
  * with a blob tx debits the real blob fee and its state root matches go-ethereum. ETC never activates EIP-4844, so on
  * the PoW path there is no blob tx and this is never reached (blob gas is 0). **beacon co-signs the byte values.**
  *
  * **Fork-varying update fraction.** The fake-exponential denominator (`UpdateFraction`) changed at Prague under
  * EIP-7691 (`3338477` Cancun → `5007716` Prague+); the caller selects it from the resolved fork
  * ([[TransactionProcessor]] gates on `Eip(7691)`). BPO forks (EIP-7892) can further vary it via a per-timestamp blob
  * schedule — out of P5a scope (a Prague-base Osaka block uses the Prague fraction).
  */
object CalcBlobFee:

  /** EIP-4844 minimum blob gas price — `BlobTxMinBlobGasprice = 1` (go-ethereum `params/protocol_params.go:204`); the
    * `factor` of the fake-exponential, so `blobBaseFee(excessBlobGas = 0) = 1`.
    */
  val MinBlobBaseFee: BigInt = 1

  /** EIP-4844 Cancun blob-base-fee update fraction — `3338477` (go-ethereum `DefaultCancunBlobConfig.UpdateFraction`,
    * `params/config.go:378`).
    */
  val CancunUpdateFraction: BigInt = 3338477

  /** EIP-7691 Prague+ blob-base-fee update fraction — `5007716` (go-ethereum `DefaultPragueBlobConfig.UpdateFraction`,
    * `params/config.go:384`). Prague raised the blob throughput (target 6 / max 9) and this fraction with it; Osaka
    * inherits it absent a BPO override.
    */
  val PragueUpdateFraction: BigInt = 5007716

  /** The blob base fee for a header's `excessBlobGas` — `fakeExponential(MinBlobBaseFee, excessBlobGas,
    * updateFraction)` (go-ethereum `BlobConfig.blobBaseFee`, `eip4844.go:44-46`). The caller supplies the fork-resolved
    * `updateFraction` ([[CancunUpdateFraction]] / [[PragueUpdateFraction]]).
    */
  def blobBaseFee(excessBlobGas: BigInt, updateFraction: BigInt): BigInt =
    fakeExponential(MinBlobBaseFee, excessBlobGas, updateFraction)

  /** `factor · e^(numerator / denominator)` approximated by its Taylor series, integer-truncated — go-ethereum
    * `fakeExponential` (`eip4844.go`). Byte-exact: `output` accumulates `accum` (starting `factor · denominator`), then
    * `accum ← accum · numerator / denominator / i` each term until `accum` truncates to `0`; the result is `output /
    * denominator`. All divisions truncate toward zero (`BigInt` `/` == Go `big.Int.Div` on non-negative operands).
    */
  def fakeExponential(factor: BigInt, numerator: BigInt, denominator: BigInt): BigInt =
    fakeExpLoop(BigInt(0), factor * denominator, numerator, denominator, 1) / denominator

  @tailrec
  private def fakeExpLoop(output: BigInt, accum: BigInt, numerator: BigInt, denominator: BigInt, i: Int): BigInt =
    if accum <= 0 then output
    else fakeExpLoop(output + accum, accum * numerator / denominator / i, numerator, denominator, i + 1)
