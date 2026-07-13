# besu — primitives
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

## Architecture summary

besu factors its primitives into three Gradle modules, each the JVM structural
analog of a fukuii Scala module:

- **`datatypes/`** — the public value types (Address, Hash, Wei, Log, blob/KZG
  holders). These are thin wrappers over **Apache Tuweni** `Bytes`/`Bytes32`/
  `UInt256` (published as `io.consensys.tuweni:tuweni-bytes`/`tuweni-units`).
  Tuweni is besu's foundational immutable byte type — the analog of fukuii's
  own `bytes` module (`ByteString`/`akka.util`-style wrappers).
- **`crypto/algorithms/`** — hashing (`Hash`, `MessageDigestFactory`), the
  `SignatureAlgorithm` pluggable-curve seam and its BouncyCastle-backed
  `AbstractSECP256` base with native-JNI fast paths (`SECP256K1`, `SECP256R1`),
  plus a pure-Java `altbn128/` pairing fallback.
- **`crypto/services/`** — the `NodeKey` / `SecurityModule` indirection that
  separates "sign with the node identity" from "where the key physically lives"
  (in-memory keypair vs. HSM/plugin).
- **`ethereum/rlp/`** — a **streaming** RLP reader/writer (`RLPInput`/
  `RLPOutput`) with an explicit cursor API, deliberately **not** reflection- or
  struct-tag-driven the way go-ethereum's `rlp` package is.

KZG (EIP-4844) and BLS12-381 live one layer up (in `ethereum/core/kzg/` and the
EVM precompiles) but bind to besu-native/consensys JNI artifacts; documented
below under native backends because they define besu's native-crypto strategy.

## Key types / interfaces / files

**datatypes (Tuweni-backed value types)**
- `datatypes/.../Address.java:33` — `class Address extends BytesHolder`, `SIZE = 20`; static `wrap`, `readFrom(RLPInput)`, `extract(SECPPublicKey)` (keccak-of-pubkey → last 20 bytes), `contractAddress(sender, nonce)`, `precompiled(int)`.
- `datatypes/.../BytesHolder.java:38` — `class BytesHolder implements Comparable<BytesHolder>` — the shared fixed-width byte-wrapper base (Address, Hash both extend it) delegating to a Tuweni `Bytes`.
- `datatypes/.../Hash.java:27` — `class Hash extends BytesHolder`; interned constants `ZERO`, `EMPTY_TRIE_HASH = hash(RLP.NULL)`, `EMPTY_LIST_HASH = hash(RLP.EMPTY_LIST)` — note the primitive types depend on the RLP module for these well-known digests.
- `datatypes/.../Wei.java:26` — `final class Wei extends BaseUInt256Value<Wei> implements Quantity` — a Tuweni `UInt256` value type, not a bare BigInteger.
- `datatypes/.../Quantity.java:23` — `interface Quantity` (`getAsBigInteger`, `toHexString`, `toShortHexString`) — the marker for "unsigned integer value" types (Wei, GWei, BlobGas).
- `datatypes/build.gradle:52-57` — depends on `crypto:algorithms`, `ethereum:rlp`, `tuweni-bytes`, `tuweni-units`, caffeine, guava.

**crypto — the pluggable signature seam**
- `crypto/.../SignatureAlgorithm.java:26` — `interface SignatureAlgorithm`: `sign`, `verify` (3 overloads incl. a `verifyMalleable` default that throws unless the curve overrides), `normaliseSignature`, `calculateECDHKeyAgreement`, `recoverPublicKeyFromSignature`, plus the native toggles `disableNative()/maybeEnableNative()/isNative()`. `ALGORITHM = "ECDSA"` is a compile-time constant to dodge the `InsecureCryptoUsage` error-prone check.
- `crypto/.../SignatureAlgorithmFactory.java:22` — a **mutable static singleton**: `getInstance()` returns the current `SignatureAlgorithm`; `switchInstance(curveName)` swaps between `secp256k1` (default) and `secp256r1` via a `switch`; `resetInstance()` for tests. This is the multi-network / alt-curve dispatch point — set once at startup.
- `crypto/.../AbstractSECP256.java:30` — `abstract class AbstractSECP256 implements SignatureAlgorithm`; provider `"BC"` (BouncyCastle), builds `ECDomainParameters` from `SECNamedCurves.getByName(curveName)`, holds `halfCurveOrder` for low-s normalization. Pure-Java signing/verify path.
- `crypto/.../SECP256K1.java:47` — `extends AbstractSECP256`; ctor calls `maybeEnableNative()` which probes `LibSecp256k1.CONTEXT` (besu-native JNI). `sign`/`verify` branch on `useNative` → native path else `super` (BouncyCastle). Uses RFC-6979 deterministic-k (`HMacDSAKCalculator(SHA256Digest)`). Adapted from BitcoinJ/web3j.
- `crypto/.../SECP256R1.java` — the alternate curve (NIST P-256), same base, backed by the `secp256r1` native lib.
- `crypto/.../Hash.java:30` — `abstract class Hash`; keccak256/sha256/ripemd160/blake2bf via `MessageDigestFactory`. keccak digest is `Suppliers.memoize`d + `.clone()`d per call; sha256 is a `ThreadLocal<MessageDigest>` — a JVM-specific optimization to avoid re-instantiating digests on the hot path.
- `crypto/services/.../NodeKey.java:29` — wraps a `SecurityModule`; `sign(dataHash)` delegates the raw r/s to the module then `normaliseSignature`s via `SignatureAlgorithmFactory.getInstance()`. Decouples the signing algorithm from key custody (in-memory vs. HSM plugin).
- `crypto/algorithms/build.gradle:33-41` — `api bcprov-jdk18on`, `implementation` on native artifacts `org.hyperledger.besu:secp256k1`, `:secp256r1`, `:blake2bf`, plus Tuweni.

