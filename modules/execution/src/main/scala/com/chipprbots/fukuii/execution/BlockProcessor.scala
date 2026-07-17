package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Block
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.domain.Log
import com.chipprbots.fukuii.domain.Receipt
import com.chipprbots.fukuii.domain.SenderRecovery
import com.chipprbots.fukuii.domain.SigError
import com.chipprbots.fukuii.evm.ProposalId.Eip
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.encode
import com.chipprbots.fukuii.trie.ByteArrayEncoder
import com.chipprbots.fukuii.trie.ByteArraySerializable
import com.chipprbots.fukuii.trie.InMemoryMptStorage
import com.chipprbots.fukuii.trie.LeafChange
import com.chipprbots.fukuii.trie.MerklePatriciaTrie

/** Why a block cannot be executed or committed — a **fail-LOUD** result (never a silent skip). A tx-inclusion or
  * sender-recovery failure aborts the whole block (the block carrying it is invalid); a commitment mismatch means the
  * executed state disagrees with the header's committed roots (a consensus divergence).
  */
enum BlockExecutionError:

  /** Transaction `index` is not includable (a `Left[TransactionError]` from the per-tx engine — bad nonce, insufficient
    * balance, intrinsic/floor/cap violation). Distinct from a *reverted* tx, which is included and yields a
    * `status=false` receipt.
    */
  case TransactionInvalid(index: Int, error: TransactionError)

  /** Sender recovery failed for transaction `index` — a malformed signature (go-ethereum aborts block import on a
    * non-recoverable sender).
    */
  case SenderRecoveryFailed(index: Int, error: SigError)

  /** The executed cumulative gas used disagrees with `header.gasUsed`. */
  case GasUsedMismatch(computed: BigInt, expected: BigInt)

  /** The bloom over all executed logs disagrees with `header.logsBloom`. */
  case LogsBloomMismatch(computed: Bloom, expected: Bloom)

  /** A pre/post-execution system call (`phase`, e.g. EIP-4788 beacon-root, EIP-2935 block-hash) did not run to
    * completion — a codeless target or a VM halt. Fail LOUD (go-ethereum `panic`s / besu throws): the block depends on
    * the system call's state mutation.
    */
  case SystemCallFailed(phase: String, error: SystemCallError)

  /** The block body carries EIP-4895 withdrawals on a path with **no** withdrawals processor (the ETC/PoW path) — a
    * consensus violation, hard-rejected (core-geth accepts no withdrawals; L4 plan §9, RX-L4-13). Never a silent skip.
    */
  case WithdrawalsNotAllowed(count: Int)

  /** The EIP-7685 request phase (EIP-6110 deposit-scrape, EIP-7002/7251 queue calls) failed — a codeless queue target,
    * a VM halt, or a malformed deposit log. Fail LOUD (go-ethereum `PostExecution` returns the error; besu throws): the
    * block depends on the request phase's state mutation and commitment (RX-L4-12/14).
    */
  case RequestPhaseFailed(error: RequestError)

  /** The computed EIP-7685 `requestsHash` disagrees with `header.requestsHash` (besu `AbstractBlockProcessor:466-483`
    * fail-loud). `expected` is `None` when a Prague+ header omits the mandatory `requestsHash` field entirely.
    */
  case RequestsHashMismatch(computed: ByteString, expected: Option[ByteString])

  /** The receipts-trie root disagrees with `header.receiptsRoot`. */
  case ReceiptsRootMismatch(computed: ByteString, expected: ByteString)

  /** The committed state root disagrees with `header.stateRoot`. */
  case StateRootMismatch(computed: ByteString, expected: ByteString)

/** The computed outcome of executing a block's body — the world after the tx loop + reward + commit, and the four
  * post-execution commitments the header must match. The **producer** path fills a header from these computed roots;
  * the **verify** path ([[BlockProcessor.processBlock]]) compares them to the header it was handed — one executor, both
  * directions (L4 plan §2 v3, RX-L4-03).
  *
  * @param world
  *   the committed world ([[InMemoryWorldState.stateRootHash]] is [[stateRoot]]).
  * @param receipts
  *   the per-tx receipts, in block order.
  * @param gasUsed
  *   Σ per-tx settled gas used (== the last receipt's `cumulativeGasUsed`).
  * @param requestsHash
  *   the EIP-7685 `requestsHash` on the Prague+ ETH path (`Some`, even `RequestsHash.Empty` when no requests) — `None`
  *   on the `noOp` (PoW / pre-Prague) path, where the header carries no `requestsHash` field at all.
  */
