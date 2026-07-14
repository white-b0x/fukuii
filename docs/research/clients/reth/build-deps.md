# reth — build-deps
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth is a **single Cargo workspace** (`Cargo.toml:10`) that decomposes the node
into ~108 first-party `reth-*` library crates plus binaries and examples — the
**most granular** decomposition of the six SR clients (besu/nethermind/erigon/
core-geth/geth). The workspace `members` array lists **142 entries** (crates +
bins + examples + testing utils; `Cargo.toml:10-153`), of which **108 are
published/pathed `reth-*` crates** wired through `[workspace.dependencies]`.

Three build-level mechanisms carry the whole design:

1. **Fine-grained crate decomposition** — each subsystem is a directory *group*
   of small crates (e.g. `crates/net/` is 14 crates, `crates/rpc/` is 12,
   `crates/storage/` is 11, `crates/ethereum/` is 9). Traits, types, DB models,
   and the concrete impl are usually *separate* crates (`reth-storage-api` vs
   `reth-provider` vs `reth-db-api` vs `reth-db`), so a downstream consumer can
   depend on the interface without pulling the implementation.
2. **`[workspace.dependencies]` as the single version source** — every crate,
   first-party and third-party, has exactly one version/path declared once at the
   root (`Cargo.toml:316`, 336 entries), and member crates reference them with
   `foo.workspace = true`. This is Cargo's native analog of a besu BOM / .NET
   Central Package Management / fukuii's `Dependencies.scala`.
3. **Feature-flag composition** — a small set of Cargo features (`consensus`,
   `evm`, `node`, `rpc`, `provider`, `exex`, `trie`, `pool`, `network`, `full`)
   turns whole optional crates on/off, so the same source tree builds anything
   from a thin library import up to a full node binary.

The external base is the **alloy** crate ecosystem (Paradigm's Ethereum
primitives/RPC/consensus libraries, 37 `alloy-*` entries; `Cargo.toml:439-478`)
and **revm** (the EVM, `Cargo.toml:434`). reth does *not* define its own
`U256`/`Address`/RLP — it re-exports alloy's, so reth crates and alloy-based
tooling share one primitive type layer.

_Vendoring note: this vendored copy is the **ethereum-only** workspace. In
upstream reth the Optimism/Scroll families live in parallel crate trees
(`crates/optimism/*`, `op-reth` bin) that are *not* present here; the
family-gating mechanism they use is the same feature-flag pattern documented
below, just applied to a `crates/optimism/*` tree instead of `crates/ethereum/*`._

## Key types / interfaces / files

- `Cargo.toml:9-155` — `[workspace]` `members`: the 142-member manifest; the
  canonical map of every crate group (primitives, storage, trie, evm, revm,
  consensus, net, rpc, stages, node, engine, exex, payload, transaction-pool,
  static-file, chainspec, cli).
- `Cargo.toml:1-8` — `[workspace.package]`: inherited version (`2.3.0`),
  `edition = "2024"`, `rust-version = "1.95"`, dual `MIT OR Apache-2.0` license —
  set once, inherited by every member via `version.workspace = true`.
- `Cargo.toml:158` — `resolver = "2"` (feature unification resolver).
- `Cargo.toml:161-260` — `[workspace.lints]` (`rust.*` + `clippy.*`): a
  workspace-wide lint policy (missing-docs=warn, rust_2018_idioms=deny, plus a
  large curated clippy nursery set) that every crate opts into with
  `[lints] workspace = true`.
- `Cargo.toml:316-431` — `[workspace.dependencies]` first-party block: every
  `reth-*` crate declared once as `{ path = "crates/...", default-features =
  false }`. Note several are pinned to a *published* version rather than a path
  (`reth-codecs = "0.5.0"`, `reth-primitives-traits = "0.5.0"`,
  `reth-trie-common`… ; `Cargo.toml:325,398,404`) — those are released
  independently and consumed as crates.io deps even inside the workspace.
