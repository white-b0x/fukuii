package com.chipprbots.fukuii.common

import com.typesafe.scalalogging
import org.slf4j.LoggerFactory

/** Mix-in providing an SLF4J-backed logger named after the concrete (mixing) class.
  *
  * This is the one genuinely foundational utility every layer above `common` needs, and it is the
  * only thing the wired `logging` dependency exists for at this layer. The logger is eagerly
  * initialised; prefer [[LazyLogger]] on a type that is instantiated on a hot path.
  */
trait Logger:
  protected val log: scalalogging.Logger =
    scalalogging.Logger(LoggerFactory.getLogger(getClass))

/** Mix-in providing a lazily-initialised logger — the field is materialised on first use rather
  * than at construction, avoiding a `LoggerFactory` lookup for short-lived, frequently-allocated types.
  */
trait LazyLogger:
  protected lazy val log: scalalogging.Logger =
    scalalogging.Logger(LoggerFactory.getLogger(getClass))
