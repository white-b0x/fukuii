# nethermind — testing
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind's test surface splits into three concentric rings:

1. **Unit / integration tests** — one `*.Test` NUnit project per production project
   (`Nethermind.Blockchain.Test`, `Nethermind.Consensus.Test`, `Nethermind.Evm.Test`,
   `Nethermind.Core.Test`, `Nethermind.TxPool.Test`, `Nethermind.Synchronization.Test`,
   `Nethermind.Network.Test`, `Nethermind.JsonRpc.Test`, `Nethermind.Merge.Plugin.Test`,
   the AuRa/Clique/Optimism/Taiko/Xdc plugin `.Test` projects, …). Convention is strict
   1:1 mirroring: production `Foo` → test `Foo.Test`. All share a single `tests.props`
   MSBuild import that turns each into a self-executing NUnit runner
   (`<EnableNUnitRunner>true</EnableNUnitRunner>`, `<OutputType>Exe</OutputType>`) and
   pulls in the repo's own Roslyn `Nethermind.Test.Analyzers` as an analyzer.

2. **Reference-test consumers** (`Ethereum.*.Test`) — the official
   [ethereum/tests](https://github.com/ethereum/tests) and EEST (execution-spec-tests /
   "pyspec") fixture suites, turned into NUnit parameterized tests. This is the
   conformance ring and the focus of this doc.

3. **Standalone CLI runners** — `Nethermind.Test.Runner` (`nethtest`, the t8n-equivalent
   state/blockchain oracle consumer, driven by stdin/flags, JSON in → JSON out),
   `Nethermind.Blockchain.Test.Runner` (an interactive perf/"bug hunter" REPL), and the
   in-tree hive adapter (`Nethermind.Hive`, a plugin that replays hive-supplied blocks
   through the real block-processing pipeline).

Benchmarks are a fourth, separate ring: `*.Benchmark` projects driven by BenchmarkDotNet
via `Nethermind.Benchmark.Runner`.

## Key types / interfaces / files

### Fixture → NUnit plumbing (the loader)
- `Ethereum.Test.Base/ITestLoadStrategy.cs` + `TestLoadStrategy.cs` — strategy that walks a
  fixtures directory and deserializes JSON into `EthereumTest` subtypes.
- `Ethereum.Test.Base/TestsSourceLoader.cs:14` — wraps a load-strategy; every `LoadTests`
  result is piped through `TestChunkFilter.FilterByChunk` before it reaches NUnit.
- `Ethereum.Test.Base/GeneralStateTest.cs`, `BlockchainTest.cs`, `TransactionTest.cs` —
  the in-memory test models; `JsonToEthereumTest.cs` + the `*Json.cs` DTOs do the decode.
- `Ethereum.Test.Base/GeneralStateTestBase.cs:57` (`RunTest`) and
  `BlockchainTestBase.cs:65` (`RunTest`) — the execution harness: builds an Autofac
  container (`TestNethermindModule`), a `CustomSpecProvider` pinning genesis→Frontier and
  block-1→the test's target fork, processes, and asserts the resulting **state root**.
