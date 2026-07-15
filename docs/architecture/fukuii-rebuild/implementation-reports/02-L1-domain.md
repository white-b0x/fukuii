# L1 — domain: `domain`

_Layer L1 (pure value types), depends down-only on `bytes`, `crypto`, `rlp`, `common`. The first layer
that composes the L0 leaves into ecosystem consensus objects. Forward-looking plan:
[`plan/L1.md`](../plan/L1.md); per-item byte-cited RX evidence: [`plan/rx/L1.md`](../plan/rx/L1.md). Byte behavior
matched against go-ethereum `core/types/` (shared/ETH-family) and core-geth `core/types/` (ETC-frozen),
with besu / besu-etc as the JVM-implementation lens. Built in five phases, each forge (ETC) / beacon (ETH)
consensus-validated and eye-tested; the one gate-discovery (H-1, block-gated homestead) is recorded in
§Signing & recovery._

## Scope

`domain` models the immutable value types every state root, transaction, and block depends on — and the
pure signing/recovery gate the layers above call down into. It defines the *values*; access to *stored*
values (`Blockchain`/`BlockchainReader`) relocates to L7 `chain` (the primary SCC-breaking move — see
Layer boundaries). It holds **no** custody/unlock/expiry state: the signing operation is a pure function,
so an L8 `ISigner` (keystore / clef / HSM / validator-remote) can implement it without the key ever
coupling to the operation.

What L1 builds:

- **Account state** — `Account(nonce, balance: Wei, storageRoot, codeHash)`, `derives RLPCodec`.
- **Semantic value wrappers** — `opaque type Wei = UInt256`, `opaque type ChainId = BigInt` (EIP-155,
  opaque *data* not a type-level fork).
- **Transaction** — a Scala 3 `enum` of the ecosystem's EIP-2718 typed envelopes: `Legacy` / `AccessList`
  (2930) / `DynamicFee` (1559) / `Blob` (4844) / `SetCode` (7702), plus `AccessListEntry`,
  `SetCodeAuthorization`, `BlobSidecar`.
- **Signing & recovery** — per-variant sighash, EIP-155 sender recovery, the N-1 `ValidateSignatureValues`
  gate, `tx.hash`, and the 7702 inner-authorization recovery (`SenderRecovery`).
- **Block** — fork-variant `BlockHeader` (open trailing-optional tail), `BlockBody`, `Block`, `Withdrawal`.
- **Receipt / logs** — `Receipt` (status-vs-postState union, typed prefix), `Log`, `Bloom`.

**Not redefined here:** `Address`/`Hash`/`UInt256` are L0 `bytes`; `ECDSASignature`, sender-recovery math,
`kec256`/`sha256`, `constantTimeEquals` are L0 `crypto`; the RLP engine + `derives RLPCodec` +
`PrefixedRLPEncodable` are L0 `rlp`. `domain` *composes* these, never re-implements them.

## Design decisions & empirical logic

### 1. `enum Transaction` — per-variant cases, illegal combinations unrepresentable

The typed-tx envelope is a Scala 3 `enum` with one case per EIP-2718 type, each carrying exactly the fields
its go-ethereum `core/types/tx_*.go` struct declares, in RLP field order. The type id is the `txType`
method, not a stored discriminator.

**Empirical logic:** this replaces besu's `Transaction.java` optionals-on-one-class shape (11 `Optional<>`
fields) — "1559 fields on a legacy tx" becomes unrepresentable, and each variant has a single explicit
`given RLPCodec` arm. The admissible type set is exactly `{0x00–0x04}`; **no `DepositTx` (0x7E)** — it is
OP-stack-only, absent from both core-geth (the ETC authority) and go-ethereum mainline (RX-L1-07). A future
OP-stack `NetworkFamily` would add 0x7E as *that family's* variant, never the base enum.

**EIP-2718 dispatch — the `≥0xc0` boundary.** `Transaction.decode(bytes)` treats a first byte **≥ 0xc0** as
a legacy RLP list, `0x01–0x04` as typed; the `0x05..0xbf` gap and any unknown byte are **rejected**, never
silently treated as legacy. This mirrors besu `transactionTypeByOpaqueByte` (`Optional.empty` = reject) and
go-ethereum `decodeTyped` (`default → ErrTxTypeNotSupported`). Treating the gap as legacy would decode a
malformed/future type byte as a well-formed legacy list — a wrong-tx-hash consensus split. beacon: strictly
safer than geth's `UnmarshalBinary` (which uses `b[0] > 0x7f` then relies on a failing decode).

### 2. Blob (EIP-4844) two-form — two named codecs, wrapper out of implicit scope

`Transaction.Blob` carries a `sidecar: Option[BlobSidecar]` (geth's `Sidecar *BlobTxSidecar rlp:"-"`,
excluded from the consensus RLP by construction). Two **explicitly named** codecs express the two forms
(nethermind two-form discipline, never one flag-conditional codec):

- `blobConsensusCodec` — the **default** `given` (no sidecar). `tx.hash`, the tx-trie, and
  `Transaction.decode` all resolve to it.
