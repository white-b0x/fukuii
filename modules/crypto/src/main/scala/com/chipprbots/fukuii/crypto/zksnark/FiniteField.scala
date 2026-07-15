package com.chipprbots.fukuii.crypto.zksnark

/** Typeclass for arithmetic over a finite field `A` — the abstraction the BN128 pairing tower (`Fp` ⊂ `Fp2` ⊂ `Fp6` ⊂
  * `Fp12`) is written against.
  *
  * Scala 3 idiom: field instances are `given`s (was `implicit object`), and the fluent operators live in
  * [[FiniteField]]'s `extension` block (was the `implicit class Ops`, a flagged Scala-2 anti-pattern in old fukuii).
  * `import FiniteField.*` brings the operators into scope; the `given`s resolve from the field companion objects with
  * no import.
  */
trait FiniteField[A]:
  def zero: A
  def one: A
  def add(a: A, b: A): A
  def mul(a: A, b: A): A
  def sub(a: A, b: A): A
  def inv(a: A): A
  def neg(a: A): A
  def sqr(a: A): A = mul(a, a)
  def dbl(a: A): A = add(a, a)
  def isZero(a: A): Boolean
  def isValid(a: A): Boolean

object FiniteField:

  /** Summon the field instance for `A` — supports `FiniteField[Fp].one` call sites. */
  def apply[A](using field: FiniteField[A]): FiniteField[A] = field

  extension [A](a: A)(using F: FiniteField[A])
    def +(b: A): A = F.add(a, b)
    def *(b: A): A = F.mul(a, b)
    def -(b: A): A = F.sub(a, b)
    def doubled(): A = F.dbl(a)
    def squared(): A = F.sqr(a)
    def inversed(): A = F.inv(a)
    def negated(): A = F.neg(a)
    def isValid(): Boolean = F.isValid(a)
    def isZero(): Boolean = F.isZero(a)
