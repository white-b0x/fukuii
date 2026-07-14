# reth — primitives
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth does **not define its own primitive types**. Bytes, addresses, hashes,
integers, RLP, tx/header/block/receipt structures, and even the low-level crypto
(hashing, secp256k1 recovery, KZG) are all supplied by the **alloy** crate
ecosystem — a set of independently-versioned, published Rust crates shared across
the whole Ethereum-Rust world (reth, foundry, alloy-based tooling). reth's own
"primitives" work is reduced to two things:

1. **Thin domain crate** `reth-ethereum-primitives` — mostly `type` aliases that
   name alloy types (`Block = alloy_consensus::Block<TransactionSigned>`), plus a
   handful of helper functions (receipt-root calculation) and the `EthPrimitives`
   marker struct that binds a concrete set of types together.
2. **Trait abstraction** `reth-primitives-traits` — the `NodePrimitives` trait
   family that lets the rest of reth be generic over *which* concrete primitive
   types a network uses (the compile-time "NodeTypes family" pattern).

Notably, at this commit both `reth-primitives-traits` and `reth-codecs` have been
**extracted out of the workspace into versioned published crates** (`version =
"0.5.0"`, no local `path`) — the abstraction layer is now itself a shared library,
not just an internal module. The old monolithic `reth-primitives` umbrella crate
is gone; consumers pull `reth-ethereum-primitives` + `reth-primitives-traits` (or
the re-export umbrella `reth-ethereum`).

The result: reth's primitives layer is a *composition* of external libraries plus
a generic trait seam, rather than a hand-rolled type hierarchy like go-ethereum's.

## Key types / interfaces / files

- `Cargo.toml:440` — `alloy-primitives = "1.6.0"` — `B256`, `Address`, `U256`,
  `Bytes`, `Bloom`, keccak256. The shared value-types crate; **not** vendored,
  pulled from crates.io.
- `Cargo.toml:447` — `alloy-rlp = "0.3.13"` — RLP `Encodable`/`Decodable` traits
  **and** the `#[derive(RlpEncodable, RlpDecodable)]` proc-macros.
- `Cargo.toml:452` / `454` — `alloy-consensus = "2.1.0"`, `alloy-eips = "2.1.0"` —
  the canonical tx envelopes, `Header`, `Block`, `BlockBody`, `Receipt`, EIP-2718
  typed-tx encoding. reth's block/tx/header types *are* these.
- `Cargo.toml:396` — `reth-primitives-traits = "0.5.0"` — **external published
  crate** (was `crates/primitives-traits/` in earlier reth). Home of the
  `NodePrimitives` trait and `crypto::secp256k1` helpers.
- `Cargo.toml:327` / `328` — `reth-codecs = "0.5.0"`, `reth-codecs-derive` —
  external Compact/columnar DB codec, also extracted to a published crate.
- `Cargo.toml:359` — `reth-ethereum-primitives = { path = "crates/ethereum/primitives" }`
  — the only primitives crate still local to the workspace; it's thin.
- `crates/ethereum/primitives/src/lib.rs:23-37` — the delegation in one screen:
  `Transaction = alloy_consensus::EthereumTypedTransaction<TxEip4844>`,
  `TransactionSigned = alloy_consensus::EthereumTxEnvelope<TxEip4844>`,
  `Block = alloy_consensus::Block<TransactionSigned>`,
  `BlockBody = alloy_consensus::BlockBody<TransactionSigned>` — all `type` aliases.
- `crates/ethereum/primitives/src/lib.rs:41-49` — `struct EthPrimitives` and its
  `impl reth_primitives_traits::NodePrimitives for EthPrimitives { type Block = …;
  type BlockHeader = alloy_consensus::Header; type SignedTx = …; type Receipt = …; }`
  — the concrete type-family binding.
- `crates/ethereum/primitives/src/receipt.rs:8` — `type Receipt<T = TxType> =
  EthereumReceipt<T>` — even the receipt is an alloy alias; reth only adds
  `calculate_receipt_root_no_memo` (`receipt.rs:16`).
- `crates/node/types/src/lib.rs:27-36` — `trait NodeTypes { type Primitives:
  NodePrimitives; type ChainSpec: EthChainSpec<Header = …Primitives…BlockHeader>; … }`
  — the compile-time family seam: everything downstream is generic over
  `N::Primitives`.
- `crates/node/builder/src/node.rs:81` — `type Primitives = <N::Types as
  NodeTypes>::Primitives;` — example of the whole node plumbing threading the
  associated type rather than a concrete struct.
- `crates/ethereum/reth/src/lib.rs:14-20` — the umbrella re-export: `pub use
  reth_ethereum_primitives::*;` and `pub mod primitives { pub use
  reth_primitives_traits::*; }` — single import surface for downstream users.
- `crates/ethereum/evm/tests/execute.rs:22` — `crypto::secp256k1::public_key_to_address`
  — ECDSA/secp256k1 recovery helpers live in `reth-primitives-traits::crypto`, not
  a bespoke reth crypto crate.

### Crypto (all external, workspace-pinned)
- `Cargo.toml:595` — `k256 = "0.13"` (features `ecdsa`) — pure-Rust secp256k1.
- `Cargo.toml:596` — `secp256k1 = "0.30"` (features `global-context`, `recovery`)
  — libsecp256k1 bindings for signature recovery.
