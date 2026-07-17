package com.chipprbots.fukuii.rlp

import scala.compiletime.constValue
import scala.compiletime.summonInline
import scala.deriving.Mirror
import scala.util.control.NonFatal

/** Encodes a `T` into the RLP AST. */
trait RLPEncoder[T]:
  def encode(obj: T): RLPEncodeable

object RLPEncoder:
  def apply[T](using ev: RLPEncoder[T]): RLPEncoder[T] = ev
  def instance[T](f: T => RLPEncodeable): RLPEncoder[T] = (obj: T) => f(obj)
  def encode[T](obj: T)(using enc: RLPEncoder[T]): RLPEncodeable = enc.encode(obj)

/** Decodes a `T` out of the RLP AST. */
trait RLPDecoder[T]:
  def decode(rlp: RLPEncodeable): T

object RLPDecoder:
  def apply[T](using ev: RLPDecoder[T]): RLPDecoder[T] = ev
  def instance[T](f: RLPEncodeable => T): RLPDecoder[T] = (rlp: RLPEncodeable) => f(rlp)
  def decode[T](rlp: RLPEncodeable)(using dec: RLPDecoder[T]): T = dec.decode(rlp)

/** A bidirectional RLP codec — both an encoder and a decoder for the same type.
  *
  * This is the DEFAULT the observations doc names for fukuii's RLP layer (reth's alloy `#[derive(RlpEncodable)]`,
  * nethermind's per-type decoder registry): a single compile-time-resolved `given RLPCodec[T]` per type, no runtime
  * reflection walk. The [[RLPCodec.derived]] method below is what makes `case class X(...) derives RLPCodec` actually
  * compile and work.
  *
  * A `trait ... extends RLPEncoder[T], RLPDecoder[T]`, not a type alias to an intersection (`type RLPCodec[T] =
  * RLPEncoder[T] & RLPDecoder[T]`): Scala 3's `derives` clause requires a *class type*, and a type alias to an
  * intersection is rejected as "not a class type" — so the alias form could never be a `derives` target. The trait is
  * semantically identical (an `RLPCodec[T]` still *is* both an `RLPEncoder[T]` and an `RLPDecoder[T]`) and is the
  * derivable equivalent.
  */
trait RLPCodec[T] extends RLPEncoder[T], RLPDecoder[T]

object RLPCodec:
  def apply[T](using ev: RLPCodec[T]): RLPCodec[T] = ev

  /** Combine a separate encoder and decoder into a single codec. */
  def from[T](enc: RLPEncoder[T], dec: RLPDecoder[T]): RLPCodec[T] =
    new RLPCodec[T]:
      def encode(obj: T): RLPEncodeable = enc.encode(obj)
      def decode(rlp: RLPEncodeable): T = dec.decode(rlp)

  extension [A](codec: RLPCodec[A])
    /** Derive a codec for `B` from one for `A` via an isomorphism. */
    def xmap[B](f: A => B, g: B => A): RLPCodec[B] =
      new RLPCodec[B]:
        def encode(obj: B): RLPEncodeable = codec.encode(g(obj))
        def decode(rlp: RLPEncodeable): B = f(codec.decode(rlp))

  /** Compile-time codec derivation for product types (case classes).
    *
    * A product encodes as an `RLPList` of its fields in declaration order and decodes back by requiring exactly that
    * many elements — the straight-field-list default that matches reth's `#[derive(RlpEncodable)]`. Fork-conditional or
    * storage-vs-wire variants (trailing-optional omission, extra witness fields) stay hand-written as an explicit
    * `given`, exactly as reth hand-writes those cases rather than deriving them — keeping the special behavior visible
    * per type. Each field type must have its own `RLPCodec` in scope (`import RLPCodecs.given`).
    */
  inline def derived[T <: Product](using m: Mirror.ProductOf[T]): RLPCodec[T] =
    // `inline` only reaches far enough to resolve the fully-typed field-tuple codec and the field count at
    // compile time; the actual codec object is built by the regular (non-inline) `productCodec`, so the
    // anonymous class is defined once rather than duplicated at every `derives` site (avoids E197).
    productCodec[T](m)(
      summonInline[TupleRLPCodec[m.MirroredElemTypes]],
      constValue[Tuple.Size[m.MirroredElemTypes]]
    )

  /** Type-directed RLP codec for a product's heterogeneous field tuple.
    *
    * Recurses on the tuple's `H *: T` cons structure so every field keeps its precise static type through the fold
    * (`head: RLPCodec[H]` is summoned per element, `tail` handles the rest) — no field is ever erased to an existential
    * `RLPCodec[?]`/`Any`, which is exactly what lets [[productCodec]] run with zero `asInstanceOf`.
    *
    * Public because it is part of the derivation surface: `derived` is `inline`, so its
    * `summonInline[TupleRLPCodec[…]]` resolves against the *call site's* implicit scope in whichever module writes
    * `derives RLPCodec`. These givens live in this companion (always in implicit scope for a `TupleRLPCodec[_]` search,
    * no import needed) and therefore must be accessible from those sites — the same reason a field's `given
    * RLPCodec[H]` must be. Not intended for direct use.
    */
  trait TupleRLPCodec[T <: Tuple]:
    def encode(t: T): List[RLPEncodeable]
    def decode(items: List[RLPEncodeable]): T

  object TupleRLPCodec:
    given TupleRLPCodec[EmptyTuple] with
      def encode(t: EmptyTuple): List[RLPEncodeable] = Nil
      def decode(items: List[RLPEncodeable]): EmptyTuple = EmptyTuple

    given [H, T <: Tuple](using head: RLPCodec[H], tail: TupleRLPCodec[T]): TupleRLPCodec[H *: T] with
      def encode(t: H *: T): List[RLPEncodeable] = head.encode(t.head) :: tail.encode(t.tail)
      def decode(items: List[RLPEncodeable]): H *: T = items match
        case h :: rest => head.decode(h) *: tail.decode(rest)
        // Unreachable in normal flow: `productCodec.decode` arity-checks before recursing, so the element count
        // always matches the tuple length. Kept as a defensive hard failure rather than a silent truncation.
        case Nil => throw RLPException("Cannot decode product: fewer RLP elements than fields")

  private def productCodec[T <: Product](m: Mirror.ProductOf[T])(
      tc: TupleRLPCodec[m.MirroredElemTypes],
      size: Int
  ): RLPCodec[T] =
    new RLPCodec[T]:
      def encode(obj: T): RLPEncodeable =
        RLPList(tc.encode(Tuple.fromProductTyped(obj)(using m))*)

      def decode(rlp: RLPEncodeable): T =
        rlp match
          case list: RLPList =>
            val items = list.items
            if items.length != size then
              throw RLPException(
                s"Cannot decode product: expected $size elements, got ${items.length}",
                rlp
              )
            m.fromProduct(tc.decode(items.toList))
          case _ =>
            throw RLPException("Cannot decode product: expected an RLPList", rlp)

