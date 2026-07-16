package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.evm.ProposalId.Eip

/** Height → newly-activated-EIP conformance for the single [[EvmConfig.forBlock]] dispatch, at the frozen ETC mainnet
  * fork heights. The oracle is the **reference client** — the per-fork EIP delta the fold resolves must equal the EIP
  * numbers core-geth `upstream:params/config_classic.go` activates at that `EIP*FBlock` (cited per fork below), never a
  * fukuii-internal set. Grounding the assertion on the reference client rather than fukuii's own `etc*Set` values is
  * what lets a set that schedules an EIP one fork early/late fail here.
  *
  * Complementary to `EvmProposalFoldIdentitySpec`, which proves the full set→bundle fold identity (opcode table + gas
  * schedule + config flags) byte-for-byte; this spec pins only the *height → newly-activated EIP set* half. An ETH
  * cross-check (go-ethereum instruction sets, synthetic timestamp clock) is appended. Block clock for ETC.
  */
class EtcForkHeightConformanceSpec extends AnyFunSuite:

  import EvmProposals.*

  private def eipSet(ns: Int*): Set[ProposalId] = ns.iterator.map(Eip.apply).toSet

  /** Real ETC mainnet fork activations (name, block height, fukuii cumulative effective set). Heights are the frozen
    * core-geth `config_classic.go` `*FBlock` coordinates; the cumulative sets drive the synthetic `ForkSchedule`, but
    * the assertions below compare the *fold's* per-fork delta against the reference-client literal, not this set.
    */
  private val etcForks: List[(String, Long, Set[ProposalId])] = List(
    ("Atlantis", 8_772_000L, byzantiumSet),
    ("Agharta", 9_573_000L, constantinopleSet),
    ("Phoenix", 10_500_839L, istanbulSet),
    ("Magneto", 13_189_133L, berlinSet),
    ("Mystique", 14_525_000L, etcMystiqueSet),
    ("Spiral", 19_250_000L, etcSpiralSet)
  )

  /** The reference oracle: the EIP numbers each ETC fork **newly activates**, transcribed from core-geth
    * `params/config_classic.go` (the `EIP*FBlock` line is cited per fork). This is the anti-circularity pivot — these
    * literals come from the reference client, never from a fukuii `Set` definition.
    *
    *   - Atlantis (8,772,000): the synthetic schedule collapses every pre-Byzantium EIP onto the first listed fork, so
    *     its delta is the full cumulative EVM set active by Byzantium: EIP-2/7 (`EIP2/7FBlock` 1,150,000), EIP-150
    *     (`EIP150Block` 2,500,000), EIP-160 (`EIP160FBlock` 3,000,000), EIP-161/170 (`EIP161/170FBlock` 8,772,000),
    *     Byzantium EIP-140/198/211/214 (`EIP140/198/211/214FBlock` 8,772,000), and the alt-bn128 ECADD/ECMUL/ECPAIRING
    *     precompiles (core-geth `EIP212/213FBlock` 8,772,000 — the canonical EIP numbers are 196/197, fukuii's registry
    *     numbering).
    *   - Agharta (9,573,000): `EIP145/1014/1052FBlock`.
    *   - Phoenix (10,500,839): `EIP152/1108/1344/1884/2028/2200FBlock`.
    *   - Magneto (13,189,133): `EIP2565/2929/2930FBlock` (EIP-2718 typed-tx envelope is not an EVM opcode/gas
    *     proposal).
    *   - Mystique (14,525,000): `EIP3529FBlock` + `EIP3541FBlock` — and NOT EIP-3860, whose `EIP3860FBlock` is
    *     19,250,000.
    *   - Spiral (19,250,000): `EIP3651FBlock` + `EIP3855FBlock` + `EIP3860FBlock` + `EIP6049FBlock`.
    */
  private val coreGethAddedByFork: Map[String, Set[ProposalId]] = Map(
    "Atlantis" -> eipSet(2, 7, 150, 160, 161, 170, 140, 198, 211, 214, 196, 197),
    "Agharta" -> eipSet(145, 1014, 1052),
    "Phoenix" -> eipSet(152, 1108, 1344, 1884, 2028, 2200),
    "Magneto" -> eipSet(2565, 2929, 2930),
    "Mystique" -> eipSet(3529, 3541),
    "Spiral" -> eipSet(3651, 3855, 3860, 6049)
  )

  /** Build a `ForkSchedule` on the block axis from a monotonic `(height, cumulativeSet)` ladder: each proposal
    * activates at the lowest height whose cumulative set first contains it (mirrors `EvmProposalFoldIdentitySpec`).
    */
  private def scheduleOf(ladder: List[(Long, Set[ProposalId])], axis: Long => ForkActivation): ForkSchedule =
    val entries = evmApplicationOrder.iterator.flatMap { id =>
      ladder.find(_._2.contains(id)).map(h => id -> axis(h._1))
    }.toMap
    ForkSchedule(entries)

  private val etcSchedule: ForkSchedule =
    scheduleOf(etcForks.map((_, h, set) => h -> set), h => ForkActivation.ByBlock(BigInt(h)))

  private def header(number: Long, timestamp: Long = 0L): BlockHeader =
    BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = Address.Zero,
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 0,
      number = BigInt(number),
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = timestamp,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty
    )

  /** Only the `Eip(_)` proposals of a resolved config (this schedule adds no `Ecip`). */
  private def activeEips(cfg: EvmConfig): Set[ProposalId] =
    cfg.activeProposals.collect { case e @ Eip(_) => e }

  // -- per-fork newly-activated EIP delta == core-geth reference literal ----------------------------------------------

  etcForks.sliding(2).foreach {
    case List((_, prevHeight, _), (name, height, _)) =>
      test(s"forBlock: EIPs newly activated at $name ($height) match core-geth config_classic.go, not a fukuii set"):
        val added = activeEips(EvmConfig.forBlock(header(height), etcSchedule)) --
          activeEips(EvmConfig.forBlock(header(prevHeight), etcSchedule))
        assert(added == coreGethAddedByFork(name))
    case _ => ()
  }

  test("forBlock: the first fork (Atlantis) newly activates exactly the core-geth cumulative-Byzantium EVM EIP set"):
    val added = activeEips(EvmConfig.forBlock(header(8_772_000L), etcSchedule)) --
      activeEips(EvmConfig.forBlock(header(8_772_000L - 1), etcSchedule))
    assert(added == coreGethAddedByFork("Atlantis"))

  test("forBlock: EIP-3860 is absent at Mystique and first present at Spiral (core-geth EIP3860FBlock = 19,250,000)"):
    val mystique = EvmConfig.forBlock(header(14_525_000L), etcSchedule)
    val spiral = EvmConfig.forBlock(header(19_250_000L), etcSchedule)
    assert(
      !mystique.isActive(Eip(3860)) && !mystique.eip3860Enabled &&
        spiral.isActive(Eip(3860)) && spiral.eip3860Enabled
    )

  test("below the first fork (Atlantis) the effective set is empty (Frontier)"):
    assert(activeEips(EvmConfig.forBlock(header(8_772_000L - 1), etcSchedule)).isEmpty)

  // -- boundary EIPs activate exactly at their fork height (not a block early/late) -----------------------------------

  test("EIP-170 maxCodeSize activates exactly at Atlantis 8,772,000 (core-geth EIP170FBlock)"):
    val below = EvmConfig.forBlock(header(8_772_000L - 1), etcSchedule)
    val at = EvmConfig.forBlock(header(8_772_000L), etcSchedule)
    assert(
      below.maxCodeSize.isEmpty && !below.isActive(Eip(170)) &&
        at.maxCodeSize.contains(BigInt(24576)) && at.isActive(Eip(170))
    )

  test("EIP-2929 warm/cold access activates exactly at Magneto 13,189,133 (core-geth EIP2929FBlock)"):
    val below = EvmConfig.forBlock(header(13_189_133L - 1), etcSchedule)
    val at = EvmConfig.forBlock(header(13_189_133L), etcSchedule)
    assert(!below.eip2929Enabled && !below.isActive(Eip(2929)) && at.eip2929Enabled && at.isActive(Eip(2929)))

  test("just below Magneto EIP-2929/2565/2930 are not yet present (Phoenix/Istanbul era)"):
    val below = EvmConfig.forBlock(header(13_189_133L - 1), etcSchedule)
    assert(!below.isActive(Eip(2929)) && !below.isActive(Eip(2565)) && !below.isActive(Eip(2930)))

  // -- ETH cross-check: per-fork EIP delta vs go-ethereum instruction sets (validates the ETH side of EIP-3860) -------

  /** Synthetic ETH timestamp ladder (Berlin → London → Shanghai → Cancun). The oracle is go-ethereum
    * `core/vm/jump_table.go`: `newLondonInstructionSet` = Berlin + enable3529 + enable3198 (**no** enable3860);
    * `newShanghaiInstructionSet` = Merge(London) + enable3855 + enable3860. So EIP-3860 first activates at Shanghai on
    * the ETH clock, never London — the ETH mirror of the ETC EIP3860FBlock correction.
    */
  private val ethForks: List[(String, Long, Set[ProposalId])] = List(
    ("Berlin", 100L, berlinSet),
    ("London", 200L, ethLondonSet),
    ("Shanghai", 300L, ethShanghaiSet),
    ("Cancun", 400L, ethCancunSet)
  )

  private val ethSchedule: ForkSchedule =
    scheduleOf(ethForks.map((_, h, set) => h -> set), h => ForkActivation.ByTimestamp(h))

  /** go-ethereum-transcribed per-fork ETH deltas:
    *   - London: EIP-3198 (BASEFEE, `enable3198`), EIP-3529 (`enable3529`), EIP-3541 (reject 0xEF), EIP-1559 — NO 3860.
    *   - Shanghai: EIP-3855 (PUSH0, `enable3855`), EIP-3651 (warm COINBASE), EIP-3860 (`enable3860`).
    *   - Cancun: EIP-4844, EIP-7516, EIP-1153, EIP-5656, EIP-6780.
    */
  private val goEthAddedByFork: Map[String, Set[ProposalId]] = Map(
    "London" -> eipSet(3198, 3529, 3541, 1559),
    "Shanghai" -> eipSet(3855, 3651, 3860),
    "Cancun" -> eipSet(4844, 7516, 1153, 5656, 6780)
  )

  ethForks.sliding(2).foreach {
    case List((_, prevTs, _), (name, ts, _)) =>
      test(s"forBlock (timestamp clock): EIPs newly activated at ETH $name match go-ethereum, not a fukuii set"):
        val added = activeEips(EvmConfig.forBlock(header(0, ts), ethSchedule)) --
          activeEips(EvmConfig.forBlock(header(0, prevTs), ethSchedule))
        assert(added == goEthAddedByFork(name))
    case _ => ()
  }

  test("ETH: EIP-3860 is absent at London and first present at Shanghai (go-ethereum newShanghaiInstructionSet)"):
    val london = EvmConfig.forBlock(header(0, 200L), ethSchedule)
    val shanghai = EvmConfig.forBlock(header(0, 300L), ethSchedule)
    assert(
      !london.isActive(Eip(3860)) && !london.eip3860Enabled &&
        shanghai.isActive(Eip(3860)) && shanghai.eip3860Enabled
    )
