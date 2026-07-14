# besu — testing
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

## Architecture summary

Besu's test estate splits into three structurally distinct layers, each a
separate Gradle source set / task so they run (and gate) independently:

1. **Unit tests** — ordinary `test` source set per module, `useJUnitPlatform()`,
   `-Xmx4g`, run everywhere. This is the bulk.
2. **Reference tests** (`:ethereum:referencetests`) — the **conformance harness**.
   Consumes the two external JSON fixture corpora — the vendored `ethereum/tests`
   git submodule and the downloaded `execution-spec-tests` tarball — and turns
   each JSON file into a **parameterized JUnit-5 test**. This is the direct analog
   for fukuii's ScalaTest reference-test consumption. Isolated in its own
   `referenceTest`/`referenceTestDevnet` source sets and `referenceTests` /
   `referenceTestsDevnet` / `referenceTestsCustom` tasks, deliberately excluded
   from the default `test`/CI unit run (OOM-prone, huge).
3. **Acceptance tests** (`:acceptance-tests:tests`, `:acceptance-tests:dsl`) — a
   **fluent DSL** that spins up real multi-node Besu **clusters** (in-process or
   as OS processes) and asserts end-to-end behaviour over live JSON-RPC. This is
   the enterprise/consortium-validation layer (BFT/QBFT/Clique, permissioning,
   plugins, pub/sub).

The load-bearing design idea is **fixture-to-test code generation at build time**:
rather than one giant data-driven test iterating thousands of files at runtime, a
Gradle task template-expands each batch of ~5 JSON fixtures into its own generated
`.java` class before compilation, so JUnit sees thousands of discrete, filterable,
independently-parallelizable test classes.

## Key types / interfaces / files

Reference-test harness (the conformance engine):

- `ethereum/referencetests/build.gradle:*` — the generator hub. Registers tasks
  `blockchainReferenceTests`, `generalstateReferenceTests`, `executionSpecTests`,
  `executionSpecDevnetTests`, `customBlockchainReferenceTests`, etc. `generateTestFiles(...)`
  collates fixture paths in batches of 5 (`paths.collate(5)`) and emits one
  generated JUnit class per batch from a template. `generateTestFilesGroupedByDirectory(...)`
  is the newer variant that groups execution-spec-tests by `hardfork/eip` so class
  names encode the fork (`ExecutionSpecBlockchainTest_prague_eip7702_set_code_tx_0`).
- `ethereum/referencetests/src/reference-test/templates/GeneralStateReferenceTest.java.template`
  and `BlockchainReferenceTest.java.template` — the code-gen templates. Each yields
  a class with `@ParameterizedTest @MethodSource("getTestParametersForConfig")` and
  `assumeTrue(runTest, ...)` so filtered-out cases become *skipped*, not failed.
- `ethereum/referencetests/src/reference-test/java/.../vm/GeneralStateReferenceTestTools.java:79`
  — `JsonTestParameters.create(...)` wiring; `executeTest(spec)` (line 127) runs one
  state test: process the single tx, commit, **assert `worldState.rootHash()` equals
  the expected root hash** (line 194) and the RLP-hashed logs match. The `EIPS_TO_RUN`
  allow-list (line 66) is driven by system property `test.ethereum.state.eips`.
- `.../vm/BlockchainReferenceTestTools.java:74` — the blockchain-test twin. Its
  `executeTest(name, spec)` builds a real `MutableBlockchain`, imports each block,
  and validates head. `NETWORKS_TO_RUN` gated by `test.ethereum.blockchain.eips`.
- `testutil/src/main/java/org/hyperledger/besu/testutil/JsonTestParameters.java:51`
  — the reusable **JSON→JUnit-params engine**. Generic `<S,T>`: Jackson-deserializes
  each fixture file into a spec object, runs a `Generator` callback to fan each file
  into one-or-more named cases, and a `Collector` applies include/ignore predicates
  (`test.ethereum.include` regex). Determinism lever lives here.
- `.../referencetests/GeneralStateTestCaseSpec.java`, `GeneralStateTestCaseEipSpec.java`,
  `BlockchainReferenceTestCaseSpec.java` — the Jackson-mapped fixture POJOs (the JSON
  schema of `ethereum/tests` rendered as Java types).
