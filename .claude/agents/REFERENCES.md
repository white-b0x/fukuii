# Agent Reference Repositories

Reference repos are cloned locally at `<fukuii-root>/.claude/repo-references/` (gitignored — per-machine, not committed). This file documents which repos exist, their GitHub URLs, and which agents use them.

## Clone Convention

```bash
cd "$(git rev-parse --show-toplevel)/.claude/repo-references"

# Core Language (mithril, wraith, prism)
git clone https://github.com/scala/scala3.git                                    # Scala 3 idioms, changelogs, migration patterns
git clone https://github.com/scala/scala.git scala2                              # Scala 2 stdlib patterns (migration source reference)
git clone https://github.com/scala/docs.scala-lang.git                           # Migration cookbook, official style guidance

# Actor Framework (loom, herald, flow, conduit)
git clone https://github.com/apache/pekko.git                                    # Typed actor API, stream internals (stream/, discovery/), testkit
git clone https://github.com/apache/pekko-connectors.git                         # TCP/UDP/network connector patterns (loom: TCP actor bridges)
git clone https://github.com/apache/pekko-http.git                               # HTTP/WebSocket routing DSL (conduit: JsonRpcHttpServer routes)
git clone https://github.com/apache/pekko-management.git                         # DNS-SD, Kubernetes, Consul discovery implementations (herald: DnsDiscovery)
mkdir -p virtuslab && cd virtuslab
git clone https://github.com/VirtusLab/pekko-serialization-helper.git            # @SerializabilityTrait — read before migrating eventStream actors (loom)
git clone https://github.com/VirtusLab/scala-skill.git                           # IDE-integrated Scala dev patterns (mithril)
cd ..

# Testing (scalamock: prism, eye; scalafix/scapegoat: mithril, wraith)
git clone https://github.com/scalamock/scalamock.git                             # Idiomatic Scala 3 mock patterns (prism, eye)
git clone https://github.com/scalacenter/scalafix.git                            # Custom rule dev, GivenUsing/ExplicitImplicitTypes rules (mithril, wraith)
git clone https://github.com/sksamuel/scapegoat.git                              # Static analysis inspection catalogue (prism, wraith)

# Spec-Driven Development (speckit-* skills)
git clone https://github.com/github/spec-kit.git                                 # Upstream SDD workflow, templates, CHANGELOG (all speckit skills)

# Typelevel functional stack (mithril, and IO-safe logging in all actors)
mkdir -p typelevel && cd typelevel
git clone https://github.com/typelevel/cats.git                                  # Functor/Monad/Traverse used in sync pipeline (mithril)
git clone https://github.com/typelevel/cats-effect.git                           # IO, Resource, Fiber patterns — CE3 (mithril; P11 asyncLog context)
git clone https://github.com/typelevel/fs2.git                                   # Streaming alt to Pekko Streams — reactive-streams interop (mithril, herald)
git clone https://github.com/typelevel/log4cats.git                              # IO-safe logging abstraction over SLF4J (wraith, eye — CE logging bugs)
cd ..

# Ethereum Protocol — specs and wire protocol (forge, beacon, herald, conduit)
git clone https://github.com/ethereumclassic/ECIPs.git                           # ETC fork schedule — LOCAL COPY MAY BE AHEAD (see note below)
git clone https://github.com/ethereum/EIPs.git                                   # ETH EIP specs (Osaka: EIP-7706, 7939, etc.)
mkdir -p ethereum && cd ethereum
git clone https://github.com/ethereum/devp2p.git                                 # RLPx, discv4/v5, ETH68/69/70, SNAP wire specs (herald)
git clone https://github.com/ethereum/execution-apis.git                         # ETH JSON-RPC spec — eth_*, net_*, web3_* (conduit)
git clone https://github.com/ethereum/yellowpaper.git                            # Formal EVM/tx spec — last resort when EIP text is ambiguous (forge, beacon)
git clone https://github.com/ethereum/consensus-specs.git                        # PoS beacon block, withdrawals, execution payload (beacon)
git clone https://github.com/ethereum/tests.git                                  # Canonical state/blockchain/VM test vectors (forge, beacon, eye)
git clone https://github.com/ethereum/hive.git                               # multi-client black-box test orchestrator: devp2p, ETH execution, PoS, smoke simulators (eye, herald, forge, beacon)
cd ..

# JSON / Serialization (conduit)
git clone https://github.com/json4s/json4s.git                                   # JSON-RPC serialization in fukuii — codec bug reference (conduit)
git clone https://github.com/circe/circe.git                                     # Migration target if json4s replaced; idiomatic Scala 3 codecs (conduit)
git clone https://github.com/sangria-graphql/sangria.git                         # GraphQL schema/execution in jsonrpc/graphql/ (conduit)

# Storage (vault)
git clone https://github.com/facebook/rocksdb.git                                # Java API, WriteBatch, WAL, column families, cache tuning (vault)

# Reference EVM Clients (forge, beacon, herald) — see the "why" note below the index
# table. Clone from origin (the white-b0x fork) directly, NOT upstream — the fork's
# `main` branch carries the ETC overlay (where one has been published), and its
# `upstream` branch is a kept-in-sync mirror of the canonical repo, so cloning origin
# gets both in one fetch. Add the true canonical as an `upstream` remote for occasional
# direct-from-canonical fetches, separate from the fork's own `upstream` mirror branch.
# Finish checked out on `upstream`, NOT `main` — registry.yaml's automated refresh-refs.sh
# maintenance (`branch: upstream`) does `git diff --quiet upstream` to check for local
# modifications before fetching; if `main` were checked out instead, that diff would never
# be empty for besu/nethermind (main genuinely differs from upstream), and the automated
# refresh would always abort with a false "has local changes" error. EXCEPTION: core-geth
# stays on `main` — its `upstream` is deprecated (no changes since 2024), so `main` is what
# gets refreshed (via `origin`, not `upstream`) and what conformance reads for that one.
# For the rest, `main` is still fetched and available — check it out on demand to read the
# ETC overlay; just don't leave it as the resting state of these clones.
mkdir -p clients && cd clients

# besu: origin main = ETC overlay (written, syncs). origin upstream = hyperledger/besu mirror.
git clone https://github.com/white-b0x/besu.git besu
git -C besu checkout -b upstream origin/upstream
git -C besu remote add upstream https://github.com/hyperledger/besu.git

# core-geth: SPECIAL CASE — stays checked out on `main`, unlike every other client here.
# origin main = go1.26 Olympia modernization (written, syncs) — the actively maintained
# branch and the real ECIP reference. origin upstream = ethereumclassic/core-geth mirror,
# but that upstream is DEPRECATED (no changes since 2024) — refreshing or diffing against
# it would be pointless/misleading, so main (not upstream) is what conformance reads and
# what refresh-refs.sh keeps fresh (via origin, not the dead upstream remote) for this one.
# Fork also has many WIP topic branches (security/*, test/*, docs/*) — not fetched here.
git clone https://github.com/white-b0x/core-geth.git core-geth
git -C core-geth checkout -b upstream origin/upstream
git -C core-geth checkout main
git -C core-geth remote add upstream https://github.com/ethereumclassic/core-geth.git
git -C core-geth remote add geth https://github.com/ethereum/go-ethereum.git      # cross-check remote only — core-geth derives from multi-geth, not go-ethereum

# nethermind: origin main = ETC overlay (written, syncs). origin upstream = NethermindEth mirror.
git clone https://github.com/white-b0x/nethermind.git nethermind
git -C nethermind checkout -b upstream origin/upstream
git -C nethermind remote add upstream https://github.com/NethermindEth/nethermind.git

# go-ethereum: no ETC overlay written yet — origin main and origin upstream are currently
# identical. Still set up the same structure so it's ready when overlay work starts.
git clone https://github.com/white-b0x/go-ethereum.git go-ethereum
git -C go-ethereum checkout -b upstream origin/upstream
git -C go-ethereum remote add upstream https://github.com/ethereum/go-ethereum.git

# reth: no ETC overlay written yet — origin main and origin upstream are currently
# identical. Will follow the same main-is-the-overlay convention once published.
git clone https://github.com/white-b0x/reth.git reth
git -C reth checkout -b upstream origin/upstream
git -C reth remote add upstream https://github.com/paradigmxyz/reth.git

# erigon: same as reth — no ETC overlay written yet.
git clone https://github.com/white-b0x/erigon.git erigon
git -C erigon checkout -b upstream origin/upstream
git -C erigon remote add upstream https://github.com/erigontech/erigon.git
cd ..
```

