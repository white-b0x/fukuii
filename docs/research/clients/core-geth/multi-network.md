# core-geth — multi-network

_Commit/branch documented: `b28aa0a0bbb1e3ba72ce11afb9310d9dc38c1832` (branch `main`). Vendored at
`.claude/repo-references/clients/core-geth`. Documented 2026-07-13._

_**Re-verified against `upstream` 2026-07-13** (SHA `4185df450`, the **deprecated Sept-2024**
last-independent core-geth branch). **Attribution correction:** the vendored tree is `main`, which
carries **+65 commits of fukuii's own ETC modernization** on top of upstream. The `Configurator`
interface, two-schema abstraction, `Crush` conversion, and closed-3-engine model documented here are
genuine **upstream** core-geth. But the **Olympia getter methods** on `ProtocolSpecifier`
(`GetOlympiaTreasuryAddress`, `GetSpiralGasTarget`, `GetOlympiaGasTarget`, `GetBaseFeeMinValue`,
`GetTxPoolPriceLimit`, `GetEIP7939Transition`) are **fukuii's `main` overlay — absent from
`upstream`** (verified via `grep upstream:params/types/ctypes/configurator_iface.go`). The claim that
core-geth is the sole authority for "ETC config content *including* Olympia" was the contamination;
Olympia config content is fukuii's, not upstream core-geth's. Corrected inline below._

## Architecture summary
core-geth (a multi-geth-heritage go-ethereum fork) is the reference client where multi-network support is
**first-class**, and it is where it diverges most sharply from upstream geth. Where geth models a network as
a single concrete `*params.ChainConfig` value (see `../go-ethereum/multi-network.md`), core-geth models a
network as any value satisfying the **`Configurator` interface** (`params/types/ctypes/configurator_iface.go:39-51`)
— a large getter/setter contract covering every EIP/ECIP transition, the consensus engine, and the genesis
block. **Two** concrete config schemas implement that one interface: `coregeth.CoreGethChainConfig`
(`params/types/coregeth/chain_config.go:32`), the ETC-native per-EIP-transition schema, and
`goethereum.ChainConfig` (`params/types/goethereum/goethereum.go:28`), the upstream-compatible fork-named
schema. A genesis holds the **interface**, not a struct — `genesisT.Genesis.Config ctypes.ChainConfigurator`
(`params/types/genesisT/genesis.go:43`) — so a running node is polymorphic over config schema. A
reflection-based `Crush` routine (`params/confp/convert.go:47`) translates any configurator into any other by
pairing `Get*`/`Set*` methods, and raw-JSON schema auto-detection (`confp/generic.UnmarshalChainConfigurator`,
`params/confp/generic/generic.go`) picks the right schema when loading a genesis file. This is genuinely a
**"chain family / config-schema abstraction layer"** that geth lacks — but it stops short of the pluggable
consensus-*family* registry that besu/nethermind offer (see Authority note): the consensus engine is still a
**closed 3-value enum** (ethash / clique / lyra2), not an extensible plugin point.

## Key types / interfaces / files
- `params/types/ctypes/configurator_iface.go:39-51` — **`Configurator = ChainConfigurator + GenesisBlocker`**,
  and `ChainConfigurator = String + ProtocolSpecifier + Forker + ConsensusEnginator`. This is the whole
  multi-network seam: a network is anything answering this method set. Every method is a **Get/Set pair**
  (the doc comment `:29-37` documents the convention; `MustSet*` may fatally error, fork methods are
  suffixed `Transition` and take `*uint64` so nil = unset).
- `params/types/ctypes/configurator_iface.go:55-343` — **`ProtocolSpecifier`**: the flat, per-EIP transition
  surface — `GetEIP150Transition`… and ETC-specific `GetECIP1080Transition` / `GetECBP1100Transition` (MESS).
  Each fork is an individually-addressable transition, not a named hardfork bundle — the ETC-native model.
  **(fukuii `main` overlay, NOT upstream:** this interface is *extended on `main`* with the Olympia getters
  `GetOlympiaTreasuryAddress` (ECIP-1112) / `GetSpiralGasTarget` / `GetOlympiaGasTarget` (ECIP-1121) /
  `GetBaseFeeMinValue` (ECIP-1111) / `GetTxPoolPriceLimit` (ECIP-1122) / `GetEIP7939Transition` — all
  **absent from `upstream`**. They are fukuii's own additions to the `ProtocolSpecifier` contract, not
  core-geth reference methods.)**
- `params/types/ctypes/configurator_iface.go:358-436` — **`ConsensusEnginator`**: `GetConsensusEngineType()`
  / `MustSetConsensusEngineType(t)` plus the per-engine sub-interfaces `EthashConfigurator`,
  `CliqueConfigurator`, `Lyra2Configurator`. This is the positive engine-selection contract geth's config
  layer lacks.
