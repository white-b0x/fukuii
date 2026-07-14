# L0 — crypto: `crypto`

_Layer L0 (foundation), depends on `bytes`. Measured against
[`observations/primitives.md`](../../research/clients/observations/primitives.md) (the "Crypto
backend strategy" row and the "Dual native+pure crypto backend" OPTIONAL(role) verdict); old-fukuii
AS-IS in [`clients/fukuii/primitives.md`](../../research/clients/fukuii/primitives.md). Byte
behavior is matched against go-ethereum `crypto/keccak.go`, `crypto/crypto.go`,
`crypto/signature_nocgo.go`; the alt-bn128 tower against the EIP-196/197 reference (ethereumj /
libff). core-geth is a pure geth passthrough at the primitive level (no ECIP divergence in
crypto), so go-ethereum is the byte-authority for the classic primitives; the KZG and BLS12-381
additions are matched against the consensus-spec-tests `kzg-mainnet` fixtures and the EIP-2537
reference vectors respectively (the same native c-kzg-4844 / besu-native libraries every reference
client links)._

## Scope

The cryptographic primitives every state root, transaction hash, and signature check depends on.
`crypto` sits at L0 alongside `bytes`/`rlp`; it depends only on `bytes` (for `Address`, `ByteUtils`
big-endian plumbing).

Seven pieces:
- **Keccak** — `kec256` (the single hottest op) + `kec512`, thread-local digest reuse.
- **secp256k1 ECDSA** — `ECDSASignature(r, s, v)` sign (RFC-6979 deterministic-`k`), recover/verify,
  low-S canonicalization (EIP-2), unsigned-byte / EIP-155 `v` handling.
- **alt-bn128 (BN128) pairing** — EIP-196/197 add/mul/pairing over the pure-Scala `Fp`/`Fp2`/`Fp6`/
  `Fp12` tower on a `FiniteField` typeclass.
- **Digests** — `sha256`, `ripemd160` (the `0x02`/`0x03` precompiles).
- **ECIES + key material** — the RLPx-handshake envelope (`ECIESCoder`/`EthereumIESEngine`/
  `ConcatKDFBytesGenerator`), key generation, public-key + address derivation, point validation.
- **KZG (EIP-4844 / EIP-7594)** — `kzg.Kzg`, the blob-commitment / opening-proof / PeerDAS cell
  primitive backed by the c-kzg-4844 native library (`jc-kzg-4844` JNI), loaded with the mainnet
  ceremony trusted setup.
- **BLS12-381 (EIP-2537)** — `bls.Bls12381`, the G1/G2 add/mul/MSM, pairing and map-to-curve
  primitive backed by the besu `bls12-381` native library (`LibEthPairings` via JNA).

## Design decisions & empirical logic

### 1. The `CryptoBackend` seam is built, pure BouncyCastle its sole impl; the native fast-path is a deferred OPTIONAL(role) occupancy

The crypto is a **pure-JVM BouncyCastle** implementation reached through a built `CryptoBackend` seam
(`CryptoBackend.scala`) — one API (`keccak256`/`sign`/`recoverPublicKey`/`pairingCheck`) with the pure
path as the sole, immutable-`given` default impl. No native JNI dependency is added at L0 for the
classic primitives.

**Empirical logic:** `observations/primitives.md` records the native+pure dual backend as
**OPTIONAL(role: validator/archival/mining-pool throughput vs enterprise single-binary
portability)** — *not* the DEFAULT. Four of six reference clients ship a dual backend, but the
observation is explicit that the native path is a role-sized optimization whose cost is a
divergent-code correctness surface (the two paths must be output-identical — e.g. the pure path
must re-add the low-S malleability check the native libsecp256k1 does for free). For an L0
foundation whose first job is *correctness* and *"the enterprise single-binary builds and runs
everywhere"*, pure BouncyCastle is the right default. Per the operator decision on RX-L0-19
(2026-07-14), the **seam is built now** (`CryptoBackend` trait, pure-BouncyCastle sole impl) rather
than left as a documented future option — because retrofitting it later would touch every
sign/hash/pair call site (a rewrite), whereas a native fast-path *occupancy* sized to fukuii's
mining-pool/archival roles is cheap to add behind the existing API when those roles land, with the
pure path as the guaranteed byte-identical fallback (a differential native-vs-pure KAT gates it),
never a silent divergence. `CryptoBackendSpec` proves the pure path is a transparent pass-through
today (the same KAT shape that will prove `native == pure` later); forge co-signed the byte-identity.

This matches old fukuii's actual shape (single pure-JVM hot path; native JNI only for BLS/KZG),
which the observation already classified as consistent with the OPTIONAL(role) reading — now with the
selection seam made explicit and R2-safe (immutable `given` default, no global-mutable-static
`switchInstance` anti-pattern).

### 2. Thread-local Keccak digest reuse, documented byte-identical

`kec256` reuses one `KeccakDigest` per thread (`ThreadLocal`) with `reset()` on entry, rather than
allocating a fresh digest per call.

**Empirical logic:** keccak is the hottest primitive — once per trie node across millions of nodes
on every state-root pass, plus every tx/block hash, address, and log-bloom. The observation's
zero-alloc synthesis says to chase *technique* discipline that ports to the JVM (erigon's
pooling/memoization) and skip what doesn't (nethermind's `Span`/`ref struct`). Thread-local digest
reuse is exactly the portable technique: it removes the per-hash digest allocation without any
memory-layout trick the JVM can't express. Two documented invariants keep it byte-exact and safe:
- **Reset-on-entry (INV-1)** — every entry point calls `reset()` first, discarding any state left by
  a hash that aborted between `update` and `doFinal` (the success-only reset inside `doFinal` never
  covers that window). A reset digest is observably identical to `new KeccakDigest(256)`, proven by
  the per-call-oracle parity test.
