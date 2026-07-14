# reth — multi-network

_Commit/branch documented: `3d76b93c243f8896f13a39ee865f87241fcd649b` (branch `main`). Vendored
read-only at `.claude/repo-references/clients/reth`. Documented 2026-07-13. Read-only research; no
fukuii source touched._

_Companion to `reth/consensus-engines.md` and `reth/storage-persistence.md` (documented in
parallel) — this file is the network-selection / chain-spec / family-wiring half of the story,
focused on the **compile-time-generics SDK** model. This is client 6/6, the closing pole of the
family-abstraction spectrum: geth single-family → core-geth config-schema → besu closed-if/else →
erigon compile-time-module-registry → nethermind runtime-plugin-registry → **reth
compile-time-generics SDK**._

## Architecture summary

reth is the **compile-time-generics SDK pole** of the family-abstraction spectrum. Where every
prior client selects a family from *data* at runtime — geth/besu branch on genesis keys, erigon
looks a name up in an `init()`-populated map, nethermind assembly-scans for config classes — reth
resolves the family in the **type system**. A "chain family" is a **`NodeTypes` implementation**
(`crates/node/types/src/lib.rs:27-36`): a marker struct that bundles four associated types —
`Primitives`, `ChainSpec: EthChainSpec`, `Storage`, `Payload: PayloadTypes` — into one stateless,
type-level configuration. A concrete family (Ethereum mainnet, an L2, an alt-L1) is a **separate
downstream crate** that implements `NodeTypes` for its own unit struct and then assembles a full
node from typed component builders via `ComponentsBuilder` (`crates/node/builder/src/components/
builder.rs:42`). Adding a family = **a new crate/binary, resolved at compile time** — the opposite
end from nethermind's runtime DLL plugins and erigon's blank-import `init()`.

Two things follow from this and define reth's position:

1. **Zero runtime dispatch.** There is no `switch (sealEngine)`, no `if chainConfig.Bor != nil`, no
   reflection scan. The node is a generic `NodeBuilder<Types, Components, AddOns>` where every seam
   (`ConsensusBuilder`, `NetworkBuilder`, `PayloadBuilder`, `ExecutorBuilder`, `PoolBuilder`) is a
   trait the family's crate satisfies with its own types. The compiler proves the family's parts fit
   before the binary exists.
2. **A family is its own binary.** Because the family is a *type parameter* baked into a monomorphized
   `NodeBuilder`, running Optimism means the `op-reth` binary (built from `reth-optimism-*` crates),
   running BSC means a bsc binary — not one `reth` binary that loads a family at boot. Type safety and
   modularity are total; single-binary multi-family is *not* the model.

So "a network" in reth is resolved at three layers, all compile-time except the last: the **family**
is a `NodeTypes` impl in a crate (type-level); the **component wiring** is a `ComponentsBuilder`
assembled in that crate (type-level); and only the **specific network instance** (mainnet vs sepolia
vs a custom genesis) is chosen at runtime, by the `--chain` CLI arg resolving to a `ChainSpec` value
(`crates/ethereum/cli/src/chainspec.rs:12-21`). The `ChainSpec` runtime value is the *only* part of
network selection that is data, not types — and it is scoped to one family's `ChainSpecParser`.

## Key types / interfaces / files

### `NodeTypes` — the family abstraction (four associated types)
- `crates/node/types/src/lib.rs:27-36` — **`NodeTypes`**, the whole family abstraction. Doc: "The
  type that configures the essential types of an Ethereum-like node… intended to be stateless and only
  define the types of the node." Four associated types:
  - `type Primitives: NodePrimitives` (`:29`) — block/header/body/tx/receipt types of the family.
  - `type ChainSpec: EthChainSpec<Header = …>` (`:31`) — the network-params model (see next section).
  - `type Storage: Default + Send + Sync + …` (`:33`) — writes chain primitives to storage.
  - `type Payload: PayloadTypes<BuiltPayload: BuiltPayload<Primitives = Self::Primitives>>` (`:35`) —
    engine/payload interaction, constrained back to `Primitives` so the parts must agree.
- `crates/node/types/src/lib.rs:42-45` — **`NodeTypesWithDB: NodeTypes`** adds `type DB: Database`,
  "configured by node internally and not intended to be user configurable." Db is a separate downstream
  layer so a family declares its types once and the node picks the DB.
