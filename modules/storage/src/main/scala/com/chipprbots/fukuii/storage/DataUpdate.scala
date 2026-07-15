package com.chipprbots.fukuii.storage

import DataSource.Key
import DataSource.Value

/** One logical mutation batch [[DataSource.update]]/[[DataSource.updateSync]] can commit atomically. */
sealed trait DataUpdate

/** Deletes `toRemove` and upserts `toUpsert` within `namespace`. */
final case class DataSourceUpdate(namespace: Namespace, toRemove: Seq[Key], toUpsert: Seq[(Key, Value)])
    extends DataUpdate

/** As [[DataSourceUpdate]], but assumes the caller already serialized keys and values. */
final case class DataSourceUpdateOptimized(
    namespace: Namespace,
    toRemove: Seq[Array[Byte]],
    toUpsert: Seq[(Array[Byte], Array[Byte])]
) extends DataUpdate
