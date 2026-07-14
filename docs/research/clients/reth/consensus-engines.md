# reth — consensus-engines

_Commit/branch documented: `3d76b93c243f8896f13a39ee865f87241fcd649b` (branch `main`,
`2026-07-01`). Vendored read-only at `.claude/repo-references/clients/reth`.
Documented 2026-07-13. Read-only research; no fukuii source touched._

_Folds in the B7.0 engine-axis research (`.local/docs/research-july/b7.0-engine-axis-decision.md`,
2026-07-13), which already banked reth's validation-only `Consensus` trait / `NodeTypes` /
`ConsensusBuilder` / `ForkCondition` findings — cited and expanded here into the full subsystem
rather than re-derived. Contrast with the runtime-plugin pole in
`docs/research/clients/nethermind/consensus-engines.md`._

## Architecture summary

reth is the **modularity / compile-time-generics (SDK) authority** — the opposite pole from
nethermind's runtime-plugin model. Where nethermind selects a consensus **mechanism** at runtime
from a parsed `chainSpec.SealEngineType` string tag and wires the winning Autofac `IModule`, reth
selects a consensus **family** at *node-assembly time* via **Rust associated types and generics**.
There is no runtime engine registry, no string discriminant, no reflection — the family is baked
into the concrete node type the binary is built from.

Two facts define reth's model, and both are unusual relative to the other five clients:

1. **The `Consensus` trait hierarchy is VALIDATION-ONLY.** `HeaderValidator` → `Consensus` →
   `FullConsensus` (`crates/consensus/consensus/src/lib.rs:74-194`) add validation layers only.
   **There is no `Seal`/mine/`Sealer`/`SealEngine` method anywhere in the consensus crates** —
   a full-tree grep of `crates/consensus/` + `crates/ethereum/consensus/` for `fn seal`/`fn mine`/
   `trait Sealer`/`SealEngine` returns nothing, and `crates/consensus/` contains only `common`,
   `consensus`, `debug-client` — **no `ethash`/`pow` crate at all**. reth assumes an external
   consensus-layer client always drives block production through the Engine API
   (`engine_newPayload` / `engine_forkchoiceUpdated`). So "engine selection" in the
   geth/besu/nethermind sense (ethash vs clique vs aura, chosen from config) **does not exist as a
   runtime concept** in the base crates. This is precisely why reth is **NOT a PoW/PoA-sealing
   authority**.

2. **Family = a Rust type, not a config value.** The concrete consensus impl is injected as an
   associated type on a builder (`ConsensusBuilder::Consensus`,
   `crates/node/builder/src/components/consensus.rs:9-18`), fixed at compile time against the
   node's `NodeTypes` (`crates/node/types/src/lib.rs:27-36`). A different family = a different
   crate/binary composed from a different `NodeTypes` — not a different branch through shared
   dispatch code.

Fork/hardfork dispatch is unified under **one** `ForkCondition` enum (block / timestamp / TTD /
never variants), so a single `Hardforks`-trait capability query answers "is fork X active?"
regardless of which axis fork X gates on — reth's answer to the block-vs-timestamp split that
fukuii carries as two `EvmConfig.forBlock` overloads.

## Key types / interfaces / files

### The validation-only Consensus trait hierarchy (the headline: no seal method)
- `crates/consensus/consensus/src/lib.rs:146-194` — **`HeaderValidator<H>`**: `validate_header`
  (header in isolation), `validate_header_against_parent` (number/timestamp/basefee/gas-limit
  increment), and a defaulted `validate_header_range`. The lowest layer, applied early in the
  pipeline.
- `lib.rs:94-142` — **`Consensus<B: Block>: HeaderValidator<B::Header>`**: adds
  `validate_body_against_header` and `validate_block_pre_execution` (tx root / ommer hash /
  withdrawals — pre-execution, pre-state), plus an `is_transient_error` hook (clock-skew-tolerant
  future-timestamp handling) and a tx-root-optimized `validate_block_pre_execution_with_tx_root`.
- `lib.rs:71-91` — **`FullConsensus<N: NodePrimitives>: Consensus<N::Block>`**: adds
  `validate_block_post_execution` (gas used / receipt root / logs bloom — checked *after*
  execution against the header). This is the type the node builder ultimately requires
  (`ConsensusBuilder::Consensus: FullConsensus<…>`).
