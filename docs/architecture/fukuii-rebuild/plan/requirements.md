# Cross-cutting requirements & downward propagation (Wave 1a)

The **cohesion backbone** of the rebuild plan. These are the top-level architectural requirements that
are NOT owned by any single layer — they impose design constraints that propagate **down** into every
layer below, and the low layers must be *designed* to carry them (memory
`research-into-cohesive-plan-before-building`). A layer plan is not complete until it satisfies the
downward constraints here, not just its own slot's evidence (`REVIEW.md` §4/§5b).

Each requirement is grounded in its evidence source — the design goals (`fukuii-mission-strategic-context`),
the SR cross-cutting synthesis (`observations/cross-cutting-themes.md`), and the per-subsystem
observations. **This is the Wave-1a first cut; it is vetted and deepened in Waves 3–4 (first pass
untrusted).** Layers marked "(deepen in 1b/2)" carry a constraint stated at the right altitude but not
yet cross-checked against that layer's specific observation.

## The requirements

| # | Requirement | Evidence source |
|---|---|---|
| **R1** | **Multi-network framework** — many networks across families (PoW/PoS/PoA…), not an ETC/ETH binary | design goal (omni-client thesis); `multi-network.md`; `consensus-engines.md` §B7.0.5 |
| **R2** | **Concurrent multi-instance, single-binary** — run N isolated networks in one process (the enterprise differentiator) | design goal (JPMC/E*TRADE/Fireblocks single-binary); `node-lifecycle.md` |
| **R3** | **Family-neutrality** — no `isEtc()`/`is_optimism()` in shared code; inject knobs through the family typeclass | `consensus-engines.md` invariant; erigon `FrozenBorBlocks` counter-example |
| **R4** | **Consensus byte-exactness / per-concern authority** — deterministic state root, validated vs the right client | `REVIEW.md` §3 authority map; `consensus-change-protocol.md` |
| **R5** | **Pekko-Typed structural target + Cats-Effect discipline** — re-draw actor boundaries onto the production shape, not a 1:1 Mantis port | `cross-cutting-themes.md` Theme 1; `pekko-typed-api.md`; `best-practices/{pekko,typelevel}` |
| **R6** | **Born-modern** — Scala 3 idiom + successor deps from line one; single version source; supply-chain gate | `cross-cutting-themes.md` (build floor); `build-deps.md`; MOD-19 |
| **R7** | **gRPC product-family / dRPC seam** — one clean gRPC boundary = internal decomposition AND dRPC-Provider bridge | `cross-cutting-themes.md` Theme 2; `exec-extensions.md`; `DRPC-GATEWAY-01` |
| **R8** | **Storage approaches & deployment modes per role** — *multiple* backends (hash **and** path-keyed; Forest/Bonsai/flat/freezer), network×role-selectable; never one hardcoded store | `storage-persistence.md`; `state-trie.md`; `historical-distribution.md`; L2 dossier (all-6-client menu) |
| **R9** | **Product family, ecosystem & network-infrastructure platform** — dRPC, CL+EL, mining pools, GUI, validator sw, MCP/agentic, dev-rich env; *erect + serve* networks (not just join) | mission memory; `cross-cutting-themes.md` Th2; `cl-engine.md`; `block-production.md`; `multi-network.md`; MCP/L8-9-10 dossier |
| **R10** | **Observability & operational / dev tooling** — logging+hygiene, metrics/dashboards/tracing, auto-doc-update, dev-rich env, testing ratchets | `observability.md`; `repo-patterns/*/dev-workflow`; `testing.md`; `logging-standards.md` |
| **R11** | **Security, hardening & automated security-maintenance** — constant-time crypto, keystore hardening, the **auth/identity/capability gate**, TLS, peer-DoS, **+ CI SAST + auto-CVE scanning + supply-chain gate + auto-maintenance** (sentinel-owned) | `accounts-signer.md` + security topics; `constant-time-comparison.md`; L8-9-10 + foundation dossier (A8: zero SAST); `supply-chain-security.md` |

---

## R1 — Multi-network, multi-consensus, heterogeneous families

