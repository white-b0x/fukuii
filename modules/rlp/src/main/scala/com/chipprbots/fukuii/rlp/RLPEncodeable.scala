package com.chipprbots.fukuii.rlp

import com.chipprbots.fukuii.bytes.Hex

/** A deserialization / malformed-input error.
  *
  * `encodeables` is the stack of values visited while recursing into the structure, which helps pinpoint what went
  * wrong: the last element is the immediate cause, the head the outermost context.
  */
final case class RLPException(message: String, encodeables: List[RLPEncodeable] = Nil) extends RuntimeException(message)

object RLPException:
  def apply(message: String, encodeable: RLPEncodeable): RLPException =
    RLPException(message, List(encodeable))

  def decodeError[T](subject: String, error: String, encodeables: List[RLPEncodeable] = Nil): T =
    throw RLPException(s"Cannot decode $subject: $error", encodeables)

/** The intermediate RLP abstract syntax tree.
  *
  * RLP encodes structure only — an *item* is either a byte string ([[RLPValue]]) or a list of items ([[RLPList]]);
  * interpreting an item as an int/address/etc. is the codec layer's job. This tree is the shape besu's streaming
  * `RLPInput`/`RLPOutput` cursor addresses structurally; fukuii keeps the explicit tree because it composes directly
  * with `Mirror`-based `derives` (each product field is an `RLPEncodeable`, assembled into an `RLPList`). The residual
  * cost is eager per-encode allocation of the tree; the byte engine below offsets it with a single-pass sized-buffer
  * list serialization.
  */
sealed trait RLPEncodeable:
  /** Decode this node as `T`, wrapping any failure with `subject` for context. */
  def decodeAs[T](subject: => String)(using dec: RLPDecoder[T]): T =
    tryDecode[T](subject, this)(dec.decode)

/** A list of items — RLP-encodes with the `0xc0`/`0xf7` list headers. */
final case class RLPList(items: RLPEncodeable*) extends RLPEncodeable:
  def +:(item: RLPEncodeable): RLPList = RLPList((item +: items)*)
  def :+(item: RLPEncodeable): RLPList = RLPList((items :+ item)*)
  def ++(other: RLPList): RLPList = RLPList((items ++ other.items)*)

/** A byte string — RLP-encodes with the `0x80`/`0xb7` string headers (or bare, for a single byte `< 0x80`).
  */
final case class RLPValue(bytes: Array[Byte]) extends RLPEncodeable:
  override def toString: String = s"RLPValue(${Hex.toHexString(bytes)})"

/** A node prefixed by a single raw byte: the serialized form is `prefix || encode(item)`.
  *
  * This is the EIP-2718 typed-envelope shape — a typed transaction or typed receipt is the transaction type byte
  * followed by the RLP payload, *not* itself wrapped in an RLP string. The prefix must be in `[0x00, 0x7f]` so it never
  * collides with an RLP list/string header byte.
  */
final case class PrefixedRLPEncodable(prefix: Byte, prefixedRLPEncodeable: RLPEncodeable) extends RLPEncodeable:
  require(prefix >= 0, "prefix should be in the range [0; 0x7f]")
