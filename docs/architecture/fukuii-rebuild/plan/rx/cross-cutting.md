# RX — cross-cutting thread-level verification (Wave 5)

_The **thread** slice of the RX depth pass. The per-layer `rx/L{n}.md` docs verify each layer's own
slice of these cross-cutting mechanisms (L0–L5 exist today; L6–L10 pending). This doc verifies the
**thread as a whole** — the runtime shape, the end-to-end mechanism, and the cross-cutting
`optimizations.md` rows not owned by any single layer. Where a thread is fully covered by per-layer
entries, the entry says so and does not duplicate._

**Registry verified:** `plan/cross-cutting.md` (§1–§8), `plan/optimizations.md` "Cross-cutting —
testing / build / observability" table, and the R1–R11 thread-level claims in `plan/requirements.md`.
**Grouped by thread:** R2-runtime · R7-through-line · NetworkFamily-contract · storage-completeness ·
security-seam · observability · MCP/agentic · cross-cutting-optimizations.

**Headline finding (the R2 premise question, answered up front):** fukuii's **single-binary,
concurrent multi-`ChainInstance`, cross-family** runtime is **genuinely novel — no reference client
runs it in production.** besu, nethermind, go-ethereum, erigon, and reth are all
**one-chain-per-process** in production. The *mechanism* (per-instance controller + per-instance
services) is proven **viable on the JVM** by besu's `ThreadBesuNodeRunner` acceptance-cluster harness —
but that is (a) test-only, (b) same-network/same-curve nodes, and (c) forced to run `NoOpMetricsSystem`
because besu's real metrics use a JVM-global registry. So the *viability* is besu-proven; the
*production, cross-family, fully-isolated* runtime is fukuii-original. This grounds R2 and every thread
that converges on it (observability per-instance registry, per-instance auth). Detail in RX-XC-01/-02/-08.

---

## Thread: R2-runtime (the `FukuiiRuntime` / `ChainInstance` shape)

### RX-XC-01 · Single-binary, concurrent multi-`ChainInstance`, cross-family runtime is viable-but-novel · Tier A (STRUCTURAL spine, R1+R2) · owner-layer L10 (thread spans L0–L10)
- **Plan claim / disposition:** `requirements.md` R2 + `cross-cutting.md` §5 + `optimizations.md` L10
  ("B7.0.5 `given NetworkFamily` registry wired", "Full R2 leak elimination") — L10 is "the concurrent
  multi-`ChainInstance` runtime: N isolated Typed guardian subtrees, hard isolation, each family wired
  via the R1 typeclass. R1+R2 converge here — the differentiator." Propagated invariant: **no global
  singletons / mutable statics anywhere L0–L9.**
- **fukuii `july-fourth`:** Classic-rooted single-node process; global statics (`ShutdownHookBuilder`,
  `ioRuntime` diamond, direct `Config.` reads) — the R2 leaks the plan enumerates. One network per run.
