package com.chipprbots.ethereum.utils

import scala.util.Try

import com.chipprbots.ethereum.domain.BlockNumber

/** Lightweight debug tracing toggles wired via JVM system properties.
  *
  * Usage: -Dfukuii.trace.block=35554 -Dfukuii.trace.tx=0xdeadbeef...
  */
object DebugTrace:

  private def optBigInt(key: String): Option[BigInt] =
    sys.props
      .get(key)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(s => Try(BigInt(s)).toOption)

  private def optHexLower(key: String): Option[String] =
    sys.props
      .get(key)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.stripPrefix("0x").toLowerCase)

  // Accept both long-form and short-form property names.
  lazy val traceBlockNumber: Option[BlockNumber] =
    optBigInt("fukuii.trace.blockNumber").orElse(optBigInt("fukuii.trace.block")).map(BlockNumber(_))

  lazy val traceTxHashLower: Option[String] =
    optHexLower("fukuii.trace.txHash").orElse(optHexLower("fukuii.trace.tx"))

  def enabledForBlock(blockNumber: BlockNumber): Boolean =
    traceBlockNumber.contains(blockNumber)

  def enabledForTx(blockNumber: BlockNumber, txHashHex: String): Boolean =
    if !enabledForBlock(blockNumber) then false
    else traceTxHashLower.forall(_ == txHashHex.stripPrefix("0x").toLowerCase)
