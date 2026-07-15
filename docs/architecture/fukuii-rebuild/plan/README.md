# fukuii rebuild — the plan

The forward-looking roadmap for the from-scratch rebuild, **written before each layer is built**, not
after. This directory is the *prospective* plan; the sibling `../NN-*.md` docs are the *retrospective*
as-built records, and `../README.md` is the status index (commit-sha per layer). Plan here → build →
review-gate → record there. Keep the two separate: this directory says what we *intend* and *why*
(grounded in the Systemic Review); the records say what we *shipped*.

## Plan status: READY FOR IMPLEMENTATION (2026-07-14)

**All 13 plan docs — `setup` + `L0`…`L10` + `testing` (+ `cross-cutting`) — are READY FOR
IMPLEMENTATION.** They cleared the full multi-pass (`REVIEW.md` §6):

1. **Waves 1–2 — enrichment.** Requirements `R1–R11`, `optimizations.md`, `feature-ledger.md` `F1–F13`,
   and every per-layer doc brought to the rubric, dispositioned (DEFAULT/STRUCTURAL/OPTIONAL/OBSOLETE).
2. **Wave 3 — cross-layer coherence.** Six through-lines checked end-to-end; two structural
   contradictions (the R7 reorg-event ADT, the R11 auth/wire seam) + the `chain`-module orphan + 14
   fixes resolved. Record: [`coherence-pass-01.md`](coherence-pass-01.md).
3. **Wave 4 — adversarial.** Two passes. The first iterated the Wave-3 fixes to a clean state (the
   `ForkActivation` half-fix + the `MutationReason` orphan found and fixed). A second, deeper per-layer
   adversarial pass (Workstream B, [`coherence-pass-02.md`](coherence-pass-02.md)) confirmed the Wave-3
   resolutions hold in prose and surfaced what the six threads structurally missed — **four scoped
   subsystem reviews** (WB-R1 scalanet discovery · WB-R2 state-diff payload contract · WB-R3 reorg-import
   driver · WB-R4 L10 isolation/custody) plus a **systemic research-asset under-linkage** (WB-R5), fixed
   via the [`../research-index.md`](../research-index.md) asset→layer map (every layer + RX doc now links
   the research it should consult). A third pass — [`coherence-pass-03.md`](coherence-pass-03.md),
   research-utilization — then re-audited every layer against that newly-linked library: the two BUILT
   layers (L0/L1) verified sound, the plan layers surfaced additive findings the SR-observation-only passes
   missed (1 consensus-adjacent + 3 security/correctness + 1 custody HIGH, plus MED/LOW), each seeding the
   owning layer's build-time §10 register. [`coherence-pass-04.md`](coherence-pass-04.md) —
   session-delta wiring verification — confirmed the cross-layer invariants added during the working
   session (eth/69+ TD-sourcing + anti-spoof, per-network pivot-policy, bootstrap-source/BitTorrent, the L2
   `chain-weight` CF) are consistently wired at every layer they span. Finally,
   [`coherence-pass-05.md`](coherence-pass-05.md) — the **full-plan re-audit** — ran a deterministic
   cross-reference sweep (fixing ~20 broken research-grounding links + 1 dangling finding-ref) and the six
   through-lines over the whole plan: DAG down-only/acyclic ✓, R1–R11 + F1–F13 coverage ✓, all `§`-refs
   resolve ✓, ~13 residual gaps applied/scheduled (incl. one MESS-ownership contradiction the session
   itself introduced), and the as-built record docs reorganized into `implementation-reports/`.
4. **Wave 5 — RX per-item reference-client verification** ([`rx/README.md`](rx/README.md)). Every
   `optimizations.md` row + per-layer seam + ledger item was verified **item-by-item against the actual
   vendored reference-client source** (two independent ETC authorities — core-geth Go + besu-etc JVM —
   where they exist), answering the four questions (appropriate? build-worthy? understanding + blast
   radius? if not, the correct answer). **329 items · 13 CORRECTS · 79 SHARPENS · ~237 CONFIRMS · zero
   byte-divergences on any consensus primitive.** Every CORRECTS + SHARPENS is applied; evidence per
   layer in [`rx/`](rx/).

