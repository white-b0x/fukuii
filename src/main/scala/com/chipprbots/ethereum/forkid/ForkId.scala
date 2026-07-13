package com.chipprbots.ethereum.forkid

import java.util.zip.CRC32

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.forks.ForkActivation
import com.chipprbots.ethereum.forks.ScheduledProposal
import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.utils.BigIntExtensionMethods.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils.*
import com.chipprbots.ethereum.utils.Hex

import RLPImplicitConversions.*
import RLPImplicits.given

case class ForkId(hash: BigInt, next: Option[BigInt]):

  def nextDisplay: String = next match
    case None    => "None"
    case Some(n) => ForkId.knownSentinels.get(n).fold(n.toString)(name => s"$n ($name)")

  override def toString(): String =
    s"ForkId(0x${Hex.toHexString(hash.toUnsignedByteArray)}, next=${nextDisplay})"

object ForkId:

  val knownSentinels: Map[BigInt, String] = Map(
    BigInt("1000000000000000000") -> "Olympia"
  )

  def create(genesisHash: ByteString, config: BlockchainConfig)(head: BigInt): ForkId =
    create(genesisHash, config)(head, 0L)

  /** EIP-2124 + EIP-6122: ForkId computation with both block number and timestamp. Block-number forks are compared
    * against `head`, timestamp forks against `headTimestamp`.
    */
  def create(genesisHash: ByteString, config: BlockchainConfig)(head: BigInt, headTimestamp: Long): ForkId =
    val crc = new CRC32()
    crc.update(genesisHash.asByteBuffer)

    val blockForks = gatherBlockForks(config)
    val timestampForks = gatherTimestampForks(config)

    // Process block forks first (sorted), then timestamp forks (sorted)
    val allForks = blockForks.map((_, false)) ++ timestampForks.map((_, true))

    val next = allForks.find { case (fork, isTimestamp) =>
      val passed = if isTimestamp then fork <= BigInt(headTimestamp) else fork <= head
      if passed then crc.update(bigIntToBytes(fork, 8))
      !passed
    }
    new ForkId(crc.getValue(), next.map(_._1))

  // 10^18 is the genesis JSON "not yet scheduled" sentinel. Olympia is re-appended
  // explicitly below when eip1559BlockNumber itself is still the sentinel, ensuring
  // ETC/Mordor advertise Olympia as the next fork. This one purpose cannot be
  // schedule-derived: ForkSchedule's `byBlockIfReal` collapses "pending at 10^18" and
  // "never scheduled" to the same `Never`, so the sentinel-vs-absent distinction
  // Olympia needs is only recoverable by reading `forkBlockNumbers.eip1559BlockNumber`
  // directly (Row 5.8b F1 caveat).
  private val olympiaSentinel: BigInt = BigInt("1000000000000000000")

  def gatherForks(config: BlockchainConfig): List[BigInt] =
    (gatherBlockForks(config) ++ gatherTimestampForks(config)).distinct.sorted

  /** Row 5.8b: derived from `ForkSchedule` (the L3 fork registry) rather than the flat `ForkBlockNumbers` struct + the
    * ad hoc DAO special-case. Every `ByBlock` entry in the schedule has already had "not yet scheduled" sentinels
    * (10^18 / Long.MaxValue) filtered to `ForkActivation.Never` at derivation time
    * (`BlockchainConfig.deriveForkSchedule`'s `byBlockIfReal`), so only the genesis-active (`v == 0`) dedup remains
    * here — the same dedup the old struct-based enumeration performed (e.g. Mordor's `difficultyBombPause`, `atlantis`,
    * etc. sit at block 0 and must not appear as fork-id checkpoints).
    */
  def gatherBlockForks(config: BlockchainConfig): List[BigInt] =
    val realForks = config.forkSchedule.entries.values
      .collect { case ScheduledProposal(ForkActivation.ByBlock(bn), _) if bn.value != 0 => bn.value }
      .toList
      .distinct
      .sorted
    // Advertise Olympia sentinel as the next fork when not yet scheduled
    val olympiaNext =
      if config.forkBlockNumbers.eip1559BlockNumber.value == olympiaSentinel then List(olympiaSentinel) else Nil
    realForks ++ olympiaNext

  /** EIP-6122: Timestamp-based forks for post-Merge chains. */
  def gatherTimestampForks(config: BlockchainConfig): List[BigInt] =
    List(
      config.forkTimestamps.shanghaiTimestamp.map(BigInt(_)),
      config.forkTimestamps.cancunTimestamp.map(BigInt(_)),
      config.forkTimestamps.pragueTimestamp.map(BigInt(_)),
      config.forkTimestamps.osakaTimestamp.map(BigInt(_)),
      config.forkTimestamps.bpo1Timestamp.map(BigInt(_)),
      config.forkTimestamps.bpo2Timestamp.map(BigInt(_))
    ).flatten.filterNot(_ == 0).distinct.sorted

  extension (forkId: ForkId)
    def toRLPEncodable: RLPEncodeable =
      import com.chipprbots.ethereum.utils.ByteUtils.*
      val hash: Array[Byte] = bigIntToBytes(forkId.hash, 4).takeRight(4)
      val next: Array[Byte] = bigIntToUnsignedByteArray(forkId.next.getOrElse(BigInt(0))).takeRight(8)
      RLPList(hash, next)

  implicit val forkIdEnc: RLPDecoder[ForkId] = new RLPDecoder[ForkId]:

    def decode(rlp: RLPEncodeable): ForkId = rlp match
      case RLPList(hash, next) =>
        val i = bigIntFromEncodeable(next)
        ForkId(bigIntFromEncodeable(hash), if i == 0 then None else Some(i))
      case _ => throw new RuntimeException("Error when decoding ForkId")
