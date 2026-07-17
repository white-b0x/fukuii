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

/** L4 P6 — the R7 spine: the per-block [[BlockExecutionOutcome]], the serializable [[BlockStateDiff]]
  * (`{prior,updated}` envelope over L2 `LeafChange`), the [[MutationReason]] attribution, and the branch-free zero-cost
  * baseline ([[MutationSink.NoTracking]] not installed). Structural, not byte-consensus — the state root is already
  * computed by P0/P4; this asserts the *shape* of the emitted per-block outcome L5's branch-import driver consumes.
  */
class BlockExecutionOutcomeSpec extends AnyFunSuite:

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
      PreExecutionProcessor.NoPreExecution,
      RewardScheme.Ecip1017RewardScheme(),
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

  private def signedTransfer(nonce: BigInt, value: BigInt = 1, gasPrice: BigInt = 1): Transaction.Legacy =
    val base: Transaction.Legacy = Transaction.Legacy(
      nonce = UInt256(nonce),
      gasPrice = Wei(UInt256(gasPrice)),
      gasLimit = UInt256(21000),
      to = Some(recipient),
      value = Wei(UInt256(value)),
      payload = ByteString.empty,
      signature = ECDSASignature(BigInt(1), BigInt(1), BigInt(35 + 2 * 61))
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

  /** Producer-fill a header from a computed [[ExecutedBlock]] so [[BlockProcessor.processBlockWithOutcome]] verifies it
    * GREEN and emits `Executed`.
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

  private def executedOutcome(): (Block, BlockStateDiff) =
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val b = block(1, List(signedTransfer(0)))
    val executed = processor.execute(powSpec, b, w, chainId).toOption.get
    processor.processBlockWithOutcome(powSpec, committed(b, executed), w, chainId) match
      case BlockExecutionOutcome.Executed(blk, diff) => (blk, diff)
      case other                                     => fail(s"expected Executed, got $other")

  // -- outcome ---------------------------------------------------------------------------------------------------------

  test("processBlockWithOutcome — an executed block emits exactly one Executed(block, stateDiff)"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val b = block(1, List(signedTransfer(0)))
    val executed = processor.execute(powSpec, b, w, chainId).toOption.get
    val cb = committed(b, executed)
    processor.processBlockWithOutcome(powSpec, cb, w, chainId) match
      case BlockExecutionOutcome.Executed(blk, _) => assert(blk == cb)
      case other                                  => fail(s"expected Executed, got $other")

  test("processBlockWithOutcome — a block whose commitment fails emits RolledBack (world discarded)"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    // an unfilled header (gasUsed=0, stateRoot=Zero) → the commitment check fails → RolledBack, no diff emitted.
    val b = block(1, List(signedTransfer(0)))
    assert(processor.processBlockWithOutcome(powSpec, b, w, chainId) == BlockExecutionOutcome.RolledBack(b))

  test("processBlockWithOutcome — a block with a sender-recovery failure emits RolledBack"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val badTx = signedTransfer(0).copy(signature = ECDSASignature(BigInt(0), BigInt(0), BigInt(27)))
    val b = block(1, List(badTx))
    assert(processor.processBlockWithOutcome(powSpec, b, w, chainId) == BlockExecutionOutcome.RolledBack(b))

  // -- state-diff: byte-reproducible + {prior,updated} + reasons -------------------------------------------------------

  test("the state-diff is byte-reproducible — the same block yields an identical BlockStateDiff"):
    val (_, d1) = executedOutcome()
    val (_, d2) = executedOutcome()
    assert(d1 == d2)

  test("state-diff — a fresh recipient carries a creation LeafChange (prior absent, updated present)"):
    val (_, diff) = executedOutcome()
    val entry = diff.accounts.find(_.address == recipient).getOrElse(fail("no recipient entry"))
    assert(entry.account.prior.isEmpty && entry.account.updated.isDefined && !entry.account.isUnchanged)

  test("state-diff — the sender's balance/nonce change is an update LeafChange (prior and updated both present)"):
    val (_, diff) = executedOutcome()
    val entry = diff.accounts.find(_.address == sender).getOrElse(fail("no sender entry"))
    assert(entry.account.prior.isDefined && entry.account.updated.isDefined && !entry.account.isUnchanged)

  test("MutationReason — the coinbase (issuance target) is tagged Reward; a plain recipient is tagged Transfer"):
    val (_, diff) = executedOutcome()
    val coinbaseReason = diff.accounts.find(_.address == coinbase).map(_.reason)
    val recipientReason = diff.accounts.find(_.address == recipient).map(_.reason)
    val senderReason = diff.accounts.find(_.address == sender).map(_.reason)
    assert(
      coinbaseReason.contains(MutationReason.Reward) &&
        recipientReason.contains(MutationReason.Transfer) &&
        senderReason.contains(MutationReason.Transfer)
    )

  test("MutationReason — the ECIP-1111 treasury credit is tagged FeeBurn"):
    val treasury = addr(0x44)
    val treasurySpec =
      ProtocolSpec(
        EvmConfig.EtcOlympia,
        PreExecutionProcessor.NoPreExecution,
        RewardScheme.PosNoRewardScheme,
        RequestProcessors.noOp,
        None,
        FeeDisposition.RedirectToTreasury(treasury)
      )
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val tx = signedTransfer(0, gasPrice = 13)
    val b = Block(header(1).copy(baseFeePerGas = Some(10)), BlockBody(List(tx), Nil, None))
    val executed = processor.execute(treasurySpec, b, w, chainId).toOption.get
    processor.processBlockWithOutcome(treasurySpec, committed(b, executed), w, chainId) match
      case BlockExecutionOutcome.Executed(_, diff) =>
        assert(diff.accounts.find(_.address == treasury).map(_.reason).contains(MutationReason.FeeBurn))
      case other => fail(s"expected Executed, got $other")

  // -- zero-cost baseline (RX-L4-16 — the wrapper is not installed) ----------------------------------------------------

  test("zero-cost — a baseline world's mutation sink is NoTracking (the tagging wrapper is not installed)"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    // the factory installs NoTracking; the branch-free baseline never swaps it.
    assert(w.mutations eq MutationSink.NoTracking)

  test("zero-cost — the baseline execute path never installs a Recording sink on the committed world"):
    val w = world(sender -> funded(BigInt(10).pow(19)))
    val executed = processor.execute(powSpec, block(1, List(signedTransfer(0))), w, chainId).toOption.get
    // execute (no consumer) threads NoTracking end-to-end; only processBlockWithOutcome installs Recording.
    assert(executed.world.mutations eq MutationSink.NoTracking)
