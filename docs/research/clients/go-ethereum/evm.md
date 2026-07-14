# go-ethereum — evm
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary

geth's EVM is a classic bytecode interpreter driven by a per-fork **jump table**: a
`[256]*operation` array (`JumpTable`) where the array index is the opcode byte and each
`*operation` bundles the execution closure, constant gas, an optional dynamic-gas closure,
stack bounds, and an optional memory-size closure. The interpreter (`core/vm/interpreter.go`)
is fork-agnostic — it never branches on fork identity inside the hot loop. Instead, **fork
selection happens once, at `NewEVM` construction time**: a `switch` over boolean fork flags
(`params.Rules`) picks the single pre-built jump table for the active fork
(`core/vm/evm.go:152-186`), stashes it in `evm.table`, and the loop reads `jumpTable[op]`
for every step. Fork tables are built by a **chain of derivation functions** — each fork's
constructor copies the previous fork's table and mutates only the opcodes/gas that changed
(`core/vm/jump_table.go`), so a fork diff is expressed as an additive mutation, not a full
re-listing. Optional/experimental EIPs are layered on top via an `activators` map keyed by
EIP number (`core/vm/eips.go`), applied per-EVM when `Config.ExtraEips` is set.

Precompiles are a parallel structure: a per-fork `map[Address]PrecompiledContract` selected
by the same fork flags (`core/vm/contracts.go:214-233`). Tracing is a passive hook struct
(`core/tracing.Hooks`) invoked from designated points in the run loop only when a `Tracer`
is configured — zero-cost when nil.

## Key types / interfaces / files

- `core/vm/jump_table.go:36-52` — `operation` struct: `execute executionFunc`,
  `constantGas uint64`, `dynamicGas gasFunc`, `minStack`/`maxStack int`,
  `memorySize memorySizeFunc`, `undefined bool`. This is the per-opcode dispatch record.
- `core/vm/jump_table.go:74` — `type JumpTable [256]*operation`. Opcode byte indexes directly.
- `core/vm/jump_table.go:54-71` — package-level pre-built tables, one `var` per fork
  (`frontierInstructionSet` … `osakaInstructionSet`, `amsterdamInstructionSet`), built once
  at init.
- `core/vm/jump_table.go:290-...` — `newFrontierInstructionSet()` builds the base 256-entry
  table literally; every later fork constructor (`newHomesteadInstructionSet` …
  `newOsakaInstructionSet`) starts `instructionSet := new<Prev>InstructionSet()` then mutates.
- `core/vm/jump_table.go:76-92` — `validate(jt)` panics if any index is nil or if a
  `memorySize` op lacks `dynamicGas` — a build-time invariant check on every table.
- `core/vm/evm.go:152-186` — the fork-selection `switch` (highest fork first) that sets
  `evm.table`; mirrored precompile switch at `contracts.go:214-233`.
- `core/vm/evm.go:187-200` — `ExtraEips` handling: deep-copies the shared table
  (`copyJumpTable`) before applying opt-in EIP activators, so global tables stay unpolluted.
- `core/vm/eips.go:29-47` — `activators map[int]func(*JumpTable)`: EIP-number → mutator.
  `EnableEIP` (`:52`), `ValidEip` (`:61`), `ActivateableEips` (`:65`).
- `core/vm/eips.go:79-92` etc. — each `enableNNNN(jt *JumpTable)` mutates gas constants and/or
  installs new opcodes (e.g. `enable1884` reprices SLOAD/BALANCE/EXTCODEHASH and adds
  SELFBALANCE; `enable2929` swaps constant→dynamic gas for state-access opcodes).
- `core/vm/interpreter.go:95-265` — `EVM.Run`: the main loop. Stack-bounds check
  (`:189-193`), constant-gas charge (`:195`), dynamic-gas + memory-size (`:201-232`),
  tracer dispatch (`:235-247`), `operation.execute` (`:253`).
