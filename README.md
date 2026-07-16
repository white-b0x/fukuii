<div align="center">
  <img src="https://raw.githubusercontent.com/chippr-robotics/fukuii/HEAD/docs/images/fukuii-hex-logo.png" alt="Fukuii Logo" width="400"/>
</div>

# 🧠🪱 Fukuii

### The multi-network EVM execution client. PoW-native, JVM-native, built for regulated finance.

Fukuii is an independent, ground-up execution client for EVM-compatible networks, built by Chippr Robotics LLC and White B0x Inc. It is the primary and only actively-developed native client for **Ethereum Classic** — the only Proof-of-Work EVM network — with full Proof-of-Stake support for Ethereum and its testnets, and a true multi-network framework spanning L1s, L2 rollups, sidechains, and ZK-EVM / alt-consensus families. One binary runs them all — concurrently, hard-isolated — on the same JVM that trading, clearing, and custody systems already trust.

> **🧪 BETA — Fukuii is a complete, ground-up rebuild. Not yet for production use.**

**Build & quality**

[![CI](https://github.com/chippr-robotics/fukuii/actions/workflows/ci.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/ci.yml)
[![Docker Build](https://github.com/chippr-robotics/fukuii/actions/workflows/docker.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/docker.yml)
[![Nightly Build](https://github.com/chippr-robotics/fukuii/actions/workflows/nightly.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/nightly.yml)
[![Dependency Check](https://github.com/chippr-robotics/fukuii/actions/workflows/dependency-check.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/dependency-check.yml)
[![codecov](https://codecov.io/gh/chippr-robotics/fukuii/graph/badge.svg)](https://codecov.io/gh/chippr-robotics/fukuii)

**Release & docs**

[![Release](https://github.com/chippr-robotics/fukuii/actions/workflows/release.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/release.yml)
[![Fast Distro](https://github.com/chippr-robotics/fukuii/actions/workflows/fast-distro.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/fast-distro.yml)
[![Docs Preview](https://github.com/chippr-robotics/fukuii/actions/workflows/docs-preview.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/docs-preview.yml)
[![GitHub Pages](https://github.com/chippr-robotics/fukuii/actions/workflows/gh-pages.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/gh-pages.yml)

**Project metadata**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Scala](https://img.shields.io/badge/Scala-3.3.8%20LTS-DC322F?logo=scala&logoColor=white)](https://www.scala-lang.org/)
[![JDK](https://img.shields.io/badge/JDK-25%20LTS-orange?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Latest Release](https://img.shields.io/github/v/release/chippr-robotics/fukuii?include_prereleases&sort=semver)](https://github.com/chippr-robotics/fukuii/releases)
[![Docker Pulls](https://img.shields.io/docker/pulls/chipprbots/fukuii.svg)](https://hub.docker.com/r/chipprbots/fukuii)

---

## Why Fukuii

- **The only PoW EVM network's only native client.** Ethereum Classic is the only Proof-of-Work EVM network. With core-geth deprecated and besu having exited ETC support, Fukuii is its successor and its only actively-developed native client — the de facto reference implementation for the entire ETC mining economy.
- **Concurrent multi-instance, single binary.** Fukuii is the only EVM client built to run multiple isolated networks concurrently inside one production process — hard-isolated database, config, and metrics per instance. Run ETC mainnet, Ethereum mainnet, and a private consortium chain from one audited binary, one configuration model, one monitoring stack.
- **JVM-native, built for regulated finance.** Pure Scala 3 / JDK 25 — no FFI bridge, no language-boundary overhead. Fukuii runs on the same runtime as the trading, clearing, and custody platforms institutions already operate, directly filling the JVM / ETC position besu vacated. Signed images, build-provenance attestation, and a CycloneDX SBOM ship with every release.
- **A genuine multi-network framework** — not a two-chain binary. PoW and PoS today; PoA (Clique / QBFT / Bor-style), heavy sidechains (Polygon / Bor-style), L2 rollups (OP-stack / Taiko-style), and ZK-EVM / alt-consensus families are all first-class members of the same fukuii-config framework.
- **Runs its own Consensus Layer.** Following erigon's Caplin precedent, Fukuii can embed a Consensus Layer in the same binary — external CL pairing (e.g. Lighthouse) becomes an option, not a requirement, for PoS networks.
- **Client diversity that's actually diverse.** A Scala / JVM implementation with a distinct dependency tree and failure profile — a genuinely independent implementation of the open Ethereum / ETC specifications, not a derivative of any Go, Rust, or C# client.

## What Fukuii Is

Fukuii is a complete, independent implementation of the Ethereum and Ethereum Classic specifications — on par with go-ethereum, besu, erigon, nethermind, and reth, and the only one that is PoW-native, JVM-native, and built for concurrent multi-network operation in a single binary. Those clients were studied as engineering reference material during design — the way any implementation of a public standard studies prior art — but Fukuii depends on, extends, and answers to none of them. Its only upstream is the specifications themselves: the **EIPs** and **ECIPs** it implements directly and completely.

## Who Uses Fukuii

From a home mining rig to multi-region regulated infrastructure — same binary, same API, same configuration model.

- **PoW mining economy** — solo miners, mining pools, and Ethash-class hardware vendors validating against the only live PoW EVM network
- **PoS validators & stakers** — self-hosted execution layer with Engine API, hardened key custody, and external-signer support
- **RPC & infrastructure providers** — one fleet serving many networks; RPC-aggregation gateways adopting Fukuii as a diverse, independent upstream
- **Exchanges, custodians & regulated finance** — auditable, JVM-native settlement infrastructure that fits existing trading, clearing, and custody stacks
- **Block explorers & data services** — complete archival history with fast trace / debug and era1 cold storage
- **Consortium & devnet operators** — custom-genesis private networks with PoA consensus, no source changes
- **Bootnode & discovery operators** — erect network infrastructure, not just join it
- **AI / agentic builders** — a node exposed as a safely-scoped, schema'd tool surface via native MCP
- **Network end users** — everyone transacting on ETC, ETH, or a private network, served by an operator who chose Fukuii

---

## Origin

> *Chordodes Fukuii is a parasitic worm that hijacks a mantis, rewires its brain, and drives it toward water. Fukuii hijacks Mantis (IOHK's abandoned Scala ETC client), rewires the codebase, and drives it toward consensus.*

*Chordodes Fukuii* is a nematomorph parasite. It infects a mantis, takes over its nervous system, and compels the host toward water — where the worm completes its lifecycle. The mantis becomes a vehicle for something else entirely.

Input Output (HK) built Mantis as a Scala client for the Ethereum Classic network, then abandoned it. From that abandoned host, Fukuii emerged — rebuilt from the ground up by Chippr Robotics LLC and White B0x Inc., wearing the name, carrying forward ETCDEV's Orbita vision of a multi-network client, and driven toward consensus.

---

## Networks

One binary, every EVM network:

- **Ethereum Classic** — full PoW node: Ethash / ETChash, ECIP-1099 DAG-epoch, ECIP-1100 MESS finality, ECIP-1017 emission, SNAP sync
- **Mordor** — ETC testnet
- **Ethereum** — full PoS execution layer via Engine API
- **Sepolia, Hoodi** — Ethereum testnets
- **Private & consortium chains** — custom genesis, chain ID, and fork schedule, no source modification
- **L2 rollups & sidechains** — OP-stack / Taiko-style rollups and Polygon / Bor-style sidechains, configured as network families
- **ZK-EVM & alt-consensus families** — hosted alongside PoW and PoS in the same framework

The curated public set (ETC, Mordor, Sepolia, Hoodi) ships as named profiles; anything else is one custom-genesis definition away.

## The Multi-Network Framework

Fukuii separates execution from consensus so one binary serves any network family:

1. **fukuii-core** — consensus-agnostic EVM execution, state storage, and JSON-RPC
2. **fukuii-env** — per-network parameters: chain ID, genesis, fork schedule, gas mechanics
3. **fukuii-config** — the pluggable consensus mechanism: PoW, PoS via Engine API, PoA, OP-style derivation, sidechain bridge / oracle logic, ZK verification

Reward and finalization are a **per-family hook** — ECIP-1017 emission versus PoS withdrawals — with no special-cased "if PoW" logic anywhere in shared code. Adding a network family, a fork, or an EIP is a definition, not a refactor. The design realizes ETCDEV's 2018 **Orbita** proposal — a generalized sidechain framework for ETC — across the full range of modern consensus backends. Full vision: [Pluggable Consensus & Multi-Network Architecture](docs/architecture/pluggable-consensus-vision.md).

## Concurrent Multi-Instance

Fukuii's flagship differentiator: **run many networks, hard-isolated, in one JVM process.**

- No shared database, config, or metrics state across instances — isolation extends all the way to a per-instance metrics registry, because no other client runs multi-instance to begin with.
- Run ETC mainnet and Ethereum mainnet concurrently from the same binary, configuration model, and monitoring stack.
- Deposit monitoring for multiple networks, served by a single operational team. Private consortium chains alongside public networks — one audited binary, not a fleet of different clients.
- The same binary attaches embedded-or-remote to the product family (mining-pool software, validator software, a GUI, a dRPC gateway, an MCP server) — monolith or distributed, your call.

---

## Capabilities

### Consensus

- **PoW** — Ethash / ETChash with ECIP-1010 / 1041 difficulty bomb, ECIP-1099 DAG-epoch, ECIP-1017 emission. Mining-pool-grade `getWork` / `submitWork` with verify-before-ack and ECIP-1099 seedHash. Internal CPU sealing for private PoW testnets.
- **PoS** — full Engine API driver (`newPayload` / `forkchoiceUpdated` / `getPayload`), timestamp-fork dispatch, withdrawals (EIP-4895), blob transactions (EIP-4844 / 7691).
- **PoA** — Clique / QBFT / Bor-style authority consensus for consortium and private networks.
- **L2 / sidechain / ZK** — OP-stack / Taiko-style rollup derivation, Polygon / Bor-style sidechains, and ZK-EVM / alt-BFT families on the one framework.
- **MESS (ECIP-1100)** — Modified Exponential Subjective Scoring: exponentially increasing proof-of-work required to override older blocks, the finality-like defense against the deep reorgs that have historically targeted ETC, plus a deep-reorg depth cap and MESS / ECBP-1100 branch resolution.

### Cryptography & EVM

- Keccak, secp256k1 ECDSA (RFC-6979 + EIP-2 low-S), sha256 / ripemd160 — byte-exact to spec.
- alt-bn128 pairing tower (EIP-196 / 197) with subgroup validation, KZG (EIP-4844 blobs, EIP-7594 PeerDAS), BLS12-381 (EIP-2537, full operation set), secp256r1 / P256VERIFY (EIP-7951), Blake2b (EIP-152) — one implementation serving both ETH-Osaka and ETC-Olympia.
- Constant-time equality primitive threaded through keystore, network, and RPC-auth.
- All EIP-2718 transaction types — legacy, access-list, dynamic-fee, blob, set-code — with strict envelope validation and no silent coercion. Sender recovery (EIP-155, homestead low-S, range-gated) + EIP-7702 authority recovery.
- **Unified fork dispatch** — block, timestamp, or total-difficulty activation behind one mechanism.

### Sync

- **SNAP sync** — the default fast-bootstrap path — plus chain backfill, regular sync, fast-sync fallback, and checkpoint / trusted-pivot sync.
- **PoW-from-genesis** and **reverse-from-CL-head** are the per-network defaults, with an ETC-specific **adaptive-hold pivot strategy** engineered for scarce-peer conditions that peer-rich-only designs don't address.
- **SNAP-serving** makes a node a stable state-serving workhorse for other peers — on for archival, tip-server, mining-pool, and bootnode roles.
- **ERA1 frozen-segment bulk import + BitTorrent bootstrap distribution** turns mining pools and the project itself into snapshot seeders — an anti-scarcity capability for a network with fewer peers.

### Storage

- **`StorageProfile`** selects keying × pruning × flat-accelerator × freezer × history-expiry by operator role and network — one of the few clients supporting more than one storage approach behind a single interface.
- Hash-keyed (archival) and path-keyed (pruned / tip) node storage, with **online migration** between them; flat-storage accelerator for O(1) account / slot reads; freezer / cold storage / era1 byte-canonical shard files; history expiry (EIP-4444); online full-pruning (copy-live-and-swap, no re-sync downtime).
- RocksDB with 28 self-describing column families, per-instance isolation, and an external prune-barrier hook so a downstream consumer can hold back pruning until it has consumed the data.
- Named operator-role presets: **archival, tip-server, mining-pool, RPC-relay, resource-light, bootnode, validator**.

### Network & Wire Protocol

- devp2p / RLPx handshake with Snappy compression; ETH wire protocol 68 / 69 / 70 / 71 (72 reserved), negotiated per peer; ForkId (EIP-2124 + EIP-6122); discovery via discv4 / v5, ENR, and DNS.
- Chain weight is sourced locally from validated PoW headers rather than trusted off the wire — a PoW network is never frozen at an older wire version.
- Bootnode serving + ENR / DNS-tree authoring and publishing — Fukuii erects network infrastructure, not just joins it.
- Peer-DoS / blacklist policy, per-IP inbound throttle and dial-ratio limits, decompression-bomb and frame-size caps; P2P exposed as its own gRPC (Sentry-style) service for distributed deployments.

### RPC & API Surface

- **JSON-RPC** across `eth_`, `net_`, `web3_`, `debug_`, and `trace_` (plus `txpool_` and `admin_` for operators) — namespace-gated, with one codec shared across JSON-RPC / WebSocket / IPC.
- **GraphQL** (EIP-1767); **Engine API** (JWT-gated, separate port, fork-gated method exposure) on PoS networks.
- **WebSocket / IPC / HTTP** transports with backpressure and rate-limit parity; `eth_subscribe` for real-time headers, logs, and pending transactions; SSL / TLS (PEM, PKCS12, JKS) for encrypted endpoints.
- **dRPC-Provider bridge** — a first-class gRPC integration surface making Fukuii an upstream to RPC-aggregation gateways, whose fan-out re-serves many downstream dApps and wallets.
- One **`Principal` / `Capability` auth model** spanning MCP write-ops, Engine-API JWT, RPC permissions, and external-signer custody — a single auth model across every transport.

### Security

- Constant-time crypto across every converging auth surface; keystore hardening (atomic write + verify, key-zeroing, read / write custody split); external-signer / HSM / clef-compatible custody; TLS on the RPC endpoint.
- CI SAST, automated CVE scanning, a supply-chain checksum gate, and a pre-merge container scan.
- **Cosign** keyless image signing (GitHub OIDC — no long-lived keys), **build-provenance attestation**, and a **CycloneDX SBOM** attached to every release. For audited operators, this supports evidence collection for software-integrity and change-management controls.

### Observability

- Per-instance Prometheus / Micrometer metrics registry; **15 shipped Grafana dashboards** (one of only two EVM clients, alongside erigon, that ship dashboards); structured per-instance logging.
- Consensus-aware health checks (PoW liveness vs. PoS CL-alive), Kubernetes-ready liveness / readiness probes; OTLP distributed tracing and push telemetry; auto-generated RPC / CLI reference kept in lockstep with the code.

---

## Ethereum Compliance

The Ethereum Foundation [Hive](https://github.com/ethereum/hive) simulator suite is the industry standard for EL client compliance testing. Each simulator runs independently — a failing suite is immediately visible in the badge wall below, not buried in a monolithic pass / fail. Nightly reference tests from the Ethereum Foundation run across the full ETC and Ethereum fork schedules.

[![Hive · smoke-genesis](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-smoke-genesis.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-smoke-genesis.yml)
[![Hive · smoke-network](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-smoke-network.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-smoke-network.yml)
[![Hive · rpc](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-rpc.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-rpc.yml)
[![Hive · rpc-compat](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-rpc-compat.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-rpc-compat.yml)
[![Hive · graphql](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-graphql.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-graphql.yml)
[![Hive · devp2p](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-devp2p.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-devp2p.yml)
[![Hive · sync](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-sync.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-sync.yml)
[![Hive · consensus](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consensus.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consensus.yml)
[![Hive · engine](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-engine.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-engine.yml)
[![Hive · consume-engine](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consume-engine.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consume-engine.yml)
[![Hive · consume-rlp](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consume-rlp.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consume-rlp.yml)
[![Hive Prague Suite](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-prague.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-prague.yml)
[![Ethereum Tests](https://github.com/chippr-robotics/fukuii/actions/workflows/ethereum-tests-nightly.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/ethereum-tests-nightly.yml)

| Suite | What it verifies |
|---|---|
| `smoke-genesis` | Genesis block consistency |
| `smoke-network` | Basic network connectivity |
| `rpc` / `rpc-compat` | JSON-RPC correctness and cross-client compatibility |
| `graphql` | GraphQL API |
| `devp2p` | Wire protocol handshake and framing |
| `sync` | Chain sync correctness |
| `consensus` | Ethereum consensus test vectors |
| `engine` / `consume-engine` | Engine API request / response and payload consumption |
| `consume-rlp` | RLP encoding correctness |
| `prague` | Prague / Electra hard-fork suite |

---

## Mining — Ethereum Classic PoW, First-Class

ETC's identity is Proof-of-Work with accessible home mining. Fukuii is built with that audience as a first-class deployment target — and, as the only actively-developed native client for the only PoW EVM network, it is effectively the default choice for the entire ETC mining economy.

- **Mining-pool-grade protocol** — `getWork` / `submitWork` with verify-before-ack and ECIP-1099 seedHash; the `mining-pool` role preset (SNAP-serving on, tip-server sync); `txpool` introspection with Besu-compatible extensions.
- **Consumer GPU and ASIC support** — Etchash on standard GPU rigs; Antminer, iPollo, Jasminer, and Bombax hardware.
- **Pool integration** — standard stratum protocol; compatible with major ETC pools. In-node Stratum is deliberately kept in dedicated pool software so the node stays lean.
- **BitTorrent state distribution** — pools and the project seed era1 snapshots, an anti-scarcity capability for a network with fewer peers.
- **Bootstrap checkpoints** — begin syncing immediately without waiting for peer consensus.

### fukuii-gui

**[fukuii-gui](https://github.com/chippr-robotics/fukuii-gui)** is a native desktop application for self-custody asset management and node operation — built on the same Qt / QML model as Monero GUI, cross-platform across Windows, Linux, and macOS.

- **Hardware wallet support** — Ledger (Nano S, Nano S Plus, Nano X, Nano Gen 5, Stax, Flex) and Trezor (Model T, Safe 3, Safe 5)
- **Self-custody wallet** — mnemonic seed backup, view-only wallets, offline transaction signing
- **Node & mining management** — sync monitoring, peer management, local or remote node config, solo mining and P2Pool integration

Not just for miners — fukuii-gui is for anyone who wants to hold and transact ETC or ETH against their own node rather than trusting a third-party RPC endpoint.

---

## Enterprise & Regulated Finance

Fukuii runs on the JVM — the same runtime as the trading, clearing, and custody systems institutions already operate — with **no FFI bridge and no language-boundary overhead**. It directly fills the JVM / ETC position besu vacated, and its supply-chain posture is built for audited procurement.

**Supply chain & compliance**
- Cosign keyless signing, build-provenance attestation, and a CycloneDX SBOM on every release
- Weekly automated dependency monitoring, PR-gated dependency review, and a scheduled NVD CVE scan against the resolved dependency set
- Semgrep static analysis (Scala-aware rulesets) on every push and PR, plus Trivy container-image scanning
- Structured logging integrates with SIEM systems; SBOM, provenance, and signed artifacts support software-integrity and change-management evidence collection

**High availability & operations**
- The **Barad-dûr** reference deployment: Kong API gateway, Prometheus, Grafana, and a dual-node ETC + Mordor topology — see [`ops/barad-dur/`](ops/barad-dur/)
- Kubernetes-ready liveness / readiness probes; RocksDB state persists across restarts on named volumes; dual-node redundancy enables rolling maintenance
- JWT-authenticated Engine API; configurable RPC namespace exposure; Kong TLS termination and IP allowlisting; non-root container images with minimal attack surface
- External-signer / HSM / clef-compatible custody for institutional key management

| Environment | Purpose | Network |
|---|---|---|
| **Barad-dûr** 🏰 | Production — Kong, Prometheus, Grafana, dual-node | ETC Mainnet + Mordor |
| **Cirith Ungol** 🕷️ | Staging — dual-node, rapid iteration | Mordor / ETC |
| **Gorgoroth** 🌋 | Private — multi-node cluster, integration tests | Private |

| Deployment | RAM | Storage | Use case |
|---|---|---|---|
| Home node | 8 GB | 500 GB | ETC mainnet, single network |
| Professional miner | 16 GB | 1 TB | ETC mainnet + pool infrastructure |
| Enterprise node | 32–64 GB | 2–4 TB | Multi-network, high RPC load |
| Archive node | 64 GB+ | 4 TB+ | Full history, indexing workloads |

RocksDB column families, WAL tuning, and memory budgets are configurable per deployment. See [Disk Management](docs/runbooks/disk-management.md) and [Docker Documentation](docs/deployment/docker.md) for Compose examples, image variants, and Kubernetes patterns.

---

## Agentic Control via MCP

Fukuii ships a **native Model Context Protocol server** — one of a very small number of EVM clients with a node-level agentic-AI interface, built into the JSON-RPC layer rather than bolted on. Operations teams query node state through an AI assistant directly; compliance teams pull reorg history on demand; infrastructure teams automate through natural language.

- **Schema'd tools bound to live node state** — operational (status, sync, peers, fork history, chain config), investigative (block / transaction / account queries, reorg detection, emission schedule), and compliance (fork history, unit conversion, config audit)
- **Transports** — stdio + SSE, plus A2A / ACP agentic transports; works with any MCP-compatible assistant. Enable by adding `"mcp"` to `fukuii.network.rpc.apis`
- **Write-ops** (mining / peer / config control) are **OAuth 2.1-gated and read-only by default** — enabled only when an operator opts into write-capable agentic access, governed by the same `Principal` / `Capability` auth model as every other transport
- **Live resources** and URI-templated access (`fukuii://block/123`, `fukuii://tx/0x…`, `fukuii://account/0x…`), plus guided prompts for health checks and troubleshooting

See [MCP Documentation](docs/MCP.md).

## Product Family

Fukuii's node exposes clean integration seams so a broader product family stands on top of it without forking the core — attaching **embedded-or-remote**, so the same binary runs as a monolith or distributed:

- **dRPC Provider** — Fukuii as an upstream to RPC-aggregation gateways
- **Mining-pool software** — attaches via the embedded-or-remote seam to Fukuii's mining-pool-grade RPC surface
- **Validator software / remote signing** — for PoS operations
- **fukuii-gui** — the desktop wallet and node manager
- **Embedded Consensus Layer** — Caplin-style single-binary CL + EL

**A deliberate design boundary, not a gap:** the node itself stays lean. It does not speak pool or validator protocols directly — dedicated pool software, validator software, and the GUI are separate products that attach to the node's seams.

---

## Getting Started

> **🧪 BETA SOFTWARE — DO NOT USE IN PRODUCTION**

### Option 1: Docker (Recommended)

```bash
# GHCR is the primary registry — all images are Cosign-signed with a build-provenance attestation
docker pull ghcr.io/chippr-robotics/fukuii:<version>

# Verify the image signature
cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/fukuii:<version>

# Run
docker run -d --name fukuii \
  -p 8545:8545 -p 8546:8546 -p 30303:30303 \
  -v fukuii-data:/app/data -v fukuii-conf:/app/conf \
  ghcr.io/chippr-robotics/fukuii:<version>
```

Docker Hub (`chipprbots/fukuii:latest`) is available as a mirror for environments where GHCR is restricted. See [Docker Documentation](docs/deployment/docker.md) for Compose examples, image variants, and security configuration.

### Option 2: Pre-built Binary

1. Download `fukuii-<version>.zip` from the [Releases page](https://github.com/chippr-robotics/fukuii/releases)
2. Extract: `unzip fukuii-<version>.zip && cd fukuii-<version>`
3. Run: `./bin/fukuii etc` (or `mordor`)

Builds for Windows, Linux, and macOS are in every release. Also available: `fukuii-assembly-<version>.jar` for `java -jar` execution (requires JDK 25+).

### Option 3: Build from Source

```bash
git submodule update --init --recursive
sbt dist
```

The distribution archive is placed under `target/universal/`. See the [Contributing Guide](CONTRIBUTING.md) for development-environment setup.

---

## Sync Architecture

```
🪱[====..............]🧠
  ↑                    ↑
  The Worm             The Brain
  (sync progress)      (assembled state)
```

See [Sync](#sync) above for the full sync capability set. Prometheus metrics and the progress display track sync in real time.

## Architecture & Stack

Built on **Scala 3.3.8 LTS** and **JDK 25 LTS**, with execution logic on an **Apache Pekko Typed** actor system — reactive actors with bounded dispatchers, generation tokens, and phase gates — and a **Cats Effect / fs2** storage contract, a deliberate engineering choice for correctness under concurrency. The codebase is a strict, down-only module dependency graph (`bytes` · `crypto` · `rlp` → `domain` → `storage` · `trie` → `evm` → `execution` → `consensus` → `network` → `sync` → `rpc` → `node`); an upward dependency is a compile error. Namespace `com.chipprbots.fukuii.*`, single binary.

Architecture and design records live under [`docs/architecture/`](docs/architecture/).

## Operations and Maintenance

- [First Start](docs/runbooks/first-start.md)
- [Metrics & Monitoring](docs/operations/metrics-and-monitoring.md)
- [Security](docs/runbooks/security.md)
- [Peering](docs/runbooks/peering.md)
- [Disk Management](docs/runbooks/disk-management.md)
- [Backup & Restore](docs/runbooks/backup-restore.md)
- [Log Triage](docs/runbooks/log-triage.md)
- [Known Issues](docs/runbooks/known-issues.md)

See [Operations Runbooks](docs/runbooks/README.md) for complete operational documentation.

---

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for environment setup, code standards, pre-commit hooks, and PR guidelines. Run `sbt pp` before submitting a PR to check formatting, style, and tests locally.

**Quick links:**
- [Documentation Site](https://chippr-robotics.github.io/fukuii/)
- [Documentation Index](docs/index.md)
- [Quick Start Guide](.github/QUICKSTART.md)

---

## Important Notes

<b>Licence:</b> Fukuii is distributed under the Apache 2.0 licence; a copy is included in the LICENSE file. Attribution and project lineage are recorded in the NOTICE file.

<b>Origin:</b> Fukuii is an independent, ground-up client. It contains no Mantis source code and is not a derivative work of Mantis. "Mantis" is a trademark of IOHK, referenced only for lineage and the name-story; see the NOTICE file.

## Contact

For questions or support, reach out to the Fukuii developers (Chippr Robotics LLC and White B0x Inc.) via our GitHub repository.
