# erigon — primitives
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon's low-level primitives descend genealogically from go-ethereum but have been
relocated and progressively rewritten under Erigon's own module tree. The historical
`erigon-lib/` package (a "clean-room-ish" low-level library) has, as of this commit, been
folded back into the main repo: the fixed types, byte helpers, and math live at repo-root
`common/`, crypto at `common/crypto/`, RLP at `execution/rlp/`, and the transaction/header
primitives at `execution/types/`. Almost every source file carries a dual copyright header
— "Copyright 2014 The go-ethereum Authors (original work) / Copyright 2024 The Erigon
Authors (modifications)" (e.g. `execution/rlp/doc.go:1`) — which is the honest signal:
the *shape* of the API is geth's, but the hot-path internals have been re-tuned for
Erigon's archival / high-throughput use-case.

The recurring theme across primitives is **allocation reduction**: `sync.Pool`-backed
scratch buffers, a swapped-in optimized keccak, zero-copy `[]byte`↔`string` conversion via
`unsafe`, atomic hash caching on transactions, and a dedicated RLP benchmark harness that
exists solely to measure allocs on the header decode path. This is a perf-focused fork of
geth's primitive layer, not a re-invention of the wire formats (which must stay consensus-
identical).

## Key types / interfaces / files

### Fixed types & byte helpers (`common/`)
- `common/hash.go:37` — `type Hash [length.Hash]byte` (32-byte fixed array). Length pulled
  from the centralized `common/length` package rather than a local literal.
- `common/address.go:33` — `type Address [length.Addr]byte` (20-byte fixed array).
- `common/length/length.go:19` — single package of length constants (`Hash=32`, `Addr=20`,
  `Bytes48`/`Bytes64`/`Bytes96` for BLS/sync-committee, `Incarnation=8`). Geth scatters
  these; Erigon centralizes them (note the beacon-chain-specific lengths — Erigon embeds a
  CL, `cl/`, so its primitives carry consensus-layer sizes too).
- `common/bytes.go:144,153` — `ToStringZeroCopy` / `ToBytesZeroCopy`: `unsafe.Slice` /
  `unsafe.StringData` zero-copy conversions. A deliberate perf/GC divergence from safe
  copying.
- `common/bytes.go` also holds the usual `RightPadBytes`/`LeftPadBytes`/`TrimLeftZeroes`
  family (geth-lineage).
- `common/u256/big.go:26` — pre-allocated cached `uint256.Int` constants (`N0,N1,…N100`)
  to avoid re-allocating common small integers.

### 256-bit integers
- Erigon uses `github.com/holiman/uint256` as the pervasive 256-bit type across primitives
  (imported in `common/crypto/crypto.go:35`, `common/u256/big.go`, RLP encoder). RLP treats
  both `*big.Int` and `*uint256.Int` as integers (`execution/rlp/doc.go`, Decoding Rules).

### RLP (`execution/rlp/`) — geth-derived, perf-hardened
- `execution/rlp/doc.go` — package doc is verbatim geth semantics (type tags, struct tags
  `tail`/`optional`/`nil`/`nilList`/`nilString`, reflection encode/decode). Erigon did
  **not** write a semantically different RLP; core types still hand-roll `EncodeRLP`/
  `DecodeRLP` and reflection is used only for simpler P2P packet structs.
- `execution/rlp/encbuffer.go:30` — `type encBuffer` scratch buffer; `:39` global
  `encBufferPool sync.Pool`; `getEncBuffer()` resets-and-reuses (`:44`), returned via
  `Put` (`:219,:334`). Pooled encoder output is the primary alloc win.
- `execution/rlp/PERFBENCH.md` + `execution/rlp/baseline.txt` + `streambench_test.go` +
  `execution/types/headerbench_test.go` — a **dedicated, committed benchmark/escape-analysis
  harness** for the RLP layer and its #1 hot consumer (`Header` decode/encode/hash), with
  captured real mainnet header RLP (pre-London epoch 99 and post-London epoch 1894). Built
  because heap profiling flagged `rlp.(*Stream).Decode` at 29% of alloc *count* and the
  `ForEachHeader` loop's escaping `var header types.Header` at ~52% of alloc *bytes*.
