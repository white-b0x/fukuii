# erigon — multi-network

_Commit/branch documented: `f1d79d699ed4b809abc0d177dcb539d8605edc41` (branch `main`,
`origin/upstream`/`origin/main`). Vendored read-only at
`.claude/repo-references/clients/erigon`. Documented 2026-07-13. Read-only research; no
fukuii source touched._

_Companion to `erigon/consensus-engines.md` (Bor/Heimdall/`FrozenBorBlocks` findings) — this
file is the network-selection / chain-spec / family-wiring half of the same story._

## Architecture summary

erigon has the **broadest *real* multi-family reach of any client documented so far**: one
binary genuinely runs **Ethereum (ETH/PoS)**, **Polygon (Bor, an L2/sidechain PoS-ish
mechanism)**, and **Gnosis Chain (Aura/AuthorityRound PoA)** plus each family's testnets. It
reaches that breadth **not** with a self-declaring plugin registry (that is nethermind, the
next pole) but with three cooperating mechanisms:

1. **Presence-of-typed-config-field dispatch on `chain.Config`** — `Ethash *EthashConfig` /
   `Aura *AuRaConfig` (nil-pointer presence) plus `Bor BorConfig` (an *interface* hydrated from
   a `BorJSON json.RawMessage` sidecar, **not** the uniform pointer convention;
   `execution/chain/chain_config.go:120-124`). A `Rules RulesName` string enum
   (`"aura"`/`"ethash"`/`"bor"`, `:50`) is carried redundantly alongside as a validated,
   spec-named consensus identity.
2. **A central chain-spec *registry* (`map[string]Spec`) populated by `init()` across multiple
   packages via blank imports** — the core spec package registers ETH+Gnosis+Chiado
   (`execution/chain/spec/config.go:37-44`); the **polygon module registers its own bor
   networks** (`polygon/chain/config.go:82-86`), and every binary that must see Polygon does
   `import _ ".../polygon/chain" // Register Polygon chains` (`node/eth/backend.go:128`,
   `cmd/utils/flags.go:79`, +6 more). This is one tier more modular than besu's registry-less
   if/else, but still **compile-time**: the module is hard-linked into the binary, not
   discovered at runtime.
3. **Per-family *modules* wired conditionally into the node backend** — `if chainConfig.Bor !=
   nil` constructs a whole Heimdall client, state-sync bridge, and a *separate polygon sync
   service*; `if chainConfig.Aura != nil` opens a dedicated Aura consensus KV DB
   (`node/eth/backend.go:587-645,1080-1095`). A foreign family is not just config — it drags in
   its own services and even its own sync pipeline.

So a "network" in erigon is a `Spec{Config, Genesis, GenesisHash, Bootnodes, DNSNetwork,
NetworkID}` value looked up by `--chain` name out of the shared registry — but the consensus
family it names may pull in an entire subsystem. The cautionary counter-finding (from
`consensus-engines.md`): this breadth is **not cleanly family-neutral** — a bor-specific method
(`FrozenBorBlocks`) leaks into the shared `ChainHeaderReader` every engine gets. **Broadest real
coverage, achieved with some abstraction leakage.**

## Key types / interfaces / files

### The config struct — presence-of-typed-field dispatch
- `execution/chain/chain_config.go:44-133` — **`chain.Config`**, the network rule set. Carries
  geth's block-number forks (`HomesteadBlock`…) and timestamp forks (`ShanghaiTime`…`AmsterdamTime`,
  `Bpo1Time`…`Bpo5Time`), `TerminalTotalDifficulty`, plus the consensus fields:
  - `:120` **`Ethash *EthashConfig`** (`json:"ethash,omitempty"`) — pointer-presence marker.
  - `:121` **`Aura *AuRaConfig`** (`json:"aura,omitempty"`) — pointer-presence marker.
  - `:123-124` **`Bor BorConfig` (`json:"-"`) + `BorJSON json.RawMessage` (`json:"bor,omitempty"`)**
    — the divergence: bor's config is an **interface**, hydrated out-of-band from a raw-JSON
    sidecar (`polygon/chain/config.go:34-43` `readBorChainSpec` unmarshals `BorJSON` into a
    `*borcfg.BorConfig` and assigns `spec.Bor`). A naive "check the pointer is non-nil" dispatch
    does **not** generalize to bor.
  - `:50` **`Rules RulesName`** (`json:"consensus,omitempty"`) — the spec's `"consensus"` key, a
    validated string engine-name carried *in addition to* the typed fields.
  - `:38-43` — the struct doc keeps geth's verbatim "any network, identified by its genesis
    block, can have its own set of configuration options" line, and adds a `sync.Once`
    deep-copy caveat (must copy via `copier.CopyWithOption(DeepCopy:true)`).
