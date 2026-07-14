# nethermind — evm
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

`Nethermind.Evm` is Nethermind's C# bytecode interpreter — a fast, allocation-averse,
JIT-monomorphized EVM. The entry point is the `VirtualMachine<TGasPolicy>` class
(`src/Nethermind/Nethermind.Evm/VirtualMachine.cs`), a `partial` class split across
several files (`VirtualMachine.cs`, `.std.cs`, `.DispatchSpecialized.cs`, `.Stream.cs`,
`.warmup.cs`, `.zkevm.cs`). It executes a transaction as an iterative loop over call
frames (`ExecuteTransaction` → `ExecuteCall` → `RunByteCode` → `RunByteCodeCore`),
never recursing on the CLR stack — a `Stack<VmState<TGasPolicy>>` holds the frames so a
1024-deep call chain cannot blow the managed stack.

Two ideas dominate the design and are the reason to study this subsystem:

1. **`IReleaseSpec` fork-gating** — every fork is a spec object exposing ~100 boolean
   `IsEipNNNEnabled` flags plus higher-level semantic getters (`ShiftOpcodesEnabled`,
   `IncludePush0Instruction`, …). Opcode availability, gas costs, and precompile sets are
   all keyed off this one object, resolved once per block from an `ISpecProvider`. This is
   Nethermind's analog to go-ethereum's `params.Rules` struct and Besu's `ProtocolSpec`.

2. **Generic tracer specialization** — the interpreter is generic not only over the gas
   policy (`TGasPolicy`) but over a family of `IFlag` type parameters (`TTracingInst`,
   `TCancelable`, `TShift`, `TPush0`). Because `IFlag.IsActive` is a `static virtual`
   compile-time constant, the RyuJIT monomorphizes each combination and folds every
   `if (TTracingInst.IsActive)` branch away. Tracing therefore costs **zero** in the hot
   loop when disabled — block processing runs `ExecuteTransaction<OffFlag>` and the traced
   code paths simply do not exist in the JITted body.

The opcode dispatch itself is a 256-entry table of **unmanaged function pointers**
(`delegate*<…>[]`), generated per-fork and memoized on the spec object, with a direct
`switch` fast-path over the measured-hot opcodes for the cancelable (`eth_call`) context.

## Key types / interfaces / files

- `src/Nethermind/Nethermind.Evm/VirtualMachine.cs:67` — `VirtualMachine<TGasPolicy>`,
  the `unsafe partial` interpreter class. `TGasPolicy : struct, IGasPolicy<TGasPolicy>`.
- `src/Nethermind/Nethermind.Evm/VirtualMachine.cs:134` — `ExecuteTransaction<TTracingInst>`,
  the top-level frame loop; `TTracingInst : struct, IFlag`. Non-generic overload at :328
  defaults to `ExecuteTransaction<OffFlag>` (the no-trace path).
- `src/Nethermind/Nethermind.Evm/VirtualMachine.cs:1209` — `RunByteCode<TTracingInst, TCancelable>`,
  which lifts the two per-fork opcode gates (`ShiftOpcodesEnabled`, `IncludePush0Instruction`)
  into compile-time `IFlag` type args via a 4-arm `switch`, then calls `RunByteCodeCore`.
- `src/Nethermind/Nethermind.Evm/VirtualMachine.DispatchSpecialized.cs:28` —
  `RunByteCodeCore<TTracingInst, TCancelable, TShift, TPush0>`, the actual dispatch loop:
  a `fixed`-pinned function-pointer array (drops the per-dispatch bounds check) with a
  direct-`switch` fast-path for hot opcodes on the cancelable path only.
- `src/Nethermind/Nethermind.Evm/VirtualMachine.std.cs:16` — `PrepareOpcodes<TTracingInst>`,
  which selects/builds the opcode table and caches it **on the spec** (`spec.EvmInstructionsNoTrace`
  / `spec.EvmInstructionsTraced`), plus a periodic cache refresh to pick up PGO-rejitted methods.
- `src/Nethermind/Nethermind.Evm/Instructions/EvmInstructions.cs:24` —
  `GenerateOpCodes<TGasPolicy, TTracingInst>(IReleaseSpec spec)`: builds the
  `delegate*<VirtualMachine<TGasPolicy>, ref EvmStack, ref TGasPolicy, ref int, EvmExceptionType>[256]`
  table, defaulting all entries to `&InstructionBadInstruction` then wiring live opcodes,
  each gated by a `spec.*Enabled` flag. Handlers live in the `EvmInstructions.*.cs` partials.
