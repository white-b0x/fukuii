# go-ethereum — multi-network

_Commit/branch documented: `59e89e81e57814a96c429c5cdcaa6ca2e0d6b143` (tag `v1.17.4-32-g59e89e81e`,
branch `upstream`). Vendored at `/media/dev/2tb/dev/reference-clients-evm/go-ethereum` (identical copy
at `.claude/repo-references/clients/go-ethereum`, same SHA). Documented 2026-07-13._

## Architecture summary
go-ethereum has **no "network family" abstraction at all**. A "network" is just a `*core.Genesis`
value — a genesis header + an allocation + an embedded `*params.ChainConfig` — and *everything*
network-specific is data inside `ChainConfig` (`params/config.go:420-481`), not a type or a plugin. The
four built-in public networks (mainnet, Sepolia, Holesky, Hoodi) are hard-coded `ChainConfig` package
vars (`params/config.go:43-180`) paired with `DefaultXxxGenesisBlock()` constructors
(`core/genesis.go:642-688`). Network selection is a CLI-flag `switch` that installs one of those genesis
values (`cmd/utils/flags.go:1925-2040`); a **custom** network is loaded from a genesis JSON file
(`--override.genesis` / the `init` subcommand). Identity is established two ways that must agree: the
**genesis block hash** (used to look the config back up from a pre-existing datadir,
`core/genesis.go:460-475`) and the `ChainConfig.ChainID` (replay protection). There is a *single*
`ChainConfig` struct shared by every network — a testnet differs from mainnet only in which fork fields
are set and to what value, so geth models **"different chains of one family," never "different consensus
families."**

## Key types / interfaces / files
- `params/config.go:420-481` — **`ChainConfig`**, the one struct that *is* a network's rule set: `ChainID`
  (`:421`), every block-number fork field (`HomesteadBlock`…`GrayGlacierBlock`, `MergeNetsplitBlock`,
  `:423-442`), every timestamp fork field (`ShanghaiTime`…`AmsterdamTime`, `UBTTime`, `:446-456`),
  `TerminalTotalDifficulty` (`:460`), and the consensus-engine sub-objects `Ethash *EthashConfig` /
  `Clique *CliqueConfig` (`:478-479`). Stored in the DB per-genesis so "any network, identified by its
  genesis block, can have its own set of configuration options" (`:415-419` doc comment).
- `params/config.go:43-180` — the four named public configs: **`MainnetChainConfig`** (ChainID 1, full
  block-number fork ladder + timestamp forks, `Ethash: new(EthashConfig)`), **`SepoliaChainConfig`**
  (ChainID 11155111, `MergeNetsplitBlock: 1735371`), **`HoleskyChainConfig`** (ChainID 17000), and
  **`HoodiChainConfig`** (ChainID 560048). All four still carry `Ethash: new(EthashConfig)` even though
  they run PoS — the ethash object survives only as the historical-header inner engine (see
  consensus-engines.md).
- `params/config.go:30-35` — **genesis-hash constants** (`MainnetGenesisHash`, `SepoliaGenesisHash`,
  `HoleskyGenesisHash`, `HoodiGenesisHash`) — the durable identity of each built-in network.
- `params/config.go:183-359` — the **synthetic test/dev configs**: `AllEthashProtocolChanges`,
  `AllCliqueProtocolChanges` (ChainID 1337, `Clique: &CliqueConfig{Period:0, Epoch:30000}`),
  `AllDevChainProtocolChanges` (ChainID 1337, **TTD 0 → merged at genesis**), `TestChainConfig`,
  `MergedTestChainConfig`, `NonActivatedConfig`. These are the only configs that positively select Clique.
- `params/config.go:408-413` — **`NetworkNames`** map (chainID string → friendly name) — the closest
  thing geth has to a "network registry," and it's just a display-banner lookup, not a dispatch table.
- `core/genesis.go:57-77` — **`Genesis`** struct: `Config *params.ChainConfig` (`:58`) + genesis header
  fields (`Nonce`, `Timestamp`, `ExtraData`, `GasLimit`, `Difficulty`, `Mixhash`, `Coinbase`) + `Alloc`
  (`:66`). The unit a network is passed around as.
- `core/genesis.go:642-688` — **`DefaultGenesisBlock` / `DefaultSepoliaGenesisBlock` /
  `DefaultHoleskyGenesisBlock` / `DefaultHoodiGenesisBlock`** — each pairs a hard-coded nonce/extradata/
  gaslimit/difficulty/timestamp with its `ChainConfig` and a `decodePrealloc(xxxAllocData)` allocation.
- `core/genesis.go:691-734` — **`DeveloperGenesisBlock(gasLimit, faucet)`** — the `geth --dev` genesis:
  copies `AllDevChainProtocolChanges` (merged-at-genesis PoS, `:693`), pre-funds all precompiles + the
  system contracts (beacon roots, history storage, withdrawal/consolidation queues) + an optional faucet.
