# 05 — L4 `execution` (as-built record)

_As-built record for `modules/execution`, landed after the layer was built + gated. Design-of-record
and per-item RX evidence live in [`plan/L4.md`](../plan/L4.md) + [`plan/rx/L4.md`](../plan/rx/L4.md); the
binding SR slot is [`observations/block-execution.md`](../../../research/clients/observations/block-execution.md).
Build-status (commit SHAs) lives only in the [`../README.md`](../README.md) index — never here
(docs-future-proof)._

Namespace `com.chipprbots.fukuii.execution.*`; DAG edge **`execution → evm, trie, storage, domain`**
(down-only; **no `consensus` import** — enforced, grep-clean). This inverts the pre-rebuild
`ledger ↔ consensus` cycle: **consensus (L5) calls execution**, never the reverse.

---

## 1. Scope

The single-block execution pipeline, family-agnostic above one economics seam: pre-execution system
calls (EIP-4788/2935) → the per-transaction apply loop (`TransactionProcessor`) → withdrawals
(EIP-4895) → post-execution request processing (EIP-6110/7002/7251) → the reward/finalize seam
(`RewardScheme`) → state commitment. Receipt generation (EIP-658 typed receipts), the base-fee
disposition (EIP-1559 burn / ECIP-1111 treasury+floor), and the per-block execution outcome
(`BlockExecutionOutcome` + state-diff) are built here. One executor serves both block import
(verify) and block production (propose) — `BlockProcessor.processBlock` /
`processBlockWithOutcome`. Built in phases P0–P7 (P8 = this close-out); **forge** (PoW/ETC) and
**beacon** (PoS/ETH) co-signed every byte-consensus phase against the **reference clients**, with
**banksy** consulted on ECIP-1111, and **eye** gated each independently.

## 2. Design decisions (empirical logic)

- **`ProtocolSpec` — an immutable per-fork bundle resolved ONCE, wrapping L3's single
  `EvmConfig.forBlock(header, schedule)`.** Replaces the pre-rebuild pattern of re-deriving
  `EvmConfig.forBlock(...)` at 6+ mid-execution call sites. The bundle holds the resolved `EvmConfig`,
  the `RewardScheme`, the `RequestType→processor` map, and the fee/base-fee disposition — L4 never
  re-resolves the fork L3 already resolved (besu `AbstractBlockProcessor.java:230`
  `protocolSchedule.getByBlockHeader(...)`, resolved once at the top of `processBlock`). Scoped to
  fukuii's actually-varying collaborators, not besu's full ~30-field `ProtocolSpec` (RX-L4-02).
- **The reward/finalize seam is the SOLE economics hook, no `if(isPoW)` in the apply loop.** A
  `sealed trait RewardScheme` lives in `execution`; the bundle carries the resolved instance
  (`Ecip1017RewardScheme` for PoW, a zero/no-op scheme for PoS). This is the physical fix for the
  `ledger ↔ consensus` cycle — the reward math lives in `execution`, selected by the bundle, never
  imported from `consensus`; consensus calls `execution.processBlock`, never the reverse. Mirrors besu
  `AbstractBlockProcessor.rewardCoinbase` — the **one** abstract method of an otherwise concrete loop,
  proven by besu-etc's own `ClassicBlockProcessor` overriding only that method for ECIP-1017 (RX-L4-01).
  Scheme selection **fails LOUD** (`sys.error` on an unresolved/mismatched scheme) — never nethermind's
  quiet zero-reward trap.
- **ECIP-1017 era emission as explicit integer arithmetic — core-geth SOLE frozen byte-authority,
  besu-etc a second independent JVM cross-check.** Era index `(blockNumber-1)/eraLength` (integer
  division, not the core-geth `mod`-remainder intermediate); **separate integer exponentiation**
  `4^era` and `5^era` before multiply-divide (never a `BigDecimal.precision` reduction-rate
  derivation); the Era-0-vs-Era-≥1 uncle/nephew formula switch; base = Frontier 5 ETH always (the era
  schedule is the sole reduction — NOT the EIP-649/1234 Byzantium/Constantinople 3/2-ETH cuts).
  core-geth's multiply-then-divide form is the canonical pick (both authorities' formula shapes
  coincide byte-for-byte only because the canonical 5e18 era reward is divisible by 8 and 32 — see
  §3 F-L4-4 and RX-L4-06 for the two-authority divisibility finding).
