# reth — evm
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth does **not** contain an EVM interpreter. Its EVM is a layered stack of
external, reusable crates that reth **configures** through its own trait seam:

1. **revm** (`revm = "41.0.0"`, external) — the interpreter itself: opcode
   dispatch, gas schedule, precompiles, the `SpecId` fork enum, the `Inspector`
   tracing hook, and a **handler-based** execution architecture (revm composes
   per-fork handler logic rather than branching inline). This is the dominant
   standalone Rust EVM, used far beyond reth.
2. **alloy-evm** (`alloy-evm = "0.37.0"`, external) — a thin trait layer *over*
   revm that reth re-exports wholesale (`crates/evm/evm/src/lib.rs:55`): the
   `Evm` trait, `EvmFactory` (produces an `Evm` from a DB + env), `EvmEnv`
   (cfg + block env parameterised by spec), `BlockExecutorFactory`,
   `PrecompilesMap` (the mutable precompile set), and the `spec()` /
   `spec_by_timestamp_and_block_number()` fork-resolution helpers.
3. **reth-evm** (`crates/evm/evm/`, ~1.8k LoC) — reth's own crate. Its centrepiece
   is the **`ConfigureEvm` trait**: the single compile-time seam a chain family
   implements to bind its primitives, spec type, EVM factory, block-executor
   factory, and block assembler into one coherent configuration. This is what
   lets reth's `NodeTypes` pattern swap an entire EVM family generically.
4. **reth-revm** (`crates/revm/`, ~1.2k LoC) — glue only: adapts reth's storage
   (`StateProvider`) into revm's `Database` trait, plus cached/cancellable DB
   wrappers and execution-witness helpers. No EVM logic.
5. **reth-ethereum-evm** (`crates/ethereum/evm/`) — the concrete Ethereum
   implementation: `EthEvmConfig` (impl of `ConfigureEvm`) and `RethEvmFactory`
   (wraps `alloy_evm::EthEvmFactory`, optionally adding a revmc JIT backend).

So the dependency direction is: **reth-ethereum-evm → reth-evm (`ConfigureEvm`)
→ alloy-evm (`EvmFactory`/`Evm`/`EvmEnv`) → revm (interpreter/`SpecId`/`Inspector`)**.
A new chain family only writes a new `ConfigureEvm` impl; the interpreter is untouched.

## Key types / interfaces / files

- `crates/evm/evm/src/lib.rs:181` — **`trait ConfigureEvm`**: the compile-time
  EVM-configuration seam. Bounds `Clone + Debug + Send + Sync + Unpin` and hangs
  five associated types off it: `Primitives` (NodePrimitives), `Error`,
  `NextBlockEnvCtx`, `BlockExecutorFactory`, `BlockAssembler`. The
  `BlockExecutorFactory` bound (`:194`) transitively pins `EvmFactory` with
  `Precompiles = PrecompilesMap` and `Spec: Into<SpecId>` — i.e. every family's
  spec must map onto revm's `SpecId`.
- `crates/evm/evm/src/lib.rs:219` — `evm_env(header)` and `:237` `next_evm_env(parent, attributes)`:
  the two entry points that build an `EvmEnv` (spec + block env) — one for
  executing an existing block, one for building the next one (post-merge, needs
  CL-supplied attributes).
- `crates/evm/evm/src/lib.rs:299` — `evm_with_env(db, env)` and `:325`
  `evm_with_env_and_inspector(db, env, inspector)`: produce a live `Evm`, the
  latter threading a revm **`Inspector`** in as the external context.
- `crates/evm/evm/src/lib.rs:453-488` — `executor()` / `batch_executor()`: default
  methods returning a `BasicBlockExecutor`, so a family gets whole-block execution
  for free once it supplies the factories.
