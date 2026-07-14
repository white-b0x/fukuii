# reth — node-lifecycle
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth's node assembly is a **compile-time SDK**: a chain family is a Rust type that
implements the `NodeTypes` trait, bundling four associated types (`Primitives`,
`ChainSpec`, `Storage`, `Payload`). Everything downstream — components, providers, RPC,
the launcher — is generic over that one type parameter, so the compiler proves the whole
node is internally consistent (the pool's transaction type equals the primitives' signed
transaction type, the payload builder's built-payload equals the engine's, etc.) **before
any code runs**. There is no runtime plugin registry and no `dyn` dispatch on the family:
"add a network" means "write a type that implements `NodeTypes` + `Node`, and the type
system checks it."

The lifecycle is a **typestate state machine** (`crates/node/builder/src/builder/states.rs:5`):
`NodeBuilder<DB, ChainSpec>` → `.with_types::<T>()` → `NodeBuilderWithTypes<T>` →
`.with_components(cb)` → `NodeBuilderWithComponents<T, CB, ()>` → `.with_add_ons(ao)` →
`NodeBuilderWithComponents<T, CB, AO>` → `.launch()` → `NodeHandle`. Each transition is a
distinct type; you physically cannot call `.launch()` before components are configured
because that method doesn't exist on the earlier types. Components themselves are assembled
by a second typestate builder, `ComponentsBuilder<Node, PoolB, PayloadB, NetworkB, ExecB,
ConsB>`, where each of the six generic slots starts as `()` and is filled by a `.pool(..)`
/ `.executor(..)` / `.network(..)` / etc. call that returns a *new* `ComponentsBuilder`
type with that slot's builder substituted in.

## Key types / interfaces / files

### The family definition (the compile-time chain family)
- `crates/node/types/src/lib.rs:27` — **`trait NodeTypes`**: the associated-type bundle that
  *is* a chain family. `type Primitives: NodePrimitives` (block/header/tx/receipt types),
  `type ChainSpec: EthChainSpec<Header = ...>` (fork schedule + genesis), `type Storage`
  (writes chain primitives to the DB), `type Payload: PayloadTypes` (engine/payload-builder
  interaction). Bound `Clone + Debug + Send + Sync + Unpin + 'static`; doc: "intended to be
  stateless and only define the types of the node."
- `crates/node/types/src/lib.rs:31` — the cross-associated-type equality constraint
  `ChainSpec: EthChainSpec<Header = <Self::Primitives as NodePrimitives>::BlockHeader>`. This
  single bound is why a family can't accidentally pair an ETH header with an OP chainspec —
  the compiler rejects it.
- `crates/node/types/src/lib.rs:42` — **`trait NodeTypesWithDB: NodeTypes`**: adds `type DB:
  Database`. Deliberately split out because the DB is chosen by the builder at launch, "not
  intended to be user configurable" — see the Gotchas note on why DB is *not* in `NodeTypes`.
- `crates/node/types/src/lib.rs:82` / `:131` — **`AnyNodeTypes<P,C,S,PL>`** and
  `AnyNodeTypesWithEngine<..>`: `PhantomData`-only const builders that let you assemble a
  `NodeTypes` impl by chaining `.primitives::<T>()`, `.chain_spec::<T>()`, etc. — a
  type-level builder producing a zero-sized family definition.
- `crates/node/types/src/lib.rs:185-203` — helper projections `BlockTy<N>`, `HeaderTy<N>`,
  `TxTy<N>`, `ReceiptTy<N>`, `PrimitivesTy<N>`, `PayloadAttrTy<N>`. These are the
  reach-through aliases every downstream bound uses instead of spelling out
  `<<N::Types as NodeTypes>::Primitives as NodePrimitives>::SignedTx`.

### Stateful adapters over the pure type bundle
- `crates/node/api/src/node.rs:24` — **`trait FullNodeTypes`**: downstream of `NodeTypes`,
  adds `type DB` and `type Provider: FullProvider<..>`. This is the type parameter the
  *component* builders are generic over (they need a provider + DB, not just the pure types).
- `crates/node/api/src/node.rs:66` — **`trait FullNodeComponents: FullNodeTypes`**: the
  runtime handle bundle — `Pool`, `Evm`, `Consensus`, `Network` associated types plus
  `pool()`, `evm_config()`, `consensus()`, `network()`, `payload_builder_handle()`,
  `provider()`, `task_executor()` accessors. This is the "assembled node" interface add-ons
  and RPC are written against.