- `Cargo.toml:433-478` — external base: `revm = "41.0.0"`, `revm-inspectors`,
  and the 37 `alloy-*` crates (primitives `1.6.0`, consensus/eips/rpc-types line
  at `2.1.0`, `alloy-evm`, `alloy-trie`, `alloy-hardforks`).
- `Cargo.toml:435` — `revmc = { git = "...revmc", branch = "main" }`: a
  git dependency pinned only by **branch, not commit SHA** (an EVM JIT compiler)
  — see Gotchas.
- `crates/ethereum/reth/Cargo.toml` — **`reth-ethereum` meta-crate** (the SDK
  front door). Every subsystem is an `optional = true` dependency
  (`Cargo.toml:16-52` of that file) toggled by a feature of the same name
  (`:107-160`): `full = [consensus, evm, node, provider, rpc, exex, trie, pool,
  network]`, `node = [provider, consensus, evm, network, node-api, dep:reth-node-
  ethereum, dep:reth-node-builder, …]`.
- `crates/ethereum/reth/src/lib.rs:14-131` — the re-export surface: `pub mod
  consensus`, `evm`, `network`, `provider`, `storage`, `node`, `engine`,
  `trie`, … each re-exporting the underlying `reth-*` crate, so downstream code
  writes `use reth_ethereum::node::builder` instead of naming a dozen crates.
- `crates/node/builder/Cargo.toml` — **`reth-node-builder`**: the composition
  crate (74 workspace deps) that assembles components (network, pool, consensus,
  evm, rpc, engine) into a runnable node via the builder/`NodeTypes` pattern —
  the primary enterprise "build a custom node from crates" entry point.
- `bin/reth/Cargo.toml:1-80` — the default binary; `default-members =
  ["bin/reth"]` (`Cargo.toml:154`). Its `[features]` block gates *build-time*
  concerns (jemalloc, asm-keccak, js-tracer, jit, gmp, otlp) rather than chain
  families, by forwarding into `reth-node-ethereum/*` and `reth-ethereum-cli/*`.
- `Cargo.toml:263-314` — `[profile.*]`: `dev` (line-tables-only debug),
  `release` (thin-LTO, strip), `maxperf` (fat-LTO, `codegen-units = 1`),
  `reproducible` (panic=abort, no incremental) — a spectrum of build profiles
  for dev-speed vs. production-perf vs. reproducible builds.

## Design decisions & rationale

- **Traits-and-types crates split from impl crates.** The recurring `*-api` /
  `*-types` / `*-common` vs concrete-crate split (`reth-storage-api` +
  `reth-db-api` + `reth-db-models` vs `reth-db`/`reth-provider`;
  `reth-network-api` vs `reth-network`; `reth-payload-primitives` vs
  `reth-payload-builder`) lets a consumer compile against an interface without
  the heavy implementation, and lets alternate impls slot in. This is what makes
  reth usable as an *SDK*, not just a binary.
- **One version, declared once.** `[workspace.dependencies]` means a security or
  compat bump to alloy/revm is a single-line edit propagated to all 108 crates —
  no per-crate drift. `default-features = false` at the root forces each consumer
  to opt into features explicitly, keeping builds minimal and `no_std`-capable
  where crates support it (note the pervasive `std` / `arbitrary` / `test-utils`
  feature families).
- **Feature flags as the family/profile switch.** Rather than build-time codegen
  or runtime config, chain families and optional capabilities are Cargo
  features. The `reth-ethereum` meta-crate's `full`/`node`/`consensus`/`evm`
  cascade (`crates/ethereum/reth/Cargo.toml:107-160`) is the template the
  Optimism/Scroll trees reuse — a family is "a crate group + a feature".
- **alloy as the shared primitive substrate.** Delegating `U256`/`Address`/RLP/
  consensus-encoding to alloy (`Cargo.toml:439-478`) means reth interoperates
  with the broader Paradigm tooling ecosystem (foundry, alloy-provider) for free
  and doesn't re-litigate primitive encodings.
