# Cross-cutting testing plan — the R10 testing ratchets (SR-12)

_The shared home the three service-layer drafts (L8/L9/L10) and every consensus layer defer their §8 DoD
to. Grounded in [`observations/testing.md`](../../../research/clients/observations/testing.md) (the SR-12
synthesis), the AS-IS snapshot `.local/docs/research/clients/fukuii/testing.md`, the five per-client
`{client}/testing.md` docs, `optimizations.md` §Cross-cutting, `requirements.md` R10/R4/R2, and
[`reference-client-crosscheck.md`](../../../research/best-practices/evm-clients/reference-client-crosscheck.md).
This is prospective intent, not an as-built record — where a layer plan's §8 names "reference vectors" or
"the GREEN bar," this doc is the definition of the ratchets those vectors run under. `cross-cutting.md` §2
states the per-phase test **cadence**; this doc states the **ratchets, vehicles, and DoD engine** that
cadence executes against. Owner: `eye` (SR-12 sits last in the ready-now band, independent)._

## 0. Scope & role

fukuii already occupies the universal conformance slot — a ScalaTest `ethereum/tests` consumer that
replays fixtures through the **real** ETH execution path and asserts the post-state root
(`EthereumTestExecutor.scala:57`, state root is the oracle), 12 in-tree `hive-*.yml` workflows (it *leads*
every reference client here — all six treat hive as external), ScalaCheck present (~83 `forAll` specs),
tiered `testEssential`/`testStandard`/`testComprehensive` aliases, forked-JVM + per-IT-spec sub-JVM
isolation, and a no-`Thread.sleep` constitution. **The rebuild does not re-invent this; it ratchets it.**
This plan schedules the specific gaps the SR found between fukuii's estate and the six reference clients'
— gaps that are false-green risks (a suite that runs zero tests while printing green), silent-drop risks
(a re-passing known-broken fixture that never flips), and missing vehicles (no multi-instance isolation
harness for the enterprise differentiator, no Olympia fixture generation for the ECIP work).

**What this doc is NOT:** it is not a layer plan (it owns no module) and it does not restate the cadence
in `cross-cutting.md` §2 or the gate lenses in `cross-cutting.md` §3. It is the **R10 testing-ratchet
register** + the **multi-instance isolation vehicle** + the **DoD engine** every layer's §8 cites.

## 1. SR verdicts honored (quoted, cited)

From [`observations/testing.md`](../../../research/clients/observations/testing.md) — the binding
DEFAULT/OPTIONAL verdicts this plan schedules. Verdicts are **use-case/role-aware**; the recurring axis is
the ETC/PoW-Olympia vs ETH/PoS-Osaka fork-family split.

| # | SR verdict (quoted) | Disposition here |
|---|---|---|
| V1 | **"Expected-failure-WITH-REASON skip taxonomy … DEFAULT (all roles). A known-broken fixture is *asserted to fail with a mandatory reason*, so a regression can't hide and a re-passing fixture flips loudly."** | **DEFAULT** — Ratchet 1 |
| V2 | **"Assert a nonzero fixture count so a missing submodule fails loudly (geth's silent `t.Skip`-the-whole-suite is the anti-pattern to avoid)."** | **DEFAULT** — Ratchet 2 |
| V3 | **"Fork identity carried in the test name … so ETC-Olympia and ETH-Osaka fixtures are independently selectable (`--tests "*_olympia_*"` vs `"*_osaka_*"`) with no runtime filter."** | **DEFAULT** — Ratchet 3 |
| V4 | **"Acceptance-test cluster DSL (besu) — the Batch-7 private-network multi-node case … boot N real nodes … `verify` across the cluster. OPTIONAL (Batch-7 private-network / multi-node role)."** | **OPTIONAL(role) — build the seam now** — Ratchet 4 (the L10 multi-instance vehicle) |
| V5 | **"ETC-fork fixture generation (core-geth `mgen` analog) — fukuii must *generate* its own Olympia-era ECIP vectors; the frozen 2025 `etclabscore/tests` snapshot stops at Mystique. DEFAULT (ETC/PoW role)."** | **DEFAULT(ETC)** — Ratchet 5 |
| V6 | **"Property-based tests (reth `proptest` → fukuii ScalaCheck) — trie-root equivalence, RLP round-trip, codec `Arbitrary` derivation, each with a fixed seed. OPTIONAL (high-value, all roles)."** | **DEFAULT (high-value, adopt)** — Ratchet 6 |
| V7 | **"Content-hash-pinned fixture manifest + failure-budget shards (erigon's `test-fixtures.json` sha256 + `eest-spec-shards.yml` `max-allowed-failures`) to make fixture provenance … explicit and reviewable."** | **DEFAULT** — Ratchet 7 |
| V8 | **"Borrow reth's `Case`/`Suite` two-trait split so new fixture formats (EEST, hive) reuse one walk/parallelize/report scaffold."** | **DEFAULT** — Ratchet 8 |
| V9 | **"In-tree hive adapter + suites — fukuii already exceeds every peer (12 suites vs besu's zero in-repo files). Keep it."** | **KEEP (no change)** — §5 tier 3 |