- `crates/node/builder/src/builder/states.rs:82` — **`NodeAdapter<T, C>`**: the concrete
  struct (`components: C`, `task_executor`, `provider`) that implements both `FullNodeTypes`
  and `FullNodeComponents` by delegating to its `components` (`states.rs:97`).

### The component builder traits (each a trait a family implements)
Each is a one-method async factory returning an associated output type, **plus a blanket
impl for closures** so a plain `|ctx| async {..}` satisfies the trait:
- `crates/node/builder/src/components/execute.rs:7` — **`ExecutorBuilder`** → `type EVM:
  ConfigureEvm<Primitives = PrimitivesTy<Node::Types>>`; `build_evm(ctx)`.
- `crates/node/builder/src/components/pool.rs:16` — **`PoolBuilder<Node, Evm>`** → `type
  Pool: TransactionPool<Transaction: PoolTransaction<Consensus = TxTy<Node::Types>>>`;
  `build_pool(ctx, evm_config)` — note it takes the EVM, encoding the dependency.
- `crates/node/builder/src/components/network.rs` — **`NetworkBuilder<Node, Pool>`** →
  `build_network(ctx, pool)`.
- `crates/node/builder/src/components/payload.rs` — **`PayloadServiceBuilder<Node, Pool,
  EVM>`** → `spawn_payload_builder_service(ctx, pool, evm)`.
- `crates/node/builder/src/components/consensus.rs:9` — **`ConsensusBuilder<Node>`** → `type
  Consensus: FullConsensus<PrimitivesTy<Node::Types>>`; `build_consensus(ctx)`.
- `crates/node/builder/src/components/mod.rs:38` — **`trait NodeComponents<T>`**: the
  assembled bundle (`Pool`/`Evm`/`Consensus`/`Network` + payload handle). `Components<..>`
  (`mod.rs:72`) is the concrete struct.

### The assembly and its dependency ordering
- `crates/node/builder/src/components/builder.rs:42` — **`ComponentsBuilder<Node, PoolB,
  PayloadB, NetworkB, ExecB, ConsB>`**: the six-slot typestate. `.pool()` (`:149`),
  `.executor()` (`:190`), `.consensus()` (`:219`) are available once `Node: FullNodeTypes`;
  `.network()` (`:256`) and `.payload()` (`:285`) only become available in an `impl` block
  gated on `ExecB: ExecutorBuilder<Node>, PoolB: PoolBuilder<Node, ExecB::EVM>` (`:245-251`)
  — **the trait bounds themselves enforce build order**: you cannot configure the network
  until the pool (which the network needs) is configured.
- `crates/node/builder/src/components/builder.rs:375` — **`build_components()`**: the actual
  order — `build_evm` → `build_pool(evm)` → `build_network(pool)` →
  `spawn_payload_builder_service(pool, evm)` → `build_consensus`. Standalone components
  first, then services spawned (`builder.rs:37-40` doc).
- `crates/node/builder/src/components/builder.rs:466-565` — `Noop{Pool,Network,Consensus,
  Payload}Builder`: swap-in no-op implementations, useful for import/debug nodes and tests.

### Preconfigured families and the CLI entry
- `crates/node/builder/src/node.rs:33` — **`trait Node<N>: NodeTypes`**: a family that comes
  with a `type ComponentsBuilder` and `type AddOns` preset (`components_builder()`,
  `add_ons()`). This is the "batteries included" layer over raw `NodeTypes`.
- `crates/ethereum/node/src/node.rs:71` `struct EthereumNode` — the reference concrete family.
  `impl NodeTypes` (`:128`) sets `Primitives = EthPrimitives`, `ChainSpec = ChainSpec`,
  `Storage = EthStorage`, `Payload = EthEngineTypes`. `EthereumNode::components()` (`:75`)
  builds `ComponentsBuilder::default().pool(EthereumPoolBuilder).executor(..).payload(..)
  .network(..).consensus(..)` (`:93-99`), and `impl Node<N>` (`:438`) wires those as the
  preset. **This ~30-line file is the entire "define a chain family" surface** — the model
  fukuii's registry wants to match.
