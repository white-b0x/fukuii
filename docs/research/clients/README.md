# Reference-Client Knowledge Base — taxonomy, templates & execution model

_Phase-0 keystone for the **Systemic Review & Reference-Client Alignment** sprint (see
`.claude/sprints/QUEUE.md` → the top-level "Systemic Review & Reference-Client Alignment" section).
This directory is the durable, public information system: thorough documentation of each production-grade
reference client, then cross-client comparison, used to steer fukuii's modernization with empirical
evidence rather than guessing. Locks the common structure so all clients are comparable._

## Why this exists
fukuii is pre-1.0 with no users — the one window to align every subsystem to production-grade best
practice. We read and digest each reference client, aggregate structured reports here, compare them, then
audit fukuii against the comparison. Two mini-versions already proved the method this session (the B7.0
engine-axis research; the preliminary-SR horizon scan).

## The phases (this dir = Phase 1–2; fukuii audit = Phase 3–4, local) — MULTI-WAVE (operator 2026-07-13)
Phase 1 is **iterative, not a single pass** ("measure many times, cut once" — the raw material is 6 production
clients + full git history). Do NOT rush to Phase 2.
- **Phase 1a — ★ orientation** ✅ DONE: `{client}/{storage,consensus,sync,multi-network}.md` across all 6 (+ besu networking). Taught us the *shape* of the differences, not the depth.
- **Initial assessment** ✅ [`initial-assessment.md`](initial-assessment.md) — orientation synthesis + the **deep-question map** that drives the rest. (Read this first.)
- **Phase 1b — deep full review** ✅ DONE (2026-07-13): all 14 slots documented for all 6 clients (84 `{client}/{subsystem}.md` docs; grid below full). Traversed via each client's own dependency graph, `upstream`-only. Branch discipline held (core-geth inverted @ `4185df450`; besu/nethermind overlays avoided).
- **Phase 1c — second-wave deep questions** ✅ DONE (woven into 1b): **historical PoW/ETC** (`{client}/history-pow-etc.md` for geth/besu/erigon/nethermind; core-geth=baseline, reth=never-PoW) + **ETH68→72 wire commit-log evolution** (`topics/wire-protocol-evolution.md`, incl. the cross-client adoption matrix + ETH68 preservation for fukuii) + mining-protocol survey (`topics/mining-protocol-{evm,nonevm}.md`). Third-wave targeted follow-ups can still surface during Phase 2 — that's expected.
- **Phase 1c-third-wave — extended slots 15–20** ✅ DONE (2026-07-13, the module-list gap audit): all 6 gap subsystems (block-production, accounts-signer, cl-engine, exec-extensions, historical-distribution, observability) documented across all 6 clients (36 docs; extended grid below full). **Phase 1 is now COMPLETE: 20 slots × 6 clients = 120 per-client docs + the topic surveys (§3a history, §3b wire, mining-protocol, consensus-methods catalog).**
- **Phase 1c-third-wave — consensus-methods & network-types survey** ✅ DONE (operator 2026-07-13, "review for PoW/PoS/PoA/L2 + all network types, current + deprecated via git — all in scope for fukuii"): `topics/consensus-methods-catalog.md` (MASTER: 16 methods × 6 clients + fukuii DEFAULT/OPTIONAL/OBSOLETE verdicts) backed by `topics/consensus-poa-and-etc-testnets.md` (Clique/IBFT2/QBFT/AuRa + ETC Kotti dead PoA testnet), `topics/consensus-l2-rollup-sidechain.md` (Bor/OP/Taiko/Xdc/Gnosis), `topics/consensus-pow-cpu-dev-and-deprecated.md` (CPU-Ethash/dev-seal/faker + deprecated-PoW), `topics/pos-networks-and-testnets.md` (Sepolia/Hoodi/Holesky + deprecated testnets). Complements the ★`consensus-engines` + `multi-network` slots + §3a `history-pow-etc` with mechanism depth + full network inventory.
- **Phase 2 — cross-client observations/comparison** ✅ DONE (2026-07-13): all 20 `observations/{subsystem}.md` written + `observations/cross-cutting-themes.md` (the two crystallized themes: CSP/JVM→Pekko-Typed target; gRPC-seam = product-family + dRPC bridge). Each = comparison table + use-case-aware approach catalog (DEFAULT/OPTIONAL(role)/OBSOLETE) + best-practice synthesis + fukuii implications. ← **Phase 3 is next.**
- **Phase 3 — fukuii snapshot** → `.local/docs/…` (fukuii evolves; snapshot goes stale).
- **Phase 4 — alignment audit → modernization backlog** → `.local/docs/…` → new QUEUE items.

