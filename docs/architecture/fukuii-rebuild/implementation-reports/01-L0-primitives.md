# L0 — primitives: `bytes`, `common`

_Layer L0 (foundation). Commit `b8c064ef6`. Measured against
[`observations/primitives.md`](../../../research/clients/observations/primitives.md); old-fukuii AS-IS
in [`clients/fukuii/primitives.md`](../../../../.local/docs/research/clients/fukuii/primitives.md)._

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

### 4. `UInt256` carries its own 256-bit modular arithmetic

`UInt256` is not just a byte/ordering wrapper — it carries the **core 256-bit modular arithmetic** as
`extension` operators: add/sub/mul (mod `2^256` wrapping), div/mod (unsigned, `y == 0 ⇒ 0`), pow
(`x^y mod 2^256`, `x^0 = 1`), bitwise and/or/xor/not, logical shl/shr, and unsigned comparison.

**Empirical logic:** every reference client carries the arithmetic **on the number type itself** —
go-ethereum's `holiman/uint256.Int` (`Add`/`Mul`/`Div`/`Exp`/`Lsh`…), besu's Tuweni `UInt256`. The
wrapping and zero-divisor semantics are matched byte-for-byte to `holiman/uint256`: subtraction
wraps into `[0, 2^256)`, `Div`/`Mod` by zero return `0` (not a trap), `Exp(x, 0) = 1`. Implemented
over `BigInt` with a single `mod 2^256` reduction (`BigInt.mod` is always non-negative, giving the
correct two's-complement wrap for an underflowing subtraction) — the JVM has no native `uint256`, so
this is the besu-style big-int-backed approach, not the geth 4×`uint64` limb representation. What is
deliberately **excluded** is the gas-metered EVM-opcode layer (EXP gas, SIGNEXTEND, signed
SDIV/SMOD/SAR) — those encode opcode/gas rules and live in `evm` (L3), not on the number type.

### 5. Scala 3 idiom throughout

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
| `UInt256` scattered arithmetic pulled from `domain`/`vm` | 256-bit modular arithmetic **on the type**, `holiman/uint256`-exact wrapping/zero-divisor semantics | The number type is self-contained; `domain`/`Wei` math consumes it without reaching up a layer |
| `common` = large grab-bag `utils` | `common` = minimal (logging facade only) | No dependency magnet; utilities added only when a consumer needs them |

## Layer boundaries (what lives elsewhere, and why)

Durable placement decisions — permanent design facts, *not* build-status (for what's committed vs
pending, see the README index alone).

- **`UInt256`'s own 256-bit modular arithmetic** (add/sub/mul/div/mod/pow, bitwise, signed compare)
  belongs **in `bytes` (L0)** — it is a property of the number type, which every reference client
  carries on UInt256 (`holiman/uint256`, Tuweni `UInt256`); `domain`'s `Wei`/balance math consumes it.
- **EVM-opcode gas-metered `UInt256` semantics** (EXP gas, sign-extend, opcode dispatch) → **`evm`
  (L3)** — those encode opcode/gas rules, not the number type.
- **`Wei` and other domain-semantic wrappers** → **`domain` (L1)** — semantic types over the raw
  primitives.
- **`common`** grows only when a concrete consumer needs a generic helper — never speculatively.

(Value-type RLP codecs live in the `rlp` sibling module and hashing/address-derivation in `crypto` —
sibling L0 modules, per the layering; not boundaries of this doc.)

## Verification

`sbt "bytes/test" "common/test"` — **55 tests green** (53 `bytes` + 2 `common`), **zero compiler
warnings** under the strict flags (`-Wunused:all`, `-Wconf:id=E198:error`,
`-Wconf:cat=unchecked:error`).
