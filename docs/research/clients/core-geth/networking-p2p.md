# core-geth — networking-p2p
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary
core-geth inherits go-ethereum's devp2p/RLPx stack wholesale: `p2p/` (transport,
discovery v4/v5, ENR), `eth/protocols/eth` (the `eth` wire protocol), and `eth/handler.go`
(peer lifecycle, block/tx propagation) are structurally vanilla geth. The **ETC-specific
divergence is entirely in the network *configuration* fed into that stack**: the bootstrap
node lists, the ENR-tree DNS discovery endpoints, the chain/network IDs, and the
config-driven ForkID fork schedule. This is the one subsystem where core-geth is the ETC
byte-authority — everything else in P2P is inherited geth.

Note: MESS / ECBP-1100 (subjective fork-choice / reorg resistance) is a **sync/consensus**
concern, not a wire concern — it is *not* referenced in `core/forkid/forkid.go` (verified).
Do not document it here; it is ★-covered elsewhere.

## Key types / interfaces / files
- `params/bootnodes_classic.go:20` — `ClassicBootnodes` (3 enode URLs: AMS/NYC/SFO on
  DigitalOcean IPs). ETC mainnet bootstrap set.
- `params/bootnodes_classic.go:30` — `dnsPrefixETC = "enrtree://AJE62Q4DUX4QMMXEHCSSCSC65TDHZYSMONSD64P3WULVLSF6MRQ3K@"`
  and `ClassicDNSNetwork1 = dnsPrefixETC + "all.classic.blockd.info"`. ETC uses the
  **`blockd.info`** ENR-tree host, *not* geth's `ethdisco.net`.
- `params/bootnodes_mordor.go:22` — `MordorBootnodes` (1 enode, `@etccoop-sfo`) and
  `MordorDNSNetwork1 = dnsPrefixETC + "all.mordor.blockd.info"`.
- `params/bootnodes.go:76` — `KnownDNSNetwork(genesis, protocol)` is the **unmodified geth**
  helper — its switch only knows `mainnet`/`sepolia`/`holesky` and returns `""` for ETC.
  ETC DNS is therefore wired by a *separate* path (below), not through this function.
- `cmd/utils/flags.go:2277` — `SetDNSDiscoveryDefaults2(cfg, url)` — **core-geth-added**
  variant that sets DNS discovery from an explicit ENR-tree URL string, used at
  `flags.go:2183` (`ClassicDNSNetwork1`) and `:2185` (`MordorDNSNetwork1`). Vanilla geth's
  `SetDNSDiscoveryDefaults(cfg, genesisHash)` (`:2290`) is used for the ETH nets. This
  URL-vs-genesis-hash split is the concrete ETC divergence in discovery wiring.
- `params/config_classic.go:35` — ETC mainnet `ChainID = 61`, `NetworkID = 1`.
- `params/config_classic.go:120` — a Classic *test* variant `ChainID = 6161`, `NetworkID = 1`.
- `params/config_mordor.go:30` — Mordor `ChainID = 63`, `NetworkID = 7`.
- `eth/protocols/eth/protocol.go:33-43` — wire versions: constants `ETH67 = 67`, `ETH68 = 68`;
  `ProtocolVersions = []uint{ETH68}`; `protocolLengths = {ETH68: 17}`. **This 2025 tree
  advertises only `eth/68`** (structurally the same wire code as contemporaneous geth).
- `core/forkid/forkid.go:72,238` — `NewID(...)` → `gatherForks(config, genesis)` →
  `confp.BlockForks(config)` / `confp.TimeForks(config, genesis)` (`params/confp/configurator.go:455,498`).

## Design decisions & rationale
- **Config-driven ForkID enumeration.** Vanilla geth hardcodes the fork block list per chain
  inside `forkid`. core-geth (a "multi-geth") instead derives the fork schedule generically
  from the `ctypes.ChainConfigurator` interface via `confp.BlockForks`/`TimeForks`. This is
  what lets one binary compute a correct ForkID hash for ETC's idiosyncratic schedule
  (EIP2…EIP6049 plus ECIP-1017/1010/1099/ECBP-1100) without chain-specific code —
  see `params/config_classic.go:38-105` for the ETC block schedule that feeds it.
- **URL-based DNS discovery for ETC.** ETC's public ENR trees live on a third-party host
  (`blockd.info`) rather than the EF-run `ethdisco.net`, so core-geth exposes them as plain
  URL constants and injects them with `SetDNSDiscoveryDefaults2` instead of the genesis-hash
  lookup table — decoupling ETC discovery from EF infrastructure.

## Notable patterns (the reusable idea)
The reusable idea is **separation of "wire mechanism" (inherited, unchanged) from "network
identity" (config data)**. All ETC-ness is expressed as data — bootnode slices, DNS URL
constants, chain/network ID ints, and a config object that a generic ForkID walker reads —
not as forked protocol code. A multi-network client can add a chain by supplying those four
data artifacts and touching zero wire-protocol logic.

## Authority note
core-geth is **authoritative for ETC network-config, bootnodes, DNS ENR-trees, chain/network
IDs, and the ForkID fork schedule** — these files are the ETC reference. The `eth` wire
protocol, RLPx, and discovery v4/v5 *code* are inherited from go-ethereum and are not ETC
authority (any recent geth is equivalent for wire structure). go-ethereum + besu/erigon/
reth/nethermind are ETH/PoS-centric and are *not* the ETC network-config authority.

## Gotchas / anti-patterns / things they later changed
- **Wire versions are frozen at `eth/68`.** Vanilla geth HEAD (59e89e81e) advertises
  `ProtocolVersions = {ETH71, ETH70, ETH69}` with `ETH69/70/71` constants; this upstream
  core-geth ref has only `ETH67/ETH68` and advertises `{ETH68}`. A fukuii port targeting
  current geth peers must carry the newer wire versions itself — core-geth-at-upstream is
  behind and will not interoperate on eth/69+.
- **`KnownDNSNetwork` returns `""` for ETC.** Because the inherited geth helper doesn't know
  ETC genesis hashes, anyone reusing that function for ETC gets an empty string; ETC discovery
  only works through the separate `SetDNSDiscoveryDefaults2` + `ClassicDNSNetwork1`/`MordorDNSNetwork1`
  path. Easy to miss when tracing discovery setup.
- **Sparse bootnode sets.** ETC mainnet ships only 3 bootnodes and Mordor only 1 — far fewer
  than ETH's redundant EF fleet, and pinned to specific hosting IPs that can (and do) rot.
- Mordor's `NetworkID` is **7** (not 63) while its `ChainID` is 63 — the two are not equal
  for Mordor, unlike many chains where they coincide.