- `lib.rs:5-20` (module doc) — states the contract explicitly: the three traits are applied **in
  order during `engine_newPayload`**, and **payload-attribute validation for block building
  (`engine_forkchoiceUpdated`) is handled separately at the engine-API layer and does not use
  these traits.** Confirms validation and production are cleanly split, with production off the
  consensus trait entirely.
- `lib.rs:504-506` — `ConsensusError::Other(Arc<dyn Error…>)` — the open extension point for
  L2-specific consensus errors (Optimism, etc.), so a downstream family adds error variants
  without editing the core enum.

### The concrete engine (one generic impl, fork-gated once)
- `crates/ethereum/consensus/src/lib.rs:42-55` — **`EthBeaconConsensus<ChainSpec>`**, generic over
  the chain-spec type, holding `Arc<ChainSpec>` plus a handful of `skip_*`/`allow_*` bool knobs
  (gas-limit ramp, blob-gas-used, requests-hash, BAL hashes) toggled by `with_*` builders — the
  ONE Ethereum-family validation engine.
- `lib.rs:57` — the bound **`impl<ChainSpec: EthChainSpec + EthereumHardforks>`**: fork-gating is
  written **once** against the `EthereumHardforks` capability trait, never per network.
- `lib.rs:176-234` — `HeaderValidator::validate_header` shows the capability-query dispatch in
  action: `is_paris_active_at_block(number)` selects the post-merge branch (difficulty/nonce/ommer
  must be zero/empty) vs the pre-merge future-timestamp check;
  `is_shanghai_active_at_timestamp(timestamp)` gates withdrawals-root;
  `is_cancun_active_at_timestamp(timestamp)` gates the 4844 checks — **block-axis and
  timestamp-axis queries sit side by side in one method**, both dispatching through the same
  `Hardforks` machinery.

### Family via Rust type — the compile-time injection point
- `crates/node/types/src/lib.rs:27-36` — **`trait NodeTypes`**: the stateless type-level config of
  a node — associated types `Primitives: NodePrimitives`, **`ChainSpec: EthChainSpec<Header =
  …>`**, `Storage`, `Payload: PayloadTypes`. This is where a chain family's identity lives: a
  family is a `NodeTypes` impl, resolved at compile time.
- `lib.rs:82-127` — **`AnyNodeTypes<P, C, S, PL>`**, a `PhantomData` type-builder with `const fn`
  setters (`primitives`/`chain_spec`/`storage`/`payload`) — you assemble the node's type identity
  with zero runtime cost; it's all phantom types.
- `crates/node/builder/src/components/consensus.rs:9-18` — **`trait ConsensusBuilder<Node:
  FullNodeTypes>`**: `type Consensus: FullConsensus<PrimitivesTy<Node::Types>> + …` and
  `fn build_consensus(self, ctx) -> impl Future<…Self::Consensus>`. The injection seam: the node
  builder demands *some* `FullConsensus`, and the builder supplies it.
- `consensus.rs:20-35` — a blanket impl making **any closure** `FnOnce(&BuilderContext) -> Future<
  FullConsensus>` a `ConsensusBuilder`. So the "engine choice" is literally a Rust closure/type
  supplied at assembly time — the compile-time analogue of nethermind's `Module`.
- `crates/ethereum/node/src/node.rs:747-764` — **`EthereumConsensusBuilder`**, the concrete wiring:
  its `build_consensus` is a one-liner — `Ok(Arc::new(EthBeaconConsensus::new(ctx.chain_spec())))`.
  The Ethereum family's engine is fixed here at build time; there is no lookup. (Note the
  `// TODO add closure to modify consensus` — even the customization hook is envisioned as a
  compile-time closure, not runtime config.)

