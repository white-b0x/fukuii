# `modules/domain` — L1 subsystem breadcrumb

_Pure value-type layer. Depends **down-only** on `bytes`, `crypto`, `rlp`, `common` — an upward
`.dependsOn` is a compile error. Full record: [`docs/architecture/fukuii-rebuild/04-L1-domain.md`](../../docs/architecture/fukuii-rebuild/04-L1-domain.md);
plan: [`plan/L1.md`](../../docs/architecture/fukuii-rebuild/plan/L1.md); byte-cited RX evidence:
[`plan/rx/L1.md`](../../docs/architecture/fukuii-rebuild/plan/rx/L1.md). Read the record before structural
changes here._

## What lives here

Ecosystem consensus **value types** composed from the L0 leaves: `Account`, opaque `Wei`/`ChainId`,
`enum Transaction` (Legacy/AccessList/DynamicFee/Blob/SetCode) + `AccessListEntry`/`SetCodeAuthorization`/
`BlobSidecar`, `SenderRecovery` (signing/recovery gate), fork-variant `BlockHeader`/`BlockBody`/`Block`/
`Withdrawal`, `Receipt`/`Log`/`Bloom`.

## Invariants (do not break without forge/beacon)

- **Consensus-critical, dual-family.** Tx RLP layout, sender recovery, header/receipt encoding feed the
  tx-trie root and block hash. Any change is a one-way door — route through the Consensus-Critical Change
  Protocol (forge for ETC, beacon for ETH) *before* editing. Byte-exact to core-geth (ETC) + go-ethereum
  (shared/ETH) + besu/besu-etc (JVM lens).
- **EIP-2718 dispatch boundary is `≥ 0xc0` ⇒ legacy** (not `> 0x04`); the `0x05..0xbf` gap + unknown bytes
  are **rejected**, never silent-legacy (a wrong-tx-hash split).
- **Blob two-form:** `blobConsensusCodec` (default `given`, no sidecar) is what `tx.hash`/tx-trie use; the
  network wrapper is nested in `BlobNetworkWrapper`, **deliberately out of default implicit scope** — do
  not add a second top-level `given RLPCodec[Blob]` (it would let the wrapper be hashed = consensus split).
- **H-1: the `homestead` flag is BLOCK-GATED, plumbed into `getSender`, never hard-coded** — a from-genesis
  node accepts full-N `s` for blocks < 1,150,000 (ETC & ETH share pre-DAO Frontier history). Typed variants
  force `homestead=true`. The N-1 `ValidateSignatureValues` gate runs **before** recovery, high-S branch
  before full-N range.
- **7702 authority** is an independent second recovery surface: magic byte **`0x05`** (not tx-type `0x04`),
  `homestead` always true.
- **`BlockHeader` open tail:** the trailing-optional codec is **list-length-driven with no fixed max**
  (Osaka+ forks append) and rejects a mid-run gap; the type is **network-neutral** (ETC = all-None = bare
  15, Olympia = baseFee-only). Never add a network symbol to the header type (R1).
- **Typed txs in a body tx-list are RLP string-wrapped** (`typeByte ‖ RLP` as a byte string, geth
  `[]*Transaction`), not raw-prefixed — feeds `transactionsRoot`.
- **`Receipt`** models both `PostStateRoot` (pre-Atlantis ETC / pre-Byzantium ETH) and `Status`; which fork
  switches is L4/L5, not here.

## Boundaries

`Blockchain`/`BlockchainReader` → L7; keystore/signer-seam/custody → L8/L9; fork *admissibility* + gas
semantics → L3/L4/L5; `constantTimeEquals` + recovery math → L0. `domain` models shapes; the rules over
them live at their execution/consensus layer (see the record's Layer boundaries).

## Follow-up (logged, non-blocking)

- Optional idiom cleanup: hand-written codecs use `summon[RLPCodec[T]].encode/decode`; L0 exposes the
  shorter `RLPEncoder.encode`/`RLPDecoder.decode` free functions. Byte-identical; consensus-gated to change
  (dozens of sites across 7 co-signed files) — an optional sweep, not a defect.