- **Thread-confinement (INV-2)** — the digest reference never escapes its method body. Hashing runs
  on platform-thread dispatchers; the doc flags that a per-task virtual-thread executor would defeat
  the pool and must revisit the design.

Output is byte-exact to go-ethereum `crypto/keccak.go:40` (`Keccak256` via `NewLegacyKeccak256`) —
the **original** Keccak padding (`0x01`), not FIPS-202 SHA3 (`0x06`). BouncyCastle's `KeccakDigest`
is the original-padding variant, so it matches geth's legacy digest; verified against the canonical
empty-trie root `56e81f…b421` and geth golden vectors.

### 3. Deterministic-`k` sign + low-S canonicalization, byte-exact to geth

`ECDSASignature.sign` uses `HMacDSAKCalculator(SHA256Digest)` (RFC-6979 deterministic `k`), then
canonicalizes `s` to the low half of the curve order, then computes `v` as the point sign (27/28)
that recovers the signer.

**Empirical logic:** deterministic `k` produces the *same* `(r, s)` as go-ethereum's
decred/libsecp256k1 signer for a given key+hash — proven by the round-trip test (sign → recover ==
pubkey) and a determinism test (two signs are identical). Low-S is EIP-2 malleability rejection:
for every valid `(r, s)`, `(r, N − s)` is also valid, and consensus accepts only `s ≤ N/2`. This
mirrors go-ethereum two ways — the `s.IsOverHalfOrder()` reject in `crypto/signature_nocgo.go:121`
and the `ValidateSignatureValues` low-S branch in `crypto/crypto.go:246`. The `v` byte is read
**unsigned** (`v & 0xff`) so EIP-155 chain-encoded values ≥ 128 (e.g. `v=157` on ETC mainnet,
chainId 61) don't wrap to a negative `BigInt`; the EIP-155 → 27/28 unwinding itself lives one layer
up in `domain` (the transaction layer), and `recoverPubBytes` takes a bare point sign.

Address derivation is byte-exact to `crypto.PubkeyToAddress` (`crypto/crypto.go:253`):
`Keccak256(pubKey)[12:]` over the prefix-dropped 64-byte key — verified against go-ethereum's
`crypto_test.go` known key/address vector (`289c2857… → 970e8128…`).

