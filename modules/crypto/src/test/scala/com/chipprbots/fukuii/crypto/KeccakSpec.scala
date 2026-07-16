package com.chipprbots.fukuii.crypto

import java.util.concurrent.Executors

import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite

/** Keccak-256/512 byte-exactness and the thread-local digest-reuse parity guarantees.
  *
  * Golden vectors are the go-ethereum `Keccak256` outputs (original-padding Keccak, not FIPS-202 SHA3). The empty-trie
  * root `56e81f...b421` is the canonical `keccak256(RLP("")) = keccak256(0x80)`.
  */
class KeccakSpec extends AnyFunSuite:

  /** Independent oracle: a fresh `new KeccakDigest(256)` per call. */
  private def oracle(inputs: Array[Byte]*): Array[Byte] =
    val d = new KeccakDigest(256)
    val out = new Array[Byte](d.getDigestSize)
    inputs.foreach(i => d.update(i, 0, i.length))
    d.doFinal(out, 0)
    out

  private def hex(bytes: Array[Byte]): String = Hex.toHexString(bytes)

  private val emptyInput = Array.emptyByteArray
  private val abcInput = "abc".getBytes("US-ASCII")
  private val oneByteInput = Array(0x42.toByte)
  private val thirtyTwoByteInput = Array.tabulate(32)(_.toByte)
  private val emptyTrieRlp = Array(0x80.toByte)

  test("kec256 matches fixed golden vectors (go-ethereum Keccak256)"):
    assert(
      hex(kec256(emptyInput)) == "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470" &&
        hex(kec256(abcInput)) == "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45" &&
        hex(kec256(oneByteInput)) == "1f675bff07515f5df96737194ea945c36c41e7b4fcef307b7cd4d0e602a69111" &&
        hex(kec256(thirtyTwoByteInput)) == "8ae1aa597fa146ebd3aa2ceddf360668dea5e526567e92b0321816a4e895bd2d" &&
        hex(kec256(emptyTrieRlp)) == "56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421",
      "kec256 must match all fixed go-ethereum golden vectors"
    )

  test("kec256 output is always 32 bytes and equals a per-call oracle"):
    val corpus = Seq(emptyInput, abcInput, oneByteInput, thirtyTwoByteInput, emptyTrieRlp)
    corpus.foreach { in =>
      val got = kec256(in)
      assert(
        got.length == 32 && got.sameElements(oracle(in)),
        s"kec256 output for $in must be 32 bytes and match the oracle"
      )
    }

  test("kec256(array, start, length) hashes only the requested sub-range"):
    val buf = Array.tabulate(100)(i => (i * 7).toByte)
    assert(
      kec256(buf, 10, 32).sameElements(oracle(buf.slice(10, 42))) &&
        kec256(buf, 0, buf.length).sameElements(kec256(buf)),
      "sub-range hashing must match the oracle and the whole-array hash"
    )

  test("varargs kec256 equals hashing the concatenation (absorb is associative)"):
    val a = Array.tabulate(13)(_.toByte)
    val b = Array.tabulate(29)(i => (i + 100).toByte)
    val c = Array.tabulate(7)(i => (i + 200).toByte)
    assert(
      kec256(a, b, c).sameElements(oracle(a, b, c)) &&
        kec256(a, b, c).sameElements(kec256(a ++ b ++ c)) &&
        !kec256(a, b).sameElements(kec256(b, a)), // ordering is significant
      "varargs kec256 must match the oracle, the concatenated hash, and be order-sensitive"
    )

  test("kec256(ByteString) delegates to the array form byte-identically"):
    val corpus = Seq(emptyInput, abcInput, thirtyTwoByteInput)
    corpus.foreach { in =>
      assert(
        kec256(ByteString(in)) == ByteString(oracle(in)) &&
          kec256(ByteString(in)).toArray.sameElements(kec256(in)),
        s"kec256(ByteString) for $in must match the oracle and the array form"
      )
    }

  test("kec512 matches a per-call KeccakDigest(512) oracle"):
    def oracle512(in: Array[Byte]): Array[Byte] =
      val d = new KeccakDigest(512)
      val out = new Array[Byte](d.getDigestSize)
      d.update(in, 0, in.length)
      d.doFinal(out, 0)
      out
    Seq(emptyInput, abcInput, thirtyTwoByteInput).foreach { in =>
      assert(
        kec512(in).length == 64 && kec512(in).sameElements(oracle512(in)),
        s"kec512 output for $in must be 64 bytes and match the oracle"
      )
    }
    // Empty keccak-512 is a well-known constant.
    assert(hex(kec512(emptyInput)).startsWith("0eab42de4c3ceb9235fc91acffe746b2"))

  test("reset-on-entry recovers after a hash aborts between update and doFinal on the same thread"):
    val pool = Executors.newSingleThreadExecutor()
    given ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      val dirtied = Future(dirtyThreadDigestThenThrow())
      Await.ready(dirtied, 10.seconds)
      val afterAbort = Await.result(Future(kec256(abcInput)), 10.seconds)
      assert(
        dirtied.value.get.isFailure &&
          afterAbort.sameElements(oracle(abcInput)) &&
          hex(afterAbort) == "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
        "the aborted digest must fail and a subsequent hash on the same thread must recover and match the oracle"
      )
    finally
      val _ = pool.shutdownNow()

  test("concurrent hashing across many threads never bleeds state"):
    val nThreads = 16
    val perThread = 1000
    val pool = Executors.newFixedThreadPool(nThreads)
    given ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      val futures = (0 until nThreads).map { t =>
        Future {
          (0 until perThread).forall { i =>
            val input = Array.tabulate(((t * 31 + i) % 200) + 1)(j => ((t << 4) ^ (i + j)).toByte)
            java.util.Arrays.equals(kec256(input), oracle(input))
          }
        }
      }
      val results = Await.result(Future.sequence(futures), 60.seconds)
      assert(results.forall(identity))
    finally
      val _ = pool.shutdownNow()

  test("each thread reuses exactly one digest instance and shares none"):
    val nThreads = 8
    val pool = Executors.newFixedThreadPool(nThreads)
    given ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      val perThread = (0 until nThreads).map { _ =>
        Future {
          val ids = scala.collection.mutable.Set.empty[Int]
          (0 until 50).foreach { i =>
            ids += kec256DigestIdentityForCurrentThread()
            val _ = kec256(Array.tabulate(i + 1)(_.toByte))
            ids += kec256DigestIdentityForCurrentThread()
          }
          (Thread.currentThread().threadId(), ids.toSet)
        }
      }
      val samples = Await.result(Future.sequence(perThread), 30.seconds)
      val byThread = samples.groupBy(_._1).map { case (tid, grp) => tid -> grp.flatMap(_._2).toSet }
      byThread.foreach { case (_, ids) => assert(ids.size == 1) }
      val allIds = byThread.values.flatten.toSeq
      assert(allIds.distinct.size == allIds.size)
    finally
      val _ = pool.shutdownNow()