- `crates/node/types/src/lib.rs:82-127` — **`AnyNodeTypes<P, C, S, PL>`**, a *type-level builder*: `const
  fn primitives<T>()`, `chain_spec<T>()`, `storage<T>()`, `payload<T>()` each return a re-parameterized
  `AnyNodeTypes`, and the `impl NodeTypes` (`:116-127`) binds them. This is how a family is *declared*
  without writing a hand `impl` — you assemble the four types positionally at the type level.
- `crates/node/types/src/lib.rs:185-203` — helper projections (`BlockTy`/`HeaderTy`/`TxTy`/`ReceiptTy`/
  `PrimitivesTy`/`PayloadAttrTy`) — accessors into a `NodeTypes` so downstream generic code names one
  associated type without spelling the whole bound.

### `EthChainSpec` — the network-params trait (the one runtime value)
- `crates/chainspec/src/api.rs:13-75` — **`EthChainSpec`**, the trait a family's `ChainSpec` type
  satisfies. `#[auto_impl(&, Arc)]` (`:13`) so `Arc<ChainSpec>` is itself an `EthChainSpec` (the value
  is always shared as `Arc`). Members: `chain()`/`chain_id()` (`:19-24`),
  `base_fee_params_at_timestamp()` + `blob_params_at_timestamp()` (`:27-30`), `deposit_contract()`
  (`:33`), `genesis_hash()`/`genesis_header()`/`genesis()` (`:36-48`), `bootnodes()` (`:51`),
  `is_optimism()`/`is_ethereum()` (`:53-61`, defaulted off `chain()`), `final_paris_total_difficulty()`
  (`:64`), `next_block_base_fee()` (`:67-74`, defaulted). This is the **runtime** network identity — one
  value per running network, obtained from the CLI.
- `crates/chainspec/src/api.rs:77-137` — **`impl<H: BlockHeader> EthChainSpec for ChainSpec<H>`**: the
  concrete Ethereum-lineage `ChainSpec` satisfies the trait. Note `is_optimism()` is hard-`false` here
  (`:130-132`) — OP overrides it in its own `OpChainSpec` (not vendored).
- `crates/chainspec/src/spec.rs:115,146,181,213,246` — **`MAINNET`/`SEPOLIA`/`HOLESKY`/`HOODI`/`DEV`**,
  each a `LazyLock<Arc<ChainSpec>>` built from an **embedded genesis JSON** (`include_str!("../res/
  genesis/<name>.json")`) plus a hardfork schedule (`EthereumHardfork::mainnet().into()`). The named
  networks are `static` singletons, not registry entries.
- `crates/chainspec/src/spec.rs:464` **`ChainSpec::from_genesis(genesis)`** + `:821` **`impl From<Genesis>
  for ChainSpec`** — the custom-genesis path: any `alloy_genesis::Genesis` becomes a `ChainSpec`, so a
  private network is expressed as a genesis JSON that the parser reifies into the same `ChainSpec` value.

### Fork dispatch — one tagged list, not two axes
- `crates/chainspec/src/spec.rs` uses a single `ChainHardforks` list where **each fork carries a
  `ForkCondition`** — `ForkCondition::Block(n)` (`:272`), `ForkCondition::Timestamp(t)` (`:277`),
  `ForkCondition::TTD{…}` (`:285`), `ForkCondition::Never`. Block-number forks and timestamp forks live
  in **one ordered list**, each self-describing its activation axis. Contrast fukuii's
  `EvmConfig.forBlock(block)` vs `forBlock(block, timestamp)` **overload split** (`AGENTS.md`): reth does
  not choose a dispatch *method* per family — a fork is `(Hardfork, ForkCondition)` and the condition
  *is* the axis. This is a third way to unify block-vs-timestamp dispatch, alongside nethermind's
  `AddTransitions(blockNumbers, timestamps)` two-set union (`../nethermind/multi-network.md`): reth tags
  per fork, nethermind partitions into two sets. Both collapse the caller's "which overload?" choice.

