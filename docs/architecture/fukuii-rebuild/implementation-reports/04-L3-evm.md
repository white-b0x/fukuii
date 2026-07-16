# 04 — L3 `evm` (as-built record)

_As-built record for `modules/evm`, landed after the layer was built + gated. Design-of-record and
per-item RX evidence live in [`plan/L3.md`](../plan/L3.md) + [`plan/rx/L3.md`](../plan/rx/L3.md); the
binding SR slot is [`observations/evm.md`](../../../research/clients/observations/evm.md). Build-status
(commit SHAs) lives only in the [`../README.md`](../README.md) index — never here (docs-future-proof)._

Namespace `com.chipprbots.fukuii.evm.*`; DAG edge **`evm → domain, crypto, rlp`** (down-only; no
`storage`/`trie`/`execution`/`consensus` import — enforced, grep-clean).

---

## 1. Scope

The bytecode machine and everything fork-selected about it: the opcode set, the per-fork gas schedule,
the stack/memory/word machine + `@tailrec` interpreter, `call`(Θ)/`create`(Λ), fork dispatch, the
precompile wrappers, and the tracer hook. It executes **one message**; block/tx orchestration, receipts,
rewards, and the concrete world-state persistence are L4/L2 (see §6). Built in phases P0–P7 (P8 = this
close-out); the two byte-consensus specialists **forge** (PoW/ETC) and **beacon** (PoS/ETH) co-signed
every byte-consensus phase against the **reference clients**, and **eye** gated each independently.

## 2. Design decisions (empirical logic)

- **Sealed `OpCode` hierarchy of `case object`s** carrying `execute` + stack `delta`/`alpha` + gas —
  not a flat `enum` (a flat enum can't carry behavior). besu `AddOperation` is the JVM idiom;
  `ConstGas`/`AddrAccessGas`/`StorageAccessGas` stay `trait`s. (RX-L3-04.)
- **Unified per-fork `given GasCalculator`** — one injected strategy object holding *both* the fee
  values and the gas computation (besu DEFAULT), replacing the split fee-chain + `varGas`/`baseGas`.
  EIP-2929 warm/cold cost lands *inside* the calculator. Siblings override only what changed. (RX-L3-05/09.)
- **`ProposalId` fold as the single production `forBlock(header, schedule)`** — the family-agnostic
  `EvmProposal(opcodeDelta, gasDelta, configDelta)` registry folded in a **pinned chronological order
  filtered by set-membership** (never `Set` iteration — Scala `Set` order is unspecified and same-slot
  deltas are last-wins, so iterating the set is a latent byte-divergence). The two legacy `forBlock`
  overloads are collapsed into one over the axis-tagged `enum ForkActivation{ByBlock,ByTimestamp,
  ByTotalDifficulty,Never}` (reth `ForkCondition` shape; besu `getByBlockHeader`). (RX-L3-11/20; §2.1.)
- **Dense `IArray[OpCode]` dispatch** — all 256 slots pre-filled with an `InvalidOpCode` sentinel at
  build (besu `MainnetEVMs`), plus go-ethereum's build-time `validate` (nil-op + `memorySize⟹dynamicGas`;
  the `delta`/`alpha` presence check is a fukuii addition — geth does not check those). (RX-L3-02/03.)
- **Neutral EIP-keyed shared spine; fork names only on family-local leaves.** The gas and opcode shared
  bases are EIP-keyed (`Eip2929`/`Eip3529`/`Eip3860`/…) — no network fork codename on a framework-level
  symbol *or its comments* (`nomenclature.md`); `Etc*`/`Eth*` appear only on leaves, and a leaf never
  `extends`/references the other family. (Operator-driven; the naming principle is now a `scala3-style`
  ratchet — see [[reference-client-not-self-reference]].)
