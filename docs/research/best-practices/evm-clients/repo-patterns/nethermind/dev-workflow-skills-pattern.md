# Nethermind — Developer-Workflow Skills Catalog

Source: `.claude/repo-references/clients/nethermind/.agents/skills/` (4 skills, vendored full clone)

Nethermind vendors exactly four Claude Code skills in its repository (this is a genuine
full git clone at `.claude/repo-references/clients/nethermind/`, not a summary or a
partial checkout). Each targets a distinct developer workflow: fixing failing Ethereum
Foundation conformance tests, running gas-repricing benchmarks in CI, auditing C#/.NET
code for resource leaks, and reviewing PR diffs. This document catalogs each skill's full
methodology and gives a concrete, evidence-based verdict on porting it to fukuii.

Every claim below is traceable to a specific file. Where a line number could not be
confirmed by direct read, the citation says "see `<file>`" rather than inventing one.

---

## fix-nethtest — full methodology

**Source:** `.claude/repo-references/clients/nethermind/.agents/skills/fix-nethtest/SKILL.md`

Debugs and fixes tests run through `Nethermind.Test.Runner` — Nethermind's CLI harness
for Ethereum Foundation state tests and blockchain tests. Takes one or more test file
paths as `$ARGUMENTS`.

### Phase 1 — Run the test

The skill first infers the test type from the JSON shape rather than asking the user:
a **blockchain test** has `"blocks"`, `"genesisBlockHeader"`, or `"network"` keys; a
**state test** has `"transaction"` and `"post"` keys (`SKILL.md:27-32`). It then builds
(only if needed) and runs `Nethermind.Test.Runner`:

```bash
dotnet build src/Nethermind/Nethermind.Test.Runner/Nethermind.Test.Runner.csproj -c release --verbosity quiet
dotnet run --project src/Nethermind/Nethermind.Test.Runner/Nethermind.Test.Runner.csproj -c release -- -t -i "<test-file>"
# blockchain tests add -b
dotnet run --project src/Nethermind/Nethermind.Test.Runner/Nethermind.Test.Runner.csproj -c release -- -b -t -i "<test-file>"
```
(`SKILL.md:34-45`)

The runner emits four structured JSON output types (`SKILL.md:47-51`):

| Output | Shape |
|---|---|
| Trace line (per opcode) | `{"pc":N,"op":N,"gas":"0x...","gasCost":"0x...","stack":[...],"depth":N,"opName":"...","error":"..."}` |
| Execution result | `{"output":"0x...","gasUsed":"0x...","time":N,"error":"..."}` |
| State root | `{"stateRoot":"0x..."}` |
| Test result | `[{"name":"...","pass":bool,"fork":"...","stateRoot":"0x..."}]` |

If `pass` is `true`, the skill reports success and stops; otherwise it proceeds to
classification.

### Phase 2 — Classify the failure

The skill reads the test JSON to establish context — target fork (from the `post`
section key), transaction type (legacy/EIP-1559/blob/SetCode via `authorizationList`),
expected state root (noting that an all-zeros hash is a placeholder, not a real
expectation), and which optional `env` fields are present (`currentExcessBlobGas`,
`currentBeaconRoot`, etc.) (`SKILL.md:59-64`).

It then classifies the failure purely from the trace signal (`SKILL.md:68-76`):

| Trace signal | Failure class | Likely cause area |
|---|---|---|
| `"error":"BadInstruction"` on a known opcode | Opcode not available | Spec flag gate or instruction runtime guard |
| `"error":"BadInstruction"` on unknown opcode | Unimplemented opcode | Missing opcode in `EvmInstructions.cs` |
| `"error":"OutOfGas"` | Gas accounting | Gas cost calculation or intrinsic gas |
| `"error":"StackUnderflow"`/`"StackOverflow"` | Stack effect | Usually expected bytecode behavior, not a bug |
| No EVM error but wrong state root | State mismatch | Wrong storage/balance writes or header defaults |
| `"loadFailure"` in result | Parse error | Unsupported JSON field or missing deserialization |
| Block validation fails (blockchain tests) | Header issue | Missing header fields or wrong fork defaults |

### Phase 3 — Root cause analysis (per failure class)

Each failure class gets its own root-cause runbook:

- **BadInstruction failures** (`SKILL.md:81-106`, the most common pattern for new
  forks): identify the opcode from `op`/`opName`; check its registration and spec-flag
  gate in `Nethermind.Evm/Instructions/EvmInstructions.cs`; trace the flag through
  `Nethermind.Core/Specs/IReleaseSpecExtensions.cs` and the fork hierarchy in
  `Nethermind.Specs/Forks/*.cs` (each fork's `Apply(ReleaseSpec spec)` replays from
  root); if the opcode is registered but still fails, check the instruction handler for
  a runtime guard on a header field (e.g. `if (!context.Header.SomeField.HasValue) goto
  BadInstruction;`); finally check the test harness's header construction in
  `Ethereum.Test.Base/GeneralTestBase.cs` (state tests, header initializer ~lines
  115-138) or `BlockchainTestBase.cs` (blockchain tests) — specifically for **type
  checks** (`is Cancun`) used where **spec-flag checks** (`IsEip4844Enabled`) are
  required, since type checks miss subclasses and later forks.

- **State root mismatches with no EVM error** (`SKILL.md:108-123`): first rule out an
  all-zeros placeholder hash; then check conditional header-field defaults, analyze the
  trace for unexpected storage writes/reverts/gas costs, check for missing EIP flags in
  a fork's `Apply()`, and check the test JSON's `config.blobSchedule` override — applied
  via `OverridableReleaseSpec` in `JsonToEthereumTest.LoadSpec()`.

- **Block validation failures** (`SKILL.md:125-129`): check required header fields for
  the target fork, new consensus rules (e.g. EIP-7928 `BlockAccessListHash`), and
  `genesisUsesTargetFork` logic in `BlockchainTestBase.cs`.

- **Load/parse failures** (`SKILL.md:131-135`): check `JsonToEthereumTest.cs` model
  classes and `SpecNameParser.cs` for unsupported fork names.

### Phase 4 — Fix and verify

Apply the minimal fix (spec-flag checks over type checks preferred), re-run, loop back
to Phase 2 on continued failure, and report: root cause (one sentence), fix location
(file:line), verification result (pass/fail + new state root), and whether the expected
hash was real or a placeholder (`SKILL.md:141-151`).

### Key files reference (Nethermind)

| Purpose | Path (relative to `src/Nethermind/`) |
|---|---|
| Test runner CLI | `Nethermind.Test.Runner/Program.cs` |
| State test execution + header | `Ethereum.Test.Base/GeneralTestBase.cs` |
| Blockchain test execution | `Ethereum.Test.Base/BlockchainTestBase.cs` |
| Test JSON parsing | `Ethereum.Test.Base/JsonToEthereumTest.cs` |
| Opcode registration + spec gates | `Nethermind.Evm/Instructions/EvmInstructions.cs` |
| Instruction implementations | `Nethermind.Evm/Instructions/EvmInstructions.*.cs` |
| Spec flag extensions | `Nethermind.Core/Specs/IReleaseSpecExtensions.cs` |
| Fork definitions | `Nethermind.Specs/Forks/*.cs` |
| Fork name parser | `Nethermind.Specs/SpecNameParser.cs` |
| EVM exception types | `Nethermind.Evm/EvmException.cs` |
| Block execution context | `Nethermind.Evm/BlockExecutionContext.cs` |

(`SKILL.md:157-169`)

### Common bug patterns appendix

Seven recurring patterns, each with a description of cause and where to look
(`SKILL.md:171-185`): RLP deserialization (missing optional fields, wrong list/single
decoding, over/under-strict length checks); gas accounting (wrong static/dynamic costs,
missing memory-expansion charges, wrong intrinsic gas); state root mismatch (wrong
storage/balance writes, missing empty-account cleanup per EIP-158, wrong nonce
increments); missing header field defaults for new EIPs; instruction runtime guards
that fail because a header field wasn't set by the test harness; validation errors
(missing OR overly strict); and missing blob-schedule overrides in
`JsonToEthereumTest.LoadSpec()`.

---

### Porting to fukuii: `fukuii-ethtest-triage`

fukuii runs the identical upstream corpus — the `ethereum/tests` repository, vendored as
a submodule (`ets/tests`, referenced at
`/media/dev/2tb/dev/fukuii/src/it/scala/com/chipprbots/ethereum/ethtest/BlockchainTestsSpec.scala:51`)
— through five ScalaTest IT specs under
`src/it/scala/com/chipprbots/ethereum/ethtest/`: `BlockchainTestsSpec`,
`GeneralStateTestsSpec`, `TransactionTestsSpec`, `VMTestsSpec`,
`ExecutionSpecsStateTestsSpec`, run nightly via
`.github/workflows/ethereum-tests-nightly.yml` (`sbt "IntegrationTest / testOnly
com.chipprbots.ethereum.ethtest.*"`, line 80). So the *category* of methodology —
run → classify by trace signal → root-cause runbook per class → fix and re-verify — is
directly portable. **The concrete trace-field vocabulary is not**, and this is the one
part of the port that needs real redesign rather than a search-and-replace.