- `Ethereum.Test.Base/StateTestFixture.cs` / `BlockchainTestFixture.cs` — generic NUnit
  base fixtures. The load happens in a **`static` `[TestCaseSource(nameof(LoadTests))]`**
  method (NUnit requires the source be static and on the method's declaring type), so each
  JSON fixture becomes one parameterized NUnit case.
- `Ethereum.Test.Base/TestDirectoryHelper.cs` — derives the fixtures subdirectory from the
  test **class name** by convention (prefix `st`/`bc`/`vm` + class name, `_`→`-`), so a
  class `EIP1559 : BlockchainTestFixture<EIP1559>` automatically loads `bcEIP1559/`.
- `Ethereum.Test.Base/DirectoryMetaTests.cs` (`All_categories_are_tested`) — a **meta-test**
  that scans the fixtures dir and fails if any `bc*`/`st*` directory has no matching test
  class. Prevents silently-unrun fixture categories.

### Two fixture-provisioning mechanisms
- **Legacy = git submodule.** `.gitmodules` pins `src/tests` →
  `github.com/ethereum/tests`. Legacy projects copy fixtures into the test output via MSBuild
  `<Content Include="..\..\tests\BlockchainTests\ValidBlocks\bc*\*.*">` (see
  `Ethereum.Blockchain.Block.Test/Ethereum.Blockchain.Block.Test.csproj`), consumed by
  `LoadLegacyBlockchainTestsStrategy` / `LoadLegacyGeneralStateTestsStrategy`.
- **Modern = downloaded archive.** `Ethereum.Blockchain.Pyspec.Test/LoadPyspecTestsStrategy.cs`
  + `Ethereum.Test.Base/TestFixtureDownloader.cs` fetch a versioned EEST tarball
  (`Constants.cs`: `execution-specs` release `tests-bal@v7.3.2`, `fixtures_bal.tar.gz`) to a
  temp cache guarded by a named `Mutex` + `.completed` marker for cross-process safety.
  This decouples fixture version from the source checkout — a pinned archive tag, not a
  submodule bump.

### CLI reference-oracle runner (`nethtest`)
- `Nethermind.Test.Runner/Program.cs` — `System.CommandLine` CLI: `--input`/`--stdin`,
  `--filter` (regex on test name), `--blockTest` vs default state test, `--trace`
  (`WhenTrace.WhenFailing|Always|Never`), `--gnosisTest` (chain-id switch).
- `Nethermind.Test.Runner/StateTestRunner.cs` (`StateTestsRunner : GeneralStateTestBase`) —
  runs each state test, emits `EthereumTestResult[]` JSON on stdout and an EIP-3155-style
  per-op `StateTestTxTrace` on stderr **only for failing tests** (or always with `-t`). This
  is nethermind's answer to geth's `evm statetest`.

### In-tree hive adapter
- `Nethermind.Hive/HivePlugin.cs` — an `INethermindPlugin` (`Enabled => hiveConfig.Enabled`)
  that registers `HiveRunner` and a `HiveStep` into the normal node startup, and relaxes two
  policies for the simulator (`FilterPeersByRecentIp = false`, tx-gossip policy cleared).
- `Nethermind.Hive/HiveRunner.cs` — reads `HIVE_*` env vars (fork activation blocks/timestamps,
  including every BPO1–5 blob schedule), loads `chain.rlp` + a blocks dir, and suggests each
  block through the **real** `IBlockTree` / `IBlockProcessingQueue` / `IBlockValidator`,
  waiting on a `SemaphoreSlim` for each block's processing result. So hive exercises the
  production import path, not a test double.
- `Nethermind.Hive.Test/HivePluginTests.cs` — thin unit coverage of the plugin wiring.

## Design decisions & rationale

- **Convention-over-configuration fixture wiring.** A one-line class
  (`public class EIP1559 : BlockchainTestFixture<EIP1559>;`) is enough to mount an entire
  fixture directory, because `TestDirectoryHelper` reverse-maps class name → directory and
  `DirectoryMetaTests` guarantees the mapping is total. Adding a fixture category is a
  one-liner and forgetting one is a test failure.
- **State root is the oracle.** Both harness bases assert the post-execution state root
  (and, for blockchain tests, block validity + expected-RLP-exception matching in
  `SuggestBlocks`). go-ethereum's t8n produces the fixtures; nethermind is a *consumer*
  that must reproduce the same root byte-for-byte.
- **Fixture provisioning is versioned, not vendored, for EEST.** Downloading a pinned
  release archive (vs. a submodule) lets the pyspec suite track fast-moving execution-spec
  releases without bloating the repo or coupling to a submodule SHA.
- **CI parallelism via chunking.** `TestChunkFilter` (`Nethermind.Core.Test/TestChunkFilter.cs`)
  reads `TEST_CHUNK=<i>of<N>` and interleaves tests across N shards (`test[i] → i % N`, so
  heavy tests don't cluster), using an FNV-1a **stable** hash so shard membership is
  identical across processes/platforms. Applied centrally in `TestsSourceLoader`, so every
  reference-test project shards for free.
- **Trace-on-failure by default.** The `nethtest` runner and `StateTestsRunner` only emit
  expensive per-opcode traces for tests that fail, keeping the common (passing) path fast.

## Notable patterns (the reusable idea)

- **Static `[TestCaseSource]` + a load strategy = one NUnit case per JSON fixture.** The
  loader (`TestsSourceLoader` → `ITestLoadStrategy`) is the seam; the fixture base classes
  are thin. This is the single most transferable pattern for fukuii: fukuii already runs
  ethereum/tests via ScalaTest — the analogue is a `TestCaseSource`-style generator
  (`Table`/`forAll` or a custom `Suite` that enumerates fixture files) feeding one test per
  vector, backed by a pluggable loader so legacy-submodule and downloaded-EEST fixtures
  share one execution path.
- **A meta-test that fails on un-mounted fixture directories** (`DirectoryMetaTests`) — a
  cheap guard fukuii can copy to ensure no reference-test category is silently skipped.
- **Deterministic, env-driven CI sharding** (`TEST_CHUNK=NofM` + stable hash) applied at the
  loader layer — orthogonal to the test framework, directly portable to fukuii's tiered
  ScalaTest suites.
- **Runner-mode skip guards** (`CiRunnerGuard` in `PyspecTestFixture.cs`): heavy generated
  shards run everywhere locally but `Assert.Ignore` in CI off Linux-x64, and honor
  `TEST_SKIP_HEAVY=1` — a clean way to keep a big conformance matrix affordable in CI while
  staying fully runnable locally. Maps onto fukuii's `[Explicit]`-equivalent tiering
  (`testEssential`/`testStandard`/`testComprehensive`).
- **Hive as a plugin over the real pipeline, not a bespoke server.** `HivePlugin`
  piggybacks on normal node startup and replays blocks through the production
  `IBlockProcessingQueue`, so the interop harness tests the same code the node runs.

## Authority note

go-ethereum is the t8n reference oracle that *produces* the state/blockchain fixtures
(via `evm t8n`, consumed into the ethereum/tests + EEST suites). nethermind here is a
**C#/NUnit reference-test consumer** plus `nethtest` (its `Nethermind.Test.Runner`
state/block oracle-consumer CLI) plus an **in-tree hive adapter** (`Nethermind.Hive`).
For PoW/ETC-specific conformance, core-geth — not go-ethereum — is fukuii's authority;
nethermind's value here is *structural* (how a C# client mechanizes fixture consumption
and CI sharding), not as an ETC consensus oracle.