- `core/genesis.go:321-421` — **`SetupGenesisBlockWithOverride`**, the write-or-verify path: empty DB →
  commit the provided (or default-mainnet) genesis; existing DB → the provided genesis must hash-match the
  stored canonical genesis (`GenesisMismatchError`, `:388-390`) and the config is compatibility-checked
  before any update.
- `core/genesis.go:460-475` — **`chainConfigOrDefault`**: given a genesis hash, return the matching
  built-in `ChainConfig`, else the stored config — the genesis-hash → config resolution used when the
  operator passes no explicit genesis.
- `cmd/utils/flags.go:1925-2040` — **`SetEthConfig`'s network `switch`**: `--mainnet`/`--sepolia`/
  `--holesky`/`--hoodi`/`--dev`/`--override.genesis` each set `cfg.NetworkId`, `cfg.Genesis`, and DNS
  discovery defaults; `flags.CheckExclusive(...)` (`:1723`) forbids combining them.
- `cmd/utils/flags.go:2377-2393` — **`MakeGenesis`**, the same flag→genesis mapping used by chain-admin
  subcommands (`init`, `dump`, …).
- `params/config.go:906-1010` — **`CheckConfigForkOrder`** (forbids skipping/reordering forks; "geth isn't
  pluggable enough to guarantee forks in a different order than official networks", `:906-907`) and the
  blob-schedule completeness check.
- `params/config.go:1383-1408` — **`Rules(num, isMerge, timestamp)`**: collapses a `ChainConfig` +
  (block, timestamp) into a flat `Rules` bool-set the EVM reads — the per-block realization of the config.

## Design decisions & rationale
- **A network is genesis data, not a type.** geth deliberately has no `NetworkType` enum, no
  `ChainSpec` interface, no per-network subclass. `ChainConfig` is "stored in the database on a per-block
  basis... any network, identified by its genesis block, can have its own set of configuration options"
  (`params/config.go:415-419`). Adding a network = adding a genesis JSON (custom) or a package var +
  constructor + genesis-hash constant + a `switch` case (built-in). The whole model is *values in one
  struct*, which is why the built-in and custom paths converge on the same `SetupGenesisBlock`.
- **Genesis hash as durable network identity.** Once a datadir exists, geth re-derives the config by
  looking the stored canonical genesis hash up in `chainConfigOrDefault` (`core/genesis.go:460-475`) and
  refuses to boot a genesis that doesn't hash-match (`GenesisMismatchError`, `:388-390`). This makes
  "which network is this datadir?" a content-addressed fact, not a flag the operator can lie about.
- **ChainID for replay protection, genesis hash for identity — two separate concerns.** `ChainID`
  (`:421`) guards EIP-155 signing; the genesis hash guards datadir identity and DNS discovery seeds
  (`SetDNSDiscoveryDefaults`, `cmd/utils/flags.go:2170-2179`). A custom net picks both.
- **Fork schedule split block-number vs timestamp inside the same struct** (`params/config.go:444`
  comment "Fork scheduling was switched from blocks to timestamps here"). Pre-merge forks are `*big.Int`
  block numbers, post-merge forks are `*uint64` timestamps — the multi-network implication is that a
  network's *entire* history (PoW era + PoS era) lives in one config, dispatched by two primitives
  (`isBlockForked` / `isTimestampForked`, `:1248-1279`). See consensus-engines.md for the dispatch detail.
- **Positive engine sub-objects on the config** (`Ethash *EthashConfig` / `Clique *CliqueConfig`,
  `:478-479`). Presence of a typed sub-object is the *intended* engine selector — a genuine multi-network
  seam (the same field could name any consensus config). But geth's own selector undercuts it with an
  `else-means-ethash` fallthrough (see gotchas, and consensus-engines.md §gotchas).
- **Fork-order sanity gate** (`CheckConfigForkOrder`, `:906-1010`). Because a custom genesis can set fork
  fields to arbitrary values, geth validates on load that forks are monotonic and non-skipping and that
  block-ordered forks never follow timestamp-ordered ones — turning a loose data struct into something
  safe to accept from untrusted JSON.

## Notable patterns (the reusable idea)
1. **Network = (genesis header + alloc + ChainConfig) value, resolved by genesis hash.** The single most
   important comparative fact: geth has no family abstraction; the genesis *is* the network. Worth naming
   for the observations table as the "flat single-family, genesis-as-identity" pole.
2. **Built-in and custom networks share one code path.** `--mainnet` installs a package-var genesis;
   `--override.genesis`/`init` decodes a JSON genesis; both flow through `SetupGenesisBlock`. No separate
   "custom chain" machinery.
3. **Content-addressed datadir identity.** Genesis-hash mismatch is a hard boot error, not a warning —
   the network a datadir belongs to is provable from its stored genesis.
4. **Config-order validation as the safety net for arbitrary genesis JSON** (`CheckConfigForkOrder`).
5. **`Rules` flattening** — the per-block projection of a network's config into a bool-set the interpreter
   consumes, decoupling "how forks are scheduled" from "which rules are live at this block."

## Authority note
**For the `multi-network` concern, go-ethereum is a WEAK authority — and the key comparative finding of
this doc.** geth is effectively **single-family**: Ethereum L1 plus its own testnets (Sepolia/Holesky/
Hoodi), all sharing one `ChainConfig` struct and one consensus trajectory (PoW → merge → PoS). It models
**"different testnets of one family via `ChainConfig`," not "different consensus families."** There is no
first-class network-family abstraction the way:
- **besu** has one (multi-consensus genesis config — a genesis file positively selects ethash / clique /
  ibft2 / qbft as peers, so PoA and PoW are co-equal configured families),
- **erigon** has one (Bor/Polygon sidechain support as a distinct chain family),
- **nethermind** has one (self-declaring chain-spec plugins — a network can register its own consensus/
  rules module).

So **fukuii's multi-network / `NetworkFamily` ambition (PoW ETC + PoS ETH as co-equal families, plus a
planned PoA/private-network stack) should look to besu / erigon / nethermind for this concern, not geth.**
geth is the authority for the *ETH/PoS baseline config content* (which fork fields exist, their semantics,
the timestamp-vs-block split, the `Rules` projection) and for the **genesis-as-identity + genesis-hash
verification** mechanics — but not for how to *structure* multiple consensus families. core-geth (next
client documented) is the authority for the ETC-specific config content geth omits.