- `.../referencetests/ReferenceTestWorldState.java`, `ForestReferenceTestWorldState.java`,
  `BonsaiReferenceTestWorldState.java` — test world-state impls so the same fixtures
  run against **both** storage backends (Forest + Bonsai).
- `.gitmodules` — `eth-ref-tests` submodule → `github.com/ethereum/tests` mounted at
  `src/reference-test/external-resources`.

Acceptance-test DSL (the cluster engine):

- `acceptance-tests/dsl/src/main/java/.../dsl/AcceptanceTestBase.java:75` — the base
  class every acceptance test extends. Wires up the whole fluent vocabulary as
  protected fields: `cluster`, `besu` (node factory), plus paired
  `*Transactions` (actions) and `*Conditions` (assertions) for eth/net/admin/bft/
  perm/txpool/web3. `@AfterEach` closes the cluster and reports memory.
- `acceptance-tests/dsl/.../node/cluster/Cluster.java:37` — `AutoCloseable`
  multi-node orchestrator. `start(Node...)` selects a bootnode, starts the rest in
  parallel (`nodes.parallelStream()`), awaits peer discovery, and `verify(Condition)`
  fans an assertion across every node. This is the multi-node-consortium primitive.
- `acceptance-tests/dsl/.../node/BesuNode.java`, `BesuNodeRunner.java`,
  `ProcessBesuNodeRunner.java`, `ThreadBesuNodeRunner.java` — a node runs either as
  a separate OS **process** or in-**thread**; the runner is pluggable behind
  `BesuNodeRunner.instance()`.
- `acceptance-tests/dsl/.../node/configuration/BesuNodeFactory.java` — `besu.createArchiveNode("node1")`
  etc.; `.../genesis/` and `.../permissioning/` builders author per-test genesis.
- Example test (`.../jsonrpc/Web3Sha3AcceptanceTest.java`): the whole test body is
  `node = besu.createArchiveNode("node1"); cluster.start(node); node.verify(web3.sha3(input, expected));`
  — action/condition reads like English.
- `acceptance-tests/tests/build.gradle` — an isolated `acceptanceTest` `JvmTestSuite`
  (`useJUnitJupiter()`), with web3j Solidity compilation of `contracts/` for on-chain
  fixtures. Separate task, not part of `test`.

## Design decisions & rationale

- **Build-time code generation, not runtime iteration.** Batching 5 fixtures per
  generated class (`paths.collate(5)`) keeps class count and per-class memory bounded
  while still giving JUnit thousands of independently-selectable, parallelizable units.
  A single mega data-driven test could not be `--tests`-filtered by fork or sharded.
- **Fork/EIP encoded in the generated class name** (`ExecutionSpec*_prague_eip7702_*`)
  so CI and devs run `--tests "*_prague_*"` or `"*eip4844*"` without a runtime filter.
- **Reference tests physically isolated from unit tests** — separate source sets and
  tasks, absent from the default CI unit run. They are memory-hungry (`REFERENCE_TESTS.md`
  documents needing `-Xmx8g`) and semantically a *conformance gate*, not a unit gate.
- **Same fixtures, two world-state backends** (Forest + Bonsai) — conformance must
  hold regardless of storage layout, so the harness abstracts the world state.
- **Two fixture provenances, one engine.** Legacy `ethereum/tests` (git submodule,
  pinned by hash) + `execution-spec-tests` (versioned tarball from a custom Ivy repo,
  `v5.4.0`), plus a **devnet** channel (`bal@v5.6.1`) for unreleased forks kept out of
  the default `referenceTests` task so CI stays stable.
- **Submodule pin is asserted by a task** (`validateReferenceTestSubmodule`) — the
  expected commit hash is hard-coded and `processResources` fails loudly if the
  submodule drifts, so a fixture bump is always a deliberate, reviewed change.
- **Acceptance DSL: action objects vs condition objects.** `*Transactions` (do a
  thing) are separated from `*Conditions` (assert a thing), and both hang off the
  base class — this is what makes tests read declaratively and share plumbing.

