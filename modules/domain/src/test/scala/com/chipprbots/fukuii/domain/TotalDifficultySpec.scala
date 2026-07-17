package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.rlp.RLPCodecs.given
import com.chipprbots.fukuii.rlp.decode
import com.chipprbots.fukuii.rlp.encode

/** The L1 [[TotalDifficulty]] fork-choice weight quantity: construction, heavier-chain ordering, Σ-accumulation via
  * `increase`/`add`, and the consensus-relevant byte shape (minimal-length big-endian RLP scalar, identical to the
  * underlying [[UInt256]]) that the L2 `chain-weight` CF round-trips.
  */
class TotalDifficultySpec extends AnyFunSuite:

  private val h0 = Hash.fromHex("0x0000000000000000000000000000000000000000000000000000000000000000")
  private val coinbase = Address.fromHex("0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba")

  private def headerWithDifficulty(d: BigInt): BlockHeader = BlockHeader(
    parentHash = h0,
    ommersHash = h0,
    beneficiary = coinbase,
    stateRoot = h0,
    transactionsRoot = h0,
    receiptsRoot = h0,
    logsBloom = Bloom.Empty,
    difficulty = d,
    number = BigInt(1),
    gasLimit = 0x7fffffffffffffffL,
    gasUsed = 0,
    unixTimestamp = 0,
    extraData = ByteString.empty,
    mixHash = h0,
    nonce = ByteString(Array.fill[Byte](8)(0))
  )

  test("construction from UInt256, BigInt, and Zero agree"):
    assert(
      TotalDifficulty(UInt256(1000)).toBigInt == BigInt(1000) &&
        TotalDifficulty.fromBigInt(BigInt(1000)).toBigInt == BigInt(1000) &&
        TotalDifficulty.Zero.toBigInt == BigInt(0)
    )

  test("Ordering selects the heavier chain — greater total difficulty is greater"):
    val heavier = TotalDifficulty.fromBigInt(BigInt(1000))
    val lighter = TotalDifficulty.fromBigInt(BigInt(999))
    val ord = summon[Ordering[TotalDifficulty]]
    assert(
      ord.gt(heavier, lighter) &&
        ord.max(heavier, lighter) == heavier &&
        !ord.gt(lighter, heavier)
    )

  test("increase(header) accumulates the header's difficulty — Σ + header.difficulty"):
    val start = TotalDifficulty.fromBigInt(BigInt(5_000_000))
    val next = start.increase(headerWithDifficulty(BigInt(2_000_000)))
    assert(next.toBigInt == BigInt(7_000_000))

  test("increase from Zero equals the header's own difficulty (genesis-relative)"):
    val td = TotalDifficulty.Zero.increase(headerWithDifficulty(BigInt(17_179_869_184L)))
    assert(td.toBigInt == BigInt(17_179_869_184L))

  test("add sums two weights"):
    val sum = TotalDifficulty.fromBigInt(BigInt(400)).add(TotalDifficulty.fromBigInt(BigInt(600)))
    assert(sum.toBigInt == BigInt(1000))

  test("RLP round-trips as a minimal-length big-endian scalar, identical to the underlying UInt256"):
    val td = TotalDifficulty(UInt256(0x0102030405L))
    assert(
      decode[TotalDifficulty](encode(td)) == td &&
        encode(td).sameElements(encode(td.toUInt256)),
      "TotalDifficulty must RLP round-trip and encode identically to its underlying UInt256 scalar"
    )

  test("TotalDifficulty.Zero encodes as the empty string (RLP scalar rule)"):
    assert(encode(TotalDifficulty.Zero).sameElements(encode(UInt256.Zero)))

  test("the full UInt256 range round-trips and stays 32-byte bounded"):
    val max = TotalDifficulty(UInt256.MaxValue)
    assert(
      decode[TotalDifficulty](encode(max)) == max &&
        max.bytes.length == UInt256.Size,
      "the max weight must RLP round-trip and stay bounded to UInt256's byte size"
    )

  test("TotalDifficulty is type-distinct from a raw UInt256"):
    assertDoesNotCompile("val td: TotalDifficulty = UInt256(1)")