// ---------------------------------------------------------------------------
// Top-level API — the surface everything above L0 uses.
// ---------------------------------------------------------------------------

/** Encode a value to canonical RLP bytes. */
def encode[T](input: T)(using enc: RLPEncoder[T]): Array[Byte] = RLP.encode(enc.encode(input))

/** Serialize an already-built AST node to bytes. */
def encode(input: RLPEncodeable): Array[Byte] = RLP.encode(input)

/** Decode a value from RLP bytes, ignoring any trailing bytes after the first item. */
def decode[T](data: Array[Byte])(using dec: RLPDecoder[T]): T = dec.decode(RLP.rawDecode(data))

/** Decode a value from an AST node. */
def decode[T](data: RLPEncodeable)(using dec: RLPDecoder[T]): T = dec.decode(data)

/** Zone-3 consuming-code syntax (see `scala3-style.md` S13): encode a held value to the RLP AST via its `RLPEncoder`.
  * Symmetric to [[RLPEncodeable.decodeAs]] on the decode side. This is for *consuming* code above L0 that holds a value
  * and wants it encoded; codec-authoring bodies dispatching to a field/sibling codec use explicit `summon[RLPCodec[U]]`
  * instead (S13 Zone 2), never this extension.
  */
extension [A](obj: A) def rlpEncoded(using enc: RLPEncoder[A]): RLPEncodeable = enc.encode(obj)

/** Strict variant of [[decode]]: decodes exactly one self-contained item and throws [[RLPException]] if any trailing
  * bytes remain. Use for buffers that by design hold exactly one item (stored state values, persisted records). Keep
  * the lenient [[decode]] for prefix-plus-payload frames (e.g. an RLPx message whose type byte precedes the payload).
  */
def decodeStrict[T](data: Array[Byte])(using dec: RLPDecoder[T]): T = dec.decode(RLP.rawDecodeStrict(data))

/** Decode to the raw AST without interpreting it. */
def rawDecode(input: Array[Byte]): RLPEncodeable = RLP.rawDecode(input)

/** [[rawDecode]] that rejects trailing bytes. */
def rawDecodeStrict(input: Array[Byte]): RLPEncodeable = RLP.rawDecodeStrict(input)

/** Start position of the next item after the one beginning at `pos`, for walking a stream of items. */
def nextElementIndex(data: Array[Byte], pos: Int): Int = RLP.getItemBounds(data, pos).end + 1

/** Run `f` on `encodeable`, wrapping any failure in an [[RLPException]] tagged with `subject`. */
def tryDecode[T](subject: => String, encodeable: RLPEncodeable)(f: RLPEncodeable => T): T =
  try f(encodeable)
  catch
    case RLPException(message, encodeables) =>
      RLPException.decodeError(subject, message, encodeable :: encodeables)
    case NonFatal(ex) =>
      RLPException.decodeError(subject, ex.getMessage, List(encodeable))