- **Reference source (byte-cited):**
  - besu `acceptance-tests/dsl/.../node/ThreadBesuNodeRunner.java:119` — `Map<String, Runner> besuRunners`
    keyed by node name; `:160/:173` a per-node `BesuControllerBuilder` + `BesuController` built from a
    Dagger component **per node**; `:145` a per-node `MetricsSystem`. **This proves in-JVM multi-node is
    viable on the JVM** — the mechanism fukuii needs. But `:669` `provideObservableMetricsSystem()` returns
    `new NoOpMetricsSystem()` for *every* node — the harness disables real metrics (see RX-XC-08), and all
    nodes are the *same* network/curve.
  - besu `Runner.java` / production `BesuCommand` — the shipped binary constructs **one** `BesuController` +
    **one** `Runner`. Production besu is one-chain-per-process.
  - besu `crypto/.../SignatureAlgorithmFactory.java:33` — `private static SignatureAlgorithm instance` (a
    JVM-global mutable static, with `resetInstance()`). The exact R2 anti-pattern: two instances needing
    different curves collide. besu tolerates it only because its multi-node harness never varies the curve.
  - nethermind `Nethermind.Runner/Ethereum/EthereumRunner.cs:15` — `EthereumRunner(INethermindApi api, …)`:
    **one `INethermindApi` per runner** = one chain per process.
  - go-ethereum / erigon / reth — all one-chain-per-process (`node.Node` / `erigon/node` / reth
    `NodeBuilder` each build a single chain's stack).
- **Q1 appropriate decision?** Yes. STRUCTURAL + the no-global-statics invariant is the only correct
  disposition for a premise no client ships: the isolation must be designed from line one because
  retrofitting instance-scoping into a static-riddled stack is a rewrite (besu's `SignatureAlgorithmFactory`
  is the cautionary case — a single static that would have to be threaded through every call site).
- **Q2 what we should implement?** Yes — and there is no "simpler alternative" to fall back to, precisely
  *because* it is unprecedented: the per-layer per-instance discipline (per-inst DB / no-global-EVM-state /
  per-inst registry / per-inst routing) **is** the mechanism. YAGNI does not apply — R2 is the enterprise
  differentiator (the mission GTM), not speculative.
- **Q3 understanding + blast radius correct?** Mechanism correct (besu proves per-instance controller +
  services is JVM-viable). **Blast-radius understatement (the finding):** the plan treats R2 as a set of
  per-layer constraints but does **not state that the whole premise is unprecedented** — that *every*
  reference client is one-chain-per-process and besu's only multi-node scenario is a test harness that
  disables metrics and fixes the curve. This matters: it means (a) there is no reference client to
  byte-check the *runtime shape* against — the per-layer per-instance greps are the *only* verification,
  so they are load-bearing, not belt-and-suspenders; and (b) the `SignatureAlgorithmFactory`-class of
  global static is not a besu "mistake" to avoid but the *predictable_ residue of one-chain-per-process
  design — fukuii will re-grow it at every layer unless the grep DoD is enforced per layer.
- **Q4 correct answer + why:** State the novelty explicitly in `cross-cutting.md` §5 / R2: single-binary
  cross-family multi-instance is **not implemented by any reference client**; besu `ThreadBesuNodeRunner`
  is the JVM-viability proof (per-node controller/services) *and* the boundary case (test-only,
  same-curve, NoOp metrics); the no-global-statics grep is therefore the primary verification at every
  layer, and each of L6–L10's own rx pass must carry the `object … { var … }` / shared-registry grep as a
  hard DoD (L0/L3/L4/L5 rx already do; L6–L9 must).
- **Verdict:** SHARPENS.
- **Plan edit:** `cross-cutting.md` §5 (or R2 in `requirements.md`) — add one paragraph: the R2 runtime
  shape is unprecedented in the reference set (all one-chain-per-process); besu `ThreadBesuNodeRunner`
  proves JVM-viability but only test-only/same-curve/NoOp-metrics; therefore the per-layer
  no-global-statics grep is the primary (not secondary) verification and is a mandatory DoD line in every
  L6–L10 rx pass.

### RX-XC-02 · No-global-singleton invariant end-to-end (L0–L9) · Tier A (STRUCTURAL R2) · owner-layer all
- **Plan claim / disposition:** `requirements.md` R2 "all" row — grep for `object … { var … }` / global
  singletons; each is an isolation break to justify or remove.
- **Coverage note (no duplication):** the *per-layer* verification is already done and coherent —
  RX-L0-15 (per-instance curve/backend), RX-L3-13/RX-L3 jump-table (`final`-field immutable table is the
  R2 model; nethermind `??=` mutation + geth package `var` are the anti-patterns), RX-L4-15/RX-L4
  (`@volatile` simulation flag → per-call `SimulationOptions`), RX-L5-04/-10/-11 (immutable-shared
  registry; per-`ChainInstance` payload/invalid caches), RX-L2-07/-10/-14 (per-instance DB handle,
  per-instance `liveIterGauge`). Each names the touched item and the grep DoD.
- **Q1/Q2:** Appropriate + right — confirmed per layer.
- **Q3 blast radius:** Correct and consistent across the verified layers. The one thread-level gap is
  purely that **L6–L9 have no rx pass yet**, so their instances of the invariant (per-instance peer
  tables, per-instance txpool/keystore, per-instance RPC routing, the L8 metric registry) are asserted in
  the plan but not yet RX-verified. Flagged, not a defect.
- **Verdict:** CONFIRMS (thread coherent; L6–L9 per-layer rx must carry the grep — tracked in RX-XC-01).

---

## Thread: R7-through-line (reorg / prune-barrier / gRPC, end-to-end)

### RX-XC-03 · The L4→L5→L9→(L2) reorg/prune through-line is DAG-coherent and byte-grounded · Tier A (STRUCTURAL spine, R7) · owner-layer L5 (ADT) + L9 (transport/manager) + L2 (prune hook)
- **Plan claim / disposition:** `optimizations.md` L4/L5/L9 rows + `cross-cutting.md` §5-adjacent + R7
  matrix — L4 emits per-block `BlockExecutionOutcome`; L5 `consensus-api` defines the `ChainNotification`
  ADT **once** (reth 3-case), fed by L4, aggregated by L5's reorg authority; L9 `grpc-seam` **imports**
  the ADT (down-dep), transports cross-process, owns the manager (buffer, per-consumer cap-1,
  wake-after-drain, `FinishedHeight`, WAL, `ExExHead`); L2 exposes `prune(safeHeight)` gated on
  `min(consumer heights)`.
- **fukuii `july-fourth`:** no first-class exec-extension framework — no reorg-aware `Committed/Reorged/Reverted`,
  no `FinishedHeight` gate, no WAL, no gRPC seam.
- **Reference source (byte-cited):**
  - reth `crates/exex/types/src/notification.rs:10` — `enum ExExNotification { ChainCommitted{new},
    ChainReorged{old,new}, ChainReverted{old} }` — the exact 3-case shape L5's `ChainNotification` adopts.
    `:57–59` `reverse()` (Committed↔Reverted, Reorged swaps old/new) — the reorg-inversion the WAL/resume
    logic relies on.
  - reth `crates/exex/exex/src/manager.rs:544–548` — `finished_height = try_fold(u64::MAX, |curr, exex|
    exex.finished_height.min(curr))` then `finished_height.send(Height(…))`: the **min-across-all-consumers**
    that is `min(consumer heights)`. `:396–408` — WAL `finalize(lowest_finished_height)` **only when
    `exex_finished_heights.iter().all(is_canonical)`** (never finalize mid-reorg). `:353/:537–540` —
    `is_ready.send(capacity>0)` backpressure + "if the buffer was full and we made space … `wake_by_ref()`"
    (the wake-after-drain deadlock guard).
  - reth `crates/exex/exex/src/context.rs:122–130` — `send_finished_height` (the consumer publishes its
    safe-to-prune height). `types/src/finished_height.rs:16` — "all blocks `<= finished_height` are safe to
    prune."
  - erigon `node/interfaces/remote/kv.proto` (per L9.md §"reference source") — `StateChanges` push
    (`Direction FORWARD|UNWIND`) co-located on the same `KV` service as remote-KV pull → "the pull path is
    the reconcile path."
- **Q1 appropriate decision?** Yes — STRUCTURAL(R7) with the ADT at L5 (reorg authority), transport at L9,
  prune-hook at L2 is the correct ownership split; reth's ExEx is the right framework model (besu's
  `BesuEvents` callbacks lack backpressure/reorg-safety/WAL — correctly not the authority here).
