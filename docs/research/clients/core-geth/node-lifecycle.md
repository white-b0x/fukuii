# core-geth — node-lifecycle
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary
core-geth inherits go-ethereum's node bring-up wholesale: the `node/` service container
(`node.Node`, service registration, lifecycle Start/Stop), `cmd/geth` (the `geth` entrypoint,
console/attach, chain import/export subcommands), and `eth/backend.go` (the `eth.Ethereum`
full-node service). The **ETC divergence is at the CLI/config-selection layer**: network
*preset* boolean flags (`--classic`, `--mordor`, `--mintme`) that resolve to ETC
chain-configs, genesis blocks, bootnodes, DNS discovery, datadir subdirs, Ethash dataset
dirs, and gas-limit defaults. Genesis/chainspec *loading* uses core-geth's multi-schema
genesis machinery — that is ★ multi-network territory (cross-referenced, not duplicated here).

## Key types / interfaces / files
- `cmd/utils/flags.go:165` — `ClassicFlag = &cli.BoolFlag{Name: "classic", Usage: "Ethereum
  Classic network: pre-configured Ethereum Classic mainnet"}`.
- `cmd/utils/flags.go:180` — `MordorFlag` (`--mordor`, "Ethereum Classic's cross-client
  proof-of-work test network"); `:175` `MintMeFlag` (`--mintme`).
- `cmd/utils/flags.go:1948` — `CheckExclusive(ctx, MainnetFlag, DeveloperFlag, DeveloperPoWFlag,
  SepoliaFlag, ClassicFlag, MordorFlag, MintMeFlag, HoleskyFlag)` — the network presets are
  mutually exclusive (one binary, pick one network).
- `cmd/utils/flags.go:1208,1245` — bootnode selection dispatches on the flag
  (`ClassicFlag` → `ClassicBootnodes`, `MordorFlag` → `MordorBootnodes`, etc.).
- `cmd/utils/flags.go:2183,2185` — DNS discovery defaults set to `ClassicDNSNetwork1` /
  `MordorDNSNetwork1` per flag (see `networking-p2p.md`).
- `cmd/utils/flags.go:2506` — `genesis = params.DefaultClassicGenesisBlock()` in the
  genesis-selection switch (Mordor/MintMe analogues alongside).
- `cmd/utils/flags.go:1688-1694` — datadir subdir per network: `--classic` → `<datadir>/classic`,
  `--mordor` → `<datadir>/mordor`.
- `cmd/utils/flags.go:1788,1812` — Ethash `DatasetDir`/`CacheDir` defaults branch for
  classic/mordor (PoW DAG placement).
- `cmd/utils/flags.go:1857-1858` — "For classic and mordor chains, maintain the gas limit at
  8M" — ETC-specific mining gas-limit default.
- `cmd/geth/main.go:350-354` — startup banner: "Starting Geth on Ethereum Classic…" /
  "Starting Geth on Mordor testnet…" logged per flag.
- `cmd/geth/consolecmd_cg_test.go:32-38` — asserts `--classic` → networkID 1 / chainID 61 /
  Mainnet(ETC) genesis hash, `--mordor` → networkID 7 / chainID 63 / Mordor genesis hash;
  `--classic --networkid 42` overrides networkID while keeping chainID 61.

## Design decisions & rationale
- **Preset flags over chainspec files for the built-in networks.** ETC/Mordor/MintMe are
  first-class `--classic`/`--mordor`/`--mintme` presets (mirroring geth's `--mainnet`/
  `--sepolia`), each wiring config + genesis + bootnodes + DNS + datadir + Ethash dirs in one
  switch, so operators don't hand-assemble a chainspec for the common ETC case.
- **networkID and chainID are decoupled.** `--classic --networkid 42` keeps chainID 61 but
  changes the P2P networkID (private ETC-rules network) — a deliberate seam for consortium/
  enterprise reuse of ETC consensus on an isolated network. This is the fukuii use-case lens:
  the same mechanism that runs public ETC also stands up private ETC-rules networks.
- **ETC PoW defaults carried through lifecycle.** 8M gas-limit default and Ethash dataset/cache
  dir handling are set at node-config time because ETC's PoW economics differ from post-merge
  ETH.

## Notable patterns (the reusable idea)
**Network identity is resolved once, centrally, at CLI→config translation.** A single
preset-flag switch fans out to every downstream default (genesis, bootnodes, DNS, datadir,
gas limit, DAG dir). Adding/altering a network is a localized edit to that switch plus the
data artifacts it points at — the service container and backend never learn about specific
chains. fukuii's HOCON network-selection + datadir discipline is the analogue.

## Authority note
core-geth is **not** the node-lifecycle authority — `node/`, `cmd/geth`, and `eth/backend.go`
service wiring are inherited from go-ethereum. Its authority is the **ETC network-preset
mapping** (flag → chainID/networkID/genesis/bootnodes/gas-limit), which is the reference for
what `--classic`/`--mordor` must resolve to. Multi-schema genesis/chainspec loading is
★ multi-network — see that doc, not this one.

## Gotchas / anti-patterns / things they later changed
- **Preset flags are mutually exclusive** (`CheckExclusive`) — you cannot combine `--classic`
  with another network flag; the binary is single-network per run.
- **Datadir is network-scoped** (`<datadir>/classic`, `<datadir>/mordor`) — switching flags
  points at a *different* chaindata dir, so an operator toggling `--classic`↔`--mordor` on the
  same `--datadir` does not corrupt/mix state, but also won't "find" the other chain's data.
- The startup path still logs and self-identifies as "**Geth**" (multi-geth heritage) even on
  ETC — cosmetic, but a tell that this is a go-ethereum fork, not a from-scratch client.
- Genesis selection (`flags.go:2506` etc.) is a 2025 snapshot of the built-in network set;
  post-Mystique/Olympia ETC config lives in `params/config_classic.go`'s fork schedule and
  must be kept current independently — the lifecycle switch just selects the config object.
