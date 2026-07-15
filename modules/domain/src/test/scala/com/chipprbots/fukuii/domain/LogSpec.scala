package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.decode
import com.chipprbots.fukuii.rlp.encode
import com.chipprbots.fukuii.rlp.rawDecode
import org.scalatest.funsuite.AnyFunSuite

/** Log RLP: exactly the 3 consensus fields go-ethereum `core/types/log.go:31-38` encodes. */
class LogSpec extends AnyFunSuite:

  private val address = Address.fromHex("0x00000000000000000000000000000000000ff1")
  private val topic1 = Hash.fromHex("0x" + ("11" * 32))
  private val topic2 = Hash.fromHex("0x" + ("22" * 32))

  test("Log round-trips through RLP"):
    val log = Log(address, List(topic1, topic2), ByteString(1, 2, 3))
    assert(decode[Log](encode(log)) == log)

  test("Log encodes as exactly 3 elements: address, topics-list, data"):
    val log = Log(address, List(topic1), ByteString.empty)
    rawDecode(encode(log)) match
      case RLPList(addr, topics, data, rest*) =>
        assert(rest.isEmpty)
        assert(decode[Address](addr) == address)
        assert(decode[List[Hash]](topics) == List(topic1))
        assert(decode[ByteString](data).isEmpty)
      case other => fail(s"expected a 3-element RLPList, got $other")

  test("a log with no topics round-trips (empty topics list)"):
    val log = Log(address, Nil, ByteString(0xab.toByte))
    assert(decode[Log](encode(log)) == log)