- **Q2 what we should implement?** Yes. The multi-client read confirms no better alternative: erigon gives
  the *transport* (gRPC `StateChanges`), reth gives the *framework* (reorg-safe notifications + barrier +
  WAL); the plan composes them exactly. Not YAGNI — R7 is the dRPC-Provider + product-family enabler.
- **Q3 understanding + blast radius correct?** Mechanism correct and byte-matched to reth. **DAG coherence
  confirmed:** L4→L5→L9 are all up→down; L2's `prune(safeHeight)` takes the safe height as an **injected
  parameter** (RX-L2-18), so the aggregator calling *down* into L2 is DAG-clean (no upward edge). The
  per-layer entries (RX-L4-15, RX-L5-35/-36, RX-L2-18) each verify their slice and agree with each other —
  the through-line is internally consistent.
- **Q4:** n/a (CONFIRMS).
- **Verdict:** CONFIRMS. forge/beacon aware (reorg = fork-choice authority, but the ADT/transport is
  non-state-root plumbing; the reorg *decision* stays in L5's forge/beacon-gated branch-import driver).

### RX-XC-04 · The prune-barrier aggregator must be the single registration point for EVERY consumer (in-proc + remote) · Tier A (STRUCTURAL, R7 blast-radius) · owner-layer L9 grpc-seam (manager) / L10 (wiring)
- **Plan claim / disposition:** L9.md §"grpc-seam" — "the manager holds the real monotonic-ID buffer …
  retains a notification only until **all** consumers have seen it (`retain(id >= min_id)`)"; "the node
  gates irreversible cleanup (state/WAL prune) on `min(consumer heights)`." The manager is physically
  homed in the **L9 `grpc-seam`** module.
- **Reference source (byte-cited):**
  - reth `manager.rs:280–306` — the `ExExManager` is constructed with **one `handles` vec of ALL ExEx
    handles** (`num_exexs = handles.len()`); the min-height fold (`:544`) is over *that* complete set. reth's
    manager is **node-scoped and transport-agnostic** — it registers every ExEx, whether it will be consumed
    in-proc or (hypothetically) remotely. There is exactly one manager and every prune-blocking consumer is
    in it.
  - reth `crates/node/builder/src/exex.rs` — ExEx registration happens at node-build time (L10-equivalent),
    above any single transport.
- **Q1/Q2 appropriate + right?** The *mechanism* (per-consumer cap-1 + reorderable buffer + min-fold) is
  right (byte-matched). The **placement** is the question.
- **Q3 understanding + blast radius correct? (the finding):** Under-stated. Homing the manager in the L9
  `grpc-seam` module is fine **only if every prune-blocking consumer routes through that one manager.** But
  R7/R9 explicitly contemplate **in-proc consumers** (erigon `direct.*` in-proc shim; an embedded CL/EL
  driver; a local indexer; pool-software) that attach *without* the gRPC transport. If such a consumer
  registers with a *different* (or no) manager, then L9's `min(consumer heights)` is computed over an
  **incomplete consumer set** → the node prunes state/WAL that a silent in-proc consumer still needs →
  silent data loss on that consumer's next reorg/backfill. reth avoids this by having exactly **one**
  node-scoped manager that every consumer — in-proc or remote — registers with.
- **Q4 correct answer + why:** The prune-barrier aggregation is **node-scoped, not transport-scoped.** State
  the invariant: there is exactly **one** `min(consumer heights)` aggregator per `ChainInstance`, and
  **every** prune-blocking consumer (in-proc via the `direct.*` shim OR remote via gRPC) MUST register a
  `FinishedHeight` publisher with it; L2's `prune(safeHeight)` is driven only by that single aggregator.
  If the manager stays in `grpc-seam`, the in-proc `direct.*` shim must register through the *same* manager
  (which is consistent with R7's "same pattern, in-proc shim vs remote gRPC is a one-function topology
  choice at composition" — L10). Because it is `min`, a *missing* consumer is worse than a slow one (a slow
  consumer holds the barrier back — safe; a missing one lets the barrier advance past data it needs —
  unsafe): "fail-safe = hold the barrier."
- **Verdict:** SHARPENS.
- **Plan edit:** L9.md §grpc-seam (and L10.md wiring) — add the invariant that the prune-barrier aggregator
  is node-scoped and is the **single registration point** for every prune-blocking consumer (in-proc shim +
  remote gRPC alike); a consumer that fails to register must **hold** the barrier (fail-safe), never be
  silently omitted from the `min`.

---

## Thread: NetworkFamily-contract (the registry-openness typeclass)

### RX-XC-05 · fukuii's `NetworkFamily` is deliberately THINNER than reth's `NodeTypes` — storage/primitives stay network-neutral · Tier A (STRUCTURAL R1/R3) · owner-layer L5 (def) + L10 (wiring)
- **Plan claim / disposition:** `cross-cutting.md` §5 + `optimizations.md` L5 — a Scala 3 `given`-based
  `NetworkFamily` typeclass registry: reth compile-time type-safety + nethermind runtime selection, *minus*
  reth's one-binary-per-family and nethermind's reflection; inject a family's knobs THROUGH the typeclass,
  never into shared readers.
- **Coverage note (no duplication):** RX-L5-03 verifies the *seam depth* (rollup/alt-BFT sizing) and its
  L1/L3 blast radius (new tx-type `DepositTx 0x7E` → `enum Transaction` extensibility). RX-L5-04 verifies
  the *runtime-openness* (nethermind self-declaration idea, not reflection; the conscious loss of drop-a-DLL
  extensibility). This entry adds the **thread-level boundary those two do not state.**
- **Reference source (byte-cited):**
  - reth `crates/node/types/src/lib.rs:27–33` — `trait NodeTypes { type Primitives: NodePrimitives; type
    ChainSpec; type Storage; … }`. reth's family type **carries `Storage` and `Primitives` as associated
    types** — the family *parameterizes the storage backend and the block/primitive types.* This works for
    reth because each concrete node binary monomorphizes to **one** family (compile-time, one-binary).
  - nethermind `IConsensusPlugin.cs` / `PluginLoader.cs:60` — `AssemblyLoadContext.Default.LoadFromAssemblyPath`
    (runtime reflection DLL loading) — the runtime-openness the plan adopts *as an idea only*.
- **Q1 appropriate decision?** Yes — the `given`-typeclass synthesis is right. But the **contract shape**
  needs a boundary reth's model does not respect.
- **Q2 what we should implement?** A typeclass that carries **fork schedule / consensus engine / chainspec
  selection / reward-finalize hook / wire-capability set** — and **NOT** `Storage`/`Primitives`. Including
  storage/primitive types in the family (reth's `NodeTypes` shape) would push family-typing **down into
  L0–L2**, which is exactly the "network assumption in a lower layer forces `isEtc()` upward" that R1/R3
  forbid (`requirements.md` R1: "L0–L4 are network-neutral"; R3: no `is<family>()` in shared readers).
- **Q3 understanding + blast radius correct? (the finding):** RX-L5-03 cites reth `NodeTypes`' associated
  types (`Primitives, ChainSpec, StateCommitment, Storage, Payload`) and notes the one-binary rejection —
  but does **not** state that *including `Storage`/`Primitives` in the family type is itself an R1 violation*
  fukuii must avoid. This is the load-bearing distinction: fukuii wants reth's compile-time *type-safety*
  without reth's *family-parameterized storage/primitives*, because R2 (multi-instance, N families in one
  binary over one neutral storage schema — RX-L2-07 "one datadir schema serves every family") requires the
  storage/primitive layers be family-blind. So fukuii's `NetworkFamily` is a **thinner** trait than reth's
  `NodeTypes` by design, and reth's `Storage`/`Primitives` associated types are a **counter-model** (what
  NOT to fold in), the same way erigon's `FrozenBorBlocks`-in-`ChainHeaderReader` is (RX-L5-30).