- `src/Nethermind/Nethermind.Evm/Instruction.cs:1` — the `Instruction : byte` enum (the opcode set).
- `src/Nethermind/Nethermind.Core/TypeFlags.cs:12` — `IFlag` with `static virtual bool IsActive`,
  and the two singletons `OffFlag` (`IsActive => false`) and `OnFlag` (`IsActive => true`).
  This is the machinery the whole zero-cost-when-off specialization rests on.
- `src/Nethermind/Nethermind.Core/Specs/IReleaseSpec.cs:15` — the per-fork spec interface
  (`IReleaseSpec : IEip1559Spec, IReceiptSpec`), ~100 `IsEipNNNEnabled` booleans plus
  `Name`, `MaxCodeSize`, `BlockReward`, etc. Semantic opcode/gas getters are derived from it.
- `src/Nethermind/Nethermind.Evm/GasPolicy/IGasPolicy.cs:16` — `IGasPolicy<TSelf>`
  (static-abstract-member interface): pluggable gas accounting. `EthereumGasPolicy` is the
  default; the abstraction exists to carry the EIP-8037 multi-dimensional "state gas" model
  without branching in the hot loop.
- `src/Nethermind/Nethermind.Evm/Tracing/ITxTracer.cs:14` — the tracer contract, a set of
  `IsTracing*` capability booleans (`IsTracingInstructions`, `IsTracingMemory`,
  `IsTracingStack`, `IsTracingOpLevelStorage`, …) each gating a family of `Report*`/`Set*`
  callbacks. `NullTxTracer` is the disabled default; `CancellationTxTracer` provides
  `IsCancelable`/`IsCancelled` for `eth_call`.
- `src/Nethermind/Nethermind.Evm/Precompiles/IPrecompile.cs:11` — `IPrecompile` (static-virtual
  `Address`/`Name`): `BaseGasCost(IReleaseSpec)` + `DataGasCost(input, IReleaseSpec)` (gas
  costs fork-gated through the spec) + `Run(...)`, plus `NormalizeInput` for result caching.

## Design decisions & rationale

- **Iterative frame loop, not recursion.** `ExecuteTransaction` runs a `while(true)` over a
  managed `_stateStack`. EVM call depth (1024) would otherwise map onto the CLR call stack;
  making frames heap-managed keeps depth bounded and lets a call return be handled inline.
- **Spec object as the single fork switch.** Rather than pass a fork enum and branch on it,
  behaviour is a property bag: `if (spec.ShiftOpcodesEnabled)`. Adding a fork is adding a
  spec implementation; the interpreter body never mentions fork names. This is what makes
  multi-network viable — each network family supplies its own `ISpecProvider`/`IReleaseSpec`
  chain (block-number-keyed for PoW, timestamp-keyed for post-merge) and the same VM runs.
- **Function-pointer opcode table, built per-fork and cached on the spec.** `PrepareOpcodes`
  does `spec.EvmInstructionsNoTrace ??= GenerateOpCodes<…>(spec)` — the 256-entry table is
  computed once per fork and hangs off the spec object, so the per-transaction cost is a
  field read. A separate `EvmInstructionsTraced` table exists so the traced and non-traced
  monomorphizations never share a slot.
- **PGO-aware cache refresh.** For the first 500k transactions, every 10,000th tx regenerates
  the non-traced table (`VirtualMachine.std.cs:26`). Rationale in-source: captured function
  pointers don't auto-update when a method is re-JITted under dynamic PGO, so the table is
  periodically rebuilt to point at the optimized method bodies.
- **Direct-switch fast path only on the cancelable path.** The comment at
  `VirtualMachine.DispatchSpecialized.cs:73` records a measured tradeoff: inlining hot opcode
  handlers via a `switch` wins for `eth_call`/simulation (few hot contracts, stay in I-cache)
  but regresses block processing ~8% (diverse opcode mix, code-size/jump-table pressure), so
  block processing takes the plain function-pointer table. `TCancelable` is a compile-time
  flag, so this compiles into two separate loop bodies with no runtime branch.
- **Pluggable gas policy for EIP-8037.** Making gas a generic `TGasPolicy` lets the
  multi-dimensional state-gas accounting (reservoir/spill accessors in `IGasPolicy`) be a
  separate policy struct; pre-8037 policies return constants, and the JIT specializes both.

## Notable patterns (the reusable idea)

