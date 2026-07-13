# besu — evm
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

## Architecture summary

besu's `evm/` is a **standalone, independently-published EVM library** (Gradle module
`evm`, artifact `org.hyperledger.besu:evm`) that the rest of besu consumes but which is
also reusable by external tooling — it depends only on `datatypes`, tuweni, and Guava, not
on besu's networking/consensus/storage. It is the JVM structural mirror to fukuii's `vm/`
package (`OpCode.scala` / `EvmConfig.scala`) and, being Java-on-the-JVM, the closest
structural reference fukuii has.

The design is built from three swappable, per-fork objects wired together at construction
time by a factory:

1. **`EVM`** — the interpreter run loop. Immutable; holds one `OperationRegistry`, one
   `GasCalculator`, one `EvmConfiguration`, and an `EvmSpecVersion`. Executes a
   `MessageFrame` to halt.
2. **`OperationRegistry`** — a flat `Operation[256]` opcode table, one instance per fork,
   assembled incrementally (each fork's registration calls the prior fork's then adds/
   overrides the delta).
3. **`GasCalculator`** — the entire per-fork gas schedule as a single polymorphic strategy
   object in a linear inheritance chain (`FrontierGasCalculator` → … →
   `OsakaGasCalculator`), each fork overriding only the methods whose costs changed.

A parallel `PrecompileContractRegistry` (a `Map<Address, PrecompiledContract>`) holds the
precompiles, assembled by the same incremental pattern. Fork assembly lives in the
`MainnetEVMs` / `MainnetPrecompiledContracts` factories: one static factory method per fork
(`frontier`, `homestead`, …, `cancun`, `prague`, `osaka`) that news up the right registry +
GasCalculator + spec version. There is no giant flag struct; the fork *is* the trio of
objects.

## Key types / interfaces / files

- `evm/.../EVM.java:127-159` — the interpreter. Constructor takes `(OperationRegistry,
  GasCalculator, EvmConfiguration, EvmSpecVersion)`; precomputes `enableConstantinople/
  Shanghai/Amsterdam/Osaka` booleans from `evmSpecVersion.ordinal()` for hot-path branch
  elision.
- `evm/.../EVM.java:227` — `runToHalt(MessageFrame, OperationTracer)`: the hot loop. Reads
  `code[pc]`, dispatches via a **big inlined `switch(opcode)`** that calls
  `XxxOperation.staticOperation(frame)` directly for common opcodes, with a
  `default -> currentOperation.execute(frame, this)` arm that falls back to the registry
  array `operations.getOperations()[opcode]`. Explicitly annotated "one of the hottest
  sections of code… benchmark before refactoring."
- `evm/.../operation/OperationRegistry.java` — `Operation[256]` wrapper. `put()` indexes by
  `operation.getOpcode()`; `get(int)` is a bare array read; `getOperations()` exposes the
  raw array to the EVM loop. Per-fork instance, not per-opcode-lookup.
- `evm/.../operation/Operation.java:114` — the `Operation` interface:
  `execute(MessageFrame, EVM) -> OperationResult`, plus `getOpcode/getName/
  getStackItemsConsumed/getStackItemsProduced/isVirtualOperation`. `OperationResult` is a
  small value class carrying `gasCost`, nullable `haltReason`, `pcIncrement`, and optional
  soft-failure info.
- `evm/.../operation/AbstractOperation.java:30` — base class; every operation takes a
  `GasCalculator` in its constructor and exposes it via `gasCalculator()`. This is how the
  gas strategy is threaded into opcode logic (each opcode asks its injected calculator for
  costs).
- `evm/.../gascalculator/GasCalculator.java:59` — the interface: ~47 cost methods spanning
  precompile costs, memory expansion, SSTORE/SLOAD, call stipends, transaction intrinsic
  gas, blob gas, code-deposit cost, etc.
