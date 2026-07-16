package com.chipprbots.fukuii.crypto.zksnark

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.crypto.zksnark.FiniteField.*

/** Field-axiom sanity for the `given`/`extension` wiring that replaced old fukuii's `implicit object` + `implicit class
  * Ops`.
  */
class FiniteFieldSpec extends AnyFunSuite:

  private val a: Fp = Fp(BigInt(12345))
  private val b: Fp = Fp(BigInt(67890))

  test("Fp additive identity and additive inverse"):
    assert(
      a + FiniteField[Fp].zero == a &&
        a - a == FiniteField[Fp].zero &&
        a + a.negated() == FiniteField[Fp].zero,
      "Fp addition must have a zero identity and every element must have an additive inverse"
    )

  test("Fp multiplicative identity and inverse"):
    assert(
      a * FiniteField[Fp].one == a &&
        (a * a.inversed()) == FiniteField[Fp].one,
      "Fp multiplication must have a one identity and every element must have a multiplicative inverse"
    )

  test("Fp doubled and squared match add/mul"):
    assert(
      a.doubled() == a + a &&
        a.squared() == a * a,
      "doubled() must equal a + a and squared() must equal a * a"
    )

  test("Fp commutativity and the extension operators resolve through the given"):
    assert(
      a + b == b + a &&
        a * b == b * a,
      "Fp addition and multiplication must be commutative"
    )

  test("Fp2/Fp6/Fp12 inverses via the tower give one"):
    val x2 = Fp2(a, b)
    val x6 = Fp6(x2, FiniteField[Fp2].one, x2)
    val x12 = Fp12(x6, FiniteField[Fp6].one)
    assert(
      (x2 * x2.inversed()) == FiniteField[Fp2].one &&
        (x6 * x6.inversed()) == FiniteField[Fp6].one &&
        (x12 * x12.inversed()) == FiniteField[Fp12].one,
      "Fp2/Fp6/Fp12 tower elements must each satisfy x * x.inversed() == one"
    )

  test("field elements are valid iff in range"):
    assert(
      a.isValid() &&
        !Fp(Fp.P).isValid() && // p itself is out of range [0, p)
        FiniteField[Fp].zero.isZero(),
      "an in-range element must be valid, p itself must be invalid, and zero must report isZero"
    )