- **Q4 correct answer + why:** Add to the `NetworkFamily` contract DoD: the trait carries fork/engine/
  chainspec/reward/wire knobs but **excludes** storage-backend and block-primitive types (those are L0–L2
  neutral, selected by the R8 `StorageProfile` per *role×network*, not by *family*). reth `NodeTypes`'
  `type Storage`/`type Primitives` is the named counter-model — folding them into the family would recreate
  the R1 leak at the type level. This keeps the seam thin *because* L0–L4 are neutral (the memory
  `research-into-cohesive-plan-before-building` "top reqs propagate down as structural constraints").
- **Verdict:** SHARPENS.
- **Plan edit:** `cross-cutting.md` §5 (and L5.md §5 NetworkFamily field list) — add the exclusion boundary:
  `NetworkFamily` carries fork/engine/chainspec/reward/wire; it MUST NOT parameterize storage backend or
  block-primitive types (reth `NodeTypes.{Storage,Primitives}` is the counter-model — folding them in is an
  R1/R3 violation at the type level). Storage selection is R8 `StorageProfile` (role×network), orthogonal to
  family.

---

## Thread: storage-completeness (R8 multiple-backends)

### RX-XC-06 · R8 multi-approach storage thread is coherent end-to-end; fully covered by per-layer rx · Tier B · owner-layer L2 (seam) + L7/L9/L10 (mode selection)
- **Plan claim / disposition:** `requirements.md` R8 + `optimizations.md` L2 — a `StorageProfile`
  role×network selector composing {keying(hash **and** path) × pruning × flat × freezer × expiry × backend}
  from line one; `INodeStorage` scheme-indirection; SNAP-serving role-gated; per-instance mode selection.