**The one caveat the SR attaches (binding):** *"Scope skips PER-NETWORK — a fork-choice fixture that is
'inconclusive post-merge' (besu's `UncleFromSideChain_Merge` ignore) is still valid for ETC/PoW; a global
skip would wrongly drop ETC coverage."* Every ratchet below carries this per-network scoping.

## 2. Authorities (per-concern, from `reference-client-crosscheck.md` §1)

Testing has **no single authority** — the harness *structure* and the fixture *content* have different
witnesses, and each ratchet cites its own.

| Concern | Authority | JVM structural mirror | Note |
|---|---|---|---|
| **Fixture content** (expected state roots, ETH-EVM vectors) | **go-ethereum `t8n`** (the oracle) | — | fukuii consumes, never authors, ETH fixtures. `t8n`'s I/O contract *is* the interop spec. |
| **ETC/PoW fixture content** (ECIP-1017, ETChash, Olympia) | **core-geth** (`etclabscore/tests` + `mgen`) | besu (ETC history) | Frozen 2025 snapshot stops at Mystique — Olympia needs generation (Ratchet 5). |
| **Conformance-harness structure** (source-set tiering, `JsonTestParameters<S,T>` engine, fork-in-name) | **besu** | **besu** (the JVM mirror) | `besu/testing.md`: build-time codegen, fork encoded in class name, same fixtures × two backends. |
| **Trait-split fixture scaffold** | **reth** (`Case`/`Suite`) | — (Rust — port the shape) | `reth/testing.md:126`: "how to load+run one fixture" ⊥ "how to discover+aggregate a tree." |
| **Property-based testing targets** | **reth** (`proptest`) → **ScalaCheck** | — | trie-root incremental==from-scratch, RLP round-trip, `Arbitrary` codecs. |
| **Adversarial-bytes fuzzing** | **go-ethereum** (`tests/fuzzers/`, differential) | — | The `FuzzRLP` analog — the missing direction (Ratchet 6). |
| **Expected-fail-with-reason ledger** | **go-ethereum** / **erigon** (`Fails(pattern,reason)`) | — | erigon asserts known-broken FAILS + failure budgets. |
| **Content-hash manifest + failure budgets** | **erigon** (`test-fixtures.json` sha256, `eest-spec-shards.yml`) | — | Provenance + "how-many-known-failing-is-OK" as a governed ratchet. |
| **Multi-node acceptance cluster** | **besu** (`AcceptanceTestBase` + `Cluster`) | **besu** | The L10 multi-instance + Batch-7 vehicle (Ratchet 4). |
| **DoD methodology** | **`reference-client-crosscheck.md`** (SUPPORTED/AMEND) | — | The multi-client evidence-table engine every byte-exact DoD runs (§6). |

**besu is the JVM-implementation guide throughout** (`reference-client-crosscheck.md` §1: "weight heavily
for JVM-idiom questions") — read its Java harness alongside geth's Go when shaping the Scala.

## 3. The R10 testing ratchets

Each ratchet = **what / why / AS-IS gap / vehicle**. Dispositions per `optimizations.md`
§Cross-cutting. Every ratchet is a **floor** (`planned-work-is-scope-floor`) — scheduled, not optional.

### Ratchet 1 — Assert-fails-WITH-REASON (replace the `BrokenEthTest` tag-hide) · DEFAULT · the single highest-value gap

- **What:** a known-broken consensus fixture is **asserted to still fail, with a mandatory reason string**
  (erigon `TestMatcher.Fails(pattern, reason)` / reth block-specific `expect_exception`), so a fixture
  that silently starts passing **flips the suite loudly** and a regression can't hide behind an exclusion.
- **Why:** the skip list doubles as a living compatibility ledger (`observations/testing.md`
  approach-catalog: "Superior to a bare `ignore`/`-- SKIP`"). erigon panics on an empty reason; geth's
  `fails` taxonomy panics on empty; reth verifies the failure lands on the *right* block number.
- **AS-IS gap:** fukuii's `BrokenEthTest`/`DisabledTest`/`FlakyTest` (`Tags.scala:238,273`) are **tag
  exclusions** — `-l BrokenEthTest` (`build.sbt:642`) hides the fixture from the gate but does **not
  assert it still fails**; a re-passing fixture does not flip loudly (AS-IS `testing.md`: "hides
  known-broken, does NOT assert-it-still-fails"). This is the highest-value testing gap.
- **Vehicle:** a `ExpectedFailure(pattern, reason)` policy in the `Case`/`Suite` scaffold (Ratchet 8):
  the runner matches the failing block/vector against the expectation; a match with the reason recorded is
  GREEN, a *pass* where a failure was expected is RED. Ties to the `ETHTEST-EXEC-REGRESSIONS-01` finding —
  the currently-`BrokenEthTest`-tagged exec specs migrate onto asserted-fail entries with their finding ID
  as the reason. **Per-network scoped** (an ETH post-merge "inconclusive" expected-fail must not drop the
  same fixture's still-valid ETC/PoW coverage).

### Ratchet 2 — Assert-nonzero-fixture-count (close the REPO-06 false-green class) · DEFAULT

- **What:** every fixture-consuming spec asserts it discovered **> 0** fixtures before running; a zero
  count fails loudly instead of printing a green "Suites: completed."
- **Why:** geth's silent `t.Skip`-the-whole-suite is the named anti-pattern (`observations/testing.md`);
  a missing/uninitialized submodule must zero *nothing* silently.
- **AS-IS gap (the concrete incident):** `REPO-06` — the `Integration = config("it").extend(Test)` axis
  delegation meant `commonSettings`' `-l IntegrationTest` also excluded every `IntegrationTest`-tagged
  spec from `IntegrationTest/test` itself, so **8 of the 9 `ethtest` spec classes ran zero tests every
  night** while green. Separately, ethtest specs resolve `new File(user.dir, "ets/tests")` and
  `if !baseDir.exists()` **bail without failing** (`BlockchainTestsSpec.scala`) — an uninitialized
  submodule zeroes ETC/EVM conformance the same way. No nonzero-fixture guard exists yet.
- **Vehicle:** a shared `assertNonZeroFixtures(count, corpusName)` barrier in the `Suite` scaffold, run
  after discovery and before assertion; wired for **both** provisioning paths (the curated in-tree
  `src/it/resources/ethereum-tests/*.json` and the File-walked `ets/tests` submodule) **and** the ETC
  `etclabscore/tests`/generated-Olympia corpus (Ratchet 5). The config-axis delegation footgun itself is
  a build-hygiene fix owned by warden (`build.sbt` `:=` full-replace of `Integration/testOptions`); this
  ratchet is the *test-level* guard that catches the next instance regardless of build-config cause.

### Ratchet 3 — Fork-in-test-name (dual-family independent selectability) · DEFAULT

- **What:** fork identity encoded in the test/case name so **ETC-Olympia and ETH-Osaka fixtures are
  independently `--tests`-selectable per family** with no runtime filter — besu's generated class names
  (`ExecutionSpec*_prague_eip7702_*`).
- **Why:** directly serves fukuii's PoW-vs-PoS dual-family conformance — CI and devs run a
  family-scoped slice (`testOnly *_olympia_*` vs `*_osaka_*`) without a bespoke harness.
- **AS-IS gap:** fukuii resolves the fork at **runtime** via `TestConverter.networkToConfig(test.network,
  baseConfig)` + a `supportedNetworks` filter (`EthereumTestExecutor.scala:47`) — flexible, but "the two
  families are **not** independently `--tests`-selectable by name the way besu's are" (AS-IS `testing.md`,
  a gap the SR flags). Fork-in-the-config, not fork-in-the-class-name.
- **Vehicle:** the `Case`/`Suite` scaffold (Ratchet 8) names each discovered case with its resolved fork
  family + fork — fukuii's rendering: the `AnyFlatSpec` behavior text / case name carries
  `<network>_<fork>` — keeping the runtime `TestConverter` resolution *and* adding a name-selectable
  handle. Aligns the ETC-Olympia (Ratchet 5) and ETH-Osaka corpora onto one selectable naming convention.

### Ratchet 4 — besu acceptance-Cluster DSL (the L10 multi-`ChainInstance` isolation vehicle) · OPTIONAL(role) — build the seam now

- **What:** a fluent multi-node cluster harness (besu `AcceptanceTestBase` + `Cluster` `AutoCloseable`,
  action `*Transactions` ⊥ assertion `*Conditions`) that boots N real nodes and `verify`s a condition
  across the cluster over live JSON-RPC.
- **Why (two payloads):**
  1. **The R2 multi-instance isolation vehicle (§4)** — the enterprise differentiator (L10's concurrent
     multi-`ChainInstance` runtime) has **no test vehicle** in the AS-IS estate; this is it.
  2. **Batch-7 private-network GTM** — boot a consortium (BFT/QBFT/Clique), elect a bootnode, await
     discovery, `verify` across all nodes — fukuii's private-network go-to-market
     (`observations/testing.md`: "the ready-made shape").
- **AS-IS gap:** no acceptance/cluster layer exists; fukuii's E2E specs (`E2EHandshakeSpec`,
  `E2ESyncSpec`, …) are single-node actor-choreography, and six of them "silently never ran in CI"
  (`REPO-06-ITSUITE`, root cause a ServerActor Classic→Typed `Bind`-sender drop). The clean write builds
  the cluster DSL from besu's structural mirror rather than retrofitting the ad-hoc E2E specs.
- **Vehicle:** a Scala `AcceptanceTestBase`-analog (`Cluster` as a `cats-effect Resource` / `AutoCloseable`
  bracketing N `ChainInstance`s), the action/condition split as two trait families, node runners pluggable
  **in-process** (Typed `ActorTestKit` guardian subtrees) or **out-of-process** (besu `ProcessBesuNodeRunner`
  analog). **Determinism:** sync-probe barriers / condition polling with bounded deadlines, never
  `Thread.sleep` (Ratchet 10). Detailed isolation assertions in §4.

### Ratchet 5 — Olympia fixture generation (core-geth `mgen` analog) · DEFAULT(ETC)

- **What:** a generator path that **produces** ETC Olympia-era ECIP vectors (ECIP-1111/1112/1121/1122
  difficulty/state/emission fixtures) from fukuii's own `BlockchainConfig` schema — core-geth's `mgen`
  (`tests/*_mgen_test.go`, `state_mgen.go`) rendered in Scala.
- **Why:** the frozen 2025 `etclabscore/tests` snapshot **stops at Mystique**; ETH's `t8n` `Forks` table
  has no ETC/Olympia entries. fukuii's Olympia ECIP work (forge-owned) has *no* reference corpus without
  generation (`core-geth/testing.md`, AS-IS `testing.md` authority note, both explicit).
- **AS-IS gap:** none exists — the entire ETC-Olympia conformance corpus is missing.
- **Vehicle:** a `forge`-owned generator (consensus-critical — the state-root litmus applies), producing
  fixtures in the `ethereum/tests` JSON schema so the *same* `Case`/`Suite` engine (Ratchet 8) consumes
  them; the generated corpus is content-hash pinned (Ratchet 7) and named `*_olympia_*` (Ratchet 3).
  **Additive extension without forking the harness** (core-geth's reusable idea) — the upstream
  `ethereum/tests` corpus stays pristine; the ETC/generated vectors sit alongside as a second source. The
  generator's own output is validated against core-geth for the through-Mystique overlap before Olympia
  vectors are trusted (the crosscheck engine, §6).

### Ratchet 6 — ScalaCheck decoder-hardening + trie/RLP proptests (the `FuzzRLP` analog) · DEFAULT

- **What:** property-based tests sitting *beside* the fixture consumer (not replacing it), three targets:
  1. **Adversarial-bytes decoder hardening** (the missing direction) — the go-ethereum `FuzzRLP` analog:
     `forAll(Gen.listOf(arbitrary[Byte])) { bytes => RLP.rawDecode(bytes) must (succeed-or-fail-cleanly) }`
     — random/adversarial byte strings against every decoder (`RLP.rawDecode`, block/tx/header/receipt
     codecs), asserting no panic/OOM/hang, only clean typed failure. **fail-loud, not crash** — ties to
     `fail-loud-invariants.md`.
  2. **MPT/trie-root incremental-vs-batch equivalence** — reth's canonical proptest
     (`fuzz_in_memory_nodes.rs:29`): the incrementally-updated trie root == a from-scratch recomputation
     over N random state-update sequences. Guards L2's parallel/flat state-root paths.
  3. **RLP round-trip** — `decode ∘ encode == id` over `Arbitrary`-derived domain types (all tx variants,
     headers, receipts) — reth's `encode∘decode == id`.
- **Why:** fixtures catch known cases; properties catch the unknown ones the fixtures don't enumerate
  (`reth/testing.md:114`: "the single most transferable idea"). The **adversarial-decode direction is
  entirely absent** from fukuii today — a network-partition / DoS surface (F-RLP-1 non-canonical-RLP was
  exactly this class at L0).
- **AS-IS gap:** ScalaCheck is *present* (~83 `forAll` specs, `scalacheck 1.19.0`) but used for
  happy-path generators; the **adversarial-bytes → decoder** direction and the trie-root-equivalence
  property are not written. Determinism: neither erigon's sha256 pin nor reth's seeded-RNG replay exists.
- **Vehicle:** seedable ScalaCheck (`Test.Parameters` with a fixed / `SEED`-env seed for
  replay, mirroring reth's `SEED`), one proptest module per codec + per incremental-vs-batch computation,
  colocated with the layer it covers (L0 RLP, L1 domain codecs, L2 trie). Each property is a per-layer §8
  DoD line.

### Ratchet 7 — Content-hash fixture manifest + failure-budget shards · DEFAULT

- **What:** a checked-in manifest pinning every fixture corpus by **sha256** (erigon `test-fixtures.json`)
  + a shard config carrying explicit `max-allowed-failures` budgets (erigon `eest-spec-shards.yml`, mostly
  `0`; bumping requires a comment + tracking issue — a governed ratchet, not a silent skip).
- **Why:** makes fixture provenance **and** the "how many known-failing is acceptable" line explicit,
  reviewable, and supply-chain-integral (the test corpus itself is a dependency).
- **AS-IS gap:** fukuii pins fixtures by **submodule SHA only** (`ets/tests` `5490db3ff5`) — no sha256
  content-hash manifest, no per-corpus failure budget, no EEST-tarball provenance record (fukuii pulls
  GeneralStateTests from the same `ethereum/tests` submodule, not a distinct pinned archive).
- **Vehicle:** a `test/fixtures.json`-analog (corpus name → URL/submodule + sha256 + size) validated at
  test-provision time (besu's `validateReferenceTestSubmodule` fails loudly on drift is the mirror), plus
  a shard/budget file the tiers read. **sentinel-adjacent:** the manifest is a supply-chain artifact —
  fixture-corpus bumps route through the same evidence-gated discipline (`resolution-age`-equivalent
  review), no unilateral corpus bumps. The generated Olympia corpus (Ratchet 5) is pinned here too.

### Ratchet 8 — reth `Case`/`Suite` two-trait scaffold (the shared engine) · DEFAULT · the enabler for 1/2/3/5

- **What:** the `Case` (load+run one fixture) ⊥ `Suite` (discover+aggregate+parallelize+report a tree)
  two-trait split, so **every** fixture format — `ethereum/tests` blockchain/state/tx, EEST, the generated
  Olympia corpus, hive result JSON — reuses **one** walk/parallelize/report engine.
- **Why:** fukuii's AS-IS consumer is "per-spec `AnyFlatSpec` bodies + circe decode + runtime File-walk,
  one engine class (`EthereumTestExecutor`) shared by hand" (AS-IS `testing.md`) — neither besu's codegen
  nor reth's trait-split. The trait-split is the **carrier for Ratchets 1/2/3/5** (expected-fail policy,
  nonzero-count barrier, fork-in-name, new corpus formats all hang off the shared `Suite`).
- **AS-IS gap:** the nine `ethtest` spec classes duplicate discovery/decode/execute scaffolding; adding
  the EEST or generated-Olympia format today means another hand-wired spec.
- **Vehicle:** `trait Case { def load(path): Case; def run(): Either[Failure, Unit]; def description }`
  and `trait Suite { type C <: Case; def run(): Report }` over the existing circe decoders and the real
  `BlockExecution.executeAndValidateBlock` path (keep state-root-as-oracle, real-execution-only). Parallel
  fan-out via a bounded parallel collection (the JVM analog of reth's rayon `into_par_iter`), inside the
  forked-JVM isolation fukuii already has. **This is a test-infra refactor, not a consensus change** —
  eye/warden own it; it preserves the byte-exact assertion unchanged.

### Ratchet 9 — reference-client-crosscheck as the DoD engine · DEFAULT

- See §6 — the methodology every layer's "byte-exact vs the §3 authority" DoD runs.

### Ratchet 10 — Retire residual `Thread.sleep`; confirm scalamock for the isolation seams · DEFAULT

- **What:** (a) eliminate the ~11 residual `Thread.sleep` sites (the no-`Thread.sleep` constitution;
  `.specify/memory/constitution.md` makes deterministic tests binding); (b) confirm **scalamock**
  adoption for the isolation seams the layers introduce (`Signer`/`ISigner`, `TransactionValidator`,
  `MetricsSystem`/per-instance registry no-op, `NetworkFamily`) rather than hand-rolled test doubles.
- **Why:** timing-based tests are the flakiness surface the constitution bans; typed mocks over the new
  seams keep the isolation tests (§4) deterministic and intention-revealing. Stream tests use sync-probe
  barriers (`take(N)` + `Sink.seq`), Typed actors use `ActorTestKit`/`BehaviorTestKit` (`cross-cutting.md`
  §2) — never timing.
- **AS-IS gap:** ~11 `Thread.sleep` files remain (AS-IS count); scalamock adoption across the new seams is
  unconfirmed (the clean write introduces the seams, so it sets the convention from line one).
- **Vehicle:** a grep-verifiable ratchet (`Thread.sleep` count → 0 in `modules/*/src/{test,it}`), enforced
  as an eye-run DoD line; the seam mocks land with each seam's owning layer (L1 signer, L8 registry/txpool,
  L5 family).

## 4. The multi-instance-isolation test vehicle (R2 — the enterprise differentiator)

The concurrent multi-`ChainInstance` single-binary runtime (L10, R1+R2 convergence) is fukuii's enterprise
differentiator — and the AS-IS estate has **no vehicle to prove isolation**. Ratchet 4's cluster DSL is
that vehicle. The isolation assertions it must carry (from `requirements.md` R2 + `L10.md` §8):

- **Boot a heterogeneous pair in one process:** a PoW-ETC instance + a PoS-ETH instance (the two current
  family instances), each via the `NetworkFamily` typeclass.
- **`verify` distinct, non-shared:**
  - **metric registries** — two instances produce **distinct** `/metrics` content (the Bug-29 gate: the
    AS-IS shared static registry made `/metrics` byte-identical across instances — `optimizations.md` L8).
  - **datadirs / RocksDB handles** — no shared DB handle, no process-global DB state (R2, L2).
  - **RPC ports / Engine-API endpoints** — per-instance routing; one Engine-API endpoint per PoS
    instance; ETC exposes none.
  - **no cross-talk** — a block/tx/peer event on one instance never appears on the other; no shared
    mutable `ActorSystem`, config, or credential state (R2, R11 per-instance auth).
- **The isolation-regression grep DoD** (`optimizations.md` L10): no `object … { var … }` / global
  singleton survives — asserted as a static check alongside the runtime cluster test.

This vehicle is **built as a seam now** even though the full N-family occupancy defers
(`planned-work-is-scope-floor`: the seam depth cannot defer, only the occupancy). It doubles as the
Batch-7 private-network end-to-end harness (boot a consortium, `verify` BFT finality/permissioning).

## 5. Test tiers — which tier each layer's DoD uses

fukuii's two-axis tiering (sbt config × ScalaTest tag) is kept; the ratchets slot into it. The
canonical tier→corpus mapping (per `build.sbt` aliases, AS-IS `testing.md`):

| Tier | Mechanism | Corpus / scope | Ratchets active | Layer DoD use |
|---|---|---|---|---|
| **Unit** | `Test` config + tags; `testEssential` (`build.sbt:560`, ≈4278 tests, drifts — canonical count in `.local/docs/test-quality-log.md`) | per-module unit + submodule (`rlp`/`bytes`/`crypto`) suites; **ScalaCheck proptests (R6)** colocated | 6, 10 | every layer — the per-phase `testOnly *X*` after a logic phase |
| **Integration / conformance** | `IntegrationTest` config, per-IT-spec sub-JVM; `testComprehensive` (`build.sbt:590`) is the only alias that runs `IntegrationTest/testOnly` | the `ethereum/tests` + generated-Olympia fixture consumer via the `Case`/`Suite` engine; the **cluster DSL (R4)** | 1, 2, 3, 4, 5, 7, 8, 10 | consensus layers (L0/L1/L3/L4/L5) byte-exact DoD; L10 isolation |
| **Hive / cross-client** | in-tree `hive/fukuii/` + 12 `hive-*.yml` over `_hive-sim.yml`, per-sim `gate_pattern`/`pass_threshold` | 10 sims (consensus/engine/rpc-compat/graphql/sync/devp2p/consume-*/osaka/prague/smoke) | 7 (pin the hive refs), 9 | L5/L6/L7/L9 integration gate; L10 boot smoke |

**Tier caveats to honor (AS-IS footguns, not to reintroduce):** `testStandard` is a **misnomer** — it
never runs the IT config (`build.sbt:575`); the `ethereum/tests` compliance suite runs **only** under
`testComprehensive`/`pp`. `FlakyTest` is excluded from *every* tier ("never a gate; fix, don't opt back
in"). The `CryptoTest` alias finds 0 in the main module (the 10 crypto tests live in `crypto/` — use
`crypto/test`). The pre-push gate is `testEssential` via `sbt-run.sh` background (`cross-cutting.md` §2 /
`background-script-execution.md`), **not** a mid-sprint run.

**Per-phase cadence** is `cross-cutting.md` §2 (not restated here): file edit → `compile-all`; logic phase
→ `testOnly *X*` + touched callers; pre-push → `testEssential`. Until warden's build-config fix lands,
every layer's DoD uses `testOnly` discovery, never bare `<module>/test` (the BUILD-1 sbt-2 false-green).

## 6. The DoD engine — `reference-client-crosscheck` as the byte-exact gate (Ratchet 9)

Every consensus layer's §8 "byte-exact vs the §3 authority" DoD runs the **multi-client evidence-table
methodology** (`reference-client-crosscheck.md` §2), not a hand-written happy-path assertion:

1. **State the byte-value as an invariant**, not a preference (`constant-time-comparison.md` is the worked
   example — the *condition* is part of the standard, not an asterisk).
2. **Build the evidence table** — one row per bearing client, each a concrete `file:line` citation
   (against `.claude/repo-references/clients/<client>/`) + a verdict.
3. **Weight by the coverage map** — an **EIP/Ethash-fidelity** claim needs **go-ethereum/core-geth**
   SUPPORTED; a **JVM-idiom** claim needs **besu** SUPPORTED. A claim contradicted by the weighted-primary
   client is **AMEND** (rewrite the claim), never SUPPORTED-with-caveat.
4. **Verdict taxonomy:** `SUPPORTED` / `AMEND` only — there is **no PARTIAL**; partial evidence means the
   claim is mis-scoped.

**The governance rule (binding):** *"A consensus/EVM coding standard is ratifiable only when grounded in
reference-client evidence. 'It compiles and the tests pass' establishes correctness of one implementation,
not that the shape is the client-universal one."* The fixture corpus proves byte-exactness against the
oracle; the crosscheck table proves the *shape* is client-universal. Both gate a consensus layer.

This is why the fixture consumer (state-root-as-oracle) and the crosscheck engine are **complementary**, not
redundant: fixtures validate the computed *result* against go-ethereum `t8n`; the crosscheck table
validates the code *shape* against besu (JVM) + geth/core-geth (fidelity). A besu idiom that diverges from
geth's *shape* is fine; a besu idiom that diverges from geth's *computed result* is a consensus bug in besu
(`reference-client-crosscheck.md` §1) — the fixtures catch that, the table doesn't.

## 7. Exit DoD — what "the testing plan is READY" means

This plan is READY (per REVIEW.md §6b multi-pass — clean pass following ≥2 found-and-fixed) when:

- **Every ratchet (1–10) has a named vehicle + owner + the layer(s) whose §8 cites it** — no ratchet is
  "adopt later" without a floor (`planned-work-is-scope-floor`).
- **The DoD engine (§6) is the stated gate for every consensus layer's byte-exact §8** — L0/L1/L3/L4/L5
  §8 point here for the crosscheck methodology; L8/L9/L10 §8 point here for the tier + isolation vehicle.
- **The multi-instance isolation vehicle (§4) is specified as a buildable seam** — L10's differentiator
  has a proving test defined before L10 is built (the L0 lesson: the gate confirms a plan, not discovers a
  crisis).
- **The per-network scoping caveat is carried on every skip/expected-fail** — no global skip silently
  drops ETC/PoW or ETH/PoS coverage.
- **The three false-green classes are closed by a scheduled ratchet each** — REPO-06 zero-fixture (R2),
  BrokenEthTest silent-repass (R1), submodule-drift (R7).
- **No layer's §8 defers a testing concern to "each layer"** — the concern lives here (R10:
  "The testing plan (SR-12) is authored as its own doc, not deferred to each layer's §8").

**The multi-pass hunts the full taxonomy** (REVIEW.md §6b): an SR verdict not honored (§1), a
best-practice not applied (`reference-client-crosscheck`, `fail-loud-invariants`), a reference-client idea
(besu cluster / reth trait-split / erigon manifest / core-geth mgen) not adopted-or-ruled-out, a
downward-constraint (R2 isolation, R4 byte-exact, R10 ratchets) unsatisfied.

## 8. Agentic alignment

- **`eye` owns testing/validation (SR-12).** eye *runs* the suites and reports counts + gaps against the
  reference vectors — it does not just read them (`cross-cutting.md` §2/§3 lens 4). eye is read-only; it
  holds no Write grant. The per-layer §8 DoDs are eye's checklist.
- **The per-layer §8 DoDs reference this doc**, not the reverse: L0/L1/L3/L4/L5 §8 cite §6 (the crosscheck
  DoD engine) + §3 (the ratchets their fixtures run under); L8/L9/L10 §8 cite §5 (tiers) + §4 (the
  isolation vehicle). This doc is the single home; a layer's §8 names *its* vectors, this doc names the
  *ratchets* those vectors run under.
- **Ownership split of the ratchet work:**
  - **`eye`** — runs every tier; owns Ratchets 1/2/3/8/10 vehicles (the `Case`/`Suite` scaffold, the
    expected-fail policy, nonzero-count barrier, fork-in-name, `Thread.sleep` retirement) as test-infra.
  - **`forge`** — Ratchet 5 (Olympia fixture generation is consensus-critical — the state-root litmus
    applies; the generator is forge-owned, validated against core-geth via §6).
  - **`warden`** — the build-config half of Ratchet 2 (the `Integration/testOptions` `:=` fix, the
    BUILD-1 sbt-2 false-green root cause), the hive workflow plumbing, the CI shard/budget wiring.
  - **`sentinel`** — the Ratchet 7 fixture-manifest as a supply-chain artifact (content-hash pins,
    corpus-bump gating), and any test-only dep add (scalamock, ScalaCheck bumps) — no other agent edits
    `Dependencies.scala`.
  - **`banksy`/`beacon`** — co-review where a tier touches their tier: banksy on txpool-admission test
    fixtures (L8), beacon on Engine-API fork-gating hive suites (L9).
- **Consensus-critical testing follows the gate** (`consensus-change-protocol.md`): the Olympia generator
  (R5) and any change to the fixture-consumer's execution path route through forge/beacon before eye
  validates — the generator produces the vectors the consensus layers are then held to.

## Layer boundaries

- **This doc owns:** the R10 testing-ratchet register (§3), the multi-instance isolation vehicle spec
  (§4), the tier→corpus→ratchet mapping (§5), and the crosscheck DoD engine reference (§6). It is the
  single home the layer §8s defer to.
- **This doc does NOT own:** the per-phase cadence (`cross-cutting.md` §2), the gate-lens definition
  (`cross-cutting.md` §3 — the ≥3-independent-lenses rule), the build-config mechanics (warden /
  `build.sbt`), or any layer's *specific* reference vectors (each layer's §8 names its own). It states the
  ratchets and vehicles those run under, not the values.
- **No module.** Testing is cross-cutting infrastructure; it builds no `modules/<name>` and sits at no DAG
  layer — it is inherited by every layer, like `cross-cutting.md`.
