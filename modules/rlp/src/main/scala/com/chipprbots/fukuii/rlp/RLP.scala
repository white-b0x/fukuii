package com.chipprbots.fukuii.rlp

import java.nio.ByteBuffer

import scala.annotation.switch
import scala.annotation.tailrec
import scala.collection.immutable.Queue

/** The RLP byte engine: [[RLPEncodeable]] ⇄ `Array[Byte]`.
  *
  * Byte layout is consensus-critical — it feeds every state root, transaction hash and block hash — and is matched
  * byte-for-byte against go-ethereum (`rlp/raw.go`, `rlp/encbuffer.go`, `rlp/decode.go`). The rules:
  *   - a single byte in `[0x00, 0x7f]` is its own encoding;
  *   - a 0–55 byte string is `0x80 + len`, then the bytes; longer is `0xb7 + lenOfLen`, the length, then the bytes;
  *   - a list with a 0–55 byte payload is `0xc0 + len`, then the concatenated item encodings; longer is `0xf7 +
  *     lenOfLen`, the length, then the items.
  */
private[rlp] object RLP:

  /** 56 is the string/list length cutoff between the short and long header forms (go-ethereum uses the same constant):
    * it maximizes the short-form range while leaving room for a `2^64` long form.
    */
  private val SizeThreshold: Int = 56

  /** Upper bound on a single item's length (`256^8`). */
  private val MaxItemLength: Double = math.pow(256, 8)

  /** Widest length-of-length the JVM can address: a payload length wider than 4 bytes overflows an `Int` array index,
    * so a long-form header declaring a longer length field is rejected (besu
    * `RLPDecodingHelpers.extractSizeFromLongItem`, geth `rlp/raw.go` `ErrValueTooLarge`).
    */
  private val MaxLengthOfLength: Int = Integer.BYTES

  /** `0x80` — short-string header base. */
  private val OffsetShortItem: Int = 0x80

  /** `0xb7` — long-string header base. */
  private val OffsetLongItem: Int = 0xb7

  /** `0xc0` — short-list header base. */
  private val OffsetShortList: Int = 0xc0

  /** `0xf7` — long-list header base. */
  private val OffsetLongList: Int = 0xf7

  /** Decode a single RLP item from `data`, ignoring any trailing bytes. */
  def rawDecode(data: Array[Byte]): RLPEncodeable = decodeWithPos(data, 0)._1

  /** Like [[rawDecode]], but requires the whole buffer to be consumed by exactly one item; throws [[RLPException]] on
    * any trailing bytes. Use for buffers that by design hold a single self-contained item (stored state values,
    * persisted records). A prefix-plus-payload frame — e.g. an RLPx message — must keep using the lenient
    * [[rawDecode]].
    */
  def rawDecodeStrict(data: Array[Byte]): RLPEncodeable =
    val (encodeable, endPos) = decodeWithPos(data, 0)
    if endPos != data.length then
      throw RLPException(s"Malformed RLP: item consumed $endPos of ${data.length} bytes, trailing bytes remain")
    encodeable

  /** Serialize an [[RLPEncodeable]] to its canonical bytes. */
  def encode(input: RLPEncodeable): Array[Byte] =
    input match
      case list: RLPList =>
        // Build the concatenated payload in one sized buffer rather than `foldLeft(Array())(_ ++ _)`,
        // which reallocates and recopies the whole accumulator per item (O(n^2) in list size).
        // Byte-identical: items encode in order, with the same list header prefix. Two passes —
        // encode each item once, sum the lengths, then arraycopy.
        val encodedItems = list.items.map(encode)
        var contentLen = 0
        encodedItems.foreach(contentLen += _.length)
        val header = encodeLength(contentLen, OffsetShortList)
        val out = new Array[Byte](header.length + contentLen)
        System.arraycopy(header, 0, out, 0, header.length)
        var pos = header.length
        encodedItems.foreach { item =>
          System.arraycopy(item, 0, out, pos, item.length)
          pos += item.length
        }
        out
      case value: RLPValue =>
        val bytes = value.bytes
        if bytes.length == 1 && (bytes(0) & 0xff) < 0x80 then bytes
        else encodeLength(bytes.length, OffsetShortItem) ++ bytes
      case PrefixedRLPEncodable(prefix, prefixedRLPEncodeable) =>
        prefix +: encode(prefixedRLPEncodeable)

  /** Minimal-length big-endian bytes of a single byte value: `0` ⇒ empty. */
  def byteToByteArray(singleByte: Byte): Array[Byte] =
    if (singleByte & 0xff) == 0 then Array.empty[Byte]
    else Array[Byte](singleByte)

  /** Minimal-length big-endian bytes of a `Short`. */
  def shortToBigEndianMinLength(singleShort: Short): Array[Byte] =
    if (singleShort & 0xff) == singleShort then byteToByteArray(singleShort.toByte)
    else Array[Byte]((singleShort >> 8 & 0xff).toByte, (singleShort >> 0 & 0xff).toByte)

  /** Minimal-length big-endian bytes of an `Int`. */
  def intToBigEndianMinLength(singleInt: Int): Array[Byte] =
    if singleInt == (singleInt & 0xff) then byteToByteArray(singleInt.toByte)
    else if singleInt == (singleInt & 0xffff) then shortToBigEndianMinLength(singleInt.toShort)
    else if singleInt == (singleInt & 0xffffff) then
      Array[Byte]((singleInt >>> 16).toByte, (singleInt >>> 8).toByte, singleInt.toByte)
    else Array[Byte]((singleInt >>> 24).toByte, (singleInt >>> 16).toByte, (singleInt >>> 8).toByte, singleInt.toByte)

  /** Read a big-endian `Int` from minimal-length bytes. */
  def bigEndianMinLengthToInt(bytes: Array[Byte]): Int =
    bigEndianMinLengthToInt(bytes, 0, bytes.length)

  /** Read a big-endian `Int` from `data` at `offset` spanning `len` bytes, without allocating a slice. */
  def bigEndianMinLengthToInt(data: Array[Byte], offset: Int, len: Int): Int =
    (len: @switch) match
      case 0 => 0
      case 1 => data(offset) & 0xff
      case 2 => ((data(offset) & 0xff) << 8) + (data(offset + 1) & 0xff)
      case 3 => ((data(offset) & 0xff) << 16) + ((data(offset + 1) & 0xff) << 8) + (data(offset + 2) & 0xff)
      case Integer.BYTES =>
        ((data(offset) & 0xff) << 24) + ((data(offset + 1) & 0xff) << 16) +
          ((data(offset + 2) & 0xff) << 8) + (data(offset + 3) & 0xff)
      case _ => throw RLPException("Bytes don't represent an int")

  /** Read a big-endian item-header length field spanning `len` bytes (`len <= MaxLengthOfLength`) as an unsigned
    * `Long`. Carrying the length in `Long` keeps a 4-byte length with the high bit set positive (a signed `Int` read
    * would wrap negative, J-RLP-3) and lets the caller range-check the payload end in a non-overflowing type before any
    * `Int` truncation (J-RLP-1) — the besu `Math.toIntExact` gate.
    */
  private def bigEndianLengthToLong(data: Array[Byte], offset: Int, len: Int): Long =
    var result = 0L
    var i = 0
    while i < len do
      result = (result << 8) | (data(offset + i) & 0xffL)
      i += 1
    result

  private def intToBytesNoLeadZeroes(value: Int): Array[Byte] =
    ByteBuffer.allocate(Integer.BYTES).putInt(value).array().dropWhile(_ == (0: Byte))

  private def encodeLength(length: Int, offset: Int): Array[Byte] =
    if length < SizeThreshold then Array((length + offset).toByte)
    else if length < MaxItemLength && length > 0xff then
      val binaryLength: Array[Byte] = intToBytesNoLeadZeroes(length)
      (binaryLength.length + offset + SizeThreshold - 1).toByte +: binaryLength
    else if length < MaxItemLength && length <= 0xff then Array((1 + offset + SizeThreshold - 1).toByte, length.toByte)
    else throw RLPException("Input too long")

  /** Locate the payload bounds of the single RLP item starting at `pos`. */
  def getItemBounds(data: Array[Byte], pos: Int): ItemBounds =
    if data.isEmpty then throw RLPException("Empty Data")
    else if pos >= data.length then
      throw RLPException(s"Truncated RLP: attempt to read item at position $pos of ${data.length} bytes")
    else
      val prefix: Int = data(pos) & 0xff
      if prefix == OffsetShortItem then ItemBounds(start = pos, end = pos, isList = false, isEmpty = true)
      else if prefix < OffsetShortItem then ItemBounds(start = pos, end = pos, isList = false)
      else if prefix <= OffsetLongItem then
        val length = prefix - OffsetShortItem
        val end = pos + length
        // F-RLP-1 (ErrCanonSize, go-ethereum rlp/raw.go:360-363): a single byte < 0x80 is its own
        // encoding and must not be wrapped as a `0x81 xx` short string.
        if length == 1 && pos + 1 < data.length && (data(pos + 1) & 0xff) < 0x80 then
          throw RLPException("Non-canonical RLP: single byte < 0x80 must not be string-wrapped (ErrCanonSize)")
        // F-RLP-3 (ErrValueTooLarge, go-ethereum rlp/raw.go:380-383): payload must fit the buffer.
        if end >= data.length then
          throw RLPException("Truncated RLP: string payload extends beyond data (ErrValueTooLarge)")
        ItemBounds(start = pos + 1, end = end, isList = false)
      else if prefix < OffsetShortList then
        val lengthOfLength = prefix - OffsetLongItem
        // J-RLP-2 (besu extractSizeFromLongItem): a length field wider than an Int can index cannot
        // address a JVM array, so reject it with a self-describing message before reading.
        if lengthOfLength > MaxLengthOfLength then
          throw RLPException("Truncated RLP: length-of-length exceeds max supported size (ErrValueTooLarge)")
        if pos + 1 + lengthOfLength > data.length then
          throw RLPException("Truncated RLP: length-of-length prefix extends beyond data")
        // J-RLP-1/J-RLP-3: carry the length in Long so a 4-byte length with the high bit set stays
        // positive and the payload-end range-check below cannot overflow to a negative Int.
        val length = bigEndianLengthToLong(data, pos + 1, lengthOfLength)
        // F-RLP-1 (ErrCanonSize, go-ethereum rlp/raw.go:410-414): long-form header is only canonical
        // when the payload is >= 56 bytes and the length field has no leading zero.
        if length < SizeThreshold then
          throw RLPException("Non-canonical RLP: length < 56 must use the short-form header (ErrCanonSize)")
        if (data(pos + 1) & 0xff) == 0 then
          throw RLPException("Non-canonical RLP: leading zero in length-of-length (ErrCanonSize)")
        val beginPos = pos + 1 + lengthOfLength
        val end = beginPos.toLong + length - 1
        // F-RLP-3 (ErrValueTooLarge, go-ethereum rlp/raw.go:380-383): payload must fit the buffer. The
        // comparison is in Long (J-RLP-1) so an oversize length cannot wrap the guard.
        if end >= data.length then
          throw RLPException("Truncated RLP: string payload extends beyond data (ErrValueTooLarge)")
        ItemBounds(start = beginPos, end = end.toInt, isList = false)
      else if prefix <= OffsetLongList then
        val length = prefix - OffsetShortList
        val end = pos + length
        if end >= data.length then
          throw RLPException("Truncated RLP: list payload extends beyond data (ErrValueTooLarge)")
        ItemBounds(start = pos + 1, end = end, isList = true)
      else
        val lengthOfLength = prefix - OffsetLongList
        // J-RLP-2 (besu extractSizeFromLongItem): reject a length field wider than an Int can index.
        if lengthOfLength > MaxLengthOfLength then
          throw RLPException("Truncated RLP: length-of-length exceeds max supported size (ErrValueTooLarge)")
        if pos + 1 + lengthOfLength > data.length then
          throw RLPException("Truncated RLP: length-of-length prefix extends beyond data")
        // J-RLP-1/J-RLP-3: carry the length in Long so an oversize length cannot wrap the guard below.
        val length = bigEndianLengthToLong(data, pos + 1, lengthOfLength)
        // F-RLP-1 (ErrCanonSize, go-ethereum rlp/raw.go:410-414): same canonical long-form rules for lists.
        if length < SizeThreshold then
          throw RLPException("Non-canonical RLP: length < 56 must use the short-form header (ErrCanonSize)")
        if (data(pos + 1) & 0xff) == 0 then
          throw RLPException("Non-canonical RLP: leading zero in length-of-length (ErrCanonSize)")
        val beginPos = pos + 1 + lengthOfLength
        val end = beginPos.toLong + length - 1
        // F-RLP-3 (ErrValueTooLarge, go-ethereum rlp/raw.go:380-383): payload must fit the buffer; compared
        // in Long (J-RLP-1) so an oversize length cannot wrap the guard.
        if end >= data.length then
          throw RLPException("Truncated RLP: list payload extends beyond data (ErrValueTooLarge)")
        ItemBounds(start = beginPos, end = end.toInt, isList = true)

  private def decodeWithPos(data: Array[Byte], pos: Int): (RLPEncodeable, Int) =
    if data.isEmpty then throw RLPException("data is too short")
    else
      getItemBounds(data, pos) match
        case ItemBounds(start, end, false, isEmpty) =>
          RLPValue(if isEmpty then Array.empty[Byte] else data.slice(start, end + 1)) -> (end + 1)
        case ItemBounds(start, end, true, _) =>
          RLPList(decodeListRecursive(data, start, end - start + 1, Queue())*) -> (end + 1)

  @tailrec
  private def decodeListRecursive(
      data: Array[Byte],
      pos: Int,
      length: Int,
      acum: Queue[RLPEncodeable]
  ): Queue[RLPEncodeable] =
    if length == 0 then acum
    else
      val (decoded, decodedEnd) = decodeWithPos(data, pos)
      decodeListRecursive(data, decodedEnd, length - (decodedEnd - pos), acum :+ decoded)

/** Payload bounds of one RLP item: `[start, end]` inclusive, plus its list/empty flags. */
final private[rlp] case class ItemBounds(start: Int, end: Int, isList: Boolean, isEmpty: Boolean = false)
