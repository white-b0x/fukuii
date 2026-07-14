# nethermind — primitives
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind's primitives layer is built around one dominant idea: **push allocation
off the heap**. C# gives it tools Go and the JVM lack — `struct` (stack/inline value
types), `ref struct` (stack-only, can hold a `Span`), `Span<byte>`/`ReadOnlySpan<byte>`
(a length + pointer view over arbitrary memory), SIMD intrinsics (`Vector256<byte>`),
and `Unsafe`/`MemoryMarshal` for reinterpret-casts. The primitive value types come in
**two flavors**: a reference-type "commitment" object (`Hash256`, `Address`) with
identity/nullability, and a paired `readonly struct` "value" variant
(`ValueHash256`, `ValueAddress`) that inlines the raw bytes with zero heap allocation.
Hot paths (trie keys, hashing, comparisons, dictionary lookups) use the struct form.

Three sub-areas:

- **`Nethermind.Core/`** — the value types. `ValueHash256` (a 32-byte hash stored as a
  single `Vector256<byte>`), `Hash256`, `Address`/`ValueAddress`, `PublicKey`,
  `Signature`, `Bloom`. Managed Keccak (`KeccakHash`) with a SIMD fast-path and a
  lock-free `KeccakCache`.
- **`Nethermind.Serialization.Rlp/`** — RLP as a **per-type decoder registry**. Every
  encodable domain type has a dedicated `RlpDecoder<T>` class; a registry maps
  `Type → IRlpDecoder`. Reading/writing goes through the `ref struct RlpReader` and the
  `TWriter : struct, IRlpWriteBackend` generic writer, both span-backed.
- **`Nethermind.Crypto/`** — thin managed wrappers over **native bindings**: secp256k1
  (`Nethermind.Crypto.SecP256k1`), BLS12-381 (`Nethermind.Crypto.Bls`, blst), and KZG
  (`Ckzg.Bindings`), plus BouncyCastle for ECIES/RIPEMD. All take/return `Span<byte>`.

## Key types / interfaces / files

- `Nethermind.Core/Crypto/Hash256.cs:19` — `public readonly struct ValueHash256` — a
  32-byte hash held as a single `private readonly Vector256<byte> _bytes` (SIMD register
  width). Zero heap allocation; equality is one vector compare (`_bytes.Equals`, line 60);
  `Bytes`/`BytesAsSpan` (lines 29-31) expose the interior as a `Span` via
  `MemoryMarshal.CreateSpan(ref Unsafe.AsRef(in _bytes), 1)` with no copy. This is *the*
  archetypal nethermind primitive.
- `Nethermind.Core/Crypto/Hash256.cs` — `Hash256` (the reference-type "commitment"
  wrapper, carries a `ValueHash256`); `Hash256AsKey` (line 110, a struct dictionary-key
  adapter). Implicit conversions both directions (lines 33-34, 99).
- `Nethermind.Core/Address.cs:24` — `sealed class Address` (20 bytes), backed by
  `private readonly ValueAddress _bytes` (line 37); `ToStructRef()` (line 233) yields a
  `ref struct` `AddressStructRef` for stack-only use. `Address(in ValueHash256 hash)`
  (line 53) slices the last 20 bytes of a hash with no intermediate array.
- `Nethermind.Core/Crypto/KeccakHash.cs:72` — `ComputeHash(ReadOnlySpan<byte>, Span<byte>)`
  — managed Keccak-f800 with `stackalloc ulong[STATE_SIZE]` state and hand-vectorized
  fast paths for exactly-20-byte (Address, `Vector128`) and exactly-32-byte (`Vector256`)
  inputs (lines 91-116) and vector-width output copy (lines 131-139). No allocation, no
  P/Invoke for the common case.
- `Nethermind.Core/Crypto/KeccakCache.cs:25` — `static unsafe class KeccakCache` — a
  fixed 64 MB one-way set-associative cache (512k entries) using a **seqlock** (lock-free
  reads: read seq → speculatively read → verify seq unchanged; single CAS on write; drop
  on contention). Memoizes Keccak of repeatedly-hashed inputs (e.g. account addresses).
- `Nethermind.Core/Crypto/Keccak.cs:13` — `static class ValueKeccak` — the compute entry
  point returning `ValueHash256`; precomputed constants `OfAnEmptyString`,
  `OfAnEmptySequenceRlp`, `EmptyTreeHash` (lines 18-28).
- `Nethermind.Serialization.Rlp/Rlp.cs:74` — `_decoderBuilder` (`Dictionary<RlpDecoderKey,
  IRlpDecoder>`) + `Decoders` (a `FrozenDictionary`, `Rlp.std.cs:17`) — **the per-type
  decoder registry**. `static Rlp()` (line 56) auto-registers every decoder in the
  assembly at type-load.
