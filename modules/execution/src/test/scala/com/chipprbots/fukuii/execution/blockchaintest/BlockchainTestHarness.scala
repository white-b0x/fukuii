package com.chipprbots.fukuii.execution.blockchaintest

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.Block
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.domain.Wei
import com.chipprbots.fukuii.evm.EvmConfig
import com.chipprbots.fukuii.evm.EvmInterpreter
import com.chipprbots.fukuii.execution.*
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.rawDecode
import com.chipprbots.fukuii.storage.EphemDataSource
import com.chipprbots.fukuii.trie.InMemoryMptStorage
import com.chipprbots.fukuii.trie.MptNode

/** The **`ethereum/tests` BlockchainTest driver** — the L4 capstone harness (geth `tests/block_test.go`
  * `BlockTest.Run`, besu `BlockchainReferenceTestTools`). It drives the full committed L4 pipeline (P0–P6:
  * `InMemoryWorldState` → `TransactionProcessor` → `BlockProcessor.processBlock` → the four commitments + reward seam)
  * against a reference [[BlockchainTestCase]], and proves byte-for-byte agreement with the reference clients.
  *
  * **The fixtures ARE the oracle.** A valid block that fails to import — or imports to a head hash ≠ `lastblockhash` —
  * is a genuine bug in P0–P6 (the header commitment ties `stateRoot`/`receiptsRoot`/`gasUsed`/`logsBloom`, so a green
  * import is a byte-exact state-transition proof), reported as [[RunResult.ValidBlockRejected]] /
  * [[RunResult.HeadMismatch]] for forge/beacon/eye to root-cause — never patched around.
  *
  * **Corpus is external / opt-in.** The `ethereum/tests` fixture DATA is not vendored into fukuii (the
  * `.claude/repo-references/…` clone is Claude-tooling-local and gitignored). Only this harness CODE is committed;
  * [[BlockchainTestDriverSpec]] runs it **only** when the operator opts in with a corpus directory. The reproducible CI
  * gate (a vendored `ethereum/tests` submodule + a dedicated reference-test tier) is a separate warden/sentinel
  * supply-chain/CI follow-up — TBD, not wired here.
  */
