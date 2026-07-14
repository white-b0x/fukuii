# Observations — evm
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/evm.md + initial-assessment §1c._

This is the Phase-2 cross-client comparison for the **evm** subsystem: how each reference
client selects the active fork's opcode/gas behaviour, assembles its opcode table, models
gas schedules, handles precompiles and tracing, and whether the interpreter is reusable
in isolation. It builds on `initial-assessment.md` §1c (fork-dispatch unification —
nethermind `AddTransitions`, reth `ForkCondition`, besu `MilestoneType`). It does not
re-research repos — every per-client claim is cited to that client's `evm.md`.

**Authority model (per Phase-0, re-stated so the table reads correctly):** go-ethereum =
canonical EVM/EIP behaviour, gas semantics, precompile outputs, fork-activation ordering
(the de-facto spec ETH-side clients validate byte-for-byte against); core-geth = the ETC
fork *schedule* and effective opcode/gas set (per-EIP block heights in `config_classic.go`/
`config_mordor.go` — the sole living, deprecated ETC byte-authority); besu = the JVM
structural mirror — GasCalculator strategy chain + standalone published EVM lib; nethermind
= `IReleaseSpec` boolean-flag spec object + generic-tracer zero-cost specialization; reth/
revm = `SpecId` enum + `ConfigureEvm` compile-time family seam (revm = the standalone Rust
EVM authority). erigon = a perf-oriented geth-derived variant, not an independent authority.

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | Authoritative |
|---|---|---|---|---|---|---|---|
| **Fork-conditional dispatch** | immutable **jump-table-per-fork** picked once at `NewEVM` by a priority-ordered `switch` over flat `params.Rules` booleans; loop never branches on fork | **additive per-EIP** — `instructionSetForConfig` builds one table gating each opcode on `IsEnabled(GetEIPxTransition, bn)`; **no named-fork table, no `chainRules` struct** | **GasCalculator-as-strategy** object per fork + per-fork `OperationRegistry`, assembled by a factory; fork = a trio of objects, no flag struct | geth's jump-table-per-fork model, table resolved once from `chainRules` at `NewEVM` | **`IReleaseSpec`-flags** — ~100 `IsEipNNNEnabled` booleans + intent-named getters, resolved once per block from `ISpecProvider` | **`SpecId` enum + `ConfigureEvm`** — every family's `Spec: Into<SpecId>`; family bound at compile time by associated types | **go-ethereum** (canonical) semantics; **besu** = JVM structural model fukuii should mirror; **core-geth** = ETC EIP-groupings |
| **Gas-schedule modelling** | per-fork jump table carries `constantGas` + `dynamicGas` closures; two-dimensional `GasCosts{Regular,State}` for EIP-4762 (in-flight) | inherits geth's gas closures, gated per-EIP transition; **no EIP-1559 base-fee** in upstream ETC config | **entire schedule = one `GasCalculator` subclass**; fork diff = the overridden methods; injected into every opcode + precompile | **multidimensional `mdgas.MdGas{Regular,State}`** threaded through `Run` + every gas func (EIP-8037), with signed refunds + spill semantics | pluggable **`IGasPolicy<TSelf>`** (static-abstract) so EIP-8037 state-gas is a separate policy struct, JIT-specialized | revm owns the gas schedule; reth only configures spec/env | **besu** (subclassable strategy object — the fukuii model); **go-ethereum** for values/semantics |
| **Opcode-table assembly** | **copy-then-mutate** — each fork constructor copies prior fork's `[256]*operation` + applies named `enableNNNN` mutators; validated at init | **copy-then-mutate additively** from a flat base, gating each mutation on its own EIP block | **incremental registry** — each `registerXOperations` calls prior fork's then `put`s the delta into a dense `Operation[256]` | geth copy-then-mutate; ExtraEips copy-on-extend only | **function-pointer table** (`delegate*<…>[256]`) built per-fork by `GenerateOpCodes(spec)`, **memoized on the spec object** | revm-internal (handler-composition per fork) | **besu**/**go-ethereum** (per-fork table built once); **nethermind** for the cache-table-on-config idea |
| **Precompile handling** | per-fork `map[Address]PrecompiledContract` selected by same fork flags; config baked statelessly into struct per fork | inherits geth's per-fork precompile maps | `PrecompileContractRegistry` (`Map<Address,…>`), incremental per fork, takes the fork's `GasCalculator` (**not applied uniformly** — some hardcode cost) | geth's `PrecompiledContract` iface, unchanged | `IPrecompile` (static-virtual `Address`) with `BaseGasCost/DataGasCost(IReleaseSpec)` fork-gated | revm/alloy `PrecompilesMap` (mutable set), configured via `ConfigureEvm` | **go-ethereum** (address→contract map, fork-selected) is the canonical shape |
| **Tracing hooks** | passive `tracing.Hooks` struct, zero-cost when nil (hoisted `debug` flag, `HasGasHook` short-circuit) | inherits geth `tracing.Hooks` | `OperationTracer` all-`default{}` iface + `NO_TRACING` singleton; JIT elides when monomorphic | geth-compatible `tracing.Hooks` (`cost` deliberately regular-gas-only to avoid tracer underflow) | **generic-tracer specialization** — `IFlag`/`OnFlag`/`OffFlag` + `static virtual` fold `if(TTracingInst.IsActive)` away → **zero hot-loop cost**; separate traced/non-traced tables | revm **`Inspector`** — tracing = "an `Evm` built with an Inspector"; same path serves `debug_*`/archival; `NoOpInspector` default | **nethermind** (zero-cost generic tracer, CLR-specific) / **reth** (`Inspector` seam) — both inspirations for a branch-free disabled path |
| **Standalone-lib reusability** | `core/vm` is an internal package (not separately published) | internal package (geth-derived) | **✓ independently-published `org.hyperledger.besu:evm`** — depends only on datatypes/tuweni/Guava; embeddable by external tooling | relocated to `execution/vm/`, still an internal package | `Nethermind.Evm` internal assembly | **✓ revm** — the dominant standalone Rust EVM (Foundry, reth, many others); reth's own EVM crate <2k LoC | **besu (JVM)** + **revm (Rust)** — the two "reusable EVM library + per-fork spec selector" references |
| **Interpreter loop** | iterative `EVM.Run` loop, jump-table index per step; PC is `uint64` (deliberate YP deviation) | same geth iterative loop | iterative `runToHalt` — hand-tuned inlined `switch` for hot opcodes + registry fallback (intentionally un-OO) | iterative geth loop + `sync.Pool`-pooled call contexts, interned keys, generation-counter cache | **iterative frame loop** over managed `Stack<VmState>` (no CLR recursion) → 1024-depth safe; `unsafe`+`fixed`+`delegate*` dispatch | revm handler-based loop (external) | **go-ethereum** / **besu** iterative loop is the mainstream shape; recursion is nobody's choice |

## Approach catalog (use-case-aware)

| Approach | Clients using it | Good for (use-case/node-role) | Verdict | Why |
|---|---|---|---|---|
| **Fork = immutable table built once + rules-switch at construction** | go-ethereum, core-geth, erigon | Any node; the mainstream Go model — hot loop is a tight array lookup, fork logic paid once | **DEFAULT** (the table-build half) | Per-fork table built once & cached, indexed with no per-step fork branch, is the universal win; the *rules-struct* half conflates block# + timestamp axes (geth folds both — fine for one canonical chain, wrong for multi-network) |
| **GasCalculator-as-strategy object per fork** (subclassable) | besu | JVM client; per-family gas rules (ETC Olympia vs ETH Osaka) as sibling subclasses; **the fukuii model** | **DEFAULT** | The single most transferable pattern for fukuii: gas schedule is a polymorphic object injected into every opcode/precompile, so opcode logic never branches on fork; a fork diff is literally the overridden methods; `EtcOlympiaGasCalculator`/`EthOsakaGasCalculator` are siblings, no shared mega-switch |
| **IReleaseSpec boolean-flags + intent-named getters** | nethermind | Multi-network client; forks-as-data with a two-layer (raw-EIP → semantic) model so the interpreter never names a fork | **DEFAULT** (the two-layer fork model) | `spec.ShiftOpcodesEnabled` (intent) over "is-Constantinople-or-later" (fork name) keeps network-prefixed fork objects from leaking fork identity into the loop — matches `scala3-style.md` `Etc*`/`Eth*` anti-conflation; the C#-specific JIT specialization does not port to the JVM |
| **SpecId enum + ConfigureEvm compile-time family seam** | reth/revm | Rust SDK; a chain family = one type parameter chosen at node-build, all fork logic collapses to header→SpecId | **OPTIONAL(SDK/multi-family)** | Elegant compile-time family selection, but presumes an owned standalone interpreter (revm) and Rust associated-type generics; fukuii's vendored JVM EVM cannot adopt the seam wholesale — it takes the *lesson* (one fork value, resolvers factored out) not the mechanism |
| **Additive per-EIP jump-table construction** (no named-fork table) | core-geth | Hosting a chain whose EIP groupings differ from ETH's (ETC) | **DEFAULT(ETC path)** | ETC forks activate a bespoke subset of ETH EIPs at ETC-specific heights — "pick one named ETH fork" cannot express Agharta-minus-EIP1283 or Mystique-minus-EIP1559; fukuii must reproduce the *effective set at a given ETC block*, which is what `Etc*` fork objects encode |
| **Generic / zero-cost tracer specialization; Inspector hook** | nethermind (generic tracer), reth/revm (Inspector) | Archival / RPC / `debug_*`/`trace_*` replay nodes where tracing must be free when off | **OPTIONAL(archival/RPC role)** | Worth the machinery only when tracing is a first-class role; the portable core is "make the disabled-tracing path branch-free" (besu's `NO_TRACING` monomorphic singleton is the JVM-appropriate form), not the CLR `static virtual` monomorphization |
| **Multidimensional gas (MdGas / IGasPolicy state-gas)** | erigon, nethermind (partial) | Nodes on chains that adopt EIP-8037 state-creation gas | **OPTIONAL(future-EIP)** | Not canonical ETH mainnet yet; a dimension-tagged gas struct with spill semantics is a clean way to add a gas dimension later, but do not port erigon's `mdgas`-typed `Run` signature as "the EVM API" — stock geth is `uint64` |
| **Standalone published EVM library** | besu (`org.hyperledger.besu:evm`), reth (revm) | Ecosystem reuse; external tooling embedding the interpreter | **OPTIONAL(product)** | A minimal-dependency EVM artifact is valuable if fukuii ever wants an embeddable EVM, but couples you to an external ABI (reth's recurring "integrate revm updates" churn) — a vendored EVM trades shared-fix benefit for stability |

## Best-practice synthesis

fukuii's EVM lives in `vm/OpCode.scala` + `vm/EvmConfig.scala`, with **network-prefixed
fork objects** (`EtcOlympiaOpCodes`/`EtcOlympiaFeeSchedule`, `EthOsakaOpCodes`/the
`EthLondon…→EthOsakaFeeSchedule` chain) and **two `forBlock` overloads** — the 2-arg
block-number form (PoW/ETC) and the 3-arg timestamp form (PoS/ETH).

**DEFAULT (the adopt menu):**

1. **Model each fork's gas schedule as a SUBCLASSABLE strategy object** — besu's
   `GasCalculator` chain, i.e. an `EtcOlympiaGasCalculator` / `EthOsakaGasCalculator`
   overriding only the methods whose costs changed off a shared base, injected into every
   opcode so the logic itself is fork-agnostic. This is the single most transferable JVM
   pattern and the closest structural mirror to fukuii's `vm/`.
2. **Build each fork's opcode table once and cache it on the config** — besu's incremental
   `OperationRegistry` assembly + nethermind's `spec.EvmInstructions ??= GenerateOpCodes(spec)`
   memoization. If `EvmConfig` recomputes opcode/fee availability per instruction, cache the
   resolved table on the `EvmConfig` instance.
3. **Keep a two-layer fork model** — raw-EIP flags → intent-named getters (nethermind's
   `IReleaseSpec` → `ShiftOpcodesEnabled`) so the interpreter never names a fork. This keeps
   `Etc*`/`Eth*` fork names OUT of the interpreter loop and enforces the `scala3-style.md`
   anti-conflation ratchet by construction. For the ETC path, encode the *effective per-EIP
   set at a given block* (core-geth's additive model), never an ETH named-fork wholesale.

Cross-ref the **consensus-engines observation** for fork-dispatch unification: reth's
`ForkCondition{Block,Timestamp,TTD,Never}` (with nethermind `AddTransitions` and besu
`MilestoneType` as runners-up) is the candidate single family-neutral seam that would
collapse fukuii's two `forBlock` overloads — but that is an engine-layer decision; keep the
PoW block-number vs PoS timestamp dispatch axes **distinct** at the EVM layer until it lands.

**OPTIONAL:** a generic / zero-cost tracer (nethermind `IFlag` monomorphization / reth
`Inspector`) for archival/RPC roles — the portable core on the JVM is besu's monomorphic
`NO_TRACING` singleton (branch-free disabled path), not the CLR specialization. Deferred:
multidimensional gas (EIP-8037) — not yet canonical.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

fukuii's `vm/` is the **SR-06** review target (scheduled *after* domain **SR-07**, since
opcode/gas objects depend on the domain types).

**CRITICAL — consensus-critical, dual-authority gate.** Every opcode semantic, gas value,
precompile output, and fork-activation height in `vm/` must be **byte-perfect** against
**core-geth** (ETC/Olympia — `forge` gate) *and* **go-ethereum** (ETH/Osaka — `beacon`
gate). Any change here routes through the Consensus-Critical Change Protocol; forge/beacon
co-sign. Do not hand-edit `vm/` reactively.

- **The adopt:** the besu **gas-strategy-object** + **cached-per-fork opcode table** is the
  structural target for `EvmConfig`/`OpCode` modernization — subclassable per-network gas
  calculators, resolved-once memoized tables, an intent-named getter layer above raw EIP
  flags.
- **Keep the axes distinct:** PoW block-number dispatch (`forBlock(blockNumber, …)`) and PoS
  timestamp dispatch (`forBlock(blockNumber, timestamp, …)`) must stay separate seams until/
  unless the consensus-engines fork-dispatch unification (reth `ForkCondition`) is adopted
  wholesale — geth's single flag-struct folds both because it serves one canonical chain; a
  multi-network client must not conflate them.
- **ETC ≠ named-ETH-fork:** encode Olympia/ECIP as the effective per-EIP set (Agharta omits
  EIP-1283, Mystique omits EIP-1559, Spiral omits EIP-4895) — core-geth's `config_classic.go`
  is the authority, not any ETH fork label.

These are **seeds, not verdicts** — Phase 3/4 decides scope, sequencing, and whether any
structural change to consensus-critical `vm/` is warranted at all.