**RLP — the streaming reader/writer**
- `ethereum/rlp/.../RLPInput.java:62` — cursor interface: `nextIsList()`, `enterList()`/`leaveList()`/`leaveListLenient()`, `readLongScalar`/`readBigIntegerScalar`/`readUInt256Scalar`, `readBytes`/`readBytes32`/`readBytes48`, `readList(valueReader)`, `readAsRlp()`, `raw()`. Decoding is caller-driven: you explicitly enter/leave lists and read typed scalars in order.
- `ethereum/rlp/.../RLPOutput.java:58` — mirror writer: `startList()`/`endList()`, `writeBytes`, `writeLongScalar`/`writeUInt256Scalar`/`writeBigIntegerScalar`, `writeList(iterable, writer)`, `writeRLPBytes` (splice pre-encoded), `writeRaw`.
- `ethereum/rlp/.../BytesValueRLPOutput.java:28` — the concrete accumulate-then-encode output. Its header comment documents a **two-pass single-walk** algorithm: values are buffered in a `List<Bytes>` with `LIST_MARKER` sentinels; nested payload sizes are tracked in a `payloadSizes[]` array with a `parentListStack`; `encoded()` walks once, emitting each list prefix from the precomputed size. Avoids re-measuring nested lists.
- `ethereum/rlp/.../BytesValueRLPInput.java` — the concrete reader over a Tuweni `Bytes`.
- `ethereum/rlp/.../RLP.java:58` — static facade: `input(bytes[, lenient])`, `encode(Consumer<RLPOutput>)`, `encodeOne`/`decodeOne`, `NULL`, `EMPTY_LIST`, `validate`, `calculateSize`.
- `ethereum/rlp/.../RLPDecodingHelpers.java`, `RLPEncodingHelpers.java` — the low-level length-prefix codec; `SimpleNoCopyRlpEncoder.java` — a zero-copy fast path.

## Design decisions & rationale

- **Value types over primitives.** Wei/GWei/BlobGas are `BaseUInt256Value`
  subclasses (Tuweni), Address/Hash are `BytesHolder` subclasses — fixed-width,
  immutable, type-distinct. A `Wei` can never be silently mixed with a raw
  `UInt256`. Tuweni is the single foundational byte/uint library, isolating the
  rest of besu from `byte[]`/`BigInteger` ergonomics.
- **`SignatureAlgorithm` as a runtime-swappable strategy.** One global instance,
  chosen once at node startup by curve name. This is how besu supports alternate
  curves (secp256r1 for permissioned/enterprise nets) without threading a curve
  parameter through every call site — the trade is a mutable static singleton.
- **Native-with-pure-fallback.** Every SECP curve is BouncyCastle-first
  (`AbstractSECP256`) with an opt-in JNI fast path (`useNative`, probed at
  construction, silently falling back on `UnsatisfiedLinkError`). Native serves
  validator/archival verify throughput; pure-Java guarantees correctness and
  portability. The abstraction is at the method level (`sign`/`verify` branch on
  `useNative`), so a missing native lib degrades gracefully rather than crashing.
- **Streaming RLP, not reflection.** besu's `RLPInput`/`RLPOutput` are an
  explicit hand-written cursor. Compared to go-ethereum's struct-tag/reflection
  encoder, this is more verbose per type but has no reflection cost, no hidden
  allocation, and gives precise control (lenient list exit, splicing pre-encoded
  bytes, single-walk sizing). Encoding correctness lives in the codec, not in
  per-type reflection metadata.
- **Key custody split from signing.** `NodeKey`/`SecurityModule` lets the same
  `SignatureAlgorithm` sign whether the key is an in-memory `KeyPair` or an
  external HSM, without the caller knowing.

## Notable patterns (the reusable idea)