- `params/types/ctypes/types.go:269-305` — **`ConsensusEngineT`** enum: `Unknown`, `Ethash`, `Clique`,
  `Lyra2`, with `String()` and `IsEthash()/IsClique()/IsLyra2()/IsUnknown()` predicates. A **closed** set —
  the ceiling of engine pluggability in this client.
- `params/types/coregeth/chain_config.go:32` — **`CoreGethChainConfig`**: the ETC-native schema, one nullable
  `*big.Int` field per EIP (`EIP2FBlock`, `EIP160FBlock`, `EIP170FBlock`, …) rather than per-hardfork; the
  struct doc (`:29-31`) repeats geth's "stored per-block, any network identified by its genesis can have its
  own options" line — but here it's one of *two* schemas, not the only one.
- `params/types/goethereum/goethereum.go:28-106` — **`goethereum.ChainConfig`**: the upstream-compatible
  schema, fork-named block fields (`HomesteadBlock`, `ByzantiumBlock`, … `CancunBlock`) + timestamp forks
  (`ShanghaiTime`…`VerkleTime`) + engine sub-objects `Ethash/Clique/Lyra2`. Lets core-geth ingest a stock
  geth genesis JSON unchanged.
- `params/types/genesisT/genesis.go:43` — **`Genesis.Config ctypes.ChainConfigurator`** — the single most
  important comparative fact: the genesis carries the **interface**, so the concrete schema is chosen at
  load time, not baked into the type. (geth: `core.Genesis.Config *params.ChainConfig`, concrete.)
- `params/types/genesisT/gen_genesis.go:65-105` + `params/confp/generic/generic.go`
  (`UnmarshalChainConfigurator`) — **JSON schema auto-detection**: probes the raw genesis JSON for
  schema-distinguishing keys (coregeth-sufficient vs goethereum-sufficient, with mutual "must-not" negation)
  and unmarshals into whichever schema matches, then stores it behind the interface.
- `params/confp/convert.go:47-198` — **`Crush(dest, source, crushZeroValues)`** + the reflection helper
  `crush(k, …)`: walks an interface's methods, and for every `Get<X>` with a matching `Set<X>` copies the
  value source→dest. Runs the copy per sub-interface (`GenesisBlocker`, `ProtocolSpecifier`, `Forker`, then
  `ConsensusEnginator` gated on `GetConsensusEngineType()`). This is how a `coregeth` config and a
  `goethereum` config become interchangeable.
- `params/confp/convert.go:28-42` — **`CloneChainConfigurator`**: `reflect.New` the same concrete type, then
  `Crush` into it — a deep copy that goes through the interface.
- `params/confp/configurator.go:171-186` — **`IsValid(conf, head)`**: interface-level validation (NetworkID
  non-nil; EIP155 ⇒ ChainID non-nil) usable against *any* configurator.
- `params/confp/configurator.go:194-296,540-576` — **`Compatible` / `isBlockForked` / `isTimeForked`**:
  config-compatibility + the block-vs-timestamp fork-activation primitives, all defined over the interface.
- `params/config_classic.go:36-142` (**`ClassicChainConfig`**, ChainID 61, NetworkID 1, `Ethash`),
  `params/config_mordor.go:33` (**`MordorChainConfig`**, ChainID 63, `Ethash`),
  `params/config_mintme.go:28-31` (**`MintMeChainConfig`**, ChainID 24734, **`Lyra2`** engine),
  `params/config_classic.go:148` (**`MessNetConfig`**, ChainID 6161) — the ETC-family built-ins, all
  `*coregeth.CoreGethChainConfig`. Upstream Mainnet/Sepolia/Holesky live alongside as `goethereum.ChainConfig`.
- `params/genesis_classic.go:27` / `genesis_mordor.go:27` / `genesis_mintme.go:43` — **`DefaultClassicGenesisBlock`
  / DefaultMordorGenesisBlock / DefaultMintMeGenesisBlock**: each pairs the config var with genesis
  header + `DecodePreAlloc(...)` alloc, returning a `*genesisT.Genesis`.
- `cmd/utils/flags.go:170-186` (network flags `--classic`/`--mordor`/`--mintme`), `:2522-2540`
  (**`genesisForCtxChainConfig`** flag→genesis switch), `:2543` (`MakeGenesis`), `:1214-1258` (datadir +
  bootnode selection per flag) — network selection is a CLI-flag switch, same shape as geth but with ETC
  networks as co-equal cases.
- `core/genesis.go:424-440` — genesis-hash → default-genesis recovery switch (`MainnetGenesisHash`,
  `SepoliaGenesisHash`, `MordorGenesisHash`, `MintMeGenesisHash`, `HoleskyGenesisHash`): genesis-hash-as-
  identity, inherited from geth, spanning both families.

