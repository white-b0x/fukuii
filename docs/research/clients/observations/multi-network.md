# Observations — multi-network
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/multi-network.md + pos-networks/consensus-l2 topics + consensus-engines observation._

This is the Phase-2 cross-client comparison for the **multi-network** subsystem: how each reference
client *defines and selects a concrete network* — the config/chain-spec model, custom-genesis support,
testnet curation, and where family identity leaks into the network-config layer. It is the
**network-config / chain-spec / genesis** complement to `observations/consensus-engines.md`, which owns
the orthogonal **consensus-engine mechanism** axis (family-abstraction spectrum, engine keying,
fork-dispatch unification, merge model). Where that doc asks "how does a client abstract a consensus
*family*," this one asks "how does a client model a *network* — its chain spec, its genesis, its
testnet set." Cross-ref its family-abstraction verdict (the B7.0.5 `NetworkFamily` registry); this doc
is the **data side** of that same design. Every per-client claim is cited to that client's
`multi-network.md`; the testnet inventory to `topics/pos-networks-and-testnets.md`; the L2/family-size
spectrum to `topics/consensus-l2-rollup-sidechain.md`.

**Authority model (network-config axis).** besu / nethermind / reth = the **JSON chain-spec** camp —
fukuii's own model (HOCON `blockchain` + a genesis JSON per network is closest in spirit to
besu/nethermind). core-geth = the ETC network-config authority (ETC/Mordor/MintMe/MessNet built-ins +
the two-schema `Configurator`). erigon = the **family-ships-own-chainspecs** authority (the polygon
module registers its own embedded chainspecs). go-ethereum = the ETH/PoS baseline config *content* +
genesis-as-identity mechanics, but a WEAK multi-network authority (single-family, genesis-is-the-network).

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | Authoritative |
|---|---|---|---|---|---|---|---|
| **Network-config model** | **Go struct** — `ChainConfig` package-vars + `//go:embed` allocs; a network *is* a `*core.Genesis` value | **Go, config-as-interface** — `Genesis.Config` holds the `ChainConfigurator` *interface*; two concrete schemas (`coregeth` per-EIP / `goethereum` fork-named) | **JSON genesis** — one `{name}.json` resource per network; `GenesisConfigOptions` interface, single JSON impl | **Go `Spec` struct + embedded chainspec JSON** — `Spec{Config,Genesis,Bootnodes,DNSNetwork,NetworkID}` loaded from `//go:embed chainspecs/*.json` | **Parity-lineage JSON chainspec** — `engine`/`params`/`genesis`/`accounts`; open `[JsonExtensionData]` engine block | **Rust `LazyLock<Arc<ChainSpec>>` statics** + embedded genesis JSON; family=type, instance=data | **besu / nethermind / reth** (JSON chain-spec — fukuii's camp); **core-geth** (Go+interface, ETC content) |
| **Chain-spec pluggability** | **none** — one struct; a network differs only in which fork fields are set | **config-schema pluggable** — 2 interchangeable schemas behind `Configurator` + reflection `Crush` conversion; but a **closed 3-engine enum** | **one schema, N mechanisms** positively keyed by genesis sub-object (`ethash`/`clique`/`ibft2`/`qbft`); geth→besu one-way transform | **cross-package `map[string]Spec` registry** populated by `init()`+blank-import; module pushes its own specs | **reflection-discovered** open `engine` block — add a family's config = drop a loadable class, zero central edit | **per-family `ChainSpecParser`**; the family itself is a compile-time type (`NodeTypes`) | **nethermind** (open engine block, zero-edit) / **core-geth** (schema polymorphism) |
| **Custom-genesis support** | `--override.genesis`/`init` → JSON through the *same* `SetupGenesisBlock` path as built-ins; **silent-mainnet default** footgun | same geth path + **schema auto-detection** (sniffs geth vs coregeth keys) | `--genesis-file` (**mutually excl. with `--network`, requires `--network-id`**) + **`operator generate-blockchain-config`** (generates validator keys, bakes them into `extraData`) | `configOrDefault` prefers explicit genesis; `keepStoredChainConfig` **preserves private-chain config**; unknown name → permissive `AllProtocolChanges` devnet | Parity chainspec native; geth genesis via a **separate `GethGenesisLoader`** (Ethash-only) | custom = genesis JSON reified via **`From<Genesis> for ChainSpec`**; foreign-family custom net needs that family's own parser | **besu** (private-net origination: `generate-blockchain-config` + `extraData` validators) |
| **Testnet curation** (current+deprecated) | Mainnet/Sepolia/**Holesky**/Hoodi/dev — **retains Holesky** | ETC (Classic/Mordor/MintMe/MessNet) + upstream Mainnet/Sepolia/Holesky; **lacks Hoodi** | Mainnet/Sepolia/Hoodi/Ephemery/dev + Linea/Lukso; **dropped Holesky 2026-04** | Mainnet/Sepolia/Hoodi/Gnosis/Chiado/Bor nets; **dropped Holesky 2025-10** | foundation/sepolia/hoodi; **dropped Holesky 2025-10** | mainnet/sepolia/**holesky**/hoodi/dev; **retains Holesky**; post-merge-born, never carried PoW-era testnets | the **modern-lean trio (nethermind/erigon/besu)** for the Holesky-out signal; **Sepolia+Hoodi universal** |
| **Family-ships-own-chainspecs** | no — all networks in `params/config.go` | ETC configs in-package (`config_classic.go` …), one binary | `NetworkDefinition` enum → resource file; **L2s (Linea/Lukso) as first-class entries** | **YES** — polygon module ships its own `polygon/chain/chainspecs/*.json`, registered via `init()`+blank-import | **YES** — plugin assemblies (`Nethermind.Optimism`/`Taiko`/`Xdc`) ship their own `*ChainSpecEngineParameters`; open engine block | **YES** — each family = downstream crate with its own `ChainSpec`/genesis (`op-reth`, `bsc` example) | **erigon** (in-tree module registers own chainspecs) / **nethermind** (plugin params) |
| **Chain-id / network-id handling** | `ChainID` (EIP-155 replay) ⊥ **genesis hash** (datadir identity) — two separate concerns; `NetworkNames` display map | inherits geth split; `ChainID`+`NetworkID` fields on both schemas | `chainId`/`networkId` in genesis; `--network-id` **required** for custom; `fromChainId()` reverse lookup | **all three separable** — `NetworkID` defaults to `ChainID` but can differ (Bloatnet 12159); **two chains can share a genesis hash** | `networkId = Params.NetworkId ?? ChainId ?? 1`; separable, default to each other | `chain()`/`chain_id()` on `EthChainSpec`; genesis hash from spec | **erigon** (the fullest 3-way-separable model + shared-hash tolerance) |
| **Config-vs-code family gating** | **config** (genesis data) — but only one family exists | config selects *schema*; adding an *engine* edits the closed enum + factory (**code**) | config (genesis key) selects mechanism, but dispatch is a hand-maintained if/else (**code to add a mechanism**) | config presence-of-field selects family, but the family *module* is a blank-import (**code**) | **config** at the param tier (chainspec engine key, zero code); subsystem wiring = `EmbeddedPlugins` list (**code**) | **code** (family = type/crate); only the network *instance* is config | **nethermind** (config-driven at the param tier — the most data-driven "add a network") |

## Approach catalog (use-case-aware)

Verdicts: **DEFAULT** = fukuii's baseline best practice · **OPTIONAL(role)** = offer for a named
use-case (private/consortium · enterprise · multi-network/L2 · cross-client ingest) · **OBSOLETE** =
understood-but-discarded (incl. "not fukuii's language/runtime camp"). Use-case taxonomy per
`README.md`'s omni-client lens.

| Approach | Clients using it | Good for (use-case / node-role) | Verdict | Why |
|---|---|---|---|---|
| **JSON chain-spec (genesis/chainspec file per network)** | besu, nethermind, reth | single-binary multi-network; operator-editable networks; fukuii's actual model | **DEFAULT (fukuii's camp)** | fukuii is HOCON `blockchain` + a genesis JSON per network — structurally closest to **besu**/`nethermind`'s "name → resource file" and reth's embedded-genesis-static. Their `sepolia.json` / `hoodi.json` are the **direct templates** for fukuii's own configs. The `fukuii-custom-networks` skill already drives this model. |
| **Go-struct config (package-var `ChainConfig`)** | go-ethereum, core-geth, erigon | Go clients only | **OBSOLETE (not fukuii's runtime)** | A network as a typed Go struct with `//go:embed` allocs is idiomatic in Go but does not port to a JVM+JSON-genesis client. Cite geth/core-geth/erigon for config *content* (fork fields, ETC per-EIP granularity), not for this packaging mechanism. |
| **core-geth multi-schema `Configurator`** | core-geth | ETC per-EIP fork granularity; ingesting a stock geth genesis unchanged | **OPTIONAL(cross-client ingest / ETC schema)** | A network as an *interface* value with N interchangeable schemas + reflection `Crush` conversion is the reference for accepting multiple config dialects and for the ETC-native per-EIP-transition surface fukuii's ETC configs need. But its engine set is a **closed 3-value enum** — schema pluggability, not family pluggability (that's the `consensus-engines.md` axis). |
| **Custom-genesis for private nets** | besu (`generate-blockchain-config` + `extraData` validators), erigon (`keepStoredChainConfig`), geth/reth (genesis-JSON reify) | private / consortium / enterprise chains (Batch 7) | **DEFAULT (for the private-net use case)** | **besu is the authority** — it ships tooling to *originate* a private BFT chain (generate N validator keypairs, encode addresses into genesis `extraData`), not just run one. The direct model for fukuii's NET-02 private-PoA stack. erigon's private-config-preservation and reth's `From<Genesis>` reify are the lighter-weight complements. |
| **Curated-current testnet set (prune deprecated)** | nethermind/erigon/besu (dropped Holesky + all pre-merge testnets) | every node role — a small, current, actively-pruned set | **DEFAULT** | The omni-client thesis in action: ship **Sepolia + Hoodi** (universal across clients) and curate OUT the unanimous-dead set (Goerli/Ropsten/Rinkeby/Kiln/Kovan/Morden). The **modern-lean trio** already removed Holesky (2025-Q4→2026-Q2); geth/core-geth/reth still carry it — treat Holesky as a sunset-track OPTIONAL, not a DEFAULT. |
| **Carry-deprecated testnet set** | (none at surveyed refs) | nothing | **OBSOLETE** | No surveyed client still ships Goerli/Ropsten/Rinkeby/Kiln/Kovan/Morden — the ecosystem verdict is settled (2023–2024 removals). Adding them is dead weight the whole ecosystem shed. |
| **Family-ships-own-chainspecs (module/plugin owns its network defs)** | erigon (`polygon/chain/chainspecs/`), nethermind (plugin params classes), reth (downstream crate) | heavy family (NET-01 Bor) that carries its own networks + external infra | **OPTIONAL(heavy family — NET-01) / DEFAULT-adjacent for the B7.0.5 registry packaging** | **erigon is the authority**: the polygon module registers its own embedded chainspecs via `init()`+blank-import, so a family is self-contained down to its network definitions. The packaging shape B7.0.5's `NetworkFamily` registry should adopt (a family carries its own chainspecs) — while **avoiding** erigon's `FrozenBorBlocks` leak into shared readers. |
| **Cross-client genesis ingest (accept a foreign client's genesis)** | core-geth (schema auto-detect), besu (one-way geth→besu transform), nethermind (parallel `GethGenesisLoader`) | interop / migrating an existing chain's genesis into fukuii | **OPTIONAL(interop / migration)** | Three encodings of "load a geth-format genesis": core-geth sniffs and stores behind the interface (dual first-class schema), nethermind keeps a parallel loader feeding the same provider (cleaner), besu rewrites geth→besu (lossy transform). nethermind's parallel-loader shape is the cleanest if fukuii ever needs geth-genesis ingest. |

## Best-practice synthesis

**The DEFAULT + OPTIONAL menu that falls out of the six clients:**

1. **Network-config model — DEFAULT: the JSON chain-spec camp (besu/nethermind/reth).** fukuii's
   HOCON `blockchain` + per-network genesis JSON is structurally the besu/nethermind "name → resource
   file" model, not the Go-struct camp (geth/core-geth/erigon). besu's `sepolia.json`/`hoodi.json` and
   nethermind's `Chains/*.json` are the most direct templates for fukuii's own network configs. The
   Go-struct packaging (typed package-vars + `//go:embed`) is idiomatic-elsewhere but OBSOLETE for a
   JVM client — port config *content* from geth/core-geth, not the mechanism.

2. **Chain-id ⊥ network-id ⊥ genesis-hash are three separable identities (erigon's model).** Every
   modern client separates `ChainID` (EIP-155 replay) from `NetworkID` (P2P) from the genesis hash
   (datadir identity); erigon goes furthest (`NetworkID` can differ from `ChainID`; two chains can
   share a genesis hash). fukuii's multi-family config layer cannot assume 1:1:1 — the PoW/ETC and
   PoS/ETH families already carry structurally different network-config objects (block-number vs
   timestamp fork dispatch, no-deposit-contract vs deposit-contract), so keep the three identities
   independent.

3. **Custom-genesis + private-net origination — DEFAULT (Batch 7): besu's `generate-blockchain-config`
   model.** For the private/consortium use case, besu is the authority: it *originates* a chain
   (generate validator keypairs → encode into genesis `extraData`), not just runs one. The direct
   reference for fukuii's NET-02 private-PoA stack, paired with erigon's private-config-preservation
   (`keepStoredChainConfig`) so an operator's custom config isn't clobbered. This is the network-config
   **data** counterpart to the `consensus-engines.md` Sealer/ValidatorProvider/BlockInterface seams —
   the seams occupy the mechanism; the genesis `extraData` codec + chainspec occupy the data.

4. **Testnet curation — DEFAULT: ship Sepolia + Hoodi (+ ETC/Mordor for PoW), curate OUT deprecated.**
   The reference-client convergence is unambiguous: **Sepolia + Hoodi** are the universal current ETH
   PoS testnet set; the deprecated set (Goerli/Ropsten/Rinkeby/Kiln/Kovan/Morden) is unanimously
   removed. **Holesky is the one live disagreement** — nethermind/erigon/besu dropped it, geth/core-geth/
   reth retain it — so it is an OPTIONAL sunset-track target, not a DEFAULT. fukuii's shipped set:
   `Sepolia` + `Hoodi` (PoS) + `ETC`/`Mordor` (PoW) as DEFAULT; `ETH mainnet` + `Holesky` as OPTIONAL
   growth/sunset; `Ephemery` OPTIONAL/niche (prefer the external-genesis path over a hardcoded
   resetting-chain-id config); `dev` for local. This is the omni-client thesis working as intended — a
   small current set, actively pruned.

5. **Family-ships-own-chainspecs (erigon) is the B7.0.5 registry's packaging shape.** When a promoted
   family carries its own networks + external infra (NET-01 Bor: Heimdall/bridge/span oracles), the
   family module should ship its own chainspecs the way erigon's `polygon/` does — self-contained down
   to the network definitions. This is the **data side of the `NetworkFamily` typeclass**
   (`consensus-engines.md` §B7.0.5): the typeclass carries the family's engine + injected oracles; the
   family's chainspecs travel with it. Adopt the packaging, hold the family-neutrality invariant — no
   `FrozenBorBlocks`-style leak of a family's network config into shared readers.

**Cross-client ingest (OPTIONAL, interop/migration):** nethermind's parallel `GethGenesisLoader`
feeding one provider abstraction is the cleanest shape (vs besu's lossy transform / core-geth's
dual-schema sniff) if fukuii ever needs to ingest a geth-format genesis — a Phase-4 seed, not a
current need.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

These are **seeds for the B7.0.5 / NET-01 / NET-02 designs and the curated-network-set policy**, not
verdicts to implement in this doc.

- **fukuii's current shape:** PoW **ETC/Mordor** (chain IDs 61/63, block-number fork dispatch, no
  deposit contract/TTD) + PoS **ETH/Sepolia** (chain IDs 1/11155111, timestamp fork dispatch, deposit
  contract, historical TTD) — two structurally-different network-config objects on separate code paths
  (`AGENTS.md` "PoW vs PoS"). fukuii's JSON-genesis + HOCON model is the **besu/nethermind chain-spec
  camp**; their `sepolia.json`/`hoodi.json` are the templates.

- **The multi-network expansion needs the chain-spec/custom-genesis model + the NetworkFamily
  registry.** NET-01 (Polygon/Bor) is the heavy family that carries its own chainspecs + injected
  Heimdall/bridge oracles (erigon `polygon/` = the packaging authority; family-neutrality the
  invariant). Batch-7 private PoA (NET-02, Clique-led) is the custom-genesis/`extraData`-validator
  origination case (besu `generate-blockchain-config` = the authority; Clique *production* sourced from
  **core-geth**, since besu stubbed it — see `consensus-engines.md`). The `NetworkFamily` typeclass is
  the **mechanism** side (`consensus-engines.md`); **multi-network config is the data side** — the
  chainspecs, genesis, deposit-contract/TTD/extraData that travel with each family.

- **Curate the testnet set** to Sepolia + Hoodi (PoS) + ETC/Mordor (PoW) as DEFAULT, with ETH mainnet +
  Holesky as OPTIONAL growth/sunset and the whole pre-merge set curated OUT — mirroring exactly where
  the modern-lean reference clients have converged. When adding Hoodi, use besu/nethermind's
  `hoodi.json` as the template (PoS-from-genesis, TTD 0, mainnet deposit contract, Shanghai/Cancun at
  genesis); when maintaining Sepolia, carry the historical TTD + `MergeNetsplitBlock` + the **custom
  permissioned deposit contract** (`0x7f02…295d`, not the mainnet one).

- **Keep chain-id / network-id / genesis-hash separable** in whatever config abstraction B7.0.5 lands —
  erigon proves a multi-family client cannot assume 1:1:1, and fukuii's two families already differ on
  every network-config axis.

_Cross-references: `observations/consensus-engines.md` (the consensus-engine mechanism axis — B7.0.5
`NetworkFamily` registry, engine keying, fork-dispatch unification, merge model — the mechanism side of
this doc's data side); `topics/pos-networks-and-testnets.md` (concrete PoS network inventory +
deprecation archaeology); `topics/consensus-l2-rollup-sidechain.md` (family-size spectrum: thin
Gnosis → heavy Bor → rollup OP/Taiko → alt-BFT XDC); `topics/consensus-methods-catalog.md` (master
method × network verdict matrix); each `{client}/multi-network.md` (per-client network-config detail)._