**What stays the same (the failure taxonomy itself).** fukuii's own EVM error hierarchy
in `src/main/scala/com/chipprbots/ethereum/vm/ProgramError.scala` maps cleanly onto
Nethermind's classes:

| Nethermind trace signal | fukuii equivalent (`ProgramError.scala`) |
|---|---|
| `BadInstruction` on a known-but-gated opcode | `OpCodeNotAvailableInStaticContext(code: Byte)` (line 12) |
| `BadInstruction` on an unknown opcode | `InvalidOpCode(code: Byte)` (line 9) |
| `OutOfGas` | `OutOfGas` (line 15) |
| `StackUnderflow`/`StackOverflow` | `StackUnderflow` / `StackOverflow` (lines 21-22, both `StackError`) |
| Invalid jump destination | `InvalidJump(dest: UInt256)` (line 16) |
| (no direct Nethermind equivalent noted in SKILL.md) | `InvalidCall`, `PreCompiledContractFail`, `RevertOccurs`, `ReturnDataOverflow`, `InvalidCode`, `InitCodeSizeLimit` (lines 24-34) |

This confirms the classification *categories* (opcode-not-available, unimplemented
opcode, gas accounting, stack effect, state-root mismatch, parse/load failure, header
issue) transfer directly — a `fukuii-ethtest-triage` skill can keep Phase 2's
signal-to-class table almost verbatim, substituting fukuii's `ProgramError` subtypes for
Nethermind's string error codes.

**What changes — CLI invocation.** There is no fukuii equivalent of
`Nethermind.Test.Runner` (a standalone CLI taking `-t -i <file>` and emitting JSON to
stdout). fukuii's tests are ordinary ScalaTest `IntegrationTest` specs invoked via sbt.
The closest single-test invocation is:

```bash
sbt "IntegrationTest / testOnly com.chipprbots.ethereum.ethtest.BlockchainTestsSpec"
```

or, for iterating on one specific vector file outside the compiled resources, the
debugging helpers already built into the base spec class
(`src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestsSpec.scala:55-92`):
`runSingleTest(resourcePath, testName)` (run one named test from a resource-bundled
suite) and `runTestFile(filePath)` (run every test in an arbitrary filesystem JSON
file — e.g. a vector checked out fresh from the `ets/tests` submodule, not yet copied
into test resources). A `fukuii-ethtest-triage` skill's "Phase 1 — Run the test" should
target these two helpers directly rather than shelling out to sbt for a single vector,
since they already exist for exactly this purpose.

**What changes — output shape (the actual gap).** This is the finding that most needs
flagging to a maintainer: Nethermind's `Nethermind.Test.Runner` emits a genuine
per-opcode trace (`pc`, `op`, `gas`, `gasCost`, `stack`, `depth`, `opName`, `error` on
every step). fukuii's ethtest harness does **not** produce anything like this today.

Reading `EthereumTestExecutor.scala` and `EthereumTestHelper.scala` confirms the actual
shape: `EthereumTestExecutor.executeTest` (lines 41-58) calls
`helper.setupAndExecuteTest(...)`, which internally calls
`blockExecution.executeAndValidateBlock(block)(using bc)`
(`EthereumTestHelper.scala:186`) and pattern-matches the result to either commit the
block or `throw new RuntimeException(s"Block execution failed: $execError")` (line 206).
The whole call chain returns `Either[String, TestExecutionResult]` — a single flat
error *string* (e.g. `"Balance mismatch for $addressHex: expected $expectedBalance, got
${account.balance}"`, `EthereumTestExecutor.scala:134`, or `"Failed to execute blocks:
${e.getMessage}\n${e.getStackTrace.take(10).mkString(...)}"`,
`EthereumTestHelper.scala:230`) — never a structured per-step trace. No
`ExecutionTracer` is attached anywhere in this harness.