**Source:** the mission (fukuii as the multi-network successor); `multi-network.md` (chain-spec/genesis
data axis); `consensus-engines.md` §B7.0.5 (`given NetworkFamily` typeclass — reth compile-time safety
inside nethermind single-binary runtime selection); **`topics/consensus-l2-rollup-sidechain.md`** (the
family-size spectrum: thin Gnosis → heavy **Polygon/Bor** sidechain → **OP-stack rollup** → alt-BFT L1)
and **`topics/consensus-methods-catalog.md`** (the 16-method matrix). **The scope is the full spectrum,
NOT ETC/ETH:** the roadmap is Polygon/Bor, L2 rollups, other alternative L1s + their families, and private
PoA — each a `NetworkFamily` instance (erigon `polygon/` = the heavy-family-ships-own-chainspecs +
injected-oracles packaging). **The structural spine:** L5's `NetworkFamily` can only be a *thin* seam
because L0–L4 are network-neutral; a network assumption in any lower layer forces `isEtc()` branches
upward and breaks the framework — and it must accommodate a **heavy family** (Bor's Heimdall/bridge
oracles) and a **rollup**, not just PoW/PoS Ethereum variants.

| Layer | Downward constraint |
|---|---|
| **L0** | Value types + crypto carry **no** network identity; ordering/canonical ops network-agnostic. |
| **L1** | `domain` models **all** tx/header/receipt variants; per-network **admissibility** is a separate gate, not a modelling omission; `ChainId` is opaque data. |
| **L2** | **One datadir schema serves every family** — CF layout + keys network-neutral, **no `isEtc()` in keys**; genesis/chainspec is *data* loaded per network (besu/nethermind "name→resource"). |
| **L3** | Fork dispatch is the parameterized **`ForkActivation`** seam (block ⊥ timestamp ⊥ TTD; reth `ForkCondition` is the cited prior art); `Etc*`/`Eth*` opcode/fee objects never cross; the EVM is **family-blind**. |
| **L4** | Reward/finalize is a **per-family hook** selected by `ProtocolSpec` (ECIP-1017 vs withdrawals), never hardcoded; execution family-blind. |
| **L5** | The `NetworkFamily` typeclass + positive `EngineId` keying + conditional merge — the framework itself, thin *because* L0–L4 are neutral. |
| **L6** | ForkId + wire computed **per network** from the fork schedule; peer handling multi-network (deepen in 1b/2 vs `networking-p2p.md`). |
| **L7** | Sync strategies parameterized per network (PoW head vs PoS pivot; per-network checkpoint) (deepen vs `sync.md`). |
| **L10** | Instantiates N families through the typeclass — the convergence point with R2. |

## R2 — Concurrent multi-instance, single-binary

**Source:** the enterprise GTM (one binary hosting many networks, isolated); `node-lifecycle.md`
(besu Lifecycle FSM, embedded-or-remote wiring). **The propagated invariant:** **no global singletons /
mutable statics anywhere L0–L9** — a global metrics registry, a static config object, a shared RocksDB
handle, a JVM-wide cache — because each would break per-instance isolation.

| Layer | Downward constraint |
|---|---|
| **L2** | Per-instance datadir + RocksDB handle; no process-global DB state. |
| **L3/L4** | No mutable global EVM/execution state; per-instance context threaded, not static. |
| **L8** | **Per-instance** metric registry (not a global static — the old Kamon global was the anti-pattern), per-instance keystore/txpool. |
| **L9** | Per-instance RPC routing; one Engine-API endpoint per PoS instance. |
| **L10** | The **concurrent multi-`ChainInstance` runtime**: N isolated Typed guardian subtrees, hard isolation, each family wired via the R1 typeclass. R1+R2 converge here — the differentiator. |
| **all** | Litmus: grep for `object … { var … }` / global singletons; each is an isolation break to justify or remove. |

## R3 — Family-neutrality

**Source:** `consensus-engines.md` (inject a family's oracles/knobs *through* the typeclass, never into
shared readers); the cautionary counter-examples erigon `FrozenBorBlocks`-in-`ChainHeaderReader` and reth
`is_optimism()`-in-shared-trait. **Bites at every shared path:** L2 storage readers, L4 execution, L5
`consensus-api`, L6 network. **DoD grep:** no `isEtc()`/`isEth()`/`is<family>()` branch in neutral code;
family behavior arrives via the `NetworkFamily` typeclass / `ProtocolSpec` bundle. This is R1's
enforcement mechanism — R1 says "be network-neutral," R3 is the check that catches the leak.