# Reference Consensus-Layer (CL) Clients — beacon-chain clients (beacon, and any future
# single-binary-EL+CL / validator-software work). Added 2026-07-13 (operator): the EL SR
# surfaced erigon's Caplin (embedded CL) → fukuii may embed/build a CL for the PoS family,
# and the "validator software (PoS)" product-family component is CL-adjacent. Unlike the EL
# clients above, these have NO fukuii fork/overlay — clone DIRECT from canonical upstream
# (default branch), no `upstream`-branch discipline. This is a SEPARATE research track with
# its OWN taxonomy (beacon-state/fork-choice LMD-GHOST+Casper-FFG, attestation/aggregation,
# sync committees, SSZ, gossipsub/discv5, validator duties, slashing protection, checkpoint/
# light-client sync) — NOT the EL 20-slot taxonomy. Research deferred (Phase 4+ / when the
# embed-CL or validator-software decision is live); repos cloned now so they're ready.
# Cloned via `.local/scratch/clone-consensus-clients.sh`.
```bash
mkdir -p consensus-clients && cd consensus-clients
git clone https://github.com/Consensys/teku.git teku                 # JVM (Java) — THE structural mirror (besu-equivalent for CLs); the reference if fukuii embeds/builds a CL in Scala/JVM
git clone https://github.com/sigp/lighthouse.git lighthouse          # Rust — modularity/perf; pairs with reth
git clone https://github.com/prysmaticlabs/prysm.git prysm           # Go — most-used CL; gRPC-native (ties to the dRPC/gRPC-seam thesis); pairs with geth/core-geth
git clone https://github.com/status-im/nimbus-eth2.git nimbus-eth2   # Nim — resource-efficient/embedded; the light single-binary CL reference
git clone https://github.com/ChainSafe/lodestar.git lodestar         # TypeScript — spec-reference clarity
git clone https://github.com/grandinetech/grandine.git grandine      # Rust — high-performance/parallel
cd ..
# Caplin (erigon's embedded CL, Go) is already in-tree at clients/erigon/cl/ — the single-binary EL+CL reference.
```