- `execution/chain/chain_config.go:227-249` — **`BorConfig` interface**: `fmt.Stringer` + Polygon
  fork predicates (`IsAgra`/`IsNapoli`/`IsAhmedabad`/`IsBhilai`/`IsRio` + `Get*Block`) +
  `StateReceiverContractAddress`, `CalculateSprintNumber/Length`, `CalculateCoinbase`. The
  Polygon fork vocabulary is entirely disjoint from ETH's — a foreign family has its own
  hard-fork names, so its config is its own interface, not fields on the shared struct.
- `execution/chain/chain_config.go:304-317` — **`getEngine()`**: a `switch` returning
  `c.Ethash`/`c.Bor`/`c.Aura`'s string else `"unknown"` — the positive, no-implicit-default read
  of which family is configured (contrast geth's `else-means-ethash`).
- `execution/chain/rules.go:21-42` — **`RulesName`** string enum (`"aura"`/`"ethash"`/`"bor"`)
  with a `ValidRulesNames` set + `Validate()` — the `Rules`/`"consensus"` field's type. (Same
  file cited in `consensus-engines.md`; here it is the network-config-level engine name, the
  spec-declared mechanism identity.)

### The chain-spec registry (map populated by init() + blank import)
- `execution/chain/spec/config.go:135-145` — **`Spec`** struct: `Name`, `GenesisHash`,
  `GenesisStateRoot`, `Genesis *types.Genesis`, `Config *chain.Config`, `Bootnodes`,
  `DNSNetwork`, optional `NetworkID` (defaults to `ChainID` if 0). The unit a network is
  registered and resolved as — richer than geth's bare `(ChainConfig, Genesis)` pairing (it
  bundles bootnodes + DNS discovery + genesis hash into one value).
- `execution/chain/spec/config.go:120-133` — **`RegisterChainSpec(name, spec)`**: writes into the
  package-global `registeredChainsByName map[string]Spec` **and** into `NetworkNameByID`. The
  registry is *mutable at init time* and cross-package.
- `execution/chain/spec/config.go:37-72` — **`init()`**: registers `Mainnet`/`Sepolia`/`Hoodi`/
  **`Gnosis`**/**`Chiado`**/`Test`/`Bloatnet`, then *validates every registered spec is
  non-empty with a genesis hash* (panics otherwise), then computes the PoS chain-id set. The
  core package owns the ETH + Gnosis(Aura) families.
- `execution/chain/spec/config.go:157-221` — the **spec vars** themselves, each loading its
  `chain.Config` from an **embedded chainspec JSON** via
  `ReadChainConfig(chainspecs, "chainspecs/<name>.json")` (`:76-77` `//go:embed chainspecs`).
  Note `Gnosis`/`Chiado` carry an explicit `GenesisStateRoot` (`:190-201`), and `Bloatnet` reuses
  mainnet's genesis hash but overrides `NetworkID: 12159` (`:212-220`) — chain-id ≠ network-id is
  first-class here.
- `execution/chain/spec/config.go:94-118` — **`ChainSpecByName`** (map lookup, `ErrChainSpecUnknown`)
  and `ChainSpecsByGenesisHash` (reverse lookup, explicitly "*ONLY USED FOR ERROR LOGGING*" and
  noting multiple chains can share a genesis hash, e.g. mainnet + bloatnet).
- `execution/chain/spec/network_id.go:19-28` — chain-id constants (`GnosisChainID = 100`,
  `ChiadoChainID = 10200`, `BloatnetNetworkID = 12159`) + the `NetworkNameByID` reverse map.

### The polygon module self-registering into the shared registry
- `polygon/chain/config.go:45-73` — the **Polygon spec vars**: `Amoy` (chainId 80002), `BorMainnet`
  (chainId 137), `BorDevnet`, `Mumbai` — each a `chainspec.Spec` whose `Config` came through
  `readBorChainSpec` (which hydrates `Bor` from the `BorJSON` sidecar).