## R4 — Consensus byte-exactness / per-concern authority

**Source:** `REVIEW.md` §3 authority map (core-geth = ETC-frozen; go-ethereum+besu = ETH/shared; besu =
JVM guide; the non-JVM three for design ideas); `consensus-change-protocol.md` (state-root litmus).
**Bites at** L0/L1/L3/L4/L5. **The constraint:** every consensus value is validated against the
*verified reference map built from the per-concern authority first*, then diffed — never against current
code; forge (PoW) / beacon (PoS) gate before implementation; the besu JVM-implementation lens runs every
layer (it caught F-BN-1, B-BLS-1, J-RLP-1 that a Go-only comparison missed).

## R5 — Pekko-Typed structural target + Cats-Effect discipline

**Source:** `cross-cutting-themes.md` Theme 1 — **no reference client uses actors**, so the migration
*mechanics* are ours (`pekko-typed-api.md`, `loom`), but the clients give the **target architecture** the
actor boundaries should adopt. The risk to avoid: a 1:1 port of Mantis's Akka-Classic structure.

| Constraint (from Theme 1) | Where it bites |
|---|---|
| **Channel-ownership granularity** — one Typed actor's private state = what one geth/erigon goroutine would exclusively own | L6/L7/L10 actor boundaries — *not* Mantis's actor set |
| **Constructor-injected `ActorRef[Command]`** (besu `ServiceManager` DI), never `actorSelection`/global lookup | L10 wiring; every actor |
| **Lifecycle as distinct Behaviors** (besu FSM, behavior-as-state-machine) | L10; long-lived actors |
| **Immutable per-fork `ProtocolSpec` bundle** the actor *references*; no mutable fork state | L5/L4 |
| **Sealed `Command` ADT + explicit `replyTo`**, never `Behavior[Any]`/`sender()` | every actor layer (L6+) |
| **Cats-Effect / fs2 from Typelevel** (not the EVM clients — they're imperative/async) | L2 `DataSource` returns `IO`/`fs2`; effect discipline throughout; no `unsafeRunSync` in actors (TL1/TL2) |

## R6 — Born-modern

**Source:** `cross-cutting-themes.md` build-floor note + `build-deps.md` + MOD-19. **The constraint:**
each layer is *written on the successor from line one* — Scala 3 idiom (opaque/given/extension/enum/
derives, never `implicit class/def`); Pekko Typed; successor deps (circe not json4s, Caliban not Sangria,
Streams-Tcp not Classic-Tcp); **single version source** (`project/Dependencies.scala`, besu central-BOM
shape); supply-chain gate (`resolution-age` ≈ besu's sha256 `verification-metadata`). Never re-introduce
the old library. Bites at **setup** (the build floor) and every layer.

## R7 — gRPC product-family / dRPC seam  *(the requirement the first draft missed)*

**Source:** `cross-cutting-themes.md` Theme 2 — **one gRPC service boundary serves two strategic goals at
once**: erigon's internal component decomposition ("one interface, two impls: in-proc `direct.*` shim /
remote gRPC client" — same binary runs monolith or distributed) AND the dRPC-Provider bridge (drpc.org's
data plane is gRPC end-to-end; a Provider is a node exposing a gRPC service). Carries **reth ExEx**
(reorg-aware notifications + `FinishedHeight` distributed prune barrier + WAL restart-safety — the
backpressure + reorg-safety besu's synchronous callbacks lack). Deliverable: `DRPC-GATEWAY-01` (fukuii as
an **upstream to Dshackle**, implementing its `Blockchain.proto` as a thin Provider adapter over conduit's
JSON-RPC) + the product-family seams (pool-software / validator-software / GUI consume the node's clean
seams — the mission GTM).

| Layer | Downward constraint |
|---|---|
| **L2** | The `FinishedHeight` prune barrier means **pruning must be gateable on `min(consumer heights)`** — the storage pruning seam (R-L2) must accept an external "safe height," not prune purely on local reorg depth. Design the hook now even if the consumer is later. |
| **L4/L5** | Reorg-aware **notifications** (reth ExEx): execution/consensus must emit reorg-aware block/state events the seam can carry — a clean event source, not ad-hoc callbacks. |
| **L9** | The **gRPC data-seam** (`grpc-seam`) alongside conduit's JSON-RPC: erigon `StateChanges` (push diffs + remote-KV pull) + reth ExEx design + the Dshackle `Blockchain.proto` Provider adapter. Built-but-unshipped is the risk. |
| **L10** | Embedded-or-remote wiring: the seam's in-proc shim vs remote gRPC is a **one-function topology choice** at composition (erigon `direct.*`). |
| **all seam producers** | The rule is *same pattern (gRPC boundary), not same interface* — internal-component protos (erigon remote-KV/Sentry) are distinct from the Dshackle chain-agnostic RPC-forward proto. |

## R8 — Storage approaches & deployment modes per user-role  *(multiple backends, network×role-selectable)*

**Source:** `storage-persistence.md`, `state-trie.md`, `historical-distribution.md` + the L2 mining dossier
(the full all-6-client storage menu). **The requirement (sharpened by the operator):** fukuii supports
**multiple storage *approaches / backends*, each optimized for a network-type × user-role, selectable** —
the *default + multiple-additive* doctrine applied to storage. **Do not pigeonhole on one backend.** besu
alone proves it (Forest **and** Bonsai behind one `DataStorageFormat` interface). The menu, all in the
reference repos: besu Forest/Bonsai/X_BONSAI_ARCHIVE · erigon flat-state/Domains/MDBX · reth
pathdb/nibble-path/static-files/parallel-streaming-state-root · nethermind Hash/HalfPath/online-full-pruning ·
go-ethereum hashdb/pathdb/snapshot/freezer. **The SR-flagged gap:** fukuii inherited core-geth's **HASH-keyed**
node store, but the ref-client *standard* is now **PATH-keyed** (geth pathdb / nethermind HalfPath-default /
reth nibble-path — the direction all three converged on) — support **both** (archival = hash-keyed,
pruned/tip = path-keyed) via nethermind's `INodeStorage` scheme-indirection seam (mutable `Scheme` +
dual-read fallback = a per-datadir role choice *and* an online migration). **Realization:** a `StorageProfile`
role×network selector (besu `DataStorageFormat` shape) composing {node-keying × pruning × flat-accelerator ×
freezer × history-expiry × backend-format} — **built as a multi-approach architecture from the seam out**
(retrofitting a 2nd backend behind a hash-only design is a rewrite). The role × network matrix:
archival-deep-data-for-dApps · tip-of-branch server · pruned RPC-relay · resource-light · validator ·
mining-pool, across L1 / sidechain / L2-rollup / diverse consensus. **SNAP serving** (F9) is a role-gated
capability — fukuii as a stable *serving* workhorse (default-off → on for server/archival/bootnode), SNAP v1+v2.

