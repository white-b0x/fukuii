package com.chipprbots.fukuii.execution

import com.chipprbots.fukuii.domain.BlockHeader

/** The EIP-1559 next-block base-fee **computation** — the proportional adjustment of a block's base fee from its parent
  * header (go-ethereum `consensus/misc/eip1559/eip1559.go` `CalcBaseFee:60-102`). This computation is **shared across
  * families**; only the *floor* differs (ETH floors at 0, `eip1559.go:97-99`; ETC Olympia floors at 1 gwei, ECIP-1111
  * draft `:61-65`) and the *disposition* of the resulting base fee differs ([[FeeDisposition]] — ETH burns, ETC
  * redirects to the treasury). The floor is a **chain-config parameter**, never baked into the computation (ECIP-1111
  * draft `:65` "via chain configuration"; L4 plan §7, RX-L4-09/10).
  *
  * The floor is applied **after** the proportional adjustment and **before** header inclusion, so it feeds the next
  * block's parent input (a floored parent yields a floored child) — applying it at the wrong point diverges every
  * subsequent block (ECIP-1111 draft `:65`).
  */
object CalcBaseFee:

  /** EIP-1559 gas-target elasticity multiplier — `gasTarget = gasLimit / 2` (go-ethereum
    * `config.ElasticityMultiplier()` default `params.DefaultElasticityMultiplier = 2`).
    */
  val DefaultElasticityMultiplier: Int = 2

  /** EIP-1559 base-fee change denominator — the per-block adjustment is at most `parentBaseFee / 8` (go-ethereum
    * `config.BaseFeeChangeDenominator()` default `params.DefaultBaseFeeChangeDenominator = 8`).
    */
  val DefaultBaseFeeChangeDenominator: BigInt = 8

  /** ETH base-fee floor — `0` (go-ethereum `eip1559.go:97-99` clamps a decreasing base fee at 0). */
  val EthBaseFeeFloor: BigInt = 0

  /** ECIP-1111 base-fee floor — `INITIAL_BASE_FEE = 1,000,000,000 wei (1 gwei)` (ECIP-1111 draft `:61-65`). ETC's ~0.5%
    * block utilization would otherwise decay the base fee toward 0 under standard EIP-1559 mechanics, eliminating
    * treasury revenue; Ronin enforces the equivalent 1-gwei floor (draft `:65`).
    */
  val Ecip1111BaseFeeFloor: BigInt = BigInt(10).pow(9)

  /** Compute the base fee of the block whose `parent` is given, clamped to `floor`. Byte-cited to go-ethereum
    * `CalcBaseFee` (`eip1559.go:66-101`):
    *   - `parentGasTarget = parent.gasLimit / elasticityMultiplier` (`:66`);
    *   - if `parent.gasUsed == parentGasTarget` the base fee is unchanged (`:68-70`);
    *   - if `parent.gasUsed > parentGasTarget` it **increases** by `max(1, parentBaseFee * gasUsedDelta / target /
    *     denominator)` (`:80-87` — the `max(1, …)` is go-ethereum's `if num < 1 { parentBaseFee + 1 }`);
    *   - if `parent.gasUsed < parentGasTarget` it **decreases** by `parentBaseFee * gasUsedDelta / target /
    *     denominator` (`:91-96`), go-ethereum then clamps the result at 0 (`:97-99`).
    *
    * The final `.max(floor)` unifies go-ethereum's decrease-branch 0-clamp with the ECIP-1111 1-gwei clamp: for `floor
    * \= 0` (ETH) it is inert on the equal/increase branches and reproduces the `if baseFee < 0 { 0 }` on the decrease
    * branch; for `floor = 1 gwei` (ETC) it is the ECIP-1111 `max(computedBaseFee, INITIAL_BASE_FEE)` clamp on every
    * branch (draft `:61-65`).
    *
    * The caller resolves the "first EIP-1559 block → InitialBaseFee" case (go-ethereum `eip1559.go:62-64`,
    * fork-config-dependent) — this function assumes `parent` is already on the EIP-1559 schedule and reads its
    * `baseFeePerGas` (absent ⇒ `0`).
    */
  def calcBaseFee(
      parent: BlockHeader,
      floor: BigInt,
      elasticityMultiplier: Int = DefaultElasticityMultiplier,
      baseFeeChangeDenominator: BigInt = DefaultBaseFeeChangeDenominator
  ): BigInt =
    val parentBaseFee = parent.baseFeePerGas.getOrElse(BigInt(0))
    val parentGasUsed = BigInt(parent.gasUsed)
    val parentGasTarget = BigInt(parent.gasLimit) / elasticityMultiplier
    val computed =
      if parentGasUsed == parentGasTarget then parentBaseFee
      else if parentGasUsed > parentGasTarget then
        val delta = (parentGasUsed - parentGasTarget) * parentBaseFee / parentGasTarget / baseFeeChangeDenominator
        parentBaseFee + delta.max(BigInt(1))
      else
        val delta = (parentGasTarget - parentGasUsed) * parentBaseFee / parentGasTarget / baseFeeChangeDenominator
        parentBaseFee - delta
    computed.max(floor)