> **ECIPs local-ahead note:** The local `repo-references/ECIPs` copy contains Olympia spec
> changes (ECIP-1111/1112/1121/1122) that have not been published upstream yet. This is
> intentional — we are the lead core developers on ETC and the authors of these ECIPs.
> The local copy is authoritative. Do **not** `git pull` to replace it with the public
> upstream without checking which branch you are on. The working spec is at `_specs/`.

## Sync All Clones

```bash
REFS=$(git rev-parse --show-toplevel)/.claude/repo-references
find "$REFS" -maxdepth 3 -name .git -exec dirname {} \; \
  | xargs -I{} git -C {} pull --ff-only 2>/dev/null
```

---

## Repository Index

### Core Language — Scala 3

| | |
|---|---|
| **GitHub** | https://github.com/scala/scala3 |
| **Clone as** | `repo-references/scala3` |
| **Used by** | `mithril`, `wraith`, `prism` |
| **Key paths** | `AGENTS.md` · `changelogs/` · `docs/docs/reference/` · `tests/` |
| **Why** | Canonical Scala 3 idioms, breaking-change log, `// error` test annotation conventions, new migration patterns as they land |

### Core Language — Scala 2 (migration source reference)

| | |
|---|---|
| **GitHub** | https://github.com/scala/scala |
| **Clone as** | `repo-references/scala2` |
| **Used by** | `wraith` |
| **Key paths** | `AGENTS.md` · `src/library/` |
| **Why** | Recognise Scala 2 stdlib patterns during migration; understand what AGENTS.md says is safe to modify |

### Core Language — Scala Documentation

| | |
|---|---|
| **GitHub** | https://github.com/scala/docs.scala-lang |
| **Clone as** | `repo-references/docs.scala-lang` |
| **Used by** | `mithril` |
| **Key paths** | `_overviews/scala3-migration/` · `_scala3-reference/` · `_overviews/scala3-book/` |
| **Why** | Migration cookbook, idiomatic Scala 3 examples, official style guidance |

---

### Actor Framework — Apache Pekko

| | |
|---|---|
| **GitHub** | https://github.com/apache/pekko |
| **Clone as** | `repo-references/pekko` |
| **Used by** | `loom`, `herald` |
| **Key paths** | `AGENTS.md` · `actor-typed/src/main/scala/` · `CHANGELOG.md` · `serialization/` |
| **Why** | Canonical Typed actor API patterns; MiMa binary-compat rules; formatting and licensing rules from `AGENTS.md`; serialization marker interfaces |

### Actor Framework — Apache Pekko Streams

> Note: Pekko Streams is inside the pekko monorepo. Key paths listed here for direct navigation.

