package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.storage.DataSource
import com.chipprbots.fukuii.storage.KeyValueStorage
import com.chipprbots.fukuii.storage.Namespace

/** Contract-code store, keyed by `keccak256(code)` → code bytes. A [[KeyValueStorage]] over the [[Namespace.Code]]
  * column family, so it is `DataSource`-backed and shares the atomic-batch write path every other domain storage uses.
  *
  * Content-addressed: the key is the code hash the account record carries (`Account.codeHash`), so identical bytecode
  * across many contracts is stored once. Matches go-ethereum `core/state` `codeWriter`/`ReadCode` (code keyed by its
  * keccak hash under the `c` prefix) and besu `WorldStateKeyValueStorage.getCode(codeHash)`.
  */
final class CodeStorage(val dataSource: DataSource) extends KeyValueStorage[ByteString, ByteString, CodeStorage]:
  override val namespace: Namespace = Namespace.Code
  override def keySerializer: ByteString => IndexedSeq[Byte] = _.toIndexedSeq
  override def keyDeserializer: IndexedSeq[Byte] => ByteString = bytes => ByteString(bytes.toArray)
  override def valueSerializer: ByteString => IndexedSeq[Byte] = _.toIndexedSeq
  override def valueDeserializer: IndexedSeq[Byte] => ByteString = bytes => ByteString(bytes.toArray)

  override protected def apply(dataSource: DataSource): CodeStorage = new CodeStorage(dataSource)