- `BlobNetworkWrapper.blobNetworkWrapperCodec` — the network wrapper (consensus body + `[blobs,
  commitments, proofs]`), deliberately in a **nested object, out of default implicit scope**, so it can
  *never* be summoned for hashing.

`versionedHash = 0x01 ‖ sha256(commitment)[1:]` (fixed KZG version byte `0x01`) — distinct from the
sidecar's own `version` field (0/1 proof format). Keeping the wrapper out of implicit scope is stronger
than a flag: hashing the wrapper form is a consensus split (§9), and this makes it structurally
impossible.

### 3. Signing & recovery — the N-1 gate, and H-1 (block-gated homestead)

`SenderRecovery` provides per-variant sighash (Legacy pre-155 6-element / EIP-155 9-element / typed
`typeByte ‖ RLP`), EIP-155 v-unwind (`deriveChainId`), and a pure `getSender: Either[SigError, Address]`
that runs `ValidateSignatureValues` **before** recovery (evaluation order: high-S branch before full-N
range — byte-exact to core-geth `crypto.go`). `tx.hash = keccak(consensus encoding)` (Blob resolves to the
consensus codec, never the wrapper).

**H-1 — the homestead flag is BLOCK-GATED, not hard-coded (gate discovery, 2026-07-14).** The RX plan
originally proposed hard-coding `s ≤ N/2`. forge's impact analysis caught that this is **unsafe for
from-genesis sync**: ETC and ETH share pre-DAO history, so blocks **0–1,149,999** are Frontier-era on both
chains where high-S is legal (`homestead=false`). A from-genesis node re-executing that range with a
hard-coded `s ≤ N/2` fails to reconstruct the canonical chain (an F-BN-1-class sync-halt). All three
reference clients block-gate the signer — go-ethereum/core-geth `MakeSigner` (Frontier/Homestead/EIP155 by
block number) and besu's per-fork `checkSignatureMalleability` flag on the `ProtocolSpec`. So `getSender`
takes a **plumbed `homestead` flag** from block context (the Legacy path; typed variants force `true`); a
from-genesis node passes `homestead=false` for the pre-1.15M range. The `r,s ≥ 1` / `r,s < N` / `v ∈ {0,1}`
checks are unconditional. See [`plan/L1.md`](../plan/L1.md) §7 for the binding rule.

**7702 inner authority — a second, independent recovery surface.** `SetCodeAuthorization.authority`
recovers the authorizing account from `keccak(0x05 ‖ RLP[chainId, address, nonce])` with **homestead always
true** and magic byte **`0x05`** (the `SetCodeMagicByte`, distinct from the tx-type `0x04`). It reads only
the authorization tuple, never the outer tx signature — a genuinely independent surface (beacon co-signed).

### 4. Fork-variant `BlockHeader` — an open trailing-optional tail with no fixed max

The header is the 15 fixed fields (parentHash…nonce) plus a hand-written codec for the **8** trailing
`rlp:"optional"` fields in geth's exact order (baseFee / withdrawalsRoot / blobGasUsed / excessBlobGas /
parentBeaconRoot / requestsHash / blockAccessListHash / slotNumber).

**Empirical logic:** the ETH tail is **open-ended** — Osaka+ forks keep appending — so the codec is
**list-length-driven with no fixed-arity match**: decode splits at field 15 and reads trailing items
positionally, so a future 9th field decodes without crashing (a hardcoded stop would truncate every future
header). Encode enforces the `rlp:"optional"` contract — **a mid-run gap is rejected** (`excessBlobGas`
without `blobGasUsed` is unrepresentable/thrown). The layout is **network-neutral and positive-keyed**
(`with*` factories set one slot each, the besu `BlockHeaderBuilder` immutable analog): **ETC pre-Olympia =
all-None = bare 15**, and ETC-Olympia (ECIP-1111) is expressible as **baseFee-only** without pulling the ETH
Shanghai/Cancun tail — closing the `Eth*`-field-on-`Etc*`-header trap by construction, not a runtime check.
A golden Cancun block vector (`ethereum/tests/BlockchainTests/.../basefeeExample.json`) re-hashes byte-exact
to go-ethereum's published hash.

### 5. Block/Body and typed-tx string-wrapping (consensus-load-bearing)

`Block` is the **flat `extblock`** `[header, txs, uncles, withdrawals?]` (header prepended, body fields
inlined — byte-exact to core-geth, *not* nested `[header, body]`). `BlockBody` carries a trailing-optional
`withdrawals` (EIP-4895, omitted pre-Shanghai / all ETC; empty-vs-absent are distinct).

Inside a body's tx-list, a **typed tx is RLP string-wrapped** (`typeByte ‖ RLP` re-wrapped as an RLP byte
string), matching go-ethereum's `[]*Transaction` `EncodeRLP` — a raw `PrefixedRLPEncodable` would mis-parse
because a type byte `< 0x80` self-encodes as a stray single-byte item. Since this list feeds
`transactionsRoot → block hash`, the string-wrap is Tier-A consensus (forge co-signed).

### 6. `Receipt` — the status-vs-postState union