The good news: the trace *infrastructure* to close this gap already exists elsewhere in
fukuii, just not wired into the test harness. `src/main/scala/com/chipprbots/ethereum/vm/StructLogTracer.scala`
defines exactly the field set Nethermind's runner emits — in fact it says so explicitly
in its own doc comment: *"matching go-ethereum's structLog format"* (line 10) — a
`StructLog` case class with `pc: Int`, `op: String`, `gas: GasAmount`, `gasCost: BigInt`,
`depth: Int`, `stack: Seq[BigInt]`, `memory: Option[Seq[String]]`, `storage:
Option[Map[String,String]]`, `error: Option[String]` (lines 11-21). It's an
`ExecutionTracer` implementation used today for the `debug_traceTransaction` JSON-RPC
method (via `DebugTracingService.scala`), completely disconnected from the ethtest IT
specs.

**Concrete design for `fukuii-ethtest-triage`:**

1. **Phase 1 (run)** — call `EthereumTestsSpec.runSingleTest` /
   `runTestFile` for the target vector rather than the full sbt `testOnly` sweep; both
   already exist for single-vector debugging.
2. **Phase 1 addendum (the actual new work)** — wire a `StructLogTracer` instance into
   `EthereumTestHelper.setupAndExecuteTest` (or a debug-only variant of it) so a failing
   test can re-run with per-opcode tracing attached, instead of only ever seeing the
   final flat error string. This is new code, not a skill-authoring task — flag it as a
   prerequisite, not an assumption the skill can paper over.
3. **Phase 2 (classify)** — keep Nethermind's signal-to-class table, substituting
   `ProgramError` subtypes (table above) for Nethermind's string codes, and substituting
   `StructLog.error: Option[String]` for Nethermind's `"error":"BadInstruction"` field
   once (2) exists. Until (2) exists, Phase 2 in fukuii can only classify from the flat
   error string (regex against `"Balance mismatch"`, `"Block execution failed"`, etc.) —
   materially weaker than Nethermind's opcode-level signal, and worth stating plainly in
   the skill body rather than pretending parity.
4. **Phase 3 (root cause runbooks)** — retarget the file pointers: fork/spec dispatch
   lives under fukuii's own `OlympiaOpCodes`/`forBlock()` (PoW) and
   `OsakaOpCodes`/`forTimestamp()` (PoS) split (see root `AGENTS.md`), not a single
   linear fork chain; opcode implementations live in fukuii's `vm/` package (parallel to
   `Nethermind.Evm/Instructions/`); the test-harness header-construction equivalent is
   `EthereumTestHelper.createParentBlockHeader` (lines 232-256) and
   `TestConverter.toBlockHeader` — check these for the "type check vs spec-flag check"
   anti-pattern Nethermind calls out (`GeneralTestBase.cs` lines 115-138 in Nethermind's
   analogue).
5. **Key files table** — rewrite for fukuii's actual layout: test runner entry points →
   `EthereumTestsSpec.scala` (`runSingleTest`/`runTestFile`); test execution → 
   `EthereumTestExecutor.scala`, `EthereumTestHelper.scala`; test JSON parsing →
   `EthereumTestsAdapter.scala`; EVM error types → `vm/ProgramError.scala`; opcode
   implementations → fukuii's `vm/` instruction set; fork dispatch → `OlympiaOpCodes`
   (PoW) / `OsakaOpCodes` (PoS).

**Bottom line:** the skill is portable as a *methodology skeleton* today (run → classify
→ root-cause → fix), but full parity with Nethermind's Phase 2/3 granularity is gated on
wiring `StructLogTracer` into the ethtest harness first — that's a real code change, not
a skill-writing exercise, and should be sequenced before or alongside authoring
`fukuii-ethtest-triage`.

---

## gas-benchmark — full methodology

**Source:** `.claude/repo-references/clients/nethermind/.agents/skills/gas-benchmark/SKILL.md`

An end-to-end CI/CD pipeline skill: build a diagnostics-enabled Docker image, trigger
Nethermind's `gas-benchmarks` repricing workflow in a *separate* GitHub repository
(`NethermindEth/gas-benchmarks`), poll for completion, and analyze results including
dotTrace XML profiler reports.

### Interactive mode

