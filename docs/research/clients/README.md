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
- **Phase 1b — deep full review** → every subsystem (all 14 slots) per client, traversed via **each client's own dependency graph** (low→high). `{client}/{subsystem}.md`.
- **Phase 1c — second-wave deep questions** (woven into 1b) → esp. **historical PoW/ETC** (git-log each client's pre-merge Ethash/ETC structure = 4-5 PoW references beyond core-geth) + **ETH68-71 wire commit-log evolution** + deeper ★-subsystem follow-ups. May expose a **third wave** of targeted questions — that's expected.
- **Phase 2 — cross-client observations/comparison** → `observations/{subsystem}.md` (durable, public). ONLY after the deep review: align → best-practice; differ → *why* (language/runtime, performance, legacy-vs-modernized).
- **Phase 3 — fukuii snapshot** → `.local/docs/…` (fukuii evolves; snapshot goes stale).
- **Phase 4 — alignment audit → modernization backlog** → `.local/docs/…` → new QUEUE items.

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
- Vendored client sources: `.claude/repo-references/clients/{core-geth,besu,erigon,nethermind,reth}` +
  `reference-clients-evm/go-ethereum` (see `.claude/agents/REFERENCES.md`). Record the exact commit/branch
  documented in each file's header.

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
| 14 | `node-lifecycle` | startup/shutdown, DI/plugins, config, metrics/logging/observability |

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

## Best-practice synthesis
Per dimension: what the evidence says the right pattern is, and which client is the authority for it.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)
Where fukuii likely aligns / diverges — a seed for the Phase-3 snapshot, not a verdict.
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
| go-ethereum |  |  | ✓ |  | ✓ |  |  |  | ✓ |  |  | ✓ |  |  |
| core-geth |  |  | ✓ |  | ✓ |  |  |  | ✓ |  |  | ✓ |  |  |
| besu |  |  | ✓ |  | ✓ |  |  |  | ✓ | ✓ |  | ✓ |  |  |
| erigon |  |  | ✓ |  | ✓ |  |  |  | ✓ |  |  | ✓ |  |  |
| nethermind |  |  | ✓ |  | ✓ |  |  |  | ✓ |  |  | ✓ |  |  |
| reth |  |  | ✓ |  | ✓ |  |  |  | ✓ |  |  | ✓ |  |  |

## Progress — Phase 2 (observations, per subsystem)
| Subsystem | Status |
|-----------|--------|
| storage-persistence ★ |  |
| consensus-engines ★ |  |
| sync ★ |  |
| multi-network ★ |  |
| _(remaining 10 after the priority-4)_ |  |

_Partial credit already banked (fold into the relevant cells when Phase 1 formally documents them): the B7.0
engine-axis research already covers `consensus-engines` engine-selection for core-geth/besu/geth/erigon/
nethermind/reth (`.local/docs/research-july/b7.0-engine-axis-decision.md`) — migrate that into
`{client}/consensus-engines.md` + `observations/consensus-engines.md` rather than re-deriving._