- `crates/evm/evm/src/aliases.rs:44` — `EvmEnvFor<Evm> = EvmEnv<SpecFor<Evm>, BlockEnvFor<Evm>>`,
  and `:47` **`trait InspectorFor<Evm, DB>: Inspector<EvmContextFor<Evm, DB>>`** —
  the bound that ties any revm `Inspector` to a specific `ConfigureEvm`'s context.
- `crates/evm/evm/src/engine.rs:8` — `trait ConfigureEngineEvm<ExecutionData>`:
  a `ConfigureEvm` extension for building an `EvmEnv`/tx-iterator straight from an
  Engine-API payload, with rayon-parallelised tx recovery (`ExecutableTxIterator`).
- `crates/ethereum/evm/src/lib.rs:130` — `impl ConfigureEvm for EthEvmConfig<C, EvmF>`:
  the concrete Ethereum binding. Note it is **generic over the chain spec `C`**
  (`EthExecutorSpec + EthChainSpec + Hardforks`) and the EVM factory `EvmF` — a
  reusable pattern (an L2/testnet reuses this with a different `C`).
- `crates/ethereum/evm/src/config.rs:1` — re-exports `revm_spec` /
  `revm_spec_by_timestamp_and_block_number` **from alloy-evm**: fork dispatch is
  not reth code, it lives in the shared lib.
- `crates/ethereum/evm/src/factory.rs:170` — `impl EvmFactory for RethEvmFactory`:
  `type Spec = SpecId`, `type Precompiles = <Inner as EvmFactory>::Precompiles`;
  `create_evm` / `create_evm_with_inspector` (`:183`/`:198`) delegate to
  `alloy_evm::EthEvmFactory`, optionally routing through the revmc JIT factory.
- `crates/revm/src/database.rs:10` — `trait EvmStateProvider` + blanket impl for
  any `StateProvider`, adapting reth storage into revm's `Database`.
- `crates/revm/src/lib.rs:31` — `pub use revm::{self, database::State, *}`: reth-revm
  is largely a curated re-export surface over revm.
- `crates/rpc/rpc/src/debug.rs:46,127` — RPC tracing wires revm's `Inspector`
  (via `revm-inspectors = "0.41.0"`, `DebugInspector`/`TransactionContext`) into
  `eth_api.inspect(db, evm_env, tx_env, &mut inspector)` — the archival/RPC use of
  the same tracing hook.

## Design decisions & rationale

- **Don't own the interpreter; own the configuration.** By depending on revm +
  alloy-evm and only defining `ConfigureEvm`, reth inherits every EIP/fork/gas
  fix the wider ecosystem lands in revm, and contributes back to a lib many
  clients share. reth's own EVM crate is <2k LoC.
- **Compile-time family selection via associated types.** `ConfigureEvm`'s
  associated types (`Primitives`, `BlockExecutorFactory`, `BlockAssembler`) mean
  the *whole* execution stack for a chain is chosen by a single type parameter.
  Combined with `NodeTypes`, an OP-stack or custom chain plugs in a different
  `ConfigureEvm` and the compiler wires the rest — no runtime `match` on chain id.
- **`SpecId` as the universal fork currency.** Every family's `Spec` must be
  `Into<SpecId>` (`lib.rs:203`). revm's `SpecId` is the single enum the interpreter
  branches on, so all fork logic collapses to "resolve header → SpecId".
- **Two fork-resolution functions, one for each dispatch style.** `revm_spec`
  (block-number, pre-merge PoW) vs `revm_spec_by_timestamp_and_block_number`
  (timestamp, post-merge) — mirrors reth's need to serve both regimes. reth just
  picks which resolver to call in `evm_env` vs `evm_env_for_payload`.
- **Inspector as the sole tracing seam.** Rather than special-casing tracing in
  the executor, tracing is "an `Evm` created with an `Inspector`". The same
  `create_evm_with_inspector` path serves `debug_*`/`trace_*` RPC and archival
  replay; production execution just uses `NoOpInspector` (the default type param,
  `aliases.rs:21`).
