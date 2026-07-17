package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.domain.Transaction
import com.chipprbots.fukuii.evm.EvmConfig
import com.chipprbots.fukuii.evm.ProposalId.Eip
import com.chipprbots.fukuii.evm.wordsForBytes

/** Intrinsic transaction gas — the gas a transaction owes **before** the VM runs, plus the EIP-7623 calldata *floor* a
  * transaction must be able to buy. Every constant is a **Tier-A consensus value** read off the fork-resolved
  * [[EvmConfig.gasCalculator]] (never a per-site fork predicate); the byte values are transcribed from go-ethereum
  * `core/state_transition.go` `IntrinsicGas`/`FloorDataGas` (RX-L4-04). The ETC schedule (EIP-3860 at Spiral, EIP-3529
  * at Magneto) and the ETH schedule (Istanbul calldata, Shanghai initcode) both flow through the injected calculator,
  * so there is **no `if(isETC)`** here — only the resolved fork's gas constants.
  *
  * **Amsterdam (EIP-2780/7976/7981) is explicitly OUT of scope** — it is an ETH-future fork neither current family (ETC
  * Olympia, ETH Osaka) reaches, so the pre-Amsterdam calldata token model (`TxDataZeroGas`/`TxDataNonZeroGas`) is the
  * only path built; the 64/64 EIP-7976 re-pricing and the EIP-7981 access-list token surcharge are deferred to a future
  * ETH fork phase (beacon).
  */
object IntrinsicGas:

  /** EIP-7623 total-cost floor per token (go-ethereum `params.TxCostFloorPerToken = 10`). */
  private val TotalCostFloorPerToken: BigInt = 10

  /** EIP-7623 token weight of a non-zero calldata byte (go-ethereum `params.TxTokenPerNonZeroByte = 4`); a zero byte is
    * one token.
    */
  private val TokensPerNonZeroByte: BigInt = 4

  /** The intrinsic gas of `tx` under the fork-resolved `evmConfig` — go-ethereum `IntrinsicGas`
    * (`state_transition.go:72-160`), pre-Amsterdam path:
    *
    *   - **base** `G_transaction` (21000), plus the create surcharge `G_txcreate` for a contract creation (0 pre-
    *     Homestead, 32000 after — so a Homestead+ create is 53000 = `TxGasContractCreation`, exactly geth's
    *     `TxGasContractCreation` branch, and a Frontier create is the bare 21000);
    *   - **EIP-7702** `G_newaccount` (25000 = `CallNewAccountGas`) per authorization tuple (geth `:89`);
    *   - **calldata** `nz*G_txdatanonzero + z*G_txdatazero` (68/16 non-zero by fork, 4 zero; geth `:100-112`);
    *   - **EIP-3860** `G_initcode_word * ceil(len/32)` for a create when active (geth `:114-119`);
    *   - **EIP-2930 access list** `G_access_list_address` per address + `G_access_list_storage` per slot (2400/1900;
    *     geth `:123-132`).
    */
  def intrinsicGas(tx: Transaction, evmConfig: EvmConfig): BigInt =
    val gc = evmConfig.gasCalculator
    val isCreate = TxFields.isContractCreation(tx)
    val data = TxFields.payload(tx)

    // Base: G_transaction, plus the create surcharge (G_txcreate is 0 pre-Homestead → a Frontier create is 21000).
    val base: BigInt = if isCreate then gc.G_transaction + gc.G_txcreate else gc.G_transaction

    // EIP-7702: CallNewAccountGas per authorization tuple (geth state_transition.go:89, pre-Amsterdam).
    val authGas: BigInt = BigInt(TxFields.authorizationList(tx).size) * gc.G_newaccount

    // Calldata: non-zero vs zero byte pricing (geth :95-112).
    val (zeroBytes, nonZeroBytes) = countBytes(data)
    val dataGas: BigInt = nonZeroBytes * gc.G_txdatanonzero + zeroBytes * gc.G_txdatazero

    // EIP-3860 initcode word metering, creations only (geth :114-119). G_initcode_word is 0 pre-3860, but gate on the
    // resolved fork intent so the rule is explicit and matches geth's `isContractCreation && rules.IsShanghai`.
    val initCodeGas: BigInt =
      if isCreate && evmConfig.eip3860Enabled then gc.G_initcode_word * wordsForBytes(BigInt(data.length))
      else BigInt(0)

    // EIP-2930 access list (geth :123-132).
    val accessList = TxFields.accessList(tx)
    val addressCount = BigInt(accessList.size)
    val storageKeyCount = BigInt(accessList.map(_.storageKeys.size).sum)
    val accessListGas: BigInt =
      addressCount * gc.G_access_list_address + storageKeyCount * gc.G_access_list_storage

    base + authGas + dataGas + initCodeGas + accessListGas

  /** The EIP-7623 calldata floor — the minimum gas a transaction must consume, `21000 + tokens * 10` where `tokens =
    * zeroBytes + nonZeroBytes * 4` (go-ethereum `FloorDataGas`, `state_transition.go:198-256`, pre-Amsterdam branch).
    * The floor never subtracts from the runtime budget; it inflates the settled `gasUsed` at tx end and gates the gas
    * limit (`gasLimit < floorDataGas` ⇒ `ErrFloorDataGas` reject). Only meaningful when [[isActiveFloor]].
    *
    * The floor base is `G_transaction` (21000) — go-ethereum's `floorBase = params.TxGas` (`:248`); the create
    * surcharge and access-list gas are **not** part of the floor.
    */
  def floorDataGas(tx: Transaction, evmConfig: EvmConfig): BigInt =
    val gc = evmConfig.gasCalculator
    val (zeroBytes, nonZeroBytes) = countBytes(TxFields.payload(tx))
    val tokens = zeroBytes + nonZeroBytes * TokensPerNonZeroByte
    gc.G_transaction + tokens * TotalCostFloorPerToken

  /** Whether the EIP-7623 calldata floor is enforced at this fork (Prague on ETH, Olympia on ETC — both carry the
    * `Eip(7623)` proposal; go-ethereum gates `FloorDataGas` on `rules.IsPrague`).
    */
  def isActiveFloor(evmConfig: EvmConfig): Boolean =
    evmConfig.isActive(Eip(7623))

  /** Split calldata into (zeroBytes, nonZeroBytes) — go-ethereum `bytes.Count(data, []byte{0})` and the complement. */
  private def countBytes(data: ByteString): (BigInt, BigInt) =
    val zero = data.count(_ == (0x00: Byte))
    (BigInt(zero), BigInt(data.length - zero))
