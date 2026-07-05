---
name: fukuii-ethtest-triage
description: >-
  Triage a failing (or newly-added) ethereum/tests blockchain-test vector under
  src/it/scala/com/chipprbots/ethereum/ethtest/ — run the single vector, on failure
  re-execute the target transaction with a StructLogTracer for a real per-opcode trace,
  classify the failure against fukuii's ProgramError hierarchy, then follow a root-cause
  runbook retargeted at fukuii's PoW (block-number dispatch via `EvmConfig.forBlock`) vs
  PoS (timestamp-overload dispatch, same method) fork split — see `PARITY-02`
  (`.claude/sprints/QUEUE.md`) before assuming which opcode-list object belongs to which
  network. Use when an ethtest vector fails, a new
  fork's vectors need debugging, or asked to "triage this ethtest failure" / "trace this
  ethereum/tests vector". Read-only diagnosis (IT-scope re-execution only, no src/main
  changes, no consensus semantics altered); fixing the root cause is a separate step
  routed through forge/beacon per the consensus-change protocol, not this skill.
disable-model-invocation: true
user-invokable: true
model: sonnet
argument-hint: "resource-path-or-file[:test-name]"
---

# Fukuii ethtest triage

Ported from Nethermind's `fix-nethtest` skill (see
`docs/research/best-practices/evm-clients/repo-patterns/nethermind/dev-workflow-skills-pattern.md`
for the full source methodology and the porting analysis this skill is built from).
Read-only diagnosis: this skill re-executes a transaction against a freshly reconstructed
in-memory world state for tracing purposes only — it never touches `BlockExecution` or any
other production consensus path, and produces no fix by itself.

## Prerequisite (already done, don't redo)

`EthereumTestHelper.reExecuteWithTrace(test, txIndex, blockIndexOpt, enableMemory,
enableStorage)` (`src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestHelper.scala`)
wires a `StructLogTracer` into `StxLedger.simulateTransactionWithTracer` — already
general-purpose, the same method `debug_traceTransaction` uses in production
(`DebugTracingService.scala`). It reconstructs pre-state, replays every full block before
the target block and every prior transaction within the target block (both via the
existing block/tx-execution helpers already used by `setupAndExecuteTest`), then attaches
the tracer to only the target transaction and returns `tracer.getSteps: Seq[StructLog]`
directly. It reads `getSteps`, **not** `tracer.getResult` — `StructLogTracer.getResult`
returns `JNothing` unconditionally today (a separate, already-logged finding in
`conduit`'s domain — `DebugTracingService`/`DebugTracingJsonMethodsImplicits` — do not
conflate the two or attempt to fix `getResult` from this skill).

## Phase 1 — Run the single vector

Don't run a full sbt `testOnly` sweep for one vector. Use the debugging entry points
already built into `EthereumTestsSpec`
(`src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestsSpec.scala:55-92`):

- **A resource-bundled suite** (`src/it/resources/ethereum-tests/*.json`), one named test:
  `runSingleTest(resourcePath: String, testName: String): Either[String, TestExecutionResult]`
- **An arbitrary filesystem JSON file** (e.g. a vector freshly checked out from the
  `ets/tests` submodule, not yet copied into test resources), every test in the file:
  `runTestFile(filePath: String): Seq[(String, Either[String, TestExecutionResult])]`

Both drive the test through the same path `executeTest` uses:
`EthereumTestExecutor.executeTest` → `EthereumTestHelper.setupAndExecuteTest` →
`BlockExecution.executeAndValidateBlock`. If the result is `Right(...)`, report success and
stop — there is nothing to triage.

## Phase 1 addendum — re-run with tracing on failure

On `Left(error)`, the flat error string alone (e.g. `"Block execution failed: ..."`) is not
enough to classify the failure. Re-run just the target transaction with tracing:

```scala
// From an EthereumTestsSpec subclass (or any IT scope with the same suite loaded):
val suite = loadTestSuite(resourcePath)
val test = suite.tests(testName)

given com.chipprbots.ethereum.utils.BlockchainConfig =
  TestConverter.networkToConfig(test.network, baseBlockchainConfig)
val helper = new EthereumTestHelper

// txIndex is 0-based within the target block; blockIndexOpt defaults to the last block
// (the common single-block-vector case) when omitted.
helper.reExecuteWithTrace(test, txIndex = 0) match
  case Right(steps) => steps.foreach(s => println(s"pc=${s.pc} op=${s.op} gas=${s.gas} " +
    s"gasCost=${s.gasCost} depth=${s.depth} stack=${s.stack} error=${s.error}"))
  case Left(err)    => println(s"Could not even reconstruct a trace: $err")
```

If the vector has more than one block or more than one transaction in the target block,
pass the actual failing transaction's position explicitly — don't assume `txIndex = 0`
without checking `test.blocks(blockIndex).transactions`. `enableMemory`/`enableStorage`
default to `true` (see [`StructLogTracer`](../../../src/main/scala/com/chipprbots/ethereum/vm/StructLogTracer.scala)
for the per-step cost of each); pass `false` for either if the trace is too large to read
usefully and only the opcode/gas/stack columns are needed.