- `polygon/chain/config.go:31` — **`//go:embed chainspecs`**: the polygon module ships its
  **own** embedded chainspecs (`polygon/chain/chainspecs/{amoy,bor-mainnet,bor-devnet,mumbai}.json`)
  — separate from the core `execution/chain/spec/chainspecs/`. The module owns its network
  resources, not just its code.
- `polygon/chain/config.go:82-86` — **`init()` → `chainspec.RegisterChainSpec(...)`** for all four
  bor networks. This is the crux: the polygon package *pushes* its networks into the core
  package's registry at init time. It only runs if the package is linked.
- **The blank-import link points** — `node/eth/backend.go:128`, `cmd/utils/flags.go:79`,
  `p2p/sentry/sentry_grpc_server.go:65`, `cmd/downloader/main.go:74`,
  `cmd/integration/commands/{stages,state_stages,state_domains}.go` — all carry
  `_ "github.com/erigontech/erigon/polygon/chain" // Register Polygon chains`. A binary that omits
  this import simply has no Polygon networks. This is the seam that makes it a **compile-time
  module registry**, not a runtime plugin registry.

### Network selection & the two consensus-DBs (the per-family wiring)
- `cmd/utils/flags.go:122-127` — **`--chain`** flag (`ChainFlag`), default `networkname.Mainnet`.
  A single string that names a registered spec.
- `cmd/utils/flags.go:2044-2057` — **`SetEthConfig`'s chain switch**: a recognized name →
  `chainspec.ChainSpecByName(chain)` → `cfg.Genesis = spec.Genesis` + `SetDNSDiscoveryDefaults`;
  `""`+networkID 1 → mainnet DNS; `networkname.Dev`/`networkname.BorDevnet` → `setDevnetEthConfig`
  (`:1454,1503`). Same "flag-switch installs a genesis" shape as geth/core-geth, but the switch
  keys into a shared *map*, and the resolved family may be ETH, Aura, or Bor.
- `execution/chain/networkname/network_name.go:23-51` — the **`networkname` string constants** +
  `All` list + `Supported()` — the flat namespace of valid `--chain` values, spanning all three
  families (`Mainnet`/`Sepolia`/`Gnosis`/`Chiado`/`BorMainnet`/`Amoy`/`Mumbai`/…).
- `node/eth/backend.go:587-594` — **rules-config selection**: `if chainConfig.Aura != nil {
  rulesConfig = &config.Aura } else if chainConfig.Bor != nil { rulesConfig = chainConfig.Bor }
  else { rulesConfig = &config.Ethash }` — the family fork the whole engine construction takes.
- `node/eth/backend.go:604-643` — **the Bor family drags in services**: under `if chainConfig.Bor
  != nil`, backend builds `heimdall.NewHttpClient` + `bridge.NewHttpClient` (or Idle variants
  under `--without-heimdall`), a `bridge.Service`, a `heimdall.Service`, and their RPC backend
  servers, storing them on `backend.polygonBridge`/`backend.heimdallService`. `:645`
  `CreateRulesEngine(..., polygonBridge, heimdallService)` threads them into the engine.
- `node/eth/backend.go:1080-1095` — **a whole separate sync service per family**: under `if
  chainConfig.Bor != nil`, `backend.polygonSyncService = polygonsync.NewService(...)` (run at
  `:1448`). Polygon does not reuse the staged sync pipeline — it has its own.
- `node/rulesconfig/config.go:71-86` (per `consensus-engines.md`) — **the Aura family opens a
  dedicated consensus DB**: `node.OpenDatabase(..., dbcfg.ConsensusDB, "aura", ...)` before
  `aura.NewAuRa(chainConfig.Aura, db)`. AuthorityRound needs its own persisted state; the network
  selection reaches all the way into storage.

### Custom / private genesis handling
- `execution/state/genesiswrite/genesis_write.go:113-124` — **`configOrDefault`**: prefer an
  explicit `g.Config` (custom genesis file); else look up the `--chain` name and use its spec
  config **only if the genesis hash matches**; else fall back to `chain.AllProtocolChanges`. The
  custom path takes precedence over the named-network path.