final case class ExecutedBlock(
    world: InMemoryWorldState,
    receipts: List[Receipt],
    gasUsed: BigInt,
    logsBloom: Bloom,
    receiptsRoot: ByteString,
    stateRoot: ByteString,
    requestsHash: Option[ByteString]
)

/** The single-block execution pipeline — besu `AbstractBlockProcessor.processBlock` (a fork-agnostic loop that reads
  * all behavior off the per-fork [[ProtocolSpec]] bundle), go-ethereum `core/state_processor.go` `Process`.
  *
  * **Family-agnostic above one economics seam (R1, DEFAULT).** The apply order is besu-canonical: pre-execution system
  * calls → the tx-apply loop → withdrawals → post-execution requests → `spec.rewardScheme.rewardBlock` → persist; **the
  * reward is the LAST state mutation before commitment** (besu reward `:485` → persist `:532`). The ETC/ETH split is
  * *which* [[RewardScheme]] the bundle carries — **no `if(isPoW)` / `if(isETC)` anywhere in the loop**. ECIP-1017
  * emission goes in exactly the slot PoS's zero-reward goes.
  *
  * **P4a/P4b scope.** The tx loop, ECIP-1017 rewards ([[RewardScheme.Ecip1017RewardScheme]]) and the PoS zero-reward
  * path ([[RewardScheme.PosNoRewardScheme]]), the EIP-1559 base-fee disposition ([[FeeDisposition]] — ETH burn /
  * ECIP-1111 treasury, applied at finalize alongside the reward, P4b), persistence, and the four post-execution
  * commitment checks. Withdrawals (EIP-4895, P5a) and the EIP-7685 request phase (EIP-6110/7002/7251 `requestsHash`,
  * P5b) run post-tx-loop on the ETH path (`noOp`/absent on ETC). The per-block `BlockExecutionOutcome` + serializable
  * state-diff (R7, [[processBlockWithOutcome]]) and the atomic block+weight write ([[AtomicBlockWriter]]) are P6.
  * **Deferred:** full ommer *validation* (count ≤ 2, ancestry) is a validator/L5 concern (only reward-relevant ommer
  * handling is here).
  *
  * **R2:** stateless and immutable; the world is threaded per instance, no `object … { var … }` / `@volatile`.
  */