**Operator decisions resolved (2026-07-14):** the L0 `CryptoBackend` dual-backend seam is **built now**
(L0 bring-to-standard); the Scala-2026 security stack (Semgrep + `sbt-dependency-submission`→Dependabot
+ Scala Steward + Gitleaks + Trivy) is **approved**, sentinel-wired SHA-pinned; the two ECIP-1111 draft
clarifications are **amended** (forge, both spec copies).

**What "READY" does *not* mean:** the consensus-critical items each layer flags — Olympia EIP-set
reconciliation (ECIP-1121), the EIP-7825 gas cap, the ECIP-1017 canonical reward form + custom-network
divisibility, MESS's JVM-first scrutiny — are **build-gate items**, recorded in the plan and resolved at
build under the Consensus-Critical Change Protocol (forge/beacon/banksy co-sign), not plan blockers.

**Next:** [`migration-runbook.md`](migration-runbook.md) — the push-button move to a fresh
`fukuii-rebuild` branch off `upstream/staging`; then build layer-by-layer, **each layer gated on its
READY plan** (per-layer lifecycle below).

## Why this exists (the lesson from L0)

L0 was built first and reviewed after. The review gate then caught a **CRITICAL chain-split bug**
(F-BN-1, alt-bn128 G2 subgroup check missing) and a **HIGH network-partition bug** (F-RLP-1,
non-canonical RLP accepted) in code already committed as "done" — plus a design decision made from
memory (flagging "besu plugin-api" as a new gap when the SR had already designed the B7.0.5
`NetworkFamily` typeclass). That is expensive and backwards. The gate should **confirm a plan**, not
discover a crisis.

So the rebuild inverts the order for every remaining layer:

> **Plan the layer from the SR first → build to the plan → review-gate confirms the plan → record.**

The plan for a layer is not optional and not light. It names, *before a line is written*: the SR slots
that govern the layer, the per-concern reference-client authorities, the DEFAULT/OPTIONAL(role)/OBSOLETE
verdicts to honor, the besu structural mirror, the Scala 3 idiom targets, the known deferrals landing at
the layer, the exit definition-of-done, and the consensus-critical traps. If the plan can't answer those
from the SR, the answer is "go read the SR," not "start typing."

## Rule 0 — the SR is the binding first input (non-negotiable)

The Phase 1–4 Systemic Review (`docs/research/clients/`) exists to inform exactly these decisions.
Every layer plan **must** be grounded in it. Before proposing any design, resolving an OPEN, or making
an authority call: read the relevant `observations/{slot}.md` + its cross-refs and honor the verdicts
already reached. Never introduce a "new gap/finding" without first confirming the SR didn't already
answer it. (Canonical: memory `consult-sr-research-before-design`; `systemic-review-protocol.md`'s
"outputs are BINDING design inputs" clause.) The SR's 20 observation slots, each a cross-client
synthesis with named authorities and DEFAULT/OPTIONAL/OBSOLETE verdicts, are the substrate of this
whole plan:

| SR slot | Governs layer(s) |
|---|---|
| `primitives.md`, `build-deps.md` | L0 foundation |
| `accounts-signer.md` | L1 domain (signing/tx), L8 keystore |
| `state-trie.md`, `storage-persistence.md`, `historical-distribution.md` | L2 storage + trie |
| `evm.md` | L3 evm |
| `block-execution.md` | L4 execution |
| `consensus-engines.md`, `multi-network.md`, `block-production.md`, `cl-engine.md` | L5 consensus (+ network-family registry) |
| `networking-p2p.md` | L6 network |
| `sync.md` | L7 sync |
| `txpool.md`, `observability.md` | L8 peripheral services |
| `rpc-api.md`, `exec-extensions.md` | L9 rpc + product-family seam |
| `node-lifecycle.md` | L10 node composition root |
| `cross-cutting-themes.md`, `testing.md` | every layer (see `cross-cutting.md`) |

