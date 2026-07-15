# L0 — foundation: `bytes`, `common`, `crypto`, `rlp`

_Layer L0 (the dependency-light foundation everything else is built on). Four leaves: `bytes` and
`common` depend on nothing; `rlp` and `crypto` each depend down-only on `bytes` (value types +
big-endian byte plumbing) and on nothing above. Forward-looking plan:
[`plan/L0.md`](../plan/L0.md); per-item byte-cited RX evidence: [`plan/rx/L0.md`](../plan/rx/L0.md).
Every slot is measured against
[`observations/primitives.md`](../../../research/clients/observations/primitives.md) (the reference-field
DEFAULT/OPTIONAL verdicts) with old-fukuii AS-IS in
[`clients/fukuii/primitives.md`](../../../../.local/docs/research/clients/fukuii/primitives.md). Byte layout is
matched against go-ethereum (`rlp/`, `crypto/`) as the shared byte-authority, core-geth for the
ETC-frozen values (a pure geth passthrough at the primitive level — no ECIP divergence in crypto), with
besu / besu-etc as the JVM-implementation lens. Built as the initial rebuild leaves (`bytes`/`common`
commit `b8c064ef6`) and then hardened in a follow-up L0 pass (the zero-cast RLP derivation and the
BouncyCastle checked-narrowing, commit `16502d72d`; the push-gate `testOnly *` rewrite, commit
`735b0607a`; the `CryptoBackend`/`constantTimeEquals` seams for RX-L0-19/16, operator decision
2026-07-14). forge co-signed the crypto/RLP byte-identity surfaces vs core-geth + besu-etc._

## Scope

The four modules under L0 hold the serialization, arithmetic, and cryptographic primitives on which
every state root, transaction hash, and block hash is built.

- **`bytes`** — byte utilities **and** the fixed-width value types (`Address`, `Hash`/`Bytes32`,
  `UInt256`), including `UInt256`'s own 256-bit modular arithmetic.
- **`common`** — genuinely generic, dependency-light utilities (currently just the SLF4J logging
  facade). Deliberately *not* the old grab-bag `utils` package.
- **`rlp`** — the Recursive Length Prefix codec: the `RLPEncoder`/`RLPDecoder`/`RLPCodec` typeclass with
  a working, zero-cast `derives RLPCodec`; the `RLPEncodeable` AST ⇄ `Array[Byte]` byte engine;
  base-type + value-type `given` instances; and the top-level `encode`/`decode`/`decodeStrict`/
  `rawDecode` API.
- **`crypto`** — the cryptographic primitives every hash and signature check depends on: Keccak
  (`kec256`/`kec512`), secp256k1 ECDSA, the alt-bn128 (BN128) pairing tower, `sha256`/`ripemd160`, the
  ECIES RLPx-handshake envelope + key material, KZG (EIP-4844/7594), BLS12-381 (EIP-2537), the
  `CryptoBackend` selection seam, and the `constantTimeEquals` secret-compare primitive.

**Not defined here:** semantic wrappers (`Wei`, `ChainId`) and the consensus value objects
(`Account`, `Transaction`, `BlockHeader`, `Receipt`) compose these leaves one layer up in L1 `domain`;
gas-metered / opcode `UInt256` semantics and the EVM precompile wrappers (KZG `0x0a`, BLS, alt-bn128)
live in L3 `evm`; keystore KDFs and RLPx handshake framing live at L8/L6.

## Design decisions & empirical logic

### Primitives — `bytes` / `common`

#### 1. Fixed-width value types live at the primitives layer

`Address`, `Hash`, and `UInt256` are defined in `bytes`, not one layer up in `domain`.

**Empirical logic:** `observations/primitives.md`'s DEFAULT for "value-type representation" is
*fixed-width value types at the primitives level* — go-ethereum's `Hash [32]byte` / `Address [20]byte`,
besu's Tuweni `BytesHolder`. Old fukuii **diverged from this DEFAULT**: its `bytes` module exposed only
raw `ByteString`/`Array[Byte]` helpers and pushed `UInt256`/`Address` up into `domain` — recorded as a
gap in the AS-IS snapshot. The rebuild puts them where the reference field puts them, so the primitives
layer is self-contained.

#### 2. Opaque types, not wrapper classes

Each value type is a Scala 3 `opaque type` (`Address = ByteString`, `Hash = ByteString`, `UInt256 =
BigInt`).

**Empirical logic:** opaque types are **type-distinct at compile time but zero-cost at runtime** — an
`Address` cannot be silently mixed with a `Hash` even though both wrap `ByteString` (proven by an
`assertDoesNotCompile` test), yet there is no wrapper object allocated. This is the Scala-3 idiom for the
"type-distinct fixed-width" DEFAULT; it is *better* than the JVM wrapper-class approach (besu/geth) on
the allocation axis and equal on the type-safety axis. (The one thing it cannot do — inline the 32 bytes
into a containing object, as nethermind's C# `struct ValueHash256` does — is a JVM limitation that does
not port; the observations doc flags this explicitly, so we do not chase it.)

#### 3. Strict `apply` + explicit `fromBytesTruncating`

`Address.apply(ByteString)` / `Hash.apply(ByteString)` are **strict** — `require` exactly N bytes, fail
loud otherwise. A separate `fromBytesTruncating` carries go-ethereum's lenient `SetBytes` (right-align /
left-pad / truncate) semantics, byte-for-byte, for the boundary sites that genuinely need it (RLP-decoded
address fields, the low-20-of-32 derivation in CREATE/CREATE2/`ecrecover`, `BytesToAddress`).