### The SDK / builder assembly (the headline "build your own node")
- `crates/node/builder/src/components/builder.rs:42-49` — **`ComponentsBuilder<Node, PoolB, PayloadB,
  NetworkB, ExecB, ConsB>`**: a struct of five typed sub-builders (`pool_builder`, `payload_builder`,
  `network_builder`, `executor_builder`, `consensus_builder`) + a `PhantomData<Node>`. Each `.pool(…)`/
  `.executor(…)`/`.payload(…)`/`.network(…)`/`.consensus(…)` swaps one type parameter — the whole node's
  component set is a single generic type the compiler checks.
- `crates/node/builder/src/components/builder.rs:428-460` — **`NodeComponentsBuilder<Node>`** trait +
  `build_components()` — the seam that turns the five typed sub-builders into a `Components` value.
- `crates/node/builder/src/components/mod.rs:37-64` — **`NodeComponents<T: FullNodeTypes>`**: the
  assembled result — `Pool`/`Evm`/`Consensus`/`Network` associated types + a `payload_builder_handle()`.
- The individual builder traits (each a one-method trait a family's crate implements — **or a bare
  closure**, via a blanket impl):
  - `components/consensus.rs:9-17` — **`ConsensusBuilder`** (`build_consensus(ctx) -> Consensus`), with a
    blanket impl for any `FnOnce(&BuilderContext) -> Future` (`:19-35`) — a builder can just be a closure.
  - `components/network.rs:11` — **`NetworkBuilder`**.
  - `components/payload.rs:14,50` — **`PayloadServiceBuilder`** / **`PayloadBuilderBuilder`**.
  - `components/execute.rs:7` — **`ExecutorBuilder`**.
  - `components/pool.rs:16` — **`PoolBuilder`**.
- `crates/node/builder/src/node.rs:33-52` — **`Node<N: FullNodeTypes>: NodeTypes + Clone`**, the
  "preconfigured components" bundle: `type ComponentsBuilder: NodeComponentsBuilder<N>` (`:35`), `type
  AddOns: NodeAddOns<…>` (`:38`), `fn components_builder()` (`:43`), `fn add_ons()`, `fn
  disabled_stages()`. A `Node` is a `NodeTypes` (a family) that *also* carries its default component
  wiring — the thing `NodeBuilder` consumes to produce a running node.
- `crates/node/builder/src/node.rs:56-88` — **`AnyNode<N, C, AO>`**, the runtime-composition escape
  hatch: `.types::<T>()` / `.components_builder(value)` / `.add_ons(value)` build a `Node` from parts
  without a hand-written struct — the "assemble a node inline" path the examples use.

### A concrete family, fully assembled — `EthereumNode`
- `crates/ethereum/node/src/node.rs:70-71` — **`pub struct EthereumNode;`** — a *unit struct*. The
  entire family is a zero-size type; all its content is in the trait impls.
- `crates/ethereum/node/src/node.rs:128-133` — **`impl NodeTypes for EthereumNode`**: `Primitives =
  EthPrimitives`, `ChainSpec = ChainSpec`, `Storage = EthStorage`, `Payload = EthEngineTypes`. Four
  lines declare the family.
- `crates/ethereum/node/src/node.rs:75-100` — **`EthereumNode::components::<Node>()`**: returns a
  `ComponentsBuilder` pre-wired with `EthereumPoolBuilder` / `BasicPayloadServiceBuilder<
  EthereumPayloadBuilder>` / `EthereumNetworkBuilder` / `EthereumExecutorBuilder` /
  `EthereumConsensusBuilder`. The family's default component set, as types.
- `crates/ethereum/node/src/node.rs:442-456` — **`impl Node for EthereumNode`**: `type ComponentsBuilder
  = ComponentsBuilder<…>`, `fn components_builder() { Self::components() }`. This is the full pattern: a
  unit struct + a `NodeTypes` impl (4 types) + a `Node` impl (default components) = a family.

### Network selection at runtime — the one data-driven layer
- `crates/cli/cli/src/chainspec.rs:35-71` — **`ChainSpecParser`** trait: `type ChainSpec`, `const
  SUPPORTED_CHAINS: &[&str]`, `fn parse(s) -> Arc<ChainSpec>`, and a clap `parser()`. **Each family ships
  its own parser** — network selection is per-family, not global.
