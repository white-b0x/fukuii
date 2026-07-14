# besu — multi-network

_Commit/branch documented: `3fd233a4f93556e932f734d8feecbad4a047ff67` (branch `upstream`, =
`origin/upstream`). Vendored at `.claude/repo-references/clients/besu`. Documented 2026-07-13._

## Architecture summary
besu makes the **genesis file's `config` block the consensus-mechanism selector**, and this is the key
comparative fact: where geth's genesis config selects only *chain params* of one family
(`../go-ethereum/multi-network.md`) and core-geth's genesis selects a *config schema* from a closed engine
enum (`../core-geth/multi-network.md`), besu's genesis positively selects the **consensus MECHANISM itself**
from a named sub-object — `ethash` / `clique` / `ibft2` / `qbft` (`ibft` legacy) — each of which is a
distinct, fully-built consensus subsystem with its own controller-builder and protocol schedule. A
`GenesisConfigOptions` interface exposes presence predicates (`isEthHash()`, `isClique()`, `isIbft2()`,
`isQbft()`, `isPoa()`, `getPowAlgorithm()`, `hasPos()`;
`config/.../GenesisConfigOptions.java:38-98`), and a **fixed if/else dispatch** in
`BesuController.Builder.fromGenesisFile` (`app/.../controller/BesuController.java:344-397`) maps those
predicates to hard-coded `*BesuControllerBuilder` implementations. So besu is **richer than core-geth on the
consensus axis** (it selects among five co-equal mechanisms, not a 3-value engine enum branched on config
pointers) but **still a closed, hand-maintained dispatch, not a self-declaring plugin registry** — adding a
mechanism means editing the if/else chain, adding a `*ConfigOptions` predicate, and writing a new
controller-builder. besu is also **the reference client for private PoA networks** (the operator
`generate-blockchain-config` subcommand + genesis `extraData` signer/validator encoding), which makes it the
direct authority for fukuii's NET-02 private-network goal — with one large caveat: **Clique block
*production* has been removed** (see Authority note / Gotchas); a new PoA network in modern besu uses IBFT2 or
QBFT.

## Key types / interfaces / files
- `config/.../GenesisConfigOptions.java:31-465` — **`GenesisConfigOptions`**, the one interface a network's
  rule set is read through. Consensus-selection predicates: `isEthHash()` (`:38`), `isIbftLegacy()` (`:45`),
  `isIbft2()` (`:52`), `isQbft()` (`:59`), `isClique()` (`:66`), `isPoa()` (`:73`), `hasPos()` (`:82`),
  `getConsensusEngine()` (`:98`, returns the engine name string), `getPowAlgorithm()` (`:413`). Per-mechanism
  config sub-objects: `getCliqueConfigOptions()` (`:119`), `getBftConfigOptions()`/`getQbftConfigOptions()`
  (`:126`/`:133`), `getIbftLegacyConfigOptions()` (`:105`), `getFixedDifficultyConfigOptions()` (`:147`). Fork
  schedule declared as **block-number milestones** (`getHomesteadBlockNumber()`…`getMergeNetSplitBlockNumber()`,
  `:154-245`) **and timestamp milestones** (`getShanghaiTime()`…`getOsakaTime()`/`getBpo1Time()`…/
  `getAmsterdamTime()`, `:252-315`) on the same interface — same block-vs-timestamp split as geth.
- `config/.../JsonGenesisConfigOptions.java:41-168` — the **sole concrete implementation** (JSON-backed).
  The mechanism predicates are pure **key-presence checks** on the genesis `config` object:
  `isEthHash()` = has `"ethash"` or `"fixeddifficulty"` (`:136-138`), `isClique()` = has `"clique"`
  (`:146-148`), `isIbft2()` = has `"ibft2"` (`:151-153`), `isQbft()` = has `"qbft"` (`:156-158`),
  `isPoa()` = any of qbft/clique/ibft2/ibftLegacy (`:161-163`), `hasPos()` = a terminal-total-difficulty is
  present (`:166-168`). `getConsensusEngine()` (`:118-133`) resolves the engine name by the *same ordered*
  ethash→ibft2→ibftLegacy→qbft→clique fallthrough, defaulting to `"unknown"`. The config-key constants are at
  `:41-48` (`ethash`/`fixeddifficulty`/`ibft`/`ibft2`/`qbft`/`clique`). **One schema, N mechanisms** —
  contrast core-geth's *N schemas behind an interface*.