- `crates/node/builder/src/builder/mod.rs:153` `struct NodeBuilder<DB, ChainSpec>`; `.new()`
  (`:164`), `.with_types::<T>()` (`:294`), `.node::<N>(node)` (`:315`, one-call convenience:
  `with_types().with_components(..).with_add_ons(..)`).

### Launch, handle, exit (tokio graceful shutdown)
- `crates/node/builder/src/launch/common.rs:120` — **`LaunchContext`** (task_executor +
  data_dir) and `LaunchContextWith<T>` (`:260`), a builder that threads attachments (configs,
  provider factory) through the launch stages; `configure_globals` raises the fd limit and
  sizes the rayon pool.
- `crates/node/builder/src/handle.rs:9` — **`NodeHandle<Node, AddOns>`**: `{ node: FullNode,
  node_exit_future }`, `#[must_use = "Needs to await the node exit future"]`;
  `wait_for_node_exit()` awaits it.
- `crates/node/core/src/exit.rs:12` — **`NodeExitFuture`**: wraps the consensus-engine future;
  resolving it is how the process stays alive / exits.
- `crates/node/builder/src/node.rs:112` — **`FullNode<Node, AddOns>`**: the launched node
  (evm_config, pool, network, provider, payload handle, task_executor, config, data_dir,
  add_ons_handle). `Deref`s to the add-ons handle so RPC handles are reachable directly.
- Hooks: `states.rs:196` `on_component_initialized`, `:205` `on_node_started`, `:291`
  `on_rpc_started`, `:307` `extend_rpc_modules`, `:220` `install_exex` (Execution Extensions).

### Config, metrics, tracing (observability)
- `crates/node/core/src/node_config.rs` — `NodeConfig<ChainSpec>`, the CLI-populated config
  root threaded through the whole builder.
- `crates/tracing/src/lib.rs:97` — **`RethTracer`** (crate `reth-tracing`): composes stdout /
  optional file / optional journald layers (`Layers`, `crates/tracing/src/layers.rs`), JSON
  or text formatter, and a **reload layer** so the stdout log-level filter can be changed at
  runtime (`lib.rs:105`).
- `crates/node/metrics/src/` — `server.rs` (Prometheus endpoint), `process.rs`/`chain.rs`/
  `storage.rs` metric hooks, `recorder.rs`. Metrics are registered by name (`metrics::counter!
  ("reth_...")`), not wired through the type system.

## Design decisions & rationale

- **Family = one type, checked at compile time.** By making `NodeTypes` a pure
  associated-type bundle with cross-type equality bounds (`types/src/lib.rs:31`, `:35`), reth
  gets a chain family whose internal consistency is a *compiler theorem*, not a runtime
  assertion. Wiring an OP payload type into an ETH primitives family is a type error at the
  `EthereumNode` definition site, not a panic at block N.
- **Split `NodeTypes` (pure) from `FullNodeTypes` (has DB+Provider) from `FullNodeComponents`
  (has running handles).** Each tier adds exactly what the next layer of code needs. Component
  builders are generic over `FullNodeTypes` (they need a provider); add-ons/RPC over
  `FullNodeComponents` (they need running handles). The DB is intentionally *not* in
  `NodeTypes` (`builder/mod.rs` "Internals" doc, ~line 138) because the builder picks it at
  launch — keeping the family definition storage-agnostic.
- **Typestate builders make illegal states unrepresentable.** The lifecycle state machine
  (`states.rs`) and the component-order bounds (`builder.rs:245-251`) push ordering and
  completeness into the type system: no `.launch()` before components, no `.network()` before
  `.pool()`. Errors surface as "method not found," the earliest possible point.
- **Closures satisfy the builder traits.** Every component-builder trait has a blanket
  `impl ... for F where F: FnOnce(&BuilderContext) -> Fut` (e.g. `execute.rs:20`,
  `consensus.rs:20`). Simple customization needs a closure; complex customization gets a named
  struct. Same trait, two ergonomic tiers.
- **`map_*` / `Noop*` for surgical override.** `ComponentsBuilder::map_pool/map_network/..`
  (`builder.rs:80-137`) let a downstream family start from `EthereumNode::components()` and
  replace one component, rather than re-declaring all six — the ETH preset is the reuse base.

## Notable patterns (the reusable idea)

