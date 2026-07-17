package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.domain.AccessListEntry
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.domain.ReceiptStatus
import com.chipprbots.fukuii.domain.SetCodeAuthorization
import com.chipprbots.fukuii.domain.Transaction
import com.chipprbots.fukuii.domain.Wei
import com.chipprbots.fukuii.evm.EvmConfig
import com.chipprbots.fukuii.evm.EvmInterpreter
import com.chipprbots.fukuii.storage.EphemDataSource
import com.chipprbots.fukuii.trie.InMemoryMptStorage
import com.chipprbots.fukuii.trie.MptNode

/** L4 P2 — the per-tx engine [[TransactionProcessor]] + [[IntrinsicGas]] + [[SimulationOptions]]. Every asserted gas
  * constant is cited to go-ethereum `core/state_transition.go` / `params/protocol_params.go`; the ETC/ETH schedule
  * split flows through the fork-resolved `EvmConfig.gasCalculator`, so there is no `if(isETC)` in either file under
  * test.
  */
class TransactionProcessorSpec extends AnyFunSuite:

  private val chainId: ChainId = ChainId(61)
  private val dummySig: ECDSASignature = ECDSASignature(r = BigInt(1), s = BigInt(1), v = BigInt(27))

  private def addr(b: Byte): Address = Address(ByteString(Array.fill[Byte](Address.Length)(b)))
  private val sender: Address = addr(0x11)
  private val recipient: Address = addr(0x22)
  private val coinbase: Address = addr(0x33)
  private val contract: Address = addr(0x44)

  private val interpreter = new EvmInterpreter[InMemoryWorldState, InMemoryAccountStorage]()
  private val processor = new TransactionProcessor(interpreter)

  private def header(): BlockHeader =
    BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = coinbase,
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 1,
      number = 1,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = 0,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty
    )

  private def spec(config: EvmConfig): ProtocolSpec =
    ProtocolSpec(config, RewardScheme.PosNoRewardScheme, RequestProcessors.noOp, None, FeeDisposition.Absent)

  private def world(accounts: (Address, Account)*): InMemoryWorldState =
    val base = InMemoryWorldState(
      codeStorage = new CodeStorage(EphemDataSource()),
      mptStorage = new InMemoryMptStorage,
      getBlockHashByNumber = _ => None,
      accountStartNonce = UInt256.Zero,
      stateRootHash = MptNode.EmptyRootHash,
      noEmptyAccounts = true
    )
    accounts.foldLeft(base)((w, kv) => w.saveAccount(kv._1, kv._2))

  private def funded(balance: BigInt): Account = Account.empty().copy(balance = Wei(UInt256(balance)))

  private def legacy(
      to: Option[Address],
      value: BigInt = 0,
      gasLimit: BigInt = 21000,
      gasPrice: BigInt = 1,
      payload: ByteString = ByteString.empty,
      nonce: BigInt = 0
  ): Transaction.Legacy =
    Transaction.Legacy(
      nonce = UInt256(nonce),
      gasPrice = Wei(UInt256(gasPrice)),
      gasLimit = UInt256(gasLimit),
      to = to,
      value = Wei(UInt256(value)),
      payload = payload,
      signature = dummySig
    )

  // -- intrinsic gas (go-ethereum IntrinsicGas, state_transition.go:72-160) -------------------------------------------

  test("intrinsic gas — legacy value transfer is the bare 21000 (params.TxGas)"):
    assert(IntrinsicGas.intrinsicGas(legacy(Some(recipient)), EvmConfig.Frontier) == BigInt(21000))

  test("intrinsic gas — calldata is priced 4/zero, 68/non-zero on Frontier (TxDataZeroGas/TxDataNonZeroGasFrontier)"):
    // data = [0x00, 0x01, 0x02] → 1 zero, 2 non-zero. 21000 + 1*4 + 2*68 = 21140.
    val data = ByteString(Array[Byte](0x00, 0x01, 0x02))
    assert(IntrinsicGas.intrinsicGas(legacy(Some(recipient), payload = data), EvmConfig.Frontier) == BigInt(21140))

  test("intrinsic gas — non-zero calldata drops to 16 by Istanbul/EIP-2028 (EtcOlympia gas schedule)"):
    // 21000 + 1*4 + 2*16 = 21036.
    val data = ByteString(Array[Byte](0x00, 0x01, 0x02))
    assert(IntrinsicGas.intrinsicGas(legacy(Some(recipient), payload = data), EvmConfig.EtcOlympia) == BigInt(21036))

  test("intrinsic gas — contract creation is 53000 (TxGasContractCreation = TxGas + G_txcreate) on a Homestead+ fork"):
    assert(IntrinsicGas.intrinsicGas(legacy(to = None), EvmConfig.EtcOlympia) == BigInt(53000))

  test("intrinsic gas — EIP-2930 access list charges 2400/address + 1900/slot"):
    // 1 address, 2 storage keys, empty data: 21000 + 2400 + 2*1900 = 27200.
    val entry = AccessListEntry(recipient, List(Hash.Zero, Hash(ByteString(Array.fill[Byte](Hash.Length)(1)))))
    val tx = Transaction.AccessList(
      chainId,
      UInt256.Zero,
      Wei(UInt256(1)),
      UInt256(30000),
      Some(recipient),
      Wei.Zero,
      ByteString.empty,
      List(entry),
      dummySig
    )
    assert(IntrinsicGas.intrinsicGas(tx, EvmConfig.EtcOlympia) == BigInt(27200))

  test("intrinsic gas — EIP-7702 charges CallNewAccountGas (25000) per authorization tuple"):
    // 2 auth tuples, empty data: 21000 + 2*25000 = 71000 (geth state_transition.go:89, pre-Amsterdam).
    val auth = SetCodeAuthorization(chainId, recipient, UInt256.Zero, 0, UInt256.Zero, UInt256.Zero)
    val tx = Transaction.SetCode(
      chainId,
      UInt256.Zero,
      Wei(UInt256(1)),
      Wei(UInt256(1)),
      UInt256(100000),
      recipient,
      Wei.Zero,
      ByteString.empty,
      Nil,
      List(auth, auth),
      dummySig
    )
    assert(IntrinsicGas.intrinsicGas(tx, EvmConfig.EtcOlympia) == BigInt(71000))

  // -- EIP-7623 calldata floor (go-ethereum FloorDataGas, state_transition.go:198-256) --------------------------------

  test("EIP-7623 floor — 21000 + tokens*10, tokens = zero + nonzero*4; active on Olympia, not on Frontier"):
    // 100 non-zero bytes → tokens = 400 → floor = 21000 + 4000 = 25000.
    val data = ByteString(Array.fill[Byte](100)(0x01))
    val tx = legacy(Some(recipient), payload = data)
    assert(
      IntrinsicGas.floorDataGas(tx, EvmConfig.EtcOlympia) == BigInt(25000) &&
        IntrinsicGas.isActiveFloor(EvmConfig.EtcOlympia) &&
        !IntrinsicGas.isActiveFloor(EvmConfig.Frontier)
    )

  test("EIP-7623 floor — a gas limit below the floor is an inclusion reject (ErrFloorDataGas)"):
    // 100 non-zero bytes: intrinsic = 21000 + 100*16 = 22600; floor = 25000. gasLimit 24000 clears intrinsic but not floor.
    val data = ByteString(Array.fill[Byte](100)(0x01))
    val tx = legacy(Some(recipient), gasLimit = 24000, payload = data)
    val result = processor.processTransaction(
      tx,
      sender,
      header(),
      spec(EvmConfig.EtcOlympia),
      world(sender -> funded(10_000_000)),
      0,
      chainId
    )
    assert(result == Left(TransactionError.CalldataFloorTooHigh(BigInt(25000), BigInt(24000))))

  // -- value-transfer tx: sender debited, coinbase tipped, nonce bumped, gasUsed correct ------------------------------

  test("value transfer — sender debited gas+value, recipient credited, coinbase tipped, nonce bumped, gasUsed=21000"):
    val start = BigInt(1_000_000)
    val tx = legacy(Some(recipient), value = 100, gasPrice = 1)
    val result = processor.processTransaction(
      tx,
      sender,
      header(),
      spec(EvmConfig.Frontier),
      world(sender -> funded(start)),
      0,
      chainId
    )
    val res = result.toOption.get
    // gasUsed=21000; tip = gasUsed*effectiveTip = 21000*1 (legacy, no baseFee → tip = gasPrice).
    assert(
      res.gasUsed == BigInt(21000) &&
        res.succeeded && res.receipt.status == ReceiptStatus.Status(true) &&
        res.world.getBalance(sender).toBigInt == start - 21000 - 100 && // gas + value out
        res.world.getBalance(recipient).toBigInt == BigInt(100) &&
        res.world.getBalance(coinbase).toBigInt == BigInt(21000) && // effective tip
        res.world.getAccount(sender).map(_.nonce).contains(UInt256(1))
    )

  test("SimulationOptions.none is the default no-sim path (isConsensus, empty relocations)"):
    // A processTransaction call omitting the simulation argument uses SimulationOptions.none.
    val tx = legacy(Some(recipient), value = 1)
    val result = processor.processTransaction(
      tx,
      sender,
      header(),
      spec(EvmConfig.Frontier),
      world(sender -> funded(1_000_000)),
      0,
      chainId
    )
    assert(SimulationOptions.none.isConsensus && SimulationOptions.none.precompileRelocations.isEmpty && result.isRight)

  // -- reverted tx: status=false, gas consumed, state rolled back except the gas accounting ----------------------------

  test("reverted tx — INVALID opcode consumes all gas, rolls back value + state, still bumps nonce, status=false"):
    val start = BigInt(1_000_000)
    // contract code 0xFE (INVALID): exceptional halt → all runtime gas consumed, state reverted.
    val w = world(sender -> funded(start), contract -> Account.empty())
      .saveCode(contract, ByteString(Array[Byte](0xfe.toByte)))
    val tx = legacy(Some(contract), value = 500, gasLimit = 100000, gasPrice = 1)
    val res = processor.processTransaction(tx, sender, header(), spec(EvmConfig.Frontier), w, 0, chainId).toOption.get
    assert(
      !res.succeeded && res.receipt.status == ReceiptStatus.Status(false) &&
        res.gasUsed == BigInt(100000) && // exceptional halt burns the whole limit
        res.world.getBalance(contract).toBigInt == BigInt(0) && // value transfer reverted
        res.world.getBalance(sender).toBigInt == start - 100000 && // only gas charged, value returned
        res.world.getBalance(coinbase).toBigInt == BigInt(100000) && // tip on consumed gas
        res.world.getAccount(sender).map(_.nonce).contains(UInt256(1)) // nonce survives the revert
    )

  // -- validation rejects ---------------------------------------------------------------------------------------------

  test("nonce mismatch is an inclusion reject"):
    val tx = legacy(Some(recipient), nonce = 5)
    val result = processor.processTransaction(
      tx,
      sender,
      header(),
      spec(EvmConfig.Frontier),
      world(sender -> funded(1_000_000)),
      0,
      chainId
    )
    assert(result == Left(TransactionError.InvalidNonce(BigInt(0), BigInt(5))))

  test("insufficient balance for gas+value is an inclusion reject"):
    val tx = legacy(Some(recipient), value = 100, gasLimit = 21000, gasPrice = 10)
    // upfront = 21000*10 + 100 = 210100; fund below it.
    val result = processor.processTransaction(
      tx,
      sender,
      header(),
      spec(EvmConfig.Frontier),
      world(sender -> funded(1000)),
      0,
      chainId
    )
    assert(result == Left(TransactionError.InsufficientBalance(BigInt(210100), BigInt(1000))))

  test("intrinsic gas above the gas limit is an inclusion reject"):
    val tx = legacy(Some(recipient), gasLimit = 20000) // below the 21000 base
    val result = processor.processTransaction(
      tx,
      sender,
      header(),
      spec(EvmConfig.Frontier),
      world(sender -> funded(1_000_000)),
      0,
      chainId
    )
    assert(result == Left(TransactionError.IntrinsicGasTooHigh(BigInt(21000), BigInt(20000))))

  // -- EIP-7825 per-tx gas cap (2^24) — ETH-only, active at Osaka -----------------------------------------------------
  // Reference: go-ethereum `core/state_transition.go:564` (`!rules.IsAmsterdam && rules.IsOsaka && msg.GasLimit >
  // params.MaxTxGas`) + `params/protocol_params.go:31` (`MaxTxGas = 1<<24`); besu co-authority
  // `EIP_7825_TRANSACTION_GAS_LIMIT_CAP = 16_777_216L`. fukuii has no Amsterdam fork, so Osaka membership is the gate.

  test("EIP-7825 — a tx with gasLimit > 2^24 on an ETH Osaka header is an inclusion reject (GasLimitAboveCap)"):
    val overCap = TransactionProcessor.MaxTxGas + 1 // 16,777,217 = 2^24 + 1
    val tx = legacy(Some(recipient), gasLimit = overCap, gasPrice = 1)
    val result = processor.processTransaction(
      tx,
      sender,
      header(),
      spec(EvmConfig.EthOsaka),
      world(sender -> funded(20_000_000)),
      0,
      chainId
    )
    assert(result == Left(TransactionError.GasLimitAboveCap(overCap, TransactionProcessor.MaxTxGas)))

  test("EIP-7825 — the cap is dormant pre-Osaka (ETH Prague) and on ETC (Olympia): gasLimit > 2^24 is accepted"):
    // Neither `EthPrague` (pre-Osaka ETH) nor `EtcOlympia` (ETC — 7825 is not an ETC EIP) carries Eip(7825), so the cap
    // never fires: the same over-cap tx clears validation and executes.
    val overCap = TransactionProcessor.MaxTxGas + 1
    val tx = legacy(Some(recipient), gasLimit = overCap, gasPrice = 1)
    def run(cfg: EvmConfig) =
      processor.processTransaction(tx, sender, header(), spec(cfg), world(sender -> funded(20_000_000)), 0, chainId)
    assert(run(EvmConfig.EthPrague).isRight && run(EvmConfig.EtcOlympia).isRight)

  // -- gas settlement formula (EIP-3529 refund cap + EIP-7623 floor), cited to geth settleGas -------------------------

  test("EIP-3529 refund cap — quotient 5 caps the refund at gasUsedBeforeRefund/5"):
    // gasLimit 100000, gasRemaining 40000 → before-refund 60000; counter 30000 capped to 60000/5 = 12000.
    val settled = TransactionProcessor.settleGas(
      gasLimit = 100000,
      gasRemaining = 40000,
      refundCounter = 30000,
      refundQuotient = 5,
      floor = 0,
      floorActive = false
    )
    assert(settled.gasUsed == BigInt(48000) && settled.gasLeft == BigInt(52000))

  test("refund quotient 2 (pre-EIP-3529) allows twice the refund"):
    val settled = TransactionProcessor.settleGas(
      gasLimit = 100000,
      gasRemaining = 40000,
      refundCounter = 30000,
      refundQuotient = 2,
      floor = 0,
      floorActive = false
    )
    assert(settled.gasUsed == BigInt(30000) && settled.gasLeft == BigInt(70000))

  test("EIP-7623 floor — settled gasUsed is raised to the floor, gasLeft reduced by the difference"):
    // before-refund 15000 < floor 25000 → gasUsed bumped to 25000, gasLeft 85000 - (25000-15000) = 75000.
    val settled = TransactionProcessor.settleGas(
      gasLimit = 100000,
      gasRemaining = 85000,
      refundCounter = 0,
      refundQuotient = 5,
      floor = 25000,
      floorActive = true
    )
    assert(settled.gasUsed == BigInt(25000) && settled.gasLeft == BigInt(75000))

  // -- F-L4-3: EIP-4844 blob upfront balance component (go-ethereum buyGas, state_transition.go:456-465) --------------
  // Under Cancun+ the upfront balance must also cover `blobGas * blobGasFeeCap` where blobGas = GAS_PER_BLOB(2^17) *
  // #blobs. ETH-only (ETC never activates 4844 → the component is 0 on the ETC path). beacon co-signs at build.

  private def blobTx(maxFeePerBlobGas: BigInt, blobs: Int): Transaction.Blob =
    Transaction.Blob(
      chainId = chainId,
      nonce = UInt256.Zero,
      maxPriorityFeePerGas = Wei(UInt256(1)),
      maxFeePerGas = Wei(UInt256(1)),
      gasLimit = UInt256(21000),
      to = recipient,
      value = Wei.Zero,
      payload = ByteString.empty,
      accessList = Nil,
      maxFeePerBlobGas = Wei(UInt256(maxFeePerBlobGas)),
      blobVersionedHashes = List.fill(blobs)(Hash.Zero),
      signature = dummySig
    )

  test("EIP-4844 — a blob tx covering gas+value but NOT blobGas*blobGasFeeCap is rejected (InsufficientBalance)"):
    // 1 blob, maxFeePerBlobGas=1000: blobGas = 131072, blob fee = 131072000; upfront = 21000*1 + 0 + 131072000 =
    // 131093000. Fund one wei short → InsufficientBalance pinpoints the boundary includes the blob component.
    val tx = blobTx(maxFeePerBlobGas = 1000, blobs = 1)
    val result = processor.processTransaction(
      tx,
      sender,
      header(),
      spec(EvmConfig.EthCancun),
      world(sender -> funded(131_092_999)),
      0,
      chainId
    )
    assert(result == Left(TransactionError.InsufficientBalance(BigInt(131_093_000), BigInt(131_092_999))))

  test("EIP-4844 — funding the full upfront (gas + blobGas*blobGasFeeCap) clears the balance check"):
    val tx = blobTx(maxFeePerBlobGas = 1000, blobs = 1)
    val result = processor.processTransaction(
      tx,
      sender,
      header(),
      spec(EvmConfig.EthCancun),
      world(sender -> funded(131_093_000)),
      0,
      chainId
    )
    assert(result.isRight)
