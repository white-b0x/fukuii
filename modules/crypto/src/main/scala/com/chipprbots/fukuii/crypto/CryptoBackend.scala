package com.chipprbots.fukuii.crypto

import org.bouncycastle.crypto.AsymmetricCipherKeyPair

import com.chipprbots.fukuii.crypto.zksnark.PairingCheck
import com.chipprbots.fukuii.crypto.zksnark.PairingCheck.G1G2Pair

/** The seam through which every consumer of L0's hot-path cryptographic primitives — Keccak-256, secp256k1
  * sign/recover, and the alt-bn128 pairing check — should route, so that adding a native (JNI) fast path later is a
  * config swap (select a different [[CryptoBackend]] instance) rather than a rewrite of every
  * `sign`/`recover`/`keccak`/`pair` call site.
  *
  * Today L0 ships exactly one implementation, [[CryptoBackend.pureBouncyCastle]], which delegates byte-for-byte to the
  * existing pure-BouncyCastle primitives ([[kec256]], [[ECDSASignature.sign]], [[ECDSASignature.recoverPubBytes]],
  * [[PairingCheck.pairingCheck]]) — it introduces no new arithmetic or canonicalization, only a summonable interface
  * over what already exists. A future native backend must stay output-identical to this one across every input (the
  * differential KAT this seam exists to make possible).
  *
  * R2 (per-instance, never a JVM-global mutable static): selection here is an immutable `val`/ `given`, never a mutable
  * static field flipped at runtime (the besu `SignatureAlgorithmFactory .switchInstance` anti-pattern, which lets one
  * instance's backend choice leak into every other network sharing the process). Two [[CryptoBackend]] values used
  * concurrently are fully independent — this trait carries no mutable state of its own.
  */
trait CryptoBackend:

  /** Keccak-256 over a whole array. */
  def keccak256(input: Array[Byte]): Array[Byte]

  /** Sign a 32-byte message hash with a key pair (deterministic-`k`, low-S canonical — see [[ECDSASignature.sign]]).
    */
  def sign(messageHash: Array[Byte], keyPair: AsymmetricCipherKeyPair): ECDSASignature

  /** Recover the 64-byte (prefix-dropped) public key from a signature and message hash — see
    * [[ECDSASignature.recoverPubBytes]].
    */
  def recoverPublicKey(r: BigInt, s: BigInt, recId: Byte, messageHash: Array[Byte]): Option[Array[Byte]]

  /** The EIP-197 alt-bn128 pairing-set predicate — see [[PairingCheck.pairingCheck]]. */
  def pairingCheck(pairs: Seq[G1G2Pair]): Boolean

object CryptoBackend:

  /** The pure-BouncyCastle backend — L0's only implementation today. Every method is a direct, unmodified delegation to
    * the existing top-level primitive; this wrapper adds no arithmetic of its own, so it is byte-identical to calling
    * the wrapped primitives directly.
    */
  val pureBouncyCastle: CryptoBackend =
    new CryptoBackend:
      def keccak256(input: Array[Byte]): Array[Byte] = kec256(input)

      def sign(messageHash: Array[Byte], keyPair: AsymmetricCipherKeyPair): ECDSASignature =
        ECDSASignature.sign(messageHash, keyPair)

      def recoverPublicKey(r: BigInt, s: BigInt, recId: Byte, messageHash: Array[Byte]): Option[Array[Byte]] =
        ECDSASignature.recoverPubBytes(r, s, recId, messageHash)

      def pairingCheck(pairs: Seq[G1G2Pair]): Boolean =
        PairingCheck.pairingCheck(pairs)

  /** The active backend — an immutable `given`, not a mutable global static (R2). A future native backend is selected
    * by summoning a different [[CryptoBackend]] `given` in the composition root, never by mutating this one.
    */
  given default: CryptoBackend = pureBouncyCastle