**The single most transferable pattern for fukuii: the streaming
`RLPInput`/`RLPOutput` cursor API.** besu proves that an explicit
`enterList()`/`readXxxScalar()`/`leaveList()` reader plus a
`startList()`/`writeXxx()`/`endList()` writer — with a `BytesValueRLPOutput`
that buffers markers and does a single-walk two-pass size computation — is a
clean, allocation-conscious alternative to reflection-based RLP. fukuii's `rlp`
module (Scala) should mirror this shape: a typed streaming decoder/encoder over
its foundational byte type, with an accumulate-then-single-walk encoder for
nested lists.

Secondary transferable patterns:
- **Runtime-swappable `SignatureAlgorithmFactory`** — a startup-time curve
  switch is the minimal seam for multi-network alt-curve support; fukuii can
  adopt the same "one global algorithm, chosen by name" shape rather than a
  compile-time curve.
- **Native-optional crypto via a boolean strategy flag** with graceful
  BouncyCastle fallback — the correct default for a JVM client that wants
  besu-native throughput but must never hard-fail on a platform without the JNI.
- **Fixed-width value types (`BytesHolder`/`BaseUInt256Value`)** over raw bytes
  — the Tuweni model maps directly onto fukuii's `bytes` module design.

## Authority note

besu is the **JVM primitives structural reference** for fukuii — its module
boundaries (`datatypes` / `crypto/algorithms` / `crypto/services` /
`ethereum/rlp`), its API shapes (streaming RLP cursor, `SignatureAlgorithm`
seam, Tuweni value types), and its native-vs-pure crypto strategy are the direct
analogs for fukuii's own `bytes`/`crypto`/`rlp` Scala modules. For **byte-level
behavioral truth** (exact RLP length-prefix edge cases, signature malleability
rules, keccak/hash outputs, EIP-2718 typed-envelope encoding), **go-ethereum is
the authority** and must be cross-referenced — besu tells you *how to shape the
JVM code*, go-ethereum tells you *what bytes must come out*. For ETC/PoW-specific
primitives (ETChash), **core-geth** remains the sole authority.

## Gotchas / anti-patterns / things they later changed

- **`SignatureAlgorithmFactory` is global mutable static state.** `switchInstance`
  mutates a process-wide singleton; tests must call `resetInstance()`
  (`@VisibleForTesting`) to avoid leaking a swapped curve. Any code that caches
  `getInstance()` at class-load time (e.g. `NodeKey`'s field initializer,
  `NodeKey.java:33`) captures whatever curve was active then — order-of-init
  sensitive. A per-network injected instance would be cleaner than a static
  switch, but besu accepts the singleton for simplicity.
- **secp256r1 is flagged experimental** — `SignatureAlgorithmFactory.switchInstance`
  logs "usage of alternative elliptic curves is still experimental" for anything
  but secp256k1. Multi-network alt-curve support exists but is not battle-hardened.
- **Native availability is silent.** `maybeEnableNative()` swallows
  `UnsatisfiedLinkError`/`NoClassDefFoundError` and logs at INFO; a mispackaged
  besu-native jar downgrades to BouncyCastle without failing loudly — throughput
  regresses invisibly. Operators must check `isNative()` to confirm the fast path.
- **`Hash` digest reuse relies on clone/ThreadLocal correctness.** keccak256 is a
  memoized template `.clone()`d per call; sha256 is a `ThreadLocal<MessageDigest>`.
  Both are optimizations to avoid `MessageDigest` re-instantiation on the hot
  path — a naive port that shares one un-cloned digest across threads would
  corrupt. This is a JVM-specific concern fukuii inherits.
- **KZG/BLS are external JNI, versioned separately.** KZG binds
  `io.consensys.protocols:jc-kzg-4844:2.1.6` (a `CKZG4844JNI` wrapper in
  `ethereum/core/kzg/CKZG4844Helper.java`, requiring a `TrustedSetup` load);
  BLS12-381 binds `org.hyperledger.besu:gnark:1.5.0`. These native artifacts
  (`besu-native-common`, `arithmetic`, `blake2bf`, `gnark`, `ipa-multipoint`,
  `secp256k1`, `secp256r1`, `boringssl`, all `1.5.0`, pinned in
  `platform/build.gradle:152-188`) are a separate supply-chain surface from the
  pure-Java crypto — each is a JNI dependency that must be present per-platform
  and kept CVE-current independently of the BouncyCastle path.
- **The primitives modules are not dependency-free.** `datatypes` depends on
  `ethereum:rlp` and `crypto:algorithms` (Address.readFrom needs RLPInput,
  Hash constants need `RLP.NULL`). The layering is datatypes → {rlp, crypto},
  not a flat leaf — worth mirroring deliberately rather than assuming primitives
  sit at the bottom.
