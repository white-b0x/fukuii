# L0 review — the confidence gate before L1

_Independent review of the completed L0 (`bytes` · `common` · `rlp` · `crypto`) against the
reference-client findings, run 2026-07-14 before starting L1. Three independent lenses, none of
them the builder, per `.agents/protocols/process/systemic-review-protocol.md`. This is a review
record; the fixes it drives are tracked to their commits below._

## Lenses & method

| Lens | Agent | Scope | Result |
|---|---|---|---|
| Correctness / byte-alignment | `beacon` (independent) | byte-exactness vs go-ethereum + core-geth; alignment vs `observations/primitives.md` | **1 CRITICAL + 1 HIGH + 2 MED** |
| Scala 3 idiom | `mithril` (independent) | opaque types, given/using/extension/enum, no Scala-2 remnants | **GREEN — no findings** ("cleanest layer reviewed") |
| Test quality + coverage | `eye` (independent, ran the suites) | do they run/pass; coverage vs reference vectors; determinism | **158/158 pass; 3 coverage gaps** |

The idiom lens is clean. The correctness + test lenses found real, verified divergences from the
byte-authority — the value of the gate.

## Findings (most-severe first)

> **All findings below are CLOSED.** They were fixed in the L0 build and **independently validated at
> the L0 gate (2026-07-14, on `fukuii-rebuild`): forge byte-identity review GREEN across all 8
> consensus items, eye 223/223 with every named KAT gate executing and passing** (BN128 G2
> subgroup-rejection, RLP canonical-decode F-RLP-1/2/3 + J-RLP-1, EIP-2537 9/9 `fail-*`, KZG,
> ECDSA/keccak/address). See the per-module verdict and Confidence sections below.

| ID | Sev | Module | Issue | Reference (verified) | Resolution |
|---|---|---|---|---|---|
| **F-BN-1** | **CRITICAL** | crypto/zksnark | alt-bn128 **G2 subgroup check missing** → ECPAIRING (`0x08`) can accept an on-curve-off-subgroup G2 point and return a pairing result differing from the reference → **state-root split** | core-geth `bn256/cloudflare/twist.go:47-63` (`Order·P=∞`); go-ethereum `bn256/gnark/g2.go:59` (`IsInSubGroup`). G1 needs none (cofactor 1) | forge: add order-`r` check to the **G2 path only** (`mul(p, BN128G2.R).isZero`), + off-subgroup rejection test. Consensus-critical: byte-perfect vs core-geth |
| **F-RLP-1** | HIGH | rlp | Decoder accepts **non-canonical size headers** (single-byte-as-string, long-form `len<56`, leading-zero length) — geth rejects (`ErrCanonSize`) → network-partition vector | go-ethereum `rlp/raw.go:360-363, 410-414`; `ethereum/tests/RLPTests/invalidRLPTest.json` | forge: enforce the 3 canonical checks in `getItemBounds`; tests from `invalidRLPTest.json` |
| **F-RLP-2** | MED | rlp | Integer/BigInt/UInt256 decoders accept **leading-zero scalars** (`ErrCanonInt`); encode is byte-exact, decode is lenient | go-ethereum `rlp/decode.go:44,750`; observations "minimal-length scalars" | forge: reject `len>1 && bytes(0)==0` in scalar decoders |
| **F-RLP-3** | MED | rlp | No **payload-bounds** check on the lenient path; `slice` silently truncates a short item (`ErrValueTooLarge`) | go-ethereum `rlp/raw.go:380-383` | forge: throw when `end >= data.length` in `getItemBounds` |
| **GAP-2** | MED | crypto | BLS12-381 **fail-vector coverage 2/9 ops** — 7 EIP-2537 `fail-*.json` not copied | `EIPs/assets/eip-2537/` | forge: copy the 7 files + a `checkFailure` per op |
| **F-UINT-1** | MED | bytes | `UInt256` has **no arithmetic** (add/mul/mod-2²⁵⁶/…) — an incomplete L0 number type; `domain`/`Wei` needs it | geth `holiman/uint256`, Tuweni `UInt256` (arithmetic on the type) | forge: add core 256-bit modular arithmetic + tests |
| **GAP-3** | LOW | rlp | length-of-length overflow rejection exists but is **untested by name** | `invalidRLPTest.json` `int32Overflow` | forge: named test |
| **BUILD-1** ✅ RESOLVED | (build) | — | `sbt <module>/test` silently reports **0 tests** (false-green) under sbt 2.0.2 — root cause: `test`≡`testQuick` skip-cache on warm `target/`; `Test/`-scoping alone insufficient | — | **Fixed `735b0607a`**: push-gate aliases (`testEssential`/`testAll`/`testStandard`) rewritten to `testOnly *`; also fixed the `testOnly -- -l Tag` empty-selector variant + the `sbt-run.sh` multi-arg drop |