- `crates/cli/cli/src/chainspec.rs:74-89` — **`parse_genesis(s)`**: tries to read `s` as a file path, else
  treats `s` as inline JSON (must contain `{`), deserializes into an `alloy_genesis::Genesis`. The custom
  chain path.
- `crates/ethereum/cli/src/chainspec.rs:6` — **`SUPPORTED_CHAINS = ["mainnet","sepolia","holesky","hoodi",
  "dev"]`** (first = default).
- `crates/ethereum/cli/src/chainspec.rs:12-21` — **`chain_value_parser(s)`**: `match s` against the five
  known names → the `LazyLock` `ChainSpec` static; **`_ => Arc::new(parse_genesis(s)?.into())`** — an
  unknown value is a genesis file/JSON reified into a `ChainSpec` via `From<Genesis>`. `:26-36`
  **`EthereumChainSpecParser`** binds this into the trait. `--chain <name|path|json>` is the sole runtime
  network knob, and it is scoped to the Ethereum family.

### "Different family, same reth core" — the worked examples
- `examples/bsc-p2p/src/chainspec.rs:12-60` — **`hardfork!(BscHardfork { Ramanujan, Niels, … Prague })`**:
  BSC declares its **own 21-fork enum** via reth's `hardfork!` macro. Comment `:14-15`: "it's still
  expected to mix with `EthereumHardfork`." `:64-101` **`bsc_mainnet()`** builds a `ChainHardforks` list
  interleaving `EthereumHardfork::*` and `BscHardfork::*` entries, each tagged
  `ForkCondition::Block`/`Timestamp`. `:104-127` **`bsc_chain_spec()`** constructs an `Arc<ChainSpec>`
  for `Chain::BinanceSmartChain` **reusing reth's core `ChainSpec` struct** — a genuinely foreign chain
  family expressed as data over the *same* core types. This is the SDK claim proven: a different family
  is a downstream crate that reuses core, not a fork of core.
- `examples/polygon-p2p/` — a second "foreign family, same reth core" p2p example (Polygon).
- `examples/custom-node-components/`, `examples/node-builder-api/` — the builder-assembly examples: swap
  one component (a custom pool/EVM/consensus) into an otherwise-stock node by passing a different typed
  builder to `ComponentsBuilder`.

## Design decisions & rationale

- **The family is a type, resolved by the compiler — not data, resolved at runtime.** `NodeTypes`
  (`node/types/src/lib.rs:27-36`) makes a chain family a set of four associated types. The rationale is
  total type safety: the four parts (`Primitives`/`ChainSpec`/`Storage`/`Payload`) are constrained to
  agree (`Payload::BuiltPayload::Primitives = Self::Primitives`, `:35`), so a mismatched assembly fails
  to compile. There is no runtime "unknown seal engine" throw (nethermind) or "silent first branch"
  (besu/geth) because an ill-formed family is not a representable program.
- **The node is a generic builder over typed component seams.** `ComponentsBuilder` + the five builder
  traits (`ConsensusBuilder`/`NetworkBuilder`/`PayloadServiceBuilder`/`ExecutorBuilder`/`PoolBuilder`)
  let a family override any component while reusing the rest, monomorphized. The reusable "build your own
  node" story: swap one typed builder, keep the others (`examples/custom-node-components`). The blanket
  closure impls (`consensus.rs:19-35`) mean a trivial component is a one-liner lambda, a full one is a
  struct — same trait.
- **A family = a downstream crate/binary, deliberately.** Because the family threads through the type
  system, `op-reth` and a bsc binary are *distinct* monomorphized programs, not one binary switching
  families at boot. reth accepts many binaries as the cost of zero-cost, compile-checked modularity. The
  headline is "reth as a library/SDK: import the crates, implement the traits, get a node" — not "one
  binary that runs every chain."
- **Only the network *instance* is runtime data.** The single data-driven seam is `ChainSpec`, selected
  by `--chain` through a per-family `ChainSpecParser` (`ethereum/cli/src/chainspec.rs`). A custom network
  is a genesis JSON reified via `From<Genesis> for ChainSpec` (`spec.rs:821`). reth keeps the *what
  family* decision in types and the *which network of that family* decision in data — a clean split.