- `evm/.../gascalculator/FrontierGasCalculator.java` … `OsakaGasCalculator.java` — the
  **linear delta chain**: `Frontier(implements) ← Homestead ← TangerineWhistle ←
  SpuriousDragon ← Byzantium ← Constantinople ← Petersburg ← Istanbul ← Berlin ← London ←
  Shanghai ← Cancun ← Prague ← Osaka`. Each subclass overrides only changed methods (e.g.
  `ConstantinopleGasCalculator.java:39` overrides `calculateStorageCost` for EIP-1283 and
  nothing else).
- `evm/.../MainnetEVMs.java:162-950+` — the fork→objects assembly. Per fork: a public
  `EVM frontier(...)` factory + a private `frontierOperations(...)` + a
  `registerFrontierOperations(registry, gasCalculator, cfg)`. Incremental:
  `registerByzantiumOperations` (line ~433) calls `registerHomesteadOperations` first, then
  `put`s only `ReturnDataCopy/ReturnDataSize/Revert/StaticCall`.
- `evm/.../EvmSpecVersion.java:29` — enum of fork identities (`FRONTIER … OSAKA, AMSTERDAM,
  … FUTURE_EIPS`), each carrying its `MainnetHardforkId`, max code size, and max initcode
  size. Ordinal comparison drives the EVM's fork-gating booleans.
- `evm/.../precompile/PrecompiledContract.java:32` — `getName()`, `gasRequirement(Bytes)`,
  `computePrecompile(...) -> PrecompileContractResult` (a `record` with success/revert/halt
  factories).
- `evm/.../precompile/PrecompileContractRegistry.java` — `Map<Address, PrecompiledContract>`
  (address-keyed, unlike the opcode array).
- `evm/.../precompile/MainnetPrecompiledContracts.java:31-206` — per-fork
  `populateForFrontier/byzantium/istanbul/cancun/prague/osaka` builders; each is incremental
  and takes the fork's `GasCalculator` so precompile gas costs also flow from the strategy
  object (e.g. AltBN128 mul cost drops 40k→6k Byzantium→Istanbul via constructor arg).
- `evm/.../tracing/OperationTracer.java:33` — the hook interface. All methods are
  `default {}` (no-ops): `tracePreExecution`, `tracePostExecution`, `tracePrecompileCall`,
  `traceContextEnter/ReEnter/Exit`, `traceStartTransaction/EndTransaction`,
  `traceAccountCreationResult`, plus `isEnabled()`. Ships a singleton `NO_TRACING` whose
  `isEnabled()` returns false.

## Design decisions & rationale

- **Gas schedule as a polymorphic strategy object, not a flag struct.** The entire fork gas
  schedule is one `GasCalculator` subclass. A fork change is "swap in a different
  `GasCalculator`," and the diff between two forks is literally the overridden methods of
  one subclass. This is the headline differentiator vs. geth's approach (geth threads fork
  booleans/`params` through a jump table). Because the calculator is constructor-injected
  into every `Operation` and `PrecompiledContract`, opcode/precompile logic never
  branches on fork — it just asks its calculator.
- **Per-fork opcode table assembled by incremental registration.** Each `registerXOperations`
  calls the previous fork's registrar, then `put`s only the new/changed opcodes into the
  256-slot array. A fork is a small delta on top of the prior fork's table, and the final
  artifact is a dense array giving O(1) dispatch with no per-step fork checks.
- **Hot-path inlining in the run loop.** The interpreter does NOT purely dispatch through
  the registry. Common opcodes are inlined as a `switch` calling `static` methods
  (`AddOperation.staticOperation`), sidestepping virtual dispatch and object allocation on
  the hottest ops; only rarer opcodes take the `default` polymorphic
  `currentOperation.execute(...)` path. The registry array still exists so the fallback and
  `getChainId()`/introspection work. Explicit maintainer note: this deliberately breaks OO
  purity for performance.
- **Zero-cost tracing when disabled.** `OperationTracer` is an all-default interface with a
  `NO_TRACING` singleton; the run loop asserts a monomorphic tracer so the JIT can elide the
  calls entirely when tracing is off.
