package com.chipprbots.fukuii.storage

import cats.effect.IO

import scala.collection.immutable.ArraySeq

import fs2.Stream

/** Typed key-value view over a single [[Namespace]] of a [[DataSource]] — the layer above the byte-pure contract that
  * concrete domain storages (block headers, receipts, trie nodes, ...) implement by supplying `K`/`V` (de)serializers.
  * `T` is the concrete subtype, so [[update]] returns a fresh instance over the (immutable) `dataSource` reference
  * rather than mutating in place.
  */
trait KeyValueStorage[K, V, T <: KeyValueStorage[K, V, T]]:
  val dataSource: DataSource
  val namespace: Namespace
  def keySerializer: K => IndexedSeq[Byte]
  def keyDeserializer: IndexedSeq[Byte] => K
  def valueSerializer: V => IndexedSeq[Byte]
  def valueDeserializer: IndexedSeq[Byte] => V

  protected def apply(dataSource: DataSource): T

  def get(key: K): Option[V] = dataSource.get(namespace, keySerializer(key)).map(valueDeserializer)

  def put(key: K, value: V): T = update(Nil, Seq(key -> value))

  def remove(key: K): T = update(Seq(key), Nil)

  /** Deletes `toRemove` and upserts `toUpsert` within this storage's namespace, atomically (see [[DataSource]]'s
    * "Atomicity (L2-F4)" note), returning a fresh `T` over the same underlying `dataSource`.
    */
  def update(toRemove: Seq[K], toUpsert: Seq[(K, V)]): T =
    dataSource.update(
      Seq(
        DataSourceUpdate(
          namespace,
          toRemove.map(keySerializer),
          toUpsert.map { case (k, v) => keySerializer(k) -> valueSerializer(v) }
        )
      )
    )
    apply(dataSource)

  def storageContent: Stream[IO, Either[DataSource.IterationError, (K, V)]] =
    dataSource.iterate(namespace).map { result =>
      result.map { case (key, value) =>
        (keyDeserializer(ArraySeq.unsafeWrapArray(key)), valueDeserializer(ArraySeq.unsafeWrapArray(value)))
      }
    }