- **Coverage note (no duplication):** the L2 seam is exhaustively verified — RX-L2-07 (`StorageProfile`
  6-axis selector), RX-L2-08 (`INodeStorage` hash+path dual-read), RX-L2-09 (flat-accelerator on live read),
  RX-L2-18 (safe-height prune, R7 compose), RX-L2-20 (`SchemaMarker` records the active profile),
  RX-L2-21/-22 (freezer + TD retention + ERA1), RX-L2-24 (SNAP-serving primitive). The upper-layer mode
  selection (L7 sync-mode-per-role, L9 serve-mode-per-role, L10 per-instance mode) is asserted in the plan
  but pending its own L7/L9/L10 rx passes.
- **Reference source (thread-level cross-check):** besu `DataStorageFormat` (Forest **and** Bonsai behind
  one interface) is the single-client proof that multi-backend-behind-one-seam is real and JVM-idiomatic
  (verified in RX-L2-07). geth (hashdb/pathdb/snapshot/freezer), nethermind (Hash/HalfPath/`INodeStorage`),
  reth (pathdb/nibble-path/static-files), erigon (flat Domains) together prove the *menu* the plan draws
  from — no single client offers all, which is exactly why R8 is "default + multiple-additive," not
  "adopt one client's store."
- **Q1/Q2 appropriate + right?** Yes — the multi-approach-from-line-one disposition is correct (retrofitting
  a 2nd backend behind a hash-only design is a rewrite; besu proves the seam belongs at the interface).
- **Q3 understanding + blast radius correct?** Yes at L2; the R8×R2 interaction ("different instances in one
  binary may run different storage modes" — R8 L10 row) is coherent with R2 (per-instance profile resolved
  at open, RX-L2-07). The R8×R7 interaction (prune-barrier composes with `StorageProfile` pruning) is
  coherent with RX-XC-03/-04. No thread-level gap; the per-layer L7/L9/L10 rx must verify the mode-per-role
  claims when they run.
- **Verdict:** CONFIRMS (thread coherent; occupancy detail deferred to L7/L9/L10 rx per the plan's own
  seam-now/occupancy-later split).

---

## Thread: security-seam (the unified `Principal`/`Capability` auth gate, R11)

### RX-XC-07 · The one auth/identity/capability seam is structurally precedented (besu) except MCP write-ops (novel) · Tier A (STRUCTURAL R11) · owner-layer L9 (gate) + L0/L8 (crypto/keystore) + L10 (per-instance)
- **Plan claim / disposition:** `requirements.md` R11 + `cross-cutting.md` — three otherwise-separate
  features (MCP-2026 OAuth 2.1 + write-ops, Engine-API JWT, external-signer custody) **converge on one
  auth/identity/capability seam** at the serving boundary, per-instance under R2; L0 exposes
  `constantTimeEquals` the JWT/MAC/ECIES paths call.
- **fukuii `july-fourth`:** keystore MAC uses plain `==` (timing finding, RX-L0-15-adjacent); Engine-API JWT exists
  but no unified per-request `Principal`/`Capability` gate; MCP at 7.2% coverage, no write-ops/auth.
- **Reference source (byte-cited):**
  - besu `ethereum/api/.../jsonrpc/authentication/AuthenticationService.java:34` — `boolean isPermitted(…)`;
    `DefaultAuthenticationService.java:208` — `.put("permissions", user.principal().getValue("permissions"))`;
    `TomlAuth.java:110–121` — per-user `permissions`/`groups`/`roles`. **besu HAS a `Principal` +
    per-method-permission model for JSON-RPC** — the JVM structural mirror for fukuii's `Principal`/`Capability`
    gate.
  - besu `authentication/EngineAuthService.java` + `JWTAuthOptionsFactory.java` + `JwtAlgorithm.java` —
    Engine-API JWT auth. go-ethereum `node/jwt_auth.go` + `node/jwt_handler.go` — the same. **Engine-API JWT
    is precedented in two clients.**
  - erigon `rpc/mcp/mcp.go:46` `NewErigonMCPServer(ethAPI, erigonAPI, otsAPI, …)` + `:33` `ServeSSE` — the
    F1 MCP authority — registers **read-only `eth_*` tools only** (`:83–245`: blockNumber, getBlock,
    getBalance, call, estimateGas, …), **no write tools, no auth gate.** (See RX-XC-09.)
- **Q1 appropriate decision?** Yes — STRUCTURAL(R11), designed-once-not-per-transport, is correct: besu
  proves the per-method-permission + JWT machinery is JVM-real; the *convergence onto one seam* is the right
  factoring (three transports, one gate).
- **Q2 what we should implement?** Yes — a per-request `Principal` + `Capability` gate + audit log threaded
  through the RPC dispatch registry, per-instance. The multi-client read confirms the *pieces* are
  precedented (besu per-method permissions + Engine-JWT; geth Engine-JWT), so the risk is not "does this
  work" but "unify them without three bespoke auth paths."
- **Q3 understanding + blast radius correct? (the finding):** Under-stated on precedent asymmetry. The plan
  presents the three-features-one-seam as uniformly novel synthesis, but the evidence splits it:
  **(a) Engine-API JWT and per-method RPC permissions are precedented** (besu `authentication/`, geth
  `jwt_*`) — the plan should cite besu `authentication/` as the JVM structural mirror for the gate (it does
  not currently, though it should — this is the closest reference model). **(b) MCP write-ops behind
  OAuth 2.1 is genuinely novel** — erigon's MCP (the F1 authority) is read-only with no auth, so the
  MCP-write-auth slice has **no reference precedent** (same novelty class as R2). **(c) The unification into
  one `Principal`/`Capability` gate** serving all three is fukuii synthesis (besu keeps separate auth
  services). Blast radius on L0: `constantTimeEquals` is the shared primitive all three call (JWT verify,
  keystore MAC, ECIES tag) — verified STRUCTURAL at RX-L0 (constant-time equals primitive).