- `execution/state/genesiswrite/genesis_write.go:58-78` — **`GenesisMismatchError`**: on a
  hash mismatch against an existing DB it *reverse-looks-up the registry by genesis hash* and
  suggests the right flag — `(try with flag --chain=<name>)`. Genesis-hash-as-identity, same as
  geth, but with a registry-aware hint.
- `execution/state/genesiswrite/genesis_write.go:210-243` — **private-chain config preservation**:
  when no new genesis is supplied, `keepStoredChainConfig` is forced on for an unknown chain name
  *or* a known name whose genesis hash doesn't match the stored one (the comment cites "custom
  genesis with chainId 1 in Hive tests"), so `erigon init`-created private-chain configs aren't
  clobbered — only overrides applied. A more careful private-network story than geth's silent
  mainnet default.
- `execution/state/genesiswrite/genesis_write.go:559` — a **Bor-Kurtosis-devnet special-case** in
  `GenesisToBlock` keyed on `g.Config.Bor != nil && ChainID == polygonchain.BorKurtosisDevnetChainId`
  — even genesis-block construction has a per-family branch.

## Design decisions & rationale

- **Presence of a typed config field selects the family — but bor breaks the convention.**
  `Ethash`/`Aura` are nil-pointer markers (`chain_config.go:120-121`) read positively by
  `getEngine()` (`:304-317`), a strictly-safer no-default posture than geth. **Bor is the
  exception**: an interface (`:123`) hydrated from a `BorJSON` sidecar (`polygon/chain/config.go:34-43`),
  because Polygon's config is large, versioned, and lives in a different module. The lesson for a
  multi-family layer: a uniform pointer-presence dispatch is clean until a family's config is too
  big/foreign to be a plain struct field — then you get a sidecar, and the dispatch stops being
  uniform.
- **A network registry populated by `init()` + blank import — modularity without a plugin
  runtime.** Rather than geth's `switch` over package vars or besu's `NetworkDefinition` enum,
  erigon has a mutable `map[string]Spec` that any package can register into
  (`RegisterChainSpec`, `config.go:120-133`). The polygon module registers its own networks
  (`polygon/chain/config.go:82-86`) and even ships its own embedded chainspecs. This decouples
  "core knows about Polygon" — core doesn't; Polygon injects itself. But because linkage is a
  compile-time blank import (`node/eth/backend.go:128` et al.), it is a **module registry, not a
  self-declaring plugin registry**: you still edit the binary's import set to add a family.
- **A foreign family is a full subsystem, wired conditionally through the backend.** `if
  chainConfig.Bor != nil` doesn't just pick an engine — it stands up Heimdall + bridge services
  and a *separate polygon sync pipeline* (`backend.go:604-643,1080-1095`); `if chainConfig.Aura
  != nil` opens a dedicated consensus DB (`rulesconfig/config.go:71-86`). erigon accepts that
  network selection has deep, cross-cutting consequences and expresses them as conditional wiring
  from one central place (the backend constructor), not as plugin lifecycle hooks.
- **Bundle everything a network needs into one `Spec` value.** `Spec` carries config + genesis +
  genesis hash + bootnodes + DNS discovery + optional network-id (`config.go:135-145`), so
  `--chain <name>` resolves the *entire* runtime network identity in one lookup, and the
  self-check `init()` panics if any spec is under-populated (`:46-64`). Richer than geth's split
  `MainnetChainConfig` var + `DefaultGenesisBlock()` + `MainnetGenesisHash` triple.
- **Chain-id, network-id, and genesis hash are three separable identities.** `NetworkID` defaults
  to `ChainID` but can differ (`RegisterChainSpec`, `:124-129`; Bloatnet overrides it to 12159 on
  a mainnet genesis hash, `:212-220`), and `ChainSpecsByGenesisHash` explicitly tolerates two
  chains sharing a genesis hash. A multi-family client cannot assume 1:1:1.

## Notable patterns (the reusable idea)

1. **Presence-of-typed-config-field family dispatch, with a sidecar escape hatch.** Nil-pointer
   `Ethash`/`Aura` markers read by a positive `getEngine()` switch (no default), **plus** an
   interface+`BorJSON`-sidecar for a family whose config is too large/foreign to inline. The
   nameable pattern for the observations table: erigon selects the *family* from the presence of a
   typed field, like geth's engine sub-objects but *actually* used across three real families —
   and it shows the pattern's failure mode (bor's non-uniform interface config).