| | |
|---|---|
| **GitHub** | https://github.com/apache/pekko (subpath: `stream/`) |
| **Clone as** | `repo-references/pekko` (already cloned — navigate to `stream/`) |
| **Used by** | `flow`, `loom`, `herald` |
| **Key paths** | `stream/src/main/scala/org/apache/pekko/stream/scaladsl/` — `Source.scala`, `Sink.scala`, `Flow.scala`, `Keep.scala` · `stream/src/main/scala/org/apache/pekko/stream/impl/fusing/` — materializer internals · `stream-testkit/src/main/scala/` — `TestSink`, `TestSource`, `TestPublisher` |
| **Why** | Canonical streaming API patterns; materialization internals (how `preMaterialize()` creates an async Reactive Streams Publisher/Subscriber boundary — root cause of CAPSTONE bug `bc2a7a2fc`); test utilities |

### Actor Framework — Apache Pekko Discovery

> Note: Pekko Discovery is inside the pekko monorepo. Key paths listed here for direct navigation.

| | |
|---|---|
| **GitHub** | https://github.com/apache/pekko (subpath: `discovery/`) |
| **Clone as** | `repo-references/pekko` (already cloned — navigate to `discovery/`) |
| **Used by** | `herald`, `flow` |
| **Key paths** | `discovery/src/main/scala/org/apache/pekko/discovery/` — `Lookup`, `ServiceDiscovery`, `SimpleServiceDiscovery` · `discovery/src/main/resources/reference.conf` |
| **Why** | DNS-SD Lookup API used by `DnsDiscovery.scala` in fukuii. Reference when modifying peer discovery or adding new discovery backends. |

### Actor Framework — Apache Pekko Management

| | |
|---|---|
| **GitHub** | https://github.com/apache/pekko-management |
| **Clone as** | `repo-references/pekko-management` |
| **Used by** | `herald` |
| **Key paths** | `discovery/` — DNS-SD, Kubernetes, Consul discovery implementations · `management/` — HTTP management API · `README.md` |
| **Why** | Concrete discovery implementations for DNS-based peer lookup. Reference when `DnsDiscovery` needs to be updated or a new discovery mechanism (K8s, Consul) is considered. |

### Actor Framework — Pekko Serialization Helper (VirtusLab)

| | |
|---|---|
| **GitHub** | https://github.com/VirtusLab/pekko-serialization-helper |
| **Clone as** | `repo-references/virtuslab/pekko-serialization-helper` |
| **Used by** | `loom` |
| **Key paths** | `README.md` · `core/src/main/scala/` |
| **Why** | `@SerializabilityTrait` and companion annotations — **required reading before migrating any actor that crosses a cluster/network boundary** |

---

### Testing — ScalaMock

| | |
|---|---|
| **GitHub** | https://github.com/scalamock/scalamock |
| **Clone as** | `repo-references/scalamock` |
| **Used by** | `prism`, `eye` |
| **Key paths** | `README.md` · `core/src/main/scala/` |
| **Why** | Idiomatic Scala 3 mock patterns; verify fukuii tests use current API |

---

### Spec-Driven Development — Spec Kit

| | |
|---|---|
| **GitHub** | https://github.com/github/spec-kit |
| **Clone as** | `repo-references/spec-kit` |
| **Used by** | all `speckit-*` skills |
| **Key paths** | `AGENTS.md` · `CHANGELOG.md` · `templates/` · `docs/` · `extensions/` |
| **Why** | Upstream SDD workflow — new spec templates, integration patterns, skill updates. Check `CHANGELOG.md` before starting a `speckit-specify` or `speckit-plan` session |

---

### VirtusLab — Scala Skill

| | |
|---|---|
| **GitHub** | https://github.com/VirtusLab/scala-skill |
| **Clone as** | `repo-references/virtuslab/scala-skill` |
| **Used by** | `mithril` |
| **Key paths** | `README.md` |
| **Why** | IDE-integrated Scala development patterns; cross-reference when proposing editor-visible refactors |

---

### Ethereum Protocol — ECIPs

| | |
|---|---|
| **GitHub** | https://github.com/ethereumclassic/ECIPs |
| **Clone as** | `repo-references/ECIPs` |
| **Used by** | `forge`, `herald`, `beacon` |
| **Key paths** | `_specs/` — all ECIP markdown specs |
| **Why** | Authoritative ETC fork schedule and specification text. **Local copy may be ahead of upstream** — we are the authors of Olympia (ECIP-1111/1112/1121/1122) and drafts not yet published publicly live here. Always prefer the local copy over the public URL. Check `_specs/ecip-1111.md`, `_specs/ecip-1112.md`, `_specs/ecip-1121.md`, `_specs/ecip-1122.md` for Olympia. |

