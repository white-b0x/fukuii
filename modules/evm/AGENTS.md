# `modules/evm` — L3 subsystem breadcrumb

_The bytecode machine. Depends **down-only** on `domain`, `crypto`, `rlp` — an upward `.dependsOn` or a
`storage`/`trie`/`execution`/`consensus` import is a compile error (grep-clean). Full record:
[`docs/architecture/fukuii-rebuild/implementation-reports/04-L3-evm.md`](../../docs/architecture/fukuii-rebuild/implementation-reports/04-L3-evm.md);
plan: [`plan/L3.md`](../../docs/architecture/fukuii-rebuild/plan/L3.md); byte-cited RX evidence:
[`plan/rx/L3.md`](../../docs/architecture/fukuii-rebuild/plan/rx/L3.md). Read the record before structural
changes here._

## What lives here

The fork-selected bytecode machine: the sealed `OpCode` hierarchy (behavior-bearing `case object`s +
`ConstGas`/`AddrAccessGas`/`StorageAccessGas` traits); the per-fork `given GasCalculator` (fee values +
computation unified); the `ProposalId`/`EvmProposal` fold + `ForkActivation`/`ForkSchedule` seam resolved
by the **single** `EvmConfig.forBlock(header, schedule)`; the dense `IArray[OpCode]` dispatch table +
build-time `validate`; the machine (`Stack`, `Memory`, immutable `MessageFrame`, `CallContext`,
`ExecutionEnv`, `EvmCode`, `ExecutionResult`, `HaltReason`) + the `@tailrec` `EvmInterpreter` (the `VM`
seam impl) with `call`/`create`; the abstract `WorldState`/`AccountStorage` seams the VM is parameterized
over; the precompile wrappers (`PrecompiledContracts`, calling L0 `crypto`); the `ExecutionTracer` hook +
`NoTracing` + the role tracers.

## Invariants (do not break — all byte-consensus)

- **Every change here is consensus-critical.** Route through the Consensus-Critical Change Protocol:
  **forge** (PoW/ETC) + **beacon** (PoS/ETH) co-sign against the **REFERENCE CLIENTS**, byte-cited — never
  against `fukuii/july-fourth` or fukuii's own sets (a self-reference; see
  `.agents/protocols/consensus/reference-client-authority.md` + [[reference-client-not-self-reference]]).
- **`forBlock` is one method over the axis-tagged `ForkActivation` enum** — no second overload, no
  `forTimestamp`, no flat geth-`Rules` struct. The fold iterates `evmApplicationOrder` **filtered by
  set-membership**, never `Set` iteration (non-determinism).
- **Neutral EIP-keyed shared spine.** Gas/opcode shared bases carry no network fork codename (nor their
  scaladoc) — `Eip2929`/`Eip3529`/`Eip3860`/…; fork names only on `Etc*`/`Eth*` **leaves**, and a leaf
  never `extends`/references the other family (`nomenclature.md` ratchet).
- **DAG/R2.** No storage/trie/L5-`BlockchainConfigForEvm` import; `EvmConfig` (incl. the `IArray` table +
  precompile `Map`) is immutable + freely shareable; no `object … { var … }` process-global EVM state.
- **Chain context is on the frame, not the fork-spec.** `chainId`/block/tx context threads through
  `CallContext`/`ExecutionEnv` as L1 `domain` values; `EvmConfig` is the fork-resolved opcode/gas bundle only.
- **Precompiles call the L0 checked crypto primitives** (never re-inline a native lib); input rejected at
  entry, fail-loud. **ETC excludes the `0x0a` KZG precompile** (no EIP-4844).
- **Tracers are observe-only** — a tracer never influences execution/gas/state (result-parity is tested).
- **EIP activation heights** are core-geth `config_classic.go` (ETC) / go-ethereum `config.go` (ETH) —
  the fold's per-fork set is conformance-tested against those literals (`EtcForkHeightConformanceSpec`).

## Gotchas

- Fork dispatch: ETC gates on block number (`ByBlock`), post-merge ETH on timestamp (`ByTimestamp`) —
  distinct `ForkActivation` cases, one `forBlock`.
- `Etc*`/`Eth*` opcode/gas objects never cross-name (a fork codename for the wrong network's byte set is
  a chain split). BLOBHASH/BLOBBASEFEE + KZG `0x0a` are ETH-only (`InvalidOp`/absent on the ETC table).
- EIP-3860 (initcode) is **Spiral/Shanghai**, not Mystique/London; SELFDESTRUCT EIP-6780 "created in tx"
  is a real per-tx `createdAddresses` set (revert-safe), not an `originalWorld.accountExists` proxy — both
  were state-splits transcribed from `july-fourth` and fixed (§3 of the record).
