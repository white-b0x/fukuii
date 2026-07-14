# L0 — RLP: `rlp`

_Layer L0 (foundation), depends on `bytes`. Measured against
[`observations/primitives.md`](../../research/clients/observations/primitives.md) (the RLP-codec
row and the "Derive-macro / typeclass-derived RLP codecs" DEFAULT); old-fukuii AS-IS in
[`clients/fukuii/primitives.md`](../../research/clients/fukuii/primitives.md). Byte layout is
matched against go-ethereum `rlp/raw.go`, `rlp/encbuffer.go`, `rlp/decode.go`._

## Scope

The Recursive Length Prefix codec — the serialization every state root, transaction hash and block
hash is built on. `rlp` sits at L0 alongside `bytes`/`crypto`; it depends only on `bytes` (for the
value types and big-endian byte plumbing).

Four pieces:
- **The codec typeclass** — `RLPEncoder[T]` / `RLPDecoder[T]` and the combined `RLPCodec[T]`, with a
  working `derives RLPCodec`.
- **The byte engine** — `RLPEncodeable` intermediate AST ⇄ `Array[Byte]`.
- **Base-type + value-type instances** — one `given RLPCodec[T]` per type.
- **The public API** — top-level `encode`/`decode`/`decodeStrict`/`rawDecode`.

## Design decisions & empirical logic

### 1. Typeclass/`derives`-derived codecs are the default — and `derives` actually works

`case class X(...) derives RLPCodec` compiles and produces a working codec, wired from line one.

**Empirical logic:** `observations/primitives.md` names *"Derive-macro / typeclass-derived RLP
codecs"* the **DEFAULT** — reth's alloy `#[derive(RlpEncodable)]` and nethermind's per-type decoder
registry both map onto a Scala 3 `given RLPCodec[T]` / `derives RLPCodec`: compile-time-resolved,
per-type, no runtime reflection walk, and fork-conditional / storage-vs-wire variants stay visible
per type. Old fukuii **had the Mirror machinery** (`RLPDerivation.scala`, `Mirror.ProductOf`-based)
**but never finished the cutover**: it was unwired (0 production call sites) and the advertised
`derives RLPCodec` **did not compile**. The rebuild builds the derivation *into* the codec companion
and proves it with a real `derives` round-trip test.

**One implementation correction over the old design.** Old fukuii declared
`type RLPCodec[T] = RLPEncoder[T] & RLPDecoder[T]` (a type alias to an intersection). Scala 3's
`derives` clause requires a **class type**; a type alias to an intersection is rejected outright as
*"not a class type"*, so that form could **never** have been a `derives` target no matter how its
companion object was written — adding a `derived` method to it is necessary but not sufficient. The
rebuild uses `trait RLPCodec[T] extends RLPEncoder[T], RLPDecoder[T]`, which is semantically
identical (an `RLPCodec[T]` still *is* both an encoder and a decoder for `T`, and satisfies any
`using RLPEncoder[T]` / `using RLPDecoder[T]`) but is a real class type the compiler will derive.

### 2. Intermediate AST, not a streaming cursor

The engine keeps an explicit `RLPEncodeable` tree (`RLPList` / `RLPValue` / `PrefixedRLPEncodable`).