A `Left(...)` at this stage (rather than a `Right(steps)`, however short) means the failure
happens *before or during pre-state/prior-block reconstruction itself* — go straight to the
"Load/parse and prior-block failures" runbook below instead of the opcode-level ones.

## Phase 2 — Classify the failure

Once you have `Right(steps)`, find the step(s) with `error.isDefined` — this is normally
the **last** step, since `StructLogTracer` still appends the erroring step before the run
halts. Match `error` against fukuii's `ProgramError` hierarchy
(`src/main/scala/com/chipprbots/ethereum/vm/ProgramError.scala`):

| `StructLog.error` string | fukuii `ProgramError` | Likely cause area |
| :-- | :-- | :-- |
| `InvalidOpCode(0x..)` | `InvalidOpCode(code: Byte)` | Opcode genuinely unimplemented — not registered in `vm/` at all |
| `OpCodeNotAvailableInStaticContext(0x..)` | `OpCodeNotAvailableInStaticContext(code: Byte)` | Opcode registered but gated — spec-flag/fork check wrong for this opcode |
| `OutOfGas` | `OutOfGas` | Gas accounting — a static/dynamic gas cost or intrinsic gas calculation is wrong for the active fork |
| `StackUnderflow` / `StackOverflow` | `StackUnderflow` / `StackOverflow` (both `StackError`) | Usually expected bytecode behavior for the vector, not a bug — confirm against the vector's own `postState`/expected failure before treating as a defect |
| `InvalidJump(dest)` | `InvalidJump(dest: UInt256)` | Invalid `JUMP`/`JUMPI` destination — usually expected bytecode behavior, same caveat as stack errors |
| `InvalidCall` | `InvalidCall` | Call-depth/value-transfer precondition violated |
| `PreCompiledContractFail` | `PreCompiledContractFail` | Precompile execution rejected its input (fukuii-only — no direct Nethermind equivalent) |
| `RevertOccurs` | `RevertOccurs` | Explicit `REVERT` — usually expected, not a bug (`useWholeGas = false`, unlike every other `ProgramError`) |
| `ReturnDataOverflow` | `ReturnDataOverflow` | `RETURNDATACOPY` read past the return-data buffer (fukuii-only) |
| `InvalidCode` | `InvalidCode` | Deployed code failed the EIP-3541 `0xEF` prefix check (or equivalent) (fukuii-only) |
| `InitCodeSizeLimit` | `InitCodeSizeLimit` | EIP-3860 init-code size limit exceeded (fukuii-only) |
| No error on any step, but wrong final state / `postState` mismatch | — (no `ProgramError`) | State mismatch — go to the state-root runbook below, not the opcode runbooks |

**Be explicit about maturity, don't oversell this**: this table gives real per-opcode
signal today (`pc`, `op`, `gas`, `gasCost`, `depth`, `stack`, optional `memory`/`storage`,
`error`) — materially better than the flat error string alone. It is **not** full parity
with Nethermind's `fix-nethtest` on day one: there is no automated classifier script here
(matching is manual, by reading `steps` and this table), no `opName` normalization layer
distinguishing "opcode byte with no name at all" from "opcode byte with a name but gated
off" the way Nethermind's runner pre-labels `BadInstruction`, and no artifact/JSON output
format — `steps` is a plain in-process `Seq[StructLog]`. Treat future automation here
(a small script that scans `steps` and prints the matched row) as a real follow-on
improvement, not something already delivered.

## Phase 3 — Root-cause runbooks (per failure class)

- **Opcode-not-available / gated-opcode failures** (`InvalidOpCode`,
  `OpCodeNotAvailableInStaticContext`) — the most common pattern when a new fork's
  vectors are added. Identify the opcode from `op`/`pc` in the trace. Fork/opcode-set
  dispatch is **split by consensus family** in this codebase — do not check the wrong
  side:
  - **PoW (ETC/Mordor)**: block-number dispatch via `EvmConfig.forBlock(blockNumber,
    blockchainConfig)`, using `EtcOlympiaOpCodes` (`vm/OpCode.scala`, `vm/EvmConfig.scala`)
    — **not** the unprefixed `OlympiaOpCodes`, which ETH's path below actually uses despite
    the ETC-sounding name (see `PARITY-02`, `.claude/sprints/QUEUE.md`).
  - **PoS (ETH/Sepolia)**: timestamp dispatch via the overloaded
    `EvmConfig.forBlock(blockNumber, timestamp, blockchainConfig)` (same method, not a
    separate `forTimestamp()`), using the unprefixed `OlympiaOpCodes` from Cancun onward,
    and `OsakaOpCodes` from Osaka onward — currently a bare alias to `OlympiaOpCodes`, not
    an independent definition (same two files). **Never use the single-arg `forBlock()`
    overload to reason about a PoS fork-gated opcode** — it is timestamp-gated, not
    block-number-gated.
  - Check the opcode is registered in the right op-code set for the vector's `network`
    field (`TestConverter.networkToConfig` maps the vector's fork name to
    `forkBlockNumbers`/`forkTimestamps` — confirm it actually activates the opcode you
    expect for that fork name before assuming a `vm/` bug).
  - If the opcode is registered but still gated off, check the runtime guard for a
    **type check where a spec-flag check is required** — the same anti-pattern
    Nethermind's `fix-nethtest` calls out (`GeneralTestBase.cs`). In fukuii the
    equivalent surface to check is header/spec-field construction:
    `EthereumTestHelper.createParentBlockHeader` and `TestConverter.toBlockHeader`
    (`src/it/scala/com/chipprbots/ethereum/ethtest/{EthereumTestHelper,TestConverter}.scala`)
    — confirm the reconstructed header actually carries the post-merge/EIP-4895/EIP-4844
    fields (`baseFeePerGas`, `withdrawalsRoot`, `blobGasUsed`, `excessBlobGas`,
    `parentBeaconBlockRoot`, `requestsHash`) the target fork needs, rather than the guard
    silently checking a narrower header *type* that doesn't include a later fork's fields.

