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
    assert(a + FiniteField[Fp].zero == a)
    assert(a - a == FiniteField[Fp].zero)
    assert(a + a.negated() == FiniteField[Fp].zero)

  test("Fp multiplicative identity and inverse"):
    assert(a * FiniteField[Fp].one == a)
    assert((a * a.inversed()) == FiniteField[Fp].one)

  test("Fp doubled and squared match add/mul"):
    assert(a.doubled() == a + a)
    assert(a.squared() == a * a)

  test("Fp commutativity and the extension operators resolve through the given"):
    assert(a + b == b + a)
    assert(a * b == b * a)

  test("Fp2/Fp6/Fp12 inverses via the tower give one"):
    val x2 = Fp2(a, b)
    assert((x2 * x2.inversed()) == FiniteField[Fp2].one)
    val x6 = Fp6(x2, FiniteField[Fp2].one, x2)
    assert((x6 * x6.inversed()) == FiniteField[Fp6].one)
    val x12 = Fp12(x6, FiniteField[Fp6].one)
    assert((x12 * x12.inversed()) == FiniteField[Fp12].one)

  test("field elements are valid iff in range"):
    assert(a.isValid())
    assert(!Fp(Fp.P).isValid()) // p itself is out of range [0, p)
    assert(FiniteField[Fp].zero.isZero())