## Design decisions & rationale
- **A network is an interface value, not a struct.** The defining choice. `genesisT.Genesis.Config` is
  `ctypes.ChainConfigurator` (`genesis.go:43`), so the same node code operates over either the ETC-native
  `coregeth` schema or the upstream `goethereum` schema without knowing which. This is the "chain family"
  abstraction layer geth omits — geth's `Genesis.Config` is the concrete `*params.ChainConfig`.
- **Two schemas, one contract — to be simultaneously ETC-native and geth-compatible.** `coregeth.CoreGethChainConfig`
  exposes forks as individual EIP-transition fields (`EIP160FBlock`, `ECIP1017FBlock`, …) — the granularity
  ETC needs because its fork schedule (Atlantis→…→Olympia) doesn't line up with ETH's named hardforks —
  while `goethereum.ChainConfig` mirrors upstream's fork-named fields so a stock geth genesis JSON loads
  unchanged. Both satisfy the `Configurator` interface, so the rest of the client is schema-agnostic.
- **Getter/Setter method-pair convention enables reflection conversion.** Because every config value is a
  `Get<X>`/`Set<X>` pair (`configurator_iface.go:29-37`), `crush` (`convert.go:150-198`) can copy any
  configurator into any other purely by method-name pairing — no per-schema translation table. This is what
  makes the two-schema design tractable: `coregeth ⇄ goethereum` conversion is generic.
- **Positive, typed engine selection with an explicit `Unknown`.** `ConsensusEngineT`
  (`types.go:269-305`) + `GetConsensusEngineType`/`MustSetConsensusEngineType`
  (`configurator_iface.go:359-360`) make the consensus engine a first-class, settable property. Presence of
  a typed engine sub-object (`Ethash`/`Clique`/`Lyra2`) selects it — and `coregeth`'s getter returns
  `Unknown` when none is set (`coregeth/chain_config_configurator.go:967-978`), a **no-implicit-default**
  posture that is strictly safer than geth for a multi-family client.
- **Three co-equal engines in one binary.** ethash (ETC/PoW), clique (PoA), and **lyra2** (MintMe, an
  entirely non-Ethereum PoW variant — `config_mintme.go:31`) ship together and are chosen by config. This is
  real multi-consensus-family support that geth (effectively ethash-only + a vestigial clique) does not have.
- **Schema auto-detection instead of a schema tag.** Rather than requiring the genesis JSON to name its
  schema, `UnmarshalChainConfigurator` (`generic/generic.go`) sniffs distinguishing keys. Keeps both stock
  geth genesis files and core-geth-native files loadable through one path.
- **Genesis-hash-as-identity retained from geth.** core-geth keeps geth's content-addressed datadir identity
  (`core/genesis.go:424-440` recovery switch) — the multi-schema layer sits *above* that mechanism, not
  instead of it.

## Notable patterns (the reusable idea)
1. **Config-as-interface (`Configurator`) with multiple concrete schemas.** The nameable pattern for the
   observations table: a network is an interface value; N schemas implement it; the node is polymorphic over
   schema. This is the pole opposite geth's "genesis *is* one concrete struct."
2. **Getter/Setter-pair contract → generic reflection conversion (`Crush`).** Uniform `Get<X>`/`Set<X>`
   naming turns cross-schema translation into a method-name walk, no hand-written mapping.