### 4. alt-bn128 stays pure-Scala over a `FiniteField` typeclass — the standing perf outlier

The BN128 pairing tower (`Fp ⊂ Fp2 ⊂ Fp6 ⊂ Fp12`) is a pure-Scala port of the libff/ethereumj
arithmetic, over a `FiniteField[A]` typeclass.

**Empirical logic:** the observation notes fukuii's alt-bn128 is pure-Scala where geth/besu use
native (gnark) — the standing performance outlier at this slot. It is kept pure-Scala for now (a)
because it matches old fukuii's byte behavior exactly (the EIP-196/197 precompiles are
consensus-critical and this code is the tested reference), and (b) because a native alt-bn128
backend is the same OPTIONAL(role) native-seam decision as §1, deferred to the precompile consumer
with a sentinel-gated dep-add. The pairing is exercised end-to-end by the EIP-197 bilinearity
identity `e(P, Q)·e(−P, Q) = 1` over the standard generators, which validates the full Miller-loop +
final-exponentiation path. Noted as the perf item to revisit when precompile throughput matters.

**G2 prime-order subgroup check (consensus-correctness).** A `BN128G2` input point is validated for
**order-`r` subgroup membership**, not merely for lying on the twist curve. The twist `E'(Fp2)` has
cofactor `> 1`, so on-curve does not imply in-subgroup; an on-curve-but-off-subgroup G2 point fed to
`ECPAIRING` (`0x08`) would make the pairing return a result differing from the reference client — a
**state-root split**. The check is `[r]·P = ∞` (`BN128Fp2.mul(p, R).isZero`), applied to the **G2
path only** — G1 over `Fp` has cofactor 1 and needs none. This is byte-for-byte the check core-geth
performs in `bn256/cloudflare/twist.go:60-62` (`cneg.Mul(c, Order); return cneg.z.IsZero()`) and
go-ethereum's gnark `IsInSubGroup` (`bn256/gnark/g2.go:59`). It is validated by an on-curve
off-subgroup G2 vector asserted rejected (the off-subgroup point independently confirmed on-curve and
`[r]P ≠ ∞`), alongside the retained bilinearity identity.

### 5. Defense-in-depth CVE guards carried forward explicitly

Three guard classes are retained from old fukuii, each tied to its CVE:
- **Point validation** (`decodeAndValidatePoint`) — `isValid` + reject point-at-infinity on every
  decoded EC point (CVE-2025-24883 / CVE-2026-26314 / CVE-2026-26315: invalid-curve / small-subgroup
  attacks). `decodePoint` alone only checks the curve equation.
- **ECIES truncation reject** (CVE-2026-22862) — a ciphertext shorter than `ephemeralKey(65) +
  IV(16) + MAC(32) + 1` is rejected before parsing; the MAC-length lower bound is checked in
  `decryptBlock`.
- **Constant-time MAC comparison** — the ECIES MAC compare routes through the exposed L0
  `constantTimeEquals` primitive (`ConstantTime.scala`, wrapping BouncyCastle
  `Arrays.constantTimeAreEqual`), no timing oracle. Exposing it as a callable L0 symbol (RX-L0-16) —
  rather than an inline call — gives the future L8 keystore-MAC and L9 JWT/auth consumers **one
  audited constant-time surface** to import and a single lint target (no `==`/`sameElements` at
  secret sites); the L6 per-frame integrity MAC stays a plain compare by design (non-secret).

**Empirical logic:** these are not new work — they are the security surface of the RLPx handshake
and the EVM precompiles, and dropping any of them in a "clean rewrite" would be a silent
regression. They are carried with their CVE rationale intact.

### 6. KZG (EIP-4844 / EIP-7594) is native-JNI by default, not an OPTIONAL(role) fast-path

