package com.chipprbots.fukuii.rlp

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.ByteUtils
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.rlp.RLP.*

/** The base-type and `bytes` value-type RLP codecs.
  *
  * One `given RLPCodec[T]` per type — a single combined encoder&decoder, not old fukuii's noisy triple (`given
  * RLPEncoder`, `given RLPDecoder`, `given RLPEncoder & RLPDecoder`) per type. Bring them into scope with `import
  * com.chipprbots.fukuii.rlp.RLPCodecs.given` (or `.*`); the `derives RLPCodec` machinery summons them per field at the
  * derivation site.
  *
  * Integer types encode as **minimal-length big-endian scalars** (no leading zeros, `0` ⇒ empty string) — the RLP
  * scalar rule, matched against go-ethereum `rlp/encbuffer.go`. Fixed-width value types ([[Address]], [[Hash]]) encode
  * as their full byte string (leading zeros preserved), while [[UInt256]] is a scalar.
  */
object RLPCodecs:

  /** Enforce the RLP canonical-integer rule on a scalar's decoded bytes: a minimal-length big-endian scalar has no
    * leading zero byte (and `0` is the empty string, never `0x00`). Byte-for-byte the `ErrCanonInt` rejection
    * go-ethereum applies in `rlp/decode.go:750` (single `0x00` byte) and `rlp/decode.go:883,923` (`len(buffer) > 0 &&
    * buffer[0] == 0`). Encode is already minimal; this closes the lenient decode path.
    */
  private def requireMinimalScalar(bytes: Array[Byte], rlp: RLPEncodeable): Unit =
    if bytes.length > 0 && bytes(0) == (0: Byte) then
      throw RLPException("Non-canonical RLP integer: leading zero byte (ErrCanonInt)", rlp)

  given RLPCodec[Byte] = new RLPCodec[Byte]:
    def encode(obj: Byte): RLPEncodeable = RLPValue(byteToByteArray(obj))
    def decode(rlp: RLPEncodeable): Byte = rlp match
      case RLPValue(bytes) =>
        requireMinimalScalar(bytes, rlp)
        if bytes.length == 0 then 0: Byte
        else if bytes.length == 1 then (bytes(0) & 0xff).toByte
        else throw RLPException("src doesn't represent a byte", rlp)
      case _ => throw RLPException("src is not an RLPValue", rlp)

  given RLPCodec[Short] = new RLPCodec[Short]:
    def encode(obj: Short): RLPEncodeable = RLPValue(shortToBigEndianMinLength(obj))
    def decode(rlp: RLPEncodeable): Short = rlp match
      case RLPValue(bytes) =>
        requireMinimalScalar(bytes, rlp)
        if bytes.length == 0 then 0: Short
        else if bytes.length == 1 then (bytes(0) & 0xff).toShort
        else if bytes.length == 2 then (((bytes(0) & 0xff) << 8) + (bytes(1) & 0xff)).toShort
        else throw RLPException("src doesn't represent a short", rlp)
      case _ => throw RLPException("src is not an RLPValue", rlp)

  given RLPCodec[Int] = new RLPCodec[Int]:
    def encode(obj: Int): RLPEncodeable = RLPValue(intToBigEndianMinLength(obj))
    def decode(rlp: RLPEncodeable): Int = rlp match
      case RLPValue(bytes) =>
        requireMinimalScalar(bytes, rlp)
        bigEndianMinLengthToInt(bytes)
      case _ => throw RLPException("src is not an RLPValue", rlp)

  /** Non-negative `BigInt` as a minimal-length unsigned big-endian scalar. */
  given bigIntCodec: RLPCodec[BigInt] = new RLPCodec[BigInt]:
    def encode(obj: BigInt): RLPEncodeable = RLPValue(ByteUtils.bigIntToUnsignedBytes(obj))
    def decode(rlp: RLPEncodeable): BigInt = rlp match
      case RLPValue(bytes) =>
        requireMinimalScalar(bytes, rlp)
        ByteUtils.bytesToBigInt(bytes)
      case _ => throw RLPException("src is not an RLPValue", rlp)

  given RLPCodec[Long] = new RLPCodec[Long]:
    def encode(obj: Long): RLPEncodeable = bigIntCodec.encode(BigInt(obj))
    def decode(rlp: RLPEncodeable): Long = rlp match
      case RLPValue(bytes) if bytes.length <= 8 => bigIntCodec.decode(rlp).toLong
      case RLPValue(bytes) => throw RLPException(s"expected max 8 bytes for Long; got ${bytes.length}", rlp)
      case _               => throw RLPException("src is not an RLPValue", rlp)

  given RLPCodec[String] = new RLPCodec[String]:
    def encode(obj: String): RLPEncodeable = RLPValue(obj.getBytes)
    def decode(rlp: RLPEncodeable): String = rlp match
      case RLPValue(bytes) => new String(bytes)
      case _               => throw RLPException("src is not an RLPValue", rlp)

  given byteArrayCodec: RLPCodec[Array[Byte]] = new RLPCodec[Array[Byte]]:
    def encode(obj: Array[Byte]): RLPEncodeable = RLPValue(obj)
    def decode(rlp: RLPEncodeable): Array[Byte] = rlp match
      case RLPValue(bytes) => bytes
      case _               => throw RLPException("src is not an RLPValue", rlp)

  given RLPCodec[ByteString] = new RLPCodec[ByteString]:
    def encode(obj: ByteString): RLPEncodeable = RLPValue(obj.toArray[Byte])
    def decode(rlp: RLPEncodeable): ByteString = ByteString(byteArrayCodec.decode(rlp))

  /** `true`/`false` as scalar `1`/`0`. */
  given RLPCodec[Boolean] = new RLPCodec[Boolean]:
    def encode(obj: Boolean): RLPEncodeable = RLPValue(byteToByteArray(if obj then 1: Byte else 0: Byte))
    def decode(rlp: RLPEncodeable): Boolean = rlp match
      case RLPValue(bytes) if bytes.length == 0                  => false
      case RLPValue(bytes) if bytes.length == 1 && bytes(0) == 1 => true
      case RLPValue(bytes) if bytes.length == 1 && bytes(0) == 0 => false
      case _                                                     => throw RLPException(s"$rlp should be 1 or 0", rlp)

  given seqCodec[T](using c: RLPCodec[T]): RLPCodec[Seq[T]] = new RLPCodec[Seq[T]]:
    def encode(obj: Seq[T]): RLPEncodeable = RLPList(obj.map(c.encode)*)
    def decode(rlp: RLPEncodeable): Seq[T] = rlp match
      case l: RLPList => l.items.map(c.decode)
      case _          => throw RLPException("src is not a Seq", rlp)

  given listCodec[T](using c: RLPCodec[T]): RLPCodec[List[T]] = new RLPCodec[List[T]]:
    def encode(obj: List[T]): RLPEncodeable = RLPList(obj.map(c.encode)*)
    def decode(rlp: RLPEncodeable): List[T] = rlp match
      case l: RLPList => l.items.map(c.decode).toList
      case _          => throw RLPException("src is not a List", rlp)

  /** `None` ⇒ empty list, `Some(v)` ⇒ single-element list. */
  given optionCodec[T](using c: RLPCodec[T]): RLPCodec[Option[T]] =
    new RLPCodec[Option[T]]:
      def encode(obj: Option[T]): RLPEncodeable = obj match
        case None        => RLPList()
        case Some(value) => RLPList(c.encode(value))
      def decode(rlp: RLPEncodeable): Option[T] = rlp match
        case RLPList(value) => Some(c.decode(value))
        case RLPList()      => None
        case _              => throw RLPException(s"$rlp should be a list with 0 or 1 elements", rlp)

  given tuple2Codec[A, B](using a: RLPCodec[A], b: RLPCodec[B]): RLPCodec[(A, B)] =
    new RLPCodec[(A, B)]:
      def encode(obj: (A, B)): RLPEncodeable = RLPList(a.encode(obj._1), b.encode(obj._2))
      def decode(rlp: RLPEncodeable): (A, B) = rlp match
        case RLPList(x, y, _*) => (a.decode(x), b.decode(y))
        case _                 => throw RLPException("src is not a 2-tuple", rlp)

  given tuple3Codec[A, B, C](using a: RLPCodec[A], b: RLPCodec[B], c: RLPCodec[C]): RLPCodec[(A, B, C)] =
    new RLPCodec[(A, B, C)]:
      def encode(obj: (A, B, C)): RLPEncodeable = RLPList(a.encode(obj._1), b.encode(obj._2), c.encode(obj._3))
      def decode(rlp: RLPEncodeable): (A, B, C) = rlp match
        case RLPList(x, y, z, _*) => (a.decode(x), b.decode(y), c.decode(z))
        case _                    => throw RLPException("src is not a 3-tuple", rlp)

  given tuple4Codec[A, B, C, D](using
      a: RLPCodec[A],
      b: RLPCodec[B],
      c: RLPCodec[C],
      d: RLPCodec[D]
  ): RLPCodec[(A, B, C, D)] =
    new RLPCodec[(A, B, C, D)]:
      def encode(obj: (A, B, C, D)): RLPEncodeable =
        RLPList(a.encode(obj._1), b.encode(obj._2), c.encode(obj._3), d.encode(obj._4))
      def decode(rlp: RLPEncodeable): (A, B, C, D) = rlp match
        case RLPList(w, x, y, z, _*) => (a.decode(w), b.decode(x), c.decode(y), d.decode(z))
        case _                       => throw RLPException("src is not a 4-tuple", rlp)

  given tuple5Codec[A, B, C, D, E](using
      a: RLPCodec[A],
      b: RLPCodec[B],
      c: RLPCodec[C],
      d: RLPCodec[D],
      e: RLPCodec[E]
  ): RLPCodec[(A, B, C, D, E)] =
    new RLPCodec[(A, B, C, D, E)]:
      def encode(obj: (A, B, C, D, E)): RLPEncodeable =
        RLPList(a.encode(obj._1), b.encode(obj._2), c.encode(obj._3), d.encode(obj._4), e.encode(obj._5))
      def decode(rlp: RLPEncodeable): (A, B, C, D, E) = rlp match
        case RLPList(v, w, x, y, z, _*) => (a.decode(v), b.decode(w), c.decode(x), d.decode(y), e.decode(z))
        case _                          => throw RLPException("src is not a 5-tuple", rlp)

  // --- bytes value types --------------------------------------------------

  /** A 20-byte address as a full byte string (not a scalar — leading zeros preserved). Decode is strict on length,
    * matching go-ethereum decoding into the fixed `[20]byte` array type.
    */
  given RLPCodec[Address] = new RLPCodec[Address]:
    def encode(obj: Address): RLPEncodeable = RLPValue(obj.toArray)
    def decode(rlp: RLPEncodeable): Address = rlp match
      case RLPValue(bytes) => Address(ByteString(bytes))
      case _               => throw RLPException("src is not an RLPValue for Address", rlp)

  /** A 32-byte hash as a full byte string; strict-length decode (go-ethereum `[32]byte`). */
  given RLPCodec[Hash] = new RLPCodec[Hash]:
    def encode(obj: Hash): RLPEncodeable = RLPValue(obj.toArray)
    def decode(rlp: RLPEncodeable): Hash = rlp match
      case RLPValue(bytes) => Hash(ByteString(bytes))
      case _               => throw RLPException("src is not an RLPValue for Hash", rlp)

  /** A 256-bit word as a **minimal-length big-endian scalar** — no leading zeros, `0` ⇒ empty string — the exact RLP
    * scalar spec for storage values and quantities.
    */
  given RLPCodec[UInt256] = new RLPCodec[UInt256]:
    def encode(obj: UInt256): RLPEncodeable = RLPValue(ByteUtils.bigIntToUnsignedBytes(obj.toBigInt))
    def decode(rlp: RLPEncodeable): UInt256 = rlp match
      case RLPValue(bytes) =>
        requireMinimalScalar(bytes, rlp)
        UInt256.fromBytes(bytes)
      case _ => throw RLPException("src is not an RLPValue for UInt256", rlp)