## Notable patterns (the reusable idea)

**The single most transferable pattern: a generic JSON-fixture → parameterized-test
engine (`JsonTestParameters<S,T>`) plus build-time template expansion that shards a
huge fixture corpus into named, filterable, skip-not-fail test units.** The three
pillars fukuii should mirror:

1. **Deserialize-once spec POJOs** — one Jackson/circe-mapped type per fixture schema
   (state vs blockchain vs transaction), so the runner code is pure semantics.
2. **`assumeTrue`/skip instead of fail for out-of-scope cases** — an unsupported fork
   or an intentionally-ignored slow/OOM test is *skipped*, keeping the green bar
   meaningful (fukuii's `pending`/`ignore` + tagged suites are the ScalaTest analog).
3. **Fork identity carried in the test name** so a network-specific run
   (`--tests "*_prague_*"`) needs no special harness — directly relevant to fukuii's
   PoW-vs-PoS / multi-network conformance, where ETC (Olympia) and ETH (Osaka)
   fixtures must be selectable independently.

Secondary transferable idea: the **acceptance `Cluster` DSL** — an `AutoCloseable`
that boots N real nodes, elects a bootnode, awaits peer discovery, and `verify`s a
condition across all of them — is the ready-made shape for enterprise/consortium
multi-node validation (BFT/permissioned networks), which is exactly fukuii's private-
network go-to-market lens.

## Authority note

besu = **JVM conformance-testing structural reference** — the closest architectural
mirror to fukuii's JVM/ScalaTest world, so its *harness structure* (source-set
tiering, fixture code-gen, `JsonTestParameters` engine, cluster DSL) is what to
borrow. It is **not** the semantic oracle: the fixtures themselves come from
`ethereum/tests` + `execution-spec-tests`, whose canonical values are produced by
**go-ethereum's `t8n`** state-transition tool (the reference oracle). For PoW/ETC
correctness the authority remains **core-geth**; besu's PoW paths are pre-merge
remnants, not the ETC authority. Use besu for *how to structure the tests*, not
*what the answers are*.

## Gotchas / anti-patterns / things they later changed

- **OOM is expected, not a bug.** `REFERENCE_TESTS.md` explicitly tells you to bump
  `-Xmx8g`; specific pathological fixtures are hard-ignored in the tools' static
  blocks (`static_Call1MB1024Calldepth`, `ShanghaiLove_.*`, `CALLBlake2f_MaxRounds.*`,
  `loopMul-.*`). Lesson: budget an ignore list for adversarial/slow vectors.
- **Duplicate-coverage cleanup.** `/stEIP2537/` and blockchain `stEOF` fixtures are
  ignored because the same ground is now covered by `execution-spec-tests`
  (`eip2537_bls_12_381_precompiles`). Legacy `ethereum/tests` and the newer
  execution-spec-tests overlap; they prune the old to avoid double-running.
- **Merge broke fork-choice-style blockchain tests.** `UncleFromSideChain_(Merge|Paris|…)`
  is ignored: post-merge the consensus layer chooses the head, so a test that asserts
  EL-side fork choice is "inconclusive." Direct warning for fukuii's dual PoW/PoS
  split — a blockchain-conformance test's validity is fork-family-dependent.
- **Migration debt in the harness itself.** `AcceptanceTestBase` still carries a
  "transition to junit5 is ongoing … supports junit4 format" comment — the tiered
  design predates the current framework and was retrofitted.
- **Two fixture corpora + a devnet channel = version-management burden.** The submodule
  hash pin, the Ivy-repo tarball version (`v5.4.0`), and the devnet version (`bal@v5.6.1`)
  must be bumped in lockstep with `--write-verification-metadata sha256` checksum
  updates; getting one wrong fails `validateReferenceTestSubmodule` or the extract task.
- **Hive is not in-repo.** Besu is exercised by ethereum/hive as an *external* client
  (hive owns the besu adapter/Dockerfile in its own repo); there are no hive adapter
  files under besu here. Contrast fukuii, which vendors its own `hive/fukuii/` adapter
  and 12 `hive-*.yml` CI workflows — fukuii keeps the integration in-tree, besu does not.