### Ethereum Protocol — EIPs

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/EIPs |
| **Clone as** | `repo-references/EIPs` |
| **Used by** | `beacon`, `forge`, `herald`, `conduit` |
| **Key paths** | `EIPS/` — EIP markdown specs |
| **Why** | Canonical ETH EIP text for Osaka (EIP-7706, EIP-7939, etc.) and all cross-referenced EIPs in Olympia ECIPs. Use `EIPS/eip-NNNN.md` — no network fetch needed. |

---

### Ethereum P2P — devp2p Spec (Batch 1)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/devp2p |
| **Clone as** | `repo-references/ethereum/devp2p` |
| **Used by** | `herald` |
| **Key paths** | `rlpx.md` · `discv4.md` · `discv5/` · `eth/67.md` · `eth/68.md` · `eth/69.md` · `snap.md` |
| **Why** | Wire-level protocol specs for RLPx, discovery v4/v5, ETH68/69/70 message formats, SNAP. Read before any herald change touching handshake, message encoding, or fork ID. |

### Actor Framework — Pekko Connectors (Batch 1)

| | |
|---|---|
| **GitHub** | https://github.com/apache/pekko-connectors |
| **Clone as** | `repo-references/pekko-connectors` |
| **Used by** | `loom`, `conduit` |
| **Key paths** | `*.md` · source module connectors |
| **Why** | Pekko-idiomatic streaming connector patterns; reference when migrating TCP/IPC/network actors that use Pekko Streams |

### Actor Framework — Pekko HTTP (Batch 1)

| | |
|---|---|
| **GitHub** | https://github.com/apache/pekko-http |
| **Clone as** | `repo-references/pekko-http` |
| **Used by** | `conduit` |
| **Key paths** | `http-core/` · `http/` · `docs/` |
| **Why** | HTTP/WebSocket routing DSL used in JsonRpcHttpServer and JsonRpcWebsocketServer — check for idiomatic route definition and streaming patterns |

---

### JSON-RPC API Spec — execution-apis (Batch 2)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/execution-apis |
| **Clone as** | `repo-references/ethereum/execution-apis` |
| **Used by** | `conduit` |
| **Key paths** | `api-documentation/` · `openrpc.json` |
| **Why** | Authoritative ETH execution layer JSON-RPC spec (eth_*, net_*, web3_*). Cross-reference before implementing or fixing any JSON-RPC method. |

### JSON / Serialization — json4s (Batch 2)

| | |
|---|---|
| **GitHub** | https://github.com/json4s/json4s |
| **Clone as** | `repo-references/json4s` |
| **Used by** | `conduit` |
| **Key paths** | `core/src/` · `native/src/` · `README.md` |
| **Why** | fukuii uses json4s for JSON-RPC serialization; reference when fixing codec bugs or migrating to circe |

### JSON / Serialization — circe (Batch 2)

| | |
|---|---|
| **GitHub** | https://github.com/circe/circe |
| **Clone as** | `repo-references/circe` |
| **Used by** | `conduit` |
| **Key paths** | `modules/core/` · `modules/parser/` · `README.md` |
| **Why** | Target library if json4s is replaced; reference for idiomatic Scala 3 JSON codec patterns |

### GraphQL — Sangria (Batch 2)

| | |
|---|---|
| **GitHub** | https://github.com/sangria-graphql/sangria |
| **Clone as** | `repo-references/sangria` |
| **Used by** | `conduit` |
| **Key paths** | `src/main/scala/sangria/` · `README.md` |
| **Why** | GraphQL schema and execution library used in `jsonrpc/graphql/GraphQLSchema.scala`; reference for schema definition DSL and resolver patterns |

---

### Scala Tooling — Scalafix (Batch 3)

| | |
|---|---|
| **GitHub** | https://github.com/scalacenter/scalafix |
| **Clone as** | `repo-references/scalafix` |
| **Used by** | `mithril`, `wraith` |
| **Key paths** | `docs/` · `rules/src/main/scala/scalafix/` |
| **Why** | Custom rule development; understanding what built-in rules (GivenUsing, ExplicitImplicitTypes) transform; debugging `.scalafix.conf` failures |

### Scala Tooling — Scapegoat (Batch 3)

| | |
|---|---|
| **GitHub** | https://github.com/sksamuel/scapegoat |
| **Clone as** | `repo-references/scapegoat` |
| **Used by** | `prism`, `wraith` |
| **Key paths** | `src/main/scala/com/sksamuel/scapegoat/inspections/` · `README.md` |
| **Why** | Understanding which inspections are enabled/disabled in `build.sbt`; reference for false-positive patterns before suppressing a warning |

### Typelevel — log4cats

