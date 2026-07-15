package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.crypto.kec256
import com.chipprbots.fukuii.rlp.RLPCodec
import com.chipprbots.fukuii.rlp.RLPEncodeable
import com.chipprbots.fukuii.rlp.RLPException
import com.chipprbots.fukuii.rlp.RLPValue

/** The 256-byte (2048-bit) logs-bloom filter carried per-receipt and aggregated into the block header.
  *
  * Opaque wrapper over its backing 256 bytes, matching go-ethereum `core/types/bloom9.go:42` (`type Bloom [256]byte`).
  * [[add]] implements the byte-exact `bloomValues`/`AddWithBuffer` scheme (`bloom9.go:62-73,143-157`): for the
  * keccak256 digest of the added data, the first six bytes are read as three big-endian `uint16` words (bytes 0-1, 2-3,
  * 4-5); each word is masked to its low 11 bits and shifted right 3 to give a byte index counting down from the top of
  * the filter, and the low 3 bits of that word's second byte select which bit of that byte to set.
  */
opaque type Bloom = ByteString

object Bloom:

  val Length: Int = 256

  val Empty: Bloom = ByteString(Array.fill[Byte](Length)(0))

  def apply(bytes: ByteString): Bloom =
    require(bytes.length == Length, s"Bloom must be exactly $Length bytes, got ${bytes.length}")
    bytes

  def apply(bytes: Array[Byte]): Bloom = apply(ByteString(bytes))

  /** The 256-byte filter as a **full fixed-width byte string** (leading zeros preserved) — like [[Address]]/[[Hash]],
    * not a scalar. go-ethereum encodes the header/receipt `Bloom [256]byte` as its raw bytes; decode is strict on the
    * 256-byte length (via [[apply]]'s `require`).
    */
  given RLPCodec[Bloom] = new RLPCodec[Bloom]:
    def encode(obj: Bloom): RLPEncodeable = RLPValue(obj.toArray)
    def decode(rlp: RLPEncodeable): Bloom = rlp match
      case RLPValue(bytes) => Bloom(ByteString(bytes))
      case _               => throw RLPException("src is not an RLPValue for Bloom", rlp)

  /** The aggregate filter over every log in a receipt, matching go-ethereum `CreateBloom` (`bloom9.go:107-119`): each
    * log's address and every one of its topics contribute bits.
    */
  def of(logs: Seq[Log]): Bloom = logs.foldLeft(Empty)((bloom, log) => bloom.add(log))

  private val WordBoundaries: List[(Int, Int)] = List((0, 1), (2, 3), (4, 5))

  extension (b: Bloom)
    def bytes: ByteString = b
    def toArray: Array[Byte] = b.toArray

    /** Add arbitrary bytes to the filter — the general primitive go-ethereum exposes as `Bloom.Add`
      * (`bloom9.go:62-73`). [[add(log:Log)*]] is the log-level convenience built on top of this.
      */
    def add(data: Array[Byte]): Bloom =
      val h = kec256(data)
      val arr = b.toArray
      for (hi, lo) <- WordBoundaries do
        val word = ((h(hi) & 0xff) << 8) | (h(lo) & 0xff)
        val byteIndex = Length - ((word & 0x7ff) >> 3) - 1
        val bitValue = 1 << (h(lo) & 0x7)
        arr(byteIndex) = (arr(byteIndex) | bitValue).toByte
      ByteString(arr)

    /** Add one log's consensus fields (address + topics) to the filter (`bloom9.go:112-116`). */
    def add(log: Log): Bloom =
      val withAddress = b.add(log.address.toArray)
      log.topics.foldLeft(withAddress)((acc, topic) => acc.add(topic.toArray))