| Layer | Downward constraint |
|---|---|
| **L2** | The **`StorageProfile` selector** (besu `DataStorageFormat` shape) composing {keying(hash **and** path) × pruning × flat × freezer × expiry × backend} — a **multi-approach architecture from line one**, never a single hardcoded store; `INodeStorage` scheme-indirection at the `MptStorage` boundary. Composes with R7's prune-barrier. |
| **L7** | Sync mode matches the role — archival backfills history; relay/tip snaps to head; checkpoint-pivot for fast bootstrap. **SNAP serving (F9)** is a first-class role capability (default-off → on for server/archival/bootnode), DoS-bounded; SNAP/v1 + v2 versioned. |
| **L9** | RPC serving mode matches the role — archival answers historical queries; a relay serves tip + forwards. |
| **L10** | Per-instance mode selection (with R2: different instances in one binary may run different storage modes). |

## R9 — Product family, ecosystem & network-infrastructure platform  *(the additive vision / GTM)*

**Source:** `fukuii-mission-strategic-context` (mining-pool GTM + enterprise single-binary);
`cross-cutting-themes.md` Theme 2 + `DRPC-GATEWAY-01`; `cl-engine.md` + `CL-RESEARCH-EMBED-01` (embedded
CL+EL); `block-production.md` + `topics/mining-protocol-{evm,nonevm}.md`; `multi-network.md` custom-genesis +
`topics/consensus-poa-and-etc-testnets.md`; the L8-9-10 dossier (MCP: erigon is the node-level MCP authority).
**The requirement:** the node is the foundation of a **product family AND a network-infrastructure platform** —
it exposes clean seams so additive products stand on it without forking the core, **and it can *erect and
serve* a network, not just join one**:
- **Products:** dRPC Provider (Dshackle `Blockchain.proto`), **CL+EL** (embedded CL driver), **PoW mining
  pools** (pool-grade getWork/submitWork + optimization) + **GUI**, **validator software**, and **native
  MCP / A2A / ACP agentic-client** interfaces (F1 — erigon-authority: embedded-SSE + standalone stdio/SSE;
  the *auth gate* is R11-structural, the rest additive-L9).