| | |
|---|---|
| **GitHub** | https://github.com/typelevel/log4cats |
| **Clone as** | `repo-references/typelevel/log4cats` |
| **Used by** | `mithril`, `wraith`, `eye` |
| **Key paths** | `core/src/main/scala/org/typelevel/log4cats/` — `Logger`, `SelfAwareLogger`, `LoggerFactory` · `slf4j/src/` — SLF4J backend integration |
| **Why** | fukuii uses `log4cats-core` + `log4cats-slf4j` as the IO-safe logging abstraction. Reference when fixing CE3 logging bugs (IO-context vs actor-thread logging), when migrating from `ctx.log` to `asyncLog`, or when reviewing logger acquisition patterns in non-actor modules. |

### Typelevel — Cats (Batch 3)

| | |
|---|---|
| **GitHub** | https://github.com/typelevel/cats |
| **Clone as** | `repo-references/typelevel/cats` |
| **Used by** | `mithril` |
| **Key paths** | `core/src/main/scala/cats/` · `docs/` |
| **Why** | Idiomatic functional abstractions (Functor, Monad, Traverse) used in sync pipeline; reference when refactoring to cats-style |

### Typelevel — Cats Effect (Batch 3)

| | |
|---|---|
| **GitHub** | https://github.com/typelevel/cats-effect |
| **Clone as** | `repo-references/typelevel/cats-effect` |
| **Used by** | `mithril` |
| **Key paths** | `core/src/main/scala/cats/effect/` · `docs/` |
| **Why** | IO monad, Resource, Fiber patterns — reference if sync/network actors are ever migrated from Pekko to cats-effect-based concurrency |

### Typelevel — fs2 (Batch 3)

| | |
|---|---|
| **GitHub** | https://github.com/typelevel/fs2 |
| **Clone as** | `repo-references/typelevel/fs2` |
| **Used by** | `mithril`, `herald` |
| **Key paths** | `core/src/main/scala/fs2/` · `docs/` |
| **Why** | Streaming alternative to Pekko Streams; reference if network IO is ever migrated from Pekko to fs2 |

---

### Ethereum Specs — Yellowpaper (Batch 4)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/yellowpaper |
| **Clone as** | `repo-references/ethereum/yellowpaper` |
| **Used by** | `forge`, `beacon` |
| **Key paths** | `Paper.pdf` · `Paper.tex` |
| **Why** | Formal EVM and transaction processing spec; last resort when EIP text is ambiguous |

### Ethereum Specs — Consensus Specs (Batch 4)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/consensus-specs |
| **Clone as** | `repo-references/ethereum/consensus-specs` |
| **Used by** | `beacon` |
| **Key paths** | `specs/phase0/` · `specs/bellatrix/` · `specs/capella/` · `specs/deneb/` |
| **Why** | PoS consensus layer spec — beacon block processing, withdrawals, execution payload format |

### Ethereum Specs — Test Vectors (Batch 4)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/tests |
| **Clone as** | `repo-references/ethereum/tests` |
| **Used by** | `forge`, `beacon`, `eye` |
| **Key paths** | `GeneralStateTests/` · `BlockchainTests/` · `VMTests/` |
| **Why** | Canonical state test vectors; cross-reference when EVM opcode or gas cost behavior is in question |

---

### Ethereum Testing — Hive (Multi-client Test Orchestrator)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/hive |
| **Clone as** | `repo-references/hive` |
| **Used by** | `eye`, `herald`, `forge`, `beacon` |
| **Key paths** | `simulators/devp2p/` — RLPx, discovery, ETH wire protocol compliance tests (herald) · `simulators/ethereum/` — block execution, state, JSON-RPC tests (forge, beacon, eye) · `simulators/eth2/` — PoS consensus tests (beacon) · `simulators/smoke/` — basic sanity checks (eye) · `hivesim/` — Go simulation framework API · `clients/` — client descriptors · `docs/` — simulator authoring guide |
| **Branch convention** | `upstream` = read-only canonical ethereum/hive master (currently checked out — use for simulator structure and hivesim API) · `main` = ETC integration WIP (incomplete, do not treat as canonical) · `fukuii` = fukuii client descriptor WIP |
| **Why** | Black-box multi-client compliance testing. Both `repo-references/hive` and `/media/dev/2tb/dev/reference-clients-evm/hive/` are clones of white-b0x/hive. Stay on `upstream` branch when reading simulator structure. Switch to `main` only to inspect in-progress ETC patches. Active test runs happen in `reference-clients-evm/hive/`. |

---

### Reference EVM Clients — Besu (three distinct references — do not conflate)