**A chain family is a type implementing a trait whose associated types cross-constrain each
other, and every downstream concern is generic over that one type — so the compiler proves
the assembled node is coherent before launch.** The registry is the trait's coherence
(one canonical impl per family type), not a runtime map. "Add a network" = "write
`impl NodeTypes for FooNode` + `impl Node<N> for FooNode`"; the compiler is the validator.

Secondary but highly transferable: **encode inter-component dependencies as trait bounds on
builder-method availability** (`builder.rs:245`), so build *order* is enforced by which
methods exist, and **spawn everything through a `BuilderContext`/`TaskExecutor`** with
`spawn_critical_with_graceful_shutdown_signal` (`pool.rs:253`) so shutdown is uniform.

## Authority note

reth = **THE compile-time `NodeTypes`/`ComponentsBuilder` SDK authority** — the B7.0.5
compile-safety half. A family is a type; consistency is a compiler theorem. nethermind is
the **runtime single-binary plugin peer** (families registered/discovered at runtime, one
binary hosts all networks — the flexibility reth trades away). besu is the **JVM plugin-SPI**
peer (`ServiceManager`/`BesuPlugin` service lookup). fukuii's B7.0.5 `given`-based
`NetworkFamily` registry wants **reth's compile-time safety with nethermind's single-binary
runtime selection** — see the takeaway below for how to get both.

## Gotchas / anti-patterns / things they later changed

- **The `is_optimism()` leak.** `EthChainSpec::is_optimism()` is a base-trait method on the
  *generic* chainspec API (`crates/chainspec/src/api.rs:54`), and the launcher branches on it
  at runtime — `launch/engine.rs:248` (`if ctx.chain_spec().is_optimism()`) and
  `launch/common.rs:974`. This is the seam where the "everything is a compile-time family"
  ideal isn't pure: instead of the OP behavior being fully dispatched through a `NodeTypes`
  associated type, a specific family's identity leaks up into the generic abstraction as a
  runtime boolean. **Lesson for fukuii:** watch for the same temptation — a
  `chainSpec.isEtc()` / `isEthPos()` runtime check in shared code is the exact anti-pattern;
  it belongs on a family-provided typeclass method (a `given` capability), not a boolean on
  the neutral spec. (This aligns with the `nomenclature.md` two-tier rule: no network-specific
  names in the shared layer.)
- **One crate per family (the tradeoff).** reth-safety costs a compiled crate per network
  (`reth-node-ethereum`, `reth-optimism-node`), and the binary is chosen at build/CLI time via
  the concrete `Node` type. There is **no** runtime "load network X into this running binary"
  — that is precisely nethermind's strength and reth's deliberate omission.
- **DB is not part of `NodeTypes` on purpose.** Because the builder injects it at launch,
  early builder states don't know the concrete DB/Provider; this is why there are three trait
  tiers (`NodeTypes` → `FullNodeTypes` → `FullNodeComponents`) instead of one — a source of
  boilerplate adapters (`NodeTypesAdapter`, `NodeTypesWithDBAdapter`, `FullNodeTypesAdapter`,
  `NodeAdapter`) that anyone porting the pattern must budget for. (Note: this vendored tree
  carries a `RocksDBProvider` path, `states.rs:19` — the DB abstraction is exactly what let
  that swap in without touching any family definition.)
- **`NodeHandle` is `#[must_use]`** (`handle.rs:9`) — dropping it without awaiting
  `wait_for_node_exit()` silently exits the node; the attribute is the guardrail.

---

### Fukuii B7.0.5 takeaway (given-registry: reth-safety + nethermind-single-binary)

reth achieves its safety with **one associated-type bundle + cross-type equality bounds +
downstream generics**. In Scala 3 the direct analog is a `trait NetworkFamily` with abstract
`type Primitives`, `type ChainSpec`, etc., where family instances are `given` values. The
piece to copy is the **cross-associated-type constraint** (reth's `types/src/lib.rs:31`): use
path-dependent types / match types so a family's chainspec and primitives are provably paired
at the `given` definition site. To *also* get nethermind's single-binary runtime selection
(which reth gives up), keep the family instances as a `Map[ChainId, NetworkFamily]` of
`given`-derived, fully-type-checked records resolved at startup — each entry compile-checked
individually (reth's guarantee), the *selection* among them done at runtime (nethermind's
flexibility). Guard against reth's own leak: never let a `family.isEtc`-style runtime boolean
appear in shared code — push every network-specific decision onto a `NetworkFamily` method so
the `given` is the single source of that behavior.