- **Q4 correct answer + why:** Cite besu `ethereum/api/.../authentication/` (`isPermitted` + `Principal` +
  per-method `permissions` + `EngineAuthService` JWT) as the **JVM structural mirror** for the gate — the
  Engine-JWT + RPC-permission half is *precedented*, port besu's shape. Flag the **MCP write-ops auth** as
  the one **unprecedented** slice (erigon MCP is read-only-no-auth) that fukuii designs against the MCP-2026
  OAuth 2.1 spec, not a client. The unified single-gate factoring is fukuii's own.
- **Verdict:** SHARPENS.
- **Plan edit:** `cross-cutting.md` / R11 — (1) add besu `ethereum/api/.../jsonrpc/authentication/`
  (`isPermitted`/`Principal`/`permissions` + `EngineAuthService` JWT) as the JVM structural mirror for the
  `Principal`/`Capability` gate; (2) note the precedent split: Engine-JWT + per-method RPC auth are
  precedented (besu + geth), MCP write-ops auth is unprecedented (erigon MCP read-only) and designed to the
  MCP-2026 spec, the single-seam unification is fukuii's synthesis.

---

## Thread: observability (F2/F3/F6 — per-instance registry, dashboards)

### RX-XC-08 · besu is the structural mirror for the metrics *shape* but the ANTI-PATTERN for R2 per-instance isolation · Tier A (STRUCTURAL R2/R10) · owner-layer L8 (thread cross-cutting)
- **Plan claim / disposition:** `cross-cutting.md` §6 + `optimizations.md` L8 — consolidate the metrics
  stack (Kamon+kanela dropped); **per-instance metric registry** (not a global static) so the
  multi-`ChainInstance` runtime reports per-network; "**besu `metrics/` is the structural mirror**"; 15
  shipped Grafana dashboards versioned to per-instance metric names; Kamon → OBSOLETE.
- **fukuii `july-fourth`:** shared static registry → byte-identical `/metrics` across instances (Bug-29).
- **Reference source (byte-cited):**
  - besu `metrics/core/.../MetricsSystemFactory.java:52` — `create(MetricsConfiguration)` returns a
    per-call `ObservableMetricsSystem` (the *interface* shape — the correct structural mirror for the
    `MetricsSystem` abstraction, categories, `ObservableMetricsSystem`/`NoOpMetricsSystem` split).
  - besu `metrics/core/.../prometheus/PrometheusMetricsSystem.java:66` — `private final PrometheusRegistry
    registry = PrometheusRegistry.defaultRegistry;` — **the real metrics system binds to the JVM-global
    `defaultRegistry`.** So two besu metrics systems in one JVM would collide (duplicate registration).
  - besu `ThreadBesuNodeRunner.java:669` — `provideObservableMetricsSystem()` returns
    `new NoOpMetricsSystem()`. **besu's own in-JVM multi-node harness disables real metrics** — the direct
    proof that besu has *not* solved per-instance metric isolation; it works around it by using NoOp.
- **Q1 appropriate decision?** Yes — per-instance registry is the only correct disposition for R2, and
  dropping Kamon (also an sbt-2 blocker) is right.
- **Q2 what we should implement?** A per-instance metric registry (each `ChainInstance` owns its own
  registry, exported with an instance label/port), Micrometer/Prometheus. Confirmed correct — and the
  multi-client read shows there is **no client to copy the isolation from** (besu, the mirror, doesn't do
  it), so the per-instance registry is fukuii-original in exactly the way RX-XC-01 predicts.
- **Q3 understanding + blast radius correct? (the finding):** The `cross-cutting.md` §6 phrase "besu
  `metrics/` is the structural mirror" is **true for the abstraction shape but misleading for R2**: besu is
  the mirror for the `MetricsSystem` *interface/categories*, but its `PrometheusMetricsSystem` uses the
  JVM-global `defaultRegistry` and its only multi-node scenario disables metrics (NoOp) — so besu is
  simultaneously the **anti-pattern** for per-instance isolation. The plan already names "the old Kamon
  global was the anti-pattern," but should also name **besu's own `defaultRegistry`** as the anti-pattern
  fukuii must not mirror. Blast radius: this couples the observability thread to R2 (RX-XC-01) — the
  per-instance registry *is* the observability instance of the no-global-statics invariant; and to R10's
  "dashboards versioned to per-instance metric names" (a dashboard with a hard-coded global metric name
  breaks the moment metrics carry an instance label).
- **Q4 correct answer + why:** Sharpen §6: besu `metrics/` is the structural mirror for the **`MetricsSystem`
  abstraction only**; besu's `PrometheusMetricsSystem.defaultRegistry` (JVM-global) + its
  `ThreadBesuNodeRunner` NoOp workaround are the **R2 anti-pattern** fukuii must not copy — the per-instance
  registry is unprecedented (no client isolates metrics per-instance because none runs multi-instance), so
  the shipped dashboards must be versioned against **instance-labelled** metric names, not the global names
  besu emits.
