# Cross-cutting plan — the concerns that span every layer

_Grounded in [`observations/cross-cutting-themes.md`](../../../research/clients/observations/cross-cutting-themes.md)
and [`observations/testing.md`](../../../research/clients/observations/testing.md). These are not a layer;
they are invariants and mechanisms every `plan/L{n}.md` inherits. Where a layer plan says "Pekko Typed"
or "besu structural mirror" or "GREEN bar," this doc is the definition._

## 1. The actor model (Theme 1 — JVM shape, Typed idiom) — L6 / L7 / L10

No reference client uses actors, so the JVM clients inform *target structure* while the actor *idiom* is
ours. The synthesis from `cross-cutting-themes.md` Theme 1:

- **Actor granularity = channel-ownership.** One Typed actor's private state = what one goroutine would
  exclusively own (the geth/erigon litmus). Not a 1:1 port of Mantis's Classic actors.
- **Sealed `Command` ADT + explicit `replyTo`** for every interaction — `Behavior[Command | InternalMsg]`,
  **never `Behavior[Any]`** (the old ~179-narrowing debt never comes into existence).
- **Constructor-injected typed `ActorRef[Command]`** passed at spawn (besu `ServiceManager` DI), never
  `actorSelection`/global lookup.
- **Lifecycle as distinct Behaviors** (besu Lifecycle FSM → behavior-as-state-machine).
- **Immutable per-fork `ProtocolSpec`-style bundles** the actor *references*; no mutable fork state in it.
- **TCP = `pekko.stream.scaladsl.Tcp` (Streams-Tcp), never Classic `pekko.io.Tcp`** (MOD-13); RLPx framing
  is stream stages, not a Classic actor bridge. **Typed test kit only** (`ActorTestKit`/`BehaviorTestKit`).
- **Effect integration** — Pekko Typed ↔ cats-effect/fs2 per the TL1/TL2 rules; no ad hoc `unsafeRunSync`
  inside actors.

Authority: `.agents/protocols/code-style/pekko-typed-api.md` (P1–P25 + TL1/TL2). The old code's
Classic→Typed migration was "otherwise complete" except a TCP boundary + under-typed message tail — in the
clean write these are invariants from line one, not debt to carry.

## 2. Testing — every layer

Per `.agents/protocols/process/testing-protocol.md` and `observations/testing.md`:

- **Per-phase cadence:** every file edit → `sbt compile-all` (fast; core-domain sweeps use `compile`
  between files, `compile-all` once at end). Type-only phases → compile, no tests. After a logic phase →
  `testOnly *<X>*` + touched caller specs. Before push → the full `testEssential` tier.
- **The sbt-2.0.2 false-green trap (BUILD-1):** bare `sbt <module>/test` silently reports 0 tests. The
  sanctioned path is `Test/`-scoped aliases + `testOnly` wildcard. warden owns the root-cause fix. Until
  fixed, every layer's DoD uses `testOnly` discovery, never bare `<module>/test`.
- **Reference vectors are the DoD, not hand-written happy-path tests.** Each consensus layer's GREEN bar
  names the `ethereum/tests` / hive / EIP-asset vectors that must pass (see each layer plan §8).
- **Determinism** — no `Thread.sleep`; Typed test kit for actors; stream tests use sync-probe barriers
  (`take(N)` + `Sink.seq`), not timing.
- **Coverage** — statement coverage ≥ 70% (constitution); the test-quality lens (eye) runs the suites and
  reports counts + gaps, it does not just read them.

## 3. The gate — the DoD that advances a layer

Every layer runs **≥3 independent lenses, none of them the builder**, before L{n+1}:

1. **Correctness / byte-alignment** — vs the per-concern authority (go-ethereum + core-geth for ETC-frozen)
   + the `observations/{slot}.md` verdicts.
2. **besu JVM-implementation lens** — does the Scala match besu's Java *approach*? Flags Go-idioms that
   don't hold on the JVM (integer widths, slice aliasing, coordinate systems, native-lib boundaries). The
   L0-proven pass (caught B-BLS-1).
