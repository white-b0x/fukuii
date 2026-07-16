package com.chipprbots.fukuii.evm

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.UInt256

/** Deterministic unit coverage for the 1024-deep immutable word [[Stack]]: push/pop ordering, dup/swap, and the
  * overflow (at [[Stack.DefaultMaxSize]]) / underflow boundaries the YP (9.1) fixes.
  */
class StackSpec extends AnyFunSuite:

  private val a = UInt256(1)
  private val b = UInt256(2)
  private val c = UInt256(3)

  test("push then pop returns the pushed word and the prior stack"):
    val s0 = Stack.empty()
    val (word, s2) = s0.push(a).pop()
    assert(word == a && s2 == s0)

  test("toSeq lists the top-most element first"):
    val s = Stack.empty().push(a).push(b)
    assert(s.toSeq == Seq(b, a))

  test("pop from an empty stack returns Zero and leaves the stack unchanged"):
    val empty = Stack.empty()
    val (word, s) = empty.pop()
    assert(word == UInt256.Zero && s == empty)

  test("pop(n) returns the top n in stack order and the remainder"):
    val s = Stack.empty().push(Seq(a, b, c)) // toSeq: c, b, a
    val (popped, rest) = s.pop(2)
    assert(popped == Seq(c, b) && rest.toSeq == Seq(a))

  test("pop(n) underflow returns n zeroes and the unchanged stack"):
    val s = Stack.empty().push(a)
    val (popped, rest) = s.pop(3)
    assert(popped == Seq.fill(3)(UInt256.Zero) && rest == s)

  test("push a sequence: the last element becomes the top"):
    val s = Stack.empty().push(Seq(a, b, c))
    assert(s.toSeq == Seq(c, b, a))

  test("dup(0) duplicates the top element"):
    val s = Stack.empty().push(a).push(b).dup(0)
    assert(s.toSeq == Seq(b, b, a))

  test("swap(1) swaps the top with the element below it"):
    val s = Stack.empty().push(a).push(b).swap(1) // toSeq before: b, a
    assert(s.toSeq == Seq(a, b))

  test("push overflow at DefaultMaxSize leaves the full stack unchanged"):
    val full = Stack.empty().push(Seq.fill(Stack.DefaultMaxSize)(a))
    val stillFull = full.push(b)
    assert(full.size == Stack.DefaultMaxSize && stillFull.size == Stack.DefaultMaxSize && stillFull == full)

  test("push is bounded by maxSize exactly (one below the limit accepts, at the limit rejects)"):
    val nearFull = Stack.empty().push(Seq.fill(Stack.DefaultMaxSize - 1)(a))
    val accepted = nearFull.push(b)
    assert(accepted.size == Stack.DefaultMaxSize && accepted.push(c).size == Stack.DefaultMaxSize)
