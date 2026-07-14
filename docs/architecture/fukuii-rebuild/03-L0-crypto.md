# L0 — crypto: `crypto`

_Layer L0 (foundation), depends on `bytes`. Measured against
[`observations/primitives.md`](../../research/clients/observations/primitives.md) (the "Crypto
backend strategy" row and the "Dual native+pure crypto backend" OPTIONAL(role) verdict); old-fukuii
AS-IS in [`clients/fukuii/primitives.md`](../../research/clients/fukuii/primitives.md). Byte
behavior is matched against go-ethereum `crypto/keccak.go`, `crypto/crypto.go`,
`crypto/signature_nocgo.go`; the alt-bn128 tower against the EIP-196/197 reference (ethereumj /
libff). core-geth is a pure geth passthrough at the primitive level (no ECIP divergence in
crypto), so go-ethereum is the sole byte-authority here._

## Scope

The cryptographic primitives every state root, transaction hash, and signature check depends on.
`crypto` sits at L0 alongside `bytes`/`rlp`; it depends only on `bytes` (for `Address`, `ByteUtils`
big-endian plumbing).

Five pieces:
- **Keccak** — `kec256` (the single hottest op) + `kec512`, thread-local digest reuse.
- **secp256k1 ECDSA** — `ECDSASignature(r, s, v)` sign (RFC-6979 deterministic-`k`), recover/verify,
  low-S canonicalization (EIP-2), unsigned-byte / EIP-155 `v` handling.
- **alt-bn128 (BN128) pairing** — EIP-196/197 add/mul/pairing over the pure-Scala `Fp`/`Fp2`/`Fp6`/
  `Fp12` tower on a `FiniteField` typeclass.
- **Digests** — `sha256`, `ripemd160` (the `0x02`/`0x03` precompiles).
- **ECIES + key material** — the RLPx-handshake envelope (`ECIESCoder`/`EthereumIESEngine`/
  `ConcatKDFBytesGenerator`), key generation, public-key + address derivation, point validation.

## Design decisions & empirical logic

### 1. Pure BouncyCastle is the default backend; the native fast-path is a deferred OPTIONAL(role)

The whole module is a single **pure-JVM BouncyCastle** implementation. No native JNI dependency is
added at L0.

**Empirical logic:** `observations/primitives.md` records the native+pure dual backend as
**OPTIONAL(role: validator/archival/mining-pool throughput vs enterprise single-binary
portability)** — *not* the DEFAULT. Four of six reference clients ship a dual backend, but the
observation is explicit that the native path is a role-sized optimization whose cost is a
divergent-code correctness surface (the two paths must be output-identical — e.g. the pure path
must re-add the low-S malleability check the native libsecp256k1 does for free). For an L0
foundation whose first job is *correctness* and *"the enterprise single-binary builds and runs
everywhere"*, pure BouncyCastle is the right default. The native seam is left as a documented future
option (see Deferred), sized to fukuii's mining-pool/archival roles when those land — added behind
one API with the pure path as the guaranteed fallback, never as a silent divergence.

This matches old fukuii's actual shape (single pure-JVM hot path; native JNI only for BLS/KZG),
which the observation already classified as consistent with the OPTIONAL(role) reading.

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

### 5. Defense-in-depth CVE guards carried forward explicitly

Three guard classes are retained from old fukuii, each tied to its CVE:
- **Point validation** (`decodeAndValidatePoint`) — `isValid` + reject point-at-infinity on every
  decoded EC point (CVE-2025-24883 / CVE-2026-26314 / CVE-2026-26315: invalid-curve / small-subgroup
  attacks). `decodePoint` alone only checks the curve equation.
- **ECIES truncation reject** (CVE-2026-22862) — a ciphertext shorter than `ephemeralKey(65) +
  IV(16) + MAC(32) + 1` is rejected before parsing; the MAC-length lower bound is checked in
  `decryptBlock`.
- **Constant-time MAC comparison** — `Arrays.constantTimeAreEqual` on the ECIES MAC, no timing
  oracle.

**Empirical logic:** these are not new work — they are the security surface of the RLPx handshake
and the EVM precompiles, and dropping any of them in a "clean rewrite" would be a silent
regression. They are carried with their CVE rationale intact.

## Improvements over old fukuii

| Old fukuii (AS-IS) | Rebuild L0 `crypto` | Why it matters |
|---|---|---|
| `FiniteField.Ops` — `implicit class FiniteFieldOps` (Scala-2 idiom, flagged anti-pattern) | `extension` block in `object FiniteField` + `given` field instances | Modern Scala 3 idiom; no implicit-scope surprises; the operators resolve through the `given`, not a wrapper class |
| `implicit object FpImpl extends FiniteField[Fp]` (×4 fields) | `given fpField: FiniteField[Fp] with …` (×4) | The Scala 3 typeclass-instance form; lazy, unambiguous |
| `package object crypto` holding ~30 defs | Top-level defs across focused files (`Keccak`, `Hashes`, `Secp256k1`, …) | No god-object package; each primitive discoverable in its own file |
| `com.chipprbots.ethereum.utils.ByteUtils` (old grab-bag) | `com.chipprbots.fukuii.bytes.ByteUtils` (L0 leaf) | Clean L0→L0 edge; no dependency on a higher `utils` package |
| Raw `Array[Byte]` address derivation, ad hoc | `pubKeyToAddress → bytes.Address` (typed) | Type-distinct `Address`, byte-exact to `crypto.PubkeyToAddress` |
| Native/pure backend split implicit (BLS/KZG native, rest pure), undocumented as a decision | Pure default **stated** as the OPTIONAL(role) choice; native seam a documented deferral | The backend strategy is now a recorded decision, not an accident |
| CVE guards present but scattered | Same guards, each with its CVE cited at the guard site | Auditable security surface |

What is **byte-exact vs go-ethereum** (with cite): keccak256/512 (`keccak.go:40`), ECDSA
deterministic-`k` sign + low-S (`signature_nocgo.go:121`, `crypto.go:246`), recovery
(`recoverPubBytes` ↔ `sigToPub`), address derivation (`crypto.go:253`). alt-bn128 is byte-exact vs
the EIP-196/197 reference tower (ethereumj/libff), validated by the pairing bilinearity vector.

## Deferred (and to which layer)

- **This crypto commit is the pure core** (keccak / secp256k1 / BN128 / ECIES). **KZG and BLS12-381
  land in the very next crypto pass — `crypto` L0 is NOT "complete" until they do** (they are crypto
  primitives, peers of BN128).
- **KZG (EIP-4844 / EIP-7594) primitive** → **`crypto` (L0), immediate next addition.** The
  `jc-kzg-4844` dep is already pinned in the floor (2.1.6) — sentinel wires it to `crypto`, no new
  approval. Built with the mainnet trusted setup + EIP-4844/7594 KAT vectors. Only the KZG
  *precompile wrapper* (`0x0a` gas/decode/dispatch) goes to `evm` (L3).
- **BLS12-381 (EIP-2537) primitive** → **`crypto` (L0), immediate next addition.** `besu-native`
  `bls12-381` 1.0.0 already pinned — sentinel wires it. Built with EIP-2537 KAT vectors. Only the
  BLS *precompile wrappers* (`0x0b–0x12`) go to `evm` (L3). (Old fukuii inlined BLS at the precompile
  site — the mislayering this fixes.)
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

`sbt "crypto/compile" "crypto/Test/compile" "crypto/testOnly com.chipprbots.fukuii.crypto.*"` —
**43 tests green**, **zero compiler warnings on main sources** under the strict flags
(`-Wunused:all`, `-Wconf:id=E198:error`, `-Wconf:cat=unchecked:error`). Coverage: keccak256/512
byte vectors + per-call-oracle parity + reset-after-abort + concurrency/thread-confinement;
sha256/ripemd160 known-answer vectors; ECDSA sign→verify→recover round-trip, low-S canonical,
RFC-6979 determinism, the go-ethereum recovery vectors, and `r||s||v` encode round-trip; secp256k1
key generation + the go-ethereum key/address KAT + point validation; BN128 `P+P=2P`/`3P` on-curve
arithmetic; the EIP-197 pairing bilinearity identity; and the ethereumj ECIES known-answer decrypt +
encrypt/decrypt round-trip + truncation guard.

_(The test specs carry the same `-Wnonunit-statement` "unused Assertion" warnings the `bytes`/`rlp`
specs do — the documented multiple-`assert`-per-test ScalaTest pattern, warning-level only, not
applied to main sources; see `build.sbt`. Test discovery note: plain `sbt crypto/test` reports 0
tests under sbt 2.0.2, a repo-wide quirk; `testOnly` with a wildcard is the working path.)_