3. **Scala 3 idiom** (mithril) — opaque/given/enum/derives, Typed correctness, no Scala-2 remnants.
4. **Test quality + coverage vs reference vectors** (eye — runs the suites).

Every finding fixed to **GREEN** before advancing; a layer is not "done" until every module in it is GREEN
**and wired** (never "built but dormant" — the dominant old-fukuii failure). Consensus-critical layers add
the mandatory forge (PoW) / beacon (PoS) protocol per `consensus-change-protocol.md`.

## 4. The consensus-critical protocol — L0/L1/L3/L4/L5

The state-root litmus (`consensus-change-protocol.md`): *does the change alter the state root?* YES →
forge (PoW network, currently ETC/Mordor) or beacon (PoS network, currently ETH/Sepolia). Client-layer
policy that does NOT alter the state root but is protocol-relevant (mempool admission, tip/gas floors, MESS
subjective fork-choice) → banksy. The order for any consensus change: **plan → forge/beacon impact analysis
→ implement byte-exact vs the verified reference map → wraith (compile) → eye (validate)**. Byte-identity is
to the *verified map built first from the authority*, never to current code.

The recurring traps (risk register in `plan/README.md`): fork-name-for-the-wrong-network
(`Eth*`/`Etc*` must never cross — `scala3-style.md` ratchet); `else-means-ETC`/`else-means-ethash`
fallthrough (positive keying only); built-but-unshipped.

## 5. The NetworkFamily registry (B7.0.5) — spans L5 + L10

fukuii is a **multi-network framework**, and the extensibility seam is the SR's B7.0.5 design (not a new
idea — `observations/consensus-engines.md` §B7.0.5 + `observations/multi-network.md`):

- **A Scala 3 `given`-based `NetworkFamily` typeclass registry** — reth's compile-time type-safety +
  nethermind's single-binary runtime selection (static `EmbeddedFamilies` list), *minus* reth's
  one-binary-per-family and nethermind's reflection. Defined at **L5** (`consensus-api`), wired at **L10**
  (the multi-ChainInstance runtime).
- **Contract boundary — the family type carries fork/engine/chainspec/reward/wire knobs, and MUST NOT
  parameterize the storage backend or block-primitive types.** reth's `NodeTypes` folds `type Storage` and
  `type Primitives` into the family type (`crates/node/types/src/lib.rs:27–33`) — legal for reth because each
  binary monomorphizes to *one* family, but folding them in here would push family-typing down into L0–L2
  and recreate the R1/R3 leak (a network assumption in a lower layer forcing `isEtc()` upward) at the type
  level. R2 (N families in one binary over **one neutral storage schema**) requires L0–L2 be family-blind.
  reth `NodeTypes.{Storage,Primitives}` is therefore the named **counter-model** (what NOT to fold in), same
  class as erigon's `FrozenBorBlocks`-in-`ChainHeaderReader`. Storage selection is the R8 `StorageProfile`
  (role×network), orthogonal to family.
- **The runtime shape (L10) is unprecedented — verify by grep, not by byte-check.** Single-binary,
  concurrent, cross-family multi-`ChainInstance` is run in production by **no** reference client — besu,
  nethermind, go-ethereum, erigon, and reth are all one-chain-per-process. besu's `ThreadBesuNodeRunner`
  acceptance harness proves the *mechanism* (per-node `BesuController` + per-node services) is **JVM-viable**,
  but only as a test-only, same-network/same-curve harness forced to run `NoOpMetricsSystem` (its real
  metrics bind a JVM-global registry — §6). So the R2 runtime has **no reference client to byte-check the
  shape against**: the per-layer no-global-statics grep (`object … { var … }` / shared-registry / global
  singleton) is the **primary** verification, not a secondary check, and is a mandatory DoD line in every
  layer's gate — the `SignatureAlgorithmFactory`-class static is the *predictable* residue of
  one-chain-per-process design, so it will re-grow at every layer unless the grep is enforced.