**Forward notes (not L0 fixes):**
- **N-1** — `ECDSASignature` exposes low-S/range primitives but does not *gate* (r,s∈[1,N-1], s≤N/2). Correctly an L2 (tx-admission) concern; **`domain`/tx-admission must enforce `ValidateSignatureValues`** — carried to L1/L2.
- **N-2** — BLS/KZG have no pure fallback (native-only, `isAvailable` guard) → EIP-2537/4844 precompiles unavailable on a platform where the native lib won't load. Enterprise-single-binary portability, **not consensus**; the OPTIONAL(role) pure-fallback seam.

## Per-module verdict

_(Post-remediation — every module GREEN as of the 2026-07-14 L0 gate.)_

| Module | Verdict |
|---|---|
| `bytes` | **GREEN** — value types/hex/ordering byte-exact; F-UINT-1 closed (`UInt256` modular arithmetic built + tested) |
| `common` | **GREEN** |
| `rlp` | **GREEN** — F-RLP-1/2/3 + J-RLP-1 + GAP-3 closed: canonical strict decode byte-exact to geth, `RLPCanonicalDecodeSpec` passing |
| `crypto` (keccak/secp256k1/hashes/KZG/BLS) | **GREEN** — byte-exact; GAP-2 closed (9/9 EIP-2537 `fail-*` KATs) |
| `crypto/zksnark` (alt-bn128) | **GREEN** — F-BN-1 closed: G2 order-`r` subgroup check byte-exact to core-geth, off-subgroup rejection KAT passing |

## besu JVM-implementation lens (follow-up pass, 2026-07-14)

_Added after the initial go-ethereum/core-geth byte-lens: an independent `beacon` pass comparing the
**stable** L0 code (secp256k1, keccak, address derivation, bytes value-types, RLP encode, ECIES, KZG/BLS
wrappers) against **besu's Java** — the lens for JVM-implementation bugs a Go comparison cannot surface.
Codified as review-lens #2 in `.local/docs/phase4/target-architecture.md`. Excludes forge's in-flight
files (BN128 / RLP decode-bounds / UInt256), covered at the gate._

**It found what the Go lens structurally could not** — the value of the lens, first pass:

| ID | Sev | Module | Issue | besu reference (verified) | Resolution |
|---|---|---|---|---|---|
| **B-BLS-1** | **MED** | crypto/bls | EIP-2537 driven through besu-native's **legacy `LibEthPairings` (matter-labs eth_pairings)** backend; **besu's own precompile now uses `LibGnarkEIP2537` (gnark)** — distinct native libs, distinct subgroup-check/map-to-curve/error edge-cases. Consensus-active on ETH/Sepolia (Prague). geth uses gnark-in-Go → invisible to a Go diff | besu `evm/.../precompile/AbstractBLS12PrecompiledContract.java:22,161` (`LibGnarkEIP2537`) | **Decided: DEPENDENCY KEPT.** The full EIP-2537 positive KAT set — every G1/G2 add/mul/msm, pairing, and map-to-curve *output* vector — passes byte-for-byte against `LibEthPairings` (the `Bls12381Spec` success suite), alongside the 9-op subgroup-**rejection** set (GAP-2). `LibEthPairings` is byte-correct for EIP-2537, so no swap to `LibGnarkEIP2537` is warranted. Only fix applied: the `Bls12381` doc-comment that conflated the backend with gnark now names the matter-labs eth_pairings (EIP-1962) backend accurately. Had any positive vector diverged, the resolution was a `sentinel`-gated dep swap (identical `eip2537_perform_operation` ABI) — not triggered |
| **J-RLP-1** | WARNING | rlp | `getItemBounds` computed the long-form payload end as `beginPos + length - 1` in **signed 32-bit `Int`**. A canonical 4-byte length in the top `Int` window (e.g. `bb 7f ff ff fd`, `length ≈ 0x7ffffffd`) overflows `end` to negative; the F-RLP-3 guard `end >= data.length` then reads `negative >= length → false` and is **bypassed** — lenient `rawDecode` accepts a malformed frame geth rejects, and nested-list decode drives `pos` negative into `ArrayIndexOutOfBoundsException`. Both the long-string (`:157`) and long-list (`:178`) branches. Found in the F-RLP-3 remediation | besu `RLPDecodingHelpers.extractSizeFromLongItem` (`:68-98`) carries size in `long` + `Math.toIntExact` before any `int` truncation; geth `rlp/raw.go` `readKind` (`uint64` + `ErrValueTooLarge`) | **Resolved.** Length read into an unsigned `Long` (`bigEndianLengthToLong`) and the payload-end bounds check compared in `Long` before `Int` truncation, in both branches — matching besu's `toIntExact` gate. **Subsumes J-RLP-3** (4-byte length with high bit set no longer reads negative) and **J-RLP-2** (length-of-length `> 4` now rejected up front with a self-describing "exceeds max supported size" message, via the new `MaxLengthOfLength` gate, matching besu `extractSizeFromLongItem`'s `sizeLength > 4` reject). `RLPCanonicalDecodeSpec` adds the `bb 7f ff ff fd` / `fb …` overflow vectors on both lenient and strict paths, asserting clean `RLPException` (not `ArrayIndexOutOfBoundsException`), plus a large-but-valid non-regression case |
| **B-ECDSA-1** | LOW | crypto | `recoverPubBytes` (`ECDSASignature.scala:118-133`) missing besu's `q.isInfinity()` reject-guard → infinity point yields `Some(Array())` (empty pubkey) **accepted**, vs besu/geth **reject**. DL-hard to reach → negligible, but a real accept-vs-reject divergence | besu `AbstractSECP256.java:353` `if (q.isInfinity()) return null;` | **Resolved.** `if q.isInfinity then None` guards the recovered point before `getEncoded(false).tail`; an infinity recovery now returns `None` (reject), matching besu/geth. Covered by a deterministic unit test that crafts a recovery to infinity (`R = G`, `s = e`) and asserts `None` |
| **B-KZG-1** | LOW | crypto/kzg | Bundled `/trusted_setup.txt` provenance asserted only in a comment; fails loud at load if wrong, but unverified | besu bundles `/kzg-trusted-setups/mainnet.txt`, `Precompute=0` (matches) | **Resolved.** `KzgSpec` now pins the bundled setup's SHA-256 (`d39b9f2d…f0f26b7`) as a provenance lock and cross-checks the zero-blob `blobToKzgCommitment` KAT (`c0‖00·47`), transitively proving the loaded setup is the canonical c-kzg mainnet ceremony. `Precompute=0` matches besu |
| **B-RLP-N2** | NOTE | rlp | `Byte`/`Short` decoders (`RLPCodecs.scala:36-51`) omit `requireMinimalScalar` that `Int`/`BigInt`/`UInt256` carry. Non-consensus (consensus scalars decode as guarded types) | geth `ErrCanonInt` applies to all int kinds | **Resolved.** `requireMinimalScalar` now guards the `Byte` and `Short` decoders too, so all integer kinds reject leading-zero scalars uniformly. `RLPCanonicalDecodeSpec` adds Byte/Short rejection and canonical round-trip cases |
| **B-RLP-N1** | NOTE | rlp | Tuple2–5 decoders (`RLPCodecs.scala:125-164`) accept trailing elements (`RLPList(x,y,_*)`), unlike strict `derived`. Non-consensus (canonical structures use strict `derived`) | besu `RLPInput.leaveList()` | Optional: tighten to exact arity or document leniency. No consensus action |

