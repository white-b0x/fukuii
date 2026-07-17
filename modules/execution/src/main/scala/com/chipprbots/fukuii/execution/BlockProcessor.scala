package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

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
  */
final case class ExecutedBlock(
    world: InMemoryWorldState,
    receipts: List[Receipt],
    gasUsed: BigInt,
    logsBloom: Bloom,
    receiptsRoot: ByteString,
    stateRoot: ByteString
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
  * commitment checks. **Deferred:** pre-execution system calls (4788/2935) are a `noOp` hook filled at P5; withdrawals
  * (EIP-4895) and EIP-7685 requests are absent (P5, beacon); full ommer *validation* (count ≤ 2, ancestry) is a
  * validator/L5 concern (only reward-relevant ommer handling is here); the atomic block+weight write and the per-block
  * `BlockExecutionOutcome` + serializable state-diff (R7) are later phases.
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
    yield
      // [post-execution requests — EIP-7002/7251/6110 — `noOp` here; the EIP-7685 request phase is P5b, beacon-gated.]
      // EIP-1559 base-fee disposition (ETH burn / ECIP-1111 treasury) — the lump-sum amount `gasUsed * baseFee`
      // computed ONCE (baseFee is block-constant, so this equals Σ per-tx base-fee charges; RX-L4-10 SHARPENS (ii)).
      // `Absent`/`Burn` mutate nothing (the base fee was left uncredited by the tx engine); `RedirectToTreasury`
      // credits the treasury additively. Uses the *computed* gasUsed (correct on the produce path where header.gasUsed
      // is unfilled). The treasury credit and the miner reward are disjoint additive addBalance's → commutative
      // (RX-L4-10 SHARPENS (i)); the deterministic order (disposition then reward) matches the ECIP-1111 draft :49-55.
      val baseFeeAmount = gasUsed * block.header.baseFeePerGas.getOrElse(BigInt(0))
      val disposed = spec.feeDisposition.dispose(worldAfterWithdrawals, baseFeeAmount)
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
        stateRoot = committed.stateRootHash
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
    else if executed.stateRoot != header.stateRoot.bytes then
      Left(BlockExecutionError.StateRootMismatch(executed.stateRoot, header.stateRoot.bytes))
    else Right(())

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