2. **Cross-package chain-spec registry via `init()` + blank import.** A central `map[string]Spec`
   that modules register themselves into (`RegisterChainSpec`), with per-module embedded
   chainspecs. One tier more modular than a hand-maintained if/else or enum, one tier below a
   runtime plugin registry (linkage is a compile-time blank import). The "module registry"
   midpoint on the pluggability spectrum.
3. **Family-as-subsystem, conditionally wired from the backend.** `if chainConfig.<Family> != nil`
   in the node constructor pulls in that family's services (Heimdall/bridge, Aura consensus DB)
   and even a *separate sync pipeline*. The evidence that "add a network family" means "add a
   module wired into central dispatch," not "add a config row."
4. **One `Spec` value = the whole network identity** (config + genesis + hash + bootnodes + DNS +
   network-id), self-validated at init. Resolve everything from `--chain <name>` in a single
   lookup.

## Position on the pluggability spectrum

erigon is the **broadest-real-coverage** point so far, sitting **above besu on actual
multi-family reach but below nethermind on abstraction cleanliness**:

- **vs geth (weak / single-family):** not close. geth is Ethereum-L1-plus-its-testnets, one
  `ChainConfig`, one consensus trajectory. erigon ships three genuinely different consensus
  families (ETH/PoS, Polygon/Bor sidechain, Gnosis/Aura PoA) in one binary.
- **vs core-geth (config-schema pluggable, closed 3-engine enum):** different axis. core-geth is
  rich on *config schema* (a `Configurator` interface, two interchangeable schemas, reflection
  conversion) but its engines are a closed enum and its "families" are still Ethereum-lineage
  (ETC/PoW, clique, lyra2). erigon is rich on *actual production families* — it runs Polygon and
  Gnosis, real foreign chains — via presence-of-field dispatch + per-family modules, with a single
  JSON config schema (no `Configurator` polymorphism). For fukuii's `NetworkFamily` ambition,
  core-geth models "multiple config schemas / ETC per-EIP content"; erigon models "how a genuinely
  foreign family (its own forks, sync, external services) plugs into a shared node."
- **vs besu (mechanism-selection via closed if/else):** the two are the closest comparison and
  differ instructively. besu positively selects a mechanism from a *genesis config key* through a
  hard-coded `fromGenesisFile` if/else → controller-builder, over five co-equal but
  **Ethereum-family** mechanisms (ethash/clique/ibft2/qbft + merge). erigon's dispatch is a
  presence-of-*typed-field* + a *registry* modules push into, and its families include **real
  non-Ethereum chains** (Polygon L2, Gnosis) with their own external infra. erigon has the broader
  *real* reach; besu has arguably the cleaner *selection* mechanism (one genesis key, one
  interface, no sidecar, no leaked cross-family method). Neither is a self-declaring plugin
  registry — both require editing the binary (besu: the if/else; erigon: the blank-import set).
- **vs nethermind (self-declaring plugin registry — the pole, forward-ref, no verdict):**
  erigon's registry is populated by compile-time `init()`+blank-import, not runtime plugin
  discovery, and its abstraction **leaks** (a bor-specific `FrozenBorBlocks` on the shared
  `ChainHeaderReader`, `consensus-engines.md` §Gotchas). nethermind (next-documented) is where a
  network registers its own consensus/rules module through a declared plugin interface — the
  cleanest, most-decoupled pole. This doc forward-refs it without a verdict.

## Authority note

**For `multi-network`, erigon is a STRONG authority — the broadest *real* multi-family reach and
the definitive sidechain/L2-family reference — but explicitly NOT the cleanest abstraction.** It
is the client to study for:
- **How a genuinely foreign consensus family plugs into a shared node** — the presence-of-field
  dispatch (`chain_config.go:120-124,306-317`), the `init()`+blank-import module registry
  (`polygon/chain/config.go:82-86`, `node/eth/backend.go:128`), and the conditional per-family
  wiring of services + a separate sync pipeline (`backend.go:604-643,1080-1095`). This is the
  **direct reference for fukuii's NET-01 (Polygon) family** and for any "family = module wired
  into central dispatch" design (memory: file-tree-seam-direction — the DESIGNED-not-built
  production-side seams).