- **Gas-accounting failures** (`OutOfGas`) — check the static/dynamic gas cost for the
  opcode at `pc` in `vm/` against the active fork's fee schedule, and check intrinsic gas
  calculation (`Transaction`/`SignedTransaction` gas-cost helpers in `domain/`) for the
  vector's transaction type (legacy vs EIP-2930/1559/4844).

- **Stack-effect failures** (`StackUnderflow`/`StackOverflow`) and **invalid-jump
  failures** (`InvalidJump`) — usually *expected* bytecode behavior for the vector (many
  `ethereum/tests` vectors deliberately exercise these paths), not a bug. Confirm against
  the vector's own `postState` / expected outcome before spending time on a "fix."

- **State-root / `postState` mismatches with no opcode-level error** — this is
  `EthereumTestExecutor.validatePostState`'s failure path
  (`src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestExecutor.scala`), reported
  as a flat `"Balance mismatch for ..."` / `"Nonce mismatch for ..."` /
  `"Storage mismatch for ..."` string — re-read the trace for unexpected storage writes,
  confirm the fork's account-cleanup rule (EIP-158 empty-account removal) is applied, and
  confirm nonce/refund accounting for the vector's transaction type.

- **Load/parse and prior-block failures** — a `Left(...)` from Phase 1 addendum itself
  (not from the target transaction's own execution) means pre-state setup or an earlier
  block in the vector failed to reconstruct. Check `EthereumTestsAdapter.scala`'s decoders
  for an unsupported/missing JSON field on the vector, and
  `EthereumTestHelper.executeBlocksWithInitialState`/`createParentBlockHeader` for genesis
  or prior-block header reconstruction issues (same header-field checklist as the
  opcode-gating case above — a missing post-merge field breaks hashing before any opcode
  ever runs).

## Phase 4 — Fix, re-verify, report

1. Apply the minimal fix. Consensus-touching changes (anything in `vm/`, fork dispatch,
   gas schedules, account-cleanup rules) **must** go through the Consensus-Critical Change
   Protocol — `forge` for PoW/ETC, `beacon` for PoS/ETH — not through this skill directly;
   this skill's own re-execution helper makes zero `src/main` changes and never will.
2. Re-run Phase 1 (`runSingleTest`/`runTestFile`) to confirm the vector now passes. If it
   still fails, loop back to Phase 1 addendum with the new trace rather than guessing again.
3. Report: the vector name, the classified failure (row from the Phase 2 table, or "state
   mismatch"/"parse failure" if no opcode error applied), the root cause in one sentence,
   the fix location (`file:line`), and the re-verification result
   (`VERIFY: ran runSingleTest(...) — result: PASS | FAIL`).

## Key files reference

| Purpose | Path |
| :-- | :-- |
| Single-vector run entry points | `src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestsSpec.scala` (`runSingleTest`/`runTestFile`) |
| Test execution (setup → block execution → postState validation) | `src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestExecutor.scala` |
| Test setup + tracing helper | `src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestHelper.scala` (`setupAndExecuteTest`, `reExecuteWithTrace`, `createParentBlockHeader`) |
| Test JSON parsing | `src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestsAdapter.scala` |
| Test → domain conversion | `src/it/scala/com/chipprbots/ethereum/ethtest/TestConverter.scala` (`toBlockHeader`, `toTransaction`, `networkToConfig`) |
| EVM error types | `src/main/scala/com/chipprbots/ethereum/vm/ProgramError.scala` |
| Opcode implementations + fork op-code sets | `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`, `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala` |
| Tracer used for re-execution | `src/main/scala/com/chipprbots/ethereum/vm/StructLogTracer.scala` (`StructLog`, `getSteps` — not `getResult`) |
| Transaction-level simulation (already general-purpose) | `src/main/scala/com/chipprbots/ethereum/ledger/StxLedger.scala` (`simulateTransactionWithTracer`) |

## Related skills / agents
- `forge` — PoW/ETC consensus fixes found via this triage.
- `beacon` — PoS/ETH consensus fixes found via this triage.
- `fukuii-benchmark-diff` — local timing-diff workflow, unrelated failure surface.