> ⚠️ **`besu`, `besu-etc`, and besu `main` are three different things.** See forge.md's
> authority statement + the `besu-three-references` memory. `besu` `upstream` = vanilla
> (ETH/shared, no ETC); `besu` `main` = **our** Olympia integration (draft, not an
> authority); **`besu-etc`** (its own entry below) = the frozen pre-Olympia ETC-base
> external authority.

| | |
|---|---|
| **GitHub** | https://github.com/hyperledger/besu (fork: white-b0x/besu) |
| **Clone as** | `repo-references/clients/besu` |
| **Used by** | `beacon` (ETH JVM authority), `forge` (shared EVM/RLP + structural mirror), `herald`, `conduit` |
| **Key paths** | `evm/src/main/java/org/hyperledger/besu/evm/` — opcodes, gas, precompiles · `consensus/` — PoW/PoS engine modules · `ethereum/core/src/main/java/org/hyperledger/besu/ethereum/mainnet/AbstractBlockProcessor.java` — the fork-agnostic block loop + `ProtocolSpec` seam (L4 structural mirror) · `ProtocolSpec.java`/`ProtocolSpecBuilder.java`/`MainnetProtocolSpecs.java` — the per-fork bundle |
| **Branch convention** | `upstream` (checked out) = mirror of `hyperledger/besu` (vanilla — **ETC removed Feb 2026**; the ETH/shared JVM authority + structural mirror) · `main` = **fukuii's OWN Olympia integration** (our `ArtificialFinality.java` MESS reactivation, ECIP-1122, EIP-7939) — a **draft-ECIP implementation reference, NOT an independent authority** (validating our Olympia against our own besu is circular) |
| **Why** | On `upstream`: the ETH JVM byte co-authority (with go-ethereum) + the JVM-implementation lens (caught F-BN-1/B-BLS-1/J-RLP-1) + the **architectural mirror** (closest kinship of the vendored clients — JVM, object-structured `ProtocolSchedule`/validator factories — for *how to structure* Scala). NOT an ETC value authority (no ETC). For pre-Olympia ETC values use `besu-etc` (below); for our Olympia work, `main` is our own draft. |

### Reference EVM Clients — besu-etc (FROZEN pre-Olympia ETC base)