- `Cargo.toml:601` — `c-kzg = "2.1.5"` — KZG (EIP-4844 blob) commitments.
- `Cargo.toml:524` / `678` — `sha2`, `sha3` for hashing; keccak via alloy-primitives.

## Design decisions & rationale

- **Delegate primitives to alloy, don't reinvent.** reth-ethereum-primitives is
  ~2 source files (`lib.rs`, `receipt.rs`) of type aliases + helpers. The reasoning:
  primitive encoding/decoding is consensus-critical, must be byte-identical across
  every Rust client and tool, and is a maintenance liability — so it's owned by one
  shared, heavily-tested, independently-released crate set (alloy) rather than
  re-implemented per client.
- **Extract the abstraction itself into a library.** `reth-primitives-traits` and
  `reth-codecs` became published crates (`0.5.0`), so third-party nodes built on
  reth can depend on the trait vocabulary without vendoring reth's tree.
- **Trait-based generic primitives (`NodePrimitives`).** Rather than hard-coding
  `Block`/`Header`/`Receipt`, reth abstracts over them via associated types on
  `NodePrimitives`, wired through `NodeTypes`. A network is a *type-level* choice
  of a `Primitives` family, resolved and monomorphized at compile time — no runtime
  dispatch, no dynamic fork registry.
- **Macro-derived RLP codecs.** RLP is generated by `#[derive(RlpEncodable,
  RlpDecodable)]` at compile time (from `alloy-rlp`), giving statically-checked,
  zero-reflection, monomorphized codecs — the opposite of go-ethereum's runtime
  `reflect`-driven `rlp` package.

## Notable patterns (the reusable idea)

- **Shared cross-client primitives library (alloy).** The single most transferable
  idea. reth treats `B256`/`Address`/`U256`/RLP/tx-envelopes as *ecosystem
  infrastructure*, imported from versioned crates, not client-private code. For
  fukuii the analogue is a **shared `primitives` module** (`bytes`, `crypto`, `rlp`
  are already separate sbt sub-modules) that could be published/reused across an
  ETC tooling family instead of being welded into the node — the same seam reth
  gets from alloy, achieved via module/artifact boundaries.
- **Derive-macro / typeclass-derived codecs.** `#[derive(RlpEncodable)]`
  (`crates/net/discv4/src/proto.rs:245`, `crates/era/src/ere/types/execution.rs:251`)
  is directly analogous to a **Scala 3 `derives`/`given`-derived RLP codec**:
  compile-time-generated, exhaustive, no reflection. fukuii's RLP layer should
  favor typeclass derivation over hand-written encoders for the same
  correctness/perf reasons reth cites.
- **Compile-time type family (`NodePrimitives`/`NodeTypes`).** A network's
  primitive types are associated types resolved statically — the Rust equivalent of
  parameterizing fukuii's node over a PoW-vs-PoS primitives bundle at the type
  level rather than branching at runtime.

## Authority note

go-ethereum remains the canonical source of primitive *behavior* (RLP byte
layout, tx signing/hashing, receipt encoding, keccak) — reth/alloy are validated
against it and the ethereum/tests vectors, not the reverse. What reth contributes
is not new semantics but a **packaging approach**: the shared-ecosystem-crate
model (alloy) + compile-time-derived codecs + a trait-abstracted primitive family.
For fukuii's PoW/ETC work, core-geth stays the ETC-specific authority; reth is a
reference for *how to structure* a primitives layer, not for ETC consensus values.

## Gotchas / anti-patterns / things they later changed

- **The monolithic `reth-primitives` umbrella crate was removed.** Earlier reth had
  a big `crates/primitives/` catch-all; at this commit it's gone, split into
  `reth-ethereum-primitives` (network-specific aliases) + `reth-primitives-traits`
  (generic traits). Older docs/tutorials referencing `reth_primitives::{Block,…}`
  are stale — types now live in `reth_ethereum_primitives` or come straight from
  `alloy_consensus`.
- **`primitives-traits`/`codecs` are no longer in-tree.** They're published crates
  (`version = "0.5.0"`), so their source is *not* in this vendored repo — you cannot
  `path:line`-cite `NodePrimitives`' definition here; only its *use* sites
  (`crates/node/types/src/lib.rs:29`, `crates/ethereum/primitives/src/lib.rs:47`).
- **Version skew across the alloy stack.** `alloy-primitives` is at major `1.6.0`
  while `alloy-consensus`/`alloy-eips` are at `2.1.0` and `alloy-rlp` at `0.3.13`
  — the "shared library" is really *many* independently-versioned crates. The
  upside is granular upgrades; the cost is a workspace-wide `[workspace.dependencies]`
  pin table (`Cargo.toml`) to keep them coherent, and supply-chain surface across
  the whole alloy org. A shared-primitives strategy inherits both.
- **Two `Receipt`s look local but aren't.** `reth-ethereum-primitives`' `Receipt`
  (`receipt.rs:8`) is an alias to `alloy_consensus::EthereumReceipt`; the crate only
  owns root-calculation helpers. Do not mistake the file's presence for reth owning
  the type.
