package com.chipprbots.fukuii.evm

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.UInt256

/** Deterministic unit coverage for the byte-addressable [[Memory]]: store/load round-trips, automatic zero-fill
  * expansion at read/write boundaries, and `expand`.
  */
class MemorySpec extends AnyFunSuite:

  test("store then load a word at offset 0 round-trips"):
    val word = UInt256(0xdead)
    val (loaded, _) = Memory.empty.store(UInt256.Zero, word).load(UInt256.Zero)
    assert(loaded == word)

  test("storing a word grows memory to at least 32 bytes"):
    val mem = Memory.empty.store(UInt256.Zero, UInt256(1))
    assert(mem.size == UInt256.Size)

  test("loading a word from uninitialised memory zero-fills and expands"):
    val (loaded, mem) = Memory.empty.load(UInt256.Zero)
    assert(loaded == UInt256.Zero && mem.size == UInt256.Size)

  test("store at a non-zero offset expands to cover offset + data length"):
    val mem = Memory.empty.store(UInt256(64), UInt256(1))
    assert(mem.size == 64 + UInt256.Size)

  test("load(offset, size) returns exactly size bytes, zero-filled beyond written data"):
    val data = ByteString(1, 2, 3)
    val (loaded, _) = Memory.empty.store(UInt256.Zero, data.toArray).load(UInt256.Zero, UInt256(5))
    assert(loaded == (data ++ ByteString(0, 0)))

  test("storing empty data leaves memory unchanged"):
    val mem = Memory.empty.store(UInt256.Zero, ByteString.empty)
    assert(mem.size == 0)

  test("store overwrites previously written bytes at the same offset"):
    val mem = Memory.empty.store(UInt256.Zero, UInt256(1)).store(UInt256.Zero, UInt256(2))
    val (loaded, _) = mem.load(UInt256.Zero)
    assert(loaded == UInt256(2))

  test("expand grows memory to offset + size without shrinking"):
    val expanded = Memory.empty.expand(UInt256.Zero, UInt256(96))
    assert(expanded.size == 96)

  test("expand with zero size is a no-op"):
    val mem = Memory.empty.store(UInt256.Zero, UInt256(1))
    assert(mem.expand(UInt256.Zero, UInt256.Zero).size == mem.size)

  test("a single byte store at offset expands to offset + 1"):
    val mem = Memory.empty.store(UInt256(10), 0xff.toByte)
    assert(mem.size == 11)
