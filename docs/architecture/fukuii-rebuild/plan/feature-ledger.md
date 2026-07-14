# Feature ledger — the running "nothing forgotten" capture

Every feature, opportunity, differentiator, or vision item the plan must account for lands here — the
operator's brain-dumps, SR-surfaced opportunities, reference-client optimizations. Each gets a **home**
(a requirement `R#` and/or a layer), a **disposition**, and a **status**. Nothing is implemented that
isn't here; the multi-pass hunt (`REVIEW.md` §6b) checks every layer against this ledger. **The operator
adds freely; the orchestrator maps each to its home and never drops one.**

The big vision is already carried by the cross-cutting requirements (`requirements.md` R1–R10). This
ledger is for the granular items + the running capture that keeps them from being forgotten between the
moment they're named and the moment they're planned into a layer.

## Ledger

| # | Item | Source | Home (requirement / layer) | Disposition | Status |
|---|---|---|---|---|---|
| F1 | **MCP — native agentic-client integration** (Model Context Protocol; the node as an AI-agent tool/server — the operator's "MPC" was **MCP**). Vision: build it out **from the base** as first-class MCP + A2A + ACP, not just the copilot shim | operator | **L9 interface seam** (MCP transport alongside JSON-RPC/GraphQL/gRPC) + R9 (agentic-client / product) | adopt + build out — fukuii already has it; make it first-class to the dominant 2026 AI-agent-to-tool standard (spec 2025-11-25 → 2026-07-28 RC **stateless-core**; + A2A agent-to-agent, ACP, MCP Apps, Tasks async, OAuth 2.1) | **EXISTS**: `.github/copilot/mcp.json` (node-as-MCP-server), `docs/MCP.md`, `docs/api/MCP_{ANALYSIS_SUMMARY,INTEGRATION_GUIDE}.md`; SR `repo-patterns/{client}/agentic-tooling-pattern.md`. **Structural: mostly L9 additive-transport — but assess whether the 2026 stateless-core + async Tasks + OAuth2.1 identity touch how L9/L10 expose state/auth/sessions** (agent aacc… augmented to research this) |
| F2 | **Structured logging + log hygiene** | operator | R10; cross-cutting + L8 observability | adopt DEFAULT — consistent SLF4J API, levels, no secret leakage | grounded `observability.md`; `logging-standards.md` protocol exists |
| F3 | **Monitoring / metrics / tracing** | operator | R10; L8 observability (per-instance registry, R2) | adopt DEFAULT — per-instance Micrometer/Prometheus; tracing seam | grounded `observability.md` |
| F4 | **Auto-documentation update** (docs regenerated/updated on release) | operator ("some reference clients have this") | R10; setup + repo-tooling | schedule — adopt the client that does it well | grounded `repo-patterns/{go-ethereum,reth}/dev-workflow-pattern.md` — Wave 2 confirms which client + mechanism |
| F5 | **Developer-rich environment** — custom networks, dev testnets, tooling | operator + `multi-network.md` | R9 (+R10); setup + L5 | adopt DEFAULT — besu `generate-blockchain-config`, `fukuii-custom-networks`, dev testnets | grounded `multi-network.md` + `topics/consensus-poa-and-etc-testnets.md` |
| F6 | **Dashboards + monitoring observability tooling** (shipped Grafana dashboards, alerting config) | operator | R10; L8 observability / ops | schedule — ship dashboards + monitoring with the client | grounded `observability.md`; Wave 2 confirms which clients ship dashboards |
| F7 | **Bootnode tooling** (run/operate bootnodes; discovery/ENR/DNS infra) | operator | L6 network; ops/infra | adopt — bootnode run-mode + management | grounded `networking-p2p.md` (discovery v4/v5, ENR, DNS) |
| F8 | **Network-infrastructure platform** — *erect* testnets + *serve* as the network-infrastructure client (network-in-a-box: genesis origination + bootnodes + faucet + RPC relay + dashboards, all in-repo) | operator | **R9 expanded (erect+serve, not just join)**; setup / L5 / L6 / L9 / L10 | **directional — the positioning shift below**; plan the seams so it's buildable | grounded `multi-network.md` (custom-genesis/private-net origination) + `topics/consensus-poa-and-etc-testnets.md` + `networking-p2p.md` |
| F9 | **SNAP serving workhorse + SNAP/v2** — efficient *receiving AND serving* of state (fukuii as a stable serving node / bootnode workhorse); SNAP/v1 **and** SNAP/v2 (EIP-7928 BAL-diff), versioned | operator | L7 sync (serve+receive) + L6 (bootnode); R8 serving role; F8 | adopt receive (DEFAULT) + **serve as a first-class role capability** (default-off → on for server/archival/bootnode roles, DoS-bounded per geth `softResponseLimit`/`maxTrieNodeTimeSpent`, besu size+wall-clock); SNAP/v2 = additive versioned `Syncer` (per-network-gated; v1 = ETC+current-ETH, v2 where BAL applies) | grounded L7 dossier B1/B8 (geth snap/1 authority + DoS bounds; unified `Syncer` v1/v2; `ShardEnumerator` 16-shard ≙ go-ethereum `GenerateTrie`) |
| F10 | **Full ETH wire range** — ETH68 / 69 / 70 / 71 (+ forward slots), advertised per-network | operator | L6 network | adopt — ETC = 68-frozen (core-geth), ETH = 69/70/71 rolling; the generic multiplexer contributes the advertised set per `NetworkFamily`, never `isEtc()` | grounded L6 dossier A2/A7 (ETH70 handshake-wiring gap; eth/71 EIP-8159 BAL; besu advertises 68-71) |
| F11 | **Heterogeneous families incl. ZK** — sidechains, L2 rollups, **ZK-rollups / ZK-EVM** (custom VM / zk-gas), based-rollups | operator | L5 `NetworkFamily` (G1 depth) + L3 evm (ZK-VM/zk-gas) + L2 (stateless/witness) | the typeclass must be *sized* to this depth now (L5 G1); ZK adds custom-VM/zk-gas + stateless-witness | grounded L5 dossier G1 (rollup DepositTx/cluster-swap; Taiko based-rollup zk-gas; XDC custom-header/block-tree) + L2 dossier B15 (reth `SparseStateTrie`/witness) |
| F12 | **Auto-maintenance for maintainers** — Dependabot dep-updates (7-day cooldown) + auto-CVE-patch PRs + CI SAST (Semgrep) + supply-chain checksum gate + pre-merge Trivy + auto-doc-update | operator | **R11** (security) + R6 (deps) + R10 (tooling) + **setup/CI**; sentinel-owned | adopt — fukuii ships **zero** of these today; evidence-gated (no unilateral bumps) | grounded foundation dossier A8 + global `supply-chain-security.md` |
| F13 | **Per-layer agentic-tooling alignment** — each layer reorients agents/charters/protocols/skills to the current file tree + plan + built state (lifecycle **step 5**); setup does the initial full charter reconciliation | operator | build-process (not a client feature); **warden** owns; parallel `TOOLING-AUDIT-01` | codified as `plan/README.md` per-layer lifecycle **step 5** — agents always current + helpful through the build | grounded operator directive + the dead-`src/`-charter miss |
| C1 | **Security / hardening** (key custody, RPC auth/JWT, TLS, peer-DoS, constant-time crypto, + auto-CVE/SAST) | operator-confirmed | **→ promoted to R11** | **RESOLVED — now R11** (low-level implications: constant-time L0 primitive + L9 auth/capability gate; evidence L8-9-10 dossier) | grounded `accounts-signer.md` + security topics + foundation dossier A8 |
| C2 | **Upgradeability** — add a new fork/EIP/network cheaply | operator-confirmed | **→ folded into R1 + R6/L3** | **RESOLVED — folded** (`ForkActivation`/`NetworkFamily` seams; each layer states its add-a-fork/network property explicitly) | — |

_Legend: F# = captured feature (has a home); C# = candidate requirement (operator to confirm)._

## Not-forgotten check (runs in the multi-pass)

Before any `plan/L{n}.md` is marked `READY FOR IMPLEMENTATION`, verify: every ledger item whose home
includes that layer is addressed in the layer plan (adopted / scheduled / consciously deferred). An
unaddressed ledger item on a "ready" layer is a gate failure.

## Positioning shift (F6–F8) — flag for the requirements pass-3

fukuii is not only a client that **joins** a network — it is the infrastructure to **erect** and **serve**
one: bootnodes, dev/testnet origination, dashboards + monitoring, faucet, RPC relay, all built into the
client repo. This widens **R9** from "product family" toward "**network infrastructure platform**," and it
imposes downward seams (genesis origination at setup/L5, bootnode/discovery at L6, serving modes at L9,
per-instance orchestration at L10). To be promoted into `requirements.md` in the pass-3 requirements review.

## Storage-approach completeness (R8 sharpening — operator)

R8 sharpens from "storage *modes* per role" to **multiple storage APPROACHES/BACKENDS, each optimized for
a network-type × user-role, selectable** — the *default + multiple-additive* doctrine applied to storage.
**Do not pigeonhole on one backend.** The proof and the menu are all in the reference repos (per the L2
dossier — grounded, not invented):
- **besu** Forest **and** Bonsai (+ X_BONSAI_ARCHIVE) — two full approaches in ONE stack, behind one
  `DataStorageFormat` interface (separate `WorldStateKeyValueStorage` hierarchies). The proof.
- **erigon** flat-state / Domains / MDBX · **reth** pathdb / nibble-path / static-files / parallel+streaming
  state-root · **nethermind** Hash / HalfPath / online-full-pruning · **go-ethereum** hashdb / pathdb /
  snapshot / freezer.

**The specific gap the SR flagged (operator):** fukuii inherited **core-geth's HASH-keyed** node store, but the
reference-client *standard* has moved to **PATH-keyed** (geth pathdb, nethermind HalfPath-default, reth
nibble-path — "the direction all three independently converged on," L2 dossier B3). Support **both**:
archival = hash-keyed (dedup, full history); pruned/tip = path-keyed (locality, bounded disk, cheap online
pruning). Realized via nethermind's **`INodeStorage` scheme-indirection seam** (mutable `Scheme` + dual-read
fallback), so keying is a per-datadir role choice **and** an online Hash→HalfPath migration. Structural: the
scheme-indirection seam must be at the `MptStorage` boundary from line one (today `PathNodeStorage` is a
one-way-locked SNAP island — L2 dossier gap #5).

**Completeness axis (the user × network matrix R8 selects on):** archival-deep-data-for-dApps · tip-of-branch
data server · pruned RPC public-relay · resource-light end-user · validator · mining-pool — across L1 /
sidechain / L2-rollup / diverse consensus (PoW/PoS/PoA). A storage approach optimized for an L2 rollup ≠ one
for an L1 ≠ one for archival — support the ones that make sense.

**Realization:** the L2 `StorageProfile` role×network selector (L2 dossier §A, besu `DataStorageFormat`
shape) composing {node-keying × pruning-strategy × flat-accelerator × freezer × history-expiry ×
backend-format}. **Structural — the L2 plan must be built as a multi-approach architecture from the seam
out, not one hardcoded backend** (retrofitting a second backend behind a hash-keyed-only design is a
rewrite). Formalize in the requirements pass (R8 expanded); build into the L2 Wave-2 enrichment.

## Open for the operator

Keep listing — this is the trusted capture. Two I need from you:
1. **F1 (MPC):** where does native MPC live, or is it a to-build differentiator? Confirm so L8 plans it right.
2. **C1 (security), C2 (upgradeability):** promote to first-class requirements (R11+) or leave folded?
