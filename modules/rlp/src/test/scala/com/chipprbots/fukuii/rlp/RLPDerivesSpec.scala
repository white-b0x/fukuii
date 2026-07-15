package com.chipprbots.fukuii.rlp

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.rlp.RLPCodecs.given

// The headline proof: `derives RLPCodec` compiles and produces a working codec. Old fukuii had the
// Mirror machinery but the `RLPCodec` alias exposed no `derived`, so this line did not compile.

final case class Person(age: Int, name: String, active: Boolean) derives RLPCodec

final case class AccountRecord(nonce: UInt256, balance: UInt256, owner: Address) derives RLPCodec

final case class Wrapper(id: Int, person: Person) derives RLPCodec

/** Compile-time `derives RLPCodec` derivation for product types. */
class RLPDerivesSpec extends AnyFunSuite:

  test("derives RLPCodec provides a summonable codec"):
    assert(Option(summon[RLPCodec[Person]]).isDefined)

  test("a derived case class round-trips through encode/decode"):
    val p = Person(30, "alice", true)
    assert(decode[Person](encode(p)) == p)

  test("a derived case class encodes as an RLP list of its fields, in declaration order"):
    val p = Person(30, "alice", true)
    // age 30 = 0x1e ; "alice" = 0x85 61 6c 69 63 65 ; active true = 0x01
    // payload = 1e + 8561…65 (6 bytes) + 01 = 8 bytes ⇒ list header 0xc8
    assert(Hex.toHexString(encode(p)) == "c81e8561" + "6c696365" + "01")

  test("derivation composes over the bytes value types (scalar UInt256 + fixed-width Address)"):
    val rec = AccountRecord(UInt256(7), UInt256(1000000), Address.fromHex("0x00000000000000000000000000000000000000ff"))
    assert(decode[AccountRecord](encode(rec)) == rec)

  test("nested derived case classes compose"):
    val w = Wrapper(1, Person(99, "bob", false))
    assert(decode[Wrapper](encode(w)) == w)

  test("decoding fails when the element count does not match the product arity"):
    // a 2-element list cannot become a 3-field Person
    val short = encode(RLPList(RLPEncoder.encode(1), RLPEncoder.encode("x")))
    intercept[RLPException](decode[Person](short))

  test("decoding a non-list into a product fails"):
    intercept[RLPException](decode[Person](encode(42)))