- **Fork dispatch is a per-fork tagged condition, not a family method.** One `ChainHardforks` list with
  `ForkCondition::Block|Timestamp|TTD|Never` per entry (`spec.rs:272-285`, `bsc chainspec:66-99`) means a
  block-number fork and a timestamp fork coexist in one schedule with no overload and no per-family
  dispatch choice — the axis is data on each fork.
- **`alloy-*` primitives as the shared substrate.** `ChainSpec`, `Genesis`, `Chain`, `EthereumHardfork`,
  the header types are `alloy_*`/`reth_*` library crates a foreign family imports and builds on (the bsc
  example imports `reth_chainspec::{ChainSpec, make_genesis_header, EthereumHardfork, hardfork!, …}`).
  Modularity is realized as *a crate graph another chain depends on*, the literal SDK shape.

## Notable patterns (the reusable idea)

1. **Compile-time-generics family abstraction (`NodeTypes`).** A chain family is four associated types on
   a stateless marker trait, assembled at the type level (`AnyNodeTypes` const-fn builder). The nameable
   pattern for the observations table: reth's "a family is a type, checked by the compiler, zero runtime
   dispatch" pole — the type-safety end of the spectrum, strictly beyond nethermind's runtime reflection.
2. **Typed component-builder SDK (`ComponentsBuilder` + the five builder traits).** Assemble a node from
   swappable typed sub-builders (pool/payload/network/executor/consensus), each a one-method trait
   satisfiable by a struct *or* a bare closure. The "build your own node" mechanism.
3. **`Node` = `NodeTypes` + default components.** The bundle a `NodeBuilder` consumes: a unit struct with
   a 4-line `NodeTypes` impl and a `Node` impl naming its default `ComponentsBuilder`
   (`EthereumNode`, `node.rs:128-133,442-456`). The minimal template for declaring a whole family.
4. **Per-family `ChainSpecParser`; custom network = `From<Genesis>`.** Runtime network selection is one
   `--chain` arg resolved by the family's own parser (`SUPPORTED_CHAINS` names or a genesis file/JSON
   reified into a `ChainSpec`). Network *instance* is the only data layer; family is types.
5. **Per-fork `ForkCondition` tagging** as the block-vs-timestamp unifier — a third design point next to
   nethermind's two-set `AddTransitions` and fukuii's overload split.
6. **"Foreign family, same core" as a downstream crate** (`bsc-p2p`, `polygon-p2p`): a real non-Ethereum
   chain declares its own `hardfork!` enum and `ChainSpec` builder while reusing reth's core `ChainSpec`
   struct — modularity proven by a crate that imports core, not forks it.

## Position on the pluggability spectrum

reth is the **compile-time-generics SDK pole** — the type-safety endpoint, opposite the single-family
start:

- **vs geth / core-geth (single-family / config-schema):** not comparable on mechanism. geth is one
  family with a vestigial clique option; core-geth adds a `Configurator` config-schema polymorphism over
  a closed 3-engine enum (`../core-geth/multi-network.md`). reth makes the *family itself* a type
  parameter — the family is not a config branch, it's a `NodeTypes` impl in a crate.
- **vs besu (closed if/else over genesis keys):** besu's `fromGenesisFile` is a hand-maintained runtime
  dispatch you edit to add a mechanism (`../besu/multi-network.md`). reth has *no* dispatch: the family is
  chosen when you pick which crate/binary to build. besu remains the private-PoA-origination authority
  (`generate-blockchain-config`, `extraData` validator encoding); reth is not that.
- **vs erigon (compile-time `init()`+blank-import module registry):** the interesting near-comparison.
  erigon is *also* compile-time (`../erigon/multi-network.md` — a family is linked in via `import _
  ".../polygon/chain"`), but it selects at runtime from a `map[string]Spec` populated by `init()`, and
  its abstraction *leaks* (`FrozenBorBlocks` on the shared reader). reth pushes the same compile-time
  idea all the way into the type system: no map, no blank-import side effect, no shared-reader leak — the
  family's concerns stay in its own `NodeTypes`/component impls, monomorphized. reth is one tier more
  decoupled *and* type-checked than erigon's module registry.
