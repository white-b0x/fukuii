# go-ethereum — primitives
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary
go-ethereum's primitives layer is three loosely-coupled packages: `rlp/` (reflection-driven serialization with an optional code-generation path), `common/` (fixed-size value types `Hash`/`Address` plus hex/bytes helpers), and `crypto/` (Keccak hashing, secp256k1 ECDSA, and pairing/blob curves bn256/BLS12-381/KZG). The defining structural choice is that every performance- or safety-sensitive crypto primitive ships with **two interchangeable backends selected at compile time by Go build tags** (`cgo` C library vs. pure-Go), so a single import site transparently gets either a fast native implementation or a portable fallback. These packages sit at the bottom of the dependency graph — `core/types`, `core/vm`, `p2p`, and the txpool all build on them — and `common.Hash`/`Address` are the lingua franca passed upward everywhere.

## Key types / interfaces / files
- `rlp/encode.go:45` — `Encoder` interface (`EncodeRLP(io.Writer) error`); user opt-in hook, otherwise reflection is used.
- `rlp/decode.go:68` — `Decoder` interface (`DecodeRLP(*Stream) error`); the streaming decode counterpart.
- `rlp/encode.go:62,78` — `Encode(w, val)` / `EncodeToBytes(val)`, the two public entry points; `decode.go:82,92` mirror them (`Decode`, `DecodeBytes`).
- `rlp/decode.go:586,616` — `Stream` type + `NewStream(r, inputLimit)`: pull-based decoder with an explicit input-size limit (DoS guard).
- `rlp/typecache.go:74` — `typeCache.info()`: per-`reflect.Type`+struct-tag cache of generated encoder/decoder closures (`typekey` at line 40 keys on both type *and* tags, since tags change the codec).
- `rlp/internal/rlpstruct/rlpstruct.go:66` — `Tags` struct: the three struct-tag behaviors `rlp:"nil"` (line 67), `rlp:"optional"` (line 72), `rlp:"tail"` (line 76); `ProcessFields` (line 104) enforces that no required field follows an optional one.
- `rlp/rlpgen/main.go` — `rlpgen` codegen tool: given `-type Header`, emits a hand-optimized `EncodeRLP`/`DecodeRLP` with no reflection (`core/types/block.go:65` shows the `//go:generate ../../rlp/rlpgen -type Header` directive; generated output is `core/types/gen_header_rlp.go`, `gen_log_rlp.go`, `gen_account_rlp.go`, `gen_withdrawal_rlp.go`).
- `rlp/encode.go:106` — `EncodeToRawList[T any]`: newer Go-generics helper producing a typed `RawList[T]`.
- `common/types.go:38-41` — `HashLength = 32`, `AddressLength = 20`; `type Hash [32]byte` (line 56), `type Address [20]byte` (line 222) — fixed-size arrays, value types, cheap to copy/compare.
- `common/types.go:261,270` — `Address.Hex()` / `checksumHex()`: EIP-55 mixed-case checksum encoding baked into the type's canonical string form.
- `common/bytes.go:29,40,132` — `FromHex`, `CopyBytes`, `TrimLeftZeroes` — the shared hex/bytes utility surface.
- `crypto/crypto.go:65` — `KeccakState` interface (`hash.Hash` + `Read`): exposes sha3's sponge `Read` so a 32-byte hash is extracted without a state copy; `HashData` (line 71) is the reuse-friendly hashing primitive.
- `crypto/keccak.go:40,54` — `Keccak256` / `Keccak256Hash` (default build); `crypto/keccak_ziren.go:93,120` — the same functions behind `//go:build ziren` (hardware-accelerated variant).
- `crypto/crypto.go:234,240,253` — `GenerateKey`, `ValidateSignatureValues` (enforces low-S / `s < secp256k1N`, `secp256k1halfN` at line 48), `PubkeyToAddress`.
- `crypto/signature_cgo.go:32,53` — `Ecrecover`/`Sign` delegating to the cgo `crypto/secp256k1` (Bitcoin Core's libsecp256k1); `crypto/signature_nocgo.go` is the `!cgo` twin using pure-Go `github.com/decred/dcrd/dcrec/secp256k1/v4`.
- `crypto/bn256/bn256_fast.go:12` (`gnark`, amd64/arm64) vs `bn256_slow.go:11` (`google`, other arches) — alt-bn128 pairing precompile backend switched by CPU arch build tag.
- `crypto/kzg4844/kzg4844.go:60,73,86` — `Blob [131072]byte`, `Commitment [48]byte`, `Proof [48]byte`; runtime backend switch via `useCKZG atomic.Bool` (line 105) between `ckzg` (cgo) and `gokzg` (pure-Go) — EIP-4844 blob crypto.
- BLS12-381: no in-tree package — `core/vm/contracts.go:30` imports `github.com/consensys/gnark-crypto/ecc/bls12-381` directly for the EIP-2537 precompiles (addresses `0x0b`–`0x10`).

## Design decisions & rationale
- **Reflection first, codegen for hot types.** RLP defaults to a reflection-driven, per-type-cached codec (`typecache.go:74`) so any struct is serializable with zero boilerplate; the hottest consensus types (Header, Log, StateAccount, Withdrawal) additionally get `rlpgen`-generated reflection-free codecs. This trades a small maintenance cost (regenerate on struct change) for large decode-throughput wins on the block-processing path.
- **Struct tags encode wire-format evolution.** `rlp:"optional"` and `rlp:"tail"` (`rlpstruct.go:72,76`) let a struct add trailing fields over time while staying backward/forward compatible, with `ProcessFields` (line 104) statically rejecting an unsound ordering (required-after-optional). This is how geth versions block/tx structures across forks without bespoke decoders.
- **Compile-time backend duality for crypto.** secp256k1 (`signature_cgo.go` / `signature_nocgo.go`), keccak (`keccak.go` / `keccak_ziren.go`), bn256 (`bn256_fast.go` / `bn256_slow.go`) each pick a backend via build tags; kzg4844 additionally switches at *runtime* (`useCKZG`). The cgo path (libsecp256k1, c-kzg) is the default for production speed; the pure-Go path guarantees the client still builds/runs on WASM, `nacl`, tinygo, and cgo-disabled toolchains.
- **Fixed-size array value types.** `Hash`/`Address` as `[N]byte` (not slices) makes them comparable (`==`, map keys), copy-by-value, and allocation-free to pass around — cheap identity semantics for the most-passed values in the system.
- **EIP-55 checksum is intrinsic.** `Address.Hex()` always emits the checksummed form (`types.go:261`), so the canonical display of an address carries integrity in the type itself.

## Notable patterns (the reusable idea)
**Dual-backend primitive with a single call site (build-tag or runtime switch).** Each crypto primitive presents one API and hides two implementations — fast native (cgo/asm/arch-specific) as default, portable pure-language as fallback — chosen by build tag (secp256k1, keccak, bn256) or an atomic runtime flag (kzg4844). Callers never branch. Use-case fitness: **DEFAULT for enterprise / multi-network** single-binary distribution (one source tree cross-compiles to every target, including cgo-less environments) and **mining-pool/validator/archival-RPC** where the native backend's throughput matters on the hot signing/hashing/pairing path. The **runtime** switch (kzg's `useCKZG`) is specifically valuable for **CEX/custody and validators** that want to A/B or fall back a crypto backend without a rebuild.