- `core/vm/interpreter.go:29-35` — `Config`: holds `Tracer *tracing.Hooks`, `NoBaseFee`,
  `EnablePreimageRecording`, `ExtraEips []int`.
- `core/vm/contracts.go:50-54` — `PrecompiledContract` interface: `RequiredGas(input) uint64`,
  `Run(input) ([]byte, error)`, `Name() string`. Deterministic gas from input size.
- `core/vm/contracts.go:61-172` — per-fork precompile maps (`PrecompiledContractsHomestead` …
  `PrecompiledContractsOsaka`) keyed by address byte; note stateless config flags baked into
  precompile structs (e.g. `&bigModExp{eip2565: true, eip7823: true, eip7883: true}` at `:157`).
- `core/vm/contracts.go:265-281` — `RunPrecompiledContract`: charges `RequiredGas`, emits a
  gas-change trace event, then `p.Run`.
- `core/vm/gas_table.go:29-56` — `memoryGasCost` (quadratic memory expansion);
  `:66-88` `memoryCopierGas` factory shared by CALLDATACOPY/CODECOPY/MCOPY/etc.
- `core/vm/gascosts.go:28-30` — `GasCosts{RegularGas, StateGas uint64}`: **two-dimensional
  gas**, StateGas separated for verkle/EIP-4762 witness accounting; `GasBudget` at `:63`.
- `core/tracing/hooks.go:277-308` — `Hooks` struct: VM events (`OnOpcode`, `OnFault`,
  `OnEnter`/`OnExit`, `OnTxStart`/`OnTxEnd`, `OnGasChange[V2]`), chain/state/block events.
- `core/tracing/hooks.go:161-162` — `OpcodeHook` signature (pc, op, gas, cost, scope,
  rData, depth, err); `:39-47` `OpContext` interface exposing memory/stack/contract to tracers.
- `params/config.go:1373-1405` — `Rules` struct (all `IsX bool`) and `ChainConfig.Rules(num,
  isMerge, timestamp)` — the single place block-number **and** timestamp forks collapse into
  flat booleans the EVM consumes.

## Design decisions & rationale

- **Fork decided once, not per-opcode.** The run loop has no fork branches; it indexes a
  table chosen at construction. This keeps the hot path a tight array lookup and moves all
  fork logic to init/construction time (`evm.go:152-186`). Fork correctness is a property of
  *which table* was selected, not of scattered `if IsCancun` checks in opcode handlers.
- **Tables built by chained mutation, not full redefinition.** `newCancunInstructionSet`
  starts from Shanghai and applies `enable4844/7516/1153/5656/6780` (`jump_table.go:120-128`).
  A fork's *diff* is literally its constructor body, so review/audit sees exactly what changed.
- **EIPs are first-class mutators.** Both mandatory fork opcodes and opt-in experimental EIPs
  share the `enableNNNN(jt *JumpTable)` shape. Mandatory ones are called from a fork
  constructor; optional ones are dispatched by number through the `activators` map at runtime
  (`eips.go:29-59`). Same mechanism, two entry points.
- **Global tables are immutable; per-EVM customization deep-copies.** `ExtraEips` forces
  `copyJumpTable` before mutation (`evm.go:188-191`) so one EVM's extra EIPs can't corrupt the
  shared package-level tables used by every other EVM.