- `Nethermind.Serialization.Rlp/Rlp.std.cs:32` — `RegisterDecoders(Assembly, bool)` — the
  registration mechanism: reflects over exported types, finds any implementing
  `IRlpDecoder<T>`, keys them by `T` (+ optional `[Rlp.Decoder(key)]` variant, e.g.
  storage vs. network form), instantiates via `Activator`. `[Rlp.SkipGlobalRegistration]`
  (`Rlp.cs:846`) opts a decoder out (used by `TxDecoder`, `ReceiptArrayStorageDecoder`).
- `Nethermind.Serialization.Rlp/IRlpDecoder.cs:11` — `interface IRlpDecoder<T>` — the
  per-type contract: `GetLength`, `Encode<TWriter>(ref TWriter, T)` (generic over a
  `struct, IRlpWriteBackend, allows ref struct` writer), `Decode(ref RlpReader, ...)`,
  plus `DecodeComplete`/`DecodeGuardNotNull` correctness variants.
- `Nethermind.Serialization.Rlp/RlpReader.cs:20` — `public ref struct RlpReader` — the
  stack-only cursor over a `ReadOnlySpan<byte>`; cannot escape to the heap (enforced by
  the compiler), which is what makes span-based decoding safe and allocation-free.
- `Nethermind.Serialization.Rlp/HeaderDecoder.cs:16` — `sealed class HeaderDecoder :
  RlpDecoder<BlockHeader>, IHeaderDecoder` — representative per-type decoder; field-by-field
  `writer.Encode(header.ParentHash)` … (lines 103-147) with explicit fork-conditional
  fields (base fee, withdrawals root, blob gas, AuRa). Contrast a reflection walk.
- `Nethermind.Serialization.Rlp/RlpDecoder.cs:11` — `abstract class RlpDecoder<T> :
  IRlpDecoder<T>` — shared base giving each concrete decoder the boilerplate
  (`Encode`→`Rlp`, array encode, `DecodeComplete`) so the per-type class only writes the
  field layout.
- `Nethermind.Crypto/Ecdsa.cs:19,43` — `SecP256k1.VerifyPrivateKey` /
  `RecoverKeyFromCompact` — the native secp256k1 binding (`Nethermind.Crypto.SecP256k1`
  package) doing sign/recover on spans.
- `Nethermind.Crypto/BlsSigner.cs:13-17` — `using G1 = Bls.P1; …` over the
  `Nethermind.Crypto.Bls` (blst) native binding; sign/verify take `ReadOnlySpan<byte>`.
- `Nethermind.Crypto/KzgPolynomialCommitments.std.cs` + `Nethermind.Crypto.csproj:15`
  (`Ckzg.Bindings`) — EIP-4844 KZG blob proofs via the c-kzg native library.

## Design decisions & rationale

- **Two-form value types (`X` class + `ValueX` struct).** The struct form is used wherever
  the value is a transient key/comparand (trie traversal, dictionary lookups, hashing
  intermediates) to avoid per-item heap objects and GC pressure; the class form is used
  where identity or nullability matters. Implicit conversions keep call sites clean.
- **Hash as a SIMD register (`Vector256<byte>`), not `byte[]`.** A 32-byte hash is exactly
  one AVX2 register, so equality/inequality is a single vector op and the value inlines
  into containing structs/arrays. `byte[]` would cost a header + bounds-checks + an
  indirection per hash.
- **Managed Keccak with a cache, not always-native.** Keccak is on the hottest path
  (every trie node, every address). A managed SIMD implementation avoids P/Invoke
  marshalling overhead for tiny inputs, and `KeccakCache`'s seqlock memoizes the most
  repeated inputs lock-free. Native bindings are reserved for the algorithms where the
  native lib is decisively faster and less hot per call (secp256k1, BLS, KZG).
- **Per-type decoder registry over reflection-per-field.** Each `RlpDecoder<T>` is
  hand-written, so the encode/decode of a `BlockHeader` is a straight-line sequence of
  span writes with no runtime field walk — fast and, more importantly, **explicit about
  fork-conditional fields and storage-vs-wire form** (the `[Rlp.Decoder(key)]` variants).
  Registration is reflection *once* at startup into a `FrozenDictionary`; the steady state
  is a hash lookup, not reflection.
