package com.chipprbots.fukuii.domain

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.RLPList
import com.chipprbots.fukuii.rlp.decode
import com.chipprbots.fukuii.rlp.encode
import com.chipprbots.fukuii.rlp.rawDecode

/** State-account RLP, matching go-ethereum `core/types/state_account.go:31-35` field order (Nonce → Balance →
  * StorageRoot → CodeHash) and the `*uint256.Int` balance width.
  */
class AccountSpec extends AnyFunSuite:

  private val emptyRoot = Hash.fromHex("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")
  private val emptyCode = Hash.fromHex("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")

  test("Account round-trips through RLP"):
    val account = Account(UInt256(7), Wei(UInt256(1000000)), emptyRoot, emptyCode)
    assert(decode[Account](encode(account)) == account)

  test("field order is Nonce -> Balance -> StorageRoot -> CodeHash"):
    val account = Account(UInt256(7), Wei(UInt256(1000000)), emptyRoot, emptyCode)
    rawDecode(encode(account)) match
      case RLPList(nonce, balance, root, codeHash, rest*) =>
        assert(rest.isEmpty)
        assert(decode[UInt256](nonce) == UInt256(7))
        assert(decode[UInt256](balance) == UInt256(1000000))
        assert(decode[Hash](root) == emptyRoot)
        assert(decode[Hash](codeHash) == emptyCode)
      case other => fail(s"expected a 4-element RLPList, got $other")

  test("balance encodes as a 32-byte-bounded quantity, not an unbounded BigInt"):
    // MaxValue is exactly the top of the UInt256 range (2^256 - 1) and must still round-trip; a
    // value one past it is impossible to construct (UInt256.apply rejects out-of-range BigInt),
    // which is itself the width-boundedness guarantee this test is pinning down.
    val account = Account(UInt256.Zero, Wei(UInt256.MaxValue), emptyRoot, emptyCode)
    assert(decode[Account](encode(account)) == account)
    assert(account.balance.bytes.length == UInt256.Size)

  test("balance zero encodes as the RLP empty string, matching a minimal-length scalar"):
    val account = Account(UInt256.Zero, Wei.Zero, emptyRoot, emptyCode)
    rawDecode(encode(account)) match
      case RLPList(_, balance, _, _, _*) => assert(decode[UInt256](balance) == UInt256.Zero)
      case other                         => fail(s"expected a 4-element RLPList, got $other")
