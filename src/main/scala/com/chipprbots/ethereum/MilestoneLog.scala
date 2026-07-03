package com.chipprbots.ethereum

import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.Logger

/** Logs fork milestone activations at node startup.
  *
  * Besu reference: ProtocolScheduleBuilder.java — `LOG.info("Protocol schedule created with milestones: {}", ...)` Only
  * activated forks (block number != Long.MaxValue) are included in the output.
  */
object MilestoneLog extends Logger:

  def logMilestones(forkBlockNumbers: ForkBlockNumbers): Unit =
    log.info("Protocol schedule milestones: {}", formatMilestones(forkBlockNumbers))

  private[ethereum] def formatMilestones(forkBlockNumbers: ForkBlockNumbers): String =
    val active = namedMilestones(forkBlockNumbers).filter { case (_, block) => block != Long.MaxValue }
    if active.isEmpty then "[]"
    else active.map { case (name, block) => s"$name:$block" }.mkString("[", ", ", "]")

  // Log-formatting boundary: unwrap once here to build the display Seq[(String, BigInt)].
  private def namedMilestones(f: ForkBlockNumbers): Seq[(String, BigInt)] = Seq(
    "Frontier" -> f.frontierBlockNumber.value,
    "Homestead" -> f.homesteadBlockNumber.value,
    "EIP-106" -> f.eip106BlockNumber.value,
    "EIP-150" -> f.eip150BlockNumber.value,
    "EIP-155" -> f.eip155BlockNumber.value,
    "EIP-160" -> f.eip160BlockNumber.value,
    "EIP-161" -> f.eip161BlockNumber.value,
    "DiffBomb-Pause" -> f.difficultyBombPauseBlockNumber.value,
    "DiffBomb-Continue" -> f.difficultyBombContinueBlockNumber.value,
    "DiffBomb-Removal" -> f.difficultyBombRemovalBlockNumber.value,
    "Byzantium" -> f.byzantiumBlockNumber.value,
    "Constantinople" -> f.constantinopleBlockNumber.value,
    "Istanbul" -> f.istanbulBlockNumber.value,
    "Atlantis" -> f.atlantisBlockNumber.value,
    "Agharta" -> f.aghartaBlockNumber.value,
    "Phoenix" -> f.phoenixBlockNumber.value,
    "Petersburg" -> f.petersburgBlockNumber.value,
    "ECIP-1099" -> f.ecip1099BlockNumber.value,
    "Muir Glacier" -> f.muirGlacierBlockNumber.value,
    "Magneto" -> f.magnetoBlockNumber.value,
    "Berlin" -> f.berlinBlockNumber.value,
    "Mystique" -> f.mystiqueBlockNumber.value,
    "Spiral" -> f.spiralBlockNumber.value,
    "Olympia" -> f.olympiaBlockNumber.value
  )