- **Precompiles selected by the same fork flags, addressed by byte.** Precompile *config* is
  baked statelessly into the struct instance per fork (e.g. modexp's EIP toggles) rather than
  read from rules at Run time — the map itself encodes the fork's behavior.
- **Two-dimensional gas (RegularGas + StateGas).** `GasCosts`/`GasBudget` split ordinary
  execution gas from state/witness gas to support EIP-4762 verkle metering
  (`interpreter.go:219-231`, `gascosts.go`). The loop charges them separately.
- **Tracing is opt-in and hoisted.** `debug := evm.Config.Tracer != nil` is computed once
  (`interpreter.go:136`); every hook site is guarded, and `HasGasHook()` short-circuits arg
  construction (`hooks.go:310-316`). Non-tracing execution pays essentially nothing.
- **`validate()` as a build-time invariant.** Every constructed table is validated for
  no-nil-entries and the memorySize⇒dynamicGas coupling, catching a malformed fork diff at
  package init rather than mid-execution (`jump_table.go:76-92`).

## Notable patterns (the reusable idea)

**Per-fork instruction set as a chain of copy-then-mutate constructors, selected once by a
flat rules struct.** The reusable core: (1) represent each fork as an immutable dispatch
table; (2) build fork N by copying fork N-1 and applying a small set of named mutators; (3)
express each EIP as one such mutator so the same function serves both "mandatory in fork X"
and "opt-in extra"; (4) at EVM construction, collapse all fork predicates (block number,
timestamp, merge status) into one boolean `Rules` struct and pick the table with a single
priority-ordered `switch`. The interpreter then never knows what fork it's on.

## Authority note

geth is the **canonical EVM/EIP reference implementation for Ethereum (PoS/ETH)**. Its
opcode semantics, gas schedules, precompile behavior, and fork-activation ordering are the
de-facto spec that ETH-side clients validate byte-for-byte against. For fukuii's `beacon`
(ETH/Sepolia) path, geth is the authority. For the **PoW/ETC** side, core-geth (not this
repo) is the ETC authority — geth's post-merge forks (Merge/Shanghai/Cancun/Prague/Osaka) and
its removal of PoW-era behavior make it a reference for *pre-merge shared opcode semantics*
only, not for ETC's divergent Olympia/ECIP fork schedule. Cross-check ETC-specific opcodes
and gas against core-geth's `config_classic.go`.

## Gotchas / anti-patterns / things they later changed

- **Fork flags mix block-number AND timestamp dispatch in one struct.** `Rules` is derived
  from both `num` and `timestamp` (`params/config.go:1388-1405`); several flags are gated on
  `isMerge && IsShanghai(...)`. This is exactly the split fukuii keeps separate as the
  `EvmConfig.forBlock(blockNumber, …)` (PoW/block) vs `forBlock(blockNumber, timestamp, …)`
  (PoS/timestamp) overloads — geth folds both into one flag set because a single client serves
  one canonical chain; a multi-network client must not conflate the two dispatch axes.
- **Shared mutable-looking tables are a footgun without the copy guard.** `enableNNNN`
  functions write in-place (`eips.go:51` comment explicitly warns "callers need to ensure the
  globally defined jump tables are not polluted"). The only thing preventing corruption is the
  discipline of `copyJumpTable` before applying `ExtraEips`. Any future code that mutates a
  package-level table directly would silently poison every EVM.
- **`IsUBT`/verkle is a placeholder.** `evm.go:157-159` selects `verkleInstructionSet` with a
  `// TODO replace with proper instruction set when fork is specified` — verkle/EIP-4762 and
  the StateGas dimension are in-flight, not final. Don't treat the two-dimensional gas API as
  a settled interface.
- **Priority-ordered switch is order-sensitive.** The fork `switch` lists newest-first
  (`Amsterdam` before `Osaka` before `Prague`…). A misordered case would select the wrong
  table for a chain that has several fork flags simultaneously true. The ordering — not the
  individual predicates — is load-bearing.
- **Precompile fork config is duplicated across maps.** Each fork's precompile map re-lists
  every address, so a change to (say) modexp's gas rules across forks means editing multiple
  near-identical map literals (`contracts.go:61-172`); geth accepts the duplication for
  explicitness over a derive-by-mutation chain like the jump tables use.
- **PC is `uint64`, not the YP's `uint256`.** Acknowledged deliberate deviation for
  performance (`interpreter.go:126-129`) — theoretically incorrect above 2^64 but practically
  unreachable.