- **Verdict:** SHARPENS.
- **Plan edit:** `cross-cutting.md` §6 — qualify "besu `metrics/` is the structural mirror" to "for the
  `MetricsSystem` abstraction only"; add besu `PrometheusMetricsSystem.defaultRegistry` + the
  `ThreadBesuNodeRunner` NoOp workaround as the named R2 anti-pattern; state the per-instance registry is
  unprecedented and dashboards must key on instance-labelled metric names.

---

## Thread: MCP / agentic (F1 — native MCP/A2A/ACP)

### RX-XC-09 · erigon MCP proves the read-only transport half only; write-ops + auth + real-actor-state are fukuii-original · Tier B (DEFAULT adopt+build, STRUCTURAL auth) · owner-layer L9 agentic-interface
- **Plan claim / disposition:** `optimizations.md` L9 — Native MCP/A2A/ACP: erigon is the node-level MCP
  authority; embedded-SSE + standalone stdio/SSE, schema'd tools, real actor-state, read-only-default;
  additive-L9 **except the auth/capability gate (R11-structural)**; fukuii's MCP is 7.2% coverage today.
- **Reference source (byte-cited):**
  - erigon `rpc/mcp/mcp.go:37–66` — `ErigonMCPServer` wraps existing `EthAPI`/`ErigonAPI`/`OtterscanAPI`
    JSON-RPC APIs; `NewMCPServer` + `registerTools()`/`registerPrompts()`/`registerResources()`; `:33`
    `ServeSSE(addr)`. `:83–245` — the registered tools are a **1:1 read-only mirror of `eth_*`**
    (`eth_blockNumber`, `eth_getBlockByNumber`, `eth_getBalance`, `eth_call`, `eth_estimateGas`,
    `eth_getProof`, …) — **no write/mutation tools, no auth.** 1277 lines, all read-path.
- **Q1 appropriate decision?** Yes — DEFAULT adopt (MCP-over-JSON-RPC, SSE) + STRUCTURAL auth is correct;
  erigon is the right authority for the *transport + tool-schema* pattern.
- **Q2 what we should implement?** The read-only tool surface + SSE/stdio transport (adopt erigon's shape) —
  plus the fukuii-additive parts: write-ops (mining/peer/config control), real actor-state exposure, and
  the R11 auth gate. The read here confirms erigon covers *only* the read-only-transport half.
- **Q3 understanding + blast radius correct? (the finding):** Slightly understated. erigon MCP is a **thin
  read-only wrapper over the RPC APIs** — it does *not* expose "real actor-state" beyond what `eth_*`
  already returns, has **no write-ops**, and **no auth**. So the plan's "real actor-state, write-ops behind
  the auth gate" is **fukuii-original**, not adopted from erigon — the same novelty class as R2/observability
  (erigon proves the transport pattern; the agentic *control surface* + auth is unprecedented). Blast radius:
  the write-ops path binds to the RX-XC-07 auth seam (MCP write is the *unprecedented* slice there), and
  "real actor-state" binds to R2 (per-instance — an MCP server must target one `ChainInstance`, RX-XC-01).
- **Q4 correct answer + why:** Note in the L9 disposition that erigon MCP proves **only** the read-only
  transport + tool-schema pattern (byte: `mcp.go` all `eth_*` read tools, no write, no auth); fukuii's
  write-ops + real-actor-state + auth-gated control surface are original (design to the MCP-2026 spec +
  RX-XC-07 auth seam, not to erigon), and each MCP server instance is per-`ChainInstance` (R2).
- **Verdict:** SHARPENS.
- **Plan edit:** `optimizations.md` L9 MCP row / L9.md agentic-interface — clarify erigon proves the
  read-only-transport half only; write-ops, real-actor-state, and auth are fukuii-original (MCP-2026 spec +
  the RX-XC-07 auth seam), per-`ChainInstance` under R2.

---

## Thread: cross-cutting optimizations (testing / build / observability rows)

### RX-XC-10 · The cross-cutting testing/build/observability rows are grounded; besu acceptance-cluster is the R2 test vehicle but must add per-instance-metrics assertions · Tier B/C · owner-layer cross-cutting (setup + L10 + testing)
- **Plan claim / disposition:** `optimizations.md` "Cross-cutting" table — assert-fails-with-reason (erigon
  `Fails`/reth `expect_exception`), assert-nonzero-fixture-count, fork-in-test-name (besu), **besu
  acceptance-cluster DSL = the L10 multi-instance test vehicle**, Olympia fixture generation (core-geth
  `mgen`), auto-doc `update-book-cli` + `git diff --exit-code` (reth + geth `check_generate`), single
  version source + checksummed supply-chain gate (besu BOM + `verification-metadata`).
- **Reference source (byte-cited):**
  - besu `acceptance-tests/dsl/.../ThreadBesuNodeRunner.java` (verified RX-XC-01) — the in-JVM multi-node
    cluster DSL; boots N nodes with per-node `BesuController`. **Confirms** the row "besu acceptance-cluster
    DSL = the L10 multi-instance test vehicle." **Caveat (byte):** `:669` it uses `NoOpMetricsSystem` — so
    besu's cluster DSL, as-is, **cannot assert per-instance metric isolation** (the exact R2 property fukuii
    must test). fukuii's port must ADD that assertion.
  - besu `gradle/verification-metadata.xml` (exists) + `build.gradle` — **confirms** the checksummed
    supply-chain gate row (besu `verification-metadata`); note the row's own caveat "no sbt-native
    equivalent → needs design" stands (this is a Gradle feature; sbt has no direct analog).
  - reth `.github/workflows/{lint.yml,fetch-grafana-dashboard.yml}` — **confirms** the auto-doc/dashboard
    regenerate-and-verify pattern exists (reth ships dashboard-fetch + lint-gen workflows); the specific
    `git diff --exit-code` regenerate gate is reth/geth CI convention (grounded at the workflow level).
  - core-geth `mgen` (cited in plan; ETC fixture generation) — the frozen `etclabscore/tests` stops at
    Mystique, so Olympia needs generated vectors — consistent with the DRAFT-spec status of Olympia
    (RX-L5-21/-24).