| | |
|---|---|
| **GitHub** | white-b0x/besu, **frozen worktree @ `eb4248c997`** (upstream besu's last commit before it removed ETC) |
| **Clone as** | `repo-references/clients/besu-etc` |
| **Used by** | `forge` (ETC base JVM co-authority), `banksy`/`herald`/`conduit` (pre-Olympia ETC lookups) |
| **Key paths** | `ethereum/core/.../mainnet/ClassicProtocolSpecs.java` · `EpochCalculator.Ecip1099EpochCalculator` · `config/src/main/resources/{classic,mordor}.json` · `ethereum/core/.../mainnet/ClassicBlockProcessor.java` — the L4 ECIP-1017 second JVM authority (`getBlockEra`, `getBlockWinnerRewardByEra`, `calculateOmmerReward` — cross-check core-geth `rewards_classic.go`) |
| **Branch convention** | **Detached, intentionally frozen @ `eb4248c997`** — do **NOT** re-point it or "update to main"; being 534 commits behind `origin/main` is correct (main is our Olympia overlay). |
| **Why** | The external **JVM co-authority for the pre-Olympia ETC *base*** — read alongside core-geth (Go), byte-values must agree: EtcHash/ECIP-1099, ECIP-1017 emission, classic fork schedule through Spiral, chainId 61/63. **Does NOT contain MESS** (ECIP-1100 — upstream besu removed it at Spiral; grep `ArtificialFinality`, not core-geth's `ecbp1100`) **nor Olympia** (1111/1112/1121/1122 never existed upstream). For MESS → core-geth is the sole external authority; for Olympia → the ECIP specs + our own `main` overlays (self-referenced drafts). |

### Reference EVM Clients — core-geth

| | |
|---|---|
| **GitHub** | https://github.com/ethereumclassic/core-geth |
| **Clone as** | `repo-references/clients/core-geth` |
| **Used by** | `forge` |
| **Key paths** | `params/` — fork/chain config · `consensus/ethash/` — PoW · `core/vm/` — opcodes/gas · `params/mutations/rewards_classic.go` — the L4 ECIP-1017 SOLE byte-authority (era index, separate integer `4^era`/`5^era`, Era-0-vs-≥1 uncle/nephew switch); `rewards_test.go` for the vector table |
| **Branch convention** | **SPECIAL CASE** — `main` (checked out) = go1.26 Olympia modernization, written and syncing — the real ECIP reference · `upstream` = mirror of `ethereumclassic/core-geth`, but DEPRECATED (no changes since 2024) — not refreshed or diffed against, `main` is what conformance reads for this client instead |
| **Why** | Authoritative ONLY for ETC-specific ECIPs (ECIP-1017/1099/1100/1111/1112/1121/1122) — NOT the EIP reference; core-geth may silently diverge from go-ethereum on EIP behavior, always verify against go-ethereum + Besu for EIP text. The extra `geth` remote (`ethereum/go-ethereum`) is a cross-check remote only — core-geth derives from multi-geth, not a go-ethereum fork. |

### Reference EVM Clients — Nethermind

| | |
|---|---|
| **GitHub** | https://github.com/NethermindEth/nethermind |
| **Clone as** | `repo-references/clients/nethermind` |
| **Used by** | `forge`, `beacon` |
| **Key paths** | `src/Nethermind/Nethermind.Evm/` — opcodes, gas · `src/Nethermind/Nethermind.Consensus/` — consensus engines |
| **Branch convention** | `upstream` (checked out) = mirror of `NethermindEth/nethermind`, kept in sync by push · `main` = ETC overlay, written and syncing — check out on demand to read it |
| **Why** | Secondary EIP reference. Has a full ETC overlay in progress (syncing ETC mainnet, per `nethermind-olympia-branch.md`). |

### Reference EVM Clients — go-ethereum

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/go-ethereum |
| **Clone as** | `repo-references/clients/go-ethereum` |
| **Used by** | `forge`, `beacon` |
| **Key paths** | `core/vm/` — opcodes, `gas_table.go` · `core/vm/contracts.go` — precompiles · `params/protocol_params.go` — gas/fork constants · `core/state_processor.go` — the L4 ETH block/tx execution pipeline authority (`Process`, `ApplyTransaction`) · `consensus/misc/eip1559/eip1559.go` — EIP-1559 base-fee burn |
| **Branch convention** | `upstream` (checked out) and `main` are currently identical — no ETC overlay written yet |
| **Why** | Primary EIP reference — authoritative for ALL EIP behavior (EIP-1559 formula, opcodes, precompiles, gas schedules) per this project's reference-client authority model (see `QUEUE.md`'s PP-00 prompt). No ETC overlay yet (planned post-fukuii stabilization). |

### Reference EVM Clients — Reth

| | |
|---|---|
| **GitHub** | https://github.com/paradigmxyz/reth |
| **Clone as** | `repo-references/clients/reth` |
| **Used by** | `beacon` |
| **Key paths** | `crates/evm/` · `crates/consensus/` |
| **Branch convention** | `upstream` (checked out) and `main` are currently identical — no ETC overlay written yet |
| **Why** | Modern high-performance ETH client reference (Rust). No ETC overlay yet. |

### Reference EVM Clients — Erigon

| | |
|---|---|
| **GitHub** | https://github.com/erigontech/erigon |
| **Clone as** | `repo-references/clients/erigon` |
| **Used by** | `beacon` |
| **Key paths** | `core/vm/` · `consensus/` |
| **Branch convention** | `upstream` (checked out) and `main` are currently identical — no ETC overlay written yet |
| **Why** | Alternative ETH client reference (Go, staged-sync architecture). No ETC overlay yet. |

**Both locations exist and serve different purposes** — same pattern as `hive` above: `repo-references/clients/<name>` is the portable, git-relative copy for structural/spec reading (this convention); `/media/dev/2tb/dev/reference-clients-evm/<name>` is the active working copy (sync testing, running nodes, building). Both are clones of the same `white-b0x/<name>` forks. Fukuii's own tooling (`registry.yaml`, `eligible.sh`, `refresh-refs.sh`, `conformance.sh`) reads from `repo-references/clients/` for portability; use `reference-clients-evm/` directly for anything that needs a live running client.

---

### Storage — RocksDB Java

| | |
|---|---|
| **GitHub** | https://github.com/facebook/rocksdb |
| **Clone as** | `repo-references/rocksdb` |
| **Used by** | `vault` |
| **Key paths** | `java/src/main/java/org/rocksdb/` · `java/rocksjni/` · `HISTORY.md` · `include/rocksdb/options.h` |
| **Why** | Canonical Java API for `RocksDB`, `WriteBatch`, `ReadOptions`, `WriteOptions`, `ColumnFamilyOptions`, `DBOptions`, `Statistics`, and `Cache`. Read before changing column family config, WAL settings, batch write patterns, cache sizing, or iterator lifecycle. `options.h` is the authoritative reference for all tuning knobs — the Java bindings mirror it 1:1. |