The same single-family assumption from consensus-engines.md surfaces here in config shape: the
`else-means-ethash` engine selector (`eth/ethconfig/config.go:238-241`, cited in consensus-engines.md) and
the fact that every public `ChainConfig` still hard-codes `Ethash: new(EthashConfig)` even for PoS
networks are both artifacts of "there is only one family, and PoW is its historical default." A genuine
multi-family client cannot carry an implicit default engine.

## Gotchas / anti-patterns / things they later changed
- **`--dev` is no longer Clique PoA — the flag text is stale.** The `--dev` usage string still reads
  "Ephemeral proof-of-authority network" (`cmd/utils/flags.go:167`), but modern dev mode is
  **merged-at-genesis PoS driven by a `SimulatedBeacon`** (`eth/catalyst/simulated_beacon.go`), not
  Clique: `DeveloperGenesisBlock` copies `AllDevChainProtocolChanges` with `TerminalTotalDifficulty: 0`
  and **no `Clique` sub-object** (`core/genesis.go:693`, `params/config.go:211-235`).
  `DeveloperPeriodFlag` (`--dev.period`) now drives the simulated-beacon block period, not a Clique
  period. Clique survives as a *config option* (`AllCliqueProtocolChanges`, custom genesis) but is no
  longer geth's dev default. Don't cite geth's `--dev` as a "Clique dev mode" reference — that changed.
- **Implicit mainnet default is a footgun.** With no genesis in DB and no genesis provided,
  `SetupGenesisBlock` silently writes **mainnet** (`core/genesis.go:336-338`, `LoadChainConfig` returns
  `MainnetChainConfig`, `:452-454`). A custom/private network that forgets to specify its genesis will
  "always fail" the hash check (`:358-360` comment) rather than run — intentional, but the failure mode is
  a genesis-mismatch error, not a clear "you didn't pick a network."
- **Every PoS config still declares `Ethash`.** `MainnetChainConfig` et al. keep `Ethash:
  new(EthashConfig)` (`params/config.go:68`, `103`, `138`, `173`) despite running PoS. It's harmless (the
  ethash object is now just the historical-header verifier) but it's a landmine for anyone reading the
  config to infer "this is a PoW network" — presence of `Ethash` no longer means PoW.
- **No positive multi-engine guard.** The config can't express "both `Ethash` and `Clique` set" as an
  error; selection silently prefers Clique (see consensus-engines.md; nethermind's
  `CalculateSealEngineType` throws instead). A multi-family config layer needs an explicit ambiguity
  guard geth lacks.
- **Fork fields are a flat, ever-growing list.** New forks (BPO1-5, Amsterdam, UBT) are added as more
  nullable pointer fields on the one struct (`params/config.go:450-456`), with `CheckConfigForkOrder`'s
  giant ordered slice (`:916-943`) kept manually in sync. Scales for one family; would not scale to N
  unrelated families each with their own fork vocabulary — another reason geth's shape doesn't generalize
  to fukuii's multi-family goal.
- **`UBT`/`Amsterdam`/`EnableUBTAtGenesis` are experimental** (`params/config.go:464-475` — "temporary
  flag only for binary devnet testing") — treat the bleeding-edge config fields as unstable when
  comparing config surfaces across clients.