- **Independently-versioned core crates.** A handful of the most stable crates
  (`reth-codecs`, `reth-primitives-traits`, `reth-trie-common`,
  `reth-zstd-compressors`, `Cargo.toml:325,398,427,431`) are published to
  crates.io and consumed by *version* even internally, so external users can
  depend on them without the whole workspace.

## Notable patterns (the reusable idea)

**The meta-crate + optional-deps + feature-cascade SDK front door.** reth ships
one façade crate (`reth-ethereum`) whose every subsystem is an `optional`
dependency behind an eponymous feature, plus `full`/`node` aggregate features
that compose them. Downstream consumers pick a granularity —
`reth-ethereum = { features = ["evm"] }` for just the EVM, or `["node"]` for a
full node — and get a clean re-export namespace (`reth_ethereum::evm::*`) instead
of naming individual crates. Combined with `[workspace.dependencies]` as the
single version source and per-crate `[lints] workspace = true`, this is the
complete Rust playbook for "a node that is also a library." For fukuii the direct
mapping is: reth's **crate granularity ↔ fukuii's sbt sub-projects**, reth's
**`[workspace.dependencies]` ↔ `project/Dependencies.scala`**, reth's
**feature flags ↔ (currently) build-time selection / would-be sbt module
gating**, and reth's **`reth-ethereum` meta-crate ↔ a hypothetical fukuii façade
module that re-exports the seams enterprise consumers assemble against.**

## Authority note

reth = the crate-granular SDK + compile-time-modularity authority among the six
SR clients: 108 first-party crates, an explicit `reth-node-builder` composition
API, a published meta-crate, and Cargo-native single-source version management.
besu (Gradle multi-module + BOM) and nethermind (.NET projects + Central Package
Management) are the JVM/.NET module-decomposition peers, but neither matches
reth's crate count or its trait/type/impl-crate separation. For **PoW/ETC
semantics** reth is *not* an authority (it is ETH/PoS-centric and here vendored
ethereum-only) — core-geth remains the sole PoW authority. Cite reth for
*structure* (how to slice a node into composable modules), not for consensus.

## Gotchas / anti-patterns / things they later changed

- **Branch-pinned git dependency.** `revmc = { git = "…/revmc", branch = "main"
  }` (`Cargo.toml:435`) pins by mutable branch, not commit SHA — exactly the
  pattern fukuii's supply-chain rules forbid (GitHub deps must pin a full SHA).
  A gated/optional EVM-JIT dep, but a supply-chain smell to flag if reused as a
  reference.
- **Vendored copy is ethereum-only.** The Optimism/Scroll crate trees and
  `op-reth` binary are absent here, so the `optimism`/`scroll` *family* features
  can't be observed directly in this checkout — the mechanism is inferred from
  the ethereum meta-crate's identical feature-cascade shape. Don't cite this tree
  as evidence of multi-family gating internals; cite it for the *pattern*.
- **Edition 2024 / Rust 1.95 floor** (`Cargo.toml:3-4`) — a very recent toolchain
  requirement; consumers on older Rust can't build the workspace. Aggressive
  currency, unlike the conservative LTS posture fukuii holds.
- **Feature-flag combinatorics.** With `default-features = false` everywhere plus
  the `std`/`arbitrary`/`test-utils` families threaded through optional deps
  (`?/std` syntax, e.g. `crates/ethereum/reth/Cargo.toml:60-82`), a
  mis-specified feature set produces confusing "trait not satisfied" errors far
  from the missing feature. Powerful but a known Cargo footgun at this scale.
- **142 members = slow cold builds.** The granularity that helps consumers costs
  compile time; the tiered `[profile.*]` set (`maxperf` fat-LTO for release,
  `dev` line-tables-only for iteration) exists specifically to manage that
  tradeoff.