### Reference locations (so nobody hunts)

- **SR synthesis** (public, the binding input): `docs/research/clients/observations/{slot}.md`, per-client
  `docs/research/clients/{client}/{slot}.md`.
- **fukuii AS-IS snapshots** (what old fukuii does, per subsystem): **`.local/docs/research/clients/fukuii/{slot}.md`**
  — note the `.local/` prefix; these are the "improvements over old fukuii" source.
- **Vendored reference-client source**: `.claude/repo-references/clients/{besu,go-ethereum,core-geth,nethermind,erigon,reth}/`
  — **all six live under `clients/`** (go-ethereum is `clients/go-ethereum`, *not* `reference-clients-evm/…`).
- **The old code** (reference-only, the port source): branch `july-fourth`. On `july-mod-sprint` the old
  `src/main/scala/com/chipprbots/ethereum/…` tree is **gone** — only the clean-write `modules/` exists.

## The authority model (per-network, per-concern — not per-client)

fukuii is a **multi-network framework** — many networks across consensus families (PoW, PoS, PoA…),
each hosting one or more networks. ETC/Mordor and ETH/Sepolia are today's *instances*, not the ceiling
("not if/else"). Authority is chosen by *what the code is*, not by client. Canonical: memory
`reference-client-authority` + `systemic-review-protocol.md`.

| Concern | Authority | Notes |
|---|---|---|
| **Shared EVM/RLP/crypto behavior** (the core every network executes) | **go-ethereum + besu together** (both maintained; must agree — divergence = investigate) | geth is what the ecosystem tracks; besu is the independent maintained JVM cross-check |
| **JVM implementation approach** (how to build it in Scala/JVM) | **besu** — read its Java *alongside* geth's Go | besu shares our JVM constraints (BouncyCastle, JNI native libs, big-int towers, no native `uint256`); Go idioms often don't transfer. **This lens is mandatory from line one.** Already paid off (B-BLS-1 at L0). |
| **A network's frozen values + fork level** (ECIP-1017/1099/1100, ETC fork schedule) | **core-geth** (FROZEN/deprecated Sept-2024 — ONLY these) + besu ETC history | Not authoritative for shared behavior (staled ~5 majors). |
| **ETH-family-specific** (blob/KZG, withdrawals, EIP-4788, Osaka, timestamp forks) | **go-ethereum** (+ besu) | Not core-geth. |
| **Module structure / organization** | **besu** (JVM structural mirror) | ProtocolSpec, ServiceManager DI, Lifecycle FSM, class-per-fork-schedule. |
| **Family/network extensibility** | **nethermind** (runtime openness) + **reth** (type-safe registry) + **erigon** (family-ships-own-chainspecs) | Synthesized as the B7.0.5 Scala 3 `given` `NetworkFamily` typeclass. besu owns consensus *seam structure* + private-net origination, NOT the registry. |

## The idiom (native to us — no reference client uses it)

- **Scala 3**: opaque types, `given`/`using`, `extension`, enums, `derives` — never `implicit class` /
  `implicit def`. Litmus: *if it isn't Scala 3 idiom, don't write it that way.*
- **Pekko Typed** (from L6): sealed `Command` ADT + explicit `replyTo`, one actor owns its state,
  behavior-as-state-machine, constructor-injected `ActorRef[Command]`, never `Behavior[Any]` /
  `sender()`. Authority: `.agents/protocols/code-style/pekko-typed-api.md` (P1–P25 + TL1/TL2). No
  reference client uses actors — the JVM clients inform *structure*, the actor *idiom* is ours.

## The per-layer lifecycle (every layer follows this)