final class BlockProcessor(txProcessor: TransactionProcessor):

  import BlockProcessor.*

  /** **Verify** a block: [[execute]] it, then validate the four commitments against `block.header` — fail LOUD on the
    * first mismatch (besu `MainnetBlockValidator`/`BodyValidation`; go-ethereum `core/block_validator.go`
    * `ValidateState`). This is the entry point consensus (L5) calls to import a block.
    */
  def processBlock(
      spec: ProtocolSpec,
      block: Block,
      initialWorld: InMemoryWorldState,
      chainId: ChainId
  ): Either[BlockExecutionError, ExecutedBlock] =
    execute(spec, block, initialWorld, chainId).flatMap(executed =>
      validateCommitments(executed, block.header).map(_ => executed)
    )

  /** **Import a block and emit its per-block [[BlockExecutionOutcome]]** — the R7 seam L4 hands *up* to L5's
    * branch-import driver (L4 plan §1/§5, RX-L4-15). [[processBlock]] runs; on success this wraps the block + a
    * byte-reproducible [[BlockStateDiff]] as `Executed`, on any execution/commitment failure it emits `RolledBack` (the
    * world/accumulator is discarded, besu `reset()`). **L5** aggregates a stream of these outcomes into the reorg-aware
    * `ChainNotification` segment stream — L4 does not decide reorgs or define that wire ADT.
    *
    * **Zero-cost baseline preserved.** The diff-collecting [[MutationSink.Recording]] is installed **only** here (the
    * R7 path); [[processBlock]]/[[execute]] callers with no consumer never install it and pay nothing (the world's
    * `mutations` stays [[MutationSink.NoTracking]], a branch-free no-op — RX-L4-16, geth `state_processor.go:77`).
    */
  def processBlockWithOutcome(
      spec: ProtocolSpec,
      block: Block,
      initialWorld: InMemoryWorldState,
      chainId: ChainId
  ): BlockExecutionOutcome =
    val sink = new MutationSink.Recording
    val trackedWorld = initialWorld.withMutationSink(sink)
    processBlock(spec, block, trackedWorld, chainId) match
      case Left(_) => BlockExecutionOutcome.RolledBack(block)
      case Right(executed) =>
        BlockExecutionOutcome.Executed(block, buildStateDiff(initialWorld, executed.world, sink, block, spec))

  /** Compose the per-block world-state envelope diff (besu `BonsaiTrieLog {prior,updated}` shape) from the accumulated
    * touched-key set — reading each touched account/slot/code's leaf on the `baseline` (pre-block) and `committed`
    * (post-persist) sides. Computed from the same committed state that produced the state root (besu ties the two at
    * `persist`), so the diff and the root cannot disagree. Net-unchanged entries are dropped; the result is canonically
    * ordered (byte-reproducible, §7 DoD).
    *
    * The [[MutationReason]] is attributed **by address role** (a coarse per-block-phase attribution, PROVISIONAL): the
    * ECIP-1111 treasury → `FeeBurn`, an EIP-4895 withdrawal address → `Withdrawal`, the coinbase / an ommer beneficiary
    * → `Reward`, everything else (tx-loop transfers) → `Transfer`. See [[MutationReason]] for why this net-diff
    * attribution folds the coinbase tip into `Reward`.
    */
  private def buildStateDiff(
      baseline: InMemoryWorldState,
      committed: InMemoryWorldState,
      sink: MutationSink.Recording,
      block: Block,
      spec: ProtocolSpec
  ): BlockStateDiff =
    val treasury: Option[Address] = spec.feeDisposition match
      case FeeDisposition.RedirectToTreasury(t) => Some(t)
      case _                                    => None
    val withdrawalAddrs: Set[Address] = block.body.withdrawals.getOrElse(Nil).map(_.address).toSet
    val coinbase: Address = block.header.beneficiary
    val ommerBeneficiaries: Set[Address] = block.body.uncleNodesList.map(_.beneficiary).toSet

    def reasonFor(a: Address): MutationReason =
      if treasury.contains(a) then MutationReason.FeeBurn
      else if withdrawalAddrs.contains(a) then MutationReason.Withdrawal
      else if a == coinbase || ommerBeneficiaries.contains(a) then MutationReason.Reward
      else MutationReason.Transfer

    def accountLeaf(w: InMemoryWorldState, a: Address): Option[ByteString] =
      w.getAccount(a).map(acc => ByteString(StateMpt.accountSerializer.toBytes(acc)))
    def slotLeaf(w: InMemoryWorldState, a: Address, slot: UInt256): Option[ByteString] =
      val v = w.getStorage(a).load(slot)
      if v == BigInt(0) then None else Some(ByteString(StateMpt.storageValueSerializer.toBytes(v)))
    def codeLeaf(w: InMemoryWorldState, a: Address): Option[ByteString] =
      val c = w.getCode(a)
      if c.isEmpty then None else Some(c)

    val entries = sink.touchedAddresses.iterator.flatMap { a =>
      val accountChange = LeafChange(accountLeaf(baseline, a), accountLeaf(committed, a))
      val storageChanges = sink
        .touchedSlots(a)
        .iterator
        .flatMap { slot =>
          val change = LeafChange(slotLeaf(baseline, a, slot), slotLeaf(committed, a, slot))
          if change.isUnchanged then None else Some(slot -> change)
        }
        .toVector
        .sortBy(_._1)(using ByteOrder.slot)
      val codeChange =
        if sink.touchedCode(a) then
          val change = LeafChange(codeLeaf(baseline, a), codeLeaf(committed, a))
          if change.isUnchanged then None else Some(change)
        else None
      val entry = AccountStateDiff(a, accountChange, storageChanges, codeChange, reasonFor(a))
      if entry.isUnchanged then None else Some(entry)
    }
    BlockStateDiff.of(entries.toVector)

  /** **Execute** a block's body without comparing to the header — the compute path shared by verify and produce. Runs
    * the besu-canonical apply order and returns the computed [[ExecutedBlock]] (committed world + the four
    * commitments). Only a tx-inclusion / sender-recovery failure can return a `Left` here.
    */
  def execute(
      spec: ProtocolSpec,
      block: Block,
      initialWorld: InMemoryWorldState,
      chainId: ChainId
  ): Either[BlockExecutionError, ExecutedBlock] =
    for
      // Pre-execution system calls — EIP-4788 beacon-root + EIP-2935 block-hash population — as SystemAddress/30M
      // pseudo-txs BEFORE the tx loop (`NoPreExecution` on the PoW / pre-Cancun path). L3's BLOCKHASH later reads the
      // slots EIP-2935 populates here (go-ethereum PreExecution :144-167; besu :265).
      worldAfterPreExec <- spec.preExecution.process(block.header, spec.evmConfig, initialWorld, chainId)
      loopState <- applyTransactions(spec, block.header, block.body.transactionList, worldAfterPreExec, chainId)
      TxLoopState(worldAfterTxs, receipts, gasUsed, logs) = loopState
      // Withdrawals (EIP-4895) — post-loop, BEFORE requests+reward (besu order, RX-L4-11), OUTSIDE the reward seam
      // (validator addresses ≠ coinbase → never double-credited with issuance, RX-L4-13). ETC hard-rejects.
      worldAfterWithdrawals <- applyWithdrawals(spec, block, worldAfterTxs)
      // EIP-7685 requests (EIP-6110 deposit-scrape, EIP-7002/7251 queue calls) — post-withdrawals, BEFORE reward (besu
      // order). `noOp` on the PoW / pre-Prague path (no requests, no requestsHash). The deposit-scrape reads `logs`; the
      // 7002/7251 calls mutate the queue contracts, so the world is threaded on (RX-L4-12/14).
      requestPhase <- applyRequests(spec, block.header, worldAfterWithdrawals, logs, chainId)
    yield
      val (worldAfterRequests, requests) = requestPhase
      // requestsHash = sha256(sha256(req_0)‖…) over non-empty requests, `Some` even when empty (RequestsHash.Empty) on
      // the Prague+ path; `None` on the noOp path (ETC header carries no requestsHash field). Computed here, validated
      // against header.requestsHash in validateCommitments (besu AbstractBlockProcessor:466-483).
      val computedRequestsHash = if spec.requests.isNoOp then None else Some(RequestsHash.compute(requests))
      // EIP-1559 base-fee disposition (ETH burn / ECIP-1111 treasury) — the lump-sum amount `gasUsed * baseFee`
      // computed ONCE (baseFee is block-constant, so this equals Σ per-tx base-fee charges; RX-L4-10 SHARPENS (ii)).
      // `Absent`/`Burn` mutate nothing (the base fee was left uncredited by the tx engine); `RedirectToTreasury`
      // credits the treasury additively. Uses the *computed* gasUsed (correct on the produce path where header.gasUsed
      // is unfilled). The treasury credit and the miner reward are disjoint additive addBalance's → commutative
      // (RX-L4-10 SHARPENS (i)); the deterministic order (disposition then reward) matches the ECIP-1111 draft :49-55.
      val baseFeeAmount = gasUsed * block.header.baseFeePerGas.getOrElse(BigInt(0))
      val disposed = spec.feeDisposition.dispose(worldAfterRequests, baseFeeAmount)
      // Reward is the LAST state mutation before commitment (besu :485 → persist :532). The family split is which
      // RewardScheme the bundle carries — no if(isPoW) here.
      val rewarded = spec.rewardScheme.rewardBlock(disposed, block.header, block.body.uncleNodesList)
      val committed = rewarded.persist
      ExecutedBlock(
        world = committed,
        receipts = receipts,
        gasUsed = gasUsed,
        logsBloom = Bloom.of(logs),
        receiptsRoot = receiptsRootOf(receipts),
        stateRoot = committed.stateRootHash,
        requestsHash = computedRequestsHash
      )

  /** Apply the block body's EIP-4895 withdrawals through the bundle's [[WithdrawalsProcessor]] — or **hard-reject** if
    * withdrawals are present with no processor (the ETC/PoW path). besu `AbstractBlockProcessor:419` gates on
    * `processor.isPresent && withdrawals.isPresent`; fukuii adds the fail-LOUD reject for the illegal combination
    * (withdrawals on a family that forbids them, RX-L4-13).
    */
  private def applyWithdrawals(
      spec: ProtocolSpec,
      block: Block,
      world: InMemoryWorldState
  ): Either[BlockExecutionError, InMemoryWorldState] =
    (spec.withdrawals, block.body.withdrawals) match
      case (Some(processor), Some(withdrawals)) => Right(processor.processWithdrawals(withdrawals, world))
      case (Some(_), None)                      => Right(world)
      case (None, None)                         => Right(world)
      case (None, Some(withdrawals))            => Left(BlockExecutionError.WithdrawalsNotAllowed(withdrawals.size))

  /** Run the bundle's EIP-7685 [[RequestProcessors]] coordinator (`noOp` on PoW / pre-Prague → `(world, Nil)`),
    * threading the world through the EIP-7002/7251 queue calls and collecting the requests in `RequestType` order. A
    * request-phase failure is mapped to a fail-LOUD [[BlockExecutionError.RequestPhaseFailed]] (RX-L4-12/14).
    */
  private def applyRequests(
      spec: ProtocolSpec,
      header: BlockHeader,
      world: InMemoryWorldState,
      logs: List[Log],
      chainId: ChainId
  ): Either[BlockExecutionError, (InMemoryWorldState, List[Request])] =
    spec.requests
      .process(RequestContext(header, spec.evmConfig, world, chainId, logs))
      .left
      .map(BlockExecutionError.RequestPhaseFailed(_))

  /** The tx-apply loop: fold [[TransactionProcessor.processTransaction]] over the block's transactions, threading the
    * world and accumulating cumulative gas used, the per-tx receipts (in order), and every log. Sender recovery runs
    * per tx (its homestead gating is the fork's `Eip(2)` activation — H-1); a recovery or inclusion failure aborts the
    * whole block. Short-circuits on the first `Left`.
    */
  private def applyTransactions(
      spec: ProtocolSpec,
      header: BlockHeader,
      txs: List[com.chipprbots.fukuii.domain.Transaction],
      initialWorld: InMemoryWorldState,
      chainId: ChainId
  ): Either[BlockExecutionError, TxLoopState] =
    val homestead = spec.evmConfig.isActive(Eip(2))
    txs.zipWithIndex.foldLeft[Either[BlockExecutionError, TxLoopState]](Right(TxLoopState.initial(initialWorld))) {
      case (left @ Left(_), _) => left
      case (Right(loopState), (tx, i)) =>
        SenderRecovery.getSender(tx, homestead) match
          case Left(sigErr) => Left(BlockExecutionError.SenderRecoveryFailed(i, sigErr))
          case Right(sender) =>
            txProcessor.processTransaction(tx, sender, header, spec, loopState.world, loopState.gasUsed, chainId) match
              case Left(txErr)   => Left(BlockExecutionError.TransactionInvalid(i, txErr))
              case Right(result) => Right(loopState.append(result))
    }

  /** Validate the four post-execution commitments against the header, in **go-ethereum's short-circuit order** with the
    * **state root checked LAST** (go-ethereum `core/block_validator.go` `ValidateState`: `gasUsed` → bloom → receipts
    * root → state root). The order is a deliberate pick (L4 plan §6 note) — the state root is the most expensive to
    * compute-and-compare and the strongest commitment, so a cheaper mismatch (gas/bloom/receipts) reports first.
    */
  private def validateCommitments(executed: ExecutedBlock, header: BlockHeader): Either[BlockExecutionError, Unit] =
    if executed.gasUsed != BigInt(header.gasUsed) then
      Left(BlockExecutionError.GasUsedMismatch(executed.gasUsed, BigInt(header.gasUsed)))
    else if executed.logsBloom != header.logsBloom then
      Left(BlockExecutionError.LogsBloomMismatch(executed.logsBloom, header.logsBloom))
    else if executed.receiptsRoot != header.receiptsRoot.bytes then
      Left(BlockExecutionError.ReceiptsRootMismatch(executed.receiptsRoot, header.receiptsRoot.bytes))
    else
      requestsHashError(executed, header) match
        case Some(error) => Left(error)
        case None =>
          if executed.stateRoot != header.stateRoot.bytes then
            Left(BlockExecutionError.StateRootMismatch(executed.stateRoot, header.stateRoot.bytes))
          else Right(())

  /** The EIP-7685 `requestsHash` commitment check — validated before the state root (state root stays LAST). On the
    * `noOp` path ([[ExecutedBlock.requestsHash]] `None`) there is no `requestsHash` to validate (an ETC header carries
    * none; the header-field-legality check is L1/L5's, not this phase's). On the Prague+ path the computed hash must
    * equal `header.requestsHash` — a `None` header field there (a Prague header missing the mandatory commitment) is a
    * mismatch against the computed value (besu `AbstractBlockProcessor:466-483` fail-loud).
    */
  private def requestsHashError(executed: ExecutedBlock, header: BlockHeader): Option[BlockExecutionError] =
    executed.requestsHash match
      case None => None
      case Some(computed) =>
        val expected = header.requestsHash.map(_.bytes)
        if expected.contains(computed) then None
        else Some(BlockExecutionError.RequestsHashMismatch(computed, expected))