`kzg.Kzg` wraps the c-kzg-4844 native library (via the `jc-kzg-4844` 2.1.6 JNI bindings) for the
full EIP-4844 blob-commitment surface (`blobToKzgCommitment`, `computeKzgProof`/`verifyKzgProof`,
`computeBlobKzgProof`/`verifyBlobKzgProof` + batch) and the EIP-7594 PeerDAS cell surface
(`computeCells`, `computeCellsAndKzgProofs`, `recoverCellsAndKzgProofs`, `verifyCellKzgProofBatch`).

**Empirical logic:** unlike keccak/secp256k1/alt-bn128 — where a pure-BouncyCastle path is the
correctness floor and the native seam is the OPTIONAL(role) optimization (§1) — KZG has *no*
practical pure-JVM implementation at consensus throughput, and every reference client
(geth/besu/nethermind/reth) links the *same* c-kzg-4844 (or crate-crypto rust) native library. The
native library, together with the trusted setup, *is* the shared byte-authority; there is no
independent pure reference to be the fallback. Native JNI is therefore the **default** at this slot,
not a role-sized fast-path. The `jc-kzg-4844` jar bundles the platform natives (linux/darwin ×
x86-64/aarch64), so the enterprise single-binary still "builds and runs everywhere" with no separate
native install. This is a peer-of-BN128 placement: both are consensus pairing-based primitives that
belong in `crypto` L0; only the `0x0a` point-evaluation *precompile wrapper* (gas, 192-byte decode,
versioned-hash check, dispatch) is deferred up to `evm` (L3).

**Trusted-setup lifecycle (documented invariant).** The c-kzg native library holds the trusted
setup in **process-global** state — exactly one setup per JVM, guarded inside the `.so`.
`Kzg.loadTrustedSetup()` is idempotent and thread-safe at the Scala layer (double-checked
`AtomicReference` gate, `CKZG4844JNI.loadNativeLibrary()` then
`loadTrustedSetupFromResource("/trusted_setup.txt", …)` on first call); every operation calls it
first, so callers never sequence the load by hand. `freeTrustedSetup()` is deliberately *not*
exposed to application code — it is a one-way global teardown that would break every other consumer
in the process. The bundled `trusted_setup.txt` is byte-identical to the c-kzg-4844 / besu mainnet
ceremony setup (PeerDAS format: 4096 g1-lagrange + 65 g2-monomial + 4096 g1-monomial points), the
same file old fukuii shipped.

Byte-exactness is validated against the consensus-spec-tests `kzg-mainnet` KAT fixtures (the
zero-blob commitment and `verify_kzg_proof` correct/incorrect cases), plus full compute→verify
round-trips (with tamper-rejection) over the 4844 blob-proof, batch, and 7594 cell/recovery paths.

### 7. BLS12-381 (EIP-2537) is a first-class L0 primitive, native-JNI, correcting old fukuii's mislayering

`bls.Bls12381` wraps the besu `bls12-381` 1.0.0 native library (`LibEthPairings`, the gnark/EIP-1962
`eth_pairings` backend, dispatched over JNA) for all nine EIP-2537 operations: G1 add/mul/MSM, G2
add/mul/MSM, pairing check, map-fp-to-G1, map-fp2-to-G2. A single `perform(op, input)` dispatches to
the native `eip2537_perform_operation` and returns `Either[String, Array[Byte]]` — `Right(output)`
on success, `Left(nativeError)` when the backend rejects a malformed point / non-canonical field
element / wrong-length input (the native library performs the mandatory subgroup checks). Inputs and
outputs are the canonical EIP-2537 byte encodings, passed through unmodified, so the wrapper is a
pure byte conduit with no re-encoding.

**Empirical logic:** same native-by-default reasoning as §6 — there is no pure-JVM BLS12-381 at
consensus throughput, and the besu native lib bundles the platform `.so`/`.dylib`. Placing it in
`crypto` L0 as a peer of the alt-bn128 tower and `kzg.Kzg` fixes a concrete mislayering: **old
fukuii inlined the besu-native BLS calls directly at the precompile site
(`vm/PrecompiledContracts.scala`)**, entangling a pure cryptographic primitive with EVM
gas/dispatch. The primitive now lives at L0; only the BLS *precompile wrappers* (gas schedule, MSM
discount table, input-length dispatch, and the precompile-address mapping — which itself differs
across EIP-2537 revisions) are deferred to `evm` (L3).