- **Q1/Q2 appropriate + right?** Yes — each row's disposition (DEFAULT for the ratchets, STRUCTURAL for the
  supply-chain gate) is correct and evidence-grounded.
- **Q3 understanding + blast radius correct? (the one finding):** The "besu acceptance-cluster DSL = the L10
  multi-instance test vehicle" row is right but **incomplete**: besu's DSL boots multi-node with **NoOp
  metrics**, so a straight port would boot N instances yet be unable to verify the headline R2 property
  (per-instance metric isolation, per-instance auth, no cross-instance state bleed). fukuii's acceptance
  cluster must **add** assertions besu's cannot make — precisely because besu doesn't run production
  multi-instance (RX-XC-01/-08). The other rows (verification-metadata, auto-doc, mgen) are byte-confirmed
  with no blast-radius surprise.
- **Q4 correct answer + why:** Note on the acceptance-cluster row: adopt besu's `ThreadBesuNodeRunner`
  cluster *structure*, but the fukuii vehicle must additionally assert **per-instance metric-registry
  isolation, per-instance auth, and no cross-`ChainInstance` mutable-state bleed** — the R2 properties besu's
  own DSL cannot test (it uses NoOp metrics + same-curve nodes). This is the test-side of RX-XC-01/-08.
- **Verdict:** CONFIRMS (rows grounded) with a SHARPEN on the acceptance-cluster row.
- **Plan edit:** `optimizations.md` cross-cutting row "besu acceptance-cluster DSL" — add: the fukuii port
  must extend besu's cluster with per-instance metric-isolation + per-instance-auth + no-cross-instance-bleed
  assertions (besu's DSL runs NoOp metrics + same-curve nodes and cannot test these R2 properties).

---

## Rollup — thread-level verdicts

| ID | Thread | Verdict | One-line |
|---|---|---|---|
| RX-XC-01 | R2-runtime | SHARPENS | Single-binary cross-family multi-instance is unprecedented; besu `ThreadBesuNodeRunner` = JVM-viability proof (test-only, NoOp metrics); no-global-statics grep is primary verification |
| RX-XC-02 | R2 invariant end-to-end | CONFIRMS | Per-layer L0–L5 coherent; L6–L9 grep pending their own rx |
| RX-XC-03 | R7 through-line | CONFIRMS | L4→L5→L9 DAG-clean, L2 hook injected from above; reth ExEx byte-matches ADT + min-fold + WAL-on-all-canonical + wake-after-drain |
| RX-XC-04 | R7 aggregator ownership | SHARPENS | Prune-barrier must be node-scoped single registration point (in-proc shim + gRPC); a missing consumer must hold the barrier (fail-safe) |
| RX-XC-05 | NetworkFamily contract | SHARPENS | fukuii's family is thinner than reth `NodeTypes`; must EXCLUDE `Storage`/`Primitives` (R1 counter-model) |
| RX-XC-06 | storage completeness (R8) | CONFIRMS | Fully covered by RX-L2-07/08/09/18/20/21/24; besu Forest+Bonsai proves multi-backend-behind-one-seam |
| RX-XC-07 | security/auth seam (R11) | SHARPENS | besu `authentication/` is the JVM mirror (Engine-JWT + per-method perms precedented); MCP write-ops auth unprecedented; single-gate unification is fukuii synthesis |
| RX-XC-08 | observability | SHARPENS | besu `metrics/` mirrors the abstraction only; `PrometheusRegistry.defaultRegistry` + NoOp workaround = the R2 anti-pattern; per-instance registry is novel |
| RX-XC-09 | MCP / agentic (F1) | SHARPENS | erigon MCP proves read-only transport only (no write, no auth); write-ops + actor-state + auth are fukuii-original |
| RX-XC-10 | cross-cutting optimizations | CONFIRMS (+SHARPEN) | Rows byte-grounded; besu acceptance-cluster must ADD per-instance-isolation assertions (besu's DSL uses NoOp metrics) |

**Cross-cutting premise answer (for the READY gate):** the R2 single-binary-multi-`ChainInstance`
premise is **genuinely novel** — no reference client runs concurrent cross-family multi-instance in
production; besu's `ThreadBesuNodeRunner` proves JVM-*viability* (per-node controller/services) but is
test-only, same-curve, and NoOp-metrics. Consequently R2, the per-instance metric registry (RX-XC-08),
per-instance auth (RX-XC-07), and MCP-write control (RX-XC-09) are all fukuii-original and can only be
verified by the per-layer no-global-statics grep DoD (RX-XC-01), not by byte-checking a reference client.
No thread-level `CORRECTS` (no reference client *contradicts* a premise); 7 `SHARPENS` fold reference
detail / novelty framing / a blast-radius edge into the plan; 3 `CONFIRMS`.