- **Authorities:** runtime openness → nethermind (`IConsensusPlugin`); type-safe registry → reth
  (`NodeTypes`); family-ships-own-chainspecs → erigon (`polygon/`, NET-01); unified fork dispatch → reth
  `ForkCondition{Block,Timestamp,TTD,Never}`. besu owns the consensus *seam structure*
  (Sealer/ValidatorProvider/BlockInterface) + private-net origination.
- **fukuii already built the seam** — `EngineId.fromMarkers` positive-keying, guarded but **dormant**
  (`else-means-ETC` fallback). L5 ships it; the PoA seams (`EngineId.Clique/Qbft/Bor`) are enumerated for
  Batch-7/NET-02.
- **Invariant:** inject a family's knobs THROUGH the typeclass, never into shared readers (no `isEtc()` /
  `is_optimism()` — erigon's `FrozenBorBlocks` leak is the counter-example).

## 6. Observability — L8, but cross-cutting

Per `observations/observability.md`: consolidate the metrics stack (Kamon + kanela dropped in the
clean-slate — also cleared an sbt-2 blocker); **per-instance metric registry** (not a global static) so
the multi-ChainInstance runtime reports per-network.

besu `metrics/` is the structural mirror for the **`MetricsSystem` abstraction only** (the interface shape,
categories, `ObservableMetricsSystem`/`NoOpMetricsSystem` split). It is simultaneously the **R2
anti-pattern** for per-instance isolation: besu's `PrometheusMetricsSystem` binds the JVM-global
`PrometheusRegistry.defaultRegistry`, and its own in-JVM multi-node harness (`ThreadBesuNodeRunner`) works
around the resulting collision by returning `NoOpMetricsSystem` for every node — i.e. besu has *not* solved
per-instance metric isolation. So the per-instance registry (each `ChainInstance` owns its own registry,
exported with an instance label/port) is **unprecedented** — no client isolates metrics per-instance because
none runs multi-instance — and it is the observability instance of the §5 no-global-statics invariant.
Consequence for R10: the shipped Grafana dashboards must key on **instance-labelled** metric names, not the
global names besu emits (a dashboard hard-coding a global metric name breaks the moment metrics carry an
instance label).

## 7. Library currency & the deferral ledger

- **Born-modern, sentinel-gated deps.** Each layer is written on the Scala-3-native successor from line
  one — never re-introduce the old library: `pekko.stream.scaladsl.Tcp` (not Classic Tcp, MOD-13); circe
  (not json4s, MOD-06); Caliban 3.1.2 + caliban-pekko-http (not Sangria, MOD-14). All dependency changes
  are **sentinel-gated** (no other agent edits `Dependencies.scala`); LTS-current per `lts-versions.md`.
- **Deferrals land at their layer, with tests** (`planned-work-is-scope-floor`). Nothing is "deferred to
  later" vaguely; every piece has a named layer + tests (a floor, not optional). Each `plan/L{n}.md` §7
  lists what lands there; nothing is dropped below the design-of-record floor.

## 8. Doc discipline — plan vs record vs index

- **`plan/`** (this directory) = prospective, before-build intent, SR-grounded. **`../NN-*.md`** =
  retrospective as-built records. **`../README.md`** = the status index (commit-sha per layer).
- **Build-status lives ONLY in the index.** Durable docs (plan + record) carry design + "Layer boundaries"
  (permanent placement facts) — never "built/deferred/not-yet-built" status, which staled within a commit
  (`docs-future-proof`).
- **Rule 0** (`consult-sr-research-before-design`): the SR is the binding first input to every decision —
  grep the slot before proposing; never flag a "new gap" without confirming the SR didn't answer it.
- **Root-doc inline maintenance.** The root `AGENTS.md`/`CLAUDE.md` and `.claude/agents/*.md` charters
  describe the codebase structure — a clean-write that relocates code into `modules/` makes their `src/…`
  references dead pointers that mislead every agent (they load these first). Each layer's RECORD step
  updates them inline (module list, Key Directories, the module's breadcrumb), same commit as the record
  doc. Repo-setup should have done the initial reconciliation (the L0 miss); it is now a lifecycle step,
  not a deferred decision.
