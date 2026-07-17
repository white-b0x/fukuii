# The plan-completeness framework — how we make the rebuild plan solid

This is the **map for polishing the plan**, not a layer plan. It exists because setup→L0 was built
before it was planned — producing a CRITICAL consensus bug (F-BN-1), a HIGH one (F-RLP-1), a JVM
overflow (J-RLP-1), and design calls made from memory (the plugin-api miss). The lesson, stated plainly:
**measure many times, cut once.** The first draft of `plan/L0–L10` is a skeleton built from a fraction
of the evidence the Systemic Review actually produced. This framework defines the full evidence set, the
bar each layer must clear, and the review waves that get us there — **no gaps, no on-the-fly logic.**

The plan is "solid" when every layer plan passes the §4 rubric against every §2 resource, setup and L0
are brought to that same bar, and the §6 cross-layer coherence pass is clean. We are not in a rush.

## Building principle (non-negotiable — the one that keeps getting lost)

Two rules govern this whole effort (memory `research-into-cohesive-plan-before-building`):

1. **Research → evidence-cited action plan → build.** We spent days researching (the whole §2 set). It
   is a **binding input, not background.** Every design decision in every layer plan traces to the
   observation/topic/best-practice/client that grounds it. We do **not** build before the plan is mapped,
   and we do **not** let the research evaporate at implementation time — that is precisely what produced
   the setup/L0 bugs. Measure many times, cut once.
2. **Plan the FULL system cohesively, bottom-to-top — top-level requirements propagate DOWN as
   structural constraints** (see the propagation matrix below). The low layers must be *designed* to carry
   the high-level frameworks; you cannot bolt them on afterward.

**The purpose of the requirements + ledger (operator): future-proof the foundation so it never needs
refactoring for a known future feature.** Every captured item is assessed for its **low-level /
structural implication**. Most high-level items (a new RPC method, a GUI, a dashboard, a product) do NOT
touch the foundation — note them, don't over-build; they're additive-later. The ones that DO impose a
low-level constraint (R7's L2 prune-barrier hook; MPC's base threshold-sig primitive at L0; multi-network's
neutral L0 value types; the storage-mode seam at L2) **must be built into the foundation now** — that is
the entire point of the downward-propagation matrix. The filter for every ledger/requirement item:
**"does this require a structural constraint the foundation must carry, or is it additive-later?"** — bake
in the former; consciously defer the latter. This is what makes the brain-dump a future-proofing exercise
rather than scope creep.

## 1. The failure this prevents

The draft leaned on **go-ethereum + core-geth + besu** and the `observations/` synthesis. It **under-used**:
- **nethermind / erigon / reth** — the SR studied all six *because each is the authority for a distinct
  concern* (below). Ignoring three of six discards half the empirical evidence.
- **`docs/research/best-practices/`** — the coding-design library (Scala/Pekko/Typelevel/evm-client
  patterns). The plan asserted "Scala 3 idiom" and "Pekko Typed" without grounding specific choices in it.
- **The reference repos themselves** (`scala3`, `pekko`, `typelevel`, …) — for how the idiom is actually
  written, not just named.
- **QUEUE.md** — the SR-NN per-subsystem alignment findings, Batch 7 (PoA), Batch 8 (modernization),
  Chase & Deferred, the Findings Resolution Log.
- **`topics/`** — the 8 deep-dive surveys (consensus-methods-catalog, mining-protocol, PoA/ETC testnets,
  wire-protocol-evolution, L2/rollup/sidechain, PoS testnets).

## The feature-completeness doctrine (two goals, both required)

fukuii targets **best-practice defaults AND the most feature-complete client** — the SR's
DEFAULT / OPTIONAL(role) / OBSOLETE taxonomy is built to serve both, and every gap-hunt applies it:

- **DEFAULT → adopt.** The SR's DEFAULT verdict per concern is fukuii's baseline (best practice).
- **OPTIONAL(role) → add as additive/optional for feature-completeness.** A pattern erigon / nethermind /
  reth (or any client) uses that is NOT the best default but is *genuinely worth having* is **added —
  feature-flagged / role-gated — not skipped.** Being feature-complete means shipping these with the seam
  in from the start. The hunt for **additive features** is a first-class objective, not a nice-to-have.