object BlockProcessor:

  /** The tx-loop accumulator — the world threaded so far, receipts in block order, Σ gas used, all logs in order. */
  final private case class TxLoopState(
      world: InMemoryWorldState,
      receipts: List[Receipt],
      gasUsed: BigInt,
      logs: List[Log]
  ):
    def append(result: TransactionResult): TxLoopState =
      TxLoopState(
        world = result.world,
        receipts = receipts :+ result.receipt,
        gasUsed = gasUsed + result.gasUsed,
        logs = logs ++ result.logs
      )

  private object TxLoopState:
    def initial(world: InMemoryWorldState): TxLoopState = TxLoopState(world, Nil, BigInt(0), Nil)

  /** The receipts-trie root — an MPT over `RLP(index) → consensusEncoding(receipt)` (go-ethereum
    * `types.DeriveSha`/`core/types/hashing.go`: key `rlp(uint(i))`, value the EIP-2718 receipt encoding — legacy
    * `RLP(body)`, typed `typeByte ‖ RLP(body)`). A **non-secure** trie (keys are the raw RLP index bytes, NOT hashed).
    * An empty receipts list yields the empty-trie root, matching an empty block's `receiptsRoot`. The trie is built
    * over an ephemeral in-memory node store and read via `getRootHash` on the resident tree (no persistence needed —
    * the same pattern [[InMemoryAccountStorage.storageRoot]] uses).
    */
  private def receiptsRootOf(receipts: Seq[Receipt]): ByteString =
    given indexKeyEncoder: ByteArrayEncoder[BigInt] with
      def toBytes(i: BigInt): Array[Byte] = encode(i)
    given receiptValueSerializer: ByteArraySerializable[Receipt] with
      def toBytes(r: Receipt): Array[Byte] = encode(summon[RLPCodec[Receipt]].encode(r))
      def fromBytes(bytes: Array[Byte]): Receipt = Receipt.decode(bytes)
    val trie = receipts.zipWithIndex.foldLeft(MerklePatriciaTrie[BigInt, Receipt](new InMemoryMptStorage)) {
      case (t, (receipt, i)) => t.put(BigInt(i), receipt)
    }
    trie.getRootHash
