package com.chipprbots.fukuii.storage

import org.scalatest.funsuite.AnyFunSuite

class KeyValueStorageSpec extends AnyFunSuite:

  private def intSerializer(i: Int): IndexedSeq[Byte] =
    IndexedSeq((i >> 24).toByte, (i >> 16).toByte, (i >> 8).toByte, i.toByte)

  private def intDeserializer(bytes: IndexedSeq[Byte]): Int =
    ((bytes(0) & 0xff) << 24) | ((bytes(1) & 0xff) << 16) | ((bytes(2) & 0xff) << 8) | (bytes(3) & 0xff)

  final class IntStorage(val dataSource: DataSource) extends KeyValueStorage[Int, Int, IntStorage]:
    override val namespace: Namespace = Namespace.AppState
    override def keySerializer: Int => IndexedSeq[Byte] = intSerializer
    override def keyDeserializer: IndexedSeq[Byte] => Int = intDeserializer
    override def valueSerializer: Int => IndexedSeq[Byte] = intSerializer
    override def valueDeserializer: IndexedSeq[Byte] => Int = intDeserializer
    override protected def apply(dataSource: DataSource): IntStorage = new IntStorage(dataSource)

  test("get returns None for a key never put"):
    val storage = new IntStorage(EphemDataSource())
    assert(storage.get(42).isEmpty)

  test("put then get round-trips the value"):
    val storage = new IntStorage(EphemDataSource())
    val after = storage.put(1, 100)
    assert(after.get(1).contains(100))

  test("update batches removal and upsert, returning a fresh instance over the same DataSource"):
    val storage = new IntStorage(EphemDataSource())
    val withData = storage.update(Nil, Seq(1 -> 10, 2 -> 20, 3 -> 30))
    val afterRemove = withData.update(Seq(2), Nil)
    assert(
      afterRemove.get(1).contains(10) &&
        afterRemove.get(2).isEmpty &&
        afterRemove.get(3).contains(30) &&
        (afterRemove.dataSource eq withData.dataSource),
      "update must remove key 2, keep keys 1 and 3, and reuse the same underlying DataSource"
    )

  test("remove deletes a single key"):
    val storage = new IntStorage(EphemDataSource()).put(5, 50)
    assert(storage.remove(5).get(5).isEmpty)

  test("storageContent streams every entry in the namespace"):
    import cats.effect.unsafe.implicits.global
    val storage = new IntStorage(EphemDataSource()).update(Nil, Seq(1 -> 10, 2 -> 20))
    val content = storage.storageContent.compile.toList.unsafeRunSync()
    assert(
      content.forall(_.isRight) &&
        content.map(_.toOption.get).toSet == Set(1 -> 10, 2 -> 20),
      "storageContent must stream every entry in the namespace as Right values matching the stored set"
    )