- **Optional JIT is a factory wrapper, not a fork.** revmc JIT is layered by
  wrapping the inner `EvmFactory` (`RethEvmFactory`), gated behind three
  independent switches (build feature + runtime enable + per-config support flag,
  `factory.rs:37`), so the non-JIT build is a zero-cost thin newtype.

## Notable patterns (the reusable idea)

**revm's `SpecId` + reth's `ConfigureEvm` trait = a compile-time-safe
EVM-family seam.** The transferable idea has two halves that reinforce each other:

1. **A single fork enum the interpreter branches on** (`SpecId`), with header→spec
   resolution factored into small pure functions (`revm_spec`,
   `revm_spec_by_timestamp_and_block_number`). All fork-conditional behaviour
   funnels through one value.
2. **A trait that binds an entire chain's EVM stack behind associated types**
   (`ConfigureEvm`) so that "which network am I?" becomes a type parameter chosen
   once at node-build time, not a runtime dispatch scattered through the executor.

The secondary reusable pattern is **layering by ownership**: interpreter (revm,
shared) → trait wrapper (alloy-evm, shared) → configuration seam (reth-evm) →
concrete binding (reth-ethereum-evm). A client that keeps this discipline can
adopt an upstream EVM library wholesale and localise all chain-specific logic to
the top layer.

## Authority note

go-ethereum is the canonical reference for EVM/EIP behaviour and gas semantics.
**revm** is the dominant *standalone Rust EVM* — a reusable component consumed by
reth, Foundry, and many others, and the Rust-ecosystem authority for interpreter
behaviour. **reth's `ConfigureEvm`** is the compile-time EVM-family seam layered
on top; it is an *integration* pattern, not an EVM authority in its own right.
The JVM peer to revm-as-standalone-lib is **besu's evm module** (`org.hyperledger.besu.evm`,
`EvmSpec`/`MainnetEVMs`) — the same "reusable EVM library plus a per-fork spec
selector" shape, which is the most directly comparable reference for fukuii's own
JVM `EvmConfig`.

## Gotchas / anti-patterns / things they later changed

- **Most of the "EVM" is not in reth.** Reading only `crates/evm/` and
  `crates/revm/` misses `EvmFactory`, `Evm`, `EvmEnv`, `PrecompilesMap`, and the
  spec resolvers — all in external `alloy-evm`, and `SpecId`/`Inspector` in
  external `revm`. The `use revm::…` / `use alloy_evm::…` imports are load-bearing.
- **`crates/evm/evm/src/config.rs` is a 4-line re-export**, not the fork logic.
  Don't expect reth to define `revm_spec`; it re-exports alloy-evm's.
- **Fast-moving external ABI.** reth pins revm/alloy-evm to exact minor versions
  (`revm = "41.0.0"`, `alloy-evm = "0.37.0"`, `revm-inspectors = "0.41.0"`) and a
  recurring PR class is "integrate revm updates" — revm breaking changes ripple
  into reth's `ConfigureEvm` impls (see reth's own CLAUDE.md, "Integration with
  Upstream Changes"). A fukuii-style vendored/forked EVM avoids this churn but
  forfeits the shared-fix benefit; it is a genuine trade-off, not a free win.
- **`EthEvmConfig` was made generic over chain spec** (was hardcoded to
  `ChainSpec`, now `EthEvmConfig<C, EvmFactory>`, `lib.rs:85`) — a deliberate
  refactor (reth PR #16758) so testnets/L2s reuse the impl. If you copy an older
  snapshot you'll see the non-generic form.
- **JIT paths are `#[cfg(feature = "jit")]` throughout `factory.rs`** and rely on
  `dyn Any` downcasting (`lib.rs:170,191`) to reach the concrete `RethEvmFactory` —
  a small escape hatch from the otherwise fully-generic seam; easy to overlook
  when reasoning about the trait alone.