- **Multi-family network selection & identity** — the `Spec` bundle (`config.go:135-145`), the
  shared `--chain` namespace across families (`networkname`), and the separable
  chain-id/network-id/genesis-hash identities.
- **The sidechain/L2 case specifically** — Bor + Heimdall + bridge, per `consensus-engines.md`.

Two authority caveats, both to surface at Phase 4:
- **Not the cleanest abstraction.** The `FrozenBorBlocks` leak into the shared reader
  (`consensus-engines.md` §Gotchas) and bor's interface+sidecar divergence from the pointer
  convention (`chain_config.go:123`) are the "broad reach, imperfect neutrality" evidence. For the
  *cleanest* family-decoupling model, the pole is **nethermind** (self-declaring plugin registry,
  forward-ref).
- **Not the ETC/PoW authority** (that is core-geth — ETChash/ECIP-1017/1099/1111/1122; erigon has
  no ECIP awareness) **nor the canonical ETH/PoS baseline** (go-ethereum). erigon's Aura engine is
  a *secondary* PoA reference; besu is the primary multi-consensus/PoA + private-network authority
  (NET-02).

## Gotchas / anti-patterns / things they later changed

- **Bor's config is an interface + JSON sidecar, not a pointer field** (`chain_config.go:123-124`,
  hydrated in `polygon/chain/config.go:34-43`). The presence-marker dispatch is **not uniform** —
  `Ethash`/`Aura` are `*T` you nil-check, but `Bor` is a `BorConfig` interface you must hydrate
  from `BorJSON` first. Do not assume "check the typed pointer is non-nil" generalizes across
  families; erigon itself special-cases bor everywhere (`String()` `:250-262`, `getEngine()`
  `:308-309`, the fork predicates `:398-412`).
- **The module registry only works if the module is blank-imported.** `polygon/chain`'s `init()`
  registers bor networks (`config.go:82-86`), but a binary that forgets `import _ ".../polygon/chain"`
  silently has no Polygon networks — `--chain bor-mainnet` would `ErrChainSpecUnknown`. The
  eight-plus scattered blank imports (`node/eth/backend.go:128`, `cmd/utils/flags.go:79`, …) are a
  fragile, easy-to-miss coupling — the cost of a compile-time registry vs a declared plugin
  interface.
- **`FrozenBorBlocks` leaks a Polygon-ism into every engine's shared reader**
  (`consensus-engines.md` §Gotchas, `rules.go:69`). The single most-cited "broad reach but not
  family-neutral" finding — restated here because it is a *multi-network* smell, not just a
  consensus one: the shared abstraction carries one family's concern.
- **`Rules RulesName` (the `"consensus"` string) is redundant with the typed fields.** A network's
  family is expressed twice — once as `Rules: "bor"` (`chain_config.go:50`) and once as a typed
  `Bor`/`Ethash`/`Aura` field. Nothing documented enforces they agree; a config that says
  `"consensus":"ethash"` but carries an `aura` block is not obviously rejected. Belt-and-suspenders
  that could disagree.
- **`configOrDefault` falls back to `AllProtocolChanges` for an unknown chain**
  (`genesis_write.go:124`) — an *all-forks-enabled devnet* config, not a hard error. A private
  network with a misspelled `--chain` and no genesis file gets a permissive dev config, not a
  refusal. Contrast geth's silent-mainnet default and besu's `--genesis-file`-requires-`--network-id`
  strictness — three different "operator forgot to specify the network" failure modes across
  clients, none of them a clean "you didn't pick a network."
- **Two chains can share a genesis hash** (Bloatnet reuses mainnet's, `config.go:212-220`), so
  genesis-hash → network is *not* a function; `ChainSpecsByGenesisHash` returns a slice and is
  documented "ONLY USED FOR ERROR LOGGING" (`:104-118`). Don't treat genesis hash as a unique
  network key the way geth's `chainConfigOrDefault` does.
- **Bloatnet overrides `NetworkID` off `ChainID`** (`:212-220`) — a reminder that the two are
  separable and the registry keys `NetworkNameByID` on the *network* id, not the chain id.
</content>
</invoke>
