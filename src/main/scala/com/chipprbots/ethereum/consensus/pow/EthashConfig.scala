package com.chipprbots.ethereum
package consensus
package pow

import java.io.File

import scala.concurrent.duration.*

import com.typesafe.config.Config as TypesafeConfig

import com.chipprbots.ethereum.consensus.mining.Protocol

final case class EthashConfig(
    ommersPoolSize: Int,
    ommerPoolQueryTimeout: FiniteDuration,
    ethashDir: String,
    mineRounds: Int
)

object EthashConfig:
  object Keys:
    final val OmmersPoolSize = "ommers-pool-size"
    final val OmmerPoolQueryTimeout = "ommer-pool-query-timeout"
    final val EthashDir = "ethash-dir"
    final val MineRounds = "mine-rounds"

  def apply(fukuiiConfig: TypesafeConfig): EthashConfig =
    val miningConfig = fukuiiConfig.getConfig(Protocol.Names.PoW)

    val ommersPoolSize = miningConfig.getInt(Keys.OmmersPoolSize)
    val ommerPoolQueryTimeout = miningConfig.getDuration(Keys.OmmerPoolQueryTimeout).toMillis.millis
    val ethashDir = resolveEthashDir(miningConfig.getString(Keys.EthashDir))
    val mineRounds = miningConfig.getInt(Keys.MineRounds)

    new EthashConfig(
      ommersPoolSize = ommersPoolSize,
      ommerPoolQueryTimeout = ommerPoolQueryTimeout,
      ethashDir = ethashDir,
      mineRounds = mineRounds
    )

  /** Resolve the effective DAG directory, falling back to the legacy `~/.ethash` location when it already holds DAG
    * files and the configured directory (normally `<datadir>/ethash`) does not — existing miners upgrading to the
    * on-datadir default should not be forced through a needless multi-GB DAG regeneration. DAGs are regenerable, so
    * this fallback exists purely to save an expensive recompute, not for correctness.
    */
  private[pow] def resolveEthashDir(configuredDir: String): String =
    val legacyDir = new File(System.getProperty("user.home"), ".ethash")
    val configured = new File(configuredDir)
    def hasDagFiles(dir: File): Boolean =
      dir.isDirectory && Option(dir.listFiles()).exists(_.nonEmpty)
    if !hasDagFiles(configured) && hasDagFiles(legacyDir) then legacyDir.getAbsolutePath else configuredDir