Byte-exactness is validated against the EIP-2537 reference vectors (`EIPs/assets/eip-2537`): each
success vector's `Input`/`Expected` is the raw precompile encoding, so the KAT is a direct
byte-for-byte check per operation, including the pairing-identity `e(0,0) → 0…01` word; the
`fail-*` vectors assert malformed input is rejected (`Left`) rather than silently mis-answered.

## Improvements over old fukuii

| Old fukuii (AS-IS) | Rebuild L0 `crypto` | Why it matters |
|---|---|---|
| `FiniteField.Ops` — `implicit class FiniteFieldOps` (Scala-2 idiom, flagged anti-pattern) | `extension` block in `object FiniteField` + `given` field instances | Modern Scala 3 idiom; no implicit-scope surprises; the operators resolve through the `given`, not a wrapper class |
| `implicit object FpImpl extends FiniteField[Fp]` (×4 fields) | `given fpField: FiniteField[Fp] with …` (×4) | The Scala 3 typeclass-instance form; lazy, unambiguous |
| `package object crypto` holding ~30 defs | Top-level defs across focused files (`Keccak`, `Hashes`, `Secp256k1`, …) | No god-object package; each primitive discoverable in its own file |
| `com.chipprbots.ethereum.utils.ByteUtils` (old grab-bag) | `com.chipprbots.fukuii.bytes.ByteUtils` (L0 leaf) | Clean L0→L0 edge; no dependency on a higher `utils` package |
| Raw `Array[Byte]` address derivation, ad hoc | `pubKeyToAddress → bytes.Address` (typed) | Type-distinct `Address`, byte-exact to `crypto.PubkeyToAddress` |
| Native/pure backend split implicit (BLS/KZG native, rest pure), no seam, undocumented as a decision | `CryptoBackend` seam **built** (one API, pure-BC sole impl, immutable `given`/R2-safe); native fast-path occupancy OPTIONAL(role) behind it | Adding a native backend later is a config swap, not a call-site rewrite; the strategy is a recorded, byte-identity-gated decision |
| Constant-time MAC compare inline (`Arrays.constantTimeAreEqual` at the call site only) | Exposed L0 `constantTimeEquals` primitive; ECIES MAC re-pointed to it | One audited constant-time surface + a single lint target for the L8/L9 secret-compare consumers (RX-L0-16) |
| CVE guards present but scattered | Same guards, each with its CVE cited at the guard site | Auditable security surface |
| alt-bn128 G2 input validated **on-curve only** — no order-`r` subgroup check | `BN128G2` enforces `[r]·P = ∞` (G2 path only), byte-exact to core-geth `twist.go` / geth gnark `IsInSubGroup` | Closes an `ECPAIRING` state-root split on an on-curve-off-subgroup G2 point |
| **BLS12-381 native calls inlined at the precompile site** (`vm/PrecompiledContracts.scala`) | `bls.Bls12381` — a first-class L0 primitive, peer of BN128/KZG; only the precompile wrapper is `evm` L3 | Cryptographic primitive no longer entangled with EVM gas/dispatch — correct layering |
| KZG lived in root-module glue (`KzgCellProofs` etc.), trusted-setup lifecycle ad hoc | `kzg.Kzg` at L0 with an idempotent, thread-safe, documented process-global trusted-setup gate | KZG is a proper L0 primitive with a stated native-by-default rationale and setup invariant |

What is **byte-exact vs go-ethereum** (with cite): keccak256/512 (`keccak.go:40`), ECDSA
deterministic-`k` sign + low-S (`signature_nocgo.go:121`, `crypto.go:246`), recovery
(`recoverPubBytes` ↔ `sigToPub`), address derivation (`crypto.go:253`). alt-bn128 is byte-exact vs
the EIP-196/197 reference tower (ethereumj/libff), validated by the pairing bilinearity vector, with
the G2 order-`r` subgroup check byte-exact to core-geth `bn256/cloudflare/twist.go:60-62` and
go-ethereum gnark `bn256/gnark/g2.go:59`.