3. **Typed consensus-engine enum + `MustSet` contract** as a positive, no-default engine selector (contrast
   geth's `else-means-ethash` fallthrough).
4. **Raw-JSON schema auto-detection** (probe distinguishing keys) to accept multiple config dialects through
   one loader.
5. **Per-EIP transition fields** (vs per-hardfork bundles) as the config granularity a non-ETH fork schedule
   needs.

## Position on the pluggability spectrum
core-geth sits **between** geth and besu/nethermind:
- **Stronger than geth**: a real family-abstraction interface (`Configurator`), two first-class config
  schemas, three co-equal consensus engines, generic cross-schema conversion, and no implicit default engine
  in the ETC schema.
- **Weaker than a plugin registry**: the consensus engine is a **closed enum** (`ConsensusEngineT`,
  `types.go:271-276`) — adding a family means editing that enum, the `ConsensusEnginator` sub-interfaces, and
  `CreateConsensusEngine`. That factory still dispatches on concrete config pointers, not the interface:
  `eth/ethconfig/config.go:238` `CreateConsensusEngine(…, ethashConfig, cliqueConfig, lyra2Config, …)`
  branches `if cliqueConfig != nil … else if lyra2Config != nil … else ethash`, then wraps in
  `beacon.New(engine)`. So it is **"multiple config SCHEMAS + a fixed set of engines as first-class,"** not
  **"a pluggable consensus-family registry."** The richer family pluggability is besu (genesis positively
  selects ethash/clique/ibft2/qbft) and nethermind (self-declaring seal-engine plugins) — forward-ref, no
  verdict here.

## Authority note
**For `multi-network`, core-geth is a STRONGER authority than go-ethereum, and the authority for the
ETC-specific config content geth omits.** geth is single-family ("different testnets of one family via one
`ChainConfig`", per `../go-ethereum/multi-network.md`); core-geth adds the genuine family-abstraction layer —
the `Configurator` interface, two interchangeable config schemas, and three co-equal engines — which is
directly relevant to fukuii's own multi-network / `NetworkFamily` config model (PoW ETC + PoS ETH as
co-equal families). It is also the authority for the **pre-Olympia ETC fork/ECIP config content**: the per-EIP
transition granularity, ECIP-1017 emission fields, and ECBP-1100/MESS — all present in `upstream`.
**The Olympia (ECIP-1111/1112/1121/1122) transition/treasury/gas-target/tip-floor fields
(`configurator_iface.go:144-171`, `config_classic.go:112-136`) are NOT upstream core-geth — they are
fukuii's own `main` overlay** (verified absent from `upstream`; the earlier "sole authority incl.
Olympia" phrasing attributed fukuii's modernization to the reference client and is corrected here).
For Olympia config content there is no core-geth reference to defer to; fukuii is authoring it.

It is **not** the authority for the deepest form of the concern — a pluggable consensus-*family* registry
where a network registers its own consensus/rules module. That is **besu / nethermind** (forward-ref, no
verdict): core-geth's engine set is a closed enum, not an extension point. Position core-geth as the
**"config-schema pluggability"** pole: multiple chain *configs*/schemas as first-class, one tier below
besu/nethermind's consensus-family pluggability. (For the ETH/PoS baseline config *content* — timestamp
forks, blob schedule, the merge — the authority remains go-ethereum, which core-geth vendors as the
`goethereum` schema.)

## Gotchas / anti-patterns / things they later changed
- **The two schemas disagree on the no-engine default — a real inconsistency.**
  `coregeth.GetConsensusEngineType()` returns `ConsensusEngineT_Unknown` when no engine is set
  (`coregeth/chain_config_configurator.go:967-978`), but `goethereum.GetConsensusEngineType()` **falls
  through to `ConsensusEngineT_Ethash`** (`goethereum/goethereum_configurator.go:948-956`) — inheriting
  geth's `else-means-ethash` footgun. So the same "no engine configured" state means *unknown* in one schema
  and *ethash* in the other. A multi-family config layer wants the `coregeth` (no-default) behavior
  uniformly; the `goethereum` default is a landmine carried over from upstream.
- **Adding a consensus family is not a plugin operation.** `ConsensusEngineT` is a closed enum
  (`types.go:271-276`) and `CreateConsensusEngine` (`eth/ethconfig/config.go:238`) branches on concrete
  config pointers. A new family (e.g. an IBFT/QBFT PoA) requires editing the enum, adding a
  `*Configurator` sub-interface, extending `Crush`'s engine switch (`convert.go:126-144`), and the factory —
  not registering a module. Do not cite core-geth as a plugin-architecture reference; it is a
  fixed-set-of-schemas reference.
- **`MustSetConsensusEngineType` mutates by nulling siblings.** Setting one engine nils the others
  (`coregeth/chain_config_configurator.go:980-997`) — there is no "both set" ambiguity guard beyond
  last-writer-wins; the config cannot *express* a two-engine error the way nethermind's
  `CalculateSealEngineType` throws. Selection is by first-non-nil in a fixed order.
- **Schema auto-detection is key-heuristic, not a declared version.** `UnmarshalChainConfigurator`
  (`generic/generic.go`) guesses the schema from which distinguishing JSON keys are present (with mutual
  "must-not" negation). Robust for the two known schemas, but a genesis that mixes keys, or a hypothetical
  third schema, would need the heuristic tables extended — it is not a self-describing format.
- **Reflection `Crush` silently swallows non-fatal setter errors.** `crush` (`convert.go:182-195`) only
  aborts on `IsFatalUnsupportedErr`; other setter errors are dropped (the `// log.Println(e) // FIXME?` is
  commented out). Cross-schema conversion can therefore lose a field that one schema can't represent without
  a hard failure — a quiet-corruption risk to be aware of when relying on `coregeth ⇄ goethereum` round-trips.
- **`kotti` and other historical multi-geth networks are gone.** This tree ships only Classic, Mordor,
  MintMe, and MessNet as ETC-family built-ins (`params/config_*.go`) plus upstream Mainnet/Sepolia/Holesky;
  the wider multi-geth network zoo (kotti, ellaism, social, …) is not present — don't assume a network exists
  because multi-geth once had it.
</content>
</invoke>