- **ECIP-1111 base-fee floor + treasury credit (ETC Olympia) vs EIP-1559 burn (ETH) — one
  `FeeDisposition` seam, not a per-network branch scattered through finalize.** ETH burns the base
  fee; ETC redirects it to the treasury with a 1-gwei floor, computed once at finalize
  (`block.gasUsed * block.baseFee`, block-constant, never a per-tx re-derivation). `forge` owns;
  `banksy` is a required consult (the security-budget economics ECIP-1122's tip floor is sized
  against).
- **`RequestType`→processor map (besu `RequestProcessorCoordinator`), `noOp` degradation named
  explicitly.** Two-phase system calls bracket the tx loop: `PreExecution` (4788/2935) before,
  `PostExecution` (withdrawals → requests → reward, besu's deliberate ordering pick) after. The
  degraded PoW/pre-Prague path is the named `noOp()` factory, never a silently-empty `Map()`.
- **Per-block `BlockExecutionOutcome` + state-diff (`BlockStateDiff`, `MutationReason`) — L4's half of
  the R7 reorg-aware event source.** L4 computes and emits the per-block outcome; L5's branch-import
  driver aggregates outcomes into the reorg-aware `ChainNotification` wire ADT (L5 owns reorg
  authority; L4 owns state-diff computation only). Each mutation carries a typed `MutationReason`
  (Reward/FeeBurn/Transfer/SystemCall/…) — L4 owns this type, tagging is near-free when no consumer
  is attached (the hooked wrapper is not installed, geth `Hooks`-shape).
- **Immutable `SimulationOptions` threaded per-call** — replaces the pre-rebuild `@volatile` mutable
  simulation flags on the block preparator. No process-global execution state (R2): two
  `ChainInstance`s in one binary never share mutable execution state.
- **Atomic block+weight write relocated to L2** (see §5 F-L4-6) — `execution` no longer owns a
  provisional `ChainWeight`/`AtomicBlockWriter`; the durability-correctness fix (crash between block
  write and weight write leaves an inconsistent DB) lives at its correct layer, `storage.BlockStore`.

## 3. Four real consensus bugs the reference corpus caught + fixed

All four were caught by validating against the **reference clients** (go-ethereum, core-geth,
besu/besu-etc) and the `ethereum/tests` BlockchainTests corpus — not against `fukuii/july-fourth` or
fukuii's own derived sets, per `reference-client-authority.md`. Full detail in
[`.local/docs/L4-findings-register.md`](../../../../.local/docs/L4-findings-register.md).

- **F-L4-1 — EIP-7702 warm-set ordering + missing EIP-2681 nonce reject (P2, beacon co-sign).** The
  EIP-7702 authorization warm-set was seeded **before** the chain-id / EIP-2681 nonce checks, so a
  wrong-chain-id authority got warmed in fukuii but not in geth/besu — a 2500-gas state-split on any
  Prague+ block using a rejected authorization; EIP-2681's `nonce==2^64-1` reject was also missing.
  **Fixed** `6d2f8153b` — ordering corrected, the nonce-overflow reject added.
- **F-L4-2 — dormant EIP-7825 per-tx 2^24 gas cap on ETH Osaka (P2, beacon co-sign).** L4 applied the
  cap conditionally on `Eip(7825)` being present in the resolved fork's active proposals, but L3's
  `EvmConfig.EthOsaka.activeProposals` never registered it — the cap was silently dormant on mainnet
  Osaka (geth gates it on `rules.IsOsaka`, `state_transition.go:564`, `params.MaxTxGas=1<<24`). A tx
  with `gasLimit > 2^24` would have been wrongly accepted. ETC is unaffected (not an ETC EIP, forge
  confirmed). **Fixed** `d8ef895a8` — a no-op `EvmProposal(Eip(7825))` registered on `EthOsaka` only.
- **F-L4-P7-1 — PREVRANDAO not threaded into the tx `CallContext` post-Merge (P7a, forge co-sign).**
  Post-Merge, opcode `0x44` (PREVRANDAO) must read `header.mixHash`; L4 instead fell back to
  `header.difficulty` (= 0 post-Merge) because prevRandao was never threaded into the per-tx
  `CallContext`. Confirmed against the corpus (`bcExample/mergeExample`, `GasUsedMismatch` Δ19900 +
  `StateRootMismatch`, 44 failing cases) — a real state-root divergence on every post-Merge ETH block
  reading `PREVRANDAO`. **Fixed** `71c595d5c` — a `prevRandao(header)` helper gated on `isPoS`,
  threaded into both the tx and system-call `CallContext`s (corpus 968→1012 passing, +44 exactly).
  forge co-signed: ETC is unaffected, including the ETC-Olympia base-fee edge (the `difficulty==0`
  conjunct gates before the base-fee conjunct, so ETC-Olympia correctly stays on `difficulty`).
- **F-L4-P7-9 — harness chain-id gap masking a real EIP-155 sender-recovery risk (P7d, eye-caught).**
  `BlockchainTestFixture` defaulted `chainId` to `1` (ETH) whenever a test's `config.chainid` field was
  absent — and the **entire** vendored `etc-tests` corpus omits it, so every ETC BlockchainTest ran
  under `ChainId(1)` instead of `ChainId(61)`. Chain ID also feeds EIP-155 sender recovery and
  EIP-2930/1559/7702 checks, so this was a real correctness gap in the harness (not the specFor
  mapping, which eye confirmed correct vs core-geth for every fork; 391 ETC cases were byte-green even
  under the wrong chain ID because most fixtures don't assert `CHAINID`'s return value). Only the 3
  `stChainId` cases surfaced it directly. **Fixed** `f5ea3354a` — a `chainIdFor(network)` derivation
  (`ETC_*`→61 per core-geth `config_classic.go:39`; ETH→`config.chainid`/1) added to the harness.

## 4. Improvements over old fukuii (`fukuii/july-fourth`, v0.8.1-series, `42959353b`)

The `ledger ↔ consensus` import cycle is broken (an upward `import consensus.*` from `execution` is
now a compile error — the structural fix for the 13-package SCC) · the reward-reduction-rate is
explicit integer `4^era`/`5^era` arithmetic (was a `BigDecimal.precision` derivation, flagged `VERIFY:`
in the pre-rebuild snapshot — the top L4 correctness fix) · one immutable `ProtocolSpec` bundle
resolved once (was 6+ mid-execution `EvmConfig.forBlock(...)` re-derivations plus scattered
`forkBlockNumbers.*`/`isXTimestamp(...)` inline predicates) · a data-driven `RequestType→processor`
map with a named `noOp` (was a hard-coded `processPragueSystemCalls` loop) · reward-scheme selection
fails LOUD (was a silent-zero-reward risk class) · atomic block+weight write (was two separate writes
— relocated to its correct layer, L2, per F-L4-6) · config injected via constructor/`using` (no
`ledger ↔ nodebuilder` import cycle) · immutable per-call `SimulationOptions` (was `@volatile` mutable
instance flags) · a per-block `BlockExecutionOutcome` + state-diff feeding a reorg-aware event source
(the pre-rebuild ledger had no such observability seam) · the reward reads `header.isPoS` **per
block**, satisfying the merge-correctness guarantee (nethermind's `MergeRewardCalculator` principle)
without needing a decorator object · no distinct `FinalizeAndAssemble` method (a Mantis-lineage shape
the SR flagged for removal — the single `finalize` seam serves both import and produce).

## 5. Deferrals + scheduled findings

- **F-L4-6 — `ChainWeight`/`AtomicBlockWriter` relocated out of L4.** Built provisionally in
  `execution`, then relocated per the 7-client survey + rebuild plan: the TD **value type** now lives
  at L1 `domain.TotalDifficulty` (`8e53c5565`), the atomic **write primitive** at L2
  `storage.BlockStore.putBlock` (`851811f1f`), with the L4 provisional types deleted (`755ae4c22`).
  The execute→persist orchestration (the import driver that calls `BlockStore.putBlock`) is L5's.
- **F-L4-3/F-L4-5 — blob-tx fee handling, resolved within L4.** F-L4-3 (P2: the upfront balance check
  omitted `blobGas × blobGasFeeCap`) and F-L4-5 (P4b→P5a: the blob-gas **debit**, not just the
  balance check, was missing) are both **resolved** — `CalcBlobFee.blobBaseFee(excessBlobGas)` plus
  the `TransactionProcessor` debit landed in `202fb24bc`.
- **F-L4-P7-5 — a banned `AS-IS` label survives at `EvmInterpreter.scala:288`** (L3 code, forge
  co-sign finding). Non-blocking, correctness-neutral; **scheduled as a mechanical comment fix**, not
  an L4 deliverable (the file is in `modules/evm`).
- **F-L4-P7-2 — invalid-tip/withdrawal-underflow throws instead of a typed `Left[TransactionError]`.**
  Blocks are correctly rejected as `InvalidBlocks`, just via a crash-shaped
  `IllegalArgumentException` rather than a clean error value. **Scheduled** — forge/beacon to decide
  whether to harden the tip-settlement path; low priority (rejection outcome is already correct).
- **F-L4-P7-3 — harness JSON parsing dependency.** The BlockchainTest harness uses jackson-databind
  (already transitively on the classpath); a declared test-scope `circe` dependency may be preferred.
  **Routed to sentinel** (dependency decision, gated) — non-blocking.
- **F-L4-P7-4/P7b — ETC reward-vector + corpus coverage.** The full core-geth `rewards_test.go` era
  vector table is ported into `Ecip1017RewardSchemeSpec`; the `testdata-etc` corpus is vendored as a
  SHA-pinned submodule (`d2c258cf1`) and, because core-geth is deprecating, mirrored to a
  fukuii-controlled repo for longevity (F-L4-P7-10, `github.com/white-b0x/fukuii-etc-tests`, repointed
  `ba5d9aa97`).
- **F-L4-P7-8 — stale CI workflows.** `ci.yml`/`ethereum-tests-nightly.yml` reference the removed
  pre-rebuild `src/it/…/ethtest` tree and the not-yet-built `node`/L10 module. **Out of L4 scope →
  QUEUE.md**, scheduled for the CI-modernization pass once the rebuild reaches L10.
- **F-L4-P7-11 — ETC fork test-coverage audit (missing-forks review).** Confirmed correctly absent
  (Die Hard, Thanos/Etchash — not yet implemented) vs. already covered (Gotham/ECIP-1017). Scheduled
  into the plan: **Spiral V1** (vendor/generate via the corpus's own `makeetc.sh`, top priority — the
  L3 EVM side already exists), **DAO-absence N1** (cheap negative regression), **reward-parity V2**
  (confirm-only), and **Olympia authoring** (fukuii-authored vectors, no external oracle, gated on
  Olympia's cross-layer landing) — all L4; **difficulty/Etchash/MESS** (ECIP-1010/1041/1099/1100) route
  to L5 (`consensus-pow`, unbuilt).
- **Carried from the L4 handoff** — R3 `isPoS(header)` create-collision → per-fork `eip7610Enabled`
  (beacon, Glamsterdam-future, byte-correct today); L5 must reverify the frozen ETC fork heights in
  the live `ForkSchedule` its `NetworkFamily` supplies.
- **Glamsterdam (EIP-7773, Draft) — HELD OFF.** No reference client has a production implementation;
  the `ProposalId` fold makes adding each constituent EIP later free. Layer map (EIP-7708/7778 → L4;
  EIP-7928 BAL → L1+L4+L6) recorded in `plan/L4.md` "Glamsterdam watch".

## 6. Layer boundaries (durable placement)

- **Engine selection, sealing, block-production policy, the Engine-API driver, MESS/ECIP-1100
  subjective fork-choice** → `consensus` (L5). L4 executes and rewards a block; *which* block to build
  on and *whether* to reorg is L5's. consensus calls `execution.processBlock` — never the reverse.
- **Transaction selection/ordering, tip & gas-target floors (ECIP-1122)** → client-layer policy
  (`banksy`, L8 txpool + L5 production). L4 applies the txs it is handed; it does not choose or order
  them.
- **The reorg-aware event source (`ChainNotification`) + its gRPC/dRPC transport + the
  `FinishedHeight` prune barrier** → L5 (the wire ADT) + L9 (transport) + L2 (pruning). L4 emits
  per-block `BlockExecutionOutcome`s (block + state-diff) up to L5's branch-import driver; it does not
  itself decide reorgs or own the segment-level wire type.
- **Opcode/gas machine, precompiles** → `evm` (L3). L4 drives the VM per tx; the fork is resolved once
  at L3 and the L4 `ProtocolSpec` bundle wraps that single resolution.
- **World-state persistence / MPT** → `trie`/`storage` (L2), reached through the `WorldState` seam. L4
  computes the state root via `persistState`; it does not own the trie or RocksDB.
- **TD value type + atomic block+weight write** → `domain` (L1, `TotalDifficulty`) + `storage` (L2,
  `BlockStore.putBlock`) — relocated out of L4 per F-L4-6 (§5).
- **RLP trailing-optional-omission + typed-tx (EIP-2718) aggregation** → `domain` (L1) DoD. L4 is a
  consumer of the fork-variant `BlockHeader`/`Transaction` types, not their definer.