- **Chain/block/tx context on the per-call environment, not the fork-spec.** `chainId`, `blobBaseFee`,
  `blobHashes`, coinbase/number/timestamp/baseFee/prevRandao thread through `CallContext`/`ExecutionEnv`
  as **L1 `domain` values** (`ChainId` etc.) — matching geth `BlockContext`/`TxContext` + besu
  `MessageFrame`. `EvmConfig` stays the fork-resolved opcode/gas bundle only; **no L5
  `BlockchainConfigForEvm` import** (that coupling was the pre-rebuild mislayering).
- **Precompile wrappers call down into L0 `crypto`.** `0x01–0x09` classic, `0x0b–0x11` BLS12-381 (EIP-2537,
  the **final** 7-precompile layout matching besu-`main`, *not* frozen core-geth's stale 10-precompile
  draft), `0x0100` P256VERIFY, `0x06/07/08` alt-bn128 (subgroup-checked G2 — the F-BN-1 guard),
  `0x0a` KZG. Each is a gas/decode/dispatch shell, input **rejected at entry, fail-loud**; the resolved
  precompile set is a fork-gated immutable `Map` on `EvmConfig`. **ETC excludes `0x0a` KZG** (no EIP-4844;
  confirmed vs ECIP-1121 + besu-`main` `populateForOlympia`). (RX-L3-07/08/23/24.)
- **Branch-free `NoTracing` default; role tracers built.** One `ExecutionTracer` slot; the disabled
  path is elidable (besu `NO_TRACING`). The full `StructLog`/`Call`/`Prestate`/`Vm` role tracers are
  built (P6) and **observe-only** (a result-parity test proves byte-identical execution under any tracer);
  RPC/JSON formatting is L9. (RX-L3-15/16.)
- **Immutable `MessageFrame` `@tailrec` loop** kept — the immutable-vs-mutable-frame throughput question
  is a **benchmark-gated, correctness-neutral OPEN** (§6), deferred to a perf-tuning pass; it does not
  gate the consensus DoD.

## 3. Two consensus state-splits found + fixed (the reference-client lesson)