- **`ref struct` reader / generic `struct` writer.** `RlpReader` being a `ref struct`
  guarantees at compile time it never escapes to the heap, making span-backed zero-copy
  decode memory-safe. The writer is generic (`Encode<TWriter> where TWriter : struct,
  IRlpWriteBackend, allows ref struct`) so the same decoder body serves a length-counting
  pass, a `Span` writer, and a pooled-buffer writer with no virtual dispatch.

## Notable patterns (the reusable idea)

- **The paired value/reference type.** For any fixed-width identifier (hash, address),
  ship a zero-alloc inline `readonly struct` for hot paths alongside the nullable class.
- **Fixed-width value as a SIMD register.** Store a 32-byte quantity as `Vector256<byte>`
  so equality is one instruction and the value inlines.
- **Registry of hand-written per-type codecs, auto-discovered once.** Keeps codecs
  explicit and fast while avoiding a giant hand-maintained `switch` — the assembly scan at
  startup wires them up, and multiple named variants (storage/wire/compact) coexist per type.
- **Seqlock memoization cache for a pure hot function** (Keccak) — lock-free reads, CAS
  write, drop-on-contention.

## Authority note

go-ethereum = canonical primitive behavior (byte-for-byte hashing, RLP, ECDSA/ECC
semantics). Nethermind is the **C# zero-alloc-struct variant + per-type RLP decoder
registry** — a re-implementation whose *outputs* must match geth but whose *internal
representation* (SIMD structs, spans, ref structs) is a C#-specific performance strategy,
not part of the spec. For PoW/ETC specifics fukuii still defers to **core-geth**, not
nethermind.

## Gotchas / anti-patterns / things they later changed

- **`Unsafe.As`/`MemoryMarshal` reinterpret-casts assume exact widths.** `ValueHash256`'s
  constructors only `Debug.Assert(bytes.Length == 32)` (Hash256.cs:38,45,55) — in a
  Release build a wrong-length input reads adjacent memory rather than throwing. The
  invariant is enforced by convention/asserts, not by the runtime.
- **`ref struct` reader cannot be stored, captured in a lambda, boxed, or used across an
  `await`.** Powerful for safety, but it constrains how decode code is structured — you
  pass `ref RlpReader` down the stack, never hold it.
- **Registry ordering / duplicate keys throw at startup.** `RegisterDecoders`
  (`Rlp.std.cs`) throws `InvalidOperationException` on a duplicate `RlpDecoderKey` unless
  `canOverrideExistingDecoders`; a decoder with no parameterless (or all-optional)
  constructor throws too. Decoders that must be hand-wired (e.g. `TxDecoder`,
  `ReceiptArrayStorageDecoder`) carry `[Rlp.SkipGlobalRegistration]` and are registered
  explicitly.
- **Managed Keccak's SIMD fast-paths are input-length-specialized** (exactly 20 / exactly
  32 bytes). Correct, but a reminder that the "fast path" only fires for those shapes;
  arbitrary-length inputs take the general loop.

## Scala-analog caveat (for fukuii)

Scala/JVM has **no `Span<byte>`, no `ref struct`, no stack-allocated user structs, and no
SIMD-register value types**. The nearest reachable analogs:

- `ValueHash256` (inline zero-alloc 32-byte value) → **opaque type over a `Array[Byte]`**
  (`opaque type Hash256 = Array[Byte]`) or an `AnyVal`/value class — but the JVM still
  heap-allocates the backing array; you erase the *wrapper* object, not the array. There
  is no way to inline 32 bytes into a containing object as nethermind does with
  `Vector256<byte>`. The realistic JVM win is avoiding the *wrapper* allocation, not the
  byte storage. (fukuii's existing `bytes`/`crypto` modules already lean on `ByteString`.)
- Per-type RLP decoder registry → **a typeclass** (`given RLPDecoder[BlockHeader]`) is the
  idiomatic Scala 3 form: compile-time-resolved, per-type, explicit — arguably cleaner than
  nethermind's runtime `FrozenDictionary`, and it fits fukuii's existing RLP typeclass
  approach. This pattern **transfers directly**.
- Native crypto bindings (secp256k1/BLS/KZG) → JNI/foreign-function bindings; fukuii already
  uses native secp256k1 similarly, so the *structure* (thin managed wrapper over native
  lib, span/array in-out) carries over even though the zero-copy span story does not.

**Most transferable idea:** the **per-type decoder registry expressed as a Scala 3
typeclass** — it gives correctness (explicit fork-conditional/storage-vs-wire variants)
and performance (no reflection walk) without needing any of C#'s memory primitives. The
zero-alloc-struct machinery (`ValueHash256`, `Span`, `ref struct`) is the part that does
**not** port — Scala's ceiling is erasing the wrapper via opaque types, never inlining the
bytes.