Second transferable idea: **reflection-by-default + codegen-for-hot-types RLP.** DEFAULT reflection keeps every peripheral type trivially serializable; OPTIONAL generated codecs are reserved for the handful of consensus types on the block-processing critical path — good for **archival/RPC and validator** roles that decode millions of headers/receipts.

## Authority note
go-ethereum is the **reference implementation for Ethereum (ETH/PoS) RLP and the ECDSA/keccak/pairing/KZG primitives** — RLP semantics, EIP-55 checksums, EIP-2537 BLS12-381 precompiles, and EIP-4844 KZG all trace to geth's behavior. For fukuii's **PoW/ETC** paths the consensus authority is core-geth (Ethash/ETChash), but for the *primitives* layer specifically — RLP wire format, keccak256, secp256k1 low-S signature validation, and blob KZG — geth remains the byte-level reference for both families, since these are chain-agnostic Ethereum primitives shared by ETC and ETH alike.

## Gotchas / anti-patterns / things they later changed
- **cgo secp256k1 is vendored C, not pure-Go.** The default `crypto/secp256k1` wraps Bitcoin Core's libsecp256k1 (`crypto/secp256k1/libsecp256k1`) via cgo; the pure-Go fallback switched to Decred's `dcrd/dcrec/secp256k1/v4` (`signature_nocgo.go:28`), replacing geth's older bespoke pure-Go impl. Any port must decide which is the source-of-truth for signature edge cases — they must agree bit-for-bit. `ScalarMult` is *unavailable* in the nocgo build (`crypto/secp256k1/scalar_mult_nocgo.go:13` panics).
- **bn256 backend migrated to gnark.** The fast path moved from the old `cloudflare` backend to `github.com/ethereum/go-ethereum/crypto/bn256/gnark` (`bn256_fast.go:12`); the `google` backend (`bn256_slow.go:11`) is the non-amd64/arm64 fallback. Don't assume "bn256" means one implementation.
- **BLS12-381 has no in-tree package anymore.** It's consumed directly from `consensys/gnark-crypto` at the precompile site (`core/vm/contracts.go:30`), so there is no `crypto/bls12381/` to mirror — a Scala port maps to a library dependency, not a translated package.
- **RLP `Stream` needs an explicit input limit or it can panic on huge sizes.** `Decode` does not set a limit for arbitrary readers (`decode.go` doc comment above line 82); use `NewStream(r, inputLimit)` (line 616) on untrusted network input.
- **`rlpgen` output must be regenerated on struct change.** The generated `gen_*_rlp.go` files are checked in; editing a struct's fields without re-running `//go:generate` silently desyncs the fast codec from the type.
- **Signed integers and floats are unencodable in RLP by design** (`rlp/doc.go`) — encoding an `int`/`float`/map returns an error, a common surprise when reusing the encoder for non-consensus data.
- **`ValidateSignatureValues` enforces low-S (EIP-2 homestead) via `secp256k1halfN`** (`crypto.go:48,240`) — a port that omits the half-N check will accept malleable signatures ETH/ETC reject.
