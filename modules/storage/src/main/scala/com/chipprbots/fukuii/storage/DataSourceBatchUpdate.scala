package com.chipprbots.fukuii.storage

import scala.collection.immutable.ArraySeq

/** Accumulates [[DataUpdate]] entries against a single [[DataSource]] before committing them as one atomic write (see
  * [[DataSource]]'s "Atomicity (L2-F4)" note) — the builder side of that contract.
  */
final case class DataSourceBatchUpdate(dataSource: DataSource, updates: Array[DataUpdate] = Array.empty):

  def and(that: DataSourceBatchUpdate): DataSourceBatchUpdate =
    require(
      this.dataSource eq that.dataSource,
      "Transactional storage updates must be performed on the same data source"
    )
    DataSourceBatchUpdate(dataSource, this.updates ++ that.updates)

  def commit(): Unit =
    dataSource.update(ArraySeq.unsafeWrapArray(updates))

  /** Fsync-backed commit — see [[DataSource.updateSync]]. */
  def commitSync(): Unit =
    dataSource.updateSync(ArraySeq.unsafeWrapArray(updates))