- **Network-infrastructure platform (F7/F8):** bootnode *serving*, ENR/DNS-tree *authoring/publishing*,
  custom-network origination (besu `generate-blockchain-config`), dev testnets, faucet, RPC relay, shipped
  dashboards — a network-in-a-box built into the client repo (fukuii as a stable serving workhorse).
- **Dev-rich environment (F5):** first-class, not an afterthought.

R7 is the data-plane *enabler*; R9 is *which products / infrastructure the seams must serve*.

| Layer | Downward constraint |
|---|---|
| **L5** | Mining seam is **pool-grade** (getWork/submitWork verify-before-ack, ECIP-1099 seedHash — the mining GTM); the CL driver is clean enough to host an embedded CL (CL+EL) later. |
| **L6** | **Bootnode + ENR/DNS-tree serving/authoring** (F7/F8) — the *write/serve* side of discovery (fukuii serves infrastructure, not just consumes it). |
| **L9** | Seams serve the family: JSON-RPC (conduit) + the R7 gRPC/dRPC Provider adapter + **native MCP/A2A/ACP agentic interface** (additive transports over the service layer; the auth gate is R11) + the R8 RPC serving modes. |
| **L10** | Embedded-or-remote wiring so pool-software / validator-software / GUI / dRPC-gateway / MCP-server attach in-proc or as separate processes (erigon `direct.*` shim vs remote gRPC; MCP embedded-SSE vs standalone binary). |
| **setup + L5** | **Dev-rich environment** as a first-class concern: custom-network origination (besu `generate-blockchain-config`), dev testnets, `fukuii-custom-networks` tooling — not an afterthought. |

## R10 — Observability & operational / developer tooling

**Source:** `observability.md` (metrics/health/tracing), `logging-standards.md` (F2),
`repo-patterns/{reth,go-ethereum}/dev-workflow-pattern.md` (F4 auto-docs), `testing.md` (the DoD ratchets).
**The requirement:** production operability + developer experience are **first-class**, not afterthoughts:
- **Logging + hygiene (F2):** consistent SLF4J/MDC, correlation (peerId/blockNumber), levels, **no secret
  leakage**.
- **Metrics + dashboards + tracing (F3/F6):** per-instance registry (R2 — the Bug-29 fix), Prometheus
  exporter, OTLP tracing seam, and the **15 shipped Grafana dashboards** (fukuii is the only client besides
  erigon that ships them) — preserved and **versioned against the per-instance metric names**.
- **Auto-doc-update (F4):** the reth `update-book-cli` + **`git diff --exit-code`** regenerate-and-verify
  pattern (+ go-ethereum `check_generate`) — for generated CLI/RPC-method reference and the metric↔dashboard
  drift check.
- **Testing ratchets (`testing.md`):** the cross-cutting testing plan — assert-fails-**with-reason** (not
  tag-hide), assert-nonzero-fixture-count (the REPO-06 false-green class), fork-in-test-name (dual-family
  selectability), the besu **acceptance-cluster DSL** (the vehicle for L10 multi-instance isolation tests),
  ScalaCheck decoder-hardening, and **reference-client-crosscheck as the DoD engine**.

