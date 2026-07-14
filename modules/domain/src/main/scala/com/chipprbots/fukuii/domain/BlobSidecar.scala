package com.chipprbots.fukuii.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.crypto.sha256

/** The EIP-4844 blob sidecar — the `blobs` / `commitments` / `proofs` a blob transaction carries **only** in the P2P
  * network-wrapper encoding, never in the consensus encoding (go-ethereum `core/types/tx_blob.go:62` `Sidecar
  * *BlobTxSidecar \`rlp:"-"\`` — excluded from the consensus RLP by the `rlp:"-"` tag; `:70-76` `BlobTxSidecar{
  * Version, Blobs, Commitments, Proofs }`). besu models the same wrapper payload as `datatypes/BlobsWithCommitments`.
  *
  * `version` distinguishes the v0 wrapper (`[tx, blobs, commitments, proofs]`, one aggregate proof per blob) from the
  * v1 wrapper (`[tx, version, blobs, commitments, proofs]`, cell proofs; Osaka/PeerDAS) — geth `tx_blob.go:38-43`
  * (`BlobSidecarVersion0=0`, `BlobSidecarVersion1=1`). **This is a wholly distinct byte from the versioned-hash leading
  * `0x01`** (a fixed KZG-version byte, [[BlobSidecar.versionedHash]]); the two must never be conflated. The
  * [[Transaction.BlobNetworkWrapper]] codec built here serializes the **v0** wrapper form.
  *
  * Modelled as `List[ByteString]` (a blob is 4096×32 bytes, a commitment/proof 48 bytes) — the L1 value layer carries
  * the bytes; KZG verification is L0 `crypto`, the precompile is L3.
  */
final case class BlobSidecar(
    version: Byte,
    blobs: List[ByteString],
    commitments: List[ByteString],
    proofs: List[ByteString]
)

object BlobSidecar:

  /** v0 wrapper: a single aggregate proof per blob (Cancun). geth `tx_blob.go:38` `BlobSidecarVersion0`. */
  val Version0: Byte = 0

  /** v1 wrapper: cell proofs for data-availability sampling (Osaka/PeerDAS). geth `tx_blob.go:43`
    * `BlobSidecarVersion1`.
    */
  val Version1: Byte = 1

  /** The EIP-4844 **versioned hash** of a KZG commitment: `0x01 ‖ sha256(commitment)[1:]` — byte-exact to go-ethereum
    * `crypto/kzg4844/kzg4844.go:195-203` `CalcBlobHashV1` (`sha256(commit)` with byte 0 overwritten to the fixed KZG
    * version tag `0x01`). This is the consensus link between a blob's commitment (network wrapper) and the
    * `blobVersionedHashes` list carried in the **consensus** tx encoding.
    *
    * ⚠️ The leading `0x01` is the **fixed KZG-version byte**, NOT the sidecar [[BlobSidecar.version]] field (0/1 proof
    * format) — they are unrelated bytes that happen to share the value 1 for the v1 case.
    */
  def versionedHash(commitment: ByteString): Hash =
    val h = sha256(commitment.toArray) // fresh 32-byte array — safe to mutate in place
    h(0) = 0x01
    Hash(ByteString(h))
