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
import com.chipprbots.fukuii.evm.EvmProposals
import com.chipprbots.fukuii.evm.ProposalId
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

    /** The case's `network` is an **ETC** fork label (`ETC_*`) not yet mapped to a fukuii `ProtocolSpec` — a genuinely
      * new ETC fork to add (e.g. an `ETC_*Transition` variant), visibly reported so it reads as "add me", never a
      * silent skip.
      */
    case UnsupportedNetwork(network: String)

    /** The case's `network` is a **non-ETC** (ETH-history) fork label carried into the `etc-tests` corpus because
      * etclabscore/tests forks ethereum/tests (Frontier…London/Merge + their transition variants). The ETC run targets
      * `ETC_*`; the ETH schedule is exercised by `referenceTestEth`. Classified distinctly so these do not read as
      * `UnsupportedNetwork` noise (a deferral, not a bug).
      */
    case OutOfScopeNetwork(network: String)

  /** Run one case through the full pipeline, returning a fail-LOUD [[RunResult]]. */
  def run(tc: BlockchainTestCase): RunResult =
    specFor(tc.network) match
      case None =>
        // An unmapped `ETC_*` label is a genuinely-new ETC fork (surface it, "add me"); any non-`ETC_` label is an
        // ETH-history fork carried into the etc-tests corpus (the ETH schedule's, exercised by referenceTestEth) —
        // out of scope for the ETC EIP-set run, not `UnsupportedNetwork` noise.
        if tc.network.startsWith("ETC_") then RunResult.UnsupportedNetwork(tc.network)
        else RunResult.OutOfScopeNetwork(tc.network)
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
    * post-Spurious-Dragon rule every fork currently mapped uses (ETH Cancun/Prague/Osaka; ETC Atlantis onward, where
    * EIP-161 activates at 8,772,000).
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

  /** Resolve a case's `network` string → the immutable per-fork [[ProtocolSpec]] bundle. Covers the ETH tail
    * (`Cancun`/`Prague`/`Osaka`) and the ETC PoW schedule (`ETC_Atlantis`…`ETC_Mystique`); other labels return `None`
    * (a visible deferral — [[RunResult.UnsupportedNetwork]] for an unmapped `ETC_*`, [[RunResult.OutOfScopeNetwork]]
    * for an ETH-history carryover).
    *
    * **ETH bundles** — the realistic ETH pairing: PoS zero-reward, base-fee **burn**, EIP-4895 withdrawals;
    * pre-execution runs EIP-4788 (Cancun+) and EIP-2935 (Prague+); the Prague request coordinator wires
    * EIP-6110/7002/7251.
    *
    * **ETC bundles** — the PoW pairing built by [[etcSpec]]: the per-fork [[EvmConfig]] is resolved through the **same
    * fold** L3 conformance proves (`EvmConfig.deriveEvmConfigAt` over the named [[EvmProposals]] cumulative set, byte-
    * identical to `EvmConfig.forBlock` at that fork height — `EtcForkHeightConformanceSpec` pins each set's per-fork
    * EIP delta against core-geth `params/config_classic.go`). The economics are ECIP-1017 emission evaluated at the
    * fork's canonical height (see [[etcSpec]]/[[EtcRewardAtForkHeight]]) — **not** the EIP-649/1234
    * Byzantium/Constantinople reductions — plus no pre-execution, no withdrawals, no base fee (all these forks are
    * pre-Olympia). Per-fork EIP set, cross-checked vs core-geth `config_classic.go`:
    *   - `ETC_Atlantis` = Byzantium EVM: EIP-140/198/211/214 + alt-bn128 196/197 (`EIP140/198/211/214/212/213FBlock`
    *     8_772_000, `config_classic.go:65-71`) atop EIP-161/170 (`:60-61`). Reward stays ECIP-1017 (no EIP-649).
    *   - `ETC_Agharta` = Petersburg EVM: EIP-145/1014/1052 (`EIP145/1014/1052FBlock` 9_573_000, `:74-76`); EIP-1283 is
    *     **absent** (commented out `:77` — Constantinople-minus-1283).
    *   - `ETC_Phoenix` = Istanbul EVM: EIP-152/1108/1344/1884/2028/2200 (`EIP…FBlock` 10_500_839, `:82-87`).
    *   - `ETC_Magneto` = Berlin EVM: EIP-2565/2929/2930 (+ 2718 typed-tx envelope) (`EIP2565/2718/2929/2930FBlock`
    *     13_189_133, `:95-98`).
    *   - `ETC_Mystique` = **London-MINUS-EIP-1559/3198**: EIP-3529 + EIP-3541 **only** (`EIP3529/3541FBlock`
    *     14_525_000, `:101-102`). NO base fee, NO BASEFEE opcode — `EIP1559FBlock`/`EIP3198FBlock` are scheduled at
    *     `olympiaMainnetBlock` (`:119-120`), i.e. base fee arrives later at Olympia (ECIP-1111), not here. This is the
    *     subtle one: Mystique is a *partial* London.
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
      case "ETC_Atlantis" => Some(etcSpec(EvmProposals.byzantiumSet, EtcAtlantisBlock))
      case "ETC_Agharta"  => Some(etcSpec(EvmProposals.constantinopleSet, EtcAghartaBlock))
      case "ETC_Phoenix"  => Some(etcSpec(EvmProposals.istanbulSet, EtcPhoenixBlock))
      case "ETC_Magneto"  => Some(etcSpec(EvmProposals.berlinSet, EtcMagnetoBlock))
      case "ETC_Mystique" => Some(etcSpec(EvmProposals.etcMystiqueSet, EtcMystiqueBlock))
      case _              => None

  /** The frozen ETC mainnet fork activation blocks (core-geth `params/config_classic.go`), used **only** as the
    * ECIP-1017 reward-era reference (see [[etcSpec]]) — the same heights cited above for the EIP-set fold. The
    * `etc-tests` fixtures execute each fork's block at header number 1 (for the EVM), but bake in the reward ECIP-1017
    * yields at the fork's canonical activation height (era 1 → 4 ETH for Atlantis/Agharta; era 2 → 3.2 ETH for
    * Phoenix/Magneto/Mystique), so the reward is evaluated at these heights, not header.number.
    */
  private val EtcAtlantisBlock: BigInt = 8_772_000
  private val EtcAghartaBlock: BigInt = 9_573_000
  private val EtcPhoenixBlock: BigInt = 10_500_839
  private val EtcMagnetoBlock: BigInt = 13_189_133
  private val EtcMystiqueBlock: BigInt = 14_525_000

  /** The ETC **PoW** bundle for a fork's cumulative proposal set and canonical activation `forkBlock`. The
    * [[EvmConfig]] is resolved by the production fold (`EvmConfig.deriveEvmConfigAt`, identical to `EvmConfig.forBlock`
    * at that fork height — never a hand-assembled EIP set), paired with the pre-Olympia PoW economics: **ECIP-1017**
    * era emission ([[etcForkReward]]), **no** pre-execution system calls ([[PreExecutionProcessor.NoPreExecution]] —
    * ETC runs neither EIP-4788 nor EIP-2935), **no** EIP-7685 requests (`noOp`), **no** EIP-4895 withdrawals (`None`),
    * and **no** EIP-1559 base fee ([[FeeDisposition.Absent]] — base fee first arrives at Olympia via ECIP-1111, absent
    * for every fork mapped here).
    *
    * **Reward era reference.** The `etc-tests` fixtures execute each fork's block at header number 1 (for the EVM) but
    * bake in the ECIP-1017 reward the schedule yields at the fork's **canonical mainnet height** (era 1 → 4 ETH for
    * Atlantis/Agharta; era 2 → 3.2 ETH for Phoenix/Magneto/Mystique — retesteth's per-fork static `blockReward`). So
    * the bundle binds `Ecip1017RewardScheme(blockReward = etcForkReward(forkBlock))`: at the fixtures' block-1 (era 0)
    * the winner reward is exactly that fork-height value, derived from the real emission formula — never a hardcoded
    * 4/3.2 constant. No `etc-tests` case carries uncles (0 of 43,781), so the era-0 uncle path is never exercised.
    */
  private def etcSpec(proposals: Set[ProposalId], forkBlock: BigInt): ProtocolSpec =
    ProtocolSpec(
      evmConfig = EvmConfig.deriveEvmConfigAt(proposals),
      preExecution = PreExecutionProcessor.NoPreExecution,
      rewardScheme = RewardScheme.Ecip1017RewardScheme(blockReward = etcForkReward(forkBlock)),
      requests = RequestProcessors.noOp,
      withdrawals = None,
      feeDisposition = FeeDisposition.Absent
    )

  /** The ECIP-1017 **winner reward** the canonical mainnet schedule yields at `forkBlock`, computed by the real
    * emission formula on the default [[RewardScheme.Ecip1017RewardScheme]] (5 ETH era-0 base, 5,000,000 era length) —
    * Atlantis 8,772,000 / Agharta 9,573,000 → era 1 → 4 ETH; Phoenix 10,500,839 / Magneto 13,189,133 / Mystique
    * 14,525,000 → era 2 → 3.2 ETH. Bound as the era-0 `blockReward` so the fixtures' block-1 header reproduces the
    * fork-height reward.
    */
  private def etcForkReward(forkBlock: BigInt): BigInt =
    val canonical = RewardScheme.Ecip1017RewardScheme()
    canonical.winnerRewardByEra(canonical.blockEra(forkBlock))