| Layer | Downward constraint |
|---|---|
| **all** | Structured logging + hygiene discipline every layer; secret-free logs. |
| **L8** | The **per-instance observability registry** (R2) + shipped dashboards versioned to metric names; consensus-family-aware health (PoW block-liveness vs PoS CL-alive). |
| **setup** | Auto-doc regenerate-`git diff` gate + `check_baddeps`-style CI **DAG-enforcement** gate (the module-boundary ratchet). |
| **L9** | RPC observability (per-method timing, debug/trace isolation) + the auto-doc hook for RPC-method reference. |
| **cross-cutting** | The testing plan (SR-12) is authored as its own doc, not deferred to each layer's §8. |

## R11 — Security & hardening  *(promoted from candidate C1 — it has low-level implications)*

**Source:** `accounts-signer.md` + the security topics; `best-practices/evm-clients/constant-time-comparison.md`;
the L8-9-10 dossier (the MCP-2026 auth requirement). **The requirement:** security is **first-class with
structural, foundation-level implications** — not a high-level add-on. The evidence: three otherwise-separate
features (MCP-2026 OAuth 2.1 + write-ops, Engine-API JWT, external-signer custody) **all converge on one
auth/identity/capability seam** at the serving boundary, and the crypto sites need a low-level constant-time
primitive.
- **L0 constant-time primitive (structural):** `crypto` **must expose** `constantTimeEquals` (BouncyCastle
  `Arrays.constantTimeAreEqual`) as a callable symbol — today L0 has only an inline BC call, not the
  exported primitive (RX-L0-16 build-gate); fukuii's keystore MAC uses plain `==` today (a real timing
  finding). Consumers: **L6 ECIES tag / L8 keystore MAC / L9 JWT** (no L1 equality site — L1's signature
  work is the *inbound range gate*, not a constant-time compare; RX-L0-11/17).
- **The auth/identity/capability gate (structural, L9):** a per-request **`Principal` + `Capability`
  permission gate + audit log** threaded through the RPC dispatch registry, **per-instance under R2** —
  designed *now*, not retrofitted per-transport. One seam serves MCP write-ops (mining/peer/config control),
  Engine-API JWT, and the external-signer boundary. **JVM structural mirror = besu
  `ethereum/api/.../jsonrpc/authentication/`** (`AuthenticationService.isPermitted` + `Principal` +
  per-method `permissions` + `EngineAuthService` JWT). **Precedent split (RX-XC-07):** Engine-API JWT +
  per-method RPC permissions are **precedented** (besu + geth `jwt_*`); **MCP write-ops auth is
  unprecedented** (erigon MCP is read-only, no auth) — designed to the MCP-2026 OAuth 2.1 spec; the
  **unification** of all three transports into one gate is fukuii's own synthesis (besu keeps *separate*
  auth services). All three verification paths call L0 `constantTimeEquals`.
- **Keystore hardening (L8):** verify-after-write + atomic-rename, key-zeroing / `ProtectedPrivateKey`
  heap-protection (vs bare `ByteString` in heap), the `ISigner`/external-signer/HSM/clef custody seam.
- **TLS + peer-DoS (L6):** the blacklist/ban policy (fukuii's 365-day tier is a 2-orders-of-magnitude
  outlier — ratify), DialRatio/inbound-throttle, decompression-bomb bounds.
- **Automated security + maintenance (operator):** CI **SAST** (Semgrep — CodeQL has no Scala extractor;
  fukuii has **zero** SAST today per the foundation dossier A8), **automated CVE scanning** (Dependabot
  security advisories + the `resolution-age` / cooldown gate), the **checksummed supply-chain gate** (besu
  `verification-metadata` — fukuii has none), **pre-merge container scan** (nethermind Trivy), `SECURITY.md`.
  **Auto-maintenance for maintainers (F12):** Dependabot dep-updates with 7-day cooldown, auto-CVE-patch PRs,
  auto-doc-update (F4 / R10) — so maintainers don't chase manually. All **sentinel-owned** (evidence-gated,
  no unilateral bumps; `.../rules/supply-chain-security.md`).