object BlockchainTestHarness:

  /** Shared VM interpreter + block processor (stateless — R2), reused across cases. */
  private val interpreter = new EvmInterpreter[InMemoryWorldState, InMemoryAccountStorage]()
  private val systemCall = new SystemCallProcessor(interpreter)
  private val processor = new BlockProcessor(new TransactionProcessor(interpreter))

  /** EIP-6110 mainnet deposit contract `0x00000000219ab540356cBB839Cbe05303d7705Fa` (go-ethereum
    * `params.MainnetChainConfig.DepositContractAddress`). The mainnet-chainId fixtures use it.
    */
  private val MainnetDepositContract: Address =
    Address(Hex.decode("0x00000000219ab540356cBB839Cbe05303d7705Fa"))

  /** The outcome of running a case — a fail-LOUD taxonomy so a failure names *what* diverged (not just "not green"). */
  enum RunResult:
    /** Green: every valid block imported, invalid blocks rejected, canonical head == `lastblockhash`. */
    case Passed

    /** The `pre` alloc persisted to a state root ≠ `genesisBlockHeader.stateRoot` — a world-build / L2-trie bug. */
    case GenesisRootMismatch(computed: ByteString, expected: ByteString)

    /** A block's RLP could not be decoded into an L1 `Block` (an L1 codec gap, or a fixture using an unsupported
      * field).
      */
    case BlockDecodeFailed(index: Int, message: String)

    /** A valid (`expectException = None`) block was **rejected** by `processBlock` — a P0–P6 pipeline bug (or a fork
      * feature not yet wired). Carries the exact [[BlockExecutionError]] for root-cause.
      */
    case ValidBlockRejected(index: Int, error: BlockExecutionError)

    /** An invalid (`expectException = Some`) block was **accepted** — a missing consensus check. */
    case InvalidBlockAccepted(index: Int, expected: String)

    /** All blocks imported, but the canonical head hash ≠ `lastblockhash` — the wrong chain (a byte-level state or
      * header divergence that still committed).
      */
    case HeadMismatch(computed: Hash, expected: Hash)

    /** A block threw (instead of returning a clean `Left`) during `processBlock` — e.g. an out-of-range `UInt256` from
      * an invalid EIP-1559 tip / withdrawal underflow. On an expected-invalid block this is still a rejection, but an
      * ungraceful one (a fail-loud-via-crash rather than a typed error).
      */
    case PipelineThrew(index: Int, message: String)

    /** The case's `network` is not yet mapped to a fukuii `ProtocolSpec` (a deferral, visibly reported — no silent
      * skip).
      */
    case UnsupportedNetwork(network: String)

  /** Run one case through the full pipeline, returning a fail-LOUD [[RunResult]]. */
  def run(tc: BlockchainTestCase): RunResult =
    specFor(tc.network) match
      case None => RunResult.UnsupportedNetwork(tc.network)
      case Some(spec) =>
        val chainId = ChainId(tc.chainId)
        val blockHashes = mutable.Map[BigInt, Hash](tc.genesisNumber -> tc.genesisHash)
        val world = buildWorld(tc.pre, n => blockHashes.get(n))
        if world.stateRootHash != tc.genesisStateRoot then
          RunResult.GenesisRootMismatch(world.stateRootHash, tc.genesisStateRoot)
        else importBlocks(tc, spec, chainId, world, blockHashes)

  /** Import the block sequence, threading the world; track the canonical head (last successfully imported block, or
    * genesis). Short-circuits on the first fail-LOUD divergence.
    */
  private def importBlocks(
      tc: BlockchainTestCase,
      spec: ProtocolSpec,
      chainId: ChainId,
      initialWorld: InMemoryWorldState,
      blockHashes: mutable.Map[BigInt, Hash]
  ): RunResult =
    /** Fold over the blocks, threading `(world, head)`; short-circuit on the first fail-LOUD divergence, else report
      * the head-vs-`lastblockhash` comparison at the end.
      */
    def loop(remaining: List[(ExpectedBlock, Int)], world: InMemoryWorldState, head: Hash): RunResult =
      remaining match
        case Nil =>
          if head == tc.lastBlockHash then RunResult.Passed
          else RunResult.HeadMismatch(head, tc.lastBlockHash)
        case (expected, index) :: rest =>
          decodeBlock(expected.rlp) match
            case Left(message) =>
              // A decode failure on an expected-invalid block is itself a valid rejection (a malformed-RLP block).
              if expected.expectException.isDefined then loop(rest, world, head)
              else RunResult.BlockDecodeFailed(index, message)
            case Right(block) =>
              val outcome =
                try Right(processor.processBlock(spec, block, world, chainId))
                catch case ex: Throwable => Left(Option(ex.getMessage).getOrElse(ex.getClass.getName).nn)
              outcome match
                case Left(message) =>
                  // A throw is a rejection, but an ungraceful one — surface it whether or not it was expected-invalid.
                  RunResult.PipelineThrew(index, message)
                case Right(Right(executed)) =>
                  if expected.expectException.isDefined then
                    RunResult.InvalidBlockAccepted(index, expected.expectException.getOrElse(""))
                  else
                    blockHashes(block.header.number) = block.header.hash
                    loop(rest, executed.world, block.header.hash)
                case Right(Left(error)) =>
                  // An expected rejection leaves head/world unchanged (a later block may still be canonical).
                  if expected.expectException.isEmpty then RunResult.ValidBlockRejected(index, error)
                  else loop(rest, world, head)

    loop(tc.blocks.zipWithIndex, initialWorld, tc.genesisHash)

  /** Decode a block's consensus RLP into an L1 [[Block]] (flat `extblock`), capturing any codec failure as a message.
    */
  private def decodeBlock(rlp: ByteString): Either[String, Block] =
    try Right(summon[RLPCodec[Block]].decode(rawDecode(rlp.toArray)))
    catch case ex: Throwable => Left(Option(ex.getMessage).getOrElse(ex.getClass.getName).nn)

  /** Build the genesis-alloc world from `pre` and persist it (its `stateRootHash` must equal
    * `genesisBlockHeader.stateRoot`). EIP-161 empty-account semantics are on (`noEmptyAccounts = true`) — the
    * post-Spurious-Dragon rule every fork in the current corpus (Cancun/Prague) uses.
    */
  private def buildWorld(pre: Map[Address, PreAccount], getBlockHash: BigInt => Option[Hash]): InMemoryWorldState =
    val base = InMemoryWorldState(
      codeStorage = new CodeStorage(EphemDataSource()),
      mptStorage = new InMemoryMptStorage,
      getBlockHashByNumber = getBlockHash,
      accountStartNonce = UInt256.Zero,
      stateRootHash = MptNode.EmptyRootHash,
      noEmptyAccounts = true
    )
    val loaded = pre.foldLeft(base) { case (w, (address, account)) =>
      val withAccount = w.saveAccount(
        address,
        Account.empty().copy(nonce = UInt256(account.nonce), balance = Wei(UInt256(account.balance)))
      )
      val withCode = if account.code.isEmpty then withAccount else withAccount.saveCode(address, account.code)
      if account.storage.isEmpty then withCode
      else
        val storage = account.storage.foldLeft(withCode.getStorage(address)) { case (s, (slot, value)) =>
          s.store(slot, value)
        }
        withCode.saveStorage(address, storage)
    }
    loaded.persist

  /** Resolve a case's `network` string → the immutable per-fork [[ProtocolSpec]] bundle. The current vendored corpus is
    * **Cancun/Prague only**; other forks return `None` (a visible [[RunResult.UnsupportedNetwork]] deferral). The
    * economics collaborators are the realistic ETH pairing: PoS zero-reward, base-fee **burn**, EIP-4895 withdrawals;
    * pre-execution runs EIP-4788 (Cancun+) and EIP-2935 (Prague+); the Prague request coordinator wires
    * EIP-6110/7002/7251.
    */
  private def specFor(network: String): Option[ProtocolSpec] =
    network match
      case "Cancun" =>
        Some(
          ProtocolSpec(
            evmConfig = EvmConfig.EthCancun,
            preExecution = PreExecutionProcessor.EthPreExecution(systemCall, historyStorageActive = false),
            rewardScheme = RewardScheme.PosNoRewardScheme,
            requests = RequestProcessors.noOp,
            withdrawals = Some(WithdrawalsProcessor.Eip4895WithdrawalsProcessor),
            feeDisposition = FeeDisposition.Burn
          )
        )
      case "Prague" | "Osaka" =>
        val evm = if network == "Osaka" then EvmConfig.EthOsaka else EvmConfig.EthPrague
        Some(
          ProtocolSpec(
            evmConfig = evm,
            preExecution = PreExecutionProcessor.EthPreExecution(systemCall, historyStorageActive = true),
            rewardScheme = RewardScheme.PosNoRewardScheme,
            requests = RequestProcessors.prague(systemCall, MainnetDepositContract),
            withdrawals = Some(WithdrawalsProcessor.Eip4895WithdrawalsProcessor),
            feeDisposition = FeeDisposition.Burn
          )
        )
      case _ => None