## Layer boundaries (what lives elsewhere, and why)

_Durable placement decisions — not build-status (see the README index for what's committed)._

- **KZG / BLS12-381 EVM *precompile wrappers*** → **`evm` (L3)**: the `0x0a` point-evaluation wrapper
  (gas / 192-byte decode / versioned-hash check / dispatch) for KZG, and the BLS12-381 precompile
  wrappers (gas schedule, MSM discount table, input-length dispatch, precompile-address mapping). The
  cryptographic *primitives* they call live here at L0 crypto.
- **Native secp256k1 / keccak fast-path** → future OPTIONAL(role) seam. When a mining-pool/archival
  throughput role justifies it, add one API with a native impl selected by config and the pure
  BouncyCastle path as the guaranteed fallback (the two must stay output-identical against
  go-ethereum byte behavior). No native dep now.
- **Native alt-bn128 backend** → same OPTIONAL(role) seam, sized to precompile throughput. The
  pure-Scala tower is the correctness reference it would be validated against.
- **Keystore KDFs** (`pbkdf2HMacSha256`, `scrypt`) → the keystore/wallet layer. They are key-file
  derivation, not consensus or handshake primitives, and belong with the keystore consumer, not the
  L0 crypto leaf.
- **RLPx handshake framing** (Auth/Ack message assembly on top of `ECIESCoder`) → `network` (L6).
  L0 provides the ECIES envelope; the handshake protocol that uses it is a network concern.

## Verification

`sbt "crypto/Test/testOnly *"` — the full `crypto` suite green (core + KZG/BLS + the
`ConstantTimeSpec` and `CryptoBackendSpec` added for RX-L0-16/19: `constantTimeEquals`
equal/unequal/length-mismatch, and the property-based `CryptoBackend` pure-pass-through byte-identity
across keccak/sign/recover/pairing), **zero compiler warnings on main sources** under the strict
flags (`-Wunused:all`, `-Wconf:id=E198:error`, `-Wconf:cat=unchecked:error`). Core coverage:
keccak256/512 byte vectors + per-call-oracle parity + reset-after-abort + concurrency/
thread-confinement; sha256/ripemd160 known-answer vectors; ECDSA sign→verify→recover round-trip,
low-S canonical, RFC-6979 determinism, the go-ethereum recovery vectors, and `r||s||v` encode
round-trip; secp256k1 key generation + the go-ethereum key/address KAT + point validation; BN128
`P+P=2P`/`3P` on-curve arithmetic; the EIP-197 pairing bilinearity identity; and the ethereumj ECIES
known-answer decrypt + encrypt/decrypt round-trip + truncation guard. KZG coverage (8 tests): the
consensus-spec-tests `kzg-mainnet` zero-blob commitment KAT and `verify_kzg_proof` correct/incorrect
KATs, plus compute→verify round-trips over the 4844 blob-proof/batch and 7594 cell/recovery paths.
BLS12-381 coverage (12 tests): the EIP-2537 reference KAT per operation (G1/G2 add/mul/MSM, pairing,
map-to-curve), the pairing-identity word, and `fail-*` malformed-input rejection.

_(The test specs carry the same `-Wnonunit-statement` "unused Assertion" warnings the `bytes`/`rlp`
specs do — the documented multiple-`assert`-per-test ScalaTest pattern, warning-level only, not
applied to main sources; see `build.sbt`. Test discovery note: under sbt 2.0.2 `<module>/Test/test`
delegates to `testQuick` and can report a false 0-test pass on a warm `target/`; the push-gate
aliases were rewritten to `testOnly *` to force real execution (commit `735b0607a`), so
`sbt "crypto/Test/testOnly *"` is the reliable path.)_