### Unified fork dispatch — `ForkCondition` + `Hardforks`
- `ForkCondition` is defined in the **external `alloy-hardforks` crate** (`Cargo.toml:450`,
  `alloy-hardforks = "0.4.7"`), re-exported by reth via `pub use alloy_hardforks::*`
  (`crates/ethereum/hardforks/src/lib.rs:25`) — so its enum body is not vendored in-tree, but its
  four variants are used pervasively and are the load-bearing abstraction: **`Block(u64)` /
  `Timestamp(u64)` / `TTD { total_difficulty, fork_block, .. } / `Never`** (confirmed by a tree-wide
  grep of `ForkCondition::{Block,Timestamp,TTD,Never}`). One enum encodes **all** activation axes
  — block number, wall-clock timestamp, terminal total difficulty (the merge), and "never".
- `crates/ethereum/hardforks/src/hardforks/mod.rs:12-41` — **`trait Hardforks`**: `fork<H>(fork) ->
  ForkCondition` plus the convenience accessors **`is_fork_active_at_timestamp`** (`:21-23`) and
  **`is_fork_active_at_block`** (`:26-28`), both of which dispatch through the **same
  `ForkCondition`** (`self.fork(fork).active_at_timestamp(ts)` / `.active_at_block(n)`). A family
  need not know which axis a fork gates on — it asks the capability question and `ForkCondition`
  resolves it.
- `hardforks/mod.rs:44-126` — **`ChainHardforks`**: the ordered `Vec<(Box<dyn Hardfork>,
  ForkCondition)>` + name→condition map. `fork_block` (`:85-92`) collapses all three
  activation axes to a single `Option<u64>` — Block→number, TTD→`fork_block`, Timestamp→ts. Forks
  are kept sorted by `ForkCondition`'s own `Ord` (Never < Block < Timestamp < TTD).
- `crates/chainspec/src/spec.rs:721-741` — **`satisfy(cond: ForkCondition) -> Head`**: the single
  match that folds all axes into a `Head` for `fork_id` computation (EIP-2124) — `Block(n)` →
  `Head.number`, `Timestamp(t)` → `Head.timestamp` + last-pre-merge block, `TTD` →
  `Head.total_difficulty`. One `match`, every axis. This is the reth reference for "unify
  block-vs-timestamp fork dispatch under one type" — compare nethermind's twin-`SortedSet`
  `AddTransitions` and fukuii's two `forBlock` overloads.

## Design decisions & rationale

- **Validation and production are different concerns — split them.** By making the `Consensus`
  hierarchy validation-only and pushing block production entirely behind the Engine API, reth
  needs no `Seal` abstraction in its consensus layer. This is coherent *given* reth's premise: an
  external CL always exists. The cost is that reth cannot self-produce PoW/PoA blocks — there is no
  seal engine to select (contrast core-geth's `ethash`, besu's `CliqueBlockCreator`).
- **Compile-time family injection over runtime plugin discovery.** A family is a `NodeTypes` +
  `ConsensusBuilder` type, resolved when the binary is assembled — not a DLL discovered at runtime
  from a `plugins/` dir (nethermind) nor a `switch` on a parsed string (besu/erigon). This buys
  full type safety and zero-cost abstraction (`PhantomData` builders, monomorphized generics) at
  the cost of: **each family is its own crate and, in practice, its own binary** (`reth` vs
  `op-reth`). You cannot flip families with a config flag.
- **One `ForkCondition` enum unifies every activation axis.** Rather than separate block-number and
  timestamp code paths, reth encodes Block/Timestamp/TTD/Never in a single enum and answers all
  fork queries through it via the `Hardforks` trait. Adding a timestamp fork is the same shape as
  adding a block fork — the axis is data, not a distinct method. This is the portable idea for
  fukuii even though the rest of reth's model is not.
- **Capability-query fork gating, never network-identity branching.** `EthBeaconConsensus` is
  generic over `EthChainSpec + EthereumHardforks` and asks `is_<fork>_active_at_<axis>` — it never
  branches on "which network". This is the exact anti-pattern the EIP1559-DEALIAS arc and B7.0's
  §A.5 target in fukuii, realized cleanly here.

## Notable patterns (the reusable idea)

1. **Validation-only consensus trait + external-CL production (the "no seal engine" model).**
   `HeaderValidator → Consensus → FullConsensus`, applied during `engine_newPayload`; production is
   off the trait, behind the Engine API. The reference for "an EL that never seals." **Not
   portable to fukuii wholesale** — fukuii must keep standalone PoW/PoA sealing for ETC and for the
   PoA-upgrade-testing use case — but a clean model of separating validation from production.
2. **Compile-time family injection via associated types (`NodeTypes` + `ConsensusBuilder`).** The
   family is a Rust type fixed at node-assembly time; a new family = a new crate composed from a
   new `NodeTypes`, with no shared-dispatch edit. The **compile-time pole** opposite nethermind's
   runtime self-declaring plugins. Portable to fukuii only as a **given/typeclass-parameterized
   node assembly**, and only if fukuii accepts one-family-per-build — which it likely will not
   (single JVM binary, runtime network selection). So this is the *contrast* case, not the target.
3. **`ForkCondition`-unified fork dispatch (THE portable pattern).** A single enum
   (Block/Timestamp/TTD/Never) + a `Hardforks` capability trait whose `is_fork_active_at_block` and
   `is_fork_active_at_timestamp` both resolve through it. The reth reference for collapsing fukuii's
   two `EvmConfig.forBlock(block)` / `forBlock(block, timestamp)` overloads into one axis-agnostic
   activation query. Portable to Scala **now**, independent of the rest of reth's SDK model.
4. **`Hardforks`-capability-query fork gating (portable).** Fork-gate on
   `is_<fork>_active_at_<axis>` capability predicates, never on network identity — the same ratchet
   fukuii's de-alias arc enforces.
5. **Open error extension via `ConsensusError::Other(Arc<dyn Error>)`.** L2/family-specific
   consensus errors attach without editing the shared enum — a small but reusable
   open-for-extension seam.

## Authority note

**reth is THE modularity / compile-time-generics (SDK) authority** — per the Phase-0 authority
model ("reth — modularity / SDK (NodeTypes, compile-time chain families)") — **and the
`ForkCondition`-unified-fork-dispatch reference.** It is the **compile-time pole** the nethermind
`consensus-engines` doc contrasts with: nethermind selects a family at runtime from parsed config +
reflection + a DI container; reth selects it at compile time from associated types +
monomorphized generics. Both achieve "add a family without editing shared dispatch," by opposite
means — nethermind pays reflection/DI indirection, reth pays one-crate-(one-binary)-per-family.

reth is **explicitly NOT a PoW/PoA-sealing authority** — its consensus layer is validation-only
(`HeaderValidator/Consensus/FullConsensus`), has **no seal/mine method and no ethash/pow crate**,
and assumes an external CL drives production via the Engine API. For ETC PoW/ETChash sealing the
authority is **core-geth** (ECIP-1017/1099/1111/1122); for multi-consensus/PoA sealing seams
(`Sealer`/`ValidatorProvider`/`BlockInterface`) it is **besu**. reth has no ECIP awareness and
cannot self-produce PoW blocks.

**The trade-off to note for fukuii.** reth's model requires **each family to be its own
crate/binary** (reth vs op-reth), which is idiomatic in Rust's monomorphized, zero-cost-generics
world but **poorly matched to fukuii's single-JVM-binary, runtime-network-selection design** — you
cannot pick ETC vs ETH vs a private PoA chain with a config flag under reth's shape. So reth's
*family-injection* model is the **less directly applicable** reference of the six (nethermind's
runtime self-declaration is the closer target for fukuii's B7.0.5 `NetworkFamily` registry). What
**is** portable, and independent of that trade-off: (a) the **`ForkCondition` unification** of
block/timestamp/TTD dispatch under one type, and (b) the **`Hardforks`-capability-query** fork
gating — both adoptable in Scala now.

## Gotchas / anti-patterns / things they later changed

- **No standalone block production — a hard limitation, not a style choice.** reth cannot seal
  PoW/PoA blocks; it depends on an external CL through the Engine API. The only "self-production"
  is the **local dev auto-seal** (`crates/engine/local/src/miner.rs`) used for `--dev`, which
  drives the Engine API locally against a mock CL — **not** a real seal engine and **not** a
  consensus mechanism you can select for a public chain. Do not read reth as evidence that an EL
  can drop standalone sealing; that regression is exactly what B7.0 warns against aligning ETC to
  (geth + reth both dropped standalone PoW).
- **`ForkCondition` lives in an external crate (`alloy-hardforks 0.4.7`), not vendored in-tree.**
  The enum body is re-exported (`pub use alloy_hardforks::*`), so its definition is not directly
  citable within the vendored reth source — the reth-owned code is the `Hardforks` trait and its
  convenience accessors (`hardforks/mod.rs`) and the `satisfy`/`fork_id` usage (`spec.rs`). A
  fukuii port owns the enum itself rather than depending on an alloy re-export.
- **One-family-per-binary is implicit in the type model.** Because the family is an associated type
  fixed at build time, reth ships `reth` and `op-reth` as separate binaries. Nothing in the
  `NodeTypes`/`ConsensusBuilder` design lets one running process host two families — a structural
  constraint a reader must infer from the generics, and the single biggest reason this model does
  not transplant onto fukuii's single-binary, multi-network design.
- **"Is this a consensus mechanism?" is answered by a trait bound, not a value.** The consensus impl
  is whatever type satisfies `FullConsensus<PrimitivesTy<Node::Types>>`; there is no enumerable
  registry to inspect at runtime. Powerful and type-safe, but the set of supported families is a
  compile-time property of which crates are linked, not a discoverable list — the mirror-image
  trade-off of nethermind's runtime-but-reflective discovery.
