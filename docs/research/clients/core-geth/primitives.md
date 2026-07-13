# core-geth — primitives
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary
Fork of go-ethereum. The primitives slot — `rlp/`, `common/`, `crypto/` —
**inherits geth unchanged**. ETC has no primitive-level divergence from ETH:
RLP encoding, `common.Hash`/`common.Address`, Keccak-256, secp256k1 ECDSA, and
address derivation are ecosystem-shared invariants, and core-geth carries geth's
implementations verbatim. A repo-wide grep for `etchash|ecip1099|ecip1017|classic`
across `crypto/`, `rlp/`, and `common/` returns **zero hits**. The ETC-specific
crypto that people expect to find here — ETChash / ECIP-1099 (DAG-size epoch
change) — does **not** live in the primitives layer; it lives in the consensus
layer (`consensus/ethash/`). This is the correct place to record "core-geth does
NOT diverge," so fukuii knows these layers are geth-canonical.

## Key types / interfaces / files
- `crypto/crypto.go:39` — `const SignatureLength = 64 + 1` — stock geth ECDSA
  signature length. Unchanged.
- `crypto/crypto.go:85` — `func Keccak256(data ...[]byte) []byte` — the mining/
  hashing primitive; standard geth. **ETChash does not add a primitive helper
  here** — it reuses this Keccak-256 and changes only the DAG epoch schedule up
  in `consensus/ethash/`.
- `crypto/crypto.go:97` — `func Keccak256Hash(...) common.Hash` — stock.
- `crypto/crypto.go:116` / `:123` — `CreateAddress` / `CreateAddress2` (CREATE /
  CREATE2 address derivation) — stock geth; no ETC divergence.
- `crypto/secp256k1/`, `crypto/signature_cgo.go`, `crypto/signature_nocgo.go` —
  secp256k1 sign/recover, cgo and pure-Go paths — geth-canonical.
- `crypto/blake2b/`, `crypto/bn256/`, `crypto/bls12381/`, `crypto/kzg4844/`,
  `crypto/ecies/`, `crypto/signify/` — precompile/crypto support packages, all
  inherited from geth.
- `rlp/`, `common/` — RLP codec and shared value types; inherited from geth
  verbatim (no ETC references).

## Design decisions & rationale
- **Do not touch primitives.** RLP, hashing, and signing are the parts of the
  protocol ETH and ETC share by definition; diverging here would break wire and
  state compatibility with the shared toolchain. core-geth's divergence budget is
  spent entirely in consensus/rewards, never in primitives.
- **ETChash is a consensus concern, not a crypto-primitive concern.** ECIP-1099
  doubles the Ethash epoch length (changing DAG growth); that is a scheduling
  parameter consumed by the mining algorithm, expressed in
  `consensus/ethash/algorithm.go`, not a new hash function. So the primitives
  layer needs no change to support ETChash.

## Notable patterns (the reusable idea)
For fukuii: **the primitives layer is a hard "inherit, do not fork" boundary.**
Any ETC/ETH divergence that appears to be crypto (ETChash, Lyra2) is actually a
consensus-algorithm parameterization sitting above the primitives — the Keccak-256/
secp256k1/RLP substrate stays identical across both networks. fukuii's own
`bytes`/`crypto`/`rlp` modules should mirror this: treat them as ecosystem-shared,
and put network-specific behaviour in the consensus layer.

## Authority note
**Not authoritative — inherits geth.** Despite core-geth being *the* ETC
byte-authority for consensus, for the primitives slot it is a pure geth
passthrough. Where fukuii needs the canonical RLP/hash/signature behaviour, geth
(and core-geth, identically) is the reference; there is no ETC-specific primitive
to reconcile.

## Gotchas / anti-patterns / things they later changed
- **Don't go looking for ETChash in `crypto/`.** It is in `consensus/ethash/`
  (`algorithm.go`, `sealer.go`, `consensus.go`). Likewise the alt-PoW **Lyra2**
  used by some ETC-adjacent testnets is a cgo package at `consensus/lyra2/`
  (`#include "Lyra2.h"`), again consensus-layer, not primitives.
- **Vintage skew:** the KZG (`c-kzg-4844`), gnark, and BLS12-381 support here is
  2025-01 vintage — relevant only to ETH-side proto-danksharding, and behind
  current geth. Not an ETC concern, but don't mistake these pins for current.
- Because the module path is unrenamed (`github.com/ethereum/go-ethereum`), these
  primitive packages import-alias exactly as geth's — a rebase against upstream
  geth touches them for free, which is precisely why they were left untouched.