**GREEN vs besu (explicitly clean):** secp256k1 sign/canonical-S(EIP-2)/recovery/address-derivation
(`HMacDSAKCalculator` RFC-6979, `BigInt(1,…)` unsigned, same `SECNamedCurves` table — identical to besu's
`normaliseSignature`/`decompressKey`/`recoverFromSignature`); keccak legacy-padding + thread-local reset;
bytes value-types (the `& 0xff` unsigned ordering correctly avoids the signed-`Byte` footgun; two's-complement
sign-byte handled; strict `apply` vs `fromBytesTruncating` split); RLP encode path (byte-identical headers/
scalars/EIP-2718 envelope); ECIES (constant-time MAC, CVE-2026-22862 guards, fixed-width secret, per-call
cipher); KZG wrapper (`Precompute=0` matches besu, correctly-ordered setup load).

**Net:** no new consensus-breaking bug in the stable files; B-BLS-1 is the material find — a JVM-backend
divergence that only the besu lens exposes, resolved by GAP-2's vectors at the gate.

## Confidence & resolution

**Consensus-sound (gate passed 2026-07-14).** The two adversarially-reachable divergences the review
caught — F-BN-1 (CRITICAL, alt-bn128 G2 subgroup, a chain-split) and F-RLP-1 (HIGH, non-canonical
RLP, a partition vector) — plus F-RLP-2/3, GAP-2/3, F-UINT-1, and the besu-lens finds (J-RLP-1,
B-ECDSA-1, B-KZG-1, B-RLP-N2) are all closed. The value-type layer, keccak, secp256k1
recovery/address-derivation, RLP strict decode, alt-bn128 with the G2 subgroup check, and the native
KZG/BLS wrappers are byte-exact against the authorities and trustworthy.

**Resolution (executed + validated).** `forge` closed F-BN-1 (byte-perfect vs core-geth `twist.go`),
F-RLP-1/2/3, GAP-2, F-UINT-1, GAP-3 in the L0 build; the sbt-2 test false-green (BUILD-1) was fixed
in `735b0607a`; and the whole layer was **re-validated at the L0 gate on `fukuii-rebuild`** — forge
byte-identity review GREEN across all 8 consensus items (both ETC authorities: core-geth Go +
besu-etc JVM), eye 223/223 with every KAT gate confirmed executing. Every module is GREEN; L0 is done
and safe to build L1 on. The gate caught a chain-splitting bug before we built on top of it — the
reason it exists.