**Type-level feature flags for zero-cost conditional behaviour.** The `IFlag` /
`OnFlag` / `OffFlag` triad (`TypeFlags.cs`) plus `static virtual` members turns a runtime
`if (tracingEnabled)` into a compile-time-constant `if (TTracingInst.IsActive)` that the JIT
folds and dead-code-eliminates. The same trick gates cancellation polling, per-fork opcode
availability (`TShift`, `TPush0`), and even out-of-EVM concerns (`ExecutionMetricsFlag` in
`BlockProcessor`). The cost is code duplication in the JIT (each combination is a distinct
compiled body), paid for by the hottest loop in the client having no branch on any of these
axes. This is C#'s equivalent of monomorphizing over a phantom/const-generic parameter.

The **complementary** reusable idea is the two-layer fork model:
`IReleaseSpec` (raw per-EIP booleans) → semantic getters (`ShiftOpcodesEnabled`) →
`GenerateOpCodes(spec)` (a fork-specialized opcode table). The interpreter never names a
fork; forks are data.

## Authority note

go-ethereum is the canonical reference for EVM/EIP behaviour (opcode semantics, gas
schedules, precompile outputs). Nethermind is a **C# re-implementation** whose distinguishing
traits are (a) `IReleaseSpec`-gated fork dispatch — a boolean-flag spec object resolved from
an `ISpecProvider`, the analog of geth's `params.Rules` and Besu's `ProtocolSpec`; and
(b) generic-tracer / `IFlag` specialization so tracing, cancellation, and per-fork gates are
compile-time constants with zero hot-loop cost. Treat geth (and for PoW/ETC, core-geth) as
the spec of record; treat Nethermind's structure as one high-performance way to organize the
dispatch, not as an independent semantic authority.

## Transfer notes for fukuii's `EvmConfig` / `OpCode`

fukuii's `EvmConfig.forBlock(blockNumber, blockchainConfig)` (PoW) /
`forBlock(blockNumber, timestamp, blockchainConfig)` (PoS) is already the same shape as
Nethermind's `IReleaseSpec` resolved from `ISpecProvider` — a per-fork config object. Two
ideas are portable:

- **Semantic feature getters over raw fork checks.** Nethermind exposes
  `spec.ShiftOpcodesEnabled` rather than making callers ask "is this Constantinople-or-later".
  fukuii's fork-conditional opcode/gas dispatch benefits from the same intent-named layer so
  network-prefixed fork objects (`EtcOlympiaOpCodes` vs `EthOsakaOpCodes`) never leak fork
  names into the interpreter — matching the `scala3-style.md` `Eth*`/`Etc*` anti-conflation
  ratchet.
- **A per-fork opcode table built once and memoized on the config.** Nethermind's
  `spec.EvmInstructions* ??= GenerateOpCodes(spec)` amortizes fork resolution to a one-time
  build. If fukuii's `EvmConfig` recomputes opcode/fee availability per instruction, caching
  the resolved table on the `EvmConfig` instance is a direct win.

The generic-tracer specialization is largely a JIT-monomorphization technique specific to
CLR `static virtual` members; on the JVM the analog (megamorphic-call elimination) is far
weaker, so it is more an inspiration for "make the disabled-tracing path branch-free" than a
literally portable mechanism.

## Gotchas / anti-patterns / things they later changed

- **`unsafe` + `fixed` + `delegate*` everywhere.** The dispatch loop pins the opcode array
  and calls through raw function pointers to drop bounds checks and virtual-call overhead.
  This is fast but memory-unsafe by construction and leans hard on the invariant that opcode
  is a `byte` (always in `[0,255]`). Not something to copy into a JVM client verbatim.
- **Opcode tables cached on the mutable spec object.** `spec.EvmInstructionsNoTrace` is a
  writable slot on the shared spec; the PGO-refresh path reassigns it. Correct only because
  the table is behaviourally identical across rebuilds — a subtle invariant to preserve.
- **The direct-switch fast path is deliberately *not* extracted.** The source warns
  (`DispatchSpecialized.cs`) that pulling the `switch` into its own method stops the JIT
  inlining the handler bodies and it then loses to the table. The performance depends on it
  staying inline in one large method — an intentional anti-"clean-code-refactor" hazard.
- **Two axes of code duplication.** Every added `IFlag` type parameter doubles the number of
  JITted loop bodies (traced×cancelable×shift×push0 already = up to 16 monomorphizations).
  This is a conscious throughput-for-code-size trade; adding more compile-time flags is not free.
- **`EthereumGasPolicy` vs generic `TGasPolicy` is recent churn.** `IVirtualMachine` keeps a
  non-generic compatibility shim (`IVirtualMachine : IVirtualMachine<EthereumGasPolicy>`,
  `IVirtualMachine.cs:29`) because the gas-policy generalization (EIP-8037 state gas) was
  retrofitted — evidence the abstraction was added under an active fork, not designed up front.
