package com.chipprbots.fukuii.domain

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.rlp.decode
import com.chipprbots.fukuii.rlp.encode
import org.scalatest.funsuite.AnyFunSuite

/** EIP-2930 access-list entry RLP — `derives RLPCodec`, byte-exact to go-ethereum `tx_access_list.go` `AccessTuple{
  * Address; StorageKeys []common.Hash }`.
  */
class AccessListEntrySpec extends AnyFunSuite:

  private val addr = Address.fromHex("0x095e7baea6a6c7c4c2dfeb977efac326af552d87")

  // Built via string repetition rather than hand-typed hex literals, so a 32-byte width can't drift by a
  // stray digit (Hash.apply/fromHex fail loud on the wrong length, so a miscount here would fail at
  // construction time rather than silently producing a shorter/longer key).
  private def hashOf(lastByteHex: String): Hash = Hash.fromHex(("00" * 31) + lastByteHex)

  test("round-trips with zero storage keys"):
    val entry = AccessListEntry(addr, List.empty)
    assert(decode[AccessListEntry](encode(entry)) == entry)

  test("round-trips with one storage key"):
    val entry = AccessListEntry(addr, List(hashOf("01")))
    assert(decode[AccessListEntry](encode(entry)) == entry)

  test("round-trips with N (3) storage keys"):
    val keys = List(hashOf("01"), Hash.fromHex("0x" + ("ff" * 32)), Hash.Zero)
    val entry = AccessListEntry(addr, keys)
    assert(decode[AccessListEntry](encode(entry)) == entry)