Both bugs were **transcribed from `fukuii/july-fourth`** (fukuii's own pre-rebuild branch) and initially
survived multiple forge/beacon co-signs + eye gates because those reviews validated against
`july-fourth` and against fukuii's *own* named sets — a self-reference the neutral "AS-IS" label hid.
Re-validating against the **reference clients** caught both. The failure mode is now closed at the
protocol/charter level (`.agents/protocols/consensus/reference-client-authority.md` + forge/beacon/eye
charters). Root cause + rule: [[reference-client-not-self-reference]].

- **EIP-3860 activation height.** Placed at Mystique (14,525,000 ETC) / London (ETH); core-geth
  `EIP3860FBlock = 19_250_000` (Spiral) and go-ethereum activate it at Shanghai. A ~4.7M-block ETC window
  (and the ETH London window) mis-metered initcode gas + wrongly enforced the size limit → state-split.
  **Fix:** `Eip(3860)` → `etcSpiralSet`/`ethShanghaiSet`; a neutral `Eip3860GasCalculator` (G_initcode_word=2)
  at the Shanghai/Spiral level; `Eip3529`/`EthLondon` revert to 0; `EtcOlympia`/`EthCancun` extend `Eip3860`.
- **SELFDESTRUCT EIP-6780 `createdInThisTx`.** Used `!originalWorld.accountExists(ownAddress)` — wrong,
  because `originalWorld` is `initialiseAccount`'d on the create path, so a contract self-destructing in
  its own constructor (the canonical 6780 case) was *not* destroyed; also broke CREATE2 at a pre-funded
  address. Live on ETH Cancun+/ETC-Olympia. **Fix:** a real per-tx **`createdAddresses`** set threaded
  through `MessageFrame`/`CallContext`/`ExecutionResult` (like `accessedAddresses`) — seeded at
  create-frame start, merged into the parent **only on success**, dropped on revert — mirroring geth
  `CreateContract`-before-initcode + journal-revert and besu `addCreate` + `UndoSet.undo`. `originalWorld`
  is untouched (SSTORE EIP-2200 `originalValue` intact). A revert-safety regression test guards the leak.

The `EtcForkHeightConformanceSpec` was **rewritten reference-anchored** — it asserts the fold's per-fork
EIP delta at real heights against **hardcoded core-geth `config_classic.go` / go-ethereum literals**, not
fukuii's own sets (which is what let EIP-3860 through). Fold-identity is re-framed as a *refactor-safety*
check (the fold reproduces the named bundles), not a correctness gate.

## 4. Improvements over old fukuii (from `fukuii/july-fourth`)

One fork-resolution mechanism (the proposal fold owns EIP-2929 gas + precompile selection; the enum-fork
read-path is retired) · one `forBlock(header, schedule)` (two overloads collapsed) · unified
`GasCalculator` (values+computation) · dense `IArray` (was `Map[Byte,OpCode]` with `Option` on the miss
path) · build-time `validate` (was an unvalidated map) · branch-free `NoTracing` (was a two-slot
`Option.foreach`) · positive per-network activation (retired the `isEthereum` binary default, R3) ·
precompiles call the L0 checked crypto (was inline native calls, the B-BLS-1 mislayering) · the two
state-splits above (§3).

## 5. Deferrals + scheduled findings

- **`ethereum/tests` GeneralStateTests → L4.** State-*transition* tests need the tx/ledger pipeline
  (`InMemoryWorldStateProxy` is an L4 `ledger/` concern); the `ets/` harness is an L4 deliverable. L3's
  external validation is the precompile KATs (EIP-196/197/2537/4844/7951/152, all shipped) + the
  reference-anchored fork-height conformance.
- **Immutable-vs-mutable-frame bench (§2/plan §6/§8 OPEN)** — correctness-neutral; a perf-tuning pass
  (needs a throwaway mutable-frame prototype). Immutable `MessageFrame` kept, correctness-proven.
- **R3 finding — `isPoS(header)` create-collision discriminator → per-fork `eip7610Enabled`.** Currently
  byte-correct (EIP-7610 absent from every shipped fork; ETC uses EIP-684, forge-confirmed), but it is a
  binary-family default (the retired anti-pattern). EIP-7610 is **Glamsterdam-CFI** (future ETH), so the
  clean fix (model `Eip(7610)` into the ETH Paris/post-Merge set + gate on `eip7610Enabled`) is
  beacon-owned, scheduled for when that fork is modeled. Moot for ETC.
- **L5 must reverify frozen ETC fork heights** in its `NetworkFamily` `ForkSchedule` (L3's conformance
  uses real core-geth heights but L5 supplies the live schedule).
- **EIP-6049 named-bundle fold drift** — cosmetic (pure-membership EIP, zero EVM-observable effect).
- **Glamsterdam — HELD OFF** (operator-confirmed): EIP-7773 is Draft with no reference-client production
  impl. The `ProposalId` fold makes adding it later free (additive proposals). Forward-map in
  [`plan/L4.md`](../plan/L4.md) "Glamsterdam watch". EIP-8037 (state-creation gas) is already seamed
  (`MessageFrame.stateGasReservoir`, RX-L3-14).

## 6. Layer boundaries (durable placement)

- KZG/BLS/alt-bn128 **primitives** → `crypto` (L0); L3 owns only the precompile *wrappers*.
- Block/tx orchestration, receipts, rewards, system calls, the concrete `WorldState`
  (`InMemoryWorldState`), and the GeneralStateTests harness → `execution` (L4).
- World-state persistence / MPT → `trie`/`storage` (L2), reached through the abstract `WorldState`/
  `AccountStorage` seams the VM is parameterized over (L3 never imports storage).
- *Which* `ForkSchedule` a network is handed → the `NetworkFamily` registry (L5). The activation *seam*
  (`ForkActivation`/`ForkSchedule` + `forBlock(header, schedule)`) is L3's; the *registry* is L5's.
- Precompile-set economics (base-fee floor/burn/treasury, ECIP-1111) → L4/forge. L3 owns the `BASEFEE`
  opcode + header base-fee read only.
- Tracer RPC/JSON formatting (`debug_*`/`trace_*` response shapes) → L9.