When invoked with no arguments, the skill does not fall back to defaults — it walks the
user through five questions: which release (`gh api
repos/NethermindEth/gas-benchmarks/releases`), which Docker image (existing tag or build
from branch), which network (`perf-devnet-3`/`jochemnet`/`mainnet`), which test filter
(discovered interactively by downloading and listing the release's test archive), and
whether to enable dotTrace profiling (`SKILL.md:40-84`).

### Argument parsing

A full flag set: `--branch`, `--image`, `--filter`, `--network` (default
`perf-devnet-3`), `--fork` (default `amsterdam`), `--dottrace`, `--gas-size` (default
`100M`), `--no-restart`, `--release`, `--gas-benchmarks-ref`, `--analyze-run`,
`--compare` (`SKILL.md:90-105`).

### Analyze-only mode (`--analyze-run`)

The primary CI-integration mode: skip Phases 0-3 entirely, fetch run metadata for an
existing run ID/URL, poll if still in progress, and jump straight to Phase 4 analysis —
this is what a CI pipeline calls after it has already triggered the workflow itself
(`SKILL.md:107-120`).

### Phase 0 — Discover release and branch

Five sub-steps: resolve the release tag (matching the fork name, verified to have data
for the requested network); find the `gas-benchmarks` branch that generated the release
(parsed from the release notes' `**Branch:**` field, with a branch-listing fallback);
discover the workflow's actual input parameters by fetching and parsing the workflow
YAML from that branch (so the skill never passes an input the workflow doesn't accept);
map network name to genesis filename; and confirm the fully resolved configuration with
the user before proceeding (`SKILL.md:122-193`).

### Phase 1 — Docker image

Skipped if `--image` given. Otherwise: determine branch, pick `Dockerfile.diag` (if
dotTrace requested) or plain `Dockerfile`, compute a sanitized tag, trigger
`publish-docker.yml` via `gh workflow run`, locate the resulting run by timestamp (to
avoid a race between triggering and listing runs), poll to completion, and fail loudly
on build failure (`SKILL.md:195-226`).

### Phase 2 — Trigger repricing workflow

Builds the `gh workflow run repricing-nethermind.yml` invocation using only the inputs
the target workflow actually declares (from Phase 0's discovery step). Two filter rules
worth calling out: the gas-size constraint (`benchmark_100M` by default) is *always*
auto-appended to the user's filter with `and` logic unless the user explicitly asks for
all gas sizes (`SKILL.md:233-240`); and `restart_before_testing=true` is passed by
default for stateful tests unless `--no-restart` is given, to guarantee clean
measurements per test (`SKILL.md:242`). It explicitly warns: never pass
`diagnostics_mode=dottrace` against a non-diag image — the container crashes with `exec:
dottrace: not found` (`SKILL.md:266`).

### Phase 3 — Wait for completion

Same timestamp-based run-ID lookup pattern as Phase 1, polling every 30 seconds with a
2-hour (240-poll) timeout (`SKILL.md:270-280`).

### Phase 4 — Analyze results (marked MANDATORY, never skippable)

The skill is explicit that a "success" workflow conclusion does **not** mean the run was
clean — exceptions can occur mid-run without failing the workflow (`SKILL.md:284`). Five
sub-phases:

- **4a. Exception scan** — fetch job logs, strip ANSI codes, grep for
  `Exception|Invalid Block|InvalidBlock|Rejected invalid` while excluding known noise
  (`node-exporter`, `pip install`, etc. — explicitly *not* excluding `dotnet`, since real
  Nethermind exceptions contain .NET runtime frames). Classifies specific exception
  strings (`HeaderGasUsedMismatch` → gas schedule mismatch; `InvalidBlockLevelAccessListHash`
  → BAL pre-state corruption; etc.) and always reports "Exceptions: none" explicitly when
  clean, plus confirms the `Nethermind is shut down` marker (`SKILL.md:286-307`).
- **4b. Timing analysis** — explicitly forbids parsing `Processed` lines from raw logs
  (block numbers repeat across restart-before-testing cycles, making log correlation
  unreliable) and instead downloads the results *artifacts*, extracting
  `engine_newPayloadV5` average timing per test file, then computing
  COUNT/AVG/MEDIAN/P90/P95/MAX via `awk` (`SKILL.md:309-343`). A `4b-compare` variant
  does the same for two runs side by side, sorted by percentage delta.
- **4c. Block stats** — sload/sstore/create counts per heaviest test block.
- **4d. Opcode tracing comparison** — only when comparing two runs, downloads each
  release's `opcodes_tracing-*.json` to confirm both runs exercised an identical
  workload.
- **4e. dotTrace analysis** — downloads the dotTrace XML artifact (when
  `--dottrace` was used), and is explicit that these files are 50-70MB and must **never**
  be loaded into context directly — always go through
  `scripts/dottrace-report.sh top|compare <xml> [N]`, which reports OwnTime/TotalTime
  per function in under 2 seconds via grep+awk (`SKILL.md:394-439`). Includes an
  interpretation guide mapping hot namespaces to bottleneck types (`Nethermind.Trie`/
  `Nethermind.Db.Rocks` → storage; `RocksDbSharp` → disk I/O; `Nethermind.Evm.VirtualMachine`
  → EVM overhead; `System.GC`/`JIT_New` → allocation pressure).

### Phase 5 — Report

A fixed report shape: block-phase breakdown table (gas-bump / setup / testing block
ranges), a prominent warning if zero testing blocks were found (release/filter mismatch),
a summary metrics table, and the top-10 heaviest test blocks (`SKILL.md:441-480`).

### CI integration

`.github/workflows/gas-benchmark-analysis.yml` runs the *entire* pipeline (build →
trigger → wait → analyze) via Claude Code and posts results as a PR comment, gated to
`NethermindEth/core` team members. Three trigger surfaces: a `@claude-bench` PR comment
(full run or `--analyze-run`/`--compare` variants), manual `gh workflow run
gas-benchmark-analysis.yml` dispatch, and repository-dispatch from the `gas-benchmarks`
repo itself (`SKILL.md:482-520`).

---

### Porting to fukuii: `fukuii-benchmark-diff` (local-only subset)

fukuii already has real timed benchmark infrastructure — a dedicated sbt `Benchmark`
configuration (`build.sbt:240`: `val Benchmark = config("benchmark").extend(Test)`,
wired into the project's `.configs(...)` at line 287 and given its own test settings at
line 343) with two concrete ScalaTest suites:

- `src/benchmark/scala/com/chipprbots/ethereum/mpt/MerklePatriciaTreeSpeedSpec.scala` —
  a plain `AnyFunSuite` (not a runnable `main` class) tagged `BenchmarkTest`
  (`com.chipprbots.ethereum.testing.Tags.BenchmarkTest`, line 20). Two tests: an
  in-memory 1000-round MPT insert benchmark that logs `"Time taken(ms): "` and the
  resulting root hash via `log.info` (lines 20-47, asserting a fixed root-hash
  fingerprint as a regression check), and an RocksDB-backed 20,000,000-insert benchmark
  logging progress every 100,000 inserts as `"=== $i elements put, time for batch is:
  $delta sec"` (lines 49-74).
- `src/benchmark/scala/com/chipprbots/ethereum/rlp/RLPSpeedSuite.scala` — also a plain
  `AnyFunSuite` (no `BenchmarkTest` tag on its two tests, notably — this is a gap in
  tagging consistency worth flagging separately, not part of this porting design), with
  block/transaction (de)serialization throughput logged as `"... serializations / sec:
  (...)"` (lines 27-48) and a 10,000,000-iteration RLP decode timing logged as `"Result
  decode()\t: ... ms"` (lines 51-61).

Both suites report timing purely via **log lines emitted through fukuii's own `Logger`
trait** (`log.info(...)`/`log.debug(...)`) — there is no structured JSON output, no
percentile computation, and no artifact file comparable to Nethermind's
`results-1-nethermind-<test>.zip`. This is the load-bearing fact for scoping the port:
Nethermind's Phase 4b timing-analysis approach (download artifact, extract
`engine_newPayloadV5` averages, compute COUNT/AVG/MEDIAN/P90/P95/MAX via `awk`) has
**no** artifact to parse in fukuii today — the only output is stdout/log-file text.

**Exact invocation.** The `Benchmark` sbt configuration is reached via the `benchmark`
scope, e.g.:

```bash
sbt "benchmark:testOnly com.chipprbots.ethereum.mpt.MerklePatriciaTreeSpeedSpec"
sbt "benchmark:testOnly com.chipprbots.ethereum.rlp.RLPSpeedSuite"
```

(consistent with `build.sbt`'s `val Benchmark = config("benchmark").extend(Test)` at
line 240 and the `Benchmark / compile` alias referenced at line 421; see `build.sbt` for
the authoritative alias list — this doc does not invent an alias that isn't confirmed
there). Full-suite compliance runs exclude `BenchmarkTest` from the default tiers (line
533 comment: *"BenchmarkTest/EthereumTest: the 3-hour compliance suite — belongs in
testComprehensive only"*, and line 537's `testOnly -- -l BenchmarkTest -l EthereumTest -l
SyncTest -l DisabledTest` excludes it from the default run) — so these benchmarks are
opt-in, not part of `testEssential`/`testStandard`.

**Concrete design for `fukuii-benchmark-diff` (local-only subset):**

1. **Run** — invoke the `benchmark:testOnly` target for the specific suite(s) requested,
   capturing stdout (sbt's own test output already includes the suite's `log.info`
   lines interleaved with ScalaTest pass/fail markers).
2. **Extract** — since output is unstructured log text, extraction is suite-specific
   regex, not a generic artifact parser:
   - MPT: `Time taken\(ms\): (\d+)` and `=== (\d+) elements put, time for batch is: ([\d.]+) sec`
   - RLP: `(Block|TX) (de)?serializations / sec: \(([\d.]+)\)` and `Result decode\(\)\s*: (\d+)ms`
3. **Store a baseline** — write the extracted numbers plus a timestamp/git-SHA to a
   local file (e.g. `.local/benchmarks/<suite>-<sha>.json`) rather than a GitHub Actions
   artifact — there is no equivalent of Nethermind's cross-repo `gas-benchmarks` release
   pipeline to store results externally.
4. **Diff two runs** — run the suite on two refs (e.g. base branch and current branch,
   sequentially — never in parallel, per this machine's resource-management rule of one
   heavy task at a time), and report a simple before/after percentage delta per metric,
   mirroring Nethermind's Phase 4b-compare table shape (metric | before | after | delta%)
   but with far fewer rows (a handful of logged numbers vs. per-test-file granularity).

**Explicitly deferred — do not attempt to port these:**

- **The Docker build + remote-workflow-trigger superstructure (Phases 0-3)**. fukuii has
  no `gas-benchmarks`-equivalent external repository, no `perf-devnet-3`/`jochemnet`
  networks, and no `publish-docker.yml`/`repricing-nethermind.yml` pair to orchestrate.
  Building this is a multi-week infrastructure project (a separate benchmark-fixture
  generator, a devnet, a Docker publishing pipeline), not a skill-porting task.
- **dotTrace / profiler-report analysis (Phase 4e)**. There is no .NET-profiler
  equivalent wired into the JVM/Scala toolchain here; a future version could target
  `async-profiler` or JFR (Java Flight Recorder) output instead, but that is new
  tooling work, not a direct port of `dottrace-report.sh`.
- **CI integration via a PR-comment bot** (`@claude-bench` equivalent). This overlaps
  with fukuii's own planned CI-review-bot design — see
  `nethermind/agentic-tooling-pattern.md` for that track; do not duplicate the design
  here.

**Bottom line:** only a small, genuinely local "run two suites, diff the log numbers"
slice of `gas-benchmark` is portable today. The bulk of the skill's value (remote
workflow orchestration, artifact-based percentile analysis, dotTrace hot-spot diffing)
depends on infrastructure fukuii does not have and that is out of scope for a skill port
by itself.

---

## resource-leak-audit — status: ALREADY PORTED

**Source:** `.claude/repo-references/clients/nethermind/.agents/skills/resource-leak-audit/SKILL.md`
and `.../resource-leak-audit/references/pattern-categories.md`

This skill has already been ported to fukuii this session as
`.agents/skills/pekko-resource-audit/SKILL.md` (confirmed present and read in full).

Nethermind's original audits C#/.NET code across a four-tier severity-agnostic category
list (`references/pattern-categories.md`): Tier 1 high-frequency accumulating leaks
(CancellationTokenSource, `ArrayPool` rent/return, ref-counted network buffers,
uncompleted `TaskCompletionSource`, event-handler `+=`/`-=` mismatches); Tier 1.5
cross-cutting lifecycle/coordination bugs (broken async handshakes, shutdown-ordering
races in `Task.WhenAll`, ignored `CancellationToken` parameters, double-dispose,
ownership ambiguity, interfaces severing disposal chains); Tier 2 error-path/shutdown
leaks (HTTP/Stream/DB/Timer/synchronization-primitive disposables, `Channel<T>`,
`IEnumerator<T>`, `Process`, sockets, crypto handles); and Tier 4 native/diagnostic
edge cases (`GCHandle`, `Marshal.AllocHGlobal`, `ThreadLocal`, abandoned tasks,
finalizer-queue pressure, `async void`). The methodology itself is a two-phase,
gated audit: Phase 1 exhaustive breadth-first search across every category (forward
search from construction, backward search for safe-wrapper bypass, disposal-site
audit) with a mandatory impact assessment before any finding is recorded, followed by a
convergence checkpoint, then Phase 2 deep validation (triggerability proof, adversary
analysis, protocol context, existing-work check via `gh search`) gated to CRITICAL/HIGH
findings only, with MEDIUM/LOW findings surfaced but left un-validated unless the user
opts in.

**What was kept vs. adapted.** `pekko-resource-audit` keeps the *methodology* almost
exactly: the same two-phase gated structure (exhaustive breadth-first search, then
validation gated to CRITICAL/HIGH), the same "check every match, not a sample" and
mandatory sibling-expansion discipline, the same self-critique checklist before
recording any finding ("does anything actually accumulate," "is the caller actually
concurrent," "is the trigger actually reachable"), and the same output format shape
(finding template with File/Line/Category/Severity/Frequency/Impact/Fix-complexity,
final triage-by-reachability + COSMETIC section). What was **replaced wholesale** is the
category list itself, since fukuii has no C#/.NET runtime at all: Nethermind's
`IDisposable`/`CancellationTokenSource`/`ArrayPool`/finalizer-queue vocabulary becomes
five Pekko-native categories in `pekko-resource-audit/SKILL.md` — uncancelled
`Behaviors.withTimers` handles, missing `context.watch`/`watchWith` cleanup on
short-lived-watched-by-long-lived relationships, stream-materialization leaks
(`preMaterialize()` anti-pattern, or a `Source`/`Flow` rebuilt per-request instead of
built once), dispatcher starvation from `Await.result`/`Await.ready` inside actor
message handling, and per-request child-actor leaks with no corresponding stop. Each
category is explicitly cross-referenced to an existing protocol entry
(`pekko-typed-api.md` P1/P9/P12/P26) rather than re-explaining the idiom, since — per
the ported skill's own framing — "this skill is the audit *methodology* that finds
violations of those idioms," not a restatement of what the idiom is.

See `.agents/skills/pekko-resource-audit/SKILL.md` for the full ported skill.

---

## review — see agentic-tooling-pattern.md

Nethermind's `review` skill (deep PR-diff review covering consensus correctness,
security, robustness, performance, DI patterns, API/breaking changes, and observability)
informed fukuii's planned CI-review-bot design. That design is documented separately at
`docs/research/best-practices/evm-clients/repo-patterns/nethermind/agentic-tooling-pattern.md`
— refer there rather than duplicating the analysis in this file.

---

## Fukuii verdict summary table

| Skill | Port now / Needs design / Already ported | Reasoning |
|---|---|---|
| `fix-nethtest` | **Needs design** (methodology portable now; full parity gated on a code change) | The run→classify→root-cause→fix skeleton and the failure taxonomy transfer directly (fukuii's `ProgramError` hierarchy maps cleanly onto Nethermind's `BadInstruction`/`OutOfGas`/stack-error classes). But fukuii's ethtest harness (`EthereumTestExecutor.scala`, `EthereumTestHelper.scala`) returns only a flat `Either[String, TestExecutionResult]` error string — no per-opcode trace exists in that path today. `StructLogTracer.scala` already implements the exact go-ethereum-compatible field set Nethermind's runner emits, but it is wired only into `debug_traceTransaction`, not the ethtest specs. Wiring it in is a prerequisite code change, not a skill-authoring task. |
| `gas-benchmark` | **Needs design** (small local subset portable now; the rest is out of scope) | fukuii's `Benchmark` sbt module (`build.sbt:240`) with `MerklePatriciaTreeSpeedSpec`/`RLPSpeedSuite` gives a genuine local timing-diff workflow to port (`fukuii-benchmark-diff`), but output is unstructured `log.info` text, not a JSON/artifact pipeline. The Docker-build + remote-workflow-trigger + dotTrace superstructure (Phases 0-3, 4e) depends on infrastructure fukuii doesn't have (no `gas-benchmarks`-equivalent repo, no devnets, no .NET profiler) and is out of scope for a skill port. |
| `resource-leak-audit` | **Already ported** | Live at `.agents/skills/pekko-resource-audit/SKILL.md`. Methodology (two-phase gated audit, self-critique, output format) kept nearly verbatim; category list fully replaced (C#/.NET `IDisposable`/CTS/ArrayPool taxonomy → Pekko timers/watch/stream-materialization/dispatcher-starvation/child-actor-leak taxonomy), cross-referenced to existing `pekko-typed-api.md` protocol entries rather than re-explained. |
| `review` | **See cross-reference** | Informed fukuii's planned CI-review-bot; full design lives in `agentic-tooling-pattern.md`, not duplicated here. |
