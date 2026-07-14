# L0 — primitives: `bytes`, `common`

_Layer L0 (foundation). Commit `b8c064ef6`. Measured against
[`observations/primitives.md`](../../research/clients/observations/primitives.md); old-fukuii AS-IS
in [`clients/fukuii/primitives.md`](../../research/clients/fukuii/primitives.md)._

## Scope

The two dependency-light leaves everything is built on:
- **`bytes`** — byte utilities **and** the fixed-width value types (`Address`, `Hash`/`Bytes32`,
  `UInt256`).
- **`common`** — genuinely generic, dependency-light utilities (currently just the SLF4J logging
  facade). Deliberately *not* the old grab-bag `utils` package.

`crypto` and `rlp` (also L0) are documented in `02-L0-rlp.md` (rlp) and `03-L0-crypto.md` (crypto).

## Design decisions & empirical logic

### 1. Fixed-width value types live at the primitives layer

`Address`, `Hash`, and `UInt256` are defined in `bytes`, not one layer up in `domain`.

**Empirical logic:** `observations/primitives.md`'s DEFAULT for "value-type representation" is
*fixed-width value types at the primitives level* — go-ethereum's `Hash [32]byte` / `Address
[20]byte`, besu's Tuweni `BytesHolder`. Old fukuii **diverged from this DEFAULT**: its `bytes`
module exposed only raw `ByteString`/`Array[Byte]` helpers and pushed `UInt256`/`Address` up into
`domain` — recorded as a gap in the AS-IS snapshot. The rebuild puts them where the reference
field puts them.

### 2. Opaque types, not wrapper classes

Each value type is a Scala 3 `opaque type` (`Address = ByteString`, `Hash = ByteString`, `UInt256
= BigInt`).

**Logic:** opaque types are **type-distinct at compile time but zero-cost at runtime** — an
`Address` cannot be silently mixed with a `Hash` even though both wrap `ByteString` (proven by an
`assertDoesNotCompile` test), yet there is no wrapper object allocated. This is the Scala-3 idiom
for the "type-distinct fixed-width" DEFAULT; it is *better* than the JVM wrapper-class approach
(besu/geth) on the allocation axis and equal on the type-safety axis. (The one thing it cannot do
— inline the 32 bytes into a containing object, as nethermind's C# `struct ValueHash256` does — is
a JVM limitation that does not port; the observations doc flags this explicitly, so we do not chase
it.)

### 3. Strict `apply` + explicit `fromBytesTruncating`

`Address.apply(ByteString)` / `Hash.apply(ByteString)` are **strict** — `require` exactly N bytes,
fail loud otherwise. A separate `fromBytesTruncating` carries go-ethereum's lenient `SetBytes`
(right-align / left-pad / truncate) semantics, byte-for-byte, for the boundary sites that genuinely
need it (RLP-decoded address fields, the low-20-of-32 derivation in CREATE/CREATE2/`ecrecover`,
`BytesToAddress`).

**Logic:** this is the project's *fail-loudly* principle applied to a consensus-adjacent
convention. geth's pervasive `SetBytes` leniency silently reshapes wrong-length input — fine at a
parse boundary (where it *is* the spec), a latent bug-swallower everywhere else. Splitting the two
gives fail-loud on programmer error **and** byte-exact geth behavior where the spec requires it.
`UInt256.fromBytes` stays lenient (`≤ 32` bytes) on purpose — it is a *numeric* big-endian scalar
decode, and RLP-encoded scalars / minimal-length storage values legitimately arrive shorter than
32 bytes; a strict "exactly 32" would wrongly reject valid input.

### 4. Scala 3 idiom throughout

`extension` methods and `given` instances replace the old `implicit class` / `implicit def` forms;
top-level definitions and braceless syntax throughout.

**Logic:** old fukuii's `ByteStringUtils`/`Padding` `implicit class`es and `implicit def`
conversions were called out as the Scala-2-era anti-pattern in the AS-IS snapshot. Litmus for the
rebuild: *if it isn't Scala 3 idiom, don't write it that way.*

## Improvements over old fukuii

| Old fukuii (AS-IS) | Rebuild L0 | Why it matters |
|---|---|---|
| Value types (`UInt256`/`Address`) deferred to `domain` | Fixed-width types in `bytes` (the DEFAULT slot) | Matches the reference field; primitives are self-contained |
| `byteStringOrdering = Ordering.by(_.toSeq)` — **signed** byte compare | **Unsigned** lexicographic ordering | Signed compare sorts `0xff` before `0x01` — a latent consensus footgun in trie/access-list ordering |
| `Hex.decode` had no odd-length guard | Odd-length rejected; `0x`/`0X` tolerated; non-hex rejected | Matches go-ethereum `hexutil`; fails loud on malformed input |
| `implicit class` / `implicit def` (Scala-2 idiom) | `extension` / `given` (Scala 3) | Modern, no implicit-scope surprises |
| Raw `ByteString` with no type distinction | Type-distinct opaque `Address`/`Hash` | Can't mix an address and a hash by accident |
| `common` = large grab-bag `utils` | `common` = minimal (logging facade only) | No dependency magnet; utilities added only when a consumer needs them |

## Deferred (and to which layer)

- **Wrapping EVM arithmetic** on `UInt256` (add/mul/mod-2²⁵⁶, signed ops, EXP-gas) → `evm`/`domain`.
  `bytes` owns only construction, byte form, equality, ordering.
- **`Wei` and other domain-semantic wrappers** → `domain`.
- **RLP codecs** (`given RLPCodec[T]` for these types) → `rlp` (the `derives` DEFAULT is that
  layer's job — and the cutover old fukuii never finished).
- **keccak / address-from-pubkey derivation** → `crypto` (needs the hash + curve).
- **`common` collection/error/cats helpers** → added when a concrete consumer first needs one.

## Verification

`sbt "bytes/test" "common/test"` — **55 tests green** (53 `bytes` + 2 `common`), **zero compiler
warnings** under the strict flags (`-Wunused:all`, `-Wconf:id=E198:error`,
`-Wconf:cat=unchecked:error`).