| Layer | Downward constraint |
|---|---|
| **L0** | `constantTimeEquals` primitive; **sign-side low-S + recover-side curve-order check** are consensus (R4) — the *inbound* signature range gate is L1's, not L0's. |
| **L1** | Inbound signature **range gate** (r,s ∈ [1,N), s ≤ N/2 — geth `ValidateSignatureValues`). |
| **L8** | Keystore MAC uses the L0 constant-time primitive; keystore hardening (atomic-write, zeroing); the `ISigner`/external-signer custody seam. |
| **L6** | Peer-DoS resistance + the blacklist/ban policy (L6 owns the type → owns the policy). |
| **L9** | The **`Principal`/`Capability` auth gate** at the serving boundary (MCP-OAuth2.1 + Engine-API JWT + RPC auth), per-instance; TLS. |
| **L10** | Per-instance auth/identity; no process-global credential state. |
| **setup / CI** | CI security automation — Semgrep SAST + Dependabot (deps + security, cooldown) + supply-chain checksum gate + pre-merge Trivy + `SECURITY.md`; the **auto-maintenance** pipeline (sentinel-owned). fukuii ships **zero** of these today (dossier A8). |

---

## The consolidated propagation matrix (requirement × layer)

The completeness test (`REVIEW.md` §5b): for each cell, can the layer plan point at the specific design
decision that satisfies it? An empty cell where a requirement bites = an on-the-fly gap that surfaces at
integration.

| | L0 | L1 | L2 | L3 | L4 | L5 | L6 | L7 | L8 | L9 | L10 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **R1 multi-network** | neutral types | all variants | neutral schema | `ForkActivation` | per-family hook | `NetworkFamily` | per-net ForkId | per-net sync | — | — | N families |
| **R2 multi-instance** | — | — | per-inst DB | no global EVM state | no global exec state | — | — | — | per-inst registry | per-inst routing | multi-`ChainInstance` |
| **R3 family-neutral** | — | — | no `isEtc()` keys | family-blind EVM | family-blind exec | typeclass inject | neutral wire | — | — | — | — |
| **R4 byte-exact** | ✔ | ✔ | ✔ (state root) | ✔ | ✔ | ✔ | — | — | — | — | — |
| **R5 Typed/effect** | `IO` types | — | `IO`/fs2 DataSource | immutable spec | immutable spec | Command ADT | Typed+channel-own | Typed+channel-own | — | — | DI + Lifecycle FSM |
| **R6 born-modern** | (setup floor) — Scala 3 idiom + successor deps every layer | | | | | | | | | | |
| **R7 gRPC seam** | — | — | prune-barrier hook | — | reorg events | reorg events | — | — | — | grpc-seam | embed-or-remote |
| **R8 storage modes** | — | — | role-gated strategy | — | — | — | — | mode per role | — | serve mode | per-inst mode |
| **R9 product family** | — | — | — | — | reorg events | pool mining + CL driver | bootnode/ENR serving | — | — | grpc+dRPC+MCP+modes | embed-or-remote |
| **R10 obs/tooling** | logging | — | — | — | — | — | — | — | per-inst registry + dashboards | RPC obs + auto-docs | — |
| **R11 security** | constant-time equals (primitive) | low-S gate | — | — | — | — | peer-DoS/blacklist + **ECIES-tag constant-time** | — | keystore hardening + signer seam + **const-time MAC** | **auth/capability gate** + **JWT const-time** | per-inst auth |

_Next: Wave 1b audits each existing `plan/L{n}.md` against this matrix + the §4 rubric → the per-layer gap
punch-list. Cells here are the first cut — Wave 3 verifies each is actually satisfied end-to-end._

**Candidate resolution (operator-confirmed via the L8-9-10 dossier evidence):** **C1 → promoted to R11**
(security has low-level implications — the constant-time L0 primitive + the L9 auth/capability gate that
MCP-OAuth2.1, Engine-API JWT, and external-signer custody all converge on). **C2 (upgradeability) → folded**
into R1 (the `NetworkFamily` seam makes adding a network cheap) + R6/L3 (the `ForkActivation` seam makes
adding a fork/EIP cheap) — no separate requirement; each layer plan states its "add a fork/network without
refactoring" property explicitly rather than leaving it implicit.