- **Standalone-library boundary.** The module keeps its dependency surface minimal so it can
  be embedded by external tooling / other clients — the EVM is a reusable artifact, not an
  internal package.

## Notable patterns (the reusable idea)

**GasCalculator-as-strategy-object (the single most transferable pattern for fukuii).**
Instead of a per-fork fee-schedule *data* struct read by fork-conditional code, besu makes
the gas schedule a *polymorphic object* in an inheritance chain where each fork overrides
only what changed, and injects that object into every opcode and precompile so the logic
itself is fork-agnostic. Two payoffs directly relevant to fukuii's multi-network goal:

- **Per-family gas rules by subclassing.** ETC's Olympia (ECIP-1121) gas deltas would be an
  `EtcOlympiaGasCalculator` overriding a handful of methods off its base, exactly parallel
  to how `ConstantinopleGasCalculator` overrides only `calculateStorageCost`. No shared
  mega-switch that both ETC and ETH branch through.
- **Fork = a trio of objects, assembled by a factory.** `(OperationRegistry, GasCalculator,
  EvmSpecVersion)` are constructed together per fork in `MainnetEVMs`; opcode-table and gas
  schedule stay in lockstep by construction, not by two parallel `if (fork >= X)` ladders.

The companion pattern — **incremental registry assembly** (each fork's registrar delegates
to the prior fork's then `put`s the delta into a dense `Operation[256]`) — gives O(1)
dispatch with the fork logic paid once at construction rather than per instruction.

## Authority note

(besu = JVM EVM structural reference + standalone-lib pattern; go-ethereum = canonical
EVM/EIP behavior authority.) Use besu to answer "how should fukuii *structure* fork-
conditional opcode/gas dispatch on the JVM" — the GasCalculator strategy chain, the
per-fork OperationRegistry, the factory assembly. Use go-ethereum (and core-geth for
ETC/Olympia specifics) as the authority for the *values and semantics* of any given
opcode/gas rule. besu targets ETH mainnet forks; it carries no ECIP-1017/1111/1121 logic,
so ETC gas/opcode *content* is not besu's to answer.

## Gotchas / anti-patterns / things they later changed

- **The run loop is intentionally un-OO and perf-fragile.** `runToHalt` is a hand-tuned
  `switch` with inlined static ops and precomputed fork booleans; the maintainer comment
  says "lots of Java idioms and OO principles are being set aside… benchmark before
  refactoring." A naive "clean it up to pure registry dispatch" would regress the hot path.
  fukuii should copy the *structure* (strategy gas object + per-fork table) but decide its
  own hot-loop dispatch strategy deliberately.
- **Two dispatch tables of record.** The inlined `switch` and the `OperationRegistry` array
  must stay consistent — an opcode handled in the switch still needs a registry entry for
  the fallback/introspection paths (and `InvalidOperation` fills unused slots 0x00–0xFE at
  frontier registration). Divergence between the two is a latent bug surface.
- **Optimized-opcode duplication.** `EvmConfiguration.enableOptimizedOpcodes()` selects
  between `AddOperation` and `AddOperationOptimized` (and ~10 others) both at registration
  time *and* inside the run-loop switch, so each arithmetic op exists in two parallel
  implementations that must agree.
- **An `enableEvmV2()` / `runToHaltV2` path is mid-migration.** A second run loop using a
  `long[]` stack (`v2/operation/*V2`) is a partial "skeleton stub" — only a handful of
  opcodes are handled, the rest fall through to the v1 registry. It is an in-progress
  rewrite, not a finished design; don't treat the v2 package as authoritative.
- **Precompile gas is split between the calculator and hardcoded constructor args.** Some
  precompiles pull cost from the injected `GasCalculator` (ECREC, SHA256), but others take a
  literal cost in their constructor at registration (`AltBN128MulPrecompiledContract(gc,
  6_000L)`), and newer BLS12 precompiles take *no* calculator at all. The gas-strategy
  abstraction is not applied uniformly to precompiles — a consistency wrinkle to avoid
  reproducing.