```
0. RESEARCH & REVIEW (start of layer — freshen the context window; operator directive) — before planning
          OR building a layer, re-read its plan/L{n}.md, its SR slots + reference-client sources, and its
          requirements + feature-ledger items. Context drifts across a long build; re-grounding on the
          layer's topics before touching it is mandatory — this is what prevents the from-memory mistakes.
1. PLAN   (plan/L{n}.md)   — SR-grounded, before building. Uses the template below.
2. BUILD  (forge/beacon/mithril/…) — to the plan; besu's Java alongside geth's Go; Scala 3 idiom.
3. DETAILED AUDIT (end of layer — before clearing to the next; multi-pass per REVIEW §6b) — ≥3 independent
          lenses, none the builder, confirming the plan AND the layer-specific review:
            a. correctness / byte-alignment vs the per-concern authority
            b. besu JVM-implementation lens (the L0-proven pass)
            c. Scala 3 idiom (mithril)
            d. test quality + coverage vs reference vectors (eye, runs the suites)
            e. requirements + feature-ledger check — every downward constraint (requirements.md) and every
               ledger item homed to this layer is satisfied (adopted / scheduled / consciously deferred)
            f. findings resolution — every finding surfaced (in planning OR build) is RESOLVED in-layer
               (preferred) or ROUTED to the dependent layer's plan §7 with a home + a test; NONE left as a
               bare deferred note. The layer is NOT cleared until its findings register is empty
               (finding-resolution.md — found AND scheduled, never mentioned-and-forgotten).
          Multi-pass: advance only on a clean pass following ≥2 found-and-fixed. Every finding fixed to
          GREEN before L{n+1}. Consensus-critical → the forge/beacon protocol.
4. RECORD (../NN-*.md) — as-built: design + empirical logic + improvements-over-old + Layer boundaries.
          Build-status ONLY in ../README.md index. No status in durable docs (docs-future-proof rule).
5. ALIGN AGENTS (agentic tooling alignment — operator: "so they're always current and helpful") — reorient
          the tooling to this layer's built state so the agents that build the NEXT layer aren't working
          blind: (a) the layer's specialist charter(s) (`.claude/agents/*.md`) — dead `src/…` paths → real
          `modules/<name>/`, the standing rebuild context, what's now built + what the plan says is next;
          (b) the protocols (`.agents/protocols/`) + skills (`.agents/skills/`) that touch this layer;
          (c) AGENTS.md's module list / Key Directories + the module's `modules/<name>/AGENTS.md` breadcrumb.
          Same commit as the RECORD. A layer isn't done until its agents understand the current file tree +
          the plan. warden owns the mechanics; assess-what's-needed is per-layer (not every charter every time).
```

**Agentic alignment (per-layer — step 5; the L0 miss, do not repeat it).** We had an agent structure that
worked; the rebuild makes its `src/…` references dead pointers that mislead every agent (they load
`AGENTS.md`/`CLAUDE.md`/the `.claude/agents/*.md` charters first). **Setup does the initial full
reconciliation** (bring every charter onto the rebuild — the standing context: `modules/`, the plan, the
authority model); **then each layer's step 5 keeps its slice current** — the specialist charter for that
layer, the touched protocols/skills, the module breadcrumb. The goal (operator): agents are *always current
and helpful* through the build — effective for the next layer, not working from a stale tree. A layer is
not "done" until its agents understand the current file tree + the plan. (Distinct from the broader
`TOOLING-AUDIT-01` agentic-polish track, which is warden's and runs in parallel.)

**Per-item reference-client verification — the RX pass (Wave 5; READY gate; operator).** Before any
layer is built, every item the plan commits to is verified **item-by-item against the actual vendored
reference-client source** — the *depth* pass that complements the SR's subsystem *breadth*. Where the
sweeps found items by scanning whole subsystems (many concerns at once, where a specific mechanism can
be misread), RX comes back with surgical focus on each planned item and checks it against every client
that has the topic, byte-cited — so a plan decision is grounded in verified source, not in the research
abstraction. This directly guards the recurring failure of building from an inaccurate abstraction
([[research-into-cohesive-plan-before-building]]). The method, the item registry (every
`optimizations.md` row + per-layer seam + ledger item), the risk tiers, the all-clients/ETC-caveat
rules, and the `CONFIRMS`/`CORRECTS`/`SHARPENS` verdict taxonomy live in
[`rx/README.md`](rx/README.md); per-layer evidence in `rx/L{n}.md` (`RX-NN` series). **A `plan/L{n}.md`
is not `READY FOR IMPLEMENTATION` until every one of its registry items has an RX entry with a verdict,
every `CORRECTS` is applied back into the plan (structural corrections re-pass Wave-3), and every
`SHARPENS` detail is folded in.** RX makes step-0 "re-read its reference-client sources" a rigorous,
documented, per-item gate rather than a re-read of the abstraction.

**Findings resolution (per-layer — no find-mention-forget; operator).** Every layer surfaces findings /
follow-ups (especially during build). The governing rule is
[`finding-resolution.md`](../../../../.agents/protocols/process/finding-resolution.md): each finding is **found
AND scheduled**, never mentioned-and-forgotten. **Prefer resolving it in the same layer it's found.** If it
genuinely depends on another layer, **route it to that layer's plan §7 ("deferrals landing here, with
tests")** so it is worked *there* — not left floating in the finding-layer. Live findings (surfaced during
build) track in the sprint tracker / a per-layer implementation log (live state — not the durable plan doc,
per docs-future-proof); the *scheduled work* lands durably in the target layer's §7 + its §10 register. A
layer is **not cleared** (DETAILED-AUDIT lens f) until every finding is resolved-in-layer or routed-with-a-home.

## Per-layer plan template (what every `plan/L{n}.md` must contain)

1. **Scope & modules** — what the layer builds; sbt module(s); `com.chipprbots.fukuii.*` package;
   down-only dependencies (the DAG edge).
2. **SR slots & verdicts** — which `observations/{slot}.md` govern it, and the specific
   DEFAULT / OPTIONAL(role) / OBSOLETE verdicts to honor (quoted, cited).
3. **Per-concern authorities** — for each concern in the layer: the byte-value authority, the besu
   JVM-implementation reference (path), the structural mirror, the extensibility authority. With paths.
4. **besu structural mirror** — the concrete besu module/class shapes to follow (read alongside geth).
5. **Scala 3 idiom targets** — the specific opaque-type/given/enum/derives (and Typed, L6+) shapes.
6. **Improvements over old fukuii** — the AS-IS gaps (`clients/fukuii/{slot}.md`) this layer closes.
7. **Deferrals landing here** — items scheduled to this layer (planned-work-is-scope-floor), with the
   tests that prove them. A floor, not "optional."
8. **Exit DoD (GREEN bar)** — what "done" means; which gate lenses; which test tiers; the reference
   vectors that must pass.
9. **Risks & consensus-critical flags** — what needs forge/beacon, byte-exactness targets, known traps
   (e.g. the fork-named-opcode-for-the-wrong-network trap, the `else-means-ETC` fallthrough).
10. **Findings & follow-ups (register — no find-mention-forget)** — findings surfaced at, or routed TO, this
    layer, each with a disposition: **resolved in-layer** (preferred) / **scheduled at §7 with a test** /
    **routed to L{m}'s §7** (with the cross-layer dependency named). This is the register the DETAILED-AUDIT
    lens (f) must clear before the layer advances (`finding-resolution.md`). Nothing is ever "noted and
    deferred" without a home.
11. **Agentic alignment** — which specialist charter(s) / protocols / skills this layer's step-5 updates, so
    the agents are current for the next layer.

## The journey — L0 → L10

The DAG (every edge points down; an upward edge is a compile error — the structural fix for the old
13-package cycle). Detailed plan per layer in the linked docs; the full target with rationale is
`.local/docs/phase4/target-architecture.md`.

| Layer | Modules | Delivers | Plan | Consensus-critical? |
|---|---|---|---|---|
| **L0** | `bytes` `common` `crypto` `rlp` | value types, hashing/signing, RLP, KZG/BLS | [`L0.md`](L0.md) | **yes** (crypto, RLP) |
| **L1** | `domain` | Account/Block/Header/Tx/Receipt, opaque IDs | [`L1.md`](L1.md) | **yes** (tx signing, RLP layout) |
| **L2** | `storage` `trie` | DataSource/RocksDB, MPT, state/history schema | [`L2.md`](L2.md) | **yes** (state root) |
| **L3** | `evm` | opcodes, gas, precompiles, fork dispatch | [`L3.md`](L3.md) | **yes** (gas, state) |
| **L4** | `execution` | block exec, receipts, rewards (ECIP-1017) | [`L4.md`](L4.md) | **yes** (rewards, state) |
| **L5** | `consensus` (`-api`/`-pow`/`-pos`) | engine dispatch, Ethash/ETChash, Engine API, MESS, block production, NetworkFamily | [`L5.md`](L5.md) | **yes** (all of it) |
| **L6** | `network` | devp2p/RLPx/discovery/wire eth68-71/ForkId | [`L6.md`](L6.md) | no (formatting → herald) |
| **L7** | `chain` `sync` | `Blockchain`/`BlockchainReader` facade over storage; fast/regular/snap/checkpoint sync | [`L7.md`](L7.md) | no |
| **L8** | `txpool` `keystore` `observability` | admission, V3 keystore, metrics | [`L8.md`](L8.md) | banksy (admission policy) |
| **L9** | `rpc` `grpc-seam` | JSON-RPC/WS/IPC/GraphQL, Engine-API transport, ExEx | [`L9.md`](L9.md) | no (transport → conduit) |
| **L10** | `node` `cli` | Typed guardian tree, Lifecycle FSM, multi-ChainInstance runtime | [`L10.md`](L10.md) | no (composition) |
| — | cross-cutting | testing, observability, actor model, NetworkFamily, deferral ledger | [`cross-cutting.md`](cross-cutting.md) | — |

## Risk register (the things that bit us or will)

- **Consensus byte-exactness** — L0/L1/L3/L4/L5 are one-way doors. Every value validated against the
  verified reference map (built from the per-concern authority *first*, then diff), never against
  current code. State-root litmus decides forge/beacon ownership (`consensus-change-protocol.md`).
- **The JVM-implementation gap** — reading only geth's Go misses JVM-backend bugs (B-BLS-1: we picked
  besu-native's legacy eth_pairings backend, not the gnark one). besu's Java is read every layer.
- **Fork-name-for-the-wrong-network** — `Eth*`/`Etc*` opcode/fee-schedule objects must never cross
  (the old unprefixed-`OlympiaOpCodes`-was-actually-Cancun trap). `scala3-style.md` ratchet enforces it.
- **`else-means-ETC` / `else-means-ethash` fallthrough** — engine selection must be positive-keyed
  (`EngineId.fromMarkers`), never a binary default. The dormant marker layer gets shipped at L5.
- **Built-but-unshipped** — the dominant old-fukuii failure (a mechanism scaffolded, cutover never
  finished). The DoD bar is GREEN *and wired*, never "exists but dormant."
- **Doc staleness** — build-status in a durable doc goes stale within a commit. Status lives only in
  the `../README.md` index; plan/record docs carry design + boundaries.

## Status

Live build-status is the `../README.md` index (commit-sha per layer). As of this plan: L0 built,
in final gate (remediation GREEN, four besu-lens fixes + re-validation in flight). L1+ planned here,
not yet built.
