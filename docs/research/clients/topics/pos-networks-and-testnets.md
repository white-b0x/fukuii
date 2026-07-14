# Topic — PoS networks & testnets (built-in network inventory, current + deprecated)
_Documented 2026-07-13. Cross-client survey (git-archaeology for deprecated). Complements multi-network.md (selection) with the concrete network inventory._

Scope: the built-in Ethereum PoS network/testnet configs each reference client
ships, current and deprecated. This is the **network-inventory** complement to the
per-client `multi-network.md` (which documents the *selection mechanism* — how a
client dispatches between families/networks) and `consensus-engines.md`. Where those
answer "how does the client pick a network," this answers "which concrete PoS
networks exist, with what chain IDs and timestamp-fork schedules, and which were
removed and when."

Reference refs surveyed (upstream/clean, per SR convention):
go-ethereum `59e89e81e`, core-geth `b28aa0a0b`, besu `3fd233a4f9`,
nethermind `0d09a09ed`, erigon `f1d79d699e`, reth `3d76b93c2`.

PoW/ETC testnets (Mordor, Kotti, Astor, Morden) and PoA testnets (Kovan, Rinkeby's
Clique era, Goerli's Clique era) are the province of
`consensus-poa-and-etc-testnets.md` and `consensus-pow-cpu-dev-and-deprecated.md`;
this topic touches them only where a network crossed from PoW/PoA into (or out of)
the PoS testnet set.

---

## Current PoS networks/testnets (mainnet, Sepolia, Holesky, Hoodi, Ephemery)

The live Ethereum PoS network set as of the surveyed refs. All are **timestamp-fork
dispatched** (Shanghai/Cancun/Prague/Osaka activate at a Unix timestamp, not a block
number — see `consensus-engines.md` for dispatch mechanics).

| Network | Chain ID | Nature | Fork dispatch | Deposit contract |
|---------|----------|--------|---------------|------------------|
| **ETH mainnet** | 1 | Merged 2022 (TTD `58750000000000000000000`) | block→timestamp overlay | `0x00000000219ab540356cBB839Cbe05303d7705Fa` |
| **Sepolia** | 11155111 | **Merged** testnet (TTD `17000000000000000`, MergeNetsplitBlock `1735371`) | block→timestamp | `0x7f02c3e3c98b133055b8b348b2ac625669ed295d` (permissioned custom) |
| **Holesky** | 17000 | **PoS-from-genesis** (TTD `0`) | timestamp only | `0x4242424242424242424242424242424242424242` |
| **Hoodi** | 560048 | **PoS-from-genesis** (TTD `0`, MergeNetsplitBlock `0`) | timestamp only | `0x00000000219ab540356cBB839Cbe05303d7705Fa` (mainnet contract) |
| **Ephemery** | 39438135 (base, rotates) | Ephemeral PoS-from-genesis, periodic reset | timestamp only | `0x00000000219ab540356cbb839cbe05303d7705fa` (mainnet contract) |

**Sepolia** (go-ethereum `params/config.go:113–147`, added `e1c000b0d`, 2021-11-08,
#23730). Launched 2021 as a PoW testnet, **merged** — so it carries a historical TTD
(`17000000000000000`) and a `MergeNetsplitBlock` (`1735371`). Timestamp forks:
`ShanghaiTime 1677557088` (2023-02-28), `CancunTime 1706655072` (2024-01-30),
`PragueTime 1741159776` (2025-03-05), `OsakaTime 1760427360` (2025-10-14), plus BPO
blob-schedule bumps (`BPO1Time 1761017184`, `BPO2Time 1761607008`). Sepolia's
validator set is **permissioned** and its deposit contract is a custom address, not
the canonical mainnet one — a genesis-config specific worth noting for any client
that hardcodes the deposit contract. This is **fukuii's current PoS testnet**.

**Holesky** (`params/config.go:78–112`, `HoleskyGenesisHash
0xb5f7f912…4661bde4`). Launched September 2023 as a **pure-PoS-from-genesis** large
validator testnet (TTD `0`, no `MergeNetsplitBlock`, `MuirGlacierBlock nil`).
Timestamp forks: `ShanghaiTime 1696000704` (2023-09-29), `CancunTime 1707305664`
(2024-02-07), `PragueTime 1740434112` (2025-02-24), `OsakaTime 1759308480`
(2025-10-01). Deposit contract `0x4242…4242` (the well-known testnet placeholder).
**Holesky is being wound down** in favor of Hoodi for validator testing — reflected
in the archaeology below: three of six clients have already *removed* it.

**Hoodi** (`params/config.go:148–183`, added `668118bfe`, 2025-03-18, #31406,
`HoodiGenesisHash 0xbbe31286…557c971b`). The **2025 replacement large-validator
testnet** superseding Holesky. PoS-from-genesis (TTD `0`, `MergeNetsplitBlock 0`),
with `ShanghaiTime`/`CancunTime` at genesis (`0`) and later forks by timestamp:
`PragueTime 1742999832` (2025-03-26), `OsakaTime 1761677592` (2025-10-28), BPO1/BPO2
following. Notably Hoodi reuses the **canonical mainnet deposit contract address**
(`0x0000…7705Fa`), unlike Holesky/Sepolia.

**Ephemery** (chain ID base `39438135`). An *ephemeral* testnet that resets on a
fixed period; the chain ID and genesis are rotated each reset. Only **besu** ships a
built-in config for it (`config/src/main/resources/ephemery.json`), TTD `0`,
PoS-from-genesis, mainnet deposit contract, and it declares the newer
`withdrawalRequestContractAddress` / `consolidationRequestContractAddress` (EIP-7002/
EIP-7251 system contracts). The other five clients expect Ephemery to be supplied as
an external genesis/chainspec rather than baked in.

Beyond these, each client also ships a **`dev`** single-node PoS network (geth chain
ID `1337`; besu `dev.json`; reth `DEV`; erigon `Test`) for local development, and
non-Ethereum PoS/PoA networks out of scope here (Gnosis/Chiado, Linea, LUKSO, JOC,
Taiko, XDC, etc. — see `consensus-l2-rollup-sidechain.md`).

---

## Deprecated testnets (git archaeology: Goerli, Ropsten, Rinkeby, Kiln, Kovan, Morden)

Removal commits per client (`git log --diff-filter=D` / `-S` on the config/genesis
files). All dates from the surveyed upstream refs.

| Testnet | Chain ID | Era | go-ethereum | besu | nethermind | erigon | reth |
|---------|----------|-----|-------------|------|------------|--------|------|
| **Goerli** | 5 | Clique PoA → merged PoS | `3c37db798` 2024-08-20 (#30289) | `45b6c0895f` 2024-05-06 (#7049) | `c9f05d5d9` 2024-04-16 (#6900) | removed | `26b7b9720` 2024-07-05 (#9310) |
| **Ropsten** | 3 | PoW → merged briefly → removed | `095e365fa` 2023-02-09 (#26644) | `4508174ef2` 2023-01-02 (#4869) | `23d5b0065` (#5874) | removed | never carried |
| **Rinkeby** | 4 | Clique PoA | `c7c84ca16` 2023-06-02 (#27406) | `c1cfaf462a` 2023-06-08 (#5540) | `892dc7a62` (#6273) | removed | never carried |
| **Kiln** | 1337802 | Merge devnet | `690338f0f` 2023-01-19 (#26522) | `4508174ef2` 2023-01-02 (#4869) | — | — | never carried |
| **Kovan** | 42 | OpenEthereum AuRa PoA | never carried¹ | never carried¹ | never carried¹ | never carried | never carried |
| **Morden** | 2 | Ancient PoW ETH/ETC | pre-history | — | — | — | never carried |
| **Holesky**² | 17000 | PoS-from-genesis (winding down) | **retained** | `1cabf61b24` 2026-04-15 (#10165) | `cbe5569fe` 2025-10-23 (#9525) | `803c8ce8f3` 2025-10-27 (#17685) | **retained** |

¹ **Kovan** was an OpenEthereum/Parity AuRa testnet; the geth family never shipped a
built-in Kovan config (it required a supplied chainspec). Documented in the PoA
survey, not here.

² **Holesky is the notable live-deprecation-in-progress.** It is still a functioning
network but is being retired for validator testing in favor of Hoodi. Three clients
have already removed the built-in config (**nethermind** Oct 2025, **erigon** Oct
2025, **besu** Apr 2026); **go-ethereum**, **core-geth**, and **reth** still carry
it at the surveyed refs. This split is the single most decision-relevant datapoint
for fukuii's curated set (below).

Additional notes from archaeology:
- besu's `4508174ef2` (#4869, "Remove Deprecated Networks") removed Ropsten, Kiln,
  **Astor**, **Calaveras**, and **Shandong** in one commit — the latter three were
  short-lived Ethereum feature/shadow devnets.
- **reth** was born post-merge (2023) and never carried any PoW-era testnet
  (Ropsten/Rinkeby/Kiln/Kovan/Morden); it only ever had Goerli, which it purged in
  `26b7b9720` before it accumulated the legacy set.
- **Goerli** is the cleanest cross-client signal of testnet deprecation cadence: all
  five geth-family clients removed it within a ~4-month window (Apr–Aug 2024),
  following the community's Goerli→Holesky migration.
- **Kotti** (core-geth's own ETC PoA testnet, chain ID 6) was dropped in core-geth
  `bcd0423e9` — an ETC/PoA network, tracked in `consensus-poa-and-etc-testnets.md`,
  noted here only for completeness.

---

## PoS testnet structure (deposit contract, TTD vs PoS-from-genesis, timestamp forks)

Three structural axes distinguish a PoS testnet config from a PoW one, and
distinguish *merged* testnets from *PoS-from-genesis* ones:

**1. TTD (terminal total difficulty) — historical vs zero.** A **merged** network
(mainnet, Sepolia, Goerli, Ropsten) began life under PoW and transitioned at a
terminal total difficulty; its config still carries the historical `TerminalTotal
Difficulty` (Sepolia `17000000000000000`, mainnet `58750000000000000000000`) and
usually a `MergeNetsplitBlock` (Sepolia `1735371`). A **PoS-from-genesis** network
(Holesky, Hoodi, Ephemery) has `TerminalTotalDifficulty: 0` and either a zero or nil
`MergeNetsplitBlock` — there is no PoW history to merge. Post-merge clients treat TTD
as a legacy field; new testnets are all PoS-from-genesis, so the trend is toward
TTD-`0` configs and eventual removal of TTD handling entirely.

**2. Deposit contract address.** PoS consensus roots in the beacon-chain deposit
contract; the EL config records its address so the client can index deposit logs.
Three patterns appear: the **canonical mainnet contract**
(`0x0000…7705Fa` — used by mainnet, Hoodi, Ephemery), the **testnet placeholder**
(`0x4242…4242` — Holesky), and a **network-specific permissioned contract**
(`0x7f02…295d` — Sepolia). A client that hardcodes the deposit contract per network
(all six do, in their genesis/config) must get this right per network; it is a
genesis-state specific, not a fork parameter.

Newer configs additionally pin the **system contracts** for Prague+ (EIP-7002
withdrawal-request, EIP-7251 consolidation-request) — visible in besu's
`ephemery.json` (`withdrawalRequestContractAddress 0x00000961…007002`,
`consolidationRequestContractAddress 0x0000bbdd…007251`).

**3. Timestamp fork dispatch.** Post-merge forks (Shanghai, Cancun, Prague, Osaka)
and the intra-fork blob-schedule bumps (BPO1/BPO2) activate at **Unix timestamps**,
not block numbers — because block timing under PoS is regular (12s slots) but the
network wants forks pinned to wall-clock coordination. Every current PoS config is a
list of `*Time` fields (`ShanghaiTime`, `CancunTime`, `PragueTime`, `OsakaTime`,
`BPO1Time`, `BPO2Time`) plus a `BlobScheduleConfig` mapping each fork to its blob
target/max/base-fee-update-fraction. A PoS-from-genesis testnet sets the early forks
to `0` (active at genesis) and only the leading edge to a real timestamp (Hoodi:
Shanghai/Cancun `0`, Prague `1742999832`, Osaka `1761677592`).

Contrast with the PoW/ETC side (see `consensus-pow-cpu-dev-and-deprecated.md`): ETC
uses **block-number** fork dispatch (`EvmConfig.forBlock(blockNumber,
blockchainConfig)`) and has no deposit contract or TTD — the two families' network
configs are structurally different objects, which is why fukuii keeps them on
separate code paths (`AGENTS.md` "PoW vs PoS").

---

## Where network configs live per client

| Client | PoS network definitions | Genesis state / allocs | Chain-ID → spec resolution |
|--------|------------------------|------------------------|----------------------------|
| **go-ethereum** | `params/config.go` (`{Mainnet,Sepolia,Holesky,Hoodi}ChainConfig` structs + `*GenesisHash` consts) | `core/genesis.go` (`Default{Sepolia,Holesky,Hoodi}GenesisBlock()`), allocs embedded via `core/genesis_alloc.go` | `params/config.go` `NetworkNames` map (`config.go:409–412`) |
| **core-geth** | `params/config.go` (retains ETH `HoleskyGenesisHash`/`SepoliaGenesisHash`) + ETC in `params/config_classic.go`, `config_mordor.go` (multi-schema) | `params/genesis.go` (`DefaultSepoliaGenesisBlock`:44, `DefaultHoleskyGenesisBlock`:57), `params/alloc.go` | `params/config.go` |
| **besu** | `config/src/main/resources/*.json` — one file per network (`mainnet.json`, `sepolia.json`, `hoodi.json`, `ephemery.json`, `dev.json`) | inline in the same JSON (`config` + genesis fields together) | `NetworkName` enum → resource file |
| **nethermind** | `src/Nethermind/Chains/*.json` chainspecs (`foundation.json` = mainnet, `sepolia.json`, `hoodi.json`) | inline in chainspec JSON; specs also in `Nethermind.Specs` | chainspec `name`/`chainId` |
| **erigon** | `execution/chain/spec/config.go` (`RegisterChainSpec(...)`, `Spec` structs) + embedded `execution/chain/spec/chainspecs/*.json` | `execution/chain/spec/allocs/*.json` (`sepolia.json`, `hoodi.json`, `mainnet.json`) | `RegisterChainSpec` registry + `networkname` |
| **reth** | `crates/chainspec/src/spec.rs` (`pub static {MAINNET,SEPOLIA,HOLESKY,HOODI}: LazyLock<Arc<ChainSpec>>`) | `crates/chainspec/res/genesis/*.json` (`holesky.json`, `hoodi.json`, `mainnet.json`, `sepolia.json`), bootnodes in `crates/net/peers/src/bootnodes.rs` | `spec.rs` `NamedChain::try_from(chain_id)` match (`spec.rs:475–480`) |

Two structural families:
- **Go clients (geth/core-geth/erigon)** define networks as **typed structs in Go**
  with allocs embedded from JSON (`//go:embed`). erigon uses an explicit
  `RegisterChainSpec` registry; geth uses package-level vars + a `NetworkNames` map.
- **JVM/Rust clients (besu/nethermind/reth)** define networks as **JSON
  chainspec/genesis files** loaded by a name→resource lookup (besu `NetworkName`
  enum, nethermind chainspec loader, reth `LazyLock` statics keyed off
  `alloy_chains::NamedChain`).

fukuii (Mantis lineage) follows the **JSON-config** model — HOCON `blockchain`
sections + a genesis JSON per network — closest in spirit to besu/nethermind, and
already the model the `fukuii-custom-networks` skill drives.

---

## Cross-client network-inventory matrix (network × client × current/deprecated × chain-id × fukuii verdict)

Legend: ✔ built-in at surveyed ref · ✘ removed (see archaeology) · — never carried.
Verdict: **DEFAULT** (ship it) · **OPTIONAL** (role-specific) · **OBSOLETE** (don't add).

| Network | Chain ID | geth | core-geth | besu | nethermind | erigon | reth | fukuii verdict |
|---------|----------|:----:|:---------:|:----:|:----------:|:------:|:----:|----------------|
| ETH mainnet | 1 | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | OPTIONAL (PoS mainnet; growth) |
| **Sepolia** | 11155111 | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | **DEFAULT** (fukuii's current PoS testnet) |
| **Holesky** | 17000 | ✔ | ✔ | ✘ 2026-04 | ✘ 2025-10 | ✘ 2025-10 | ✔ | OPTIONAL (winding down → prefer Hoodi) |
| **Hoodi** | 560048 | ✔ | ✘ | ✔ | ✔ | ✔ | ✔ | **DEFAULT** (Holesky's successor) |
| **Ephemery** | 39438135 | ✘ | ✘ | ✔ | ✘ | ✘ | ✘ | OPTIONAL (ephemeral; besu-only) |
| dev (local) | 1337 / DEV | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | OPTIONAL (dev convenience) |
| Goerli | 5 | ✘ 2024-08 | ✘ | ✘ 2024-05 | ✘ 2024-04 | ✘ | ✘ 2024-07 | **OBSOLETE** |
| Ropsten | 3 | ✘ 2023-02 | — | ✘ 2023-01 | ✘ | ✘ | — | **OBSOLETE** |
| Rinkeby | 4 | ✘ 2023-06 | — | ✘ 2023-06 | ✘ | ✘ | — | **OBSOLETE** |
| Kiln | 1337802 | ✘ 2023-01 | — | ✘ 2023-01 | — | — | — | **OBSOLETE** |
| Kovan | 42 | — | — | — | — | — | — | **OBSOLETE** (OpenEthereum AuRa) |
| Morden | 2 | pre-history | — | — | — | — | — | **OBSOLETE** |

Reading the matrix:
- **Sepolia + Hoodi are the universal current set** — every client that carries ETH
  PoS testnets carries these two (core-geth being the ETC-focused exception that
  lacks Hoodi).
- **Holesky is fracturing**: the three "modern-lean" clients (nethermind/erigon/besu)
  dropped it in the 2025-Q4→2026-Q2 window; geth/core-geth/reth retain it. It is the
  one network where clients actively disagree right now.
- **The deprecated set is unanimous**: no surveyed client carries Goerli/Ropsten/
  Rinkeby/Kiln/Kovan/Morden at its upstream ref. There is no client to imitate that
  still ships them — the ecosystem verdict is settled.

---

## fukuii implications (current Sepolia; Holesky/Hoodi/Ephemery growth; curate out deprecated)

fukuii's PoS family currently ships **ETH/Sepolia** (chain IDs 1 / 11155111,
`AGENTS.md`). The omni-client thesis is a *curated, current* testnet set — not the
kitchen-sink of every historical network — so the reference-client evidence maps to
a clear curation policy:

1. **Keep Sepolia as the DEFAULT PoS testnet.** Universal across all six clients,
   permissioned validator set, stable, and already fukuii's PoS testnet. It is a
   *merged* testnet, so fukuii's Sepolia config must carry the historical TTD
   (`17000000000000000`) and `MergeNetsplitBlock` (`1735371`) alongside the timestamp
   forks and the **custom** deposit contract `0x7f02…295d` (not the mainnet one).

2. **Add Hoodi as the second DEFAULT.** It is the ecosystem's designated
   large-validator testnet (Holesky's successor), carried by five of six clients, and
   the natural growth target after Sepolia. PoS-from-genesis (TTD `0`), mainnet
   deposit contract, Shanghai/Cancun at genesis with Prague/Osaka by timestamp — a
   simpler config than Sepolia (no merge history).

3. **Treat Holesky as OPTIONAL, deprioritized.** It still runs, but three modern
   clients have *already removed it* (nethermind/erigon/besu, 2025-Q4→2026-Q2)
   because it is being retired for validator use. If fukuii adds it, treat it as a
   short-lived compatibility target, not a long-term DEFAULT — and be ready to
   sunset it the way the reference clients are.

4. **Ephemery is OPTIONAL / niche.** Only besu bakes it in; its resetting chain ID
   makes a static built-in config awkward. Better supported (if at all) through the
   `fukuii-custom-networks` external-genesis path than as a hardcoded network.

5. **Curate OUT the entire deprecated set.** Do **not** add Goerli, Ropsten, Rinkeby,
   Kiln, Kovan, or Morden. No surveyed client still ships them; adding them would be
   carrying dead weight the whole ecosystem has shed (2023–2024). This is the
   omni-client thesis working as intended — a small current set, actively pruned,
   rather than an ever-growing legacy pile.

**Net recommended fukuii PoS network set:** `Sepolia` (DEFAULT, current) + `Hoodi`
(DEFAULT, add) as the shipped testnets, `Holesky` as an OPTIONAL sunset-track target,
`ETH mainnet` as the OPTIONAL PoS-mainnet growth target, and `dev` for local use —
mirroring exactly where geth/besu/nethermind/erigon/reth have converged, minus the
Holesky ambivalence and minus every deprecated network. Structurally, fukuii's
JSON-genesis + HOCON model is closest to besu/nethermind, so their `sepolia.json` /
`hoodi.json` chainspecs are the most direct templates for fukuii's own configs.

---

_Cross-references: `docs/research/clients/*/multi-network.md` (family/network
selection mechanism), `*/consensus-engines.md` (fork-dispatch mechanics),
`topics/consensus-poa-and-etc-testnets.md` (Kotti/Kovan/PoA + ETC testnets),
`topics/consensus-pow-cpu-dev-and-deprecated.md` (PoW-era & dev networks)._