**Empirical logic:** the observations doc records **besu's streaming-cursor `RLPInput`/`RLPOutput`**
as the JVM *structural* analog — *"the shape to mirror for the reader/writer plumbing under the
typeclass"* — explicitly **not a competing codec-authoring model**. The AST is retained over the
cursor because it composes directly with `Mirror` derivation: each product field is an
`RLPEncodeable`, assembled into an `RLPList`, with no mutable-writer threading. The trade-off,
stated plainly: the AST **eagerly allocates** a tree per encode — the residual allocation cost the
observations doc flags as fukuii's main one at this slot. It is offset (not eliminated) by the
allocation-conscious engine below; the deeper pooling/benchmark discipline (erigon's) is deferred.

### 3. Allocation-conscious engine, byte-exact to go-ethereum

Three specific choices in `RLP.scala`, all byte-identical to go-ethereum:
- **Single-pass sized-buffer list encode** — the concatenated payload is built in one
  pre-sized `Array` (encode each item once, sum lengths, `arraycopy`) instead of
  `foldLeft(Array())(_ ++ _)`, which reallocated and recopied the whole accumulator per item
  (O(n²) in list size).
- **Slice-free big-endian int read** — `bigEndianMinLengthToInt(data, offset, len)` reads a
  length-prefix in place, no intermediate array.
- **Minimal-length scalar encoding** — integers encode with no leading zeros, `0` ⇒ empty string
  (`0x80`), matching go-ethereum's `rlp/encbuffer.go`. The 55/56-byte short↔long header boundary
  and the single-byte `< 0x80` self-encoding are verified against the canonical
  `ethereum/tests/RLPTests/rlptest.json` vectors.

### 4. Value types encode by their spec role — scalar vs. byte string

`UInt256` encodes as a **minimal-length big-endian scalar** (no leading zeros, `0` ⇒ empty string);
`Address` / `Hash` encode as their **full fixed-width byte string** (leading zeros preserved).

**Empirical logic:** this is the RLP spec's string-vs-scalar distinction, and it is consensus-load-
bearing — a storage value (`UInt256`) is a quantity and strips leading zeros, while an address is a
20-byte string and must not. Decode of `Address`/`Hash` is **strict on length**, matching
go-ethereum decoding into its fixed `[20]byte` / `[32]byte` array types; `UInt256.fromBytes` stays
lenient (`≤ 32` bytes) because RLP-encoded scalars legitimately arrive shorter than 32 bytes.

### 5. Strict decode built in from the start

`decodeStrict[T]` / `rawDecodeStrict` reject trailing bytes after the first complete item; the
lenient `decode` / `rawDecode` ignore them.

**Empirical logic:** this is old fukuii's RLP-DECODE-01/02 resolution, present from line one rather
than retrofitted. A buffer that by design holds exactly one self-contained item (a stored state
value, a persisted record) must fail loud on trailing garbage — the project's *fail-loudly*
principle. The lenient path is kept for prefix-plus-payload frames (an RLPx message whose type byte
precedes the payload), where trailing bytes are expected.

## Improvements over old fukuii

| Old fukuii (AS-IS) | Rebuild L0 `rlp` | Why it matters |
|---|---|---|
| `derives RLPCodec` **did not compile**; `RLPDerivation` unwired (0 call sites) | `derives RLPCodec` compiles, works, and is the wired path (proven by a round-trip test) | The DEFAULT codec-authoring model is actually available |
| `type RLPCodec = RLPEncoder & RLPDecoder` — an intersection alias `derives` can never target | `trait RLPCodec[T] extends RLPEncoder[T], RLPDecoder[T]` — a derivable class type | Removes the root reason the old `derives` could not compile |
| **Triple** `given` per type (`RLPEncoder`, `RLPDecoder`, `RLPEncoder & RLPDecoder`) | **One** `given RLPCodec[T]` per type | Half the boilerplate; no ambiguity between the three forms |
| `RLPImplicitConversions` — `implicit class` / `implicit def` (Scala-2 idiom) | `given`/`using`, top-level defs, `Mirror` `derives` (Scala 3) | No implicit-scope surprises; modern idiom |
| Strict decode retrofitted later (RLP-DECODE-01/02) | `decodeStrict` / `rawDecodeStrict` present from line one | Fail-loud on trailing bytes by default where it matters |
| `derived*` split across `RLPDerivation` + Shapeless-era `RLPImplicitDerivations` + `.scala3`/`.backup` files | Single `RLPCodec.derived` (Mirror) | One derivation path, no dead alternates |

## Deferred (and to which layer)

- **Trailing-optional-omission derivation policy** (old fukuii's `DerivationPolicy.omitTrailingOptionals`)
  → the consumer layer that needs it (`domain`, fork-variant block headers). The base `derived` is
  the straight-field-list default, matching reth's `#[derive]`; special fork/wire variants stay
  hand-written as an explicit `given`, exactly as reth hand-writes those cases.
- **`RLPSerializable` convenience trait** → `domain`, if a concrete consumer wants it; `encode`/
  `decode` already cover the module's API surface.
- **Alloc-benchmark harness** (erigon's profile-driven discipline) → added once a consumer's
  serialization hot path is real; the AST's eager allocation is the thing to measure first.
- **Typed-transaction / typed-receipt aggregation** (reading a stream of EIP-2718 envelopes back) →
  `domain`; the engine provides `PrefixedRLPEncodable` (encode side) and `nextElementIndex` (stream
  walk), the higher-level aggregator belongs with the transaction types.

## Verification

`sbt "rlp/compile" "rlp/Test/compile" "rlp/testOnly com.chipprbots.fukuii.rlp.*"` —
**40 tests green**, **zero compiler warnings on main sources** under the strict flags
(`-Wunused:all`, `-Wconf:id=E198:error`, `-Wconf:cat=unchecked:error`). Coverage: canonical
`rlptest.json` byte vectors (empty string/list, single byte, 55/56-byte header boundary, nested
lists, integer scalars), round-trips for every base type and the `bytes` value types, a real
`derives RLPCodec` case-class round-trip (including nested and value-type fields), `UInt256`
minimal-length scalar encoding, `Address`/`Hash` full-width encoding, EIP-2718 `PrefixedRLPEncodable`
serialization, and strict-decode rejection of trailing bytes.

_(Test discovery note: plain `sbt rlp/test` reports 0 tests — a repo-wide sbt-2.0.2 quirk affecting
every module, including `bytes`; `testOnly` with a wildcard is the working discovery path.)_
