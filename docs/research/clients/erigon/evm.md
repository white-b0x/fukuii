# erigon — evm
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon's EVM is genealogically go-ethereum's `core/vm/`, but in this vintage it has been
**relocated to `execution/vm/`** and re-tuned aggressively for allocation reduction and
throughput. The macro-structure is still recognizably geth: a 256-entry jump table of
`*operation` structs selected per fork, a single `Run` interpreter loop that validates the
stack, charges constant then dynamic gas, expands memory, and dispatches `operation.execute`;
a `PrecompiledContract` interface (`RequiredGas` + `Run`); and a geth-compatible
`tracing.Hooks` tracer. On top of that geth skeleton, erigon layers three families of change:
(1) **multidimensional gas (`mdgas`)** threading regular + state gas through every gas
function to implement EIP-8037 state-creation gas; (2) **flat-state-backed state reads** via
`IntraBlockState` over a `StateReader` that returns storage by `(address, key)` directly with
no trie traversal; and (3) **allocation elimination** — `sync.Pool`-pooled call contexts,
`unique.Handle`-interned addresses/storage keys, and an opcode-scoped intern cache.

The EVM is explicitly single-use and not thread-safe (`evm.go:62` "The EVM should never be
reused and is not thread safe"); a fresh `EVM` is built per transaction via `NewEVM`, which
resolves the jump table once from `chainRules` at construction (`evm.go:116`).

## Key types / interfaces / files

- `execution/vm/interpreter.go:376` — `EVM.Run(contract, gas mdgas.MdGas, input, readOnly) (ret, gasRemaining mdgas.MdGas, gasUsed mdgas.MdGasUsage, err)` — the main loop. Note the signature already diverges from geth: gas in/out is `mdgas.MdGas`, not `uint64`.
- `execution/vm/interpreter.go:59` — `CallContext` — erigon's rename/rework of geth's `ScopeContext`. Holds `gas`, `stateGas`, `frameStateUsed` (signed per-frame net state gas), `Memory`, `Stack`, `Contract`, plus the opcode-scoped intern cache fields (`cacheGen`, `cachedKey`, `cachedAddr`).
- `execution/vm/interpreter.go:117` — `contextPool sync.Pool` + `getCallContext`/`put` — call contexts are pooled and reset (`interpreter.go:137`) rather than heap-allocated per frame.
- `execution/vm/interpreter.go:97,108` — `peekStorageKey` / `peekAddress` — opcode-scoped intern cache; `unique.Make` is called at most once per opcode dispatch (gas phase and execute phase share `cacheGen`).
- `execution/vm/interpreter.go:320` — `jumpTable(chainRules, cfg)` — fork→jump-table selector (a `switch` on `chainRules.IsAmsterdam … IsHomestead`), plus `EnableEIP` application for `cfg.ExtraEips` over a copied table (`interpreter.go:356`). Structurally identical to geth's `interpreter.go`.
- `execution/vm/jump_table.go:39` — `operation` struct (`execute`, `constantGas`, `dynamicGas`, `maxStack`, `numPop`, `numPush`, `memorySize`, `string`) — same shape as geth's, but the gas/exec function types (`jump_table.go:29-37`) take/return `mdgas.MdGas`.
- `execution/vm/jump_table.go:57-74` — the 15 per-fork instruction-set singletons, incl. erigon-specific forks `napoliInstructionSet`, `bhilaiInstructionSet`, `amsterdamInstructionSet` interleaved with the ETH mainnet forks.
- `execution/vm/jump_table.go:97` — `newAmsterdamInstructionSet` — builds on Osaka and enables `enable8024` (DUPN/SWAPN/EXCHANGE), `enable7843` (SLOTNUM), `enable8037` (state-gas). `newOsakaInstructionSet` (`:106`) adds `enable7939` (CLZ).
- `execution/vm/evm.go:63` — `EVM` struct — holds `intraBlockState *state.IntraBlockState` (the flat-state StateDB), `jt *JumpTable`, `callGasTemp` (63/64 rule), pooled `hasher keccak.KeccakState`.
- `execution/vm/interface.go:47` — `VMInterface` — external EVM API (`Create`/`Call`/`Reset`/`ChainRules`…). Note the legacy `CallerContext` (`interface.go:35`) still uses `common.Address`/`*big.Int` while the hot path uses interned `accounts.Address`/`uint256.Int`.
- `execution/protocol/mdgas/md_gas.go:28` — `MdGas{Regular, State uint64}` and `MdGasUsage{Regular uint64, State int64}` (signed State for inline refunds). `SplitTxnGasLimit` (`:111`) partitions a tx gas limit into regular + state reservoirs per EIP-8037.
- `execution/vm/gas_table.go` / `execution/vm/operations_acl.go` — dynamic gas functions; ACL (EIP-2929 warm/cold) file drives access-list gas via `evm.IntraBlockState().AddSlotToAccessList(...)` (`operations_acl.go:56`).
- `execution/vm/contracts.go:58` — `PrecompiledContract` interface (`RequiredGas(input) uint64` + `Run(input) ([]byte, error)`) — unchanged from geth.
- `execution/tracing/hooks.go:174` — `tracing.Hooks` struct (`OnTxStart/OnEnter/OnExit/OnOpcode/OnFault/OnGasChange` + `OnBalanceChange/OnStorageChange/…`) with the `OpContext` scope interface (`:33`) — a geth-compatible tracer surface (comment at `:348` even notes matching geth's spelling of a reason constant).
- `execution/state/intra_block_state.go:156` — `IntraBlockState.stateReader StateReader`; the `StateReader` interface exposes `ReadAccountStorage(address, key) (uint256.Int, bool, error)` and `ReadAccountData(address)` — flat, per-key reads with no MPT path.
- `execution/types/accounts/key_types.go:27,114` — `type Address unique.Handle[common.Address]` / `type StorageKey unique.Handle[common.Hash]`, with `InternAddress`/`InternKey` (`:32,:119`) wrapping `unique.Make`.

## Design decisions & rationale

- **Multidimensional gas (EIP-8037).** Instead of a single `uint64` gas counter, erigon threads `mdgas.MdGas{Regular, State}` through the interpreter and every `dynamicGas` function. Regular gas is charged first; a state-gas charge that overflows the per-frame state reservoir *spills* into regular gas (`interpreter.go:518-536`, `useMdGas` at `:206`). `frameStateUsed` (signed) tracks net state gas per frame so inline refunds (SSTORE clear, CREATE collision/revert) can drive it negative when the matching charge lived in an ancestor frame. This is the single biggest structural divergence from geth's EVM and it touches the `Run` signature, all gas-func signatures, and the tx-level gas split.
- **Flat-state StateDB reads.** `IntraBlockState` reads through a `StateReader` whose `ReadAccountStorage(addr, key)` returns the slot value directly. Erigon's storage model is flat (key→value in MDBX / temporal snapshots) rather than a hashed Merkle-Patricia trie walked on every `SLOAD`, so an opcode's state access is a flat lookup with no per-node hashing/decoding on the read path. This is erigon's core throughput and archival advantage.
- **Call-context pooling.** `CallContext` (stack ~32KB + memory) is drawn from a `sync.Pool` and reset on frame exit (`interpreter.go:123,137`), avoiding a large per-call heap allocation in deep call trees.
- **Interned, comparable keys.** `accounts.Address`/`StorageKey` are `unique.Handle` values, so equality and map keys are pointer-cheap and the value is de-duplicated process-wide. The **opcode-scoped intern cache** (`cacheGen` bumped once per dispatch, `interpreter.go:457`) guarantees the gas phase and the execute phase of the same opcode share one `unique.Make` result instead of interning twice.
- **Jump table resolved once, copied only for ExtraEips.** `NewEVM` picks the fork table once (`evm.go:116`); `cfg.ExtraEips` are the only case that copies the table and mutates it (`interpreter.go:357`) — the common path shares an immutable singleton, exactly as geth does.

## Notable patterns (the reusable idea)

- **Generation-counter cache for two-phase dispatch.** Each opcode is processed in a gas phase then an execute phase; both need the same interned top-of-stack key. Rather than intern twice or thread the value through, a monotonically-incremented `cacheGen` marks cache validity for exactly one dispatch (`peekStorageKey`/`peekAddress`). Cheap, allocation-free, and self-invalidating — a clean pattern for any two-pass-over-the-same-input hot loop.
- **Dimension-tagged gas with spill semantics.** Modeling gas as a small struct with a typed charge (`RegularGas`/`StateGas`) and a defined spill order, plus a *signed* usage accumulator for refunds, is a compact way to add a new gas dimension without rewriting the whole accounting core.
- **Jump-table-as-immutable-singleton + copy-on-extend.** Fork sets are prebuilt once at package init; only optional ExtraEips force a per-EVM copy.

## Authority note

go-ethereum is the canonical source for EVM/EIP behavior; erigon is a perf-oriented,
geth-derived variant. Where erigon and geth agree (opcode semantics, precompile gas,
warm/cold access-list rules, the `PrecompiledContract` and `tracing.Hooks` surfaces),
treat go-ethereum as the reference. Where erigon diverges — `mdgas` multidimensional gas,
the flat `StateReader`, interned keys, context pooling — those are **erigon implementation
choices, not consensus authority**; for fukuii's ETC/PoW consensus the authority remains
core-geth, and for ETH/PoS behavior, go-ethereum. Note also that this vintage carries
*proposed/future* EIPs (8037 state gas, 8024, 7843, 7939, plus erigon-internal forks Napoli/
Bhilai/Amsterdam) that are not yet canonical ETH mainnet — do not read their presence as
settled consensus.

## Gotchas / anti-patterns / things they later changed

- **Do not port erigon's `Run`/gas-func signatures as "the EVM API."** They are `mdgas.MdGas`-typed because of EIP-8037; geth's are `uint64`. A fukuii reader comparing gas accounting must know this vintage is a multidimensional-gas branch, not stock geth.
- **Two address representations coexist.** The hot path uses interned `accounts.Address`/`uint256.Int`; the older `CallerContext` interface (`interface.go:35`) still speaks `common.Address`/`*big.Int`. Mixing them incurs conversion; the interned types are the intended fast path.
- **`peekStorageKey`/`peekAddress` are cache-generation-only.** Their own doc-comments warn they will *not* detect a changed stack top within the same opcode — callers must peek *before* any stack mutation in that dispatch. A subtle correctness trap if reused naively.
- **`cost` in the loop is regular-gas-only for tracing.** State gas is deliberately *not* folded into `cost` (`interpreter.go:526-531`) to avoid a `uint64` underflow in the `OnGasChange(gasCopy, gasCopy-cost)` tracer call — a non-obvious coupling between the gas model and the tracer.
- **Fork-name proliferation.** The jump-table `switch` interleaves erigon-internal fork names (Napoli, Bhilai, Amsterdam) with ETH mainnet forks; a naive fork-to-EIP mapping that assumes only canonical ETH fork names will misread this table.
- **EVM is single-use / not thread-safe by contract** (`evm.go:62`). Reusing an `EVM` across transactions is an explicit anti-pattern here.
