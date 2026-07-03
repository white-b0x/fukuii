package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import boopickle.Default.*

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TxHash
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.utils.ByteUtils.byteSequenceToBuffer
import com.chipprbots.ethereum.utils.ByteUtils.compactPickledBytes

class TransactionMappingStorage(val dataSource: DataSource)
    extends TransactionalKeyValueStorage[TxHash, TransactionLocation]:

  val namespace: IndexedSeq[Byte] = Namespaces.TransactionMappingNamespace
  def keySerializer: TxHash => IndexedSeq[Byte] = identity
  def keyDeserializer: IndexedSeq[Byte] => TxHash = identity
  def valueSerializer: TransactionLocation => IndexedSeq[Byte] = tl => compactPickledBytes(Pickle.intoBytes(tl))
  def valueDeserializer: IndexedSeq[Byte] => TransactionLocation =
    byteSequenceToBuffer.andThen(Unpickle[TransactionLocation].fromBytes)

  given byteStringPickler: Pickler[ByteString] =
    transformPickler[ByteString, Array[Byte]](ByteString(_))(_.toArray[Byte])

  given blockHashPickler: Pickler[BlockHash] =
    transformPickler[BlockHash, Array[Byte]](bs => BlockHash(ByteString(bs)))(_.value.toArray[Byte])

object TransactionMappingStorage:
  type TxHash = IndexedSeq[Byte]

  case class TransactionLocation(blockHash: BlockHash, txIndex: Int)