## Gotchas / anti-patterns / things they later changed

- **Two parallel fixture worlds.** Legacy (`Ethereum.Legacy.*`, submodule-backed,
  `LoadLegacy*Strategy`) coexists with modern pyspec (`Ethereum.Blockchain.Pyspec.Test`,
  archive-backed). They share `Ethereum.Test.Base` bases but have separate load strategies
  and separate directory conventions — easy to wire a fixture into the wrong one.
- **Genesis-spec assertion is load-bearing.** `GeneralStateTestBase.RunTest` hard-fails if
  the genesis spec isn't Frontier (except Gnosis) — the comment
  (`"took a lot of time to find after it was removed!"`) flags a real past regression where
  removing the Frontier genesis entry silently broke every state test.
- **`Nethermind.Test.Runner` and `Nethermind.Blockchain.Test.Runner` are different tools.**
  The former is the scriptable JSON-oriented `nethtest` oracle; the latter is an interactive
  `P/B` REPL ("PerfStateTest" / "…BugHunter") for local perf/debug and is not a CI gate.
- **Fixture download hits the network at test time.** `TestFixtureDownloader` pulls from a
  GitHub release on first run; the mutex/marker prevent races but an offline or
  rate-limited CI runner will stall up to the 10-minute mutex timeout.
- **`Ethereum.Blockchain.Test` (unprefixed) is empty at this commit** — the general
  blockchain-fixture classes live in `Ethereum.Blockchain.Block.Test` and the legacy
  projects; don't assume the unprefixed project holds the main suite.
- **Chunk math is 1-based** (`iof N`, index 1..N); `TestChunkFilter` throws on malformed
  `TEST_CHUNK`, so a bad CI env var fails loudly rather than silently running everything.