`ReceiptStatus` models both encodings: **`PostStateRoot(Hash)`** (32-byte, pre-Byzantium ETH /
pre-Atlantis ETC) and **`Status(Boolean)`** (1-byte, post-fork). Byte-exact to geth `statusEncoding`/
`setStatus`: `Status(true) → {0x01}`, `Status(false) → {}` (empty string), root → raw 32 bytes; decode is
disjoint (`{0x01}`/empty/len-32/else-reject). The consensus `receiptRLP` `[postStateOrStatus,
cumulativeGasUsed, bloom, logs]` **includes** Bloom (the bloom-less `storedReceiptRLP` is an L2/db concern).
Typed receipts use `typeByte ‖ RLP`. Which fork switches (ETC Atlantis `EIP658FBlock`=8,772,000 / ETH
Byzantium) is an L4/L5 selection; L1 models both forms.

## Improvements over old fukuii

| Old fukuii (AS-IS) | Rebuild L1 `domain` | Why it matters |
|---|---|---|
| `domain` imported **up** into db/mpt/vm/ledger/jsonrpc/network — the 13-package SCC; `Blockchain*` facades lived here | pure value-type leaf; facades relocate to L7 `chain` | Structurally dissolves the largest cycle — enforced by the module boundary |
| One `Transaction` shape with conditional fields | `enum Transaction` — illegal field combos unrepresentable | Exhaustiveness; no "1559 fields on a legacy tx" |
| Low-S / range gate not enforced at the tx type | `getSender` runs `ValidateSignatureValues` before recovery | Closes a malleability / invalid-sig acceptance gap |
| Fork-variant header via ad-hoc extra-fields coupling; `Eth*`-on-`Etc*` risk | network-neutral positive-keyed open-tail codec; ETC = 0 trailing | Removes the wrong-network-field trap at the header level |
| Signing coupled to `Wallet.signTx`; no read/write signer seam | pure `(tx) → sender` function an L8 `ISigner` calls | clef/HSM/validator-remote become additive L8 seams |
| Blob one-codec risk (sidecar-inclusion by flag) | two named codecs; wrapper out of implicit scope | Hashing the wrapper (a consensus split) is structurally impossible |

## Layer boundaries (what lives elsewhere, and why)

_Durable placement decisions — not build-status (see the README index for what's committed)._

- **`Blockchain`/`BlockchainReader` facades** → **L7 `chain`** (they read stored blocks/state — an upward
  dependency that re-forms the SCC). `domain` defines values; `chain` defines access to stored values.
- **Keystore, `Wallet`, `personal_*` unlock, the `ISigner`/`ISignerStore` seam, custody backends
  (clef/HSM/validator-remote), keystore hardening** → **L8 `keystore` / L9 `rpc`**. L1 owns only the pure
  signing operation + recovery; it holds no unlock/custody/expiry state (the OBSOLETE in-node-unlock model
  stays out by construction).
- **Which fork a network activates** (EIP-155/2930/1559/4844/7702 admissibility, ETC Atlantis / ETH
  Byzantium receipt switch, the ETH open-tail occupancy, EIP-155 network-chainId admissibility) → **L4/L5**.
  `domain` carries the *shapes* (typed envelopes, fork-variant header fields, both receipt forms); the
  *rules over them* live at execution/consensus.
- **Gas-metered / opcode `UInt256` semantics, tx gas validation, uint64 bounds checks** → **L3 `evm` /
  L4 `execution`**.
- **Blob sidecar KZG verification, the `0x0a` point-eval precompile** → **L0 `crypto` (primitive)** + **L3
  `evm` (precompile)**. L1 models the wrapper encoding only.
- **The `constantTimeEquals` primitive** → **L0 `crypto`**. L1/L8/L9 *call* it; L1 does not define it.
- **Runtime family selection** (`NetworkFamily`) → **L5**; reth's compile-time `NodeTypes` monomorphization
  is **ruled out** for R2 — `domain` types stay plain values, not `NodeTypes`-bound type parameters.

## Verification

`sbt "domain/Test/testOnly *"` — **94 tests green** across the layer (`Wei`/`ChainId`/`Account`/`Log`/
`Bloom`, `Transaction`+dispatch, `SenderRecovery`, `BlockHeader`/`BlockBody`/`Block`/`Receipt`), format gate
(`scalafmtCheck`) clean. Byte-exact reference vectors from `ethereum/tests`: Legacy EIP-155
(`ttSignature`), AccessList (`ttEIP2930`), DynamicFee (`ttEIP1559`), Blob consensus (`bcEIP4844-
blobtransactions`), the golden Cancun block (`bcExample/basefeeExample.json` → go-ethereum's
`0x3f820e96…9ec43`), plus per-chainId sender-recovery, the H-1 full-N accept/reject flip, the N-1 fail-set,
and the 7702 authority KAT. Consensus surfaces co-signed byte-exact by forge (ETC, vs core-geth + besu-etc)
and beacon (ETH, vs go-ethereum + besu). The `enum Transaction` is the core-domain-sweep vocabulary (every
layer above consumes it).