### Scheduled follow-up track — Consensus-Layer (CL) SR (GATED, not now)
The EL SR (Phases 1–4 above) is the active track. A **parallel CL research track is scheduled as a delayed
follow-up, gated on EL Phase 4** — specifically, triggered when fukuii actually moves to **implement an
embedded CL (à la erigon's Caplin)** and/or the **validator-software** product component. These are the
"feature-complete" omni-client elements (single-binary EL+CL for the PoS family), not near-term. Rationale for
the gate (operator 2026-07-13): CL findings are only actionable once the PoS-family direction is decided
(Phase 4 decides it); CLs move fast so research done far ahead goes stale; and a full CL SR is its own
orientation→deep→comparison cycle with a **separate taxonomy** (beacon-state / fork-choice LMD-GHOST + Casper-FFG,
attestation & aggregation, sync committees, SSZ, gossipsub/discv5, validator duties, slashing protection,
checkpoint / light-client sync) — NOT the EL 20-slot model. The 6 CL repos are **pre-cloned and ready** at
`.claude/repo-references/consensus-clients/` (teku = the JVM structural mirror; + lighthouse/prysm/nimbus-eth2/
lodestar/grandine), Caplin already in-tree at `clients/erigon/cl/`. Do NOT start this ahead of EL Phase 2.

## Execution model (operator 2026-07-13) — SEQUENTIAL, RESOURCE-CAPPED
The NUC is resource-constrained; do NOT fan out a dozens-of-agents Workflow.
- **One client at a time, in order**, so results never mix. Order (adjustable): **go-ethereum → core-geth
  → besu → erigon → nethermind → reth.** Rationale: geth is the canonical EL baseline; core-geth is its ETC
  fork (document right after geth so ETC-specific diffs are clear); besu is the JVM structural mirror + PoA;
  then the perf/plugin/modularity variants.
- **Within a client**, document subsystems in dependency order (low→high, the taxonomy below). Concurrency
  **capped at ≤3 read-only doc agents at once** (they only read the vendored repo + write markdown — no
  builds, light load), or sequential if the machine is busy.
- **Never stack SR doc batches on a heavy maker `sbt` build.** The foreground sprint (B7.0-b, L1, Wave M)
  owns build priority; SR doc batches run in the gaps. "Parallel" = concurrent tracks over time, not
  simultaneous heavy load.
- **Read-only w.r.t. fukuii** — Phase 1–2 never edit fukuii source; they only write under
  `docs/research/clients/`.
- **Primary EVM client sources:** `.claude/repo-references/clients/{core-geth,besu,erigon,nethermind,reth}` +
  `reference-clients-evm/go-ethereum` (see `.claude/agents/REFERENCES.md`). Record the exact commit/branch
  documented in each file's header.
- **⚠ BRANCH DISCIPLINE (critical — operator 2026-07-13): research the `upstream` ref ONLY.** THREE repos
  carry a **fukuii-authored ETC/Olympia OVERLAY on their `main` branch** that must NEVER be attributed to the
  reference client: **core-geth** (`main` +65: ECIP-1111 treasury-ordering, ECIP-1122, audit docs),
  **besu** (`main` +47: `olympia-besu`, `feat(olympia)`, `fix(classic)`), **nethermind** (`main` +24:
  `EtcBlockTree`, `EtchashChainSpecEngineParameters`, ECIP-1111 FeeCollector — fukuii's ETC PoC). Always
  `git -C <repo> log upstream …` / read the `upstream` ref; NEVER `git log --all` or read/checkout
  `main`/`origin/main`; sanity-check any suspicious commit with `git merge-base --is-ancestor <sha> upstream`
  (fails → overlay-only, ignore for the reference finding). (erigon/reth/go-ethereum: `main`==`upstream`, clean.)
  **core-geth is INVERTED (operator 2026-07-13):** its `upstream` = the **DEPRECATED Sept-2024** branch (the
  last *independent* core-geth — sunset, but still the **frozen ETC byte-authority** for ECIP-1017/1099/1100/
  1041/1010). Its `main` = **fukuii's live ETC MODERNIZATION sprint** (Olympia ECIP-1111/1112/1121/1122,
  treasury-ordering, ECIP-1122) — fukuii's OWN forward work → belongs to the **fukuii audit (Phase 3-4)**, not
  reference findings. Reference research still reads `upstream`.
  **Known contamination to FIX:** the core-geth ORIENTATION docs were documented at overlaid `main`
  (`b28aa0a0`) — their **Olympia/ECIP-1111/1112/1121/1122** claims may be fukuii's modernization, not
  upstream(deprecated) core-geth. Re-verify vs `upstream`; attribute Olympia to fukuii's `main` where absent
  upstream. (besu/nethermind orientation docs used `upstream`, clean.)
  **ETC landscape:** core-geth deprecated (Sept 2024) + besu removed ETC (Feb 2026) → maintained ETC clients
  are sunsetting; **fukuii is the successor.** References frozen-but-rich: upstream(deprecated) core-geth
  (values) + besu-history 2019–2026 (JVM structure) + geth pre-merge (ancestral Ethash).
- **Secondary NON-EVM PoW client sources (for mining-protocol + PoW-specific angles only):**
  `/media/dev/2tb/dev/reference-clients-pow/{bitcoin,monero,zcash}` (+ monero-gui). NOT documented via the
  14-slot EVM taxonomy — consulted for **targeted PoW questions**: the **mining-protocol layer** (getWork /
  Stratum v1 / **Stratum v2**, `getblocktemplate`, external-miner & mining-pool integration — the
  *mining-pool/validator* use case), PoW peer-management (Bitcoin's canonical model), and PoW mempool/relay.
  fukuii's ETC PoW mining (internal Ethash + external-miner wiring) is the alignment target for that material.
- **GUI inspiration reference:** `/media/dev/2tb/dev/reference-clients-pow/monero-gui` — a cross-platform
  **Qt/QML** desktop client (main.qml / LeftPanel / MiddlePanel / `pages/` / `wizard/` + multi-OS installers &
  Dockerfiles) wrapping the daemon. The reference for fukuii's planned **GUI** (a fukuii deliverable, not a
  reference-client subsystem): node lifecycle + sync/peer status, **mining controls** (start/stop, threads,
  hashrate — pairs with internal-CPU-sealing + mining-pool use cases), onboarding wizard, i18n, packaging.
  Serves the *end-user / enterprise-operator* + *mining-pool* use cases. Studied as a UX/architecture pattern,
  not documented via the EVM taxonomy.

## Common subsystem taxonomy (~14 slots — the comparability keystone)
Every client is documented against THIS list, same slugs, so Phase-2 tables line up. Dependency-ordered
(also the within-client documentation order). **★ = priority-first (highest blast radius / most-cited).**

| # | Slug | Covers |
|---|------|--------|
| 1 | `build-deps` | build tool, module layout, dependency & version management |
| 2 | `primitives` | bytes/bytecode, RLP (or SSZ), crypto (hashing, ECDSA/BLS, KZG) |
| 3 ★ | `storage-persistence` | KV backend (RocksDB/MDBX/…), schema/column-families, pruning, flat storage, caching, iterator lifecycle |
| 4 | `state-trie` | MPT/state trie, account/storage model, witnesses/proofs, commitment scheme |
| 5 ★ | `consensus-engines` | engine abstraction (PoW/PoS/PoA), fork/hardfork dispatch, merge/transition handling, difficulty/finality |
| 6 | `evm` | interpreter, opcodes, gas schedule, precompiles, tracing hooks |
| 7 | `block-execution` | block validation, execution pipeline, receipts, rewards, system calls |
| 8 | `txpool` | mempool admission, ordering, replacement, eviction, gossip |
| 9 ★ | `sync` | full/fast/snap/checkpoint/staged sync, pivot selection, healing |
| 10 | `networking-p2p` | devp2p/RLPx, discovery v4/v5/ENR, wire protocols (eth/snap), peer mgmt |
| 11 | `rpc-api` | JSON-RPC, Engine API, WS/IPC, GraphQL, method coverage |
| 12 ★ | `multi-network` | network/family config & selection, testnet support, custom genesis, chain-spec model |
| 13 | `testing` | test structure, groupings/tiers, fixtures (ethereum/tests, hive), simulators, determinism |
| 14 | `node-lifecycle` | startup/shutdown, DI/plugins, config, wiring (metrics/logging pointer → slot 20) |

### Extended slots (15–20) — added 2026-07-13 (operator: "how can all clients have exactly 14?")
The original 14 are the execution-client **spine**; a module-list audit of all 6 clients found six more
first-class subsystems that don't map cleanly to any spine slot. Added as slots 15–20 so the gap is closed
before Phase 2. (Minor/util modules — console REPL, `ethclient` SDK, `event`, ABI, NAT/UPnP, source-analyzers,
`Nethermind.UI` = the GUI-as-fukuii-deliverable — remain folded, not slots.)

| # | Slug | Covers | Where it lives (examples) |
|---|------|--------|---------------------------|
| 15 | `block-production` | payload building, block assembly, sealing/mining, MEV/builder, tx-selection *production* side (vs block-execution = validate+apply) | geth `miner/` · reth `payload/` · nethermind `Mining`/`Flashbots`/`Shutter` · besu block-creation |
| 16 | `accounts-signer` | account mgmt, keystore, wallet, local + external signer (clef-style) | geth `accounts/`+`signer/` · nethermind `KeyStore`/`Wallet`/`ExternalSigner.Plugin` |
| 17 | `cl-engine` | consensus-layer integration, embedded beacon, engine-tree/fork-choice driver, Engine-API *driver* side (vs rpc transport) | erigon `cl` (Caplin) · geth `beacon/` · reth `engine/` · nethermind `Merge.Plugin`+`Ssz` |
| 18 | `exec-extensions` | execution extensions, indexing/notification hooks, trace-store, data-feeds, distinct gRPC-data transport | reth `exex/` · nethermind `TraceStore`/`StateDiffsWriter`/`BalRecorder`/`Grpc` |
| 19 | `historical-distribution` | era files, snapshot/segment distribution (torrent), freezer/ancient store, checkpoint distribution, EIP-4444 history-expiry | geth freezer · erigon `downloader` · reth `era*`/`static-file` · nethermind `Era1`/`History`/`Init.Snapshot` |
| 20 | `observability` | metrics (Prometheus), tracing (OTLP), health checks, diagnostics, dashboards, ethstats/telemetry | geth `metrics/`+`ethstats/` · erigon `diagnostics`+`dashboards` · reth `metrics`/`tracing-otlp` · nethermind `Monitoring`/`HealthChecks` |

_Forward-looking subsystems noted but NOT yet slotted (too few clients, or emerging): stateless-execution / ZK
proving (nethermind `Stateless.ZiskGuest`, reth sparse-trie witnesses) — revisit if a 21st slot is warranted._

## Use-case / node-role lens (operator 2026-07-13) — characterize approaches, DON'T just rank them
fukuii is an **omni-client**: it does NOT have to pick one approach. The design principle is **best-practice
as the DEFAULT + optional feature-enabling for other approaches per use case.** So the research must, for
every notable approach/pattern, record **what it's GOOD FOR** (which node-role / use case), not just whether
it "won." Clients diverge because they optimize for different users — that divergence is the signal, not noise.

**Use-case / node-role taxonomy** (tag approaches against these):
- **enterprise** — private/consortium infra, single-binary multi-network (fukuii's core goal), permissioning, stability.
- **CEX / custody** — high-security, high-reliability, prune-without-downtime, deterministic ops.
- **mining-pool / validator** — block production/sealing, low-latency, mempool/tip-of-branch focus.
- **light / end-user** — minimal resources, fast bootstrap, pruned state.
- **archival / data-serving + RPC** — full history, trace/debug, high-read throughput, static/frozen historical serving.
- **multi-network** — one binary, many families/networks concurrently.

**Three-way verdict per approach** (used in Phase-2 observations, NOT binary "best"):
- **DEFAULT** — the best-practice; fukuii's default.
- **OPTIONAL(use-case)** — not the default, but valuable for a specific role → a fukuii feature flag / mode. State which role and why.
- **OBSOLETE** — genuinely crappy/superseded → skip. Only after understanding *why* it existed.

Every `{subsystem}.md` "Notable patterns" + "Authority note" should note use-case fitness; the Phase-2 tables carry an explicit verdict column. The end goal: fukuii as the most complete/versatile omni-client, offering the right approach for each user, defaulting to the best.

## Per-client doc template (`{client}/{subsystem}.md`)
```markdown
# {client} — {subsystem}
_Commit/branch documented: {sha/branch}. Documented {date}._

## Architecture summary
How this client structures this subsystem (2–5 sentences).

## Key types / interfaces / files
- `path/into/vendored/repo:line` — what it is / does
(the load-bearing abstractions — the things another client would compare against)

## Design decisions & rationale
Why they built it this way (with citations). Trade-offs they made.

## Notable patterns (the reusable idea)
The pattern worth naming for the observations table.

## Authority note
Is this client authoritative for this concern? (per the authority model below). If not, who is, and where
does this client diverge / lag / lead?

## Gotchas / anti-patterns / things they later changed
Known bad shapes, deprecations, or things they got wrong then fixed (e.g. geth dropping standalone PoW).
```

## Observations schema (`observations/{subsystem}.md`)
```markdown
# Observations — {subsystem}
## Comparison table
| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | Authoritative |
|---|---|---|---|---|---|---|---|
| {dimension} | … | … | … | … | … | … | {client(s)} |

## Approach catalog (use-case-aware — the point of the whole exercise)
For each distinct approach found for this subsystem, one row:
| Approach | Clients using it | Good for (use-case/node-role) | Verdict: DEFAULT / OPTIONAL(role) / OBSOLETE | Why |
|---|---|---|---|---|
| {approach} | … | {enterprise/custody/validator/light/archival/multi-network} | {verdict} | {rationale — incl. why it exists / when to reach for it} |

## Best-practice synthesis
The DEFAULT fukuii should adopt per dimension + the OPTIONAL approaches worth offering as modes/flags for
specific use cases (with the role each serves). Not "one winner" — a default + an options menu.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)
Where fukuii likely aligns / diverges, and which optional approaches map to which fukuii use-case — seeds
for the Phase-3 snapshot, not verdicts.
```

## Authority model (which client is authoritative for which concern)
Alignment is **authority-aware, never uniform "align to geth"** — geth + reth dropped standalone PoW, so
aligning ETC code to them is a regression.
- **core-geth** — ETC / PoW / ETChash + ECIP-1017/1099/1111/1122 (the ONLY authority for ETC consensus).
- **go-ethereum** — canonical ETH / PoS baseline, the `consensus.Engine` interface, EIP reference behavior.
- **Besu** — multi-consensus / PoA (clique/ibft2/qbft), the JVM structural mirror closest to fukuii.
- **erigon** — sidechain (Bor/Polygon), staged sync, performance/DB (MDBX).
- **nethermind** — plugin architecture (self-declaring consensus/family plugins).
- **reth** — modularity / SDK (NodeTypes, compile-time chain families).
- **Scala 3 / Pekko Typed / Cats-Effect** best practice — fukuii-specific, not a client (cross-checked in
  Phase 4, not documented here).

## Chesterton's Fence (Phase 4 rule, stated here so it's not forgotten)
"Align to the RIGHT authority, OR justify the divergence (an ETC-specific requirement the others don't
implement), OR delete as tech debt" — git-history / why-does-this-exist checked before any `git rm`
(`dead-code-review.md`). Aggressive cleanup, but never deletes a real bug fix.

## Progress — Phase 1 (per-client documentation)
Status legend: ` ` = not started · `~` = in progress · `✓` = done. Update as cells complete.

| Client | build-deps | primitives | storage★ | state-trie | consensus★ | evm | block-exec | txpool | sync★ | net-p2p | rpc | multi-net★ | testing | node-life |
|--------|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| go-ethereum | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| core-geth | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| besu | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| erigon | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| nethermind | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| reth | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

### Progress — extended slots 15–20 (added 2026-07-13)
| Client | block-production | accounts-signer | cl-engine | exec-extensions | historical-distribution | observability |
|--------|:--:|:--:|:--:|:--:|:--:|:--:|
| go-ethereum | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| core-geth | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| besu | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| erigon | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| nethermind | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| reth | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

## Progress — Phase 2 (observations, per subsystem) — ✅ 20/20 COMPLETE (2026-07-13)
All `observations/{slug}.md` written + the two cross-cutting-theme capstones. Ready for Phase 3.

| Subsystem | Status | | Subsystem | Status |
|-----------|:--:|---|-----------|:--:|
| storage-persistence ★ | ✓ | | txpool | ✓ |
| consensus-engines ★ | ✓ | | networking-p2p | ✓ |
| sync ★ | ✓ | | rpc-api | ✓ |
| multi-network ★ | ✓ | | testing | ✓ |
| build-deps | ✓ | | node-lifecycle | ✓ |
| primitives | ✓ | | block-production (15) | ✓ |
| state-trie | ✓ | | accounts-signer (16) | ✓ |
| evm | ✓ | | cl-engine (17) | ✓ |
| block-execution | ✓ | | exec-extensions (18) | ✓ |
| | | | historical-distribution (19) | ✓ |
| | | | observability (20) | ✓ |

**Cross-cutting theme capstones:** `observations/cross-cutting-themes.md` — (1) CSP/JVM structure →
Pekko-Typed migration target; (2) the gRPC-seam = product-family + dRPC bridge.

_Partial credit already banked (fold into the relevant cells when Phase 1 formally documents them): the B7.0
engine-axis research already covers `consensus-engines` engine-selection for core-geth/besu/geth/erigon/
nethermind/reth (`.local/docs/research-july/b7.0-engine-axis-decision.md`) — migrate that into
`{client}/consensus-engines.md` + `observations/consensus-engines.md` rather than re-deriving._

**Cross-cutting Phase-2 theme to crystallize (operator 2026-07-13): "CSP/JVM structure → Pekko Typed migration
target."** No reference client uses actors (Go channels, C# async, Rust tokio, besu Vert.x/services), so there
is NO Classic→Typed *framework* to import — that mechanics lives in fukuii's own `loom` agent +
`pekko-typed-api.md`. But the clients inform the *target architecture* the Typed migration should adopt:
geth/erigon **channel-ownership** = the actor-granularity litmus (one goroutine's exclusive state → one Typed
actor); besu **ServiceManager constructor-injected services** = pass typed `ActorRef[Command]` at spawn, not
lookup; besu **Lifecycle FSM** = behavior-as-state-machine; besu **ProtocolSpec** = immutable per-fork bundle
actors reference (no mutable fork state in the actor); typed message DTOs = sealed Command ADT + replyTo. Also
sbt: besu versionless-submodule BOM → single version source (`project/Dependencies.scala`); erigon module
boundary at the process/gRPC hop → product-family seams. Effect systems (Cats Effect) are NOT in any reference
client → Typelevel ecosystem is the reference, not the EVM clients. Each `{client}` doc's "fukuii takeaway"
lines accumulate this; the comparison pass names it as a standalone observation.