- **OBSOLETE / non-compliant → understand and consciously AVOID.** A decision the SR marks OBSOLETE or that
  is incompatible with fukuii's model (geth's `else-means-ethash`; a PoS-only assumption on a PoW-legal
  chain) is named and avoided — we understand *why* it's wrong so we never reintroduce it.

Two standing reinforcements the review must not lose:
- **JVM stack — besu is the implementation guide.** Read besu's Java alongside geth's Go on every layer;
  it shares fukuii's JVM constraints where Go doesn't (it caught F-BN-1, B-BLS-1, J-RLP-1).
- **Typed actors — Pekko 1.6+ / Scala 3.** The SR flagged fukuii's *existing* actor system as having
  **bottlenecks / poor design** (a known weakness — surface the specific findings). No reference client
  uses actors, so the Typed *target* comes from the SR's CSP→channel-ownership mapping
  (`cross-cutting-themes.md` Theme 1) + `best-practices/pekko/{typed-patterns,concurrency}` + the `pekko`
  reference repo (`actor-typed`). Study these thoroughly; be *thoughtful* in the actor design; do NOT
  1:1-port the weak Mantis/Classic structure.

## 2. The complete evidence set (nothing is exempt — check ALL of these per layer)

| Resource | What it gives | Location |
|---|---|---|
| **SR observations** (20) | per-subsystem cross-client comparison + DEFAULT/OPTIONAL(role)/OBSOLETE verdicts + named authority | `docs/research/clients/observations/{slot}.md` |
| **SR topics** (8) | deep-dives: consensus-methods-catalog, mining-protocol-{evm,nonevm}, consensus-{poa-and-etc-testnets, pow-cpu-dev, l2-rollup-sidechain}, pos-networks-and-testnets, wire-protocol-evolution | `docs/research/clients/topics/` |
| **SR per-client** (6×20) | each client's design per subsystem — the raw authority evidence | `docs/research/clients/{go-ethereum,core-geth,besu,erigon,nethermind,reth}/{slot}.md` |
| **fukuii `july-fourth`** (Phase 3) | what old fukuii does per subsystem — the "improve over old" source | `.local/docs/research/clients/fukuii/{slot}.md` |
| **cross-cutting-themes** | CSP/JVM→Pekko-Typed target; gRPC-seam = product-family+dRPC | `docs/research/clients/observations/cross-cutting-themes.md` |
| **best-practices — coding design** | `scala/type-safety`, `pekko/typed-patterns`+`concurrency`, `typelevel/patterns` | `docs/research/best-practices/{scala,pekko,typelevel}/` |
| **best-practices — evm-clients** | anti-patterns, fail-loud-invariants, mutable-state-parity, error-recovery, constant-time-comparison, p2p, peer-disconnect-blacklist, snap-sync, reference-client-crosscheck | `docs/research/best-practices/evm-clients/` |
| **best-practices — repo-patterns** | per-client build-release / repo-hygiene / agentic-tooling / dev-workflow (→ setup) | `docs/research/best-practices/evm-clients/repo-patterns/{client}/` |
| **reference repos — idiom** | how the idiom is actually written: `scala3`, `docs.scala-lang`, `scala2` (migration) | `.claude/repo-references/{scala3,docs.scala-lang,scala2}/` |
| **reference repos — Pekko** | `pekko/actor-typed`, `actor-testkit-typed`, `pekko-http`, `pekko-connectors`, `pekko-management` | `.claude/repo-references/pekko*/` |
| **reference repos — Typelevel** | `cats`, `cats-effect`, `fs2`, `log4cats` (the effect layer) | `.claude/repo-references/typelevel/` |
| **reference repos — libs** | `circe` (L9), `rocksdb` (L2), `bouncycastle` (L0), `scalafix`/`scapegoat` (setup lint), `hive` (testing), `spec-kit` | `.claude/repo-references/` |
| **reference repos — clients** | the six clients' source (cite alongside the SR per-client docs) | `.claude/repo-references/clients/` |
| **QUEUE.md — SR-NN** | Phase-3 per-subsystem alignment findings (the rebuild's per-layer gap list) | `.claude/sprints/QUEUE.md` + `queue/systemic-review.md` |
| **QUEUE.md — Batch 7** | Private Network Stack / multiple PoA — feeds L5 NetworkFamily + L10 multi-instance | `.claude/sprints/QUEUE.md` §Batch 7 |
| **QUEUE.md — Batch 8 / MOD** | modernization: MOD-13 Tcp, MOD-06 json4s→circe, MOD-14 Sangria→Caliban, dep floor | `.claude/sprints/QUEUE.md` §Batch 8 |
| **QUEUE.md — Chase & Deferred / Findings Log** | DRPC-GATEWAY-01, CL-RESEARCH-EMBED-01, resolved-finding routings | `.claude/sprints/QUEUE.md` (tail) |
| **Design goals** | mission: mining-pool GTM + enterprise single-binary multi-network; omni-client thesis | memory `fukuii-mission-strategic-context`, `mod19-modernization-waves` |

## 3. The per-concern authority map (from QUEUE.md — binding, richer than the draft used)

**Authority is per-concern; each client earns its authority somewhere.** Do not collapse to three.

| Concern | Primary authority | JVM-implementation guide | Also mine (SR value) |
|---|---|---|---|
| ETC/PoW/ETChash, ECIP-1017/1099/1111/1122 | **core-geth** (FROZEN) | **besu** (ETC history) | — |
| ETH/PoS, EIP behavior, Engine API | **go-ethereum + besu** | **besu** | nethermind, reth, erigon (cross-check) |
| Shared EVM/RLP/crypto/trie behavior | **go-ethereum + besu** (must agree) | **besu** | all six (divergence = investigate) |
| **Multi-consensus / PoA** (Clique/IBFT/QBFT) | **besu** | besu | core-geth (Clique *sealing*, besu stubbed it) |
| **Sidechain / performance** (Bor, flat-state, staged sync) | **erigon** | (Go — port the *idea*) | reth |
| **Plugin / extensibility architecture** | **nethermind** (`IConsensusPlugin`) | (C# — port the *idea*) | reth (`NodeTypes`) |
| **Modularity / SDK / type-safe registry** | **reth** (`NodeTypes`, `ForkCondition`) | (Rust — port the *idea*) | nethermind |
| JVM implementation *approach* (always) | — | **besu** (read its Java alongside geth's Go) | — |

**The rule for the non-JVM clients (erigon/nethermind/reth):** they are authorities for **design ideas**
(plugin registry, family packaging, modularity, flat-state, staged sync) — port the *shape*, realized in
Scala 3 / Pekko Typed; besu remains the JVM-implementation guide. Their Go/C#/Rust does not transfer, but
their *architecture* is empirical evidence the SR captured deliberately.

## 4. The per-layer completeness rubric (every `plan/L{n}.md` must pass ALL)

A layer plan is complete only when it can answer, from evidence:

1. **All 6 clients mined per-concern — including their optimizations.** Not just besu/geth/core-geth. For
   each concern in the layer, the §3 authority is cited AND the relevant erigon/nethermind/reth design
   idea + **performance/architecture optimization** (erigon staged-sync / flat-state / Domains / gRPC
   decomposition; reth parallel-state-root / ExEx / modularity-SDK; nethermind plugin-arch / parallel
   processing / pruning) is either adopted (with the SR verdict), scheduled OPTIONAL(role), or explicitly
   ruled out (why). Catalogued cross-layer in **`plan/optimizations.md`**. An optimization the SR
   documented that a layer neither adopts nor consciously defers is a gap. A layer that never mentions
   three of six clients is incomplete.
2. **SR verdicts honored** — every governing `observations/{slot}.md` DEFAULT/OPTIONAL(role)/OBSOLETE is
   quoted + cited; nothing invented; the relevant **topics/** deep-dive is mined.
3. **QUEUE.md items mapped** — the layer's `SR-NN` alignment finding, any Batch-7/8/MOD item, and any
   Chase/Deferred thread that lands here are pulled in and scheduled (not rediscovered later).
4. **Coding design grounded in the reference material** — the Scala-3 idiom and Pekko-Typed shapes cite
   `best-practices/{scala,pekko,typelevel}` and the actual `scala3`/`pekko`/`typelevel` reference repos —
   concrete patterns, not "use opaque types" as an assertion.
5. **fukuii `july-fourth` gap closed** — the `clients/fukuii/{slot}.md` gaps this layer fixes, each a floor.
6. **Deferrals scheduled with tests** — `planned-work-is-scope-floor`; nothing "optional/later" without a
   named layer + proving test.
7. **Design goals served** — where the layer advances the mission (mining-pool ergonomics, enterprise
   single-binary multi-network), it's stated.
8. **No gap, no on-the-fly logic** — every design decision traces to evidence; open questions are named as
   OPEN with a resolution owner, never left implicit.
9. **Consensus-critical flags + DoD** — byte-exact targets, reference vectors, the gate lenses.

## 5. Per-layer resource map (the inputs each enrichment wave pulls)

| Layer | SR-NN | topics | best-practices | non-JVM-client value to add |
|---|---|---|---|---|
| **setup** | — | repo-patterns/* | repo-patterns (build-release, hygiene, agentic-tooling); `scalafix`/`scapegoat` | reth/erigon/nethermind repo structure |
| **L0** | SR-10 | — | evm-clients/{constant-time-comparison, fail-loud-invariants}; scala/type-safety; `bouncycastle` | reth alloy primitives; nethermind value-types |
| **L1** | SR-07 | — | evm-clients/mutable-state-parity; scala/type-safety | reth typed-tx; nethermind tx model |
| **L2** | SR-05, SR-09 | — | evm-clients/snap-sync; typelevel/patterns; `rocksdb` | **erigon** flat-state/Domains; nethermind pruning; reth pathdb |
| **L3** | SR-06 | mining-protocol-evm | evm-clients/anti-patterns; scala/type-safety | reth EVM SDK modularity; nethermind |
| **L4** | SR-08 | — | evm-clients/{fail-loud, mutable-state-parity, error-recovery} | erigon exec staging; reth |
| **L5** | SR-04, SR-EXT-01/02 | consensus-methods-catalog, consensus-poa-and-etc-testnets, mining-protocol-{evm,nonevm}, consensus-l2-rollup-sidechain, pos-networks | (design) | **besu** PoA seams; **nethermind** plugin registry; **reth** NodeTypes/ForkCondition; **erigon** Bor packaging + Batch 7 |
| **L6** | SR-02 | wire-protocol-evolution | evm-clients/{p2p, peer-disconnect-blacklist}; pekko/{typed-patterns,concurrency} | erigon Sentry; reth net; nethermind |
| **L7** | SR-01 | — | evm-clients/snap-sync; pekko/concurrency | **erigon** staged sync; reth pipeline; nethermind |
| **L8** | SR-11 (keystore/metrics/txpool) | — | typelevel/patterns | reth txpool sub-pools; nethermind plugins |
| **L9** | SR-03 | — | typelevel/patterns; `circe` | reth ExEx; erigon StateChanges (grpc-seam); Batch 8 circe/Caliban |
| **L10** | SR-11 (nodebuilder/cli), SR-EXT-02 | — | pekko/{typed-patterns,concurrency}; besu ServiceManager DI | **nethermind** plugin lifecycle; reth NodeBuilder; Batch 7 multi-instance |
| **cross-cutting** | SR-12 (test infra) | — | ALL best-practices; `hive`; `scalamock` | reference-client-crosscheck methodology |

## 5b. Top-down requirements propagation (the plan is cohesive bottom-to-top — STRUCTURAL)

The §5 map is bottom-up (each layer's inputs). But the plan is not a stack of independent layers — the
**high-level architectural requirements impose design constraints that flow DOWN into every layer below,
and the low layers must be designed to carry them.** A layer plan is not complete until it satisfies the
downward constraints, not just its own slot's evidence. Derive these FIRST (Wave 1a), before per-layer
enrichment.

### R1 — Multi-network framework (the structural spine; the operator's example)

L5's `NetworkFamily` typeclass can only be a *thin* seam **because** L0–L4 are network-neutral. If any
lower layer bakes in a network assumption, L5 is forced into `isEtc()` branches and the framework breaks.

| Layer | Downward constraint R1 imposes |
|---|---|
| **L0** | Value types + crypto carry **no** network identity; ordering/canonical ops are network-agnostic. A baked-in assumption here poisons everything above. |
| **L1** | `domain` models **all** tx/header/receipt variants; per-network **admissibility** is a *separate validation gate*, not a modelling omission; `ChainId` is opaque data, not a type-level fork. |
| **L2** | **ONE datadir schema serves every family** — CF layout + keys network-neutral, **no `isEtc()` in storage keys**; genesis/chainspec is **data** loaded per network (besu/nethermind "name→resource"). *(the operator's structural example.)* |
| **L3** | Fork dispatch is the parameterized **`ForkActivation` seam** (block ⊥ timestamp ⊥ TTD; reth `ForkCondition` is the cited prior art); `Etc*`/`Eth*` opcode/fee objects never cross; the EVM is **family-blind** — runs whatever `ProtocolSpec` it's handed. |
| **L4** | Reward/finalize is a **per-family hook** selected by `ProtocolSpec` (ECIP-1017 vs withdrawals), never hardcoded; execution is family-blind. |
| **L5** | The `NetworkFamily` typeclass + positive `EngineId` keying + conditional merge — the framework itself, thin *because* L0–L4 are neutral. |
| **L6/L7** | ForkId/wire + sync strategies **parameterized per network** from the fork schedule (PoW head vs PoS pivot; per-network checkpoint). |
| **L10** | Instantiates N families through the typeclass — the convergence point. |

### The other cross-cutting requirements (same downward discipline)

| Requirement | Where it bites downward |
|---|---|
| **R2 — concurrent multi-instance, single-binary** (enterprise differentiator) | **No global singletons / mutable statics anywhere L0–L9** (a global metrics registry, static config, shared RocksDB handle) — they break per-instance isolation. L2 per-instance datadir; L8 **per-instance** metric registry + keystore/txpool; L9 per-instance RPC/Engine-API routing; **L10** the concurrent multi-`ChainInstance` runtime (N isolated guardian subtrees). R1+R2 converge at L10. |
| **R3 — family-neutrality** | No `isEtc()`/`is_optimism()` in shared readers/paths (L2 storage, L4 execution, L5 `consensus-api`, L6 network); inject knobs *through* the typeclass (erigon `FrozenBorBlocks` is the counter-example). |
| **R4 — consensus byte-exactness** | L0/L1/L3/L4/L5 byte-exact vs the §3 per-concern authority; validate against the verified reference map first, never current code. |
| **R5 — Pekko Typed + cats-effect discipline** | L6/L7/L10 actors (channel-ownership, sealed `Command` ADT, no `Behavior[Any]`); L2 `DataSource` returns `IO`/`fs2`; effect discipline throughout — no `unsafeRunSync` in actors. |
| **R6 — born-modern** | Scala 3 idiom + successor deps (circe/Caliban/Streams-Tcp) from line one, every layer; never re-introduce the old lib. |

**The completeness test for cohesion:** for each requirement above, can you point at the specific design
decision in each lower layer's plan that satisfies it? If a layer plan doesn't mention how it carries the
multi-network / multi-instance / family-neutrality constraint, it is not done — that's the on-the-fly gap
that bites at integration.

## 6. The wave sequence

Each wave is a discrete, reviewable pass. We do not advance a wave until the prior one is signed off.

- **Wave 1a — Top-down requirements derivation (read-only).** Establish/verify the §5b propagation
  matrix: for each cross-cutting requirement (multi-network, multi-instance, family-neutrality,
  byte-exactness, Typed/effect, born-modern), derive the concrete design constraint it imposes on each
  lower layer. Output: the per-layer downward-constraint list.
- **Wave 1b — Bottom-up resource-integration audit (read-only).** Per layer (incl. setup + L0), audit the
  draft against the §4 rubric + §5 resource map **and the Wave-1a downward constraints**: what evidence is
  cited, what's missing, where on-the-fly logic slipped in, whether each downward constraint is satisfied.
  Output: a per-layer gap-list (the punch-list driving Wave 2). No plan edits yet.
- **Wave 2 — Per-layer enrichment.** Layer by layer, close the Wave-1 gaps: mine all 6 clients per-concern,
  pull the SR-NN + topics + best-practices + QUEUE items, ground the coding design in the reference repos,
  schedule deferrals. Each layer re-passes the §4 rubric. (Includes rewriting `L0.md` and adding a real
  `setup.md` to standard — setup and L0 were done without this rigor.)
- **Wave 3 — Cross-layer coherence.** Seam-by-seam: do adjacent layers agree (L1 header ↔ L3/L4 validation;
  L2 storage ↔ L4 world-state; L4 ↔ L5 inversion; L5 NetworkFamily ↔ L10 wiring)? Is the DAG consistent
  end-to-end? Are deferrals placed at exactly one layer? Fix contradictions.
- **Wave 4 — Adversarial completeness pass.** A skeptic's read: what's still missing — a client not mined,
  a topic unread, a QUEUE item unmapped, an OPEN left implicit, a design goal unserved? What it finds
  feeds the next iteration, until a pass finds nothing (multi-pass §6b). *(Waves 3–4 ran; findings
  recorded in `coherence-pass-01.md` — 2 structural contradictions + the `chain`-module orphan + a
  half-fix + a type-ownership orphan, all resolved, re-verification clean.)*
- **Wave 5 — Per-item reference-client verification (the RX pass).** The **depth** pass that complements
  the subsystem **breadth** of Waves 1–4: every item the plan commits to (every `optimizations.md` row,
  per-layer seam, ledger F1–F13) is verified **item-by-item against the actual vendored reference-client
  source** — not the research abstraction. Each item gets an `RX-NN` evidence entry (byte-cited, all
  clients that have the topic) ending in `CONFIRMS` / `CORRECTS` / `SHARPENS`; `CORRECTS`/`SHARPENS` feed
  back into the plan (structural corrections re-pass Wave 3). This is the direct guard against building
  from an inaccurate abstraction (the F-BN-1/F-RLP-1 failure mode). Method + registry + tiers in
  [`rx/README.md`](rx/README.md); evidence in `rx/L{n}.md`.
- **Only then** — the plan is solid; the L0 code fixes already found (J-RLP-1 etc.) fold into the setup/L0
  bring-to-standard; and building L1 begins against a plan with **no surprises and no unverified items**.

## 6b. Multi-pass verification — no wave item is trusted on one pass

A wave item (a layer plan, a requirement, a seam's coherence) is **not complete when one pass finds
nothing** — it's complete when a pass finds nothing *after* the prior passes already found and fixed
everything they could. **Run at least three passes on every item.** The logic: if the third pass still
surfaces gaps, that is direct evidence more remain — fix them and run a fourth, iterating until a pass
comes back **clean**. Speed hides gaps (it hid F-BN-1, F-RLP-1, J-RLP-1, and the R7 requirement); the
passes are the proof we did not trade thoroughness for pace.

Every pass hunts the **full** gap taxonomy — a pass that checks one dimension is not a pass:
- **SR gaps** — an `observations/`/`topics/` verdict not honored, a slot not mined.
- **Best-practices gaps** — a `scala/`, `pekko/`, `typelevel/`, or `evm-clients/` pattern not applied.
- **Reference-client gaps** — a design idea from any of the **six** clients (esp. nethermind/erigon/reth)
  not adopted-or-ruled-out.
- **Future-feature / vision gaps** — a roadmap item the layer must be *designed to carry* but isn't
  (heterogeneous families, storage modes, product-family seams — `requirements.md`).
- **Multi-network / multi-consensus / heterogeneous-family gaps** — a family (alt-L1, L2 rollup,
  sidechain, PoA) the design can't accommodate.
- **Downward-constraint gaps** — a `requirements.md` matrix cell the layer doesn't satisfy.

Each pass records what it found (or "clean"); the item advances only on a **clean pass that follows at
least two that found-and-fixed.** A `READY FOR IMPLEMENTATION` marker requires this on every wave.

## 7. Definition of "solid" (the exit bar for the whole plan)

- Every `plan/L{n}.md` (setup + L0…L10 + cross-cutting) passes the §4 rubric — evidence-cited, all six
  clients mined per-concern, SR-NN + topics + best-practices + QUEUE mapped, coding design grounded in the
  reference repos, deferrals scheduled, no on-the-fly logic.
- The §6 cross-layer coherence and adversarial passes are clean.
- **Every registry item is RX-verified (Wave 5): each `optimizations.md` row / per-layer seam / ledger
  item has an `RX-NN` entry with a `CONFIRMS`/`CORRECTS`/`SHARPENS` verdict against the vendored
  reference-client source; every `CORRECTS` is applied; every `SHARPENS` detail folded in.** No item
  ships on the strength of the research abstraction alone.
- setup and L0 are brought to the same bar as L1–L10 (they were not).
- The plan reads as one cohesive, empirically-grounded architecture — the measure-twice foundation the
  setup→L0 arc skipped.
