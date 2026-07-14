package com.chipprbots.fukuii.crypto

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.digests.KeccakDigest

/** Keccak-256 and Keccak-512 — the hottest primitive in the client.
  *
  * `kec256` runs once per trie node across millions of nodes on every state-root computation, plus
  * on every transaction hash, block hash, address derivation and log-bloom. Byte output is
  * identical to go-ethereum `crypto/keccak.go:40` (`Keccak256` via `NewLegacyKeccak256`) — the
  * legacy/original Keccak padding (`0x01`), NOT the FIPS-202 SHA3 padding (`0x06`). BouncyCastle's
  * `KeccakDigest` is the original-padding variant, so it matches geth's `golang.org/x/crypto/sha3`
  * legacy digest byte-for-byte.
  *
  * ==Thread-local digest reuse==
  *
  * Each thread reuses one `KeccakDigest` instance instead of allocating a fresh one per hash.
  *
  * Byte-for-byte safety: `KeccakDigest.reset()` invokes the same `init(fixedOutputLength)` path the
  * constructor runs, so a reset digest is observably identical to `new KeccakDigest(256)` for
  * output purposes. Every entry point calls `reset()` on entry (INV-1) — this discards any state
  * left by a prior hash that aborted between `update` and `doFinal`, which the success-only reset
  * inside `doFinal` never covers.
  *
  * Thread-confinement (INV-2): the digest reference MUST NEVER escape its method body — not
  * returned, stored in a field, or captured by a `Future`/closure. Hashing is expected to run on
  * platform-thread dispatchers; do not drive `kec256` from a per-task virtual-thread executor
  * without revisiting this design (millions of vthreads would each lazily allocate an un-pooled
  * digest).
  */

private val kec256Digest: ThreadLocal[KeccakDigest] =
  ThreadLocal.withInitial(() => new KeccakDigest(256))

private val kec512Digest: ThreadLocal[KeccakDigest] =
  ThreadLocal.withInitial(() => new KeccakDigest(512))

/** Keccak-256 over a sub-range of `input`. */
def kec256(input: Array[Byte], start: Int, length: Int): Array[Byte] =
  val d = kec256Digest.get()
  d.reset() // reset-on-entry (INV-1): clear any state left by a prior aborted hash
  val output = new Array[Byte](d.getDigestSize)
  d.update(input, start, length)
  d.doFinal(output, 0)
  output

/** Keccak-256 over a whole array. A dedicated fixed-arity overload so single-array callers do not
  * bind to the varargs form and allocate a wrapper `Seq` per call; overload resolution prefers the
  * fixed-arity method, and the output is byte-identical to the varargs form for one argument.
  */
def kec256(input: Array[Byte]): Array[Byte] =
  kec256(input, 0, input.length)

/** Keccak-256 over the concatenation of several arrays (absorb is associative over concat, so this
  * equals hashing `a ++ b ++ ...` — but without the intermediate concat allocation).
  */
def kec256(inputs: Array[Byte]*): Array[Byte] =
  val d = kec256Digest.get()
  d.reset()
  val output = new Array[Byte](d.getDigestSize)
  inputs.foreach(i => d.update(i, 0, i.length))
  d.doFinal(output, 0)
  output

/** Keccak-256 over a `ByteString`, delegating to the array form (byte-identical). */
def kec256(input: ByteString): ByteString =
  ByteString(kec256(input.toArray))

/** Keccak-512 over a whole array. */
def kec512(input: Array[Byte]): Array[Byte] =
  val d = kec512Digest.get()
  d.reset()
  val output = new Array[Byte](d.getDigestSize)
  d.update(input, 0, input.length)
  d.doFinal(output, 0)
  output

/** Keccak-512 over a `ByteString`. */
def kec512(input: ByteString): ByteString =
  ByteString(kec512(input.toArray))

/** Test-only hook (INV-2 bounded-footprint check): the identity hash of THIS thread's reused
  * digest, WITHOUT exposing the digest reference. Lets a test assert exactly one digest instance
  * per thread and none shared across threads. Never widen to return the digest itself.
  */
private[crypto] def kec256DigestIdentityForCurrentThread(): Int =
  System.identityHashCode(kec256Digest.get())

/** Test-only hook (INV-1 reset-after-abort check): dirty THIS thread's reused digest with
  * `update(...)` then throw BEFORE `doFinal` — exactly the aborted-mid-update window a
  * success-path-only reset never covers. The digest reference never escapes.
  */
private[crypto] def dirtyThreadDigestThenThrow(): Unit =
  val d = kec256Digest.get()
  d.update(Array[Byte](1, 2, 3, 4, 5), 0, 5)
  throw new RuntimeException("aborted between update and doFinal (test hook)")