**Empirical logic:** this is the project's *fail-loudly* principle applied to a consensus-adjacent
convention. geth's pervasive `SetBytes` leniency silently reshapes wrong-length input — fine at a parse
boundary (where it *is* the spec), a latent bug-swallower everywhere else. Splitting the two gives
fail-loud on programmer error **and** byte-exact geth behavior where the spec requires it.
`UInt256.fromBytes` stays lenient (`≤ 32` bytes) on purpose — it is a *numeric* big-endian scalar decode,
and RLP-encoded scalars / minimal-length storage values legitimately arrive shorter than 32 bytes; a
strict "exactly 32" would wrongly reject valid input.

#### 4. `UInt256` carries its own 256-bit modular arithmetic

`UInt256` is not just a byte/ordering wrapper — it carries the **core 256-bit modular arithmetic** as
`extension` operators: add/sub/mul (mod `2^256` wrapping), div/mod (unsigned, `y == 0 ⇒ 0`), pow
(`x^y mod 2^256`, `x^0 = 1`), bitwise and/or/xor/not, logical shl/shr, and unsigned comparison.

**Empirical logic:** every reference client carries the arithmetic **on the number type itself** —
go-ethereum's `holiman/uint256.Int` (`Add`/`Mul`/`Div`/`Exp`/`Lsh`…), besu's Tuweni `UInt256`. The
wrapping and zero-divisor semantics are matched byte-for-byte to `holiman/uint256`: subtraction wraps
into `[0, 2^256)`, `Div`/`Mod` by zero return `0` (not a trap), `Exp(x, 0) = 1`. Implemented over
`BigInt` with a single `mod 2^256` reduction (`BigInt.mod` is always non-negative, giving the correct
two's-complement wrap for an underflowing subtraction) — the JVM has no native `uint256`, so this is the
besu-style big-int-backed approach, not the geth 4×`uint64` limb representation. What is deliberately
**excluded** is the gas-metered EVM-opcode layer (EXP gas, SIGNEXTEND, signed SDIV/SMOD/SAR) — those
encode opcode/gas rules and live in `evm` (L3), not on the number type.

#### 5. Scala 3 idiom throughout (`bytes`/`common`)

`extension` methods and `given` instances replace the old `implicit class` / `implicit def` forms;
top-level definitions and braceless syntax throughout.

**Empirical logic:** old fukuii's `ByteStringUtils`/`Padding` `implicit class`es and `implicit def`
conversions were called out as the Scala-2-era anti-pattern in the AS-IS snapshot. Litmus for the
rebuild: *if it isn't Scala 3 idiom, don't write it that way.* Two consensus-relevant corrections landed
in the same pass: the byte ordering became **unsigned** lexicographic (old fukuii's
`Ordering.by(_.toSeq)` was a signed compare that sorts `0xff` before `0x01` — a latent trie/access-list
footgun), and `Hex.decode` gained an odd-length guard (`0x`/`0X` tolerated, non-hex rejected — matching
go-ethereum `hexutil`).

### RLP — `rlp`

#### 6. Typeclass/`derives`-derived codecs are the default — and `derives` actually works

`case class X(...) derives RLPCodec` compiles and produces a working codec, wired from line one.

**Empirical logic:** `observations/primitives.md` names *"Derive-macro / typeclass-derived RLP codecs"*
the **DEFAULT** — reth's alloy `#[derive(RlpEncodable)]` and nethermind's per-type decoder registry both
map onto a Scala 3 `given RLPCodec[T]` / `derives RLPCodec`: compile-time-resolved, per-type, no runtime
reflection walk, and fork-conditional / storage-vs-wire variants stay visible per type. Old fukuii **had
the Mirror machinery** (`RLPDerivation.scala`, `Mirror.ProductOf`-based) **but never finished the
cutover**: it was unwired (0 production call sites) and the advertised `derives RLPCodec` **did not
compile**. The rebuild builds the derivation *into* the codec companion and proves it with a real
`derives` round-trip test (including nested and value-type fields).

**One implementation correction over the old design.** Old fukuii declared `type RLPCodec[T] =
RLPEncoder[T] & RLPDecoder[T]` (a type alias to an intersection). Scala 3's `derives` clause requires a
**class type**; a type alias to an intersection is rejected outright as *"not a class type"*, so that
form could **never** have been a `derives` target no matter how its companion object was written — adding
a `derived` method to it is necessary but not sufficient. The rebuild uses `trait RLPCodec[T] extends
RLPEncoder[T], RLPDecoder[T]`, semantically identical (an `RLPCodec[T]` still *is* both an encoder and a
decoder for `T`, and satisfies any `using RLPEncoder[T]` / `using RLPDecoder[T]`) but a real class type
the compiler will derive.

#### 7. Zero-cast, type-safe product derivation

The `derives RLPCodec` product path is **type-safe end-to-end with zero executable `asInstanceOf`**.
`RLPCodec.derived` resolves a `Mirror.ProductOf[T]` and a recursive `TupleRLPCodec[m.MirroredElemTypes]`
typeclass that recurses on the tuple's `H *: T` cons structure — `head: RLPCodec[H]` is summoned per
field, `tail` handles the rest — so **every field keeps its precise static type through the fold**, never
erased to an existential `RLPCodec[?]`/`Any`. `productCodec` then encodes as an `RLPList` and arity-checks
before decoding via `m.fromProduct`. The only erasure cast remaining anywhere in the path lives inside the
standard library's `Tuple.fromProductTyped`, not in fukuii code.

**Empirical logic:** the earlier build assembled the product codec from an existential `List[RLPCodec[?]]`
and cast each element with `asInstanceOf[RLPEncoder/Decoder[Any]]` inside the encode/decode loop — a
runtime cast per field, a `ClassCastException` surface if the field-list and tuple ever drifted. The
recursive-typeclass form (removed the loop in commit `16502d72d`) makes that drift *unrepresentable*: the
compiler proves each field's codec matches its static type, so the fold cannot be mis-summoned. This is
consensus-load-bearing — the derived codec feeds transaction/receipt/state hashing — so eliminating the
executable cast removes a class of latent hashing bug, not just a lint. It is field-resolution-identical
to the old per-element `summonInline[RLPCodec[head]]` (same `given RLPCodec[H]` per field type, same
declaration order), verified byte-exact against the `ethereum/tests` vectors.

Note the contrast with pre-rebuild fukuii, whose `derives`-based RLP codec **existed but was unwired and
did not compile** (§6); this one is wired, compiles, is zero-cast, and is byte-exact. **Trailing-optional
omission** (geth's `rlp:"optional"` / `rlp:"tail"`, e.g. the fork-variant block header) is expressed as a
**hand-written `given` per consuming type**, not a `DerivationPolicy` object — no such object exists in
the built `rlp` module. The base `derived` is the straight-field-list default (reth's `#[derive]`); a
special fork/wire variant stays a hand-written `given`, exactly as reth hand-writes those cases, keeping
the special behavior visible per type.

#### 8. Intermediate AST, not a streaming cursor

The engine keeps an explicit `RLPEncodeable` tree (`RLPList` / `RLPValue` / `PrefixedRLPEncodable`).

**Empirical logic:** the observations doc records **besu's streaming-cursor `RLPInput`/`RLPOutput`** as
the JVM *structural* analog — *"the shape to mirror for the reader/writer plumbing under the typeclass"* —
explicitly **not a competing codec-authoring model**. The AST is retained over the cursor because it
composes directly with `Mirror` derivation: each product field is an `RLPEncodeable`, assembled into an
`RLPList`, with no mutable-writer threading. The trade-off, stated plainly: the AST **eagerly allocates**
a tree per encode — the residual allocation cost the observations doc flags as fukuii's main one at this
slot. It is offset (not eliminated) by the allocation-conscious engine below; the deeper pooling/benchmark
discipline (erigon's) is deferred.

#### 9. Allocation-conscious engine, byte-exact to go-ethereum

Three specific choices in `RLP.scala`, all byte-identical to go-ethereum:
- **Single-pass sized-buffer list encode** — the concatenated payload is built in one pre-sized `Array`
  (encode each item once, sum lengths, `arraycopy`) instead of `foldLeft(Array())(_ ++ _)`, which
  reallocated and recopied the whole accumulator per item (O(n²) in list size).
- **Slice-free big-endian int read** — `bigEndianMinLengthToInt(data, offset, len)` reads a length-prefix
  in place, no intermediate array.
- **Minimal-length scalar encoding** — integers encode with no leading zeros, `0` ⇒ empty string
  (`0x80`), matching go-ethereum's `rlp/encbuffer.go`. The 55/56-byte short↔long header boundary and the
  single-byte `< 0x80` self-encoding are verified against the canonical
  `ethereum/tests/RLPTests/rlptest.json` vectors.

#### 10. Value types encode by their spec role — scalar vs. byte string

`UInt256` encodes as a **minimal-length big-endian scalar** (no leading zeros, `0` ⇒ empty string);
`Address` / `Hash` encode as their **full fixed-width byte string** (leading zeros preserved).

**Empirical logic:** this is the RLP spec's string-vs-scalar distinction, and it is consensus-load-bearing
— a storage value (`UInt256`) is a quantity and strips leading zeros, while an address is a 20-byte string
and must not. Decode of `Address`/`Hash` is **strict on length**, matching go-ethereum decoding into its
fixed `[20]byte` / `[32]byte` array types; `UInt256.fromBytes` stays lenient (`≤ 32` bytes) because
RLP-encoded scalars legitimately arrive shorter than 32 bytes.

#### 11. Strict decode built in from the start

`decodeStrict[T]` / `rawDecodeStrict` reject trailing bytes after the first complete item; the lenient
`decode` / `rawDecode` ignore them.

**Empirical logic:** this is old fukuii's RLP-DECODE-01/02 resolution, present from line one rather than
retrofitted. A buffer that by design holds exactly one self-contained item (a stored state value, a
persisted record) must fail loud on trailing garbage — the project's *fail-loudly* principle. The lenient
path is kept for prefix-plus-payload frames (an RLPx message whose type byte precedes the payload), where
trailing bytes are expected.

#### 12. Canonical-form decode enforcement (not just structural decode)

The decoder rejects **non-canonical encodings** the way go-ethereum does, rather than accepting any
structurally-parseable input:
- **Non-canonical size headers** (`ErrCanonSize`, `rlp/raw.go`) — a single byte `< 0x80` wrapped as a
  `0x81 xx` short string; a long-form header (`0xb8…`/`0xf8…`) used for a `< 56`-byte payload; a
  length-of-length field with a leading zero byte.
- **Non-canonical integers** (`ErrCanonInt`, `rlp/decode.go`) — a scalar decode (`Int`/`Long`/`BigInt`/
  `UInt256`) rejects a leading zero byte, including a lone `0x00` (zero is the empty string, never
  `0x00`). Encode was already minimal-length; this closes the matching decode path.
- **Payload bounds** (`ErrValueTooLarge`, `rlp/raw.go`) — a header that claims more payload than the
  buffer holds is rejected instead of silently truncating via `slice`.

**Empirical logic:** a lenient decoder that accepts multiple byte-encodings of the same value is a
**consensus-divergence / network-partition vector** — two nodes can disagree on whether a block or
transaction is well-formed. go-ethereum and besu both enforce these canonical rules; matching them
byte-for-byte is a hard requirement, not a nicety. Old fukuii inherited Mantis's *structural-only*
decoder, which accepted all three non-canonical classes above; the rebuild is strict from line one,
covered by the `ethereum/tests/RLPTests/invalidRLPTest.json` vectors.

### Crypto — `crypto`

#### 13. The `CryptoBackend` seam is built; pure BouncyCastle its sole impl; the native fast-path a deferred OPTIONAL(role)

The classic crypto is a **pure-JVM BouncyCastle** implementation reached through a built `CryptoBackend`
seam (`CryptoBackend.scala`) — one API (`keccak256`/`sign`/`recoverPublicKey`/`pairingCheck`) with the
pure path as the sole, immutable-`given` default impl. No native JNI dependency is added at L0 for the
classic primitives.

**Empirical logic:** `observations/primitives.md` records the native+pure dual backend as
**OPTIONAL(role: validator/archival/mining-pool throughput vs enterprise single-binary portability)** —
*not* the DEFAULT. Four of six reference clients ship a dual backend, but the observation is explicit that
the native path is a role-sized optimization whose cost is a divergent-code correctness surface (the two
paths must be output-identical — e.g. the pure path must re-add the low-S malleability check the native
libsecp256k1 does for free). For an L0 foundation whose first job is *correctness* and *"the enterprise
single-binary builds and runs everywhere"*, pure BouncyCastle is the right default. Per the operator
decision on **RX-L0-19** (2026-07-14) the **seam is built now** (`CryptoBackend` trait, pure-BouncyCastle
sole impl) rather than left as a documented future option — retrofitting it later would touch every
sign/hash/pair call site (a rewrite), whereas a native fast-path *occupancy* sized to fukuii's
mining-pool/archival roles is cheap to add behind the existing API when those roles land, with the pure
path as the guaranteed byte-identical fallback (a differential native-vs-pure KAT gates it), never a
silent divergence. Selection is an immutable `given default` (R2-safe: per-instance, never the besu
`SignatureAlgorithmFactory.switchInstance` JVM-global-mutable-static anti-pattern that leaks one network's
backend choice into every other network sharing the process). `CryptoBackendSpec` proves the pure path is
a transparent pass-through today (the same KAT shape that will prove `native == pure` later); forge
co-signed the byte-identity. This matches old fukuii's actual shape (single pure-JVM hot path; native JNI
only for BLS/KZG), now with the selection seam made explicit and R2-safe.

#### 14. Thread-local Keccak digest reuse, documented byte-identical

`kec256` reuses one `KeccakDigest` per thread (`ThreadLocal`) with `reset()` on entry, rather than
allocating a fresh digest per call.

**Empirical logic:** keccak is the hottest primitive — once per trie node across millions of nodes on
every state-root pass, plus every tx/block hash, address, and log-bloom. The observation's zero-alloc
synthesis says to chase *technique* discipline that ports to the JVM (erigon's pooling/memoization) and
skip what doesn't (nethermind's `Span`/`ref struct`). Thread-local digest reuse is exactly the portable
technique: it removes the per-hash digest allocation without any memory-layout trick the JVM can't
express. Two documented invariants keep it byte-exact and safe:
- **Reset-on-entry (INV-1)** — every entry point calls `reset()` first, discarding any state left by a
  hash that aborted between `update` and `doFinal` (the success-only reset inside `doFinal` never covers
  that window). A reset digest is observably identical to `new KeccakDigest(256)`, proven by the
  per-call-oracle parity test.
- **Thread-confinement (INV-2)** — the digest reference never escapes its method body. Hashing runs on
  platform-thread dispatchers; the doc flags that a per-task virtual-thread executor would defeat the pool
  and must revisit the design.

Output is byte-exact to go-ethereum `crypto/keccak.go:40` (`Keccak256` via `NewLegacyKeccak256`) — the
**original** Keccak padding (`0x01`), not FIPS-202 SHA3 (`0x06`). BouncyCastle's `KeccakDigest` is the
original-padding variant, so it matches geth's legacy digest; verified against the canonical empty-trie
root `56e81f…b421` and geth golden vectors.

#### 15. Deterministic-`k` sign + low-S canonicalization, byte-exact to geth

`ECDSASignature.sign` uses `HMacDSAKCalculator(SHA256Digest)` (RFC-6979 deterministic `k`), then
canonicalizes `s` to the low half of the curve order, then computes `v` as the point sign (27/28) that
recovers the signer.

**Empirical logic:** deterministic `k` produces the *same* `(r, s)` as go-ethereum's
decred/libsecp256k1 signer for a given key+hash — proven by the round-trip test (sign → recover ==
pubkey) and a determinism test (two signs are identical). Low-S is EIP-2 malleability rejection: for every
valid `(r, s)`, `(r, N − s)` is also valid, and consensus accepts only `s ≤ N/2`. This mirrors
go-ethereum two ways — the `s.IsOverHalfOrder()` reject in `crypto/signature_nocgo.go:121` and the
`ValidateSignatureValues` low-S branch in `crypto/crypto.go:246`. The `v` byte is read **unsigned**
(`v & 0xff`) so EIP-155 chain-encoded values ≥ 128 (e.g. `v=157` on ETC mainnet, chainId 61) don't wrap
to a negative `BigInt`; the EIP-155 → 27/28 unwinding itself lives one layer up in `domain` (the
transaction layer), and `recoverPubBytes` takes a bare point sign. Address derivation is byte-exact to
`crypto.PubkeyToAddress` (`crypto/crypto.go:253`): `Keccak256(pubKey)[12:]` over the prefix-dropped
64-byte key — verified against go-ethereum's `crypto_test.go` known key/address vector (`289c2857… →
970e8128…`).

The BouncyCastle key-material access on this path is **checked narrowing**, not an unchecked cast: the
`keyPair.getPrivate` / `getPublic` sites in `ECDSASignature`, `Secp256k1`, `ECIESCoder`, and
`EthereumIESEngine` pattern-match on `ECPrivateKeyParameters` / `ECPublicKeyParameters` and `sys.error`
on any other subtype (commit `16502d72d`), replacing the earlier `asInstanceOf`. The BouncyCastle API
returns the erased supertype, so the previous cast would `ClassCastException` opaquely on a wrong subtype;
the match fails loud with the actual class name instead — the *fail-loudly* principle at a crypto boundary.

#### 16. alt-bn128 stays pure-Scala over a `FiniteField` typeclass — the standing perf outlier

The BN128 pairing tower (`Fp ⊂ Fp2 ⊂ Fp6 ⊂ Fp12`) is a pure-Scala port of the libff/ethereumj arithmetic,
over a `FiniteField[A]` typeclass.

**Empirical logic:** the observation notes fukuii's alt-bn128 is pure-Scala where geth/besu use native
(gnark) — the standing performance outlier at this slot. It is kept pure-Scala for now (a) because it
matches old fukuii's byte behavior exactly (the EIP-196/197 precompiles are consensus-critical and this
code is the tested reference), and (b) because a native alt-bn128 backend is the same OPTIONAL(role)
native-seam decision as §13, deferred to the precompile consumer with a sentinel-gated dep-add. The
pairing is exercised end-to-end by the EIP-197 bilinearity identity `e(P, Q)·e(−P, Q) = 1` over the
standard generators, which validates the full Miller-loop + final-exponentiation path. Noted as the perf
item to revisit when precompile throughput matters.

**G2 prime-order subgroup check (consensus-correctness).** A `BN128G2` input point is validated for
**order-`r` subgroup membership**, not merely for lying on the twist curve. The twist `E'(Fp2)` has
cofactor `> 1`, so on-curve does not imply in-subgroup; an on-curve-but-off-subgroup G2 point fed to
`ECPAIRING` (`0x08`) would make the pairing return a result differing from the reference client — a
**state-root split**. The check is `[r]·P = ∞` (`BN128Fp2.mul(p, R).isZero`), applied to the **G2 path
only** — G1 over `Fp` has cofactor 1 and needs none. This is byte-for-byte the check core-geth performs in
`bn256/cloudflare/twist.go:60-62` (`cneg.Mul(c, Order); return cneg.z.IsZero()`) and go-ethereum's gnark
`IsInSubGroup` (`bn256/gnark/g2.go:59`). It is validated by an on-curve off-subgroup G2 vector asserted
rejected (the off-subgroup point independently confirmed on-curve and `[r]P ≠ ∞`), alongside the retained
bilinearity identity.

#### 17. Defense-in-depth CVE guards carried forward explicitly, plus the `constantTimeEquals` primitive

Three guard classes are retained from old fukuii, each tied to its CVE:
- **Point validation** (`decodeAndValidatePoint`) — `isValid` + reject point-at-infinity on every decoded
  EC point (CVE-2025-24883 / CVE-2026-26314 / CVE-2026-26315: invalid-curve / small-subgroup attacks).
  `decodePoint` alone only checks the curve equation.
- **ECIES truncation reject** (CVE-2026-22862) — a ciphertext shorter than `ephemeralKey(65) + IV(16) +
  MAC(32) + 1` is rejected before parsing; the MAC-length lower bound is checked in `decryptBlock`.
- **Constant-time MAC comparison** — the ECIES MAC compare routes through the built L0 `constantTimeEquals`
  primitive (`ConstantTime.scala`, a top-level `def constantTimeEquals` wrapping BouncyCastle
  `Arrays.constantTimeAreEqual`), no timing oracle.

**Empirical logic:** these are not new work — they are the security surface of the RLPx handshake and the
EVM precompiles, and dropping any of them in a "clean rewrite" would be a silent regression. They are
carried with their CVE rationale intact. **RX-L0-16** additionally required `constantTimeEquals` to exist
as a *callable L0 symbol* rather than an inline `Arrays.constantTimeAreEqual` at the ECIES site: exposing
one audited constant-time surface gives the future L8 keystore-MAC and L9 JWT/auth consumers a single
symbol to import and a single lint target (no `==`/`sameElements` at secret sites; besu unifies the same
consumers behind `ECIESEncryptionEngine`, nethermind behind `CryptographicOperations.FixedTimeEquals`).
The L6 per-frame integrity MAC stays a plain compare by design (non-secret — timing leaks nothing).
`ConstantTimeSpec` covers equal / unequal / length-mismatch.

#### 18. KZG (EIP-4844 / EIP-7594) is native-JNI by default, not an OPTIONAL(role) fast-path

`kzg.Kzg` wraps the c-kzg-4844 native library (via the `jc-kzg-4844` 2.1.6 JNI bindings) for the full
EIP-4844 blob-commitment surface (`blobToKzgCommitment`, `computeKzgProof`/`verifyKzgProof`,
`computeBlobKzgProof`/`verifyBlobKzgProof` + batch) and the EIP-7594 PeerDAS cell surface
(`computeCells`, `computeCellsAndKzgProofs`, `recoverCellsAndKzgProofs`, `verifyCellKzgProofBatch`).

**Empirical logic:** unlike keccak/secp256k1/alt-bn128 — where a pure-BouncyCastle path is the correctness
floor and the native seam is the OPTIONAL(role) optimization (§13) — KZG has *no* practical pure-JVM
implementation at consensus throughput, and every reference client (geth/besu/nethermind/reth) links the
*same* c-kzg-4844 (or crate-crypto rust) native library. The native library, together with the trusted
setup, *is* the shared byte-authority; there is no independent pure reference to be the fallback. Native
JNI is therefore the **default** at this slot, not a role-sized fast-path. The `jc-kzg-4844` jar bundles
the platform natives (linux/darwin × x86-64/aarch64), so the enterprise single-binary still "builds and
runs everywhere" with no separate native install. This is a peer-of-BN128 placement: both are consensus
pairing-based primitives that belong in `crypto` L0; only the `0x0a` point-evaluation *precompile wrapper*
(gas, 192-byte decode, versioned-hash check, dispatch) is deferred up to `evm` (L3).

**Trusted-setup lifecycle (documented invariant).** The c-kzg native library holds the trusted setup in
**process-global** state — exactly one setup per JVM, guarded inside the `.so`. `Kzg.loadTrustedSetup()`
is idempotent and thread-safe at the Scala layer (double-checked `AtomicReference` gate,
`CKZG4844JNI.loadNativeLibrary()` then `loadTrustedSetupFromResource("/trusted_setup.txt", …)` on first
call); every operation calls it first, so callers never sequence the load by hand. `freeTrustedSetup()`
is deliberately *not* exposed to application code — it is a one-way global teardown that would break every
other consumer in the process. The bundled `trusted_setup.txt` is byte-identical to the c-kzg-4844 / besu
mainnet ceremony setup (PeerDAS format: 4096 g1-lagrange + 65 g2-monomial + 4096 g1-monomial points), the
same file old fukuii shipped.

#### 19. BLS12-381 (EIP-2537) is a first-class L0 primitive, native-JNI, correcting old fukuii's mislayering

`bls.Bls12381` wraps the besu `bls12-381` 1.0.0 native library (`LibEthPairings`, the gnark/EIP-1962
`eth_pairings` backend, dispatched over JNA) for all nine EIP-2537 operations: G1 add/mul/MSM, G2
add/mul/MSM, pairing check, map-fp-to-G1, map-fp2-to-G2. A single `perform(op, input)` dispatches to the
native `eip2537_perform_operation` and returns `Either[String, Array[Byte]]` — `Right(output)` on success,
`Left(nativeError)` when the backend rejects a malformed point / non-canonical field element / wrong-length
input (the native library performs the mandatory subgroup checks). Inputs and outputs are the canonical
EIP-2537 byte encodings, passed through unmodified, so the wrapper is a pure byte conduit with no
re-encoding.

**Empirical logic:** same native-by-default reasoning as §18 — there is no pure-JVM BLS12-381 at consensus
throughput, and the besu native lib bundles the platform `.so`/`.dylib`. Placing it in `crypto` L0 as a
peer of the alt-bn128 tower and `kzg.Kzg` fixes a concrete mislayering: **old fukuii inlined the
besu-native BLS calls directly at the precompile site (`vm/PrecompiledContracts.scala`)**, entangling a
pure cryptographic primitive with EVM gas/dispatch. The primitive now lives at L0; only the BLS
*precompile wrappers* (gas schedule, MSM discount table, input-length dispatch, and the precompile-address
mapping — which itself differs across EIP-2537 revisions) are deferred to `evm` (L3).

## Improvements over old fukuii

_Grouped by module; each row is a durable design fact, not a build-status snapshot._

**`bytes` / `common`**

| Old fukuii (AS-IS) | Rebuild L0 | Why it matters |
|---|---|---|
| Value types (`UInt256`/`Address`) deferred to `domain` | Fixed-width types in `bytes` (the DEFAULT slot) | Matches the reference field; primitives are self-contained |
| `byteStringOrdering = Ordering.by(_.toSeq)` — **signed** byte compare | **Unsigned** lexicographic ordering | Signed compare sorts `0xff` before `0x01` — a latent consensus footgun in trie/access-list ordering |
| `Hex.decode` had no odd-length guard | Odd-length rejected; `0x`/`0X` tolerated; non-hex rejected | Matches go-ethereum `hexutil`; fails loud on malformed input |
| `implicit class` / `implicit def` (Scala-2 idiom) | `extension` / `given` (Scala 3) | Modern, no implicit-scope surprises |
| Raw `ByteString` with no type distinction | Type-distinct opaque `Address`/`Hash` | Can't mix an address and a hash by accident |
| `UInt256` scattered arithmetic pulled from `domain`/`vm` | 256-bit modular arithmetic **on the type**, `holiman/uint256`-exact wrapping/zero-divisor semantics | The number type is self-contained; `domain`/`Wei` math consumes it without reaching up a layer |
| `common` = large grab-bag `utils` | `common` = minimal (logging facade only) | No dependency magnet; utilities added only when a consumer needs them |

**`rlp`**

| Old fukuii (AS-IS) | Rebuild L0 `rlp` | Why it matters |
|---|---|---|
| `derives RLPCodec` **did not compile**; `RLPDerivation` unwired (0 call sites) | `derives RLPCodec` compiles, works, and is the wired path (round-trip tested) | The DEFAULT codec-authoring model is actually available |
| `type RLPCodec = RLPEncoder & RLPDecoder` — an intersection alias `derives` can never target | `trait RLPCodec[T] extends RLPEncoder[T], RLPDecoder[T]` — a derivable class type | Removes the root reason the old `derives` could not compile |
| Product derivation via existential `List[RLPCodec[?]]` + per-field `asInstanceOf[…[Any]]` loop | Recursive `TupleRLPCodec[H *: T]` — every field keeps its static type; **zero executable `asInstanceOf`** (only cast is inside stdlib `Tuple.fromProductTyped`) | Removes a runtime-cast / `ClassCastException` surface on a consensus-hashing path (commit `16502d72d`) |
| **Triple** `given` per type (`RLPEncoder`, `RLPDecoder`, `RLPEncoder & RLPDecoder`) | **One** `given RLPCodec[T]` per type | Half the boilerplate; no ambiguity between the three forms |
| `RLPImplicitConversions` — `implicit class` / `implicit def` (Scala-2 idiom) | `given`/`using`, top-level defs, `Mirror` `derives` (Scala 3) | No implicit-scope surprises; modern idiom |
| Strict decode retrofitted later (RLP-DECODE-01/02) | `decodeStrict` / `rawDecodeStrict` present from line one | Fail-loud on trailing bytes by default where it matters |
| Mantis-inherited **structural-only** decode — accepts non-canonical size headers, leading-zero scalars, over-long payloads | Canonical-form enforcement (`ErrCanonSize`/`ErrCanonInt`/`ErrValueTooLarge`), byte-exact to go-ethereum | Closes a consensus-divergence / network-partition vector; one byte-encoding per value |
| `derived*` split across `RLPDerivation` + Shapeless-era `RLPImplicitDerivations` + `.scala3`/`.backup` files | Single `RLPCodec.derived` (Mirror) | One derivation path, no dead alternates |

**`crypto`**

| Old fukuii (AS-IS) | Rebuild L0 `crypto` | Why it matters |
|---|---|---|
| `FiniteField.Ops` — `implicit class FiniteFieldOps` (Scala-2 idiom) | `extension` block in `object FiniteField` + `given` field instances | Modern Scala 3 idiom; operators resolve through the `given`, not a wrapper class |
| `implicit object FpImpl extends FiniteField[Fp]` (×4 fields) | `given fpField: FiniteField[Fp] with …` (×4) | The Scala 3 typeclass-instance form; lazy, unambiguous |
| `package object crypto` holding ~30 defs | Top-level defs across focused files (`Keccak`, `Hashes`, `Secp256k1`, …) | No god-object package; each primitive discoverable in its own file |
| `com.chipprbots.ethereum.utils.ByteUtils` (old grab-bag) | `com.chipprbots.fukuii.bytes.ByteUtils` (L0 leaf) | Clean L0→L0 edge; no dependency on a higher `utils` package |
| Raw `Array[Byte]` address derivation, ad hoc | `pubKeyToAddress → bytes.Address` (typed) | Type-distinct `Address`, byte-exact to `crypto.PubkeyToAddress` |
| BouncyCastle key access via unchecked `asInstanceOf[ECPrivate/PublicKeyParameters]` | Pattern-match + `sys.error` on wrong subtype (`ECDSASignature`/`ECIESCoder`/`EthereumIESEngine`/`Secp256k1`) | Fail-loud checked narrowing, not an opaque `ClassCastException`, at a crypto boundary (commit `16502d72d`) |
| Native/pure backend split implicit (BLS/KZG native, rest pure), no seam, undocumented | `CryptoBackend` seam **built** (one API, pure-BC sole impl, immutable `given`/R2-safe); native fast-path OPTIONAL(role) behind it | Adding a native backend later is a config swap, not a call-site rewrite; a recorded, byte-identity-gated decision (RX-L0-19) |
| Constant-time MAC compare inline (`Arrays.constantTimeAreEqual` at the call site only) | Built L0 `constantTimeEquals` primitive; ECIES MAC re-pointed to it | One audited constant-time surface + a single lint target for L8/L9 secret-compare consumers (RX-L0-16) |
| CVE guards present but scattered | Same guards, each with its CVE cited at the guard site | Auditable security surface |
| alt-bn128 G2 input validated **on-curve only** — no order-`r` subgroup check | `BN128G2` enforces `[r]·P = ∞` (G2 path only), byte-exact to core-geth `twist.go` / geth gnark `IsInSubGroup` | Closes an `ECPAIRING` state-root split on an on-curve-off-subgroup G2 point |
| **BLS12-381 native calls inlined at the precompile site** (`vm/PrecompiledContracts.scala`) | `bls.Bls12381` — a first-class L0 primitive, peer of BN128/KZG; only the precompile wrapper is `evm` L3 | Cryptographic primitive no longer entangled with EVM gas/dispatch — correct layering |
| KZG lived in root-module glue (`KzgCellProofs` etc.), trusted-setup lifecycle ad hoc | `kzg.Kzg` at L0 with an idempotent, thread-safe, documented process-global trusted-setup gate | KZG is a proper L0 primitive with a stated native-by-default rationale and setup invariant |

## Layer boundaries (what lives elsewhere, and why)

_Durable placement decisions — permanent design facts, **not** build-status (for what's committed vs
pending, see the rebuild README index alone)._

- **`Wei` and other domain-semantic wrappers** → **`domain` (L1)** — semantic types over the raw `bytes`
  primitives.
- **EVM-opcode gas-metered `UInt256` semantics** (EXP gas, sign-extend, opcode dispatch) → **`evm` (L3)**
  — those encode opcode/gas rules, not the number type.
- **Trailing-optional-omission** (old fukuii's `DerivationPolicy.omitTrailingOptionals`) → the consuming
  layer that needs it (`domain`, fork-variant block headers) as a **hand-written `given` per type**. The
  base `derived` is the straight-field-list default, matching reth's `#[derive]`; there is no
  `DerivationPolicy` object in the `rlp` module.
- **`RLPSerializable` convenience trait / typed-tx-envelope aggregation** → **`domain`**; the `rlp` engine
  provides `PrefixedRLPEncodable` (encode side) and `nextElementIndex` (stream walk), and `encode`/`decode`
  already cover the module's API surface.
- **KZG / BLS12-381 / alt-bn128 EVM *precompile wrappers*** → **`evm` (L3)**: the `0x0a`
  point-evaluation wrapper (gas / 192-byte decode / versioned-hash check / dispatch) for KZG, the
  BLS12-381 precompile wrappers (gas schedule, MSM discount table, input-length dispatch,
  precompile-address mapping), and the alt-bn128 precompiles. The cryptographic *primitives* they call
  live here at L0 `crypto`.
- **Native secp256k1 / keccak / alt-bn128 fast-paths** → future OPTIONAL(role) seam behind the built
  `CryptoBackend` (or a peer BN128 seam), sized to a mining-pool/archival throughput role, with the pure
  BouncyCastle / pure-Scala path as the guaranteed output-identical fallback. No native dep for these now.
- **Keystore KDFs** (`pbkdf2HMacSha256`, `scrypt`) → the keystore/wallet layer (L8) — key-file derivation,
  not consensus or handshake primitives.
- **RLPx handshake framing** (Auth/Ack message assembly on top of `ECIESCoder`) → **`network` (L6)**. L0
  provides the ECIES envelope; the handshake protocol that uses it is a network concern.
- **`common`** grows only when a concrete consumer needs a generic helper — never speculatively.
- **Alloc-benchmark harness** (erigon's profile-driven discipline) → added once a consumer's serialization
  hot path is real; the RLP AST's eager allocation is the thing to measure first.

## Verification

Per-module suites, all under the strict flags (`-Wunused:all`, `-Wconf:id=E198:error`,
`-Wconf:cat=unchecked:error`), **zero compiler warnings on main sources**:

- `sbt "bytes/test" "common/test"` — **55 tests green** (53 `bytes` + 2 `common`): value-type type-
  distinctness (`assertDoesNotCompile`), strict/lenient constructors, `holiman/uint256`-exact modular
  arithmetic + zero-divisor semantics, unsigned ordering, hex round-trips.
- `sbt "rlp/testOnly com.chipprbots.fukuii.rlp.*"` — **40 tests green**: canonical `rlptest.json` byte
  vectors (empty string/list, single byte, 55/56-byte header boundary, nested lists, integer scalars),
  round-trips for every base type and the `bytes` value types, a real zero-cast `derives RLPCodec`
  case-class round-trip (nested + value-type fields), `UInt256` minimal-length scalar / `Address`/`Hash`
  full-width encoding, EIP-2718 `PrefixedRLPEncodable` serialization, strict-decode trailing-byte
  rejection, and the `invalidRLPTest.json` canonical-form rejections.
- `sbt "crypto/Test/testOnly *"` — the full `crypto` suite green: keccak256/512 byte vectors +
  per-call-oracle parity + reset-after-abort + concurrency/thread-confinement; sha256/ripemd160 KATs;
  ECDSA sign→verify→recover round-trip, low-S canonical, RFC-6979 determinism, go-ethereum recovery +
  key/address vectors, `r‖s‖v` encode round-trip; secp256k1 keygen + point validation; BN128 on-curve
  arithmetic + the EIP-197 bilinearity identity + the G2 off-subgroup rejection; the ethereumj ECIES KAT +
  round-trip + truncation guard; KZG (8 tests) — the consensus-spec-tests `kzg-mainnet` KATs plus
  4844/7594 compute→verify round-trips with tamper-rejection; BLS12-381 (12 tests) — the EIP-2537
  reference KAT per operation + the pairing-identity word + `fail-*` malformed-input rejection; and
  `ConstantTimeSpec` + the property-based `CryptoBackendSpec` pure-pass-through byte-identity across
  keccak/sign/recover/pairing (RX-L0-16/19).

Consensus surfaces (RLP canonical form, ECDSA/keccak byte behavior, the alt-bn128 G2 subgroup check,
`CryptoBackend` byte-identity) are co-signed byte-exact by forge vs core-geth + besu-etc.

_(Test discovery note: under sbt 2.0.2 `<module>/Test/test` delegates to `testQuick` and can report a
false 0-test pass on a warm `target/`, a repo-wide quirk; the push-gate aliases were rewritten to
`testOnly *` to force real execution (commit `735b0607a`), so `sbt "<module>/Test/testOnly *"` is the
reliable path. The `crypto`/`rlp` test specs carry the documented `-Wnonunit-statement` "unused
Assertion" warnings from the multiple-`assert`-per-test ScalaTest pattern — warning-level only, not
applied to main sources; see `build.sbt`.)_
