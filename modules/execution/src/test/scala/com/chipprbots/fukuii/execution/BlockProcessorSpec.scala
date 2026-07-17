package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.ECDSASignature
import com.chipprbots.fukuii.crypto.pubKeyFromPrvKey
import com.chipprbots.fukuii.crypto.pubKeyToAddress
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.Block
import com.chipprbots.fukuii.domain.BlockBody
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.domain.SenderRecovery
import com.chipprbots.fukuii.domain.Transaction
import com.chipprbots.fukuii.domain.Wei
import com.chipprbots.fukuii.evm.EvmConfig
import com.chipprbots.fukuii.evm.EvmInterpreter
import com.chipprbots.fukuii.storage.EphemDataSource
import com.chipprbots.fukuii.trie.InMemoryMptStorage
import com.chipprbots.fukuii.trie.MptNode

/** L4 P4a — the [[BlockProcessor]] pipeline: the family-agnostic tx-apply loop, the ECIP-1017 reward seam, persistence,
  * and the four post-execution commitment checks (fail LOUD, go-ethereum state-root-last order). The family split is
  * *which* [[RewardScheme]] the bundle carries — asserted here by a PoW-vs-PoS coinbase-balance difference with **no
  * branch** in the loop.
  */
class BlockProcessorSpec extends AnyFunSuite:

  private val chainId: ChainId = ChainId(61)

  private val prvKey: ByteString = ByteString(Hex.decode("0x" + "11" * 32))
  private val sender: Address = pubKeyToAddress(pubKeyFromPrvKey(prvKey))
  private def addr(b: Byte): Address = Address(ByteString(Array.fill[Byte](Address.Length)(b)))
  private val recipient: Address = addr(0x22)
  private val coinbase: Address = addr(0x33)

  private val interpreter = new EvmInterpreter[InMemoryWorldState, InMemoryAccountStorage]()
  private val processor = new BlockProcessor(new TransactionProcessor(interpreter))

  private def powSpec: ProtocolSpec =
    ProtocolSpec(
      EvmConfig.EtcOlympia,
      RewardScheme.Ecip1017RewardScheme(),
      RequestProcessors.noOp,
      None,
      FeeDisposition.Absent
    )

  private def posSpec: ProtocolSpec =
    ProtocolSpec(
      EvmConfig.EtcOlympia,
      RewardScheme.PosNoRewardScheme,
      RequestProcessors.noOp,
      None,
      FeeDisposition.Absent
    )

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

  private val treasury: Address = addr(0x44)

  /** A funded [[sender]]-signed EIP-155 (chainId 61) legacy value transfer of `value` wei at `nonce`, with an explicit
    * `gasPrice` (so a base-fee/tip split can be exercised: `effectiveTip = gasPrice - baseFee`).
    */
  private def signedTransfer(nonce: BigInt, value: BigInt = 1, gasPrice: BigInt = 1): Transaction.Legacy =
    val base: Transaction.Legacy = Transaction.Legacy(
      nonce = UInt256(nonce),
      gasPrice = Wei(UInt256(gasPrice)),
      gasLimit = UInt256(21000),
      to = Some(recipient),
      value = Wei(UInt256(value)),
      payload = ByteString.empty,
      signature = ECDSASignature(BigInt(1), BigInt(1), BigInt(35 + 2 * 61)) // placeholder v for the EIP-155 sighash
    )
    val sh = SenderRecovery.signingHash(base)
    val sig = ECDSASignature.sign(ByteString(sh), prvKey)
    val v155 = BigInt(35 + 2 * 61 + (sig.v.toInt - 27))
    base.copy(signature = ECDSASignature(sig.r, sig.s, v155))

  private def header(number: BigInt): BlockHeader =
    BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = coinbase,
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 1,
      number = number,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = 0,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty
    )

  private def block(number: BigInt, txs: List[Transaction]): Block =
    Block(header(number), BlockBody(txs, Nil, None))

  /** Commit an executed block's computed roots back into its header — the producer path (fill the header from
    * [[ExecutedBlock]]), so [[BlockProcessor.processBlock]] then verifies against a self-consistent header.
    */
  private def committed(b: Block, e: ExecutedBlock): Block =
    b.copy(header =
      b.header.copy(
        gasUsed = e.gasUsed.toLong,
        receiptsRoot = Hash(e.receiptsRoot),
        stateRoot = Hash(e.stateRoot),
        logsBloom = e.logsBloom
      )
    )

  // -- pipeline ------------------------------------------------------------------------------------------------------

  test("execute — N txs fold cumulativeGasUsed monotonically and produce N receipts"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val b = block(1, List(signedTransfer(0), signedTransfer(1)))
    val executed = processor.execute(powSpec, b, w, chainId).toOption.get
    assert(
      executed.receipts.length == 2 &&
        executed.receipts.map(_.cumulativeGasUsed) == List(21000L, 42000L) && // monotonic
        executed.gasUsed == BigInt(42000)
    )

  test("execute — the committed state root is deterministic across runs"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val b = block(1, List(signedTransfer(0)))
    val r1 = processor.execute(powSpec, b, w, chainId).toOption.get
    val r2 = processor.execute(powSpec, b, w, chainId).toOption.get
    assert(r1.stateRoot == r2.stateRoot)

  test("processBlock — a self-consistent (producer-filled) header verifies GREEN"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val b = block(1, List(signedTransfer(0)))
    val executed = processor.execute(powSpec, b, w, chainId).toOption.get
    assert(processor.processBlock(powSpec, committed(b, executed), w, chainId).isRight)

  // -- commitment checks fail LOUD (go-ethereum ValidateState order — state root LAST) -------------------------------

  test("processBlock — a wrong gasUsed fails LOUD with GasUsedMismatch"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val b = block(1, List(signedTransfer(0)))
    val executed = processor.execute(powSpec, b, w, chainId).toOption.get
    val bad = committed(b, executed)
    val tampered = bad.copy(header = bad.header.copy(gasUsed = 99999L))
    processor.processBlock(powSpec, tampered, w, chainId) match
      case Left(_: BlockExecutionError.GasUsedMismatch) => succeed
      case other                                        => fail(s"expected GasUsedMismatch, got $other")

  test("processBlock — a wrong receiptsRoot fails LOUD with ReceiptsRootMismatch"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val b = block(1, List(signedTransfer(0)))
    val executed = processor.execute(powSpec, b, w, chainId).toOption.get
    val bad = committed(b, executed)
    val tampered = bad.copy(header = bad.header.copy(receiptsRoot = Hash.Zero))
    processor.processBlock(powSpec, tampered, w, chainId) match
      case Left(_: BlockExecutionError.ReceiptsRootMismatch) => succeed
      case other                                             => fail(s"expected ReceiptsRootMismatch, got $other")

  test("processBlock — a wrong stateRoot fails LOUD with StateRootMismatch (checked LAST, after gas/bloom/receipts)"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val b = block(1, List(signedTransfer(0)))
    val executed = processor.execute(powSpec, b, w, chainId).toOption.get
    val bad = committed(b, executed)
    // gasUsed, bloom, and receiptsRoot are all correct; only the state root is wrong → StateRootMismatch, not another.
    val tampered = bad.copy(header = bad.header.copy(stateRoot = Hash.Zero))
    processor.processBlock(powSpec, tampered, w, chainId) match
      case Left(_: BlockExecutionError.StateRootMismatch) => succeed
      case other                                          => fail(s"expected StateRootMismatch, got $other")

  // -- the family split is the RewardScheme, not a branch (RX-L4-01) -------------------------------------------------

  test("PoW vs PoS — the coinbase difference is exactly the ECIP-1017 era-0 reward (5 ETH), no if(isPoW) in the loop"):
    val startBalance = BigInt(10).pow(19)
    val pow = processor
      .execute(powSpec, block(1, List(signedTransfer(0))), world(sender -> funded(startBalance)), chainId)
      .toOption
      .get
    val pos = processor
      .execute(posSpec, block(1, List(signedTransfer(0))), world(sender -> funded(startBalance)), chainId)
      .toOption
      .get
    val powCoinbase = pow.world.getBalance(coinbase).toBigInt
    val posCoinbase = pos.world.getBalance(coinbase).toBigInt
    // both receive the 21000-wei tip; only the PoW path adds the 5 ETH block reward.
    assert(posCoinbase == BigInt(21000) && powCoinbase - posCoinbase == BigInt(5) * BigInt(10).pow(18))

  test("sender recovery failure aborts the block (malformed signature)"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val badTx = signedTransfer(0).copy(signature = ECDSASignature(BigInt(0), BigInt(0), BigInt(27))) // r=s=0 invalid
    val b = block(1, List(badTx))
    processor.execute(powSpec, b, w, chainId) match
      case Left(_: BlockExecutionError.SenderRecoveryFailed) => succeed
      case other                                             => fail(s"expected SenderRecoveryFailed, got $other")

  // -- P4b: EIP-1559 base-fee disposition — ETH burn vs ECIP-1111 treasury (RX-L4-09/10) -----------------------------
  //
  // A base-fee/tip split: baseFee=10, gasPrice=13 ⇒ effectiveTip = 3. One 21000-gas tx ⇒ the lump-sum base-fee amount
  // is 21000 * 10 = 210000; the coinbase tip is 21000 * 3 = 63000. The tests isolate the disposition with the PoS
  // zero-reward scheme (no 5-ETH issuance noise); the treasury credit and miner reward are proven commutative
  // separately with the ECIP-1017 scheme. Amounts cited to the ECIP-1111 draft :49-55 (`gasUsed * baseFee → treasury`).

  private def blockWithBaseFee(number: BigInt, txs: List[Transaction], baseFee: BigInt): Block =
    Block(header(number).copy(baseFeePerGas = Some(baseFee)), BlockBody(txs, Nil, None))

  private def treasurySpec(scheme: RewardScheme): ProtocolSpec =
    ProtocolSpec(
      EvmConfig.EtcOlympia,
      scheme,
      RequestProcessors.noOp,
      None,
      FeeDisposition.RedirectToTreasury(treasury)
    )
  private def burnSpec(scheme: RewardScheme): ProtocolSpec =
    ProtocolSpec(EvmConfig.EtcOlympia, scheme, RequestProcessors.noOp, None, FeeDisposition.Burn)

  test("ECIP-1111 — the treasury is credited exactly gasUsed * baseFee (lump sum) on an ETC Olympia block"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val b = blockWithBaseFee(1, List(signedTransfer(0, gasPrice = 13)), baseFee = 10)
    val e = processor.execute(treasurySpec(RewardScheme.PosNoRewardScheme), b, w, chainId).toOption.get
    assert(e.world.getBalance(treasury).toBigInt == BigInt(21000) * 10) // 210000

  test("ECIP-1111 — value-conserving: total supply is unchanged (base fee redirected, not removed)"):
    val start = BigInt(10).pow(19)
    val w = world(sender -> funded(start))
    val b = blockWithBaseFee(1, List(signedTransfer(0, value = 1, gasPrice = 13)), baseFee = 10)
    val e = processor.execute(treasurySpec(RewardScheme.PosNoRewardScheme), b, w, chainId).toOption.get
    val total = List(sender, recipient, coinbase, treasury).map(e.world.getBalance(_).toBigInt).sum
    // what leaves the sender as base fee (gasUsed*baseFee) enters the treasury — supply is conserved.
    assert(total == start && e.world.getBalance(treasury).toBigInt == BigInt(21000) * 10)

  test("EIP-1559 (ETH) — the base fee is burned: coinbase receives only the tip, nothing else credited"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val b = blockWithBaseFee(1, List(signedTransfer(0, gasPrice = 13)), baseFee = 10)
    val e = processor.execute(burnSpec(RewardScheme.PosNoRewardScheme), b, w, chainId).toOption.get
    // burn = no extra mutation: the coinbase got only gasUsed*effectiveTip; the base-fee portion was left uncredited.
    assert(e.world.getBalance(coinbase).toBigInt == BigInt(21000) * 3 && e.world.getBalance(treasury).toBigInt == 0)

  test("burn (ETH) vs treasury (ETC) diverge on the same base-fee input — treasury grows on ETC, supply drops on ETH"):
    val start = BigInt(10).pow(19)
    def run(spec: ProtocolSpec): ExecutedBlock =
      val w = world(sender -> funded(start))
      val b = blockWithBaseFee(1, List(signedTransfer(0, value = 1, gasPrice = 13)), baseFee = 10)
      processor.execute(spec, b, w, chainId).toOption.get
    val etc = run(treasurySpec(RewardScheme.PosNoRewardScheme))
    val eth = run(burnSpec(RewardScheme.PosNoRewardScheme))
    val accounts = List(sender, recipient, coinbase, treasury)
    val etcSupply = accounts.map(etc.world.getBalance(_).toBigInt).sum
    val ethSupply = accounts.map(eth.world.getBalance(_).toBigInt).sum
    val baseFeeAmount = BigInt(21000) * 10
    assert(
      etc.world.getBalance(treasury).toBigInt == baseFeeAmount && // ETC: treasury grows
        eth.world.getBalance(treasury).toBigInt == 0 && // ETH: nothing routed
        ethSupply == etcSupply - baseFeeAmount // ETH: the base fee is removed from supply
    )

  test("ECIP-1111 — treasury credit and miner reward commute: the same state root either finalize order (RX-L4-10 i)"):
    // The consensus axes are the amount and the floor, NOT the sequence: treasury and miner are distinct addresses and
    // both credits are additive addBalance's, so disposition-then-reward and reward-then-disposition yield one root.
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val hdr = header(1).copy(baseFeePerGas = Some(10))
    val disp = FeeDisposition.RedirectToTreasury(treasury)
    val scheme = RewardScheme.Ecip1017RewardScheme()
    val amount = BigInt(21000) * 10
    val dispositionThenReward = scheme.rewardBlock(disp.dispose(w, amount), hdr, Nil).persist
    val rewardThenDisposition = disp.dispose(scheme.rewardBlock(w, hdr, Nil), amount).persist
    assert(dispositionThenReward.stateRootHash == rewardThenDisposition.stateRootHash)