- `config/.../PowAlgorithm.java:20-22` — the **PoW-algorithm enum is only `UNSUPPORTED` and `ETHASH`**;
  `getPowAlgorithm()` returns `ETHASH` iff `isEthHash()` else `UNSUPPORTED`
  (`JsonGenesisConfigOptions.java:412-413`). besu's PoW support is **ethash-only** — no keccak/etchash/lyra2
  (contrast core-geth's third `lyra2` engine). The `"fixeddifficulty"` key is a dev/test ethash variant.
- `app/.../controller/BesuController.java:344-397` — **`fromGenesisFile(genesisConfig, syncMode)`**, the
  consensus-mechanism → controller dispatch. Ordered branches: PoW (`getPowAlgorithm() != UNSUPPORTED`) →
  `MainnetBesuControllerBuilder` (`:355-356`); `isIbft2()` → `IbftBesuControllerBuilder` (`:357-358`);
  `isIbftLegacy()` → **throws** ("IBFT1 legacy no longer supported", `:359-361`); `isQbft()` →
  `QbftBesuControllerBuilder` (`:362-363`); `isClique()` → `CliqueBesuControllerBuilder` **only if a TTD is
  present**, else **throws** "Clique Block Production (mining) is no longer supported… still possible to sync
  existing Clique networks if migrated to PoS" (`:364-372`); no recognized mechanism + TTD → PoS
  (`MainnetBesuControllerBuilder` for pre-merge validation, `:373-377`); nothing + no TTD → pure-PoS
  `MergeBesuControllerBuilder` (`:378-382`). This hard-coded if/else **is** the multi-consensus seam — and
  its ceiling.
- `app/.../controller/BesuController.java:384-395` — **`TransitionBesuControllerBuilder`** wrap: when a TTD is
  present, the pre-merge builder is composed with `MergeBesuControllerBuilder` so one chain runs PoW/PoA
  *then* PoS across the merge. Consensus mechanism can **change over the chain's life**, not just be selected
  once.
- `app/.../controller/BesuController.java:399-421` — **`createConsensusScheduleBesuControllerBuilder`** +
  **`ConsensusScheduleBesuControllerBuilder`**: for BFT `isConsensusMigration()` genesis (IBFT2→QBFT), builds
  a **block-keyed `Map<Long, BesuControllerBuilder>` schedule** (`:401,413,417-419`) so the active consensus
  controller switches at a configured block. A second, scheduled form of "consensus changes mid-chain."
- `app/.../controller/` — the fixed set of controller-builders that the dispatch chooses among:
  `MainnetBesuControllerBuilder` (PoW/ethash), `CliqueBesuControllerBuilder`, `IbftBesuControllerBuilder`
  (IBFT2), `QbftBesuControllerBuilder`, `IbftLegacyBesuControllerBuilder`, `MergeBesuControllerBuilder` (PoS),
  and the two composites (`TransitionBesuControllerBuilder`, `ConsensusScheduleBesuControllerBuilder`). Adding
  a consensus family means adding one of these + a predicate + a dispatch branch — not registering a plugin.
- `config/.../NetworkDefinition.java:22-247` — the **named-network enum** (besu's `NetworkName` successor):
  `MAINNET`(chainId 1)/`SEPOLIA`(11155111)/`HOODI`(560048)/`EPHEMERY`/`LINEA_MAINNET`/`LINEA_SEPOLIA`/`LUKSO`/
  `DEV`(1337)/`FUTURE_EIPS`/`EXPERIMENTAL_EIPS`. Each entry carries a **built-in genesis resource path**
  (`getGenesisFile()`, `:151`), chainId, networkId, `canSnapSync`, `nativeRequired`, `targetGasLimit`, and an
  optional deprecation date (`:111-144`). `fromChainId()` (`:239-246`) reverse-looks-up by chain id. Note the
  list now includes **third-party L2/alt-L1 chains** (Linea, Lukso) as first-class named networks — the enum
  is a curated registry, not only Ethereum's own testnets.
- `config/src/main/resources/{mainnet,sepolia,hoodi,dev}.json` — the **embedded genesis resources** each
  `NetworkDefinition` entry points at (`EthNetworkConfig.getNetworkConfig` loads them via `GenesisConfig
  .fromSource`, `app/.../cli/config/EthNetworkConfig.java:70-94`).
- `app/.../cli/config/EthNetworkConfig.java:40-113` — **`EthNetworkConfig`** record (`genesisConfig`,
  `networkId`, boot nodes, DNS discovery url) — the resolved runtime network value. `getNetworkConfig(
  NetworkDefinition)` (`:70`) reads the enum's genesis resource and pulls boot/ENR nodes + DNS discovery from
  the genesis `discovery` block (`:73-93`).
- `app/.../cli/BesuCommand.java:467-508` — **`--network`** (`:470`, parses to a `NetworkDefinition` value,
  `:479-480`), **`--genesis-file`** (`:379-383`, "Cannot be used with `--network`"; requires `--network-id`),
  **`--network-id`** (`:505-508`). `readGenesisConfig()` (`:1698-1707`) is the resolution point: EPHEMERY →
  time-derived genesis; else `--genesis-file` → load+transform; else the named network's resource (defaulting
  to `MAINNET`). Same "built-in switch OR custom genesis file, converging on one `GenesisConfig`" shape as
  geth, but the custom file can select a PoA/BFT mechanism the built-ins don't.
- `app/.../cli/BesuCommand.java:1726-1796` — **geth-format genesis auto-transform**: `loadAndTransformGenesis
  File` detects a geth-shaped genesis (`isGethFormat` = has `config`, has `mergeNetsplitBlock`, lacks
  `ethash`; `:1760`) and rewrites it to besu's dialect (`transformGethToBesu`, `:1796`) before loading. A
  **one-way ingest** of geth JSON — parallels core-geth's dual-schema auto-detection but as a transform, not a
  second first-class schema.
- `app/.../cli/subcommands/operator/GenerateBlockchainConfig.java:60-278` — the **`operator
  generate-blockchain-config` subcommand**: the canonical way to stand up a **private BFT network**. Reads an
  operator config (genesis + a `blockchain.nodes` block), optionally **generates N node keypairs**
  (`generateNodesKeys`, `:191-231`), collects their addresses, and **encodes them into the genesis
  `extraData`** as the initial validator set (`processExtraData`, `:234-253`) — IBFT2 via
  `IbftExtraDataCodec.encodeFromAddresses` (`:244-246`), QBFT via `QbftExtraDataCodec` (`:249-251`). This is
  the private-PoA-network authority mechanism.
- `consensus/clique/.../CliqueExtraData.java:40-68` — **Clique `extraData` layout**: 32-byte vanity prefix
  (`EXTRA_VANITY_LENGTH`, `:44`) + the validator/signer `List<Address>` + a proposer seal signature — the
  Clique PoA signer set lives in the genesis header's `extraData`, read/written here.
- `config/.../JsonCliqueConfigOptions.java:25-73` — Clique tuning defaults: **`epochlength`** (default 30_000
  blocks, `:25,50-52`), **`blockperiodseconds`** (default 15s, `:26,60-63`), **`createemptyblocks`** (default
  true, `:27,70-73`). These are the block-period / epoch knobs a private Clique genesis sets.
- `ethereum/permissioning/.../PermissioningConfiguration.java:19-34` — **permissioning config** now carries
  only `Optional<LocalPermissioningConfiguration>` (`:21`); `createDefault()` is empty (`:31-33`).
- `app/.../cli/options/PermissionsOptions.java:34-52` — the **permissioning CLI surface**:
  `--permissions-nodes-config-file[-enabled]` and `--permissions-accounts-config-file[-enabled]` — **local
  allowlist-file** node/account permissioning only. (Smart-contract/on-chain permissioning *controllers* still
  exist under `ethereum/permissioning/{node,account}/` — `NodePermissioningController`,
  `AccountPermissioningControllerFactory`, `TransactionSmartContractPermissioningController` — but the
  on-chain-permissioning CLI flags are **not present in this revision**; only file-based local permissioning
  is operator-exposed here.)

## Design decisions & rationale
- **The genesis `config` block positively selects the consensus mechanism.** The defining choice and the
  spectrum-topping fact: a `"clique"` / `"ibft2"` / `"qbft"` / `"ethash"` sub-object in genesis *is* the
  mechanism selector (`JsonGenesisConfigOptions.java:136-163`). PoW, PoA (Clique), and BFT (IBFT2/QBFT) are
  **co-equal configured mechanisms**, each with a full consensus subsystem — not, as in geth, one family with
  a vestigial clique option, nor, as in core-geth, one PoW schema plus a closed 3-engine enum. This is why the
  authority model names besu the multi-consensus / PoA reference.
- **Selection is a fixed if/else over predicates, mapping to hard-coded controller-builders.**
  `fromGenesisFile` (`BesuController.java:344-397`) is a hand-maintained dispatch, and
  `ConsensusScheduleBesuControllerBuilder` (`:399-421`) is a hand-built block schedule. besu deliberately
  keeps consensus mechanisms as a **known, closed set of first-class subsystems** rather than a plugin point —
  richer selection than core-geth, but the same "editing the client, not registering a module" ceiling.
- **Consensus can change over a chain's lifetime, two ways.** `TransitionBesuControllerBuilder` composes a
  pre-merge builder with the PoS `MergeBesuControllerBuilder` across the merge (`:384-395`);
  `ConsensusScheduleBesuControllerBuilder` runs a **block-keyed schedule** of builders for BFT migration
  (IBFT2→QBFT, `:399-421`). besu treats "which consensus is active" as a function of block height, not a
  single boot-time constant — a capability geth/core-geth express only via the merge beacon wrapper.
- **One JSON config schema, not many.** Unlike core-geth (two interchangeable schemas behind a
  `Configurator` interface), besu has a single `GenesisConfigOptions` interface with one JSON implementation;
  multi-network richness lives on the **consensus-selection axis**, not the config-schema axis. Cross-client
  ingest is handled by a **one-way geth→besu genesis transform** (`BesuCommand.java:1726-1796`), not by a
  second self-describing schema.
- **Private BFT networks are a first-class operator workflow.** `generate-blockchain-config`
  (`GenerateBlockchainConfig.java`) generates validator keypairs and bakes their addresses into genesis
  `extraData` (`:234-253`) — besu ships the tooling to *originate* a private PoA/BFT chain, not just run one.
  This is the enterprise/consortium use case besu is built around and the direct model for fukuii's NET-02.
- **Clique block production was intentionally removed.** `fromGenesisFile` throws for a Clique genesis
  without a TTD (`:364-372`) — modern besu will *sync* a Clique chain that migrated to PoS but will not
  *mine/propose* Clique blocks. The rationale (per the error text) is that Clique existed as a pre-merge PoA
  and its production path was retired; IBFT2/QBFT are the supported PoA production mechanisms now.

## Notable patterns (the reusable idea)
1. **Genesis-config key positively selects the consensus mechanism** (`"clique"`/`"ibft2"`/`"qbft"`/`"ethash"`
   presence). The nameable pattern for the observations table: besu's "genesis positively selects a mechanism
   from a fixed set of first-class consensus subsystems" pole — one tier richer than core-geth's
   "config-schema pluggability, closed engine enum," one tier below a self-declaring plugin registry.
2. **Predicate-keyed controller-builder dispatch** (`GenesisConfigOptions.isX()` → a hard-coded
   `*BesuControllerBuilder`). Multi-consensus as a closed, hand-maintained if/else, not a registry.
3. **Consensus-over-time as a block-keyed builder schedule** (`ConsensusScheduleBesuControllerBuilder`) and a
   merge transition composite (`TransitionBesuControllerBuilder`) — the active consensus is a function of
   block height.
4. **`extraData`-encoded validator set + generator tooling** (`generate-blockchain-config` →
   IBFT/QBFT `ExtraDataCodec.encodeFromAddresses`) as the private-network origination workflow.
5. **Named-network enum → embedded genesis resource** (`NetworkDefinition` → `/{name}.json`), extended to
   third-party L2/alt-L1 chains (Linea, Lukso) as first-class entries — a curated built-in registry.
6. **One-way geth→besu genesis transform** as the cross-client ingest path (vs core-geth's dual-schema
   auto-detection).

## Position on the pluggability spectrum
besu sits **above core-geth, below (reported) nethermind** on consensus-family pluggability, and it is
**strong on a different axis than core-geth**:
- **vs geth (weak / single-family):** besu has a genuine multi-consensus abstraction — five co-equal
  mechanisms positively selected by genesis config, each a full subsystem — where geth is effectively
  ethash-only + a vestigial clique config option.
- **vs core-geth (config-schema pluggability, closed engine enum):** the two are rich on *different* axes.
  core-geth is rich on **config schema** (a `Configurator` interface + two interchangeable schemas +
  reflection conversion) but its engine set is a closed 3-value enum (`ethash`/`clique`/`lyra2`) dispatched on
  concrete config pointers. besu is rich on **consensus mechanism** (five first-class mechanisms incl. two BFT
  families + Clique + a merge-transition + a BFT-migration schedule) but has **one** JSON config schema. besu
  selects the *mechanism* from genesis; core-geth selects the *schema* from genesis. For fukuii's
  `NetworkFamily` ambition, besu is the better model for **how to structure multiple co-equal consensus
  families as pluggable subsystems**; core-geth remains the better model for **multiple config schemas / the
  ETC per-EIP config content**.
- **vs nethermind (forward-ref, no verdict):** besu's dispatch is still a **fixed if/else over
  genesis-key predicates → hard-coded controller-builders** (`BesuController.java:344-421`) — adding a
  consensus family edits the client. It is **not** a self-declaring plugin registry where a network registers
  its own seal-engine/rules module. nethermind (next-documented, reportedly the strongest self-declaring
  plugin model) is where to look for that deepest form; this doc forward-refs it without a verdict.

## Authority note
**For `multi-network`, besu is a STRONG authority — the multi-consensus-selection and private-PoA-network
reference — and the JVM structural mirror closest to fukuii.** It is the client to study for:
- **Consensus-mechanism selection from genesis config** (`isEthHash`/`isClique`/`isIbft2`/`isQbft` →
  controller-builder dispatch). Directly relevant to fukuii's PoW/PoS/PoA `NetworkFamily` model and to the
  `consensus/{pow,pos,poa}` mechanism-category file tree (memory: file-tree-seam-direction).
- **Consensus-over-time composition** (`TransitionBesuControllerBuilder`, `ConsensusScheduleBesuController
  Builder`) — a pattern fukuii's dual-family dispatch can learn from for merge/migration handling.
- **Private / consortium PoA network origination** — `generate-blockchain-config`, genesis `extraData`
  validator encoding, Clique `epochlength`/`blockperiodseconds` knobs. This is **the direct reference for
  fukuii's NET-02 private-network stack** (memory: file-tree-seam-direction — "NET-02 Clique leads").

**Critical NET-02 caveat (also a Gotcha):** modern besu **removed Clique block *production*** — it will only
*sync* a Clique chain that migrated to PoS, and throws on a mining Clique genesis
(`BesuController.java:364-372`). So besu is the authority for **Clique validation/sync semantics and
`extraData`/config layout**, and for **PoA block *production* via IBFT2/QBFT**, but it is **not** a current
reference for *Clique block production*. If NET-02 specifically wants Clique *mining*, the production-path
reference is **core-geth** (whose `clique` engine is still a live production engine) or an older
besu/go-ethereum; besu's live PoA production is IBFT2/QBFT. Surface this to the operator when NET-02 scopes
"Clique leads."

besu is **not** the authority for: PoW/ETC consensus content (core-geth — besu's PoW is ethash-only, no
etchash/ECIP), the ETH/PoS baseline config content (go-ethereum), a self-declaring consensus-plugin registry
(nethermind, forward-ref), or the config-schema-polymorphism axis (core-geth).

## Gotchas / anti-patterns / things they later changed
- **Clique block production is gone** (`BesuController.java:364-372`) — see Authority note. The single most
  important gotcha for anyone citing besu as a "Clique dev/PoA mining" reference: that path was removed. Only
  Clique-that-migrated-to-PoS is runnable, sync-only.
- **PoW is ethash-only** (`PowAlgorithm.java:20-22`, `JsonGenesisConfigOptions.java:412-413`). No keccak,
  etchash, or lyra2. Do not cite besu for any non-ethash PoW; core-geth is the multi-PoW-variant reference.
- **No multi-mechanism ambiguity guard.** A genesis with more than one consensus key (e.g. both `ethash` and
  `clique`) is not rejected — both `getConsensusEngine()` (`JsonGenesisConfigOptions.java:118-133`) and
  `fromGenesisFile` (`BesuController.java:355-382`) silently take the **first** matched branch in a fixed
  order (PoW → ibft2 → ibftLegacy → qbft → clique). besu shares geth's "no positive multi-engine error" gap;
  nethermind's `CalculateSealEngineType` reportedly throws instead. A fukuii multi-family config layer wants
  an explicit ambiguity guard neither geth nor besu has.
- **IBFT1 (legacy `ibft`) is a hard error on boot** (`BesuController.java:359-361`) — supported only inside a
  `ConsensusScheduleBesuControllerBuilder` IBFT1→QBFT *migration*, not as a standalone mechanism. Treat
  `ibftLegacy` config as migration-only.
- **Adding a consensus family is a client edit, not a plugin registration.** The if/else in `fromGenesisFile`
  + a new `*ConfigOptions` predicate + a new `*BesuControllerBuilder` must all be hand-written. Do not cite
  besu as a plugin-architecture reference for consensus — that is nethermind (forward-ref).
- **`--genesis-file` and `--network` are mutually exclusive, and `--genesis-file` requires `--network-id`**
  (`BesuCommand.java:379-383`). A custom-genesis private network must set the network id explicitly; there is
  no genesis-hash-derived default the way geth silently defaults to mainnet.
- **On-chain (smart-contract) permissioning is not CLI-exposed in this revision.** The controllers exist under
  `ethereum/permissioning/{node,account}/`, but `PermissionsOptions.java:34-52` exposes only the local
  allowlist-file flags, and `PermissioningConfiguration` (`:19-34`) carries only `localConfig`. Don't assume
  besu's historically-documented on-chain permissioning is wired here — verify against the CLI surface.
- **`NetworkDefinition` now includes third-party chains** (Linea, Lukso) and deprecated entries
  (`isDeprecated()`, `:197-208`). The named-network list is curated and changes across releases — don't treat
  it as a stable Ethereum-only set.