- **vs nethermind (runtime self-declaring plugin registry):** the two poles, and the decisive contrast.
  nethermind reaches "add a family without editing shared dispatch" through **runtime reflection** —
  assembly-scan for `IChainSpecEngineParameters`, match the chainspec's open `engine` block, self-enable
  plugins, all in **one binary** at boot (`../nethermind/multi-network.md`). reth reaches the *same* goal
  through **compile-time generics** — `NodeTypes` associated types, typed component builders, checked by
  the compiler, in a **per-family binary**. Same objective, opposite mechanism:
  - nethermind: runtime flexibility (drop a DLL, one binary runs any configured family), at the cost of
    reflection fragility (trimming/AOT tension, `EngineName`/`SealEngineType` can silently disagree, two
    tiers to keep in sync).
  - reth: compile-time guarantees (a malformed family doesn't compile, zero dispatch cost), at the cost
    of **one binary per family** and no runtime family-switching.

  Neither dominates; they trade runtime-flexibility against compile-time-safety, and single-binary
  against many-binaries.

## Authority note

**For `multi-network`, reth is THE compile-time-generics / SDK-modularity authority — the type-safety
end of the family-abstraction spectrum.** It is the client to study for:

- **The typed family abstraction** (`NodeTypes` four associated types, `AnyNodeTypes` type-level builder,
  the `Payload::Primitives = Primitives` cross-constraint) — the reference for "make the family a checked
  type, not a runtime branch."
- **The component-builder SDK** (`ComponentsBuilder` + `ConsensusBuilder`/`NetworkBuilder`/
  `PayloadBuilder`/`ExecutorBuilder`/`PoolBuilder`, each struct-or-closure) — the reference for "assemble
  a node from swappable typed seams," directly relevant to fukuii's DESIGNED-not-built production-side
  seams (Sealer / ValidatorProvider / BlockInterface — memory: file-tree-seam-direction).
- **`Node` = `NodeTypes` + default components** (`EthereumNode`'s unit-struct + two impls) — the minimal
  template for declaring a full family.
- **Per-family `ChainSpecParser` + `From<Genesis>` custom networks** — network *instance* as the one
  runtime-data layer, family as types.

Authority caveats to surface at Phase 4:

- **Not the ETC/PoW consensus-content authority** (core-geth — ETChash/ECIP-1017/1099/1111/1122; reth
  dropped standalone PoW entirely, so aligning ETC code toward reth is a regression per the README
  authority model) **nor the canonical ETH/PoS baseline** (go-ethereum). reth is the authority for the
  *modularity mechanism*, not any family's rules.
- **Compile-time generics are a Rust-native mechanism.** fukuii is Scala 3 / JVM: the *pattern*
  (family-as-typeclass, node-as-generic-over-typed-components, compiler-checked assembly) transfers, but
  the *mechanism* is `given`/`using` typeclass instances and higher-kinded bounds, not Rust associated
  types + monomorphization. Adopt the shape (type-safe family selection), not the trait syntax.
- **A family = a binary in reth.** fukuii is a **single JVM binary**. reth's "one binary per family" is
  the one aspect that does *not* transfer — see the synthesis below.

## fukuii synthesis — the closing of the spectrum

reth closes the family-abstraction spectrum at the type-safety pole. The full spectrum, both axes named:

| Client | Mechanism | Binary model | Dispatch |
|--------|-----------|--------------|----------|
| go-ethereum | single family | one binary | n/a (one family) |
| core-geth | config-schema (`Configurator`) | one binary | runtime, closed engine enum |
| besu | genesis-key if/else | one binary | runtime, hand-maintained dispatch |
| erigon | compile-time module registry (`init()`+blank-import) | one binary | runtime map lookup, leaky |
| nethermind | runtime plugin registry (reflection) | **one binary** | runtime reflection, zero central edit |
| **reth** | **compile-time generics (`NodeTypes` SDK)** | **many binaries** | **none — resolved in types** |

**For fukuii (single-binary JVM), neither pole is a drop-in.** nethermind's runtime reflection is a .NET
mechanism (assembly scanning, trimming-fragile) and reth's compile-time generics force one binary per
family — fukuii wants a *single* binary that can run PoW/ETC and PoS/ETH (and future PoA) families.

**The practical target is a nethermind-style `given`-based typeclass registry: compile-time-safe like
reth, single-binary like nethermind's intent.** Concretely:

- Model each family as a Scala 3 **typeclass instance** (a `given NetworkFamily` / engine-params
  instance) the way reth models it as a `NodeTypes` impl — so the compiler checks that a family's parts
  agree (reth's type safety), without reth's per-binary constraint.
- Select the *active* family at runtime from the loaded chain config (nethermind's single-binary
  intent), but resolve the family's *components* through `given` summoning at the seams — the Scala
  analogue of reth's typed `ComponentsBuilder`, not a runtime reflection scan.
- Keep network *instance* selection (mainnet/testnet/custom genesis) as runtime data behind a
  per-family parser, exactly as reth scopes `ChainSpecParser` to a family and reifies custom genesis via
  `From<Genesis>`.
- Adopt reth's **per-fork tagged condition** (or nethermind's two-set `AddTransitions`) to collapse
  fukuii's `forBlock(block)` / `forBlock(block, timestamp)` overload split into one family-neutral seam.

That is the synthesis the spectrum points to: **reth's compile-time type-safety, achieved with `given`
instances, retained inside nethermind's single-binary runtime-selection** — the DESIGNED-not-built
production-side seams (Sealer / ValidatorProvider / BlockInterface) become `given`-summoned typed
components, one binary, compiler-checked. (Forward-ref only — the Phase-3/4 fukuii audit decides; this
file states the target, not a verdict on fukuii's current code.)

## Gotchas / anti-patterns / things they later changed

- **One binary per family is the model's cost.** reth's total type safety buys many binaries: `op-reth`,
  a bsc binary, etc. are distinct programs. Do **not** cite reth as a single-binary-multi-family
  reference — it is the opposite of that. For single-binary multi-family, nethermind's runtime registry
  is the reference; reth is the type-safety reference.
- **OP-stack crates are NOT vendored in this snapshot.** `crates/optimism/` is absent (confirmed empty).
  The canonical "second family" — `reth-optimism-*` with `OpNode`, `OpChainSpec` (overriding
  `is_optimism()`, cf. `api.rs:53-56,130-132`), `OpChainSpecParser` — lives outside this vendored copy.
  The `bsc-p2p` and `polygon-p2p` **examples** are the in-repo "foreign family, same core" evidence;
  document the OP pattern from them + the `is_optimism()` hook, and say the OP crates aren't here.
- **`is_optimism()` is a hard-coded discriminator on the trait.** `EthChainSpec::is_optimism()`
  (`api.rs:53-56`) defaults off `chain().is_optimism()` and the Ethereum `ChainSpec` returns hard-`false`
  (`:130-132`). A family-specific boolean baked into the shared trait is a small leak of one family's
  identity into the common abstraction — reth's analogue of erigon's `FrozenBorBlocks`, though far
  narrower (a defaulted predicate vs a shared-reader method).
- **The type-parameter chain is deep.** `NodeTypes` → `FullNodeTypes` → `Node<N>` → `ComponentsBuilder<
  Node, PoolB, PayloadB, NetworkB, ExecB, ConsB>` → `NodeAdapter` threads many type parameters; the
  generics are powerful but the error messages and the learning curve are steep (the recurring reth-PR
  pattern "make X generic over chainspec" — CLAUDE.md examples — shows the codebase is *still*
  generalizing concrete types years in). A Scala port must weigh `given`-inference limits against this.
- **Custom genesis is Ethereum-`ChainSpec`-shaped.** `chain_value_parser`'s fallback reifies a genesis
  into the Ethereum `ChainSpec` (`ethereum/cli/src/chainspec.rs:19`). A *foreign-family* custom network
  needs that family's own parser/`ChainSpec` (bsc builds its own) — a genesis JSON alone does not select
  a non-Ethereum family the way nethermind's open `engine` block does. Family is types; genesis only
  parameterizes the family you already compiled in.
- **No runtime ambiguity guard is needed — or possible.** nethermind's `CalculateSealEngineType` throws
  on >1 or 0 seal engines (`../nethermind/multi-network.md`); reth has no equivalent because a program
  has exactly one compiled-in family — the ambiguity nethermind guards against at runtime is unrepresentable
  in reth. A trade-off, not a gap: reth cannot be *told at runtime* to run a different family at all.