- `execution/rlp/internal/rlpstruct` — struct-field processing extracted into an internal
  subpackage (mirrors geth's later refactor).

### Crypto (`common/crypto/`)
- `common/crypto/crypto.go:76,89` — `Keccak256` / `Keccak256Hash` built on a pooled
  `KeccakState`.
- `common/crypto/crypto.go:34,307,314` — **the key divergence**: the hasher pool's `New`
  returns `keccak.NewFastKeccak()` from `github.com/erigontech/fastkeccak` (imported as
  `keccak`), *not* geth's `golang.org/x/crypto/sha3.NewLegacyKeccak256`. Erigon maintains
  its own optimized keccak. (`golang.org/x/crypto/sha3` is still imported at `:37` but only
  for the rarely-used `Keccak512`.)
- `common/hasher.go:22,29` — a second pooled hasher (`common.Hasher` wrapping
  `fastkeccak.NewFastKeccak()`), the general-purpose `NewHasher()`/`ReturnHasherToPool`/
  `HashData` path. Another Erigon-authored (2024) file.
- secp256k1 dual build (same split geth uses, but under Erigon's own fork of the cgo
  binding):
  - `common/crypto/signature_cgo.go` — build tag `!nacl && !js && cgo && !gofuzz`; uses
    `github.com/erigontech/secp256k1` (Erigon's fork of libsecp256k1 cgo bindings). Exposes
    `EcrecoverWithContext` for reusing a `secp256k1.Context` — a perf hook geth lacks.
  - `common/crypto/signature_nocgo.go` — build tag `nacl || js || !cgo || gofuzz`; pure-Go
    fallback via `github.com/decred/dcrd/dcrec/secp256k1/v4`. Note `:123` — it must add an
    explicit malleability (low-S) rejection because "libsecp256k1 does this check but decred
    doesn't."
- `common/crypto/kzg/kzg.go:28,73,81` — EIP-4844 blob KZG via
  `github.com/crate-crypto/go-eth-kzg` (pure-Go), lazy trusted-setup init
  (`NewContext4096Secure` / from JSON file). `go_eth_kzg.go:23` adds `VerifyCellProofBatch`
  for the newer cell-proof (PeerDAS-era) path.
- Other subpackages: `common/crypto/blake2b` (EIP-152 precompile), `common/crypto/bn254`
  (alt-bn128 pairing precompiles), `common/crypto/secp256r1` (EIP-7212 P-256), `ecies`.

### Transaction / header primitives (`execution/types/`)
- `execution/types/transaction.go:60` — `type Transaction interface`; `:75`
  `SigningHash(chainID *uint256.Int)`, `:82,83` `EncodeRLP`/`DecodeRLP(s *rlp.Stream)`.
- `execution/types/transaction.go:104` — `hash atomic.Pointer[common.Hash]` — tx hash is
  computed once and cached in a lock-free `sync/atomic.Pointer`; `legacy_tx.go:352` shows
  the `Load()`-check / compute / `Store()` pattern. Avoids re-keccak of the same tx.
- `execution/types/transaction.go:138` — `DecodeRLPTransaction(s, blobTxnsAreWrappedWithBlobs)`
  central typed-tx dispatch (legacy / access-list / dynamic-fee / blob / set-code / AA).
  Tx-type files: `legacy_tx.go`, `access_list_tx.go`, `dynamic_fee_tx.go`, `blob_tx.go`
  (+ `blob_tx_wrapper.go`), `set_code_tx.go` (EIP-7702), `aa_transaction.go` (account
  abstraction).
- `execution/types/hashing.go:35` — `encodeBufferPool sync.Pool` of `bytes.Buffer`;
  `:174,195,208` `rlpHash`/`prefixedRlpHash` pull a pooled `crypto.NewKeccakState()`;
  `DeriveSha` builds the derivable-list (receipts/tx) root via `trie.NewHashBuilder`.

## Design decisions & rationale

- **Keep geth's wire semantics, swap the engine underneath.** RLP grammar, struct tags, and
  the `Transaction`/`Encoder`/`Decoder` interfaces are geth-identical (they must be, for
  consensus), but the buffers, hasher, and secp256k1 binding are Erigon-owned and tuned.
- **Pool everything on the hot path.** `encBufferPool` (RLP), `hasherPool` /
  `hashersPool` (keccak), `encodeBufferPool` (hashing). Erigon's staged sync re-serializes
  and re-hashes enormous volumes of headers/txs/receipts during archival import, so
  per-op allocations dominate; pooling is the lever.
- **Own the keccak.** `github.com/erigontech/fastkeccak` replaces `x/crypto/sha3` for the
  256-bit path — keccak is the single most-called primitive in an EVM client, so an
  optimized implementation compounds across the whole workload.
- **Own the secp256k1 cgo binding** (`erigontech/secp256k1`) and expose context reuse
  (`EcrecoverWithContext`) for batch signature recovery, with a pure-Go decred fallback for
  cgo-less/wasm builds.
- **Cache derived values.** Atomic tx-hash caching and `common/u256` cached small-int
  constants remove repeated recomputation/allocation.
- **Centralize sizes** (`common/length`) so the same constants serve EL primitives and the
  embedded CL (`cl/`) — a consequence of Erigon being a combined EL+CL client, unlike geth.

## Notable patterns (the reusable idea)

1. **Committed, profile-driven micro-benchmark harness for the serialization hot path**
   (`execution/rlp/PERFBENCH.md` + `baseline.txt` + real captured mainnet header RLP). The
   harness isolates RLP+Header decode/encode/hash so any alloc-reduction change gets a
   stable before/after `benchstat` number, sourced from an actual heap profile that named
   the offending call sites. This is the single most transferable practice: primitives
   optimization is grounded in a reproducible allocation benchmark, not intuition.
2. **`sync.Pool` scratch buffers keyed to the operation** (encode buffer, keccak state,
   hashing buffer) with a strict get-reset / put-back discipline.
3. **Lock-free memoization of an expensive pure function** via `atomic.Pointer[T]` (the tx
   hash), safe under concurrent reads without a mutex.
4. **Pluggable native/pure-Go crypto via build tags** so the fast cgo path is default but a
   portable fallback always compiles — with the fallback explicitly re-adding safety checks
   the native lib does for free (low-S malleability).

## Authority note

For consensus-observable primitive *behavior* (RLP grammar, keccak output, secp256k1
recovery, KZG verification, tx/header hashing), **go-ethereum remains the canonical
reference** — Erigon deliberately preserves geth's semantics and even its package docs.
Erigon is the **perf-oriented variant**: same outputs, re-tuned internals. What was
`erigon-lib` (now merged into `common/` + `execution/`) is the clean low-level library that
carries these optimizations — treat it as "how to make geth-equivalent primitives faster,"
not as an independent source of truth for what the primitives should compute. For a fukuii
diff, mine Erigon for *techniques* (pooling, fastkeccak, atomic caching, the benchmark
harness) and cross-check *values* against go-ethereum (and, for ETC/PoW specifics,
core-geth).

## Gotchas / anti-patterns / things they later changed

- **`unsafe` zero-copy conversions** (`common/bytes.go:144,153`) alias the backing store:
  a "string" produced by `ToStringZeroCopy` is only safe while the underlying `[]byte` is
  not mutated. A safety-for-speed trade fukuii should not port blindly onto the JVM (where
  the equivalent is a different set of tricks entirely).
- **`erigon-lib` moved.** Older docs/imports referencing `erigon-lib/common`,
  `erigon-lib/rlp`, `erigon-lib/crypto` are stale at this commit — the code now lives at
  repo-root `common/`, `execution/rlp/`, `common/crypto/`. Chase the current paths.
- **Erigon-forked dependencies** (`erigontech/fastkeccak`, `erigontech/secp256k1`) are not
  upstream geth deps — a supply-chain note if fukuii ever vendored Erigon primitives: you'd
  be pulling Erigon-maintained crypto forks, not the community libs.
- **The nocgo path is not the same code as cgo** and had to independently re-implement the
  low-S malleability rejection (`signature_nocgo.go:123`). Divergent crypto paths behind a
  build tag are a correctness risk surface — the two must be kept output-identical, which is
  exactly why the malleability check is called out in a comment.
- **The header decode allocation problem is documented as still-relevant** in `PERFBENCH.md`
  (the escaping `var header types.Header` in `ForEachHeader` is the biggest byte allocator on
  the profiled workload) — i.e. this is an actively-worked, not-yet-fully-solved area, which
  is itself the useful signal: even a perf-focused client still leaks allocations on the
  primitive hot path.
